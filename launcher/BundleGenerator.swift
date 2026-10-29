// BundleGenerator.swift
//
// Creates and removes the per-app <Label>.app bundles that make installed
// Android apps first-class macOS citizens (Dock, Spotlight, Finder
// uninstall). Compiled into the LAUNCHER target; consumed by the install
// pipeline (InstallCore.swift, which also provides `Paths`).
//
// Bundle contract (per-app bundle layout):
//   <Paths.bundlesDir>/<Label>.app/Contents/   (/Applications, or
//                           ~/Applications for a user who cannot write it)
//     Info.plist            identity (bundle id org.macapk.app.<pkgSanitized>)
//     PkgInfo               "APPL????"
//     MacOS/<Label>         the stub (copy of the pre-signed template)
//     Resources/AppIcon.icns, AppIcon.png
//                           (NO retained APK since v1.0.1585 -- the one copy
//                           lives in android/data/app below, owner rule)
//     android/              passed to the runtime as --android-root
//       data/app/~~<nonce>/<pkg>-<nonce>/base.apk (+ split_*.apk)
//                           the INSTALLED package, Android's own layout: the
//                           runtime lays it down from the dropped file at
//                           extraction (apk_unpacker.cpp
//                           EnsureInstalledPackage), the stub launches it
//       data/data/<pkg>_<ver>/  app data. The RUNTIME creates and names
//                           this (main.cpp SetupAndroidRoot versions the
//                           dir); the generator does NOT pre-create a bare
//                           data/data/<pkg>/ -- it used to, describing an
//                           unversioned layout the runtime never had, and
//                           every bundle carried one empty ghost dir
//                           (audit S10).
//       .macapk/code/       regenerable versioned caches
//
// SIGNING MODEL (T1 spike, 2026-08-10; honesty pass 2026-08-11): generated
// bundles are NEVER codesigned. The stub template was ad-hoc signed at
// build time as a standalone file (before entering any bundle), and that
// embedded signature stays valid wherever the file is copied. Running
// codesign on the bundle -- or on the executable INSIDE the bundle, which
// codesign silently widens to the enclosing bundle -- would create a
// resource seal over Contents/, and the app data the runtime writes under
// Contents/android/ would break that seal forever after.
//
// ⚠ KNOWN, ACCEPTED CONSEQUENCE (audit S10): `codesign --verify` on a
// generated bundle FAILS (rc=1, "code has no resources but signature
// indicates they must be present") -- the stub's own embedded signature
// declares a resource-seal requirement that the unsealed bundle cannot
// satisfy. Launch works because the kernel validates only the executable's
// own embedded signature, and bundles are created locally, never
// quarantined, so Gatekeeper assessment does not run. This is a LATENT
// risk, not a proven-safe state: any future macOS tightening that assesses
// resident bundles (Gatekeeper/TCC attribution) would reject these. If
// that lands, the fix direction is signing the bundle AFTER generation
// with Contents/android/ excluded from the seal (resource rules), not
// un-signing the stub.
//
// UPDATE MODEL: regeneration replaces every generated artifact by
// building a fresh staging bundle and swapping directories (new inodes
// throughout -- Apple documents that modifying signed code in place risks
// a kernel signature-cache kill; developer.apple.com/documentation/
// security/updating-mac-software). Contents/android/ is carried over by
// a single directory rename and is never deleted on update.

import AppKit

// MARK: - Public API (contract with the install pipeline)

struct BundleSpec {
    let package: String
    let label: String
    let versionName: String
    let versionCode: Int64
    let iconPNG: URL?
    let apkToRetain: URL
}

enum BundleGeneratorError: LocalizedError {
    case stubTemplateMissing(String)
    case apkMissing(String)
    case bundlesDirUnavailable(String)
    case tooManyLabelCollisions(String)
    case stagingFailed(String)
    case swapFailed(String)
    case removeRefused(String)

    var errorDescription: String? {
        switch self {
        case .stubTemplateMissing(let s): return "App stub template missing: \(s)"
        case .apkMissing(let s):          return "APK to retain not found: \(s)"
        case .bundlesDirUnavailable(let s): return "Bundle directory unavailable: \(s)"
        case .tooManyLabelCollisions(let s): return "Could not find a free bundle name for: \(s)"
        case .stagingFailed(let s):       return "Building the app bundle failed: \(s)"
        case .swapFailed(let s):          return "Installing the app bundle failed: \(s)"
        case .removeRefused(let s):       return "Refusing to remove: \(s)"
        }
    }
}

enum BundleGenerator {

