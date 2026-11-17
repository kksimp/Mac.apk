// LocationBridge -- JNI seam to macOS Location Services (CoreLocation), the
// native half being runtime/src/location_bridge.mm.
//
// The layer BELOW android.location.LocationManager, which plays the part of
// AOSP's LocationManagerService + LocationProviderManager on top of it: this
// class only reports the host's state (is Location Services on, has macOS
// authorized this app, at what accuracy) and turns each CLLocation into an
// android.location.Location. Which provider a fix is reported under, the
// per-request filtering and every permission rule live in LocationManager.
//
// MACAPK_LOCATION_HOST=0 (default ON) unbinds it: available() is false and
// LocationManager falls back to the pre-bridge surface, where every built-in
// provider reports disabled and no prompt can ever appear.
//
// Native methods are bound at boot via RegisterNatives
// (native_methods.cpp -> location::RegisterLocationBridgeMethods), the same
// pattern as GamepadBridge and CameraBridge; a missing registration shows up
// as UnsatisfiedLinkError, which available() turns into "not bound".
package com.macapk.loader;

import android.location.Location;
import android.os.SystemClock;

public final class LocationBridge {
    private LocationBridge() {}

    private static final String TAG = "Mac.apk.Location";

    public static final boolean ON = !"0".equals(System.getenv("MACAPK_LOCATION_HOST"));

    // CLAuthorizationStatus, values verbatim from CLLocationManager.h. macOS has
    // no WhenInUse state (the header marks it API_UNAVAILABLE(macos)); a granted
    // app reads AuthorizedAlways.
    public static final int AUTH_NOT_DETERMINED = 0;
    public static final int AUTH_RESTRICTED     = 1;
    public static final int AUTH_DENIED         = 2;
    public static final int AUTH_AUTHORIZED     = 3;

    private static native boolean nativeBound();
    private static native void nativeSetHelper(String path);
    private static native boolean nativeCanPrompt();
    private static native int nativeAuthStatus();
    private static native boolean nativeReducedAccuracy();
    private static native boolean nativeServicesEnabled();
    private static native double[] nativeCachedFix();
    private static native void nativeRequestAuth();
    private static native void nativeStart(boolean reduced);
    private static native void nativeStop();
    private static native String nativeHostSettingsUrl();

    /** What LocationManager hears from the host. Called on the AppKit thread:
     *  an implementation must only record and post, never block. */
    public interface Sink {
        void onHostLocation(Location location);
        void onHostAuthorization(int status, boolean reducedAccuracy);
    }

    private static volatile Sink sSink;
    private static volatile int sBound = -1;   // -1 unknown, 0 no, 1 yes
    private static final Object AUTH_LOCK = new Object();

    // locationServicesEnabled may round-trip to locationd, and apps poll
    // isProviderEnabled from their UI thread (some every frame), so the answer
    // is held briefly. An authorization callback refreshes it at once: turning
    // Location Services off reports every app as denied.
    private static final long SERVICES_TTL_MS = 2000;
    private static volatile long sServicesAt;
    private static volatile boolean sServices;

    public static void setSink(Sink sink) { sSink = sink; }

    /** Is the host location service wired in at all? */
    public static boolean available() {
        int b = sBound;
        if (b < 0) {
            if (!ON) {
                b = 0;
            } else {
                try {
                    b = nativeBound() ? 1 : 0;
                    if (b == 1) {
                        java.io.File h = locateHelper();
                        nativeSetHelper(h != null ? h.getPath() : null);
                        try {
                            NativeLog.i(TAG, h != null ? "Mac.apk Location helper: " + h
                                    : "no Mac.apk Location helper found: macOS cannot show its"
                                      + " location prompt for this runtime");
                        } catch (Throwable ignored) { }
                    }
                } catch (Throwable t) {
                    // NativeLog is itself native: an offline JVM (the gates)
                    // has no runtime library, and the log must not throw.
                    try { NativeLog.w(TAG, "macOS Location Services not bridged: " + t); }
                    catch (Throwable ignored) { }
                    b = 0;
                }
            }
            sBound = b;
        }
        return b == 1;
    }

