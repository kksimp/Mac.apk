// com.google.android.gms.common.security.ProviderInstallerImpl -- Google Play
// services' side of ProviderInstaller, for apps that call
// ProviderInstaller.installIfNeeded with Play services installed.
//
// On a device the client library (inside the app) checks that Play services
// is installed, then loads this class from Play services' own code
// (createPackageContext("com.google.android.gms", INCLUDE_CODE)) and calls
// insertProvider(Context): Play services' Conscrypt is put first in the
// provider list as "GmsCore_OpenSSL", and TLS from then on goes through it.
// Win.apk has no second package's code in an app's process (createPackageContext
// is the app's own context), so the class was not found and the install failed
// on every call. Google Maps registers its own "Ssl_Guard" provider, whose TLS
// refuses every connection ("Makes SSL Connections wait for security
// patches") until that install puts a real provider ahead of it: with the
// install failing, gRPC got a null SSLEngine and the map never loaded a tile.
//
// Win.apk's TLS is the JDK's (MacApkConscryptProvider has the rationale), so
// the provider put first is OpenSSLProvider over the JDK, under the name and
// class Play services' install uses. Reached only when the app's own check
// found Play services installed and Google-signed, as on a device.
package com.google.android.gms.common.security;

import android.content.Context;

import com.google.android.gms.org.conscrypt.OpenSSLProvider;

import java.security.Provider;
import java.security.Security;

public final class ProviderInstallerImpl {
    private static final String TAG = "Win.apk.ProviderInstaller";

    private ProviderInstallerImpl() {}

    public static synchronized void insertProvider(Context context) {
        Provider first = Security.getProviders().length > 0 ? Security.getProviders()[0] : null;
        if (first instanceof OpenSSLProvider) return;
        // Win.apk's own name-only alias (MacApkConscryptProvider) holds the name;
        // the real one takes its place, first.
        Security.removeProvider(OpenSSLProvider.NAME);
        OpenSSLProvider p = new OpenSSLProvider();
        Security.insertProviderAt(p, 1);
        // What an app (or the JDK) already made of the defaults was built over
        // the provider that was first then: the defaults now come from this one.
        try {
            javax.net.ssl.SSLContext def = javax.net.ssl.SSLContext.getInstance("Default", p);
            javax.net.ssl.SSLContext.setDefault(def);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(def.getSocketFactory());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "default SSLContext kept: " + t);
        }
        android.util.Log.i(TAG, "GmsCore_OpenSSL (the JDK's TLS) installed first, ahead of "
            + (first != null ? first.getName() : "nothing"));
    }

    /** Play services' install statistics: nothing to report here. */
    public static void reportRequestStats2(Context context, long start, long end) {}

    public static void reportRequestStats(Context context, long start, long end) {}
}
