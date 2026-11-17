#!/usr/bin/env python3
"""Start-time benchmark: how long apps take to their first frame, and the
memory they hold then, under several configurations (issue #70).

    startbench.py --runtime <staged runtime> --out <dir> --apks <cache>
                  --config NAME=JRE[,VAR=VALUE...][,@runtime=<dir>][,@wait=1] [--config ...]
                  [--only-from smoke.txt | --only pkg,...] [--warm 3] [--frames 150]

Each configuration gets its own data folder per app, so its first start is a
real first start (install, DEX conversion, and the framework archive's
recording when the runtime makes one; @wait=1 then waits for
<data>/cds/framework-*.jsa before the warm starts). Then come --warm starts per
configuration, taken in turns (A, B, C, A, B, C, ...) so a slow minute of the
machine hits them all alike. @runtime picks another staged runtime for a
configuration (main's, to compare with). Every run is headless and ends
--frames display frames after its first frame; its last frame must have
content ("drew"), as in the smoke test.

The time is measured from outside, the same way for any runtime: from
starting the process to its "first frame presented" line. At that line the
process's working set and private bytes (commit) are read. The runtime's own
"startup:" lines (cds.h), when it has them, are kept too. report.json and
report.md go to --out.
"""

import argparse
import ctypes
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sweep  # noqa: E402  (read_apps, read_list, fetch, bmp_colors)

JVM_RE = re.compile(r"\[win\.apk\] startup: JVM created at ([\d.]+) s; class-data sharing (\w+)")
OWN_FIRST_RE = re.compile(r"\[win\.apk\] startup: first frame at ([\d.]+) s")


class Counters(ctypes.Structure):
    _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
                ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t),
                ("PrivateUsage", ctypes.c_size_t)]


def memory(pid):
    """(working set, private bytes) of a process in MB, or (None, None)."""
    try:
        k32 = ctypes.WinDLL("kernel32")
        h = k32.OpenProcess(0x1000 | 0x0010, False, pid)  # QUERY_LIMITED_INFORMATION | VM_READ
        if not h:
            return None, None
        c = Counters()
        c.cb = ctypes.sizeof(c)
        ok = k32.K32GetProcessMemoryInfo(h, ctypes.byref(c), c.cb)
        k32.CloseHandle(h)
        return (round(c.WorkingSetSize / 1048576), round(c.PrivateUsage / 1048576)) if ok else (None, None)
    except Exception:
        return None, None


def parse_config(spec):
    name, rest = spec.split("=", 1)
    parts = rest.split(",")
    cfg = {"name": name, "jdk": parts[0], "env": {}, "runtime": None, "wait": False}
    for p in parts[1:]:
        if not p:
            continue
        k, v = p.split("=", 1)
        if k == "@runtime":
            cfg["runtime"] = v
        elif k == "@wait":
            cfg["wait"] = v == "1"
        else:
            cfg["env"][k] = v
    return cfg


def run(cfg, apk, data, out, label, args):
    os.makedirs(data, exist_ok=True)
    log_path = os.path.join(out, "logs", f"{label}.log")
    frame = os.path.join(out, "frames", f"{label}.bmp")
    if os.path.exists(frame):
        os.remove(frame)
    exe = os.path.join(cfg["runtime"] or args.runtime, "bin", "winapk-runtime.exe")
    cmd = [exe, "--apk", apk, "--data", data]
    if cfg["jdk"]:
        cmd += ["--jdk", cfg["jdk"]]
    env = dict(os.environ)
    env.update({"WINAPK_HEADLESS": "1", "WINAPK_HEADLESS_EXIT_AFTER_FRAMES": str(args.frames),
                "WINAPK_HEADLESS_FRAME": frame})
    env.update(cfg["env"])
    seen = {}
    start = time.perf_counter()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)

    def copy():
        with open(log_path, "w", encoding="utf-8", errors="replace") as log:
            try:
                for raw in proc.stdout:
                    t = time.perf_counter() - start
                    line = raw.decode("utf-8", "replace")
                    if "first frame presented" in line and "first" not in seen:
                        seen["first"] = t
                        seen["mem"] = memory(proc.pid)
                    log.write(f"{t:8.3f} {line}")
            except (OSError, ValueError):
                pass

    reader = threading.Thread(target=copy, daemon=True)
    reader.start()
    try:
        code = proc.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        code = None
    reader.join(timeout=10)
    wall = round(time.perf_counter() - start, 2)
    text = open(log_path, encoding="utf-8", errors="replace").read()
    jvm = JVM_RE.search(text)
    own = OWN_FIRST_RE.search(text)
    ws, private = seen.get("mem", (None, None))
    drew = code == 0 and "first" in seen and sweep.bmp_colors(frame) > 2
    r = {"label": label, "exit": code, "wall": wall, "drew": drew,
         "first_frame": round(seen["first"], 3) if "first" in seen else None,
         "own_first_frame": float(own.group(1)) if own else None,
         "ws_mb": ws, "private_mb": private,
         "jvm": float(jvm.group(1)) if jvm else None, "sharing": jvm.group(2) if jvm else None,
         "cds_lines": sorted(set(re.findall(r"\[win\.apk\] cds[^\n]*", text)))[:6]}
    print(f"[bench] {label}: {'drew' if drew else 'NOT DRAWN'} exit={code} first frame {r['first_frame']} s "
          f"(own {r['own_first_frame']}), JVM {r['jvm']} s, sharing {r['sharing']}, ws {ws} MB, "
          f"private {private} MB, wall {wall} s", flush=True)
    return r


