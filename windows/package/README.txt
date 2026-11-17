WinARM.apk preview
=====================

WinARM.apk runs Android apps on Windows 11 on Arm (Snapdragon and other ARM64 PCs) as
ordinary Windows programs: each app in its own window, with its own Start
menu entry and Windows notifications. There is no emulator and no virtual
machine. WinARM.apk is an unofficial port of Mac.apk and is not affiliated with
or endorsed by the Mac.apk project. This is an early preview.

Project page: https://github.com/airisu-dev/WinARM.apk . It is based on israel2176/win.apk
(https://github.com/israel2176/win.apk), which also has an x64 build.

Installing WinARM.apk
-------------------------
Win.apk-Setup-arm64.exe installs it for your user only (no administrator rights),
puts it in the Start menu and opens .apk files with it. Windows SmartScreen
warns because the preview is not code-signed: click "More info", then "Run
anyway". Or unzip Win.apk-windows-arm64.zip anywhere and start Win.apk.exe.

Included: Java (built from Eclipse Temurin 21, the jre folder) and ANGLE for graphics
(bin\libEGL.dll, bin\libGLESv2.dll). Web pages inside apps
and Google sign-in use the Microsoft Edge WebView2 Runtime, which
Windows 11 already has.

Adding and running apps
-----------------------
- From the Store tab: F-Droid's free and open-source apps, the easiest place
  to start. Search, then Install.
- From a file: double-click an .apk, .apkm, .xapk or .apks in Explorer, drag
  it onto the library, or click "Install app". Use an app's arm64-v8a build (or
  one with no native code). An app with only x86_64 or 32-bit ARM
  code (armeabi-v7a) does not run.
- Double-click an app to open it. The mouse is your finger, the wheel
  scrolls, Ctrl+wheel (or a pinch on the touchpad) zooms, a right click is a
  long press, and Escape is Android's Back. On a touchscreen every finger is a
  finger. Resize the window and the app lays itself out again.
- Right-click an app for Open, Stop, App details and Remove. App details holds
  the rest: orientation, permissions, the log, Report a problem.
- Settings: theme, app window size and default orientation, your Windows
  folders, the Google account, updates.

What works, and what does not yet, in short: Java and Kotlin apps, OpenGL ES games,
sound and video, WebView, webcams, location, Bluetooth, notifications and
Google sign-in work; x86_64 and 32-bit ARM code, Vulkan, OpenSL ES sound, the NDK media
API, real sensors, in-app purchases and Play Integrity do not.

Where things are
----------------
Your apps and their data:  %LOCALAPPDATA%\Win.apk
Logs:                      %LOCALAPPDATA%\Win.apk\logs (run-<app>.log is an
                           app's last run, install-<app>.log its install)
Settings:                  HKEY_CURRENT_USER\Software\Win.apk

Removing an app keeps what it saved unless you tick "Also remove accounts and
data saved by the app"; uninstalling WinARM.apk keeps the apps too. Delete %LOCALAPPDATA%\Win.apk
to start from scratch.

Reporting a problem
-------------------
Use "Report a problem" in WinARM.apk: under an error, or on an app's details
page. It opens GitHub's form with the versions filled in and the logs folder
with the app's log selected; nothing is sent until you post it. Include where
the APK came from, the steps, a screenshot and the whole log.

Apps run with your Windows account's rights, without a phone-style sandbox:
install only apps you trust.

License
-------
WinARM.apk is free software under the GNU AGPL v3.0 (LICENSE.txt), like Mac.apk;
its source is at https://github.com/airisu-dev/WinARM.apk . The third-party
components it ships and their licences are listed in THIRD_PARTY_NOTICES.txt;
full texts are also in jre\legal, bin\licenses and clang\licenses.
