package com.macapk.dex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Code attribute of one method (JVMS SE21 section 4.7.3): the
 * bytecode itself, max_stack / max_locals, the exception table, and the
 * LineNumberTable / LocalVariableTable / StackMapTable sub-attributes.
 *
 * Instructions are recorded as a list and only serialized in toCodeAttributeBody(),
 * because three things cannot be known while emitting:
 *
 *  - Whether a forward branch fits in the signed 16-bit operand that every
 *    conditional branch has. JVMS 4.9.1 caps code_length at 65535, so a single
 *    method really can need goto_w. Conditional branches have NO wide form at
 *    all, so a far `ifeq L` has to become `ifne skip; goto_w L; skip:`.
 *  - Where tableswitch / lookupswitch padding lands. JVMS section 6.5
 *    (tableswitch): "immediately after the tableswitch opcode, between zero and
 *    three bytes must act as padding, such that defaultbyte1 begins at an
 *    address that is a multiple of four bytes from the start of the current
 *    method". Widening a branch earlier in the method moves the switch and
 *    changes its padding.
 *  - The bytecode offsets the StackMapTable and the exception table refer to.
 *
 * Layout is a fixpoint, but only over JUMP sizes, which are monotonically
 * non-decreasing and bounded (3 -> 5 or 3 -> 8, at most once per jump). Switch
 * padding is recomputed as a pure function of the running offset during each
 * layout pass rather than being a fixpoint variable of its own, which is what
 * keeps the loop from oscillating between two switch alignments forever.
 *
 * Local variable indices and constant pool indices are known at emit time and
 * never move (ConstantPool allocates monotonically), so `wide` prefixes and the
 * ldc / ldc_w choice are decided immediately and never participate in layout.
 *
 * Not thread safe. One instance per method.
 */
public final class CodeWriter {

    // ---- opcodes (JVMS chapter 6) ----------------------------------------
    public static final int NOP = 0, ACONST_NULL = 1, ICONST_M1 = 2, ICONST_0 = 3,
            ICONST_1 = 4, ICONST_2 = 5, ICONST_3 = 6, ICONST_4 = 7, ICONST_5 = 8,
            LCONST_0 = 9, LCONST_1 = 10, FCONST_0 = 11, FCONST_1 = 12, FCONST_2 = 13,
            DCONST_0 = 14, DCONST_1 = 15, BIPUSH = 16, SIPUSH = 17, LDC = 18,
            LDC_W = 19, LDC2_W = 20, ILOAD = 21, LLOAD = 22, FLOAD = 23, DLOAD = 24,
            ALOAD = 25, ILOAD_0 = 26, LLOAD_0 = 30, FLOAD_0 = 34, DLOAD_0 = 38,
            ALOAD_0 = 42, IALOAD = 46, LALOAD = 47, FALOAD = 48, DALOAD = 49,
            AALOAD = 50, BALOAD = 51, CALOAD = 52, SALOAD = 53, ISTORE = 54,
            LSTORE = 55, FSTORE = 56, DSTORE = 57, ASTORE = 58, ISTORE_0 = 59,
            LSTORE_0 = 63, FSTORE_0 = 67, DSTORE_0 = 71, ASTORE_0 = 75,
            IASTORE = 79, LASTORE = 80, FASTORE = 81, DASTORE = 82, AASTORE = 83,
            BASTORE = 84, CASTORE = 85, SASTORE = 86, POP = 87, POP2 = 88, DUP = 89,
            DUP_X1 = 90, DUP_X2 = 91, DUP2 = 92, DUP2_X1 = 93, DUP2_X2 = 94, SWAP = 95,
            IADD = 96, LADD = 97, FADD = 98, DADD = 99, ISUB = 100, LSUB = 101,
            FSUB = 102, DSUB = 103, IMUL = 104, LMUL = 105, FMUL = 106, DMUL = 107,
            IDIV = 108, LDIV = 109, FDIV = 110, DDIV = 111, IREM = 112, LREM = 113,
            FREM = 114, DREM = 115, INEG = 116, LNEG = 117, FNEG = 118, DNEG = 119,
            ISHL = 120, LSHL = 121, ISHR = 122, LSHR = 123, IUSHR = 124, LUSHR = 125,
            IAND = 126, LAND = 127, IOR = 128, LOR = 129, IXOR = 130, LXOR = 131,
            IINC = 132, I2L = 133, I2F = 134, I2D = 135, L2I = 136, L2F = 137,
            L2D = 138, F2I = 139, F2L = 140, F2D = 141, D2I = 142, D2L = 143,
            D2F = 144, I2B = 145, I2C = 146, I2S = 147, LCMP = 148, FCMPL = 149,
            FCMPG = 150, DCMPL = 151, DCMPG = 152, IFEQ = 153, IFNE = 154, IFLT = 155,
            IFGE = 156, IFGT = 157, IFLE = 158, IF_ICMPEQ = 159, IF_ICMPNE = 160,
            IF_ICMPLT = 161, IF_ICMPGE = 162, IF_ICMPGT = 163, IF_ICMPLE = 164,
            IF_ACMPEQ = 165, IF_ACMPNE = 166, GOTO = 167, JSR = 168, RET = 169,
            TABLESWITCH = 170, LOOKUPSWITCH = 171, IRETURN = 172, LRETURN = 173,
            FRETURN = 174, DRETURN = 175, ARETURN = 176, RETURN = 177,
            GETSTATIC = 178, PUTSTATIC = 179, GETFIELD = 180, PUTFIELD = 181,
            INVOKEVIRTUAL = 182, INVOKESPECIAL = 183, INVOKESTATIC = 184,
            INVOKEINTERFACE = 185, INVOKEDYNAMIC = 186, NEW = 187, NEWARRAY = 188,
            ANEWARRAY = 189, ARRAYLENGTH = 190, ATHROW = 191, CHECKCAST = 192,
            INSTANCEOF = 193, MONITORENTER = 194, MONITOREXIT = 195, WIDE = 196,
            MULTIANEWARRAY = 197, IFNULL = 198, IFNONNULL = 199, GOTO_W = 200,
            JSR_W = 201;

    // newarray atype codes (JVMS 6.5 newarray, Table 6.5.newarray-A)
    public static final int T_BOOLEAN = 4, T_CHAR = 5, T_FLOAT = 6, T_DOUBLE = 7,
            T_BYTE = 8, T_SHORT = 9, T_INT = 10, T_LONG = 11;

