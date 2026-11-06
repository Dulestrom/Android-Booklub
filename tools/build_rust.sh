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
# Rust keeps source paths for its error messages. These replace this computer's folders, which
# hold the user name, with neutral ones, as the desktop's release build does. The later rule
# wins where both match.
# The encoded form keeps each flag whole, because folder names may contain spaces.
root=$(pwd -P)
export CARGO_ENCODED_RUSTFLAGS="--remap-path-prefix=$HOME=/build"$'\x1f'"--remap-path-prefix=$root=/booklub"
(cd rust && cargo ndk --platform 33 -t arm64-v8a -t x86_64 -o "../$jni" build $flag)
# The JVM tests load a host build through JNA (app/build.gradle.kts), so keep it current too.
(cd rust && cargo build --release)
# Library mode reads the interface from the compiled code, so any target's library will do.
library="rust/target/aarch64-linux-android/$profile/libbooklub_mobile.so"
out="app/src/main/java"
rm -rf "$out/uniffi"
(cd rust && cargo run --quiet --bin uniffi-bindgen -- generate --library "../$library" --language kotlin --out-dir "../$out" --no-format)
# Refuses to finish if a local folder survived into the libraries the APK carries.
for library in "$jni"/*/*.so; do
    for leak in "$HOME/" "$root/"; do
        if grep -aqF "$leak" "$library"; then
            echo "REFUSING: $library still contains a local path. Nothing is ready to package." >&2
            rm -rf "$jni"
            exit 1
        fi
    done
done
tools/third_party_notices.py >/dev/null
echo "Built $(du -sh "$jni" | cut -f1) of native code, Kotlin bindings in $out/uniffi, and the licence notices."
