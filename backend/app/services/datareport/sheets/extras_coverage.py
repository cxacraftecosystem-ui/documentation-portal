from __future__ import annotations

from dataclasses import replace
from typing import Any

from app.core.db import db
from app.services.data_explorer import overview
from app.services.datareport.contract import Banner, Column, Link, ReportContext, Sheet, register
from app.services.datareport.sheets.extras_common import (
    Plugin,
    cached,
    chunks,
    fingerprint,
    g,
    ident,
    media_facts,
    media_owner_artisans,
    open_record,
    records,
    say,
    when,
)

STATE_TEXT = {"complete": "Complete", "partial": "Partial", "missing": "Missing"}
STATE_VALUE = {"complete": 1.0, "partial": 0.5, "missing": 0.0}
SPARK = "trend"

_V2_SQL = (
    'SELECT a."artisanId", a."questionLineageId" AS "key", rv."sessionId" FROM "QuestionnaireAnswer" a '
    'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
    'WHERE a."artisanId" = ANY($1::text[]) AND btrim(coalesce(rv."text", \'\')) <> \'\''
)


async def answered_v2(aids: list[str]) -> dict[str, list[tuple[str, str]]]:
    out: dict[str, list[tuple[str, str]]] = {}
    for part in chunks(aids):
        for row in await db.query_raw(_V2_SQL, part):
            out.setdefault(str(row["artisanId"]), []).append((str(row["key"]), str(row.get("sessionId") or "")))
    return out


def _key_of(question: Any) -> str:
    return str(g(question, "lineageId") or g(question, "id") or "")


def answered_from(interview: Any) -> set[str] | None:
    responses = g(interview, "responses")
    if responses is None:
        return None
    keys: set[str] = set()
    for response in responses:
        if not str(g(response, "answerText") or "").strip():
            continue
        question = g(response, "question")
        key = _key_of(question) if question is not None else str(g(response, "questionId") or "")
        if key:
            keys.add(key)
    return keys


def _newest_first(interviews: list[Any]) -> list[Any]:
    def stamp(item: Any) -> float:
        moment = when(g(item, "createdAt"))
        return moment.timestamp() if moment else 0.0

    return sorted(interviews, key=stamp, reverse=True)


async def _questions() -> list[Any]:
    return list(
        await db.questionnairequestion.find_many(
            where={"isActive": True, "retiredAt": None},
            order=[{"sortOrder": "asc"}, {"id": "asc"}],
            take=overview.QUESTIONS_MAX,
        )
    )


