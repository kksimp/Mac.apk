package android.view;
import android.graphics.Point;
import android.util.DisplayMetrics;
import com.macapk.loader.NativeCocoa;
public class Display {
    /** AOSP Display.FLAG_* (android-11.0.0_r48 core/java/android/view/Display.java).
     *  Absent here, which stopped the window battery compiling at all. Mac.apk's
     *  single display is neither private nor round, so the flags are declared for
     *  the API surface and Display.getFlags() does not set them. */
    public static final int FLAG_PRIVATE = 1 << 2;
    public static final int FLAG_ROUND   = 1 << 4;

    public static final int DEFAULT_DISPLAY = 0;
    public Display() {}
    public int getDisplayId()           { return DEFAULT_DISPLAY; }
    /** AOSP names the built-in display from a FRAMEWORK STRING, not from the
     *  product: DisplayManagerService's LogicalDisplay fills DisplayInfo.name
     *  for the default display with
     *  com.android.internal.R.string.display_manager_built_in_display_name,
     *  which is "Built-in Screen" (core/res/res/values/strings.xml:4221,
     *  android-11.0.0_r48). So this is not device identity we would be forging
     *  -- every stock Android device answers the same string here, and the
     *  reference capture does too. Reporting a product name instead is simply
     *  a wrong answer to a framework question. */
    public String getName()             { return "Built-in Screen"; }
    /** AOSP: rotation relative to natural orientation. Mac.apk tracks
     *  orientation as landscape-vs-portrait only (cocoa_bridge.mm's
     *  g_phone_w/g_phone_h swap on CocoaApplyRequestedOrientation / the
     *  Cmd+R shortcut), not a true 4-way physical rotation, so this is the
     *  most faithful answer obtainable without new native state: a
     *  landscape-shaped canvas reports ROTATION_90 (we cannot distinguish
     *  it from ROTATION_270, nor ROTATION_0 from ROTATION_180). Boot.java
     *  uses the identical width>height test for Configuration.orientation. */
    public int getRotation() {
        return macapkRotation();
    }
    /** getRotation()'s answer without a Display instance, for code that must
     *  agree with it exactly (CameraBridge.getSensorOrientation cancels it). */
    public static int macapkRotation() {
        // v1.0.1637: the COMMITTED display (see macapkRealWidthPx), so this can
        // never disagree with Configuration.orientation mid-change.
        return (macapkRealWidthPx() > macapkRealHeightPx())
            ? Surface.ROTATION_90 : Surface.ROTATION_0;
    }
    /** AOSP (deprecated): "use getRotation()". Delegates. */
    public int getOrientation()         { return getRotation(); }
    /** AOSP: size of the display in pixels. NativeCocoa.phoneWidth/Height()
     *  return the logical (points) size; Boot.java's DisplayMetrics uses the
     *  same *2 retina conversion for widthPixels/heightPixels, so mirror it
     *  here instead of the stale 720x1280 default. */
    // ================================================================
    // ⚠ v1.0.939: the APP size and the REAL size are DIFFERENT.
    // ================================================================
    //
    // These four methods used to be one value: getRealSize literally called
    // getSize and getRealMetrics literally called getMetrics. AOSP distinguishes
    // them, and the distinction is the whole point of the pair:
    //   getRealSize/getRealMetrics -> the PHYSICAL display, decor included
    //   getSize/getMetrics         -> the area an app may actually use
    // (Display.java javadoc: getSize is "the size of the display, in pixels...
    // excludes... the window decorations"; getRealSize is "the real size of the
    // display without subtracting any window decor".)
    //
    // The justification in Boot.java for collapsing them -- "we present no
    // status or navigation bar, so there is no decor inset to subtract" -- has
    // been false since v1.0.867: StatusBarDriver DRAWS a 24dp status bar and a
    // 48dp three-button nav strip, and since v1.0.865 ViewCanvasDriver already
    // sizes rootView to EXCLUDE the nav strip. So the view layer was correct and
    // only the REPORTED metrics lied; this change is reporting-only and cannot
    // double-subtract, because nothing in the layout path derives its insets
    // from these methods.
    //
    // Measured against the golden: reference realSize 808x1796 / app 808x1700,
    // i.e. exactly one 96px nav strip apart -- the same 48dp
    // navigation_bar_height StatusBarDriver already measured off that AVD.
    //
    // ⚠ COUPLED TO THE CAPTURE GEOMETRY. The probe used to run at a 1700px
    // surface and report 1700 for both, which matched the reference's APP height
    // by accident -- two errors cancelling. Subtracting decor without also
    // running at the real 1796 makes the app height 1604 and parity WORSE.
    // debug/fwprobe/cycle.sh moves to --height 898 in the same change.
    //
    // Gate: MACAPK_DISPLAY_DECOR=0 collapses the ladder again (real == app),
    // which is the one-command A/B for any fleet movement this causes.
    private static final boolean MACAPK_DISPLAY_DECOR =
        !"0".equals(System.getenv("MACAPK_DISPLAY_DECOR"));

