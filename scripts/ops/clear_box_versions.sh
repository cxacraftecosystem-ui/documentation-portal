#!/usr/bin/env bash
set -euo pipefail

APP="${APP:-/home/ubuntu/app}"
HOME_DIR="${HOME_DIR:-/home/ubuntu}"
SITE="${NGINX_SITE:-/etc/nginx/sites-available/fieldrepo}"
NODE_CACHE="${NODE_CACHE:-$HOME_DIR/.cache/prisma-python}"
CPYTHON_ROOT="${CPYTHON_ROOT:-/opt/cpython}"
NODE_PIN="${NODE_PIN:-26.13.0}"
LOG="${LOG:-$HOME_DIR/ops-logs/clear-earlier-versions.log}"
APPLY=no

for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=yes ;;
    --dry-run) APPLY=no ;;
    *) echo "usage: clear_box_versions.sh [--dry-run | --apply]" >&2; exit 64 ;;
  esac
done

say() { printf '%s\n' "$*"; }
die() { say "refused: $*"; exit 1; }
real() { readlink -f "$1" 2>/dev/null || true; }
size_of() { local s; s="$(sudo -n timeout 120 du -sh "$1" 2>/dev/null || timeout 120 du -sh "$1" 2>/dev/null || true)"; s="${s%%$'\t'*}"; echo "${s:-?}"; }
stamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }

KEEP=()
DELETE=()
REASON=()
USED_BY_PROCESS=" "

for p in /proc/[0-9]*; do
  for l in exe cwd; do
    t="$(sudo -n readlink -f "$p/$l" 2>/dev/null || readlink -f "$p/$l" 2>/dev/null || true)"
    if [ -n "$t" ]; then USED_BY_PROCESS="$USED_BY_PROCESS$t "; fi
  done
done

keep() { local r; r="$(real "$1")"; if [ -n "$r" ]; then KEEP+=("$r"); fi; }

