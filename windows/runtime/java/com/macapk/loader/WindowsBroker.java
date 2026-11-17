// com.macapk.loader.WindowsBroker -- the system broker's process on Windows.
//
// On the Mac launchd starts the broker on the first lookup of its Mach name. On
// Windows nothing does, so the first app that looks it up and finds nothing
// starts `winapk-runtime --system` itself and waits for the name to answer. Two
// apps doing that at once start two; the second finds the name taken
// (FILE_FLAG_FIRST_PIPE_INSTANCE, and the Local\ mutex runtime_main claims
// first) and exits, so there is one broker per user session. The broker exits
// on its own when no app has been open for a while (BrokerIdle).
//
// The runtime, data folder, JDK and jars come from the system properties
// windows/host/runtime_main.cpp sets for every JVM it creates.
package com.macapk.loader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class WindowsBroker {
    private WindowsBroker() {}

    /** How long an app waits for a broker it started to answer. A cold broker
     *  is a JVM boot plus reading the installed apps' manifests. */
    static final long START_WAIT_MS = Long.getLong("winapk.broker.startWaitMs", 30_000L);

    /** winapk-runtime with this process's --data, --jdk and --lib: what an app
     *  or the broker is started with (the caller adds --apk or --system). */
    public static List<String> runtimeCommand() {
        List<String> cmd = new ArrayList<>();
        String exe = System.getProperty("winapk.runtime.exe");
        if (exe == null || exe.isEmpty()) throw new IllegalStateException("winapk.runtime.exe is not set");
        cmd.add(exe);
        add(cmd, "--data", System.getProperty("winapk.data"));
        add(cmd, "--jdk", System.getProperty("winapk.jdk"));
        add(cmd, "--lib", System.getProperty("winapk.lib"));
        return cmd;
    }

    private static void add(List<String> cmd, String flag, String value) {
        if (value != null && !value.isEmpty()) { cmd.add(flag); cmd.add(value); }
    }

    /** Start the broker (never waits for it). Its output goes to
     *  logs\system.log in the data folder. */
    static void start() {
        try {
            List<String> cmd = runtimeCommand();
            cmd.add("--system");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // The broker gets no app's identity or window from this process.
            for (String k : new String[] { "MACAPK_SERVICE_HOST", "MACAPK_PROCESS_NAME", "MACAPK_SECONDARY_PROCESS",
                    "WINAPK_READY_EVENT", "WINAPK_HEADLESS_EXIT_AFTER_FRAMES", "WINAPK_HEADLESS_FRAME" }) {
                pb.environment().remove(k);
            }
            String data = System.getProperty("winapk.data");
            pb.redirectErrorStream(true);
            if (data != null && !data.isEmpty()) {
                File logs = new File(data, "logs");
                logs.mkdirs();
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logs, "system.log")));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }
            Process p = pb.start();
            NativeLog.i("system", "started the system broker (pid " + p.pid() + ")");
        } catch (Throwable t) {
            NativeLog.w("system", "could not start the system broker: " + t);
        }
    }
}
