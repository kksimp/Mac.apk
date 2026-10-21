// InstallCore.swift -- the install spine: paths, the registry, and the ONE
// pipeline every entry point runs through.
//
// WHY this file exists as a separate unit from MacApkLauncherApp.swift:
// four different callers install an APK (GUI drag-drop, install-from-folder,
// Finder Open-With, and later an Android-initiated install via a helper
// binary). Today only the launcher can do it, so its rules -- what counts as
// success, what gets written to the registry, what happens on a re-install --
// live inside a SwiftUI @MainActor class and cannot be reached from a
// non-AppKit process. Everything here is UI-free and main-actor-free on
// purpose: a bare command-line helper links the same file and gets identical
// behaviour, which is the only way "one pipeline" stays true rather than
// becoming two implementations that drift.
//
// Spec: docs/platform-integration-master-plan_2026-07-11.md section 4, with
// the corrections listed at each site (the plan was written at v1.0.611 and
// several of its premises were measured false against the tree at v1.0.943).
//
// Layering rule: this file imports Foundation + CryptoKit + Darwin ONLY. No
// AppKit, no SwiftUI, no LocalAuthentication. Anything that needs to ask the
// user something is injected as a closure (InstallPipeline.Hooks) so the
// dialog lives in the caller that has a UI and the pipeline stays runnable
// headless (tests, --no-ui, the future helper).

import Foundation
import CryptoKit

// MARK: - Logs

/// The ONE resolution of "where do Mac.apk's logs live".
///
/// Project-relative `logs/` when the .app sits inside the repo checkout,
/// otherwise `~/Library/Logs/macapk/`. `LaunchModel.logsDirectory()` and the
/// GUI's "Reveal logs folder" button both delegate here. They must: a button
/// that opens directory A while the writer appends to directory B is worse
/// than no button at all, because it tells the user with authority that there
/// is nothing to find.
public enum MacApkLogDir {
    public static func url() -> URL {
        let fm = FileManager.default
        var dir = Bundle.main.bundleURL.deletingLastPathComponent()
        for _ in 0..<6 {
            let candidate = dir.appendingPathComponent("logs")
            if fm.fileExists(atPath: candidate.path) { return candidate }
            let parent = dir.deletingLastPathComponent()
            if parent == dir { break }
            dir = parent
        }
        return fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Logs")
            .appendingPathComponent("macapk")
    }
}

/// Append-only record of every install attempt, written into the folder the
/// GUI's "Reveal logs folder" button opens.
///
/// WHY THIS EXISTS. `macapk_launcher.log` is created by
/// `LaunchModel.resetLiveLog()`, which runs when the user clicks Run. A
/// session that only INSTALLS therefore wrote nothing to the logs folder at
/// all, and the install path reported its failures through `NSLog`, which
/// reaches the unified system log and no file. So a user whose install failed
/// and who then opened "Reveal logs folder" -- exactly as the UI invites --
/// found an empty directory, and the one piece of evidence naming the cause
/// (the extractor's own output) was unreachable without `log show`.
///
/// Measured on a fresh machine 2026-08-22: two APKs failed with "nothing was
/// unpacked", the logs folder was empty, and the extractor tail that would
/// have named the cause existed only in memory.
///
/// Deliberately NOT gated on the launcher's `fileLogEnabled` toggle. That
/// switch is about mirroring a RUN's chatty stderr; this is a handful of lines
/// per install and is the only record that an install failed.
public enum InstallLog {
    private static let lock = NSLock()

    /// Rotate at 4 MB, keeping ONE previous generation. An install writes a
    /// few lines plus, when it fails, up to 8 KB of extractor tail -- so this
    /// holds a long history while staying bounded on a machine that installs
    /// a lot.
    private static let maxBytes = 4 * 1024 * 1024

    public static func url() -> URL {
        MacApkLogDir.url().appendingPathComponent("install.log")
    }

    /// One timestamped line. Safe to call from any thread and from any of the
    /// three binaries that link this file: up to four install workers run
    /// concurrently, and the helper is a separate process appending to the
    /// same file, so every write is O_APPEND under a lock.
    public static func write(_ message: String) {
        let stamp = ISO8601DateFormatter().string(from: Date())
        let line = "[\(stamp)] [pid \(getpid())] \(message)\n"
        guard let data = line.data(using: .utf8) else { return }

        lock.lock()
        defer { lock.unlock() }

        let fm = FileManager.default
        let dst = url()
        try? fm.createDirectory(at: dst.deletingLastPathComponent(),
                                withIntermediateDirectories: true)
        if let attrs = try? fm.attributesOfItem(atPath: dst.path),
           let size = attrs[.size] as? Int, size > maxBytes {
            let prev = dst.appendingPathExtension("1")
            try? fm.removeItem(at: prev)
            try? fm.moveItem(at: dst, to: prev)
        }
        if let h = try? FileHandle(forWritingTo: dst) {
            defer { try? h.close() }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: data)
        } else {
            try? data.write(to: dst)
        }
    }
}

// MARK: - Quarantine

/// Strip `com.apple.quarantine` from a bundle WE synthesized.
///
/// WHY THIS EXISTS, and why it was not needed until v1.0.1030.
///
/// Generated per-app bundles are deliberately never codesigned (see the
/// SIGNING MODEL comment in BundleGenerator.swift): the stub template carries
/// its own ad-hoc signature from build time, and sealing the bundle would put
/// a resource seal over `Contents/`, which the app data the runtime writes
/// under `Contents/android/` would invalidate on the first byte. The known,
/// accepted consequence is that `codesign --verify` on a generated bundle
/// FAILS with "code has no resources but signature indicates they must be
/// present".
///
/// That was safe only while its stated premise held -- BundleGenerator's own
/// comment says bundles are *"created locally, never quarantined, so
/// Gatekeeper assessment does not run"*, and calls it a LATENT risk. The
/// premise broke the moment Mac.apk started being delivered as a downloaded,
/// notarized DMG: the control panel then carries `com.apple.quarantine`
/// itself, and files a quarantined process creates INHERIT the attribute. So
/// every generated bundle became quarantined AND unsealed, which is precisely
/// the combination Gatekeeper reports as "the application is damaged and
/// should be moved to the Trash".
///
/// Measured on a second machine 2026-08-22: launching from the app drawer
/// (LaunchServices, which enforces) was refused outright, while launching from
/// the control panel (a direct spawn) raised the same dialog repeatedly and
/// still ran once dismissed. The app itself was fine -- Flappy Bird booted,
/// held 55-60 fps and took touch input.
///
/// This is the correct fix rather than a bypass: we are not clearing the flag
/// on downloaded code, we are clearing it on a wrapper this machine just
/// generated, which is what an installer is supposed to do for what it
/// installs. The APK's own quarantine state is irrelevant to it -- the runtime
/// parses it as data, it is never executed as host code.
///
/// Best effort throughout: `removexattr` returning ENOATTR is the common case
/// (nothing was quarantined) and is not an error. Signing the bundle after
/// generation with `Contents/android/` excluded from the seal, which
/// BundleGenerator names as the other fix direction, remains open and would
/// additionally survive a future macOS that assesses resident bundles.
public enum Quarantine {
    public static let attribute = "com.apple.quarantine"

    /// Clear the attribute on `url` and everything beneath it.
    public static func clear(at url: URL) {
        clearOne(url)
        guard let e = FileManager.default.enumerator(
                at: url, includingPropertiesForKeys: nil,
                options: [], errorHandler: nil) else { return }
        for case let child as URL in e { clearOne(child) }
    }

    /// Clear the attribute on `url` and its executable, without walking the
    /// whole tree. For healing an EXISTING bundle on a hot path: a generated
    /// bundle contains the app's entire extracted install under
    /// Contents/android, so a recursive walk is far too expensive to repeat on
    /// every refresh, and Gatekeeper reads the attribute on the .app directory
    /// itself.
    public static func clearShallow(at url: URL) {
        clearOne(url)
        let contents = url.appendingPathComponent("Contents")
        clearOne(contents)
        clearOne(contents.appendingPathComponent("Info.plist"))
        let macos = contents.appendingPathComponent("MacOS")
        clearOne(macos)
        if let kids = try? FileManager.default.contentsOfDirectory(
                at: macos, includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]) {
            for k in kids { clearOne(k) }
        }
    }

    /// XATTR_NOFOLLOW so a symlink is cleared itself rather than its target:
    /// the run wrapper's executable IS a symlink to the shared runtime, and
    /// following it would strip the attribute off the wrong file.
    private static func clearOne(_ url: URL) {
        let rc = url.withUnsafeFileSystemRepresentation { path -> Int32 in
            guard let path else { return -1 }
            return removexattr(path, attribute, XATTR_NOFOLLOW)
        }
        // ENOATTR is the COMMON case (nothing was quarantined) and is not an
        // error. Anything else is: a clear that silently fails leaves an app
        // unlaunchable with no trace, which is exactly how the v1.0.1030 fix
        // looked correct while the real quarantine sat somewhere else.
        if rc != 0 && errno != ENOATTR {
            InstallLog.write("[quarantine] removexattr failed on \(url.path): "
                           + String(cString: strerror(errno)))
        }
    }
}

// MARK: - Paths

/// Every on-disk location the install system owns.
///
/// Generated bundles go in `/Applications` whenever this user can write it,
/// and not in `~/Applications/Mac.apk Apps/` (which the master plan proposed):
/// the plan's own risk register (R3) doubted that the macOS 26 Spotlight Apps
/// view -- which replaced Launchpad -- indexes subfolders, and the owner's
/// words are "inside of the Mac OS application files".
///
/// ⚠ GitHub #28 (v1.0.2282): `/Applications` is root:admin 775 on a stock
/// macOS, so a STANDARD (non-admin) user cannot create anything in it. With
/// `bundlesDir` a constant, every install by such a user failed staging with
/// NSCocoaErrorDomain 513 ("You don't have permission to save the file
/// 'MacOS' in the folder 'Contents'"), and there was nowhere else to put the
/// app. Such a user falls back to the per-user `~/Applications` -- the folder
/// macOS itself uses for a user's own apps, which Launch Services and the
/// Spotlight Apps view both index (no subfolder, for the R3 reason above).
/// `bundleDirs` lists both, and every reader that FINDS installed bundles
/// (generator lookup and remove guard, reconcile adoption, bulk uninstall,
/// the runtime's cross-app launcher and bundle watcher) scans both, so an
/// app installed into either one is the same installed app. App data is in
/// `appDataDir` either way.
public enum Paths {
    public static let supportDir: URL = {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Library/Application Support")
        return base.appendingPathComponent("Mac.apk", isDirectory: true)
    }()

    /// The registry. Same path the launcher has always used, so an existing
    /// install keeps its library across this change (the file is migrated in
    /// place, never replaced -- see RegistryStore.load).
    public static let registryURL = supportDir.appendingPathComponent("installed_apps.json")

    /// LEGACY: where retained copies of the source APKs used to live. No longer
    /// created or written (owner: no APK copies, ever); `ApkRetention.purgeAll`
    /// empties it at startup. The bundle's own `Contents/Resources/base.<ext>`
    /// is the copy that keeps a Launchpad icon alive after ~/Downloads is
    /// cleaned.
    public static let apksDir = supportDir.appendingPathComponent("apks", isDirectory: true)

    /// Scratch space for in-flight copies. Anything here older than 24h is a
    /// crash leftover and gets swept.
    public static let stagingDir = supportDir.appendingPathComponent("staging", isDirectory: true)

    /// Per-package pidfiles, so a second launch of the same app is refused
    /// rather than racing it. (Written by the launcher/stub, not by us.)
    public static let runDir = supportDir.appendingPathComponent("run", isDirectory: true)

    /// The system Applications folder: where a NEW bundle goes whenever this
    /// user can write it. `MACAPK_SYSTEM_APPS_DIR` relocates it for a gate
    /// (debug/bundlesdir_gate.sh points it at a read-only scratch folder to
    /// play a standard user on an admin's Mac); the control panel unsets it
    /// at startup, so it is a harness seam only.
    public static var systemBundlesDir: URL {
        if let e = ProcessInfo.processInfo.environment["MACAPK_SYSTEM_APPS_DIR"], !e.isEmpty {
            return URL(fileURLWithPath: e, isDirectory: true)
        }
        return URL(fileURLWithPath: "/Applications", isDirectory: true)
    }

    /// The per-user `~/Applications`: where a NEW bundle goes when
    /// `systemBundlesDir` is not writable (GitHub #28). `MACAPK_USER_APPS_DIR`
    /// relocates it, like the one above.
    public static var userBundlesDir: URL {
        if let e = ProcessInfo.processInfo.environment["MACAPK_USER_APPS_DIR"], !e.isEmpty {
            return URL(fileURLWithPath: e, isDirectory: true)
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications", isDirectory: true)
    }

    /// Every folder an installed bundle may live in, in preference order.
    /// Readers that look FOR installed bundles iterate this, never just
    /// `bundlesDir`: a user whose access changed (made admin, or no longer)
    /// still has apps in the other folder.
    public static var bundleDirs: [URL] {
        let a = systemBundlesDir, b = userBundlesDir
        return a.standardizedFileURL.path == b.standardizedFileURL.path ? [a] : [a, b]
    }

    /// Where a NEW bundle is generated: `systemBundlesDir` when this user can
    /// write it, else `userBundlesDir` (GitHub #28). An update regenerates an
    /// existing bundle in whichever folder holds it
    /// (`BundleGenerator.generate`), so this only decides where a first
    /// install lands. Answers `systemBundlesDir` when neither is usable, so
    /// the failure names the folder an admin would expect.
    public static var bundlesDir: URL {
        let sys = systemBundlesDir
        if isWritableDir(sys) { return sys }
        let user = userBundlesDir
        if isWritableDir(user) { return user }
        return sys
    }

    /// True when `url` is an existing directory this process can create
    /// entries in. `isWritableFile` answers for the effective uid, which is
    /// the question that matters -- /Applications is root-owned but
    /// admin-group-writable on a stock macOS, and neither fact alone predicts
    /// the answer.
    static func isWritableDir(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue
        else { return false }
        return fm.isWritableFile(atPath: url.path)
    }

    /// Create `~/Applications` when this user cannot write `/Applications`
    /// and it does not exist yet (a fresh account has none). Called by the
    /// install paths before they generate; idempotent and best effort.
    /// Never creates the system folder.
    @discardableResult
    public static func prepareBundlesDir() -> URL {
        let sys = systemBundlesDir
        if isWritableDir(sys) { return sys }
        let user = userBundlesDir
        let fm = FileManager.default
        if !fm.fileExists(atPath: user.path) {
            do {
                try fm.createDirectory(at: user, withIntermediateDirectories: true)
                InstallLog.write("[install] \(sys.path) is not writable for this user; "
                               + "created \(user.path) for app bundles")
            } catch {
                InstallLog.write("[install] could not create \(user.path): \(error)")
            }
        }
        return bundlesDir
    }

    /// Our `.app` children of every bundle folder, folder by folder in
    /// preference order and sorted by name within each, so a duplicate
    /// resolves deterministically. Hidden entries (swap siblings) skipped.
    /// Not filtered by identity: callers apply their own guard.
    public static func bundleCandidates() -> [URL] {
        let fm = FileManager.default
        var out: [URL] = []
        for d in bundleDirs {
            guard let kids = try? fm.contentsOfDirectory(at: d, includingPropertiesForKeys: nil,
                                                         options: [.skipsHiddenFiles])
            else { continue }
            out += kids.filter { $0.pathExtension == "app" }
                       .sorted { $0.lastPathComponent < $1.lastPathComponent }
        }
        return out
    }

    /// The bundle folder `url` sits directly inside, or nil when it is in
    /// none of them. Resolves symlinks on both sides, as remove()'s guard does.
    public static func bundleDir(containing url: URL) -> URL? {
        let parent = url.resolvingSymlinksInPath().standardizedFileURL
            .deletingLastPathComponent().path
        return bundleDirs.first {
            $0.resolvingSymlinksInPath().standardizedFileURL.path == parent
        }
    }

    /// Where each installed app's Android tree (its data) lives, one folder per
    /// app: `~/Library/Application Support/MacApk/<package>/`. A SIBLING of
    /// supportDir, not inside it, because a ".apk" substring in an app's data
    /// path breaks guests (see AppDataLayout).
    /// `MACAPK_APPDATA_ROOT` relocates it (harnesses that run the stub or the
    /// generator on throwaway bundles).
    public static let appDataDir: URL = {
        if let e = ProcessInfo.processInfo.environment["MACAPK_APPDATA_ROOT"], !e.isEmpty {
            return URL(fileURLWithPath: e, isDirectory: true)
        }
        return supportDir.deletingLastPathComponent()
            .appendingPathComponent("MacApk", isDirectory: true)
    }()

    // Sidecar locks. Separate files rather than locking the data file itself:
    // flock on the data file would be lost across the atomic rename that
    // replaces it, which is exactly the window two writers must not share.
    public static let registryLockURL = supportDir.appendingPathComponent("installed_apps.json.lock")
    public static let installLockURL  = supportDir.appendingPathComponent("install.lock")

    /// Create the directories we own. Cheap and idempotent; call before any
    /// write. Deliberately does NOT create a bundle folder (the system one is
    /// not ours; `prepareBundlesDir` makes `~/Applications` only when an
    /// install needs it) or the support dir's parent.
    @discardableResult
    public static func ensureDirectories() -> Bool {
        let fm = FileManager.default
        var ok = true
        for d in [supportDir, stagingDir, runDir] {
            do { try fm.createDirectory(at: d, withIntermediateDirectories: true) }
            catch { ok = false }
        }
        return ok
    }

    /// True when we could actually write a bundle into `bundlesDir`, i.e.
    /// into either folder (see `isWritableDir`).
    public static var bundlesDirIsWritable: Bool { isWritableDir(bundlesDir) }
}

// MARK: - Free-space preflight

/// An install unpacks the APK (dex -> jar, apktool decode, translated dylibs,
/// the retained APK copy): in practice about 3x the APK, plus working room. A
/// full disk otherwise surfaces as a cryptic extractor failure half-way through
/// and leaves a partial bundle behind. Fail up front with a readable message.
/// MACAPK_SPACE_CHECK=0 skips it.
public enum FreeSpaceCheck {
    public static let overheadBytes: Int64 = 200 * 1024 * 1024

    /// nil when there is enough room (or it cannot be determined); otherwise
    /// the message to show.
    public static func shortfallMessage(apkSizes: [Int64], destination: URL) -> String? {
        if ProcessInfo.processInfo.environment["MACAPK_SPACE_CHECK"] == "0" { return nil }
        let needed = apkSizes.reduce(0, +) * 3 + overheadBytes
        // The bundle folder may not exist yet; ask the nearest existing parent.
        var probe = destination
        while !FileManager.default.fileExists(atPath: probe.path), probe.path != "/" {
            probe = probe.deletingLastPathComponent()
        }
        guard let avail = try? probe.resourceValues(
                forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage,
              avail < needed else { return nil }
        let gb = String(format: "%.1f", Double(needed) / 1_073_741_824)
        let freeGb = String(format: "%.1f", Double(avail) / 1_073_741_824)
        return "Not enough disk space: installing needs about \(gb) GB free, "
             + "but only \(freeGb) GB is available. Free some space and try again."
    }

    public static func size(of url: URL) -> Int64 {
        let n = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return Int64(n)
    }
}

// MARK: - App data location (outside the bundle)

/// Where an installed app's Android tree lives, and the move that puts it there.
///
/// WHY. Until v1.0.2251 an app's whole Android tree (data/data, sdcard, the
/// code cache) lived INSIDE its bundle, at `<App>.app/Contents/android` (owner,
/// 2026-10-19: "Data inside the .app is a real wart"). Every save the app wrote
/// broke the bundle's code seal, Finder sizes counted saves as the app, and the
/// data had no place the user could open and clean.
///
/// THE LAYOUT.
///
///     <App>.app/Contents/app/          the installed package (`~~<nonce>/<pkg>-<nonce>/
///                                      base.apk` + splits): the APK IS the app, so
///                                      it stays in the bundle, under its seal
///     <App>.app/Contents/android  ->   ~/Library/Application Support/MacApk/<folder>
///     MacApk/<folder>/data/app    ->   <App>.app/Contents/app
///     MacApk/<folder>/data/data/<pkg>_<ver>/, sdcard/, .macapk/code/, ...
///
/// The runtime is still handed `<App>.app/Contents/android`, so every path a
/// guest sees is exactly what it saw before (measured 2026-10-19 on Flappy Bird:
/// launched from Finder through the link, the real folder's path appears nowhere
/// in the run log). That is load-bearing twice over: the real path has a space
/// ("Application Support"), which breaks guests that read their APK path out of
/// a jar URL (BundleGenerator.apkSafeDirName); and the folder is "MacApk", not
/// under "Mac.apk", because the runtime canonicalises some paths
/// (AppClassLoader's dylib getCanonicalPath) and a ".apk" substring breaks guests
/// that split a path at it (apk_unpacker.cpp ResolveAndroidRootDir).
///
/// `<folder>` is the package name (`-2`, `-3` for a second bundle of the same
/// package). Deleting it is "Clear data": the next launch recreates it empty.
/// Trashing the .app leaves it behind (the macOS norm); Mac.apk's uninstall
/// trashes both (BundleGenerator.remove).
///
/// THE OWNERSHIP RECORD is the folder's `data/app` link: it names the bundle
/// that owns the folder. That is what lets a move interrupted after the rename
/// but before the bundle's link was made be finished on the next launch.
///
/// THE MOVE is three renames on one volume (never a copy): `data/app` into
/// `Contents/app`, the tree to `<folder>`, then the link. Each failure undoes
/// what came before it. `MACAPK_APPDATA_IN_BUNDLE=1`, or the file
/// `<supportDir>/appdata-in-bundle`, stops new moves (moved apps stay moved);
/// `debug/appdata_layout.sh revert` puts an app's data back inside its bundle.
public enum AppDataLayout {

    public static var root: URL { Paths.appDataDir }

    public static func androidLink(_ bundle: URL) -> String { bundle.path + "/Contents/android" }
    public static func appDir(_ bundle: URL) -> String { bundle.path + "/Contents/app" }

    /// The kill switch for NEW moves (see the type comment).
    public static var movesDisabled: Bool {
        if ProcessInfo.processInfo.environment["MACAPK_APPDATA_IN_BUNDLE"] == "1" { return true }
        return FileManager.default.fileExists(
            atPath: Paths.supportDir.appendingPathComponent("appdata-in-bundle").path)
    }

    /// The real data folder of a bundle whose data has moved out, or nil.
    public static func dataFolder(ofBundle bundle: URL) -> URL? {
        guard let t = readLink(androidLink(bundle)) else { return nil }
        return URL(fileURLWithPath: t, isDirectory: true)
    }

    // POSIX on purpose: FileManager resolves symlinks in the URLs it hands back,
    // and every decision here is about the link itself.
    static func fileType(_ p: String) -> mode_t? {
        var st = stat()
        return lstat(p, &st) == 0 ? (st.st_mode & S_IFMT) : nil
    }
    static func isDir(_ p: String) -> Bool { fileType(p) == S_IFDIR }
    static func readLink(_ p: String) -> String? {
        var buf = [CChar](repeating: 0, count: Int(PATH_MAX) + 1)
        let n = readlink(p, &buf, Int(PATH_MAX))
        guard n > 0 else { return nil }
        buf[n] = 0
        return String(cString: buf)
    }
    static func errText() -> String { String(cString: strerror(errno)) }

    /// `<root>/<package>`, or the first free `-N` sibling. Never an existing
    /// name: a leftover folder is somebody's data (a trashed bundle's, or one
    /// the user kept) and is not silently handed to a new install.
    static func freeFolder(for package: String) -> URL? {
        var base = String(package.unicodeScalars.map { c -> Character in
            switch c {
            case "a"..."z", "A"..."Z", "0"..."9", ".", "_", "-": return Character(c)
            default: return "_"
            }
        })
        while base.hasPrefix(".") { base.removeFirst() }
        if base.isEmpty { base = "app" }
        for n in 1...99 {
            let u = root.appendingPathComponent(n == 1 ? base : "\(base)-\(n)", isDirectory: true)
            if fileType(u.path) == nil { return u }
        }
        return nil
    }

    /// The folder whose `data/app` link names this bundle: a move that renamed
    /// the tree but died before linking it.
    static func folderOwned(by bundle: URL) -> URL? {
        let want = appDir(bundle)
        guard let kids = try? FileManager.default.contentsOfDirectory(atPath: root.path)
        else { return nil }
        for k in kids.sorted() where !k.hasPrefix(".") {
            let f = root.appendingPathComponent(k, isDirectory: true)
            if readLink(f.path + "/data/app") == want { return f }
        }
        return nil
    }

    /// The skeleton a fresh tree needs (the same two dirs the stub creates):
    /// data/data and .macapk/code. The runtime makes the rest.
    static func createSkeleton(_ folder: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: folder.appendingPathComponent("data/data", isDirectory: true),
                               withIntermediateDirectories: true)
        try fm.createDirectory(at: folder.appendingPathComponent(".macapk/code", isDirectory: true),
                               withIntermediateDirectories: true)
    }

    /// Point `<folder>/data/app` at this bundle's `Contents/app`. Heals a bundle
    /// that was moved or renamed (the link is absolute), and brings an install
    /// dir that landed in the folder back into the bundle.
    static func repairAppLink(folder: URL, bundle: URL, log: (String) -> Void) {
        let inApp = folder.path + "/data/app"
        let app = appDir(bundle)
        if isDir(inApp) {
            guard fileType(app) == nil else {
                log("app data: both \(inApp) and \(app) exist; leaving both as they are")
                return
            }
            if rename(inApp, app) != 0 {
                log("app data: could not move \(inApp) into the bundle: \(errText())")
                return
            }
            log("app data: moved the installed package back into \(app)")
        }
        if fileType(app) == nil {
            try? FileManager.default.createDirectory(atPath: app, withIntermediateDirectories: true)
        }
        if let have = readLink(inApp) {
            if have == app { return }
            unlink(inApp)
            log("app data: \(inApp) pointed at \(have); now \(app) (the bundle moved)")
        }
        try? FileManager.default.createDirectory(atPath: folder.path + "/data",
                                                 withIntermediateDirectories: true)
        if symlink(app, inApp) != 0 {
            log("app data: could not link \(inApp) -> \(app): \(errText())")
        }
    }

    /// Make `bundle` use the out-of-bundle layout. Idempotent and cheap once
    /// done. The caller must hold what keeps the app from running meanwhile:
    /// the stub holds its pidfile flock, an install holds the install lock on a
    /// bundle it is generating; `mayMove: false` (the stub without its lock)
    /// limits this to the repairs. Returns the data folder, or nil when the data
    /// stays inside the bundle (moves disabled, another volume, or a failure
    /// that was rolled back -- the app still launches from the bundle then).
    @discardableResult
    public static func ensure(bundle: URL, package: String, mayMove: Bool = true,
                              log: (String) -> Void) -> URL? {
        let link = androidLink(bundle)
        // Already moved: heal the folder and its link back to the bundle.
        if let target = readLink(link) {
            let folder = URL(fileURLWithPath: target, isDirectory: true)
            if !isDir(folder.path) {
                // The user deleted it ("clear data"), or it never existed.
                do { try createSkeleton(folder) } catch {
                    log("app data: \(folder.path) is missing and could not be created: \(error)")
                    return nil
                }
                log("app data: \(folder.path) was missing; created it empty, "
                    + "so the app starts with fresh data")
            }
            repairAppLink(folder: folder, bundle: bundle, log: log)
            return folder
        }
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        } catch {
            log("app data: could not create \(root.path): \(error)")
            return nil
        }
        // Nothing at Contents/android: finish an interrupted move, or start a
        // bundle that never had a tree.
        if fileType(link) == nil {
            if let owned = folderOwned(by: bundle) {
                if symlink(owned.path, link) == 0 {
                    log("app data: finished an interrupted move, \(link) -> \(owned.path)")
                    return owned
                }
                log("app data: could not link \(link) -> \(owned.path): \(errText())")
                return nil
            }
            if movesDisabled { return nil }
            guard let folder = freeFolder(for: package) else {
                log("app data: no free folder name for \(package) under \(root.path)")
                return nil
            }
            do { try createSkeleton(folder) } catch {
                log("app data: could not create \(folder.path): \(error)")
                return nil
            }
            repairAppLink(folder: folder, bundle: bundle, log: log)
            if symlink(folder.path, link) != 0 {
                log("app data: could not link \(link) -> \(folder.path): \(errText())")
                try? FileManager.default.removeItem(at: folder)
                return nil
            }
            log("app data: new folder \(folder.path)")
            return folder
        }
        guard isDir(link) else {
            log("app data: \(link) is neither a folder nor a link; leaving it")
            return nil
        }
        if movesDisabled { return nil }
        guard mayMove else {
            log("app data: still inside the bundle; it moves on a launch that holds the app's lock")
            return nil
        }
        return move(bundle: bundle, package: package, log: log)
    }

    /// The legacy layout -> the new one. See the type comment for the steps.
    static func move(bundle: URL, package: String, log: (String) -> Void) -> URL? {
        let src = androidLink(bundle)
        let app = appDir(bundle)
        let inApp = src + "/data/app"
        // Same volume or nothing: a copy of a multi-GB tree on a full disk is the
        // failure this must never cause. /Applications and ~/Library are both on
        // the Data volume on a stock Mac.
        var sa = stat(), sb = stat()
        guard stat(bundle.path + "/Contents", &sa) == 0, stat(root.path, &sb) == 0,
              sa.st_dev == sb.st_dev else {
            log("app data: \(bundle.path) is not on the same volume as \(root.path); "
                + "leaving its data inside the bundle")
            return nil
        }
        guard let folder = freeFolder(for: package) else {
            log("app data: no free folder name for \(package) under \(root.path)")
            return nil
        }
        // 1. The installed package into the bundle, linked from where it was.
        var movedApp = false, linkedApp = false
        if isDir(inApp) {
            guard fileType(app) == nil else {
                log("app data: \(app) already exists beside \(inApp); leaving the data inside")
                return nil
            }
            guard rename(inApp, app) == 0 else {
                log("app data: could not move \(inApp) to \(app): \(errText())")
                return nil
            }
            movedApp = true
        } else if fileType(app) == nil {
            try? FileManager.default.createDirectory(atPath: app, withIntermediateDirectories: true)
        }
        func undoApp() {
            if linkedApp { unlink(inApp) }
            if movedApp { rename(app, inApp) }
        }
        if readLink(inApp) != app {
            if fileType(inApp) != nil { unlink(inApp) }
            try? FileManager.default.createDirectory(atPath: src + "/data",
                                                     withIntermediateDirectories: true)
            guard symlink(app, inApp) == 0 else {
                log("app data: could not link \(inApp) -> \(app): \(errText())")
                undoApp()
                return nil
            }
            linkedApp = true
        }
        // 2. The tree out of the bundle.
        guard rename(src, folder.path) == 0 else {
            log("app data: could not move \(src) to \(folder.path): \(errText())")
            undoApp()
            return nil
        }
        // 3. The bundle's link to it.
        guard symlink(folder.path, src) == 0 else {
            log("app data: could not link \(src) -> \(folder.path): \(errText()); moving it back")
            if rename(folder.path, src) == 0 { undoApp() }
            return nil
        }
        log("app data: moved out of the bundle to \(folder.path)")
        return folder
    }

    /// Trash the data folder of a bundle that is being uninstalled. Only the
    /// folder this bundle's link names, and only a direct child of `root`.
    /// Read the folder BEFORE the bundle goes (the link goes with it).
    public static func trashFolder(_ folder: URL, log: (String) -> Void) {
        let parent = folder.deletingLastPathComponent().standardizedFileURL.path
        guard parent == root.standardizedFileURL.path, isDir(folder.path) else {
            log("app data: not trashing \(folder.path) (not a folder directly in \(root.path))")
            return
        }
        do {
            try FileManager.default.trashItem(at: folder, resultingItemURL: nil)
            log("app data: trashed \(folder.path)")
        } catch {
            log("app data: could not trash \(folder.path): \(error)")
        }
    }
}

// MARK: - JSON value (unknown-field preservation)

/// A minimal any-JSON value. Exists so the registry can round-trip keys it
/// does not know about instead of deleting them on the next write. Without
/// this, an older build reading and re-saving a file written by a newer one
/// would silently strip the newer one's settings -- the failure mode the
/// compat rules below are meant to prevent.
/// Stamping an app bundle's icon: the ONE implementation, shared by every
/// binary this file compiles into (the launcher, `macapk-installhelper`, and the
/// per-app stub).
///
/// WHY IT LIVES HERE rather than in BundleGenerator, which is where the icns
/// recipe started: BundleGenerator is NOT compiled into the app stub, and the
/// stub is the only process that runs on every Dock launch and therefore the
/// only one positioned to SELF-HEAL a bundle whose icon never got stamped.
/// Duplicating the recipe into the stub was the obvious move and the wrong one:
/// on 2026-08-17 an identical "fix it in one place, miss the twin" mistake with
/// bundle-resource resolution shipped three separate user-visible bugs, one of
/// them twelve lines from the fix. So: one implementation, three callers.
///
/// `sips` and `iconutil` are both in /usr/bin, which matters -- a Dock-launched
/// process gets PATH=/usr/bin:/bin:/usr/sbin:/sbin with NO Homebrew, and a bare
/// tool name there is how three apps came up blank earlier this month.
public enum IconStamp {

    /// Every iconset slot macOS defines, smallest pixel edge first: (pixel
    /// edge, file name). This is Apple's "complete set", in the order the
    /// pre-v1.0.1650 recipe rasterized it (the order is kept so the =0 arm of
    /// MACAPK_ICNS_NO_UPSCALE runs the identical sips sequence).
    static let fullLadder: [(Int, String)] = [
        (16, "icon_16x16"), (32, "icon_16x16@2x"), (32, "icon_32x32"),
        (64, "icon_32x32@2x"), (128, "icon_128x128"), (256, "icon_128x128@2x"),
        (256, "icon_256x256"), (512, "icon_256x256@2x"), (512, "icon_512x512"),
        (1024, "icon_512x512@2x"),
    ]

    /// MACAPK_ICNS_NO_UPSCALE (v1.0.1650, default ON; =0 restores the full
    /// 16..1024 ladder, byte-for-byte the pre-change icns).
    ///
    /// WHY: the recipe used to rasterize all ten slots whatever the source
    /// resolution, so `sips -z` UPSCALED a small Android launcher icon to 256,
    /// 512 and 1024 px. Those representations carry no information the source
    /// did not have, and they dominate the file. Measured 2026-09-26 on the 18
    /// installed Mac.apk bundles: 16 have 72-192 px sources and their icns
    /// total ~11 MB; Frozen Bubble's 96 px icon became 1,096,022 bytes, of
    /// which `ic10` (the 1024 px slot) alone is 484,354.
    /// (docs/fleet-misc-rocks-frozen-sgt-anarch_2026-09-26.md, side finding.)
    ///
    /// WHY a partial set is correct and not a shortcut: Apple's own iconset
    /// guidance says "it is not a requirement to have a complete set; the
    /// system will choose the best representation for sizes and resolutions
    /// that you don't supply" (HighResolutionOSX, "Optimizing", Provide
    /// High-Resolution Versions of All App Graphics Resources). The icns
    /// container is a list of independent typed elements (IconStorage.h:
    /// IconFamilyResource / IconFamilyElement), and /usr/bin/iconutil packs a
    /// partial .iconset without complaint (verified on macOS 26). So the Dock
    /// and Finder scale the largest representation we DO supply. Measured
    /// cost, drawing both icns through NSImage: bit-identical at every slot
    /// the source reaches, and at every size when the source IS a slot size
    /// (sips and AppKit's high-quality scale are the same resampler); above
    /// the cap of any other source, a mean 1.4-3.1/255 difference, because
    /// the system then rescales our top slot instead of the source (two
    /// interpolations instead of one). Gate: debug/w5launchericon_gate.sh;
    /// numbers: docs/icns-no-upscale-ladder_2026-09-26.md.
    static var noUpscale: Bool {
        ProcessInfo.processInfo.environment["MACAPK_ICNS_NO_UPSCALE"] != "0"
    }

