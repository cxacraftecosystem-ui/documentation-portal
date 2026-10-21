"""Coverage, computed on read from ``questionnaire_take_v`` (DESIGN 3.1-3.3, 3.5 and 5.2).

THE HIERARCHY RULE, used by the check, the grid, the backfill and the tests alike: a target is
already covered for an artisan when the target itself, OR ITS PARENT SECTION, has a take for them.
A question take after a section take therefore prompts; a section take after question takes does
not (it shows "D3, D4 also have question takes."). Only ANSWERED subjects count; a general
("Whole session") recording counts for nothing; a NEEDS_DECISION take counts for the prompt but
NEVER for "covered" (DESIGN 3.1; audit M2), and only live questions count toward a section's total.

TYPED OR RECORDED, EITHER COUNTS; RECORDING PREFERRED (owner, amendment 2 of 22 Oct 2026, point 4).
A question is covered for an artisan by a take (ANSWERED) OR by a typed answer with text (their own
or a group answer; a soft-deleted session's answers do not count). Every grid cell says where its
coverage comes from: ``source`` is ``RECORDED`` when any take covers it (shown first: a recording is
preferred), ``TYPED`` when only typed answers do (a lighter mark, and ``addRecording`` asks the
gentle "Add a recording?"), else None. "14 of 24 sections" counts both. The "Already recorded"
prompt (``check``) is about RECORDING, so a typed answer is NOT prior coverage there: recording over a
typed answer asks nothing; each per-artisan entry carries ``typed`` so a client can say it is there.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Any

from app import shared_vocab
from app.questionnaire import ports
from app.questionnaire.common import aware, as_date, fail, get, iso, now

ESTIMATED = set(shared_vocab.ESTIMATED_CAPTURED_AT_SOURCES)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    db = ports.db()

    return db


def section_key(section_id: str) -> str:
    return f"S:{section_id}"


def question_key(lineage_id: str) -> str:
    return f"Q:{lineage_id}"


async def resolve_targets(client: Any, questionnaire_id: str, targets: list[Any]) -> list[dict[str, Any]]:
    """Each requested target as ``{targetKey, sectionId, questionLineageId, askedQuestionId,
    displayCode, label, parentSectionId, sectionCode}``. A target outside the instrument -> 422."""
    out = []
    for t in targets:
        section_id = get(t, "sectionId")
        lineage_id = get(t, "questionLineageId")
        if section_id:
            rows = await client.query_raw(
                'SELECT "id", "code", "title" FROM "QuestionnaireSection" WHERE "id" = $1 AND "questionnaireId" = $2',
                section_id, questionnaire_id,
            )
            if not rows:
                fail("TARGET_OUTSIDE_INSTRUMENT")
            s = rows[0]
            out.append({
                "targetKey": section_key(section_id), "sectionId": section_id, "questionLineageId": None,
                "askedQuestionId": None, "displayCode": None, "label": f"Section {s['code']}",
                "parentSectionId": section_id, "sectionCode": s["code"],
            })
        elif lineage_id:
            rows = await client.query_raw(
                'SELECT q."id", q."lineageId", q."displayCode", q."prompt", q."sectionId", q."sectionCode", q."retiredAt", '
                'q."supersededById" FROM "QuestionnaireQuestion" q WHERE q."lineageId" = $1 AND q."questionnaireId" = $2 '
                'ORDER BY (q."retiredAt" IS NULL AND q."supersededById" IS NULL) DESC, q."createdAt" DESC',
                lineage_id, questionnaire_id,
            )
            if not rows:
                fail("TARGET_OUTSIDE_INSTRUMENT")
            q = rows[0]  # the live wording of the lineage: what is asked now
            out.append({
                "targetKey": question_key(lineage_id), "sectionId": None, "questionLineageId": lineage_id,
                "askedQuestionId": str(q["id"]), "displayCode": q.get("displayCode"),
                "label": f"{q.get('displayCode') or ''} {q.get('prompt') or ''}".strip(),
                "parentSectionId": q.get("sectionId"), "sectionCode": q.get("sectionCode"),
            })
        else:
            fail("VALIDATION_FAILED", "A target names a sectionId or a questionLineageId.")
    return out


async def take_rows(
    client: Any, questionnaire_id: str, *, artisan_ids: list[str] | None = None, session_id: str | None = None,
    workshop_id: str | None = None, target_keys: list[str] | None = None,
) -> list[dict[str, Any]]:
    where = ['tv."questionnaireId" = $1']
    params: list[Any] = [questionnaire_id]
    if artisan_ids is not None:
        params.append(artisan_ids)
        where.append(f'tv."artisanId" = ANY(${len(params)}::text[])')
    if session_id:
        params.append(session_id)
        where.append(f'tv."sessionId" = ${len(params)}')
    if workshop_id:
        params.append(workshop_id)
        where.append(f'tv."workshopId" = ${len(params)}')
    if target_keys is not None:
        params.append(target_keys)
        where.append(f'tv."targetKey" = ANY(${len(params)}::text[])')
    return await client.query_raw(
        'SELECT tv."recordingId", tv."rootRecordingId", tv."sessionId", tv."artisanId", tv."targetKey", tv."sectionId", '
        'tv."questionLineageId", tv."askedQuestionId", tv."capturedAt", tv."capturedAtSource"::text AS source, '
        'tv."filing"::text AS filing, tv."continuationOfId", tv."rosterCount", tv."recordedById", tv."durationMs", '
        'tv."changeSeq", tv."repeatReason"::text AS reason, tv."flag"::text AS flag, tv."named", tv."position", '
        'u."name" AS "recordedByName", i."sessionDate", i."purpose"::text AS purpose '
        'FROM questionnaire_take_v tv JOIN "User" u ON u."id" = tv."recordedById" '
        'JOIN "QuestionnaireInterview" i ON i."id" = tv."sessionId" WHERE ' + " AND ".join(where)
        + ' ORDER BY tv."capturedAt", tv."recordingId"',
        *params,
    )


def take_payload(row: dict[str, Any], *, session_id: str | None, user_id: str | None) -> dict[str, Any]:
    return {
        "recordingId": str(row["recordingId"]),
        "position": int(row["position"]) if row.get("position") is not None else None,
        "capturedAt": iso(row["capturedAt"]),
        "estimated": row.get("source") in ESTIMATED,
        "durationMs": row.get("durationMs"),
        "recordedBy": {"id": str(row["recordedById"]), "name": row.get("recordedByName") or ""},
        "session": {
            "id": str(row["sessionId"]),
            "date": (as_date(row.get("sessionDate")).isoformat() if row.get("sessionDate") else None),
            "rosterCount": int(row.get("rosterCount") or 0),
        },
        "sameSession": bool(session_id) and str(row["sessionId"]) == session_id,
        "mine": bool(user_id) and str(row["recordedById"]) == user_id,
        "repeatReason": row.get("reason"),
    }


def suggest_reason(prior: list[dict[str, Any]], *, purpose: str | None, roster: int, user_id: str | None) -> str | None:
    """DESIGN 3.3 rule 2: the session purpose first; otherwise a different setting -> NEW_SETTING,
    a different researcher -> RE_INTERVIEW, the same researcher -> REDO."""
    if not prior:
        return None
    if purpose == "FOLLOW_UP":
        return "FOLLOW_UP"
    if purpose == "REDO":
        return "REDO"
    latest = max(prior, key=lambda r: (aware(r["capturedAt"]), str(r["recordingId"])))
    if (int(latest.get("rosterCount") or 0) == 1) != (roster == 1):
        return "NEW_SETTING"
    if user_id and str(latest["recordedById"]) != user_id:
        return "RE_INTERVIEW"
    return "REDO"


#: Where a cell's coverage comes from (``shared/questionnaire-enums.json`` CoverageSource).
SOURCE_RECORDED = "RECORDED"
SOURCE_TYPED = "TYPED"


async def typed_lineages(client: Any, questionnaire_id: str, artisan_ids: list[str]) -> dict[str, set[str]]:
    """artisanId -> the question lineages they have a typed answer WITH TEXT for, in live sessions."""
    if not artisan_ids:
        return {}
    rows = await client.query_raw(
        'SELECT a."artisanId", a."questionLineageId" FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" si ON si."id" = rv."sessionId" AND si."deletedAt" IS NULL '
        'WHERE a."questionnaireId" = $1 AND a."artisanId" = ANY($2::text[]) AND btrim(rv."text") <> \'\'',
        questionnaire_id, artisan_ids,
    )
    out: dict[str, set[str]] = defaultdict(set)
    for r in rows:
        if r.get("artisanId"):
            out[str(r["artisanId"])].add(str(r["questionLineageId"]))
    return out


async def check(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``POST /coverage/check``: asked once, before recording or upload (P2, P5)."""
    db = _db(client)
    targets = await resolve_targets(db, payload.questionnaireId, payload.targets)
    identical = None
    if payload.contentSha256:
        # Only a hash the SERVER verified (audit M9): a wrong client hash must never make P5 hide a
        # distinct take, which would then upload nothing.
        rows = await db.query_raw(
            'SELECT r."id", m."questionnaireInterviewId" AS sid FROM "MediaFile" m JOIN "QuestionnaireRecording" r ON r."mediaFileId" = m."id" '
            'WHERE m."contentSha256" = $1 AND m."shaVerifiedAt" IS NOT NULL AND m."removedAt" IS NULL AND r."removedAt" IS NULL '
            'ORDER BY m."createdAt" LIMIT 1',
            payload.contentSha256.lower(),
        )
        if rows:
            identical = {"recordingId": str(rows[0]["id"]), "sessionId": rows[0].get("sid"),
                         "sameSession": bool(payload.sessionId) and rows[0].get("sid") == payload.sessionId}
    purpose = None
    if payload.sessionId:
        srows = await db.query_raw('SELECT "purpose"::text AS p FROM "QuestionnaireInterview" WHERE "id" = $1', payload.sessionId)
        purpose = srows[0]["p"] if srows else None
    keys = {t["targetKey"] for t in targets} | {section_key(t["parentSectionId"]) for t in targets if t["parentSectionId"]}
    rows = await take_rows(db, payload.questionnaireId, artisan_ids=payload.artisanIds, target_keys=sorted(keys))
    by = defaultdict(list)
    for r in rows:
        by[(str(r["artisanId"]), str(r["targetKey"]))].append(r)
    question_takes_in_section: dict[tuple[str, str], int] = {}
    section_ids = [t["sectionId"] for t in targets if t["sectionId"]]
    if section_ids:
        qrows = await db.query_raw(
            'SELECT tv."artisanId", q."sectionId", count(*) AS n FROM questionnaire_take_v tv '
            'JOIN "QuestionnaireQuestion" q ON q."id" = tv."questionLineageId" '
            'WHERE tv."questionnaireId" = $1 AND tv."artisanId" = ANY($2::text[]) AND q."sectionId" = ANY($3::text[]) GROUP BY 1, 2',
            payload.questionnaireId, payload.artisanIds, section_ids,
        )
        question_takes_in_section = {(str(r["artisanId"]), str(r["sectionId"])): int(r["n"]) for r in qrows}
    typed = await typed_lineages(db, payload.questionnaireId, list(payload.artisanIds))
    out_targets = []
    covered_prior: list[dict[str, Any]] = []
    for t in targets:
        per = []
        for artisan_id in payload.artisanIds:
            direct = by.get((artisan_id, t["targetKey"]), [])
            via = [] if direct or not t["questionLineageId"] or not t["parentSectionId"] else by.get((artisan_id, section_key(t["parentSectionId"])), [])
            shown = direct or via
            if shown:
                state = "NEEDS_DECISION" if any(r.get("filing") == "NEEDS_DECISION" for r in shown) else ("RECORDED" if direct else "VIA_SECTION")
                covered_prior.extend(shown)
            elif t["sectionId"] and question_takes_in_section.get((artisan_id, t["sectionId"])):
                state = "PARTIAL"
            else:
                state = "NOT_RECORDED"
            per.append({
                "artisanId": artisan_id, "state": state, "viaSection": bool(via and not direct),
                "takes": [take_payload(r, session_id=payload.sessionId, user_id=user.id) for r in shown],
                # A typed answer is there; it never makes this prompt fire (recording over it is encouraged).
                "typed": bool(t["questionLineageId"] and str(t["questionLineageId"]) in typed.get(artisan_id, set())),
            })
        out_targets.append({"targetKey": t["targetKey"], "label": t["label"], "perArtisan": per})
    return {
        "asOf": iso(now()),
        "identical": identical,
        "targets": out_targets,
        "suggestedReason": suggest_reason(covered_prior, purpose=purpose, roster=len(payload.artisanIds), user_id=user.id),
    }


