"""THE consent gate (DESIGN_2026-10-18.md section 3.11; owner decision 1 and correction (c)).

ONE STORE AND ONE RULE (owner decision, 19 Oct 2026). Every consent is a row in the ``Consent``
ledger, and the rule is ``app/transcription/ledger.py``'s ``decide``, the same function
designer-portal's gate calls. This module adds only what is this portal's: WHO a recording is about.

One question, asked before ANY outside send (speech-to-text, AI summary, insights, translation,
answer drafting, search embeddings): may this recording leave the server? Text about people that is
no one recording (an answer draft from a pasted transcript) asks :func:`interview_verdict`, the same
rule over the interview's artisans. Consent is ASSUMED BY DEFAULT and anyone can revoke it (owner
decision, 19 Oct 2026: "the consent should be assumed by default, people can revoke it if
required"). ``verdict(media)`` answers ALLOW or REFUSED:

1. the recording's own current ``RECORDING`` row: REFUSED -> REFUSED; GRANTED -> ALLOW unless a
   subject refused later than that grant. The owner's attestation of 18 Oct 2026 writes these
   (``scripts/backfill_consent_owner_attestation.py``), and so does a researcher recording an answer
   for one recording (``/media/{id}/recording-consent``);
2. the subjects are the recording's ANSWERED and PRESENT subjects; for media outside the
   questionnaire, the artisans of its linked record;
3. any subject REFUSED -> REFUSED (``transcriptStatus = REFUSED_CONSENT``);
4. otherwise ALLOW: a subject with no answer counts as agreeing, and so does a recording with no
   artisan subject. A later GRANTED after a refusal allows again and re-enqueues, subject to the pause.

``AWAITING`` stays in the vocabulary only because recordings stored before 19 Oct 2026 can still
carry ``AWAITING_CONSENT``; ``scripts/release_awaiting_consent.py`` moves them on.

THE OLDER ``RecordingConsent`` TABLE IS NOT READ (audit M8, 21 Oct 2026). It held 0 rows in
production, so the fallback that folded its uncopied rows in beside the ledger's read nothing and
cost a UNION on every consent question. ``scripts/migrate_recording_consent_to_ledger.py`` stays for
any other database; a later release drops the table.

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


def decide(recording: Any, subjects: Iterable[Any]) -> Verdict:
    """The shared rule (``ledger.decide``): the recording's own row, then one row per subject."""
    return ledger.decide(recording, subjects)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def current_states(
    subject_type: str, subject_ids: list[str], *, purpose: str = PURPOSE, client: Any = None
) -> dict[str, dict[str, Any]]:
    """The newest ledger row per subject, keyed by subject id."""
    ids = list(dict.fromkeys(str(i) for i in subject_ids if i))
    if not ids:
        return {}
    return await ledger.current_states(_db(client), subject_type, ids, purpose=purpose)


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
        return []  # a questionnaire recording with no artisan subject: sent (no answer)
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


async def _media_subjects_many(media_ids: list[str], client: Any) -> dict[str, list[str]]:
    """:func:`_media_subjects` for many media files in at most two reads, whatever their number.

    The first read answers rule 2 for every questionnaire recording at once (a recording with no
    subject comes back as one row with a NULL artisan, which is the "no answer" case); the second
    asks the linked-record fallback only for the media that are no recording at all.
    """
    db = _db(client)
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    if not ids:
        return {}
    out: dict[str, list[str]] = {}
    rows = await db.query_raw(
        'SELECT r."mediaFileId" AS m, s."artisanId" FROM "QuestionnaireRecording" r '
        'LEFT JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = r."id" WHERE r."mediaFileId" = ANY($1::text[])',
        ids,
    )
    for row in rows:
        subjects = out.setdefault(str(row["m"]), [])
        if row.get("artisanId"):
            subjects.append(str(row["artisanId"]))
    rest = [media_id for media_id in ids if media_id not in out]
    if rest:
        linked = await db.query_raw(
            'SELECT m."id" AS m, m."artisanId" AS a FROM "MediaFile" m WHERE m."id" = ANY($1::text[]) AND m."artisanId" IS NOT NULL '
            'UNION SELECT m."id", qa."artisanId" FROM "MediaFile" m JOIN "QuestionnaireInterviewArtisan" qa '
            '  ON qa."interviewId" = m."questionnaireInterviewId" WHERE m."id" = ANY($1::text[]) '
            'UNION SELECT m."id", p."artisanId" FROM "MediaFile" m JOIN "ProductDocumentation" p ON p."id" = m."productId" '
            '  WHERE m."id" = ANY($1::text[]) AND p."artisanId" IS NOT NULL '
            'UNION SELECT m."id", t."artisanId" FROM "MediaFile" m JOIN "ToolArtisan" t ON t."toolId" = m."toolId" '
            '  WHERE m."id" = ANY($1::text[])',
            rest,
        )
        for media_id in rest:
            out[media_id] = []
        for row in linked:
            if row.get("a"):
                out[str(row["m"])].append(str(row["a"]))
    return out


