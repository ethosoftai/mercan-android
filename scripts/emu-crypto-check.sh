#!/usr/bin/env bash
# Fresh install with a plaintext model pushed in -> app must seal it, load, and leave no plaintext.
set -uo pipefail
ADB=/opt/android-sdk/platform-tools/adb
E=/root/emu.sh
bash /root/emu-fresh.sh 4 c0 >/dev/null
$E tap 960 208; sleep 1   # Atla
for i in $(seq 1 60); do sleep 5; if $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && $ADB shell cat /sdcard/ui.xml | grep -q 'Hazır'; then echo "ready after $((i*5))s"; break; fi; done
$E screenshot c1_ready
echo "--- files/models ---"; $ADB shell "ls -la /data/data/ai.mercan.app/files/models/"
echo "--- cache/load (must be empty) ---"; $ADB shell "ls -la /data/data/ai.mercan.app/cache/load/ 2>&1"
echo "--- header (must be MRCNENC1) ---"; $ADB shell "head -c 8 /data/data/ai.mercan.app/files/models/model.mercan.enc"; echo
$E tap 540 1338; sleep 40
$E screenshot c2_chat
$E logcat 6 | grep -v "F DEBUG"
