// android.os.Build -- system identification constants. Apps and engines
// inspect these to gate behavior on Android version.
//
// The version identity (SDK_INT, RELEASE, and everything derived from
// them, fingerprint included) comes from com.macapk.loader.PresentedSdk,
// the single source for "what Android version is this device?". Default
// presentation is Android 10 (API 29), unchanged; the PresentedSdk gates
// (MACAPK_SDK_INT, MACAPK_SDK_FROM_TARGET) can raise it per process --
// see that class's header for the model and the clamp evidence.
//
// NOTE the fields stay `static final` on purpose: the level is
// process-constant (one app per process), and apps read these as fields
// at runtime (the SDK stub jar ships them without ConstantValue, so
// javac never inlines them into app dex). What the PresentedSdk call
// additionally guarantees is that OUR OWN framework javac compilation
// cannot constant-fold them either -- a method-call initializer is not a
// JLS constant expression -- so every reader, app and framework alike,
// observes the same runtime-chosen value.
package android.os;

public class Build {
    public static final String UNKNOWN     = "unknown";
    public static final String BOARD       = "macapk";
    public static final String BOOTLOADER  = "unknown";
    // BRAND / MANUFACTURER / MODEL: what an app (and the account-security
    // alerts of the services it signs in to, "New login from <model>") is
    // told the device is. Owner decision 2026-11-04: a Mac, made by Apple. The
    // model is the REAL host hardware model (sysctl hw.model, e.g. "Mac14,7"),
    // exported once per process by the runtime as MACAPK_HOST_MODEL; "Mac"
    // when that is missing. Method-call initializers (not JLS constants), like
    // TAGS/TYPE, so no reader constant-folds them. FINGERPRINT below derives
    // from BRAND/PRODUCT/DEVICE, so it follows BRAND automatically.
    public static final String BRAND       = macapkBrand();
    public static final String CPU_ABI     = "arm64-v8a";
    public static final String CPU_ABI2    = "";
    public static final String[] SUPPORTED_ABIS    = { "arm64-v8a" };
    public static final String[] SUPPORTED_64_BIT_ABIS = { "arm64-v8a" };
    public static final String[] SUPPORTED_32_BIT_ABIS = {};
    public static final String DEVICE      = "macapk";
    public static final String DISPLAY     = "Mac.apk-1";
    public static final String HARDWARE    = "apple_silicon";
    public static final String HOST        = "macapk-mac";
    public static final String ID          = "PR1";
    // The host is a Mac, so the manufacturer is Apple (was "Voltare", the
    // project's name, which is not who made the hardware an app runs on).
    public static final String MANUFACTURER = macapkBrand();
    public static final String MODEL       = macapkHostModel();
    public static final String PRODUCT     = "macapk";
    public static final String RADIO       = "unknown";
    // TAGS/TYPE: normally a release device ("release-keys"/"user"). When the
    // owner opts a hosted Google Play services process into accepting the
    // debug-signed Google apps they installed (the broker sets
    // MACAPK_DEBUG_CERTS=1 only on the GMS host, only while "Use Google GMS"
    // is on), we present the TRUE identity of that process: a developer build
    // (userdebug/test-keys). This is what GMS's OWN GoogleCertificates gate
    // reads (m5.cg.b/mo.a: Build.TYPE in {eng,userdebug} && Build.TAGS has
    // {dev-keys,test-keys}) before it will consult its developer flag-override
    // file -- the documented hermetic-override mechanism. It is NOT an emulator
    // persona (our SystemProperties still answers no qemu/characteristics) and
    // NOT a certificate forgery; it is an honest "this is a dev build" answer,
    // scoped to the one process that asked and gated on the owner's opt-in.
    // Method-call initializers (not JLS constants), so no reader constant-folds
    // them -- same rule the version fields above rely on.
    public static final String TAGS        = macapkDebugCertsPersona() ? "test-keys"  : "release-keys";
    public static final String TYPE        = macapkDebugCertsPersona() ? "userdebug"  : "user";
    /** True only when this process was launched with MACAPK_DEBUG_CERTS=1 (the
     *  broker sets it on a GMS host under the "Use Google GMS" opt-in). */
    private static boolean macapkDebugCertsPersona() {
        return "1".equals(System.getenv("MACAPK_DEBUG_CERTS"));
    }
    public static final String USER        = "macapk";
    private static String macapkBrand() { return "Apple"; }
    /** The host hardware model the runtime exported (MACAPK_HOST_MODEL), or
     *  "Mac". Accepts only a short, printable, non-empty value: a model string
     *  ends up in HTTP headers, account alerts and logs. */
    private static String macapkHostModel() {
        try {
            String m = System.getenv("MACAPK_HOST_MODEL");
            if (m != null) {
                m = m.trim();
                if (!m.isEmpty() && m.length() <= 40) {
                    boolean ok = true;
                    for (int i = 0; i < m.length(); i++) {
                        char c = m.charAt(i);
                        if (c < 0x20 || c > 0x7e) { ok = false; break; }
                    }
                    if (ok) return m;
                }
            }
        } catch (Throwable ignored) {}
        return "Mac";
    }

