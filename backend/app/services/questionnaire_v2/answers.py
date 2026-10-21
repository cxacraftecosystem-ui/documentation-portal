"""Canonical answers (DESIGN 3.6): one per (artisan, question lineage), every change a revision.

``PUT /answers`` is processed PER ITEM and never fails as a whole:

* no ``baseVersion`` means create only: an existing answer is a ``CONFLICT`` carrying its text;
* a stale ``baseVersion`` is a ``CONFLICT`` (P9);
* identical text is ``UNCHANGED``;
* a pinned answer edited below Professor is ``FORBIDDEN``;
* a PREFILL only ever overwrites an earlier PREFILL revision.

Proposals write drafts (``QuestionnaireTakeDraft``), never answers; a person accepts them here.
"""

from __future__ import annotations

import hashlib
import re
from datetime import timedelta
from typing import Any
from uuid import uuid4

from app.core.config import get_settings
from app.core.roles import has_rank
from app.services import consent_gate
from app.services.questionnaire_v2 import sessions
from app.services.questionnaire_v2.common import get

TX_TIMEOUT = timedelta(seconds=20)
TX_MAX_WAIT = timedelta(seconds=15)
DEFAULT_COUNTRY_CODE = "+91"


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


# ── phone (owner correction (b)) ───────────────────────────────────────────────────────────────
def split_phone(country_code: str | None, phone: str | None) -> tuple[str, str]:
    """(country code, national digits). The country code column is authoritative; until the
    value-standardisation stream lands it on this portal, a legacy ``+91...`` in ``phone`` is split."""
    digits = re.sub(r"\D", "", phone or "")
    code = (country_code or "").strip()
    if code and not code.startswith("+"):
        code = f"+{code}"
    if not code:
        code = DEFAULT_COUNTRY_CODE
        if (phone or "").strip().startswith("+") and digits.startswith("91") and len(digits) == 12:
            digits = digits[2:]
    elif digits.startswith(code.lstrip("+")) and len(digits) > 10 and code == "+91":
        digits = digits[len(code) - 1:]
    return code, digits


def format_phone(country_code: str | None, phone: str | None) -> str | None:
    """``+91 98765 43210``; other countries ``+<code> <digits>``; nothing for an empty phone."""
    code, digits = split_phone(country_code, phone)
    if not digits:
        return None
    if code == "+91" and len(digits) == 10:
        return f"+91 {digits[:5]} {digits[5:]}"
    return f"{code} {digits}"


def prefill_value(artisan: Any, field: str, *, session: dict[str, Any] | None = None, interviewer: str | None = None) -> str | None:
    """The text a question mapped to ``artisanField`` is prefilled with, or None."""
    if field == "phone":
        return format_phone(get(artisan, "phoneCountryCode"), get(artisan, "phone"))
    if field in ("sessionDate", "interviewDate"):
        day = (session or {}).get("sessionDate")
        return str(day)[:10] if day else None
    if field == "interviewer":
        return interviewer
    if field == "craft":
        craft = get(artisan, "craft")
        return get(craft, "name") if craft is not None and not isinstance(craft, str) else craft
    value = get(artisan, field)
    if value is None or value == "":
        return None
    return value.date().isoformat() if hasattr(value, "date") and callable(value.date) else str(value)


# ── reading ────────────────────────────────────────────────────────────────────────────────────
def answer_out(row: dict[str, Any] | None) -> dict[str, Any] | None:
    if not row:
        return None
    return {
        "id": str(row["id"]), "version": int(row["version"]), "text": row.get("text") or "",
        "origin": row.get("origin") or "TYPED", "attribution": row.get("attribution") or "INDIVIDUAL",
        "pinned": bool(row.get("pinned")), "sourceRecordingId": row.get("sourceRecordingId"),
    }


async def current_answer(client: Any, artisan_id: str, lineage_id: str) -> dict[str, Any] | None:
    rows = await client.query_raw(
        'SELECT a."id", a."version", a."pinned", a."questionnaireId", rv."text", rv."origin"::text AS origin, '
        'rv."attribution"::text AS attribution, rv."sourceRecordingId", rv."sessionId", rv."createdAt" AS "revisedAt", rv."actorId" '
        'FROM "QuestionnaireAnswer" a LEFT JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'WHERE a."artisanId" = $1 AND a."questionLineageId" = $2',
        artisan_id, lineage_id,
    )
    return rows[0] if rows else None