async def verdict_details(media_ids: Iterable[str], *, client: Any = None) -> dict[str, dict[str, Any]]:
    """:func:`verdict_detail` for many media files, keyed by media id, in at most four reads.

    Same rule, same answer per file as asking one by one; a list of recordings (a session, an
    artisan's record, the corpus feed) no longer pays three or four round trips per row for it.
    """
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    if not ids:
        return {}
    grants = await current_states(ledger.RECORDING, ids, client=client)
    subjects = await _media_subjects_many(ids, client)
    everyone = [artisan_id for media_id in ids for artisan_id in subjects.get(media_id, [])]
    states = await current_states(ledger.ARTISAN, everyone, client=client)
    out: dict[str, dict[str, Any]] = {}
    for media_id in ids:
        grant = grants.get(media_id)
        rows = [states.get(artisan_id) for artisan_id in subjects.get(media_id, [])]
        result = decide(grant, rows)
        if result == ALLOW and (grant or {}).get("state") == ledger.GRANTED:
            out[media_id] = {"verdict": ALLOW, "source": (grant or {}).get("source") or SOURCE_OWNER}
        else:
            answered = grant is not None or any(rows)
            out[media_id] = {"verdict": result, "source": SOURCE_RESEARCHER if answered else None}
    return out


async def verdict(media: Any, *, client: Any = None) -> Verdict:
    """ALLOW | AWAITING | REFUSED for one media file (a row, a dict or its id)."""
    media_id = media if isinstance(media, str) else str(getattr(media, "id", None) or media["id"])
    return (await _decided(media_id, client))[0]


async def _decided(media_id: str, client: Any) -> tuple[Verdict, dict[str, Any] | None, bool]:
    """The verdict, the recording's own row, and whether any answer at all is on record."""
    grant = (await current_states(ledger.RECORDING, [media_id], client=client)).get(media_id)
    subjects = await _media_subjects(media_id, client)
    states = await current_states(ledger.ARTISAN, subjects, client=client)
    rows = [states.get(artisan_id) for artisan_id in subjects]
    return decide(grant, rows), grant, grant is not None or any(rows)


async def artisans_verdict(artisan_ids: Iterable[str], *, client: Any = None) -> Verdict:
    """The rule for text that is about people but is no one recording (rules 3 and 4): one REFUSED
    refuses; otherwise it is sent (no answer, or no artisan at all, counts as agreeing)."""
    ids = list(dict.fromkeys(str(i) for i in artisan_ids if i))
    states = await current_states(ledger.ARTISAN, ids, client=client)
    return decide(None, [states.get(artisan_id) for artisan_id in ids])


async def interview_verdict(interview_id: str, *, client: Any = None) -> Verdict:
    """:func:`artisans_verdict` over an interview's participants: text pasted into an interview (an
    answer draft from a pasted transcript) is the interview's artisans' words."""
    rows = await _db(client).query_raw(
        'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', interview_id
    )
    return await artisans_verdict([str(row["artisanId"]) for row in rows], client=client)


async def verdict_detail(media_id: str, *, client: Any = None) -> dict[str, Any]:
    """``{"verdict", "source"}`` for a take row: the recording's own grant's source (the owner's
    attestation of 18 Oct) when that grant is what lets it go, else "researcher" when an answer
    recorded in the app decided it, else None (no answer on record: consent assumed by default)."""
    result, grant, answered = await _decided(media_id, client)
    if result == ALLOW and (grant or {}).get("state") == ledger.GRANTED:
        return {"verdict": ALLOW, "source": (grant or {}).get("source") or SOURCE_OWNER}
    return {"verdict": result, "source": SOURCE_RESEARCHER if answered else None}


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