    /** "Mac.apk Location.app" (launcher/LocationHelperMain.swift), the bundle
     *  CoreLocation runs in so macOS can show its prompt: MACAPK_LOCATION_HELPER,
     *  else Contents/Helpers next to the running runtime (a dev Mac.apk.app),
     *  else the canonical installed Mac.apk.app (MACAPK_CANONICAL_APP or
     *  /Applications). The staged shared runtime has no Helpers of its own,
     *  so an installed app resolves the canonical one, as it does for
     *  macapk-installhelper (PackageInstallerBackend.locateHelper). */
    static java.io.File locateHelper() {
        final String rel = "Helpers/Mac.apk Location.app/Contents/MacOS/macapk-location";
        String env = System.getenv("MACAPK_LOCATION_HELPER");
        if (env != null && !env.isEmpty()) {
            java.io.File f = new java.io.File(env);
            return f.canExecute() ? f : null;
        }
        try {
            String cmd = ProcessHandle.current().info().command().orElse(null);
            if (cmd != null) {
                java.io.File exeDir = new java.io.File(cmd).getParentFile();
                java.io.File contents = exeDir != null ? exeDir.getParentFile() : null;
                if (contents != null) {
                    java.io.File f = new java.io.File(contents, rel);
                    if (f.canExecute()) return f;
                }
            }
        } catch (Throwable ignored) { }
        for (java.io.File app : PackageInstallerBackend.canonicalAppCandidates()) {
            java.io.File f = new java.io.File(app, "Contents/" + rel);
            if (f.canExecute()) return f;
        }
        return null;
    }

    /** Can macOS show its location prompt for us at all? Only through the helper:
     *  for this runtime's own process CoreLocationAgent skips it. */
    public static boolean canPrompt() {
        return available() && nativeCanPrompt();
    }

    public static int authStatus() {
        if (!available()) return AUTH_RESTRICTED;
        return nativeAuthStatus();
    }

    /** macOS has authorized this app (any accuracy). */
    public static boolean authorized() { return authStatus() == AUTH_AUTHORIZED; }

    /** macOS has refused this app, or the system-wide switch is off (Apple
     *  folds both into kCLAuthorizationStatusDenied). */
    public static boolean refused() {
        int s = authStatus();
        return s == AUTH_DENIED || s == AUTH_RESTRICTED;
    }

    /** The user allowed only approximate location (System Settings' per-app
     *  "Precise" switch off): the host form of holding COARSE without FINE. */
    public static boolean reducedAccuracy() {
        return available() && nativeReducedAccuracy();
    }

    /** The system-wide Location Services switch: Android's location master
     *  switch (Settings.Secure.LOCATION_MODE). */
    public static boolean servicesEnabled() {
        if (!available()) return false;
        long now = SystemClock.uptimeMillis();
        if (sServicesAt == 0 || now - sServicesAt > SERVICES_TTL_MS) {
            sServices = nativeServicesEnabled();
            sServicesAt = now;
        }
        return sServices;
    }