async def answers_for(client: Any, *, artisan_ids: list[str] | None = None, session_id: str | None = None,
                      questionnaire_id: str | None = None) -> list[dict[str, Any]]:
    where, params = [], []
    if artisan_ids is not None:
        params.append(artisan_ids)
        where.append(f'a."artisanId" = ANY(${len(params)}::text[])')
    if questionnaire_id:
        params.append(questionnaire_id)
        where.append(f'a."questionnaireId" = ${len(params)}')
    if session_id:
        params.append(session_id)
        where.append(f'EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" x WHERE x."answerId" = a."id" AND x."sessionId" = ${len(params)})')
    rows = await client.query_raw(
        'SELECT a."id", a."artisanId", a."questionLineageId", a."version", a."pinned", rv."text", rv."origin"::text AS origin, '
        'rv."attribution"::text AS attribution, rv."sourceRecordingId", rv."sessionId", rv."status"::text AS status, '
        'rv."sourceTranscriptHash", rv."askedQuestionId", rv."languageCode" '
        'FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        + ("WHERE " + " AND ".join(where) if where else "") + ' ORDER BY a."artisanId", a."questionLineageId"',
        *params,
    )
    return rows


# ── writing ────────────────────────────────────────────────────────────────────────────────────
async def _lineage(client: Any, lineage_id: str) -> dict[str, Any] | None:
    rows = await client.query_raw(
        'SELECT q."id", q."questionnaireId", q."artisanField" FROM "QuestionnaireQuestion" q WHERE q."lineageId" = $1 '
        'ORDER BY (q."retiredAt" IS NULL AND q."supersededById" IS NULL) DESC, q."createdAt" DESC LIMIT 1',
        lineage_id,
    )
    return rows[0] if rows else None


async def _transcript_hash(client: Any, recording_id: str | None) -> str | None:
    if not recording_id:
        return None
    rows = await client.query_raw(
        'SELECT m."transcriptText" FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" WHERE r."id" = $1',
        recording_id,
    )
    text = rows[0].get("transcriptText") if rows else None
    return hashlib.sha256(text.encode()).hexdigest() if text else None


_CURRENT_COLUMNS = (
    'a."id", a."version", a."pinned", a."questionnaireId", rv."text", rv."origin"::text AS origin, '
    'rv."attribution"::text AS attribution, rv."sourceRecordingId", rv."sessionId", rv."createdAt" AS "revisedAt", rv."actorId" '
)


async def _prefetch(items: list[Any], client: Any) -> dict[str, dict[Any, Any]]:
    """The read-only lookups every item of one ``PUT /answers`` makes, read for the whole batch at
    once: the live question of each lineage, each pair's current answer, the transcript each
    source recording carries, and the artisans a PREFILL reads. :func:`_write_one` falls back to its
    own read for anything not here, and forgets a pair's answer once it has written that pair, so a
    batch naming one pair twice still sees its own first write exactly as before."""
    pre: dict[str, dict[Any, Any]] = {"lineage": {}, "current": {}, "hash": {}, "artisan": {}}
    lineage_ids = sorted({str(i.questionLineageId) for i in items if i.questionLineageId})
    if not lineage_ids:
        return pre
    for row in await client.query_raw(
        'SELECT DISTINCT ON (q."lineageId") q."lineageId" AS "_lineageId", q."id", q."questionnaireId", q."artisanField" '
        'FROM "QuestionnaireQuestion" q WHERE q."lineageId" = ANY($1::text[]) '
        'ORDER BY q."lineageId", (q."retiredAt" IS NULL AND q."supersededById" IS NULL) DESC, q."createdAt" DESC',
        lineage_ids,
    ):
        key = str(row.pop("_lineageId"))
        pre["lineage"][key] = row
    for lineage_id in lineage_ids:
        pre["lineage"].setdefault(lineage_id, None)
    pairs = sorted({(str(i.artisanId), str(i.questionLineageId)) for i in items})
    for row in await client.query_raw(
        f'SELECT {_CURRENT_COLUMNS}, a."artisanId" AS "_artisanId", a."questionLineageId" AS "_lineageId" '
        'FROM "QuestionnaireAnswer" a LEFT JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'WHERE (a."artisanId", a."questionLineageId") IN (SELECT * FROM unnest($1::text[], $2::text[]))',
        [a for a, _ in pairs], [q for _, q in pairs],
    ):
        pre["current"][(str(row.pop("_artisanId")), str(row.pop("_lineageId")))] = row
    for pair in pairs:
        pre["current"].setdefault(pair, None)
    recording_ids = sorted({str(i.sourceRecordingId) for i in items if i.sourceRecordingId})
    if recording_ids:
        found = await client.query_raw(
            'SELECT r."id", m."transcriptText" FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
            'WHERE r."id" = ANY($1::text[])',
            recording_ids,
        )
        texts = {str(r["id"]): r.get("transcriptText") for r in found}
        for rid in recording_ids:
            text = texts.get(rid)
            pre["hash"][rid] = hashlib.sha256(text.encode()).hexdigest() if text else None
    prefill = sorted({str(i.artisanId) for i in items if i.origin == "PREFILL"})
    if prefill:
        for artisan in await client.artisan.find_many(where={"id": {"in": prefill}}, include={"craft": True}):
            pre["artisan"][artisan.id] = artisan
        for artisan_id in prefill:
            pre["artisan"].setdefault(artisan_id, None)
    return pre


