"""The consent rule for recordings, as the shared transcription package asks it (18 Oct 2026).

Consent is assumed by default and can be revoked (owner decision, 19 Oct 2026): a recorded refusal
blocks ONLY sending a recording to an outside service (transcription, AI summaries and insights,
translation), and no answer on record counts as agreeing. Recording, upload and storage are never
blocked.

ONE STORE (owner decision, 19 Oct 2026). Every answer lives in the ``Consent`` ledger, decided by
``services/consent_gate.py`` with the rule designer-portal uses too (``app/transcription/ledger.py``).
This module is the adapter the queue, "Transcribe now" and the insights job call
(``app.transcription.consent.ConsentGate``), plus the two ``/recording-consent`` routes' reads and
writes, kept so installed apps that still call those routes keep working; both apps now record the
answer through ``/artisans/{id}/consent``, and every route writes the same ledger row.

The older ``RecordingConsent`` table is no longer written. Until
``scripts/migrate_recording_consent_to_ledger.py`` has copied it into the ledger, the gate's reads
fold its rows in (``consent_gate.current_states``).

A failed read refuses, never raises: an unreadable store may hold a refusal, so it must not become
a send.
"""

from __future__ import annotations

import logging
from typing import Any

from app.services import consent_gate
from app.transcription import ledger
from app.transcription.consent import (
    GRANTED,
    NOT_RECORDED,
    REFUSED,
    ConsentVerdict,
    verdict_for,
)

logger = logging.getLogger(__name__)

DECISIONS = (GRANTED, REFUSED)
__all__ = ["NOT_RECORDED", "ConsentRefused", "gate"]


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


async def latest_decision(*, media_id: str | None = None, artisan_id: str | None = None) -> dict[str, Any] | None:
    """The current row for one recording (its own answer) or one artisan, from the one store."""
    if media_id:
        return (await consent_gate.current_states(ledger.RECORDING, [media_id])).get(media_id)
    if artisan_id:
        return (await consent_gate.current_states(ledger.ARTISAN, [artisan_id])).get(artisan_id)
    return None


async def decision_for(media: Any) -> str:
    """GRANTED, REFUSED or NOT_RECORDED for one stored recording. Raises on a failed read."""
    return ledger.state_for(await consent_gate.verdict(str(_value(media, "id"))))


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
    actor_id: str,
    media_id: str | None = None,
    artisan_id: str | None = None,
    note: str | None = None,
) -> dict[str, Any]:
    """Append one decision to the ledger and return it as :func:`payload` reads a row."""
    if decision not in DECISIONS:
        raise ValueError("decision must be GRANTED or REFUSED")
    if bool(media_id) == bool(artisan_id):
        raise ValueError("a consent is about one recording or one artisan")
    if media_id:
        await consent_gate.record_recording_consent(media_id, state=decision, actor_id=actor_id, note=note)
    else:
        await consent_gate.record_artisan_consent(
            str(artisan_id), state=decision, basis=ledger.VERBAL_RECORDED, actor_id=actor_id, note=note
        )
    return await latest_decision(media_id=media_id, artisan_id=artisan_id) or {}


def payload(row: Any | None, decision: str) -> dict[str, Any]:
    """The ``/recording-consent`` wire shape, unchanged for the apps already installed."""
    return {
        "decision": decision,
        "recorded": bool(row),
        "granted": decision == GRANTED,
        "note": _value(row, "note") if row else None,
        "source": _value(row, "source") if row else None,
        "attestedAt": _value(row, "recordedAt") if row else None,
    }
