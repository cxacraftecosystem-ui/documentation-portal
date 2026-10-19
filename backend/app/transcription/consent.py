"""The consent rule, the same in both portals.

Missing consent blocks ONLY sending a recording to an outside service (transcription, AI summary,
translation). Recording is never blocked. Both portals keep every consent in ONE store, the
``Consent`` ledger, and decide it with ONE rule (:mod:`ledger` in this package). Each portal names
who a recording is about and hands the package a :class:`ConsentVerdict`:

* designer-portal: the workshops that hold the recording (``services/dictation_consent.py``).
* documentation-portal: the recording's artisans (``services/recording_consent.py``).

Recordings that existed on 18 Oct 2026 carry consent by owner attestation (``source`` below); the
backfill scripts write it and flag nothing.
"""

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

CONSENT_NOT_RECORDED_MESSAGE = (
    "Consent not recorded. Record it to send this recording for transcription."
)
CONSENT_REFUSED_MESSAGE = "Consent was refused, so this recording stays on this server."


@dataclass(frozen=True)
class ConsentVerdict:
    may_send: bool
    decision: str  # GRANTED | REFUSED | NOT_RECORDED
    refusal: str | None = None


def verdict_for(decision: str | None) -> ConsentVerdict:
    if decision == GRANTED:
        return ConsentVerdict(True, GRANTED)
    if decision == REFUSED:
        return ConsentVerdict(False, REFUSED, CONSENT_REFUSED_MESSAGE)
    return ConsentVerdict(False, NOT_RECORDED, CONSENT_NOT_RECORDED_MESSAGE)


class ConsentGate(Protocol):
    async def verdict(self, media: Any) -> ConsentVerdict: ...
