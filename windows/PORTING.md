# WinARM.apk porting notes

Why the Windows on Arm port is built the way it is, the facts it depends on,
and its known gaps. [`README.md`](README.md) covers building and running.

## 1. Scope

WinARM.apk runs Android apps on Windows 11 on Arm as ordinary Windows
processes, with no emulator and no virtual machine, by porting the
[Mac.apk](https://github.com/kksimp/Mac.apk) runtime. It is based on
[israel2176/win.apk](https://github.com/israel2176/win.apk), which also targets
x64 PCs. Licensed under AGPL-3.0, like upstream.

Out of scope: x64 PCs (use israel2176/win.apk there), apps whose native code is only
`x86_64` or only `armeabi-v7a` (Windows on Arm CPUs have no AArch32), and
libraries that use x18 as scratch (section 3, D6).

## 2. From Mac.apk to Windows on Arm

| Upstream part | Location | On Windows on Arm |
|---|---|---|
| Android framework, DEX-to-JVM converter, boot loader | `runtime/java/` | Used as is on a stock HotSpot, with the hooks listed in `README.md` |
| Native runtime (JVM host, framework natives, Cocoa window, ANGLE glue) | `runtime/src/` | Replaced by `windows/host/`; two files are compiled from upstream (the GL bridges and `native_sqlite.cpp`) |
| arm64 ELF loader and Bionic shim | `runtime/translator/` | Replaced by `windows/src/`; upstream's is the behavioural reference |
| Swift launcher | `launcher/` | Replaced by `windows/launcher/` |

On Apple Silicon an app's arm64 `.so` runs directly. The same holds here:
Windows ARM64 and AAPCS64 agree on fixed arguments, so host functions bind
directly to a library's imports and HotSpot calls guest natives through thin
thunks. What differs from the Mac:

- **x18** is the TEB pointer on Windows on Arm; Android code may use it as a
  scratch register. Libraries that write it are refused at load
  (`include/winapk/arm64_x18.h`).
- **TPIDR_EL0** is free for user code on Windows. Bionic keeps its TLS slots
  there (the stack guard is slot 5), so each thread gets a block on its first
  entry into guest code (`src/arm64/arm64_native.cpp`).
- **`svc #0`** would be a Windows system call. The loader patches it to a
  `brk` the shim answers as the Linux call.
- **Variadic floating-point arguments**: Windows passes them in integer
  registers, AAPCS64 in v0-v7. The variadic entry stubs spill both under an
  AAPCS64 `va_list` (`src/abi_thunk_arm64.cpp`).
- **Struct layouts, open flags and syscall numbers** arm64 Linux has
  differently from the shim's: `src/arm64/arm64_abi.cpp` binds those imports
  to wrappers.
- The **Bionic shim** maps Android libc onto the UCRT and Win32, and Mac
  services (Cocoa, Metal, AVFoundation, WKWebView, ...) have Windows
  counterparts in `windows/host/`.

## 3. Decisions

D1. **Soft fork.** `main` shares Mac.apk's history and merges its releases.
Windows code lives in `windows/` and other paths upstream does not have; an
upstream file changes only for a hook Windows needs or a framework fix worth
offering upstream, and every such change is listed in `README.md`.

D2. **Clang only**, MSYS2 CLANGARM64, Temurin 21 aarch64. CMake refuses any
target but ARM64.

D3. **Copy ELF segments into one `VirtualAlloc` region.** `MapViewOfFile`
needs file offsets aligned to 64 KiB; ELF segments are aligned to 4 or 16 KiB.

D4. **The guest gets its own `JNIEnv` and `JavaVM`**, generated from `jni.h`
(`src/guest_jni.cpp`, `jni_slots.inc`), so every call can be checked and the
variadic forms bridged.

D5. **Guest code enters the system in three places only**: the loader
(`ElfImage`), the linker's image registry (`GuestLinker`) and native-method
binding (`jni_bind`). New code must not call guest functions around them.

D6. **Refuse what cannot run, and say why**: an x86_64 or armeabi-v7a
library, or one that writes x18, fails at load with a message the user sees
(the launcher's install and start errors, the run log). `WINAPK_ARM64_ALLOW_X18=1`
loads it anyway, for investigation.

## 4. Technical findings

### Loader and ABI

- Code built for Linux does not probe its stack page by page as Windows code
  must: a frame larger than a page writes past the guard page, and Windows
  cannot deliver that fault. Guest threads get 1 MB of their stack committed
  before guest code runs (`src/guest_stack.cpp`).
- lld emits Android APS2 relocations with `--pack-dyn-relocs=android+relr`;
  the loader decodes them as bionic's `packed_reloc_iterator` does, and RELR.
- IRELATIVE resolvers are guest code: they run after the text is executable,
  with bionic's `(AT_HWCAP | _IFUNC_ARG_HWCAP, &arg)`.
- Test libraries need no NDK: plain clang + lld with `-nostdlib` and
  hand-declared libc imports (`test/build_guest.sh`).

### JNI

- JDK 21's `JNINativeInterface_` has 235 slots: 4 reserved, 169 with fixed
  arguments, the rest the `...` and `va_list` families.
- Variadic JNI arguments follow C promotion: boolean, byte, char, short and
  int arrive in an integer slot, float as a double.
- JNI cannot give a method's signature from a `jmethodID`; the variadic bridge
  asks JVMTI.
- JVMTI `NativeMethodBind` fires for name-bound natives and for
  `RegisterNatives`; the bound address is replaced with a thunk built from
  the signature.
- Other aarch64 JDKs' `jni_md.h` make `jint` a `long`; Temurin's is `int`.

### Linker

- Bionic's dlopen flags on LP64: `RTLD_LAZY` 1, `RTLD_NOW` 2, `RTLD_LOCAL` 0,
  `RTLD_GLOBAL` 0x100.
- When a library is linked, bionic searches the global group, then the
  library and its `DT_NEEDED` tree in breadth-first order.
- Bionic's `dladdr` names a symbol only when `[value, value + size)` covers
  the address.
- Guest paths are UTF-8; build `std::filesystem::path` with `u8path`.

### JVM hosting

- HotSpot reads JVM option strings in the ANSI code page; the runtime's
  manifest makes it UTF-8.
- `-Xss` does not apply to the thread that creates the VM, so the runtime
  creates it on a thread with a large stack.
- A vectored exception handler must pass on what it does not own, so
  HotSpot's own handling keeps working.
- `System.mapLibraryName("x")` is `x.dll`, which matters to SoLoader.

### Android paths

An app assumes a POSIX filesystem. One table decides where a guest path lives
(`src/bionic/paths.cpp` for native code, the java.base overlay's
`jdk.internal.util.WinApkPaths` for java.io and java.nio): the app sees
`/data/user/0/<pkg>` and `/storage/emulated/0`, the host opens the folder
under `%LOCALAPPDATA%\Win.apk\android`. File names with characters NTFS
forbids are escaped.

## 5. Upstream behaviour the port preserves

- `com.macapk.loader.AppClassLoader.findLibrary` is the single funnel for
  native libraries; it returns null when the APK lacks the library, so the
  app gets `UnsatisfiedLinkError` as on Android.
- HotSpot loads what `findLibrary` returns with `LoadLibraryW` and keys
  libraries by path: each guest library gets its own copy of a stub DLL
  (`host/guest_stub.cpp`, `host/guest_libs.cpp`) whose `JNI_OnLoad` loads the
  ELF image and calls the guest's with the guest `JavaVM`.
- `Java_*` natives are bound lazily through `ClassLoader.findNative`, which
  searches only libraries registered with the declaring class's loader.
- `FindClass` tries the app class loader without initializing the class, then
  the `JNI_OnLoad` context loader, then HotSpot's `FindClass`.
- `DT_NEEDED` dependencies run their constructors, not their `JNI_OnLoad`;
  guest `dlopen` and `NativeActivity` never run `JNI_OnLoad`.
- Native threads created by guest code are not attached to the JVM
  automatically.

## 6. Known gaps

- The thunk pool is mapped read-write-execute and has no unwind information.
- Apps that ship only `x86_64` or only `armeabi-v7a` code do not run; neither
  do libraries that write x18.
- Some shim answers still describe an x86_64 device (system properties such
  as `ro.product.cpu.abi`, `uname`'s machine); arm64 apps get arm64 layouts
  through `arm64_abi.cpp`, but these values are to be checked.
- Signals are delivered for faults and self-sent signals
  (`src/bionic/signal.cpp`); POSIX timers are recorded only.
- The window is painted from a readback of every frame; partial redraw
  (`MACAPK_FRAMESPEED_DAMAGE=1`) is off by default.
- No in-app purchases, Play Integrity or Vulkan; OpenSL ES is a stub.
