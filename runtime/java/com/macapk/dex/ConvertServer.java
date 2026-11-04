// ConvertServer -- one warm converter process shared by several conversions.
//
//     java -cp ... Main --server <dir> [options] <input> <out.jar>
//
// WHY. A cold JVM spends a large share of a conversion warming up: on 15k
// classes the JIT compilers burned 14.5 s of CPU (C2 12.2 s) during a 7.4 s
// wall-clock run, and the same conversion in an already-warm JVM took a third
// of the time. A caller that converts several apps at once (a batch install, or
// several apps that start together) used to pay that once per app, in parallel
// JVMs that fought each other for cores: N worker pools, N JIT compilers and N
// garbage collectors on one machine. With --server they share ONE process that
// stays warm and converts them one after another, each on the full pool.
//
// THE CONTRACT DOES NOT CHANGE. The --server process is still the caller's
// converter: it parses the input itself, so the parse verdict, the
// <out.jar>.parsed marker and an invalid-input exit code arrive exactly when
// they would without --server, then it waits for the shared process to write
// <out.jar>, replays that conversion's stderr as its own, and exits with its
// exit code. A caller can kill it, wait for it, or read its output as before.
// If the shared process cannot be started or dies, the conversion runs
// in-process instead, so --server can only change how long it takes.
//
// THE SPOOL. <dir>/<settings hash>/ holds one file per queued job, a lock file
// the running server holds for its whole life, and the server's own log. The
// settings hash covers everything that can change a conversion's output and is
// not on the command line -- the JVM, its flags, the classpath (paths, sizes
// and timestamps, so a rebuilt converter gets a new server), DEX2JVM_*
// environment variables and dex2jvm.* system properties -- so a job is only
// ever converted by a server that would produce the bytes its own process
// would. The command line itself travels with the job and is re-applied for
// it, with Options reset in between.
//
// Jobs run one at a time, smallest input first among those waiting: the
// shortest-job-first order gets the most callers their jar soonest (measured: a
// small app queued behind a large one waited 20 s for a conversion that takes
// 1 s), the large job still finishes when it would have, and one job at a time
// keeps peak memory at one session's worth.

