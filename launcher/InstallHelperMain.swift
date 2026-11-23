// InstallHelperMain.swift -- @main for Contents/bin/macapk-installhelper.
//
// A SYNCHRONOUS, headless install. It exists because `open --env` cannot
// deliver an environment to an app that is ALREADY RUNNING -- macOS answers
// "was already running and so the additional environment variables could not
// be set" -- so the harness's MACAPK_INSTALL_NOUI never arrived, the Finder
// path showed its confirm dialog, and an unattended run blocked until timeout
// and reported "no bundle in 300s". That reads as an install failure and is
// really "nobody clicked". Measured 2026-08-11.
//
// It is also the shape the deferred F-Droid path needs: a guest app cannot be
// handed the launcher's UI, so an Android-initiated install has to cross into
// a host process that can show a confirmation and (later) an authorization
// prompt of its own.
//
// ⚠ THE AUTH GATE IS THIS BINARY'S, AND --no-ui MUST NEVER REACH PAST IT.
// `--source android` -- an install OR an uninstall initiated by an app running
// inside the runtime -- requires a real LAContext device-owner check, in this
// process, before anything else happens. A guest can influence a child
// process's environment, so no env var and no flag may ever be sufficient to
// skip that step: --no-ui is refused outright for the android source, and any
// failure of the check (cancel, wrong password, no method available) declines
// the request. For an UNINSTALL the prompt additionally NAMES THE TARGET --
// label and package resolved from OUR registry, never from guest-supplied
// text -- because that prompt is the human's only defence against a store
// asking to delete something other than what the human thinks (it plays the
// role of AOSP's system-owned UninstallerActivity confirmation dialog).
//
// Exit codes (master plan 4.6): 0 success, 2 bad args, 3 cancelled,
// 4 auth failed, 5 error, 6 unsupported input.

import CryptoKit
import Foundation
import LocalAuthentication

@main
struct InstallHelperMain {

    static func main() {
        var args = Array(CommandLine.arguments.dropFirst())
        guard let verb = args.first else { usage(); exit(2) }
        args.removeFirst()

        var apk: String?
        var source = "gui"
        var json = false
        var noUI = false
        var pkg: String?
        var dirName: String?
        var deleteData = true
        var requestorLabel = ""
        var removeAllApps = false
        var force = false
        var dryRun = false
        var op: String?
        var identityOnly = false
        var selftestDir: String?
        // Stage S5 (runtime-permission / app-details): repeatable lists.
        var ops: [String] = []
        var perms: [String] = []
        var members: [String] = []
        var partial: [String] = []
        var groups: [String] = []
        var prompt: String?
        var level = 0
        var auto = false

        while !args.isEmpty {
            let a = args.removeFirst()
            switch a {
            case "--apk":             apk = args.isEmpty ? nil : args.removeFirst()
            case "--package":         pkg = args.isEmpty ? nil : args.removeFirst()
            case "--dir-name":        dirName = args.isEmpty ? nil : args.removeFirst()
            case "--source":          source = args.isEmpty ? "gui" : args.removeFirst()
            case "--json":            json = true
            case "--no-ui":           noUI = true
            case "--requestor-label": requestorLabel = args.isEmpty ? "" : args.removeFirst()
            case "--keep-data":       deleteData = false
            case "--remove-all-apps": removeAllApps = true
            case "--force":           force = true
            case "--dry-run":         dryRun = true
            case "--op":              op = args.isEmpty ? nil : args.removeFirst()
                                      if let o = op { ops.append(o) }
            case "--identity-only":   identityOnly = true
            case "--dir":             selftestDir = args.isEmpty ? nil : args.removeFirst()
            case "--perm":            if !args.isEmpty { perms.append(args.removeFirst()) }
            case "--member":          if !args.isEmpty { members.append(args.removeFirst()) }
            case "--partial":         if !args.isEmpty { partial.append(args.removeFirst()) }
            case "--group":           if !args.isEmpty { groups.append(args.removeFirst()) }
            case "--prompt":          prompt = args.isEmpty ? nil : args.removeFirst()
            case "--level":           level = Int(args.isEmpty ? "" : args.removeFirst()) ?? 0
            case "--auto":            auto = true
            case "-h", "--help":      usage(); exit(0)
            default:
                FileHandle.standardError.write(Data("unknown argument: \(a)\n".utf8))
                exit(2)
            }
        }

        // ---- special app access (stage S1) ---------------------------------
        // Android's Settings "All files access" / "Media management" switch.
        // Handled HERE, ahead of the LAContext gate below, because its consent
        // is a different surface (a CFUserNotification the user answers, not a
        // device-owner authentication) -- and it opts in to the android source
        // explicitly rather than inheriting the install-shaped prompt. See
        // SpecialAccess.run for the whole contract.
        if verb == "special-access" {
            exit(SpecialAccess.run(op: op, source: source, noUI: noUI,
                                   identityOnly: identityOnly, json: json))
        }
        if verb == "appops-selftest" {
            exit(SpecialAccess.selftest(dir: selftestDir, json: json))
        }
        // ---- storage runtime permissions + App info (stage S5) -------------
        // Android's GrantPermissionsActivity dialog and Settings' App info
        // screen. Same surface, same identity rule and same signed store as
        // special-access above; see RuntimePermission for the contract.
        if verb == "runtime-permission" {
            exit(RuntimePermission.run(source: source, noUI: noUI, level: level,
                                       prompt: prompt, auto: auto, perms: perms,
                                       members: members, partial: partial, json: json))
        }
        if verb == "app-details" {
            exit(RuntimePermission.appDetails(source: source, noUI: noUI, level: level,
                                              groups: groups, ops: ops, json: json))
        }
        if verb == "perms-selftest" {
            exit(RuntimePermission.selftest(dir: selftestDir, json: json))
        }

        // ---- the SHARED RUNTIME verbs ------------------------------------
        // Handled before locateRuntime() below, because the entire point of
        // `runtime-install` is that there may be no usable runtime yet, and
        // `runtime-status` must be answerable when there is definitely none.
        // They also give debug/installtest/run.sh a headless driver for the
        // most destructive action in the product.
        switch verb {
        case "runtime-status", "runtime-install", "runtime-uninstall":
            // These sit BEFORE the android-source auth gate below (they must
            // work with no runtime installed), so refuse that source here or
            // `runtime-uninstall --source android` would be the one verb the
            // gate never sees. No caller passes this combination -- the
            // runtime's argv is compile-time and only ever install/uninstall
            // -- but "every verb except install/uninstall refuses the android
            // source" should be true by construction, not by call-site
            // convention.
            if source == "android" {
                emit(json, ["status": "error",
                            "message": "verb \(verb) does not accept --source android"])
                exit(2)
            }
            // Tee to <logs>/install.log as well as stderr. A helper-driven
            // install is headless by definition, so without the file its
            // account of a failure lives only in whatever the caller happened
            // to redirect stderr to.
            let stderrLog: (String) -> Void = {
                FileHandle.standardError.write(Data(($0 + "\n").utf8))
                InstallLog.write("[helper] " + $0)
            }
            // The payload ships beside this helper (Contents/bin/...), so our
            // own path is the most reliable source -- the same reason
            // locateRuntime() resolves by path rather than by bundle id.
            let myContents = URL(fileURLWithPath: CommandLine.arguments[0])
                .resolvingSymlinksInPath()
                .deletingLastPathComponent()      // Contents/bin
                .deletingLastPathComponent()      // Contents
            let extra = [myContents]

            if verb == "runtime-status" {
                let st = RuntimeInstall.state(extraSources: extra)
                emit(json, ["status": "success",
                            "installed": st.installed ? "true" : "false",
                            "stale": st.isStale ? "true" : "false",
                            "removedByUser": st.removedByUser ? "true" : "false",
                            "sourceAvailable": st.sourceAvailable ? "true" : "false",
                            "bundleRuntimeExecutable": st.bundleRuntimeExecutable ? "true" : "false",
                            "stagedRoot": RuntimeInstall.stagedRoot.path,
                            "stagedBytes": String(st.stagedBytes),
                            "buildId": (st.stagedBuildId ?? "").replacingOccurrences(of: "\n", with: " "),
                            "summary": st.summary])
                if !json { print(st.summary) }
                exit(st.installed ? 0 : 7)
            }

            if verb == "runtime-install" {
                let r = RuntimeInstall.install(extraSources: extra, log: stderrLog)
                emit(json, ["status": r.isOk ? "success" : "error", "message": r.message])
                if !json && r.isOk { print(RuntimeInstall.stagedRoot.path) }
                exit(r.isOk ? 0 : 5)
            }

            // runtime-uninstall
            //
            // --dry-run prints the bulk-uninstall TARGET LIST and removes
            // nothing at all. It exists because the destructive path cannot be
            // exercised end to end on a machine holding the owner's real
            // library, and "we never test it" is not an acceptable answer for
            // the most destructive action in the product. The gate asserts the
            // targeting -- throwaway present, Mac.apk.app absent, every target
            // independently re-verified as org.macapk.app.* -- which is where
            // a bug here would actually live. The per-app REMOVAL primitive is
            // separately covered by A10b.
            if dryRun {
                guard let rt = locateRuntime() else {
                    emit(json, ["status": "error", "message": "no runtime to resolve paths"])
                    exit(5)
                }
                var hooks = InstallPipeline.Hooks()
                hooks.log = stderrLog
                BundleGenerator.install(into: &hooks)
                let plan = InstallPipeline(runtimePath: rt,
                                           androidRoot: androidRootFromSettings(),
                                           hooks: hooks).planUninstallAllApps()
                // Plain text, one target per line with a type prefix, so the
                // gate can grep it without a JSON parser.
                for p in plan.packages   { print("package \(p)") }
                for b in plan.bundles    { print("bundle \(b)") }
                for b in plan.refusedBundles { print("refused \(b)") }
                for a in plan.retainedApks   { print("apk \(a)") }
                print("untouched \(plan.untouchedBundleCount)")
                print("removeAllApps \(removeAllApps ? "yes" : "no")")
                exit(0)
            }

            // Refuse while a guest is running. A runtime yanked out from under
            // a live app is a crash the user did not ask for, and the pidfiles
            // already tell us -- so ask rather than race. --force is for the
            // harness, which kills its own guest first.
            let live = RuntimeInstall.runningGuests()
            if !live.isEmpty && !force {
                emit(json, ["status": "error",
                            "message": "these apps are running: " + live.joined(separator: ", ")
                                     + ". Quit them first, or pass --force."])
                exit(5)
            }
            var appsMessage = ""
            if removeAllApps {
                // ONE implementation of "remove an app", driven per package.
                // See InstallPipeline.uninstallAllApps.
                guard let rt = locateRuntime() else {
                    emit(json, ["status": "error",
                                "message": "cannot remove apps without a runtime to resolve paths"])
                    exit(5)
                }
                var hooks = InstallPipeline.Hooks()
                hooks.log = stderrLog
                BundleGenerator.install(into: &hooks)
                let pipeline = InstallPipeline(runtimePath: rt,
                                               androidRoot: androidRootFromSettings(),
                                               hooks: hooks)
                let rep = pipeline.uninstallAllApps()
                appsMessage = "; removed \(rep.removed.count) app(s), "
                            + "\(rep.orphanBundlesRemoved.count) orphan bundle(s), "
                            + "\(rep.retainedApksSwept) retained APK(s)"
                if !rep.isClean {
                    appsMessage += "; PROBLEMS: "
                                 + (rep.failures + rep.refusedBundles).joined(separator: ", ")
                }
            }
            let r = RuntimeInstall.uninstall(extraSources: extra, log: stderrLog)
            emit(json, ["status": r.isOk ? "success" : "error",
                        "message": r.message + appsMessage])
            exit(r.isOk ? 0 : 5)
        default:
            break
        }

        // AN ANDROID-INITIATED REQUEST MUST PASS A REAL AUTHORIZATION CHECK.
        //
        // This is the one gate between an app running inside our runtime and
        // installing -- or DELETING -- software on the user's Mac. It is OURS,
        // not macOS's, which is exactly why it must be impossible to skip.
        // There is no flag and no env var that reaches past it: `--no-ui` is
        // refused here, and a guest can influence a child process's
        // environment, so anything env-driven would be a permanent hole
        // rather than a dev convenience.
        //
        // Failure of ANY kind -- cancel, wrong password, no biometric hardware,
        // LAContext unavailable to a non-bundled binary -- REFUSES the request.
        // Never fall through to "proceed anyway"; the safe direction is to
        // decline something legitimate, not to allow something unauthorized.
        if source == "android" {
            if noUI {
                emit(json, ["status": "auth_failed",
                            "message": "--no-ui cannot be combined with --source android: an "
                                     + "Android-initiated install or uninstall always requires a human"])
                exit(4)
            }
            // WHAT THE OWNER IS BEING ASKED TO AUTHORIZE -- built per verb,
            // BEFORE the prompt, from data the guest cannot fabricate. The
            // requestor label is display-only text (the runtime sends its own
            // hosted package name; a guest could at most misdescribe ITSELF);
            // the uninstall TARGET is resolved from our registry.
            let who = requestorLabel.isEmpty ? "An Android app" : requestorLabel
            let reason: String
            switch verb {
            case "install":
                reason = "\(who) wants to install \(URL(fileURLWithPath: apk ?? "an app").lastPathComponent)"
            case "uninstall":
                // A guest uninstall is SINGLE-PACKAGE and FULL-DELETE only:
                // AOSP's public uninstall passes flags=0 (data removed;
                // DELETE_KEEP_DATA is @hide and DELETE_PACKAGES-gated), and
                // scoping flags would let the asking app narrow the deletion
                // to something other than what the prompt describes. Refuse
                // the combination rather than ignore it.
                if dirName != nil || !deleteData || removeAllApps {
                    emit(json, ["status": "error",
                                "message": "--dir-name/--keep-data/--remove-all-apps cannot be "
                                         + "combined with --source android"])
                    exit(2)
                }
                guard let p = pkg, !p.isEmpty else { usage(); exit(2) }
                // Resolve the target BEFORE prompting, and refuse without a
                // prompt when it is not installed: asking the owner to
                // authorize deleting something that does not exist both
                // trains the approve-anything reflex and leaks nothing --
                // AOSP answers the same request with
                // DELETE_FAILED_INTERNAL_ERROR ("Not removing non-existent
                // package", DeletePackageHelper.java:158).
                let rows = ((try? RegistryStore.shared.load())?.records(forPackage: p) ?? [])
                    .filter { $0.state == AppState.installed }
                guard let rec = rows.first else {
                    emit(json, ["status": "error", "stage": "not-installed",
                                "message": "Not removing non-existent package \(p)"])
                    exit(6)
                }
                // THE PROMPT NAMES THE TARGET. Label + version come from the
                // registry row our own install pipeline wrote (from the APK's
                // real manifest), the package name is spelled out, and the
                // consequence ("and delete its data") is stated -- a prompt
                // that says only "authorize" is not informed consent.
                let label = rec.label.isEmpty ? p : rec.label
                let vers = rec.versionName.isEmpty ? "" : " \(rec.versionName)"
                reason = "\(who) wants to uninstall \u{201C}\(label)\(vers)\u{201D} (\(p)) "
                       + "and delete its data from this Mac"
            default:
                // No other verb accepts the android source; in particular the
                // runtime-* verbs (handled above) and any future verb must opt
                // in deliberately rather than inherit an install-shaped prompt.
                emit(json, ["status": "error",
                            "message": "verb \(verb) does not accept --source android"])
                exit(2)
            }
            let ctx = LAContext()
            ctx.localizedCancelTitle = verb == "uninstall" ? "Don't Uninstall" : "Don't Install"
            var authError: NSError?
            // deviceOwnerAuthentication = Touch ID with a password fallback, so
            // a Mac without a sensor still gets a real check rather than a pass.
            guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &authError) else {
                emit(json, ["status": "auth_failed",
                            "message": "no authorization method available on this Mac "
                                     + "(\(authError?.localizedDescription ?? "unknown")); "
                                     + "refusing an app-initiated \(verb)"])
                exit(4)
            }
            let sem = DispatchSemaphore(value: 0)
            var ok = false
            var failure: String = ""
            ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { granted, err in
                ok = granted
                failure = err?.localizedDescription ?? ""
                sem.signal()
            }
            // BOUNDED WAIT. `evaluatePolicy` has no timeout of its own, so an
            // unbounded `sem.wait()` here hangs the helper FOREVER whenever the
            // prompt is never answered: the human walks away, the Mac locks, the
            // prompt is dismissed by something else, or the process has no GUI
            // session able to present it at all. Measured 2026-08-19: helper pid
            // 82729 sat 67 minutes in `LAClient evaluatePolicy` blocked on an XPC
            // reply that never came, main thread parked on this very semaphore.
            //
            // The cost is not just a stuck process. A helper that never exits
            // never writes a terminal status, so the guest's PackageInstaller
            // session never resolves and the STORE's row is stranded mid-install
            // (Aurora sat at INSTALLING/100 with nothing able to advance it).
            // That is the same "installing forever" the owner has been chasing,
            // reached by a second route.
            //
            // The window is deliberately generous: the comment below is right
            // that a human staring at a Touch ID prompt can legitimately take
            // minutes, and a timeout that fires on a slow human would be its own
            // bug. This bounds the pathological case only.
            //
            // On expiry, invalidate() dismisses any prompt still on screen and
            // makes the callback fire with an error; we deliberately do NOT wait
            // for it, because the whole point is that it may never come. Failing
            // through the SAME auth_failed/exit(4) path a declined prompt uses is
            // what makes this terminal downstream: that path is already proven to
            // move a store's row off INSTALLING.
            let authDeadline: DispatchTime = .now() + .seconds(300)
            if sem.wait(timeout: authDeadline) == .timedOut {
                ctx.invalidate()
                emit(json, ["status": "auth_failed",
                            "message": "no response to the authorization prompt after "
                                     + "5 minutes; refusing the app-initiated \(verb)"])
                exit(4)
            }
            guard ok else {
                emit(json, ["status": "auth_failed",
                            "message": failure.isEmpty ? "authorization declined" : failure])
                exit(4)
            }
            FileHandle.standardError.write(Data("[helper] authorized by device owner\n".utf8))
            if verb == "install" {
                // THE FIRST progress report of an android-initiated install, and
                // the one that matters most: everything before this line was a
                // human staring at a Touch ID prompt, which can be minutes. A
                // store watching the session sees its bar leave the staging band
                // the moment the human answers, instead of after the whole extract.
                emitProgress("authorized", 0.05)
            }
        }

