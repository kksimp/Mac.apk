// com.macapk.loader.PresentedSdk -- the ONE process-wide answer to "what
// Android version is this device?".
//
// WHY THIS EXISTS (2026-08-08). The runtime used to present FIVE API levels
// at once: Build.SDK_INT said 29, ResourceIndex's CONFIG_DIRS force-served
// values-v31/v34, MACAPK_ARSC_RES_SDK scored qualifiers at 34, apktool's
// framework table was API 36, and the answer-probe golden AVD is API 30.
// Each consumer had papered over the SDK_INT=29 contradiction separately.
// This class is the single source every consumer now derives from:
// Build.VERSION.SDK_INT/RELEASE, SystemProperties ro.build.version.*,
// ResourceIndex's -v<N> qualifier acceptance, and CONFIG_DIRS filtering.
//
// THE PER-APP MODEL (owner call, 2026-08-08: "see what versions of android
// the app supports at runtime and always tell them we are the highest one
// they support"). Real Android's SDK_INT is a device constant, and the
// platform varies BEHAVIOUR per app through targetSdkVersion compat gates
// (hundreds of CompatChanges). Mac.apk runs ONE app per process, so it can
// do something a multi-app device cannot: pick the DEVICE LEVEL per
// process to match the app. From inside the process this is coherent --
// the app observes one level everywhere, exactly as if it were installed
// on a real device of that level -- and it puts every app on the level its
// developers actually tested: their own targetSdk.
//
//   presented = max(clamp(targetSdkVersion, FLOOR, CEILING), minSdkVersion)
//
// - minSdkVersion wins over both bounds (Win.apk, 2026-11-06): a device below
//   it would never have installed the app. Play services 26.x declares 31;
//   presented 29 it refused to start its services.
// - targetSdkVersion is NOT "the highest level the app supports" (apps
//   forward-support every future level; the manifest has no max field).
//   It is the behaviour generation the app was DESIGNED against, which is
//   the level whose branches its developers ran. That is the faithful
//   reading of the owner's intent.
// - FLOOR = 29: an app targeting below 29 already runs correctly today
//   being told 29 (the ARM32 fleet: flappy targetSdk 15, hillclimb 28 --
//   both sweep-green), and that is also what the API-30 oracle does to
//   them (old apps on a NEWER device is Android's most-tested compat
//   direction). Reporting an ANCIENT level instead would steer them onto
//   2012-era code paths nothing has tested in a decade, and would drop
//   their -v21+ resource variants. Never lie DOWNWARD past today's value.
// - CEILING = 36 (raised from 34, 2026-08-21). The block here used to say 34
//   was "the level our framework can currently stand behind" and that "35 is out
//   until the edge-to-edge enforcement surface exists: AOSP 15 FORCES
//   edge-to-edge for targetSdk-35 apps, and our window model is the OPPOSITE of
//   what that needs". **That reasoning is REFUTED and the ceiling it justified
//   was protecting nothing.**
//
//   Measured, three ways:
//   * `MACAPK_SDK_INT` forced on EVERY app regardless of targetSdk -- the
//     one-level model -- gives a protected sweep of PASS=11 REGRESS=0 at 35, at
//     36 AND at 37. flappy (targetSdk 15) and hcr181 (targetSdk 16, ARM32) both
//     PASS while being told they are on API 37, verified from their own logs
//     (`presenting API 37`) rather than from the env we set.
//   * The Canvas fleet at forced-36 against the per-app clamp, same build: 12 of
//     16 apps >= 99.3% identical, including every app that ships `-v35`
//     resources (deskclock, anki, wikipedia, opensudoku all 99.98%). The three
//     movers are the fleet's known content-drifty apps, each inside its own
//     documented range.
//   * Grepped at v1.0.1012: `sDecorFitsSystemWindows` is pinned true at every
//     presented level, nothing in `runtime/` matches
//     `isEdgeToEdgeEnforced|windowOptOutEdgeToEdge`, and NOT ONE of the four
//     real `PresentedSdk` readers is a comparison. A ceiling lift cannot change
//     one line of our window arithmetic, so the window model was never the gate.
//     (UPDATED 2026-09-26, wave 5 w5-windecor: that grep is no longer empty.
//     The FIRST PresentedSdk comparison is android.app.Activity
//     .macapkIsEdgeToEdgeEnforced -- android15 PhoneWindow's enforcement for a
//     targetSdk>=35 app on a presented 35+ device, which makes the bar-colour
//     GETTERS answer TRANSPARENT and setStatusBarColor a no-op; bisect with
//     MACAPK_EDGE_TO_EDGE=0 / MACAPK_WINDOW_BAR_COLORS=0. It changes answers,
//     not window arithmetic: sDecorFitsSystemWindows is still pinned true and
//     the E2E LAYOUT half is not implemented.
//     docs/window-decor-title-barcolours-styleable_2026-09-26.md)
//
//   ⚠ WHAT THE SWEEP COULD NOT SEE, stated because it is the honest half: the
//   12 protected apps top out at `-v31` (mindustry/bouncy `-v26`, subwaysurf
//   `-v31`), so a green sweep at 35/36/37 is evidence about the CODE half only.
//   The resource half lives in the Canvas fleet, which is why both were run.
//
//   ⚠ AND THE REFERENCE IS NOT LEVEL-MATCHED: the golden AVD is pinned to
//   `system-images;android-30` (android-emulator/setup.sh:63-64), i.e. Android
//   11. We already serve v31/v34 fleet-wide, so we are ALREADY ahead of it; at
//   36 we serve v35 too. Where that diverges we are MORE correct and will score
//   WORSE, and no instrument we own separates that from a regression. Per the
//   alpha-software rule that is a cost to notate, not a reason to hold.
//
//   37 is deliberately NOT taken. No `android17-release` branch exists on the
//   AOSP mirror, so every future question about it would be documentation-only,
//   and exactly one corpus app targets it. `MAX_KNOWN_SDK` is already 37 and
//   `MACAPK_SDK_INT=37` already works, so this is a one-line revisit the day the
//   branch cuts.
//
//   Override without rebuilding: MACAPK_SDK_CEILING=<n>, clamped to
//   [FLOOR, MAX_KNOWN_SDK].
//
// GATES. MACAPK_SDK_INT is default-unset; MACAPK_SDK_FROM_TARGET is DEFAULT-ON
// since v1.0.1012 (set it to 0 to restore the flat DEFAULT_SDK in one run).
// (This block used to claim MACAPK_SDK_FROM_TARGET was "DEFAULT-ON since
// v1.0.1004". That was never true of the code below it -- the branch tests
// `"1".equals(getenv(...))`, so an unset var presents 29 -- and the claim
// survived the same evening's revert, contradicting the comment 30 lines down
// that records it. Corrected 2026-08-20; a stale gate default in this file is
// expensive because every consumer derives its level from here.)
//   MACAPK_SDK_INT=<n>        absolute override, wins over everything.
//   MACAPK_SDK_FROM_TARGET=0  DISABLES the per-app clamp and restores the flat
//                             DEFAULT_SDK. Anything else (unset included) keeps
//                             the clamp ON. It needs the APK path; it reads
//                             MACAPK_APK_PATH (exported by the native launcher;
//                             see below), else stays inert with one loud log line.
//
// WHY AN ENV VAR AND NOT A Boot-time SETTER: Build.VERSION.SDK_INT must be
// a static final int, so its value is fixed at Build.VERSION class-init,
// and ANY framework class's <clinit> may touch Build.VERSION before Boot
// reaches its manifest parse (SystemProperties does, ResourceIndex does).
// Environment variables are process-constant from birth, so reading them
// here is safe no matter which class loads first. The APK path therefore
// also has to arrive via env; until the native side exports it, a manual
// run passes it by hand:
//   MACAPK_SDK_FROM_TARGET=1 MACAPK_APK_PATH=app.apk macapk-runtime --apk app.apk
package com.macapk.loader;

