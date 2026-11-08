from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from app.core.db import db

CANDIDATES = 200
TRIGRAM_THRESHOLD = 0.45
RRF_K = 60
MAX_VECTOR_DISTANCE = 0.65

SORT_KEYS: tuple[str, ...] = ("relevance", "name", "recorded")
SORT_DIRS: dict[str, str] = {"asc": "ASC", "desc": "DESC"}
DEFAULT_SORT: tuple[str, str] = ("relevance", "desc")


@dataclass
class Scope:
    types: list[str]
    may_read_dw: bool = False
    visible_dw_ids: list[str] = field(default_factory=list)
    shareable_only: bool = False
    require_artisan_consent: bool = False
    craft_ids: list[str] = field(default_factory=list)
    craft_codes: list[str] = field(default_factory=list)
    place: str | None = None
    date_from: datetime | None = None
    date_to: datetime | None = None
    workshop_ids: list[str] = field(default_factory=list)
    artisan_ids: list[str] = field(default_factory=list)
    media_transcript: str | None = None


@dataclass
class Result:
    mode: str
    items: dict[str, list[dict[str, Any]]]
    totals: dict[str, int]


class _Params:
    def __init__(self) -> None:
        self.values: list[Any] = []

    def add(self, value: Any) -> str:
        self.values.append(value)
        return f"${len(self.values)}"

    def array(self, values: list[str]) -> str:
        return (
            f"ARRAY(SELECT jsonb_array_elements_text({self.add(json.dumps(list(values)))}::jsonb))"
        )


