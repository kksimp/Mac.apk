#!/usr/bin/env python3
"""Compatibility sweep: boot every app of the corpus headless and report what
blocks them, ranked by how many apps each problem affects.

    sweep.py --runtime <staged runtime dir> --out <report dir>
             [--jdk <dir>] [--apps apps.txt] [--apks <cache dir>]
             [--frames 60] [--timeout 180] [--launcher wine] [--only pkg,...]
             [--shard I/N] [--only-from smoke.txt] [--skip pkg,...] [--require-drew]
    sweep.py --merge <report dir>... --out <report dir>

--shard I/N runs only every Nth app starting at the Ith (1-based), so N
machines share the corpus; --merge joins their report directories (report.json,
logs, frames) into one report, as if one machine had run them all.
--only-from takes the packages to run from a file (smoke.txt), and
--require-drew makes the sweep a test: an app that does not draw is run once
more, and if it still does not the exit code is 1. --skip leaves packages out
(the ARM64 smoke test skips the libGDX game an ARM64 PC refuses).

For each app it runs winapk-runtime with WINAPK_HEADLESS=1 and a fresh data
directory, waits for --frames presented frames (or the timeout), and keeps the
log, each line led by the seconds since the start, and the last frame. apps.txt lists "package versionCode", with the APK's
file name as a third field when it is not <package>_<versionCode>.apk. Each run
gets one status:

    drew      ran its frames, and the last frame has real content
    blank     ran its frames, but the last frame is one or two colors
    no-frame  ran until the timeout without presenting a frame
    crashed   the process ended on its own before that (exit code != 0)

It then collects, per app, the host natives that were called while still
stubbed, the exceptions Java logged, a HotSpot crash file if there was one,
and for the app's own native code the system functions it called that the
shim does not have yet and the libraries that could not be loaded, and writes
report.json and report.md with the problems sorted by the number of apps they
appear in. That ranking is what decides what to fix next:
a fix to the framework or the host helps every app the problem shows up in.

Paths given to the runtime are made Windows paths with `winepath -w` when
--launcher is wine. MACAPK_JVM_OPTS from the environment is passed through
(run_app_wine.sh explains the one Wine needs).
"""

import argparse
import collections
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

STUB_RE = re.compile(r"native not implemented on Windows yet, returning 0: (\S+)")
# "[W] Win.apk.ActivityHost: onCreate threw java.lang.Foo: msg -- continuing"
THREW_RE = re.compile(r"threw ([\w.$]+(?:Exception|Error))(?::\s*(.*?))?(?: -- continuing)?$")
# A bare stack-trace head: "java.lang.IllegalStateException: msg"
TRACE_RE = re.compile(r"^([a-z][\w.]*\.[A-Z][\w$]*(?:Exception|Error))(?::\s*(.*))?$")
FRAMES_RE = re.compile(r"\[win\.apk\] (\d+) frames presented")
# An app's own native code (host/guest_libs.cpp): a system function the shim
# does not have yet, and a library that could not be loaded at all.
GUEST_MISSING_RE = re.compile(
    r"guest (?:called a system function|uses a system variable) not implemented on Windows yet, [^:]*: (\S+)")
GUEST_LOAD_RE = re.compile(r"\[win\.apk\] cannot load (\S+?\.so): (?:cannot load \"[^\"]*\": )?(.*)$")
# The time run_app puts in front of each log line.
STAMP_RE = re.compile(r"^ *\d+\.\d{3} ", re.M)


def read_list(path):
    """Package names, one per line, '#' starts a comment."""
    names = set()
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line:
            names.add(line)
    return names


def read_apps(path, only):
    apps = []
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split()
        pkg, version = fields[0], fields[1]
        if only and pkg not in only:
            continue
        # The file is <package>_<versionCode>.apk unless the line names it (a
        # few builds carry a commit hash in the name).
        apps.append((pkg, version, fields[2] if len(fields) > 2 else f"{pkg}_{version}.apk"))
    return apps


