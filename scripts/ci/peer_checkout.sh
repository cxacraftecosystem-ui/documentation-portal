#!/usr/bin/env bash
set -euo pipefail

: "${PEER:?PEER is the other portal, owner/name}"
BRANCH="${BRANCH:-main}"
PEER_URL="${PEER_URL:-https://github.com/${PEER}.git}"
HERE="${HERE:-${GITHUB_WORKSPACE:-$(pwd)}}"
DEST="${DEST:-$(dirname "$HERE")/${PEER#*/}}"
WAIT_SECONDS="${PEER_WAIT_SECONDS:-600}"
POLL_SECONDS="${PEER_POLL_SECONDS:-30}"
ENV_NAMES="${PEER_ENV_NAMES:-CROSS_PORTAL_PEER_REPO SIBLING_REPO}"
PORTAL_SPECIFIC="${PORTAL_SPECIFIC:-shared/questionnaire-form-contract.json shared/manifest.json}"
ALSO_COMPARED="${PEER_ALSO_COMPARED:-scripts/ci/peer_checkout.sh scripts/ci/test_peer_checkout.sh scripts/ci/await_backend_release.sh scripts/ci/test_await_backend_release.sh backend/app/release.py backend/tests/test_release_fingerprint.py backend/tests/test_questionnaire_start_sheet_contract.py}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

say() { printf '%s\n' "$*"; }
oneline() { tr '\n' ' '; }
summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi; }

tip_of() {
  git ls-remote "$PEER_URL" "$1" | awk 'NR==1 {print $1}'
}

newest_tag() {
  git ls-remote --tags --refs "$PEER_URL" 'v*' \
    | awk '{sub("refs/tags/", "", $2); print $2}' \
    | sort -V | tail -n1
}

fetch() {
  local ref="$1" into="$2"
  rm -rf "$into"
  git clone --quiet --depth 1 --branch "$ref" "$PEER_URL" "$into"
}

is_portal_specific() {
  local rel="$1" name
  for name in $PORTAL_SPECIFIC; do
    [ "$rel" = "$name" ] && return 0
  done
  return 1
}

differences() {
  local peer="$1" rel
  while IFS= read -r rel; do
    [ -f "$peer/$rel" ] || continue
    is_portal_specific "$rel" && continue
    if ! cmp -s <(tr -d '\r' < "$HERE/$rel") <(tr -d '\r' < "$peer/$rel"); then
      printf '%s\n' "$rel"
    fi
  done < <(compared_files | LC_ALL=C sort)
}

compared_files() {
  local rel
  (cd "$HERE" && find shared -type f)
  for rel in $ALSO_COMPARED; do
    if [ -f "$HERE/$rel" ]; then printf '%s\n' "$rel"; fi
  done
}

use() {
  local ref="$1" from="$2" verdict="$3" sha name
  sha="$(git -C "$from" rev-parse HEAD)"
  rm -rf "$DEST"
  mv "$from" "$DEST"
  if [ -n "${GITHUB_ENV:-}" ]; then
    for name in $ENV_NAMES; do printf '%s=%s\n' "$name" "$DEST" >> "$GITHUB_ENV"; done
  fi
  say "Shared files are compared with ${PEER}@${ref} (${sha}): ${verdict}."
  say "::notice title=Peer compared::${PEER}@${ref} ${sha} (${verdict})"
  summary "Shared files compared with \`${PEER}@${ref}\` at \`${sha}\`: ${verdict}."
}

candidates=()
if [ "$BRANCH" != "main" ] && [ -n "$(tip_of "refs/heads/$BRANCH")" ]; then
  candidates+=("$BRANCH")
  waitable=yes
else
  waitable=no
fi
candidates+=(main)
[ "$BRANCH" = "main" ] && waitable=yes
tag="$(newest_tag || true)"
[ -n "$tag" ] && candidates+=("$tag")
preferred="${candidates[0]}"
say "Peer candidates, in order: ${candidates[*]}"

declare -A seen=()
deadline=$(( $(date +%s) + WAIT_SECONDS ))
round=0
while :; do
  round=$((round + 1))
  for ref in "${candidates[@]}"; do
    if [ "$ref" = "$tag" ]; then sha="$(tip_of "refs/tags/$ref")"; else sha="$(tip_of "refs/heads/$ref")"; fi
    [ -n "$sha" ] || continue
    [ -n "${seen[$sha]:-}" ] && continue
    seen[$sha]=1
    dir="$WORK/$sha"
    fetch "$ref" "$dir"
    diff_list="$(differences "$dir")"
    if [ -z "$diff_list" ]; then
      verdict="its shared files match this checkout"
      [ "$round" -gt 1 ] && verdict="${verdict}, after waiting for the peer to push"
      use "$ref" "$dir" "$verdict"
      exit 0
    fi
    say "${PEER}@${ref} (${sha}) differs in: $(printf '%s\n' "$diff_list" | oneline)"
    rm -rf "$dir"
  done
  if [ "$waitable" != yes ] || [ "$(date +%s)" -ge "$deadline" ]; then break; fi
  say "No peer ref matches yet; the peer may not have pushed. Looking again in ${POLL_SECONDS}s (until $(date -u -d "@$deadline" +%H:%M:%SZ))."
  sleep "$POLL_SECONDS"
done

fetch "$preferred" "$WORK/final"
sha="$(git -C "$WORK/final" rev-parse HEAD)"
diff_list="$(differences "$WORK/final" | oneline)"
use "$preferred" "$WORK/final" "NO MATCH"
say "::error title=Shared files differ from the peer::No ref of ${PEER} (${candidates[*]}) has this checkout's shared files, after waiting up to ${WAIT_SECONDS}s where a peer push was possible. Compared last with ${PEER}@${preferred} ${sha}; these differ: ${diff_list}"
summary "No peer ref matched. Differing files: ${diff_list}"
exit 1
