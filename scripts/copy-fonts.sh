#!/usr/bin/env bash
set -euo pipefail
D=/mnt/c/Users/yagiz/Desktop/app/apps/android/app/src/main/res/font
mkdir -p "$D"
cp /root/fonts/bonum-regular.otf "$D/bonum_regular.otf"
cp /root/fonts/bonum-bold.otf "$D/bonum_bold.otf"
cp /root/fonts/bonum-italic.otf "$D/bonum_italic.otf"
cp /root/fonts/inter/extras/ttf/Inter-Regular.ttf "$D/inter_regular.ttf"
cp /root/fonts/inter/extras/ttf/Inter-Medium.ttf "$D/inter_medium.ttf"
cp /root/fonts/inter/extras/ttf/Inter-SemiBold.ttf "$D/inter_semibold.ttf"
cp /root/fonts/inter/extras/ttf/Inter-Bold.ttf "$D/inter_bold.ttf"
ls -la "$D"
