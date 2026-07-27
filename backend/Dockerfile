# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------------------------
# Field Repository API — multi-stage image.
#
# BUILD FROM THE REPOSITORY ROOT, not from backend/:
#     docker build -f backend/Dockerfile -t field-repository-backend:local .
# The context has to include docker/backend/entrypoint.sh as well as backend/, and the single
# root .dockerignore is what keeps that context small (and keeps backend/.env out of it).
#
# The split is: a fat `builder` that owns pip, a C toolchain and Node.js, and a thin `runtime`
# that owns nothing but a virtualenv, the application source and one Rust binary. Node exists
# ONLY in the builder — see the prisma stage below for why that is safe.
#
# Deliberately NOT in the runtime image: pip's build toolchain, Node.js, npm, the Prisma CLI,
# git, and (by default) ffmpeg. Every one of them is a build-time need that nothing at request
# time can reach.
# ---------------------------------------------------------------------------------------------

ARG PYTHON_VERSION=3.12

# Install ffmpeg into the runtime image. OFF by default because it costs ~180 MB and nothing on
# the request path needs it: pydub is imported lazily inside the two functions that convert audio
# (services/ai.py chunking, the /data audio->mp4 endpoint), and both already degrade with a clear
# message when it is absent. Turn it on only if you are exercising transcription locally:
#     docker compose build --build-arg INSTALL_FFMPEG=true api
ARG INSTALL_FFMPEG=false


# =============================================================================================
# Stage 1 — builder: resolve dependencies into a self-contained virtualenv.
# =============================================================================================
FROM python:${PYTHON_VERSION}-slim-bookworm AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

