"""Shared helpers for questionnaire v2: the error shape, time, and reading raw rows."""

from __future__ import annotations

import json
import secrets
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any, NoReturn

IST = timezone(timedelta(hours=5, minutes=30), name="IST")
JOIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I/L: read aloud across a courtyard
JOIN_CODE_LENGTH = 6

MESSAGES = {
    "VALIDATION_FAILED": "Some fields are not valid.",
    "NOT_FOUND": "Not found.",
    "FORBIDDEN": "You can't do that here.",
    "STALE_VERSION": "Changed on another device.",
    "INSTRUMENT_LOCKED": "Locked once recording starts.",
    "PARTICIPANT_HAS_TAKES": "Their takes here would no longer count for them.",
    "HAS_RECORDINGS": "Move or remove its recordings first.",
    "TITLE_IS_GENERATED": "Titles are generated from the session's details. Send a note instead.",
    "TARGET_OUTSIDE_INSTRUMENT": "That section or question is not on this session's questionnaire.",
    "JOIN_CODE_UNKNOWN": "No open session has that code.",
    "MERGE_INSTRUMENT_MISMATCH": "Both sessions must be on the same questionnaire.",
    "SESSION_UNKNOWN": "That session does not exist.",
    "TRANSCRIPTION_PAUSED": "Transcription is paused.",
    "CONSENT_REFUSED": "Consent to send this recording outside was refused.",
    "CONSENT_NOT_RECORDED": "Waiting for consent to transcribe.",
    "APPROVE_BLOCKED": "This session can't be approved yet.",
    # Joined the frozen list with the first client that shows them (Field Repository web, U3).
    "FORM_DELETED": "This questionnaire was deleted.",
    "BUNDLE_VERSION": "This bundle was made by a newer version of the app.",
    # Joined the frozen list once every client showed them (22 Oct 2026: DR web and Android, FR web
    # and Android): the answer lock (U9, P15) and a respondent who still has answers or takes.
    "ANSWER_LOCKED": "Only the person who answered, a lead or an admin can change this answer.",
    "RESPONDENT_HAS_ROWS": "This respondent has answers or takes.",
}
STATUS = {
    "VALIDATION_FAILED": 422, "NOT_FOUND": 404, "FORBIDDEN": 403, "STALE_VERSION": 409,
    "INSTRUMENT_LOCKED": 409, "PARTICIPANT_HAS_TAKES": 409, "HAS_RECORDINGS": 409,
    "TITLE_IS_GENERATED": 422, "TARGET_OUTSIDE_INSTRUMENT": 422, "JOIN_CODE_UNKNOWN": 404,
    "MERGE_INSTRUMENT_MISMATCH": 409, "SESSION_UNKNOWN": 404, "TRANSCRIPTION_PAUSED": 409,
    "CONSENT_REFUSED": 409, "CONSENT_NOT_RECORDED": 409, "APPROVE_BLOCKED": 409,
    "FORM_DELETED": 410, "BUNDLE_VERSION": 422, "ANSWER_LOCKED": 409, "RESPONDENT_HAS_ROWS": 409,
}

#: The unification's codes (UNIFY section 2.2), served now and NOT YET in the frozen contract's closed
#: list (``shared/questionnaire-contract.json``), which every client checks itself against: each one
#: joins that list in the stream that teaches the clients to show it (U1-U4), and moves up into
#: MESSAGES/STATUS then. ``shared/questionnaire-enums.json`` lists them as ``pendingErrorCodes``.
UNIFY_MESSAGES: dict[str, str] = {}
UNIFY_STATUS: dict[str, int] = {}


def fail(code: str, message: str | None = None, **extra: Any) -> NoReturn:
    """Raise the contract's error: ``{"detail": {"code", "message", ...}}``."""
    # Imported here, not at the top: the queue worker imports this module (the nightly session close)
    # and would otherwise load FastAPI for an exception it never raises (tests/test_import_footprint.py).
    from app.questionnaire.errors import http_error

    status = STATUS.get(code) or UNIFY_STATUS[code]
    raise http_error(status, {"code": code, "message": message or MESSAGES.get(code) or UNIFY_MESSAGES[code], **extra})


def now() -> datetime:
    return datetime.now(UTC)


def today_ist() -> date:
    return datetime.now(IST).date()


def aware(value: Any) -> datetime | None:
    """A UTC-aware datetime from a Prisma value, an ISO string or None."""
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day, tzinfo=UTC)
    text = str(value).replace("Z", "+00:00")
    parsed = datetime.fromisoformat(text)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def as_date(value: Any) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value)[:10])


def naive_utc(value: datetime | None) -> str | None:
    """A ``timestamp(3)`` literal for raw SQL parameters."""
    if value is None:
        return None
    return value.astimezone(UTC).replace(tzinfo=None).isoformat(timespec="milliseconds")


def iso(value: Any) -> str | None:
    dt = aware(value)
    return dt.isoformat().replace("+00:00", "Z") if dt else None


def enum_text(value: Any) -> Any:
    return getattr(value, "value", value)


def get(row: Any, key: str, default: Any = None) -> Any:
    if row is None:
        return default
    if isinstance(row, dict):
        return row.get(key, default)
    return getattr(row, key, default)


def new_join_code() -> str:
    """A six-character join code with at least one digit.

    The top-bar scanner reads a typed six-character word as a session code only when it holds a
    digit (owner, 2026-10-20), so an all-letter word such as a name is never mistaken for one.
    """
    while True:
        code = "".join(secrets.choice(JOIN_ALPHABET) for _ in range(JOIN_CODE_LENGTH))
        if any(character.isdigit() for character in code):
            return code


def jsonable(value: Any) -> Any:
    """A value Prisma's Json wrapper will take (datetimes as ISO strings)."""
    return json.loads(json.dumps(value, default=lambda v: iso(v) if isinstance(v, (datetime, date)) else str(v)))


def ids(rows: list[Any], key: str = "id") -> list[str]:
    return [str(get(row, key)) for row in rows if get(row, key)]
