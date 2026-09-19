#!/usr/bin/env bash
# Reinstall the APK as a first launch (keeps the pushed model): clears app data, pushes model, launches.
set -uo pipefail
ADB=/opt/android-sdk/platform-tools/adb
E=/root/emu.sh
$ADB shell am force-stop ai.mercan.app
$ADB install -r /root/work/out/android/Mercan.apk 2>&1 | tail -1
$ADB shell pm clear ai.mercan.app >/dev/null
$ADB root >/dev/null 2>&1; sleep 2
$ADB shell "mkdir -p /data/data/ai.mercan.app/files/models"
$ADB push /root/work/models/model.mercan /data/data/ai.mercan.app/files/models/model.mercan 2>&1 | tail -1
$ADB shell "UID=\$(stat -c %u /data/data/ai.mercan.app); chown -R \$UID:\$UID /data/data/ai.mercan.app/files"
$E launch "${1:-6}"
$E screenshot "${2:-fresh}"
$E logcat 12 | grep -v "F DEBUG"
