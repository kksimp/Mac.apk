# WinARM.apk

**Run Android apps on Windows 11 on Arm as ordinary Windows programs. No emulator, no virtual machine.**

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](../LICENSE)

WinARM.apk installs Android apps (`.apk`, `.apkm`, `.xapk`, `.apks`) and runs
each one in its own window, with its own Start menu entry, taskbar icon and
Windows notifications. Java runs on HotSpot, the app's arm64 native code runs
as it is on the Arm processor, and graphics go through ANGLE on Direct3D 11.
No Android system boots; only what an app needs is re-created on top of Windows.

It is a soft fork of [Mac.apk](https://github.com/kksimp/Mac.apk) that carries
a Windows port under [`windows/`](../windows/). It is **ARM64 only**. For Windows
on x64, use [israel2176/win.apk](https://github.com/israel2176/win.apk).

> [!NOTE]
> This is a preview. Many apps run well; others run in part or not yet.

## Requirements

- Windows 11 on Arm, for example a Snapdragon X PC.
- Apps must ship `arm64-v8a` native code, or none at all (pure Java or Kotlin).
  Apps whose only native code is `x86_64` or `armeabi-v7a` do not run.

## Install

Download `Win.apk-Setup-arm64.exe` from the
[releases](https://github.com/airisu-dev/WinARM.apk/releases) and run it.
Then double-click an `.apk` (or a split bundle), drag one into the library
window, or install from the built-in F-Droid Store tab. Each app gets its own
window and a Start menu shortcut.

## Features

- Resizable app windows, taskbar icons, Windows toast notifications.
- Webcam, microphone, Windows location, paired Bluetooth devices, Wi-Fi
  information, game controllers, your Downloads and Pictures folders, and the
  clipboard.
- Google sign-in, and experimentally Google's own Play services.
- Light and dark themes.

## Build

Build with clang (MSYS2 CLANGARM64) on a Windows on Arm machine and Temurin 21
aarch64. The steps, tests and environment variables are in
[`windows/README.md`](../windows/README.md). For a map of the repository, see
[`CLAUDE.md`](../CLAUDE.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## Licence

AGPL-3.0: see [`LICENSE`](../LICENSE). The name and marks are covered by
[`TRADEMARKS.md`](../TRADEMARKS.md).

## Credits

- [kksimp/Mac.apk](https://github.com/kksimp/Mac.apk): the Android framework in
  Java and the runtime this port builds on.
- [israel2176/win.apk](https://github.com/israel2176/win.apk): the original
  Windows port (Win.apk) this fork is derived from.