        let runtime = locateRuntime()
        guard let runtimePath = runtime else {
            emit(json, ["status": "error", "message": "macapk-runtime not found next to this helper"])
            exit(5)
        }

        let root = androidRootFromSettings()
        var hooks = InstallPipeline.Hooks()
        hooks.log = { line in
            FileHandle.standardError.write(Data((line + "\n").utf8))
            InstallLog.write("[helper] " + line)
        }
        // Stage reports go out as a MACHINE-READABLE stderr line, separate from
        // the human log above. The runtime's session backend parses exactly
        // this shape (PackageInstallerBackend.HELPER_PROGRESS) to move a
        // committed session's internal progress; scraping the prose log instead
        // would break the first time someone rewords a message.
        hooks.progress = { stage, fraction in emitProgress(stage, fraction) }
        BundleGenerator.install(into: &hooks)

        switch verb {
        case "install":
            guard let apkPath = apk else { usage(); exit(2) }
            let url = URL(fileURLWithPath: apkPath)
            guard FileManager.default.fileExists(atPath: url.path) else {
                emit(json, ["status": "error", "message": "no such file: \(url.path)"])
                exit(5)
            }
            let pipeline = InstallPipeline(runtimePath: runtimePath, androidRoot: root, hooks: hooks)
            // `interactive: false` skips the confirm SEAM inside the pipeline.
            // That is the whole point of this binary: a headless caller has no
            // one to answer a dialog. It does not skip anything security
            // relevant -- the android source is refused above.
            _ = noUI
            let src: InstallSource = (source == "android")
                ? .android(requestorLabel: requestorLabel.isEmpty ? "an app" : requestorLabel)
                : .gui
            let out = pipeline.run(InstallRequest(sourceFile: url, source: src, interactive: false))
            switch out.status {
            case .success:
                emit(json, ["status": "success",
                            "package": out.package ?? "",
                            "versionCode": String(out.versionCode ?? 0),
                            "wasUpdate": out.wasUpdate ? "true" : "false",
                            "bundlePath": out.bundlePath ?? ""])
                // Plain mode prints ONLY the bundle path on stdout, so a
                // harness can capture it with $(...) and nothing else.
                if !json, let b = out.bundlePath { print(b) }
                exit(0)
            case .cancelled:
                emit(json, ["status": "cancelled", "message": ""]); exit(3)
            case .authFailed:
                emit(json, ["status": "auth_failed", "message": ""]); exit(4)
            case .failed(let stage, let message):
                emit(json, ["status": "error", "stage": stage, "message": message])
                // Stage "invalid-apk" (InstallPipeline's contract string for
                // "the DEX cannot be parsed") is this binary's documented
                // exit 6, "unsupported input" -- the session backend turns
                // that into STATUS_FAILURE_INVALID / INSTALL_FAILED_INVALID_APK,
                // matching what a device answers for an unparseable APK.
                // Everything else stays the generic exit 5.
                exit(stage == "invalid-apk" ? 6 : 5)
            }

        // --dir-name scopes the removal to ONE installed version, which is the
        // exact call shape the control panel's tile menu makes (one .app per
        // version, so a tile must not take its sibling with it). Exposed here
        // because otherwise that call shape -- new, and destructive -- would
        // have no headless driver and therefore no gate row.
        case "uninstall":
            guard let p = pkg else { usage(); exit(2) }
            let pipeline = InstallPipeline(runtimePath: runtimePath, androidRoot: root, hooks: hooks)
            // Snapshot the rows BEFORE the removal so the data-removal claim
            // can be VERIFIED afterwards instead of assumed -- the runtime
            // builds the broadcast's EXTRA_DATA_REMOVED from the dataRemoved
            // field below, and "claim less" is the rule when the evidence is
            // incomplete.
            let before = (try? RegistryStore.shared.load())?.records(forPackage: p) ?? []
            let st = pipeline.uninstall(package: p, dirName: dirName, deleteData: deleteData)
            if case .success = st {
                // dataRemoved = every place this package's data could live is
                // verifiably gone: each pre-uninstall bundle (the data lives
                // INSIDE Contents/android since the bundle cutover), each
                // shared-root data dir, and no parked KeptData tree. A
                // --keep-data uninstall parked the data on purpose, so it
                // reports false without looking.
                var dataRemoved = deleteData
                if deleteData {
                    let fm = FileManager.default
                    for r in before {
                        if let bp = r.bundlePath, fm.fileExists(atPath: bp) { dataRemoved = false }
                        if !r.dirName.isEmpty {
                            let dd = root.appendingPathComponent("data/data", isDirectory: true)
                                         .appendingPathComponent(r.dirName, isDirectory: true)
                            if fm.fileExists(atPath: dd.path) { dataRemoved = false }
                        }
                    }
                    if !KeptData.entries(forPackage: p).isEmpty { dataRemoved = false }
                }
                emit(json, ["status": "success", "package": p,
                            "dataRemoved": dataRemoved ? "true" : "false"])
                exit(0)
            }
            emit(json, ["status": "error", "package": p, "message": "\(st)"]); exit(5)

        // registry-vs-disk reconciliation, headless.
        //
        // WHY THE VERB EXISTS: reconcile() is the code that decides what
        // happens to a row whose .app the user dragged to the Trash, and its
        // load-bearing property is a NEGATIVE one -- the row must be KEPT and
        // reported, never deleted, because "trashed" and "moved, or replaced
        // by a rebuild" are indistinguishable from here and the second reading
        // makes deletion unrecoverable harm. A property that says "this must
        // NOT happen" is exactly the kind that rots un-noticed, and until now
        // the only way to exercise it was to click through the GUI. Now the
        // install gate can assert it (A20_RECONCILE_KEEPS_ROW).
        //
        // --dry-run reports and changes NOTHING (not even the bundlePath
        // clearing the GUI applies), so the gate can run it against the
        // owner's real library and read the row back afterwards. Without the
        // flag it behaves exactly as the GUI's own call site does.
        case "reconcile":
            let pipeline = InstallPipeline(runtimePath: runtimePath, androidRoot: root, hooks: hooks)
            let findings = pipeline.reconcile(apply: !dryRun)
            // Plain text, one record per line with a type prefix, so the gate
            // greps it without a JSON parser (same shape as the
            // runtime-uninstall --dry-run plan above). Suppressed under --json
            // so stdout stays a single parseable object, exactly as `install`
            // suppresses its bundle-path line.
            let fm = FileManager.default
            var rowCount = 0
            if let reg = try? RegistryStore.shared.load() {
                rowCount = reg.apps.count
                if !json {
                    for r in reg.apps {
                        let bundle = r.bundlePath.map {
                            fm.fileExists(atPath: $0) ? "present" : "missing"
                        } ?? "none"
                        print("row \(r.dirName) package=\(r.package) state=\(r.state)"
                              + " bundle=\(bundle) label=\(r.label)")
                    }
                }
            }
            if !json {
                for f in findings {
                    print("finding \(f.problem.rawValue) \(f.dirName) package=\(f.package)"
                          + " :: \(f.detail)")
                }
                print("rows \(rowCount)")
                print("findings \(findings.count)")
                print("applied \(dryRun ? "no" : "yes")")
            }
            emit(json, ["status": "success",
                        "rows": String(rowCount),
                        "findings": String(findings.count),
                        "bundleMissing": String(findings.filter {
                            $0.problem == .bundleMissing }.count),
                        "applied": dryRun ? "false" : "true"])
            exit(0)

        default:
            usage(); exit(2)
        }
    }

    /// The runtime ships beside this helper inside Mac.apk.app. Resolving
    /// relative to our OWN binary rather than by bundle id means the helper
    /// always drives the runtime it was built with -- the same reason the
    /// per-app stub resolves by path first.
    static func locateRuntime() -> String? {
        let me = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        let bin = me.deletingLastPathComponent()                 // Contents/bin
        let candidates = [
            bin.deletingLastPathComponent().appendingPathComponent("Resources/macapk-runtime"),
            bin.appendingPathComponent("macapk-runtime"),
        ]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0.path) }?.path
    }

    /// Honour the androidRoot the launcher persisted, so a helper-driven
    /// install lands where a GUI-driven one would. Only used as the FALLBACK
    /// root now that every app extracts into its own bundle.
    static func androidRootFromSettings() -> URL {
        if let s = RegistryStore.shared.setting("androidRoot"), !s.isEmpty {
            return URL(fileURLWithPath: s)
        }
        // Same default as the runtime (ResolveAndroidRootDir) and the launcher.
        // Moved out of ~/Documents/android 2026-08-12; see apk_unpacker.cpp.
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Caches/MacApk/scratch")
    }

    /// One FINISHED stage of an install, on stderr, in the one shape the
    /// runtime parses: `[helper] progress <fraction> <stage>`.
    ///
    /// stderr and not stdout on purpose -- plain (non-`--json`) mode reserves
    /// stdout for the bundle path so a shell harness can capture it with
    /// `$(...)` and nothing else. The runtime redirects stderr into the same
    /// stream it already reads, so it sees these either way.
    ///
    /// It carries no authority and no secrets: it is a number and a stage name
    /// describing work that has already happened, readable only by the process
    /// that spawned this helper.
    static func emitProgress(_ stage: String, _ fraction: Double) {
        let f = max(0.0, min(1.0, fraction))
        let safe = stage.isEmpty ? "stage"
            : String(stage.unicodeScalars.filter {
                CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-"
              })
        FileHandle.standardError.write(
            Data(String(format: "[helper] progress %.4f %@\n", f,
                        safe.isEmpty ? "stage" : safe).utf8))
    }

    static func emit(_ json: Bool, _ fields: [String: String]) {
        guard json else {
            if let m = fields["message"], !m.isEmpty {
                FileHandle.standardError.write(Data((m + "\n").utf8))
            }
            return
        }
        let body = fields.keys.sorted().map { k in
            "\"\(k)\":\"\(fields[k]!.replacingOccurrences(of: "\"", with: "\\\""))\""
        }.joined(separator: ",")
        print("{\(body)}")
    }

    static func usage() {
        FileHandle.standardError.write(Data("""
        macapk-installhelper install   --apk <path> [--source gui|finder|android]
                                       [--requestor-label <name>] [--json]
        macapk-installhelper uninstall --package <pkg> [--dir-name <pkg_ver>]
                                       [--keep-data] [--source gui|android]
                                       [--requestor-label <name>] [--json]
        macapk-installhelper reconcile [--dry-run] [--json]
        macapk-installhelper special-access --op android:manage_external_storage|
                                            android:manage_media --source android [--json]
        macapk-installhelper special-access --identity-only [--json]
        macapk-installhelper appops-selftest --dir <new empty dir> [--json]
        macapk-installhelper runtime-permission --source android --level <api>
                                            (--prompt basic|select|select_more|
                                             supergroup_pre_q|supergroup_q_to_s|location |
                                             --auto)
                                            --perm <p>... [--member <p>...]
                                            [--partial <p>...] [--json]
        macapk-installhelper app-details --source android --level <api>
                                         [--group <name>=<p>,<p>...]... [--op <op>]...
                                         [--json]
        macapk-installhelper perms-selftest --dir <new empty dir> [--json]

        macapk-installhelper runtime-status    [--json]
        macapk-installhelper runtime-install   [--json]
        macapk-installhelper runtime-uninstall [--remove-all-apps] [--force]
                                              [--dry-run] [--json]

        Headless, synchronous. Prints the generated bundle path on stdout when
        it succeeds (or a one-line JSON object with --json).

        The runtime-* verbs manage the SHARED runtime under
        ~/Library/Application Support/Mac.apk/runtime/. runtime-uninstall
        removes ONLY that: the control panel, every installed app, their data
        and the registry are untouched, and the apps work again after
        runtime-install with no reinstall. --remove-all-apps additionally
        removes every app and its data (the default-off checkbox in the GUI
        dialog) and cannot be undone -- so --dry-run prints the target list and
        removes nothing, which is how debug/installtest/run.sh checks the
        targeting on a machine holding real apps. runtime-status exits 7 when
        the runtime is not installed, 0 when it is.

        `reconcile` compares the registry against the disk and prints one
        `row ...` line per registry entry followed by one `finding ...` line
        per gap (bundleMissing / dataDirMissing / apkMissing / stuckInstalling).
        It NEVER removes a row: a missing .app is reported, not uninstalled,
        because "trashed" and "moved or replaced by a rebuild" are
        indistinguishable and deleting an app's saves on the wrong reading is
        unrecoverable. Without --dry-run it also clears the stale bundlePath
        (what the GUI does on launch); with --dry-run it changes nothing at
        all, which is how the gate exercises it on a real library.

        `--source android` REQUIRES device-owner authorization (Touch ID, with a
        password fallback) and prompts for it -- for `install` AND for
        `uninstall`. There is no way to skip that: --no-ui is refused for that
        source, and any failure -- cancel, wrong password, no method available
        -- declines the request rather than proceeding. --requestor-label names
        the asking app in the prompt. An android-source `uninstall` is
        additionally single-package and full-delete only (--dir-name,
        --keep-data and --remove-all-apps are refused), resolves the target
        from the registry BEFORE prompting (a not-installed package is refused
        with no prompt), and the prompt names the target's label, version and
        package so the owner authorizes a specific, spelled-out deletion.

        `special-access` is Android's Settings switch for "All files access"
        (android:manage_external_storage) or "Media management"
        (android:manage_media). It is run BY the Mac.apk runtime, and it takes
        no app identity from its arguments: the asking app is its PARENT
        process, which must be the Mac.apk runtime (macapk-runtime, or that
        app's named copy of it) whose --android-root is an
        installed app's bundle and which holds that app's run/<dirName>.pid
        lock. The question is a macOS system alert whose default button
        leaves the current state alone ("Don't Allow" while not allowed,
        "Allow" while allowed); the answer is recorded, signed, in
        ~/Library/Application Support/Mac.apk/appops/app-ops.json. Only
        `--source android` is accepted and --no-ui is refused. Exit codes:
        0 allowed, 3 not allowed, 4 no answer within 5 minutes (nothing
        changed), 5 identity refused or the store could not be written,
        2 usage. `--identity-only` resolves and prints the caller's identity
        and changes nothing (no alert, no write); it is how
        debug/helper_identity_gate.sh tests the identity rules.

        `appops-selftest` signs fixed test entries into a NEW directory (it
        refuses the real store and any directory that already holds a key), so
        debug/allfiles_gate.sh can prove this signer and the runtime's verifier
        agree byte for byte, and that the store's keying survives an update
        and an uninstall of a sibling version. It grants nothing to any app.

        `runtime-permission` is Android's runtime-permission dialog for the
        storage permissions (READ_MEDIA_IMAGES / VIDEO / AUDIO /
        VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION, READ / WRITE_EXTERNAL_STORAGE),
        asked by the runtime when an app calls requestPermissions. Same caller
        identity rule and the same store as special-access. The question is a
        system alert worded from the permissions themselves; its default
        (Return) button is "Don't allow" (the current state), and a second
        "Don't allow" for the same group is recorded as don't-ask-again, as
        Android records it. --auto grants with no alert, and only into a group
        that already holds a granted permission (Android's silent grant); it is
        refused otherwise. The select / select_more prompts and --partial need
        a photo picker Mac.apk does not have yet, and are refused (usage) until
        it does. --prompt location asks for ACCESS_FINE / ACCESS_COARSE_LOCATION
        (the per-app grant; macOS Location Services is a separate, Mac.apk-wide
        gate), and its --partial ACCESS_COARSE_LOCATION offers "Only
        approximate". Exit codes: 0 allowed (or approximate), 3 not allowed, 4 no answer within 5
        minutes (nothing changed), 5 identity refused or store error, 6 --auto
        refused, 2 usage.

        `app-details` is Settings' "App info" for the calling app: one alert
        with a check box per storage permission group (--group) and per special
        access (--op), each showing the stored state. "Done" (the default
        button) applies what was changed, "Cancel" changes nothing. Exit codes:
        0 answered, 4 no answer, 5 identity refused or store error, 2 usage.

        `perms-selftest` is appops-selftest for the runtime-permission half: it
        signs fixed entries into a NEW directory and checks the answer rules,
        so debug/mediaperm_gate.sh can prove the Swift and Java sides agree.

        """.utf8))
    }
}

