#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIM="${MACAPK_TEST_SHIM:-$ROOT/runtime/translator/build/libmacapk-bionic-shim.dylib}"
TMP="$(mktemp -d /tmp/macapk-wrapper-dlsym.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/lib_native"
clang++ -std=c++17 -dynamiclib "$ROOT/debug/WrapperDlsymFixture.cpp" -o "$TMP/lib_native/libwrapperfixture.dylib"
clang++ -std=c++17 "$ROOT/debug/WrapperDlsymSmoke.cpp" -o "$TMP/check"
"$TMP/check" "$SHIM" "$TMP/lib_native/libwrapperfixture.so"

clang++ -std=c++17 -fno-builtin -dynamiclib "$ROOT/debug/WrapperDependencyFixture.cpp" -install_name "$TMP/lib_native/libguestdependency.dylib" -o "$TMP/lib_native/libguestdependency.dylib"
printf 'extern "C" int macapk_dependency_anchor(); extern "C" int anchor() { return macapk_dependency_anchor(); }\n' > "$TMP/dependent.cpp"
clang++ -std=c++17 -dynamiclib "$TMP/dependent.cpp" -L"$TMP/lib_native" -lguestdependency -o "$TMP/lib_native/libdependentfixture.dylib"
clang++ -std=c++17 "$ROOT/debug/WrapperDependencySmoke.cpp" -o "$TMP/dependency-check"
"$TMP/dependency-check" "$SHIM" "$TMP/lib_native/libdependentfixture.so"