public final class PresentedSdk {
    private PresentedSdk() {}

    /** Today's default presentation, unchanged since the project began. */
    private static final int DEFAULT_SDK = 29;
    /** Per-app clamp bounds; see the header for the evidence behind each. */
    private static final int FLOOR   = 29;
    /** The newest level we present. Overridable at runtime by
     *  MACAPK_SDK_CEILING, clamped to [FLOOR, MAX_KNOWN_SDK].
     *
     *  WHY AN OVERRIDE (2026-08-21): raising the ceiling is a one-constant edit
     *  whose EVIDENCE costs a build plus a 15-minute sweep plus a 15-minute
     *  fleet recap, per candidate value. Making it an env var turns 34/35/36/37
     *  into a one-build A/B and makes every step of the ceiling campaign
     *  reversible in a single run rather than a rebuild. It cannot change any
     *  behaviour when unset, which is what makes it safe to land ahead of the
     *  raise it exists to measure. */
    private static final int CEILING = ceilingFromEnv(36);

    private static int ceilingFromEnv(int compiled) {
        try {
            String v = System.getenv("MACAPK_SDK_CEILING");
            if (v == null || v.trim().isEmpty()) return compiled;
            int n = Integer.parseInt(v.trim());
            if (n < FLOOR) n = FLOOR;
            if (n > MAX_KNOWN_SDK) n = MAX_KNOWN_SDK;
            return n;
        } catch (Throwable t) {
            return compiled;   // a bad value must never change the presentation
        }
    }

