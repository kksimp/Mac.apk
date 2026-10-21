// AppStubMain.swift
//
// The per-app launcher stub: the executable inside every generated
// <Label>.app bundle (see BundleGenerator.swift). Its whole job is to find
// the ONE shared Mac.apk runtime and become it via execv, pointing the
// runtime at the APK and app-data tree that live inside this bundle.
//
// COMPILED AS ITS OWN EXECUTABLE (the "stub template",
// Resources/appstub/MacApkAppStub inside Mac.apk.app) by a separate
// swiftc invocation in build_launcher.sh: this file PLUS
// InstallCore.swift, which is UI-free by contract (Foundation + CryptoKit
// only, "a bare command-line helper links the same file") and supplies
// Paths and RegistryStore. Linking it is what lets the stub write
// lastLaunchedAt through the one real registry implementation instead of
// hand-mirroring paths that would drift. Do NOT add this file to the
// launcher target itself (two @main).
//
// SIGNING MODEL (T1 spike, 2026-08-10): the template binary is ad-hoc
// signed ONCE at build time as a standalone file, BEFORE it is copied
// into any bundle. Generated bundles are never codesigned as bundles, so
// no resource seal exists and the app data the runtime writes under
// Contents/android/ can never break one. Do not run codesign against a
// generated bundle or anything inside it: codesign pointed at a bundle's
// main executable seals the ENCLOSING bundle as a side effect.
//
// AppKit only, no SwiftUI: the stub's UI surface is NSAlert on failure.

import AppKit
import CryptoKit
import Darwin

// MARK: - Small helpers

/// Every line stubLog has emitted so far, retained in memory so beginRunLog()
/// can replay them into the run log once it knows where that file goes.
/// Buffering (rather than opening the file first) is deliberate: the log path
/// is derived from the resolved runtime and the bundle's own identity, neither
/// of which is known at the top of main, and every failure BEFORE that point
/// ends in a modal fatalAlert the user can actually read. Bounded by the number
/// of stubLog call sites (a few dozen), so it cannot grow with runtime.
private var stubLogBuffer: [String] = []

private func stubLog(_ msg: String) {
    let line = "[MacApkAppStub] " + msg + "\n"
    stubLogBuffer.append(line)
    FileHandle.standardError.write(Data(line.utf8))
}

// MARK: - The run log
//
// WHY THIS EXISTS. A bundle launched from the Dock/Finder has no terminal, so
// until v1.0.962 the runtime's entire stderr -- every `[I] Mac.apk.*` line and
// any uncaught Java exception -- was discarded by launchd. That made every
// owner-reported crash on an INSTALLED app unreproducible from evidence: the
// harnesses capture stderr because they redirect it themselves, and the actual
// product did not. `logs/macapk_launcher.log` only ever carried runs started by
// the launcher, which is a different process.
//
// The redirect is done HERE, in the stub, rather than with a `--log-file` flag
// in the runtime, for two reasons: dup2'd descriptors are inherited across
// execv, so the file captures output the runtime emits BEFORE it parses argv
// (dyld failures, early aborts) which a flag could never reach; and it requires
// no change to the runtime binary, so it cannot alter what any harness sees
// (harnesses never run the stub).

/// Archived run logs kept per app. Much smaller than the launcher's
/// kLogArchiveKeep of 10, for two compounding reasons: this prunes PER APP and
/// there are 11 installed bundles on the owner's machine, and a Mac.apk run log
/// is far bigger than a launcher one.
///
/// MEASURED 2026-08-12, F-Droid, both a cold and a warm launch: ~1.1 MB per
/// 50-second run, 11.7k lines, dominated by per-class
/// `AppCl: loadClass/findClass` tracing rather than by first-launch DEX
/// conversion -- so a warm run does NOT get cheaper. Three keeps the worst case
/// near 3.3 MB per app (~36 MB across the fleet, and only for apps actually
/// launched four or more times) while still leaving the owner the crash plus
/// the two runs before it, which is what a "it crashed, then worked on
/// relaunch" report needs: the relaunch archives the crash rather than
/// overwriting it.
private let kRunLogArchiveKeep = 3

/// Byte ceiling for one app's archived run logs, applied newest-first on top of
/// the count cap. At the measured ~1.1 MB/run the COUNT cap is what normally
/// binds; this exists for the pathological case (an app logging in a spin loop)
/// where three files could be far larger, and it bounds that without a
/// supervising process -- which the execv model rules out, since the stub
/// BECOMES the runtime and nothing is left watching.
///
/// The LIVE file is bounded by the RUNTIME, not here (2026-10-01): the shim's
/// per-run log cap (`__macapk_logcap_start`, MACAPK_LOG_CAP_MB, default 64 MB)
/// rotates `app_<identity>.log` to `app_<identity>.prev.log` when it reaches the
/// cap, keeping that one previous segment, so a run is at most about 2x the cap
/// on disk. That replaced the residual this comment used to admit -- a spinning
/// logger (Plague Inc, 22.9 MB/s) could fill the disk in one run. Both segments
/// are archived at the next launch (rotateRunLog), where the newest one is
/// exempt from this byte budget and the older one normally is not, so the
/// archive holds at most one capped segment plus this budget.
private let kRunLogArchiveMaxBytes = 4 * 1024 * 1024

/// Directory the run log goes in. Mirrors the resolution the launcher
/// (MacApkLauncherApp.logsDirectory) and the runtime (main.cpp's -XX:ErrorFile
/// block) already use, so a JVM crash dump and the stderr that led to it land
/// side by side: a repo `logs/` if this runtime is a dev-tree build, otherwise
/// ~/Library/Logs/macapk/.
///
/// The repo probe requires a sibling `VERSION` file AND a `runtime/` directory,
/// not merely the presence of a `logs/` subdir. That is not belt-and-braces:
/// the staged runtime lives at ~/Library/Application Support/Mac.apk/runtime/,
/// and a bare `logs/` existence test walks up to ~/Library and matches
/// ~/Library/Logs -- the filesystem is case-insensitive, so `logs` == `Logs`.
private func runLogDirectory(runtime: URL) -> URL {
    let fm = FileManager.default
    var dir = runtime.resolvingSymlinksInPath().deletingLastPathComponent()
    for _ in 0..<6 {
        var isDir: ObjCBool = false
        if fm.fileExists(atPath: dir.appendingPathComponent("logs").path, isDirectory: &isDir),
           isDir.boolValue,
           fm.fileExists(atPath: dir.appendingPathComponent("VERSION").path),
           fm.fileExists(atPath: dir.appendingPathComponent("runtime").path) {
            return dir.appendingPathComponent("logs")
        }
        let parent = dir.deletingLastPathComponent()
        if parent == dir { break }
        dir = parent
    }
    return fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Logs")
        .appendingPathComponent("macapk")
}

