"""THE consent gate (DESIGN_2026-10-18.md section 3.11; owner decision 1 and correction (c)).

ONE STORE AND ONE RULE (owner decision, 19 Oct 2026). Every consent is a row in the ``Consent``
ledger, and the rule is ``app/transcription/ledger.py``'s ``decide``, the same function
designer-portal's gate calls. This module adds only what is this portal's: WHO a recording is about.

One question, asked before ANY outside send (speech-to-text, AI summary, insights, translation,
answer drafting): may this recording leave the server? ``verdict(media)`` answers ALLOW, AWAITING or
REFUSED:

1. the recording's own current ``RECORDING`` row: REFUSED -> REFUSED; GRANTED -> ALLOW unless a
   subject refused later than that grant. The owner's attestation of 18 Oct 2026 writes these
   (``scripts/backfill_consent_owner_attestation.py``), and so does a researcher recording an answer
   for one recording (``/media/{id}/recording-consent``);
2. the subjects are the recording's ANSWERED and PRESENT subjects; for media outside the
   questionnaire, the artisans of its linked record;
3. any subject REFUSED -> REFUSED (``transcriptStatus = REFUSED_CONSENT``);
4. every subject GRANTED -> ALLOW;
5. otherwise, including a recording with no artisan subject -> AWAITING (``AWAITING_CONSENT``).
   When consent is recorded later the item moves on by itself, subject to the pause.

THE OLDER ``RecordingConsent`` TABLE IS READ ONLY AS A FALLBACK. Its rows are copied into the ledger
by ``scripts/migrate_recording_consent_to_ledger.py`` (dry run first); until then
:func:`current_states` folds every row not yet copied in beside the ledger's, newest answer winning,
so nothing recorded there is lost and nothing writes there any more. Once the script has run against
production the fallback reads nothing, and a later release drops it with the table.

NEVER CONSULTED BY recording, uploading, filing, playing, typing answers, review or export: none of
those sends anything anywhere. ``tests/test_consent_gate.py`` holds that line.

It implements the transcription design's ``ConsentGate`` protocol (``DocumentationConsentGate``),
so the shared transcription package calls it without importing Prisma. This module is shared with
``feature/s-transcribe-hotfix``; BACKEND owns its tests.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime, timedelta, timezone
from typing import Any

from app.transcription import ledger
from app.transcription.ledger import ALLOW, AWAITING, REFUSED, Verdict

__all__ = ["ALLOW", "AWAITING", "REFUSED", "Verdict"]

#: The owner's attestation (18 Oct 2026) covers every recording made up to the end of that day, IST.
ATTESTATION_CUTOFF = datetime(2026, 10, 18, 23, 59, 59, 999000, tzinfo=timezone(timedelta(hours=5, minutes=30)))
#: The moment the attestation rows are stamped with (2026-10-18T00:00+05:30).
ATTESTATION_RECORDED_AT = datetime(2026, 10, 18, 0, 0, tzinfo=timezone(timedelta(hours=5, minutes=30)))

PURPOSE = ledger.EXTERNAL_PROCESSING
SOURCE_RESEARCHER = ledger.SOURCE_RESEARCHER
SOURCE_OWNER = ledger.SOURCE_OWNER
TRANSCRIPT_STATUS_FOR = {AWAITING: "AWAITING_CONSENT", REFUSED: "REFUSED_CONSENT"}
#: The older table the fallback reads, and the ``migratedFrom`` prefix its copies carry.
LEGACY_TABLE = "RecordingConsent"


def decide(recording: Any, subjects: Iterable[Any]) -> Verdict:
    """The shared rule (``ledger.decide``): the recording's own row, then one row per subject."""
    return ledger.decide(recording, subjects)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


