"""Map every craft recorded before the master list, and every free-text craft, onto the list.

    cd backend && python -m app.scripts.map_crafts --dry-run          # the report, changes nothing
    cd backend && python -m app.scripts.map_crafts --apply            # do it
    cd backend && python -m app.scripts.map_crafts --dry-run --json   # the report as JSON

The same file in both portals. The production order is in docs/RELEASE.md: deploy, dry run, compare
with docs/CRAFTS.md, apply, dry run again (0 ``inPlace`` and 0 ``merge`` left). It is idempotent.

For each LEGACY craft, in this order (docs/CRAFTS.md, "Mapping"):

1. **Curated**: ``app/data/craft_master_aliases.json`` by old name: a serial, ``adopt``, or ``legacy``.
2. **Exact**: the normalised name is a master craft's name or slug.
3. **Alias**: the name is a known spelling of a shared code exactly one master craft carries.
4. **Fuzzy**, reported as ``proposed`` and never applied: token-set ratio at least 0.90, and at least
   0.05 better than the runner-up.

The first old craft matched to a master craft is updated IN PLACE onto it, keeping its id so no
reference moves, and the seeded duplicate is removed. Every further one is merged into it. An adopted
craft becomes ACTIVE under its own name. Anything else stays LEGACY; nothing is ever deleted.

Free text (the designer portal's workshop and plan ``craftName``, products with no ``craftId``, and
records holding ``extraMetadata.legacyCraftText``) takes steps 1 to 3, and a match sets ``craftId``.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import asdict, dataclass
from typing import Any

from app.services import craft_master as cm

#: The fuzzy step's thresholds.
FUZZY_MIN = 0.90
FUZZY_MARGIN = 0.05

#: Free-text sources: (kind label, delegate, text column or None for legacyCraftText, name column to
#: keep in step with the link or None). Delegates a portal lacks are skipped.
FREE_TEXT_SOURCES: tuple[tuple[str, str, str | None, str | None], ...] = (
    ("designWorkshop", "designworkshop", "craftName", "craftName"),
    ("annualPlanEntry", "annualplanentry", "craftName", "craftName"),
    ("product", "productdocumentation", "craftName", "craftName"),
    ("artisan", "artisan", None, None),
    ("tool", "tooldocumentation", None, "craftName"),
)


@dataclass
class Row:
    kind: str
    id: str
    oldName: str
    action: str
    target: str | None
    references: int
    source: str = ""
    targetId: str | None = None
    targetSlug: str | None = None
    subCategoryId: str | None = None
    score: float | None = None
    delegate: str = ""
    nameColumn: str | None = None


def _label(craft: Any) -> str:
    serial = getattr(craft, "sourceSerial", None)
    return f"{craft.slug} (#{serial})" if serial else str(craft.slug)


def _curated() -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    data = cm.load_aliases()
    crafts = {cm.normalise(e["oldName"]): e for e in data.get("crafts", [])}
    free = {cm.normalise(e["text"]): e for e in data.get("freeText", [])}
    return crafts, free


def _fuzzy(name: str, masters: list[Any]) -> tuple[Any | None, float]:
    scored = sorted(((cm.token_set_ratio(name, m.name), m.slug, m) for m in masters), key=lambda t: (-t[0], t[1]))
    if not scored:
        return None, 0.0
    best = scored[0]
    runner = scored[1][0] if len(scored) > 1 else 0.0
    if best[0] >= FUZZY_MIN and best[0] - runner >= FUZZY_MARGIN:
        return best[2], round(best[0], 3)
    return None, round(best[0], 3)


def _is_seeded(craft: Any) -> bool:
    """A master row exactly as the migration or the seed loader inserted it."""
    return craft.id == cm.master_id("c", craft.slug)


async def plan(client: Any) -> list[Row]:
    """The report: one row per LEGACY craft and per unlinked free-text record."""
    curated, curated_free = _curated()
    actives = await cm.active_crafts(client)
    masters = [c for c in actives if getattr(c, "sourceSerial", None) is not None]
    by_serial = {c.sourceSerial: c for c in masters}
    subs = {s.slug: s for s in await client.craftsubcategory.find_many()}
    legacy = await client.craft.find_many(where={"status": cm.LEGACY})

    def first(craft: Any) -> tuple:
        entry = curated.get(cm.normalise(craft.name)) or {}
        return (not entry.get("inPlace"), craft.createdAt, craft.id)

    rows: list[Row] = []
    claimed: set[str] = set()
    planned_names: dict[str, tuple[Row, Any]] = {}
    for craft in sorted(legacy, key=first):
        references = await cm.reference_count(client, craft.id)
        row = Row("craft", craft.id, craft.name, "unmatched", None, references, delegate="craft")
        entry = curated.get(cm.normalise(craft.name))
        target = None
        if entry is not None:
            row.source = "curated"
            if entry.get("legacy"):
                row.action = "legacy"
            elif entry.get("adopt"):
                sub = subs.get(entry["adopt"])
                if sub is not None:
                    row.action, row.target, row.subCategoryId = "adopt", sub.slug, sub.id
            elif entry.get("sourceSerial") is not None:
                target = by_serial.get(int(entry["sourceSerial"]))
        if entry is None:
            hits = cm.exact_matches(craft.name, masters)
            if len(hits) == 1:
                target, row.source = hits[0], "exact"
            else:
                hits = await cm.alias_matches(client, craft.name, masters)
                if len(hits) == 1:
                    target, row.source = hits[0], "alias"
                else:
                    proposed, score = _fuzzy(craft.name, masters)
                    row.score = score
                    if proposed is not None:
                        row.action, row.target, row.targetId, row.source = "proposed", _label(proposed), proposed.id, "fuzzy"
        if target is not None:
            row.target, row.targetId = _label(target), target.id
            if target.id not in claimed and _is_seeded(target) and await cm.reference_count(client, target.id) == 0:
                row.action = "inPlace"
            else:
                row.action = "merge"
            claimed.add(target.id)
        rows.append(row)
        planned_names[cm.normalise(craft.name)] = (row, craft)

    for _kind, delegate, column, name_column in FREE_TEXT_SOURCES:
        table = getattr(client, delegate, None)
        if table is None:
            continue
        if column is not None:
            records = await table.find_many(where={"craftId": None, column: {"not": None}})
        else:
            records = await table.find_many(where={"craftId": None})
        for record in records:
            text = getattr(record, column, None) if column else _legacy_text(record)
            if not (isinstance(text, str) and text.strip()):
                continue
            row = Row("freeText", record.id, text, "unmatched", None, 1, delegate=delegate, nameColumn=name_column)
            key = cm.normalise(text)
            entry = curated_free.get(key)
            target = None
            if entry is not None:
                row.source = "curated"
                if entry.get("legacy"):
                    row.action = "legacy"
                elif entry.get("sameAs"):
                    target = _resolve_same_as(entry["sameAs"], actives, planned_names, curated, by_serial)
                    if isinstance(target, tuple):
                        adopted = target[1]
                        row.action, row.target, row.targetId = "link", adopted.slug, adopted.id
                        row.targetSlug = adopted.slug
                        target = None
            elif key in curated and curated[key].get("sourceSerial") is not None:
                row.source = "curated"
                target = by_serial.get(int(curated[key]["sourceSerial"]))
            else:
                hits = cm.exact_matches(text, actives)
                if len(hits) == 1:
                    target, row.source = hits[0], "exact"
                else:
                    hits = await cm.alias_matches(client, text, actives)
                    if len(hits) == 1:
                        target, row.source = hits[0], "alias"
            if target is not None:
                row.action, row.target, row.targetId = "link", _label(target), target.id
                row.targetSlug = target.slug
            rows.append(row)
    return rows


def _legacy_text(record: Any) -> str | None:
    meta = getattr(record, "extraMetadata", None)
    if isinstance(meta, str):
        try:
            meta = json.loads(meta)
        except ValueError:
            return None
    return meta.get(cm.LEGACY_TEXT_KEY) if isinstance(meta, dict) else None


def _resolve_same_as(
    name: str, actives: list[Any], planned: dict[str, tuple[Row, Any]], curated: dict[str, dict[str, Any]], by_serial: dict
) -> Any:
    """The craft another old name maps to: an ACTIVE craft of that name, or that name's planned row."""
    hits = cm.exact_matches(name, actives)
    if len(hits) == 1:
        return hits[0]
    found = planned.get(cm.normalise(name))
    if found is not None and found[0].action == "adopt":
        return found
    entry = curated.get(cm.normalise(name)) or {}
    if entry.get("sourceSerial") is not None:
        return by_serial.get(int(entry["sourceSerial"]))
    return None