async def _sections(client: Any, questionnaire_id: str) -> list[dict[str, Any]]:
    return await client.query_raw(
        'SELECT s."id", s."code", s."title", s."sortOrder", '
        '(SELECT count(*) FROM "QuestionnaireQuestion" q WHERE q."sectionId" = s."id" AND q."isActive" '
        '  AND q."retiredAt" IS NULL AND q."supersededById" IS NULL) AS total '
        'FROM "QuestionnaireSection" s WHERE s."questionnaireId" = $1 AND s."isActive" ORDER BY s."sortOrder"',
        questionnaire_id,
    )


async def _live_lineages(client: Any, questionnaire_id: str) -> dict[str, set[str]]:
    """sectionId -> the lineages asked now (active, not retired, not superseded)."""
    rows = await client.query_raw(
        'SELECT q."sectionId", COALESCE(q."lineageId", q."id") AS lineage FROM "QuestionnaireQuestion" q '
        'WHERE q."questionnaireId" = $1 AND q."isActive" AND q."retiredAt" IS NULL AND q."supersededById" IS NULL',
        questionnaire_id,
    )
    out: dict[str, set[str]] = defaultdict(set)
    for r in rows:
        if r.get("sectionId"):
            out[str(r["sectionId"])].add(str(r["lineage"]))
    return out


