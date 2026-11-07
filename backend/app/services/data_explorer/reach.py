from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from app.core.db import db

NO_CRAFT = "_none"
ID_CHUNK = 5000
INTERVIEW_TAGS = ("questionnaire", "questionnaireinterview")

_WORKSHOP_ARTISANS_SQL = (
    'SELECT "workshopId", "artisanId" FROM "WorkshopArtisan" WHERE "workshopId" = ANY($1::text[])'
)
_WORKSHOP_CRAFTS_SQL = (
    'SELECT wc."workshopId", wc."craftId" FROM "WorkshopCraft" wc '
    'JOIN "Craft" c ON c."id" = wc."craftId" WHERE wc."workshopId" = ANY($1::text[])'
)
_FILED_TOOLS_SQL = (
    'SELECT t."id", t."workshopId", t."artisanId", '
    'coalesce(array_agg(ta."artisanId") FILTER (WHERE ta."artisanId" IS NOT NULL), ARRAY[]::text[]) AS "links" '
    'FROM "ToolDocumentation" t LEFT JOIN "ToolArtisan" ta ON ta."toolId" = t."id" '
    'WHERE t."workshopId" = ANY($1::text[]) GROUP BY t."id"'
)
_ARTISANS_SQL = (
    'SELECT a."id", a."workshopId", a."craftId", (c."id" IS NOT NULL) AS "craftExists" '
    'FROM "Artisan" a LEFT JOIN "Craft" c ON c."id" = a."craftId" '
    'WHERE a."workshopId" = ANY($1::text[]) OR a."id" = ANY($2::text[]) OR a."craftId" = ANY($3::text[])'
)
_PRODUCTS_SQL = (
    'SELECT "id", "artisanId", "workshopId" FROM "ProductDocumentation" WHERE "artisanId" = ANY($1::text[])'
)
_PROCESSES_SQL = 'SELECT "id", "productId" FROM "Process" WHERE "productId" = ANY($1::text[])'
_STEPS_SQL = 'SELECT "id", "processId" FROM "ProcessStep" WHERE "processId" = ANY($1::text[])'
_TOOLS_SQL = (
    'SELECT t."id", t."workshopId", t."artisanId" AS "linkedArtisan" FROM "ToolDocumentation" t '
    'WHERE t."artisanId" = ANY($1::text[]) '
    'UNION SELECT t."id", t."workshopId", ta."artisanId" FROM "ToolDocumentation" t '
    'JOIN "ToolArtisan" ta ON ta."toolId" = t."id" WHERE ta."artisanId" = ANY($1::text[])'
)
_INTERVIEWS_SQL = (
    'SELECT "interviewId", "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "artisanId" = ANY($1::text[])'
)
_MEDIA_COLUMNS = (
    'm."id", m."sizeBytes", m."workshopId", m."craftId", m."artisanId", m."productId", m."toolId", '
    'm."questionnaireInterviewId", m."linkedRecordType", m."linkedRecordId"'
)
_MEDIA_SQL = (
    f"SELECT {_MEDIA_COLUMNS} FROM \"MediaFile\" m WHERE m.\"removedAt\" IS NULL AND ("
    'm."workshopId" = ANY($1::text[]) OR m."craftId" = ANY($2::text[]) OR m."artisanId" = ANY($3::text[]) '
    'OR m."productId" = ANY($4::text[]) OR m."toolId" = ANY($5::text[]) '
    'OR m."questionnaireInterviewId" = ANY($6::text[]) OR m."linkedRecordId" = ANY($7::text[]))'
)

Totals = dict[str, tuple[int, int]]


@dataclass
class Facts:
    workshop_artisans: dict[str, set[str]] = field(default_factory=lambda: defaultdict(set))
    workshop_crafts: dict[str, set[str]] = field(default_factory=lambda: defaultdict(set))
    filed: dict[str, list[tuple[str, list[str]]]] = field(default_factory=lambda: defaultdict(list))
    artisans: dict[str, tuple[str | None, str | None, bool]] = field(default_factory=dict)
    products: dict[str, list[tuple[str, str | None]]] = field(default_factory=lambda: defaultdict(list))
    processes: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))
    steps: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))
    tools: dict[str, list[tuple[str, str | None]]] = field(default_factory=lambda: defaultdict(list))
    interviews: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))
    sizes: dict[str, int] = field(default_factory=dict)
    by_fk: dict[tuple[str, str], set[str]] = field(default_factory=lambda: defaultdict(set))
    by_link: dict[tuple[str, str], set[str]] = field(default_factory=lambda: defaultdict(set))
    loose: set[str] = field(default_factory=set)
    untyped: set[str] = field(default_factory=set)
    visible: dict[str, set[str] | None] = field(default_factory=dict)

    def sees(self, kind: str, rec_id: str) -> bool:
        allowed = self.visible.get(kind)
        return allowed is None or rec_id in allowed


