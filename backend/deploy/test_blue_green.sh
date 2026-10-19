#!/usr/bin/env bash
# shellcheck disable=SC2016  # the stub bodies are single-quoted on purpose: they expand when they run
# Runs blue_green.sh against stubbed sudo, systemctl, nginx, curl and journalctl and a fake venv, so
# the release logic is proved without a box, root or the network. Checks runs it ("Deploy scripts").
#
# The stubs keep the services' state as files. After every stop and every nginx reload the systemctl
# stub asks "is the port nginx proxies to still served?" and records a gap when it is not, so the
# zero-downtime claim is asserted, not assumed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/blue_green.sh"
real_sleep="$(command -v sleep)"
passed=0
failed=0

stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$T/bin/$1"; chmod +x "$T/bin/$1"; }

setup() {
  T="$(mktemp -d)"
  echo "$T" > "$TMPDIR_CHECK/T"
  export APP="$T/app" NGINX_SITE="$T/nginx-site" UNIT_DIR="$T/units" NODE_CACHE="$T/node" \
    CPYTHON_ROOT="$T/cpython" STATE="$T/state" HEALTH_LIMIT=2 DRAIN=0 REAL_SLEEP="$real_sleep"
  mkdir -p "$APP/venvs/v1/bin" "$UNIT_DIR" "$STATE/active" "$T/bin" "$NODE_CACHE/nodeenv-26.11.1/bin" "$CPYTHON_ROOT"
  ln -s nodeenv-26.11.1 "$NODE_CACHE/nodeenv"
  printf '#!/bin/sh\necho v26.11.1\n' > "$NODE_CACHE/nodeenv-26.11.1/bin/node"
  chmod +x "$NODE_CACHE/nodeenv-26.11.1/bin/node"
  cat > "$APP/venvs/v1/bin/python" <<'PY'
#!/usr/bin/env bash
echo "python $*" >> "$STATE/log"
if [ "${1:-} ${2:-} ${3:-}" = "-m prisma migrate" ]; then
  n="$(cat "$STATE/migrate_failures" 2>/dev/null || echo 0)"
  if [ "$n" -gt 0 ]; then echo $((n - 1)) > "$STATE/migrate_failures"; exit 1; fi
fi
exit 0
PY
  chmod +x "$APP/venvs/v1/bin/python"
  touch "$APP/venvs/v1/.complete"
  echo v1 > "$APP/venv-wanted"
  cat > "$NGINX_SITE" <<'NGINX'
server {
    listen 80 default_server;
    location / {
        proxy_pass http://127.0.0.1:8000;
    }
}
NGINX
  stub sudo 'exec "$@"'
  stub sleep 'exec "$REAL_SLEEP" 0.02'
  stub journalctl 'echo "journal for $*"'
  stub nginx 'exit "${NGINX_T_RC:-0}"'
  # The port nginx proxies to, and whether a running unit listens there.
  cat > "$T/bin/served" <<'SH'
#!/usr/bin/env bash
port="${1:-$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$NGINX_SITE")}"
[ -e "$STATE/sick-$port" ] && exit 1
[ -e "$STATE/active/fieldrepo-api@$port" ] && exit 0
[ "$port" = 8000 ] && [ -e "$STATE/active/fieldrepo" ] && exit 0
exit 1
SH
  chmod +x "$T/bin/served"
  stub curl 'url="${*: -1}"
port="$(sed -nE "s#^http://127\.0\.0\.1:([0-9]+)/health\$#\1#p" <<< "$url")"
served $port && { echo ok; exit 0; }
exit 7'
  stub systemctl 'echo "systemctl $*" >> "$STATE/log"
q=no; args=()
for a in "$@"; do case "$a" in --quiet) q=yes ;; --no-pager) ;; *) args+=("$a") ;; esac; done
verb="${args[0]}"; unit="${args[1]:-}"
case "$verb" in
  is-active)
    if [ -e "$STATE/active/$unit" ]; then [ "$q" = yes ] || echo active; exit 0; fi
    [ "$q" = yes ] || echo inactive; exit 3 ;;
  start|restart) touch "$STATE/active/$unit" ;;
  stop) rm -f "$STATE/active/$unit" ;;