    /** The PHYSICAL surface, decor included.
     *  v1.0.1637: the COMMITTED display size (com.macapk.loader.DisplayConfig),
     *  not the native canvas globals. Those are the BOOT canvas: a window drag
     *  never writes them (only Cmd+R / setRequestedOrientation do, and they
     *  change them before the window has actually moved), so reading them live
     *  answered the boot size forever after a resize while the app was drawing
     *  into something else. The committed size moves exactly when the render
     *  surface does, on the Android main thread, together with the
     *  DisplayMetrics and Configuration that describe it. */
    public static int macapkRealWidthPx()  { return com.macapk.loader.DisplayConfig.realWidthPx(); }
    public static int macapkRealHeightPx() { return com.macapk.loader.DisplayConfig.realHeightPx(); }
    /** The bottom system-navigation strip AOSP subtracts from the app area. 0
     *  when the app opted into edge-to-edge, matching what ViewCanvasDriver
     *  already does when sizing rootView -- the two must not disagree. */
    public static int macapkNavDecorPx() {
        if (!MACAPK_DISPLAY_DECOR) return 0;
        try {
            if (!android.view.Window.macapkDecorFitsSystemWindows()) return 0;
            return com.macapk.loader.StatusBarDriver.navBarInsetPx(null);
        } catch (Throwable t) { return 0; }
    }
    /** The navigation decor AOSP subtracts from the app WIDTH -- non-zero only
     *  when the bar has moved to a side edge.
     *
     *  AOSP DisplayPolicy.getNonDecorDisplayWidth (android11-release :2977-2989,
     *  fetched 2026-08-19):
     *      if (hasNavigationBar()) {
     *          final int navBarPosition = navigationBarPosition(fullWidth, fullHeight, rotation);
     *          if (navBarPosition == NAV_BAR_LEFT || navBarPosition == NAV_BAR_RIGHT) {
     *              width -= getNavigationBarWidth(rotation, uiMode);
     *          }
     *      }
     *  ...and getNonDecorDisplayHeight (:3023-3037) subtracts only for
     *  NAV_BAR_BOTTOM. We took it off the HEIGHT unconditionally, so in
     *  landscape every app that sizes itself from Display.getHeight() got a
     *  short answer and a wide one. */
    public static int macapkNavDecorWidthPx() {
        if (!MACAPK_DISPLAY_DECOR) return 0;
        try {
            return com.macapk.loader.StatusBarDriver.contentInsetLeftPx(null)
                 + com.macapk.loader.StatusBarDriver.contentInsetRightPx(null);
        } catch (Throwable t) { return 0; }
    }
    /** The navigation decor AOSP subtracts from the app HEIGHT -- non-zero only
     *  while the bar is at the bottom. See macapkNavDecorWidthPx. */
    public static int macapkNavDecorHeightPx() {
        if (!MACAPK_DISPLAY_DECOR) return 0;
        try {
            return com.macapk.loader.StatusBarDriver.contentInsetBottomPx(null);
        } catch (Throwable t) { return 0; }
    }
    /** The top status bar. Excluded from Configuration.screenHeightDp (AOSP
     *  subtracts BOTH bars there) but NOT from getSize/getMetrics, which on
     *  AOSP subtract only the navigation decor. */
    public static int macapkStatusDecorPx() {
        if (!MACAPK_DISPLAY_DECOR) return 0;
        // heightPxRaw, not heightPx: this is a DISPLAY property. AOSP's
        // DisplayContent.computeScreenConfiguration and
        // computeSizeRangesAndScreenLayout subtract the status bar height for
        // every rotation regardless of what the foreground window's flags say,
        // so a fullscreen activity must not shrink screenHeightDp.
        try { return com.macapk.loader.StatusBarDriver.heightPxRaw(null); }
        catch (Throwable t) { return 0; }
    }

