"""The consent rule, the same in both portals.

Consent is assumed by default and anyone can revoke it (owner decision, 19 Oct 2026). A recorded
refusal blocks ONLY sending a recording to an outside service (transcription, AI summary,
translation); no answer on record counts as agreeing. Recording is never blocked. Both portals keep every consent in ONE store, the
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

#: Only for a consent store that could not be read: nothing is sent and the send is tried again.
CONSENT_UNREADABLE_MESSAGE = (
    "The consent record could not be read, so nothing was sent. It will be tried again."
)
CONSENT_REFUSED_MESSAGE = "Consent was refused, so this recording stays on this server."


@dataclass(frozen=True)
class ConsentVerdict:
    may_send: bool
    decision: str  # GRANTED | REFUSED | NOT_RECORDED
    refusal: str | None = None


def verdict_for(decision: str | None) -> ConsentVerdict:
    """GRANTED and NOT_RECORDED (no answer, which counts as agreeing) send; REFUSED does not. None,
    or a word this cannot read, is a store that could not be read: nothing is sent, never a guess."""
    if decision == GRANTED:
        return ConsentVerdict(True, GRANTED)
    if decision == NOT_RECORDED:
        return ConsentVerdict(True, NOT_RECORDED)
    if decision == REFUSED:
        return ConsentVerdict(False, REFUSED, CONSENT_REFUSED_MESSAGE)
    return ConsentVerdict(False, NOT_RECORDED, CONSENT_UNREADABLE_MESSAGE)


class ConsentGate(Protocol):
    async def verdict(self, media: Any) -> ConsentVerdict: ...
