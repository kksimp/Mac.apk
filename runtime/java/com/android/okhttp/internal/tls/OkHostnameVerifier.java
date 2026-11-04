// com.android.okhttp.internal.tls.OkHostnameVerifier -- the hostname verifier
// that real Android returns from HttpsURLConnection.getDefaultHostnameVerifier().
//
// WHY THIS EXISTS (root cause, byte-verified 2026-11-01). Real Android's
// libcore wires the default HTTPS hostname verifier to
// com.android.okhttp.internal.tls.OkHostnameVerifier.INSTANCE (it reflects
// `OkHostnameVerifier.INSTANCE` in a NoPreloadHolder, see AOSP
// libcore/ojluni/src/main/java/javax/net/ssl/HttpsURLConnection.java). That
// verifier performs real RFC 2818 / 6125 hostname matching. The host OpenJDK's
// javax.net.ssl.HttpsURLConnection instead defaults to its own
// HttpsURLConnection$DefaultHostnameVerifier, whose verify() returns FALSE
// unconditionally -- the JDK does endpoint identification internally in
// sun.net.www.protocol.https and only consults the default verifier as a
// never-approving fallback. Measured on JDK 21.0.11:
//     getDefaultHostnameVerifier().getClass()
//       == javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier
//     verify("android.googleapis.com", <any session>) == false
//
// That breaks every Android caller that verifies a raw SSLSocket by hand
// through getDefaultHostnameVerifier(). GMS's SSLCertSocketFactory
// (defpackage.fvbv, com.google.android.gms@264131029) does exactly that:
//     SSLSession s = sslSocket.getSession();
//     if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s))
//         throw new SSLPeerUnverifiedException("Cannot verify hostname: " + host);
// so on the host JDK its gRPC/okhttp TLS to android.googleapis.com (the
// DroidGuard / VerdictProvider channel, and more) fails with
// "Cannot verify hostname" even after a correct BoringSSL handshake through
// GMS's own bundled Conscrypt. Mac.apk's Boot installs INSTANCE as the JVM
// default so that call answers the way a device answers.
//
// FAITHFULNESS. The algorithm below mirrors AOSP's own OkHostnameVerifier
// (external/okhttp, the non-strict INSTANCE), including the two AOSP
// deviations from upstream square/okhttp 2.7.5:
//   - the Common-Name fallback is REMOVED (AOSP b/70278814): a DNS host is
//     matched against subjectAltName dNSName entries ONLY, never the CN;
//   - hostname and pattern must be printable ASCII (AOSP b/171980069).
// Detection is preserved: a genuinely wrong or mismatched certificate still
// fails here, exactly as on a device. We are not bypassing a check, we are
// no longer FALSELY failing one because the host JDK's placeholder default
// rejects everything.
package com.android.okhttp.internal.tls;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/** A HostnameVerifier consistent with RFC 2818. */
public final class OkHostnameVerifier implements HostnameVerifier {

    /** The non-strict default, the one AOSP's HttpsURLConnection installs. */
    public static final OkHostnameVerifier INSTANCE = new OkHostnameVerifier(false);

    /** Quick check that a host string is an IP address (v4 or v6). */
    private static final Pattern VERIFY_AS_IP_ADDRESS =
            Pattern.compile("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)");

    private static final int ALT_DNS_NAME = 2;
    private static final int ALT_IPA_NAME = 7;

    /** When strict, a single-label wildcard pattern such as "*.com" is rejected. */
    private final boolean strict;

    private OkHostnameVerifier(boolean strict) {
        this.strict = strict;
    }

    /** AOSP exposes a strict variant via HttpsURLConnection.getStrictHostnameVerifier(). */
    public static OkHostnameVerifier strictInstance() {
        return new OkHostnameVerifier(true);
    }

    @Override
    public boolean verify(String host, SSLSession session) {
        try {
            Certificate[] certificates = session.getPeerCertificates();
            return verify(host, (X509Certificate) certificates[0]);
        } catch (SSLException e) {
            return false;
        }
    }

    public boolean verify(String host, X509Certificate certificate) {
        return VERIFY_AS_IP_ADDRESS.matcher(host).matches()
                ? verifyIpAddress(host, certificate)
                : verifyHostName(host, certificate);
    }