    /** The newest API level this build can NAME a release string for.
     *  MUST equal the highest `case` in releaseString(int). */
    static final int MAX_KNOWN_SDK = 37;
    /** AOSP's own bounds: VERSION_CODES.BASE (1) .. CUR_DEVELOPMENT (10000,
     *  the preview sentinel). Used to sanity-check MACAPK_SDK_INT; the level
     *  we will actually PRESENT is additionally capped at MAX_KNOWN_SDK. */
    private static final int ABS_MIN_SDK = 1;
    private static final int ABS_MAX_SDK = 10000;

    private static final int PRESENTED;
    private static final boolean EXPLICIT;
    /** Which gate chose the level. Reported in the one-line banner below so a
     *  log says not just WHAT we presented but WHY -- an env var that failed to
     *  parse, and a manifest we could not read, both fall back to the same 29 a
     *  default run presents, and those three states are indistinguishable
     *  otherwise. */
    private static final String SOURCE;

    static {
        int sdk = DEFAULT_SDK;
        boolean explicit = false;
        String source = "default";
        try {
            String forced = System.getenv("MACAPK_SDK_INT");
            if (forced != null && !forced.isEmpty()) {
                try {
                    int v = Integer.parseInt(forced.trim());
                    // AOSP's own bounds, not ours. The old test was [21,36],
                    // which SILENTLY presented 29 for anything outside it -- so
                    // MACAPK_SDK_INT=16 and =37 both reported "no change",
                    // a false no-difference at BOTH ends of the very campaign
                    // this override exists to run. Measured 2026-08-20 with
                    // debug/levelprobe.sh, which reads the level back out of
                    // Build and flagged the skew.
                    if (v >= ABS_MIN_SDK && v <= ABS_MAX_SDK) {
                        // Cap at what we can NAME. Presenting a level whose
                        // release string we cannot source would put a
                        // fabricated Android into Build.FINGERPRINT, which
                        // crash reporters and WebView UA strings PARSE.
                        sdk = Math.min(v, MAX_KNOWN_SDK);
                        if (sdk != v) {
                            logw("MACAPK_SDK_INT=" + v + " is above MAX_KNOWN_SDK="
                                + MAX_KNOWN_SDK + "; presenting " + sdk);
                        }
                        explicit = true;
                        source = "MACAPK_SDK_INT";
                    } else {
                        logw("MACAPK_SDK_INT=" + forced + " outside ["
                            + ABS_MIN_SDK + "," + ABS_MAX_SDK
                            + "]; presenting default " + DEFAULT_SDK);
                    }
                } catch (NumberFormatException nfe) {
                    logw("MACAPK_SDK_INT="
                        + forced + " unparseable; presenting default " + DEFAULT_SDK);
                }
            } else if (!"0".equals(System.getenv("MACAPK_SDK_FROM_TARGET"))) {
                // ⚠ DEFAULT-ON since v1.0.1012, on measurement rather than
                // on optimism. KILL SWITCH: MACAPK_SDK_FROM_TARGET=0 restores
                // the flat DEFAULT_SDK in one run.
                //
                // The 2026-08-20 revert was undiagnosed. Two causes were then
                // found and fixed (values-night invisible to colours/styles;
                // system_* not resolving by integer id), and a THIRD, which was
                // the one that mattered: android.window.OnBackInvokedDispatcher
                // was declared a CLASS where AOSP declares an INTERFACE, so at
                // SDK >= 33 androidx.activity's Api33Impl hit
                // IncompatibleClassChangeError, lifecycle delivery died and the
                // app rendered NOTHING. unciv went RICH/26,667 colours at 31 to
                // BLANK/distinct=1 at 33, 34 and 35 alike.
                //
                // Measured with the flag ON after that fix:
                //   protected sweep      PASS=11 REGRESS=0  (was PASS=8 REGRESS=3)
                //   fleet, flag-off vs flag-on, same build:
                //     12 of 16 apps >= 99.9% identical
                //     mastodon 10.14% -> 99.98%
                //   the four movers, against a same-build churn control:
                //     anuto 88.78% vs 88.42% churn -> churn
                //     sfsolitaire 99.26% vs 99.26% churn -> churn
                //     newpipe 93.16% vs 98.86% churn -> flat vs emulator (78.19 -> 78.02)
                //     bouncy DETERMINISTIC both ways (100.00 / 99.99) and 92.94%
                //       between them -- a REAL change, and it moves TOWARD the
                //       emulator: 65.53% -> 66.89%
                //
                // ⚠ RESIDUAL RISK, stated not hidden: sweep + fleet exercise
                // about 28 of the 62 corpus apps, and the CODE half of this flip
                // touches every `if (SDK_INT >= N)` in every app and its bundled
                // AndroidX. A green result narrows the risk; it does not remove
                // it. The other changes in this campaign each got a dedicated
                // instrument (nightaudit, propgate, cam-gate); this one has no
                // equivalent and no claim to one.
                //
                // Historical note on the original revert: The original revert was undiagnosed; that cause
                // (values-night invisible to colours/styles, plus system_* not
                // resolving by integer id) is fixed and verified. But running
                // the protected sweep and the fleet WITH this flag on shows it
                // is still not safe: PASS=8 REGRESS=3 against PASS=11 REGRESS=0
                // on the same build. unciv 26,726 -> 296 colours, subway
                // 84,006 -> 341, mastodon's whole content area from its brand
                // green to #fefbff with an INK IMBALANCE over 1.3M px.
                //
                // EVERY app that broke targets 35 and is presented 34; apps
                // targeting 34 EXACTLY are all fine, and the three span libGDX,
                // Unity and views/AndroidX, so it is not a Compose or Material
                // problem. The premise of this clamp is "present the level the
                // app was DESIGNED against", and for a 35-targeting app 34 is
                // not that level -- it is one we invented. The flat DEFAULT_SDK
                // is at least Android's most-tested compat direction. Evaluate
                // clamping only when targetSdk <= CEILING, and leaving apps
                // above the ceiling at DEFAULT_SDK, before flipping again.
                //
                // Historical note on the original revert: Presenting each app its own targetSdk (clamped
                // [29,34]) rendered Aurora Store ALL WHITE -- owner-observed,
                // immediately after the flip, on an otherwise-working build.
                // Not diagnosed: the flip changes SDK_INT for nearly every app
                // AND flips isExplicit(), which switches the CONFIG_DIRS
                // force-serve and MACAPK_ARSC_RES_SDK carve-outs from a pinned
                // 34 to level-derived behaviour -- so it moves resource
                // selection and code branches at once. That coupling is
                // exactly why it needs a per-level campaign rather than a
                // default flip. Opt in with MACAPK_SDK_FROM_TARGET=1.
                //
                // The owner still wants this: "we are
                // supposed to use the highest the app supports by default."
                // Presenting a flat 29 to every app tells a modern app it is on
                // Android 10 and steers it onto compat paths it does not ship
                // for -- and drops its own -v31/-v34 resource variants, which we
                // already serve fleet-wide. The clamp bounds below are unchanged
                // and are what keeps this honest: never below FLOOR (an ancient
                // level would steer old apps onto 2012-era code nothing has
                // tested), never above CEILING (the level our framework can
                // actually stand behind -- 35 forces edge-to-edge and our
                // WindowInsets dispatch is deliberately absent).
                // Kill switch: MACAPK_SDK_FROM_TARGET=0 restores the flat 29.
                int[] sdks = readSdks();
                int target = sdks[0];
                if (target > 0) {
                    sdk = presentedFor(target, sdks[1], CEILING);
                    explicit = true;
                    source = "MACAPK_SDK_FROM_TARGET(targetSdk=" + target
                        + (sdks[1] > 0 ? ", minSdk=" + sdks[1] : "") + ")";
                    logi("per-app presentation: "
                        + "targetSdk=" + target + " minSdk=" + sdks[1] + " -> presenting " + sdk
                        + " (clamp [" + FLOOR + "," + CEILING + "], never below minSdk)");
                }
                // readSdks already logged why it could not answer.
            } else {
                // The flat default, but never below the app's minSdkVersion:
                // a device below it would not have installed the app, and an
                // app told so takes branches its developers removed (Play
                // services 26.x requires 31 and refused to run on 29).
                int min = System.getenv("MACAPK_APK_PATH") != null ? readSdks()[1] : 0;
                if (min > sdk) {
                    sdk = Math.min(min, MAX_KNOWN_SDK);
                    source = "default(minSdk=" + min + ")";
                }
            }
        } catch (Throwable t) {
            // Presentation must never take the process down; fall back to
            // the historical constant.
            sdk = DEFAULT_SDK;
            explicit = false;
            source = "default(threw)";
        }
        PRESENTED = sdk;
        EXPLICIT = explicit;
        SOURCE = source;
        // ONE UNCONDITIONAL LINE, EVERY RUN (2026-08-20). Until now nothing
        // logged the presented level unless a gate had something to complain
        // about, so a run's log could not answer "which API level was this?" --
        // and the whole API-level campaign is a sequence of A/Bs over exactly
        // that value. Worse, every failure path here falls back to 29 SILENTLY,
        // so a cell that meant to present 34 but could not read the manifest
        // looked identical to the default cell it was being compared against.
        // A harness must be able to read the level back OUT of the log rather
        // than trust the env it set (debug/sdklevel_ab.sh does exactly that).
        //
        // Emitted from the static initializer, which is the earliest moment the
        // value exists and is deliberately before any consumer derives from it.
        // logi() is infallible by contract (it falls back to stderr when the
        // native sink is not up yet), which matters here because Build.VERSION's
        // own <clinit> can be what triggers this class.
        logi("presenting API " + PRESENTED + " (Android " + releaseString()
            + "), explicit=" + EXPLICIT + ", source=" + SOURCE
            + " [default=" + DEFAULT_SDK + " clamp=" + FLOOR + ".." + CEILING + "]");
    }

