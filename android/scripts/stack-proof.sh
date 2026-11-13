#!/usr/bin/env bash
set -uo pipefail
adb="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/platform-tools/adb"
out="${STACK_OUT:?}"
seed="${STACK_SEED:?}"
mkdir -p "$out"
token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$seed")
craft=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["craftId"])' "$seed")
artisans=$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["artisanIds"]))' "$seed")
"$adb" install -r -t "$RUNNER_TEMP/app-debug.apk"
"$adb" install -r -t "$RUNNER_TEMP/app-debug-androidTest.apk"
"$adb" shell pm grant com.fieldrepository.app android.permission.ACCESS_LOCAL_NETWORK || true
"$adb" logcat -c || true
baseline_args=()
if [ -n "${STACK_BASELINE_API:-}" ]; then baseline_args=(-e baselineApi "$STACK_BASELINE_API"); fi
"$adb" shell am instrument -w \
  -e class com.fieldrepository.app.stack.ToolSaveStackDeviceTest \
  -e stackApi "$STACK_API" "${baseline_args[@]}" \
  -e token "$token" -e craftId "$craft" -e artisanIds "$artisans" \
  com.fieldrepository.app.test/androidx.test.runner.AndroidJUnitRunner > "$out/stack-test.txt" 2>&1
cat "$out/stack-test.txt"
"$adb" logcat -d -s ToolStackProof:I > "$out/stack-proof-logcat.txt" 2>&1 || true
echo "--- requests and answers seen on the device ---"
cat "$out/stack-proof-logcat.txt"
grep -q "^OK (" "$out/stack-test.txt"