def _chunks(ids: list[str]) -> Iterable[list[str]]:
    for start in range(0, len(ids), ID_CHUNK):
        yield ids[start : start + ID_CHUNK]


async def _visible(delegate: Any, ids: Iterable[str], where: dict[str, Any] | None) -> set[str] | None:
    if not where:
        return None
    wanted = sorted({i for i in ids if i})
    found: set[str] = set()
    for part in _chunks(wanted):
        rows = await delegate.group_by(by=["id"], where={"AND": [{"id": {"in": part}}, where]})
        found |= {str(row["id"]) for row in rows}
    return found


async def _rows(sql: str, *args: Any) -> list[dict[str, Any]]:
    return list(await db.query_raw(sql, *args))


def _interview_where(browser: Any, scope: Any) -> dict[str, Any]:
    live = getattr(browser, "_live", None)
    records = getattr(scope, "records", None) or {}
    return live(records) if callable(live) else records


def _media_where(scope: Any) -> dict[str, Any] | None:
    media = getattr(scope, "media", None)
    return {"AND": [{"removedAt": None}, media]} if media else None


async def load(workshop_ids: list[str], scope: Any, browser: Any) -> Facts:
    facts = Facts()
    records = getattr(scope, "records", None) or None
    wids = sorted(set(workshop_ids))
    for row in await _rows(_WORKSHOP_ARTISANS_SQL, wids):
        facts.workshop_artisans[str(row["workshopId"])].add(str(row["artisanId"]))
    for row in await _rows(_WORKSHOP_CRAFTS_SQL, wids):
        facts.workshop_crafts[str(row["workshopId"])].add(str(row["craftId"]))
    tool_artisans: set[str] = set()
    for row in await _rows(_FILED_TOOLS_SQL, wids):
        linked = [str(row["artisanId"])] if row.get("artisanId") else []
        linked += [str(a) for a in row.get("links") or [] if a and str(a) not in linked]
        facts.filed[str(row["workshopId"])].append((str(row["id"]), linked))
        tool_artisans.update(linked)
    linked_ids = sorted({a for ids in facts.workshop_artisans.values() for a in ids} | tool_artisans)
    craft_ids = sorted({c for ids in facts.workshop_crafts.values() for c in ids})
    for row in await _rows(_ARTISANS_SQL, wids, linked_ids, craft_ids):
        facts.artisans[str(row["id"])] = (
            row.get("workshopId"),
            row.get("craftId"),
            bool(row.get("craftExists")),
        )
    aids = sorted(facts.artisans)
    for row in await _rows(_PRODUCTS_SQL, aids):
        facts.products[str(row["artisanId"])].append((str(row["id"]), row.get("workshopId")))
    pids = sorted({p for items in facts.products.values() for p, _w in items})
    for row in await _rows(_PROCESSES_SQL, pids):
        facts.processes[str(row["productId"])].append(str(row["id"]))
    prids = sorted({p for items in facts.processes.values() for p in items})
    for row in await _rows(_STEPS_SQL, prids):
        facts.steps[str(row["processId"])].append(str(row["id"]))
    sids = sorted({s for items in facts.steps.values() for s in items})
    for row in await _rows(_TOOLS_SQL, aids):
        pair = (str(row["id"]), row.get("workshopId"))
        if pair not in facts.tools[str(row["linkedArtisan"])]:
            facts.tools[str(row["linkedArtisan"])].append(pair)
    tids = sorted({t for items in facts.tools.values() for t, _w in items} | {t for items in facts.filed.values() for t, _a in items})
    for row in await _rows(_INTERVIEWS_SQL, aids):
        facts.interviews[str(row["artisanId"])].append(str(row["interviewId"]))
    iids = sorted({i for items in facts.interviews.values() for i in items})
    every = sorted(set(wids) | set(craft_ids) | {a[1] for a in facts.artisans.values() if a[1]} | set(aids)
                   | set(pids) | set(prids) | set(sids) | set(tids) | set(iids))
    crafts = sorted(set(craft_ids) | {a[1] for a in facts.artisans.values() if a[1]})
    for row in await _rows(_MEDIA_SQL, wids, crafts, aids, pids, tids, iids, every):
        mid = str(row["id"])
        facts.sizes[mid] = int(row.get("sizeBytes") or 0)
        for key in ("workshopId", "craftId", "artisanId", "productId", "toolId", "questionnaireInterviewId"):
            if row.get(key):
                facts.by_fk[(key, str(row[key]))].add(mid)
        if row.get("linkedRecordType") and row.get("linkedRecordId"):
            facts.by_link[(str(row["linkedRecordType"]), str(row["linkedRecordId"]))].add(mid)
        if row.get("linkedRecordType") is None:
            facts.untyped.add(mid)
        if not any(row.get(k) for k in ("artisanId", "productId", "toolId", "questionnaireInterviewId")):
            facts.loose.add(mid)
    facts.visible = {
        "artisan": await _visible(db.artisan, aids, records),
        "product": await _visible(db.productdocumentation, pids, records),
        "process": await _visible(db.process, prids, records),
        "tool": await _visible(db.tooldocumentation, tids, records),
        "interview": await _visible(db.questionnaireinterview, iids, _interview_where(browser, scope) or None),
        "media": await _visible(db.mediafile, list(facts.sizes), _media_where(scope)),
    }
    return facts


