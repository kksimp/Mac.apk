#!/usr/bin/env python3
"""Run the independently authored fixture in a fresh temporary Android root."""
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

if len(sys.argv) != 4:
    raise SystemExit("Usage: run.py <runtime-root> <fixture-apk> <log-path>")
runtime, apk, log = map(lambda x: Path(x).resolve(), sys.argv[1:])
binary = runtime / "Resources/macapk-runtime"
if not binary.is_file():
    binary = runtime / "bin/macapk-runtime"

with tempfile.TemporaryDirectory(prefix="macapk-native-load-fixture-") as tmp:
    temp = Path(tmp)
    env = os.environ.copy()
    env["MACAPK_APP_CACHE_DIR"] = str(temp / "code")
    env["MACAPK_ANDROID_ROOT"] = str(temp / "android")
    env["MACAPK_APP_LABEL"] = "Native Load Fixture"
    with log.open("wb") as output:
        proc = subprocess.Popen(
            [str(binary), "--apk", str(apk), "--android-root", str(temp / "android"),
             "--orientation", "portrait", "--width", "400", "--height", "640"],
            env=env, stdout=output, stderr=subprocess.STDOUT,
        )
        verdict = None
        try:
            deadline = time.monotonic() + 90
            with log.open("rb") as reader:
                pending = b""
                while time.monotonic() < deadline:
                    data = pending + reader.read()
                    lines = data.split(b"\n")
                    pending = lines.pop()
                    for raw in lines:
                        line = raw.decode(errors="replace")
                        if "NativeLoadFixture" not in line:
                            continue
                        if "CHECK:" in line:
                            print(line, flush=True)
                        if line.startswith("NativeLoadFixture PASS:"):
                            print(line, flush=True)
                            verdict = 0
                        if line.startswith("NativeLoadFixture FAIL:"):
                            print(line, flush=True)
                            verdict = 1
                    if verdict is not None:
                        break
                    if proc.poll() is not None:
                        print("Fixture exited before verdict:", proc.returncode)
                        break
                    time.sleep(0.25)
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
        print("Fixture log:", log)
        raise SystemExit(2 if verdict is None else verdict)
