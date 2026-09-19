#!/usr/bin/env bash
# Prepares the Mercan runtime (Ahmet2001/mercanApp-test1 + patched llama.cpp e71b805) next to this repo.
#   bash runtime/setup-runtime.sh [dest]        (default: ../repo — what CMakeLists.txt/MERCAN_REPO_DIR expects)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${1:-$HERE/../../repo}"
UPSTREAM_COMMIT=2bd274f
if [ ! -d "$DEST/.git" ]; then
  git clone https://github.com/Ahmet2001/mercanApp-test1.git "$DEST"
fi
git -C "$DEST" checkout -q "$UPSTREAM_COMMIT"
git -C "$DEST" apply --check "$HERE/mercanApp-test1-context-clear.patch" 2>/dev/null && git -C "$DEST" apply "$HERE/mercanApp-test1-context-clear.patch"
# llama.cpp @ e71b805 + NedoLM patch + vendored NDSRF004 tokenizer crate (upstream script)
bash "$DEST/scripts/prepare_llama.sh"
LLAMA="$DEST/build/_deps/llama-src"
git -C "$LLAMA" apply --check "$HERE/llama-cpp-mingw-power-throttling.patch" 2>/dev/null && git -C "$LLAMA" apply "$HERE/llama-cpp-mingw-power-throttling.patch"
cp "$DEST/runtime/libmercan/include/mercan_kv.h" "$LLAMA/src/models/mercan_kv.h"
python3 "$HERE/patch_llama_cmake.py" "$LLAMA/src/CMakeLists.txt"
echo "runtime ready: $DEST"
