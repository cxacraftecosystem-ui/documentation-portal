# shellcheck shell=bash
# The verdict of backup-db.yml's `freshness` job, as a function with no I/O of its own, so that
# scripts/ci/test_backup_freshness.sh can run it against fixed clocks. Sourced, never executed.
# IDENTICAL IN BOTH PORTALS: the sibling repository carries this file byte for byte. Change both.
#
# ─── WHY THE EXPECTED DATE COMES FROM THE DUMP'S SCHEDULE, NOT FROM THE CLOCK (2026-11-03) ──
# The check used to demand `$(TZ=Asia/Kolkata date +%F).sql.gz`, "today in India". That holds only
# while the check runs on the same India day as the dump it is looking for, and GitHub does not
# promise that: on 2026-11-02 it started the 09:23 UTC check at 19:37 UTC, 01:07 IST on 3 November,
# and the check demanded 2026-11-03.sql.gz an hour and forty minutes before that dump was due. Both
# portals went red (runs 41511588787 and 41513651103) with both dumps sitting in S3.
#
# So the date to demand is the newest dump that SHOULD EXIST by now: today's India date once India
# time is past the dump's slot plus a grace window for GitHub's own delay, yesterday's before that.
# A check started late, even past midnight, then still asks for the dump it was scheduled to ask
# for; a check on time asks for exactly what it always did. The grace is 6 h because the worst
# start GitHub gave a scheduled run of ours in October 2026 was just under 7 h on a top-of-the-hour
# slot, and our slots sit on odd minutes, where the queue is shorter. A dump later than slot + 6 h
# is still caught: the very next check after the deadline demands it.
#
# India time has had no daylight saving since 1945, so IST is UTC+05:30 by arithmetic and the
# function needs no time-zone database.
#
# backup_freshness NOW NIGHTLY_KEY NIGHTLY_WHEN WEEKLY_KEY WEEKLY_WHEN
#   NOW            seconds since the epoch
#   *_KEY, *_WHEN  the newest object's key and LastModified, as `aws s3api list-objects-v2
#                  --output text` prints them; the key is "None" (or empty) when there is none.
# Reads DUMP_SLOT_IST (HH:MM, the dump cron in India time), BACKUP_PREFIX and, optionally,
# GRACE_HOURS (default 6). Prints the findings as workflow commands and `key=value` lines, and
# returns 1 when the backup is missing or stale.
backup_freshness() {
  local now="$1" nkey="$2" nwhen="$3" wkey="$4" wwhen="$5"
  local grace_h="${GRACE_HOURS:-6}" slot="${DUMP_SLOT_IST:?DUMP_SLOT_IST is not set}"
  local prefix="${BACKUP_PREFIX:?BACKUP_PREFIX is not set}"
  local ist=19800 day=86400
  local slot_s=$(( 10#${slot%%:*} * 3600 + 10#${slot##*:} * 60 ))

  # Midnight of the India day `now` falls in, as a UTC epoch.
  local midnight=$(( (now + ist) - (now + ist) % day - ist ))
  if (( now < midnight + slot_s + grace_h * 3600 )); then
    midnight=$(( midnight - day ))
  fi
  local deadline=$(( midnight + slot_s + grace_h * 3600 ))
  local expected
  expected="$(date -u -d "@$(( midnight + ist ))" +%F)"
  echo "expected=${expected}"
  echo "deadline=$(date -u -d "@${deadline}" +%FT%TZ)"

  if [ -z "${nkey}" ] || [ "${nkey}" = "None" ] || [ -z "${nwhen}" ]; then
    echo "::error::no nightly dump under ${prefix}/. Treat the database as having NO backup."
    return 1
  fi
  local nepoch
  nepoch="$(date -u -d "${nwhen}" +%s)"
  local ndate="${nkey#"${prefix}/"}"
  ndate="${ndate%.sql.gz}"
  echo "nightly=${nkey}"
  echo "nightly_age_h=$(( (now - nepoch) / 3600 ))"

  # A manual run after midnight writes a key newer than `expected`; that is fresher, not missing.
  if ! [[ "${ndate}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || [[ "${ndate}" < "${expected}" ]]; then
    echo "::error::no dump for ${expected} (${prefix}/${expected}.sql.gz, due at ${slot} IST, deadline $(date -u -d "@${deadline}" +%H:%M) UTC); the newest is ${nkey}. That night's run did not land: check this workflow's scheduled runs and run it by hand."
    return 1
  fi
  # The 26 h rule, measured at the expected slot's deadline rather than at `now`, so a check GitHub
  # starts late cannot age a good dump past the limit. It catches an object whose name is current
  # and whose content is not: a copy, a restore or a hand upload under a fresh date.
  if (( deadline - nepoch > 26 * 3600 )); then
    echo "::error::the newest nightly dump ${nkey} was written $(( (deadline - nepoch) / 3600 )) hours before the ${expected} deadline (limit 26)."
    return 1
  fi

  if [ -z "${wkey}" ] || [ "${wkey}" = "None" ] || [ -z "${wwhen}" ]; then
    echo "::warning::no weekly copy yet. The next nightly run writes one."
    return 0
  fi
  local wage_h=$(( (now - $(date -u -d "${wwhen}" +%s)) / 3600 ))
  echo "weekly=${wkey}"
  echo "weekly_age_h=${wage_h}"
  # Measured at `now`: the first dump of each ISO week writes a weekly copy, so consecutive copies
  # are about 7 days apart, and the eighth day absorbs a late dump and a late check together.
  if (( wage_h > 192 )); then
    echo "::error::the newest weekly copy is ${wage_h} hours old (limit 8 days)."
    return 1
  fi
  return 0
}
