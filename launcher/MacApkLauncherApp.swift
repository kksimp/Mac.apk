// MacApkLauncherApp.swift
//
// Mac.apk launcher - a real Mac app you open by double-clicking.
//
// What the user does:
//   1. Build it once with scripts/build_launcher.sh (or open
//      build/MacApk.app from a release tarball).
//   2. Double-click MacApk.app from Finder or the Dock.
//   3. Drag an APK onto the window, or click Browse to pick one.
//   4. Toggle Verbose if they want a live log panel inside the app.
//   5. Click Launch.
//
// The launcher spawns the runtime as a child process, captures its stderr,
// and routes events back to the user as toasts and an inline log panel.
// No command line necessary at any point.
//
// Why a single Swift file: keeps contributors able to read the whole UI
// in one place. SwiftUI is dense enough that splitting this across a
// dozen files would hide the structure.

import SwiftUI
import AppKit
import CoreServices
import UniformTypeIdentifiers
import Combine

// MARK: - Entry point
//
// The @main attribute requires the file to be compiled as a library, not a
// script. The build script passes -parse-as-library to swiftc; without that
// flag the compiler emits "'main' attribute cannot be used in a module that
// contains top-level code". This file has no top-level code, only type
// declarations, so -parse-as-library makes everything resolve correctly.

/// Takes the Help menu off the menu bar (owner, 2026-10-13). SwiftUI has no
/// way to drop the menu itself: `CommandGroup(replacing: .help)` only empties
/// it, and AppKit still shows an empty Help menu with its search field. SwiftUI
/// also rebuilds the main menu when commands change (the Check for Updates
/// title does), so this removes it again whenever an item is added back.
@MainActor
enum HelpMenuRemover {
    private static var observer: NSObjectProtocol?

    static func install() {
        strip()
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: NSMenu.didAddItemNotification, object: nil, queue: .main
        ) { note in
            guard (note.object as? NSMenu) === NSApp.mainMenu else { return }
            MainActor.assumeIsolated { strip() }
        }
    }

    private static func strip() {
        guard let bar = NSApp.mainMenu else { return }
        for item in bar.items.reversed()
        where (NSApp.helpMenu != nil && item.submenu === NSApp.helpMenu)
            || item.submenu?.identifier?.rawValue == "help"
            || item.title == "Help" {
            bar.removeItem(item)
        }
    }
}

@main
struct MacApkLauncherApp: App {
    // The shared LaunchModel holds APK path, verbose flag, run state, and
    // log feed. All views observe it.
    @StateObject private var model = LaunchModel()
    /// Shared with LauncherView's inline button, so the menu item and the
    /// button always agree about whether an update is waiting.
    @StateObject private var updates = UpdateChecker.shared

    init() {
        // Harness-only settings never belong in the owner's control panel
        // (owner, 2026-10-19: "the mac.apk app still launches apps in the
        // background"). scripts/rebuild_and_run.sh ends with `open` on this
        // app, and a thread's install shell that exported MACAPK_NO_ACTIVATE=1
        // handed it to us: the live launcher (started 23:43:49 by the 23:43:45
        // v1.0.2275 install) carried MACAPK_NO_ACTIVATE=1 and
        // MACAPK_INSTALL_LOCK_HELD, and every app opened from the drawer
        // inherited the first, so each one ordered its window behind. Settings
        // typed into this window (defaultEnv, an app's Get Info env) are passed
        // explicitly per launch and are unaffected.
        for name in ["MACAPK_NO_ACTIVATE", "MACAPK_PAUSE_WHEN_HIDDEN",
                     "MACAPK_INSTALL_LOCK_HELD", "MACAPK_INJECT_FILE",
                     "MACAPK_DEBUG_HOSTKEY_FILE", "MACAPK_RECORD_INPUT",
                     "MACAPK_EXTERNAL_URLS",
                     // Bundle-folder seams for debug/bundlesdir_gate.sh only:
                     // the control panel always installs into the real folders.
                     "MACAPK_SYSTEM_APPS_DIR", "MACAPK_USER_APPS_DIR"] {
            unsetenv(name)
        }
        // After the first runloop turn, when SwiftUI has built the main menu.
        DispatchQueue.main.async { HelpMenuRemover.install() }
    }

    var body: some Scene {
        WindowGroup("Mac.apk") {
            LauncherView()
                .environmentObject(model)
                // Owner, 2026-10-13: about 10% larger than the old 600 x 480.
                .frame(minWidth: 660, minHeight: 528)
                // Owner, 2026-10-07: "if you double click an apk while
                // mac.apk is open itll open a second mac.apk window and
                // install". A WindowGroup with no external-event preference
                // answers EVERY open event (Finder double-click, Open With,
                // Dock drop) by creating a NEW window and delivering the URL
                // to it -- same process, so install.log showed one pid and a
                // second window. Preferring "*" here routes the event to the
                // window that already exists. SwiftUI still creates one when
                // none exists (Mac.apk running with its window closed), and
                // onOpenURL below hides that one.
                .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
                .onAppear { model.windowAppearedAt = Date() }
                // onOpenURL fires when the user opens an .apk via Finder's
                // "Open With > Mac.apk" or by dropping it on the Dock icon.
                // The URL is a file://, so we extract the path and stash it.
                .onOpenURL { url in
                    if url.isFileURL && kSupportedApkExtensions.contains(
                        url.pathExtension.lowercased()) {
                        // v1.0.464: Open-With / Dock-drop INSTALLS the APK
                        // into the library (it no longer runs directly).
                        // Stash the path; LauncherView observes
                        // pendingInstallPaths and routes it through the
                        // install funnel, which confirms first.
                        //
                        // APPEND, never assign: selecting several APKs in
                        // Finder and hitting Open delivers one open event
                        // PER FILE in quick succession. Against the single
                        // String this used to be, each event overwrote the
                        // last and every file but one was silently dropped.
                        // Owner, 2026-08-22: "double clicking should be a
                        // silent install that opens the app bundle after."
                        // If this open event is why we launched, get the
                        // window off screen before it is ever drawn.
                        let sinceLaunch = Date().timeIntervalSince(model.launchedAt)
                        InstallLog.write(String(format:
                            "[gui] open event %.2fs after launch", sinceLaunch))
                        if sinceLaunch < 5 {
                            model.launchedForOpen = true
                            NSApp.hide(nil)
                        } else if Date().timeIntervalSince(model.windowAppearedAt) < 2 {
                            // Mac.apk was already running with no window, so
                            // SwiftUI made one just to receive this event.
                            // Keep the install silent: hide it rather than
                            // quit, since the user had Mac.apk running.
                            InstallLog.write("[gui] open event created a window; hiding it")
                            NSApp.hide(nil)
                        }
                        model.pendingInstallPaths.append(url.path)
                    }
                }
        }
        .windowResizability(.contentSize)
        // Kill the standard New / Open menu items because they make no sense
        // for a launcher and just confuse users when they trigger nothing.
        .commands {
            CommandGroup(replacing: .newItem) { }
            // Mac.apk > Check for Updates..., the standard macOS spot (right
            // under About). Same checker as the glowing button in the panel.
            CommandGroup(after: .appInfo) {
                Button(updates.menuTitle) {
                    updates.check { updates.presentResult($0) }
                }
                .disabled(updates.isChecking)
            }
            // Owner, 2026-10-13: no Help menu. Mac.apk ships no help book, so
            // its only item opened a "Help isn't available" dialog. This empties
            // the menu; HelpMenuRemover takes the empty menu off the bar.
            CommandGroup(replacing: .help) { }
        }
    }
}

// MARK: - Model

// MARK: - Screen size presets
//
// Common Android logical resolutions (dp, not physical pixels). Stored
// in their natural portrait form; the landscape orientation choice
// swaps width/height at launch time so the long side ends up horizontal.
//
// "Auto" is a special preset (width = 0): the launcher omits
// --width/--height and the RUNTIME sizes the window to the biggest
// canvas the display supports, shaped to the RESOLVED orientation
// (manifest-aware when Orientation is Auto) -- see
// cocoa_bridge.mm::CocoaSetPhoneSizeMax.
struct PhonePreset: Identifiable, Hashable {
    let id: String
    let label: String
    let width: Int
    let height: Int
    /// True when this preset means "let the runtime decide" -- no
    /// --width / --height args are emitted on the command line.
    var isAuto: Bool { width == 0 || height == 0 }
    var argW: String { "\(width)" }
    var argH: String { "\(height)" }
}

let kPhonePresets: [PhonePreset] = [
    // "Auto" sentinel: the launcher passes the biggest canvas the
    // Mac's display supports (main screen's visibleFrame, shaped to
    // the chosen orientation) -- see runtimeArgs().
    PhonePreset(id: "auto",      label: "Auto  (biggest size this display supports)", width: 0,    height: 0),
    PhonePreset(id: "480x800",   label: "480 × 800  (6\" HDPI — phone default)", width: 480,  height: 800),
    PhonePreset(id: "360x640",   label: "360 × 640  (5\" MDPI)",                  width: 360,  height: 640),
    PhonePreset(id: "360x780",   label: "360 × 780  (6.5\" modern)",              width: 360,  height: 780),
    PhonePreset(id: "393x851",   label: "393 × 851  (Pixel 7 logical)",           width: 393,  height: 851),
    PhonePreset(id: "412x915",   label: "412 × 915  (Pixel 6 logical)",           width: 412,  height: 915),
    PhonePreset(id: "320x480",   label: "320 × 480  (small legacy)",              width: 320,  height: 480),
    PhonePreset(id: "540x960",   label: "540 × 960  (xHDPI)",                     width: 540,  height: 960),
    PhonePreset(id: "720x1280",  label: "720 × 1280 (720p HD)",                   width: 720,  height: 1280),
    PhonePreset(id: "960x1600",  label: "960 × 1600 (5:3 / 1600×960 landscape)",  width: 960,  height: 1600),
    PhonePreset(id: "1080x1920", label: "1080 × 1920 (1080p Full HD)",            width: 1080, height: 1920),
    PhonePreset(id: "1200x1920", label: "1200 × 1920 (5:8 tablet)",               width: 1200, height: 1920),
    PhonePreset(id: "1440x2560", label: "1440 × 2560 (2K QHD)",                   width: 1440, height: 2560),
]

// MARK: - Orientation choice
//
// The runtime accepts --orientation auto|portrait|landscape. "auto"
// reads android:screenOrientation from the launchable activity in the
// APK manifest and picks accordingly (defaults to portrait when the
// manifest doesn't declare one). The launcher mirrors those three
// values verbatim so what the user picks here is exactly what the
// runtime sees on the command line.
enum LauncherOrientation: String, CaseIterable, Identifiable {
    case auto, landscape, portrait
    var id: String { rawValue }

    var label: String {
        switch self {
        case .auto:      return "Auto (from manifest)"
        case .landscape: return "Landscape"
        case .portrait:  return "Portrait"
        }
    }

    /// Tooltip shown next to the picker.
    var help: String {
        switch self {
        case .auto:
            return "Read android:screenOrientation from the APK manifest. Falls back to portrait if the manifest doesn't say."
        case .landscape:
            return "Force landscape (window swaps width/height so the long side is horizontal)."
        case .portrait:
            return "Force portrait (window keeps the preset's natural orientation)."
        }
    }

    /// SF Symbol that hints at the orientation visually.
    var symbol: String {
        switch self {
        case .auto:      return "arrow.triangle.2.circlepath"
        case .landscape: return "rectangle"
        case .portrait:  return "rectangle.portrait"
        }
    }
}

// MARK: - Runtime binary
//
// One runtime: OpenJDK 21 + com.macapk.dex (our own DEX->JAR) + Java framework reimpl +
// ARM32→ARM64 translator for native libs. The launcher used to expose a picker between
// this and an earlier in-house DEX interpreter; the interpreter is gone.

/// File extensions the picker / drop zone / Open-With handler treat
/// as installable Android apps. `.apk` is the canonical single-file
/// form; the rest are bundle formats (each is a zip whose top-level
/// entries include one or more `*.apk` files plus split / obb data):
///   - `.apkm`: APKMirror bundle (base.apk + config.*.apk splits)
///   - `.xapk`: APKPure bundle (base.apk + split apks + obb + icon)
///   - `.apks`: bundletool output (universal.apk inside, plus splits)
///   - `.apkx`: APKPure's older single-apk variant; same shape as .apk
/// For bundles we extract the inner base apk to a sibling temp dir at
/// launch time and pass the extracted .apk path on to the runtime,
/// which only knows how to handle plain .apk files.
let kSupportedApkExtensions: Set<String> = ["apk", "apkm", "xapk", "apks", "apkx"]

/// Substring the runtime prints (bare `fprintf`, runtime/src/main.cpp)
/// when every ABI in the APK is one we cannot run -- an x86-only build is
/// the realistic case. The app still boots, but its native code is
/// replaced by a stub dylib, so it will misbehave or crash later for
/// reasons that look nothing like the real cause. Matched on TEXT because
/// the launcher owns none of the runtime sources; if that message is ever
/// reworded, update this constant with it.
let kUnsupportedAbiMarker = "APK ships NO usable ABI"

/// Filename of the runtime binary inside Contents/Resources when the
/// launcher is bundled, OR at runtime/build/bin/ in the dev tree.
private let kRuntimeBinaryName = "macapk-runtime"

/// Dev-tree path relative to the project root.
private let kRuntimeDevTreePath = "runtime/build/bin/macapk-runtime"

// MARK: - Launcher settings persistence
//
// Launcher-level preferences that must survive a relaunch. Deliberately a
// tiny key/value facade rather than @AppStorage scattered through the views:
// these settings belong in the install registry's `settings` section, and a
// facade means that move is a change to THIS type's two functions instead of
// a hunt through the UI. The accessor names (`setting` / `setSetting`)
// mirror the registry store's, for the same reason.
//
// Backing store is UserDefaults (the app's own suite), which is the ordinary
// macOS home for this and needs no file format, no migration, and no error
// path of its own.
enum LauncherSettings {
    /// Keys are namespaced so they cannot collide with any framework
    /// default that happens to share a short name.
    private static func defaultsKey(_ key: String) -> String {
        "org.macapk.launcher.\(key)"
    }

    static let kAndroidRoot = "androidRoot"
    static let kDefaultEnv  = "defaultEnv"

    static func setting(_ key: String) -> String? {
        UserDefaults.standard.string(forKey: defaultsKey(key))
    }

    static func setSetting(_ key: String, _ value: String?) {
        let d = UserDefaults.standard
        if let v = value, !v.isEmpty {
            d.set(v, forKey: defaultsKey(key))
        } else {
            d.removeObject(forKey: defaultsKey(key))
        }
    }

    /// Global environment applied to EVERY launch, under any per-app
    /// override. Stored as JSON text so the store stays string-valued and
    /// the eventual move into the registry is a copy, not a re-encode.
    static func defaultEnv() -> [String: String] {
        guard let raw = setting(kDefaultEnv),
              let data = raw.data(using: .utf8),
              let dict = try? JSONDecoder().decode([String: String].self, from: data)
        else { return [:] }
        return dict
    }

    static func setDefaultEnv(_ env: [String: String]) {
        guard !env.isEmpty else { setSetting(kDefaultEnv, nil); return }
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        guard let data = try? enc.encode(env),
              let text = String(data: data, encoding: .utf8) else { return }
        setSetting(kDefaultEnv, text)
    }

    /// An environment variable name Android/POSIX would accept, and the
    /// shape every MACAPK_* knob uses. Applied as a warn-not-block check in
    /// the env editors so a typo is visible before it silently does nothing.
    static func isValidEnvKey(_ key: String) -> Bool {
        guard let first = key.first, first.isUppercase || first == "_" else { return false }
        return key.allSatisfy { $0.isUppercase || $0.isNumber || $0 == "_" }
    }
}

/// Holds the launcher's mutable state. SwiftUI re-renders any view that
/// reads a published property when that property changes.
final class LaunchModel: ObservableObject {
    @Published var apkPath: String = ""
    @Published var verbose: Bool = false
    @Published var isRunning: Bool = false
    @Published var status: String = ""
    @Published var statusKind: StatusKind = .info
    /// Which boot phase the runtime is currently in, derived from the
    /// stderr markers it already prints (see `LaunchPhase.forLogLine`).
    /// A cold install can take ~30 s (Candy Crush, measured) during which
    /// the status bar used to show nothing but a spinner and "Running...",
    /// leaving the user unable to tell progress from a hang. The bar now
    /// renders this phase's LABEL.
    ///
    /// Deliberately a step label and NOT a percentage: APK sizes vary by
    /// more than 10x and the steps are nowhere near equal-cost, so any
    /// percentage we could compute would be a lie.
    @Published var phase: LaunchPhase = .idle
    /// Non-nil when the runtime reported that the APK ships no ABI we can
    /// run. The payload is the ABI list the APK actually ships (possibly
    /// empty if the message format drifts). Rendered as a dedicated
    /// banner because the runtime emits this as a bare fprintf with no
    /// severity prefix: `isPinnedSeverity` never pins it,
    /// `colorForLogLine` never colors it, and the log panel is hidden
    /// unless Verbose is on, so today the warning is effectively invisible
    /// even though it means the app's native code is GONE.
    @Published var unsupportedAbiWarning: String? = nil
    /// v1.0.89: pendingLaunch is a counter the InstalledAppsView bumps
    /// when the user double-clicks a tile. LauncherView observes via
    /// `.onChange` and triggers launch() immediately, so double-click
    /// goes straight from icon → running game with no separate Launch-
    /// button press. Single-click keeps its current "just load the APK"
    /// behaviour so the user can review screen size + orientation
    /// before committing.
    @Published var pendingLaunch: Int = 0
    /// v1.0.339: when the user launches an app from the drawer we have
    /// its display name + icon (from the parsed manifest / extracted
    /// resources). We hand both to the runtime so macOS shows the app's
    /// own icon in the Dock and the app's display name in the window
    /// title bar, rather than "Mac.apk" / the package name. Empty for
    /// drag-drop first-launches (no InstalledApp record yet) -- the
    /// runtime falls back to its default Dock + package-name title.
    @Published var appLabel: String = ""
    /// The android root for the selected app: its bundle's if it has one,
    /// else nil so callers fall back to the shared root. Verified to still
    /// exist, because a user can drag a bundle to the Trash behind our back
    /// and launching into a vanished path would re-extract silently.
    var selectedAppAndroidRoot: URL? {
        let p = appBundlePath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !p.isEmpty else { return nil }
        let root = URL(fileURLWithPath: p).appendingPathComponent("Contents/android")
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: root.path, isDirectory: &isDir),
              isDir.boolValue else { return nil }
        return root
    }

    /// Dev toggle: put the version back into generated bundle NAMES.
    /// Persisted in the registry settings so the helper and the stub see the
    /// same answer the GUI does -- a setting only the GUI knew would make a
    /// helper-driven install name its bundle differently from a drag-drop.
    @Published var bundleNameShowsVersion: Bool =
        RegistryStore.shared.setting(BundleGenerator.showVersionInNameSettingKey) == "1" {
        didSet {
            try? RegistryStore.shared.setSetting(
                BundleGenerator.showVersionInNameSettingKey,
                bundleNameShowsVersion ? "1" : nil)
        }
    }

    /// The selected app's own .app bundle, when it has one.
    ///
    /// ⚠ LOAD-BEARING. A bundle-installed app keeps ALL its data inside its
    /// bundle. Launching it with the shared android root instead makes the
    /// runtime re-extract from scratch and hand the app an EMPTY data dir --
    /// so the same app had two different homes depending on whether you
    /// double-clicked its icon or clicked it in this grid. Audited 2026-08-11.
    @Published var appBundlePath: String = ""
    @Published var appIconPath: String = ""
    /// v1.0.464 (install-then-launch UX): id (dirName) of the installed
    /// app the user has selected in the grid. Drives the tile highlight
    /// and is the app the Launch button runs. Empty = nothing selected.
    @Published var selectedAppId: String = ""
    /// When this process started. Used to tell "the user launched Mac.apk"
    /// from "Finder launched Mac.apk in order to hand it an APK": an open
    /// event that arrives within seconds of launch is the latter.
    let launchedAt = Date()
    /// When a launcher window last appeared. An open event that lands right
    /// after it means SwiftUI created that window to deliver the event.
    var windowAppearedAt = Date.distantPast

    /// True when this launch exists only to service a Finder open. Such a
    /// launch never shows the control panel: it installs, opens the app it
    /// produced, and quits. See finderInstallDidFinish.
    var launchedForOpen = false

    /// v1.0.464: paths stashed by the App-level onOpenURL handler (Finder
    /// double-click / "Open With > Mac.apk" / Dock drop) for the
    /// LauncherView to pick up and INSTALL (not run). LauncherView observes
    /// this via .onChange, drains it, and routes it through the install
    /// funnel, which confirms with the user first.
    ///
    /// An ARRAY, not a single path: Finder delivers a multi-file open as
    /// one event per file, so the previous single-String field kept only
    /// the last of them. The observer coalesces a burst into one request
    /// so N selected APKs produce ONE confirmation, not N.
    @Published var pendingInstallPaths: [String] = []
    @Published var selectedPreset: PhonePreset = kPhonePresets[0]
    /// Orientation the runtime should boot the window in. Defaults to
    /// .auto so the runtime reads the APK manifest and picks. Override
    /// to .landscape for landscape-only games whose manifest doesn't
    /// declare screenOrientation (Hill Climb Racing is the canonical
    /// example -- cocos2d-x sets the orientation at runtime, not in
    /// the manifest).
    @Published var selectedOrientation: LauncherOrientation = .auto
    /// User-visible path where Mac.apk mirrors Android's filesystem
    /// for the running app (`<root>/data/data/<pkg>/foo.png` etc.).
    /// We always pin the trailing component to `android` so picking
    /// "~/Documents" lands files at "~/Documents/android/data/..."
    /// rather than dumping them straight into the parent. Default
    /// matches the runtime's default in [main.cpp] -- both reach the
    /// same dir, but exposing it here lets the user see + change it.
    ///
    /// PERSISTED (it was not, before). The Settings sheet let the user pick
    /// a folder and then dropped the choice on quit, because this was a bare
    /// @Published with no backing store anywhere in the file. The next
    /// launch silently reverted to ~/Documents/android and the user's apps
    /// looked like they had vanished -- they were installed under the OTHER
    /// root. `init()` reads the stored value back; `didSet` writes changes.
    /// Property observers do not fire during initialization, so the read in
    /// init() does not turn around and re-write what it just read.
    /// ⚠ Moved out of ~/Documents/android 2026-08-12, matching the runtime's
    /// own default (ResolveAndroidRootDir in apk_unpacker.cpp, which carries
    /// the reasoning). The two must agree: this value is what the launcher
    /// passes as --android-root, so a launcher still defaulting to the old
    /// path would recreate the directory the change exists to stop creating.
    @Published var androidRoot: String = {
        let home = NSHomeDirectory()
        return "\(home)/Library/Caches/MacApk/scratch"
    }() {
        didSet {
            LauncherSettings.setSetting(LauncherSettings.kAndroidRoot, androidRoot)
        }
    }
    /// Environment applied to EVERY launch, beneath any per-app override.
    /// Edited in Settings > Advanced; persisted via LauncherSettings.
    @Published var defaultEnv: [String: String] = [:] {
        didSet { LauncherSettings.setDefaultEnv(defaultEnv) }
    }
    /// Whether the launcher writes the runtime's stderr stream to
    /// `logs/macapk_launcher.log` on each Run. Default ON so a user
    /// who hits a crash can grab the log without having reconfigured
    /// anything. Toggle independent of `verbose` -- verbose just
    /// controls whether we ALSO show the live log panel in the UI,
    /// and whether the runtime itself emits its chatty `--verbose`
    /// trace events.
    @Published var fileLogEnabled: Bool = true
    /// Hidden dev menu. Revealed by tapping the "Mac.apk" title in
    /// the header five times. Stays revealed for the rest of the
    /// session (clears on relaunch). When true, the controls row
    /// shows the Force ARM32 + Disable Ads toggles for testing.
    @Published var devMenuRevealed: Bool = false
    /// When true, the runtime launches with MACAPK_FORCE_ABI=
    /// armeabi-v7a in the environment so it picks the ARM32 lib
    /// even when an arm64-v8a copy is present in the APK. Useful
    /// for exercising the ARM32 lifter on apps that ship both ABIs.
    @Published var forceArm32: Bool = false

    /// When true, the runtime launches with MACAPK_DEV_TRACE=1
    /// which enables ALL diagnostic instrumentation: per-BL trace
    /// (every direct bl/blx/tail-B logged with r0..r3), JNI handle
    /// make/resolve/release trace, verbose indirect-call dispatcher
    /// logging. Apps run MUCH slower with this on (often ~1 fps for
    /// HCR-class games). Per user 2026-05-20: diagnostic slowdowns
    /// are acceptable. Hidden behind the dev menu alongside Force
    /// ARM32 (5-tap title to reveal).
    @Published var verboseDevTrace: Bool = false
    /// Verbose runtime logging. OFF by default: the framework logs every
    /// classload at INFO (~16K lines per boot), so a normal UI run silences
    /// THAT CALL SITE (MACAPK_QUIET_CLASSLOAD=1) and keeps AOSP's own INFO
    /// severity floor (MACAPK_LOG_LEVEL=4) for everything else, which is what
    /// a real device's logcat shows by default. Flip on to drop the floor to
    /// 0 and restore the classload trace for a bug report. Direct CLI runs set
    /// nothing, so they stay fully verbose for the debug harnesses.
    /// See launch()'s env block for the measurement behind the two values --
    /// in particular why this is NOT a severity floor of WARN any more.
    @Published var verboseLogging: Bool = false
    /// Log feed shown in the panel. Two-tier retention:
    ///   - Error / warning lines (severity 'E' or 'W' in the runtime
    ///     log format `HH:MM:SS.ms <SEV> [tag] message`) are pinned and
    ///     never evicted while the runtime is alive. Bug reports need
    ///     the FIRST error in a chain, not the most recent one.
    ///   - Everything else (info / debug / trace) gets trimmed to a
    ///     rolling tail so a chatty 60fps render loop doesn't push the
    ///     errors out of view or balloon the launcher's memory.
    /// The two streams render together in `LogPanel` with errors and
    /// warnings visually distinct so they read as a permanent record.
    @Published var logLines: [String] = []
    /// HARD cap on total lines kept in the in-panel log. SwiftUI's
    /// LazyVStack with .textSelection(.enabled) on every Text gets
    /// noticeably slow past ~500 rows, and a stuck-in-a-SEGV-loop
    /// runtime can produce thousands of error lines per second --
    /// big enough to freeze the UI thread on resize / scroll. We cap
    /// the panel display tightly. Nothing is lost: every line still
    /// goes to logs/macapk_launcher.log unfiltered for grep / tail.
    private let kTotalLogCap = 400
    /// Of the kTotalLogCap budget, how many slots reserve themselves
    /// for pinned (error / warning) lines. We always keep at most
    /// this many pinned lines, dropping the oldest pinned only after
    /// the cap is full. The remaining (kTotalLogCap - kPinnedReserved)
    /// slots are a rolling tail of non-pinned lines.
    private let kPinnedReserved = 200

    /// Live log mirror file. Every appended line also writes here so a
    /// developer can `tail -f` or `grep` the full session even after
    /// the in-panel rolling tail has dropped older entries. Path is
    /// stable across sessions (overwritten at each Run click) so the
    /// user always knows where to look. The file lives inside the
    /// repo's `logs/` directory; resetLiveLog archives the previous
    /// run to `logs/archive/launcher_<unix-ts>.log` and prunes the
    /// archive to the last `kLogArchiveKeep` files.
    private lazy var liveLogPath: String = {
        logsDirectory().appendingPathComponent("macapk_launcher.log").path
    }()
    /// How many archived launcher logs to retain before pruning.
    private let kLogArchiveKeep = 10
    /// Open file handle for the mirror. Lazy: only created on first
    /// appendLog after a Run click (resetLiveLog clears the previous
    /// session and re-opens). Closed when the runtime exits.
    private var liveLogHandle: FileHandle?
    private let liveLogLock = NSLock()
    /// Bytes in the current mirror segment, for the per-run size cap below.
    private var liveLogBytes = 0
    /// The per-run log size cap, the SAME contract the runtime enforces on a
    /// run log that is a regular file (bionic_shim.cpp __macapk_logcap_start,
    /// MACAPK_LOG_CAP_MB, default 64 MB, 0 = unlimited). A Run-button launch
    /// hands the runtime a PIPE, so the runtime cannot cap it -- this mirror is
    /// the file, and it rotates the same way: at the cap the live file becomes
    /// macapk_launcher.prev.log (replacing any older segment) and a fresh file
    /// opens with one marker line, so the newest output is always in
    /// macapk_launcher.log and a run costs at most about 2x the cap.
    private lazy var liveLogCapBytes: Int = {
        let def = 64 << 20
        guard let raw = ProcessInfo.processInfo.environment["MACAPK_LOG_CAP_MB"],
              !raw.isEmpty else { return def }
        guard let mb = Int(raw), mb >= 0 else { return def }
        return min(mb, 1 << 20) << 20
    }()
    private var prevLogPath: String {
        logsDirectory().appendingPathComponent("macapk_launcher.prev.log").path
    }

    /// v1.0.130: one-shot migration -- wipe pre-v1.0.130 cache dirs
    /// that lived under `~/Library/Caches/macapk/` so the user only
    /// has to manage ONE directory (`~/Documents/android/`). Called
    /// from LaunchModel.init() so it runs once per launcher session.
    /// Failure is non-fatal -- if Library/Caches has stale state we
    /// can't remove (permissions, ENOENT, etc.) the launcher still
    /// works, the dir just stays sitting there until the user removes
    /// it manually.
    ///
    /// Three legacy locations cleaned:
    ///  - `bundles/<bundle>/` -- v1.0.130 moved this to
    ///    `~/Documents/android/_bundles/`.
    ///  - `apps/<pkg>/` -- v1.0.79's pre-merge cache, no current code
    ///    writes here; everything is under `~/Documents/android/data/data/<pkg>_<ver>/`.
    ///  - `translated_libs/<pkg>/` -- legacy translator-cache location;
    ///    current translator writes to `<app_dir>/lib_native/`.
    init() {
        migrateLegacyCaches()
        // Stamp the diagnostic "which build wrote this" registry field on
        // every write this process makes. The field existed from day one and
        // NO caller ever populated it (audit S10), so it was always absent.
        // Writers that lack BuildInfo (the per-app stub) leave it alone, and
        // writeLocked() then preserves the previous writer's stamp.
        RegistryStore.shared.writerVersion = BuildInfo.id
        // Restore persisted settings. These assignments happen inside init,
        // where Swift sets stored properties directly without running the
        // didSet observers, so restoring does not re-save.
        if let saved = LauncherSettings.setting(LauncherSettings.kAndroidRoot),
           !saved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            androidRoot = saved
        }
        defaultEnv = LauncherSettings.defaultEnv()
    }
    private func migrateLegacyCaches() {
        let fm = FileManager.default
        // Genuine pre-rebrand cache dir names to clean up. The current
        // cache lives under ~/Library/Caches/macapk/, so it must NOT
        // appear here or it would be deleted on every launch.
        for legacyName in ["MacDroid", "macdroid"] {
            let legacyRoot = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent(legacyName)
            // Only act if the dir exists and is non-empty -- avoid
            // creating spurious log entries when there's nothing to do.
            guard fm.fileExists(atPath: legacyRoot.path) else { continue }
            do {
                try fm.removeItem(at: legacyRoot)
                NSLog("[macapk-launcher] cleaned up legacy cache at %@",
                      legacyRoot.path)
            } catch {
                NSLog("[macapk-launcher] could not remove legacy cache %@: %@",
                      legacyRoot.path, "\(error)")
            }
        }
    }

    /// Resolve the directory the repo keeps logs in. Walks up from the
    /// launcher bundle the same way runtimePath() does: <project>/build/
    /// MacApk.app, so two parents up gets to the project root that
    /// contains `logs/`. If we can't find a project root (e.g. the .app
    /// was copied to /Applications), fall back to
    /// ~/Library/Logs/macapk/ so logs never end up in /tmp again.
    func logsDirectory() -> URL {
        // Resolution lives in MacApkLogDir (InstallCore.swift) so the install
        // helper, which has no LaunchModel, writes where this button looks.
        let baseDir = MacApkLogDir.url()
        try? FileManager.default.createDirectory(
            at: baseDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(
            at: baseDir.appendingPathComponent("archive"),
            withIntermediateDirectories: true)
        return baseDir
    }

    /// v1.0.64: resolve the directory the runtime should use for
    /// cached classes.jar / apktool / translated-dylib output (one
    /// subdirectory per package; see `runtime/src/apk_unpacker.cpp`).
    ///
    /// Layout: instead of nesting the cache inside
    /// `runtime/build/var/apps/` (which `rebuild_and_run.sh` wipes
    /// wholesale every iteration), we want the cache to live at
    /// `<repo>/cache/apps/` so it survives rebuilds. The "Clear
    /// ARM32 Cache" button in the controls section deletes its
    /// contents on demand. The runtime is also able to use the
    /// fallback `~/Library/Caches/macapk/apps/` when running from
    /// a bundled `.app` that has no sibling repo (see the matching
    /// MACAPK_APP_CACHE_DIR default-resolution logic in
    /// `runtime/src/main.cpp`); the launcher mirrors that policy
    /// here so both halves agree on the location.
    ///
    /// Resolution order:
    ///   1. Dev-tree mode -- runtime binary lives under
    ///      `<repo>/runtime/build/bin/macapk-runtime`. Walk three
    ///      `..` segments from the binary to reach the repo root.
    ///   2. Bundled `.app` mode -- runtime binary is in
    ///      `Contents/Resources/`; the bundle has no sibling repo.
    ///      Fall back to `~/Library/Caches/macapk/apps/`.
    ///   3. If we couldn't find the runtime at all, return the
    ///      Library/Caches fallback so the env var is still valid.
    /// v1.0.130: returns the user-visible `~/Documents/android/` root
    /// (or whatever the user set). All Mac.apk caches & per-app data
    /// land under this dir so the user can manage / delete everything
    /// from one Finder location. Mirrors SetupAndroidRootEx's contract:
    /// expand `~`, force the trailing `/android` segment if missing,
    /// fall back to `~/Documents/android` when blank.
    func androidRootURL() -> URL {
        let fm = FileManager.default
        var raw = androidRoot.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty {
            raw = fm.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Caches/MacApk/scratch").path
        }
        if raw.hasPrefix("~") {
            raw = (raw as NSString).expandingTildeInPath
        }
        var url = URL(fileURLWithPath: raw)
        if url.lastPathComponent != "android" {
            url = url.appendingPathComponent("android")
        }
        return url
    }

    func arm32AppCacheDir() -> URL {
        // v1.0.79: cache merged with asset extraction under
        // `<android_root>/data/data/`. Single dir per APK.
        // v1.0.130: rebased on androidRootURL so the path-resolution
        // logic is shared with the launcher's other cache dirs (bundle
        // extraction, etc.) -- one source of truth.
        return androidRootURL().appendingPathComponent("data/data",
                                                        isDirectory: true)
    }

    /// Walk up from the launcher bundle looking for a directory that
    /// already contains a `logs/` subdir (i.e. the repo root). Returns
    /// nil if no such ancestor exists within six levels.

    /// Reset and re-open the live log file. Called when the user
    /// clicks Run so each session starts a fresh mirror. Before
    /// truncating, the previous run's log (if non-empty) is moved
    /// to `logs/archive/launcher_<unix-ts>.log` and the archive is
    /// pruned to the last kLogArchiveKeep entries.
    func resetLiveLog() {
        liveLogLock.lock()
        defer { liveLogLock.unlock() }
        try? liveLogHandle?.close()
        liveLogHandle = nil
        // Always archive + prune the previous run regardless of the
        // current setting -- you might be turning logging back ON to
        // capture a repro, and you don't want the previous run's file
        // sitting next to a fresh empty one looking like the current
        // session. If the user has file-log disabled we leave the
        // live-log file untouched after the archive step.
        archivePreviousLog()
        pruneArchive(keep: kLogArchiveKeep)
        liveLogBytes = 0
        guard fileLogEnabled else { return }
        FileManager.default.createFile(atPath: liveLogPath, contents: Data())
        liveLogHandle = FileHandle(forWritingAtPath: liveLogPath)
    }

    /// The size cap fired (caller holds liveLogLock): keep this segment as
    /// macapk_launcher.prev.log and continue in a fresh live file that opens
    /// with one marker line naming where the earlier output went.
    private func rotateLiveLogLocked() {
        let fm = FileManager.default
        try? liveLogHandle?.close()
        liveLogHandle = nil
        try? fm.removeItem(atPath: prevLogPath)
        try? fm.moveItem(atPath: liveLogPath, toPath: prevLogPath)
        let marker = "[launcher] logcap: this run's log reached \(liveLogBytes >> 20) MB "
            + "(MACAPK_LOG_CAP_MB=\(liveLogCapBytes >> 20)); the output before this line was "
            + "rotated to \(prevLogPath) (one previous segment is kept, older ones are "
            + "discarded). MACAPK_LOG_CAP_MB=0 disables the cap.\n"
        let data = marker.data(using: .utf8) ?? Data()
        fm.createFile(atPath: liveLogPath, contents: data)
        liveLogHandle = FileHandle(forWritingAtPath: liveLogPath)
        _ = try? liveLogHandle?.seekToEnd()
        liveLogBytes = data.count
    }

    /// Move the previous run's log into `logs/archive/` with a
    /// timestamped name. No-op if the file is missing or empty (no
    /// point archiving zero bytes after a freshly-cleared session).
    private func archivePreviousLog() {
        let fm = FileManager.default
        // A capped run's OLDER segment (rotateLiveLogLocked) is archived too,
        // first, so the live (newest) one keeps the later name in the archive.
        if fm.fileExists(atPath: prevLogPath) {
            let ts = Int(Date().timeIntervalSince1970)
            try? fm.moveItem(atPath: prevLogPath,
                             toPath: logsDirectory().appendingPathComponent("archive")
                                .appendingPathComponent("launcher_\(ts).prev.log").path)
        }
        guard fm.fileExists(atPath: liveLogPath) else { return }
        if let attrs = try? fm.attributesOfItem(atPath: liveLogPath),
           let size = attrs[.size] as? Int, size == 0 {
            try? fm.removeItem(atPath: liveLogPath)
            return
        }
        let ts = Int(Date().timeIntervalSince1970)
        let dest = logsDirectory()
            .appendingPathComponent("archive")
            .appendingPathComponent("launcher_\(ts).log").path
        try? fm.moveItem(atPath: liveLogPath, toPath: dest)
    }

    /// Keep the `keep` newest launcher_*.log files in the archive
    /// directory; delete the rest.
    private func pruneArchive(keep: Int) {
        let fm = FileManager.default
        let archiveDir = logsDirectory().appendingPathComponent("archive")
        guard let contents = try? fm.contentsOfDirectory(
            at: archiveDir,
            includingPropertiesForKeys: [.contentModificationDateKey])
        else { return }
        let logs = contents.filter {
            $0.lastPathComponent.hasPrefix("launcher_")
                && $0.pathExtension == "log"
        }
        let sorted = logs.sorted { a, b in
            let mA = (try? a.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate) ?? Date.distantPast
            let mB = (try? b.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate) ?? Date.distantPast
            return mA > mB
        }
        for old in sorted.dropFirst(keep) {
            try? fm.removeItem(at: old)
        }
    }

    /// Path the user can grep / tail. Surfaced in the log panel header.
    var liveLogPathForDisplay: String { liveLogPath }

    enum StatusKind {
        case info, success, error
    }

    /// Coarse boot phases, in the order the runtime actually reaches them.
    /// The raw values encode that order so the displayed label can only
    /// ever move FORWARD: a straggler line from an earlier stage (or a
    /// second app-jar pass on a bundle) must not drag the label backwards.
    ///
    /// Every case is keyed off a marker the runtime ALREADY prints, so
    /// nothing new had to be logged for this. All of them are bare
    /// `fprintf(stderr, ...)` calls that ignore MACAPK_LOG_LEVEL, with one
    /// exception noted on `translatingNative`.
    enum LaunchPhase: Int, Comparable {
        case idle = 0
        case preparing              // launch() fired; no marker seen yet
        case cachedCode             // [macapk] cache hit for ... (skip DEX->JAR conversion)
        case convertingDex          // [macapk] converting DEX->JAR:
        case decodingResources      // [macapk] stage resources-decoded (kept as a phase for
                                    // an old runtime's "decoding ... via apktool" line;
                                    // since v1.0.1590 nothing is decoded)
        case unpackingNative        // [macapk] extracted lib/ ... | APK ships NO usable ABI
        case startingRuntime        // [macapk] HotSpot up.
        case translatingNative      // [xlat] ... (translator subprocess output)
        case rendering              // [I] angle: AngleBeginFrame call #N

        static func < (lhs: LaunchPhase, rhs: LaunchPhase) -> Bool {
            lhs.rawValue < rhs.rawValue
        }

        /// User-facing step label for the status bar. No percentages, no
        /// step counts: a cache hit skips conversion entirely, so "step 3
        /// of 8" would be wrong for half of all runs.
        var label: String {
            switch self {
            case .idle:              return "Running…"
            case .preparing:         return "Starting…"
            case .cachedCode:        return "Loading cached app code…"
            case .convertingDex:     return "Converting app code (DEX to JAR)…"
            case .decodingResources: return "Indexing app resources…"
            case .unpackingNative:   return "Unpacking assets and native libraries…"
            case .startingRuntime:   return "Starting the Android runtime…"
            case .translatingNative: return "Translating native code…"
            case .rendering:         return "Running…"
            }
        }

        /// Map one runtime stderr line onto the phase it announces, or nil
        /// when the line says nothing about progress (the overwhelmingly
        /// common case, so the cheap checks come first).
        ///
        /// Marker sources, all verified against the runtime this was
        /// written from:
        ///   apk_unpacker.cpp  cache hit / converting DEX->JAR / stage resources-decoded
        ///   main.cpp          extracted lib/ ... , APK ships NO usable ABI
        ///   jvm_loader.cpp    HotSpot up.
        ///   AppClassLoader.java  [xlat] <translator stdout>
        ///   angle_glue.cpp    AngleBeginFrame call #N
        static func forLogLine(_ line: String) -> LaunchPhase? {
            guard line.count > 8 else { return nil }
            if line.contains("[macapk] ") {
                if line.contains("cache hit for") { return .cachedCode }
                if line.contains("converting DEX->JAR:") { return .convertingDex }
                if line.contains("decoding AndroidManifest.xml via apktool")
                    || line.contains("stage resources-decoded") {
                    return .decodingResources
                }
                if line.contains("extracted lib/") { return .unpackingNative }
                if line.contains(kUnsupportedAbiMarker) { return .unpackingNative }
                if line.contains("HotSpot up.") { return .startingRuntime }
                return nil
            }
            // The translator's output reaches us through NativeLog.i (INFO).
            // That USED to mean these two never arrived on a default run --
            // the old quiet default was a WARN+ERROR severity floor, so the
            // label sat on "Starting the Android runtime" until the first
            // frame. Fixed in the same change that restored the install trail:
            // the quiet default is now AOSP's INFO floor plus a source-level
            // silence on the one chatty call site, so the translate step is
            // visible again without the dev-menu toggle.
            if line.contains("[xlat] ") { return .translatingNative }
            if line.contains("running translator:") { return .translatingNative }
            // Any frame means we are past boot; angle logs #1..#3 then
            // every 600th, so match the prefix rather than exactly "#1".
            if line.contains("AngleBeginFrame call #") { return .rendering }
            return nil
        }
    }

    /// Highest phase seen so far, as a raw value. Written from the log
    /// reader threads, so it lives behind its own lock and the @Published
    /// `phase` is only assigned on the main queue -- and only on an actual
    /// advance, which happens a handful of times per run rather than once
    /// per log line.
    private var highestPhaseRaw: Int = LaunchPhase.idle.rawValue
    private let phaseLock = NSLock()

    /// Reset phase tracking for a new run. Call on the main thread.
    func resetPhase(to newPhase: LaunchPhase) {
        phaseLock.lock()
        highestPhaseRaw = newPhase.rawValue
        phaseLock.unlock()
        phase = newPhase
    }

    /// Advance the published phase if `line` announces a later one.
    private func notePhase(from line: String) {
        guard let p = LaunchPhase.forLogLine(line) else { return }
        phaseLock.lock()
        let advanced = p.rawValue > highestPhaseRaw
        if advanced { highestPhaseRaw = p.rawValue }
        phaseLock.unlock()
        guard advanced else { return }
        DispatchQueue.main.async {
            // Re-check before publishing: the run can END (resetPhase back
            // to .idle) between the advance above and this main-queue hop,
            // and a straggler line drained from the pipe afterwards must
            // not resurrect a stale phase onto a finished run.
            self.phaseLock.lock()
            let stillCurrent = (p.rawValue == self.highestPhaseRaw)
            self.phaseLock.unlock()
            if stillCurrent { self.phase = p }
        }
    }

    /// Raise the unsupported-ABI banner when the runtime prints its
    /// no-usable-ABI warning. First occurrence wins (the runtime prints it
    /// once per run anyway); the user can dismiss it from the banner.
    private func noteUnsupportedAbi(from line: String) {
        guard line.contains(kUnsupportedAbiMarker) else { return }
        let abis = LaunchModel.abiList(fromWarning: line)
        DispatchQueue.main.async {
            if self.unsupportedAbiWarning == nil {
                self.unsupportedAbiWarning = abis
            }
        }
    }

    /// Pull the ABI list out of the runtime's warning line, which reads
    /// `[macapk] APK ships NO usable ABI (has: x86, x86_64) -- Mac.apk ...`.
    /// Returns "" if the parenthetical is not where we expect it, in which
    /// case the banner falls back to its generic wording. Shared with the
    /// install path, which scans the extractor's captured output for the
    /// same marker.
    static func abiList(fromWarning line: String) -> String {
        guard let open = line.range(of: "(has: ") else { return "" }
        guard let close = line.range(of: ")", options: [],
                                     range: open.upperBound..<line.endIndex)
        else { return "" }
        return String(line[open.upperBound..<close.lowerBound])
            .trimmingCharacters(in: .whitespaces)
    }

    /// The current child process, if any. Held so we can terminate it when
    /// the user clicks Stop or closes the window mid-run.
    var process: Process?

    /// An INSTALLED app opened from this window runs as its own .app (see
    /// launchInstalledBundle): this is that app, the timer tailing its run
    /// log into the log panel, and the termination observer.
    var runningApp: NSRunningApplication?
    var runLogTimer: DispatchSourceTimer?
    var terminationObserver: NSObjectProtocol?

    /// Resolve the runtime binary's absolute path. Lookup order:
    ///   1. Bundled at Contents/Resources/macapk-runtime.
    ///   2. Sibling dev-tree path (..//.. up to 3 levels), so running
    ///      the launcher straight from an Xcode/SwiftPM build works
    ///      without copying binaries around.
    ///   3. /usr/local/bin/macapk-runtime (legacy install location).
    /// Returns nil if no candidate exists; the launcher's status row
    /// surfaces this with a build hint.
    func runtimePath() -> String? {
        let fm = FileManager.default
        if let bundled = Bundle.main.path(forResource: kRuntimeBinaryName,
                                          ofType: nil) {
            return bundled
        }
        let bundleURL = Bundle.main.bundleURL
        let basename = (kRuntimeDevTreePath as NSString).lastPathComponent
        let candidates = [
            bundleURL.deletingLastPathComponent()
                .appendingPathComponent("../\(kRuntimeDevTreePath)").path,
            bundleURL.deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent(kRuntimeDevTreePath).path,
            bundleURL.deletingLastPathComponent().deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent(kRuntimeDevTreePath).path,
            "/usr/local/bin/\(basename)",
        ]
        for c in candidates {
            if fm.fileExists(atPath: c) { return c }
        }
        return nil
    }

    /// Pending log lines awaiting batched flush onto the main thread.
    /// Reads/writes are guarded by `pendingLogLock`. We coalesce many
    /// log appends into a single main-thread update to avoid O(N²)
    /// behaviour when the runtime emits thousands of lines (which would
    /// otherwise freeze the UI on shutdown when buffers drain).
    private var pendingLog: [String] = []
    private var pendingLogScheduled = false
    private let pendingLogLock = NSLock()
    /// Running count of non-pinned lines currently in `logLines`. Maintained
    /// incrementally so trimming is O(1) per call instead of O(N) scan.
    var nonPinnedCount = 0
    var pinnedCount = 0

    /// Append a line to the log feed. Pins error / warning lines (see
    /// `logLines` doc). Batches on the main thread to avoid O(N²) cost.
    /// Also mirrors every line to logs/macapk_launcher.log so a
    /// developer can grep the full unfiltered session even after the
    /// in-panel rolling tail drops older entries.
    func appendLog(_ line: String) {
        // Mirror first so a crash / hang doesn't lose recent lines.
        liveLogLock.lock()
        if let h = liveLogHandle, let data = (line + "\n").data(using: .utf8) {
            try? h.write(contentsOf: data)
            liveLogBytes += data.count
            if liveLogCapBytes > 0 && liveLogBytes >= liveLogCapBytes { rotateLiveLogLocked() }
        }
        liveLogLock.unlock()

        // This is the single choke point every runtime stderr/stdout line
        // flows through, so the two UI signals we derive from the log
        // stream are classified here: the boot-phase step label and the
        // unsupported-ABI banner. Both are substring checks that bail on
        // the first miss, and both only touch the main queue when the
        // derived state actually changes (a handful of times per run).
        notePhase(from: line)
        noteUnsupportedAbi(from: line)

        pendingLogLock.lock()
        pendingLog.append(line)
        let alreadyScheduled = pendingLogScheduled
        if !alreadyScheduled {
            pendingLogScheduled = true
        }
        pendingLogLock.unlock()
        if alreadyScheduled { return }
        // Schedule a single main-thread flush. All log lines arriving
        // before this fires get batched into the same update.
        DispatchQueue.main.async { self.flushPendingLog() }
    }

    /// Flush all pending log lines onto `logLines` in one shot. Maintains
    /// running counts of pinned/non-pinned lines so trim is O(batch size).
    private func flushPendingLog() {
        pendingLogLock.lock()
        let batch = pendingLog
        pendingLog.removeAll(keepingCapacity: true)
        pendingLogScheduled = false
        pendingLogLock.unlock()

        for line in batch {
            self.logLines.append(line)
            if LaunchModel.isPinnedSeverity(line) { pinnedCount += 1 }
            else { nonPinnedCount += 1 }
        }

        // Trim to the hard total cap. Strategy:
        //   1. First evict non-pinned lines (oldest first) until we're
        //      either at the cap OR within (cap - kPinnedReserved)
        //      non-pinned lines.
        //   2. If still over the cap, evict pinned lines (oldest first).
        //
        // This protects errors / warnings from a noisy chatter feed
        // pushing them out, but still guarantees a strict ceiling so
        // the LazyVStack never grows large enough to stall scrolling.
        // The full unfiltered stream is mirrored to
        // logs/macapk_launcher.log so nothing is actually lost.
        let nonPinnedTailMax = kTotalLogCap - kPinnedReserved
        if nonPinnedCount > nonPinnedTailMax {
            let toDrop = nonPinnedCount - nonPinnedTailMax
            var dropped = 0
            self.logLines.removeAll { line in
                if dropped >= toDrop { return false }
                if LaunchModel.isPinnedSeverity(line) { return false }
                dropped += 1
                return true
            }
            nonPinnedCount -= dropped
        }
        // Hard cap: if pinned lines alone exceed our reserved share,
        // start dropping the oldest pinned. A SEGV storm can otherwise
        // saturate the panel with identical lines.
        while self.logLines.count > kTotalLogCap {
            if let firstPinned = self.logLines.firstIndex(where: { LaunchModel.isPinnedSeverity($0) }) {
                self.logLines.remove(at: firstPinned)
                pinnedCount -= 1
            } else if !self.logLines.isEmpty {
                self.logLines.removeFirst()
                nonPinnedCount = max(0, nonPinnedCount - 1)
            } else { break }
        }
    }

    /// Returns true if a runtime log line should be pinned (never evicted
    /// from the panel under normal trim). The runtime's logger formats
    /// lines as `HH:MM:SS.ms <SEV> [tag] message` where <SEV> is one of
    /// T (trace), D (debug), I (info), W (warn), E (error). We pin W and E.
    static func isPinnedSeverity(_ line: String) -> Bool {
        // Find the first space after the timestamp; the next char is severity.
        // We check both the unicode-style ` E [` substring and the broader
        // ` W [` so this works whether the logger uses single-letter tags
        // or full words. The tag-bracket suffix anchors against false
        // positives in user-provided message text.
        if line.contains(" E [") { return true }
        if line.contains(" W [") { return true }
        if line.contains(" ERROR ") { return true }
        if line.contains(" WARN ")  { return true }
        return false
    }
}