async def grid(questionnaire_id: str, *, artisan_ids: list[str] | None = None, workshop_id: str | None = None, client: Any = None) -> dict[str, Any]:
    """``GET /coverage``: artisan x section, with partial counts and the admin overrides."""
    db = _db(client)
    if artisan_ids is None:
        if not workshop_id:
            fail("VALIDATION_FAILED", "Give artisanIds or a workshopId.")
        rows = await db.query_raw(
            'SELECT DISTINCT qa."artisanId" FROM "QuestionnaireInterviewArtisan" qa JOIN "QuestionnaireInterview" i ON i."id" = qa."interviewId" '
            'WHERE i."workshopId" = $1 AND i."questionnaireId" = $2 UNION SELECT "artisanId" FROM "WorkshopArtisan" WHERE "workshopId" = $1',
            workshop_id, questionnaire_id,
        )
        artisan_ids = sorted(str(r["artisanId"]) for r in rows)
    names = await db.query_raw('SELECT "id", "name" FROM "Artisan" WHERE "id" = ANY($1::text[]) ORDER BY "name"', artisan_ids)
    order = [str(n["id"]) for n in names]
    sections = await _sections(db, questionnaire_id)
    takes = await take_rows(db, questionnaire_id, artisan_ids=order)
    lineage_section = {}
    if takes:
        lrows = await db.query_raw(
            'SELECT "id", "sectionId" FROM "QuestionnaireQuestion" WHERE "id" = ANY($1::text[])',
            list({str(t["questionLineageId"]) for t in takes if t.get("questionLineageId")}),
        )
        lineage_section = {str(r["id"]): r.get("sectionId") for r in lrows}
    overrides = await db.query_raw(
        'SELECT "artisanId", "sectionId", "status"::text AS status FROM "QuestionnaireSectionStatus" WHERE "artisanId" = ANY($1::text[])',
        order,
    )
    override_by = {(str(o["artisanId"]), str(o["sectionId"])): o["status"] for o in overrides}
    live = await _live_lineages(db, questionnaire_id)
    typed = await typed_lineages(db, questionnaire_id, order)
    lineage_home = {lineage: sid for sid, lineages in live.items() for lineage in lineages}
    section_takes: dict[tuple[str, str], list] = defaultdict(list)
    question_lineages: dict[tuple[str, str], set] = defaultdict(set)
    question_takes: dict[tuple[str, str], set] = defaultdict(set)
    nd: dict[tuple[str, str], bool] = defaultdict(bool)
    for t in takes:
        a = str(t["artisanId"])
        if t.get("sectionId"):
            sec = str(t["sectionId"])
        else:
            found = lineage_section.get(str(t.get("questionLineageId")))
            if not found:
                continue
            sec = str(found)
        if t.get("filing") == "NEEDS_DECISION":
            # Waiting for a decision: shown, never counted as covered (DESIGN 3.1; audit M2).
            nd[(a, sec)] = True
            continue
        if t.get("sectionId"):
            section_takes[(a, sec)].append(t)
        else:
            question_takes[(a, sec)].add(str(t["recordingId"]))
            question_lineages[(a, sec)].add(str(t["questionLineageId"]))
    out_sections = []
    for s in sections:
        sid = str(s["id"])
        total = int(s["total"] or 0)
        cells = []
        for a in order:
            st = section_takes.get((a, sid), [])
            # Only questions asked NOW count toward "covered", so a retired question can never push
            # Partial to 100% (audit M2). A typed answer with text covers its question too (owner,
            # amendment 2 point 4); a section take covers the section whatever was typed.
            asked = live.get(sid, set())
            recorded_q = question_lineages.get((a, sid), set()) & asked
            typed_q = {x for x in typed.get(a, set()) if lineage_home.get(x) == sid} & asked
            covered = len(recorded_q | typed_q)
            if st:
                state = "RECORDED"
            elif covered:
                state = "PARTIAL"
            elif nd.get((a, sid)):
                state = "NEEDS_DECISION"
            else:
                state = "NOT_RECORDED"
            distinct_takes = len({str(t["recordingId"]) for t in st} | question_takes.get((a, sid), set()))
            source = SOURCE_RECORDED if (st or recorded_q) else (SOURCE_TYPED if typed_q else None)
            cells.append({
                "artisanId": a, "state": state, "takes": distinct_takes, "covered": covered, "total": total,
                "needsDecision": bool(nd.get((a, sid))), "override": override_by.get((a, sid)),
                "source": source, "recordedCount": len(recorded_q), "typedOnlyCount": len(typed_q - recorded_q),
                "addRecording": source == SOURCE_TYPED,
            })
        out_sections.append({"sectionId": sid, "code": s["code"], "title": s["title"], "cells": cells})
    return {
        "questionnaireId": questionnaire_id,
        "artisans": [{"artisanId": str(n["id"]), "name": n["name"]} for n in names],
        "sections": out_sections,
    }


