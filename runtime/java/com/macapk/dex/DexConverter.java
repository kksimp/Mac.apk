// DexConverter -- the public entry point of the DEX -> JVM bytecode converter,
// a Java reimplementation of what the Python enjarify does.
//
// Two shapes of caller, deliberately:
//
//   convert(dex)                 the bulk path: every class at once, which is
//                                what building a whole classes.jar needs.
//   convertClass(dex, name)      the lazy path: ONE class, translated when a
//                                class loader's findClass asks for it. Most of an
//                                app's classes are never loaded (measured 54% of
//                                Calculator, 85% of GD Lite wasted), so the
//                                on-demand path skips that work entirely.
//   open(dex...)                 the efficient lazy path: parse once, keep the
//                                DexFile resident, answer many class requests.
//                                convertClass re-parses per call, which is fine
//                                for a one-off but wasteful in a loop.
//
// ERROR POLICY: one bad method must not lose its class, and one bad class must
// not lose the APK. enjarify made the same choice (main.py collects per-class
// errors and keeps going) and it is what lets an app load when a single
// unusual method fails to translate: the class still loads, and the failure
// only surfaces if that method is actually called. Failures are recorded in
// errors() rather than thrown.

package com.macapk.dex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DexConverter {

    private DexConverter() {}

    // ==================================================================
    // Bulk API
    // ==================================================================

    /**
     * Translate every class in one DEX.
     *
     * @return internal class name ("com/example/Foo") -> class file bytes,
     *         in the dex's own class order.
     */
    public static Map<String, byte[]> convert(byte[] dex) {
        return open(dex).convertAll();
    }

    /**
     * Translate ONE class out of a DEX.
     *
     * @param internalName "com/example/Foo" (also accepts "Lcom/example/Foo;"
     *                     and "com.example.Foo", since callers disagree).
     * @return the class file bytes, or null if this DEX does not define it.
     */
    public static byte[] convertClass(byte[] dex, String internalName) {
        return open(dex).classBytes(internalName);
    }

    /** Open a resident session over one or more DEX files. */
    public static Session open(byte[]... dexes) {
        return new Session(dexes);
    }

    // ==================================================================
    // Session
    // ==================================================================

    /**
     * A parsed DEX (or several), kept resident so many class requests share one
     * parse. Parsing is the cheap half: measured on Candy Crush's 28 MB of dex,
     * parsing is ~0.3 s against ~15 s to translate, so holding the parse and
     * skipping unused translation is the whole win of the lazy path.
     */
    public static final class Session {
        /** Classes in flight at once on the parallel path. Bounds peak memory to
         *  that many class files while still keeping every core busy. */
        private static final int BATCH = 512;
        /** Worker cap that holds whatever the heap: conversion is CPU bound and
         *  allocation heavy, and 8 workers is what an 8 GB box was tuned for. */
        private static final int MIN_THREAD_CAP = 8;
        /** Heap each worker is budgeted past {@link #MIN_THREAD_CAP}. A
         *  worker's live set is one class's translation plus its share of the
         *  in-flight window; 256 MB leaves room for the session's resident parse
         *  (Play services' dex set is large) on top of that. */
        private static final long HEAP_PER_THREAD = 256L << 20;

        private final List<DexFile> files = new ArrayList<>();
        private final Map<String, DexClass> byName = new LinkedHashMap<>();
        private final Map<String, String> errors = new LinkedHashMap<>();
        /**
         * Reference least-upper-bound oracle, shared by every class in the
         * session so the memo is warm and so a class in classes2.dex can see a
         * superclass defined in classes.dex. Built once, read from many threads.
         */
        private final ClassHierarchyOracle hierarchy;
        /**
         * Session-wide "what does X enclose" / "resolve this name" index for
         * InnerClasses (JVMS 4.7.6) and NestHost/NestMembers (4.7.28/4.7.29),
         * for the identical cross-dex reason hierarchy exists: a nested class
         * can land in a different classes*.dex than its outer class (measured
         * on Signal 172401 -- see NestIndex's header). Built once, read from
         * many threads.
         */
        private final NestIndex nestIndex;
        /**
         * Which classes need a forwarding constructor R8 deleted, and which call
         * sites may be re-pointed at one. See InitRepointPlan.
         *
         * Built lazily: it needs a pass over every method in the session, which
         * the lazy single-class path (classBytes) should not pay for at open()
         * time, and which is wasted entirely on a session nobody converts.
         */
        private volatile InitRepointPlan repointPlan;
        /**
         * Which `invoke-super &lt;interface&gt;` sites must name a DIRECT
         * superinterface instead. See SuperInterfacePlan.
         *
         * Not lazy, unlike repointPlan: it reads class metadata on demand rather
         * than scanning every method, so constructing it is free.
         */
        private final SuperInterfacePlan superIfacePlan;

        Session(byte[]... dexes) {
            for (byte[] d : dexes) {
                if (d == null) continue;
                DexFile f = DexFile.parse(d);
                files.add(f);
                for (DexClass c : f.classes()) {
                    // First definition wins across dexes, matching enjarify's
                    // dedup so a multi-dex app converts identically either way.
                    byName.putIfAbsent(c.name(), c);
                }
            }
            ClassLoader lib = Options.hierarchyLoader != null
                    ? Options.hierarchyLoader : DexConverter.class.getClassLoader();
            hierarchy = new ClassHierarchyOracle(byName, lib);
            superIfacePlan = SuperInterfacePlan.of(byName, lib);
            nestIndex = NestIndex.of(byName);
        }

        /** Every class name this session can produce, in dex order. */
        public java.util.Set<String> classNames() { return byName.keySet(); }

        /**
         * Resolve the session's cross-class plans NOW instead of on the first
         * {@link #classBytes} call.
         *
         * <p>Exists for the lazy path: InitRepointPlan.build scans every
         * method in the session (measured 22 s on TikTok's 411k classes in a
         * cold JVM that is busy with other start-up work, where a warmed
         * standalone converter pays ~3 s), and classBytes resolves it lazily --
         * so without this, that whole scan lands on the FIRST class request,
         * inside the JVM's classloading machinery. A lazy class loader can
         * call this from a background daemon thread at session open so the
         * scan overlaps start-up instead of blocking it.
         * Idempotent and thread-safe (repointPlan double-checks under the
         * session lock); calling it changes no output bytes, only WHEN the
         * one-time work happens.
         */
        public void prepare() { repointPlan(); }

        public List<DexFile> dexFiles() { return files; }

        /** Per-class translation failures from the last convertAll/classBytes. */
        public Map<String, String> errors() { return errors; }

        /**
         * The constructor re-pointing plan, built once per session.
         *
         * Resolved by the callers BEFORE any worker thread starts so the plan is
         * a plain immutable value by then: nothing here may mutate once
         * conversion is in flight, which is what keeps the output byte-identical
         * from one thread to eight.
         */
        InitRepointPlan repointPlan() {
            InitRepointPlan p = repointPlan;
            if (p != null) return p;
            synchronized (this) {
                if (repointPlan == null) {
                    // DEX2JVM_INIT_REPOINT=0 turns the whole fix off for A/B.
                    String env = System.getenv("DEX2JVM_INIT_REPOINT");
                    repointPlan = "0".equals(env) ? InitRepointPlan.NONE
                                                  : InitRepointPlan.build(byName);
                }
                return repointPlan;
            }
        }

        public byte[] classBytes(String name) {
            DexClass c = byName.get(normalize(name));
            if (c == null) return null;
            InitRepointPlan plan = repointPlan();
            try {
                return convertOne(c, hierarchy, plan, superIfacePlan, nestIndex);
            } catch (RuntimeException e) {
                errors.put(c.name(), describe(e));
                return null;
            }
        }

        public Map<String, byte[]> convertAll() {
            Map<String, byte[]> out = new LinkedHashMap<>(byName.size() * 2);
            forEach(out::put);
            return out;
        }

        /**
         * Translate every class, handing each one to {@code sink} and keeping
         * no reference to it.
         *
         * This is what the bulk path uses. convertAll holds every class file
         * in memory at once, which on a 17k-class APK (HCR 1.43) needed more
         * than 3 GB of heap; streaming straight into the jar keeps the whole
         * conversion flat in memory regardless of app size.
         *
         * <p>That promise is about the OUTPUT, and for a long time only the
         * output kept it: the INPUT side memoized every class's parse into a
         * DexClass this Session holds forever, so the conversion grew without
         * bound anyway. TikTok 2024604030 (37 dex, 316 MB, 411,385 classes)
         * exhausted a 6 GB heap. Each class is therefore released here, on the
         * drain thread, once its bytes have gone to the sink -- see
         * DexClass.releaseMembers.
         */
        public void forEach(java.util.function.BiConsumer<String, byte[]> sink) {
            List<Map.Entry<String, DexClass>> all = new ArrayList<>(byName.entrySet());
            // Resolved here, on this thread, because the plan is CROSS-CLASS: the
            // call site that needs a forwarding constructor is usually in a
            // different class from the one that has to declare it, so it cannot
            // be discovered inside the per-class fan-out.
            final InitRepointPlan plan = repointPlan();
            int threads = threadCount();
            if (threads <= 1 || all.size() < BATCH) {
                for (Map.Entry<String, DexClass> e : all) {
                    convertInto(sink, e.getKey(), e.getValue(), plan);
                }
                return;
            }
            java.util.concurrent.ExecutorService pool = newPool(threads);
            try {
                // A sliding window rather than one big submit: the whole point
                // of forEach is that memory stays flat regardless of app size
                // (materialising every class at once needed >3 GB on a 17k-class
                // APK), so at most BATCH class files are ever in flight. It used
                // to be fixed batches of BATCH, drained to empty before the next
                // was submitted, which left the pool idle at every batch tail
                // waiting on the slowest class; the window refills as each class
                // is drained, so the workers never run dry while classes remain.
                java.util.ArrayDeque<java.util.concurrent.Future<byte[]>> window =
                    new java.util.ArrayDeque<>(BATCH);
                int next = 0;
                for (int i = 0; i < all.size(); i++) {
                    while (next < all.size() && window.size() < BATCH) {
                        DexClass c = all.get(next++).getValue();
                        window.add(pool.submit(() -> convertOne(c, hierarchy, plan, superIfacePlan, nestIndex)));
                    }
                    // Drained IN ORDER, on this thread. That is what keeps the
                    // output deterministic (dex order, byte-identical to the
                    // sequential path) and what lets `errors` stay a plain
                    // ordered map touched by one thread only.
                    String name = all.get(i).getKey();
                    try {
                        sink.accept(name, window.poll().get());
                    } catch (java.util.concurrent.ExecutionException ex) {
                        errors.put(name, describe(ex.getCause()));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("conversion interrupted", ex);
                    }
                    // After the get(), so the worker that parsed this class
                    // has certainly finished with it. A later class in the
                    // window may still ask this one a metadata question (via
                    // SuperInterfacePlan), which simply re-parses; the memo
                    // is a cache and dropping it cannot change any output.
                    all.get(i).getValue().releaseMembers();
                }
            } finally {
                pool.shutdownNow();
            }
        }

        /** Worker count. Options.threads (DEX2JVM_THREADS) overrides it, mostly
         *  so the parallel and sequential paths can be A/B'd for byte identity.
         *
         *  <p>Otherwise one per core, capped at the larger of MIN_THREAD_CAP and
         *  one worker per HEAP_PER_THREAD of -Xmx. A flat cap of 8
         *  changed nothing on an 8-core M2 but left half of a 16-core machine
         *  idle; the heap term keeps a small -Xmx from being spread over more
         *  workers than it can feed. Output is byte-identical at any count. */
        private static int threadCount() {
            int n = Options.threads;
            if (n >= 1) return n;
            long heapCap = Runtime.getRuntime().maxMemory() / HEAP_PER_THREAD;
            long cap = Math.max(MIN_THREAD_CAP, heapCap);
            return (int) Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), cap));
        }

        /** The converter's worker pool: daemon threads, so an early exit never
         *  waits on one. Callers shut it down when they are done with it. */
        private static java.util.concurrent.ExecutorService newPool(int threads) {
            return java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, Options.logTag);
                t.setDaemon(true);   // never hold the JVM open on an early exit
                return t;
            });
        }

        private void convertInto(java.util.function.BiConsumer<String, byte[]> sink,
                                 String name, DexClass cls, InitRepointPlan plan) {
            try {
                sink.accept(name, convertOne(cls, hierarchy, plan, superIfacePlan, nestIndex));
            } catch (RuntimeException ex) {
                errors.put(name, describe(ex));
            } finally {
                // Also on the failure path: a class we could not translate has
                // no more claim on the heap than one we could.
                cls.releaseMembers();
            }
        }
    }

    // ==================================================================
    // One class
    // ==================================================================

    static byte[] convertOne(DexClass cls, DexType.ClassHierarchy hierarchy) {
        return convertOne(cls, hierarchy, InitRepointPlan.NONE, SuperInterfacePlan.NONE, NestIndex.NONE);
    }

    static byte[] convertOne(DexClass cls, DexType.ClassHierarchy hierarchy,
                             InitRepointPlan plan, SuperInterfacePlan superIfaces,
                             NestIndex nestIndex) {
        // The test knobs arm the two rescue transforms unconditionally so a
        // gate can exercise them on methods that are nowhere near the real
        // limit. See MethodOutliner.FORCE / MethodSplitter.FORCE.
        if (MethodOutliner.forced() || MethodSplitter.forced()) {
            return convertOne(cls, hierarchy, plan, superIfaces, nestIndex, true);
        }
        try {
            return convertOne(cls, hierarchy, plan, superIfaces, nestIndex, false);
        } catch (CodeWriter.CodeLengthExceeded e) {
            // Dalvik has no code_length limit and the JVM caps it at 65535
            // (JVMS 4.9.1), so a class can be perfectly valid on ART and
            // inexpressible here. Losing the whole class over one method is the
            // worst outcome available -- Telegram's ChatMessageCell draws every
            // message in a chat and has 613 methods, one of which overflows --
            // so retry with the rescue transforms armed: MethodOutliner for a
            // straight-line region, MethodSplitter for a branchy method with
            // try blocks (which is the shape ChatMessageCell.setMessageContent
            // actually has). The retry is the ONLY caller that arms them, which
            // is what keeps every class that already fits byte-identical.
            try {
                return convertOne(cls, hierarchy, plan, superIfaces, nestIndex, true);
            } catch (CodeWriter.CodeLengthExceeded e2) {
                // Neither transform could bring the method under 65535 bytes.
                // Before stubbing it, try once more with ConstReadingElision
                // armed: a Dalvik constant is stored once per POSSIBLE reading
                // (int and float, long and double), and dropping the readings
                // nothing can observe shrinks a constant-heavy body, and with it
                // the parts the transforms have to place. rescueWithElision ends
                // in the same loud stub (see there) that used to come next, so
                // this step can only improve the outcome.
                return rescueWithElision(cls, hierarchy, plan, superIfaces, nestIndex);
            } catch (RuntimeException e2) {
                if (!poolFull(e2)) throw e2;       // the transforms filled the pool
                return rescueWithElision(cls, hierarchy, plan, superIfaces, nestIndex);
            }
        } catch (RuntimeException e) {
            // The CONSTANT POOL, not a method, overflowed (JVMS 4.1:
            // constant_pool_count is a u2, so at most 65534 usable entries).
            // The code-length transforms cannot help -- they move code between
            // methods of the SAME class, which share the one pool -- but dead
            // constant readings are what fills such a pool: WhatsApp
            // 2.26.41.71's X/0Ec, a 22,452-entry long[] table, needs ~49k
            // entries and was heading for ~116k, a double and a float beside
            // every long and int it stores.
            // Anything else is a genuine failure and keeps propagating.
            if (!poolFull(e)) throw e;
            return rescueWithElision(cls, hierarchy, plan, superIfaces, nestIndex);
        }
    }

    /**
     * The rescue ladder again, with {@link ConstReadingElision} armed on this
     * thread: elision alone (no synthetic members), then elision plus
     * MethodOutliner / MethodSplitter, then elision plus the oversize stub.
     *
     * <p>The stub is the LAST RESORT: keep the CLASS, lose only the one method.
     * Both transforms can legitimately refuse -- MethodSplitter will not cut
     * inside a try range or between a method's first and last monitor op
     * (MethodSplitter.java, rules 2 and 5), and MethodOutliner will not outline
     * across a try range. Before the stub existed that refusal dropped the
     * ENTIRE class, silently, so at run time the app saw NoClassDefFoundError
     * for a class its dex plainly defines (measured on Instagram: seven classes,
     * one of them, X/9Pu, on the render path). A class with one method that
     * throws -- and NAMES ITSELF in the message -- is strictly better than no
     * class at all. This is a loud stub, not a silent one: the distinction
     * MethodOutliner's header draws.
     *
     * <p>A constant pool that still overflows after elision propagates, and the
     * class fails as before, now with a census of what filled the pool.
     */
    static byte[] rescueWithElision(DexClass cls, DexType.ClassHierarchy hierarchy,
                                    InitRepointPlan plan, SuperInterfacePlan superIfaces,
                                    NestIndex nestIndex) {
        boolean was = ConstReadingElision.arm(true);
        try {
            try {
                return convertOne(cls, hierarchy, plan, superIfaces, nestIndex, false);
            } catch (CodeWriter.CodeLengthExceeded e) {
                // elision alone was not enough; add the transforms
            }
            try {
                return convertOne(cls, hierarchy, plan, superIfaces, nestIndex, true);
            } catch (CodeWriter.CodeLengthExceeded e2) {
                return stubPass(cls, hierarchy, plan, superIfaces, nestIndex);
            }
        } finally {
            ConstReadingElision.arm(was);
        }
    }

    /**
     * The oversize-stub pass, with {@link ClinitPrecompute} armed: a
     * &lt;clinit&gt; still over the limit whose every step is a whitelisted data
     * operation gets the final state it computes emitted instead of the stub,
     * so the class can initialise. Any other oversize method is stubbed exactly
     * as before.
     *
     * <p>The precomputed initializer takes constant pool entries the stub does
     * not (its data strings, its helpers). Should the class then run out of
     * pool, it is converted once more with the rescue disarmed, which is the
     * stub path as it was, so this pass can only improve the outcome.
     */
    static byte[] stubPass(DexClass cls, DexType.ClassHierarchy hierarchy,
                           InitRepointPlan plan, SuperInterfacePlan superIfaces,
                           NestIndex nestIndex) {
        boolean was = ClinitPrecompute.arm(true);
        ClinitPrecompute.resetFired();
        try {
            byte[] out;
            try {
                out = convertOne(cls, hierarchy, plan, superIfaces, nestIndex, true, true);
            } catch (RuntimeException e) {
                if (!ClinitPrecompute.fired() || !poolFull(e)) throw e;
                ClinitPrecompute.arm(false);
                ClinitPrecompute.resetFired();
                return convertOne(cls, hierarchy, plan, superIfaces, nestIndex, true, true);
            }
            if (ClinitPrecompute.fired()) {
                ClinitPrecompute.precomputed.incrementAndGet();
                System.err.println("[" + Options.logTag + "] precomputed oversize <clinit> of "
                    + cls.name() + " (" + ClinitPrecompute.lastSummary() + ")");
            }
            return out;
        } finally {
            ClinitPrecompute.arm(was);
            ClinitPrecompute.resetFired();
        }
    }

    /**
     * Whether {@code m} on its own translates past code_length 65535, measured
     * in a throwaway class file so the attempt leaves nothing behind in the
     * real class's constant pool: a 100 KB data initializer's literals would
     * otherwise stay there, unused, once ClinitPrecompute replaces the body.
     */
    static boolean overflowsAlone(DexClass cls, DexMethod m, DexType.ClassHierarchy hierarchy,
                                  InitRepointPlan plan, SuperInterfacePlan superIfaces,
                                  boolean allowOutlining) {
        ClassFileWriter scratch = new ClassFileWriter(classAccessFlags(cls), cls.name(),
            cls.superclassName(), cls.interfaceNames().toArray(new String[0]));
        ClassFileWriter.MethodWriter smw =
            scratch.addMethod(m.accessFlags() & METHOD_FLAGS_MASK, m.name(), m.descriptor());
        try {
            Translator.translate(m, smw, hierarchy, plan, superIfaces, allowOutlining);
            CodeWriter body = smw.code();
            return body != null && body.measuredLength() > 65535;
        } catch (CodeWriter.CodeLengthExceeded e) {
            return true;
        } catch (RuntimeException e) {
            return false;   // not a size problem; the real translation reports it
        }
    }

    /** True when the failure is the constant pool running out of slots. */
    static boolean poolFull(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof ConstantPool.PoolFullException) return true;
        }
        return false;
    }

    static byte[] convertOne(DexClass cls, DexType.ClassHierarchy hierarchy,
                             InitRepointPlan plan, SuperInterfacePlan superIfaces,
                             NestIndex nestIndex, boolean allowOutlining) {
        return convertOne(cls, hierarchy, plan, superIfaces, nestIndex,
                          allowOutlining, false);
    }

    /** Count of methods replaced by the oversize stub, for the convert report. */
    static final java.util.concurrent.atomic.AtomicInteger oversizeStubs =
        new java.util.concurrent.atomic.AtomicInteger();

    static byte[] convertOne(DexClass cls, DexType.ClassHierarchy hierarchy,
                             InitRepointPlan plan, SuperInterfacePlan superIfaces,
                             NestIndex nestIndex, boolean allowOutlining,
                             boolean stubOversize) {
        ClassFileWriter cw = new ClassFileWriter(
            classAccessFlags(cls),
            cls.name(),
            cls.superclassName() == null ? null : cls.superclassName(),
            cls.interfaceNames().toArray(new String[0]));
        cw.setUnfinalForeignWrites(UNFINAL_FOREIGN_WRITES);

        String src = cls.sourceFile();
        if (src != null) cw.setSourceFile(src);

        // Class-level system annotations become real attributes, and hand back
        // the per-method AnnotationDefault values, which DEX stores once on the
        // annotation TYPE rather than on each member.
        Map<String, DexFile.Value> defaults = applyClassAnnotations(cw, cls, nestIndex);

        for (DexField f : cls.fields()) {
            ClassFileWriter.FieldWriter fw =
                cw.addField(fieldAccessFlags(cls, f, superIfaces), f.name(), f.type());
            DexFile.Value v = f.staticValue();
            if (v != null && f.isStatic()) applyConstantValue(fw, v, f.type());
            applyMemberAnnotations(cw, fw, f.annotations());
        }

        for (DexMethod m : cls.methods()) {
            ClassFileWriter.MethodWriter mw =
                cw.addMethod(m.accessFlags() & METHOD_FLAGS_MASK, m.name(), m.descriptor());
            applyMethodAnnotations(cw, mw, m, defaults.get(m.name()));
            if (m.isAbstract() || m.isNative()) continue;
            if (stubOversize) {
                // An oversize <clinit> is usually a data table, and when it
                // computes nothing but this class's own static state,
                // ClinitPrecompute emits that state instead of the stub (which
                // would make the CLASS fail to initialise). Measured in a
                // scratch class first, so a body it replaces leaves no
                // constants behind.
                boolean precompute = ClinitPrecompute.armed() && ClinitPrecompute.candidate(m);
                String refusal = null;
                if (precompute && overflowsAlone(cls, m, hierarchy, plan, superIfaces, allowOutlining)) {
                    refusal = ClinitPrecompute.tryEmit(cls, m, cw, mw);
                    if (refusal == null) {
                        m.releaseCode();
                        continue;
                    }
                }
                int over = -1;
                try {
                    Translator.translate(m, mw, hierarchy, plan, superIfaces,
                                         allowOutlining);
                    // ⚠ Translator does NOT throw for an oversized body. Its
                    // splitter path deliberately "leaves the method exactly as
                    // the unsplit path produced it, which is the state
                    // DexConverter's caller expects to fail on" -- the throw
                    // then comes out of cw.toByteArray() at SERIALIZATION time,
                    // by which point we no longer know which method it was.
                    // So measure here instead of catching.
                    CodeWriter body = mw.code();
                    if (body != null) {
                        int len = body.measuredLength();
                        if (len > 65535) over = len;
                    }
                } catch (CodeWriter.CodeLengthExceeded e) {
                    over = e.length;        // belt and braces
                }
                if (over >= 0) {
                    mw.resetCode();
                    if (precompute && refusal == null) {
                        // The scratch measurement fit and this one did not
                        // (a fuller pool needs wider ldc forms): still worth it.
                        refusal = ClinitPrecompute.tryEmit(cls, m, cw, mw);
                        if (refusal == null) {
                            m.releaseCode();
                            continue;
                        }
                    }
                    if (refusal != null) {
                        System.err.println("[" + Options.logTag + "] oversize <clinit> of "
                            + cls.name() + " not precomputed: " + refusal);
                    }
                    // Only THIS method is inexpressible; the rest of the class
                    // is fine. Emit a body that throws with the reason in the
                    // message, so a caller that actually reaches it says so.
                    Translator.emitUnsupportedBody(mw, m,
                        Options.syntheticPrefix + ": " + cls.name() + "." + m.name() + m.descriptor()
                        + " needs code_length " + over + ", over the JVM's 65535"
                        + " (JVMS 4.9.1); neither MethodOutliner nor"
                        + " MethodSplitter could rescue it");
                    oversizeStubs.incrementAndGet();
                    System.err.println("[" + Options.logTag + "] STUBBED oversize method "
                        + cls.name() + "." + m.name() + m.descriptor()
                        + " (code_length " + over + ") -- the CLASS is kept,"
                        + " this one method throws if called");
                }
            } else {
                // Test knob DEX2JVM_CLINIT_PRECOMPUTE_FORCE=1: every <clinit>
                // the rescue accepts, so a gate can exercise it on classes
                // nowhere near the limit. Never set in normal use.
                if (ClinitPrecompute.FORCE && ClinitPrecompute.candidate(m)
                        && ClinitPrecompute.tryEmit(cls, m, cw, mw) == null) {
                    m.releaseCode();
                    continue;
                }
                Translator.translate(m, mw, hierarchy, plan, superIfaces, allowOutlining);
            }
            // The bytecode is written; nothing reads this body again, and the
            // memo would otherwise keep the insns AND the decoded line-number
            // table alive for as long as the class is reachable -- which, on the
            // bulk path, is the whole session. See DexMethod.releaseCode.
            m.releaseCode();
        }

        // A split or outlined <clinit> / <init> writes the class's own finals
        // from a synthetic helper, which HotSpot refuses once the class is at
        // version 53+ (a nest raises it to 55). See ClassFileWriter's javadoc.
        if (INITIALIZER_HELPER_FINALS) cw.unfinalFieldsWrittenByInitializerHelpers();

        // Last, so a real member never has to move: the constructors R8 deleted
        // and re-pointed away from. See InitRepointPlan.
        String superName = cls.superclassName();
        if (superName != null && !cls.isInterface()) {
            for (InitRepointPlan.Forward f : plan.syntheticConstructors(cls.name())) {
                InitRepointPlan.emitForwardingConstructor(cw, superName, f);
            }
        }

        // toByteArray also clears ACC_FINAL on own fields written outside
        // their initializer (UNFINAL_FOREIGN_WRITES).
        byte[] bytes = cw.toByteArray();
        List<String> unfinaled = cw.foreignWriteUnfinals();
        if (!unfinaled.isEmpty()) {
            foreignWriteClasses.incrementAndGet();
            foreignWriteFields.addAndGet(unfinaled.size());
            if (UNFINAL_DEBUG) {
                System.err.println("[" + Options.logTag + "] unfinal " + cls.name() + ": "
                    + String.join(" ", unfinaled) + " (written outside its initializer)");
            }
        }
        return bytes;
    }

    /** Classes, and fields, that UNFINAL_FOREIGN_WRITES changed, for the
     *  convert report. Counted per successful conversion of a class. */
    static final java.util.concurrent.atomic.AtomicInteger foreignWriteClasses =
        new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger foreignWriteFields =
        new java.util.concurrent.atomic.AtomicInteger();

    // ==================================================================
    // Annotations -> attributes
    // ==================================================================
    //
    // DEX stores several class file ATTRIBUTES as "system" annotations, and
    // they are abundant: across the corpus, Signature 310k, InnerClass 112k,
    // Throws 101k, EnclosingClass 73k, MethodParameters 39k. They must be
    // turned back into attributes rather than emitted as annotations -- a
    // Signature attribute is what makes getGenericSuperclass work, and
    // InnerClasses is what getSimpleName / getEnclosingClass read. Only
    // VISIBILITY_RUNTIME annotations belong in RuntimeVisibleAnnotations.
    //
    // Element names and value shapes are pinned to dx's AnnotationUtils (the
    // tool that WRITES the format) and confirmed against a census of the whole
    // APK corpus.

    static final String A_SIGNATURE    = "Ldalvik/annotation/Signature;";
    static final String A_THROWS       = "Ldalvik/annotation/Throws;";
    static final String A_INNER_CLASS  = "Ldalvik/annotation/InnerClass;";
    static final String A_MEMBER_CLASSES = "Ldalvik/annotation/MemberClasses;";
    static final String A_ENCLOSING_CLASS  = "Ldalvik/annotation/EnclosingClass;";
    static final String A_ENCLOSING_METHOD = "Ldalvik/annotation/EnclosingMethod;";
    static final String A_ANNOTATION_DEFAULT = "Ldalvik/annotation/AnnotationDefault;";
    static final String A_SOURCE_DEBUG   = "Ldalvik/annotation/SourceDebugExtension;";
    static final String A_METHOD_PARAMS  = "Ldalvik/annotation/MethodParameters;";
    /** java.lang.annotation.Retention / RetentionPolicy, for the synthesized
     *  retention stamp on annotation types R8 stripped it from -- see
     *  {@link #ensureRuntimeRetention}. */
    static final String A_RETENTION      = "Ljava/lang/annotation/Retention;";
    static final String RETENTION_POLICY = "Ljava/lang/annotation/RetentionPolicy;";

    /** Applies every class-level annotation, returning method-name ->
     *  AnnotationDefault value for the members of an annotation type. */
    static Map<String, DexFile.Value> applyClassAnnotations(ClassFileWriter cw, DexClass cls,
                                                             NestIndex nestIndex) {
        Map<String, DexFile.Value> defaults = new LinkedHashMap<>();
        List<DexFile.Annotation> user = new ArrayList<>();
        DexFile.Annotation innerClass = null;
        String outerDescriptor = null;

        for (DexFile.Annotation a : cls.annotations()) {
            String t = a.type();
            if (t == null) continue;
            if (a.visibility() != DexFile.VISIBILITY_SYSTEM) { user.add(a); continue; }
            switch (t) {
                case A_SIGNATURE: {
                    String sig = joinSignature(a.element("value"));
                    if (sig != null) cw.setSignature(sig);
                    break;
                }
                case A_INNER_CLASS:
                    innerClass = a;
                    break;
                case A_ENCLOSING_CLASS: {
                    DexFile.Value v = a.element("value");
                    if (v != null) outerDescriptor = v.asTypeDescriptor();
                    break;
                }
                case A_ENCLOSING_METHOD: {
                    // Only a local or anonymous class carries this, and it is
                    // what makes isLocalClass/isAnonymousClass answer true. A
                    // plain member class gets EnclosingClass instead, which
                    // becomes the InnerClasses outer entry rather than this
                    // attribute (JVMS 4.7.7).
                    DexFile.Value v = a.element("value");
                    DexFile.MethodRef mr = v == null ? null : v.asMethod();
                    if (mr != null) {
                        cw.setEnclosingMethod(mr.declaringClassName(), mr.name(),
                                              mr.proto().descriptor());
                    }
                    break;
                }
                case A_MEMBER_CLASSES:
                    // Superseded by NestIndex.enclosedClasses, which is the
                    // same relationship read the other way round (and session-
                    // wide, not per-dex) and also yields each nested class's
                    // simple name and access flags.
                    break;
                case A_ANNOTATION_DEFAULT: {
                    DexFile.Value v = a.element("value");
                    DexFile.Annotation inner = v == null ? null : v.asAnnotation();
                    if (inner != null) {
                        for (DexFile.AnnotationElement e : inner.elements()) {
                            if (e.name() != null) defaults.put(e.name(), e.value());
                        }
                    }
                    break;
                }
                case A_SOURCE_DEBUG: {
                    DexFile.Value v = a.element("value");
                    String s = v == null ? null : v.asString();
                    // JVMS 4.7.11: the body IS the modified-UTF8 bytes, with no
                    // length prefix and no constant pool entry.
                    if (s != null) cw.addAttribute("SourceDebugExtension", Mutf8.encode(s));
                    break;
                }
                default:
                    // An unrecognised dalvik.annotation.* (e.g. the
                    // optimization.* hints) has no class file meaning; dropping
                    // it is what a real Android toolchain does too.
                    break;
            }
        }

        applyInnerClasses(cw, cls, innerClass, outerDescriptor, nestIndex);
        // NestHost / NestMembers (JVMS 4.7.28, 4.7.29). DEX carries no nest
        // attributes, so private access between a class and the classes nested
        // inside it is lost unless it is rebuilt from the same enclosing-class
        // information InnerClasses uses. See NestPlan.
        NestPlan.apply(cw, cls, nestIndex);
        if (cls.isAnnotation()) ensureRuntimeRetention(user);
        addAnnotationAttributes(cw.pool(), cls.annotations(), user,
                b -> cw.addAttribute("RuntimeVisibleAnnotations", b),
                b -> cw.addAttribute("RuntimeInvisibleAnnotations", b));
        return defaults;
    }

    /**
     * Synthesizes {@code @Retention(RetentionPolicy.RUNTIME)} on an annotation
     * TYPE declaration that does not already carry a runtime-visible one.
     *
     * WHY. On Android, runtime visibility is a PER-USE byte in the DEX
     * (annotation_item.visibility: VISIBILITY_RUNTIME 0x01 "visible at
     * runtime"), and ART's reflection reads only that byte -- every getter in
     * art/runtime/dex/dex_file_annotations.cc passes kDexVisibilityRuntime and
     * GetAnnotationItemFromAnnotationSet skips items failing
     * IsVisibilityCompatible(annotation_item->visibility_, visibility); the
     * word "Retention" does not appear in that file. So to ART the type-level
     * {@code @Retention} is dead metadata, and R8 strips it (measured: TikTok
     * 46.4.3 has ZERO occurrences of Ljava/lang/annotation/Retention; across
     * all 37 dex files, while its settings interfaces carry runtime-visible
     * annotation uses).
     *
     * HotSpot derives visibility the OTHER way round, from the type:
     * sun.reflect.annotation.AnnotationType defaults a missing
     * {@code @Retention} to RetentionPolicy.CLASS, and
     * AnnotationParser.parseAnnotations2 then drops every parsed use whose
     * type is not RUNTIME-retained -- getAnnotation() returns null with no
     * error. Stamping the type RUNTIME makes that filter a no-op, which
     * leaves the per-use visibility (already split into RuntimeVisible /
     * RuntimeInvisibleAnnotations by addAnnotationAttributes) as the sole
     * authority -- exactly ART's model.
     *
     * The stamp is UNCONDITIONAL for retention-less types, not driven by
     * whether some use site in the session is runtime-visible, because it
     * cannot over-expose: a VISIBILITY_BUILD use lands in
     * RuntimeInvisibleAnnotations, which HotSpot's ClassFileParser DISCARDS at
     * class load unless -XX:+PreserveAllAnnotations (default false), and
     * JVMS 4.7.17 says such annotations "are not made available by the class
     * libraries". Evidence-driven stamping would also
     * need a session-wide use-site scan, i.e. cross-class state on the
     * per-class fan-out, for no behavioural difference.
     *
     * The other meta-annotations R8 strips need NO counterpart on purpose:
     * ART consults {@code @Inherited} and {@code @Repeatable} from the DEX at
     * runtime (libcore Class.getAnnotation walks superclasses only "if
     * (annotationClass.isDeclaredAnnotationPresent(Inherited.class))"), so
     * when R8 removes them the DEVICE loses the behaviour too and absence is
     * parity; {@code @Target} and {@code @Documented} have no reflective
     * effect beyond being queryable, and null matches the device. Synthesizing
     * any of them would invent behaviour ART does not have.
     *
     * A type carrying an EXPLICIT runtime-visible {@code @Retention} (R8 kept
     * it, or the app is unminified) is left alone.
     */
    static void ensureRuntimeRetention(List<DexFile.Annotation> user) {
        for (DexFile.Annotation a : user) {
            if (A_RETENTION.equals(a.type())
                    && a.visibility() == DexFile.VISIBILITY_RUNTIME) return;
        }
        user.add(new DexFile.Annotation(DexFile.VISIBILITY_RUNTIME, A_RETENTION,
                List.of(new DexFile.AnnotationElement("value",
                        new DexFile.Value(DexFile.VALUE_ENUM, 0,
                                new DexFile.FieldRef(RETENTION_POLICY, RETENTION_POLICY,
                                                     "RUNTIME"))))));
    }

    /**
     * The InnerClasses attribute (JVMS 4.7.6), assembled from DEX's two halves:
     * the inner class carries InnerClass{name, accessFlags} plus its enclosing
     * class or method, and the outer class carries MemberClasses.
     *
     * Reflection reads BOTH directions out of this one attribute:
     * getSimpleName / isAnonymousClass / getEnclosingClass use the entry whose
     * inner_class_info is this class, and getDeclaredClasses uses the entries
     * whose outer_class_info is.
     *
     * The two halves are sourced differently on purpose. This class's OWN
     * entry (below) reads {@code innerClass}/{@code outerDescriptor} straight
     * off {@code cls}'s own system annotations -- always safe, because a type
     * descriptor is self-contained wherever it is emitted, never a
     * cross-dex-file lookup. Every ENCLOSED class's entry, by contrast, needs
     * to know who else names {@code cls} as their enclosing class, which is
     * why that half goes through {@code nestIndex} (session-wide) rather than
     * {@code cls.dex()} (one physical dex file) -- see NestIndex's header.
     */
    static void applyInnerClasses(ClassFileWriter cw, DexClass cls,
                                  DexFile.Annotation innerClass, String outerDescriptor,
                                  NestIndex nestIndex) {
        if (innerClass != null) {
            DexFile.Value nameV = innerClass.element("name");
            DexFile.Value flagsV = innerClass.element("accessFlags");
            // name is explicitly NULL for an anonymous class, which the
            // attribute encodes as inner_name_index 0.
            String simple = nameV == null || nameV.isNull() ? null : nameV.asString();
            int flags = flagsV == null ? cls.accessFlags() : flagsV.asInt();
            cw.addInnerClass(cls.name(),
                             outerDescriptor == null ? null : DexFile.internalName(outerDescriptor),
                             simple, flags & INNER_CLASS_FLAGS_MASK);
        }
        // Everything this class ENCLOSES, which is a superset of MemberClasses:
        // an anonymous or local class is enclosed but is not a member, and
        // omitting it makes HotSpot reject the pair -- Class.getDeclaringClass
        // on the anonymous class throws IncompatibleClassChangeError
        // ("Sample and Sample$1 disagree on InnerClasses attribute"). The
        // inverse index also carries each nested class's own name and flags,
        // which MemberClasses does not, so it is the better source for both.
        //
        // SESSION-wide (nestIndex), not cls.dex().enclosedClasses(): a real
        // multidex APK does not keep a nest in one physical dex file. Measured
        // on Signal 172401, org/signal/video/exo/ExoPlayerPool (classes5.dex)
        // encloses ExoPlayerPool$DataSourceTransferListener (classes8.dex) --
        // a per-dex lookup here silently dropped that entry from
        // ExoPlayerPool's own InnerClasses attribute, which is exactly the
        // "X and Y disagree on InnerClasses attribute" shape.
        for (DexClass nested : nestIndex.enclosedClasses(cls.name())) {
            if (nested.name().equals(cls.name())) continue;
            DexFile.Annotation ni = systemAnnotation(nested.annotations(), A_INNER_CLASS);
            if (ni == null) continue;
            DexFile.Value nameV = ni.element("name");
            DexFile.Value flagsV = ni.element("accessFlags");
            // outer_class_info is set only for a MEMBER. An anonymous or local
            // class carries EnclosingMethod instead and takes 0 here, which is
            // what makes isMemberClass false and isLocalClass true.
            boolean isMember = systemAnnotation(nested.annotations(), A_ENCLOSING_CLASS) != null;
            cw.addInnerClass(nested.name(), isMember ? cls.name() : null,
                             nameV == null || nameV.isNull() ? null : nameV.asString(),
                             (flagsV == null ? nested.accessFlags() : flagsV.asInt())
                                 & INNER_CLASS_FLAGS_MASK);
        }
    }

    static void applyMemberAnnotations(ClassFileWriter cw, ClassFileWriter.Member fw,
                                       List<DexFile.Annotation> anns) {
        List<DexFile.Annotation> user = new ArrayList<>();
        for (DexFile.Annotation a : anns) {
            if (a.type() == null) continue;
            if (a.visibility() == DexFile.VISIBILITY_SYSTEM) {
                if (A_SIGNATURE.equals(a.type())) {
                    String sig = joinSignature(a.element("value"));
                    if (sig != null) fw.setSignature(sig);
                }
                continue;
            }
            user.add(a);
        }
        addAnnotationAttributes(cw.pool(), anns, user,
                b -> fw.addAttribute("RuntimeVisibleAnnotations", b),
                b -> fw.addAttribute("RuntimeInvisibleAnnotations", b));
    }

    static void applyMethodAnnotations(ClassFileWriter cw, ClassFileWriter.MethodWriter mw,
                                       DexMethod m, DexFile.Value annotationDefault) {
        List<DexFile.Annotation> user = new ArrayList<>();
        for (DexFile.Annotation a : m.annotations()) {
            String t = a.type();
            if (t == null) continue;
            if (a.visibility() != DexFile.VISIBILITY_SYSTEM) { user.add(a); continue; }
            switch (t) {
                case A_SIGNATURE: {
                    String sig = joinSignature(a.element("value"));
                    if (sig != null) mw.setSignature(sig);
                    break;
                }
                case A_THROWS: {
                    String[] ex = typeArray(a.element("value"));
                    if (ex.length > 0) mw.setExceptions(ex);
                    break;
                }
                case A_METHOD_PARAMS: {
                    byte[] body = methodParametersBody(cw.pool(), a);
                    if (body != null) mw.addAttribute("MethodParameters", body);
                    break;
                }
                default:
                    break;
            }
        }
        addAnnotationAttributes(cw.pool(), m.annotations(), user,
                b -> mw.addAttribute("RuntimeVisibleAnnotations", b),
                b -> mw.addAttribute("RuntimeInvisibleAnnotations", b));

        byte[] pv = AnnotationWriter.parameterAnnotationsBody(cw.pool(),
                visibleOnly(m.parameterAnnotations(), DexFile.VISIBILITY_RUNTIME));
        if (pv != null) mw.addAttribute("RuntimeVisibleParameterAnnotations", pv);
        byte[] pi = AnnotationWriter.parameterAnnotationsBody(cw.pool(),
                visibleOnly(m.parameterAnnotations(), DexFile.VISIBILITY_BUILD));
        if (pi != null) mw.addAttribute("RuntimeInvisibleParameterAnnotations", pi);

        if (annotationDefault != null) {
            byte[] b = AnnotationWriter.annotationDefaultBody(cw.pool(), annotationDefault);
            if (b != null) mw.addAttribute("AnnotationDefault", b);
        }
    }

    /**
     * MethodParameters (JVMS 4.7.24). DEX carries it as two parallel arrays;
     * a null name means "parameter present but unnamed", which the attribute
     * encodes as name_index 0 rather than by omitting the entry.
     */
    static byte[] methodParametersBody(ConstantPool pool, DexFile.Annotation a) {
        DexFile.Value namesV = a.element("names");
        DexFile.Value flagsV = a.element("accessFlags");
        List<DexFile.Value> names = namesV == null ? null : namesV.asArray();
        List<DexFile.Value> flags = flagsV == null ? null : flagsV.asArray();
        if (names == null || names.isEmpty() || names.size() > 255) return null;
        ConstantPool.ByteVector b = new ConstantPool.ByteVector(1 + names.size() * 4);
        b.putU1(names.size());
        for (int i = 0; i < names.size(); i++) {
            DexFile.Value n = names.get(i);
            String s = (n == null || n.isNull()) ? null : n.asString();
            b.putU2(s == null ? 0 : pool.utf8(s));
            b.putU2(flags != null && i < flags.size() && flags.get(i) != null
                    ? flags.get(i).asInt() : 0);
        }
        return b.toByteArray();
    }

    /** Splits the user annotations by DEX visibility and hands each body to its sink. */
    static void addAnnotationAttributes(ConstantPool pool, List<DexFile.Annotation> all,
                                        List<DexFile.Annotation> user,
                                        java.util.function.Consumer<byte[]> visible,
                                        java.util.function.Consumer<byte[]> invisible) {
        if (user.isEmpty()) return;
        // VISIBILITY_RUNTIME is Java's RetentionPolicy.RUNTIME and is the only
        // kind reflection can see; VISIBILITY_BUILD is RetentionPolicy.CLASS,
        // which javac still writes out as RuntimeInvisibleAnnotations.
        byte[] v = AnnotationWriter.annotationsBody(pool,
                filterVisibility(user, DexFile.VISIBILITY_RUNTIME));
        if (v != null) visible.accept(v);
        byte[] i = AnnotationWriter.annotationsBody(pool,
                filterVisibility(user, DexFile.VISIBILITY_BUILD));
        if (i != null) invisible.accept(i);
    }

    static List<DexFile.Annotation> filterVisibility(List<DexFile.Annotation> in, int visibility) {
        List<DexFile.Annotation> out = new ArrayList<>();
        for (DexFile.Annotation a : in) if (a.visibility() == visibility) out.add(a);
        return out;
    }

    static List<List<DexFile.Annotation>> visibleOnly(List<List<DexFile.Annotation>> params,
                                                      int visibility) {
        List<List<DexFile.Annotation>> out = new ArrayList<>(params.size());
        for (List<DexFile.Annotation> p : params) out.add(filterVisibility(p, visibility));
        return out;
    }

    static DexFile.Annotation systemAnnotation(List<DexFile.Annotation> anns, String type) {
        for (DexFile.Annotation a : anns) {
            if (type.equals(a.type()) && a.visibility() == DexFile.VISIBILITY_SYSTEM) return a;
        }
        return null;
    }

    /** DEX splits a generic signature into an array of string chunks that must
     *  be concatenated in order to rebuild the one Signature attribute string. */
    static String joinSignature(DexFile.Value v) {
        List<DexFile.Value> parts = v == null ? null : v.asArray();
        if (parts == null || parts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (DexFile.Value p : parts) {
            String s = p == null ? null : p.asString();
            if (s == null) return null;
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String[] typeArray(DexFile.Value v) {
        List<DexFile.Value> items = v == null ? null : v.asArray();
        if (items == null) return new String[0];
        List<String> out = new ArrayList<>(items.size());
        for (DexFile.Value i : items) {
            String d = i == null ? null : i.asTypeDescriptor();
            if (d != null) out.add(DexFile.internalName(d));
        }
        return out.toArray(new String[0]);
    }

    /**
     * DEX and the class file agree on most access-flag bits, but a DEX
     * class_def carries ACC_SUPER's slot as something else, and the JVM wants
     * ACC_SUPER set on every non-interface class so invokespecial resolves the
     * modern way (JVMS 4.1: "the ACC_SUPER flag ... for backward compatibility").
     */
    static int classAccessFlags(DexClass cls) {
        int f = cls.accessFlags() & CLASS_FLAGS_MASK;
        if (!cls.isInterface()) f |= ClassFileWriter.ACC_SUPER;
        return f;
    }

    static void applyConstantValue(ClassFileWriter.FieldWriter fw, DexFile.Value v, String desc) {
        // Only the primitive and String cases are expressible as a
        // ConstantValue attribute; anything else the DEX encodes as a static
        // value is materialised by <clinit>, which we translate anyway.
        Object boxed = boxConstant(v, desc);
        if (boxed == null) return;
        try {
            fw.setConstantValue(boxed);
        } catch (RuntimeException | LinkageError ignored) {
            // A field without its ConstantValue still verifies and still gets
            // its value from <clinit>; losing the attribute is not worth
            // losing the class.
        }
    }

    /** DEX encoded_value -> the Java object FieldWriter.setConstantValue wants,
     *  or null when the descriptor has no ConstantValue representation. */
    static Object boxConstant(DexFile.Value v, String desc) {
        if (v == null || v.isNull() || desc.isEmpty()) return null;
        switch (desc.charAt(0)) {
            case 'Z': case 'B': case 'C': case 'S': case 'I':
                return Integer.valueOf(v.asInt());
            case 'J': return Long.valueOf(v.asLong());
            // asFloat/asDouble decode the raw IEEE754 bits the DEX stores.
            case 'F': return Float.valueOf(v.asFloat());
            case 'D': return Double.valueOf(v.asDouble());
            default:
                return "Ljava/lang/String;".equals(desc) ? v.asString() : null;
        }
    }

    // Bits the class file format defines for each location. Masking keeps any
    // DEX-only bit (or a future one) from producing a malformed classfile.
    static final int CLASS_FLAGS_MASK =
        ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_FINAL | ClassFileWriter.ACC_SUPER
        | ClassFileWriter.ACC_INTERFACE | ClassFileWriter.ACC_ABSTRACT
        | ClassFileWriter.ACC_SYNTHETIC | ClassFileWriter.ACC_ANNOTATION
        | ClassFileWriter.ACC_ENUM;

    /**
     * inner_class_access_flags (JVMS 4.7.6, Table 4.7.6-A). NOT the same set as
     * a class_info's: an inner class may be private or protected, which a
     * top-level class may not, and ACC_SUPER has no meaning here.
     */
    static final int INNER_CLASS_FLAGS_MASK =
        ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_PROTECTED
        | ClassFileWriter.ACC_STATIC | ClassFileWriter.ACC_FINAL
        | ClassFileWriter.ACC_INTERFACE | ClassFileWriter.ACC_ABSTRACT
        | ClassFileWriter.ACC_SYNTHETIC | ClassFileWriter.ACC_ANNOTATION
        | ClassFileWriter.ACC_ENUM;

    /**
     * DEX2JVM_IFACE_FIELD_FLAGS (default ON; =0 restores the raw DEX flags).
     *
     * JVMS 4.5: "Fields of interfaces must have their ACC_PUBLIC, ACC_STATIC,
     * and ACC_FINAL flags set", and HotSpot enforces it in
     * ClassFileParser::verify_legal_field_modifiers -- but only when the class
     * is being verified, so a JVM running with bytecode verification enabled
     * rejects the interface with a ClassFormatError, taking every class that
     * links against it down too. ART accepts the looser flags in a
     * dex 035 file (DexFileVerifier::CheckFieldAccessFlags only warns below
     * the default-methods dex version).
     *
     * The one producer seen in the corpus is D8's interface desugaring: an
     * interface with static state gets a synthetic `static int $desugar$clinit`
     * (flags 0x1008, package-private, non-final) that its $-CC companion reads
     * with sget purely to trigger the interface's &lt;clinit&gt;. Measured
     * 2026-09-26 on Geometry Dash Meltdown: yads/sq0.$desugar$clinit, one
     * reader (yads/sq0$-CC.&lt;clinit&gt;), no writer anywhere in the app -- and
     * the format error took 20 classes down with it.
     *
     * The rewrite is limited to STATIC SYNTHETIC interface fields, i.e. to
     * compiler bookkeeping. Adding PUBLIC only widens access. Adding FINAL is
     * only safe for a field nothing writes except the interface's own
     * &lt;clinit&gt;: HotSpot's LinkResolver::resolve_field turns any other
     * putstatic of a final field into an IllegalAccessError ("Update to static
     * final field ... attempted from a different class/method"), with or
     * without verification. That is PROVEN rather than assumed: the field must
     * be package-private or private, which confines every legal writer to the
     * interface's own package (ART rejects the rest in CanAccessMember too), and
     * SuperInterfacePlan.staticFieldWrittenOutsideClinit scans that package for
     * an sput naming it. A public one, or one without a session to scan, is
     * left exactly as it is, as is every hand-written (non-synthetic) field.
     * Measured 2026-09-26: exactly one such field in the 62-app corpus (the
     * GDM one above) and zero writers of it.
     */
    static final boolean IFACE_FIELD_FLAGS =
            !"0".equals(System.getenv("DEX2JVM_IFACE_FIELD_FLAGS"));

    /**
     * DEX2JVM_INITIALIZER_HELPER_FINALS (default ON; =0 keeps ACC_FINAL,
     * byte for byte the old output).
     *
     * In a class emitted at major_version 53 or above -- in practice one a
     * NestHost/NestMembers attribute raised to 55 -- clear ACC_FINAL on each own
     * field that a synthetic helper split or outlined out of an initializer
     * writes: a static written from a helper of &lt;clinit&gt;, an instance field
     * written from a helper of &lt;init&gt;. Without it the helper's first
     * putstatic / putfield is an IllegalAccessError ("Update to static final
     * field ..." / "Update to non-static final field ... attempted from a
     * different method"), and the class never initialises or the instance is
     * never constructed. Measured on d8-built nested classes at
     * version 55 (a static spilled out of &lt;clinit&gt;, an instance field out
     * of &lt;init&gt;). See ClassFileWriter.unfinalFieldsWrittenByInitializerHelpers.
     */
    static final boolean INITIALIZER_HELPER_FINALS =
            !"0".equals(System.getenv("DEX2JVM_INITIALIZER_HELPER_FINALS"));

    /**
     * DEX2JVM_UNFINAL_FOREIGN_WRITES (default ON; =0 keeps ACC_FINAL, byte for
     * byte the old output).
     *
     * The general form of the rule above, for writes that are already in the
     * DEX: in a class emitted at major_version 53 or above, clear ACC_FINAL on
     * each own static field a method other than &lt;clinit&gt; writes, and on each
     * own instance field a method other than &lt;init&gt; writes, as read from the
     * emitted bytecode. ART lets any method of the class write its own finals,
     * and obfuscated SDKs do (a static {@code init$0} filling a private static
     * final table for &lt;clinit&gt;); HotSpot refuses it from version 53 on, so
     * the class never initializes. Measured 2026-10-25 on com.leumi.leumiwallet
     * 155.3.1, where o.ey.b.$$a killed the Firebase-Messaging thread: 399
     * classes, 1,096 static fields, every other class byte-identical; none in
     * WhatsApp. Helpers marked as initializer parts stay under
     * DEX2JVM_INITIALIZER_HELPER_FINALS. See
     * ClassFileWriter.unfinalFieldsWrittenOutsideInitializers.
     *
     * DEX2JVM_UNFINAL_DEBUG=1 prints one line per class it changes; the counts
     * are always kept (foreignWriteClasses / foreignWriteFields) and the CLI
     * reports them.
     */
    static final boolean UNFINAL_FOREIGN_WRITES =
            !"0".equals(System.getenv("DEX2JVM_UNFINAL_FOREIGN_WRITES"));
    static final boolean UNFINAL_DEBUG =
            System.getenv("DEX2JVM_UNFINAL_DEBUG") != null;

    static int fieldAccessFlags(DexClass cls, DexField f, SuperInterfacePlan app) {
        int flags = f.accessFlags() & FIELD_FLAGS_MASK;
        final int need = ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_STATIC
                | ClassFileWriter.ACC_FINAL;
        if (IFACE_FIELD_FLAGS && cls.isInterface() && (flags & need) != need
                && (flags & ClassFileWriter.ACC_STATIC) != 0
                && (flags & ClassFileWriter.ACC_SYNTHETIC) != 0
                && (flags & ClassFileWriter.ACC_PUBLIC) == 0
                && !app.staticFieldWrittenOutsideClinit(cls, f)) {
            flags &= ~(ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_PROTECTED
                       | ClassFileWriter.ACC_VOLATILE | ClassFileWriter.ACC_TRANSIENT);
            flags |= need;
        }
        return flags;
    }

    static final int FIELD_FLAGS_MASK =
        ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_PROTECTED
        | ClassFileWriter.ACC_STATIC | ClassFileWriter.ACC_FINAL | ClassFileWriter.ACC_VOLATILE
        | ClassFileWriter.ACC_TRANSIENT | ClassFileWriter.ACC_SYNTHETIC
        | ClassFileWriter.ACC_ENUM;

    static final int METHOD_FLAGS_MASK =
        ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_PROTECTED
        | ClassFileWriter.ACC_STATIC | ClassFileWriter.ACC_FINAL
        | ClassFileWriter.ACC_SYNCHRONIZED | ClassFileWriter.ACC_BRIDGE
        | ClassFileWriter.ACC_VARARGS | ClassFileWriter.ACC_NATIVE
        | ClassFileWriter.ACC_ABSTRACT | ClassFileWriter.ACC_STRICT
        | ClassFileWriter.ACC_SYNTHETIC;

    /** "Lcom/Foo;" / "com.Foo" / "com/Foo" -> "com/Foo". */
    static String normalize(String n) {
        if (n == null) return null;
        if (n.endsWith(".class")) n = n.substring(0, n.length() - 6);
        if (n.length() > 2 && n.charAt(0) == 'L' && n.endsWith(";")) {
            n = n.substring(1, n.length() - 1);
        }
        return n.replace('.', '/');
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null) {
            sb.append(" <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }
}
