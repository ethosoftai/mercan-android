#!/usr/bin/env bash
# Publishes the current sources and release binaries to GitHub:
#   ethosoftai/mercan-windows   engine + server + launcher + Open WebUI patch + build scripts
#   ethosoftai/mercan-android   engine + Android app + brand assets + build scripts
# Binaries (installer, APK) go to a GitHub Release, since the repositories cannot hold
# files over 100 MB. Run from Windows (Git Bash):
#   bash apps/scripts/publish-github.sh "commit mesajı"
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GH=$ROOT/github
W=$GH/mercan-windows
A=$GH/mercan-android
MSG=${1:-"sürüm güncellemesi"}
VERSION=${MERCAN_VERSION:-0.7.0}

echo "== dosyaları eşitle (WSL: rsync + Open WebUI yaması)"
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -u root -- bash /mnt/c/Users/yagiz/Desktop/app/apps/scripts/publish-sync.sh

for repo in "$W" "$A"; do
  name=$(basename "$repo")
  echo "== $name"
  git -C "$repo" add -A
  if git -C "$repo" diff --cached --quiet; then
    echo "   (değişiklik yok)"
  else
    git -C "$repo" commit -q -m "$MSG"
    git -C "$repo" push -q origin HEAD
    echo "   $(git -C "$repo" log --oneline -1)"
  fi
done

echo "== sürüm dosyaları (GitHub Releases)"
NOTES="Mercan $VERSION — mikrofonla yazma (whisper.cpp, tamamen cihazda), sadeleştirilmiş ayarlar, dairesiz logo ve yanıt akarken animasyon, Mercan görünümlü kurulum sihirbazı."
release() {  # repo tag title file
  gh release view "$2" --repo "$1" >/dev/null 2>&1 \
    || gh release create "$2" --repo "$1" --title "$3" --notes "$NOTES"
  [ -f "$4" ] && gh release upload "$2" "$4" --repo "$1" --clobber
}
release ethosoftai/mercan-windows "v$VERSION" "Mercan $VERSION (Windows)" "$ROOT/dist/MercanSetup-$VERSION.exe"
release ethosoftai/mercan-android "v$VERSION" "Mercan $VERSION (Android)" "$ROOT/dist/Mercan-$VERSION.apk"
for f in "$ROOT"/dist/linux/*; do
  [ -f "$f" ] && gh release upload "v$VERSION" "$f" --repo ethosoftai/mercan-windows --clobber
done
echo "PUBLISH_DONE"