    public void getSize(Point outSize)  { outSize.x = getWidth(); outSize.y = getHeight(); }
    public void getRealSize(Point out)  {
        out.x = macapkRealWidthPx();
        out.y = macapkRealHeightPx();
    }
    public int getWidth()               { return macapkRealWidthPx()  - macapkNavDecorWidthPx(); }
    public int getHeight()              { return macapkRealHeightPx() - macapkNavDecorHeightPx(); }
    public void getMetrics(DisplayMetrics m) {
        m.setToDefaults();
        m.widthPixels  = getWidth();
        m.heightPixels = getHeight();
    }
    public void getRealMetrics(DisplayMetrics m) {
        m.setToDefaults();
        m.widthPixels  = macapkRealWidthPx();
        m.heightPixels = macapkRealHeightPx();
    }
    /** Deprecated (API 13) display-size-into-Rect. Reddit's IntroductionActivity
     *  (welcome/login screen) calls this in onCreate; without it onCreate throws
     *  NoSuchMethodError and the Compose login UI never sets up. AOSP fills
     *  (0,0,width,height). */
    public void getRectSize(android.graphics.Rect outSize) {
        if (outSize != null) outSize.set(0, 0, getWidth(), getHeight());
    }
    /** AOSP: the smallest/largest APP size across all four rotations.
     *
     *  This is NOT "the display size" -- Display.getCurrentSizeRange reads
     *  DisplayInfo.{smallest,largest}NominalApp{Width,Height}, which
     *  DisplayContent.computeSizeRangesAndScreenLayout builds by calling
     *  adjustDisplaySizeRanges once per rotation with the width and height
     *  SWAPPED for 90/270, taking the min and max of DisplayPolicy's
     *  getConfigDisplayWidth/Height (non-decor width, and non-decor height
     *  minus the status bar) over all four.
     *
     *  Concretely, for a phone whose navigation bar moves to the side in
     *  landscape (config_navBarCanMove, the phone default):
     *
     *    rot 0/180 (portrait) : w = W                 h = H - nav - status
     *    rot 90/270 (landscape): w = H - nav          h = W - status
     *
     *  We answered width x height for both bounds, which made the range a
     *  single point and made the derived "does the range contain the app
     *  size" question answer 1 where every real Android device answers 0
     *  (the portrait app height, which excludes only the navigation bar,
     *  legitimately exceeds the largest NOMINAL height, which also excludes
     *  the status bar). Both of those were probe rows. */
    public void getCurrentSizeRange(Point smallest, Point largest) {
        final int w = macapkRealWidthPx(), h = macapkRealHeightPx();
        final int nat = Math.min(w, h), lng = Math.max(w, h);  // natural portrait
        final int nav = macapkNavDecorPx(), sb = macapkStatusDecorPx();
        final int portW = nat,       portH = lng - nav - sb;
        final int landW = lng - nav, landH = nat - sb;
        if (smallest != null) {
            smallest.x = Math.min(portW, landW);
            smallest.y = Math.min(portH, landH);
        }
        if (largest != null) {
            largest.x = Math.max(portW, landW);
            largest.y = Math.max(portH, landH);
        }
    }
    /** AOSP: the refresh rate of this display in frames per second.
     *
     *  ⚠ THIS MUST EQUAL THE RATE WE ACTUALLY PACE AT. Choreographer derives its frame interval
     *  from this value, and a guest engine (Unity/Swappy especially) advances its simulation
     *  clock by that interval per tick -- so if we drive ticks faster than we report, the game
     *  runs fast. That is exactly the v1.0.568 "gameplay too fast" defect: the drive ran at the
     *  raw ~74/s display-link rate while this method returned a hardcoded 60f.
     *
     *  Until v1.0.1102 this returned a literal 60f, which was SAFE but pinned every game to 60
     *  even on a 120 Hz panel. It now returns the measured rate of the screen the window is on,
     *  clamped to MACAPK_MAX_FPS (default 120) -- the SAME value, from the SAME native function
     *  (CocoaDisplayHz), that sets MTKView.preferredFramesPerSecond and the guest drive cadence.
     *  One source, so they cannot drift. Do not reintroduce a literal here.
     *
     *  Falls back to 60f if the native side is not up yet (a guest can ask before the window
     *  exists), which is the pre-v1102 answer and the safest possible one. */
    public float getRefreshRate()       { return macapkDisplayHz(); }

