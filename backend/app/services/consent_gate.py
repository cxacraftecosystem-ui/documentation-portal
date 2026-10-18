"""THE consent gate (DESIGN_2026-10-18.md section 3.11; owner decision 1 and correction (c)).

One question, asked before ANY outside send (speech-to-text, AI summary, translation, answer
drafting): may this recording leave the server? ``verdict(media)`` answers ALLOW, AWAITING or
REFUSED, in this order:

1. a current ``RECORDING`` consent row for this media with state GRANTED -> ALLOW. Only the owner's
   attestation of 18 Oct 2026 writes these (``scripts/backfill_consent_owner_attestation.py``);
2. the subjects are the recording's ANSWERED and PRESENT subjects; for media outside the
   questionnaire, the artisans of its linked record;
3. any subject REFUSED -> REFUSED (``transcriptStatus = REFUSED_CONSENT``);
4. every subject GRANTED -> ALLOW;
5. otherwise, including a recording with no artisan subject -> AWAITING (``AWAITING_CONSENT``).
   When consent is recorded later the item moves on by itself, subject to the pause.

NEVER CONSULTED BY recording, uploading, filing, playing, typing answers, review or export: none of
those sends anything anywhere. ``tests/test_consent_gate.py`` holds that line.

It implements the transcription design's ``ConsentGate`` protocol (``DocumentationConsentGate``),
so the shared transcription package calls it without importing Prisma. This module is shared with
``feature/s-transcribe-hotfix``; BACKEND owns its tests.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

ALLOW = "ALLOW"
AWAITING = "AWAITING"
REFUSED = "REFUSED"
Verdict = Literal["ALLOW", "AWAITING", "REFUSED"]

#: The owner's attestation (18 Oct 2026) covers every recording made up to the end of that day, IST.
ATTESTATION_CUTOFF = datetime(2026, 10, 18, 23, 59, 59, 999000, tzinfo=timezone(timedelta(hours=5, minutes=30)))
#: The moment the attestation rows are stamped with (2026-10-18T00:00+05:30).
ATTESTATION_RECORDED_AT = datetime(2026, 10, 18, 0, 0, tzinfo=timezone(timedelta(hours=5, minutes=30)))

PURPOSE = "EXTERNAL_PROCESSING"
SOURCE_RESEARCHER = "researcher"
SOURCE_OWNER = "owner attestation"
TRANSCRIPT_STATUS_FOR = {AWAITING: "AWAITING_CONSENT", REFUSED: "REFUSED_CONSENT"}


def decide(recording_grant: str | None, subject_states: Iterable[str | None]) -> Verdict:
    """The five rules, as a pure function. ``recording_grant`` is the current RECORDING row's state
    (or None); ``subject_states`` one current ARTISAN state per subject (None = no row)."""
    if recording_grant == "GRANTED":
        return ALLOW
    states = list(subject_states)
    if any(state == "REFUSED" for state in states):
        return REFUSED
    if states and all(state == "GRANTED" for state in states):
        return ALLOW
    return AWAITING


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def current_states(
    subject_type: str, subject_ids: list[str], *, purpose: str = PURPOSE, client: Any = None
) -> dict[str, dict[str, Any]]:
    """The latest consent row per subject (``consent_current_v``), keyed by subject id."""
    if not subject_ids:
        return {}
    rows = await _db(client).query_raw(
        'SELECT "subjectId", "state"::text AS state, "basis"::text AS basis, "source", "recordedAt", '
        '"actorId", "note", "evidenceMediaId", "version" FROM consent_current_v '
        'WHERE "subjectType" = $1::"ConsentSubjectType" AND "purpose" = $2::"ConsentPurpose" '
        'AND "subjectId" = ANY($3::text[])',
        subject_type, purpose, list(dict.fromkeys(subject_ids)),
    )
    return {str(row["subjectId"]): row for row in rows}


async def _media_subjects(media_id: str, client: Any) -> list[str]:
    """Rule 2: the recording's subjects, else the artisans of the media's linked record."""
    db = _db(client)
    rows = await db.query_raw(
        'SELECT s."artisanId" FROM "QuestionnaireRecordingSubject" s '
        'JOIN "QuestionnaireRecording" r ON r."id" = s."recordingId" WHERE r."mediaFileId" = $1',
        media_id,
    )
    if rows:
        return [str(row["artisanId"]) for row in rows]
    has_recording = await db.query_raw('SELECT 1 FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', media_id)
    if has_recording:
        return []  # a questionnaire recording with no artisan subject: AWAITING
    rows = await db.query_raw(
        'SELECT m."artisanId" AS a FROM "MediaFile" m WHERE m."id" = $1 AND m."artisanId" IS NOT NULL '
        'UNION SELECT qa."artisanId" FROM "MediaFile" m JOIN "QuestionnaireInterviewArtisan" qa '
        '  ON qa."interviewId" = m."questionnaireInterviewId" WHERE m."id" = $1 '
        'UNION SELECT p."artisanId" FROM "MediaFile" m JOIN "ProductDocumentation" p ON p."id" = m."productId" '
        '  WHERE m."id" = $1 AND p."artisanId" IS NOT NULL '
        'UNION SELECT t."artisanId" FROM "MediaFile" m JOIN "ToolArtisan" t ON t."toolId" = m."toolId" WHERE m."id" = $1',
        media_id,
    )
    return [str(row["a"]) for row in rows if row.get("a")]


async def verdict(media: Any, *, client: Any = None) -> Verdict:
    """ALLOW | AWAITING | REFUSED for one media file (a row, a dict or its id)."""
    media_id = media if isinstance(media, str) else str(getattr(media, "id", None) or media["id"])
    grant = await current_states("RECORDING", [media_id], client=client)
    subjects = await _media_subjects(media_id, client)
    states = await current_states("ARTISAN", subjects, client=client)
    return decide(
        (grant.get(media_id) or {}).get("state"),
        [(states.get(artisan_id) or {}).get("state") for artisan_id in subjects],
    )


async def verdict_detail(media_id: str, *, client: Any = None) -> dict[str, Any]:
    """``{"verdict", "source"}`` for a take row: the source is "owner attestation" when the 18 Oct
    attestation covers it, else "researcher" when every subject's consent was recorded in the app."""
    grant = await current_states("RECORDING", [media_id], client=client)
    if (grant.get(media_id) or {}).get("state") == "GRANTED":
        return {"verdict": ALLOW, "source": SOURCE_OWNER}
    result = await verdict(media_id, client=client)
    return {"verdict": result, "source": SOURCE_RESEARCHER if result != AWAITING else None}