def section_covered(cell: dict[str, Any]) -> bool:
    """"14 of 24 sections": Recorded, or Partial at 100% of the active questions. A take waiting
    for a decision covers nothing until it is decided (audit M2)."""
    return cell["state"] == "RECORDED" or (cell["total"] > 0 and cell["covered"] >= cell["total"])


#: The highest changeSeq that NO transaction still running can undercut (audit M3). A sequence value
#: is taken when a row is written but becomes visible only on commit, so a slow transaction can
#: commit a LOWER changeSeq after a reader saw a higher one. Rows written by transactions at or after
#: the oldest one still running (the snapshot's xmin) do not advance the horizon; they are still
#: returned, and returned again next time, which a client absorbs (it replaces per recording id).
HORIZON_SQL = (
    'SELECT COALESCE(max("changeSeq"), 0) AS s FROM "QuestionnaireRecording" '
    'WHERE ("xmin"::text)::bigint < (pg_snapshot_xmin(pg_current_snapshot())::text::bigint % 4294967296)'
)


async def snapshot(questionnaire_id: str, workshop_id: str | None, since_seq: int | None, *, client: Any = None) -> dict[str, Any]:
    """Takes changed after ``since_seq`` (every take of each changed recording, so a client replaces
    its takes per recordingId), the recordings removed since, and every recording that changed at
    all (``changedRecordingIds``: one whose takes all went, e.g. a subject turned PRESENT, is in
    neither of the other lists). Scoped to one workshop. ``asOfSeq`` is read FIRST and bounded by
    the oldest running transaction, so a late commit is never skipped (audit M3)."""
    db = _db(client)
    since = int(since_seq or 0)
    horizon = await db.query_raw(HORIZON_SQL)
    as_of = int(horizon[0]["s"]) if horizon else 0
    params: list[Any] = [questionnaire_id, since]
    scope = ""
    if workshop_id:
        params.append(workshop_id)
        scope = f' AND tv."workshopId" = ${len(params)}'
    rows = await db.query_raw(
        'SELECT tv."recordingId", tv."sessionId", tv."artisanId", tv."targetKey", tv."capturedAt", '
        'tv."capturedAtSource"::text AS source, tv."filing"::text AS filing, tv."position" '
        'FROM questionnaire_take_v tv WHERE tv."questionnaireId" = $1 AND tv."recordingId" IN '
        '(SELECT "id" FROM "QuestionnaireRecording" WHERE "changeSeq" > $2)' + scope + ' ORDER BY tv."capturedAt"',
        *params,
    )
    removed_params: list[Any] = [questionnaire_id, since]
    removed_scope = ""
    if workshop_id:
        removed_params.append(workshop_id)
        removed_scope = ' AND i."workshopId" = $3'
    removed = await db.query_raw(
        'SELECT r."id" FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        'WHERE i."questionnaireId" = $1 AND r."changeSeq" > $2 AND (r."removedAt" IS NOT NULL OR m."removedAt" IS NOT NULL)' + removed_scope,
        *removed_params,
    )
    changed = await db.query_raw(
        'SELECT r."id" FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        'WHERE i."questionnaireId" = $1 AND r."changeSeq" > $2' + removed_scope,
        *removed_params,
    )
    return {
        "asOfSeq": str(max(as_of, since)),
        "changedRecordingIds": sorted(str(r["id"]) for r in changed),
        "takes": [
            {
                "recordingId": str(r["recordingId"]), "sessionId": r.get("sessionId"), "artisanId": str(r["artisanId"]),
                "targetKey": r["targetKey"], "role": "ANSWERED", "capturedAt": iso(r["capturedAt"]),
                "estimated": r.get("source") in ESTIMATED, "filing": r.get("filing"),
                "position": int(r["position"]) if r.get("position") is not None else None,
            }
            for r in rows
        ],
        "removedRecordingIds": [str(r["id"]) for r in removed],
    }


