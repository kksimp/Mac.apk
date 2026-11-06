// android.os.Parcel -- IPC marshalling primitive.
//
// v1.0.940: this class now MARSHALS. Until then ~35 write/read pairs stashed
// the live object in a side ArrayList and wrote only an int index, so
// writeBundle/readBundle handed back the IDENTICAL instance. A "round trip"
// was an ALIAS, not a copy: a mutation after the write leaked into what the
// app believed was a separate object, and we SUCCEEDED where real Android
// throws (an app Parcelable that the unparcelling loader cannot see is a
// BadParcelableException on Android, and was a live object here). Every value
// family below is now flattened into `data` in AOSP's wire layout and rebuilt
// on read.
//
// Two things still ride a live-object side table, and both are honest rather
// than a shortcut -- see the block comment above `objects` for why.
//
// Byte-format note: AOSP's marshall() javadoc disclaims cross-version format
// stability, so we are not OBLIGED to reproduce AOSP's bytes. We do anyway,
// because dataSize() IS observable and 16 single-write sizes are already
// pinned by the answer probe; matching AOSP is the cheapest way to keep them
// matching. Design + evidence: docs/parcel-real-marshalling_2026-08-10.md.
package android.os;

public final class Parcel {
    private static final int INITIAL_CAPACITY = 64;

    // AOSP keeps THREE independent numbers here, and conflating any two of them
    // is observable to an app:
    //   dataPosition() -- the read/write cursor,
    //   dataSize()     -- how much has been WRITTEN; a high-water mark that a
    //                     rewind must NOT reduce (Parcel.java:496),
    //   dataCapacity() -- the allocation (always >= dataSize).
    // dataAvail() is specified as exactly dataSize()-dataPosition()
    // (Parcel.java:500-506). A single java.nio.ByteBuffer cannot express that:
    // its position doubled as the size and its limit doubled as the capacity,
    // so the everyday write -> setDataPosition(0) -> read round-trip reported
    // dataSize()==0 and dataAvail()==capacity. Any app that writes, rewinds and
    // then asks how much is in the parcel saw an EMPTY one. Hence the explicit
    // triple below.
    private byte[] data = new byte[INITIAL_CAPACITY];
    private int pos;
    private int size;

    private Parcel() {}
    public static Parcel obtain() { return new Parcel(); }

    /** AOSP core/java/android/os/Parcel.java:597 --
     *      Parcel parcel = Parcel.obtain(); parcel.markForBinder(binder); return parcel;
     *  markForBinder records whether the target binder is a PROXY, which AOSP
     *  uses to pick the stability/version rules for the transaction. Every
     *  binder in Mac.apk is local (there is no transport), so there is nothing
     *  to mark -- but the factory must still exist and still hand back a
     *  usable Parcel, which is the whole of its observable contract. */
    public static Parcel obtain(IBinder binder) { return obtain(); }

    public void recycle() {
        objects.clear();
        objectOffsetCount = 0;
        creatorCache = null;
        flattening.clear();
        pos = 0;
        size = 0;
    }

    // AOSP Parcel exception protocol -- the RPC reply header every generated
    // AIDL stub writes (writeNoException / writeException) and every proxy
    // reads (readException). Mirrors android14-release Parcel.java:2832-3087
    // verbatim in wire shape: an int code (0 = no exception), then for an
    // exception the message, a stack-trace header size (0: AOSP writes a
    // trace only when sParcelExceptionStackTrace is on, which it is not by
    // default), then the EX_SERVICE_SPECIFIC errorCode or the EX_PARCELABLE
    // length-prefixed payload. The StrictMode / noted-app-ops reply headers
    // (-128 / -127) are READ and skipped but never written: we gather neither.
    //
    // These were no-ops while every Binder call was in-process (both ends
    // skipped them symmetrically, so nothing broke -- except that an exception
    // thrown by the service vanished). With a cross-process transport
    // (MacApkBinderTransport) the far side's exception must arrive, and the
    // header is what AIDL code on BOTH sides already expects. Audit at the
    // switch (2026-10-30): every in-tree onTransact either is oneway (writes
    // no reply at all, e.g. LicensingService) or calls writeNoException.
    private static final int EX_SECURITY = -1;
    private static final int EX_BAD_PARCELABLE = -2;
    private static final int EX_ILLEGAL_ARGUMENT = -3;
    private static final int EX_NULL_POINTER = -4;
    private static final int EX_ILLEGAL_STATE = -5;
    private static final int EX_NETWORK_MAIN_THREAD = -6;
    private static final int EX_UNSUPPORTED_OPERATION = -7;
    private static final int EX_SERVICE_SPECIFIC = -8;
    private static final int EX_PARCELABLE = -9;
    /** @hide */ public static final int EX_HAS_NOTED_APPOPS_REPLY_HEADER = -127;
    private static final int EX_HAS_STRICTMODE_REPLY_HEADER = -128;

    public final void writeNoException() { writeInt(0); }

    public final void writeException(Exception e) {
        int code = getExceptionCode(e);
        writeInt(code);
        if (code == 0) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        }
        writeString(e.getMessage());
        writeInt(0);   // remote stack-trace header size: none
        switch (code) {
            case EX_SERVICE_SPECIFIC:
                writeInt(((ServiceSpecificException) e).errorCode);
                break;
            case EX_PARCELABLE: {
                final int sizePosition = dataPosition();
                writeInt(0);
                writeParcelable((Parcelable) e, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                final int payloadPosition = dataPosition();
                setDataPosition(sizePosition);
                writeInt(payloadPosition - sizePosition);
                setDataPosition(payloadPosition);
                break;
            }
        }
    }

    public static int getExceptionCode(Throwable e) {
        int code = 0;
        if (e instanceof Parcelable
                && e.getClass().getClassLoader() == Parcelable.class.getClassLoader()) {
            code = EX_PARCELABLE;
        } else if (e instanceof SecurityException) {
            code = EX_SECURITY;
        } else if (e instanceof BadParcelableException) {
            code = EX_BAD_PARCELABLE;
        } else if (e instanceof IllegalArgumentException) {
            code = EX_ILLEGAL_ARGUMENT;
        } else if (e instanceof NullPointerException) {
            code = EX_NULL_POINTER;
        } else if (e instanceof IllegalStateException) {
            code = EX_ILLEGAL_STATE;
        } else if (e instanceof NetworkOnMainThreadException) {
            code = EX_NETWORK_MAIN_THREAD;
        } else if (e instanceof UnsupportedOperationException) {
            code = EX_UNSUPPORTED_OPERATION;
        } else if (e instanceof ServiceSpecificException) {
            code = EX_SERVICE_SPECIFIC;
        }
        return code;
    }

    public final void readException() {
        int code = readExceptionCode();
        if (code != 0) {
            String msg = readString();
            readException(code, msg);
        }
    }

    public final int readExceptionCode() {
        int code = readInt();
        if (code == EX_HAS_NOTED_APPOPS_REPLY_HEADER) {
            // AppOpsManager.readAndLogNotedAppops: an int count of longs.
            int n = readInt();
            for (int i = 0; i < n; i++) readLong();
            code = readInt();
        }
        if (code == EX_HAS_STRICTMODE_REPLY_HEADER) {
            int headerSize = readInt();
            if (headerSize > 4) setDataPosition(dataPosition() + headerSize - 4);
            return 0;
        }
        return code;
    }