def disk(data):
    """Bytes of the archives under a data folder."""
    return {os.path.relpath(f, data): os.path.getsize(f) for f in glob.glob(os.path.join(data, "cds", "*.jsa"))}


def med(values):
    values = [v for v in values if v is not None]
    return round(statistics.median(values), 3) if values else None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--runtime", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--apks", required=True)
    ap.add_argument("--apps", default=os.path.join(HERE, "apps.txt"))
    ap.add_argument("--config", action="append", required=True)
    ap.add_argument("--only", default="")
    ap.add_argument("--only-from")
    ap.add_argument("--warm", type=int, default=3)
    ap.add_argument("--frames", type=int, default=150)
    ap.add_argument("--timeout", type=int, default=240)
    ap.add_argument("--archive-wait", type=int, default=180)
    args = ap.parse_args()
    out = os.path.abspath(args.out)
    for sub in ("logs", "frames", "data"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    configs = [parse_config(c) for c in args.config]
    only = set(filter(None, args.only.split(",")))
    if args.only_from:
        only |= sweep.read_list(args.only_from)
    results = []
    for pkg, version, name in sweep.read_apps(args.apps, only):
        apk = sweep.fetch(pkg, version, name, args.apks)
        if not apk:
            print(f"[bench] {pkg}: download failed", flush=True)
            continue
        apk = os.path.abspath(apk)
        per = {}
        for cfg in configs:
            data = os.path.join(out, "data", cfg["name"], pkg)
            shutil.rmtree(data, ignore_errors=True)
            cold = run(cfg, apk, data, out, f"{pkg}.{cfg['name']}.cold", args)
            waited = None
            if cfg["wait"]:
                t0 = time.time()
                pattern = os.path.join(data, "cds", "framework-*.jsa")
                while time.time() - t0 < args.archive_wait and not glob.glob(pattern):
                    time.sleep(1)
                waited = round(time.time() - t0, 1)
                print(f"[bench] {pkg}.{cfg['name']}: archive {'ready' if glob.glob(pattern) else 'MISSING'} "
                      f"after {waited} s", flush=True)
                if os.path.exists(os.path.join(data, "cds", "dump.log")):
                    shutil.copy(os.path.join(data, "cds", "dump.log"),
                                os.path.join(out, "logs", f"{pkg}.{cfg['name']}.dump.log"))
            per[cfg["name"]] = {"cold": cold, "archive_wait": waited, "warm": []}
        for i in range(args.warm):
            for cfg in configs:
                data = os.path.join(out, "data", cfg["name"], pkg)
                per[cfg["name"]]["warm"].append(run(cfg, apk, data, out, f"{pkg}.{cfg['name']}.warm{i + 1}", args))
        for cfg in configs:
            p = per[cfg["name"]]
            warm = p["warm"]
            results.append({
                "package": pkg, "config": cfg["name"], "env": cfg["env"],
                "cold_first_frame": p["cold"]["first_frame"], "cold_drew": p["cold"]["drew"],
                "warm_first_frame": med([w["first_frame"] for w in warm]),
                "warm_own_first_frame": med([w["own_first_frame"] for w in warm]),
                "warm_jvm": med([w["jvm"] for w in warm]),
                "warm_ws_mb": med([w["ws_mb"] for w in warm]),
                "warm_private_mb": med([w["private_mb"] for w in warm]),
                "warm_drew": sum(w["drew"] for w in warm), "warm_runs": len(warm),
                "warm_sharing": sorted({w["sharing"] or "?" for w in warm}),
                "archive_wait": p["archive_wait"],
                "archives": disk(os.path.join(out, "data", cfg["name"], pkg)),
                "runs": [p["cold"]] + warm})
        json.dump(results, open(os.path.join(out, "report.json"), "w"), indent=1)
    lines = ["| app | config | first start (s) | warm first frame (s) | JVM created (s) | working set (MB) | "
             "private (MB) | drew (warm) | sharing | archive (MB) |", "|" + "---|" * 10]
    for r in results:
        mb = round(sum(r["archives"].values()) / 1048576, 1)
        lines.append(f"| {r['package']} | {r['config']} | {r['cold_first_frame']} | {r['warm_first_frame']} | "
                     f"{r['warm_jvm']} | {r['warm_ws_mb']} | {r['warm_private_mb']} | "
                     f"{r['warm_drew']}/{r['warm_runs']}{'' if r['cold_drew'] else ' (cold NOT drawn)'} | "
                     f"{','.join(r['warm_sharing'])} | {mb} |")
    open(os.path.join(out, "report.md"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