/// Move this app's previous run log into `archive/` under a timestamped name,
/// then prune the archive to kRunLogArchiveKeep files / kRunLogArchiveMaxBytes.
/// Only this app's own `app_<identity>_*.log` files are considered, so the
/// launcher's `launcher_*.log` archive and the other installed apps' logs are
/// never touched.
private func rotateRunLog(dir: URL, identity: String) {
    let fm = FileManager.default
    let archiveDir = dir.appendingPathComponent("archive")
    try? fm.createDirectory(at: archiveDir, withIntermediateDirectories: true)
    let live = dir.appendingPathComponent("app_\(identity).log")
    // The runtime's log cap leaves the previous run's OLDER segment next to it
    // as app_<identity>.prev.log (bionic_shim.cpp macapk_logcap_rotate). It is
    // archived too, under a name the prune below already matches; it keeps its
    // own (older) mtime, so the newest-first budget sorts it after the tail
    // segment, which holds the run's last lines.
    let ts = Int(Date().timeIntervalSince1970)
    let segments = [
        (live, "app_\(identity)_\(ts).log"),
        (dir.appendingPathComponent("app_\(identity).prev.log"), "app_\(identity)_\(ts).prev.log"),
    ]
    for (url, archivedName) in segments {
        guard let attrs = try? fm.attributesOfItem(atPath: url.path) else { continue }
        // Nothing to keep from a zero-byte previous run; just drop it so the
        // archive does not fill with empty files.
        if (attrs[.size] as? Int) ?? 0 == 0 {
            try? fm.removeItem(at: url)
        } else {
            try? fm.moveItem(at: url, to: archiveDir.appendingPathComponent(archivedName))
        }
    }
    guard let contents = try? fm.contentsOfDirectory(
        at: archiveDir,
        includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])
    else { return }
    let mine = contents.filter {
        $0.lastPathComponent.hasPrefix("app_\(identity)_")
            && $0.pathExtension == "log"
    }
    let newestFirst = mine.sorted { a, b in
        let mA = (try? a.resourceValues(forKeys: [.contentModificationDateKey])
            .contentModificationDate) ?? Date.distantPast
        let mB = (try? b.resourceValues(forKeys: [.contentModificationDateKey])
            .contentModificationDate) ?? Date.distantPast
        return mA > mB
    }
    var budget = kRunLogArchiveMaxBytes
    for (i, url) in newestFirst.enumerated() {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        // i == 0 is exempt from the BYTE cap on purpose. Without that, one run
        // bigger than the whole budget would delete itself -- discarding the
        // most recent log, which is precisely the evidence this exists to keep.
        // The count cap still applies to it (kRunLogArchiveKeep is never 0).
        if i >= kRunLogArchiveKeep || (i > 0 && size > budget) {
            try? fm.removeItem(at: url)
        } else {
            budget -= size
        }
    }
}

/// Rotate, open, and point fd 1 + fd 2 at this app's run log, then replay the
/// buffered stub lines into it. Returns the path so the caller can name it.
///
/// Three cases decline to redirect, and the middle one is the load-bearing
/// one:
///
///  - `MACAPK_STUB_LOG=0` turns it off outright.
///  - **fd 2 is already a regular file**, which means a CALLER deliberately
///    redirected it and is going to read that file. `debug/appbundle.sh` does
///    exactly this under `AB_VIA_STUB=1` (`"$stub" >"$log" 2>&1 &`), and
///    stealing its stream would leave that harness with only the handful of
///    stub lines emitted before this point -- a silently broken instrument,
///    which is the failure mode this whole change exists to remove, not add.
///    An isatty() test alone does NOT catch it: a file is not a TTY.
///  - `isatty(2)`: a developer ran the stub from a terminal and wants their
///    stream.
///
/// What is left -- /dev/null, a pipe to the log daemon, a closed descriptor --
/// is the launchd/Dock case, where nobody is capturing and the output is
/// discarded today. That is the case this exists for.
private func beginRunLog(runtime: URL, identity: String,
                         header: [String]) -> String? {
    if ProcessInfo.processInfo.environment["MACAPK_STUB_LOG"] == "0" { return nil }
    if isatty(2) == 1 { return nil }
    var st = stat()
    if fstat(2, &st) == 0 && (st.st_mode & S_IFMT) == S_IFREG { return nil }
    let dir = runLogDirectory(runtime: runtime)
    // Logs hold app output and paths: owner-only (0700 dir, 0600 files).
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                             attributes: [.posixPermissions: 0o700])
    rotateRunLog(dir: dir, identity: identity)
    // v1.0.1650: HotSpot crash dumps land in this same dir and nothing rotated
    // them (InstallCore.swift JvmCrashDumpPrune; MACAPK_HSERR_PRUNE=0 = off).
    JvmCrashDumpPrune.prune(dir: dir)
    let path = dir.appendingPathComponent("app_\(identity).log").path
    // O_APPEND so the runtime's writes -- and any child process that inherits
    // these descriptors -- append atomically instead of racing on one offset.
    let fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0o600)
    guard fd >= 0 else {
        stubLog("WARN: cannot open run log \(path) (errno \(errno)); "
                + "this launch will leave no diagnostic trail")
        return nil
    }
    var preamble = header.map { "[MacApkAppStub] " + $0 + "\n" }
    preamble.append(contentsOf: stubLogBuffer)
    _ = preamble.joined().withCString { write(fd, $0, strlen($0)) }
    // Point both stdout and stderr at the file. The runtime logs to stderr;
    // stdout is redirected too so nothing an app or a native library prints
    // there is lost. dup2 clears FD_CLOEXEC, so both survive the execv.
    dup2(fd, 1)
    dup2(fd, 2)
    if fd > 2 { close(fd) }
    return path
}

/// Show a modal alert naming the actual problem, then exit. A stub that
/// dies silently is unusable, so every failure path routes through here.
/// `openMacApk` non-nil adds an "Open Mac.apk" button that launches the
/// resolved (or to-be-installed) Mac.apk before exiting.
private func fatalAlert(_ title: String, _ body: String,
                        exitCode: Int32, openMacApk: URL? = nil) -> Never {
    stubLog("FATAL: \(title): \(body)")
    // MACAPK_STUB_NO_UI exists so an unattended harness can MEASURE a failure
    // path. A modal alert in a script blocks until timeout and then reads as a
    // hang rather than as the specific refusal it is, which is what made "the
    // runtime is uninstalled, so this app must not launch" untestable --
    // debug/installtest/run.sh asserts on this exit code. It suppresses only an
    // error REPORT: the process still exits non-zero with the same code, and
    // there is no confirmation and nothing destructive anywhere on this path.
    if ProcessInfo.processInfo.environment["MACAPK_STUB_NO_UI"] == "1" {
        exit(exitCode)
    }
    let app = NSApplication.shared
    app.setActivationPolicy(.regular)
    app.activate()
    let alert = NSAlert()
    alert.messageText = title
    alert.informativeText = body
    alert.alertStyle = .critical
    alert.addButton(withTitle: "OK")
    if openMacApk != nil {
        alert.addButton(withTitle: "Open Mac.apk")
    }
    let response = alert.runModal()
    if response == .alertSecondButtonReturn, let url = openMacApk {
        NSWorkspace.shared.open(url)
    }
    exit(exitCode)
}

/// Absolute path of this executable via dyld, not argv[0]: argv[0] is
/// caller-controlled and empty argv is possible under a raw exec.
private func ownExecutableURL() -> URL {
    var size = UInt32(0)
    _NSGetExecutablePath(nil, &size)
    var buf = [CChar](repeating: 0, count: Int(size) + 1)
    _NSGetExecutablePath(&buf, &size)
    return URL(fileURLWithPath: String(cString: buf)).resolvingSymlinksInPath()
}

