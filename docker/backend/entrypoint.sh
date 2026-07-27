#!/bin/sh
# ---------------------------------------------------------------------------------------------
# Backend container entrypoint.
#
# It exists for ONE reason: to make it impossible to accidentally run this container against the
# production database.
#
# backend/.env in this repository holds the LIVE Supabase pooler URL, the real JWT secret and the
# AWS media credentials. It is excluded from the build context (.dockerignore) so it can never be
# baked into an image — but nothing stops a developer from passing it in by hand:
#
#     docker compose run --rm --env-file backend/.env api      # <- this is the accident
#
# and a container that quietly connected would be writing to real researchers' records from a
# throwaway sandbox. So before uvicorn is ever exec'd, the connection string is inspected and a
# remote host is refused outright. Refusing is the whole point: a warning gets scrolled past.
#
# The escape hatch is a separate, deliberate variable — you cannot trip over it, you have to type
# it — and it is there for the one legitimate case, a read-only investigation against a staging
# database that happens to be remote.
# ---------------------------------------------------------------------------------------------
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

# The host classification mirrors app/core/config.py::_is_local_db_host, on purpose: the same
# definition of "local" that decides whether to force sslmode=require decides whether this
# container may run at all. stdlib only — this runs before the application is imported, and a
# guard that needs the app to be importable is a guard that fails open when the app is broken.
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

# One line, at startup, saying exactly what this process is attached to. Cheap, and it turns the
# "wait, which database am I looking at?" question into something `docker logs` already answers.
echo "fieldrepo: database host ${DB_HOST} (${DB_KIND}); queue worker=${MEDIA_QUEUE_WORKER_ENABLED:-unset}"

exec "$@"
