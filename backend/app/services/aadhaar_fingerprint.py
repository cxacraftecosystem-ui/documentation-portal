"""The artisan register keeps a keyed fingerprint of an Aadhaar number and its last four digits.

WHAT IS STORED (owner decision #2, 2026-10-17; docs/DATA_MODEL.md, docs/SECURITY.md)
---------------------------------------------------------------------------------
``Artisan.aadhaarFingerprint`` = ``HMAC-SHA256(AADHAAR_FINGERPRINT_KEY, the 12 digits)`` as lower-case
hex, unique where present, and ``Artisan.aadhaarLast4``. Nothing else of the number is kept. The full
number arrives once, over TLS, in the create/edit body (or a spreadsheet row, or the duplicate
lookup), is validated, fingerprinted here, and dropped. It is never written to the database, a log,
an export or a response.

The fingerprint is an HMAC and not a plain hash because there are only 10^12 Aadhaar numbers: a
plain SHA-256 of each could be tabulated in hours. Without the key the fingerprint is useless.

ONE KEY FOR BOTH PORTALS. The designer portal and the Field Repository use the same key, so the
cross-portal artisan link (C27, ``services/cross_portal.py``) can compare a stored fingerprint with
the other portal's directly. The formula is byte-for-byte ``cross_portal.aadhaar_code``: the key's
text stripped and UTF-8 encoded, the 12 ASCII digits as the message, hex digest.

THE LEGACY COLUMN. ``Artisan.aadhaarNumber`` held the full number until this change. It is never
written again (``seal`` sets it to NULL whenever the number is touched), is read only to mask and to
match rows the backfill (``scripts/backfill_aadhaar_fingerprint.py``) has not reached yet, and is
dropped by the guarded migration ``prisma/migrations/20261029090000_drop_aadhaar_number``. When that ships, set
:data:`LEGACY_COLUMN_PRESENT` to False in the same commit. backend/DEPLOY_AWS.md has the run order.

This module is carried byte for byte by both portals; change both. The one line that may differ is
:data:`LEGACY_COLUMN_PRESENT`, which follows each portal's own drop migration (False here since
2026-10-19).
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
from typing import Any

#: The stored columns.
FINGERPRINT_FIELD = "aadhaarFingerprint"
LAST4_FIELD = "aadhaarLast4"
#: The request/response field. Clients still send the typed number under this name and are served
#: the mask ("XXXX XXXX 9012") under it; it is no longer a column anyone writes.
NUMBER_FIELD = "aadhaarNumber"

#: True while ``Artisan.aadhaarNumber`` still exists in the schema. Flip to False in the commit that
#: moves the drop migration into ``prisma/migrations`` and removes the field from schema.prisma.
LEGACY_COLUMN_PRESENT = False

#: Shortest key accepted. 32 random bytes in base64 is 44 characters.
MIN_KEY_LENGTH = 32

_TWELVE_DIGITS = re.compile(r"[0-9]{12}")
_FINGERPRINT = re.compile(r"[0-9a-f]{64}")


class FingerprintKeyMissing(RuntimeError):
    """AADHAAR_FINGERPRINT_KEY is unset or too short, so no Aadhaar number can be stored or matched."""


def configured_key(settings: Any = None) -> str:
    """The fingerprint key, or :class:`FingerprintKeyMissing`. Never logged, never returned."""
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
    """``HMAC-SHA256(key, number)`` as lower-case hex. ``number`` must be the normalised 12 digits.

    Refuses anything else rather than fingerprinting it: "1234 5678 9012" and "123456789012" must
    never become two fingerprints of one person, so normalisation is the caller's job and this
    function checks that it was done.
    """
    if not isinstance(number, str) or not _TWELVE_DIGITS.fullmatch(number):
        raise ValueError("fingerprint() takes the normalised 12-digit Aadhaar number")
    return hmac.new(key.strip().encode("utf-8"), number.encode("ascii"), hashlib.sha256).hexdigest()


def fingerprint_of(number: str, settings: Any = None) -> str:
    """:func:`fingerprint` under the configured key."""
    return fingerprint(number, configured_key(settings))


def is_fingerprint(value: Any) -> bool:
    return isinstance(value, str) and bool(_FINGERPRINT.fullmatch(value))


def last4(number: str | None) -> str | None:
    return number[-4:] if number and len(number) >= 4 else None


def mask_last4(digits: str | None) -> str | None:
    """``"9012"`` -> ``"XXXX XXXX 9012"``, the same mask ``artisan_identity.mask_aadhaar`` makes."""
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
    """The last four digits a row holds: the column, or (before the backfill) the legacy number's."""
    digits = _get(row, LAST4_FIELD)
    if digits:
        return digits
    legacy = _get(row, NUMBER_FIELD) if LEGACY_COLUMN_PRESENT else None
    if isinstance(legacy, str) and _TWELVE_DIGITS.fullmatch(legacy):
        return legacy[-4:]
    return None