// MARK: - Install request plumbing
//
// ONE funnel, four entry points. Finder double-click / Open-With, the drop
// zone, the file picker, and the folder batch all describe what they want as
// a request and hand it to `LauncherView.runInstall`. Before this, three of
// those four each had their own copy of "find the runtime, call
// library.install, interpret the outcomes", which is why they disagreed about
// what to report and why only one of them noticed the no-usable-ABI warning.
//
// >>> INTEGRATION SEAM (shared install core) <<<
// These two types mirror InstallCore's `InstallSource` / `InstallRequest`
// and are meant to be DELETED once the launcher compiles against that file.
//
// They are still here, rather than using the real types, for one concrete
// reason: `scripts/build_launcher.sh` compiles ONLY MacApkLauncherApp.swift
// and BuildInfo.generated.swift. Referencing InstallCore's types makes this
// file stop compiling under the shipping build command, so adopting them has
// to happen in the same change that adds InstallCore.swift to that swiftc
// invocation. Verified by typecheck both ways.
//
// The mapping, checked against the landed InstallCore.swift:
//     LauncherInstallSource   -> InstallSource   (same three cases)
//     .recordValue            -> InstallSource.recordValue -- NOTE: theirs
//                                returns bare "android"; this one keeps the
//                                requestor label ("android:<label>"), which
//                                the detail sheet renders. Pick one.
//     LauncherInstallRequest  -> InstallRequest  (sourceFile, source, interactive)
//     runInstall(_:)          -> InstallPipeline.run(InstallRequest(...))
//
// Two shape differences integration has to decide, not rename away:
//  1. InstallRequest names ONE `sourceFile`; this stays plural, because a
//     Finder multi-select arrives as N separate open events that must
//     coalesce into ONE confirmation. The cutover is "N pipeline runs behind
//     one confirm", not a field rename.
//  2. InstallPipeline owns its own confirm seam (Hooks.confirm), so
//     `confirmInstall` below becomes that hook rather than a step in front
//     of it -- otherwise the user is asked to confirm twice.

/// Where an install request came from. Drives the confirmation policy and is
/// recorded on the app's registry row.
enum LauncherInstallSource: Equatable {
    case gui
    case finder
    case android(requestorLabel: String)

    /// Value persisted on the app record.
    var recordValue: String {
        switch self {
        case .gui:                    return "gui"
        case .finder:                 return "finder"
        case .android(let requestor): return "android:\(requestor)"
        }
    }
}

struct LauncherInstallRequest {
    let sourceFiles: [URL]
    let source: LauncherInstallSource
    /// True = ask the user before touching anything. Finder opens are
    /// interactive (the user double-clicked a file, not a button in this
    /// app); drag-drop, the picker and the folder batch are not, because
    /// the gesture that started them WAS the confirmation.
    let interactive: Bool
}

/// What we can tell the user about an APK before installing it.
///
/// Two tiers, and the UI says which one it is showing. The detailed tier
/// needs the runtime to answer `--peek-manifest` (reading a manifest means
/// decoding binary XML out of the zip, which is the runtime's job and not
/// something to reimplement in the launcher). Until that flag exists we
/// degrade to what the file system alone can say: name and size.
struct ApkPeek {
    let file: URL
    let sizeBytes: Int64
    let package: String?
    let label: String?
    let versionName: String?
    let versionCode: Int64?

    /// True when the runtime answered. False = filename + size only.
    var isDetailed: Bool { package != nil }

    var displayName: String {
        if let l = label, !l.isEmpty { return l }
        return file.deletingPathExtension().lastPathComponent
    }

    var sizeText: String {
        ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file)
    }

    /// One line describing the package, or the fallback line naming what we
    /// could not determine. Saying so is the point: a confirmation that
    /// silently shows less information than usual is worse than one that
    /// admits the manifest was not read.
    var detailLine: String {
        guard isDetailed else {
            return "\(file.lastPathComponent) · \(sizeText) "
                + "(the runtime could not read this package's manifest, so "
                + "only the file name and size are known)"
        }
        var s = package ?? ""
        if let v = versionName, !v.isEmpty { s += " · version \(v)" }
        if let c = versionCode { s += " (\(c))" }
        return s + " · " + sizeText
    }

    /// Filesystem-only peek. Always succeeds.
    static func basic(_ url: URL) -> ApkPeek {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return ApkPeek(file: url, sizeBytes: Int64(size), package: nil,
                       label: nil, versionName: nil, versionCode: nil)
    }

    /// Ask the runtime to read the manifest. NEVER call on the main thread:
    /// it spawns a subprocess and waits.
    ///
    /// Every call asks. There used to be a once-per-session capability probe
    /// (PeekCapability) from before the runtime knew `--peek-manifest`; it
    /// kept the LAST result, so a single slow or unreadable file switched the
    /// peek off for the rest of the launcher session, and because the GUI
    /// install aborts when the peek has no package, every later install in
    /// that session failed too (owner's second Mac, 2026-10-02: an .apkm on a
    /// USB stick timed out once, the retry failed in the same second). The
    /// runtime ships inside this bundle, so the flag always exists and one
    /// file's failure says nothing about the next one.
    static func detailed(_ url: URL, runtimePath: String) -> ApkPeek {
        let fallback = basic(url)

        let p = Process()
        p.executableURL = URL(fileURLWithPath: runtimePath)
        // `--peek-manifest <apk>` takes the path as its OWN argument; it is
        // not a mode flag layered on --apk (runtime/src/main.cpp ParseArgs:
        // `else if (s == "--peek-manifest") { need(1); a.peek_manifest =
        // argv[++i]; }`). Getting this wrong is silent: the wrong spelling
        // just exits non-zero and we fall back to name + size forever.
        p.arguments = ["--peek-manifest", url.path]
        let out = Pipe()
        p.standardOutput = out
        // Usage text on the unknown-flag path is noise here, and discarding
        // it also removes any chance of a two-pipe reader deadlock.
        p.standardError = FileHandle.nullDevice
        do {
            try p.run()
        } catch {
            NSLog("[install] --peek-manifest could not start: \(error)")
            return fallback
        }
        // Watchdog: a peek that hangs must not strand the confirmation. The
        // budget grows with the file: a bundle with no JSON sidecar is read
        // by extracting its whole base apk, and from a USB stick on a cold
        // Mac a 115 MB .apkm passed the old flat 5 s.
        let budget = peekBudgetSeconds(sizeBytes: fallback.sizeBytes)
        let deadline = DispatchTime.now() + .seconds(budget)
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: deadline) {
            if p.isRunning {
                NSLog("[install] --peek-manifest passed its \(budget) s budget "
                      + "on \(url.lastPathComponent); stopping it")
                p.terminate()
            }
        }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        guard p.terminationStatus == 0,
              let obj = try? JSONSerialization.jsonObject(with: data),
              let j = obj as? [String: Any] else {
            return fallback
        }
        // Key aliases: the exact spelling the runtime emits is not pinned
        // yet, so accept the plausible ones rather than silently reading nil.
        func str(_ keys: [String]) -> String? {
            for k in keys {
                if let v = j[k] as? String, !v.isEmpty { return v }
            }
            return nil
        }
        let code: Int64? = {
            for k in ["versionCode", "version_code"] {
                if let n = j[k] as? NSNumber { return n.int64Value }
                if let s = j[k] as? String, let n = Int64(s) { return n }
            }
            return nil
        }()
        return ApkPeek(file: url,
                       sizeBytes: fallback.sizeBytes,
                       package: str(["package", "packageName", "package_name"]),
                       label: str(["label", "appLabel", "applicationLabel"]),
                       versionName: str(["versionName", "version_name", "version"]),
                       versionCode: code)
    }

    /// 10 s, plus 1 s per 8 MB (~8 MB/s is a slow USB 2 stick read), capped
    /// at 120 s. 115 MB -> 24 s. Overridable with MACAPK_PEEK_BUDGET_S.
    static func peekBudgetSeconds(sizeBytes: Int64) -> Int {
        if let s = ProcessInfo.processInfo.environment["MACAPK_PEEK_BUDGET_S"],
           let n = Int(s), n > 0 { return n }
        let mb = max(Int64(0), sizeBytes) / (8 * 1024 * 1024)
        return min(120, 10 + Int(mb))
    }
}

/// Collects errors from the N concurrent `setDefaultApplication` completion
/// handlers, which are delivered on an unspecified queue.
private final class HandlerFailureBox: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []
    func add(_ s: String) { lock.lock(); items.append(s); lock.unlock() }
    func all() -> [String] { lock.lock(); defer { lock.unlock() }; return items }
}

// MARK: - Environment override editor
//
// Used twice: the global default env in Settings > Advanced, and the per-app
// override in the detail sheet. Text-based (one KEY=VALUE per line) rather
// than a dynamic row table because the values here are pasted out of docs and
// changelogs far more often than they are typed one character at a time, and
// because a text blob round-trips through the clipboard.
//
// Edits are applied on an explicit button, not on every keystroke: parsing
// mid-typing would delete a variable the instant the user cleared its value
// to retype it.
struct EnvEditor: View {
    @Binding var env: [String: String]
    let caption: String

    @State private var text: String = ""
    @State private var problems: [String] = []
    @State private var loaded: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextEditor(text: $text)
                .font(.system(.caption, design: .monospaced))
                .frame(minHeight: 72)
                .overlay(RoundedRectangle(cornerRadius: 5)
                    .stroke(Color.secondary.opacity(0.3)))
            if !problems.isEmpty {
                // Warn, do not block: an unknown-looking key may simply be
                // one this build of the launcher has not heard of.
                ForEach(problems, id: \.self) { p in
                    Label(p, systemImage: "exclamationmark.triangle")
                        .font(.caption2)
                        .foregroundColor(.orange)
                }
            }
            HStack {
                Text(caption)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button("Apply") { apply() }
                    .controlSize(.small)
            }
        }
        .onAppear {
            // Only seed once: re-seeding on every re-render would stomp
            // whatever the user has typed but not applied.
            if !loaded { text = Self.text(from: env); loaded = true }
        }
    }

    private func apply() {
        let parsed = Self.parse(text)
        problems = parsed.problems
        env = parsed.env
    }

    static func text(from env: [String: String]) -> String {
        env.keys.sorted().map { "\($0)=\(env[$0] ?? "")" }.joined(separator: "\n")
    }

    /// One KEY=VALUE per line. Blank lines and `#` comments are skipped so a
    /// user can annotate why a knob is set.
    static func parse(_ text: String) -> (env: [String: String], problems: [String]) {
        var out: [String: String] = [:]
        var problems: [String] = []
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            guard let eq = line.firstIndex(of: "=") else {
                problems.append("\"\(line)\" has no '=' and was ignored")
                continue
            }
            let key = String(line[line.startIndex..<eq])
                .trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: eq)...])
                .trimmingCharacters(in: .whitespaces)
            guard !key.isEmpty else {
                problems.append("a line starting with '=' was ignored")
                continue
            }
            if !LauncherSettings.isValidEnvKey(key) {
                problems.append("\(key) is not a usual variable name "
                                + "(A-Z, 0-9 and _); it is still applied")
            }
            out[key] = value
        }
        return (out, problems)
    }
}

// MARK: - Google account plumbing (v1.0.1171)
//
// Mac.apk's Play Services layer (runtime/java/com/macapk/gms/) needs a
// signed-in Google account before it can answer a Play licence check the way
// a real device does. Until now the only way to add one was a shell command,
// which is not something a user of a Mac app should ever have to run.
//
// The launcher deliberately owns NONE of the account state. Every read and
// write goes through the runtime's `--gms` subcommands. The tokens live in a
// mode-0600 file the runtime writes, and a launcher that parsed that file
// directly would be a second copy of a credential format that is an
// implementation detail, plus a second place credentials could leak from.

/// One signed-in Google account, exactly as `--gms accounts` reports it.
struct GmsAccount: Identifiable, Hashable {
    let email: String
    /// Whatever the runtime lists after the address (which Play services this
    /// account is currently good for). Carried as free-form text and only ever
    /// displayed, never branched on: it is a diagnostic, not a contract.
    let services: String
    var id: String { email }
}

/// What one `--gms signin-window` run did.
///
/// The three cases are kept apart because they need three different UIs: a
/// cancel is NOT a failure and must not raise an error banner, and a real
/// failure carries Google's own message, which is usually the entire
/// diagnostic ("this account is managed", a blocked device, no network).
enum GmsSignInOutcome {
    case signedIn(String)
    case cancelled
    case failed(String)
}

/// A `--gms` failure. Text-only because text is the whole of what the panel
/// does with it: the runtime's own message (often Google's own message) is
/// more useful than any category this launcher could invent for it.
struct GmsError: Error {
    let message: String
}

/// Holds the live `--gms signin-window` child so the panel's Cancel button can
/// close it. A box rather than a plain stored property because the process is
/// spawned on a background thread and cancelled from the main one, and because
/// Cancel can be pressed in the window between the two, which `adopt` handles
/// by terminating a child that arrives after the fact.
private final class GmsSignInHandle: @unchecked Sendable {
    private let lock = NSLock()
    private var process: Process?
    private var cancelled = false

    func adopt(_ p: Process) {
        lock.lock()
        let already = cancelled
        process = p
        lock.unlock()
        if already { p.terminate() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let p = process
        lock.unlock()
        p?.terminate()
    }

    func wasCancelled() -> Bool {
        lock.lock(); defer { lock.unlock() }; return cancelled
    }
}

/// Records whether the watchdog killed a `--gms` subprocess, so a timeout can
/// be reported AS a timeout instead of as an ordinary non-zero exit. Its own
/// class because the flag is written on a timer queue and read on the caller's.
private final class GmsWatchdogFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var didFire = false
    func fire() { lock.lock(); didFire = true; lock.unlock() }
    func fired() -> Bool { lock.lock(); defer { lock.unlock() }; return didFire }
}

/// Spawns `macapk-runtime --gms ...` and reads its tab-separated answers.
///
/// NEVER call any of this on the main thread. Every entry point spawns a
/// subprocess and waits for it, and `signIn` waits for a human to type a
/// password (and possibly a second factor on another device) into Google's own
/// page, so it can block for minutes.
enum GmsCLI {
    /// Exit status the runtime uses for "the user closed the sign-in window".
    /// Distinct from 1 on purpose: a cancel must not surface as an error.
    private static let kCancelled: Int32 = 2

    /// Wall-clock ceiling for the subcommands that only touch local state.
    /// `signin-window` is deliberately exempt (see `signIn`).
    private static let kLocalTimeout: TimeInterval = 30

    private struct Answer {
        let status: Int32
        /// stdout and stderr merged, tail-capped.
        let text: String
        /// True when the watchdog killed it rather than it exiting on its own.
        let timedOut: Bool
        /// Non-nil when the process could not be started at all.
        let launchError: String?
    }

