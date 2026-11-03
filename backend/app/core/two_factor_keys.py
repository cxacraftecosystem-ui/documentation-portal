from __future__ import annotations

import base64
import re
import secrets
from datetime import UTC, datetime

KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
KEY_BYTES = 32


class KeyFormatError(ValueError):
    pass


def _decode(material: str) -> bytes:
    cleaned = material.strip()
    try:
        return base64.urlsafe_b64decode(cleaned + "=" * (-len(cleaned) % 4))
    except (ValueError, TypeError) as exc:
        raise KeyFormatError("the key material is not base64url") from exc


def parse_key(value: str) -> tuple[str, bytes]:
    key_id, sep, material = (value or "").strip().partition(":")
    if not sep or not KEY_ID_PATTERN.fullmatch(key_id):
        raise KeyFormatError("expected <key id>:<base64url key>, with a key id of letters, digits, - or _")
    raw = _decode(material)
    if len(raw) != KEY_BYTES:
        raise KeyFormatError(f"the key must be {KEY_BYTES} bytes, base64url-encoded")
    return key_id, raw


def parse_keyring(current: str | None, previous: str | None) -> tuple[tuple[str, bytes] | None, dict[str, bytes]]:
    ring: dict[str, bytes] = {}
    active = parse_key(current) if current and current.strip() else None
    entries = [active] if active else []
    for item in (previous or "").split(","):
        if item.strip():
            entries.append(parse_key(item))
    for key_id, raw in entries:
        if key_id in ring and ring[key_id] != raw:
            raise KeyFormatError(f"key id {key_id!r} is configured twice with different keys")
        ring[key_id] = raw
    return active, ring


def generate_key(key_id: str | None = None) -> str:
    kid = key_id or datetime.now(UTC).strftime("k%Y%m%d")
    if not KEY_ID_PATTERN.fullmatch(kid):
        raise KeyFormatError("a key id is 1-32 letters, digits, - or _")
    return f"{kid}:{base64.urlsafe_b64encode(secrets.token_bytes(KEY_BYTES)).decode('ascii').rstrip('=')}"
