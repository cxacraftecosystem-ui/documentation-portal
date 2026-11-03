#!/usr/bin/env bash
set -euo pipefail

APP="${APP:-/home/ubuntu/app}"
SITE="${NGINX_SITE:-/etc/nginx/sites-available/fieldrepo}"
UNIT_DIR="${UNIT_DIR:-/etc/systemd/system}"
NODE_CACHE="${NODE_CACHE:-$HOME/.cache/prisma-python}"
HEALTH_LIMIT="${HEALTH_LIMIT:-600}"
DRAIN="${DRAIN:-5}"
NODE_PIN=26.13.0
UNIT=fieldrepo-api
LEGACY=fieldrepo
QUEUE=fieldrepo-queue

say() { printf '%s\n' "$*" >&2; }
die() { say "::error::$*"; exit 1; }
other() { if [ "$1" = 8000 ]; then echo 8001; else echo 8000; fi; }
tree_of() { echo "$APP/slots/$1/backend"; }
venv_of() { echo "$APP/slots/$1/venv"; }
active() { sudo systemctl is-active --quiet "$1" 2>/dev/null; }

live_port() {
  local p
  p="$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$SITE" | head -n1)"
  case "$p" in 8000|8001) echo "$p" ;; *) die "$SITE proxies to '${p:-nothing}', not 127.0.0.1:8000 or :8001; refusing to guess which API is live" ;; esac
}

live_unit() {
  local port="$1"
  if active "$UNIT@$port"; then echo "$UNIT@$port"
  elif [ "$port" = 8000 ] && active "$LEGACY"; then echo "$LEGACY"
  fi
}

install_unit() {
  local tmp
  tmp="$(mktemp)"
  cat > "$tmp" <<UNITFILE
# Written by backend/deploy/blue_green.sh. One instance per loopback port; nginx proxies to one.
# A SINGLE uvicorn process, never --workers 2: infra/terraform/user_data.sh says why.
[Unit]
Description=Field Repository API on port %i
After=network.target

[Service]
User=ubuntu
WorkingDirectory=$APP/slots/%i/backend
EnvironmentFile=$APP/slots/%i/backend/.env
# After EnvironmentFile so it always wins: the web process must never run the queue.
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
# Two malloc arenas, not glibc's eight per core: backend/deploy/blue_green.sh says why.
Environment=MALLOC_ARENA_MAX=2
ExecStart=$APP/slots/%i/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port %i --workers 1
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
UNITFILE
  if ! cmp -s "$tmp" "$UNIT_DIR/$UNIT@.service"; then
    sudo install -m 644 "$tmp" "$UNIT_DIR/$UNIT@.service"
    sudo systemctl daemon-reload
    say "installed $UNIT_DIR/$UNIT@.service"
  fi
  printf '%s
' '# Written by backend/deploy/blue_green.sh.' '[Service]' 'Environment=MALLOC_ARENA_MAX=2' > "$tmp"
  if ! cmp -s "$tmp" "$UNIT_DIR/$QUEUE.service.d/memory.conf"; then
    sudo mkdir -p "$UNIT_DIR/$QUEUE.service.d"
    sudo install -m 644 "$tmp" "$UNIT_DIR/$QUEUE.service.d/memory.conf"
    sudo systemctl daemon-reload
    say "installed $UNIT_DIR/$QUEUE.service.d/memory.conf"
  fi
  rm -f "$tmp"
}

point() { ln -sfn "$2" "$1.tmp" && mv -Tf "$1.tmp" "$1"; }

cmd_prepare() {
  local live cand tree
  install_unit
  live="$(live_port)"
  cand="$(other "$live")"
  tree="$(tree_of "$cand")"
  if active "$UNIT@$cand"; then
    say "stopping $UNIT@$cand: it is running but nginx does not send it traffic"
    sudo systemctl stop "$UNIT@$cand"
  fi
  if [ -e "$APP/backend" ] && [ "$(readlink -f "$APP/backend")" = "$(readlink -f "$tree")" ]; then
    die "$APP/backend resolves to $tree, the tree this release would overwrite, while nginx proxies to $live. Nothing was changed; run 'blue_green.sh status' and fix the layout by hand."
  fi
  mkdir -p "$tree"
  if [ -f "$APP/backend/.env" ]; then
    install -m 600 "$APP/backend/.env" "$tree/.env"
  fi
  point "$APP/next" "$tree"
  say "live port $live; this release goes to $cand ($tree)"
  echo "$cand"
}