    /// Run one subcommand and collect everything it printed.
    ///
    /// `onStarted` receives the live Process so a caller can terminate it (the
    /// panel's Cancel button for the blocking sign-in). It fires on the
    /// calling background thread, immediately after a successful spawn.
    private static func run(_ runtimePath: String, _ args: [String],
                            timeout: TimeInterval?,
                            onStarted: ((Process) -> Void)? = nil) -> Answer {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: runtimePath)
        p.arguments = ["--gms"] + args
        // ONE pipe for both streams. The runtime logs to stderr while a
        // subcommand runs, and two pipes with a single reader deadlock as soon
        // as the unread one fills its 64 KB buffer (the install path carries
        // the same comment for the same reason). Merging also means a usage or
        // error message printed on either stream still reaches the user; the
        // parsers below ignore any line whose first tab-separated field is not
        // a record tag they know, which is what makes that safe.
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError  = pipe
        do {
            try p.run()
        } catch {
            return Answer(status: -1, text: "", timedOut: false,
                          launchError: error.localizedDescription)
        }
        onStarted?(p)
        let watchdog = GmsWatchdogFlag()
        if let t = timeout {
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + t) {
                if p.isRunning {
                    watchdog.fire()
                    p.terminate()
                }
            }
        }
        // Read to EOF BEFORE waiting. A chatty run can outrun the pipe buffer,
        // and waiting first would then block forever.
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        var text = String(data: data, encoding: .utf8) ?? ""
        // Keep the TAIL: both the answer record and the proximate failure are
        // at the end, and a sign-in that a user took two minutes over
        // accumulates a lot of ordinary log ahead of them.
        if text.count > 8192 { text = String(text.suffix(8192)) }
        return Answer(status: p.terminationStatus, text: text,
                      timedOut: watchdog.fired(), launchError: nil)
    }

    private static func lines(_ text: String) -> [String] {
        text.components(separatedBy: .newlines)
    }

    /// Split one line into its tab-separated fields.
    private static func fields(_ line: String) -> [String] {
        line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    }

    /// The message shown when a subcommand fails WITHOUT printing an `ERROR`
    /// record. It includes the runtime's own last line, because a bare
    /// "exit 1" is neither something a user can act on nor something anyone
    /// can debug from a screenshot of this panel.
    private static func diagnostic(_ a: Answer, verb: String) -> String {
        let tail = lines(a.text).last { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let base = "The Mac.apk runtime could not \(verb) (exit \(a.status))."
        guard let t = tail, !t.isEmpty else { return base }
        return base + " Last output: " + t
    }

    private static func timeoutMessage(_ verb: String) -> String {
        "The Mac.apk runtime did not \(verb) within \(Int(kLocalTimeout)) seconds."
    }

    /// The signed-in accounts. Zero of them is a normal, successful answer.
    static func accounts(runtimePath: String) -> Result<[GmsAccount], GmsError> {
        let a = run(runtimePath, ["accounts"], timeout: kLocalTimeout)
        if let e = a.launchError {
            return .failure(GmsError(message: "Could not start the Mac.apk runtime: \(e)"))
        }
        if a.timedOut { return .failure(GmsError(message: timeoutMessage("answer"))) }
        guard a.status == 0 else {
            return .failure(GmsError(message: diagnostic(a, verb: "read the account list")))
        }
        var out: [GmsAccount] = []
        for line in lines(a.text) {
            let f = fields(line)
            guard f.count >= 2, f[0] == "ACCOUNT", !f[1].isEmpty else { continue }
            let services = f.count > 2 ? f[2...].joined(separator: " ") : ""
            out.append(GmsAccount(email: f[1], services: services))
        }
        return .success(out)
    }

    /// Open Google's own sign-in window and wait for the user to finish.
    static func signIn(runtimePath: String,
                       onStarted: @escaping (Process) -> Void) -> GmsSignInOutcome {
        // No timeout, on purpose: what this call waits for is a person typing
        // a password, and possibly approving a prompt on a phone. A watchdog
        // here would close Google's window out from under them.
        let a = run(runtimePath, ["signin-window"], timeout: nil, onStarted: onStarted)
        if let e = a.launchError {
            return .failed("Could not start the Mac.apk runtime: \(e)")
        }
        if a.status == kCancelled { return .cancelled }
        // ERROR is checked before the exit status because it is the only place
        // Google's own wording survives, and that wording is the diagnostic.
        for line in lines(a.text) {
            let f = fields(line)
            if f.count >= 2, f[0] == "ERROR" {
                let msg = f[1...].joined(separator: " ")
                    .trimmingCharacters(in: .whitespaces)
                if !msg.isEmpty { return .failed(msg) }
            }
        }
        guard a.status == 0 else {
            return .failed(diagnostic(a, verb: "complete the sign-in"))
        }
        for line in lines(a.text) {
            let f = fields(line)
            if f.count >= 2, f[0] == "SIGNED_IN", !f[1].isEmpty {
                return .signedIn(f[1])
            }
        }
        // Exit 0 with no record. Say so rather than showing a signed-in state
        // we cannot name; the account list refresh would contradict it anyway.
        return .failed("The runtime reported a successful sign-in but did not "
                     + "say which account. Try again.")
    }

    static func signOut(email: String, runtimePath: String) -> Result<Void, GmsError> {
        let a = run(runtimePath, ["signout", email], timeout: kLocalTimeout)
        if let e = a.launchError {
            return .failure(GmsError(message: "Could not start the Mac.apk runtime: \(e)"))
        }
        if a.timedOut { return .failure(GmsError(message: timeoutMessage("sign out"))) }
        guard a.status == 0 else {
            return .failure(GmsError(message: diagnostic(a, verb: "sign out")))
        }
        return .success(())
    }

    /// `--gms status`, verbatim. Diagnostic only; never parsed.
    static func statusDump(runtimePath: String) -> String {
        let a = run(runtimePath, ["status"], timeout: kLocalTimeout)
        if let e = a.launchError { return "Could not start the Mac.apk runtime: \(e)" }
        if a.timedOut { return timeoutMessage("answer") }
        let trimmed = a.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "The runtime printed nothing (exit \(a.status))." }
        return trimmed
    }
}

/// Live account state for the Accounts panel.
///
/// Owned by `LauncherView` as a `@StateObject` rather than by the sheet, so a
/// sign-in that is still waiting on the user survives the panel being closed
/// and reopened. Closing it mid-sign-in is a normal thing to do: Google's page
/// is a separate window and that is where the user's attention has gone.
@MainActor
final class GmsAccountStore: ObservableObject {
    enum Phase: Equatable {
        case idle
        case loading
        case signingIn
        case signingOut(String)
    }

    @Published private(set) var accounts: [GmsAccount] = []
    @Published private(set) var phase: Phase = .idle
    /// Last failure, shown verbatim. Cleared when the next action starts.
    @Published private(set) var errorText: String? = nil

    /// Cheap "is an account signed in?" signal, read from the runtime's
    /// zero-byte `gms/accounts.present` sentinel WITHOUT spawning the runtime.
    /// The full `accounts` list still needs the `--gms accounts` probe (for the
    /// emails and services the panel shows), but the toolbar nudge only needs
    /// this boolean, and paying a JVM boot on every launcher start just to
    /// decide whether to show a hint is the trade we are avoiding. Refreshed at
    /// init, whenever the panel is dismissed, and when the app is brought
    /// forward (which catches a sign-in that happened inside a running app).
    @Published private(set) var sentinelSignedIn: Bool = false

    /// The launcher's view of "does this device have a Google account", from the
    /// cheapest truthful source available at the moment. `accounts` is only
    /// populated after the panel has probed once; the sentinel answers before
    /// then. Either one being positive is proof; both negative is the nudge case.
    var hasKnownAccount: Bool { !accounts.isEmpty || sentinelSignedIn }

    /// ~/Library/Application Support/Mac.apk/gms/accounts.present -- the same
    /// path GmsStore.accountSentinelFile() writes. Resolved once; the directory
    /// does not move under a running launcher.
    private static let sentinelURL: URL = {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil, create: false))
            ?? URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Library/Application Support")
        return base.appendingPathComponent("Mac.apk/gms/accounts.present")
    }()

    init() { refreshSentinel() }

    /// Re-read the sentinel. A `stat`, no process, no parsing, safe to call
    /// often. Only publishes when the value actually changes, so it does not
    /// churn the view on every window-focus event.
    func refreshSentinel() {
        let present = FileManager.default.fileExists(atPath: Self.sentinelURL.path)
        if present != sentinelSignedIn { sentinelSignedIn = present }
    }

    /// Handle on the live `--gms signin-window` child, kept ONLY so the
    /// panel's Cancel button can close it: terminating the runtime is what
    /// dismisses Google's window. Without this, a user who changed their mind
    /// has a modal-looking in-progress row and no way out of it, because the
    /// call is blocked on a human and so nothing times out.
    private var signInHandle: GmsSignInHandle? = nil

    var isBusy: Bool { phase != .idle }

    /// Same failure the header's "runtime not found" badge reports, worded for
    /// someone who came here to add an account rather than to build anything.
    private static let kNoRuntime =
        "Mac.apk cannot find its runtime, so it cannot manage accounts. "
        + "Install the runtime from the control at the top left of the main "
        + "window, or build it with runtime/scripts/build.sh."

    func refresh(runtimePath: String?) {
        // A refresh while a sign-in window is open would race the account file
        // the runtime is about to write, so callers re-refresh on completion.
        guard phase == .idle else { return }
        guard let rt = runtimePath else { errorText = Self.kNoRuntime; return }
        phase = .loading
        errorText = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let r = GmsCLI.accounts(runtimePath: rt)
            Task { @MainActor in
                self.phase = .idle
                switch r {
                case .success(let list): self.accounts = list
                case .failure(let e):    self.errorText = e.message
                }
                // The probe backfills the sentinel on the runtime side; re-read
                // it so the toolbar nudge agrees with the panel that just loaded.
                self.refreshSentinel()
            }
        }
    }

    func signIn(runtimePath: String?) {
        guard phase == .idle else { return }
        guard let rt = runtimePath else { errorText = Self.kNoRuntime; return }
        phase = .signingIn
        errorText = nil
        let handle = GmsSignInHandle()
        signInHandle = handle
        DispatchQueue.global(qos: .userInitiated).async {
            let outcome = GmsCLI.signIn(runtimePath: rt) { handle.adopt($0) }
            // Read the cancel flag on THIS thread, before hopping back: the
            // flag lives in the handle precisely so a non-Sendable Process
            // never has to cross onto the main actor.
            let userCancelled = handle.wasCancelled()
            Task { @MainActor in
                self.signInHandle = nil
                self.phase = .idle
                switch outcome {
                case .signedIn:
                    // Do not trust the returned address as the whole truth:
                    // re-read the list, so the panel shows what the runtime
                    // actually persisted.
                    self.refresh(runtimePath: rt)
                case .cancelled:
                    break                  // the user closed Google's window
                case .failed(let m):
                    // A cancel WE caused by terminating the child comes back
                    // as a failure. The user asked for it, so say nothing.
                    if !userCancelled { self.errorText = m }
                }
            }
        }
    }

    func cancelSignIn() {
        guard phase == .signingIn else { return }
        signInHandle?.cancel()
    }

    func signOut(_ account: GmsAccount, runtimePath: String?) {
        guard phase == .idle else { return }
        guard let rt = runtimePath else { errorText = Self.kNoRuntime; return }
        phase = .signingOut(account.email)
        errorText = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let r = GmsCLI.signOut(email: account.email, runtimePath: rt)
            Task { @MainActor in
                self.phase = .idle
                // Refresh FIRST, then report: refresh() clears errorText as it
                // starts, and its own completion only ever SETS an error, so
                // this ordering keeps the sign-out failure on screen while
                // still re-reading what the runtime has left.
                self.refresh(runtimePath: rt)
                if case .failure(let e) = r { self.errorText = e.message }
            }
        }
    }
}

// MARK: - Launcher view

/// Makes an NSAlert's primary button say what it is about to do.
///
/// The runtime-uninstall dialog has one checkbox that changes the action from
/// "remove the runtime" to "remove the runtime AND every app and its data".
/// Leaving the button reading "Uninstall Runtime" in both states puts the whole
/// consequence in body text, which is exactly the text a user in a hurry skips.
///
/// A separate NSObject because `NSControl.target` is a weak reference and the
/// action needs an `@objc` selector, which a SwiftUI View struct cannot host.
/// The caller keeps it alive across `runModal()`.
private final class PrimaryButtonRetitler: NSObject {
    private weak var primary: NSButton?
    private let plain: String
    private let destructive: String

    init(primary: NSButton, plain: String, destructive: String) {
        self.primary = primary
        self.plain = plain
        self.destructive = destructive
    }

    @objc func checkboxChanged(_ sender: NSButton) {
        primary?.title = sender.state == .on ? destructive : plain
    }
}

struct LauncherView: View {
    @EnvironmentObject var model: LaunchModel
    // v1.0.464: the authoritative installed-apps library lives here so
    // the drop zone (install on drop) AND the grid (browse / launch /
    // uninstall) share one source of truth. Passed into InstalledAppsView.
    @StateObject private var library = InstalledLibrary()
    @State private var isTargeted: Bool = false
    // Hidden dev-menu reveal: 5 taps on the "Mac.apk" header title
    // within a 2-second window flips model.devMenuRevealed. The
    // window resets the count if the gap between taps is too long,
    // so a casual click doesn't accidentally accumulate over hours.
    @State private var titleTapCount: Int = 0
    @State private var lastTitleTap: Date = Date.distantPast
    // v1.0.466: launch + storage config lives in a Settings sheet (gear
    // button) instead of cluttering the main window. Keeps the main view
    // to just: install drop zone -> app grid -> status.
    @State private var showSettings: Bool = false
    // v1.0.1171: Accounts is its OWN top-level panel, not a Settings section.
    // An account is not a preference: it decides whether an app bought on
    // Google Play will run at all, so it has to be findable without opening a
    // preferences sheet first.
    @State private var showAccounts: Bool = false
    /// Google account state. Lives here rather than in the sheet so a sign-in
    /// that is still waiting on the user survives the panel being closed.
    @StateObject private var accountStore = GmsAccountStore()
    /// Latch for the Finder open-event coalescer (see the onChange below).
    @State private var openEventDrainScheduled: Bool = false
    /// The app whose detail sheet is open, if any.
    @State private var detailApp: InstalledApp? = nil
    /// Which app currently opens .apk and friends, as a one-line summary.
    @State private var defaultHandlerSummary: String = "Checking…"
    @State private var settingDefaultHandler: Bool = false
    /// True when Mac.apk opens every supported extension; drives the green
    /// "Default" check that replaces the Make Default button.
    @State private var macApkIsDefaultHandler: Bool = false
    /// The visible result of the LAST Make Default click, shown on the row
    /// itself. nil until the button is used in this sheet. See
    /// makeDefaultHandler for why it is judged by re-reading the handlers.
    @State private var defaultHandlerFeedback: String? = nil
    @State private var defaultHandlerFeedbackOK: Bool = true
    /// Live state of the SHARED runtime, driving the top-left control.
    /// Re-read on appear and on every activation rather than cached, because a
    /// build, a promote, or the helper CLI can change it behind our back.
    @State private var runtimeState: RuntimeInstall.State = RuntimeInstall.state()
    @State private var runtimeBusy: Bool = false
    /// GitHub Releases update check behind the Check for Updates button
    /// (launcher/UpdateCheck.swift). Checked once when the panel opens.
    @ObservedObject private var updates = UpdateChecker.shared
    /// Drives the button's pulse while a newer release is waiting.
    @State private var updateGlowPulse: Bool = false

    var body: some View {
        VStack(spacing: 18) {
            header
            dropZone
            // Sits directly under the drop zone so it is the first thing
            // you see after an install / launch that hit the no-usable-ABI
            // path. Renders nothing when there is no warning.
            unsupportedAbiBanner
            // v1.0.466: the main window is now just install (drop zone) ->
            // the app grid (the thing you use most) -> status. Launch +
            // storage config moved to the Settings sheet (gear button).
            InstalledAppsView(library: library,
                              onInstall: { runInstall($0) },
                              onShowDetail: { detailApp = $0 })
                .environmentObject(model)
                // v1.0.503: claim the window's spare vertical height for the
                // app grid (which now grows with the window) ahead of the
                // trailing Spacer. When the grid is collapsed/empty it has no
                // flexible child, so the Spacer still absorbs the slack.
                .layoutPriority(1)
            statusBar
            if model.verbose {
                logPanel
            }
            Spacer(minLength: 8)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 20)
        .sheet(isPresented: $showSettings) { settingsSheet }
        .sheet(isPresented: $showAccounts) {
            AccountsSheet(store: accountStore,
                          onClose: { showAccounts = false })
                .environmentObject(model)
        }
        // The nudge pill / checkmark fade in and out with the signed-in signal
        // rather than popping. Bool is Equatable, so this animates exactly the
        // transitions declared on the pill in `header`.
        .animation(.easeInOut(duration: 0.2), value: accountStore.hasKnownAccount)
        // Re-read the cheap sentinel when the panel closes and whenever the
        // launcher is brought forward. The second one is what catches a sign-in
        // completed INSIDE a running app (the in-app prompt): the launcher had
        // no part in it, so a focus-time stat is how it learns.
        .onChange(of: showAccounts) { _, isOpen in
            if !isOpen { accountStore.refreshSentinel() }
        }
        .onReceive(NotificationCenter.default.publisher(
            for: NSApplication.didBecomeActiveNotification)) { _ in
            accountStore.refreshSentinel()
        }
        .sheet(item: $detailApp) { app in
            AppDetailSheet(library: library,
                           app: app,
                           // Dismiss FIRST, then act on the next runloop
                           // turn. Both actions open a modal panel/alert,
                           // and starting a nested modal loop while a sheet
                           // is still animating out is the classic way to
                           // get a stuck, unclickable window.
                           onUpdateFromApk: { target in
                               detailApp = nil
                               DispatchQueue.main.async { updateFromApk(target) }
                           },
                           onResetApp: { target in
                               detailApp = nil
                               DispatchQueue.main.async { confirmResetApp(target) }
                           },
                           onClose: { detailApp = nil })
                .environmentObject(model)
        }
        // v1.0.89: double-click on an installed-app tile bumps
        // model.pendingLaunch; we observe here and fire the same
        // launch() that the Launch button uses. Single source of
        // truth for "actually run" lives in this view; the tile just
        // signals intent.
        .onChange(of: model.pendingLaunch) { _, _ in
            if !model.isRunning && !model.apkPath.isEmpty {
                launch()
            }
        }
        // v1.0.464: APKs opened via Finder double-click / "Open With" /
        // Dock-drop arrive here as pendingInstallPaths. Install (never run),
        // then clear the queue so re-opening the same file re-fires.
        //
        // Coalesced on purpose. Finder emits one open event per selected
        // file, so this observer fires N times for one user gesture; without
        // the latch below, opening 5 APKs would put 5 modal confirmations on
        // screen back to back. The latch lets the burst finish arriving, then
        // drains the whole queue into a single request.
        .onChange(of: model.pendingInstallPaths) { _, _ in
            drainPendingInstalls()
        }
        // A window SwiftUI creates to deliver an open event (Mac.apk running
        // with its window closed) gets the URL appended before this view's
        // onChange is watching, so that first value is never a "change".
        // Drain on appear too, or that install silently never starts.
        .onAppear { drainPendingInstalls() }
        // Refresh on activation: re-READ the registry, then reconcile it
        // against the disk. The reconcile half catches a bundle or APK the
        // user deleted in Finder while the launcher sat in the background;
        // the re-read half catches rows OTHER processes wrote meanwhile
        // (macapk-installhelper installs, a stub's lastLaunchedAt) -- with
        // only reconcile() here the in-memory snapshot stayed hours stale,
        // which is half of what made the S2 row-erasure race so easy to hit.
        .onReceive(NotificationCenter.default.publisher(
            for: NSApplication.didBecomeActiveNotification)) { _ in
            library.refresh()
        }
    }

