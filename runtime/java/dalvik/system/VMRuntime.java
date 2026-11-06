// dalvik.system.VMRuntime -- ART/libart's runtime-introspection singleton
// (libcore/libart/src/main/java/dalvik/system/VMRuntime.java). It is a hidden
// API, but a meaningful set of libraries reach it by REFLECTION at init:
//   * Conscrypt / older OkHttp probe is64Bit() / vmInstructionSet() to pick a
//     native ABI.
//   * RenderScript-support and a few Firebase/GMS init paths read
//     getTargetSdkVersion() / setTargetSdkVersion().
//   * Some allocators call newNonMovableArray / newUnpaddedArray.
// A missing class is caught by most callers' try/catch, but not all; supplying a
// faithful-shaped class removes the failure mode and lets ABI probes succeed.
//
// AOSP surface mirrored (signatures verified against libart's VMRuntime.java;
// these are not in android.jar so they are reached reflectively):
//   public static VMRuntime getRuntime()
//   public boolean is64Bit()
//   public String vmInstructionSet()
//   public String vmLibrary()
//   public int getTargetSdkVersion()
//   public void setTargetSdkVersion(int)
//   public static int getSdkVersion()        // some forks expose this
//   public long addressOf(Object)            // best-effort 0
//   public boolean isCheckJniEnabled()
//   public Object newNonMovableArray(Class<?>, int)
//   public Object newUnpaddedArray(Class<?>, int)
//   public void registerNativeAllocation(long) / registerNativeFree(long)
//   public String[] getRuntimeFeatures()
//   public void setHiddenApiExemptions(String...)        // newer
//   public static void setProcessDataDirectory(String)   // handleBindApplication's data-dir hand-off
//   public static void setNonSdkApiUsageConsumer(java.util.function.Consumer)  // skipped: type not load-bearing
//
// Mac.apk runs guest arm64 (Stage 6) natively in-process, so is64Bit() is true
// and the instruction set is "arm64-v8a" for the common path. (The arm32-lifter
// path makes the guest believe it is 32-bit; if a future load wants per-load
// ABI honesty it can set the system property below. We default to 64-bit, which
// matches the dominant Stage-6 path.)
package dalvik.system;

public final class VMRuntime {

    private static final VMRuntime INSTANCE = new VMRuntime();

    // The APP's targetSdkVersion as ART sees it. AOSP's ART leaves this at 0
    // until ActivityThread.handleBindApplication calls
    // VMRuntime.getRuntime().setTargetSdkVersion(data.appInfo.targetSdkVersion),
    // and 0 is the honest pre-bind answer.
    //
    // This was a hardcoded 34 with NO caller of the setter anywhere in the
    // tree, so every app -- including one targeting 8 -- was told "34". That
    // matters because older support-library and ART-touching code reads
    // VMRuntime.getRuntime().getTargetSdkVersion() IN PREFERENCE to
    // Build.VERSION.SDK_INT precisely because it is the PER-APP answer, and a
    // constant here silently answers for a modern app. See
    // docs/legacy-compat-audit_2026-08-24.md 5.2.
    //
    // Two sources now feed it, in AOSP's own order of authority: an explicit
    // setTargetSdkVersion (Application.macapkInitVersion makes that call the
    // moment the loader hands over the parsed manifest, which is our
    // handleBindApplication), and failing that a LAZY read of the app's own
    // ApplicationInfo, so a guest that asks before the loader has pushed still
    // gets a real answer rather than a stale constant.
    private int targetSdkVersion = 0;
    private boolean targetSdkVersionSet = false;

    private VMRuntime() {}

    /** AOSP: the process-wide singleton. */
    public static VMRuntime getRuntime() {
        return INSTANCE;
    }

    /** AOSP: true on a 64-bit ART. Stage 6 executes guest arm64 in-process, so
     *  the dominant path is 64-bit. Overridable via the system property
     *  "macapk.vm.is64bit" (set false for the arm32-lifter path). */
    public boolean is64Bit() {
        return !"false".equals(System.getProperty("macapk.vm.is64bit"));
    }

