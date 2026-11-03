from __future__ import annotations

import hashlib
import hmac
import logging
import re
from typing import Any

FINGERPRINT_FIELD = "aadhaarFingerprint"
LAST4_FIELD = "aadhaarLast4"
NUMBER_FIELD = "aadhaarNumber"

LEGACY_COLUMN_PRESENT = False

MIN_KEY_LENGTH = 32

_TWELVE_DIGITS = re.compile(r"[0-9]{12}")
_FINGERPRINT = re.compile(r"[0-9a-f]{64}")


class FingerprintKeyMissing(RuntimeError):
    pass


def configured_key(settings: Any = None) -> str:
    if settings is None:
        from app.core.config import get_settings

        settings = get_settings()
    key = (getattr(settings, "aadhaar_fingerprint_key", None) or "").strip()
    if len(key) < MIN_KEY_LENGTH:
        raise FingerprintKeyMissing(
            "AADHAAR_FINGERPRINT_KEY is not configured (32 random bytes, base64); "
            "Aadhaar numbers cannot be stored or matched without it."
        )
    return key


def fingerprint(number: str, key: str) -> str:
    if not isinstance(number, str) or not _TWELVE_DIGITS.fullmatch(number):
        raise ValueError("fingerprint() takes the normalised 12-digit Aadhaar number")
    return hmac.new(key.strip().encode("utf-8"), number.encode("ascii"), hashlib.sha256).hexdigest()


def fingerprint_of(number: str, settings: Any = None) -> str:
    return fingerprint(number, configured_key(settings))


def is_fingerprint(value: Any) -> bool:
    return isinstance(value, str) and bool(_FINGERPRINT.fullmatch(value))


def last4(number: str | None) -> str | None:
    return number[-4:] if number and len(number) >= 4 else None


def mask_last4(digits: str | None) -> str | None:
    if not digits:
        return None
    if len(digits) != 4 or not digits.isascii() or not digits.isdigit():
        return "XXXX XXXX XXXX"
    return f"XXXX XXXX {digits}"


def _get(row: Any, key: str) -> Any:
    if isinstance(row, dict):
        return row.get(key)
    return getattr(row, key, None)


def stored_last4(row: Any) -> str | None:
    digits = _get(row, LAST4_FIELD)
    if digits:
        return digits
    legacy = _get(row, NUMBER_FIELD) if LEGACY_COLUMN_PRESENT else None
    if isinstance(legacy, str) and _TWELVE_DIGITS.fullmatch(legacy):
        return legacy[-4:]
    return None


def stored_mask(row: Any) -> str | None:
    digits = _get(row, LAST4_FIELD)
    if digits:
        return mask_last4(digits)
    legacy = _get(row, NUMBER_FIELD) if LEGACY_COLUMN_PRESENT else None
    if legacy:
        from app.services.artisan_identity import mask_aadhaar

        return mask_aadhaar(str(legacy))
    return None


def has_aadhaar(row: Any) -> bool:
    return bool(_get(row, FINGERPRINT_FIELD) or _get(row, LAST4_FIELD) or (
        LEGACY_COLUMN_PRESENT and _get(row, NUMBER_FIELD)
    ))


def seal(data: dict[str, Any], settings: Any = None, *, previous: Any = None) -> str | None:
    if NUMBER_FIELD not in data:
        return None
    value = data.pop(NUMBER_FIELD)
    if LEGACY_COLUMN_PRESENT:
        data[NUMBER_FIELD] = None
    if value is None or not str(value).strip():
        data[FINGERPRINT_FIELD] = None
        data[LAST4_FIELD] = None
        return None
    number = str(value)
    if previous is not None and holds(previous, number, settings):
        if _get(previous, FINGERPRINT_FIELD):
            for key in (NUMBER_FIELD, FINGERPRINT_FIELD, LAST4_FIELD):
                data.pop(key, None)
            return number
        data.pop(NUMBER_FIELD, None)
    data[FINGERPRINT_FIELD] = fingerprint_of(number, settings)
    data[LAST4_FIELD] = number[-4:]
    return number