    /**
     * Operand stack effect of every opcode whose effect does NOT depend on a
     * descriptor. The descriptor-dependent ones (getstatic through
     * invokedynamic, and multianewarray) are computed at emit time and are left
     * at 0 here.
     */
    private static final byte[] DELTA = new byte[202];
    static {
        int[][] spec = {
            {NOP, 0}, {ACONST_NULL, 1},
            {ICONST_M1, 1}, {ICONST_0, 1}, {ICONST_1, 1}, {ICONST_2, 1},
            {ICONST_3, 1}, {ICONST_4, 1}, {ICONST_5, 1},
            {LCONST_0, 2}, {LCONST_1, 2},
            {FCONST_0, 1}, {FCONST_1, 1}, {FCONST_2, 1},
            {DCONST_0, 2}, {DCONST_1, 2},
            {BIPUSH, 1}, {SIPUSH, 1}, {LDC, 1}, {LDC_W, 1}, {LDC2_W, 2},
            {ILOAD, 1}, {LLOAD, 2}, {FLOAD, 1}, {DLOAD, 2}, {ALOAD, 1},
            {IALOAD, -1}, {LALOAD, 0}, {FALOAD, -1}, {DALOAD, 0}, {AALOAD, -1},
            {BALOAD, -1}, {CALOAD, -1}, {SALOAD, -1},
            {ISTORE, -1}, {LSTORE, -2}, {FSTORE, -1}, {DSTORE, -2}, {ASTORE, -1},
            {IASTORE, -3}, {LASTORE, -4}, {FASTORE, -3}, {DASTORE, -4},
            {AASTORE, -3}, {BASTORE, -3}, {CASTORE, -3}, {SASTORE, -3},
            {POP, -1}, {POP2, -2}, {DUP, 1}, {DUP_X1, 1}, {DUP_X2, 1},
            {DUP2, 2}, {DUP2_X1, 2}, {DUP2_X2, 2}, {SWAP, 0},
            {IADD, -1}, {LADD, -2}, {FADD, -1}, {DADD, -2},
            {ISUB, -1}, {LSUB, -2}, {FSUB, -1}, {DSUB, -2},
            {IMUL, -1}, {LMUL, -2}, {FMUL, -1}, {DMUL, -2},
            {IDIV, -1}, {LDIV, -2}, {FDIV, -1}, {DDIV, -2},
            {IREM, -1}, {LREM, -2}, {FREM, -1}, {DREM, -2},
            {INEG, 0}, {LNEG, 0}, {FNEG, 0}, {DNEG, 0},
            // A shift consumes an int shift-amount, so the long forms are -1
            // (long + int in, long out), not -2 like the arithmetic forms.
            {ISHL, -1}, {LSHL, -1}, {ISHR, -1}, {LSHR, -1}, {IUSHR, -1}, {LUSHR, -1},
            {IAND, -1}, {LAND, -2}, {IOR, -1}, {LOR, -2}, {IXOR, -1}, {LXOR, -2},
            {IINC, 0},
            {I2L, 1}, {I2F, 0}, {I2D, 1}, {L2I, -1}, {L2F, -1}, {L2D, 0},
            {F2I, 0}, {F2L, 1}, {F2D, 1}, {D2I, -1}, {D2L, 0}, {D2F, -1},
            {I2B, 0}, {I2C, 0}, {I2S, 0},
            {LCMP, -3}, {FCMPL, -1}, {FCMPG, -1}, {DCMPL, -3}, {DCMPG, -3},
            {IFEQ, -1}, {IFNE, -1}, {IFLT, -1}, {IFGE, -1}, {IFGT, -1}, {IFLE, -1},
            {IF_ICMPEQ, -2}, {IF_ICMPNE, -2}, {IF_ICMPLT, -2}, {IF_ICMPGE, -2},
            {IF_ICMPGT, -2}, {IF_ICMPLE, -2}, {IF_ACMPEQ, -2}, {IF_ACMPNE, -2},
            {GOTO, 0}, {JSR, 1}, {RET, 0},
            {TABLESWITCH, -1}, {LOOKUPSWITCH, -1},
            {IRETURN, -1}, {LRETURN, -2}, {FRETURN, -1}, {DRETURN, -2},
            {ARETURN, -1}, {RETURN, 0},
            {NEW, 1}, {NEWARRAY, 0}, {ANEWARRAY, 0}, {ARRAYLENGTH, 0},
            {ATHROW, -1}, {CHECKCAST, 0}, {INSTANCEOF, 0},
            {MONITORENTER, -1}, {MONITOREXIT, -1},
            {IFNULL, -1}, {IFNONNULL, -1}, {GOTO_W, 0}, {JSR_W, 1},
        };
        for (int[] s : spec) DELTA[s[0]] = (byte) s[1];
        // The _0.._3 shorthands share the effect of their base opcode.
        for (int i = 0; i < 4; i++) {
            DELTA[ILOAD_0 + i] = DELTA[ILOAD];
            DELTA[LLOAD_0 + i] = DELTA[LLOAD];
            DELTA[FLOAD_0 + i] = DELTA[FLOAD];
            DELTA[DLOAD_0 + i] = DELTA[DLOAD];
            DELTA[ALOAD_0 + i] = DELTA[ALOAD];
            DELTA[ISTORE_0 + i] = DELTA[ISTORE];
            DELTA[LSTORE_0 + i] = DELTA[LSTORE];
            DELTA[FSTORE_0 + i] = DELTA[FSTORE];
            DELTA[DSTORE_0 + i] = DELTA[DSTORE];
            DELTA[ASTORE_0 + i] = DELTA[ASTORE];
        }
    }

    /** A position in the instruction stream. Resolved to a bytecode offset by
     *  layout; safe to reference from exception handlers, line numbers and
     *  stack map frames before it is even marked. */
    public static final class Label {
        int insnIndex = -1;
        int offset = -1;
        boolean referenced;
        /** Optional provenance, e.g. the DEX offset this label stands for. Only
         *  used to make "referenced but never marked" name the culprit, which is
         *  the difference between a one-line diagnosis and a bisect. */
        String origin;

        /** Bytecode offset, valid only after the enclosing CodeWriter has been
         *  serialized (or during serialization, which is when StackMapWriter
         *  reads it for an ITEM_Uninitialized entry). */
        public int offset() {
            if (offset < 0) throw new IllegalStateException("label not resolved yet");
            return offset;
        }

        boolean isMarked() { return insnIndex >= 0; }

        @Override public String toString() { return offset >= 0 ? Integer.toString(offset) : "?"; }
    }

    // ---- instruction records --------------------------------------------

    private abstract static class Insn {
        int offset;
        int delta;              // operand stack effect
        boolean fallsThrough = true;
        abstract int size();
        /** Recomputes size for the given start offset. Only Switch uses the
         *  argument; everything else is offset independent. */
        void layoutAt(int off) { this.offset = off; }
        abstract void write(ConstantPool.ByteVector out);
    }

    /** Fixed-size opcode with 0..4 bytes of already-known operand. */
    private static final class Op extends Insn {
        final int opcode;
        final int operand;
        final int operandBytes;

        Op(int opcode, int operand, int operandBytes) {
            this.opcode = opcode;
            this.operand = operand;
            this.operandBytes = operandBytes;
        }

        @Override int size() { return 1 + operandBytes; }

        @Override void write(ConstantPool.ByteVector out) {
            out.putU1(opcode);
            switch (operandBytes) {
                case 0: break;
                case 1: out.putU1(operand); break;
                case 2: out.putU2(operand); break;
                case 3: out.putU2(operand >>> 8); out.putU1(operand); break;
                default: out.putU4(operand); break;
            }
        }
    }

    /** wide-prefixed form: 196, opcode, u2 index [, s2 const for iinc]. */
    private static final class WideOp extends Insn {
        final int opcode;
        final int index;
        final int constant;
        final boolean hasConstant;

        WideOp(int opcode, int index, int constant, boolean hasConstant) {
            this.opcode = opcode;
            this.index = index;
            this.constant = constant;
            this.hasConstant = hasConstant;
        }

        @Override int size() { return hasConstant ? 6 : 4; }

        @Override void write(ConstantPool.ByteVector out) {
            out.putU1(WIDE);
            out.putU1(opcode);
            out.putU2(index);
            if (hasConstant) out.putU2(constant & 0xFFFF);
        }
    }

    private static final class Mark extends Insn {
        final Label label;
        Mark(Label label) { this.label = label; }
        @Override int size() { return 0; }
        @Override void write(ConstantPool.ByteVector out) { }
    }

    private static final class Jump extends Insn {
        final int opcode;
        final Label target;
        int width = 3;   // 3 = short form, 5 = goto_w/jsr_w, 8 = inverted + goto_w

        Jump(int opcode, Label target) {
            this.opcode = opcode;
            this.target = target;
        }

        @Override int size() { return width; }

        /** Returns true if the encoding had to grow. Sizes never shrink, which
         *  is what makes the layout loop terminate. */
        boolean grow() {
            if (width != 3) return false;
            int rel = target.offset - offset;
            if (rel >= -32768 && rel <= 32767) return false;
            width = (opcode == GOTO || opcode == JSR) ? 5 : 8;
            return true;
        }

        @Override void write(ConstantPool.ByteVector out) {
            if (width == 3) {
                out.putU1(opcode);
                out.putU2((target.offset - offset) & 0xFFFF);
            } else if (width == 5) {
                out.putU1(opcode == GOTO ? GOTO_W : JSR_W);
                out.putU4(target.offset - offset);
            } else {
                // Conditional branches have no wide form (JVMS chapter 6 gives
                // every if<cond> a signed 16-bit branchoffset and no _w
                // variant), so invert the test to hop over an unconditional
                // goto_w:  if<!cond> +8 ; goto_w target ; <here>
                out.putU1(invert(opcode));
                out.putU2(8);
                out.putU1(GOTO_W);
                out.putU4(target.offset - (offset + 3));
            }
        }

