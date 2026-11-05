#!/usr/bin/env bash
set -euo pipefail

: "${REPO:?REPO is owner/name}"
: "${SHA:?SHA is the commit being published}"
: "${API_BASE:?API_BASE is the public API origin}"
ENFORCE="${ENFORCE:-true}"
DEPLOY_WAIT_SECONDS="${DEPLOY_WAIT_SECONDS:-5400}"
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-900}"
POLL_SECONDS="${POLL_SECONDS:-30}"
WORKFLOW="${DEPLOY_WORKFLOW:-deploy-backend.yml}"
BACKEND_DIR="${BACKEND_DIR:-backend}"
PYTHON="${PYTHON:-python3}"
CURL="${CURL:-curl}"

say() { printf '%s\n' "$*"; }
summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi; }

refuse() {
  local title="$1" detail="$2"
  if [ "$ENFORCE" = true ]; then
    say "::error title=Android publish refused: ${title}::${detail}"
    summary "### Not published: ${title}" "" "${detail}"
    exit 1
  fi
  say "::warning title=Would refuse to publish: ${title}::${detail} This run does not publish, so it goes on."
  summary "### Gate (report only): ${title}" "" "${detail}"
  exit 0
}

deploy_runs() {
  gh api "repos/${REPO}/actions/workflows/${WORKFLOW}/runs?head_sha=${SHA}&per_page=50" \
    --jq '.workflow_runs | sort_by(.created_at) | reverse | .[] | "\(.id) \(.status) \(.conclusion // "none") \(.html_url)"'
}

started="$(date +%s)"
deadline=$(( started + DEPLOY_WAIT_SECONDS ))
[ "$ENFORCE" = true ] || deadline="$started"
say "Waiting for ${WORKFLOW} on ${SHA} to succeed, then for ${API_BASE}/health to report this commit's backend."

verdict=""
while :; do
  runs="$(deploy_runs)"
  if [ -z "$runs" ]; then
    verdict="none"
  elif grep -qvE '^[0-9]+ completed ' <<< "$runs"; then
    verdict="running"
  elif grep -qE '^[0-9]+ completed success ' <<< "$runs"; then
    verdict="success"
    break
  else
    verdict="failed"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
  say "${WORKFLOW} on ${SHA}: ${verdict}; looking again in ${POLL_SECONDS}s."
  sleep "$POLL_SECONDS"
done

newest="$(head -n1 <<< "$runs")"
url="${newest##* }"
case "$verdict" in
  none)
    refuse "no backend deploy" "No ${WORKFLOW} run exists for ${SHA}. The backend for this commit was never deployed: push the commit to main first, and publish once its deploy has succeeded." ;;
  running)
    refuse "backend deploy not finished" "${WORKFLOW} for ${SHA} has not finished after $(( ($(date +%s) - started) / 60 )) minutes (${url}). Publish again once it succeeds." ;;
  failed)
    refuse "backend deploy failed" "${WORKFLOW} for ${SHA} ended '$(awk '{print $3}' <<< "$newest")' (${url}). The new backend is not live, so the app that needs it is not published. Fix and redeploy the backend, then publish again." ;;
esac
succeeded="$(grep -m1 -E '^[0-9]+ completed success ' <<< "$runs")"
url="${succeeded##* }"
say "${WORKFLOW} for ${SHA} succeeded: ${url}"

want="$("$PYTHON" "${BACKEND_DIR}/app/release.py" "$BACKEND_DIR")"
deadline=$(( $(date +%s) + HEALTH_WAIT_SECONDS ))
[ "$ENFORCE" = true ] || deadline="$(date +%s)"
attempt=0
live=""
while :; do
  attempt=$((attempt + 1))
  body="$("$CURL" -fsS --max-time 20 -H 'cache-control: no-cache' "${API_BASE}/health?release-check=${GITHUB_RUN_ID:-local}-${attempt}" 2>/dev/null || true)"
  live="$(printf '%s' "$body" | "$PYTHON" -c 'import json,sys
try:
    print(json.load(sys.stdin).get("release") or "")
except Exception:
    print("")')"
  if [ "$live" = "$want" ]; then
    say "${API_BASE}/health reports release ${live}, this commit's backend."
    say "::notice title=Backend live::${WORKFLOW} succeeded (${url}) and /health reports ${live}."
    summary "Backend deploy ${url} succeeded and \`/health\` reports \`${live}\`, this commit's backend."
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
  say "/health reports '${live:-nothing}', this commit builds ${want}; looking again in ${POLL_SECONDS}s."
  sleep "$POLL_SECONDS"
done

if [ -z "$live" ]; then
  refuse "live backend unreadable" "${WORKFLOW} succeeded (${url}), but ${API_BASE}/health gave no release (body: '${body:0:200}'). A backend from before the release field cannot prove what it runs."
fi
refuse "a different backend is live" "${WORKFLOW} succeeded (${url}), but ${API_BASE}/health reports release ${live} and this commit's backend is ${want}. A newer or older backend is serving; publish from the commit that is live, or redeploy."
