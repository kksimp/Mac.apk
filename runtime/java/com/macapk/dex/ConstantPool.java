package com.macapk.dex;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JVM class-file constant pool builder with full deduplication.
 *
 * Layout authority: JVMS SE21 section 4.4 ("The Constant Pool"), tag table
 * 4.4-A. Every cp_info starts with a u1 tag; the payload shapes below are
 * transcribed from the individual 4.4.x subsections.
 *
 * Two invariants this class exists to enforce, both of which are the classic
 * way a hand-rolled class writer produces a file that javap reads as garbage:
 *
 *  1. CONSTANT_Long_info and CONSTANT_Double_info take TWO pool slots. JVMS
 *     4.4.5: "All 8-byte constants take up two entries in the constant_pool
 *     table of the class file. ... In retrospect, making 8-byte constants take
 *     two constant pool entries was a poor choice." The second slot is
 *     unusable and must not be written. _alloc() below is the single place
 *     that knows the width, so no caller can get it wrong.
 *  2. Utf8 is MODIFIED UTF-8, not standard UTF-8 (JVMS 4.4.7): the code point
 *     U+0000 is encoded as the two bytes 0xC0 0x80, and supplementary code
 *     points are encoded as their UTF-16 surrogate PAIR, i.e. two 3-byte
 *     sequences (CESU-8), never as a single 4-byte sequence. Passing a String
 *     through StandardCharsets.UTF_8 produces a file that HotSpot rejects with
 *     ClassFormatError on any emoji in a string constant. See toModifiedUtf8().
 *
 * Index stability: an index, once handed out, never changes. Allocation is
 * monotonic and nothing is ever re-packed. Callers may therefore branch on the
 * returned index at emit time (CodeWriter does exactly this to choose the
 * 2-byte `ldc` over the 3-byte `ldc_w` when the index fits in a u1).
 *
 * Not thread safe. One instance per class file.
 */
public final class ConstantPool {

    // ---- tags (JVMS 4.4, Table 4.4-A) ------------------------------------
    public static final int CONSTANT_Utf8               = 1;
    public static final int CONSTANT_Integer            = 3;
    public static final int CONSTANT_Float              = 4;
    public static final int CONSTANT_Long               = 5;
    public static final int CONSTANT_Double             = 6;
    public static final int CONSTANT_Class              = 7;
    public static final int CONSTANT_String             = 8;
    public static final int CONSTANT_Fieldref           = 9;
    public static final int CONSTANT_Methodref          = 10;
    public static final int CONSTANT_InterfaceMethodref = 11;
    public static final int CONSTANT_NameAndType        = 12;
    public static final int CONSTANT_MethodHandle       = 15;
    public static final int CONSTANT_MethodType         = 16;
    public static final int CONSTANT_Dynamic            = 17;
    public static final int CONSTANT_InvokeDynamic      = 18;

    // ---- MethodHandle reference kinds (JVMS 4.4.8, Table 4.4.8-A) --------
    public static final int REF_getField         = 1;
    public static final int REF_getStatic        = 2;
    public static final int REF_putField         = 3;
    public static final int REF_putStatic        = 4;
    public static final int REF_invokeVirtual    = 5;
    public static final int REF_invokeStatic     = 6;
    public static final int REF_invokeSpecial    = 7;
    public static final int REF_newInvokeSpecial = 8;
    public static final int REF_invokeInterface  = 9;

