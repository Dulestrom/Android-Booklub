# Sourced by the build scripts. Phone builds use their own Rust, kept off PATH, so the desktop
# app's Ubuntu-packaged Rust is never touched.
toolchain="$HOME/.local/share/booklub-android"
export RUSTUP_HOME="$toolchain/rustup"
export CARGO_HOME="$toolchain/cargo"
export PATH="$CARGO_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/usr/lib/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
# Gradle needs Java 21; the system default may be newer.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