async def apply(client: Any, rows: list[Row]) -> dict[str, int]:
    """Carry out the planned rows: crafts first, then free text. Returns counts by action."""
    done: dict[str, int] = {}
    for row in rows:
        if row.kind != "craft" or row.action not in ("inPlace", "merge", "adopt"):
            continue
        async with client.tx() as tx:
            craft = await tx.craft.find_unique(where={"id": row.id})
            if craft is None or cm.status_of(craft) != cm.LEGACY:
                continue
            if row.action == "adopt":
                sub = await tx.craftsubcategory.find_unique(where={"id": row.subCategoryId}, include={"category": True})
                await tx.craft.update(
                    where={"id": craft.id},
                    data={"status": cm.ACTIVE, "subCategoryId": sub.id, "category": sub.category.name},
                )
            else:
                target = await tx.craft.find_unique(where={"id": row.targetId})
                if target is None:
                    continue
                if row.action == "inPlace" and _is_seeded(target) and await cm.reference_count(tx, target.id) == 0:
                    await _in_place(tx, craft, target)
                else:
                    await cm.merge_craft(tx, craft, target)
        done[row.action] = done.get(row.action, 0) + 1

    live = await cm.active_crafts(client)
    by_id = {c.id: c for c in live}
    by_slug = {c.slug: c for c in live}
    for row in rows:
        if row.kind != "freeText" or row.action != "link":
            continue
        craft = by_slug.get(row.targetSlug or "") or by_id.get(row.targetId or "")
        if craft is None:
            continue
        data: dict[str, Any] = {"craftId": craft.id}
        if row.nameColumn:
            data[row.nameColumn] = craft.name
        await getattr(client, row.delegate).update(where={"id": row.id}, data=data)
        # A tool's crafts are its ToolCraft rows; craftId and craftName are derived from them.
        if (
            row.delegate == "tooldocumentation"
            and await client.toolcraft.find_first(where={"toolId": row.id, "craftId": craft.id}) is None
        ):
            await client.toolcraft.create(data={"toolId": row.id, "craftId": craft.id})
        done["link"] = done.get("link", 0) + 1
    return done


