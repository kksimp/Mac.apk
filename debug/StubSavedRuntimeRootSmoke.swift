// StubSavedRuntimeRootSmoke.swift
//
// Regression for "an app's saved MACAPK_RUNTIME_ROOT selects the runtime on
// every launch path" (launcher/AppStubMain.swift step 2b,
// RuntimeInstall.adoptSavedRuntimeRoot). Links the REAL launcher/InstallCore.swift
// and drives the two functions the stub calls before it resolves the runtime.
// Nothing here touches the real registry or the staged runtime: the registry
// is a value built in memory (and one decoded from JSON), the runtime is a
// stand-in under a scratch directory, and ensureStaged() is only ever called
// with an override in force -- the branch that stages nothing.
//
//   debug/check_stub_saved_runtime_root.sh
//   exit status 0 = every case passed

import Foundation

@main
enum StubSavedRuntimeRootSmoke {
    static var failed = 0
    static var passed = 0

    static func check(_ name: String, _ ok: Bool, _ detail: String = "") {
        if ok {
            passed += 1
            print("PASS  \(name)")
        } else {
            failed += 1
            print("FAIL  \(name)\(detail.isEmpty ? "" : " -- \(detail)")")
        }
    }

    static func main() {
        let fm = FileManager.default
        let work = CommandLine.arguments.count > 1
            ? URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
            : fm.temporaryDirectory.appendingPathComponent("stub-saved-runtime-root", isDirectory: true)

        // A runtime root of the app's own, as an override names one: an
        // executable where RuntimeInstall looks, and a build id of its own.
        // The space in the name is on purpose: these are user-chosen paths.
        let root = work.appendingPathComponent("repaired runtime", isDirectory: true)
        let resources = root.appendingPathComponent("Resources", isDirectory: true)
        let binary = resources.appendingPathComponent("macapk-runtime")
        try! fm.createDirectory(at: resources, withIntermediateDirectories: true)
        try! "#!/bin/sh\nexit 0\n".write(to: binary, atomically: true, encoding: .utf8)
        chmod(binary.path, 0o755)
        let buildId = "CONVERTER_ID=feedfacefeedface\nJAVA_ID=feedfacefeedface\nNATIVE_ID=0badcafe0badcafe\n"
        try! buildId.write(to: resources.appendingPathComponent("build-id.txt"), atomically: true, encoding: .utf8)

        // --- 1. The lookup is by row identity (dirName), and is only a lookup.
        let registry = Registry(apps: [
            AppRecord(package: "org.example.a", label: "A", dirName: "org.example.a_1_0",
                      version: "1.0", versionName: "1.0", versionCode: 1,
                      env: ["MACAPK_RUNTIME_ROOT": root.path, "MACAPK_JVM_OPTS": "-Xmx2g"]),
            AppRecord(package: "org.example.b", label: "B", dirName: "org.example.b_2_0",
                      version: "2.0", versionName: "2.0", versionCode: 2),
        ])
        let saved = registry.savedEnvironment(dirName: "org.example.a_1_0")
        check("saved env of the row, every key",
              saved == ["MACAPK_RUNTIME_ROOT": root.path, "MACAPK_JVM_OPTS": "-Xmx2g"])
        check("a row without overrides reads as empty",
              registry.savedEnvironment(dirName: "org.example.b_2_0").isEmpty)
        check("a row that is not there reads as empty",
              registry.savedEnvironment(dirName: "org.example.a_9_9").isEmpty)
        check("a bundle from before dirName existed reads as empty",
              registry.savedEnvironment(dirName: nil).isEmpty)

        // The same answer from the file format the stub really reads.
        let json = """
        {
          "schema": 2,
          "settings": {},
          "apps": [
            { "package": "org.example.a", "label": "A", "dirName": "org.example.a_1_0",
              "version": "1.0", "versionName": "1.0", "versionCode": 1, "source": "gui",
              "state": "installed",
              "env": { "MACAPK_RUNTIME_ROOT": \(jsonString(root.path)), "MACAPK_PROCESS_SPLIT": "0" } }
          ]
        }
        """
        let decoded = try? JSONDecoder().decode(Registry.self, from: Data(json.utf8))
        check("decoded from the registry file format",
              decoded?.savedEnvironment(dirName: "org.example.a_1_0")
                  == ["MACAPK_RUNTIME_ROOT": root.path, "MACAPK_PROCESS_SPLIT": "0"],
              "\(String(describing: decoded?.savedEnvironment(dirName: "org.example.a_1_0")))")

        // --- 2. Nothing is exported until the stub does it.
        unsetenv("MACAPK_RUNTIME_ROOT")
        check("reading does not export anything",
              ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"] == nil)

        // --- 3. The saved root, adopted: the resolution follows it.
        check("adopting returns the root", RuntimeInstall.adoptSavedRuntimeRoot(saved) == root.path)
        check("adopting exports MACAPK_RUNTIME_ROOT",
              ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"] == root.path)
        check("adopting exports that one key only",
              ProcessInfo.processInfo.environment["MACAPK_JVM_OPTS"] == nil)
        check("resolveRuntime() answers the override",
              RuntimeInstall.resolveRuntime()?.path == binary.path,
              ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"] ?? "nil")
        var lines: [String] = []
        let staged = RuntimeInstall.ensureStaged(extraSources: [], log: { lines.append($0) })
        check("ensureStaged() answers the override",
              staged?.path == binary.path, "\(String(describing: staged?.path))")
        check("ensureStaged() names the override in its log, and stages nothing",
              lines.contains { $0.contains("MACAPK_RUNTIME_ROOT=") && $0.contains(binary.path) }
                  && !lines.contains { $0.contains("staging") },
              lines.joined(separator: " | "))
        let contentsRoot = binary.deletingLastPathComponent().deletingLastPathComponent()
        check("the override carries its own identity, not the staged one's",
              RuntimeInstall.buildId(at: contentsRoot)?.contains("JAVA_ID=feedfacefeedface") == true)

        // --- 4. A saved value replaces one inherited from the environment,
        //        which is what step 8 used to do a moment too late.
        setenv("MACAPK_RUNTIME_ROOT", "/inherited/from/the/environment", 1)
        _ = RuntimeInstall.adoptSavedRuntimeRoot(saved)
        check("a saved root replaces an inherited one",
              ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"] == root.path)

        // --- 5. Rows that name no runtime leave the environment alone.
        setenv("MACAPK_RUNTIME_ROOT", "/inherited/from/the/environment", 1)
        check("no MACAPK_RUNTIME_ROOT in the row: nothing adopted",
              RuntimeInstall.adoptSavedRuntimeRoot(["MACAPK_JVM_OPTS": "-Xmx2g"]) == nil)
        check("an empty MACAPK_RUNTIME_ROOT: nothing adopted",
              RuntimeInstall.adoptSavedRuntimeRoot(["MACAPK_RUNTIME_ROOT": ""]) == nil)
        check("an empty row: nothing adopted", RuntimeInstall.adoptSavedRuntimeRoot([:]) == nil)
        check("... and the inherited value is still there",
              ProcessInfo.processInfo.environment["MACAPK_RUNTIME_ROOT"] == "/inherited/from/the/environment")

        // --- 6. An override that names no runtime fails as itself. It must not
        //        quietly put the app back on the staged runtime.
        let nowhere = work.appendingPathComponent("nowhere", isDirectory: true).path
        check("a root that is not there is adopted all the same",
              RuntimeInstall.adoptSavedRuntimeRoot(["MACAPK_RUNTIME_ROOT": nowhere]) == nowhere)
        check("... and resolves to nothing", RuntimeInstall.resolveRuntime() == nil)
        lines = []
        check("... ensureStaged() returns nil",
              RuntimeInstall.ensureStaged(extraSources: [], log: { lines.append($0) }) == nil)
        check("... and says why", lines.contains { $0.contains("no executable runtime there") },
              lines.joined(separator: " | "))

        print("\(passed) passed, \(failed) failed")
        exit(failed == 0 ? 0 : 1)
    }
}

/// A JSON string literal for a path (quotes and backslashes escaped).
private func jsonString(_ s: String) -> String {
    let data = (try? JSONEncoder().encode(s)) ?? Data("\"?\"".utf8)
    return String(decoding: data, as: UTF8.self)
}
