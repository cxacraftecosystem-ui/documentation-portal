from __future__ import annotations

from typing import Any
from urllib.parse import quote

from fastapi import HTTPException, status

from app.core.db import db
from app.services.data_explorer import stats, walk

QUESTIONS_MAX = 2000
INTERVIEWS_MAX = 5000
MISSING_MAX = 5
CHUNK = 500
NO_CRAFT = "_none"

_ANSWERED_SQL = (
    'SELECT DISTINCT r."interviewId", coalesce(q."lineageId", q."id") AS "key" '
    'FROM "QuestionnaireResponse" r JOIN "QuestionnaireQuestion" q ON q."id" = r."questionId" '
    'WHERE r."interviewId" = ANY($1::text[]) AND btrim(coalesce(r."answerText", \'\')) <> \'\''
)
_MEDIA_SQL = (
    'SELECT m."id", m."mediaType"::text AS "mediaType", m."status"::text AS "status", '
    'm."transcriptStatus", '
    '(m."transcriptText" IS NOT NULL AND m."transcriptText" <> \'\') AS "hasText", '
    'EXISTS (SELECT 1 FROM "TranscriptVersion" v WHERE v."mediaFileId" = m."id" '
    'AND v."supersededAt" IS NULL AND v."translationStatus" = \'PENDING\') AS "translating" '
    'FROM "MediaFile" m WHERE m."id" = ANY($1::text[]) AND m."removedAt" IS NULL'
)
_IN_PROGRESS = frozenset({"QUEUED", "PROCESSING", "PENDING", "RUNNING"})


