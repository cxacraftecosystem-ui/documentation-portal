#!/usr/bin/env bash
set -euxo pipefail

if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y git ffmpeg nginx libatomic1 curl ca-certificates

cat > /etc/nginx/sites-available/fieldrepo <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    client_max_body_size 200M;
    include /etc/nginx/snippets/origin-verify.conf;

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
echo 'allow 127.0.0.1; deny all;' > /etc/nginx/snippets/origin-verify.conf
ln -sf /etc/nginx/sites-available/fieldrepo /etc/nginx/sites-enabled/fieldrepo
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable nginx
systemctl restart nginx

cat > /etc/systemd/system/fieldrepo-api@.service <<'UNIT'
# Written by backend/deploy/blue_green.sh. One instance per loopback port; nginx proxies to one.
# A SINGLE uvicorn process, never --workers 2: infra/terraform/user_data.sh says why.
[Unit]
Description=Field Repository API on port %i
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/slots/%i/backend
EnvironmentFile=/home/ubuntu/app/slots/%i/backend/.env
# After EnvironmentFile so it always wins: the web process must never run the queue.
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
# Two malloc arenas, not glibc's eight per core: backend/deploy/blue_green.sh says why.
Environment=MALLOC_ARENA_MAX=2
ExecStart=/home/ubuntu/app/slots/%i/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port %i --workers 1
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/fieldrepo-queue.service <<'UNIT'
[Unit]
Description=Field Repository media queue worker
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/backend
EnvironmentFile=/home/ubuntu/app/backend/.env
# Two malloc arenas, not glibc's eight per core: backend/deploy/blue_green.sh says why.
Environment=MALLOC_ARENA_MAX=2
ExecStart=/home/ubuntu/app/backend/.venv/bin/python -m app.worker
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable fieldrepo-queue || true
mkdir -p /home/ubuntu/app/venvs
chown -R ubuntu:ubuntu /home/ubuntu/app

cpython=3.15.2
pbs_release=20261026
pbs_url=https://github.com/astral-sh/python-build-standalone/releases/download/20261026/cpython-3.15.2%2B20261026-x86_64-unknown-linux-gnu-install_only.tar.gz
pbs_sha256=08d4ae9024a7cfbd3b70e559241afa2571cd3208d7bd5f96f88e0080dd4092aa
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