    /** The API level this process presents everywhere. Process-constant. */
    public static int get() { return PRESENTED; }

    /** True when a gate CHOSE the level (even if it chose 29). Consumers
     *  that keep legacy carve-outs alive for the incoherent default (the
     *  CONFIG_DIRS force-serve, MACAPK_ARSC_RES_SDK) switch to coherent
     *  level-derived behaviour when this is true. */
    public static boolean isExplicit() { return EXPLICIT; }

    /** Build.VERSION.RELEASE for the presented level. Every consumer that
     *  derives a version STRING (fingerprint included) must use this so the
     *  numbers can never disagree. */
    public static String releaseString() { return releaseString(PRESENTED); }

    /** Package-visible so the offline 1..37 gate driver can exercise every row
     *  without constructing a process per level. */
    static String releaseString(int level) {
        switch (level) {
            case 1:  return "1.0";
            case 2:  return "1.1";
            case 3:  return "1.5";
            case 4:  return "1.6";
            case 5:  return "2.0";
            case 6:  return "2.0.1";
            case 7:  return "2.1";
            case 8:  return "2.2";
            case 9:  return "2.3";
            case 10: return "2.3.3";
            case 11: return "3.0";
            case 12: return "3.1";
            case 13: return "3.2";
            case 14: return "4.0";
            // CONFLICT, surfaced rather than resolved silently: the
            // android14-release mirror renders this javadoc line as
            // "Android 4.03" and android11-release as "Android 4.0.3".
            // 4.03 is not a string any device ever reported, so the older
            // rendering wins.
            case 15: return "4.0.3";
            case 16: return "4.1";
            case 17: return "4.2";
            case 18: return "4.3";
            case 19: return "4.4";
            case 20: return "4.4W";
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6.0";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0.0";
            case 27: return "8.1.0";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12";   // S_V2 (12L) still reports RELEASE "12"
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            case 36: return "16";
            // Sourced from Google's OWN aapt2 output, not from a guess: the
            // Aurora Store APK's binary manifest carries
            // platformBuildVersionCode="37" platformBuildVersionName="17"
            // and android:compileSdkVersionCodename="17".
            case 37: return "17";
            default:
                // NEVER return the level as a string, which is what this used
                // to do for everything outside 21..36. Build.FINGERPRINT
                // concatenates RELEASE, and crash reporters, analytics SDKs and
                // WebView UA strings PARSE it -- a bare "8" is not a
                // wrong-but-harmless value, it is one that parses as a
                // NONEXISTENT Android. Fall back to the newest level we can
                // actually name, and say so loudly.
                logw("no release string for API " + level
                     + "; reporting Android " + releaseString(MAX_KNOWN_SDK)
                     + " (the newest level this build can name)");
                return releaseString(MAX_KNOWN_SDK);
        }
    }