    /// Create (or regenerate in place) the .app bundle for `spec`.
    /// Returns the bundle URL. Regeneration preserves Contents/android/.
    ///
    /// `dirName` is the app's REGISTRY ROW IDENTITY (the cache-dir name,
    /// e.g. "org.fdroid.fdroid_1.21.1") and gets embedded as MacapkDirName
    /// so the stub can record lastLaunchedAt against the right row. It is
    /// a separate, defaulted parameter rather than a BundleSpec field
    /// because the BundleSpec shape is pinned by the lane contract; the
    /// install pipeline should always pass it (the registry is keyed by
    /// dirName, not package -- packages are ambiguous, e.g. three hillclimb
    /// rows on the live machine). nil produces a bundle that launches fine
    /// but never records a launch.
    static func generate(_ spec: BundleSpec, dirName: String? = nil) throws -> URL {
        let fm = FileManager.default
        guard fm.fileExists(atPath: spec.apkToRetain.path) else {
            throw BundleGeneratorError.apkMissing(spec.apkToRetain.path)
        }
        let template = try stubTemplateURL()

        let bundlesDir = homeFolder(for: spec, dirName: dirName)
        var isDir: ObjCBool = false
        if !(fm.fileExists(atPath: bundlesDir.path, isDirectory: &isDir) && isDir.boolValue) {
            throw BundleGeneratorError.bundlesDirUnavailable(bundlesDir.path)
        }

        let name = try resolveBundleName(for: spec, dirName: dirName, in: bundlesDir)
        let finalURL = bundlesDir.appendingPathComponent("\(name).app", isDirectory: true)
        recoverInterruptedSwap(name: name, finalURL: finalURL, in: bundlesDir)
        genLog("bundle folder: \(bundlesDir.path)")

        // Hidden same-directory staging sibling: same volume, so every
        // swap below is a rename, and Finder never shows the dot-dir.
        let staging = bundlesDir.appendingPathComponent(".\(name).app.staging", isDirectory: true)
        let previous = bundlesDir.appendingPathComponent(".\(name).app.previous", isDirectory: true)
        try? fm.removeItem(at: staging)

        do {
            try buildStagingBundle(spec: spec, name: name, dirName: dirName,
                                   template: template, at: staging, finalURL: finalURL)
        } catch {
            try? fm.removeItem(at: staging)
            throw BundleGeneratorError.stagingFailed(String(describing: error))
        }

        // Swap: park the old bundle (app data rides inside it), promote the
        // staging bundle, then carry the data directory over with one
        // rename. recoverInterruptedSwap() above heals a crash between any
        // two of these steps on the next generate() for the same name.
        //
        // ⚠ A `.previous` STILL EXISTING here means recoverInterruptedSwap()
        // just FAILED to adopt its app data (it logs why, and it keeps the
        // parked bundle "for the next attempt"). This used to run
        // `try? fm.removeItem(at: previous)` at the top of the swap -- which
        // deleted the user's only copy of those saves in the very same call
        // that promised to retry (audit S3). Abort instead: the saves stay
        // parked, the cause named in the log gets fixed, and the next
        // generate() retries the adoption.
        if fm.fileExists(atPath: previous.path) {
            try? fm.removeItem(at: staging)
            throw BundleGeneratorError.swapFailed(
                "a previous interrupted update left app data at \(previous.path) "
                + "that could not be adopted into \(finalURL.lastPathComponent) "
                + "(see the log above); refusing to overwrite it")
        }
        do {
            if fm.fileExists(atPath: finalURL.path) {
                try fm.moveItem(at: finalURL, to: previous)
            }
            try fm.moveItem(at: staging, to: finalURL)
            if fm.fileExists(atPath: previous.path) {
                // Only delete the parked bundle once its data is provably
                // in the new one. adoptAndroidDir throws on failure, which
                // skips the removeItem below and leaves the saves in
                // `previous` for recoverInterruptedSwap() to pick up.
                try adoptAndroidDir(from: previous, into: finalURL)
                try fm.removeItem(at: previous)
            }
        } catch {
            throw BundleGeneratorError.swapFailed(
                "\(error) -- app data, if any, is preserved under "
                + "\(previous.path) or \(staging.path)")
        }
        // The app's data lives outside the bundle (AppDataLayout): a fresh
        // bundle's skeleton, or a legacy tree just carried over, moves to its
        // folder in ~/Library/Application Support/MacApk here, under the install
        // lock and before extraction writes anything. Never fatal: on failure
        // the tree stays inside the bundle, which still launches.
        AppDataLayout.ensure(bundle: finalURL, package: spec.package,
                             log: { genLog($0); InstallLog.write("[appdata] " + $0) })

        // Deliberately NOT registered with LaunchServices here (audit S7).
        // generate() runs BEFORE the minutes-long extraction, under an
        // interim name and icon whenever the label was a resource reference
        // -- registering that would put the wrong identity in Spotlight for
        // the whole extraction, and forever if the extraction failed. Both
        // install paths call finalizeIdentity() after extraction, and THAT
        // is the registration point; a failed install removes the fresh
        // bundle instead (InstallPipeline.removeFreshBundleAfterFailedInstall
        // and the launcher's equivalent), and remove() lsregister--u's, which
        // is harmless on a never-registered path.
        try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: finalURL.path)
        genLog("generated \(finalURL.path) for \(spec.package) "
               + "(versionCode \(spec.versionCode))")
        // The bundle is unsealed BY DESIGN, so it must not also be
        // quarantined or Gatekeeper calls it damaged. See Quarantine.clear.
        Quarantine.clear(at: finalURL)
        return finalURL
    }

    /// Stamp a bundle's REAL identity on, after extraction has revealed it.
    ///
    /// The bundle must be created BEFORE extraction, because extraction targets
    /// it -- and at that moment all we have is `--peek-manifest`, which cannot
    /// resolve an `android:label` that is a resource reference. F-Droid
    /// therefore installed as `org.fdroid.fdroid 1.23.2.app`. The extractor
    /// DOES know: it writes the resolved label into meta.json and rasterizes
    /// icon.png. This applies both.
    ///
    /// THE LaunchServices registration point: generate() deliberately does
    /// not register (audit S7 -- its claim that the interim name was "never
    /// visible" was false, since the old code registered before the
    /// minutes-long extraction). The bundle is on disk, and so visible to
    /// Finder, from the moment generate() creates it; what this ordering
    /// guarantees is narrower and true: the identity WE hand LaunchServices
    /// is only ever the final one, and a rename -u's the interim path so no
    /// stale registration lingers. Every step is best effort -- an install
    /// must never fail over a name or an icon, and a bundle keeping its
    /// package-derived name still launches.
    ///
    /// Returns the bundle URL, which changes when the name did.
    @discardableResult
    static func finalizeIdentity(bundle: URL, label: String?, iconPNG: URL?,
                                 versionName: String, package: String,
                                 dirName: String?,
                                 keepBundleIdentifier: String? = nil) -> URL {
        let fm = FileManager.default
        var current = bundle

        // 1. The real icon, if the extractor produced one.
        if let icon = iconPNG, fm.fileExists(atPath: icon.path) {
            // ONE recipe with the stub's self-heal: AppIcon.png is a real PNG
            // (a WebP icon.png is transcoded; sips cannot write WebP, so the old
            // copy + buildIcns left the placeholder icns behind) and the icns is
            // built from it.
            if !IconStamp.stamp(bundle: current, from: icon) {
                genLog("WARN: icns build failed for \(icon.path)")
            }
            genLog("finalize: icon from \(icon.lastPathComponent)")

            // v1.0.1001: BUMP THE BUNDLE'S mtime, or macOS shows the OLD icon.
            //
            // Measured 2026-08-19 on YouTube: the bundle is created with the
            // default icon at 13:58:37 and the real one is stamped here at
            // 13:59:02, 25 s later. On disk that is CORRECT -- the trashed copy
            // carries md5 9faabd53 / 4392 bytes, the app's own icon, not the
            // 744f0976 default. The owner still saw the Mac.apk logo.
            //
            // The cause is that IconServices/Finder cache a bundle's icon keyed
            // by PATH, and `/Applications/YouTube.app` had just held a DIFFERENT
            // bundle carrying the default icon (installed, trashed, reinstalled
            // at the same path minutes apart -- the normal reinstall shape).
            // `lsregister -f` below re-registers the bundle but does NOT flush
            // that cache, which is why registering after the icon was not
            // enough on its own.
            //
            // Touching the bundle directory is what invalidates it: the cache
            // is validated against the bundle's modification date, so moving it
            // forward makes the next lookup re-read Contents/Resources. Cheap,
            // no private API, and it must happen BEFORE the lsregister calls
            // below so the re-registration sees the new date.
            //
            // A correct icon that macOS refuses to display is still a wrong
            // icon to the user, so this is part of the fix, not cosmetics.
            try? fm.setAttributes([.modificationDate: Date()],
                                  ofItemAtPath: current.path)
        }

        // 2. The name. Only act on a label that is real and better than what we
        //    already used; blank, or equal to the package, is neither.
        //    v1.0.1641: a label that is neither skips only the RENAME. It used to
        //    return here, which also skipped the Info.plist rewrite and the seal
        //    below -- so after an in-place update MacapkDirName kept naming the
        //    REPLACED version, the stub recorded its pidfile and launches under a
        //    removed dirName, and the next update's running-app check missed it.
        let usableLabel: String? = {
            guard let raw = label, !raw.isEmpty, raw != package else { return nil }
            return sanitizedLabel(raw)
        }()
        let raw: String = {
            if usableLabel != nil, let l = label { return l }
            let pl = current.appendingPathComponent("Contents/Info.plist")
            if let d = try? Data(contentsOf: pl),
               let dict = (try? PropertyListSerialization.propertyList(from: d, format: nil))
                    as? [String: Any],
               let name = BundleDisplayName.label(fromPlist: dict) {
                return name
            }
            return current.deletingPathExtension().lastPathComponent
        }()
        // Same rule as resolveBundleName: the plain label by default.
        var desired = usableLabel ?? current.deletingPathExtension().lastPathComponent
        if let clean = usableLabel, showVersionInName(), !versionName.isEmpty,
           let v = sanitizedLabel(versionName) {
            desired = "\(clean) \(v)"
        }
        if usableLabel != nil {
            desired = apkSafeDirName(desired)
            if desired.isEmpty { desired = apkSafeDirName(package) }
        }
        // The pre-rename executable name, read from Info.plist rather than by
        // listing Contents/MacOS: a `.DS_Store` (or any stray file) appearing
        // during the minutes-long extraction window sorted FIRST in the old
        // `names.first` scan and got renamed instead of the stub, leaving
        // CFBundleExecutable pointing at nothing (audit S10).
        let oldExeName: String? = {
            let pl = current.appendingPathComponent("Contents/Info.plist")
            guard let d = try? Data(contentsOf: pl),
                  let dict = (try? PropertyListSerialization.propertyList(
                        from: d, format: nil)) as? [String: Any] else { return nil }
            return dict["CFBundleExecutable"] as? String
        }() ?? current.deletingPathExtension().lastPathComponent

        if current.deletingPathExtension().lastPathComponent != desired {
            let target = current.deletingLastPathComponent()
                .appendingPathComponent("\(desired).app", isDirectory: true)
            // Never clobber: the name could already belong to another app.
            if fm.fileExists(atPath: target.path) {
                genLog("finalize: \(desired).app exists; keeping \(current.lastPathComponent)")
            } else {
                do {
                    try fm.moveItem(at: current, to: target)
                    // Drop any registration the interim path picked up
                    // (Finder registers apps on sight even though generate()
                    // does not), so no stale LS entry points at a path that
                    // no longer exists. Harmless when never registered.
                    runTool(lsregisterPath, ["-u", current.path])
                    genLog("finalize: renamed to \(target.lastPathComponent)")
                    current = target
                } catch {
                    genLog("finalize: rename to \(desired).app failed, keeping "
                           + "\(current.lastPathComponent): \(error)")
                }
            }
        }

        // 3. The executable must be renamed with the bundle: CFBundleExecutable
        //    has to name a file that exists or the bundle will not launch.
        let contents = current.appendingPathComponent("Contents")
        let macos = contents.appendingPathComponent("MacOS")
        let exeName = current.deletingPathExtension().lastPathComponent
        if let old = oldExeName, old != exeName,
           fm.fileExists(atPath: macos.appendingPathComponent(old).path) {
            try? fm.moveItem(at: macos.appendingPathComponent(old),
                             to: macos.appendingPathComponent(exeName))
        }

        // 4. Rewrite Info.plist with the real name, preserving the fields the
        //    generator computed (versionCode, dirName).
        let plistURL = contents.appendingPathComponent("Info.plist")
        var vc: Int64 = 0
        var keptDir = dirName
        var currentId: String? = nil
        if let d = try? Data(contentsOf: plistURL),
           let pl = (try? PropertyListSerialization.propertyList(from: d, format: nil))
                     as? [String: Any] {
            vc = Int64((pl["MacapkVersionCode"] as? String) ?? "0") ?? 0
            if keptDir == nil { keptDir = pl["MacapkDirName"] as? String }
            currentId = pl["CFBundleIdentifier"] as? String
        }
        // v1.0.1641: an in-place update keeps the bundle's identifier. It is
        // derived from the dirName, i.e. per VERSION, and macOS keys the app's
        // WebKit store (~/Library/WebKit/<id>: cookies, localStorage, IndexedDB),
        // its TCC grants (camera, microphone, screen) and its defaults domain by
        // it -- a new id on every update would orphan all of that.
        let xml = infoPlistXML(
            spec: BundleSpec(package: package, label: raw, versionName: versionName,
                             versionCode: vc, iconPNG: nil, apkToRetain: current),
            executableName: exeName, dirName: keptDir,
            // generate() already chose a UNIQUE id (uniqueBundleIdentifier);
            // recomputing it from the dirName here could collide again.
            bundleIdentifier: keepBundleIdentifier ?? currentId)
        try? xml.write(to: plistURL, atomically: true, encoding: .utf8)
        BundleDisplayName.writeStrings(
            bundle: current, label: raw,
            shown: BundleDisplayName.shownName(label: raw, fileName: exeName,
                                               versionName: versionName))

        // Pre-create the exec symlink the stub would otherwise make at launch.
        // It lives in Contents/MacOS, which is now SEALED, so a link created
        // AFTER signing shows up as `file added: Contents/MacOS/.<name>` and
        // the next assessment fails with "a sealed resource is missing or
        // invalid" -- i.e. the app would launch once and then be refused.
        // Measured on the first signed build before this was added.
        //
        // Name derived the SAME way the stub derives it (from the
        // label just written -- CFBundleName, BundleDisplayName.label -- with
        // "/" folded out), or the stub would
        // not recognise it and would create a second one.
        // Target is the canonical staged path, which is deterministic even if
        // the runtime is not staged yet -- a dangling link seals fine and
        // resolves by the time anything execs it.
        let linkName = "." + raw.replacingOccurrences(of: "/", with: "-")
        let execLink = macos.appendingPathComponent(linkName)
        let linkTarget = RuntimeInstall.stagedRoot
            .appendingPathComponent("Resources/macapk-runtime").path
        try? fm.removeItem(at: execLink)
        if symlink(linkTarget, execLink.path) != 0 {
            InstallLog.write("[sign] could not pre-create \(execLink.path) "
                           + "(errno \(errno)); the stub will create it and "
                           + "the seal will break on second launch")
        }
        // A rename above leaves the data folder's data/app link naming the old
        // path (it is absolute); point it at this one before sealing. Repairs
        // only: finalize also runs on INSTALLED bundles outside an install
        // (refreshStalePurposeStrings, with the app possibly running), so it
        // never moves a legacy tree or creates a folder for a bundle without one.
        if AppDataLayout.dataFolder(ofBundle: current) != nil {
            AppDataLayout.ensure(bundle: current, package: package, mayMove: false,
                                 log: { genLog($0); InstallLog.write("[appdata] " + $0) })
        }
        Quarantine.clear(at: current)
        // Seal LAST: after the icon, the plist rewrite and the extraction, so
        // the seal covers the finished bundle. Before registration, so the
        // first thing LaunchServices ever assesses is the signed article.
        adHocSign(bundle: current)
        registerWithLaunchServices(current)
        return current
    }

    /// Ad-hoc code-sign a generated bundle so macOS will launch it.
    ///
    /// THIS REVERSES THE "generated bundles are NEVER codesigned" decision at
    /// the top of this file, because that decision was measured wrong on a
    /// machine that actually enforces it. The reasoning there was sound as far
    /// as it went -- a seal over Contents/ is invalidated by the app data the
    /// runtime writes under Contents/android/ -- but it assumed the unsealed
    /// bundle would simply launch, and flagged that as a LATENT risk.
    ///
    /// It is not latent any more. MEASURED on a second machine 2026-08-22,
    /// with a control, on the machine that reproduces:
    ///   - unsigned bundle at its installed path        -> "damaged", refused
    ///   - the SAME unsigned bundle at a brand-new path -> "damaged", refused
    ///   - the same bundle after `codesign --force --deep --sign -` -> LAUNCHES,
    ///     and its save data survives open/close.
    /// syspolicyd named the cause outright: with no _CodeSignature/CodeResources
    /// there is no bundle code object to evaluate, so Gatekeeper assesses the
    /// bare stub instead and logs `(bundle_id: (null))`, which no approval can
    /// ever attach to -- `Terminating process due to Gatekeeper rejection`.
    /// Quarantine, provenance and a cached denial breadcrumb were each
    /// hypothesised and each REFUTED by measurement before this one held.
    ///
    /// Ad-hoc (`--sign -`) and not Developer ID: this runs on the USER's
    /// machine, which has no signing identity, and ad-hoc is what was proven
    /// to work. `/usr/bin/codesign` is base-OS (a real 460 KB binary against
    /// CodesignKit, not an xcrun shim), so this does not add a Command Line
    /// Tools dependency.
    ///
    /// Called AFTER extraction, deliberately: sealing before Contents/android
    /// is populated would leave every extracted file outside the seal at the
    /// moment of first assessment. Writes the app makes LATER do invalidate
    /// `codesign --verify`, which is the cost the original decision was trying
    /// to avoid -- but a launch only needs the assessment to pass once per
    /// path, and the measured behaviour above is a bundle that keeps launching
    /// and keeps its data.
    ///
    /// Best effort: an install must not fail over this. A failure is LOGGED
    /// loudly rather than swallowed, because a silent no-op here reproduces
    /// exactly the bug this fixes.
    @discardableResult
    static func adHocSign(bundle: URL) -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/codesign")
        p.arguments = ["--force", "--deep", "--sign", "-", bundle.path]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch {
            InstallLog.write("[sign] could not run codesign: \(error)")
            return false
        }
        let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(),
                         encoding: .utf8) ?? ""
        p.waitUntilExit()
        if p.terminationStatus != 0 {
            // App Management (macOS 14+) can refuse modification of a bundle
            // in /Applications; that is the one failure mode worth naming,
            // because it is a permission the user grants, not a code bug.
            InstallLog.write("[sign] codesign FAILED (\(p.terminationStatus)) on "
                           + "\(bundle.path): \(out.trimmingCharacters(in: .whitespacesAndNewlines))")
            return false
        }
        InstallLog.write("[sign] ad-hoc signed \(bundle.path)")
        return true
    }

    /// Move a generated bundle to the Trash (user-recoverable; the app's
    /// data rides inside it) after unregistering it from LaunchServices.
    /// Hard guards: only direct children of a bundle folder
    /// (Paths.bundleDirs: /Applications or ~/Applications), only .app
    /// directories, never through a symlink, and only bundles carrying our
    /// org.macapk.app. identifier -- this function must be incapable of
    /// deleting anything we did not generate.
    static func remove(bundleAt: URL) throws {
        let fm = FileManager.default
        if (try? bundleAt.resourceValues(forKeys: [.isSymbolicLinkKey]))?
                .isSymbolicLink == true {
            throw BundleGeneratorError.removeRefused("\(bundleAt.path) is a symlink")
        }
        let resolved = bundleAt.resolvingSymlinksInPath().standardizedFileURL
        guard let home = Paths.bundleDir(containing: resolved) else {
            throw BundleGeneratorError.removeRefused(
                "\(resolved.path) is not directly inside "
                + Paths.bundleDirs.map(\.path).joined(separator: " or "))
        }
        let root = home.resolvingSymlinksInPath().standardizedFileURL
        guard resolved.pathExtension == "app" else {
            throw BundleGeneratorError.removeRefused("\(resolved.path) is not a .app")
        }
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: resolved.path, isDirectory: &isDir), isDir.boolValue else {
            throw BundleGeneratorError.removeRefused("\(resolved.path) is not a directory")
        }
        let plistURL = resolved.appendingPathComponent("Contents/Info.plist")
        guard let data = try? Data(contentsOf: plistURL),
              let plist = (try? PropertyListSerialization.propertyList(
                    from: data, format: nil)) as? [String: Any],
              let bid = plist["CFBundleIdentifier"] as? String,
              bid.hasPrefix("org.macapk.app.") else {
            throw BundleGeneratorError.removeRefused(
                "\(resolved.path) does not carry an org.macapk.app.* bundle id "
                + "(not generated by Mac.apk)")
        }
        // Read before the bundle goes: its Contents/android link is the only
        // record of which data folder is this app's (AppDataLayout).
        let dataFolder = AppDataLayout.dataFolder(ofBundle: resolved)
        runTool(lsregisterPath, ["-u", resolved.path])
        try fm.trashItem(at: resolved, resultingItemURL: nil)
        // Uninstall removes the app AND its data, as it did when the data rode
        // inside the bundle. To the Trash, like the bundle, so it is
        // recoverable. ("Keep data" has already lifted the data dir out to
        // kept-data before this runs.)
        if let f = dataFolder {
            AppDataLayout.trashFolder(f, log: { genLog($0); InstallLog.write("[appdata] " + $0) })
        }
        // Stale swap siblings for this name are regenerable or already
        // superseded; sweep them so an uninstall leaves nothing behind.
        let base = resolved.deletingPathExtension().lastPathComponent
        try? fm.removeItem(at: root.appendingPathComponent(".\(base).app.staging"))
        // v1.0.1641: a parked `.previous` is where generate() leaves the ONLY copy
        // of an app's data when adoptAndroidDir fails after the swap; unlinking it
        // here deleted those saves permanently. Trash it instead (recoverable).
        let parked = root.appendingPathComponent(".\(base).app.previous")
        if fm.fileExists(atPath: parked.path) {
            do { try fm.trashItem(at: parked, resultingItemURL: nil) }
            catch { genLog("could not trash \(parked.path): \(error); left in place") }
        }
        genLog("removed \(resolved.path) (trashed)")
    }

    // MARK: - Naming

    /// Folder/executable-safe form of an app label. Strips path
    /// separators, colons, quotes, and control characters; trims leading
    /// dots (hidden dirs) and edge whitespace; caps at 80 characters.
    /// Unicode (including emoji) is legal in APFS names and is kept.
    /// Returns nil when nothing displayable survives.
    /// A bundle's DIRECTORY name must not contain ".apk". The bundle IS the
    /// app's android root (<name>.app/Contents/android), so every guest-visible
    /// path -- sourceDir, getFilesDir, getExternalFilesDir -- runs through this
    /// name, and guest code that finds its APK by splitting a path at the first
    /// ".apk" splits at OURS. Measured 2026-09-22 on Among Us (Unity il2cpp):
    /// with ".apk" earlier in the path its resource copy fails and Unity reports
    /// "Not enough storage space to install required resources." The display
    /// name (CFBundleDisplayName) keeps the real label; only the directory
    /// changes. See runtime/src/apk_unpacker.cpp ResolveAndroidRootDir, which
    /// also warns loudly about an existing root with this shape.
    static func apkSafeDirName(_ name: String) -> String {
        // ".apk" anywhere in the name breaks guests that split a path at the
        // first ".apk" (see the doc above). A SPACE breaks the same class a
        // different way: guest code that recovers its own APK path from
        // getClass().getResource(...) reads a jar:file: URL whose path
        // File.toURI() percent-encodes (AOSP ClassPathURLStreamHandler does the
        // same), so a space becomes "%20"; code that splits that URL WITHOUT
        // URL-decoding then opens a path that does not exist. Measured
        // 2026-10-07 on Minecraft 1.26.52.3 installed as a second version:
        // the collision name "Minecraft (minecraftpe).app" made pairip's
        // com.pairip.VMRunner.openBaseApk fail with "Could not open base apk"
        // (it does substring(9, '!') on the resource URL, no decode), aborting
        // boot. Real Android package paths are space-free (/data/app/...), so
        // real apps never hit this -- only our /Applications/<Label>.app prefix
        // can introduce a space, and our own version/collision suffixes add
        // spaces and parentheses. Collapse any run of space/parenthesis to a
        // single '-' so the directory stays a clean path segment. The display
        // name (CFBundleName / CFBundleDisplayName) keeps the real label; only
        // the directory changes, exactly as for the ".apk" rule above.
        var s = name.replacingOccurrences(of: ".apk", with: " apk",
                                          options: .caseInsensitive)
        s = s.replacingOccurrences(of: "[ ()]+", with: "-",
                                   options: .regularExpression)
        // Anything a file: URL percent-encodes breaks the same class of guest
        // code a third way: code that looks a local page up by its RAW path
        // while WebView.getUrl() hands back the percent-encoded spec (Chromium
        // GURL encodes every non-ASCII byte, as on AOSP). Measured 2026-10-26
        // on 三國志·戰略版（港澳版） (com.qookkagame.sgzzlb.hkmo): ejoysdk's
        // BrowserInjection keys the login page's startup data by
        // "/Applications/三國志·戰略版（港澳版）.app/.../index.html", the page's
        // URL arrives as "/Applications/%E4%B8%89%E5%9C%8B...", the lookup
        // misses, the page gets {} and its sign-in screen throws on
        // pkg_info.country. Real Android data paths are plain ASCII
        // (/data/user/0/<pkg>), so keep only RFC 3986 unreserved characters.
        // A label with none left (all CJK, say) becomes "" and the callers
        // fall back to the package name.
        s = s.replacingOccurrences(of: "[^A-Za-z0-9._~-]+", with: "-",
                                   options: .regularExpression)
        while s.hasPrefix("-") { s.removeFirst() }
        while s.hasSuffix("-") { s.removeLast() }
        return s
    }

    static func sanitizedLabel(_ label: String) -> String? {
        let forbidden = CharacterSet(charactersIn: "/:\\\"'\u{201C}\u{201D}\u{2018}\u{2019}")
            .union(.controlCharacters).union(.newlines)
        var s = label.unicodeScalars.filter { !forbidden.contains($0) }
            .reduce(into: "") { $0.unicodeScalars.append($1) }
        s = s.trimmingCharacters(in: .whitespaces)
        while s.hasPrefix(".") { s.removeFirst() }
        while s.hasSuffix(".") || s.hasSuffix(" ") { s.removeLast() }
        if s.count > 80 { s = String(s.prefix(80)) }
        return s.isEmpty ? nil : s
    }

    /// Bundle-identifier-safe package: keep [A-Za-z0-9.-], map "_" to "-"
    /// (Android allows underscores in package names; bundle ids do not).
    static func sanitizedBundleIdPackage(_ package: String) -> String {
        var out = ""
        for ch in package.unicodeScalars {
            switch ch {
            case "a"..."z", "A"..."Z", "0"..."9", ".", "-": out.unicodeScalars.append(ch)
            case "_": out.append("-")
            default: break
            }
        }
        return out.isEmpty ? "unknown" : out
    }

    /// CFBundleShortVersionString must be digits-and-dots: leading
    /// \d+(\.\d+)* of versionName, fallback "1.0".
    static func sanitizedShortVersion(_ versionName: String) -> String {
        var out = ""
        var lastWasDot = true
        for ch in versionName {
            if ch.isNumber { out.append(ch); lastWasDot = false }
            else if ch == "." && !lastWasDot { out.append(ch); lastWasDot = true }
            else { break }
        }
        while out.hasSuffix(".") { out.removeLast() }
        return out.isEmpty ? "1.0" : out
    }

    /// Pick the bundle folder base name. The name is FIXED at first
    /// install so Dock pins survive: an existing bundle claiming this
    /// package keeps its folder even when the label changed on update.
    /// Only a first install derives a name from the label (else package),
    /// suffixing " (<last package segment>)", then " 2", " 3", ... when a
    /// DIFFERENT package already owns the folder.
    /// Adapt this generator onto `InstallPipeline.Hooks`.
    ///
    /// The pipeline deliberately declares its bundle hooks as CLOSURES over
    /// plain field types rather than referencing `BundleSpec` directly, so
    /// InstallCore.swift compiles whether or not this file is in the target.
    /// This is the single place that ties the two together -- keep it here,
    /// so there is exactly one call site to audit when the shape changes.
    static func install(into hooks: inout InstallPipeline.Hooks) {
        hooks.generateBundle = { pkg, label, versionName, versionCode, icon, apk, dirName in
            try generate(BundleSpec(package: pkg, label: label,
                                    versionName: versionName,
                                    versionCode: versionCode,
                                    iconPNG: icon, apkToRetain: apk),
                         dirName: dirName)
        }
        hooks.removeBundle = { url in try remove(bundleAt: url) }
        hooks.finalizeBundle = { bundle, label, icon, versionName, package, dirName, keepId in
            finalizeIdentity(bundle: bundle, label: label, iconPNG: icon,
                             versionName: versionName, package: package,
                             dirName: dirName, keepBundleIdentifier: keepId)
        }
    }

    /// Which record a generated bundle represents.
    ///
    /// `.version` (DEFAULT, owner decision 2026-08-10) gives every installed
    /// VERSION its own .app, because the registry already keys rows by
    /// dirName and this machine legitimately carries three rows for
    /// com.fingersoft.hillclimb. Keying bundles by package while the registry
    /// keys rows by dirName was an incompatibility between two lanes: a
    /// second version produced two grid rows but one bundle, and the update
    /// path then moved the first version's saves into the second version's
    /// bundle before deleting the parked one.
    ///
    /// `.package` is the shipping-product behaviour (an update REPLACES the
    /// app in place, one icon in Launchpad) and is selectable from the
    /// control panel's dev section.
    enum IdentityMode: String {
        case version   // one .app per <package>_<version>
        case package   // one .app per package; updates replace in place
    }

    /// Registry setting key backing the dev-menu toggle. Absent means
    /// `.version`, so a machine that has never touched the toggle gets the
    /// default without a migration.
    static let identityModeSettingKey = "bundleIdentityMode"

    /// Show the version in a bundle's NAME. Default OFF: a user installing
    /// F-Droid should get "F-Droid.app", not "F-Droid 1.23.2.app" -- the
    /// version is an implementation detail of our per-version identity, and
    /// Finder/Launchpad show the bundle's filename, so it leaks straight into
    /// the user's Applications folder.
    ///
    /// Versions still get SEPARATE bundles; the second one just disambiguates
    /// by suffix (see resolveBundleName), so the common case -- one version
    /// installed -- reads clean and only an actual collision shows a number.
    /// Dev toggle in the control panel's Advanced section.
    static let showVersionInNameSettingKey = "bundleNameShowsVersion"

    static func showVersionInName() -> Bool {
        RegistryStore.shared.setting(showVersionInNameSettingKey) == "1"
    }

    static func identityMode() -> IdentityMode {
        guard let raw = RegistryStore.shared.setting(identityModeSettingKey),
              let mode = IdentityMode(rawValue: raw) else { return .version }
        return mode
    }

    /// The bundle folder `generate()` works in for this record: the writable
    /// folder that already holds its bundle (an update regenerates in place,
    /// with its data), else `Paths.bundlesDir` -- /Applications, or
    /// ~/Applications for a user who cannot write it (GitHub #28).
    ///
    /// A bundle found only in a folder this user CANNOT write (an admin put
    /// it in /Applications) is not regenerated there -- that is the 513
    /// failure -- so the new one goes to the writable folder and the log says
    /// two copies now exist.
    /// internal so debug/bundlesdir_gate.sh can drive it.
    static func homeFolder(for spec: BundleSpec, dirName: String?) -> URL {
        let wantDir = (identityMode() == .version) ? dirName : nil
        var lockedOut: URL?
        for d in Paths.bundleDirs {
            guard let n = existingBundleName(matching: spec.package, dirName: wantDir, in: d)
            else { continue }
            if Paths.isWritableDir(d) { return d }
            if lockedOut == nil { lockedOut = d.appendingPathComponent("\(n).app") }
        }
        let target = Paths.prepareBundlesDir()
        if let l = lockedOut {
            genLog("\(l.path) is not writable for this user; installing a separate "
                 + "copy in \(target.path)")
        }
        return target
    }

    // internal (not private) so debug/bundlename_gate.sh can drive it against
    // a temp directory; nothing outside this file calls it.
    static func resolveBundleName(for spec: BundleSpec, dirName: String?,
                                  in bundlesDir: URL) throws -> String {
        // Identity: the dirName when one is available and we are in
        // per-version mode, else the package.
        let mode = identityMode()
        let wantDir = (mode == .version) ? dirName : nil
        if let existing = existingBundleName(matching: spec.package, dirName: wantDir,
                                             in: bundlesDir) {
            return existing
        }
        // The NAME is what Finder and Launchpad show, so it defaults to the
        // plain label: "F-Droid.app". Versions still get separate bundles --
        // the collision loop below suffixes the second one -- so a user with
        // one version installed never sees a number, and only a genuine
        // collision produces "F-Droid (fdroid)" / "F-Droid 2".
        // The dev toggle puts the version back in for A/B work.
        var base = sanitizedLabel(spec.label) ?? spec.package
        if wantDir != nil, showVersionInName(), !spec.versionName.isEmpty,
           let v = sanitizedLabel(spec.versionName) {
            base = "\(base) \(v)"
        }
        base = apkSafeDirName(base)
        if base.isEmpty { base = spec.package }
        let lastSeg = spec.package.split(separator: ".").last.map(String.init) ?? spec.package
        // Run EVERY candidate through apkSafeDirName, not just `base`: the
        // collision suffixes below re-introduce a space and parentheses, which
        // are exactly the path hazard apkSafeDirName exists to remove (a space
        // in the bundle path broke pairip's APK-path recovery -- see its doc).
        var candidates = [base, apkSafeDirName("\(base) (\(lastSeg))")]
        for n in 2...99 { candidates.append(apkSafeDirName("\(base) \(n)")) }
        for cand in candidates {
            let url = bundlesDir.appendingPathComponent("\(cand).app")
            guard FileManager.default.fileExists(atPath: url.path) else { return cand }
            if bundleMatches(url, package: spec.package, dirName: wantDir) {
                return cand  // ours: regenerate in place
            }
        }
        throw BundleGeneratorError.tooManyLabelCollisions(spec.label)
    }

    /// Does this .app represent the record we are generating for? When
    /// `dirName` is non-nil the match is per-version, else per-package.
    private static func bundleMatches(_ url: URL, package: String, dirName: String?) -> Bool {
        let plistURL = url.appendingPathComponent("Contents/Info.plist")
        guard let data = try? Data(contentsOf: plistURL),
              let plist = (try? PropertyListSerialization.propertyList(
                    from: data, format: nil)) as? [String: Any] else {
            // No readable Info.plist: a HALF bundle (an interrupted uninstall
            // or install left `Contents/android` behind). It used to count as
            // somebody else's folder, so the reinstall of the SAME app was
            // pushed to "<Label> (<lastSeg>).app" next to its own orphaned
            // data (measured 2026-09-24: "Flappy Bird.app" holding only
            // Contents/android/.macapk/code/com.dotgears.flappybird_1.3 made
            // the real install "Flappy Bird (flappybird).app"). Adopt it when
            // its android tree is THIS package's, so the app regenerates in
            // place and keeps its saves; a different package's leftovers still
            // collide and get the suffix.
            return orphanMatches(url, package: package, dirName: dirName)
        }
        guard (plist["MacapkPackage"] as? String) == package else { return false }
        guard let want = dirName else { return true }
        // A bundle generated before MacapkDirName existed carries no such key.
        // Treat it as the package's bundle so it is adopted and upgraded in
        // place rather than orphaned beside a new one.
        guard let have = plist["MacapkDirName"] as? String else { return true }
        return have == want
    }

    /// A plist-less folder whose leftover android tree belongs to `package`
    /// (and to `dirName`'s version, when one is given). Evidence is the code
    /// cache dir `<package>_<version>` or the app's own data dir.
    private static func orphanMatches(_ url: URL, package: String, dirName: String?) -> Bool {
        let fm = FileManager.default
        let android = url.appendingPathComponent("Contents/android")
        let codeRoot = android.appendingPathComponent(".macapk/code")
        if let codes = try? fm.contentsOfDirectory(atPath: codeRoot.path) {
            for c in codes {
                if let want = dirName { if c == want { return true } }
                else if c == package || c.hasPrefix(package + "_") { return true }
            }
        }
        // No code cache left (or it was cleaned): the app's data dir still names
        // it. The runtime names that dir `<pkg>_<ver>` (main.cpp
        // SetupAndroidRoot; see the layout at the top of this file), i.e. the
        // same string as the registry dirName, with a bare `<pkg>` accepted
        // for bundles older than the versioned layout.
        let dataRoot = android.appendingPathComponent("data/data")
        if let dirs = try? fm.contentsOfDirectory(atPath: dataRoot.path) {
            for d in dirs {
                if let want = dirName { if d == want { return true } }
                else if d == package || d.hasPrefix(package + "_") { return true }
            }
        }
        return false
    }

    /// Scan bundlesDir for a generated bundle representing this record.
    /// Sorted so a (should-never-happen) duplicate resolves deterministically.
    private static func existingBundleName(matching package: String, dirName: String?,
                                           in bundlesDir: URL) -> String? {
        let fm = FileManager.default
        guard let children = try? fm.contentsOfDirectory(
                at: bundlesDir, includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]) else { return nil }
        for child in children.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
        where child.pathExtension == "app" {
            if bundleMatches(child, package: package, dirName: dirName) {
                return child.deletingPathExtension().lastPathComponent
            }
        }
        return nil
    }

    // MARK: - Staging

    private static func buildStagingBundle(spec: BundleSpec, name: String,
                                           dirName: String?,
                                           template: URL, at staging: URL,
                                           finalURL: URL) throws {
        let fm = FileManager.default
        let contents = staging.appendingPathComponent("Contents", isDirectory: true)
        let macos = contents.appendingPathComponent("MacOS", isDirectory: true)
        let resources = contents.appendingPathComponent("Resources", isDirectory: true)
        let android = contents.appendingPathComponent("android", isDirectory: true)
        try fm.createDirectory(at: macos, withIntermediateDirectories: true)
        try fm.createDirectory(at: resources, withIntermediateDirectories: true)
        // data/data is created WITHOUT a package leaf: the runtime names the
        // app dir itself (data/data/<pkg>_<ver>, main.cpp SetupAndroidRoot).
        // A pre-created bare data/data/<pkg>/ was a ghost the runtime never
        // used, one per bundle (audit S10).
        try fm.createDirectory(
            at: android.appendingPathComponent("data/data", isDirectory: true),
            withIntermediateDirectories: true)
        try fm.createDirectory(
            at: android.appendingPathComponent(".macapk/code", isDirectory: true),
            withIntermediateDirectories: true)

        // The stub, pre-signed at build time; the copy is a new file (new
        // inode), which is exactly what the kernel signature cache wants.
        try fm.copyItem(at: template, to: macos.appendingPathComponent(name))

        // NO retained copy of the source APK (v1.0.1585; owner: "i dont want
        // mac.apk to save a copy of the apks anymore ever"). The extraction
        // that follows generation consumes the dropped file into the Android
        // install layout, `Contents/android/data/app/~~<nonce>/<pkg>-<nonce>/
        // base.apk` (+ split_*.apk for a container) -- the ONE copy the app
        // owns, which is what AppStubMain launches. `spec.apkToRetain` is
        // still the file the extractor reads; it just no longer lands here.
        _ = spec.apkToRetain

        // Icon: .icns for Finder/Dock (proven sips+iconutil recipe, same as
        // build_launcher.sh), plus the raw PNG for the runtime's
        // MACAPK_APP_ICON Dock adoption. Both best-effort: an install must
        // never fail over an icon.
        var iconSource = spec.iconPNG
        if iconSource == nil || !fm.fileExists(atPath: iconSource!.path) {
            // v1.0.1000: SAY WHY. This fallback was completely silent, and
            // that silence is why a real defect hid behind it: on YouTube
            // (2026-08-19) the rasterizer had produced a correct 4392-byte
            // icon.png in the CODE dir, nothing mirrored it into data/data
            // where the installer reads, and the bundle silently shipped a
            // default icon md5-identical to macapk_default_icon.png. The only
            // signal was the ABSENCE of finalizeIdentity's "finalize: icon
            // from ..." line, which is not something anyone greps for.
            //
            // Note a nil iconPNG is NORMAL at generate() time -- InstallCore
            // deliberately passes nil because the bundle must exist before
            // extraction targets it, and finalizeIdentity stamps the real icon
            // afterwards. So nil here is only interesting when it is the LAST
            // word, which is why this distinguishes the two cases rather than
            // crying wolf on every install.
            if let missing = iconSource {
                genLog("WARN: icon \(missing.lastPathComponent) was named but "
                     + "does not exist at \(missing.path) -- using the default")
            } else {
                genLog("interim identity: no icon yet (expected at generate "
                     + "time; finalizeIdentity stamps the real one)")
            }
            // NOT Bundle.main: nil inside macapk-installhelper. See
            // bundledResourceCandidates() -- this exact line shipped bundles
            // with no icon for every store-driven install.
            iconSource = bundledResourceURL("macapk_default_icon.png")
        }
        if let src = iconSource {
            try? fm.copyItem(at: src, to: resources.appendingPathComponent("AppIcon.png"))
            buildIcns(from: src, to: resources.appendingPathComponent("AppIcon.icns"))
        }

        // v1.0.1641: the id must be unique per bundle (see infoPlistXML). An
        // in-place update KEEPS its bundle's original per-version id, so a later
        // side-by-side install of that older version would compute the same id
        // for a DIFFERENT bundle; give the newcomer a free one instead.
        let baseId = "org.macapk.app." + (dirName.map(sanitizedBundleIdPackage)
                                          ?? sanitizedBundleIdPackage(spec.package))
        let plist = infoPlistXML(spec: spec, executableName: name, dirName: dirName,
                                 bundleIdentifier: uniqueBundleIdentifier(baseId, excluding: finalURL))
        try plist.write(to: contents.appendingPathComponent("Info.plist"),
                        atomically: true, encoding: .utf8)
        // The label, for LaunchServices (BundleDisplayName): the plist's
        // CFBundleDisplayName is the FILE name, so without this the bundle
        // would display as e.g. "Calculator (calculator)".
        BundleDisplayName.writeStrings(
            bundle: contents.deletingLastPathComponent(), label: spec.label,
            shown: BundleDisplayName.shownName(label: spec.label, fileName: name,
                                               versionName: spec.versionName))
        try "APPL????".write(to: contents.appendingPathComponent("PkgInfo"),
                             atomically: true, encoding: .utf8)
    }

    /// `base`, or `base-2`, `base-3`, ... when another bundle in either
    /// bundle folder (not `excluding`, the bundle being regenerated) already
    /// carries it. Both folders, because Launch Services sees both.
    static func uniqueBundleIdentifier(_ base: String, excluding: URL) -> String {
        var taken = Set<String>()
        for u in Paths.bundleCandidates() {
            if u.standardizedFileURL.path == excluding.standardizedFileURL.path { continue }
            let pl = u.appendingPathComponent("Contents/Info.plist")
            if let d = try? Data(contentsOf: pl),
               let dict = (try? PropertyListSerialization.propertyList(from: d, format: nil)) as? [String: Any],
               let id = dict["CFBundleIdentifier"] as? String {
                taken.insert(id)
            }
        }
        if !taken.contains(base) { return base }
        for i in 2...99 where !taken.contains("\(base)-\(i)") { return "\(base)-\(i)" }
        return base
    }

    // internal (not private) so debug/appidentity_gate.sh can render the REAL
    // template; nothing outside this file calls it.
    static func infoPlistXML(spec: BundleSpec, executableName: String,
                                     dirName: String?,
                                     bundleIdentifier: String? = nil) -> String {
        // The bundle id must be UNIQUE PER GENERATED BUNDLE. Under the default
        // per-version identity two versions of one package coexist as two
        // .apps, and if both claimed org.macapk.app.<package> LaunchServices
        // would treat them as the same app: urlForApplication(withBundleId:)
        // returns one arbitrarily, and the stub could exec the wrong one.
        // In per-package mode dirName is nil and the id is the package, which
        // is exactly the old behaviour.
        let idLeaf = dirName.map(sanitizedBundleIdPackage) ?? sanitizedBundleIdPackage(spec.package)
        let pkgSan = idLeaf
        let shortVer = sanitizedShortVersion(spec.versionName)
        func e(_ s: String) -> String {
            s.replacingOccurrences(of: "&", with: "&amp;")
             .replacingOccurrences(of: "<", with: "&lt;")
             .replacingOccurrences(of: ">", with: "&gt;")
             .replacingOccurrences(of: "\"", with: "&quot;")
        }
        let dirNameEntry = dirName.map {
            "\n  <key>MacapkDirName</key><string>\(e($0))</string>"
        } ?? ""
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <!-- CFBundleName is the Android label (menu bar, Cmd-Tab). The
               DISPLAY name is the label too, but it has to arrive through
               {Base,en}.lproj/InfoPlist.strings: macOS shows a localized
               display name only when this unlocalized CFBundleDisplayName
               equals the bundle's FILE name, and the file can carry a
               collision suffix ("Calculator (calculator).app"). See
               BundleDisplayName in InstallCore.swift. The executable is named
               after the bundle file, so executableName IS that file name. -->
          <key>CFBundleName</key><string>\(e(spec.label))</string>
          <key>CFBundleDisplayName</key><string>\(e(executableName))</string>
          <key>LSHasLocalizedDisplayName</key><true/>
          <key>CFBundleDevelopmentRegion</key><string>en</string>
          <key>CFBundleExecutable</key><string>\(e(executableName))</string>
          <key>CFBundleIdentifier</key><string>\(e(bundleIdentifier ?? "org.macapk.app.\(pkgSan)"))</string>
          <key>CFBundlePackageType</key><string>APPL</string>
          <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
          <key>CFBundleIconFile</key><string>AppIcon</string>
          <key>CFBundleVersion</key><string>\(spec.versionCode)</string>
          <key>CFBundleShortVersionString</key><string>\(e(shortVer))</string>
          <key>LSMinimumSystemVersion</key><string>26.4</string>
          <key>NSHighResolutionCapable</key><true/>
          <!-- TCC PURPOSE STRINGS. macOS REFUSES the capability outright, WITHOUT
               PROMPTING, when the requesting bundle has no usage description;
               AVCaptureDevice just reports "not authorized". These were present on
               Mac.apk.app but NOT on the per-app bundles this generator writes, so
               an app run from the LAUNCHER could use the camera while the SAME app
               INSTALLED to /Applications silently could not. Measured 2026-08-24
               with debug/camtest: onError code=3 ERROR_CAMERA_DISABLED and no
               prompt ever appeared.
               Every capability an Android app can request through Mac.apk needs its
               string here; a missing one is invisible until a user tries it. -->
          <key>NSCameraUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting camera access.</string>
          <key>NSMicrophoneUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting microphone access.</string>
          <key>NSLocationWhenInUseUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting your location.</string>
          <key>NSBluetoothAlwaysUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting Bluetooth access.</string>
          <key>NSLocalNetworkUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting local network access.</string>
          <key>NSPhotoLibraryUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting photo library access.</string>
          <key>NSSpeechRecognitionUsageDescription</key><string>\(e(spec.label)) is an Android app running in Mac.apk and is requesting speech recognition.</string>
          <key>LSMultipleInstancesProhibited</key><true/>
          <key>MacapkPackage</key><string>\(e(spec.package))</string>\(dirNameEntry)
          <key>MacapkVersionCode</key><string>\(spec.versionCode)</string>
          <key>MacapkVersionName</key><string>\(e(spec.versionName))</string>
          <key>MacapkGeneratedBy</key><string>\(e(BuildInfo.id))</string>
        </dict>
        </plist>
        """
    }

    // MARK: - Swap recovery

    /// Heal a crash that interrupted a previous generate() swap for this
    /// bundle name. Data is never deleted: the .previous bundle (which
    /// carries Contents/android/) either becomes the app again or donates
    /// its android/ tree to the promoted bundle.
    private static func recoverInterruptedSwap(name: String, finalURL: URL, in dir: URL) {
        let fm = FileManager.default
        let previous = dir.appendingPathComponent(".\(name).app.previous", isDirectory: true)
        guard fm.fileExists(atPath: previous.path) else { return }
        if !fm.fileExists(atPath: finalURL.path) {
            // Crashed between parking the old bundle and promoting the new
            // one: the parked bundle IS the app. Put it back.
            if (try? fm.moveItem(at: previous, to: finalURL)) != nil {
                genLog("RECOVERED interrupted update: restored \(finalURL.path)")
            }
        } else {
            // Same rule as the swap path: the parked bundle is only removed
            // once its data is provably in the promoted one. On failure we
            // keep it, so the saves survive and the next generate() for this
            // name retries the adoption.
            do {
                try adoptAndroidDir(from: previous, into: finalURL)
                try? fm.removeItem(at: previous)
                genLog("RECOVERED interrupted update: adopted app data into \(finalURL.path)")
            } catch {
                genLog("ERROR: interrupted update left app data in \(previous.path); "
                       + "keeping it for the next attempt: \(error)")
            }
        }
    }

    /// Move Contents/android from `old` into `current`, replacing the
    /// skeleton. One directory rename, so it is atomic on the volume.
    ///
    /// ⚠ THIS MUST THROW, AND THE SKELETON MUST NOT BE DELETED UP FRONT.
    /// It used to return Void and swallow a failed move as a WARN, while
    /// both callers went on to delete `old` unconditionally -- so a move
    /// that failed for any reason destroyed the user's ONLY copy of the
    /// app's saves, silently, while the enclosing catch still claimed the
    /// data had been preserved. Deleting `dst` before the move made it
    /// worse: a failure then lost both sides. The skeleton is now parked
    /// aside and restored if the move fails, and the error propagates so
    /// callers keep `old` (which still holds the data) intact.
    private static func adoptAndroidDir(from old: URL, into current: URL) throws {
        let fm = FileManager.default
        // The installed package first. Since v1.0.2251 it lives in Contents/app
        // and the data folder's data/app links to it (AppDataLayout); a new
        // staging bundle never has one, so this is a plain rename.
        let oldApp = old.appendingPathComponent("Contents/app")
        let newApp = current.appendingPathComponent("Contents/app")
        var movedApp = false
        if AppDataLayout.fileType(oldApp.path) != nil,
           AppDataLayout.fileType(newApp.path) == nil {
            try fm.moveItem(at: oldApp, to: newApp)
            movedApp = true
        }
        // lstat, not fileExists: Contents/android is a LINK to the data folder
        // since v1.0.2251, and fileExists follows it (a folder the user deleted
        // would read as "no data" and the link would be dropped with `old`).
        let src = old.appendingPathComponent("Contents/android")
        guard AppDataLayout.fileType(src.path) != nil else { return }
        let dst = current.appendingPathComponent("Contents/android")

        // Park the freshly generated (empty) skeleton rather than deleting
        // it, so a failed move can be fully undone.
        var parked: URL?
        if AppDataLayout.fileType(dst.path) != nil {
            let aside = current.appendingPathComponent(
                "Contents/.android.skeleton.\(UUID().uuidString)", isDirectory: true)
            try fm.moveItem(at: dst, to: aside)
            parked = aside
        }
        do {
            try fm.moveItem(at: src, to: dst)
        } catch {
            if let aside = parked { try? fm.moveItem(at: aside, to: dst) }
            if movedApp { try? fm.moveItem(at: newApp, to: oldApp) }
            genLog("ERROR: could not carry app data forward from \(src.path): \(error)")
            throw error
        }
        if let aside = parked { try? fm.removeItem(at: aside) }
    }

    // MARK: - Icons

    /// PNG -> multi-size .icns via sips + iconutil: the slot names of
    /// scripts/build_launcher.sh, but only the slots up to the source's own
    /// resolution (IconStamp.ladder, MACAPK_ICNS_NO_UPSCALE; the recipe already proven for
    /// the launcher's own icon; ImageIO icns writing is NOT used because
    /// its multi-size behavior is unproven on this platform).
    @discardableResult
    /// Delegates to `IconStamp.buildIcns` in InstallCore.swift, which is the
    /// ONE implementation -- the app stub needs the same recipe to self-heal a
    /// bundle on launch, and BundleGenerator is not compiled into the stub.
    /// Kept as a thin wrapper so the two existing call sites below read the
    /// same as before.
    private static func buildIcns(from png: URL, to icns: URL) -> Bool {
        let ok = IconStamp.buildIcns(from: png, to: icns)
        if !ok { genLog("WARN: icns build failed for \(png.path)") }
        return ok
    }

    // MARK: - Plumbing

    private static let lsregisterPath =
        "/System/Library/Frameworks/CoreServices.framework/Versions/A/"
        + "Frameworks/LaunchServices.framework/Versions/A/Support/lsregister"

    private static func registerWithLaunchServices(_ bundle: URL) {
        let status = runTool(lsregisterPath, ["-f", bundle.path])
        if status != 0 {
            // Non-fatal: Finder/LS also registers on first sight; log so a
            // missing Spotlight entry is diagnosable.
            genLog("WARN: lsregister -f exited \(status) for \(bundle.path)")
        }
    }

    /// The pre-signed stub template inside the running Mac.apk bundle
    /// (placed there by scripts/build_launcher.sh).
    /// Candidate locations for a path under the Mac.apk bundle's
    /// `Contents/Resources`, in priority order, for BOTH binaries this file is
    /// compiled into.
    ///
    /// ⚠ THE TRAP THIS EXISTS TO KILL: `Bundle.main` is only correct for the
    /// launcher, which sits at `Contents/MacOS/MacApk`. `macapk-installhelper`
    /// sits at `Contents/bin/`, and a binary there is NOT inside a bundle as
    /// far as CFBundle is concerned -- `Bundle.main.bundlePath` comes back as
    /// `Contents/bin` itself and every `url(forResource:)` returns nil.
    /// MEASURED with a standalone probe, 2026-08-17: the identical binary
    /// resolves `macapk_default_icon.png` from `Contents/MacOS/` and returns
    /// nil from `Contents/bin/`. (CFBundle wants exactly `Contents/MacOS/exe`;
    /// any other depth breaks the walk -- the same reason the app stub's exec
    /// symlink must stay flat in `Contents/MacOS/`.)
    ///
    /// This was already found once, for the stub template, on 2026-08-11 --
    /// but the fix was written inline in `stubTemplateURL()` alone, so the
    /// icon lookup below kept using bare `Bundle.main` and silently produced
    /// bundles with NO icon at all whenever an install came from the helper
    /// (i.e. every app installed by a guest store rather than dropped on the
    /// launcher). Owner hand-test 2026-08-17: *"The install failed to get the
    /// icon"* for PlayTube, installed from inside F-Droid -- the only app on
    /// disk with no `AppIcon.icns`, while all 11 launcher-installed apps had
    /// one. Hence ONE resolver both call sites share, rather than a second
    /// inline copy waiting to be missed a third time.
    private static func bundledResourceCandidates(_ relPath: String) -> [URL] {
        var candidates: [URL] = []
        if let res = Bundle.main.resourceURL {
            candidates.append(res.appendingPathComponent(relPath))
        }
        // Resolve from OUR OWN EXECUTABLE, which is true for both callers.
        let me = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        let myDir = me.deletingLastPathComponent()          // Contents/MacOS or Contents/bin
        let contents = myDir.deletingLastPathComponent()    // Contents
        candidates.append(contents.appendingPathComponent("Resources/" + relPath))
        candidates.append(myDir.appendingPathComponent(relPath))
        return candidates
    }

    /// First existing candidate for `relPath`, or nil. Best-effort by design:
    /// callers treat a missing resource as "skip it", never as a hard failure.
    static func bundledResourceURL(_ relPath: String) -> URL? {
        let fm = FileManager.default
        return bundledResourceCandidates(relPath)
            .first(where: { fm.fileExists(atPath: $0.path) })
    }

    /// The pre-signed stub template, which always lives at
    /// `Contents/Resources/appstub/MacApkAppStub`.
    ///
    /// ⚠ `Bundle.main` IS NOT ENOUGH. This file is compiled into TWO binaries
    /// that sit at different depths: the launcher at `Contents/MacOS/MacApk`,
    /// where `Bundle.main.resourceURL` is the app's Resources, and
    /// `macapk-installhelper` at `Contents/bin/`, where a bare (non-bundled)
    /// executable makes `Bundle.main` its own directory -- so the helper looked
    /// for `Contents/bin/appstub/...`, found nothing, and silently fell back to
    /// installing without a bundle. Measured 2026-08-11 on the helper's first
    /// real run.
    ///
    /// So resolve from OUR OWN EXECUTABLE PATH as well, which is true for both
    /// callers, and keep Bundle.main first for the launcher's normal case.
    private static func stubTemplateURL() throws -> URL {
        let candidates = bundledResourceCandidates("appstub/MacApkAppStub")
        let fm = FileManager.default
        if let hit = candidates.first(where: { fm.isExecutableFile(atPath: $0.path) }) {
            return hit
        }
        throw BundleGeneratorError.stubTemplateMissing(
            candidates.map(\.path).joined(separator: ", ")
            + " -- rebuild Mac.apk with scripts/build_launcher.sh "
            + "(it compiles launcher/AppStubMain.swift into the template)")
    }

    @discardableResult
    private static func runTool(_ path: String, _ args: [String]) -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do {
            try p.run()
            p.waitUntilExit()
            return p.terminationStatus
        } catch {
            return -1
        }
    }

    private static func genLog(_ msg: String) {
        FileHandle.standardError.write(Data(("[BundleGenerator] " + msg + "\n").utf8))
    }
}
