# Android class loading and reflection compatibility

`BaseDexClassLoader.pathList.dexElements` now controls the actual order used for class and resource lookup. Prepared elements do not become visible before injection; removing an element removes its unresolved classes. Dynamic DEX conversion keeps the original archive URL so resources remain available from their original bytes. Loader caches no longer bypass a changed path list. The main application loader attaches original APK resources to its initial converted-code element; after mutation their order and removal follow that element, rather than an unconditional APK fallback.

Reflective initialization of accessible, app-owned static final fields follows [ART field access](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/native/java_lang_reflect_Field.cc), through restricted JNI field IDs. Caller access checks, type checks and host-field write denial remain. The JNI path is necessary on the Android-patched JRE whose Unsafe surface omits desktop-only static field helpers.

`VMDebug` reports the real host VM's JDWP configuration. An enabled agent is conservatively reported as debugging, including while listening; this does not conceal a debugger.

Run `debug/build_java_only.sh`, then `debug/check_android_loader.sh` with a JDK 21 compiler. Set `MACAPK_TEST_JRE` and `MACAPK_TEST_PATCH` to the matching Android-patched JRE and jdk.unsupported patch directory. Tests cover path mutation, main-loader APK resource prepend/append/enumeration/removal, visibility/order/removal/identity, actual rewritten class initialization, primitive conversions, caller/host denial, and JDWP off/on. No application archive or account is required.