def covered_by_attestation(media: Any) -> bool:
    """True when the file was created on or before the attestation cutoff."""
    created = getattr(media, "createdAt", None) if not isinstance(media, dict) else media.get("createdAt")
    if created is None:
        return False
    if isinstance(created, str):
        created = datetime.fromisoformat(created.replace("Z", "+00:00"))
    if created.tzinfo is None:
        from datetime import UTC

        created = created.replace(tzinfo=UTC)
    return created <= ATTESTATION_CUTOFF


class DocumentationConsentGate:
    """The transcription package's ``ConsentGate`` for this portal."""

    def __init__(self, client: Any = None) -> None:
        self._client = client

    async def verdict(self, media_id: str) -> Verdict:
        return await verdict(media_id, client=self._client)


async def record_artisan_consent(
    artisan_id: str,
    *,
    state: str,
    basis: str,
    actor_id: str,
    purpose: str = PURPOSE,
    note: str | None = None,
    evidence_media_id: str | None = None,
    recorded_at: datetime | None = None,
    client: Any = None,
) -> dict[str, Any]:
    """Append one ARTISAN row (the ledger is append-only: a change is a new row)."""
    from datetime import UTC
    from uuid import uuid4

    when = recorded_at or datetime.now(UTC)
    row_id = uuid4().hex
    # The ledger's unique key holds recordedAt to the millisecond; two answers inside one
    # millisecond (a quick Yes after a No) take the next free one instead of being dropped.
    for _ in range(5):
        written = await _db(client).execute_raw(
            'INSERT INTO "Consent" ("id", "subjectType", "subjectId", "purpose", "state", "basis", "source", '
            '"version", "note", "evidenceMediaId", "actorId", "recordedAt") VALUES ($1, \'ARTISAN\', $2, '
            '$3::"ConsentPurpose", $4::"ConsentState", $5::"ConsentBasis", $6, 1, $7, $8, $9, $10::timestamp) '
            'ON CONFLICT ("subjectType", "subjectId", "purpose", "source", "recordedAt") DO NOTHING',
            row_id, artisan_id, purpose, state, basis, SOURCE_RESEARCHER, note, evidence_media_id, actor_id,
            when.astimezone(UTC).replace(tzinfo=None).isoformat(timespec="milliseconds"),
        )
        if written:
            break
        when = when + timedelta(milliseconds=1)
    return {"id": row_id, "recordedAt": when}