    private func drainPendingInstalls() {
        guard !model.pendingInstallPaths.isEmpty, !openEventDrainScheduled else { return }
        openEventDrainScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            openEventDrainScheduled = false
            let queued = model.pendingInstallPaths
            model.pendingInstallPaths = []
            guard !queued.isEmpty else { return }
            // interactive: false -- "silent" means no confirm sheet as
            // well as no window. The user chose this file and asked for
            // it to be opened; a modal asking whether they meant it is
            // the thing being removed here.
            runInstall(LauncherInstallRequest(
                sourceFiles: queued.map { URL(fileURLWithPath: $0) },
                source: .finder,
                interactive: false))
        }
    }

    // MARK: Header
    private var header: some View {
        // v1.0.88: centered title experiment. The icon + title + tagline
        // sit in a centered VStack; the right-side dev-info column is
        // overlaid in the trailing corner via ZStack so it doesn't push
        // the centered group off-axis. If the centered look doesn't
        // grow on you, revert to the previous icon-on-left layout.
        ZStack {
            VStack(alignment: .center, spacing: 4) {
                // Word only, no icon (owner, 2026-10-13). The icon+word group
                // used to be what was centred, which put "Mac.apk" half an
                // icon right of the tagline and of a centred sheet.
                Text("Mac.apk")
                    .font(.system(size: 24, weight: .semibold, design: .rounded))
                    .contentShape(Rectangle())
                    .onTapGesture {
                        let now = Date()
                        if now.timeIntervalSince(lastTitleTap) > 2.0 {
                            titleTapCount = 1
                        } else {
                            titleTapCount += 1
                        }
                        lastTitleTap = now
                        if titleTapCount >= 5 && !model.devMenuRevealed {
                            model.devMenuRevealed = true
                        }
                    }
                Text("Run Android APKs natively on macOS")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity)
            // Top-LEFT, per the owner: "two buttons on the main screen probably
            // top leftish ... that says Runtime: (install) and (uninstall)".
            // Overlaid in the leading corner of the same ZStack the trailing
            // dev column uses, so neither pushes the centred title off axis.
            HStack {
                runtimeControl
                Spacer()
            }
            HStack {
                Spacer()
            // v1.0.85: build ID + runtime status moved behind the
            // 5-tap dev menu. These are dev-only diagnostics --
            // regular users don't need to know the build hash or
            // which runtime binary the launcher is using. When NOT
            // revealed: nothing here (header just has the title +
            // tagline). When revealed: same two-line build/runtime
            // block we used to show always, but only shows if a
            // runtime is actually missing (red badge) -- that
            // condition is bad enough that we WANT to surface it
            // even without the dev menu.
            VStack(alignment: .trailing, spacing: 2) {
                if model.devMenuRevealed {
                    Text("build \(BuildInfo.id)")
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundColor(.secondary)
                        .help("Stamped at launcher build time. If this does not change after a rebuild, you are running a cached app.")
                    if let p = model.runtimePath() {
                        HStack(spacing: 5) {
                            Text(URL(fileURLWithPath: p).lastPathComponent)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundColor(.secondary)
                            Image(systemName: "circle.fill")
                                .foregroundColor(.green)
                                .font(.system(size: 7))
                        }
                        .help("macapk-runtime found at \(p)")
                    }
                }
                // Missing-runtime warning ALWAYS shows -- it blocks
                // every launch and tells the user how to fix it.
                if model.runtimePath() == nil {
                    HStack(spacing: 5) {
                        Text("runtime not found")
                            .font(.caption)
                            .foregroundColor(.red)
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                            .font(.system(size: 10))
                    }
                    .help("Build the runtime first: runtime/scripts/build.sh")
                }
            }
            // v1.0.1171: Accounts, left of the gear. Signing in with Google
            // is what lets an app bought on Google Play complete its licence
            // check, so this is a peer of Settings rather than a row inside it.
            //
            // v1.0.1173: a "Sign in with Google" nudge sits to the LEFT of the
            // glyph until an account exists, then disappears -- otherwise a lone
            // profile icon gives a first-time user no reason to click it. The
            // signal is the cheap `accounts.present` sentinel (hasKnownAccount),
            // NOT the full account list, so we still avoid booting a JVM on
            // launcher start just to decide whether to show it.
            if !accountStore.hasKnownAccount {
                Button { showAccounts = true } label: {
                    Text("Sign in with Google")
                        .font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(
                            Capsule().fill(Color.accentColor.opacity(0.15)))
                        .overlay(
                            Capsule().stroke(Color.accentColor.opacity(0.35), lineWidth: 1))
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .help(accountsHelpText)
                // A gentle fade when it goes away after sign-in, rather than a
                // jump: the glyph beside it stays put, so only the pill animates.
                .transition(.opacity.combined(with: .move(edge: .trailing)))
            }
            Button { showAccounts = true } label: {
                Image(systemName: accountStore.hasKnownAccount
                      ? "person.crop.circle.badge.checkmark"
                      : "person.crop.circle")
                    .font(.system(size: 16, weight: .regular))
            }
            .buttonStyle(.borderless)
            .help(accountsHelpText)
            // v1.0.466: Settings gear (always visible) opens the sheet
            // holding screen size, orientation, and the Android files
            // location -- keeping that config out of the main window.
            Button { showSettings = true } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 16, weight: .regular))
            }
            .buttonStyle(.borderless)
            .help("Settings — screen size, orientation, and where app files are stored.")
            }   // close trailing HStack
        }       // close ZStack
    }

    /// Tooltip for the Accounts button. A named `String` rather than a ternary
    /// written inline at the call site: both `help` overloads take a string
    /// literal, so a literal-vs-literal ternary there is needlessly ambiguous.
    private var accountsHelpText: String {
        if accountStore.accounts.isEmpty {
            return "Accounts. Sign in with Google so apps bought on Google "
                 + "Play can check their licence."
        }
        return "Accounts. Signed in as "
             + accountStore.accounts.map { $0.email }.joined(separator: ", ") + "."
    }

    // MARK: Runtime install / uninstall (top-left)

    /// "Runtime: Installed" plus the two buttons.
    ///
    /// The state shown here is NOT a second notion of "installed": it is
    /// `build-id.txt` staged-vs-shipped, the same file and the same comparison
    /// `debug/staleness.sh` makes. A UI that could disagree with that script
    /// would drift from it, so it reads the same source.
    private var runtimeControl: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 5) {
                Image(systemName: runtimeStatusIcon)
                    .foregroundColor(runtimeStatusColor)
                    .font(.system(size: 10))
                Text("Runtime: \(runtimeState.summary)")
                    .font(.caption)
                    .foregroundColor(runtimeState.installed ? .secondary : .primary)
                // The runtime's own identity, next to its state -- the owner
                // asked for the state "ideally with the build stamp". Inline
                // only behind the dev menu, matching the existing rule that
                // build hashes are a developer diagnostic (v1.0.85); the
                // tooltip carries the full thing for everyone, because "is the
                // runtime the one I just built" is the question this control
                // gets asked most.
                if model.devMenuRevealed, let short = runtimeJavaIdShort {
                    Text(short)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundColor(.secondary)
                }
            }
            .help(runtimeHelpText)
            HStack(spacing: 6) {
                Button(runtimeInstallTitle) { installRuntime() }
                    .disabled(runtimeBusy || !runtimeState.sourceAvailable)
                    .help(runtimeState.sourceAvailable
                          ? "Stage the shared runtime into \(RuntimeInstall.stagedRoot.path). Installed apps are not touched."
                          : "Mac.apk.app carries no runtime payload to install from. Rebuild with runtime/scripts/build.sh.")
                Button("Uninstall…") { confirmUninstallRuntime() }
                    .disabled(runtimeBusy || !runtimeState.installed)
                    .help("Remove the shared runtime. Your Android apps and their data stay installed, but cannot launch until you reinstall it.")
                updateButton
            }
            .controlSize(.small)
        }
        .onAppear {
            refreshRuntimeState()
            updates.checkOnLaunch()
        }
        .onReceive(NotificationCenter.default.publisher(
            for: NSApplication.didBecomeActiveNotification)) { _ in
            refreshRuntimeState()
        }
    }

    // MARK: Check for Updates (beside the runtime buttons)

    /// Icon-only, inline with Install / Uninstall: a labelled third button in
    /// that row runs into the centred title at the window's minimum
    /// width. Glows amber when the launch check found a newer GitHub release
    /// than the one baked into this build. Amber rather than red: red already
    /// means "runtime missing" in this corner, and a waiting update is not a
    /// failure. The same check lives in the menu bar (Mac.apk > Check for
    /// Updates...).
    private var updateButton: some View {
        let glowing = updates.updateAvailable
        return Button {
            updates.check { updates.presentResult($0) }
        } label: {
            Image(systemName: glowing ? "arrow.down.circle.fill" : "arrow.triangle.2.circlepath")
                .accessibilityLabel(glowing ? "Update Available" : "Check for Updates")
        }
        .disabled(updates.isChecking)
        .tint(glowing ? .orange : nil)
        .buttonStyle(.bordered)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.orange, lineWidth: glowing ? 1.5 : 0)
                .opacity(glowing ? (updateGlowPulse ? 1.0 : 0.45) : 0)
        )
        .shadow(color: glowing ? Color.orange.opacity(updateGlowPulse ? 0.9 : 0.35) : .clear,
                radius: glowing ? (updateGlowPulse ? 7 : 3) : 0)
        // onAppear too: the menu bar item can find the update while the
        // panel is closed, and onChange never fires for a value that was
        // already true when the view appeared.
        .onAppear { setUpdateGlow(glowing) }
        .onChange(of: glowing) { _, on in setUpdateGlow(on) }
        .help(updateHelpText)
    }

    private func setUpdateGlow(_ on: Bool) {
        if on {
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                updateGlowPulse = true
            }
        } else {
            updateGlowPulse = false
        }
    }

    private var updateHelpText: String {
        switch updates.state {
        case .available(let tag, _):
            return "Mac.apk \(tag) is available (you have v\(updates.displayVersion)). Click to go to the releases page."
        case .checking:
            return "Checking GitHub for a newer Mac.apk release…"
        default:
            return updates.bakedRelease == nil
                ? "Development build: update checks only run on published releases."
                : "Check GitHub for a newer Mac.apk release."
        }
    }

    /// `JAVA_ID=xxxxxxxx` from the STAGED runtime's build-id.txt. JAVA_ID
    /// rather than the whole blob because it is the one that keys the per-app
    /// DEX caches, so it is the id that explains a re-conversion.
    private var runtimeJavaIdShort: String? {
        guard let blob = runtimeState.stagedBuildId else { return nil }
        for line in blob.split(whereSeparator: \.isNewline)
        where line.hasPrefix("JAVA_ID=") {
            return "JAVA_ID=" + line.dropFirst("JAVA_ID=".count).prefix(8)
        }
        return nil
    }

    private var runtimeHelpText: String {
        guard runtimeState.installed else {
            return runtimeState.removedByUser
                ? "You removed the shared runtime. Your Android apps are still installed "
                + "and keep their data; press Install to make them launchable again."
                : "The shared runtime is not staged yet. Press Install."
        }
        var s = "Shared runtime at \(RuntimeInstall.stagedRoot.path)\n"
        s += (runtimeState.stagedBuildId ?? "no build-id.txt") + "\n"
        s += "launcher build \(BuildInfo.id)"
        if runtimeState.isStale {
            s += "\n\nThis is NOT what Mac.apk.app currently ships"
            if !runtimeState.missingPayloadDirs.isEmpty {
                s += " (missing " + runtimeState.missingPayloadDirs.joined(separator: ", ") + ")"
            }
            s += ". Press Update."
        }
        return s
    }

    private var runtimeStatusIcon: String {
        if !runtimeState.installed { return "xmark.circle.fill" }
        return runtimeState.isStale ? "exclamationmark.triangle.fill" : "checkmark.circle.fill"
    }
    private var runtimeStatusColor: Color {
        if !runtimeState.installed { return .red }
        return runtimeState.isStale ? .orange : .green
    }
    /// "Install" is the wrong word for a runtime that is already there, and
    /// hiding the button would remove the only repair affordance. So the title
    /// says which of the three things pressing it will do.
    private var runtimeInstallTitle: String {
        if !runtimeState.installed { return "Install" }
        return runtimeState.isStale ? "Update" : "Reinstall"
    }

    private func refreshRuntimeState() {
        runtimeState = RuntimeInstall.state()
        // Once per control-panel session: bring every installed app's stub
        // up to this Mac.apk's template (v1.0.1592, StubRefresh). A stub from
        // before v1.0.1592 cannot refresh itself on launch, and an app the
        // user never launches would otherwise keep its old stub forever. Off
        // the main thread: it hashes one ~700 KB file per installed app.
        if runtimeState.installed {
            DispatchQueue.global(qos: .utility).async {
                StubRefresh.refreshAllOnce { model.appendLog($0) }
            }
        }
    }

    private func installRuntime() {
        runtimeBusy = true
        model.status = "Installing the Mac.apk runtime…"
        model.statusKind = .info
        // Off the main thread: staging is ~15 files and an APFS clone, fast,
        // but it is still file IO and a beachballed control panel during an
        // action the user just asked for is its own bug.
        DispatchQueue.global(qos: .userInitiated).async {
            // appendLog is already designed to be called from the runtime's
            // background reader thread (it locks, mirrors to the log file, and
            // hops to main itself), so it needs no wrapper here.
            let r = RuntimeInstall.install { model.appendLog($0) }
            Task { @MainActor in
                runtimeBusy = false
                refreshRuntimeState()
                model.status = r.isOk
                    ? "Runtime installed. Your Android apps can launch again."
                    : "Could not install the runtime: \(r.message)"
                model.statusKind = r.isOk ? .success : .error
            }
        }
    }

    /// The uninstall confirmation, to the owner's spec (2026-08-12).
    ///
    /// Two decisions in here are load-bearing rather than cosmetic:
    ///
    ///  * **The checkbox is OFF every time.** It is constructed fresh and never
    ///    persisted, so the safe path is the one you get by not reading
    ///    carefully. There is deliberately no "remember this" anywhere.
    ///  * **The primary button's title changes with the checkbox**, so the
    ///    button itself states what is about to happen instead of leaving the
    ///    consequence three lines up in body text nobody re-reads.
    ///
    /// The counts are measured, not adjectives: "all your apps" is not informed
    /// consent when the library at risk is the owner's own.
    private func confirmUninstallRuntime() {
        // Never yank the runtime out from under a live app.
        let live = RuntimeInstall.runningGuests()
        if !live.isEmpty {
            let a = NSAlert()
            a.messageText = "Quit your Android apps first"
            a.informativeText = """
            \(live.count == 1 ? "An Android app is" : "\(live.count) Android apps are") \
            still running (\(live.joined(separator: ", "))). Removing the runtime \
            underneath a running app would crash it.

            Quit \(live.count == 1 ? "it" : "them") and try again.
            """
            a.alertStyle = .warning
            a.addButton(withTitle: "OK")
            a.runModal()
            return
        }

        let (appCount, appBytes) = InstallPipeline.installedAppFootprint()
        let sizeStr = ByteCountFormatter.string(fromByteCount: appBytes, countStyle: .file)

        let alert = NSAlert()
        alert.messageText = "Uninstall the Mac.apk runtime?"
        // The reassurance IS the point of separating the two actions. A bare
        // "your apps will be inoperable" reads like they are being broken
        // permanently, which is exactly what this design avoids.
        alert.informativeText = """
        Uninstalling the runtime will make your Android apps inoperable.
        They stay installed, and will work again when you reinstall the runtime.
        """
        alert.alertStyle = .warning

        let plainTitle = "Uninstall Runtime"
        let destructiveTitle = appCount == 1
            ? "Uninstall Runtime and Remove 1 App"
            : "Uninstall Runtime and Remove \(appCount) Apps"

        // Manual frames, not Auto Layout: NSAlert sizes an accessory view from
        // its frame, and a stack view that computes its own fitting size gets
        // clipped to zero height here often enough to not be worth the risk.
        let width: CGFloat = 380
        let detail = NSTextField(wrappingLabelWithString: appCount == 0
            ? "You have no Android apps installed, so this removes nothing extra."
            : "This permanently removes all \(appCount) app\(appCount == 1 ? "" : "s") "
            + "and their data (\(sizeStr)). This cannot be undone.")
        detail.translatesAutoresizingMaskIntoConstraints = true
        detail.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        detail.textColor = .secondaryLabelColor
        detail.preferredMaxLayoutWidth = width - 20
        detail.frame = NSRect(x: 20, y: 0, width: width - 20, height: 32)

        let check = NSButton(checkboxWithTitle: "Also remove all Android apps",
                             target: nil, action: nil)
        check.state = .off                   // never remembered, never pre-ticked
        check.isEnabled = appCount > 0
        check.frame = NSRect(x: 0, y: 36, width: width, height: 18)

        let container = NSView(frame: NSRect(x: 0, y: 0, width: width, height: 56))
        container.addSubview(detail)
        container.addSubview(check)
        alert.accessoryView = container

        alert.addButton(withTitle: plainTitle)   // buttons[0] = default
        alert.addButton(withTitle: "Cancel")
        alert.buttons[0].hasDestructiveAction = true

        let retitler = PrimaryButtonRetitler(primary: alert.buttons[0],
                                             plain: plainTitle,
                                             destructive: destructiveTitle)
        check.target = retitler
        check.action = #selector(PrimaryButtonRetitler.checkboxChanged(_:))

        let response = alert.runModal()
        withExtendedLifetime(retitler) {}     // keep the unowned action target alive
        guard response == .alertFirstButtonReturn else { return }
        uninstallRuntime(removeAllApps: check.state == .on)
    }

    private func uninstallRuntime(removeAllApps: Bool) {
        runtimeBusy = true
        model.status = removeAllApps
            ? "Removing every app, then the runtime…"
            : "Removing the runtime…"
        model.statusKind = .info
        let runtime = model.runtimePath()
        let root = model.androidRootURL()
        DispatchQueue.global(qos: .userInitiated).async {
            let log: (String) -> Void = { model.appendLog($0) }
            var appsNote = ""
            var appsClean = true
            if removeAllApps, let rt = runtime {
                // ONE remover. This is the same uninstall() the tile's
                // right-click menu drives, applied per package, plus the
                // orphan/retained-APK sweep and a reconcile.
                var hooks = InstallPipeline.Hooks()
                hooks.log = log
                BundleGenerator.install(into: &hooks)
                let pipeline = InstallPipeline(runtimePath: rt, androidRoot: root, hooks: hooks)
                let rep = pipeline.uninstallAllApps()
                appsClean = rep.isClean
                appsNote = " Removed \(rep.removed.count) app(s)."
                if !rep.isClean {
                    appsNote += " PROBLEMS: "
                              + (rep.failures + rep.refusedBundles).joined(separator: "; ")
                }
            } else if removeAllApps {
                appsClean = false
                appsNote = " Could not remove apps: the runtime path is unavailable."
            }
            let r = RuntimeInstall.uninstall(log: log)
            Task { @MainActor in
                runtimeBusy = false
                refreshRuntimeState()
                library.refresh()
                model.status = (r.isOk ? r.message : "Uninstall problem: \(r.message)") + appsNote
                model.statusKind = (r.isOk && appsClean) ? .success : .error
            }
        }
    }

    // MARK: Drop zone
    private var dropZone: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(
                    style: StrokeStyle(lineWidth: 2, dash: [8, 5])
                )
                .foregroundColor(isTargeted ? .accentColor : Color.secondary.opacity(0.4))
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(isTargeted ? Color.accentColor.opacity(0.10) : Color.clear)
                )

            VStack(spacing: 10) {
                // v1.0.464: the drop zone INSTALLS (no longer loads-for-
                // run). Dropping / clicking-to-pick registers the APK in
                // the library; launching happens from the grid below.
                Image(systemName: library.installProgress != nil
                      ? "arrow.down.circle"
                      : "square.and.arrow.down")
                    .font(.system(size: 34, weight: .light))
                if let prog = library.installProgress {
                    // Bind the progress the library has always tracked but
                    // the drop zone never showed: which APK of how many,
                    // and WHICH FILE is being unpacked right now (the part
                    // that takes the time). Kept to the same two text rows
                    // the idle state uses, because this zone is a fixed
                    // 124pt tall and a third row would clip; the linear
                    // bar for batches lives in the installed-apps header.
                    Text(prog.total > 1
                         ? "Installing \(min(prog.completed + 1, prog.total)) of \(prog.total)…"
                         : "Installing…")
                        .font(.system(size: 15, weight: .medium))
                    Text(prog.current.isEmpty
                         ? "Unpacking manifest, icon, and assets"
                         : prog.current)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal, 12)
                } else {
                    Text("Drop an APK here to install")
                        .font(.system(size: 15, weight: .medium))
                    Text("or click to choose a file  ·  .apk .apkm .xapk .apks .apkx")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary.opacity(0.9))
            .padding()
        }
        .frame(height: 124)
        // Whole zone is clickable; the dashed border + arrow icon
        // signal "drop or click" but the .contentShape pins the hit
        // target to the rectangle so clicks on transparent corners
        // also register. .pointingHand changes the cursor so the
        // click affordance is unmistakable.
        .contentShape(Rectangle())
        .onHover { hovering in
            if hovering {
                NSCursor.pointingHand.set()
            } else {
                NSCursor.arrow.set()
            }
        }
        .onTapGesture { pickFile() }
        // Accept file URLs. We filter to .apk inside the handler so bad
        // drops give a useful error rather than silently accepting.
        // v1.0.464: a valid drop INSTALLS the APK (extract-only) into the
        // library rather than loading it for a direct run.
        .onDrop(of: [UTType.fileURL], isTargeted: $isTargeted) { providers in
            guard let provider = providers.first else { return false }
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                guard let u = url else { return }
                let ext = u.pathExtension.lowercased()
                DispatchQueue.main.async {
                    if kSupportedApkExtensions.contains(ext) {
                        // Not interactive: the drop itself was the gesture.
                        runInstall(LauncherInstallRequest(sourceFiles: [u],
                                                          source: .gui,
                                                          interactive: false))
                    } else {
                        model.status = "Unsupported file type: .\(ext). Drop a .apk, .apkm, .xapk, .apks, or .apkx."
                        model.statusKind = .error
                    }
                }
            }
            return true
        }
    }

    // MARK: Settings sheet
    //
    // v1.0.466: screen size, orientation, and the Android files location
    // moved here from the main window (owner: the inline file-path text
    // wasn't user-friendly and isn't how Mac apps do it). The Advanced
    // section (dev toggles) appears only after the 5-tap title reveal.
    private var settingsSheet: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Settings")
                    .font(.headline)
                Spacer()
                Button("Done") { showSettings = false }
                    .keyboardShortcut(.defaultAction)
            }
            .padding()
            Divider()
            Form {
                // The Display section (Screen size + Orientation) moved to
                // Advanced (dev menu) 2026-10-04, Mac.apk-Releases issue 1
                // ("the Screen size setting fails to save"). Neither picker
                // ever reached an INSTALLED app: AppStubMain.swift launches the
                // runtime with only --apk and --android-root, so both applied
                // only to runs started from this window, and neither was
                // persisted. Every app window is user-resizable and re-renders
                // live at the new size (cocoa_bridge.mm, live-resize
                // re-render), orientation comes from the app's manifest, and
                // View > Rotate (Cmd+R) turns a window, so neither is a user
                // setting.
                Section("File types") {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Open Android app files with Mac.apk")
                            Text(defaultHandlerSummary)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(2)
                        }
                        Spacer()
                        // Feedback lives ON this row (owner, 2026-10-04: "it's
                        // hard to tell that the button does anything"). The old
                        // confirmation went to model.status, i.e. the main
                        // window's status bar, which this sheet covers.
                        if settingDefaultHandler {
                            ProgressView().controlSize(.small)
                        } else if macApkIsDefaultHandler {
                            Label("Default", systemImage: "checkmark.circle.fill")
                                .foregroundColor(.green)
                                .help("Mac.apk opens .apk, .apkm, .xapk, .apks and .apkx files.")
                        } else {
                            Button("Make Default") { makeDefaultHandler() }
                        }
                    }
                    if let fb = defaultHandlerFeedback {
                        Label(fb, systemImage: defaultHandlerFeedbackOK
                              ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundColor(defaultHandlerFeedbackOK ? Color.green : Color.orange)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text("Covers .apk, .apkm, .xapk, .apks and .apkx. "
                         + "Double-clicking one in Finder installs it.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Section("Logs") {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Diagnostics")
                        Spacer()
                        Button("Reveal logs folder") { revealLogsFolder() }
                    }
                    Text("install.log records every install attempt, including "
                       + "the installer's own output when one fails. Send this "
                       + "folder when reporting a problem.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                // Owner, 2026-10-19: app data moved out of the bundles into one
                // folder per app (AppDataLayout), and settings opens it so the
                // data is easy to look at and clean.
                Section("App data") {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Saves, caches and files")
                        Spacer()
                        Button("Open App Data Folder") { openAppDataFolder() }
                    }
                    Text("Each installed app keeps its data in its own folder here, "
                       + "named after its package. Deleting an app's folder clears "
                       + "its data, and the app starts fresh the next time it opens.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    // Owner, 2026-10-20: Android's Download, Documents, Pictures,
                    // Movies and Music are the Mac's own folders (SharedStorage
                    // USER_FOLDER_MAP, on since v1.0.1657). The runtime reads
                    // this switch from a file, since a Dock-launched app gets no
                    // environment from here (SharedStorage.userFoldersOffFile).
                    Toggle("Use my Mac's Downloads, Documents, Pictures, Movies and Music",
                           isOn: macFoldersBinding)
                    Text("Android apps see these as their own Download, Documents, Pictures, "
                       + "Movies and Music folders. Turning this off takes effect the next "
                       + "time each app opens; your files stay in your Mac folders.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                if model.devMenuRevealed {
                    Section("App files") {
                        // The path itself is deliberately NOT rendered here
                        // (owner ask): a raw filesystem path in a settings row
                        // is not how Mac apps surface a storage location, and it
                        // was the widest thing in the sheet. It is still one
                        // hover away in the tooltip and one click away in Finder.
                        HStack(alignment: .firstTextBaseline) {
                            Text("Location")
                                .help(model.androidRoot)
                            Spacer()
                            Button("Change…") { pickAndroidRoot() }
                            Button {
                                revealAndroidRoot()
                            } label: {
                                Image(systemName: "arrow.up.right.square")
                            }
                            .help("Reveal this folder in Finder.")
                            .disabled(model.androidRoot.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                        Text("Where each installed app's files live. Save files persist here between runs.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Section("Advanced") {
                        Picker("Screen size", selection: $model.selectedPreset) {
                            ForEach(kPhonePresets) { preset in
                                Text(preset.label).tag(preset)
                            }
                        }
                        .help("Starting canvas size, for runs started from THIS window only "
                            + "(not saved). Installed apps opened from the Dock, Launchpad "
                            + "or Finder size themselves, and every app window can be "
                            + "resized like any Mac window.")
                        Picker("Orientation", selection: $model.selectedOrientation) {
                            ForEach(LauncherOrientation.allCases) { o in
                                Text(o.label).tag(o)
                            }
                        }
                        .help(model.selectedOrientation.help
                            + " For runs started from THIS window only (not saved); "
                            + "installed apps follow their manifest, and View > Rotate "
                            + "(Cmd+R) turns any app window.")
                        Toggle("Show live log panel (verbose)", isOn: $model.verbose)
                            .help("Show the runtime log in the main window and pass --verbose to the runtime.")
                        Toggle("Write log to folder", isOn: $model.fileLogEnabled)
                            .help("Mirror this session's stderr to logs/macapk_launcher.log.")
                        Toggle("Force ARM32", isOn: $model.forceArm32)
                            .help("MACAPK_FORCE_ABI=armeabi-v7a -- pick the ARM32 lib even when an arm64 copy is present.")
                        Toggle("Show app versions in Applications", isOn: $model.bundleNameShowsVersion)
                            .help("Name generated bundles \"F-Droid 1.23.2.app\" instead of \"F-Droid.app\". "
                                + "Off by default: Finder and Launchpad show the bundle's filename, so the "
                                + "version leaks into the user's Applications folder. Versions still get "
                                + "SEPARATE bundles either way -- this only changes how they are named. "
                                + "Applies to bundles generated from now on, not existing ones.")
                        Toggle("Verbose trace (very slow)", isOn: $model.verboseDevTrace)
                        Toggle("Verbose logging (all classloads)", isOn: $model.verboseLogging)
                            .help("MACAPK_DEV_TRACE=1 -- all diagnostic instrumentation. ~1 fps for HCR-class games.")
                        Button("Uninstall all apps…", role: .destructive) {
                            confirmClearArm32Cache()
                        }
                        .disabled(model.isRunning)
                        .help(arm32CacheHelpText())
                        Text("build \(BuildInfo.id)")
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                    Section("Default environment") {
                        EnvEditor(env: $model.defaultEnv,
                                  caption: "Applied to EVERY app you launch. "
                                    + "A per-app override (Get Info… on a tile) "
                                    + "wins over anything set here.")
                    }
                }
            }
            .formStyle(.grouped)
        }
        .frame(width: 480, height: model.devMenuRevealed ? 760 : 340)
        .onAppear {
            defaultHandlerFeedback = nil
            refreshDefaultHandlerSummary()
        }
    }

    // MARK: Default-handler affordance
    //
    // LSHandlerRank Default in Info.plist claims the types, but it does NOT
    // displace a handler the user previously chose (nor one another app
    // claimed first) -- LaunchServices keeps the user's choice. So there has
    // to be an explicit button.
    //
    // API: NSWorkspace.setDefaultApplication(at:toOpen:completionHandler:),
    // which is the Swift import of
    //   -[NSWorkspace setDefaultApplicationAtURL:toOpenContentType:completionHandler:]
    // (MacOSX26.5.sdk AppKit/NSWorkspace.h, macOS 12.0+). Explicitly NOT
    // LSSetDefaultRoleHandlerForContentType, which the SDK marks
    // API_DEPRECATED pointing at exactly this replacement.
    //
    // The system may ask the user to approve the change; the header says so
    // ("Some types require user consent ... the system will ask the user
    // asynchronously before invoking the completion handler"), which is why
    // this is completion-based and why the summary is re-read afterwards
    // rather than assumed.

    // ⚠ WHICH TYPES (fixed 2026-10-04, Mac.apk-Releases issue 1). This used to
    // set the default for our OWN five UTIs only. On a Mac where another app
    // declares a type for the .apk extension, LaunchServices can make THAT
    // type canonical for the extension (measured: org.polyrun.apk on the dev
    // machine), so a double-clicked .apk carries the foreign UTI and our
    // default for org.macapk.apk is never consulted. Reported on macOS 27: the
    // files kept opening in Keka and Mac.apk was missing from Open With, which
    // is only possible when the file's type is not one we declare. The button reported success and nothing changed, while Get
    // Info > "Change All..." (which sets the default for the file's REAL type)
    // worked. So the list below adds every type each extension resolves to on
    // THIS Mac, and the summary is computed per EXTENSION, which is what a
    // Finder double-click actually looks up. Setting the default for a foreign
    // type is safe because Info.plist claims all five extensions in an
    // extension-only document type: we are a legitimate handler for it.

    /// Our five declared UTIs, plus every type the supported extensions
    /// resolve to on this Mac (another app's UTI, or a dynamic `dyn.` type).
    /// `UTType.types(tag:tagClass:conformingTo:)` returns only types that
    /// carry the extension tag, so this can never pick up a broad type such
    /// as public.zip-archive and hijack every zip on the machine.
    private var handledContentTypes: [UTType] {
        var seen = Set<String>()
        var out: [UTType] = []
        func add(_ t: UTType?) {
            guard let t = t, !seen.contains(t.identifier) else { return }
            seen.insert(t.identifier)
            out.append(t)
        }
        for id in ["org.macapk.apk", "org.macapk.apkm", "org.macapk.xapk",
                   "org.macapk.apks", "org.macapk.apkx"] {
            add(UTType(id))
        }
        for ext in kSupportedApkExtensions.sorted() {
            add(UTType(filenameExtension: ext))
            for t in UTType.types(tag: ext, tagClass: .filenameExtension,
                                  conformingTo: nil) {
                add(t)
            }
        }
        return out
    }

    /// Re-reads who opens each extension, updates the summary line and the
    /// Default check, and returns how many extensions Mac.apk opens.
    @discardableResult
    private func refreshDefaultHandlerSummary() -> (mine: Int, total: Int) {
        // Per extension: the type a file with that extension is given on this
        // Mac (the preferred type for the tag), and who opens it. That is the
        // question a Finder double-click asks, unlike "who opens org.macapk.apk",
        // which can be answered "Mac.apk" while every .apk opens in Keka.
        let exts = kSupportedApkExtensions.sorted()
        let me = Bundle.main.bundleURL.resolvingSymlinksInPath()
        var mine = 0
        var others: Set<String> = []
        for ext in exts {
            guard let t = UTType(filenameExtension: ext),
                  let handler = NSWorkspace.shared.urlForApplication(toOpen: t) else { continue }
            if handler.resolvingSymlinksInPath() == me {
                mine += 1
            } else {
                others.insert(FileManager.default.displayName(atPath: handler.path))
            }
        }
        macApkIsDefaultHandler = (mine == exts.count)
        if mine == exts.count {
            defaultHandlerSummary = "Mac.apk opens all \(exts.count) file types."
        } else if mine == 0 {
            let who = others.sorted().joined(separator: ", ")
            defaultHandlerSummary = who.isEmpty
                ? "No app currently opens these files."
                : "Currently opened by \(who)."
        } else {
            defaultHandlerSummary = "Mac.apk opens \(mine) of \(exts.count) file types."
        }
        return (mine, exts.count)
    }

    private func makeDefaultHandler() {
        let appURL = Bundle.main.bundleURL
        // Make LaunchServices re-read THIS bundle's Info.plist first, so the
        // extension-only document type is registered before we ask to be the
        // default for a foreign type. A copy dragged into /Applications is
        // normally registered at first launch, but an older registration of
        // the same bundle id (a previous version) can still be the one LS
        // holds. Cheap, synchronous, harmless if already current.
        let reg = LSRegisterURL(appURL as CFURL, true)
        if reg != 0 {
            NSLog("[launcher] LSRegisterURL(%@) = %d", appURL.path, reg)
        }
        let types = handledContentTypes
        guard !types.isEmpty else {
            defaultHandlerFeedback = "macOS has not registered Mac.apk's file types yet."
            defaultHandlerFeedbackOK = false
            return
        }
        defaultHandlerFeedback = nil
        settingDefaultHandler = true
        let group = DispatchGroup()
        // Collected off the completion handlers, which land on an arbitrary
        // queue; the box keeps that append off a captured var.
        let failures = HandlerFailureBox()
        for t in types {
            group.enter()
            NSWorkspace.shared.setDefaultApplication(at: appURL, toOpen: t) { error in
                if let e = error {
                    failures.add("\(t.identifier): \(e.localizedDescription)")
                }
                group.leave()
            }
        }
        // The verdict is what LaunchServices reports AFTERWARDS, per
        // extension, not whether the calls returned an error. A clean
        // completion is not proof: that is exactly how the v1.0.1702 button
        // "succeeded" while every .apk kept opening in Keka (Mac.apk-Releases
        // issue 1), and a declined consent prompt would read the same way.
        group.notify(queue: .main) {
            settingDefaultHandler = false
            let r = refreshDefaultHandlerSummary()
            let failed = failures.all()
            if !failed.isEmpty {
                NSLog("[launcher] setDefaultApplication failures: %@",
                      failed.joined(separator: " | "))
            }
            if r.mine == r.total {
                defaultHandlerFeedback = "Done. Double-clicking an Android app file "
                    + "in Finder now opens Mac.apk."
                defaultHandlerFeedbackOK = true
                model.status = "Mac.apk now opens Android app files."
                model.statusKind = .success
            } else {
                let why = failed.first.map { " (\($0))" } ?? ""
                defaultHandlerFeedback = "macOS did not apply the change: Mac.apk opens "
                    + "\(r.mine) of \(r.total) file types\(why). If macOS asked, choose "
                    + "Mac.apk. Otherwise select a .apk in Finder, press Cmd+I and use "
                    + "Open with > Change All."
                defaultHandlerFeedbackOK = false
                model.status = "Couldn't make Mac.apk the default for Android app files."
                model.statusKind = .error
            }
        }
    }

    /// Shorten a home-relative path for display (e.g. ~/Documents/android).
    private func prettyPath(_ path: String) -> String {
        let home = NSHomeDirectory()
        if path.hasPrefix(home) {
            return "~" + path.dropFirst(home.count)
        }
        return path
    }

    /// Help text shown when the runtime binary can't be found on disk.
    private var missingRuntimeHelp: String {
        "macapk-runtime not built yet. Run runtime/scripts/build.sh."
    }

    // MARK: Status bar
    @ViewBuilder
    private var statusBar: some View {
        if model.isRunning {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                // The step label, not a percentage -- see LaunchPhase.
                // A cold first launch spends most of its 30 s in DEX->JAR
                // conversion and asset extraction, and an indeterminate
                // spinner alone cannot be told apart from a hang.
                Text(model.phase.label)
                    .font(.callout)
                    .foregroundColor(.secondary)
                    .help("What the runtime is doing right now. Steps vary by app: a cached app skips conversion, a pure-Java app skips native translation.")
                Spacer()
                // v1.0.466: Stop lives here now (it left the controls row
                // when that row moved into the Settings sheet).
                Button(role: .destructive) {
                    stopRunning()
                } label: {
                    Label("Stop", systemImage: "stop.fill")
                }
                .controlSize(.regular)
            }
        } else if !model.status.isEmpty {
            HStack(spacing: 8) {
                Image(systemName: statusIcon)
                    .foregroundColor(statusColor)
                Text(model.status)
                    .font(.callout)
                    .lineLimit(2)
                Spacer()
            }
        } else {
            // Reserve the space so the layout doesn't jump when status appears.
            Text(" ").font(.callout).hidden()
        }
    }

    // MARK: Unsupported-ABI banner
    //
    // The runtime prints "APK ships NO usable ABI" as a bare fprintf with
    // no severity prefix, so the log panel neither pins nor colors it --
    // and that panel is hidden unless Verbose is on. The result was a
    // silent, load-bearing warning: the app boots with a STUB dylib in
    // place of its native code and then fails later in ways that look
    // nothing like "wrong architecture". Surfacing it as its own banner is
    // the whole point; it is dismissible but never auto-hides.
    @ViewBuilder
    private var unsupportedAbiBanner: some View {
        if let abis = model.unsupportedAbiWarning {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundColor(.orange)
                    .font(.system(size: 14))
                VStack(alignment: .leading, spacing: 2) {
                    Text("This app's native code can't run on Mac.apk")
                        .font(.system(size: 12, weight: .semibold))
                    Text(abis.isEmpty
                         ? "The APK ships no arm64-v8a, armeabi-v7a, or armeabi library. Its native code is replaced by a stub, so the app may start but will misbehave or crash."
                         : "The APK only ships \(abis). Mac.apk runs arm64-v8a, armeabi-v7a, and armeabi, so this app's native code is replaced by a stub: it may start but will misbehave or crash.")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                Button {
                    model.unsupportedAbiWarning = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.borderless)
                .help("Dismiss this warning")
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.orange.opacity(0.12))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.orange.opacity(0.45), lineWidth: 1)
            )
        }
    }

    private var statusIcon: String {
        switch model.statusKind {
        case .info:    return "info.circle"
        case .success: return "checkmark.circle.fill"
        case .error:   return "exclamationmark.triangle.fill"
        }
    }
    private var statusColor: Color {
        switch model.statusKind {
        case .info:    return .secondary
        case .success: return .green
        case .error:   return .red
        }
    }

    // MARK: Inline log panel (verbose mode)
    //
    // We show logs inline in the window rather than popping Terminal.app
    // because a real Mac app should not require a separate terminal window
    // for its primary feedback. Verbose users who want a separate Terminal
    // can run the runtime directly from a shell if they prefer.
    private var logPanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Runtime log")
                    .font(.system(.subheadline, design: .rounded))
                    .fontWeight(.medium)
                Spacer()
                // Copy Logs: writes the entire log buffer to the system
                // pasteboard joined by newlines. Useful for pasting a full
                // run trace into a bug report or chat without having to
                // select-all in the scroll view.
                Button {
                    let text = model.logLines.joined(separator: "\n")
                    let pb = NSPasteboard.general
                    pb.clearContents()
                    pb.setString(text, forType: .string)
                    // Briefly show feedback so the user knows it worked.
                    model.status = "Copied \(model.logLines.count) log lines to clipboard."
                    model.statusKind = .success
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .help("Copy all log lines to clipboard")
                .disabled(model.logLines.isEmpty)

                // Reveal-in-Finder: opens the live mirror file. The
                // launcher writes every log line to logs/macapk_launcher.log
                // unfiltered, so a user can `tail -f` or `grep` the
                // full session even after the rolling tail evicts old
                // entries from the panel.
                Button {
                    NSWorkspace.shared.activateFileViewerSelecting(
                        [URL(fileURLWithPath: model.liveLogPathForDisplay)])
                } label: {
                    Image(systemName: "magnifyingglass.circle")
                }
                .buttonStyle(.borderless)
                .help("Reveal live log file (\(model.liveLogPathForDisplay)) in Finder")

                Button {
                    model.logLines.removeAll()
                    model.nonPinnedCount = 0
                    model.pinnedCount = 0
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .help("Clear log")
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(Array(model.logLines.enumerated()), id: \.offset) { idx, line in
                            Text(line)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundColor(colorForLogLine(line))
                                .id(idx)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(8)
                }
                .frame(height: 200)
                .background(Color(NSColor.textBackgroundColor))
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 6))
                // Auto-scroll to the most recent line as new logs arrive.
                .onChange(of: model.logLines.count) { _, newCount in
                    if newCount > 0 {
                        withAnimation(.linear(duration: 0.05)) {
                            proxy.scrollTo(newCount - 1, anchor: .bottom)
                        }
                    }
                }
            }
        }
    }

    /// Pick a color hint based on log level letter ("E", "W", "I", etc).
    /// Logger writes lines like "12:34:56.789012 E [tag] message" so we
    /// look at the level character.
    private func colorForLogLine(_ line: String) -> Color {
        // Find the level char after the first space.
        if let spaceIdx = line.firstIndex(of: " "),
           line.index(after: spaceIdx) < line.endIndex {
            let levelChar = line[line.index(after: spaceIdx)]
            switch levelChar {
            case "E": return .red
            case "W": return .orange
            case "I": return .primary
            case "D": return .secondary
            case "T": return .secondary.opacity(0.7)
            default:  return .primary
            }
        }
        return .primary
    }

    // MARK: Actions

    private func pickFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        // Accept .apk plus bundle formats. We pass UTTypes for each
        // extension; if a particular OS version doesn't know the type
        // (e.g. .apkm isn't a registered system type), the URL-only
        // fallback still lets the user pick it -- macOS treats
        // unknown content types as "any file."
        var allowed: [UTType] = []
        for ext in kSupportedApkExtensions {
            if let t = UTType(filenameExtension: ext) {
                allowed.append(t)
            }
        }
        panel.allowedContentTypes = allowed
        panel.message = "Pick an APK (.apk) or bundle (.apkm, .xapk, .apks, .apkx) to INSTALL. It'll appear in the installed-apps grid; launch it from there."
        if panel.runModal() == .OK, let url = panel.url {
            // v1.0.464: picking installs (no longer loads-for-run). Not
            // interactive: choosing a file in the panel WAS the confirmation.
            runInstall(LauncherInstallRequest(sourceFiles: [url],
                                              source: .gui,
                                              interactive: false))
        }
    }

    // MARK: The one install funnel
    //
    // Every path that installs an app ends up here: Finder open events, the
    // drop zone, the file picker, the folder batch, and the detail sheet's
    // "Update from APK". Nothing else may call `library.install` directly --
    // that is what made the four entry points drift apart (only two of them
    // reported failures, only two noticed the no-usable-ABI warning, and
    // only Finder skipped confirmation because it had no confirmation code).
    //
    // >>> INTEGRATION SEAM (shared install core) <<<
    // When InstallCore lands, the BODY below becomes one call per file:
    //     InstallPipeline.run(InstallRequest(sourceFile: url,
    //                                        source: <mapped source>,
    //                                        interactive: request.interactive))
    // and the confirm + report halves move behind it. The call SITES do not
    // change, which is the entire point of funnelling them first.

    /// Validate, optionally confirm, then extract-and-register.
    private func runInstall(_ request: LauncherInstallRequest) {
        guard let runtime = model.runtimePath() else {
            model.status = "macapk-runtime not found. Build it with runtime/scripts/build.sh."
            model.statusKind = .error
            return
        }
        // Installing needs the runtime too (the pipeline shells out to it for
        // --extract-only). Say so plainly, because the failure otherwise is
        // deeply confusing: runtimePath() finds the copy inside our own bundle,
        // which uninstall left in place with its EXECUTE BIT CLEARED, so the
        // install dies on a raw EACCES ("you don't have permission to view it")
        // that names a file the user never heard of. The fix is one click and
        // the button is already on screen.
        if !RuntimeInstall.state().installed {
            // v1.0.1018: self-heal instead of sending the user to the control
            // panel. This is the SAME ensureStaged the per-app stub already
            // calls on every launch (AppStubMain.swift), so it is not a new
            // mechanism -- the first-install path was simply the one place that
            // hard-errored instead of using it. Staging is per-user
            // (~/Library/Application Support/Mac.apk/runtime), needs no root,
            // and is an APFS clone of ~15 files, so doing it inline is cheap.
            //
            // It still refuses to undo a DELIBERATE uninstall: ensureStaged
            // returns nil when the runtime-removed marker is present, and we
            // fall through to the original message in that case. So "I removed
            // it on purpose" and "it was never staged" stay distinguishable,
            // which is the whole reason that marker exists.
            model.status = "Preparing the Mac.apk runtime..."
            model.statusKind = .info
            if RuntimeInstall.ensureStaged(log: { model.appendLog($0) }) == nil {
                model.status = "The Mac.apk runtime is not installed, so apps cannot be "
                    + "installed or launched. Choose Runtime: Install (top left) first."
                model.statusKind = .error
                return
            }
        }
        let files = request.sourceFiles.filter {
            kSupportedApkExtensions.contains($0.pathExtension.lowercased())
        }
        guard !files.isEmpty else {
            model.status = "Nothing installable in that selection. "
                + "Mac.apk takes .apk, .apkm, .xapk, .apks, and .apkx."
            model.statusKind = .error
            return
        }

        guard request.interactive else {
            startInstall(files, source: request.source, runtime: runtime)
            return
        }

        // Peek off the main thread (it spawns a subprocess), then confirm on
        // it. Doing the peek inline would freeze the window for as long as
        // the runtime takes to answer, which is exactly the moment the user
        // is waiting to see what they double-clicked.
        model.status = "Reading \(files.count == 1 ? files[0].lastPathComponent : "\(files.count) packages")…"
        model.statusKind = .info
        DispatchQueue.global(qos: .userInitiated).async {
            let peeks = files.map { ApkPeek.detailed($0, runtimePath: runtime) }
            DispatchQueue.main.async {
                guard confirmInstall(peeks: peeks, source: request.source) else {
                    model.status = files.count == 1
                        ? "Install cancelled."
                        : "Install of \(files.count) apps cancelled."
                    model.statusKind = .info
                    return
                }
                startInstall(files, source: request.source, runtime: runtime)
            }
        }
    }

    /// The confirmation Finder installs get. Returns true to proceed.
    ///
    /// Modal on purpose: the alternative is a sheet, and an open event can
    /// arrive while the window is not key (or not yet on screen at all, on a
    /// cold launch), where a sheet has nothing to attach to.
    private func confirmInstall(peeks: [ApkPeek],
                                source: LauncherInstallSource) -> Bool {
        // TEST-ONLY unattended mode, for debug/installtest. A modal alert in a
        // harness that no human is watching is not a safe default -- it just
        // hangs until the harness times out and reports a failure that is
        // really "nobody clicked", which is exactly what it did.
        //
        // ⚠ SCOPED TO NON-ANDROID SOURCES ON PURPOSE. When the deferred
        // F-Droid path lands, an .android request must pass a real Touch ID /
        // password check, and NOTHING here may be able to skip it: a guest app
        // can influence a child process's environment, so an env var must
        // never be sufficient to bypass an authorization step. This skips a
        // CONFIRMATION for a locally-initiated install and nothing else.
        if case .android = source {
            // fall through to the real dialog
        } else if ProcessInfo.processInfo.environment["MACAPK_INSTALL_NOUI"] == "1" {
            NSLog("[install] MACAPK_INSTALL_NOUI=1: proceeding without the confirm dialog")
            return true
        }
        let alert = NSAlert()
        alert.alertStyle = .informational

        if peeks.count == 1 {
            let peek = peeks[0]
            // "Already installed" is decided by PACKAGE when the manifest
            // was readable and by file name when it was not -- never claim
            // an update/downgrade, because versionCode is not available yet
            // (see ApkPeek and the versionCode field on InstalledApp).
            let installed = peek.package.flatMap { pkg in
                library.apps.first { $0.package == pkg }
            }
            if let existing = installed {
                // Classify ONLY when both sides carry a versionCode. A row
                // installed before the runtime wrote versionCode has nil,
                // and versionName is a marketing string with no ordering,
                // so anything else would be a guess presented as a fact.
                switch (existing.versionCode, peek.versionCode) {
                case let (old?, new?) where new > old:
                    alert.messageText = "Update \"\(existing.label)\"?"
                    alert.informativeText = """
                    \(peek.detailLine)

                    This is newer than the installed version (\(old) -> \(new)). \
                    Your saved data is carried forward.
                    """
                case let (old?, new?) where new < old:
                    alert.alertStyle = .warning
                    alert.messageText = "Install an OLDER version of \"\(existing.label)\"?"
                    alert.informativeText = """
                    \(peek.detailLine)

                    This is older than what you have installed (\(old) -> \(new)). \
                    Older versions keep their own separate data, so this app's \
                    current saves will not be visible to it.
                    """
                default:
                    alert.messageText = "Reinstall \"\(existing.label)\"?"
                    alert.informativeText = """
                    \(peek.detailLine)

                    \(existing.label) is already installed\
                    \(existing.version.isEmpty ? "" : " (version \(existing.version))"). \
                    Installing again re-unpacks it from this file. Its saved data is kept.
                    """
                }
            } else {
                alert.messageText = "Install \"\(peek.displayName)\"?"
                alert.informativeText = """
                \(peek.detailLine)

                Mac.apk will unpack this app into your app library so you can \
                launch it from here. It runs inside the Mac.apk Android runtime \
                and does not modify system files.
                """
            }
        } else {
            alert.messageText = "Install \(peeks.count) apps?"
            // Cap the list: an "Open With" on a whole folder can be dozens
            // of files, and an alert that tall is unusable.
            let shown = peeks.prefix(8).map { "  • \($0.displayName)" }
            let rest = peeks.count - shown.count
            alert.informativeText = shown.joined(separator: "\n")
                + (rest > 0 ? "\n  … and \(rest) more" : "")
                + "\n\nEach is unpacked into your app library. Apps already "
                + "installed are re-unpacked and keep their saved data."
        }

        if case .android(let requestor) = source {
            alert.informativeText += "\n\nRequested by \(requestor)."
        }
        alert.addButton(withTitle: peeks.count == 1 ? "Install" : "Install All")
        alert.addButton(withTitle: "Cancel")
        return alert.runModal() == .alertFirstButtonReturn
    }

    /// Run the extractor over `files` and report ONE summary. Shared by every
    /// entry point so a single-file install and a batch report consistently.
    private func startInstall(_ files: [URL],
                              source: LauncherInstallSource,
                              runtime: String) {
        model.status = files.count == 1
            ? "Installing \(files[0].lastPathComponent)…"
            : "Installing \(files.count) apps…"
        model.statusKind = .info
        library.install(apks: files,
                        runtimePath: runtime,
                        androidRoot: model.androidRootURL().path,
                        cacheDir: model.arm32AppCacheDir().path,
                        source: source.recordValue) { outcomes in
            // Completion runs on the main actor once extraction finishes.
            // Report what ACTUALLY happened. The old single-APK path claimed
            // "Installed <name> (or it was already present)" with a green
            // checkmark whenever the app could not be found afterwards,
            // which is exactly the failure case -- a corrupt or unsupported
            // APK reported itself as a successful install.
            guard !outcomes.isEmpty else {
                model.status = "That install produced no result."
                model.statusKind = .error
                return
            }
            // Surface the no-usable-ABI warning from the extract run too:
            // this is where it is FIRST discoverable, well before launch.
            if let abiLine = outcomes.lazy
                .flatMap({ $0.output.split(whereSeparator: \.isNewline) })
                .first(where: { $0.contains(kUnsupportedAbiMarker) }) {
                model.unsupportedAbiWarning =
                    LaunchModel.abiList(fromWarning: String(abiLine))
            }
            let failed = outcomes.filter { !$0.succeeded }
            let okCount = outcomes.count - failed.count
            if failed.isEmpty {
                if outcomes.count == 1, let app = outcomes[0].entry {
                    model.status = "Installed \(app.label). Select it below to launch."
                } else {
                    model.status = "Installed \(okCount) app\(okCount == 1 ? "" : "s")."
                }
                model.statusKind = .success
            } else if let first = failed.first {
                // Name the first failure so there is something to act on;
                // the rest, with their full output tails, are in the system
                // log via NSLog from InstalledLibrary.install.
                let more = failed.count > 1 ? " (+\(failed.count - 1) more)" : ""
                let prefix = outcomes.count == 1
                    ? "Couldn't install \(first.apk.lastPathComponent): "
                    : "Installed \(okCount) of \(outcomes.count). "
                        + "\(first.apk.lastPathComponent) failed: "
                model.status = prefix + first.failureReason + more
                model.statusKind = .error
            }
            if case .finder = source { finderInstallDidFinish(outcomes) }
        }
    }

    /// The Finder double-click contract (owner, 2026-08-22): "double clicking
    /// should be a silent install that opens the app bundle after".
    ///
    /// So a Finder open installs without a confirm sheet, without showing the
    /// control panel, and hands the user the app it produced.
    private func finderInstallDidFinish(_ outcomes: [ExtractOutcome]) {
        let bundles = outcomes.compactMap { o -> URL? in
            guard o.succeeded,
                  let path = o.bundlePath ?? o.entry?.bundlePath, !path.isEmpty
            else { return nil }
            return URL(fileURLWithPath: path)
        }
        for bundle in bundles {
            let cfg = NSWorkspace.OpenConfiguration()
            cfg.activates = true
            NSWorkspace.shared.openApplication(at: bundle, configuration: cfg,
                                               completionHandler: nil)
        }

        // Quit ONLY when this launch existed to service the open event. If the
        // user already had the panel open, closing it under them would be rude
        // -- they get the silent install and the app, and keep their window.
        InstallLog.write("[gui] finder install done: \(bundles.count) bundle(s), "
                       + "launchedForOpen=\(model.launchedForOpen)")
        guard model.launchedForOpen else { return }
        model.launchedForOpen = false

        guard !bundles.isEmpty else {
            // Nothing opened, so something failed. Come back on screen rather
            // than vanishing: a silent failure on a double-click is invisible
            // to the user, which is precisely the trap the install-logging
            // work exists to close. The status line already names the reason.
            NSApp.unhide(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        // Give LaunchServices a moment to take the new app before we go, so
        // the Dock hand-off does not race our termination.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            NSApp.terminate(nil)
        }
    }

    // MARK: Per-app maintenance actions

    /// Re-install this app from a newer APK the user picks.
    ///
    /// This checks the one thing that must be true -- the chosen APK is the
    /// SAME PACKAGE -- and hands the rest to the normal confirmation, which
    /// does the update / reinstall / downgrade classification from
    /// versionCode when both sides have one. Splitting it that way means
    /// the classification is written once and every entry point gets it,
    /// rather than only this menu item.
    private func updateFromApk(_ app: InstalledApp) {
        guard let runtime = model.runtimePath() else {
            model.status = "macapk-runtime not found. Build it with runtime/scripts/build.sh."
            model.statusKind = .error
            return
        }
        let panel = NSOpenPanel()
        panel.title = "Choose a newer APK for \(app.label)"
        panel.message = "Pick the .apk / .apkm / .xapk / .apks / .apkx to install "
            + "over \(app.label) (\(app.package))."
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = kSupportedApkExtensions.compactMap {
            UTType(filenameExtension: $0)
        }
        guard panel.runModal() == .OK, let url = panel.url else { return }

        model.status = "Reading \(url.lastPathComponent)…"
        model.statusKind = .info
        DispatchQueue.global(qos: .userInitiated).async {
            let peek = ApkPeek.detailed(url, runtimePath: runtime)
            DispatchQueue.main.async {
                if let pkg = peek.package, pkg != app.package {
                    let alert = NSAlert()
                    alert.messageText = "That APK is a different app"
                    alert.informativeText = """
                    \(url.lastPathComponent) contains \(pkg), not \
                    \(app.package).

                    Nothing was changed. Pick an APK for \(app.label), or \
                    install this one on its own by dropping it on the window.
                    """
                    alert.alertStyle = .warning
                    alert.runModal()
                    model.status = "Update cancelled: wrong package."
                    model.statusKind = .error
                    return
                }
                // Package matched, or could not be read at all. Either way
                // the normal confirmation is the next step, and it says
                // which of the two it is showing.
                runInstall(LauncherInstallRequest(sourceFiles: [url],
                                                  source: .gui,
                                                  interactive: true))
            }
        }
    }

    /// Delete an app's on-disk tree and re-unpack it from its APK.
    ///
    /// ⚠ THIS DELETES SAVED GAMES. The runtime keeps extracted assets,
    /// translated dylibs, and the app's own save files in ONE directory
    /// (`<android-root>/data/data/<pkg>_<ver>/`), so there is no
    /// caches-only subset to remove -- which is exactly why this is not
    /// called "reset caches" anywhere the user can see. The dialog says it
    /// in the first line, and the action refuses to run at all unless the
    /// source APK is still on disk, because a wipe with nothing to
    /// re-extract from would simply destroy the app.
    private func confirmResetApp(_ app: InstalledApp) {
        guard let apkPath = app.apkPath,
              FileManager.default.fileExists(atPath: apkPath) else {
            let alert = NSAlert()
            alert.messageText = "Can't reset \(app.label)"
            alert.informativeText = """
            Resetting re-unpacks the app from its APK, and that file is no \
            longer at its recorded location\
            \(app.apkPath.map { ":\n\n\($0)" } ?? ".")

            Nothing was changed. Drop the APK on the window to reinstall it.
            """
            alert.alertStyle = .warning
            alert.runModal()
            return
        }
        guard let runtime = model.runtimePath() else { return }

        let size = library.dataDirSizeBytes(for: app)
        let alert = NSAlert()
        alert.messageText = "Reset \(app.label)? This deletes its saved data."
        alert.informativeText = """
        Mac.apk keeps an app's extracted assets, translated code AND its \
        save files in one folder, so a reset cannot spare the saves. \
        Everything \(app.label) has stored (\(ByteCountFormatter.string(
            fromByteCount: size, countStyle: .file))) is deleted, then the app \
        is unpacked again from:

        \(apkPath)

        Use this when an app misbehaves in a way a fresh install would fix.
        """
        alert.alertStyle = .critical
        alert.addButton(withTitle: "Delete and Reinstall")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }

        library.wipeDataDir(for: app)
        startInstall([URL(fileURLWithPath: apkPath)],
                     source: .gui, runtime: runtime)
    }

    /// Resolve the path handed to the runtime. The runtime's apk_unpacker
    /// handles bundles directly (v1.0.152+): for a .apkm / .xapk / .apks it
    /// installs the inner apks as the split set under data/app (v1.0.1585)
    /// and reads them in place (v1.0.1586; it used to merge them into one
    /// `_merged.apk`). So we pass the ORIGINAL file straight through for
    /// every type and let the runtime own bundle handling (single source of
    /// truth).
    ///
    /// Why this matters (v1.0.211): the launcher previously pre-extracted
    /// only `base.apk` from a bundle and launched that. For a split /
    /// multi-arch bundle -- e.g. Crossy Road's "2arch" .apkm -- `base.apk`
    /// carries NO native libraries; the arm64 `libmain.so` / `libunity.so`
    /// / `libil2cpp.so` live entirely in `split_config.arm64_v8a.apk`.
    /// Handed a lib-less base.apk, the runtime found no `libmain.so` to
    /// translate, `findLibrary("main")` fell back to the no-op stub dylib,
    /// Unity's `NativeLoader.load` threw `UnsatisfiedLinkError`, the Java
    /// `UnityPlayer` never constructed, and the title booted to a black
    /// window (while native libunity still ticked at 60fps to a surface
    /// nothing was wired to). Passing the bundle gives the runtime every
    /// split's libs, so the translate cache populates and the title renders.
    private func resolveApkForLaunch(_ path: String) throws -> String {
        return path
    }

    /// Folder picker for the Android filesystem mirror. We start the
    /// panel at the user's currently-configured root (or Documents if
    /// it's empty/missing) so picking a sibling location is one click.
    /// The result is forced to end in "android" so the runtime never
    /// dumps data/data/<pkg>/ into the parent dir directly.
    private func pickAndroidRoot() {
        let panel = NSOpenPanel()
        panel.title = "Choose where Mac.apk keeps the android filesystem mirror"
        panel.message = "Pick any folder. Mac.apk will create (or reuse) a subfolder called 'android' inside it."
        panel.prompt = "Choose"
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        let current = model.androidRoot.trimmingCharacters(in: .whitespacesAndNewlines)
        if !current.isEmpty {
            var seed = current
            if seed.hasPrefix("~/") { seed = NSString(string: seed).expandingTildeInPath }
            let url = URL(fileURLWithPath: seed)
            // Start one level up if the current path already ends in
            // "android" -- picking the same folder is rarely what the
            // user wants when reopening the panel.
            panel.directoryURL = (url.lastPathComponent == "android")
                ? url.deletingLastPathComponent()
                : url
        } else {
            panel.directoryURL = URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Documents")
        }
        if panel.runModal() == .OK, let url = panel.url {
            let chosen = url.lastPathComponent == "android"
                ? url
                : url.appendingPathComponent("android")
            model.androidRoot = chosen.path
        }
    }

    /// Open the configured android root in Finder, creating it first if
    /// the user hasn't run anything yet. Convenient for inspecting save
    /// files, replacing assets manually, etc.
    private func revealAndroidRoot() {
        let raw = model.androidRoot.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return }
        var path = raw
        if path.hasPrefix("~/") { path = NSString(string: path).expandingTildeInPath }
        let url = URL(fileURLWithPath: path)
        try? FileManager.default.createDirectory(
            at: url, withIntermediateDirectories: true)
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    /// Open the launcher's logs directory in Finder. Uses the same
    /// resolution as `logsDirectory()` (project-relative `logs/` if
    /// the .app is in the repo, otherwise `~/Library/Logs/macapk/`)
    /// and creates the folder first if it doesn't exist.
    private func revealLogsFolder() {
        let url = model.logsDirectory()
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    /// The "Use my Mac's folders" switch: ON unless the off file exists. The file
    /// is what the runtime reads (SharedStorage.userFoldersOffFile); writing it
    /// directly keeps one source of truth with nothing to sync.
    private static var macFoldersOffFile: URL {
        Paths.supportDir.appendingPathComponent("user-folders-off")
    }
    @State private var macFoldersOn: Bool =
        !FileManager.default.fileExists(atPath: macFoldersOffFile.path)
    private var macFoldersBinding: Binding<Bool> {
        Binding(get: { macFoldersOn }, set: { on in
            let f = Self.macFoldersOffFile
            if on {
                try? FileManager.default.removeItem(at: f)
            } else {
                try? FileManager.default.createDirectory(
                    at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
                FileManager.default.createFile(atPath: f.path, contents: Data())
            }
            macFoldersOn = !FileManager.default.fileExists(atPath: f.path)
        })
    }

    /// Opens ~/Library/Application Support/MacApk (AppDataLayout.root) itself,
    /// rather than selecting it in its parent, so the per-app folders are what
    /// Finder shows.
    private func openAppDataFolder() {
        let url = AppDataLayout.root
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        NSWorkspace.shared.open(url)
    }

    /// Translate the launcher's settings (APK path, screen preset,
    /// orientation, verbose) into the argv expected by macapk-runtime.
    private func runtimeArgs() -> [String] {
        let apk = model.apkPath
        // Decide the (width, height) we'll actually pass on the command
        // line. The presets in kPhonePresets are all portrait-shaped
        // (width <= height). When the user picks Landscape we swap so
        // the long side is horizontal; Auto/Portrait keep the preset's
        // natural shape and let the runtime's --orientation flag decide
        // how to interpret it.
        //
        // If the preset is the "Auto" sentinel (width=0), we OMIT the
        // size args: the RUNTIME computes the biggest canvas the
        // display supports for the RESOLVED orientation
        // (cocoa_bridge.mm::CocoaSetPhoneSizeMax). It must be the
        // runtime's job because with Orientation = Auto only the
        // runtime knows the manifest's answer -- v1.0.344 computed the
        // size HERE and passed explicit (portrait-shaped) dimensions,
        // which main.cpp honors literally, so landscape-manifest games
        // (Among Us, Plague Inc) booted in portrait windows. Fixed in
        // v1.0.345 by moving the computation runtime-side.
        let preset = model.selectedPreset
        let landscape = (model.selectedOrientation == .landscape)

        var args: [String] = ["--apk", apk,
                              "--orientation", model.selectedOrientation.rawValue]
        if !preset.isAuto {
            let baseW = preset.width
            let baseH = preset.height
            let w = landscape ? max(baseW, baseH) : baseW
            let h = landscape ? min(baseW, baseH) : baseH
            args += ["--width", String(w), "--height", String(h)]
        }
        if model.verbose { args.append("--verbose") }
        // A bundle-installed app is launched against ITS OWN bundle, exactly as
        // its stub does. Falling back to the shared root here is what split one
        // app across two data homes.
        if let bundleRoot = model.selectedAppAndroidRoot {
            args += ["--android-root", bundleRoot.path]
        } else {
            let trimmed = model.androidRoot
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                args += ["--android-root", normalizedAndroidRoot(trimmed)]
            }
        }
        return args
    }

    /// Make sure the path the user typed (or picked via NSOpenPanel)
    /// ends in an "android" folder. If they hand us "~/Documents" we
    /// extend to "~/Documents/android"; if they already chose
    /// ".../android" we leave it alone. Also expands a leading "~/".
    private func normalizedAndroidRoot(_ raw: String) -> String {
        var path = raw
        if path.hasPrefix("~/") {
            path = NSString(string: path).expandingTildeInPath
        }
        // Strip trailing slashes for the lastPathComponent check.
        while path.count > 1 && path.hasSuffix("/") {
            path.removeLast()
        }
        let url = URL(fileURLWithPath: path)
        if url.lastPathComponent != "android" {
            return url.appendingPathComponent("android").path
        }
        return url.path
    }

    /// Resolve the directory the runtime should use for cached
    /// classes.jar / apktool / translated-dylib output (one subdirectory
    /// per package, see `runtime/src/apk_unpacker.cpp`).
    ///
    /// v1.0.64 layout: instead of nesting the cache inside
    /// `runtime/build/var/apps/` (which `rebuild_and_run.sh` wipes
    /// wholesale every iteration), we want the cache to live at
    /// `<repo>/cache/apps/` so it survives rebuilds. The "Clear
    /// ARM32 app cache" button in the controls section deletes its
    /// contents on demand. The runtime is also able to use the
    /// fallback `~/Library/Caches/macapk/apps/` when running from
    /// a bundled `.app` that has no sibling repo (see the matching
    /// MACAPK_APP_CACHE_DIR default-resolution logic in
    /// `runtime/src/main.cpp`); the launcher mirrors that policy
    /// here so both halves agree on the location.
    ///
    /// Resolution order:
    ///   1. Dev-tree mode -- runtime binary lives under
    ///      `<repo>/runtime/build/bin/macapk-runtime`. Walk three
    ///      `..` segments from the binary to reach the repo root.
    ///   2. Bundled `.app` mode -- runtime binary is in
    ///      `Contents/Resources/`; the bundle has no sibling repo.
    ///      Fall back to `~/Library/Caches/macapk/apps/`.
    ///   3. If we couldn't find the runtime at all, return the
    ///      Library/Caches fallback so the env var is still valid.
    /// Tooltip for the destructive "Uninstall all apps…" button.
    ///
    /// ⚠ The name `arm32CacheHelpText` and the old wording are historical
    /// and were actively misleading: this button does NOT delete a cache.
    /// Cache and USER DATA live in the same tree
    /// (`<android-root>/data/data/<pkg>_<ver>/` holds extracted assets,
    /// translated dylibs AND every save file), so "clear the cache" here
    /// means "delete every app and every save". The confirm dialog has
    /// always said so; the tooltip -- the thing you read BEFORE clicking --
    /// did not, and described only the regenerable half.
    private func arm32CacheHelpText() -> String {
        let dir = model.arm32AppCacheDir()
        let bytes = directorySizeBytes(at: dir)
        let sizeStr: String
        if bytes == 0 {
            sizeStr = "empty"
        } else {
            sizeStr = ByteCountFormatter.string(
                fromByteCount: bytes, countStyle: .file)
        }
        // ⚠ This text used to say assets, dylibs and saves "share one folder
        // per app". That stopped being true with the code/data split: the
        // regenerable half now lives under <root>/.macapk/code/<dir> and the
        // saves under <root>/data/data/<dir>. This button still deletes the
        // DATA half, so the warning must stay just as loud -- but it must not
        // keep justifying itself with a layout that no longer exists.
        return """
        Uninstall EVERY app and delete everything under \(dir.path) -- \
        settings and SAVE FILES included. Currently \(sizeStr). To remove a \
        single app, right-click its tile instead; to force apps to rebuild \
        their caches without touching saves, run \
        scripts/rebuild_and_run.sh --fresh-caches.
        """
    }

    /// Shows a confirm sheet before nuking the cache. The runtime
    /// must be stopped first (the disabled-while-running rule on
    /// the button already enforces this; the alert is belt-and-
    /// suspenders for users who somehow trigger it via keyboard
    /// shortcut or accessibility tools).
    private func confirmClearArm32Cache() {
        let dir = model.arm32AppCacheDir()
        let bytes = directorySizeBytes(at: dir)
        let sizeStr = bytes == 0
            ? "empty"
            : ByteCountFormatter.string(
                fromByteCount: bytes, countStyle: .file)

        let alert = NSAlert()
        alert.messageText = "Clear All Apps?"
        alert.informativeText = """
        This uninstalls EVERY app and deletes everything under
        \(dir.path)

        Currently \(sizeStr). Save files, settings, translated dylibs, \
        meta.json, icon.png, and extracted assets ALL go with it.

        Use "Uninstall" from a tile's right-click menu to remove just \
        one app. This button is the nuclear option.
        """
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Clear All")
        alert.addButton(withTitle: "Cancel")
        let response = alert.runModal()
        guard response == .alertFirstButtonReturn else { return }

        let freed = clearArm32Cache(at: dir)
        // v1.0.464: also empty the library so the grid reflects the wipe.
        library.clearAll()
        let freedStr = ByteCountFormatter.string(
            fromByteCount: freed, countStyle: .file)
        model.status = "Cleared \(freedStr); all apps uninstalled."
        model.statusKind = .success
    }

    /// Walk every regular file under `dir` and sum the sizes. Returns
    /// 0 if the directory doesn't exist (e.g. fresh checkout that
    /// hasn't run anything yet). Used by `arm32CacheHelpText()` and
    /// the confirm dialog so the user can see how much they're about
    /// to delete.
    private func directorySizeBytes(at dir: URL) -> Int64 {
        let fm = FileManager.default
        guard fm.fileExists(atPath: dir.path) else { return 0 }
        guard let enumerator = fm.enumerator(
            at: dir,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            let values = try? url.resourceValues(
                forKeys: [.fileSizeKey, .isRegularFileKey])
            if values?.isRegularFile == true,
               let size = values?.fileSize {
                total += Int64(size)
            }
        }
        return total
    }

    /// Delete every per-app subdirectory under `dir` (one folder per
    /// package), but keep `dir` itself so subsequent launches don't
    /// re-create it from scratch with potentially-different
    /// permissions. Returns bytes freed for status display.
    @discardableResult
    private func clearArm32Cache(at dir: URL) -> Int64 {
        let fm = FileManager.default
        guard fm.fileExists(atPath: dir.path) else { return 0 }
        let before = directorySizeBytes(at: dir)
        // Enumerate the immediate children only (one per package);
        // deleting each subtree is faster than a recursive walk and
        // lets us continue past per-entry failures.
        if let entries = try? fm.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]) {
            for entry in entries {
                try? fm.removeItem(at: entry)
            }
        }
        return before
    }

    /// Build (or refresh) a tiny synthetic "<label>.app"-shaped wrapper under
    /// Application Support and return the path INSIDE it to exec instead of
    /// the runtime's real path, or `runtimePath` unchanged if the wrapper
    /// can't be built.
    ///
    /// WHY THIS EXISTS. launch() spawns the runtime with Process() /
    /// executableURL = runtimePath directly, with NO enclosing .app bundle
    /// at all -- unlike an installed app double-clicked from the Dock, which
    /// runs through the per-app stub's execv (AppStubMain.swift) and
    /// inherits THAT process's own LaunchServices spawn-time registration.
    /// CFBundleGetMainBundle() / ProcessInfo.processName / the parts of
    /// NSRunningApplication that are NOT backed by that spawn-time
    /// registration -- which is what the running app's OWN menu-bar title
    /// reads once it calls NSApplication.shared -- resolve from
    /// _NSGetExecutablePath()'s raw invoked path, and a bare Process() spawn
    /// has nothing there to resolve. Confirmed directly: a runtime spawned
    /// this exact way shows "macapk-runtime" as its own bold app-menu title,
    /// not the app being run, even though its Dock ICON is already correct
    /// (MACAPK_APP_ICON, set unconditionally below, is a separate mechanism
    /// that does not depend on bundle identity).
    ///
    /// Fix, matching AppStubMain.swift's mechanism exactly (verified there
    /// empirically against this macOS version, not assumed): exec through a
    /// symlink placed inside a synthesized bundle, so Bundle.main resolves
    /// CFBundleName/CFBundleDisplayName from ITS Info.plist. Rewritten on
    /// every call (one plist write + one symlink, self-healing rather than
    /// only correct until the label next changes) so it always names the
    /// CURRENT app -- only one launch from this panel runs at a time, so one
    /// reused wrapper location is enough; it does not need to be per-app.
    private func makeRunWrapperExecPath(runtimePath: String, label: String) -> String {
        let fm = FileManager.default
        // PER-APP wrapper dir, not one shared MacApkRun.app: the Info.plist
        // below carries THIS app's name, so a single shared wrapper would let a
        // second launch rewrite the plist out from under a first one that is
        // still starting up, handing it the wrong name. Keying the directory by
        // label makes concurrent launches independent -- and the launcher's
        // Installed-Apps panel makes concurrent launches easy to trigger.
        // "/" is the only byte illegal in a POSIX filename, so fold it out of
        // both the directory name and the link name.
        let safeLabel = (label.isEmpty ? "Mac.apk App" : label)
            .replacingOccurrences(of: "/", with: "-")
        let wrapper = Paths.supportDir
            .appendingPathComponent("run-wrapper", isDirectory: true)
            .appendingPathComponent("\(safeLabel).app", isDirectory: true)
        let contentsDir = wrapper.appendingPathComponent("Contents", isDirectory: true)
        let macOSDir = contentsDir.appendingPathComponent("MacOS", isDirectory: true)
        // Named after the app, dot-prefixed to stay hidden. ProcessInfo
        // .processName reports this basename, which is what puts the app's name
        // in `ps`, Activity Monitor and every .ips crash report's procName --
        // where previously every app's crash reported "macapk-runtime" and a
        // DiagnosticReports triage pass could not tell which app had died.
        // Verified on this macOS version that a FLAT link inside Contents/MacOS
        // still resolves the bundle (CFBundleName correct); nesting it deeper
        // to avoid the leading dot breaks CFBundle resolution outright.
        let linkName = "." + safeLabel
        let link = macOSDir.appendingPathComponent(linkName)
        let displayLabel = safeLabel
        let plist: [String: Any] = [
            "CFBundleName": displayLabel,
            "CFBundleDisplayName": displayLabel,
            "CFBundleExecutable": linkName,
            "CFBundleIdentifier": "org.macapk.run",
            "CFBundlePackageType": "APPL",
        ]
        do {
            try fm.createDirectory(at: macOSDir, withIntermediateDirectories: true)
            let data = try PropertyListSerialization.data(
                fromPropertyList: plist, format: .xml, options: 0)
            try data.write(to: contentsDir.appendingPathComponent("Info.plist"))
            unlink(link.path)
            guard symlink(runtimePath, link.path) == 0 else { return runtimePath }
            // Same reason as the generated bundles: a quarantined control
            // panel hands its quarantine to everything it writes, and this
            // synthesized wrapper is unsigned.
            Quarantine.clear(at: wrapper)
            return link.path
        } catch {
            return runtimePath
        }
    }

    private func launch() {
        if let bundle = installedBundleForLaunch() {
            launchInstalledBundle(bundle)
            return
        }
        guard let runtimePath = model.runtimePath() else {
            model.status = "macapk-runtime not found. Build it with runtime/scripts/build.sh."
            model.statusKind = .error
            return
        }

        // Resolve the apk path. Both plain .apk and bundle formats
        // (.apkm/.xapk/.apks/.apkx) now pass straight through to the
        // runtime, which merges bundles (base.apk + every split's libs)
        // itself. Any failure here surfaces in the status bar; the
        // launcher bails before flipping isRunning so the UI stays usable.
        let resolved: String
        do {
            resolved = try resolveApkForLaunch(model.apkPath)
        } catch {
            model.status = "Couldn't open this bundle: \(error.localizedDescription)"
            model.statusKind = .error
            return
        }

        model.isRunning = true
        model.status = ""
        // Fresh run: the step label starts at "Starting..." and the
        // no-usable-ABI banner clears, so it can only be showing this
        // run's verdict (the runtime re-prints it every launch).
        model.resetPhase(to: .preparing)
        model.unsupportedAbiWarning = nil
        model.logLines.removeAll()
        // Reset incremental log counts for the new run.
        model.nonPinnedCount = 0
        model.pinnedCount = 0
        // Archive the previous run, prune old archives, then truncate
        // logs/macapk_launcher.log so the user's `tail -f` / `grep`
        // against the live file always reflects this session.
        model.resetLiveLog()

        // runtimeArgs() reads model.apkPath; for the actual subprocess
        // we substitute the resolved path so a .apkm/.xapk bundle
        // hands the runtime a real .apk on disk. The display path
        // stays in the drop zone so the user sees what they picked.
        var args = runtimeArgs()
        if let idx = args.firstIndex(of: "--apk"), idx + 1 < args.count {
            args[idx + 1] = resolved
        }

        // v1.0.73: write meta.json into cache/apps/<dirname>/ so the
        // Installed Apps panel can find the original APK path on the
        // next launcher startup. We use the DISPLAY path (model.apkPath)
        // not the resolved path, so .apkm/.xapk bundles re-extract on
        // future runs (the resolved base.apk lives in a tmp dir that
        // gets cleaned up after the process exits). Per-run; idempotent.
        writeAppMeta(originalApk: model.apkPath)

        // See makeRunWrapperExecPath's header comment: exec through a
        // synthetic bundle-relative symlink so the Dock/Cmd-Tab/menu-bar
        // identity resolves to this app's own label instead of falling
        // back to the bare runtime binary's name.
        let runtimeExecPath = makeRunWrapperExecPath(
            runtimePath: runtimePath, label: model.appLabel)

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: runtimeExecPath)
        proc.arguments = args

        // Inherit the launcher's environment so the runtime keeps the
        // usual PATH / HOME / DYLD_* values, then layer dev-menu
        // overrides on top. v1.0.64: also always set
        // MACAPK_APP_CACHE_DIR so the cache lives outside
        // `runtime/build/var/apps/` (which `rebuild_and_run.sh`
        // wipes wholesale) -- the runtime's apk_unpacker uses
        // that env var as authoritative when set. The same path is
        // used by the "Uninstall all apps..." button below.
        //
        // Dev-menu overrides (Force ARM32, Verbose Trace) are gated
        // on devMenuRevealed (5-tap title) so casual users never
        // see them, but the cache-dir setting applies to every run.
        var env = ProcessInfo.processInfo.environment
        // Same rule as --android-root above: follow the bundle when there is
        // one, so install-time and launch-time agree about where code lives.
        env["MACAPK_APP_CACHE_DIR"] = model.selectedAppAndroidRoot
            .map { $0.appendingPathComponent(".macapk/code").path }
            ?? model.arm32AppCacheDir().path
        // TEMPORARY (v1.0.335 networking debug): capture il2cpp exception type +
        // message + a native libil2cpp fp-backtrace for NullReference-class
        // throws, so the Among Us online spawn-deserialization NRE can be
        // localized to a C# method (the managed-stack API is dead on this
        // release build). Low overhead; remove once the online kick is fixed.
        // (The Among Us online-kick fix is now permanent in the runtime: the
        // contract-correct gettimeofday tv_usec is unconditional and the
        // Firebase NetworkInfo.getSubtype() gap that made a correct clock crash
        // Crossy is fixed. The il2cpp exception logger + native-stack capture
        // remain in the runtime gated behind MACAPK_IL2CPP_EXC_LOG /
        // MACAPK_EXC_NATIVE_STACK for on-demand debugging -- not forced on here.)
        // Dock icon + window title policy.
        //
        // History: v1.0.128 hid the runtime's own Dock icon so the user
        // saw only ONE entry ("Mac.apk"); v1.0.339/340 flipped that and
        // had the launcher CEDE its Dock (.accessory) so the running app
        // took over the single slot.
        //
        // v1.0.342: the launcher and the runtime each keep their OWN Dock
        // icon -- they no longer share one slot. The launcher stays
        // .regular (its AppIcon.icns Mac.apk tile is always in the Dock)
        // and the runtime shows its own separate entry: the app's own icon
        // (`MACAPK_APP_ICON`) when the drawer record has one, otherwise the
        // bundled Mac.apk default (`MACAPK_DEFAULT_ICON`), so a launched
        // app is never blank/generic. We do NOT touch the launcher's
        // activation policy here (and the terminationHandler no longer
        // restores it), so there is nothing that swaps the two icons in
        // and out of a shared Dock space.
        let haveAppIcon = !model.appIconPath.isEmpty
            && FileManager.default.fileExists(atPath: model.appIconPath)
        if haveAppIcon {
            env["MACAPK_APP_ICON"] = model.appIconPath
        }
        // The Mac.apk default icon ships in the app bundle next to the
        // runtime (Resources/macapk_default_icon.png). Hand its path to the
        // runtime as the fallback for apps with no icon of their own.
        let defaultIconPath = Bundle.main.path(forResource: "macapk_default_icon",
                                               ofType: "png")
        if let defIcon = defaultIconPath {
            env["MACAPK_DEFAULT_ICON"] = defIcon
        }
        if !model.appLabel.isEmpty {
            env["MACAPK_APP_LABEL"] = model.appLabel
        }
        if model.devMenuRevealed {
            if model.forceArm32 {
                env["MACAPK_FORCE_ABI"] = "armeabi-v7a"
            }
            if model.verboseDevTrace {
                // Master switch read by every trace function in
                // bionic_shim.cpp. Also propagate as the specific
                // env vars so individual code paths that check them
                // directly also fire.
                env["MACAPK_DEV_TRACE"]         = "1"
                env["MACAPK_TRACE_BL"]          = "1"
                env["MACAPK_TRACE_JNI_HANDLES"] = "1"
            }
        }
        // Quiet-by-default for UI runs -- see verboseLogging. The dev-menu
        // toggle restores full verbosity. Applied unconditionally (not gated on
        // the dev menu) so casual users get the fast, quiet run; a direct CLI
        // run sets nothing and stays verbose.
        //
        // ⚠ THIS USED TO BE `MACAPK_LOG_LEVEL = 5` (WARN+ERROR only) AND THAT
        // WAS THE WRONG INSTRUMENT. The cost it was fighting is real but it is
        // concentrated in ONE source, not spread across the INFO severity:
        // measured on a full two-install Aurora Store session
        // (~/Library/Logs/macapk/app_com.aurora.store_4.8.4.log, 25,949 lines),
        // INFO was 16,733 lines of which 15,977 -- 95.5% -- are the
        // AppClassLoader loadClass/findClass pair. Everything else that the
        // level-5 gate suppressed came to 649 lines for the WHOLE session.
        // Suppressing by SEVERITY to remove one chatty CALL SITE also removed
        // the entire PackageInstaller install lifecycle (createSession ->
        // committed -> staged -> helper: -> status=0 INSTALL_SUCCEEDED, 42 of
        // those 649 lines), which is correctly INFO and must not be relabelled
        // to WARN just to survive a gate. The owner hit exactly that on
        // 2026-08-19: two real installs left NOTHING in the launcher log but a
        // single receiver-crash WARN, so the failure could not be diagnosed
        // from the log at all and was misread as "the install never started".
        //
        // So: gate the SOURCE, keep the severity floor where a real device has
        // it. MACAPK_QUIET_CLASSLOAD is the pre-existing dedicated knob for the
        // classload pair (AppClassLoader.QUIET_CLASSLOAD) and it suppresses at
        // the call site, so it also skips the throwaway string concatenation
        // that the level gate could only drop one layer further down -- i.e.
        // strictly CHEAPER than what it replaces, on the only lines that
        // mattered for speed.
        //
        // Level 4 = INFO is AOSP's OWN default floor, not a Mac.apk invention:
        // android_util_Log.cpp calls __android_log_is_loggable(level, tag,
        // ANDROID_LOG_INFO), i.e. INFO is the default minimum for every tag
        // unless a developer lowers it with log.tag.<TAG>. runtime/java/android/
        // util/Log.java already documents and uses that same INFO default when
        // MACAPK_LOG_LEVEL is unset. Level 5 was quieter than any real device
        // and there is no AOSP mechanism that makes install logging immune to
        // the priority filter, so the faithful fix is the device's own floor
        // rather than a per-tag bypass in our log funnel.
        //
        // Net measured cost of the change: +649 lines on a 3,408-line quiet log
        // for a session containing two installs. Net gain: 100% of the install
        // trail. Both settings stay overridable below -- a user who really
        // wants WARN+ERROR can still type MACAPK_LOG_LEVEL into Settings.
        if !model.verboseLogging {
            env["MACAPK_LOG_LEVEL"]      = "4"
            env["MACAPK_QUIET_CLASSLOAD"] = "1"
        }
        // User-configured environment goes on LAST, so what someone typed
        // into Settings > Default environment or an app's Get Info sheet is
        // what the runtime actually sees. Global first, per-app over it:
        // the more specific setting wins, which is the only ordering that
        // makes a per-app override meaningful.
        for (k, v) in model.defaultEnv { env[k] = v }
        if let app = library.apps.first(where: { $0.id == model.selectedAppId }) {
            for (k, v) in app.env { env[k] = v }
            library.noteLaunched(app)
        }
        proc.environment = env

        // Pipe stderr so we can stream log lines into the log panel in real time.
        let stderrPipe = Pipe()
        let stdoutPipe = Pipe()
        proc.standardError  = stderrPipe
        proc.standardOutput = stdoutPipe

        // Buffer queue serializes access to the partial-line buffers from
        // the readability handler threads.
        let bufferQueue = DispatchQueue(label: "org.macapk.launcher.log")
        var stderrBuffer = Data()
        var stdoutBuffer = Data()
        let drain: (inout Data, Data) -> Void = { buffer, newData in
            buffer.append(newData)
            while let nlIdx = buffer.firstIndex(of: 0x0A) {
                let lineData = buffer.prefix(upTo: nlIdx)
                buffer.removeSubrange(buffer.startIndex...nlIdx)
                if let line = String(data: lineData, encoding: .utf8) {
                    self.model.appendLog(line)
                }
            }
        }

        stderrPipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.isEmpty {
                handle.readabilityHandler = nil
                return
            }
            bufferQueue.async {
                drain(&stderrBuffer, data)
            }
        }
        stdoutPipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.isEmpty {
                handle.readabilityHandler = nil
                return
            }
            bufferQueue.async {
                drain(&stdoutBuffer, data)
            }
        }

        proc.terminationHandler = { p in
            // Flush any remaining buffered partial lines.
            bufferQueue.async {
                if !stderrBuffer.isEmpty,
                   let line = String(data: stderrBuffer, encoding: .utf8) {
                    self.model.appendLog(line)
                }
                if !stdoutBuffer.isEmpty,
                   let line = String(data: stdoutBuffer, encoding: .utf8) {
                    self.model.appendLog(line)
                }
            }
            DispatchQueue.main.async {
                self.model.isRunning = false
                self.model.process = nil
                self.model.resetPhase(to: .idle)
                // icon-lifecycle fan-out lane (2026-07-23), BUG B --
                // "icon disappears after closing the game" / "appears and
                // disappears from the app library". The runtime child
                // process just exited having possibly rewritten icon.png
                // and meta.json OUT OF BAND (Boot.java's writeLauncherIcon
                // re-renders adaptive/vector icons on every real boot, and
                // apk_unpacker.cpp's WriteAppMetadata runs on every launch
                // too) -- but nothing in this class re-syncs `library.apps`
                // with disk after a launch, so InstalledAppsView's grid
                // only shows fresh icon/label state when SOME OTHER,
                // unrelated @Published change happens to force a re-render
                // in the meantime. iconView(_:) re-checks the filesystem
                // live on every render, so the fix is just making sure a
                // render actually happens once the child is done writing:
                // refresh() re-reads installed_apps.json and reassigns the
                // @Published `apps` array, which unconditionally fires a
                // change notification (Combine's @Published does not diff),
                // forcing every grid tile's iconView to re-check icon.png.
                // Cheap (one JSON decode of a small file); safe even when
                // nothing on disk actually changed.
                self.library.refresh()
                // v1.0.342: the launcher no longer cedes its Dock icon
                // while a game runs (the runtime keeps its own separate
                // entry), so there is nothing to restore here.
                if p.terminationStatus == 0 {
                    self.model.status = "Runtime exited cleanly."
                    self.model.statusKind = .success
                } else {
                    self.model.status = "Runtime exited with status \(p.terminationStatus). Toggle Verbose for details."
                    self.model.statusKind = .error
                }
            }
        }

        do {
            try proc.run()
            model.process = proc
            yieldActivationToRuntime(pid: proc.processIdentifier)
        } catch {
            model.isRunning = false
            model.status = "Failed to launch runtime: \(error.localizedDescription)"
            model.statusKind = .error
        }
    }

    /// Hand keyboard focus to the game we just spawned. Since macOS 14
    /// activation is cooperative: the runtime's own activate when its window
    /// appears is honoured only if the app the user is in (this launcher, which
    /// they just clicked) yields to it first (NSApplication.yieldActivation(to:),
    /// AppKit's documented hand-off for one app launching another). Without it
    /// the game window sometimes opened behind as a non-key window, traffic
    /// lights grey and no keyboard input, until the user switched apps and
    /// back (owner, 2026-10-05, Flappy Bird). An app opened from the Dock or
    /// Finder goes through LaunchServices, which grants this itself.
    ///
    /// The runtime has no NSRunningApplication until it has connected to the
    /// window server, so poll for it briefly on the main thread. If the user
    /// moves to another app meanwhile, the yield is simply not used.
    private func yieldActivationToRuntime(pid: pid_t, attempt: Int = 0) {
        if let app = NSRunningApplication(processIdentifier: pid) {
            NSApp.yieldActivation(to: app)
            return
        }
        guard attempt < 150, model.process?.isRunning == true else { return }  // ~15 s
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(100)) {
            self.yieldActivationToRuntime(pid: pid, attempt: attempt + 1)
        }
    }

    private func stopRunning() {
        if let p = model.process, p.isRunning {
            p.terminate()
        }
        if let app = model.runningApp, !app.isTerminated {
            app.terminate()
            // A runtime that does not honour Quit within a few seconds is
            // stopped the way the old child process was.
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                if !app.isTerminated { app.forceTerminate() }
            }
        }
    }

    // MARK: Installed apps open as their own .app (owner, 2026-10-06)

    /// The installed bundle to open for the selected app, or nil to run the
    /// runtime directly (an APK dropped but not installed, or the gate off).
    ///
    /// Owner, 2026-10-06: "the mac.apk launcher should just launch the .app so
    /// they have parity". Spawning the runtime from here gave an app a SECOND
    /// way to run that differed from a Dock / Finder launch in ways that kept
    /// surfacing as bugs: no run lock (every permission dialog refused, and a
    /// second instance on a URL open -- v1.0.1724 patched that one), no
    /// per-app run log, a different environment, a synthetic run-wrapper
    /// bundle instead of the app's own. Opening the bundle through
    /// LaunchServices runs the SAME stub the Dock does, so there is one path.
    /// MACAPK_WINDOW_OPENS_BUNDLE=0 restores the direct spawn.
    private func installedBundleForLaunch() -> URL? {
        if ProcessInfo.processInfo.environment["MACAPK_WINDOW_OPENS_BUNDLE"] == "0" { return nil }
        guard let root = model.selectedAppAndroidRoot else { return nil }
        let bundle = root.deletingLastPathComponent().deletingLastPathComponent()
        guard bundle.pathExtension == "app",
              let info = NSDictionary(contentsOf: bundle.appendingPathComponent("Contents/Info.plist")),
              info["MacapkPackage"] as? String != nil else { return nil }
        return bundle
    }

    private func launchInstalledBundle(_ bundle: URL) {
        let app = library.apps.first(where: { $0.id == model.selectedAppId })
        let label = model.appLabel.isEmpty ? bundle.deletingPathExtension().lastPathComponent
                                           : model.appLabel
        model.isRunning = true
        model.status = ""
        model.resetPhase(to: .preparing)
        model.unsupportedAbiWarning = nil
        model.logLines.removeAll()
        model.nonPinnedCount = 0
        model.pinnedCount = 0
        model.resetLiveLog()

        // What only this window can add: the dev-menu toggles and the
        // environment typed into Settings or the app's Get Info sheet.
        // LaunchServices merges it over the normal launch environment, and
        // the stub passes it on to the runtime. (It does not reach an app
        // that is already running: that one is just brought forward.)
        var env: [String: String] = [:]
        if model.devMenuRevealed {
            if model.forceArm32 { env["MACAPK_FORCE_ABI"] = "armeabi-v7a" }
            if model.verboseDevTrace {
                env["MACAPK_DEV_TRACE"] = "1"
                env["MACAPK_TRACE_BL"] = "1"
                env["MACAPK_TRACE_JNI_HANDLES"] = "1"
            }
        }
        for (k, v) in model.defaultEnv { env[k] = v }
        if let app {
            for (k, v) in app.env { env[k] = v }
            library.noteLaunched(app)
        }
        let cfg = NSWorkspace.OpenConfiguration()
        cfg.environment = env
        cfg.activates = true
        cfg.createsNewApplicationInstance = false

        let started = Date()
        model.appendLog("[launcher] opening \(bundle.path)")
        NSWorkspace.shared.openApplication(at: bundle, configuration: cfg) { running, error in
            DispatchQueue.main.async {
                guard let running, error == nil else {
                    self.model.isRunning = false
                    self.model.resetPhase(to: .idle)
                    self.model.status = "Couldn't open \(label): "
                        + (error?.localizedDescription ?? "no app was started")
                    self.model.statusKind = .error
                    return
                }
                self.model.runningApp = running
                if let dirName = app?.dirName ?? (NSDictionary(contentsOf:
                        bundle.appendingPathComponent("Contents/Info.plist"))?["MacapkDirName"] as? String) {
                    self.tailRunLog(dirName: dirName, since: started)
                }
                self.watchTermination(of: running, label: label)
            }
        }
    }

    /// Stream the app's own run log (written by its stub; MacApkLogDir) into
    /// the log panel, so the window still shows what the runtime prints.
    private func tailRunLog(dirName: String, since started: Date) {
        model.runLogTimer?.cancel()
        let url = MacApkLogDir.url().appendingPathComponent("app_\(dirName).log")
        var offset: UInt64 = 0
        var opened = false
        var partial = Data()
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + .milliseconds(300), repeating: .milliseconds(500))
        timer.setEventHandler { [model] in
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                  let size = (attrs[.size] as? NSNumber)?.uint64Value else { return }
            if !opened {
                // The stub starts a fresh log for this launch; an older file
                // (or an app that was already running) is read from its end.
                let mtime = attrs[.modificationDate] as? Date ?? .distantPast
                offset = mtime >= started.addingTimeInterval(-1) && size < 4 << 20 ? 0 : size
                opened = true
            }
            if size < offset { offset = 0 }   // rotated (MACAPK_LOG_CAP_MB)
            guard size > offset, let h = try? FileHandle(forReadingFrom: url) else { return }
            defer { try? h.close() }
            try? h.seek(toOffset: offset)
            let data = h.readData(ofLength: Int(min(size - offset, 1 << 20)))
            offset += UInt64(data.count)
            partial.append(data)
            while let nl = partial.firstIndex(of: 0x0A) {
                let line = String(decoding: partial[partial.startIndex..<nl], as: UTF8.self)
                partial.removeSubrange(partial.startIndex...nl)
                model.appendLog(line)
            }
        }
        model.runLogTimer = timer
        timer.resume()
    }

    private func watchTermination(of running: NSRunningApplication, label: String) {
        if let old = model.terminationObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(old)
        }
        let pid = running.processIdentifier
        model.terminationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didTerminateApplicationNotification, object: nil, queue: .main
        ) { note in
            guard let gone = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                  gone.processIdentifier == pid else { return }
            // One last read so the final lines land, then stop tailing.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                self.model.runLogTimer?.cancel()
                self.model.runLogTimer = nil
            }
            if let o = self.model.terminationObserver {
                NSWorkspace.shared.notificationCenter.removeObserver(o)
                self.model.terminationObserver = nil
            }
            self.model.runningApp = nil
            self.model.isRunning = false
            self.model.resetPhase(to: .idle)
            Task { @MainActor in self.library.refresh() }
            self.model.status = "\(label) closed."
            self.model.statusKind = .success
        }
        if running.isTerminated {
            // It quit before the observer was in place.
            model.runningApp = nil
            model.isRunning = false
            model.resetPhase(to: .idle)
        }
    }

    /// v1.0.73: write `meta.json` into the cache app dir matching the
    /// just-launched APK. The Installed Apps panel reads this back to
    /// recover the original APK path for re-launch. Best-effort -- if
    /// we can't write (cache dir doesn't exist yet because translation
    /// hasn't finished, no write permission, etc.), we silently no-op
    /// and the app simply won't appear in the panel until next launch.
    ///
    /// The cache app dir name is derived by the runtime from the APK's
    /// package + version (see runtime/src/apk_unpacker.cpp). Rather
    /// than reproduce that logic here, we wait 8 seconds after the
    /// runtime starts (the runtime unpacks early in boot, well within
    /// 8s) then scan cache/apps/ for the freshest dir and drop the
    /// meta.json there. This is racy in the absolute extreme, but
    /// the practical reality is one launch = one new freshest-dir.
    private func writeAppMeta(originalApk: String) {
        // RETIRED (was harmful). This used to wait 8s then write
        // {apkPath, lastLaunched} into the mtime-FRESHEST cache dir --
        // a racy heuristic that (a) clobbered the runtime's authoritative
        // meta.json ({apkPath, label, package}) with a lossier schema and
        // (b) frequently wrote the just-launched app's apkPath into a
        // DIFFERENT app's cache dir (observed: Reddit's apkPath landed in
        // org.fdroid.fdroid_15/meta.json), corrupting the apkPath<->cache
        // mapping so relaunch passed the wrong/stale path. The runtime's
        // apk_unpacker now writes the correct meta.json into the correct
        // versioned cache dir on every launch (cold AND cache-hit), and
        // recovers from a missing/stale APK by matching that recorded
        // apkPath back to the cache. So the launcher no longer writes it.
        _ = originalApk
    }
}