async def first_gaps(client: Any, questionnaire_id: str, artisan_ids: list[str]) -> dict[str, Any]:
    """Each artisan's first section (instrument order) with no take at all."""
    g = await grid(questionnaire_id, artisan_ids=artisan_ids, client=client)
    gaps: dict[str, Any] = {a: None for a in artisan_ids}
    for section in g["sections"]:
        for cell in section["cells"]:
            if gaps.get(cell["artisanId"]) is None and cell["state"] == "NOT_RECORDED" and cell["artisanId"] in gaps:
                gaps[cell["artisanId"]] = {"sectionId": section["sectionId"], "code": section["code"], "title": section["title"]}
    return gaps


# ── the primary take (DESIGN 3.5) ──────────────────────────────────────────────────────────────
def choose_primary(
    takes: list[dict[str, Any]], *, answer_source: str | None, explicit: str | None, senior_redoers: set[str] | None = None,
) -> str | None:
    """The primary take of one (artisan, target), by the five rules in order. ``takes`` are view rows
    (``recordingId``, ``capturedAt``, ``rosterCount``, ``reason``, ``recordedById``)."""
    if not takes:
        return None
    live = {str(t["recordingId"]) for t in takes}
    if answer_source and answer_source in live:
        return answer_source
    if explicit and explicit in live:
        return explicit
    ordered = sorted(takes, key=lambda t: (aware(t["capturedAt"]), str(t["recordingId"])))
    senior = senior_redoers or set()
    redos = []
    for i, t in enumerate(ordered):
        if t.get("reason") != "REDO" or i == 0:
            continue
        redone = ordered[i - 1]
        if str(t["recordedById"]) == str(redone["recordedById"]) or str(t["recordedById"]) in senior:
            redos.append(t)
    if redos:
        return str(redos[-1]["recordingId"])
    solo = [t for t in ordered if int(t.get("rosterCount") or 0) == 1]
    if solo:
        return str(solo[0]["recordingId"])
    return str(ordered[0]["recordingId"])


