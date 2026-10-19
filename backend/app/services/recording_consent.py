"""The consent rule for recordings in this portal (owner decision, 18 Oct 2026).

Missing consent blocks ONLY sending a recording to an outside service: transcription, AI summaries
and translation. Recording, upload and storage are never blocked. It is the same rule designer-portal
enforces through ``DesignWorkshop.dictationConsent`` (``app/transcription/consent.py`` holds the
shared words), decided here against ``RecordingConsent`` rows:

* a row for the recording itself (``mediaFileId``) wins;
* otherwise the newest row for the recording's artisan (``artisanId``) answers for every recording
  of them;
* otherwise consent is NOT_RECORDED and nothing is sent.

Every recording that existed on 18 Oct 2026 carries a GRANTED row by owner attestation, written by
``scripts/backfill_consent_owner_attestation.py`` (dry run first). Nothing is flagged.

A failed read refuses, never raises: an unknown consent must not become a send.
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.db import db
from app.transcription.consent import (
    GRANTED,
    NOT_RECORDED,
    REFUSED,
    ConsentVerdict,
    verdict_for,
)

logger = logging.getLogger(__name__)

DECISIONS = (GRANTED, REFUSED)


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


async def latest_decision(*, media_id: str | None = None, artisan_id: str | None = None) -> Any | None:
    where: dict[str, Any] = {"mediaFileId": media_id} if media_id else {"artisanId": artisan_id, "mediaFileId": None}
    return await db.recordingconsent.find_first(where=where, order=[{"attestedAt": "desc"}, {"createdAt": "desc"}])


async def decision_for(media: Any) -> str:
    """GRANTED, REFUSED or NOT_RECORDED for one stored recording. Raises on a failed read."""
    own = await latest_decision(media_id=str(_value(media, "id")))
    if own is not None:
        return str(own.decision)
    artisan_id = _value(media, "artisanId")
    if artisan_id:
        theirs = await latest_decision(artisan_id=str(artisan_id))
        if theirs is not None:
            return str(theirs.decision)
    return NOT_RECORDED


class RecordingConsentGate:
    """``app.transcription.consent.ConsentGate`` for this portal."""

    async def verdict(self, media: Any) -> ConsentVerdict:
        try:
            return verdict_for(await decision_for(media))
        except Exception as exc:  # noqa: BLE001 - see the module docstring: refuse, never raise
            logger.warning(
                "recording consent of media %s could not be read (%s); refusing the send",
                _value(media, "id"),
                type(exc).__name__,
            )
            return verdict_for(None)


gate = RecordingConsentGate()


class ConsentRefused(Exception):
    """Raised by "Transcribe now" when consent does not permit the send; routes answer 409."""

    def __init__(self, verdict: ConsentVerdict) -> None:
        super().__init__(verdict.refusal or "This recording may not be sent.")
        self.verdict = verdict


async def record(
    *,
    decision: str,
    actor_id: str | None,
    media_id: str | None = None,
    artisan_id: str | None = None,
    note: str | None = None,
    source: str | None = None,
    attested_at: Any = None,
) -> Any:
    """Append one decision. The newest row for a scope is the answer; older rows are the history."""
    if decision not in DECISIONS:
        raise ValueError("decision must be GRANTED or REFUSED")
    if bool(media_id) == bool(artisan_id):
        raise ValueError("a consent is about one recording or one artisan")
    data: dict[str, Any] = {
        "decision": decision,
        "attestedById": actor_id,
        "note": (note or "").strip() or None,
        "source": source,
    }
    if media_id:
        data["mediaFileId"] = media_id
    if artisan_id:
        data["artisanId"] = artisan_id
    if attested_at is not None:
        data["attestedAt"] = attested_at
    return await db.recordingconsent.create(data=data)


def payload(row: Any | None, decision: str) -> dict[str, Any]:
    return {
        "decision": decision,
        "recorded": row is not None,
        "granted": decision == GRANTED,
        "note": _value(row, "note") if row is not None else None,
        "source": _value(row, "source") if row is not None else None,
        "attestedAt": _value(row, "attestedAt") if row is not None else None,
    }