class Tree:
    def __init__(self, facts: Facts) -> None:
        self.f = facts

    def _media(self, ids: Iterable[str]) -> set[str]:
        return {m for m in ids if self.f.sees("media", m)}

    def _record(self, fk: str, tags: tuple[str, ...], rec_id: str) -> set[str]:
        found = set(self.f.by_fk.get((fk, rec_id), set()))
        for tag in tags:
            found |= self.f.by_link.get((tag, rec_id), set())
        return self._media(found)

    def linked(self, wid: str) -> list[str]:
        f = self.f
        crafts = f.workshop_crafts.get(wid, set())
        members = f.workshop_artisans.get(wid, set())
        reached = [
            aid
            for aid, (home, craft, _exists) in f.artisans.items()
            if (home == wid or aid in members or (craft and craft in crafts)) and f.sees("artisan", aid)
        ]
        seen = set(reached)
        orphans: list[str] = []
        for tid, artisans in f.filed.get(wid, []):
            if not f.sees("tool", tid) or (seen and any(a in seen for a in artisans)):
                continue
            orphans.extend(artisans)
        extra = [a for a in dict.fromkeys(orphans) if a in f.artisans and a not in seen and f.sees("artisan", a)]
        return reached + extra

    def crafts(self, wid: str) -> tuple[set[str], bool]:
        listed = set(self.f.workshop_crafts.get(wid, set()))
        none = False
        for aid in self.linked(wid):
            _home, craft, exists = self.f.artisans[aid]
            if craft and exists:
                listed.add(craft)
            elif not craft:
                none = True
        return listed, none

    def misc(self, wid: str) -> set[str]:
        found = self.f.by_fk.get(("workshopId", wid), set()) | self.f.by_link.get(("workshop", wid), set())
        return self._media(found & self.f.loose)

    def craft(self, wid: str, cid: str) -> set[str]:
        out: set[str] = set()
        if cid != NO_CRAFT:
            exists = cid in self.f.workshop_crafts.get(wid, set()) or any(
                craft == cid and ok for _h, craft, ok in self.f.artisans.values()
            )
            if not exists:
                return out
            out |= self._record("craftId", ("craft",), cid)
        for aid in self.linked(wid):
            craft = self.f.artisans[aid][1]
            if (cid == NO_CRAFT and not craft) or craft == cid:
                out |= self.artisan(wid, aid)
        return out

    def workshop(self, wid: str) -> set[str]:
        out = self.misc(wid)
        listed, none = self.crafts(wid)
        for cid in [*listed, *([NO_CRAFT] if none else [])]:
            out |= self.craft(wid, cid)
        return out

    def own(self, aid: str) -> set[str]:
        typed = self.f.by_link.get(("artisan", aid), set())
        bare = self.f.by_fk.get(("artisanId", aid), set()) & self.f.untyped
        return self._media(typed | bare)

    def products(self, wid: str, aid: str) -> list[str]:
        return [
            pid for pid, home in self.f.products.get(aid, []) if home in (wid, None) and self.f.sees("product", pid)
        ]

    def process_list(self, pid: str) -> list[str]:
        return [p for p in self.f.processes.get(pid, []) if self.f.sees("process", p)]

    def step(self, sid: str) -> set[str]:
        return self._media(self.f.by_link.get(("processstep", sid), set()))

    def process(self, prid: str) -> set[str]:
        out = self._media(self.f.by_link.get(("process", prid), set()))
        for sid in self.f.steps.get(prid, []):
            out |= self.step(sid)
        return out

    def product(self, pid: str) -> set[str]:
        out = self._record("productId", ("product",), pid)
        for prid in self.process_list(pid):
            out |= self.process(prid)
        return out

    def processes(self, pid: str) -> set[str]:
        out: set[str] = set()
        for prid in self.process_list(pid):
            out |= self.process(prid)
        return out

    def member(self, aid: str, wid: str) -> bool:
        home = self.f.artisans.get(aid, (None, None, False))[0]
        return home == wid or aid in self.f.workshop_artisans.get(wid, set())

    def tools(self, wid: str, aid: str) -> list[str]:
        member = self.member(aid, wid)
        return [
            tid
            for tid, home in self.f.tools.get(aid, [])
            if (home in (wid, None) or member) and self.f.sees("tool", tid)
        ]

    def tool(self, tid: str) -> set[str]:
        return self._record("toolId", ("tool",), tid)

    def interview_list(self, aid: str) -> list[str]:
        return [i for i in dict.fromkeys(self.f.interviews.get(aid, [])) if self.f.sees("interview", i)]

    def interview(self, iid: str) -> set[str]:
        return self._record("questionnaireInterviewId", INTERVIEW_TAGS, iid)

    def branch(self, wid: str, aid: str, name: str) -> set[str]:
        out: set[str] = set()
        if name == "products":
            for pid in self.products(wid, aid):
                out |= self.product(pid)
        elif name == "tools":
            for tid in self.tools(wid, aid):
                out |= self.tool(tid)
        elif name == "questionnaire":
            for iid in self.interview_list(aid):
                out |= self.interview(iid)
        return out

    def artisan(self, wid: str, aid: str) -> set[str]:
        if aid not in self.f.artisans or not self.f.sees("artisan", aid):
            return set()
        out = self.own(aid)
        for name in ("products", "tools", "questionnaire"):
            out |= self.branch(wid, aid, name)
        return out

    def totals(self, sets: dict[str, set[str]]) -> Totals:
        return {key: (len(ids), sum(self.f.sizes.get(m, 0) for m in ids)) for key, ids in sets.items()}