        private static int invert(int opcode) {
            if (opcode >= IFEQ && opcode <= IF_ACMPNE) {
                return ((opcode - IFEQ) % 2 == 0) ? opcode + 1 : opcode - 1;
            }
            if (opcode == IFNULL) return IFNONNULL;
            if (opcode == IFNONNULL) return IFNULL;
            throw new IllegalStateException("not an invertible branch: " + opcode);
        }
    }

    private static final class Switch extends Insn {
        final boolean table;
        final int low, high;        // tableswitch only
        final int[] keys;           // lookupswitch only
        final Label dflt;
        final Label[] targets;
        int pad;

        Switch(boolean table, int low, int high, int[] keys, Label dflt, Label[] targets) {
            this.table = table;
            this.low = low;
            this.high = high;
            this.keys = keys;
            this.dflt = dflt;
            this.targets = targets;
            this.delta = -1;
            this.fallsThrough = false;
        }

        private int body() {
            return table ? (4 + 4 + 4 + 4 * targets.length)      // default, low, high, jumps
                         : (4 + 4 + 8 * targets.length);         // default, npairs, (match, offset)*
        }

        @Override int size() { return 1 + pad + body(); }

        @Override void layoutAt(int off) {
            this.offset = off;
            // JVMS 6.5: padding runs to the next 4-byte boundary measured from
            // the start of the method, i.e. from code[0].
            pad = (4 - ((off + 1) & 3)) & 3;
        }

        @Override void write(ConstantPool.ByteVector out) {
            out.putU1(table ? TABLESWITCH : LOOKUPSWITCH);
            for (int i = 0; i < pad; i++) out.putU1(0);
            out.putU4(dflt.offset - offset);
            if (table) {
                out.putU4(low);
                out.putU4(high);
                for (Label t : targets) out.putU4(t.offset - offset);
            } else {
                out.putU4(targets.length);
                for (int i = 0; i < targets.length; i++) {
                    out.putU4(keys[i]);
                    out.putU4(targets[i].offset - offset);
                }
            }
        }
    }

    private static final class Handler {
        final Label start, end, handler;
        final String type;   // null means "any", i.e. catch_type 0 (finally)
        Handler(Label s, Label e, Label h, String t) {
            start = s; end = e; handler = h; type = t;
        }
    }

    private static final class LineEntry {
        final Label start; final int line;
        LineEntry(Label s, int l) { start = s; line = l; }
    }

    private static final class LocalEntry {
        final Label start, end;
        final String name, desc, signature;
        final int index;
        LocalEntry(Label s, Label e, String n, String d, String sig, int i) {
            start = s; end = e; name = n; desc = d; signature = sig; index = i;
        }
    }

    // ---- state -----------------------------------------------------------

    private final ConstantPool pool;
    private final List<Insn> insns = new ArrayList<>();
    private final List<Jump> jumps = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Handler> handlers = new ArrayList<>();
    private final List<LineEntry> lines = new ArrayList<>();
    private final List<LocalEntry> locals = new ArrayList<>();
    private final Map<Label, StackMapWriter.Frame> labelFrames = new HashMap<>();

    private int explicitMaxStack = -1;
    private int explicitMaxLocals = -1;
    private int codeLength = -1;

    private StackMapWriter.FrameProvider frameProvider;
    private StackMapWriter.Frame initialFrame;
    private boolean stackMapEmitted;
    private String stackMapDropReason;
    /** Largest local-SLOT count over the frames actually written, so max_locals
     *  can cover them. Reset per serialization. */
    private int frameMaxLocalSlots;

    /** Diagnostic only. A dropped StackMapTable is invisible in the class file
     *  (JVMS 4.7.4 makes an absent table equivalent to an empty one), so the
     *  symptom surfaces far away as "Expecting a stackmap frame at branch
     *  target N". Setting DEX2JVM_FRAME_DEBUG names the offset that caused
     *  the drop, which is the only way to find it without a debugger. */
    private static final boolean FRAME_DEBUG =
            System.getenv("DEX2JVM_FRAME_DEBUG") != null;

    /** Re-decodes every StackMapTable this writer produces and fails loudly if
     *  it does not read back as the frames that were handed in. Off by default
     *  because it doubles the frame work; on, it turns the encoder from a thing
     *  to be trusted into a thing that is checked. See
     *  StackMapWriter.roundTripMismatch. */
    private static final boolean FRAME_SELFCHECK =
            System.getenv("DEX2JVM_FRAME_SELFCHECK") != null;

    /**
     * How many methods have had their StackMapTable dropped, process wide.
     *
     * A drop is INVISIBLE in the output (JVMS 4.7.4 makes an absent table
     * equivalent to an empty one) and only surfaces much later as "Expecting a
     * stackmap frame at branch target N" from a verifier that checks the class
     * at link time. Throwing instead is not an option -- that would
     * trade a verify error for a translation error, i.e. lose the whole class
     * over a single method that may well run correctly when it is loaded
     * without verification. So it stays a fallback and becomes
     * COUNTABLE instead: a gate can assert this is 0 rather than inferring it
     * from a downstream symptom.
     */
    private static final java.util.concurrent.atomic.AtomicLong droppedTables =
            new java.util.concurrent.atomic.AtomicLong();

    /** Number of methods whose StackMapTable was dropped since process start. */
    public static long droppedStackMapTables() { return droppedTables.get(); }

    /** Methods whose max_stack could not be computed exactly; see
     *  computeMaxStack. Non-zero means some max_stack is a lower bound. */
    private static final java.util.concurrent.atomic.AtomicLong maxStackGaveUp =
            new java.util.concurrent.atomic.AtomicLong();

    /** Number of methods whose max_stack relaxation hit its budget. */
    public static long maxStackBudgetExhausted() { return maxStackGaveUp.get(); }

    public CodeWriter(ConstantPool pool) { this.pool = pool; }

    public ConstantPool pool() { return pool; }

    // ---- labels ----------------------------------------------------------

    public Label newLabel() {
        Label l = new Label();
        labels.add(l);
        return l;
    }

    /** A label that remembers where it came from, for error messages. */
    public Label newLabel(String origin) {
        Label l = newLabel();
        l.origin = origin;
        return l;
    }

    /** Binds a label to the current position. A label may be marked once. */
    public void mark(Label l) {
        if (l.isMarked()) throw new IllegalStateException("label already marked");
        l.insnIndex = insns.size();
        add(new Mark(l));
    }

    private void add(Insn in) { insns.add(in); }

    // ---- plain instructions ---------------------------------------------

    /** Any opcode with no operand bytes. */
    public void op(int opcode) {
        Op in = new Op(opcode, 0, 0);
        in.delta = DELTA[opcode];
        in.fallsThrough = !isTerminal(opcode);
        add(in);
    }

    private static boolean isTerminal(int opcode) {
        return (opcode >= IRETURN && opcode <= RETURN) || opcode == ATHROW || opcode == RET;
    }

    /** bipush / sipush / newarray. */
    public void intOp(int opcode, int operand) {
        int bytes = (opcode == SIPUSH) ? 2 : 1;
        Op in = new Op(opcode, operand, bytes);
        in.delta = DELTA[opcode];
        add(in);
    }

    /**
     * iload/lload/fload/dload/aload/istore/lstore/fstore/dstore/astore/ret.
     * Picks the 1-byte shorthand for indices 0..3 and the wide form above 255;
     * the index is final at emit time so this never affects layout.
     */
    public void varOp(int opcode, int local) {
        if (local < 0) throw new IllegalArgumentException("negative local " + local);
        Insn in;
        if (local <= 3 && opcode != RET) {
            in = new Op(shorthand(opcode) + local, 0, 0);
        } else if (local <= 255) {
            in = new Op(opcode, local, 1);
        } else {
            in = new WideOp(opcode, local, 0, false);
        }
        in.delta = DELTA[opcode];
        in.fallsThrough = opcode != RET;
        add(in);
        // A long or double store/load claims two slots, so max_locals has to
        // reach index + 2 for those.
        int width = (opcode == LLOAD || opcode == DLOAD || opcode == LSTORE || opcode == DSTORE) ? 2 : 1;
        impliedMaxLocals = Math.max(impliedMaxLocals, local + width);
    }

