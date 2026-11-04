// ClinitPrecompute -- the last rescue for a <clinit> over the JVM's 64 KB
// limit: run it once, at conversion time, and emit only the state it leaves.
//
// WHY
// Dalvik has no code_length limit and the JVM caps a method at 65535 bytes
// (JVMS 4.9.1). The usual oversize <clinit> is a DATA TABLE: d8 turns
// `static final int[][] T = {{...}, ...}` into new-array, fill-array-data and
// aput, and a fill-array-data of N ints is one Dalvik instruction plus a
// payload, but N JVM stores. MethodOutliner and MethodSplitter move code into
// helpers; they cannot cut inside a try range or between a method's first and
// last monitor op, and generated tables are often wrapped in exactly that
// (one `synchronized (Self.class)` block per field, measured on a messaging
// app's 112,411-byte <clinit>). The stub that came next made the class's
// initializer throw, so the CLASS became unusable (ExceptionInInitializerError,
// then NoClassDefFoundError on every later touch) even though the code that
// failed computes nothing but constants.
//
// WHAT
// 1. Interpret the DEX <clinit> concretely, under a whitelist of operations
//    that have no effect outside this class's own static fields and the
//    arrays the initializer itself allocates:
//      constants, moves, const-string, primitive arithmetic and conversions,
//      compares, branches, switches, new-array, filled-new-array,
//      fill-array-data, array-length, aget/aput, check-cast to an array type,
//      sget/sput of a static field DECLARED BY THIS CLASS, invoke-static of a
//      static method DECLARED BY THIS CLASS (interpreted the same way),
//      System.arraycopy, and monitor-enter/exit on this class's own Class
//      object (const-class of itself; nothing else can see the data the block
//      writes before initialization completes, JVMS 5.5).
//    Anything else bails out: any other invoke, a field of another class,
//    new-instance, const-class of another type, instance-of, iget/iput,
//    throw, invoke-custom/polymorphic, method handles, an instruction that
//    WOULD throw on the path actually taken (null, bounds, negative size,
//    division by zero, array store, cast), unbalanced monitors, or a step,
//    depth or size budget running out. Try ranges are allowed: the
//    interpretation is concrete, so a handler could only run if an
//    instruction on the path taken threw, and that already bails.
// 2. The result is the final value of every static field the initializer
//    wrote, with exact types (all primitive array kinds, nested and reference
//    arrays, Strings, null, primitives) and array IDENTITY: two fields (or
//    elements) that hold one array still hold one array.
// 3. Emit a <clinit> that materialises exactly that state, self-contained:
//    array contents are packed into String constants (at most 65535 bytes of
//    modified UTF-8 each, as many as needed) and decoded by small private
//    static helpers of the same class; arrays are built first, then the
//    fields are assigned. Every method stays under the code limit, and the
//    pool is checked for room before anything is emitted.
// Because the whitelist admits no effect outside this class's statics and
// its own fresh arrays, and those cannot be observed before initialization
// completes, writing the final state is indistinguishable from running the
// original code.
//
// WHEN IT RUNS
// Only after everything else failed: DexConverter reaches it on the oversize
// stub path, after the outliner, the splitter and ConstReadingElision, for a
// <clinit> that is still over the limit. A class that already converts is
// never touched, so its bytes are unchanged. A bail-out keeps the stub.
// DEX2JVM_CLINIT_PRECOMPUTE=0 turns it off; DEX2JVM_CLINIT_PRECOMPUTE_FORCE=1
// applies it to EVERY <clinit> it accepts, for a test gate (the semantic
// suite), the same spirit as DEX2JVM_SPLIT_FORCE.