// MARK: - Handing a launch's URL to the running instance
//
// LaunchServices delivers a URL (a kAEGetURL Apple Event) to the RUNNING copy
// of an app only when it knows that copy as this bundle. A runtime started
// from the Mac.apk window runs under the run-wrapper's identity
// (org.macapk.run, MacApkLauncherApp.makeRunWrapperExecPath), so a browser
// redirect such as an Epic sign-in's `eos.<id>://epic/auth?code=...` launches
// THIS stub instead and the event is addressed to the stub's process. The stub
// then finds the run lock held and exits, which used to drop the URL: the
// sign-in never finished, and before the runtime held the lock itself
// (RunLock.java, v1.0.1724) a second copy of the app booted with it instead
// (owner, 2026-10-06, RL Sideswipe: "Pressing allow ... caused it to open a
// second rocket league"). On a device the browser's NEW_TASK start reaches
// the running task.
//
// So before exiting, the stub takes its launch's Apple Events and hands every
// URL to the running instance the way a cross-app start reaches a running
// target: a file in the bundle's inbox (Contents/android/.macapk/host-urls,
// mode 0600, written under a dot name and renamed so a reader never sees half
// of one), then one ring of the target's doorbell (CrossAppDoorbell.java),
// which drains it through HostUrlIntake exactly as a GURL the runtime had
// received itself. The activation below is the backstop: it drains too.
//
// MACAPK_STUB_URL_HANDOFF=0 turns it off (the stub exits without waiting for
// its launch event, as before). MACAPK_STUB_URL_WAIT_MS bounds the wait for
// that event (default 1500): LaunchServices sends it right after the process
// checks in, so the bound is only paid when no event comes at all.
private final class LaunchEventCollector: NSObject, NSApplicationDelegate {
    var urls: [String] = []
    var sawLaunchEvent = false

    // AppKit installs its own core handlers before this callback, so handlers
    // set here win (the documented place to take over kAEGetURL).
    func applicationWillFinishLaunching(_ notification: Notification) {
        let em = NSAppleEventManager.shared()
        em.setEventHandler(self, andSelector: #selector(handleGetURL(_:withReplyEvent:)),
                           forEventClass: AEEventClass(kInternetEventClass),
                           andEventID: AEEventID(kAEGetURL))
        for id in [kAEOpenApplication, kAEReopenApplication, kAEOpenDocuments] {
            em.setEventHandler(self, andSelector: #selector(handleOther(_:withReplyEvent:)),
                               forEventClass: AEEventClass(kCoreEventClass),
                               andEventID: AEEventID(id))
        }
    }

    @objc func handleGetURL(_ event: NSAppleEventDescriptor,
                            withReplyEvent reply: NSAppleEventDescriptor) {
        if let s = event.paramDescriptor(forKeyword: AEKeyword(keyDirectObject))?.stringValue,
           !s.isEmpty {
            urls.append(s)
        }
        sawLaunchEvent = true
    }

    @objc func handleOther(_ event: NSAppleEventDescriptor,
                           withReplyEvent reply: NSAppleEventDescriptor) {
        sawLaunchEvent = true
    }
}

enum LaunchUrlHandoff {
    static func forward(bundleRoot: URL, androidRoot: URL) {
        let env = ProcessInfo.processInfo.environment
        if env["MACAPK_STUB_URL_HANDOFF"] == "0" { return }
        let waitMs = Int(env["MACAPK_STUB_URL_WAIT_MS"] ?? "") ?? 1500
        let urls = collect(timeout: Double(max(0, waitMs)) / 1000)
        guard !urls.isEmpty else { return }
        let dir = androidRoot.appendingPathComponent(".macapk/host-urls", isDirectory: true)
        var dropped = 0
        for (i, u) in urls.enumerated() where drop(u, index: i, into: dir) {
            dropped += 1
        }
        let answer = ringDoorbell(bundleRoot: bundleRoot) ?? "no doorbell (activation drains it)"
        stubLog("handed \(dropped) of \(urls.count) launch URL(s) to the running instance "
                + "(\(urls.map(redacted).joined(separator: ", "))); doorbell: \(answer)")
    }

    /// The launch's own Apple Events: wait for the first one (LaunchServices
    /// sends the GURL, or an 'oapp' for a plain open), then a short settle for
    /// any further GURL, collecting every URL.
    private static func collect(timeout: TimeInterval) -> [String] {
        let app = NSApplication.shared
        // No Dock tile or menu bar of its own: this process only relays.
        app.setActivationPolicy(.prohibited)
        let collector = LaunchEventCollector()
        app.delegate = collector   // weak; `collector` lives to the end of this function
        app.finishLaunching()
        let deadline = Date().addingTimeInterval(timeout)
        var settleUntil: Date? = nil
        while Date() < (settleUntil ?? deadline) {
            if let ev = app.nextEvent(matching: .any,
                                      until: Date(timeIntervalSinceNow: 0.02),
                                      inMode: .default, dequeue: true) {
                app.sendEvent(ev)
            }
            if collector.sawLaunchEvent && settleUntil == nil {
                settleUntil = min(deadline, Date().addingTimeInterval(0.15))
            }
        }
        withExtendedLifetime(collector) {}
        return collector.urls
    }

