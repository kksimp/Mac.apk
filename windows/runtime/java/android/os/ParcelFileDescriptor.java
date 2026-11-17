package android.os;

import java.io.FileDescriptor;

// ParcelFileDescriptor -- AOSP wraps an open file descriptor so it can be
// handed across binder + back to native code. Mac.apk only needs the
// "carry a real fd + size to native code" half: Unity's
// UnityPlayerForActivityOrService.nativeRender calls
// AssetManager.openFd(...).getParcelFileDescriptor() and then, on the native
// side, reads the integer fd out of the FileDescriptor (via JNI GetIntField,
// which bypasses module access checks) to mmap the asset. So getFileDescriptor()
// MUST return a real, still-open FileDescriptor; getFd() returns the integer
// for the few callers that take the Java path.
// AOSP: https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/os/ParcelFileDescriptor.java
public class ParcelFileDescriptor implements Parcelable, java.io.Closeable {
    private FileDescriptor fd;
    // Keep the backing stream referenced so its fd is not closed/GC'd out from
    // under the consumer before it has mmap'd or dup'd it.
    private java.io.Closeable owner;
    private long size;

    // ---- reliable-channel state (AOSP calls this the "comm" fd) ------------
    // On a device a reliable PFD carries a second, socketpair fd: each end
    // WRITES its own close status to its comm fd, and the peer READS it, which
    // is how checkError() distinguishes a clean EOF from a crash. Mac.apk is
    // single-process, so both ends of every such pair live here and the status
    // can be carried in a shared object instead of through a socket -- same
    // observable protocol, no kernel round trip. A PFD with mComm == null is
    // the plain (unreliable) kind, exactly as on a device.
    private Comm mComm;        // where THIS end reports its own status
    private Comm mPeerComm;    // where the OTHER end reports; what checkError reads
    private OnCloseListener mCloseListener;
    private Handler mCloseHandler;
    private boolean mReportedClose;

    private static final class Comm {
        static final int OK = 0, ERROR = 1, DEAD = 2;
        volatile int status = -1;     // -1 == nothing reported yet
        volatile String msg;
        volatile boolean detached;
    }

