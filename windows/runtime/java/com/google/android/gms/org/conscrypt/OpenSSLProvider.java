// com.google.android.gms.org.conscrypt.OpenSSLProvider -- the provider Google
// Play services' ProviderInstaller puts first ("GmsCore_OpenSSL"), here over
// the JDK's TLS and crypto (ProviderInstallerImpl has why). Its class name is
// what TLS stacks look for in the provider list: gRPC's Platform picks the
// first provider whose class is one of Android's Conscrypt providers (or Play
// services') and treats it as Conscrypt.
package com.google.android.gms.org.conscrypt;

import android.security.MacApkConscryptProvider;

import java.security.Provider;

public final class OpenSSLProvider extends Provider {
    public static final String NAME = "GmsCore_OpenSSL";

    public OpenSSLProvider() {
        super(NAME, "1.0", "Win.apk: Play services' provider name over the JDK's JSSE/JCE");
        MacApkConscryptProvider.copyPlatformServices(this);
        android.security.MacApkConscryptTls.register(this);
    }
}