    /// Pixel size of a PNG, read from its IHDR chunk (W3C PNG spec 5.2, the
    /// 8-byte signature; 11.2.1, "The IHDR chunk shall be the first chunk",
    /// width and height are its first two big-endian 4-byte fields, and "Zero
    /// is an invalid value" for either). nil for anything that
    /// is not a well-formed PNG header, so the caller can fall back to the full
    /// ladder rather than guess. Read here instead of through ImageIO because
    /// this file's layering rule is Foundation + CryptoKit + Darwin only, and
    /// it is compiled into the per-app stub.
    static func pngPixelSize(at url: URL) -> (width: Int, height: Int)? {
        guard let fh = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? fh.close() }
        guard let head = try? fh.read(upToCount: 24), head.count == 24 else { return nil }
        let b = [UInt8](head)
        let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        guard Array(b[0..<8]) == signature,
              b[12] == 0x49, b[13] == 0x48, b[14] == 0x44, b[15] == 0x52  // "IHDR"
        else { return nil }
        func be32(_ o: Int) -> Int {
            Int(b[o]) << 24 | Int(b[o + 1]) << 16 | Int(b[o + 2]) << 8 | Int(b[o + 3])
        }
        let w = be32(16), h = be32(20)
        guard w > 0, h > 0 else { return nil }   // the spec forbids 0
        return (w, h)
    }

    /// The slots worth rasterizing for a source whose LONGER edge is `edge`:
    /// every slot up to and including the smallest one that holds all of the
    /// source's pixels, and nothing above it. The longer edge, because
    /// `sips -z` squares a non-square source and no source pixel may be lost.
    /// nil (unknown size) or an edge above 1024 yields the full ladder, which
    /// is exactly what the recipe produced before the gate existed.
    static func ladder(forSourceEdge edge: Int?) -> [(Int, String)] {
        guard let edge, edge > 0,
              let cap = fullLadder.map({ $0.0 }).first(where: { $0 >= edge })
        else { return fullLadder }
        return fullLadder.filter { $0.0 <= cap }
    }

    /// Rasterize `png` into the iconset slots its resolution supports (all
    /// ten with MACAPK_ICNS_NO_UPSCALE=0) and pack them into `icns`.
    /// Best-effort: returns false rather than throwing, because no icon is ever
    /// worth failing an install or blocking an app launch over.
    @discardableResult
    public static func buildIcns(from source: URL, to icns: URL) -> Bool {
        // A source that is not a PNG by signature (a WebP/JPEG the APK shipped as
        // its launcher icon) is normalised to a scratch PNG first: `pngPixelSize`
        // can only read a PNG header (nil selected the full UPSCALE ladder), and
        // `sips` cannot write anything but its input's format (see the slot loop).
        // A PNG source never takes this branch, so its recipe is unchanged.
        var png = source
        var scratch: URL? = nil
        if !isPNG(at: source) {
            let dir = FileManager.default.temporaryDirectory
                .appendingPathComponent("macapk-icns-src-\(UUID().uuidString)", isDirectory: true)
            let out = dir.appendingPathComponent("source.png")
            if (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil {
                scratch = dir
                if runIconTool("/usr/bin/sips", ["-s", "format", "png", source.path,
                                                 "--out", out.path]) == 0, isPNG(at: out) {
                    png = out
                }
            }
        }
        defer { if let scratch { try? FileManager.default.removeItem(at: scratch) } }
        guard noUpscale else { return buildIcns(from: png, to: icns, sizes: fullLadder) }
        let edge = pngPixelSize(at: png).map { max($0.width, $0.height) }
        let sizes = ladder(forSourceEdge: edge)
        if buildIcns(from: png, to: icns, sizes: sizes) { return true }
        // A partial set that failed to pack must not cost the bundle its icon:
        // retry with the complete set, which is the recipe that has always
        // worked. The gate asserts the partial set packs on its own (a retry
        // would leave `ic10` in the output and fail it), so this net cannot
        // hide a regression of the partial path.
        return sizes.count != fullLadder.count
            && buildIcns(from: png, to: icns, sizes: fullLadder)
    }

    /// The recipe itself: one `sips -z` per slot into a scratch .iconset, then
    /// `iconutil -c icns`. With `sizes == fullLadder` this is, call for call,
    /// the pre-v1.0.1650 body of buildIcns(from:to:).
    static func buildIcns(from png: URL, to icns: URL, sizes: [(Int, String)]) -> Bool {
        let fm = FileManager.default
        let work = fm.temporaryDirectory
            .appendingPathComponent("macapk-icns-\(UUID().uuidString)", isDirectory: true)
        let iconset = work.appendingPathComponent("AppIcon.iconset", isDirectory: true)
        defer { try? fm.removeItem(at: work) }
        do {
            try fm.createDirectory(at: iconset, withIntermediateDirectories: true)
        } catch { return false }
        for (px, fname) in sizes {
            // `-s format png` is load-bearing: sips keeps the SOURCE's format
            // when writing, and it can READ WebP but not WRITE it ("Error:
            // Can't write format: org.webmproject.webp"). An Android launcher
            // icon that is a .webp raster (Open Sudoku, Smash Hit, Gallery on
            // the owner's machine, 2026-09-29) therefore produced NO slot at
            // all, iconutil returned 1, and the bundle kept the generator's
            // placeholder icns. For a PNG source the output is byte-identical
            // to the recipe without the flag (measured, md5-equal).
            _ = runIconTool("/usr/bin/sips",
                            ["-s", "format", "png", "-z", "\(px)", "\(px)", png.path,
                             "--out", iconset.appendingPathComponent("\(fname).png").path])
        }
        return runIconTool("/usr/bin/iconutil",
                           ["-c", "icns", iconset.path, "-o", icns.path]) == 0
    }

    /// Write both icon forms into `bundle`: `AppIcon.png` (which the runtime
    /// adopts as its live Dock tile via MACAPK_APP_ICON) and `AppIcon.icns`
    /// (which Finder, Launchpad and the pre-launch Dock read from the bundle).
    @discardableResult
    public static func stamp(bundle: URL, from png: URL) -> Bool {
        let fm = FileManager.default
        guard fm.fileExists(atPath: png.path) else { return false }
        let res = bundle.appendingPathComponent("Contents/Resources", isDirectory: true)
        guard (try? fm.createDirectory(at: res, withIntermediateDirectories: true)) != nil
        else { return false }
        let dstPNG = res.appendingPathComponent("AppIcon.png")
        try? fm.removeItem(at: dstPNG)
        // A PNG is copied verbatim (the placeholder test compares bytes). Any
        // other raster the APK shipped under an "icon.png" name (the runtime
        // writes the APK's entry as it is, apk_unpacker.cpp: WebP is common)
        // is transcoded, so AppIcon.png is a REAL PNG: the icns is built from
        // it, and pngPixelSize can read its size for the no-upscale ladder
        // (it returns nil for WebP, which used to select the full upscale
        // ladder). The copy is only the fallback if sips cannot decode it.
        if isPNG(at: png) {
            try? fm.copyItem(at: png, to: dstPNG)
        } else if runIconTool("/usr/bin/sips", ["-s", "format", "png", png.path,
                                                "--out", dstPNG.path]) != 0
                    || !isPNG(at: dstPNG) {
            try? fm.removeItem(at: dstPNG)
            try? fm.copyItem(at: png, to: dstPNG)
        }
        return buildIcns(from: fm.fileExists(atPath: dstPNG.path) ? dstPNG : png,
                         to: res.appendingPathComponent("AppIcon.icns"))
    }

    /// True when the file starts with the 8-byte PNG signature (W3C PNG 5.2).
    static func isPNG(at url: URL) -> Bool {
        guard let fh = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? fh.close() }
        guard let head = try? fh.read(upToCount: 8), head.count == 8 else { return false }
        return [UInt8](head) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    }

    /// Self-heal: stamp only when the bundle has NO `AppIcon.icns` yet.
    ///
    /// The hole this closes: `BundleGenerator.generate()` runs BEFORE the app is
    /// extracted, so at that moment there is no real icon and the bundle gets
    /// the generic default; the app's OWN icon only becomes available later,
    /// when the framework rasterizes it (an `<adaptive-icon>`/vector icon needs
    /// the real Drawable pipeline, which only exists once a JVM is up). If that
    /// later stamp does not happen, the bundle keeps a generic Finder icon
    /// forever even though a perfectly good `icon.png` is sitting in the app's
    /// data dir. Measured 2026-08-17: PlayTube, installed from inside F-Droid,
    /// was the only app of 12 on disk with no `AppIcon.icns`, while its data dir
    /// held a 3219-byte `icon.png`.
    ///
    /// Deliberately does NOT overwrite an existing icns -- a bundle that already
    /// has an icon is not a problem to solve, and re-running sips 10 times on
    /// every launch would be pure waste.
    @discardableResult
    public static func backfillIfMissing(bundle: URL, from png: URL) -> Bool {
        let icns = bundle.appendingPathComponent("Contents/Resources/AppIcon.icns")
        if FileManager.default.fileExists(atPath: icns.path) { return false }
        return stamp(bundle: bundle, from: png)
    }

    /// Self-heal, second half: the bundle HAS an icon, but it is still the
    /// generic placeholder generate() stamps before the app's own icon exists.
    ///
    /// `backfillIfMissing` alone never fires for that case, because generate()
    /// ALWAYS leaves an icns behind -- the placeholder -- when it has no real
    /// icon yet, so "no icns" is almost never the state a bundle is in. The app
    /// whose android:icon is an <adaptive-icon>/vector (no raster mipmap the
    /// installer can copy) therefore kept the placeholder in Finder, Launchpad
    /// and Spotlight forever, while the RUNNING app showed its real icon in the
    /// Dock (the runtime adopts the rasterized icon.png itself). MEASURED
    /// 2026-09-29 (owner: "Icon shows in dock but not app drawer", Open Sudoku
    /// and Smash Hit): Open Sudoku.app, Smash Hit.app, Gallery.app and
    /// RVTest.app all carried AppIcon.icns md5 fe9cbe91..., 69797 bytes, while
    /// each data dir held its own icon.png (4303 / 86576 / 7390 bytes) written
    /// by the first launch.
    ///
    /// A placeholder is recognised by CONTENT: the bundle's AppIcon.png is
    /// byte-identical to one of `placeholders` (the default icon the generator
    /// copied). Stamps only when the app's own png exists and is not itself the
    /// placeholder, so a genuine icon is never replaced. On a stamp it also does
    /// what finalizeIdentity does after an icon change: bump the bundle's mtime
    /// (IconServices validates its per-path cache against it) and re-register
    /// with LaunchServices, which is what Launchpad and the Dock read.
    @discardableResult
    public static func backfillIfMissingOrPlaceholder(bundle: URL, from png: URL,
                                                      placeholders: [URL]) -> Bool {
        let fm = FileManager.default
        guard fm.fileExists(atPath: png.path) else { return false }
        let res = bundle.appendingPathComponent("Contents/Resources")
        let icns = res.appendingPathComponent("AppIcon.icns")
        if fm.fileExists(atPath: icns.path) {
            let havePNG = res.appendingPathComponent("AppIcon.png")
            guard let have = try? Data(contentsOf: havePNG),
                  let own = try? Data(contentsOf: png) else { return false }
            let ph = placeholders.compactMap { try? Data(contentsOf: $0) }
            let placeholderKept = ph.contains(have) && !ph.contains(own) && own != have
            // The SECOND heal case, and the one the owner's bundles are in: the
            // installer copied the real icon to AppIcon.png but it is not a PNG
            // (a WebP the runtime wrote under the name icon.png), so sips could
            // not write any slot, the icns was never built, and the placeholder
            // icns from generate() stayed. AppIcon.png is a real icon, so the
            // byte-equal-to-placeholder test above cannot see it. Terminates:
            // stamp() leaves a real PNG behind. A source sips cannot decode at
            // all stays a bounded retry per launch, never a loop within one.
            let icnsNeverBuilt = !isPNG(at: havePNG) && !ph.contains(own)
            guard placeholderKept || icnsNeverBuilt else { return false }
        }
        guard stamp(bundle: bundle, from: png) else { return false }
        try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: bundle.path)
        _ = runIconTool(LaunchServicesTool.lsregister, ["-f", bundle.path])
        return true
    }

    private static func runIconTool(_ path: String, _ args: [String]) -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do { try p.run(); p.waitUntilExit(); return p.terminationStatus }
        catch { return -1 }
    }
}

/// The LaunchServices registration tool (base OS; the same path
/// BundleGenerator uses). Shared so the stub, which compiles only this file
/// and AppStubMain.swift, can re-register a bundle it repaired.
public enum LaunchServicesTool {
    public static let lsregister =
        "/System/Library/Frameworks/CoreServices.framework/Versions/A/"
        + "Frameworks/LaunchServices.framework/Versions/A/Support/lsregister"
}

/// Show an app by its Android LABEL everywhere macOS names it, while its
/// bundle FILE keeps the unique name the installer had to give it.
///
/// THE COLLISION. /Applications is one flat namespace. Google's Calculator
/// lands next to /Applications/Calculator.app (OpenCalc, installed first) and
/// VLC for Android next to the real VLC.app, so resolveBundleName gives them
/// "Calculator (calculator).app" and "VLC (vlc).app". That file name must stay
/// unique -- but Android shows only the label, and the owner saw the suffix in
/// the Dock, Launchpad and Finder (2026-09-29: "google calculator says
/// (calculator) after its name", VLC "app name is vlc (vlc)").
///
/// WHY CFBundleDisplayName ALONE DID NOT DO IT. Info.plist already said
/// CFBundleDisplayName = "Calculator". Apple, Information Property List,
/// CFBundleDisplayName: "In macOS, before displaying a localized name for your
/// bundle, the Finder compares the value of this key against the actual name of
/// your bundle in the file system. If the two names match, the Finder proceeds
/// to display the localized name from the appropriate InfoPlist.strings file of
/// your bundle. If the names do not match, the Finder displays the file-system
/// name." So the plain label is shown ONLY through a localization, and only
/// when the unlocalized key equals the file name.
///
/// THE SHAPE (verified 2026-09-29 against LaunchServices on this macOS with
/// URLResourceValues.localizedName and FileManager.displayName(atPath:), on
/// three bundles named "X (calculator).app": plist display name == label ->
/// "X (calculator)" [today's shape]; plist display name == FILE name +
/// LSHasLocalizedDisplayName + {Base,en}.lproj/InfoPlist.strings carrying the
/// label -> "X"; the same strings but the plist key != file name -> the file
/// name again, i.e. exactly Apple's rule):
///   Info.plist   CFBundleDisplayName        = <file name, no .app>
///                LSHasLocalizedDisplayName  = true
///                CFBundleDevelopmentRegion  = en   (the fallback localization
///                                                   for a user language we do
///                                                   not ship)
///                CFBundleName               = <label>   (menu bar, unchanged)
///   Resources/{Base,en}.lproj/InfoPlist.strings
///                "CFBundleDisplayName" = "<label>"; "CFBundleName" = "<label>";
/// Applied to EVERY generated bundle, not just the colliding ones, so there is
/// one shape: when the file name IS the label the localization is simply the
/// same string.
///
/// The Android label therefore lives in CFBundleName (and the strings file), no
/// longer in CFBundleDisplayName; `label(fromPlist:)` is the one reader.
public enum BundleDisplayName {
    static let localizations = ["Base", "en"]

    /// The Android label a bundle carries: CFBundleName first (the label in
    /// every bundle the generator ever wrote), then CFBundleDisplayName.
    public static func label(fromPlist plist: [String: Any]) -> String? {
        if let n = plist["CFBundleName"] as? String, !n.isEmpty { return n }
        if let n = plist["CFBundleDisplayName"] as? String, !n.isEmpty { return n }
        return nil
    }

    /// The name LaunchServices should SHOW: the Android label -- except when the
    /// bundle file deliberately carries the version (the control panel's
    /// "show version in name" dev toggle, BundleGenerator.showVersionInName,
    /// names it "<label> <version>"), the one case where the FILE name is the
    /// wanted display name. Every collision suffix ("(calculator)", " 2") and
    /// every file-name sanitization ("Plague Inc" for "Plague Inc.") shows the
    /// label, as Android does.
    public static func shownName(label: String, fileName: String,
                                 versionName: String?) -> String {
        if let v = versionName, !v.isEmpty, fileName.hasSuffix(" " + v) { return fileName }
        return label
    }

    static func stringsBody(label: String, shown: String) -> String {
        func q(_ s: String) -> String {
            s.replacingOccurrences(of: "\\", with: "\\\\")
             .replacingOccurrences(of: "\"", with: "\\\"")
             .replacingOccurrences(of: "\n", with: " ")
        }
        return "/* Mac.apk: the Android label, shown by LaunchServices while the "
             + "bundle file keeps its unique name. See BundleDisplayName. */\n"
             + "\"CFBundleDisplayName\" = \"\(q(shown))\";\n"
             + "\"CFBundleName\" = \"\(q(label))\";\n"
    }

    /// Write the per-localization strings: CFBundleDisplayName = `shown`
    /// (default: the label; see shownName), CFBundleName = the label (menu
    /// bar). Returns true if any file changed.
    @discardableResult
    public static func writeStrings(bundle: URL, label: String, shown: String? = nil) -> Bool {
        let fm = FileManager.default
        let body = Data(stringsBody(label: label, shown: shown ?? label).utf8)
        var changed = false
        for loc in localizations {
            let dir = bundle.appendingPathComponent("Contents/Resources/\(loc).lproj",
                                                    isDirectory: true)
            let f = dir.appendingPathComponent("InfoPlist.strings")
            if (try? Data(contentsOf: f)) == body { continue }
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            if (try? body.write(to: f, options: .atomic)) != nil { changed = true }
        }
        return changed
    }

    /// Bring an EXISTING bundle to the shape above (the stub runs this on every
    /// launch; it is a few stats when nothing is wrong). Rewrites Info.plist
    /// through PropertyListSerialization only when a key is off. Returns true
    /// when it changed anything, after re-registering with LaunchServices so
    /// the Dock, Launchpad and Finder read the new name.
    @discardableResult
    public static func repairIfNeeded(bundle: URL) -> Bool {
        let plistURL = bundle.appendingPathComponent("Contents/Info.plist")
        guard let data = try? Data(contentsOf: plistURL),
              var plist = (try? PropertyListSerialization.propertyList(
                    from: data, options: [], format: nil)) as? [String: Any],
              plist["MacapkPackage"] is String,            // generated by Mac.apk
              let label = label(fromPlist: plist) else { return false }
        let fileName = bundle.deletingPathExtension().lastPathComponent
        let shown = shownName(label: label, fileName: fileName,
                              versionName: plist["MacapkVersionName"] as? String)
        // Only a bundle whose FILE name differs from the name it should show
        // displays wrong, so only that bundle is touched after install. Every
        // other one already shows the right name, and leaving it byte-for-byte
        // alone keeps this repair off bundles whose install-time signature may
        // still bind Info.plist. (The colliding bundles on the owner's machine
        // were checked: `codesign -dvvv` reports Info.plist=not bound on each --
        // their stubs were refreshed from the unbound template.)
        if fileName == shown && (plist["LSHasLocalizedDisplayName"] as? Bool) != true {
            return false
        }
        var plistChanged = false
        if (plist["CFBundleDisplayName"] as? String) != fileName {
            plist["CFBundleDisplayName"] = fileName; plistChanged = true
        }
        if (plist["LSHasLocalizedDisplayName"] as? Bool) != true {
            plist["LSHasLocalizedDisplayName"] = true; plistChanged = true
        }
        if plist["CFBundleDevelopmentRegion"] == nil {
            plist["CFBundleDevelopmentRegion"] = "en"; plistChanged = true
        }
        // The label moves to CFBundleName if an older bundle only had it in
        // CFBundleDisplayName (label(fromPlist:) read it from there).
        if (plist["CFBundleName"] as? String) != label {
            plist["CFBundleName"] = label; plistChanged = true
        }
        var changed = writeStrings(bundle: bundle, label: label, shown: shown)
        if plistChanged,
           let out = try? PropertyListSerialization.data(fromPropertyList: plist,
                                                         format: .xml, options: 0),
           (try? out.write(to: plistURL, options: .atomic)) != nil {
            changed = true
        }
        if changed {
            try? FileManager.default.setAttributes([.modificationDate: Date()],
                                                   ofItemAtPath: bundle.path)
            let p = Process()
            p.executableURL = URL(fileURLWithPath: LaunchServicesTool.lsregister)
            p.arguments = ["-f", bundle.path]
            p.standardOutput = FileHandle.nullDevice
            p.standardError = FileHandle.nullDevice
            if (try? p.run()) != nil { p.waitUntilExit() }
        }
        return changed
    }
}

public enum JSONValue: Codable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let b = try? c.decode(Bool.self) { self = .bool(b); return }
        if let d = try? c.decode(Double.self) { self = .number(d); return }
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let a = try? c.decode([JSONValue].self) { self = .array(a); return }
        if let o = try? c.decode([String: JSONValue].self) { self = .object(o); return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "unrepresentable JSON value")
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let d):
            // Emit whole numbers without a ".0" so a versionCode-shaped value
            // survives a round trip looking like an integer.
            if d == d.rounded() && abs(d) < 9.007199254740992e15 { try c.encode(Int64(d)) }
            else { try c.encode(d) }
        case .bool(let b): try c.encode(b)
        case .object(let o): try c.encode(o)
        case .array(let a): try c.encode(a)
        case .null: try c.encodeNil()
        }
    }

    public var stringValue: String? {
        switch self {
        case .string(let s): return s
        case .number(let d): return d == d.rounded() ? String(Int64(d)) : String(d)
        case .bool(let b): return b ? "1" : "0"
        default: return nil
        }
    }
}

// MARK: - Registry model

/// One installed app.
///
/// Identity is `dirName`, not `package`. That is not a style choice: the live
/// registry on this machine has THREE rows for `com.fingersoft.hillclimb`
/// (three versions of Hill Climb Racing, three data dirs), and the owner A/Bs
/// versions of the same game routinely. A package-keyed store would have
/// deleted two of sixty-three rows during migration, which is precisely the
/// harm the never-lose-an-entry rule exists to prevent.
public struct AppRecord: Codable, Equatable {
    public var package: String
    public var label: String
    /// The `<androidRoot>/data/data` subdirectory the runtime created. Unique
    /// per install; this is the identity key.
    public var dirName: String
    /// What dirName parsing produced. Kept verbatim so existing UI strings do
    /// not change under the user.
    public var version: String
    /// The manifest's `android:versionName`. Equals `version` for rows
    /// migrated from v1, where it is all we had.
    public var versionName: String
    /// The manifest's `android:versionCode`: the authoritative update
    /// comparator. 0 means "not known yet" (every v1-migrated row, and any
    /// install done before the runtime learned `--peek-manifest`).
    public var versionCode: Int64
    /// Where the APK lives NOW -- normally our retained copy under `apks/`.
    public var apkPath: String?
    /// Where it came from, for provenance only. Never launched from.
    public var originalApkPath: String?
    /// The generated per-app bundle, or nil when we did not make one
    /// (bundle generation not wired yet, or /Applications unwritable).
    public var bundlePath: String?
    public var installedAt: Date?
    public var updatedAt: Date?
    public var lastLaunchedAt: Date?
    /// `gui` | `finder` | `android` | `legacy`.
    public var source: String
    /// Per-app environment overrides applied at launch.
    public var env: [String: String]
    /// `installing` | `installed` | `broken`. Journalled so a crash mid-install
    /// is recoverable instead of leaving a row that looks fine and is not.
    public var state: String

    public init(package: String, label: String, dirName: String,
                version: String, versionName: String, versionCode: Int64,
                apkPath: String? = nil, originalApkPath: String? = nil,
                bundlePath: String? = nil,
                installedAt: Date? = nil, updatedAt: Date? = nil,
                lastLaunchedAt: Date? = nil,
                source: String = "gui", env: [String: String] = [:],
                state: String = AppState.installed) {
        self.package = package; self.label = label; self.dirName = dirName
        self.version = version; self.versionName = versionName
        self.versionCode = versionCode
        self.apkPath = apkPath; self.originalApkPath = originalApkPath
        self.bundlePath = bundlePath
        self.installedAt = installedAt; self.updatedAt = updatedAt
        self.lastLaunchedAt = lastLaunchedAt
        self.source = source; self.env = env; self.state = state
    }

    // Dates are hand-coded rather than left to JSONDecoder.dateDecodingStrategy
    // because `.iso8601` REJECTS fractional seconds, and a file written by any
    // other tool (or a future build that adds them) would then fail to parse --
    // turning a cosmetic difference into "your library is corrupt". Decode is
    // tolerant of both forms; encode always emits the strict form.
    enum CodingKeys: String, CodingKey {
        case package, label, dirName, version, versionName, versionCode
        case apkPath, originalApkPath, bundlePath
        case installedAt, updatedAt, lastLaunchedAt
        case source, env, state
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        package = try c.decode(String.self, forKey: .package)
        label = (try? c.decode(String.self, forKey: .label)) ?? package
        dirName = try c.decode(String.self, forKey: .dirName)
        version = (try? c.decode(String.self, forKey: .version)) ?? ""
        versionName = (try? c.decode(String.self, forKey: .versionName)) ?? version
        versionCode = (try? c.decode(Int64.self, forKey: .versionCode)) ?? 0
        apkPath = try? c.decodeIfPresent(String.self, forKey: .apkPath)
        originalApkPath = try? c.decodeIfPresent(String.self, forKey: .originalApkPath)
        bundlePath = try? c.decodeIfPresent(String.self, forKey: .bundlePath)
        installedAt = ISO8601.parse(try? c.decodeIfPresent(String.self, forKey: .installedAt))
        updatedAt = ISO8601.parse(try? c.decodeIfPresent(String.self, forKey: .updatedAt))
        lastLaunchedAt = ISO8601.parse(try? c.decodeIfPresent(String.self, forKey: .lastLaunchedAt))
        source = (try? c.decode(String.self, forKey: .source)) ?? "legacy"
        env = (try? c.decode([String: String].self, forKey: .env)) ?? [:]
        state = (try? c.decode(String.self, forKey: .state)) ?? AppState.installed
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(package, forKey: .package)
        try c.encode(label, forKey: .label)
        try c.encode(dirName, forKey: .dirName)
        try c.encode(version, forKey: .version)
        try c.encode(versionName, forKey: .versionName)
        try c.encode(versionCode, forKey: .versionCode)
        try c.encodeIfPresent(apkPath, forKey: .apkPath)
        try c.encodeIfPresent(originalApkPath, forKey: .originalApkPath)
        try c.encodeIfPresent(bundlePath, forKey: .bundlePath)
        try c.encodeIfPresent(ISO8601.format(installedAt), forKey: .installedAt)
        try c.encodeIfPresent(ISO8601.format(updatedAt), forKey: .updatedAt)
        try c.encodeIfPresent(ISO8601.format(lastLaunchedAt), forKey: .lastLaunchedAt)
        try c.encode(source, forKey: .source)
        try c.encode(env, forKey: .env)
        try c.encode(state, forKey: .state)
    }
}

/// The legal values of `AppRecord.state`, as constants so a typo is a
/// compile error at the call sites we control.
///
/// ONLY `installed` means "PackageManager must report this package". Every
/// other value is a package that is NOT installed, and both readers agree on
/// that by testing for this one string:
/// `MacApkPackageManager.InstalledRegistry.parse` skips any row whose state is
/// not `installed`, and the launcher's grid lists only those rows. That is why
/// a new state can be added here without touching the Java side.
public enum AppState {
    public static let installing = "installing"
    public static let installed  = "installed"
    public static let broken     = "broken"

    /// The control panel's own install path (MacApkLauncherApp's
    /// `InstalledApp`, every drag-drop / double-click / Open With install)
    /// wrote `"ok"` for an installed app until v1.0.2239. Swift's own readers
    /// mostly accepted it, but the guest-side readers did not
    /// (MacApkPackageManager's registry, SpecialAccessStore.selfIdentity) and
    /// neither did the install helper's CallerIdentity -- so such an app was
    /// invisible to PackageManager and had every runtime permission refused
    /// ("no installed app's bundle holds android root", Rhythm, issue #11).
    /// One spelling on every read: decode maps it, and the next write persists it.
    public static let legacyInstalled = "ok"
    public static func normalized(_ s: String) -> String {
        s == legacyInstalled ? installed : s
    }

    /// The app's macOS bundle is GONE from disk, so the package is not
    /// installed -- but we still know about it, and its retained APK is still
    /// under `Paths.apksDir`, so reinstalling is a local copy rather than a
    /// re-download.
    ///
    /// WHY A STATE AND NOT A ROW DELETION. macOS lets the user drag an .app to
    /// the Trash at any time with no notification to us, so the registry is a
    /// CACHE of reality, not the source of it -- and on a device there is no
    /// state in which PackageManager reports a package whose code is gone.
    /// Deleting the row instead would answer PackageManager correctly and pay
    /// for it by discarding the retained APK pointer (197 MB of not
    /// re-downloading for the app that prompted this), the per-app env
    /// overrides, and the provenance -- unrecoverably, on an inference drawn
    /// from a `stat`. Marking is reversible: put the bundle back and the very
    /// next read flips the row to `installed` again.
    ///
    /// AOSP PRECEDENT for "known but not installed": `ApplicationInfo` keeps a
    /// package's record with `FLAG_INSTALLED` (1<<23, "true if the application
    /// is currently installed for the calling user") CLEARED, and
    /// `FLAG_IS_DATA_ONLY` (1<<24) exists for exactly the case where "the
    /// application only has its data installed; the application package itself
    /// does not currently exist on the device". Such a package is invisible to
    /// an unflagged query and needs `MATCH_UNINSTALLED_PACKAGES` to be seen.
    /// `removed` is that same shape with the halves swapped (we keep the source
    /// APK, the data went with the bundle).
    public static let removed    = "removed"
}

// MARK: - Bundle presence (the ONE liveness predicate)

/// What a `stat` of a recorded bundle path actually established.
///
/// Three answers rather than a Bool, because "the directory is not there" and
/// "I could not find out" are different facts and only the first one may ever
/// change a registry row. A `Bool` collapses them, and the collapsed value
/// defaults to the destructive reading.
public enum BundlePresence: Equatable {
    /// The bundle is on disk.
    case present
    /// The bundle is NOT on disk, and its parent directory is present,
    /// readable and populated -- so the bundle's absence is a real absence and
    /// not a symptom of the volume/folder being unreachable.
    case absent
    /// We could not establish either. ALWAYS treated as "still installed".
    case unknown(String)
    /// The row records no bundle path at all. Not every installed app has one
    /// (bundle generation can be off, or /Applications unwritable), and such a
    /// row is simply not subject to this reconciliation.
    case notTracked
}

/// The single implementation of "is this app's bundle still there".
///
/// Everything that needs the answer -- the registry store's load path, the
/// pipeline's reconcile report, the launcher's health badges -- calls THIS.
/// Before v1.0.998 there were two independent implementations (a
/// `fileExists` in `InstallPipeline.reconcile` and another in
/// `InstalledLibrary.reconcile`) and neither changed the row's state, so the
/// registry kept answering "installed" for an app the user had trashed.
public enum BundleLiveness {

    /// Conservative by construction: only ENOENT/ENOTDIR on the bundle AND a
    /// parent that we can positively confirm is a populated directory yields
    /// `.absent`. Every other outcome -- EACCES, EIO, a stale NFS handle, an
    /// unmounted volume, a parent we cannot stat, a parent that is empty --
    /// yields `.unknown`, which changes nothing.
    public static func presence(ofBundleAt path: String?) -> BundlePresence {
        guard let p = path, !p.isEmpty else { return .notTracked }

        // lstat, not stat: a bundle replaced by a dangling symlink is still
        // "something is there", and following the link would report the
        // TARGET's absence as the bundle's.
        var st = Darwin.stat()
        if lstat(p, &st) == 0 { return .present }
        let e = errno
        guard e == ENOENT || e == ENOTDIR else {
            return .unknown("\(p): \(String(cString: strerror(e))) (errno \(e))")
        }

        // The bundle is not there. Is its CONTAINER there? An unmounted
        // external volume, a network mount that dropped, or a bad
        // androidRoot all produce ENOENT on the leaf while meaning
        // "unreachable", not "deleted".
        let parent = (p as NSString).deletingLastPathComponent
        guard !parent.isEmpty, parent != p else {
            return .unknown("\(p): no parent directory to corroborate with")
        }
        var pst = Darwin.stat()
        guard stat(parent, &pst) == 0 else {
            let pe = errno
            return .unknown("\(p): parent \(parent) is not statable: "
                          + "\(String(cString: strerror(pe))) (errno \(pe))")
        }
        guard (pst.st_mode & S_IFMT) == S_IFDIR else {
            return .unknown("\(p): parent \(parent) is not a directory")
        }
        // A directory's st_nlink is 2 + its subdirectory count on HFS+/APFS,
        // so anything > 2 is positively populated for one stat and no
        // directory read. /Applications on any real Mac is far above that.
        // Only when it looks empty do we pay for the read -- and an empty
        // container is the signature of an unmounted mountpoint, which must
        // not be read as "the user deleted every app".
        if pst.st_nlink <= 2 {
            let kids = (try? FileManager.default.contentsOfDirectory(atPath: parent)) ?? []
            if kids.isEmpty {
                return .unknown("\(p): parent \(parent) is empty -- "
                              + "unreachable rather than deleted")
            }
        }
        return .absent
    }
}

/// One state change a bundle-presence pass made to a row. Returned so the
/// caller can log it, show it, and (in the runtime's case) announce it -- the
/// pass itself never talks to a UI.
public struct BundleTransition: Equatable {
    public let dirName: String
    public let package: String
    public let label: String
    public let from: String
    public let to: String
    public let detail: String
}

/// Registry-level settings. `androidRoot` and `defaultEnv` are named because
/// the stub and the launcher both need them; anything else round-trips
/// through `extras` untouched.
///
/// `androidRoot` being here is a bug fix, not just plumbing: today the
/// launcher holds it in a plain @Published var with no persistence anywhere
/// (MacApkLauncherApp.swift ~258), so a user who picks a custom root loses it
/// on every restart and their apps' data appears to vanish.
public struct RegistrySettings: Equatable {
    public var androidRoot: String?
    public var defaultEnv: [String: String]
    public var extras: [String: JSONValue]

    public init(androidRoot: String? = nil,
                defaultEnv: [String: String] = [:],
                extras: [String: JSONValue] = [:]) {
        self.androidRoot = androidRoot
        self.defaultEnv = defaultEnv
        self.extras = extras
    }
}

extension RegistrySettings: Codable {
    public init(from decoder: Decoder) throws {
        let raw = (try? decoder.singleValueContainer().decode([String: JSONValue].self)) ?? [:]
        androidRoot = raw["androidRoot"]?.stringValue
        if case .object(let o)? = raw["defaultEnv"] {
            var env: [String: String] = [:]
            for (k, v) in o { if let s = v.stringValue { env[k] = s } }
            defaultEnv = env
        } else {
            defaultEnv = [:]
        }
        extras = raw.filter { $0.key != "androidRoot" && $0.key != "defaultEnv" }
    }

    public func encode(to encoder: Encoder) throws {
        var raw = extras
        if let r = androidRoot { raw["androidRoot"] = .string(r) }
        if !defaultEnv.isEmpty {
            raw["defaultEnv"] = .object(defaultEnv.mapValues { JSONValue.string($0) })
        }
        var c = encoder.singleValueContainer()
        try c.encode(raw)
    }
}

/// The whole file.
public struct Registry: Codable {
    /// The schema this project understands. Bump ONLY with a migration.
    public static let currentSchema = 2

    public var schema: Int
    /// Mac.apk's VERSION at the moment of the last write. Diagnostic only --
    /// the compat decision is made on `schema` -- but it turns "which build
    /// wrote this" from an archaeology problem into a field.
    public var writerVersion: String?
    public var settings: RegistrySettings
    public var apps: [AppRecord]

    /// True when the file on disk carries a schema NEWER than this build
    /// understands. We read it as best we can (unknown keys are preserved),
    /// but every mutator refuses: a writer must never downgrade a file it
    /// only partly understands. Not persisted.
    public var readOnly: Bool = false

    enum CodingKeys: String, CodingKey { case schema, writerVersion, settings, apps }

    public init(schema: Int = Registry.currentSchema,
                writerVersion: String? = nil,
                settings: RegistrySettings = RegistrySettings(),
                apps: [AppRecord] = []) {
        self.schema = schema; self.writerVersion = writerVersion
        self.settings = settings; self.apps = apps
    }

    public static var empty: Registry { Registry() }

    /// Every record for a package, newest versionCode first. More than one is
    /// normal (see the AppRecord identity note).
    public func records(forPackage pkg: String) -> [AppRecord] {
        apps.filter { $0.package == pkg }
            .sorted { ($0.versionCode, $0.version) > ($1.versionCode, $1.version) }
    }

    /// The record an update would supersede: highest versionCode, falling back
    /// to most-recently-touched when versionCode is unknown (0) for all of them,
    /// which is the case for every row migrated from v1.
    public func currentRecord(forPackage pkg: String) -> AppRecord? {
        // v1.0.1641: an INSTALLED row wins over a higher-versionCode row in any
        // other state. A failed or interrupted update's leftover row (broken /
        // installing) used to shadow the live install, so the next attempt
        // classified against a row that owns no bundle and installed beside the
        // app with none of its data.
        let all = records(forPackage: pkg)
        let live = all.filter { $0.state == AppState.installed || $0.state == "ok" }
        let rs = live.isEmpty ? all : live
        if let best = rs.first, best.versionCode > 0 { return best }
        return rs.max { lhs, rhs in
            (lhs.updatedAt ?? lhs.installedAt ?? .distantPast)
                < (rhs.updatedAt ?? rhs.installedAt ?? .distantPast)
        } ?? rs.first
    }

    /// Bring `state` into agreement with what is actually on disk, in BOTH
    /// directions, and report what moved.
    ///
    /// This is a pure in-memory transform on purpose: it is applied by
    /// `RegistryStore.loadLocked`, so EVERY reader in this process -- the
    /// launcher's grid, the helper, the per-app stub, `records(forPackage:)`,
    /// `currentRecord(forPackage:)` -- sees the same answer at the same
    /// instant without anyone having to remember to call a reconciler first.
    /// Persisting it is a separate, deliberate act (`reconcileBundles()`, and
    /// any `mutate`, which re-reads through this path before writing).
    ///
    /// WHAT IT DELIBERATELY DOES NOT DO:
    ///  - it never removes a row, so `apkPath` (the retained APK), `env`, and
    ///    the install provenance survive;
    ///  - it never clears `bundlePath`, because that path is the only evidence
    ///    that lets the REVERSE transition be detected when the user puts the
    ///    bundle back. (Clearing it is what the pre-v1.0.998 reconcile did,
    ///    which left a row that looked exactly like a legitimately
    ///    bundle-less install and could never recover.)
    ///  - it never touches `updatedAt`: that field orders `currentRecord` and
    ///    gates `recoverInterruptedInstalls`, and a reconcile is not an
    ///    install event.
    ///  - it never touches a row that is `installing` (the bundle legitimately
    ///    does not exist yet during a fresh install) or `broken` (already not
    ///    installed; the user's repair path owns it).
    @discardableResult
    public mutating func reconcileBundlePresence() -> [BundleTransition] {
        var moved: [BundleTransition] = []
        for i in apps.indices {
            let r = apps[i]
            guard r.state == AppState.installed || r.state == AppState.removed else { continue }
            let presence = BundleLiveness.presence(ofBundleAt: r.bundlePath)
            let want: String
            let why: String
            switch (r.state, presence) {
            case (AppState.installed, .absent):
                want = AppState.removed
                why = "\(r.bundlePath ?? "?") is gone"
            case (AppState.removed, .present):
                want = AppState.installed
                why = "\(r.bundlePath ?? "?") is back"
            case (AppState.removed, .notTracked):
                // Cannot be re-checked and cannot come back on its own. Left
                // as-is rather than resurrected: a row that reached `removed`
                // did so because a bundle it HAD went away.
                continue
            default:
                continue
            }
            moved.append(BundleTransition(dirName: r.dirName, package: r.package,
                                          label: r.label, from: r.state, to: want,
                                          detail: why))
            apps[i].state = want
        }
        return moved
    }
}

public enum RegistryError: LocalizedError {
    /// The file exists and is not parseable. We never overwrite in this case.
    case corrupt(path: String, detail: String)
    /// The file's schema is newer than this build. Read-only degrade.
    case schemaTooNew(found: Int, known: Int, path: String)
    /// Migration would have changed the number of entries. Refuse and keep v1.
    case migrationWouldLose(before: Int, after: Int)
    case lockTimeout(path: String)
    case io(String)