    // ⚠ v1.0.938: DERIVED from the fields below, not spelled independently.
    // It used to read "macapk/v2/arm64-v8a:.../PR1/0:user/release-keys", which
    // disagreed with our OWN Build fields in four of its eight components --
    // brand macapk vs the old BRAND "Mac.apk", product v2 vs PRODUCT "macapk", device
    // arm64-v8a vs DEVICE "macapk", incremental 0 vs VERSION.INCREMENTAL "1".
    // AOSP's deriveFingerprint() BUILDS the string from those same properties
    // (Build.java, `$(BRAND)/$(PRODUCT)/$(DEVICE):$(RELEASE)/$(ID)/$(INCREMENTAL):$(TYPE)/$(TAGS)`),
    // so a real device cannot contradict itself here. Apps parse this string
    // and compare components against the individual fields; a mismatch is the
    // kind of thing that makes a device-detect heuristic take a branch nobody
    // tested. This does NOT change any identity value -- every component still
    // says what we already said elsewhere -- and it deliberately does NOT match
    // the emulator's fingerprint, which would be claiming to be a Google device.
    public static final String FINGERPRINT =
        BRAND + "/" + PRODUCT + "/" + DEVICE + ":" + VERSION.RELEASE
        + "/" + ID + "/" + VERSION.INCREMENTAL + ":" + TYPE + "/" + TAGS;
    // AOSP: `TIME = getLong("ro.build.date.utc") * 1000`, and getLong returns -1
    // on parse failure -- so on ANY real Android build TIME is either a positive
    // epoch-ms or -1000. ZERO IS NOT A REACHABLE VALUE, which made us a device
    // claiming to have been built at the Unix epoch. Crash reporters and
    // update-checkers compute build age from it, and it flows into
    // getFingerprintedPartitions() as each Partition's build time.
    //
    // It was also incoherent with our OWN VERSION.SECURITY_PATCH below: a 1970
    // build carrying a 2026 patch level. Both now derive from ONE constant, so
    // they cannot drift apart again -- which is the same failure mode that let
    // PackageManager advertise sensors the catalog had dropped.
    //
    // Not System.currentTimeMillis(): a build date must be STABLE across runs
    // (an app caching it would see the device rebuilt every launch) and must not
    // be in the future.
    static final String MACAPK_BUILD_DATE = "2026-01-01";   // also VERSION.SECURITY_PATCH
    public static final long   TIME        = macapkBuildTimeMillis();

