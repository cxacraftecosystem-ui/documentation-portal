#!/usr/bin/env bash
# First-boot provisioning for the Field Repository API box (Ubuntu 26.04 since 2026-10-09: the
# AMI filter in main.tf). Installs system deps (including ffmpeg for Whisper audio chunking and nginx
# as the reverse proxy so port 8000 is never exposed directly), prepares a swap file so installs
# don't OOM on a small box, lays down the nginx site + systemd units, and LAST installs the API's
# interpreter: upstream CPython 3.14.8 from a pinned, checksummed python-build-standalone build,
# under /opt/cpython (the end of this file says why it comes last and why it is not apt's).
# The actual code is deployed by the GitHub Actions workflow (deploy-backend.yml), which also builds
# the API's venv from backend/requirements.lock under /home/ubuntu/app/venvs and points
# /home/ubuntu/app/backend/.venv at it; nothing here installs a Python package.
#
# It runs as root under cloud-init, once. To launch a box with it: backend/DEPLOY_AWS.md §10 has the
# exact `aws ec2 run-instances` call. The file must reach EC2 with LF line endings (.gitattributes
# keeps every *.sh LF in a checkout, Windows included); a CRLF copy fails on its first line.
set -euxo pipefail

# --- swap (protects a 1-2 GiB box during pip/prisma installs) ---------------
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
# libatomic1: the official Node 26 binary the deploy pins for the Prisma CLI links against it. The 26.04
# cloud image ships it and a minimal image does not, so it is named here (a no-op where it is present)
# and deploy-backend.yml's build step checks for it too. curl and ca-certificates fetch the
# interpreter at the end of this file; the cloud image has both already.
# No python3.14, python3.14-venv or PPA: the API does not run on apt's Python (see the end).
apt-get install -y git ffmpeg nginx libatomic1 curl ca-certificates

# --- nginx reverse proxy: 80 -> 127.0.0.1:8000 -------------------------------
cat > /etc/nginx/sites-available/fieldrepo <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    client_max_body_size 200M;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
NGINX
ln -sf /etc/nginx/sites-available/fieldrepo /etc/nginx/sites-enabled/fieldrepo
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable nginx
systemctl restart nginx

# --- systemd unit for the API (uvicorn) --------------------------------------
# `.venv` below is a symlink the deploy maintains, to /home/ubuntu/app/venvs/cpython-<version>+<build>-
# <hash of the lock>; the unit never needs to change when the venv does (deploy-backend.yml says why
# it is a symlink and not a directory).
# IMPORTANT: a SINGLE uvicorn process (NOT --workers 2). With >1 worker uvicorn runs a
# multiprocess supervisor that health-pings each worker over a pipe (answered by a daemon thread)
# and SIGKILLs any worker that fails to pong within timeout_worker_healthcheck. On this small,
# CPU-credit-throttled box a heavy transcription chunk (run via asyncio.to_thread) starved that
# pong thread, so the supervisor SIGKILLed the worker mid-job. SIGKILL skips the shutdown hook, so
# the worker's Prisma query-engine subprocess was orphaned (reparented to init) — one orphan per
# kill cycle — until the orphans exhausted the Supabase pooler and EVERY DB call (login included)
# returned HTTP 500 while /health (no DB) stayed 200. One process = no supervisor = no SIGKILL loop.
# The media queue runs in its OWN service (fieldrepo-queue, below), so its heavy AI/ffmpeg work is
# never in the request-serving process — that both removes the SIGKILL trigger and keeps responses
# fast (no CloudFront 504). MEDIA_QUEUE_WORKER_ENABLED=false disables the in-process queue here.
cat > /etc/systemd/system/fieldrepo.service <<'UNIT'
[Unit]
Description=Field Repository API
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/backend
EnvironmentFile=/home/ubuntu/app/backend/.env
# Applied AFTER EnvironmentFile so it always wins: the web process must never run the queue.
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
Restart=always
# 10s (not 3s) between restarts so that IF the process ever does exit while the Supabase
# transaction pooler is at its client-connection ceiling, restarts don't hammer the pooler
# faster than its connections can drain. (The app also now keeps serving and reconnects to
# the DB in the background instead of exiting, so this is defense-in-depth.)
RestartSec=10
# Reap the whole control group on stop/restart so a Prisma query-engine is never left orphaned.
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
UNIT