package com.macapk.dex;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ConvertServer {

    private ConvertServer() {}

    /** How long an idle server waits for another job before it exits. */
    private static final long IDLE_EXIT_MS = 15_000;

    /** How often the server looks for jobs, and a client for its result. */
    private static final long POLL_MS = 50;

    /** A client checks that the server it is waiting on is alive this often. */
    private static final long LIVENESS_MS = 1_000;

    private static final String LOCK = "server.lock";
    private static final String JOB = ".job";
    private static final String RUN = ".run";
    private static final String EXIT = ".exit";
    private static final String LOG = ".log";

    // ==================================================================
    // Client: the process the caller started
    // ==================================================================

    /**
     * Runs {@code job} through the shared server for {@code job.server}.
     * {@code args} is the command line that produced {@code job}; it is what
     * the server re-applies. Returns the exit code to exit with.
     */
    static int submit(Main.Job job, String[] args) {
        long t0 = System.currentTimeMillis();
        // Parse here, not in the server: the caller may be waiting on the
        // verdict (exit code or .parsed marker), and the server may be busy
        // with another app for a while. Parsing is the cheap part. No library
        // yet: the verdict does not depend on it, and the server picks its own.
        int[] exit = { 0 };
        DexConverter.Session session = Main.parse(job, exit);
        if (session == null) return exit[0];
        Path parsedMarker = Paths.get(job.out + ".parsed");
        if (Options.parsedMarker) {
            try { Files.createFile(parsedMarker); } catch (IOException ignored) {}
        }
        try {
            // Not needed while the server converts; let the heap go.
            session = null;
            System.gc();
            Integer code = null;
            try {
                code = viaServer(Paths.get(job.server), job, withoutServer(args));
            } catch (IOException | RuntimeException e) {
                System.err.println("[" + Options.logTag + "] shared converter unavailable ("
                        + e + "); converting in this process");
            }
            if (code != null) return code;
            // Re-parsed after the library is chosen: a session reads the
            // library when it is opened.
            Main.applyLibrary(job);
            session = Main.parse(job, exit);
            if (session == null) return exit[0];
            return Main.convertParsed(session, job.out, t0);
        } finally {
            try { Files.deleteIfExists(parsedMarker); } catch (IOException ignored) {}
        }
    }

    /**
     * Queues the job and waits for its result. Null means the server could not
     * convert it (it died with the job claimed) and the caller should; an
     * exception means the spool itself is unusable.
     */
    private static Integer viaServer(Path root, Main.Job job, List<String> args)
            throws IOException {
        Path dir = root.resolve(settingsHash());
        Files.createDirectories(dir);
        String id = ProcessHandle.current().pid() + "-" + System.nanoTime();
        Path jobFile = dir.resolve(id + JOB);
        Path runFile = dir.resolve(id + RUN);
        Path exitFile = dir.resolve(id + EXIT);
        Path logFile = dir.resolve(id + LOG);

        StringBuilder sb = new StringBuilder();
        sb.append(ProcessHandle.current().pid()).append('\n');
        sb.append(sizeOf(job.in)).append('\n');
        for (String a : args) sb.append(a).append('\n');
        Path tmp = dir.resolve(id + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, jobFile, StandardCopyOption.ATOMIC_MOVE);

        try {
            ensureServer(dir);
            long lastCheck = System.currentTimeMillis();
            while (!Files.exists(exitFile)) {
                long now = System.currentTimeMillis();
                if (now - lastCheck >= LIVENESS_MS) {
                    lastCheck = now;
                    if (!serverRunning(dir) && !Files.exists(exitFile)) {
                        if (Files.exists(jobFile)) {
                            // Still queued, and the server went away (idle
                            // exit racing our submit, or a crash): start one.
                            ensureServer(dir);
                        } else {
                            // Claimed by a server that is now gone.
                            Files.deleteIfExists(runFile);
                            replay(logFile);
                            System.err.println("[" + Options.logTag + "] shared converter"
                                    + " exited during this conversion; converting in"
                                    + " this process");
                            return null;
                        }
                    }
                }
                sleep(POLL_MS);
            }
            int code = Integer.parseInt(new String(Files.readAllBytes(exitFile),
                    StandardCharsets.UTF_8).trim());
            replay(logFile);
            return code;
        } finally {
            Files.deleteIfExists(jobFile);
            Files.deleteIfExists(exitFile);
            Files.deleteIfExists(logFile);
        }
    }

    /** {@code args} without the --server option, which the server must not see. */
    private static List<String> withoutServer(String[] args) {
        List<String> out = new ArrayList<>(Arrays.asList(args));
        int i = out.indexOf("--server");
        if (i >= 0) { out.remove(i); out.remove(i); }
        return out;
    }

    /** Copies a job's captured stderr to ours, then deletes it. */
    private static void replay(Path log) throws IOException {
        if (!Files.exists(log)) return;
        System.err.write(Files.readAllBytes(log));
        System.err.flush();
        Files.deleteIfExists(log);
    }

    /** Starts a server for {@code dir} unless one is running. A lost race just
     *  means the extra server finds the lock taken and exits. */
    private static void ensureServer(Path dir) throws IOException {
        if (serverRunning(dir)) return;
        // Bounded: a running server keeps writing to the file it opened.
        Path serverLog = dir.resolve("server.log");
        if (sizeOf(serverLog) > (1 << 20)) Files.deleteIfExists(serverLog);
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command()
                .orElse(Paths.get(System.getProperty("java.home"), "bin", "java").toString()));
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Main.class.getName());
        cmd.add("--serve");
        cmd.add(dir.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(serverLog.toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.start();
    }

    /** True while some process holds {@code dir}'s server lock. */
    private static boolean serverRunning(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir.resolve(LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;   // this very JVM holds it
        }
    }

    /**
     * Hash of everything outside the command line that can change what this
     * process would emit. Servers are keyed by it; see the file header.
     */
    private static String settingsHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(ProcessHandle.current().info().command().orElse("")).append('\n');
            sb.append(System.getProperty("java.home")).append('\n');
            for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                sb.append("jvm ").append(a).append('\n');
            }
            for (String e : System.getProperty("java.class.path").split(
                    java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                Path p = Paths.get(e);
                long size = -1, mtime = -1;
                try {
                    size = Files.size(p);
                    mtime = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ignored) {}
                sb.append("cp ").append(e).append(' ').append(size).append(' ')
                  .append(mtime).append('\n');
            }
            for (Map.Entry<String, String> e : new TreeMap<>(System.getenv()).entrySet()) {
                if (e.getKey().startsWith("DEX2JVM_")) {
                    sb.append("env ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
            }
            TreeMap<String, String> props = new TreeMap<>();
            for (String k : System.getProperties().stringPropertyNames()) {
                if (k.startsWith("dex2jvm.")) props.put(k, System.getProperty(k));
            }
            for (Map.Entry<String, String> e : props.entrySet()) {
                sb.append("prop ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            byte[] h = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================================================================
    // Server: the shared process
    // ==================================================================

    /** Serves {@code dir} until idle. Returns the process exit code. */
    static int serve(Path dir) {
        Object[] defaults = Options.snapshot();
        PrintStream realErr = System.err;
        try {
            Files.createDirectories(dir);
            FileChannel ch = FileChannel.open(dir.resolve(LOCK),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = ch.tryLock();
            if (lock == null) return 0;   // another server has it
            realErr.println("[" + Options.logTag + "] shared converter pid "
                    + ProcessHandle.current().pid() + " serving " + dir);
            long idleSince = System.currentTimeMillis();
            for (;;) {
                Path next = nextJob(dir);
                if (next == null) {
                    if (System.currentTimeMillis() - idleSince < IDLE_EXIT_MS) {
                        sleep(POLL_MS);
                        continue;
                    }
                    // Release BEFORE the final look: a client that queued a
                    // job and saw the lock still held is relying on this scan;
                    // one that sees it free starts a new server itself.
                    lock.release();
                    if (nextJob(dir) == null) break;
                    lock = ch.tryLock();
                    if (lock == null) break;   // a new server took over
                    idleSince = System.currentTimeMillis();
                    continue;
                }
                runClaimed(dir, next, defaults, realErr);
                sweepOrphans(dir);
                idleSince = System.currentTimeMillis();
            }
            ch.close();
            realErr.println("[" + Options.logTag + "] shared converter idle; exiting");
            return 0;
        } catch (IOException e) {
            realErr.println("[" + Options.logTag + "] shared converter: " + e);
            return 1;
        }
    }

    /**
     * Claims the smallest waiting job and returns its claimed (.run) path, or
     * null when there is none. Jobs whose client has died are discarded: no
     * one is waiting for them.
     */
    private static Path nextJob(Path dir) throws IOException {
        Path best = null;
        long bestSize = Long.MAX_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + JOB)) {
            for (Path p : ds) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;   // claimed or withdrawn meanwhile
                }
                if (lines.size() < 2) continue;
                long pid = Long.parseLong(lines.get(0).trim());
                if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    Files.deleteIfExists(p);
                    continue;
                }
                long size = Long.parseLong(lines.get(1).trim());
                // Smallest first; equal sizes in name order (pid, then time).
                if (best == null || size < bestSize || (size == bestSize
                        && p.getFileName().toString().compareTo(best.getFileName().toString()) < 0)) {
                    best = p;
                    bestSize = size;
                }
            }
        }
        if (best == null) return null;
        String n = best.getFileName().toString();
        Path run = dir.resolve(n.substring(0, n.length() - JOB.length()) + RUN);
        try {
            Files.move(best, run, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return null;   // withdrawn by its client; look again next time
        }
        return run;
    }

    /** Deletes results nobody will collect: their client was killed. */
    private static void sweepOrphans(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + EXIT)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                int dash = n.indexOf('-');
                if (dash <= 0) continue;
                long pid;
                try {
                    pid = Long.parseLong(n.substring(0, dash));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
                Files.deleteIfExists(p);
                Files.deleteIfExists(dir.resolve(n.substring(0, n.length() - EXIT.length()) + LOG));
            }
        }
    }

    /** Converts one claimed job, writing its log and then its exit code. */
    private static void runClaimed(Path dir, Path run, Object[] defaults, PrintStream realErr)
            throws IOException {
        String n = run.getFileName().toString();
        String id = n.substring(0, n.length() - RUN.length());
        List<String> lines = Files.readAllLines(run, StandardCharsets.UTF_8);
        String[] args = lines.subList(2, lines.size()).toArray(new String[0]);
        int code;
        try (PrintStream log = new PrintStream(Files.newOutputStream(dir.resolve(id + LOG)),
                true, StandardCharsets.UTF_8)) {
            System.setErr(log);
            try {
                Options.restore(defaults);
                Main.Job job = Main.parseArgs(args);
                // The client owns the marker: it parsed the input and removes
                // the marker when it exits.
                Options.parsedMarker = false;
                code = Main.runJob(job);
            } catch (Throwable t) {
                log.println("[" + Options.logTag + "] CONVERT FAILED in the shared converter: " + t);
                t.printStackTrace(log);
                code = Main.EXIT_CONVERT_FAILED;
            } finally {
                System.setErr(realErr);
                Options.restore(defaults);
            }
        }
        Path tmp = dir.resolve(id + ".exit.tmp");
        Files.write(tmp, Integer.toString(code).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, dir.resolve(id + EXIT), StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(run);
        realErr.println("[" + Options.logTag + "] job " + id + " exit " + code);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