def workshop_id(norm: str) -> str:
    segs = [s for s in norm.split("/") if s]
    if len(segs) != 2 or segs[0] not in ("by-workshop", "workshops"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not a workshop folder")
    return segs[1]


def artisan_of(path: str) -> str | None:
    segs = [s for s in path.split("/") if s]
    for index in range(2, len(segs) - 1):
        if segs[index] == "artisans":
            return segs[index + 1]
    return None


def sections_of(questions: list[Any]) -> list[dict[str, Any]]:
    found: dict[str, dict[str, Any]] = {}
    for question in questions:
        code = str(getattr(question, "sectionCode", None) or "").strip() or "_"
        entry = found.get(code)
        if entry is None:
            label = str(getattr(question, "sectionTitle", None) or "").strip() or code
            entry = found[code] = {"key": code, "label": label, "questions": []}
        key = str(getattr(question, "lineageId", None) or question.id)
        if all(k != key for k, _ in entry["questions"]):
            entry["questions"].append((key, str(getattr(question, "prompt", None) or "")))
    return list(found.values())


def coverage(sections: list[dict[str, Any]], answered: set[str]) -> tuple[dict[str, int], list[dict[str, Any]]]:
    filled = 0
    total = 0
    out: list[dict[str, Any]] = []
    for section in sections:
        keys = section["questions"]
        done = [k for k, _ in keys if k in answered]
        filled += len(done)
        total += len(keys)
        if keys and len(done) == len(keys):
            state = "complete"
        elif done:
            state = "partial"
        else:
            state = "missing"
        missing = [prompt for k, prompt in keys if k not in answered][:MISSING_MAX]
        out.append({"key": section["key"], "state": state, "missing": missing})
    return {"filled": filled, "total": total}, out


def section_href(interview_id: str, code: str) -> str:
    return f"/questionnaire/sessions/{quote(interview_id, safe='')}/view?section={quote(code, safe='')}"


def link_sections(
    states: list[dict[str, Any]],
    sections: list[dict[str, Any]],
    interview_ids: list[str],
    answered_by_interview: dict[str, set[str]],
) -> None:
    keys_of = {section["key"]: {k for k, _ in section["questions"]} for section in sections}
    for state in states:
        keys = keys_of.get(state["key"], set())
        chosen = next(
            (i for i in interview_ids if answered_by_interview.get(i, set()) & keys),
            interview_ids[0] if interview_ids else None,
        )
        state["href"] = section_href(chosen, state["key"]) if chosen else None


def transcript_counts(rows: list[dict[str, Any]]) -> dict[str, int]:
    counts = {"total": 0, "ready": 0, "translating": 0, "failed": 0}
    for row in rows:
        kind = str(row.get("mediaType") or "").upper()
        state = str(row.get("transcriptStatus") or "").upper()
        if kind not in ("AUDIO", "VIDEO"):
            continue
        counts["total"] += 1
        if state == "FAILED":
            counts["failed"] += 1
        elif state in _IN_PROGRESS or row.get("translating"):
            counts["translating"] += 1
        elif row.get("hasText") or state == "COMPLETED":
            counts["ready"] += 1
    return counts


def review_counts(statuses: list[str]) -> dict[str, int]:
    upper = [str(s).split(".")[-1].upper() for s in statuses if s and str(s).strip()]
    return {
        "approved": sum(1 for s in upper if s == "APPROVED"),
        "pending": sum(1 for s in upper if s in ("PENDING", "NEEDS_REVISION")),
        "total": len(upper),
    }


async def _media_rows(ids: list[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for start in range(0, len(ids), CHUNK):
        rows.extend(await db.query_raw(_MEDIA_SQL, ids[start : start + CHUNK]))
    return rows


async def _answered(interview_ids: list[str]) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for start in range(0, len(interview_ids), CHUNK):
        for row in await db.query_raw(_ANSWERED_SQL, interview_ids[start : start + CHUNK]):
            out.setdefault(str(row["interviewId"]), set()).add(str(row["key"]))
    return out


def _interview_where(browser: Any, aids: list[str], scope: Any) -> dict[str, Any]:
    records = scope.records
    live = getattr(browser, "_live", None)
    if callable(live):
        records = live(records)
    return browser._and({"artisans": {"some": {"artisanId": {"in": aids}}}}, records)


def _craft_label(ws: Any) -> str | None:
    names: list[str] = []
    for link in getattr(ws, "crafts", None) or []:
        name = getattr(getattr(link, "craft", None), "name", None)
        if name and name not in names:
            names.append(str(name))
    return " · ".join(names) or None


async def workshop_overview(browser: Any, norm: str, scope: Any) -> dict[str, Any]:
    wid = workshop_id(norm)
    ws = await browser._require(
        db.workshop, wid, "Workshop", include=browser._WS_CRAFTS_INCLUDE, scope_where=scope.records
    )
    artisans = list(await browser._linked_artisans(ws, scope))
    aids = [str(a.id) for a in artisans]
    owners: dict[str, set[str]] = {}

    async def listing(path: str, level_scope: Any) -> Any:
        entries, info = await browser._list_level(path, level_scope)
        aid = artisan_of(path)
        if aid:
            for entry in entries:
                mid = entry.get("mediaId") or entry.get("_txMedia")
                if mid:
                    owners.setdefault(str(mid), set()).add(aid)
        return entries, info

    files, truncated = await walk.manifest_files(listing, scope, [(norm, "")], None, fill=False)
    totals = stats.totals(files, truncated=truncated)
    sized: dict[str, int] = {}
    for f in files:
        if f.get("mediaId") and f.get("content") is None and not f.get("_fill") and f.get("mediaType") in walk.TYPE_TOKEN:
            sized.setdefault(str(f["mediaId"]), stats.size_of(f) or 0)
    media_ids = list(sized)
    questions = await db.questionnairequestion.find_many(
        where={"isActive": True, "retiredAt": None},
        order=[{"sortOrder": "asc"}, {"id": "asc"}],
        take=QUESTIONS_MAX,
    )
    sections = sections_of(list(questions))
    interviews = (
        await db.questionnaireinterview.find_many(
            where=_interview_where(browser, aids, scope),
            include={"artisans": True},
            order=[{"createdAt": "desc"}, {"id": "desc"}],
            take=INTERVIEWS_MAX,
        )
        if aids
        else []
    )
    answered_by_interview = await _answered([str(i.id) for i in interviews])
    rows = await _media_rows(media_ids)
    by_media = {str(r["id"]): r for r in rows}

    answered: dict[str, set[str]] = {aid: set() for aid in aids}
    sittings: dict[str, list[str]] = {aid: [] for aid in aids}
    for interview in interviews:
        keys = answered_by_interview.get(str(interview.id), set())
        for link in getattr(interview, "artisans", None) or []:
            if str(link.artisanId) in answered:
                answered[str(link.artisanId)] |= keys
                sittings[str(link.artisanId)].append(str(interview.id))

    per_media: dict[str, list[dict[str, Any]]] = {aid: [] for aid in aids}
    per_bytes: dict[str, int] = dict.fromkeys(aids, 0)
    for mid in media_ids:
        row = by_media.get(mid)
        if row is None:
            continue
        for aid in owners.get(mid, ()):
            if aid in per_media:
                per_media[aid].append(row)
                per_bytes[aid] += sized.get(mid, 0)

    out_artisans: list[dict[str, Any]] = []
    filled_total = {"filled": 0, "total": 0}
    for artisan in artisans:
        aid = str(artisan.id)
        fields, section_states = coverage(sections, answered.get(aid, set()))
        link_sections(section_states, sections, sittings.get(aid, []), answered_by_interview)
        filled_total["filled"] += fields["filled"]
        filled_total["total"] += fields["total"]
        craft_id = getattr(artisan, "craftId", None) or NO_CRAFT
        mine = per_media.get(aid, [])
        out_artisans.append(
            {
                "id": aid,
                "name": artisan.name,
                "craft": getattr(getattr(artisan, "craft", None), "name", None),
                "path": f"by-workshop/{wid}/crafts/{craft_id}/artisans/{aid}",
                "fields": fields,
                "media": {"files": len(mine), "bytes": per_bytes.get(aid, 0)},
                "transcripts": transcript_counts(mine),
                "review": review_counts(
                    [str(getattr(artisan, "status", "") or ""), *(str(r.get("status") or "") for r in mine)]
                ),
                "sections": section_states,
            }
        )

    return {
        "path": norm,
        "title": ws.title,
        "subtitle": _craft_label(ws),
        "totals": {
            "files": totals["files"],
            "bytes": totals["bytes"],
            "fields": filled_total,
            "transcripts": transcript_counts(rows),
            "review": review_counts(
                [*(str(getattr(a, "status", "") or "") for a in artisans), *(str(r.get("status") or "") for r in rows)]
            ),
        },
        "sections": [{"key": s["key"], "label": s["label"]} for s in sections],
        "artisans": out_artisans,
        "truncated": bool(truncated) or len(interviews) >= INTERVIEWS_MAX or len(questions) >= QUESTIONS_MAX,
    }