// MARK: - Installed Apps library (v1.0.464)
//
// "Install" = register an APK in a persistent JSON library at
// ~/Library/Application Support/Mac.apk/installed_apps.json. Dropping,
// picking, Open-With'ing, or "Install from folder"-ing an APK runs the
// runtime in --extract-only mode (unpack manifest + icon + assets, no
// JVM, no window) and records {package, label, version, apkPath,
// dirName} in the library. Launching happens from the grid below, never
// from the drop zone.
//
// The library is AUTHORITATIVE (not a live data/data scan). That lets an
// uninstall "remove from the library but keep the app's data dir" -- the
// previous design derived the list from data/data, which made keep-data
// impossible. On first run (no library file yet) we seed it by importing
// any already-extracted app found under the current android-root, so a
// user upgrading from the scan-based launcher keeps their apps.

/// One installed app. Persisted as JSON (Codable). Paths that depend on
/// the android-root (icon, data dir) are resolved live by InstalledLibrary
/// against the CURRENT root, never stored -- the user can move the root.
extension InstalledApp {
    /// Bridge from the on-disk record. Kept next to the struct so a new field
    /// on either side shows up as a compile error here rather than as a value
    /// that silently stops round-tripping.
    init(record r: AppRecord) {
        self.init(package: r.package, label: r.label, version: r.version,
                  apkPath: r.apkPath, dirName: r.dirName,
                  // AppRecord.versionCode is non-optional and uses 0 for
                  // "unknown" (AOSP's own default). The UI distinguishes the
                  // two, so map 0 back to nil rather than showing "version 0".
                  versionCode: r.versionCode == 0 ? nil : r.versionCode,
                  originalApkPath: r.originalApkPath, bundlePath: r.bundlePath,
                  installedAt: r.installedAt, updatedAt: r.updatedAt,
                  lastLaunchedAt: r.lastLaunchedAt, source: r.source,
                  env: r.env, state: r.state)
    }

