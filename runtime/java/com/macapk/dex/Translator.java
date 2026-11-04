// Translator -- the Dalvik-to-JVM translation pass: the seam between the DEX
// side (DexFile / InstructionDecoder / TypeInference) and the class-file side
// (ClassFileWriter / CodeWriter / StackMapWriter).
//
// THE SHAPE OF THE PROBLEM
// Dalvik is a REGISTER machine: every instruction names its operands and its
// destination. The JVM is a STACK machine: operands are pushed, the op consumes
// them, the result is stored. So each Dalvik instruction becomes a short,
// stack-neutral JVM sequence:
//
//     push each operand from its local  ->  emit the JVM op  ->  store the result
//
// "Stack-neutral" is load-bearing. Because every Dalvik instruction begins and
// ends with an empty JVM operand stack, a StackMapTable frame at any DEX
// instruction boundary has an empty stack, and the only interesting part of the
// frame is the locals array. That is what makes frame emission tractable here
// (see frameAt below); an exception handler is the single exception, entering
// with exactly the thrown object on the stack.
//
// THE REGISTER-TO-LOCAL MAPPING (the part that is not obvious)
// A Dalvik register is UNTYPED: v3 may hold an int here and an object there,
// and `const v3, 0` produces a value that is legally an int, a float, AND a
// null reference until something consumes it. The JVM verifier will not accept
// that: a local has one type at each point, and `istore` followed by `aload` of
// the same slot is a verify error.
//
// So the mapping is not register -> slot but (REGISTER, SCALAR) -> SLOT. v3
// read as an int and v3 read as a reference are two different JVM locals.
// TypeInference has already done the hard half: it hands back, per instruction,
// a Use[] whose entries are resolved to ONE scalar each, and a Def[] whose
// `scalars` may carry several bits, meaning "materialise this value in each of
// these representations". A `const v3, 0` therefore stores three times: into
// v3's int slot, its float slot, and its object slot. That costs a few bytes of
// bytecode and buys verifiability.
//
// Parameters are handled separately because the JVM dictates their slots: on
// entry locals 0..argSlots-1 ARE the arguments, in order, with `this` at 0.
// Dalvik instead puts parameters in the LAST registers. A prologue copies each
// incoming parameter into the register slot(s) the body expects, after which
// the parameter slots are never written again -- which is what lets frameAt
// report them from the descriptor instead of tracking them.
//
// WHAT IS DELIBERATELY NOT HERE
// Class-level assembly (fields, the constant pool, attributes) is DexConverter's
// job; this class only fills in one method's Code attribute.

