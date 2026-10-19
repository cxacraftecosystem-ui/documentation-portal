"""THE consent store, one model and one rule in both portals (owner decision, 19 Oct 2026).

Every consent either portal holds is a row in the append-only ``Consent`` table: who the consent is
about (``subjectType`` + ``subjectId``), what it is for (``purpose``), the answer (``state``), how it
was given (``basis``), who recorded it (``actorId``) and when the person answered (``recordedAt``).
A change of mind is a NEW row; ``consent_current_v`` is the newest row per subject and purpose, and
the older rows are the history. The table, the view and every index are created by the migration
``20261103090000_one_consent_store``, which is the same file in both repositories.

SUBJECTS. ``ARTISAN`` is the consent of one person, the same record whichever portal asked (both
portals read and write it through ``/artisans/{id}/consent``);
``WORKSHOP`` is designer-portal's per-workshop answer; ``RECORDING`` scopes a consent to one
recording (the owner's attestation of 18 Oct 2026 is written per recording); ``USER`` is kept for the
usage-analytics consent.

THE RULE (:func:`decide`), asked before anything leaves the server for an outside service
(speech-to-text, AI summaries and insights, translation, answer drafting, search embeddings). Consent
is ASSUMED BY DEFAULT and anyone can take it back (owner decision, 19 Oct 2026: "the consent should be
assumed by default, people can revoke it if required"):

1. a REFUSED row for the recording itself refuses;
2. a GRANTED row for the recording allows, unless one of its subjects refused LATER than that grant
   (a withdrawal after the owner's attestation still stops the send);
3. otherwise any subject that refused refuses;
4. otherwise it is sent: a subject with no answer on record counts as agreeing, and so does a
   recording with no subject at all. A later GRANTED row after a refusal allows again, because each
   subject is read at its newest row.

``AWAITING`` is kept in the vocabulary for stored statuses written before the rule changed; the rule
itself never answers it.

Recording, uploading, storing, playing, typing answers, review and export never ask: none of them
sends anything anywhere.

WHERE THE ROWS CAME FROM BEFORE. documentation-portal kept a second table, ``RecordingConsent``, and
designer-portal kept ``DwWorkshopConsentDecision``. Each portal's data script copies those rows in
here, stamping ``migratedFrom`` (:func:`legacy_key`) so a copy is made once; until it has run, each
portal's reader also folds in the rows not yet copied, and nothing else reads the old tables.

This module talks to the database only through the client a portal hands it (``query_raw`` and
``execute_raw``), so it imports nothing from either portal.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from datetime import UTC, datetime, timedelta
from typing import Any, Literal
from uuid import uuid4

# ── The vocabulary: the Postgres enums, spelled once ─────────────────────────────────────────

NOT_RECORDED = "NOT_RECORDED"
GRANTED = "GRANTED"
REFUSED = "REFUSED"
STATES = (NOT_RECORDED, GRANTED, REFUSED)
#: The two answers a person can record; NOT_RECORDED is the absence of one.
DECISIONS = (GRANTED, REFUSED)

ARTISAN = "ARTISAN"
WORKSHOP = "WORKSHOP"
USER = "USER"
RECORDING = "RECORDING"
SUBJECT_TYPES = (ARTISAN, WORKSHOP, USER, RECORDING)

EXTERNAL_PROCESSING = "EXTERNAL_PROCESSING"
CROSS_PORTAL_SHARING = "CROSS_PORTAL_SHARING"
USAGE_ANALYTICS = "USAGE_ANALYTICS"
PURPOSES = (EXTERNAL_PROCESSING, CROSS_PORTAL_SHARING, USAGE_ANALYTICS)

VERBAL_RECORDED = "VERBAL_RECORDED"
VERBAL_WITNESSED = "VERBAL_WITNESSED"
SIGNED_FORM = "SIGNED_FORM"
OWNER_ATTESTATION = "OWNER_ATTESTATION"
BASES = (VERBAL_RECORDED, VERBAL_WITNESSED, SIGNED_FORM, OWNER_ATTESTATION)

#: ``source``: a consent recorded in either app, or the owner's attestation of 18 Oct 2026.
SOURCE_RESEARCHER = "researcher"
SOURCE_OWNER = "owner attestation"

ALLOW = "ALLOW"
AWAITING = "AWAITING"
Verdict = Literal["ALLOW", "AWAITING", "REFUSED"]

#: The columns every reader gets, from the view or from a portal's fallback over its old table.
COLUMNS = (
    '"subjectId", "state"::text AS state, "basis"::text AS basis, "source", "recordedAt", '
    '"createdAt", "actorId", "note", "evidenceMediaId", "version", "migratedFrom"'
)


def legacy_key(table: str, row_id: str) -> str:
    """``migratedFrom`` for a row copied from an older consent table, e.g. ``RecordingConsent:<id>``."""
    return f"{table}:{row_id}"


# ── The rule ──────────────────────────────────────────────────────────────────────────────────


def _state(row: Any) -> str | None:
    if row is None:
        return None
    if isinstance(row, str):
        return row
    value = row.get("state") if isinstance(row, Mapping) else getattr(row, "state", None)
    return None if value is None else str(getattr(value, "value", value))


def _moment(row: Any) -> datetime | None:
    if row is None or isinstance(row, str):
        return None
    value = row.get("recordedAt") if isinstance(row, Mapping) else getattr(row, "recordedAt", None)
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value)
        except ValueError:
            return None
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def decide(recording: Any, subjects: Iterable[Any]) -> Verdict:
    """The four rules (module docstring), as a pure function: ALLOW or REFUSED, never AWAITING.

    ``recording`` is the recording's own current row and ``subjects`` one current row per subject;
    each is None (nothing recorded, which counts as agreeing), a state string, or a row with
    ``state`` and ``recordedAt``. A bare string carries no moment, so it can never be shown to be
    later than anything.
    """
    rows = list(subjects)
    own = _state(recording)
    if own == REFUSED:
        return REFUSED
    refusals = [row for row in rows if _state(row) == REFUSED]
    if own == GRANTED:
        granted_at = _moment(recording)
        if granted_at is not None and any(
            (refused_at := _moment(row)) is not None and refused_at > granted_at for row in refusals
        ):
            return REFUSED
        return ALLOW
    if refusals:
        return REFUSED
    return ALLOW


def state_for(verdict: str) -> str:
    """ALLOW / REFUSED / AWAITING as the consent words GRANTED / REFUSED / NOT_RECORDED."""
    return {ALLOW: GRANTED, REFUSED: REFUSED}.get(verdict, NOT_RECORDED)


# ── The database half: reads and the one write ───────────────────────────────────────────────


def _utc_text(moment: datetime) -> str:
    aware = moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)
    return aware.astimezone(UTC).replace(tzinfo=None).isoformat(timespec="milliseconds")


async def current_states(
    client: Any, subject_type: str, subject_ids: Iterable[str], *, purpose: str = EXTERNAL_PROCESSING
) -> dict[str, dict[str, Any]]:
    """The newest row per subject (``consent_current_v``), keyed by subject id."""
    ids = list(dict.fromkeys(str(i) for i in subject_ids if i))
    if not ids:
        return {}
    rows = await client.query_raw(
        f"SELECT {COLUMNS} FROM consent_current_v "
        'WHERE "subjectType" = $1::"ConsentSubjectType" AND "purpose" = $2::"ConsentPurpose" '
        'AND "subjectId" = ANY($3::text[])',
        subject_type,
        purpose,
        ids,
    )
    return {str(row["subjectId"]): dict(row) for row in rows}


async def history(
    client: Any, subject_type: str, subject_id: str, *, purpose: str = EXTERNAL_PROCESSING
) -> list[dict[str, Any]]:
    """Every row about one subject and purpose, oldest first."""
    rows = await client.query_raw(
        f'SELECT "id", {COLUMNS} FROM "Consent" '
        'WHERE "subjectType" = $1::"ConsentSubjectType" AND "purpose" = $2::"ConsentPurpose" '
        'AND "subjectId" = $3 ORDER BY "recordedAt" ASC, "createdAt" ASC',
        subject_type,
        purpose,
        subject_id,
    )
    return [dict(row) for row in rows]


async def append(
    client: Any,
    *,
    subject_type: str,
    subject_id: str,
    state: str,
    basis: str,
    actor_id: str,
    purpose: str = EXTERNAL_PROCESSING,
    source: str = SOURCE_RESEARCHER,
    note: str | None = None,
    evidence_media_id: str | None = None,
    recorded_at: datetime | None = None,
    version: int = 1,
    migrated_from: str | None = None,
) -> dict[str, Any] | None:
    """Append one row. Returns ``{"id", "recordedAt"}``, or None when ``migrated_from`` was copied before.

    The ledger's natural key holds ``recordedAt`` to the millisecond, so two answers inside one
    millisecond (a quick Yes after a No) take the next free one instead of being dropped.
    """
    if subject_type not in SUBJECT_TYPES:
        raise ValueError(f"unknown consent subject {subject_type!r}")
    if purpose not in PURPOSES:
        raise ValueError(f"unknown consent purpose {purpose!r}")
    if state not in DECISIONS:
        raise ValueError("a consent records GRANTED or REFUSED; NOT_RECORDED is the absence of one")
    if basis not in BASES:
        raise ValueError(f"unknown consent basis {basis!r}")
    if not str(subject_id or "").strip() or not str(actor_id or "").strip():
        raise ValueError("a consent names its subject and the person who recorded it")
    when = recorded_at or datetime.now(UTC)
    row_id = uuid4().hex
    for _ in range(5):
        written = await client.execute_raw(
            'INSERT INTO "Consent" ("id", "subjectType", "subjectId", "purpose", "state", "basis", '
            '"source", "version", "note", "evidenceMediaId", "actorId", "recordedAt", "migratedFrom") '
            'VALUES ($1, $2::"ConsentSubjectType", $3, $4::"ConsentPurpose", $5::"ConsentState", '
            '$6::"ConsentBasis", $7, $8, $9, $10, $11, $12::timestamp, $13) ON CONFLICT DO NOTHING',
            row_id,
            subject_type,
            subject_id,
            purpose,
            state,
            basis,
            source,
            version,
            (note or "").strip() or None,
            evidence_media_id,
            actor_id,
            _utc_text(when),
            migrated_from,
        )
        if written:
            return {"id": row_id, "recordedAt": when}
        if migrated_from is not None:
            copied = await client.query_raw(
                'SELECT 1 FROM "Consent" WHERE "migratedFrom" = $1', migrated_from
            )
            if copied:
                return None
        when = when + timedelta(milliseconds=1)
    raise RuntimeError("the consent ledger refused five moments in a row; nothing was recorded")
