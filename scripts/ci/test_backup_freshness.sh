#!/usr/bin/env bash
# Runs scripts/ci/backup_freshness.sh against fixed clocks. Run by Checks → Deploy scripts.
# IDENTICAL IN BOTH PORTALS, like the file it tests. Every case is a real or plausible start time
# of the freshness check; the first two are the runs GitHub delayed on 2026-11-02.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/backup_freshness.sh
. "${here}/backup_freshness.sh"

export BACKUP_PREFIX=backups/test-portal
failures=0

show() { local line; while IFS= read -r line; do echo "    ${line}"; done <<< "$1"; }

# expect PASS|FAIL "case" NOW_UTC NIGHTLY_DATE|None NIGHTLY_WHEN WEEKLY_WHEN|None [expected-date]
expect() {
  local want="$1" name="$2" now nkey wkey out got
  now="$(date -u -d "$3" +%s)"
  if [ "$4" = None ]; then nkey=None; else nkey="${BACKUP_PREFIX}/$4.sql.gz"; fi
  if [ "$6" = None ]; then wkey=None; else wkey="${BACKUP_PREFIX}/weekly/2026-W45_x.sql.gz"; fi
  if out="$(backup_freshness "${now}" "${nkey}" "$5" "${wkey}" "$6" 2>&1)"; then got=PASS; else got=FAIL; fi
  if [ "${got}" != "${want}" ]; then
    echo "not ok - ${name}: wanted ${want}, got ${got}"; show "${out}"
    failures=$(( failures + 1 ))
  elif [ -n "${7:-}" ] && ! grep -qx "expected=$7" <<< "${out}"; then
    echo "not ok - ${name}: wanted expected=$7"; show "${out}"
    failures=$(( failures + 1 ))
  else
    echo "ok - ${name}"
  fi
}

# Designer portal: dump at 21:17 UTC = 02:47 IST, so the deadline is 08:47 IST = 03:17 UTC.
export DUMP_SLOT_IST=02:47
W=2026-11-01T21:40:00Z   # a weekly copy written early on Monday 2 Nov, IST

expect PASS "on-time check (14:53 IST)" \
  2026-11-02T09:23:00Z 2026-11-02 2026-11-01T21:40:00Z "$W" 2026-11-02
expect PASS "check delayed past IST midnight (19:37 UTC on 2 Nov, run 41511588787)" \
  2026-11-02T19:37:00Z 2026-11-02 2026-11-01T21:40:00Z "$W" 2026-11-02
expect PASS "check delayed to just before the next deadline (08:46 IST)" \
  2026-11-03T03:16:00Z 2026-11-02 2026-11-01T21:40:00Z "$W" 2026-11-02
expect PASS "dump delayed into the next morning, check before the deadline (07:30 IST)" \
  2026-11-03T02:00:00Z 2026-11-02 2026-11-01T21:40:00Z "$W" 2026-11-02
expect PASS "dump delayed into the next morning (09:00 IST), check after it lands" \
  2026-11-03T09:23:00Z 2026-11-03 2026-11-03T03:30:00Z "$W" 2026-11-03
expect PASS "manual dump after midnight is newer than expected" \
  2026-11-02T19:50:00Z 2026-11-03 2026-11-02T19:45:00Z "$W" 2026-11-02
expect PASS "no weekly copy yet is a warning, not a failure" \
  2026-11-02T09:23:00Z 2026-11-02 2026-11-01T21:40:00Z None

expect FAIL "missing dump: on-time check finds yesterday's" \
  2026-11-03T09:23:00Z 2026-11-02 2026-11-01T21:40:00Z "$W" 2026-11-03
expect FAIL "missing dump: delayed check past midnight finds the day before yesterday's" \
  2026-11-02T19:37:00Z 2026-11-01 2026-10-31T21:40:00Z "$W" 2026-11-02
expect FAIL "dump later than the grace: check just after the deadline (08:48 IST)" \
  2026-11-03T03:18:00Z 2026-11-02 2026-11-01T21:40:00Z "$W" 2026-11-03
expect FAIL "no nightly dump at all" \
  2026-11-02T09:23:00Z None "" "$W"
expect FAIL "stale dump: current name, written days earlier" \
  2026-11-02T09:23:00Z 2026-11-02 2026-10-30T21:40:00Z "$W"
expect FAIL "a key that is not a date" \
  2026-11-02T09:23:00Z latest 2026-11-01T21:40:00Z "$W"
expect FAIL "stale weekly copy (9 days)" \
  2026-11-02T09:23:00Z 2026-11-02 2026-11-01T21:40:00Z 2026-10-24T09:00:00Z

# Documentation portal: dump at 21:47 UTC = 03:17 IST, deadline 09:17 IST = 03:47 UTC.
export DUMP_SLOT_IST=03:17
expect PASS "FR check delayed past IST midnight (19:50 UTC on 2 Nov, run 41513651103)" \
  2026-11-02T19:50:00Z 2026-11-02 2026-11-01T22:10:00Z "$W" 2026-11-02
expect PASS "FR 09:00 IST is still before its own deadline" \
  2026-11-03T03:30:00Z 2026-11-02 2026-11-01T22:10:00Z "$W" 2026-11-02
expect FAIL "FR 09:30 IST is past its deadline and has no 3 Nov dump" \
  2026-11-03T04:00:00Z 2026-11-02 2026-11-01T22:10:00Z "$W" 2026-11-03

if [ "${failures}" -gt 0 ]; then
  echo "${failures} case(s) failed"
  exit 1
fi
echo "all cases passed"