    /** The one place this class reads the paced refresh rate. See getRefreshRate(). */
    static float macapkDisplayHz() {
        try {
            int hz = NativeCocoa.displayHz();
            if (hz >= 24 && hz <= 480) { macapkAnnounceHz(hz, "native"); return (float) hz; }
            macapkAnnounceHz(60, "out-of-range native value " + hz);
        } catch (Throwable t) {
            // Native not bound yet (a guest can ask before the window exists). That is legitimate
            // and 60f is the safest answer -- but a PERMANENT binding failure would land here too
            // and be indistinguishable from it, silently pinning every panel to 60. So say so out
            // loud exactly once rather than letting the fallback masquerade as a measurement.
            macapkAnnounceHz(60, "FALLBACK (" + t.getClass().getSimpleName() + ")");
        }
        return 60f;
    }

    private static boolean sHzAnnounced = false;
    private static synchronized void macapkAnnounceHz(int hz, String src) {
        if (sHzAnnounced) return;
        sHzAnnounced = true;
        System.err.println("[I] Display: getRefreshRate() = " + hz + " Hz [" + src + "]");
    }
    public boolean isValid()            { return true; }

    // AOSP Display power states (API 20+).
    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_OFF = 1;
    public static final int STATE_ON = 2;
    public static final int STATE_DOZE = 3;
    public static final int STATE_DOZE_SUSPEND = 4;
    public static final int STATE_VR = 5;
    public static final int STATE_ON_SUSPEND = 6;
    /** AOSP getState(): the display's power state. Always ON here. */
    public int getState()               { return STATE_ON; }

    /** AOSP (API 21): app-visible VSYNC phase offset in nanoseconds.
     *  Minecraft Bedrock's renderdragon frame pacing caches this method ID
     *  from native at init; a missing method returned a NULL jmethodID that
     *  the engine then called (SEGV in jni_CallXxxMethod, v1.0.572). 1 ms is
     *  a typical real-device value; the exact number only phases the
     *  engine's pacing estimates. */
    public long getAppVsyncOffsetNanos() { return 1_000_000L; }

    /** AOSP (API 21): how long after a vsync the app's frame submission can
     *  still make the next present. Typical device value is one 60 Hz frame
     *  period + the 1 ms vsync offset. */
    public long getPresentationDeadlineNanos() { return 17_666_666L; }

    // Display.Mode (API 23+). libGDX (Unciv) calls getSupportedModes() in
    // onCreate; a missing method threw NoSuchMethodError. We report one mode
    // matching the current surface at 60 Hz.
    public static final class Mode implements android.os.Parcelable {
        private final int id, w, h; private final float rr;
        public Mode(int id, int w, int h, float rr) { this.id = id; this.w = w; this.h = h; this.rr = rr; }
        public int getModeId()            { return id; }
        public int getPhysicalWidth()     { return w; }
        public int getPhysicalHeight()    { return h; }
        public float getRefreshRate()     { return rr; }

        /** AOSP Display.Mode.getAlternativeRefreshRates() (API 31): the other
         *  refresh rates this display can switch to SEAMLESSLY from this mode.
         *  This display advertises exactly one mode at one rate
         *  (getSupportedModes / getSupportedRefreshRates above), so there is no
         *  alternative to switch to and AOSP's answer is an empty array -- the
         *  same as any display whose mode has no seamless partners. */
        public float[] getAlternativeRefreshRates() { return new float[0]; }

        /** AOSP Display.Mode.getSupportedHdrTypes() (API 34): the HDR types
         *  available IN THIS MODE. Consistent with Display.isHdr() == false and
         *  getHdrCapabilities() reporting no types: empty. */
        public int[] getSupportedHdrTypes() { return new int[0]; }
        @Override public String toString() { return "Mode{id=" + id + " " + w + "x" + h + "@" + rr + "}"; }

        // AOSP's Mode also carries vsyncRate, isSynthetic,
        // alternativeRefreshRates[] and supportedHdrTypes[] (Display.java
        // L2404-2413/2695-2703), which this class does not track. Writing
        // our own 4-field subset in the same LEADING order AOSP uses
        // (modeId, width, height, refreshRate) rather than padding the
        // missing fields.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel out, int parcelableFlags) {
            out.writeInt(id);
            out.writeInt(w);
            out.writeInt(h);
            out.writeFloat(rr);
        }

