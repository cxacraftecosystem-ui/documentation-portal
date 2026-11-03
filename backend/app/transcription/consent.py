from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

OWNER_ATTESTATION_SOURCE = "owner attestation"
OWNER_ATTESTATION_DATE = "2026-10-18"
OWNER_ATTESTATION_NOTE = (
    "The owner confirmed on 18 Oct 2026 that consent exists for every recording made before this "
    "date, including recordings still waiting to be transcribed."
)

GRANTED = "GRANTED"
REFUSED = "REFUSED"
NOT_RECORDED = "NOT_RECORDED"

CONSENT_UNREADABLE_MESSAGE = (
    "The consent record could not be read, so nothing was sent. It will be tried again."
)
CONSENT_REFUSED_MESSAGE = "Consent was refused, so this recording stays on this server."


@dataclass(frozen=True)
class ConsentVerdict:
    may_send: bool
    decision: str
    refusal: str | None = None


def verdict_for(decision: str | None) -> ConsentVerdict:
    if decision == GRANTED:
        return ConsentVerdict(True, GRANTED)
    if decision == NOT_RECORDED:
        return ConsentVerdict(True, NOT_RECORDED)
    if decision == REFUSED:
        return ConsentVerdict(False, REFUSED, CONSENT_REFUSED_MESSAGE)
    return ConsentVerdict(False, NOT_RECORDED, CONSENT_UNREADABLE_MESSAGE)


class ConsentGate(Protocol):
    async def verdict(self, media: Any) -> ConsentVerdict: ...
