#!/usr/bin/env bash
set -uo pipefail
adb="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/platform-tools/adb"
app="${APP_ID:?}"
seed="${INTERVIEW_SEED:?}"
out="${INTERVIEW_OUT:?}"
shots="${SHOTS:?}"
devices="${INTERVIEW_DEVICES:-phone tablet}"
mkdir -p "$out" "$shots"

field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {}, {"d": d}))' "$seed" "$1"; }
token=$(field 'd["tokens"]["lead"]')
user_id=$(field 'd["users"]["lead"]["id"]')
user_name=$(field 'd["users"]["lead"]["name"]')
user_email=$(field 'd["users"]["lead"]["email"]')
user_role=$(field 'd["users"]["lead"]["role"]')
artisans=$(field '";".join(f"{v}|{k}" for k, v in d["artisans"].items())')
sessions=""
if [ -n "${INTERVIEW_SESSIONS:-}" ] && [ -s "$INTERVIEW_SESSIONS" ]; then
  sessions=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); notes={"bySection":"CI board by section","byQuestion":"CI board by question","finished":"CI board finished","fresh":"CI fresh","group":"CI group"}; print(";".join(f"{notes[k]}|{v}" for k, v in d.items() if k in notes))' "$INTERVIEW_SESSIONS")
fi

"$adb" install -r -t "$RUNNER_TEMP/app-debug.apk"
"$adb" install -r -t "$RUNNER_TEMP/app-debug-androidTest.apk"
"$adb" reverse tcp:8000 tcp:8000
"$adb" reverse tcp:9000 tcp:9000
for permission in RECORD_AUDIO POST_NOTIFICATIONS ACCESS_LOCAL_NETWORK; do
  "$adb" shell pm grant "$app" "android.permission.$permission" || true
done

status=0
for device in $devices; do
  if [ "$device" = "tablet" ]; then
    "$adb" shell wm size 2560x1600
    "$adb" shell wm density 320
  else
    "$adb" shell wm size reset
    "$adb" shell wm density reset
  fi
  sleep 3
  "$adb" shell "am instrument -w -e class $app.questionnaire.portal.InterviewShotsTest -e interviewDevice $device -e interviewToken '$token' -e interviewUserId '$user_id' -e interviewUserName '$user_name' -e interviewUserEmail '$user_email' -e interviewUserRole '$user_role' -e interviewArtisans '$artisans' -e interviewSessions '$sessions' $app.test/androidx.test.runner.AndroidJUnitRunner" > "$out/interview-$device.txt" 2>&1
  cat "$out/interview-$device.txt"
  grep -q "^OK (" "$out/interview-$device.txt" || status=1
done
"$adb" shell wm size reset
"$adb" shell wm density reset
"$adb" pull "/sdcard/Android/data/$app/files/design-fidelity/." "$shots/" || true
ls -R "$shots" || true
exit "$status"