async def primaries_many(client: Any, rows_by_artisan: dict[str, list[dict[str, Any]]]) -> dict[str, dict[str, str | None]]:
    """:func:`primaries` for many artisans at once, keyed by artisan id, in three reads in all.

    Each artisan sees exactly what its own call would: its answers, the MAKE_PRIMARY events on ITS
    rows' recordings that name it, and the senior roles of ITS rows' recorders.
    """
    targets: dict[str, dict[str, list]] = {}
    for artisan_id, rows in rows_by_artisan.items():
        by_target: dict[str, list] = defaultdict(list)
        for r in rows:
            if r.get("continuationOfId"):
                continue
            by_target[str(r["targetKey"])].append(r)
        if by_target:
            targets[artisan_id] = by_target
    out: dict[str, dict[str, str | None]] = {artisan_id: {} for artisan_id in rows_by_artisan}
    if not targets:
        return out
    wanted = list(targets)
    answers = await client.query_raw(
        'SELECT a."artisanId", a."questionLineageId", rv."sourceRecordingId" FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" WHERE a."artisanId" = ANY($1::text[])',
        wanted,
    )
    source: dict[str, dict[str, Any]] = defaultdict(dict)
    for a in answers:
        source[str(a["artisanId"])][question_key(str(a["questionLineageId"]))] = a.get("sourceRecordingId")
    recordings_of = {artisan_id: {str(r["recordingId"]) for r in rows_by_artisan[artisan_id]} for artisan_id in wanted}
    events = await client.query_raw(
        'SELECT e."recordingId", e."after" FROM "QuestionnaireRecordingEvent" e WHERE e."kind" = \'MAKE_PRIMARY\' '
        'AND e."recordingId" = ANY($1::text[]) ORDER BY e."at"',
        list(set().union(*recordings_of.values())),
    )
    explicit: dict[str, dict[str, str]] = defaultdict(dict)
    for e in events:
        after = e.get("after") or {}
        if isinstance(after, str):
            import json

            after = json.loads(after)
        artisan_id = str(after.get("artisanId"))
        if artisan_id in targets and after.get("targetKey") and str(e["recordingId"]) in recordings_of[artisan_id]:
            explicit[artisan_id][str(after["targetKey"])] = str(e["recordingId"])
    seniors = await client.query_raw(
        "SELECT \"id\" FROM \"User\" WHERE \"role\" IN ('PROFESSOR', 'ADMIN', 'MASTER_ADMIN') AND \"id\" = ANY($1::text[])",
        list({str(r["recordedById"]) for artisan_id in wanted for r in rows_by_artisan[artisan_id]}),
    )
    senior_ids = {str(s["id"]) for s in seniors}
    for artisan_id, by_target in targets.items():
        mine = {str(r["recordedById"]) for r in rows_by_artisan[artisan_id]}
        out[artisan_id] = {
            key: choose_primary(
                takes, answer_source=source[artisan_id].get(key), explicit=explicit[artisan_id].get(key),
                senior_redoers=senior_ids & mine,
            )
            for key, takes in by_target.items()
        }
    return out