    /// One URL as <epochMillis>-<index>-<random>.url, FIFO by name (the
    /// reader sorts), never visible half-written.
    private static func drop(_ url: String, index: Int, into dir: URL) -> Bool {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true,
                                   attributes: [.posixPermissions: 0o700])
        } catch {
            stubLog("WARN: cannot create \(dir.path): \(error.localizedDescription)")
            return false
        }
        let ms = Int64(Date().timeIntervalSince1970 * 1000)
        let name = String(format: "%013lld-%03d-%08x.url", ms, index, arc4random())
        let tmp = dir.appendingPathComponent("." + name).path
        let final = dir.appendingPathComponent(name).path
        let fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard fd >= 0 else {
            stubLog("WARN: cannot write \(tmp) (errno \(errno))")
            return false
        }
        let bytes = Array(url.utf8)
        let n = bytes.withUnsafeBufferPointer { write(fd, $0.baseAddress, $0.count) }
        close(fd)
        guard n == bytes.count, rename(tmp, final) == 0 else {
            unlink(tmp)
            stubLog("WARN: cannot publish \(final) (errno \(errno))")
            return false
        }
        return true
    }

    /// CrossAppDoorbell.ring in Swift: the same path (run/door-<first 16 hex of
    /// SHA-256(canonical bundle path)>.sock), the same one word. Returns the
    /// target's answer, or nil when nobody is listening.
    private static func ringDoorbell(bundleRoot: URL) -> String? {
        let canon = bundleRoot.resolvingSymlinksInPath().path
        let hex = SHA256.hash(data: Data(canon.utf8)).prefix(8)
            .map { String(format: "%02x", $0) }.joined()
        let path = Paths.runDir.appendingPathComponent("door-\(hex).sock").path
        guard path.utf8.count <= 102, FileManager.default.fileExists(atPath: path) else {
            return nil
        }
        let s = socket(AF_UNIX, SOCK_STREAM, 0)
        guard s >= 0 else { return nil }
        defer { close(s) }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let ok = withUnsafeMutableBytes(of: &addr.sun_path) { raw -> Bool in
            let p = Array(path.utf8)
            guard p.count < raw.count else { return false }
            raw.copyBytes(from: p)
            raw[p.count] = 0
            return true
        }
        guard ok else { return nil }
        addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard rc == 0 else { return nil }
        var tv = timeval(tv_sec: 0, tv_usec: 500_000)
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        let word = Array("DRAIN\n".utf8)
        _ = word.withUnsafeBufferPointer { write(s, $0.baseAddress, $0.count) }
        shutdown(s, SHUT_WR)
        var buf = [UInt8](repeating: 0, count: 64)
        let n = buf.withUnsafeMutableBufferPointer { read(s, $0.baseAddress, $0.count) }
        guard n > 0 else { return "no answer" }
        return String(decoding: buf[0..<n], as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Scheme, authority and path only: an OAuth redirect's query is a
    /// one-time authorization code (HostUrlIntake.redact does the same).
    private static func redacted(_ url: String) -> String {
        guard let cut = url.firstIndex(where: { $0 == "?" || $0 == "#" }) else { return url }
        return String(url[..<cut]) + " (+\(url.distance(from: cut, to: url.endIndex)) chars)"
    }
}

// MARK: - Entry point

@main
struct AppStubMain {
    /// LEGACY: the names a bundle generated before v1.0.1585 kept the dropped
    /// file under, in `Contents/Resources/`. Since v1.0.1585 the ONE copy of
    /// the app lives where Android keeps it, `Contents/android/data/app/
    /// ~~<nonce>/<pkg>-<nonce>/base.apk` (+ `split_*.apk`), and nothing is
    /// retained in Resources (owner: "i dont want mac.apk to save a copy of
    /// the apks anymore ever"). These names stay only so an old bundle still
    /// launches once; the runtime then lays the install dir down, and the
    /// next launch deletes the Resources copy. Keep in step with
    /// InstallCore's `ApkRetention.bundledApkNames`.
    static let retainedApkNames = ["base.apk", "base.apkm", "base.xapk",
                                   "base.apks", "base.apkx"]

    /// The installed package's base.apk under `<androidRoot>/data/app/
    /// <dir-nonce>/<pkg-nonce>/`, or nil when the runtime has not laid it
    /// down yet (a bundle from before v1.0.1585 on its first launch). Two
    /// fixed levels, exactly Android's layout. The runtime writes the
    /// `.installed` marker beside base.apk only after the WHOLE split set is
    /// in place and verified (apk_unpacker.cpp EnsureInstalledPackage), and
    /// that marker is what licenses deleting the legacy Resources copy: a
    /// base.apk without it is an install that never completed.
    static let installedMarker = ".installed"

    static func installedBaseApk(androidRoot: URL, package: String? = nil) -> URL? {
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
                   fm.fileExists(atPath: pkgDir.appendingPathComponent(installedMarker).path) {
                    return base
                }
            }
        }
        return nil
    }

    static func main() {
        let fm = FileManager.default

        // 1. Resolve our own bundle from the executable location:
        //    <bundle>.app/Contents/MacOS/<exec>.
        let exe = ownExecutableURL()
        let contents = exe.deletingLastPathComponent().deletingLastPathComponent()
        guard contents.lastPathComponent == "Contents" else {
            fatalAlert("This app is damaged",
                       "The launcher stub is not inside an app bundle "
                       + "(ran from \(exe.path)). Reinstall the app from Mac.apk.",
                       exitCode: 78)
        }
        let bundleRoot = contents.deletingLastPathComponent()

        // 2. Identity comes from our own Info.plist. MacapkPackage carries
        //    the RAW Android package name; CFBundleIdentifier only has the
        //    sanitized form (bundle-id charset is lossy, "_" became "-").
        let plistURL = contents.appendingPathComponent("Info.plist")
        guard let plistData = try? Data(contentsOf: plistURL),
              let plist = (try? PropertyListSerialization.propertyList(
                    from: plistData, format: nil)) as? [String: Any] else {
            fatalAlert("This app is damaged",
                       "Could not read \(plistURL.path). Reinstall the app from Mac.apk.",
                       exitCode: 78)
        }
        guard let package = plist["MacapkPackage"] as? String, !package.isEmpty else {
            fatalAlert("This app is damaged",
                       "Info.plist has no MacapkPackage key, so the stub does not "
                       + "know which Android package it belongs to. Reinstall the "
                       + "app from Mac.apk.",
                       exitCode: 78)
        }
        // The Android label. CFBundleName, not CFBundleDisplayName: the latter
        // is the bundle's FILE name (which may carry a collision suffix) so
        // that LaunchServices shows the label from InfoPlist.strings -- see
        // BundleDisplayName. Bundles installed before that shape get it here,
        // once, so an already-installed "Calculator (calculator).app" shows
        // "Calculator" from its next launch on, with no reinstall.
        let label = BundleDisplayName.label(fromPlist: plist) ?? package
        if BundleDisplayName.repairIfNeeded(bundle: bundleRoot) {
            stubLog("display name: \(bundleRoot.lastPathComponent) now shows as \u{201C}\(label)\u{201D}")
        }
        let versionName = (plist["MacapkVersionName"] as? String)
            ?? (plist["CFBundleShortVersionString"] as? String) ?? "?"
        // The registry row identity. dirName, NOT package: the live registry
        // holds multiple rows per package (three for hillclimb), so package
        // keying would touch the wrong row. Optional so bundles generated
        // before this key existed still launch (they just do not record).
        let dirName = plist["MacapkDirName"] as? String
        stubLog("starting \(package) (\(label) \(versionName)) from \(bundleRoot.path)")

        // 3. Resolve the ONE shared runtime.
        //
        //    ⚠ CONTRACT CHANGED 2026-08-12 (Runtime Install/Uninstall). We
        //    exec the STAGED runtime under
        //        ~/Library/Application Support/Mac.apk/runtime/
        //    and NEVER the copy inside Mac.apk.app, even though one is still
        //    shipped there. That is not a preference, it is the feature: the
        //    owner asked for an Uninstall button that removes the shared
        //    runtime while keeping the control panel and every installed app.
        //    While this list was a FALLBACK CHAIN ending at
        //    Mac.apk.app/Contents/Resources/macapk-runtime, removing the
        //    staged copy changed nothing a user could observe -- the app just
        //    launched off the next candidate and the button looked broken.
        //    So Mac.apk.app is resolved below for three things only: the
        //    payload to stage FROM, the default Dock icon, and the "Open
        //    Mac.apk" button on the failure alert. Never as an exec candidate.
        //    See RuntimeInstall in InstallCore.swift for the full rationale.
        //
        //    Mac.apk.app itself still resolves the way it always did:
        //    MACAPK_CANONICAL_APP -> /Applications -> LaunchServices (a
        //    fallback only, because LS has been observed carrying stale
        //    org.macapk.launcher registrations pointing at deleted checkouts).
        //
        //    ⚠ THE COMMENT ABOVE DESCRIBED AN ORDER THE CODE DID NOT HAVE
        //    (fixed 2026-08-19). `RuntimeInstall.sourceContents` ranks the
        //    caller's `extra` list ABOVE /Applications -- it has to, because
        //    macapk-installhelper passes its OWN Contents there and must stage
        //    from the bundle it ships in. So handing it the LaunchServices
        //    answer unconditionally made LS BEAT the canonical app, the exact
        //    opposite of "a fallback only", and LS is the one source here known
        //    to have pointed at deleted checkouts. It was not the cause of the
        //    stale-runtime bug -- LS resolves to /Applications/Mac.apk.app on
        //    this machine today -- but it is the mechanism by which a stub
        //    could stage a guest app off a stale tree, silently.
        //
        //    So offer LS only when the canonical path has no payload to stage
        //    from. The existence test mirrors sourceContents' own (the runtime
        //    binary may legitimately be non-executable there: that is what
        //    Uninstall does to it), so "canonical present but disabled" still
        //    correctly resolves to the canonical rather than wandering off to
        //    whatever LS remembers.
        //
        //    "The canonical path" is the fixed locations sourceContents() knows
        //    (/Applications, then ~/Applications since GitHub #13), so a
        //    per-user install resolves by path like /Applications does and LS
        //    stays the fallback for anywhere else.
        var appCandidates: [URL] = []
        let canonicalHasPayload = RuntimeInstall.sourceContents() != nil
        if !canonicalHasPayload,
           let ls = NSWorkspace.shared.urlForApplication(
                withBundleIdentifier: "org.macapk.launcher") {
            appCandidates.append(ls.appendingPathComponent("Contents", isDirectory: true))
        }
        let sourceContents = RuntimeInstall.sourceContents(extra: appCandidates)
        let macapkApp = sourceContents?.deletingLastPathComponent()
        // Tell the runtime which Mac.apk.app this is. The staged runtime ships
        // no install helper and no location helper, so the guest side looks
        // them up in the control panel (PackageInstallerBackend.locateHelper,
        // LocationBridge.locateHelper); with Mac.apk outside /Applications they
        // would otherwise find nothing (store installs and the location prompt
        // fail). An explicit MACAPK_CANONICAL_APP from the caller wins.
        if let app = macapkApp,
           (ProcessInfo.processInfo.environment["MACAPK_CANONICAL_APP"] ?? "").isEmpty {
            setenv("MACAPK_CANONICAL_APP", app.path, 1)
        }

        // Self-heal, part one: THIS FILE. The stub inside a generated bundle
        // is a copy of Mac.apk.app's template, frozen at install time; after
        // a Mac.apk update it is the one piece of an installed app nothing
        // else refreshes (the runtime below self-heals, the Dock-identity link
        // is remade every launch). So compare ourselves with the installed
        // Mac.apk's template and, when we differ, swap the template in (a new
        // inode + rename, see StubRefresh) and re-execute it with our own
        // argv and environment: the launch that discovers the update already
        // runs the new stub, and every step below runs in the new code. The
        // env marker stops a stub that still differs after a swap (a
        // read-only bundle, a copy that raced) from looping; it then runs on
        // as it is, which is what happened on every launch before this.
        //
        // Before the runtime stage on purpose: a stub old enough to mishandle
        // the current bundle layout must hand over BEFORE it reaches the code
        // that would mishandle it. Never fatal -- a failure here is logged and
        // this launch proceeds on the old stub.
        if let src = sourceContents {
            let template = StubRefresh.template(in: src)
            let env = ProcessInfo.processInfo.environment
            if let stale = StubRefresh.isStale(executable: exe, template: template), stale {
                if env["MACAPK_STUB_REFRESHED"] == nil {
                    do {
                        try StubRefresh.refresh(executable: exe, from: template)
                        stubLog("stub differed from the installed Mac.apk's template; "
                                + "refreshed \(exe.path) and re-executing")
                        setenv("MACAPK_STUB_REFRESHED", "1", 1)
                        execv(exe.path, CommandLine.unsafeArgv)
                        // Only reached when execv itself failed.
                        stubLog("WARN: re-exec of the refreshed stub failed (errno "
                                + "\(errno), \(String(cString: strerror(errno)))); "
                                + "continuing this launch on the previous stub")
                    } catch {
                        stubLog("WARN: could not refresh this app's stub from "
                                + "\(template.path): \(error.localizedDescription); "
                                + "continuing on the installed one")
                    }
                } else {
                    stubLog("WARN: stub still differs from the template after a refresh; "
                            + "continuing on this one")
                }
            } else if env["MACAPK_STUB_REFRESHED"] != nil {
                stubLog("stub refreshed from the installed Mac.apk (re-executed)")
            }
            // The marker has done its job; the runtime and the app must not
            // inherit it.
            unsetenv("MACAPK_STUB_REFRESHED")
        }

        // Self-heal, part two: the runtime. A build refreshes the payload inside Mac.apk.app and
        // knows nothing about the staged copy, so without this every app would
        // run yesterday's runtime after a rebuild. It is a no-op once the
        // staged build-id matches, and -- the part that matters -- it REFUSES
        // to run at all once the user has pressed Uninstall.
        let runtimeURL = RuntimeInstall.ensureStaged(extraSources: appCandidates) {
            stubLog($0)
        }
        guard let runtime = runtimeURL else {
            let st = RuntimeInstall.state(extraSources: appCandidates)
            // Two genuinely different failures, and telling them apart is the
            // difference between "press the button in Mac.apk" and "your
            // install is broken". Say which one this is.
            let body: String
            if st.removedByUser {
                body = "The Mac.apk runtime is not installed, so \(label) cannot run.\n\n"
                     + "\(label) and its data are still installed and untouched. "
                     + "Open Mac.apk and choose Runtime: Install to make it work again."
            } else if !st.sourceAvailable {
                body = "\(label) runs on the Mac.apk runtime, but Mac.apk.app could not "
                     + "be found in /Applications, ~/Applications or via Launch Services, "
                     + "so there is nothing to install the runtime from. "
                     + "Install Mac.apk and try again."
            } else {
                body = "\(label) runs on the Mac.apk runtime, but it could not be "
                     + "staged from Mac.apk.app. Open Mac.apk and choose "
                     + "Runtime: Install to repair it."
            }
            fatalAlert("The Mac.apk runtime is not installed", body,
                       exitCode: 66, openMacApk: macapkApp)
        }
        // The runtime's identity stamp (JAVA_ID/NATIVE_ID, written by the
        // build and staged with the payload). One log line that makes a
        // stale-runtime run diagnosable after the fact; never computed here.
        if let ids = RuntimeInstall.buildId(at: RuntimeInstall.stagedRoot) {
            stubLog("runtime identity: "
                    + ids.split(whereSeparator: \.isNewline).joined(separator: " "))
        } else {
            stubLog("runtime identity: no build-id.txt (pre-freshness build)")
        }
        stubLog("resolved runtime: \(runtime.path)")

        let androidRoot = contents.appendingPathComponent("android", isDirectory: true)

        // 4. Single instance. flock on run/<identity>.pid is the aliveness
        //    signal (released by the kernel when the holder dies, so no
        //    stale-pid heuristics). The fd is opened WITHOUT O_CLOEXEC on
        //    purpose: it must survive the execv below so the lock lives
        //    exactly as long as the runtime process.
        //
        //    Keyed by DIRNAME, not package (audit S8f): the registry's row
        //    identity is dirName precisely because several versions of one
        //    package legitimately coexist (three hillclimb rows on the live
        //    machine, one .app per version by owner decision). A
        //    package-keyed pidfile made the second version's stub "activate"
        //    the OTHER version instead of launching -- breaking the
        //    documented A/B workflow. Pre-dirName bundles fall back to the
        //    package key, which for them is the old behaviour.
        //
        //    Taken BEFORE the payload is resolved (it used to come after):
        //    moving the app's data out of the bundle (step 5) must never race a
        //    running copy of the app, and the lock is what proves there is none.
        let runDir = Paths.runDir
        try? fm.createDirectory(at: runDir, withIntermediateDirectories: true)
        let pidPath = runDir.appendingPathComponent("\(dirName ?? package).pid").path
        let fd = open(pidPath, O_RDWR | O_CREAT, 0o644)
        // Whether THIS launch holds the lock. Only then may step 5 move the
        // app's data: a stale lock (below) means an orphaned helper of the last
        // run may still be writing into the tree.
        var holdsLock = false
        if fd >= 0 {
            if flock(fd, LOCK_EX | LOCK_NB) != 0 {
                // A live holder: bring the existing instance forward instead
                // of double-launching. The pid in the file targets the
                // activation; the flock already proved liveness.
                var text = ""
                if let h = FileHandle(forReadingAtPath: pidPath),
                   let d = try? h.readToEnd() {
                    text = String(decoding: d, as: UTF8.self)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                }
                // A STALE lock: the pid we wrote is gone, yet the flock is still
                // held. That is a process that inherited the lock fd from an
                // instance that has since quit (a helper it spawned before the
                // runtime marked the fd close-on-exec, or a guest fork that
                // outlived it). Activating the dead pid did nothing and the app
                // simply never opened (owner, 2026-10-19: "apps are launching
                // in the background again"). The app is not running, so launch
                // it -- without the lock this once, which only costs its
                // permission dialogs until the orphan exits.
                let holderPid = Int32(text) ?? 0
                if holderPid > 0 && kill(holderPid, 0) != 0 && errno == ESRCH {
                    stubLog("WARN: pidfile \(pidPath) is locked but pid \(holderPid) is gone "
                            + "(an orphaned child holds the lock); launching anyway")
                    close(fd)
                } else {
                    stubLog("already running (pidfile \(pidPath) locked, pid=\(text)); activating")
                    // A browser link LaunchServices sent to THIS process goes to
                    // the running instance, as a browser's start reaches the
                    // running task on a device (LaunchUrlHandoff below).
                    LaunchUrlHandoff.forward(bundleRoot: bundleRoot, androidRoot: androidRoot)
                    if let pid = Int32(text),
                       let running = NSRunningApplication(processIdentifier: pid) {
                        // v1.0.1642: BACK at an app's launcher root hides it (the
                        // macOS stand-in for Android's "task moved behind home").
                        // Opening the app again must bring it back, as tapping its
                        // icon does on a device; unhide is what fires the runtime's
                        // NSApplicationDidUnhideNotification -> the task resumes.
                        if running.isHidden { _ = running.unhide() }
                        running.activate(options: [])
                    }
                    exit(0)
                }
            } else {
                holdsLock = true
                ftruncate(fd, 0)
                lseek(fd, 0, SEEK_SET)
                let pidText = "\(getpid())\n"
                _ = pidText.withCString { write(fd, $0, strlen($0)) }
                // fd intentionally left open (and inherited) across execv.
            }
        } else {
            stubLog("WARN: cannot open pidfile \(pidPath) (errno \(errno)); "
                    + "continuing without single-instance protection")
        }

        // 5. The app's data lives OUTSIDE the bundle, in
        //    ~/Library/Application Support/MacApk/<package> (AppDataLayout).
        //    A bundle from before that layout is moved over here, once, under
        //    the lock above; a moved one gets its data folder re-created if the
        //    user deleted it (clear data) and its link back to the bundle
        //    repaired if the bundle was moved. The runtime is still handed
        //    Contents/android (now a link), so every guest-visible path is
        //    unchanged. Never fatal: on any failure the data stays where it is.
        //    Without the lock (stale lock, or no pidfile) only the repairs run;
        //    the move waits for a launch that holds it.
        AppDataLayout.ensure(bundle: bundleRoot, package: package, mayMove: holdsLock,
                             log: { stubLog($0) })

        // 6. Our payload: the installed package and the app-data tree, both
        //    reached through Contents/android. Since v1.0.2251 the tree lives
        //    in the app's data folder and data/app links back to this
        //    bundle's Contents/app (step 5), so the paths below are the ones
        //    they always were.
        //
        //    The installed base.apk under android/data/app is THE app (the
        //    Android layout; one copy, nothing retained anywhere else). A
        //    bundle generated before v1.0.1585 still carries the dropped file
        //    in Resources/ and no install dir yet: launch from Resources ONCE,
        //    the runtime lays the install dir down (apk_unpacker.cpp
        //    EnsureInstalledPackage), and from the next launch on the
        //    Resources copy is redundant and removed here -- the stub owns
        //    the bundle.
        // v1.0.1641: an in-place store update that died mid-way is undone here,
        // before we pick what to launch (InPlaceRecovery).
        InPlaceRecovery.recoverIfInterrupted(androidRoot: androidRoot,
                                             log: { FileHandle.standardError.write(Data("[MacApkAppStub] \($0)\n".utf8)) })
        var apkURL: URL? = installedBaseApk(androidRoot: androidRoot, package: package)
        var legacyRetained: [URL] = []
        for name in retainedApkNames {
            let cand = contents.appendingPathComponent("Resources/\(name)")
            if fm.fileExists(atPath: cand.path) { legacyRetained.append(cand) }
        }
        if let installed = apkURL {
            for stale in legacyRetained {
                do {
                    try fm.removeItem(at: stale)
                    stubLog("removed legacy retained copy \(stale.lastPathComponent); "
                            + "the app now lives at \(installed.path)")
                } catch {
                    stubLog("could not remove legacy retained copy \(stale.path): \(error)")
                }
            }
        } else if let first = legacyRetained.first {
            apkURL = first
            stubLog("pre-v1.0.1585 bundle: launching from \(first.lastPathComponent) "
                    + "once; the runtime installs it under android/data/app")
        }
        guard let apk = apkURL else {
            fatalAlert("The app's package file is missing",
                       "\(bundleRoot.path)/Contents/android/data/app has no installed "
                       + "base.apk. Open Mac.apk to repair or reinstall \(label).",
                       exitCode: 68, openMacApk: macapkApp)
        }
        // Skeleton per the bundle contract: data/data (the PARENT -- the
        // runtime itself creates and versions the app dir as <pkg>_<ver>,
        // main.cpp SetupAndroidRoot; a pre-created bare data/data/<pkg> was
        // a ghost dir re-created on every launch, audit S10) and
        // .macapk/code for the regenerable versioned caches. Created here
        // too (not just at generation) so a hand-pruned bundle self-heals
        // instead of failing -- and creating them doubles as the
        // writability probe behind the alert below.
        let dataDir = androidRoot.appendingPathComponent("data/data", isDirectory: true)
        let codeDir = androidRoot.appendingPathComponent(".macapk/code", isDirectory: true)
        do {
            try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
            try fm.createDirectory(at: codeDir, withIntermediateDirectories: true)
        } catch {
            fatalAlert("Could not prepare app data",
                       "Creating \(androidRoot.path) failed: "
                       + error.localizedDescription
                       + " (is the disk full, or the bundle read-only?)",
                       exitCode: 73)
        }

        // 7. Record the launch in the registry (the UI's "last launched"
        //    column). Bounded: touchLastLaunched gives up past timeoutMs
        //    rather than delaying the app start, so an unconditional call on
        //    the launch path is safe. Runs only for a REAL start (the
        //    already-running path above exits before this).
        RegistryStore.shared.log = { stubLog($0) }
        if let dirName {
            RegistryStore.shared.touchLastLaunched(dirName: dirName, timeoutMs: 200)
        } else {
            stubLog("no MacapkDirName in Info.plist (pre-dirName bundle); "
                    + "not recording lastLaunchedAt")
        }
        //    ... and settle the registry against the disk BEFORE the runtime
        //    starts. This is the moment that matters for the guest: the
        //    JVM-side reader (MacApkPackageManager.InstalledRegistry) parses
        //    installed_apps.json directly, in another process and another
        //    language, so it cannot run the reconciliation itself -- it
        //    consumes the `state` this writes. Running it here means a store
        //    launched AFTER the user trashed some other app is told the truth
        //    on its very first getPackageInfo call, with no window.
        //
        //    Bounded and cheap for the same reason touchLastLaunched is:
        //    reconcileBundles writes ONLY when a row actually moved, and gives
        //    up rather than delaying the app start if another process holds
        //    the lock (the in-memory answer was already right; the next writer
        //    persists it). It is a separate call from touchLastLaunched
        //    because a pre-dirName bundle has no dirName and must still leave
        //    a correct registry behind.
        do {
            let moved = try RegistryStore.shared.reconcileBundles(timeoutMs: 200)
            if !moved.isEmpty {
                stubLog("registry: \(moved.count) bundle transition(s) -- "
                        + moved.map { "\($0.package) \($0.from)->\($0.to)" }
                               .joined(separator: ", "))
            }
        } catch {
            stubLog("registry: skipped bundle reconcile (\(error.localizedDescription))")
        }

        // 8. Environment, matching the launcher's launch() contract so the
        //    runtime presents this process as the app: cocoa_bridge.mm
        //    adopts Dock icon + window title from MACAPK_APP_ICON /
        //    MACAPK_APP_LABEL / MACAPK_DEFAULT_ICON. Cache dir points at the
        //    versioned code-cache side of the bundle's android tree.
        setenv("MACAPK_APP_CACHE_DIR", codeDir.path, 1)
        setenv("MACAPK_APP_LABEL", label, 1)
        //
        //    SELF-HEAL THE BUNDLE ICON FIRST. A bundle can legitimately reach
        //    this point with no icon at all: BundleGenerator.generate() runs
        //    BEFORE the app is extracted, so at generate time there is no real
        //    icon to stamp, and an app whose android:icon is an
        //    <adaptive-icon>/vector has no plain raster mipmap to copy either --
        //    rasterizing it needs the framework's real Drawable pipeline, which
        //    only exists once a JVM has run. So the app's own icon.png often
        //    appears in its data dir only AFTER a first launch, and nothing was
        //    copying it back into the bundle. MEASURED 2026-08-17: PlayTube,
        //    installed from inside F-Droid, was the only one of 12 installed
        //    apps with no AppIcon.icns, while its data dir held a perfectly good
        //    3219-byte icon.png -- so Finder, Launchpad and the pre-launch Dock
        //    tile all showed a generic icon, and MACAPK_APP_ICON below was not
        //    set either, so even the RUNNING app fell back to the default tile.
        //    Stamping here fixes already-installed apps with no reinstall, on
        //    the next launch, which is why it lives in the stub: this is the
        //    only process that runs on every Dock launch.
        //    Cheap: backfillIfMissing returns immediately once an icns exists,
        //    so the sips/iconutil work happens at most once per bundle.
        if let dirName {
            let appIcon = androidRoot
                .appendingPathComponent("data/data", isDirectory: true)
                .appendingPathComponent(dirName, isDirectory: true)
                .appendingPathComponent("icon.png")
            // ...and the COMMON case: an icns exists but it is the generic
            //    placeholder generate() stamped (every <adaptive-icon> app), so
            //    Launchpad and Finder showed the placeholder while the running
            //    app's Dock tile showed the real icon (owner 2026-09-29: Open
            //    Sudoku, Smash Hit). Recognised by content against the same
            //    default icon files the generator and the runtime use.
            let placeholders = [
                runtime.deletingLastPathComponent().appendingPathComponent("macapk_default_icon.png"),
                macapkApp?.appendingPathComponent("Contents/Resources/macapk_default_icon.png"),
            ].compactMap { $0 }
            if IconStamp.backfillIfMissingOrPlaceholder(bundle: bundleRoot, from: appIcon,
                                                        placeholders: placeholders) {
                stubLog("stamped bundle icon from \(appIcon.path)")
            }
        }
        let iconPNG = contents.appendingPathComponent("Resources/AppIcon.png")
        if fm.fileExists(atPath: iconPNG.path) {
            setenv("MACAPK_APP_ICON", iconPNG.path, 1)
        }
        // The fallback Dock icon ships in the runtime payload, so take it from
        // the staged copy (which is what we are about to exec) and fall back to
        // the control panel's copy only for a payload staged before it was
        // listed. Purely cosmetic either way: a miss means the generic tile.
        for icon in [runtime.deletingLastPathComponent()
                        .appendingPathComponent("macapk_default_icon.png"),
                     macapkApp?.appendingPathComponent(
                        "Contents/Resources/macapk_default_icon.png")].compactMap({ $0 }) {
            if fm.fileExists(atPath: icon.path) {
                setenv("MACAPK_DEFAULT_ICON", icon.path, 1)
                break
            }
        }

        // 9. Open the run log and point stderr at it, so this launch leaves
        //    evidence. Must be the LAST thing before execv: everything above
        //    can still fatalAlert, and a modal the user reads beats a file
        //    nobody knows about. The stub's own lines are not lost by waiting
        //    -- stubLog buffers them and beginRunLog replays them into the
        //    file, so the log opens with the full resolution trail (which
        //    runtime was staged, its build identity, which APK) already in it.
        //
        //    Keyed by dirName, matching the pidfile and the registry row: one
        //    package legitimately has several installed versions (three
        //    hillclimb bundles on the owner's machine), and a package-keyed
        //    log would have them overwrite each other's evidence.
        let logIdentity = dirName ?? package
        let runLogPath = beginRunLog(
            runtime: runtime, identity: logIdentity,
            header: ["---- run log \(ISO8601DateFormatter().string(from: Date())) ----",
                     "app: \(label) \(versionName) (\(package)), dirName=\(logIdentity)",
                     "bundle: \(bundleRoot.path)",
                     "stub pid: \(getpid())"])
        if let runLogPath {
            stubLog("run log: \(runLogPath)")
        }

        // 10. Become the runtime. execv inherits environ (with the setenv
        //    additions above), the pidfile fd, and the redirected fd 1 + 2.
        //
        //    "No fork, so the Dock/LS identity survives" was the assumption
        //    this comment used to make, and it is false: the Dock tile
        //    tooltip, the Cmd-Tab switcher and the bold app-menu title all
        //    read NSRunningApplication.localizedName / ProcessInfo.processName,
        //    which AppKit derives from CFBundleGetMainBundle() for the
        //    CURRENTLY RUNNING EXECUTABLE the moment the process calls
        //    NSApplication.shared (cocoa_bridge.mm CocoaInit). Bundle
        //    resolution walks up from _NSGetExecutablePath()'s raw invoked
        //    path looking for Contents/Info.plist. The shared runtime lives
        //    OUTSIDE any .app bundle
        //    (~/Library/Application Support/Mac.apk/runtime/Resources/), so
        //    that walk finds nothing and every one of those APIs falls back
        //    to the bare executable name, "macapk-runtime" -- reproduced
        //    directly: lsappinfo/NSRunningApplication read "macapk-runtime"
        //    for a Dock-launched app despite its Info.plist carrying the
        //    right CFBundleName throughout.
        //
        //    Fix: exec through a symlink placed INSIDE this bundle's
        //    Contents/MacOS/, pointing at the real shared binary, instead of
        //    the real path directly. Verified empirically (not assumed) on
        //    this macOS version: _NSGetExecutablePath returns the raw
        //    (unresolved) invoked path, so Bundle.main / NSRunningApplication
        //    / lsappinfo walk up from Contents/MacOS/ and find THIS bundle's
        //    Info.plist -- CFBundleName, CFBundleDisplayName, bundle
        //    identifier, everything correct, no private LaunchServices call
        //    and no argv[0] lie. The runtime's OWN resource resolution is
        //    unaffected: main.cpp's BinaryDir() (used for the JRE, every
        //    jar, the bionic shim dylib, the MoltenVK ICD) feeds the same
        //    _NSGetExecutablePath path through fs::weakly_canonical, which
        //    resolves the symlink back to the real staged directory --
        //    confirmed with a standalone C++ probe against this exact code
        //    shape before landing this change. The link's name deliberately
        //    does not match CFBundleExecutable (that name is the stub's own
        //    file in this same directory): ProcessInfo.processName picks up
        //    the link's name rather than CFBundleExecutable, so `ps` /
        //    Activity Monitor's raw process-name column shows the link name
        //    rather than "macapk-runtime". That is turned into an ASSET
        //    below by naming the link after the app; the Dock tile, Cmd-Tab
        //    switcher and app-menu title read CFBundleName/CFBundleDisplayName
        //    and are correct either way.
        //    Refreshed on every launch rather than only at bundle-generation
        //    time, so it self-heals across a runtime update without needing
        //    every installed bundle regenerated, matching ensureStaged's own
        //    self-heal posture above. Falls back to exec'ing the runtime
        //    directly if the symlink can't be created (e.g. a read-only
        //    bundle) -- this is a Dock-identity cosmetic, never worth
        //    blocking the app launch over.
        //    The link is named after the APP (dot-prefixed so it stays hidden
        //    and cannot collide with CFBundleExecutable, which is already the
        //    app's own name -- that file is the stub sitting in this same
        //    directory). ProcessInfo.processName reports the link's basename,
        //    so this is what makes `ps`, Activity Monitor and -- the one that
        //    actually matters -- every .ips crash report's procName field say
        //    ".Aurora Store" instead of "macapk-runtime". Before this, EVERY
        //    crash report from EVERY installed app carried the identical
        //    procName, so a DiagnosticReports triage pass could not tell which
        //    app had died without correlating timestamps by hand.
        //    MEASURED on this macOS version before landing, with a standalone
        //    probe over three placements: a flat link (any name) resolves the
        //    bundle correctly (CFBundleName = the app), while nesting the link
        //    one directory deeper to drop the leading dot BREAKS the CFBundle
        //    walk outright (bundlePath = the subdirectory, CFBundleName = nil)
        //    -- i.e. it would have traded the Dock/menu-title fix this whole
        //    change exists for in exchange for a prettier `ps` line. Keep the
        //    link FLAT in Contents/MacOS/.
        //    "/" is the only byte illegal in a POSIX filename; a label
        //    containing one would silently make symlink() fail with ENOENT and
        //    drop us onto the fallback path below, so fold it out first.
        let linkName = "." + label.replacingOccurrences(of: "/", with: "-")
        let execLink = contents.appendingPathComponent("MacOS/\(linkName)")
        var execPath = runtime.path
        // Only (re)create when missing or pointing elsewhere. This link is
        // inside Contents/MacOS, which is SEALED as of v1.0.1031: rewriting it
        // on every launch mutates a sealed resource, and the NEXT Gatekeeper
        // assessment then fails with "a sealed resource is missing or
        // invalid" -- an app that launches once and is refused afterwards.
        // BundleGenerator pre-creates it, so the common path is a no-op.
        let have = try? FileManager.default
            .destinationOfSymbolicLink(atPath: execLink.path)
        if have == runtime.path {
            execPath = execLink.path
        } else {
            unlink(execLink.path)
            if symlink(runtime.path, execLink.path) == 0 {
                execPath = execLink.path
                stubLog("exec link repointed -> \(runtime.path) (was \(have ?? "absent")); "
                        + "the bundle seal is now stale until the next install")
            } else {
                stubLog("WARN: could not create \(execLink.path) (errno \(errno)); "
                        + "exec'ing the runtime directly (Dock will show its own name)")
            }
        }
        // Last line of defence before handing control to the runtime. The
        // staged tree is normally cleared once per build by
        // RuntimeInstall.dequarantineStagedOnce, but this stub is the process
        // that actually execs it, and a quarantined target here is refused
        // with "the application is damaged" -- attributed to THIS bundle,
        // which is unsealed by design and can never pass assessment.
        //
        // `runtime.path` and not execPath: execPath is the symlink we just
        // made, and Quarantine uses XATTR_NOFOLLOW, so clearing the link
        // would leave the real binary untouched.
        Quarantine.clearShallow(at: contents.deletingLastPathComponent())
        Quarantine.clear(at: runtime.deletingLastPathComponent())
        stubLog("exec \(execPath) --apk \(apk.path) --android-root \(androidRoot.path)")
        var argv: [UnsafeMutablePointer<CChar>?] = [
            strdup(execPath),
            strdup("--apk"), strdup(apk.path),
            strdup("--android-root"), strdup(androidRoot.path),
            nil,
        ]
        execv(execPath, &argv)
        let err = String(cString: strerror(errno))
        fatalAlert("Could not start \(label)",
                   "exec of \(execPath) failed: \(err) (errno \(errno)). "
                   + "The Mac.apk runtime may be damaged; rebuild or reinstall Mac.apk.",
                   exitCode: 69)
    }
}