def _settled_without_write(item: Any, user: Any, base: dict[str, Any], current: dict[str, Any] | None) -> dict[str, Any] | None:
    """The outcome decided by the answer as it stands, without writing (CONFLICT, a pinned answer's
    FORBIDDEN, UNCHANGED), or None when the item goes on to its versioned write."""
    if current is None:
        return None
    out_current = answer_out(current)
    if item.baseVersion is None or item.baseVersion != int(current["version"]):
        return {**base, "status": "CONFLICT", "current": out_current}
    if current.get("pinned") and not has_rank(user, "PROFESSOR"):
        return {**base, "status": "FORBIDDEN", "current": out_current}
    if item.origin == "PREFILL" and current.get("origin") != "PREFILL":
        return {**base, "status": "CONFLICT", "current": out_current}
    if (current.get("text") or "") == item.text and (current.get("origin") or "") == item.origin:
        return {**base, "status": "UNCHANGED", "answer": out_current, "current": out_current}
    return None


async def _write_one(
    item: Any, user: Any, client: Any, session_cache: dict[str, Any], pre: dict[str, dict[Any, Any]] | None = None
) -> dict[str, Any]:
    base = {"artisanId": item.artisanId, "questionLineageId": item.questionLineageId, "answer": None, "current": None}
    pre = pre or {"lineage": {}, "current": {}, "hash": {}, "artisan": {}}
    lineage_key = str(item.questionLineageId)
    if lineage_key in pre["lineage"]:
        question = pre["lineage"][lineage_key]
    else:
        question = await _lineage(client, item.questionLineageId)
    if question is None:
        return {**base, "status": "FORBIDDEN"}
    session = session_cache.get(item.sessionId)
    if session is None:
        session = await sessions.load_session_row(client, item.sessionId)
        if session is None:
            return {**base, "status": "FORBIDDEN"}
        session["_canWork"] = await sessions.can_work_in(client, user, session)
        session_cache[item.sessionId] = session
    if not session["_canWork"] or str(session["questionnaireId"]) != str(question["questionnaireId"]):
        return {**base, "status": "FORBIDDEN"}
    if "_people" not in session:
        session["_people"] = await session_people(client, str(session["id"]))
    if item.artisanId not in session["_people"]:
        # An answer is about somebody who was there: a participant, or a subject of one of its takes
        # (audit M15). Anyone else is refused, never silently written.
        return {**base, "status": "FORBIDDEN"}
    pair = (str(item.artisanId), lineage_key)
    snapshot = pair in pre["current"]
    if snapshot:
        current = pre["current"][pair]
    else:
        current = await current_answer(client, item.artisanId, item.questionLineageId)
    settled = _settled_without_write(item, user, base, current)
    if settled is not None and snapshot:
        # The batch read may be older than a save that landed since (another device, another tab).
        # An outcome that writes nothing is reported from a fresh read, never from that snapshot;
        # one that writes is checked by the versioned UPDATE / INSERT below, which re-reads on a miss.
        current = await current_answer(client, item.artisanId, item.questionLineageId)
        settled = _settled_without_write(item, user, base, current)
    if settled is not None:
        return settled
    field_value = None
    if item.origin == "PREFILL" and question.get("artisanField"):
        if str(item.artisanId) in pre["artisan"]:
            artisan = pre["artisan"][str(item.artisanId)]
        else:
            artisan = await client.artisan.find_unique(where={"id": item.artisanId}, include={"craft": True})
        field_value = prefill_value(artisan, str(question["artisanField"]), session=session) if artisan else None
    status = "PENDING"
    hash_known = bool(item.sourceRecordingId) and str(item.sourceRecordingId) in pre["hash"]
    # From here on this pair may change, so a later item naming it reads it afresh.
    pre["current"].pop(pair, None)
    async with client.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        if current is None:
            answer_id = uuid4().hex
            inserted = await tx.execute_raw(
                'INSERT INTO "QuestionnaireAnswer" ("id", "artisanId", "questionnaireId", "questionLineageId", "updatedAt") '
                'VALUES ($1, $2, $3, $4, now()) ON CONFLICT ("artisanId", "questionLineageId") DO NOTHING',
                answer_id, item.artisanId, str(question["questionnaireId"]), item.questionLineageId,
            )
            if not inserted:
                raced = await current_answer(tx, item.artisanId, item.questionLineageId)
                return {**base, "status": "CONFLICT", "current": answer_out(raced)}
            version = 1
        else:
            answer_id = str(current["id"])
            changed = await tx.execute_raw(
                'UPDATE "QuestionnaireAnswer" SET "version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1 AND "version" = $2',
                answer_id, int(current["version"]),
            )
            if not changed:
                raced = await current_answer(tx, item.artisanId, item.questionLineageId)
                return {**base, "status": "CONFLICT", "current": answer_out(raced)}
            version = int(current["version"]) + 1
        revision_id = uuid4().hex
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireAnswerRevision" ("id", "answerId", "text", "languageCode", "origin", "attribution", '
            '"askedQuestionId", "sessionId", "sourceRecordingId", "sourceTranscriptHash", "sourceFieldValue", "status", "actorId") '
            'VALUES ($1, $2, $3, $4, $5::"AnswerOrigin", $6::"AnswerAttribution", $7, $8, $9, $10, $11, $12::"RecordStatus", $13)',
            revision_id, answer_id, item.text, session.get("languageCode"), item.origin, item.attribution, str(question["id"]),
            item.sessionId, item.sourceRecordingId,
            pre["hash"][str(item.sourceRecordingId)] if hash_known else await _transcript_hash(tx, item.sourceRecordingId),
            field_value, status, user.id,
        )
        await tx.execute_raw('UPDATE "QuestionnaireAnswer" SET "currentRevisionId" = $2 WHERE "id" = $1', answer_id, revision_id)
        if session.get("status_t") == "APPROVED":
            # The revision is PENDING; the session stays APPROVED and goes back to review (audit M13).
            await sessions.note_pending_change(tx, str(session["id"]))
    saved = {
        "id": answer_id, "version": version, "text": item.text, "origin": item.origin, "attribution": item.attribution,
        "pinned": bool(current and current.get("pinned")), "sourceRecordingId": item.sourceRecordingId,
    }
    return {**base, "status": "SAVED", "answer": saved}