def match_where(
    number: str, settings: Any = None, *, legacy: bool = LEGACY_COLUMN_PRESENT
) -> dict[str, Any]:
    clause: dict[str, Any] = {FINGERPRINT_FIELD: fingerprint_of(number, settings)}
    if legacy and LEGACY_COLUMN_PRESENT:
        return {"OR": [clause, {NUMBER_FIELD: number}]}
    return clause


_LEGACY_RECHECK_SECONDS = 60.0
_legacy_state: dict[str, Any] = {"pending": True, "checked": 0.0}


async def legacy_pending(db: Any) -> bool:
    import time

    if not LEGACY_COLUMN_PRESENT or not _legacy_state["pending"]:
        return False
    now = time.monotonic()
    if now - _legacy_state["checked"] < _LEGACY_RECHECK_SECONDS and _legacy_state["checked"]:
        return True
    try:
        remaining = await db.artisan.count(
            where={NUMBER_FIELD: {"not": None}, FINGERPRINT_FIELD: None}
        )
    except Exception:  # noqa: BLE001 - unanswerable: keep matching the old column, which finds duplicates
        return True
    _legacy_state.update(pending=remaining > 0, checked=now)
    return remaining > 0


def reset_legacy_pending() -> None:
    _legacy_state.update(pending=True, checked=0.0)


def holds(row: Any, number: str, settings: Any = None) -> bool:
    stored = _get(row, FINGERPRINT_FIELD)
    if stored:
        return hmac.compare_digest(str(stored), fingerprint_of(number, settings))
    if LEGACY_COLUMN_PRESENT:
        legacy = _get(row, NUMBER_FIELD)
        return bool(legacy) and hmac.compare_digest(str(legacy), number)
    return False


def public_view(node: dict[str, Any]) -> dict[str, Any]:
    node[NUMBER_FIELD] = stored_mask(node)
    node.pop(FINGERPRINT_FIELD, None)
    return node


_HAD, _CLEARED, _REPLACED, _EMPTY, _STILL_EMPTY = (
    "(value recorded)",
    "(cleared)",
    "(value replaced)",
    "(empty)",
    "(still empty)",
)

LEDGER_PAIRS = frozenset(
    {(_EMPTY, _STILL_EMPTY), (_HAD, _CLEARED), (_HAD, _REPLACED), (_EMPTY, _HAD)}
)


def ledger_entry(had: bool, has: bool) -> dict[str, Any]:
    if not had and not has:
        return {"old": _EMPTY, "new": _STILL_EMPTY, "redacted": True}
    if not has:
        return {"old": _HAD, "new": _CLEARED, "redacted": True}
    if had:
        return {"old": _HAD, "new": _REPLACED, "redacted": True}
    return {"old": _EMPTY, "new": _HAD, "redacted": True}


def ledger_view(entry: Any) -> dict[str, Any] | None:
    if not isinstance(entry, dict):
        return ledger_entry(bool(entry), bool(entry))
    if (entry.get("old"), entry.get("new")) in LEDGER_PAIRS:
        return {"old": entry["old"], "new": entry["new"], "redacted": True}
    if "old" not in entry and "new" not in entry:
        return None
    return ledger_entry(bool(entry.get("old")), bool(entry.get("new")))


_AADHAAR_SHAPED = re.compile(
    r"(?<![0-9])[2-9][0-9]{3}(?:[ +\-]|%20|%2D)?[0-9]{4}(?:[ +\-]|%20|%2D)?[0-9]{4}(?![0-9])",
    re.IGNORECASE,
)


def redact_path(path: str) -> str:
    head, separator, query = path.partition("?")
    if not separator:
        return path
    return f"{head}?{_AADHAAR_SHAPED.sub('[redacted]', query)}"


class AccessLogRedaction(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        try:
            args = record.args
            if isinstance(args, tuple) and len(args) == 5 and isinstance(args[2], str):
                record.args = (args[0], args[1], redact_path(args[2]), args[3], args[4])
        except Exception:  # noqa: BLE001 - a filter must never fail the request it logs
            pass
        return True


def install_access_log_redaction() -> None:
    access = logging.getLogger("uvicorn.access")
    if not any(isinstance(existing, AccessLogRedaction) for existing in access.filters):
        access.addFilter(AccessLogRedaction())
