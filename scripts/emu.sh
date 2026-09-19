#!/usr/bin/env bash
# Helper for the headless test emulator: emu.sh start|stop|install|screenshot NAME|tap X Y|text STR|key KEY|logcat
set -uo pipefail
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_AVD_HOME=/root/.android/avd
ADB=/opt/android-sdk/platform-tools/adb
OUT=/mnt/c/Users/yagiz/AppData/Local/Temp/claude/C--Users-yagiz-Desktop-app/c131897e-8deb-43e8-aff3-78b37019fa6b/scratchpad
case "${1:-}" in
  start)
    cd /opt/android-sdk/emulator
    nohup ./emulator -avd mercan34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 3072 -cores 4 -no-snapshot > /tmp/emu.log 2>&1 &
    for i in $(seq 1 72); do
      sleep 5
      if [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then echo "BOOTED after $((i*5))s"; break; fi
    done
    $ADB devices
    tail -3 /tmp/emu.log
    ;;
  stop) $ADB emu kill; ;;
  install) $ADB install -r "${2:-/root/work/out/android/Mercan.apk}" 2>&1 | tail -2 ;;
  launch) $ADB shell am start -n ai.mercan.app/.MainActivity >/dev/null; sleep "${2:-3}" ;;
  screenshot) $ADB exec-out screencap -p > "$OUT/${2:-shot}.png" && echo "saved $OUT/${2:-shot}.png" ;;
  tap) $ADB shell input tap "$2" "$3" ;;
  swipe) $ADB shell input swipe "$2" "$3" "$4" "$5" "${6:-300}" ;;
  text) $ADB shell input text "$2" ;;
  key) $ADB shell input keyevent "$2" ;;
  nav3) $ADB shell cmd overlay enable com.android.internal.systemui.navbar.threebutton; $ADB shell cmd overlay disable com.android.internal.systemui.navbar.gestural ;;
  logcat) $ADB logcat -d -s MercanJNI AndroidRuntime:E "*:F" | tail -"${2:-40}" ;;
  shell) shift; $ADB shell "$@" ;;
  *) echo "usage: emu.sh start|stop|install|launch|screenshot NAME|tap X Y|swipe x1 y1 x2 y2|text STR|key KEY|nav3|logcat|shell CMD"; exit 1 ;;
esac
