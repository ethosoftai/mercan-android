#!/usr/bin/env bash
# Mirrors the working tree into the two GitHub clones under github/. Runs inside WSL
# (Git Bash has no rsync); publish-github.sh calls it and then commits/pushes.
set -euo pipefail
ROOT=${MERCAN_ROOT:-/mnt/c/Users/yagiz/Desktop/app}
APPS=$ROOT/apps
GH=$ROOT/github
W=$GH/mercan-windows
A=$GH/mercan-android
VERSION=${MERCAN_VERSION:-0.7.0}

# Open WebUI fork -> patch against upstream v0.6.5 (the fork itself is never copied)
cd /root/work/open-webui
git add -A
git diff --cached --binary v0.6.5 > "$APPS/webui/open-webui-v0.6.5-mercan.patch"
git reset -q
echo "patch: $(wc -c < "$APPS/webui/open-webui-v0.6.5-mercan.patch") bytes"

# --- Windows repository
rsync -a --delete "$APPS/common/" "$W/common/"
rsync -a --delete "$APPS/server/" "$W/server/"
rsync -a --delete "$APPS/windows/" "$W/windows/"
rsync -a --delete --exclude '*.zip' "$APPS/webui/" "$W/webui/"
mkdir -p "$W/scripts"
rm -f "$W"/scripts/*
for f in build-windows.sh build-webui-runtime.sh build-linux-server.sh build-linux-test.sh webui-dev.sh \n         build-linux-packages.sh \
         release-windows.sh publish-github.sh publish-sync.sh installer.nsi mingw-w64-x86_64.cmake \
         sync.sh copy-fonts.sh make_icon.sh owui-nohelp.sh patch_llama_cmake.py; do
  [ -f "$APPS/scripts/$f" ] && cp "$APPS/scripts/$f" "$W/scripts/"
done
cp "$APPS/CMakeLists.txt" "$W/CMakeLists.txt"

# --- Android repository
rsync -a --delete --exclude 'build/' --exclude '.gradle/' --exclude 'app/build/' --exclude 'app/.cxx/' \
      --exclude 'local.properties' --exclude 'keystore.properties' --exclude 'app/src/main/assets/whisper.bin' \
      "$APPS/android/" "$A/android/"
rsync -a --delete "$APPS/common/" "$A/common/"
rsync -a --delete "$APPS/server/" "$A/server/"
rsync -a --delete --exclude '*.zip' "$APPS/webui/brand/" "$A/brand/"
rsync -a --delete "$ROOT/fonts/" "$A/fonts/"
mkdir -p "$A/scripts"
rm -f "$A"/scripts/*
for f in build-android.sh sync.sh copy-fonts.sh gen_drawables.py emu.sh emu-fresh.sh emu-push-model.sh \
         setup-emulator.sh publish-github.sh publish-sync.sh patch_llama_cmake.py; do
  [ -f "$APPS/scripts/$f" ] && cp "$APPS/scripts/$f" "$A/scripts/"
done
cp "$APPS/CMakeLists.txt" "$A/CMakeLists.txt"

# --- dist/: binaries live in GitHub Releases (files over 100 MB cannot be committed)
rm -f "$W"/dist/*.exe "$W"/dist/*.zip "$A"/dist/*.apk
mkdir -p "$W/dist" "$A/dist"
cat > "$W/dist/README.md" <<EOF
# Dağıtım

Kurulum dosyası (~261 MB) sürüm sayfasındadır:
https://github.com/ethosoftai/mercan-windows/releases/tag/v$VERSION

\`MercanSetup-$VERSION.exe\` yönetici izni istemez, \`%LOCALAPPDATA%\Programs\Mercan\` altına kurar.
Dil modeli (483 MB) ilk açılışta indirilir; konuşma tanıma modeli kurulumun içinde gelir.
EOF
cat > "$A/dist/README.md" <<EOF
# Dağıtım

APK (~71 MB) sürüm sayfasındadır:
https://github.com/ethosoftai/mercan-android/releases/tag/v$VERSION

\`Mercan-$VERSION.apk\` — Android 8.0+ (arm64-v8a). Dil modeli (483 MB) ilk açılışta indirilir;
konuşma tanıma modeli (60 MB) APK içinde gelir.
EOF
echo "sync done"
