#!/usr/bin/env bash
set -uo pipefail
E=/root/emu.sh
ADB=/opt/android-sdk/platform-tools/adb
$E launch 20
# enable thinking mode (bulb), type, send (keyboard open -> send button sits higher)
$E tap 78 2194; sleep 1
$E tap 540 2195; sleep 1
$ADB shell input text "Bir%sbardakta%s250%sml%ssu%svar,%s3%sbardak%skac%slitre%seder?"
sleep 1
$E tap 1000 1438
sleep 20
$E screenshot think1
sleep 70
$E key 4
sleep 1
$E screenshot think2
# open drawer
$E tap 72 199; sleep 2
$E screenshot drawer
$E key 4; sleep 1
# dark mode
$ADB shell cmd uimode night yes; sleep 4
$E screenshot dark
$ADB shell cmd uimode night no; sleep 3