is_kept() {
  local r="$1" k
  for k in "${KEEP[@]}"; do
    [ "$r" = "$k" ] && return 0
    case "$k/" in "$r"/*) return 0 ;; esac
    case "$r/" in "$k"/*) return 0 ;; esac
  done
  return 1
}

in_use() {
  local r="$1"
  case "$USED_BY_PROCESS" in *" $r "*|*" $r/"*) return 0 ;; esac
  return 1
}

plan() {
  local path="$1" why="$2" r
  [ -e "$path" ] || [ -L "$path" ] || return 0
  r="$(real "$path")"
  [ -n "$r" ] || r="$path"
  if is_kept "$r"; then say "keep    $path (live or rollback slot, or holds one)"; return 0; fi
  if in_use "$r"; then say "keep    $path (a running process uses it)"; return 0; fi
  if [ -e "$path/.keep" ]; then say "keep    $path (.keep)"; return 0; fi
  DELETE+=("$path")
  REASON+=("$why")
}

live_port() {
  sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$SITE" 2>/dev/null | head -n1
}

port="$(live_port)"
case "$port" in 8000|8001) ;; *) die "$SITE proxies to '${port:-nothing}', not 8000 or 8001, so the live slot is unknown" ;; esac
other=8000; if [ "$port" = 8000 ]; then other=8001; fi
say "host $(hostname), nginx proxies to $port, rollback port $other, mode $([ "$APPLY" = yes ] && echo APPLY || echo DRY-RUN)"

LAYOUT=""
if [ -d "$APP/slots" ]; then
  LAYOUT=slots
  [ -x "$APP/slots/$port/backend/.venv/bin/python" ] || die "$APP/slots/$port has no runnable venv; the live slot is not where this script expects"
  keep "$APP/slots/$port"
  keep "$APP/slots/$other"
  keep "$APP/backend"
  for s in "$APP/slots/$port" "$APP/slots/$other"; do keep "$s/backend/.venv"; done
  if [ -s "$APP/venv-wanted" ]; then keep "$APP/venvs/$(cat "$APP/venv-wanted")"; fi
  say "layout slots: live $(real "$APP/slots/$port"), previous $(real "$APP/slots/$other")"
elif [ -d "$APP/releases" ]; then
  LAYOUT=releases
  live="$(real "$APP/current")"
  [ -n "$live" ] && [ -d "$live" ] || die "$APP/current does not resolve to a directory"
  [ "$(real "$APP/slot-$port")" = "$live" ] || die "slot-$port and current disagree ($(real "$APP/slot-$port") vs $live); fix that before pruning"
  previous="$(real "$APP/slot-$other")"
  if [ -z "$previous" ] || [ "$previous" = "$live" ] || [ ! -x "$previous/backend/.venv/bin/python" ]; then
    previous="$(find "$APP/releases" -mindepth 2 -maxdepth 2 -type f -name .released -printf '%T@ %h\n' | sort -rn | cut -d' ' -f2- | while read -r d; do [ "$(real "$d")" != "$live" ] && [ -x "$d/backend/.venv/bin/python" ] && { real "$d"; break; }; done || true)"
  fi
  keep "$live"
  if [ -n "$previous" ]; then keep "$previous"; fi
  say "layout releases: live $live, previous ${previous:-<none>}"
else
  die "neither $APP/slots nor $APP/releases exists; unknown layout"
fi

[ "${#KEEP[@]}" -ge 2 ] || die "fewer than two trees to keep were found; refusing to prune"

if [ -d "$APP/releases" ]; then
  for d in "$APP"/releases/*; do
    [ -d "$d" ] || continue
    if [ -n "$(find "$d" -maxdepth 0 -newermt '-60 minutes' 2>/dev/null)" ]; then say "keep    $d (changed in the last hour; a deploy may be building it)"; continue; fi
    plan "$d" "earlier release tree and its venv"
  done
fi
if [ "$LAYOUT" = slots ]; then
  for d in "$APP"/slots/*; do
    [ -d "$d" ] || continue
    case "$(basename "$d")" in "$port"|"$other") continue ;; esac
    plan "$d" "slot directory that is neither live nor the rollback slot"
  done
fi
if [ -d "$APP/venvs" ]; then
  for d in "$APP"/venvs/* "$APP"/venvs/.incoming.*; do
    [ -e "$d" ] || continue
    plan "$d" "earlier base venv"
  done
fi
if [ -d "$APP/legacy-backend" ]; then plan "$APP/legacy-backend" "pre blue/green tree"; fi
if [ -d "$APP/backend" ] && [ ! -L "$APP/backend" ] && [ "$LAYOUT" = releases ]; then plan "$APP/backend" "pre release-layout tree"; fi
for f in "$APP"/.release-venv.tar.gz "$APP"/requirements.lock.incoming "$APP"/.cpython-download.*; do
  if [ -e "$f" ]; then plan "$f" "leftover deploy upload"; fi
done

for d in "$NODE_CACHE"/nodeenv-*; do
  [ -d "$d" ] || continue
  [ "$d" = "$NODE_CACHE/nodeenv-$NODE_PIN" ] && continue
  [ "$(real "$NODE_CACHE/nodeenv")" = "$(real "$d")" ] && continue
  plan "$d" "Node for an earlier Prisma CLI pin"
done

kept_venvs=()
for k in "${KEEP[@]}"; do
  for v in "$k/backend/.venv" "$k"; do
    if [ -x "$v/bin/python" ]; then kept_venvs+=("$(real "$v")"); fi
  done
done
engines=" "
all_reference=yes
for v in "${kept_venvs[@]}"; do
  c="$(ls "$v"/lib/python*/site-packages/prisma/client.py 2>/dev/null | head -n1 || true)"
  e="$( [ -n "$c" ] && grep -oE "$NODE_CACHE/binaries/[^/']+" "$c" | head -n1 || true)"
  if [ -n "$e" ]; then engines="$engines$e "; else all_reference=no; fi
done
if [ "$all_reference" = yes ] && [ "$engines" != " " ]; then
  for d in "$NODE_CACHE"/binaries/*; do
    [ -d "$d" ] || continue
    case "$engines" in *" $d "*) continue ;; esac
    plan "$d" "Prisma engine no kept venv uses"
  done
fi

for d in "$CPYTHON_ROOT"/*; do
  [ -d "$d" ] || continue
  used=no
  for v in "${kept_venvs[@]}"; do
    case "$(real "$v/bin/python")" in "$d"/*) used=yes; break ;; esac
  done
  if [ "$used" = yes ]; then say "keep    $d (interpreter of a kept venv)"; continue; fi
  plan "$d" "interpreter no kept venv runs on"
done
for d in "$CPYTHON_ROOT"/.incoming.* "$CPYTHON_ROOT"/.stage-*; do
  if [ -e "$d" ]; then plan "$d" "half-installed interpreter"; fi
done

for d in "$HOME_DIR/.cache/pip" "$HOME_DIR/.cache/uv" "$HOME_DIR/.npm/_cacache" "$NODE_CACHE/nodeenv-replaced"; do
  if [ -e "$d" ]; then plan "$d" "download cache"; fi
done
debs="$(find /var/cache/apt/archives -maxdepth 1 -name '*.deb' 2>/dev/null | wc -l | tr -d ' ')"

say ""
say "keep:"
for k in "${KEEP[@]}"; do say "  $k"; done
say ""
if [ "${#DELETE[@]}" -eq 0 ] && [ "$debs" = 0 ]; then
  say "nothing to delete"
  exit 0
fi
say "delete:"
for i in "${!DELETE[@]}"; do
  say "  $(size_of "${DELETE[$i]}")	${DELETE[$i]}	(${REASON[$i]})"
done
if [ "$debs" != 0 ]; then say "  $(size_of /var/cache/apt/archives)	/var/cache/apt/archives/*.deb ($debs packages, apt-get clean)"; fi
say ""
say "disk before: $(df -h / | tail -1)"

if [ "$APPLY" != yes ]; then
  say "DRY RUN: nothing was deleted. Run again with --apply."
  exit 0
fi

mkdir -p "$(dirname "$LOG")"
for i in "${!DELETE[@]}"; do
  p="${DELETE[$i]}"
  r="$(real "$p")"; [ -n "$r" ] || r="$p"
  if is_kept "$r" || in_use "$r"; then say "skipped $p (became live or in use)"; continue; fi
  s="$(size_of "$p")"
  if rm -rf -- "$p" 2>/dev/null || sudo -n rm -rf -- "$p" 2>/dev/null; then
    say "deleted $p ($s)"
    printf '%s\tdeleted\t%s\t%s\t%s\n' "$(stamp)" "$s" "$p" "${REASON[$i]}" >> "$LOG"
  else
    say "FAILED  $p"
    printf '%s\tfailed\t%s\t%s\t%s\n' "$(stamp)" "$s" "$p" "${REASON[$i]}" >> "$LOG"
  fi
done
if [ "$debs" != 0 ]; then
  if sudo -n apt-get clean 2>/dev/null; then
    say "deleted /var/cache/apt/archives/*.deb ($debs packages)"
    printf '%s\tdeleted\t%s packages\t/var/cache/apt/archives/*.deb\tapt-get clean\n' "$(stamp)" "$debs" >> "$LOG"
  fi
fi
say "disk after: $(df -h / | tail -1)"
say "logged to $LOG"
