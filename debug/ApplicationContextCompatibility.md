# Wrapped Application context metadata and package identity

A wrapper may construct a second Application and attach it to an existing base
Context. ApplicationInfo first consults that base, while preserving the existing
non-null/non-throwing fallback, PackageManager lookup, and dataDir repair if the
delegate returns null, throws, or supplies an incomplete record. A record cached
before attachment cannot hide valid nativeLibraryDir metadata from the base.

Activity prefers its attached Application's nonempty package identity. The process
Application and Boot manifest record remain fallbacks when no attached identity
is available. Host routing recognizes both the reported Application package and
the installed manifest package for explicit and implicit local components, and
retains the selected component's package. External targets still use the existing
resolution and host launch policies. Application.getPackageName is not rewritten.

Public OsConstants.PROT_* fields allow precompiled Android SDKs to resolve the
existing SharedMemory protection flags and read-only mappings.

AOSP references: [ActivityThread creates the Activity base Context](https://android.googlesource.com/platform/frameworks/base/+/c136cb0/core/java/android/app/ActivityThread.java)
and [ContextWrapper delegates ApplicationInfo to its base](https://android.googlesource.com/platform/frameworks/base/+/android10-release/core/java/android/content/ContextWrapper.java).

After building the Java jars, run `bash debug/check_application_context.sh`.
Coverage includes a pre-attachment query, the actual native library directory,
null/throwing/incomplete delegates, dataDir repair, empty and plugin Application
package names, Activity-created Intents, explicit/implicit local routing under
both package identities, external resolution and policy, pre-Boot fallback, and
SharedMemory field resolution/read-only mapping.

Earlier combined local changes allowed DREAME to pass Application and Flutter
initialization. Usable screens, login, and device control remain unverified. This
PR excludes native loading and in-memory DEX changes, the repair launcher, and app
data.