    /**
     * Ask macOS for authorization and wait for the user's answer -- the TCC
     * prompt standing in for Android's GrantPermissionsActivity. Returns the
     * status afterwards. Returns at once when the question is already settled
     * (CoreLocation never re-prompts), and never on the AppKit thread, which is
     * the thread that has to show the prompt and deliver the answer.
     */
    public static int requestAuthorizationAndWait(long timeoutMs) {
        if (!available()) return AUTH_RESTRICTED;
        if (authStatus() != AUTH_NOT_DETERMINED) return authStatus();
        if (!canPrompt()) {
            // Waiting would only freeze the caller: no prompt can appear.
            NativeLog.w(TAG, "macOS cannot show its location prompt (no Mac.apk Location"
                    + " helper); location stays unauthorized");
            return authStatus();
        }
        nativeRequestAuth();
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (AUTH_LOCK) {
            while (authStatus() == AUTH_NOT_DETERMINED) {
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) {
                    NativeLog.w(TAG, "no answer to the macOS location prompt after "
                            + timeoutMs + " ms; the permission stays ungranted");
                    break;
                }
                try {
                    AUTH_LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return authStatus();
    }

    /** The URL of the host's own location settings (Windows:
     *  ms-settings:privacy-location), or null when the host has none to offer
     *  (the Mac binds no such native). */
    public static String hostSettingsUrl() {
        if (!available()) return null;
        try {
            return nativeHostSettingsUrl();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Start (or re-aim) CoreLocation updates. Fixes arrive via the Sink. */
    public static void start(boolean reduced) {
        if (available()) nativeStart(reduced);
    }

    public static void stop() {
        if (available()) nativeStop();
    }

    /** CoreLocation's cached fix for this app, or null. Only meaningful once
     *  macOS has authorized the app; the caller checks that. */
    public static Location cachedFix() {
        if (!available()) return null;
        double[] v = nativeCachedFix();
        if (v == null || v.length < 12) return null;
        return toLocation(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9],
                (long) v[10], v[11] != 0);
    }

    // ---- callbacks from location_bridge.mm (AppKit thread) ----------------

    static void onNativeLocation(double lat, double lon, double ellipsoidalAlt, double mslAlt,
            double hAcc, double vAcc, double speed, double speedAcc, double course,
            double courseAcc, long timeMs, boolean simulated) {
        Location l = toLocation(lat, lon, ellipsoidalAlt, mslAlt, hAcc, vAcc, speed, speedAcc,
                course, courseAcc, timeMs, simulated);
        Sink s = sSink;
        if (s != null) {
            try { s.onHostLocation(l); } catch (Throwable t) {
                NativeLog.w(TAG, "location delivery failed: " + t);
            }
        }
    }

    /** One CLLocation. Negative accuracies / speed / course are CoreLocation's
     *  "invalid" marker, so each maps to the Location having no such field.
     *  The provider is left null; LocationManager names it per registration. */
    private static Location toLocation(double lat, double lon, double ellipsoidalAlt,
            double mslAlt, double hAcc, double vAcc, double speed, double speedAcc,
            double course, double courseAcc, long timeMs, boolean simulated) {
        Location l = new Location((String) null);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setTime(timeMs);
        // Location.getElapsedRealtimeNanos is the fix time on SystemClock's
        // elapsedRealtime base, which is what getElapsedRealtimeAgeMillis and
        // AOSP's own age checks compare against. Derived from the fix's real
        // age, so a cached fix reads as old as it is.
        long ageMs = Math.max(0L, System.currentTimeMillis() - timeMs);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L);
        l.setAccuracy((float) hAcc);
        if (vAcc >= 0) {
            // Android's altitude is height above the WGS84 ellipsoid
            // (Location.getAltitude javadoc); CoreLocation's `altitude` is above
            // mean sea level and `ellipsoidalAltitude` is the WGS84 one. The MSL
            // value goes to Android 14's dedicated field.
            l.setAltitude(ellipsoidalAlt);
            l.setVerticalAccuracyMeters((float) vAcc);
            l.setMslAltitudeMeters(mslAlt);
            l.setMslAltitudeAccuracyMeters((float) vAcc);
        }
        if (speed >= 0) l.setSpeed((float) speed);
        if (speedAcc >= 0) l.setSpeedAccuracyMetersPerSecond((float) speedAcc);
        if (course >= 0) l.setBearing((float) course);
        if (courseAcc >= 0) l.setBearingAccuracyDegrees((float) courseAcc);
        // CLLocationSourceInformation.isSimulatedBySoftware: a location
        // injected by a simulator is what Android calls a mock location.
        if (simulated) l.setMock(true);
        return l;
    }

    static void onNativeAuthorization(int status, boolean reduced) {
        sServicesAt = 0;   // the switch may be what changed
        synchronized (AUTH_LOCK) { AUTH_LOCK.notifyAll(); }
        NativeLog.i(TAG, "macOS location authorization: status=" + status
                + (reduced ? " (approximate only)" : ""));
        Sink s = sSink;
        if (s != null) {
            try { s.onHostAuthorization(status, reduced); } catch (Throwable t) {
                NativeLog.w(TAG, "authorization delivery failed: " + t);
            }
        }
    }
}