    /// The APK this row should launch, repair and reinstall from: the bundle's
    /// own `Contents/Resources/base.<ext>` first (the stub's contract, and the
    /// one copy Mac.apk keeps -- there is no retained copy any more), then the
    /// recorded path, then the original the user installed from. Rows written
    /// before the retention removal point `apkPath` into the deleted
    /// Application Support/Mac.apk/apks/, so without this preference every
    /// such row would badge "APK missing" while its app launches fine.
    var resolvedApkPath: String? {
        ApkRetention.apkCandidates(for: asRecord())
            .first { FileManager.default.fileExists(atPath: $0) }
    }

    /// Copy carrying a generated bundle's path. The struct is all-`let` by
    /// design, so this is the only way to record the bundle without making
    /// every field mutable.
    func withBundlePath(_ path: String?) -> InstalledApp {
        InstalledApp(package: package, label: label, version: version,
                     apkPath: apkPath, dirName: dirName, versionCode: versionCode,
                     originalApkPath: originalApkPath, bundlePath: path,
                     installedAt: installedAt, updatedAt: updatedAt,
                     lastLaunchedAt: lastLaunchedAt, source: source,
                     env: env, state: state)
    }

    func asRecord() -> AppRecord {
        AppRecord(package: package, label: label, dirName: dirName,
                  version: version,
                  // The launcher never had a versionName field; the record's
                  // is derived from the dirName suffix, which is what `version`
                  // already is. Writing it keeps the two in step instead of
                  // blanking a field the pipeline populated.
                  versionName: version,
                  versionCode: versionCode ?? 0,
                  apkPath: apkPath, originalApkPath: originalApkPath,
                  bundlePath: bundlePath, installedAt: installedAt,
                  updatedAt: updatedAt, lastLaunchedAt: lastLaunchedAt,
                  // "ok" is this struct's in-memory word for a healthy app; the
                  // registry's is "installed" (AppState.normalized).
                  source: source, env: env, state: AppState.normalized(state))
    }
}

struct InstalledApp: Identifiable, Hashable, Codable {
    var id: String { dirName }      // data/data/<dirName> is unique per install
    let package: String
    let label: String
    let version: String             // from the dirName "<pkg>_<ver>" suffix
    let apkPath: String?            // source APK (nil if it couldn't be recorded)
    let dirName: String             // the data/data subdir the runtime created

    // ---- Fields added for the registry-v2 record shape -------------------
    //
    // Every one is optional or defaulted, and `init(from:)` below decodes
    // them with `decodeIfPresent`. That is load-bearing, not defensive
    // style: the registry on disk right now is the v1 flat array whose rows
    // carry ONLY the five fields above, and after this file's `load()` was
    // made strict a decode failure no longer silently reseeds -- it refuses
    // to load and shows an error. A single non-optional field without a
    // decode default would therefore turn every existing install into a
    // visible "registry unreadable" state on the next launch.

    /// Monotonic version from the manifest, and the only sound comparator
    /// for update-vs-downgrade (versionName is a marketing string and is
    /// not ordered). `nil` for every row installed before the runtime
    /// started writing `versionCode` into meta.json, so a comparison is
    /// only sound when BOTH sides have one.
    let versionCode: Int64?
    /// Where the user's copy of the APK lives, when it differs from
    /// `apkPath` (which is the file we were handed at install time and may
    /// since have been moved or deleted).
    let originalApkPath: String?
    /// Generated per-app macOS .app bundle, when one exists.
    let bundlePath: String?
    let installedAt: Date?
    let updatedAt: Date?
    let lastLaunchedAt: Date?
    /// How this app got here: "gui" (drag-drop / picker / folder batch),
    /// "finder" (double-click / Open With), or an Android requestor's label.
    let source: String
    /// Per-app environment overrides, applied on top of the global defaults
    /// at launch. Empty for every app until the user edits them.
    let env: [String: String]
    /// "ok" | "broken". Set by the reconcile pass; never auto-acted on.
    let state: String

    init(package: String,
         label: String,
         version: String,
         apkPath: String?,
         dirName: String,
         versionCode: Int64? = nil,
         originalApkPath: String? = nil,
         bundlePath: String? = nil,
         installedAt: Date? = nil,
         updatedAt: Date? = nil,
         lastLaunchedAt: Date? = nil,
         source: String = "gui",
         env: [String: String] = [:],
         state: String = "ok") {
        self.package = package
        self.label = label
        self.version = version
        self.apkPath = apkPath
        self.dirName = dirName
        self.versionCode = versionCode
        self.originalApkPath = originalApkPath
        self.bundlePath = bundlePath
        self.installedAt = installedAt
        self.updatedAt = updatedAt
        self.lastLaunchedAt = lastLaunchedAt
        self.source = source
        self.env = env
        self.state = state
    }

    /// Hand-written rather than synthesized because Swift's synthesized
    /// `Codable` does NOT fall back to a property's default value when a key
    /// is absent -- it throws `keyNotFound`. Reading a v1 row would fail on
    /// `source` / `env` / `state` under the synthesized decoder.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        package  = try c.decode(String.self, forKey: .package)
        label    = try c.decode(String.self, forKey: .label)
        version  = try c.decode(String.self, forKey: .version)
        apkPath  = try c.decodeIfPresent(String.self, forKey: .apkPath)
        dirName  = try c.decode(String.self, forKey: .dirName)
        versionCode     = try c.decodeIfPresent(Int64.self,  forKey: .versionCode)
        originalApkPath = try c.decodeIfPresent(String.self, forKey: .originalApkPath)
        bundlePath      = try c.decodeIfPresent(String.self, forKey: .bundlePath)
        installedAt     = try c.decodeIfPresent(Date.self,   forKey: .installedAt)
        updatedAt       = try c.decodeIfPresent(Date.self,   forKey: .updatedAt)
        lastLaunchedAt  = try c.decodeIfPresent(Date.self,   forKey: .lastLaunchedAt)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "gui"
        env    = try c.decodeIfPresent([String: String].self, forKey: .env) ?? [:]
        state  = try c.decodeIfPresent(String.self, forKey: .state) ?? "ok"
    }

    /// Copy with a replaced env map (the record is otherwise immutable, and
    /// keeping it that way means every mutation goes through `upsert`, which
    /// is the only thing that saves).
    func withEnv(_ newEnv: [String: String]) -> InstalledApp {
        InstalledApp(package: package, label: label, version: version,
                     apkPath: apkPath, dirName: dirName,
                     versionCode: versionCode,
                     originalApkPath: originalApkPath,
                     bundlePath: bundlePath,
                     installedAt: installedAt, updatedAt: Date(),
                     lastLaunchedAt: lastLaunchedAt,
                     source: source, env: newEnv, state: state)
    }
}

/// One line into BOTH the unified system log and `<logs>/install.log`.
///
/// Every `[install]` site used to be NSLog-only, which meant the only account
/// of a failed install lived in the unified system log -- not in the folder
/// the GUI's own "Reveal logs folder" button opens. See InstallLog for the
/// measurement that prompted this.
///
/// File scope and no actor isolation on purpose: the install workers run on
/// background queues, four at a time.
func installLog(_ message: String) {
    NSLog("[install] %@", message)
    InstallLog.write("[gui] " + message)
}

/// Result of ONE APK's EXTRACT attempt (the `--extract-only` subprocess).
///
/// Named `ExtractOutcome`, not `InstallOutcome`, on purpose: `InstallOutcome`
/// is the name of the whole-pipeline result type in the shared install core
/// (status / package / label / versionCode / wasUpdate / bundlePath), and two
/// file-scope types with one name in one module is a hard compile error. This
/// type is strictly the narrower thing -- "did the extractor subprocess run
/// and did a library row appear" -- so it keeps a narrower name.
///
/// Why this type exists: `InstalledLibrary.install()` used to send the
/// extractor subprocess's stdout+stderr to /dev/null and never look at
/// `terminationStatus`; its only `catch` fires when `Process.run()` itself
/// throws, which is a "could not exec" failure, not a "the APK is corrupt"
/// failure. The completion handler then reported
/// "Installed <name> (or it was already present)" with a green checkmark
/// whenever `makeEntry` found no matching meta.json -- so dropping a
/// corrupt or unsupported APK told the user it had worked.
///
/// `succeeded` is therefore deliberately strict: the extractor must exit 0
/// AND the app must actually be in the library afterwards.
///
/// Declared at file scope (not nested in the @MainActor class) so the
/// parallel install workers can build one off the main actor.
struct ExtractOutcome {
    let apk: URL
    /// The library entry that resulted, or nil when nothing was registered.
    let entry: InstalledApp?
    /// Extractor exit status; -1 when the process never started.
    let exitStatus: Int32
    /// Tail of the extractor's combined stdout+stderr (capped).
    let output: String
    /// Set when `Process.run()` itself threw.
    let launchError: String?
    /// The bundle this install produced, when it got that far.
    ///
    /// NOT derived from `entry`: the library row is built from the extracted
    /// data dir's meta.json and does not carry a bundlePath yet at this point,
    /// so reading it there silently yields nil and a caller that wants to OPEN
    /// what it just installed gets nothing. The generator already knows this
    /// path, so carry it.
    var bundlePath: String? = nil

    var succeeded: Bool { entry != nil && exitStatus == 0 && launchError == nil }

    /// One line, user-facing, naming what actually went wrong.
    var failureReason: String {
        if let e = launchError { return "couldn't start the installer (\(e))" }
        let detail = ExtractOutcome.diagnosticLine(from: output)
        if exitStatus != 0 {
            return detail.isEmpty
                ? "the installer exited with status \(exitStatus)"
                : "the installer exited with status \(exitStatus). \(detail)"
        }
        // Exit 0 but nothing registered: the extractor ran and decided
        // there was nothing to unpack (not a zip, no manifest, no dex).
        return detail.isEmpty
            ? "nothing was unpacked, so the app was not added"
            : "nothing was unpacked. \(detail)"
    }

    /// Pick the single most useful line out of the extractor's output for
    /// the status bar. Prefers the LAST error-shaped line (the proximate
    /// failure, since later output supersedes earlier), and falls back to
    /// the last non-empty line so the user still gets something concrete.
    static func diagnosticLine(from output: String) -> String {
        let lines = output.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let needles = ["error", "failed", "cannot", "no such", "not a ",
                       "invalid", "unsupported", "exception", "corrupt"]
        let picked = lines.last(where: { line in
            let low = line.lowercased()
            return needles.contains { low.contains($0) }
        }) ?? lines.last
        guard var s = picked else { return "" }
        if s.count > 160 { s = String(s.prefix(157)) + "..." }
        return s
    }
}

/// Thread-safe accumulator for the parallel installers' outcomes. A
/// reference type rather than a captured `var` so the four concurrent
/// workers share one instance without an escaping-mutable-capture.
private final class ExtractOutcomeBox: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [ExtractOutcome] = []
    func add(_ outcome: ExtractOutcome) {
        lock.lock(); items.append(outcome); lock.unlock()
    }
    func all() -> [ExtractOutcome] {
        lock.lock(); defer { lock.unlock() }; return items
    }
}

/// The authoritative, persistent installed-apps library.
@MainActor
final class InstalledLibrary: ObservableObject {
    @Published private(set) var apps: [InstalledApp] = []
    @Published var installProgress: InstallProgress? = nil

    /// Non-nil when the registry file EXISTS but could not be read. The UI
    /// renders this as a banner and every write is refused while it is set.
    ///
    /// This exists because of a data-loss bug, not for tidiness. `load()`
    /// used to be one `if let` chain over
    /// `fileExists && try? Data && try? decode`, whose else-branch was the
    /// FIRST-RUN SEED -- it scanned data/data and `save()`d the result. So
    /// "the file does not exist yet" and "the file exists and did not
    /// decode" took the same path, and a single unreadable/partial/
    /// schema-drifted read silently replaced a 63-entry registry with
    /// whatever happened to be lying under data/data. The three states are
    /// now distinct, and the failure one neither seeds nor saves.
    @Published private(set) var loadError: String? = nil

    /// Per-app on-disk verification from the reconcile pass, keyed by
    /// `dirName`. Surfaced as tile badges; never acted on automatically.
    @Published private(set) var health: [String: AppHealth] = [:]

    /// What the reconcile pass found on disk for one app. All three checks
    /// are "the registry claims X, is X actually there".
    struct AppHealth: Equatable {
        var dataDirMissing: Bool = false
        var apkMissing: Bool = false
        var bundleMissing: Bool = false

        var isBroken: Bool { dataDirMissing || apkMissing || bundleMissing }

        /// One line for a tooltip. Ordered worst-first: a missing data dir
        /// means the app cannot run at all, a missing APK only means it
        /// cannot be re-extracted or updated.
        var summary: String {
            var parts: [String] = []
            if dataDirMissing { parts.append("its installed files are missing") }
            if apkMissing     { parts.append("the source APK is no longer at its recorded path") }
            if bundleMissing  { parts.append("its macOS app was deleted, so it is no longer installed") }
            return parts.isEmpty ? "" : parts.joined(separator: "; ")
        }
    }

    struct InstallProgress: Equatable {
        var completed: Int
        var total: Int
        var current: String   // filename being processed (or "" between)
    }

    /// data/data under the current android-root. Set by the view before
    /// any install via configure(); used to resolve icons + delete data.
    private(set) var dataDataRoot: URL =
        URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent("Library/Caches/MacApk/scratch/android/data/data")

