// ClassFileRewriter -- detects + repairs the broken-<clinit> bytecode
// pattern that dex2jar emits for certain Kotlin / enum singleton
// initializers.
//
// THE BUG (dex2jar codegen quirk)
// ------------------------------------------------------------------
// Some Kotlin / enum classes' `<clinit>` (or equivalent static
// initializer method) gets emitted as:
//
//     new           #cp_idx_of_WRONG_CLASS       (3 bytes: 0xBB ih il)
//     dup                                         (1 byte:  0x59)
//     [zero or more arg-pushing opcodes]
//     invokespecial #cp_idx_of_WRONG.<init>       (3 bytes: 0xB7 ih il)
//     [zero or more popping/storing opcodes]
//     putstatic     #cp_idx_of_THIS_CLASS.FIELD   (3 bytes: 0xB3 ih il)
//
// where WRONG_CLASS is `java/lang/Object`, `java/lang/Enum`, or
// the abstract base of the actual concrete class. The intended
// bytecode would have `new THIS_CLASS` and
// `invokespecial THIS_CLASS.<init>:<descriptor>` -- i.e., the same
// descriptor but rooted at the OWNING class.
//
// Effect under -Xverify:none (our JVM mode): the broken bytecode
// loads, runs, stores a BARE wrong-type instance into the static
// field. Any subsequent code that calls a method on
// `THIS_CLASS.INSTANCE` expecting THIS_CLASS semantics hits
// IncompatibleClassChangeError because the actual receiver is e.g.
// `java/lang/Object` which doesn't implement the requested
// interface. (Witnessed: sgtpuzzles' kotlin.collections.EmptyList
// before we shadowed it; sgtpuzzles' kotlin enum after EmptyList
// fix; analogous case in ShatteredPD via FreeType.a() -> h3, though
// that one is in a regular method body and not yet covered.)
//
// THE FIX
// ------------------------------------------------------------------
// At class load time, in `AppClassLoader.findClass`, we scan the
// raw class file bytes BEFORE handing them to `defineClass`. If we
// find the broken pattern, we rewrite the `new` and `invokespecial`
// CP indices to point at THIS_CLASS instead of the wrong class.
// The descriptor (the Methodref's name_and_type_index) is preserved
// -- if the wrong ctor was `<init>:(Ljava/lang/String;I)V`, the
// rewrite reuses that NameAndType and binds it to THIS_CLASS.
//
// CP entries may need to be added (the THIS_CLASS Methodref for the
// required descriptor may not exist yet). We append new entries at
// the end of the constant pool and increment constant_pool_count;
// existing indices keep their values, so no other bytecode needs
// updating.
//
// JVMS REFERENCES (verified before implementation):
// - https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html
//   §4.1   ClassFile structure
//   §4.4   constant_pool layout (tags 1, 7, 9, 10, 12)
//   §4.6   method_info structure
//   §4.7.3 Code attribute structure
// - https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html
//   §6.5   new        (0xBB)  u2 cp_idx (Class_info)
//          dup        (0x59)
//          invokespecial (0xB7)  u2 cp_idx (Methodref)
//          putstatic  (0xB3)  u2 cp_idx (Fieldref)
//          return     (0xB1)
// - https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html
//   §5.3.5  defineClass() = derivation, BEFORE verification
//   →  rewriting in findClass() before defineClass() is safe
package com.macapk.loader;

import java.util.ArrayList;
import java.util.List;

public final class ClassFileRewriter {

    /** Toggle: log every detected/repaired class. Off by default to
     *  keep boot logs quiet; flip via MACAPK_DEBUG_REWRITER=1. */
    private static final boolean DEBUG =
        "1".equals(System.getenv("MACAPK_DEBUG_REWRITER"));

    /** CP entry tags (JVMS §4.4 table 4.4-A). Only the ones we touch. */
    private static final int CP_UTF8         = 1;
    private static final int CP_CLASS        = 7;
    private static final int CP_FIELDREF     = 9;
    private static final int CP_METHODREF    = 10;
    private static final int CP_NAMEANDTYPE  = 12;
    // Other tags we need to *skip* during CP traversal:
    private static final int CP_INTEGER      = 3;
    private static final int CP_FLOAT        = 4;
    private static final int CP_LONG         = 5;
    private static final int CP_DOUBLE       = 6;
    private static final int CP_STRING       = 8;
    private static final int CP_IFACE_METHODREF = 11;
    private static final int CP_METHODHANDLE = 15;
    private static final int CP_METHODTYPE   = 16;
    private static final int CP_DYNAMIC      = 17;
    private static final int CP_INVOKEDYN    = 18;
    private static final int CP_MODULE       = 19;
    private static final int CP_PACKAGE      = 20;

    /** Opcodes we scan / rewrite. */
    private static final int OP_NEW           = 0xBB;
    private static final int OP_DUP           = 0x59;
    private static final int OP_INVOKESPECIAL = 0xB7;
    private static final int OP_INVOKEVIRTUAL = 0xB6;
    private static final int OP_INVOKESTATIC  = 0xB8;
    private static final int OP_PUTSTATIC     = 0xB3;
    private static final int OP_GETSTATIC     = 0xB2;
    private static final int OP_RETURN        = 0xB1;
    private static final int OP_ARETURN       = 0xB0;
    private static final int OP_POP           = 0x57;
    private static final int OP_NOP           = 0x00;

    /** access_flags bit values (JVMS §4.1 + §4.6). */
    private static final int ACC_ABSTRACT     = 0x0400;
    private static final int ACC_INTERFACE    = 0x0200;
    private static final int ACC_STATIC       = 0x0008;

    // -----------------------------------------------------------------
    // Pattern H registry -- components AOSP would run in ANOTHER process
    // -----------------------------------------------------------------
    //
    // Dotted class name -> the resolved android:process the manifest puts
    // that component in, for every <activity>/<activity-alias>/<receiver>
    // whose process is NOT the app's default one. Published once by
    // Boot.run() straight after the manifest parse and before the app's
    // first class is defined; empty (and therefore free) for every app
    // that declares no android:process at all, which is most of them.
    //
    // MEASURED 2026-08-13 over the 51-APK corpus: 9 APKs declare a
    // non-default android:process (F-Droid + AnkiDroid `:acra`, NewPipe
    // `:acra` + `:phoenix`, hillclimb `:adnw`, calculator
    // `:primes_lifeboat`, jetpackjoyride `:skynetservice`, dumbways
    // `:gameAnalyticsExceptionReporter`, openttd `:RestartMainActivity`,
    // VLC `:logger`), plus Aurora Store `:phoenix`. So this is a
    // framework-wide contract, not one app's quirk.
    private static volatile java.util.Map<String, String> sForeignProcess =
        java.util.Collections.emptyMap();

    /** Publish the foreign-process component table (see sForeignProcess).
     *  Null/empty restores the default of "no component is foreign", which
     *  disables Pattern H entirely. */
    public static void setForeignProcessComponents(
            java.util.Map<String, String> byDottedClassName) {
        sForeignProcess = (byDottedClassName == null || byDottedClassName.isEmpty())
            ? java.util.Collections.<String, String>emptyMap()
            : java.util.Collections.unmodifiableMap(
                new java.util.HashMap<String, String>(byDottedClassName));
    }

    /** Result type. */
    public static final class Result {
        public final byte[] bytes;
        public final boolean rewritten;
        public final String reason;
        public Result(byte[] bytes, boolean rewritten, String reason) {
            this.bytes = bytes; this.rewritten = rewritten; this.reason = reason;
        }
    }

    /** One pairip Java method-indirection wiring: the obfuscated registry
     *  holds a static java.lang.reflect.Method field (holderInternal.fieldName)
     *  that a protected method reads and invokes; on a genuine device pairip's
     *  startup VM program sets it to the extracted original body
     *  (declaringClass.extractedMethod). See collectPairipRegistryWirings. */
    public static final class PairipWiring {
        public final String holderInternal;   // JVM-internal form, e.g. com/x/Y
        public final String fieldName;         // the static Method field
        public final String extractedMethod;   // the "<name>$NNN" sibling in the reader class
        PairipWiring(String h, String f, String e) {
            holderInternal = h; fieldName = f; extractedMethod = e;
        }
    }

