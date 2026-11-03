#!/bin/sh
set -eu

fail() {
    echo "" >&2
    echo "  ============================================================================" >&2
    echo "  REFUSING TO START" >&2
    echo "  ============================================================================" >&2
    printf '  %s\n' "$@" >&2
    echo "  ============================================================================" >&2
    echo "" >&2
    exit 1
}

if [ -z "${DATABASE_URL:-}" ]; then
    fail "DATABASE_URL is not set." \
         "" \
         "This container never reads backend/.env — that file points at PRODUCTION." \
         "Local values live in .env.docker.example at the repository root:" \
         "" \
         "    cp .env.docker.example .env     # then: docker compose --profile full up" \
         "" \
         "See docs/DOCKER.md."
fi

DB_HOST_KIND="$(
    python - <<'PY'
import os
from ipaddress import ip_address
from urllib.parse import urlsplit

LOCAL_NAMES = {"localhost", "host.docker.internal", "postgres", "db"}

host = (urlsplit(os.environ["DATABASE_URL"]).hostname or "").strip().strip("[]").lower()
if not host:
    print("unknown")
elif host in LOCAL_NAMES or host.endswith((".local", ".internal", ".localhost")):
    print(f"local {host}")
else:
    try:
        address = ip_address(host)
    except ValueError:
        print(f"remote {host}")
    else:
        kind = "local" if (address.is_loopback or address.is_private or address.is_link_local) else "remote"
        print(f"{kind} {host}")
PY
)"

DB_KIND="${DB_HOST_KIND%% *}"
DB_HOST="${DB_HOST_KIND#* }"

if [ "$DB_KIND" = "remote" ] && [ "${ALLOW_REMOTE_DATABASE:-}" != "yes-i-mean-it" ]; then
    fail "DATABASE_URL points at a REMOTE host: ${DB_HOST}" \
         "" \
         "This is the production-database guard. Containers in this stack are expected to talk" \
         "to the compose Postgres service ('postgres:5432'), not to Supabase." \
         "" \
         "If you passed --env-file backend/.env, that is the bug: that file is PRODUCTION." \
         "Use the repo-root .env (copied from .env.docker.example) instead." \
         "" \
         "If you genuinely mean to reach a remote database, set ALLOW_REMOTE_DATABASE=yes-i-mean-it" \
         "and be certain MEDIA_QUEUE_WORKER_ENABLED is false — a second queue worker against the" \
         "real database will start re-processing live media jobs."
fi

if [ "$DB_KIND" = "unknown" ]; then
    fail "DATABASE_URL has no parseable host: ${DATABASE_URL}"
fi

echo "fieldrepo: database host ${DB_HOST} (${DB_KIND}); queue worker=${MEDIA_QUEUE_WORKER_ENABLED:-unset}"

exec "$@"