package com.macapk.dex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class Translator {

    private Translator() {}

    /**
     * DEX2JVM_NARROW_TRY -- cover only the THROWING instructions of a DEX try
     * range in the JVM exception table (default ON; =0 restores the previous
     * output byte for byte: one exception_table row per catch spanning the whole
     * DEX range, frames computed under the JVM edge rule).
     *
     * THE MISMATCH THIS REMOVES. A DEX try_item names a contiguous range, but
     * ART routes an exception to its handler only from an instruction that can
     * actually throw: the catch lookup is keyed on the dex_pc of the throwing
     * instruction, and ART's verifier gives a handler edge only to instructions
     * flagged kThrow (art/libdexfile/dex/dex_instruction_list.h; our
     * Opcodes.canThrow and TypeInference.canThrow are that exact 84-opcode set,
     * checked mechanically on 2026-09-26). JVMS 4.10.1.6 instead applies
     * instructionSatisfiesHandlers to EVERY instruction whose offset lies in an
     * exception_table row, throwing or not, and HotSpot implements exactly that
     * (verifier.cpp verify_exception_handler_targets: `if (bci >= start_pc &&
     * bci < end_pc)`). Copying the DEX range verbatim therefore made the handler
     * frame answer for program points ART never connects to it, and some of
     * those points hold a slot the verifier types `top` -- a register reused
     * after an uninitialized `new` merged into it, or the handler's own
     * `move-object`s when R8 places a handler inside the range it guards. The
     * handler frame then had to say `top`, and the handler body, which reads the
     * register because ART proved it is a reference on every THROWING path,
     * failed the split verifier:
     *
     *   org/joinmastodon/android/api/f.run()V @2402: aload
     *   Type top (current frame, locals[18]) is not assignable to reference type
     *
     * That shape was the largest G8 family left (24 of 62 split-verifier
     * errors across the 62-app corpus, 2026-09-26), plus the protected-receiver
     * failures whose receiver had been widened the same way (kotlinx
     * SharedFlowImpl.collect$suspendImpl).
     *
     * THE FIX. Emit one row per maximal run of reachable throwing instructions
     * inside the DEX range, and analyse under the ART edge rule. The set of
     * program points that can reach a handler is then IDENTICAL on both sides,
     * which is also the most faithful reading of the DEX: a non-throwing
     * instruction cannot raise anything on ART, and its JVM expansion (loads,
     * stores, gotos, arithmetic that cannot trap) cannot raise anything on the
     * JVM either. Rows only multiply where a range interleaves throwing and
     * non-throwing code. Measured cost over the 62-app corpus: +0.31% jar
     * bytes as first landed (max +1.44% on one app), +0.24% with
     * MONITOR_SAFE_TRY, all of it in exception_table rows. The one exception
     * is a catch-all clause in a method that takes a monitor, which HotSpot's
     * JIT needs whole; see MONITOR_SAFE_TRY.
     *
     * Read once, at class initialisation: a JVM's environment is fixed for its
     * lifetime. NOTE a cache of converted output that is keyed on the
     * converter's code identity rather than on this flag has to be cleared
     * between the arms of an A/B comparison.
     */
    static final boolean NARROW_TRY = !"0".equals(System.getenv("DEX2JVM_NARROW_TRY"));

    /**
     * DEX2JVM_MONITOR_SAFE_TRY -- under NARROW_TRY, a method that contains a
     * monitor-enter keeps each CATCH-ALL clause over its whole DEX range, and its
     * handler takes edges from every instruction in that range (default ON; =0
     * narrows catch-all clauses in those methods too, the NARROW_TRY output as
     * first landed; irrelevant when NARROW_TRY=0, which is whole-range anyway).
     *
     * WHAT NARROWING BROKE, AND WHY NO VERIFIER SAW IT. C1 and C2 compile a
     * method with monitorenter only if GenerateOopMap proves its monitors
     * balanced (ciMethod::has_balanced_monitors, run as GeneratePairingInfo).
     * Its exception rule (generateOopMap.cpp do_exception_edge, jdk21u) takes
     * every bytecode that Bytecodes::can_trap -- and bytecodes.cpp marks ldc,
     * ldc_w, ldc2_w and checkcast can_trap along with the obvious ones -- walks
     * the exception_table rows covering it, stops at the first catch_type 0 row,
     * and if there is none while a lock is held, sets _monitor_safe = false
     * ("non-empty monitor stack at exceptional exit"). The compile is then
     * skipped at every tier ("not compilable (unbalanced monitors)") and the
     * method stays interpreted for the life of the process.
     *
     * The emitter produces exactly those bytecodes for NON-throwing DEX
     * instructions: `ldc` for a `const`/`const-wide` or float literal that no
     * short form encodes, `checkcast` where a use needs a narrower type than
     * the frame proves. D8's catch-all around a synchronized body covered them,
     * and NARROW_TRY dropped them from it on purpose, so the output still ran
     * and verified while the JIT gave up on the method. Measured with
     * a standalone replica of that rule (CodeWriter.monitorImbalance is the
     * in-converter copy), methods that cannot be compiled across the 62-app
     * corpus: 8,915 -> 31,477 of 107,848 with a monitor (mastodon 16 -> 73 of
     * 188); with this flag, 8,915 again, the SAME methods in every app, with G8
     * unchanged. With this flag off, HotSpot itself logs "Monitor mismatch" and
     * "COMPILE SKIPPED ... (unbalanced monitors)" for such a method.
     *
     * THE FIX. Only catch_type 0 rows count to that rule, and typed rows do
     * not, so only the catch-all needs the whole range back. Typed catches stay
     * narrowed -- that is where the handler-reads-`top` G8 family lives -- and
     * the catch-all handler's frame is computed from every instruction in its
     * range (TypeInference.MethodInput.catchAllWideEdges), which is the JVM rule
     * HotSpot's verifier applies to that row. Keyed on the METHOD because a
     * nested try/finally inside a synchronized block needs its own catch-all
     * whole too, and GenerateOopMap's verdict is per method anyway.
     *
     * If that wider catch-all frame leaves a register a catch-all handler reads
     * undefined (the old wide-rule failure), the method falls back to the full
     * narrow output: convertible and verifiable beats compilable.
     */
    static final boolean MONITOR_SAFE_TRY =
            !"0".equals(System.getenv("DEX2JVM_MONITOR_SAFE_TRY"));

    /**
     * exception_table_length is a u2 (JVMS 4.7.3); see emitTryCatch for what
     * happens to a method whose narrowed table would not fit.
     *
     * DEX2JVM_EXCEPTION_ROWS_LIMIT is a TEST KNOB that can only LOWER it, so
     * the fallback can be exercised on a small method (a handful of try
     * blocks and a limit below their row count): the real limit needs a 68000-row input, which d8 takes about two minutes to
     * build. Measured with that input (2026-09-26): without the fallback the
     * class fails to translate (CodeWriter's u2 backstop), and without either
     * the count wraps and HotSpot dies with a fatal error when bytecode
     * verification is off.
     */
    static final int MAX_EXCEPTION_ROWS = exceptionRowsLimit();

    private static int exceptionRowsLimit() {
        String v = System.getenv("DEX2JVM_EXCEPTION_ROWS_LIMIT");
        if (v == null || v.isEmpty()) return 65535;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 && n < 65535 ? n : 65535;
        } catch (NumberFormatException e) {
            return 65535;
        }
    }

    /**
     * DEX2JVM_NULL_AGET -- an aget on a PROVABLY NULL array stores a typed
     * zero into each slot of its def instead of `dup`ing the loaded value into
     * all of them (default ON; =0 restores the previous bytes). See the
     * F_ARRAY_GET case in emitOne. Obfuscators (Moat in Hill Climb Racing
     * 1.43) plant such loads behind opaque predicates; they always throw, so only verifiability
     * changes.
     */
    static final boolean NULL_AGET = !"0".equals(System.getenv("DEX2JVM_NULL_AGET"));

    /** Raised when one method cannot be translated. DexConverter catches this
     *  per method so a single bad method cannot lose the whole class. */
    public static final class TranslationException extends RuntimeException {
        public TranslationException(String msg) { super(msg); }
        public TranslationException(String msg, Throwable cause) { super(msg, cause); }
    }

    // ==================================================================
    // Entry point
    // ==================================================================

    /**
     * Translate one method's DEX code into {@code mw}'s Code attribute.
     * Does nothing for abstract/native methods, which have no code item.
     */
    public static void translate(DexMethod method, ClassFileWriter.MethodWriter mw) {
        translate(method, mw, null);
    }

    /**
     * @param hierarchy optional reference least-upper-bound oracle. Null keeps the
     *   old behaviour (every unequal reference merge widens to java/lang/Object),
     *   which is sound but imprecise enough that HotSpot's split verifier rejects
     *   the result -- see ClassHierarchyOracle for the measured numbers.
     */
    public static void translate(DexMethod method, ClassFileWriter.MethodWriter mw,
                                 DexType.ClassHierarchy hierarchy) {
        translate(method, mw, hierarchy, InitRepointPlan.NONE);
    }

    /**
     * @param repoints which constructor call sites may be re-pointed at the
     *   forwarding constructor R8 deleted. See InitRepointPlan.
     */
    static void translate(DexMethod method, ClassFileWriter.MethodWriter mw,
                          DexType.ClassHierarchy hierarchy, InitRepointPlan repoints) {
        translate(method, mw, hierarchy, repoints, SuperInterfacePlan.NONE);
    }

    /**
     * @param superIfaces which `invoke-super &lt;interface&gt;` sites must name a
     *   DIRECT superinterface instead. See SuperInterfacePlan.
     */
    static void translate(DexMethod method, ClassFileWriter.MethodWriter mw,
                          DexType.ClassHierarchy hierarchy, InitRepointPlan repoints,
                          SuperInterfacePlan superIfaces) {
        translate(method, mw, hierarchy, repoints, superIfaces, false);
    }

    /**
     * @param allowOutlining retry an oversized method with MethodOutliner rather
     *   than letting CodeLengthExceeded take the whole class down. Set only by
     *   DexConverter's retry, i.e. only for a class that has ALREADY failed, so
     *   a method that fits is never re-translated and its bytes cannot change.
     */
    static void translate(DexMethod method, ClassFileWriter.MethodWriter mw,
                          DexType.ClassHierarchy hierarchy, InitRepointPlan repoints,
                          SuperInterfacePlan superIfaces, boolean allowOutlining) {
        DexCode code = method.code();
        if (code == null) return;
        MethodTranslator done = translateOnce(method, code, mw, hierarchy, repoints,
                                              superIfaces, null, null);
        if (!allowOutlining) return;

        // MethodOutliner first: it is the older, narrower transform, it leaves
        // the method's own control flow untouched, and where it applies it is
        // strictly cheaper than a chain. MethodSplitter is the fallback for the
        // shape it cannot take -- a branchy method, or one with try blocks.
        // With the splitter's test knob on, go straight to the splitter so the
        // semantic test suite exercises IT rather than the outliner.
        if (!MethodSplitter.forced()) {
            MethodOutliner.Plan plan = done.planOutlining();
            if (plan != null) {
                // Committed only if the outlined translation survives: a failure
                // here must leave the method exactly as the normal path produced
                // it, with no half-added synthetic methods on the class.
                try {
                    mw.newCode();
                    MethodTranslator outlined = translateOnce(method, code, mw, hierarchy,
                            repoints, superIfaces, plan, null);
                    outlined.commitSyntheticMethods();
                    return;
                } catch (RuntimeException e) {
                    mw.newCode();
                    done = translateOnce(method, code, mw, hierarchy, repoints, superIfaces,
                                         null, null);
                }
            }
        }

        // Retry on a SHRINKING per-part byte budget. A part that still
        // overflows is reported by commitSyntheticMethods, not discovered at
        // serialization time, so an attempt never leaves a half-built class
        // behind -- and the next budget is computed from the length that broke
        // rather than picked off a ladder, because the fixed per-part overhead
        // does not scale with the budget.
        //
        // `done` is reused across attempts on purpose: a split attempt builds
        // its own MethodTranslator against a fresh CodeWriter, so the measured
        // translation it plans from is untouched. Only the final failure has to
        // put an unsplit body back on the method.
        //
        // The ladder runs WITHOUT the <clinit> static-field spill first, so any
        // method a parameter-only plan can place comes out exactly as it did
        // before the spill existed. Only when that ladder fails and at least
        // one of its refusals was the argument-slot ceiling -- the one refusal
        // the spill changes; without one the spill ladder would retrace the same
        // failing sequence step for step -- does an eligible <clinit> get a
        // second ladder with the spill on (MethodSplitter.CLINIT_SPILL).
        SplitLadder ladder = new SplitLadder();
        if (ladder.run(method, code, mw, hierarchy, repoints, superIfaces, done, false)) return;
        if (ladder.sawSlotWall && done.spillEligible()
                && ladder.run(method, code, mw, hierarchy, repoints, superIfaces, done, true)) {
            MethodSplitter.Plan p = ladder.committed;
            if (p != null && p.instanceSpill()) {
                // The one line a constructor rescue leaves (MethodSplitter,
                // THE <init> SPILL), so a run that depends on it says so.
                ctorSpills.incrementAndGet();
                System.err.println("[" + Options.logTag + "] split oversize constructor "
                    + method.declaringClass().name() + "." + method.name() + method.descriptor()
                    + " into " + p.parts().size() + " parts, " + p.spillFields().size()
                    + " values through instance fields (DEX2JVM_CTOR_RESCUE=0 turns this off)");
            }
            return;
        }
        // Nothing worked: leave the method exactly as the unsplit path produced
        // it, which is the state DexConverter's caller expects to fail on.
        if (ladder.placed) {
            mw.newCode();
            translateOnce(method, code, mw, hierarchy, repoints, superIfaces, null, null);
        }
    }

    /** Constructors split through instance spill fields, process wide. */
    static final java.util.concurrent.atomic.AtomicInteger ctorSpills =
            new java.util.concurrent.atomic.AtomicInteger();

    /** One MethodSplitter retry ladder over a method whose unsplit translation
     *  overflowed. {@link #run} tries successively smaller per-part budgets and
     *  returns true once a chain translates and commits. The object carries two
     *  facts across a second run: whether any attempt overwrote the method's
     *  code (so the caller must restore the unsplit body), and whether any
     *  refusal was the JVMS 4.3.3 argument-slot ceiling. */
    private static final class SplitLadder {
        /** Some attempt emitted code into the method, so a final failure must
         *  re-translate the unsplit body. */
        boolean placed;
        /** The plan that translated and committed, once {@link #run} returned
         *  true. */
        MethodSplitter.Plan committed;
        /** Some attempt was refused because a part needed more than 255
         *  argument slots: the one refusal the &lt;clinit&gt; spill changes. */
        boolean sawSlotWall;

        boolean run(DexMethod method, DexCode code, ClassFileWriter.MethodWriter mw,
                    DexType.ClassHierarchy hierarchy, InitRepointPlan repoints,
                    SuperInterfacePlan superIfaces, MethodTranslator done, boolean spill) {
            int prevSlotDemand = 0;
            int target = MethodSplitter.initialTarget();
            for (int attempt = 0; attempt < 8 && target >= MethodSplitter.MIN_TARGET; attempt++) {
                // Only the first attempt may let a part grow past `target` to reach
                // a legal cut. Every later attempt is here BECAUSE a part overflowed,
                // so widening back to the ceiling would re-plan the same too-big part.
                MethodSplitter.Plan chain = done.planSplitting(target, attempt == 0, spill);
                if (chain == null) {
                    int demand = MethodSplitter.lastSlotDemand();
                    if (demand > 0) sawSlotWall = true;
                    // Not every refusal is final. A STRUCTURAL one ("no valid cut
                    // within N bytes", "more than 64 parts") only gets worse as the
                    // budget falls, so stop. But a refusal that is a property of
                    // WHERE THE CUTS LANDED -- the JVMS 4.3.3 argument-slot ceiling
                    // above all, since a part's descriptor is the union of what is
                    // live at ITS entry points -- may well succeed with different
                    // boundaries. This loop used to treat both alike and threw away
                    // methods a second plan would have accepted.
                    if (!MethodSplitter.lastRefusalWasBudgetDependent()) break;
                    // Stop when the retries are chasing an ASYMPTOTE rather than
                    // closing a gap. The tightest boundary's live set is a property
                    // of the METHOD, so below some width no budget reaches it and
                    // each further attempt costs a full re-plan for nothing.
                    // Measured on Instagram's X/11D.<clinit>: 910, 731, 590, 475,
                    // 384, 310, 271, 270, 268 slots -- the last 20% budget cut
                    // bought ONE slot.
                    if (demand > 0) {
                        if (prevSlotDemand > 0 && demand >= prevSlotDemand * 49 / 50) break;
                        prevSlotDemand = demand;
                    }
                    // Deliberately NOT `placed = true`: planSplitting only PLANS, so
                    // a refusal has emitted nothing and the unsplit body `done`
                    // produced is still intact. Marking it placed would force a
                    // pointless re-translation of a body that was never disturbed.
                    int next = MethodSplitter.shrink(target, MethodSplitter.codeLimit());
                    if (next >= target) break;          // shrink() must make progress
                    target = next;
                    continue;
                }
                try {
                    mw.newCode();
                    MethodTranslator chained = translateOnce(method, code, mw, hierarchy,
                            repoints, superIfaces, null, chain);
                    chained.commitSyntheticMethods();
                    committed = chain;
                    return true;
                } catch (CodeWriter.CodeLengthExceeded e) {
                    if (MethodSplitter.debug()) {
                        System.err.println("[dex-split] target " + target + " -> " + e.length
                                + " bytes, retrying");
                    }
                    placed = true;
                    target = MethodSplitter.shrink(target, e.length);
                } catch (RuntimeException e) {
                    if (MethodSplitter.debug()) {
                        System.err.println("[dex-split] target " + target + " failed: " + e);
                    }
                    placed = true;
                    break;                     // not a size problem; a reshape will not fix it
                }
            }
            return false;
        }
    }

    /** Replace a method's body with {@code throw new UnsupportedOperationException(msg)}.
     *
     *  The last-resort path for a method that cannot be expressed in class-file
     *  form at all: Dalvik has no per-method code limit and the JVM caps
     *  code_length at 65535 (JVMS 4.9.1), so a method can be perfectly valid on
     *  ART and inexpressible here, and BOTH rescue transforms can legitimately
     *  refuse it (MethodSplitter will not cut inside a try range or a monitor
     *  window, so one big try or one big lock leaves it no legal cut;
     *  MethodOutliner will not outline across a try range).
     *
     *  Before this existed the whole CLASS was dropped, and dropped SILENTLY --
     *  one stderr line at convert time and nothing at run time, so the app hit
     *  NoClassDefFoundError for a type its own dex defines. Measured on
     *  Instagram: seven classes lost that way, one of them (X/9Pu) on the Bloks
     *  render path, so nothing rendered and the only symptom was a missing
     *  type far from the cause (see DexConverter's last-resort comment).
     *
     *  This is deliberately a LOUD stub. MethodOutliner's header argues against
     *  silent stubs and is right; a body that names its own limit in the
     *  exception message is the opposite of silent, and it keeps the class's
     *  other methods linkable. A caller that never invokes this method is
     *  unaffected; one that does gets an accurate diagnosis instead of a
     *  missing type. */
    public static void emitUnsupportedBody(ClassFileWriter.MethodWriter mw,
                                           DexMethod method, String msg) {
        CodeWriter c = mw.newCode();
        c.typeOp(CodeWriter.NEW, "java/lang/UnsupportedOperationException");
        c.op(CodeWriter.DUP);
        c.ldcString(msg);
        c.methodOp(CodeWriter.INVOKESPECIAL,
                   "java/lang/UnsupportedOperationException",
                   "<init>", "(Ljava/lang/String;)V", false);
        c.op(CodeWriter.ATHROW);
    }

    /** One translation attempt, with the wide-handler fallback the JVM's
     *  exception-edge rule forces, and (MONITOR_COVER) the monitor re-plan.
     *  Returns the translator whose output the method now carries. */
    private static MethodTranslator translateOnce(DexMethod method, DexCode code,
                                                  ClassFileWriter.MethodWriter mw,
                                                  DexType.ClassHierarchy hierarchy,
                                                  InitRepointPlan repoints,
                                                  SuperInterfacePlan superIfaces,
                                                  MethodOutliner.Plan outline,
                                                  MethodSplitter.Plan split) {
        MethodTranslator base = translateBase(method, code, mw, hierarchy, repoints,
                                              superIfaces, outline, split);
        // Only the ordinary path: a hoisted region or a chain part is its own
        // method with its own table, and MethodOutliner / MethodSplitter plan
        // from the translation they are handed.
        if (!MONITOR_COVER || !NARROW_TRY || !MONITOR_SAFE_TRY
                || outline != null || split != null) {
            return base;
        }
        CodeWriter baseCode = mw.code();
        if (baseCode == null || !baseCode.hasMonitorEnter()) return base;
        String before = baseCode.monitorImbalance();
        if (before == null) return base;               // HotSpot can already compile it

        // The re-plan is optional: whatever goes wrong inside it, the method
        // keeps the translation it already has rather than failing its class.
        MonitorCover.Plan plan;
        try {
            plan = MonitorCover.plan(base.lastNorm, base.allTries, base.types,
                                     base.catchAllWide, MONITOR_COVER_THROWING);
        } catch (RuntimeException e) {
            coverStat(method, "refused", before, "planner threw " + e);
            return base;
        }
        if (plan == null) {
            coverStat(method, "refused", before, MonitorCover.lastRefusal.get());
            return base;
        }
        mw.newCode();
        try {
            // Same inputs as `base` in every respect (the same narrowCatchAll
            // choice, hence the same analysis), so the body, its frames and
            // every existing handler come out identical; only the exception
            // table and the appended release stubs differ.
            MethodTranslator t = new MethodTranslator(method, code, mw, hierarchy, false,
                                                      base.narrowCatchAll, repoints, superIfaces,
                                                      null, null);
            t.coverPlan = plan;
            t.run();
            String after = mw.code().monitorImbalance();
            // The release stubs cost code bytes. A method the base
            // translation fit into code_length (JVMS 4.7.3: < 65536) must not
            // be pushed over it by this re-plan, or the class would take the
            // oversize path (split, outline, or a throwing stub body) for the
            // sake of a JIT nicety. measuredLength() is idempotent with respect
            // to serialization (see its javadoc), so asking changes no byte.
            // The largest method rescued in the corpus is 9,941 bytes, so this
            // has never fired; it is here so it cannot. MONITOR_COVER_CODE_LIMIT
            // is 65535 unless a test lowers it.
            int len = after == null ? mw.code().measuredLength() : 0;
            if (after == null && len > MONITOR_COVER_CODE_LIMIT) {
                coverStat(method, "reverted", before, "would be " + len + " bytes");
            } else if (after == null) {
                coverStat(method, "rescued", before, null);
                return t;
            } else {
                coverStat(method, "reverted", before, "still " + after);
            }
        } catch (RuntimeException e) {        // TranslationException above all
            coverStat(method, "reverted", before, String.valueOf(e.getMessage()));
        }
        // Keep the base translation exactly as it was: the writer it filled is
        // put back rather than rebuilt, so nothing it emitted can differ.
        mw.setCode(baseCode);
        return base;
    }

    /**
     * DEX2JVM_MONITOR_COVER -- give a monitor method that HotSpot's JIT
     * cannot prove balanced the catch-all coverage javac would have given it,
     * so it compiles (default ON; =0 never tries, which is the previous output
     * byte for byte; inert unless NARROW_TRY and MONITOR_SAFE_TRY are both on,
     * because it is a refinement of the table those two produce).
     *
     * WHAT IT FIXES. Measured with a standalone replica of that rule over
     * the 62-app corpus (2026-09-26): 8,915 of 107,848 methods with a monitor
     * (8.3%; nowinandroid 1,427 of 2,691) that C1 and C2 refuse with "not
     * compilable (unbalanced monitors)", so they run interpreted forever. By
     * reason: 8,231 "non-empty monitor stack at exceptional exit" (7,927 of them
     * at an ldc: a DEX const that d8 left outside every try range inside the
     * lock), 667 "merge conflict" and 17 "underflow" (non-throwing instructions
     * AFTER a monitor-exit that d8 merged into the range whose catch-all
     * releases the lock, so the whole-range catch-all row fed depth 0 into a
     * handler entered at depth 1). See MonitorCover for the mechanism and the
     * argument that no row it adds or removes can route a real exception.
     * Result on the same corpus: 8,915 -> 98 (the floor is itemised in
     * MonitorCover's header); HotSpot compiled 8,796 of the 8,817 rescued
     * methods at tier 4 and refused none (21 need ad-SDK classes the harness
     * cannot load); jar bytes +0.0053%; G6/G7 pass on all 62 apps and every
     * changed class still verifies.
     *
     * HOW IT IS APPLIED, and why output only changes where it helps. The
     * method is translated as before; only if that output fails
     * CodeWriter.monitorImbalance (a replica of GenerateOopMap's monitor rule)
     * is MonitorCover asked for a plan. The method is then translated again
     * from the SAME inputs, so its body, frames and handlers are identical, and
     * only the exception table changes: non-throwing instructions leave a
     * catch-all row entered in another monitor context, and lock-holding
     * non-throwing instructions nothing of their context covers (plus, under
     * MONITOR_COVER_THROWING, throwing ones outside every try) get a row to a
     * release stub appended after the body (emitCoverStubs). The result is kept
     * only if the replica then PASSES. A refused plan, a re-translation that
     * throws, or one that is still unbalanced puts the original writer back
     * untouched. So every method this changes goes from uncompilable to
     * compilable by the replica, and every other method is byte-identical --
     * except that a class holding a reverted attempt may carry the constant
     * pool entries that attempt added, which no code references.
     *
     * The replica does not track lock identity, so "compilable" is confirmed by
     * HotSpot itself (a WhiteBox compile of the rescued methods at tier 4,
     * with MONITOR_COVER=0 as the control that must be refused).
     * DEX2JVM_MONITOR_COVER_DEBUG=1 prints one line per attempted method (rescued / refused / reverted, with the reason).
     */
    static final boolean MONITOR_COVER =
            !"0".equals(System.getenv("DEX2JVM_MONITOR_COVER"));

    /**
     * DEX2JVM_MONITOR_COVER_THROWING -- let MONITOR_COVER also give a
     * release stub to a THROWING DEX instruction that holds a lock outside
     * every try range (default ON; =0 refuses such a method, which is the
     * MONITOR_COVER output before this existed). Such an instruction sits
     * there because the producer left it there -- R8's no-throw analysis, or
     * an instrumenter such as JaCoCo putting its probe stores outside javac's
     * range -- not because anything proved it cannot throw; ART accepts the
     * shape either way. Measured
     * over the corpus (2026-09-26): 613 of the 653 monitor methods
     * MONITOR_COVER alone still left uncompilable, by DEX opcode iget-object
     * 241, aput-boolean 144 (JaCoCo probes), invoke-static 114, const-string
     * 45, invoke-virtual 38, aput-byte 18, iget 7, iget-wide 6. The one
     * observable difference is on an improbable path, one that throws only
     * on an abnormal condition (an NPE or AIOOBE on a probe array, an OOM, a
     * class-initialisation error): were the
     * instruction to throw, the original exception propagates after the lock
     * is released, where HotSpot's interpreter today replaces it with an
     * IllegalMonitorStateException. ART propagates the original exception
     * (and leaves the lock held), so the caller sees what it would see on ART.
     * See MonitorCover's header.
     */
    static final boolean MONITOR_COVER_THROWING =
            !"0".equals(System.getenv("DEX2JVM_MONITOR_COVER_THROWING"));

    /**
     * The code_length a MONITOR_COVER re-plan may reach before it is reverted:
     * 65535 (JVMS 4.7.3). DEX2JVM_MONITOR_COVER_CODE_LIMIT is a TEST KNOB
     * that can only LOWER it, so the revert can be exercised on a small method
     * (=20 reverts every re-plan; no corpus method comes within 55 KB of the
     * real limit).
     */
    static final int MONITOR_COVER_CODE_LIMIT = coverCodeLimit();

    private static int coverCodeLimit() {
        String v = System.getenv("DEX2JVM_MONITOR_COVER_CODE_LIMIT");
        if (v == null || v.isEmpty()) return 65535;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 && n < 65535 ? n : 65535;
        } catch (NumberFormatException e) {
            return 65535;
        }
    }

    private static final boolean MONITOR_COVER_DEBUG =
            System.getenv("DEX2JVM_MONITOR_COVER_DEBUG") != null;

    private static void coverStat(DexMethod m, String outcome, String before, String why) {
        if (!MONITOR_COVER_DEBUG) return;
        System.err.println("[dex-monitor-cover] " + outcome + " "
                + m.declaringClass().name() + "." + m.name() + m.proto().descriptor()
                + " (was: " + before + ")" + (why == null ? "" : " -- " + why));
    }

    /** The translation as it was before MONITOR_COVER: one attempt, with the
     *  wide-handler fallback the JVM's exception-edge rule forces. */
    private static MethodTranslator translateBase(DexMethod method, DexCode code,
                                                  ClassFileWriter.MethodWriter mw,
                                                  DexType.ClassHierarchy hierarchy,
                                                  InitRepointPlan repoints,
                                                  SuperInterfacePlan superIfaces,
                                                  MethodOutliner.Plan outline,
                                                  MethodSplitter.Plan split) {
        // Under NARROW_TRY the ART edge rule is not a fallback but the rule:
        // the exception table covers exactly the instructions it gives edges
        // to, so the JVM-rule retry below would repeat the same analysis.
        MethodTranslator first = new MethodTranslator(method, code, mw, hierarchy, !NARROW_TRY,
                                                      false, repoints, superIfaces, outline, split);
        try {
            first.run();
            return first;
        } catch (TranslationException e) {
            if (NARROW_TRY) {
                // Only a monitor method's whole-range catch-all can widen a
                // handler frame under NARROW_TRY; see MONITOR_SAFE_TRY. If that
                // is what failed, fall back to narrowing the catch-all as well.
                if (!first.catchAllWide) throw e;
                if (NARROW_MONITOR_DEBUG) {
                    System.err.println("[dex-narrow-monitor] " + method.declaringClass().name()
                            + "." + method.name() + ": " + e.getMessage());
                }
                mw.newCode();
                MethodTranslator t = new MethodTranslator(method, code, mw, hierarchy, false,
                                                          true, repoints, superIfaces, outline, split);
                t.run();
                return t;
            }
            // The JVM's exception-edge rule (JVMS 4.10.1.6: every instruction in
            // a protected range reaches the handler, not just the throwing ones)
            // is what the verifier checks, so it is the default. But it is
            // STRICTLY weaker about what the handler knows, and a handful of real
            // methods -- 40 across Mindustry and GD Lite, among them
            // rhino/Interpreter and androidx/multidex/MultiDexExtractor -- read a
            // register in the handler that the JVM rule leaves undefined there.
            // ART never routes that edge, so the DEX is legal and the read is
            // fine on every path that can actually reach it.
            //
            // Falling back to the ART rule for just that method keeps the class
            // convertible. The cost is that this one method may still fail the
            // split verifier, which is exactly where it stood before -- so this
            // is never a regression, only a smaller win.
            mw.newCode();
            MethodTranslator t = new MethodTranslator(method, code, mw, hierarchy, false,
                                                      false, repoints, superIfaces, outline, split);
            t.run();
            return t;
        }
    }

    /** Diagnostic only: name each monitor method whose whole-range catch-all
     *  could not be translated and fell back to the fully narrowed table. Its
     *  own variable, because DEX2JVM_NARROW_DEBUG names every method. */
    private static final boolean NARROW_MONITOR_DEBUG =
            System.getenv("DEX2JVM_MONITOR_DEBUG") != null;

    // ==================================================================
    // One method
    // ==================================================================

    private static final class MethodTranslator {
        final DexMethod method;
        final DexCode code;
        final ClassFileWriter.MethodWriter mw;
        /**
         * The writer the body is currently being emitted into.
         *
         * NOT final, and that is the whole mechanism behind outlining: while a
         * hoisted region is being emitted this points at the synthetic method's
         * own writer, and everything downstream keeps working because every use
         * site reads the field rather than a captured reference. It is always
         * restored to {@link #mainWriter} before run() returns.
         */
        CodeWriter out;
        /** The writer for the method actually being translated. */
        final CodeWriter mainWriter;
        final DexFile dex;
        final String ownerName;
        final boolean isStatic;

        /** Regions to hoist, or null on the ordinary path. See MethodOutliner. */
        final MethodOutliner.Plan outline;
        /**
         * Which writer marked each label.
         *
         * Once a body can span several writers, a label is only meaningful to
         * the one that marked it: CodeWriter.layout resolves a label through
         * `insns.get(l.insnIndex)`, so handing a label from another writer to
         * lineNumber() would resolve it against the wrong instruction list. Every
         * later consumer routes through this map.
         */
        final Map<CodeWriter.Label, CodeWriter> labelOwner = new HashMap<>();
        /** Synthetic methods built for this method, added to the class only once
         *  the whole translation has survived. */
        final List<Object[]> pendingSynthetics = new ArrayList<>();
        /** DEX offset at which the region currently being emitted ends, or -1. */
        int chunkEnd = -1;

        /**
         * Everything that is scoped to ONE emitted method.
         *
         * The ordinary path builds exactly one of these, wrapping mainWriter,
         * so nothing about it changes; the MethodSplitter path builds one per
         * chain member. The maps have to be per-part rather than per-method
         * because a DEX offset means a DIFFERENT bytecode position in each
         * part's byte stream: a label resolves through its own writer's
         * instruction list, and a try-range end that coincides with the next
         * part's first instruction has to bind at the END of THIS part.
         */
        final class PartState implements StackMapWriter.FrameProvider {
            /** 0 is the original method; 1.. are the synthetic chain members. */
            final int index;
            final CodeWriter writer;
            /** The plan entry, or null on the ordinary path. */
            final MethodSplitter.Part part;

            /** DEX code-unit offset -> label at the start of its translated sequence. */
            final Map<Integer, CodeWriter.Label> labels = new HashMap<>();
            /** Offsets that begin an exception handler: their frame carries one
             *  stack entry. Only handlers whose body CONSUMES the exception (they
             *  start with move-exception); the rest go through a stub. */
            final Map<Integer, String> handlerEntryTypes = new HashMap<>();
        /**
         * Handler offset -> a stub that discards the thrown exception and jumps
         * to the handler body.
         *
         * The JVM pushes the exception onto an emptied stack at handler entry
         * (JVMS 4.10.1.6). DEX captures it with `move-exception`, but a handler
         * is NOT required to start with one -- a catch that ignores its
         * exception simply does not. Translating that literally leaves the
         * exception on the stack for the whole handler body, and the stack
         * depth then disagrees with every other path into the same code. On GD
         * Lite that is a real collision: AppLovin's e$a.a(e$b) has two adjacent
         * catch-all handlers where the first body falls straight through into
         * the second's entry bci, so 462 is reachable at depth 1 by dispatch
         * and depth 0 by fall-through. HotSpot's oop-map builder calls that a
         * stack height conflict and aborts the VM.
         *
         * Routing such a handler through `pop; goto body` fixes both halves:
         * the exception is consumed, and the body bci is only ever entered with
         * an empty stack.
         */
            final Map<Integer, CodeWriter.Label> handlerStubs = new HashMap<>();
            /** Bytecode offset of each stub -> the handler DEX offset it serves. */
            final Map<Integer, Integer> stubOffsets = new HashMap<>();
        /**
         * Try-range END offsets not yet bound to a label, ascending.
         *
         * A range end is one PAST the last covered instruction, so unlike a
         * branch target it need not be the start of an instruction. Two shapes
         * occur in real DEX and both used to lose the whole class:
         *   - it equals insnsSize, when the range runs to the end of the method
         *     (Flappy's FileProvider.a: try [0x25,0x2d) with insnsSize 0x2d);
         *   - it lands on a data payload, which Normalizer drops as data.
         * Binding these as the body is emitted is what gives them a real
         * bytecode position. Creating them afterwards, as emitTryCatch did,
         * left them referenced but never marked.
         */
            final java.util.TreeSet<Integer> pendingRangeEnds = new java.util.TreeSet<>();

            /**
             * DEX offset in a LATER part -> the portal that calls into it.
             *
             * Deliberately NOT in {@link #labels}: a portal stands for an offset
             * this part does not contain, and putting it there would let
             * bindRangeEndsUpTo reuse it as a try-range END -- which would stretch
             * the range over the handler stubs the portal sits behind. TreeMap so
             * emission order is a function of the code, not of hashing.
             */
            final java.util.TreeMap<Integer, CodeWriter.Label> portals = new java.util.TreeMap<>();
            /** Try blocks whose range and handlers both live in this part. */
            List<TypeInference.TryBlock> tries = java.util.Collections.emptyList();
            /** Handler DEX offset -> this part's COPY of a handler that lives in
             *  another part (MethodSplitter.CLONE_HANDLERS). Kept apart from
             *  {@link #labels} for the reason portals are: the offset is not
             *  this part's, and a range end must never bind to it. */
            final java.util.TreeMap<Integer, CodeWriter.Label> cloneLabels =
                    new java.util.TreeMap<>();

            PartState(int index, CodeWriter writer, MethodSplitter.Part part) {
                this.index = index;
                this.writer = writer;
                this.part = part;
            }

            @Override public StackMapWriter.Frame frameAt(int bytecodeOffset) {
                return frameFor(this, bytecodeOffset);
            }

            /** See MethodTranslator.bytecodeToDex for why this is built once, in
             *  ascending DEX order, rather than scanned per frame. */
            private Map<Integer, Integer> bytecodeToDex;

            Integer dexOffsetForBytecode(int bytecodeOffset) {
                if (bytecodeToDex == null) buildBytecodeToDex();
                return bytecodeToDex.get(bytecodeOffset);
            }

            void buildBytecodeToDex() {
                bytecodeToDex = new HashMap<>();
                // A stub has no DEX offset of its own; record which handler it
                // serves so frameAt can give it that handler's locals plus the
                // thrown exception on the stack.
                for (Map.Entry<Integer, CodeWriter.Label> e : handlerStubs.entrySet()) {
                    if (e.getValue().offset >= 0) stubOffsets.put(e.getValue().offset, e.getKey());
                }
                // A portal stands for the program point it jumps to, so it takes
                // that offset's frame: the verifier state a branch into it must
                // satisfy is exactly the state at the target.
                for (Map.Entry<Integer, CodeWriter.Label> e : portals.entrySet()) {
                    CodeWriter.Label l = e.getValue();
                    if (l.offset >= 0) bytecodeToDex.put(l.offset, e.getKey());
                }
                List<Integer> dexOffsets = new ArrayList<>(labels.keySet());
                java.util.Collections.sort(dexOffsets);
                for (int dexOffset : dexOffsets) {
                    CodeWriter.Label l = labels.get(dexOffset);
                    if (l == null || l.offset < 0) continue;
                    // Only THIS part's labels describe the method this provider
                    // serves. A label inside a hoisted region (or another part)
                    // has an offset in a DIFFERENT byte stream, so admitting it
                    // here would publish a frame for an unrelated program point.
                    if (ownerOf(l) != writer) continue;
                    Integer incumbent = bytecodeToDex.get(l.offset);
                    if (incumbent == null || betterFrameSource(this, dexOffset, incumbent)) {
                        bytecodeToDex.put(l.offset, dexOffset);
                    }
                }
                // A handler copy's pc is that handler's program point: its frame
                // is the handler's own (locals at the handler, the exception on
                // the stack), whatever else happens to share the bytecode offset.
                for (Map.Entry<Integer, CodeWriter.Label> e : cloneLabels.entrySet()) {
                    CodeWriter.Label l = e.getValue();
                    if (l.offset >= 0) bytecodeToDex.put(l.offset, e.getKey());
                }
            }
        }

        Instruction[] decoded;
        TypeInference.Result types;
        Locals locals;

        /**
         * Per-part emission state. On the ordinary path there is exactly ONE of
         * these and it wraps mainWriter, so every read below is the same map it
         * always was; on the MethodSplitter path there is one per chain member.
         * See PartState.
         */
        PartState cur;
        final List<PartState> partStates = new ArrayList<>();
        /** The chain plan, or null on the ordinary path. See MethodSplitter. */
        final MethodSplitter.Plan split;
        /** {name, descriptor} of own fields an initializer's synthetic helper
         *  writes: statics from a helper of &lt;clinit&gt;, instance fields from a
         *  helper of &lt;init&gt;. Handed to the class only by
         *  commitSyntheticMethods, so a failed attempt records nothing. See
         *  ClassFileWriter.unfinalFieldsWrittenByInitializerHelpers. */
        final List<String[]> initializerHelperPuts = new ArrayList<>();
        /** The try blocks of the method being translated, for the split path. */
        List<TypeInference.TryBlock> allTries = java.util.Collections.emptyList();
        /** Every handler offset -> its exception type, stubbed or not. */
        final Map<Integer, String> handlerTypes = new HashMap<>();
        /** new-instance sites, for uninitialized VTypes in frames. */
        final Map<Integer, CodeWriter.Label> newLabels = new HashMap<>();

        final DexType.ClassHierarchy hierarchy;
        final boolean wideHandlers;
        /** Set by the MONITOR_SAFE_TRY fallback: narrow catch-all clauses too. */
        final boolean narrowCatchAll;
        /**
         * This method keeps its catch-all clauses over their whole DEX range and
         * analyses them under the JVM edge rule. Decided in run() once the code is
         * decoded; see Translator.MONITOR_SAFE_TRY.
         */
        boolean catchAllWide;
        /**
         * Set by translateOnce for a MONITOR_COVER re-translation, before run():
         * which non-throwing instructions leave their catch-all row and which get
         * a release stub. Changes the exception table only (emitTryCatchCovered).
         */
        MonitorCover.Plan coverPlan;
        /** The release stubs emitTryCatchCovered appended: label -> the JVM
         *  slots of the locks it releases, innermost first. frameFor reads it. */
        final Map<CodeWriter.Label, int[]> coverStubs = new java.util.LinkedHashMap<>();
        final InitRepointPlan repointPlan;
        final SuperInterfacePlan superIfacePlan;
        /** new-instance DEX offset -> the &lt;init&gt; that consumes it, for the
         *  sites whose allocation stays on the operand stack. See
         *  computeDeferredNew. */
        Map<Integer, Integer> deferredNew = new HashMap<>();
        /** DEX offsets of those &lt;init&gt; calls. */
        java.util.Set<Integer> deferredInit = new HashSet<>();
        /** DEX offsets where JVM local 0 is still uninitializedThis. See
         *  computeThisUninit. */
        java.util.Set<Integer> thisUninit;

        /**
         * DEX offset of a constructor call -> the class we name instead of the
         * one the DEX names. Keyed by the NEGATIVE constant-pool-like index we
         * substitute into the normalized instruction, so type inference sees the
         * re-pointed owner and types the initialized value the way ART does (as
         * the ALLOCATED class, not the callee's declaring class).
         */
        final Map<Integer, Repoint> repoints = new java.util.LinkedHashMap<>();
        /** Synthetic method index -> its target, for DexResolver and emitInvoke. */
        final Map<Integer, Repoint> syntheticMethods = new HashMap<>();

        MethodTranslator(DexMethod method, DexCode code, ClassFileWriter.MethodWriter mw,
                         DexType.ClassHierarchy hierarchy, boolean wideHandlers,
                         boolean narrowCatchAll,
                         InitRepointPlan repointPlan, SuperInterfacePlan superIfacePlan,
                         MethodOutliner.Plan outline, MethodSplitter.Plan split) {
            this.repointPlan = repointPlan == null ? InitRepointPlan.NONE : repointPlan;
            this.superIfacePlan = superIfacePlan == null ? SuperInterfacePlan.NONE : superIfacePlan;
            this.wideHandlers = wideHandlers;
            this.narrowCatchAll = narrowCatchAll;
            this.method = method;
            this.code = code;
            this.mw = mw;
            this.hierarchy = hierarchy;
            this.outline = outline;
            this.split = split;
            // Always a fresh writer: a retry must not inherit anything the
            // attempt it is replacing emitted.
            this.out = mw.newCode();
            this.mainWriter = this.out;
            this.cur = new PartState(0, this.mainWriter,
                                     split == null ? null : split.parts().get(0));
            this.partStates.add(this.cur);
            this.dex = method.declaringClass().dex();
            this.ownerName = method.declaringClass().name();
            this.isStatic = method.isStatic();
        }

        void run() {
            decoded = InstructionDecoder.decode(code.insns());
            catchAllWide = NARROW_TRY && MONITOR_SAFE_TRY && !wideHandlers && !narrowCatchAll
                    && hasMonitorEnter(decoded);
            List<TypeInference.TryBlock> tries = buildTryBlocks();
            collectRepoints();
            computeDeferredNew(tries);
            // The cheap scan misses a site whose register is written on a path
            // that is not TAKEN between the allocation and the call, so a second
            // exact pass runs off the analysis itself -- but only for methods
            // whose shape says there is something to find. See hasHiddenRepoint.
            boolean exactPass = hasHiddenRepoint();

            // Analyse, then check the cheap allocation pairing against what the
            // analysis actually proved. Nothing is emitted until after this
            // loop, so a disagreement costs one extra analysis and no output.
            List<TypeInference.Insn> norm;
            int drops = 0;
            for (;;) {
                norm = Normalizer.normalize(decoded, dex, method);
                applyRepoints(norm);

                TypeInference.MethodInput in = new TypeInference.MethodInput(
                    code.registersSize(), isStatic, method.isConstructor(), ownerName,
                    spacedParams(), norm, tries, new DexResolver(dex, syntheticMethods),
                    hierarchy, method.proto().returnType());
                in.wideHandlerEdges = wideHandlers;
                in.catchAllWideEdges = catchAllWide;
                in.deferredNewSites = deferredNew.keySet();
                types = TypeInference.analyzeQuietly(in);

                if (exactPass) {
                    exactPass = false;
                    if (collectRepointsFromTypes(norm)) continue;   // re-analyse
                }
                if (repoints.isEmpty()) break;
                List<Integer> bad = invalidRepoints();
                if (bad.isEmpty()) break;
                // A register's allocation site was ambiguous enough that the
                // pairing was wrong. Drop those sites; drop every site if a
                // second round still disagrees, which bounds the loop at four
                // analyses and always terminates.
                if (drops++ == 0) repoints.keySet().removeAll(bad); else repoints.clear();
            }

            computeThisUninit(norm, tries);
            locals = Locals.allocate(types, norm, code.registersSize(), argSlotCount());
            lastNorm = norm;
            allTries = tries;
            if (ConstReadingElision.armed()) {
                deadConstReadings = ConstReadingElision.plan(norm, types, tries,
                        branchTargets(tries), elisionBarriers(), decoded);
            }

            if (split != null) { runSplit(norm, tries); return; }

            cur.tries = tries;
            for (TypeInference.TryBlock t : tries) cur.pendingRangeEnds.add(t.endOffset);
            // A stub row that runs through the method's last instruction ends
            // one past it, where no instruction label exists; bind one there the
            // way a try range ending at the end of the code is bound.
            if (coverPlan != null && !norm.isEmpty()) {
                TypeInference.Insn last = norm.get(norm.size() - 1);
                if (coverPlan.stub.containsKey(last.offset)) {
                    cur.pendingRangeEnds.add(last.nextOffset);
                }
            }

            emitPrologue();
            emitBody(norm);
            // A region that runs to the last instruction has no following
            // instruction to close it, so close it here.
            if (out != mainWriter) {
                out.op(CodeWriter.RETURN);
                out = mainWriter;
                chunkEnd = -1;
            }
            // Whatever is left ends at or past the end of the code; it binds
            // here, so the range closes at the end of the emitted body.
            bindRangeEndsUpTo(Integer.MAX_VALUE);
            markEndOfCode();
            emitHandlerStubs();
            emitTryCatch(tries);
            emitLineNumbers(cur.labels);

            // newCode() already installed the correct initial frame, including
            // uninitializedThis for a constructor, so only the provider is ours.
            // Only the main writer needs one: a hoisted region is branch-free and
            // uncovered by any handler, so it has no branch target and therefore
            // needs no StackMapTable at all (JVMS 4.10.1).
            mainWriter.setFrameProvider(cur);
        }

        /**
         * DEX offset -> scalars of that const whose store is dead, or null when
         * ConstReadingElision is not armed (every ordinary translation).
         */
        Map<Integer, Integer> deadConstReadings;

        /** Offsets where an outline chunk or a split part begins or ends, and
         *  MonitorCover stub sites: locals are read there in ways it.uses does
         *  not describe, so an elision window must not cross one. */
        java.util.Set<Integer> elisionBarriers() {
            java.util.Set<Integer> b = new HashSet<>();
            if (outline != null) {
                for (MethodOutliner.Chunk c : outline.chunks()) {
                    b.add(c.startOffset);
                    b.add(c.endOffset);
                }
            }
            if (split != null) {
                for (MethodSplitter.Part p : split.parts()) b.add(p.startOffset);
            }
            if (coverPlan != null) b.addAll(coverPlan.stub.keySet());
            return b;
        }

        /** The normalized stream the emitted body came from, for planOutlining. */
        List<TypeInference.Insn> lastNorm = java.util.Collections.emptyList();

        /**
         * Whether the method contains a monitor-enter at all. Syntactic on
         * purpose: it has to be known BEFORE the analysis it steers, and a dead
         * monitor-enter only costs that method the narrowed catch-all.
         */
        static boolean hasMonitorEnter(Instruction[] insns) {
            for (Instruction i : insns) {
                if (!i.isPayload() && i.opcode() == Opcodes.MONITOR_ENTER) return true;
            }
            return false;
        }

        // ------------------------------------------- constructor re-pointing

        /**
         * Find the constructor calls in this method that the JVM would reject
         * and that the session's plan can rescue. See InitRepointPlan for why
         * the plan has to be built before the parallel fan-out.
         */
        void collectRepoints() {
            if (repointPlan.isEmpty()) return;
            String superName = method.declaringClass().superclassName();
            InitRepointPlan.scanMethod(decoded, method, code,
                                       (offset, uninitThis, allocated, ref) -> {
                // The new-instance flavour re-points at the ALLOCATED class; the
                // uninitializedThis flavour at the current class's DIRECT
                // superclass, which is the only other thing JVMS 4.9.2 allows an
                // <init> to name for its own `this`.
                String target = uninitThis ? superName : allocated;
                if (target == null) return;
                String named = DexFile.internalName(ref.declaringClass());
                String desc = ref.proto().descriptor();
                String emitDesc = repointPlan.repointDescriptor(target, desc, named);
                if (emitDesc == null) return;
                repoints.put(offset, new Repoint(offset, uninitThis, allocated, target,
                                                 ref.proto(), emitDesc));
            });
        }

        /**
         * Pair every {@code new-instance} that can safely keep its uninitialized
         * reference on the OPERAND STACK with the {@code <init>} that consumes
         * it, javac-style.
         *
         * WHY. Storing the allocation into a JVM local parks an
         * {@code uninitialized(N)} there, and JVMS 4.10.1.2 gives
         * {@code uninitialized(_)} no assignability to any class type. So when
         * the new..&lt;init&gt; window falls inside a protected range, the handler
         * frame for that slot is forced to {@code top} and a handler body that
         * reads the register cannot load it:
         *   androidx/core/graphics/TypefaceCompatApi29Impl
         *     .createFromFontFamilyFilesResourceEntry @146: aload
         *   Reason: Type top (current frame, locals[19]) is not assignable to
         *           reference type
         * javac never hits this because the value lives on the stack, which
         * JVMS 4.10.1.6 DISCARDS on the exception edge
         * ({@code TrueExcStackFrame = frame(Locals, [ExceptionClass], Flags)}).
         *
         * The {@code new} still executes at its DEX offset -- only the STORE
         * moves -- so class initialisation and allocation keep their original
         * order relative to everything in between. That is what makes the
         * intervening instructions free to throw.
         *
         * GUARDS, all required, else the site keeps the old emission:
         * <ul>
         *   <li>the next instruction to touch the register is an
         *       {@code invoke-direct <init>} taking it as the receiver, so
         *       exactly one {@code <init>} consumes the allocation and nothing
         *       reads, copies or overwrites it in between;</li>
         *   <li>the register is not ALSO one of that call's arguments, which
         *       would need a second copy on the stack;</li>
         *   <li>nothing between the two is a branch, switch, return or throw,
         *       so control provably falls through;</li>
         *   <li>no offset in the window is a branch or switch TARGET, because a
         *       frame there would have to describe a stack our emission leaves
         *       two entries deep, and the other incoming edge has neither.</li>
         * </ul>
         * Handler entries are not excluded: a handler is reached with the stack
         * replaced, so it cannot observe the deferred value at all.
         *
         * The set is handed to TypeInference so the analysis stops writing the
         * register's OBJ SLOT at these sites while still tracking the VALUE.
         */
        void computeDeferredNew(List<TypeInference.TryBlock> tries) {
            deferredNew = new HashMap<>();
            deferredInit = new HashSet<>();
            if (decoded.length == 0) return;
            java.util.Set<Integer> targets = branchTargets(tries);
            for (int i = 0; i < decoded.length; i++) {
                Instruction n = decoded[i];
                if (n.isPayload() || n.opcode() != 0x22) continue;
                int reg = n.a();
                for (int j = i + 1; j < decoded.length; j++) {
                    Instruction in = decoded[j];
                    if (in.isPayload()) break;
                    if (targets.contains(in.address())) break;
                    int op = in.opcode();
                    if (op == 0x70 || op == 0x76) {          // invoke-direct[/range]
                        int[] args = in.argRegisters();
                        DexFile.MethodRef ref;
                        try { ref = dex.methodRef(in.index()); } catch (RuntimeException e) { break; }
                        if (ref == null || !"<init>".equals(ref.name())) break;
                        if (args.length == 0 || args[0] != reg) break;
                        boolean alsoArgument = false;
                        for (int k = 1; k < args.length; k++) if (args[k] == reg) alsoArgument = true;
                        if (alsoArgument) break;
                        deferredNew.put(n.address(), in.address());
                        deferredInit.add(in.address());
                        break;
                    }
                    if (touchesRegister(in, reg) || divertsControl(in)) break;
                }
            }
        }

        /**
         * Every offset control can arrive at other than by falling through.
         *
         * The CATCH HANDLERS are load-bearing here and were the omission that
         * made reddit's pvm.c fail G7: emitHandlerStubs reaches a handler by a
         * goto, so a handler entry landing inside a deferred window is a second
         * incoming edge with an EMPTY stack while the fall-through arrives two
         * deep ("bci 576 is reachable at depth 2 (from bci 574) and at depth 0").
         */
        java.util.Set<Integer> branchTargets(List<TypeInference.TryBlock> tries) {
            java.util.Set<Integer> out = new HashSet<>();
            for (TypeInference.TryBlock t : tries) {
                for (TypeInference.Catch c : t.catches) out.add(c.handlerOffset);
            }
            for (Instruction in : decoded) {
                if (in.isPayload()) continue;
                switch (TypeInference.familyOf(in.opcode())) {
                    case TypeInference.F_GOTO:
                    case TypeInference.F_IF:
                    case TypeInference.F_IFZ:
                        out.add(in.target());
                        break;
                    case TypeInference.F_SWITCH:
                        for (int t : in.switchTargets()) out.add(t);
                        break;
                    default:
                        break;
                }
            }
            return out;
        }

        /** True when {@code in} reads or writes {@code reg} in any position. */
        boolean touchesRegister(Instruction in, int reg) {
            int[] args = in.argRegisters();
            if (args != null) for (int a : args) if (a == reg) return true;
            switch (TypeInference.familyOf(in.opcode())) {
                case TypeInference.F_INVOKE_VIRTUAL:
                case TypeInference.F_INVOKE_SUPER:
                case TypeInference.F_INVOKE_DIRECT:
                case TypeInference.F_INVOKE_STATIC:
                case TypeInference.F_INVOKE_INTERFACE:
                case TypeInference.F_INVOKE_CUSTOM:
                case TypeInference.F_INVOKE_POLYMORPHIC:
                case TypeInference.F_FILLED_NEW_ARRAY:
                    return false;                            // registers are in argRegisters
                default:
                    break;
            }
            // Every other form addresses at most vA, vB and vC, and reading a
            // register we do not model as read only ever DECLINES a site.
            return in.a() == reg || in.b() == reg || in.c() == reg
                || (in.isWideA() && in.a() + 1 == reg);
        }

        boolean divertsControl(Instruction in) {
            switch (TypeInference.familyOf(in.opcode())) {
                case TypeInference.F_GOTO:
                case TypeInference.F_IF:
                case TypeInference.F_IFZ:
                case TypeInference.F_SWITCH:
                case TypeInference.F_RETURN:
                case TypeInference.F_THROW:
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Whether this method has an {@code <init>} call the cheap scan cannot
         * have paired, so it is worth paying for one extra analysis.
         *
         * The tell is an {@code <init>} call naming a class that this method
         * neither allocates nor inherits from, which is exactly R8's re-pointed
         * shape and is otherwise almost unheard of. It costs one pass over the
         * decoded instructions and keeps every ordinary method on a single
         * analysis. The scan still runs too, and catches the complementary shape
         * (a site re-pointed AT a class the method also allocates elsewhere).
         */
        boolean hasHiddenRepoint() {
            if (repointPlan.isEmpty()) return false;
            java.util.Set<String> allocated = new HashSet<>();
            for (Instruction i : decoded) {
                if (i.isPayload() || i.opcode() != 0x22) continue;
                String d;
                try { d = dex.typeDescriptor(i.index()); } catch (RuntimeException e) { continue; }
                if (d != null) allocated.add(DexFile.internalName(d));
            }
            String superName = method.declaringClass().superclassName();
            boolean ctor = method.isConstructor() && !isStatic;
            int ownDelegation = 0;
            for (Instruction i : decoded) {
                if (i.isPayload()) continue;
                if (i.opcode() != 0x70 && i.opcode() != 0x76) continue;
                DexFile.MethodRef ref;
                try { ref = dex.methodRef(i.index()); } catch (RuntimeException e) { continue; }
                if (ref == null || !"<init>".equals(ref.name())) continue;
                String named = DexFile.internalName(ref.declaringClass());
                if (allocated.contains(named)) continue;
                // A constructor gets ONE free call naming the current class or
                // its direct superclass: its own this(...)/super(...). A second
                // one, or any at all in a method that is not a constructor, has
                // to be paired with an allocation and so is worth a look.
                // Measured: reddit's duq.call() does `new zxv` and calls
                // java/lang/Object.<init>, which is also duq's own superclass, so
                // the old blanket exemption hid it.
                if (ctor && (named.equals(ownerName) || named.equals(superName))
                        && ownDelegation++ == 0) {
                    continue;
                }
                return true;
            }
            return false;
        }

        /**
         * Add every constructor call the ANALYSIS proves the JVM would reject,
         * which the linear scan can miss.
         *
         * The scan forgets a register as soon as anything writes it, including a
         * write on a path that is not taken between the allocation and the call.
         * Measured: androidx/compose/runtime/q1.a does `new-instance v0,
         * ParcelableSnapshotMutableState` at @16 and calls the superclass
         * constructor on v0 at @47, with a `const-string v0` at @31 sitting on
         * the throw path in between -- so the site was silently dropped and the
         * class kept failing with "Call to wrong <init> method".
         *
         * Type inference has no such blind spot: it hands back the receiver's
         * uninitialized reference with the offset of the `new` that made it.
         *
         * @return true when something was added, so the caller re-analyses with
         *         the re-pointed owners in place
         */
        boolean collectRepointsFromTypes(List<TypeInference.Insn> norm) {
            String superName = method.declaringClass().superclassName();
            Map<Integer, String> allocations = null;
            boolean added = false;
            for (TypeInference.Insn insn : norm) {
                if (TypeInference.familyOf(insn.opcode) != TypeInference.F_INVOKE_DIRECT) continue;
                if (insn.args.length == 0 || insn.args[0] < 0) continue;   // already ours
                if (repoints.containsKey(insn.offset)) continue;
                if (!types.isReachable(insn.offset)) continue;
                DexFile.MethodRef ref;
                try { ref = dex.methodRef((int) insn.args[0]); } catch (RuntimeException e) { continue; }
                if (ref == null || !"<init>".equals(ref.name())) continue;
                TypeInference.InsnTypes it = types.typesFor(insn.offset);
                if (it == null || it.uses.length == 0) continue;
                DexType.Ref r = it.uses[0].type.ref();
                if (r == null) continue;

                String named = DexFile.internalName(ref.declaringClass());
                String target;
                boolean uninitThis;
                if (r.kind == DexType.Ref.KIND_UNINIT_THIS) {
                    // JVMS 4.9.2 already allows the current class and its DIRECT
                    // superclass here.
                    if (named.equals(ownerName) || named.equals(superName)) continue;
                    target = superName;
                    uninitThis = true;
                } else if (r.kind == DexType.Ref.KIND_UNINIT) {
                    if (allocations == null) allocations = newInstanceTypes();
                    target = allocations.get(r.newOffset);
                    if (target == null || target.equals(named)) continue;
                    uninitThis = false;
                } else {
                    continue;
                }
                if (target == null) continue;
                String desc = ref.proto().descriptor();
                String emitDesc = repointPlan.repointDescriptor(target, desc, named);
                if (emitDesc == null) continue;
                repoints.put(insn.offset, new Repoint(insn.offset, uninitThis,
                        uninitThis ? null : target, target, ref.proto(), emitDesc));
                added = true;
            }
            return added;
        }

        /**
         * Swap each re-pointed site's DEX method index for a negative one that
         * DexResolver answers from {@link #syntheticMethods}.
         *
         * Rewriting the INDEX rather than only the emitted owner is what keeps
         * type inference honest: TypeInference's F_INVOKE_DIRECT case types the
         * initialized value as the callee's declaring class, so re-pointing at
         * the allocated class is also what makes it agree with ART, whose
         * RegTypeCache::FromUninitialized returns the type the new-instance
         * created. Leaving inference on the old owner would put the SUPERCLASS
         * in every frame and break the very next putfield/putstatic.
         */
        void applyRepoints(List<TypeInference.Insn> norm) {
            syntheticMethods.clear();
            if (repoints.isEmpty()) return;
            int next = -1;
            for (int i = 0; i < norm.size(); i++) {
                TypeInference.Insn insn = norm.get(i);
                Repoint rp = repoints.get(insn.offset);
                if (rp == null || insn.args.length == 0) continue;
                rp.index = next--;
                syntheticMethods.put(rp.index, rp);
                long[] args = insn.args.clone();
                args[0] = rp.index;
                norm.set(i, new TypeInference.Insn(insn.offset, insn.nextOffset, insn.opcode,
                        args, insn.registerList, insn.switchTargets, insn.moveResultDescriptor,
                        insn.implicitCastTypeIndex, insn.implicitCastRegisters));
            }
        }

        /**
         * Re-pointed sites the analysis does NOT agree with, by DEX offset.
         *
         * The pairing that produced them is a linear scan, so it can be fooled
         * by control flow into naming the wrong allocation. Emitting
         * `invokespecial T.<init>` against a `new X` would be exactly the bug
         * this whole file exists to remove, so every site is confirmed here
         * against the uninitialized reference type inference actually proved.
         */
        List<Integer> invalidRepoints() {
            List<Integer> bad = new ArrayList<>();
            Map<Integer, String> allocations = null;
            for (Repoint rp : repoints.values()) {
                // Unreachable code is never emitted, so it cannot be wrong.
                if (!types.isReachable(rp.dexOffset)) continue;
                TypeInference.InsnTypes it = types.typesFor(rp.dexOffset);
                DexType.Ref r = (it == null || it.uses.length == 0) ? null : it.uses[0].type.ref();
                if (r == null) { bad.add(rp.dexOffset); continue; }
                if (rp.uninitThis) {
                    if (r.kind != DexType.Ref.KIND_UNINIT_THIS) bad.add(rp.dexOffset);
                    continue;
                }
                if (r.kind != DexType.Ref.KIND_UNINIT) { bad.add(rp.dexOffset); continue; }
                if (allocations == null) allocations = newInstanceTypes();
                if (!rp.allocated.equals(allocations.get(r.newOffset))) bad.add(rp.dexOffset);
            }
            return bad;
        }

        /** DEX offset of every new-instance -> the internal name it allocates. */
        Map<Integer, String> newInstanceTypes() {
            Map<Integer, String> out = new HashMap<>();
            for (Instruction i : decoded) {
                if (i.isPayload() || i.opcode() != 0x22) continue;
                String d = dex.typeDescriptor(i.index());
                if (d != null) out.put(i.address(), DexFile.internalName(d));
            }
            return out;
        }

        // ---------------------------------------------------------- setup

        /** Parameter descriptors, one per register word, receiver first when
         *  non-static, with a null after each wide half. */
        String[] spacedParams() {
            List<String> ps = new ArrayList<>();
            if (!isStatic) ps.add("L" + ownerName + ";");
            for (String p : method.proto().parameterTypes()) {
                ps.add(p);
                if (isWideDesc(p)) ps.add(null);
            }
            return ps.toArray(new String[0]);
        }

        int argSlotCount() {
            int n = isStatic ? 0 : 1;
            for (String p : method.proto().parameterTypes()) n += isWideDesc(p) ? 2 : 1;
            return n;
        }

        List<TypeInference.TryBlock> buildTryBlocks() {
            List<TypeInference.TryBlock> out = new ArrayList<>();
            for (DexCode.Try t : code.tries()) {
                List<TypeInference.Catch> cs = new ArrayList<>();
                for (DexCode.Handler h : t.handlers()) {
                    String internal = h.typeName();   // null == catch_all
                    cs.add(new TypeInference.Catch(internal, h.address()));
                    // ONE bci can be the handler for several DIFFERENT exception
                    // types -- R8 folds identical catch bodies together, and a
                    // catch_all can share a bci with a typed catch. The frame
                    // there has to name a type that ALL of them are assignable
                    // to; naming whichever arrived last made the verifier
                    // reject the real one ("Type java/lang/NoSuchMethodError is
                    // not assignable to java/lang/NoClassDefFoundError (stack
                    // map)"). Without a class hierarchy the only provable common
                    // supertype is Throwable, which is always correct here
                    // because every catchable value is one (JVMS 4.10.1.6).
                    String had = handlerTypes.get(h.address());
                    String now = internal == null ? "java/lang/Throwable" : internal;
                    handlerTypes.put(h.address(),
                        (had == null || had.equals(now)) ? now : "java/lang/Throwable");
                }
                out.add(new TypeInference.TryBlock(t.startAddress(),
                    t.startAddress() + t.instructionCount(), cs));
            }
            return out;
        }

        /**
         * Bind every try-range end at or before {@code dexOffset} to the
         * current position.
         *
         * Called right after each instruction's own label is marked, so an end
         * that IS an instruction start needs nothing (that label is already
         * bound at exactly the right place), and an end that fell in a gap --
         * a data payload emits no bytecode -- binds to the position the next
         * real instruction occupies, which is the same bytecode offset.
         */
        void bindRangeEndsUpTo(int dexOffset) {
            while (!cur.pendingRangeEnds.isEmpty() && cur.pendingRangeEnds.first() <= dexOffset) {
                int end = cur.pendingRangeEnds.pollFirst();
                if (cur.labels.containsKey(end)) continue;   // an instruction starts here
                CodeWriter.Label l = out.newLabel("dex@0x" + Integer.toHexString(end)
                        + " try-range end");
                cur.labels.put(end, l);
                out.mark(l);
            }
        }

        /**
         * One entry stub per handler whose body does not itself consume the
         * thrown exception. See handlerStubs for why.
         *
         * A handler that DOES start with move-exception is left pointing
         * straight at its body: move-exception translates to a store from the
         * stack, so the exception is consumed at the right place already, and
         * the extra hop would just cost bytes.
         */
        void emitHandlerStubs() { emitHandlerStubs(handlerTypes.keySet()); }

        void emitHandlerStubs(java.util.Collection<Integer> which) {
            List<Integer> offsets = new ArrayList<>(which);
            java.util.Collections.sort(offsets);   // deterministic emission order
            for (int h : offsets) {
                if (startsWithMoveException(h)) {
                    // Body consumes it; frameAt must still put it on the stack.
                    cur.handlerEntryTypes.put(h, handlerTypes.get(h));
                    continue;
                }
                CodeWriter.Label body = cur.labels.get(h);
                if (body == null) continue;        // unreachable handler, nothing to guard
                CodeWriter.Label stub = out.newLabel("handler-stub@0x" + Integer.toHexString(h));
                out.mark(stub);
                out.op(CodeWriter.POP);
                out.jump(CodeWriter.GOTO, body);
                cur.handlerStubs.put(h, stub);
            }
        }

        /**
         * True when this handler's body actually consumes the thrown exception,
         * i.e. it starts with move-exception AND we emitted that instruction.
         *
         * The reachability half matters: emitBody skips unreachable
         * instructions, so a dead handler emits no store no matter what the raw
         * DEX says, and treating it as self-consuming would leave the throwable
         * on the stack.
         */
        boolean startsWithMoveException(int dexOffset) {
            if (!types.isReachable(dexOffset)) return false;
            for (Instruction i : decoded) {
                if (i.address() == dexOffset) return i.opcode() == 0x0d;
            }
            return false;
        }

        /** Marks, at the current position, every label that was created as a
         *  branch/range target but never reached by the body. */
        void markEndOfCode() {
            for (Map.Entry<Integer, CodeWriter.Label> e : cur.labels.entrySet()) {
                if (e.getKey() >= code.insnsSize() && !e.getValue().isMarked()) {
                    // In the writer that CREATED it: a label resolves through its
                    // own writer's instruction list, so marking it in a different
                    // one would resolve it to an unrelated offset.
                    ownerOf(e.getValue()).mark(e.getValue());
                }
            }
        }

        CodeWriter.Label labelFor(int dexOffset) {
            if (split != null) {
                int pi = split.partIndexOf(dexOffset);
                if (pi > cur.index) return portalFor(dexOffset);
                if (pi < cur.index) {
                    // MethodSplitter only cuts where no edge runs backwards, so
                    // this cannot happen for a plan it produced. Failing loudly
                    // makes translate() fall back to the unsplit translation
                    // rather than emit a branch into another method's bytes.
                    throw new TranslationException("backward cross-part branch to 0x"
                            + Integer.toHexString(dexOffset));
                }
            }
            CodeWriter.Label l = cur.labels.get(dexOffset);
            if (l == null) {
                l = out.newLabel("dex@0x" + Integer.toHexString(dexOffset));
                cur.labels.put(dexOffset, l);
                labelOwner.put(l, out);
            }
            return l;
        }

        /** The writer a label belongs to; the main one for anything created
         *  before outlining was in play. */
        CodeWriter ownerOf(CodeWriter.Label l) {
            CodeWriter w = labelOwner.get(l);
            return w == null ? mainWriter : w;
        }

        // ------------------------------------------------------- outlining

        /**
         * Enter or leave a hoisted region at {@code dexOffset}.
         *
         * Entering emits, in the ORIGINAL method, the parameter loads and the
         * `invokestatic`, then points {@link #out} at the synthetic method's
         * writer and emits the unpacking prologue that puts each parameter back
         * in the local slot the hoisted code reads it from. Leaving closes the
         * synthetic method with `return` and restores {@link #out}.
         *
         * The regions never nest and never overlap (MethodOutliner walks the
         * instruction stream once, in order), so one flag is enough state.
         */
        void switchWriterAt(int dexOffset) {
            if (out != mainWriter && dexOffset == chunkEnd) {
                out.op(CodeWriter.RETURN);
                out = mainWriter;
                chunkEnd = -1;
            }
            if (out != mainWriter) return;
            MethodOutliner.Chunk c = outline.startingAt(dexOffset);
            if (c == null) return;

            for (MethodOutliner.Param p : c.params) {
                out.varOp(loadOp(p.scalar), p.slot);
            }
            out.methodOp(CodeWriter.INVOKESTATIC, ownerName, c.name, c.descriptor, false);

            CodeWriter body = new CodeWriter(mw.owner.pool());
            // JVMS 4.7.3 requires max_locals to cover the argument slots even
            // where the body never reads them from their incoming positions.
            int argSlots = CodeWriter.argSlots(c.descriptor);
            body.setMaxLocals(argSlots);
            emitOutlinePrologue(body, c, argSlots);
            // ACC_SYNTHETIC because this member is not in the source; private
            // and static because nothing outside the class may call it and it
            // captures no receiver (a live `this` arrives as a parameter like
            // any other value).
            pendingSynthetics.add(new Object[]{ c.name, c.descriptor, body });
            out = body;
            chunkEnd = c.endOffset;
        }

        /**
         * Move each incoming parameter into the local slot the hoisted body
         * reads it from, VIA A DISJOINT SCRATCH AREA.
         *
         * The two-phase copy is not caution, it is required. A synthetic
         * method's parameters occupy slots 0..n-1, and the slots the body reads
         * are the ORIGINAL method's register slots, which for a static method
         * with no arguments also start at 0 -- so source and destination
         * overlap, and a naive `load p; store dest` sequence clobbers a
         * parameter it has not read yet. Measured on Telegram's
         * EmojiData.&lt;clinit&gt;, where `istore 5` overwrote the String[] in
         * parameter slot 5 four instructions before `aload 5` wanted it:
         *
         *   Bad local variable type: Type integer (current frame, locals[5])
         *   is not assignable to reference type
         *
         * Copying everything ABOVE both blocks first and only then writing the
         * destinations makes the move order irrelevant, which is the standard
         * answer to a parallel move with overlapping source and target sets.
         */
        void emitOutlinePrologue(CodeWriter body, MethodOutliner.Chunk c, int argSlots) {
            int scratch = argSlots;
            for (MethodOutliner.Param p : c.params) {
                scratch = Math.max(scratch, p.slot + widthOf(p.scalar));
            }
            int src = 0, tmp = scratch;
            for (MethodOutliner.Param p : c.params) {
                body.varOp(loadOp(p.scalar), src);
                body.varOp(storeOp(p.scalar), tmp);
                src += widthOf(p.scalar);
                tmp += widthOf(p.scalar);
            }
            tmp = scratch;
            for (MethodOutliner.Param p : c.params) {
                body.varOp(loadOp(p.scalar), tmp);
                body.varOp(storeOp(p.scalar), p.slot);
                tmp += widthOf(p.scalar);
            }
        }

        private static int widthOf(int scalar) {
            return (scalar == DexType.LONG || scalar == DexType.DOUBLE) ? 2 : 1;
        }


        // -------------------------------------------------- chain splitting

        /**
         * Emit the method as a forward-only chain of parts. See MethodSplitter.
         *
         * The loop is emitBody's, with one extra test: the moment an
         * instruction belongs to a later part, the current part is closed and a
         * new writer opened. Every branch that crossed the cut has already been
         * routed to a portal by labelFor, so nothing else in the emitter needs
         * to know a split is happening.
         */
        void runSplit(List<TypeInference.Insn> norm, List<TypeInference.TryBlock> tries) {
            List<MethodSplitter.Part> parts = split.parts();
            assignPartTries(tries);
            emitPrologue();
            TypeInference.Insn last = null;
            for (TypeInference.Insn insn : norm) {
                int pi = split.partIndexOf(insn.offset);
                if (pi != cur.index) {
                    MethodSplitter.Part next = parts.get(pi);
                    finishPart(last, next.startOffset);
                    beginPart(next, tries);
                    last = null;
                }
                Integer copied = split.clonedHandlerContaining(insn.offset);
                if (copied != null && !partUsesHandler(copied)) {
                    // A cloned handler whose own part has no range using it:
                    // every range that does has its copy, and nothing else can
                    // enter the block, so the original would be dead code --
                    // and dead code after a throw still needs a frame, which a
                    // handler with no row in this part's table cannot supply.
                    bindRangeEndsUpTo(insn.offset);
                    last = insn;
                    continue;
                }
                out.mark(labelFor(insn.offset));
                bindRangeEndsUpTo(insn.offset);
                last = insn;
                if (!types.isReachable(insn.offset)) continue;
                TypeInference.InsnTypes it = types.typesFor(insn.offset);
                try {
                    emitOne(insn, it);
                } catch (TranslationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new TranslationException("at DEX offset 0x"
                        + Integer.toHexString(insn.offset) + " opcode 0x"
                        + Integer.toHexString(insn.opcode), e);
                }
            }
            finishPart(last, -1);
        }

        /** The try blocks whose range starts in the current part. MethodSplitter
         *  guarantees a range and its handlers never straddle a cut, so this
         *  partitions them. */
        void assignPartTries(List<TypeInference.TryBlock> tries) {
            List<TypeInference.TryBlock> mine = new ArrayList<>();
            for (TypeInference.TryBlock t : tries) {
                if (split == null || split.partIndexOf(t.startOffset) == cur.index) mine.add(t);
            }
            cur.tries = mine;
            for (TypeInference.TryBlock t : mine) cur.pendingRangeEnds.add(t.endOffset);
        }

        /** Handler offsets of the current part's ranges, ascending. */
        java.util.TreeSet<Integer> partHandlerOffsets() {
            java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
            for (TypeInference.TryBlock t : cur.tries) {
                for (TypeInference.Catch c : t.catches) {
                    if (handlerTypes.containsKey(c.handlerOffset)) out.add(c.handlerOffset);
                }
            }
            return out;
        }

        /**
         * Lay down this part's copy of every cloned handler its ranges use but
         * another part holds (MethodSplitter.CLONE_HANDLERS). Runs after
         * bindRangeEndsUpTo and the portals, so a copy sits outside every
         * range of the part, as a handler body must not be covered by the
         * range it serves. The planner guarantees the block is a straight line
         * ending in throw or return that only an exception edge enters, so the
         * copy needs no label but its first, and the frame there is the
         * handler's own (frameFor via cloneLabels / handlerEntryTypes).
         */
        void emitHandlerClones() {
            if (split == null) return;
            java.util.TreeSet<Integer> need = new java.util.TreeSet<>();
            for (TypeInference.TryBlock t : cur.tries) {
                for (TypeInference.Catch c : t.catches) {
                    int h = c.handlerOffset;
                    if (split.cloneBlock(h) == null || !types.isReachable(h)) continue;
                    if (split.partIndexOf(h) != cur.index) need.add(h);
                }
            }
            if (need.isEmpty()) return;
            Map<Integer, TypeInference.Insn> byOffset = new HashMap<>();
            for (TypeInference.Insn in : lastNorm) byOffset.put(in.offset, in);
            for (int h : need) {
                CodeWriter.Label l = out.newLabel("handler-copy@0x" + Integer.toHexString(h));
                labelOwner.put(l, out);
                out.mark(l);
                cur.cloneLabels.put(h, l);
                cur.handlerEntryTypes.put(h, handlerTypes.get(h));
                for (int o : split.cloneBlock(h)) {
                    TypeInference.Insn insn = byOffset.get(o);
                    if (insn == null) {
                        throw new TranslationException("handler copy: no instruction at 0x"
                                + Integer.toHexString(o));
                    }
                    emitOne(insn, types.typesFor(o));
                }
            }
        }

        /** True when a range of the CURRENT part names this handler. */
        boolean partUsesHandler(int handlerOffset) {
            for (TypeInference.TryBlock t : cur.tries) {
                for (TypeInference.Catch c : t.catches) {
                    if (c.handlerOffset == handlerOffset) return true;
                }
            }
            return false;
        }

        /**
         * Close the part being emitted: bind what is still open, then lay down
         * the pieces that must sit OUTSIDE every try range -- the handler stubs
         * and the portals.
         *
         * The portals' position is load-bearing, not cosmetic. A portal calls
         * into the next part, and an `invokestatic` inside a protected range
         * would let a handler in THIS part catch an exception thrown by code
         * that the range never covered. Emitting them after bindRangeEndsUpTo
         * puts them past every range end.
         */
        void finishPart(TypeInference.Insn last, int nextStart) {
            if (nextStart >= 0 && last != null && types.isReachable(last.offset)
                    && fallsThrough(last)) {
                // The next part's code is not physically next any more, so the
                // fall-through edge has to be spelled out. nextStart rather than
                // last.nextOffset: a dropped data payload can sit between them,
                // and control reaches the next EMITTED instruction.
                out.jump(CodeWriter.GOTO, labelFor(nextStart));
            }
            bindRangeEndsUpTo(Integer.MAX_VALUE);
            markEndOfCode();
            emitHandlerStubs(partHandlerOffsets());
            emitPortals();
            emitHandlerClones();
            emitTryCatch(cur.tries);
            emitLineNumbers(cur.labels);
            cur.writer.setFrameProvider(cur);
        }

        boolean fallsThrough(TypeInference.Insn in) {
            for (int s : normalSuccessors(in)) if (s == in.nextOffset) return true;
            return false;
        }

        /** Open the next part: a fresh writer, its own dispatch prologue. */
        void beginPart(MethodSplitter.Part part, List<TypeInference.TryBlock> tries) {
            CodeWriter body = new CodeWriter(mw.owner.pool());
            // A part is static, so its initial frame is just its own parameters.
            body.setInitialFrame(StackMapWriter.initialFrame(ownerName, part.name,
                                                             part.descriptor, true));
            body.setMaxLocals(part.argSlots);
            cur = new PartState(part.index, body, part);
            partStates.add(cur);
            out = body;
            pendingSynthetics.add(new Object[]{ part.name, part.descriptor, body });
            assignPartTries(tries);
            emitPartPrologue(part);
        }

        /**
         * A part's prologue: park the arguments out of harm's way, give every
         * register slot a definite value, then dispatch on the entry id.
         *
         * The copy to scratch is required, not defensive. A part's arguments
         * occupy slots 0..argSlots-1 and the body's register slots start at the
         * ORIGINAL method's argument count, so the two blocks overlap whenever
         * a part takes more arguments than the method did -- and
         * zeroInitRegisterSlots would then wipe arguments it has not read yet.
         * Copying everything above both blocks first makes the order
         * irrelevant, which is the standard answer to a parallel move with
         * overlapping source and destination sets. Same reason
         * emitOutlinePrologue does it.
         */
        void emitPartPrologue(MethodSplitter.Part part) {
            int scratchBase = Math.max(part.argSlots, locals.slotCount());
            // A receiver-carrying part (MethodSplitter, THE <init> SPILL) keeps
            // the object under construction in slot 0, which no register slot
            // overlaps (they start at the constructor's own argument count),
            // and its entry id follows it.
            int idSlot = part.receiver ? 1 : 0;
            out.varOp(CodeWriter.ILOAD, idSlot);
            out.varOp(CodeWriter.ISTORE, scratchBase);
            int src = idSlot + 1, dst = scratchBase + 1;
            // A spilled part's values are not arguments: each entry stub reads
            // its own from the part's static fields (MethodSplitter.CLINIT_SPILL),
            // or from the receiver's instance fields (THE <init> SPILL).
            if (!part.spilled) {
                for (MethodSplitter.Param p : part.params) {
                    out.varOp(loadOp(p.scalar), src);
                    out.varOp(storeOp(p.scalar), dst);
                    p.scratchSlot = dst;
                    src += p.width();
                    dst += p.width();
                }
            }
            out.setMaxLocals(dst);
            zeroInitRegisterSlots();

            if (part.entries.isEmpty()) return;   // unreachable part; fall into the body

            CodeWriter.Label[] stubs = new CodeWriter.Label[part.entries.size()];
            for (int i = 0; i < stubs.length; i++) {
                stubs[i] = out.newLabel("entry$" + i);
                labelOwner.put(stubs[i], out);
            }
            out.varOp(CodeWriter.ILOAD, scratchBase);
            out.tableSwitch(0, stubs.length - 1, stubs[0], stubs);

            StackMapWriter.Frame stubFrame = partStubFrame(part, scratchBase, dst);
            for (int i = 0; i < stubs.length; i++) {
                MethodSplitter.Entry e = part.entries.get(i);
                out.mark(stubs[i]);
                // Explicit, because the state here is not any DEX program
                // point's: the register slots hold their zero-initialized
                // values and the arguments are still only in scratch.
                out.putFrame(stubs[i], stubFrame);
                for (MethodSplitter.Param p : e.params) {
                    if (part.spilled && part.receiver) {
                        // The instance-field twin of the getstatic below: the
                        // fields of the object under construction, in slot 0.
                        out.varOp(CodeWriter.ALOAD, 0);
                        out.fieldOp(CodeWriter.GETFIELD, ownerName, p.spillField, p.descriptor);
                        out.varOp(storeOp(p.scalar), p.slot);
                        if (p.scalar == DexType.OBJ) {
                            out.varOp(CodeWriter.ALOAD, 0);
                            out.op(CodeWriter.ACONST_NULL);
                            out.fieldOp(CodeWriter.PUTFIELD, ownerName, p.spillField,
                                        p.descriptor);
                        }
                        continue;
                    }
                    if (part.spilled) {
                        // getstatic of a field typed exactly p.descriptor: the
                        // value arrives with the type the target frame declares,
                        // with no cast. The reference is then dropped from the
                        // field so the class does not keep alive what the
                        // original method would have let die.
                        out.fieldOp(CodeWriter.GETSTATIC, ownerName, p.spillField, p.descriptor);
                        out.varOp(storeOp(p.scalar), p.slot);
                        if (p.scalar == DexType.OBJ) {
                            out.op(CodeWriter.ACONST_NULL);
                            out.fieldOp(CodeWriter.PUTSTATIC, ownerName, p.spillField,
                                        p.descriptor);
                        }
                        continue;
                    }
                    out.varOp(loadOp(p.scalar), p.scratchSlot);
                    out.varOp(storeOp(p.scalar), p.slot);
                }
                for (int[] ns : e.nullSlots) {
                    out.op(CodeWriter.ACONST_NULL);
                    out.varOp(CodeWriter.ASTORE, ns[0]);
                }
                out.jump(CodeWriter.GOTO, labelFor(e.dexOffset));
            }
        }

        /**
         * The verifier state shared by every entry stub.
         *
         * Register slots carry what zeroInitRegisterSlots just put there, which
         * is assignable to whatever the target block's frame declares: null
         * merges with any reference type (JVMS 4.10.1.2) and the primitive
         * zeros are already the right verification type. The scratch copies
         * carry their declared parameter types. Everything else is top.
         */
        StackMapWriter.Frame partStubFrame(MethodSplitter.Part part, int scratchBase, int end) {
            StackMapWriter.VType[] slots = new StackMapWriter.VType[end];
            Arrays.fill(slots, StackMapWriter.VType.TOP);
            for (Locals.Entry e : locals.entries()) {
                switch (e.scalar) {
                    case DexType.INT:    slots[e.slot] = StackMapWriter.VType.INTEGER; break;
                    case DexType.FLOAT:  slots[e.slot] = StackMapWriter.VType.FLOAT; break;
                    case DexType.LONG:   slots[e.slot] = StackMapWriter.VType.LONG; break;
                    case DexType.DOUBLE: slots[e.slot] = StackMapWriter.VType.DOUBLE; break;
                    case DexType.OBJ:    slots[e.slot] = StackMapWriter.VType.NULL; break;
                    default: break;
                }
            }
            slots[scratchBase] = StackMapWriter.VType.INTEGER;
            if (part.receiver) slots[0] = StackMapWriter.VType.object(ownerName);
            if (!part.spilled) {
                for (MethodSplitter.Param p : part.params) {
                    slots[p.scratchSlot] = StackMapWriter.VType.forDescriptor(p.descriptor);
                }
            }
            return new StackMapWriter.Frame(StackMapWriter.compressSlots(slots),
                                            new StackMapWriter.VType[0]);
        }

        /** A branch target that lives in a LATER part: the call site that hands
         *  control over, emitted at the end of this part. */
        CodeWriter.Label portalFor(int dexOffset) {
            CodeWriter.Label l = cur.portals.get(dexOffset);
            if (l == null) {
                l = out.newLabel("portal@0x" + Integer.toHexString(dexOffset));
                cur.portals.put(dexOffset, l);
                labelOwner.put(l, out);
            }
            return l;
        }

        /**
         * One call site per cross-part target: push the entry id and the values
         * that entry restores, call, and return whatever the callee returned.
         *
         * A parameter this entry does not use gets a zero or a null. It is
         * unread on the far side -- the entry stub only stores the ones its own
         * list names -- so the value is never observable; it exists because a
         * descriptor is fixed for the whole part.
         */
        void emitPortals() {
            for (Map.Entry<Integer, CodeWriter.Label> pe : cur.portals.entrySet()) {
                int target = pe.getKey();
                MethodSplitter.Part tp = split.partOf(target);
                MethodSplitter.Entry en = split.entryFor(target);
                if (en == null || tp.name == null) {
                    throw new TranslationException("no chain entry for 0x"
                            + Integer.toHexString(target));
                }
                java.util.Set<MethodSplitter.Param> mine =
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                mine.addAll(en.params);

                out.mark(pe.getValue());
                if (tp.spilled && tp.receiver) {
                    // THE <init> SPILL: the same writes, into the object under
                    // construction. Slot 0 holds it in every member of the
                    // chain: the constructor's own `this`, initialized wherever
                    // a portal is (every cut sits after super()), or a part's
                    // receiver argument.
                    for (MethodSplitter.Param p : en.params) {
                        out.varOp(CodeWriter.ALOAD, 0);
                        out.varOp(loadOp(p.scalar), p.slot);
                        out.fieldOp(CodeWriter.PUTFIELD, ownerName, p.spillField, p.descriptor);
                    }
                    out.varOp(CodeWriter.ALOAD, 0);
                    out.pushInt(en.id);
                } else if (tp.spilled) {
                    // Only the values THIS entry restores are written; the entry
                    // stub reads exactly those, so the part's other fields are
                    // never read on this path.
                    for (MethodSplitter.Param p : en.params) {
                        out.varOp(loadOp(p.scalar), p.slot);
                        out.fieldOp(CodeWriter.PUTSTATIC, ownerName, p.spillField, p.descriptor);
                    }
                    out.pushInt(en.id);
                } else {
                    if (tp.receiver) out.varOp(CodeWriter.ALOAD, 0);
                    out.pushInt(en.id);
                    for (MethodSplitter.Param p : tp.params) {
                        if (mine.contains(p)) out.varOp(loadOp(p.scalar), p.slot);
                        else pushDefaultFor(p.descriptor);
                    }
                }
                out.methodOp(CodeWriter.INVOKESTATIC, ownerName, tp.name, tp.descriptor, false);
                out.op(returnOpForDescriptor(method.proto().returnType()));
            }
        }

        void pushDefaultFor(String desc) {
            switch (desc.charAt(0)) {
                case 'I': case 'S': case 'B': case 'C': case 'Z': out.pushInt(0); break;
                case 'F': out.pushFloatBits(0); break;
                case 'J': out.pushLong(0L); break;
                case 'D': out.pushDoubleBits(0L); break;
                default:  out.op(CodeWriter.ACONST_NULL); break;
            }
        }

        static int returnOpForDescriptor(String desc) {
            switch (desc.charAt(0)) {
                case 'V': return CodeWriter.RETURN;
                case 'I': case 'S': case 'B': case 'C': case 'Z': return CodeWriter.IRETURN;
                case 'F': return CodeWriter.FRETURN;
                case 'J': return CodeWriter.LRETURN;
                case 'D': return CodeWriter.DRETURN;
                default:  return CodeWriter.ARETURN;
            }
        }

        /**
         * A chain plan for this method, or null when it fits or cannot be cut.
         * Valid only right after run() on an UNSPLIT translation. `spill` lets
         * a part over the argument-slot ceiling take its values through static
         * fields, and only where {@link #spillEligible} admits it.
         */
        MethodSplitter.Plan planSplitting(int targetBytes, boolean allowWiden, boolean spill) {
            int length = mainWriter.measuredLength();
            if (length <= MethodSplitter.codeLimit()) return null;
            Map<Integer, Integer> bytecodeOf = new HashMap<>(cur.labels.size() * 2);
            for (Map.Entry<Integer, CodeWriter.Label> e : cur.labels.entrySet()) {
                CodeWriter.Label l = e.getValue();
                if (l.isMarked() && l.offset >= 0) bytecodeOf.put(e.getKey(), l.offset);
            }
            java.util.Set<String> taken = new HashSet<>();
            for (DexMethod m : method.declaringClass().methods()) taken.add(m.name());
            java.util.Set<String> takenFields = new HashSet<>();
            for (DexField f : method.declaringClass().fields()) takenFields.add(f.name());
            // The caller asks for the spill only on its last-resort ladder, and
            // only for a method spillEligible() admits.
            boolean spillAllowed = spill && spillEligible();
            return MethodSplitter.compute(lastNorm, types, locals, allTries, deferredNew,
                    thisUninit,
                    off -> { Integer b = bytecodeOf.get(off); return b == null ? -1 : b; },
                    length, targetBytes, allowWiden, method.proto().returnType(), taken,
                    methodTag(), spillAllowed, takenFields,
                    ctorSpillEligible() ? "L" + ownerName + ";" : null);
        }

        /** May this method's split parts take their values through fields?
         *  Static fields are sound only for a class initializer (one
         *  execution, one thread, under the init lock, JVMS 5.5) and only where
         *  the class may declare a private non-final static: not an interface
         *  (JVMS 4.5). See MethodSplitter.CLINIT_SPILL. A constructor uses
         *  instance fields of the object it constructs instead; see
         *  {@link #ctorSpillEligible}. */
        boolean spillEligible() {
            return (MethodSplitter.CLINIT_SPILL && "<clinit>".equals(method.name())
                    && !method.declaringClass().isInterface())
                || ctorSpillEligible();
        }

        /** May this method's split parts take their values through INSTANCE
         *  fields of the object under construction? Only an instance
         *  constructor of a class: it runs once per object, after super() --
         *  where every cut sits -- the object is initialized, and no other
         *  thread or nested construction can share its fields. See
         *  MethodSplitter's THE &lt;init&gt; SPILL. */
        boolean ctorSpillEligible() {
            return MethodSplitter.CTOR_SPILL && !isStatic && "<init>".equals(method.name())
                    && !method.declaringClass().isInterface();
        }

        /** Attach every synthetic method this translation produced to the class.
         *  Called only once the whole method has translated without throwing. */
        void commitSyntheticMethods() {
            // Measure BEFORE attaching anything. A part that still overflows has
            // to be discovered here, where the caller can retry with a smaller
            // budget or fall back, rather than at serialization time with half
            // a chain already on the class.
            if (split != null) {
                int over = mainWriter.measuredLength();
                if (over > MethodSplitter.codeLimit()) {
                    throw new CodeWriter.CodeLengthExceeded(over);
                }
                for (Object[] s : pendingSynthetics) {
                    int len = ((CodeWriter) s[2]).measuredLength();
                    if (len > MethodSplitter.codeLimit()) {
                        throw new CodeWriter.CodeLengthExceeded(len);
                    }
                }
            }
            for (String[] w : initializerHelperPuts) {
                mw.owner.noteInitializerHelperPut(w[0], w[1]);
            }
            if (split != null) {
                // Declared only now, once the whole chain has translated and
                // measured, so a failed attempt leaves no field behind either.
                // A constructor's spill lives in the object it constructs, and
                // is transient so serialization never sees it (MethodSplitter,
                // THE <init> SPILL).
                int access = split.instanceSpill()
                        ? ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_TRANSIENT
                          | ClassFileWriter.ACC_SYNTHETIC
                        : ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_STATIC
                          | ClassFileWriter.ACC_SYNTHETIC;
                for (String[] f : split.spillFields()) {
                    mw.owner.addField(access, f[0], f[1]);
                }
            }
            // A part of an initializer is marked as one, so the foreign-write
            // rule leaves its writes to initializerHelperPuts above. See
            // ClassFileWriter.unfinalFieldsWrittenOutsideInitializers.
            String initializer = ("<clinit>".equals(method.name()) || "<init>".equals(method.name()))
                    ? method.name() : null;
            for (Object[] s : pendingSynthetics) {
                mw.owner.addMethod(ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_STATIC
                                     | ClassFileWriter.ACC_SYNTHETIC,
                                     (String) s[0], (String) s[1])
                          .setCode((CodeWriter) s[2])
                          .markInitializerHelper(initializer);
            }
        }

        /**
         * A plan for splitting this method, or null when it fits or cannot be
         * split. Valid only right after run().
         */
        MethodOutliner.Plan planOutlining() {
            int length = mainWriter.measuredLength();
            if (length <= MethodOutliner.codeLimit()) return null;
            Map<Integer, Integer> bytecodeOf = new HashMap<>(cur.labels.size() * 2);
            for (Map.Entry<Integer, CodeWriter.Label> e : cur.labels.entrySet()) {
                CodeWriter.Label l = e.getValue();
                if (l.isMarked() && l.offset >= 0) bytecodeOf.put(e.getKey(), l.offset);
            }
            java.util.Set<String> taken = new HashSet<>();
            for (DexMethod m : method.declaringClass().methods()) taken.add(m.name());
            String tag = sanitizeForMethodName(method.name());
            return MethodOutliner.compute(lastNorm, types, locals, !code.tries().isEmpty(),
                    deferredNew,
                    off -> { Integer b = bytecodeOf.get(off); return b == null ? -1 : b; },
                    length, taken, tag);
        }

        /**
         * The name fragment a synthetic method built for THIS method is named
         * after, unique within the class.
         *
         * The method name alone is not enough. Two OVERLOADS are two methods
         * with one name, they are translated by two independent calls that
         * cannot see each other's synthetics, and each builds its "already
         * taken" set from the class's DEX method NAMES only -- so both would
         * mint dex2jvm$part$foo$0, and if the two chains happened to want the
         * same descriptor the class would carry a duplicate method and fail to
         * load. Disambiguating by position is exact where a hash would only be
         * probable.
         */
        String methodTag() {
            String base = sanitizeForMethodName(method.name());
            List<DexMethod> all = method.declaringClass().methods();
            int seen = 0, mine = -1;
            for (int i = 0; i < all.size(); i++) {
                if (!all.get(i).name().equals(method.name())) continue;
                if (all.get(i) == method) mine = seen;
                seen++;
            }
            return seen > 1 && mine >= 0 ? base + "_" + mine : base;
        }

        /** A method name is not a legal identifier fragment ("&lt;clinit&gt;"), so
         *  the characters a class file forbids in a name (JVMS 4.2.2) go. */
        private static String sanitizeForMethodName(String name) {
            StringBuilder sb = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                sb.append(c == '<' || c == '>' || c == '.' || c == ';' || c == '['
                          || c == '/' ? '_' : c);
            }
            return sb.toString();
        }

        // ------------------------------------------------------- prologue

        /**
         * Copy the incoming parameters from the JVM's mandated slots into the
         * register slots the body reads. Dalvik places parameters in the last
         * `insSize` registers, so register (registersSize - insSize + k) holds
         * parameter word k.
         */
        void emitPrologue() {
            zeroInitRegisterSlots();
            int firstParamReg = code.registersSize() - code.insSize();
            int slot = 0;
            int reg = firstParamReg;
            String[] spaced = spacedParams();
            for (int i = 0; i < spaced.length; i++) {
                String desc = spaced[i];
                if (desc == null) { continue; }        // wide high half: no separate copy
                int scalar = scalarOf(desc);
                int dest = locals.slotOrMinusOne(reg, scalar);
                if (dest >= 0) {
                    out.varOp(loadOp(scalar), slot);
                    out.varOp(storeOp(scalar), dest);
                }
                boolean wide = isWideDesc(desc);
                slot += wide ? 2 : 1;
                reg += wide ? 2 : 1;
            }
        }

        /**
         * Give every register slot a definite value before the body runs.
         *
         * ART and the JVM disagree about exception edges. ART only routes a
         * handler edge from an instruction that can THROW, so DEX code may
         * legally write a register with a non-throwing `const` inside a try and
         * read it in the handler -- on any real path the write happened first.
         * JVMS 4.10.1.6 applies instructionSatisfiesHandlers to EVERY
         * instruction in the protected range, throwing or not, so to the JVM
         * that same local is `top` on entry to the handler:
         *
         *   Type top (current frame, locals[5]) is not assignable to
         *   'android/app/Activity' (stack map, locals[5])
         *
         * Definite assignment here makes the JVM's conservative merge agree
         * with ART's precise one: the pre-range value is a zero/null rather
         * than `top`, and null merges with any reference to that reference
         * (JVMS 4.10.1.2), so the handler frame keeps its type.
         *
         * The alternative -- adding handler edges from every instruction so our
         * own analysis matches the JVM's -- was measured and reverted: it makes
         * registers dead at handlers that the handler genuinely reads, and
         * costs 5-11 classes per app to "no local for vN scalar none". Making
         * the claim TRUE beats weakening it.
         *
         * Runs before the parameter copies, which overwrite the slots they own.
         */
        void zeroInitRegisterSlots() {
            for (Locals.Entry e : locals.entries()) {
                switch (e.scalar) {
                    case DexType.INT:    out.pushInt(0); break;
                    case DexType.FLOAT:  out.pushFloatBits(0); break;
                    case DexType.LONG:   out.pushLong(0L); break;
                    case DexType.DOUBLE: out.pushDoubleBits(0L); break;
                    case DexType.OBJ:    out.op(CodeWriter.ACONST_NULL); break;
                    default: continue;
                }
                out.varOp(storeOp(e.scalar), e.slot);
            }
        }

        // ----------------------------------------------------------- body

        void emitBody(List<TypeInference.Insn> norm) {
            for (TypeInference.Insn insn : norm) {
                // Redirect emission into (or back out of) a hoisted region
                // BEFORE anything for this instruction is written, so the
                // instruction's own label is marked in the writer that will
                // hold its code. See MethodOutliner.
                if (outline != null) switchWriterAt(insn.offset);
                // An unreachable instruction has no inferred types, so emitting
                // it with guessed ones would be worse than not emitting it: the
                // verifier would reject the guess. Dead DEX code is common in
                // R8 output, and the JVM never executes it, so a bare label is
                // enough to keep branch targets resolvable.
                out.mark(labelFor(insn.offset));
                // After the mark, so a range end that coincides with this
                // instruction reuses its label instead of adding a second one.
                bindRangeEndsUpTo(insn.offset);
                if (!types.isReachable(insn.offset)) continue;
                TypeInference.InsnTypes it = types.typesFor(insn.offset);
                try {
                    emitOne(insn, it);
                } catch (TranslationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new TranslationException("at DEX offset 0x"
                        + Integer.toHexString(insn.offset) + " opcode 0x"
                        + Integer.toHexString(insn.opcode), e);
                }
            }
        }

        void emitOne(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            int family = TypeInference.familyOf(insn.opcode);
            switch (family) {
                case TypeInference.F_NOP:
                    break;

                case TypeInference.F_MOVE:
                case TypeInference.F_MOVE_WIDE:
                    emitMove(insn, it);
                    break;

                case TypeInference.F_MOVE_RESULT:
                    // The value is already on the stack: the preceding invoke /
                    // filled-new-array left it there, or the handler entry left
                    // the exception there. Just store it.
                    storeDefs(it);
                    break;

                case TypeInference.F_RETURN:
                    emitReturn(insn, it);
                    break;

                case TypeInference.F_CONST32:
                case TypeInference.F_CONST64:
                    emitConst(insn, it, family == TypeInference.F_CONST64);
                    break;

                case TypeInference.F_CONST_STRING:
                    emitStringConstant(dex.string((int) insn.args[1]));
                    storeDefs(it);
                    break;

                case TypeInference.F_CONST_CLASS: {
                    // Dalvik bytecode spec, const-class (1c 21c): "In the case
                    // where the indicated type is primitive, this will store a
                    // reference to the primitive type's degenerate class."
                    // (source.android.com/docs/core/runtime/dalvik-bytecode.)
                    // d8 emits exactly that for a multi-dimensional primitive
                    // array: `new boolean[r][c]` -> const-class Z +
                    // Array.newInstance(Class, int[]). A JVM class constant
                    // cannot name a primitive (JVMS 4.4.1: CONSTANT_Class names
                    // a class, interface or ARRAY type), so `ldc Z` resolved
                    // a class literally named "Z" and threw
                    // NoClassDefFoundError: Z (measured: Klooni's play button,
                    // Piece.<init> -> GameScreen never opened). javac's own
                    // spelling of boolean.class is the wrapper's TYPE field;
                    // emit that. Same stack effect (+1 reference) as the ldc.
                    String desc = dex.typeDescriptor((int) insn.args[1]);
                    String wrapper = primitiveWrapper(desc);
                    if (wrapper != null) {
                        out.fieldOp(CodeWriter.GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
                    } else {
                        out.ldcClass(DexFile.internalName(desc));
                    }
                    storeDefs(it);
                    break;
                }

                case TypeInference.F_MONITOR_ENTER:
                    pushUse(it, 0);
                    out.op(CodeWriter.MONITORENTER);
                    break;

                case TypeInference.F_MONITOR_EXIT:
                    pushUse(it, 0);
                    out.op(CodeWriter.MONITOREXIT);
                    break;

                case TypeInference.F_CHECK_CAST:
                    // Dalvik's check-cast both checks AND retypes the register,
                    // so the result must be stored back, not discarded.
                    pushUse(it, 0);
                    out.typeOp(CodeWriter.CHECKCAST,
                        DexFile.internalName(dex.typeDescriptor((int) insn.args[1])));
                    if (it.defs.length > 0) storeDefs(it); else out.op(CodeWriter.POP);
                    break;

                case TypeInference.F_INSTANCE_OF:
                    pushUse(it, 0);
                    out.typeOp(CodeWriter.INSTANCEOF,
                        DexFile.internalName(dex.typeDescriptor((int) insn.args[2])));
                    storeDefs(it);
                    break;

                case TypeInference.F_ARRAY_LEN:
                    pushUse(it, 0);
                    out.op(CodeWriter.ARRAYLENGTH);
                    storeDefs(it);
                    break;

                case TypeInference.F_NEW_INSTANCE: {
                    CodeWriter.Label here = out.newLabel();
                    out.mark(here);
                    newLabels.put(insn.offset, here);
                    out.typeOp(CodeWriter.NEW,
                        DexFile.internalName(dex.typeDescriptor((int) insn.args[1])));
                    if (deferredNew.containsKey(insn.offset)) {
                        // javac's shape: keep the uninitialized reference on the
                        // OPERAND STACK until its <init>, and never in a local.
                        // See computeDeferredNew.
                        out.op(CodeWriter.DUP);
                    } else {
                        storeDefs(it);
                    }
                    break;
                }

                case TypeInference.F_NEW_ARRAY:
                    pushUse(it, 0);
                    emitNewArray(dex.typeDescriptor((int) insn.args[2]));
                    storeDefs(it);
                    break;

                case TypeInference.F_FILLED_NEW_ARRAY:
                    emitFilledNewArray(insn, it);
                    break;

                case TypeInference.F_FILL_ARRAY_DATA:
                    emitFillArrayData(insn, it);
                    break;

                case TypeInference.F_THROW:
                    pushUse(it, 0);
                    out.op(CodeWriter.ATHROW);
                    break;

                case TypeInference.F_GOTO:
                    out.jump(CodeWriter.GOTO, labelFor((int) insn.args[0]));
                    break;

                case TypeInference.F_SWITCH:
                    emitSwitch(insn, it);
                    break;

                case TypeInference.F_CMP:
                    emitCmp(insn, it);
                    break;

                case TypeInference.F_IF:
                    emitIf(insn, it);
                    break;

                case TypeInference.F_IFZ:
                    emitIfZero(insn, it);
                    break;

                case TypeInference.F_ARRAY_GET: {
                    pushUse(it, 0);            // array
                    pushUse(it, 1);            // index
                    int load = arrayLoadOp(insn.opcode, it.uses[0].type);
                    out.op(load);
                    if (NULL_AGET && it.uses[0].definitelyNull && it.defs.length > 0) {
                        // A PROVABLY NULL array: ART's verifier types the result
                        // Zero, legal as every 32-bit reading at once, so our
                        // def can name int, float AND reference slots. The JVM
                        // value on the stack is whatever the chosen xaload
                        // produces (an int for saload), and `dup; fstore` of it
                        // fails the split verifier:
                        //   com/moat/analytics/mobile/cha/g @290: fstore
                        //   Type integer (current frame, stack[1]) is not
                        //   assignable to float
                        // The load always throws NullPointerException, on ART and
                        // here, so no store below it ever runs. Dropping the
                        // loaded value and storing a typed zero per slot keeps
                        // exactly that behaviour and gives each slot the type
                        // its frame declares (the same zeros the prologue stores).
                        out.op(load == CodeWriter.LALOAD || load == CodeWriter.DALOAD
                                ? CodeWriter.POP2 : CodeWriter.POP);
                        storeZeroDefs(it);
                    } else {
                        storeDefs(it);
                    }
                    break;
                }

                case TypeInference.F_ARRAY_PUT:
                    // TypeInference emits uses in JVM PUSH order (array, index,
                    // value), not in DEX argument order, so push them straight
                    // through. Reordering them here put the array reference in
                    // the value slot, which HotSpot's verifier reported as "Bad
                    // type on operand stack".
                    pushUses(it);
                    out.op(arrayStoreOp(insn.opcode, it.uses[0].type));
                    break;

                case TypeInference.F_INSTANCE_GET: {
                    DexFile.FieldRef f = dex.fieldRef((int) insn.args[2]);
                    pushUse(it, 0);
                    out.fieldOp(CodeWriter.GETFIELD, DexFile.internalName(f.declaringClass()),
                        f.name(), f.type());
                    storeDefs(it);
                    break;
                }

                case TypeInference.F_INSTANCE_PUT: {
                    DexFile.FieldRef f = dex.fieldRef((int) insn.args[2]);
                    pushUses(it);              // objectref, then value
                    String iowner = DexFile.internalName(f.declaringClass());
                    out.fieldOp(CodeWriter.PUTFIELD, iowner, f.name(), f.type());
                    // The <init> twin of the F_STATIC_PUT case: HotSpot's
                    // version-53 rule also refuses a putfield of a final
                    // instance field from any method but <init>, and an
                    // outlined or split constructor writes from a helper.
                    if (out != mainWriter && iowner.equals(ownerName)
                            && "<init>".equals(method.name())) {
                        initializerHelperPuts.add(new String[]{ f.name(), f.type() });
                    }
                    break;
                }

                case TypeInference.F_STATIC_GET: {
                    DexFile.FieldRef f = dex.fieldRef((int) insn.args[1]);
                    out.fieldOp(CodeWriter.GETSTATIC, DexFile.internalName(f.declaringClass()),
                        f.name(), f.type());
                    storeDefs(it);
                    break;
                }

                case TypeInference.F_STATIC_PUT: {
                    DexFile.FieldRef f = dex.fieldRef((int) insn.args[1]);
                    pushUse(it, 0);
                    String fowner = DexFile.internalName(f.declaringClass());
                    out.fieldOp(CodeWriter.PUTSTATIC, fowner, f.name(), f.type());
                    // A write from a synthetic helper of <clinit> (an outlined
                    // chunk or a chain part) rather than from <clinit> itself.
                    // See ClassFileWriter.unfinalFieldsWrittenByInitializerHelpers.
                    if (out != mainWriter && fowner.equals(ownerName)
                            && "<clinit>".equals(method.name())) {
                        initializerHelperPuts.add(new String[]{ f.name(), f.type() });
                    }
                    break;
                }

                case TypeInference.F_INVOKE_VIRTUAL:
                case TypeInference.F_INVOKE_SUPER:
                case TypeInference.F_INVOKE_DIRECT:
                case TypeInference.F_INVOKE_STATIC:
                case TypeInference.F_INVOKE_INTERFACE:
                    emitInvoke(insn, it, family);
                    break;

                case TypeInference.F_INVOKE_CUSTOM:
                    emitInvokeCustom(insn, it);
                    break;

                case TypeInference.F_INVOKE_POLYMORPHIC:
                    emitInvokePolymorphic(insn, it);
                    break;

                case TypeInference.F_CONST_METHOD_HANDLE:
                    out.ldcRaw(methodHandleConstant(dex.methodHandle((int) insn.args[1])));
                    storeDefs(it);
                    break;

                case TypeInference.F_CONST_METHOD_TYPE:
                    out.ldcRaw(out.pool().methodType(dex.proto((int) insn.args[1]).descriptor()));
                    storeDefs(it);
                    break;

                case TypeInference.F_UNARY_OP:
                    pushUse(it, 0);
                    emitUnary(insn.opcode);
                    storeDefs(it);
                    break;

                case TypeInference.F_BINARY_OP:
                    pushUse(it, 0);
                    pushUse(it, 1);
                    emitBinary(insn.opcode);
                    storeDefs(it);
                    break;

                case TypeInference.F_BINARY_OP_CONST:
                    emitBinaryConst(insn, it);
                    break;

                default:
                    throw new TranslationException("unhandled family " + family
                        + " for opcode 0x" + Integer.toHexString(insn.opcode));
            }
        }

        // ------------------------------------------------- family helpers

        void emitMove(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            // A move must be replayed once per scalar reading the destination
            // needs, because each reading lives in its own local. The source
            // register holds the same value under each of those readings.
            int src = (int) insn.args[1];
            for (TypeInference.Def d : it.defs) {
                for (int scalar : DexType.scalarBits(d.scalars)) {
                    int from = locals.slotOrMinusOne(src, scalar);
                    int to = locals.slotOrMinusOne(d.register, scalar);
                    if (from < 0 || to < 0) continue;
                    out.varOp(loadOp(scalar), from);
                    out.varOp(storeOp(scalar), to);
                }
            }
        }

        void emitReturn(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            String ret = method.proto().returnType();
            if ("V".equals(ret)) { out.op(CodeWriter.RETURN); return; }
            // The declared return type wins over the register's inferred
            // scalar, and it has to win for the LOAD as well as the return
            // opcode: loading v1's int reading and then freturn-ing it is
            // exactly "float_type is not assignable from integer_type".
            // TypeInference already prefers this reading (preferredForReturn),
            // so the load below normally agrees; this keeps them in step even
            // if the register has no local under the declared reading.
            int scalar = scalarOf(ret);
            pushUseAs(it, 0, scalar);
            out.op(returnOp(scalar));
        }

        void emitConst(TypeInference.Insn insn, TypeInference.InsnTypes it, boolean wide) {
            long v = insn.args[1];
            // Readings ConstReadingElision proved overwritten before anything
            // can observe them; only ever non-zero on a rescue retry.
            int dead = deadConstReadings == null ? 0
                    : deadConstReadings.getOrDefault(insn.offset, 0);
            for (TypeInference.Def d : it.defs) {
                for (int scalar : DexType.scalarBits(d.scalars)) {
                    int slot = locals.slotOrMinusOne(d.register, scalar);
                    if (slot < 0) continue;
                    if ((dead & scalar) != 0 && d.register == insn.arg(0)) {
                        ConstReadingElision.elided.incrementAndGet();
                        continue;
                    }
                    pushConst(scalar, v, wide);
                    out.varOp(storeOp(scalar), slot);
                }
            }
        }

        /** Materialise the untyped Dalvik constant under one JVM reading. */
        void pushConst(int scalar, long v, boolean wide) {
            switch (scalar) {
                case DexType.INT:    out.pushInt((int) v); break;
                case DexType.FLOAT:  out.pushFloatBits((int) v); break;
                case DexType.LONG:   out.pushLong(v); break;
                case DexType.DOUBLE: out.pushDoubleBits(v); break;
                case DexType.OBJ:
                    // Only a zero constant is ever readable as a reference.
                    out.op(CodeWriter.ACONST_NULL);
                    break;
                default:
                    throw new TranslationException("const with scalar " + scalar);
            }
        }

        void emitNewArray(String arrayDesc) {
            String elem = arrayDesc.substring(1);
            int atype = primitiveArrayCode(elem);
            if (atype >= 0) out.intOp(CodeWriter.NEWARRAY, atype);
            else out.typeOp(CodeWriter.ANEWARRAY, DexFile.internalName(elem));
        }

        void emitFilledNewArray(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            String arrayDesc = dex.typeDescriptor((int) insn.args[0]);
            String elem = arrayDesc.substring(1);
            int n = it.uses.length;
            out.pushInt(n);
            emitNewArray(arrayDesc);
            int storeOp = arrayStoreOpForDesc(elem);
            for (int i = 0; i < n; i++) {
                out.op(CodeWriter.DUP);
                out.pushInt(i);
                pushUse(it, i);
                out.op(storeOp);
            }
            // Leaves the array on the stack for the following move-result,
            // matching how invoke leaves its result.
        }

        void emitFillArrayData(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            int payloadOffset = (int) insn.args[1];
            Instruction payload = findPayload(payloadOffset);
            if (payload == null) {
                throw new TranslationException("fill-array-data payload missing at 0x"
                    + Integer.toHexString(payloadOffset));
            }
            byte[] data = payload.arrayData();
            DexType arr = it.uses[0].type;
            String elem = arr.arrayElementDescriptor();
            if (elem == null) elem = "I";
            int width = elementWidth(elem);
            int storeOp = arrayStoreOpForDesc(elem);
            int count = width == 0 ? 0 : data.length / width;
            for (int i = 0; i < count; i++) {
                pushUse(it, 0);
                out.pushInt(i);
                pushArrayElement(data, i, width, elem);
                out.op(storeOp);
            }
        }

        void pushArrayElement(byte[] data, int i, int width, String elem) {
            long raw = 0;
            for (int b = 0; b < width; b++) raw |= (long) (data[i * width + b] & 0xff) << (8 * b);
            switch (elem) {
                case "Z": case "B": out.pushInt((byte) raw); break;
                case "S": out.pushInt((short) raw); break;
                case "C": out.pushInt((char) raw); break;
                case "I": out.pushInt((int) raw); break;
                case "F": out.pushFloatBits((int) raw); break;
                case "J": out.pushLong(raw); break;
                case "D": out.pushDoubleBits(raw); break;
                default: throw new TranslationException("fill-array-data element " + elem);
            }
        }

        void emitSwitch(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            // The decoder resolves the payload onto the switch instruction, and
            // only that copy has ABSOLUTE targets (see Normalizer).
            Instruction sw = instructionAt(insn.offset);
            int[] keys = sw == null ? null : sw.switchKeys();
            int[] targets = insn.switchTargets.length > 0
                ? insn.switchTargets : (sw == null ? null : sw.switchTargets());
            if (keys == null || targets == null || keys.length != targets.length) {
                // Unresolvable payload: the switch can only fall through.
                pushUse(it, 0);
                out.op(CodeWriter.POP);
                return;
            }
            Instruction payload = findPayload((int) insn.args[1]);
            pushUse(it, 0);
            CodeWriter.Label dflt = labelFor(insn.nextOffset);
            CodeWriter.Label[] ls = new CodeWriter.Label[targets.length];
            for (int i = 0; i < targets.length; i++) ls[i] = labelFor(targets[i]);
            boolean packed = payload != null
                && payload.payloadKind() == Instruction.PayloadKind.PACKED_SWITCH;
            if (packed && keys.length > 0) {
                out.tableSwitch(keys[0], keys[0] + keys.length - 1, dflt, ls);
            } else {
                out.lookupSwitch(dflt, keys, ls);
            }
        }

        /**
         * Dalvik's cmp family yields -1/0/1 in a register; the JVM's lcmp/fcmp/dcmp
         * yield the same on the STACK, so these map directly. The only real work
         * is picking the right NaN bias: cmpl-* pushes -1 when either operand is
         * NaN, cmpg-* pushes 1 (Dalvik spec, "cmpkind"), which is exactly the
         * FCMPL/FCMPG and DCMPL/DCMPG split.
         */
        void emitCmp(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            pushUse(it, 0);
            pushUse(it, 1);
            switch (insn.opcode) {
                case 0x2d: out.op(CodeWriter.FCMPL); break;   // cmpl-float
                case 0x2e: out.op(CodeWriter.FCMPG); break;   // cmpg-float
                case 0x2f: out.op(CodeWriter.DCMPL); break;   // cmpl-double
                case 0x30: out.op(CodeWriter.DCMPG); break;   // cmpg-double
                case 0x31: out.op(CodeWriter.LCMP); break;    // cmp-long
                default: throw new TranslationException("cmp opcode 0x"
                    + Integer.toHexString(insn.opcode));
            }
            storeDefs(it);
        }

        void emitIf(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            pushUse(it, 0);
            pushUse(it, 1);
            boolean ref = it.uses[0].scalar == DexType.OBJ;
            int base = insn.opcode - 0x32;   // if-eq, if-ne, if-lt, if-ge, if-gt, if-le
            int op;
            if (ref) {
                if (base == 0) op = CodeWriter.IF_ACMPEQ;
                else if (base == 1) op = CodeWriter.IF_ACMPNE;
                else throw new TranslationException("ordered compare on references");
            } else {
                op = CodeWriter.IF_ICMPEQ + base;
            }
            out.jump(op, labelFor((int) insn.args[2]));
        }

        void emitIfZero(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            int base = insn.opcode - 0x38;   // if-eqz .. if-lez
            boolean ref = it.uses[0].scalar == DexType.OBJ;
            CodeWriter.Label target = labelFor((int) insn.args[1]);
            pushUse(it, 0);
            if (ref) {
                if (base == 0) out.jump(CodeWriter.IFNULL, target);
                else if (base == 1) out.jump(CodeWriter.IFNONNULL, target);
                else throw new TranslationException("ordered compare-to-zero on a reference");
            } else {
                out.jump(CodeWriter.IFEQ + base, target);
            }
        }

        void emitInvoke(TypeInference.Insn insn, TypeInference.InsnTypes it, int family) {
            // A negative index is one of ours: a constructor call re-pointed at
            // the forwarding <init> R8 deleted (see collectRepoints).
            int methodIndex = (int) insn.args[0];
            Repoint rp = methodIndex < 0 ? syntheticMethods.get(methodIndex) : null;
            DexFile.MethodRef m = rp == null ? dex.methodRef(methodIndex) : null;
            DexFile.Proto proto = rp == null ? m.proto() : rp.proto;
            String owner = rp == null ? DexFile.internalName(m.declaringClass()) : rp.owner;
            String name = rp == null ? m.name() : "<init>";
            String desc = rp == null ? proto.descriptor() : rp.emitDescriptor;

            // The DEX proto still describes what is on the stack: the padding a
            // re-point may add is ours, pushed after every real operand.
            // A deferred allocation put the receiver on the stack (twice) at the
            // new-instance, so its operand is already there and must not be
            // loaded from a local it does not live in.
            boolean deferred = deferredInit.contains(insn.offset);
            // The RECEIVER of a non-<init> invokespecial has to be assignable to
            // the CURRENT class, not to the class the call names. JVMS 4.9.2:
            // "If an invokespecial instruction names a method which is not an
            // instance initialization method, then the target reference on the
            // operand stack must be a class instance whose type is assignment
            // compatible with the current class", which HotSpot enforces as
            // `current_frame->pop_stack(current_type())`. Getting this wrong was
            // the largest family left after the <init> work: SafeDK hoists
            // `super.startActivityForResult(...)` into a STATIC helper whose
            // parameter is declared as the SUPERCLASS, so the operand is typed
            // androidx/core/app/ComponentActivity where the verifier demands
            // androidx/activity/ComponentActivity. One checkcast re-establishes
            // what the DEX already relies on.
            boolean specialReceiver = (family == TypeInference.F_INVOKE_SUPER
                                       || family == TypeInference.F_INVOKE_DIRECT)
                                      && !"<init>".equals(name);
            // A protected method named through a superclass, called on a receiver
            // that is not the current class: name the receiver's own type when it
            // resolves to a PUBLIC override. Decided BEFORE the operands are
            // pushed, because the receiver requirement follows the named class.
            // See SuperInterfacePlan.publicRepointFor.
            String protectedOwner = null;
            if (family == TypeInference.F_INVOKE_VIRTUAL && rp == null && it.uses.length > 0) {
                DexType.Ref rr = it.uses[0].type == null ? null : it.uses[0].type.ref();
                if (rr != null && rr.kind == DexType.Ref.KIND_CLASS) {
                    protectedOwner = superIfacePlan.publicRepointFor(ownerName, owner, name,
                                                                     desc, rr.name);
                }
            }
            pushInvokeUses(it, invokeRequiredTypes(specialReceiver ? ownerName
                    : (protectedOwner != null ? protectedOwner : owner), proto,
                    family != TypeInference.F_INVOKE_STATIC, it.uses.length),
                    deferred ? 1 : 0);
            if (rp != null) for (int i = 0; i < rp.padCount; i++) out.op(CodeWriter.ACONST_NULL);

            int op;
            boolean itf = false;
            switch (family) {
                case TypeInference.F_INVOKE_STATIC: op = CodeWriter.INVOKESTATIC; break;
                case TypeInference.F_INVOKE_INTERFACE:
                    op = CodeWriter.INVOKEINTERFACE; itf = true; break;
                case TypeInference.F_INVOKE_SUPER:
                    // invoke-super is a non-virtual call to the superclass slot.
                    op = CodeWriter.INVOKESPECIAL; break;
                case TypeInference.F_INVOKE_DIRECT:
                    // invoke-direct covers <init>, private methods and (in old
                    // dex) package-private ones: all invokespecial on the JVM.
                    op = CodeWriter.INVOKESPECIAL; break;
                default: op = CodeWriter.INVOKEVIRTUAL; break;
            }
            // Since Java 8 an INTERFACE can declare static, private and default
            // methods, and DEX reaches all three with an opcode that is not
            // invoke-interface: invoke-static for a static interface method
            // (Comparator.comparingInt, List.of, Map.entry...), invoke-direct
            // for a private one, invoke-super for `I.super.m()`. The constant
            // pool entry still has to match the OWNER's kind, not the opcode --
            // JVMS 4.4.2, "In a CONSTANT_Methodref_info structure, the
            // class_index item should be a class type, not an interface type"
            // -- because the tag is what selects the resolution algorithm, and
            // each one rejects the other's holder (JVMS 5.4.3.3 "If C is an
            // interface, method resolution throws an IncompatibleClassChangeError";
            // 5.4.3.4 the converse). HotSpot implements exactly that split in
            // LinkResolver::linktime_resolve_{static,special}_method, and it is a
            // RESOLUTION-time check, so disabling bytecode verification does
            // not hide it:
            //   IncompatibleClassChangeError: Method 'java.util.Comparator
            //   java.util.Comparator.comparingInt(...)' must be InterfaceMethodref constant
            //
            // invokevirtual is deliberately excluded: JVMS 5.4.3.3 makes an
            // interface holder an error there whatever the tag says, and
            // HotSpot's resolve_method rejects it before it ever looks at the
            // tag ("Found interface %s, but class was expected"). A virtual call
            // through an interface is invoke-interface in DEX anyway.
            //
            // The oracle answers false for a type it cannot see, which leaves
            // the old (wrong) Methodref for an interface that is in neither the
            // APK nor the converter's classpath. That is acceptable because the
            // converter is normally given what the app links against (an
            // android.jar plus the JDK, via Options.hierarchyLoader or
            // --classpath), so a type missing from BOTH cannot be loaded at run
            // time either and would fail with NoClassDefFoundError before the
            // tag mattered. Without that classpath, framework interfaces are
            // invisible and keep the Methodref tag. DEX itself offers no better
            // signal: method_id_item records no holder kind,
            // which is why dx/d8 must consult the class hierarchy here too.
            if (hierarchy != null
                    && (op == CodeWriter.INVOKESTATIC || op == CodeWriter.INVOKESPECIAL)) {
                itf = hierarchy.isInterface(owner);
            }
            String emitOwner = owner;
            // DEX invoke-super to a CLASS invokes the CURRENT class's superclass
            // slot (Dalvik: "the virtual method of the immediate parent class"),
            // regardless of the class the methodref names. Some repackagers emit
            // the ref with the CURRENT class as the holder (measured: an app
            // protector's wrapper Application subclass whose attachBaseContext
            // calls super.attachBaseContext with a methodref whose class is that
            // Application subclass itself). A literal
            // `invokespecial <current-class>.m` is then a SELF-call -> infinite recursion, because JVMS 6.5's ACC_SUPER
            // super-lookup fires ONLY when the named class is a PROPER superclass
            // of the current class. Retarget to the direct superclass, which is
            // exactly what super.m() means; resolution then finds m at or above
            // it (e.g. ContextWrapper.attachBaseContext). Normal sites already
            // name the direct super, so emitOwner is unchanged for them. Interface
            // invoke-super (I.super.m()) is handled below via superIfacePlan.
            if (rp == null && family == TypeInference.F_INVOKE_SUPER && !itf
                    && owner.equals(ownerName)) {
                String directSuper = method.declaringClass().superclassName();
                if (directSuper != null) emitOwner = directSuper;
            }
            // `I.super.m()` where I is an INDIRECT superinterface is legal DEX and
            // illegal bytecode: JVMS 4.9.2 lets invokespecial name only a DIRECT
            // one. Name a direct superinterface that resolves to the same method.
            // See SuperInterfacePlan for why the substitution has to be checked
            // rather than assumed.
            if (itf && family == TypeInference.F_INVOKE_SUPER) {
                String direct = superIfacePlan.substituteFor(ownerName, owner, name, desc);
                if (direct != null) {
                    emitOwner = direct;
                    // The extended proof may name the direct SUPERCLASS instead
                    // (JVMS 4.9.2 "a method in a superclass of the current
                    // class"), which is a CONSTANT_Methodref: an
                    // InterfaceMethodref naming a class is an
                    // IncompatibleClassChangeError at resolution.
                    if (superIfacePlan.isClassSubstitute(ownerName, direct)) itf = false;
                } else if (superIfacePlan.mayAddDirectInterface(ownerName, owner, name, desc)) {
                    // Nothing we can NAME reaches the method ART runs, so make
                    // the named interface a direct one instead; the
                    // invokespecial below then names it legally. See
                    // SuperInterfacePlan.mayAddDirectInterface for the cost.
                    mw.owner.addDirectInterface(owner);
                }
            }
            if (protectedOwner != null) emitOwner = protectedOwner;
            // A virtual call whose owner is an ARRAY type (e.g. clone() on int[])
            // has no class to name; the JVM models those on Object.
            if (rp == null && m.declaringClass().startsWith("[")) emitOwner = "java/lang/Object";
            // Opt-in re-pointing (Options.unsafeRedirect / Options.exitRedirect):
            // an Unsafe or process-exit call from app code is swapped for a static
            // call on the configured class. For Unsafe the receiver becomes the
            // first parameter, so the operand stack the DEX proto describes is
            // untouched. UnsafeRepoint and GuestRepoint have the contracts.
            String emitDesc = desc;
            String emitName = name;
            if (rp == null) {
                GuestRepoint.Target t = GuestRepoint.lookup(
                        family == TypeInference.F_INVOKE_STATIC,
                        family == TypeInference.F_INVOKE_VIRTUAL, owner, name, desc);
                if (t != null) {
                    op = CodeWriter.INVOKESTATIC;
                    itf = false;
                    emitOwner = t.owner;
                    emitName = t.name;
                    emitDesc = t.desc;
                }
            }
            out.methodOp(op, emitOwner, emitName, emitDesc, itf);

            if (deferred) {
                // The dup the new-instance left is now an INITIALIZED reference
                // (JVMS 4.10.1.9 substitutes every copy of uninitialized(N) at
                // once), so this is where the Dalvik register finally gets its
                // value. A missing slot means nothing ever reads it.
                int reg = it.uses[0].register;
                int slot = locals.slotOrMinusOne(reg, DexType.OBJ);
                if (slot >= 0) out.varOp(CodeWriter.ASTORE, slot); else out.op(CodeWriter.POP);
                return;                                     // <init> returns void
            }
            // A void call leaves nothing; a value-returning call leaves its
            // result for the following move-result. If there is no move-result,
            // Dalvik discards it, so we must pop to stay stack-neutral.
            String ret = proto.returnType();
            if (!"V".equals(ret) && !nextIsMoveResult(insn)) {
                out.op(isWideDesc(ret) ? CodeWriter.POP2 : CodeWriter.POP);
            }
        }

        /**
         * invoke-custom -> invokedynamic.
         *
         * A DEX call_site_item is the flattened form of everything the JVM
         * splits between the CONSTANT_InvokeDynamic entry and the
         * BootstrapMethods attribute: values[0] is the bootstrap method handle,
         * [1] the member name, [2] the method type, and [3..] the bootstrap's
         * extra static arguments (dex-format, "call_site_item").
         */
        void emitInvokeCustom(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            DexFile.CallSite cs = dex.callSite((int) insn.args[0]);
            DexFile.MethodHandleRef bsm = cs.bootstrapMethod();
            DexFile.Proto type = cs.methodType();
            if (bsm == null || cs.methodName() == null || type == null) {
                throw new TranslationException("malformed call_site " + insn.args[0]);
            }

            List<DexFile.Value> extra = cs.bootstrapArguments();
            int[] argIndexes = new int[extra.size()];
            for (int i = 0; i < argIndexes.length; i++) {
                argIndexes[i] = loadableConstant(extra.get(i));
            }
            int bsmIndex = mw.owner.addBootstrapMethod(methodHandleConstant(bsm), argIndexes);

            pushUses(it);
            out.invokeDynamic(bsmIndex, cs.methodName(), type.descriptor());

            String ret = type.returnType();
            if (!"V".equals(ret) && !nextIsMoveResult(insn)) {
                out.op(isWideDesc(ret) ? CodeWriter.POP2 : CodeWriter.POP);
            }
        }

        /**
         * invoke-polymorphic -> invokevirtual with the CALL SITE's descriptor.
         *
         * MethodHandle.invokeExact and friends are signature-polymorphic (JVMS
         * 2.9.3): the JVM does not resolve them against a declared descriptor,
         * it takes the one written at the call site. DEX keeps that descriptor
         * in the separate proto operand, which is why it needs its own opcode
         * format rather than reusing invoke-virtual.
         */
        void emitInvokePolymorphic(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            DexFile.MethodRef m = dex.methodRef((int) insn.args[0]);
            DexFile.Proto proto = dex.proto((int) insn.args[1]);
            if (proto == null) {
                throw new TranslationException("invoke-polymorphic proto " + insn.args[1]);
            }
            String owner = DexFile.internalName(m.declaringClass());
            // The receiver is whatever class declares the polymorphic method --
            // MethodHandle or VarHandle -- not always MethodHandle. See
            // pushUseCastingTo.
            for (int i = 0; i < it.uses.length; i++) {
                if (i == 0) pushUseCastingTo(it, 0, owner); else pushUse(it, i);
            }
            out.methodOp(CodeWriter.INVOKEVIRTUAL, owner, m.name(), proto.descriptor(), false);

            String ret = proto.returnType();
            if (!"V".equals(ret) && !nextIsMoveResult(insn)) {
                out.op(isWideDesc(ret) ? CodeWriter.POP2 : CodeWriter.POP);
            }
        }

        /** CONSTANT_MethodHandle for a DEX method_handle_item. */
        int methodHandleConstant(DexFile.MethodHandleRef h) {
            int kind = referenceKind(h.type());
            if (h.field() != null) {
                DexFile.FieldRef f = h.field();
                return out.pool().methodHandle(kind, DexFile.internalName(f.declaringClass()),
                        f.name(), f.type(), false);
            }
            DexFile.MethodRef m = h.method();
            if (m == null) throw new TranslationException("method_handle with no target");
            // DEX does not record whether the owner is an interface, so only the
            // invoke-interface kind can be known to need an InterfaceMethodref.
            // A static or default method on an interface referenced through a
            // method handle is the gap; it needs an InterfaceMethodref too.
            boolean itf = h.type() == DexFile.METHOD_HANDLE_INVOKE_INTERFACE;
            return out.pool().methodHandle(kind, DexFile.internalName(m.declaringClass()),
                    m.name(), m.proto().descriptor(), itf);
        }

        /** DEX method_handle_type -> JVMS 4.4.8 reference_kind. */
        int referenceKind(int dexType) {
            switch (dexType) {
                case DexFile.METHOD_HANDLE_STATIC_PUT:      return ConstantPool.REF_putStatic;
                case DexFile.METHOD_HANDLE_STATIC_GET:      return ConstantPool.REF_getStatic;
                case DexFile.METHOD_HANDLE_INSTANCE_PUT:    return ConstantPool.REF_putField;
                case DexFile.METHOD_HANDLE_INSTANCE_GET:    return ConstantPool.REF_getField;
                case DexFile.METHOD_HANDLE_INVOKE_STATIC:   return ConstantPool.REF_invokeStatic;
                case DexFile.METHOD_HANDLE_INVOKE_INSTANCE: return ConstantPool.REF_invokeVirtual;
                case DexFile.METHOD_HANDLE_INVOKE_CONSTRUCTOR:
                    return ConstantPool.REF_newInvokeSpecial;
                case DexFile.METHOD_HANDLE_INVOKE_DIRECT:   return ConstantPool.REF_invokeSpecial;
                case DexFile.METHOD_HANDLE_INVOKE_INTERFACE:
                    return ConstantPool.REF_invokeInterface;
                default:
                    throw new TranslationException("method_handle_type " + dexType);
            }
        }

        /**
         * A bootstrap argument as a LOADABLE constant pool index (JVMS 4.7.23
         * bootstrap_arguments). Note the contrast with an annotation's
         * element_value, where a string is a CONSTANT_Utf8: here it is a real
         * CONSTANT_String and a type is a CONSTANT_Class.
         */
        int loadableConstant(DexFile.Value v) {
            ConstantPool pool = out.pool();
            switch (v.tag()) {
                case DexFile.VALUE_STRING: return pool.stringRef(v.asString());
                case DexFile.VALUE_TYPE:
                    return pool.classRef(DexFile.internalName(v.asTypeDescriptor()));
                case DexFile.VALUE_BYTE:
                case DexFile.VALUE_SHORT:
                case DexFile.VALUE_CHAR:
                case DexFile.VALUE_INT:     return pool.integer(v.asInt());
                case DexFile.VALUE_BOOLEAN: return pool.integer(v.asBoolean() ? 1 : 0);
                case DexFile.VALUE_LONG:    return pool.longConst(v.asLong());
                case DexFile.VALUE_FLOAT:   return pool.floatConst(v.asFloat());
                case DexFile.VALUE_DOUBLE:  return pool.doubleConst(v.asDouble());
                case DexFile.VALUE_METHOD_TYPE:
                    return pool.methodType(v.asProto().descriptor());
                case DexFile.VALUE_METHOD_HANDLE:
                    return methodHandleConstant(v.asMethodHandle());
                default:
                    throw new TranslationException(
                            "bootstrap argument tag 0x" + Integer.toHexString(v.tag()));
            }
        }

        void emitUnary(int opcode) {
            switch (opcode) {
                case 0x7b: out.op(CodeWriter.INEG); break;   // neg-int
                case 0x7c: out.pushInt(-1); out.op(CodeWriter.IXOR); break; // not-int
                case 0x7d: out.op(CodeWriter.LNEG); break;
                case 0x7e: out.pushLong(-1L); out.op(CodeWriter.LXOR); break; // not-long
                case 0x7f: out.op(CodeWriter.FNEG); break;
                case 0x80: out.op(CodeWriter.DNEG); break;
                case 0x81: out.op(CodeWriter.I2L); break;
                case 0x82: out.op(CodeWriter.I2F); break;
                case 0x83: out.op(CodeWriter.I2D); break;
                case 0x84: out.op(CodeWriter.L2I); break;
                case 0x85: out.op(CodeWriter.L2F); break;
                case 0x86: out.op(CodeWriter.L2D); break;
                case 0x87: out.op(CodeWriter.F2I); break;
                case 0x88: out.op(CodeWriter.F2L); break;
                case 0x89: out.op(CodeWriter.F2D); break;
                case 0x8a: out.op(CodeWriter.D2I); break;
                case 0x8b: out.op(CodeWriter.D2L); break;
                case 0x8c: out.op(CodeWriter.D2F); break;
                case 0x8d: out.op(CodeWriter.I2B); break;
                case 0x8e: out.op(CodeWriter.I2C); break;
                case 0x8f: out.op(CodeWriter.I2S); break;
                default: throw new TranslationException("unary 0x" + Integer.toHexString(opcode));
            }
        }

        void emitBinary(int opcode) {
            // 0x90..0xaf are the three-register forms; 0xb0..0xcf repeat the
            // same 32 operations in /2addr form, so normalise onto the first.
            int base = opcode >= 0xb0 ? opcode - 0xb0 + 0x90 : opcode;
            out.op(binaryOp(base));
        }

        void emitBinaryConst(TypeInference.Insn insn, TypeInference.InsnTypes it) {
            int opcode = insn.opcode;
            int lit = (int) insn.args[2];
            // rsub-int and rsub-int/lit8 subtract the REGISTER from the literal,
            // which is the operand order the JVM cannot express directly.
            boolean reversed = (opcode == 0xd1 || opcode == 0xd9);
            if (reversed) {
                out.pushInt(lit);
                pushUse(it, 0);
                out.op(CodeWriter.ISUB);
            } else {
                pushUse(it, 0);
                out.pushInt(lit);
                out.op(constBinaryOp(opcode));
            }
            storeDefs(it);
        }

        // ------------------------------------------------- stack plumbing

        /** Push every operand, in the order TypeInference lists them, which is
         *  already the JVM's push order for every family. */
        void pushUses(TypeInference.InsnTypes it) {
            for (int i = 0; i < it.uses.length; i++) pushUse(it, i);
        }

        /**
         * Push an invoke's operands, inserting a checkcast wherever the type we
         * inferred is not PROVABLY assignable to what the callee declares.
         *
         * Dalvik does not need this: ART's verifier tracks a register's type
         * per path and never has to write it down. The JVM does -- every merge
         * point gets a StackMapTable frame -- and one Dalvik register maps to
         * one JVM local per scalar reading, so two catch clauses that reuse the
         * same register for their exception force a single slot to hold both.
         * The merge widens to their common supertype and a later use of the
         * specific type is then rejected:
         *
         *   Bad type on operand stack ... Type 'java/lang/Throwable' is not
         *   assignable to 'java/io/IOException'
         *
         * (androidx.core.graphics.TypefaceCompatUtil.copyToFile, and 800-odd
         * more across the corpus -- 88% of everything the split verifier still
         * rejected after the least-upper-bound oracle landed.)
         *
         * A checkcast re-establishes the type the DEX already proved. It cannot
         * fail on any path ART considers reachable, and it is emitted only where
         * assignability is not provable, so precise code is unaffected.
         */
        void pushInvokeUses(TypeInference.InsnTypes it, String[] required) {
            pushInvokeUses(it, required, 0);
        }

        /** @param from index of the first operand to push; 1 skips a receiver
         *              that is already on the stack (a deferred allocation). */
        void pushInvokeUses(TypeInference.InsnTypes it, String[] required, int from) {
            for (int i = from; i < it.uses.length; i++) {
                pushUse(it, i);
                if (required != null && i < required.length) {
                    castIfImprecise(it.uses[i], required[i]);
                }
            }
        }

        /**
         * The reference type each operand of an invoke must have, in push order,
         * with null wherever nothing is required (a primitive, or an operand
         * whose position we could not line up).
         *
         * Two shapes are accepted because a long/double argument may be listed
         * either as one operand or as its two register words depending on the
         * instruction form; matching on length picks the right one rather than
         * guessing, and an unrecognised shape disables the refinement instead of
         * casting the wrong operand.
         */
        String[] invokeRequiredTypes(String owner, DexFile.Proto proto,
                                     boolean hasReceiver, int useCount) {
            if (hierarchy == null) return null;
            List<String> params = proto.parameterTypes();
            List<String> byArg = new ArrayList<>(params.size() + 1);
            if (hasReceiver) byArg.add(owner);
            for (String d : params) byArg.add(refNameOfDescriptor(d));
            if (byArg.size() == useCount) return byArg.toArray(new String[0]);

            List<String> byWord = new ArrayList<>(params.size() * 2 + 1);
            if (hasReceiver) byWord.add(owner);
            for (String d : params) {
                byWord.add(refNameOfDescriptor(d));
                if (isWideDesc(d)) byWord.add(null);
            }
            if (byWord.size() == useCount) return byWord.toArray(new String[0]);
            return null;
        }

        /** Internal name for a reference descriptor, or null for a primitive. */
        static String refNameOfDescriptor(String d) {
            if (d == null || d.isEmpty()) return null;
            char c = d.charAt(0);
            if (c == '[') return d;
            if (c == 'L' && d.endsWith(";")) return d.substring(1, d.length() - 1);
            return null;
        }

        void castIfImprecise(TypeInference.Use u, String required) {
            if (required == null || u.scalar != DexType.OBJ || u.definitelyNull) return;
            if (u.checkCastTo != null) return;   // the analysis already cast it
            String have = u.type.ref().name;
            if (have == null || have.equals(required)) return;
            if (assignableTo(have, required)) return;
            out.typeOp(CodeWriter.CHECKCAST, required);
        }

        /**
         * Conservative "is a value of `from` always acceptable where `to` is
         * required", using JVMS 4.10.1.2 assignability rather than real Java
         * subtyping.
         *
         * The interface case is the load-bearing one: the verifier treats EVERY
         * class as assignable to every interface type and defers the real check
         * to invokeinterface at run time. Casting there would be pure bloat, so
         * interfaces answer true.
         */
        boolean assignableTo(String from, String to) {
            if (DexType.OBJECT_NAME.equals(to)) return true;
            if (from.charAt(0) == '[' || to.charAt(0) == '[') return from.equals(to);
            if (hierarchy.isInterface(to)) return true;
            String cur = from;
            for (int guard = 0; cur != null && guard < 256; guard++) {
                if (cur.equals(to)) return true;
                cur = hierarchy.superclassOf(cur);
            }
            // Chain ran out before reaching `to`. If it never reached Object the
            // oracle is incomplete and we cannot claim either way, so fall
            // through to a cast: an unnecessary checkcast always succeeds, a
            // missing one is a class that will not verify.
            return false;
        }

        /**
         * Push a const-string, splitting it when it cannot be a constant.
         *
         * A CONSTANT_Utf8's length field is u2, so a string whose MODIFIED
         * UTF-8 encoding exceeds 65535 bytes has no representation in the
         * constant pool (JVMS 4.4.7) -- javac rejects such a literal outright.
         * DEX has no such limit, so real APKs do ship them: Temple Run 2 and
         * Alto's Adventure both embed a >64 KB OM SDK JavaScript blob as a
         * single const-string (com/vungle/ads/internal/omsdk/Res), which was
         * the only class in an 840k-class corpus we could not translate.
         *
         * Emitting the pieces and joining them with String.concat reproduces
         * the exact same string, needs no synthetic member, and keeps the stack
         * two deep. The concatenation cost is paid per execution, which is
         * irrelevant for what is always a one-shot resource initialiser.
         */
        void emitStringConstant(String s) {
            if (mutf8Length(s) <= 65535) {
                out.ldcString(s);
                return;
            }
            List<String> parts = splitForConstantPool(s);
            out.ldcString(parts.get(0));
            for (int i = 1; i < parts.size(); i++) {
                out.ldcString(parts.get(i));
                out.methodOp(CodeWriter.INVOKEVIRTUAL, "java/lang/String", "concat",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
            }
        }

        /**
         * Length of {@code s} in modified UTF-8 (JVMS 4.4.7): U+0001..U+007F
         * take one byte, U+0000 and U+0080..U+07FF take two, everything else
         * three. Surrogates are encoded individually, which is exactly why a
         * split between them still rebuilds the same char sequence.
         */
        static int mutf8Length(String s) {
            int n = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 0x0001 && c <= 0x007F) n += 1;
                else if (c <= 0x07FF) n += 2;
                else n += 3;
                if (n < 0) return Integer.MAX_VALUE;   // overflow guard
            }
            return n;
        }

        static List<String> splitForConstantPool(String s) {
            List<String> parts = new ArrayList<>();
            int start = 0, bytes = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int w = (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF ? 2 : 3);
                if (bytes + w > 65535) {
                    parts.add(s.substring(start, i));
                    start = i;
                    bytes = 0;
                }
                bytes += w;
            }
            parts.add(s.substring(start));
            return parts;
        }

        /** Push use #i, honouring provable-null and the analysis's cast hints. */
        void pushUse(TypeInference.InsnTypes it, int i) {
            TypeInference.Use u = it.uses[i];
            if (u.definitelyNull) {
                // The local may never have been written on this path, so loading
                // it would not verify; the value is null by construction.
                out.op(CodeWriter.ACONST_NULL);
                return;
            }
            int slot = locals.slotOrMinusOne(u.register, u.scalar);
            if (slot < 0) {
                throw new TranslationException("no local for v" + u.register
                    + " scalar " + DexType.scalarToString(u.scalar));
            }
            out.varOp(loadOp(u.scalar), slot);
            if (u.checkCastTo != null) out.typeOp(CodeWriter.CHECKCAST, u.checkCastTo);
        }

        /**
         * Push use #i, but assert {@code target} instead of the type the
         * analysis picked, and only where the analysis wanted a cast at all.
         *
         * Needed for the RECEIVER of an invoke-polymorphic. TypeInference types
         * that operand as java/lang/invoke/MethodHandle, which is right for
         * MethodHandle.invoke / invokeExact and wrong for VarHandle: its access
         * methods are signature-polymorphic too (JVMS 2.9.3), so DEX reaches
         * them with the same invoke-polymorphic opcode. Casting a VarHandle to
         * MethodHandle is a run-time ClassCastException that no structural check
         * can see, which is exactly what tests/semantic/cases/Reflect.java caught:
         *   class java.lang.invoke.VarHandleInts$FieldInstanceReadWrite cannot
         *   be cast to class java.lang.invoke.MethodHandle
         * The cast target is the only thing that descriptor decides (the scalar
         * is OBJ either way), so fixing it here is the whole fix.
         */
        void pushUseCastingTo(TypeInference.InsnTypes it, int i, String target) {
            TypeInference.Use u = it.uses[i];
            if (u.definitelyNull || u.checkCastTo == null || target == null) {
                pushUse(it, i);
                return;
            }
            int slot = locals.slotOrMinusOne(u.register, u.scalar);
            if (slot < 0) {
                throw new TranslationException("no local for v" + u.register
                    + " scalar " + DexType.scalarToString(u.scalar));
            }
            out.varOp(loadOp(u.scalar), slot);
            out.typeOp(CodeWriter.CHECKCAST, target);
        }

        /**
         * Push use #i under a caller-dictated reading rather than the inferred
         * one. Used where the JVM opcode that consumes the value pins its type
         * (a return, whose descriptor is declared) but the DEX opcode does not.
         * Falls back to the inferred reading when the register has no local
         * under the requested one.
         */
        void pushUseAs(TypeInference.InsnTypes it, int i, int scalar) {
            TypeInference.Use u = it.uses[i];
            if (u.definitelyNull || u.scalar == scalar
                    || locals.slotOrMinusOne(u.register, scalar) < 0) {
                pushUse(it, i);
                return;
            }
            out.varOp(loadOp(scalar), locals.slotOrMinusOne(u.register, scalar));
            if (u.checkCastTo != null) out.typeOp(CodeWriter.CHECKCAST, u.checkCastTo);
        }

        /** Store the top-of-stack value into every local reading this def needs. */
        /**
         * Store a typed zero into every slot the def owns, one push per slot.
         * Only for a def that no execution can reach (see the NULL_AGET site):
         * it keeps each slot's verification type what the frame says without
         * pretending the dead value has one JVM type.
         */
        void storeZeroDefs(TypeInference.InsnTypes it) {
            TypeInference.Def d = it.defs[0];
            for (int scalar : DexType.scalarBits(d.scalars)) {
                int slot = locals.slotOrMinusOne(d.register, scalar);
                if (slot < 0) continue;
                switch (scalar) {
                    case DexType.INT:    out.pushInt(0); break;
                    case DexType.FLOAT:  out.pushFloatBits(0); break;
                    case DexType.LONG:   out.pushLong(0L); break;
                    case DexType.DOUBLE: out.pushDoubleBits(0L); break;
                    case DexType.OBJ:    out.op(CodeWriter.ACONST_NULL); break;
                    default: continue;
                }
                out.varOp(storeOp(scalar), slot);
            }
        }

        void storeDefs(TypeInference.InsnTypes it) {
            if (it.defs.length == 0) { return; }
            TypeInference.Def d = it.defs[0];
            int[] bits = sameCategory(DexType.scalarBits(d.scalars), d.wide);
            int written = 0;
            for (int scalar : bits) {
                int slot = locals.slotOrMinusOne(d.register, scalar);
                if (slot < 0) continue;
                // Duplicate for all but the last consumer.
                if (written < countSlots(d, bits) - 1) {
                    out.op(DexType.isWide(scalar) ? CodeWriter.DUP2 : CodeWriter.DUP);
                }
                out.varOp(storeOp(scalar), slot);
                written++;
            }
            // Nothing consumed it: discard with the width of the value that is
            // actually on the stack, which is the category we kept above.
            if (written == 0) {
                out.op((bits.length > 0 ? DexType.isWide(bits[0]) : d.wide)
                        ? CodeWriter.POP2 : CodeWriter.POP);
            }
        }

        /**
         * Keep only the scalar readings that share ONE JVM stack category.
         *
         * A value on the stack occupies either one slot (int/float/reference)
         * or two (long/double); it cannot be both, so `dup`/`dup2` and the
         * store opcode have to agree on which. A def's scalar SET can
         * legitimately span both, because DexType.ANY -- what the analysis
         * assigns to a value it knows is unreachable, e.g. the result of an
         * aget on a provably NULL array -- has every bit set, and
         * DexType.isWide reports true for any set containing a 64-bit bit.
         * Storing under both categories then emits `dup2` on a one-slot value:
         *
         *   aconst_null; iload 6; saload   <- one int on the stack
         *   dup2; istore 6; dup2; lstore 23; dup2; fstore 21; dup2; dstore 25
         *
         * which HotSpot's oop-map builder rejects as a stack underflow (found
         * on Hill Climb Racing 1.43, com/moat/analytics/mobile/cha/g). The
         * instruction always throws before any of those stores can run, so narrowing to one
         * category loses nothing real; it just keeps the shape legal.
         *
         * A MIXED set only arises from ANY, and on those paths our opcode
         * selection defaults to the one-slot form (arrayLoadOp picks IALOAD for
         * an unknown element type), so category 1 is the reading that matches
         * what was actually pushed.
         */
        static int[] sameCategory(int[] bits, boolean wide) {
            boolean mixed = false, sawWide = false, sawNarrow = false;
            for (int s : bits) {
                if (DexType.isWide(s)) sawWide = true; else sawNarrow = true;
            }
            mixed = sawWide && sawNarrow;
            boolean keepWide = mixed ? false : wide;
            int n = 0;
            for (int s : bits) if (DexType.isWide(s) == keepWide) n++;
            int[] out = new int[n];
            int i = 0;
            for (int s : bits) if (DexType.isWide(s) == keepWide) out[i++] = s;
            return out;
        }

        int countSlots(TypeInference.Def d, int[] bits) {
            int n = 0;
            for (int s : bits) if (locals.slotOrMinusOne(d.register, s) >= 0) n++;
            return n;
        }

        boolean nextIsMoveResult(TypeInference.Insn insn) {
            Instruction next = instructionAt(insn.nextOffset);
            return next != null && next.isMoveResult();
        }

        Instruction instructionAt(int offset) {
            for (Instruction i : decoded) if (i.address() == offset) return i;
            return null;
        }

        Instruction findPayload(int offset) {
            Instruction i = instructionAt(offset);
            return (i != null && i.isPayload()) ? i : null;
        }

        // ------------------------------------------------------ try/catch

        void emitTryCatch(List<TypeInference.TryBlock> tries) {
            if (coverPlan != null) { emitTryCatchCovered(tries); return; }
            // Under NARROW_TRY every range's spans are computed first, because
            // their total decides whether they can be used at all: the table's
            // length is a u2 (JVMS 4.7.3), and a range that interleaves N runs of
            // throwing code with C catch clauses costs N*C rows. A method whose
            // narrowed table would not fit keeps one row per catch over the
            // whole DEX range instead, exactly the pre-NARROW_TRY table. Its
            // frames are still the ART-rule ones, so it may fail the split
            // verifier as such methods did before (the old retry path); at run
            // time routing is identical either way, because the instructions
            // the wider rows add cannot throw. None of the 62 apps in the
            // corpus comes near the limit (2026-09-26); see MAX_EXCEPTION_ROWS
            // for how to exercise it on a small method.
            //
            // In a monitor method (catchAllWide, see MONITOR_SAFE_TRY) the
            // catch-all clause is exempt: it keeps its one whole-range row, so
            // it costs one row however the range is narrowed.
            java.util.Map<TypeInference.TryBlock, List<CodeWriter.Label[]>> narrowed = null;
            if (NARROW_TRY) {
                narrowed = new java.util.IdentityHashMap<>();
                long rows = 0;
                for (TypeInference.TryBlock t : tries) {
                    if (cur.labels.get(t.startOffset) == null) continue;
                    if (cur.labels.get(t.endOffset) == null) continue;   // thrown below
                    List<CodeWriter.Label[]> s = throwingSpans(t);
                    narrowed.put(t, s);
                    for (TypeInference.Catch c : t.catches) {
                        rows += (catchAllWide && c.exceptionType == null) ? 1 : s.size();
                    }
                }
                if (rows > MAX_EXCEPTION_ROWS) narrowed = null;
            }
            for (TypeInference.TryBlock t : tries) {
                CodeWriter.Label start = cur.labels.get(t.startOffset);
                CodeWriter.Label end = cur.labels.get(t.endOffset);
                if (start == null) continue;
                // bindRangeEndsUpTo has already bound every range end, so a
                // missing one is a bug here rather than something to paper
                // over: creating the label now guarantees it is referenced and
                // never marked, which loses the whole class.
                if (end == null) {
                    throw new TranslationException("try range end 0x"
                        + Integer.toHexString(t.endOffset) + " was never bound");
                }
                // The spans this range's rows cover: the whole DEX range, or,
                // under NARROW_TRY, only its runs of throwing instructions --
                // except a monitor method's catch-all, which keeps the whole
                // range (MONITOR_SAFE_TRY) and so is emitted even when the
                // range has no throwing instruction at all, as it was before
                // narrowing existed.
                List<CodeWriter.Label[]> whole =
                        java.util.Collections.singletonList(new CodeWriter.Label[]{start, end});
                List<CodeWriter.Label[]> narrowSpans = narrowed != null ? narrowed.get(t) : whole;
                for (TypeInference.Catch c : t.catches) {
                    List<CodeWriter.Label[]> spans =
                            (catchAllWide && c.exceptionType == null) ? whole : narrowSpans;
                    if (spans.isEmpty()) continue;
                    // A handler the analysis proved unreachable emits NO code,
                    // so its label collapsed forward onto the next live
                    // instruction. Pointing the table at that bci invents a
                    // phantom entry into live code: HotSpot's
                    // GenerateOopMap::mark_reachable_code marks EVERY
                    // handler_pc alive at stack depth 1 with no reachability or
                    // type-dominance filter of its own, so the bci ends up
                    // reachable at depth 1 by dispatch and depth 0 by ordinary
                    // flow, and merge_state_into_bb aborts the VM with a stack
                    // height conflict. Dropping the row loses nothing: the
                    // handler is dead precisely because an earlier catch in the
                    // same range already covers everything it could catch, and
                    // the JVM tries handlers in table order anyway.
                    if (!types.isReachable(c.handlerOffset)) continue;
                    CodeWriter.Label h = cur.cloneLabels.get(c.handlerOffset);
                    if (h == null) h = cur.handlerStubs.get(c.handlerOffset);
                    if (h == null) h = cur.labels.get(c.handlerOffset);
                    if (h == null) {
                        // On the ordinary path a reachable handler always has a
                        // label by now and this is unreachable. On the chain
                        // path it would mean the handler's body landed in a
                        // DIFFERENT part, which MethodSplitter refuses to plan
                        // -- so reaching here is a planner bug, and dropping
                        // the row silently would delete a catch clause the app
                        // depends on. Fail and let translate() fall back.
                        if (split != null) {
                            throw new TranslationException("handler 0x"
                                + Integer.toHexString(c.handlerOffset)
                                + " is not in the part its try range is in");
                        }
                        continue;
                    }
                    // Rows go catch-major, span-minor. DEX try_items never
                    // overlap and a range's spans are disjoint, so the rows that
                    // cover any one bci are exactly one span's, still in the
                    // DEX catch order the JVM's first-match dispatch needs. A
                    // whole-range catch-all row changes nothing there: the DEX
                    // format puts catch_all after every typed handler
                    // (encoded_catch_handler), so its row comes last and is
                    // first-match only where no typed row covers the bci.
                    for (CodeWriter.Label[] s : spans) {
                        out.tryCatch(s[0], s[1], h, c.exceptionType);
                    }
                }
            }
        }

        /**
         * The exception table of a MONITOR_COVER re-translation: the table
         * emitTryCatch would write, with two changes, then the release stubs.
         *
         *   1. A whole-range catch-all row (catchAllWide) leaves out the
         *      non-throwing instructions the plan drops -- those in a different
         *      monitor context from the handler -- so it becomes the runs
         *      between them. Every other row, typed or catch-all, is exactly
         *      emitTryCatch's, in emitTryCatch's order.
         *   2. Lock-holding instructions the plan assigns a stub -- non-throwing
         *      ones no row of their context covers, and (MONITOR_COVER_THROWING)
         *      throwing ones outside every try range -- get rows of their own,
         *      appended in code order, one per maximal run with the same stub.
         *      Appending is safe for first-match dispatch: under narrowing no
         *      typed row covers a non-throwing instruction, no row at all covers
         *      an instruction outside every try, and the plan only assigns a
         *      stub where no catch-all row (after change 1) does, so each
         *      appended row is the only row at its bci.
         *
         * Dropping a row over an instruction never invalidates a handler's
         * frame (it was computed from a superset of predecessors), and a stub's
         * frame is TOP but for the lock slots (frameFor), so the analysis is
         * the base translation's, unchanged. The whole-range fallback
         * emitTryCatch takes when narrowed rows would overflow the u2 table
         * length is not reproduced: such a method is refused and keeps its
         * previous output, as is one whose table this would overflow.
         */
        void emitTryCatchCovered(List<TypeInference.TryBlock> tries) {
            List<Object[]> rows = new ArrayList<>();
            long narrowedRows = 0;
            java.util.Map<TypeInference.TryBlock, List<CodeWriter.Label[]>> narrowed =
                    new java.util.IdentityHashMap<>();
            for (TypeInference.TryBlock t : tries) {
                if (cur.labels.get(t.startOffset) == null) continue;
                if (cur.labels.get(t.endOffset) == null) continue;   // thrown below
                List<CodeWriter.Label[]> sp = throwingSpans(t);
                narrowed.put(t, sp);
                for (TypeInference.Catch c : t.catches) {
                    narrowedRows += (catchAllWide && c.exceptionType == null) ? 1 : sp.size();
                }
            }
            if (narrowedRows > MAX_EXCEPTION_ROWS) {
                throw new TranslationException("monitor cover: the narrowed table would not fit");
            }
            for (TypeInference.TryBlock t : tries) {
                CodeWriter.Label start = cur.labels.get(t.startOffset);
                CodeWriter.Label end = cur.labels.get(t.endOffset);
                if (start == null) continue;
                if (end == null) {
                    throw new TranslationException("try range end 0x"
                        + Integer.toHexString(t.endOffset) + " was never bound");
                }
                for (TypeInference.Catch c : t.catches) {
                    List<CodeWriter.Label[]> spans;
                    if (catchAllWide && c.exceptionType == null) {
                        spans = wholeRangeLessDropped(t, start, end);
                    } else {
                        spans = narrowed.get(t);
                    }
                    if (spans.isEmpty()) continue;
                    if (!types.isReachable(c.handlerOffset)) continue;
                    CodeWriter.Label h = cur.handlerStubs.get(c.handlerOffset);
                    if (h == null) h = cur.labels.get(c.handlerOffset);
                    if (h == null) continue;
                    for (CodeWriter.Label[] sp : spans) rows.add(new Object[]{sp, h, c.exceptionType});
                }
            }

            // Stub rows, in code order: maximal runs of reachable instructions
            // assigned the same stub. The stubs themselves go after everything
            // already emitted, so no existing row can reach them.
            Map<String, CodeWriter.Label> stubByKey = new HashMap<>();
            String runKey = null;
            int runStart = -1;
            for (TypeInference.Insn insn : lastNorm) {
                if (!types.isReachable(insn.offset)) continue;
                String key = stubKey(insn.offset);
                if (runKey != null && !runKey.equals(key)) {
                    rows.add(new Object[]{span(runStart, insn.offset), stubByKey.get(runKey), null});
                    runKey = null;
                }
                if (key != null && runKey == null) {
                    runKey = key;
                    runStart = insn.offset;
                    stubByKey.computeIfAbsent(key, k -> out.newLabel("monitor-cover-stub " + k));
                }
            }
            if (runKey != null) {
                rows.add(new Object[]{span(runStart, lastNorm.get(lastNorm.size() - 1).nextOffset),
                                      stubByKey.get(runKey), null});
            }
            // A run of instructions can translate to no bytecode at all, and a
            // row over it would be start_pc == end_pc, which ClassFileParser
            // rejects (see CodeWriter.coversNoCode). Such a row covers nothing,
            // so dropping it changes no bci's coverage. Measured on nowinandroid
            // before this filter: 87 classes that verified came out "Illegal
            // exception table range".
            rows.removeIf(r -> {
                CodeWriter.Label[] sp = (CodeWriter.Label[]) r[0];
                return out.coversNoCode(sp[0], sp[1]);
            });
            if (rows.size() > MAX_EXCEPTION_ROWS) {
                throw new TranslationException("monitor cover needs " + rows.size()
                        + " exception_table rows");
            }
            for (Object[] r : rows) {
                CodeWriter.Label[] sp = (CodeWriter.Label[]) r[0];
                out.tryCatch(sp[0], sp[1], (CodeWriter.Label) r[1], (String) r[2]);
            }
            emitCoverStubs(stubByKey);
        }

        /** The stub key the plan gives the instruction at {@code offset}, or
         *  null: the monitor context (monitor-enter DEX offsets, see
         *  MonitorCover.Plan.stubContext for why it is part of the key), then
         *  the JVM slots of the held locks, innermost first, as
         *  "ctx,ctx|slot,slot". A constructor point where `this` is still
         *  uninitialized gets none: a stub frame cannot carry flagThisUninit, so
         *  HotSpot would find the covered frame not assignable to it
         *  (StackMapFrame::is_assignable_to compares flags). */
        private String stubKey(int offset) {
            int[] regs = coverPlan.stub.get(offset);
            if (regs == null) return null;
            if (method.isConstructor() && thisUninitAt(offset)) return null;
            StringBuilder b = new StringBuilder();
            for (int c : coverPlan.stubContext.get(offset)) {
                if (b.length() > 0) b.append(',');
                b.append(Integer.toHexString(c));
            }
            b.append('|');
            for (int k = regs.length - 1; k >= 0; k--) {
                int slot = locals.slotOrMinusOne(regs[k], DexType.OBJ);
                if (slot < 0) return null;
                if (k != regs.length - 1) b.append(',');
                b.append(slot);
            }
            return b.toString();
        }

        /**
         * One release stub per key: `aload <lock>; monitorexit` for each held
         * lock, innermost first, then `athrow` -- javac's synchronized handler
         * without the store to a temporary, since the exception is already on
         * the stack. frameFor gives it a frame of TOP except the lock slots.
         */
        private void emitCoverStubs(Map<String, CodeWriter.Label> stubByKey) {
            List<String> keys = new ArrayList<>(stubByKey.keySet());
            java.util.Collections.sort(keys);                // deterministic emission order
            for (String key : keys) {
                CodeWriter.Label l = stubByKey.get(key);
                String[] parts = key.substring(key.indexOf('|') + 1).split(",");
                int[] slots = new int[parts.length];
                for (int k = 0; k < parts.length; k++) slots[k] = Integer.parseInt(parts[k]);
                out.mark(l);
                for (int slot : slots) {
                    out.varOp(CodeWriter.ALOAD, slot);
                    out.op(CodeWriter.MONITOREXIT);
                }
                out.op(CodeWriter.ATHROW);
                coverStubs.put(l, slots);
            }
        }

        /**
         * A whole-range catch-all row's spans with the plan's dropped
         * instructions taken out: [start, end) split at each of them. With
         * nothing dropped this is exactly the one whole-range span emitTryCatch
         * writes. Unreachable instructions emit no code, so only reachable ones
         * can start a later span.
         */
        private List<CodeWriter.Label[]> wholeRangeLessDropped(TypeInference.TryBlock t,
                                                                CodeWriter.Label start,
                                                                CodeWriter.Label end) {
            List<CodeWriter.Label[]> spans = new ArrayList<>();
            CodeWriter.Label runStart = start;
            for (TypeInference.Insn insn : lastNorm) {
                if (insn.offset < t.startOffset) continue;
                if (insn.offset >= t.endOffset) break;
                if (!types.isReachable(insn.offset)) continue;
                CodeWriter.Label here = cur.labels.get(insn.offset);
                boolean dropped = coverPlan.drop.contains(insn.offset);
                // A boundary needs a label. Without one, splitting here could
                // silently uncover a THROWING instruction, which would change
                // where a real exception goes; refuse, and the caller keeps the
                // previous translation.
                if ((dropped ? runStart != null : runStart == null) && here == null) {
                    throw new TranslationException("monitor cover: no label at 0x"
                            + Integer.toHexString(insn.offset) + " to split a catch-all row");
                }
                if (dropped) {
                    if (runStart != null && here != runStart) {
                        spans.add(new CodeWriter.Label[]{runStart, here});
                    }
                    runStart = null;
                } else if (runStart == null) {
                    runStart = here;
                }
            }
            if (runStart != null) spans.add(new CodeWriter.Label[]{runStart, end});
            return spans;
        }

        /**
         * The maximal runs of reachable THROWING instructions inside {@code t},
         * as [start, end) label pairs. See {@link Translator#NARROW_TRY}.
         *
         * The predicate is TypeInference.canThrow, the very one that decides
         * which instructions get a handler edge under the ART rule, so the frame
         * at each handler is computed from exactly the program points the table
         * lets reach it. An unreachable instruction emits no bytecode, so it
         * neither opens nor breaks a run. A run ends at the first reachable
         * non-throwing instruction (whose label marks the start of its code) or
         * at the DEX range end (bound by bindRangeEndsUpTo).
         *
         * Membership is by instruction START, which is also ART's rule: its
         * catch lookup is keyed on the dex_pc of the throwing instruction, so an
         * instruction that begins before a range which starts inside it is not
         * covered, while one that begins inside a range that ends inside it is.
         * Every reachable throwing instruction emits at least one bytecode (even
         * a deferred new-instance emits its `new; dup`), so no run is empty and
         * no zero-length row -- a ClassFormatError under verification -- is
         * produced.
         */
        List<CodeWriter.Label[]> throwingSpans(TypeInference.TryBlock t) {
            List<CodeWriter.Label[]> spans = new ArrayList<>();
            int runStart = -1;
            for (TypeInference.Insn insn : lastNorm) {
                if (insn.offset < t.startOffset) continue;
                if (insn.offset >= t.endOffset) break;
                if (!types.isReachable(insn.offset)) continue;
                if (TypeInference.canThrow(insn.opcode)) {
                    if (runStart < 0) runStart = insn.offset;
                    continue;
                }
                if (runStart >= 0) {
                    spans.add(span(runStart, insn.offset));
                    runStart = -1;
                }
            }
            if (runStart >= 0) spans.add(span(runStart, t.endOffset));
            return spans;
        }

        private CodeWriter.Label[] span(int from, int to) {
            CodeWriter.Label a = cur.labels.get(from);
            CodeWriter.Label b = cur.labels.get(to);
            // Both are bound by construction (every emitted instruction marks
            // its own label; the range end is in pendingRangeEnds). A miss is a
            // bug, and quietly dropping the row would delete a catch clause.
            if (a == null || b == null) {
                throw new TranslationException("narrow try span [0x"
                    + Integer.toHexString(from) + ",0x" + Integer.toHexString(to)
                    + ") has an unbound label");
            }
            return new CodeWriter.Label[]{a, b};
        }

        /**
         * LineNumberTable (JVMS 4.7.12) from the DEX debug info, which the
         * parser keeps and enjarify discards. This is what makes a guest stack
         * trace name a source file and line instead of just a method.
         *
         * The filter is load-bearing, not defensive. R8 canonicalises debug
         * info and SHARES one debug_info_item across many structurally
         * identical methods, sizing it for the longest sharer: on mindustry,
         * 2,523,007 of 3,960,100 decoded positions (64%) have an address past
         * the end of the method that references them. Only an address we
         * actually emitted bytecode for can be mapped, so the rest are
         * dropped.
         */
        void emitLineNumbers(Map<Integer, CodeWriter.Label> labelMap) {
            // The flat table rather than code.positions(): the object view
            // allocates one Position per entry, and this loop runs 3.76 billion
            // times over a large app (measured on TikTok 2024604030). Same
            // entries, same order -- see DexCode.positionTable().
            int[] positions = code.positionTable();
            if (positions.length == 0) return;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < positions.length; i += 2) {
                int address = positions[i];
                int line = positions[i + 1];
                // line_number is a u2; a DEX line is a uleb128 and can exceed
                // it. An out-of-range line cannot be encoded, and a truncated
                // one would point at the wrong source line.
                if (line < 0 || line > 0xffff) continue;
                CodeWriter.Label l = labelMap.get(address);
                if (l == null || !l.isMarked()) continue;
                // Inlined frames emit several positions at one address; the
                // first is the one the stack trace should name.
                if (!seen.add(address)) continue;
                // Routed by owner so a position inside a hoisted region lands in
                // that region's own LineNumberTable, where its label resolves.
                ownerOf(l).lineNumber(line, l);
            }
        }

        // --------------------------------------------------------- frames

        /**
         * The verifier state at a JVM bytecode offset. Because every translated
         * Dalvik instruction is stack-neutral, the stack is empty at every
         * boundary except an exception handler's first instruction, which the
         * JVM enters with the thrown object on the stack.
         */
        StackMapWriter.Frame frameFor(PartState ps, int bytecodeOffset) {
            // A MONITOR_COVER release stub first: it has no DEX offset, and its
            // bytecode offset can coincide with an end-of-code label's.
            for (Map.Entry<CodeWriter.Label, int[]> e : coverStubs.entrySet()) {
                if (e.getKey().offset == bytecodeOffset) return coverStubFrame(e.getValue());
            }
            // dexOffsetForBytecode builds the map, which is also what populates
            // stubOffsets, so it has to run before stubOffsets is consulted.
            Integer viaLabel = ps.dexOffsetForBytecode(bytecodeOffset);
            Integer stubFor = ps.stubOffsets.get(bytecodeOffset);
            Integer dexOffset = stubFor != null ? stubFor : viaLabel;
            if (dexOffset == null) return null;
            RegisterState st = types.entryState(dexOffset);
            if (st == null) return null;

            StackMapWriter.VType[] slots = new StackMapWriter.VType[locals.slotCount()];
            Arrays.fill(slots, StackMapWriter.VType.TOP);
            // Parameter slots keep their declared types: the prologue is the only
            // writer, so nothing in the body can change them. A chain part has
            // DIFFERENT parameters, which its own prologue copies away into
            // scratch before the body runs, so its parameter slots are dead here
            // and TOP is both correct and the only safe answer.
            if (ps.index == 0) fillParameterSlots(slots, dexOffset);
            // ...except the receiver a part of a spilled constructor keeps in
            // slot 0 (MethodSplitter, THE <init> SPILL): its prologue never
            // moves it, nothing writes it, and its portals read it.
            else if (ps.part != null && ps.part.receiver) {
                slots[0] = StackMapWriter.VType.object(ownerName);
            }
            for (Locals.Entry e : locals.entries()) {
                DexType t = st.get(e.register);
                if (t == null || t.isDead()) continue;
                if ((t.scalar() & e.scalar) == 0) continue;
                slots[e.slot] = vtypeFor(ps, e.scalar, t);
            }
            String handlerType = stubFor != null ? handlerTypes.get(stubFor)
                                                 : ps.handlerEntryTypes.get(dexOffset);
            StackMapWriter.VType[] stack = handlerType == null
                ? new StackMapWriter.VType[0]
                : new StackMapWriter.VType[]{ StackMapWriter.VType.object(handlerType) };
            // A frame lists LOCAL VARIABLES, not slots: a long/double is ONE
            // entry that implies the following slot. Handing over the raw
            // slot-indexed array yields "StackMapTable format error: bad type
            // array size", which HotSpot's verifier reports.
            return new StackMapWriter.Frame(StackMapWriter.compressSlots(slots), stack);
        }

        /**
         * The frame at a release stub: TOP in every local but the lock slots,
         * which are `java/lang/Object`, and the thrown exception on the stack
         * (a catch_type 0 row pushes java/lang/Throwable, JVMS 4.10.1.6). Every
         * frame the stub's rows cover is assignable to it (JVMS 4.10.1.2: any
         * type is assignable to top, a reference to Object), which is what lets
         * a stub cover any lock-holding point without widening a real handler's
         * frame, and the stub reads nothing else.
         */
        StackMapWriter.Frame coverStubFrame(int[] lockSlots) {
            StackMapWriter.VType[] slots = new StackMapWriter.VType[locals.slotCount()];
            Arrays.fill(slots, StackMapWriter.VType.TOP);
            for (int slot : lockSlots) slots[slot] = StackMapWriter.VType.object("java/lang/Object");
            return new StackMapWriter.Frame(StackMapWriter.compressSlots(slots),
                    new StackMapWriter.VType[]{ StackMapWriter.VType.object("java/lang/Throwable") });
        }

        void fillParameterSlots(StackMapWriter.VType[] slots, int dexOffset) {
            int slot = 0;
            if (!isStatic) {
                // `this` is the one parameter whose TYPE changes without the
                // slot being written: it enters a constructor as
                // uninitializedThis and becomes the class itself the moment the
                // super/this <init> runs (JVMS 4.10.1.6). Declaring
                // uninitializedThis in EVERY frame of a constructor is therefore
                // wrong after that point, and the verifier says so --
                //   Type 'com/applovin/impl/sdk/v' (current frame, locals[0])
                //   is not assignable to uninitializedThis (stack map, locals[0])
                slots[slot++] = (method.isConstructor() && thisUninitAt(dexOffset))
                    ? StackMapWriter.VType.UNINITIALIZED_THIS
                    : StackMapWriter.VType.object(ownerName);
            }
            for (String p : method.proto().parameterTypes()) {
                if (slot >= slots.length) break;
                slots[slot] = StackMapWriter.VType.forDescriptor(p);
                slot += isWideDesc(p) ? 2 : 1;
            }
        }

        boolean thisUninitAt(int dexOffset) {
            // No analysis (a caller that never ran computeThisUninit): the entry
            // state is the safe answer.
            return thisUninit == null || thisUninit.contains(dexOffset);
        }

        /**
         * The DEX offsets at which JVM local 0 is still {@code uninitializedThis}.
         *
         * This CANNOT be read off the Dalvik receiver register, which is what it
         * used to do. JVM local 0 is a parameter slot the body never writes, so
         * it holds the receiver for the whole method; the Dalvik receiver
         * register is an ordinary register that R8 reuses freely. Measured:
         * androidx/navigation/NavDestinationBuilder.&lt;init&gt;(Navigator,KClass,Map)
         * has p0 == v11 and does `new-instance v11, IllegalArgumentException`
         * BEFORE any super call, on the path that throws. After that the register
         * holds uninitialized(27), so the old test said "initialized" and every
         * frame from there on declared the class -- while HotSpot still carried
         * flagThisUninit:
         *   Inconsistent stackmap frames at branch target 235
         *   Type uninitializedThis (current frame, locals[0]) is not assignable
         *   to 'androidx/navigation/NavDestinationBuilder' (stack map, locals[0])
         *
         * So compute what the JVM computes. JVMS 4.10.1.9 clears flagThisUninit
         * only on the invokespecial that initializes uninitializedThis, and
         * 4.10.1.4 mergedFlags UNIONs the flag at a join, which makes this a
         * plain forward may-reachability: an offset is uninit if ANY path from
         * the method entry reaches it without passing that call.
         *
         * The exception edges are the WIDE ones (JVMS 4.10.1.6 applies a handler
         * to every instruction in its range) even when the register analysis fell
         * back to ART's narrower rule, because this models HotSpot's verifier,
         * not ART's. And the edge OUT of the initializing call itself carries the
         * PRE-call flags, per 4.10.1.9's
         * {@code ExceptionStackFrame = frame(Locals, [], Flags)}: an <init> that
         * throws leaves the object uninitialized.
         */
        void computeThisUninit(List<TypeInference.Insn> norm,
                               List<TypeInference.TryBlock> tries) {
            thisUninit = new HashSet<>();
            if (isStatic || !method.isConstructor() || norm.isEmpty()) return;
            Map<Integer, TypeInference.Insn> byOffset = new HashMap<>(norm.size() * 2);
            for (TypeInference.Insn in : norm) byOffset.put(in.offset, in);

            ArrayDeque<Integer> work = new ArrayDeque<>();
            work.add(norm.get(0).offset);
            thisUninit.add(norm.get(0).offset);
            while (!work.isEmpty()) {
                int at = work.remove();
                TypeInference.Insn in = byOffset.get(at);
                if (in == null) continue;
                // Every handler that covers this instruction is entered with the
                // flags as they stand HERE, so the edge is taken whether or not
                // this instruction is the initializing call.
                for (TypeInference.TryBlock t : tries) {
                    if (at < t.startOffset || at >= t.endOffset) continue;
                    for (TypeInference.Catch c : t.catches) {
                        if (thisUninit.add(c.handlerOffset)) work.add(c.handlerOffset);
                    }
                }
                if (initializesThis(in)) continue;   // the flag stops here
                for (int s : normalSuccessors(in)) {
                    if (byOffset.containsKey(s) && thisUninit.add(s)) work.add(s);
                }
            }
        }

        /** True for the {@code invoke-direct} that runs this constructor's own
         *  {@code super(...)} or {@code this(...)}, i.e. the one whose receiver
         *  the analysis proved is uninitializedThis. */
        boolean initializesThis(TypeInference.Insn in) {
            if (TypeInference.familyOf(in.opcode) != TypeInference.F_INVOKE_DIRECT) return false;
            TypeInference.InsnTypes it = types.typesFor(in.offset);
            if (it == null || it.uses.length == 0) return false;
            DexType.Ref r = it.uses[0].type.ref();
            return r != null && r.kind == DexType.Ref.KIND_UNINIT_THIS;
        }

        /** Non-exception successors, in the {@link TypeInference.Insn} arg
         *  conventions. */
        int[] normalSuccessors(TypeInference.Insn in) {
            switch (TypeInference.familyOf(in.opcode)) {
                case TypeInference.F_RETURN:
                case TypeInference.F_THROW:
                    return new int[0];
                case TypeInference.F_GOTO:
                    return new int[]{ (int) in.args[0] };
                case TypeInference.F_IF:
                    return new int[]{ (int) in.args[2], in.nextOffset };
                case TypeInference.F_IFZ:
                    return new int[]{ (int) in.args[1], in.nextOffset };
                case TypeInference.F_SWITCH: {
                    int[] out = new int[in.switchTargets.length + 1];
                    System.arraycopy(in.switchTargets, 0, out, 0, in.switchTargets.length);
                    out[out.length - 1] = in.nextOffset;
                    return out;
                }
                default:
                    return new int[]{ in.nextOffset };
            }
        }

        StackMapWriter.VType vtypeFor(PartState ps, int scalar, DexType t) {
            switch (scalar) {
                case DexType.INT:    return StackMapWriter.VType.INTEGER;
                case DexType.FLOAT:  return StackMapWriter.VType.FLOAT;
                case DexType.LONG:   return StackMapWriter.VType.LONG;
                case DexType.DOUBLE: return StackMapWriter.VType.DOUBLE;
                case DexType.OBJ: {
                    DexType.Ref r = t.ref();
                    if (r == null) return StackMapWriter.VType.NULL;
                    switch (r.kind) {
                        case DexType.Ref.KIND_NULL: return StackMapWriter.VType.NULL;
                        case DexType.Ref.KIND_UNINIT_THIS:
                            return StackMapWriter.VType.UNINITIALIZED_THIS;
                        case DexType.Ref.KIND_UNINIT: {
                            CodeWriter.Label l = newLabels.get(r.newOffset);
                            if (l == null) return StackMapWriter.VType.TOP;
                            // An Uninitialized_variable_info names the OFFSET of
                            // its `new` (JVMS 4.7.4), so a label from another
                            // part would name an unrelated instruction in this
                            // part's byte stream. MethodSplitter refuses a cut
                            // that would let an uninitialized value cross, so
                            // reaching here means the plan and the emission
                            // disagree: fail loudly and let translate() fall
                            // back rather than publish a wrong frame.
                            if (ownerOf(l) != ps.writer) {
                                throw new TranslationException(
                                    "uninitialized(0x" + Integer.toHexString(r.newOffset)
                                    + ") crossed a part boundary");
                            }
                            return StackMapWriter.VType.uninitialized(l);
                        }
                        case DexType.Ref.KIND_CLASS:
                            return StackMapWriter.VType.object(r.name);
                        default:
                            return StackMapWriter.VType.TOP;
                    }
                }
                default: return StackMapWriter.VType.TOP;
            }
        }

        /**
         * Bytecode offset -> the DEX offset whose entry state describes it.
         *
         * Several DEX offsets routinely share ONE bytecode offset, because
         * several things translate to no bytes at all: an unreachable
         * instruction (emitBody marks a bare label and moves on), a `nop`, and
         * a try-range end bound to the following instruction. So this is a
         * choice, and it has to be a deterministic one -- it used to be
         * whichever entry an IdentityHashMap scan hit first, and identity hash
         * codes vary with allocation history, so converting the SAME class
         * twice in one process could emit different StackMapTables and
         * different bytes (measured on androidx/multidex/MultiDex: 10748 vs
         * 10739). Building it once in ascending DEX order removes both the
         * non-determinism and a per-frame linear scan. Lives on PartState
         * because a DEX offset lands at a different bci in each part.
         */

        /** Which of two DEX offsets sharing a bytecode offset should supply the frame. */
        private boolean betterFrameSource(PartState ps, int candidate, int incumbent) {
            // A handler entry wins: its frame is the only one carrying the
            // thrown object on the stack, and getting that wrong is a verify
            // error rather than a cosmetic difference.
            boolean ch = ps.handlerEntryTypes.containsKey(candidate);
            boolean ih = ps.handlerEntryTypes.containsKey(incumbent);
            if (ch != ih) return ch;
            // Otherwise prefer an offset the analysis actually reached: an
            // unreachable one has no entry state, and picking it would drop
            // the frame and with it the whole StackMapTable.
            boolean cr = types.entryState(candidate) != null;
            boolean ir = types.entryState(incumbent) != null;
            if (cr != ir) return cr;
            // Among equals the LAST offset wins. Several DEX offsets share one
            // bytecode offset only when everything between them emits nothing (a
            // nop, an unreachable instruction, a try-range end), so to the JVM
            // they are ONE program point and the published frame has to satisfy
            // every edge into any of them. The earlier offsets fall through into
            // the later one, so the last offset's entry state IS that join;
            // publishing the first one's is a strict subset of the predecessors.
            //
            // Caught by mindustry's arc/net/dns/JndiContextNameserverProvider
            // $Inner.getNameservers: the `nop` at 0x2f (a handler entry where v2
            // is still `const/4 v2, 0`) collapses onto the same bci as
            // `move-object v1, v2` at 0x30, whose state also merges the goto at
            // 0x2e where v2 holds the caught String. First-wins published null:
            //   Type 'java/lang/String' (locals[3]) is not assignable to null
            return true;
        }
    }

    // ==================================================================
    // Register-to-local allocation
    // ==================================================================

    /**
     * Assigns one JVM local per (Dalvik register, scalar) pair that the method
     * actually uses. Slots start above the parameter block, which the JVM
     * reserves for the incoming arguments.
     */
    static final class Locals {
        static final class Entry {
            final int register, scalar, slot;
            Entry(int register, int scalar, int slot) {
                this.register = register; this.scalar = scalar; this.slot = slot;
            }
        }

        private final Map<Long, Integer> slots = new HashMap<>();
        private final List<Entry> entries = new ArrayList<>();
        private int next;

        private Locals(int firstFree) { this.next = firstFree; }

        static Locals allocate(TypeInference.Result types, List<TypeInference.Insn> code,
                               int registerCount, int argSlots) {
            Locals l = new Locals(argSlots);
            for (TypeInference.Insn insn : code) {
                TypeInference.InsnTypes it = types.typesFor(insn.offset);
                if (it == null) continue;
                for (TypeInference.Use u : it.uses) l.ensure(u.register, u.scalar);
                for (TypeInference.Def d : it.defs) {
                    for (int scalar : DexType.scalarBits(d.scalars)) l.ensure(d.register, scalar);
                }
            }
            return l;
        }

        void ensure(int register, int scalar) {
            if (scalar == DexType.NONE) return;
            long key = key(register, scalar);
            if (slots.containsKey(key)) return;
            int slot = next;
            next += DexType.isWide(scalar) ? 2 : 1;
            slots.put(key, slot);
            entries.add(new Entry(register, scalar, slot));
        }

        int slotOrMinusOne(int register, int scalar) {
            Integer s = slots.get(key(register, scalar));
            return s == null ? -1 : s;
        }

        int slotCount() { return next; }
        List<Entry> entries() { return entries; }

        private static long key(int register, int scalar) {
            return ((long) register << 8) | (scalar & 0xff);
        }
    }

    // ==================================================================
    // Resolver
    // ==================================================================

    /**
     * One constructor call site whose invokespecial must name a different class
     * from the one the DEX names, because R8 deleted the constructor in between.
     * See InitRepointPlan.
     */
    static final class Repoint {
        final int dexOffset;
        /** The receiver is the enclosing constructor's own uninitialized `this`. */
        final boolean uninitThis;
        /** Internal name the new-instance created, null when uninitThis. */
        final String allocated;
        /** Internal name to name on the invokespecial instead. */
        final String owner;
        /** The DEX's own proto, which still describes the operands on the stack. */
        final DexFile.Proto proto;
        /**
         * The descriptor to write on the invokespecial. Equal to the proto's
         * except where the constructor had to be synthesised under a padded
         * descriptor, in which case padCount extra nulls are pushed first.
         */
        final String emitDescriptor;
        final int padCount;
        /** The negative index standing in for a DEX method index. */
        int index;

        Repoint(int dexOffset, boolean uninitThis, String allocated, String owner,
                DexFile.Proto proto, String emitDescriptor) {
            this.dexOffset = dexOffset;
            this.uninitThis = uninitThis;
            this.allocated = allocated;
            this.owner = owner;
            this.proto = proto;
            this.emitDescriptor = emitDescriptor;
            this.padCount = InitRepointPlan.padCount(emitDescriptor, proto.descriptor());
        }
    }

    /**
     * Answers TypeInference's questions about the constant pool.
     *
     * A NEGATIVE method index is not in the DEX at all: it is a re-pointed
     * constructor call this translator invented, and it is answered from the
     * per-method table instead. Negative is what makes the two spaces provably
     * disjoint without needing to know how many method_ids the DEX has.
     */
    static final class DexResolver implements TypeInference.Resolver {
        private final DexFile dex;
        private final Map<Integer, Repoint> synthetic;

        DexResolver(DexFile dex) { this(dex, java.util.Collections.emptyMap()); }

        DexResolver(DexFile dex, Map<Integer, Repoint> synthetic) {
            this.dex = dex;
            this.synthetic = synthetic;
        }

        private Repoint repoint(int methodIndex) {
            if (methodIndex >= 0) return null;
            Repoint r = synthetic.get(methodIndex);
            if (r == null) throw new TranslationException("no re-pointed method " + methodIndex);
            return r;
        }

        @Override public String typeDescriptor(int typeIndex) {
            return dex.typeDescriptor(typeIndex);
        }
        @Override public String fieldDescriptor(int fieldIndex) {
            return dex.fieldRef(fieldIndex).type();
        }
        @Override public String fieldOwner(int fieldIndex) {
            return DexFile.internalName(dex.fieldRef(fieldIndex).declaringClass());
        }
        @Override public String methodReturnDescriptor(int methodIndex) {
            Repoint r = repoint(methodIndex);
            return r != null ? "V" : dex.methodRef(methodIndex).proto().returnType();
        }
        @Override public String methodName(int methodIndex) {
            Repoint r = repoint(methodIndex);
            return r != null ? "<init>" : dex.methodRef(methodIndex).name();
        }
        @Override public String methodOwner(int methodIndex) {
            Repoint r = repoint(methodIndex);
            return r != null ? r.owner
                             : DexFile.internalName(dex.methodRef(methodIndex).declaringClass());
        }
        @Override public String[] methodSpacedParamDescriptors(int methodIndex, boolean isStatic) {
            Repoint r = repoint(methodIndex);
            String receiver = r != null ? "L" + r.owner + ";" : null;
            DexFile.Proto proto;
            if (r != null) {
                proto = r.proto;
            } else {
                DexFile.MethodRef m = dex.methodRef(methodIndex);
                receiver = m.declaringClass();
                proto = m.proto();
            }
            List<String> out = new ArrayList<>();
            if (!isStatic) out.add(receiver);
            for (String p : proto.parameterTypes()) {
                out.add(p);
                if (isWideDesc(p)) out.add(null);
            }
            return out.toArray(new String[0]);
        }
        @Override public String[] protoSpacedParamDescriptors(int protoIndex) {
            return spaced(dex.proto(protoIndex));
        }
        @Override public String[] callSiteSpacedParamDescriptors(int callSiteIndex) {
            return spaced(dex.callSite(callSiteIndex).methodType());
        }

        private static String[] spaced(DexFile.Proto p) {
            if (p == null) return null;
            List<String> out = new ArrayList<>();
            for (String t : p.parameterTypes()) {
                out.add(t);
                if (isWideDesc(t)) out.add(null);
            }
            return out.toArray(new String[0]);
        }
    }

    // ==================================================================
    // Normalisation: Instruction -> TypeInference.Insn
    // ==================================================================

    /**
     * Rewrites decoded instructions into the flat, family-indexed form
     * TypeInference consumes (its {@code Insn.args} conventions). This is where
     * the many DEX encodings of "the same operation" collapse: const/4, const/16,
     * const, const/high16 all become CONST32 with a sign-extended value; the
     * /2addr arithmetic forms become the same family as their three-register
     * siblings; branch offsets become absolute targets.
     */
    static final class Normalizer {

        static List<TypeInference.Insn> normalize(Instruction[] decoded, DexFile dex,
                                                  DexMethod method) {
            List<TypeInference.Insn> out = new ArrayList<>(decoded.length);
            for (int i = 0; i < decoded.length; i++) {
                Instruction in = decoded[i];
                if (in.isPayload()) continue;   // data, not code
                out.add(one(in, decoded, i, dex, method));
            }
            return out;
        }

        static TypeInference.Insn one(Instruction in, Instruction[] all, int idx,
                                      DexFile dex, DexMethod method) {
            int op = in.opcode();
            int family = TypeInference.familyOf(op);
            int off = in.address(), next = in.nextAddress();
            long[] args;
            int[] regs = new int[0];
            int[] switchTargets = new int[0];
            String moveResultDesc = null;

            switch (family) {
                case TypeInference.F_NOP:
                    args = new long[0];
                    break;
                case TypeInference.F_MOVE:
                case TypeInference.F_MOVE_WIDE:
                    args = new long[]{ in.a(), in.b() };
                    break;
                case TypeInference.F_MOVE_RESULT:
                    args = new long[]{ in.a() };
                    moveResultDesc = moveResultDescriptor(all, idx, dex);
                    break;
                case TypeInference.F_RETURN:
                    args = op == 0x0e ? new long[0] : new long[]{ in.a() };
                    break;
                case TypeInference.F_CONST32:
                case TypeInference.F_CONST64:
                    args = new long[]{ in.a(), in.literal() };
                    break;
                case TypeInference.F_CONST_STRING:
                case TypeInference.F_CONST_CLASS:
                case TypeInference.F_NEW_INSTANCE:
                    args = new long[]{ in.a(), in.index() };
                    break;
                case TypeInference.F_MONITOR_ENTER:
                case TypeInference.F_MONITOR_EXIT:
                case TypeInference.F_THROW:
                    args = new long[]{ in.a() };
                    break;
                case TypeInference.F_CHECK_CAST:
                    args = new long[]{ in.a(), in.index() };
                    break;
                case TypeInference.F_INSTANCE_OF:
                    args = new long[]{ in.a(), in.b(), in.index() };
                    break;
                case TypeInference.F_ARRAY_LEN:
                    args = new long[]{ in.a(), in.b() };
                    break;
                case TypeInference.F_NEW_ARRAY:
                    args = new long[]{ in.a(), in.b(), in.index() };
                    break;
                case TypeInference.F_FILLED_NEW_ARRAY:
                    args = new long[]{ in.index() };
                    regs = in.argRegisters();
                    break;
                case TypeInference.F_FILL_ARRAY_DATA:
                    args = new long[]{ in.a(), in.target() };
                    break;
                case TypeInference.F_GOTO:
                    args = new long[]{ in.target() };
                    break;
                case TypeInference.F_SWITCH:
                    args = new long[]{ in.a(), in.target() };
                    // Read the targets from the SWITCH, never from the payload:
                    // the DEX encoding stores them relative to the switch's own
                    // address, so the payload's array is raw relative offsets
                    // (Instruction.switchTargets doc). Using the payload's copy
                    // produced labels at addresses that do not exist, which
                    // surfaced as 1,083 "label referenced but never marked".
                    switchTargets = in.switchTargets();
                    break;
                case TypeInference.F_CMP:
                    args = new long[]{ in.a(), in.b(), in.c() };
                    break;
                case TypeInference.F_IF:
                    args = new long[]{ in.a(), in.b(), in.target() };
                    break;
                case TypeInference.F_IFZ:
                    args = new long[]{ in.a(), in.target() };
                    break;
                case TypeInference.F_ARRAY_GET:
                case TypeInference.F_ARRAY_PUT:
                    args = new long[]{ in.a(), in.b(), in.c() };
                    break;
                case TypeInference.F_INSTANCE_GET:
                case TypeInference.F_INSTANCE_PUT:
                    args = new long[]{ in.a(), in.b(), in.index() };
                    break;
                case TypeInference.F_STATIC_GET:
                case TypeInference.F_STATIC_PUT:
                    args = new long[]{ in.a(), in.index() };
                    break;
                case TypeInference.F_INVOKE_VIRTUAL:
                case TypeInference.F_INVOKE_SUPER:
                case TypeInference.F_INVOKE_DIRECT:
                case TypeInference.F_INVOKE_STATIC:
                case TypeInference.F_INVOKE_INTERFACE:
                    args = new long[]{ in.index() };
                    regs = in.argRegisters();
                    break;
                case TypeInference.F_INVOKE_CUSTOM:
                    args = new long[]{ in.index() };          // call_site_ids index
                    regs = in.argRegisters();
                    break;
                case TypeInference.F_INVOKE_POLYMORPHIC:
                    // 45cc/4rcc carries TWO indices: the signature-polymorphic
                    // method (vB) and the call site's real proto (vH).
                    args = new long[]{ in.index(), in.h() };
                    regs = in.argRegisters();
                    break;
                case TypeInference.F_CONST_METHOD_HANDLE:
                case TypeInference.F_CONST_METHOD_TYPE:
                    args = new long[]{ in.a(), in.index() };
                    break;
                case TypeInference.F_UNARY_OP:
                    args = new long[]{ in.a(), in.b() };
                    break;
                case TypeInference.F_BINARY_OP:
                    // /2addr (0xb0..0xcf) is [destAndSrc1, src2]; the wide forms
                    // are [dest, src1, src2].
                    args = (op >= 0xb0 && op <= 0xcf)
                        ? new long[]{ in.a(), in.b() }
                        : new long[]{ in.a(), in.b(), in.c() };
                    break;
                case TypeInference.F_BINARY_OP_CONST:
                    args = new long[]{ in.a(), in.b(), in.literal() };
                    break;
                default:
                    args = new long[0];
                    break;
            }
            return new TypeInference.Insn(off, next, op, args, regs, switchTargets,
                moveResultDesc, -1, null);
        }

        /** The descriptor a move-result receives: the preceding producer's type. */
        static String moveResultDescriptor(Instruction[] all, int idx, DexFile dex) {
            if (all[idx].opcode() == 0x0d) return "Ljava/lang/Throwable;";  // move-exception
            for (int j = idx - 1; j >= 0; j--) {
                Instruction p = all[j];
                if (p.isPayload()) continue;
                // Both invokedynamic-family producers have their result type in
                // an operand rather than in the referenced method, and both must
                // be tested BEFORE isInvoke: invoke-polymorphic sets the invoke
                // flag but MethodHandle.invokeExact is declared to return
                // Object, so the method ref would give the wrong type.
                if (p.opcode() == Opcodes.INVOKE_POLYMORPHIC
                        || p.opcode() == Opcodes.INVOKE_POLYMORPHIC_RANGE) {
                    DexFile.Proto pr = dex.proto(p.h());
                    return pr == null ? null : pr.returnType();
                }
                if (p.opcode() == Opcodes.INVOKE_CUSTOM
                        || p.opcode() == Opcodes.INVOKE_CUSTOM_RANGE) {
                    DexFile.Proto pr = dex.callSite(p.index()).methodType();
                    return pr == null ? null : pr.returnType();
                }
                if (p.isInvoke()) return dex.methodRef(p.index()).proto().returnType();
                if (p.opcode() == 0x24 || p.opcode() == 0x25) {   // filled-new-array
                    return dex.typeDescriptor(p.index());
                }
                return null;
            }
            return null;
        }

        static Instruction at(Instruction[] all, int address) {
            for (Instruction i : all) if (i.address() == address) return i;
            return null;
        }
    }

    // ==================================================================
    // Small shared helpers
    // ==================================================================

    static boolean isWideDesc(String desc) {
        return "J".equals(desc) || "D".equals(desc);
    }

    static int scalarOf(String desc) {
        return DexType.scalarFromDescriptor(desc);
    }

    static int loadOp(int scalar) {
        switch (scalar) {
            case DexType.INT:    return CodeWriter.ILOAD;
            case DexType.FLOAT:  return CodeWriter.FLOAD;
            case DexType.LONG:   return CodeWriter.LLOAD;
            case DexType.DOUBLE: return CodeWriter.DLOAD;
            case DexType.OBJ:    return CodeWriter.ALOAD;
            default: throw new TranslationException("load of scalar " + scalar);
        }
    }

    static int storeOp(int scalar) {
        switch (scalar) {
            case DexType.INT:    return CodeWriter.ISTORE;
            case DexType.FLOAT:  return CodeWriter.FSTORE;
            case DexType.LONG:   return CodeWriter.LSTORE;
            case DexType.DOUBLE: return CodeWriter.DSTORE;
            case DexType.OBJ:    return CodeWriter.ASTORE;
            default: throw new TranslationException("store of scalar " + scalar);
        }
    }

    static int returnOp(int scalar) {
        switch (scalar) {
            case DexType.INT:    return CodeWriter.IRETURN;
            case DexType.FLOAT:  return CodeWriter.FRETURN;
            case DexType.LONG:   return CodeWriter.LRETURN;
            case DexType.DOUBLE: return CodeWriter.DRETURN;
            case DexType.OBJ:    return CodeWriter.ARETURN;
            default: throw new TranslationException("return of scalar " + scalar);
        }
    }

    /** aget family (0x44..0x4a) -> the matching JVM array load. */
    static int arrayLoadOp(int opcode, DexType array) {
        String elem = array == null ? null : array.arrayElementDescriptor();
        switch (opcode) {
            // 0x44 aget covers int[] AND float[], 0x45 aget-wide covers long[]
            // AND double[]: the DEX opcode names the element WIDTH, not its
            // type, so only the array's own type can choose. iaload against a
            // float[] is a verify error ("Bad type"), and the aput direction
            // reads as "integer_type is not assignable from float_type".
            case 0x44: return "F".equals(elem) ? CodeWriter.FALOAD : CodeWriter.IALOAD;
            case 0x45: return "D".equals(elem) ? CodeWriter.DALOAD : CodeWriter.LALOAD;
            case 0x46: return CodeWriter.AALOAD;
            case 0x47: return CodeWriter.BALOAD;   // aget-boolean
            case 0x48: return CodeWriter.BALOAD;   // aget-byte
            case 0x49: return CodeWriter.CALOAD;
            case 0x4a: return CodeWriter.SALOAD;
            default: throw new TranslationException("aget 0x" + Integer.toHexString(opcode));
        }
    }

    /** aput family (0x4b..0x51) -> the matching JVM array store. */
    static int arrayStoreOp(int opcode, DexType array) {
        String elem = array == null ? null : array.arrayElementDescriptor();
        switch (opcode) {
            // See arrayLoadOp: aput / aput-wide are element-type ambiguous.
            case 0x4b: return "F".equals(elem) ? CodeWriter.FASTORE : CodeWriter.IASTORE;
            case 0x4c: return "D".equals(elem) ? CodeWriter.DASTORE : CodeWriter.LASTORE;
            case 0x4d: return CodeWriter.AASTORE;
            case 0x4e: return CodeWriter.BASTORE;   // aput-boolean
            case 0x4f: return CodeWriter.BASTORE;   // aput-byte
            case 0x50: return CodeWriter.CASTORE;
            case 0x51: return CodeWriter.SASTORE;
            default: throw new TranslationException("aput 0x" + Integer.toHexString(opcode));
        }
    }

    static int arrayStoreOpForDesc(String elem) {
        switch (elem) {
            case "Z": case "B": return CodeWriter.BASTORE;
            case "C": return CodeWriter.CASTORE;
            case "S": return CodeWriter.SASTORE;
            case "I": return CodeWriter.IASTORE;
            case "F": return CodeWriter.FASTORE;
            case "J": return CodeWriter.LASTORE;
            case "D": return CodeWriter.DASTORE;
            default: return CodeWriter.AASTORE;
        }
    }

    static int elementWidth(String elem) {
        switch (elem) {
            case "Z": case "B": return 1;
            case "C": case "S": return 2;
            case "I": case "F": return 4;
            case "J": case "D": return 8;
            default: return 4;
        }
    }

    /** newarray atype for a primitive element, or -1 for a reference element. */
    static int primitiveArrayCode(String elem) {
        switch (elem) {
            case "Z": return CodeWriter.T_BOOLEAN;
            case "C": return CodeWriter.T_CHAR;
            case "F": return CodeWriter.T_FLOAT;
            case "D": return CodeWriter.T_DOUBLE;
            case "B": return CodeWriter.T_BYTE;
            case "S": return CodeWriter.T_SHORT;
            case "I": return CodeWriter.T_INT;
            case "J": return CodeWriter.T_LONG;
            default: return -1;
        }
    }

    /** The 0x90..0xaf arithmetic block, in DEX order. */
    static int binaryOp(int opcode) {
        switch (opcode) {
            case 0x90: return CodeWriter.IADD;
            case 0x91: return CodeWriter.ISUB;
            case 0x92: return CodeWriter.IMUL;
            case 0x93: return CodeWriter.IDIV;
            case 0x94: return CodeWriter.IREM;
            case 0x95: return CodeWriter.IAND;
            case 0x96: return CodeWriter.IOR;
            case 0x97: return CodeWriter.IXOR;
            case 0x98: return CodeWriter.ISHL;
            case 0x99: return CodeWriter.ISHR;
            case 0x9a: return CodeWriter.IUSHR;
            case 0x9b: return CodeWriter.LADD;
            case 0x9c: return CodeWriter.LSUB;
            case 0x9d: return CodeWriter.LMUL;
            case 0x9e: return CodeWriter.LDIV;
            case 0x9f: return CodeWriter.LREM;
            case 0xa0: return CodeWriter.LAND;
            case 0xa1: return CodeWriter.LOR;
            case 0xa2: return CodeWriter.LXOR;
            case 0xa3: return CodeWriter.LSHL;
            case 0xa4: return CodeWriter.LSHR;
            case 0xa5: return CodeWriter.LUSHR;
            case 0xa6: return CodeWriter.FADD;
            case 0xa7: return CodeWriter.FSUB;
            case 0xa8: return CodeWriter.FMUL;
            case 0xa9: return CodeWriter.FDIV;
            case 0xaa: return CodeWriter.FREM;
            case 0xab: return CodeWriter.DADD;
            case 0xac: return CodeWriter.DSUB;
            case 0xad: return CodeWriter.DMUL;
            case 0xae: return CodeWriter.DDIV;
            case 0xaf: return CodeWriter.DREM;
            default: throw new TranslationException("binop 0x" + Integer.toHexString(opcode));
        }
    }

    /** The lit16 (0xd0..0xd8) and lit8 (0xd9..0xe2) blocks. */
    static int constBinaryOp(int opcode) {
        switch (opcode) {
            case 0xd0: case 0xd8: return CodeWriter.IADD;
            case 0xd2: case 0xda: return CodeWriter.IMUL;
            case 0xd3: case 0xdb: return CodeWriter.IDIV;
            case 0xd4: case 0xdc: return CodeWriter.IREM;
            case 0xd5: case 0xdd: return CodeWriter.IAND;
            case 0xd6: case 0xde: return CodeWriter.IOR;
            case 0xd7: case 0xdf: return CodeWriter.IXOR;
            case 0xe0: return CodeWriter.ISHL;
            case 0xe1: return CodeWriter.ISHR;
            case 0xe2: return CodeWriter.IUSHR;
            default: throw new TranslationException("lit binop 0x" + Integer.toHexString(opcode));
        }
    }
    /** The wrapper class whose TYPE field holds the primitive `desc`'s Class
     *  (javac's spelling of int.class etc.), or null when `desc` is not a
     *  primitive descriptor. V is included: void.class is Void.TYPE. */
    static String primitiveWrapper(String desc) {
        if (desc == null || desc.length() != 1) return null;
        switch (desc.charAt(0)) {
            case 'Z': return "java/lang/Boolean";
            case 'B': return "java/lang/Byte";
            case 'S': return "java/lang/Short";
            case 'C': return "java/lang/Character";
            case 'I': return "java/lang/Integer";
            case 'J': return "java/lang/Long";
            case 'F': return "java/lang/Float";
            case 'D': return "java/lang/Double";
            case 'V': return "java/lang/Void";
            default:  return null;
        }
    }

}