        public static final android.os.Parcelable.Creator<Mode> CREATOR =
                new android.os.Parcelable.Creator<Mode>() {
            @Override public Mode createFromParcel(android.os.Parcel in) {
                return new Mode(in.readInt(), in.readInt(), in.readInt(), in.readFloat());
            }
            @Override public Mode[] newArray(int size) { return new Mode[size]; }
        };
    }
    /** AOSP Display.Mode reports the PHYSICAL panel, so it neither subtracts
     *  decor nor rotates: DisplayInfo.getMode() comes from the physical
     *  display's supported-modes table, and getNaturalDisplayWidth/Height are
     *  what the rest of DisplayInfo swaps -- not the mode. We were building it
     *  from getWidth()/getHeight(), which meant (a) it lost the navigation bar
     *  after the decor ladder landed and (b) it would have transposed on an
     *  orientation change. Report the natural-orientation surface instead, and
     *  build it per call so it cannot go stale against a resize. */
    private Mode currentMode() {
        final int w = macapkRealWidthPx(), h = macapkRealHeightPx();
        return new Mode(1, Math.min(w, h), Math.max(w, h), macapkDisplayHz());
    }
    public Mode getMode()               { return currentMode(); }
    public Mode[] getSupportedModes()   { return new Mode[] { currentMode() }; }
    public float[] getSupportedRefreshRates() { return new float[] { macapkDisplayHz() }; }

    /** SDR display: AOSP returns a capabilities object with no HDR types. */
    public HdrCapabilities getHdrCapabilities() { return new HdrCapabilities(); }

    /** No HDR pipeline on this compositor. */
    public boolean isHdr() { return false; }

    /** No wide-gamut pipeline either; players pick the sRGB rendition. */
    public boolean isWideColorGamut() { return false; }

    /**
     * AOSP Display.java:1029-1032 (android-11.0.0_r48):
     *     public ColorSpace getPreferredWideGamutColorSpace() {
     *         if (mDisplayInfo.isWideColorGamut()) { ... }
     *         return null;
     *     }
     * i.e. the preferred wide-gamut space is non-null EXACTLY when the display
     * reports wide gamut. isWideColorGamut() is false here, so null is the
     * correct and only consistent answer -- claiming a space while denying the
     * gamut would make an app configure a surface the compositor cannot honour.
     * The method was absent entirely, so every caller got NoSuchMethodError
     * rather than the null it null-checks for.
     */
    public android.graphics.ColorSpace getPreferredWideGamutColorSpace() {
        return isWideColorGamut() ? android.graphics.ColorSpace.get(
                android.graphics.ColorSpace.Named.DISPLAY_P3) : null;
    }

    /**
     * The HDR formats this display can present, plus its luminance range.
     *
     * A Mac.apk window composites through a standard CAMetalLayer with no HDR
     * pipeline, so the honest answer is the same one AOSP gives for an SDR
     * display: an empty supported-types array and INVALID_LUMINANCE. Video
     * players read getSupportedHdrTypes() and pick an SDR rendition, which is
     * correct -- claiming HDR support would make them select a stream we cannot
     * tone-map.
     *
     * Source: frameworks/base/core/java/android/view/Display.java
     * (nested class HdrCapabilities)
     */
    public static final class HdrCapabilities implements android.os.Parcelable {

        public static final int HDR_TYPE_INVALID = -1;
        public static final int HDR_TYPE_DOLBY_VISION = 1;
        public static final int HDR_TYPE_HDR10 = 2;
        public static final int HDR_TYPE_HLG = 3;
        public static final int HDR_TYPE_HDR10_PLUS = 4;

        public static final float INVALID_LUMINANCE = -1.0f;

        private final int[] mSupportedHdrTypes;
        private final float mMaxLuminance;
        private final float mMaxAverageLuminance;
        private final float mMinLuminance;

        public HdrCapabilities() {
            this(new int[0], INVALID_LUMINANCE, INVALID_LUMINANCE, INVALID_LUMINANCE);
        }

        public HdrCapabilities(int[] supportedHdrTypes, float maxLuminance,
                               float maxAverageLuminance, float minLuminance) {
            mSupportedHdrTypes = supportedHdrTypes != null ? supportedHdrTypes : new int[0];
            mMaxLuminance = maxLuminance;
            mMaxAverageLuminance = maxAverageLuminance;
            mMinLuminance = minLuminance;
        }

        public int[] getSupportedHdrTypes() { return mSupportedHdrTypes.clone(); }

        public float getDesiredMaxLuminance() { return mMaxLuminance; }

        public float getDesiredMaxAverageLuminance() { return mMaxAverageLuminance; }

        public float getDesiredMinLuminance() { return mMinLuminance; }

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeIntArray(mSupportedHdrTypes);
            dest.writeFloat(mMaxLuminance);
            dest.writeFloat(mMaxAverageLuminance);
            dest.writeFloat(mMinLuminance);
        }

        public static final Creator<HdrCapabilities> CREATOR = new Creator<HdrCapabilities>() {
            @Override public HdrCapabilities createFromParcel(android.os.Parcel in) {
                return new HdrCapabilities(in.createIntArray(),
                        in.readFloat(), in.readFloat(), in.readFloat());
            }
            @Override public HdrCapabilities[] newArray(int size) {
                return new HdrCapabilities[size];
            }
        };
    }

    /** AOSP Display.getShape (API 34): the physical outline of the display.
     *  Mac.apk targets rectangular windows, so this is the rectangular (empty
     *  path) DisplayShape. */
    public android.view.DisplayShape getShape() { return android.view.DisplayShape.NONE; }

    /** AOSP Display.getRoundedCorner (API 31): the rounded-corner geometry for a
     *  corner position, or null. Rectangular windows have none. */
    public android.view.RoundedCorner getRoundedCorner(int position) { return null; }

    public android.view.DisplayCutout getCutout() { return null; }
    public int getFlags() { return 0; }
    public int getPixelFormat() { return android.graphics.PixelFormat.RGBA_8888; }

    // ---- AOSP API-36 surface completion (android.view lane) ------------------
    // Each answer below is the one an SDR, fixed-refresh-rate display gives on a
    // real device -- which is what macOS presents to us through CAMetalLayer
    // (see isHdr() / isWideColorGamut() / getRefreshRate() above, all of which
    // already report exactly that). Nothing here invents a capability.

    /** AOSP Display.isHdrSdrRatioAvailable(): can this display report a live
     *  HDR/SDR brightness ratio? Only an HDR display can, and isHdr() is false. */
    public boolean isHdrSdrRatioAvailable() { return false; }

    /** AOSP Display.getHdrSdrRatio(): the current HDR headroom. AOSP documents
     *  the value as 1.0 when isHdrSdrRatioAvailable() is false, i.e. "HDR is
     *  exactly as bright as SDR". */
    public float getHdrSdrRatio() { return 1.0f; }

    /** AOSP Display.getHighestHdrSdrRatio() (API 36): the maximum headroom this
     *  display can ever reach. 1.0 for an SDR display, matching getHdrSdrRatio. */
    public float getHighestHdrSdrRatio() { return 1.0f; }

    /** AOSP Display.registerHdrSdrRatioChangedListener(Executor, Consumer):
     *  AOSP THROWS IllegalStateException when isHdrSdrRatioAvailable() is
     *  false -- an app must not be allowed to believe it registered. Reproduced,
     *  rather than silently accepting a listener that could never fire. */
    public void registerHdrSdrRatioChangedListener(java.util.concurrent.Executor executor,
            java.util.function.Consumer<Display> listener) {
        if (!isHdrSdrRatioAvailable()) {
            throw new IllegalStateException("HDR/SDR ratio changed not supported");
        }
    }
    /** AOSP Display.unregisterHdrSdrRatioChangedListener(Consumer): nothing can
     *  be registered (see above), so this is AOSP's no-op for an unknown
     *  listener. */
    public void unregisterHdrSdrRatioChangedListener(
            java.util.function.Consumer<Display> listener) {}

    /** AOSP Display.isMinimalPostProcessingSupported(): whether the display can
     *  be put into a low-latency (game/auto) mode. macOS exposes no such
     *  control, so this is false -- which is also what makes
     *  WindowManager.LayoutParams.preferMinimalPostProcessing inert here, and
     *  the two now agree instead of contradicting each other. */
    public boolean isMinimalPostProcessingSupported() { return false; }

    /** AOSP Display.hasArrSupport() (API 36): does the display support adaptive
     *  refresh rate? getSupportedRefreshRates() reports exactly one rate, so no. */
    public boolean hasArrSupport() { return false; }

    /** AOSP Display.getSuggestedFrameRate(int category) (API 36): the frame rate
     *  the platform suggests for a FRAME_RATE_CATEGORY_* class of content. On a
     *  display with a single supported rate every category resolves to that
     *  rate, so the suggestion is getRefreshRate() -- read from the same place
     *  rather than hardcoded, so the two can never drift apart. */
    public float getSuggestedFrameRate(int category) { return getRefreshRate(); }
}