package com.macapk.dex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ClinitPrecompute {

    private ClinitPrecompute() {}

    static final boolean ENABLED = !"0".equals(System.getenv("DEX2JVM_CLINIT_PRECOMPUTE"));

    /** Test knob: precompute every accepted &lt;clinit&gt;, oversize or not. */
    static final boolean FORCE = ENABLED
            && "1".equals(System.getenv("DEX2JVM_CLINIT_PRECOMPUTE_FORCE"));

    private static final ThreadLocal<Boolean> ARMED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> FIRED = ThreadLocal.withInitial(() -> false);

    /** Arms (or disarms) the rescue for translations on THIS thread; returns
     *  the previous state so a caller can restore it in a finally. */
    static boolean arm(boolean on) {
        boolean was = ARMED.get();
        ARMED.set(on && ENABLED);
        return was;
    }

    static boolean armed() { return ARMED.get(); }

    /** Whether an emission started on this thread since the last reset (it
     *  takes constant pool entries): DexConverter retries without the rescue
     *  if the class then runs out of constant pool. */
    static boolean fired() { return FIRED.get(); }

    static void resetFired() { FIRED.set(false); }

    /** Oversize initializers replaced, process wide (counted by
     *  DexConverter.stubPass once the class is final). */
    static final java.util.concurrent.atomic.AtomicInteger precomputed =
            new java.util.concurrent.atomic.AtomicInteger();

    // ---- budgets --------------------------------------------------------
    // Generous for a data table (the motivating one runs ~400 steps and
    // allocates ~65k elements), small enough that a pathological initializer
    // costs the converter a fraction of a second before it bails.

    private static final int MAX_STEPS = 4_000_000;
    private static final int MAX_DEPTH = 16;
    /** Elements allocated by the interpretation, all arrays together. */
    private static final long MAX_ELEMENTS = 4_000_000;
    /** Arrays in the final state: each index is pushed with sipush. */
    private static final int MAX_ARRAYS = 32767;
    /** Elements of one reference array in the final state, for the same reason. */
    private static final int MAX_REF_LENGTH = 32767;
    /** Encoded array data, all String constants together. */
    private static final int MAX_DATA_BYTES = 8 << 20;
    /** One String constant: a CONSTANT_Utf8's length is a u2 (JVMS 4.4.7). */
    private static final int CHUNK_BYTES = 65535;
    /** Upper-bound bytes per generated method, below the 65535 limit; every
     *  method is measured again before it is attached. */
    private static final int METHOD_BUDGET = 60000;

    /** True when {@code m} is a static initializer this rescue may look at. */
    static boolean candidate(DexMethod m) {
        return ENABLED && "<clinit>".equals(m.name()) && "()V".equals(m.descriptor())
                && m.isStatic() && !m.isNative() && !m.isAbstract() && m.codeOffset() != 0;
    }

    /**
     * Interprets {@code m} (the class's &lt;clinit&gt;) and, if every step was
     * whitelisted, gives {@code mw} a body that writes the resulting state and
     * attaches its helper methods to {@code cw}.
     *
     * @return null when the body was emitted, else why not (nothing attached,
     *         {@code mw} left without code)
     */
    static String tryEmit(DexClass cls, DexMethod m, ClassFileWriter cw,
                          ClassFileWriter.MethodWriter mw) {
        Interp in = new Interp(cls);
        try {
            in.runClinit(m);
        } catch (Bail b) {
            return b.getMessage();
        } catch (RuntimeException | StackOverflowError e) {
            return "interpretation failed: " + e;
        }
        try {
            return new Emitter(in, cls, cw, mw).emit();
        } catch (Bail b) {
            return b.getMessage();
        }
    }

    /** One line describing the last successful emission on this thread. */
    static String lastSummary() { return SUMMARY.get(); }

    private static final ThreadLocal<String> SUMMARY = new ThreadLocal<>();

    // ==================================================================
    // Values
    // ==================================================================

    /** Not an error: the initializer did something outside the whitelist. */
    private static final class Bail extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Bail(String why) { super(why, null, false, false); }
    }

    private static Bail bail(String why) { return new Bail(why); }

    /** An array the initializer allocated. Float and double contents are
     *  kept as raw bits (int[] / long[]) so a NaN payload survives. */
    private static final class Arr {
        final int id;          // allocation order: the emitted order
        final String desc;     // "[I", "[[I", "[Ljava/lang/String;"
        final Object data;     // byte[] Z B, char[] C, short[] S, int[] I F, long[] J D, Object[] refs

        Arr(int id, String desc, Object data) {
            this.id = id;
            this.desc = desc;
            this.data = data;
        }

        char kind() { return desc.charAt(1); }
        boolean isRef() { char k = kind(); return k == 'L' || k == '['; }
        int length() { return java.lang.reflect.Array.getLength(data); }
    }

    /** This class's own Class object (const-class of itself). Only monitors
     *  may use it; it never reaches the emitted state. */
    private static final Object SELF_CLASS = new Object() {
        @Override public String toString() { return "<own class object>"; }
    };

    /** One static field declared by the class. */
    private static final class Static {
        final DexField field;
        final String name;
        final String type;
        long bits;          // primitive value; float/double as raw bits
        Object ref;         // reference value: null, String, Arr
        boolean opaque;     // initial value we cannot model: any read bails
        boolean written;

        Static(DexField f) {
            this.field = f;
            this.name = f.name();
            this.type = f.type();
        }

        boolean isRef() { char c = type.charAt(0); return c == 'L' || c == '['; }
    }

    // ==================================================================
    // Interpreter
    // ==================================================================

    private static final byte NONE = 0, NARROW = 1, WLO = 2, WHI = 3, REF = 4;

    private static final class Frame {
        final long[] v;
        final Object[] o;
        final byte[] t;
        int monitors;

        Frame(int n) {
            v = new long[n];
            o = new Object[n];
            t = new byte[n];
        }
    }

    private static final class Interp {
        final DexClass cls;
        final DexFile dex;
        final String self;        // "Lcom/foo/Bar;"
        final Map<String, Static> statics = new LinkedHashMap<>();
        final Map<String, String> strings = new HashMap<>();
        final Map<DexMethod, Instruction[]> decoded = new IdentityHashMap<>();
        int steps;
        long elements;
        int nextId;
        int depth;

        // The result register, read by the move-result right after the
        // instruction that set it.
        byte resTag;
        long resBits;
        Object resRef;

        Interp(DexClass cls) {
            this.cls = cls;
            this.dex = cls.dex();
            this.self = cls.descriptor();
            for (DexField f : cls.fields()) {
                if (!f.isStatic()) continue;
                Static s = new Static(f);
                DexFile.Value v = f.staticValue();
                if (v != null && !v.isNull()) {
                    char c = s.type.charAt(0);
                    if (c == 'L' || c == '[') {
                        String str = "Ljava/lang/String;".equals(s.type) ? v.asString() : null;
                        if (str != null) s.ref = canon(str); else s.opaque = true;
                    } else {
                        s.bits = narrowTo(c, v.asLong());
                    }
                }
                statics.put(s.name + ":" + s.type, s);
            }
        }

        String canon(String s) {
            String c = strings.putIfAbsent(s, s);
            return c == null ? s : c;
        }

        void runClinit(DexMethod m) {
            Frame f = new Frame(m.code().registersSize());
            exec(m, f);
        }

        Instruction[] code(DexMethod m) {
            Instruction[] byAddr = decoded.get(m);
            if (byAddr == null) {
                DexCode c = m.code();
                if (c == null) throw bail("no code: " + m);
                byAddr = InstructionDecoder.addressIndex(
                        InstructionDecoder.decode(c.insns()), c.insnsSize());
                decoded.put(m, byAddr);
            }
            return byAddr;
        }

        // ---- registers -----------------------------------------------

        static void kill(Frame f, int r) {
            if (f.t[r] == WLO) f.t[r + 1] = NONE;
            else if (f.t[r] == WHI) f.t[r - 1] = NONE;
        }

        static void setNarrow(Frame f, int r, int val) {
            kill(f, r);
            f.t[r] = NARROW;
            f.v[r] = val;
            f.o[r] = null;
        }

        static void setWide(Frame f, int r, long val) {
            kill(f, r);
            kill(f, r + 1);
            f.t[r] = WLO;
            f.t[r + 1] = WHI;
            f.v[r] = val;
            f.o[r] = null;
            f.o[r + 1] = null;
        }

        static void setRef(Frame f, int r, Object val) {
            kill(f, r);
            f.t[r] = REF;
            f.o[r] = val;
            f.v[r] = 0;
        }

        static int narrow(Frame f, int r) {
            if (f.t[r] != NARROW) throw bail("v" + r + " is not a 32-bit value");
            return (int) f.v[r];
        }

        static long wide(Frame f, int r) {
            if (f.t[r] != WLO || f.t[r + 1] != WHI) throw bail("v" + r + " is not a register pair");
            return f.v[r];
        }

        /** A reference operand; integer 0 is null, as in Dalvik. */
        static Object ref(Frame f, int r) {
            if (f.t[r] == REF) return f.o[r];
            if (f.t[r] == NARROW && f.v[r] == 0) return null;
            throw bail("v" + r + " is not a reference");
        }

        /** A reference that is about to be STORED or compared: the own-class
         *  object may only ever reach a monitor. */
        static Object storable(Frame f, int r) {
            Object o = ref(f, r);
            if (o == SELF_CLASS) throw bail("the class object escapes into data");
            return o;
        }

        static Arr array(Frame f, int r) {
            Object o = ref(f, r);
            if (o == null) throw bail("null array");
            if (!(o instanceof Arr)) throw bail("not an array");
            return (Arr) o;
        }

        static void copy(Frame f, int dst, int src) {
            byte t = f.t[src];
            if (t == NARROW) setNarrow(f, dst, (int) f.v[src]);
            else if (t == REF) setRef(f, dst, f.o[src]);
            else throw bail("move of an undefined register v" + src);
        }

        // ---- the loop ---------------------------------------------------

        /** Runs {@code m} in {@code f}; a return value lands in the result
         *  register. */
        void exec(DexMethod m, Frame f) {
            if (++depth > MAX_DEPTH) throw bail("call depth over " + MAX_DEPTH);
            Instruction[] code = code(m);
            int pc = 0;
            boolean resultValid = false;
            for (;;) {
                if (++steps > MAX_STEPS) throw bail("step budget (" + MAX_STEPS + ") exhausted");
                if (pc < 0 || pc >= code.length) throw bail("pc out of range");
                Instruction in = code[pc];
                if (in == null || in.isPayload()) throw bail("pc lands on data");
                int op = in.opcode();
                int a = in.a(), b = in.b(), c = in.c();
                int next = in.nextAddress();
                boolean setsResult = false;
                switch (op) {
                    case Opcodes.NOP:
                        break;

                    case Opcodes.MOVE: case Opcodes.MOVE_FROM16: case Opcodes.MOVE_16:
                        setNarrow(f, a, narrow(f, b));
                        break;
                    case Opcodes.MOVE_WIDE: case Opcodes.MOVE_WIDE_FROM16: case Opcodes.MOVE_WIDE_16:
                        setWide(f, a, wide(f, b));
                        break;
                    case Opcodes.MOVE_OBJECT: case Opcodes.MOVE_OBJECT_FROM16: case Opcodes.MOVE_OBJECT_16:
                        ref(f, b);   // type check only
                        copy(f, a, b);
                        break;

                    case Opcodes.MOVE_RESULT:
                        if (!resultValid || resTag != NARROW) throw bail("move-result without a result");
                        setNarrow(f, a, (int) resBits);
                        break;
                    case Opcodes.MOVE_RESULT_WIDE:
                        if (!resultValid || resTag != WLO) throw bail("move-result-wide without a result");
                        setWide(f, a, resBits);
                        break;
                    case Opcodes.MOVE_RESULT_OBJECT:
                        if (!resultValid || resTag != REF) throw bail("move-result-object without a result");
                        setRef(f, a, resRef);
                        break;

                    case Opcodes.RETURN_VOID:
                        finish(f);
                        return;
                    case Opcodes.RETURN:
                        resTag = NARROW; resBits = narrow(f, a); resRef = null;
                        finish(f);
                        return;
                    case Opcodes.RETURN_WIDE:
                        resTag = WLO; resBits = wide(f, a); resRef = null;
                        finish(f);
                        return;
                    case Opcodes.RETURN_OBJECT:
                        resTag = REF; resRef = ref(f, a); resBits = 0;
                        finish(f);
                        return;

                    case Opcodes.CONST_4: case Opcodes.CONST_16: case Opcodes.CONST:
                    case Opcodes.CONST_HIGH16:
                        setNarrow(f, a, (int) in.literal());
                        break;
                    case Opcodes.CONST_WIDE_16: case Opcodes.CONST_WIDE_32: case Opcodes.CONST_WIDE:
                    case Opcodes.CONST_WIDE_HIGH16:
                        setWide(f, a, in.literal());
                        break;
                    case Opcodes.CONST_STRING: case Opcodes.CONST_STRING_JUMBO: {
                        String s = dex.string(in.index());
                        if (s == null) throw bail("bad string index");
                        setRef(f, a, canon(s));
                        break;
                    }
                    case Opcodes.CONST_CLASS:
                        if (!self.equals(dex.typeDescriptor(in.index()))) {
                            throw bail("const-class of another type " + dex.typeDescriptor(in.index()));
                        }
                        setRef(f, a, SELF_CLASS);
                        break;

                    case Opcodes.MONITOR_ENTER:
                        if (ref(f, a) != SELF_CLASS) throw bail("monitor on something other than the own class");
                        f.monitors++;
                        break;
                    case Opcodes.MONITOR_EXIT:
                        if (ref(f, a) != SELF_CLASS || f.monitors == 0) throw bail("unbalanced monitor-exit");
                        f.monitors--;
                        break;

                    case Opcodes.CHECK_CAST: {
                        String t = dex.typeDescriptor(in.index());
                        if (t == null || t.charAt(0) != '[') throw bail("check-cast to a non-array type " + t);
                        Object o = storable(f, a);
                        if (o != null && !assignable(o, t)) throw bail("check-cast would throw");
                        break;
                    }

                    case Opcodes.ARRAY_LENGTH:
                        setNarrow(f, a, array(f, b).length());
                        break;

                    case Opcodes.NEW_ARRAY: {
                        int n = narrow(f, b);
                        String t = dex.typeDescriptor(in.index());
                        setRef(f, a, newArray(t, n));
                        break;
                    }

                    case Opcodes.FILLED_NEW_ARRAY: case Opcodes.FILLED_NEW_ARRAY_RANGE: {
                        String t = dex.typeDescriptor(in.index());
                        int[] regs = in.argRegisters();
                        if (t == null || t.length() < 2 || t.charAt(0) != '[') throw bail("filled-new-array of " + t);
                        char k = t.charAt(1);
                        if (k != 'I' && k != 'L' && k != '[') throw bail("filled-new-array of " + t);
                        Arr arr = newArray(t, regs.length);
                        for (int i = 0; i < regs.length; i++) {
                            if (k == 'I') {
                                ((int[]) arr.data)[i] = narrow(f, regs[i]);
                            } else {
                                Object o = storable(f, regs[i]);
                                if (o != null && !assignable(o, t.substring(1))) throw bail("array store would throw");
                                ((Object[]) arr.data)[i] = o;
                            }
                        }
                        resTag = REF; resRef = arr; resBits = 0;
                        setsResult = true;
                        break;
                    }

                    case Opcodes.FILL_ARRAY_DATA:
                        fillArrayData(array(f, a), in);
                        break;

                    case Opcodes.GOTO: case Opcodes.GOTO_16: case Opcodes.GOTO_32:
                        next = in.target();
                        break;

                    case Opcodes.PACKED_SWITCH: case Opcodes.SPARSE_SWITCH: {
                        int key = narrow(f, a);
                        int[] keys = in.switchKeys(), targets = in.switchTargets();
                        if (keys == null || targets == null) throw bail("switch without payload");
                        for (int i = 0; i < keys.length; i++) {
                            if (keys[i] == key) { next = targets[i]; break; }
                        }
                        break;
                    }

                    case Opcodes.CMPL_FLOAT: case Opcodes.CMPG_FLOAT: {
                        float x = Float.intBitsToFloat(narrow(f, b)), y = Float.intBitsToFloat(narrow(f, c));
                        int r = x > y ? 1 : x == y ? 0 : x < y ? -1 : (op == Opcodes.CMPG_FLOAT ? 1 : -1);
                        setNarrow(f, a, r);
                        break;
                    }
                    case Opcodes.CMPL_DOUBLE: case Opcodes.CMPG_DOUBLE: {
                        double x = Double.longBitsToDouble(wide(f, b)), y = Double.longBitsToDouble(wide(f, c));
                        int r = x > y ? 1 : x == y ? 0 : x < y ? -1 : (op == Opcodes.CMPG_DOUBLE ? 1 : -1);
                        setNarrow(f, a, r);
                        break;
                    }
                    case Opcodes.CMP_LONG:
                        setNarrow(f, a, Long.compare(wide(f, b), wide(f, c)));
                        break;

                    case Opcodes.IF_EQ: case Opcodes.IF_NE: {
                        boolean eq = same(f, a, b);
                        if (eq == (op == Opcodes.IF_EQ)) next = in.target();
                        break;
                    }
                    case Opcodes.IF_LT: case Opcodes.IF_GE: case Opcodes.IF_GT: case Opcodes.IF_LE: {
                        int x = narrow(f, a), y = narrow(f, b);
                        if (test(op - Opcodes.IF_EQ, Integer.compare(x, y))) next = in.target();
                        break;
                    }
                    case Opcodes.IF_EQZ: case Opcodes.IF_NEZ: {
                        boolean zero;
                        if (f.t[a] == NARROW) zero = f.v[a] == 0;
                        else zero = storable(f, a) == null;
                        if (zero == (op == Opcodes.IF_EQZ)) next = in.target();
                        break;
                    }
                    case Opcodes.IF_LTZ: case Opcodes.IF_GEZ: case Opcodes.IF_GTZ: case Opcodes.IF_LEZ:
                        if (test(op - Opcodes.IF_EQZ, Integer.compare(narrow(f, a), 0))) next = in.target();
                        break;

                    case Opcodes.AGET: case Opcodes.AGET_WIDE: case Opcodes.AGET_OBJECT:
                    case Opcodes.AGET_BOOLEAN: case Opcodes.AGET_BYTE: case Opcodes.AGET_CHAR:
                    case Opcodes.AGET_SHORT:
                        aget(f, op, a, array(f, b), narrow(f, c));
                        break;
                    case Opcodes.APUT: case Opcodes.APUT_WIDE: case Opcodes.APUT_OBJECT:
                    case Opcodes.APUT_BOOLEAN: case Opcodes.APUT_BYTE: case Opcodes.APUT_CHAR:
                    case Opcodes.APUT_SHORT:
                        aput(f, op, a, array(f, b), narrow(f, c));
                        break;

                    case Opcodes.SGET: case Opcodes.SGET_WIDE: case Opcodes.SGET_OBJECT:
                    case Opcodes.SGET_BOOLEAN: case Opcodes.SGET_BYTE: case Opcodes.SGET_CHAR:
                    case Opcodes.SGET_SHORT: {
                        Static s = own(in.index(), op - Opcodes.SGET);
                        if (s.opaque) throw bail("read of " + s.name + ", whose initial value is not modelled");
                        if (s.isRef()) setRef(f, a, s.ref);
                        else if (op == Opcodes.SGET_WIDE) setWide(f, a, s.bits);
                        else setNarrow(f, a, (int) s.bits);
                        break;
                    }
                    case Opcodes.SPUT: case Opcodes.SPUT_WIDE: case Opcodes.SPUT_OBJECT:
                    case Opcodes.SPUT_BOOLEAN: case Opcodes.SPUT_BYTE: case Opcodes.SPUT_CHAR:
                    case Opcodes.SPUT_SHORT: {
                        Static s = own(in.index(), op - Opcodes.SPUT);
                        if (s.isRef()) {
                            Object o = storable(f, a);
                            if (o != null && !assignable(o, s.type)) throw bail("store to " + s.name + " of an incompatible value");
                            s.ref = o;
                        } else if (op == Opcodes.SPUT_WIDE) {
                            s.bits = wide(f, a);
                        } else {
                            int v = narrow(f, a);
                            char k = s.type.charAt(0);
                            if (k == 'Z' && v != 0 && v != 1) throw bail("boolean store of " + v);
                            s.bits = narrowTo(k, v);
                        }
                        s.opaque = false;
                        s.written = true;
                        break;
                    }

                    case Opcodes.INVOKE_STATIC: case Opcodes.INVOKE_STATIC_RANGE:
                        setsResult = invokeStatic(f, in);
                        break;

                    case Opcodes.NEG_INT: setNarrow(f, a, -narrow(f, b)); break;
                    case Opcodes.NOT_INT: setNarrow(f, a, ~narrow(f, b)); break;
                    case Opcodes.NEG_LONG: setWide(f, a, -wide(f, b)); break;
                    case Opcodes.NOT_LONG: setWide(f, a, ~wide(f, b)); break;
                    case Opcodes.NEG_FLOAT: setNarrow(f, a, fbits(-fval(f, b))); break;
                    case Opcodes.NEG_DOUBLE: setWide(f, a, dbits(-dval(f, b))); break;
                    case Opcodes.INT_TO_LONG: setWide(f, a, narrow(f, b)); break;
                    case Opcodes.INT_TO_FLOAT: setNarrow(f, a, fbits((float) narrow(f, b))); break;
                    case Opcodes.INT_TO_DOUBLE: setWide(f, a, dbits((double) narrow(f, b))); break;
                    case Opcodes.LONG_TO_INT: setNarrow(f, a, (int) wide(f, b)); break;
                    case Opcodes.LONG_TO_FLOAT: setNarrow(f, a, fbits((float) wide(f, b))); break;
                    case Opcodes.LONG_TO_DOUBLE: setWide(f, a, dbits((double) wide(f, b))); break;
                    case Opcodes.FLOAT_TO_INT: setNarrow(f, a, (int) fval(f, b)); break;
                    case Opcodes.FLOAT_TO_LONG: setWide(f, a, (long) fval(f, b)); break;
                    case Opcodes.FLOAT_TO_DOUBLE: setWide(f, a, dbits((double) fval(f, b))); break;
                    case Opcodes.DOUBLE_TO_INT: setNarrow(f, a, (int) dval(f, b)); break;
                    case Opcodes.DOUBLE_TO_LONG: setWide(f, a, (long) dval(f, b)); break;
                    case Opcodes.DOUBLE_TO_FLOAT: setNarrow(f, a, fbits((float) dval(f, b))); break;
                    case Opcodes.INT_TO_BYTE: setNarrow(f, a, (byte) narrow(f, b)); break;
                    case Opcodes.INT_TO_CHAR: setNarrow(f, a, (char) narrow(f, b)); break;
                    case Opcodes.INT_TO_SHORT: setNarrow(f, a, (short) narrow(f, b)); break;

                    default:
                        if (op >= Opcodes.ADD_INT && op <= Opcodes.USHR_INT) {
                            setNarrow(f, a, intOp(op - Opcodes.ADD_INT, narrow(f, b), narrow(f, c)));
                        } else if (op >= Opcodes.ADD_LONG && op <= Opcodes.USHR_LONG) {
                            int k = op - Opcodes.ADD_LONG;
                            long y = k >= 8 ? narrow(f, c) : wide(f, c);   // shifts take an int amount
                            setWide(f, a, longOp(k, wide(f, b), y));
                        } else if (op >= Opcodes.ADD_FLOAT && op <= Opcodes.REM_FLOAT) {
                            setNarrow(f, a, fbits(floatOp(op - Opcodes.ADD_FLOAT, fval(f, b), fval(f, c))));
                        } else if (op >= Opcodes.ADD_DOUBLE && op <= Opcodes.REM_DOUBLE) {
                            setWide(f, a, dbits(doubleOp(op - Opcodes.ADD_DOUBLE, dval(f, b), dval(f, c))));
                        } else if (op >= Opcodes.ADD_INT_2ADDR && op <= Opcodes.USHR_INT_2ADDR) {
                            setNarrow(f, a, intOp(op - Opcodes.ADD_INT_2ADDR, narrow(f, a), narrow(f, b)));
                        } else if (op >= Opcodes.ADD_LONG_2ADDR && op <= Opcodes.USHR_LONG_2ADDR) {
                            int k = op - Opcodes.ADD_LONG_2ADDR;
                            long y = k >= 8 ? narrow(f, b) : wide(f, b);
                            setWide(f, a, longOp(k, wide(f, a), y));
                        } else if (op >= Opcodes.ADD_FLOAT_2ADDR && op <= Opcodes.REM_FLOAT_2ADDR) {
                            setNarrow(f, a, fbits(floatOp(op - Opcodes.ADD_FLOAT_2ADDR, fval(f, a), fval(f, b))));
                        } else if (op >= Opcodes.ADD_DOUBLE_2ADDR && op <= Opcodes.REM_DOUBLE_2ADDR) {
                            setWide(f, a, dbits(doubleOp(op - Opcodes.ADD_DOUBLE_2ADDR, dval(f, a), dval(f, b))));
                        } else if (op >= Opcodes.ADD_INT_LIT16 && op <= Opcodes.XOR_INT_LIT16) {
                            setNarrow(f, a, litOp(op - Opcodes.ADD_INT_LIT16, narrow(f, b), (int) in.literal()));
                        } else if (op >= Opcodes.ADD_INT_LIT8 && op <= Opcodes.USHR_INT_LIT8) {
                            setNarrow(f, a, litOp(op - Opcodes.ADD_INT_LIT8, narrow(f, b), (int) in.literal()));
                        } else {
                            throw bail(Opcodes.nameOf(op) + " is not whitelisted");
                        }
                        break;
                }
                resultValid = setsResult;
                pc = next;
            }
        }

        private void finish(Frame f) {
            if (f.monitors != 0) throw bail("returns holding a monitor");
            depth--;
        }

        /** if-test conditions, indexed from if-eq / if-eqz. */
        private static boolean test(int k, int cmp) {
            switch (k) {
                case 0: return cmp == 0;
                case 1: return cmp != 0;
                case 2: return cmp < 0;
                case 3: return cmp >= 0;
                case 4: return cmp > 0;
                default: return cmp <= 0;
            }
        }

        /** if-eq / if-ne: integers by value, references by identity. */
        private static boolean same(Frame f, int x, int y) {
            if (f.t[x] == NARROW && f.t[y] == NARROW) return f.v[x] == f.v[y];
            return storable(f, x) == storable(f, y);
        }

        // ---- arithmetic (Dalvik and Java agree on every one of these) ---

        private static int intOp(int k, int x, int y) {
            switch (k) {
                case 0: return x + y;
                case 1: return x - y;
                case 2: return x * y;
                case 3: if (y == 0) throw bail("division by zero"); return x / y;
                case 4: if (y == 0) throw bail("division by zero"); return x % y;
                case 5: return x & y;
                case 6: return x | y;
                case 7: return x ^ y;
                case 8: return x << y;
                case 9: return x >> y;
                default: return x >>> y;
            }
        }

        private static long longOp(int k, long x, long y) {
            switch (k) {
                case 0: return x + y;
                case 1: return x - y;
                case 2: return x * y;
                case 3: if (y == 0) throw bail("division by zero"); return x / y;
                case 4: if (y == 0) throw bail("division by zero"); return x % y;
                case 5: return x & y;
                case 6: return x | y;
                case 7: return x ^ y;
                case 8: return x << (int) y;
                case 9: return x >> (int) y;
                default: return x >>> (int) y;
            }
        }

        /** The lit16 / lit8 families: add, rsub, mul, div, rem, and, or, xor,
         *  then (lit8 only) shl, shr, ushr. */
        private static int litOp(int k, int x, int lit) {
            switch (k) {
                case 0: return x + lit;
                case 1: return lit - x;
                case 2: return x * lit;
                case 3: if (lit == 0) throw bail("division by zero"); return x / lit;
                case 4: if (lit == 0) throw bail("division by zero"); return x % lit;
                case 5: return x & lit;
                case 6: return x | lit;
                case 7: return x ^ lit;
                case 8: return x << lit;
                case 9: return x >> lit;
                default: return x >>> lit;
            }
        }

        private static float floatOp(int k, float x, float y) {
            switch (k) {
                case 0: return x + y;
                case 1: return x - y;
                case 2: return x * y;
                case 3: return x / y;
                default: return x % y;
            }
        }

        private static double doubleOp(int k, double x, double y) {
            switch (k) {
                case 0: return x + y;
                case 1: return x - y;
                case 2: return x * y;
                case 3: return x / y;
                default: return x % y;
            }
        }

        private static float fval(Frame f, int r) { return Float.intBitsToFloat(narrow(f, r)); }
        private static double dval(Frame f, int r) { return Double.longBitsToDouble(wide(f, r)); }
        private static int fbits(float x) { return Float.floatToRawIntBits(x); }
        private static long dbits(double x) { return Double.doubleToRawLongBits(x); }

        // ---- arrays -----------------------------------------------------

        Arr newArray(String t, int n) {
            if (t == null || t.length() < 2 || t.charAt(0) != '[') throw bail("new-array of " + t);
            if (n < 0) throw bail("negative array size");
            elements += n;
            if (elements > MAX_ELEMENTS) throw bail("allocates more than " + MAX_ELEMENTS + " elements");
            Object data;
            switch (t.charAt(1)) {
                case 'Z': case 'B': data = new byte[n]; break;
                case 'C': data = new char[n]; break;
                case 'S': data = new short[n]; break;
                case 'I': case 'F': data = new int[n]; break;
                case 'J': case 'D': data = new long[n]; break;
                case 'L': case '[': data = new Object[n]; break;
                default: throw bail("new-array of " + t);
            }
            return new Arr(nextId++, t, data);
        }

        static void fillArrayData(Arr arr, Instruction in) {
            byte[] raw = in.arrayData();
            int width = in.arrayElementWidth(), count = in.arrayElementCount();
            if (raw == null) throw bail("fill-array-data without payload");
            int want;
            switch (arr.kind()) {
                case 'Z': case 'B': want = 1; break;
                case 'C': case 'S': want = 2; break;
                case 'I': case 'F': want = 4; break;
                case 'J': case 'D': want = 8; break;
                default: throw bail("fill-array-data into " + arr.desc);
            }
            if (width != want) throw bail("fill-array-data width " + width + " for " + arr.desc);
            if (count > arr.length()) throw bail("fill-array-data overruns the array");
            for (int i = 0; i < count; i++) {
                long v = 0;
                for (int k = width - 1; k >= 0; k--) v = (v << 8) | (raw[i * width + k] & 0xff);
                switch (arr.kind()) {
                    case 'Z':
                        if (v > 1) throw bail("boolean array data " + v);
                        ((byte[]) arr.data)[i] = (byte) v;
                        break;
                    case 'B': ((byte[]) arr.data)[i] = (byte) v; break;
                    case 'C': ((char[]) arr.data)[i] = (char) v; break;
                    case 'S': ((short[]) arr.data)[i] = (short) v; break;
                    case 'I': case 'F': ((int[]) arr.data)[i] = (int) v; break;
                    default: ((long[]) arr.data)[i] = v; break;
                }
            }
        }

        static void checkIndex(Arr arr, int i) {
            if (i < 0 || i >= arr.length()) throw bail("array index out of bounds");
        }

        /** aget family, offset from aget: the opcode must match the array. */
        static void aget(Frame f, int op, int dst, Arr arr, int i) {
            checkIndex(arr, i);
            char k = arr.kind();
            switch (op) {
                case Opcodes.AGET:
                    if (k != 'I' && k != 'F') throw bail("aget on " + arr.desc);
                    setNarrow(f, dst, ((int[]) arr.data)[i]);
                    break;
                case Opcodes.AGET_WIDE:
                    if (k != 'J' && k != 'D') throw bail("aget-wide on " + arr.desc);
                    setWide(f, dst, ((long[]) arr.data)[i]);
                    break;
                case Opcodes.AGET_OBJECT:
                    if (!arr.isRef()) throw bail("aget-object on " + arr.desc);
                    setRef(f, dst, ((Object[]) arr.data)[i]);
                    break;
                case Opcodes.AGET_BOOLEAN:
                    if (k != 'Z') throw bail("aget-boolean on " + arr.desc);
                    setNarrow(f, dst, ((byte[]) arr.data)[i] & 0xff);
                    break;
                case Opcodes.AGET_BYTE:
                    if (k != 'B') throw bail("aget-byte on " + arr.desc);
                    setNarrow(f, dst, ((byte[]) arr.data)[i]);
                    break;
                case Opcodes.AGET_CHAR:
                    if (k != 'C') throw bail("aget-char on " + arr.desc);
                    setNarrow(f, dst, ((char[]) arr.data)[i]);
                    break;
                default:
                    if (k != 'S') throw bail("aget-short on " + arr.desc);
                    setNarrow(f, dst, ((short[]) arr.data)[i]);
                    break;
            }
        }

        static void aput(Frame f, int op, int src, Arr arr, int i) {
            checkIndex(arr, i);
            char k = arr.kind();
            switch (op) {
                case Opcodes.APUT:
                    if (k != 'I' && k != 'F') throw bail("aput on " + arr.desc);
                    ((int[]) arr.data)[i] = narrow(f, src);
                    break;
                case Opcodes.APUT_WIDE:
                    if (k != 'J' && k != 'D') throw bail("aput-wide on " + arr.desc);
                    ((long[]) arr.data)[i] = wide(f, src);
                    break;
                case Opcodes.APUT_OBJECT: {
                    if (!arr.isRef()) throw bail("aput-object on " + arr.desc);
                    Object o = storable(f, src);
                    if (o != null && !assignable(o, arr.desc.substring(1))) throw bail("array store would throw");
                    ((Object[]) arr.data)[i] = o;
                    break;
                }
                case Opcodes.APUT_BOOLEAN: {
                    if (k != 'Z') throw bail("aput-boolean on " + arr.desc);
                    int v = narrow(f, src);
                    if (v != 0 && v != 1) throw bail("boolean store of " + v);
                    ((byte[]) arr.data)[i] = (byte) v;
                    break;
                }
                case Opcodes.APUT_BYTE:
                    if (k != 'B') throw bail("aput-byte on " + arr.desc);
                    ((byte[]) arr.data)[i] = (byte) narrow(f, src);
                    break;
                case Opcodes.APUT_CHAR:
                    if (k != 'C') throw bail("aput-char on " + arr.desc);
                    ((char[]) arr.data)[i] = (char) narrow(f, src);
                    break;
                default:
                    if (k != 'S') throw bail("aput-short on " + arr.desc);
                    ((short[]) arr.data)[i] = (short) narrow(f, src);
                    break;
            }
        }

        // ---- fields and calls -------------------------------------------

        /** The own static field an sget/sput names. {@code kind} is the
         *  opcode's offset in its family: 0 32-bit, 1 wide, 2 object,
         *  3 boolean, 4 byte, 5 char, 6 short. */
        Static own(int fieldIndex, int kind) {
            DexFile.FieldRef r = dex.fieldRef(fieldIndex);
            if (r == null || !self.equals(r.declaringClass())) {
                throw bail("field of another class " + r);
            }
            Static s = statics.get(r.name() + ":" + r.type());
            if (s == null) throw bail("static field not declared by this class: " + r);
            char t = s.type.charAt(0);
            boolean ok;
            switch (kind) {
                case 0: ok = t == 'I' || t == 'F'; break;
                case 1: ok = t == 'J' || t == 'D'; break;
                case 2: ok = t == 'L' || t == '['; break;
                case 3: ok = t == 'Z'; break;
                case 4: ok = t == 'B'; break;
                case 5: ok = t == 'C'; break;
                default: ok = t == 'S'; break;
            }
            if (!ok) throw bail("field access kind does not match " + r);
            return s;
        }

        /** invoke-static: System.arraycopy, or a static method of this class
         *  (interpreted). Returns whether it left a result. */
        boolean invokeStatic(Frame f, Instruction in) {
            DexFile.MethodRef r = dex.methodRef(in.index());
            if (r == null) throw bail("bad method index");
            int[] args = in.argRegisters();
            if ("Ljava/lang/System;".equals(r.declaringClass()) && "arraycopy".equals(r.name())
                    && "(Ljava/lang/Object;ILjava/lang/Object;II)V".equals(r.descriptor())) {
                arraycopy(storable(f, args[0]), narrow(f, args[1]), storable(f, args[2]),
                          narrow(f, args[3]), narrow(f, args[4]));
                return false;
            }
            if (!self.equals(r.declaringClass())) throw bail("call to " + r);
            DexMethod callee = null;
            for (DexMethod m : cls.methods()) {
                if (m.isStatic() && r.name().equals(m.name()) && r.descriptor().equals(m.descriptor())) {
                    callee = m;
                    break;
                }
            }
            if (callee == null || callee.isNative() || callee.isAbstract() || callee.codeOffset() == 0
                    || "<clinit>".equals(callee.name())) {
                throw bail("call to " + r);
            }
            DexCode code = callee.code();
            int regs = code.registersSize(), ins = code.insSize();
            if (args.length != ins || ins > regs) throw bail("argument shape of " + r);
            Frame g = new Frame(regs);
            for (int i = 0; i < args.length; i++) {
                int to = regs - ins + i;
                g.t[to] = f.t[args[i]];
                g.v[to] = f.v[args[i]];
                g.o[to] = f.o[args[i]];
            }
            resTag = NONE;
            exec(callee, g);
            char ret = r.proto().returnType().charAt(0);
            if (ret == 'V') return false;
            byte want = (ret == 'J' || ret == 'D') ? WLO : (ret == 'L' || ret == '[') ? REF : NARROW;
            if (resTag == NARROW && want == REF && resBits == 0) {   // `return 0` as null
                resTag = REF;
                resRef = null;
            }
            if (resTag != want) throw bail("return value shape of " + r);
            return true;
        }

        static void arraycopy(Object src, int sp, Object dst, int dp, int n) {
            if (!(src instanceof Arr) || !(dst instanceof Arr)) throw bail("arraycopy of a non-array");
            Arr s = (Arr) src, d = (Arr) dst;
            if (sp < 0 || dp < 0 || n < 0 || sp > s.length() - n || dp > d.length() - n) {
                throw bail("arraycopy out of bounds");
            }
            if (!s.desc.equals(d.desc)) {
                if (!s.isRef() || !d.isRef()) throw bail("arraycopy between " + s.desc + " and " + d.desc);
                String comp = d.desc.substring(1);
                Object[] from = (Object[]) s.data;
                for (int i = 0; i < n; i++) {
                    Object o = from[sp + i];
                    if (o != null && !assignable(o, comp)) throw bail("arraycopy store would throw");
                }
            }
            System.arraycopy(s.data, sp, d.data, dp, n);
        }
    }

    /** Truncates a stored 32-bit value the way a field or array of that type
     *  holds it. */
    private static long narrowTo(char type, long v) {
        switch (type) {
            case 'Z': return v & 1;
            case 'B': return (byte) v;
            case 'C': return (char) v;
            case 'S': return (short) v;
            case 'I': case 'F': return (int) v;
            default: return v;
        }
    }

    /** Conservative assignability of a modelled value to a declared type: a
     *  false "no" only costs the rescue, a false "yes" would be a wrong value. */
    private static boolean assignable(Object value, String target) {
        if (value instanceof String) {
            return "Ljava/lang/String;".equals(target) || "Ljava/lang/Object;".equals(target)
                    || "Ljava/lang/CharSequence;".equals(target)
                    || "Ljava/lang/Comparable;".equals(target)
                    || "Ljava/io/Serializable;".equals(target);
        }
        if (value instanceof Arr) return assignableDesc(((Arr) value).desc, target);
        return false;
    }

    private static boolean assignableDesc(String src, String dst) {
        if (src.equals(dst) || "Ljava/lang/Object;".equals(dst)) return true;
        if (src.charAt(0) != '[') return false;
        if ("Ljava/lang/Cloneable;".equals(dst) || "Ljava/io/Serializable;".equals(dst)) return true;
        if (dst.charAt(0) != '[') return false;
        String sc = src.substring(1), dc = dst.substring(1);
        char s0 = sc.charAt(0), d0 = dc.charAt(0);
        boolean sRef = s0 == 'L' || s0 == '[', dRef = d0 == 'L' || d0 == '[';
        return sRef && dRef && assignableDesc(sc, dc);
    }

    // ==================================================================
    // Emitter
    // ==================================================================

    /** A slice of one primitive array's data in one String constant. */
    private static final class Piece {
        final int array, start, chunk, charOffset, count;

        Piece(int array, int start, int chunk, int charOffset, int count) {
            this.array = array;
            this.start = start;
            this.chunk = chunk;
            this.charOffset = charOffset;
            this.count = count;
        }
    }

    private static final class Emitter {
        final Interp in;
        final DexClass cls;
        final ClassFileWriter cw;
        final ClassFileWriter.MethodWriter mw;
        final ConstantPool pool;
        final String owner;
        final boolean itf;

        final List<Static> fields = new ArrayList<>();
        final List<Arr> table = new ArrayList<>();
        final IdentityHashMap<Arr, Integer> index = new IdentityHashMap<>();
        final List<String> chunks = new ArrayList<>();
        final List<Piece> pieces = new ArrayList<>();
        final Map<Character, String> decoders = new LinkedHashMap<>();
        int dataBytes;

        Emitter(Interp in, DexClass cls, ClassFileWriter cw, ClassFileWriter.MethodWriter mw) {
            this.in = in;
            this.cls = cls;
            this.cw = cw;
            this.mw = mw;
            this.pool = cw.pool();
            this.owner = cls.name();
            this.itf = cls.isInterface();
        }

        String emit() {
            collect();
            encode();
            checkPool();
            // From here on the pool takes entries; if it runs out (here or in
            // a later method of the class) DexConverter redoes the class
            // without this rescue.
            FIRED.set(true);

            java.util.Set<String> taken = new java.util.HashSet<>();
            for (DexMethod m : cls.methods()) taken.add(m.name());
            Map<Character, String> decoderNames = new LinkedHashMap<>();
            List<String[]> extra = new ArrayList<>();         // name, desc
            List<CodeWriter> extraCode = new ArrayList<>();
            for (Map.Entry<Character, String> d : decoders.entrySet()) {
                String name = unique(taken, "$unpack$" + d.getKey());
                decoderNames.put(d.getKey(), name);
                String desc = decoderDesc(d.getValue());
                extra.add(new String[] { name, desc });
                extraCode.add(decoder(name, desc, d.getValue()));
            }
            List<String> helpers = new ArrayList<>();
            for (CodeWriter h : fillers(decoderNames)) {
                String name = unique(taken, "$clinit$" + helpers.size());
                helpers.add(name);
                extra.add(new String[] { name, HELPER_DESC });
                extraCode.add(h);
            }
            CodeWriter main = clinit(helpers);

            // Measure everything BEFORE attaching anything, so a refusal
            // leaves the class exactly as the stub path expects it.
            if (main.measuredLength() > 65535) throw bail("the field assignments alone exceed 64 KB");
            for (CodeWriter c : extraCode) {
                if (c.measuredLength() > 65535) throw bail("a generated helper exceeds 64 KB");
            }
            mw.setCode(main);
            for (int i = 0; i < extra.size(); i++) {
                cw.addMethod(ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_STATIC
                                | ClassFileWriter.ACC_SYNTHETIC, extra.get(i)[0], extra.get(i)[1])
                  .setCode(extraCode.get(i));
            }
            SUMMARY.set(fields.size() + " fields, " + table.size() + " arrays, "
                        + dataBytes + " bytes data");
            return null;
        }

        String unique(Set<String> taken, String tail) {
            String base = Options.syntheticPrefix + tail;
            String name = base;
            for (int k = 1; taken.contains(name); k++) name = base + "_" + k;
            taken.add(name);
            return name;
        }

        /** Written fields in declaration order, and every array they reach,
         *  in allocation order. */
        void collect() {
            List<Arr> work = new ArrayList<>();
            for (Static s : in.statics.values()) {
                if (!s.written) continue;
                fields.add(s);
                if (s.ref instanceof String) checkString((String) s.ref);
                if (s.ref instanceof Arr) work.add((Arr) s.ref);
            }
            IdentityHashMap<Arr, Boolean> seen = new IdentityHashMap<>();
            List<Arr> reached = new ArrayList<>();
            while (!work.isEmpty()) {
                Arr a = work.remove(work.size() - 1);
                if (seen.put(a, Boolean.TRUE) != null) continue;
                reached.add(a);
                if (!a.isRef()) continue;
                Object[] els = (Object[]) a.data;
                if (els.length > MAX_REF_LENGTH) throw bail("a reference array of " + els.length + " elements");
                for (Object o : els) {
                    if (o instanceof Arr) work.add((Arr) o);
                    else if (o instanceof String) checkString((String) o);
                }
            }
            if (reached.size() > MAX_ARRAYS) throw bail(reached.size() + " arrays");
            reached.sort((x, y) -> Integer.compare(x.id, y.id));
            for (Arr a : reached) {
                index.put(a, table.size());
                table.add(a);
            }
        }

        static void checkString(String s) {
            if (Mutf8.encodedLength(s) > 65535) throw bail("a string constant over 65535 bytes");
        }

        // ---- data encoding ----------------------------------------------
        //
        // Each element becomes 1, 2 or 4 sixteen-bit units (bytes, booleans,
        // chars and shorts one; int and float bits two; long and double bits
        // four, high unit first), and each unit u is stored as the char
        // (u + 1) & 0xFFFF, so the very common zero unit costs one byte of
        // modified UTF-8 instead of two. The decoder undoes it with i2c(c - 1).

        static int units(char kind) {
            switch (kind) {
                case 'Z': case 'B': case 'C': case 'S': return 1;
                case 'I': case 'F': return 2;
                default: return 4;
            }
        }

        static long raw(Arr a, int i) {
            switch (a.kind()) {
                case 'Z': case 'B': return ((byte[]) a.data)[i] & 0xff;
                case 'C': return ((char[]) a.data)[i];
                case 'S': return ((short[]) a.data)[i] & 0xffff;
                case 'I': case 'F': return ((int[]) a.data)[i];
                default: return ((long[]) a.data)[i];
            }
        }

        static int utfBytes(char c) {
            return (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF) ? 2 : 3;
        }

        void encode() {
            StringBuilder cur = new StringBuilder();
            int curBytes = 0;
            char[] u = new char[4];
            for (int k = 0; k < table.size(); k++) {
                Arr a = table.get(k);
                if (a.isRef()) continue;
                int n = a.length(), lo = 0, hi = n;
                while (lo < hi && raw(a, lo) == 0) lo++;
                while (hi > lo && raw(a, hi - 1) == 0) hi--;
                if (lo == hi) continue;                  // a new array is already all zero
                decoders.putIfAbsent(a.kind(), a.desc);
                int w = units(a.kind());
                int pieceStart = lo, pieceOff = cur.length();
                for (int i = lo; i < hi; i++) {
                    long v = raw(a, i);
                    int bytes = 0;
                    for (int j = 0; j < w; j++) {
                        int unit = (int) (v >>> (16 * (w - 1 - j))) & 0xffff;
                        u[j] = (char) ((unit + 1) & 0xffff);
                        bytes += utfBytes(u[j]);
                    }
                    if (curBytes + bytes > CHUNK_BYTES) {
                        if (i > pieceStart) {
                            pieces.add(new Piece(k, pieceStart, chunks.size(), pieceOff, i - pieceStart));
                        }
                        chunks.add(cur.toString());
                        cur.setLength(0);
                        curBytes = 0;
                        pieceStart = i;
                        pieceOff = 0;
                    }
                    cur.append(u, 0, w);
                    curBytes += bytes;
                    dataBytes += bytes;
                    if (dataBytes > MAX_DATA_BYTES) throw bail("more than " + MAX_DATA_BYTES + " bytes of array data");
                }
                pieces.add(new Piece(k, pieceStart, chunks.size(), pieceOff, hi - pieceStart));
            }
            if (cur.length() > 0) chunks.add(cur.toString());
        }

        /** An upper bound on the pool entries the emission adds, checked
         *  against what is left. */
        void checkPool() {
            int need = 16;                              // attribute names, slack
            need += 2 * chunks.size();                  // String + Utf8
            need += 3 * pieces.size() + 2 * table.size() + 4 * fields.size();
            need += 2 * table.size();                   // array classes (dedup in practice)
            need += 4 * (decoders.size() + 1 + table.size() * 11 / METHOD_BUDGET + 1);
            need += 6 * 3;                              // charAt, intBitsToFloat, longBitsToDouble
            for (Static s : fields) if (s.ref instanceof String) need += 2;
            for (Arr a : table) {
                if (!a.isRef()) continue;
                for (Object o : (Object[]) a.data) if (o instanceof String) need += 2;
            }
            if (need > pool.space()) throw bail("constant pool has room for " + pool.space() + " entries, needs up to " + need);
        }

        // ---- code ---------------------------------------------------------

        static final String HELPER_DESC = "([Ljava/lang/Object;)V";

        static String decoderDesc(String arrayDesc) {
            return "(" + arrayDesc + "ILjava/lang/String;II)V";
        }

        CodeWriter newCode(String name, String desc) {
            CodeWriter c = new CodeWriter(pool);
            c.setInitialFrame(StackMapWriter.initialFrame(owner, name, desc, true));
            c.setMaxLocals(CodeWriter.argSlots(desc));
            return c;
        }

        /**
         * decode(array, start, s, soff, count): for i in [0, count),
         * array[start + i] = element i of s starting at char soff.
         * Locals: 0 array, 1 start, 2 s, 3 soff, 4 count, 5 i, 6 char position.
         */
        CodeWriter decoder(String name, String desc, String arrayDesc) {
            char kind = arrayDesc.charAt(1);
            int w = units(kind);
            CodeWriter c = newCode(name, desc);
            StackMapWriter.VType[] locals = {
                StackMapWriter.VType.object(arrayDesc), StackMapWriter.VType.INTEGER,
                StackMapWriter.VType.object("java/lang/String"), StackMapWriter.VType.INTEGER,
                StackMapWriter.VType.INTEGER, StackMapWriter.VType.INTEGER,
            };
            StackMapWriter.Frame frame = new StackMapWriter.Frame(locals, new StackMapWriter.VType[0]);
            CodeWriter.Label top = c.newLabel(), end = c.newLabel();
            c.op(CodeWriter.ICONST_0);
            c.varOp(CodeWriter.ISTORE, 5);
            c.mark(top);
            c.putFrame(top, frame);
            c.varOp(CodeWriter.ILOAD, 5);
            c.varOp(CodeWriter.ILOAD, 4);
            c.jump(CodeWriter.IF_ICMPGE, end);
            c.varOp(CodeWriter.ILOAD, 3);
            c.varOp(CodeWriter.ILOAD, 5);
            if (w > 1) {
                c.pushInt(w == 2 ? 1 : 2);
                c.op(CodeWriter.ISHL);
            }
            c.op(CodeWriter.IADD);
            c.varOp(CodeWriter.ISTORE, 6);
            c.varOp(CodeWriter.ALOAD, 0);
            c.varOp(CodeWriter.ILOAD, 1);
            c.varOp(CodeWriter.ILOAD, 5);
            c.op(CodeWriter.IADD);
            int store;
            switch (kind) {
                case 'Z': case 'B':
                    unit(c, 0);
                    store = CodeWriter.BASTORE;
                    break;
                case 'C':
                    unit(c, 0);
                    store = CodeWriter.CASTORE;
                    break;
                case 'S':
                    unit(c, 0);
                    store = CodeWriter.SASTORE;
                    break;
                case 'I': case 'F':
                    unit(c, 0);
                    c.pushInt(16);
                    c.op(CodeWriter.ISHL);
                    unit(c, 1);
                    c.op(CodeWriter.IOR);
                    if (kind == 'F') {
                        c.methodOp(CodeWriter.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false);
                        store = CodeWriter.FASTORE;
                    } else {
                        store = CodeWriter.IASTORE;
                    }
                    break;
                default:
                    for (int j = 0; j < 4; j++) {
                        unit(c, j);
                        c.op(CodeWriter.I2L);
                        if (j < 3) {
                            c.pushInt(48 - 16 * j);
                            c.op(CodeWriter.LSHL);
                        }
                        if (j > 0) c.op(CodeWriter.LOR);
                    }
                    if (kind == 'D') {
                        c.methodOp(CodeWriter.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
                        store = CodeWriter.DASTORE;
                    } else {
                        store = CodeWriter.LASTORE;
                    }
                    break;
            }
            c.op(store);
            c.iinc(5, 1);
            c.jump(CodeWriter.GOTO, top);
            c.mark(end);
            c.putFrame(end, frame);
            c.op(CodeWriter.RETURN);
            return c;
        }

        /** Pushes unit j of the current element: i2c(s.charAt(p + j) - 1). */
        static void unit(CodeWriter c, int j) {
            c.varOp(CodeWriter.ALOAD, 2);
            c.varOp(CodeWriter.ILOAD, 6);
            if (j > 0) {
                c.pushInt(j);
                c.op(CodeWriter.IADD);
            }
            c.methodOp(CodeWriter.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            c.op(CodeWriter.ICONST_1);
            c.op(CodeWriter.ISUB);
            c.op(CodeWriter.I2C);
        }

        /** Upper bound of pushInt's encoding. */
        static int intCost(int v) {
            return (v >= -1 && v <= 5) ? 1 : (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) ? 2 : 3;
        }

        /**
         * The helpers that build the arrays: allocate every one, decode the
         * primitive data, then link the reference arrays. Straight-line code,
         * so no frames; each helper takes the array table as local 0 and keeps
         * the reference array being filled in local 1.
         */
        List<CodeWriter> fillers(Map<Character, String> decoderNames) {
            List<CodeWriter> out = new ArrayList<>();
            if (table.isEmpty()) return out;
            CodeWriter[] cur = { null };
            int[] used = { 0 };
            int[] loaded = { -1 };
            java.util.function.IntConsumer room = cost -> {
                if (cur[0] == null || used[0] + cost + 1 > METHOD_BUDGET) {
                    if (cur[0] != null) cur[0].op(CodeWriter.RETURN);
                    cur[0] = newCode("helper", HELPER_DESC);
                    out.add(cur[0]);
                    used[0] = 0;
                    loaded[0] = -1;
                }
                used[0] += cost;
            };
            for (int k = 0; k < table.size(); k++) {
                Arr a = table.get(k);
                room.accept(1 + intCost(k) + intCost(a.length()) + 3 + 1);
                CodeWriter c = cur[0];
                c.varOp(CodeWriter.ALOAD, 0);
                c.pushInt(k);
                c.pushInt(a.length());
                newArray(c, a.desc);
                c.op(CodeWriter.AASTORE);
            }
            for (Piece p : pieces) {
                Arr a = table.get(p.array);
                room.accept(1 + intCost(p.array) + 1 + 3 + intCost(p.start) + 3
                            + intCost(p.charOffset) + intCost(p.count) + 3);
                CodeWriter c = cur[0];
                c.varOp(CodeWriter.ALOAD, 0);
                c.pushInt(p.array);
                c.op(CodeWriter.AALOAD);
                c.typeOp(CodeWriter.CHECKCAST, a.desc);
                c.pushInt(p.start);
                c.ldcString(chunks.get(p.chunk));
                c.pushInt(p.charOffset);
                c.pushInt(p.count);
                c.methodOp(CodeWriter.INVOKESTATIC, owner, decoderNames.get(a.kind()),
                           decoderDesc(a.desc), itf);
            }
            for (int k = 0; k < table.size(); k++) {
                Arr a = table.get(k);
                if (!a.isRef()) continue;
                Object[] els = (Object[]) a.data;
                for (int e = 0; e < els.length; e++) {
                    Object o = els[e];
                    if (o == null) continue;
                    int valueCost = o instanceof String ? 3 : 1 + intCost(index.get((Arr) o)) + 1;
                    int header = 1 + intCost(k) + 1 + 3 + 1;
                    int cost = 1 + intCost(e) + valueCost + 1;
                    room.accept(loaded[0] == k ? cost : cost + header);
                    CodeWriter c = cur[0];
                    if (loaded[0] != k) {
                        c.varOp(CodeWriter.ALOAD, 0);
                        c.pushInt(k);
                        c.op(CodeWriter.AALOAD);
                        c.typeOp(CodeWriter.CHECKCAST, "[Ljava/lang/Object;");
                        c.varOp(CodeWriter.ASTORE, 1);
                        loaded[0] = k;
                    }
                    c.varOp(CodeWriter.ALOAD, 1);
                    c.pushInt(e);
                    if (o instanceof String) {
                        c.ldcString((String) o);
                    } else {
                        c.varOp(CodeWriter.ALOAD, 0);
                        c.pushInt(index.get((Arr) o));
                        c.op(CodeWriter.AALOAD);
                    }
                    c.op(CodeWriter.AASTORE);
                }
            }
            cur[0].op(CodeWriter.RETURN);
            return out;
        }

        static void newArray(CodeWriter c, String desc) {
            switch (desc.charAt(1)) {
                case 'Z': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_BOOLEAN); break;
                case 'B': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_BYTE); break;
                case 'C': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_CHAR); break;
                case 'S': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_SHORT); break;
                case 'I': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_INT); break;
                case 'F': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_FLOAT); break;
                case 'J': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_LONG); break;
                case 'D': c.intOp(CodeWriter.NEWARRAY, CodeWriter.T_DOUBLE); break;
                default: c.typeOp(CodeWriter.ANEWARRAY, DexFile.internalName(desc.substring(1))); break;
            }
        }

        /** The new &lt;clinit&gt;: the array table, the helpers, then every
         *  written field, in declaration order. */
        CodeWriter clinit(List<String> helpers) {
            CodeWriter c = newCode("<clinit>", "()V");
            if (!table.isEmpty()) {
                c.pushInt(table.size());
                c.typeOp(CodeWriter.ANEWARRAY, "java/lang/Object");
                c.varOp(CodeWriter.ASTORE, 0);
                for (String h : helpers) {
                    c.varOp(CodeWriter.ALOAD, 0);
                    c.methodOp(CodeWriter.INVOKESTATIC, owner, h, HELPER_DESC, itf);
                }
            }
            for (Static s : fields) {
                switch (s.type.charAt(0)) {
                    case 'Z': case 'B': case 'C': case 'S': case 'I':
                        c.pushInt((int) s.bits);
                        break;
                    case 'F':
                        c.pushFloatBits((int) s.bits);
                        break;
                    case 'J':
                        c.pushLong(s.bits);
                        break;
                    case 'D':
                        c.pushDoubleBits(s.bits);
                        break;
                    default:
                        if (s.ref == null) {
                            c.op(CodeWriter.ACONST_NULL);
                        } else if (s.ref instanceof String) {
                            c.ldcString((String) s.ref);
                        } else {
                            c.varOp(CodeWriter.ALOAD, 0);
                            c.pushInt(index.get((Arr) s.ref));
                            c.op(CodeWriter.AALOAD);
                            if (!"Ljava/lang/Object;".equals(s.type)) {
                                c.typeOp(CodeWriter.CHECKCAST, DexFile.internalName(s.type));
                            }
                        }
                        break;
                }
                c.fieldOp(CodeWriter.PUTSTATIC, owner, s.name, s.type);
            }
            c.op(CodeWriter.RETURN);
            return c;
        }
    }
}
