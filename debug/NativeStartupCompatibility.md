# 原生加载与 JNI 启动兼容性

本组改动修复 ELF、Bionic 与 HotSpot 之间的真实调用契约，不依赖应用包名或应用二进制偏移。

- 翻译包装库通过 `dlsym(handle, name)` 查找继承的宿主符号时，采用普通 ELF 导入所用的 Bionic 适配器，避免把 Bionic pthread 对象交给 Darwin 实现。包装库自身导出和普通宿主句柄保留原语义。
- ELF 构造阶段可以发布真正的 `JNI_OnLoad`。包装入口在构造完成后查询并调用真实 hook；只有实际不存在时才采用 JNI 规定的 1.1 默认版本。
- 已通过现有 `MACAPK_GUEST_RWX=1` 明确授权的代码区，也允许 libc 的 memcpy/memmove/memset 完成写入。写入结束恢复 RX 并刷新指令缓存；未授权目的区、撤销授权、无效源地址仍产生真实错误。恢复 RX 失败会撤销访问并终止。
- 对原有内联 SVC 兼容入口中的 faccessat/openat，仅在系统保留原参数时调用既有 Bionic 文件适配器；参数已被系统覆盖时返回 ENOSYS，保留真实文件与 errno 语义。
- 移除从 HotSpot JNI 函数内部改 PC/LR 的信号恢复。原先 GetString 地址窗口也会误认相邻 GetArrayLength，跳过 JVM 线程状态恢复并阻塞 safepoint。JNI 入口之前的字符串句柄检查和 HotSpot 正常信号链保留。

构建 `runtime/translator` 后执行：

```bash
bash debug/check_wrapper_dlsym.sh
bash debug/check_deferred_jni_onload.sh
bash debug/check_guest_rwx_copy.sh
bash debug/check_inline_file_syscalls.sh
MACAPK_TEST_JVM=/path/to/installed-runtime/Resources/jre/lib/server/libjvm.dylib \
  bash debug/check_jni_signal_recovery.sh
```

这些检查生成本机 ELF/JNI fixture，无需下载应用。原生构建目录需要可加载的 ANGLE 依赖；可将 `thirdparty/angle-prebuilt/lib/` 的 dylib 复制到该构建目录，或使用已完整打包的目录设置 `MACAPK_TEST_NATIVE` / `MACAPK_TEST_SHIM`。

独立 JNI 回归在真实 Android-patched JRE 21 上覆盖无效数组的真实 VM 错误、有效字符串/数组、16 次 GC 和 128 次隐式 Java NPE。代码内存回归覆盖复制、重叠移动、填充、授权边界及恢复失败。

此前包含本组修复的本地组合中，小米已由用户确认进入首页；DREAME 完成 Application、Splash 和 Flutter 初始化，但可用画面尚未验收。它们同时需要独立的 Java 类加载/Context 修复；此 PR 不声称单独修复完整登录或设备操作。
