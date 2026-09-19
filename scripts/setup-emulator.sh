#!/usr/bin/env bash
# Installs the Android emulator + an x86_64 API 34 image (has ARM translation) and creates the AVD.
set -euo pipefail
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
SDKM=/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" --install "emulator" "system-images;android-34;google_apis;x86_64" 2>&1 | tail -2
ls /opt/android-sdk/emulator/emulator
ls /opt/android-sdk/system-images/android-34/google_apis/x86_64/ | head -5
export DEBIAN_FRONTEND=noninteractive
apt-get install -y -qq libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libasound2t64 libxdamage1 libxrandr2 libxkbfile1 >/dev/null 2>&1 || true
echo no | /opt/android-sdk/cmdline-tools/latest/bin/avdmanager create avd -n mercan34 -k "system-images;android-34;google_apis;x86_64" -d "pixel_6" --force >/dev/null
# 3-button navigation is what the user reported; the emulator defaults to gestures on this image.
echo "hw.keyboard=yes" >> /root/.android/avd/mercan34.avd/config.ini
echo "hw.ramSize=3072" >> /root/.android/avd/mercan34.avd/config.ini
echo EMU_INSTALLED
