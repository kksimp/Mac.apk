# 包装 Application 的 Context 元数据

应用包装器可以创建真正的 Application，再附着到已存在的 base Context。ApplicationInfo 应从该真实 base 获取，避免构造阶段缓存的空记录令 nativeLibraryDir 等字段丢失。

Activity 的包身份来自已绑定的安装记录；宿主启动路由也采用该记录。Application 自行覆盖 getPackageName 不再把 Activity 构造的同包 Intent 变为空包名，也不再把同应用页面误判成跨应用启动。真实外部目标仍经过原有解析、权限与宿主启动策略。

补齐公开 OsConstants.PROT_* 字段，使预编译 Android SDK 能在运行时解析它们并使用现有 SharedMemory 映射。

AOSP 依据：[ActivityThread 创建 Activity 的 base Context](https://android.googlesource.com/platform/frameworks/base/+/c136cb0/core/java/android/app/ActivityThread.java)、[ContextWrapper 将 ApplicationInfo 委托给 base](https://android.googlesource.com/platform/frameworks/base/+/android10-release/core/java/android/content/ContextWrapper.java)。

构建 Java jars 后运行 `bash debug/check_application_context.sh`。检查包括预附着缓存、真实本机库目录、空/伪包名 wrapper、Activity 创建 Intent、外部目标解析/策略、启动记录尚未绑定时的回退，以及公开保护位的运行时解析和 SharedMemory 只读映射。

本地组合修复曾让 DREAME 通过 Application 和 Flutter 初始化；完整页面、登录及设备控制尚未验收。此 PR 不包含原生加载或内存 DEX 修复，也不改写应用自身的 getPackageName 实现。
