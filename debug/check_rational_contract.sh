#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRAMEWORK="${1:?Usage: check_rational_contract.sh <android-framework.jar-or-classes>}"
JDK="${MACAPK_TEST_JDK:-$(/usr/libexec/java_home -v 21)}"
WORK="$(mktemp -d /tmp/macapk-rational-contract.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
"$JDK/bin/javac" --release 21 -cp "$FRAMEWORK" -d "$WORK" "$ROOT/debug/RationalContractSmoke.java"
"$JDK/bin/java" -cp "$WORK:$FRAMEWORK" RationalContractSmoke
