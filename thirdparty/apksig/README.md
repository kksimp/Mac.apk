# apksig -- Android's APK signature verifier

`apksig-9.5.1.jar` is AOSP's `com.android.apksig` library (`platform/tools/apksig`),
the code behind the SDK's `apksigner`, UNMODIFIED, as published on Google's Maven
repository:

    https://dl.google.com/android/maven2/com/android/tools/build/apksig/9.5.1/apksig-9.5.1.jar
    SHA-256 7ae2e5980c77d853e3513074ee7c822bbdcdcde1668889d17faa7fe8bc8aa821

Licence: Apache License 2.0 (the `LICENSE` file inside the jar; the POM says the
same). Plain Java 8 bytecode, no dependencies, about 500 KB.

How it is used: `windows/scripts/build_java.sh` compiles `runtime/java` against it
and puts its classes into `macapk-loader.jar`, beside
`com.macapk.loader.ApkVerification`, which runs `ApkVerifier` on every APK whose
signing certificate Win.apk reports (an app's own, another installed app's) or
trusts (Google's packages as system apps). It checks what a device's installer
checks: the signature over the signed data with the signer's key, the content
digests over the whole ZIP, the v3 proof-of-rotation chain, v1 (JAR) signatures
for old APKs. `windows/test/java/ApkVerificationTest.java` uses its `ApkSigner`
to make the test APKs.

Why the library and not our own parser: a verifier that is wrong is worse than
none, and this is the one Android's build tools and Play use, with its own tests.

Update: download a newer version from the URL pattern above, check the jar's
`LICENSE`, replace the file and the version in `build_java.sh`.