async def primaries(client: Any, artisan_id: str, rows: list[dict[str, Any]]) -> dict[str, str | None]:
    """targetKey -> primary recordingId for one artisan, over view rows of that artisan."""
    by_target: dict[str, list] = defaultdict(list)
    for r in rows:
        if r.get("continuationOfId"):
            continue
        by_target[str(r["targetKey"])].append(r)
    if not by_target:
        return {}
    answers = await client.query_raw(
        'SELECT a."questionLineageId", rv."sourceRecordingId" FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" WHERE a."artisanId" = $1',
        artisan_id,
    )
    source = {question_key(str(a["questionLineageId"])): a.get("sourceRecordingId") for a in answers}
    events = await client.query_raw(
        'SELECT e."recordingId", e."after" FROM "QuestionnaireRecordingEvent" e WHERE e."kind" = \'MAKE_PRIMARY\' '
        'AND e."recordingId" = ANY($1::text[]) ORDER BY e."at"',
        list({str(r["recordingId"]) for r in rows}),
    )
    explicit: dict[str, str] = {}
    for e in events:
        after = e.get("after") or {}
        if isinstance(after, str):
            import json

            after = json.loads(after)
        if str(after.get("artisanId")) == artisan_id and after.get("targetKey"):
            explicit[str(after["targetKey"])] = str(e["recordingId"])
    seniors = await client.query_raw(
        "SELECT \"id\" FROM \"User\" WHERE \"role\" IN ('PROFESSOR', 'ADMIN', 'MASTER_ADMIN') AND \"id\" = ANY($1::text[])",
        list({str(r["recordedById"]) for r in rows}),
    )
    senior = {str(s["id"]) for s in seniors}
    return {
        key: choose_primary(takes, answer_source=source.get(key), explicit=explicit.get(key), senior_redoers=senior)
        for key, takes in by_target.items()
    }