#: The ``RecordingConsent`` rows not yet copied into the ledger, in the ledger's columns. ``{scope}``
#: picks a recording's own rows or an artisan's (a row naming a recording is never an artisan's).
_LEGACY_SQL = (
    'SELECT {subject} AS "subjectId", rc."decision" AS state, '
    """CASE WHEN rc."source" = 'owner attestation' THEN 'OWNER_ATTESTATION' ELSE 'VERBAL_RECORDED' END AS basis, """
    """COALESCE(rc."source", 'researcher') AS "source", rc."attestedAt" AS "recordedAt", rc."createdAt", """
    'rc."attestedById" AS "actorId", rc."note", NULL AS "evidenceMediaId", 1 AS "version", '
    'NULL AS "migratedFrom", 1 AS legacy FROM "RecordingConsent" rc WHERE {scope} '
    """AND NOT EXISTS (SELECT 1 FROM "Consent" c WHERE c."migratedFrom" = 'RecordingConsent:' || rc."id")"""
)
_LEGACY_SCOPE = {
    ledger.RECORDING: ('rc."mediaFileId"', 'rc."mediaFileId" = ANY($1::text[])'),
    ledger.ARTISAN: ('rc."artisanId"', 'rc."mediaFileId" IS NULL AND rc."artisanId" = ANY($1::text[])'),
}


async def current_states(
    subject_type: str, subject_ids: list[str], *, purpose: str = PURPOSE, client: Any = None
) -> dict[str, dict[str, Any]]:
    """The newest consent row per subject, keyed by subject id: the ledger's, or a newer row of the
    older ``RecordingConsent`` table that has not been copied into it yet (module docstring)."""
    ids = list(dict.fromkeys(str(i) for i in subject_ids if i))
    if not ids:
        return {}
    if purpose != PURPOSE or subject_type not in _LEGACY_SCOPE:
        return await ledger.current_states(_db(client), subject_type, ids, purpose=purpose)
    subject, scope = _LEGACY_SCOPE[subject_type]
    rows = await _db(client).query_raw(
        'SELECT DISTINCT ON ("subjectId") * FROM ('
        f"SELECT {ledger.COLUMNS}, 0 AS legacy FROM consent_current_v "
        'WHERE "subjectType" = $2::"ConsentSubjectType" AND "purpose" = $3::"ConsentPurpose" '
        'AND "subjectId" = ANY($1::text[]) UNION ALL '
        + _LEGACY_SQL.format(subject=subject, scope=scope)
        + ') x ORDER BY "subjectId", "recordedAt" DESC, "createdAt" DESC, legacy ASC',
        ids, subject_type, purpose,
    )
    return {str(row["subjectId"]): {k: v for k, v in dict(row).items() if k != "legacy"} for row in rows}


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
    return (await _decided(media_id, client))[0]


async def _decided(media_id: str, client: Any) -> tuple[Verdict, dict[str, Any] | None]:
    grant = (await current_states(ledger.RECORDING, [media_id], client=client)).get(media_id)
    subjects = await _media_subjects(media_id, client)
    states = await current_states(ledger.ARTISAN, subjects, client=client)
    return decide(grant, [states.get(artisan_id) for artisan_id in subjects]), grant


async def verdict_detail(media_id: str, *, client: Any = None) -> dict[str, Any]:
    """``{"verdict", "source"}`` for a take row: the recording's own grant's source (the owner's
    attestation of 18 Oct) when that grant is what lets it go, else "researcher" when the subjects'
    consent recorded in the app decided it."""
    result, grant = await _decided(media_id, client)
    if result == ALLOW and (grant or {}).get("state") == ledger.GRANTED:
        return {"verdict": ALLOW, "source": (grant or {}).get("source") or SOURCE_OWNER}
    return {"verdict": result, "source": SOURCE_RESEARCHER if result != AWAITING else None}


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
    written = await ledger.append(
        _db(client), subject_type=ledger.ARTISAN, subject_id=artisan_id, state=state, basis=basis,
        actor_id=actor_id, purpose=purpose, note=note, evidence_media_id=evidence_media_id,
        recorded_at=recorded_at,
    )
    return written or {}


async def record_recording_consent(
    media_id: str,
    *,
    state: str,
    actor_id: str,
    basis: str = ledger.VERBAL_RECORDED,
    note: str | None = None,
    recorded_at: datetime | None = None,
    client: Any = None,
) -> dict[str, Any]:
    """Append one RECORDING row: an answer about this one recording (the rule in the module docstring)."""
    written = await ledger.append(
        _db(client), subject_type=ledger.RECORDING, subject_id=media_id, state=state, basis=basis,
        actor_id=actor_id, note=note, recorded_at=recorded_at,
    )
    return written or {}
