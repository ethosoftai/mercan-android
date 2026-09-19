#!/usr/bin/env bash
set -uo pipefail
E=/root/emu.sh
# tap the input, type, screenshot with keyboard open (checks IME inset), send
$E tap 540 2195
sleep 1
/opt/android-sdk/platform-tools/adb shell input text "Ankara%sneresidir?%sKisa%scevapla."
sleep 1
$E screenshot apk3_keyboard
$E tap 1000 2195
sleep 45
$E screenshot apk4_reply
$E logcat 8
