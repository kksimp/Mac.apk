// android.system.Os — POSIX syscall facade. AOSP:
//   https://cs.android.com/android/platform/superproject/main/+/main:libcore/luni/src/main/java/android/system/Os.java
// We bridge stat/lstat/fstat to NIO BasicFileAttributes; signal/sigprocmask
// callers either get an ENOENT-shaped exception or a no-op, whichever is
// safer for the caller. CleanCacheWorker uses lstat to safely traverse
// symlinks under the cache dir; it tolerates ErrnoException.
package android.system;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;

public class Os {
    public Os() {}

    /** lstat — stat the *link* itself, not the target. AOSP throws
     *  ErrnoException on missing. Our impl handles symlinks via
     *  java.nio.file with NOFOLLOW_LINKS. */
    public static StructStat lstat(String path) throws ErrnoException {
        if (path == null) throw new ErrnoException("lstat", OsConstants.ENOENT);
        File f = new File(path);
        try {
            Path p = f.toPath();
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            StructStat s = new StructStat();
            s.st_size  = a.size();
            s.st_mtime = a.lastModifiedTime().toMillis() / 1000L;
            s.st_ctime = a.creationTime().toMillis() / 1000L;
            s.st_atime = a.lastAccessTime().toMillis() / 1000L;
            // Approximate POSIX mode bits: dir=040000, regular=0100000,
            // symlink=0120000; rwx for owner from java.io.File.
            if (a.isSymbolicLink())            s.st_mode |= OsConstants.S_IFLNK;
            else if (a.isDirectory())          s.st_mode |= OsConstants.S_IFDIR;
            else if (a.isRegularFile())        s.st_mode |= OsConstants.S_IFREG;
            if (f.canRead())  s.st_mode |= 0400;
            if (f.canWrite()) s.st_mode |= 0200;
            if (f.canExecute()) s.st_mode |= 0100;
            return s;
        } catch (java.nio.file.NoSuchFileException nse) {
            throw new ErrnoException("lstat", OsConstants.ENOENT);
        } catch (IOException ioe) {
            throw new ErrnoException("lstat", OsConstants.EIO);
        }
    }

    /** stat — like lstat, but follows symlinks. */
    public static StructStat stat(String path) throws ErrnoException {
        if (path == null) throw new ErrnoException("stat", OsConstants.ENOENT);
        File f = new File(path);
        try {
            BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
            StructStat s = new StructStat();
            s.st_size  = a.size();
            s.st_mtime = a.lastModifiedTime().toMillis() / 1000L;
            s.st_ctime = a.creationTime().toMillis() / 1000L;
            s.st_atime = a.lastAccessTime().toMillis() / 1000L;
            if (a.isDirectory())          s.st_mode |= OsConstants.S_IFDIR;
            else if (a.isRegularFile())   s.st_mode |= OsConstants.S_IFREG;
            return s;
        } catch (java.nio.file.NoSuchFileException nse) {
            throw new ErrnoException("stat", OsConstants.ENOENT);
        } catch (IOException ioe) {
            throw new ErrnoException("stat", OsConstants.EIO);
        }
    }

    // --- process environment ---------------------------------------------
    // AOSP (libcore/luni/src/main/java/android/system/Os.java, verified
    // against LineageOS/android_libcore lineage-21.0):
    //   :232  public static String getenv(String name) { return Libcore.os.getenv(name); }
    //   :675  public static void setenv(String name, String value, boolean overwrite) throws ErrnoException
    //   :973  public static void unsetenv(String name) throws ErrnoException
    //
    // setenv RETURNS VOID. Ours previously returned int, so an app compiled
    // against the real SDK emits `invokestatic ...(Ljava/lang/String;
    // Ljava/lang/String;Z)V` and got NoSuchMethodError -- the return type is
    // part of the method descriptor. Measured 2026-07-30: that aborted
    // AnkiDroid's AnkiDroidApp.onCreate, so its late-set singletons stayed
    // null and its IntentHandler trampoline could not route onward.
    //
    // These route to the environment the app's NATIVE code reads (NativeOs ->
    // native_os.cpp -> the shim's __macapk_guest_setenv/getenv/unsetenv, the
    // guest's Bionic `environ`; gate MACAPK_GUEST_ENV_MUTABLE, whose 0 is host
    // setenv(3) as before), not to System.getenv(): the JVM's env map is an
    // immutable startup snapshot that native code cannot observe, and
    // configuring native code is the whole purpose of this API.
    public static String getenv(String name) {
        if (name == null) return null;
        try { return com.macapk.loader.NativeOs.getenv(name); }
        catch (Throwable t) { return System.getenv(name); }
    }

