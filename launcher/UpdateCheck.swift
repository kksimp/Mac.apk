import AppKit
import Foundation

// MARK: - Update notification (v1.0.2208)
//
// The owner's spec: bake the release number into the build we upload to GitHub
// Releases, compare it against the latest release there, check once when the
// control panel opens, make the Check for Updates button glow when a newer
// release exists, and on click offer to open the releases page.
//
// WHERE THE NUMBER COMES FROM. `BuildInfo.releaseVersion` is written by
// scripts/build_launcher.sh, and only scripts/release.sh (the one command that
// produces the DMG we publish) sets it, from VERSION. Every other build leaves
// it nil. VERSION is stamped into Info.plist on every build, dev builds too, so
// it cannot tell a published build from a local one; a dev build is usually
// AHEAD of the latest release and would otherwise read as "current" forever, or
// worse, a stale clone would nag about a release it never claimed to be.
//
// WHAT IS COMPARED. Release tags on kksimp/Mac.apk-Releases are "v" + VERSION
// (v1.0.1666, v1.0.1700, v1.0.1702, v1.0.1732 as of 2026-10-13), so both sides
// reduce to dotted integers and compare component by component.
//
// NETWORK. One unauthenticated GET of the GitHub releases/latest endpoint per
// control-panel launch plus one per click. That is far under the 60/hour
// anonymous limit. A failed launch check stays silent (no glow, no alert): an
// offline Mac is not news. Only a click reports the failure.

enum ReleaseVersion {
    /// "v1.0.1732" or "1.0.1732" -> [1, 0, 1732]. Each component keeps its
    /// leading digits, so a suffix like "1.0.1800-beta" still compares.
    /// nil when there is no number at all.
    static func components(_ raw: String) -> [Int]? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") { s.removeFirst() }
        var out: [Int] = []
        for part in s.split(separator: ".", omittingEmptySubsequences: false) {
            let digits = part.prefix(while: { $0.isASCII && $0.isNumber })
            guard let n = Int(digits) else { break }
            out.append(n)
            if digits.count != part.count { break }
        }
        return out.isEmpty ? nil : out
    }

    /// True when `latest` is a strictly higher version than `installed`.
    static func isNewer(_ latest: String, than installed: String) -> Bool {
        guard let a = components(latest), let b = components(installed) else { return false }
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0
            let y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }
}

@MainActor
final class UpdateChecker: ObservableObject {
    enum State: Equatable {
        case idle
        case checking
        /// The latest release is not newer than this build (or this is a dev
        /// build, which never reports an update).
        case current(latestTag: String)
        case available(latestTag: String, page: URL)
        case failed(String)
    }

    /// One checker for the whole app: the menu bar item and the control
    /// panel's button read and drive the same state.
    static let shared = UpdateChecker()

    @Published private(set) var state: State = .idle

    static let releasesPage = URL(string: "https://github.com/kksimp/Mac.apk-Releases/releases")!
    private static let latestAPI =
        URL(string: "https://api.github.com/repos/kksimp/Mac.apk-Releases/releases/latest")!

    /// The release number baked into this build, or nil for a dev build.
    var bakedRelease: String? { BuildInfo.releaseVersion }

    /// What to call this build in a dialog: the baked release, else the
    /// Info.plist version build_launcher.sh stamps on every build.
    var displayVersion: String {
        if let r = bakedRelease { return r }
        return Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
            as? String ?? "unknown"
    }

    var updateAvailable: Bool {
        if case .available = state { return true }
        return false
    }

    var isChecking: Bool { state == .checking }

    /// Mac.apk > Check for Updates... in the menu bar; names the release when
    /// one is waiting, since a menu item cannot glow.
    var menuTitle: String {
        switch state {
        case .available(let tag, _): return "Update Available (\(tag))…"
        case .checking: return "Checking for Updates…"
        default: return "Check for Updates…"
        }
    }

    private var launchCheckDone = false