    /** Returns true if {@code certificate} matches {@code ipAddress}. */
    private boolean verifyIpAddress(String ipAddress, X509Certificate certificate) {
        List<String> altNames = getSubjectAltNames(certificate, ALT_IPA_NAME);
        for (int i = 0, size = altNames.size(); i < size; i++) {
            if (ipAddress.equalsIgnoreCase(altNames.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if {@code certificate} matches {@code hostName}. */
    private boolean verifyHostName(String hostName, X509Certificate certificate) {
        // Android-added: reject non-printable-ASCII hosts before lowercasing (b/171980069).
        if (!isPrintableAsciiString(hostName)) {
            return false;
        }
        hostName = hostName.toLowerCase(Locale.US);
        List<String> altNames = getSubjectAltNames(certificate, ALT_DNS_NAME);
        for (int i = 0, size = altNames.size(); i < size; i++) {
            if (verifyHostName(hostName, altNames.get(i))) {
                return true;
            }
        }
        // Android-removed: Common-Name fallback (b/70278814). No DNS SAN match == no match.
        return false;
    }

    public static List<String> allSubjectAltNames(X509Certificate certificate) {
        List<String> altIpaNames = getSubjectAltNames(certificate, ALT_IPA_NAME);
        List<String> altDnsNames = getSubjectAltNames(certificate, ALT_DNS_NAME);
        List<String> result = new ArrayList<String>(altIpaNames.size() + altDnsNames.size());
        result.addAll(altIpaNames);
        result.addAll(altDnsNames);
        return result;
    }

    private static List<String> getSubjectAltNames(X509Certificate certificate, int type) {
        List<String> result = new ArrayList<String>();
        try {
            java.util.Collection<?> subjectAltNames = certificate.getSubjectAlternativeNames();
            if (subjectAltNames == null) {
                return result;
            }
            for (Object subjectAltName : subjectAltNames) {
                List<?> entry = (List<?>) subjectAltName;
                if (entry == null || entry.size() < 2) {
                    continue;
                }
                Integer altNameType = (Integer) entry.get(0);
                if (altNameType == null || altNameType != type) {
                    continue;
                }
                String altName = (String) entry.get(1);
                if (altName != null) {
                    result.add(altName);
                }
            }
            return result;
        } catch (CertificateParsingException e) {
            return new ArrayList<String>();
        }
    }

    /**
     * Returns true if {@code hostName} matches the name or pattern {@code pattern}.
     *
     * @param hostName lower-case host name.
     * @param pattern domain name pattern from certificate. May be a wildcard
     *     pattern such as {@code *.android.com}.
     */
    public boolean verifyHostName(String hostName, String pattern) {
        // Basic sanity checks.
        if ((hostName == null) || (hostName.length() == 0) || (hostName.startsWith("."))
                || (hostName.endsWith(".."))) {
            // Invalid domain name.
            return false;
        }
        if ((pattern == null) || (pattern.length() == 0) || (pattern.startsWith("."))
                || (pattern.endsWith(".."))) {
            // Invalid pattern/domain name.
            return false;
        }

        // Normalize hostName and pattern by turning them into absolute domain names if they are not
        // yet absolute. This is needed because server certificates do not normally contain absolute
        // names or patterns, but they should be treated as absolute. At the same time, any hostName
        // presented to this method should also be treated as absolute for the purposes of matching
        // to the server certificate.
        //   www.android.com  matches www.android.com
        //   www.android.com  matches www.android.com.
        //   www.android.com. matches www.android.com.
        //   www.android.com. matches www.android.com
        if (!hostName.endsWith(".")) {
            hostName += '.';
        }
        if (!pattern.endsWith(".")) {
            pattern += '.';
        }
        // hostName and pattern are now absolute domain names.

        // Android-added: non-printable-ASCII patterns never match (b/171980069).
        if (!isPrintableAsciiString(pattern)) {
            return false;
        }

        pattern = pattern.toLowerCase(Locale.US);
        // hostName and pattern are now in lower case -- domain names are case-insensitive.

        if (!pattern.contains("*")) {
            // Not a wildcard pattern -- hostName and pattern must match exactly.
            return hostName.equals(pattern);
        }
        // Wildcard pattern

        // WILDCARD PATTERN RULES:
        // 1. Asterisk (*) is only permitted in the left-most domain name label and must be the
        //    only character in that label (i.e., must match the whole left-most label).
        //    For example, *.example.com is permitted, while *a.example.com, a*.example.com,
        //    a*b.example.com, a.*.example.com are not permitted.
        // 2. Asterisk (*) cannot match across domain name labels.
        //    For example, *.example.com matches test.example.com but does not match
        //    sub.test.example.com.
        // 3. Wildcard patterns for single-label domain names are not permitted (e.g., *.com) --
        //    unless using {@code strict} mode.

        if ((!pattern.startsWith("*.")) || (pattern.indexOf('*', 1) != -1)) {
            // Asterisk (*) is only permitted in the left-most domain name label and must be the only
            // character in that label.
            return false;
        }

        // Optimization: check whether hostName is too short to match the pattern. hostName must be at
        // least as long as the pattern because asterisk must match the whole left-most label and
        // hostName starts with a non-empty label. Thus, asterisk has to match one or more characters.
        if (hostName.length() < pattern.length()) {
            // hostName too short to match the pattern.
            return false;
        }

        if ("*.".equals(pattern)) {
            // Wildcard pattern for single-label domain name -- not permitted.
            return false;
        }

        // hostName must end with the region of pattern following the asterisk.
        String suffix = pattern.substring(1);

        // Optimization: check whether hostName ends with ".suffix". If it does not, then hostName
        // does not match the pattern.
        if (!hostName.endsWith(suffix)) {
            // hostName does not end with the suffix
            return false;
        }

        // Check that asterisk did not match across domain name labels.
        int suffixStartIndexInHostName = hostName.length() - suffix.length();
        if ((suffixStartIndexInHostName > 0)
                && (hostName.lastIndexOf('.', suffixStartIndexInHostName - 1) != -1)) {
            // Asterisk is matching across domain name labels -- not permitted.
            return false;
        }

        if (strict) {
            // Wildcard pattern for single-label domain name is not permitted in strict mode.
            String domainPart = pattern.substring(2, pattern.length() - 1);
            if (domainPart.indexOf('.') < 0) {
                return false;
            }
        }

        // hostName matches pattern
        return true;
    }

    /** Rejects any string containing a character that is not printable ASCII (space < c < DEL). */
    private static boolean isPrintableAsciiString(String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c >= '\u007f') {
                return false;
            }
        }
        return true;
    }
}
