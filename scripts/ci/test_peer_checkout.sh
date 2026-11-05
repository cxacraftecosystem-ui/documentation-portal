#!/usr/bin/env bash
set -uo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/peer_checkout.sh"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
failures=0

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.invalid GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.invalid

peer_src="$root/peer-src"
bare="$root/peer.git"
here="$root/work/here"
mkdir -p "$here/shared" "$peer_src/shared"
git init -q --bare -b main "$bare"
git -C "$peer_src" init -q -b main
git -C "$peer_src" remote add origin "$bare"

commit_peer() {
  local value="$1" specific="$2"
  printf '%s\n' "$value" > "$peer_src/shared/contract.json"
  printf '%s\n' "$specific" > "$peer_src/shared/questionnaire-form-contract.json"
  git -C "$peer_src" add -A && git -C "$peer_src" commit -q -m "$value" --allow-empty
  git -C "$peer_src" push -q origin HEAD:main
}

set_here() {
  printf '%s\n' "$1" > "$here/shared/contract.json"
  printf 'ours\n' > "$here/shared/questionnaire-form-contract.json"
  printf 'only here\n' > "$here/shared/only-here.json"
}

run() {
  local out
  out="$(PEER=owner/peer PEER_URL="file://$bare" HERE="$here" DEST="$root/work/peer" \
    GITHUB_ENV="$root/env" GITHUB_STEP_SUMMARY="$root/summary" \
    PEER_WAIT_SECONDS="${WAIT:-0}" PEER_POLL_SECONDS=1 BRANCH="${BRANCH:-main}" bash "$script" 2>&1)"
  rc=$?
  printf '%s' "$out"
  return "$rc"
}

expect() {
  local want="$1" name="$2" pattern="$3" out got
  if out="$(run)"; then got=PASS; else got=FAIL; fi
  if [ "$got" != "$want" ]; then
    echo "not ok - $name: wanted $want, got $got"; printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  elif ! grep -qE -- "$pattern" <<< "$out"; then
    echo "not ok - $name: output lacks /$pattern/"; printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  else
    echo "ok - $name"
  fi
}

commit_peer one theirs
set_here one
expect PASS "peer main matches; a portal-specific file and a file only here are ignored" "owner/peer@main [0-9a-f]{40} \(its shared files match"
if grep -q "^CROSS_PORTAL_PEER_REPO=$root/work/peer$" "$root/env" && [ -f "$root/work/peer/shared/contract.json" ]; then
  echo "ok - the matched checkout is exported"
else
  echo "not ok - the matched checkout is exported"; failures=$((failures + 1))
fi

git -C "$peer_src" tag v0.0.1
git -C "$peer_src" push -q origin v0.0.1
commit_peer two theirs
expect PASS "peer main moved on; the newest release tag still matches" "owner/peer@v0.0.1 [0-9a-f]{40} \(its shared files match"

set_here three
WAIT=0 expect FAIL "a real mismatch fails and names the SHA it compared" "::error title=Shared files differ from the peer::.*owner/peer@main [0-9a-f]{40}; these differ: shared/contract.json"

( sleep 3; commit_peer three theirs ) &
WAIT=30 expect PASS "the peer pushes the same change a moment later" "after waiting for the peer to push"
wait

set_here four
BRANCH=feature-x WAIT=30 expect FAIL "a branch with no peer branch does not wait" "these differ: shared/contract.json"

if [ "$failures" -gt 0 ]; then echo "$failures failed"; exit 1; fi
echo "all passed"