# --- systemd unit for the media-processing queue worker ----------------------
# Runs the transcription/measurement queue in its OWN process (see app/worker.py). Separate from
# uvicorn on purpose: no multiprocess supervisor can SIGKILL it mid-job, and its heavy work never
# competes with request serving. KillMode=control-group reaps its query-engine on restart.
cat > /etc/systemd/system/fieldrepo-queue.service <<'UNIT'
[Unit]
Description=Field Repository media queue worker
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/backend
EnvironmentFile=/home/ubuntu/app/backend/.env
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m app.worker
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
# Services are enabled here; they start cleanly once the deploy workflow has placed
# the code and the .env file under /home/ubuntu/app/backend.
systemctl enable fieldrepo || true
systemctl enable fieldrepo-queue || true
mkdir -p /home/ubuntu/app/venvs
chown -R ubuntu:ubuntu /home/ubuntu/app

# --- The API's interpreter: upstream CPython 3.14.8, pinned and checksummed -------------------
# The exact release CI tests (checks.yml), from python-build-standalone rather than apt: Ubuntu 26.04's
# own python3.14 is 3.14.4 with backported fixes, a different interpreter from the one the tests ran
# on. The four pins below are the same four as deploy-backend.yml's build step, which says how they
# were chosen and why the box is not on 3.15 yet; backend/tests/test_interpreter_pin.py fails when the
# two files (or checks.yml's version) disagree. The deploy installs it too, the same way, if it is
# missing, so a box whose first boot could not fetch it is mended by its first deploy.
#
# LAST IN THIS FILE ON PURPOSE: a download that fails, or a file whose SHA-256 is not the pinned one,
# stops this script (cloud-init then reports an error) only after the swap, nginx and both units are
# already in place. A wrong hash is REFUSED, never unpacked. What passes is unpacked as root into a
# staging directory beside its final place, its standard library byte-compiled there as root (the
# tarball ships no bytecode, and nobody but root can write it later; the deploy's build step says
# what that costs), proved as the user the services run as (the version, and ssl, sqlite3, lzma,
# ctypes, pyexpat, ensurepip, venv), and only then renamed to /opt/cpython/<version>+<build>,
# root-owned and writable by nobody else, with INSTALLED_FROM naming the URL and hash. The system
# python3 stays as the cloud image shipped it: cloud-init and apt's own tooling run on it.
cpython=3.14.8
pbs_release=20261009
pbs_url=https://github.com/astral-sh/python-build-standalone/releases/download/20261009/cpython-3.14.8%2B20261009-x86_64-unknown-linux-gnu-install_only.tar.gz
pbs_sha256=83f9cb480b702548c592443f86209cf0fc03448d90692df257f095c01791dccc
prefix="/opt/cpython/${cpython}+${pbs_release}"
provenance="$(printf 'url=%s\nsha256=%s' "$pbs_url" "$pbs_sha256")"
if [ "$(cat "$prefix/INSTALLED_FROM" 2>/dev/null)" != "$provenance" ]; then
  if [ -e "$prefix" ]; then
    echo "$prefix exists but is not the pinned build; leaving it alone" >&2
    exit 1
  fi
  install -d -m 0755 -o root -g root /opt/cpython
  stage="$(mktemp -d /opt/cpython/.stage-XXXXXX)"
  curl -fsSL --proto '=https' --proto-redir '=https' --tlsv1.2 --retry 5 --retry-all-errors \
    --connect-timeout 20 --max-time 900 -o "$stage/cpython.tar.gz" "$pbs_url"
  got="$(sha256sum "$stage/cpython.tar.gz")"
  got="${got%% *}"
  if [ "$got" != "$pbs_sha256" ]; then
    rm -rf "$stage"
    echo "REFUSED: $pbs_url hashed to $got, and the pin says $pbs_sha256; nothing was installed" >&2
    exit 1
  fi
  tar -xzf "$stage/cpython.tar.gz" -C "$stage" --no-same-owner --no-same-permissions
  rm -f "$stage/cpython.tar.gz"
  printf '%s\n' "$provenance" > "$stage/python/INSTALLED_FROM"
  "$stage/python/bin/python${cpython%.*}" -I -m compileall -q -s "$stage/python" -p "$prefix" "$stage/python/lib/python${cpython%.*}"
  chmod -R u+rwX,go+rX,go-w "$stage/python"
  chmod 0755 "$stage"
  runuser -u ubuntu -- env PYTHONDONTWRITEBYTECODE=1 "$stage/python/bin/python${cpython%.*}" -c 'import sys, ssl, sqlite3, lzma, bz2, zlib, ctypes, pyexpat, ensurepip, venv; v = "%d.%d.%d" % sys.version_info[:3]; v == sys.argv[1] or sys.exit("expected CPython %s, the build says %s" % (sys.argv[1], v)); print("proved:", sys.version.split()[0], "|", ssl.OPENSSL_VERSION, "| SQLite", sqlite3.sqlite_version, "|", pyexpat.EXPAT_VERSION)' "$cpython"
  mv -T "$stage/python" "$prefix"
  rm -rf "$stage"
fi
"$prefix/bin/python${cpython%.*}" -VV
