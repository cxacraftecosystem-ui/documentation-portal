"""Recording chips: the few facts that make a take findable again (owner, 31 Oct 2026).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (shared/questionnaire.sha256).

Every recording the server hands out carries ``chips``: the section (code and title) or question
(display code) it answers, the questionnaire, who answered and who was only present, the session's
date (and the capture day when that differs, and whether either is estimated), the workshop (a
field workshop or a design workshop), the language and the duration. Clients draw them in one order
and make every chip but the duration a filter (``shared/questionnaire-chips.json`` holds both
clients to the same labels and the same filter per chip).

Nothing here is read per recording: :func:`session_meta` is one statement for every session in the
list (plus one for design workshops, only when one is named), and :func:`for_rows` reuses the
target and subject rows the caller already read when it has them.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Any

from app.questionnaire.common import IST, as_date, aware, enum_text
from app.shared_vocab import ESTIMATED_CAPTURED_AT_SOURCES

#: The day a take was captured is read in India time, as every other day in the questionnaire.
ESTIMATED = frozenset(ESTIMATED_CAPTURED_AT_SOURCES)

_META_SQL = (
    'SELECT i."id", i."title", i."sessionDate", i."sessionDateEstimated", i."source"::text AS source, '
    'i."languageCode", i."workshopId", i."designWorkshopId", q."id" AS qid, q."title" AS qtitle, q."kind" AS qkind, '
    'w."title" AS wtitle FROM "QuestionnaireInterview" i '
    'LEFT JOIN "Questionnaire" q ON q."id" = i."questionnaireId" '
    'LEFT JOIN "Workshop" w ON w."id" = i."workshopId" WHERE i."id" = ANY($1::text[])'
)


def capture_day(captured_at: Any) -> str | None:
    value = aware(captured_at)
    if value is None:
        return None
    return value.astimezone(IST).date().isoformat()


async def session_meta(client: Any, session_ids: list[str]) -> dict[str, dict[str, Any]]:
    """The session half of every chip, for many sessions in one read (two with design workshops).

    A design workshop's title is read only for sessions that name one and no field workshop: that
    table exists in designer-portal only, and documentation-portal's sessions never name one.
    """
    sids = sorted({str(s) for s in session_ids if s})
    if not sids:
        return {}
    rows = await client.query_raw(_META_SQL, sids)
    design_ids = sorted({str(r["designWorkshopId"]) for r in rows if r.get("designWorkshopId") and not r.get("workshopId")})
    design: dict[str, str] = {}
    if design_ids:
        for d in await client.query_raw('SELECT "id", "title" FROM "DesignWorkshop" WHERE "id" = ANY($1::text[])', design_ids):
            design[str(d["id"])] = d.get("title") or ""
    out: dict[str, dict[str, Any]] = {}
    for r in rows:
        day = as_date(r.get("sessionDate"))
        if r.get("workshopId"):
            workshop = {"id": str(r["workshopId"]), "name": r.get("wtitle") or "", "type": "FIELD"}
        elif r.get("designWorkshopId") and str(r["designWorkshopId"]) in design:
            workshop = {"id": str(r["designWorkshopId"]), "name": design[str(r["designWorkshopId"])], "type": "DESIGN"}
        else:
            workshop = None
        out[str(r["id"])] = {
            "session": {"id": str(r["id"]), "title": r.get("title") or ""},
            "questionnaire": {"id": str(r["qid"]), "title": r.get("qtitle") or "", "kind": r.get("qkind")} if r.get("qid") else None,
            "sessionDate": day.isoformat() if day else None,
            "sessionDateEstimated": bool(r.get("sessionDateEstimated")),
            "workshop": workshop,
            "source": enum_text(r.get("source")) or "APP",
            "languageCode": r.get("languageCode"),
        }
    return out


def target_chip(t: dict[str, Any]) -> dict[str, Any]:
    """One target row (``QuestionnaireRecordingTarget`` joined to its section and question)."""
    if t.get("questionLineageId"):
        return {
            "kind": "QUESTION",
            "questionLineageId": str(t["questionLineageId"]),
            "displayCode": t.get("displayCode") or "",
            "prompt": t.get("prompt") or "",
            "sectionId": t.get("qsecid") or t.get("sectionId"),
            "sectionCode": t.get("qsec") or "",
            "sectionTitle": t.get("qstitle") or "",
        }
    return {
        "kind": "SECTION",
        "sectionId": t.get("sectionId"),
        "sectionCode": t.get("scode") or "",
        "sectionTitle": t.get("stitle") or "",
    }


def build(*, meta: dict[str, Any] | None, targets: list[dict[str, Any]], subjects: list[dict[str, Any]],
          respondents: list[dict[str, Any]], captured_at: Any, captured_at_source: str | None,
          language_code: str | None, duration_ms: int | None, recorded_by: dict[str, Any] | None) -> dict[str, Any]:
    """The ``chips`` object of one recording. Pure: everything it needs is passed in.

    ``subjects`` are ``{artisanId, name, role}``; ``respondents`` are ``{respondentId, name, role}``.
    Who answered comes before who was only present, each group by name, so the "+N" a client shows
    hides the same people on every screen.
    """
    meta = meta or {}
    session_day = meta.get("sessionDate")
    captured = capture_day(captured_at)
    order = {"ANSWERED": 0, "PRESENT": 1}
    artisans = sorted(
        ({"id": str(s["artisanId"]), "name": s.get("name") or "", "role": s.get("role") or "ANSWERED"} for s in subjects if s.get("artisanId")),
        key=lambda a: (order.get(a["role"], 2), a["name"].casefold(), a["id"]),
    )
    guests = sorted(
        ({"id": str(g["respondentId"]), "name": g.get("name") or "", "role": g.get("role") or "ANSWERED"} for g in respondents),
        key=lambda a: (order.get(a["role"], 2), a["name"].casefold(), a["id"]),
    )
    return {
        "session": meta.get("session"),
        "questionnaire": meta.get("questionnaire"),
        "targets": [target_chip(t) for t in targets],
        "artisans": artisans,
        "respondents": guests,
        "sessionDate": session_day,
        "sessionDateEstimated": bool(meta.get("sessionDateEstimated")),
        "capturedOn": captured if captured and captured != session_day else None,
        "captureEstimated": (captured_at_source or "") in ESTIMATED,
        "workshop": meta.get("workshop"),
        "languageCode": language_code or meta.get("languageCode"),
        "durationMs": int(duration_ms) if duration_ms is not None else None,
        "researcher": recorded_by,
        "source": meta.get("source") or "APP",
    }


#: Targets with what a chip names: the section's code and title, the question's display code.
TARGETS_SQL = (
    'SELECT t.*, q."displayCode", q."prompt", q."sectionCode" AS qsec, q."sectionTitle" AS qstitle, q."sectionId" AS qsecid, '
    's."code" AS scode, s."title" AS stitle FROM "QuestionnaireRecordingTarget" t '
    'LEFT JOIN "QuestionnaireQuestion" q ON q."id" = COALESCE(t."askedQuestionId", t."questionLineageId") '
    'LEFT JOIN "QuestionnaireSection" s ON s."id" = t."sectionId" WHERE t."recordingId" = ANY($1::text[]) ORDER BY t."targetKey"'
)

#: Artisan subjects (with their names) and guest respondents in one read.
SUBJECTS_SQL = (
    'SELECT x.* FROM (SELECT s."recordingId", s."artisanId", NULL::text AS "respondentId", a."name" AS name, '
    's."role"::text AS role, s."named", s."repeatReason"::text AS reason, s."flag"::text AS flag '
    'FROM "QuestionnaireRecordingSubject" s LEFT JOIN "Artisan" a ON a."id" = s."artisanId" WHERE s."recordingId" = ANY($1::text[]) '
    'UNION ALL SELECT g."recordingId", NULL, g."respondentId", r."name", g."role"::text, g."named", g."repeatReason"::text, g."flag"::text '
    'FROM "QuestionnaireRecordingRespondent" g JOIN "QuestionnaireRespondent" r ON r."id" = g."respondentId" '
    'WHERE g."recordingId" = ANY($1::text[])) x ORDER BY x.role, x."artisanId", x.name'
)


async def for_rows(client: Any, rows: list[dict[str, Any]], *, targets: list[dict[str, Any]] | None = None,
                   subjects: list[dict[str, Any]] | None = None) -> dict[str, dict[str, Any]]:
    """``{recordingId: chips}`` for ``recording_rows`` output. ``targets``/``subjects`` are the
    :data:`TARGETS_SQL`/:data:`SUBJECTS_SQL` rows when the caller already read them."""
    if not rows:
        return {}
    rids = [str(r["id"]) for r in rows]
    if targets is None:
        targets = await client.query_raw(TARGETS_SQL, rids)
    if subjects is None:
        subjects = await client.query_raw(SUBJECTS_SQL, rids)
    meta = await session_meta(client, [str(r["sessionId"]) for r in rows if r.get("sessionId")])
    t_by: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for t in targets:
        t_by[str(t["recordingId"])].append(t)
    a_by: dict[str, list[dict[str, Any]]] = defaultdict(list)
    g_by: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for s in subjects:
        (a_by if s.get("artisanId") else g_by)[str(s["recordingId"])].append(s)
    out: dict[str, dict[str, Any]] = {}
    for r in rows:
        rid = str(r["id"])
        out[rid] = build(
            meta=meta.get(str(r.get("sessionId"))) if r.get("sessionId") else None,
            targets=t_by.get(rid, []), subjects=a_by.get(rid, []), respondents=g_by.get(rid, []),
            captured_at=r.get("capturedAt"), captured_at_source=r.get("source"), language_code=r.get("languageCode"),
            duration_ms=r.get("durationMs"),
            recorded_by={"id": str(r["recordedById"]), "name": r.get("recordedByName") or ""} if r.get("recordedById") else None,
        )
    return out


async def for_media(client: Any, media_ids: list[str]) -> dict[str, dict[str, Any]]:
    """``{mediaFileId: chips}`` for media rows a portal lists (the media library, a workshop's media,
    search). A take gets its full chips; a clip linked to a session but not filed yet gets the
    session's (no target, no artisans). Media that is not a questionnaire clip gets nothing."""
    from app.questionnaire import recordings

    mids = sorted({str(m) for m in media_ids if m})
    if not mids:
        return {}
    rows = await recordings.recording_rows(client, media_ids=mids)
    by_recording = await for_rows(client, rows)
    out = {str(r["mediaFileId"]): by_recording[str(r["id"])] for r in rows}
    loose = [m for m in mids if m not in out]
    if loose:
        linked = await client.query_raw(
            'SELECT m."id", m."questionnaireInterviewId" AS sid, m."createdAt" FROM "MediaFile" m '
            'WHERE m."id" = ANY($1::text[]) AND m."questionnaireInterviewId" IS NOT NULL',
            loose,
        )
        meta = await session_meta(client, [str(m["sid"]) for m in linked])
        for m in linked:
            out[str(m["id"])] = build(
                meta=meta.get(str(m["sid"])), targets=[], subjects=[], respondents=[], captured_at=None,
                captured_at_source=None, language_code=None, duration_ms=None, recorded_by=None,
            )
    return out
