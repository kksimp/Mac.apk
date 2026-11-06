# org.apache.http.legacy -- Android's own Apache HttpClient

What Android ships to apps as the `org.apache.http.legacy` shared library: the
Apache HttpClient 4.0-era stack (org.apache.http.*), Android's legacy HTTP
client (android.net.http.AndroidHttpClient, RequestQueue, ...),
com.android.internal.http.multipart and org.apache.commons.codec.

- `org.apache.http.legacy.jar` -- the device artifact, pulled UNMODIFIED from an
  Android 14 emulator system image
  (`google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036.A4/12096271`) at
  `/system/framework/org.apache.http.legacy.jar` (one classes.dex), on 2026-10-30
  with the owner's OK.
- `org.apache.http.legacy.jvm.jar` -- the same classes converted to JVM bytecode
  by our DEX converter (com.macapk.dex / dex2jvm): 456 classes.

Source and licence: AOSP `platform/external/apache-http`, Apache License 2.0
(the code is Apache HttpComponents plus Android's additions; see that project's
NOTICE). Nothing here is Google-proprietary.

How it is used: `runtime/scripts/build.sh` and `debug/build_java_only.sh` compile
the framework against the JVM jar and unpack its classes into
`android-framework.jar` (never over a class we compiled). The small slice of
org.apache.http that Android keeps in its BOOT classpath (conn.ssl.*,
conn.scheme.SocketFactory / LayeredSocketFactory / HostNameResolver,
ConnectTimeoutException, ...) stays ours in runtime/java, as on a device. The 88
hand-written classes this replaced were deleted.

Why: the Play Store's Phenotype path SUBCLASSES this library's connection-pool
internals (tsccm.ConnPoolByRoute, DefaultRequestDirector, ...); only the real
library satisfies it.

Regenerate the JVM jar after a converter change:

    java -cp runtime/build/lib/android-framework.jar:runtime/build/lib/macapk-loader.jar \
        com.macapk.dex.Main thirdparty/apache-http-legacy/org.apache.http.legacy.jar \
        thirdparty/apache-http-legacy/org.apache.http.legacy.jvm.jar