    private static long macapkBuildTimeMillis() {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(MACAPK_BUILD_DATE);
            return d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Throwable t) {
            return 1767225600000L;   // 2026-01-01T00:00:00Z, the same instant
        }
    }
    // ⚠ v1.0.938: UNKNOWN, not an invented serial. AOSP's javadoc is explicit --
    // "@deprecated Use getSerial() instead. ... always set to Build.UNKNOWN" --
    // because Android 8 REMOVED the stable hardware serial from the SDK on
    // purpose: it was a permanent, unresettable device identifier. We were
    // handing every app "macapk-stub", i.e. re-supplying exactly the tracking
    // token the platform withdrew, and one that is identical across every
    // Mac.apk install so it also fingerprints the RUNTIME. `unknown` is both
    // the AOSP-correct answer and the privacy-preserving one, and it happens to
    // close the BLD.SERIAL row (the reference also answers "unknown").
    public static final String SERIAL      = UNKNOWN;
    // API 31: primary SoC identity. AOSP itself falls back to UNKNOWN
    // (SocProperties.soc_manufacturer()/soc_model() are Optional, backed by
    // ro.soc.* system properties that a device without that info leaves
    // unset) -- we have no SoC identity to report either, so we take the
    // same documented fallback rather than inventing one.
    public static final String SOC_MANUFACTURER = UNKNOWN;
    public static final String SOC_MODEL   = UNKNOWN;

    // AOSP core/java/android/os/Build.java:192 / :205
    //     public static final String SKU = getString("ro.boot.hardware.sku");
    //     public static final String ODM_SKU = getString("ro.boot.product.hardware.sku");
    // where getString() is SystemProperties.get(property, UNKNOWN). Both are
    // bootloader-supplied hardware-variant tags; a device whose bootloader
    // does not set them reports UNKNOWN, and so does this host. Resolved
    // THROUGH SystemProperties rather than pinned to the literal, so that if
    // the runtime ever pushes those keys the answer follows -- and so an app
    // reading Build.SKU and __system_property_get("ro.boot.hardware.sku")
    // cannot disagree.
    // NOTE these are deliberately NOT compile-time constants (AOSP's are not
    // either: the signature file writes them with no initializer), so an app
    // reads the real field rather than an inlined string.
    /** AOSP: {@code IS_DEBUGGABLE = SystemProperties.getInt("ro.debuggable", 0) == 1}
     *  (Build.java:1941-1942). We present TYPE="user", and the build system forces
     *  ro.debuggable=0 for every user variant, so false is the answer consistent with
     *  everything else this class reports. Declared as a compile-time constant and
     *  ABOVE SKU deliberately: SystemProperties' KNOWN block reads this field, and
     *  SKU reads SystemProperties, so a non-constant here would make the two classes'
     *  static initialisers mutually dependent (JLS 12.4.2 would silently hand one of
     *  them a default value). */
    public static final boolean IS_DEBUGGABLE = false;

    public static final String SKU     = SystemProperties.get("ro.boot.hardware.sku", UNKNOWN);
    public static final String ODM_SKU =
            SystemProperties.get("ro.boot.product.hardware.sku", UNKNOWN);

    // AOSP :1630-:1644 -- the getBackportedFixStatus() value domain.
    public static final int BACKPORTED_FIX_STATUS_UNKNOWN = 0;
    public static final int BACKPORTED_FIX_STATUS_FIXED = 1;
    public static final int BACKPORTED_FIX_STATUS_NOT_APPLICABLE = 2;
    public static final int BACKPORTED_FIX_STATUS_NOT_FIXED = 3;

    /** AOSP :1676 -- reads the device's backported-fix alias bitset
     *  (`BackportedFixesProperties.alias_bitset()`, populated from
     *  ro.build.backported_fixes.alias_bitset) and answers FIXED only for a
     *  bit that is set, UNKNOWN otherwise -- including for every id outside
     *  1..1023. Mac.apk ships no backported-fix bitset, which is the same
     *  state as a device whose bitset is empty, so every id is UNKNOWN. */
    public static int getBackportedFixStatus(long id) {
        return BACKPORTED_FIX_STATUS_UNKNOWN;
    }

    /** AOSP :1530 / :1541 -- pure arithmetic on a VERSION_CODES_FULL value
     *  (SDK_INT_MULTIPLIER is 100000, AOSP :1335). Ported verbatim; these do
     *  not report anything about THIS device, they decode a caller-supplied
     *  int, so they are correct independently of what we present as SDK_INT. */
    public static int getMajorSdkVersion(int sdkIntFull) { return sdkIntFull / 100000; }
    public static int getMinorSdkVersion(int sdkIntFull) { return sdkIntFull % 100000; }

    // getSerial(): API 26 replaced the deprecated SERIAL field with a method
    // (gated by READ_PHONE_STATE on a real device). It returns the SAME value as
    // the field -- which since v1.0.938 is Build.UNKNOWN, because Android
    // deliberately withdrew the stable hardware serial and we should not
    // re-supply it under either name.
    public static String getSerial() { return SERIAL; }

    // getRadioVersion(): baseband/radio firmware version. No radio on this host.
    //
    // ⚠ null, NOT the RADIO field. These are two DIFFERENT AOSP contracts and we
    // were conflating them: the deprecated Build.RADIO field falls back to
    // Build.UNKNOWN ("unknown"), but the modern method is
    // `joinListOrElse(TelephonyProperties.baseband_version(), null)` and its own
    // javadoc says "May return null (if, for instance, the radio is not
    // currently on)". A caller that null-checks -- the documented contract --
    // was being handed the literal string "unknown" and had no way to tell it
    // from a real baseband version. No radio is exactly the case the null is for.
    public static String getRadioVersion() { return null; }

    // Build.Partition (API 29): describes a versioned system partition. Apps that
    // call getFingerprintedPartitions() iterate these; we expose the system
    // partition only.
    public static class Partition {
        public static final String PARTITION_NAME_SYSTEM = "system";
        private final String mName;
        private final String mFingerprint;
        private final long mTimeMs;
        Partition(String name, String fingerprint, long timeMs) {
            mName = name; mFingerprint = fingerprint; mTimeMs = timeMs;
        }
        public String getName() { return mName; }
        public String getFingerprint() { return mFingerprint; }
        public long getBuildTimeMillis() { return mTimeMs; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Partition)) return false;
            Partition p = (Partition) o;
            return mTimeMs == p.mTimeMs
                && java.util.Objects.equals(mName, p.mName)
                && java.util.Objects.equals(mFingerprint, p.mFingerprint);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(mName, mFingerprint, mTimeMs);
        }
    }

    public static java.util.List<Partition> getFingerprintedPartitions() {
        java.util.List<Partition> out = new java.util.ArrayList<>();
        out.add(new Partition(Partition.PARTITION_NAME_SYSTEM, FINGERPRINT, TIME));
        return out;
    }

    public static class VERSION {
        public static final String INCREMENTAL = "1";
        // SDK_INT and RELEASE are the presented level (default 29/"10");
        // com.macapk.loader.PresentedSdk is the single source, see the
        // class header above for why the initializer is a method call.
        public static final int    SDK_INT     = com.macapk.loader.PresentedSdk.get();
        public static final String RELEASE     = com.macapk.loader.PresentedSdk.releaseString();
        public static final String CODENAME    = "REL";
        // API 35: SDK_INT_MULTIPLIER(100000) * ro.build.version.sdk + ro.build.version.sdk_minor.
        // We have no minor-release concept (sdk_minor is always 0 on a device
        // with only major releases), so this collapses to exactly SDK_INT * 100000.
        public static final int    SDK_INT_FULL = SDK_INT * 100000;
        // 0 on a released (non-preview) build -- matches a real device. Duolingo's
        // FullStory SDK reads this in a static initializer; a missing field threw
        // NoSuchFieldError and killed the launch.
        public static final int    PREVIEW_SDK_INT = 0;
        public static final int    MEDIA_PERFORMANCE_CLASS = 0;
        @Deprecated
        public static final String SDK         = Integer.toString(SDK_INT);
        // Derived from the same constant as Build.TIME so the two cannot drift
        // apart (we previously claimed a 1970 build with a 2026 patch level).
        public static final String SECURITY_PATCH = MACAPK_BUILD_DATE;
        public static final String[] ACTIVE_CODENAMES = {};
        public static final String BASE_OS     = "";
        // API 30+: RELEASE on a released build, else the dev CODENAME. We are a
        // released build so this equals RELEASE.
        public static final String RELEASE_OR_CODENAME = RELEASE;
        // API 33+: a display string for release-or-preview; same as RELEASE here.
        public static final String RELEASE_OR_PREVIEW_DISPLAY = RELEASE;
    }

    public static class VERSION_CODES {
        public static final int BASE = 1;
        // The pre-CUPCAKE point releases AOSP declares and we were missing.
        // javac INLINES VERSION_CODES constants into app bytecode (they carry a
        // ConstantValue attribute, unlike VERSION.SDK_INT/RELEASE), so a missing
        // field is invisible to compiled callers and matters only to reflection
        // over the field set -- which is exactly what the converted jars that
        // touch this class do (`ldc class android/os/Build$VERSION_CODES`).
        // They want the field PRESENT.
        public static final int BASE_1_1 = 2;
        public static final int CUPCAKE = 3;
        public static final int DONUT = 4;
        public static final int ECLAIR = 5;
        public static final int ECLAIR_0_1 = 6;
        public static final int ECLAIR_MR1 = 7;
        public static final int FROYO = 8;
        public static final int GINGERBREAD = 9;
        public static final int GINGERBREAD_MR1 = 10;
        public static final int HONEYCOMB = 11;
        public static final int HONEYCOMB_MR1 = 12;
        public static final int HONEYCOMB_MR2 = 13;
        public static final int ICE_CREAM_SANDWICH = 14;
        public static final int ICE_CREAM_SANDWICH_MR1 = 15;
        public static final int JELLY_BEAN = 16;
        public static final int JELLY_BEAN_MR1 = 17;
        public static final int JELLY_BEAN_MR2 = 18;
        public static final int KITKAT = 19;
        public static final int KITKAT_WATCH = 20;
        public static final int LOLLIPOP = 21;
        public static final int LOLLIPOP_MR1 = 22;
        public static final int M  = 23;
        public static final int N  = 24;
        public static final int N_MR1 = 25;
        public static final int O  = 26;
        public static final int O_MR1 = 27;
        public static final int P  = 28;
        public static final int Q  = 29;
        public static final int R  = 30;
        public static final int S  = 31;
        public static final int S_V2 = 32;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
        public static final int VANILLA_ICE_CREAM = 35;
        public static final int BAKLAVA = 36;
        // ⚠ NAME NOT AUTHORITATIVELY ESTABLISHED (2026-08-20). The LEVEL is
        // solid -- Aurora Store's binary manifest carries Google's own aapt2
        // output, platformBuildVersionCode="37" platformBuildVersionName="17".
        // The IDENTIFIER is not: no android17-* branch exists on the AOSP
        // mirror and developer.android.com is JS-rendered (reads EMPTY to
        // WebFetch, the same trap as cs.android.com). Google's SDK manifest
        // lists android-37.2-beta1 with codename CinnamonBun, which is a
        // PREVIEW codename rather than a finalized field name. Kept because a
        // present field with a possibly-wrong name serves reflection, while an
        // absent field serves nothing; rename it the moment a released AOSP
        // branch names it.
        public static final int CINNAMON_BUN = 37;
        // Sentinel for "the current in-development release" (AOSP value).
        public static final int CUR_DEVELOPMENT = 10000;
    }

    /** AOSP core/java/android/os/Build.java:1335-1520 (API 36) -- every
     *  VERSION_CODES entry re-expressed on the major.minor scale that
     *  VERSION.SDK_INT_FULL uses, i.e. `code * SDK_INT_MULTIPLIER` with
     *  SDK_INT_MULTIPLIER = 100000 (AOSP :1335, private there). Each value is
     *  derived from VERSION_CODES above rather than transcribed, so the two
     *  tables cannot drift apart.
     *
     *  Values cross-checked against the API-36 signature file: BASE 100000,
     *  BAKLAVA 3600000. NOTE the list deliberately stops at BAKLAVA (36):
     *  VERSION_CODES.CINNAMON_BUN exists here but its NAME is not
     *  authoritatively established (see the note on it above), and AOSP's own
     *  VERSION_CODES_FULL has no entry for it. */
    public static class VERSION_CODES_FULL {
        private static final int SDK_INT_MULTIPLIER = 100000;
        public static final int BASE = VERSION_CODES.BASE * SDK_INT_MULTIPLIER;
        public static final int BASE_1_1 = VERSION_CODES.BASE_1_1 * SDK_INT_MULTIPLIER;
        public static final int CUPCAKE = VERSION_CODES.CUPCAKE * SDK_INT_MULTIPLIER;
        public static final int DONUT = VERSION_CODES.DONUT * SDK_INT_MULTIPLIER;
        public static final int ECLAIR = VERSION_CODES.ECLAIR * SDK_INT_MULTIPLIER;
        public static final int ECLAIR_0_1 = VERSION_CODES.ECLAIR_0_1 * SDK_INT_MULTIPLIER;
        public static final int ECLAIR_MR1 = VERSION_CODES.ECLAIR_MR1 * SDK_INT_MULTIPLIER;
        public static final int FROYO = VERSION_CODES.FROYO * SDK_INT_MULTIPLIER;
        public static final int GINGERBREAD = VERSION_CODES.GINGERBREAD * SDK_INT_MULTIPLIER;
        public static final int GINGERBREAD_MR1 = VERSION_CODES.GINGERBREAD_MR1 * SDK_INT_MULTIPLIER;
        public static final int HONEYCOMB = VERSION_CODES.HONEYCOMB * SDK_INT_MULTIPLIER;
        public static final int HONEYCOMB_MR1 = VERSION_CODES.HONEYCOMB_MR1 * SDK_INT_MULTIPLIER;
        public static final int HONEYCOMB_MR2 = VERSION_CODES.HONEYCOMB_MR2 * SDK_INT_MULTIPLIER;
        public static final int ICE_CREAM_SANDWICH = VERSION_CODES.ICE_CREAM_SANDWICH * SDK_INT_MULTIPLIER;
        public static final int ICE_CREAM_SANDWICH_MR1 = VERSION_CODES.ICE_CREAM_SANDWICH_MR1 * SDK_INT_MULTIPLIER;
        public static final int JELLY_BEAN = VERSION_CODES.JELLY_BEAN * SDK_INT_MULTIPLIER;
        public static final int JELLY_BEAN_MR1 = VERSION_CODES.JELLY_BEAN_MR1 * SDK_INT_MULTIPLIER;
        public static final int JELLY_BEAN_MR2 = VERSION_CODES.JELLY_BEAN_MR2 * SDK_INT_MULTIPLIER;
        public static final int KITKAT = VERSION_CODES.KITKAT * SDK_INT_MULTIPLIER;
        public static final int KITKAT_WATCH = VERSION_CODES.KITKAT_WATCH * SDK_INT_MULTIPLIER;
        public static final int LOLLIPOP = VERSION_CODES.LOLLIPOP * SDK_INT_MULTIPLIER;
        public static final int LOLLIPOP_MR1 = VERSION_CODES.LOLLIPOP_MR1 * SDK_INT_MULTIPLIER;
        public static final int M = VERSION_CODES.M * SDK_INT_MULTIPLIER;
        public static final int N = VERSION_CODES.N * SDK_INT_MULTIPLIER;
        public static final int N_MR1 = VERSION_CODES.N_MR1 * SDK_INT_MULTIPLIER;
        public static final int O = VERSION_CODES.O * SDK_INT_MULTIPLIER;
        public static final int O_MR1 = VERSION_CODES.O_MR1 * SDK_INT_MULTIPLIER;
        public static final int P = VERSION_CODES.P * SDK_INT_MULTIPLIER;
        public static final int Q = VERSION_CODES.Q * SDK_INT_MULTIPLIER;
        public static final int R = VERSION_CODES.R * SDK_INT_MULTIPLIER;
        public static final int S = VERSION_CODES.S * SDK_INT_MULTIPLIER;
        public static final int S_V2 = VERSION_CODES.S_V2 * SDK_INT_MULTIPLIER;
        public static final int TIRAMISU = VERSION_CODES.TIRAMISU * SDK_INT_MULTIPLIER;
        public static final int UPSIDE_DOWN_CAKE = VERSION_CODES.UPSIDE_DOWN_CAKE * SDK_INT_MULTIPLIER;
        public static final int VANILLA_ICE_CREAM = VERSION_CODES.VANILLA_ICE_CREAM * SDK_INT_MULTIPLIER;
        public static final int BAKLAVA = VERSION_CODES.BAKLAVA * SDK_INT_MULTIPLIER;
    }
}
