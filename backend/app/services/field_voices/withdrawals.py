from __future__ import annotations

from typing import Any

from app.transcription import ledger as consent_ledger

__all__ = ["BASES", "SUBJECT_TYPES", "UnknownSubject", "record", "status"]

PUBLICATION = consent_ledger.PUBLICATION
SUBJECT_TYPES = (
    consent_ledger.ARTISAN,
    consent_ledger.RECORDING,
    consent_ledger.WORKSHOP,
    consent_ledger.USER,
)
BASES = (consent_ledger.VERBAL_RECORDED, consent_ledger.VERBAL_WITNESSED, consent_ledger.SIGNED_FORM)
NOTE_MAX_LENGTH = 500

EXISTS_SQL = {
    consent_ledger.ARTISAN: 'SELECT "id" FROM "Artisan" WHERE "id" = $1',
    consent_ledger.RECORDING: 'SELECT "id" FROM "MediaFile" WHERE "id" = $1',
    consent_ledger.USER: 'SELECT "id" FROM "User" WHERE "id" = $1',
    consent_ledger.WORKSHOP: 'SELECT "id" FROM "Workshop" WHERE "id" = $1',
}


class UnknownSubject(LookupError):
    pass


async def _exists(client: Any, adapter: Any, subject_type: str, subject_id: str) -> bool:
    if subject_type not in SUBJECT_TYPES:
        return False
    if await client.query_raw(EXISTS_SQL[subject_type], subject_id):
        return True
    if subject_type == consent_ledger.WORKSHOP:
        found = await adapter.design_workshops(client, {subject_id})
        return subject_id in found
    return False


def _view(subject_type: str, subject_id: str, row: dict[str, Any] | None) -> dict[str, Any]:
    state = str((row or {}).get("state") or consent_ledger.NOT_RECORDED)
    recorded = (row or {}).get("recordedAt")
    return {
        "subjectType": subject_type,
        "subjectId": subject_id,
        "shown": state != consent_ledger.REFUSED,
        "state": state,
        "basis": (row or {}).get("basis"),
        "note": (row or {}).get("note"),
        "recordedAt": recorded.isoformat() if hasattr(recorded, "isoformat") else recorded,
    }


async def status(client: Any, adapter: Any, subject_type: str, subject_id: str) -> dict[str, Any]:
    if not await _exists(client, adapter, subject_type, subject_id):
        raise UnknownSubject(f"{subject_type} {subject_id}")
    states = await consent_ledger.current_states(client, subject_type, [subject_id], purpose=PUBLICATION)
    return _view(subject_type, subject_id, states.get(subject_id))


async def record(
    client: Any, adapter: Any, subject_type: str, subject_id: str, *, shown: bool, basis: str,
    note: str | None, actor_id: str,
) -> dict[str, Any]:
    if basis not in BASES:
        raise ValueError(f"unknown basis {basis!r}")
    if not await _exists(client, adapter, subject_type, subject_id):
        raise UnknownSubject(f"{subject_type} {subject_id}")
    await consent_ledger.append(
        client,
        subject_type=subject_type,
        subject_id=subject_id,
        state=consent_ledger.GRANTED if shown else consent_ledger.REFUSED,
        basis=basis,
        actor_id=actor_id,
        purpose=PUBLICATION,
        note=(note or "").strip()[:NOTE_MAX_LENGTH] or None,
    )
    return await status(client, adapter, subject_type, subject_id)