def stored_mask(row: Any) -> str | None:
    """What every surface shows for a row's Aadhaar: ``XXXX XXXX 9012``, or None when it has none."""
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
    """Turn a write payload's ``aadhaarNumber`` into the stored columns. Returns the number, or None.

    Mutates ``data``. Absent key: nothing changes. ``None``/blank: the number is retracted (both
    columns NULL). A normalised 12-digit number: fingerprint and last four. The legacy column is set
    to NULL whenever the key is present, so touching the number also clears any full copy a row
    still held. The number is returned so the caller can run its duplicate check, and must not be
    kept beyond that.

    ``previous`` is the stored row on an edit: re-sending the number it already holds changes
    nothing, so all three keys are dropped and the edit is not a change to the Aadhaar at all.

    A masked value ("XXXX XXXX 9012") must already have been dropped
    (``schemas.records.drop_masked_identity_numbers``).
    """
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
        # A legacy row re-sent its own number: fill the new columns, and leave the old one for the
        # drop migration rather than make this edit look like a retraction.
        data.pop(NUMBER_FIELD, None)
    data[FINGERPRINT_FIELD] = fingerprint_of(number, settings)
    data[LAST4_FIELD] = number[-4:]
    return number


def match_where(
    number: str, settings: Any = None, *, legacy: bool = LEGACY_COLUMN_PRESENT
) -> dict[str, Any]:
    """The Prisma ``where`` that finds the artisan holding ``number`` (normalised 12 digits).

    Matches the fingerprint, and — with ``legacy`` — the old column too, so a row the backfill has
    not reached is still a duplicate of the same person typed today. Pass
    ``legacy=await legacy_pending(db)``: once no such row is left the number stops travelling to the
    database at all (a query is the one place it would otherwise appear, in a DEBUG engine trace).
    """
    clause: dict[str, Any] = {FINGERPRINT_FIELD: fingerprint_of(number, settings)}
    if legacy and LEGACY_COLUMN_PRESENT:
        return {"OR": [clause, {NUMBER_FIELD: number}]}
    return clause


_LEGACY_RECHECK_SECONDS = 60.0
_legacy_state: dict[str, Any] = {"pending": True, "checked": 0.0}


async def legacy_pending(db: Any) -> bool:
    """Does any artisan still hold a number without its fingerprint? Asked at most once a minute.

    STICKY ONCE FALSE: nothing writes the legacy column any more, so a table the backfill has
    finished stays finished, and the answer is never asked again in this process.
    """
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
    """Forget the cached answer (tests, and a process that has just inserted a legacy row)."""
    _legacy_state.update(pending=True, checked=0.0)