async def required_unanswered(client: Any, session_id: str) -> dict[str, Any]:
    """A19: how many REQUIRED questions (``isRequired``, asked now) each subject of a session has
    neither answered in text nor been recorded on (a question take, or a take on its section). Finish
    reports it ("3 required questions unanswered") and NEVER blocks on it."""
    srow = await client.query_raw('SELECT "questionnaireId" FROM "QuestionnaireInterview" WHERE "id" = $1', session_id)
    if not srow:
        return {"count": 0, "bySubject": {}}
    qid = str(srow[0]["questionnaireId"])
    required = await client.query_raw(
        'SELECT COALESCE(q."lineageId", q."id") AS lineage, q."sectionId" FROM "QuestionnaireQuestion" q '
        'WHERE q."questionnaireId" = $1 AND q."isRequired" AND q."isActive" AND q."retiredAt" IS NULL AND q."supersededById" IS NULL',
        qid,
    )
    if not required:
        return {"count": 0, "bySubject": {}}
    people = await client.query_raw(
        'SELECT \'A:\' || "artisanId" AS key, "artisanId", NULL::text AS "respondentId" FROM "QuestionnaireInterviewArtisan" '
        'WHERE "interviewId" = $1 UNION ALL SELECT \'R:\' || "id", NULL, "id" FROM "QuestionnaireRespondent" '
        'WHERE "sessionId" = $1 AND "artisanId" IS NULL',
        session_id,
    )
    artisan_ids = [str(p["artisanId"]) for p in people if p.get("artisanId")]
    covered: dict[str, set[str]] = defaultdict(set)
    for a, lineages in (await typed_lineages(client, qid, artisan_ids)).items():
        covered[f"A:{a}"] |= lineages
    section_of = {str(r["lineage"]): str(r["sectionId"]) if r.get("sectionId") else None for r in required}
    for t in await take_rows(client, qid, artisan_ids=artisan_ids):
        key = f"A:{t['artisanId']}"
        if t.get("filing") == "NEEDS_DECISION":
            continue
        if t.get("questionLineageId"):
            covered[key].add(str(t["questionLineageId"]))
        elif t.get("sectionId"):
            covered[key] |= {lin for lin, sec in section_of.items() if sec == str(t["sectionId"])}
    guest_rows = await client.query_raw(
        'SELECT \'R:\' || a."respondentId" AS key, a."questionLineageId" FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireRespondent" r ON r."id" = a."respondentId" '
        'WHERE r."sessionId" = $1 AND btrim(rv."text") <> \'\'',
        session_id,
    )
    for g in guest_rows:
        covered[str(g["key"])].add(str(g["questionLineageId"]))
    wanted = set(section_of)
    by_subject = {str(p["key"]): len(wanted - covered.get(str(p["key"]), set())) for p in people}
    return {"count": sum(by_subject.values()), "bySubject": by_subject}
