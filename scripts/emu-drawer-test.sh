#!/usr/bin/env bash
set -uo pipefail
E=/root/emu.sh
ADB=/opt/android-sdk/platform-tools/adb
$E launch 30
$E screenshot relaunch
# open drawer via hamburger
$E tap 72 199; sleep 2
$E screenshot drawer
# close drawer by tapping outside it (right edge)
$E tap 1000 1200; sleep 1
# dark mode
$ADB shell cmd uimode night yes; sleep 5
$E screenshot dark
$E tap 72 199; sleep 2
$E screenshot dark_drawer
$E tap 1000 1200; sleep 1
$ADB shell cmd uimode night no; sleep 3
$E logcat 6 | grep -v "F DEBUG"