    /** Log via the runtime sink; fall back to stderr when the native lib
     *  is absent (offline drivers). A log failure must never disturb the
     *  computed presentation, so callers treat these as infallible. */
    private static void logw(String msg) {
        try { NativeLog.w("Mac.apk.PresentedSdk", msg); }
        catch (Throwable t) { System.err.println("[W] Mac.apk.PresentedSdk: " + msg); }
    }
    private static void logi(String msg) {
        try { NativeLog.i("Mac.apk.PresentedSdk", msg); }
        catch (Throwable t) { System.err.println("[I] Mac.apk.PresentedSdk: " + msg); }
    }

    /** The app's declared targetSdkVersion, from the BINARY AndroidManifest
     *  inside the APK at MACAPK_APK_PATH. The apktool-decoded text manifest
     *  is NOT usable here: apktool strips <uses-sdk> into apktool.yml
     *  (verified across the decoded corpus: zero uses-sdk elements), which
     *  our pipeline does not keep, so the binary manifest is the only
     *  reliable source. AOSP PackageParser defaulting is mirrored:
     *  targetSdkVersion absent -> minSdkVersion -> 1. Returns 0 when the
     *  gate cannot answer (no path, no zip entry, parse failure). */
    private static int[] readSdks() {
        String apk = System.getenv("MACAPK_APK_PATH");
        if (apk == null || apk.isEmpty()) {
            logw("MACAPK_SDK_FROM_TARGET=1 but "
                + "MACAPK_APK_PATH is unset; presenting default " + DEFAULT_SDK);
            return new int[2];
        }
        return readSdksFromApk(apk);
    }