build_tree() {
  local tree="$1" base="$2" venv="$3" py="$3/bin/python" have dest f want_schema built_schema
  if [ -f "$base/.schema-sha256" ] && [ -f "$tree/prisma/schema.prisma" ]; then
    want_schema="$(sha256sum "$tree/prisma/schema.prisma" | cut -d' ' -f1)"
    built_schema="$(cat "$base/.schema-sha256")"
    [ "$want_schema" = "$built_schema" ] || die "$base holds a Prisma client generated from schema $built_schema, and this tree's prisma/schema.prisma is $want_schema. Nothing was changed."
  fi
  rm -rf "$venv"
  cp -a "$base" "$venv"
  rm -f "$venv/.complete"
  { grep -rlIF -- "$base" "$venv/bin" "$venv/pyvenv.cfg" 2>/dev/null || true; } | while IFS= read -r f; do
    sed -i "s#$base#$venv#g" "$f"
  done
  cd "$tree"
  point .venv "$venv"
  mkdir -p "$NODE_CACHE"
  have="$("$NODE_CACHE/nodeenv/bin/node" --version 2>/dev/null || true)"
  if [ "$have" != "v$NODE_PIN" ]; then
    dest="$NODE_CACHE/nodeenv-$NODE_PIN"
    if [ "$("$dest/bin/node" --version 2>/dev/null || true)" != "v$NODE_PIN" ]; then
      rm -rf "$dest"
      "$py" -m nodeenv --prebuilt --node="$NODE_PIN" "$dest"
    fi
    if [ -d "$NODE_CACHE/nodeenv" ] && [ ! -L "$NODE_CACHE/nodeenv" ]; then
      rm -rf "$NODE_CACHE/nodeenv-replaced"
      mv "$NODE_CACHE/nodeenv" "$NODE_CACHE/nodeenv-replaced"
    fi
    point "$NODE_CACHE/nodeenv" "nodeenv-$NODE_PIN"
  fi
  export PRISMA_USE_GLOBAL_NODE=false
  "$NODE_CACHE/nodeenv/bin/node" --version
  { "$py" -VV; readlink -f "$py"; } > "$venv/.complete"
}

migrate() {
  local tree="$1" target="$2" tries="$3" attempt
  for attempt in $(seq 1 "$tries"); do
    if (cd "$tree" && PATH="$target/bin:$PATH" "$target/bin/python" -m app.prisma_cli migrate deploy); then return 0; fi
    say "migrate attempt $attempt/$tries failed"
    [ "$attempt" = "$tries" ] || sleep 10
  done
  return 1
}

wait_healthy() {
  local port="$1" start now next_note
  start="$(date +%s)"; next_note=$((start + 20))
  while :; do
    if curl -fsS -m 5 "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
      say "port $port answered /health after $(( $(date +%s) - start ))s"; return 0
    fi
    now="$(date +%s)"
    if [ "$now" -ge $((start + HEALTH_LIMIT)) ]; then return 1; fi
    if [ "$now" -ge "$next_note" ]; then
      say "waiting for /health on $port: $((now - start))s of ${HEALTH_LIMIT}s; $UNIT@$port is $(sudo systemctl is-active "$UNIT@$port" 2>/dev/null || true)"
      next_note=$((now + 20))
    fi
    sleep 2
  done
}

switch_nginx() {
  local port="$1" backup
  backup="$(mktemp)"
  sudo cp "$SITE" "$backup"
  sudo sed -i -E "s#^([[:space:]]*proxy_pass http://127\.0\.0\.1:)[0-9]+;#\1$port;#" "$SITE"
  if [ "$(live_port)" != "$port" ] || ! sudo nginx -t >/dev/null 2>&1; then
    sudo cp "$backup" "$SITE"; rm -f "$backup"
    die "nginx refused the switch to $port; its config is as it was"
  fi
  sudo systemctl reload nginx
  if ! curl -fsS -m 10 -o /dev/null http://127.0.0.1/health; then
    sudo cp "$backup" "$SITE"; rm -f "$backup"
    sudo systemctl reload nginx
    die "nginx did not answer /health after the switch to $port; it was switched back"
  fi
  rm -f "$backup"
  say "nginx now proxies to $port"
}

