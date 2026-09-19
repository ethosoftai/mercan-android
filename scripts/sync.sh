#!/usr/bin/env bash
# Copies the Windows-side sources into the WSL filesystem (building on /mnt/c is very slow)
# and applies the idempotent llama.cpp CMake tweaks.
set -euo pipefail
SRC=/mnt/c/Users/yagiz/Desktop/app
DST=/root/work
mkdir -p "$DST"
rsync -a --delete --exclude 'build/release' --exclude 'build/_deps/llama-src-broken' --exclude '.git' \
  --exclude 'vendor/nedo004-ffi/target' \
  "$SRC/repo/" "$DST/repo/"
rsync -a --delete --exclude 'android/build' --exclude 'android/app/build' --exclude 'android/app/.cxx' --exclude 'android/.gradle' \
  "$SRC/apps/" "$DST/apps/"
sed -i 's/\r$//' "$DST"/apps/scripts/*.sh "$DST"/apps/scripts/*.cmake "$DST"/apps/scripts/*.py
python3 "$DST/apps/scripts/patch_llama_cmake.py" "$DST/repo/build/_deps/llama-src/src/CMakeLists.txt"
echo "synced to $DST"
