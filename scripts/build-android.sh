#!/usr/bin/env bash
# Builds the Android APK (arm64-v8a, all ggml CPU variants) from WSL/Linux.
set -euo pipefail
source "$HOME/.cargo/env"
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH="/opt/gradle-8.11.1/bin:$PATH"

WORK=/root/work
REPO=$WORK/repo
LLAMA=$REPO/build/_deps/llama-src
APPS=$WORK/apps
OUT=$WORK/out/android
API=26
TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64

# 1) Exact NDSRF004 tokenizer bridge for aarch64 Android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR=$TOOLCHAIN/bin/llvm-ar
export CC_aarch64_linux_android=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang
export AR_aarch64_linux_android=$TOOLCHAIN/bin/llvm-ar
FFI=$LLAMA/vendor/nedo004-ffi
cargo build --release --locked --manifest-path "$FFI/Cargo.toml" --target aarch64-linux-android
FFI_LIB=$FFI/target/aarch64-linux-android/release/libnedo004_ffi.a
test -s "$FFI_LIB"

# 2) (CMake tweaks are applied by sync.sh)

# 3) Gradle
export MERCAN_REPO_DIR=$REPO
export MERCAN_LLAMA_DIR=$LLAMA
export NEDO004_FFI_LIB=$FFI_LIB
cd "$APPS/android"
gradle --no-daemon --console=plain assembleRelease

mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-release.apk "$OUT/Mercan.apk"
ls -la "$OUT"
unzip -l "$OUT/Mercan.apk" | grep -E 'lib/arm64-v8a' || true
echo "ANDROID PACKAGE: $OUT/Mercan.apk"
