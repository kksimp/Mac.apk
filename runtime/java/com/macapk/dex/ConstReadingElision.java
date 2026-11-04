// ConstReadingElision -- stop materialising the readings of a Dalvik constant
// that nothing can observe before the register is overwritten.
//
// THE PROBLEM
// A Dalvik register is untyped, so `const/16 v2, 4543` is an int AND a float,
// and `const-wide v0, 0x8162...` is a long AND a double, until something reads
// it. Translator maps (register, scalar) to its own JVM local and, per
// TypeInference's Def.scalars, stores the constant once PER READING (see the
// header of Translator). That is what makes the output verify, and for an
// ordinary method it costs a few bytes. For a method that is nothing but
// constants it is ruinous:
//
//   - every reading that is not 0/1/2 (float) or 0/1 (long, double) or a
//     short (int) needs its OWN constant-pool entry, and a long or double
//     takes TWO slots (JVMS 4.4.5). Measured on WhatsApp 2.26.41.71's X/0Ec, a
//     22,452-entry long[] table filled by 21 straight-line methods: Long
//     12,455 + Double 12,454 + Float 12,455 + Integer 3,253 entries when the
//     65534-entry pool (JVMS 4.1, constant_pool_count is a u2) ran out --
//     half of them double/float readings no instruction ever loads. The whole
//     class was dropped ("constant pool full") and WhatsApp's worker thread
//     died on NoClassDefFoundError: X/0Ec at every launch.
//   - every dead reading also costs code bytes (an ldc + a store), which is
//     what pushes some methods over the 65535-byte code_length cap.
//
// THE RULE (all of it checked, none of it assumed)
// The store of reading S of `const vR` at instruction I is dropped only when
// the scan forward from I, in straight-line fall-through order, reaches a
// KILLER before anything that could observe the (vR, S) local:
//
//   killer      a const / const-wide of EXACTLY vR, the same width, whose own
//               Def materialises S. It overwrites the very local the dropped
//               store would have written, so the local's content at the
//               killer and after it is the same with or without the store.
//               (If the killer is itself elided, its own killer applies, and
//               so on down a finite chain that ends in a REAL store, with no
//               observation point anywhere along it.)
//   observer    anything that may read the local or snapshot it:
//                 * an instruction whose TypeInference uses read (vR, S)
//                   (Translator only ever loads a register local through
//                   pushUse* from it.uses, except emitMove, handled next),
//                 * a move whose source overlaps vR (emitMove copies every
//                   reading), and any use of vR-1 / vR+1 (wide overlap),
//                 * a StackMapTable frame: a branch, switch or handler target,
//                 * an exception edge: any instruction overlapping a DEX try
//                   range, or a MonitorCover release-stub site,
//                 * a MethodOutliner chunk boundary or MethodSplitter part
//                   start, where live locals are passed as arguments,
//                 * leaving the block (if, goto, switch, return, throw), an
//                   unreachable instruction, or any family not audited below.
//   any other WRITE of vR (or an overlapping pair) also ends the scan with
//   the reading kept, as does the end of the window.
//
// Two rules about the constant ITSELF keep the frames honest: no elision at
// a const that is a frame point (branch, switch or handler target), and never
// ALL of a const's readings. A const that emits no code shares its bytecode
// offset with the next instruction, and Translator's betterFrameSource then
// publishes the LATER offset's state (after the const) for both -- a
// VerifyError, caught under DEX2JVM_CONST_ELISION_FORCE on X/58C.A00.
//
// Since nothing can observe the local between the dropped store and the
// killer's store, and the killer's store leaves the local exactly as it was in
// the unelided translation, every observation point sees the same value in
// both translations. The verifier sees the same type too: every primitive
// local is zero-initialised by the prologue (zeroInitRegisterSlots) and only
// ever stores its own scalar.
//
// WHEN IT RUNS
// Only on DexConverter's rescue retries -- a class whose constant pool
// overflowed, or one with a method over code_length -- armed per thread with
// arm(). A class that already converts is never re-translated, so its bytes
// are unchanged. DEX2JVM_CONST_ELISION=0 disables it for A/B;
// DEX2JVM_CONST_ELISION_FORCE=1 arms it everywhere, for a test gate.