    /// Library JSON file under Application Support. Created on demand.
    private let libraryURL: URL = {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Library/Application Support")
        let dir = base.appendingPathComponent("Mac.apk", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("installed_apps.json")
    }()

    /// Point the library at the active android-root's data/data and load
    /// (or first-run-seed) the persisted list. Idempotent.
    func configure(dataDataRoot: URL) {
        self.dataDataRoot = dataDataRoot
        load()
        reconcile()
    }

    /// Re-read the library file (e.g. after an external change) and re-verify
    /// what it claims against the disk.
    func refresh() { load(); reconcile() }

    /// Encoder/decoder pair for the registry file. They must agree on the
    /// date strategy or dates round-trip wrong, so both are built here and
    /// nowhere else. ISO-8601 rather than the default reference-interval
    /// Double because the registry is a file a human opens to check state.
    private static func makeDecoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }
    private static func makeEncoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return e
    }

    /// Three distinct outcomes, deliberately not two:
    ///   1. no file yet          -> first-run seed from data/data, then save
    ///   2. file reads + decodes -> use it
    ///   3. file exists, does not read/decode -> keep whatever we already
    ///      had, publish `loadError`, and DO NOT WRITE. Seeding here would
    ///      overwrite a registry we simply failed to parse.
    /// Read through `RegistryStore`, which owns the on-disk schema.
    ///
    /// The launcher used to decode the file itself as a bare `[InstalledApp]`
    /// array. That has to stop the moment anything writes schema v2, because
    /// the two readers would disagree about the same path and the grid would
    /// silently show nothing. RegistryStore also migrates v1 -> v2 (taking two
    /// backups first, one of them write-once-ever) and refuses a schema newer
    /// than it understands rather than downgrading it.
    private func load() {
        let store = RegistryStore.shared
        guard store.registryFileExists else {
            // Genuine first run: import every already-extracted app under
            // data/data so an upgrade from the scan-based launcher keeps
            // the user's apps.
            let seeded = Self.importExisting(dataDataRoot: dataDataRoot)
            apps = seeded.sorted { $0.label.lowercased() < $1.label.lowercased() }
            loadError = nil
            // Merge by dirName rather than writing the array wholesale: a
            // concurrent helper install can create the registry between the
            // exists-check above and this write, and its row must survive
            // the seed (audit S2).
            persist { reg in
                for e in seeded
                where !reg.apps.contains(where: { $0.dirName == e.dirName }) {
                    reg.apps.append(e.asRecord())
                }
            }
            return
        }
        do {
            let reg = try store.load()
            apps = reg.apps.map(InstalledApp.init(record:))
                .sorted { $0.label.lowercased() < $1.label.lowercased() }
            loadError = nil
        } catch {
            // Leave `apps` at its last good value (empty on a cold start),
            // leave the file alone, and make the failure impossible to miss.
            loadError = "\(libraryURL.lastPathComponent) could not be read: "
                + "\(error.localizedDescription)"
            NSLog("[library] REFUSING to reseed: %@ exists but did not decode: %@",
                  libraryURL.path, "\(error)")
        }
    }

    /// Writes are refused while `loadError` is set. Without this guard the
    /// data-loss bug just moves one level up: a failed load leaves `apps`
    /// empty or stale in memory, and the next `upsert` / `remove` writes
    /// THAT over the intact file on disk.
    ///
    /// ⚠ INTENT-SCOPED WRITES ONLY (audit S2, 2026-08-11). This used to be
    /// `save()`, which did `reg.apps = self.apps.map { $0.asRecord() }` -- a
    /// WHOLE-ARRAY replace from the launcher's in-memory snapshot. mutate()
    /// re-reads the file under the lock precisely so concurrent writers are
    /// safe, and the wholesale assignment defeated that re-read: any row
    /// written meanwhile by macapk-installhelper or by a per-app stub was
    /// ERASED on the launcher's next save (deterministic -- the snapshot can
    /// be hours stale, since activation only reconciles). Every caller now
    /// states its own edit against the FRESH on-disk registry inside `apply`,
    /// and rows this launcher did not touch are never rewritten.
    private func persist(_ apply: (inout Registry) -> Void) {
        guard loadError == nil else {
            NSLog("[library] refusing to write: registry did not load (%@)",
                  loadError ?? "")
            return
        }
        do {
            // Through RegistryStore, so this write takes the same flock, gets
            // the same atomic replace, and preserves `settings` -- which a
            // whole-file encode of `apps` alone would silently drop, taking
            // androidRoot and every per-app env override with it.
            try RegistryStore.shared.mutate { reg in apply(&reg) }
            // Converge the grid on the post-mutation truth. This is also what
            // makes a helper-installed row APPEAR after any launcher write,
            // instead of only after a restart.
            if let reg = try? RegistryStore.shared.loadReadOnly() {
                apps = reg.apps.map(InstalledApp.init(record:))
                    .sorted { $0.label.lowercased() < $1.label.lowercased() }
            }
        } catch {
            loadError = "Could not save \(libraryURL.lastPathComponent): "
                + "\(error.localizedDescription)"
            NSLog("[library] save FAILED: %@", "\(error)")
        }
    }

    /// The rows that ARE installed -- the only ones the grid lists, and the
    /// same set `MacApkPackageManager` reports to a store. `apps` still holds
    /// every row, because a `removed` row is not junk: it still points at the
    /// retained APK a reinstall would use.
    var installedApps: [InstalledApp] { apps.filter { $0.state != AppState.removed } }

    /// Rows whose macOS bundle is gone. Shown separately so the user can put
    /// the app back (or forget it) instead of it silently vanishing along
    /// with its retained APK.
    var removedApps: [InstalledApp] { apps.filter { $0.state == AppState.removed } }

    /// Set once the legacy Application Support/Mac.apk/apks/ purge has run in
    /// this process; see reconcile().
    private static var purgedLegacyApks = false
    private static var refreshedPurposeStrings = false

    /// Verify what the registry claims against the disk: the app's data dir,
    /// its source APK, and its generated .app bundle if it has one.
    ///
    /// The bundle half is NOT decided here. It is decided once, in
    /// `Registry.reconcileBundlePresence` on `RegistryStore`'s read path, so
    /// this launcher, the install helper, a per-app stub and the guest's
    /// PackageManager cannot disagree -- which they did, because this method
    /// and `InstallPipeline.reconcile` each had their own `fileExists` and
    /// neither changed the row's state. Here we only MIRROR the decision into
    /// a badge, and persist it so the guest's reader (which parses the file
    /// directly and cannot run this code) sees it too.
    ///
    /// Still surfaces-only for everything else: it never deletes a row and
    /// never deletes a file -- a user who moved an APK to another folder, or
    /// has an external volume unmounted, must not have their registry pruned
    /// behind their back. (The one deletion it does make is of OUR legacy
    /// retained-APK copies, never of anything the user placed -- see the
    /// purge at the top.)
    func reconcile() {
        let fm = FileManager.default
        // One-time per process: empty the legacy Application Support/Mac.apk/
        // apks/ directory. Nothing writes it any more (owner: no APK copies,
        // ever) and every row resolves its APK through the bundle first, so
        // the copies there are dead weight -- measured 3.9 GB on this machine
        // before the owner deleted the directory by hand.
        if !Self.purgedLegacyApks {
            Self.purgedLegacyApks = true
            ApkRetention.purgeAll { NSLog("[library] %@", $0) }
        }
        // One-time per process, OFF the main actor: re-stamp any installed
        // bundle whose Info.plist predates a TCC purpose string the generator
        // now writes (InstallPipeline.refreshStalePurposeStrings). Such a bundle
        // is refused the camera/microphone/location/... by macOS with no prompt
        // at all, and an old stub cannot heal itself, so -- like the quarantine
        // self-heal below -- the control panel is the surface that can.
        if !Self.refreshedPurposeStrings {
            Self.refreshedPurposeStrings = true
            DispatchQueue.global(qos: .utility).async {
                var hooks = InstallPipeline.Hooks()
                hooks.log = { NSLog("[library] %@", $0) }
                BundleGenerator.install(into: &hooks)
                InstallPipeline.refreshStalePurposeStrings(hooks: hooks)
            }
        }
        var next: [String: AppHealth] = [:]
        for app in apps {
            var h = AppHealth()
            h.dataDirMissing = !fm.fileExists(atPath: dataDir(for: app).path)
            // Only a RECORDED path can be missing. A nil apkPath is a
            // different problem (nothing was recorded), already reported by
            // selectApp, and flagging it here would badge it twice.
            if let p = app.apkPath, !p.isEmpty {
                h.apkMissing = (app.resolvedApkPath == nil) && !fm.fileExists(atPath: p)
            }
            h.bundleMissing = (app.state == AppState.removed)
            // SELF-HEAL an already-installed bundle that was generated before
            // v1.0.1030, or by a build that lacked the fix. Those bundles are
            // quarantined and unsealed, so LaunchServices refuses them with
            // "the application is damaged" -- and it refuses them BEFORE the
            // app stub runs, so the app cannot heal itself. The control panel
            // is the only surface that can.
            //
            // Root only, not the whole tree: this runs on every refresh, and
            // Contents/android holds an app's entire extracted install, so a
            // recursive walk here would be thousands of syscalls per app per
            // refresh. Gatekeeper reads the attribute on the .app directory
            // itself, which is what makes the shallow clear sufficient.
            if let bp = app.bundlePath, !bp.isEmpty {
                Quarantine.clearShallow(at: URL(fileURLWithPath: bp))
                // A pre-v1.0.1585 bundle's Resources copy is shed by ITS OWN
                // STUB, at the launch where the stub can already run from
                // android/data/app -- never from here. The launcher cannot
                // know which stub a bundle carries (measured 2026-09-19: a
                // launcher-side delete met a bundle whose stub restage had
                // silently skipped it, and that app died with "package file
                // is missing" until the stub was refreshed).
            }
            next[app.dirName] = h
        }
        if next != health { health = next }
        // Persist what the read path already computed. Guarded by the same
        // rule as every other write here: a registry we could not parse is
        // never written back.
        guard loadError == nil else { return }
        // Bounded: this runs on the main actor (activation), and converging
        // the file is a courtesy to the OTHER process's reader -- the answer
        // this launcher shows is already correct in memory. Never hang the UI
        // waiting for a lock a helper install is holding.
        if let moved = try? RegistryStore.shared.reconcileBundles(timeoutMs: 500),
           !moved.isEmpty {
            for m in moved {
                NSLog("[library] %@: %@ -> %@ (%@)", m.package, m.from, m.to, m.detail)
            }
            if let reg = try? RegistryStore.shared.loadReadOnly() {
                apps = reg.apps.map(InstalledApp.init(record:))
                    .sorted { $0.label.lowercased() < $1.label.lowercased() }
            }
        }
        recoverInterruptedInstalls()
    }

    /// Clear rows that a killed install left claiming `installing`.
    ///
    /// WHY THIS EXISTS AT ALL: RegistryStore.recoverInterruptedInstalls has
    /// been in InstallCore since the ledger was written and had ZERO callers,
    /// so nothing in the product ever ran it. That is why the owner's aborted
    /// install on 2026-08-19 left a row stuck at `installing` indefinitely:
    /// `installing` is also specifically exempt from the bundle-presence
    /// reconcile above ("it never touches a row that is `installing`, because
    /// the bundle legitimately may not exist yet"), so no pass at all could
    /// converge it.
    ///
    /// SCOPE, stated precisely because two ledgers got conflated while this was
    /// being diagnosed. This one is OURS, and a guest's PackageManager already
    /// treats `installing` as not-installed
    /// (MacApkPackageManager.InstalledRegistry counts a row only when
    /// state == "installed"), so a stuck row does NOT wedge a store's install
    /// check. What it wedges is THIS UI, where the row reads as perpetually
    /// installing and is never offered for repair or removal; `broken` is
    /// equally not-installed to the guest and is actionable to the user. The
    /// "Aurora says installing forever" symptom lived in Aurora's OWN SQLite
    /// database, which nothing here writes -- that unsticks when the store next
    /// reconciles against PackageManager.
    ///
    /// The primary fix for the abort is elsewhere -- PackageInstallerBackend no
    /// longer hands the helper a pipe that dies with the guest, so an approved
    /// install now finishes on its own and writes its own terminal state. This
    /// is the belt-and-braces for what that cannot cover: a helper that is
    /// SIGKILLed, a power loss, a crash between the row write and the work.
    ///
    /// Two deliberate choices:
    ///
    ///  - THE ONE-HOUR CUTOFF IS THE LIBRARY'S DEFAULT AND IT STAYS. A real
    ///    install can legitimately run for minutes (TikTok's 197 MB bundle
    ///    extracts for ~4.5 min, and before that the helper blocks on a human
    ///    at the Touch ID prompt for as long as that human takes). Anything
    ///    aggressive enough to feel responsive would eventually relabel a
    ///    perfectly healthy in-flight install as `broken`. That deletes nothing
    ///    by itself, but it offers the user a repair/remove affordance for an
    ///    install that is fine, and makes the guest's PackageManager answer
    ///    "not installed" for a package that is about to be -- both worse than
    ///    a stale row that resolves on the next launch.
    ///  - OFF THE MAIN ACTOR, AND ONLY WHEN THERE IS SOMETHING TO DO. The
    ///    recovery takes the registry lock with NO timeout, and the lock is
    ///    held for the whole of an install by a helper in another process --
    ///    calling it inline would hang the UI for the entire duration of any
    ///    install running right now. The pre-check reads the rows this pass
    ///    ALREADY loaded, so the common case (nothing stale) costs one array
    ///    scan and touches no lock at all.
    private func recoverInterruptedInstalls() {
        let cutoff = Date().addingTimeInterval(-3600)
        let stale = apps.contains { app in
            guard app.state == AppState.installing else { return false }
            return (app.updatedAt ?? app.installedAt ?? .distantPast) < cutoff
        }
        guard stale else { return }
        DispatchQueue.global(qos: .utility).async {
            guard let hit = try? RegistryStore.shared.recoverInterruptedInstalls(),
                  !hit.isEmpty else { return }
            NSLog("[library] interrupted install(s) marked broken: %@",
                  hit.joined(separator: ", "))
            Task { @MainActor in self.load() }
        }
    }

    func health(for app: InstalledApp) -> AppHealth {
        health[app.dirName] ?? AppHealth()
    }

    /// The live registry row for `app`, which may have changed since the
    /// caller captured its copy (the detail sheet holds a snapshot).
    func current(_ app: InstalledApp) -> InstalledApp {
        apps.first { $0.dirName == app.dirName } ?? app
    }

    /// A leftover data dir from a PREVIOUS version of the same package.
    struct OrphanDir: Identifiable, Hashable {
        let url: URL
        let sizeBytes: Int64
        var id: String { url.lastPathComponent }
        var name: String { url.lastPathComponent }
    }

    /// Dirs under data/data that belong to this package but not to this
    /// install: `<pkg>_<olderVersion>`. They are pure leftovers -- the
    /// runtime derives the dir name from package + versionName, so an app
    /// that has been updated leaves its previous tree behind, saves and all.
    ///
    /// Ownership is confirmed from each candidate's OWN meta.json, not from
    /// the directory name. The name is ambiguous and this list feeds a
    /// Delete button: `<pkg>_<ver>` cannot be split back apart reliably
    /// when a package name itself contains an underscore followed by a
    /// digit, so `com.foo_2`'s directory looks exactly like an old version
    /// of `com.foo`. The name is used only to cheaply narrow the candidates
    /// before reading; a dir with no meta.json is left out rather than
    /// guessed at.
    ///
    /// Walks the sizes, so call it OFF the main thread: an app with a large
    /// extracted asset tree makes this a multi-GB recursive stat.
    nonisolated func orphanDirs(for app: InstalledApp,
                                dataDataRoot: URL) -> [OrphanDir] {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: dataDataRoot, includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]) else { return [] }
        let prefix = app.package + "_"
        return entries.compactMap { url -> OrphanDir? in
            let name = url.lastPathComponent
            guard name != app.dirName,
                  name.hasPrefix(prefix),
                  !name.contains(".staging."),
                  (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
            else { return nil }
            // v1.0.1641: an in-place update leaves data/data/<oldDirName> as a LINK
            // to the current data dir; it is not an orphan and must never be
            // offered for deletion (its size walk would count the live data).
            if DataCarryForward.isSymlink(url) { return nil }
            // Confirm from the dir's own metadata that it really is this
            // package before offering to delete it.
            let meta = url.appendingPathComponent("meta.json")
            guard let d = try? Data(contentsOf: meta),
                  let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                  let pkg = j["package"] as? String,
                  pkg == app.package
            else { return nil }
            return OrphanDir(url: url,
                             sizeBytes: Self.directorySizeBytes(at: url))
        }.sorted { $0.name < $1.name }
    }

    /// Delete one orphaned version dir. Only ever called from an explicit
    /// per-row button in the detail sheet -- the reconcile pass never does.
    func deleteOrphan(_ orphan: OrphanDir) {
        try? FileManager.default.removeItem(at: orphan.url)
    }

    /// Wipe an app's data dir so the next install re-unpacks it from the
    /// APK. This DELETES SAVED DATA: the runtime keeps extracted assets,
    /// translated dylibs and the app's own save files in one tree, so there
    /// is no "caches only" subset to remove. Callers must say so.
    func wipeDataDir(for app: InstalledApp) {
        // ⚠ An empty dirName would resolve dataDir() to the data/data ROOT
        // (URL.appendingPathComponent("") returns the parent unchanged), and
        // this removeItem would then delete every installed app's data at
        // once. dirName arrives from a JSON file that is shared across builds
        // and is hand-editable, so refuse rather than trust it.
        guard !app.dirName.isEmpty else {
            NSLog("[macapk] refusing to wipe data for a record with an empty dirName (package=\(app.package))")
            return
        }
        try? FileManager.default.removeItem(at: dataDir(for: app))
        reconcile()
    }

    /// Public size helper for views (the static is private).
    nonisolated func sizeOfDirectory(_ url: URL) -> Int64 {
        Self.directorySizeBytes(at: url)
    }

    // MARK: live path resolution (android-root-dependent, not persisted)

    func dataDir(for app: InstalledApp) -> URL {
        // Never let an empty dirName collapse this to dataDataRoot itself.
        // Callers treat the result as one app's directory, and one of them
        // deletes it. A sentinel component keeps a malformed record pointing
        // at something that cannot exist instead of at everything.
        let leaf = app.dirName.isEmpty ? ".invalid-empty-dirname" : app.dirName
        // ⚠ THE BUNDLE IS THE APP'S DATA DIR. Since the v1.0.944-949 cutover a
        // bundle-installed app's data lives at
        // `<bundle>/Contents/android/data/data/<dirName>`, and the shared root
        // holds -- at most -- a same-named tree left by a CLI/fleet run of the
        // same package. This used to return the shared path unconditionally,
        // which made every caller wrong in a different way: the uninstall
        // dialog reported "no data on disk" for an app with saves, "Reveal in
        // Finder" opened the wrong tree or nothing, reconcile flagged
        // dataDirMissing on healthy apps, and both destructive callers (Reset,
        // and remove(deleteData:)) deleted a fleet-harness tree while leaving
        // the app's real saves untouched.
        if let bp = app.bundlePath, !bp.isEmpty {
            let inBundle = URL(fileURLWithPath: bp)
                .appendingPathComponent("Contents/android/data/data")
                .appendingPathComponent(leaf)
            // Fall through to the shared root only when the bundle genuinely
            // has no such tree (a pre-cutover row whose bundlePath was added
            // later), so this can never point a deleter at a path that is not
            // this app's.
            if FileManager.default.fileExists(atPath: inBundle.path) { return inBundle }
            if !FileManager.default.fileExists(
                atPath: dataDataRoot.appendingPathComponent(leaf).path) {
                // Nothing either side: report the bundle path, because that is
                // where this app's data BELONGS and where a caller asking
                // "is it missing?" should be told to look.
                return inBundle
            }
        }
        return dataDataRoot.appendingPathComponent(leaf)
    }
    func iconURL(for app: InstalledApp) -> URL? {
        let p = dataDir(for: app).appendingPathComponent("icon.png")
        return FileManager.default.fileExists(atPath: p.path) ? p : nil
    }
    func dataDirExists(for app: InstalledApp) -> Bool {
        FileManager.default.fileExists(atPath: dataDir(for: app).path)
    }
    func dataDirSizeBytes(for app: InstalledApp) -> Int64 {
        Self.directorySizeBytes(at: dataDir(for: app))
    }

    // MARK: mutation

    private func upsert(_ entry: InstalledApp) {
        persist { reg in
            let rec = entry.asRecord()
            if let idx = reg.apps.firstIndex(where: { $0.dirName == rec.dirName }) {
                // Re-installing an app must not silently discard the fields
                // the USER owns. `entry` is rebuilt from meta.json by
                // makeEntry, which knows nothing about env overrides or when
                // the app first arrived, so a plain overwrite would wipe both
                // every time an app is re-dropped or refreshed by the folder
                // batch. The merge reads the ON-DISK row (not this launcher's
                // snapshot), so a lastLaunchedAt a stub wrote a minute ago
                // survives too.
                let old = reg.apps[idx]
                var merged = rec
                if merged.versionCode == 0 { merged.versionCode = old.versionCode }
                if merged.originalApkPath == nil { merged.originalApkPath = old.originalApkPath }
                if merged.bundlePath == nil { merged.bundlePath = old.bundlePath }
                merged.installedAt = old.installedAt ?? rec.installedAt
                merged.updatedAt = Date()
                merged.lastLaunchedAt = old.lastLaunchedAt
                if merged.env.isEmpty { merged.env = old.env }
                reg.apps[idx] = merged
            } else {
                reg.apps.append(rec)
            }
        }
        reconcile()
    }

    /// Replace one app's environment overrides and persist. Called by the
    /// detail sheet's env editor.
    func setEnv(_ newEnv: [String: String], for app: InstalledApp) {
        persist { reg in
            guard let idx = reg.apps.firstIndex(where: { $0.dirName == app.dirName })
            else { return }
            reg.apps[idx].env = newEnv
            reg.apps[idx].updatedAt = Date()
        }
    }

    /// Stamp `lastLaunchedAt`. Best-effort: a launch is not worth blocking
    /// on a registry write, so a refused write (see `persist()`) just means
    /// the timestamp does not stick.
    func noteLaunched(_ app: InstalledApp) {
        persist { reg in
            guard let idx = reg.apps.firstIndex(where: { $0.dirName == app.dirName })
            else { return }
            reg.apps[idx].lastLaunchedAt = Date()
        }
    }

    /// Remove an app: its bundle, its data, its retained APK and its row.
    ///
    /// ⚠ THIS DRIVES `InstallPipeline.uninstall`, AND IT MUST. It used to be a
    /// two-line local implementation -- delete `<sharedRoot>/data/data/<dir>`,
    /// drop the row -- written before app data moved inside the bundle. After
    /// the cutover it was wrong in every direction at once: it never removed
    /// the .app, so "Uninstall" left the app in /Applications and Launchpad;
    /// `deleteData: true` deleted a shared-root tree that for a bundle app
    /// holds nothing (or holds a fleet-harness run of the same package) while
    /// the real saves stayed in the bundle; and `deleteData: false` never ran
    /// the lift that the "your data is kept" promise depends on, so the user
    /// dragged the leftover .app to the Trash and lost everything the dialog
    /// had just told them was safe. That is the bug the owner reported.
    ///
    /// The pipeline is the ONE remover (see `uninstallAllApps`'s comment,
    /// which already claimed this call site drives it). Going through it also
    /// buys the retained-APK sweep, the pidfile cleanup, the empty-dirName
    /// refusal and -- via BundleGenerator's hook -- putting the bundle in the
    /// TRASH rather than unlinking it.
    ///
    /// Not `@MainActor`-bound work: removing a multi-GB bundle beachballs the
    /// panel, so callers run it off the main actor and refresh afterwards.
    nonisolated func removeSync(_ app: InstalledApp, deleteData: Bool,
                                runtimePath: String, androidRoot: URL,
                                log: @escaping (String) -> Void) -> InstallStatus {
        var hooks = InstallPipeline.Hooks()
        hooks.log = log
        BundleGenerator.install(into: &hooks)
        let pipeline = InstallPipeline(runtimePath: runtimePath,
                                       androidRoot: androidRoot, hooks: hooks)
        // Scoped to THIS version. One .app per version is the owner's model, so
        // a tile's Uninstall must leave a sibling version installed.
        return pipeline.uninstall(package: app.package, dirName: app.dirName,
                                  deleteData: deleteData)
    }

    /// Empty the library (the "Clear All Apps" dev action wipes the data
    /// dirs separately; this just drops every library entry). Dropping rows
    /// this launcher never saw is the point here -- "clear ALL" -- so the
    /// whole-array removal is the stated intent, not the S2 race.
    func clearAll() {
        persist { reg in reg.apps.removeAll() }
    }

    // MARK: install

    /// Install one or more APKs: run the runtime in --extract-only mode
    /// (up to 4 in parallel), then record each in the library. Progress is
    /// published for the header + drop-zone indicators. `completion` runs
    /// on the main actor once the whole batch finishes, and receives ONE
    /// `ExtractOutcome` per input APK so the caller can tell a real
    /// install from a failure instead of assuming success.
    func install(apks: [URL], runtimePath: String, androidRoot: String,
                 cacheDir: String,
                 source: String = "gui",
                 completion: (([ExtractOutcome]) -> Void)? = nil) {
        guard !apks.isEmpty else { completion?([]); return }
        installLog("session: \(apks.count) apk(s), source=\(source)")
        installProgress = .init(completed: 0, total: apks.count, current: "")
        let dataDataRoot = self.dataDataRoot
        let parallelLimit = 4
        let outcomes = ExtractOutcomeBox()
        DispatchQueue.global(qos: .userInitiated).async {
            let group = DispatchGroup()
            let sem = DispatchSemaphore(value: parallelLimit)
            for apk in apks {
                group.enter()
                DispatchQueue.global(qos: .userInitiated).async {
                    sem.wait()
                    defer { sem.signal(); group.leave() }
                    let apkName = apk.lastPathComponent
                    installLog("\(apkName): source \(apk.path)")
                    Task { @MainActor in
                        if var p = self.installProgress {
                            p.current = apkName
                            self.installProgress = p
                        }
                    }
                    // THE BUNDLE IS THE APP'S ONLY HOME (owner, 2026-08-11:
                    // "I want all the app data to be contained to the .app
                    // bundle ... the only thing shared would be the Mac.apk
                    // runtime"). So the bundle is created BEFORE extraction
                    // and the extractor is pointed INSIDE it -- rather than
                    // extracting to a shared root and moving afterwards,
                    // which left an app with two homes and a copy that
                    // outlived its bundle.
                    //
                    // This is what --peek-manifest exists for: the bundle
                    // needs package/label/version BEFORE the expensive
                    // extraction, and the peek reads only the binary
                    // manifest.
                    let peek = ApkPeek.detailed(apk, runtimePath: runtimePath)
                    var appRoot = androidRoot           // fallback: shared root
                    var appCache = cacheDir
                    var madeBundle: URL?
                    var bundleError: String? = nil
                    if let pkg = peek.package, !pkg.isEmpty {
                        let ver = peek.versionName ?? ""
                        let dirName = ver.isEmpty ? pkg : "\(pkg)_\(ver)"
                        do {
                            let url = try BundleGenerator.generate(
                                BundleSpec(package: pkg,
                                           label: peek.label ?? pkg,
                                           versionName: ver,
                                           versionCode: peek.versionCode ?? 0,
                                           iconPNG: nil,   // rasterized by the unpack; adopted below
                                           apkToRetain: apk),
                                dirName: dirName)
                            madeBundle = url
                            let android = url.appendingPathComponent("Contents/android")
                            appRoot  = android.path
                            appCache = android.appendingPathComponent(".macapk/code").path
                            installLog("\(apkName): bundle \(url.path)")
                            // The other half of "Uninstall, keep my data": put
                            // a parked tree back BEFORE the extractor runs, so
                            // main.cpp takes its "reinstall into existing data
                            // dir (app data preserved)" branch. Doing it after
                            // would race the extractor for the directory. Same
                            // call, same ordering, as InstallPipeline.run --
                            // this GUI path is a second install implementation
                            // and drifting on THIS would make the keep promise
                            // true only for helper-driven installs.
                            _ = KeptData.restore(package: pkg, versionName: ver,
                                                 intoAndroidRoot: android,
                                                 log: { installLog($0) })
                        } catch {
                            // ⚠ NO SHARED-ROOT FALLBACK -- see InstallCore's
                            // matching comment. An install that cannot produce
                            // a bundle has not installed an app, and pretending
                            // otherwise puts data in ~/Documents/android, which
                            // no longer exists.
                            installLog("\(apkName): bundle generation FAILED, "
                                     + "install aborted: \(error)")
                            bundleError = "\(error.localizedDescription)"
                        }
                    } else {
                        bundleError = "the APK's manifest gave no package name, "
                                    + "so no app bundle could be created"
                        installLog("\(apkName): manifest peek gave no package; aborting")
                    }

                    // Abort BEFORE extraction rather than after. Extracting with
                    // no bundle to extract INTO would write into the shared root
                    // -- the thing that must never happen again -- and would
                    // then need cleaning up.
                    if let be = bundleError {
                        let outcome = ExtractOutcome(apk: apk, entry: nil, exitStatus: -1,
                                                     output: "bundle generation failed: \(be)",
                                                     launchError: be)
                        outcomes.add(outcome)
                        Task { @MainActor in
                            if var pr = self.installProgress {
                                pr.completed += 1; pr.current = ""; self.installProgress = pr
                            }
                        }
                        return
                    }

                    installLog("\(apkName): extract-only starting into \(appRoot)")
                    let p = Process()
                    p.executableURL = URL(fileURLWithPath: runtimePath)
                    p.arguments = ["--apk", apk.path,
                                   "--android-root", appRoot,
                                   "--extract-only"]
                    var env = ProcessInfo.processInfo.environment
                    env["MACAPK_APP_CACHE_DIR"] = appCache
                    p.environment = env
                    // Capture the extractor's output rather than dropping
                    // it on /dev/null: it is the ONLY evidence of why an
                    // install failed, and we now report failures. ONE pipe
                    // carries both streams so a single read-to-EOF drains
                    // everything -- two pipes with one reader can deadlock
                    // once the unread one fills its 64 KB buffer.
                    let pipe = Pipe()
                    p.standardOutput = pipe
                    p.standardError  = pipe
                    var captured = ""
                    var status: Int32 = -1
                    var launchError: String? = nil
                    do {
                        try p.run()
                        // Read to EOF BEFORE waiting. A chatty extract on a
                        // large APK can outrun the pipe buffer, and waiting
                        // first would then block forever.
                        let data = pipe.fileHandleForReading.readDataToEndOfFile()
                        p.waitUntilExit()
                        status = p.terminationStatus
                        captured = String(data: data, encoding: .utf8) ?? ""
                        // Keep the tail: the proximate failure is at the end.
                        if captured.count > 8192 {
                            captured = String(captured.suffix(8192))
                        }
                    } catch {
                        launchError = error.localizedDescription
                        installLog("\(apkName): extractor could not start: \(error)")
                    }
                    // Read the install's own metadata back from wherever it
                    // actually landed. MirrorInstallMetadata copies the two
                    // launcher-facing files (meta.json, icon.png) into
                    // "<root>/data/data/<dir>/", and <root> is the bundle
                    // when we made one -- so scanning the SHARED root here
                    // would find nothing for a bundled install.
                    let scanRoot = madeBundle
                        .map { $0.appendingPathComponent("Contents/android/data/data") }
                        ?? dataDataRoot
                    let entry = Self.makeEntry(forApk: apk,
                                               dataDataRoot: scanRoot,
                                               source: source)
                    let outcome = ExtractOutcome(apk: apk, entry: entry,
                                                 exitStatus: status,
                                                 output: captured,
                                                 launchError: launchError,
                                                 bundlePath: madeBundle?.path)
                    if !outcome.succeeded {
                        // Mirror the real reason into the system log even
                        // when the caller only shows a one-line summary.
                        // The FULL captured tail, not just the one-line
                        // diagnostic: the status bar shows the summary, and
                        // the summary is what proved too thin to diagnose a
                        // fresh-machine failure remotely.
                        installLog("\(apkName): FAILED (exit \(status)): "
                                 + "\(outcome.failureReason)")
                        installLog("\(apkName): extractor output follows\n\(captured)")
                        // A failed extraction must not leave a half-built,
                        // Spotlight-visible .app in /Applications (audit S7).
                        // Only when it holds NO completed install: a failed
                        // REinstall regenerated a bundle that adopted the
                        // previous data, and that data must survive.
                        if let url = madeBundle,
                           !InstallPipeline.bundleContainsInstalledData(url) {
                            do {
                                try BundleGenerator.remove(bundleAt: url)
                                installLog("\(apkName): removed the half-built "
                                         + "bundle \(url.path)")
                            } catch {
                                installLog("\(apkName): could not remove the "
                                         + "half-built bundle \(url.path): \(error)")
                            }
                            madeBundle = nil
                        }
                    } else {
                        installLog("\(apkName): installed "
                                 + "\(entry?.package ?? "?") "
                                 + "\(entry?.version ?? "") -> "
                                 + "\(madeBundle?.path ?? "no bundle")")
                    }
                    // The bundle was created BEFORE extraction (above), so
                    // there is nothing to generate here -- only to record.
                    // Generating it afterwards is what used to leave the app
                    // extracted in the shared root with an empty bundle beside
                    // it; the ordering IS the fix, not an optimisation.
                    // Stamp the REAL name and icon on, exactly as the
                    // pipeline does. Without this the GUI paths (double-click,
                    // drag-drop, install-from-folder, Open With) left every
                    // bundle named after its PACKAGE with a default icon,
                    // while a helper-driven install got it right -- so the fix
                    // shipped in v1.0.950 was true only for the path I tested.
                    var bundled = madeBundle.map { entry?.withBundlePath($0.path) } ?? entry
                    if let url = madeBundle, let e = entry {
                        let icon = scanRoot.appendingPathComponent(e.dirName)
                                           .appendingPathComponent("icon.png")
                        let finalURL = BundleGenerator.finalizeIdentity(
                            bundle: url,
                            label: e.label,
                            iconPNG: FileManager.default.fileExists(atPath: icon.path) ? icon : nil,
                            versionName: e.version,
                            package: e.package,
                            dirName: e.dirName)
                        bundled = e.withBundlePath(finalURL.path)
                    }

                    outcomes.add(outcome)
                    Task { @MainActor in
                        if let entry = bundled { self.upsert(entry) }
                        // Increment the published counter directly (no
                        // captured mutable var -> Swift-6-clean).
                        if var pr = self.installProgress {
                            pr.completed += 1
                            pr.current = ""
                            self.installProgress = pr
                        }
                    }
                }
            }
            group.notify(queue: .main) {
                Task { @MainActor in
                    self.installProgress = nil
                    completion?(outcomes.all())
                }
            }
        }
    }

    // MARK: helpers (nonisolated; safe to call off the main actor)

    /// Build a library entry for a just-extracted APK by locating the
    /// data/data dir whose meta.json records this APK's path.
    /// The launcher-written `icon.png` for an installed app, or nil when the
    /// unpack could not rasterize one (adaptive/vector icons still land here
    /// only after a boot-time render). Static + nonisolated because the
    /// install path needs it off the main actor.
    nonisolated static func installedIconURL(dataDataRoot: URL,
                                             dirName: String) -> URL? {
        let p = dataDataRoot.appendingPathComponent(dirName)
                            .appendingPathComponent("icon.png")
        return FileManager.default.fileExists(atPath: p.path) ? p : nil
    }

    nonisolated private static func makeEntry(forApk apk: URL,
                                              dataDataRoot: URL,
                                              source: String) -> InstalledApp? {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: dataDataRoot, includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]) else { return nil }
        for dir in entries {
            // "<pkg>.staging.<pid>" is a runtime install-staging dir: a
            // CONCURRENT install's half-written tree (or a crashed one,
            // reaped by the runtime later). Its meta.json can transiently
            // match this apkPath, and binding the library entry to a dir
            // that is about to be renamed/removed yields a ghost app.
            if dir.lastPathComponent.contains(".staging.") { continue }
            if DataCarryForward.isSymlink(dir) { continue }   // v1.0.1641 compat link
            let meta = dir.appendingPathComponent("meta.json")
            guard let d = try? Data(contentsOf: meta),
                  let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
            else { continue }
            let recorded = (j["apkPath"] as? String) ?? ""
            let match = (recorded == apk.path)
                || (URL(fileURLWithPath: recorded).lastPathComponent == apk.lastPathComponent)
            if match { return entryFrom(dir: dir, meta: j, source: source) }
        }
        return nil
    }

    /// First-run seed: import every data/data dir that has a meta.json.
    nonisolated private static func importExisting(dataDataRoot: URL) -> [InstalledApp] {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: dataDataRoot, includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]) else { return [] }
        var out: [InstalledApp] = []
        for dir in entries {
            if dir.lastPathComponent.hasPrefix(".") { continue }
            // Skip runtime install-staging dirs ("<pkg>.staging.<pid>"):
            // half-written by a concurrent install, or dead until the
            // runtime's stale-staging sweep reaps them. Seeding an entry
            // from one produces a ghost app whose dir vanishes.
            if dir.lastPathComponent.contains(".staging.") { continue }
            let isDir = (try? dir.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            if !isDir || DataCarryForward.isSymlink(dir) { continue }
            let meta = dir.appendingPathComponent("meta.json")
            if let d = try? Data(contentsOf: meta),
               let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
                // A seeded row predates the registry, so we genuinely do not
                // know how it got here. "imported" says that rather than
                // guessing "gui".
                out.append(entryFrom(dir: dir, meta: j, source: "imported"))
            }
        }
        return out
    }

    nonisolated private static func entryFrom(dir: URL,
                                              meta: [String: Any],
                                              source: String) -> InstalledApp {
        let dirName = dir.lastPathComponent
        let pkg: String = {
            if let p = meta["package"] as? String, !p.isEmpty { return p }
            return packageFromDirName(dirName)
        }()
        let label: String = {
            if let l = meta["label"] as? String, !l.isEmpty { return l }
            return pkg
        }()
        let apkPath: String? = {
            if let a = meta["apkPath"] as? String, !a.isEmpty { return a }
            return nil
        }()
        // meta.json now carries versionCode + versionName alongside the
        // original three keys (runtime/src/apk_unpacker.cpp
        // WriteAppMetadata). Rows written before that stay nil, which is
        // why every comparison against this field has to tolerate nil
        // rather than defaulting to 0 -- a defaulted 0 would make every
        // pre-existing app look older than everything.
        let code: Int64? = {
            if let n = meta["versionCode"] as? NSNumber { return n.int64Value }
            if let s = meta["versionCode"] as? String { return Int64(s) }
            return nil
        }()
        return InstalledApp(package: pkg, label: label,
                            version: versionFromDirName(dirName, package: pkg),
                            apkPath: apkPath, dirName: dirName,
                            versionCode: code,
                            originalApkPath: apkPath,
                            installedAt: Date(),
                            source: source)
    }

    /// "<package>_<version>" -> package, when meta.json lacks one. Splits
    /// at the first "_" that precedes a digit (the version's leading char).
    nonisolated private static func packageFromDirName(_ dirName: String) -> String {
        let chars = Array(dirName)
        var i = 0
        while i < chars.count - 1 {
            if chars[i] == "_" && chars[i + 1].isNumber {
                return String(chars[0..<i])
            }
            i += 1
        }
        return dirName
    }

    nonisolated private static func versionFromDirName(_ dirName: String,
                                                       package: String) -> String {
        let prefix = package + "_"
        if dirName.hasPrefix(prefix) { return String(dirName.dropFirst(prefix.count)) }
        return ""
    }

    nonisolated private static func directorySizeBytes(at dir: URL) -> Int64 {
        let fm = FileManager.default
        guard fm.fileExists(atPath: dir.path),
              let e = fm.enumerator(at: dir,
                  includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
                  options: [.skipsHiddenFiles, .skipsPackageDescendants])
        else { return 0 }
        var total: Int64 = 0
        for case let url as URL in e {
            let v = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            if v?.isRegularFile == true, let s = v?.fileSize { total += Int64(s) }
        }
        return total
    }
}

// MARK: - Per-app detail sheet
//
// "Get Info" for an installed app: what it is, what it is using on disk,
// what is broken about it, and the environment it launches with.
//
// Everything expensive here (directory sizes, orphan discovery) runs on a
// background queue and lands in @State. Doing it inline would freeze the
// window for as long as it takes to walk a multi-GB extracted asset tree,
// which is the normal size for the games this project targets.
struct AppDetailSheet: View {
    @ObservedObject var library: InstalledLibrary
    @EnvironmentObject var model: LaunchModel
    /// Snapshot from the tile that opened this sheet. Reads go through
    /// `current` so an edit made here is reflected immediately.
    let app: InstalledApp
    var onUpdateFromApk: (InstalledApp) -> Void
    var onResetApp: (InstalledApp) -> Void
    var onClose: () -> Void