    private static int shorthand(int opcode) {
        switch (opcode) {
            case ILOAD: return ILOAD_0;
            case LLOAD: return LLOAD_0;
            case FLOAD: return FLOAD_0;
            case DLOAD: return DLOAD_0;
            case ALOAD: return ALOAD_0;
            case ISTORE: return ISTORE_0;
            case LSTORE: return LSTORE_0;
            case FSTORE: return FSTORE_0;
            case DSTORE: return DSTORE_0;
            case ASTORE: return ASTORE_0;
            default: throw new IllegalArgumentException("not a local-variable opcode: " + opcode);
        }
    }

    private int impliedMaxLocals;

    /** iinc, with the wide form when either operand does not fit in a byte. */
    public void iinc(int local, int increment) {
        Insn in;
        if (local <= 255 && increment >= -128 && increment <= 127) {
            in = new Op(IINC, ((local & 0xFF) << 8) | (increment & 0xFF), 2);
        } else {
            in = new WideOp(IINC, local, increment, true);
        }
        in.delta = 0;
        add(in);
        impliedMaxLocals = Math.max(impliedMaxLocals, local + 1);
    }

    /** new / anewarray / checkcast / instanceof. `type` is an internal name, or
     *  a descriptor for an array type. */
    public void typeOp(int opcode, String type) {
        Op in = new Op(opcode, pool.classRef(type), 2);
        in.delta = DELTA[opcode];
        add(in);
    }

    /**
     * Every putstatic / putfield this body holds, as pairs {opcode,
     * constant-pool index of its Fieldref}, flattened. Read from the recorded
     * instruction list, which is exactly what toCodeAttributeBody serializes
     * (fieldOp is the only way to add one, and nothing is ever removed), so a
     * caller sees the bytecode as it will be written.
     */
    int[] fieldPuts() {
        int n = 0;
        for (Insn in : insns) {
            if (in instanceof Op && isPut(((Op) in).opcode)) n++;
        }
        int[] out = new int[2 * n];
        int k = 0;
        for (Insn in : insns) {
            if (!(in instanceof Op)) continue;
            Op o = (Op) in;
            if (!isPut(o.opcode)) continue;
            out[k++] = o.opcode;
            out[k++] = o.operand;
        }
        return out;
    }

    private static boolean isPut(int opcode) { return opcode == PUTSTATIC || opcode == PUTFIELD; }

    /** getstatic / putstatic / getfield / putfield. */
    public void fieldOp(int opcode, String owner, String name, String desc) {
        int slots = slotsOf(desc);
        int d;
        switch (opcode) {
            case GETSTATIC: d = slots; break;
            case PUTSTATIC: d = -slots; break;
            case GETFIELD: d = slots - 1; break;
            case PUTFIELD: d = -slots - 1; break;
            default: throw new IllegalArgumentException("not a field opcode: " + opcode);
        }
        Op in = new Op(opcode, pool.fieldRef(owner, name, desc), 2);
        in.delta = d;
        add(in);
    }

    /**
     * invokevirtual / invokespecial / invokestatic / invokeinterface.
     *
     * `itf` selects CONSTANT_InterfaceMethodref over CONSTANT_Methodref and is
     * NOT the same question as "is this invokeinterface": a static or default
     * method on an interface is called with invokestatic / invokespecial but
     * still needs an InterfaceMethodref, and getting it backwards produces an
     * IncompatibleClassChangeError only when the call finally executes.
     */
    public void methodOp(int opcode, String owner, String name, String desc, boolean itf) {
        int args = argSlots(desc);
        int ret = returnSlots(desc);
        int d = (opcode == INVOKESTATIC) ? (ret - args) : (ret - args - 1);
        int ref = (opcode == INVOKEINTERFACE)
                ? pool.interfaceMethodRef(owner, name, desc)
                : pool.anyMethodRef(owner, name, desc, itf);
        Insn in;
        if (opcode == INVOKEINTERFACE) {
            // JVMS 6.5 invokeinterface: u2 index, u1 count, u1 zero. `count`
            // is the argument slot count INCLUDING the receiver and must be
            // nonzero. It is a u1, and JVMS 4.3.3 caps a method at 255 argument
            // slots including `this` for exactly that reason -- but a DEX we did
            // not write is not obliged to respect that, and letting the count
            // wrap would corrupt the two operand bytes rather than fail.
            if (args + 1 > 255) {
                throw new ConstantPool.LimitExceededException(
                        "invokeinterface argument slots " + (args + 1)
                                + " exceeds 255 (JVMS 4.3.3): " + owner + "." + name + desc);
            }
            in = new Op(opcode, (ref << 16) | ((args + 1) << 8), 4);
        } else {
            in = new Op(opcode, ref, 2);
        }
        in.delta = d;
        add(in);
    }

    /** invokedynamic. bootstrapMethodAttrIndex indexes the class's
     *  BootstrapMethods attribute (see ClassFileWriter.addBootstrapMethod). */
    public void invokeDynamic(int bootstrapMethodAttrIndex, String name, String desc) {
        int ref = pool.invokeDynamic(bootstrapMethodAttrIndex, name, desc);
        // JVMS 6.5 invokedynamic: u2 index followed by two zero bytes.
        Op in = new Op(INVOKEDYNAMIC, ref << 16, 4);
        in.delta = returnSlots(desc) - argSlots(desc);
        add(in);
    }

    /** multianewarray. `desc` is the ARRAY type descriptor, e.g. "[[I". */
    public void multiANewArray(String desc, int dimensions) {
        // JVMS 6.5 multianewarray: dimensions is a u1 and "must be greater than
        // or equal to 1". Masking a bad value into range would emit a different
        // instruction than the caller asked for, silently.
        if (dimensions < 1 || dimensions > 255) {
            throw new IllegalArgumentException(
                    "multianewarray dimensions " + dimensions + " outside 1..255 (JVMS 6.5): " + desc);
        }
        Op in = new Op(MULTIANEWARRAY, (pool.classRef(desc) << 8) | (dimensions & 0xFF), 3);
        in.delta = 1 - dimensions;
        add(in);
    }

    // ---- constants -------------------------------------------------------

    /** Emits ldc / ldc_w / ldc2_w for an already-allocated pool index. The
     *  2-byte ldc is chosen whenever the index fits in a u1; pool indices are
     *  final once handed out, so this decision never has to be revisited. */
    public void ldcRaw(int cpIndex) {
        boolean wide = pool.isCategory2(cpIndex);
        Op in;
        if (wide) {
            in = new Op(LDC2_W, cpIndex, 2);
            in.delta = 2;
        } else if (cpIndex <= 255) {
            in = new Op(LDC, cpIndex, 1);
            in.delta = 1;
        } else {
            in = new Op(LDC_W, cpIndex, 2);
            in.delta = 1;
        }
        add(in);
    }

    public void ldcString(String s) { ldcRaw(pool.stringRef(s)); }
    public void ldcClass(String internalNameOrDesc) { ldcRaw(pool.classRef(internalNameOrDesc)); }

    /** Pushes an int using the shortest encoding. */
    public void pushInt(int v) {
        if (v >= -1 && v <= 5) op(ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) intOp(BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) intOp(SIPUSH, v);
        else ldcRaw(pool.integer(v));
    }

    public void pushLong(long v) {
        if (v == 0L) op(LCONST_0);
        else if (v == 1L) op(LCONST_1);
        else ldcRaw(pool.longConst(v));
    }

    /** Raw bits so that -0.0f and a specific NaN payload survive, which is what
     *  DEX const/4-const/high16 actually carry. */
    public void pushFloatBits(int bits) {
        if (bits == 0x00000000) op(FCONST_0);
        else if (bits == 0x3F800000) op(FCONST_1);
        else if (bits == 0x40000000) op(FCONST_2);
        else ldcRaw(pool.floatBits(bits));
    }

    public void pushDoubleBits(long bits) {
        if (bits == 0x0000000000000000L) op(DCONST_0);
        else if (bits == 0x3FF0000000000000L) op(DCONST_1);
        else ldcRaw(pool.doubleBits(bits));
    }

    // ---- control flow ----------------------------------------------------

