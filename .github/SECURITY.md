# Security

## Reporting

Please do not report security problems in a public issue. Use GitHub's
private reporting: the repository's **Security** tab, then **Report a
vulnerability**. Say what you found, how to reproduce it, and which WinARM.apk
version and Windows version you used.

Only the newest pre-release is supported; a fix ships in the next one.

## What WinARM.apk is, security-wise

An Android app running under WinARM.apk is ordinary code running with your
Windows user account's permissions, inside a normal Windows process. There
is no phone-style sandbox, no separate user per app, and no virtual machine
around it. Consequences:

- An app can read and write what you can. "An APK can read my files" is
  expected behaviour, not a vulnerability. Install only apps you trust.
- Android's permission dialogs (location, camera, contacts and so on) decide
  what the Android framework tells the app, not what the process can reach.
- All apps share one Android file system under `%LOCALAPPDATA%\Win.apk\android`.
- Preview builds are not code-signed. Windows SmartScreen warns on install,
  and some scanners may flag the runtime.

## What counts as a vulnerability

Reports in these areas are welcome:

- The permission store: a way for an app to grant itself a permission, or to
  forge or alter `appops\app-ops.json`, which the launcher signs and the
  runtime verifies.
- The Store: a way to install an APK that does not match the SHA-256 in
  F-Droid's index, or to make WinARM.apk fetch from somewhere other than
  f-droid.org.
- The Google sign-in window: anything that would expose the Google session or
  the stored tokens (`gms\gms_state.properties`) to an app or another user.
- The install helper (`Win.apk.exe install` and the permission verbs): a way
  for an app to drive it without the user's answer.
- Memory-safety bugs in the loader, the linker or the Bionic shim that a
  crafted APK could use against the host process beyond what the app could
  do anyway with its user rights.

Out of scope: anything an app can do because it runs as the user (see
above), and problems in the apps themselves.