    public final void readException(int code, String msg) {
        String remoteStackTrace = null;
        final int remoteStackPayloadSize = readInt();
        if (remoteStackPayloadSize > 0) remoteStackTrace = readString();
        Exception e = createExceptionOrNull(code, msg);
        if (e == null) e = new RuntimeException("Unknown exception code: " + code + " msg " + msg);
        if (remoteStackTrace != null) {
            e.addSuppressed(new RemoteException("Remote stack trace:\n" + remoteStackTrace));
        }
        Parcel.<RuntimeException>macapkSneakyThrow(e);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void macapkSneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    public Exception createExceptionOrNull(int code, String msg) {
        switch (code) {
            case EX_PARCELABLE:
                if (readInt() > 0) {
                    return (Exception) readParcelable(Parcelable.class.getClassLoader());
                }
                return new RuntimeException(msg + " [missing Parcelable]");
            case EX_SECURITY:             return new SecurityException(msg);
            case EX_BAD_PARCELABLE:       return new BadParcelableException(msg);
            case EX_ILLEGAL_ARGUMENT:     return new IllegalArgumentException(msg);
            case EX_NULL_POINTER:         return new NullPointerException(msg);
            case EX_ILLEGAL_STATE:        return new IllegalStateException(msg);
            case EX_NETWORK_MAIN_THREAD:  return new NetworkOnMainThreadException();
            case EX_UNSUPPORTED_OPERATION:return new UnsupportedOperationException(msg);
            case EX_SERVICE_SPECIFIC:     return new ServiceSpecificException(readInt(), msg);
            default:                      return null;
        }
    }

    // ---- transport boundary (MacApkBinderTransport, package-private) --------
    // A Parcel crossing processes is its bytes plus its side table: each
    // objects[i] is what the int at objectOffsets[*] indexes. The transport
    // turns IBinder entries into Mach port rights and rebuilds the table on
    // the far side with the SAME indices, so the bytes need no rewriting --
    // the job flat_binder_object + the offsets array do for the binder driver.
    final byte[] macapkTransportBytes() { return java.util.Arrays.copyOf(data, size); }
    final Object[] macapkTransportObjects() { return objects.toArray(); }
    final int[] macapkTransportOffsets() {
        return java.util.Arrays.copyOf(objectOffsets, objectOffsetCount);
    }
    final void macapkTransportLoad(byte[] bytes, Object[] objs, int[] offsets) {
        unmarshall(bytes, 0, bytes.length);
        if (objs != null) java.util.Collections.addAll(objects, objs);
        if (offsets != null) for (int off : offsets) noteObjectOffset(off);
        pos = 0;
    }

    public int  dataPosition() { return pos; }
    public int  dataSize()     { return size; }
    public int  dataCapacity() { return data.length; }
    // AOSP does NOT bounds-check this. Parcel.cpp's setDataPosition only
    // rejects a value above INT32_MAX, so seeking past the end is legal; the
    // next write simply extends the parcel. Backing the cursor with
    // ByteBuffer.position() made this an IllegalArgumentException instead.
    public void setDataPosition(int p) { pos = p < 0 ? 0 : p; }

    private void ensure(int need) {
        if (need <= data.length) return;
        data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, need));
    }

    /** True when `n` more bytes can be read without running past dataSize. AOSP
     *  Parcel::readAligned returns 0 and does NOT advance the cursor when the
     *  read would run past mDataSize, so every reader below asks first. */
    private boolean hasBytes(int n) { return pos >= 0 && n >= 0 && pos <= size - n; }

    /** Commit a write of `n` bytes at the cursor: advance it and raise the
     *  high-water mark. Every writer funnels through here so no path can
     *  advance the cursor without growing dataSize. */
    private void wrote(int n) {
        pos += n;
        if (pos > size) size = pos;
    }

    public void writeInt(int v) {
        ensure(pos + 4);
        data[pos]     = (byte) v;
        data[pos + 1] = (byte) (v >>> 8);
        data[pos + 2] = (byte) (v >>> 16);
        data[pos + 3] = (byte) (v >>> 24);
        wrote(4);
    }
    public int readInt() {
        if (!hasBytes(4)) return 0;
        int v = (data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8)
              | ((data[pos + 2] & 0xff) << 16) | ((data[pos + 3] & 0xff) << 24);
        pos += 4;
        return v;
    }
    public void writeLong(long v) {
        ensure(pos + 8);
        for (int i = 0; i < 8; i++) data[pos + i] = (byte) (v >>> (8 * i));
        wrote(8);
    }
    public long readLong() {
        if (!hasBytes(8)) return 0L;
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (data[pos + i] & 0xff)) << (8 * i);
        pos += 8;
        return v;
    }
    // AOSP writeFloat/writeDouble are writeAligned<T>, i.e. the raw IEEE bits.
    public void writeFloat(float v)    { writeInt(Float.floatToRawIntBits(v)); }
    public float readFloat()           { return Float.intBitsToFloat(readInt()); }
    public void writeDouble(double v)  { writeLong(Double.doubleToRawLongBits(v)); }
    public double readDouble()         { return Double.longBitsToDouble(readLong()); }

    /** Round a byte count up to the 4-byte boundary AOSP's Parcel::writeInplace
     *  pads every variable-length block to (frameworks/native pad_size). */
    private static int pad4(int n) { return (n + 3) & ~3; }

    public void writeString(String v) {
        // AOSP wire layout, Parcel.cpp writeString16: writeInt32(len) where len
        // counts UTF-16 CODE UNITS, then (len+1) little-endian char16_t -- the
        // trailing NUL is written -- then writeInplace's pad to a 4-byte
        // boundary. A null string is a bare -1 and nothing else. That is why
        // "ab" costs 12 bytes and "" costs 8, not 6 and 4 as a UTF-8 body would.
        // String.length() is already a UTF-16 unit count, so a surrogate pair
        // counts 2 exactly as it does on the C++ side.
        if (v == null) { writeInt(-1); return; }
        int n = v.length();
        writeInt(n);
        int padded = pad4((n + 1) * 2);
        ensure(pos + padded);
        int at = pos;
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            data[at++] = (byte) c;
            data[at++] = (byte) (c >>> 8);
        }
        // The NUL terminator plus any pad bytes must read back as zero even when
        // this region already held data from an earlier, longer write.
        while (at < pos + padded) data[at++] = 0;
        wrote(padded);
    }

    // AIDL Stub/Proxy plumbing. Real Android writes a strict-mode policy
    // header int + the interface descriptor; we model it symmetrically (the
    // descriptor string) so a Proxy's writeInterfaceToken pairs with a Stub's
    // enforceInterface across our in-process Parcel. GMS / Firebase aidl
    // proxies call writeInterfaceToken during onTransact even when GmsCore is
    // absent; without the method they hit NoSuchMethodError mid-init.
    public void writeInterfaceToken(String interfaceName) {
        writeString(interfaceName);
    }
    public void enforceInterface(String interfaceName) {
        // Consume the token to keep the read cursor aligned with a paired
        // writeInterfaceToken, then assert equality the way AOSP does:
        // android_os_Parcel_enforceInterface throws SecurityException("Binder
        // invocation to an incorrect interface") whenever Parcel::enforceInterface
        // reports a descriptor mismatch. We have no remote binder, so this is not
        // a security boundary here -- it is a CURSOR-ALIGNMENT check, and that is
        // exactly what makes it worth keeping: a stub reading a parcel some other
        // proxy filled is about to decode garbage, and a loud SecurityException at
        // the token is far easier to diagnose than the silent misparse that
        // follows.
        String got = readString();
        if (interfaceName == null ? got != null : !interfaceName.equals(got)) {
            throw new SecurityException("Binder invocation to an incorrect interface");
        }
    }
    public String readString() {
        // Mirrors Parcel::readString16Inplace: if the length int cannot be read
        // the result is a NULL string, not an empty one. Reading a string off
        // the end of a parcel used to yield "" here, which is the one answer a
        // caller cannot distinguish from a genuinely empty written string.
        if (!hasBytes(4)) return null;
        int n = readInt();
        if (n < 0 || n > (size - pos) / 2) return null;
        int padded = pad4((n + 1) * 2);
        if (!hasBytes(padded)) return null;
        char[] c = new char[n];
        int at = pos;
        for (int i = 0; i < n; i++) {
            c[i] = (char) ((data[at] & 0xff) | ((data[at + 1] & 0xff) << 8));
            at += 2;
        }
        pos += padded;
        return new String(c);
    }

    // AOSP Parcel.java:909 "writeByte(byte val) { writeInt(val); }" and
    // Parcel.java:2538 "readByte() { return (byte)(readInt() & 0xff); }" -- a
    // byte scalar occupies FOUR bytes on the wire and keeps the parcel 4-byte
    // aligned. (Byte ARRAYS are different: those are raw bytes plus a pad.)
    public void writeByte(byte v) { writeInt(v); }
    public byte readByte()        { return (byte) (readInt() & 0xff); }

    // AOSP structure: the whole-array form is a thin wrapper over the
    // (offset, len) slice form, so both share one null/bounds contract.
    public final void writeByteArray(byte[] b) {
        writeByteArray(b, 0, (b != null) ? b.length : 0);
    }
    public final void writeByteArray(byte[] b, int offset, int len) {
        if (b == null) { writeInt(-1); return; }
        // Mirrors com.android.internal.util.ArrayUtils.throwsIfOutOfBounds, which
        // AOSP's 3-arg writeByteArray calls: an out-of-range slice is an
        // ArrayIndexOutOfBoundsException, never a silently truncated write.
        if ((offset | len) < 0 || offset > b.length - len) {
            throw new ArrayIndexOutOfBoundsException(
                "length=" + b.length + "; regionStart=" + offset + "; regionLength=" + len);
        }
        // AOSP: writeInt32(len) then writeInplace(len), which copies the raw
        // bytes and zero-fills up to the next 4-byte boundary.
        writeInt(len);
        int padded = pad4(len);
        ensure(pos + padded);
        System.arraycopy(b, offset, data, pos, len);
        for (int i = len; i < padded; i++) data[pos + i] = 0;
        wrote(padded);
    }
    public byte[] createByteArray() {
        int len = readInt();
        // AOSP's android_os_Parcel_createByteArray sanity-checks the stored
        // length against dataAvail() and returns null rather than over-reading.
        if (len < 0 || len > size - pos) return null;
        byte[] arr = new byte[len];
        System.arraycopy(data, pos, arr, 0, len);
        pos += pad4(len);
        return arr;
    }

    // There is no binder transaction in-process, but an IBinder in Mac.apk is
    // still a live Java object, so it rides the object-ref side table below.
    // The old no-op pair silently dropped every AIDL callback/listener handed
    // across a Parcel, which is the one thing writeStrongBinder exists to do.
    public final void writeStrongBinder(IBinder b) { writeObjectRef(b); }
    public final IBinder readStrongBinder() {
        Object o = readObjectRef(); return (o instanceof IBinder) ? (IBinder) o : null;
    }
    // AOSP: writeStrongBinder(val == null ? null : val.asBinder()). This is how
    // every AIDL-generated Proxy marshals a callback/listener parameter.
    public final void writeStrongInterface(IInterface val) {
        writeStrongBinder(val == null ? null : val.asBinder());
    }

    // ------------------------------------------------------------------
    // The live-object side table -- what is LEFT of the old object-ref model.
    //
    // Exactly two cases still keep a reference instead of bytes, and neither is
    // a shortcut around marshalling:
    //
    //   1. IBinder. There is no binder transport here and an IBinder is a live
    //      Java object. AOSP does not put the object into the byte stream
    //      either: it writes a flat_binder_object plus an offsets table that
    //      the driver translates on the way across. An index into a local table
    //      is the honest in-process equivalent, and marshall()ed bytes carrying
    //      a binder are meaningful only in-process on Android too.
    //
    //   2. A Parcelable whose writeToParcel produced ZERO bytes. 26 of the 135
    //      Parcelables in this tree still have an empty writeToParcel body
    //      (Intent, KeyEvent, MotionEvent, PendingIntent, Notification, ...).
    //      Flattening one of those for real yields a class name and nothing
    //      else, and reads back a default-constructed instance: an Intent that
    //      crossed a Parcel would arrive with no action, no component and no
    //      extras. That is SILENT data loss, and strictly worse than the
    //      aliasing bug this rewrite fixes -- so a zero-byte body falls back to
    //      the live reference and says so on stderr, once per class.
    //      REMOVAL CONDITION: the fallback is driven by a MEASUREMENT (did the
    //      body write anything), so the moment a class grows a real
    //      writeToParcel it stops firing for that class with no edit here.
    //      The write and read sides agree without any shared state, because a
    //      well-formed CREATOR reads exactly what writeToParcel wrote: zero
    //      bytes written implies zero bytes read, and both sides then
    //      write/consume the index.
    //
    // Both markers are ordinary ints in `data`, so the standard write-all ->
    // setDataPosition(0) -> read-all round-trip still resolves each one at its
    // own position after a rewind. `objectOffsets` records WHERE each marker
    // was written, which is what lets appendFrom REBASE the copied markers into
    // the destination's table -- without it, slicing a Bundle's bytes out of
    // one Parcel into another (readFromParcelInner does exactly that) would
    // resolve every index against the wrong table and hand back the wrong
    // object.
    // ------------------------------------------------------------------
    private final java.util.ArrayList<Object> objects = new java.util.ArrayList<>();
    private int[] objectOffsets = new int[0];
    private int objectOffsetCount;

    private void writeObjectRef(Object o) {
        if (o == null) { writeInt(-1); return; }
        int idx = objects.size();
        objects.add(o);
        noteObjectOffset(pos);
        writeInt(idx);
    }
    private Object readObjectRef() {
        // Do NOT fall through to readInt()'s out-of-range 0: index 0 is a valid
        // slot, so a truncated read would hand back an unrelated object.
        if (!hasBytes(4)) return null;
        int idx = readInt();
        return (idx < 0 || idx >= objects.size()) ? null : objects.get(idx);
    }
    private void noteObjectOffset(int at) {
        if (objectOffsetCount == objectOffsets.length) {
            objectOffsets = java.util.Arrays.copyOf(objectOffsets,
                    objectOffsets.length == 0 ? 8 : objectOffsets.length * 2);
        }
        objectOffsets[objectOffsetCount++] = at;
    }

    /** Names of classes we have already warned about, so a per-frame carrier
     *  cannot flood the log. Static because the defect is per CLASS, not per
     *  Parcel. */
    private static final java.util.Set<String> EMPTY_WRITER_WARNED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Objects whose writeToParcel is executing right now, innermost last.
     *  A list, not a set: the depth is one or two, and identity is what matters.
     *  Per-Parcel because the nesting is. */
    private final java.util.ArrayList<Object> flattening = new java.util.ArrayList<>();
    private boolean isFlattening(Object o) {
        for (int i = flattening.size() - 1; i >= 0; i--) if (flattening.get(i) == o) return true;
        return false;
    }

    /** Write a Parcelable's body, falling back to the live reference when the
     *  body turned out to be empty. See case 2 in the block comment above. */
    private void writeParcelableBody(Parcelable p, int flags) {
        final int start = pos;
        flattening.add(p);
        try { p.writeToParcel(this, flags); }
        finally { flattening.remove(flattening.size() - 1); }
        if (pos != start) return;
        writeObjectRef(p);
        final String name = p.getClass().getName();
        if (EMPTY_WRITER_WARNED.add(name)) {
            // stderr rather than android.util.Log: Log funnels into a NATIVE
            // method, so touching it would make this class unusable outside the
            // runtime (the debug/parcel_verify.sh gate runs on a bare JDK). The
            // runtime mirrors its stderr into the launcher log either way.
            System.err.println("[W] Mac.apk.Parcel: " + name + ".writeToParcel wrote no bytes; "
                + "keeping the live object instead of marshalling it. Give the class a real "
                + "writeToParcel/CREATOR pair to remove this fallback.");
        }
    }

    /** Inverse of writeParcelableBody: if the creator consumed nothing, the
     *  writer produced nothing and a live reference follows. */
    private Object readParcelableBody(Object created, int bodyStart) {
        if (pos != bodyStart) return created;
        Object live = readObjectRef();
        return live != null ? live : created;
    }

    // Mirrors AOSP Parcel.checkTypeToUnparcel, the guard behind every API-33
    // type-safe reader: an item that is not an instance of the Class<T> the
    // caller asked for is a BadParcelableException (AOSP raises the @hide
    // BadTypeParcelableException subclass, which apps can only catch as
    // BadParcelableException), not a silently-dropped null. A null item is not
    // type-checked, matching AOSP's VAL_NULL path.
    private static Object checkTypeToUnparcel(Class<?> requiredType, Object o) {
        if (requiredType != null && o != null && !requiredType.isInstance(o)) {
            throw new BadParcelableException("About to unparcel a "
                + o.getClass().getCanonicalName() + ", which is not a subtype of type "
                + requiredType.getCanonicalName() + " required by caller.");
        }
        return o;
    }

    // --- Bundle framing. AOSP Parcel.java:1038-1045: a null bundle is a bare
    //     -1, otherwise the Bundle writes its own back-patched length + magic +
    //     ArrayMap payload (BaseBundle.writeToParcelInner). readBundle is LAZY:
    //     it only slices the bytes, and the unparcel happens on first access.
    //     That laziness is observable, not an optimisation -- an app routinely
    //     puts a Bundle somewhere that is never read, and eager unparcelling
    //     would surface a BadParcelableException at a point real Android never
    //     does. ---
    public void writeBundle(Bundle val) {
        if (val == null) { writeInt(-1); return; }
        val.writeToParcel(this, 0);
    }
    public Bundle readBundle() { return readBundle(null); }
    public Bundle readBundle(ClassLoader loader) {
        int length = readInt();
        if (length < 0) return null;
        final Bundle bundle = new Bundle(this, length);
        // AOSP only OVERRIDES the loader when one was supplied; a null argument
        // leaves the bundle's own default loader in place rather than clearing it.
        if (loader != null) bundle.setClassLoader(loader);
        return bundle;
    }
    public void writePersistableBundle(PersistableBundle val) {
        if (val == null) { writeInt(-1); return; }
        val.writeToParcel(this, 0);
    }
    public PersistableBundle readPersistableBundle() { return readPersistableBundle(null); }
    public PersistableBundle readPersistableBundle(ClassLoader loader) {
        int length = readInt();
        if (length < 0) return null;
        final PersistableBundle bundle = new PersistableBundle(this, length);
        if (loader != null) bundle.setClassLoader(loader);
        return bundle;
    }

    // --- Parcelable. AOSP Parcel.java:1735-1749: the class name, then the
    //     object's own body. The name is what makes the read side able to find
    //     a CREATOR -- and what makes an app class invisible to the framework
    //     loader throw, exactly as it does on Android. ---
    public void writeParcelable(Parcelable p, int flags) {
        if (p == null) { writeString(null); return; }
        writeParcelableCreator(p);
        writeParcelableBody(p, flags);
    }
    /** AOSP Parcel.java:1757: flatten just the creator NAME. Split out because
     *  writeParcelable is not the only writer of it. */
    public void writeParcelableCreator(Parcelable p) {
        writeString(p.getClass().getName());
    }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T readParcelable(ClassLoader loader) {
        Parcelable.Creator<?> creator = readParcelableCreator(loader);
        if (creator == null) return null;
        return (T) createFromCreator(creator, loader);
    }
    // API-33 type-safe sibling. Note the unbounded <T>: AOSP declares
    // `<T> T readParcelable(ClassLoader, Class<T>)`, so the erased descriptor
    // returns Object and app bytecode compiled against the SDK links against it.
    @SuppressWarnings("unchecked")
    public <T> T readParcelable(ClassLoader loader, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        Parcelable.Creator<?> creator = readParcelableCreator(loader, clazz);
        if (creator == null) return null;
        return (T) checkTypeToUnparcel(clazz, createFromCreator(creator, loader));
    }
    /** Drive a creator over the body, honouring ClassLoaderCreator (AOSP
     *  Parcel.java:3271-3283) and the empty-body fallback. */
    private Object createFromCreator(Parcelable.Creator<?> creator, ClassLoader loader) {
        final int bodyStart = pos;
        Object o = (creator instanceof Parcelable.ClassLoaderCreator)
                ? ((Parcelable.ClassLoaderCreator<?>) creator).createFromParcel(this, loader)
                : creator.createFromParcel(this);
        return readParcelableBody(o, bodyStart);
    }

    public void writeParcelableArray(Parcelable[] val, int flags) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        // Each element carries its own class name: AOSP allows the array to be
        // heterogeneous, so a single name for the whole array would be wrong.
        for (Parcelable p : val) writeParcelable(p, flags);
    }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T[] readParcelableArray(ClassLoader loader) {
        int n = readInt(); if (n < 0) return null;
        Parcelable[] out = new Parcelable[n];
        for (int i = 0; i < n; i++) out[i] = readParcelable(loader);
        return (T[]) out;
    }
    @SuppressWarnings("unchecked")
    public <T> T[] readParcelableArray(ClassLoader loader, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        int n = readInt(); if (n < 0) return null;
        // AOSP allocates the result with the caller's component type
        // (Array.newInstance(clazz, n)), so the caller's implicit checkcast
        // succeeds even when the writer's array was declared more loosely.
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, n);
        for (int i = 0; i < n; i++) out[i] = (T) checkTypeToUnparcel(clazz, readParcelable(loader));
        return out;
    }
    public void writeParcelableList(java.util.List<? extends Parcelable> val, int flags) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) writeParcelable(val.get(i), flags);
    }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> java.util.List<T> readParcelableList(java.util.List<T> list,
                                                                       ClassLoader loader) {
        // AOSP resizes the caller's list in place and returns that same
        // instance; a written null (-1) clears it.
        final int n = readInt();
        if (n == -1) { list.clear(); return list; }
        final int m = list.size();
        int i = 0;
        for (; i < m && i < n; i++) list.set(i, (T) readParcelable(loader));
        for (; i < n; i++) list.add((T) readParcelable(loader));
        for (; i < m; i++) list.remove(n);
        return list;
    }
    @SuppressWarnings("unchecked")
    public <T> java.util.List<T> readParcelableList(java.util.List<T> list, ClassLoader loader,
                                                    Class<? extends T> clazz) {
        java.util.Objects.requireNonNull(list);
        java.util.Objects.requireNonNull(clazz);
        final int n = readInt();
        if (n == -1) { list.clear(); return list; }
        final int m = list.size();
        int i = 0;
        for (; i < m && i < n; i++) list.set(i, (T) readParcelable(loader, clazz));
        for (; i < n; i++) list.add((T) readParcelable(loader, clazz));
        for (; i < m; i++) list.remove(n);
        return list;
    }

    // --- Parcelable.Creator lookup. This is where AOSP's
    //     BadParcelableException comes from, and reproducing the LOADER choice
    //     is the whole point: AOSP Parcel.java:3312-3381 uses
    //
    //         loader == null ? getClass().getClassLoader() : loader
    //
    //     and on Android getClass().getClassLoader() is the BOOT loader, which
    //     cannot see app classes -- so readParcelable(null) on an app
    //     Parcelable throws "ClassNotFoundException when unmarshalling".
    //     The identical line reproduces that here WITHOUT a special case,
    //     because Boot.java makes AppClassLoader a CHILD of the system loader
    //     that defines android.os.Parcel, and a parent cannot see its child's
    //     classes. Do not "helpfully" widen this to the app loader: succeeding
    //     where Android throws is the bug this replaced. ---
    private java.util.HashMap<ClassLoader, java.util.HashMap<String, Parcelable.Creator<?>>> creatorCache;

    public final Parcelable.Creator<?> readParcelableCreator(ClassLoader loader) {
        return readParcelableCreatorInternal(loader, null);
    }
    @SuppressWarnings("unchecked")
    public <T> Parcelable.Creator<T> readParcelableCreator(ClassLoader loader, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        return (Parcelable.Creator<T>) readParcelableCreatorInternal(loader, clazz);
    }
    @SuppressWarnings("unchecked")
    private <T> Parcelable.Creator<T> readParcelableCreatorInternal(ClassLoader loader,
                                                                    Class<T> clazz) {
        String name = readString();
        if (name == null) return null;

        Parcelable.Creator<?> creator = null;
        if (creatorCache == null) creatorCache = new java.util.HashMap<>();
        java.util.HashMap<String, Parcelable.Creator<?>> map = creatorCache.get(loader);
        if (map == null) { map = new java.util.HashMap<>(); creatorCache.put(loader, map); }
        else creator = map.get(name);

        Class<?> parcelableClass = null;
        if (creator == null) {
            try {
                // Explicitly emulate Class.forName(String)'s "caller class
                // loader" behaviour when the caller passed none.
                ClassLoader parcelableClassLoader =
                    (loader == null ? getClass().getClassLoader() : loader);
                parcelableClass = Class.forName(name, false, parcelableClassLoader);
            } catch (ClassNotFoundException e) {
                throw new BadParcelableException(
                    "ClassNotFoundException when unmarshalling: " + name, e);
            }
            if (!Parcelable.class.isAssignableFrom(parcelableClass)) {
                throw new BadParcelableException("Parcelable protocol requires subclassing "
                    + "from Parcelable on class " + name);
            }
            creator = findCreator(parcelableClass, name);
            map.put(name, creator);
        }

        if (clazz != null) {
            // AOSP checks the CREATOR's declaring class against the caller's
            // required type. The cached path has no Class in hand, so recover it.
            if (parcelableClass == null) {
                try {
                    ClassLoader parcelableClassLoader =
                        (loader == null ? getClass().getClassLoader() : loader);
                    parcelableClass = Class.forName(name, false, parcelableClassLoader);
                } catch (ClassNotFoundException e) {
                    throw new BadParcelableException(
                        "ClassNotFoundException when unmarshalling: " + name, e);
                }
            }
            if (!clazz.isAssignableFrom(parcelableClass)) {
                throw new BadParcelableException("Parcelable creator " + name
                    + " is not a subclass of required class " + clazz.getName()
                    + " provided in the parameter");
            }
        }
        return (Parcelable.Creator<T>) creator;
    }
    @SuppressWarnings("unchecked")
    private static Parcelable.Creator<Object> findCreator(Class<?> parcelableClass, String name) {
        try {
            java.lang.reflect.Field f = parcelableClass.getField("CREATOR");
            if ((f.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
                throw new BadParcelableException("Parcelable protocol requires "
                    + "the CREATOR object to be static on class " + name);
            }
            if (!Parcelable.Creator.class.isAssignableFrom(f.getType())) {
                throw new BadParcelableException("Parcelable protocol requires a "
                    + "Parcelable.Creator object called CREATOR on class " + name);
            }
            // AOSP setAccessible(true): a CREATOR on a package-private class is
            // legal, and getField alone cannot read it.
            f.setAccessible(true);
            Parcelable.Creator<Object> c = (Parcelable.Creator<Object>) f.get(null);
            if (c == null) {
                throw new BadParcelableException("Parcelable protocol requires a "
                    + "non-null Parcelable.Creator object called CREATOR on class " + name);
            }
            return c;
        } catch (NoSuchFieldException e) {
            throw new BadParcelableException("Parcelable protocol requires a "
                + "Parcelable.Creator object called CREATOR on class " + name, e);
        } catch (IllegalAccessException e) {
            throw new BadParcelableException("IllegalAccessException when unmarshalling: "
                + name, e);
        }
    }

    // ---------------------------------------------------------------------
    // writeValue / readValue -- the tagged union every Bundle, Map, List and
    // SparseArray funnels through. The VAL_* codes are AOSP's verbatim
    // (Parcel.java:229-258) and the instanceof ORDER below is load-bearing:
    // several types match more than one branch, and AOSP's own comments name
    // the traps (Bundle before Parcelable, CharSequence after String,
    // CharSequence[] after String[] and before Object[], Serializable last).
    // ---------------------------------------------------------------------
    private static final int VAL_NULL = -1;
    private static final int VAL_STRING = 0;
    private static final int VAL_INTEGER = 1;
    private static final int VAL_MAP = 2;
    private static final int VAL_BUNDLE = 3;
    private static final int VAL_PARCELABLE = 4;
    private static final int VAL_SHORT = 5;
    private static final int VAL_LONG = 6;
    private static final int VAL_FLOAT = 7;
    private static final int VAL_DOUBLE = 8;
    private static final int VAL_BOOLEAN = 9;
    private static final int VAL_CHARSEQUENCE = 10;
    private static final int VAL_LIST = 11;
    private static final int VAL_SPARSEARRAY = 12;
    private static final int VAL_BYTEARRAY = 13;
    private static final int VAL_STRINGARRAY = 14;
    private static final int VAL_IBINDER = 15;
    private static final int VAL_PARCELABLEARRAY = 16;
    private static final int VAL_OBJECTARRAY = 17;
    private static final int VAL_INTARRAY = 18;
    private static final int VAL_LONGARRAY = 19;
    private static final int VAL_BYTE = 20;
    private static final int VAL_SERIALIZABLE = 21;
    private static final int VAL_SPARSEBOOLEANARRAY = 22;
    private static final int VAL_BOOLEANARRAY = 23;
    private static final int VAL_CHARSEQUENCEARRAY = 24;
    private static final int VAL_PERSISTABLEBUNDLE = 25;
    private static final int VAL_SIZE = 26;
    private static final int VAL_SIZEF = 27;
    private static final int VAL_DOUBLEARRAY = 28;

    /** NOT an AOSP tag ("MAPK"), and deliberately far outside AOSP's -1..28 so
     *  it can never be confused with one.
     *
     *  It exists for a shape a few framework classes in this tree use:
     *  writeToParcel is `dest.writeValue(this)` and CREATOR.createFromParcel is
     *  `source.readValue(null)`.
     *
     *  CURRENT RIDERS (updated v1.0.940): ParcelFileDescriptor, SharedMemory,
     *  Bitmap -- all hold a live OS resource with no byte form, which is the same
     *  honest answer we give an IBinder. (ResultReceiver was a fourth rider and
     *  is NOT any more: it writes AOSP's single strong binder, because a
     *  class-name round trip could not find an app-defined subclass -- see
     *  android/os/ResultReceiver.java.)
     *  VibrationEffect, VibrationAttributes and CombinedVibration were on this
     *  list and are NOT any more: they were pure value types with no live
     *  resource, and they now marshal their real fields. If you add a class
     *  here, ask first whether it actually holds something unmarshallable --
     *  the list is a last resort, not a default.
     *  That pair is self-consistent ONLY while writeValue emits a live-object
     *  index, which is what it used to do. Once writeValue marshals for real it
     *  is an INFINITE RECURSION: writeValue -> writeParcelable -> writeToParcel
     *  -> writeValue -> ... -> StackOverflowError.
     *
     *  Breaking the cycle by rewinding does not work, because those CREATORs
     *  call readValue and so need a VALUE to read. So the innermost writeValue
     *  emits this tag plus the live index, and readValue hands the object back
     *  -- symmetric, and terminating. Most of those classes hold a live OS
     *  resource (an fd, a shared-memory mapping, a bitmap, a callback) with no
     *  meaningful byte form, so for them this is the same honest answer we give
     *  an IBinder.
     *
     *  REMOVAL CONDITION: the tag is only ever emitted when a writeToParcel
     *  delegates straight back to writeValue for ITSELF. Give those classes a
     *  real field-by-field writeToParcel (or leave the resource-holding ones
     *  alone, deliberately) and it stops being reachable with no edit here. */
    private static final int VAL_MACAPK_LIVEREF = 0x4D41504B;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public final void writeValue(Object v) {
        if (v != null && isFlattening(v)) {
            // v.writeToParcel delegated straight back here for ITSELF. There
            // are no fields to flatten -- the object IS the value.
            writeInt(VAL_MACAPK_LIVEREF);
            writeObjectRef(v);
            return;
        }
        if (v == null) {
            writeInt(VAL_NULL);
        } else if (v instanceof String) {
            writeInt(VAL_STRING); writeString((String) v);
        } else if (v instanceof Integer) {
            writeInt(VAL_INTEGER); writeInt((Integer) v);
        } else if (v instanceof java.util.Map) {
            writeInt(VAL_MAP); writeMap((java.util.Map) v);
        } else if (v instanceof Bundle) {
            // Must be before Parcelable: Bundle IS a Parcelable, and the
            // VAL_BUNDLE path is what keeps the nested read lazy.
            writeInt(VAL_BUNDLE); writeBundle((Bundle) v);
        } else if (v instanceof PersistableBundle) {
            writeInt(VAL_PERSISTABLEBUNDLE); writePersistableBundle((PersistableBundle) v);
        } else if (v instanceof Parcelable) {
            writeInt(VAL_PARCELABLE); writeParcelable((Parcelable) v, 0);
        } else if (v instanceof Short) {
            writeInt(VAL_SHORT); writeInt(((Short) v).intValue());
        } else if (v instanceof Long) {
            writeInt(VAL_LONG); writeLong((Long) v);
        } else if (v instanceof Float) {
            writeInt(VAL_FLOAT); writeFloat((Float) v);
        } else if (v instanceof Double) {
            writeInt(VAL_DOUBLE); writeDouble((Double) v);
        } else if (v instanceof Boolean) {
            writeInt(VAL_BOOLEAN); writeInt((Boolean) v ? 1 : 0);
        } else if (v instanceof CharSequence) {
            // Must be after String. This is also why a StringBuilder written
            // into a Parcel comes back a plain String on Android: the
            // TextUtils format records a non-Spanned CharSequence as kind 1
            // plus its text, and the creator rebuilds a String from that.
            writeInt(VAL_CHARSEQUENCE); writeCharSequence((CharSequence) v);
        } else if (v instanceof java.util.List) {
            writeInt(VAL_LIST); writeList((java.util.List) v);
        } else if (v instanceof android.util.SparseArray) {
            writeInt(VAL_SPARSEARRAY); writeSparseArray((android.util.SparseArray) v);
        } else if (v instanceof boolean[]) {
            writeInt(VAL_BOOLEANARRAY); writeBooleanArray((boolean[]) v);
        } else if (v instanceof byte[]) {
            writeInt(VAL_BYTEARRAY); writeByteArray((byte[]) v);
        } else if (v instanceof String[]) {
            writeInt(VAL_STRINGARRAY); writeStringArray((String[]) v);
        } else if (v instanceof CharSequence[]) {
            // Must be after String[] and before Object[].
            writeInt(VAL_CHARSEQUENCEARRAY); writeCharSequenceArray((CharSequence[]) v);
        } else if (v instanceof IBinder) {
            writeInt(VAL_IBINDER); writeStrongBinder((IBinder) v);
        } else if (v instanceof Parcelable[]) {
            writeInt(VAL_PARCELABLEARRAY); writeParcelableArray((Parcelable[]) v, 0);
        } else if (v instanceof int[]) {
            writeInt(VAL_INTARRAY); writeIntArray((int[]) v);
        } else if (v instanceof long[]) {
            writeInt(VAL_LONGARRAY); writeLongArray((long[]) v);
        } else if (v instanceof Byte) {
            writeInt(VAL_BYTE); writeInt((Byte) v);
        } else if (v instanceof android.util.Size) {
            writeInt(VAL_SIZE); writeSize((android.util.Size) v);
        } else if (v instanceof android.util.SizeF) {
            writeInt(VAL_SIZEF); writeSizeF((android.util.SizeF) v);
        } else if (v instanceof double[]) {
            writeInt(VAL_DOUBLEARRAY); writeDoubleArray((double[]) v);
        } else {
            Class<?> clazz = v.getClass();
            if (clazz.isArray() && clazz.getComponentType() == Object.class) {
                // Only a PURE Object[] gets VAL_OBJECTARRAY. Other reference
                // arrays fall through to serialization, because the tag does
                // not record a component type and rebuilding one as Object[]
                // would break the caller's checkcast.
                writeInt(VAL_OBJECTARRAY); writeArray((Object[]) v);
            } else if (v instanceof java.io.Serializable) {
                // Must be last.
                writeInt(VAL_SERIALIZABLE); writeSerializable((java.io.Serializable) v);
            } else {
                throw new RuntimeException("Parcel: unable to marshal value " + v);
            }
        }
    }

    public final Object readValue(ClassLoader loader) {
        int type = readInt();
        switch (type) {
            case VAL_NULL:              return null;
            case VAL_STRING:            return readString();
            case VAL_INTEGER:           return readInt();
            case VAL_MAP:               return readHashMap(loader);
            case VAL_PARCELABLE:        return readParcelable(loader);
            case VAL_SHORT:             return (short) readInt();
            case VAL_LONG:              return readLong();
            case VAL_FLOAT:             return readFloat();
            case VAL_DOUBLE:            return readDouble();
            case VAL_BOOLEAN:           return readInt() == 1;
            case VAL_CHARSEQUENCE:      return readCharSequence();
            case VAL_LIST:              return readArrayList(loader);
            case VAL_BOOLEANARRAY:      return createBooleanArray();
            case VAL_BYTEARRAY:         return createByteArray();
            case VAL_STRINGARRAY:       return createStringArray();
            case VAL_CHARSEQUENCEARRAY: return readCharSequenceArray();
            case VAL_IBINDER:           return readStrongBinder();
            case VAL_OBJECTARRAY:       return readArray(loader);
            case VAL_INTARRAY:          return createIntArray();
            case VAL_LONGARRAY:         return createLongArray();
            case VAL_BYTE:              return readByte();
            case VAL_SERIALIZABLE:      return readSerializableInternal(loader);
            case VAL_PARCELABLEARRAY:   return readParcelableArray(loader);
            case VAL_SPARSEARRAY:       return readSparseArray(loader);
            case VAL_SPARSEBOOLEANARRAY: return readSparseBooleanArray();
            // "loading will be deferred" -- the nested laziness is deliberate.
            case VAL_BUNDLE:            return readBundle(loader);
            case VAL_PERSISTABLEBUNDLE: return readPersistableBundle(loader);
            case VAL_SIZE:              return readSize();
            case VAL_SIZEF:             return readSizeF();
            case VAL_DOUBLEARRAY:       return createDoubleArray();
            case VAL_MACAPK_LIVEREF:    return readObjectRef();
            default: {
                int off = dataPosition() - 4;
                throw new RuntimeException("Parcel " + this
                    + ": Unmarshalling unknown type code " + type + " at offset " + off);
            }
        }
    }

    // --- ArrayMap payload. This is the shape a Bundle marshals into, and the
    //     ORDER is observable: BaseBundle writes val.keyAt(i), i.e. ArrayMap's
    //     hash-sorted index order, and the reader appends sequentially and
    //     throws at the FIRST value it cannot rebuild. Which keys survive a
    //     failed unparcel is therefore fully determined by String.hashCode. ---
    void writeArrayMapInternal(java.util.Map<String, Object> val) {
        if (val == null) { writeInt(-1); return; }
        final int n = val.size();
        writeInt(n);
        if (val instanceof android.util.ArrayMap) {
            android.util.ArrayMap<String, Object> am = (android.util.ArrayMap<String, Object>) val;
            for (int i = 0; i < n; i++) { writeString(am.keyAt(i)); writeValue(am.valueAt(i)); }
            return;
        }
        // A non-ArrayMap backing (Bundle.EMPTY's immutable map, or a future
        // caller's) must still emit hash-sorted pairs, because the reader
        // rebuilds with ArrayMap.append and an out-of-order key would silently
        // corrupt the map's binary search.
        String[] keys = val.keySet().toArray(new String[0]);
        java.util.Arrays.sort(keys, (a, b) -> {
            int ha = (a == null) ? 0 : a.hashCode();
            int hb = (b == null) ? 0 : b.hashCode();
            return Integer.compare(ha, hb);
        });
        for (String k : keys) { writeString(k); writeValue(val.get(k)); }
    }
    void readArrayMapInternal(android.util.ArrayMap<String, Object> outVal, int n,
                              ClassLoader loader) {
        while (n > 0) {
            String key = readString();
            Object value = readValue(loader);
            // append(), not put(): the writer emitted hash-sorted keys, so each
            // one sorts last. AOSP calls validate() after the loop; a mid-loop
            // throw skips it, which is exactly why a partially-filled map is
            // left usable rather than poisoned.
            outVal.append(key, value);
            n--;
        }
    }
    void writeMapInternal(java.util.Map<String, Object> val) {
        if (val == null) { writeInt(-1); return; }
        java.util.Set<java.util.Map.Entry<String, Object>> entries = val.entrySet();
        int size = entries.size();
        writeInt(size);
        for (java.util.Map.Entry<String, Object> e : entries) {
            writeValue(e.getKey());
            writeValue(e.getValue());
            size--;
        }
        if (size != 0) {
            throw new BadParcelableException("Map size does not match number of entries!");
        }
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void readMapInternal(java.util.Map outVal, int n, ClassLoader loader) {
        while (n > 0) {
            Object key = readValue(loader);
            Object value = readValue(loader);
            outVal.put(key, value);
            n--;
        }
    }

    /** AOSP Parcel.hasReadWriteHelper(): true only when a ReadWriteHelper other
     *  than the no-op default is installed. We have no such hook, so BaseBundle's
     *  "unparcel before copying the blob" branch never fires. */
    public final boolean hasReadWriteHelper() { return false; }
    /** AOSP copies the source's per-loader creator cache so a nested Parcel does
     *  not repeat the Class.forName work. Purely a cache; behaviour is identical
     *  without it, which is why it is safe for this to be shallow. */
    public final void adoptClassCookies(Parcel from) {
        if (from != null && from.creatorCache != null) {
            if (creatorCache == null) creatorCache = new java.util.HashMap<>();
            for (java.util.Map.Entry<ClassLoader,
                    java.util.HashMap<String, Parcelable.Creator<?>>> e
                    : from.creatorCache.entrySet()) {
                java.util.HashMap<String, Parcelable.Creator<?>> mine = creatorCache.get(e.getKey());
                if (mine == null) creatorCache.put(e.getKey(), new java.util.HashMap<>(e.getValue()));
                else mine.putAll(e.getValue());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Additional typed marshalling (audit subsystem 11, P0). The byte buffer
    // backs scalars + primitive arrays; the object-ref side list backs anything
    // that holds live object references (lists, maps, typed objects, sparse
    // arrays, serializables). Both share the read cursor so the standard
    // write-all -> setDataPosition(0) -> read-all round-trip stays consistent.
    // ---------------------------------------------------------------------

    // boolean is flattened as an int (1/0) exactly like AOSP Parcel.writeInt.
    public void writeBoolean(boolean v) { writeInt(v ? 1 : 0); }
    public boolean readBoolean()        { return readInt() != 0; }

    // --- primitive-array writers/readers (length-prefixed, -1 == null) ---
    public void writeIntArray(int[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (int x : arr) writeInt(x);
    }
    public int[] createIntArray() {
        int n = readInt(); if (n < 0) return null;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = readInt();
        return arr;
    }
    public void readIntArray(int[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = readInt();
        else { for (int i = 0; i < n; i++) readInt(); throw new RuntimeException("bad array lengths"); }
    }

    public void writeLongArray(long[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (long x : arr) writeLong(x);
    }
    public long[] createLongArray() {
        int n = readInt(); if (n < 0) return null;
        long[] arr = new long[n];
        for (int i = 0; i < n; i++) arr[i] = readLong();
        return arr;
    }
    public void readLongArray(long[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = readLong();
        else { for (int i = 0; i < n; i++) readLong(); throw new RuntimeException("bad array lengths"); }
    }

    public void writeFloatArray(float[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (float x : arr) writeFloat(x);
    }
    public float[] createFloatArray() {
        int n = readInt(); if (n < 0) return null;
        float[] arr = new float[n];
        for (int i = 0; i < n; i++) arr[i] = readFloat();
        return arr;
    }
    public void readFloatArray(float[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = readFloat();
        else { for (int i = 0; i < n; i++) readFloat(); throw new RuntimeException("bad array lengths"); }
    }

    public void writeDoubleArray(double[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (double x : arr) writeDouble(x);
    }
    public double[] createDoubleArray() {
        int n = readInt(); if (n < 0) return null;
        double[] arr = new double[n];
        for (int i = 0; i < n; i++) arr[i] = readDouble();
        return arr;
    }
    public void readDoubleArray(double[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = readDouble();
        else { for (int i = 0; i < n; i++) readDouble(); throw new RuntimeException("bad array lengths"); }
    }

    public void writeCharArray(char[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (char x : arr) writeInt(x);
    }
    public char[] createCharArray() {
        int n = readInt(); if (n < 0) return null;
        char[] arr = new char[n];
        for (int i = 0; i < n; i++) arr[i] = (char) readInt();
        return arr;
    }
    public void readCharArray(char[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = (char) readInt();
        else { for (int i = 0; i < n; i++) readInt(); throw new RuntimeException("bad array lengths"); }
    }

    public void writeBooleanArray(boolean[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (boolean x : arr) writeInt(x ? 1 : 0);
    }
    public boolean[] createBooleanArray() {
        int n = readInt(); if (n < 0) return null;
        boolean[] arr = new boolean[n];
        for (int i = 0; i < n; i++) arr[i] = readInt() != 0;
        return arr;
    }
    public void readBooleanArray(boolean[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = readInt() != 0;
        else { for (int i = 0; i < n; i++) readInt(); throw new RuntimeException("bad array lengths"); }
    }

    public void readByteArray(byte[] out) {
        int n = readInt();
        if (out != null && n == out.length && n <= size - pos) {
            System.arraycopy(data, pos, out, 0, n);
            pos += pad4(n);
        } else {
            // AOSP throws "Bad length" without draining; the transaction is
            // abandoned, so there is no cursor left to keep aligned.
            throw new RuntimeException("bad array lengths");
        }
    }

    // --- String[] / String list ---
    public void writeStringArray(String[] arr) {
        if (arr == null) { writeInt(-1); return; }
        writeInt(arr.length);
        for (String s : arr) writeString(s);
    }
    public String[] createStringArray() {
        int n = readInt(); if (n < 0) return null;
        String[] arr = new String[n];
        for (int i = 0; i < n; i++) arr[i] = readString();
        return arr;
    }
    public void readStringArray(String[] out) {
        int n = readInt();
        if (out != null && n == out.length) for (int i = 0; i < n; i++) out[i] = readString();
        else { for (int i = 0; i < n; i++) readString(); throw new RuntimeException("bad array lengths"); }
    }
    public void writeStringList(java.util.List<String> val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (String s : val) writeString(s);
    }
    public java.util.ArrayList<String> createStringArrayList() {
        int n = readInt(); if (n < 0) return null;
        java.util.ArrayList<String> l = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(readString());
        return l;
    }
    public void readStringList(java.util.List<String> list) {
        int n = readInt();
        if (n < 0) return;
        list.clear();
        for (int i = 0; i < n; i++) list.add(readString());
    }

    // --- IBinder[] / List<IBinder> (AIDL methods taking several callbacks) ---
    // Same length-prefixed shape as the String family (-1 == null), with
    // writeStrongBinder/readStrongBinder per element, exactly as AOSP does.
    public final void writeBinderArray(IBinder[] val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (IBinder b : val) writeStrongBinder(b);
    }
    public final IBinder[] createBinderArray() {
        int n = readInt(); if (n < 0) return null;
        IBinder[] val = new IBinder[n];
        for (int i = 0; i < n; i++) val[i] = readStrongBinder();
        return val;
    }
    // AOSP throws on a length mismatch without draining the remainder; the
    // transaction is abandoned, so there is no cursor left to keep aligned.
    public final void readBinderArray(IBinder[] val) {
        int n = readInt();
        if (n == val.length) { for (int i = 0; i < n; i++) val[i] = readStrongBinder(); }
        else throw new RuntimeException("bad array lengths");
    }
    public final void writeBinderList(java.util.List<IBinder> val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) writeStrongBinder(val.get(i));
    }
    public final java.util.ArrayList<IBinder> createBinderArrayList() {
        int n = readInt(); if (n < 0) return null;
        java.util.ArrayList<IBinder> l = new java.util.ArrayList<IBinder>(n);
        while (n > 0) { l.add(readStrongBinder()); n--; }
        return l;
    }
    // AOSP resizes the caller's list in place (set / add / remove) instead of
    // clearing it, so an element the caller already holds keeps its slot.
    public final void readBinderList(java.util.List<IBinder> list) {
        int m = list.size();
        int n = readInt();
        int i = 0;
        for (; i < m && i < n; i++) list.set(i, readStrongBinder());
        for (; i < n; i++) list.add(readStrongBinder());
        for (; i < m; i++) list.remove(n);
    }

    // --- CharSequence ---
    // AOSP routes this straight through TextUtils.writeToParcel /
    // CHAR_SEQUENCE_CREATOR, and this tree already implements that format
    // faithfully (kind int, text, then the self-delimiting span records), so
    // delegating is both correct and the only way a Spanned keeps its spans.
    // It is also why a StringBuilder written into a Parcel comes back a plain
    // java.lang.String on Android: kind==1 rebuilds a String. That used to need
    // a special-case flattener here; making the marshalling real deletes it.
    public void writeCharSequence(CharSequence cs) {
        android.text.TextUtils.writeToParcel(cs, this, 0);
    }
    public CharSequence readCharSequence() {
        return android.text.TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(this);
    }
    public void writeCharSequenceArray(CharSequence[] val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (CharSequence cs : val) writeCharSequence(cs);
    }
    public CharSequence[] readCharSequenceArray() {
        int n = readInt(); if (n < 0) return null;
        CharSequence[] out = new CharSequence[n];
        for (int i = 0; i < n; i++) out[i] = readCharSequence();
        return out;
    }
    public void writeCharSequenceList(java.util.ArrayList<CharSequence> val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (int i = 0; i < val.size(); i++) writeCharSequence(val.get(i));
    }
    public java.util.ArrayList<CharSequence> readCharSequenceList() {
        int n = readInt(); if (n < 0) return null;
        java.util.ArrayList<CharSequence> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(readCharSequence());
        return out;
    }

    // --- generic List / Map / Object[]: a length prefix (-1 == null) then one
    //     writeValue per element/entry, exactly as AOSP does. ---
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void writeList(java.util.List val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) writeValue(val.get(i));
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void readList(java.util.List outVal, ClassLoader loader) {
        int n = readInt();
        readListInternal(outVal, n, loader, null);
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> void readList(java.util.List<? super T> outVal, ClassLoader loader, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        int n = readInt();
        readListInternal((java.util.List) outVal, n, loader, clazz);
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void readListInternal(java.util.List outVal, int n, ClassLoader loader, Class<?> clazz) {
        // AOSP APPENDS here and does not clear -- readList's contract is "add
        // the parcel's items to this list", which is why readArrayList has to
        // hand it a fresh one. Note this is NOT readTypedList's contract, which
        // genuinely resizes in place; conflating the two makes a null list
        // (n == -1) walk off the end of remove().
        while (n > 0) {
            outVal.add(checkTypeToUnparcel(clazz, readValue(loader)));
            n--;
        }
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public java.util.ArrayList readArrayList(ClassLoader loader) {
        int n = readInt(); if (n < 0) return null;
        java.util.ArrayList l = new java.util.ArrayList(n);
        readListInternal(l, n, loader, null);
        return l;
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> java.util.ArrayList<T> readArrayList(ClassLoader loader, Class<? extends T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        int n = readInt(); if (n < 0) return null;
        java.util.ArrayList l = new java.util.ArrayList(n);
        readListInternal(l, n, loader, clazz);
        return (java.util.ArrayList<T>) l;
    }
    public void writeArray(Object[] val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (Object o : val) writeValue(o);
    }
    public Object[] readArray(ClassLoader loader) {
        int n = readInt(); if (n < 0) return null;
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = readValue(loader);
        return out;
    }
    // AOSP's clazz here is the COMPONENT type: readArrayInternal allocates
    // Array.newInstance(clazz, n) and type-checks every element into it.
    @SuppressWarnings("unchecked")
    public <T> T[] readArray(ClassLoader loader, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        int n = readInt(); if (n < 0) return null;
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, n);
        for (int i = 0; i < n; i++) out[i] = (T) checkTypeToUnparcel(clazz, readValue(loader));
        return out;
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void writeMap(java.util.Map val) { writeMapInternal((java.util.Map<String, Object>) val); }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void readMap(java.util.Map outVal, ClassLoader loader) {
        int n = readInt(); if (n < 0) return;
        readMapInternal(outVal, n, loader);
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <K, V> void readMap(java.util.Map<? super K, ? super V> outVal, ClassLoader loader,
                               Class<K> clazzKey, Class<V> clazzValue) {
        java.util.Objects.requireNonNull(clazzKey);
        java.util.Objects.requireNonNull(clazzValue);
        int n = readInt(); if (n < 0) return;
        while (n > 0) {
            Object k = checkTypeToUnparcel(clazzKey, readValue(loader));
            Object v = checkTypeToUnparcel(clazzValue, readValue(loader));
            ((java.util.Map) outVal).put(k, v);
            n--;
        }
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public java.util.HashMap readHashMap(ClassLoader loader) {
        int n = readInt(); if (n < 0) return null;
        java.util.HashMap m = new java.util.HashMap(n);
        readMapInternal(m, n, loader);
        return m;
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <K, V> java.util.HashMap<K, V> readHashMap(ClassLoader loader,
            Class<? extends K> clazzKey, Class<? extends V> clazzValue) {
        java.util.Objects.requireNonNull(clazzKey);
        java.util.Objects.requireNonNull(clazzValue);
        int n = readInt(); if (n < 0) return null;
        java.util.HashMap m = new java.util.HashMap(n);
        while (n > 0) {
            Object k = checkTypeToUnparcel(clazzKey, readValue(loader));
            Object v = checkTypeToUnparcel(clazzValue, readValue(loader));
            m.put(k, v);
            n--;
        }
        return (java.util.HashMap<K, V>) m;
    }

    // --- typed Parcelable objects/lists/arrays. No class name on the wire:
    //     the CALLER supplies the CREATOR, which is why this whole family keeps
    //     working with no ClassLoader anywhere in sight. ---
    public <T extends Parcelable> void writeTypedObject(T val, int parcelableFlags) {
        if (val == null) { writeInt(0); return; }
        writeInt(1);
        writeParcelableBody(val, parcelableFlags);
    }
    public <T> T readTypedObject(Parcelable.Creator<T> c) {
        if (readInt() == 0) return null;
        final int bodyStart = pos;
        T o = c.createFromParcel(this);
        @SuppressWarnings("unchecked") T r = (T) readParcelableBody(o, bodyStart);
        return r;
    }
    public <T extends Parcelable> void writeTypedList(java.util.List<T> val) {
        writeTypedList(val, 0);
    }
    public <T extends Parcelable> void writeTypedList(java.util.List<T> val, int flags) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) writeTypedObject(val.get(i), flags);
    }
    public <T> java.util.ArrayList<T> createTypedArrayList(Parcelable.Creator<T> c) {
        int n = readInt(); if (n < 0) return null;
        java.util.ArrayList<T> l = new java.util.ArrayList<T>(n);
        while (n > 0) { l.add(readTypedObject(c)); n--; }
        return l;
    }
    public <T> void readTypedList(java.util.List<T> list, Parcelable.Creator<T> c) {
        final int m = list.size();
        final int n = readInt();
        int i = 0;
        for (; i < m && i < n; i++) list.set(i, readTypedObject(c));
        for (; i < n; i++) list.add(readTypedObject(c));
        for (; i < m; i++) list.remove(n);
    }
    public <T extends Parcelable> void writeTypedArray(T[] val, int flags) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (T t : val) writeTypedObject(t, flags);
    }
    @SuppressWarnings("unchecked")
    public <T> T[] createTypedArray(Parcelable.Creator<T> c) {
        int n = readInt(); if (n < 0) return null;
        T[] out = c.newArray(n);
        for (int i = 0; i < n; i++) out[i] = readTypedObject(c);
        return out;
    }
    /** AOSP :2285 -- SparseArray<Parcelable> in the TYPED encoding (no class
     *  name per element), keyed by int. Distinct from writeSparseArray above,
     *  which uses writeValue and so carries a type tag. */
    public final <T extends Parcelable> void writeTypedSparseArray(
            android.util.SparseArray<T> val, int parcelableFlags) {
        if (val == null) { writeInt(-1); return; }
        final int count = val.size();
        writeInt(count);
        for (int i = 0; i < count; i++) {
            writeInt(val.keyAt(i));
            writeTypedObject(val.valueAt(i), parcelableFlags);
        }
    }

    /** AOSP :4004 */
    public final <T extends Parcelable> android.util.SparseArray<T> createTypedSparseArray(
            Parcelable.Creator<T> creator) {
        final int count = readInt();
        if (count < 0) return null;
        final android.util.SparseArray<T> array = new android.util.SparseArray<T>(count);
        for (int i = 0; i < count; i++) {
            final int index = readInt();
            array.append(index, readTypedObject(creator));
        }
        return array;
    }

    /** AOSP :1519 -- ArrayMap<String, Parcelable> in the typed encoding. */
    public <T extends Parcelable> void writeTypedArrayMap(
            android.util.ArrayMap<String, T> val, int parcelableFlags) {
        if (val == null) { writeInt(-1); return; }
        final int count = val.size();
        writeInt(count);
        for (int i = 0; i < count; i++) {
            writeString(val.keyAt(i));
            writeTypedObject(val.valueAt(i), parcelableFlags);
        }
    }

    /** AOSP :4033 */
    public final <T extends Parcelable> android.util.ArrayMap<String, T> createTypedArrayMap(
            Parcelable.Creator<T> creator) {
        final int count = readInt();
        if (count < 0) return null;
        final android.util.ArrayMap<String, T> map = new android.util.ArrayMap<String, T>(count);
        for (int i = 0; i < count; i++) {
            final String key = readString();
            map.append(key, readTypedObject(creator));
        }
        return map;
    }

    // ---- IInterface arrays / lists (AOSP :2139-:2251, :2387, :4113, :4178).
    // Every element is written as a strong binder and read back through the
    // caller's asInterface function -- the AIDL-generated `Stub.asInterface`.
    // Our writeStrongInterface/readStrongBinder already handle the in-process
    // case (the binder IS the object), so these are AOSP's bodies verbatim.

    /** AOSP :2139 */
    public final <T extends IInterface> void writeInterfaceArray(T[] val) {
        if (val != null) {
            int n = val.length;
            writeInt(n);
            for (int i = 0; i < n; i++) writeStrongInterface(val[i]);
        } else {
            writeInt(-1);
        }
    }

    /** AOSP :2217 */
    public final <T extends IInterface> T[] createInterfaceArray(
            java.util.function.IntFunction<T[]> newArray,
            java.util.function.Function<IBinder, T> asInterface) {
        int n = readInt();
        if (n >= 0) {
            T[] val = newArray.apply(n);
            for (int i = 0; i < n; i++) val[i] = asInterface.apply(readStrongBinder());
            return val;
        }
        return null;
    }

    /** AOSP :2240 -- throws BadParcelableException, not RuntimeException, when
     *  the lengths disagree. */
    public final <T extends IInterface> void readInterfaceArray(
            T[] val, java.util.function.Function<IBinder, T> asInterface) {
        int n = readInt();
        if (n == val.length) {
            for (int i = 0; i < n; i++) val[i] = asInterface.apply(readStrongBinder());
        } else {
            throw new BadParcelableException("bad array lengths");
        }
    }

    /** AOSP :2387 */
    public final <T extends IInterface> void writeInterfaceList(java.util.List<T> val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) writeStrongInterface(val.get(i));
    }

    /** AOSP :4113 */
    public final <T extends IInterface> java.util.ArrayList<T> createInterfaceArrayList(
            java.util.function.Function<IBinder, T> asInterface) {
        int n = readInt();
        if (n < 0) return null;
        java.util.ArrayList<T> l = new java.util.ArrayList<T>(n);
        while (n > 0) { l.add(asInterface.apply(readStrongBinder())); n--; }
        return l;
    }

    /** AOSP :4178 -- same grow/shrink-in-place shape as readTypedList. */
    public final <T extends IInterface> void readInterfaceList(
            java.util.List<T> list, java.util.function.Function<IBinder, T> asInterface) {
        final int m = list.size();
        final int n = readInt();
        int i = 0;
        for (; i < m && i < n; i++) list.set(i, asInterface.apply(readStrongBinder()));
        for (; i < n; i++) list.add(asInterface.apply(readStrongBinder()));
        for (; i < m; i++) list.remove(n);
    }

    // ---- Fixed-size (and multi-dimensional) arrays (AOSP :2500, :4349-:4670).
    // AIDL's `int[3]` / `Foo[2][4]` types marshal through these. Ported
    // verbatim, including every BadParcelableException message, because those
    // messages are the only diagnostic an AIDL mismatch produces. The two
    // pieces AOSP has that are dropped here are ensureWithinMemoryLimit (an
    // allocation guard against a hostile parcel arriving over binder -- there
    // is no binder here, so no hostile parcel) and getItemTypeSize, which only
    // feeds it.

    /** AOSP :2500 */
    public <T> void writeFixedArray(T val, int parcelableFlags, int... dimensions) {
        if (val == null) { writeInt(-1); return; }
        writeFixedArrayInternal(val, parcelableFlags, /*index=*/0, dimensions);
    }

    private <T> void writeFixedArrayInternal(T val, int parcelableFlags, int index,
                                             int[] dimensions) {
        if (index >= dimensions.length) {
            throw new BadParcelableException("Array has more dimensions than expected: "
                    + dimensions.length);
        }
        int length = dimensions[index];
        if (val == null) {
            throw new BadParcelableException("Non-null array shouldn't have a null array.");
        }
        if (!val.getClass().isArray()) {
            throw new BadParcelableException("Not an array: " + val);
        }
        if (java.lang.reflect.Array.getLength(val) != length) {
            throw new BadParcelableException("bad length: expected " + length + ", but got "
                    + java.lang.reflect.Array.getLength(val));
        }
        final Class<?> componentType = val.getClass().getComponentType();
        if (!componentType.isArray() && index + 1 != dimensions.length) {
            throw new BadParcelableException("Array has fewer dimensions than expected: "
                    + dimensions.length);
        }
        if (componentType == boolean.class)      writeBooleanArray((boolean[]) val);
        else if (componentType == byte.class)    writeByteArray((byte[]) val);
        else if (componentType == char.class)    writeCharArray((char[]) val);
        else if (componentType == int.class)     writeIntArray((int[]) val);
        else if (componentType == long.class)    writeLongArray((long[]) val);
        else if (componentType == float.class)   writeFloatArray((float[]) val);
        else if (componentType == double.class)  writeDoubleArray((double[]) val);
        else if (componentType == IBinder.class) writeBinderArray((IBinder[]) val);
        else if (IInterface.class.isAssignableFrom(componentType)) {
            writeInterfaceArray((IInterface[]) val);
        } else if (Parcelable.class.isAssignableFrom(componentType)) {
            writeTypedArray((Parcelable[]) val, parcelableFlags);
        } else if (componentType.isArray()) {
            writeInt(length);
            for (int i = 0; i < length; i++) {
                writeFixedArrayInternal(java.lang.reflect.Array.get(val, i), parcelableFlags,
                        index + 1, dimensions);
            }
        } else {
            throw new BadParcelableException("unknown type for fixed-size array: " + componentType);
        }
    }

    /** AOSP :4349 */
    public <T> void readFixedArray(T val) {
        Class<?> componentType = val.getClass().getComponentType();
        if (componentType == boolean.class)      readBooleanArray((boolean[]) val);
        else if (componentType == byte.class)    readByteArray((byte[]) val);
        else if (componentType == char.class)    readCharArray((char[]) val);
        else if (componentType == int.class)     readIntArray((int[]) val);
        else if (componentType == long.class)    readLongArray((long[]) val);
        else if (componentType == float.class)   readFloatArray((float[]) val);
        else if (componentType == double.class)  readDoubleArray((double[]) val);
        else if (componentType == IBinder.class) readBinderArray((IBinder[]) val);
        else if (componentType.isArray()) {
            int length = readInt();
            if (length != java.lang.reflect.Array.getLength(val)) {
                throw new BadParcelableException("Bad length: expected "
                        + java.lang.reflect.Array.getLength(val) + ", but got " + length);
            }
            for (int i = 0; i < length; i++) readFixedArray(java.lang.reflect.Array.get(val, i));
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
    }

    /** AOSP :4388 */
    @SuppressWarnings("unchecked")
    public <T, S extends IInterface> void readFixedArray(
            T val, java.util.function.Function<IBinder, S> asInterface) {
        Class<?> componentType = val.getClass().getComponentType();
        if (IInterface.class.isAssignableFrom(componentType)) {
            readInterfaceArray((S[]) val, asInterface);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length != java.lang.reflect.Array.getLength(val)) {
                throw new BadParcelableException("Bad length: expected "
                        + java.lang.reflect.Array.getLength(val) + ", but got " + length);
            }
            for (int i = 0; i < length; i++) {
                readFixedArray(java.lang.reflect.Array.get(val, i), asInterface);
            }
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
    }

    /** AOSP :4414 */
    @SuppressWarnings("unchecked")
    public <T, S extends Parcelable> void readFixedArray(T val, Parcelable.Creator<S> c) {
        Class<?> componentType = val.getClass().getComponentType();
        if (Parcelable.class.isAssignableFrom(componentType)) {
            readTypedArray((S[]) val, c);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length != java.lang.reflect.Array.getLength(val)) {
                throw new BadParcelableException("Bad length: expected "
                        + java.lang.reflect.Array.getLength(val) + ", but got " + length);
            }
            for (int i = 0; i < length; i++) {
                readFixedArray(java.lang.reflect.Array.get(val, i), c);
            }
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
    }

    /** AOSP :4431 */
    private void ensureClassHasExpectedDimensions(Class<?> cls, int numDimension) {
        if (numDimension <= 0) {
            throw new BadParcelableException("Fixed-size array should have dimensions.");
        }
        for (int i = 0; i < numDimension; i++) {
            if (!cls.isArray()) {
                throw new BadParcelableException("Array has fewer dimensions than expected: "
                        + numDimension);
            }
            cls = cls.getComponentType();
        }
        if (cls.isArray()) {
            throw new BadParcelableException("Array has more dimensions than expected: "
                    + numDimension);
        }
    }

    /** AOSP :4472 */
    @SuppressWarnings("unchecked")
    public <T> T createFixedArray(Class<T> cls, int... dimensions) {
        ensureClassHasExpectedDimensions(cls, dimensions.length);
        T val = null;
        final Class<?> componentType = cls.getComponentType();
        if (componentType == boolean.class)      val = (T) createBooleanArray();
        else if (componentType == byte.class)    val = (T) createByteArray();
        else if (componentType == char.class)    val = (T) createCharArray();
        else if (componentType == int.class)     val = (T) createIntArray();
        else if (componentType == long.class)    val = (T) createLongArray();
        else if (componentType == float.class)   val = (T) createFloatArray();
        else if (componentType == double.class)  val = (T) createDoubleArray();
        else if (componentType == IBinder.class) val = (T) createBinderArray();
        else if (componentType.isArray()) {
            return (T) createNestedFixedArray(componentType, dimensions, null, null);
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
        if (val != null && java.lang.reflect.Array.getLength(val) != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + java.lang.reflect.Array.getLength(val));
        }
        return val;
    }

    /** AOSP :4544 */
    @SuppressWarnings("unchecked")
    public <T, S extends IInterface> T createFixedArray(
            Class<T> cls, java.util.function.Function<IBinder, S> asInterface,
            int... dimensions) {
        ensureClassHasExpectedDimensions(cls, dimensions.length);
        T val = null;
        final Class<?> componentType = cls.getComponentType();
        if (IInterface.class.isAssignableFrom(componentType)) {
            val = (T) createInterfaceArray(
                    n -> (S[]) java.lang.reflect.Array.newInstance(componentType, n), asInterface);
        } else if (componentType.isArray()) {
            return (T) createNestedFixedArray(componentType, dimensions, asInterface, null);
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
        if (val != null && java.lang.reflect.Array.getLength(val) != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + java.lang.reflect.Array.getLength(val));
        }
        return val;
    }

    /** AOSP :4604 */
    @SuppressWarnings("unchecked")
    public <T, S extends Parcelable> T createFixedArray(
            Class<T> cls, Parcelable.Creator<S> c, int... dimensions) {
        ensureClassHasExpectedDimensions(cls, dimensions.length);
        T val = null;
        final Class<?> componentType = cls.getComponentType();
        if (Parcelable.class.isAssignableFrom(componentType)) {
            val = (T) createTypedArray(c);
        } else if (componentType.isArray()) {
            return (T) createNestedFixedArray(componentType, dimensions, null, c);
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
        if (val != null && java.lang.reflect.Array.getLength(val) != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + java.lang.reflect.Array.getLength(val));
        }
        return val;
    }

    // The three createFixedArray overloads share one multi-dimensional branch
    // in AOSP (copy-pasted three times there); it is factored out here because
    // only the per-element read differs. Behaviour is identical: read the outer
    // length, check it against dimensions[0], allocate the WHOLE
    // multi-dimensional array at once from the innermost component type, then
    // fill each slice with readFixedArray.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object createNestedFixedArray(Class<?> componentType, int[] dimensions,
            java.util.function.Function asInterface, Parcelable.Creator c) {
        int length = readInt();
        if (length < 0) return null;
        if (length != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + length);
        }
        Class<?> innermost = componentType.getComponentType();
        while (innermost.isArray()) innermost = innermost.getComponentType();
        Object val = java.lang.reflect.Array.newInstance(innermost, dimensions);
        for (int i = 0; i < length; i++) {
            Object slice = java.lang.reflect.Array.get(val, i);
            if (asInterface != null) {
                readFixedArray(slice,
                        (java.util.function.Function<IBinder, IInterface>) asInterface);
            } else if (c != null) {
                readFixedArray(slice, (Parcelable.Creator<Parcelable>) c);
            } else {
                readFixedArray(slice);
            }
        }
        return val;
    }

    public <T> void readTypedArray(T[] val, Parcelable.Creator<T> c) {
        int n = readInt();
        if (val != null && n == val.length) {
            for (int i = 0; i < n; i++) val[i] = readTypedObject(c);
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    // --- SparseArray<Parcelable> / SparseBooleanArray / SparseIntArray ---
    public <T> void writeSparseArray(android.util.SparseArray<T> val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) { writeInt(val.keyAt(i)); writeValue(val.valueAt(i)); }
    }
    @SuppressWarnings("unchecked")
    public <T> android.util.SparseArray<T> readSparseArray(ClassLoader loader) {
        int n = readInt(); if (n < 0) return null;
        android.util.SparseArray<T> sa = new android.util.SparseArray<T>(n);
        while (n > 0) { int key = readInt(); sa.append(key, (T) readValue(loader)); n--; }
        return sa;
    }
    @SuppressWarnings("unchecked")
    public <T> android.util.SparseArray<T> readSparseArray(ClassLoader loader,
                                                           Class<? extends T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        int n = readInt(); if (n < 0) return null;
        android.util.SparseArray<T> sa = new android.util.SparseArray<T>(n);
        while (n > 0) {
            int key = readInt();
            sa.append(key, (T) checkTypeToUnparcel(clazz, readValue(loader)));
            n--;
        }
        return sa;
    }
    public void writeSparseBooleanArray(android.util.SparseBooleanArray val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) { writeInt(val.keyAt(i)); writeByte((byte) (val.valueAt(i) ? 1 : 0)); }
    }
    public android.util.SparseBooleanArray readSparseBooleanArray() {
        int n = readInt(); if (n < 0) return null;
        android.util.SparseBooleanArray sa = new android.util.SparseBooleanArray(n);
        while (n > 0) { int key = readInt(); boolean value = readByte() == 1; sa.append(key, value); n--; }
        return sa;
    }
    public void writeSparseIntArray(android.util.SparseIntArray val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) { writeInt(val.keyAt(i)); writeInt(val.valueAt(i)); }
    }
    public android.util.SparseIntArray readSparseIntArray() {
        int n = readInt(); if (n < 0) return null;
        android.util.SparseIntArray sa = new android.util.SparseIntArray(n);
        while (n > 0) { int key = readInt(); int value = readInt(); sa.append(key, value); n--; }
        return sa;
    }

    // --- Size / SizeF (AOSP writes two ints/floats) ---
    public void writeSize(android.util.Size val) {
        if (val == null) { writeInt(-1); writeInt(-1); return; }
        writeInt(val.getWidth()); writeInt(val.getHeight());
    }
    public android.util.Size readSize() { return new android.util.Size(readInt(), readInt()); }
    // AOSP writes two floats, same shape as Size's two ints.
    public void writeSizeF(android.util.SizeF val) {
        if (val == null) { writeFloat(-1f); writeFloat(-1f); return; }
        writeFloat(val.getWidth()); writeFloat(val.getHeight());
    }
    public android.util.SizeF readSizeF() { return new android.util.SizeF(readFloat(), readFloat()); }

    // --- Serializable: the class name plus real ObjectOutputStream bytes,
    //     AOSP Parcel.java:2097-2117 / 3429-3454. ---
    public void writeSerializable(java.io.Serializable s) {
        if (s == null) { writeString(null); return; }
        String name = s.getClass().getName();
        writeString(name);
        java.io.ByteArrayOutputStream bao = new java.io.ByteArrayOutputStream();
        try {
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bao);
            oos.writeObject(s);
            oos.close();
            writeByteArray(bao.toByteArray());
        } catch (java.io.IOException ioe) {
            throw new RuntimeException("Parcelable encountered IOException writing serializable object "
                + "(name = " + name + ")", ioe);
        }
    }
    public java.io.Serializable readSerializable() {
        return (java.io.Serializable) readSerializableInternal(null);
    }
    // AOSP declares `<T> T readSerializable(ClassLoader, Class<T>)` with an
    // UNBOUNDED T, so the erased descriptor returns Object. Bounding T to
    // Serializable here erased the return to java.io.Serializable, and app
    // bytecode compiled against the SDK (which calls the ()Ljava/lang/Object;
    // descriptor) missed it entirely with NoSuchMethodError.
    @SuppressWarnings("unchecked")
    public <T> T readSerializable(ClassLoader loader, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        return (T) checkTypeToUnparcel(clazz, readSerializableInternal(loader));
    }

    private Object readSerializableInternal(final ClassLoader loader) {
        String name = readString();
        if (name == null) return null;
        byte[] serializedData = createByteArray();
        if (serializedData == null) return null;
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(serializedData);
        try {
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais) {
                @Override protected Class<?> resolveClass(java.io.ObjectStreamClass osClass)
                        throws java.io.IOException, ClassNotFoundException {
                    // AOSP tries the caller's loader, then falls through to
                    // ObjectInputStream's latestUserDefinedLoader. That
                    // mechanism does NOT translate: on Android it skips the
                    // BOOT loader and lands on the app's, but on our topology
                    // the framework is defined by the SYSTEM loader, which
                    // latestUserDefinedLoader accepts and stops at, so it never
                    // reaches AppClassLoader. A verbatim port therefore turns a
                    // currently-correct app-Serializable round trip into a
                    // ClassNotFoundException. What AOSP OBSERVABLY does is "the
                    // app's classes resolve", so we keep its two steps and add
                    // the app loader behind them rather than in front -- a
                    // framework/JDK class still resolves exactly as before.
                    // Measured on this box, JDK 21; see
                    // docs/parcel-real-marshalling_2026-08-10.md section 3.4.
                    if (loader != null) {
                        Class<?> c = Class.forName(osClass.getName(), false, loader);
                        if (c != null) return c;
                    }
                    try {
                        return super.resolveClass(osClass);
                    } catch (ClassNotFoundException e) {
                        ClassLoader app = appVisibleClassLoader();
                        if (app != null) return Class.forName(osClass.getName(), false, app);
                        throw e;
                    }
                }
            };
            return ois.readObject();
        } catch (java.io.IOException ioe) {
            throw new BadParcelableException("Parcelable encountered IOException reading a "
                + "Serializable object (name = " + name + ")", ioe);
        } catch (ClassNotFoundException cnfe) {
            throw new BadParcelableException("Parcelable encountered ClassNotFoundException "
                + "reading a Serializable object (name = " + name + ")", cnfe);
        }
    }

    /** The loader that can see GUEST classes. Reflective on purpose:
     *  android.os must not take a hard compile edge into com.macapk.loader
     *  (ActivityHost transitively drags in the whole Activity/View stack), and
     *  this class has to stay loadable on a bare JDK for debug/parcel_verify.sh.
     *  The thread's context loader is set to the app loader at three points in
     *  Boot, so it is the cheaper answer and is tried first. */
    private static ClassLoader appVisibleClassLoader() {
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl != null && tccl != Parcel.class.getClassLoader()) return tccl;
        } catch (Throwable ignored) { }
        try {
            Class<?> host = Class.forName("com.macapk.loader.ActivityHost", false,
                                          Parcel.class.getClassLoader());
            Object cl = host.getMethod("macapkAppClassLoader").invoke(null);
            return (ClassLoader) cl;
        } catch (Throwable ignored) { }
        return null;
    }

    // --- exception-shaped helpers some AIDL/stub code references ---
    public final RuntimeException createException() { return new RuntimeException(); }

    // --- buffer accounting completeness ---
    // AOSP Parcel.java:504 specifies this as exactly dataSize()-dataPosition().
    // Clamped at zero because setDataPosition may legally seek past the end.
    public final int dataAvail() { return pos >= size ? 0 : size - pos; }
    public final void setDataSize(int newSize) {
        // AOSP: "Change the amount of data in the parcel. Can be either smaller
        // or larger than the current size" (Parcel.java:527). The native
        // Parcel::continueWrite clamps BOTH mDataSize and mDataPos down to the
        // new size, so shrinking also pulls the cursor back inside the parcel.
        if (newSize < 0) return;
        ensure(newSize);
        if (newSize > size) java.util.Arrays.fill(data, size, newSize, (byte) 0);
        if (pos > newSize) pos = newSize;
        size = newSize;
    }
    public final void setDataCapacity(int newCapacity) {
        // AOSP: "Can not be less than dataSize" -- this only ever grows.
        ensure(newCapacity);
    }

    // AOSP scans the parcel's object table for BINDER_TYPE_FD; ours holds the
    // live FileDescriptor / ParcelFileDescriptor entries in the same place.
    public final boolean hasFileDescriptors() {
        for (Object o : objects) {
            if (o instanceof java.io.FileDescriptor || o instanceof ParcelFileDescriptor) return true;
        }
        return false;
    }

    /** AOSP :1002 -- the same question restricted to a byte RANGE: an fd
     *  entry whose marker lies inside it. AOSP's native side rejects a range
     *  outside the parcel, and so do we. */
    public boolean hasFileDescriptors(int offset, int length) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IllegalArgumentException(
                    "Invalid range: offset=" + offset + " length=" + length
                    + " dataSize=" + size);
        }
        for (int i = 0; i < objectOffsetCount; i++) {
            final int at = objectOffsets[i];
            if (at < offset || at > offset + length - 4) continue;
            final int idx = (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8)
                          | ((data[at + 2] & 0xff) << 16) | ((data[at + 3] & 0xff) << 24);
            if (idx < 0 || idx >= objects.size()) continue;
            Object o = objects.get(idx);
            if (o instanceof java.io.FileDescriptor || o instanceof ParcelFileDescriptor) return true;
        }
        return false;
    }

    /** AOSP :1152 -- verbatim. AIDL-generated stubs call this after reading
     *  their arguments so a version mismatch surfaces as a diagnosable
     *  BadParcelableException instead of silent garbage. */
    public void enforceNoDataAvail() {
        final int n = dataAvail();
        if (n > 0) {
            throw new BadParcelableException("Parcel data not fully consumed, unread size: " + n);
        }
    }

    // AOSP :735 -- `addFlags(FLAG_PROPAGATE_ALLOW_BLOCKING)`: "whether the
    // object should propagate allow-blocking to the binder objects read from
    // this parcel". allow-blocking marks a binder proxy as safe to call from a
    // thread with StrictMode's no-blocking policy. Mac.apk's binders are all
    // local (Binder.transact dispatches synchronously in-process), so nothing
    // consults the flag -- but AOSP has no getter either, so the flag is
    // write-only there as well, and setting it is genuinely all the method
    // does from a caller's point of view.
    private boolean mPropagateAllowBlocking;
    public void setPropagateAllowBlocking() { mPropagateAllowBlocking = true; }

    // --- marshall/unmarshall. Now genuinely meaningful: the byte stream holds
    //     flattened data, not index markers. The exceptions are an IBinder and
    //     an empty-writeToParcel Parcelable, both of which are still an index
    //     into the side table -- which is no worse than Android, where a
    //     marshalled binder is also only valid in-process. ---
    public final byte[] marshall() {
        // dataSize, not the cursor: AOSP hands back everything written, and the
        // probe pins marshall().length == dataSize() as the one blob invariant.
        return java.util.Arrays.copyOf(data, size);
    }
    public final void unmarshall(byte[] src, int offset, int length) {
        // AOSP's android_os_Parcel_unmarshall REPLACES the contents:
        // setDataSize(length); setDataPosition(0); memcpy(writeInplace(length)).
        if (src == null || length < 0) return;
        ensure(length);
        System.arraycopy(src, offset, data, 0, length);
        // The old bytes are gone, so any index marker that pointed into the
        // side table is gone with them. Keeping the entries would let a stale
        // index resolve to an unrelated object.
        objects.clear();
        objectOffsetCount = 0;
        size = length;
        pos = length;
    }
    public final void appendFrom(Parcel parcel, int offset, int length) {
        if (parcel == null || length <= 0) return;
        final int destStart = pos;
        ensure(pos + length);
        System.arraycopy(parcel.data, offset, data, pos, length);
        wrote(length);
        // Carry the source's side-table entries across and REBASE the markers we
        // just copied. Without the rebase, slicing a Bundle's bytes out of one
        // Parcel into another (BaseBundle.readFromParcelInner does exactly that)
        // would resolve every index against this Parcel's table and hand back
        // the wrong object -- or none. `objectOffsets` exists for this: a marker
        // is indistinguishable from any other int in the raw bytes, so the only
        // way to find one is to have recorded where it was written.
        if (parcel.objectOffsetCount == 0) return;
        final int base = objects.size();
        objects.addAll(parcel.objects);
        for (int i = 0; i < parcel.objectOffsetCount; i++) {
            final int srcOff = parcel.objectOffsets[i];
            if (srcOff < offset || srcOff > offset + length - 4) continue;
            final int at = destStart + (srcOff - offset);
            final int oldIdx = (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8)
                             | ((data[at + 2] & 0xff) << 16) | ((data[at + 3] & 0xff) << 24);
            if (oldIdx < 0) continue;   // a null ref, written as a bare -1
            final int v = base + oldIdx;
            data[at]     = (byte) v;
            data[at + 1] = (byte) (v >>> 8);
            data[at + 2] = (byte) (v >>> 16);
            data[at + 3] = (byte) (v >>> 24);
            noteObjectOffset(at);
        }
    }

    // FileDescriptor marshalling. AOSP puts a BINDER_TYPE_FD object in the
    // parcel and the driver installs a duplicate in the receiver. Here the
    // descriptor is an entry in the object table (like a binder); within one
    // process it stays the live FileDescriptor, and MacApkBinderTransport turns
    // it into a Mach fileport (a kernel-held duplicate) when the parcel crosses
    // processes, installing a fresh fd on the far side. These were no-ops that
    // read back null, so every fd sent through a Parcel disappeared.
    // readRawFileDescriptor returns java.io.FileDescriptor, as AOSP declares it
    // (it used to be typed ParcelFileDescriptor, so the AOSP descriptor would
    // not link).
    public final void writeFileDescriptor(java.io.FileDescriptor val) {
        if (val == null) throw new NullPointerException("FileDescriptor");
        writeObjectRef(val);
    }
    public final void writeRawFileDescriptor(java.io.FileDescriptor val) {
        writeFileDescriptor(val);
    }
    /** AOSP: a NEW ParcelFileDescriptor holding a dup of the parcel's fd. */
    public final ParcelFileDescriptor readFileDescriptor() {
        java.io.FileDescriptor fd = readRawFileDescriptor();
        if (fd == null) return null;
        try {
            return ParcelFileDescriptor.dup(fd);
        } catch (java.io.IOException e) {
            throw new BadParcelableException("could not dup fd: " + e.getMessage());
        }
    }
    public final java.io.FileDescriptor readRawFileDescriptor() {
        Object o = readObjectRef();
        if (o instanceof java.io.FileDescriptor) return (java.io.FileDescriptor) o;
        if (o instanceof ParcelFileDescriptor) return ((ParcelFileDescriptor) o).getFileDescriptor();
        return null;
    }

    // Blob: AOSP routes large (>~16KB) byte payloads through ashmem; in-process
    // we just treat it as a byte array.
    public final void writeBlob(byte[] b) { writeByteArray(b); }
    /** AOSP :1243 -- the ranged form; the bounds check is
     *  ArrayUtils.throwsIfOutOfBounds, which throws ArrayIndexOutOfBounds. */
    public final void writeBlob(byte[] b, int offset, int len) {
        if (b == null) { writeInt(-1); return; }
        if (len < 0 || offset < 0 || offset > b.length - len) {
            throw new ArrayIndexOutOfBoundsException(
                    "length=" + b.length + "; regionStart=" + offset + "; regionLength=" + len);
        }
        writeByteArray(b, offset, len);
    }
    public final byte[] readBlob() { return createByteArray(); }

    // ---- ByteBuffer marshall/unmarshall (API 36; AOSP :844 / :881) ---------
    // AOSP dispatches on isDirect()/hasArray() only to pick a native entry
    // point; both write the same bytes. Here there is one implementation and
    // the same three argument checks, including the IllegalArgumentException
    // for a buffer that is neither direct nor array-backed (a read-only
    // wrapper of a byte[]), which is a real case a caller can hit.
    public final void marshall(java.nio.ByteBuffer buffer) {
        if (buffer == null) throw new NullPointerException();
        if (buffer.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
        if (!buffer.isDirect() && !buffer.hasArray()) throw new IllegalArgumentException();
        final int position = buffer.position();
        final int remaining = buffer.remaining();
        // AOSP's native side copies min(dataSize, remaining) and reports how
        // much it wrote; the caller's position advances by that amount.
        final int n = Math.min(size, remaining);
        buffer.put(data, 0, n);
        buffer.position(position + n);
    }

    public final void unmarshall(java.nio.ByteBuffer buffer) {
        if (buffer == null) throw new NullPointerException();
        if (!buffer.isDirect() && !buffer.hasArray()) throw new IllegalArgumentException();
        final int position = buffer.position();
        final int remaining = buffer.remaining();
        byte[] tmp = new byte[remaining];
        buffer.get(tmp);
        unmarshall(tmp, 0, remaining);
        // AOSP advances by the FULL remaining, not by what it consumed.
        buffer.position(position + remaining);
    }

    // AOSP STRING_CREATOR: a Parcelable.Creator<String> reading a single string.
    public static final Parcelable.Creator<String> STRING_CREATOR =
        new Parcelable.Creator<String>() {
            @Override public String createFromParcel(Parcel source) { return source.readString(); }
            @Override public String[] newArray(int size) { return new String[size]; }
        };
}
