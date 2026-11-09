#!/usr/bin/env bash
set -euo pipefail

INSTANCE="${1:-}"
MODE="${2:---dry-run}"
REGION="${AWS_REGION:-ap-south-1}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOGDIR="${LOGDIR:-.}"

case "$INSTANCE" in i-*) ;; *) echo "usage: run_box_clear.sh <instance-id> [--dry-run | --apply]" >&2; exit 64 ;; esac
case "$MODE" in --dry-run|--apply) ;; *) echo "usage: run_box_clear.sh <instance-id> [--dry-run | --apply]" >&2; exit 64 ;; esac
export MSYS_NO_PATHCONV=1

b64="$(base64 -w0 "$HERE/clear_box_versions.sh")"
params="$(mktemp)"
trap 'rm -f "$params"' EXIT
printf '{"commands":["echo %s | base64 -d > /tmp/ops-clear.sh && sudo -u ubuntu -H bash /tmp/ops-clear.sh %s; rc=$?; rm -f /tmp/ops-clear.sh; exit $rc"]}' "$b64" "$MODE" > "$params"

ref="$params"
if command -v cygpath >/dev/null 2>&1; then ref="$(cygpath -m "$params")"; fi
id="$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
  --comment "clear earlier versions $MODE" --parameters "file://$ref" --query Command.CommandId --output text)"
echo "SSM command $id on $INSTANCE ($MODE)"
for _ in $(seq 1 90); do
  status="$(aws ssm get-command-invocation --region "$REGION" --command-id "$id" --instance-id "$INSTANCE" --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in Pending|InProgress|Delayed) sleep 10 ;; *) break ;; esac
done
out="$LOGDIR/clear-box-$INSTANCE-$(date -u +%Y%m%dT%H%M%SZ)${MODE#-}.txt"
aws ssm get-command-invocation --region "$REGION" --command-id "$id" --instance-id "$INSTANCE" \
  --query '[Status,StandardOutputContent,StandardErrorContent]' --output text | tee "$out"
echo "saved to $out"