package com.macapk.dex;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

final class ConstReadingElision {

    private ConstReadingElision() {}

    static final boolean ENABLED = !"0".equals(System.getenv("DEX2JVM_CONST_ELISION"));

    /** How far past the constant to look for its killer, in instructions. */
    private static final int WINDOW = 64;

    private static final ThreadLocal<Boolean> ARMED = ThreadLocal.withInitial(() -> false);

    /** Arms (or disarms) elision for translations on THIS thread. Returns the
     *  previous state so a caller can restore it in a finally. */
    static boolean arm(boolean on) {
        boolean was = ARMED.get();
        ARMED.set(on && ENABLED);
        return was;
    }

    /**
     * Test knob, DEX2JVM_CONST_ELISION_FORCE=1: arm elision for EVERY class,
     * not only the rescue retries, so a gate can run it over a whole app and
     * diff verification and behaviour against the unelided output. Same spirit
     * as DEX2JVM_SPLIT_FORCE / DEX2JVM_OUTLINE_FORCE. Unset normally.
     */
    static final boolean FORCE = ENABLED
            && "1".equals(System.getenv("DEX2JVM_CONST_ELISION_FORCE"));

    static boolean armed() { return FORCE || ARMED.get(); }

    /** Readings elided by this thread's translations, for diagnostics. */
    static final java.util.concurrent.atomic.AtomicLong elided =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * For every const / const-wide in {@code norm} that has at least one dead
     * reading: DEX offset -> the mask of DexType scalars whose store may be
     * dropped. Offsets with nothing dead are absent.
     *
     * @param leaders   every offset that is the target of a non-fall-through
     *                  edge: branch, switch and handler targets
     * @param barriers  further observation points (outline chunk bounds,
     *                  split part starts, monitor-cover stub sites)
     * @param decoded   the method's decoded instructions (Translator.decoded)
     */
    static Map<Integer, Integer> plan(List<TypeInference.Insn> norm,
                                      TypeInference.Result types,
                                      List<TypeInference.TryBlock> tries,
                                      Set<Integer> leaders, Set<Integer> barriers,
                                      Instruction[] decoded) {
        Map<Integer, Integer> out = new HashMap<>();
        Map<Integer, Instruction> byOffset = new HashMap<>(decoded.length * 2);
        for (Instruction i : decoded) if (!i.isPayload()) byOffset.put(i.address(), i);
        IntFunction<Instruction> rawAt = byOffset::get;
        BitSet inTry = new BitSet();
        for (TypeInference.TryBlock t : tries) {
            if (t.endOffset > t.startOffset) inTry.set(t.startOffset, t.endOffset);
        }
        int n = norm.size();
        for (int idx = 0; idx < n; idx++) {
            TypeInference.Insn c = norm.get(idx);
            int fam = TypeInference.familyOf(c.opcode);
            if (fam != TypeInference.F_CONST32 && fam != TypeInference.F_CONST64) continue;
            if (!types.isReachable(c.offset) || covered(inTry, c) || barriers.contains(c.offset)) {
                continue;
            }
            // Never at a frame point. A const whose every reading is dropped
            // emits NO code, so its label lands on the next instruction's
            // bytecode offset, and the frame published there may then be the
            // state AFTER the const instead of the merge BEFORE it. Measured
            // under DEX2JVM_CONST_ELISION_FORCE on WhatsApp's X/58C.A00:
            // `if-eqz v0, :L ... :L const/4 v0, 0` gave a frame saying v0 is
            // null where the other edge arrives with an X/0EQ ("Type 'X/0EQ'
            // ... is not assignable to null (stack map)").
            if (leaders.contains(c.offset)) continue;
            TypeInference.InsnTypes it = types.typesFor(c.offset);
            if (it == null) continue;
            int reg = c.arg(0);
            boolean wide = fam == TypeInference.F_CONST64;
            int readings = readingsOf(it, reg);
            if (readings == 0) continue;
            int dead = scan(norm, idx, reg, wide, readings, types, inTry, leaders, barriers,
                            rawAt);
            // ...and never ALL of them, for the same reason from the other side:
            // the JVM wants frames at points the DEX does not call targets (the
            // fall-through of a switch is its default target, a widened if
            // gets a goto_w), and a const that emitted nothing there would hand
            // its label's frame to the next instruction. One store keeps the
            // const's own bytecode offset, whose frame is its entry state.
            if ((dead & readings) == readings) dead &= ~Integer.lowestOneBit(readings);
            if (dead != 0) out.put(c.offset, dead);
        }
        return out;
    }

