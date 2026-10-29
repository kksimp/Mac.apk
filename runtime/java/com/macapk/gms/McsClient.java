// com.macapk.gms.McsClient -- the MCS persistent receive channel for FCM.
//
// This is how push messages actually ARRIVE. Registration (GcmRegisterClient)
// gets a token; this holds a long-lived TLS connection to Google's Mobile
// Connection Server over which Google pushes DataMessageStanzas, which we turn
// into the com.google.android.c2dm.intent.RECEIVE broadcast the app's
// FirebaseMessagingService consumes.
//
// ⛔ SCOPE. Auth is the LoginRequest protobuf carrying the checkin
// android_id/securityToken -- REGISTRATION credentials, no attestation, no
// client TLS cert. We validate Google's server cert with the JDK default trust
// store and present nothing of our own.
//
// PROTOCOL (docs/gms-fcm-mcs-wire_2026-09-05.md Part B; TLS recipe验证 by the
// feasibility lane, 2026-09-05): TLS to mtalk.google.com:{5228,443}. The client
// writes ONE version byte (0x29) then a stream of [tag byte][varint len][proto];
// the server answers symmetrically. LoginResponse without an error field = we
// are connected. Heartbeat every 5 min (honour the server's config), 90s ack
// watchdog. Reconnect backoff 5s -> +10s to 60s -> +60s to 600s, reset on any
// inbound message. Every received message is acked (by persistent_id on the next
// login, and via a StreamAck IqStanza) or Google re-delivers it.
//
// ⚠ TLS TRAPS the feasibility lane proved and this code must honour:
//   - a raw SSLSocket does NOT verify the hostname; set
//     EndpointIdentificationAlgorithm("HTTPS") or any valid cert passes.
//   - an IP-literal address loses SNI (Google then serves invalid2.invalid);
//     connect by HOSTNAME and set ServerNames explicitly.
//   - use a PRIVATE SSLContext, never SSLContext.getDefault(): the global
//     default is mutable by guest code sharing this JVM.
//   - a bare Socket obeys guest-mutable socksProxyHost; use Proxy.NO_PROXY.
package com.macapk.gms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public final class McsClient {

    private static final String HOST = "mtalk.google.com";
    private static final int[] PORTS = { 5228, 443 };
    private static final int VERSION_BYTE = 41;   // 0x29

    // MCS tags (McsConstants).
    private static final int TAG_HEARTBEAT_PING = 0, TAG_HEARTBEAT_ACK = 1;
    private static final int TAG_LOGIN_REQUEST = 2, TAG_LOGIN_RESPONSE = 3;
    private static final int TAG_CLOSE = 4, TAG_IQ = 7, TAG_DATA = 8;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TICK_MS = 30_000;      // a deadline tick, not a disconnect
    private static final long HEARTBEAT_MS = 5 * 60_000;  // microG default; server may lower
    private static final long ACK_DEADLINE_MS = 90_000;

    /** Where a received push goes. The runtime sets this to a broadcaster; the
     *  CLI leaves it logging-only. Kept as a hook so the socket has no compile
     *  dependency on Context. */
    public interface Dispatcher { void deliver(String pkg, Map<String, String> extras); }
    private static volatile Dispatcher sDispatcher = (pkg, extras) ->
        log("push for " + pkg + " (" + extras.size() + " keys) -- no dispatcher set, dropped");

    public static void setDispatcher(Dispatcher d) { if (d != null) sDispatcher = d; }

    // ===================================================================
    // Singleton lifecycle
    // ===================================================================

    private static final Object LOCK = new Object();
    private static McsClient sInstance;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean closing = false;
    private Thread thread;
    // volatile: all three are written on the MCS thread and read from others
    // (PushRegisterService's HandlerThread via ackMessage, and stop() from any
    // caller). Barriers only -- no behaviour change, and inboundCount stays
    // incremented solely on the MCS thread so a non-atomic ++ is still correct.
    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private final Object writeLock = new Object();
    private volatile long inboundCount = 0;
    private volatile long lastAckedStreamId = 0;
    private volatile long lastPingSentMs = 0;
    private volatile long lastAckRecvMs = 0;
    private final List<String> unacked = new ArrayList<>();   // persistent_ids to ack on next login
    private long heartbeatMs = HEARTBEAT_MS;

    private McsClient() {}

    /** Start the receive channel (idempotent). Requires a checkin. */
    public static void start() {
        synchronized (LOCK) {
            if (sInstance == null) sInstance = new McsClient();
            sInstance.startInternal();
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (sInstance != null) sInstance.stopInternal();
        }
    }

    /** Ack a delivered message by persistent id (called from PushRegisterService
     *  what=2). Adds it to the next-login list and requests a StreamAck. */
    public static void ackMessage(String persistentId) {
        synchronized (LOCK) {
            if (sInstance != null && persistentId != null) sInstance.queueAck(persistentId);
        }
    }

    private void queueAck(String persistentId) {
        synchronized (unacked) { if (!unacked.contains(persistentId)) unacked.add(persistentId); }
        try { sendStreamAck(); } catch (Throwable ignored) {}
    }

    private void startInternal() {
        if (!running.compareAndSet(false, true)) return;
        closing = false;
        thread = new Thread(this::loop, "macapk-mcs");
        thread.setDaemon(true);   // no shutdown hooks in this runtime; the fd closes on exit
        thread.start();
    }

    private void stopInternal() {
        closing = true;
        running.set(false);
        closeSocket();
    }

    // ===================================================================
    // The connect/login/read loop with backoff
    // ===================================================================

    private long backoffMs = 0;

    /** When the current connection completed its handshake, or 0 while
     *  disconnected. */
    private volatile long connectedSinceMs = 0;

    /** How long a connection must HOLD before it counts as a success that earns
     *  a backoff reset.
     *
     *  v1.0.1177, and this is load-bearing. The reset used to live in the read
     *  loop as `backoffMs = 0` on ANY inbound frame, executed BEFORE the frame
     *  was dispatched. A LoginResponse carrying an error, and a server Close,
     *  are both inbound frames whose handlers then throw, so both zeroed the
     *  backoff on their way to failing and nextBackoff() returned the 5s floor
     *  again. The escalation ladder below could therefore only ever engage when
     *  BOTH ports refused outright, and every failure at or after login pinned
     *  the retry at exactly 5s forever.
     *
     *  That matters here more than it would on a phone, because every Mac.apk
     *  app is its own process sharing ONE device androidId (GmsStore.dir() is
     *  installation-wide), so a rejected or evicted login is a state two apps
     *  can hold each other in indefinitely against Google's servers.
     *
     *  microG's McsService, which this ladder's constants come from, resets
     *  AFTER its handler switch for exactly this reason. Chromium goes further
     *  and treats a connection that dies soon after a successful login as a
     *  FAILURE (kConnectionResetWindowSecs = 10s,
     *  google_apis/gcm/engine/connection_factory_impl.cc), restoring the
     *  escalated backoff rather than discarding it. We mirror Chromium: a
     *  connection earns a reset by SURVIVING, not by saying hello. */
    private static final long SUSTAINED_MS = 10_000;

    private void loop() {
        while (running.get() && !closing) {
            connectedSinceMs = 0;
            try {
                connectAndRun();
            } catch (Throwable t) {
                log("connection ended: " + t);
            }
            // Close THIS iteration's socket before sleeping. connectAndRun
            // reassigns `socket` on every attempt, so without this each retry
            // abandoned a live SSLSocket to the GC, and on the heartbeat-ack
            // path the abandoned one can still be TCP-alive, i.e. one app
            // transiently holding two MCS connections on the shared androidId.
            closeSocket();
            if (!running.get() || closing) break;
            long up = (connectedSinceMs == 0) ? 0
                    : System.currentTimeMillis() - connectedSinceMs;
            if (up >= SUSTAINED_MS) backoffMs = 0;
            long delay = nextBackoff();
            log("reconnecting in " + (delay / 1000) + "s (last connection held "
                    + (up / 1000) + "s)");
            sleep(delay);
        }
        closeSocket();
    }

    private long nextBackoff() {
        if (backoffMs == 0) backoffMs = 5_000;
        else if (backoffMs < 60_000) backoffMs += 10_000;
        else if (backoffMs < 600_000) backoffMs += 60_000;
        // JITTER. The ladder above is microG's, which is jitter-free because
        // microG holds ONE connection in one persistent process. Mac.apk runs
        // one process per app off a single shared androidId, so two apps that
        // start together retry on the same tick and stay phase-locked. Chromium
        // applies 33% for the same reason (gcm_backoff_policy.cc: "In order to
        // ensure a minimum of 20 seconds between server errors ... we have a
        // 30s +- 10s (33%) jitter"). Additive, so it never shortens a delay.
        return backoffMs + (long) (backoffMs * 0.33 * Math.random());
    }

    private void connectAndRun() throws IOException {
        long androidId = CheckinClient.ensureAndroidId();
        long securityToken = GmsStore.securityToken();
        if (androidId == 0 || securityToken == 0) throw new IOException("not checked in");

        SSLSocket s = openSocket();
        socket = s;
        InputStream in = s.getInputStream();

        // Handshake: version byte + LoginRequest, once.
        //
        // `out` is PUBLISHED INSIDE THE LOCK, together with the version byte.
        // Assigning it before taking writeLock let another thread's ack (
        // PushRegisterService -> ackMessage -> sendStreamAck, on its own
        // HandlerThread) see a non-null `out` for a brand-new socket and write an
        // IqStanza onto it BEFORE the version byte. Google would read 0x07 as our
        // version and close the stream, and the escalating backoff would then
        // stall push for minutes. Making `out` volatile (same version) did not
        // help and slightly widened it, since writeFrame re-reads the field.
        //
        // The STREAM COUNTERS reset here too, and that is a real protocol bug
        // rather than tidiness: `last_stream_id_received` means "the last frame
        // ON THIS STREAM". These were instance fields on a singleton that
        // connectAndRun re-enters on every reconnect, so from connection 2 onward
        // we reported a number far larger than the server had sent, and the
        // LoginRequest carries no stream id to resynchronise it. Both reference
        // implementations reset per connection: microG constructs a fresh
        // McsInputStream per socket (streamId = 0), and Chromium's
        // ResetStateAndBuildLoginRequest sets stream_id_in_ = 0.
        synchronized (writeLock) {
            inboundCount = 0;
            lastAckedStreamId = 0;
            out = s.getOutputStream();
            out.write(VERSION_BYTE);
            writeFrame(TAG_LOGIN_REQUEST, buildLoginRequest(androidId, securityToken));
            out.flush();
        }
        log("connected to " + socket.getInetAddress() + ", login sent");

        // Read the server's version byte, once.
        int serverVersion = in.read();
        if (serverVersion < 0) throw new IOException("server closed before version byte");

        lastPingSentMs = System.currentTimeMillis();
        lastAckRecvMs = System.currentTimeMillis();
        // The handshake is up. Whether it EARNS a backoff reset is decided in
        // loop() by how long it survives from here; see SUSTAINED_MS.
        connectedSinceMs = System.currentTimeMillis();

        while (running.get() && !closing) {
            Integer tag = readTagWithDeadline(in);
            if (tag == null) {           // a read tick: evaluate heartbeat
                maybeHeartbeat();
                continue;
            }
            long len = readVarint(in);
            byte[] body = readN(in, (int) len);
            inboundCount++;
            handle(tag, body);
        }
    }

    private SSLSocket openSocket() throws IOException {
        IOException last = null;
        for (int port : PORTS) {
            Socket plain = null;
            boolean complete = false;
            try (com.macapk.loader.HostCrypto.Scope crypto = com.macapk.loader.HostCrypto.use()) {
                plain = new Socket(Proxy.NO_PROXY);
                plain.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS);
                plain.setKeepAlive(true);
                plain.setTcpNoDelay(true);
                SSLSocket s = (SSLSocket) factory().createSocket(plain, HOST, port, true);
                SSLParameters sp = s.getSSLParameters();
                sp.setEndpointIdentificationAlgorithm("HTTPS");   // raw SSLSocket does NOT verify the name
                sp.setServerNames(List.of(new SNIHostName(HOST)));
                s.setSSLParameters(sp);
                s.setSoTimeout(CONNECT_TIMEOUT_MS);
                s.startHandshake();                               // a timeout here is fatal
                s.setSoTimeout(READ_TICK_MS);
                complete = true;
                return s;
            } catch (IOException e) {
                last = e;
                log("port " + port + " failed: " + e);
            } finally {
                if (!complete && plain != null) try { plain.close(); } catch (IOException ignored) {}
            }
        }
        throw (last != null) ? last : new IOException("no MCS port answered");
    }

    /** A private SSLContext: the JVM-global default is mutable by guest code. */
    private static volatile SSLSocketFactory sFactory;
    private static SSLSocketFactory factory() throws IOException {
        if (sFactory != null) return sFactory;
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);   // the JDK cacerts (has GTS Root R1)
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);  // no client cert
            sFactory = ctx.getSocketFactory();
            return sFactory;
        } catch (Exception e) {
            throw new IOException("could not build the TLS context: " + e);
        }
    }

    // ===================================================================
    // Message handling
    // ===================================================================

    private void handle(int tag, byte[] body) throws IOException {
        switch (tag) {
            case TAG_LOGIN_RESPONSE: {
                // error field (3) present => login failed.
                byte[] err = Protobuf.path(body, 3);
                if (err != null) {
                    int code = (int) Protobuf.scalar(err, 1, -1);
                    String msg = Protobuf.pathString(err, 2);
                    throw new IOException("login rejected: code=" + code + " " + msg
                        + " (checkin creds may be stale)");
                }
                // heartbeat_config (7) -> interval_ms (3)
                byte[] hc = Protobuf.path(body, 7);
                if (hc != null) {
                    long iv = Protobuf.scalar(hc, 3, 0);
                    if (iv >= 30_000) heartbeatMs = iv;
                }
                synchronized (unacked) { unacked.clear(); }   // acked by being in this login
                log("LOGGED IN (heartbeat " + (heartbeatMs / 1000) + "s)");
                break;
            }
            case TAG_DATA:
                handleData(body);
                break;
            case TAG_HEARTBEAT_PING:
                sendHeartbeatAck(body);
                break;
            case TAG_HEARTBEAT_ACK:
                lastAckRecvMs = System.currentTimeMillis();
                break;
            case TAG_CLOSE:
                throw new IOException("server sent Close");
            case TAG_IQ:
                // StreamAck / SelectiveAck from the server; nothing to do.
                break;
            default:
                log("unknown tag " + tag + " (" + body.length + " bytes), skipped");
        }
    }

    /** DataMessageStanza -> the RECEIVE broadcast extras. */
    private void handleData(byte[] body) {
        try {
            Map<String, String> extras = new LinkedHashMap<>();
            String from = Protobuf.pathString(body, 3);
            String category = Protobuf.pathString(body, 5);   // the target package
            String persistentId = Protobuf.pathString(body, 9);
            String messageId = Protobuf.pathString(body, 2);
            String collapseKey = Protobuf.pathString(body, 6);
            if (from != null) extras.put("from", from);
            extras.put("google.message_id", persistentId != null ? persistentId : messageId);
            if (collapseKey != null) extras.put("collapse_key", collapseKey);
            long sent = Protobuf.scalar(body, 18, 0);
            if (sent != 0) extras.put("google.sent_time", Long.toString(sent * 1000));
            long ttl = Protobuf.scalar(body, 17, 0);
            if (ttl != 0) extras.put("google.ttl", Long.toString(ttl));

            // app_data (7): repeated {key=1, value=2}. Every pair becomes an extra.
            Protobuf.Reader r = new Protobuf.Reader(body);
            int t;
            while ((t = r.readTag()) != -1) {
                if (Protobuf.Reader.fieldOf(t) == 7 && Protobuf.Reader.wireOf(t) == Protobuf.WIRE_LEN) {
                    byte[] kv = r.bytes();
                    String k = Protobuf.pathString(kv, 1);
                    String v = Protobuf.pathString(kv, 2);
                    if (k != null && v != null && !"from".equals(k)) extras.put(k, v);
                } else {
                    r.skip(Protobuf.Reader.wireOf(t));
                }
            }

            if (category == null) { log("data message with no category, dropped"); return; }

            // Do not DISPATCH a message that is not ours. Every Mac.apk app
            // process logs into MCS with the SAME device androidId
            // (GmsStore.dir() is installation-wide), so Google multiplexes the
            // messages for EVERY registered package down whichever socket is up,
            // and `category` is the routing field. Without this check app A
            // handed app B's payload to its own dispatcher, which then dropped
            // it on the setPackage filter -- so the data was briefly visible to
            // the wrong app for no benefit.
            //
            // ⚠ THIS DOES NOT RELIABLY PRESERVE THE MESSAGE FOR APP B. THE
            // STREAM ACK IS CUMULATIVE, NOT PER-MESSAGE: `inboundCount++` above
            // runs before handle(), and sendStreamAck / both heartbeat paths
            // send that counter as last_stream_id_received, so the next
            // heartbeat (or the next ack for one of OUR messages) acknowledges
            // this frame too.
            //
            // It is NOT inert either, and two earlier versions of this comment
            // were wrong in OPPOSITE directions -- first claiming the message was
            // preserved, then that suppression was impossible. What the return
            // actually buys is a WINDOW: queueAck would stream-ack immediately
            // AND add the id to `unacked`, which is the ONLY thing a reconnect
            // reports (buildLoginRequest sends received_persistent_id and carries
            // no stream id at all). Skipping it leaves the frame unacked on BOTH
            // channels until the next heartbeat -- default 5 min, and the server
            // may raise it to 15-28 min -- so a socket drop inside that window
            // leaves the message in Google's queue for redelivery.
            //
            // The real constraint is the SHARED IDENTITY: one androidId means one
            // server-side queue, and the server picks a socket, not a package.
            // Fixing it properly means a per-app checkin identity, which a device
            // does not have (android_id is per-DEVICE and also backs
            // Settings.Secure.ANDROID_ID and the licensing X-DFE-Device-Id), so it
            // is a real design decision and not a local patch. Until then, a push
            // for an app that is not running can be consumed by one that is.
            String self = null;
            try {
                android.app.Application app = android.app.Application.getInstance();
                if (app != null) self = app.getPackageName();
            } catch (Throwable ignored) {}
            if (self != null && !self.equals(category)) {
                log("push for " + category + " is not ours (" + self
                    + "), not dispatching -- NOTE the cumulative stream ack still"
                    + " consumes it, see the comment above");
                return;
            }

            if (persistentId != null) queueAck(persistentId);
            log("push for " + category + " id=" + extras.get("google.message_id"));
            sDispatcher.deliver(category, extras);
        } catch (Throwable e) {
            log("could not parse a data message: " + e);
        }
    }

    // ===================================================================
    // Outbound frames
    // ===================================================================

    private byte[] buildLoginRequest(long androidId, long securityToken) {
        String aidDec = Long.toString(androidId);         // SIGNED decimal (microG)
        Protobuf.Writer w = Protobuf.writer()
                .string(1, "android-" + sdkInt())         // id
                .string(2, "mcs.android.com")             // domain
                .string(3, aidDec)                        // user
                .string(4, aidDec)                        // resource
                .string(5, Long.toString(securityToken))  // auth_token
                .string(6, "android-" + Long.toHexString(androidId))  // device_id
                .message(8, Protobuf.writer().string(1, "new_vc").string(2, "1"))  // setting
                .bool(12, false)                          // adaptive_heartbeat
                .bool(14, true)                           // use_rmq2 (must)
                .int32(16, 2)                             // auth_service = ANDROID_ID
                .int32(17, 1);                            // network_type
        synchronized (unacked) {
            for (String id : unacked) w.string(10, id);   // received_persistent_id
        }
        return w.toByteArray();
    }

    private void sendHeartbeatPing() throws IOException {
        Protobuf.Writer w = Protobuf.writer();
        if (inboundCount > lastAckedStreamId) w.int32(2, (int) inboundCount);
        writeLocked(TAG_HEARTBEAT_PING, w.toByteArray());
        lastPingSentMs = System.currentTimeMillis();
    }

    private void sendHeartbeatAck(byte[] ping) throws IOException {
        long status = Protobuf.scalar(ping, 3, 0);
        Protobuf.Writer w = Protobuf.writer();
        if (inboundCount > lastAckedStreamId) w.int32(2, (int) inboundCount);
        if (status != 0) w.int64(3, status);
        writeLocked(TAG_HEARTBEAT_ACK, w.toByteArray());
    }

    /** StreamAck: an IqStanza type=SET with extension id 13. Acks received
     *  messages at the stream level so Google stops re-sending them. */
    private void sendStreamAck() throws IOException {
        if (out == null) return;
        Protobuf.Writer ext = Protobuf.writer().int32(1, 13).bytes(2, new byte[0]);
        Protobuf.Writer iq = Protobuf.writer()
                .int32(2, 1)          // type = SET
                .string(3, "")        // id
                .message(7, ext)      // extension
                .int32(12, 0);        // status
        if (inboundCount <= lastAckedStreamId) return;   // nothing new to ack
        long acking = inboundCount;
        iq.int32(10, (int) acking);
        // Record only what was actually WRITTEN. writeLocked no-ops when `out`
        // is null inside the lock (a socket that just closed), and advancing the
        // marker regardless recorded a skipped write as sent -- after which the
        // heartbeats omit field 2 until a new frame arrives, so the ack is lost
        // rather than retried.
        if (writeLocked(TAG_IQ, iq.toByteArray())) lastAckedStreamId = acking;
    }

    private void maybeHeartbeat() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastAckRecvMs > heartbeatMs + ACK_DEADLINE_MS
                && lastPingSentMs > lastAckRecvMs) {
            throw new IOException("heartbeat ack overdue -- link is dead");
        }
        if (now - lastPingSentMs >= heartbeatMs) sendHeartbeatPing();
    }

    /** @return true if the frame was actually written; false when the socket is
     *  already gone. Callers that record "I have acked up to N" must key off the
     *  return, not off having called this. */
    private boolean writeLocked(int tag, byte[] body) throws IOException {
        synchronized (writeLock) {
            if (out == null) return false;
            writeFrame(tag, body);
            out.flush();
            return true;
        }
    }

    private void writeFrame(int tag, byte[] body) throws IOException {
        out.write(tag);
        writeVarint(out, body.length);
        out.write(body);
    }

    // ===================================================================
    // Varint framing
    // ===================================================================

    /** Read a tag byte, or null on a read-timeout tick. */
    private Integer readTagWithDeadline(InputStream in) throws IOException {
        try {
            int b = in.read();
            if (b < 0) throw new IOException("stream closed");
            return b & 0xFF;
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    private static long readVarint(InputStream in) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) throw new IOException("stream closed mid-varint");
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("varint too long");
    }

    private static void writeVarint(OutputStream out, int v) throws IOException {
        long u = v & 0xFFFFFFFFL;
        while (true) {
            if ((u & ~0x7FL) == 0) { out.write((int) u); return; }
            out.write(((int) u & 0x7F) | 0x80);
            u >>>= 7;
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        if (n < 0 || n > 32 * 1024 * 1024) throw new IOException("absurd frame length " + n);
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("stream closed mid-frame");
            off += r;
        }
        return b;
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        // `out` under writeLock, which is what writeFrame null-checks it behind.
        // Nulling it unguarded raced the per-iteration close added at v1.0.1177:
        // an ack from PushRegisterService could pass the null check and then NPE
        // on the write. Callers swallow it, so the visible cost was a silently
        // dropped StreamAck rather than a crash, which is worse to diagnose.
        synchronized (writeLock) { out = null; }
    }

    private static int sdkInt() {
        try { return android.os.Build.VERSION.SDK_INT; } catch (Throwable t) { return 30; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void log(String m) {
        try { com.macapk.loader.NativeLog.i("Mac.apk.GMS", "mcs: " + m); }
        catch (Throwable t) { System.err.println("[mcs] " + m); }
    }

    /**
     * CONNECT + LOGIN ONLY, synchronously, for the CLI to prove the socket +
     * framing + auth against Google's live server without running the full
     * receive loop. @return null on success, else the failure.
     */
    public static String testLogin() {
        try {
            long androidId = CheckinClient.ensureAndroidId();
            long securityToken = GmsStore.securityToken();
            if (androidId == 0 || securityToken == 0) return "not checked in";
            McsClient c = new McsClient();
            SSLSocket s = c.openSocket();
            OutputStream o = s.getOutputStream();
            InputStream in = s.getInputStream();
            synchronized (c.writeLock) {
                c.out = o;
                o.write(VERSION_BYTE);
                c.writeFrame(TAG_LOGIN_REQUEST, c.buildLoginRequest(androidId, securityToken));
                o.flush();
            }
            s.setSoTimeout(15_000);
            int serverVersion = in.read();
            if (serverVersion < 0) { s.close(); return "server closed before version byte"; }
            int tag = in.read();
            long len = readVarint(in);
            byte[] body = readN(in, (int) len);
            s.close();
            if (tag == TAG_LOGIN_RESPONSE) {
                byte[] err = Protobuf.path(body, 3);
                if (err != null) {
                    return "login rejected: code=" + Protobuf.scalar(err, 1, -1)
                        + " " + Protobuf.pathString(err, 2);
                }
                return null;   // success
            }
            return "unexpected first message tag=" + tag;
        } catch (Throwable t) {
            return "" + t;
        }
    }
}
