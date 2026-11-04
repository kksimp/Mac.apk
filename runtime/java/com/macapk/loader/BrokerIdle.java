// When the system broker may stop. The broker (SystemServer) hosts Google Play
// services, the Services Framework and whatever else it spawned as service
// hosts; each is a JVM. On a phone they live as long as the device is on, but a
// Mac is not a phone, so with "Use Google GMS" on they run only while an Android
// app is open: the broker counts the processes it did NOT spawn (the apps the
// user opened), and once there have been none for a grace period it stops what
// it spawned and exits. launchd starts it again on the next app's lookup.
//
// This class is only the policy, free of any Android or process state, so a gate
// can drive it with a clock of its own (debug/broker_idle_gate.sh).
package com.macapk.loader;

final class BrokerIdle {
    private BrokerIdle() {}

    /** Seconds with no open app before the broker stops. Short enough that the
     *  services do not idle for long, long enough that closing one app and
     *  opening another does not pay a full Play services boot in between. */
    static final long DEFAULT_GRACE_S = 60;

    /** Whether this broker stops when idle. Default: on for the product (the
     *  installed-apps registry), off for a development/gate broker, which is
     *  told its packages by MACAPK_BROKER_APKS and has no app to keep it alive.
     *  MACAPK_BROKER_IDLE_EXIT=1/0 overrides either way (0 = the old 24/7). */
    static boolean enabled(boolean product) {
        String e = System.getenv("MACAPK_BROKER_IDLE_EXIT");
        if (e != null && !e.isEmpty()) return "1".equals(e);
        return product;
    }

    static long graceMs() {
        String g = System.getenv("MACAPK_BROKER_IDLE_GRACE_S");
        if (g != null && !g.isEmpty()) {
            try { return Math.max(1, Long.parseLong(g.trim())) * 1000L; } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_GRACE_S * 1000L;
    }

    /** Before any app has attached the broker cannot tell "nobody is coming" from
     *  "the app that started me is still launching" (first Play launch: cold
     *  translation and conversion, minutes; measured: a 60 s grace killed the
     *  broker 4 times). So it waits longer: MACAPK_BROKER_IDLE_FIRST_S, default 15
     *  minutes, never less than the normal grace. */
    static long firstAppGraceMs(long graceMs) {
        long v = 15 * 60_000L;
        String g = System.getenv("MACAPK_BROKER_IDLE_FIRST_S");
        if (g != null && !g.isEmpty()) {
            try { v = Long.parseLong(g.trim()) * 1000L; } catch (NumberFormatException ignored) {}
        }
        return Math.max(graceMs, v);
    }

    /** `clients` open apps now; the last time there was one (or the broker's
     *  start, if none has come yet) was `lastClientMs`. */
    static boolean shouldStop(int clients, long lastClientMs, long nowMs, long graceMs) {
        return clients == 0 && nowMs - lastClientMs >= graceMs;
    }
}