async def _in_place(tx: Any, craft: Any, master: Any) -> None:
    """Move ``craft`` onto ``master``'s identity, keeping ``craft``'s id, and drop the seeded twin."""
    columns = {
        "name": master.name,
        "slug": master.slug,
        "subCategoryId": master.subCategoryId,
        "category": master.category,
        "sourceSerial": master.sourceSerial,
        "stateOfOrigin": list(master.stateOfOrigin or []),
        "stateOfPractice": list(master.stateOfPractice or []),
        "giStatus": cm.enum_value(master.giStatus),
        "isCatchAll": bool(master.isCatchAll),
        "sourceNotes": list(master.sourceNotes or []),
        "status": cm.ACTIVE,
        "craftCode": getattr(craft, "craftCode", None) or getattr(master, "craftCode", None),
        "extraMetadata": cm.as_json(cm.with_alias(getattr(craft, "extraMetadata", None), craft.name)),
    }
    await tx.craft.delete(where={"id": master.id})
    await tx.craft.update(where={"id": craft.id}, data=columns)
    moved = await tx.craft.find_unique(where={"id": craft.id})
    await cm.remember_spelling(tx, moved, craft.name, user_id=None)


def totals(rows: list[Row]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.action] = counts.get(row.action, 0) + 1
    return counts


def report(rows: list[Row]) -> list[dict[str, Any]]:
    keys = ("kind", "id", "oldName", "action", "target", "references")
    return [{k: v for k, v in asdict(row).items() if k in keys} for row in rows]


def table(rows: list[Row]) -> str:
    header = ("kind", "id", "oldName", "action", "target", "references")
    lines = [" | ".join(header)]
    for row in report(rows):
        lines.append(" | ".join(str(row[k]) if row[k] is not None else "" for k in header))
    return "\n".join(lines)


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Map crafts and free-text craft names onto the master list.")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    from app.core.db import connect_db, db, disconnect_db

    await connect_db()
    try:
        rows = await plan(db)
        applied = await apply(db, rows) if args.apply else {}
    finally:
        await disconnect_db()
    if args.json:
        print(json.dumps({"rows": report(rows), "totals": totals(rows), "applied": applied}, indent=2))
    else:
        print(table(rows))
        print()
        print("totals: " + ", ".join(f"{k} {v}" for k, v in sorted(totals(rows).items())) if rows else "totals: none")
        if args.apply:
            print("applied: " + ", ".join(f"{k} {v}" for k, v in sorted(applied.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