    public var errorDescription: String? {
        switch self {
        case .corrupt(let p, let d):
            return "The installed-apps registry at \(p) could not be read (\(d)). "
                 + "It has NOT been modified. Move it aside to start fresh, or repair the JSON."
        case .schemaTooNew(let f, let k, let p):
            return "The registry at \(p) was written by a newer Mac.apk (schema \(f); this build "
                 + "understands \(k)). It is being read but will not be written, so the newer "
                 + "build's data stays intact."
        case .migrationWouldLose(let b, let a):
            return "Registry migration was refused: \(b) entries in, \(a) out. "
                 + "The original file is untouched."
        case .lockTimeout(let p):
            return "Timed out waiting for the registry lock at \(p)."
        case .io(let m):
            return m
        }
    }
}

// MARK: - Small POSIX helpers

/// ISO-8601 with and without fractional seconds. Two formatters because
/// ISO8601DateFormatter's options are fixed at parse time and the strict
/// parser rejects the fractional form outright.
enum ISO8601 {
    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f
    }()
    private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    static func format(_ d: Date?) -> String? { d.map { plain.string(from: $0) } }
    static func parse(_ s: String??) -> Date? {
        guard let s = s ?? nil, !s.isEmpty else { return nil }
        return plain.date(from: s) ?? fractional.date(from: s)
    }
}

/// An advisory `flock` on a sidecar file.
///
/// Sidecar, not the data file: the write path replaces the data file with
/// `rename(2)`, and a lock held on the old inode says nothing about the new
/// one. Three processes contend here -- the launcher, the per-app stub
/// writing lastLaunchedAt, and later the install helper -- so the lock is the
/// only thing making read-modify-write safe.
final class FileLock {
    private var fd: Int32 = -1

    init?(path: String) {
        fd = open(path, O_CREAT | O_RDWR | O_CLOEXEC, 0o644)
        if fd < 0 { return nil }
    }

    /// Acquire exclusively. `timeoutMs == nil` blocks indefinitely;
    /// otherwise polls and gives up, which is what lets the stub skip its
    /// non-essential write rather than delay an app launch.
    func lock(timeoutMs: Int?) -> Bool {
        guard let ms = timeoutMs else { return flock(fd, LOCK_EX) == 0 }
        let deadline = Date().addingTimeInterval(Double(ms) / 1000.0)
        while true {
            if flock(fd, LOCK_EX | LOCK_NB) == 0 { return true }
            if errno != EWOULDBLOCK { return false }
            if Date() >= deadline { return false }
            usleep(5_000)
        }
    }

    func unlock() { if fd >= 0 { _ = flock(fd, LOCK_UN) } }
    deinit { if fd >= 0 { close(fd) } }
}

/// Write bytes, then `rename` them into place.
///
/// Not `Data.write(options: .atomic)`: that also does a temp+rename, but it
/// leaves the temp in a system location for some paths and, more importantly,
/// does not fsync before the rename -- so a power loss can leave a
/// zero-length registry that parses as neither v1 nor v2. The registry is the
/// one file every process depends on; the extra fsync is worth it.
func atomicWrite(_ data: Data, to url: URL) throws {
    let dir = url.deletingLastPathComponent()
    let tmp = dir.appendingPathComponent(".\(url.lastPathComponent).tmp.\(getpid())")
    try? FileManager.default.removeItem(at: tmp)
    try data.write(to: tmp)
    if let fh = try? FileHandle(forWritingTo: tmp) {
        fsync(fh.fileDescriptor)
        try? fh.close()
    }
    if rename(tmp.path, url.path) != 0 {
        let e = String(cString: strerror(errno))
        try? FileManager.default.removeItem(at: tmp)
        throw RegistryError.io("could not replace \(url.path): \(e)")
    }
}

// MARK: - RegistryStore

/// The ONLY writer of the registry file.
///
/// Every mutation is lock -> re-read from disk -> mutate -> atomic write.
/// Re-reading inside the lock (rather than trusting an in-memory copy) is
/// what makes concurrent writers safe: two processes that each cached the
/// file at startup would otherwise each write back their own stale view and
/// the second would erase the first's install.
public final class RegistryStore: @unchecked Sendable {
    public static let shared = RegistryStore()

    private let url: URL
    private let lockURL: URL
    /// Serialises callers inside ONE process. flock is per-process-per-fd on
    /// Darwin, so two threads here could otherwise both believe they hold it.
    private let localLock = NSLock()

    /// Stamped into `writerVersion`. The launcher can set this to the real
    /// VERSION; nil is fine and just leaves the field out.
    public var writerVersion: String?

    /// Diagnostics sink. Defaults to NSLog-free silence so a headless helper
    /// does not spray the system log.
    public var log: ((String) -> Void)?

    public init(url: URL = Paths.registryURL,
                lockURL: URL = Paths.registryLockURL) {
        self.url = url
        self.lockURL = lockURL
    }

    /// True when no process is inside `InstallPipeline.run`'s serialised
    /// section. See the call site in `loadReconciledLocked` for why the probe
    /// must never block.
    static func installIdle() -> Bool {
        guard let l = FileLock(path: Paths.installLockURL.path) else { return false }
        guard l.lock(timeoutMs: 0) else { return false }
        l.unlock()
        return true
    }

    /// True when a registry file exists at all. The launcher's first-run
    /// seeding (import every data/data dir that has a meta.json) must key off
    /// THIS and not off `load().apps.isEmpty`, or an intentionally emptied
    /// library gets re-seeded on the next launch.
    public var registryFileExists: Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    // MARK: read

    /// Read the registry, migrating v1 in place the first time.
    ///
    /// Failure modes, all deliberate:
    ///  - no file        -> an empty v2 registry, nothing written.
    ///  - v1 array       -> migrated, backed up, written back ONCE.
    ///  - v2 object      -> as-is.
    ///  - newer schema   -> read best-effort, `readOnly = true`, mutators throw.
    ///  - anything else  -> THROWS. The file is not touched.
    ///
    /// That last line is the point of this method. The behaviour it replaces
    /// (MacApkLauncherApp.swift:2195-2208) swallowed every decode error with
    /// `try?` and then re-seeded and overwrote the file -- so one malformed
    /// byte in a 63-entry library silently became a different library.
    public func load() throws -> Registry {
        localLock.lock(); defer { localLock.unlock() }
        return try loadLocked(migrateIfNeeded: true)
    }

    /// Read without ever writing. For observers that must not have side
    /// effects (a stub deciding whether to bother, a status view).
    public func loadReadOnly() throws -> Registry {
        localLock.lock(); defer { localLock.unlock() }
        return try loadLocked(migrateIfNeeded: false)
    }

    /// Read + reconcile, discarding the transition list.
    private func loadLocked(migrateIfNeeded: Bool) throws -> Registry {
        try loadReconciledLocked(migrateIfNeeded: migrateIfNeeded).0
    }

    /// THE ONE PLACE bundle-presence reconciliation is applied.
    ///
    /// It sits on the read path, not on each reader, and that is the whole
    /// point: `load()`, `loadReadOnly()`, `setting()`, `mutate()` and
    /// `touchLastLaunched()` all funnel through here, so the launcher, the
    /// install helper and a per-app stub cannot form different opinions about
    /// whether an app is installed. (They had exactly that bug: two separate
    /// reconcilers, neither of which changed `state`, so the file kept saying
    /// `installed` for an app the user had dragged to the Trash and
    /// `MacApkPackageManager` kept telling stores it was there.)
    ///
    /// Reconciling on READ rather than only on a periodic sweep is what makes
    /// the answer correct with no "when did we last check" window: the check
    /// IS the read. Persisting is separate and deliberate -- see
    /// `reconcileBundles()`.
    private func loadReconciledLocked(migrateIfNeeded: Bool) throws -> (Registry, [BundleTransition]) {
        var reg = try decodeLocked(migrateIfNeeded: migrateIfNeeded)
        // ⛔ NOT WHILE AN INSTALL IS IN FLIGHT. `BundleGenerator.generate`
        // builds into staging and MOVES the result into place, and an update
        // replaces an existing bundle -- so there is a window in which a
        // perfectly healthy app's recorded path does not exist. Reconciling
        // inside that window would mark a row `removed` on a rename. The
        // install path takes `Paths.installLockURL` for its whole duration, so
        // "somebody holds it" is the exact signal.
        //
        // The probe is NON-BLOCKING, which is what makes it deadlock-proof:
        // the install path takes install.lock and THEN the registry lock,
        // while we are already holding the registry lock here. We never wait,
        // so the AB-BA cycle cannot close. Failing to open the lock file at
        // all is also treated as "an install may be running" -- the direction
        // that changes nothing.
        guard Self.installIdle() else { return (reg, []) }
        let moved = reg.reconcileBundlePresence()
        if !moved.isEmpty {
            for m in moved {
                log?("[registry] \(m.package) \(m.from) -> \(m.to): \(m.detail)")
            }
        }
        return (reg, moved)
    }

    private func decodeLocked(migrateIfNeeded: Bool) throws -> Registry {
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path) else { return .empty }
        let data: Data
        do { data = try Data(contentsOf: url) }
        catch { throw RegistryError.io("could not read \(url.path): \(error.localizedDescription)") }

        if data.isEmpty {
            // A zero-length file is a crashed write, not a library. Refuse
            // rather than treat it as "no apps" and then overwrite it.
            throw RegistryError.corrupt(path: url.path, detail: "file is empty")
        }

        // Distinguish the shapes by parsing once, generically. Doing this
        // before Codable means a v1 file never has to survive a v2 decode.
        let any: Any
        do { any = try JSONSerialization.jsonObject(with: data, options: []) }
        catch { throw RegistryError.corrupt(path: url.path, detail: error.localizedDescription) }

        if any is [Any] {
            let migrated = try migrateV1(data: data)
            if migrateIfNeeded {
                try withLock(timeoutMs: nil) { try self.writeLocked(migrated) }
                log?("[registry] migrated v1 -> v2, \(migrated.apps.count) entries")
            }
            return migrated
        }

        guard let obj = any as? [String: Any] else {
            throw RegistryError.corrupt(path: url.path, detail: "top level is neither an array nor an object")
        }

        let schema = (obj["schema"] as? Int) ?? 0
        if schema < Registry.currentSchema, obj["apps"] is [Any] {
            // A future-proofing branch, not dead code: schema 1 was never
            // written as an object, but if a 1.5-shaped file ever appears the
            // right answer is to run it through the same tolerant decode
            // rather than to reject it.
            log?("[registry] object with schema \(schema) < \(Registry.currentSchema); reading tolerantly")
        }

        var reg: Registry
        do { reg = try JSONDecoder().decode(Registry.self, from: data) }
        catch { throw RegistryError.corrupt(path: url.path, detail: "\(error)") }

        if reg.schema > Registry.currentSchema {
            reg.readOnly = true
            log?("[registry] schema \(reg.schema) is newer than \(Registry.currentSchema); READ-ONLY")
        }
        // Every reader sees ONE spelling of installed, and the next mutate
        // (a stub's touchLastLaunched on every launch is one) persists it, which
        // heals a registry the pre-v1.0.2239 control panel wrote.
        for i in reg.apps.indices where reg.apps[i].state == AppState.legacyInstalled {
            reg.apps[i].state = AppState.installed
        }
        return reg
    }

    /// v1 was a bare array of `{apkPath, dirName, label, package, version}`.
    ///
    /// The single hard rule: entry count in == entry count out. Anything else
    /// throws and leaves the v1 file exactly where it was.
    private func migrateV1(data: Data) throws -> Registry {
        guard let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            throw RegistryError.corrupt(path: url.path, detail: "v1 array contains non-object entries")
        }
        try writeBackups(of: data)

        var apps: [AppRecord] = []
        apps.reserveCapacity(arr.count)
        for e in arr {
            let dirName = (e["dirName"] as? String) ?? ""
            let pkg = (e["package"] as? String)
                ?? (dirName.isEmpty ? "" : Self.packageFromDirName(dirName))
            let version = (e["version"] as? String) ?? ""
            let apk = (e["apkPath"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            apps.append(AppRecord(
                package: pkg,
                label: (e["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? pkg,
                // Keep a row even if dirName was somehow absent: losing it is
                // worse than carrying a row the UI will show as missing.
                dirName: dirName.isEmpty ? pkg : dirName,
                version: version,
                versionName: version,
                // 0, not a guess. The v1 `version` string is the sanitized
                // versionNAME, and for several apps here it merely LOOKS like
                // a code ("4020200", "860"). Parsing it as one would produce a
                // confidently wrong update comparator.
                versionCode: 0,
                apkPath: apk,
                originalApkPath: apk,
                bundlePath: nil,
                // Genuinely unknown. A synthesised timestamp would make all 63
                // rows look installed at the same instant, which is worse than
                // an empty field the UI can render as "--".
                installedAt: nil, updatedAt: nil, lastLaunchedAt: nil,
                source: "legacy", env: [:], state: AppState.installed))
        }
        guard apps.count == arr.count else {
            throw RegistryError.migrationWouldLose(before: arr.count, after: apps.count)
        }
        return Registry(schema: Registry.currentSchema,
                        writerVersion: writerVersion,
                        settings: RegistrySettings(),
                        apps: apps)
    }

    /// Two backups, on purpose.
    ///  - `installed_apps.json.v1.bak` is written ONCE, ever, and never
    ///    overwritten: it is the pristine pre-migration library, and the
    ///    recovery instruction stays a single `cp` forever.
    ///  - a timestamped copy for the paranoid case of repeated migrations.
    private func writeBackups(of data: Data) throws {
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let pristine = dir.appendingPathComponent("\(url.lastPathComponent).v1.bak")
        if !FileManager.default.fileExists(atPath: pristine.path) {
            try atomicWrite(data, to: pristine)
        }
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.timeZone = TimeZone.current
        let stamped = dir.appendingPathComponent(
            "\(url.lastPathComponent).bak-\(f.string(from: Date()))")
        try atomicWrite(data, to: stamped)
        log?("[registry] backup written: \(pristine.lastPathComponent), \(stamped.lastPathComponent)")
    }

    /// "<package>_<version>" -> package. Same rule the launcher already uses
    /// (split at the first "_" that precedes a digit), duplicated here rather
    /// than shared so migration never depends on launcher internals.
    static func packageFromDirName(_ dirName: String) -> String {
        let chars = Array(dirName)
        var i = 0
        while i < chars.count - 1 {
            if chars[i] == "_" && chars[i + 1].isNumber { return String(chars[0..<i]) }
            i += 1
        }
        return dirName
    }

    // MARK: write

    /// Insert or replace by `dirName`.
    public func upsert(_ rec: AppRecord) throws {
        try mutate { reg in
            if let i = reg.apps.firstIndex(where: { $0.dirName == rec.dirName }) {
                reg.apps[i] = rec
            } else {
                reg.apps.append(rec)
            }
        }
    }

    /// Remove EVERY record for a package.
    ///
    /// The contract offers only a package key, and "uninstall F-Droid" plainly
    /// means all of it. Use `remove(dirName:)` when you mean one version --
    /// which matters here, since three Hill Climb Racing versions share a
    /// package on this machine.
    public func remove(package: String) throws {
        try mutate { reg in reg.apps.removeAll { $0.package == package } }
    }

    /// Remove exactly one installed version.
    public func remove(dirName: String) throws {
        try mutate { reg in reg.apps.removeAll { $0.dirName == dirName } }
    }

    /// Read a settings value. `androidRoot` and `defaultEnv.<NAME>` are
    /// routed to their typed homes; everything else reads from `extras`.
    public func setting(_ key: String) -> String? {
        guard let reg = try? loadReadOnly() else { return nil }
        return Self.setting(key, in: reg.settings)
    }

    static func setting(_ key: String, in s: RegistrySettings) -> String? {
        if key == "androidRoot" { return s.androidRoot }
        if key.hasPrefix("defaultEnv.") { return s.defaultEnv[String(key.dropFirst(11))] }
        return s.extras[key]?.stringValue
    }

    /// Write a settings value; nil removes it.
    public func setSetting(_ key: String, _ value: String?) throws {
        try mutate { reg in
            if key == "androidRoot" {
                reg.settings.androidRoot = value
            } else if key.hasPrefix("defaultEnv.") {
                let name = String(key.dropFirst(11))
                if let v = value { reg.settings.defaultEnv[name] = v }
                else { reg.settings.defaultEnv.removeValue(forKey: name) }
            } else {
                if let v = value { reg.settings.extras[key] = .string(v) }
                else { reg.settings.extras.removeValue(forKey: key) }
            }
        }
    }

    /// Record a launch. Called by the per-app stub on every app start, so it
    /// must never delay the launch: if another process holds the lock past
    /// `timeoutMs` we simply do not record it. A missing lastLaunchedAt is a
    /// cosmetic loss; a second of latency on every app start is not.
    @discardableResult
    public func touchLastLaunched(dirName: String, at date: Date = Date(),
                                  timeoutMs: Int = 200) -> Bool {
        do {
            try mutate(timeoutMs: timeoutMs) { reg in
                if let i = reg.apps.firstIndex(where: { $0.dirName == dirName }) {
                    reg.apps[i].lastLaunchedAt = date
                }
            }
            return true
        } catch {
            log?("[registry] skipped lastLaunchedAt for \(dirName): \(error.localizedDescription)")
            return false
        }
    }

    /// Any entry left `installing` for longer than `olderThan` is the residue
    /// of a crash (or a kill -9 mid-extract) and becomes `broken`, which the
    /// UI can offer to repair or remove. Returns the affected dirNames.
    @discardableResult
    public func recoverInterruptedInstalls(olderThan: TimeInterval = 3600) throws -> [String] {
        var hit: [String] = []
        try mutate { reg in
            let cutoff = Date().addingTimeInterval(-olderThan)
            for i in reg.apps.indices where reg.apps[i].state == AppState.installing {
                let stamp = reg.apps[i].updatedAt ?? reg.apps[i].installedAt ?? .distantPast
                if stamp < cutoff {
                    reg.apps[i].state = AppState.broken
                    hit.append(reg.apps[i].dirName)
                }
            }
        }
        if !hit.isEmpty { log?("[registry] marked broken after interrupted install: \(hit)") }
        return hit
    }

    /// PERSIST the bundle-presence reconciliation that `load()` already
    /// applies in memory, and report what moved.
    ///
    /// Writing matters because the OTHER reader is in another process and
    /// another language: `MacApkPackageManager.InstalledRegistry` (Java, in
    /// the guest runtime) parses this file directly and reports every row
    /// whose `state` is `installed`. It cannot run this code, so it consumes
    /// the RESULT rather than re-deriving it -- one predicate, one writer, two
    /// agreeing readers. A store asking `getPackageInfo` therefore stops being
    /// told an app is installed as soon as this has run once.
    ///
    /// Cheap by design: it writes ONLY when a row actually moved, so the
    /// per-app stub can call it on every launch without adding a write to the
    /// launch path. `timeoutMs` bounds the lock wait for exactly that caller;
    /// giving up is fine, because the in-memory answer was already correct and
    /// the next writer persists it.
    @discardableResult
    public func reconcileBundles(timeoutMs: Int? = nil) throws -> [BundleTransition] {
        localLock.lock(); defer { localLock.unlock() }
        var moved: [BundleTransition] = []
        try withLock(timeoutMs: timeoutMs) {
            let (reg, m) = try self.loadReconciledLocked(migrateIfNeeded: false)
            moved = m
            guard !m.isEmpty else { return }
            // A file written by a newer build is read but never written back
            // (see loadLocked). The in-memory reconciliation still applies for
            // this process; we simply do not persist it.
            guard !reg.readOnly else {
                self.log?("[registry] not persisting \(m.count) bundle transition(s): "
                        + "schema \(reg.schema) is newer than \(Registry.currentSchema)")
                return
            }
            try self.writeLocked(reg)
            self.log?("[registry] persisted \(m.count) bundle transition(s)")
        }
        return moved
    }

    /// Apply an arbitrary edit under the lock, re-reading from disk first.
    public func mutate(timeoutMs: Int? = nil,
                       _ body: (inout Registry) throws -> Void) throws {
        localLock.lock(); defer { localLock.unlock() }
        try withLock(timeoutMs: timeoutMs) {
            var reg = try self.loadLocked(migrateIfNeeded: false)
            if reg.readOnly {
                throw RegistryError.schemaTooNew(found: reg.schema,
                                                 known: Registry.currentSchema,
                                                 path: self.url.path)
            }
            // A v1 file reaching here means load() was never called. Migrate
            // rather than write a v2 object over an unbacked-up v1 array.
            if reg.schema < Registry.currentSchema {
                let data = (try? Data(contentsOf: self.url)) ?? Data()
                if !data.isEmpty, (try? JSONSerialization.jsonObject(with: data)) is [Any] {
                    reg = try self.migrateV1(data: data)
                } else {
                    reg.schema = Registry.currentSchema
                }
            }
            let before = reg.apps.count
            try body(&reg)
            _ = before
            try self.writeLocked(reg)
        }
    }

    private func withLock(timeoutMs: Int?, _ body: () throws -> Void) throws {
        // The LOCK's own parent, not Paths.ensureDirectories(): a store
        // pointed at a test or migration path must not create directories in
        // the user's real support folder as a side effect of taking a lock.
        try? FileManager.default.createDirectory(at: lockURL.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        guard let l = FileLock(path: lockURL.path) else {
            throw RegistryError.io("could not open lock file \(lockURL.path)")
        }
        guard l.lock(timeoutMs: timeoutMs) else {
            throw RegistryError.lockTimeout(path: lockURL.path)
        }
        defer { l.unlock() }
        try body()
    }

    private func writeLocked(_ reg: Registry) throws {
        var out = reg
        out.schema = Registry.currentSchema
        out.writerVersion = writerVersion ?? reg.writerVersion
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try enc.encode(out)
        try atomicWrite(data, to: url)
    }
}

// MARK: - APK retention (section 4.2)

/// Formerly kept a SECOND copy of every installed APK under Application
/// Support/Mac.apk/apks/. That copy is gone for good.
///
/// Owner, 2026-08-12: "we should be deleting the apk after it is installed ...
/// Once the app is installed the original apk shouldnt be needed." And on
/// 2026-09-19, unconditionally: "i dont want mac.apk to save a copy of the
/// apks anymore ever." The retained copy was pure duplication of the copy the
/// bundle itself launches from (`Contents/Resources/base.<ext>`, the stub's
/// contract), and `links=1` on every one of them -- genuinely doubled disk.
///
/// What remains here is the CLEANUP half: `purgeAll` empties the old
/// directory at launcher startup, and the prune/remove entry points stay so an
/// uninstall of a row that still points into it leaves nothing behind. Repair
/// and reinstall no longer need this copy at all: they resolve the bundle's
/// own APK first (`bundledApk(for:)`), then the original the user installed
/// from.
public enum ApkRetention {

    /// The old dev toggle's key. Read by nothing any more; kept only so a
    /// settings file that still carries it stays decodable.
    public static let keepApksSettingKey = "keepApks"

    /// Always false. The toggle that used to drive this was removed with the
    /// feature; see the type comment.
    public static func keepApks() -> Bool { false }

    /// The names BundleGenerator writes the source APK under, in the order
    /// AppStubMain.retainedApkNames prefers them. ⚠ KEEP IN SYNC with that
    /// list: the stub and the launcher must agree on what a bundle's own
    /// copy is called, or repair finds nothing while the app still launches.
    public static let bundledApkNames = ["base.apk", "base.apkm", "base.xapk",
                                         "base.apks", "base.apkx"]

    /// The installed package's base.apk under `<androidRoot>/data/app/
    /// <dir-nonce>/<pkg-nonce>/`, complete (the runtime's `.installed` marker
    /// beside it), or nil. Mirrors AppStubMain.installedBaseApk -- the two
    /// must agree on what "installed" means, or the launcher would repair
    /// from a dir the stub refuses to launch.
    public static func installedBaseApk(androidRoot: URL, package: String? = nil) -> URL? {
        let fm = FileManager.default
        let appRoot = androidRoot.appendingPathComponent("data/app", isDirectory: true)
        // Names, not URLs: contentsOfDirectory(at:) hands back RESOLVED paths, and
        // data/app is a link into the bundle since the app data moved out
        // (AppDataLayout). The runtime needs the path as the guest sees it,
        // <root>/data/app/~~<nonce>/<pkg>-<nonce>/base.apk: it checks that shape
        // lexically (main.cpp install_dir) and reports it as sourceDir.
        func children(_ dir: URL) -> [URL]? {
            guard let names = try? fm.contentsOfDirectory(atPath: dir.path) else { return nil }
            return names.filter { !$0.hasPrefix(".") }
                .map { dir.appendingPathComponent($0, isDirectory: true) }
        }
        guard let nonces = children(appRoot) else { return nil }
        for nonce in nonces.sorted(by: { $0.path < $1.path }) {
            guard let pkgs = children(nonce) else { continue }
            // v1.0.1641: the PACKAGE's own install dir first. A stray
            // `<other>-<nonce>` (measured: Instagram's `base-Qm..`, left by a
            // manifest read that fell back to the file name) sorts before
            // `com.<pkg>-<nonce>`; taking the first-sorted launched the stray,
            // and after an update it cloned the OLD apk back over the new one.
            let ordered = pkgs.sorted { a, b in
                let ao = package.map { a.lastPathComponent.hasPrefix($0 + "-") } ?? false
                let bo = package.map { b.lastPathComponent.hasPrefix($0 + "-") } ?? false
                return ao != bo ? ao : a.path < b.path
            }
            for pkgDir in ordered {
                let base = pkgDir.appendingPathComponent("base.apk")
                var isDir: ObjCBool = false
                if fm.fileExists(atPath: base.path, isDirectory: &isDir), !isDir.boolValue,
                   fm.fileExists(atPath: pkgDir.appendingPathComponent(".installed").path) {
                    return base
                }
            }
        }
        return nil
    }

    /// The APK the bundle itself launches from, if the bundle is on disk: the
    /// installed base.apk under Contents/android/data/app first (the one copy
    /// kept since v1.0.1585), then a pre-v1.0.1585 bundle's Resources copy.
    /// This is the copy that survives a ~/Downloads cleanup, so it is the
    /// first candidate for repair/reinstall and for the health check.
    public static func bundledApk(for rec: AppRecord) -> String? {
        guard let b = rec.bundlePath, !b.isEmpty else { return nil }
        let bundle = URL(fileURLWithPath: b)
        if let inst = installedBaseApk(androidRoot: bundle.appendingPathComponent("Contents/android"),
                                       package: rec.package) {
            return inst.path
        }
        let res = bundle.appendingPathComponent("Contents/Resources")
        for n in bundledApkNames {
            let p = res.appendingPathComponent(n).path
            if FileManager.default.fileExists(atPath: p) { return p }
        }
        return nil
    }

    /// Every path a row's APK might be at, most durable first.
    public static func apkCandidates(for rec: AppRecord) -> [String] {
        var out: [String] = []
        if let b = bundledApk(for: rec) { out.append(b) }
        for p in [rec.apkPath, rec.originalApkPath].compactMap({ $0 })
        where !out.contains(p) { out.append(p) }
        return out
    }

    /// Delete the whole legacy `apks/` directory. Idempotent; logs what went.
    /// Called at launcher startup, so a machine upgraded across this change
    /// gets its disk back without an action.
    @discardableResult
    public static func purgeAll(log: ((String) -> Void)? = nil) -> Int {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: Paths.apksDir,
                                                      includingPropertiesForKeys: [.fileSizeKey],
                                                      options: [.skipsHiddenFiles])
        else { return 0 }
        var n = 0
        var bytes: Int64 = 0
        for f in files {
            let sz = (try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            do { try fm.removeItem(at: f); n += 1; bytes += sz }
            catch { log?("[retain] could not purge \(f.lastPathComponent): \(error)") }
        }
        try? fm.removeItem(at: Paths.apksDir)
        if n > 0 { log?("[retain] purged \(n) legacy retained APK(s), \(bytes / 1_048_576) MB") }
        return n
    }

    /// Delete retained APKs for a package that no live record points at.
    ///
    /// Deliberately NOT "delete the superseded copy" as the plan words it:
    /// the registry legitimately holds several versions of one package at
    /// once, and deleting an APK a live row still references would break that
    /// row's launch. Reachability is the only safe test. Returns what it
    /// removed.
    @discardableResult
    public static func pruneUnreferenced(package: String, registry: Registry,
                                         log: ((String) -> Void)? = nil) -> [String] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: Paths.apksDir,
                                                      includingPropertiesForKeys: nil,
                                                      options: [.skipsHiddenFiles])
        else { return [] }
        let referenced = Set(registry.apps.compactMap { $0.apkPath })
        var removed: [String] = []
        for f in files where f.lastPathComponent.hasPrefix("\(package)_")
                          || f.deletingPathExtension().lastPathComponent == package {
            if referenced.contains(f.path) { continue }
            do { try fm.removeItem(at: f); removed.append(f.lastPathComponent) }
            catch { log?("[retain] could not prune \(f.lastPathComponent): \(error)") }
        }
        if !removed.isEmpty { log?("[retain] pruned \(removed)") }
        return removed
    }

    /// Delete every retained APK for a package (uninstall).
    @discardableResult
    public static func removeAll(package: String, log: ((String) -> Void)? = nil) -> [String] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: Paths.apksDir,
                                                      includingPropertiesForKeys: nil,
                                                      options: [.skipsHiddenFiles])
        else { return [] }
        var removed: [String] = []
        for f in files where f.lastPathComponent.hasPrefix("\(package)_")
                          || f.deletingPathExtension().lastPathComponent == package {
            do { try fm.removeItem(at: f); removed.append(f.lastPathComponent) }
            catch { log?("[retain] could not delete \(f.lastPathComponent): \(error)") }
        }
        return removed
    }

    /// Delete staging leftovers older than a day. A crashed copy of a big APK
    /// can be hundreds of MB.
    public static func sweepStaging(olderThan: TimeInterval = 86_400,
                                    log: ((String) -> Void)? = nil) {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(
            at: Paths.stagingDir, includingPropertiesForKeys: [.contentModificationDateKey])
        else { return }
        let cutoff = Date().addingTimeInterval(-olderThan)
        for f in files {
            let m = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? Date()
            if m < cutoff {
                try? fm.removeItem(at: f)
                log?("[retain] swept stale staging file \(f.lastPathComponent)")
            }
        }
    }

    static func sanitizeComponent(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "/:\\\"'?*|<>\n\t")
        return s.components(separatedBy: bad).joined().trimmingCharacters(in: .whitespaces)
    }
}

// MARK: - JVM crash-dump pruning (v1.0.1650, lane "disk")

/// Bounds the `hs_err_pid<N>.log` files HotSpot writes into the logs dir
/// (main.cpp aims `-XX:ErrorFile` there). Per-app run logs rotate
/// (AppStubMain.rotateRunLog); these did not, so every JVM crash left ~250 KB
/// forever -- measured 2026-09-26: 30 files / 7 MB in five days on the dev
/// machine.
///
/// Rule: the newest `keep` are ALWAYS kept (a crash loop today stays
/// readable), and of the rest only those older than `minAge` go -- so a
/// burst is never pruned while someone may still be triaging it. Only names
/// matching `hs_err_pid<digits>.log` exactly are candidates; nothing else in
/// the directory is looked at.
///
/// `MACAPK_HSERR_PRUNE=0` turns it off (the pre-change behaviour).
public enum JvmCrashDumpPrune {
    public static func isCrashDumpName(_ n: String) -> Bool {
        guard n.hasPrefix("hs_err_pid"), n.hasSuffix(".log") else { return false }
        let mid = n.dropFirst("hs_err_pid".count).dropLast(".log".count)
        return !mid.isEmpty && mid.allSatisfy { $0 >= "0" && $0 <= "9" }
    }

    @discardableResult
    public static func prune(dir: URL, keep: Int = 10, minAge: TimeInterval = 86_400,
                             now: Date = Date()) -> (removed: Int, bytes: Int64) {
        if ProcessInfo.processInfo.environment["MACAPK_HSERR_PRUNE"] == "0" { return (0, 0) }
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey,
                                                  .isRegularFileKey]) else { return (0, 0) }
        let dumps: [(URL, Date, Int)] = items.compactMap { u in
            guard isCrashDumpName(u.lastPathComponent),
                  let v = try? u.resourceValues(forKeys: [.contentModificationDateKey,
                                                          .fileSizeKey, .isRegularFileKey]),
                  v.isRegularFile == true else { return nil }
            return (u, v.contentModificationDate ?? .distantPast, v.fileSize ?? 0)
        }.sorted { $0.1 > $1.1 }
        var removed = 0
        var bytes: Int64 = 0
        for (i, d) in dumps.enumerated() where i >= keep && now.timeIntervalSince(d.1) > minAge {
            if (try? fm.removeItem(at: d.0)) != nil {
                removed += 1
                bytes += Int64(d.2)
            }
        }
        return (removed, bytes)
    }
}

// MARK: - Data carry-forward (section 4.4, corrected)

/// Moves an app's saves forward when an update lands in a new data directory.
///
/// Under the target layout (Contract 1) this should be unnecessary BY
/// CONSTRUCTION: app data lives at `<androidRoot>/data/data/<pkg>/`, keyed on
/// the package alone, and simply survives an update -- which is also what
/// AOSP does (code path is regenerated per version, `/data/data/<pkg>` is
/// preserved). This type exists for the layout that is on disk TODAY, where
/// the runtime keys directories as `<pkg>_<version>` and a new version starts
/// an empty one.
///
/// Two corrections to the plan's algorithm, both from reading real directories
/// on this machine rather than the v1.0.611 description:
///
///  1. The plan's exclusion list (`res/`, `assets/`, `lib/`, `smali*/`,
///     `original/`, `unknown/`, `apktool.yml`) describes a layout that no
///     longer exists -- those are all nested inside `_apkt/` now, and today's
///     dirs additionally carry `base.apk`, `lib_native/`, `dexopt/`, `.toc`,
///     `.x18cache`, `.macapk_*` stamps and `.source-mtime`. A stale exclusion
///     list is the worst bug available here (it silently loses saves), so the
///     list below was derived from actual `<pkg>_<ver>` listings and, more
///     importantly, is no longer the primary defence.
///  2. The primary defence is the copy rule itself: walk the OLD tree FILE by
///     file and copy only paths that do NOT exist in the new tree. Anything
///     the fresh extraction produced is therefore never overwritten, whether
///     or not it is on the list. Per-file rather than per-directory matters:
///     Rocks 'n' Diamonds extracts `levels/`, `graphics/`, `conf/` at the top
///     level and the engine WRITES user levels into them, so a
///     directory-level "skip, it exists in new" would drop them.
public enum DataCarryForward {

    /// Regenerable artifacts, by top-level name. Verified against
    /// `~/Documents/android/data/data/org.fdroid.fdroid_15` and
    /// `org.artsoft.rocksndiamonds_4020200` on 2026-08-10.
    /// ⚠ KEEP IN SYNC WITH `kNames[]` IN runtime/src/apk_unpacker.cpp (the
    /// runtime's own inventory of what it creates). That list is the source
    /// of truth; this one is the Swift mirror, and the two drifting is a
    /// silent bug in both directions. Carrying one of OUR artifacts forward
    /// plants a stale one in a directory that should not have it -- the
    /// v1.0.823/827 stale-artifact class that already caused two false
    /// regression calls. Failing to carry one of the APP's directories loses
    /// the user's saves.
    ///
    /// Everything NOT listed here is treated as the app's and is carried.
    /// That default is deliberate: an unknown name is far more likely to be
    /// app data than a new artifact of ours, and the failure directions are
    /// not symmetric (stale artifact = a confusing bug; lost saves = gone).
    /// So when the runtime learns to write something new, add it HERE too.
    static let excludedTopLevel: Set<String> = [
        // --- mirrored from apk_unpacker.cpp kNames[] ---
        "classes.jar",              // com.macapk.dex output
        "_apkt",                    // pre-1590 apktool resource decode (reclaimed)
        "lib_native",               // translated dylibs
        "AndroidManifest.xml",      // pre-1590 hoisted text manifest (reclaimed)
        ".source-mtime",            // conversion stamp
        "_merged.apk",              // pre-v1.0.1586 bundle merge product (the runtime deletes it)
        "services_root",            // Boot's META-INF/services extract (present in all 64 dirs)
        ".x18cache",                // Stage-6 x18 patch plans
        "dexopt",                   // baseline profiles from the APK
        "classes.unverified.txt",   // dexconv sidecar
        "_split_res",               // transient split-resource staging
        "_bundle_staging",          // transient bundle-merge staging
        // --- launcher-facing / re-published, also ours ---
        "base.apk",                 // retained/hardlinked source APK
        "meta.json", "icon.png",
        "code_cache",               // AOSP clears this on update; `cache/` survives
        ".dexjar-pending",          // the background converter's sidecar (apk_unpacker.cpp)
        // (".toc" was listed here with no writer anywhere; as an exclusion it
        // only ever threatened an app file of that name. Removed v1.0.1641.)
        ".gles31cache", ".angle_blob",
    ]

    /// Anything the runtime stamps for freshness. Copying one forward would
    /// make a stale cache look fresh in the new directory.
    static let excludedPrefixes: [String] = [".macapk"]

    public struct Result {
        /// Top-level entries RENAMED into the new directory (moveInPlace).
        public var moved: Int = 0
        /// Their names, so undoMoveInPlace can put exactly those back.
        public var movedNames: [String] = []
        public var copied: Int = 0
        public var skippedExisting: Int = 0
        public var skippedArtifact: Int = 0
        public var bytes: Int64 = 0
        public var errors: [String] = []
        public var ranAtAll: Bool = false
        public var reason: String = ""
    }

    /// True when this android root uses the code/data split (code under
    /// `<androidRoot>/.macapk/code/`) -- every generated bundle's root does.
    ///
    /// ⚠ Honesty note (audit S10): data under the split layout is still
    /// dirName-VERSIONED (`data/data/<pkg>_<ver>`), not unversioned -- the
    /// "survives by construction" claim this used to make described a layout
    /// the runtime never implemented. Carry-forward simply does not handle
    /// the split layout; under per-version bundles the owner chose
    /// independent versions ("for dev, we will start fresh"), so there is
    /// deliberately nothing to carry here.
    public static func layoutIsSplit(androidRoot: URL) -> Bool {
        FileManager.default.fileExists(
            atPath: androidRoot.appendingPathComponent(".macapk/code").path)
    }