    @State private var dataSize: Int64? = nil
    @State private var apkSize: Int64? = nil
    @State private var orphans: [InstalledLibrary.OrphanDir] = []
    @State private var scanning: Bool = true

    private var current: InstalledApp { library.current(app) }
    private var health: InstalledLibrary.AppHealth { library.health(for: app) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(current.label).font(.headline).lineLimit(1)
                Spacer()
                Button("Done") { onClose() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding()
            Divider()
            Form {
                if health.isBroken {
                    Section {
                        Label("This app needs attention: \(health.summary).",
                              systemImage: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                            .font(.caption)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                identitySection
                storageSection
                macAppSection
                optionsSection
                Section("Environment overrides") {
                    EnvEditor(env: Binding(
                        get: { library.current(app).env },
                        set: { library.setEnv($0, for: library.current(app)) }),
                              caption: "Applied to this app only, on top of the "
                                + "global default environment.")
                }
                Section("Actions") {
                    Button("Update from APK…") { onUpdateFromApk(current) }
                        .disabled(model.isRunning)
                    Button("Reset app (deletes saves)…", role: .destructive) {
                        onResetApp(current)
                    }
                    .disabled(model.isRunning)
                    Button("Copy debug launch command") { copyLaunchCommand() }
                        .help("Puts the full macapk-runtime command line, with "
                              + "this app's environment, on the clipboard.")
                }
            }
            .formStyle(.grouped)
        }
        .frame(width: 520, height: 620)
        .onAppear(perform: rescan)
    }

    private var identitySection: some View {
        Section("Identity") {
            row("Package", current.package)
            row("Version", current.version.isEmpty ? "unknown" : current.version)
            if let code = current.versionCode {
                row("Version code", String(code))
            }
            row("Installed from", sourceLabel(current.source))
            if let d = current.installedAt { row("Installed", Self.stamp(d)) }
            if let d = current.lastLaunchedAt { row("Last launched", Self.stamp(d)) }
        }
    }

    private var storageSection: some View {
        Section("Storage") {
            HStack {
                Text("App files")
                Spacer()
                Text(scanning ? "measuring…" : Self.bytes(dataSize))
                    .foregroundColor(.secondary)
                Button {
                    NSWorkspace.shared.activateFileViewerSelecting(
                        [library.dataDir(for: current).resolvingSymlinksInPath()])
                } label: { Image(systemName: "arrow.up.right.square") }
                    .disabled(health.dataDirMissing)
                    .help("Reveal this app's folder in Finder.")
            }
            HStack {
                Text("Source APK")
                Spacer()
                Text(health.apkMissing
                     ? "missing"
                     : (scanning ? "measuring…" : Self.bytes(apkSize)))
                    .foregroundColor(health.apkMissing ? .orange : .secondary)
                if let p = current.apkPath, !health.apkMissing {
                    Button {
                        NSWorkspace.shared.activateFileViewerSelecting(
                            [URL(fileURLWithPath: p)])
                    } label: { Image(systemName: "arrow.up.right.square") }
                        .help(p)
                }
            }
            if !orphans.isEmpty {
                // Old <pkg>_<version> trees. Surfaced, never auto-deleted:
                // they hold the previous version's SAVES, which is exactly
                // what a user reaches for after a bad update.
                Text("Older versions still on disk")
                    .font(.caption)
                    .foregroundColor(.secondary)
                ForEach(orphans) { o in
                    HStack {
                        Text(o.name)
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Text(Self.bytes(o.sizeBytes)).foregroundColor(.secondary)
                        Button("Delete", role: .destructive) { confirmDeleteOrphan(o) }
                            .controlSize(.small)
                    }
                }
            }
        }
    }

    private var macAppSection: some View {
        Section("macOS app") {
            if let b = current.bundlePath, !b.isEmpty {
                HStack {
                    Text(health.bundleMissing ? "Missing" : "Installed")
                        .foregroundColor(health.bundleMissing ? .orange : .primary)
                    Spacer()
                    Button {
                        NSWorkspace.shared.activateFileViewerSelecting(
                            [URL(fileURLWithPath: b)])
                    } label: { Image(systemName: "arrow.up.right.square") }
                        .disabled(health.bundleMissing)
                        .help(b)
                }
            } else {
                Text("No standalone macOS app has been generated for this app yet.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }

    private var optionsSection: some View {
        Section("App options") {
            Toggle("Run in Android TV mode", isOn: Binding(
                get: { library.current(app).env["MACAPK_TV_MODE"] == "1" },
                set: { enabled in
                    var env = library.current(app).env
                    if enabled {
                        env["MACAPK_TV_MODE"] = "1"
                    } else {
                        env.removeValue(forKey: "MACAPK_TV_MODE")
                    }
                    library.setEnv(env, for: library.current(app))
                }
            ))
            .help("Forces Android TV Leanback UI mode (UiModeManager MODE_TYPE_TELEVISION), bypassing mobile ads and enabling TV landscape controls.")
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundColor(.secondary)
                .textSelection(.enabled)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    private func sourceLabel(_ raw: String) -> String {
        switch raw {
        case "gui":      return "this window"
        case "finder":   return "Finder"
        case "imported": return "imported from an earlier install"
        default:
            if raw.hasPrefix("android:") {
                return "an Android app (\(raw.dropFirst("android:".count)))"
            }
            return raw
        }
    }

    private func rescan() {
        scanning = true
        let snapshot = current
        let root = library.dataDataRoot
        let lib = library
        DispatchQueue.global(qos: .userInitiated).async {
            let dSize = lib.sizeOfDirectory(root.appendingPathComponent(snapshot.dirName))
            let aSize: Int64? = snapshot.apkPath.flatMap { p in
                (try? URL(fileURLWithPath: p)
                    .resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }
                    .map(Int64.init)
            }
            let orph = lib.orphanDirs(for: snapshot, dataDataRoot: root)
            DispatchQueue.main.async {
                dataSize = dSize
                apkSize = aSize
                orphans = orph
                scanning = false
            }
        }
    }

    private func confirmDeleteOrphan(_ o: InstalledLibrary.OrphanDir) {
        let alert = NSAlert()
        alert.messageText = "Delete \(o.name)?"
        alert.informativeText = """
        This is an older version's folder for \(current.package). It holds \
        that version's assets AND its saved data (\(Self.bytes(o.sizeBytes))).

        Deleting it does not affect the version you have installed now.
        """
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Delete")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        library.deleteOrphan(o)
        rescan()
    }

    /// The exact command line this app launches with, for bug reports.
    private func copyLaunchCommand() {
        guard let runtime = model.runtimePath() else { return }
        var parts: [String] = []
        let env = model.defaultEnv.merging(current.env) { _, perApp in perApp }
        for k in env.keys.sorted() {
            parts.append("\(k)=\(Self.shellQuote(env[k] ?? ""))")
        }
        parts.append(Self.shellQuote(runtime))
        parts.append("--apk")
        parts.append(Self.shellQuote(current.apkPath ?? "<no APK recorded>"))
        parts.append("--android-root")
        parts.append(Self.shellQuote(model.androidRootURL().path))
        let cmd = parts.joined(separator: " ")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(cmd, forType: .string)
        model.status = "Launch command copied to the clipboard."
        model.statusKind = .info
    }

    /// Single-quote for /bin/sh, escaping embedded quotes the POSIX way
    /// ('\'' closes, escapes, reopens). App labels and android roots are
    /// user-controlled paths, so this cannot be skipped.
    private static func shellQuote(_ s: String) -> String {
        "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func bytes(_ n: Int64?) -> String {
        guard let n = n else { return "unknown" }
        return ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }

    private static func stamp(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: d)
    }
}

// MARK: - Installed Apps grid

/// Grid of installed apps. Single-click selects (and loads into the
/// launch slot so the Launch button + Return run it); double-click
/// launches immediately; right-click for the full action menu.
struct InstalledAppsView: View {
    @EnvironmentObject var model: LaunchModel
    @ObservedObject var library: InstalledLibrary
    /// Hand an install request UP to LauncherView's single funnel rather
    /// than calling library.install here. This view used to own a second,
    /// separately-maintained copy of the install-and-report logic, which is
    /// how the batch path ended up with failure summaries the single-file
    /// path did not have (and vice versa for the ABI warning).
    var onInstall: (LauncherInstallRequest) -> Void
    /// Open the per-app detail sheet (owned by LauncherView, so it survives
    /// this view's re-renders and can sit above the whole window).
    var onShowDetail: (InstalledApp) -> Void
    @State private var expanded: Bool = true
    @State private var hoveredApp: String? = nil

    private let tileSize: CGFloat = 80
    private let iconSize: CGFloat = 64

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            if expanded { gridOrEmptyState }
        }
        .onAppear {
            library.configure(dataDataRoot: model.arm32AppCacheDir())
        }
    }

    @ViewBuilder
    private var header: some View {
        VStack(spacing: 4) {
            ZStack {
                HStack(spacing: 8) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.secondary)
                    Text("Installed apps")
                        .font(.system(size: 13, weight: .semibold))
                    Text(library.removedApps.isEmpty
                         ? "(\(library.installedApps.count))"
                         : "(\(library.installedApps.count) + \(library.removedApps.count) removed)")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity)
                HStack {
                    Spacer()
                    Button(action: { installFromFolder() }) {
                        Label("Install from folder", systemImage: "square.and.arrow.down")
                            .labelStyle(.titleAndIcon)
                            .font(.system(size: 12))
                    }
                    .buttonStyle(.borderless)
                    .disabled(library.installProgress != nil)
                    .help("Open a folder of .apk/.apkm/.xapk/.apks files and " +
                          "install (extract icon + label + assets) each one. " +
                          "Runs 4 in parallel. Already-installed apps refresh.")
                    Button(action: { library.refresh() }) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 11))
                    }
                    .buttonStyle(.borderless)
                    .help("Reload the installed-apps list")
                }
            }
            if let prog = library.installProgress {
                // Owner ask: the app name used to sit on the SAME line as
                // the counter, so a long filename grew the row until it ran
                // into the "Install from folder" button above it. Name goes
                // on its own line under the counter, and is width-capped so
                // it truncates instead of pushing anything.
                VStack(spacing: 2) {
                    HStack(spacing: 8) {
                        ProgressView(value: Double(prog.completed),
                                      total: Double(max(prog.total, 1)))
                            .progressViewStyle(.linear)
                            .frame(maxWidth: 160)
                        Text("Installing \(prog.completed)/\(prog.total)")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .fixedSize()
                    }
                    if !prog.current.isEmpty {
                        Text(prog.current)
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .frame(maxWidth: 260)
                    }
                }
                .frame(maxWidth: .infinity)
            } else if let err = library.loadError {
                // The registry exists but could not be read. Loud on
                // purpose: the grid below is EMPTY in this state, and an
                // empty grid with no explanation reads as "my apps are
                // gone" when in fact nothing has been touched.
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                        .font(.system(size: 12))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Your installed-apps list could not be read.")
                            .font(.system(size: 11, weight: .semibold))
                        Text("\(err) Nothing has been deleted and no changes "
                             + "will be saved until this is resolved.")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 2)
            } else if expanded && !library.apps.isEmpty {
                // v1.0.465: the "how to run" hint lives right by the apps.
                Text("Double-click to launch  ·  right-click for options  ·  drop an APK above to add more")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() }
        }
    }

    @ViewBuilder
    private var gridOrEmptyState: some View {
        if library.apps.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("No apps installed yet.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Text("Drop an APK above (or use \"Install from folder\") to install one, then launch it from here.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(.vertical, 6)
        } else {
            // Fixed columns, as many as fit, and the block of them centred
            // (owner, 2026-10-13: the drawer was off centre). The old
            // adaptive GridItem capped each column at tileSize, so whatever
            // width was left over after the last whole column all piled up
            // on the trailing edge. A partial last row still starts at the
            // left of the centred block, like Launchpad.
            GeometryReader { geo in
            let count = max(1, Int((geo.size.width + 6) / (tileSize + 6)))
            let columns = Array(repeating: GridItem(.fixed(tileSize), spacing: 6),
                                count: count)
            ScrollView(.vertical, showsIndicators: true) {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
                    // Installed first, then the rows whose .app the user
                    // deleted behind our back. The second group is DIMMED and
                    // cannot be launched: on a device an app whose code is
                    // gone is simply not installed, and this panel must not
                    // claim otherwise any more than PackageManager may. They
                    // stay listed rather than vanishing because the row still
                    // points at the retained APK -- one click reinstalls with
                    // no download.
                    ForEach(library.installedApps) { app in
                        appTile(app)
                    }
                    ForEach(library.removedApps) { app in
                        appTile(app)
                    }
                }
                // An explicit width: a LazyVGrid takes the whole proposed
                // width even with fixed columns and lays them out from the
                // leading edge, so maxWidth alone does not centre it.
                .frame(width: CGFloat(count) * tileSize + CGFloat(count - 1) * 6)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity)
            }
            }
            // v1.0.503: grow the grid with the window instead of the old
            // hard 3-row cap (maxHeight: 3 * (tileSize + 28)). The columns
            // already reflow with window WIDTH via the adaptive GridItem;
            // this lets the ScrollView take the remaining vertical space so
            // ROWS grow/shrink with window HEIGHT and the grid only scrolls
            // when it genuinely overflows. A one-row minHeight floor keeps
            // at least a row of tiles visible when the window is short or the
            // verbose log panel is open; maxHeight .infinity claims the slack
            // (InstalledAppsView carries .layoutPriority(1) in LauncherView so
            // it wins that slack over the trailing Spacer).
            .frame(minHeight: tileSize + 28, maxHeight: .infinity)
        }
    }

    private func appTile(_ app: InstalledApp) -> some View {
        let selected = (model.selectedAppId == app.id)
        let gone = (app.state == AppState.removed)
        return VStack(spacing: 4) {
            iconView(app)
                .frame(width: iconSize, height: iconSize)
                .opacity(gone ? 0.35 : 1)
                .overlay(alignment: .topTrailing) { healthBadge(app) }
            Text(app.label)
                .font(.system(size: 11))
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(width: tileSize - 4)
                .foregroundColor(gone ? .secondary : .primary)
            if gone {
                Text("not installed")
                    .font(.system(size: 9))
                    .foregroundColor(.orange)
            }
        }
        .padding(.horizontal, 2)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(selected
                      ? Color.accentColor.opacity(0.22)
                      : Color.gray.opacity(hoveredApp == app.id ? 0.18 : 0))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(selected ? Color.accentColor : Color.clear, lineWidth: 1.5)
        )
        .contentShape(Rectangle())
        .onHover { hovering in
            hoveredApp = hovering ? app.id : (hoveredApp == app.id ? nil : hoveredApp)
        }
        .help(helpText(app))
        // Double-click launches; single-click selects. count:2 declared
        // first so SwiftUI dispatches it before the single-click fallback.
        .onTapGesture(count: 2) { launchApp(app) }
        .onTapGesture(count: 1) { selectApp(app) }
        .contextMenu {
            if app.state == AppState.removed {
                // No Launch and no Select: there is nothing to launch. The
                // repair path re-runs the install from the RETAINED APK, so
                // it needs no download and no file picker.
                Button("Reinstall") { reinstall(app) }
                    .disabled(library.health(for: app).apkMissing && app.apkPath == nil)
                Button("Get Info…") { onShowDetail(app) }
            } else {
                Button("Launch") { launchApp(app) }
                Button("Select (don't launch)") { selectApp(app) }
                Button("Get Info…") { onShowDetail(app) }
            }
            Divider()
            if let p = app.apkPath {
                Button("Reveal APK in Finder") {
                    NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: p)])
                }
                .disabled(library.health(for: app).apkMissing)
            }
            Button("Reveal install dir in Finder") {
                NSWorkspace.shared.activateFileViewerSelecting(
                    [library.dataDir(for: app).resolvingSymlinksInPath()])
            }
            .disabled(library.health(for: app).dataDirMissing)
            // Stage S1: Android's special app access for this app, as Settings
            // lists it. Shown only while a grant is in force -- the ALLOW side
            // is the app's to ask for (its own prompt, then the system alert
            // macapk-installhelper shows), exactly as on a device; this panel
            // is the Settings page where the user can take it back.
            if app.state != AppState.removed {
                let granted = AppOpsStore.allowedOps(for: specialAccessIdentity(app))
                if !granted.isEmpty {
                    Divider()
                    if granted.contains(AppOpsStore.manageExternalStorage) {
                        Button("Revoke All Files Access") {
                            revokeSpecialAccess(app, op: AppOpsStore.manageExternalStorage)
                        }
                    }
                    if granted.contains(AppOpsStore.manageMedia) {
                        Button("Revoke Media Management") {
                            revokeSpecialAccess(app, op: AppOpsStore.manageMedia)
                        }
                    }
                }
            }
            Divider()
            Button(app.state == AppState.removed ? "Remove from library…" : "Uninstall…",
                   role: .destructive) { confirmUninstall(app) }
        }
    }

    /// The special-access store's identity for a tile: the registry row's
    /// package + installedAt key the grant (it survives an in-place update; a
    /// reinstall starts clean), dirName names the process to stop.
    private func specialAccessIdentity(_ app: InstalledApp) -> AppOpsStore.Identity {
        AppOpsStore.Identity(dirName: app.dirName, package: app.package,
                             installedAt: ISO8601.format(app.installedAt) ?? "")
    }

    /// Settings' switch turned off: record "deny" (signed, like every write to
    /// the store) and, for All files access, stop the app if it is running --
    /// AOSP StorageManagerService kills an app whose MANAGE_EXTERNAL_STORAGE
    /// op leaves MODE_ALLOWED (killAppForOpChange); media management has no
    /// such rule.
    private func revokeSpecialAccess(_ app: InstalledApp, op: String) {
        let what = (op == AppOpsStore.manageExternalStorage) ? "All files access"
                                                             : "Media management"
        let id = specialAccessIdentity(app)
        // Every installed version that shares this grant (same package +
        // installedAt: an update installed beside the old bundle) loses it too.
        let sharing = library.apps
            .filter { specialAccessIdentity($0).key == id.key }
            .map(\.dirName)
        do {
            let stopped = try AppOpsStore.revoke(op: op, id: id, alsoDirNames: sharing)
            model.status = "\(what) revoked for \(app.label)"
                + (stopped ? "; it was running and has been stopped, as Android does." : ".")
            model.statusKind = .success
        } catch {
            model.status = "Could not revoke \(what) for \(app.label): "
                + error.localizedDescription
            model.statusKind = .error
        }
    }

    /// Re-run the install for a row whose .app the user deleted, from the APK
    /// we retained at install time. Routed through the SAME pipeline an
    /// ordinary install uses -- there is no second install path here.
    private func reinstall(_ app: InstalledApp) {
        guard let apk = app.resolvedApkPath
        else {
            model.status = "No APK on disk for \(app.label) -- install it from a file again."
            model.statusKind = .error
            return
        }
        onInstall(LauncherInstallRequest(sourceFiles: [URL(fileURLWithPath: apk)],
                                         source: .gui, interactive: true))
    }

    /// Warning glyph on a tile whose registry row does not match the disk.
    /// Surfaced here rather than only inside the detail sheet because the
    /// grid is where the user looks, and "why won't this launch" is exactly
    /// the question the reconcile pass answers.
    @ViewBuilder
    private func healthBadge(_ app: InstalledApp) -> some View {
        let h = library.health(for: app)
        if h.isBroken {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 12))
                .foregroundColor(.orange)
                .background(
                    Circle().fill(Color(nsColor: .windowBackgroundColor))
                        .frame(width: 14, height: 14))
                .help("\(app.label): \(h.summary). Right-click > Get Info… for details.")
        }
    }

    private func helpText(_ app: InstalledApp) -> String {
        var s = "\(app.label) (\(app.package))"
        if !app.version.isEmpty { s += "\nversion \(app.version)" }
        s += "\nClick to select · Double-click to launch"
        return s
    }

    private func iconView(_ app: InstalledApp) -> some View {
        Group {
            if let iconPath = library.iconURL(for: app),
               let img = NSImage(contentsOf: iconPath) {
                Image(nsImage: img)
                    .resizable()
                    .scaledToFit()
                    .cornerRadius(10)
            } else {
                // Adaptive-XML / missing-icon fallback: monogram tile
                // with the first character of the label.
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(LinearGradient(
                            colors: [Color.accentColor.opacity(0.6),
                                     Color.accentColor.opacity(0.4)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing))
                    Text(String(app.label.prefix(1)).uppercased())
                        .font(.system(size: 24, weight: .semibold, design: .rounded))
                        .foregroundColor(.white)
                }
            }
        }
    }

    /// Select (load) an installed app into the launch slot without running
    /// it. Sets the highlight + the app label/icon the runtime uses for
    /// its Dock entry + window title.
    private func selectApp(_ app: InstalledApp) {
        guard app.state != AppState.removed else {
            model.status = "\(app.label) is not installed -- its macOS app was deleted. "
                         + "Right-click > Reinstall (uses the APK the app was installed from, if it is still on disk)."
            model.statusKind = .error
            return
        }
        guard let apkPath = app.resolvedApkPath ?? app.apkPath else {
            model.status = "No APK path recorded for \(app.label). Re-install it by dropping the APK again."
            model.statusKind = .error
            return
        }
        model.apkPath = apkPath
        model.appLabel = app.label
        model.appBundlePath = app.bundlePath ?? ""
        model.appIconPath = library.iconURL(for: app)?.path ?? ""
        model.selectedAppId = app.id
        model.status = "\(app.label) selected — double-click to launch."
        model.statusKind = .info
    }

    /// Select + run immediately (the LauncherView's pendingLaunch watcher
    /// fires launch() on the next tick).
    private func launchApp(_ app: InstalledApp) {
        selectApp(app)
        guard model.selectedAppId == app.id, !model.apkPath.isEmpty else { return }
        model.status = "Launching \(app.label)…"
        model.statusKind = .info
        model.pendingLaunch &+= 1
    }

    /// Uninstall one installed version: its .app, its data, its retained APK
    /// and its registry row.
    ///
    /// ⚠ THE WORDING IS PART OF THE FIX, not decoration. The previous text
    /// said "the app's data folder is kept on disk, so re-installing it later
    /// is instant" -- which described the pre-bundle world. After the cutover
    /// the data lives inside the .app, this dialog's action did not remove the
    /// .app at all, and the user then dragged the leftover app to the Trash and
    /// lost the saves the dialog had just called safe. Two rules for anyone
    /// editing this string: say what happens to the .APP (it is what the user
    /// sees in /Applications), and never promise data survives unless
    /// `KeptData.restore` can actually bring it back.
    private func confirmUninstall(_ app: InstalledApp) {
        let alert = NSAlert()
        alert.messageText = "Uninstall \(app.label) \(app.version)?"
        let dataSizeStr: String
        if library.dataDirExists(for: app) {
            dataSizeStr = ByteCountFormatter.string(
                fromByteCount: library.dataDirSizeBytes(for: app), countStyle: .file)
        } else {
            dataSizeStr = "no data on disk"
        }
        alert.informativeText = """
        This moves \(app.label) \(app.version) to the Trash and removes it \
        from your installed apps.

        Its saved data (\(dataSizeStr)) is set aside first, and installing \
        this same version again puts it back. Nothing else on your Mac is \
        touched.

        Tick the box to delete that data permanently instead. This cannot be \
        undone.
        """
        alert.alertStyle = .warning
        // Repurpose the suppression checkbox as the "also delete data" toggle.
        alert.showsSuppressionButton = true
        alert.suppressionButton?.title = "Also delete its saved data permanently"
        alert.addButton(withTitle: "Uninstall")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let deleteData = (alert.suppressionButton?.state == .on)
        if model.selectedAppId == app.id {
            model.selectedAppId = ""
            model.apkPath = ""
            model.appLabel = ""
            model.appIconPath = ""
        }
        // Off the main actor: removing a multi-GB bundle beachballs the panel.
        let rt = model.runtimePath() ?? ""
        let root = model.androidRootURL()
        model.status = "Uninstalling \(app.label)…"
        model.statusKind = .info
        DispatchQueue.global(qos: .userInitiated).async {
            let st = library.removeSync(app, deleteData: deleteData,
                                        runtimePath: rt, androidRoot: root,
                                        log: { m in Task { @MainActor in model.appendLog(m) } })
            Task { @MainActor in
                library.refresh()
                switch st {
                case .success:
                    model.status = deleteData
                        ? "Uninstalled \(app.label) \(app.version) and deleted its data."
                        : "Uninstalled \(app.label) \(app.version); its data is kept for a reinstall."
                    model.statusKind = .success
                case .failed(let stage, let message):
                    model.status = "Could not uninstall \(app.label) (\(stage)): \(message)"
                    model.statusKind = .error
                default:
                    model.status = "Uninstall of \(app.label) did not complete."
                    model.statusKind = .error
                }
            }
        }
    }

    /// Install every APK in a chosen folder (extract-only, 4 in parallel).
    private func installFromFolder() {
        let panel = NSOpenPanel()
        panel.title = "Choose APK folder"
        panel.message = "Pick a folder of .apk / .apkm / .xapk / .apks files. " +
            "Mac.apk installs (extract + register) each one. " +
            "Already-installed apps are refreshed."
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        // Seed at the project's Apks/ dir (sibling of the checkout) if present.
        let projectApks = URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent("Documents/Projects/Macapk/Apks")
        if FileManager.default.fileExists(atPath: projectApks.path) {
            panel.directoryURL = projectApks
        }
        guard panel.runModal() == .OK, let folder = panel.url else { return }

        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: folder,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]) else { return }
        let suffixes = [".apk", ".apkm", ".xapk", ".apks", ".apkx"]
        let apks = entries.filter { url in
            suffixes.contains { url.lastPathComponent.lowercased().hasSuffix($0) }
        }.sorted { $0.lastPathComponent < $1.lastPathComponent }

        if apks.isEmpty {
            let alert = NSAlert()
            alert.messageText = "No APKs found"
            alert.informativeText = "\(folder.path) contains no " +
                ".apk / .apkm / .xapk / .apks / .apkx files."
            alert.runModal()
            return
        }
        // The funnel keeps the 4-way parallel extract (InstalledLibrary
        // .install takes the whole array and runs `parallelLimit` at a
        // time), and now also gives this path the failure summary and the
        // no-usable-ABI warning it shares with every other entry point.
        onInstall(LauncherInstallRequest(sourceFiles: apks,
                                         source: .gui,
                                         interactive: false))
    }
}

// MARK: - Accounts panel (Google sign-in)
//
// The user-visible half of the `--gms` contract in "Google account plumbing"
// above. Everything expensive here is a subprocess, so nothing in this file
// calls the runtime on the main thread: `GmsAccountStore` owns that rule and
// this view only reads its published state.
//
// The panel is deliberately narrow in scope. It answers three questions and no
// others: is an account signed in, how do I add one, and what does Mac.apk do
// with it. Account state itself lives in the runtime; the launcher persists
// nothing about it, so there is no LauncherSettings key here.
struct AccountsSheet: View {
    @EnvironmentObject var model: LaunchModel
    @ObservedObject var store: GmsAccountStore
    var onClose: () -> Void

    /// The account the sign-out confirmation is about, and whether it is up.
    /// Two pieces of state rather than one optional because
    /// `confirmationDialog` is driven by a Bool and the dialog's own body
    /// still needs to name the account.
    @State private var signOutTarget: GmsAccount? = nil
    @State private var showSignOutConfirm: Bool = false
    /// `--gms status` output, fetched on demand behind the disclosure.
    @State private var diagnostics: String = ""
    @State private var showDiagnostics: Bool = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Accounts")
                    .font(.headline)
                Spacer()
                // Closing mid-sign-in is fine and is NOT a cancel: the store
                // outlives this sheet, so the Google window stays open and the
                // result lands whether the panel is on screen or not.
                Button("Done") { onClose() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding()
            Divider()
            Form {
                Section("Google") { googleSection }
                Section("Privacy") { privacySection }
                if model.devMenuRevealed {
                    Section("Diagnostics") { diagnosticsSection }
                }
            }
            .formStyle(.grouped)
        }
        .frame(width: 480, height: model.devMenuRevealed ? 600 : 430)
        .onAppear { store.refresh(runtimePath: model.runtimePath()) }
        // A SwiftUI dialog rather than the NSAlert the rest of this file uses
        // for confirmations: every one of those runs from the main window,
        // whereas this one would start a nested app-modal loop on top of a
        // presented sheet. The comment on the detail sheet's dismiss-then-act
        // handlers is about exactly that hazard.
        .confirmationDialog(
            Text(signOutTarget.map { "Sign out of \($0.email)?" } ?? "Sign out?"),
            isPresented: $showSignOutConfirm,
            titleVisibility: .visible
        ) {
            Button("Sign Out", role: .destructive) {
                if let account = signOutTarget {
                    store.signOut(account, runtimePath: model.runtimePath())
                }
            }
            // Deliberately does NOT clear signOutTarget: the title and message
            // above read it, and nilling it here would make both flip to their
            // placeholder text while the dialog is still animating out. Every
            // path that presents the dialog sets it first, so a stale value
            // is never displayed.
            Button("Cancel", role: .cancel) { }
        } message: {
            Text(signOutTarget.map {
                "Mac.apk deletes \($0.email)'s saved token from this Mac. Apps "
                + "that check a Google Play licence will not find an account "
                + "until you sign in again."
            } ?? "")
        }
    }

    // MARK: Google section

    @ViewBuilder
    private var googleSection: some View {
        if model.runtimePath() == nil {
            // A missing runtime is why the buttons would do nothing, so say so
            // instead of offering a Sign in button that silently fails.
            Label("Mac.apk cannot find its runtime, so it cannot manage "
                + "accounts. Install the runtime from the control at the top "
                + "left of the main window, or build it with "
                + "runtime/scripts/build.sh.",
                  systemImage: "exclamationmark.triangle.fill")
                .font(.callout)
                .foregroundStyle(Color.orange)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            switch store.phase {
            case .signingIn:
                signingInRow
            case .loading:
                busyRow("Checking for signed-in accounts…")
            case .signingOut(let email):
                busyRow("Signing out of \(email)…")
            case .idle:
                idleContent
            }
            if let message = store.errorText {
                errorRow(message)
            }
        }
    }

    @ViewBuilder
    private var idleContent: some View {
        if store.accounts.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("No Google account is signed in.")
                    .font(.callout)
                // WHY someone would want one. Without this the button is an
                // unexplained request for a Google password, which is exactly
                // the shape of a thing users are right to refuse.
                Text("Apps you bought on Google Play verify their licence with "
                   + "Google before they run, exactly as they do on an Android "
                   + "device. Signing in lets Mac.apk answer that check with "
                   + "your own account. Apps that do not use Play licensing "
                   + "run without one.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                signInButton(title: "Sign in with Google", prominent: true)
            }
        } else {
            VStack(alignment: .leading, spacing: 10) {
                // Every account, not just the first: `--gms accounts` reports
                // zero or more, and showing one row for an N-account state
                // would be the panel telling the user something untrue.
                ForEach(store.accounts) { account in
                    accountRow(account)
                }
                signInButton(title: "Add another account", prominent: false)
            }
        }
    }

    private func accountRow(_ account: GmsAccount) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "person.crop.circle.fill")
                .foregroundStyle(Color.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                Text(account.email)
                    .font(.callout)
                    .textSelection(.enabled)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !account.services.isEmpty {
                    Text(account.services)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Button("Sign Out…") {
                signOutTarget = account
                showSignOutConfirm = true
            }
            .disabled(store.isBusy)
        }
    }

    /// `prominent` is the only visual difference between "you have no account,
    /// this is the thing to do" and "you already have one, here is a second".
    @ViewBuilder
    private func signInButton(title: String, prominent: Bool) -> some View {
        HStack {
            if prominent {
                Button(title) { store.signIn(runtimePath: model.runtimePath()) }
                    .buttonStyle(.borderedProminent)
                    .disabled(store.isBusy)
            } else {
                Button(title) { store.signIn(runtimePath: model.runtimePath()) }
                    .controlSize(.small)
                    .disabled(store.isBusy)
            }
            Spacer()
        }
    }

    /// The blocking state. It gets its own explanation because the thing the
    /// user has to do next is in ANOTHER window, and an unexplained spinner
    /// here reads as a hang rather than as "your turn".
    private var signingInRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Waiting for you to finish signing in in the Google window…")
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("That window is Google's own sign-in page. Mac.apk does not "
               + "read what you type into it.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            // Without this the in-progress state has no exit: the runtime is
            // blocked on a human, so nothing times out, and a user who changed
            // their mind would be left looking at a spinner. Terminating the
            // child is what closes Google's window.
            Button("Cancel sign-in") { store.cancelSignIn() }
                .controlSize(.small)
        }
    }

    private func busyRow(_ text: String) -> some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text(text)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
    }

    /// Google's own wording, verbatim. Paraphrasing it would throw away the
    /// one piece of information that says WHICH of a dozen sign-in failures
    /// happened, and that string is usually the whole diagnostic.
    private func errorRow(_ message: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.orange)
            Text(message)
                .font(.caption)
                .foregroundStyle(Color.red)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: Privacy
    //
    // Three claims, each one true of the implementation as it stands: the page
    // is Google's, the token is a 0600 file on this Mac, and sign-out deletes
    // it. Nothing here promises anything the runtime does not actually do.
    private var privacySection: some View {
        VStack(alignment: .leading, spacing: 6) {
            privacyLine("You sign in on Google's own page, in a window Mac.apk "
                      + "does not read. Mac.apk never sees your password.")
            privacyLine("The token Google returns is stored on this Mac only, "
                      + "in a file that only your macOS account can read.")
            privacyLine("Signing out deletes that token from this Mac.")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    private func privacyLine(_ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: "lock.fill")
                .font(.system(size: 9))
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: Diagnostics (dev menu only)

    private var diagnosticsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("macapk-runtime --gms status")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(showDiagnostics ? "Hide" : "Show") {
                    showDiagnostics.toggle()
                    if showDiagnostics { loadDiagnostics() }
                }
                .controlSize(.small)
                .disabled(model.runtimePath() == nil)
            }
            if showDiagnostics {
                ScrollView {
                    Text(diagnostics.isEmpty ? "reading…" : diagnostics)
                        .font(.system(.caption2, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 120)
            }
        }
    }

    /// Off the main thread like every other `--gms` call: answering means
    /// starting the runtime, which is not instant.
    private func loadDiagnostics() {
        guard let rt = model.runtimePath() else { return }
        diagnostics = ""
        DispatchQueue.global(qos: .userInitiated).async {
            let text = GmsCLI.statusDump(runtimePath: rt)
            Task { @MainActor in diagnostics = text }
        }
    }
}