    /**
     * Any if&lt;cond&gt; / goto / jsr. Always pass the SHORT opcode: widening to
     * goto_w, or to the inverted-test-plus-goto_w sequence, is this class's
     * job and depends on a final layout the caller does not have yet.
     */
    public void jump(int opcode, Label target) {
        if (opcode == GOTO_W || opcode == JSR_W) {
            throw new IllegalArgumentException(
                    "pass GOTO/JSR; the wide form is selected during layout");
        }
        target.referenced = true;
        Jump j = new Jump(opcode, target);
        j.delta = DELTA[opcode];
        j.fallsThrough = (opcode != GOTO && opcode != GOTO_W);
        add(j);
        jumps.add(j);
    }

    public void tableSwitch(int low, int high, Label dflt, Label[] targets) {
        if (targets.length != high - low + 1) {
            throw new IllegalArgumentException("tableswitch target count does not match [low, high]");
        }
        dflt.referenced = true;
        for (Label t : targets) t.referenced = true;
        add(new Switch(true, low, high, null, dflt, targets));
    }

    public void lookupSwitch(Label dflt, int[] keys, Label[] targets) {
        if (keys.length != targets.length) {
            throw new IllegalArgumentException("lookupswitch key/target length mismatch");
        }
        // JVMS 6.5 lookupswitch: "The match-offset pairs ... must be sorted in
        // increasing numerical order by match." HotSpot's verifier checks this.
        for (int i = 1; i < keys.length; i++) {
            if (keys[i] <= keys[i - 1]) {
                throw new IllegalArgumentException("lookupswitch keys must be strictly increasing");
            }
        }
        dflt.referenced = true;
        for (Label t : targets) t.referenced = true;
        add(new Switch(false, 0, 0, keys, dflt, targets));
    }

    /** Adds an exception_table entry (JVMS 4.7.3). `type` null means catch-any,
     *  which is how a `finally` block and DEX's catch-all are expressed. */
    public void tryCatch(Label start, Label end, Label handler, String type) {
        start.referenced = true;
        end.referenced = true;
        handler.referenced = true;
        handlers.add(new Handler(start, end, handler, type));
    }

    // ---- debug tables ----------------------------------------------------

    /** LineNumberTable entry (JVMS 4.7.12). This is what makes a guest stack
     *  trace name a real source line instead of an offset. */
    public void lineNumber(int line, Label start) {
        start.referenced = true;
        lines.add(new LineEntry(start, line));
    }

    /** LocalVariableTable entry (JVMS 4.7.13), plus a LocalVariableTypeTable
     *  entry (4.7.14) when `signature` is non-null. */
    public void localVariable(String name, String desc, String signature,
                              Label start, Label end, int index) {
        start.referenced = true;
        end.referenced = true;
        locals.add(new LocalEntry(start, end, name, desc, signature, index));
    }

    // ---- limits and frames ----------------------------------------------

    public void setMaxStack(int v) { explicitMaxStack = v; }

    /** Set this from the DEX method's registers_size. The computed value is
     *  used as a floor regardless, since a too-small max_locals is a
     *  ClassFormatError and a too-large one is merely wasteful. */
    public void setMaxLocals(int v) { explicitMaxLocals = v; }

    /** Declares the verifier state at a label. Preferred over a FrameProvider:
     *  a DEX translator already thinks in basic blocks, and a label survives
     *  branch widening whereas a bytecode offset does not. */
    public void putFrame(Label label, StackMapWriter.Frame frame) {
        label.referenced = true;
        labelFrames.put(label, frame);
    }

    /** Offset-keyed alternative to putFrame, consulted only for offsets that
     *  putFrame did not already cover. */
    public void setFrameProvider(StackMapWriter.FrameProvider provider) {
        this.frameProvider = provider;
    }

    /** The implicit frame at offset 0 (JVMS 4.7.4). Required for a correct
     *  StackMapTable; build it with StackMapWriter.initialFrame(). Without it
     *  no StackMapTable is emitted, because every delta is relative to it. */
    public void setInitialFrame(StackMapWriter.Frame frame) { this.initialFrame = frame; }

    /** True if the last serialization actually wrote a StackMapTable. */
    public boolean stackMapEmitted() { return stackMapEmitted; }

    /** Why the StackMapTable was omitted, or null if it was emitted or never
     *  requested. Callers that require verifier-clean output should treat a
     *  non-null value as a hard error. */
    public String stackMapDropReason() { return stackMapDropReason; }

    public int codeLength() { return codeLength; }

    // ---- descriptor helpers ---------------------------------------------

    /** Operand stack slots a value of this field descriptor occupies. */
    public static int slotsOf(String desc) {
        char c = desc.charAt(0);
        return (c == 'J' || c == 'D') ? 2 : 1;
    }

    /**
     * Total argument slots of a method descriptor, excluding the receiver.
     *
     * An array is ONE slot regardless of its element type, so "[J" contributes
     * 1 and not 2. Consuming the '[' run before looking at the element char is
     * the whole point of the first branch.
     */
    public static int argSlots(String methodDescriptor) {
        int n = 0;
        int i = 1;
        while (methodDescriptor.charAt(i) != ')') {
            char c = methodDescriptor.charAt(i);
            if (c == '[') {
                while (methodDescriptor.charAt(i) == '[') i++;
                if (methodDescriptor.charAt(i) == 'L') {
                    i = methodDescriptor.indexOf(';', i) + 1;
                } else {
                    i++;
                }
                n += 1;
            } else if (c == 'L') {
                i = methodDescriptor.indexOf(';', i) + 1;
                n += 1;
            } else {
                n += (c == 'J' || c == 'D') ? 2 : 1;
                i++;
            }
        }
        return n;
    }

    /** Return-value slots of a method descriptor (0 for void). */
    public static int returnSlots(String methodDescriptor) {
        char c = methodDescriptor.charAt(methodDescriptor.indexOf(')') + 1);
        if (c == 'V') return 0;
        return (c == 'J' || c == 'D') ? 2 : 1;
    }

    // ---- monitor pairing (HotSpot's JIT precondition) ----------------------

    /** True when the body contains a monitorenter. */
    boolean hasMonitorEnter() {
        for (Insn in : insns) {
            if (in instanceof Op && ((Op) in).opcode == MONITORENTER) return true;
        }
        return false;
    }

