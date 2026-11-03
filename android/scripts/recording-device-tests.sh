#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}/platform-tools/adb"
PKG=com.fieldrepository.app
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

./gradlew -q :app:installDebug :app:installDebugAndroidTest
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
run -e class "$PKG.recordingdevice.RecordingKillDeviceTest" -e killPhase 1
"$ADB" shell am force-stop "$PKG"
echo "== kill mid-take: phase 2"
run -e class "$PKG.recordingdevice.RecordingKillDeviceTest" -e killPhase 2
echo "device tests passed"
