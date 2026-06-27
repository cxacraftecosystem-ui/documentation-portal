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
cat > /etc/systemd/system/fieldrepo.service <<'UNIT'
[Unit]
Description=Field Repository API
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/backend
EnvironmentFile=/home/ubuntu/app/backend/.env
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 2
Restart=always
# 10s (not 3s) between restarts so that IF the process ever does exit while the Supabase
# transaction pooler is at its client-connection ceiling, restarts don't hammer the pooler
# faster than its connections can drain. (The app also now keeps serving and reconnects to
# the DB in the background instead of exiting, so this is defense-in-depth.)
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
# Service is enabled here; it starts cleanly once the deploy workflow has placed
# the code and the .env file under /home/ubuntu/app/backend.
systemctl enable fieldrepo || true
mkdir -p /home/ubuntu/app
chown -R ubuntu:ubuntu /home/ubuntu/app
