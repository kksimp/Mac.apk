// com.macapk.gms.GoogleGmsCertificate -- the public certificate Google signs
// com.google.android.gms with (SHA-1 38918a453d07199354f8b19af05ec6562ced5788),
// which getPackageInfo reports on Win.apk for that package and for the store,
// com.android.vending, as on a phone.
//
// WHY. Every app bundles its own play-services-basement, and that copy (not
// our shadow) answers isGooglePlayServicesAvailable: it reads the gms package's
// signature and compares it byte for byte with the certificates it embeds.
// With our platform certificate there it answered SERVICE_INVALID (9), so the
// app's GoogleSignInClient chose its "no implementation" intent and failed at
// once (12500) -- the sign-in provider (GoogleSignInBroker), the location
// service and the rest were never asked. BuyMe and Waze, 2026-11-03.
//
// These are the same bytes the app's own library carries (it is a public
// certificate, no key): the claim is the one microG makes with signature
// spoofing. Nothing is signed with it and no attestation changes: Play
// Integrity / SafetyNet still fail honestly. MACAPK_GMS_GOOGLE_CERT=0 restores
// our own certificate (the "honest ceiling" in PlayServices).
package com.macapk.gms;

public final class GoogleGmsCertificate {
    private GoogleGmsCertificate() {}

    /** Whether getPackageInfo reports this certificate for gms and the store. */
    public static boolean enabled() {
        String v = System.getenv("MACAPK_GMS_GOOGLE_CERT");
        return !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    /** The certificate as a Signature, or null when switched off. */
    public static android.content.pm.Signature signature() {
        if (!enabled()) return null;
        return new android.content.pm.Signature(java.util.Base64.getDecoder().decode(DER_BASE64));
    }

    /** Whether Google's certificate signed this verified APK: as its signer,
     *  or as an earlier signer in its verified v3 rotation lineage (Play
     *  services 26.x is signed by a newer Google key, SHA-1 bd324242..., whose
     *  lineage starts at this one). False for an APK that did not verify. */
    public static boolean isGoogleSigner(com.macapk.loader.ApkVerification.Result r) {
        return r != null && r.signedBy(java.util.Base64.getDecoder().decode(DER_BASE64));
    }

    private static final String DER_BASE64 =
            "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9y" +
            "bmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4G" +
            "A1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpD" +
            "YWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9p" +
            "ZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW" +
            "0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/" +
            "8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V" +
            "+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0z" +
            "OHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOjgdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/Tgt9r45jk14alMIGmBgNVHSMEgZ4w" +
            "gZuAFMd9jMIhF1Ylmn/Tgt9r45jk14aloXikdjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UE" +
            "BxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJv" +
            "aWSCCQDC4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4" +
            "rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjwyxCUKRJNexBiGcCEyj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy" +
            "8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh5iZBqpknHf1SKMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTb" +
            "Qe3aDQsQcafEQPD+nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYld" +
            "X3WfMBEmh/9iFBDAaTCK";
}