// MARK: - special-access (Android "All files access" / "Media management", stage S1)

/// The helper side of Android's special-app-access Settings screens --
/// docs/shared-storage-real-mac-folders-design_2026-09-25.md, S1 and section 3a.
///
/// On a device these screens are Settings activities that toggle an app-op
/// (ManageExternalStorageDetails.java:111-114, android14:
/// `setUidMode(OP_MANAGE_EXTERNAL_STORAGE, uid, newState ? MODE_ALLOWED :
/// MODE_ERRORED)`), and only a holder of MANAGE_APP_OPS_MODES -- never a
/// third-party app -- can write that op. Here the screen is THIS process:
///
///  - the asking app is identified from our PARENT process and nothing else
///    (`CallerIdentity`); there is no identity argument to forge;
///  - the question is a CFUserNotification, drawn by a system process
///    (UserNotificationCenter), so the app in the runtime cannot click it
///    without Accessibility / PostEvent rights it does not have. The DEFAULT
///    button (Return) is the one that leaves the switch where it is: "Don't
///    Allow" while it is off, "Allow" while it is on -- entering Settings and
///    pressing Back changes nothing on a device, and a stray Return must not
///    revoke a grant (which would also kill and relaunch the app);
///  - the answer is written, signed, by `AppOpsStore`, with a key the runtime
///    never holds. (The public key sits beside the store, so the signature is
///    binding, not a wall; the wall against guest native code is the shim's
///    block of the directory. See AppOpsStore's header.)
///
/// Either button writes only when it CHANGES something: leaving a Settings
/// screen without moving the switch writes nothing on a device either. No
/// answer within 300 s changes nothing (exit 4).
enum SpecialAccess {

