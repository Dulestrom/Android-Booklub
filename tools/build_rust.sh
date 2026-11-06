#!/bin/bash
# Builds the shared Rust core for phones and the emulator, and generates its Kotlin bindings.
# usage: tools/build_rust.sh [debug|release]
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
profile="${1:-release}"
flag=$([ "$profile" = release ] && echo --release || true)
jni="app/src/main/jniLibs"
rm -rf "$jni"
(cd rust && cargo ndk --platform 33 -t arm64-v8a -t x86_64 -o "../$jni" build $flag)
# The JVM tests load a host build through JNA (app/build.gradle.kts), so keep it current too.
(cd rust && cargo build --release)
# Library mode reads the interface from the compiled code, so any target's library will do.
library="rust/target/aarch64-linux-android/$profile/libbooklub_mobile.so"
out="app/src/main/java"
rm -rf "$out/uniffi"
(cd rust && cargo run --quiet --bin uniffi-bindgen -- generate --library "../$library" --language kotlin --out-dir "../$out" --no-format)
tools/third_party_notices.py >/dev/null
echo "Built $(du -sh "$jni" | cut -f1) of native code, Kotlin bindings in $out/uniffi, and the licence notices."