esac
case "$verb" in stop|reload) served || echo "gap after systemctl $*" >> "$STATE/gaps" ;; esac
exit 0'
  export PATH="$T/bin:$PATH"
}

legacy_box() {
  mkdir -p "$APP/backend" "$APP/venvs/old/bin"
  printf 'DATABASE_URL=x\nTWO_FACTOR_ENCRYPTION_KEY=k1:abc\n' > "$APP/backend/.env"
  ln -s "$APP/venvs/old" "$APP/backend/.venv"
  touch "$UNIT_DIR/fieldrepo.service" "$UNIT_DIR/fieldrepo-queue.service"
  touch "$STATE/active/fieldrepo" "$STATE/active/fieldrepo-queue"
}

# What the workflow does between prepare and release: rsync the code into the tree and edit its .env.
fill_next() { mkdir -p "$APP/next/app"; echo "release $1" > "$APP/next/app/VERSION"; echo "BUILD=$1" >> "$APP/next/.env"; }

prepare() { bash "$script" prepare 2> >(tee -a "$STATE/err" >&2); }
release() { bash "$script" release "$1" 2> >(tee -a "$STATE/err" >&2); }
port_now() { sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$NGINX_SITE"; }
is_up() { [ -e "$STATE/active/$1" ]; }
no_gaps() { [ ! -s "$STATE/gaps" ] || { cat "$STATE/gaps" >&2; return 1; }; }

# The command must fail. Not `!`: errexit ignores a negated command, so `! x` asserts nothing.
fails() { if "$@"; then echo "expected to fail: $*" >&2; return 1; fi; }

# Each test in its own subshell with errexit on, OUTSIDE any `if` (an `if` would switch errexit off
# for everything it runs, and every assertion below would pass).
check() {
  local rc
  set +e
  ( set -e; "$1" ) > "$TMPDIR_CHECK/out" 2>&1
  rc=$?
  set -e
  rm -rf "$(cat "$TMPDIR_CHECK/T" 2>/dev/null)"
  if [ "$rc" = 0 ]; then passed=$((passed + 1)); echo "ok   $1"
  else failed=$((failed + 1)); echo "FAIL $1"; sed 's/^/     | /' "$TMPDIR_CHECK/out" | tail -n 30; fi
}

t_legacy_conversion() {
  setup; legacy_box
  cand="$(prepare)"; [ "$cand" = 8001 ]
  [ "$(readlink -f "$APP/next")" = "$APP/slots/8001/backend" ]
  grep -q '^TWO_FACTOR_ENCRYPTION_KEY=k1:abc$' "$APP/next/.env"   # the live .env was carried over
  [ -f "$UNIT_DIR/fieldrepo-api@.service" ]
  grep -q -- '--port %i' "$UNIT_DIR/fieldrepo-api@.service"
  fill_next 2
  release 8001
  [ "$(port_now)" = 8001 ]
  is_up fieldrepo-api@8001; fails is_up fieldrepo; is_up fieldrepo-queue
  [ ! -e "$UNIT_DIR/fieldrepo.service" ]
  [ -L "$APP/backend" ] && [ "$(readlink -f "$APP/backend")" = "$APP/slots/8001/backend" ]
  [ -d "$APP/legacy-backend" ] && [ ! -L "$APP/legacy-backend" ]
  [ "$(cat "$APP/venv-previous")" = "$APP/venvs/old" ]
  [ -d "$APP/venvs/old" ]                                     # the rollback venv is kept
  [ ! -e "$APP/next" ]
  grep -q 'python -m scripts.reencrypt_two_factor_secrets' "$STATE/log"
  no_gaps
}

t_second_release_alternates_and_never_gaps() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2; release "$cand"
  cand="$(prepare)"; [ "$cand" = 8000 ]
  grep -q '^BUILD=2$' "$APP/next/.env"                         # the live tree's .env, not a stale one
  fill_next 3; release 8000
  [ "$(port_now)" = 8000 ]; is_up fieldrepo-api@8000; fails is_up fieldrepo-api@8001
  [ "$(readlink -f "$APP/backend")" = "$APP/slots/8000/backend" ]
  grep -q 'release 2' "$APP/slots/8001/backend/app/VERSION"    # the previous tree stays for a rollback
  no_gaps
}

