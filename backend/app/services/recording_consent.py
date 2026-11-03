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
    if media_id:
        return (await consent_gate.current_states(ledger.RECORDING, [media_id])).get(media_id)
    if artisan_id:
        return (await consent_gate.current_states(ledger.ARTISAN, [artisan_id])).get(artisan_id)
    return None


async def decision_for(media: Any) -> str:
    return ledger.state_for(await consent_gate.verdict(str(_value(media, "id"))))


class RecordingConsentGate:
    async def verdict(self, media: Any) -> ConsentVerdict:
        try:
            return verdict_for(await decision_for(media))
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "recording consent of media %s could not be read (%s); refusing the send",
                _value(media, "id"),
                type(exc).__name__,
            )
            return verdict_for(None)


gate = RecordingConsentGate()


class ConsentRefused(Exception):
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
    return {
        "decision": decision,
        "recorded": bool(row),
        "granted": decision == GRANTED,
        "note": _value(row, "note") if row else None,
        "source": _value(row, "source") if row else None,
        "attestedAt": _value(row, "recordedAt") if row else None,
    }
