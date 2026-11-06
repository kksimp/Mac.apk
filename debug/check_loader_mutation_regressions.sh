#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
. "$ROOT/debug/jdk25_select.sh"
JDK="${MACAPK_TEST_JDK:-$(macapk_jdk_home)}"
TMP="$(mktemp -d /tmp/macapk-loader-mutation.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_live_memory_dex_fixture.py" "$TMP/live.dex" "$TMP/offsets.txt"
python3 - "$TMP" <<'PYFIXTURE'
import hashlib, struct, sys, zlib
from pathlib import Path
root = Path(sys.argv[1])
image = bytearray((root / 'live.dex').read_bytes())
beta = int((root / 'offsets.txt').read_text().split()[1])
struct.pack_into('<H', image, beta + 2, 99)
image[12:32] = hashlib.sha1(image[32:]).digest()
struct.pack_into('<I', image, 8, zlib.adler32(image[12:]) & 0xffffffff)
(root / 'patch.dex').write_bytes(image)
PYFIXTURE
"$JDK/bin/javac" --release 21 -cp "$LIB/*" -d "$TMP/classes" "$ROOT/debug/LoaderMutationRegressionSmoke.java"
status=0
for scenario in unlinked lazy; do
    MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 MACAPK_LAZY_DEX=0 "$JDK/bin/java" -Xverify:all -Djava.io.tmpdir="$TMP" \
        -cp "$TMP/classes:$LIB/*" LoaderMutationRegressionSmoke "$TMP/live.dex" "$scenario" || status=1
done
exit "$status"
