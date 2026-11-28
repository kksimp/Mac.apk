#!/usr/bin/env bash
# check_decorview_content_frame.sh <android-framework.jar>
#
# Regression for ActivityWindow.getDecorView(): once the window's decor sits
# inside the frame that carries android.R.id.content, the window hands out that
# frame, so getDecorView().findViewById(android.R.id.content) finds it as it
# does on a device. Source-only, on a plain JDK; see
# debug/DecorViewContentFrameSmoke.java.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRAMEWORK="${1:?Usage: check_decorview_content_frame.sh <android-framework.jar>}"
JDK="${MACAPK_TEST_JDK:-$(/usr/libexec/java_home -v 21)}"
WORK="$(mktemp -d /tmp/macapk-decorview-content-frame.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
"$JDK/bin/javac" --release 21 -cp "$FRAMEWORK" -d "$WORK" "$ROOT/debug/DecorViewContentFrameSmoke.java"
"$JDK/bin/java" -cp "$WORK:$FRAMEWORK" DecorViewContentFrameSmoke
