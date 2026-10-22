#!/usr/bin/env bash
# DESIGN §9 tests 49-51 on a connected device or emulator (adb devices shows one).
#   1. RecordingServiceDeviceTest: app switch, locked screen, Back (leave), a call taking audio focus.
#   2. RecordingKillDeviceTest: start a take, kill the app, check the take is offered back and plays.
# Usage: android/scripts/recording-device-tests.sh   (from anywhere; needs ANDROID_HOME or adb on PATH)
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}/platform-tools/adb"
PKG=com.fieldrepository.app
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

./gradlew -q :app:installDebug :app:installDebugAndroidTest
# A fresh install's first start can outlast the instrumentation's startup timeout on a slow emulator
# (it compiles the app on first launch); start it once and let that finish first.
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
"$ADB" shell am force-stop "$PKG"

run() {
  local out
  out="$("$ADB" shell am instrument -w -r "$@" "$RUNNER")"
  echo "$out" | grep -E "INSTRUMENTATION_STATUS: (class|test)=|INSTRUMENTATION_STATUS_CODE: -2|OK \(|FAILURES|Tests run" || true
  if echo "$out" | grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed"; then echo "$out" | sed -n '1,80p'; return 1; fi
}

echo "== recording service"
run -e class "$PKG.recordingdevice.RecordingServiceDeviceTest"

echo "== kill mid-take: phase 1"
# am instrument ends by killing the app process; force-stop makes sure the service is gone too.
run -e class "$PKG.recordingdevice.RecordingKillDeviceTest" -e killPhase 1
"$ADB" shell am force-stop "$PKG"
echo "== kill mid-take: phase 2"
run -e class "$PKG.recordingdevice.RecordingKillDeviceTest" -e killPhase 2
echo "device tests passed"
