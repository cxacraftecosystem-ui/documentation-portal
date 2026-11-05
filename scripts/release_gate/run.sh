#!/usr/bin/env bash
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "${here}/../.." && pwd)"
export GATE_OUT="${GATE_OUT:-${root}/release-gate-out}"
mkdir -p "$GATE_OUT"
status=0

python3 "${here}/run_checks.py" || status=1

specs=()
for spec in "${root}"/frontend/release-gate/*.spec.ts; do
  [ -f "$spec" ] || continue
  name="$(basename "$spec" .spec.ts)"
  if [ -z "${GATE_ONLY:-}" ] || [[ " ${GATE_ONLY} " == *" ${name} "* ]]; then
    specs+=("release-gate/${name}.spec.ts")
  fi
done
if [ "${#specs[@]}" -gt 0 ]; then
  (cd "${root}/frontend" && npx playwright test -c playwright.release-gate.config.ts "${specs[@]}") || status=1
fi

python3 "${here}/run_checks.py" --summary || status=1
exit "$status"