async def compute(ctx: ReportContext) -> dict[str, Any]:
    artisans = records(ctx, "artisans")
    interviews = _newest_first(records(ctx, "interviews", filtered=False))
    if not artisans:
        return {"sections": [], "artisans": []}

    async def load() -> dict[str, Any]:
        sections = overview.sections_of(await _questions())
        answered_by: dict[str, set[str]] = {}
        unknown: list[str] = []
        for interview in interviews:
            keys = answered_from(interview)
            if keys is None:
                unknown.append(ident(interview))
            else:
                answered_by[ident(interview)] = keys
        if unknown:
            answered_by.update(await overview._answered(unknown))
        sittings: dict[str, list[str]] = {}
        for interview in interviews:
            for link in g(interview, "artisans") or []:
                aid = str(g(link, "artisanId") or ident(g(link, "artisan")) or "")
                if aid:
                    sittings.setdefault(aid, []).append(ident(interview))
        v2 = await answered_v2(sorted({ident(a) for a in artisans} - {""}))
        for aid, pairs in v2.items():
            for key, session in pairs:
                if not session:
                    continue
                answered_by.setdefault(session, set()).add(key)
                if session not in sittings.get(aid, []):
                    sittings.setdefault(aid, []).append(session)
        facts = await media_facts(ctx)
        owners = media_owner_artisans(ctx)
        sizes = {ident(m): int(g(m, "sizeBytes") or 0) for m in records(ctx, "media", filtered=False)}
        per_artisan: dict[str, dict[str, int]] = {}
        for mid, aids in owners.items():
            for aid in aids:
                bucket = per_artisan.setdefault(aid, {"files": 0, "bytes": 0, "talkMs": 0})
                bucket["files"] += 1
                bucket["bytes"] += sizes.get(mid, 0)
                bucket["talkMs"] += int((facts.get(mid) or {}).get("talkMs") or 0)
        keys_of = {s["key"]: {k for k, _ in s["questions"]} for s in sections}
        rows: list[dict[str, Any]] = []
        for artisan in artisans:
            aid = ident(artisan)
            mine = sittings.get(aid, [])
            answered: set[str] = set()
            for iid in mine:
                answered |= answered_by.get(iid, set())
            fields, states = overview.coverage(sections, answered)
            for state in states:
                keys = keys_of.get(state["key"], set())
                state["interview"] = next(
                    (i for i in mine if answered_by.get(i, set()) & keys), mine[0] if mine else None
                )
            rows.append(
                {
                    "id": aid,
                    "fields": fields,
                    "states": states,
                    "media": per_artisan.get(aid, {"files": 0, "bytes": 0, "talkMs": 0}),
                }
            )
        return {
            "sections": [{"key": s["key"], "label": s["label"]} for s in sections],
            "artisans": rows,
        }

    key = "extras.coverage." + fingerprint(
        [ident(a) for a in artisans], [ident(i) for i in interviews]
    )
    data = await cached(ctx, key, load)
    by_id = {ident(a): a for a in artisans}
    for row in data["artisans"]:
        row["artisan"] = by_id.get(row["id"])
    return data


def state_text(ctx: ReportContext, state: str) -> str:
    return ctx.t("status." + state, STATE_TEXT.get(state, state)) if ctx.t else STATE_TEXT.get(state, state)


def _cell(ctx: ReportContext, state: dict[str, Any]) -> Any:
    text = state_text(ctx, state["state"])
    interview = state.get("interview")
    if interview and ctx.options.links:
        return Link(text, ctx.links.section(interview, state["key"]))
    return text


def _percent(fields: dict[str, int]) -> float | None:
    return fields["filled"] / fields["total"] if fields["total"] else None


BASE_COLUMNS = [
    Column("artisanId", "Artisan ID", "id", "The artisan's record id.", hidden=True, group="ids", source="Artisan.id"),
    Column("artisan", "Artisan", "link", "Opens the artisan in the portal.", width=26, source="Artisan.name"),
    Column("craft", "Craft", "text", "The artisan's craft.", width=18, source="Craft.name"),
    Column(
        "filled",
        "Answered",
        "int",
        "Questions answered across all sittings.",
        total="sum",
        source="QuestionnaireResponse.answerText,QuestionnaireAnswerRevision.text",
    ),
    Column("questions", "Questions", "int", "Questions in the active questionnaire.", total="sum", source="QuestionnaireQuestion.id"),
    Column("complete", "Complete", "percent", "Share of questions answered.", width=11),
]
SECTION_COLUMN = Column(
    "s0",
    "Section",
    "status",
    "Complete, Partial or Missing for one questionnaire section. Opens that section of the newest sitting that answered it.",
    width=14,
    group="sections",
    source="QuestionnaireQuestion.sectionTitle",
)
SPARK_COLUMN = Column(
    SPARK, "By section", "text", "Sparkline of the sections: top is complete, middle partial, bottom missing.", width=22
)
SPARK_VALUE = Column(
    "ns0",
    "Section",
    "number",
    "One section as a number: 1 complete, 0.5 partial, 0 missing.",
    hidden=True,
    group=SPARK,
    source="QuestionnaireQuestion.sectionCode",
)
MEDIA_COLUMNS = [
    Column("files", "Files", "int", "Files linked to the artisan.", total="sum", source="MediaFile.id"),
    Column("bytes", "Size", "bytes", "Total size of those files.", total="sum", source="MediaFile.sizeBytes"),
    Column("talkMs", "Talk time", "duration_ms", "Length of the transcribed recordings.", total="sum", source="TranscriptVersion.durationMs"),
]


