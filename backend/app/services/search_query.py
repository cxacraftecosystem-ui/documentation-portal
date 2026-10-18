"""The ranking query behind ``GET /search/unified`` and the peer search (docs/SEARCH.md).

ONE STATEMENT, ONE ROUND TRIP. It returns the page of items for every asked type and each type's
total, so a search holds one of the production pool's five connections for one query.

Three candidate lists, each capped per type:

* ``fts``  — ``tsv @@ (english OR simple websearch query)``, by ``ts_rank_cd``. Stems ("dyes" finds
  "dyeing") through ``english``; any script, Devanagari included, through ``simple``.
* ``trgm`` — ``word_similarity(lower(q), lower(title)) >= 0.45``. Typos and names: "indgo" scores
  0.5 against "indigo", under pg_trgm's default ``<%`` threshold of 0.6, which is why the threshold
  is spelled out here (:data:`TRIGRAM_THRESHOLD`) rather than taken from the operator.
* ``vec``  — only in hybrid mode: nearest embeddings by cosine distance.

They are fused by reciprocal rank, ``score = Σ 1/(60 + rank)``, and every list applies the same
permission and filter predicates (:class:`Scope`), so nothing outside the caller's view is ever
ranked, counted or snippeted. ``base`` is ``NOT MATERIALIZED`` so each list reads the index through
its own access path (the GIN index for ``fts``) instead of copying every permitted body first.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from app.core.db import db

#: Candidates per list and type before fusion.
CANDIDATES = 200
#: Lowest word similarity that counts as a name or typo match.
TRIGRAM_THRESHOLD = 0.45
#: Reciprocal-rank constant.
RRF_K = 60
#: Nearest-neighbour candidates further than this cosine distance are not meaning matches.
MAX_VECTOR_DISTANCE = 0.65


@dataclass
class Scope:
    """Who is asking and what they narrowed to. Every field becomes a predicate in every list."""

    types: list[str]
    may_read_dw: bool = False
    visible_dw_ids: list[str] = field(default_factory=list)
    shareable_only: bool = False
    #: Peer answers only: every artisan the record names has granted sharing consent, re-checked live.
    require_artisan_consent: bool = False
    craft_ids: list[str] = field(default_factory=list)
    craft_codes: list[str] = field(default_factory=list)
    place: str | None = None
    date_from: datetime | None = None
    date_to: datetime | None = None
    workshop_ids: list[str] = field(default_factory=list)


@dataclass
class Result:
    mode: str
    #: type -> list of item dicts, best first.
    items: dict[str, list[dict[str, Any]]]
    #: type -> total matches.
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
    return " AND ".join(clauses)


def build_sql(
    q: str, scope: Scope, *, limit: int, offset: int = 0, qvec: str | None = None
) -> tuple[str, list[Any]]:
    """The statement and its parameters. Pure, so its shape can be tested without a database."""
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
    """Character ranges in ``text`` where a query word (or its stem) starts a word."""
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
    q: str, scope: Scope, *, limit: int, offset: int = 0, qvec: list[float] | None = None
) -> Result:
    """Ranked items and totals for every type in ``scope.types``."""
    from app.services.search_embeddings import vector_literal

    if not scope.types:
        return Result(mode="hybrid" if qvec else "text", items={}, totals={})
    sql, params = build_sql(
        q, scope, limit=limit, offset=offset, qvec=vector_literal(qvec) if qvec else None
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
