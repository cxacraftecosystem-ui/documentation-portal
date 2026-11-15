#!/usr/bin/env bash
set -euo pipefail
seed_out="${1:?seed output path}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
logs="${RUNNER_TEMP:-/tmp}"
bucket="${AWS_S3_BUCKET:?}"
minio_port="${INTERVIEW_MINIO_PORT:-9000}"

cd "$root/backend"
python -m pip install --upgrade pip > /dev/null
pip install -r requirements.lock > /dev/null
python -m prisma py fetch
python -m prisma generate --schema prisma/schema.prisma
python -m app.prisma_cli migrate deploy --schema prisma/schema.prisma

if ! docker ps --format '{{.Names}}' | grep -qx interview-minio; then
  docker run -d --name interview-minio -p "${minio_port}:9000" \
    -e MINIO_ROOT_USER="${AWS_ACCESS_KEY_ID:?}" -e MINIO_ROOT_PASSWORD="${AWS_SECRET_ACCESS_KEY:?}" \
    docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z server /data > /dev/null
fi
for _ in $(seq 1 30); do
  if docker exec interview-minio mcli alias set local http://127.0.0.1:9000 "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" > /dev/null 2>&1; then break; fi
  sleep 2
done
docker exec interview-minio mcli mb --ignore-existing "local/${bucket}"

python ../scripts/ci/seed_interview_stack.py "$seed_out"

AI_FEATURES_ENABLED="${AI_FEATURES_ENABLED:-true}" AI_TRANSCRIPT_INSIGHTS_ENABLED="${AI_TRANSCRIPT_INSIGHTS_ENABLED:-true}" nohup python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1 > "$logs/interview-api.log" 2>&1 &
for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8000/health > /dev/null 2>&1; then
    echo "The interview stack is up."
    exit 0
  fi
  sleep 2
done
echo "::error::the API did not answer /health within three minutes"
tail -n 200 "$logs/interview-api.log"
exit 1