    public static void setenv(String name, String value, boolean overwrite) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.setenv(name, value, overwrite);
        if (r < 0) throw new ErrnoException("setenv", -r);
    }

    public static void unsetenv(String name) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.unsetenv(name);
        if (r < 0) throw new ErrnoException("unsetenv", -r);
    }
    public static String gethostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Throwable t) { return "localhost"; }
    }
    public static int getuid() { return 1000; }
    public static int getgid() { return 1000; }
    public static int getpid() { return (int) ProcessHandle.current().pid(); }
    /** AOSP Os.gettid (libcore Linux_gettid -> gettid(2)); android.os.Process.myTid()
     *  is defined as this on Android, so both answer the same id here. Play Store's
     *  signed-out screen called it from its main thread (NoSuchMethodError). */
    public static int gettid() { return android.os.Process.myTid(); }

    /** AOSP Os.prctl (libcore Linux_prctl -> prctl(2)). macOS has no prctl, and
     *  the options apps reach it for are Linux process attributes with no host
     *  equivalent -- PR_SET_DUMPABLE (toggles core-dump/ptrace eligibility, which
     *  Google Play services' Application sets to 1 to "enable native stack dump
     *  capture") and the like. Report success (0) the way the syscall would on a
     *  device so the caller proceeds; we do not actually change the host
     *  process's dumpability (and must not make ourselves non-debuggable).
     *  PR_GET_DUMPABLE answers the Linux default (1 = dumpable). This is a
     *  faithful no-op surface, not a bypass: no anti-debug is defeated, because
     *  the caller here is ENABLING dumpability, not hardening. */
    public static int prctl(int option, long arg2, long arg3, long arg4, long arg5)
            throws ErrnoException {
        if (option == OsConstants.PR_GET_DUMPABLE) return 1;
        return 0;
    }

    /** AOSP Os.chmod (libcore Linux_chmod -> chmod(2)). macOS is POSIX, so set
     *  the mode's owner/group/other rwx bits through java.nio. Only the low 9
     *  permission bits map to PosixFilePermission (setuid/setgid/sticky have no
     *  portable Files API and are irrelevant to the app-private dirs apps chmod);
     *  Google Play services chmods its own data dirs during attachBaseContext. */
    public static void chmod(String path, int mode) throws ErrnoException {
        if (path == null) throw new ErrnoException("chmod", OsConstants.ENOENT);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
        java.nio.file.attribute.PosixFilePermission[] bits = {
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        };
        for (int i = 0; i < 9; i++) if ((mode & (1 << i)) != 0) perms.add(bits[i]);
        try {
            java.nio.file.Files.setPosixFilePermissions(
                    new java.io.File(path).toPath(), perms);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ErrnoException("chmod", OsConstants.ENOENT);
        } catch (java.io.IOException e) {
            throw new ErrnoException("chmod", OsConstants.EIO);
        }
    }

    /** AOSP Os.readlink (API 21): the target path of a symlink. libcore's
     *  Linux.readlink is readlink(2) (libcore/luni/src/main/native/
     *  libcore_io_Linux.cpp Linux_readlink: readlink(), throwing ErrnoException
     *  "readlink" on failure), so a path that is NOT a symlink fails with EINVAL,
     *  a missing one with ENOENT, a path through a regular file with ENOTDIR.
     *  This used to hand back the path itself for all three, which is a
     *  different, wrong fact: RetroArch's RetroActivityCommon.cleanupSymlinks
     *  deletes every file in its cores dir whose readlink does not throw, so
     *  each launch deleted every core the user had downloaded.
     *  MACAPK_OS_READLINK_ERRNO=0 restores the old answer. */
    public static String readlink(String path) throws android.system.ErrnoException {
        if (!READLINK_ERRNO) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(path);
                if (java.nio.file.Files.isSymbolicLink(p)) {
                    return java.nio.file.Files.readSymbolicLink(p).toString();
                }
                return path;
            } catch (Exception e) {
                return path;
            }
        }
        if (path == null) throw new NullPointerException("path == null");
        final java.nio.file.Path p;
        try {
            p = java.nio.file.Paths.get(path);
        } catch (java.nio.file.InvalidPathException e) {
            throw new ErrnoException("readlink", OsConstants.ENOENT);
        }
        try {
            if (java.nio.file.Files.isSymbolicLink(p)) {
                return java.nio.file.Files.readSymbolicLink(p).toString();
            }
            if (java.nio.file.Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                throw new ErrnoException("readlink", OsConstants.EINVAL);
            }
            // Missing: ENOTDIR when an existing ancestor is not a directory
            // (path resolution fails there), otherwise ENOENT.
            for (java.nio.file.Path a = p.getParent(); a != null; a = a.getParent()) {
                if (java.nio.file.Files.exists(a)) {
                    if (!java.nio.file.Files.isDirectory(a)) {
                        throw new ErrnoException("readlink", OsConstants.ENOTDIR);
                    }
                    break;
                }
            }
            throw new ErrnoException("readlink", OsConstants.ENOENT);
        } catch (java.nio.file.AccessDeniedException e) {
            throw new ErrnoException("readlink", OsConstants.EACCES);
        } catch (IOException e) {
            throw new ErrnoException("readlink", OsConstants.EIO);
        }
    }

    private static final boolean READLINK_ERRNO =
        !"0".equals(System.getenv("MACAPK_OS_READLINK_ERRNO"));


    // --- raw-fd operations (JNI via com.macapk.loader.NativeOs -> Darwin libc). ---
    // The int fd lives in java.io.FileDescriptor's private `fd` field; the runtime
    // launches HotSpot with --add-opens=java.base/java.io=ALL-UNNAMED so reflection
    // reaches it. Added v735.
    private static final java.lang.reflect.Field FD_FIELD = fdField();
    private static java.lang.reflect.Field fdField() {
        try { java.lang.reflect.Field f = java.io.FileDescriptor.class.getDeclaredField("fd");
              f.setAccessible(true); return f; } catch (Throwable t) { return null; }
    }
    private static int fdOf(java.io.FileDescriptor fd) {
        if (fd == null || FD_FIELD == null) return -1;
        try { return FD_FIELD.getInt(fd); } catch (Throwable t) { return -1; }
    }
    private static java.io.FileDescriptor newFd(int fd) {
        java.io.FileDescriptor f = new java.io.FileDescriptor();
        try { if (FD_FIELD != null) FD_FIELD.setInt(f, fd); } catch (Throwable t) {}
        return f;
    }

    /** AOSP libcore Os.open (libcore/luni/src/main/java/android/system/Os.java):
     *  "public static FileDescriptor open(String path, int flags, int mode)
     *   throws ErrnoException". `flags` carries the caller's bionic O_* bits;
     *  the native side translates them to Darwin's before open(2). Returns a
     *  FileDescriptor wrapping the raw fd, matching the framework contract that
     *  callers pass to Os.close / Os.read / new FileInputStream(fd). */
    public static java.io.FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.open(path, flags, mode);
        if (r < 0) throw new ErrnoException("open", -r);
        return newFd(r);
    }
    public static long lseek(java.io.FileDescriptor fd, long offset, int whence) throws ErrnoException {
        long r = com.macapk.loader.NativeOs.lseek(fdOf(fd), offset, whence);
        if (r < 0) throw new ErrnoException("lseek", (int)(-r));
        return r;
    }

    /** AOSP libcore Os.read (libcore/luni/src/main/java/android/system/Os.java):
     *  "public static int read(FileDescriptor fd, byte[] bytes, int byteOffset,
     *   int byteCount) throws ErrnoException, InterruptedIOException" -- a raw
     *  read(2) that returns the number of bytes read (0 at EOF), NOT owning the
     *  fd. Backed by NativeOs.read (native_os.cpp), which retries EINTR. */
    public static int read(java.io.FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount)
            throws ErrnoException, java.io.InterruptedIOException {
        int r = com.macapk.loader.NativeOs.read(fdOf(fd), bytes, byteOffset, byteCount);
        if (r < 0) throw new ErrnoException("read", -r);
        return r;
    }

    /** AOSP libcore Os.read(FileDescriptor, ByteBuffer): reads into the buffer's
     *  remaining() region and advances its position by the count read. Heap
     *  buffers read straight into the backing array; direct buffers stage
     *  through a temporary (the same split IoBridge uses). */
    public static int read(java.io.FileDescriptor fd, java.nio.ByteBuffer buffer)
            throws ErrnoException, java.io.InterruptedIOException {
        int pos = buffer.position();
        int rem = buffer.remaining();
        if (rem == 0) return 0;
        int n;
        if (buffer.hasArray()) {
            n = read(fd, buffer.array(), buffer.arrayOffset() + pos, rem);
        } else {
            byte[] tmp = new byte[rem];
            n = read(fd, tmp, 0, rem);
            if (n > 0) buffer.put(tmp, 0, n);
        }
        if (n > 0 && buffer.hasArray()) buffer.position(pos + n);
        return n;
    }

    /** AOSP libcore Os.write: a raw write(2) returning the number of bytes
     *  written (may be short); the fd is not owned. Backed by NativeOs.write. */
    public static int write(java.io.FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount)
            throws ErrnoException, java.io.InterruptedIOException {
        int r = com.macapk.loader.NativeOs.write(fdOf(fd), bytes, byteOffset, byteCount);
        if (r < 0) throw new ErrnoException("write", -r);
        return r;
    }

    /** AOSP libcore Os.write(FileDescriptor, ByteBuffer): writes the buffer's
     *  remaining() region and advances its position by the count written. */
    public static int write(java.io.FileDescriptor fd, java.nio.ByteBuffer buffer)
            throws ErrnoException, java.io.InterruptedIOException {
        int pos = buffer.position();
        int rem = buffer.remaining();
        if (rem == 0) return 0;
        int n;
        if (buffer.hasArray()) {
            n = write(fd, buffer.array(), buffer.arrayOffset() + pos, rem);
        } else {
            byte[] tmp = new byte[rem];
            buffer.get(tmp);          // advances position by rem
            buffer.position(pos);     // restore; we advance by the actual count below
            n = write(fd, tmp, 0, rem);
        }
        if (n > 0) buffer.position(pos + n);
        return n;
    }
    public static void close(java.io.FileDescriptor fd) throws ErrnoException {
        int raw = fdOf(fd);
        int r = com.macapk.loader.NativeOs.close(raw);
        if (r < 0) throw new ErrnoException("close", -r);
        try { if (FD_FIELD != null) FD_FIELD.setInt(fd, -1); } catch (Throwable t) {}
    }
    public static java.io.FileDescriptor dup(java.io.FileDescriptor fd) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.dup(fdOf(fd));
        if (r < 0) throw new ErrnoException("dup", -r);
        return newFd(r);
    }
    public static java.io.FileDescriptor dup2(java.io.FileDescriptor oldFd, int newFd) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.dup2(fdOf(oldFd), newFd);
        if (r < 0) throw new ErrnoException("dup2", -r);
        return newFd(r);
    }
    public static long sysconf(int name) {
        return com.macapk.loader.NativeOs.sysconf(name);
    }
    public static java.io.FileDescriptor memfd_create(String name, int flags) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.memfdCreate(name, flags);
        if (r < 0) throw new ErrnoException("memfd_create", -r);
        return newFd(r);
    }

    public static void symlink(String oldPath, String newPath) throws ErrnoException {
        try { java.nio.file.Files.createSymbolicLink(java.nio.file.Paths.get(newPath), java.nio.file.Paths.get(oldPath)); }
        catch (java.nio.file.FileAlreadyExistsException e) { throw new ErrnoException("symlink", OsConstants.EEXIST); }
        catch (Exception e) { throw new ErrnoException("symlink", OsConstants.EIO); }
    }

    /** mkfifo -- create a POSIX FIFO (named pipe) at path with permission
     *  bits mode. AOSP delegates straight to the mkfifo(3) libc call:
     *  "public static void mkfifo(String path, int mode) throws
     *  ErrnoException { Libcore.os.mkfifo(path, mode); }" (libcore
     *  luni/src/main/java/android/system/Os.java, android11-release, at
     *  the "See mkfifo(3)" javadoc).
     *
     *  NativeOs (com.macapk.loader.NativeOs, backed by native_os.cpp) has
     *  no raw mkfifo(2) binding -- its raw-fd surface covers lseek/close/
     *  dup/dup2/sysconf/memfd_create/fstat/ftruncate only -- and adding one
     *  means touching native_os.cpp plus a rebuild, both outside this
     *  file's scope. java.nio.file.Files has no FIFO-creation API either
     *  (regular files/dirs/symlinks only, unlike symlink() above), so
     *  there is no pure-JDK path.
     *
     *  macOS ships the real mkfifo(1) utility at /usr/bin/mkfifo, a thin
     *  wrapper over the identical mkfifo(2) syscall AOSP calls; "-m mode"
     *  sets the exact permission bits at creation time (ignoring umask),
     *  matching an explicit mode argument's intent. This produces a real,
     *  fully POSIX-compliant FIFO -- not an approximation of one -- via
     *  the same "shell a host utility" idiom already used elsewhere in
     *  this framework (e.g. android.os.BatteryManager's /usr/bin/pmset
     *  call, android.net.TrafficStats). */
    public static void mkfifo(String path, int mode) throws ErrnoException {
        if (path == null) throw new ErrnoException("mkfifo", OsConstants.ENOENT);
        try {
            String modeArg = Integer.toOctalString(mode & 07777);
            Process p = new ProcessBuilder("/usr/bin/mkfifo", "-m", modeArg, path)
                    .redirectErrorStream(true).start();
            if (p.waitFor() != 0) {
                // mkfifo(1) gives no machine-readable errno back, only an exit
                // code; EEXIST is overwhelmingly the real-world case (a setup
                // step re-run against a FIFO it already created), so surface
                // that specifically rather than a bare "something failed" an
                // app cannot branch on the way it would branch on a real errno.
                throw new ErrnoException("mkfifo", new File(path).exists() ? OsConstants.EEXIST : OsConstants.EIO);
            }
        } catch (ErrnoException e) {
            throw e;
        } catch (IOException e) {
            throw new ErrnoException("mkfifo", OsConstants.EIO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrnoException("mkfifo", OsConstants.EIO);
        }
    }
    public static StructUtsname uname() {
        return new StructUtsname("Linux",
            System.getProperty("user.name", "localhost"),
            System.getProperty("os.version", "0.0.0"),
            "#1 SMP", "aarch64");
    }

    public static StructStat fstat(java.io.FileDescriptor fd) throws ErrnoException {
        long[] v = com.macapk.loader.NativeOs.fstat(fdOf(fd));
        if (v == null) throw new ErrnoException("fstat", OsConstants.EBADF);
        StructStat s = new StructStat();
        s.st_dev = v[0]; s.st_ino = v[1]; s.st_mode = (int) v[2]; s.st_nlink = v[3];
        s.st_size = v[4]; s.st_atime = v[5]; s.st_mtime = v[6]; s.st_ctime = v[7];
        s.st_blksize = v[8]; s.st_blocks = v[9]; s.st_rdev = v[10];
        return s;
    }
    public static void posix_fallocate(java.io.FileDescriptor fd, long offset, long length) throws ErrnoException {
        int r = com.macapk.loader.NativeOs.ftruncate(fdOf(fd), offset + length);
        if (r < 0) throw new ErrnoException("posix_fallocate", -r);
    }

    /** statvfs(2). AOSP:
     *    public static StructStatVfs statvfs(String path) throws ErrnoException
     *        { return Libcore.os.statvfs(path); }
     *  (libcore/luni/src/main/java/android/system/Os.java:915)
     *
     *  Backed by the host's own statvfs(3) through NativeOs -- Darwin's
     *  struct statvfs is field-for-field identical to Bionic's, so the numbers
     *  are exact rather than derived. Deliberately NOT routed through
     *  java.nio.file.FileStore: that reports total/usable/unallocated BYTES and
     *  a block size, so f_blocks/f_bfree/f_bavail would have to be back-divided
     *  (lossy) and f_files/f_ffree/f_namemax would have to be invented.
     *
     *  Found by the 2026-08-27 fleet census: NewPipe references
     *  Os.statvfs/fstatvfs + StructStatVfs and we provided none of the three. */
    public static StructStatVfs statvfs(String path) throws ErrnoException {
        if (path == null) throw new ErrnoException("statvfs", OsConstants.ENOENT);
        long[] v = com.macapk.loader.NativeOs.statvfs(path);
        if (v == null) throw new ErrnoException("statvfs", OsConstants.ENOENT);
        return structStatVfsOf(v);
    }

    /** fstatvfs(2). AOSP: libcore Os.java:197, same delegation to Libcore.os. */
    public static StructStatVfs fstatvfs(java.io.FileDescriptor fd) throws ErrnoException {
        long[] v = com.macapk.loader.NativeOs.fstatvfs(fdOf(fd));
        if (v == null) throw new ErrnoException("fstatvfs", OsConstants.EBADF);
        return structStatVfsOf(v);
    }

    /** long[11] in struct-declaration order -> StructStatVfs. The order is
     *  pinned on the native side (native_os.cpp statvfs_to_array); keep the two
     *  in step if either changes. */
    private static StructStatVfs structStatVfsOf(long[] v) {
        return new StructStatVfs(v[0], v[1], v[2], v[3], v[4], v[5],
                                 v[6], v[7], v[8], v[9], v[10]);
    }
}



