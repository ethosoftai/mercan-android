#!/usr/bin/env bash
set -uo pipefail
ADB=/opt/android-sdk/platform-tools/adb
$ADB shell am force-stop ai.mercan.app
$ADB root >/dev/null 2>&1; sleep 3
$ADB shell "mkdir -p /data/data/ai.mercan.app/files/models"
$ADB push /root/work/models/model.mercan /data/data/ai.mercan.app/files/models/model.mercan 2>&1 | tail -1
$ADB shell "rm -f /data/data/ai.mercan.app/files/models/model.mercan.part; UID=\$(stat -c %u /data/data/ai.mercan.app); chown -R \$UID:\$UID /data/data/ai.mercan.app/files; ls -la /data/data/ai.mercan.app/files/models/"
/root/emu.sh launch 25
/root/emu.sh screenshot apk2
/root/emu.sh logcat 6
