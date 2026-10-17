"""The keys two-step secrets are encrypted with: ``TWO_FACTOR_ENCRYPTION_KEY`` and its predecessors.

WHY A KEY OF ITS OWN. The authenticator secrets used to be sealed with a key derived from
``JWT_SECRET``, so rotating the session-signing secret (the first thing anyone does after a leak)
silently turned every enrolled secret into ciphertext nobody could open. A dedicated key separates
the two: ``JWT_SECRET`` can be rotated at will, and this key is rotated on its own schedule.

THE FORMAT. ``<key id>:<32 random bytes, base64url>``, e.g. ``k20261017:3q2-...``. The key id is
written into every ciphertext (``v2.<key id>.<iv>.<tag>.<ciphertext>``), so a secret always names
the key that opens it, and rotation is: put the new key in ``TWO_FACTOR_ENCRYPTION_KEY``, move the
old one into ``TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS`` (comma-separated), and either let each secret be
re-encrypted the next time it is used or run ``scripts/reencrypt_two_factor_secrets.py --apply``.
Once that reports nothing left on the old key, drop it from the previous list.

UNSET IS ALLOWED, AND LOUD. Without a key the service keeps the legacy ``JWT_SECRET``-derived key,
logs a WARNING, and every ``v1`` secret keeps working; the day a key is set, ``v1`` secrets are still
read (with the legacy key) and moved to the new one on use or by the backfill.

Pure: no settings, no database. ``app/core/config.py`` calls :func:`parse_keyring` to refuse a
malformed value at start-up rather than at the first sign-in.
"""

from __future__ import annotations

import base64
import re
import secrets
from datetime import UTC, datetime

KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
KEY_BYTES = 32


class KeyFormatError(ValueError):
    """A configured key is not ``<key id>:<32 bytes base64url>``. The message never contains it."""


def _decode(material: str) -> bytes:
    cleaned = material.strip()
    try:
        return base64.urlsafe_b64decode(cleaned + "=" * (-len(cleaned) % 4))
    except (ValueError, TypeError) as exc:
        raise KeyFormatError("the key material is not base64url") from exc


def parse_key(value: str) -> tuple[str, bytes]:
    """``(key id, 32-byte key)`` from one configured entry."""
    key_id, sep, material = (value or "").strip().partition(":")
    if not sep or not KEY_ID_PATTERN.fullmatch(key_id):
        raise KeyFormatError("expected <key id>:<base64url key>, with a key id of letters, digits, - or _")
    raw = _decode(material)
    if len(raw) != KEY_BYTES:
        raise KeyFormatError(f"the key must be {KEY_BYTES} bytes, base64url-encoded")
    return key_id, raw


def parse_keyring(current: str | None, previous: str | None) -> tuple[tuple[str, bytes] | None, dict[str, bytes]]:
    """``(current key or None, {key id: key} for current AND previous)``.

    Refuses a key id used twice with different material: a ciphertext names its key by id, so two
    keys behind one id would make some secrets unreadable at random.
    """
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
    """A fresh ``<key id>:<key>`` entry; the id defaults to ``k`` + today's date (UTC)."""
    kid = key_id or datetime.now(UTC).strftime("k%Y%m%d")
    if not KEY_ID_PATTERN.fullmatch(kid):
        raise KeyFormatError("a key id is 1-32 letters, digits, - or _")
    return f"{kid}:{base64.urlsafe_b64encode(secrets.token_bytes(KEY_BYTES)).decode('ascii').rstrip('=')}"