    private static int scan(List<TypeInference.Insn> norm, int idx, int reg, boolean wide,
                            int readings, TypeInference.Result types, BitSet inTry,
                            Set<Integer> leaders, Set<Integer> barriers,
                            IntFunction<Instruction> rawAt) {
        int pending = readings;
        int end = Math.min(norm.size(), idx + 1 + WINDOW);
        for (int k = idx + 1; k < end; k++) {
            TypeInference.Insn ki = norm.get(k);
            int off = ki.offset;
            if (leaders.contains(off) || barriers.contains(off) || covered(inTry, ki)
                    || !types.isReachable(off)) {
                return 0;
            }
            TypeInference.InsnTypes kt = types.typesFor(off);
            if (kt == null) return 0;
            int kf = TypeInference.familyOf(ki.opcode);

            // A const writes, never reads: is it the killer?
            if (kf == TypeInference.F_CONST32 || kf == TypeInference.F_CONST64) {
                int dest = ki.arg(0);
                boolean kw = kf == TypeInference.F_CONST64;
                if (dest == reg && kw == wide) {
                    return pending & readingsOf(kt, reg);
                }
                if (overlaps(dest, kw, reg, wide)) return 0;
                continue;
            }
            if (!audited(kf)) return 0;

            // READS. Translator loads a register local only through pushUse*
            // from it.uses -- emitMove is the exception, and copies EVERY
            // reading of its source.
            if ((kf == TypeInference.F_MOVE || kf == TypeInference.F_MOVE_WIDE)
                    && overlaps(ki.arg(1), kf == TypeInference.F_MOVE_WIDE, reg, wide)) {
                return 0;
            }
            for (TypeInference.Use u : kt.uses) {
                if (u.register == reg) {
                    if (!u.definitelyNull) pending &= ~u.scalar;
                } else if (overlaps(u.register, DexType.isWide(u.scalar), reg, wide)) {
                    return 0;
                }
            }
            // Belt and braces: a register the instruction names syntactically
            // that no use or def accounts for is an unknown read of everything.
            Instruction raw = rawAt.apply(off);
            if (raw == null) return 0;
            for (int r = reg - 1; r <= reg + (wide ? 1 : 0) + 1; r++) {
                if (r >= 0 && mentions(raw, r) && !named(kt, r)) return 0;
            }
            if (pending == 0) return 0;

            // WRITES other than a killer: keep what is still pending.
            for (TypeInference.Def d : kt.defs) {
                if (overlaps(d.register, d.wide, reg, wide)) return 0;
            }
            // Leaving the block: the successor's frame observes the local.
            switch (kf) {
                case TypeInference.F_IF: case TypeInference.F_IFZ: case TypeInference.F_GOTO:
                case TypeInference.F_SWITCH: case TypeInference.F_RETURN:
                case TypeInference.F_THROW:
                    return 0;
                default:
                    break;
            }
        }
        return 0;
    }

    /** Whether some use or def of the instruction covers register r. */
    private static boolean named(TypeInference.InsnTypes kt, int r) {
        for (TypeInference.Use u : kt.uses) {
            if (overlaps(u.register, DexType.isWide(u.scalar), r, false)) return true;
        }
        for (TypeInference.Def d : kt.defs) {
            if (overlaps(d.register, d.wide, r, false)) return true;
        }
        return false;
    }