    /// Once per control-panel session, and only for a release build: a dev
    /// build has nothing to compare, so it spends no request on launch.
    func checkOnLaunch() {
        guard !launchCheckDone, bakedRelease != nil else { return }
        launchCheckDone = true
        check { _ in }
    }

    func check(completion: @escaping (State) -> Void) {
        guard state != .checking else { return }
        state = .checking
        var req = URLRequest(url: Self.latestAPI, cachePolicy: .reloadIgnoringLocalCacheData,
                             timeoutInterval: 15)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        // GitHub rejects API requests with no User-Agent.
        req.setValue("Mac.apk/\(displayVersion)", forHTTPHeaderField: "User-Agent")
        let baked = bakedRelease
        Task {
            let result: State
            do {
                let (data, resp) = try await URLSession.shared.data(for: req)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                if code != 200 {
                    result = .failed(code == 403 || code == 429
                        ? "GitHub is rate-limiting update checks right now. Try again later."
                        : "GitHub answered with HTTP \(code).")
                } else if let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                          let tag = obj["tag_name"] as? String,
                          ReleaseVersion.components(tag) != nil {
                    // Only ever open a github.com page: the URL comes from a
                    // network response and is handed to NSWorkspace.
                    let page = (obj["html_url"] as? String).flatMap(URL.init(string:))
                        .flatMap { $0.scheme == "https" && $0.host?.lowercased() == "github.com" ? $0 : nil }
                        ?? Self.releasesPage
                    if let baked, ReleaseVersion.isNewer(tag, than: baked) {
                        result = .available(latestTag: tag, page: page)
                    } else {
                        result = .current(latestTag: tag)
                    }
                } else {
                    result = .failed("The latest release on GitHub has no version number.")
                }
            } catch {
                result = .failed(error.localizedDescription)
            }
            self.state = result
            completion(result)
        }
    }

    /// The dialog a click ends in. Every outcome offers the releases page,
    /// because "where do I get Mac.apk" is a fair question whatever the answer.
    func presentResult(_ result: State) {
        let alert = NSAlert()
        var pageToOpen = Self.releasesPage
        switch result {
        case .available(let tag, let page):
            pageToOpen = page
            alert.messageText = "A new version of Mac.apk is available"
            alert.informativeText = "Mac.apk \(tag) is out. You have v\(displayVersion)."
            alert.addButton(withTitle: "Go to Releases Page")
            alert.addButton(withTitle: "Not Now")
        case .current(let tag):
            if bakedRelease == nil {
                alert.messageText = "This is a development build"
                alert.informativeText = """
                Development builds (v\(displayVersion)) do not check for updates. \
                The latest public release is \(tag).
                """
            } else {
                alert.messageText = "Mac.apk is up to date"
                alert.informativeText = "You have v\(displayVersion), the latest release."
            }
            alert.addButton(withTitle: "OK")
            alert.addButton(withTitle: "Open Releases Page")
        case .failed(let why):
            alert.messageText = "Could not check for updates"
            alert.informativeText = why
            alert.alertStyle = .warning
            alert.addButton(withTitle: "OK")
            alert.addButton(withTitle: "Open Releases Page")
        case .idle, .checking:
            return
        }
        let isAvailable: Bool
        if case .available = result { isAvailable = true } else { isAvailable = false }
        let finish: (NSApplication.ModalResponse) -> Void = { resp in
            let opensPage = isAvailable
                ? resp == .alertFirstButtonReturn
                : resp == .alertSecondButtonReturn
            if opensPage { NSWorkspace.shared.open(pageToOpen) }
        }
        // A sheet on the control panel, so the dialog sits centred on the
        // window instead of wherever runModal puts a free-standing alert
        // (owner, 2026-10-13: it showed up off to the left). With no window
        // open (menu bar item, panel closed) it falls back to a plain alert.
        if let window = NSApp.keyWindow ?? NSApp.mainWindow
            ?? NSApp.windows.first(where: { $0.isVisible && $0.canBecomeMain }) {
            alert.beginSheetModal(for: window, completionHandler: finish)
        } else {
            finish(alert.runModal())
        }
    }
}