    /** Entry point. Takes the raw class file bytes; returns possibly-
     *  rewritten bytes. Never throws -- on any parse error the
     *  original input is returned unchanged. */
    public static Result maybeRewrite(byte[] raw, String className) {
        if (PRESCAN_ENABLED) {
            boolean needsParse = needsFullParse(raw, className);
            if (PRESCAN_STATS) {
                long seen = prescanSeen.incrementAndGet();
                if (needsParse) prescanFullParsed.incrementAndGet();
                if (seen % 500 == 0) {
                    long fp = prescanFullParsed.get();
                    System.err.println("[rewriter] prescan stats: seen=" + seen
                        + " full-parsed=" + fp + " skipped=" + (seen - fp)
                        + " skip-ratio=" + String.format(java.util.Locale.ROOT,
                            "%.2f%%", 100.0 * (seen - fp) / seen));
                }
            }
            if (!needsParse) {
                return new Result(raw, false, "prescan: no rewrite markers present");
            }
        }
        try {
            ClassFile cf = new ClassFile(raw);
            int hits = 0;

            // ----------------- Pattern D — strip ACC_ABSTRACT -----------------
            // dex2jar sometimes flags a class as ACC_ABSTRACT even though
            // none of its methods are abstract (witnessed: ShatteredPD's `h3`
            // which is a thin libgdx Pointer wrapper -- has only a (J)V
            // constructor, no abstract methods, yet the class flag has
            // ACC_ABSTRACT set). Apps then do `new h3(addr)` somewhere in
            // their code, which would succeed if h3 were concrete -- but
            // JVM rejects with InstantiationError because of the class
            // access flag.
            //
            // Safe heuristic: strip ACC_ABSTRACT from the class IF AND ONLY
            // IF none of its methods is itself ACC_ABSTRACT. A class with
            // no abstract methods cannot have any unimplemented contract;
            // letting it be instantiated is identical to making it
            // `public class` in source. Genuinely-abstract classes
            // (RecyclerView.LayoutManager, AbstractList, etc.) all have
            // at least one abstract method and won't match this filter.
            //
            // Interfaces (ACC_INTERFACE) are NOT touched -- they're
            // ACC_ABSTRACT by JVM definition, and method dispatch on an
            // interface receiver doesn't use the abstract-flag check.
            if ((cf.classAccessFlags & ACC_INTERFACE) == 0
                && (cf.classAccessFlags & ACC_ABSTRACT) != 0) {
                boolean anyMethodAbstract = false;
                for (MethodEntry m : cf.methods) {
                    if ((m.accessFlags & ACC_ABSTRACT) != 0) {
                        anyMethodAbstract = true;
                        break;
                    }
                }
                if (!anyMethodAbstract) {
                    int newFlags = cf.classAccessFlags & ~ACC_ABSTRACT;
                    writeU2(raw, cf.bodyStart, newFlags);
                    if (DEBUG) {
                        System.err.println("[rewriter] " + className
                            + ": Pattern-D stripped ACC_ABSTRACT (no abstract methods present)");
                    }
                    hits++;
                }
            }

            // ----------------- Pattern E -- Glide HttpFetcher redirect --------
            // v1.0.111: F-Droid's org.fdroid.download.glide.HttpFetcher
            // launches a Kotlin coroutine via GlobalScope.launch(Dispatchers.IO)
            // to fetch icons through Ktor+OkHttp. In our environment the
            // launched StandaloneCoroutine never dispatches (all
            // DefaultDispatcher workers stay parked, OkHttp Dispatcher
            // threads stay idle, zero HTTP requests fire even after
            // 134 loads queued for 3 min). Likely a kotlinx-coroutines
            // integration issue with our META-INF/services or HandlerDispatcher.
            //
            // We bypass HttpFetcher by rewriting the UTF-8 constant
            // "org/fdroid/download/glide/HttpFetcher" to a SAME-LENGTH
            // pointer at our drop-in shim "com/macapk/loader/HttpFetcher_Macdr"
            // (both exactly 37 chars), but ONLY in DownloadRequestLoader
            // (which contains the `new HttpFetcher(...)` site). The
            // HttpFetcher class file itself is left untouched (its self-
            // referencing CP entry would otherwise corrupt class loading);
            // post-rewrite, nothing reaches it.
            //
            // Pre-condition: HttpFetcher_Macdr ships in the framework jar
            // with a compatible constructor + DataFetcher interface.
            String dotted = className == null ? "" : className.replace('/', '.');
            if (dotted.equals("org.fdroid.download.glide.DownloadRequestLoader")
                && "1".equals(System.getenv("MACAPK_GLIDE_HTTPFETCHER_SHIM"))) {
                // v1.0.111: opt-in until the matching com.macapk.loader.HttpFetcher_Macdr
                // shim ships in an AppClassLoader-visible location (currently
                // blocked on cross-classloader visibility -- the shim
                // references com.bumptech.glide.Priority + DataFetcher
                // which only live in the APK's classloader, while the
                // framework jar lives in the system classloader. Resolution
                // requires either a separate per-app jar in the
                // AppClassLoader's URL list or a bytecode-level interface
                // injection at runtime).
                int patched = patternEHttpFetcherRedirect(cf, raw);
                if (patched > 0) {
                    if (DEBUG) {
                        System.err.println("[rewriter] " + className
                            + ": Pattern-E redirected " + patched + " HttpFetcher CP entry(ies)");
                    }
                    hits += patched;
                }
            }

            // ----------------- Pattern G -- Java-side /proc/* redirect --------
            // See the block comment at patternGRedirectProcPaths for the full
            // mechanism/rationale. Unconditional (unlike Pattern E, not gated
            // to one class or an opt-in env var) -- ANY class's constant pool
            // can legitimately contain a raw "/proc/meminfo" literal (ad SDKs
            // read it directly via java.io.RandomAccessFile/FileInputStream/
            // FileReader, bypassing the native fopen/open Bionic-ABI remap
            // entirely), and redirecting it to a real, readable file is safe
            // even in the vanishingly unlikely case some OTHER, unrelated
            // string constant happens to coincide.
            int patchedG = patternGRedirectProcPaths(cf, raw);
            if (patchedG > 0) {
                if (DEBUG) {
                    System.err.println("[rewriter] " + className
                        + ": Pattern-G redirected " + patchedG + " /proc/* CP entry(ies)");
                }
                hits += patchedG;
            }

            // ----------------- Patterns A + B — <clinit> scan -----------------
            for (MethodEntry m : cf.methods) {
                String name = cf.utf8(m.nameIndex);
                String desc = cf.utf8(m.descIndex);
                if (!"<clinit>".equals(name) || !"()V".equals(desc)) {
                    // Only scan <clinit> in this pass. Method-body
                    // patterns (FreeType.a() -> h3) are a separate
                    // case requiring concrete-subclass discovery
                    // (deferred).
                    continue;
                }
                CodeAttr code = m.code;
                if (code == null) continue;
                hits += scanAndRewriteClinit(cf, code, className);
            }

            // ----------------- Pattern F -- neutralize JDK-removed ------------
            // ----------------- System.runFinalizersOnExit ---------------------
            // AOSP libcore keeps System.runFinalizersOnExit(boolean) as a
            // @Deprecated back-compat forward to Runtime.runFinalizersOnExit
            // (present on every real Android device, even brand new ones);
            // the unmodified host OpenJDK 21 removed it in JDK 11
            // (JDK-8198249), and java.lang.System cannot be shadowed the way
            // android.* packages can (it is a bootstrap-loader class, final,
            // loaded off the boot module path), so any call throws
            // NoSuchMethodError where real Android silently forwards it. We
            // patch the call site instead of the class: scan EVERY method
            // (not just <clinit> -- this call can appear anywhere) and
            // replace `invokestatic java/lang/System.runFinalizersOnExit:
            // (Z)V` (3 bytes) in place with `pop; nop; nop` (also 3 bytes,
            // so no PC/branch-target/StackMapTable shift is needed). Stack
            // effect matches exactly -- both consume one category-1 value
            // and push nothing -- so the substitution is valid even under
            // full verification, not just -Xverify:none.
            for (MethodEntry m : cf.methods) {
                CodeAttr code = m.code;
                if (code == null) continue;
                hits += patternFNeutralizeRunFinalizersOnExit(cf, code, className);
            }

            // ---- Pattern H -- contain a FOREIGN-PROCESS component's exit ----
            // A component declared `android:process=":something"` runs, on
            // real Android, in a SEPARATE OS process. Mac.apk hosts every
            // component of an app in the ONE runtime process by design (no
            // emulation, no virtualization, no Zygote), and that host process
            // IS the app's main process -- so a System.exit()/Runtime.exit()
            // executed by such a component takes down the whole app, where
            // Android would only have killed a throwaway sibling process and
            // left the main one running.
            //
            // WITNESSED (2026-08-13, Aurora Store 4.8.4, reproduced twice
            // deterministically): finishing onboarding calls JakeWharton's
            // ProcessPhoenix.triggerRebirth, which starts
            //   <activity android:name="com.jakewharton.processphoenix.
            //             PhoenixActivity" android:process=":phoenix"/>
            // whose onCreate is, verbatim from the app's own bytecode:
            //   Process.killProcess(getIntent().getIntExtra(pid))
            //   startActivities(restartIntents)
            //   finish()
            //   Runtime.getRuntime().exit(0)          <-- "Kill kill kill!"
            // On Android the exit(0) ends only the `:phoenix` process and the
            // startActivities() respawns the app. Here it ended the RUNTIME:
            // clean status 0, so no .ips, no hs_err and no jetsam record --
            // which is exactly why the owner's "it crashes, then works on
            // relaunch" report had left no evidence. (It "works on relaunch"
            // because onboarding had already persisted PREFERENCE_INTRO=true
            // before the exit, so the second launch never reaches Phoenix.)
            //
            // Everything ProcessPhoenix actually wants ALREADY works here:
            // Process.killProcess is a documented no-op (android/os/Process),
            // and the startActivities() relaunch was observed completing in
            // the log immediately before the process died. Containing the
            // final exit is therefore the whole fix -- the app restarts its
            // activity and keeps running, which is Android's net effect.
            //
            // MECHANISM: in-place, same-length opcode substitution, exactly
            // as Pattern F does, so no PC / branch-target / StackMapTable
            // shift is possible:
            //   invokestatic  java/lang/System.exit:(I)V   -> pop; nop; nop
            //   invokevirtual java/lang/Runtime.exit:(I)V  -> pop; pop; nop
            //   invokevirtual java/lang/Runtime.halt:(I)V  -> pop; pop; nop
            // Stack effects match exactly (System.exit consumes one int;
            // Runtime.exit/halt consume an int plus the Runtime receiver,
            // both category-1), so the substitution is valid under full
            // verification and not merely under our -Xverify:none.
            //
            // SCOPE, and why this is not a licence to ignore exit(): only
            // classes the MANIFEST declares in a non-default android:process
            // are touched. An app quitting ITSELF from its own main-process
            // code still exits, which is the AOSP behaviour and the one the
            // launcher's Stop/close path depends on.
            String foreignProc = sForeignProcess.isEmpty() ? null
                : sForeignProcess.get(className == null ? ""
                    : className.replace('/', '.'));
            if (foreignProc != null) {
                int patchedH = 0;
                for (MethodEntry m : cf.methods) {
                    CodeAttr code = m.code;
                    if (code == null) continue;
                    patchedH += patternHContainProcessExit(cf, code, className);
                }
                if (patchedH > 0) {
                    NativeLog.i("Mac.apk.ClassFileRewriter", className
                        + " runs in android:process=\"" + foreignProc + "\","
                        + " which Mac.apk hosts in the main runtime process --"
                        + " contained " + patchedH + " process-exit call site(s)"
                        + " so the component cannot terminate the app");
                    hits += patchedH;
                }
            }

            // ART permits accessible writes to app-owned static final fields.
            if (containsNeedle(raw, NEEDLE_REFLECT_FIELD)) {
                for (MethodEntry m : cf.methods) if (m.code != null) {
                    byte[] bc=m.code.code;
                    int end=m.code.codeOffset+m.code.codeLen;
                    for(int pc=m.code.codeOffset;pc<end;) {
                        int op=bc[pc]&0xff, len=instructionLength(op,bc,pc);
                        if(len<=0) break;
                        if(op==OP_INVOKEVIRTUAL && isMethodref(cf,u2(bc,pc+1),"java/lang/reflect/Field","set","(Ljava/lang/Object;Ljava/lang/Object;)V")) {
                            int cls=cf.findOrAddClass("com/macapk/loader/ArtReflection");
                            int nt=cf.findOrAddNameAndType("set","(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V");
                            bc[pc]=(byte)OP_INVOKESTATIC; writeU2(bc,pc+1,cf.findOrAddMethodref(cls,nt)); hits++;
                        }
                        else if(op==OP_INVOKEVIRTUAL && isMethodref(cf,u2(bc,pc+1),"java/lang/reflect/Field","get","(Ljava/lang/Object;)Ljava/lang/Object;")) {
                            int cls=cf.findOrAddClass("com/macapk/loader/ArtReflection");
                            int nt=cf.findOrAddNameAndType("get","(Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;");
                            bc[pc]=(byte)OP_INVOKESTATIC; writeU2(bc,pc+1,cf.findOrAddMethodref(cls,nt)); hits++;
                        }
                        pc+=len;
                    }
                }
            }

            // ---- Pattern L -- libcore private-field names on HotSpot ----
            // Apps reflect libcore's private fields by NAME; the host JDK names
            // some of them differently (java.io.FileDescriptor: libcore
            // `descriptor`, OpenJDK `fd`). Route the app's own
            // Class.getDeclaredField(String) call sites through
            // LibcoreFields.getDeclaredField(Class, String), which answers the
            // libcore name with the host field and passes everything else
            // through. invokevirtual -> invokestatic, both 3 bytes with the same
            // stack effect (Class, String -> Field), so no PC / branch /
            // StackMapTable shift. Rationale and the name table:
            // LibcoreFields.java. Gate MACAPK_LIBCORE_FIELD_NAMES.
            if (LIBCORE_FIELD_NAMES && containsNeedle(raw, NEEDLE_GET_DECLARED_FIELD)
                    && containsNeedle(raw, NEEDLE_LIBCORE_FIELD)) {
                int patchedL = 0;
                for (MethodEntry m : cf.methods) {
                    CodeAttr code = m.code;
                    if (code == null) continue;
                    patchedL += patternLLibcoreFieldNames(cf, code, className);
                }
                if (patchedL > 0) {
                    if (DEBUG) {
                        System.err.println("[rewriter] " + className + ": Pattern-L routed "
                            + patchedL + " getDeclaredField call site(s) through LibcoreFields");
                    }
                    hits += patchedL;
                }
            }

            if (hits > 0) {
                byte[] out = cf.serialize();
                if (DEBUG) {
                    System.err.println("[rewriter] " + className
                        + ": repaired " + hits + " broken <clinit> pattern(s); "
                        + "bytes " + raw.length + " -> " + out.length);
                }
                return new Result(out, true,
                    "repaired " + hits + " broken-<clinit> pattern(s)");
            }
            return new Result(raw, false, "no broken patterns found");
        } catch (Throwable t) {
            // Parse error / unsupported CP entry / malformed class.
            // Return original bytes so the JVM's regular error path
            // surfaces the issue. Never let the rewriter make a
            // working class file worse.
            if (DEBUG) {
                System.err.println("[rewriter] " + className
                    + ": parse failed (" + t + "); pass-through");
            }
            return new Result(raw, false, "parse failed: " + t);
        }
    }