    public ParcelFileDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor != null) {
            this.fd = descriptor.fd;
            this.owner = descriptor.owner;
            this.size = descriptor.size;
            // AOSP's copy constructor wraps the source PFD, so the wrapper
            // answers canDetectErrors()/checkError() by delegating to it.
            // Sharing the comm objects is the same relationship here.
            this.mComm = descriptor.mComm;
            this.mPeerComm = descriptor.mPeerComm;
        }
    }

    public ParcelFileDescriptor(FileDescriptor fd) { this.fd = fd; }

    // Mac.apk helper: build a PFD around a real fd plus the stream that owns it
    // (so close() releases it) and the byte size for getStatSize().
    public static ParcelFileDescriptor macapkWrap(FileDescriptor fd,
                                                    java.io.Closeable owner,
                                                    long size) {
        ParcelFileDescriptor p = new ParcelFileDescriptor(fd);
        p.owner = owner;
        p.size = size;
        return p;
    }

    // AOSP open mode flags (subset apps actually pass).
    public static final int MODE_READ_ONLY = 0x10000000;
    public static final int MODE_WRITE_ONLY = 0x20000000;
    public static final int MODE_READ_WRITE = 0x30000000;
    public static final int MODE_CREATE = 0x08000000;
    public static final int MODE_TRUNCATE = 0x04000000;
    public static final int MODE_APPEND = 0x02000000;
    public static final int MODE_WORLD_READABLE = 0x00000001;
    public static final int MODE_WORLD_WRITEABLE = 0x00000002;

    // AOSP parseMode(String): converts a ContentResolver-style mode string
    // ("r", "w", "wt", "wa", "rw", "rwt") into the MODE_* bitmask open(File,int)
    // consumes. Real AOSP's body is one line --
    //   return FileUtils.translateModePosixToPfd(FileUtils.translateModeStringToPosix(mode));
    // (frameworks/base/core/java/android/os/ParcelFileDescriptor.java, fetched
    // 2026-07-31) -- and composing the two FileUtils steps it calls
    // (frameworks/base/core/java/android/os/FileUtils.java, same fetch) gives
    // exactly the folded-together logic below: "rw" prefix -> READ_WRITE|CREATE
    // (O_RDWR|O_CREAT), "w" prefix (not "rw") -> WRITE_ONLY|CREATE
    // (O_WRONLY|O_CREAT), else ("r" prefix) -> READ_ONLY (O_RDONLY); 't' anywhere
    // in the string adds TRUNCATE (O_TRUNC), 'a' anywhere adds APPEND (O_APPEND).
    // AOSP's translateModeStringToPosix throws IllegalArgumentException on an
    // empty/invalid mode string; we default to MODE_READ_ONLY instead so a
    // caller that passes a stray mode degrades to a safe read rather than a
    // crash (the same leniency MediaStoreProvider.java's private copy of this
    // exact algorithm already uses -- this is the shared, public home for it;
    // that copy is not a per-APK patch, just pre-existing duplication).
    public static int parseMode(String mode) {
        if (mode == null || mode.isEmpty()) return MODE_READ_ONLY;
        int res;
        if (mode.startsWith("rw")) {
            res = MODE_READ_WRITE | MODE_CREATE;
        } else if (mode.startsWith("w")) {
            res = MODE_WRITE_ONLY | MODE_CREATE;
        } else {
            res = MODE_READ_ONLY;
        }
        if (mode.indexOf('t') >= 0) res |= MODE_TRUNCATE;
        if (mode.indexOf('a') >= 0) res |= MODE_APPEND;
        return res;
    }

    // AOSP open(File,int): open a real file and wrap its fd. We map the Android
    // MODE_* bits onto a JDK RandomAccessFile mode ("r" / "rw"); CREATE/TRUNCATE
    // are honored by RandomAccessFile("rw") semantics (it creates) plus an
    // explicit truncate. The backing RandomAccessFile is the owner so close()
    // releases the fd.
    public static ParcelFileDescriptor open(java.io.File file, int mode) throws java.io.FileNotFoundException {
        if (file == null) throw new java.io.FileNotFoundException("null file");
        // AOSP FileUtils.translateModePfdToPosix(int) (frameworks/base/core/
        // java/android/os/FileUtils.java, fetched 2026-07-31) checks
        // `(mode & MODE_READ_WRITE) == MODE_READ_WRITE` FIRST (an EQUALITY
        // test against the full flag -- BOTH bits present), then
        // `(mode & MODE_WRITE_ONLY) == MODE_WRITE_ONLY`, then falls back to
        // MODE_READ_ONLY. That equality-not-nonzero distinction is load-
        // bearing: MODE_READ_WRITE is DEFINED as
        // MODE_READ_ONLY | MODE_WRITE_ONLY (0x30000000 = 0x10000000 |
        // 0x20000000, see the constants above), so MODE_READ_ONLY's own bit
        // (0x10000000) is already a SUBSET of MODE_READ_WRITE's bit pattern.
        // The previous body here --
        // `(mode & (MODE_WRITE_ONLY | MODE_READ_WRITE)) != 0` -- was a bare
        // nonzero test, which matches on that shared bit: a caller passing
        // PLAIN MODE_READ_ONLY (no other bits at all) was wrongly classified
        // as wanting write access.
        // MEASURED (Fossify Gallery bring-up, 2026-07-31): Glide requests
        // mode "r" for a local file: URI -- ContentResolver
        // .openAssetFileDescriptor -> ParcelFileDescriptor.parseMode("r") =
        // MODE_READ_ONLY, no other bits -- and this method's old check
        // treated that as write, opened a JDK RandomAccessFile in "rw", and
        // macOS threw FileNotFoundException("... (Permission denied)") for
        // every world-readable-but-not-writable file (e.g. r--r--r--
        // fixtures) -- which is most files an app only ever reads. Generic:
        // any caller of ContentResolver.openFileDescriptor/
        // openAssetFileDescriptor on a file: Uri with mode "r" hit this.
        boolean write = (mode & MODE_READ_WRITE) == MODE_READ_WRITE
                      || (mode & MODE_WRITE_ONLY) == MODE_WRITE_ONLY;
        String raf = write ? "rw" : "r";
        try {
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(file, raf);
            if (write && (mode & MODE_TRUNCATE) != 0) {
                try { r.setLength(0); } catch (java.io.IOException ignored) {}
            }
            if (write && (mode & MODE_APPEND) != 0) {
                try { r.seek(r.length()); } catch (java.io.IOException ignored) {}
            }
            java.io.FileDescriptor d = r.getFD();
            return macapkWrap(d, r, file.length());
        } catch (java.io.FileNotFoundException fnf) {
            throw fnf;
        } catch (java.io.IOException io) {
            throw new java.io.FileNotFoundException(io.getMessage());
        }
    }

    // AOSP dup(): duplicate this descriptor. We have no os-level dup primitive
    // available portably from pure Java, so we return a PFD that shares the same
    // underlying fd but does NOT own the stream (so closing the dup does not
    // close the original). This preserves the "two handles to the same open
    // file" contract for in-process readers.
    public ParcelFileDescriptor dup() throws java.io.IOException {
        ParcelFileDescriptor p = new ParcelFileDescriptor(this.fd);
        p.size = this.size;
        // owner left null: the dup does not own the backing stream.
        return p;
    }

    public static ParcelFileDescriptor dup(java.io.FileDescriptor orig) throws java.io.IOException {
        return new ParcelFileDescriptor(orig);
    }

    // AOSP createPipe(): returns {readEnd, writeEnd}. We back it with a JDK
    // Pipe (java.nio.channels.Pipe) and expose each end's FileDescriptor.
    public static ParcelFileDescriptor[] createPipe() throws java.io.IOException {
        java.nio.channels.Pipe pipe = java.nio.channels.Pipe.open();
        java.io.FileDescriptor readFd = channelFd(pipe.source());
        java.io.FileDescriptor writeFd = channelFd(pipe.sink());
        ParcelFileDescriptor[] out = new ParcelFileDescriptor[2];
        out[0] = macapkWrap(readFd, pipe.source(), 0);
        out[1] = macapkWrap(writeFd, pipe.sink(), 0);
        return out;
    }

    // Reliable pipe has the same shape in our model (no cross-process death
    // detection to add).
    public static ParcelFileDescriptor[] createReliablePipe() throws java.io.IOException {
        return createPipe();
    }

    // AOSP fromDatagramSocket(DatagramSocket): wrap the socket's underlying fd
    // (AOSP itself does this via a hidden DatagramSocket.getFileDescriptor$()
    // only Android's own DatagramSocket class exposes). The host JDK's
    // java.net.DatagramSocket has no such API, so we reflect the fd out of its
    // NIO-backed implementation instead: since JDK 13's java.net rearchitecture
    // (JDK-8236925) a plain `new DatagramSocket()` delegates to a
    // sun.nio.ch.DatagramSocketAdaptor holding a DatagramChannelImpl in a `dc`
    // field, while a socket obtained via DatagramChannel.open().socket()
    // exposes that channel directly through getChannel(). Either way the
    // channel impl holds a private java.io.FileDescriptor `fd` field, exactly
    // like the Pipe channels channelFd() below already reflects. Verified
    // empirically against this project's JDK 21 (both paths probed live).
    // Returns null when no fd can be resolved, matching AOSP's own contract
    // (fromDatagramSocket returns null when the socket's fd is null).
    public static ParcelFileDescriptor fromDatagramSocket(java.net.DatagramSocket datagramSocket) {
        if (datagramSocket == null) return null;
        java.io.FileDescriptor d = datagramSocketFd(datagramSocket);
        return (d != null) ? macapkWrap(d, null, 0) : null;
    }

    private static java.io.FileDescriptor datagramSocketFd(java.net.DatagramSocket s) {
        java.nio.channels.DatagramChannel ch = s.getChannel();
        if (ch != null) return channelFd(ch);
        // NOTE: this reflection path needs java.base/java.net AND
        // java.base/sun.nio.ch opened via --add-opens; only java.base/java.io
        // is currently opened (runtime/src/jvm_loader.cpp), so today this
        // always falls through to null (a graceful degrade matching AOSP's own
        // "fd unavailable" contract, not a crash) until those two are added.
        try {
            java.lang.reflect.Field delegateF = java.net.DatagramSocket.class.getDeclaredField("delegate");
            delegateF.setAccessible(true);
            Object delegate = delegateF.get(s);
            if (delegate != null) {
                try {
                    java.lang.reflect.Field dcF = delegate.getClass().getDeclaredField("dc");
                    dcF.setAccessible(true);
                    Object dc = dcF.get(delegate);
                    if (dc != null) return channelFd(dc);
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // AOSP adoptFd(int): wrap a raw fd the caller already owns; ownership
    // transfers to the returned PFD (its close() will close the fd). AOSP does
    // `fdesc.setInt$(fd)` (Android's own hidden FileDescriptor API); on the
    // host JDK we set the same private int field this file's own
    // macapkFdInt()/getFd() already reflects for READING (java.base/java.io is
    // opened in jvm_loader.cpp), so this is the write side of an
    // already-working reflection path.
    public static ParcelFileDescriptor adoptFd(int fd) {
        // Windows: the FileDescriptor needs a handle of its own for Java I/O.
        return new ParcelFileDescriptor(com.macapk.loader.NativeOs.wrap(fd));
    }

    private static java.io.FileDescriptor channelFd(Object channel) {
        // SelectableChannel implementations on the JDK hold a private
        // FileDescriptor; reflect it so native consumers can read the int fd.
        // Best-effort: if reflection is blocked we hand back an empty
        // FileDescriptor (int fd unknown), which is non-fatal for Java-side use.
        try {
            for (Class<?> c = channel.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("fd");
                    f.setAccessible(true);
                    Object v = f.get(channel);
                    if (v instanceof java.io.FileDescriptor) return (java.io.FileDescriptor) v;
                } catch (NoSuchFieldException nsf) {
                    // keep walking up
                }
            }
        } catch (Throwable ignored) {}
        return new java.io.FileDescriptor();
    }

    public java.io.FileDescriptor getFileDescriptor() { return fd; }

    // The raw integer fd. JDK 21 strong-encapsulates java.io.FileDescriptor, so
    // reflecting its private `fd` field needs `--add-opens java.base/java.io`
    // (set in jvm_loader.cpp). If that is unavailable we return -1; native
    // consumers (Unity) read the int via JNI from getFileDescriptor() instead,
    // so -1 here is non-fatal.
    public int getFd() { return macapkFdInt(fd); }

    public long getStatSize() { return size; }

    // AOSP detachFd(): return the raw integer fd and RELEASE ownership -- the
    // caller (Unity's native asset loader does exactly this in nativeRender)
    // now owns the fd and closes it after mmap. We therefore must NOT close the
    // same fd later (a double-close would hit whatever reused the number). So we
    // stop owning it: stash the backing stream in a process-lifetime list so its
    // finalizer never fires + null `owner` so close() becomes a no-op. The fd
    // stays valid for its new owner.
    private static final java.util.List<java.io.Closeable> sDetached =
            java.util.Collections.synchronizedList(new java.util.ArrayList<java.io.Closeable>());
    public int detachFd() {
        int n = macapkFdInt(fd);
        if (owner != null) { sDetached.add(owner); owner = null; }
        // AOSP: detaching is itself a close EVENT, reported to the peer and to
        // any OnCloseListener as FileDescriptorDetachedException -- "after
        // detach the remote side may continue reading/writing ... but they can
        // no longer deliver reliable close/error events".
        if (mComm != null) {
            mComm.detached = true;
            if (mComm.status < 0) mComm.status = Comm.DEAD;
        }
        reportClose(new FileDescriptorDetachedException());
        return n;
    }

    @Override public void close() {
        try { if (owner != null) owner.close(); } catch (Throwable ignored) {}
        if (mComm != null && mComm.status < 0) mComm.status = Comm.OK;
        reportClose(null);
    }

    // ---- AOSP reliable-channel surface ------------------------------------

    /** AOSP core/java/android/os/ParcelFileDescriptor.java:960 --
     *  `return mCommFd != null;` i.e. "was this descriptor created as one of
     *  the RELIABLE variants". Plain ones answer false on a device too. */
    public boolean canDetectErrors() {
        return mComm != null || mPeerComm != null;
    }

    /** AOSP :983 -- read the PEER's reported status and rethrow it.
     *  "If this ParcelFileDescriptor is unable to detect remote errors, it
     *  will return silently" (AOSP logs a warning and returns when there is no
     *  comm channel) -- so a plain PFD is a no-op here, not a throw. */
    public void checkError() throws java.io.IOException {
        Comm peer = mPeerComm;
        if (peer == null) {
            // No comm channel: nothing to check. AOSP logs
            // "Peer didn't provide a comm channel; unable to check for errors".
            return;
        }
        if (peer.detached) {
            throw new FileDescriptorDetachedException();
        }
        if (peer.status == Comm.ERROR) {
            throw new java.io.IOException("Remote error: " + peer.msg);
        }
        if (peer.status == Comm.DEAD) {
            throw new java.io.IOException("Remote side is dead");
        }
    }

    /** AOSP :830 -- close this end and report the given message to the peer.
     *  The null check and its IllegalArgumentException are AOSP's. */
    public void closeWithError(String msg) throws java.io.IOException {
        if (msg == null) {
            throw new IllegalArgumentException("Message must not be null");
        }
        if (mComm != null) {
            mComm.status = Comm.ERROR;
            mComm.msg = msg;
        }
        try { if (owner != null) owner.close(); } catch (Throwable ignored) {}
        owner = null;
        reportClose(new java.io.IOException(msg));
    }

    // Deliver the close event to an OnCloseListener exactly once, on the
    // Handler the caller supplied (AOSP delivers it from that Looper's
    // MessageQueue fd listener, so the thread is the same one).
    private void reportClose(final java.io.IOException e) {
        final OnCloseListener l = mCloseListener;
        final Handler h = mCloseHandler;
        if (l == null || mReportedClose) return;
        mReportedClose = true;
        if (h != null) {
            h.post(new Runnable() { @Override public void run() { l.onClose(e); } });
        } else {
            l.onClose(e);
        }
    }

    /** AOSP :264 -- open(File,int) then attach a close listener. Both null
     *  checks are AOSP's, and both throw IllegalArgumentException. */
    public static ParcelFileDescriptor open(java.io.File file, int mode, Handler handler,
                                            OnCloseListener listener)
            throws java.io.IOException {
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        ParcelFileDescriptor pfd = open(file, mode);
        pfd.mComm = new Comm();
        pfd.mCloseListener = listener;
        pfd.mCloseHandler = handler;
        return pfd;
    }

    /** AOSP :291 -- takes ownership of pfd's fd (via detachFd()) and returns a
     *  new descriptor that reports its close through the listener. */
    public static ParcelFileDescriptor wrap(ParcelFileDescriptor pfd, Handler handler,
                                            OnCloseListener listener)
            throws java.io.IOException {
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        java.io.Closeable prevOwner = pfd.owner;
        long sz = pfd.size;
        java.io.FileDescriptor raw = pfd.fd;
        pfd.detachFd();
        ParcelFileDescriptor out = new ParcelFileDescriptor(raw);
        out.owner = prevOwner;   // ownership transfers with the fd
        out.size = sz;
        out.mComm = new Comm();
        out.mCloseListener = listener;
        out.mCloseHandler = handler;
        return out;
    }

    /** AOSP :405 -- "Returns a new ParcelFileDescriptor holding a
     *  FileDescriptor for a DUP of the given fd", i.e. the caller keeps
     *  ownership of the original and the returned PFD owns its own copy.
     *  There is no dup(2) reachable from pure Java, so this goes through
     *  Darwin's fdesc filesystem: opening /dev/fd/N yields a NEW descriptor
     *  onto the same object (for a socket/pipe the Darwin fdesc open behaves
     *  as a dup; for a regular file it is a fresh open of the same vnode, so
     *  the file OFFSET is independent rather than shared -- the one place
     *  this differs from dup(2), and it is stated rather than hidden).
     *  The ownership half of the contract, which is the half a caller can be
     *  broken by, holds exactly: the returned PFD closes only its own fd. */
    public static ParcelFileDescriptor fromFd(int fd) throws java.io.IOException {
        if (fd < 0) throw new java.io.IOException("Invalid fd " + fd);
        java.io.File node = new java.io.File("/dev/fd/" + fd);
        try {
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(node, "rw");
            return macapkWrap(r.getFD(), r, safeLength(r));
        } catch (java.io.IOException ignored) { }
        try {
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(node, "r");
            return macapkWrap(r.getFD(), r, safeLength(r));
        } catch (java.io.IOException ignored) { }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(node);
            return macapkWrap(in.getFD(), in, 0);
        } catch (java.io.IOException e) {
            throw new java.io.IOException("Unable to dup fd " + fd, e);
        }
    }

    private static long safeLength(java.io.RandomAccessFile r) {
        try { return r.length(); } catch (java.io.IOException e) { return 0; }
    }

    /** AOSP :465 -- `dup` of the Socket's own FileDescriptor, "or null if the
     *  Socket is not currently open". The host JDK hides java.net.Socket's fd
     *  behind sun.nio.ch and java.net, neither of which is --add-opens'd (see
     *  fromDatagramSocket above), so when it cannot be resolved we take AOSP's
     *  own documented null return rather than hand back a PFD with no fd. */
    public static ParcelFileDescriptor fromSocket(java.net.Socket socket) {
        if (socket == null) return null;
        java.io.FileDescriptor d = socketFd(socket);
        return (d != null) ? macapkWrap(d, null, 0) : null;
    }

    private static java.io.FileDescriptor socketFd(java.net.Socket s) {
        java.nio.channels.SocketChannel ch = s.getChannel();
        if (ch != null) {
            java.io.FileDescriptor d = channelFd(ch);
            if (macapkFdInt(d) >= 0) return d;
        }
        try {
            java.lang.reflect.Field impl = java.net.Socket.class.getDeclaredField("impl");
            impl.setAccessible(true);
            Object o = impl.get(s);
            if (o != null) {
                java.io.FileDescriptor d = channelFd(o);
                if (macapkFdInt(d) >= 0) return d;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** AOSP :551 -- "two ParcelFileDescriptors structured as a pair of sockets
     *  connected to each other. The two sockets are indistinguishable."
     *  AOSP calls Os.socketpair(AF_UNIX, SOCK_STREAM). Java has no socketpair,
     *  but JDK 16+ opens real AF_UNIX SOCK_STREAM channels
     *  (StandardProtocolFamily.UNIX), so this is a genuine AF_UNIX stream pair
     *  -- bind + connect + accept on a private socket file that is unlinked
     *  immediately, which leaves exactly two connected endpoints and no
     *  listener. Not emulated with a pipe: a pipe is unidirectional and would
     *  break the "indistinguishable" half of the contract. */
    public static ParcelFileDescriptor[] createSocketPair() throws java.io.IOException {
        Object[] ends = unixSocketPair();
        java.nio.channels.SocketChannel a = (java.nio.channels.SocketChannel) ends[0];
        java.nio.channels.SocketChannel b = (java.nio.channels.SocketChannel) ends[1];
        return new ParcelFileDescriptor[] {
            macapkWrap(channelFd(a), a, 0),
            macapkWrap(channelFd(b), b, 0)
        };
    }

    /** AOSP :582 -- the same pair, plus a comm channel each way so either end
     *  can report an error the other detects with checkError(). Here the two
     *  ends are always in one process, so the comm objects ARE the channel. */
    public static ParcelFileDescriptor[] createReliableSocketPair() throws java.io.IOException {
        ParcelFileDescriptor[] pair = createSocketPair();
        Comm ca = new Comm();
        Comm cb = new Comm();
        pair[0].mComm = ca; pair[0].mPeerComm = cb;
        pair[1].mComm = cb; pair[1].mPeerComm = ca;
        return pair;
    }

    private static Object[] unixSocketPair() throws java.io.IOException {
        java.nio.file.Path sock = java.nio.file.Files.createTempFile("macapk-sp", ".sock");
        java.nio.file.Files.deleteIfExists(sock);   // bind() requires a free name
        java.net.UnixDomainSocketAddress addr = java.net.UnixDomainSocketAddress.of(sock);
        java.nio.channels.ServerSocketChannel srv = null;
        java.nio.channels.SocketChannel a = null;
        java.nio.channels.SocketChannel b = null;
        try {
            srv = java.nio.channels.ServerSocketChannel.open(
                    java.net.StandardProtocolFamily.UNIX);
            srv.bind(addr);
            a = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
            a.connect(addr);
            b = srv.accept();
            return new Object[] { a, b };
        } catch (java.io.IOException e) {
            closeQuietly(a); closeQuietly(b);
            throw e;
        } finally {
            closeQuietly(srv);
            try { java.nio.file.Files.deleteIfExists(sock); } catch (java.io.IOException ignored) {}
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) { try { c.close(); } catch (java.io.IOException ignored) {} }
    }

    /** AOSP :1169 -- invoked when the descriptor this listener was attached to
     *  is closed; the argument is null for a clean close. */
    public interface OnCloseListener {
        void onClose(java.io.IOException e);
    }

    /** AOSP nested class -- thrown when the remote side called detachFd(). */
    public static class FileDescriptorDetachedException extends java.io.IOException {
        private static final long serialVersionUID = 0xDe7ac4edL;
        public FileDescriptorDetachedException() {
            super("Remote side is detached");
        }
    }

    // AOSP: describeContents() reports CONTENTS_FILE_DESCRIPTOR so a caller
    // marshalling a Bundle/Parcelable containing a PFD knows to expect
    // fd-passing semantics. We have no real Binder, but the constant is just
    // contract metadata apps read off the value, so return it faithfully
    // instead of the wrong 0.
    @Override public int describeContents() { return Parcelable.CONTENTS_FILE_DESCRIPTOR; }

    // AOSP flattens the live fd across the Binder transaction as ancillary data
    // so the receiving side gets a real duplicate fd. Mac.apk has no real
    // Binder (every Parcel round-trip is in-process), so this uses the same
    // live-object-ref idiom Parcel.java already uses for Bundle/Parcelable/
    // Messenger (writeValue -> writeObjectRef, readValue -> readObjectRef),
    // which preserves the live fd/owner/size across the round-trip as
    // faithfully as AOSP's real fd duplication does across a real IPC
    // boundary. Was silent data loss: writeToParcel was a no-op and every PFD
    // read back out of a Parcel came back null regardless of what was written.
    // Common trigger: AIDL-generated stub/proxy code for a method that
    // returns/takes a ParcelFileDescriptor calls pfd.writeToParcel(...) and
    // ParcelFileDescriptor.CREATOR.createFromParcel(...) directly (bypassing
    // writeParcelable/readParcelable), which is exactly the AOSP-generated
    // codegen shape for a Parcelable return value.
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeValue(this); }

    public static final Creator<ParcelFileDescriptor> CREATOR = new Creator<ParcelFileDescriptor>() {
        @Override public ParcelFileDescriptor createFromParcel(Parcel source) {
            Object o = source.readValue(null);
            return (o instanceof ParcelFileDescriptor) ? (ParcelFileDescriptor) o : null;
        }
        @Override public ParcelFileDescriptor[] newArray(int size) { return new ParcelFileDescriptor[size]; }
    };

    // Windows: a FileDescriptor holds a HANDLE; NativeOs gives native code a
    // descriptor number for it.
    static int macapkFdInt(FileDescriptor f) {
        return com.macapk.loader.NativeOs.fdOf(f);
    }

    // ParcelFileDescriptor.AutoCloseInputStream -- a FileInputStream that closes
    // the owning ParcelFileDescriptor when the stream is closed. AOSP ref: same
    // file. Used by callers that consume an asset/pipe via stream API.
    public static class AutoCloseInputStream extends java.io.FileInputStream {
        private final ParcelFileDescriptor mPfd;
        public AutoCloseInputStream(ParcelFileDescriptor pfd) {
            super(pfd.getFileDescriptor());
            mPfd = pfd;
        }
        @Override public void close() throws java.io.IOException {
            try { super.close(); } finally { mPfd.close(); }
        }
    }

    // ParcelFileDescriptor.AutoCloseOutputStream -- mirror for the write side.
    public static class AutoCloseOutputStream extends java.io.FileOutputStream {
        private final ParcelFileDescriptor mPfd;
        public AutoCloseOutputStream(ParcelFileDescriptor pfd) {
            super(pfd.getFileDescriptor());
            mPfd = pfd;
        }
        @Override public void close() throws java.io.IOException {
            try { super.close(); } finally { mPfd.close(); }
        }
    }
}