    /** The level presented to an app with this targetSdkVersion and
     *  minSdkVersion (0 = not declared): its target clamped to [FLOOR,
     *  ceiling], and never below its minSdk, which wins over the ceiling (an
     *  app needing a level above it would not install on a device below it),
     *  up to MAX_KNOWN_SDK. Package-visible for the unit test. */
    static int presentedFor(int target, int min, int ceiling) {
        int sdk = Math.max(FLOOR, Math.min(ceiling, target));
        if (min > sdk) sdk = Math.min(min, MAX_KNOWN_SDK);
        return sdk;
    }

    /** Public because Manifest.java needs the same read: the decoded text
     *  manifest it parses today has NO uses-sdk element (apktool strips it),
     *  so Manifest.targetSdk silently degrades to 1 for every app, and
     *  Boot's targetSdk-gated rules (resizeableActivity >= 24, the pre-O
     *  aspect cap) all run on 1. This helper is the cure; wiring it into
     *  Manifest belongs to that file's owner. Returns 0 when unreadable. */
    public static int readTargetSdkFromApk(String apk) {
        return readSdksFromApk(apk)[0];
    }

    /** {targetSdkVersion, minSdkVersion} from the APK's binary manifest, with
     *  readTargetSdkFromApk's defaulting for the target; minSdk is 0 when the
     *  manifest declares none, and both are 0 when it cannot be read. */
    static int[] readSdksFromApk(String apk) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry e = zf.getEntry("AndroidManifest.xml");
            byte[] axml;
            if (e != null) {
                try (java.io.InputStream in = zf.getInputStream(e)) {
                    axml = in.readAllBytes();
                }
            } else {
                // No top-level AndroidManifest.xml is the NORMAL shape of a
                // multi-part bundle (.apkm / .xapk / .apks), whose root holds
                // base.apk plus config splits -- 11 of the 62-app local corpus,
                // several of them in the protected sweep. Returning 0 here made
                // the caller present DEFAULT_SDK (29) while
                // ApplicationInfo.targetSdkVersion, which parses the same
                // bundle through Manifest.parse, reported the app's real level.
                // One app, two answers: every targetSdk-gated AOSP behaviour
                // (Build.VERSION.SDK_INT, the pre-O aspect cap, resizeability,
                // the scoped-storage and notification-permission gates) then
                // disagreed with the app's own ApplicationInfo.
                //
                // Manifest.binaryManifestFromBundle is the SAME base-member
                // selector Manifest.parse uses, deliberately shared rather than
                // re-implemented, so the two cannot drift apart.
                axml = Manifest.binaryManifestFromBundle(zf);
                if (axml == null) {
                    logw("no AndroidManifest.xml in "
                        + apk + "; presenting default " + DEFAULT_SDK);
                    return new int[2];
                }
            }
            org.w3c.dom.Document doc = BinaryXmlParser.parse(axml);
            org.w3c.dom.NodeList sdks = doc.getElementsByTagName("uses-sdk");
            int target = 0, min = 0;
            if (sdks.getLength() > 0) {
                org.w3c.dom.Element sdk = (org.w3c.dom.Element) sdks.item(0);
                target = parseIntAttr(sdk, "targetSdkVersion");
                min    = parseIntAttr(sdk, "minSdkVersion");
            }
            if (target == 0) target = min > 0 ? min : 1;
            return new int[] { target, min };
        } catch (Throwable t) {
            logw("binary manifest read failed for "
                + apk + " (" + t + "); presenting default " + DEFAULT_SDK);
            return new int[2];
        }
    }

    private static int parseIntAttr(org.w3c.dom.Element el, String name) {
        String v = el.getAttributeNS(
            "http://schemas.android.com/apk/res/android", name);
        if (v == null || v.isEmpty()) return 0;
        try {
            // BinaryXmlParser renders int-typed attributes as decimal text.
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }
}