    /** Whether the DEX instruction names register r in any operand position,
     *  including the high half of a register pair. By format (Dalvik bytecode
     *  spec, "Instruction formats"): an index or literal is never a register. */
    static boolean mentions(Instruction in, int r) {
        switch (in.format()) {
            case F35C: case F3RC: case F45CC: case F4RCC:
                for (int x : in.argRegisters()) if (x == r) return true;
                return false;
            case F12X: case F22X: case F32X: case F22B: case F22T: case F22S: case F22C:
                return pair(in.a(), in.isWideA(), r) || pair(in.b(), in.isWideB(), r);
            case F23X:
                return pair(in.a(), in.isWideA(), r) || pair(in.b(), in.isWideB(), r)
                        || pair(in.c(), in.isWideC(), r);
            case F11N: case F11X: case F21T: case F21S: case F21H: case F21C:
            case F31I: case F31T: case F31C: case F51L:
                return pair(in.a(), in.isWideA(), r);
            default:
                return false;          // 10x, 10t, 20t, 30t: no register operand
        }
    }

    private static boolean pair(int base, boolean wide, int r) {
        return base == r || (wide && base + 1 == r);
    }

    /** Families whose emitter reads registers only through it.uses (or, for
     *  moves, as checked explicitly) and writes them only through it.defs. */
    private static boolean audited(int family) {
        switch (family) {
            case TypeInference.F_NOP:
            case TypeInference.F_MOVE: case TypeInference.F_MOVE_WIDE:
            case TypeInference.F_MOVE_RESULT:
            case TypeInference.F_CONST_STRING: case TypeInference.F_CONST_CLASS:
            case TypeInference.F_CHECK_CAST: case TypeInference.F_INSTANCE_OF:
            case TypeInference.F_ARRAY_LEN: case TypeInference.F_NEW_INSTANCE:
            case TypeInference.F_NEW_ARRAY: case TypeInference.F_FILLED_NEW_ARRAY:
            case TypeInference.F_CMP:
            case TypeInference.F_ARRAY_GET: case TypeInference.F_ARRAY_PUT:
            case TypeInference.F_INSTANCE_GET: case TypeInference.F_INSTANCE_PUT:
            case TypeInference.F_STATIC_GET: case TypeInference.F_STATIC_PUT:
            case TypeInference.F_INVOKE_VIRTUAL: case TypeInference.F_INVOKE_SUPER:
            case TypeInference.F_INVOKE_DIRECT: case TypeInference.F_INVOKE_STATIC:
            case TypeInference.F_INVOKE_INTERFACE:
            case TypeInference.F_UNARY_OP: case TypeInference.F_BINARY_OP:
            case TypeInference.F_BINARY_OP_CONST:
            // Control transfers: their reads are uses too; the scan stops after them.
            case TypeInference.F_IF: case TypeInference.F_IFZ: case TypeInference.F_GOTO:
            case TypeInference.F_SWITCH: case TypeInference.F_RETURN: case TypeInference.F_THROW:
                return true;
            default:
                return false;
        }
    }

    private static int readingsOf(TypeInference.InsnTypes it, int reg) {
        int r = 0;
        for (TypeInference.Def d : it.defs) if (d.register == reg) r |= d.scalars;
        return r;
    }

    /** Any code unit of the instruction inside a DEX try range. */
    private static boolean covered(BitSet inTry, TypeInference.Insn in) {
        int next = inTry.nextSetBit(in.offset);
        return next >= 0 && next < Math.max(in.nextOffset, in.offset + 1);
    }

    /** Whether register a (a pair a,a+1 when wide) shares a register with b. */
    static boolean overlaps(int a, boolean aWide, int b, boolean bWide) {
        int aHi = aWide ? a + 1 : a, bHi = bWide ? b + 1 : b;
        return a <= bHi && b <= aHi;
    }
}