    // -----------------------------------------------------------------
    // Pre-scan -- cheap, allocation-free gate before the full parse
    // -----------------------------------------------------------------
    //
    // See docs/efficiency-classload-logging_2026-07-24.md Finding 1.
    // The full parse above (`new ClassFile(raw)`) allocates one
    // CpEntry per constant-pool entry plus one MethodEntry (+ CodeAttr
    // for every concrete method) per method -- real, unconditional
    // per-class cost paid by EVERY class the app defines (this runs
    // for every class on every launch of every app, via
    // AppClassLoader.findClass()), to catch a small, fixed set of
    // witnessed dex2jar/enjarify-era bugs that only a tiny minority of
    // classes ever match.
    //
    // needsFullParse() decides, WITHOUT allocating anything, whether a
    // class could possibly match ANY of the patterns above (A, B, D,
    // E, F, G -- enumerated from the code as it stands today, not
    // assumed from the doc's "~4 witnessed classes" summary, which
    // predates Pattern F/G). It MUST be a superset of the real trigger
    // conditions: a false POSITIVE costs one wasted full parse
    // (harmless); a false NEGATIVE silently un-fixes a real bug (the
    // one thing this file exists to prevent), so every check below is
    // deliberately looser than the pattern it stands in for. Per
    // pattern:
    //
    //   Pattern A (new java/lang/Enum)   -> the constant pool MUST
    //     hold a UTF8 entry with the exact bytes "java/lang/Enum" for
    //     the CONSTANT_Class the `new` opcode targets to exist at all
    //     (JVMS SS4.4.1, SS4.4.7), AND the class must have a <clinit>
    //     method at all (Patterns A/B only ever scan <clinit> -- see
    //     the loop below that calls scanAndRewriteClinit -- and a
    //     method's name is REQUIRED to exist as a UTF8 CP entry per
    //     JVMS SS4.6, so requiring the literal "<clinit>" bytes too is
    //     zero-risk, not a heuristic). The <clinit> AND-gate matters
    //     in practice: "java/lang/Object"/"java/lang/Enum" alone are
    //     FAR too common to be a useful filter on their own (measured:
    //     "java/lang/Object" is present in ~86% of a real app's
    //     classes, since it's the default super_class name for any
    //     class that extends nothing else).
    //   Pattern B (new java/lang/Object) -> same reasoning, needle
    //     "java/lang/Object" AND <clinit>.
    //   Pattern D (strip ACC_ABSTRACT)   -> NOT a byte string --
    //     classAccessFlags is a positional u2 immediately after the
    //     constant pool (JVMS SS4.1), and "no method is itself
    //     abstract" needs each method's own access_flags. Both are
    //     located here via the exact same non-allocating arithmetic
    //     the real parser uses (cpEntrySize() for the constant pool,
    //     skipFieldOrMethod() for fields/methods), so this walk
    //     cannot disagree with the real parser about entry
    //     boundaries -- it reads the SAME bits Pattern D itself
    //     checks, just without allocating a MethodEntry per method.
    //     This makes Pattern D's pre-scan an EXACT match, not just a
    //     superset (measured necessary: androidx/kotlin-stdlib class
    //     files commonly carry ACC_ABSTRACT on classes with no
    //     abstract methods at all -- e.g. Kotlin file-facades like
    //     ArraysKt -- so a coarser "class is abstract-flagged" check
    //     alone would have full-parsed a large fraction of every
    //     app's classes for nothing).
    //   Pattern E (Glide HttpFetcher)    -> gated on an EXACT class-
    //     name match against the `className` parameter (no bytes
    //     needed) AND an opt-in env var -- reproducing the SAME two
    //     checks the real pattern uses, so this can never be a false
    //     negative.
    //   Pattern F (runFinalizersOnExit)  -> needle
    //     "runFinalizersOnExit" (the Methodref's NameAndType requires
    //     a UTF8 entry with these bytes to exist).
    //   Pattern G (/proc/meminfo redirect) -> needle "/proc/meminfo",
    //     reusing FROM_BYTES_G (the exact constant Pattern G itself
    //     matches against, declared below).
    //
    // The needle scan is a flat, boundary-agnostic search over the
    // WHOLE raw byte array: it does not rely on correctly walking
    // constant-pool entry boundaries, so a bug in the one positional
    // walk below (Pattern D's) can never cause a needle match to be
    // missed. That keeps the zero-false-negative property for the
    // four witnessed/live string patterns (A, B, F, G) independent of
    // the one pattern that genuinely requires positional parsing (D).

    /** MACAPK_REWRITER_PRESCAN=0 disables the gate below and restores
     *  the pre-2026-07-24 behavior of always doing the full structured
     *  parse. Default on. Kept as a rollback escape hatch, and as the
     *  mechanism used to verify zero false negatives: a DEBUG-logged
     *  (MACAPK_DEBUG_REWRITER=1) rewritten-class run with PRESCAN=0
     *  (baseline -- always full-parse, i.e. the exact pre-change
     *  behavior) must produce a BYTE-IDENTICAL set of rewritten
     *  classes to a run with the default PRESCAN=1. */
    private static final boolean PRESCAN_ENABLED =
        !"0".equals(System.getenv("MACAPK_REWRITER_PRESCAN"));

    /** MACAPK_REWRITER_PRESCAN_STATS=1 logs a running skip/full-parse
     *  tally every 500 classes seen. Off by default (an
     *  AtomicLong.incrementAndGet() per class is real, if tiny, cost
     *  that normal boots shouldn't pay) -- diagnostic only, for
     *  measuring the skip ratio during rollout verification. */
    private static final boolean PRESCAN_STATS =
        "1".equals(System.getenv("MACAPK_REWRITER_PRESCAN_STATS"));
    private static final java.util.concurrent.atomic.AtomicLong prescanSeen =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong prescanFullParsed =
        new java.util.concurrent.atomic.AtomicLong();