    /** AOSP: the primary ABI's instruction set ("arm64-v8a" / "armeabi-v7a").
     *  Honest for Stage 6; overridable via "macapk.vm.instructionSet" for the
     *  arm32-lifter path. */
    public String vmInstructionSet() {
        String p = System.getProperty("macapk.vm.instructionSet");
        if (p != null && !p.isEmpty()) return p;
        return is64Bit() ? "arm64-v8a" : "armeabi-v7a";
    }

    /** AOSP: the runtime backing library; on modern Android this is "libart.so". */
    public String vmLibrary() {
        return "libart.so";
    }

    /** AOSP: the app's targetSdkVersion as the runtime sees it.
     *  AOSP marks this synchronized (paired with setTargetSdkVersion). */
    public synchronized int getTargetSdkVersion() {
        if (!targetSdkVersionSet) {
            int t = android.app.Application.macapkAppTargetSdk();
            if (t > 0) {
                targetSdkVersion = t;
                targetSdkVersionSet = true;
            }
        }
        return targetSdkVersion;
    }

    /** AOSP: setter used by the activity-thread bootstrap to inform the runtime
     *  of the manifest target. We retain it (drives getTargetSdkVersion).
     *  AOSP marks this synchronized. */
    public synchronized void setTargetSdkVersion(int targetSdkVersion) {
        this.targetSdkVersion = targetSdkVersion;
        this.targetSdkVersionSet = true;
    }

    /** Some forks expose a static getSdkVersion mirroring Build.VERSION.SDK_INT. */
    public static int getSdkVersion() {
        try {
            return android.os.Build.VERSION.SDK_INT;
        } catch (Throwable t) {
            return 34;
        }
    }

    /** AOSP: the raw address of an object. We have no fixed-address heap to
     *  expose; return 0 (callers use this only for diagnostics/pinning, which
     *  no-ops here). */
    public long addressOf(Object obj) {
        return 0L;
    }

    /** AOSP: whether CheckJNI is on. We do not run ART's CheckJNI; report false. */
    public boolean isCheckJniEnabled() {
        return false;
    }

    /** AOSP: allocate a non-movable array. The JVM heap is managed and has no
     *  "non-movable" concept exposed to Java; a normal array satisfies the
     *  observable contract (callers want an array of the element type/length).
     *  componentType must be a primitive or reference type. */
    public Object newNonMovableArray(Class<?> componentType, int length) {
        return java.lang.reflect.Array.newInstance(componentType, length);
    }

    /** AOSP: allocate an array sized to fill its allocation padding. We have no
     *  allocator padding to exploit; a plain array of the requested length is
     *  the faithful observable result. */
    public Object newUnpaddedArray(Class<?> componentType, int minLength) {
        return java.lang.reflect.Array.newInstance(componentType, minLength);
    }

    /** AOSP: hint the GC about native memory pressure. The JVM GC has its own
     *  accounting; honor as a no-op (the call must not fail). */
    public void registerNativeAllocation(long bytes) {
        // no-op: JVM GC manages its own native-memory pressure heuristics
    }

    /** AOSP back-compat overload (deprecated): older callers pass an int. AOSP
     *  keeps this distinct method (descriptor (I)V vs (J)V) and forwards to the
     *  long form; a missing int overload is a NoSuchMethodError for those
     *  callers, so we mirror it. */
    @Deprecated
    public void registerNativeAllocation(int bytes) {
        registerNativeAllocation((long) bytes);
    }

    /** AOSP: counterpart to registerNativeAllocation. */
    public void registerNativeFree(long bytes) {
        // no-op
    }

    /** AOSP back-compat overload (deprecated int form). */
    @Deprecated
    public void registerNativeFree(int bytes) {
        registerNativeFree((long) bytes);
    }

    /** AOSP: enabled runtime feature flags. We expose none. */
    public String[] getRuntimeFeatures() {
        return new String[0];
    }

    /** AOSP (newer): exempt signatures from hidden-API enforcement. We do not
     *  enforce hidden-API access at all, so this is a no-op. */
    public void setHiddenApiExemptions(String... signaturePrefixes) {
        // no-op: Mac.apk does not enforce hidden-API restrictions
    }