promote() {
  local port="$1" old_unit="$2" tree
  tree="$(tree_of "$port")"
  switch_nginx "$port"
  handed_over=yes
  sudo systemctl enable "$UNIT@$port" >/dev/null 2>&1 || say "::warning::could not enable $UNIT@$port; it will not start after a reboot"
  if [ -n "$old_unit" ] && [ "$old_unit" != "$UNIT@$port" ]; then
    sleep "$DRAIN"
    sudo systemctl stop "$old_unit" || say "::warning::could not stop $old_unit"
    sudo systemctl disable "$old_unit" >/dev/null 2>&1 || true
  fi
  if [ -f "$UNIT_DIR/$LEGACY.service" ] && ! active "$LEGACY"; then
    sudo systemctl disable "$LEGACY" >/dev/null 2>&1 || true
    sudo rm -f "$UNIT_DIR/$LEGACY.service"
    sudo systemctl daemon-reload
    say "removed the old single-port $LEGACY.service"
  fi
  if [ -d "$APP/backend" ] && [ ! -L "$APP/backend" ]; then
    rm -rf "$APP/legacy-backend"
    mv -T "$APP/backend" "$APP/legacy-backend"
    say "kept the old tree as $APP/legacy-backend"
  fi
  point "$APP/backend" "$tree"
  rm -f "$APP/next"
}

cmd_release() {
  cand="${1:-}"; old_unit=""
  local live tree want target venv previous
  case "$cand" in 8000|8001) ;; *) die "release needs the port 'prepare' printed (8000 or 8001), got '$cand'" ;; esac
  live="$(live_port)"
  [ "$live" = "$(other "$cand")" ] || die "nginx proxies to $live, so $cand is the live port; run prepare again"
  tree="$(tree_of "$cand")"; venv="$(venv_of "$cand")"
  [ "$(readlink -f "$APP/next")" = "$(readlink -f "$tree")" ] || die "$APP/next does not point at $tree; run prepare again"
  [ -s "$tree/.env" ] || die "$tree/.env is missing or empty; the .env steps did not run"
  want="$(cat "$APP/venv-wanted")"
  target="$APP/venvs/$want"
  [ -f "$target/.complete" ] || die "$target is not a complete venv"
  old_unit="$(live_unit "$live")"
  previous="$(readlink -f "$APP/backend/.venv" 2>/dev/null || true)"

  build_tree "$tree" "$target" "$venv"

  queue_was=no; old_stopped=no; cand_started=no; handed_over=no
  trap 'on_exit' EXIT
  if active "$QUEUE"; then queue_was=yes; fi
  sudo systemctl stop "$QUEUE" 2>/dev/null || true

  if ! migrate "$tree" "$venv" 3; then
    if [ -n "$old_unit" ]; then
      say "::warning::prisma migrate deploy failed three times with $old_unit serving (read the attempts above: this fallback is for a pooler with no free session, EMAXCONNSESSION); stopping it to free its sessions and trying again. The API is down until $UNIT@$cand answers /health."
      sudo systemctl stop "$old_unit" || true
      old_stopped=yes
      sleep 5
    fi
    migrate "$tree" "$venv" 5 || die "prisma migrate deploy failed. Nothing was switched: nginx still proxies to $live, on the code that matches the schema the database has."
  fi

  sudo systemctl reset-failed "$UNIT@$cand" 2>/dev/null || true
  sudo systemctl start "$UNIT@$cand"
  cand_started=yes
  if ! wait_healthy "$cand"; then
    say "$UNIT@$cand did not answer /health within ${HEALTH_LIMIT}s:"
    sudo journalctl -u "$UNIT@$cand" -n 80 --no-pager || true
    die "the new release did not start. nginx still proxies to $live; it was not switched."
  fi

  promote "$cand" "$old_unit"
  if [ -n "$previous" ] && [ "$previous" != "$venv" ]; then printf '%s\n' "$previous" > "$APP/venv-previous"; fi
  if [ "$queue_was" = yes ] || [ -f "$UNIT_DIR/$QUEUE.service" ]; then
    sudo systemctl restart "$QUEUE" 2>/dev/null || true
  fi
  queue_was=restarted

  local rc=0 py="$venv/bin/python"
  if grep -q '^TWO_FACTOR_ENCRYPTION_KEY=.' "$tree/.env"; then
    set +e
    (cd "$tree" && "$py" -m scripts.reencrypt_two_factor_secrets); rc=$?
    set -e
    if [ "$rc" = 3 ]; then die "the two-step secrets above open with no configured key: put the missing key in TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS and deploy again"; fi
    if [ "$rc" != 0 ]; then say "::warning::the two-step key check could not run (exit $rc)"; fi
  fi

  tidy "$target"
  say "released: nginx -> $cand ($tree), venv $want"
}