    /// Copy the old version's app-written files into the new version's dir.
    /// Never throws: a failed carry-forward must degrade to "your saves are
    /// still in the old directory", never to a failed install.
    @discardableResult
    public static func run(from oldDirName: String, to newDirName: String,
                           dataDataRoot: URL, androidRoot: URL,
                           log: ((String) -> Void)? = nil) -> Result {
        var r = Result()
        guard !oldDirName.isEmpty, oldDirName != newDirName else {
            r.reason = "same directory; nothing to carry"
            return r
        }
        if layoutIsSplit(androidRoot: androidRoot) {
            r.reason = "split code/data layout: carry-forward only handles the "
                     + "legacy shared root, and per-version bundles keep versions "
                     + "independent by design"
            return r
        }
        let fm = FileManager.default
        let old = dataDataRoot.appendingPathComponent(oldDirName, isDirectory: true)
        let new = dataDataRoot.appendingPathComponent(newDirName, isDirectory: true)
        guard fm.fileExists(atPath: old.path) else {
            r.reason = "previous data directory is gone"
            return r
        }
        guard fm.fileExists(atPath: new.path) else {
            r.reason = "new data directory does not exist yet"
            return r
        }
        r.ranAtAll = true

        // Two-level walk: a NON-recursive scan of the top level to apply the
        // exclusions, then a fresh recursive enumerator per surviving entry.
        //
        // ⚠ Do NOT collapse this back into one recursive enumerator plus
        // `skipDescendants()`. That is what this code did first, and it lost
        // save data: `skipDescendants()` documents itself as skipping the most
        // recently obtained SUBDIRECTORY, so calling it after obtaining a
        // top-level FILE (`.source-mtime`, `classes.jar`, `base.apk` -- all on
        // the exclusion list) suppresses recursion into the NEXT directory
        // instead. Measured on a synthetic tree: excluding
        // `.macapk_apk_fingerprint` silently swallowed the whole of `files/`,
        // with no error and no log line, and which directory got eaten
        // depended on filesystem ordering. Splitting the walk removes the call
        // entirely and, as a bonus, never descends into an excluded `_apkt/`
        // (2+ GB on a real app).
        let tops: [URL]
        do { tops = try fm.contentsOfDirectory(at: old, includingPropertiesForKeys: nil,
                                               options: []) }
        catch {
            r.reason = "could not enumerate the previous data directory: \(error.localizedDescription)"
            return r
        }

        for entry in tops {
            let top = entry.lastPathComponent
            if excludedTopLevel.contains(top)
                || excludedPrefixes.contains(where: { top.hasPrefix($0) }) {
                r.skippedArtifact += 1
                log?("[carry] - \(top) (regenerable artifact)")
                continue
            }
            copyTree(from: entry, relativeBase: old, into: new, result: &r, log: log)
        }
        r.reason = "copied \(r.copied) file(s), \(r.bytes) bytes; "
                 + "\(r.skippedExisting) already present, \(r.skippedArtifact) artifact(s) skipped"
        log?("[carry] \(oldDirName) -> \(newDirName): \(r.reason)")
        for e in r.errors.prefix(10) { log?("[carry] ERROR \(e)") }
        return r
    }

    /// v1.0.1641: carry a STORE UPDATE's data forward inside ONE bundle, as ONE
    /// atomic rename.
    ///
    /// AOSP keeps `/data/data/<pkg>` across an update and clears only
    /// `code_cache` (InstallPackageHelper / AppDataHelper
    /// .prepareAppDataPostCommitLIF, android14). Our data dirs are
    /// dirName-versioned (`<pkg>_<version>`), so the update's extractor has just
    /// created a fresh `<new>` holding nothing but its own artifacts (meta.json,
    /// icon.png, `.macapk_*` stamps) beside the previous version's `<old>`.
    ///
    /// v1.0.1640 renamed `<old>`'s top-level entries into `<new>` one by one.
    /// Two measured failures killed that: a directory without owner-write (the
    /// dr-x------ `lib-compressed` SoLoader leaves) cannot be renamed to a
    /// DIFFERENT parent, and a name present on both sides needed a merge whose
    /// precedence is a guess. Now the whole `<old>` is renamed to `<new>` --
    /// same parent, so one atomic rename(2) that read-only children do not
    /// block and no merge exists -- after `<new>`'s artifacts are set aside and
    /// restored over it (the NEW version's meta.json / icon.png win).
    ///
    /// REFUSED (error, nothing moved) when `<new>` holds anything that is not
    /// one of our artifacts: the app ran against the new data dir during the
    /// update (or something restored data into it), and neither side may be
    /// thrown away. After the rename: regenerable, version-specific entries are
    /// dropped (code_cache, `.x18cache`, the code-artifact names, SoLoader
    /// unpack dirs -- SoLoader re-extracts from the new APK), and `<old>`
    /// becomes a link to `<new>` so absolute paths the app saved keep resolving.
    @discardableResult
    public static func moveInPlace(from oldDirName: String, to newDirName: String,
                                   androidRoot: URL,
                                   log: ((String) -> Void)? = nil) -> Result {
        var r = Result()
        guard !oldDirName.isEmpty, !newDirName.isEmpty, oldDirName != newDirName else {
            r.reason = "same directory; nothing to carry"
            return r
        }
        let fm = FileManager.default
        let dd = androidRoot.resolvingSymlinksInPath()
            .appendingPathComponent("data/data", isDirectory: true)
        let old = dd.appendingPathComponent(oldDirName, isDirectory: true)
        let new = dd.appendingPathComponent(newDirName, isDirectory: true)
        if isSymlink(old) || !fm.fileExists(atPath: old.path) {
            // No data dir for the old version at all: nothing to lose. Not an
            // error (a never-launched install has none).
            r.reason = "previous data directory is gone"
            return r
        }
        r.ranAtAll = true
        // What the extractor put in <new>: must be OUR artifacts only.
        var saved: [String: Data] = [:]
        if fm.fileExists(atPath: new.path) {
            guard let newTops = try? fm.contentsOfDirectory(atPath: new.path) else {
                r.errors.append("could not list \(newDirName)")
                return r
            }
            let foreign = newTops.filter { !isArtifactName($0) }
            if !foreign.isEmpty {
                r.errors.append("\(newDirName) already holds app data (\(foreign.prefix(5).joined(separator: ", "))): "
                              + "the app ran during the update; refusing to choose between two data sets")
                return r
            }
            for n in ["meta.json", "icon.png"] {
                if let d = try? Data(contentsOf: new.appendingPathComponent(n)) { saved[n] = d }
            }
            do { try fm.removeItem(at: new) }
            catch {
                r.errors.append("could not clear \(newDirName) for the carry: \(error.localizedDescription)")
                return r
            }
        }
        do {
            try fm.moveItem(at: old, to: new)   // same parent: one rename(2)
        } catch {
            r.errors.append("could not rename \(oldDirName) to \(newDirName): \(error.localizedDescription)")
            // Put the extractor's artifacts back so the dir exists as it did.
            try? fm.createDirectory(at: new, withIntermediateDirectories: true)
            for (n, d) in saved { try? d.write(to: new.appendingPathComponent(n)) }
            return r
        }
        r.movedNames = [DataCarryForward.wholeDirMarker]
        r.moved = ((try? fm.contentsOfDirectory(atPath: new.path)) ?? []).count
        for (n, d) in saved { try? d.write(to: new.appendingPathComponent(n), options: .atomic) }
        // Drop what is regenerable AND version-specific. The runtime's own
        // reinstall strip (kNames) never runs on this dir now -- it was not the
        // "existing data dir" of the extract that just finished.
        for n in (try? fm.contentsOfDirectory(atPath: new.path)) ?? [] {
            let u = new.appendingPathComponent(n)
            let drop = (isArtifactName(n) && n != "meta.json" && n != "icon.png"
                        && !n.hasPrefix(".macapk"))
                || isSoLoaderUnpackDir(u)
            guard drop else { continue }
            makeOwnerWritable(u)
            do { try fm.removeItem(at: u); r.skippedArtifact += 1 }
            catch { log?("[carry] could not drop \(n): \(error.localizedDescription)") }
        }
        // The OLD name becomes a link to the new dir (relative, same parent).
        do {
            try fm.createSymbolicLink(atPath: old.path, withDestinationPath: newDirName)
            retargetLinks(in: dd, from: oldDirName, to: newDirName, log: log)
            log?("[carry] \(oldDirName) is now a link to \(newDirName)")
        } catch {
            log?("[carry] could not leave a link at \(oldDirName): \(error.localizedDescription)")
        }
        r.reason = "renamed \(oldDirName) -> \(newDirName) (\(r.moved) top-level entr(ies)); "
                 + "\(r.skippedArtifact) regenerable entr(ies) dropped"
        log?("[carry] in place: \(r.reason)")
        return r
    }

    /// Marker in Result.movedNames: moveInPlace renamed the WHOLE directory.
    public static let wholeDirMarker = "<whole-dir>"

    /// True when `name` is one of OUR regenerable artifacts at a data-dir top
    /// level (never the app's), per excludedTopLevel / excludedPrefixes.
    public static func isArtifactName(_ name: String) -> Bool {
        excludedTopLevel.contains(name) || excludedPrefixes.contains { name.hasPrefix($0) }
    }

    /// A SoLoader unpack dir (`lib-main`, `lib-compressed`, ...): SoLoader's
    /// UnpackingSoSource writes its `dso_state` / `dso_deps` / `dso_lock`
    /// bookkeeping into it. Its .so files are extracted from ONE version's APK,
    /// so they must not reach another version (our dylib exposure would serve
    /// the old code); SoLoader re-extracts them, as it re-validates on a device.
    public static func isSoLoaderUnpackDir(_ u: URL) -> Bool {
        let n = u.lastPathComponent
        guard n.hasPrefix("lib-"), !isSymlink(u) else { return false }
        let fm = FileManager.default
        return ["dso_state", "dso_deps", "dso_lock", "dso_manifest"].contains {
            fm.fileExists(atPath: u.appendingPathComponent($0).path)
        }
    }

    /// lstat: is `url` itself a symbolic link (never follows it)?
    public static func isSymlink(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isSymbolicLinkKey]))?.isSymbolicLink == true
    }

    /// Re-point every symlink directly inside `dir` whose target is `from` (a
    /// relative name, or a path ending in "/<from>") at the same place with
    /// `to` in its last component. Keeps every compatibility link ONE hop from
    /// the real directory: chaining them would hit MAXSYMLINKS (32) after as
    /// many updates.
    static func retargetLinks(in dir: URL, from: String, to: String,
                              log: ((String) -> Void)?) {
        let fm = FileManager.default
        for n in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] {
            let link = dir.appendingPathComponent(n)
            guard isSymlink(link),
                  let dest = try? fm.destinationOfSymbolicLink(atPath: link.path) else { continue }
            let last = (dest as NSString).lastPathComponent
            guard last == from, n != from else { continue }
            let head = (dest as NSString).deletingLastPathComponent
            let newDest = head.isEmpty ? to : (head as NSString).appendingPathComponent(to)
            do {
                try fm.removeItem(at: link)
                try fm.createSymbolicLink(atPath: link.path, withDestinationPath: newDest)
                log?("[carry] re-pointed link \(n) -> \(newDest)")
            } catch {
                log?("[carry] could not re-point link \(n): \(error.localizedDescription)")
            }
        }
    }

    /// Put the carry back (rollback of a later failure): if moveInPlace renamed
    /// the whole `<old>` to `<new>`, rename it back and drop the link. Returns
    /// true when the old version's data is where it reads it.
    @discardableResult
    public static func undoMoveInPlace(_ r: Result, from oldDirName: String, to newDirName: String,
                                       androidRoot: URL, log: ((String) -> Void)? = nil) -> Bool {
        let fm = FileManager.default
        let dd = androidRoot.resolvingSymlinksInPath().appendingPathComponent("data/data")
        let old = dd.appendingPathComponent(oldDirName)
        let new = dd.appendingPathComponent(newDirName)
        guard r.movedNames.contains(wholeDirMarker) else { return true }
        if isSymlink(old) { try? fm.removeItem(at: old) }
        if fm.fileExists(atPath: old.path) {
            log?("[carry] UNDO: \(oldDirName) exists again; left \(newDirName) in place")
            return false
        }
        do {
            try fm.moveItem(at: new, to: old)
            log?("[carry] undo: \(newDirName) renamed back to \(oldDirName)")
            return true
        } catch {
            log?("[carry] UNDO could not rename \(newDirName) back: \(error.localizedDescription)")
            return false
        }
    }

    /// v1.0.1641: an old data dir a pre-v1.0.1587 runtime extracted APK assets
    /// into, and that was never reclaimed (no `.macapk_assets_reclaimed`), holds
    /// the OLD version's asset bytes under app-looking names (gfx/, sounds/, ...).
    /// Carried, they would shadow the new APK for every path-based asset open
    /// (the shim maps "/assets/", "assets/" and bare names to the data dir
    /// first). Drop each file that matches an assets/ entry of the OLD package
    /// (`oldInstallDir`: base.apk and split_*.apk) by relative path, SIZE and
    /// the zip entry's time within 120 s -- the runtime's own reclaim rule
    /// (main.cpp ReclaimExtractedAssets), so a same-size file the app EDITED in
    /// place (Rocks 'n' Diamonds writes user levels into extracted levels/)
    /// keeps its newer mtime and is kept. Only directories this emptied are
    /// pruned. No-op for a reclaimed dir (every live one, measured 2026-09-24).
    public static func dropLegacyExtractedAssets(dataDir: URL, oldInstallDir: URL,
                                                 log: ((String) -> Void)? = nil) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: dataDir.path), !isSymlink(dataDir),
              !fm.fileExists(atPath: dataDir.appendingPathComponent(".macapk_assets_reclaimed").path)
        else { return }
        let apks = ((try? fm.contentsOfDirectory(atPath: oldInstallDir.path)) ?? [])
            .filter { $0.hasSuffix(".apk") }
        var dropped = 0
        var touched = Set<String>()
        for apk in apks {
            for e in zipAssetEntries(oldInstallDir.appendingPathComponent(apk)) {
                let f = dataDir.appendingPathComponent(e.rel)
                guard !isSymlink(f),
                      let a = try? fm.attributesOfItem(atPath: f.path),
                      (a[.type] as? FileAttributeType) == .typeRegular,
                      (a[.size] as? NSNumber)?.int64Value == e.size,
                      let m = a[.modificationDate] as? Date,
                      abs(m.timeIntervalSince(e.time)) <= 120 else { continue }
                if (try? fm.removeItem(at: f)) != nil {
                    dropped += 1
                    touched.insert(f.deletingLastPathComponent().path)
                }
            }
        }
        // Prune only the directories this emptied, bottom-up, never dataDir itself.
        for d in touched.sorted(by: { $0.count > $1.count }) {
            var u = URL(fileURLWithPath: d)
            while u.path.hasPrefix(dataDir.path + "/"),
                  ((try? fm.contentsOfDirectory(atPath: u.path)) ?? ["?"]).isEmpty {
                try? fm.removeItem(at: u)
                u = u.deletingLastPathComponent()
            }
        }
        if dropped > 0 {
            log?("[carry] dropped \(dropped) legacy extracted asset file(s) of the old version "
               + "from \(dataDir.lastPathComponent) (never reclaimed; path, size and time "
               + "match the old APK)")
        }
    }

    /// (path under assets/, uncompressed size, entry time) for every file entry
    /// of `apk`, from `/usr/bin/zipinfo -T` (decimal yyyymmdd.hhmmss times).
    static func zipAssetEntries(_ apk: URL) -> [(rel: String, size: Int64, time: Date)] {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/zipinfo")
        p.arguments = ["-T", "-s", apk.path, "assets/*"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return [] }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone.current      // DOS times are local time
        df.dateFormat = "yyyyMMdd.HHmmss"
        var out: [(String, Int64, Date)] = []
        for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
            // -rw-r--r--  2.0 unx   12345 b- defN 20140122.120000 assets/gfx/a.png
            guard line.hasPrefix("-") else { continue }
            let f = line.split(separator: " ", maxSplits: 7, omittingEmptySubsequences: true)
            guard f.count == 8, let size = Int64(f[3]), let t = df.date(from: String(f[6])) else { continue }
            let name = String(f[7])
            guard name.hasPrefix("assets/"), !name.hasSuffix("/") else { continue }
            out.append((String(name.dropFirst("assets/".count)), size, t))
        }
        return out
    }

    /// Names OUR runtime keeps in a CODE dir. KEEP IN SYNC with
    /// Boot.CODE_DIR_OURS (runtime/java/com/macapk/loader/Boot.java) and
    /// main.cpp IsCodeDirOwnName (debug/update_inplace_gate.sh checks all four).
    static let codeDirArtifacts: Set<String> = [
        "classes.jar", "lib_native", "services_root", ".source-mtime", "meta.json",
        "icon.png", "_apkt", "AndroidManifest.xml", "_merged.apk", ".x18cache",
        "dexopt", "classes.unverified.txt", "_split_res", "_bundle_staging",
        "base.apk", ".gles31cache", ".angle_blob", ".dexjar-pending",
    ]

    /// v1.0.1641: the REPLACED version's code dir. Our artifacts in it are
    /// regenerable, but before v1.0.1641 ApplicationInfo.dataDir named this
    /// directory, so apps stored real data here (measured: Instagram's session
    /// prefs, drafts and ~110 MB of app_* stores). Move every non-artifact
    /// entry (never a link -- Boot leaves those, pointing into the data dir)
    /// into the NEW data dir, never overwriting; SoLoader unpack dirs are
    /// version-specific and dropped instead. Then replace the old code dir with
    /// a link to the new data dir, so absolute paths saved through the old field
    /// keep resolving. A code dir that still holds app files afterwards is KEPT,
    /// never deleted.
    public static func carryCodeDir(_ oldDirName: String, into newDirName: String,
                                    androidRoot: URL, log: ((String) -> Void)? = nil) {
        let fm = FileManager.default
        let root = androidRoot.resolvingSymlinksInPath()
        let codeRoot = root.appendingPathComponent(".macapk/code")
        let oldCode = codeRoot.appendingPathComponent(oldDirName)
        let newData = root.appendingPathComponent("data/data").appendingPathComponent(newDirName)
        guard !isSymlink(oldCode), fm.fileExists(atPath: oldCode.path),
              let tops = try? fm.contentsOfDirectory(atPath: oldCode.path) else { return }
        func ours(_ n: String) -> Bool { codeDirArtifacts.contains(n) || n.hasPrefix(".macapk") }
        var moved = 0, kept = 0
        for top in tops where !ours(top) {
            let src = oldCode.appendingPathComponent(top)
            if isSymlink(src) { continue }
            makeOwnerWritable(src)   // SoLoader leaves its unpack dirs read-only
            if isSoLoaderUnpackDir(src) {
                try? fm.removeItem(at: src)   // version-specific; SoLoader re-extracts
                continue
            }
            let dst = newData.appendingPathComponent(top)
            if (try? fm.attributesOfItem(atPath: dst.path)) == nil {
                do { try fm.moveItem(at: src, to: dst); moved += 1 }
                catch { kept += 1; log?("[carry] code dir: could not move \(top): \(error.localizedDescription)") }
            } else {
                let (m, k) = mergeMove(from: src, into: dst)
                moved += m; kept += k
                pruneEmptyDirs(src)
                if ((try? fm.contentsOfDirectory(atPath: src.path)) ?? ["?"]).isEmpty {
                    try? fm.removeItem(at: src)
                }
            }
        }
        let rest = (try? fm.contentsOfDirectory(atPath: oldCode.path)) ?? ["?"]
        if kept == 0 && rest.allSatisfy({ ours($0) || isSymlink(oldCode.appendingPathComponent($0)) }) {
            makeOwnerWritable(oldCode)
            do {
                try fm.removeItem(at: oldCode)
                try fm.createSymbolicLink(atPath: oldCode.path,
                                          withDestinationPath: "../../data/data/\(newDirName)")
                retargetLinks(in: codeRoot, from: oldDirName, to: newDirName, log: log)
                log?("[carry] code dir \(oldDirName): moved \(moved) app entr(ies) to the data dir; "
                   + "now a link to data/data/\(newDirName)")
            } catch {
                log?("[carry] code dir \(oldDirName): \(error.localizedDescription)")
            }
        } else {
            log?("[carry] code dir \(oldDirName) KEPT: \(kept) app file(s) could not be moved "
               + "(a same-named file exists in the data dir); nothing deleted")
        }
    }

    static func pruneEmptyDirs(_ dir: URL) {
        let fm = FileManager.default
        guard !isSymlink(dir), let kids = try? fm.contentsOfDirectory(atPath: dir.path) else { return }
        for k in kids {
            let u = dir.appendingPathComponent(k)
            var isDir: ObjCBool = false
            if fm.fileExists(atPath: u.path, isDirectory: &isDir), isDir.boolValue, !isSymlink(u) {
                pruneEmptyDirs(u)
                if ((try? fm.contentsOfDirectory(atPath: u.path)) ?? ["?"]).isEmpty {
                    try? fm.removeItem(at: u)
                }
            }
        }
    }

    /// chmod u+w on `u` and every directory under it (never through a link).
    static func makeOwnerWritable(_ u: URL) {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard !isSymlink(u), fm.fileExists(atPath: u.path, isDirectory: &isDir), isDir.boolValue else { return }
        func addW(_ p: String) {
            if let a = try? fm.attributesOfItem(atPath: p), let m = a[.posixPermissions] as? NSNumber {
                try? fm.setAttributes([.posixPermissions: m.intValue | 0o200], ofItemAtPath: p)
            }
        }
        addW(u.path)
        if let e = fm.enumerator(atPath: u.path) {
            while let rel = e.nextObject() as? String {
                let c = u.appendingPathComponent(rel)
                var d: ObjCBool = false
                if !isSymlink(c), fm.fileExists(atPath: c.path, isDirectory: &d), d.boolValue { addW(c.path) }
            }
        }
    }

    /// Move each file under `src` to the same relative path under `dst` unless
    /// one exists there. Returns (moved, keptBecauseTaken).
    static func mergeMove(from src: URL, into dst: URL) -> (Int, Int) {
        let fm = FileManager.default
        var moved = 0, kept = 0
        guard let walker = fm.enumerator(atPath: src.path) else { return (0, 0) }
        var files: [String] = []
        while let rel = walker.nextObject() as? String {
            let u = src.appendingPathComponent(rel)
            var isDir: ObjCBool = false
            if isSymlink(u) || (fm.fileExists(atPath: u.path, isDirectory: &isDir) && !isDir.boolValue) {
                files.append(rel)
            }
        }
        for rel in files {
            let from = src.appendingPathComponent(rel), to = dst.appendingPathComponent(rel)
            if (try? fm.attributesOfItem(atPath: to.path)) != nil { kept += 1; continue }
            do {
                try fm.createDirectory(at: to.deletingLastPathComponent(),
                                       withIntermediateDirectories: true)
                try fm.moveItem(at: from, to: to)
                moved += 1
            } catch { kept += 1 }
        }
        return (moved, kept)
    }

    /// Copy one surviving top-level entry, file by file, never overwriting
    /// anything the fresh extraction already produced.
    ///
    /// Per-FILE and not per-directory on purpose: Rocks 'n' Diamonds extracts
    /// `levels/`, `graphics/` and `conf/` at the top level AND the engine
    /// writes user levels into them, so "the directory exists in the new tree,
    /// skip it" would drop the user's work while looking like it did the right
    /// thing.
    private static func copyTree(from source: URL, relativeBase: URL, into new: URL,
                                 result r: inout Result, log: ((String) -> Void)?) {
        let fm = FileManager.default
        // `source` is a TOP-LEVEL entry of `relativeBase`, so a file's path
        // relative to the base is "<top>/<path under source>". Built from the
        // enumerator's own RELATIVE paths: matching absolute paths against the
        // base as a prefix silently skipped every file whenever the two
        // spellings differed (the enumerator reports /private/var/... for a
        // /var/... base), which the v1.0.1640 gate caught as "nothing merged".
        let topRel = source.lastPathComponent

        func handle(_ item: URL, _ rel: String) {
            guard !rel.isEmpty else { return }
            let vals = try? item.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            let target = new.appendingPathComponent(rel)
            guard vals?.isRegularFile == true else { return }
            if fm.fileExists(atPath: target.path) { r.skippedExisting += 1; return }
            do {
                try fm.createDirectory(at: target.deletingLastPathComponent(),
                                       withIntermediateDirectories: true)
                try fm.copyItem(at: item, to: target)
                r.copied += 1
                r.bytes += Int64(vals?.fileSize ?? 0)
                // Log every carried path: this is the audit trail that makes a
                // future "did we lose a save?" answerable instead of a guess.
                log?("[carry] + \(rel)")
            } catch {
                r.errors.append("\(rel): \(error.localizedDescription)")
            }
        }

        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: source.path, isDirectory: &isDir) else { return }
        if !isDir.boolValue { handle(source, topRel); return }
        guard let walker = fm.enumerator(atPath: source.path) else { return }
        while let sub = walker.nextObject() as? String {
            handle(source.appendingPathComponent(sub), topRel + "/" + sub)
        }
    }
}

// MARK: - In-place update crash recovery

/// v1.0.1641: finish undoing an in-place store update that died mid-way (the
/// helper crashed or was killed between swapping the installed package and
/// finishing). The pipeline leaves `<root>/.macapk/update-backup/manifest.json`
/// beside its clone of the installed package for exactly as long as the update
/// is in flight, and removes both on success or rollback. So a manifest whose
/// owner is no longer a live install helper means an interrupted update: put
/// the installed package back from the clone and, if the data dir was already
/// renamed to the new version's name, rename it back. The per-app stub runs this
/// before resolving what to launch; a running update holds the stub's pidfile
/// lock, so the stub never gets here while one is live.
public enum InPlaceRecovery {
    @discardableResult
    public static func recoverIfInterrupted(androidRoot: URL,
                                            log: ((String) -> Void)? = nil) -> Bool {
        let fm = FileManager.default
        let bkRoot = androidRoot.appendingPathComponent(".macapk/update-backup")
        guard let d = try? Data(contentsOf: bkRoot.appendingPathComponent("manifest.json")),
              let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
              let installDir = j["installDir"] as? String,
              let backup = j["backup"] as? String,
              let oldDirName = j["oldDirName"] as? String, !oldDirName.isEmpty
        else { return false }
        if let owner = j["ownerPid"] as? Int, owner > 0, kill(pid_t(owner), 0) == 0 {
            var buf = [CChar](repeating: 0, count: 4096)
            if proc_pidpath(pid_t(owner), &buf, UInt32(buf.count)) > 0,
               String(cString: buf).hasSuffix("macapk-installhelper") {
                return false   // the update is still running
            }
        }
        if fm.fileExists(atPath: backup) {
            try? fm.removeItem(atPath: installDir)
            do { try fm.moveItem(atPath: backup, toPath: installDir) }
            catch {
                log?("[recover] could not restore \(installDir): \(error.localizedDescription)")
                return false
            }
        }
        let dd = androidRoot.appendingPathComponent("data/data")
        let old = dd.appendingPathComponent(oldDirName)
        if DataCarryForward.isSymlink(old),
           let dest = try? fm.destinationOfSymbolicLink(atPath: old.path) {
            let real = dd.appendingPathComponent((dest as NSString).lastPathComponent)
            try? fm.removeItem(at: old)
            if fm.fileExists(atPath: real.path) { try? fm.moveItem(at: real, to: old) }
        }
        try? fm.removeItem(at: bkRoot)
        log?("[recover] an interrupted in-place update was undone: \(oldDirName) and its "
           + "installed package are back as they were before it")
        return true
    }
}

// MARK: - Kept data (the "uninstall but keep my saves" store)

/// The holding area for an uninstalled app's data.
///
/// ⚠ WHY THIS TYPE EXISTS. Since the bundle cutover an app's data lives INSIDE
/// its .app (`Contents/android/data/data/<dirName>`), so removing the bundle
/// destroys the data BY CONSTRUCTION. `uninstall(deleteData: false)` therefore
/// has to lift the data out first -- and that half was written -- but nothing
/// ever put it BACK. Data parked where the user cannot reach it is not kept,
/// it is hoarded, and a dialog promising "kept" was false either way. This type
/// owns BOTH halves so they cannot drift apart again.
///
/// The store is `~/Library/Application Support/Mac.apk/kept-data/<dirName>/`,
/// which is the app's data dir moved verbatim -- including its own `meta.json`.
/// That file is the identity: no sidecar is written, so there is nothing that
/// can desync from the tree it describes.
///
/// MATCHING IS PER-VERSION, deliberately. `dirName` is `<package>_<version>`
/// and versions are independent (owner, 2026-08-10, "for dev, we will start
/// fresh"; gate row A9b asserts it). So reinstalling v2 does NOT inherit v1's
/// kept data -- it waits for v1. Restoring across versions here would silently
/// re-introduce the carry-forward that decision removed.
public enum KeptData {

    public static var root: URL {
        Paths.supportDir.appendingPathComponent("kept-data", isDirectory: true)
    }

    /// One parked data tree, described well enough for a UI to name it.
    public struct Entry {
        public var dirName: String
        public var package: String
        public var label: String
        public var versionName: String
        public var url: URL
        public var keptAt: Date
        public var sizeBytes: Int64
    }

    /// Read `<kept>/<dirName>/meta.json` -- the app's OWN metadata, written by
    /// the runtime's MirrorInstallMetadata. Falls back to the dirName, which
    /// always encodes the package, so an entry stays identifiable even if the
    /// metadata is missing.
    private static func describe(_ dir: URL) -> Entry {
        let fm = FileManager.default
        let name = dir.lastPathComponent
        var pkg = RegistryStore.packageFromDirName(name)
        var label = ""
        var versionName = ""
        if let d = try? Data(contentsOf: dir.appendingPathComponent("meta.json")),
           let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
            if let p = j["package"] as? String, !p.isEmpty { pkg = p }
            label = (j["label"] as? String) ?? ""
            versionName = (j["versionName"] as? String) ?? ""
        }
        if versionName.isEmpty {
            versionName = InstallPipeline.versionFromDirName(name, package: pkg)
        }
        let keptAt = (try? dir.resourceValues(forKeys: [.contentModificationDateKey]))?
            .contentModificationDate ?? .distantPast
        var bytes: Int64 = 0
        if let w = fm.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey],
                                 options: []) {
            for case let f as URL in w {
                bytes += Int64((try? f.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            }
        }
        return Entry(dirName: name, package: pkg,
                     label: label.isEmpty ? pkg : label,
                     versionName: versionName, url: dir,
                     keptAt: keptAt, sizeBytes: bytes)
    }

    /// Everything currently parked, newest first.
    public static func entries() -> [Entry] {
        let fm = FileManager.default
        guard let kids = try? fm.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]) else { return [] }
        return kids
            .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
            .map(describe)
            .sorted { $0.keptAt > $1.keptAt }
    }

    public static func entries(forPackage package: String) -> [Entry] {
        entries().filter { $0.package == package }
    }

    /// Move an app's data OUT of a bundle that is about to be removed.
    ///
    /// Returns the destination, or nil when there was nothing to lift. THROWS
    /// on failure rather than returning nil, because the caller must be able to
    /// tell "nothing to keep" from "could not keep it" -- the second must abort
    /// the uninstall, or `deleteData: false` destroys the very thing it
    /// promised to preserve.
    @discardableResult
    public static func lift(dirName: String, fromBundle bundle: URL) throws -> URL? {
        guard !dirName.isEmpty else { return nil }
        let fm = FileManager.default
        let src = bundle.appendingPathComponent("Contents/android/data/data")
                        .appendingPathComponent(dirName)
        guard fm.fileExists(atPath: src.path) else { return nil }
        let dst = root.appendingPathComponent(dirName, isDirectory: true)
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        // A previous keep for the SAME dirName (uninstall, reinstall, uninstall
        // again without ever relaunching). The newer tree is the one the user
        // just had, so it wins -- but only once the move is about to happen.
        if fm.fileExists(atPath: dst.path) { try fm.removeItem(at: dst) }
        do {
            try fm.moveItem(at: src, to: dst)
        } catch {
            // Application Support and /Applications are normally the same
            // volume, but a rename across volumes fails with EXDEV. Copy, then
            // remove the source only once the copy is complete.
            try fm.copyItem(at: src, to: dst)
            try? fm.removeItem(at: src)
        }
        return dst
    }

    /// Put a parked tree back, INTO the android root an install is about to
    /// extract into. Call this AFTER the bundle exists and BEFORE the extractor
    /// runs: `runtime/src/main.cpp` takes its "reinstall into existing data dir
    /// (app data preserved)" branch when the data dir is already there, and
    /// strips only known code artifacts from it. Restoring afterwards would
    /// race the extractor for the same directory.
    ///
    /// Matching is by package AND versionName (see the type comment). Returns
    /// the dirName restored, or nil when nothing matched.
    @discardableResult
    public static func restore(package: String, versionName: String,
                               intoAndroidRoot androidRoot: URL,
                               log: ((String) -> Void)? = nil) -> String? {
        guard !package.isEmpty else { return nil }
        let fm = FileManager.default
        // An empty versionName means the peek could not read one; matching an
        // arbitrary kept version to it would restore the wrong saves, so only
        // an equally version-less entry qualifies.
        guard let hit = entries(forPackage: package)
                .first(where: { $0.versionName == versionName }) else { return nil }
        let dst = androidRoot.appendingPathComponent("data/data")
                             .appendingPathComponent(hit.dirName, isDirectory: true)
        // Never overwrite a data dir that already holds an install. The kept
        // tree is the older copy in that case, and a fresh bundle that already
        // has data is the adopt-across-regeneration case the install path
        // relies on (bundleContainsInstalledData).
        if fm.fileExists(atPath: dst.appendingPathComponent("meta.json").path) {
            log?("[install] kept data for \(hit.dirName) left parked: the target already holds an install")
            return nil
        }
        do {
            try fm.createDirectory(at: dst.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            if fm.fileExists(atPath: dst.path) { try fm.removeItem(at: dst) }
            do { try fm.moveItem(at: hit.url, to: dst) }
            catch {
                try fm.copyItem(at: hit.url, to: dst)
                try? fm.removeItem(at: hit.url)
            }
            log?("[install] restored kept app data for \(hit.dirName) into \(dst.path)")
            return hit.dirName
        } catch {
            // Non-fatal: the install continues with a fresh data dir and the
            // kept tree stays parked, so the user can still get it back. Saying
            // nothing is what would make it a loss.
            log?("[install] could not restore kept app data for \(hit.dirName): "
               + "\(error.localizedDescription); it is still at \(hit.url.path)")
            return nil
        }
    }

    /// Forget parked trees. Called when the user asks for the data to be
    /// DELETED: leaving an invisible copy behind after someone ticked "also
    /// delete its data" is the same class of lie in the other direction.
    ///
    /// `dirNames`, when non-empty, scopes it to those versions -- removing v1
    /// must not discard v2's parked saves.
    @discardableResult
    public static func discard(package: String, dirNames: [String] = [],
                               log: ((String) -> Void)? = nil) -> Int {
        var n = 0
        let scope = Set(dirNames.filter { !$0.isEmpty })
        for e in entries(forPackage: package) where scope.isEmpty || scope.contains(e.dirName) {
            do { try FileManager.default.removeItem(at: e.url); n += 1 }
            catch { log?("[uninstall] could not remove kept data \(e.url.path): "
                       + "\(error.localizedDescription)") }
        }
        if n > 0 { log?("[uninstall] discarded \(n) kept data tree(s) for \(package)") }
        return n
    }
}

// MARK: - Install pipeline (section 4.5)

/// Where an install came from. `android` carries the requesting app's label so
/// a confirmation dialog can name it ("F-Droid wants to install ...").
public enum InstallSource: Equatable {
    case gui
    case finder
    case android(requestorLabel: String)

    /// Persisted into `AppRecord.source`.
    public var recordValue: String {
        switch self {
        case .gui: return "gui"
        case .finder: return "finder"
        case .android: return "android"
        }
    }
}

public struct InstallRequest {
    public let sourceFile: URL
    public let source: InstallSource
    /// false only for --no-ui test mode: skips confirm (and, when it exists,
    /// auth). Nothing in a production path may set this.
    public let interactive: Bool

    public init(sourceFile: URL, source: InstallSource, interactive: Bool) {
        self.sourceFile = sourceFile
        self.source = source
        self.interactive = interactive
    }
}

public enum InstallStatus: Equatable {
    case success
    case cancelled
    case authFailed
    case failed(stage: String, message: String)
}

public struct InstallOutcome {
    public let status: InstallStatus
    public let package: String?
    public let label: String?
    public let versionCode: Int64?
    public let wasUpdate: Bool
    public let bundlePath: String?

    public init(status: InstallStatus, package: String? = nil, label: String? = nil,
                versionCode: Int64? = nil, wasUpdate: Bool = false,
                bundlePath: String? = nil) {
        self.status = status; self.package = package; self.label = label
        self.versionCode = versionCode; self.wasUpdate = wasUpdate
        self.bundlePath = bundlePath
    }

    public var succeeded: Bool { status == .success }
}

/// How a staged APK relates to what is already installed.
public enum InstallKind: String {
    case fresh, update, reinstall, downgrade
}

/// What the runtime's `--peek-manifest` told us before we spent anything on
/// extraction. `available == false` means the runtime does not support the
/// flag yet, and classification degrades to post-extraction.
public struct PeekResult {
    public var available: Bool = false
    public var package: String?
    public var versionCode: Int64?
    public var versionName: String?
    /// android:label. The runtime's --peek-manifest emits it, and the bundle
    /// is NAMED from it, so it must survive the peek. Empty for an @ref label
    /// (resolving that needs the arsc, which the peek exists to avoid) --
    /// callers fall back to the package.
    public var label: String?
    public var detail: String = ""
}

/// Everything a confirmation dialog needs, with no opinion about how it is
/// drawn. Kept UI-free so the same pipeline serves SwiftUI, a bare helper, and
/// a test harness.
public struct InstallPrompt {
    public let kind: InstallKind
    public let source: InstallSource
    public let sourceFile: URL
    public let package: String?
    public let label: String?
    public let newVersionName: String?
    public let newVersionCode: Int64?
    public let installedVersionName: String?
    public let installedVersionCode: Int64?
    /// True when we could not peek the manifest, so the dialog can say
    /// "Install <filename>?" instead of pretending to know the version.
    public let identityKnown: Bool
}

/// The one install path. Every entry point calls `run`.
public final class InstallPipeline {

