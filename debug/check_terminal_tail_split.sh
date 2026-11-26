#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${MACAPK_TEST_JDK:-$(/usr/libexec/java_home -v 21)}"
TMP="$(mktemp -d /tmp/macapk-terminal-tail.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/fixture"
cat > "$TMP/fixture/TailOracle.java" <<'JAVA'
package fixture;
public class TailOracle {
    public static int calls;
    public static final RuntimeException failure = new RuntimeException("tail failure");
    public static int finish(int key, int mode) {
        calls++;
        if (mode == 2) throw failure;
        return -key - 7;
    }
}
JAVA
python3 "$ROOT/debug/make_terminal_tail_fixture.py" "$TMP/input.dex" 1000
python3 "$ROOT/debug/make_terminal_tail_fixture.py" "$TMP/small.dex" 24
"$JDK/bin/javac" --release 21 -d "$TMP/classes" "$ROOT"/runtime/java/com/macapk/dex/*.java \
    "$ROOT/debug/TerminalTailSplitSmoke.java" "$TMP/fixture/TailOracle.java"
"$JDK/bin/java" -Xverify:all -cp "$TMP/classes" TerminalTailSplitSmoke "$TMP/input.dex" 1000
"$JDK/bin/java" -Xverify:all -cp "$TMP/classes" TerminalTailSplitSmoke "$TMP/small.dex" 24
