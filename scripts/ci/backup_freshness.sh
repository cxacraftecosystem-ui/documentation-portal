# shellcheck shell=bash
backup_freshness() {
  local now="$1" nkey="$2" nwhen="$3" wkey="$4" wwhen="$5"
  local grace_h="${GRACE_HOURS:-6}" slot="${DUMP_SLOT_IST:?DUMP_SLOT_IST is not set}"
  local prefix="${BACKUP_PREFIX:?BACKUP_PREFIX is not set}"
  local ist=19800 day=86400
  local slot_s=$(( 10#${slot%%:*} * 3600 + 10#${slot##*:} * 60 ))

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

  if ! [[ "${ndate}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || [[ "${ndate}" < "${expected}" ]]; then
    echo "::error::no dump for ${expected} (${prefix}/${expected}.sql.gz, due at ${slot} IST, deadline $(date -u -d "@${deadline}" +%H:%M) UTC); the newest is ${nkey}. That night's run did not land: check this workflow's scheduled runs and run it by hand."
    return 1
  fi
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
  if (( wage_h > 192 )); then
    echo "::error::the newest weekly copy is ${wage_h} hours old (limit 8 days)."
    return 1
  fi
  return 0
}