    /// Injected side effects. Every one is optional and the pipeline has a
    /// defined behaviour without it, so a caller can adopt this incrementally
    /// instead of needing all of P0/P1/P2 to land at once.
    public struct Hooks {
        /// Diagnostics.
        public var log: ((String) -> Void)?
        /// Ask the user. Return false to cancel. nil == auto-approve.
        public var confirm: ((InstallPrompt) -> Bool)?
        /// THE AUTH SEAM. Not called today (owner deferred the Android-initiated
        /// install path to last). When it is wired, exactly one line below
        /// changes from a comment to a call, and only for `.android` requests.
        public var authenticate: ((String) -> Bool)?
        /// Generate the per-app .app bundle. Deliberately a closure over the
        /// FIELDS of the sibling lane's `BundleSpec` rather than a reference to
        /// that type: this file must compile whether or not BundleGenerator.swift
        /// exists yet. Wiring it is three lines at the call site.
        /// (package, label, versionName, versionCode, iconPNG, apkToRetain,
        ///  dirName) -> bundle URL
        ///
        /// dirName is load-bearing, not decoration: under the default
        /// per-version identity the generator keys the bundle by dirName, so
        /// omitting it would collapse every version of a package onto one
        /// bundle -- which is precisely the cross-lane mismatch that moved one
        /// version's saves into another version's bundle.
        public var generateBundle: ((String, String, String, Int64, URL?, URL, String) throws -> URL)?
        /// Remove a generated bundle.
        public var removeBundle: ((URL) throws -> Void)?
        /// Stamp the bundle's REAL identity on after extraction, when the
        /// resolved label and rasterized icon finally exist. Returns the
        /// bundle URL, which CHANGES when the name did.
        ///
        /// A closure for the same reason generateBundle is one: this file is
        /// compiled into the per-app stub target WITHOUT BundleGenerator.swift,
        /// so naming that type here does not link. (I broke exactly that and
        /// the stub build caught it: "cannot find 'BundleGenerator' in scope".)
        /// (bundle, label, iconPNG, versionName, package, dirName,
        ///  bundleIdentifierToKeep) -> bundle URL. The last argument is non-nil
        ///  only for an in-place update, which keeps the replaced bundle's id.
        public var finalizeBundle: ((URL, String?, URL?, String, String, String?, String?) -> URL)?
        /// STAGE REPORTING, for a caller that has a progress bar to move.
        /// (stageName, fractionComplete in 0...1).
        ///
        /// Called only AFTER the named stage has finished, never before, so a
        /// consumer can never render progress the install has not actually
        /// made. That property is the reason this is a hook here rather than a
        /// timer or a text-scrape of `log` on the far side: an
        /// android-initiated install runs behind a Touch ID prompt and a large
        /// extract, and the store watching it has minutes of nothing to show
        /// otherwise. See PackageInstallerBackend.HelperProgress, which is the
        /// one consumer today (via InstallHelperMain's stderr stage line).
        ///
        /// The fractions are deliberately coarse and hand-chosen: they mark
        /// real boundaries in this method, and no stage has a meaningful
        /// sub-progress of its own to report.
        public var progress: ((String, Double) -> Void)?

        public init() {}
    }

    public let runtimePath: String
    public let androidRoot: URL
    public var hooks: Hooks
    public let store: RegistryStore

    /// `<androidRoot>/data/data`, the same derivation the launcher uses
    /// (arm32AppCacheDir), duplicated here so the helper does not need the
    /// launcher to compute it.
    public var dataDataRoot: URL {
        androidRoot.appendingPathComponent("data/data", isDirectory: true)
    }

    /// `<androidRoot>/.macapk/code`, the regenerable half of the Contract-1
    /// split. Code and data dirs share a basename, so `<codeRoot>/<dirName>`
    /// is the sibling of `<dataDataRoot>/<dirName>`.
    ///
    /// Deleting THIS is always safe (it is the converted jar, the apktool
    /// decode and the translated dylibs, all reproducible from the APK);
    /// deleting the data sibling is not.
    public var codeRoot: URL {
        androidRoot.appendingPathComponent(".macapk/code", isDirectory: true)
    }

    public init(runtimePath: String, androidRoot: URL,
                store: RegistryStore = .shared, hooks: Hooks = Hooks()) {
        self.runtimePath = runtimePath
        self.androidRoot = androidRoot
        self.store = store
        self.hooks = hooks
    }

    private func log(_ s: String) { hooks.log?(s) }

    /// Report a FINISHED stage. Never called ahead of the work -- see
    /// Hooks.progress. Kept as one funnel so every stage boundary reads the
    /// same way and a new one cannot forget to be reported.
    private func report(_ stage: String, _ fraction: Double) {
        hooks.progress?(stage, fraction)
    }

    // MARK: run

    /// preflight -> confirm -> [auth] -> extract -> retain -> migrate ->
    /// register -> bundle -> finish.
    public func run(_ req: InstallRequest) -> InstallOutcome {
        Paths.ensureDirectories()
        ApkRetention.sweepStaging(log: hooks.log)

        // ---- preflight -------------------------------------------------
        let peek = peekManifest(req.sourceFile)
        if !peek.available {
            log("[install] --peek-manifest unavailable (\(peek.detail)); "
              + "classification degrades to post-extraction and the confirm dialog "
              + "cannot name the version")
        }

        var registry: Registry
        do { registry = try store.load() }
        catch {
            return .failed("preflight", error.localizedDescription)
        }

        var existing = peek.package.flatMap { registry.currentRecord(forPackage: $0) }
        var kind = classify(newCode: peek.versionCode, newName: peek.versionName,
                            against: existing)
        // v1.0.1640: an update that REPLACES the installed app (see
        // updatesInPlace). Refused while that app is running: AOSP kills the
        // replaced package's processes first, and we cannot move a live app's
        // data directory out from under it.
        if let msg = FreeSpaceCheck.shortfallMessage(
                apkSizes: [FreeSpaceCheck.size(of: req.sourceFile)],
                destination: Paths.bundlesDir) {
            return .failed("no-space", msg)
        }
        var inPlace = updatesInPlace(kind: kind, source: req.source, existing: existing)
        if inPlace, let old = existing {
            let live = RuntimeInstall.runningGuests()
            if live.contains(old.dirName) || live.contains(old.package) {
                return .failed("app-running", "\(old.label) is running. Quit it, then "
                             + "retry the update (the update replaces it in place).")
            }
            log("[install] update of \(old.dirName) will replace it in place "
              + "(\(old.bundlePath ?? "?"))")
        }

        // ---- confirm ---------------------------------------------------
        if req.interactive, let ask = hooks.confirm {
            let prompt = InstallPrompt(
                kind: kind, source: req.source, sourceFile: req.sourceFile,
                package: peek.package,
                label: existing?.label,
                newVersionName: peek.versionName, newVersionCode: peek.versionCode,
                installedVersionName: existing?.versionName,
                installedVersionCode: existing?.versionCode,
                identityKnown: peek.available)
            if !ask(prompt) {
                log("[install] cancelled by the user at confirm")
                return InstallOutcome(status: .cancelled, package: peek.package)
            }
        }

        // ---- auth (SEAM -- deliberately not wired) ----------------------
        //
        // When the Android-initiated install path lands, this becomes:
        //
        //     if case .android(let who) = req.source, req.interactive {
        //         guard hooks.authenticate?("install \(who)'s download") ?? false else {
        //             return InstallOutcome(status: .authFailed, package: peek.package)
        //         }
        //     }
        //
        // It is left out rather than stubbed-true so nobody can mistake a
        // stub for a check. `.authFailed` already exists in InstallStatus and
        // the `authenticate` hook already exists above, so adding it is one
        // block here and one closure at the call site -- no restructuring.
        // Owner instruction 2026-08-10: install-intent work is deferred last.

        // Everything from here is serialised across processes.
        guard let gate = FileLock(path: Paths.installLockURL.path) else {
            return .failed("extract", "could not open \(Paths.installLockURL.path)")
        }
        guard gate.lock(timeoutMs: nil) else {
            return .failed("extract", "could not acquire the install lock")
        }
        defer { gate.unlock() }

        // v1.0.1641: RE-DECIDE under the lock. Everything above ran before it,
        // and the lock wait is unbounded: a queued second update of the same
        // package (a store retry, Aurora and F-Droid, the launcher) held a
        // snapshot of the row the first update already replaced, asked the
        // generator for a dirName that bundle no longer carries, and installed
        // BESIDE the app with an empty data dir. The running-app check is
        // repeated for the same reason -- it may be minutes old.
        if let fresh = try? store.load() {
            registry = fresh
            let now = peek.package.flatMap { registry.currentRecord(forPackage: $0) }
            if now != existing {
                log("[install] the installed record changed while waiting for the "
                  + "install lock; re-deciding")
            }
            existing = now
            kind = classify(newCode: peek.versionCode, newName: peek.versionName,
                            against: existing)
            inPlace = updatesInPlace(kind: kind, source: req.source, existing: existing)
        }
        // The stub's pidfile FLOCK is its "running" signal (AppStubMain: "the
        // flock is the aliveness signal, no stale-pid heuristics"); the pid TEXT
        // runningGuests reads can be stale for a live app (measured 2026-09-24).
        // Take the lock and HOLD it until this install returns: a Dock click
        // mid-update then gets "already running" instead of booting the app
        // into a half-updated bundle.
        var heldRunLocks: [Int32] = []
        defer { for fd in heldRunLocks { flock(fd, LOCK_UN); close(fd) } }
        if inPlace, let old = existing {
            let live = RuntimeInstall.runningGuests()
            if live.contains(old.dirName) || live.contains(old.package) {
                return .failed("app-running", "\(old.label) is running. Quit it, then "
                             + "retry the update (the update replaces it in place).")
            }
            try? FileManager.default.createDirectory(at: Paths.runDir, withIntermediateDirectories: true)
            for name in Set([old.dirName, old.package]) {
                let path = Paths.runDir.appendingPathComponent("\(name).pid").path
                let fd = open(path, O_RDWR | O_CREAT, 0o644)
                guard fd >= 0 else { continue }
                if flock(fd, LOCK_EX | LOCK_NB) != 0 {
                    close(fd)
                    return .failed("app-running", "\(old.label) is running. Quit it, then "
                                 + "retry the update (the update replaces it in place).")
                }
                // No pid a waiting stub could "activate": 0 names no process.
                _ = ftruncate(fd, 0)
                _ = "0\n".withCString { write(fd, $0, 2) }
                heldRunLocks.append(fd)
            }
        }
        let fm = FileManager.default
        // The installed bundle's CFBundleIdentifier, kept across an in-place
        // update (finalizeIdentity). Read before the generator rewrites Info.plist.
        let keptBundleId: String? = {
            guard inPlace, let b = existing?.bundlePath else { return nil }
            let pl = URL(fileURLWithPath: b).appendingPathComponent("Contents/Info.plist")
            guard let d = try? Data(contentsOf: pl),
                  let dict = (try? PropertyListSerialization.propertyList(from: d, format: nil))
                      as? [String: Any] else { return nil }
            return dict["CFBundleIdentifier"] as? String
        }()

        // ---- state journal ---------------------------------------------
        //
        // Written BEFORE extraction so a crash leaves evidence. It needs an
        // identity, which means it needs the peek: without --peek-manifest we
        // do not know the package until the extractor has already run, and
        // the journal necessarily degrades to after-the-fact. Say so rather
        // than invent a placeholder row that a later reconcile would have to
        // guess about.
        var journalledDirName: String? = nil
        if let pkg = peek.package {
            let provisional = provisionalDirName(package: pkg, versionName: peek.versionName,
                                                 existing: existing)
            journalledDirName = provisional
            try? store.upsert(AppRecord(
                package: pkg,
                label: existing?.label ?? pkg,
                dirName: provisional,
                version: peek.versionName ?? "",
                versionName: peek.versionName ?? "",
                versionCode: peek.versionCode ?? 0,
                apkPath: existing?.apkPath,
                originalApkPath: req.sourceFile.path,
                // Same-row-only inheritance, as in the register step below
                // (audit S6): a crash mid-extract must not leave a journal
                // row pointing at a DIFFERENT version's bundle, where the
                // broken row's uninstall would trash that version's data.
                bundlePath: (existing?.dirName == provisional) ? existing?.bundlePath : nil,
                installedAt: existing?.installedAt ?? Date(),
                updatedAt: Date(),
                lastLaunchedAt: existing?.lastLaunchedAt,
                source: req.source.recordValue,
                env: existing?.env ?? [:],
                state: AppState.installing))
        } else {
            log("[install] no package identity before extraction, so no `installing` "
              + "journal entry; crash recovery for THIS install is unavailable")
        }

        // ---- bundle FIRST, so extraction lands inside it -------------------
        // The bundle is the app's only home, so it has to exist before the
        // extractor runs. That is what --peek-manifest is for: package, label
        // and version are available here WITHOUT the expensive extraction.
        // Generating the bundle afterwards (the original stage order) is what
        // left the app extracted in a shared root with an empty bundle beside
        // it, and a shared copy that survived removing the bundle.
        var bundleURL: URL?
        // A standard user cannot write /Applications; make sure the per-user
        // folder exists before the writability test below (GitHub #28).
        if hooks.generateBundle != nil { Paths.prepareBundlesDir() }
        if let gen = hooks.generateBundle, Paths.bundlesDirIsWritable,
           let pkg0 = peek.package, !pkg0.isEmpty {
            let ver0 = peek.versionName ?? ""
            // An in-place update generates against the INSTALLED row's identity,
            // so the generator resolves (and regenerates, adopting its android
            // tree with the data in it) the existing bundle instead of naming a
            // second one "<Label> (<lastSeg>)"; finalizeBundle stamps the new
            // dirName once extraction has produced it.
            let dir0 = (inPlace ? existing?.dirName : nil)
                ?? (ver0.isEmpty ? pkg0 : "\(pkg0)_\(ver0)")
            do {
                bundleURL = try gen(pkg0, peek.label ?? pkg0, ver0,
                                    peek.versionCode ?? 0, nil,
                                    req.sourceFile, dir0)
                log("[install] bundle: \(bundleURL!.path)")
                report("bundle", 0.10)
            } catch {
                // ⚠ NO SHARED-ROOT FALLBACK. Owner, 2026-08-12: "make sure they
                // never install there again."
                //
                // Falling back used to keep a failed install "working", but
                // under the bundle model that is a lie: the app would have no
                // icon, no bundle to launch or Trash, and its data would land
                // in ~/Documents/android -- a directory that no longer exists.
                // An install that cannot produce an app has not installed
                // anything, so say so instead of half-doing it.
                markBrokenOrRollback(journalledDirName,
                                 hadExisting: existing?.dirName == journalledDirName)
                return .failed("bundle", "could not create the app bundle: "
                             + "\(error.localizedDescription)")
            }
        }
        let extractRoot = bundleURL.map { $0.appendingPathComponent("Contents/android") }
        report("prepared", 0.15)

        // ---- restore kept data, BEFORE the extractor runs -----------------
        // The other half of `uninstall(deleteData: false)`. Ordering is
        // load-bearing: main.cpp preserves an EXISTING data dir on reinstall
        // ("app data preserved; N stale code artifact(s) removed") and creates
        // an empty one when there is none, so the tree has to be back in place
        // before extraction. Doing it afterwards would race the extractor for
        // the same directory and, on the fresh-dir branch, restore into a tree
        // the app had already opened.
        // v1.0.1641: NOT on an in-place update. The app's CURRENT data is carried
        // below; a kept snapshot restored into the new data dir first would win
        // every file-by-file conflict (the carry never overwrites) and strand the
        // current saves in the old dir.
        if !inPlace, let root = extractRoot, let pkg0 = peek.package, !pkg0.isEmpty {
            _ = KeptData.restore(package: pkg0, versionName: peek.versionName ?? "",
                                 intoAndroidRoot: root, log: hooks.log)
        }

        // ---- in-place: make the update UNDOABLE before the extractor runs ----
        // v1.0.1641. The extractor replaces the installed package (base.apk, the
        // splits, lib/) in the package-keyed install dir EARLY -- before DEX
        // conversion can fail -- and the stub launches whatever is there, so a
        // failed update used to leave the old app running the NEW code against
        // an empty data dir, its saves intact but invisible. Keep a clone of the
        // install dir (APFS clonefile: instant, no space until something diverges)
        // and the set of dirs that existed, so any failure below restores the
        // installed version exactly.
        var inPlaceBackup: (installDir: URL, backup: URL)? = nil
        var preData = Set<String>(), preCode = Set<String>()
        /// Put the installed version back exactly: its package, its identity, and
        /// no dirs from this attempt. Data never moved before the carry, and the
        /// carry undoes itself before calling this.
        func rollbackInPlace(_ why: String) {
            guard inPlace, let old = existing, let root = extractRoot else { return }
            if let b = inPlaceBackup {
                do {
                    if fm.fileExists(atPath: b.installDir.path) { try fm.removeItem(at: b.installDir) }
                    try fm.moveItem(at: b.backup, to: b.installDir)
                    try? fm.removeItem(at: b.backup.deletingLastPathComponent())   // + manifest
                } catch {
                    log("[install] ROLLBACK could not restore the installed package from "
                      + "\(b.backup.path): \(error.localizedDescription)")
                }
            }
            let dd = root.appendingPathComponent("data/data")
            for n in (try? fm.contentsOfDirectory(atPath: dd.path)) ?? [] where !preData.contains(n) {
                let d = dd.appendingPathComponent(n)
                let rest = (try? fm.contentsOfDirectory(atPath: d.path)) ?? ["?"]
                if rest.allSatisfy(DataCarryForward.isArtifactName) {
                    try? fm.removeItem(at: d)
                } else {
                    log("[install] ROLLBACK kept \(d.path): it holds non-artifact files")
                }
            }
            let cd = root.appendingPathComponent(".macapk/code")
            for n in (try? fm.contentsOfDirectory(atPath: cd.path)) ?? [] where !preCode.contains(n) {
                try? fm.removeItem(at: cd.appendingPathComponent(n))   // regenerable
            }
            if let url = bundleURL, let fin = hooks.finalizeBundle {
                let icon = dd.appendingPathComponent(old.dirName).appendingPathComponent("icon.png")
                _ = fin(url, old.label, fm.fileExists(atPath: icon.path) ? icon : nil,
                        old.versionName, old.package, old.dirName, keptBundleId)
            }
            log("[install] in-place update ROLLED BACK (\(why)); \(old.label) "
              + "v\(old.versionName) is unchanged")
        }

        if inPlace, let root = extractRoot {
            preData = Set((try? fm.contentsOfDirectory(
                atPath: root.appendingPathComponent("data/data").path)) ?? [])
            preCode = Set((try? fm.contentsOfDirectory(
                atPath: root.appendingPathComponent(".macapk/code").path)) ?? [])
            if let base = ApkRetention.installedBaseApk(androidRoot: root, package: existing?.package) {
                let dir = base.deletingLastPathComponent()
                let bkRoot = root.appendingPathComponent(".macapk/update-backup")
                let bk = bkRoot.appendingPathComponent(dir.lastPathComponent)
                try? fm.removeItem(at: bk)
                do {
                    try fm.createDirectory(at: bkRoot, withIntermediateDirectories: true)
                    try fm.copyItem(at: dir, to: bk)
                    inPlaceBackup = (dir, bk)
                    // What a crash-recovery (InPlaceRecovery, run by the stub
                    // before every launch) needs to restore this exactly.
                    let manifest: [String: Any] = [
                        "installDir": dir.path, "backup": bk.path,
                        "oldDirName": existing?.dirName ?? "", "package": existing?.package ?? "",
                        "ownerPid": Int(getpid()),
                    ]
                    if let d = try? JSONSerialization.data(withJSONObject: manifest) {
                        try? d.write(to: bkRoot.appendingPathComponent("manifest.json"), options: .atomic)
                    }
                    log("[install] in-place: installed package backed up (clone) to \(bk.path)")
                } catch {
                    rollbackInPlace("could not back up the installed package: "
                                  + error.localizedDescription)
                    markBrokenOrRollback(journalledDirName,
                                         hadExisting: existing?.dirName == journalledDirName)
                    return .failed("update-backup", "could not back up the installed app "
                                 + "before updating it, so nothing was changed: "
                                 + error.localizedDescription)
                }
            }
        }
        // ---- extract ----------------------------------------------------
        let ex = extract(req.sourceFile, into: extractRoot)
        guard ex.status == 0 else {
            rollbackInPlace("the extractor exited with status \(ex.status)")
            removeFreshBundleAfterFailedInstall(bundleURL)
            markBrokenOrRollback(journalledDirName,
                                 hadExisting: existing?.dirName == journalledDirName)
            // Stage "invalid-apk" is a CONTRACT string: InstallHelperMain maps
            // it to exit 6 ("unsupported input"), which the session backend
            // reports as STATUS_FAILURE_INVALID / INSTALL_FAILED_INVALID_APK --
            // what a device answers for an APK whose DEX cannot be parsed.
            if ex.invalidApk {
                return .failed("invalid-apk",
                               "the APK's code (DEX) cannot be parsed, so the "
                             + "installed app would not be able to run. "
                             + (ex.diagnostic.isEmpty ? "" : ex.diagnostic))
            }
            return .failed("extract", ex.diagnostic.isEmpty
                           ? "the extractor exited with status \(ex.status)"
                           : "the extractor exited with status \(ex.status). \(ex.diagnostic)")
        }

        // Bind to the directory the runtime actually created, by matching
        // meta.json's apkPath -- the same rule the launcher uses, and the
        // reason we do not have to predict the runtime's version sanitizing.
        guard let found = locateInstalledDir(forApk: req.sourceFile, in: extractRoot) else {
            rollbackInPlace("nothing was unpacked")
            removeFreshBundleAfterFailedInstall(bundleURL)
            markBrokenOrRollback(journalledDirName,
                                 hadExisting: existing?.dirName == journalledDirName)
            return .failed("extract", ex.diagnostic.isEmpty
                           ? "nothing was unpacked, so the app was not added"
                           : "nothing was unpacked. \(ex.diagnostic)")
        }

        // The extractor has run and produced a real app dir. This is the long
        // pole of an install (DEX->JAR conversion, apktool decode, native
        // translation), so it is the boundary a watching store most needs.
        report("extracted", 0.80)

        let pkg = found.package
        let versionName = found.versionName.isEmpty
            ? (peek.versionName ?? found.version) : found.versionName
        // meta.json is the better source once it carries versionCode (lane L1);
        // until then it will be 0 here and the peek is all we have.
        let versionCode = found.versionCode > 0 ? found.versionCode : (peek.versionCode ?? 0)

        // Re-derive against the REAL package in the degraded (no-peek) case.
        var previous = existing
        var effectiveKind = kind
        if peek.package == nil {
            registry = (try? store.load()) ?? registry
            previous = registry.records(forPackage: pkg)
                .first { $0.dirName != found.dirName }
            effectiveKind = classify(newCode: versionCode, newName: versionName,
                                     against: previous)
            log("[install] post-extraction classification: \(effectiveKind.rawValue)")
        }

        // ---- retain -----------------------------------------------------
        // There is NO retained copy any more (owner: "i dont want mac.apk to
        // save a copy of the apks anymore ever", 2026-09-19). The one copy an
        // installed app owns is the one the runtime just laid down where
        // Android keeps it, `<bundle>/Contents/android/data/app/~~<nonce>/
        // <pkg>-<nonce>/base.apk` (meta.json `installedApk`; the dropped file
        // is consumed -- cloned or, for a container, its inner apks extracted
        // there). That is what the stub launches and what the registry row
        // points at, so launch-from-the-launcher, health, repair and
        // reinstall resolve the same file the stub does instead of a path in
        // ~/Downloads the user may clean.
        // docs/disk-footprint-and-space-efficiency_2026-09-19.md.
        var retained: URL? = nil
        if !found.installedApk.isEmpty,
           FileManager.default.fileExists(atPath: found.installedApk) {
            retained = URL(fileURLWithPath: found.installedApk)
        } else if let b = bundleURL,
                  let inst = ApkRetention.installedBaseApk(
                      androidRoot: b.appendingPathComponent("Contents/android"),
                      package: found.package) {
            retained = inst
        }
        if let r = retained {
            log("[retain] installed at \(r.path)")
        } else {
            log("[retain] no installed copy recorded; the row records the source path itself")
        }

        report("retained", 0.85)

        // ---- migrate (carry-forward) -------------------------------------
        var carried: DataCarryForward.Result? = nil
        // In place only when the generator really resolved the INSTALLED
        // bundle (it matches by the row's dirName; a bundle whose stamp
        // disagrees with its row would have produced a new one instead).
        let replacedInPlace: Bool = {
            guard inPlace, let b = bundleURL, let old = previous?.bundlePath else { return false }
            return b.standardizedFileURL.path == URL(fileURLWithPath: old).standardizedFileURL.path
        }()
        if inPlace && !replacedInPlace {
            log("[install] in-place update resolved a different bundle "
              + "(\(bundleURL?.path ?? "none")); installed beside the old version instead")
        }
        if inPlace && !replacedInPlace {
            // Never carry into a bundle we did not mean to replace; the old app is
            // untouched, so undo this attempt rather than leave two half-apps.
            rollbackInPlace("the generator resolved a different bundle")
            markBrokenOrRollback(journalledDirName,
                                 hadExisting: existing?.dirName == journalledDirName)
            return .failed("update-identity", "the update resolved a different app bundle "
                         + "than the installed one, so nothing was changed")
        }
        if replacedInPlace, let prev = previous, let root = extractRoot {
            // v1.0.1641: the extract must have produced THIS package. A manifest
            // read that fell back to the file name ("base") would otherwise carry
            // the user's data into data/data/base, where no launch ever looks.
            guard found.package == prev.package else {
                rollbackInPlace("the extract produced package \(found.package), not \(prev.package)")
                markBrokenOrRollback(journalledDirName,
                                     hadExisting: existing?.dirName == journalledDirName)
                return .failed("update-identity", "the updated APK unpacked as "
                             + "\(found.package), not \(prev.package); nothing was changed")
            }
            if prev.dirName != found.dirName {
                // Extracted APK assets a pre-v1.0.1587 runtime left in the data dir
                // (never reclaimed) are the OLD version's bytes under app-looking
                // names; matched against the OLD package (the backup), they are
                // dropped rather than carried over the new APK's.
                if let b = inPlaceBackup {
                    DataCarryForward.dropLegacyExtractedAssets(
                        dataDir: root.appendingPathComponent("data/data/\(prev.dirName)"),
                        oldInstallDir: b.backup, log: hooks.log)
                }
                let r = DataCarryForward.moveInPlace(from: prev.dirName, to: found.dirName,
                                                     androidRoot: root, log: hooks.log)
                carried = r
                if !r.errors.isEmpty {
                    // Undo the renames that did happen, then the whole update:
                    // "your update failed" is recoverable, "half your saves moved"
                    // is not.
                    // moveInPlace refuses before touching anything, or its one
                    // rename either happened or did not; undo covers the former.
                    // The new data dir is never deleted here: when the carry was
                    // refused it may hold data the app wrote during the update
                    // (rollbackInPlace removes it only if it holds nothing but
                    // our artifacts).
                    DataCarryForward.undoMoveInPlace(r, from: prev.dirName, to: found.dirName,
                                                     androidRoot: root, log: hooks.log)
                    rollbackInPlace("the data carry failed: \(r.errors.prefix(3).joined(separator: "; "))")
                    markBrokenOrRollback(journalledDirName,
                                         hadExisting: existing?.dirName == journalledDirName)
                    return .failed("update-data", "could not move the app's data to the "
                                 + "updated version, so the update was undone: "
                                 + r.errors.prefix(3).joined(separator: "; "))
                }
                // The previous version's CODE dir: our artifacts are regenerable,
                // but apps wrote into it through the pre-v1.0.1641
                // ApplicationInfo.dataDir -- carry those into the new DATA dir
                // (where that field points now), then leave the old name as a link
                // there so absolute paths an app saved keep resolving.
                DataCarryForward.carryCodeDir(prev.dirName, into: found.dirName,
                                              androidRoot: root, log: hooks.log)
            }
        } else if let prev = previous, prev.dirName != found.dirName,
           effectiveKind == .update || effectiveKind == .downgrade {
            carried = DataCarryForward.run(from: prev.dirName, to: found.dirName,
                                           dataDataRoot: dataDataRoot,
                                           androidRoot: androidRoot, log: hooks.log)
        }
        _ = carried

        // ---- register ----------------------------------------------------
        let now = Date()
        var rec = AppRecord(
            package: pkg,
            label: found.label.isEmpty ? pkg : found.label,
            dirName: found.dirName,
            version: found.version,
            versionName: versionName,
            versionCode: versionCode,
            apkPath: (retained ?? req.sourceFile).path,
            originalApkPath: req.sourceFile.path,
            // Inherit a bundlePath ONLY from the SAME row (a reinstall
            // updating its own record). Inheriting a DIFFERENT version's
            // bundle meant that whenever THIS install's own bundle failed to
            // materialize (no peek, unwritable /Applications, generator
            // throw), two registry rows pointed at one bundle -- and
            // uninstalling either trashed the other's data (audit S6).
            bundlePath: (previous?.dirName == found.dirName) ? previous?.bundlePath : nil,
            installedAt: previous?.installedAt ?? now,
            updatedAt: now,
            lastLaunchedAt: previous?.lastLaunchedAt,
            source: req.source.recordValue,
            env: previous?.env ?? [:],
            state: AppState.installing)

        // Drop the provisional journal row if the real dirName differs from
        // the one we guessed, so a bad guess cannot survive as a ghost.
        if let j = journalledDirName, j != rec.dirName { try? store.remove(dirName: j) }
        try? store.upsert(rec)
        report("registered", 0.92)

        // ---- finalise the bundle's identity --------------------------------
        // The bundle was generated BEFORE extraction (so the extractor could
        // target it), which means it was named from the PEEK -- and a peek
        // cannot resolve a resource-reference android:label. Now that the
        // extractor has run we have the real label and a rasterized icon, so
        // stamp them on. This is where "org.fdroid.fdroid 1.23.2.app" becomes
        // "F-Droid 1.23.2.app".
        if var url = bundleURL {
            // MirrorInstallMetadata writes icon.png beside meta.json under
            // "<root>/data/data/<dir>/", and <root> is the bundle here.
            let iconPNG = (extractRoot ?? androidRoot)
                .appendingPathComponent("data/data")
                .appendingPathComponent(found.dirName)
                .appendingPathComponent("icon.png")
            if let fin = hooks.finalizeBundle {
                url = fin(url, rec.label,
                          FileManager.default.fileExists(atPath: iconPNG.path) ? iconPNG : nil,
                          rec.versionName, rec.package, rec.dirName,
                          replacedInPlace ? keptBundleId : nil)
            }
            rec.bundlePath = url.path
        } else if !Paths.bundlesDirIsWritable {
            log("[install] \(Paths.bundlesDir.path) is not writable; installed without a bundle")
        }

        report("finalized", 0.97)

        // ---- finish --------------------------------------------------------
        rec.state = AppState.installed
        do { try store.upsert(rec) }
        catch { return .failed("register", error.localizedDescription) }
        // The replaced version's row goes only now, once the new one is
        // installed: it points at the SAME bundle, and two rows sharing a
        // bundle is the audit-S6 hazard (uninstalling one trashes the other).
        if replacedInPlace, let prev = previous, prev.dirName != rec.dirName {
            try? store.remove(dirName: prev.dirName)
            log("[install] \(prev.dirName) replaced in place by \(rec.dirName)")
        }
        if let b = inPlaceBackup { try? fm.removeItem(at: b.backup.deletingLastPathComponent()) }

        if let fresh = try? store.load() {
            ApkRetention.pruneUnreferenced(package: pkg, registry: fresh, log: hooks.log)
        }

        let wasUpdate = (effectiveKind == .update || effectiveKind == .downgrade)
        log("[install] \(effectiveKind.rawValue) OK: \(rec.label) \(rec.package) "
          + "v\(rec.versionName) (code \(rec.versionCode)) -> \(rec.dirName)")
        report("installed", 1.0)
        return InstallOutcome(status: .success, package: pkg, label: rec.label,
                              versionCode: versionCode, wasUpdate: wasUpdate,
                              bundlePath: rec.bundlePath)
    }

    /// True when a generated bundle's android tree holds at least one
    /// COMPLETED install: a `Contents/android/data/data/<dir>/meta.json`.
    /// The extractor writes meta.json as its LAST step
    /// (runtime/src/apk_unpacker.cpp, WriteAppMetadata at the tail of the
    /// unpack), so its presence separates "this bundle carries a previous
    /// install's data -- adopted across the regeneration swap" from "this
    /// bundle is a fresh skeleton plus, at most, a failed extraction's
    /// debris". That distinction is what makes failure cleanup safe.
    public static func bundleContainsInstalledData(_ bundle: URL) -> Bool {
        let fm = FileManager.default
        let dd = bundle.appendingPathComponent("Contents/android/data/data")
        guard let kids = try? fm.contentsOfDirectory(at: dd,
                                                     includingPropertiesForKeys: nil,
                                                     options: []) else { return false }
        return kids.contains {
            fm.fileExists(atPath: $0.appendingPathComponent("meta.json").path)
        }
    }

    /// A FAILED extraction must not leave a half-built, Spotlight-visible
    /// .app in /Applications forever (audit S7). Only a bundle holding no
    /// completed install is removed: a reinstall/update that failed mid-way
    /// regenerated a bundle that ADOPTED the previous data, and removing
    /// that would destroy the user's saves to tidy up a failure.
    private func removeFreshBundleAfterFailedInstall(_ bundle: URL?) {
        guard let url = bundle else { return }
        guard !Self.bundleContainsInstalledData(url) else {
            log("[install] extract failed; keeping \(url.lastPathComponent) "
              + "-- it holds a previous install's data")
            return
        }
        guard let rm = hooks.removeBundle else { return }
        do {
            try rm(url)
            log("[install] removed the half-built bundle \(url.path) after the failed install")
        } catch {
            log("[install] could not remove the half-built bundle \(url.path): "
              + "\(error.localizedDescription)")
        }
    }

    /// A crashed or failed install must not leave a row claiming `installing`
    /// forever, and must not delete a row that was already there and fine.
    private func markBrokenOrRollback(_ dirName: String?, hadExisting: Bool) {
        guard let d = dirName else { return }
        if hadExisting {
            try? store.mutate { reg in
                if let i = reg.apps.firstIndex(where: { $0.dirName == d }) {
                    reg.apps[i].state = AppState.broken
                }
            }
        } else {
            try? store.remove(dirName: d)
        }
    }

    // MARK: classification

    /// Registry setting that turns in-place STORE updates off ("0"), for A/B.
    public static let storeUpdatesInPlaceSettingKey = "storeUpdatesInPlace"

    /// v1.0.1640 -- does this install REPLACE the installed app, as an update
    /// does on a device (one app, same icon, its data kept)?
    ///
    /// Yes for an UPDATE (higher versionCode) of a package that is installed
    /// with a live bundle, when either
    ///  - an app inside the runtime asked for it (`.android`: Aurora Store,
    ///    F-Droid, via PackageInstaller). A store's "Update" button means
    ///    "replace this app"; installing beside it gave the user a second
    ///    "<Label> (<lastSeg>)" app with none of their data. The session backend
    ///    has already refused an update whose signer does not match the
    ///    installed one (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so the data is
    ///    only ever inherited by the same signer, as on a device; or
    ///  - the bundle identity mode is `.package`, whose documented contract is
    ///    exactly this ("an update REPLACES the app in place").
    /// Finder / launcher installs in the default `.version` mode keep the owner's
    /// per-version independence (2026-08-10), unchanged. Reinstalls of the same
    /// version already regenerate in place; downgrades are not replacements.
    func updatesInPlace(kind: InstallKind, source: InstallSource, existing: AppRecord?) -> Bool {
        // "ok" is the launcher's legacy spelling of installed (MacApkLauncherApp
        // writes it; registry readers treat the two alike).
        guard kind == .update, let old = existing,
              old.state == AppState.installed || old.state == "ok",
              let b = old.bundlePath, !b.isEmpty,
              FileManager.default.fileExists(
                  atPath: URL(fileURLWithPath: b).appendingPathComponent("Contents/Info.plist").path)
        else { return false }
        var byStore = false
        if case .android = source { byStore = true }
        let packageMode = store.setting("bundleIdentityMode") == "package"
        guard byStore || packageMode else { return false }
        if store.setting(Self.storeUpdatesInPlaceSettingKey) == "0" {
            log("[install] in-place updates are off (\(Self.storeUpdatesInPlaceSettingKey)=0)")
            return false
        }
        return true
    }

    func classify(newCode: Int64?, newName: String?, against existing: AppRecord?) -> InstallKind {
        guard let old = existing else { return .fresh }
        if let n = newCode, n > 0, old.versionCode > 0 {
            if n > old.versionCode { return .update }
            if n == old.versionCode { return .reinstall }
            return .downgrade
        }
        // No usable versionCode on one side or the other -- every v1-migrated
        // row is in this state. Fall back to the version STRING, and when even
        // that matches call it a reinstall. Deliberately never guesses
        // "downgrade" from strings: "1.10" < "1.9" lexically, and warning a
        // user about a downgrade that is not one erodes the warning.
        if let n = newName, !n.isEmpty {
            return n == old.versionName || n == old.version ? .reinstall : .update
        }
        return .reinstall
    }

    /// Best-effort directory name for the pre-extraction journal row. The
    /// runtime sanitizes the version its own way, so this is a guess; the row
    /// is replaced with the real one right after extraction.
    private func provisionalDirName(package: String, versionName: String?,
                                    existing: AppRecord?) -> String {
        if let v = versionName, !v.isEmpty {
            return "\(package)_\(ApkRetention.sanitizeComponent(v))"
        }
        return existing?.dirName ?? package
    }

    // MARK: runtime invocations