def _like(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{escaped}%"


def _where(scope: Scope, p: _Params) -> str:
    clauses = [f'd."entityType" = ANY({p.array(scope.types)})']
    clauses.append(
        "(d.\"audience\" = 'ALL' OR (d.\"audience\" = 'DW' AND ("
        f'{p.add(bool(scope.may_read_dw))}::boolean OR d."designWorkshopId" = ANY({p.array(scope.visible_dw_ids)}))))'
    )
    if scope.shareable_only:
        clauses.append('d."shareable"')
    if scope.require_artisan_consent:
        clauses.append(
            'NOT EXISTS (SELECT 1 FROM unnest(d."artisanIds") AS a(id) '
            'LEFT JOIN "ArtisanSharingConsent" c ON c."artisanId" = a.id '
            'WHERE c."granted" IS NOT TRUE)'
        )
    if scope.craft_ids:
        clauses.append(f'd."craftIds" && {p.array(scope.craft_ids)}')
    if scope.craft_codes:
        clauses.append(f'd."craftCodes" && {p.array(scope.craft_codes)}')
    if scope.place:
        like = p.add(_like(scope.place))
        clauses.append(f'(d."place" ILIKE {like} OR d."subtitle" ILIKE {like})')
    if scope.artisan_ids:
        clauses.append(f'd."artisanIds" && {p.array(scope.artisan_ids)}')
    if scope.date_from:
        clauses.append(f'd."recordDate" >= {p.add(scope.date_from.isoformat())}::timestamptz')
    if scope.date_to:
        clauses.append(f'd."recordDate" <= {p.add(scope.date_to.isoformat())}::timestamptz')
    if scope.workshop_ids:
        real = [w for w in scope.workshop_ids if w != "none"]
        parts = []
        if real:
            parts.append(f'd."workshopIds" && {p.array(real)}')
        if "none" in scope.workshop_ids:
            parts.append('cardinality(d."workshopIds") = 0')
        clauses.append("(" + " OR ".join(parts) + ")")
    if scope.media_transcript in ("yes", "no"):
        found = "EXISTS" if scope.media_transcript == "yes" else "NOT EXISTS"
        clauses.append(
            "(d.\"entityType\" NOT IN ('media', 'transcripts') OR "
            f"{found} (SELECT 1 FROM \"MediaFile\" hm WHERE hm.\"id\" = d.\"entityId\" "
            "AND hm.\"transcriptText\" IS NOT NULL AND hm.\"transcriptText\" <> ''))"
        )
    return " AND ".join(clauses)


def parse_sort(raw: Any) -> tuple[str, str]:
    if raw is None:
        return DEFAULT_SORT
    key, sep, direction = str(raw).strip().partition(":")
    if not sep or key not in SORT_KEYS or direction not in SORT_DIRS:
        raise ValueError(raw)
    if key == "relevance" and direction != "desc":
        raise ValueError(raw)
    return key, direction


def sort_text(sort: tuple[str, str]) -> str:
    return f"{sort[0]}:{sort[1]}"


_NAME_KEY = 'lower(h."title") COLLATE "C"'
_ID_KEY = 'h."entityId" COLLATE "C" ASC'


def order_by(sort: tuple[str, str]) -> str:
    key, direction = sort
    if key not in SORT_KEYS[1:] or direction not in SORT_DIRS:
        raise ValueError(sort_text(sort))
    word = SORT_DIRS[direction]
    if key == "name":
        return f"{_NAME_KEY} {word} NULLS LAST, {_ID_KEY}"
    return f'h."recordDate" {word} NULLS LAST, {_NAME_KEY} ASC, {_ID_KEY}'


_ITEMS_SQL = """
  coalesce((SELECT json_agg(json_build_object(
      'id', pg."entityId", 'type', pg."entityType", 'title', pg."title", 'subtitle', pg."subtitle",
      'place', pg."place", 'craftCodes', pg."craftCodes", 'artisanIds', pg."artisanIds",
      'recordDate', pg."recordDate", 'openPath', pg."openPath", 'score', round(pg.score::numeric, 6),
      'snippet', CASE WHEN pg."body" = '' THEN '' ELSE ts_headline('english', pg."body", q.tsq,
          'MaxWords=30, MinWords=12, StartSel="", StopSel="", HighlightAll=false') END
    ) ORDER BY pg."entityType", pg.pos) FROM page pg, q), '[]'::json) AS items"""


def _sorted_sql(
    q: str, scope: Scope, sort: tuple[str, str], *, limit: int, offset: int, qvec: str | None
) -> tuple[str, list[Any]]:
    p = _Params()
    query = p.add(q)
    where = _where(scope, p)
    lo = p.add(offset)
    hi = p.add(offset + limit)
    vec = ""
    if qvec is not None:
        vref = p.add(qvec)
        vec = (
            f' OR (b."embedding" IS NOT NULL'
            f' AND (b."embedding" <=> {vref}::vector) <= {MAX_VECTOR_DISTANCE})'
        )
    sql = f"""
WITH q AS (
  SELECT websearch_to_tsquery('english', {query}) || websearch_to_tsquery('simple', {query}) AS tsq,
         lower({query}) AS lq
),
base AS NOT MATERIALIZED (SELECT d.* FROM "SearchDocument" d WHERE {where}),
hits AS (
  SELECT b.* FROM base b, q
   WHERE b."tsv" @@ q.tsq OR word_similarity(q.lq, lower(b."title")) >= {TRIGRAM_THRESHOLD}{vec}
),
ranked AS (
  SELECT h.*, 0::float8 AS score,
         row_number() OVER (PARTITION BY h."entityType" ORDER BY {order_by(sort)}) AS pos,
         count(*) OVER (PARTITION BY h."entityType") AS fused_n
    FROM hits h
),
page AS (SELECT * FROM ranked WHERE pos > {lo} AND pos <= {hi})
SELECT{_ITEMS_SQL},
  coalesce((SELECT json_object_agg(t."entityType", t.n) FROM (
      SELECT "entityType", max(fused_n)::int AS n FROM ranked GROUP BY 1
  ) t), '{{}}'::json) AS totals
"""
    return sql, p.values


def build_sql(
    q: str,
    scope: Scope,
    *,
    limit: int,
    offset: int = 0,
    qvec: str | None = None,
    sort: tuple[str, str] | None = None,
) -> tuple[str, list[Any]]:
    if sort is not None and sort != DEFAULT_SORT:
        return _sorted_sql(q, scope, sort, limit=limit, offset=offset, qvec=qvec)
    p = _Params()
    query = p.add(q)
    where = _where(scope, p)
    cap = max(CANDIDATES, offset + limit)
    cap_ref = p.add(cap)
    lo = p.add(offset)
    hi = p.add(offset + limit)
    vec_cte = ""
    vec_union = ""
    if qvec is not None:
        vref = p.add(qvec)
        vec_cte = f""",
vec AS (
  SELECT id, "entityType", row_number() OVER (PARTITION BY "entityType" ORDER BY dist, id) AS r
    FROM (SELECT b.id, b."entityType", b."embedding" <=> {vref}::vector AS dist
            FROM base b WHERE b."embedding" IS NOT NULL) v
   WHERE dist <= {MAX_VECTOR_DISTANCE}
)"""
        vec_union = "UNION ALL SELECT id, r FROM vec WHERE r <= cap.n"
    sql = f"""
WITH q AS (
  SELECT websearch_to_tsquery('english', {query}) || websearch_to_tsquery('simple', {query}) AS tsq,
         lower({query}) AS lq
),
cap AS (SELECT {cap_ref}::int AS n),
base AS NOT MATERIALIZED (SELECT d.* FROM "SearchDocument" d WHERE {where}),
fts AS (
  SELECT id, row_number() OVER (PARTITION BY "entityType" ORDER BY rank DESC, id) AS r
    FROM (SELECT b.id, b."entityType", ts_rank_cd(b."tsv", q.tsq) AS rank
            FROM base b, q WHERE b."tsv" @@ q.tsq) f
),
trgm AS (
  SELECT id, row_number() OVER (PARTITION BY "entityType" ORDER BY sim DESC, id) AS r
    FROM (SELECT b.id, b."entityType", word_similarity(q.lq, lower(b."title")) AS sim
            FROM base b, q
           WHERE word_similarity(q.lq, lower(b."title")) >= {TRIGRAM_THRESHOLD}) t
){vec_cte},
fused AS (
  SELECT id, sum(1.0 / ({RRF_K} + r)) AS score FROM (
    SELECT id, r FROM fts, cap WHERE r <= cap.n
    UNION ALL SELECT id, r FROM trgm, cap WHERE r <= cap.n
    {vec_union.replace("FROM vec", "FROM vec, cap")}
  ) hits GROUP BY id
),
counted AS (
  SELECT b."entityType", count(*)::int AS n
    FROM base b, q
   WHERE b."tsv" @@ q.tsq OR word_similarity(q.lq, lower(b."title")) >= {TRIGRAM_THRESHOLD}
   GROUP BY b."entityType"
),
ranked AS (
  SELECT b.*, f.score,
         row_number() OVER (PARTITION BY b."entityType" ORDER BY f.score DESC, b.id) AS pos,
         count(*) OVER (PARTITION BY b."entityType") AS fused_n
    FROM fused f JOIN base b ON b.id = f.id
),
page AS (SELECT * FROM ranked WHERE pos > {lo} AND pos <= {hi})
SELECT
  coalesce((SELECT json_agg(json_build_object(
      'id', pg."entityId", 'type', pg."entityType", 'title', pg."title", 'subtitle', pg."subtitle",
      'place', pg."place", 'craftCodes', pg."craftCodes", 'artisanIds', pg."artisanIds",
      'recordDate', pg."recordDate", 'openPath', pg."openPath", 'score', round(pg.score::numeric, 6),
      'snippet', CASE WHEN pg."body" = '' THEN '' ELSE ts_headline('english', pg."body", q.tsq,
          'MaxWords=30, MinWords=12, StartSel="", StopSel="", HighlightAll=false') END
    ) ORDER BY pg."entityType", pg.pos) FROM page pg, q), '[]'::json) AS items,
  coalesce((SELECT json_object_agg(t."entityType", t.n) FROM (
      SELECT coalesce(c."entityType", r."entityType") AS "entityType",
             greatest(coalesce(c.n, 0), coalesce(r.fused_n, 0))::int AS n
        FROM counted c
        FULL JOIN (SELECT "entityType", max(fused_n) AS fused_n FROM ranked GROUP BY 1) r
          ON r."entityType" = c."entityType"
  ) t), '{{}}'::json) AS totals
"""
    return sql, p.values


def _decode(value: Any, empty: Any) -> Any:
    if value is None:
        return empty
    if isinstance(value, str | bytes):
        return json.loads(value)
    return value


_WORD = re.compile(r"\w+", re.UNICODE)


def highlights(text: str, q: str) -> list[list[int]]:
    if not text:
        return []
    terms = {t.lower() for t in _WORD.findall(q) if len(t) >= 2}
    stems = {t[: max(3, len(t) - 2)] if len(t) > 4 else t for t in terms}
    ranges: list[list[int]] = []
    for match in _WORD.finditer(text):
        word = match.group(0).lower()
        if any(word.startswith(stem) for stem in stems):
            ranges.append([match.start(), match.end()])
    return ranges


async def run(
    q: str,
    scope: Scope,
    *,
    limit: int,
    offset: int = 0,
    qvec: list[float] | None = None,
    sort: tuple[str, str] | None = None,
) -> Result:
    from app.services.search_embeddings import vector_literal

    if not scope.types:
        return Result(mode="hybrid" if qvec else "text", items={}, totals={})
    sql, params = build_sql(
        q,
        scope,
        limit=limit,
        offset=offset,
        qvec=vector_literal(qvec) if qvec else None,
        sort=sort,
    )
    rows = await db.query_raw(sql, *params)
    row = rows[0] if rows else {}
    items: dict[str, list[dict[str, Any]]] = {}
    for item in _decode(row.get("items"), []):
        item["snippet"] = (item.get("snippet") or "").strip()
        item["highlights"] = highlights(item["snippet"], q)
        item["score"] = float(item.get("score") or 0)
        items.setdefault(item["type"], []).append(item)
    totals = {str(k): int(v) for k, v in _decode(row.get("totals"), {}).items()}
    return Result(mode="hybrid" if qvec else "text", items=items, totals=totals)


_TURN_SQL = """
SELECT DISTINCT ON (t."mediaFileId") t."mediaFileId" AS id, t."startMs" AS "startMs"
  FROM "TranscriptTurn" t
  JOIN "TranscriptVersion" v ON v."id" = t."versionId" AND v."supersededAt" IS NULL
 WHERE t."mediaFileId" = ANY($1::text[])
   AND coalesce(t."editedText", t."machineText") ILIKE ANY($2::text[])
 ORDER BY t."mediaFileId", t."position"
"""

_KIND_SQL = (
    'SELECT "id", "mediaType"::text AS "mediaType" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
)

_SUGGEST_SQL = """
SELECT w AS word, similarity(w, $1) AS score
  FROM (SELECT DISTINCT lower(part) AS w
          FROM "SearchDocument" d, regexp_split_to_table(d."title", '[^[:alnum:]]+') AS part
         WHERE d."audience" = 'ALL' AND length(part) >= 3) words
 WHERE similarity(w, $1) >= 0.35
 ORDER BY score DESC, w
 LIMIT 1
"""


def query_terms(q: str) -> list[str]:
    return [t for t in (w.lower() for w in _WORD.findall(q)) if len(t) >= 2]


async def transcript_moments(items: list[dict[str, Any]], q: str) -> None:
    ids = [str(item["id"]) for item in items if item.get("id")]
    if not ids:
        return
    patterns = [_like(term) for term in query_terms(q)] or [_like(q.strip())]
    moments: dict[str, int] = {}
    kinds: dict[str, str] = {}
    try:
        for row in await db.query_raw(_TURN_SQL, ids, patterns):
            moments[str(row["id"])] = int(row.get("startMs") or 0)
        for row in await db.query_raw(_KIND_SQL, ids):
            kinds[str(row["id"])] = str(row.get("mediaType") or "")
    except Exception:  # noqa: BLE001
        return
    for item in items:
        key = str(item.get("id"))
        if key in moments:
            item["startMs"] = moments[key]
        if kinds.get(key):
            item["mediaType"] = kinds[key]


async def did_you_mean(q: str) -> str | None:
    words = _WORD.findall(q)
    if not words:
        return None
    changed = False
    out: list[str] = []
    for word in words:
        best = None
        if len(word) >= 3:
            try:
                rows = await db.query_raw(_SUGGEST_SQL, word.lower())
            except Exception:  # noqa: BLE001
                return None
            best = str(rows[0]["word"]) if rows else None
        if best and best != word.lower():
            out.append(best)
            changed = True
        else:
            out.append(word)
    return " ".join(out) if changed else None
