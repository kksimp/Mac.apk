# Contributing to WinARM.apk

Two ways in: report how an app runs, or send code. WinARM.apk runs on
Windows 11 on Arm only; x64 reports and patches belong at
[israel2176/win.apk](https://github.com/israel2176/win.apk).

## Report a problem

When an app fails to install or start, **Report a problem** in the window that
says so (or on the app's details page) opens the "An app does not work" form
with versions filled in; nothing is sent until you post it. Otherwise
[open an issue](https://github.com/airisu-dev/WinARM.apk/issues/new/choose).
A report that can be acted on has:

1. **Versions:** WinARM.apk (Settings > About), Windows, the app and its
   version, and where the APK came from.
2. **Steps to reproduce,** numbered, from a fresh start.
3. **A screenshot** of the app where it goes wrong (Win+Shift+S).
4. **The whole log:** `%LOCALAPPDATA%\Win.apk\logs\last-run.log` for a run,
   `install-<app>.log` for an install (Ctrl+L opens the newest). Look it over
   first; it can contain file names and the app's own output.

Check the open issues for the same app first, and keep to one app per issue.

## Send code

- Read [`windows/README.md`](../windows/README.md) and get a build running.
  [`CLAUDE.md`](../CLAUDE.md) is the map of the repository.
- Branch from `main`, one topic per branch, and open the pull request against
  `main`. Commit messages are in English and say why the change was made.
- CI (`win-ci.yml`) must be green: it builds with clang on `windows-11-arm`,
  runs the tests and a smoke test, and packages the result.
- **Behave like Android.** Framework classes under `runtime/java/android/...`
  mirror AOSP's public API and behaviour.
- **No app-specific code.** Fix the framework, shim or host instead.
- **Windows code lives in `windows/`.** Change `runtime/` only where Windows
  needs a hook, and list it in `windows/README.md`.
- Style: C++17, clang only, 120 columns, comments say why.
- Update the documents your change makes wrong, in the same pull request.

Pull requests that defeat license checks or copy protection, forge attestation
tokens, bypass account or payment security, or help obtain apps you have no
right to use will be closed.

Security problems: see [`SECURITY.md`](SECURITY.md), not a public issue.
