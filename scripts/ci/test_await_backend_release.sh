#!/usr/bin/env bash
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${here}/await_backend_release.sh"
backend="$(cd "${here}/../../backend" && pwd)"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
failures=0
PY=python3
"$PY" -c "" 2>/dev/null || PY=python
want="$("$PY" "${backend}/app/release.py" "${backend}")"

mkdir -p "$root/bin"
cat > "$root/bin/gh" <<'STUB'
#!/usr/bin/env bash
n="$(cat "$STUB_DIR/gh.count" 2>/dev/null || echo 0)"
n=$((n + 1)); echo "$n" > "$STUB_DIR/gh.count"
file="$STUB_DIR/runs.$n"
[ -f "$file" ] || file="$STUB_DIR/runs"
cat "$file"
STUB
cat > "$root/bin/curl" <<'STUB'
#!/usr/bin/env bash
cat "$STUB_DIR/health"
STUB
chmod +x "$root/bin/gh" "$root/bin/curl"

scenario() {
  rm -f "$root"/runs* "$root/gh.count"
  printf '%s' "$1" > "$root/runs"
  printf '%s' "$2" > "$root/health"
}

expect() {
  local want_rc="$1" name="$2" pattern="$3" out rc
  out="$(PATH="$root/bin:$PATH" STUB_DIR="$root" REPO=o/r SHA=abc123 API_BASE=https://api.invalid \
    BACKEND_DIR="$backend" PYTHON="$PY" POLL_SECONDS=0 DEPLOY_WAIT_SECONDS="${DWAIT:-0}" HEALTH_WAIT_SECONDS=0 \
    ENFORCE="${ENFORCE:-true}" GITHUB_STEP_SUMMARY="$root/summary" bash "$script" 2>&1)"
  rc=$?
  if [ "$rc" != "$want_rc" ]; then
    echo "not ok - $name: wanted exit $want_rc, got $rc"; printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  elif ! grep -qE -- "$pattern" <<< "$out"; then
    echo "not ok - $name: output lacks /$pattern/"; printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  else
    echo "ok - $name"
  fi
}

ok_run="1 completed success https://gh.invalid/runs/1"
bad_run="2 completed failure https://gh.invalid/runs/2"
busy_run="3 in_progress none https://gh.invalid/runs/3"

scenario "$ok_run" "{\"status\":\"ok\",\"release\":\"$want\"}"
expect 0 "deploy succeeded and /health reports this commit" "Backend live::.*runs/1.*$want"

scenario "$bad_run" "{\"status\":\"ok\",\"release\":\"$want\"}"
expect 1 "a failed deploy refuses and says why" "Android publish refused: backend deploy failed::.*ended 'failure' \(https://gh.invalid/runs/2\)"

scenario "" "{\"status\":\"ok\",\"release\":\"$want\"}"
expect 1 "no deploy for the commit refuses" "Android publish refused: no backend deploy::"

scenario "$busy_run" "{\"status\":\"ok\",\"release\":\"$want\"}"
expect 1 "a deploy still running past the wait refuses" "Android publish refused: backend deploy not finished::.*runs/3"

scenario "$busy_run" "{\"status\":\"ok\",\"release\":\"$want\"}"
printf '%s' "$ok_run" > "$root/runs.2"
DWAIT=60 expect 0 "a running deploy is waited for" "looking again.*"

scenario "$ok_run" '{"status":"ok","release":"0000"}'
expect 1 "a different live backend refuses" "a different backend is live::.*reports release 0000 and this commit's backend is $want"

scenario "$ok_run" '{"status":"ok"}'
expect 1 "a backend without the release field refuses" "live backend unreadable::"

scenario "$bad_run" '{"status":"ok"}'
ENFORCE=false expect 0 "a dry run reports the refusal and goes on" "Would refuse to publish: backend deploy failed::"

if [ "$failures" -gt 0 ]; then echo "$failures failed"; exit 1; fi
echo "all passed"