    static func run(op: String?, source: String, noUI: Bool, identityOnly: Bool,
                    json: Bool) -> Int32 {
        if identityOnly {
            // READ-ONLY: resolve and print. The only mode in which the test
            // overrides below are honoured -- it shows nothing and writes
            // nothing, so pointing it at a fixture registry grants nothing.
            // debug/helper_identity_gate.sh.
            let env = ProcessInfo.processInfo.environment
            let reg = env["MACAPK_IDENTITY_TEST_REGISTRY"].flatMap { $0.isEmpty ? nil : $0 }
                .map { URL(fileURLWithPath: $0) } ?? Paths.registryURL
            let run = env["MACAPK_IDENTITY_TEST_RUNDIR"].flatMap { $0.isEmpty ? nil : $0 }
                .map { URL(fileURLWithPath: $0, isDirectory: true) } ?? Paths.runDir
            switch CallerIdentity.resolve(parent: getppid(), registryURL: reg, runDir: run) {
            case .success(let c):
                InstallHelperMain.emit(json, [
                    "status": "success",
                    "dirName": c.dirName, "package": c.package, "label": c.label,
                    "installedAt": c.installedAt, "bundlePath": c.bundlePath,
                    "androidRoot": c.androidRoot, "pidfile": c.pidfile,
                    "parentPid": String(c.parentPid), "parentExe": c.parentExe])
                if !json { print(c.dirName) }
                return 0
            case .failure(let why):
                InstallHelperMain.emit(json, ["status": "identity_refused",
                                              "message": why.message])
                return 5
            }
        }
        // EXPLICIT opt-in to the android source: the runtime is the only
        // caller (PackageInstallerBackend.runSpecialAccessHelper passes it as a
        // compile-time constant). The control panel writes through AppOpsStore
        // directly and never reaches this verb.
        guard source == "android" else {
            InstallHelperMain.emit(json, ["status": "error",
                "message": "special-access is only asked for by an app (--source android)"])
            return 2
        }
        // The whole point is a human's answer. No flag or env var skips it.
        guard !noUI else {
            InstallHelperMain.emit(json, ["status": "error",
                "message": "--no-ui cannot be combined with special-access: the grant is the"
                         + " user's answer to the alert"])
            return 2
        }
        guard let op = op, AppOpsStore.knownOps.contains(op) else {
            InstallHelperMain.emit(json, ["status": "error",
                "message": "--op must be \(AppOpsStore.manageExternalStorage) or "
                         + "\(AppOpsStore.manageMedia)"])
            return 2
        }
        let caller: CallerIdentity.Resolved
        switch CallerIdentity.resolve(parent: getppid(), registryURL: Paths.registryURL,
                                      runDir: Paths.runDir) {
        case .success(let c): caller = c
        case .failure(let why):
            InstallHelperMain.emit(json, ["status": "identity_refused", "message": why.message])
            return 5
        }
        let id = AppOpsStore.Identity(dirName: caller.dirName, package: caller.package,
                                      installedAt: caller.installedAt)
        let current = AppOpsStore.mode(for: id, op: op)
        FileHandle.standardError.write(Data(
            "[helper] special-access \(op) for \(caller.dirName): currently \(current?.rawValue ?? "not set")\n".utf8))
        let icon = URL(fileURLWithPath: caller.bundlePath)
            .appendingPathComponent("Contents/Resources/AppIcon.icns")
        let answer = ask(label: caller.label.isEmpty ? caller.package : caller.label,
                         op: op, current: current,
                         iconURL: FileManager.default.fileExists(atPath: icon.path) ? icon : nil)
        switch answer {
        case .allow:
            do {
                if current != .allow {
                    try AppOpsStore.setMode(.allow, op: op, for: id, by: "helper")
                }
            } catch {
                InstallHelperMain.emit(json, ["status": "error",
                    "message": "could not record the grant: \(error.localizedDescription)"])
                return 5
            }
            InstallHelperMain.emit(json, ["status": "allowed", "op": op, "dirName": id.dirName])
            return 0
        case .dontAllow:
            if current == .allow {
                do {
                    try AppOpsStore.setMode(.deny, op: op, for: id, by: "helper")
                } catch {
                    InstallHelperMain.emit(json, ["status": "error",
                        "message": "could not record the change: \(error.localizedDescription)"])
                    return 5
                }
            }
            InstallHelperMain.emit(json, ["status": "denied", "op": op, "dirName": id.dirName])
            return 3
        case .noAnswer:
            InstallHelperMain.emit(json, ["status": "timeout", "op": op,
                "message": "no answer to the alert within 5 minutes; nothing changed"])
            return 4
        }
    }

    enum Answer { case allow, dontAllow, noAnswer }

    /// The alert. Title and description are AOSP Settings' own strings
    /// (packages/apps/Settings res/values/strings.xml android14 :9174-9197:
    /// manage_external_storage_title, allow_manage_external_storage_description,
    /// media_management_apps_toggle_label, media_management_apps_description).
    /// CFUserNotificationCreate/ReceiveResponse and DisplayAlert are not
    /// deprecated in the macOS 26 SDK (CFUserNotification.h). Bounded at 300 s,
    /// the same bound the install prompt uses; a timeout is kCFUserNotification
    /// CancelResponse and changes nothing.
    static func ask(label: String, op: String, current: AppOpsStore.Mode?,
                    iconURL: URL?) -> Answer {
        let isFiles = (op == AppOpsStore.manageExternalStorage)
        let header = isFiles
            ? "Allow \u{201C}\(label)\u{201D} access to manage all files?"
            : "Allow \u{201C}\(label)\u{201D} to manage media?"
        let aosp = isFiles
            ? "Allow this app to read, modify and delete all files on this device or any"
              + " connected storage volumes. If granted, app may access files without your"
              + " explicit knowledge."
            : "If allowed, this app can modify or delete media files created with other apps"
              + " without asking you. App must have permission to access files and media."
        let state: String
        switch current {
        case .some(.allow): state = "Currently allowed."
        case .some(.deny):  state = "Currently not allowed."
        case .none:         state = "Currently not allowed."
        }
        let body = aosp + "\n\n" + (isFiles ? "This is Android\u{2019}s \u{201C}All files access\u{201D}"
                                           : "This is Android\u{2019}s \u{201C}Media management\u{201D}")
            + " setting for \(label), kept by Mac.apk. " + state
            + " You can revoke it later from the Mac.apk control panel."
        // The default (Return) button keeps the current state; see the enum's
        // header. The alternate is the change the user would make.
        let keepAllowed = (current == .allow)
        let (defaultTitle, alternateTitle) = keepAllowed
            ? ("Allow", "Don\u{2019}t Allow")
            : ("Don\u{2019}t Allow", "Allow")
        var flags: CFOptionFlags = 0
        let rc = CFUserNotificationDisplayAlert(
            300, CFOptionFlags(kCFUserNotificationNoteAlertLevel),
            iconURL as CFURL?, nil, nil,
            header as CFString, body as CFString,
            defaultTitle as CFString, alternateTitle as CFString, nil, &flags)
        guard rc == 0 else { return .noAnswer }
        return answer(responseFlags: flags, keepAllowed: keepAllowed)
    }

    /// Which answer a CFUserNotification response means, given which title sat
    /// on the default button. Separate so the mapping is testable without an
    /// alert (appops-selftest checks both layouts).
    static func answer(responseFlags flags: CFOptionFlags, keepAllowed: Bool) -> Answer {
        switch flags & 0x3 {
        case CFOptionFlags(kCFUserNotificationDefaultResponse):
            return keepAllowed ? .allow : .dontAllow
        case CFOptionFlags(kCFUserNotificationAlternateResponse):
            return keepAllowed ? .dontAllow : .allow
        default:
            return .noAnswer
        }
    }

    /// `appops-selftest --dir <dir>`: sign one FIXED entry into a NEW directory
    /// so the offline gate can prove this signer (CryptoKit) and the runtime's
    /// verifier (java.security SHA256withECDSA) agree byte for byte on the
    /// canonical payload. Refuses the real store directory (by resolved path,
    /// case-insensitively, as APFS compares) and any directory that already
    /// holds a key, and only ever writes the dirName ".macapk-selftest", which
    /// no installed app can have (a package name cannot start with a dot). So
    /// it grants nothing to anything.
    static func selftest(dir: String?, json: Bool) -> Int32 {
        guard let dir = dir, !dir.isEmpty else {
            InstallHelperMain.emit(json, ["status": "error", "message": "--dir is required"])
            return 2
        }
        let d = URL(fileURLWithPath: dir, isDirectory: true).standardizedFileURL
        let real = AppOpsStore.defaultDir.resolvingSymlinksInPath().path.lowercased()
        if d.resolvingSymlinksInPath().path.lowercased() == real
            || d.path.lowercased() == AppOpsStore.defaultDir.path.lowercased() {
            InstallHelperMain.emit(json, ["status": "error",
                                          "message": "refusing the real special-access store"])
            return 2
        }
        if FileManager.default.fileExists(atPath: AppOpsStore.keyURL(d).path) {
            InstallHelperMain.emit(json, ["status": "error",
                "message": "\(d.path) already holds a signing key; use a new directory"])
            return 2
        }
        let filesOp = AppOpsStore.manageExternalStorage, mediaOp = AppOpsStore.manageMedia
        let id = AppOpsStore.Identity(dirName: ".macapk-selftest", package: "org.macapk.selftest",
                                      installedAt: "2026-01-01T00:00:00Z")
        // A second, throwaway package for the keying checks below; it is
        // removed again, so the directory ends with the one fixed entry the
        // Java side reads.
        let v1 = AppOpsStore.Identity(dirName: ".macapk-selftest-b_1.0",
                                      package: "org.macapk.selftest.b",
                                      installedAt: "2026-02-02T00:00:00Z")
        let v2 = AppOpsStore.Identity(dirName: ".macapk-selftest-b_2.0",
                                      package: v1.package, installedAt: v1.installedAt)
        let reinstall = AppOpsStore.Identity(dirName: v2.dirName, package: v1.package,
                                             installedAt: "2026-03-03T00:00:00Z")
        var checks: [String: String] = [:]
        do {
            try AppOpsStore.setMode(.allow, op: filesOp, for: id, by: "selftest", in: d)
            try AppOpsStore.setMode(.deny, op: mediaOp, for: id, by: "selftest", in: d)
            try AppOpsStore.setMode(.allow, op: filesOp, for: v1, by: "selftest", in: d)
        } catch {
            InstallHelperMain.emit(json, ["status": "error", "message": error.localizedDescription])
            return 5
        }
        func yes(_ b: Bool) -> String { b ? "yes" : "no" }
        // An in-place update: new dirName, same package + installedAt.
        checks["update_keeps_grant"] = yes(AppOpsStore.mode(for: v2, op: filesOp, in: d) == .allow)
        checks["panel_sees_update"] = yes(AppOpsStore.allowedOps(for: v2, in: d).contains(filesOp))
        // A reinstall: new installedAt.
        checks["reinstall_starts_clean"] = yes(AppOpsStore.mode(for: reinstall, op: filesOp, in: d) == nil)
        // Uninstalling one of two side-by-side rows that share the grant keeps it...
        AppOpsStore.removeEntries([v1], keeping: [v2], in: d)
        checks["sibling_uninstall_keeps_grant"] = yes(AppOpsStore.mode(for: v2, op: filesOp, in: d) == .allow)
        // ...and uninstalling the last one removes it.
        AppOpsStore.removeEntries([v2], keeping: [], in: d)
        checks["last_uninstall_removes_grant"] = yes(AppOpsStore.mode(for: v2, op: filesOp, in: d) == nil)
        // The alert's button mapping: Return keeps the current state.
        let def = CFOptionFlags(kCFUserNotificationDefaultResponse)
        let alt = CFOptionFlags(kCFUserNotificationAlternateResponse)
        checks["return_keeps_off"] = yes(answer(responseFlags: def, keepAllowed: false) == .dontAllow
                                         && answer(responseFlags: alt, keepAllowed: false) == .allow)
        checks["return_keeps_on"] = yes(answer(responseFlags: def, keepAllowed: true) == .allow
                                        && answer(responseFlags: alt, keepAllowed: true) == .dontAllow)
        let files = AppOpsStore.mode(for: id, op: filesOp, in: d)
        let media = AppOpsStore.mode(for: id, op: mediaOp, in: d)
        let ok = files == .allow && media == .deny && !checks.values.contains("no")
        var out: [String: String] = ["status": ok ? "success" : "error",
                                     "dir": d.path, "key": id.key,
                                     "manage_external_storage": files?.rawValue ?? "none",
                                     "manage_media": media?.rawValue ?? "none"]
        for (k, v) in checks { out[k] = v }
        InstallHelperMain.emit(json, out)
        return ok ? 0 : 5
    }
}

// MARK: - CallerIdentity (who is asking: our parent process)

