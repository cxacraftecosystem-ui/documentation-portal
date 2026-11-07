#!/usr/bin/env bash
set -euo pipefail

host="${HOST:-}"
keys="${HOST_KEYS:-}"
alias_name="${HOST_ALIAS:-deploy-target}"
ssh_dir="${SSH_DIR:-$HOME/.ssh}"

if [ -z "$host" ]; then
  echo "::error::No host to pin: the EC2_HOST secret is empty."
  exit 1
fi
if [ -z "${keys//[[:space:]]/}" ]; then
  echo "::error::EC2_SSH_HOST_KEYS is not set, so the server cannot be authenticated and nothing is sent to it. Add the box's public host keys as that secret (docs/RELEASE.md, 'Pinned SSH host keys')."
  exit 1
fi

mkdir -p "$ssh_dir"
chmod 700 "$ssh_dir"
known="$ssh_dir/known_hosts_pinned"
: > "$known"

pinned=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  read -r -a fields <<< "$line"
  for i in "${!fields[@]}"; do
    case "${fields[$i]}" in
      ssh-ed25519|ecdsa-sha2-nistp256|ecdsa-sha2-nistp384|ecdsa-sha2-nistp521|ssh-rsa)
        blob="${fields[$((i + 1))]:-}"
        if [[ "$blob" =~ ^[A-Za-z0-9+/]+=*$ ]]; then
          printf '%s %s %s\n' "$alias_name" "${fields[$i]}" "$blob" >> "$known"
          pinned=$((pinned + 1))
        fi
        break
        ;;
    esac
  done
done <<< "$keys"

if [ "$pinned" -eq 0 ]; then
  echo "::error::EC2_SSH_HOST_KEYS holds no readable public host key. Each line must be '<type> <base64>' as in /etc/ssh/ssh_host_ed25519_key.pub."
  exit 1
fi
chmod 600 "$known"

config="$ssh_dir/config"
tmp="$(mktemp)"
{
  printf 'Host %s\n' "$host"
  printf '  HostKeyAlias %s\n' "$alias_name"
  printf '  UserKnownHostsFile %s\n' "$known"
  printf '  StrictHostKeyChecking yes\n'
  printf '  CheckHostIP no\n'
  printf '  UpdateHostKeys no\n'
  printf '\n'
  if [ -f "$config" ]; then cat "$config"; fi
} > "$tmp"
mv "$tmp" "$config"
chmod 600 "$config"

echo "pinned ${pinned} host key(s) for the deploy target; StrictHostKeyChecking yes"