    /**
     * Bytecodes::can_trap for the JVMS opcodes (jdk21u
     * src/hotspot/share/interpreter/bytecodes.cpp, the can_trap column of each
     * def(...)): ldc/ldc_w/ldc2_w, aload_0, the array loads and stores,
     * idiv/ldiv/irem/lrem, the returns, field access, every invoke, new /
     * newarray / anewarray / multianewarray, arraylength, athrow, checkcast,
     * instanceof, monitorenter and monitorexit.
     */
    private static boolean canTrap(int op) {
        if (op >= IALOAD && op <= SALOAD) return true;
        if (op >= IASTORE && op <= SASTORE) return true;
        if (op >= IRETURN && op <= RETURN) return true;
        if (op >= GETSTATIC && op <= MONITOREXIT) return true;   // field ops .. monitorexit
        switch (op) {
            case LDC: case LDC_W: case LDC2_W: case ALOAD_0:
            case IDIV: case LDIV: case IREM: case LREM:
            case MULTIANEWARRAY:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether HotSpot's JIT will accept this method's monitors, answered the way
     * ciMethod::has_balanced_monitors answers it: null when balanced, else the
     * first mismatch GenerateOopMap would report.
     *
     * WHY THE CONVERTER ASKS. C1 and C2 compile a method containing
     * monitorenter only if GeneratePairingInfo(method).monitor_safe() holds
     * (jdk21u src/hotspot/share/ci/ciMethod.cpp has_balanced_monitors); when it
     * does not, the compile bails at every tier with "not compilable (unbalanced
     * monitors)" and the method runs interpreted for the life of the process.
     * The code still verifies and still computes the right answer, so no other
     * check sees it. Translator.MONITOR_COVER uses this answer to decide
     * whether a monitor method needs its exception table re-planned, and keeps
     * a re-plan only if this says it is balanced.
     *
     * THE RULES (jdk21u src/hotspot/share/oops/generateOopMap.cpp), replayed on
     * the instruction list rather than on laid-out bytes, which is equivalent:
     * an exception_table row covers exactly the instructions between its two
     * labels, and a Mark has no bytes of its own.
     *   - _max_monitors is the static monitorenter count; a push at that depth
     *     is "monitor stack overflow" (monitor_push).
     *   - a monitorexit at depth 0 is "monitor stack underflow" (monitor_pop).
     *   - reaching one instruction at two depths is "monitor stack height merge
     *     conflict" (merge_state_into_bb).
     *   - a return at depth > 0 is "non-empty monitor stack at return"
     *     (do_return_monitor_check); an athrow at depth > 0 in a method with no
     *     exception table cannot be matched either.
     *   - do_exception_edge, on the PRE-state of every can_trap bytecode except
     *     aload_0 (never), a return (only at depth != 0) and a monitorexit
     *     (only at depth 0): each covering row in table order merges the depth
     *     into its handler, the first catch_type 0 row ends the walk, and if none
     *     did while a lock is held the method is unsafe ("non-empty monitor
     *     stack at exceptional exit"). Only catch_type 0 counts; a typed catch,
     *     even of Throwable, does not.
     * NOT replayed: lock IDENTITY ("improper monitor pair", "nested redundant
     * lock"), which needs GenerateOopMap's reference tracking. So a null answer
     * is necessary for compilation, not sufficient. Identity CAN depend on the
     * table (a handler entered from two different locks merges them into a
     * plain reference), so MonitorCover guards it on its side -- one stub per
     * monitor context, exiting a register that holds the lock throughout, and
     * no plan for a lock taken twice -- and a WhiteBox compile at tier 4 asks
     * HotSpot itself about the methods it rescues (8,796 of 8,817 compiled
     * over the corpus, 0 refused, 21 unloadable for missing ad-SDK classes).
     * This is the in-converter copy of the rule; Translator.MONITOR_SAFE_TRY
     * records what a standalone byte-reading replica of it measured.
     */
    String monitorImbalance() {
        int n = insns.size();
        int enters = 0;
        for (Insn in : insns) {
            if (in instanceof Op && ((Op) in).opcode == MONITORENTER) enters++;
        }
        if (enters == 0 || n == 0) return null;
        boolean hasExceptions = !handlers.isEmpty();
        int[] depth = new int[n];
        java.util.Arrays.fill(depth, -1);
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        depth[0] = 0;
        work.add(0);
        while (!work.isEmpty()) {
            int i = work.poll();
            int d = depth[i];
            Insn in = insns.get(i);
            int op = opcodeOf(in);

            if (op >= 0 && (hasExceptions || d != 0) && canTrap(op)) {
                boolean edge = true;
                if (op == ALOAD_0) edge = false;
                else if (op >= IRETURN && op <= RETURN && d == 0) edge = false;
                else if (op == MONITOREXIT && d != 0) edge = false;
                if (edge) {
                    boolean caughtAll = false;
                    for (Handler h : handlers) {
                        if (i < h.start.insnIndex || i >= h.end.insnIndex) continue;
                        String m = mergeDepth(depth, work, h.handler.insnIndex, d);
                        if (m != null) return m + " at insn " + i;
                        if (h.type == null) { caughtAll = true; break; }
                    }
                    if (!caughtAll && d != 0) {
                        return "non-empty monitor stack at exceptional exit at insn " + i
                                + " (opcode " + op + ")";
                    }
                }
            }

            int after = d;
            if (op == MONITORENTER) {
                if (d >= enters) return "monitor stack overflow at insn " + i;
                after = d + 1;
            } else if (op == MONITOREXIT) {
                if (d == 0) return "monitor stack underflow at insn " + i;
                after = d - 1;
            } else if (op >= IRETURN && op <= RETURN && d > 0) {
                return "non-empty monitor stack at return at insn " + i;
            } else if (op == ATHROW && !hasExceptions && d > 0) {
                return "athrow holding a monitor with no exception table at insn " + i;
            }

            if (in.fallsThrough && i + 1 < n) {
                String m = mergeDepth(depth, work, i + 1, after);
                if (m != null) return m + " at insn " + i;
            }
            if (in instanceof Jump) {
                String m = mergeDepth(depth, work, ((Jump) in).target.insnIndex, after);
                if (m != null) return m + " at insn " + i;
            } else if (in instanceof Switch) {
                Switch s = (Switch) in;
                String m = mergeDepth(depth, work, s.dflt.insnIndex, after);
                if (m != null) return m + " at insn " + i;
                for (Label t : s.targets) {
                    m = mergeDepth(depth, work, t.insnIndex, after);
                    if (m != null) return m + " at insn " + i;
                }
            }
        }
        return null;
    }

    /**
     * True when no bytecode lies between two BOUND labels: only Marks, which
     * have no bytes, sit in [start, end). An exception_table row over such a
     * span would have start_pc == end_pc, and ClassFileParser rejects that
     * whenever it verifies the class (jdk21u classFileParser.cpp
     * parse_exception_table: guarantee_property((start_pc < end_pc) &&
     * (end_pc <= code_length), "Illegal exception table range in class file
     * %s"); JVMS 4.7.3 requires start_pc < end_pc). A DEX instruction can
     * translate to no bytecode at all (a move the emitter folds away, say),
     * and Translator.MONITOR_COVER builds rows over runs of exactly those
     * non-throwing instructions, so it asks before adding one. An unbound
     * label answers false: nothing is known about it yet.
     */
    boolean coversNoCode(Label start, Label end) {
        if (!start.isMarked() || !end.isMarked()) return false;
        for (int i = start.insnIndex; i < end.insnIndex; i++) {
            if (!(insns.get(i) instanceof Mark)) return false;
        }
        return true;
    }

    private static int opcodeOf(Insn in) {
        if (in instanceof Op) return ((Op) in).opcode;
        if (in instanceof WideOp) return ((WideOp) in).opcode;
        if (in instanceof Jump) return ((Jump) in).opcode;
        if (in instanceof Switch) return ((Switch) in).table ? TABLESWITCH : LOOKUPSWITCH;
        return -1;                                   // Mark: no bytes
    }

    private static String mergeDepth(int[] depth, java.util.ArrayDeque<Integer> work,
                                     int to, int d) {
        if (to < 0 || to >= depth.length) return "edge to an unbound label";
        if (depth[to] == -1) {
            depth[to] = d;
            work.add(to);
            return null;
        }
        return depth[to] == d ? null : "monitor stack height merge conflict";
    }

    // ---- layout ----------------------------------------------------------

    private void layout() {
        int off = 0;
        for (Insn in : insns) {
            in.layoutAt(off);
            off += in.size();
        }
        codeLength = off;
        for (Label l : labels) {
            if (l.isMarked()) l.offset = insns.get(l.insnIndex).offset;
        }
    }

    /**
     * The one structural limit a front end can actually do something about.
     *
     * Distinct from a plain LimitExceededException so DexConverter can tell "this
     * method is longer than a Code attribute can express, retry the class with
     * MethodOutliner armed" apart from "this class needs more than 65534 constant
     * pool entries", which no amount of outlining fixes. See MethodOutliner.
     */
    public static final class CodeLengthExceeded extends ConstantPool.LimitExceededException {
        private static final long serialVersionUID = 1L;
        /** The code_length that could not be encoded. */
        public final int length;
        CodeLengthExceeded(int length) {
            super("method code_length " + length + " outside 1..65535 (JVMS 4.9.1)");
            this.length = length;
        }
    }

    /**
     * Run the layout fixpoint and report code_length WITHOUT enforcing the
     * 1..65535 limit.
     *
     * MethodOutliner needs two things this provides and nothing else does: the
     * size the method came out at, and (through {@link Label#offset()}) where
     * each DEX offset landed in the byte stream, so it can pick split points by
     * real bytes rather than by an instruction-count guess.
     *
     * Idempotent with respect to the eventual serialization: layout() is a pure
     * function of the instruction list and jump sizes only ever grow, so a
     * writer that is measured and then serialized produces the same bytes as one
     * that is only serialized. It is nonetheless called ONLY on a method that has
     * already overflowed, or that Translator.MONITOR_COVER has just re-planned
     * (to keep a method that fit from being pushed over the limit), so the
     * normal path never pays for it.
     */
    public int measuredLength() {
        boolean grew;
        int guard = 0;
        do {
            layout();
            grew = false;
            for (Jump j : jumps) if (j.grow()) grew = true;
            if (++guard > jumps.size() * 2 + 8) break;
        } while (grew);
        return codeLength;
    }

    private void assemble() {
        for (Label l : labels) {
            if (l.referenced && !l.isMarked()) {
                throw new IllegalStateException("label referenced but never marked"
                        + (l.origin == null ? "" : " (" + l.origin + ")"));
            }
        }
        boolean grew;
        int guard = 0;
        do {
            layout();
            grew = false;
            for (Jump j : jumps) if (j.grow()) grew = true;
            // Each jump can grow at most once, so this cannot spin.
            if (++guard > jumps.size() * 2 + 8) {
                throw new IllegalStateException("branch layout did not converge");
            }
        } while (grew);
        if (codeLength == 0 || codeLength > 65535) {
            throw new CodeLengthExceeded(codeLength);
        }
    }

    /**
     * Depth-first walk of the emitted control flow, taking the deepest operand
     * stack seen at any program point. Every instruction's stack effect was
     * recorded when it was emitted, including the descriptor-dependent ones, so
     * this never has to re-decode the byte stream.
     *
     * The peak for one instruction is max(depthBefore, depthAfter), which
     * covers the dup_x2 / dup2_x2 family whose peak is their exit depth and the
     * invoke family whose peak is their entry depth.
     */
    private int computeMaxStack() {
        int n = insns.size();
        if (n == 0) return 0;

        // A worklist keyed by instruction, NOT a stack of (instruction, depth)
        // pairs. The pair form grew without bound: every pop pushed up to
        // 2 + switch-target entries, and a node was only pruned once its final
        // depth was known, so a large switch inside a loop could enqueue the
        // same nodes over and over. That is what made HCR 1.43 exhaust a 6 GB
        // heap inside Arrays.copyOf here. Carrying the depth in entry[] instead
        // bounds the queue at one slot per instruction.
        int[] entry = new int[n];
        java.util.Arrays.fill(entry, -1);
        boolean[] queued = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0, size = 0;

        entry[0] = 0;
        queued[0] = true;
        queue[tail] = 0; tail = (tail + 1) % n; size++;

        for (Handler h : handlers) {
            // A handler is entered with exactly the thrown exception on the
            // stack (JVMS 4.10.1.6, handlerExceptionClass).
            int hi = h.handler.insnIndex;
            if (hi < 0 || hi >= n || entry[hi] >= 1) continue;
            entry[hi] = 1;
            if (!queued[hi]) {
                queued[hi] = true;
                queue[tail] = hi; tail = (tail + 1) % n; size++;
            }
        }

        int max = 0;
        // Insurance only: depth is a fixed function of position in code this
        // writer emits (every translated instruction is stack neutral at its
        // boundaries), so relaxation converges in O(n). The cap keeps a
        // malformed input from spinning instead of finishing.
        long budget = 64L * n + 1024L;

        while (size > 0 && budget-- > 0) {
            int i = queue[head]; head = (head + 1) % n; size--;
            queued[i] = false;
            int d = entry[i];
            Insn in = insns.get(i);
            int after = d + in.delta;
            if (after < 0) after = 0;   // stay lenient; the verifier is the real judge
            if (d > max) max = d;
            if (after > max) max = after;

            if (in.fallsThrough && i + 1 < n) {
                if (after > entry[i + 1]) {
                    entry[i + 1] = after;
                    if (!queued[i + 1]) {
                        queued[i + 1] = true;
                        queue[tail] = i + 1; tail = (tail + 1) % n; size++;
                    }
                }
            }
            if (in instanceof Jump) {
                int t = ((Jump) in).target.insnIndex;
                if (t >= 0 && t < n && after > entry[t]) {
                    entry[t] = after;
                    if (!queued[t]) {
                        queued[t] = true;
                        queue[tail] = t; tail = (tail + 1) % n; size++;
                    }
                }
            } else if (in instanceof Switch) {
                Switch s = (Switch) in;
                int d0 = s.dflt.insnIndex;
                if (d0 >= 0 && d0 < n && after > entry[d0]) {
                    entry[d0] = after;
                    if (!queued[d0]) {
                        queued[d0] = true;
                        queue[tail] = d0; tail = (tail + 1) % n; size++;
                    }
                }
                for (Label lt : s.targets) {
                    int t = lt.insnIndex;
                    if (t < 0 || t >= n || after <= entry[t]) continue;
                    entry[t] = after;
                    if (!queued[t]) {
                        queued[t] = true;
                        queue[tail] = t; tail = (tail + 1) % n; size++;
                    }
                }
            }
        }
        // Budget exhaustion means the relaxation did NOT reach a fixpoint, so
        // `max` is a lower bound and the emitted max_stack may be too small.
        // That is a silent under-approximation of exactly the shape this file
        // is being audited for, so it is counted; measured 0 across the 33-app
        // corpus (922,492 classes), which is why it is still a count and not a
        // throw. See CodeWriter.maxStackBudgetExhausted.
        if (budget < 0) maxStackGaveUp.incrementAndGet();
        return max;
    }

    /**
     * Offsets that the type-checking verifier demands a frame for: every branch
     * target, every handler_pc, and the instruction following an unconditional
     * transfer of control. The last one is easy to forget and produces
     * "Expecting a stackmap frame at branch target N" on code that looks
     * perfectly reachable, because the type checker has no incoming state for
     * the instruction after a goto/return/athrow/switch.
     *
     * One more, specific to us: when a conditional branch gets widened into
     * "if&lt;!cond&gt; +8; goto_w target", the fall-through position becomes a
     * branch target that did not exist when the caller was emitting. The state
     * there is the caller's own fall-through state, which it cannot in general
     * substitute with the target's, so a caller that wants verifier-clean
     * output should mark a label immediately after every conditional branch and
     * give it a frame. A basic-block oriented front end does that anyway, since
     * a DEX if-* ends a block on both edges.
     */
    private java.util.TreeSet<Integer> requiredFrameOffsets() {
        java.util.TreeSet<Integer> need = new java.util.TreeSet<>();
        for (Insn in : insns) {
            if (in instanceof Jump) {
                Jump j = (Jump) in;
                need.add(j.target.offset);
                if (j.width == 8) need.add(j.offset + 8);
            } else if (in instanceof Switch) {
                Switch s = (Switch) in;
                need.add(s.dflt.offset);
                for (Label t : s.targets) need.add(t.offset);
            }
            if (!in.fallsThrough) {
                int next = in.offset + in.size();
                if (next < codeLength) need.add(next);
            }
        }
        for (Handler h : handlers) need.add(h.handler.offset);
        need.remove(0);   // offset 0 is the implicit initial frame, never written
        return need;
    }

    private byte[] buildStackMapTable() {
        stackMapEmitted = false;
        stackMapDropReason = null;
        frameMaxLocalSlots = 0;
        if (labelFrames.isEmpty() && frameProvider == null) {
            return null;   // caller opted out; the implicit empty map applies
        }
        if (initialFrame == null) {
            stackMapDropReason = "no initial frame supplied";
            droppedTables.incrementAndGet();
            return null;
        }
        Map<Integer, StackMapWriter.Frame> byOffset = new HashMap<>();
        for (Map.Entry<Label, StackMapWriter.Frame> e : labelFrames.entrySet()) {
            byOffset.put(e.getKey().offset, e.getValue());
        }
        StackMapWriter smw = new StackMapWriter(pool);
        smw.setInitialFrame(initialFrame);
        for (int off : requiredFrameOffsets()) {
            StackMapWriter.Frame f = byOffset.get(off);
            if (f == null && frameProvider != null) f = frameProvider.frameAt(off);
            if (f == null) {
                stackMapDropReason = "no frame available at bytecode offset " + off;
                droppedTables.incrementAndGet();
                if (FRAME_DEBUG) {
                    System.err.println("[dex-frame] DROP codeLength=" + codeLength
                            + " handlers=" + handlers.size() + " " + stackMapDropReason);
                }
                return null;
            }
            // JVMS 4.7.4, on both append_frame and full_frame: "It is an error
            // if, for any index i, locals[i] represents a local variable whose
            // index is greater than the maximum number of local variables for
            // the method." HotSpot enforces it on the EXPANDED count
            // (stackMapTable.cpp inserts a second slot for every category-2
            // entry, then calls check_verification_type_array_size against
            // max_locals), so the floor is slotCount, not locals.length.
            // max_locals was previously raised only to cover the INITIAL frame,
            // which is not enough on its own: a frame can legally define a local
            // the initial frame does not have.
            int slots = StackMapWriter.slotCount(f.locals);
            if (slots > frameMaxLocalSlots) frameMaxLocalSlots = slots;
            smw.addFrame(off, f);
        }
        if (smw.size() == 0) return null;   // nothing to say; implicit empty map is correct
        stackMapEmitted = true;
        byte[] body = smw.toAttributeBody();
        if (FRAME_SELFCHECK) {
            String bad = smw.roundTripMismatch(body);
            if (bad != null) {
                throw new IllegalStateException("StackMapTable round trip mismatch: " + bad);
            }
        }
        return body;
    }

    // ---- serialization ---------------------------------------------------

    private static void putAttr(ConstantPool pool, ConstantPool.ByteVector out,
                                String name, byte[] body) {
        out.putU2(pool.utf8(name));
        out.putU4(body.length);
        out.putBytes(body);
    }

    /**
     * Serializes the Code attribute BODY, i.e. everything after
     * attribute_length: max_stack, max_locals, code, exception_table and the
     * nested attributes. ClassFileWriter wraps it.
     *
     * Sub-attribute order follows javac's (LineNumberTable, LocalVariableTable,
     * LocalVariableTypeTable, StackMapTable). Order is not specified by JVMS
     * 4.7.3 and no verifier depends on it, but matching javac keeps javap
     * diffs readable when comparing our output against a reference build.
     */
    public byte[] toCodeAttributeBody() {
        assemble();

        byte[] stackMap = buildStackMapTable();

        ConstantPool.ByteVector code = new ConstantPool.ByteVector(codeLength + 16);
        for (Insn in : insns) in.write(code);

        ConstantPool.ByteVector out = new ConstantPool.ByteVector(codeLength + 128);
        int maxStack = (explicitMaxStack >= 0) ? explicitMaxStack : computeMaxStack();
        int maxLocals = Math.max(explicitMaxLocals, impliedMaxLocals);
        if (initialFrame != null) {
            maxLocals = Math.max(maxLocals, StackMapWriter.slotCount(initialFrame.locals));
        }
        // Every frame's locals must fit too, not just the initial one
        // (JVMS 4.7.4). frameMaxLocalSlots was collected by buildStackMapTable,
        // which is why that runs first.
        //
        // Measured as a no-op today: A/B with this line removed produced
        // byte-identical jars for F-Droid, subway and nowinandroid. That is
        // expected rather than reassuring -- Translator.zeroInitRegisterSlots
        // stores into every allocated slot, so impliedMaxLocals already covers
        // them -- and it is a property of the CALLER, not of this writer. Kept
        // so the invariant belongs to the code that writes the attribute.
        maxLocals = Math.max(maxLocals, frameMaxLocalSlots);
        if (maxLocals > 65535) {
            throw new ConstantPool.LimitExceededException(
                    "method max_locals " + maxLocals + " exceeds 65535 (JVMS 4.11)");
        }
        if (maxStack > 65535) {
            throw new ConstantPool.LimitExceededException(
                    "method max_stack " + maxStack + " exceeds 65535 (JVMS 4.11)");
        }
        out.putU2(maxStack);
        out.putU2(maxLocals);
        out.putU4(codeLength);
        out.putBytes(code.toByteArray());

        // exception_table_length is a u2 (JVMS 4.7.3). Writing more rows than
        // that would silently truncate the count and misparse everything after
        // it, so it is a hard limit like max_locals above. Translator keeps
        // the table under it (see emitTryCatch); this is the backstop.
        if (handlers.size() > 65535) {
            throw new ConstantPool.LimitExceededException("exception_table has "
                    + handlers.size() + " entries, exceeds 65535 (JVMS 4.7.3)");
        }
        out.putU2(handlers.size());
        for (Handler h : handlers) {
            out.putU2(h.start.offset);
            out.putU2(h.end.offset);
            out.putU2(h.handler.offset);
            out.putU2(h.type == null ? 0 : pool.classRef(h.type));
        }

        List<byte[]> attrBodies = new ArrayList<>();
        List<String> attrNames = new ArrayList<>();

        if (!lines.isEmpty()) {
            // JVMS 4.7.12: start_pc must be a VALID INDEX into the code array,
            // so codeLength itself is out of range. A label can legitimately
            // resolve there -- a try range ending at the end of the method
            // binds one past the last instruction -- and R8's shared
            // debug_info_item hands out positions at exactly those addresses,
            // which HotSpot rejects outright at defineClass time with
            // "Invalid pc in LineNumberTable" (323 classes on mindustry).
            // Filtering here rather than in the caller keeps the invariant
            // with the code that writes the attribute.
            List<LineEntry> valid = new ArrayList<>(lines.size());
            for (LineEntry e : lines) {
                if (e.start.isMarked() && e.start.offset >= 0 && e.start.offset < codeLength) {
                    valid.add(e);
                }
            }
            if (!valid.isEmpty()) {
                ConstantPool.ByteVector b = new ConstantPool.ByteVector(2 + valid.size() * 4);
                b.putU2(valid.size());
                for (LineEntry e : valid) {
                    b.putU2(e.start.offset);
                    b.putU2(e.line);
                }
                attrNames.add("LineNumberTable");
                attrBodies.add(b.toByteArray());
            }
        }

        if (!locals.isEmpty()) {
            // JVMS 4.7.13: "The value of the start_pc item must be a valid
            // index into the code array ... The value of start_pc + length must
            // be either a valid index into the code array ... or it must be
            // equal to code_length." An out-of-range pair does not produce a
            // bad debug table, it produces a ClassFormatError at defineClass
            // for the whole class, and length is a u2 so a reversed range would
            // be written as a huge positive number. Checked rather than
            // filtered because nothing emits these today (the DEX front end
            // does not call localVariable), so a violation means a new caller
            // got the contract wrong and should hear about it.
            for (LocalEntry e : locals) {
                int start = e.start.offset;
                int end = e.end.offset;
                if (start < 0 || start >= codeLength || end < start || end > codeLength) {
                    throw new IllegalStateException("LocalVariableTable entry " + e.name
                            + " has range [" + start + ", " + end + ") outside [0, "
                            + codeLength + ") (JVMS 4.7.13)");
                }
            }
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2 + locals.size() * 10);
            b.putU2(locals.size());
            for (LocalEntry e : locals) {
                b.putU2(e.start.offset);
                b.putU2(e.end.offset - e.start.offset);   // this field is a LENGTH, not an end pc
                b.putU2(pool.utf8(e.name));
                b.putU2(pool.utf8(e.desc));
                b.putU2(e.index);
            }
            attrNames.add("LocalVariableTable");
            attrBodies.add(b.toByteArray());

            int generic = 0;
            for (LocalEntry e : locals) if (e.signature != null) generic++;
            if (generic > 0) {
                ConstantPool.ByteVector g = new ConstantPool.ByteVector(2 + generic * 10);
                g.putU2(generic);
                for (LocalEntry e : locals) {
                    if (e.signature == null) continue;
                    g.putU2(e.start.offset);
                    g.putU2(e.end.offset - e.start.offset);
                    g.putU2(pool.utf8(e.name));
                    g.putU2(pool.utf8(e.signature));
                    g.putU2(e.index);
                }
                attrNames.add("LocalVariableTypeTable");
                attrBodies.add(g.toByteArray());
            }
        }

        if (stackMap != null) {
            attrNames.add("StackMapTable");
            attrBodies.add(stackMap);
        }

        out.putU2(attrBodies.size());
        for (int i = 0; i < attrBodies.size(); i++) {
            putAttr(pool, out, attrNames.get(i), attrBodies.get(i));
        }
        return out.toByteArray();
    }
}