/// Who is asking, derived ONLY from the parent process -- never from
/// arguments, never from the environment (a guest controls both for a child
/// it spawns). Every step must hold, or the request is refused:
///
///  1. the parent (getppid) is alive and its executable is `macapk-runtime`
///     (proc_pidpath resolves the stub's exec link to the real binary, the same
///     test RuntimeInstall.runningGuests uses), or an app's named copy of it
///     (NamedProcess, MACAPK_NAMED_PROCESS) -- and then only the copy the
///     resolved app's own bundle execs (checked after step 3), so one app's
///     copy cannot speak for another;
///  2. its argv (sysctl KERN_PROCARGS2) carries `--android-root <path>` -- the
///     per-app stub always execs the runtime with it;
///  3. exactly one INSTALLED registry row has a bundle whose Contents/android
///     IS that root (both realpath'd);
///  4. that row's run/<dirName>.pid (or the pre-dirName <package>.pid) is
///     flock-HELD and the PARENT holds it open (PROC_PIDLISTFDS +
///     PROC_PIDFDVNODEPATHINFO): the stub opens it without O_CLOEXEC and
///     execs the runtime, so a genuine runtime owns exactly that fd.
///
/// A guest can spawn processes, so this is not a boundary against a guest
/// that fakes a whole runtime (a binary named macapk-runtime, the argv, the
/// lock of an app that is NOT running) -- but what it gets for that is an
/// alert, naming a different installed app, that the USER must answer.
/// Nothing it can do makes the alert answer itself.
enum CallerIdentity {
    struct Refusal: Error { let message: String }

    struct Resolved {
        let dirName: String
        let package: String
        let label: String
        /// ISO8601.format of the row's installedAt (the store binds to it).
        let installedAt: String
        let bundlePath: String
        let androidRoot: String
        let pidfile: String
        let parentPid: pid_t
        let parentExe: String
    }

    static func resolve(parent pid: pid_t, registryURL: URL,
                        runDir: URL) -> Result<Resolved, Refusal> {
        guard pid > 1 else {
            return .failure(Refusal(message: "no parent process (orphaned helper)"))
        }
        var pbuf = [CChar](repeating: 0, count: 4096)
        guard proc_pidpath(pid, &pbuf, UInt32(pbuf.count)) > 0 else {
            return .failure(Refusal(message: "parent \(pid) is gone"))
        }
        let exe = String(cString: pbuf)
        guard NamedProcess.isRuntime(path: exe) else {
            return .failure(Refusal(message: "parent \(pid) (\(exe)) is not a Mac.apk runtime"))
        }
        guard let argv = procArgs(pid) else {
            return .failure(Refusal(message: "cannot read the arguments of parent \(pid)"))
        }
        var root: String?
        if let i = argv.firstIndex(of: "--android-root"), i + 1 < argv.count {
            root = argv[i + 1]
        }
        guard let rootArg = root, !rootArg.isEmpty else {
            return .failure(Refusal(message: "parent \(pid) was not started with --android-root"
                                  + " (a dev or harness run has no installed identity)"))
        }
        let rootReal = realPath(rootArg)
        guard let rows = registryRows(registryURL) else {
            return .failure(Refusal(message: "registry \(registryURL.path) is unreadable"))
        }
        let matches = rows.filter { r in
            r.state == "installed" && !r.bundlePath.isEmpty && !r.dirName.isEmpty
                && realPath(r.bundlePath + "/Contents/android") == rootReal
        }
        guard matches.count == 1, let row = matches.first else {
            return .failure(Refusal(message: matches.isEmpty
                ? "no installed app's bundle holds android root \(rootArg)"
                : "android root \(rootArg) is claimed by \(matches.count) registry rows"))
        }
        if URL(fileURLWithPath: exe).lastPathComponent != "macapk-runtime",
           !NamedProcess.bundle(row.bundlePath, execs: exe) {
            return .failure(Refusal(message: "parent \(pid) runs \(exe), which is not the named "
                                  + "runtime of \(row.label)'s bundle"))
        }
        let open = Set(openVnodePaths(pid).map(realPath))
        var held: String?
        for name in [row.dirName, row.package] where !name.isEmpty {
            let pf = runDir.appendingPathComponent("\(name).pid").path
            guard FileManager.default.fileExists(atPath: pf) else { continue }
            guard open.contains(realPath(pf)) else { continue }
            let fd = Darwin.open(pf, O_RDONLY | O_CLOEXEC)
            guard fd >= 0 else { continue }
            let free = flock(fd, LOCK_SH | LOCK_NB) == 0
            if free { _ = flock(fd, LOCK_UN) }
            close(fd)
            if !free { held = pf; break }
        }
        guard let pidfile = held else {
            return .failure(Refusal(message: "parent \(pid) does not hold \(row.dirName)'s run lock"
                                  + " in \(runDir.path)"))
        }
        return .success(Resolved(dirName: row.dirName, package: row.package, label: row.label,
                                 installedAt: row.installedAt, bundlePath: row.bundlePath,
                                 androidRoot: rootArg, pidfile: pidfile, parentPid: pid,
                                 parentExe: exe))
    }

    struct Row { let dirName, package, label, installedAt, bundlePath, state: String }

    /// Tolerant, READ-ONLY parse of the registry (v2 object or v1 array). Not
    /// RegistryStore: that reconciles and may take locks in the real support
    /// dir, and this must have no side effects at all.
    static func registryRows(_ url: URL) -> [Row]? {
        guard let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) else { return nil }
        let apps: [[String: Any]]
        if let a = obj as? [[String: Any]] { apps = a }
        else if let o = obj as? [String: Any], let a = o["apps"] as? [[String: Any]] { apps = a }
        else { return nil }
        return apps.map { a in
            let pkg = a["package"] as? String ?? ""
            let inst = ISO8601.format(ISO8601.parse(a["installedAt"] as? String)) ?? ""
            return Row(dirName: a["dirName"] as? String ?? "", package: pkg,
                       label: a["label"] as? String ?? pkg, installedAt: inst,
                       bundlePath: a["bundlePath"] as? String ?? "",
                       // "ok" is the control panel's legacy spelling of
                       // installed (AppState.normalized); reading it as anything
                       // else refused every runtime permission for an app
                       // installed by drag-drop or double-click (issue #11).
                       state: AppState.normalized(a["state"] as? String ?? "installed"))
        }
    }

    static func realPath(_ p: String) -> String {
        guard let r = Darwin.realpath(p, nil) else { return p }
        defer { free(r) }
        return String(cString: r)
    }

    /// argv of `pid` from sysctl KERN_PROCARGS2: an int argc, the exec path,
    /// NUL padding, then argc NUL-terminated strings (xnu kern_sysctl.c
    /// sysctl_procargsx). Same-user processes only, which is all we ask about.
    static func procArgs(_ pid: pid_t) -> [String]? {
        var mib: [Int32] = [CTL_KERN, KERN_PROCARGS2, pid]
        var size = 0
        guard sysctl(&mib, 3, nil, &size, nil, 0) == 0, size > 4 else { return nil }
        var buf = [UInt8](repeating: 0, count: size)
        guard sysctl(&mib, 3, &buf, &size, nil, 0) == 0, size > 4 else { return nil }
        let argc = buf.withUnsafeBytes { Int($0.load(as: Int32.self)) }
        var i = 4
        while i < size && buf[i] != 0 { i += 1 }   // exec path
        while i < size && buf[i] == 0 { i += 1 }   // padding
        var out: [String] = []
        while out.count < argc && i < size {
            var j = i
            while j < size && buf[j] != 0 { j += 1 }
            out.append(String(decoding: buf[i..<j], as: UTF8.self))
            i = j + 1
        }
        return out
    }

    /// The paths of every vnode fd `pid` holds open (libproc).
    static func openVnodePaths(_ pid: pid_t) -> [String] {
        let bytes = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, nil, 0)
        guard bytes > 0 else { return [] }
        let stride = MemoryLayout<proc_fdinfo>.stride
        var fds = [proc_fdinfo](repeating: proc_fdinfo(), count: Int(bytes) / stride + 16)
        let got = fds.withUnsafeMutableBytes { raw -> Int32 in
            proc_pidinfo(pid, PROC_PIDLISTFDS, 0, raw.baseAddress, Int32(raw.count))
        }
        guard got > 0 else { return [] }
        var out: [String] = []
        for fd in fds.prefix(Int(got) / stride)
            where fd.proc_fdtype == UInt32(PROX_FDTYPE_VNODE) {
            var vi = vnode_fdinfowithpath()
            let sz = Int32(MemoryLayout<vnode_fdinfowithpath>.size)
            guard proc_pidfdinfo(pid, fd.proc_fd, PROC_PIDFDVNODEPATHINFO, &vi, sz) == sz
            else { continue }
            let path = withUnsafeBytes(of: &vi.pvip.vip_path) { raw -> String in
                String(cString: raw.bindMemory(to: CChar.self).baseAddress!)
            }
            if !path.isEmpty { out.append(path) }
        }
        return out
    }
}

// MARK: - runtime-permission / app-details (storage runtime permissions, stage S5)

/// The runtime-permission half of the store: one state token per storage
/// permission in the entry's "perms" object, signed with the ops (the /3
/// payload -- AppOpsStore.canonicalPayload). Tokens encode PermissionController's
/// flags (KotlinUtils.grantRuntimePermission / revokeRuntimePermission,
/// lineage-21.0 :948-1040, :1268-1340): "granted" = granted + USER_SET,
/// "granted_fixed" = granted + USER_SET|USER_FIXED, "denied" = USER_SET,
/// "denied_fixed" = USER_SET|USER_FIXED; absent = never asked. The runtime
/// reads them (runtime/java/com/macapk/loader/SpecialAccessStore.java,
/// PermModel); only this binary writes them. docs/storage-runtime-permissions_2026-09-26.md.
extension AppOpsStore {
    static let readExternal = "android.permission.READ_EXTERNAL_STORAGE"
    static let writeExternal = "android.permission.WRITE_EXTERNAL_STORAGE"
    static let accessMediaLocation = "android.permission.ACCESS_MEDIA_LOCATION"
    static let readMediaImages = "android.permission.READ_MEDIA_IMAGES"
    static let readMediaVideo = "android.permission.READ_MEDIA_VIDEO"
    static let readMediaAudio = "android.permission.READ_MEDIA_AUDIO"
    static let readMediaUserSelected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    static let storagePermissions: Set<String> = [
        readExternal, writeExternal, accessMediaLocation, readMediaImages, readMediaVideo,
        readMediaAudio, readMediaUserSelected]
    /// The location runtime permissions (2026-10-05). macOS authorizes location
    /// for Mac.apk as a WHOLE (locationd keys the client by the shared
    /// runtime's signing identifier, org.macapk.runtime), so the per-app
    /// Android grant has to live here, as the storage family's does; the macOS
    /// prompt is only the host's own gate behind it.
    static let fineLocation = "android.permission.ACCESS_FINE_LOCATION"
    static let coarseLocation = "android.permission.ACCESS_COARSE_LOCATION"
    static let locationPermissions: Set<String> = [fineLocation, coarseLocation]
    /// Every runtime permission this store keeps a token for.
    static let runtimePermissions: Set<String> = storagePermissions.union(locationPermissions)
    static let permStates: Set<String> = ["granted", "granted_fixed", "denied", "denied_fixed"]

