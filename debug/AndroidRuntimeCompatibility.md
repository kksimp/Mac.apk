# Android class loading and reflection compatibility

`BaseDexClassLoader.pathList.dexElements` now controls the actual order used for class and resource lookup. Prepared elements do not become visible before injection; removing an element removes its unresolved classes. Dynamic DEX conversion keeps the original archive URL so resources remain available from their original bytes. Loader caches no longer bypass a changed path list. The main application loader attaches original APK resources to its initial converted-code element; after mutation their order and removal follow that element, rather than an unconditional APK fallback.

Reflective initialization of accessible, app-owned static final fields follows [ART field access](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/native/java_lang_reflect_Field.cc), through restricted JNI field IDs. Caller access checks, type checks and host-field write denial remain. The JNI path is necessary on the Android-patched JRE whose Unsafe surface omits desktop-only static field helpers.

`VMDebug` reports the real host VM's JDWP configuration. An enabled agent is conservatively reported as debugging, including while listening; this does not conceal a debugger.

Run `debug/build_java_only.sh`, then `debug/check_android_loader.sh` with a JDK 21 compiler. Set `MACAPK_TEST_JRE` and `MACAPK_TEST_PATCH` to the matching Android-patched JRE and jdk.unsupported patch directory. Tests cover path mutation, main-loader APK resource prepend/append/enumeration/removal, visibility/order/removal/identity, actual rewritten class initialization, primitive conversions, caller/host denial, and JDWP off/on. No application archive or account is required.
# 内存 DEX 与上游同步补充

反射入口 `makeInMemoryDexElements(ByteBuffer[], List<IOException>)` 保留真实源 buffer，并在实际请求类时转换当时的方法体。一个 factory 调用中的多个 DEX 共享类型元数据，正确识别位于其他 DEX 的接口；每个 Element 仍只提供自己的类。重复定义和反射调整数组顺序按当前元素顺序选择，移除的元素不会经共享元数据泄漏类。

同步上游时保留原生库按需加载、并行类加载、Jato 重写与原有反射初始化修复。

在原有 `debug/check_android_loader.sh` 外，运行 `debug/check_in_memory_dex_path.sh` 和 `debug/check_live_memory_dex.sh`。它们生成真实 DEX，覆盖 heap/direct/read-only buffer、非零位置/长度、延迟恢复的方法体、跨 DEX 接口静态方法、重复定义、定义 loader 身份、元素重排/删除、格式错误与无持久化 DEX/JAR。
