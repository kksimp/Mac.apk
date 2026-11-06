// com.macapk.gms.CheckinClient -- Google device checkin (registration).
//
// WHAT CHECKIN IS, AND WHAT IT IS NOT. Checkin is how an Android device tells
// Google "I exist", and gets back an `android_id` -- the 64-bit identifier that
// every later Play API call carries as X-DFE-Device-Id. It requires no account,
// carries NO attestation, and asserts nothing about the device's integrity. It
// is REGISTRATION, not CERTIFICATION. Nothing in this file touches Play
// Integrity, SafetyNet or DroidGuard, and nothing in it can be made to.
//
// ---- WHAT WE SEND, AND THE THINGS WE DELIBERATELY DO NOT --------------------
// We send what we honestly are: the Build fields android.os.Build already
// reports to every guest (BRAND/MANUFACTURER "Apple", MODEL the host hw.model, the real SDK level,
// the real ABI list, the real screen metrics). We register as a Mac.apk device
// because that is what this is.
//
// microG's reference implementation fabricates several identifiers that we
// OMIT, and the omissions are deliberate rather than unfinished:
//   * meid -- microG synthesises a 15-digit MEID with a valid Luhn digit
//     (DeviceIdentifier.java:36-60). This machine has no cellular radio, so it
//     has no MEID; a wifi-only Android tablet omits the field and so do we.
//     Minting one would be inventing a cellular identity that does not exist.
//   * cellOperator / simOperator / roaming -- microG hardcodes "26207" (a German
//     network) and "mobile-notroaming" (PhoneInfo.java:22-24). We have no SIM.
//     Omitted.
//   * accountCookie -- microG can attach a per-account LSID obtained from a
//     separate bare ac2dm call. We send the zero-account form (a single empty
//     string), which is microG's own no-account path and a supported request
//     shape. Checkin does not need an account and we do not give it one.
// The MAC address IS sent, but as a stable RANDOM address persisted in the GMS
// store -- not this Mac's real hardware address. That is not a fabrication to
// fool anyone: MAC randomisation is what every Android 10+ device does by
// default, so a random-but-stable address is the FAITHFUL value, and it avoids
// handing Google a hardware identifier for the user's actual machine.
//
// ⚠ IF GOOGLE REJECTS THE HONEST REQUEST, THAT IS DATA, NOT A PROMPT TO LIE.
// Should checkin turn out to require a fabricated MEID or a real-looking OEM
// fingerprint, the correct response is to report the precise stopping point,
// not to start synthesising identifiers until it passes.
//
// PROTOCOL REFERENCE (read-only, never vendored): microG GmsCore @157c9d86,
//   play-services-core/src/main/java/org/microg/gms/checkin/CheckinClient.java
//   play-services-core-proto/src/main/proto/checkin.proto
//   play-services-core-proto/src/main/proto/deviceconfig.proto
// Field numbers below were read from those files; see
// docs/gms-checkin-auth-wire_2026-09-05.md for the full tabulation.
package com.macapk.gms;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CheckinClient {

    private static final String URL = "https://android.clients.google.com/checkin";

    /** microG sends this hard-coded UA rather than deriving one from Build
     *  (CheckinClient.java:60). We send OURS, because the whole point of this
     *  request is to say what this device is, and the Build fields inside the
     *  body already say "Mac.apk" -- a UA claiming to be a vbox86p emulator
     *  image would contradict the body it is attached to. */
    private static final String USER_AGENT = "Android-Checkin/2.0 (Mac.apk); gzip";

    /** microG SettingsContract.kt:120. The literal first-checkin digest; the
     *  server hands back a new one to echo on later requests. */
    private static final String INITIAL_DIGEST =
            "1-929a0dca0eee55513280171a8585da7dcd3700f8";

    /** microG CheckinClient.java:146. An OTA certificate token the request
     *  carries; it identifies the update channel, not the device. */
    private static final String OTA_CERT = "71Q6Rn2DDZl1zPDVaaeEHItd";

    // Request field numbers (checkin.proto, CheckinRequest)
    private static final int REQ_ANDROID_ID = 2,  REQ_DIGEST = 3,   REQ_CHECKIN = 4;
    private static final int REQ_LOCALE = 6,      REQ_LOGGING_ID = 7;
    private static final int REQ_MAC_ADDRESS = 9, REQ_ACCOUNT_COOKIE = 11;
    private static final int REQ_TIME_ZONE = 12,  REQ_SECURITY_TOKEN = 13;
    private static final int REQ_VERSION = 14,    REQ_OTA_CERT = 15;
    private static final int REQ_SERIAL = 16,     REQ_DEVICE_CONFIG = 18;
    private static final int REQ_MAC_TYPE = 19,   REQ_FRAGMENT = 20;
    // CheckinRequest.Checkin
    private static final int CK_BUILD = 1, CK_LAST_CHECKIN_MS = 2, CK_EVENT = 3,
                             CK_USER_NUMBER = 9;
    // CheckinRequest.Checkin.Event
    private static final int EV_TAG = 1, EV_VALUE = 2, EV_TIME_MS = 3;
    // CheckinRequest.Checkin.Build
    private static final int B_FINGERPRINT = 1, B_HARDWARE = 2, B_BRAND = 3,
                             B_BOOTLOADER = 5, B_CLIENT_ID = 6, B_TIME = 7,
                             B_DEVICE = 9, B_SDK_VERSION = 10, B_MODEL = 11,
                             B_MANUFACTURER = 12, B_PRODUCT = 13, B_OTA_INSTALLED = 14;
    // DeviceConfig
    private static final int DC_TOUCH_SCREEN = 1, DC_KEYBOARD_TYPE = 2, DC_NAVIGATION = 3,
                             DC_SCREEN_LAYOUT = 4, DC_HAS_HARD_KEYBOARD = 5,
                             DC_HAS_FIVE_WAY_NAV = 6, DC_DENSITY_DPI = 7,
                             DC_GLES_VERSION = 8, DC_NATIVE_PLATFORM = 11,
                             DC_WIDTH_PIXELS = 12, DC_HEIGHT_PIXELS = 13, DC_LOCALE = 14;
    // CheckinResponse -- note androidId/securityToken are FIXED64 in the response
    // and VARINT in the request. That asymmetry is real (proto: request field 2
    // is int64, response field 7 is fixed64) and a decoder that assumes one
    // shape for both silently reads zero.
    private static final int RESP_TIME_MS = 3, RESP_DIGEST = 4, RESP_ANDROID_ID = 7,
                             RESP_SECURITY_TOKEN = 8, RESP_DEVICE_DATA_VERSION = 12;

    private CheckinClient() {}

    /** The android_id, checking in first if we have never registered.
     *  @return 0 when registration failed -- never a fabricated id. Callers that
     *          need one must surface the failure; a made-up device id would make
     *          every later Play call fail in a way that looks like something
     *          else entirely. */
    public static synchronized long ensureAndroidId() {
        // SYNCHRONIZED because two threads reach this concurrently on a cold
        // store since v1.0.1179: the MCS thread starts at Boot for any app that
        // declares push, while PushRegisterService's HandlerThread registers.
        // Both would see androidId==0, both would check in, and last-write-wins
        // would leave a token minted for one device identity while the socket
        // logged in as another. Re-read inside the lock so the loser of the race
        // takes the winner's id instead of checking in again.
        long existing = GmsStore.androidId();
        if (existing != 0) return existing;
        try {
            return checkin();
        } catch (Throwable t) {
            log("checkin failed: " + t);
            return 0L;
        }
    }

    /** Time budget for the checkin POST. See the note at the call site: on a
     *  cold store this runs inside the register round trip, so it shares the
     *  client's ~30s window rather than getting its own. */
    private static final int CHECKIN_CONNECT_MS = 8_000;
    private static final int CHECKIN_READ_MS    = 12_000;

    /** Perform a checkin and persist the result. @return the android_id. */
    public static synchronized long checkin() throws IOException {
        // synchronized as well as ensureAndroidId: this is PUBLIC and GmsCli
        // calls it directly, so leaving it open would let the CLI race the
        // runtime's own checkin and reintroduce the two-androidId split the
        // lock on ensureAndroidId was added to close. Same monitor (both are
        // static on this class), so the pair cannot deadlock each other.
        long androidId = GmsStore.androidId();
        long securityToken = GmsStore.securityToken();
        boolean first = (androidId == 0);

        byte[] body = buildRequest(androidId, securityToken, first);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        // Same budget as register3, and for the same reason: on a COLD store this
        // checkin runs INSIDE PushRegisterService.handle, before register3, so
        // the client's ~30s MessengerIpcClient window has to cover both. On the
        // GmsHttp default (15s+30s) the pair could reach ~68s and the app would
        // report "Timed out waiting for response" -- the exact shape the FCM
        // gate exists to prevent, on a first launch over a bad network.
        GmsHttp.Response r = GmsHttp.postBytes(URL, body, "application/x-protobuffer",
                                               headers, /*gzipBody=*/true,
                                               CHECKIN_CONNECT_MS, CHECKIN_READ_MS);
        GmsHttp.log("checkin", URL, r);
        if (!r.ok()) {
            throw new IOException("checkin: HTTP " + r.status + " -- "
                    + shortBody(r.body));
        }

        long id  = Protobuf.scalar(r.body, RESP_ANDROID_ID, 0L);
        long tok = Protobuf.scalar(r.body, RESP_SECURITY_TOKEN, 0L);
        if (id == 0) {
            throw new IOException("checkin: response carried no android_id ("
                    + r.body.length + " bytes: " + Protobuf.hex(r.body, 64) + ")");
        }
        String ddvi = Protobuf.pathString(r.body, RESP_DEVICE_DATA_VERSION);
        String digest = Protobuf.pathString(r.body, RESP_DIGEST);
        GmsStore.setCheckin(id, tok, ddvi);
        if (digest != null) GmsStore.putAccountData("_device", "digest", digest);
        long timeMs = Protobuf.scalar(r.body, RESP_TIME_MS, 0L);
        if (timeMs != 0) GmsStore.putAccountData("_device", "lastCheckinMs", Long.toString(timeMs));
        log("registered android_id=" + Long.toHexString(id)
            + " (first=" + first + ", securityToken=" + (tok != 0) + ")");
        return id;
    }

    // ===================================================================

    private static byte[] buildRequest(long androidId, long securityToken, boolean first) {
        Protobuf.Writer build = Protobuf.writer()
                .string(B_FINGERPRINT, android.os.Build.FINGERPRINT)
                .string(B_HARDWARE,    android.os.Build.HARDWARE)
                .string(B_BRAND,       android.os.Build.BRAND)
                .string(B_BOOTLOADER,  android.os.Build.BOOTLOADER)
                // microG CheckinClient.java:113 -- identifies the checkin client
                // family, not the device. Every Android device sends this.
                .string(B_CLIENT_ID,   "android-google")
                // SECONDS, not millis: Build.TIME is ms and the proto field is a
                // unix time in seconds (microG divides by 1000 at :124). Sending
                // millis here dates the build ~50,000 years in the future.
                .int64 (B_TIME,        android.os.Build.TIME / 1000L)
                .string(B_DEVICE,      android.os.Build.DEVICE)
                .int32 (B_SDK_VERSION, android.os.Build.VERSION.SDK_INT)
                .string(B_MODEL,       android.os.Build.MODEL)
                .string(B_MANUFACTURER,android.os.Build.MANUFACTURER)
                .string(B_PRODUCT,     android.os.Build.PRODUCT)
                .bool  (B_OTA_INSTALLED, false);

        Protobuf.Writer event = Protobuf.writer()
                .string(EV_TAG, first ? "event_log_start" : "system_update");
        if (!first) event.string(EV_VALUE, "1536,0,-1,NULL");
        event.int64(EV_TIME_MS, System.currentTimeMillis());

        Protobuf.Writer checkin = Protobuf.writer()
                .message(CK_BUILD, build)
                .int64  (CK_LAST_CHECKIN_MS, lastCheckinMs())
                .message(CK_EVENT, event)
                // cellOperator / simOperator / roaming intentionally absent --
                // see the file header. This machine has no SIM.
                .int32  (CK_USER_NUMBER, 0);

        Protobuf.Writer req = Protobuf.writer()
                .int64 (REQ_ANDROID_ID, androidId)
                .string(REQ_DIGEST, digest())
                .message(REQ_CHECKIN, checkin)
                .string(REQ_LOCALE, java.util.Locale.getDefault().toString())
                // A fresh value per request, as microG does. It is a correlation
                // id for Google's own logs, not a device identifier, and a stable
                // one would be MORE identifying rather than less.
                .int64 (REQ_LOGGING_ID, new java.security.SecureRandom().nextLong())
                .string(REQ_MAC_ADDRESS, macAddress())
                // The zero-account form: one empty string. microG's own path when
                // no account exists.
                .string(REQ_ACCOUNT_COOKIE, "")
                .string(REQ_TIME_ZONE, java.util.TimeZone.getDefault().getID());
        if (securityToken != 0) req.fixed64(REQ_SECURITY_TOKEN, securityToken);
        req.int32 (REQ_VERSION, 3)
           .string(REQ_OTA_CERT, OTA_CERT);
        String serial = serial();
        if (serial != null) req.string(REQ_SERIAL, serial);
        req.message(REQ_DEVICE_CONFIG, deviceConfig())
           .string (REQ_MAC_TYPE, "wifi")
           // fragment pairs with securityToken: 0 on a first checkin, 1 on an
           // update that carries the token (microG :142,163).
           .int32  (REQ_FRAGMENT, securityToken != 0 ? 1 : 0);
        return req.toByteArray();
    }

    private static Protobuf.Writer deviceConfig() {
        Protobuf.Writer dc = Protobuf.writer()
                // AOSP Configuration constants: TOUCHSCREEN_FINGER=3,
                // KEYBOARD_NOKEYS=1, NAVIGATION_NONAV=1. We have a pointer that
                // we deliver as touch events and no d-pad. DELIBERATE DIVERGENCE
                // (2026-10-01): since MACAPK_HOST_KEYBOARD the guest's own
                // Configuration says KEYBOARD_QWERTY / HARDKEYBOARDHIDDEN_NO (the
                // Mac's keyboard), but this GMS device registration keeps the
                // phone profile it was registered with (keyboard 1, no hard
                // keyboard): changing the values a checkin sends is a device-
                // identity change the host-keyboard lane did not measure.
                .int32(DC_TOUCH_SCREEN, 3)
                .int32(DC_KEYBOARD_TYPE, 1)
                .int32(DC_NAVIGATION, 1)
                // SCREENLAYOUT_SIZE_LARGE=3. A desktop window is a large screen.
                .int32(DC_SCREEN_LAYOUT, 3)
                .bool (DC_HAS_HARD_KEYBOARD, false)
                .bool (DC_HAS_FIVE_WAY_NAV, false)
                .int32(DC_DENSITY_DPI, densityDpi())
                // reqGlEsVersion is packed major<<16 | minor. 0x00030001 = GLES
                // 3.1, which is what our ANGLE-on-Metal path advertises.
                .int32(DC_GLES_VERSION, 0x00030001);
        for (String abi : abis()) dc.string(DC_NATIVE_PLATFORM, abi);
        int[] wh = screenPixels();
        dc.int32(DC_WIDTH_PIXELS, wh[0]).int32(DC_HEIGHT_PIXELS, wh[1]);
        dc.string(DC_LOCALE, java.util.Locale.getDefault().toString());
        // sharedLibrary / availableFeature / glExtension are omitted rather than
        // guessed. They tell Google which APKs this device can be served, and an
        // invented list would ask for builds we cannot run. An empty list is the
        // conservative, truthful answer until we enumerate them for real.
        return dc;
    }

    // ===================================================================
    // Honest device facts
    // ===================================================================

    private static String[] abis() {
        try {
            String[] a = android.os.Build.SUPPORTED_ABIS;
            if (a != null && a.length > 0) return a;
        } catch (Throwable ignored) {}
        return new String[] { "arm64-v8a" };
    }

    /** The device profile the checkin reports its screen from -- deliberately
     *  NOT Resources.getSystem(). Since MACAPK_SYSTEM_RES_FOLLOW (v1.0.1650)
     *  getSystem() follows the committed window, AOSP's behaviour, and a window
     *  resize must not change the DEVICE a checkin describes (a phone's physical
     *  screen does not change size). This is exactly the value getSystem()
     *  answered before that change -- DisplayMetrics.setToDefaults() -- so the
     *  checkin payload is byte-identical across it. OPEN (owner): whether the
     *  profile should report some other stable value.
     *  docs/fleet-canvas-games-solitaires-feudal_2026-09-26.md OPEN QUESTION 1. */
    private static android.util.DisplayMetrics checkinProfileMetrics() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        dm.setToDefaults();
        // v1.0.2187: setToDefaults now follows the launch screen (160 dpi on a
        // 1x monitor). The checkin device profile is registered once and must
        // not change with whichever screen an app last opened on, so it keeps
        // the 720x1280 @ 320 dpi profile it has always sent.
        dm.density = 2.0f;
        dm.densityDpi = android.util.DisplayMetrics.DENSITY_XHIGH;
        dm.scaledDensity = 2.0f;
        dm.xdpi = dm.ydpi = android.util.DisplayMetrics.DENSITY_XHIGH;
        return dm;
    }

    private static int densityDpi() {
        try {
            android.util.DisplayMetrics dm = checkinProfileMetrics();
            if (dm.densityDpi > 0) return dm.densityDpi;
        } catch (Throwable ignored) {}
        return 160;
    }

    private static int[] screenPixels() {
        try {
            android.util.DisplayMetrics dm = checkinProfileMetrics();
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[] { dm.widthPixels, dm.heightPixels };
            }
        } catch (Throwable ignored) {}
        return new int[] { 1080, 1920 };
    }

    /** Build.SERIAL, when we report one. AOSP has returned "unknown" to apps
     *  since API 26 (the real serial needs READ_PHONE_STATE), so an absent or
     *  "unknown" value is omitted rather than replaced. */
    private static String serial() {
        try {
            String s = android.os.Build.SERIAL;
            if (s != null && !s.isEmpty() && !"unknown".equalsIgnoreCase(s)) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    /** A stable, randomly generated wifi MAC, 12 lower-case hex chars with no
     *  separators (the format checkin wants).
     *
     *  RANDOM, NOT THIS MAC'S REAL ADDRESS, and that is the faithful choice:
     *  Android has randomised the MAC it reports per-network since Android 10,
     *  so a real device does not hand its hardware address to checkin either.
     *  STABLE, because a new address on every launch would look to Google like a
     *  new device on every launch. Persisted in the GMS store next to the rest of
     *  the device identity.
     *
     *  The first three octets are left as generated rather than borrowed from a
     *  real vendor's OUI range: claiming another manufacturer's prefix is the
     *  same class of thing as claiming their name. The locally-administered bit
     *  (bit 1 of the first octet) is SET and the multicast bit is CLEAR, which is
     *  precisely how a randomised address is supposed to be formed (IEEE 802
     *  s8.2, and what Android's own MAC randomisation emits). */
    private static String macAddress() {
        String cached = GmsStore.accountData("_device", "macAddress");
        if (cached != null && cached.length() == 12) return cached;
        byte[] b = new byte[6];
        new java.security.SecureRandom().nextBytes(b);
        b[0] = (byte) ((b[0] | 0x02) & ~0x01);   // locally administered, unicast
        StringBuilder sb = new StringBuilder(12);
        for (byte x : b) sb.append(String.format("%02x", x));
        String mac = sb.toString();
        GmsStore.putAccountData("_device", "macAddress", mac);
        return mac;
    }

    private static String digest() {
        String d = GmsStore.accountData("_device", "digest");
        return (d == null || d.isEmpty()) ? INITIAL_DIGEST : d;
    }

    private static long lastCheckinMs() {
        String s = GmsStore.accountData("_device", "lastCheckinMs");
        if (s == null) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static String shortBody(byte[] b) {
        if (b == null || b.length == 0) return "(empty body)";
        String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        // The error body is usually text; if it is not, the hex is more useful.
        boolean printable = true;
        for (int i = 0; i < Math.min(s.length(), 64); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') { printable = false; break; }
        }
        return printable ? s.substring(0, Math.min(s.length(), 400))
                         : Protobuf.hex(b, 64);
    }

    static void log(String m) {
        try { com.macapk.loader.NativeLog.i("Mac.apk.GMS", "checkin: " + m); }
        catch (Throwable t) { System.err.println("[checkin] " + m); }
    }
}