def _section_columns(template: Column, prefix: str, sections: list[dict[str, Any]]) -> list[Column]:
    return [
        replace(template, key=f"{prefix}{index}", label=section["label"])
        for index, section in enumerate(sections)
    ]


def _base_row(ctx: ReportContext, row: dict[str, Any]) -> dict[str, Any]:
    artisan = row.get("artisan")
    craft = g(g(artisan, "craft"), "name")
    return {
        "artisanId": row["id"],
        "artisan": open_record(ctx, "artisans", artisan) if artisan is not None else row["id"],
        "craft": craft,
        "filled": row["fields"]["filled"],
        "questions": row["fields"]["total"],
        "complete": _percent(row["fields"]),
    }


def _ordered(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda r: (_percent(r["fields"]) or 0.0, str(g(r.get("artisan"), "name") or "")))


def _empty_note(ctx: ReportContext, data: dict[str, Any]) -> str | None:
    if not data["artisans"]:
        return say(ctx, "banner.noArtisans", "No artisans in this folder.")
    if not data["sections"]:
        return say(ctx, "banner.noQuestions", "No active questionnaire questions.")
    return None


async def build_coverage(ctx: ReportContext) -> list[Sheet]:
    data = await compute(ctx)
    columns = [*BASE_COLUMNS, *_section_columns(SECTION_COLUMN, "s", data["sections"])]
    rows = []
    for row in _ordered(data["artisans"]):
        out = _base_row(ctx, row)
        for index, state in enumerate(row["states"]):
            out[f"s{index}"] = _cell(ctx, state)
        rows.append(out)
    sheet = Sheet(
        key="coverage",
        name="Coverage",
        columns=columns,
        rows=rows,
        banners=[Banner(say(ctx, "banner.coverageLegend", "Green: complete · Amber: partly answered · Red: not started"))],
        note=_empty_note(ctx, data),
        databar_columns=["complete"],
        count=len(rows),
    )
    return [sheet]


async def build_completeness(ctx: ReportContext) -> list[Sheet]:
    data = await compute(ctx)
    spark = [SPARK_COLUMN, *_section_columns(SPARK_VALUE, "ns", data["sections"])] if data["sections"] else []
    columns = [*BASE_COLUMNS, *spark, *MEDIA_COLUMNS]
    rows = []
    for row in _ordered(data["artisans"]):
        out = _base_row(ctx, row)
        if data["sections"]:
            out[SPARK] = None
        for index, state in enumerate(row["states"]):
            out[f"ns{index}"] = STATE_VALUE.get(state["state"], 0.0)
        out["files"] = row["media"]["files"]
        out["bytes"] = row["media"]["bytes"]
        out["talkMs"] = row["media"]["talkMs"]
        rows.append(out)
    sheet = Sheet(
        key="completeness",
        name="Completeness",
        columns=columns,
        rows=rows,
        note=_empty_note(ctx, data),
        sparkline_columns=[SPARK] if data["sections"] else [],
        databar_columns=["complete", "bytes", "talkMs"],
        count=len(rows),
    )
    return [sheet]


register(
    Plugin(
        "extras_coverage",
        "Coverage",
        110,
        "coverage",
        build_coverage,
        lambda: [("coverage", [*BASE_COLUMNS, SECTION_COLUMN], "Coverage")],
    )
)
register(
    Plugin(
        "extras_sparklines",
        "Completeness",
        111,
        "sparklines",
        build_completeness,
        lambda: [("completeness", [*BASE_COLUMNS, SPARK_COLUMN, SPARK_VALUE, *MEDIA_COLUMNS], "Completeness")],
    )
)