    private static final byte[] NEEDLE_JAVA_LANG_ENUM =
        "java/lang/Enum".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] NEEDLE_JAVA_LANG_OBJECT =
        "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] NEEDLE_RUN_FINALIZERS_ON_EXIT =
        "runFinalizersOnExit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // Patterns A/B only ever inspect <clinit> (see the `!"<clinit>".
    // equals(name)` guard in the loop above that calls
    // scanAndRewriteClinit). A class without a <clinit> method
    // structurally CANNOT contain this exact UTF8 entry (JVMS SS4.6:
    // every method_info's name_index must reference a CONSTANT_Utf8
    // holding the method's name) -- so AND-ing it in is a zero-risk,
    // JVMS-guaranteed narrowing, not a heuristic. It matters in
    // practice, not just on paper: measured on this machine's full
    // app corpus (org.fdroid.fdroid_15, ~19.7k classes), "java/lang/
    // Object" alone is present in 86% of ALL classes (it is the
    // default super_class name for any class that doesn't extend
    // something else, plus the target of the near-universal `super()`
    // call in every trivial constructor) -- gating on it alone would
    // have full-parsed the overwhelming majority of every app's
    // classes for nothing. AND-ing <clinit> cuts that to ~22-34%
    // across the four required test apps (see
    // docs/efficiency-classfile-prescan_2026-07-24.md).
    private static final byte[] NEEDLE_CLINIT =
        "<clinit>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** True if `raw` might match ANY pattern and therefore needs the
     *  full structured parse. See the block comment above for the
     *  per-pattern reasoning. Never throws -- any hiccup fails OPEN
     *  (returns true), deferring to the full parse's own try/catch in
     *  maybeRewrite(), which already handles malformed classes safely
     *  (returns raw unchanged). Failing open costs at most one wasted
     *  full-parse attempt, never a missed rewrite. */
    private static boolean needsFullParse(byte[] raw, String className) {
        try {
            String dotted = className == null ? "" : className.replace('/', '.');
            if (dotted.equals("org.fdroid.download.glide.DownloadRequestLoader")
                && "1".equals(System.getenv("MACAPK_GLIDE_HTTPFETCHER_SHIM"))) {
                return true;
            }
            // Pattern H: a component the manifest puts in another
            // android:process. Decided by NAME, not by a byte needle -- the
            // map is tiny (zero entries for most apps, single digits
            // otherwise) and a name lookup is cheaper than scanning the
            // class body for `java/lang/Runtime`, which is common.
            if (!sForeignProcess.isEmpty() && sForeignProcess.containsKey(dotted)) {
                return true;
            }
            if (containsNeedle(raw, NEEDLE_RUN_FINALIZERS_ON_EXIT)
                || containsNeedle(raw, FROM_BYTES_G)) {
                return true;
            }
            if (containsNeedle(raw, NEEDLE_REFLECT_FIELD)) return true;
            // Pattern L: a getDeclaredField call AND a libcore field name
            // literal in the same class (an over-match costs one parse).
            if (LIBCORE_FIELD_NAMES && containsNeedle(raw, NEEDLE_GET_DECLARED_FIELD)
                    && containsNeedle(raw, NEEDLE_LIBCORE_FIELD)) {
                return true;
            }
            if (containsNeedle(raw, NEEDLE_CLINIT)
                && (containsNeedle(raw, NEEDLE_JAVA_LANG_ENUM)
                    || containsNeedle(raw, NEEDLE_JAVA_LANG_OBJECT))) {
                return true;
            }
            return classNeedsPatternDFullParse(raw);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Flat, allocation-free substring search for `needle` anywhere in
     *  `haystack`. Deliberately ignores constant-pool structure --
     *  it does not matter whether the match falls inside a real UTF8
     *  entry, only that the bytes are present SOMEWHERE. That is what
     *  makes it safe against any bug in a positional parse: it can
     *  only ever over-match (a wasted full parse), never under-match
     *  (a missed rewrite). */
    private static boolean containsNeedle(byte[] haystack, byte[] needle) {
        int n = haystack.length, m = needle.length;
        if (m == 0 || m > n) return false;
        byte first = needle[0];
        outer:
        for (int i = 0; i <= n - m; i++) {
            if (haystack[i] != first) continue;
            for (int j = 1; j < m; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** True if Pattern D's FULL real condition holds: class-level
     *  ACC_ABSTRACT set (ACC_INTERFACE clear) AND no individual
     *  method has ACC_ABSTRACT set (JVMS SS4.1, SS4.6). This is an
     *  EXACT match for Pattern D's own precondition in maybeRewrite()
     *  above, not just a safe superset -- measured empirically (see
     *  docs/efficiency-classfile-prescan_2026-07-24.md) that a coarser
     *  "class is abstract-flagged" check alone over-triggers heavily:
     *  enjarify marks many ordinary androidx/kotlin-stdlib classes
     *  (Kotlin file-facades like ArraysKt, Intrinsics) ACC_ABSTRACT
     *  even though Pattern D only actually fires when NONE of the
     *  class's methods are themselves abstract. Genuinely-abstract
     *  classes (RecyclerView.LayoutManager, AbstractList, etc. -- has
     *  at least one real abstract method) are common enough in a
     *  typical app that skipping the methods check would tank the
     *  skip ratio for no correctness benefit.
     *
     *  Reuses skipFieldOrMethod() -- the SAME helper the real
     *  ClassFile constructor already uses to skip fields and advance
     *  past methods -- so this walk cannot disagree with the real
     *  parser about entry boundaries. It reads each method's
     *  access_flags (the first u2 of method_info) directly instead of
     *  calling parseMethod(), so no MethodEntry/CodeAttr is ever
     *  allocated. Builds no objects at all. */
    private static boolean classNeedsPatternDFullParse(byte[] r) {
        int cpCount = u2(r, 8);
        int off = 10;
        int i = 1;
        while (i < cpCount) {
            int tag = r[off] & 0xFF;
            int sz = cpEntrySize(tag, r, off);
            off += sz;
            i += (tag == CP_LONG || tag == CP_DOUBLE) ? 2 : 1;
        }
        int classAccessFlags = u2(r, off); off += 2;
        if ((classAccessFlags & ACC_INTERFACE) != 0
            || (classAccessFlags & ACC_ABSTRACT) == 0) {
            return false;   // class-level precondition already fails.
        }
        off += 2;                              // this_class
        off += 2;                              // super_class
        int ifcCount = u2(r, off); off += 2;
        off += 2 * ifcCount;                   // interfaces[], plain u2 each
        int fieldsCount = u2(r, off); off += 2;
        for (int f = 0; f < fieldsCount; ++f) {
            off = skipFieldOrMethod(r, off);
        }
        int methodsCount = u2(r, off); off += 2;
        for (int m = 0; m < methodsCount; ++m) {
            int methodAccessFlags = u2(r, off);
            if ((methodAccessFlags & ACC_ABSTRACT) != 0) {
                return false;   // a real abstract method -> Pattern D can't fire.
            }
            off = skipFieldOrMethod(r, off);
        }
        return true;
    }

    // -----------------------------------------------------------------
    // Pattern E -- Glide HttpFetcher constant-pool string redirect
    // -----------------------------------------------------------------

    private static final byte[] FROM_BYTES =
        "org/fdroid/download/glide/HttpFetcher".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // The redirect target's FQN length is LOAD-BEARING: it must equal
    // FROM_BYTES.length (37) because the rewrite is an in-place CP byte
    // overwrite that cannot resize the entry. The simple name length is
    // therefore chosen to make `com/macapk/loader/<name>` exactly 37.
    // (Do not shorten the package or rename the class without re-padding
    // the simple name -- a rebrand that changed the package length is
    // exactly what the static assertion below guards against.)
    private static final byte[] TO_BYTES =
        "com/macapk/loader/HttpFetcherForGlide".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static {
        if (FROM_BYTES.length != TO_BYTES.length) {
            // Static assertion: the rewrite is in-place, so the strings
            // MUST be the same length. If this throws at class init,
            // someone touched the source / target literals.
            throw new AssertionError("HttpFetcher rewrite name lengths differ: "
                + FROM_BYTES.length + " vs " + TO_BYTES.length);
        }
    }

    /** Walk the CP looking for UTF-8 entries whose bytes equal
     *  FROM_BYTES; for each match, overwrite the bytes in-place with
     *  TO_BYTES. Returns the number of entries rewritten. */
    private static int patternEHttpFetcherRedirect(ClassFile cf, byte[] raw) {
        int hits = 0;
        for (int i = 1; i < cf.cpEntries.size(); i++) {
            CpEntry e = cf.cpEntries.get(i);
            if (e == null || e.tag != CP_UTF8) continue;
            int len = u2(raw, e.offset + 1);
            if (len != FROM_BYTES.length) continue;
            int dataStart = e.offset + 3;
            boolean match = true;
            for (int j = 0; j < FROM_BYTES.length; j++) {
                if (raw[dataStart + j] != FROM_BYTES[j]) { match = false; break; }
            }
            if (!match) continue;
            // In-place rewrite. Length matches so no CP offsets shift.
            System.arraycopy(TO_BYTES, 0, raw, dataStart, TO_BYTES.length);
            hits++;
        }
        return hits;
    }

    // -----------------------------------------------------------------
    // <clinit> bytecode scan
    // -----------------------------------------------------------------

    /** Scan the code buffer for two known-broken dex2jar patterns:
     *
     *  PATTERN A — `new java/lang/Enum` outside the Enum class itself.
     *  Enum is abstract, so `new java/lang/Enum(...)` is NEVER valid
     *  Java; finding it anywhere is unambiguously the bug. We rewrite
     *  both `new` and the paired `invokespecial Enum.<init>:DESC` to
     *  point at THIS class (the bytecode is in this class's <clinit>
     *  because we restrict to <clinit>, and Kotlin enums emit one
     *  `new Enum` per enum value -- often interleaved with array
     *  assembly before the final putstatic -- so we don't try to
     *  pair the new with a specific putstatic).
     *
     *  PATTERN B — `new java/lang/Object` paired with
     *  `putstatic THIS.X:LTHIS;`. Object is a legitimate field type
     *  in many places (e.g. AppInitializer.sLock:Ljava/lang/Object;),
     *  so we MUST check the field's type descriptor and only rewrite
     *  when it is exactly L<THIS>;. The pattern is:
     *      new Object; dup; invokespecial Object.<init>:()V;
     *      putstatic THIS.X:LTHIS;
     *
     *  Returns the number of repairs applied. Rewrites happen
     *  in-place on cf.raw (CodeAttr.code is a live view); CP additions
     *  go through cf.findOrAddMethodref(). */
    private static int scanAndRewriteClinit(
            ClassFile cf, CodeAttr code, String className) {
        int repairs = 0;
        byte[] bc    = code.code;                     // == cf.raw
        int codeEnd  = code.codeOffset + code.codeLen;
        String thisClassName = cf.classNameOf(cf.thisClass);

        // PATTERN A: any `new java/lang/Enum` outside Enum itself is
        // a dex2jar bug. Pair each `new Enum` with the next
        // `invokespecial Enum.<init>:DESC` and rewrite both.
        if (!"java/lang/Enum".equals(thisClassName)) {
            int pc = code.codeOffset;
            while (pc < codeEnd) {
                int op = bc[pc] & 0xFF;
                int len = instructionLength(op, bc, pc);
                if (len <= 0) break;
                if (op == OP_NEW) {
                    int classIdx = u2(bc, pc + 1);
                    if ("java/lang/Enum".equals(cf.classNameOf(classIdx))) {
                        // Verify a paired invokespecial Enum.<init>
                        // exists below (sanity check that this is a
                        // constructor call, not a stray `new Enum`
                        // followed by something else). We DON'T rewrite
                        // the invokespecial: dex2jar often strips the
                        // enum subclass's synthetic <init> entirely
                        // (witnessed: Lifecycle$State has no <init>
                        // method at all in the JAR). Calling
                        // `invokespecial java/lang/Enum.<init>` on a
                        // freshly-allocated subclass receiver is legal
                        // per JVMS §6.5.invokespecial (it initializes
                        // the Enum slice of the subclass object; -Xverify:
                        // none lets the bytecode through since we
                        // don't enforce the "receiver is exactly the
                        // declared class" check at runtime). The
                        // observable effect is: the new object exists
                        // as a Lifecycle$State (so subsequent
                        // putstatic-into-LLifecycle$State; succeeds)
                        // and Enum.name/Enum.ordinal are populated
                        // correctly via the super ctor.
                        int invoke = findPairedInvokespecial(bc, pc, codeEnd, classIdx, cf);
                        if (invoke > 0) {
                            // Rewrite only the `new` target.
                            writeU2(bc, pc + 1, cf.thisClass);
                            if (DEBUG) {
                                int origMethodref = u2(bc, invoke + 1);
                                int origNt = cf.methodrefNameAndType(origMethodref);
                                String desc = cf.utf8(cf.nameAndTypeDescriptor(origNt));
                                System.err.println("[rewriter] " + className
                                    + ": <clinit> pc=" + pc
                                    + " Pattern-A: `new Enum` -> `new " + thisClassName
                                    + "` (keep invokespecial Enum.<init>:" + desc + ")");
                            }
                            repairs++;
                        }
                    }
                }
                pc += len;
            }
        }

        // PATTERN B: `new Object; dup; invokespecial Object.<init>:()V;
        // putstatic THIS.X:LTHIS;` -- the legacy "kotlin EmptyList-shape"
        // case. Strict putstatic-match-with-field-type-LTHIS check is
        // required to avoid breaking `static Object sLock = new Object()`-
        // style legitimate code.
        int pc = code.codeOffset;
        while (pc < codeEnd) {
            int op = bc[pc] & 0xFF;
            int len = instructionLength(op, bc, pc);
            // instructionLength returns -1 for tableswitch/lookupswitch (their
            // length is jump-table-dependent and we don't parse the table). The
            // Pattern A loop above bails on len<=0; Pattern B MUST do the same,
            // otherwise `pc += len` (with len == -1) walks backward forever ->
            // 100% CPU hang. Hit in the wild: a kotlinx.coroutines class's
            // <clinit> (loaded during Recomposer.<init> on the Compose bring-up
            // path) contains a switch, which spun this loop for 190s+. Bailing
            // is correct: the legacy "new;dup;invokespecial;putstatic" enum/
            // EmptyList shape we repair only appears at the very top of <clinit>,
            // before any switch.
            if (len <= 0) break;
            if (op != OP_NEW) { pc += len; continue; }

            int newClassIdx = u2(bc, pc + 1);
            // Next instruction must be dup. If it isn't, this is a
            // weird `new` (rare) -- skip.
            if (pc + 3 >= codeEnd || (bc[pc + 3] & 0xFF) != OP_DUP) {
                pc += len; continue;
            }

            // Scan forward through the constructor-call args until we
            // hit invokespecial referencing the same class.
            int probe = pc + 4;  // past dup
            int invokeOff = -1;
            while (probe < codeEnd) {
                int pop = bc[probe] & 0xFF;
                if (pop == OP_INVOKESPECIAL) {
                    int methodrefIdx = u2(bc, probe + 1);
                    int methodrefClassIdx = cf.methodrefClass(methodrefIdx);
                    if (methodrefClassIdx == newClassIdx) {
                        invokeOff = probe;
                        break;
                    } else {
                        // Different invokespecial in our path; not the
                        // ctor for our `new`. Stop the inner scan to
                        // avoid pairing across nested objects.
                        break;
                    }
                }
                // Bail out on any control flow / scope-ending opcode.
                if (pop == OP_NEW || pop == OP_RETURN || pop == OP_ARETURN) {
                    break;
                }
                int plen = instructionLength(pop, bc, probe);
                if (plen <= 0) break;
                probe += plen;
            }
            if (invokeOff < 0) { pc += len; continue; }

            // After invokespecial, look for the immediate putstatic.
            // The putstatic's Fieldref must:
            //   (a) point at a field DECLARED on THIS class (class_index)
            //   (b) be a field whose TYPE is L<THIS_CLASS>; (descriptor)
            // Both checks are required to distinguish the dex2jar bug
            // from legitimate `new Subclass(); putstatic THIS.field:LSubclass;`
            // patterns (where (b) would not hold).
            int afterInvoke = invokeOff + 3;
            if (afterInvoke >= codeEnd
                || (bc[afterInvoke] & 0xFF) != OP_PUTSTATIC) {
                pc += len; continue;
            }
            int fieldrefIdx      = u2(bc, afterInvoke + 1);
            int fieldrefClassIdx = cf.fieldrefClass(fieldrefIdx);
            if (fieldrefClassIdx != cf.thisClass) {
                pc += len; continue;
            }
            int fieldNtIdx = cf.fieldrefNameAndType(fieldrefIdx);
            String fieldDesc = cf.utf8(cf.nameAndTypeDescriptor(fieldNtIdx));
            String expectedDesc = "L" + thisClassName + ";";
            boolean fieldTypeIsThis = expectedDesc.equals(fieldDesc);

            // Pattern B-variant: field type is an INTERFACE that THIS
            // class implements. Common dex2jar shape:
            //     static SomeImpl FOO = new SomeImpl();   // SomeImpl implements Executor
            //     FOO field declared as: Ljava/util/concurrent/Executor;
            //   dex2jar may emit `new Object` instead of `new SomeImpl`
            //   AND store into a field of interface type. The fix is
            //   the same shape as Pattern B (new Object -> new THIS)
            //   PROVIDED THIS class actually implements the interface
            //   the field is typed as.
            boolean fieldTypeIsImplementedIface = false;
            if (!fieldTypeIsThis && fieldDesc != null
                && fieldDesc.startsWith("L") && fieldDesc.endsWith(";")) {
                String fieldTypeName = fieldDesc.substring(1, fieldDesc.length() - 1);
                for (int ifaceCpIdx : cf.interfaces) {
                    if (fieldTypeName.equals(cf.classNameOf(ifaceCpIdx))) {
                        fieldTypeIsImplementedIface = true;
                        break;
                    }
                }
            }

            if (!fieldTypeIsThis && !fieldTypeIsImplementedIface) {
                // Legitimate cross-type store. Common pattern:
                //   static java.io.File DEFAULT = new File("/tmp");
                // putstatic'd into FileProvider.DEFAULT:Ljava/io/File;.
                // Field type is Ljava/io/File;, not LFileProvider; and
                // FileProvider doesn't implement File (which isn't an
                // interface anyway).
                pc += len; continue;
            }

            // PATTERN B is JUST Object — Enum is handled in pattern A
            // above (with broader matching). The strict putstatic-field-
            // descriptor check below filters out legitimate
            // `static Object FOO = new Object()` (because that field's
            // descriptor is Ljava/lang/Object;, not L<THIS>;).
            String newClassName = cf.classNameOf(newClassIdx);
            if (!"java/lang/Object".equals(newClassName)) {
                pc += len; continue;
            }

            // Sanity: `new`'s class must not equal THIS class.
            if (newClassIdx == cf.thisClass) {
                pc += len; continue;
            }

            // Repair: rewrite ONLY the `new`'s CP index to THIS class.
            // Keep the `invokespecial Object.<init>:()V` unchanged for
            // the same reason as Pattern A: dex2jar often strips the
            // synthetic <init> from the THIS class (witnessed:
            // kotlin.collections.EmptyList has no <init> in the JAR
            // even though Kotlin source would generate one). Calling
            // `invokespecial Object.<init>:()V` on a newly-allocated
            // subclass receiver is legal per JVMS §6.5.invokespecial
            // (with -Xverify:none) -- Object.<init> is a no-op so the
            // receiver remains a properly-typed THIS instance, and
            // subsequent putstatic THIS.X:LTHIS; sees a real LTHIS;
            // reference. Functional state on THIS comes from method
            // dispatch (e.g. EmptyList.size() returns 0); the missing
            // <init> doesn't affect that.
            writeU2(bc, pc + 1, cf.thisClass);

            if (DEBUG) {
                int origMethodrefIdx = u2(bc, invokeOff + 1);
                int origNtIdx        = cf.methodrefNameAndType(origMethodrefIdx);
                String origCls = cf.classNameOf(newClassIdx);
                String desc    = cf.utf8(cf.nameAndTypeDescriptor(origNtIdx));
                System.err.println("[rewriter] " + className
                    + ": <clinit> pc=" + pc
                    + " Pattern-B: `new " + origCls + "` -> `new "
                    + thisClassName + "` (keep invokespecial "
                    + origCls + ".<init>:" + desc + ")");
            }
            repairs++;
            pc += len;
        }
        return repairs;
    }

    // -----------------------------------------------------------------
    // Pattern L -- libcore private-field names (see LibcoreFields)
    // -----------------------------------------------------------------

    /** Gate for Pattern L (default ON); MACAPK_LIBCORE_FIELD_NAMES=0 leaves
     *  every getDeclaredField call site alone. */
    static final boolean LIBCORE_FIELD_NAMES =
        !"0".equals(System.getenv("MACAPK_LIBCORE_FIELD_NAMES"));
    private static final byte[] NEEDLE_REFLECT_FIELD = "java/lang/reflect/Field".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] NEEDLE_GET_DECLARED_FIELD =
        "getDeclaredField".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /** The libcore field names LibcoreFields maps; any one is enough to parse. */
    private static final byte[] NEEDLE_LIBCORE_FIELD =
        "descriptor".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Rewrite every `invokevirtual java/lang/Class.getDeclaredField:
     *  (Ljava/lang/String;)Ljava/lang/reflect/Field;` in one method into
     *  `invokestatic com/macapk/loader/LibcoreFields.getDeclaredField:
     *  (Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;`. */
    private static int patternLLibcoreFieldNames(ClassFile cf, CodeAttr code, String className) {
        int routed = 0;
        int target = -1;
        byte[] bc = code.code;
        int codeEnd = code.codeOffset + code.codeLen;
        int pc = code.codeOffset;
        while (pc < codeEnd) {
            int op = bc[pc] & 0xFF;
            int len = instructionLength(op, bc, pc);
            if (len <= 0) break;
            if (op == OP_INVOKEVIRTUAL && isMethodref(cf, u2(bc, pc + 1), "java/lang/Class",
                    "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;")) {
                if (target < 0) {
                    int cls = cf.findOrAddClass("com/macapk/loader/LibcoreFields");
                    int nt = cf.findOrAddNameAndType("getDeclaredField",
                        "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;");
                    target = cf.findOrAddMethodref(cls, nt);
                }
                bc[pc] = (byte) OP_INVOKESTATIC;
                writeU2(bc, pc + 1, target);
                routed++;
            }
            pc += len;
        }
        return routed;
    }

    // -----------------------------------------------------------------
    // Pattern F -- neutralize invokestatic System.runFinalizersOnExit
    // -----------------------------------------------------------------

    /** Scan one method's code for `invokestatic
     *  java/lang/System.runFinalizersOnExit:(Z)V` and neutralize each hit
     *  in place (pop; nop; nop -- same 3 bytes, same net stack effect).
     *  Returns the number of call sites neutralized. */
    private static int patternFNeutralizeRunFinalizersOnExit(
            ClassFile cf, CodeAttr code, String className) {
        int repairs = 0;
        byte[] bc = code.code;
        int codeEnd = code.codeOffset + code.codeLen;
        int pc = code.codeOffset;
        while (pc < codeEnd) {
            int op = bc[pc] & 0xFF;
            int len = instructionLength(op, bc, pc);
            if (len <= 0) break;
            if (op == OP_INVOKESTATIC && isRunFinalizersOnExit(cf, u2(bc, pc + 1))) {
                bc[pc]     = (byte) OP_POP;
                bc[pc + 1] = (byte) OP_NOP;
                bc[pc + 2] = (byte) OP_NOP;
                if (DEBUG) {
                    System.err.println("[rewriter] " + className
                        + ": Pattern-F pc=" + pc
                        + " neutralized invokestatic System.runFinalizersOnExit(Z)V");
                }
                repairs++;
            }
            pc += len;
        }
        return repairs;
    }

    // -----------------------------------------------------------------
    // Pattern H -- contain a foreign-process component's process exit
    // -----------------------------------------------------------------

    /** Scan one method's code for the three ways a guest can terminate the
     *  host process and neutralize each hit IN PLACE, preserving both the
     *  instruction length (3 bytes) and the net stack effect. Rationale +
     *  scope live at the Pattern-H block in maybeRewrite(). Returns the
     *  number of call sites contained. */
    private static int patternHContainProcessExit(
            ClassFile cf, CodeAttr code, String className) {
        int contained = 0;
        byte[] bc = code.code;
        int codeEnd = code.codeOffset + code.codeLen;
        int pc = code.codeOffset;
        while (pc < codeEnd) {
            int op = bc[pc] & 0xFF;
            int len = instructionLength(op, bc, pc);
            if (len <= 0) break;
            // System.exit(I)V leaves one int on the stack -> pop it.
            if (op == OP_INVOKESTATIC
                    && isMethodref(cf, u2(bc, pc + 1),
                                   "java/lang/System", "exit", "(I)V")) {
                bc[pc]     = (byte) OP_POP;
                bc[pc + 1] = (byte) OP_NOP;
                bc[pc + 2] = (byte) OP_NOP;
                if (DEBUG) {
                    System.err.println("[rewriter] " + className
                        + ": Pattern-H pc=" + pc + " contained System.exit(I)V");
                }
                contained++;
            // Runtime.exit/halt are instance calls: [.., Runtime, int].
            // Two category-1 pops clear both.
            } else if (op == OP_INVOKEVIRTUAL
                    && (isMethodref(cf, u2(bc, pc + 1),
                                    "java/lang/Runtime", "exit", "(I)V")
                     || isMethodref(cf, u2(bc, pc + 1),
                                    "java/lang/Runtime", "halt", "(I)V"))) {
                bc[pc]     = (byte) OP_POP;
                bc[pc + 1] = (byte) OP_POP;
                bc[pc + 2] = (byte) OP_NOP;
                if (DEBUG) {
                    System.err.println("[rewriter] " + className
                        + ": Pattern-H pc=" + pc + " contained Runtime.exit/halt(I)V");
                }
                contained++;
            }
            pc += len;
        }
        return contained;
    }

    /** True if the Methodref (or InterfaceMethodref) CP entry at
     *  methodrefIdx targets exactly owner.name:descriptor. */
    private static boolean isMethodref(ClassFile cf, int methodrefIdx,
                                       String owner, String name, String desc) {
        int classIdx = cf.methodrefClass(methodrefIdx);
        if (classIdx < 0 || !owner.equals(cf.classNameOf(classIdx))) return false;
        int ntIdx = cf.methodrefNameAndType(methodrefIdx);
        if (ntIdx < 0) return false;
        return name.equals(cf.utf8(cf.nameAndTypeName(ntIdx)))
            && desc.equals(cf.utf8(cf.nameAndTypeDescriptor(ntIdx)));
    }

    /** True if the Methodref (or InterfaceMethodref) CP entry at
     *  methodrefIdx targets exactly java/lang/System.runFinalizersOnExit
     *  (Z)V -- the one JDK-11-removed libcore back-compat API confirmed so
     *  far. See the Pattern-F comment in maybeRewrite() for why this can't
     *  be fixed by shadowing java.lang.System instead. */
    private static boolean isRunFinalizersOnExit(ClassFile cf, int methodrefIdx) {
        int classIdx = cf.methodrefClass(methodrefIdx);
        if (classIdx < 0 || !"java/lang/System".equals(cf.classNameOf(classIdx))) return false;
        int ntIdx = cf.methodrefNameAndType(methodrefIdx);
        if (ntIdx < 0) return false;
        return "runFinalizersOnExit".equals(cf.utf8(cf.nameAndTypeName(ntIdx)))
            && "(Z)V".equals(cf.utf8(cf.nameAndTypeDescriptor(ntIdx)));
    }

    // -----------------------------------------------------------------
    // Pattern G -- Java-side /proc/* constant-pool string redirect
    // -----------------------------------------------------------------
    //
    // THE GAP: bionic_shim.cpp already synthesizes /proc/meminfo (and
    // cpuinfo/statm/status) so a native fopen()/open() from the APK's OWN
    // .so code succeeds -- but that remap lives entirely in the Bionic-ABI
    // shim layer, which only intercepts calls from LIFTED/Stage-6 NATIVE
    // code. Some SDKs read these paths directly from Kotlin/Java via a raw
    // java.io.RandomAccessFile/FileInputStream/FileReader (confirmed live,
    // 2026-07-24, Geometry Dash Meltdown: Unity Ads'
    // AndroidStaticDeviceInfoDataSource.getMemoryInfo() AND
    // AndroidDynamicDeviceInfoDataSource, plus BidMachine's
    // io.bidmachine.DeviceInfo$Ram, plus com.unity3d.services.core.device.
    // Device -- FOUR independent SDK call sites in ONE APK, all reading the
    // literal "/proc/meminfo"). That call goes straight to the HOST JVM's
    // own native file-open implementation (java.base's FileInputStream/
    // RandomAccessFile open0), never touching bionic_shim.cpp at all. And
    // unlike android.*/androidx.* framework classes, java.io.File/
    // FileInputStream/FileReader/RandomAccessFile cannot be shadowed: they
    // are java.base BOOTSTRAP classes, and the JVM rejects defining a
    // class named "java.io.*" from any non-bootstrap classloader with
    // SecurityException ("Prohibited package name"). (On real Android
    // this SDK read just succeeds against the device's real /proc/meminfo
    // -- Mac.apk is the odd one out here, not the SDK.)
    //
    // THE FIX: since the JVM/JDK internals can't be intercepted, redirect
    // the APK'S OWN bytecode instead -- exactly Pattern E's technique
    // (find a Utf8 constant-pool entry whose bytes equal FROM_BYTES_G,
    // overwrite in place with TO_BYTES_G), generalized to fire
    // UNCONDITIONALLY (any class, not just one hardcoded target) since any
    // class's constant pool can legitimately contain this literal.
    //
    // Why an in-place SAME-LENGTH overwrite (not a CP-append + rewrite the
    // ldc/ldc_w instruction's operand, which an earlier version of this
    // fix tried): appending new constant-pool entries works, but ldc's
    // operand is only ONE byte (0-255) -- a class whose constant pool
    // already has 255+ entries can't reach a newly-appended (necessarily
    // higher-index) entry without upgrading ldc to the wider ldc_w, which
    // changes the instruction's LENGTH and cascades into shifting every
    // later branch target / exception-table range / line-number entry in
    // the method. Real, but disproportionate, engineering for this
    // narrow case -- and it wasn't hypothetical: Unity Ads' own
    // AndroidDynamicDeviceInfoDataSource hit exactly this (its "/proc/
    // meminfo" ldc's operand couldn't reach a newly-appended entry's
    // index). An in-place, same-length overwrite has no such limit: it
    // never touches the instruction stream or grows the constant pool at
    // all, so it works identically regardless of a class's CP size --
    // trading a discoverable long path (${android_root}/proc/meminfo)
    // for a symlink alias of the SAME 13-byte length as "/proc/meminfo"
    // (see Boot.PROC_MEMINFO_ALIAS + writeJavaProcMirror, which populates
    // the real content and refreshes the alias every boot).
    private static final byte[] FROM_BYTES_G =
        "/proc/meminfo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] TO_BYTES_G =
        "/tmp/mcmeminf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static {
        if (FROM_BYTES_G.length != TO_BYTES_G.length) {
            throw new AssertionError("/proc/meminfo redirect length mismatch: "
                + FROM_BYTES_G.length + " vs " + TO_BYTES_G.length);
        }
    }

    /** Walk the CP looking for UTF-8 entries whose bytes equal
     *  FROM_BYTES_G; for each match, overwrite the bytes in-place with
     *  TO_BYTES_G. Same technique as patternEHttpFetcherRedirect, just
     *  unconditional (every class gets scanned, not one hardcoded
     *  target). Returns the number of entries rewritten. */
    private static int patternGRedirectProcPaths(ClassFile cf, byte[] raw) {
        int hits = 0;
        for (int i = 1; i < cf.cpEntries.size(); i++) {
            CpEntry e = cf.cpEntries.get(i);
            if (e == null || e.tag != CP_UTF8) continue;
            int len = u2(raw, e.offset + 1);
            if (len != FROM_BYTES_G.length) continue;
            int dataStart = e.offset + 3;
            boolean match = true;
            for (int j = 0; j < FROM_BYTES_G.length; j++) {
                if (raw[dataStart + j] != FROM_BYTES_G[j]) { match = false; break; }
            }
            if (!match) continue;
            System.arraycopy(TO_BYTES_G, 0, raw, dataStart, TO_BYTES_G.length);
            hits++;
        }
        return hits;
    }

    /** Starting from a `new X` at `newPc`, scan forward and return the
     *  PC of the next `invokespecial X.<init>:DESC`, or -1 if no
     *  reasonable pair found. Used for Pattern-A (Enum) detection.
     *
     *  We allow common interleaving opcodes between `new` and
     *  `invokespecial` (dup, ldc/ldc_w for the string name,
     *  iconst_N / bipush / sipush for the ordinal, astore/aload
     *  pairs for kotlin-style local-then-array assembly). We BAIL
     *  on control-flow opcodes (branches, returns, athrow) because
     *  those break the linear "construct an X" pattern. */
    private static int findPairedInvokespecial(
            byte[] bc, int newPc, int codeEnd, int newClassIdx, ClassFile cf) {
        int probe = newPc + 3;          // past `new` opcode + u2 operand
        // Tolerate up to 64 bytes of args / interleave before the
        // matching invokespecial. Enum constructor args are at most
        // a couple of ldc + iconst opcodes (~8 bytes). The interleave
        // for array assembly can grow, but for the constructor call
        // itself the invokespecial should still be near. Stop at 64
        // to keep the scan tight.
        int limit = Math.min(codeEnd, newPc + 64);
        while (probe < limit) {
            int op = bc[probe] & 0xFF;
            if (op == OP_INVOKESPECIAL) {
                int methodrefIdx = u2(bc, probe + 1);
                int methodrefClassIdx = cf.methodrefClass(methodrefIdx);
                // Match only if this invokespecial targets the same
                // class as our `new`. Otherwise this is invokespecial
                // for something else (a different constructor); the
                // `new` is unpaired in this window.
                return methodrefClassIdx == newClassIdx ? probe : -1;
            }
            // Bail on control flow / scope-ending opcodes.
            if (op == OP_NEW || op == OP_RETURN || op == OP_ARETURN
                || op == 0xA7    // goto
                || op == 0xC8    // goto_w
                || op == 0xC6 || op == 0xC7  // ifnull/ifnonnull
                || (op >= 0x99 && op <= 0xA6)  // if* int compares
                || op == 0xBF) { // athrow
                return -1;
            }
            int len = instructionLength(op, bc, probe);
            if (len <= 0) return -1;
            probe += len;
        }
        return -1;
    }

    /** Allowlist of base classes that the dex2jar bug emits in place
     *  of the actual concrete class. Both are universal/abstract
     *  bases that cannot legitimately satisfy a field of type
     *  L<concrete>; in Java — finding `new <one of these>` followed
     *  by `putstatic THIS.X:LTHIS;` is a strong tell.
     *
     *  Why allowlist (rather than "anything not equal to THIS"): a
     *  legitimate Java program CAN write
     *      static MyInterface FOO = new MyConcreteImpl();
     *  where FOO is declared MyInterface but holds a concrete impl.
     *  If we rewrote that, we'd break it. The allowlist constrains
     *  the rewrite to the cases we've actually witnessed dex2jar
     *  emitting bad code for. */
    private static boolean isKnownBrokenBase(String className) {
        return "java/lang/Object".equals(className)
            || "java/lang/Enum".equals(className);
    }

    /** Compute the byte length of the instruction at `pc`. Covers
     *  every opcode we might encounter walking through a <clinit>.
     *  Returns 1 for unrecognized opcodes (which is wrong but lets
     *  the scan continue forward conservatively).
     *
     *  Per JVMS Chapter 6: every opcode is 1 byte, followed by 0..N
     *  operand bytes. The table here covers the constructor-call
     *  argument loaders + the ones we rewrite. Less-common opcodes
     *  (switch, wide, multianewarray) we treat by reading the
     *  documented operand byte count. */
    private static int instructionLength(int op, byte[] bc, int pc) {
        switch (op) {
            // 0-operand
            case 0x00: case 0x01: case 0x02: case 0x03: case 0x04:
            case 0x05: case 0x06: case 0x07: case 0x08:                  // nop, *const
            case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D: case 0x0E: case 0x0F:
            case 0x1A: case 0x1B: case 0x1C: case 0x1D:                  // iload_0..3
            case 0x1E: case 0x1F: case 0x20: case 0x21:                  // lload_0..3
            case 0x22: case 0x23: case 0x24: case 0x25:                  // fload_0..3
            case 0x26: case 0x27: case 0x28: case 0x29:                  // dload_0..3
            case 0x2A: case 0x2B: case 0x2C: case 0x2D:                  // aload_0..3
            case 0x59: case 0x5A: case 0x5B: case 0x5C: case 0x5D: case 0x5E: case 0x5F:
            case 0xAC: case 0xAD: case 0xAE: case 0xAF: case 0xB0: case 0xB1:  // ireturn..return
            case 0xBE: case 0xBF: case 0xC2: case 0xC3:                  // arraylen, athrow, monitor
                return 1;

            // 1-operand
            case 0x10:                                                    // bipush
            case 0x12:                                                    // ldc
            case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:        // iload, lload, etc.
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A:        // istore, etc.
            case 0xA9:                                                    // ret
            case 0xBC:                                                    // newarray
                return 2;

            // 2-operand
            case 0x11:                                                    // sipush
            case 0x13: case 0x14:                                         // ldc_w, ldc2_w
            case 0xB2: case 0xB3: case 0xB4: case 0xB5:                   // get/put static/field
            case 0xB6: case 0xB7: case 0xB8:                              // invokevirtual/special/static
            case 0xBB: case 0xBD:                                         // new, anewarray
            case 0xC0: case 0xC1:                                         // checkcast, instanceof
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4:
            case 0xA5: case 0xA6:                                         // if* branches
            case 0xA7: case 0xA8:                                         // goto, jsr
            case 0x84:                                                    // iinc (special: 3 bytes)
                if (op == 0x84) return 3;
                return 3;

            // 4-operand
            case 0xB9:                                                    // invokeinterface
            case 0xBA:                                                    // invokedynamic
                return 5;

            case 0xC5:                                                    // multianewarray
                return 4;

            case 0xC4:                                                    // wide
                // wide modifies the following load/store/iinc. Return
                // 1 here and let the outer loop bail on the next op
                // (it'll over-read by a few bytes but since we only
                // care about constructor-call sequences in <clinit>
                // -- which don't normally use wide -- this is fine).
                return 1;

            case 0xC6: case 0xC7:                                         // ifnull, ifnonnull
            case 0xC8:                                                    // goto_w
                return op == 0xC8 ? 5 : 3;
            case 0xC9:                                                    // jsr_w
                return 5;

            // tableswitch / lookupswitch: variable-length, padded to
            // 4-byte boundary. We don't expect these in <clinit>
            // constructor-call sequences; if we hit one, abort scan.
            case 0xAA: case 0xAB:
                return -1;

            default:
                return 1;
        }
    }

    // -----------------------------------------------------------------
    // pairip Java method-indirection registry detection
    // -----------------------------------------------------------------
    //
    // WHAT pairip DOES (RE 2026-10-17, City of Survivors
    // com.bibiboom.settlementheroes + any pairip-wrapped app that protects
    // its OWN Java methods, not just the native engine):
    //
    //   Each protected method M in class C has its body replaced by
    //       Method m = HOLDER.f;                 // getstatic, type Method
    //       return m.invoke(receiver, {args..});  // invokevirtual Method.invoke
    //   and the original body is moved verbatim into a STATIC sibling named
    //   "M$NNN" in the SAME class C (receiver prepended as the first param).
    //   The static Method field HOLDER.f lives in an obfuscated "registry"
    //   class (hidden in a decoy package, no <clinit>, only Method fields),
    //   and is SET at runtime -- by string name, reflectively -- by pairip's
    //   startup VM program (StartupLauncher.launch -> VMRunner.invoke(startup,
    //   null) -> executeVM), the SAME nargs==0 program the integrity gate
    //   bypasses (native_methods.cpp macapk_pairip_gate). So on Mac.apk the
    //   field stays null and the protected method NPEs at Method.invoke on a
    //   null Method -- which, for an Application/Activity/provider onCreate,
    //   aborts init before the app's real code (e.g. UnityPlayer creation)
    //   ever runs.
    //
    // THE FAITHFUL FIX (what the device VM does, done without the VM):
    //   the Method the VM installs is exactly C.getDeclaredMethod("M$NNN", ..)
    //   -- a reflective handle to the app's OWN extracted body. That mapping
    //   is derivable from the class file alone (the protected method names the
    //   field it reads; its "$NNN" sibling is the body), so we reconstruct it
    //   here and AppClassLoader installs it after defineClass (see
    //   wirePairipRegistry). This runs the app's genuine code; it does NOT
    //   run or defeat the integrity VM, patch .text, or forge anything.
    //
    // This method only DETECTS and never rewrites; the install is reflective
    // and happens post-define. Gated to pairip apps by the caller. Never
    // throws.

    private static final byte[] METHOD_DESC_NEEDLE =
        "Ljava/lang/reflect/Method;".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    /** Scan one class file for pairip method-indirection wirings. Returns the
     *  (holder.field -> this-class.M$NNN) pairs the device's startup VM would
     *  install. Empty for the overwhelming majority of classes (cheap prescan
     *  rejects anything without a java.lang.reflect.Method field reference). */
    public static List<PairipWiring> collectPairipRegistryWirings(
            byte[] raw, String className) {
        List<PairipWiring> out = new ArrayList<>();
        try {
            if (raw == null || !containsBytes(raw, METHOD_DESC_NEEDLE)) return out;
            ClassFile cf = new ClassFile(raw);
            // 1. Index the extracted static siblings: base method name -> "base$NNN".
            //    An ambiguous base (more than one "$NNN" sibling) is dropped: we
            //    cannot know which the registry points at without the VM program.
            java.util.Map<String, String> extracted = new java.util.HashMap<>();
            java.util.Set<String> ambiguous = new java.util.HashSet<>();
            for (MethodEntry m : cf.methods) {
                if ((m.accessFlags & ACC_STATIC) == 0) continue;
                String mn = cf.utf8(m.nameIndex);
                int d = mn.lastIndexOf('$');
                if (d <= 0 || d == mn.length() - 1) continue;
                boolean digits = true;
                for (int i = d + 1; i < mn.length(); ++i) {
                    char c = mn.charAt(i);
                    if (c < '0' || c > '9') { digits = false; break; }
                }
                if (!digits) continue;
                String base = mn.substring(0, d);
                if (extracted.put(base, mn) != null) ambiguous.add(base);
            }
            for (String b : ambiguous) extracted.remove(b);
            if (extracted.isEmpty()) return out;
            // 2. Pair each protected method (one that reads a Method field and
            //    invokes Method.invoke) with its extracted sibling by base name.
            for (MethodEntry m : cf.methods) {
                if (m.code == null) continue;
                String mn = cf.utf8(m.nameIndex);
                String ext = extracted.get(mn);
                if (ext == null) continue;                 // no extracted body for M
                int frIdx = registryFieldRead(cf, m.code);
                if (frIdx < 0) continue;                   // not the indirection shape
                int classIdx = cf.fieldrefClass(frIdx);
                int ntIdx = cf.fieldrefNameAndType(frIdx);
                if (classIdx < 0 || ntIdx < 0) continue;
                String holder = cf.classNameOf(classIdx);
                String fname = cf.utf8(cf.nameAndTypeName(ntIdx));
                if (holder == null || holder.equals("?")
                    || fname == null || fname.equals("?")) continue;
                out.add(new PairipWiring(holder, fname, ext));
            }
        } catch (Throwable t) {
            // Detection must never break class loading.
        }
        return out;
    }

    /** If method M has the pairip indirection shape -- a getstatic of a static
     *  field typed Ljava/lang/reflect/Method; AND an invokevirtual of
     *  java/lang/reflect/Method.invoke -- return the CP index of that Fieldref
     *  (the first such getstatic); else -1. */
    private static int registryFieldRead(ClassFile cf, CodeAttr code) {
        byte[] bc = code.code;                 // == raw backing array
        int pc = code.codeOffset;
        int end = code.codeOffset + code.codeLen;
        int fieldRef = -1;
        boolean sawInvoke = false;
        while (pc < end) {
            int op = bc[pc] & 0xFF;
            if (op == OP_GETSTATIC && fieldRef < 0) {
                int cpIdx = u2(bc, pc + 1);
                int ntIdx = cf.fieldrefNameAndType(cpIdx);
                if (ntIdx >= 0) {
                    String desc = cf.utf8(cf.nameAndTypeDescriptor(ntIdx));
                    if ("Ljava/lang/reflect/Method;".equals(desc)) fieldRef = cpIdx;
                }
            } else if (op == OP_INVOKEVIRTUAL) {
                int cpIdx = u2(bc, pc + 1);
                int cIdx = cf.methodrefClass(cpIdx);
                int ntIdx = cf.methodrefNameAndType(cpIdx);
                if (cIdx >= 0 && ntIdx >= 0
                    && "java/lang/reflect/Method".equals(cf.classNameOf(cIdx))
                    && "invoke".equals(cf.utf8(cf.nameAndTypeName(ntIdx)))) {
                    sawInvoke = true;
                }
            }
            int len = instructionLength(op, bc, pc);
            if (len <= 0) break;               // tableswitch/lookupswitch: bail safely
            pc += len;
        }
        return (fieldRef >= 0 && sawInvoke) ? fieldRef : -1;
    }

    /** Naive substring search over a byte[] (needle is short + rare). */
    private static boolean containsBytes(byte[] hay, byte[] needle) {
        if (needle.length == 0 || hay.length < needle.length) return false;
        final int last = hay.length - needle.length;
        final byte n0 = needle[0];
        outer:
        for (int i = 0; i <= last; ++i) {
            if (hay[i] != n0) continue;
            for (int j = 1; j < needle.length; ++j) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // ClassFile parser (just enough to scan + rewrite)
    // -----------------------------------------------------------------

    /** Tracks the byte ranges of every interesting section so we can
     *  serialize back with our CP additions inserted. */
    private static final class ClassFile {
        final byte[] raw;
        final int    cpStart;         // offset of first CP entry
        int          cpCount;         // current count (>= original)
        final int    cpEndOriginal;   // offset of first byte AFTER original CP
        final int    thisClass;       // u2 (CP index)
        final List<CpEntry> cpEntries = new ArrayList<>();
        final List<CpEntry> cpAdded   = new ArrayList<>();
        final int    bodyStart;       // offset of access_flags
        final int    classAccessFlags;
        final List<Integer> interfaces = new ArrayList<>();  // CP indices
        final List<MethodEntry> methods = new ArrayList<>();

        ClassFile(byte[] r) {
            this.raw = r;
            if (r.length < 10) throw new IllegalArgumentException("too short");
            int magic = u4(r, 0);
            if (magic != 0xCAFEBABE) {
                throw new IllegalArgumentException("bad magic 0x" +
                    Integer.toHexString(magic));
            }
            this.cpCount = u2(r, 8);
            this.cpStart = 10;

            int off = cpStart;
            // CP indices start at 1; entry 0 is unused. Long/Double
            // entries consume TWO indices (the slot after them is
            // unusable per JVMS §4.4.5). We model that with a null
            // placeholder.
            cpEntries.add(null);  // index 0 unused
            int i = 1;
            while (i < cpCount) {
                int tag = r[off] & 0xFF;
                int entryStart = off;
                int sz = cpEntrySize(tag, r, off);
                off += sz;
                CpEntry e = new CpEntry(tag, entryStart, sz);
                cpEntries.add(e);
                if (tag == CP_LONG || tag == CP_DOUBLE) {
                    cpEntries.add(null);  // slot consumed
                    i += 2;
                } else {
                    i += 1;
                }
            }
            this.cpEndOriginal = off;

            this.bodyStart = off;
            this.classAccessFlags = u2(r, off);
            int accessFlags = classAccessFlags; off += 2;
            this.thisClass  = u2(r, off); off += 2;
            int superClass  = u2(r, off); off += 2;
            int ifcCount    = u2(r, off); off += 2;
            // Capture interfaces as CP indices so Pattern B-variant
            // can check whether THIS class implements a putstatic
            // field's declared interface type.
            for (int ifaceI = 0; ifaceI < ifcCount; ++ifaceI) {
                interfaces.add(u2(r, off));
                off += 2;
            }
            int fieldsCount = u2(r, off); off += 2;
            for (int f = 0; f < fieldsCount; ++f) {
                off = skipFieldOrMethod(r, off);
            }
            int methodsCount = u2(r, off); off += 2;
            for (int m = 0; m < methodsCount; ++m) {
                methods.add(parseMethod(r, off));
                off = skipFieldOrMethod(r, off);
            }
            // attributes follow; we don't need to parse them.
        }

        /** Methodref CP entries are 5 bytes: tag, class_idx (u2),
         *  name_and_type_idx (u2). Return the class_idx. */
        int methodrefClass(int idx) {
            CpEntry e = cpEntries.get(idx);
            if (e == null || (e.tag != CP_METHODREF
                              && e.tag != CP_IFACE_METHODREF)) return -1;
            return u2(raw, e.offset + 1);
        }
        int methodrefNameAndType(int idx) {
            CpEntry e = cpEntries.get(idx);
            if (e == null || (e.tag != CP_METHODREF
                              && e.tag != CP_IFACE_METHODREF)) return -1;
            return u2(raw, e.offset + 3);
        }
        int fieldrefClass(int idx) {
            CpEntry e = cpEntries.get(idx);
            if (e == null || e.tag != CP_FIELDREF) return -1;
            return u2(raw, e.offset + 1);
        }
        int fieldrefNameAndType(int idx) {
            CpEntry e = cpEntries.get(idx);
            if (e == null || e.tag != CP_FIELDREF) return -1;
            return u2(raw, e.offset + 3);
        }
        /** NameAndType is 5 bytes: tag, name_idx (u2), desc_idx (u2). */
        int nameAndTypeName(int idx) {
            CpEntry e = cpEntries.get(idx);
            if (e == null || e.tag != CP_NAMEANDTYPE) return -1;
            return u2(raw, e.offset + 1);
        }
        /** NameAndType is 5 bytes: tag, name_idx (u2), desc_idx (u2). */
        int nameAndTypeDescriptor(int idx) {
            CpEntry e = cpEntries.get(idx);
            if (e == null || e.tag != CP_NAMEANDTYPE) return -1;
            return u2(raw, e.offset + 3);
        }
        /** Resolve a Class CP index to its UTF-8 class name. */
        String classNameOf(int classIdx) {
            CpEntry e = cpEntries.get(classIdx);
            if (e == null || e.tag != CP_CLASS) return "?";
            int nameIdx = u2(raw, e.offset + 1);
            return utf8(nameIdx);
        }
        String utf8(int idx) {
            if (idx <= 0 || idx >= cpEntries.size()) return "?";
            CpEntry e = cpEntries.get(idx);
            if (e == null || e.tag != CP_UTF8) return "?";
            int len = u2(raw, e.offset + 1);
            return new String(raw, e.offset + 3, len, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** Find a CONSTANT_Utf8 equal to `v` (original or added) or append one. */
        int findOrAddUtf8(String v) {
            for (int i = 1; i < cpEntries.size(); ++i) {
                CpEntry e = cpEntries.get(i);
                if (e == null || e.tag != CP_UTF8) continue;
                if (e.added ? v.equals(e.addedText) : v.equals(utf8(i))) return i;
            }
            byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);  // ASCII names only
            byte[] entry = new byte[3 + b.length];
            entry[0] = (byte) CP_UTF8;
            writeU2(entry, 1, b.length);
            System.arraycopy(b, 0, entry, 3, b.length);
            CpEntry e = new CpEntry(CP_UTF8, -1, entry.length);
            e.added = true; e.addedBytes = entry; e.addedText = v;
            return append(e);
        }
        /** Find a CONSTANT_Class naming `internalName` or append one. */
        int findOrAddClass(String internalName) {
            for (int i = 1; i < cpEntries.size(); ++i) {
                CpEntry e = cpEntries.get(i);
                if (e == null || e.tag != CP_CLASS || e.added) continue;
                if (internalName.equals(classNameOf(i))) return i;
            }
            int name = findOrAddUtf8(internalName);
            byte[] entry = { (byte) CP_CLASS, (byte) (name >> 8), (byte) name };
            CpEntry e = new CpEntry(CP_CLASS, -1, 3);
            e.added = true; e.addedBytes = entry;
            return append(e);
        }
        /** Append a CONSTANT_NameAndType for (name, descriptor). */
        int findOrAddNameAndType(String name, String desc) {
            int n = findOrAddUtf8(name), d = findOrAddUtf8(desc);
            for (int i = 1; i < cpEntries.size(); ++i) {
                CpEntry e = cpEntries.get(i);
                if (e == null || e.tag != CP_NAMEANDTYPE || e.added) continue;
                if (nameAndTypeName(i) == n && nameAndTypeDescriptor(i) == d) return i;
            }
            byte[] entry = { (byte) CP_NAMEANDTYPE, (byte) (n >> 8), (byte) n, (byte) (d >> 8), (byte) d };
            CpEntry e = new CpEntry(CP_NAMEANDTYPE, -1, 5);
            e.added = true; e.addedBytes = entry;
            return append(e);
        }
        private int append(CpEntry e) {
            int idx = cpEntries.size();
            cpEntries.add(e);
            cpAdded.add(e);
            cpCount++;
            return idx;
        }

        /** Look up an existing Methodref with the given (class_idx,
         *  name_and_type_idx). If absent, append one. Returns the CP
         *  index of the (possibly newly-added) entry. */
        int findOrAddMethodref(int classIdx, int ntIdx) {
            // Search existing entries first.
            for (int i = 1; i < cpEntries.size(); ++i) {
                CpEntry e = cpEntries.get(i);
                if (e == null || e.tag != CP_METHODREF) continue;
                int existing = e.added
                    ? (e.addedClassIdx << 16) | e.addedNtIdx
                    : (u2(raw, e.offset + 1) << 16) | u2(raw, e.offset + 3);
                if (existing == ((classIdx << 16) | ntIdx)) {
                    return i;
                }
            }
            // Append.
            int newIdx = cpEntries.size();
            CpEntry e = new CpEntry(CP_METHODREF, -1, 5);
            e.added = true;
            e.addedClassIdx = classIdx;
            e.addedNtIdx    = ntIdx;
            cpEntries.add(e);
            cpAdded.add(e);
            cpCount++;
            return newIdx;
        }

        /** Serialize the (possibly-modified) class file back to bytes.
         *  The original `raw` is split at cpEndOriginal and rebuilt
         *  with:
         *     - header [0..8) (rewriting cpCount in place)
         *     - original CP bytes [cpStart..cpEndOriginal)
         *     - newly-added CP bytes
         *     - body [bodyStart..end), unchanged (any in-place
         *       bytecode rewrites already live in `raw`)
         */
        byte[] serialize() {
            int newSize = raw.length;
            int addedBytes = 0;
            for (CpEntry e : cpAdded) addedBytes += e.size;
            newSize += addedBytes;
            byte[] out = new byte[newSize];
            // Header [0..10): copy then overwrite cpCount.
            System.arraycopy(raw, 0, out, 0, cpStart);
            writeU2(out, 8, cpCount);
            // Original CP entries.
            int dst = cpStart;
            System.arraycopy(raw, cpStart, out, dst, cpEndOriginal - cpStart);
            dst += cpEndOriginal - cpStart;
            // Added CP entries.
            for (CpEntry e : cpAdded) {
                if (e.addedBytes != null) {
                    System.arraycopy(e.addedBytes, 0, out, dst, e.addedBytes.length);
                    dst += e.addedBytes.length;
                    continue;
                }
                out[dst++] = (byte) e.tag;
                writeU2(out, dst, e.addedClassIdx); dst += 2;
                writeU2(out, dst, e.addedNtIdx);    dst += 2;
            }
            // Body (unchanged; in-place bytecode rewrites stayed in `raw`).
            System.arraycopy(raw, bodyStart, out, dst, raw.length - bodyStart);
            return out;
        }
    }

    private static final class CpEntry {
        final int tag;
        final int offset;   // offset in raw (-1 for added entries)
        final int size;
        boolean added;
        int addedClassIdx;
        int addedNtIdx;
        /** For added Utf8 / Class / NameAndType entries: the whole entry. */
        byte[] addedBytes;
        String addedText;
        CpEntry(int tag, int offset, int size) {
            this.tag = tag; this.offset = offset; this.size = size;
        }
    }

    private static int cpEntrySize(int tag, byte[] r, int off) {
        switch (tag) {
            case CP_UTF8:          return 3 + u2(r, off + 1);
            case CP_INTEGER:
            case CP_FLOAT:         return 5;
            case CP_LONG:
            case CP_DOUBLE:        return 9;
            case CP_CLASS:         return 3;
            case CP_STRING:        return 3;
            case CP_FIELDREF:
            case CP_METHODREF:
            case CP_IFACE_METHODREF:return 5;
            case CP_NAMEANDTYPE:   return 5;
            case CP_METHODHANDLE:  return 4;
            case CP_METHODTYPE:    return 3;
            case CP_DYNAMIC:
            case CP_INVOKEDYN:     return 5;
            case CP_MODULE:
            case CP_PACKAGE:       return 3;
            default:
                throw new IllegalArgumentException(
                    "unknown CP tag " + tag + " at offset " + off);
        }
    }

    /** Skip a field_info or method_info (same layout):
     *  u2 access_flags, u2 name_idx, u2 desc_idx, u2 attrs_count,
     *  attribute_info[]. */
    private static int skipFieldOrMethod(byte[] r, int off) {
        off += 6;
        int attrCount = u2(r, off); off += 2;
        for (int a = 0; a < attrCount; ++a) {
            off += 2;                  // name_idx
            int len = u4(r, off);
            off += 4 + len;
        }
        return off;
    }

    private static final class MethodEntry {
        final int accessFlags;
        final int nameIndex;
        final int descIndex;
        final CodeAttr code;
        MethodEntry(int af, int n, int d, CodeAttr c) {
            accessFlags = af; nameIndex = n; descIndex = d; code = c;
        }
    }

    /** Parse method_info at offset, return MethodEntry. Caller is
     *  responsible for advancing past the method. */
    private static MethodEntry parseMethod(byte[] r, int off) {
        int accessFlags = u2(r, off); off += 2;
        int nameIdx     = u2(r, off); off += 2;
        int descIdx     = u2(r, off); off += 2;
        int attrCount   = u2(r, off); off += 2;
        CodeAttr code = null;
        // We need the CP to look up attribute names, but we don't
        // have it parsed yet at this exact call. Instead, we use a
        // structural trick: the Code attribute's first 4 bytes are
        // name_idx (u2) + length (u4); we'll resolve which attribute
        // is Code via the OUTER ClassFile.utf8(idx) once cpEntries is
        // populated.
        for (int a = 0; a < attrCount; ++a) {
            int attrNameIdx = u2(r, off); off += 2;
            int attrLen     = u4(r, off); off += 4;
            int attrStart   = off;
            // Heuristically detect Code attribute: a Code attribute
            // starts with max_stack (u2), max_locals (u2), code_length
            // (u4). The first byte of bytecode follows. For our scan,
            // we just need to know the start + length of the bytecode
            // buffer if THIS attribute IS Code. We can't tell here
            // for sure without CP. So we capture both candidate
            // attrs; the OUTER walker will check the attr-name UTF8
            // against "Code". For simplicity, we always make a
            // CodeAttr and let the OUTER walker filter via the
            // method's name "<clinit>".
            //
            // Since we ONLY scan <clinit>, and a method has at most
            // ONE Code attribute, we just take the first attribute
            // here as the candidate. Methods like <clinit> rarely
            // have multiple attributes -- usually just Code.
            if (code == null && attrLen >= 8) {
                int maxStack  = u2(r, attrStart);
                int maxLocals = u2(r, attrStart + 2);
                int codeLen   = u4(r, attrStart + 4);
                if (codeLen > 0 && 8 + codeLen <= attrLen) {
                    code = new CodeAttr(r, attrStart + 8, codeLen, attrNameIdx);
                }
            }
            off = attrStart + attrLen;
        }
        return new MethodEntry(accessFlags, nameIdx, descIdx, code);
    }

    private static final class CodeAttr {
        final byte[] code;       // points to the SAME backing array as raw
        final int    codeOffset;
        final int    codeLen;
        final int    attrNameIdx;
        CodeAttr(byte[] raw, int off, int len, int attrNameIdx) {
            // Wrap the bytecode in a slice that points at the same
            // backing array so in-place rewrites land in `raw`.
            this.code = raw;       // shared
            this.codeOffset = off;
            this.codeLen = len;
            this.attrNameIdx = attrNameIdx;
            // BUT the scan loop indexes `code[]` starting at 0, so we
            // need a real slice. Use a wrapping approach: present the
            // code as a separate sub-array that's a *copy* and then
            // copy back. Simpler: switch to using offsets in `raw`.
            // For this first pass, we DO copy + copy-back; the
            // performance overhead is negligible (a few KB per class).
        }
    }

    // Override: the scan loop uses code.code as if it were the slice.
    // Implementing that requires a small adjustment -- we extract the
    // slice into a real byte[] before scan, then re-splice. Done in
    // ClassFile.scanAndRewrite via the helper below.

    // -----------------------------------------------------------------
    // Low-level read/write helpers
    // -----------------------------------------------------------------

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
    private static int u4(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
             | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8)
             | (b[off + 3] & 0xFF);
    }
    private static void writeU2(byte[] b, int off, int v) {
        b[off    ] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }
}
