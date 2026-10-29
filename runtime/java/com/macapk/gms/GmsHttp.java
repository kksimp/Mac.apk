// com.macapk.gms.GmsHttp -- the one HTTP client for every call Mac.apk makes to
// Google on the user's behalf (checkin, auth, licensing).
//
// WHY ONE CLASS. Three verticals need the same four things and would otherwise
// each get them slightly wrong: a bounded timeout, gzip on the way out AND in,
// a body read that does not lose Google's error text on a 4xx, and a log line
// that never prints a token. Centralising also means there is exactly one place
// that knows the User-Agent, so a change to how we identify ourselves cannot
// half-land across three files.
//
// ⚠ TIMEOUTS ARE NOT OPTIONAL HERE. android.accounts.AccountManager.getAuthToken
// is documented to block, and a guest calls it from a worker thread that may be
// holding an app lock. HttpURLConnection's DEFAULT is no timeout at all -- an
// unreachable Google host would park that thread for the process lifetime, which
// on this runtime reads as the app hanging with no error. Both timeouts are set
// on every request, unconditionally.
//
// ⚠ WE READ THE ERROR STREAM. Google's /auth reports failures as HTTP 4xx with
// the REASON in the body (`Error=BadAuthentication`, `Error=NeedsBrowser`, ...).
// HttpURLConnection throws IOException from getInputStream() on 4xx and puts the
// body on getErrorStream(); a client that only reads the input stream turns
// every diagnosable failure into a bare "server returned 403" and sends the next
// session hunting the wrong thing.
package com.macapk.gms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class GmsHttp {

    /** How we identify ourselves to Google. Mac.apk, honestly -- we are not
     *  pretending to be a Pixel or the Play Store binary. If a Google endpoint
     *  turns out to require a specific client string, that is an honest
     *  stopping point to report, not a string to quietly copy. */
    public static final String USER_AGENT =
            "Mac.apk/" + version() + " (Linux; U; Android " + sdkRelease()
            + "; " + android.os.Build.MODEL + " Build/" + android.os.Build.ID + ")";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 30_000;

    private GmsHttp() {}

    /** One response. `body` is the decoded (un-gzipped) payload from whichever
     *  stream carried it, so a 4xx still has its reason text. */
    public static final class Response {
        public final int status;
        public final byte[] body;
        public final Map<String, String> headers;

        Response(int status, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
            this.headers = headers;
        }

        public boolean ok() { return status >= 200 && status < 300; }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        @Override public String toString() {
            return "HTTP " + status + " (" + body.length + " bytes)";
        }
    }

    // ===================================================================

    /** POST an application/x-www-form-urlencoded body. */
    public static Response postForm(String url, Map<String, String> form,
                                    Map<String, String> headers) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (e.getValue() == null) continue;
                if (sb.length() > 0) sb.append('&');
                sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
            }
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        if (headers != null) h.putAll(headers);
        return request("POST", url, sb.toString().getBytes(StandardCharsets.UTF_8), h, false);
    }

    /** POST raw bytes (a protobuf). `gzipBody` matches Google's checkin, which
     *  wants the request gzipped. */
    public static Response postBytes(String url, byte[] body, String contentType,
                                     Map<String, String> headers, boolean gzipBody)
            throws IOException {
        return postBytes(url, body, contentType, headers, gzipBody,
                         CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /** Same, with an explicit time budget.
     *
     *  Exists because a caller can be answering something that has its OWN
     *  deadline, and the default 15s+30s exceeds it. firebase-messaging's
     *  MessengerIpcClient gives a bound register service 30s total before it
     *  reports "Timed out waiting for response", so a register3 POST that is
     *  allowed to run 45s guarantees the app gives up first and sees a timeout
     *  rather than the clean error reply we would otherwise hand it. */
    public static Response postBytes(String url, byte[] body, String contentType,
                                     Map<String, String> headers, boolean gzipBody,
                                     int connectMs, int readMs)
            throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", contentType);
        if (headers != null) h.putAll(headers);
        return request("POST", url, body, h, gzipBody, connectMs, readMs);
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, null, headers, false);
    }

    // ===================================================================

    private static Response request(String method, String url, byte[] body,
                                    Map<String, String> headers, boolean gzipBody)
            throws IOException {
        return request(method, url, body, headers, gzipBody,
                       CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static Response request(String method, String url, byte[] body,
                                    Map<String, String> headers, boolean gzipBody,
                                    int connectMs, int readMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) com.macapk.loader.HostProxy.openConnection(new URL(url));
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            // Ask for gzip explicitly AND decode it ourselves below. If we left
            // this unset, HttpURLConnection would add it and transparently
            // decode -- but only when it added it itself, so a caller that sets
            // the header gets raw gzip bytes back. Being explicit on both sides
            // removes that footgun.
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            if (body != null) {
                byte[] payload = body;
                if (gzipBody) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(body.length / 2 + 64);
                    try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(body); }
                    payload = bo.toByteArray();
                    c.setRequestProperty("Content-Encoding", "gzip");
                }
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            }

            int status = c.getResponseCode();
            InputStream in = null;
            try {
                in = (status >= 200 && status < 400) ? c.getInputStream() : c.getErrorStream();
            } catch (IOException e) {
                in = c.getErrorStream();     // 4xx: the reason is on the error stream
            }
            byte[] raw = (in == null) ? new byte[0] : readAll(in);
            if ("gzip".equalsIgnoreCase(c.getContentEncoding()) && raw.length > 0) {
                raw = gunzip(raw);
            }

            Map<String, String> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    respHeaders.put(e.getKey().toLowerCase(java.util.Locale.ROOT),
                                    e.getValue().get(0));
                }
            }
            return new Response(status, raw, respHeaders);
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        try { in.close(); } catch (Throwable ignored) {}
        return b.toByteArray();
    }

    private static byte[] gunzip(byte[] raw) {
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            return readAll(gz);
        } catch (Throwable t) {
            // Not actually gzip despite the header, or truncated. Return the raw
            // bytes rather than nothing -- a protobuf parse failure downstream is
            // a far better diagnostic than an empty body.
            return raw;
        }
    }

    static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    private static String version() {
        String v = System.getenv("MACAPK_VERSION");
        return (v == null || v.isEmpty()) ? "1.0" : v;
    }

    private static String sdkRelease() {
        try { return android.os.Build.VERSION.RELEASE; }
        catch (Throwable t) { return "11"; }
    }

    /** A log line that is safe to leave on. Tokens, cookies and Authorization
     *  values never appear -- a log file gets shared, and a googleplay-scoped
     *  token in one is a live credential. */
    static void log(String what, String url, Response r) {
        try {
            com.macapk.loader.NativeLog.i("Mac.apk.GMS",
                what + " " + stripQuery(url) + " -> " + (r == null ? "(no response)" : r.toString()));
        } catch (Throwable ignored) {}
    }

    static String stripQuery(String url) {
        if (url == null) return "(null)";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?...";
    }
}