def _artisan_tail(segs: list[str]) -> list[str] | None:
    if len(segs) >= 6 and segs[2] == "crafts" and segs[4] == "artisans":
        return segs[5:]
    if len(segs) >= 4 and segs[2] == "artisans":
        return segs[3:]
    return None


async def workshop_totals(segs: list[str], keys: list[str], scope: Any, browser: Any) -> Totals | None:
    if len(segs) == 1:
        tree = Tree(await load(keys, scope, browser))
        return tree.totals({wid: tree.workshop(wid) for wid in keys})
    wid = segs[1]
    tree = Tree(await load([wid], scope, browser))
    if len(segs) == 2 or (len(segs) == 3 and segs[2] == "crafts"):
        return tree.totals({cid: tree.craft(wid, cid) for cid in keys})
    if (len(segs) == 4 and segs[2] == "crafts") or (len(segs) == 5 and segs[2] == "crafts" and segs[4] == "artisans"):
        return tree.totals({aid: tree.artisan(wid, aid) for aid in keys})
    if len(segs) == 3 and segs[2] == "artisans":
        return tree.totals({aid: tree.artisan(wid, aid) for aid in keys})
    tail = _artisan_tail(segs)
    if tail is None:
        return None
    aid = tail[0]
    if len(tail) == 1:
        return tree.totals({name: tree.branch(wid, aid, name) for name in keys})
    branch = tail[1]
    if branch == "products":
        if len(tail) == 2:
            return tree.totals({pid: tree.product(pid) for pid in keys})
        if len(tail) == 3:
            return tree.totals({name: tree.processes(tail[2]) if name == "processes" else set() for name in keys})
        if len(tail) == 4 and tail[3] == "processes":
            return tree.totals({prid: tree.process(prid) for prid in keys})
        if len(tail) == 5 and tail[3] == "processes":
            return tree.totals({sid: tree.step(sid) for sid in keys})
    if branch == "tools" and len(tail) == 2:
        return tree.totals({tid: tree.tool(tid) for tid in keys})
    if branch == "questionnaire" and len(tail) == 2:
        return tree.totals({iid: tree.interview(iid) for iid in keys})
    return None