def holds(row: Any, number: str, settings: Any = None) -> bool:
    """Does ``row`` hold ``number``? Constant-time on the fingerprint; legacy rows by value."""
    stored = _get(row, FINGERPRINT_FIELD)
    if stored:
        return hmac.compare_digest(str(stored), fingerprint_of(number, settings))
    if LEGACY_COLUMN_PRESENT:
        legacy = _get(row, NUMBER_FIELD)
        return bool(legacy) and hmac.compare_digest(str(legacy), number)
    return False


def public_view(node: dict[str, Any]) -> dict[str, Any]:
    """An encoded Artisan as it leaves the API: the mask under ``aadhaarNumber``, no fingerprint.

    Applied to every dict that carries one of the stored columns, whoever the viewer is. Mutates and
    returns ``node``.
    """
    node[NUMBER_FIELD] = stored_mask(node)
    node.pop(FINGERPRINT_FIELD, None)
    return node


# ── The edit history ────────────────────────────────────────────────────────────────────────────
#
# A ``RecordRevision`` entry for the Aadhaar records that it changed, never a value: the same four
# wordings the designer portal's ``access._redacted_change`` writes for every redacted column.

_HAD, _CLEARED, _REPLACED, _EMPTY, _STILL_EMPTY = (
    "(value recorded)",
    "(cleared)",
    "(value replaced)",
    "(empty)",
    "(still empty)",
)

#: Every ``(old, new)`` pair :func:`ledger_entry` can produce. Closed, so a reader can tell one of
#: these from a stored value without believing a ``redacted`` flag.
LEDGER_PAIRS = frozenset(
    {(_EMPTY, _STILL_EMPTY), (_HAD, _CLEARED), (_HAD, _REPLACED), (_EMPTY, _HAD)}
)


def ledger_entry(had: bool, has: bool) -> dict[str, Any]:
    """The valueless history entry for an Aadhaar that went from ``had`` to ``has``."""
    if not had and not has:
        return {"old": _EMPTY, "new": _STILL_EMPTY, "redacted": True}
    if not has:
        return {"old": _HAD, "new": _CLEARED, "redacted": True}
    if had:
        return {"old": _HAD, "new": _REPLACED, "redacted": True}
    return {"old": _EMPTY, "new": _HAD, "redacted": True}


def ledger_view(entry: Any) -> dict[str, Any] | None:
    """A stored history entry as it may be shown: unchanged if already valueless, else re-derived.

    Returns None for something that is not an old/new entry at all (a provenance stamp)."""
    if not isinstance(entry, dict):
        return ledger_entry(bool(entry), bool(entry))
    if (entry.get("old"), entry.get("new")) in LEDGER_PAIRS:
        return {"old": entry["old"], "new": entry["new"], "redacted": True}
    if "old" not in entry and "new" not in entry:
        return None
    return ledger_entry(bool(entry.get("old")), bool(entry.get("new")))


# ── Access logs ─────────────────────────────────────────────────────────────────────────────────
#
# Clients send the number in a body. App builds already in the field still call
# ``GET /artisans/lookup/aadhaar?number=…``, and uvicorn writes the query string to its access log.
# This filter blanks any Aadhaar-shaped value in a logged query string, under any parameter name.

_AADHAAR_SHAPED = re.compile(
    r"(?<![0-9])[2-9][0-9]{3}(?:[ +\-]|%20|%2D)?[0-9]{4}(?:[ +\-]|%20|%2D)?[0-9]{4}(?![0-9])",
    re.IGNORECASE,
)


def redact_path(path: str) -> str:
    """``path`` with every Aadhaar-shaped value in its query string replaced by ``[redacted]``."""
    head, separator, query = path.partition("?")
    if not separator:
        return path
    return f"{head}?{_AADHAAR_SHAPED.sub('[redacted]', query)}"


class AccessLogRedaction(logging.Filter):
    """On ``uvicorn.access``: its line is ``'%s - "%s %s HTTP/%s" %d'`` with the path third of five.
    Never raises and never drops a line; anything of another shape passes untouched."""

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
