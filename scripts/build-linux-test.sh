#!/usr/bin/env bash
# Native Linux build inside WSL: CLI + headless engine test against the real model.
set -euo pipefail
source "$HOME/.cargo/env"
WORK=/root/work
REPO=$WORK/repo
LLAMA=$REPO/build/_deps/llama-src
APPS=$WORK/apps
BUILD=$WORK/build-linux
MODEL=${MODEL:-/mnt/c/Users/yagiz/Desktop/app/models/model.mercan}

FFI=$LLAMA/vendor/nedo004-ffi
cargo build --release --locked --manifest-path "$FFI/Cargo.toml" --target x86_64-unknown-linux-gnu
FFI_LIB=$FFI/target/x86_64-unknown-linux-gnu/release/libnedo004_ffi.a

cmake -S "$APPS" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DMERCAN_REPO_DIR="$REPO" -DMERCAN_LLAMA_DIR="$LLAMA" -DNEDO004_FFI_LIB="$FFI_LIB" \
  -DMERCAN_BUILD_GUI=OFF -DMERCAN_BUILD_CLI=ON -DMERCAN_BUILD_TEST=ON
cmake --build "$BUILD" -j "$(nproc)" --target chat_test mercan

# Model on /mnt/c is slow to mmap through 9p: copy once into the Linux FS.
mkdir -p "$WORK/models"
[ -s "$WORK/models/model.mercan" ] || cp "$MODEL" "$WORK/models/model.mercan"
echo "=== chat_test ==="
"$BUILD/chat_test" "$WORK/models/model.mercan" "${THREADS:-6}"