    /// The VERIFIED runtime-permission tokens stored for `id` (only known
    /// permissions and known tokens), or [:].
    static func permissions(for id: Identity, in d: URL = defaultDir) -> [String: String] {
        guard let key = loadPublicKey(in: d) else { return [:] }
        let apps = currentApps(readStore(in: d))
        guard let entry = apps[id.key] as? [String: Any], verify(entry, id: id, key: key)
        else { return [:] }
        return (entry["perms"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
            .filter { runtimePermissions.contains($0.key) && permStates.contains($0.value) }
    }

    /// Record runtime-permission tokens (and, for App info, op modes) in ONE
    /// signed write, keeping everything else a still-valid entry holds -- the
    /// same lock / key / replace-an-unverifiable-entry rules as setMode.
    static func setPermissions(_ changes: [String: String], ops opChanges: [String: Mode] = [:],
                               for id: Identity, by: String, in d: URL = defaultDir) throws {
        for (p, st) in changes where !runtimePermissions.contains(p) || !permStates.contains(st) {
            throw StoreError.badField("permission \(p)=\(st)")
        }
        for op in opChanges.keys where !knownOps.contains(op) {
            throw StoreError.badField("op \(op)")
        }
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
        if let old = apps[id.key] as? [String: Any], verify(old, id: id, key: key.publicKey) {
            entry = old
        }
        var ops = (entry["ops"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        for (op, m) in opChanges { ops[op] = m.rawValue }
        var perms = (entry["perms"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        for (p, st) in changes { perms[p] = st }
        let updatedAt = ISO8601.format(Date()) ?? ""
        guard let payload = canonicalPayload(package: id.package, installedAt: id.installedAt,
                                             ops: ops, perms: perms,
                                             updatedAt: updatedAt, by: by)
        else { throw StoreError.badField("identity \(id.key)") }
        let sig = try key.signature(for: payload)
        entry["package"] = id.package
        entry["installedAt"] = id.installedAt
        entry["ops"] = ops
        entry["perms"] = perms
        entry["updatedAt"] = updatedAt
        entry["by"] = by
        entry["sig"] = sig.derRepresentation.base64EncodedString()
        apps[id.key] = entry
        root["apps"] = apps
        root["version"] = storeVersion
        try writeStore(root, in: d)
    }
}

/// Android's runtime-permission dialog (PermissionController's
/// GrantPermissionsActivity) and Settings' App info screen, for the storage
/// permissions -- stage S5 of docs/shared-storage-real-mac-folders-design_2026-09-25.md
/// (owner decision 2026-09-25: media permissions are asked like Android).
///
/// WHY HERE AND NOT AN IN-APP DIALOG. A dialog drawn inside the runtime is a
/// view in the guest's own process: the app can click it (performClick,
/// dispatchTouchEvent, Instrumentation), and the process that drew it would
/// have to write the grant -- but the runtime only READS the store and never
/// holds the signing key. The out-of-process alert has neither problem, which
/// is the reasoning S1 already settled for special access; the same surface,
/// the same parent-process identity (CallerIdentity) and the same signed store
/// are reused.
///
/// WHAT THE RUNTIME DECIDES AND WHAT THIS BINARY DECIDES. The runtime plans a
/// request the way GrantPermissionsViewModel does (which groups need a dialog,
/// which are answered silently) and passes permission NAMES only. This binary
/// words the question from the permissions themselves (never from a group name
/// it is handed), reads the current state from the store, decides whether a
/// "Don't allow" is the don't-ask-again kind (the group was already answered,
/// BasicGrantBehavior.getDenyButton :36-47), and refuses an --auto grant into
/// a group that holds no granted permission -- so a guest that forges the argv
/// gets at most an alert about ITSELF that the user must still answer.
enum RuntimePermission {

    enum Answer: Equatable { case allow, select, deny, noAnswer }

    static let prompts: Set<String> = ["basic", "select", "select_more",
                                       "supergroup_pre_q", "supergroup_q_to_s", "location"]
    /// The prompts that only make sense with a photo picker behind them.
    static let pickerPrompts: Set<String> = ["select", "select_more"]

    /// KotlinUtils.isPhotoPickerPromptEnabled (DeviceConfig
    /// privacy/photo_picker_prompt_enabled; lineage-21.0 KotlinUtils.kt
    /// :232-239). OFF until Mac.apk has a photo picker (stage S8): "Select
    /// photos and videos" would record limited access with nothing selected,
    /// and the later SELECT_MORE / picker-redirect states would promise a
    /// picker that never opens. With it off the runtime never plans a select
    /// prompt, and this binary refuses one (and any --partial) so a forged argv
    /// cannot reach that path either. MUST match
    /// SpecialAccessStore.PHOTO_PICKER_PROMPT_ENABLED (Java); flip both when
    /// the picker lands.
    static let photoPickerPromptEnabled = false

    /// Whether `p` can be shown at all right now.
    static func promptOffered(_ p: String) -> Bool {
        prompts.contains(p) && (photoPickerPromptEnabled || !pickerPrompts.contains(p))
    }

    /// This binary's own group table (PermissionMapping.kt :94-117):
    /// ACCESS_MEDIA_LOCATION is STORAGE below 33 and READ_MEDIA_VISUAL from 33.
    static func group(_ p: String, level: Int) -> String {
        switch p {
        case AppOpsStore.fineLocation, AppOpsStore.coarseLocation: return "location"
        case AppOpsStore.readExternal, AppOpsStore.writeExternal: return "storage"
        case AppOpsStore.accessMediaLocation: return level < 33 ? "storage" : "visual"
        case AppOpsStore.readMediaAudio: return "aural"
        default: return "visual"
        }
    }

    /// The permissions whose grant means the GROUP is granted. MUST agree with
    /// SpecialAccessStore.PermModel.groupFullyGranted (Java): any granted
    /// member (GrantBehavior.isGroupFullyGranted), except, for
    /// READ_MEDIA_VISUAL on U+, getPartialGrantPermissions
    /// (StorageGrantBehavior.kt lineage-21.0 :91-102, :129-135) -- which is
    /// {USER_SELECTED} while the photo-picker prompt is off, and gains
    /// ACCESS_MEDIA_LOCATION only with it on. So: storage = READ, WRITE (and
    /// ACCESS_MEDIA_LOCATION below 33, where it is a STORAGE member); visual =
    /// IMAGES, VIDEO, ACCESS_MEDIA_LOCATION (USER_SELECTED never counts; it
    /// does not exist below 34); aural = AUDIO. With the picker prompt on, the
    /// runtime drops ACCESS_MEDIA_LOCATION only for an app that DECLARES
    /// USER_SELECTED, which this binary cannot see; dropping it for all is the
    /// stricter side, and a refusal (exit 6) makes the runtime ask instead
    /// (PermModel.promptInstead), so a drift costs a dialog, never a grant.
    static func anchors(_ g: String, level: Int) -> Set<String> {
        switch g {
        case "storage":
            return level < 33
                ? [AppOpsStore.readExternal, AppOpsStore.writeExternal, AppOpsStore.accessMediaLocation]
                : [AppOpsStore.readExternal, AppOpsStore.writeExternal]
        case "aural": return [AppOpsStore.readMediaAudio]
        case "location": return AppOpsStore.locationPermissions
        default:
            return level >= 34 && photoPickerPromptEnabled
                ? [AppOpsStore.readMediaImages, AppOpsStore.readMediaVideo]
                : [AppOpsStore.readMediaImages, AppOpsStore.readMediaVideo,
                   AppOpsStore.accessMediaLocation]
        }
    }

    static func isGranted(_ t: String?) -> Bool { t == "granted" || t == "granted_fixed" }
    static func isFixed(_ t: String?) -> Bool { t == "granted_fixed" || t == "denied_fixed" }

    /// GrantPermissionsViewModel.getGroupState's silent grant: a permission
    /// joins a group that is ALREADY granted. Refused when its group holds no
    /// granted anchor.
    static func autoGrantAllowed(perms: [String], current: [String: String], level: Int) -> Bool {
        guard !perms.isEmpty else { return false }
        for p in perms {
            let g = group(p, level: level)
            if !anchors(g, level: level).contains(where: { isGranted(current[$0]) }) { return false }
        }
        return true
    }

    /// What an answer writes (only the tokens that change). onPermissionGrantResult
    /// (GrantPermissionsViewModel.kt :678-770): Allow grants every affected
    /// permission; Don't allow revokes them, fixed when the group had already
    /// been answered; "Select photos" grants the partial set and revokes the
    /// rest, both fixed when USER_SELECTED was already set
    /// (grantUserSelectedVisualGroupPermissions :771-823); in the "more photos"
    /// prompt, "Don't select more" takes the same path (DENIED_MORE).
    static func changes(answer: Answer, prompt: String, perms: [String], members: [String],
                        partial: [String], current: [String: String]) -> [String: String] {
        var want: [String: String] = [:]
        if prompt == "location" {
            // The location dialog (GrantPermissionsActivity's precise /
            // approximate choice, Android 12+): "Allow" grants every requested
            // permission, "Only approximate" grants COARSE and leaves FINE
            // denied, "Don't allow" denies them -- fixed when the group had
            // already been answered, as for any group.
            let userSet = Set(members + perms).contains { current[$0] != nil }
            for p in perms {
                switch answer {
                case .allow: want[p] = "granted"
                case .select: want[p] = partial.contains(p) ? "granted" : (userSet ? "denied_fixed" : "denied")
                case .deny: want[p] = userSet ? "denied_fixed" : "denied"
                case .noAnswer: break
                }
            }
            return want.filter { current[$0.key] != $0.value }
        }
        let userSelected = current[AppOpsStore.readMediaUserSelected]
        let selectFixed = isFixed(userSelected) || userSelected != nil
        func partialPath() {
            for p in perms {
                if partial.contains(p) {
                    want[p] = selectFixed ? "granted_fixed" : "granted"
                } else {
                    want[p] = selectFixed ? "denied_fixed" : "denied"
                }
            }
        }
        switch answer {
        case .noAnswer:
            return [:]
        case .allow:
            for p in perms { want[p] = "granted" }
        case .select:
            partialPath()
        case .deny:
            if prompt == "select_more" {
                partialPath()
            } else {
                let userSet = Set(members + perms).contains { current[$0] != nil }
                for p in perms { want[p] = userSet ? "denied_fixed" : "denied" }
            }
        }
        return want.filter { current[$0.key] != $0.value }
    }

    /// Which answer a CFUserNotification response means for `prompt`. The
    /// DEFAULT button (Return) keeps the current state: "Don't allow" -- or
    /// "Don't select more", which keeps a limited grant.
    static func answer(responseFlags flags: CFOptionFlags, prompt: String) -> Answer {
        switch flags & 0x3 {
        case CFOptionFlags(kCFUserNotificationDefaultResponse):
            return prompt == "select_more" ? .select : .deny
        case CFOptionFlags(kCFUserNotificationAlternateResponse):
            return .allow
        case CFOptionFlags(kCFUserNotificationOtherResponse):
            return (prompt == "select" || prompt == "select_more" || prompt == "location")
                ? .select : .noAnswer
        default:
            return .noAnswer
        }
    }

    /// The question, worded from the permissions (PermissionController
    /// res/values/strings.xml lineage-21.0 :1485-1515: permgrouprequest_storage,
    /// _storage_q_to_s, _storage_pre_q, _read_media_aural, _read_media_visual,
    /// _more_photos; buttons :56, :71, :81-87, :136).
    static func question(label: String, prompt: String, perms: [String], level: Int) -> String {
        let who = "\u{201C}\(label)\u{201D}"
        if prompt == "location" {
            // permgrouprequest_location (PermissionController strings.xml).
            return "Allow \(who) to access this device\u{2019}s location?"
        }
        if prompt == "supergroup_pre_q" {
            return "Allow \(who) to access photos, videos, music, audio, and other files on this device?"
        }
        let groups = Set(perms.map { group($0, level: level) })
        if prompt == "supergroup_q_to_s" || groups.count > 1 {
            return "Allow \(who) to access photos, videos, music, and audio on this device?"
        }
        switch groups.first ?? "storage" {
        case "aural": return "Allow \(who) to access music and audio on this device?"
        case "visual":
            return prompt == "select_more"
                ? "Allow \(who) to access more photos and videos on this device?"
                : "Allow \(who) to access photos and videos on this device?"
        default: return "Allow \(who) to access photos, media, and files on your device?"
        }
    }

    static func ask(label: String, prompt: String, perms: [String], partial: [String] = [],
                    level: Int, iconURL: URL?) -> Answer {
        let header = question(label: label, prompt: prompt, perms: perms, level: level)
        var body = "This is Android\u{2019}s permission request for \(label), kept by Mac.apk."
            + " You can change it later in the app\u{2019}s App info."
        let defaultTitle: String, alternateTitle: String
        var otherTitle: String? = nil
        switch prompt {
        case "select":
            defaultTitle = "Don\u{2019}t allow"
            alternateTitle = "Allow all"
            otherTitle = "Select photos and videos"
            body += "\n\nMac.apk cannot pick individual photos yet, so \u{201C}Select photos"
                + " and videos\u{201D} gives limited access with no photos selected."
        case "select_more":
            defaultTitle = "Don\u{2019}t select more"
            alternateTitle = "Allow all"
            otherTitle = "Select more"
        case "location":
            defaultTitle = "Don\u{2019}t allow"
            alternateTitle = "Allow"
            // The precise / approximate choice exists only when the app asked
            // for FINE and COARSE together (partial = the COARSE half).
            if !partial.isEmpty { otherTitle = "Only approximate" }
            body += "\n\nThe location comes from macOS Location Services, which"
                + " asks once, separately, whether Mac.apk may use it."
        default:
            defaultTitle = "Don\u{2019}t allow"
            alternateTitle = "Allow"
        }
        var flags: CFOptionFlags = 0
        let rc = CFUserNotificationDisplayAlert(
            300, CFOptionFlags(kCFUserNotificationNoteAlertLevel),
            iconURL as CFURL?, nil, nil, header as CFString, body as CFString,
            defaultTitle as CFString, alternateTitle as CFString, otherTitle as CFString?, &flags)
        guard rc == 0 else { return .noAnswer }
        return answer(responseFlags: flags, prompt: prompt)
    }

    static func usageError(_ json: Bool, _ msg: String) -> Int32 {
        InstallHelperMain.emit(json, ["status": "error", "message": msg])
        return 2
    }

    static func resolveCaller(_ json: Bool) -> CallerIdentity.Resolved? {
        switch CallerIdentity.resolve(parent: getppid(), registryURL: Paths.registryURL,
                                      runDir: Paths.runDir) {
        case .success(let c): return c
        case .failure(let why):
            InstallHelperMain.emit(json, ["status": "identity_refused", "message": why.message])
            return nil
        }
    }

    static func icon(_ c: CallerIdentity.Resolved) -> URL? {
        let u = URL(fileURLWithPath: c.bundlePath)
            .appendingPathComponent("Contents/Resources/AppIcon.icns")
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    // ---- runtime-permission ----------------------------------------------------

    static func run(source: String, noUI: Bool, level: Int, prompt: String?, auto: Bool,
                    perms: [String], members: [String], partial: [String], json: Bool) -> Int32 {
        guard source == "android" else {
            return usageError(json, "runtime-permission is only asked for by an app (--source android)")
        }
        guard !noUI else {
            return usageError(json, "--no-ui cannot be combined with runtime-permission: the"
                                  + " grant is the user's answer to the alert")
        }
        let isLocation = prompt == "location"
        let known = isLocation ? AppOpsStore.locationPermissions : AppOpsStore.storagePermissions
        guard !perms.isEmpty, perms.allSatisfy({ known.contains($0) }),
              members.allSatisfy({ known.contains($0) }) else {
            return usageError(json, isLocation
                ? "--prompt location takes only location runtime permissions"
                : "--perm/--member must name storage runtime permissions")
        }
        let partialOK: Set<String> = isLocation
            ? [AppOpsStore.coarseLocation]
            : [AppOpsStore.readMediaUserSelected, AppOpsStore.accessMediaLocation]
        guard partial.allSatisfy({ partialOK.contains($0) && perms.contains($0) }) else {
            return usageError(json, "--partial must be READ_MEDIA_VISUAL_USER_SELECTED or"
                                  + " ACCESS_MEDIA_LOCATION (ACCESS_COARSE_LOCATION for"
                                  + " --prompt location), and one of the --perm values")
        }
        guard auto || prompts.contains(prompt ?? "") else {
            return usageError(json, "--prompt must be one of \(prompts.sorted()) (or --auto)")
        }
        guard auto || promptOffered(prompt ?? ""),
              photoPickerPromptEnabled || partial.isEmpty || isLocation else {
            return usageError(json, "--prompt \(prompt ?? "") / --partial need a photo picker,"
                                  + " which Mac.apk does not have yet (Allow means allow all)")
        }
        guard level >= 23 else { return usageError(json, "--level must be the presented API level") }
        guard let caller = resolveCaller(json) else { return 5 }
        let id = AppOpsStore.Identity(dirName: caller.dirName, package: caller.package,
                                      installedAt: caller.installedAt)
        let current = AppOpsStore.permissions(for: id)
        FileHandle.standardError.write(Data(
            "[helper] runtime-permission \(perms) for \(caller.dirName): current \(current)\n".utf8))
        if auto {
            guard autoGrantAllowed(perms: perms, current: current, level: level) else {
                InstallHelperMain.emit(json, ["status": "refused",
                    "message": "--auto needs a granted permission in the same group"])
                return 6
            }
            do {
                var grant: [String: String] = [:]
                for p in perms where current[p] != "granted" { grant[p] = "granted" }
                if !grant.isEmpty {
                    try AppOpsStore.setPermissions(grant, for: id, by: "helper")
                }
            } catch {
                InstallHelperMain.emit(json, ["status": "error",
                    "message": "could not record the grant: \(error.localizedDescription)"])
                return 5
            }
            InstallHelperMain.emit(json, ["status": "auto_granted", "dirName": id.dirName])
            return 0
        }
        let p = prompt ?? "basic"
        let label = caller.label.isEmpty ? caller.package : caller.label
        let a = ask(label: label, prompt: p, perms: perms, partial: partial, level: level,
                    iconURL: icon(caller))
        if a == .noAnswer {
            InstallHelperMain.emit(json, ["status": "timeout",
                "message": "no answer to the alert within 5 minutes; nothing changed"])
            return 4
        }
        let want = changes(answer: a, prompt: p, perms: perms, members: members,
                           partial: partial, current: current)
        if !want.isEmpty {
            do {
                try AppOpsStore.setPermissions(want, for: id, by: "helper")
            } catch {
                InstallHelperMain.emit(json, ["status": "error",
                    "message": "could not record the answer: \(error.localizedDescription)"])
                return 5
            }
        }
        switch a {
        case .allow:
            InstallHelperMain.emit(json, ["status": "granted", "dirName": id.dirName])
            return 0
        case .select:
            InstallHelperMain.emit(json, ["status": "partial", "dirName": id.dirName])
            return 0
        default:
            InstallHelperMain.emit(json, ["status": "denied", "dirName": id.dirName])
            return 3
        }
    }

    // ---- app-details ---------------------------------------------------------------

    /// One check box of the App info alert.
    struct Row: Equatable {
        let key: String          // "group:<name>" or "op:<opstr>"
        let title: String
        let perms: [String]      // group rows
        let checked: Bool
    }

    static let groupNames: Set<String> = ["STORAGE", "READ_MEDIA_VISUAL", "READ_MEDIA_AURAL",
                                          "storage-supergroup"]

    /// The rows for `groups` ("<name>=<p>,<p>") and `ops`, with their stored
    /// state. Titles: the platform group labels (core/res strings.xml android14
    /// :891-901 "Files", "Music and audio", "Photos and videos"; android12's
    /// "Files and media" below 33) and Settings' special-access titles.
    static func rows(groups: [String], ops: [String], level: Int, current: [String: String],
                     opModes: [String: AppOpsStore.Mode]) -> [Row]? {
        var out: [Row] = []
        for spec in groups {
            let parts = spec.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2, groupNames.contains(parts[0]) else { return nil }
            let perms = parts[1].split(separator: ",").map(String.init)
            guard !perms.isEmpty, perms.allSatisfy({ AppOpsStore.storagePermissions.contains($0) })
            else { return nil }
            let kinds = Set(perms.map { group($0, level: level) })
            var anchorSet = Set<String>()
            for k in kinds { anchorSet.formUnion(anchors(k, level: level)) }
            let checked = perms.contains { anchorSet.contains($0) && isGranted(current[$0]) }
            var title: String
            switch parts[0] {
            case "READ_MEDIA_VISUAL": title = "Photos and videos"
            case "READ_MEDIA_AURAL": title = "Music and audio"
            case "STORAGE": title = level < 33 ? "Files and media" : "Files"
            default: title = "Photos, videos, music, and audio"
            }
            if !checked && isGranted(current[AppOpsStore.readMediaUserSelected])
                && perms.contains(AppOpsStore.readMediaUserSelected) {
                title += " (limited access)"
            }
            out.append(Row(key: "group:" + parts[0], title: title, perms: perms, checked: checked))
        }
        for op in ops {
            guard AppOpsStore.knownOps.contains(op) else { return nil }
            let title = op == AppOpsStore.manageExternalStorage ? "All files access" : "Media management"
            out.append(Row(key: "op:" + op, title: title, perms: [], checked: opModes[op] == .allow))
        }
        return out.count <= 8 ? out : nil
    }

    /// What unticking / ticking rows writes. Settings' App permissions screen
    /// (AppPermissionViewModel.requestChange / onDenyAnyWay, lineage-21.0
    /// :705-905, :1077-1110): Allow grants the group, Don't allow revokes it
    /// with userFixed = false (so the app may ask again). An untouched row
    /// writes nothing.
    static func detailsChanges(rows: [Row], checked: [Bool], current: [String: String])
        -> (perms: [String: String], ops: [String: AppOpsStore.Mode]) {
        var perms: [String: String] = [:]
        var ops: [String: AppOpsStore.Mode] = [:]
        for (i, r) in rows.enumerated() where i < checked.count && checked[i] != r.checked {
            if r.key.hasPrefix("op:") {
                ops[String(r.key.dropFirst(3))] = checked[i] ? .allow : .deny
            } else {
                for p in r.perms { perms[p] = checked[i] ? "granted" : "denied" }
            }
        }
        return (perms.filter { current[$0.key] != $0.value }, ops)
    }

    /// CFUserNotificationCheckBoxChecked(i) (CFUserNotification.h): bit 8 + i.
    static func checkBoxBit(_ i: Int) -> CFOptionFlags { CFOptionFlags(1) << CFOptionFlags(8 + i) }

    static func appDetails(source: String, noUI: Bool, level: Int, groups: [String],
                           ops: [String], json: Bool) -> Int32 {
        guard source == "android" else {
            return usageError(json, "app-details is only asked for by an app (--source android)")
        }
        guard !noUI else {
            return usageError(json, "--no-ui cannot be combined with app-details")
        }
        guard level >= 23 else { return usageError(json, "--level must be the presented API level") }
        guard let caller = resolveCaller(json) else { return 5 }
        let id = AppOpsStore.Identity(dirName: caller.dirName, package: caller.package,
                                      installedAt: caller.installedAt)
        let current = AppOpsStore.permissions(for: id)
        var opModes: [String: AppOpsStore.Mode] = [:]
        for op in ops { if let m = AppOpsStore.mode(for: id, op: op) { opModes[op] = m } }
        guard let rs = rows(groups: groups, ops: ops, level: level, current: current,
                            opModes: opModes) else {
            return usageError(json, "bad --group / --op")
        }
        let label = caller.label.isEmpty ? caller.package : caller.label
        let header = "App info: \(label)"
        var body = "\(caller.package) -- Android\u{2019}s App info for this app, kept by Mac.apk."
        if rs.isEmpty {
            body += "\n\nThis app has no permissions Mac.apk asks about."
            var f: CFOptionFlags = 0
            let rc = CFUserNotificationDisplayAlert(
                300, CFOptionFlags(kCFUserNotificationNoteAlertLevel), icon(caller) as CFURL?,
                nil, nil, header as CFString, body as CFString, "Done" as CFString, nil, nil, &f)
            InstallHelperMain.emit(json, ["status": rc == 0 ? "done" : "timeout", "changed": "no"])
            return rc == 0 ? 0 : 4
        }
        body += " Ticked items are allowed. Turning a permission off stops the app, as"
            + " Android does."
        var dict: [CFString: Any] = [
            kCFUserNotificationAlertHeaderKey: header,
            kCFUserNotificationAlertMessageKey: body,
            kCFUserNotificationDefaultButtonTitleKey: "Done",
            kCFUserNotificationAlternateButtonTitleKey: "Cancel",
            kCFUserNotificationCheckBoxTitlesKey: rs.map { $0.title },
        ]
        if let u = icon(caller) { dict[kCFUserNotificationIconURLKey] = u }
        var flags = CFOptionFlags(kCFUserNotificationNoteAlertLevel)
        for (i, r) in rs.enumerated() where r.checked { flags |= checkBoxBit(i) }
        var err: Int32 = 0
        guard let note = CFUserNotificationCreate(kCFAllocatorDefault, 300, flags, &err,
                                                  dict as CFDictionary) else {
            InstallHelperMain.emit(json, ["status": "error",
                                          "message": "could not show the alert (\(err))"])
            return 5
        }
        var resp: CFOptionFlags = 0
        let rc = CFUserNotificationReceiveResponse(note, 300, &resp)
        guard rc == 0, (resp & 0x3) == CFOptionFlags(kCFUserNotificationDefaultResponse) else {
            let timedOut = rc != 0 || (resp & 0x3) == CFOptionFlags(kCFUserNotificationCancelResponse)
            InstallHelperMain.emit(json, ["status": timedOut ? "timeout" : "cancelled", "changed": "no"])
            return timedOut ? 4 : 0
        }
        let ticked = rs.indices.map { (resp & checkBoxBit($0)) != 0 }
        let (pc, oc) = detailsChanges(rows: rs, checked: ticked, current: current)
        if !pc.isEmpty || !oc.isEmpty {
            do {
                try AppOpsStore.setPermissions(pc, ops: oc, for: id, by: "helper")
            } catch {
                InstallHelperMain.emit(json, ["status": "error",
                    "message": "could not record the change: \(error.localizedDescription)"])
                return 5
            }
        }
        InstallHelperMain.emit(json, ["status": "done",
                                      "changed": (pc.isEmpty && oc.isEmpty) ? "no" : "yes"])
        return 0
    }

    // ---- perms-selftest ------------------------------------------------------------

    /// `perms-selftest --dir <dir>`: sign FIXED runtime-permission entries into
    /// a NEW directory (refusing the real store and any directory that already
    /// holds a key, as appops-selftest does) and check the answer rules, so
    /// debug/mediaperm_gate.sh can prove this signer and the runtime's verifier
    /// agree on the /3 payload. Grants nothing to anything: it only ever
    /// writes the dirName ".macapk-selftest-perms", which no installed app can
    /// have (a package name cannot start with a dot), into a directory that is
    /// not the real store.
    static func selftest(dir: String?, json: Bool) -> Int32 {
        guard let dir = dir, !dir.isEmpty else { return usageError(json, "--dir is required") }
        let d = URL(fileURLWithPath: dir, isDirectory: true).standardizedFileURL
        let real = AppOpsStore.defaultDir.resolvingSymlinksInPath().path.lowercased()
        if d.resolvingSymlinksInPath().path.lowercased() == real
            || d.path.lowercased() == AppOpsStore.defaultDir.path.lowercased() {
            return usageError(json, "refusing the real special-access store")
        }
        if FileManager.default.fileExists(atPath: AppOpsStore.keyURL(d).path) {
            return usageError(json, "\(d.path) already holds a signing key; use a new directory")
        }
        let id = AppOpsStore.Identity(dirName: ".macapk-selftest-perms",
                                      package: "org.macapk.selftest.perms",
                                      installedAt: "2026-01-01T00:00:00Z")
        let img = AppOpsStore.readMediaImages, vid = AppOpsStore.readMediaVideo
        let aud = AppOpsStore.readMediaAudio, us = AppOpsStore.readMediaUserSelected
        let written = [img: "granted", vid: "granted", aud: "denied_fixed", us: "granted"]
        var checks: [String: String] = [:]
        func yes(_ b: Bool) -> String { b ? "yes" : "no" }
        do {
            try AppOpsStore.setMode(.allow, op: AppOpsStore.manageExternalStorage, for: id,
                                    by: "selftest", in: d)
            try AppOpsStore.setPermissions(written, for: id, by: "selftest", in: d)
            checks["perms_roundtrip"] = yes(AppOpsStore.permissions(for: id, in: d) == written)
            // An op write after a permission write must keep (and re-sign) the
            // permissions -- the InstallCore side of the /3 payload.
            try AppOpsStore.setMode(.deny, op: AppOpsStore.manageMedia, for: id,
                                    by: "selftest", in: d)
            checks["setmode_keeps_perms"] = yes(AppOpsStore.permissions(for: id, in: d) == written
                && AppOpsStore.mode(for: id, op: AppOpsStore.manageExternalStorage, in: d) == .allow)
        } catch {
            InstallHelperMain.emit(json, ["status": "error", "message": error.localizedDescription])
            return 5
        }
        var refused = false
        do { try AppOpsStore.setPermissions([img: "bogus"], for: id, by: "selftest", in: d) }
        catch { refused = true }
        checks["bad_token_refused"] = yes(refused)
        // The answer rules.
        let fresh: [String: String] = [:]
        checks["first_deny_not_fixed"] = yes(changes(answer: .deny, prompt: "basic", perms: [aud],
            members: [aud], partial: [], current: fresh) == [aud: "denied"])
        checks["second_deny_fixed"] = yes(changes(answer: .deny, prompt: "basic", perms: [aud],
            members: [aud], partial: [], current: [aud: "denied"]) == [aud: "denied_fixed"])
        checks["allow_clears_fixed"] = yes(changes(answer: .allow, prompt: "basic", perms: [aud],
            members: [aud], partial: [], current: [aud: "denied"]) == [aud: "granted"])
        checks["select_partial"] = yes(changes(answer: .select, prompt: "select",
            perms: [img, vid, us], members: [img, vid, us], partial: [us], current: fresh)
            == [img: "denied", vid: "denied", us: "granted"])
        checks["select_again_fixed"] = yes(changes(answer: .select, prompt: "select_more",
            perms: [img, vid, us], members: [img, vid, us], partial: [us],
            current: [img: "denied", vid: "denied", us: "granted"])
            == [img: "denied_fixed", vid: "denied_fixed", us: "granted_fixed"])
        checks["no_answer_writes_nothing"] = yes(changes(answer: .noAnswer, prompt: "basic",
            perms: [aud], members: [aud], partial: [], current: fresh).isEmpty)
        checks["auto_needs_granted_group"] = yes(
            !autoGrantAllowed(perms: [vid], current: fresh, level: 34)
            && autoGrantAllowed(perms: [vid], current: [img: "granted"], level: 34)
            && !autoGrantAllowed(perms: [vid], current: [us: "granted"], level: 34)
            && !autoGrantAllowed(perms: [aud], current: [img: "granted"], level: 34))
        // The Java planner's rule (groupFullyGranted): ACCESS_MEDIA_LOCATION
        // granted makes READ_MEDIA_VISUAL granted, at 33 and (picker prompt
        // off) at 34 -- so asking for it alone, then for IMAGES, is a silent
        // grant on both sides, not a refused --auto.
        let aml = AppOpsStore.accessMediaLocation
        checks["auto_after_media_location"] = yes(
            autoGrantAllowed(perms: [img], current: [aml: "granted", us: "granted"], level: 34)
            && autoGrantAllowed(perms: [img], current: [aml: "granted"], level: 33))
        checks["picker_prompts_off"] = yes(!photoPickerPromptEnabled
            && !promptOffered("select") && !promptOffered("select_more")
            && promptOffered("basic") && promptOffered("supergroup_q_to_s"))
        let def = CFOptionFlags(kCFUserNotificationDefaultResponse)
        let alt = CFOptionFlags(kCFUserNotificationAlternateResponse)
        let oth = CFOptionFlags(kCFUserNotificationOtherResponse)
        // S1 grants signed before stage S5 (macapk-appops/2, ops only) are
        // still believed and are re-signed as /3 by the next write; a /2
        // signature over an entry that carries "perms" is not believed.
        checks["legacy_v2_ops_entry_kept"] = "no"
        checks["legacy_v2_resigned_v3"] = "no"
        checks["legacy_v2_with_perms_refused"] = "no"
        do {
            let key = try AppOpsStore.loadOrCreateKey(in: d)
            func plant(_ lid: AppOpsStore.Identity, perms: [String: String]?) throws {
                let ops = [AppOpsStore.manageExternalStorage: "allow"]
                let upd = "2026-09-20T00:00:00Z"
                guard let p = AppOpsStore.canonicalPayload(
                        package: lid.package, installedAt: lid.installedAt, ops: ops,
                        updatedAt: upd, by: "selftest", tag: AppOpsStore.legacyPayloadTag)
                else { return }
                var e: [String: Any] = ["package": lid.package, "installedAt": lid.installedAt,
                                        "ops": ops, "updatedAt": upd, "by": "selftest",
                                        "sig": try key.signature(for: p).derRepresentation
                                            .base64EncodedString()]
                if let perms = perms { e["perms"] = perms }
                var root = AppOpsStore.readStore(in: d)
                var apps = AppOpsStore.currentApps(root)
                apps[lid.key] = e
                root["apps"] = apps
                root["version"] = AppOpsStore.storeVersion
                try AppOpsStore.writeStore(root, in: d)
            }
            let legacy = AppOpsStore.Identity(dirName: ".macapk-selftest-v2",
                                              package: "org.macapk.selftest.v2",
                                              installedAt: "2026-01-02T00:00:00Z")
            let legacyPerms = AppOpsStore.Identity(dirName: ".macapk-selftest-v2p",
                                                   package: "org.macapk.selftest.v2p",
                                                   installedAt: "2026-01-03T00:00:00Z")
            // Left as /2 for the Java verifier's interop rows
            // (debug/mediaperm_verify/MediapermProbe.java interop).
            let legacyKept = AppOpsStore.Identity(dirName: ".macapk-selftest-v2k",
                                                  package: "org.macapk.selftest.v2k",
                                                  installedAt: "2026-01-04T00:00:00Z")
            try plant(legacy, perms: nil)
            try plant(legacyPerms, perms: [img: "granted"])
            try plant(legacyKept, perms: nil)
            checks["legacy_v2_ops_entry_kept"] = yes(
                AppOpsStore.mode(for: legacy, op: AppOpsStore.manageExternalStorage, in: d) == .allow)
            checks["legacy_v2_with_perms_refused"] = yes(
                AppOpsStore.mode(for: legacyPerms, op: AppOpsStore.manageExternalStorage, in: d) == nil
                && AppOpsStore.permissions(for: legacyPerms, in: d).isEmpty)
            try AppOpsStore.setMode(.deny, op: AppOpsStore.manageMedia, for: legacy,
                                    by: "selftest", in: d)
            let e = AppOpsStore.currentApps(AppOpsStore.readStore(in: d))[legacy.key] as? [String: Any]
            let ops = (e?["ops"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
            var v3 = false
            if let e = e, let b64 = e["sig"] as? String, let der = Data(base64Encoded: b64),
               let sig = try? P256.Signing.ECDSASignature(derRepresentation: der),
               let p3 = AppOpsStore.canonicalPayload(
                    package: legacy.package, installedAt: legacy.installedAt, ops: ops,
                    updatedAt: e["updatedAt"] as? String ?? "", by: e["by"] as? String ?? "") {
                v3 = key.publicKey.isValidSignature(sig, for: p3)
            }
            checks["legacy_v2_resigned_v3"] = yes(v3
                && ops[AppOpsStore.manageExternalStorage] == "allow"
                && ops[AppOpsStore.manageMedia] == "deny")
        } catch {
            FileHandle.standardError.write(Data("[selftest] legacy rows: \(error)\n".utf8))
        }
        checks["return_is_dont_allow"] = yes(answer(responseFlags: def, prompt: "basic") == .deny
            && answer(responseFlags: alt, prompt: "basic") == .allow
            && answer(responseFlags: oth, prompt: "select") == .select
            && answer(responseFlags: CFOptionFlags(kCFUserNotificationCancelResponse),
                      prompt: "basic") == .noAnswer)
        checks["return_keeps_limited"] = yes(answer(responseFlags: def, prompt: "select_more") == .select)
        checks["question_from_perms"] = yes(
            question(label: "X", prompt: "basic", perms: [aud], level: 34).contains("music and audio")
            && question(label: "X", prompt: "basic", perms: [img], level: 34).contains("photos and videos")
            && question(label: "X", prompt: "basic",
                        perms: [AppOpsStore.readExternal], level: 30).contains("photos, media, and files"))
        let rs = rows(groups: ["READ_MEDIA_VISUAL=\(img),\(vid),\(us)", "READ_MEDIA_AURAL=\(aud)"],
                      ops: [AppOpsStore.manageExternalStorage], level: 34,
                      current: [us: "granted", img: "denied"], opModes: [:]) ?? []
        let untouched = detailsChanges(rows: rs, checked: rs.map { $0.checked },
                                       current: [us: "granted", img: "denied"])
        let tickedAll = detailsChanges(rows: rs, checked: rs.map { _ in true },
                                       current: [us: "granted", img: "denied"])
        checks["details_rows"] = yes(rs.count == 3 && rs[0].title.contains("limited")
            && !rs[0].checked && !rs[1].checked && !rs[2].checked)
        checks["details_untouched_no_change"] = yes(untouched.perms.isEmpty && untouched.ops.isEmpty)
        checks["details_tick_grants"] = yes(tickedAll.perms == [img: "granted", vid: "granted", aud: "granted"]
            && tickedAll.ops == [AppOpsStore.manageExternalStorage: .allow])
        checks["checkbox_bits"] = yes(checkBoxBit(0) == 0x100 && checkBoxBit(2) == 0x400)
        let ok = !checks.values.contains("no")
        var out: [String: String] = ["status": ok ? "success" : "error", "dir": d.path, "key": id.key]
        for (k, v) in checks { out[k] = v }
        InstallHelperMain.emit(json, out)
        return ok ? 0 : 5
    }
}
