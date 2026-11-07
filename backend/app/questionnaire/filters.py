from __future__ import annotations

from dataclasses import dataclass, field, fields
from datetime import date
from typing import Any

from app.questionnaire import ports
from app.questionnaire.common import as_date, fail, iso
from app.questionnaire.kinds import KIND_TOKENS

MULTI = (
    "questionnaireId", "sectionId", "questionLineageId", "artisanId", "researcherId", "workshopId",
    "designWorkshopId", "kind", "source", "transcript", "languageCode",
)
SOURCES = ("APP", "UPLOAD", "HANDOFF")
TRANSCRIPT = ("HAS", "NONE", "PENDING", "FAILED")
KINDS = (*sorted(KIND_TOKENS), "NONE")
TRANSCRIPT_STATES = {
    "HAS": ("COMPLETED", "SKIPPED_DUPLICATE"),
    "FAILED": ("FAILED", "EMPTY", "REFUSED_CONSENT"),
}
MAX_VALUES = 50
MAX_TEXT = 200
PAGE_MAX = 100


@dataclass(frozen=True)
class Filters:
    q: str | None = None
    questionnaireId: tuple[str, ...] = ()
    sectionId: tuple[str, ...] = ()
    questionLineageId: tuple[str, ...] = ()
    artisanId: tuple[str, ...] = ()
    researcherId: tuple[str, ...] = ()
    workshopId: tuple[str, ...] = ()
    designWorkshopId: tuple[str, ...] = ()
    dateFrom: date | None = None
    dateTo: date | None = None
    kind: tuple[str, ...] = ()
    source: tuple[str, ...] = ()
    transcript: tuple[str, ...] = ()
    languageCode: tuple[str, ...] = ()

    def empty(self) -> bool:
        return not any(getattr(self, f.name) for f in fields(self))

    def without(self, *names: str) -> Filters:
        return Filters(**{f.name: (() if f.name in MULTI else None) if f.name in names else getattr(self, f.name) for f in fields(self)})

    def session_level(self) -> bool:
        return not self.without("q", "questionnaireId", "kind").empty()

    def echo(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        for f in fields(self):
            value = getattr(self, f.name)
            if value:
                out[f.name] = list(value) if isinstance(value, tuple) else value.isoformat() if isinstance(value, date) else value
        return out


def _raw(params: Any, name: str) -> list[str]:
    if hasattr(params, "getlist"):
        return [str(v) for v in params.getlist(name)]
    value = params.get(name)
    if value is None:
        return []
    return [str(v) for v in value] if isinstance(value, (list, tuple)) else [str(value)]


def _values(params: Any, name: str) -> list[str]:
    out: list[str] = []
    for value in _raw(params, name):
        for piece in value.split(","):
            part = piece.strip()
            if part and part not in out:
                out.append(part)
    if len(out) > MAX_VALUES:
        fail("VALIDATION_FAILED", f"At most {MAX_VALUES} values for {name}.")
    return out


def _text(params: Any, name: str) -> str | None:
    values = _raw(params, name)
    text = values[-1].strip() if values else ""
    return text[:MAX_TEXT] or None


def _day(params: Any, name: str) -> date | None:
    value = _text(params, name)
    if not value:
        return None
    try:
        return as_date(value)
    except ValueError:
        fail("VALIDATION_FAILED", f"{name} is not a date (YYYY-MM-DD).")


def _vocab(values: list[str], allowed: tuple[str, ...], name: str) -> tuple[str, ...]:
    upper = [v.upper() for v in values]
    bad = [v for v in upper if v not in allowed]
    if bad:
        fail("VALIDATION_FAILED", f"{name} must be one of {', '.join(allowed)}.")
    return tuple(upper)


def parse(params: Any) -> Filters:
    q = _text(params, "q") or _text(params, "search")
    out = {name: tuple(_values(params, name)) for name in MULTI if name not in ("kind", "source", "transcript")}
    out["languageCode"] = tuple(v.lower() for v in out["languageCode"])
    start, end = _day(params, "dateFrom"), _day(params, "dateTo")
    if start and end and start > end:
        start, end = end, start
    return Filters(
        q=q, dateFrom=start, dateTo=end,
        kind=_vocab(_values(params, "kind"), KINDS, "kind"),
        source=_vocab(_values(params, "source"), SOURCES, "source"),
        transcript=_vocab(_values(params, "transcript"), TRANSCRIPT, "transcript"),
        **out,
    )


def like(text: str) -> str:
    return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


@dataclass
class Sql:
    params: list[Any] = field(default_factory=list)

    def p(self, value: Any) -> str:
        self.params.append(value)
        return f"${len(self.params)}"

    def arr(self, values: tuple[str, ...] | list[str]) -> str:
        return f"{self.p(list(values))}::text[]"


def _design_portal() -> bool:
    return ports.portal() == "designer-portal"


def _takes_in_session(alias: str = "i") -> str:
    return (f'FROM "QuestionnaireRecording" r2 JOIN "MediaFile" m2 ON m2."id" = r2."mediaFileId" '
            f'WHERE m2."questionnaireInterviewId" = {alias}."id" AND r2."removedAt" IS NULL AND m2."removedAt" IS NULL')


def _target_match(f: Filters, b: Sql, alias: str) -> str:
    parts = []
    if f.sectionId:
        sections = b.arr(f.sectionId)
        parts.append(f'{alias}."sectionId" = ANY({sections})')
        parts.append(f'EXISTS (SELECT 1 FROM "QuestionnaireQuestion" tq WHERE tq."id" = {alias}."questionLineageId" AND tq."sectionId" = ANY({sections}))')
    if f.questionLineageId:
        parts.append(f'{alias}."questionLineageId" = ANY({b.arr(f.questionLineageId)})')
    return "(" + " OR ".join(parts) + ")"


M_TS = 'm."transcriptStatus"'
M2_TS = 'm2."transcriptStatus"'


def _transcript_states(token: str, column: str, b: Sql) -> str:
    if token == "PENDING":
        return f'({column} IS NULL OR NOT ({column} = ANY({b.arr(TRANSCRIPT_STATES["HAS"] + TRANSCRIPT_STATES["FAILED"])})))'
    return f'{column} = ANY({b.arr(TRANSCRIPT_STATES[token])})'


def _kind_match(f: Filters, b: Sql, form: str) -> str:
    named = [k for k in f.kind if k != "NONE"]
    parts = []
    if named:
        parts.append(f'{form}."kind" = ANY({b.arr(named)})')
    if "NONE" in f.kind:
        parts.append(f'{form}."kind" IS NULL')
    return "(" + " OR ".join(parts) + ")"


def _workshop_match(f: Filters, b: Sql, alias: str) -> str:
    parts = []
    if f.workshopId:
        parts.append(f'{alias}."workshopId" = ANY({b.arr(f.workshopId)})')
    if f.designWorkshopId:
        parts.append(f'{alias}."designWorkshopId" = ANY({b.arr(f.designWorkshopId)})')
    return "(" + " OR ".join(parts) + ")"


def session_text(term: str, b: Sql, alias: str = "i") -> str:
    t = b.p(like(term))
    esc = "ESCAPE '\\'"
    parts = [
        f'{alias}."title" ILIKE {t} {esc}', f'{alias}."note" ILIKE {t} {esc}', f'{alias}."legacyTitle" ILIKE {t} {esc}',
        f'{alias}."place" ILIKE {t} {esc}',
        f'EXISTS (SELECT 1 FROM "Questionnaire" sq WHERE sq."id" = {alias}."questionnaireId" AND sq."title" ILIKE {t} {esc})',
        f'EXISTS (SELECT 1 FROM "Workshop" sw WHERE sw."id" = {alias}."workshopId" AND sw."title" ILIKE {t} {esc})',
        f'EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" sx JOIN "Artisan" sa ON sa."id" = sx."artisanId" '
        f'WHERE sx."interviewId" = {alias}."id" AND (sa."name" ILIKE {t} {esc} OR sa."localName" ILIKE {t} {esc}))',
        f'EXISTS (SELECT 1 FROM "QuestionnaireRespondent" sr WHERE sr."sessionId" = {alias}."id" AND sr."name" ILIKE {t} {esc})',
        f'EXISTS (SELECT 1 FROM "QuestionnaireInterviewResearcher" se JOIN "User" su ON su."id" = se."userId" '
        f'WHERE se."interviewId" = {alias}."id" AND su."name" ILIKE {t} {esc})',
    ]
    if _design_portal():
        parts.append(f'EXISTS (SELECT 1 FROM "DesignWorkshop" sd WHERE sd."id" = {alias}."designWorkshopId" AND sd."title" ILIKE {t} {esc})')
    return "(" + " OR ".join(parts) + ")"


def session_where(f: Filters, b: Sql, *, alias: str = "i", text: bool = True) -> list[str]:
    w: list[str] = []
    if f.questionnaireId:
        w.append(f'{alias}."questionnaireId" = ANY({b.arr(f.questionnaireId)})')
    if f.kind:
        w.append(f'EXISTS (SELECT 1 FROM "Questionnaire" kq WHERE kq."id" = {alias}."questionnaireId" AND {_kind_match(f, b, "kq")})')
    if f.artisanId:
        w.append(f'EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = {alias}."id" AND x."artisanId" = ANY({b.arr(f.artisanId)}))')
    if f.researcherId:
        w.append(f'EXISTS (SELECT 1 FROM "QuestionnaireInterviewResearcher" x WHERE x."interviewId" = {alias}."id" AND x."userId" = ANY({b.arr(f.researcherId)}))')
    if f.workshopId or f.designWorkshopId:
        w.append(_workshop_match(f, b, alias))
    if f.dateFrom:
        w.append(f'{alias}."sessionDate" >= {b.p(f.dateFrom.isoformat())}::date')
    if f.dateTo:
        w.append(f'{alias}."sessionDate" <= {b.p(f.dateTo.isoformat())}::date')
    if f.source:
        w.append(f'{alias}."source"::text = ANY({b.arr(f.source)})')
    if f.languageCode:
        langs = b.arr(f.languageCode)
        w.append(f'({alias}."languageCode" = ANY({langs}) OR EXISTS (SELECT 1 {_takes_in_session(alias)} AND r2."languageCode" = ANY({langs})))')
    if f.sectionId or f.questionLineageId:
        takes = f'EXISTS (SELECT 1 {_takes_in_session(alias)} AND EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t2 WHERE t2."recordingId" = r2."id" AND {_target_match(f, b, "t2")}))'
        lineage = []
        if f.questionLineageId:
            lineage.append(f'av."questionLineageId" = ANY({b.arr(f.questionLineageId)})')
        if f.sectionId:
            lineage.append(f'EXISTS (SELECT 1 FROM "QuestionnaireQuestion" aq WHERE aq."id" = av."questionLineageId" AND aq."sectionId" = ANY({b.arr(f.sectionId)}))')
        typed = (f'EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" rv JOIN "QuestionnaireAnswer" av ON av."id" = rv."answerId" '
                 f'WHERE rv."sessionId" = {alias}."id" AND (' + " OR ".join(lineage) + "))")
        w.append(f"({takes} OR {typed})")
    if f.transcript:
        parts = []
        for token in f.transcript:
            if token == "NONE":
                parts.append(f'NOT EXISTS (SELECT 1 {_takes_in_session(alias)} AND {_transcript_states("HAS", M2_TS, b)})')
            else:
                parts.append(f'EXISTS (SELECT 1 {_takes_in_session(alias)} AND {_transcript_states(token, M2_TS, b)})')
        w.append("(" + " OR ".join(parts) + ")")
    if text and f.q:
        w.append(session_text(f.q, b, alias))
    return w


NO_TRANSCRIPTS = object()


def _transcript_text_match(t: str, esc: str, b: Sql, scope: Any) -> str | None:
    if scope is NO_TRANSCRIPTS:
        return None
    clause = f'm."transcriptText" ILIKE {t} {esc}'
    return clause if scope is None else f"({clause} AND {scope.sql('m', b.p)})"


def recording_text(term: str, b: Sql, scope: Any = NO_TRANSCRIPTS) -> str:
    t = b.p(like(term))
    esc = "ESCAPE '\\'"
    parts = [
        session_text(term, b, "i"),
        f'EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" xt LEFT JOIN "QuestionnaireSection" xs ON xs."id" = xt."sectionId" '
        f'LEFT JOIN "QuestionnaireQuestion" xq ON xq."id" = COALESCE(xt."askedQuestionId", xt."questionLineageId") '
        f'WHERE xt."recordingId" = r."id" AND (xs."code" ILIKE {t} {esc} OR xs."title" ILIKE {t} {esc} OR xq."displayCode" ILIKE {t} {esc} '
        f'OR xq."prompt" ILIKE {t} {esc} OR xq."sectionTitle" ILIKE {t} {esc}))',
        f'EXISTS (SELECT 1 FROM "QuestionnaireRecordingSubject" xa JOIN "Artisan" a2 ON a2."id" = xa."artisanId" '
        f'WHERE xa."recordingId" = r."id" AND (a2."name" ILIKE {t} {esc} OR a2."localName" ILIKE {t} {esc}))',
        f'EXISTS (SELECT 1 FROM "User" xu WHERE xu."id" = r."recordedById" AND xu."name" ILIKE {t} {esc})',
        _transcript_text_match(t, esc, b, scope),
        f'm."originalFilename" ILIKE {t} {esc}',
    ]
    return "(" + " OR ".join(p for p in parts if p) + ")"


def recording_where(f: Filters, b: Sql, scope: Any = NO_TRANSCRIPTS) -> list[str]:
    w = ['r."removedAt" IS NULL', 'm."removedAt" IS NULL', 'i."deletedAt" IS NULL', 'i."mergedIntoId" IS NULL']
    session_only = f.without("q", "artisanId", "researcherId", "sectionId", "questionLineageId", "dateFrom", "dateTo",
                             "transcript", "languageCode")
    w += session_where(session_only, b, alias="i", text=False)
    if f.artisanId:
        w.append(f'EXISTS (SELECT 1 FROM "QuestionnaireRecordingSubject" x WHERE x."recordingId" = r."id" AND x."artisanId" = ANY({b.arr(f.artisanId)}))')
    if f.researcherId:
        w.append(f'r."recordedById" = ANY({b.arr(f.researcherId)})')
    if f.sectionId or f.questionLineageId:
        w.append(f'EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t WHERE t."recordingId" = r."id" AND {_target_match(f, b, "t")})')
    if f.dateFrom or f.dateTo:
        day = "(r.\"capturedAt\" AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata')::date"
        on_session, on_capture = [], []
        if f.dateFrom:
            start = b.p(f.dateFrom.isoformat())
            on_session.append(f'i."sessionDate" >= {start}::date')
            on_capture.append(f"{day} >= {start}::date")
        if f.dateTo:
            end = b.p(f.dateTo.isoformat())
            on_session.append(f'i."sessionDate" <= {end}::date')
            on_capture.append(f"{day} <= {end}::date")
        w.append("((" + " AND ".join(on_session) + ") OR (" + " AND ".join(on_capture) + "))")
    if f.transcript:
        parts = []
        for token in f.transcript:
            if token == "NONE":
                parts.append(f'{M_TS} IS NULL OR NOT {_transcript_states("HAS", M_TS, b)}')
            else:
                parts.append(_transcript_states(token, M_TS, b))
        w.append("(" + " OR ".join(f"({p})" for p in parts) + ")")
    if f.languageCode:
        w.append(f'COALESCE(r."languageCode", i."languageCode") = ANY({b.arr(f.languageCode)})')
    if f.q:
        w.append(recording_text(f.q, b, scope))
    return w


_RECORDING_FROM = (
    'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
    'JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
)


async def list_recordings(f: Filters, user: Any = None, *, session_id: str | None = None, cursor: str | None = None,
                          limit: int = 30, client: Any = None) -> dict[str, Any]:
    from app.questionnaire import recordings

    db = client or ports.db()
    limit = max(1, min(int(limit or 30), PAGE_MAX))
    b = Sql()
    w = recording_where(f, b, await ports.records.transcript_scope(user))
    if session_id:
        w.append(f'm."questionnaireInterviewId" = {b.p(session_id)}')
    if cursor:
        try:
            at, rid = cursor.split("|", 1)
        except ValueError:
            fail("VALIDATION_FAILED", "Bad cursor.")
        w.append(f'(r."capturedAt", r."id") < ({b.p(at)}::timestamp, {b.p(rid)})')
    page = await db.query_raw(
        'SELECT r."id", r."capturedAt" ' + _RECORDING_FROM + "WHERE " + " AND ".join(w)
        + f' ORDER BY r."capturedAt" DESC, r."id" DESC LIMIT {limit + 1}',
        *b.params,
    )
    more = len(page) > limit
    page = page[:limit]
    ids = [str(r["id"]) for r in page]
    rows = await recordings.recording_rows(db, recording_ids=ids) if ids else []
    by_id = {str(r["id"]): r for r in rows}
    ordered = [by_id[i] for i in ids if i in by_id]
    items = await recordings.serialize_recordings(db, ordered, user)
    next_cursor = None
    if more and page:
        last = page[-1]
        stamp = iso(last["capturedAt"]) or ""
        next_cursor = f"{stamp.replace('Z', '').replace('+00:00', '')}|{last['id']}"
    return {"items": items, "nextCursor": next_cursor, "filters": f.echo()}


async def media_ids_matching(f: Filters, *, client: Any = None, limit: int = 5000, viewer: Any = NO_TRANSCRIPTS) -> list[str]:
    db = client or ports.db()
    b = Sql()
    scope = NO_TRANSCRIPTS if viewer is NO_TRANSCRIPTS else await ports.records.transcript_scope(viewer)
    w = recording_where(f, b, scope)
    rows = await db.query_raw(
        'SELECT DISTINCT r."mediaFileId" AS id ' + _RECORDING_FROM + "WHERE " + " AND ".join(w) + f" LIMIT {int(limit)}",
        *b.params,
    )
    return [str(r["id"]) for r in rows]


def media_filtered(f: Filters) -> bool:
    return not f.without("q").empty()


async def media_text_ids(term: str, *, client: Any = None, limit: int = 5000, viewer: Any = NO_TRANSCRIPTS) -> list[str]:
    return await media_ids_matching(Filters(q=term), client=client, limit=limit, viewer=viewer)


async def form_ids_matching(f: Filters, *, client: Any = None) -> set[str] | None:
    if f.empty():
        return None
    db = client or ports.db()
    b = Sql()
    clauses = []
    if f.questionnaireId:
        clauses.append(f'q."id" = ANY({b.arr(f.questionnaireId)})')
    if f.kind:
        clauses.append(_kind_match(f, b, "q"))
    if f.q:
        t = b.p(like(f.q))
        esc = "ESCAPE '\\'"
        text_parts = [
            f'q."title" ILIKE {t} {esc}', f'q."description" ILIKE {t} {esc}',
            f'EXISTS (SELECT 1 FROM "QuestionnaireSection" fs WHERE fs."questionnaireId" = q."id" AND (fs."title" ILIKE {t} {esc} OR fs."code" ILIKE {t} {esc}))',
            f'EXISTS (SELECT 1 FROM "QuestionnaireQuestion" fq WHERE fq."questionnaireId" = q."id" AND (fq."prompt" ILIKE {t} {esc} OR fq."displayCode" ILIKE {t} {esc}))',
        ]
        live = 'i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL'
        text_parts.append(f'EXISTS (SELECT 1 FROM "QuestionnaireInterview" i WHERE i."questionnaireId" = q."id" AND {live} AND {session_text(f.q, b, "i")})')
        clauses.append("(" + " OR ".join(text_parts) + ")")
    sessions_part = f.without("q", "questionnaireId", "kind")
    if not sessions_part.empty():
        live = 'i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL'
        has = f'EXISTS (SELECT 1 FROM "QuestionnaireInterview" i WHERE i."questionnaireId" = q."id" AND {live} AND ' + " AND ".join(
            session_where(sessions_part, b, alias="i", text=False)) + ")"
        if (f.workshopId or f.designWorkshopId) and sessions_part.without("workshopId", "designWorkshopId").empty():
            bound = f'EXISTS (SELECT 1 FROM "QuestionnaireBinding" qb WHERE qb."questionnaireId" = q."id" AND {_workshop_match(f, b, "qb")})'
            clauses.append(f"({has} OR {bound})")
        else:
            clauses.append(has)
    rows = await db.query_raw('SELECT q."id" FROM "Questionnaire" q WHERE ' + " AND ".join(clauses), *b.params)
    return {str(r["id"]) for r in rows}


FORM_TITLE, ARTISAN_NAME, USER_NAME = 'q."title"', 'a."name"', 'u."name"'
FACETS = ("questionnaires", "sections", "questions", "artisans", "researchers", "workshops", "languages")


async def facets(*, field_name: str | None = None, q: str | None = None, questionnaire_ids: list[str] | None = None,
                 limit: int = 50, client: Any = None) -> dict[str, Any]:
    db = client or ports.db()
    limit = max(1, min(int(limit or 50), 200))
    wanted = [field_name] if field_name else list(FACETS)
    for name in wanted:
        if name not in FACETS:
            fail("VALIDATION_FAILED", f"field must be one of {', '.join(FACETS)}.")
    live = 'i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL'
    out: dict[str, Any] = {
        "kinds": list(KINDS), "sources": list(SOURCES), "transcript": list(TRANSCRIPT),
    }

    def text(column: str, b: Sql) -> str:
        return f" AND {column} ILIKE {b.p(like(q))} ESCAPE '\\'" if q else ""

    for name in wanted:
        b = Sql()
        if name == "questionnaires":
            rows = await db.query_raw(
                'SELECT q."id", q."title", q."kind", count(i."id") AS n FROM "Questionnaire" q '
                f'LEFT JOIN "QuestionnaireInterview" i ON i."questionnaireId" = q."id" AND {live} '
                f'WHERE q."deletedAt" IS NULL{text(FORM_TITLE, b)} '
                f'GROUP BY q."id", q."title", q."kind" ORDER BY n DESC, q."title" LIMIT {limit}', *b.params)
            out[name] = [{"id": str(r["id"]), "label": r["title"] or "", "kind": r.get("kind"), "count": int(r["n"])} for r in rows]
        elif name == "sections":
            scope = f' AND s."questionnaireId" = ANY({b.arr(questionnaire_ids)})' if questionnaire_ids else ""
            rows = await db.query_raw(
                'SELECT s."id", s."code", s."title", s."questionnaireId", s."sortOrder" FROM "QuestionnaireSection" s '
                f'WHERE s."isActive"{scope}' + (f' AND (s."title" ILIKE {b.p(like(q))} ESCAPE \'\\\' OR s."code" ILIKE {b.p(like(q))} ESCAPE \'\\\')' if q else "")
                + f' ORDER BY s."questionnaireId", s."sortOrder" LIMIT {limit}', *b.params)
            out[name] = [{"id": str(r["id"]), "label": f'{r["code"]} · {r["title"]}'.strip(" ·"), "code": r["code"],
                          "title": r["title"], "questionnaireId": str(r["questionnaireId"]) if r.get("questionnaireId") else None} for r in rows]
        elif name == "questions":
            if not questionnaire_ids:
                out[name] = []
                continue
            rows = await db.query_raw(
                'SELECT COALESCE(qq."lineageId", qq."id") AS lineage, qq."displayCode", qq."prompt", qq."sectionId", qq."questionnaireId", qq."sortOrder" '
                f'FROM "QuestionnaireQuestion" qq WHERE qq."isActive" AND qq."supersededById" IS NULL AND qq."questionnaireId" = ANY({b.arr(questionnaire_ids)})'
                + (f' AND (qq."prompt" ILIKE {b.p(like(q))} ESCAPE \'\\\' OR qq."displayCode" ILIKE {b.p(like(q))} ESCAPE \'\\\')' if q else "")
                + f' ORDER BY qq."sectionCode", qq."sortOrder" LIMIT {limit}', *b.params)
            out[name] = [{"id": str(r["lineage"]), "label": f'{r.get("displayCode") or ""} {r.get("prompt") or ""}'.strip(),
                          "displayCode": r.get("displayCode"), "sectionId": r.get("sectionId"),
                          "questionnaireId": str(r["questionnaireId"]) if r.get("questionnaireId") else None} for r in rows]
        elif name == "artisans":
            rows = await db.query_raw(
                'SELECT a."id", a."name", count(DISTINCT x."interviewId") AS n FROM "QuestionnaireInterviewArtisan" x '
                f'JOIN "QuestionnaireInterview" i ON i."id" = x."interviewId" AND {live} JOIN "Artisan" a ON a."id" = x."artisanId" '
                f'WHERE TRUE{text(ARTISAN_NAME, b)} GROUP BY a."id", a."name" ORDER BY n DESC, a."name" LIMIT {limit}', *b.params)
            out[name] = [{"id": str(r["id"]), "label": r["name"] or "", "count": int(r["n"])} for r in rows]
        elif name == "researchers":
            rows = await db.query_raw(
                'SELECT u."id", u."name", count(DISTINCT x."interviewId") AS n FROM "QuestionnaireInterviewResearcher" x '
                f'JOIN "QuestionnaireInterview" i ON i."id" = x."interviewId" AND {live} JOIN "User" u ON u."id" = x."userId" '
                f'WHERE TRUE{text(USER_NAME, b)} GROUP BY u."id", u."name" ORDER BY n DESC, u."name" LIMIT {limit}', *b.params)
            out[name] = [{"id": str(r["id"]), "label": r["name"] or "", "count": int(r["n"])} for r in rows]
        elif name == "workshops":
            out[name] = await _workshop_facets(db, q, limit)
        elif name == "languages":
            rows = await db.query_raw(
                f'SELECT i."languageCode" AS code, count(*) AS n FROM "QuestionnaireInterview" i WHERE {live} AND i."languageCode" IS NOT NULL '
                f'GROUP BY 1 ORDER BY n DESC LIMIT {limit}')
            out[name] = [{"id": r["code"], "label": r["code"], "count": int(r["n"])} for r in rows]
    return out


async def _workshop_facets(db: Any, q: str | None, limit: int) -> list[dict[str, Any]]:
    live = 'i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL'
    b = Sql()
    term = f" AND w.\"title\" ILIKE {b.p(like(q))} ESCAPE '\\'" if q else ""
    union = (
        f'SELECT w."id", w."title", \'FIELD\' AS type, count(i."id") AS n FROM "Workshop" w JOIN "QuestionnaireInterview" i '
        f'ON i."workshopId" = w."id" AND {live} WHERE TRUE{term} GROUP BY w."id", w."title"'
    )
    if _design_portal():
        dterm = f" AND d.\"title\" ILIKE {b.p(like(q))} ESCAPE '\\'" if q else ""
        union += (
            f' UNION ALL SELECT d."id", d."title", \'DESIGN\' AS type, count(i."id") AS n FROM "DesignWorkshop" d JOIN "QuestionnaireInterview" i '
            f'ON i."designWorkshopId" = d."id" AND i."workshopId" IS NULL AND {live} WHERE TRUE{dterm} GROUP BY d."id", d."title"'
        )
    rows = await db.query_raw(f"SELECT * FROM ({union}) x ORDER BY n DESC, title LIMIT {limit}", *b.params)
    return [{"id": str(r["id"]), "label": r["title"] or "", "type": r["type"], "count": int(r["n"])} for r in rows]