    /// Ask the runtime for the manifest identity without extracting anything.
    ///
    /// The flag is being added by the runtime lane. Until it lands the runtime
    /// exits non-zero on an unknown flag, which we report as unavailable and
    /// carry on -- an install still works, it just cannot name the version in
    /// the dialog or journal `installing` beforehand.
    public func peekManifest(_ apk: URL) -> PeekResult {
        var r = PeekResult()
        guard FileManager.default.isExecutableFile(atPath: runtimePath) else {
            r.detail = "runtime not executable at \(runtimePath)"
            return r
        }
        let out = runProcess(runtimePath, ["--peek-manifest", apk.path], env: [:])
        guard out.status == 0 else {
            r.detail = "exit \(out.status)"
            return r
        }
        // One line of JSON on stdout. Scan for the LAST line that parses as an
        // object with a "package": the runtime may print banner lines first,
        // and picking the last one is right because later output supersedes.
        for line in out.text.split(whereSeparator: \.isNewline).reversed() {
            guard let d = line.trimmingCharacters(in: .whitespaces).data(using: .utf8),
                  let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                  let pkg = j["package"] as? String, !pkg.isEmpty
            else { continue }
            r.available = true
            r.package = pkg
            r.versionName = j["versionName"] as? String
            r.label = (j["label"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            if let n = j["versionCode"] as? NSNumber { r.versionCode = n.int64Value }
            else if let s = j["versionCode"] as? String { r.versionCode = Int64(s) }
            if let l = j["label"] as? String, !l.isEmpty { r.detail = l }
            return r
        }
        r.detail = "no JSON identity line on stdout"
        return r
    }

    struct ExtractResult {
        var status: Int32; var text: String; var diagnostic: String
        /// The runtime printed its INSTALL_FAILED_INVALID_APK marker: the
        /// APK's DEX cannot be parsed, so an installed package would have no
        /// executable code. Distinguished from a generic extractor failure
        /// because the two deserve different statuses at the store -- AOSP
        /// answers STATUS_FAILURE_INVALID for exactly this, and the helper
        /// maps it to its "unsupported input" exit 6.
        var invalidApk: Bool = false
    }

    /// The extraction invocation, matched to what the launcher does today
    /// (MacApkLauncherApp.swift ~2296-2302): argv `--apk <p> --android-root <r>
    /// --extract-only`, env `MACAPK_APP_CACHE_DIR=<root>/data/data`, one pipe
    /// carrying both streams. The single pipe is load-bearing: two pipes with
    /// one reader deadlock once the unread one fills its 64 KB buffer.
    /// Extract INTO a specific android root.
    ///
    /// `into` is the app's own bundle (`<bundle>/Contents/android`) for a real
    /// install, and only falls back to the shared root when no bundle could be
    /// made. Owner decision 2026-08-11: "I want all the app data to be
    /// contained to the .app bundle ... the only thing shared would be the
    /// Mac.apk runtime." Extracting to a shared root and moving afterwards is
    /// what left an app with two homes and a copy that outlived its bundle.
    /// Honest sub-stage progress for the minutes-long extract. The runtime
    /// prints a machine line -- `[macapk] stage <name>` -- strictly AFTER each
    /// named stage finishes (apk_unpacker.cpp / main.cpp; a dedicated line,
    /// not prose, for the same reason HelperProgress is). The fractions here
    /// are hand-chosen waypoints inside the pipeline's own
    /// prepared(0.15)..extracted(0.80) band, shaped to the measured cold
    /// timeline (the DEX conversion is the long pole). The extract stages can
    /// OVERLAP since v1.0.1001 (the converter runs concurrently with the
    /// resource decode), so the caller must apply these through a monotonic
    /// high-water mark -- a stage that finishes out of order must never move
    /// the bar backwards.
    static let extractStageFractions: [Substring: Double] = [
        "merged": 0.25,             // the bundle's split set validated in place (was: merged)
        "resources-decoded": 0.35,  // (v1.0.1590: printed immediately -- nothing is decoded)
        "dex-converted": 0.70,      // the long pole: classes.jar written
        // Same fraction as dex-converted: the extract prints this INSTEAD
        // when the parse verdict is in but a long conversion was handed to
        // the background (v1.0.1002) -- the install's dex stage is resolved
        // either way, and the finished jar is adopted by the next launch.
        "dex-deferred": 0.70,
        "assets-extracted": 0.74,   // assets tar + lib/<abi> unzip done
        "natives-translated": 0.78, // install-time icon + .so translation done
    ]

    func extract(_ apk: URL, into root: URL? = nil) -> ExtractResult {
        let r = root ?? androidRoot
        var highWater = 0.15        // the "prepared" report that precedes us
        let out = runProcess(runtimePath,
                             ["--apk", apk.path,
                              "--android-root", r.path,
                              "--extract-only"],
                             // Matches AppStubMain's contract exactly, so an
                             // install and a later launch agree about where the
                             // code cache lives.
                             env: ["MACAPK_APP_CACHE_DIR":
                                     r.appendingPathComponent(".macapk/code").path],
                             onLine: { line in
            guard line.hasPrefix("[macapk] stage ") else { return }
            let name = line.dropFirst("[macapk] stage ".count)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let f = Self.extractStageFractions[name[...]], f > highWater
            else { return }
            highWater = f
            // Hyphen, not a dot: InstallHelperMain.emitProgress strips
            // anything outside [alnum_-] from stage names.
            self.report("extract-\(name)", f)
        })
        return ExtractResult(status: out.status, text: out.text,
                             diagnostic: Self.diagnosticLine(from: out.text),
                             // apk_unpacker.cpp prints this marker (and exits
                             // 65) when the DEX set cannot be parsed. String
                             // detection over exit-code detection on purpose:
                             // the runtime's other failure exits are signals
                             // and aborts whose numbers we do not own.
                             invalidApk: out.text.contains("INSTALL_FAILED_INVALID_APK"))
    }

    struct ProcOut { var status: Int32; var text: String }

    /// `onLine` (optional) sees each complete output line, WITHOUT its
    /// newline, as it arrives -- the extract's sub-stage progress needs the
    /// lines mid-run, and reading to EOF first is precisely what kept the
    /// progress bar frozen for the length of the extract. Called on the
    /// calling thread; the read loop below replaces readDataToEndOfFile with
    /// the same blocking semantics (availableData blocks until data or EOF),
    /// so behavior without onLine is unchanged.
    func runProcess(_ exe: String, _ args: [String], env: [String: String],
                    onLine: ((String) -> Void)? = nil) -> ProcOut {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: exe)
        p.arguments = args
        var e = ProcessInfo.processInfo.environment
        for (k, v) in env { e[k] = v }
        p.environment = e
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch {
            return ProcOut(status: -1, text: "could not start \(exe): \(error.localizedDescription)")
        }
        // Read to EOF BEFORE waiting: a chatty extract on a large APK outruns
        // the pipe buffer and waiting first would block forever.
        let fh = pipe.fileHandleForReading
        var data = Data()
        // Bytes of `data` already scanned for line breaks. Marker lines are
        // pure ASCII, so scanning raw bytes for 0x0A cannot split a
        // multi-byte scalar of a marker; a non-UTF8 line simply fails the
        // String conversion and is skipped by the scanner (the full
        // transcript in `data` is untouched either way).
        var scanned = 0
        while true {
            let chunk = fh.availableData      // blocks until data or EOF
            if chunk.isEmpty { break }        // EOF
            data.append(chunk)
            guard let onLine else { continue }
            while let nl = data[scanned...].firstIndex(of: 0x0A) {
                if let line = String(data: data[scanned..<nl], encoding: .utf8) {
                    onLine(line)
                }
                scanned = nl + 1
            }
        }
        p.waitUntilExit()
        var text = String(data: data, encoding: .utf8) ?? ""
        if text.count > 65_536 { text = String(text.suffix(65_536)) }
        return ProcOut(status: p.terminationStatus, text: text)
    }

    /// Pick the most useful line out of a failed run's output: the LAST
    /// error-shaped line, since later output supersedes earlier.
    static func diagnosticLine(from output: String) -> String {
        let lines = output.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let needles = ["error", "failed", "cannot", "no such", "not a ",
                       "invalid", "unsupported", "exception", "corrupt"]
        let picked = lines.last(where: { l in
            let low = l.lowercased(); return needles.contains { low.contains($0) }
        }) ?? lines.last
        guard var s = picked else { return "" }
        if s.count > 200 { s = String(s.prefix(197)) + "..." }
        return s
    }

    // MARK: data-dir discovery

    struct FoundApp {
        var dirName: String; var package: String; var label: String
        var version: String; var versionName: String; var versionCode: Int64
        /// meta.json `installedApk` (v1.0.1585): the ONE copy of the app the
        /// runtime keeps, `<root>/data/app/~~<nonce>/<pkg>-<nonce>/base.apk`.
        /// Empty for a meta.json written by an older runtime.
        var installedApk: String = ""
    }

    /// Find the `<androidRoot>/data/data` subdir whose meta.json records this
    /// APK. `.staging.<pid>` dirs are skipped: they are a concurrent (or
    /// crashed) install's half-written tree, their meta.json can transiently
    /// match, and binding to one yields a row whose directory then vanishes.
    /// Find the directory the runtime actually created for this APK.
    ///
    /// `in root` is the android root the extraction targeted -- the app's own
    /// bundle for a real install. Scanning the SHARED root instead would find
    /// nothing for a bundled install, and the caller reports that as "nothing
    /// was unpacked", i.e. a successful install looks like a failure.
    /// MirrorInstallMetadata puts meta.json + icon.png under
    /// "<root>/data/data/<dir>/", which is what this reads.
    func locateInstalledDir(forApk apk: URL, in root: URL? = nil) -> FoundApp? {
        let fm = FileManager.default
        let scanRoot = root.map { $0.appendingPathComponent("data/data") } ?? dataDataRoot
        guard let entries = try? fm.contentsOfDirectory(
            at: scanRoot, includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]) else { return nil }
        var best: (FoundApp, Date)? = nil
        for dir in entries {
            let name = dir.lastPathComponent
            if name.contains(".staging.") || name.hasPrefix(".") { continue }
            // v1.0.1641: data/data/<oldDirName> is a LINK to the current data dir
            // after an in-place update; it shows the current meta.json under the
            // old name and must never be taken for an install.
            if DataCarryForward.isSymlink(dir) { continue }
            let meta = dir.appendingPathComponent("meta.json")
            guard let d = try? Data(contentsOf: meta),
                  let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
            else { continue }
            let recorded = (j["apkPath"] as? String) ?? ""
            let match = (recorded == apk.path)
                || (URL(fileURLWithPath: recorded).lastPathComponent == apk.lastPathComponent)
            guard match else { continue }
            let pkg = (j["package"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? RegistryStore.packageFromDirName(name)
            var vc: Int64 = 0
            if let n = j["versionCode"] as? NSNumber { vc = n.int64Value }
            else if let s = j["versionCode"] as? String { vc = Int64(s) ?? 0 }
            let found = FoundApp(
                dirName: name, package: pkg,
                label: (j["label"] as? String) ?? "",
                version: Self.versionFromDirName(name, package: pkg),
                versionName: (j["versionName"] as? String) ?? "",
                versionCode: vc,
                installedApk: (j["installedApk"] as? String) ?? "")
            let mtime = (try? dir.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? .distantPast
            // Several dirs can legitimately match on filename (the same APK
            // installed at two versions). Take the most recently written one:
            // that is the extraction we just ran.
            if best == nil || mtime > best!.1 { best = (found, mtime) }
        }
        return best?.0
    }

    static func versionFromDirName(_ dirName: String, package: String) -> String {
        let prefix = package + "_"
        return dirName.hasPrefix(prefix) ? String(dirName.dropFirst(prefix.count)) : ""
    }

    func iconURL(forDirName dirName: String) -> URL? {
        let p = dataDataRoot.appendingPathComponent(dirName).appendingPathComponent("icon.png")
        return FileManager.default.fileExists(atPath: p.path) ? p : nil
    }

    // MARK: uninstall

    /// Uninstall an app: bundle, retained APKs, registry rows, and -- when
    /// asked -- the data directories.
    ///
    /// `deleteData` defaults to true because that is what removing an app
    /// means to a Mac user and it is the owner's stated point ("uninstall any
    /// app like they are already used to"). The launcher's existing dialog
    /// already exposes it as a checkbox, so the caller decides.
    /// `dirName` scopes the removal to ONE installed version. The owner's model
    /// is one .app per version (A9 legitimately leaves two side by side), so a
    /// tile's Uninstall must remove the version that tile shows and leave its
    /// siblings alone. nil = every version of the package, which is what the
    /// CLI verb and the bulk remover want.
    @discardableResult
    public func uninstall(package: String, dirName: String? = nil,
                          deleteData: Bool = true) -> InstallStatus {
        let fm = FileManager.default
        guard let reg = try? store.load() else {
            return .failed(stage: "uninstall", message: "could not read the registry")
        }
        var rows = reg.records(forPackage: package)
        if let only = dirName, !only.isEmpty {
            rows = rows.filter { $0.dirName == only }
            if rows.isEmpty {
                return .failed(stage: "uninstall", message: "\(only) is not installed")
            }
        }
        if rows.isEmpty { return .failed(stage: "uninstall", message: "\(package) is not installed") }

        // Refuse while a guest is running: removing its bundle and data dir out
        // from under a live process loses its unsaved state and leaves it
        // writing into a deleted tree. Same two signals the install path uses
        // (runningGuests, then the stub's pidfile flock, which is authoritative
        // where the former can be stale). MACAPK_UNINSTALL_RUNNING_CHECK=0 skips.
        if ProcessInfo.processInfo.environment["MACAPK_UNINSTALL_RUNNING_CHECK"] != "0" {
            let live = RuntimeInstall.runningGuests()
            for r in rows {
                var running = live.contains(r.dirName) || live.contains(r.package)
                if !running {
                    for name in Set([r.dirName, r.package]) where !name.isEmpty {
                        let fd = open(Paths.runDir.appendingPathComponent("\(name).pid").path, O_RDONLY)
                        guard fd >= 0 else { continue }
                        if flock(fd, LOCK_EX | LOCK_NB) != 0 { running = true } else { flock(fd, LOCK_UN) }
                        close(fd)
                        if running { break }
                    }
                }
                if running {
                    return .failed(stage: "app-running",
                                   message: "\(r.label) is running. Quit it, then try uninstalling again.")
                }
            }
        }

        for r in rows {
            if let bp = r.bundlePath {
                let url = URL(fileURLWithPath: bp)
                // ⚠ THE BUNDLE CONTAINS THE DATA. Removing it unconditionally
                // made `deleteData: false` a LIE -- proven by running it with a
                // planted sentinel, which did not survive. So when the caller
                // asks to keep the data, lift it OUT of the bundle first and
                // say where it went; otherwise "keep my saves" silently meant
                // "throw them away with everything else".
                if !deleteData, !r.dirName.isEmpty {
                    do {
                        if let dst = try KeptData.lift(dirName: r.dirName, fromBundle: url) {
                            log("[uninstall] kept app data at \(dst.path); "
                              + "reinstalling \(r.package) \(r.versionName) restores it")
                        }
                    } catch {
                        // Do NOT proceed to trash the bundle if we could not
                        // rescue the data the caller asked us to keep.
                        log("[uninstall] could not preserve app data (\(error.localizedDescription)); "
                          + "leaving \(bp) in place so nothing is lost")
                        continue
                    }
                }
                if let rm = hooks.removeBundle {
                    do { try rm(url) } catch { log("[uninstall] bundle: \(error.localizedDescription)") }
                } else if fm.fileExists(atPath: bp) {
                    // No generator wired yet: still remove what we created, so
                    // uninstall is complete rather than half-done.
                    do { try fm.removeItem(at: url) }
                    catch { log("[uninstall] could not remove \(bp): \(error.localizedDescription)") }
                }
            }
            if deleteData {
                // ⚠ An empty dirName makes appendingPathComponent("") return
                // dataDataRoot ITSELF, and this removeItem would then delete
                // every installed app's data. The install path always fills
                // dirName in, but records are read from a JSON file that is
                // shared across builds and hand-editable, so refuse instead
                // of trusting it.
                guard !r.dirName.isEmpty else {
                    log("[uninstall] refusing to delete data for a row with an empty dirName (package=\(r.package))")
                    continue
                }
                let dd = dataDataRoot.appendingPathComponent(r.dirName)
                if fm.fileExists(atPath: dd.path) {
                    do { try fm.removeItem(at: dd); log("[uninstall] removed \(dd.path)") }
                    catch { log("[uninstall] could not remove \(dd.path): \(error.localizedDescription)") }
                }
            }
            // The regenerable half goes on EVERY uninstall, not just when
            // deleteData is set: the user asked for the app to be gone, and
            // .macapk/code/<dirName> is 50-200 MB per app of converted jar,
            // apktool decode and translated dylibs that nothing will ever
            // read again once the row is removed. Keeping it would leak that
            // per app forever. It is reproducible from the APK, so removing
            // it can never lose anything the user authored.
            if !r.dirName.isEmpty {
                let cd = codeRoot.appendingPathComponent(r.dirName)
                if fm.fileExists(atPath: cd.path) {
                    do { try fm.removeItem(at: cd); log("[uninstall] removed \(cd.path)") }
                    catch { log("[uninstall] could not remove \(cd.path): \(error.localizedDescription)") }
                }
                // The stub's single-instance pidfile (run/<dirName>.pid; a
                // few bytes, but residue the uninstall promise covers).
                try? fm.removeItem(at: Paths.runDir.appendingPathComponent("\(r.dirName).pid"))
            }
        }
        // The pre-dirName pidfile spelling, written by stubs generated
        // before the dirName keying.
        try? fm.removeItem(at: Paths.runDir.appendingPathComponent("\(package).pid"))
        // Stage S1: the removed rows' special-access grants ("All files
        // access", media management). AOSP clears an uninstalled package's
        // app-ops; the store's installedAt binding would already refuse a stale
        // entry after a reinstall, but leaving it would be residue the uninstall
        // promise covers. Always, including --keep-data: the grant belongs to
        // the install, not to the saves. A grant a SURVIVING row shares (same
        // package + installedAt: a side-by-side version) stays.
        let removedIds = rows.map(AppOpsStore.Identity.init(record:))
        let survivorIds = reg.apps
            .filter { r in !rows.contains { $0.dirName == r.dirName } }
            .map(AppOpsStore.Identity.init(record:))
        AppOpsStore.removeEntries(removedIds, keeping: survivorIds)
        // "Also delete its data" has to mean it. An earlier keep-data uninstall
        // may have parked a tree for this package; leaving it would make the
        // delete a lie in the other direction -- and it would silently come
        // back on the next install. Scoped exactly as the removal was, so
        // removing v1 cannot discard v2's parked saves.
        if deleteData {
            _ = KeptData.discard(package: package, dirNames: rows.map(\.dirName),
                                 log: hooks.log)
        }
        do {
            if let only = dirName, !only.isEmpty {
                // Scoped to one version: drop just that row, then let the
                // retention pruner decide which APKs are still referenced by
                // the SURVIVING rows. removeAll(package:) here would delete the
                // sibling version's source APK too.
                try store.remove(dirName: only)
                if let fresh = try? store.load() {
                    ApkRetention.pruneUnreferenced(package: package, registry: fresh,
                                                   log: hooks.log)
                }
            } else {
                _ = ApkRetention.removeAll(package: package, log: hooks.log)
                try store.remove(package: package)
            }
        } catch { return .failed(stage: "uninstall", message: error.localizedDescription) }
        log("[uninstall] \(dirName ?? package): \(rows.count) row(s) removed, deleteData=\(deleteData)")
        return .success
    }

    // MARK: uninstall every app (the runtime-uninstall dialog's opt-in)

    /// What a bulk uninstall actually did, per app.
    ///
    /// Reported rather than summed into a bool because a PARTIAL failure is the
    /// dangerous case: a registry that claims apps which are gone, or hides
    /// apps that remain, is worse than either clean outcome. The caller shows
    /// this, and `uninstallAllApps` reconciles afterwards so the registry
    /// matches the disk whatever happened.
    public struct BulkUninstallReport {
        public var removed: [String] = []
        public var failures: [String] = []          // "pkg: why"
        public var orphanBundlesRemoved: [String] = []
        public var refusedBundles: [String] = []    // failed the identifier guard
        public var retainedApksSwept: Int = 0

        public var isClean: Bool { failures.isEmpty && refusedBundles.isEmpty }
    }

    /// Exactly what a bulk uninstall would touch, computed without touching it.
    ///
    /// Split out from the execution so the destructive path can be INSPECTED.
    /// `debug/installtest/run.sh` asserts against this rather than running the
    /// real thing, because running the real thing on the development machine
    /// would delete the owner's library -- and a targeting bug is precisely
    /// what the gate needs to catch BEFORE anyone presses the button.
    public struct BulkUninstallPlan {
        public var packages: [String] = []
        /// `.app` bundles that will be removed. Every entry has passed the
        /// `org.macapk.app.` identifier guard.
        public var bundles: [String] = []
        /// `.app` bundles in a bundle folder that carry our prefix but are
        /// refused anyway (belt and braces around the control panel).
        public var refusedBundles: [String] = []
        /// Everything else in the bundle folders, counted not listed: these are
        /// the user's own Mac apps and the number is the reassurance.
        public var untouchedBundleCount: Int = 0
        public var retainedApks: [String] = []
    }

    /// Enumerate the targets. Pure: nothing here removes anything.
    public func planUninstallAllApps() -> BulkUninstallPlan {
        let fm = FileManager.default
        var plan = BulkUninstallPlan()

        // By PACKAGE: uninstall() removes every row for a package (three
        // hillclimb versions are three rows, one call).
        if let reg = try? store.load() {
            var seen = Set<String>()
            plan.packages = reg.apps.map(\.package).filter { seen.insert($0).inserted }
        }

        // Bundles, including ones the registry never knew about (a row lost to
        // a crash, a hand-copied .app). "Remove all apps" that leaves a
        // launchable icon behind has not removed all apps.
        // Both bundle folders: an app installed while this user could not
        // write /Applications lives in ~/Applications (GitHub #28).
        do {
            for b in Paths.bundleCandidates() {
                // ⚠ THE ONE GUARD between a bug in this dialog and the user's
                // /Applications. Only bundles WE generated carry this prefix.
                guard let id = InstallPipeline.bundleIdentifier(of: b),
                      id.hasPrefix("org.macapk.app.") else {
                    plan.untouchedBundleCount += 1
                    continue
                }
                // Mac.apk.app is org.macapk.launcher and so can never match the
                // prefix above -- but assert it separately rather than relying
                // on that arithmetic surviving the next edit to a bundle id.
                // Owner, explicitly: "the uninstall runtime button will not
                // remove the control panel."
                guard id != "org.macapk.launcher",
                      b.lastPathComponent != "Mac.apk.app" else {
                    plan.refusedBundles.append(b.path); continue
                }
                plan.bundles.append(b.path)
            }
        }

        // Retained APK copies. Without these the user has "removed all apps"
        // and still has gigabytes of APKs.
        if let files = try? fm.contentsOfDirectory(at: Paths.apksDir,
                                                   includingPropertiesForKeys: nil,
                                                   options: [.skipsHiddenFiles]) {
            plan.retainedApks = files.map(\.path)
        }
        return plan
    }

    /// Remove EVERY installed Android app, its data, its bundle, its registry
    /// row and its retained APK.
    ///
    /// This is the most destructive thing the product can do, and it is
    /// deliberately NOT a second remover: it drives the same
    /// `uninstall(package:deleteData:)` the tile's right-click menu uses, once
    /// per package, so there is one implementation of "what removing an app
    /// means" and it cannot drift.
    ///
    /// It only runs when the user ticks the (default-off) checkbox in the
    /// runtime-uninstall dialog. Removing the runtime alone never calls it.
    @discardableResult
    public func uninstallAllApps() -> BulkUninstallReport {
        let fm = FileManager.default
        var report = BulkUninstallReport()
        let plan = planUninstallAllApps()
        report.refusedBundles = plan.refusedBundles

        for p in plan.packages {
            switch uninstall(package: p, deleteData: true) {
            case .success:              report.removed.append(p)
            case .failed(_, let msg):   report.failures.append("\(p): \(msg)")
            case .cancelled:            report.failures.append("\(p): cancelled")
            case .authFailed:           report.failures.append("\(p): not authorized")
            }
        }

        // Re-plan rather than reusing the first list: the per-package
        // uninstalls above have already removed most of these bundles, and a
        // stale list would report failures for paths that are correctly gone.
        for b in planUninstallAllApps().bundles {
            let url = URL(fileURLWithPath: b)
            do { try fm.removeItem(at: url); report.orphanBundlesRemoved.append(b) }
            catch { report.failures.append("\(url.lastPathComponent): \(error.localizedDescription)") }
        }

        for f in planUninstallAllApps().retainedApks {
            do { try fm.removeItem(at: URL(fileURLWithPath: f)); report.retainedApksSwept += 1 }
            catch { log("[uninstall-all] could not remove \(f): \(error)") }
        }

        // Whatever happened above, leave the registry agreeing with the disk.
        reconcile(apply: true)
        log("[uninstall-all] removed \(report.removed.count) package(s), "
          + "\(report.orphanBundlesRemoved.count) orphan bundle(s), "
          + "\(report.retainedApksSwept) retained APK(s), "
          + "\(report.failures.count) failure(s)")
        return report
    }

    /// `CFBundleIdentifier` of a .app, or nil. Foundation-only on purpose
    /// (this file must stay linkable into the headless helper and the stub).
    public static func bundleIdentifier(of app: URL) -> String? {
        let p = app.appendingPathComponent("Contents/Info.plist")
        guard let d = try? Data(contentsOf: p),
              let plist = (try? PropertyListSerialization.propertyList(
                    from: d, format: nil)) as? [String: Any] else { return nil }
        return plist["CFBundleIdentifier"] as? String
    }

    /// Everything a "remove all apps" would delete, for the confirmation
    /// dialog. Counting bundles rather than registry rows because the bundle is
    /// what the user SEES, and a dialog that says "all your apps" without
    /// saying how many is not informed consent.
    public static func installedAppFootprint() -> (count: Int, bytes: Int64) {
        var count = 0
        var bytes: Int64 = 0
        do {
            for b in Paths.bundleCandidates() {
                guard let id = bundleIdentifier(of: b), id.hasPrefix("org.macapk.app.")
                else { continue }
                count += 1
                bytes += RuntimeInstall.directoryBytes(b)
            }
        }
        // The retained APK copies go too, so they belong in the figure.
        bytes += RuntimeInstall.directoryBytes(Paths.apksDir)
        return (count, bytes)
    }

    // MARK: reconcile

    public struct ReconcileFinding {
        public enum Problem: String {
            case bundleMissing      // the .app is gone; the user probably trashed it
            case bundleUnreachable  // could not tell -- unmounted volume, EACCES, ...
            case bundleUnregistered // one of OUR .apps in a bundle folder with no row
            case dataDirMissing     // extracted tree gone; app needs re-extraction
            case apkMissing         // no retained or original APK on disk
            case stuckInstalling    // interrupted install, now marked broken
            case orphanCodeCache    // <androidRoot>/.macapk/code/<dirName> with no app
            case stalePurposeStrings // Info.plist lacks a TCC purpose string the generator writes
        }
        public let dirName: String
        public let package: String
        public let label: String
        public let problem: Problem
        public let detail: String
    }

    /// Compare the registry against the disk and report the gaps.
    ///
    /// `apply` persists the two SAFE convergences: the bundle-presence state
    /// transition (`installed` <-> `removed`, computed once in
    /// `Registry.reconcileBundlePresence` and written by
    /// `RegistryStore.reconcileBundles`), and the adoption of a Mac.apk bundle
    /// that is on disk with no registry row. Everything else is REPORTED and
    /// left alone.
    ///
    /// ⚠ IT STILL NEVER DELETES ANYTHING. Not the row, not the retained APK,
    /// not the code cache, not a data tree. "The user dragged the .app to the
    /// Trash" and "the user moved it, or a rebuild replaced /Applications" are
    /// indistinguishable from here, and the second reading makes any deletion
    /// unrecoverable harm. Marking the row `removed` is fully reversible: put
    /// the bundle back and the next read flips it to `installed` again.
    ///
    /// ⚠ CHANGED at v1.0.998: this used to CLEAR `bundlePath` for a missing
    /// bundle and leave `state` at `installed`. That was the bug the owner
    /// hit -- the row still said installed, so `MacApkPackageManager` kept
    /// telling Aurora Store that a trashed app was present and Aurora refused
    /// to reinstall it -- and clearing the path also destroyed the only
    /// evidence that could ever detect the bundle coming BACK. The parameter
    /// was named `applyBundleClearing` for that behaviour and is now `apply`.
    @discardableResult
    public func reconcile(apply: Bool = true) -> [ReconcileFinding] {
        let fm = FileManager.default
        // load() already applies the presence transform in memory, so `r.state`
        // below is the reconciled state, not the byte on disk.
        guard let reg = try? store.load() else { return [] }
        var out: [ReconcileFinding] = []

        for r in reg.apps {
            let presence = BundleLiveness.presence(ofBundleAt: r.bundlePath)
            var bundleGone = false
            switch presence {
            case .absent:
                bundleGone = true
                out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                 problem: .bundleMissing,
                                 detail: "\(r.bundlePath ?? "?") no longer exists"))
            case .unknown(let why):
                // Reported so it is visible, but it changes NOTHING: an
                // unreachable volume must not look like a deleted app.
                out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                 problem: .bundleUnreachable, detail: why))
            case .present, .notTracked:
                break
            }
            // ⚠ AN APP'S DATA LIVES INSIDE ITS BUNDLE (v1.0.949 per-app model),
            // not under the shared android root. Checking only `dataDataRoot`
            // reported `dataDirMissing` for ALL 11 installed apps on this
            // machine -- i.e. the reconcile UI would have told the user every
            // app had lost its data, while every app was intact. Look where the
            // data actually is, and fall back to the shared root only for a row
            // that never got a bundle.
            //
            // When the bundle itself is gone the data went with it BY DESIGN
            // (that is the drag-to-Trash promise), so reporting both is noise:
            // bundleMissing is the finding, and a second dataDirMissing line
            // would only make the real one harder to see.
            if !bundleGone {
                let dd: URL = r.bundlePath.map {
                    URL(fileURLWithPath: $0)
                        .appendingPathComponent("Contents/android/data/data")
                        .appendingPathComponent(r.dirName)
                } ?? dataDataRoot.appendingPathComponent(r.dirName)
                if !fm.fileExists(atPath: dd.path) {
                    out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                     problem: .dataDirMissing,
                                     detail: "\(dd.path) no longer exists"))
                }
            }
            if PurposeStrings.refreshEnabled, !bundleGone, let bp = r.bundlePath,
               let missing = PurposeStrings.missing(inBundle: URL(fileURLWithPath: bp)),
               !missing.isEmpty {
                out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                 problem: .stalePurposeStrings,
                                 detail: "Info.plist has no \(missing.joined(separator: ", "))"
                                       + " (macOS refuses those capabilities without prompting)"))
            }
            let apkOK = ApkRetention.apkCandidates(for: r)
                .contains { fm.fileExists(atPath: $0) }
            if !apkOK {
                out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                 problem: .apkMissing,
                                 detail: "neither the bundle's own APK nor the original is on disk"))
            }
            if r.state == AppState.broken {
                out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                 problem: .stuckInstalling,
                                 detail: "an install did not finish; repair or remove"))
            }
            // The code cache is OUTSIDE the bundle (<androidRoot>/.macapk/code)
            // and is 50-200 MB per app of converted jar + apktool decode +
            // translated dylibs. `uninstall` removes it; a drag-to-Trash cannot,
            // so nothing would ever collect it. REPORTED, never swept here --
            // it is also exactly what makes a reinstall fast, so deleting it
            // behind the user is both destructive and counterproductive. The
            // uninstall ("Remove from library") path is what frees it.
            if bundleGone, !r.dirName.isEmpty {
                let cd = codeRoot.appendingPathComponent(r.dirName)
                if fm.fileExists(atPath: cd.path) {
                    out.append(.init(dirName: r.dirName, package: r.package, label: r.label,
                                     problem: .orphanCodeCache,
                                     detail: "\(cd.path) is kept (a reinstall reuses it); "
                                           + "\"Remove from library\" frees it"))
                }
            }
        }

        // The REVERSE drift: a Mac.apk bundle sitting in /Applications that no
        // row names. Happens when a user copies a bundle in from another Mac,
        // restores one from a backup, or drags one back out of the Trash after
        // its row was hand-edited away. A one-directional reconcile that only
        // marks apps missing is half a fix.
        let unregistered = Self.unregisteredBundles(knownTo: reg)
        for u in unregistered {
            out.append(.init(dirName: u.dirName, package: u.package, label: u.label,
                             problem: .bundleUnregistered,
                             detail: "\(u.bundle.path) has no registry row"))
        }

        guard apply else { return out }

        if out.contains(where: { $0.problem == .stalePurposeStrings }) {
            _ = Self.refreshStalePurposeStrings(store: store, hooks: hooks)
        }
        if let moved = try? store.reconcileBundles(), !moved.isEmpty {
            for m in moved { log("[reconcile] \(m.package): \(m.from) -> \(m.to) (\(m.detail))") }
        }
        if !unregistered.isEmpty {
            try? store.mutate { reg in
                for u in unregistered
                where !reg.apps.contains(where: { $0.dirName == u.dirName }) {
                    reg.apps.append(u.record)
                }
            }
            log("[reconcile] adopted \(unregistered.count) unregistered bundle(s)")
        }
        return out
    }

    /// Re-stamp every installed bundle whose Info.plist predates a TCC purpose
    /// string the generator now writes (PurposeStrings.keys), through the SAME
    /// finalizeBundle hook an in-place update uses: the plist is rewritten from
    /// the current generator, the bundle id, name and exec link are kept, and
    /// the bundle is re-sealed. Returns the dirNames refreshed.
    ///
    /// WHY. A bundle's Info.plist is written once, at install. The purpose
    /// strings were added to the generator on 2026-08-24 (camera had failed
    /// with ERROR_CAMERA_DISABLED and no prompt), but nothing ever re-stamped a
    /// bundle installed before that, so those apps are refused the camera,
    /// microphone, location, Bluetooth, local network, Photos and speech
    /// recognition by macOS WITHOUT a prompt, for good. Measured 2026-09-29:
    /// 13 of 69 installed bundles, Aegis' QR scanner among them.
    ///
    /// A RUNNING app is skipped (its stub's pidfile flock is held), exactly as
    /// an in-place update refuses one; the next reconcile picks it up. While the
    /// refresh HOLDS an app's lock the pidfile reads "0" (no process), as in the
    /// in-place update path, so a stub launched mid-refresh sees "already
    /// running" and cannot "activate" a recycled pid left in the file by the
    /// app's last run.
    /// Re-sealing changes the ad-hoc signature, so macOS forgets any TCC answer
    /// keyed to the old one -- a bundle with no purpose string had none to
    /// forget for those services.
    ///
    /// MACAPK_PURPOSE_REFRESH=0 turns the whole thing off (this and the
    /// reconcile finding): nothing re-stamped a bundle before it existed.
    /// `runDir` is a parameter only so a gate can hold a scratch pidfile.
    @discardableResult
    public static func refreshStalePurposeStrings(store: RegistryStore = .shared,
                                                  hooks: Hooks,
                                                  runDir: URL = Paths.runDir) -> [String] {
        let log: (String) -> Void = { hooks.log?($0) }
        guard PurposeStrings.refreshEnabled,
              let fin = hooks.finalizeBundle, let reg = try? store.load() else { return [] }
        var done: [String] = []
        for r in reg.apps where r.state == AppState.installed {
            guard let bp = r.bundlePath, !bp.isEmpty else { continue }
            let bundle = URL(fileURLWithPath: bp)
            guard let missing = PurposeStrings.missing(inBundle: bundle), !missing.isEmpty
            else { continue }
            var locks: [Int32] = []
            defer { for fd in locks { flock(fd, LOCK_UN); close(fd) } }
            var running = false
            try? FileManager.default.createDirectory(at: runDir,
                                                     withIntermediateDirectories: true)
            for name in Set([r.dirName, r.package]) where !name.isEmpty {
                let path = runDir.appendingPathComponent("\(name).pid").path
                let fd = open(path, O_RDWR | O_CREAT, 0o644)
                guard fd >= 0 else { continue }
                if flock(fd, LOCK_EX | LOCK_NB) != 0 { close(fd); running = true; break }
                locks.append(fd)
            }
            // Only once EVERY lock is held (a running app's pidfile must never be
            // touched): no pid a waiting stub could "activate" -- 0 names no process.
            if !running {
                for fd in locks {
                    _ = ftruncate(fd, 0)
                    _ = "0\n".withCString { write(fd, $0, 2) }
                }
            }
            if running {
                log("[reconcile] \(r.label): purpose strings missing (\(missing.joined(separator: ", "))), "
                  + "but the app is running; will refresh at the next reconcile")
                continue
            }
            let keepId = PurposeStrings.bundleIdentifier(inBundle: bundle)
            let url = fin(bundle, nil, nil, r.versionName, r.package, r.dirName, keepId)
            let still = PurposeStrings.missing(inBundle: url) ?? missing
            if still.isEmpty {
                log("[reconcile] \(r.label): re-stamped Info.plist with \(missing.count) missing "
                  + "purpose string(s) (\(missing.joined(separator: ", ")))")
                done.append(r.dirName)
            } else {
                log("[reconcile] \(r.label): Info.plist still lacks \(still.joined(separator: ", "))")
            }
        }
        return done
    }

    /// A Mac.apk bundle on disk that the registry does not know about, and the
    /// row we would adopt it with.
    struct UnregisteredBundle {
        let bundle: URL
        let dirName: String
        let package: String
        let label: String
        let record: AppRecord
    }

    /// Scan every bundle folder (`Paths.bundleDirs`) for OUR bundles with no
    /// matching row. A dirName found in both folders is offered once, from
    /// the preferred folder.
    static func unregisteredBundles(knownTo reg: Registry) -> [UnregisteredBundle] {
        var out: [UnregisteredBundle] = []
        var seen = Set<String>()
        for d in Paths.bundleDirs {
            for u in unregisteredBundles(knownTo: reg, in: d) where seen.insert(u.dirName).inserted {
                out.append(u)
            }
        }
        return out
    }

    /// One bundle folder's share of the scan above.
    ///
    /// Four gates, all of which must pass before a bundle is adoptable, so a
    /// stray .app can never synthesise an "installed" answer:
    ///  1. `CFBundleIdentifier` starts with `org.macapk.app.` -- we made it;
    ///  2. `MacapkPackage` names a package;
    ///  3. `Contents/android/data/data/<dir>/meta.json` exists -- the runtime
    ///     writes that file only after a COMPLETED unpack, so this is the same
    ///     "holds a finished install" test `bundleContainsInstalledData` uses;
    ///  4. no row already carries that `dirName`.
    /// Identity and version come from the bundle's OWN meta.json (written by
    /// the runtime, not inferred from the directory name), so an adopted row
    /// carries a real `versionCode` and cannot mis-order an update.
    /// `in` is a parameter rather than a constant only so a gate can point it
    /// at a scratch directory. Product callers always take the default.
    static func unregisteredBundles(knownTo reg: Registry,
                                    in bundlesDir: URL) -> [UnregisteredBundle] {
        let fm = FileManager.default
        guard let kids = try? fm.contentsOfDirectory(at: bundlesDir,
                                                     includingPropertiesForKeys: nil,
                                                     options: [.skipsHiddenFiles])
        else { return [] }
        let known = Set(reg.apps.map(\.dirName))
        var out: [UnregisteredBundle] = []
        for b in kids.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
        where b.pathExtension == "app" {
            guard let id = bundleIdentifier(of: b), id.hasPrefix("org.macapk.app.") else { continue }
            guard let plist = infoPlist(of: b),
                  let pkg = plist["MacapkPackage"] as? String, !pkg.isEmpty else { continue }
            let dd = b.appendingPathComponent("Contents/android/data/data")
            guard let dirs = try? fm.contentsOfDirectory(at: dd, includingPropertiesForKeys: nil,
                                                         options: [.skipsHiddenFiles])
            else { continue }
            for d in dirs {
                if DataCarryForward.isSymlink(d) { continue }   // v1.0.1641 compat link
                let meta = d.appendingPathComponent("meta.json")
                guard fm.fileExists(atPath: meta.path) else { continue }
                let dirName = d.lastPathComponent
                if known.contains(dirName) { continue }
                guard let md = try? Data(contentsOf: meta),
                      let j = (try? JSONSerialization.jsonObject(with: md)) as? [String: Any]
                else { continue }
                let metaPkg = (j["package"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? pkg
                let label = (j["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? metaPkg
                let versionName = (j["versionName"] as? String) ?? ""
                let versionCode = (j["versionCode"] as? NSNumber)?.int64Value ?? 0
                let version = versionName.isEmpty
                    ? versionFromDirName(dirName, package: metaPkg) : versionName
                // The APK path recorded in meta.json is where the install ran
                // FROM, which on an adopted bundle may be another machine's
                // path. Only claim `apkPath` when the file is actually here;
                // otherwise keep it as provenance only.
                let metaApk = (j["apkPath"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                let apkHere = metaApk.flatMap { fm.fileExists(atPath: $0) ? $0 : nil }
                let stamp = (try? b.resourceValues(forKeys: [.contentModificationDateKey]))?
                    .contentModificationDate
                out.append(UnregisteredBundle(
                    bundle: b, dirName: dirName, package: metaPkg, label: label,
                    record: AppRecord(package: metaPkg, label: label, dirName: dirName,
                                      version: version, versionName: version,
                                      versionCode: versionCode,
                                      apkPath: apkHere, originalApkPath: metaApk,
                                      bundlePath: b.path,
                                      installedAt: stamp, updatedAt: stamp,
                                      lastLaunchedAt: nil,
                                      source: "adopted", env: [:],
                                      state: AppState.installed)))
            }
        }
        return out
    }

    static func infoPlist(of app: URL) -> [String: Any]? {
        let p = app.appendingPathComponent("Contents/Info.plist")
        guard let d = try? Data(contentsOf: p) else { return nil }
        return (try? PropertyListSerialization.propertyList(from: d, format: nil)) as? [String: Any]
    }

    /// Re-run the pipeline from `extract` for a broken row, using the retained
    /// APK (or the original if retention never happened).
    public func repair(dirName: String, interactive: Bool = false) -> InstallOutcome {
        guard let reg = try? store.load(),
              let rec = reg.apps.first(where: { $0.dirName == dirName })
        else { return .failed("repair", "no registry entry for \(dirName)") }
        let fm = FileManager.default
        let candidates = ApkRetention.apkCandidates(for: rec)
        guard let apk = candidates.first(where: { fm.fileExists(atPath: $0) }) else {
            return .failed("repair",
                           "no APK on disk for \(rec.label); reinstall it from a file")
        }
        log("[repair] re-running the pipeline for \(rec.label) from \(apk)")
        return run(InstallRequest(sourceFile: URL(fileURLWithPath: apk),
                                  source: .gui, interactive: interactive))
    }
}

private extension InstallOutcome {
    static func failed(_ stage: String, _ msg: String) -> InstallOutcome {
        InstallOutcome(status: .failed(stage: stage, message: msg))
    }
}

// MARK: - RuntimeInstall (the SHARED runtime, install / uninstall)

/// The shared Mac.apk runtime, staged OUTSIDE the control panel so it can be
/// removed without removing the control panel.
///
/// Owner, 2026-08-12: *"the control panel needs an option to check if the
/// runtime is installed, and two buttons ... Runtime: (install) and
/// (uninstall) ... That way they can add or remove the runtime while keeping
/// all apps installed"*, and, decisively, *"the uninstall runtime button will
/// not remove the control panel. Just the shared runtime."*
///
/// ## Where it lives, and why it moved out of Mac.apk.app
///
/// Before this, the only runtime was `Mac.apk.app/Contents/{Resources,bin,lib}`
/// and every per-app stub exec'd it from there. That left no way to satisfy the
/// owner: deleting it deletes part of the control panel, and leaving it makes
/// Uninstall change nothing a user can observe -- apps keep launching and the
/// button looks broken. So the runtime is now STAGED at
///
///     ~/Library/Application Support/Mac.apk/runtime/
///
/// whose layout deliberately MIRRORS the bundle's `Contents/`, because the
/// runtime resolves its own dependencies by relative path and those paths are
/// a hard contract:
///
///     Resources/macapk-runtime   the binary; finds ./libGLESv2 ./libEGL at
///                                the @executable_path rpath baked into it
///     bin/                       ../bin/libmacapk-bionic-shim.dylib (main.cpp),
///                                macapk-translator (AppClassLoader passes this
///                                dir as --shim-dir, so the shim must be beside
///                                it), macapk-elfexec
///     lib/                       ../lib/android-framework.jar and
///                                macapk-loader.jar (config.h kFrameworkJarRel /
///                                kLoaderJarRel), plus the app-stub dylib and
///                                bcprov, which Boot.java probes relative to the
///                                loader jar's GRANDPARENT -- which is why the
///                                staged root must be the Contents/ shape and
///                                not a flat directory.
///     build-id.txt               the identity (see below)
///
/// ## The trap this class exists to close
///
/// The stub's runtime lookup was a FALLBACK CHAIN. If the control panel keeps
/// an executable runtime inside its own bundle AND the stub still falls back to
/// it, removing the staged copy is invisible. Both halves are closed here, on
/// purpose, because each covers a population the other cannot:
///
///  1. **New stubs never exec the in-bundle copy at all.** `resolveRuntime()`
///     returns the STAGED binary or nothing. The bundle is consulted only as
///     the install SOURCE.
///  2. **`uninstall()` also clears the execute bit on the in-bundle payload.**
///     This is what makes the button honest for the ~11 bundles ALREADY on this
///     machine, whose stub binaries are copies compiled before rule 1 existed
///     and which still walk to `Mac.apk.app/Contents/Resources/macapk-runtime`.
///     Their check is `isExecutableFile`, and `execv` on a non-executable file
///     fails with EACCES, so a de-executable-bitted payload cannot be run by
///     any stub, old or new. `install()` restores the bit.
///
/// The payload is NOT deleted from the bundle, and that is deliberate: it is
/// the source Install stages from, and a network updater is an explicit
/// non-goal (docs/platform-integration-master-plan_2026-07-11.md section 13).
/// Removing it would make Install impossible offline.
///
/// ## "Installed" is not a second source of truth
///
/// Freshness is decided by `build-id.txt` -- the SAME file
/// `debug/staleness.sh` compares and the same one `runtime/scripts/build.sh`
/// writes. Staged content whose build-id differs from the control panel's is
/// reported STALE and re-staged, so this UI cannot drift from `staleness.sh`.
public enum RuntimeInstall {

    // MARK: paths

    /// The staged runtime root. Mirrors a bundle's `Contents/`.
    public static var stagedRoot: URL {
        Paths.supportDir.appendingPathComponent("runtime", isDirectory: true)
    }

    /// Written by `uninstall()`, deleted by `install()`.
    ///
    /// Its ONLY job is to stop the self-heal in `ensureStaged` from silently
    /// undoing a deliberate uninstall on the next app launch. Without it,
    /// "uninstalled" and "not staged yet" are the same on-disk state and the
    /// button would appear to work and then quietly reverse itself.
    /// Strip quarantine from the STAGED runtime tree, once per staged build.
    ///
    /// WHY, and why v1.0.1030 did not cover it. Staging copies the payload out
    /// of Mac.apk.app with `copyfile(COPYFILE_CLONE | COPYFILE_ALL)`, and
    /// COPYFILE_ALL carries xattrs (the comment at that call says so). When
    /// Mac.apk.app arrived as a downloaded DMG, every payload FILE carries
    /// com.apple.quarantine even though the bundle itself passed Gatekeeper --
    /// so the staged tree, which is the code every launch actually executes,
    /// is quarantined. `xattr -l` on a bundle directory does not show its
    /// children, which is why a check of the three bundle roots found nothing.
    ///
    /// None of the v1.0.1030 clear sites reached it: the per-app bundle and
    /// the run wrapper are cleared, but the stub execv's a SYMLINK into this
    /// tree, and clearOne uses XATTR_NOFOLLOW, so clearing the symlink never
    /// touches the target. Executing quarantined code triggers an assessment
    /// whose enclosing bundle is the deliberately unsealed generated .app --
    /// which can never pass -- and the launch is refused as "damaged".
    ///
    /// Once per staged build rather than per launch: the tree is tens of MB
    /// and a recursive walk on every app start is pure waste. The marker
    /// records the staged build id, so a re-stage redoes the work.
    static func dequarantineStagedOnce(log: ((String) -> Void)? = nil) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: stagedRoot.path) else { return }
        let marker = stagedRoot.appendingPathComponent(".dequarantined")
        let idURL = stagedRoot.appendingPathComponent("Resources/build-id.txt")
        let want = (try? String(contentsOf: idURL, encoding: .utf8)) ?? "unknown"
        if let have = try? String(contentsOf: marker, encoding: .utf8), have == want {
            return
        }
        Quarantine.clear(at: stagedRoot)
        try? want.write(to: marker, atomically: true, encoding: .utf8)
        log?("[runtime] cleared quarantine on the staged runtime tree")
    }

    public static var removedMarkerURL: URL {
        Paths.supportDir.appendingPathComponent("runtime-removed")
    }

    /// Serialises stage/remove across the launcher, the helper, and every
    /// per-app stub that self-heals on launch.
    private static var lockPath: String {
        Paths.supportDir.appendingPathComponent("runtime.lock").path
    }

    /// The runtime binary to exec, or nil when the runtime is not installed.
    /// STAGED ONLY -- never the copy inside the control panel. That is the
    /// whole point; see the trap note above.
    public static func resolveRuntime() -> URL? {
        if let override = ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"],
           !override.isEmpty {
            let rt = URL(fileURLWithPath: override)
                .appendingPathComponent("Resources/macapk-runtime")
            return FileManager.default.isExecutableFile(atPath: rt.path) ? rt : nil
        }
        let rt = stagedRoot.appendingPathComponent("Resources/macapk-runtime")
        return FileManager.default.isExecutableFile(atPath: rt.path) ? rt : nil
    }

    // MARK: the payload manifest

    /// Exactly what "the runtime" is, as paths relative to a bundle's
    /// `Contents/` (and to `stagedRoot`, which has the same shape).
    ///
    /// ⚠ KEEP IN STEP WITH `runtime/scripts/build.sh`'s `sync_payload_into`
    /// pair list. That function is what refreshes a live bundle after a build;
    /// this is what copies a bundle to the staged root. A file in one and not
    /// the other means the staged runtime is missing something a freshly built
    /// bundle has, which surfaces as a mystery failure inside the guest rather
    /// than as a staging error.
    static let payload: [String] = [
        "Resources/macapk-runtime",
        "Resources/libskiko-macos-arm64.dylib",
        "Resources/libmacapk-skia-metal.dylib",
        "Resources/libGLESv2.dylib",
        "Resources/libEGL.dylib",
        "Resources/libvulkan.dylib",
        "Resources/libMoltenVK.dylib",
        "Resources/MoltenVK_icd.json",
        "Resources/macapk_default_icon.png",
        "bin/macapk-translator",
        "bin/libmacapk-bionic-shim.dylib",
        "bin/macapk-elfexec",
        // Relinked third-party deps (v1.0.981 self-containment). The shim and
        // translator load these as @rpath siblings now that they no longer
        // point at /opt/homebrew. MEASURED 2026-08-14: without them staged, the
        // shim fails to dlopen inside every stub-launched app --
        //   tried: .../runtime/bin/libcapstone.5.dylib (no such file)
        // -- while the control-panel path keeps working, because it runs the
        // in-bundle copy that has them. Same two-execution-surfaces shape as
        // Resources/jre in payloadDirs below.
        "bin/libcapstone.5.dylib",
        "bin/libpng16.16.dylib",
        "lib/libmacapk-app-stub.dylib",
        "lib/android-framework.jar",
        "lib/macapk-loader.jar",
        "lib/bcprov-jdk18on-1.79.jar",
        "Resources/build-id.txt",   // moved out of Contents/ at v1.0.1019 so the bundle can be sealed
    ]

    /// Payload members that are DIRECTORIES of .class files, not files:
    /// every `Contents/lib/*_patch` the control panel ships.
    ///
    /// `--patch-module` dirs. `runtime/scripts/build.sh` compiles them and
    /// `main.cpp` passes `--patch-module=<module>=Contents/lib/<dir>` for each
    /// one that exists. `payload` copies single files and would silently skip a
    /// directory, so they are discovered separately.
    ///
    /// ⚠ DISCOVERED BY PATTERN, NEVER LISTED BY NAME, and that is the whole
    /// lesson of the bug that produced this code. These dirs were never bundled
    /// at all before 2026-08-12 because `scripts/build_launcher.sh` copied a
    /// HARDCODED LIST OF JARS: the java.base patch (SimpleDateFormat's
    /// standalone `'c'`, v1.0.845) was therefore live when running the repo
    /// binary and absent from every .app the owner launches, and `main.cpp`
    /// guards the flag with `fs::exists()` so nothing said a word. A hardcoded
    /// list here would reproduce that exactly the day a third patch dir lands.
    /// Matching `*_patch` means a new one is staged the moment it is bundled.
    static func payloadDirs(in contentsRoot: URL) -> [String] {
        var out: [String] = []

        let lib = contentsRoot.appendingPathComponent("lib", isDirectory: true)
        if let kids = try? FileManager.default.contentsOfDirectory(
                at: lib, includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]) {
            out += kids.filter {
                $0.lastPathComponent.hasSuffix("_patch")
                    && ((try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false)
            }
            .map { "lib/" + $0.lastPathComponent }
        }

        // ⚠ Resources/jre and Resources/tools: the SELF-CONTAINMENT payload
        // (v1.0.981). These are DIRECTORIES, so `payload` -- which copies single
        // files -- silently skipped them, and the comment above turned out to be
        // prophetic: "a hardcoded list here would reproduce that exactly the day
        // a third patch dir lands." It did, within two days, in a different
        // directory than the one it was watching.
        //
        // MEASURED 2026-08-14, and this broke EVERY app-bundle launch: once
        // relink_bundle_deps.sh rewrote macapk-runtime to load
        // @rpath/libjvm.dylib from the bundled JRE, the staged copy still had no
        // jre/, so the stub died before reaching the runtime at all:
        //
        //   dyld: Library not loaded: @rpath/libjvm.dylib
        //     tried: .../Mac.apk/runtime/Resources/libjvm.dylib (no such file)
        //
        // Launching the same app from the control panel kept working, because
        // that path runs the IN-BUNDLE binary next to the real jre/. Two
        // execution surfaces, one of them staged and incomplete -- the same
        // shape as the java.base patch bug this function was written to close.
        //
        // Listed by name rather than by pattern because Resources/ also holds
        // appstub/, which is the per-app stub template and NOT runtime payload;
        // globbing here would stage it into a directory it has no business in.
        // Add a name when a new runtime payload dir is bundled.
        // "Frameworks" added v1.0.1018. It does NOT exist in a dev bundle --
        // stage_jre.sh puts the java.desktop dylibs flat in jre/lib and
        // relink_bundle_deps.sh puts capstone/libpng in Contents/bin -- but
        // scripts/build_dist.sh's relocate_recursive creates it and rewrites
        // dependents to @rpath with rpath @loader_path/../../../Frameworks.
        // Staged without it, that rpath resolves to a directory that is never
        // created, and an INSTALLED app (which execs the staged copy, not the
        // one in the bundle) dies at the first java.desktop touch -- while
        // state() still reports "Installed / not stale", because it compares
        // only payload members. Launching from the control panel works, because
        // the in-bundle Frameworks resolves. That asymmetry is why this could
        // only ever have been caught in a release build.
        // The isDirectory guard below makes this a no-op on a dev bundle.
        //
        // "lib/pairip-refs" added v1.0.1574, and it reproduced the Frameworks
        // asymmetry above VERBATIM the day it landed. It is a DATA directory
        // (the harvested pairip boot refs: import table, decrypted string pool,
        // one decrypted code segment + its .fnv byte-check), not a *_patch class
        // dir, so neither the `payload` file list nor the `*_patch` glob staged
        // it. main.cpp defaults MACAPK_PAIRIP_{IMPORT_TABLE,INJECT_STRINGS,
        // CODE_PATCH_DIR} to <bin>/../lib/pairip-refs, so an INSTALLED app (which
        // execs the STAGED copy) ran pairip with the gate ON but the refs ABSENT:
        // the string pool stayed null and a pool reader handed HotSpot a garbage
        // jstring -> SIGSEGV in jni_GetStringLength -> abort, ~11s in. The repo
        // binary and the control-panel path both resolve their own
        // lib/pairip-refs and were unaffected, so state() reported "Installed /
        // not stale" the whole time (it compares only listed payload). Measured +
        // A/B-confirmed 2026-09-18: staged, MC boots and renders; not staged, it
        // aborts. build_launcher.sh already bundles it into Contents/lib; this is
        // what carries it the rest of the way into the staged shared runtime.
        for dir in ["Resources/jre", "Resources/tools", "Frameworks", "lib/pairip-refs"] {
            let u = contentsRoot.appendingPathComponent(dir, isDirectory: true)
            if (try? u.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                out.append(dir)
            }
        }
        return out.sorted()
    }

    /// Without these the runtime cannot boot, so their absence is a staging
    /// ERROR rather than a skipped optional file. The rest of `payload` is
    /// genuinely optional (a Metal-only ANGLE build has no libMoltenVK, a
    /// Capstone-less build has no translator) exactly as build.sh treats them.
    static let requiredPayload: Set<String> = [
        "Resources/macapk-runtime",
        "Resources/libskiko-macos-arm64.dylib",
        "Resources/libmacapk-skia-metal.dylib",
        "lib/android-framework.jar",
        "lib/macapk-loader.jar",
    ]

    /// The payload members that must be executable to be useful. `uninstall()`
    /// strips the bit from these inside the control panel; `install()` puts it
    /// back. Dylibs and jars are dlopen'd/read, never exec'd, so stripping them
    /// would prove nothing.
    static let executablePayload: [String] = [
        "Resources/macapk-runtime",
        "bin/macapk-translator",
        "bin/macapk-elfexec",
    ]

    // MARK: source (the payload the control panel ships)

    /// Find the control panel's `Contents/` directory -- the install source.
    ///
    /// Foundation-only by the file's layering rule, so LaunchServices is NOT
    /// consulted here; a caller that has AppKit (the stub) passes its own
    /// resolved candidate in `extra`.
    public static func sourceContents(extra: [URL] = []) -> URL? {
        let fm = FileManager.default
        var candidates: [URL] = []
        if let override = ProcessInfo.processInfo.environment["MACAPK_CANONICAL_APP"],
           !override.isEmpty {
            candidates.append(URL(fileURLWithPath: override)
                .appendingPathComponent("Contents", isDirectory: true))
        }
        candidates.append(contentsOf: extra)
        candidates.append(URL(fileURLWithPath: "/Applications/Mac.apk.app/Contents",
                              isDirectory: true))
        // A per-user install (~/Applications/Mac.apk.app) is a normal place to
        // put an app, and a user who cannot write /Applications has no other.
        // Before this the control panel running from there found no payload of
        // its own (Runtime: Install disabled, "carries no runtime payload"),
        // because the GUI calls state()/install() with no `extra`. GitHub #13.
        candidates.append(FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications/Mac.apk.app/Contents", isDirectory: true))
        // Last, the bundle THIS binary ships in, wherever it was put (any
        // folder, a renamed copy). Only the control panel and the install
        // helper carry Resources/macapk-runtime; a per-app stub's own bundle
        // does not, so for a stub this candidate never matches. Kept after the
        // fixed locations so an existing /Applications install still wins.
        let me = (Bundle.main.executableURL
                  ?? URL(fileURLWithPath: CommandLine.arguments[0])).resolvingSymlinksInPath()
        candidates.append(me.deletingLastPathComponent().deletingLastPathComponent())
        for c in candidates {
            // The runtime binary may legitimately be non-executable here (that
            // is what uninstall does to it), so test EXISTENCE, not the bit --
            // otherwise Install could never find the payload it just disabled.
            if fm.fileExists(atPath: c.appendingPathComponent("Resources/macapk-runtime").path) {
                return c
            }
        }
        return nil
    }

    // MARK: state

    public struct State {
        /// A staged, executable runtime is present.
        public let installed: Bool
        /// `build-id.txt` from the staged copy, trimmed. nil when absent.
        public let stagedBuildId: String?
        /// `build-id.txt` from the control panel's payload.
        public let sourceBuildId: String?
        /// The user pressed Uninstall (the marker is present).
        public let removedByUser: Bool
        /// The control panel still ships a payload we could install from.
        public let sourceAvailable: Bool
        /// A legacy stub could still exec the copy inside the control panel.
        /// Must be false whenever `installed` is false, or Uninstall is a lie.
        public let bundleRuntimeExecutable: Bool
        /// Disk held by the staged copy.
        public let stagedBytes: Int64
        /// `payloadDirs` present in the control panel but missing from the
        /// staged copy. See `isStale` for why this is tracked separately.
        public let missingPayloadDirs: [String]
        /// Payload FILES that differ from what the control panel ships.
        public let stalePayloadFiles: [String]

        /// Staged, but not what the control panel currently ships.
        ///
        /// Three independent triggers, and NONE of them is redundant -- each
        /// covers a change the other two cannot see:
        ///
        ///  * `stalePayloadFiles` -- the runtime BINARY and its dylibs/jars.
        ///    The only signal that catches a main.cpp edit, because no build id
        ///    covers macapk-runtime (see `staleFiles`).
        ///  * `missingPayloadDirs` -- a `--patch-module` dir. Not in the
        ///    framework jar, so FRAMEWORK_ID cannot see it either.
        ///  * `build-id.txt` -- the same comparison `debug/staleness.sh` makes.
        ///    Kept so this UI cannot disagree with that script.
        ///
        /// ⚠ KNOWN HOLE, stated here so it is not mistaken for coverage:
        /// `payloadDirs` are checked for EXISTENCE only, never for content, and
        /// `FRAMEWORK_ID` folds the `*_patch` dirs in by CLASS SHAPE alone
        /// (`runtime/scripts/build_ids.py` PATCH_DIRS + `shape_record`: name,
        /// access flags, super, interfaces). So a method-BODY change to a
        /// `--patch-module` class -- SimpleDateFormat's standalone 'c' is the
        /// live example -- moves no id and trips no trigger above, and the
        /// staged copy silently keeps the old bytecode. `Resources/jre` and
        /// `Resources/tools` have the same exposure. It is left open rather
        /// than closed by walking those trees because `Resources/jre` alone is
        /// ~160 MB and this runs on every app launch; the right fix is a cheap
        /// digest computed once by the build, not a recursive stat here.
        public var isStale: Bool {
            guard installed else { return false }
            if !stalePayloadFiles.isEmpty { return true }
            if !missingPayloadDirs.isEmpty { return true }
            guard let s = sourceBuildId else { return false }
            return stagedBuildId != s
        }

        /// ⚠ "Installed" is reserved for a COMPLETE, current runtime. A staged
        /// tree missing a `--patch-module` dir runs, but runs different
        /// bytecode from the one the control panel ships, and calling that
        /// "Installed" is the second-source-of-truth drift this whole type is
        /// written to avoid. Name the incompleteness instead.
        public var summary: String {
            if !installed { return "Not installed" }
            if !missingPayloadDirs.isEmpty {
                return "Incomplete (missing "
                     + missingPayloadDirs.map { ($0 as NSString).lastPathComponent }
                        .joined(separator: ", ") + ")"
            }
            return isStale ? "Installed (stale)" : "Installed"
        }
    }

    public static func state(extraSources: [URL] = []) -> State {
        let fm = FileManager.default
        let src = sourceContents(extra: extraSources)
        let staged = stagedRoot.appendingPathComponent("Resources/macapk-runtime")
        let bundleRT = src?.appendingPathComponent("Resources/macapk-runtime")
        var missingDirs: [String] = []
        if let src {
            for rel in payloadDirs(in: src)
            where !fm.fileExists(atPath: stagedRoot.appendingPathComponent(rel).path) {
                missingDirs.append(rel)
            }
        }
        return State(
            installed: fm.isExecutableFile(atPath: staged.path),
            stagedBuildId: buildId(at: stagedRoot),
            sourceBuildId: src.flatMap { buildId(at: $0) },
            removedByUser: fm.fileExists(atPath: removedMarkerURL.path),
            sourceAvailable: src != nil,
            bundleRuntimeExecutable: bundleRT.map { fm.isExecutableFile(atPath: $0.path) } ?? false,
            stagedBytes: directoryBytes(stagedRoot),
            missingPayloadDirs: missingDirs,
            stalePayloadFiles: src.map { staleFiles(source: $0) } ?? [])
    }

    /// Payload files whose (size, mtime) differ between the control panel and
    /// the staged copy -- i.e. the staged runtime is not the one that shipped.
    ///
    /// ⚠ THIS, NOT build-id.txt, IS THE LOAD-BEARING FRESHNESS TEST, and the
    /// reason is a hole that build-id.txt cannot see. `NATIVE_ID` is a hash of
    /// `macapk-translator` + `libmacapk-bionic-shim.dylib` -- it does NOT
    /// include `macapk-runtime`. So editing main.cpp or apk_unpacker.cpp, which
    /// is the single most common kind of change in this repo, rebuilds the
    /// runtime binary and moves NO id at all. Measured 2026-08-12: after a full
    /// `runtime/scripts/build.sh` that replaced the bundle's runtime binary,
    /// a build-id-only check still reported a clean "Installed" while the
    /// staged copy held the previous binary. Left that way, a runtime-only
    /// change would never reach a GUI-launched app and every measurement taken
    /// through a bundle would silently be of old code -- the exact failure the
    /// freshness machinery in CLAUDE.md exists to kill.
    ///
    /// (size, mtime) rather than a content hash because both `ditto` -- which
    /// `runtime/scripts/build.sh`'s sync_payload_into uses -- and `copyfile`
    /// with COPYFILE_ALL, which install() uses, preserve mtime exactly. So
    /// "same size and same mtime" means "this IS that copy", it costs ~15
    /// stat(2) calls instead of hashing 45 MB, and this runs on every app
    /// launch.
    /// Files that ride in a `payloadDirs` directory but must ALSO be compared
    /// for staleness one by one: the directory copy is checked for existence
    /// only, so a changed member would otherwise leave the staged runtime
    /// reading "Installed". `Resources/tools/framework-res.apk` is the platform
    /// resource apk the runtime resolves every @android: reference against
    /// (v1.0.1589); a rebuilt one must restage. NOT copied here -- the
    /// directory copy carries it (a file copy first would make the later
    /// copyItem of the directory fail on an existing destination).
    static let payloadDirFiles: [String] = [
        "Resources/tools/framework-res.apk",
    ]

    static func staleFiles(source: URL) -> [String] {
        let fm = FileManager.default
        var out: [String] = []
        for rel in payload + payloadDirFiles {
            let s = source.appendingPathComponent(rel)
            guard let sa = try? fm.attributesOfItem(atPath: s.path) else { continue }
            guard let da = try? fm.attributesOfItem(
                    atPath: stagedRoot.appendingPathComponent(rel).path) else {
                out.append(rel); continue          // shipped, but not staged
            }
            let ss = (sa[.size] as? NSNumber)?.int64Value
            let ds = (da[.size] as? NSNumber)?.int64Value
            let sm = (sa[.modificationDate] as? Date)?.timeIntervalSince1970
            let dm = (da[.modificationDate] as? Date)?.timeIntervalSince1970
            if ss != ds || sm.map({ abs($0 - (dm ?? 0)) > 1 }) ?? false {
                out.append(rel)
            }
        }
        return out
    }

    /// `build-id.txt` under a Contents-shaped root, trimmed. The whole file, so
    /// a change to ANY of the ids counts. Kept for DISPLAY and as a second
    /// signal; `staleFiles` is what actually decides freshness (see above).
    public static func buildId(at contentsRoot: URL) -> String? {
        guard let s = try? String(contentsOf: contentsRoot.appendingPathComponent("Resources/build-id.txt"),
                                  encoding: .utf8) else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    // MARK: install

    public enum Result: Equatable {
        case ok(String)
        case failed(String)

        public var isOk: Bool { if case .ok = self { return true }; return false }
        public var message: String {
            switch self { case .ok(let m), .failed(let m): return m }
        }
    }

    /// Stage the runtime from the control panel's payload.
    ///
    /// Atomic at the directory level: the whole tree is built beside the target
    /// and swapped in, so an interrupted install can never leave a HALF staged
    /// runtime that `resolveRuntime()` would happily hand to a guest app.
    @discardableResult
    public static func install(extraSources: [URL] = [],
                               log: ((String) -> Void)? = nil) -> Result {
        Paths.ensureDirectories()
        guard let lock = FileLock(path: lockPath), lock.lock(timeoutMs: 30_000) else {
            return .failed("another Mac.apk process is installing or removing the runtime")
        }
        defer { lock.unlock() }

        guard let src = sourceContents(extra: extraSources) else {
            return .failed("no Mac.apk runtime payload found. Expected it inside "
                         + "Mac.apk.app/Contents; reinstall Mac.apk.")
        }
        let fm = FileManager.default

        // Restore the execute bits we cleared on uninstall FIRST: the copy
        // below preserves mode, so staging a de-executable-bitted payload would
        // produce a staged runtime that cannot be exec'd either.
        for rel in executablePayload {
            let p = src.appendingPathComponent(rel)
            guard fm.fileExists(atPath: p.path) else { continue }
            if !fm.isExecutableFile(atPath: p.path) {
                if chmod(p.path, 0o755) == 0 {
                    log?("[runtime] restored the execute bit on \(rel)")
                } else {
                    log?("[runtime] WARN could not restore the execute bit on \(rel): "
                       + String(cString: strerror(errno)))
                }
            }
        }

        let staging = Paths.supportDir.appendingPathComponent(
            "runtime.staging-\(getpid())", isDirectory: true)
        try? fm.removeItem(at: staging)
        var copied = 0
        var missingRequired: [String] = []
        do {
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            for rel in payload {
                let from = src.appendingPathComponent(rel)
                guard fm.fileExists(atPath: from.path) else {
                    if requiredPayload.contains(rel) { missingRequired.append(rel) }
                    continue
                }
                let to = staging.appendingPathComponent(rel)
                try fm.createDirectory(at: to.deletingLastPathComponent(),
                                       withIntermediateDirectories: true)
                // COPYFILE_CLONE makes this an APFS clone (near-instant, no
                // extra disk until something diverges) and falls back to a real
                // copy when the volume cannot clone. COPYFILE_ALL carries the
                // mode and xattrs, which matters: the runtime binary is ad-hoc
                // signed WITH ENTITLEMENTS (allow-jit, custom-x18-abi-toggle),
                // the signature is embedded in the Mach-O rather than sealed by
                // an enclosing bundle (`Sealed Resources=none`), so a
                // byte-faithful copy stays valid wherever it lands. That is the
                // same property the app-stub template relies on.
                if copyfile(from.path, to.path, nil,
                            copyfile_flags_t(COPYFILE_CLONE | COPYFILE_ALL)) != 0 {
                    throw RegistryError.io("copy \(rel): " + String(cString: strerror(errno)))
                }
                copied += 1
            }
            for rel in Self.payloadDirs(in: src) {
                let from = src.appendingPathComponent(rel)
                let to = staging.appendingPathComponent(rel)
                try fm.createDirectory(at: to.deletingLastPathComponent(),
                                       withIntermediateDirectories: true)
                // FileManager rather than copyfile here: these are tiny trees of
                // .class files, and copyItem's recursive semantics need no flag
                // archaeology to be obviously right.
                try fm.copyItem(at: from, to: to)
                copied += 1
            }
        } catch {
            try? fm.removeItem(at: staging)
            return .failed("staging failed: \(error.localizedDescription)")
        }
        if !missingRequired.isEmpty {
            try? fm.removeItem(at: staging)
            return .failed("the Mac.apk payload is incomplete (missing "
                         + missingRequired.joined(separator: ", ")
                         + "). Rebuild with runtime/scripts/build.sh.")
        }

        // Name the guests that are running RIGHT NOW, before the swap.
        //
        // It is one implementation for every caller -- the control panel's
        // button, the stub's self-heal, `macapk-installhelper runtime-install`
        // and therefore scripts/rebuild_and_run.sh's staging step -- rather
        // than each one re-deriving "is anything running" from the pidfiles in
        // its own language. It is a NOTE, not a refusal: unlike uninstall(),
        // staging leaves a working runtime in place the whole time.
        //
        // WHAT THE SWAP DOES TO A LIVE GUEST -- MEASURED 2026-08-19, not
        // assumed. A real ad-hoc-signed-with-entitlements macapk-runtime
        // (flags=0x10002 adhoc,runtime), exec'd through a stub-style symlink
        // into the staged tree, was put to work on a 245 MB APK and the tree
        // was then renamed aside and rm -rf'd underneath it, i.e. exactly the
        // sequence below. It survived and ran to completion, exit code 0, no
        // crash report -- twice. The rename leaves the old inode alone, so the
        // mapped image keeps its vnode and can still page in; it is overwriting
        // signed code IN PLACE that gets a process killed, which is why the
        // swap is written this way.
        //
        // The honest caveat, from the same run: after the swap, anything the
        // live guest resolves BY PATH comes from the NEW tree. The test guest
        // spawned 14 macapk-translator subprocesses after the swap and every
        // one of them came from the new build. So a guest that was already
        // running when a build landed is a MIXED build -- old runtime image,
        // new helper binaries -- and stays that way until it is relaunched.
        // That is what this log line is for: it makes such a run identifiable
        // afterwards instead of being a mystery in a diff.
        let liveGuests = runningGuests()
        if !liveGuests.isEmpty {
            log?("[runtime] note: \(liveGuests.count) app(s) running during staging ("
               + liveGuests.joined(separator: ", ")
               + "). They keep the build they started with -- and pick up NEW "
               + "helper binaries for anything they load by path -- until relaunched.")
        }

        // Swap. Move the old tree aside first so the window in which no
        // runtime exists is a rename, not a recursive delete.
        let aside = Paths.supportDir.appendingPathComponent(
            "runtime.old-\(getpid())", isDirectory: true)
        try? fm.removeItem(at: aside)
        if fm.fileExists(atPath: stagedRoot.path) {
            do { try fm.moveItem(at: stagedRoot, to: aside) }
            catch {
                try? fm.removeItem(at: staging)
                return .failed("could not replace the existing staged runtime: "
                             + error.localizedDescription)
            }
        }
        do { try fm.moveItem(at: staging, to: stagedRoot) }
        catch {
            // Put the old one back rather than leaving nothing.
            if fm.fileExists(atPath: aside.path) { try? fm.moveItem(at: aside, to: stagedRoot) }
            try? fm.removeItem(at: staging)
            return .failed("could not move the staged runtime into place: "
                         + error.localizedDescription)
        }
        try? fm.removeItem(at: aside)

        // Make the STAGED executables executable regardless of what the source
        // looked like. The chmod on the source above is best-effort -- it fails
        // on a locked volume or an MDM-managed /Applications -- and COPYFILE_ALL
        // faithfully carries a 0644 mode across, which would land a "successful"
        // install whose runtime cannot be exec'd: state() would then report Not
        // installed immediately after Install reported success, with nothing in
        // the log to explain it. Setting the mode on our own staged copy needs
        // no permission we do not have.
        for rel in executablePayload {
            let p = stagedRoot.appendingPathComponent(rel)
            guard fm.fileExists(atPath: p.path) else { continue }
            if chmod(p.path, 0o755) != 0 {
                log?("[runtime] WARN could not make \(rel) executable: "
                   + String(cString: strerror(errno)))
            }
        }
        // POSITIVE ASSERTION, not trust in the copy above.
        //
        // Everything this type exists to prevent is the shape "silently
        // succeeded but wrong": a fallback that quietly resolves the in-bundle
        // runtime, a `--patch-module` dir that main.cpp skips without a word.
        // The copy loop can only fail loudly, but the SET it iterates is
        // discovered, and a discovery bug would produce a staged runtime that
        // is missing a patch module and reports Installed. So verify the
        // landed tree against the source and refuse rather than report success.
        // The marker is deliberately still in place at this point, so a refusal
        // leaves the runtime uninstalled rather than half-installed.
        let after = state(extraSources: extraSources)
        if !after.missingPayloadDirs.isEmpty {
            return .failed("the runtime staged without "
                         + after.missingPayloadDirs.joined(separator: ", ")
                         + ", which would silently disable a --patch-module the "
                         + "control panel ships. Nothing was installed.")
        }
        if !after.stalePayloadFiles.isEmpty {
            return .failed("the runtime staged but "
                         + after.stalePayloadFiles.joined(separator: ", ")
                         + " does not match what Mac.apk.app ships. Nothing was installed.")
        }
        // The STAGED path explicitly, not resolveRuntime(): that honours
        // MACAPK_RUNTIME_ROOT, and an assertion about what we just wrote must
        // not be satisfiable by a different tree the caller pointed us at.
        guard after.installed else {
            return .failed("the runtime staged but \(stagedRoot.path)"
                         + "/Resources/macapk-runtime is not executable. "
                         + "Nothing was installed.")
        }
        try? fm.removeItem(at: removedMarkerURL)

        let id = buildId(at: stagedRoot) ?? "unknown"
        let dirs = Self.payloadDirs(in: src)
        let msg = "runtime installed at \(stagedRoot.path) (\(copied) items"
                + (dirs.isEmpty ? "" : ", patch modules: "
                   + dirs.map { ($0 as NSString).lastPathComponent }.joined(separator: " "))
                + ", build " + id.replacingOccurrences(of: "\n", with: " ") + ")"
        log?("[runtime] " + msg)
        // The stub template ships beside the payload and changes with it, so
        // the moment the shared runtime is (re)staged is the moment every
        // installed app's own stub copy must follow (v1.0.1592, StubRefresh).
        StubRefresh.refreshAll(template: StubRefresh.template(in: src), log: log)
        return .ok(msg)
    }

    // MARK: uninstall

    /// Remove the shared runtime. Touches NOTHING else: not the control panel,
    /// not any app bundle, not any app's data, not the registry.
    ///
    /// `neutralizeBundleCopy` clears the execute bit on the control panel's own
    /// payload. It defaults to true because without it this button does nothing
    /// observable for a bundle generated before the staged-path contract -- see
    /// the trap note on this type. It is a parameter only so a caller can stage
    /// A/B experiments; the product never passes false.
    @discardableResult
    public static func uninstall(extraSources: [URL] = [],
                                 neutralizeBundleCopy: Bool = true,
                                 log: ((String) -> Void)? = nil) -> Result {
        Paths.ensureDirectories()
        guard let lock = FileLock(path: lockPath), lock.lock(timeoutMs: 30_000) else {
            return .failed("another Mac.apk process is installing or removing the runtime")
        }
        defer { lock.unlock() }

        let fm = FileManager.default
        let freed = directoryBytes(stagedRoot)

        // Marker FIRST. If the removal below dies half-way, the next launch
        // must still refuse to self-heal -- the safe direction is "the user
        // asked for this off", never "quietly turn it back on".
        let stamp = ISO8601.format(Date()) ?? ""
        let note = """
        The Mac.apk runtime was uninstalled from the control panel on \(stamp).

        Android apps stay installed and keep their data, but cannot launch until
        the runtime is reinstalled (Mac.apk -> Runtime: Install). This file is
        what stops a launch from silently re-staging it; deleting the file by
        hand is equivalent to pressing Install.

        """
        do { try atomicWrite(Data(note.utf8), to: removedMarkerURL) }
        catch { return .failed("could not record the uninstall: \(error.localizedDescription)") }

        var problems: [String] = []
        if fm.fileExists(atPath: stagedRoot.path) {
            do { try fm.removeItem(at: stagedRoot) }
            catch { problems.append("staged runtime: \(error.localizedDescription)") }
        }
        // Sweep any interrupted stage/swap leftovers so "uninstalled" does not
        // still hold 45 MB under a name nobody recognises.
        if let kids = try? fm.contentsOfDirectory(at: Paths.supportDir,
                                                  includingPropertiesForKeys: nil) {
            for k in kids where k.lastPathComponent.hasPrefix("runtime.staging-")
                             || k.lastPathComponent.hasPrefix("runtime.old-") {
                try? fm.removeItem(at: k)
            }
        }

        var neutralized = 0
        if neutralizeBundleCopy, let src = sourceContents(extra: extraSources) {
            for rel in executablePayload {
                let p = src.appendingPathComponent(rel)
                guard fm.fileExists(atPath: p.path),
                      fm.isExecutableFile(atPath: p.path) else { continue }
                // 0o644, not removal: this is the payload Install stages from,
                // and deleting it would make reinstalling impossible without a
                // network -- an explicit non-goal.
                if chmod(p.path, 0o644) == 0 {
                    neutralized += 1
                } else {
                    problems.append("could not disable \(rel) inside Mac.apk.app ("
                                  + String(cString: strerror(errno))
                                  + "); apps installed before this build may still launch")
                }
            }
        }

        let freedStr = ByteCountFormatter.string(fromByteCount: freed, countStyle: .file)
        if problems.isEmpty {
            let msg = "runtime removed (\(freedStr) freed, \(neutralized) in-bundle "
                    + "binaries disabled). Apps stay installed."
            log?("[runtime] " + msg)
            return .ok(msg)
        }
        let msg = "runtime removed with problems: " + problems.joined(separator: "; ")
        log?("[runtime] " + msg)
        return .failed(msg)
    }

    // MARK: self-heal

    /// Make sure a usable, CURRENT runtime is staged, and return its binary.
    ///
    /// ⚠ ITS ONE CALLER IS THE PER-APP STUB (`AppStubMain.swift`), on every app
    /// launch. This docstring used to add "and at control-panel start", and that
    /// was FALSE: `MacApkLauncherApp.swift` calls `state()` to label the button
    /// and `install()` when the button is pressed, and never calls this at all.
    /// The wrong sentence mattered, because it made the staged runtime look
    /// covered when it was not -- `scripts/rebuild_and_run.sh` ends by `open`ing
    /// the control panel, so a reader concluded the build refreshed staging.
    /// Measured 2026-08-19: v1.0.998 was promoted at ~00:44, nothing launched a
    /// guest between 00:29 and 09:22, and the staged root served the previous
    /// build (v1.0.997, binary mtime 2026-08-18 21:54) for that whole window,
    /// while `debug/staleness.sh` reported the canonical app FRESH throughout.
    ///
    /// So this is now the SECOND line of defence, not the first.
    /// `scripts/rebuild_and_run.sh` stages eagerly at promote time by invoking
    /// `macapk-installhelper runtime-install` (the same `install()` below), and
    /// this stays because a build is not the only way a bundle's payload
    /// changes: `runtime/scripts/build.sh`'s `sync_payload_into` refreshes live
    /// bundles in place, and a machine can acquire a newer Mac.apk.app without
    /// this repo's build script running at all.
    ///
    /// It NEVER runs when the user pressed Uninstall. That is the one rule that
    /// makes the button mean anything.
    @discardableResult
    public static func ensureStaged(extraSources: [URL] = [],
                                    log: ((String) -> Void)? = nil) -> URL? {
        // An explicit A/B target is the caller's business: do not self-heal it,
        // do not consult the marker, do not stage over it. Short-circuiting
        // here rather than only in resolveRuntime() is what keeps the override
        // coherent -- otherwise a set MACAPK_RUNTIME_ROOT would still trigger a
        // full 45 MB stage of the DEFAULT root that nothing then executes.
        if let override = ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"],
           !override.isEmpty {
            let rt = resolveRuntime()
            log?("[runtime] MACAPK_RUNTIME_ROOT=\(override) -> "
               + (rt?.path ?? "no executable runtime there"))
            return rt
        }
        if FileManager.default.fileExists(atPath: removedMarkerURL.path) {
            log?("[runtime] not installed (removed by the user); not self-healing")
            return nil
        }
        // Before the early return: an ALREADY-staged tree is exactly the case
        // that needs healing, and it never reaches install() below.
        dequarantineStagedOnce(log: log)
        let st = state(extraSources: extraSources)
        if st.installed && !st.isStale { return resolveRuntime() }
        if !st.sourceAvailable {
            log?("[runtime] no payload to stage from (Mac.apk.app not found)")
            return resolveRuntime()
        }
        let why: String
        if !st.installed {
            why = "no staged runtime yet"
        } else if !st.stalePayloadFiles.isEmpty {
            why = "changed: " + st.stalePayloadFiles.prefix(4).joined(separator: ", ")
        } else if !st.missingPayloadDirs.isEmpty {
            why = "missing " + st.missingPayloadDirs.joined(separator: ", ")
        } else {
            why = "staged build \(st.stagedBuildId ?? "?") != shipped \(st.sourceBuildId ?? "?")"
        }
        log?("[runtime] staging: \(why)")
        let r = install(extraSources: extraSources, log: log)
        if case .failed(let m) = r { log?("[runtime] staging FAILED: \(m)") }
        // Again after staging: install() just re-copied the payload, xattrs
        // and all, and the marker written above now names the previous build.
        dequarantineStagedOnce(log: log)
        return resolveRuntime()
    }

    // MARK: running guests

    /// The identities (dirName, or package for pre-dirName bundles) of guest
    /// apps running right now.
    ///
    /// Source of truth is the per-app pidfile the stub writes into
    /// `Paths.runDir` before it execv's -- but we deliberately do NOT probe its
    /// flock. Taking even a momentary `LOCK_EX` there would make a stub that is
    /// starting at that instant see EWOULDBLOCK, conclude "already running",
    /// and activate a pid that is not the app. So read the pid and ask the
    /// kernel what that process actually IS: a recycled pid answers with some
    /// other binary's path and is correctly ignored.
    public static func runningGuests() -> [String] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: Paths.runDir,
                                                      includingPropertiesForKeys: nil,
                                                      options: [.skipsHiddenFiles])
        else { return [] }
        var live: [String] = []
        for f in files where f.pathExtension == "pid" {
            guard let text = try? String(contentsOf: f, encoding: .utf8),
                  let pid = pid_t(text.trimmingCharacters(in: .whitespacesAndNewlines))
            else { continue }
            var buf = [CChar](repeating: 0, count: 4096)
            guard proc_pidpath(pid, &buf, UInt32(buf.count)) > 0 else { continue }
            let exe = URL(fileURLWithPath: String(cString: buf)).lastPathComponent
            if exe == "macapk-runtime" || exe.hasPrefix("MacApkAppStub") {
                live.append(f.deletingPathExtension().lastPathComponent)
            }
        }
        return live.sorted()
    }

    // MARK: helpers

    /// Bytes held under `dir`. Used for the "X freed" line and the uninstall
    /// dialog's size figure; 0 when the directory does not exist.
    public static func directoryBytes(_ dir: URL) -> Int64 {
        let fm = FileManager.default
        guard fm.fileExists(atPath: dir.path) else { return 0 }
        guard let e = fm.enumerator(at: dir,
                                    includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
                                    options: []) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in e {
            let v = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            if v?.isRegularFile == true, let s = v?.fileSize { total += Int64(s) }
        }
        return total
    }
}

// MARK: - Per-app stub refresh

/// Keeps every installed app's launcher stub in step with the Mac.apk that
/// is installed.
///
/// WHY. Each generated `<Label>.app` carries its OWN copy of the stub
/// template (`Mac.apk.app/Contents/Resources/appstub/MacApkAppStub`),
/// renamed to the bundle's `CFBundleExecutable` and frozen at install time.
/// Updating Mac.apk refreshed the shared runtime (`RuntimeInstall.ensureStaged`
/// self-heals on every launch) but nothing refreshed that copy, so after an
/// update every installed app kept running the stub of the build it was
/// installed under -- until now only the developer script
/// `scripts/restage_app_stubs.sh` closed that gap, and a user has no such
/// script (v1.0.1592).
///
/// THREE callers, one mechanism:
///  * the stub itself, on every launch, for its OWN file (`AppStubMain`): if
///    it differs from the installed Mac.apk's template it swaps the template
///    in and re-executes, so the launch that discovers the update already
///    runs the new stub;
///  * `RuntimeInstall.install`, right after a successful stage -- the template
///    changes exactly when the runtime payload does, so this is the moment the
///    Install / Update button, and the self-heal, refresh every installed app;
///  * the control panel once per session (`refreshAllOnce`), for bundles
///    whose stub predates the self-check above (they cannot heal themselves).
///
/// HOW A SWAP IS DONE, and why it is safe for a RUNNING app: the template is
/// copied to a temp file in the same directory (a NEW inode -- Apple documents
/// that modifying signed code in place risks a kernel signature-cache kill,
/// developer.apple.com/documentation/security/updating-mac-software) and then
/// `rename(2)`d over the executable. A process already running keeps its old,
/// now-unlinked inode mapped; the next exec sees the new file; an interrupted
/// copy can never leave a half-written stub. No codesign anywhere, by the
/// SIGNING MODEL contract (BundleGenerator.swift): the template was ad-hoc
/// signed as a standalone file at build time and that embedded signature is
/// valid wherever its bytes are copied, while signing the bundle would seal
/// `Contents/android/` and break on the app's first write.
public enum StubRefresh {
    /// The stub template inside a Mac.apk `Contents` directory (the one
    /// `BundleGenerator` copies into every new bundle).
    public static func template(in contents: URL) -> URL {
        contents.appendingPathComponent("Resources/appstub/MacApkAppStub")
    }

    /// SHA-256 of a whole file, or nil when it cannot be read.
    public static func digest(of url: URL) -> String? {
        guard let data = try? Data(contentsOf: url, options: [.mappedIfSafe]) else { return nil }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// True when `executable` is not byte-identical to `template`; nil when
    /// either cannot be read (nothing to decide -- callers leave things alone).
    public static func isStale(executable: URL, template: URL) -> Bool? {
        let fm = FileManager.default
        guard let ta = try? fm.attributesOfItem(atPath: template.path),
              let ea = try? fm.attributesOfItem(atPath: executable.path),
              let ts = (ta[.size] as? NSNumber)?.int64Value,
              let es = (ea[.size] as? NSNumber)?.int64Value else { return nil }
        if ts != es { return true }
        guard let td = digest(of: template), let ed = digest(of: executable) else { return nil }
        return td != ed
    }

    public enum RefreshError: LocalizedError {
        case copy(String), swap(String)
        public var errorDescription: String? {
            switch self {
            case .copy(let m): return "copying the stub template failed: \(m)"
            case .swap(let m): return "swapping the stub in failed: \(m)"
            }
        }
    }

    /// Replace `executable` with a fresh copy of `template`: temp file beside
    /// it, then an atomic rename. Never writes into the existing file.
    public static func refresh(executable: URL, from template: URL) throws {
        let fm = FileManager.default
        let tmp = executable.deletingLastPathComponent()
            .appendingPathComponent(".stub-refresh.\(getpid()).\(UInt32.random(in: 0...UInt32.max))")
        try? fm.removeItem(at: tmp)
        do {
            try fm.copyItem(at: template, to: tmp)
        } catch {
            try? fm.removeItem(at: tmp)
            throw RefreshError.copy(error.localizedDescription)
        }
        // The template is executable; copyItem keeps the mode, but say so
        // explicitly -- a stub that is not executable is a bundle that will
        // not launch, and a chmod costs nothing.
        chmod(tmp.path, 0o755)
        if rename(tmp.path, executable.path) != 0 {
            let e = String(cString: strerror(errno))
            try? fm.removeItem(at: tmp)
            throw RefreshError.swap(e)
        }
    }

    public struct Summary {
        public var refreshed: [String] = []
        public var current = 0
        public var failed: [String] = []
        public var skipped: [String] = []
        public var line: String {
            var parts = ["\(refreshed.count) refreshed", "\(current) current"]
            if !failed.isEmpty { parts.append("\(failed.count) failed") }
            if !skipped.isEmpty { parts.append("\(skipped.count) skipped") }
            return parts.joined(separator: ", ")
        }
    }

    /// The stub executable of every installed bundle the registry knows,
    /// as (label, executable URL). The bundle's own `CFBundleExecutable` is
    /// authoritative for the name -- `BundleGenerator.finalize` may rename it
    /// away from the label -- and `PropertyListSerialization` parses the
    /// plist the way macOS does (a strict XML parser once skipped five
    /// bundles here over a comment in the file; see the catalog entry of
    /// 2026-09-19).
    static func installedStubs() -> [(label: String, executable: URL)] {
        guard let reg = try? RegistryStore.shared.loadReadOnly() else { return [] }
        let fm = FileManager.default
        var out: [(String, URL)] = []
        for a in reg.apps {
            guard a.state == AppState.installed || a.state == "ok",
                  let b = a.bundlePath, fm.fileExists(atPath: b) else { continue }
            let contents = URL(fileURLWithPath: b).appendingPathComponent("Contents", isDirectory: true)
            guard let data = try? Data(contentsOf: contents.appendingPathComponent("Info.plist")),
                  let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil))
                      as? [String: Any],
                  let exe = plist["CFBundleExecutable"] as? String, !exe.isEmpty else { continue }
            out.append((a.label, contents.appendingPathComponent("MacOS/\(exe)")))
        }
        return out
    }

    /// Bring every installed bundle's stub up to `template`. Best effort per
    /// bundle; a bundle that cannot be refreshed is reported, never fatal.
    @discardableResult
    public static func refreshAll(template: URL, log: ((String) -> Void)? = nil) -> Summary {
        var sum = Summary()
        guard FileManager.default.fileExists(atPath: template.path) else {
            log?("[stubs] no stub template at \(template.path); nothing refreshed")
            return sum
        }
        for (label, exe) in installedStubs() {
            guard let stale = isStale(executable: exe, template: template) else {
                sum.skipped.append(label)
                log?("[stubs] \(label): stub unreadable at \(exe.path) (reinstall it)")
                continue
            }
            if !stale { sum.current += 1; continue }
            do {
                try refresh(executable: exe, from: template)
                sum.refreshed.append(label)
                log?("[stubs] \(label): stub refreshed from the installed Mac.apk")
            } catch {
                sum.failed.append(label)
                log?("[stubs] \(label): \(error.localizedDescription)")
            }
        }
        if !sum.refreshed.isEmpty || !sum.failed.isEmpty {
            log?("[stubs] " + sum.line)
        }
        return sum
    }

    private static var sweptThisProcess = false
    private static let sweepLock = NSLock()

    /// `refreshAll` against the installed Mac.apk, once per process. What the
    /// control panel runs at startup so a bundle generated before the stub
    /// learned to refresh itself is brought current the first time Mac.apk is
    /// opened after an update.
    public static func refreshAllOnce(extraSources: [URL] = [],
                                      log: ((String) -> Void)? = nil) {
        sweepLock.lock()
        let done = sweptThisProcess; sweptThisProcess = true
        sweepLock.unlock()
        if done { return }
        guard let src = RuntimeInstall.sourceContents(extra: extraSources) else { return }
        refreshAll(template: template(in: src), log: log)
    }
}

// MARK: - Special app access store (Android app-ops 92 / 110, stage S1)

/// The record of the user's answer to Android's "All files access" and "Media
/// management" switches -- app-ops MANAGE_EXTERNAL_STORAGE (92) and
/// MANAGE_MEDIA (110). Stage S1 of
/// docs/shared-storage-real-mac-folders-design_2026-09-25.md.
///
/// WHERE: `Paths.supportDir/appops/app-ops.json`, beside the helper's signing
/// key. A file of its own, NOT an `AppRecord` field: `AppRecord` does not keep
/// fields it does not know, so any stale stub running `touchLastLaunched`
/// would silently delete a grant stored there.
///
/// WHO WRITES: `macapk-installhelper special-access` (after the user answers a
/// CFUserNotification), the control panel's revoke, and uninstall (which only
/// removes entries). Nobody else, and there is no env override on this side:
/// a guest can influence a child's environment, so every writer uses the one
/// real location. The runtime only READS it
/// (runtime/java/com/macapk/loader/SpecialAccessStore.java).
///
/// NOT WRITABLE BY GUEST NATIVE CODE (the critique's P0 #1): the runtime's
/// native shim denies guest code the whole `appops/` directory by every
/// spelling APFS folds to it (bionic_shim.cpp macapk_store_guard /
/// macapk_fold_may_match). Every entry also carries an ECDSA P-256 signature
/// over `canonicalPayload`, made with a private key that only this code
/// (helper, control panel) reads, and the runtime verifies with the public key
/// -- but that public key sits in the same directory, so the signature is NOT a
/// second wall: anything that can write `appops/` (guest JAVA file I/O, an
/// inline `svc #0`, a symlink detour; same macOS user, no sandbox) can replace
/// the key and sign its own entry. What the signature buys is binding (an
/// entry cannot be edited to name another app or install) and rejection of a
/// copied or hand-edited file. This closes every Android-API route and a plain
/// native open/fopen/mkdir; it is not a same-user host boundary.
///
/// KEY + VALIDITY: an entry is keyed and signed by package + installedAt, the
/// registry row's -- AOSP keeps app-ops per package (uid), and they survive an
/// update. NOT by dirName: that is `<package>_<version>`, and every in-place
/// update changes it while keeping installedAt, so a dirName key lost the
/// grant on every update and orphaned the old entry (review, 2026-09-26). A
/// reinstall gets a new installedAt and so starts with no grant, even when an
/// older build did the uninstall. Two side-by-side versions of one package
/// that share installedAt (an update installed beside the old bundle) share
/// one grant, as one package does on a device.
public enum AppOpsStore {
    public static let manageExternalStorage = "android:manage_external_storage"
    public static let manageMedia = "android:manage_media"
    public static let knownOps: Set<String> = [manageExternalStorage, manageMedia]

    /// Bump on BOTH sides (here and SpecialAccessStore.PAYLOAD_TAG) if the
    /// signed field set changes. /2 (2026-09-26): dirName left the payload and
    /// the key; a /1 entry fails to verify and is dropped on the next write.
    /// /3 (2026-09-26, stage S5): the entry's "perms" object -- the storage
    /// runtime-permission states macapk-installhelper's runtime-permission /
    /// app-details verbs record -- is signed too, as `perm:<name>=<state>`
    /// lines after the op lines (docs/storage-runtime-permissions_2026-09-26.md).
    static let payloadTag = "macapk-appops/3"
    /// The S1 tag, still accepted by `verify` for an entry with NO "perms"
    /// object: its /3 payload is the /2 payload with only the tag line
    /// changed, so a /2 signature covers exactly what it covered before and
    /// grants made before S5 survive; the next write re-signs the entry as /3.
    /// An entry that carries "perms" must verify as /3. MUST match
    /// SpecialAccessStore.LEGACY_PAYLOAD_TAG (Java).
    static let legacyPayloadTag = "macapk-appops/2"
    /// The file's "version": entries of any other version are never read and
    /// are dropped by the next write.
    static let storeVersion = 2

    public enum Mode: String { case allow, deny }

    /// Who an entry belongs to: the registry row's identity.
    public struct Identity: Equatable {
        /// Which row / run pidfile this is (for stopping a running app). NOT
        /// part of the key or the signature -- see the type's header.
        public let dirName: String
        public let package: String
        /// `ISO8601.format(installedAt)` -- whole seconds, UTC, "Z" -- or "".
        public let installedAt: String
        public init(dirName: String, package: String, installedAt: String) {
            self.dirName = dirName; self.package = package; self.installedAt = installedAt
        }
        /// The store key. MUST match SpecialAccessStore.storeKey (Java). '@'
        /// cannot occur in a package name.
        public var key: String { "\(package)@\(installedAt)" }
        public init(record: AppRecord) {
            self.init(dirName: record.dirName, package: record.package,
                      installedAt: ISO8601.format(record.installedAt) ?? "")
        }
    }

    public static var defaultDir: URL {
        Paths.supportDir.appendingPathComponent("appops", isDirectory: true)
    }
    static func storeURL(_ d: URL) -> URL { d.appendingPathComponent("app-ops.json") }
    static func keyURL(_ d: URL) -> URL { d.appendingPathComponent("signing-key.p256") }
    static func pubURL(_ d: URL) -> URL { d.appendingPathComponent("signing-key.pub") }
    static func lockURL(_ d: URL) -> URL { d.appendingPathComponent("app-ops.json.lock") }

    enum StoreError: Error, LocalizedError {
        case badField(String), io(String)
        var errorDescription: String? {
            switch self {
            case .badField(let s): return "refusing to sign a malformed field: \(s)"
            case .io(let s): return s
            }
        }
    }

    /// The bytes that are signed. MUST match SpecialAccessStore.canonicalPayload
    /// (Java) byte for byte: UTF-8, '\n'-terminated lines, ops sorted by name,
    /// then the runtime-permission states (/3) sorted by name.
    static func canonicalPayload(package: String, installedAt: String,
                                 ops: [String: String], perms: [String: String] = [:],
                                 updatedAt: String, by: String,
                                 tag: String = payloadTag) -> Data? {
        for f in [package, installedAt, updatedAt, by] where f.contains("\n") {
            return nil
        }
        var s = tag + "\n"
        s += "package=\(package)\n"
        s += "installedAt=\(installedAt)\n"
        for k in ops.keys.sorted() {
            let v = ops[k] ?? ""
            if k.contains("\n") || v.contains("\n") { return nil }
            s += "op:\(k)=\(v)\n"
        }
        for k in perms.keys.sorted() {
            let v = perms[k] ?? ""
            if k.contains("\n") || v.contains("\n") { return nil }
            s += "perm:\(k)=\(v)\n"
        }
        s += "updatedAt=\(updatedAt)\n"
        s += "by=\(by)\n"
        return s.data(using: .utf8)
    }

    // ---- key ---------------------------------------------------------------

    /// The helper's public key (base64 SubjectPublicKeyInfo), or nil.
    static func loadPublicKey(in d: URL) -> P256.Signing.PublicKey? {
        guard let text = try? String(contentsOf: pubURL(d), encoding: .ascii),
              let der = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines))
        else { return nil }
        return try? P256.Signing.PublicKey(derRepresentation: der)
    }

    /// Load the signing key, creating it (and the directory, 0700) on first use.
    /// A new key makes every existing entry unverifiable, so they are dropped
    /// rather than left to be rejected one by one. Caller holds the lock.
    static func loadOrCreateKey(in d: URL) throws -> P256.Signing.PrivateKey {
        let fm = FileManager.default
        try fm.createDirectory(at: d, withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        _ = chmod(d.path, 0o700)
        if let raw = try? Data(contentsOf: keyURL(d)),
           let key = try? P256.Signing.PrivateKey(rawRepresentation: raw) {
            let want = key.publicKey.derRepresentation.base64EncodedString()
            let have = (try? String(contentsOf: pubURL(d), encoding: .ascii))?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if have != want {
                try writeFile(Data((want + "\n").utf8), to: pubURL(d), mode: 0o644)
            }
            return key
        }
        let key = P256.Signing.PrivateKey()
        try writeFile(key.rawRepresentation, to: keyURL(d), mode: 0o600)
        try writeFile(Data((key.publicKey.derRepresentation.base64EncodedString() + "\n").utf8),
                      to: pubURL(d), mode: 0o644)
        if fm.fileExists(atPath: storeURL(d).path) {
            try writeStore(["version": storeVersion, "apps": [String: Any]()], in: d)
        }
        return key
    }

    /// temp + fsync + rename, with the final mode set on the temp BEFORE the
    /// rename, so the key is never world-readable even for an instant.
    static func writeFile(_ data: Data, to url: URL, mode: mode_t) throws {
        let tmp = url.deletingLastPathComponent()
            .appendingPathComponent(".\(url.lastPathComponent).tmp.\(getpid())")
        unlink(tmp.path)
        let fd = open(tmp.path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode)
        guard fd >= 0 else {
            throw StoreError.io("cannot create \(tmp.path): \(String(cString: strerror(errno)))")
        }
        var ok = fchmod(fd, mode) == 0
        data.withUnsafeBytes { buf in
            var off = 0
            while ok && off < buf.count {
                let n = write(fd, buf.baseAddress! + off, buf.count - off)
                if n <= 0 { ok = false } else { off += n }
            }
        }
        if ok { ok = fsync(fd) == 0 }
        close(fd)
        guard ok, rename(tmp.path, url.path) == 0 else {
            unlink(tmp.path)
            throw StoreError.io("cannot write \(url.path): \(String(cString: strerror(errno)))")
        }
    }

    // ---- read ----------------------------------------------------------------

    static func readStore(in d: URL) -> [String: Any] {
        guard let data = try? Data(contentsOf: storeURL(d)),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return ["version": storeVersion, "apps": [String: Any]()] }
        return obj
    }

    /// The current-format entries of a store, keyed by `Identity.key`; an
    /// older-format file reads as empty (none of its entries could verify).
    static func currentApps(_ root: [String: Any]) -> [String: Any] {
        guard (root["version"] as? Int) == storeVersion else { return [:] }
        return root["apps"] as? [String: Any] ?? [:]
    }

    static func writeStore(_ obj: [String: Any], in d: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: obj,
                                              options: [.prettyPrinted, .sortedKeys])
        try writeFile(data, to: storeURL(d), mode: 0o600)
    }

    /// True when `entry` is genuine (signature) and belongs to `id`.
    static func verify(_ entry: [String: Any], id: Identity,
                       key: P256.Signing.PublicKey) -> Bool {
        guard (entry["package"] as? String) == id.package,
              (entry["installedAt"] as? String) == id.installedAt,
              let sigB64 = entry["sig"] as? String,
              let sigDer = Data(base64Encoded: sigB64),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: sigDer)
        else { return false }
        let ops = (entry["ops"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        let perms = (entry["perms"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        let updatedAt = entry["updatedAt"] as? String ?? "", by = entry["by"] as? String ?? ""
        guard let payload = canonicalPayload(
                package: id.package, installedAt: id.installedAt,
                ops: ops, perms: perms, updatedAt: updatedAt, by: by)
        else { return false }
        if key.isValidSignature(sig, for: payload) { return true }
        // An S1 (/2) entry: ops only, no "perms" key at all.
        guard entry["perms"] == nil,
              let legacy = canonicalPayload(package: id.package, installedAt: id.installedAt,
                                            ops: ops, updatedAt: updatedAt, by: by,
                                            tag: legacyPayloadTag)
        else { return false }
        return key.isValidSignature(sig, for: legacy)
    }

    /// The VERIFIED mode stored for (id, op), or nil (nothing stored, or an
    /// entry that fails verification -- which reads the same as nothing, as the
    /// runtime reads it).
    public static func mode(for id: Identity, op: String, in d: URL = defaultDir) -> Mode? {
        guard let key = loadPublicKey(in: d) else { return nil }
        let apps = currentApps(readStore(in: d))
        guard let entry = apps[id.key] as? [String: Any], verify(entry, id: id, key: key),
              let raw = (entry["ops"] as? [String: Any])?[op] as? String
        else { return nil }
        return Mode(rawValue: raw)
    }

    // ---- write ---------------------------------------------------------------

    /// Record `mode` for (id, op), signed. Other ops of a still-valid entry are
    /// kept; an entry that no longer verifies for `id` (a previous install's)
    /// is replaced wholesale. Serialised by a sidecar flock across processes.
    public static func setMode(_ mode: Mode, op: String, for id: Identity, by: String,
                               in d: URL = defaultDir) throws {
        guard knownOps.contains(op) else { throw StoreError.badField("op \(op)") }
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        guard let lock = FileLock(path: lockURL(d).path), lock.lock(timeoutMs: 10_000) else {
            throw StoreError.io("could not lock \(lockURL(d).path)")
        }
        defer { lock.unlock() }
        let key = try loadOrCreateKey(in: d)
        var root = readStore(in: d)
        var apps = currentApps(root)
        var entry: [String: Any] = [:]
        if let old = apps[id.key] as? [String: Any],
           verify(old, id: id, key: key.publicKey) {
            entry = old
        }
        var ops = (entry["ops"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        ops[op] = mode.rawValue
        // Stage S5: a still-valid entry's runtime-permission states are kept
        // (entry = old above) and must be signed with the op change.
        let perms = (entry["perms"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        let updatedAt = ISO8601.format(Date()) ?? ""
        guard let payload = canonicalPayload(package: id.package,
                                             installedAt: id.installedAt, ops: ops,
                                             perms: perms, updatedAt: updatedAt, by: by)
        else { throw StoreError.badField("identity \(id.key)") }
        let sig = try key.signature(for: payload)
        entry["package"] = id.package
        entry["installedAt"] = id.installedAt
        entry["ops"] = ops
        entry["updatedAt"] = updatedAt
        entry["by"] = by
        entry["sig"] = sig.derRepresentation.base64EncodedString()
        apps[id.key] = entry
        root["apps"] = apps
        root["version"] = storeVersion
        try writeStore(root, in: d)
    }

    /// Uninstall: drop the removed rows' entries (AOSP clears an uninstalled
    /// package's app-ops) -- except an entry a SURVIVING row still holds: two
    /// side-by-side versions of one package can share package + installedAt,
    /// and removing one must not revoke the other, as removing nothing of a
    /// still-installed package revokes nothing on a device. Removing needs no
    /// signature. Best-effort and silent when there is no store.
    public static func removeEntries(_ removed: [Identity], keeping survivors: [Identity],
                                     in d: URL = defaultDir) {
        let keys = Set(removed.filter { !$0.package.isEmpty }.map(\.key))
            .subtracting(survivors.map(\.key))
        guard !keys.isEmpty,
              FileManager.default.fileExists(atPath: storeURL(d).path),
              let lock = FileLock(path: lockURL(d).path), lock.lock(timeoutMs: 2_000)
        else { return }
        defer { lock.unlock() }
        var root = readStore(in: d)
        guard (root["version"] as? Int) == storeVersion,
              var apps = root["apps"] as? [String: Any] else { return }
        let before = apps.count
        for k in keys { apps.removeValue(forKey: k) }
        guard apps.count != before else { return }
        root["apps"] = apps
        try? writeStore(root, in: d)
    }

    /// The control panel's revoke: record "deny" and, as AOSP's
    /// StorageManagerService.onAppOpsChanged does for op 92 turning
    /// not-ALLOWED (killAppForOpChange, sms14 :4838-4845), end the app if it is
    /// running -- every installed version sharing this grant (`alsoDirNames`:
    /// the other rows with the same package + installedAt), since the grant
    /// was theirs too. Returns true when a running process was signalled.
    @discardableResult
    public static func revoke(op: String, id: Identity, alsoDirNames: [String] = [],
                              in d: URL = defaultDir) throws -> Bool {
        let was = mode(for: id, op: op, in: d)
        try setMode(.deny, op: op, for: id, by: "panel", in: d)
        guard op == manageExternalStorage, was == .allow else { return false }
        var stopped = killRunning(dirName: id.dirName, package: id.package)
        for other in Set(alsoDirNames) where !other.isEmpty && other != id.dirName {
            if killRunning(dirName: other, package: "") { stopped = true }
        }
        return stopped
    }

    /// The ops recorded ALLOW for `id`, verified -- for the control panel's
    /// menu, which asks per tile on every render. Cached on the store's and the
    /// key's (mtime, size), so a render costs two stats, not a signature check
    /// per tile.
    public static func allowedOps(for id: Identity, in d: URL = defaultDir) -> Set<String> {
        let entries = verifiedEntries(in: d)
        guard let e = entries[id.key], e.package == id.package,
              e.installedAt == id.installedAt else { return [] }
        return e.allowed
    }

    struct VerifiedEntry { let package: String; let installedAt: String; let allowed: Set<String> }
    private static var cacheKey: [Int64] = []
    private static var cache: [String: VerifiedEntry] = [:]
    private static let cacheLock = NSLock()

    static func verifiedEntries(in d: URL) -> [String: VerifiedEntry] {
        func stamp(_ u: URL) -> [Int64] {
            var st = Darwin.stat()
            guard stat(u.path, &st) == 0 else { return [0, 0, 0] }
            return [Int64(st.st_mtimespec.tv_sec), Int64(st.st_mtimespec.tv_nsec), Int64(st.st_size)]
        }
        let key = stamp(storeURL(d)) + stamp(pubURL(d)) + [Int64(d.path.hashValue)]
        cacheLock.lock(); defer { cacheLock.unlock() }
        if key == cacheKey { return cache }
        var out: [String: VerifiedEntry] = [:]
        if let pub = loadPublicKey(in: d) {
            for (k, v) in currentApps(readStore(in: d)) {
                guard let e = v as? [String: Any],
                      let pkg = e["package"] as? String,
                      let inst = e["installedAt"] as? String else { continue }
                let id = Identity(dirName: "", package: pkg, installedAt: inst)
                guard k == id.key, verify(e, id: id, key: pub) else { continue }
                let ops = (e["ops"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
                out[k] = VerifiedEntry(package: pkg, installedAt: inst,
                    allowed: Set(ops.filter { $0.value == Mode.allow.rawValue }.keys))
            }
        }
        cacheKey = key
        cache = out
        return out
    }

    /// SIGKILL the app's runtime, the way killUid does -- but only a process we
    /// can positively identify: its run/<dirName>.pid (or the pre-dirName
    /// package spelling) must be LOCKED (the stub's flock is the aliveness
    /// signal) and name a live macapk-runtime, so a recycled pid is never hit.
    static func killRunning(dirName: String, package: String) -> Bool {
        for name in [dirName, package] where !name.isEmpty {
            let path = Paths.runDir.appendingPathComponent("\(name).pid").path
            let fd = open(path, O_RDONLY | O_CLOEXEC)
            guard fd >= 0 else { continue }
            let free = flock(fd, LOCK_SH | LOCK_NB) == 0
            if free { _ = flock(fd, LOCK_UN) }
            close(fd)
            if free { continue }
            guard let text = try? String(contentsOfFile: path, encoding: .utf8),
                  let pid = pid_t(text.trimmingCharacters(in: .whitespacesAndNewlines)),
                  pid > 1 else { continue }
            var buf = [CChar](repeating: 0, count: 4096)
            guard proc_pidpath(pid, &buf, UInt32(buf.count)) > 0,
                  URL(fileURLWithPath: String(cString: buf)).lastPathComponent == "macapk-runtime"
            else { continue }
            if kill(pid, SIGKILL) == 0 { return true }
        }
        return false
    }
}

/// The TCC purpose strings (Info.plist usage descriptions) every generated
/// bundle must carry. macOS refuses a protected capability OUTRIGHT, without
/// prompting, when the requesting bundle has no usage description for it, so a
/// missing key is invisible until a user tries the feature (Aegis' QR scanner,
/// 2026-09-29). BundleGenerator.infoPlistXML writes one line per key;
/// debug/purposestrings_gate.sh fails if the two lists ever disagree.
public enum PurposeStrings {
    public static let keys: [String] = [
        "NSCameraUsageDescription",
        "NSMicrophoneUsageDescription",
        "NSLocationWhenInUseUsageDescription",
        "NSBluetoothAlwaysUsageDescription",
        "NSLocalNetworkUsageDescription",
        "NSPhotoLibraryUsageDescription",
        "NSSpeechRecognitionUsageDescription",
    ]

    static func plist(_ bundle: URL) -> [String: Any]? {
        let pl = bundle.appendingPathComponent("Contents/Info.plist")
        guard let d = try? Data(contentsOf: pl) else { return nil }
        return (try? PropertyListSerialization.propertyList(from: d, format: nil)) as? [String: Any]
    }

    /// MACAPK_PURPOSE_REFRESH (default ON; =0 is the old path, where nothing
    /// re-stamped an installed bundle's Info.plist and reconcile reported no
    /// stalePurposeStrings finding).
    public static var refreshEnabled: Bool {
        ProcessInfo.processInfo.environment["MACAPK_PURPOSE_REFRESH"] != "0"
    }

    /// The purpose-string keys this bundle's Info.plist lacks; nil when it is
    /// not one of OUR generated bundles (no MacapkPackage) or is unreadable,
    /// so a foreign .app is never touched.
    public static func missing(inBundle bundle: URL) -> [String]? {
        guard let d = plist(bundle), d["MacapkPackage"] is String else { return nil }
        return keys.filter { d[$0] == nil }
    }

    static func bundleIdentifier(inBundle bundle: URL) -> String? {
        plist(bundle)?["CFBundleIdentifier"] as? String
    }
}