# build-essential is here for the *possibility* of an sdist-only transitive dependency. Every
# direct dependency ships a manylinux wheel today (verified: the whole install resolves without
# compiling), so this apt layer is insurance, and it is thrown away with the stage either way.
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential \
 && rm -rf /var/lib/apt/lists/*

# A virtualenv rather than the system site-packages: it makes the hand-off to the runtime stage a
# single directory copy with no chance of picking up a distro-managed Python package by accident.
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}" \
    VIRTUAL_ENV=/opt/venv

WORKDIR /build

# Only pyproject.toml, so this layer — the slow one — is reused on every source-only change.
COPY backend/pyproject.toml ./pyproject.toml

# Install the DEPENDENCIES, not the project. `pip install .` would also copy an `app` package into
# site-packages, and `app/data/questionnaire_questions.json` is not declared as package data, so
# that copy would silently ship without it and the questionnaire seed would fail at runtime. The
# source tree is mounted into the runtime image as a plain directory instead, which cannot lose a
# file. tomllib is stdlib on 3.11+, so reading the list needs nothing installed first.
#
# The core [project.dependencies] list ONLY. Optional extras (dev, and any future cache/ai extras)
# are deliberately not installed: this image is meant to weigh what the EC2 t3.micro weighs.
RUN python -c "import tomllib, pathlib; \
print('\n'.join(tomllib.loads(pathlib.Path('pyproject.toml').read_text())['project']['dependencies']))" \
      > /tmp/requirements.txt \
 && pip install --no-cache-dir -r /tmp/requirements.txt


# =============================================================================================
# Stage 2 — prisma: generate the Python client and fetch the query engine.
#
# THIS IS THE STAGE THAT MAKES THE IMAGE WORK. prisma-client-py ships no generated code: the
# `prisma` package on PyPI is a generator, and `from prisma import Prisma` raises
# "Client hasn't been generated yet" until `prisma generate` has written the models into
# site-packages/prisma/. That generator is a Node program, so Node.js has to exist *somewhere* —
# but only here, and this whole stage is discarded.
#
# At run time the client talks to a standalone Rust binary (prisma-client-py uses the *binary*
# engine, not a Node process), which is why the runtime image needs the engine and not Node.
# =============================================================================================
FROM builder AS prisma

# Node is what runs the Prisma CLI. bookworm's nodejs is 18.x, comfortably above the CLI's
# documented floor of 16.13. npm comes along because prisma-client-py's node resolver can reach
# for either binary.
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm \
 && rm -rf /var/lib/apt/lists/*

# Pin every cache prisma-client-py writes to, so the artefacts land in known directories that the
# runtime stage can copy. Left at their defaults they go to $HOME/.cache, and $HOME differs
# between the root user that builds and the unprivileged user that runs — the engine would be
# downloaded again on first request, inside a container that has no compiler, no network
# guarantee and no write access to that path.
ENV PRISMA_BINARY_CACHE_DIR=/opt/prisma-engines \
    PRISMA_HOME_DIR=/opt/prisma-home \
    PRISMA_NODEENV_CACHE_DIR=/opt/prisma-home/nodeenv \
    PRISMA_USE_GLOBAL_NODE=true

# A placeholder connection string, NOT a real one. `prisma generate` refuses to run when a
# variable referenced by the datasource block is unresolvable, but it never opens a connection —
# it only parses the URL. Nothing about the database is baked into the client, and this value
# never survives into the runtime stage.
ENV DATABASE_URL=postgresql://placeholder:placeholder@127.0.0.1:5432/placeholder

COPY backend/prisma ./prisma

RUN python -m prisma py fetch \
 && python -m prisma generate --schema=./prisma/schema.prisma \
 && python -c "from prisma import Prisma; Prisma; print('prisma client import OK')"

# Lift the ONE file the runtime needs out of the 81 MB `py fetch` left behind. That cache is an npm
# tree — the Prisma CLI, its JavaScript build output, the schema engine, and the query engine
# binary stored twice under two package names. All of it except this single Rust binary is Node
# payload, and the runtime image has no Node to run it with.
#
# Found rather than hard-coded: the filename carries the platform triple
# (query-engine-debian-openssl-3.0.x today), so a base-image bump to a different OpenSSL would
# silently break a literal path. The size floor skips the .js and .d.ts files that share the prefix.
#
# --version proves here, in the build, that it executes with no Node and no CLI beside it. If that
# ever stops being true this fails loudly at build time instead of on the first query.
RUN mkdir -p /opt/prisma-engine \
 && engine="$(find /opt/prisma-engines -type f -size +1M -name 'query-engine-*' | head -n 1)" \
 && test -n "$engine" \
 && cp "$engine" /opt/prisma-engine/query-engine \
 && chmod 0755 /opt/prisma-engine/query-engine \
 && /opt/prisma-engine/query-engine --version

# The production-database guard, in this stage too. The `migrate` service in docker-compose.yml is
# built from HERE rather than from the runtime stage, because `prisma migrate` is a Node program and
# the runtime image deliberately has no Node — and a migration container is the single worst thing
# to let loose on the wrong database. Same script, same refusal.
COPY docker/backend/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]


# =============================================================================================
# Stage 3 — runtime.
# =============================================================================================
FROM python:${PYTHON_VERSION}-slim-bookworm AS runtime

ARG INSTALL_FFMPEG

# PYTHONUNBUFFERED so uvicorn's log lines reach `docker logs` as they happen rather than when a
# 4 KB pipe buffer fills — the difference between watching a slow startup and staring at nothing.
# PRISMA_QUERY_ENGINE_BINARY is an absolute path to the engine, and prisma-client-py checks it
# FIRST — before the working directory, before the generated client's embedded paths, before the
# binary cache — and skips its version probe when it is set. That is what lets the runtime image
# carry one 18 MB binary instead of the whole npm cache the build produced. Point it at a path that
# does not exist and the client raises BinaryNotFoundError naming the path, which is a far better
# failure than the silent re-download the unpinned cache directory used to invite.
#
# PYTHONPATH=/app because the application is a directory here, not an installed package (see the
# builder stage for why). uvicorn happens to work without it — it puts its working directory on
# sys.path — but `python scripts/seed_admin.py` does not: sys.path[0] becomes /app/scripts and the
# import of `app.core.config` fails. Setting it once makes the maintenance scripts run the way
# their documentation says they do.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/opt/venv/bin:${PATH}" \
    VIRTUAL_ENV=/opt/venv \
    PYTHONPATH=/app \
    PRISMA_QUERY_ENGINE_BINARY=/opt/prisma-engine/query-engine

# Only what the engine binary itself links against. openssl is a hard requirement of the Prisma
# query engine; ca-certificates is for the outbound TLS every boto3/S3 and AI-provider call makes.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates openssl \
 && if [ "$INSTALL_FFMPEG" = "true" ]; then apt-get install -y --no-install-recommends ffmpeg; fi \
 && rm -rf /var/lib/apt/lists/*

# An unprivileged, fixed-uid account. Fixed rather than auto-assigned so a bind-mounted volume has
# predictable ownership, and >10000 so it cannot collide with a host system account.
RUN groupadd --system --gid 10001 fieldrepo \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home fieldrepo

# The virtualenv carries the generated Prisma client with it — `prisma generate` wrote the models
# into /opt/venv/lib/python3.x/site-packages/prisma, so this one COPY brings both.
COPY --from=prisma /opt/venv /opt/venv
COPY --from=prisma /opt/prisma-engine /opt/prisma-engine

WORKDIR /app

# The source, as a directory. Ownership is root and the mode is read-only for the app user on
# purpose: nothing the application does at run time should be able to rewrite its own code.
COPY backend/app ./app
COPY backend/prisma ./prisma
COPY backend/scripts ./scripts
COPY backend/pyproject.toml ./pyproject.toml
COPY docker/backend/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

# Somewhere writable for tempfiles. app/main.py puts the media-queue election lock in the system
# temp directory, and services that stream media write there too; /tmp is world-writable already,
# but this makes the expectation explicit and survives a future `--read-only` container.
RUN mkdir -p /tmp/fieldrepo && chown fieldrepo:fieldrepo /tmp/fieldrepo

USER fieldrepo

EXPOSE 8000

# NOTE ON THE PATH: it is /health, not /api/health. The health and readiness routes are registered
# directly on the FastAPI app (app/main.py), *outside* the APIRouter that carries the /api prefix,
# and production nginx proxies / straight through — so /api/health has never existed and returns
# 404. Probing it would mark a perfectly healthy container unhealthy forever.
#
# /health is also the correct choice over /health/ready here, for the same reason CloudFront uses
# it: /health answers "is this process serving?" without touching the database, while /health/ready
# 503s whenever the DB is unreachable. A container is not broken because Postgres is still starting,
# and marking it unhealthy would take the API down instead of letting the built-in watchdog
# reconnect. Watch /health/ready from an uptime monitor, not from Docker.
#
# urllib rather than curl: curl is 4 MB of runtime image that exists solely to answer this probe.
# start-period is generous because the first request pays for the Prisma engine spawning plus a
# cross-region connect.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
    CMD ["python", "-c", "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4).status == 200 else 1)"]

# The entrypoint's whole job is to refuse to start against a remote database. See the script.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# ONE worker, always. --workers 2 puts a multiprocess supervisor in front of uvicorn that
# health-pings each worker and SIGKILLs any that fails to answer; on a CPU-starved box a slow
# request starved that ping, the supervisor killed the worker mid-flight, and the Prisma query
# engine it had spawned was orphaned rather than reaped. Enough orphans exhausted the connection
# pooler and every database call started returning 500 while /health stayed green. Scale with
# replicas, never with --workers. (app/worker.py carries the long version of this story.)
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips", "*"]
