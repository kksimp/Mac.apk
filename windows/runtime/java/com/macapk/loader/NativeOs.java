// NativeOs -- thin JNI wrapper over the host libc (on Windows, the Bionic
// shim: windows/host/native_os.cpp), backing
// the raw-fd operations of android.system.Os (lseek/close/dup/sysconf/
// memfd_create/statvfs). Raw int fds cross the boundary; android.system.Os
// converts to/from java.io.FileDescriptor and throws ErrnoException.
// Results < 0 from lseek/close/dup carry -errno. See native_os.cpp.
package com.macapk.loader;

public final class NativeOs {
    private NativeOs() {}
    public static native int  open(String path, int flags, int mode);   // new fd, else -errno (flags = bionic O_*)
    public static native long lseek(int fd, long offset, int whence);   // >=0 offset, else -errno
    public static native int  close(int fd);                            // 0, else -errno
    public static native int  dup(int fd);                              // new fd, else -errno
    public static native int  dup2(int oldFd, int newFd);               // newFd, else -errno
    public static native long sysconf(int name);                        // host value (name = bionic _SC_*)
    public static native int  memfdCreate(String name, int flags);      // new fd, else -errno
    public static native long[] fstat(int fd);                          // stat fields, or null
    public static native int  ftruncate(int fd, long length);           // 0, else -errno
    public static native int  takeRunLock(String path);                 // held fd, else -errno (RunLock)
    public static native int  read(int fd, byte[] buf, int off, int count);   // bytes read (0=EOF), else -errno
    public static native int  write(int fd, byte[] buf, int off, int count);  // bytes written, else -errno

    /** Decompress an .xz / LZMA stream with macOS's own Compression framework;
     *  null if the input is not a decodable stream. Java SE ships no XZ decoder,
     *  and a Meta app packs its compiled layouts into one xz blob it addresses by
     *  offset -- see native_os.cpp's NativeOs_xzDecompress and
     *  ResourceIndex.macapkPackedBlobBytes. */
    public static native byte[] xzDecompress(byte[] in);

    // statvfs(3) / fstatvfs(3). Both exist on Darwin with a field-for-field
    // identical struct to Bionic's, so these are the host call, not an
    // emulation. Returned as long[11] in struct-declaration order --
    // {f_bsize, f_frsize, f_blocks, f_bfree, f_bavail, f_files, f_ffree,
    //  f_favail, f_fsid, f_flag, f_namemax} -- or null if the call failed.
    public static native long[] statvfs(String path);                   // fields, or null
    public static native long[] fstatvfs(int fd);                       // fields, or null

    // Process environment. These reach the REAL libc environment, not
    // java.lang.System.getenv()'s immutable JVM-startup snapshot -- native
    // code loaded through Stage 6 reads it with getenv(3), which is the only
    // reason an app calls android.system.Os.setenv at all.
    public static native int    setenv(String name, String value, boolean overwrite); // 0, else -errno
    public static native String getenv(String name);                    // value, or null
    public static native int    unsetenv(String name);                  // 0, else -errno

    // --- java.io.FileDescriptor <-> raw fd (Windows) ---------------------------
    // On Windows a java.io.FileDescriptor holds a HANDLE (its private `handle`
    // field) and its `fd` field stays -1, while native code works with CRT
    // descriptor numbers. These convert by duplicating the handle, so each side
    // owns and closes its own copy (sharing the file position, like dup(2)).
    public static native int  fdForHandle(long handle);                 // new fd, else -errno
    public static native long handleForFd(int fd);                      // new HANDLE, else -1
    public static native void closeHandle(long handle);

    /** Closes `d`'s HANDLE, if it has one, and marks it closed. */
    public static void closeHandleOf(java.io.FileDescriptor d) {
        if (d == null || FD_HANDLE == null) return;
        try {
            synchronized (d) {
                long h = FD_HANDLE.getLong(d);
                if (h == -1) return;
                FD_HANDLE.setLong(d, -1);
                closeHandle(h);
            }
        } catch (Throwable t) {}
    }

    private static final java.lang.reflect.Field FD_INT = field("fd");
    private static final java.lang.reflect.Field FD_HANDLE = field("handle");
    private static java.lang.reflect.Field field(String name) {
        try {
            java.lang.reflect.Field f = java.io.FileDescriptor.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The raw fd native code can use for `d`, or -1. A handle-only descriptor
     *  gets a descriptor number once; it is cached in `d`'s `fd` field, which
     *  the JDK on Windows does not use for I/O. */
    public static int fdOf(java.io.FileDescriptor d) {
        if (d == null || FD_INT == null) return -1;
        try {
            synchronized (d) {
                int n = FD_INT.getInt(d);
                if (n >= 0 || FD_HANDLE == null) return n;
                long h = FD_HANDLE.getLong(d);
                if (h == -1) return -1;
                n = fdForHandle(h);
                if (n < 0) return -1;
                FD_INT.setInt(d, n);
                return n;
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /** A java.io.FileDescriptor for a raw fd, usable by both sides: the number
     *  for native code and a handle of its own for Java's streams. */
    public static java.io.FileDescriptor wrap(int fd) {
        java.io.FileDescriptor d = new java.io.FileDescriptor();
        try {
            if (FD_INT != null) FD_INT.setInt(d, fd);
            if (FD_HANDLE != null && fd >= 0) FD_HANDLE.setLong(d, handleForFd(fd));
        } catch (Throwable t) {}
        return d;
    }
}