def fetch(pkg, version, name, cache):
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, f"{pkg}_{version}.apk")
    if os.path.exists(path):
        return path
    for base in ("repo", "archive"):
        try:
            tmp = path + ".part"
            urllib.request.urlretrieve(f"https://f-droid.org/{base}/{name}", tmp)
            os.replace(tmp, path)
            return path
        except Exception:
            continue
    return None


def win_path(path, launcher):
    path = os.path.abspath(path)
    if launcher and os.path.basename(launcher).startswith("wine"):
        out = subprocess.run([launcher, "winepath", "-w", path], capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    return path


def bmp_colors(path, limit=16):
    """Distinct colors among a grid of samples of a 32-bit BMP, capped at limit."""
    try:
        with open(path, "rb") as f:
            data = f.read()
        offset = struct.unpack_from("<I", data, 10)[0]
        width, height = struct.unpack_from("<ii", data, 18)
        height = abs(height)
        colors = set()
        for y in range(0, height, max(1, height // 64)):
            for x in range(0, width, max(1, width // 36)):
                i = offset + (y * width + x) * 4
                colors.add(data[i:i + 3])
                if len(colors) >= limit:
                    return len(colors)
        return len(colors)
    except Exception:
        return 0


def run_app(pkg, apk, args, out):
    log_path = os.path.join(out, "logs", f"{pkg}.log")
    frame_path = os.path.join(out, "frames", f"{pkg}.bmp")
    data_dir = os.path.join(out, "data", pkg)
    shutil.rmtree(data_dir, ignore_errors=True)
    os.makedirs(data_dir, exist_ok=True)
    exe = os.path.join(args.runtime, "bin", "winapk-runtime.exe")
    cmd = ([args.launcher] if args.launcher else []) + [
        exe, "--apk", win_path(apk, args.launcher), "--data", win_path(data_dir, args.launcher)]
    if args.jdk:
        cmd += ["--jdk", win_path(args.jdk, args.launcher)]
    env = dict(os.environ)
    env["WINAPK_HEADLESS"] = "1"
    env["WINAPK_HEADLESS_EXIT_AFTER_FRAMES"] = str(args.frames)
    env["WINAPK_HEADLESS_FRAME"] = win_path(frame_path, args.launcher)
    env.setdefault("WINEDEBUG", "-all")
    start = time.time()
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)

        # Each line gets the seconds since the start: the frame budget is a
        # time, and an app that misses it by a little (a slow first-run setup)
        # only shows where its time went with a clock beside its lines.
        def copy():
            try:
                for raw in proc.stdout:
                    log.write(f"{time.time() - start:8.3f} " + raw.decode("utf-8", "replace"))
                    log.flush()
            except (OSError, ValueError):
                pass  # the log was closed under a pipe something else still held

        reader = threading.Thread(target=copy, daemon=True)
        reader.start()
        try:
            code = proc.wait(timeout=args.timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            code = None
            timed_out = True
        # A process the runtime started may still hold the pipe; never wait on it.
        reader.join(timeout=10)
    if args.launcher and os.path.basename(args.launcher).startswith("wine"):
        # A killed wine leaves the JVM behind in the wineserver.
        subprocess.run(["wineserver", "-k"], capture_output=True)
    seconds = round(time.time() - start, 1)
    text = STAMP_RE.sub("", open(log_path, encoding="utf-8", errors="replace").read())
    return analyze(pkg, text, code, timed_out, seconds, frame_path, out)


def analyze(pkg, text, code, timed_out, seconds, frame_path, out):
    stubs = sorted(set(STUB_RE.findall(text)))
    guest_missing = sorted(set(GUEST_MISSING_RE.findall(text)))
    # Why a library failed, with the path cut to the file name; the reason is
    # what ranks (the same gap shows up in many apps).
    load_failures = sorted(set(f"{os.path.basename(lib.replace(chr(92), '/'))}: {why[:160]}"
                               for lib, why in GUEST_LOAD_RE.findall(text)))
    exceptions = collections.OrderedDict()
    for line in text.splitlines():
        line = line.strip()
        m = THREW_RE.search(line) or TRACE_RE.match(line)
        if m:
            name, msg = m.group(1), (m.group(2) or "").strip()
            exceptions.setdefault(name, msg[:200])
    frames = max([int(n) for n in FRAMES_RE.findall(text)] or [0])
    first_frame = "first frame presented" in text
    hs_err = "A fatal error has been detected by the Java Runtime Environment" in text
    if frames > 0 and code == 0:
        colors = bmp_colors(frame_path)
        status = "drew" if colors > 2 else "blank"
    elif timed_out:
        status = "no-frame"
        colors = bmp_colors(frame_path) if first_frame else 0
    else:
        status = "crashed"
        colors = 0
    return {
        "package": pkg,
        "status": status,
        "exit_code": code,
        "seconds": seconds,
        "first_frame": first_frame,
        "frame_colors": colors,
        "jvm_crash": hs_err,
        "stubbed_natives": stubs,
        "guest_missing": guest_missing,
        "guest_load_failures": load_failures,
        "exceptions": exceptions,
    }


def rank(results, key):
    counter = collections.Counter()
    sample = {}
    for r in results:
        items = r[key]
        names = items.keys() if isinstance(items, dict) else items
        for name in names:
            counter[name] += 1
            if isinstance(items, dict) and name not in sample:
                sample[name] = (r["package"], items[name])
    return counter, sample


def write_report(results, out):
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    by_status = collections.Counter(r["status"] for r in results)
    lines = ["# Win.apk compatibility sweep", ""]
    lines.append(f"{len(results)} apps: " + ", ".join(
        f"{by_status[s]} {s}" for s in ("drew", "blank", "no-frame", "crashed") if by_status[s]))
    lines += ["", "| App | Status | Seconds | Stubbed natives | Exceptions |", "|---|---|---|---|---|"]
    for r in sorted(results, key=lambda r: (r["status"], r["package"])):
        lines.append(f"| {r['package']} | {r['status']}{' (JVM crash)' if r['jvm_crash'] else ''} | "
                     f"{r['seconds']} | {len(r['stubbed_natives'])} | {len(r['exceptions'])} |")
    stubs, _ = rank(results, "stubbed_natives")
    lines += ["", "## Stubbed natives, by number of apps that called them", "",
              "| Apps | Native |", "|---|---|"]
    lines += [f"| {n} | `{name}` |" for name, n in stubs.most_common()]
    missing, _ = rank(results, "guest_missing")
    lines += ["", "## System functions the app's native code called, missing on Windows", "",
              "| Apps | Function |", "|---|---|"]
    lines += [f"| {n} | `{name}` |" for name, n in missing.most_common()]
    reasons = collections.Counter()
    for r in results:
        for f in set(f.split(": ", 1)[1] for f in r["guest_load_failures"]):
            reasons[f] += 1
    lines += ["", "## Native libraries that could not be loaded, by reason", "", "| Apps | Reason |", "|---|---|"]
    lines += [f"| {n} | {why.replace('|', '/')} |" for why, n in reasons.most_common()]
    exc, sample = rank(results, "exceptions")
    lines += ["", "## Exceptions, by number of apps", "", "| Apps | Exception | First seen in |", "|---|---|---|"]
    for name, n in exc.most_common():
        pkg, msg = sample[name]
        detail = f"{pkg}: {msg}" if msg else pkg
        lines.append(f"| {n} | `{name}` | {detail.replace('|', '/')} |")
    with open(os.path.join(out, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return "\n".join(lines)


def merge(parts, out):
    results = []
    for part in parts:
        path = os.path.join(part, "report.json")
        if not os.path.exists(path):
            print(f"[sweep] {part}: no report.json, skipped", flush=True)
            continue
        results += json.load(open(path, encoding="utf-8"))
        for sub in ("logs", "frames"):
            src = os.path.join(part, sub)
            if os.path.isdir(src):
                shutil.copytree(src, os.path.join(out, sub), dirs_exist_ok=True)
    print(write_report(results, out))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--runtime")
    ap.add_argument("--out", required=True)
    ap.add_argument("--jdk")
    ap.add_argument("--apps", default=os.path.join(HERE, "apps.txt"))
    ap.add_argument("--apks", default=None, help="APK cache (default: <out>/apks)")
    ap.add_argument("--frames", type=int, default=60)
    ap.add_argument("--timeout", type=int, default=180)
    ap.add_argument("--launcher", default=None)
    ap.add_argument("--only", default="")
    ap.add_argument("--shard", default="1/1", help="I/N: every Nth app from the Ith (1-based)")
    ap.add_argument("--merge", nargs="+", metavar="DIR", help="join these report directories into --out")
    ap.add_argument("--only-from", metavar="FILE", help="run only the packages this file lists (smoke.txt)")
    ap.add_argument("--skip", default="", help="packages not to run, comma-separated: ones this host cannot run")
    ap.add_argument("--require-drew", action="store_true",
                    help="exit 1 when an app does not draw, after one more try")
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    if args.merge:
        os.makedirs(out, exist_ok=True)
        return merge(args.merge, out)
    if not args.runtime:
        ap.error("--runtime is required unless --merge is given")
    index, count = (int(n) for n in args.shard.split("/"))
    if not 1 <= index <= count:
        ap.error("--shard is I/N with 1 <= I <= N")
    for sub in ("logs", "frames", "data"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    cache = args.apks or os.path.join(out, "apks")
    only = set(filter(None, args.only.split(",")))
    if args.only_from:
        only |= read_list(args.only_from)
    apps = read_apps(args.apps, only)
    missing = only - {pkg for pkg, _, _ in apps}
    if missing:
        # A smoke app that is not in the corpus would silently not run.
        ap.error("not in " + args.apps + ": " + ", ".join(sorted(missing)))
    skip = set(filter(None, args.skip.split(",")))
    apps = [a for a in apps if a[0] not in skip]
    results = []
    for pkg, version, name in apps[index - 1::count]:
        apk = fetch(pkg, version, name, cache)
        if not apk:
            print(f"[sweep] {pkg}: download failed", flush=True)
            continue
        r = run_app(pkg, apk, args, out)
        print(f"[sweep] {pkg}: {r['status']} in {r['seconds']} s, {len(r['stubbed_natives'])} stubbed natives, "
              f"{len(r['exceptions'])} exceptions, {len(r['guest_missing'])} missing system functions, "
              f"{len(r['guest_load_failures'])} libraries not loaded", flush=True)
        if args.require_drew and r["status"] != "drew":
            # One more try tells a slow start on a busy machine from a break.
            print(f"[sweep] {pkg}: {r['status']}, trying once more", flush=True)
            shutil.rmtree(os.path.join(out, "data", pkg), ignore_errors=True)
            r = run_app(pkg, apk, args, out)
            print(f"[sweep] {pkg}: {r['status']} in {r['seconds']} s", flush=True)
        results.append(r)
        shutil.rmtree(os.path.join(out, "data", pkg), ignore_errors=True)
    print(write_report(results, out))
    if args.require_drew:
        failed = [f"{r['package']} ({r['status']})" for r in results if r["status"] != "drew"]
        missing = len(apps[index - 1::count]) - len(results)
        if failed or missing:
            print("[sweep] FAILED: " + ", ".join(failed + ([f"{missing} not downloaded"] if missing else [])),
                  flush=True)
            return 1
        print(f"[sweep] OK: all {len(results)} apps drew", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