t_unhealthy_release_leaves_the_old_one_serving() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2; touch "$STATE/sick-8001"
  fails release 8001
  [ "$(port_now)" = 8000 ]; is_up fieldrepo; fails is_up fieldrepo-api@8001; is_up fieldrepo-queue
  [ ! -L "$APP/backend" ]                                       # nothing was converted
  grep -q 'did not answer /health' "$STATE/err"
  no_gaps
}

t_migration_falls_back_to_stopping_the_old_one() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2; echo 4 > "$STATE/migrate_failures"
  release 8001
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001; fails is_up fieldrepo
  grep -q 'stopping it to free its sessions' "$STATE/err"
}

t_failed_migration_restarts_the_old_one() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2; echo 99 > "$STATE/migrate_failures"
  fails release 8001
  [ "$(port_now)" = 8000 ]; is_up fieldrepo; fails is_up fieldrepo-api@8001; is_up fieldrepo-queue
  grep -q 'migrate deploy failed' "$STATE/err"
}

t_nginx_refusal_keeps_the_old_config() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2
  export NGINX_T_RC=1
  fails release 8001
  unset NGINX_T_RC
  [ "$(port_now)" = 8000 ]; is_up fieldrepo; fails is_up fieldrepo-api@8001
  no_gaps
}

t_rollback_returns_to_the_other_port() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2; release "$cand"
  cand="$(prepare)"; fill_next 3; release "$cand"
  bash "$script" rollback 2>>"$STATE/err"
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001; fails is_up fieldrepo-api@8000
  [ "$(readlink -f "$APP/backend")" = "$APP/slots/8001/backend" ]
  no_gaps
}

t_restart_restarts_the_live_port_only() {
  setup; legacy_box
  cand="$(prepare)"; fill_next 2; release "$cand"
  : > "$STATE/log"
  bash "$script" restart 2>>"$STATE/err"
  grep -q 'systemctl restart fieldrepo-api@8001' "$STATE/log"
  fails grep -q 'fieldrepo-api@8000' "$STATE/log"
  is_up fieldrepo-api@8001; is_up fieldrepo-queue
}

t_fresh_box() {
  setup
  cand="$(prepare)"; [ "$cand" = 8001 ]
  [ ! -e "$APP/backend" ]
  echo 'DATABASE_URL=x' > "$APP/next/.env"; fill_next 1
  release 8001
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001
  [ "$(readlink -f "$APP/backend")" = "$APP/slots/8001/backend" ]
}

t_release_refuses_the_live_port() {
  setup; legacy_box
  prepare >/dev/null
  fails release 8000
  fails release 9000
  [ "$(port_now)" = 8000 ]; is_up fieldrepo
}

t_prepare_stops_a_stale_candidate() {
  setup; legacy_box
  touch "$STATE/active/fieldrepo-api@8001"
  [ "$(prepare)" = 8001 ]
  fails is_up fieldrepo-api@8001; is_up fieldrepo
}

t_prepare_refuses_when_backend_is_the_idle_tree() {
  setup
  mkdir -p "$APP/slots/8001/backend"; ln -s "$APP/slots/8001/backend" "$APP/backend"
  fails prepare
}

# The template the script installs is the one a new box's user_data writes, byte for byte.
t_user_data_writes_the_same_unit() {
  local from_script from_user_data
  from_script="$(sed -n '/<<UNITFILE$/,/^UNITFILE$/p' "$script" | sed '1d;$d' | sed 's#\$APP#/home/ubuntu/app#g')"
  from_user_data="$(sed -n "/fieldrepo-api@.service <<'UNIT'\$/,/^UNIT\$/p" "$here/../../infra/terraform/user_data.sh" | sed '1d;$d')"
  [ -n "$from_script" ]
  [ "$from_script" = "$from_user_data" ] || { diff <(echo "$from_script") <(echo "$from_user_data") >&2; return 1; }
}

TMPDIR_CHECK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_CHECK"' EXIT
for t in $(declare -F | awk '{print $3}' | grep '^t_'); do check "$t" "$t"; done
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