    // The app's data directory, as ActivityThread.handleBindApplication hands
    // it to ART: "Pass data directory path to ART. This is used for caching
    // information and should be set before any application code is loaded."
    // (frameworks/base ActivityThread.java, VMRuntime.setProcessDataDirectory(
    // data.appInfo.dataDir)). ART uses it to place the content-addressed
    // Anonymous-DexFile@<checksum> artifacts of in-memory dex; our analogue is
    // MacApkDexConverter's dynamic-DEX cache under <dataDir>/code_cache.
    // com.macapk.loader.Boot sets it right after it resolves the data dir,
    // before the Application class loads. Null before that and in offline
    // tools, which then convert into a per-run temp dir instead.
    private static volatile String sProcessDataDirectory;

    /** AOSP (@SystemApi MODULE_LIBRARIES, libart VMRuntime.java): record the
     *  process's app data directory. */
    public static void setProcessDataDirectory(String dataDir) {
        sProcessDataDirectory = dataDir;
    }

    /** Mac.apk-internal read side of {@link #setProcessDataDirectory}. AOSP
     *  keeps the value native-side with no Java getter, so this stays
     *  package-private rather than inventing public API. */
    static String processDataDirectory() {
        return sProcessDataDirectory;
    }

    /** AOSP: notify the runtime that boot has completed (warms caches on
     *  device). No analogue here. */
    public void notifyStartupCompleted() {
        // no-op
    }

    // ---- static ABI / instruction-set helpers ----
    // Conscrypt, the RenderScript support runtime, and older OkHttp probe these
    // reflectively to pick a native ABI. The map + bodies are copied verbatim
    // from AOSP libart VMRuntime.java (ABI_TO_INSTRUCTION_SET_MAP, lines 66-72;
    // getInstructionSet/is64BitInstructionSet/is64BitAbi bodies) so behavior --
    // including the IllegalArgumentException on an unknown ABI -- matches.

    private static final java.util.Map<String, String> ABI_TO_INSTRUCTION_SET_MAP =
            new java.util.HashMap<String, String>(16);
    static {
        ABI_TO_INSTRUCTION_SET_MAP.put("armeabi", "arm");
        ABI_TO_INSTRUCTION_SET_MAP.put("armeabi-v7a", "arm");
        ABI_TO_INSTRUCTION_SET_MAP.put("x86", "x86");
        ABI_TO_INSTRUCTION_SET_MAP.put("x86_64", "x86_64");
        ABI_TO_INSTRUCTION_SET_MAP.put("arm64-v8a", "arm64");
        ABI_TO_INSTRUCTION_SET_MAP.put("arm64-v8a-hwasan", "arm64");
        ABI_TO_INSTRUCTION_SET_MAP.put("riscv64", "riscv64");
    }

    /** AOSP: map an ABI string to its instruction-set name; throws
     *  IllegalArgumentException for an unsupported ABI (faithful to AOSP). */
    public static String getInstructionSet(String abi) {
        final String instructionSet = ABI_TO_INSTRUCTION_SET_MAP.get(abi);
        if (instructionSet == null) {
            throw new IllegalArgumentException("Unsupported ABI: " + abi);
        }
        return instructionSet;
    }

    /** AOSP: whether the given instruction set is 64-bit. */
    public static boolean is64BitInstructionSet(String instructionSet) {
        return (instructionSet != null) && instructionSet.contains("64");
    }

    /** AOSP: whether the given ABI is 64-bit. */
    public static boolean is64BitAbi(String abi) {
        return is64BitInstructionSet(getInstructionSet(abi));
    }

    /**
     * AOSP VMRuntime.getBaseApkOptimizationInfo (a native in ART,
     * dalvik_system_VMRuntime.cc): the compiler filter and compilation reason ART
     * registered for the primary APK's odex (AppInfo::GetPrimaryApkOptimizationStatus).
     * No ART odex exists here (the app's DEX runs converted, on HotSpot), so the
     * honest answer is AppInfo's "nothing registered" value, "unknown" for both,
     * which every OptimizationInfo predicate reports as false.
     */
    public static DexFile.OptimizationInfo getBaseApkOptimizationInfo() {
        return new DexFile.OptimizationInfo("unknown", "unknown");
    }
}