on_exit() {
  local rc=$?
  if [ "$handed_over" != yes ]; then
    if [ "$cand_started" = yes ]; then sudo systemctl stop "$UNIT@$cand" 2>/dev/null || true; fi
    if [ "$old_stopped" = yes ] && [ -n "$old_unit" ]; then
      say "restarting $old_unit, which was stopped for the migration"
      sudo systemctl start "$old_unit" || true
    fi
  fi
  if [ "$queue_was" = yes ]; then sudo systemctl start "$QUEUE" 2>/dev/null || true; fi
  exit "$rc"
}

tidy() {
  local target="$1" keep d v used
  keep=" $target $(cat "$APP/venv-previous" 2>/dev/null || true) $APP/venvs/py3.12-legacy "
  for d in "$APP"/slots/*/backend "$APP/legacy-backend"; do
    [ -e "$d/.venv" ] && keep="$keep$(readlink -f "$d/.venv") "
  done
  for d in "$APP"/venvs/*; do
    [ -d "$d" ] || continue
    case "$keep" in *" $d "*) continue ;; esac
    rm -rf "$d"
  done
  for d in "$NODE_CACHE"/nodeenv-*; do
    [ -d "$d" ] || continue
    [ "$d" = "$NODE_CACHE/nodeenv-$NODE_PIN" ] || rm -rf "$d"
  done
  for d in "${CPYTHON_ROOT:-/opt/cpython}"/*; do
    [ -d "$d" ] || continue
    used=no
    for v in "$APP"/venvs/* "$APP"/slots/*/venv; do
      case "$(readlink -f "$v/bin/python" 2>/dev/null || true)" in "$d"/*) used=yes; break ;; esac
    done
    [ "$used" = yes ] || sudo rm -rf "$d"
  done
}

cmd_rollback() {
  local live back tree
  live="$(live_port)"; back="$(other "$live")"; tree="$(tree_of "$back")"
  [ -x "$tree/.venv/bin/python" ] && [ -f "$tree/.venv/.complete" ] || die "$tree has no complete .venv; there is nothing on $back to roll back to"
  [ -s "$tree/.env" ] || die "$tree/.env is missing"
  old_unit="$(live_unit "$live")"
  queue_was=no; old_stopped=no; cand_started=no; handed_over=no; cand="$back"
  trap 'on_exit' EXIT
  sudo systemctl reset-failed "$UNIT@$back" 2>/dev/null || true
  sudo systemctl start "$UNIT@$back"
  cand_started=yes
  wait_healthy "$back" || die "$UNIT@$back did not answer /health; nginx still proxies to $live"
  promote "$back" "$old_unit"
  sudo systemctl restart "$QUEUE" 2>/dev/null || true
  say "rolled back: nginx -> $back ($tree)"
}

cmd_restart() {
  local live unit
  live="$(live_port)"; unit="$(live_unit "$live")"
  [ -n "$unit" ] || unit="$UNIT@$live"
  sudo systemctl restart "$unit"
  sudo systemctl restart "$QUEUE" 2>/dev/null || true
  wait_healthy "$live" || die "$unit did not answer /health within ${HEALTH_LIMIT}s; read: sudo journalctl -u $unit -n 80"
}

cmd_status() {
  local live
  live="$(live_port)"
  echo "nginx proxies to: $live"
  echo "live unit:        $(live_unit "$live")"
  echo "backend ->        $(readlink -f "$APP/backend" 2>/dev/null || echo missing)"
  for p in 8000 8001; do
    echo "port $p:        $(sudo systemctl is-active "$UNIT@$p" 2>/dev/null || true), venv $(readlink -f "$(tree_of "$p")/.venv" 2>/dev/null || echo none)"
  done
}

case "${1:-}" in
  prepare) cmd_prepare ;;
  release) shift; cmd_release "$@" ;;
  rollback) cmd_rollback ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  *) die "usage: blue_green.sh prepare | release <port> | rollback | restart | status" ;;
esac