    /**
     * Thrown when a class file would exceed a JVMS structural limit (JVMS
     * 4.11): more than 65534 usable constant pool entries, a Utf8 longer than
     * 65535 bytes, a method longer than 65535 bytes, and so on.
     *
     * This is a distinct type on purpose. enjarify's writeclass.toClassFile
     * catches the equivalent (error.ClassfileLimitExceeded) and retries the
     * whole class with size optimizations enabled; the DEX front end can do
     * the same rather than failing the class outright.
     *
     * <p>Not final: {@link CodeWriter.CodeLengthExceeded} and
     * {@link PoolFullException} refine it, because those are the two cases a
     * front end can act on: an oversize method by outlining or splitting it, a
     * full pool by dropping the constant readings nothing observes.
     */
    public static class LimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public LimitExceededException(String msg) { super(msg); }
    }

    /**
     * The constant_pool_count case of {@link LimitExceededException}, as its own
     * type so a front end can tell it apart from the other limits and retry the
     * class with a pool-saving strategy (DexConverter.rescueWithElision, see
     * ConstReadingElision), exactly as {@link CodeWriter.CodeLengthExceeded}
     * lets it retry an oversize method.
     */
    public static final class PoolFullException extends LimitExceededException {
        private static final long serialVersionUID = 1L;
        public PoolFullException(String msg) { super(msg); }
    }

    /** "Utf8=..., Long=..." -- what the pool is spent on, for the overflow message. */
    public String census() {
        int[] n = new int[19];
        int end = (mode == Mode.SPLIT) ? MAX_ENTRIES : bot;
        for (int i = 1; i < end; i++) {
            int t = tags[i] & 0xFF;
            if (t < n.length) n[t]++;
        }
        String[] names = { null, "Utf8", null, "Integer", "Float", "Long", "Double", "Class",
                "String", "Fieldref", "Methodref", "InterfaceMethodref", "NameAndType", null,
                null, "MethodHandle", "MethodType", "Dynamic", "InvokeDynamic" };
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < n.length; t++) {
            if (n[t] == 0 || names[t] == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(names[t]).append('=').append(n[t]);
        }
        return sb.toString();
    }

    /**
     * Growable big-endian byte buffer. Lives here because ConstantPool is the
     * leaf of this package's dependency graph, so hosting the shared primitive
     * here keeps this package acyclic (everything depends on ConstantPool;
     * ConstantPool depends on nothing).
     */
    public static final class ByteVector {
        private byte[] buf;
        private int len;

        public ByteVector() { this(64); }
        public ByteVector(int capacity) { buf = new byte[Math.max(8, capacity)]; }

        public int length() { return len; }

        private void ensure(int extra) {
            if (len + extra <= buf.length) return;
            int cap = buf.length;
            while (cap < len + extra) cap <<= 1;
            byte[] nb = new byte[cap];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }

        public ByteVector putU1(int v) {
            ensure(1);
            buf[len++] = (byte) v;
            return this;
        }

        public ByteVector putU2(int v) {
            ensure(2);
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) v;
            return this;
        }

        public ByteVector putU4(int v) {
            ensure(4);
            buf[len++] = (byte) (v >>> 24);
            buf[len++] = (byte) (v >>> 16);
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) v;
            return this;
        }

        public ByteVector putU8(long v) {
            putU4((int) (v >>> 32));
            putU4((int) v);
            return this;
        }

        public ByteVector putBytes(byte[] b) { return putBytes(b, 0, b.length); }

        public ByteVector putBytes(byte[] b, int off, int n) {
            ensure(n);
            System.arraycopy(b, off, buf, len, n);
            len += n;
            return this;
        }

        /** Repeats a byte sequence n times (constant pool placeholder fill). */
        public ByteVector putRepeated(byte[] b, int n) {
            ensure(b.length * n);
            for (int i = 0; i < n; i++) putBytes(b);
            return this;
        }

        /** Overwrites the u2 at absolute position pos. Used for back-patching. */
        public void setU2(int pos, int v) {
            buf[pos] = (byte) (v >>> 8);
            buf[pos + 1] = (byte) v;
        }

        /** Overwrites the u4 at absolute position pos. */
        public void setU4(int pos, int v) {
            buf[pos] = (byte) (v >>> 24);
            buf[pos + 1] = (byte) (v >>> 16);
            buf[pos + 2] = (byte) (v >>> 8);
            buf[pos + 3] = (byte) v;
        }

        public byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }

    /**
     * Allocation strategy.
     *
     * SIMPLE allocates every entry upward from index 1 and writes a pool of
     * exactly the size used. This is the default and what nearly every class
     * wants.
     *
     * SPLIT reproduces enjarify's SplitConstantPool (jvm/constantpool.py:160):
     * ldc-loadable constants are allocated upward from 1 while everything else
     * (Utf8, NameAndType, the *ref triples) is allocated DOWNWARD from 65534,
     * so the low 255 slots that the 2-byte `ldc` can address are not wasted on
     * entries no `ldc` can ever name. The cost is that the written pool is
     * always the maximum 65535 entries with the unused middle filled by empty
     * Utf8 placeholders, which adds up to ~196 KB per class file. Use it only
     * on the retry path for a class that overflowed, or when code size in the
     * method bodies matters more than file size.
     */
    public enum Mode { SIMPLE, SPLIT }

    private static final int MAX_ENTRIES = 65535;         // constant_pool_count is u2
    private static final byte[] PLACEHOLDER = { CONSTANT_Utf8, 0, 0 }; // Utf8 of length 0

    private final Mode mode;

    // Parallel entry arrays. tags[i] == 0 marks an unused slot (the second half
    // of a Long/Double, or an unallocated slot in SPLIT mode).
    private byte[] tags;
    private int[] x;      // first operand: utf8 table index, u2 ref, int bits, or high 32 bits
    private int[] y;      // second operand: u2 ref, or low 32 bits of an 8-byte constant

    private byte[][] utf8Table = new byte[16][];
    private int utf8Count;

    private int bot = 1;             // next free low index
    private int top = MAX_ENTRIES;   // one past the last free high index (SPLIT only)

    // Two caches, deliberately in separate key namespaces. utf8ByString is a
    // memo for the String entry point; utf8ByBytes is the CANONICAL dedup,
    // keyed by the exact modified-UTF-8 bytes. Sharing one map would be a
    // silent-corruption bug: the ISO-8859-1 view of a multi-byte encoding can
    // equal some other String, and a lookup would then return an entry whose
    // bytes are not the bytes asked for.
    private final Map<String, Integer> utf8ByString = new HashMap<>();
    private final Map<String, Integer> utf8ByBytes = new HashMap<>();
    private final Map<Integer, Integer> intMap = new HashMap<>();
    private final Map<Integer, Integer> floatMap = new HashMap<>();
    private final Map<Long, Integer> longMap = new HashMap<>();
    private final Map<Long, Integer> doubleMap = new HashMap<>();
    private final Map<Long, Integer> refMap = new HashMap<>();

    public ConstantPool() { this(Mode.SIMPLE); }

    public ConstantPool(Mode mode) {
        this.mode = mode;
        int initial = (mode == Mode.SPLIT) ? MAX_ENTRIES : 256;
        tags = new byte[initial];
        x = new int[initial];
        y = new int[initial];
    }

    public Mode mode() { return mode; }

    /** constant_pool_count as it will be written (JVMS 4.1). */
    public int count() { return (mode == Mode.SPLIT) ? MAX_ENTRIES : bot; }

    /** Number of slots still allocatable. */
    public int space() { return top - bot; }

    // ---- allocation ------------------------------------------------------

    private void grow(int need) {
        if (need <= tags.length) return;
        int cap = tags.length;
        while (cap < need) cap <<= 1;
        if (cap > MAX_ENTRIES) cap = MAX_ENTRIES;
        byte[] nt = new byte[cap];
        int[] nx = new int[cap];
        int[] ny = new int[cap];
        System.arraycopy(tags, 0, nt, 0, tags.length);
        System.arraycopy(x, 0, nx, 0, x.length);
        System.arraycopy(y, 0, ny, 0, y.length);
        tags = nt; x = nx; y = ny;
    }

    /**
     * width is 2 for Long/Double (JVMS 4.4.5: 8-byte constants occupy two
     * entries), 1 for everything else. `low` requests bottom-up allocation in
     * SPLIT mode and is ignored in SIMPLE mode.
     */
    private int alloc(int tag, boolean low, int width) {
        if (space() < width) {
            throw new PoolFullException("constant pool full (65534 entries; " + census() + ")");
        }
        int index;
        if (mode == Mode.SPLIT && !low) {
            top -= width;
            index = top;
        } else {
            index = bot;
            bot += width;
            grow(bot);
        }
        tags[index] = (byte) tag;
        return index;
    }

    private static boolean isLoadable(int tag) {
        // JVMS 6.5 ldc: the entry must be loadable. Bias these toward the low
        // slots in SPLIT mode so the 2-byte ldc can address them.
        return tag == CONSTANT_Integer || tag == CONSTANT_Float
                || tag == CONSTANT_String || tag == CONSTANT_Class
                || tag == CONSTANT_MethodHandle || tag == CONSTANT_MethodType
                || tag == CONSTANT_Dynamic;
    }

    private static long key(int tag, int a, int b) {
        // a and b are u2 values, so 17 bits each is generous headroom.
        return ((long) tag << 34) | ((long) a << 17) | (long) b;
    }

    private int refEntry(int tag, int a, int b) {
        long k = key(tag, a, b);
        Integer got = refMap.get(k);
        if (got != null) return got;
        int index = alloc(tag, isLoadable(tag), 1);
        x[index] = a;
        y[index] = b;
        refMap.put(k, index);
        return index;
    }

    // ---- Utf8 (JVMS 4.4.7) ----------------------------------------------

    /**
     * Encodes a String as modified UTF-8 per JVMS 4.4.7.
     *
     * The three differences from standard UTF-8 that matter:
     *   - U+0000 is 0xC0 0x80, so a NUL can never appear inside the bytes.
     *   - Code points above U+FFFF are encoded as the two 3-byte sequences of
     *     their UTF-16 surrogate pair. Java Strings already hold surrogate
     *     pairs as two chars, so encoding char-by-char yields this for free;
     *     the bug is only introduced by routing through a real UTF-8 encoder.
     *   - The length prefix counts BYTES, not characters, and is a u2.
     */
    public static byte[] toModifiedUtf8(String s) {
        int n = s.length();
        // Fast path: pure single-byte ASCII with no NUL.
        boolean ascii = true;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == 0 || c > 0x7F) { ascii = false; break; }
        }
        if (ascii) return s.getBytes(StandardCharsets.ISO_8859_1);

        ByteVector bv = new ByteVector(n + (n >> 1) + 8);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                bv.putU1(c);
            } else if (c <= 0x07FF) { // covers c == 0 as the 0xC0 0x80 pair
                bv.putU1(0xC0 | ((c >> 6) & 0x1F));
                bv.putU1(0x80 | (c & 0x3F));
            } else {
                bv.putU1(0xE0 | ((c >> 12) & 0x0F));
                bv.putU1(0x80 | ((c >> 6) & 0x3F));
                bv.putU1(0x80 | (c & 0x3F));
            }
        }
        return bv.toByteArray();
    }

    /** Adds (or reuses) a CONSTANT_Utf8_info for the given String. */
    public int utf8(String s) {
        Integer got = utf8ByString.get(s);
        if (got != null) return got;
        int index = utf8Raw(toModifiedUtf8(s));
        utf8ByString.put(s, index);
        return index;
    }

    /**
     * Adds (or reuses) a CONSTANT_Utf8_info from bytes that are ALREADY in
     * modified UTF-8 form. DEX string_data_items are stored in exactly this
     * encoding (dex format: "MUTF-8"), so a DEX front end that keeps the raw
     * bytes can hand them straight through and skip a decode/re-encode round
     * trip that could only lose information on malformed input.
     */
    public int utf8Raw(byte[] mutf8) {
        if (mutf8.length > 65535) {
            throw new LimitExceededException("Utf8 constant longer than 65535 bytes");
        }
        // ISO-8859-1 is a lossless byte<->char bijection, so this key compares
        // the exact bytes without allocating a wrapper type.
        String k = new String(mutf8, StandardCharsets.ISO_8859_1);
        Integer got = utf8ByBytes.get(k);
        if (got != null) return got;

        int index = alloc(CONSTANT_Utf8, false, 1);
        if (utf8Count == utf8Table.length) {
            byte[][] nt = new byte[utf8Table.length * 2][];
            System.arraycopy(utf8Table, 0, nt, 0, utf8Count);
            utf8Table = nt;
        }
        utf8Table[utf8Count] = mutf8;
        x[index] = utf8Count++;
        utf8ByBytes.put(k, index);
        return index;
    }

    // ---- the rest of the pool -------------------------------------------

    /** CONSTANT_Class_info (JVMS 4.4.1). Takes an INTERNAL name ("java/lang/Object")
     *  or, for an array type, a descriptor ("[I", "[Ljava/lang/String;"). */
    public int classRef(String internalName) {
        return refEntry(CONSTANT_Class, utf8(internalName), 0);
    }

    /** CONSTANT_String_info (JVMS 4.4.3). */
    public int stringRef(String value) {
        return refEntry(CONSTANT_String, utf8(value), 0);
    }

    /** CONSTANT_String_info whose bytes are already modified UTF-8. */
    public int stringRefRaw(byte[] mutf8) {
        return refEntry(CONSTANT_String, utf8Raw(mutf8), 0);
    }

    /** CONSTANT_Integer_info (JVMS 4.4.4). */
    public int integer(int value) {
        Integer got = intMap.get(value);
        if (got != null) return got;
        int index = alloc(CONSTANT_Integer, true, 1);
        x[index] = value;
        intMap.put(value, index);
        return index;
    }

    /** CONSTANT_Float_info from the IEEE 754 bit pattern. DEX carries raw bits,
     *  and raw bits are also the only key that keeps -0.0f distinct from 0.0f
     *  and preserves a specific NaN payload. */
    public int floatBits(int bits) {
        Integer got = floatMap.get(bits);
        if (got != null) return got;
        int index = alloc(CONSTANT_Float, true, 1);
        x[index] = bits;
        floatMap.put(bits, index);
        return index;
    }

    public int floatConst(float value) { return floatBits(Float.floatToRawIntBits(value)); }

    /** CONSTANT_Long_info (JVMS 4.4.5). Consumes TWO pool slots. */
    public int longConst(long value) {
        Integer got = longMap.get(value);
        if (got != null) return got;
        int index = alloc(CONSTANT_Long, false, 2);
        x[index] = (int) (value >>> 32);
        y[index] = (int) value;
        longMap.put(value, index);
        return index;
    }

    /** CONSTANT_Double_info from the IEEE 754 bit pattern. Consumes TWO slots. */
    public int doubleBits(long bits) {
        Integer got = doubleMap.get(bits);
        if (got != null) return got;
        int index = alloc(CONSTANT_Double, false, 2);
        x[index] = (int) (bits >>> 32);
        y[index] = (int) bits;
        doubleMap.put(bits, index);
        return index;
    }

    public int doubleConst(double value) { return doubleBits(Double.doubleToRawLongBits(value)); }

    /** CONSTANT_NameAndType_info (JVMS 4.4.6). */
    public int nameAndType(String name, String descriptor) {
        return refEntry(CONSTANT_NameAndType, utf8(name), utf8(descriptor));
    }

    /** CONSTANT_Fieldref_info (JVMS 4.4.2). */
    public int fieldRef(String owner, String name, String descriptor) {
        return refEntry(CONSTANT_Fieldref, classRef(owner), nameAndType(name, descriptor));
    }

    /** CONSTANT_Methodref_info (JVMS 4.4.2). */
    public int methodRef(String owner, String name, String descriptor) {
        return refEntry(CONSTANT_Methodref, classRef(owner), nameAndType(name, descriptor));
    }

    /** CONSTANT_InterfaceMethodref_info (JVMS 4.4.2). */
    public int interfaceMethodRef(String owner, String name, String descriptor) {
        return refEntry(CONSTANT_InterfaceMethodref, classRef(owner), nameAndType(name, descriptor));
    }

    /**
     * Either a Methodref or an InterfaceMethodref depending on `itf`. Which one
     * is REQUIRED to match the owner's kind: JVMS 4.4.2 requires
     * CONSTANT_InterfaceMethodref for a method in an interface, and HotSpot
     * throws IncompatibleClassChangeError at first execution if the wrong one
     * is used. DEX's invoke-interface / invoke-virtual distinction plus the
     * class-kind of the owner is what the front end resolves this from.
     */
    public int anyMethodRef(String owner, String name, String descriptor, boolean itf) {
        return itf ? interfaceMethodRef(owner, name, descriptor)
                   : methodRef(owner, name, descriptor);
    }

    /** CONSTANT_MethodHandle_info (JVMS 4.4.8). refIndex must already point at
     *  a Fieldref/Methodref/InterfaceMethodref appropriate for referenceKind. */
    public int methodHandle(int referenceKind, int refIndex) {
        return refEntry(CONSTANT_MethodHandle, referenceKind, refIndex);
    }

    public int methodHandle(int referenceKind, String owner, String name, String descriptor, boolean itf) {
        int ref;
        switch (referenceKind) {
            case REF_getField:
            case REF_getStatic:
            case REF_putField:
            case REF_putStatic:
                ref = fieldRef(owner, name, descriptor);
                break;
            case REF_invokeInterface:
                ref = interfaceMethodRef(owner, name, descriptor);
                break;
            default:
                ref = anyMethodRef(owner, name, descriptor, itf);
                break;
        }
        return methodHandle(referenceKind, ref);
    }

    /** CONSTANT_MethodType_info (JVMS 4.4.9). */
    public int methodType(String descriptor) {
        return refEntry(CONSTANT_MethodType, utf8(descriptor), 0);
    }

    /** CONSTANT_InvokeDynamic_info (JVMS 4.4.10). bootstrapMethodAttrIndex is
     *  an index into the class's BootstrapMethods attribute, not the pool. */
    public int invokeDynamic(int bootstrapMethodAttrIndex, String name, String descriptor) {
        return refEntry(CONSTANT_InvokeDynamic, bootstrapMethodAttrIndex, nameAndType(name, descriptor));
    }

    /** CONSTANT_Dynamic_info (JVMS 4.4.10), the constant (condy) form. */
    public int dynamicConstant(int bootstrapMethodAttrIndex, String name, String descriptor) {
        return refEntry(CONSTANT_Dynamic, bootstrapMethodAttrIndex, nameAndType(name, descriptor));
    }

    /** True if the entry at `index` is a category-2 constant, i.e. one that
     *  ldc2_w loads rather than ldc/ldc_w. */
    public boolean isCategory2(int index) {
        int tag = tags[index] & 0xFF;
        return tag == CONSTANT_Long || tag == CONSTANT_Double;
    }

    public int tagAt(int index) { return tags[index] & 0xFF; }

    /**
     * The member a CONSTANT_Fieldref names, as {@link #memberKey} would spell
     * it, or null when {@code index} is not a Fieldref. Read only: it never
     * allocates an entry, so asking cannot change the class file.
     */
    String fieldRefKey(int index) {
        if (index <= 0 || index >= tags.length || (tags[index] & 0xFF) != CONSTANT_Fieldref) {
            return null;
        }
        int nat = y[index];
        return utf8Key(x[x[index]]) + '\0' + utf8Key(x[nat]) + '\0' + utf8Key(y[nat]);
    }

    /**
     * owner, name and descriptor joined by NUL, each as its modified-UTF-8
     * bytes read as ISO-8859-1. That is a lossless view of the exact bytes
     * (the JVM compares names byte for byte), and NUL never occurs inside
     * modified UTF-8 (JVMS 4.4.7), so the key is unambiguous.
     */
    static String memberKey(String owner, String name, String descriptor) {
        return latin1(toModifiedUtf8(owner)) + '\0' + latin1(toModifiedUtf8(name))
                + '\0' + latin1(toModifiedUtf8(descriptor));
    }

    private String utf8Key(int utf8Index) { return latin1(utf8Table[x[utf8Index]]); }

    private static String latin1(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    // ---- serialization ---------------------------------------------------

    private void writeEntry(ByteVector out, int index) {
        int tag = tags[index] & 0xFF;
        if (tag == 0) return;   // unused slot (second half of an 8-byte constant)
        out.putU1(tag);
        switch (tag) {
            case CONSTANT_Utf8: {
                byte[] b = utf8Table[x[index]];
                out.putU2(b.length);
                out.putBytes(b);
                break;
            }
            case CONSTANT_Integer:
            case CONSTANT_Float:
                out.putU4(x[index]);
                break;
            case CONSTANT_Long:
            case CONSTANT_Double:
                out.putU4(x[index]);
                out.putU4(y[index]);
                break;
            case CONSTANT_Class:
            case CONSTANT_String:
            case CONSTANT_MethodType:
                out.putU2(x[index]);
                break;
            case CONSTANT_MethodHandle:
                out.putU1(x[index]);   // reference_kind is a u1
                out.putU2(y[index]);
                break;
            default:
                // Fieldref / Methodref / InterfaceMethodref / NameAndType /
                // Dynamic / InvokeDynamic are all u2 + u2.
                out.putU2(x[index]);
                out.putU2(y[index]);
                break;
        }
    }

    /** Writes constant_pool_count followed by the pool itself (JVMS 4.1). */
    public void writeTo(ByteVector out) {
        out.putU2(count());
        if (mode == Mode.SPLIT) {
            for (int i = 1; i < bot; i++) writeEntry(out, i);
            out.putRepeated(PLACEHOLDER, top - bot);
            for (int i = top; i < MAX_ENTRIES; i++) writeEntry(out, i);
        } else {
            for (int i = 1; i < bot; i++) writeEntry(out, i);
        }
    }
}