async def session_people(client: Any, session_id: str) -> set[str]:
    """The artisans an answer in this session may be about: its participants and its takes' subjects."""
    rows = await client.query_raw(
        'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1 UNION '
        'SELECT s."artisanId" FROM "QuestionnaireRecordingSubject" s JOIN "QuestionnaireRecording" r ON r."id" = s."recordingId" '
        'JOIN "MediaFile" m ON m."id" = r."mediaFileId" WHERE m."questionnaireInterviewId" = $1',
        session_id,
    )
    return {str(r["artisanId"]) for r in rows}


async def put_answers(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    cache: dict[str, Any] = {}
    try:
        pre = await _prefetch(list(payload.items), db)
    except Exception:  # noqa: BLE001 - each item then reads for itself, exactly as before
        import logging

        logging.getLogger(__name__).warning("answer prefetch failed; reading per item", exc_info=True)
        pre = None
    results = []
    for item in payload.items:
        try:
            results.append(await _write_one(item, user, db, cache, pre))
        except Exception:  # noqa: BLE001 - one item never fails the batch
            import logging

            logging.getLogger(__name__).exception("answer item failed")
            # Tried once more (a dropped connection is the usual cause); if it fails again the item
            # is a CONFLICT that CARRIES the stored answer, so the client always has something to
            # show and to resolve against, never an empty conflict (audit Low).
            try:
                results.append(await _write_one(item, user, db, {}))
                continue
            except Exception:  # noqa: BLE001
                logging.getLogger(__name__).exception("answer item failed twice")
            stored = None
            try:
                stored = answer_out(await current_answer(db, item.artisanId, item.questionLineageId))
            except Exception:  # noqa: BLE001
                stored = None
            results.append({"artisanId": item.artisanId, "questionLineageId": item.questionLineageId,
                            "status": "CONFLICT", "answer": None, "current": stored})
    return {"results": results}


# ── proposals ──────────────────────────────────────────────────────────────────────────────────
async def proposals(recording_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    """Drafts from one take's COMPLETED transcript; never answers, never a new transcription."""
    from app.services import answer_drafts
    from app.services.questionnaire_v2 import recordings

    db = _db(client)
    row = await recordings.require_recording(db, recording_id)
    # Proposals can call an outside model: the same people who may change the take (audit M15).
    await recordings.assert_can_change(db, user, row, reviewers=True)
    rec = (await recordings.serialize_recordings(db, [row], user))[0]
    text = rec["transcript"]["text"]
    if not text:
        return {"proposals": []}
    answered = [s for s in rec["subjects"] if s["role"] == "ANSWERED"]
    if len(answered) == 1:
        owners: list[str] | None = [answered[0]["artisanId"]]
    elif answered and all(s["named"] for s in answered):
        owners = [s["artisanId"] for s in answered]
    else:
        owners = None  # "Whose answer is this?" before Accept
    items: list[tuple[str, str, str]] = []  # (lineage, text, origin)
    verdict: str | None = None  # one recording, so one consent read however many sections it covers
    for t in rec["targets"]:
        if t["questionLineageId"]:
            items.append((t["questionLineageId"], text, "TRANSCRIPT"))
        elif t["sectionId"]:
            settings = get_settings()
            # Drafting is an outside service: the consent gate and the off-by-default switch both apply.
            if not getattr(settings, "ai_answer_drafts_enabled", False):
                continue
            if verdict is None:
                verdict = await consent_gate.verdict(rec["mediaFileId"], client=db)
            if verdict != consent_gate.ALLOW:
                continue
            qs = await db.query_raw(
                'SELECT "lineageId", "prompt" FROM "QuestionnaireQuestion" WHERE "sectionId" = $1 AND "isActive" '
                'AND "retiredAt" IS NULL AND "supersededById" IS NULL ORDER BY "sortOrder"',
                t["sectionId"],
            )
            numbered = [(i + 1, q["prompt"]) for i, q in enumerate(qs)]
            try:
                drafted, _provider, _model = await answer_drafts.draft_answers(numbered, text, settings, user_id=user.id)
            except answer_drafts.DraftUnavailable:
                continue
            for i, q in enumerate(qs):
                if drafted.get(i + 1):
                    items.append((str(q["lineageId"]), drafted[i + 1], "MACHINE_SUGGESTED"))
    out = []
    readers = list(owners or [s["artisanId"] for s in answered])
    existing_by: dict[tuple[str, str], dict[str, Any]] = {}
    if items and readers:
        for row in await db.query_raw(
            f'SELECT {_CURRENT_COLUMNS}, a."artisanId" AS "_artisanId", a."questionLineageId" AS "_lineageId" '
            'FROM "QuestionnaireAnswer" a LEFT JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
            'WHERE a."artisanId" = ANY($1::text[]) AND a."questionLineageId" = ANY($2::text[])',
            readers, sorted({lineage for lineage, _t, _o in items}),
        ):
            existing_by[(str(row.pop("_artisanId")), str(row.pop("_lineageId")))] = row
    # Every draft row in ONE upsert. A (lineage, artisan) named twice keeps its last text, as the
    # one-row-at-a-time upserts did (and as one statement must: it may not touch a row twice).
    drafts: dict[tuple[str, str], tuple[str, str]] = {}
    for lineage_id, draft, origin in items:
        for artisan_key in (owners or [""]):
            drafts[(str(lineage_id), str(artisan_key))] = (draft, origin)
    if drafts:
        keys = list(drafts)
        await db.execute_raw(
            'INSERT INTO "QuestionnaireTakeDraft" ("id", "recordingId", "questionLineageId", "artisanId", "text", "origin") '
            'SELECT x.id, $1, x.lineage, x.artisan, x.body, x.origin::"AnswerOrigin" '
            'FROM unnest($2::text[], $3::text[], $4::text[], $5::text[], $6::text[]) AS x(id, lineage, artisan, body, origin) '
            'ON CONFLICT ("recordingId", "questionLineageId", "artisanId") '
            'DO UPDATE SET "text" = EXCLUDED."text", "origin" = EXCLUDED."origin"',
            recording_id, [uuid4().hex for _ in keys], [k[0] for k in keys], [k[1] for k in keys],
            [drafts[k][0] for k in keys], [drafts[k][1] for k in keys],
        )
    for lineage_id, draft, origin in items:
        existing = []
        for artisan_id in readers:
            cur = existing_by.get((str(artisan_id), str(lineage_id)))
            if cur:
                existing.append({"artisanId": artisan_id, **(answer_out(cur) or {})})
        out.append({"questionLineageId": lineage_id, "artisanIds": owners, "text": draft, "origin": origin, "existing": existing})
    return {"proposals": out}
