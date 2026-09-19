#!/usr/bin/env bash
set -uo pipefail
ADB=/opt/android-sdk/platform-tools/adb
for i in $(seq 1 72); do
  sleep 5
  if [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then echo "BOOTED after $((i*5))s"; break; fi
done
/root/emu.sh nav3
sleep 2
/root/emu.sh install
/root/emu.sh launch 8
/root/emu.sh screenshot apk1
/root/emu.sh logcat 15
