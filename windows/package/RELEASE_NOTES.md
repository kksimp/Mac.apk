**WinARM.apk: Android apps on Windows on Arm.** WinARM.apk runs Android apps on Windows 11 on Arm as ordinary Windows programs: each app gets its own window, Start menu entry and notifications. It is an unofficial port of [Mac.apk](https://github.com/kksimp/Mac.apk), based on [israel2176/win.apk](https://github.com/israel2176/win.apk) (which also has an x64 build).

> Preview build, not code-signed yet: SmartScreen may warn. Click **More info**, then **Run anyway**.

## Download
- **`Win.apk-Setup-arm64.exe`**, the installer (per user, no administrator rights), or **`Win.apk-windows-arm64.zip`**, portable: unzip it and run `Win.apk.exe`.

## Highlights
- **Native on Windows on Arm.** Apps' `arm64-v8a` code runs as it is on the CPU, with no emulation or translation. The Store picks arm64 builds for you.
- **A maximized window is the phone turned on its side**, at a density that draws it pixel for pixel.
- **Text and layout closer to Android:** right-to-left paragraphs, line limits and baselines, `TextView` sizing, password and phone fields, themes' colours and sizes, tinted and inset drawables, image matrices.

## Known limitations
- Apps with only `x86_64` or only 32-bit ARM (`armeabi-v7a`) code do not run.
- A library that uses the x18 register is refused (two libGDX games of 92 tested apps).
- No in-app purchases, Play Integrity or Vulkan; OpenSL ES sound is missing; sensors report a phone lying still.

Found a problem? Use **Report a problem** in WinARM.apk, or [open an issue](https://github.com/airisu-dev/WinARM.apk/issues/new/choose).
