#!/usr/bin/env bash
# First-boot provisioning for the Field Repository API box (Ubuntu 24.04).
# Installs system deps (including ffmpeg for Whisper audio chunking and nginx as
# the reverse proxy so port 8000 is never exposed directly), prepares a swap file
# so installs don't OOM on 1 GiB, and lays down the nginx site + systemd unit.
# The actual code is deployed by the GitHub Actions workflow (deploy-backend.yml).
set -euxo pipefail

# --- swap (protects the 1 GiB box during pip/prisma installs) ----------------
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3.12-venv python3-pip git ffmpeg nginx

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
mkdir -p /home/ubuntu/app
chown -R ubuntu:ubuntu /home/ubuntu/app
