from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import HTTPException

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
MASTER_FILE = DATA_DIR / "craft_master_list.json"
ALIASES_FILE = DATA_DIR / "craft_master_aliases.json"

MASTER_COUNT = 455

ACTIVE = "ACTIVE"
LEGACY = "LEGACY"
MERGED = "MERGED"
STATUSES = (ACTIVE, LEGACY, MERGED)

GI = "GI"
NON_GI = "NON_GI"
GI_STATUSES = (GI, NON_GI)

ALL_INDIA = "All India"

MESSAGES: dict[str, str] = {
    "CRAFT_EXISTS": "That craft is already in this sub-category.",
    "CRAFT_IN_USE": "Used by {n} records. Merge it into another craft instead.",
    "CATEGORY_NOT_EMPTY": "Move or delete its sub-categories first.",
    "SUB_CATEGORY_NOT_EMPTY": "Move or delete its crafts first.",
    "CRAFT_NOT_IN_LIST": "Pick a craft from the list.",
}

ALIASES_KEY = "aliases"
LEGACY_TEXT_KEY = "legacyCraftText"
MASTER_SEED_KEY = "masterSeed"
DELETED_MASTER_TYPE = "craftDeleted"

DIRECT_LINKS: tuple[tuple[str, str], ...] = (
    ("artisan", "craftId"),
    ("productdocumentation", "craftId"),
    ("tooldocumentation", "craftId"),
    ("mediafile", "craftId"),
)
OPTIONAL_LINKS: tuple[tuple[str, str], ...] = (
    ("designworkshop", "craftId"),
    ("annualplanentry", "craftId"),
)


def craft_error(code: str, status_code: int, **extra: Any) -> HTTPException:
    message = MESSAGES[code].format(n=extra.get("referenceCount", 0))
    return HTTPException(status_code=status_code, detail={"code": code, "message": message, **extra})


def etag_matches(if_none_match: str | None, etag: str) -> bool:
    def bare(tag: str) -> str:
        tag = tag.strip()
        return tag[2:] if tag[:2].upper() == "W/" else tag

    tags = [bare(tag) for tag in (if_none_match or "").split(",") if tag.strip()]
    return "*" in tags or bare(etag) in tags



@dataclass(frozen=True)
class MasterCraft:
    category: str
    category_slug: str
    sub_category: str
    sub_category_slug: str
    source_serial: int
    name: str
    slug: str
    state_of_origin: tuple[str, ...]
    state_of_practice: tuple[str, ...]
    gi_status: str
    notes: tuple[str, ...]

    @property
    def is_catch_all(self) -> bool:
        return is_catch_all(self.notes)


@dataclass(frozen=True)
class MasterSubCategory:
    slug: str
    name: str
    sort_order: int


@dataclass(frozen=True)
class MasterCategory:
    slug: str
    name: str
    sort_order: int
    sub_categories: tuple[MasterSubCategory, ...]


def is_catch_all(notes: Any) -> bool:
    return any("catch-all" in str(note).lower() for note in notes or ())


@lru_cache(maxsize=1)
def load_master() -> tuple[MasterCraft, ...]:
    rows = json.loads(MASTER_FILE.read_text(encoding="utf-8"))
    return tuple(
        MasterCraft(
            category=row["category"],
            category_slug=row["categorySlug"],
            sub_category=row["subCategory"],
            sub_category_slug=row["subCategorySlug"],
            source_serial=int(row["sourceSerial"]),
            name=row["craftName"],
            slug=row["craftSlug"],
            state_of_origin=tuple(row["stateOfOrigin"]),
            state_of_practice=tuple(row["stateOfPractice"]),
            gi_status=row["giStatus"],
            notes=tuple(row["notes"]),
        )
        for row in rows
    )


@lru_cache(maxsize=1)
def master_categories() -> tuple[MasterCategory, ...]:
    order: list[str] = []
    names: dict[str, str] = {}
    subs: dict[str, list[tuple[str, str]]] = {}
    for craft in load_master():
        if craft.category_slug not in names:
            order.append(craft.category_slug)
            names[craft.category_slug] = craft.category
            subs[craft.category_slug] = []
        pair = (craft.sub_category_slug, craft.sub_category)
        if pair not in subs[craft.category_slug]:
            subs[craft.category_slug].append(pair)
    return tuple(
        MasterCategory(
            slug=slug,
            name=names[slug],
            sort_order=index,
            sub_categories=tuple(
                MasterSubCategory(slug=s, name=n, sort_order=j)
                for j, (s, n) in enumerate(subs[slug], start=1)
            ),
        )
        for index, slug in enumerate(order, start=1)
    )


@lru_cache(maxsize=1)
def load_aliases() -> dict[str, Any]:
    return json.loads(ALIASES_FILE.read_text(encoding="utf-8"))


def master_id(kind: str, slug: str) -> str:
    return f"cm{kind}{hashlib.md5(f'{kind}:{slug}'.encode()).hexdigest()[:22]}"


_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def normalise(text: Any) -> str:
    if text is None:
        return ""
    from anyascii import anyascii

    folded = anyascii(unicodedata.normalize("NFC", str(text))).lower().replace("&", " and ")
    return " ".join(_NON_ALNUM.sub(" ", folded).split())


def token_set_ratio(a: Any, b: Any) -> float:
    ta, tb = set(normalise(a).split()), set(normalise(b).split())
    if not ta or not tb:
        return 0.0
    both = " ".join(sorted(ta & tb))
    first = " ".join(filter(None, (both, " ".join(sorted(ta - tb)))))
    second = " ".join(filter(None, (both, " ".join(sorted(tb - ta)))))

    def ratio(x: str, y: str) -> float:
        return SequenceMatcher(None, x, y).ratio() if x and y else 0.0

    return max(ratio(both, first), ratio(both, second), ratio(first, second))


def craft_aliases(craft: Any) -> list[str]:
    meta = getattr(craft, "extraMetadata", None)
    if isinstance(meta, str):
        try:
            meta = json.loads(meta)
        except ValueError:
            meta = None
    values = meta.get(ALIASES_KEY) if isinstance(meta, dict) else None
    return [str(v) for v in values or [] if str(v).strip()]


def with_alias(meta: Any, alias: str) -> dict[str, Any]:
    base = dict(meta) if isinstance(meta, dict) else {}
    values = [str(v) for v in base.get(ALIASES_KEY) or []]
    if alias and normalise(alias) not in {normalise(v) for v in values}:
        values.append(alias)
    base[ALIASES_KEY] = values
    return base


def as_json(value: Any) -> Any:
    from prisma import Json

    return Json(value)



def enum_value(value: Any) -> Any:
    return getattr(value, "value", value)


def _stamp(value: Any) -> str:
    return value.isoformat() if hasattr(value, "isoformat") else str(value or "")


def status_of(craft: Any) -> str:
    return str(enum_value(getattr(craft, "status", None)) or ACTIVE)


async def category_tree(client: Any) -> tuple[list[Any], list[dict[str, Any]]]:
    categories = await client.craftcategory.find_many(
        order=[{"sortOrder": "asc"}, {"name": "asc"}], include={"subCategories": True}
    )
    counts: dict[str, int] = {}
    for row in await client.craft.group_by(
        by=["subCategoryId"], where={"status": ACTIVE, "subCategoryId": {"not": None}}, count=True
    ):
        counts[row["subCategoryId"]] = _group_count(row)
    payload: list[dict[str, Any]] = []
    for category in categories:
        subs = sorted(getattr(category, "subCategories", None) or [], key=lambda s: (s.sortOrder, s.name))
        payload.append(
            {
                "id": category.id,
                "slug": category.slug,
                "name": category.name,
                "sortOrder": category.sortOrder,
                "subCategories": [
                    {
                        "id": sub.id,
                        "slug": sub.slug,
                        "name": sub.name,
                        "sortOrder": sub.sortOrder,
                        "craftCount": counts.get(sub.id, 0),
                    }
                    for sub in subs
                ],
            }
        )
    return categories, payload


def _group_count(row: Any) -> int:
    count = row.get("_count") if isinstance(row, dict) else getattr(row, "_count", None)
    if isinstance(count, dict):
        count = count.get("_all", 0)
    return int(count or 0)


async def build_catalogue(client: Any, *, include_inactive: bool = False) -> dict[str, Any]:
    from app.services import cross_portal_codes

    categories, category_payload = await category_tree(client)
    where: dict[str, Any] = {} if include_inactive else {"status": ACTIVE}
    crafts = await client.craft.find_many(where=where)
    spellings = await client.craftspelling.find_many()

    sub_order: dict[str, tuple[int, int, str]] = {}
    category_of: dict[str, str] = {}
    for category in categories:
        for sub in getattr(category, "subCategories", None) or []:
            sub_order[sub.id] = (category.sortOrder, sub.sortOrder, sub.name)
            category_of[sub.id] = category.id

    def order(craft: Any) -> tuple:
        inactive = status_of(craft) != ACTIVE
        place = sub_order.get(getattr(craft, "subCategoryId", None) or "", (10**6, 10**6, ""))
        serial = getattr(craft, "sourceSerial", None)
        return (inactive, place, serial is None, serial or 0, (craft.name or "").lower(), craft.id)

    crafts = sorted(crafts, key=order)

    by_code: dict[str, set[str]] = {}
    for row in spellings:
        if getattr(row, "craftCode", None):
            by_code.setdefault(row.craftCode, set()).add(row.spelling)

    inactive_ids = [c.id for c in crafts if status_of(c) != ACTIVE]
    references = await reference_counts(client, inactive_ids) if inactive_ids else {}

    digest = hashlib.sha256()
    digest.update(b"inactive" if include_inactive else b"active")
    for category in categories:
        digest.update(f"k{category.id}{_stamp(category.updatedAt)}".encode())
        for sub in getattr(category, "subCategories", None) or []:
            digest.update(f"s{sub.id}{_stamp(sub.updatedAt)}".encode())
    for spelling, code in sorted((r.spelling, r.craftCode or "") for r in spellings):
        digest.update(f"a{spelling}={code}".encode())

    rows: list[dict[str, Any]] = []
    for craft in crafts:
        digest.update(f"c{craft.id}{_stamp(craft.updatedAt)}".encode())
        own = cross_portal_codes.normalise(craft.name)
        aliases = set(by_code.get(getattr(craft, "craftCode", None) or "", set()))
        aliases.update(cross_portal_codes.normalise(a) for a in craft_aliases(craft))
        aliases.discard(own)
        aliases.discard("")
        sub_id = getattr(craft, "subCategoryId", None)
        row = {
            "id": craft.id,
            "slug": craft.slug,
            "name": craft.name,
            "localName": getattr(craft, "localName", None),
            "categoryId": category_of.get(sub_id or ""),
            "subCategoryId": sub_id,
            "sourceSerial": getattr(craft, "sourceSerial", None),
            "stateOfOrigin": list(getattr(craft, "stateOfOrigin", None) or []),
            "stateOfPractice": list(getattr(craft, "stateOfPractice", None) or []),
            "giStatus": enum_value(getattr(craft, "giStatus", None)),
            "isCatchAll": bool(getattr(craft, "isCatchAll", False)),
            "craftCode": getattr(craft, "craftCode", None),
            "aliases": sorted(aliases),
        }
        if include_inactive:
            row["status"] = status_of(craft)
            row["mergedIntoId"] = getattr(craft, "mergedIntoId", None)
            row["referenceCount"] = references.get(craft.id, 0) if status_of(craft) != ACTIVE else None
        rows.append(row)
    return {"version": digest.hexdigest(), "categories": category_payload, "crafts": rows}



def _links(client: Any) -> tuple[tuple[str, str], ...]:
    return DIRECT_LINKS + tuple(link for link in OPTIONAL_LINKS if hasattr(client, link[0]))


async def reference_count(client: Any, craft_id: str) -> int:
    total = 0
    for delegate, column in _links(client):
        if delegate == "tooldocumentation":
            total += await client.tooldocumentation.count(
                where={"OR": [{column: craft_id}, {"craftLinks": {"some": {"craftId": craft_id}}}]}
            )
        else:
            total += await getattr(client, delegate).count(where={column: craft_id})
    total += await client.workshopcraft.count(where={"craftId": craft_id})
    return total


async def reference_count_map(client: Any, craft_ids: list[str]) -> dict[str, int]:
    ids = list(dict.fromkeys(craft_ids))
    counts: dict[str, int] = dict.fromkeys(ids, 0)
    if not ids:
        return counts
    wanted = {"in": ids}
    for delegate, column in _links(client):
        if delegate == "tooldocumentation":
            tools = await client.tooldocumentation.find_many(
                where={"OR": [{column: wanted}, {"craftLinks": {"some": {"craftId": wanted}}}]},
                include={"craftLinks": True},
            )
            for tool in tools:
                named = {getattr(tool, column, None)}
                named.update(link.craftId for link in (getattr(tool, "craftLinks", None) or []))
                for craft_id in named & counts.keys():
                    counts[craft_id] += 1
            continue
        for row in await getattr(client, delegate).group_by(by=[column], where={column: wanted}, count=True):
            craft_id = row.get(column) if isinstance(row, dict) else getattr(row, column, None)
            if craft_id in counts:
                counts[craft_id] += _group_count(row)
    for row in await client.workshopcraft.group_by(by=["craftId"], where={"craftId": wanted}, count=True):
        craft_id = row.get("craftId") if isinstance(row, dict) else getattr(row, "craftId", None)
        if craft_id in counts:
            counts[craft_id] += _group_count(row)
    return counts


async def reference_counts(client: Any, craft_ids: list[str]) -> dict[str, int]:
    counts: dict[str, int] = dict.fromkeys(craft_ids, 0)
    if not craft_ids:
        return counts
    wanted = {"craftId": {"in": list(craft_ids)}}
    reads: list[tuple[Any, dict[str, Any]]] = [
        (getattr(client, delegate), wanted)
        for delegate, _ in _links(client)
        if delegate != "tooldocumentation"
    ]
    reads.append((client.tooldocumentation, {**wanted, "craftLinks": {"none": {}}}))
    reads.append((client.toolcraft, wanted))
    reads.append((client.workshopcraft, wanted))
    for delegate, where in reads:
        for row in await delegate.group_by(by=["craftId"], where=where, count=True):
            craft_id = row.get("craftId") if isinstance(row, dict) else getattr(row, "craftId", None)
            if craft_id in counts:
                counts[craft_id] += _group_count(row)
    return counts


def state_choices() -> tuple[str, ...]:
    from app.services.address import INDIAN_STATES_AND_UNION_TERRITORIES

    return (*INDIAN_STATES_AND_UNION_TERRITORIES, ALL_INDIA)


def clean_states(values: Any) -> list[str]:
    allowed = set(state_choices())
    out: list[str] = []
    for value in values or []:
        state = " ".join(str(value).split())
        if not state:
            continue
        if state not in allowed:
            raise HTTPException(
                status_code=422,
                detail={"code": "UNKNOWN_STATE", "message": f"{state} is not a state or union territory."},
            )
        if state not in out:
            out.append(state)
    return out



def _index(crafts: list[Any]) -> dict[str, list[Any]]:
    found: dict[str, list[Any]] = {}
    for craft in crafts:
        keys = {normalise(craft.name), normalise(getattr(craft, "slug", None))}
        keys.update(normalise(alias) for alias in craft_aliases(craft))
        for key in keys - {""}:
            found.setdefault(key, []).append(craft)
    return found


def exact_matches(text: Any, crafts: list[Any]) -> list[Any]:
    key = normalise(text)
    return list({c.id: c for c in _index(crafts).get(key, [])}.values()) if key else []


async def spelling_index(client: Any) -> Any | None:
    from app.services import cross_portal_codes

    if not hasattr(client, "craftspelling"):
        return None
    return await cross_portal_codes.load_spelling_index(client)


async def alias_matches(client: Any, text: Any, crafts: list[Any], *, index: Any | None = None) -> list[Any]:
    from app.services import cross_portal_codes

    has_table = hasattr(client, "craftspelling")
    if has_table:
        code = await cross_portal_codes.resolve_craft_code(client, text, spellings=index)
    else:
        code = cross_portal_codes.shared_craft_code(text)
    if code is None:
        return []
    spellings = {cross_portal_codes.normalise(s) for s in _code_spellings(code)}
    if has_table and index is not None:
        spellings.update(index.by_code.get(code, ()))
    elif has_table:
        for row in await client.craftspelling.find_many(where={"craftCode": code}):
            spellings.add(row.spelling)
    hits = [
        c
        for c in crafts
        if getattr(c, "craftCode", None) == code or cross_portal_codes.normalise(c.name) in spellings
    ]
    return list({c.id: c for c in hits}.values())


def _code_spellings(code: str) -> tuple[str, ...]:
    from app.services import cross_portal_codes

    entry = cross_portal_codes.shared().craft(code)
    return (entry.name, *entry.spellings) if entry is not None else ()


async def active_crafts(client: Any) -> list[Any]:
    return await client.craft.find_many(where={"status": ACTIVE})


async def match_craft_name(client: Any, text: Any) -> Any | None:
    if not normalise(text):
        return None
    crafts = await active_crafts(client)
    hits = exact_matches(text, crafts)
    if len(hits) == 1:
        return hits[0]
    if hits:
        return None
    hits = await alias_matches(client, text, crafts)
    return hits[0] if len(hits) == 1 else None


async def match_names(client: Any, names: Any) -> dict[str, Any]:
    crafts = await active_crafts(client)
    found: dict[str, Any] = {}
    for name in {str(n) for n in names or () if n and str(n).strip()}:
        hits = exact_matches(name, crafts)
        if not hits:
            hits = await alias_matches(client, name, crafts)
        if len(hits) == 1:
            found[name] = hits[0]
    return found


async def assert_active_craft(client: Any, craft_id: str) -> Any:
    craft = await client.craft.find_unique(where={"id": craft_id})
    if craft is None or status_of(craft) != ACTIVE:
        raise craft_error("CRAFT_NOT_IN_LIST", 422)
    return craft


async def assert_active_crafts(
    client: Any, craft_ids: list[str], *, stored: list[str] | None = None, tool: Any | None = None
) -> None:
    wanted = [craft_id for craft_id in dict.fromkeys(craft_ids) if craft_id]
    if not wanted:
        return
    rows = await client.craft.find_many(where={"id": {"in": wanted}})
    inactive = [row.id for row in rows if status_of(row) != ACTIVE]
    if not inactive:
        return
    kept = set(stored or ())
    if tool is not None:
        kept.add(getattr(tool, "craftId", None) or "")
        kept.update(link.craftId for link in await client.toolcraft.find_many(where={"toolId": tool.id}))
    if any(craft_id not in kept for craft_id in inactive):
        raise craft_error("CRAFT_NOT_IN_LIST", 422)


def _stash_legacy_text(data: dict[str, Any], text: str) -> None:
    meta = data.get("extraMetadata")
    meta = dict(meta) if isinstance(meta, dict) else {}
    meta[LEGACY_TEXT_KEY] = text
    data["extraMetadata"] = meta


async def resolve_record_craft(
    client: Any,
    data: dict[str, Any],
    *,
    stored: Any | None = None,
    keep_name: bool = False,
    strict: bool = False,
) -> dict[str, Any]:
    text = data.get("craftName") if keep_name else data.pop("craftName", None)
    craft_id = data.get("craftId")
    stored_id = getattr(stored, "craftId", None) if stored is not None else None
    if craft_id:
        if craft_id != stored_id:
            await assert_active_craft(client, craft_id)
        return data
    if not (isinstance(text, str) and text.strip()):
        return data
    if stored is not None and text.strip() == (getattr(stored, "craftName", None) or "").strip():
        return data
    craft = await match_craft_name(client, text)
    if craft is not None:
        data["craftId"] = craft.id
        if keep_name:
            data["craftName"] = craft.name
        return data
    if strict:
        raise HTTPException(
            status_code=422,
            detail=(
                f"No craft is called '{text}'. Crafts are a controlled list and a spreadsheet cannot "
                "add to it — use the name as it appears on the craft list."
            ),
        )
    _stash_legacy_text(data, text.strip())
    return data


async def craft_columns(client: Any, sent: dict[str, Any], *, stored: Any | None = None) -> dict[str, Any]:
    out: dict[str, Any] = {}
    if "craftId" in sent:
        craft_id = sent.get("craftId") or None
        if craft_id is None:
            return {"craftId": None, "craftName": None}
        if craft_id != getattr(stored, "craftId", None):
            craft = await assert_active_craft(client, craft_id)
        else:
            craft = await client.craft.find_unique(where={"id": craft_id})
        out["craftId"] = craft_id
        if craft is not None:
            out["craftName"] = craft.name
        return out
    if "craftName" in sent:
        text = sent.get("craftName")
        if not (isinstance(text, str) and text.strip()):
            return {"craftId": None, "craftName": None}
        if stored is not None and text.strip() == (getattr(stored, "craftName", None) or ""):
            return {}
        craft = await match_craft_name(client, text)
        if craft is not None:
            return {"craftId": craft.id, "craftName": craft.name}
        return {"craftId": None, "craftName": text.strip()}
    return out


async def resolve_tool_crafts(
    client: Any, data: dict[str, Any], craft_ids: list[str] | None, *, tool: Any | None = None
) -> list[str] | None:
    if craft_ids:
        await assert_active_crafts(client, craft_ids, tool=tool)
        return craft_ids
    if data.get("craftId"):
        await assert_active_crafts(client, [data["craftId"]], tool=tool)
        return craft_ids
    text = data.get("craftName")
    typed = isinstance(text, str) and text.strip()
    held = (getattr(tool, "craftName", None) or "") if tool is not None else None
    unchanged = craft_ids is None and typed and text.strip() == held
    if typed and not unchanged:
        craft = await match_craft_name(client, text)
        if craft is not None:
            return [craft.id]
        _stash_legacy_text(data, text.strip())
    if tool is None and not typed:
        raise craft_error("CRAFT_NOT_IN_LIST", 422)
    return craft_ids



def _rename_in_list(joined: str | None, old: str, new: str) -> str:
    names: list[str] = []
    for part in (joined or "").split(","):
        name = part.strip()
        if not name:
            continue
        name = new if name == old else name
        if name not in names:
            names.append(name)
    return ", ".join(names)


async def merge_craft(client: Any, source: Any, target: Any, *, user_id: str | None = None) -> dict[str, int]:
    if source.id == target.id:
        raise ValueError("a craft cannot be merged into itself")
    moved: dict[str, int] = {}
    touched_tools = [
        t.id
        for t in await client.tooldocumentation.find_many(
            where={"OR": [{"craftId": source.id}, {"craftLinks": {"some": {"craftId": source.id}}}]}
        )
    ]
    for delegate, column in _links(client):
        moved[delegate] = await getattr(client, delegate).update_many(
            where={column: source.id}, data={column: target.id}
        )
    moved["craft"] = await client.craft.update_many(
        where={"mergedIntoId": source.id}, data={"mergedIntoId": target.id}
    )
    for link in await client.toolcraft.find_many(where={"craftId": source.id}):
        twin = await client.toolcraft.find_first(where={"toolId": link.toolId, "craftId": target.id})
        if twin is None:
            await client.toolcraft.create(data={"toolId": link.toolId, "craftId": target.id})
    moved["toolcraft"] = await client.toolcraft.delete_many(where={"craftId": source.id})
    for link in await client.workshopcraft.find_many(where={"craftId": source.id}):
        twin = await client.workshopcraft.find_first(
            where={"workshopId": link.workshopId, "craftId": target.id}
        )
        if twin is None:
            await client.workshopcraft.create(data={"workshopId": link.workshopId, "craftId": target.id})
    moved["workshopcraft"] = await client.workshopcraft.delete_many(where={"craftId": source.id})

    await client.productdocumentation.update_many(
        where={"craftId": target.id, "NOT": {"craftName": target.name}}, data={"craftName": target.name}
    )
    for tool_id in touched_tools:
        tool = await client.tooldocumentation.find_unique(where={"id": tool_id})
        if tool is None:
            continue
        renamed = _rename_in_list(getattr(tool, "craftName", None), source.name, target.name)
        if renamed and renamed != tool.craftName:
            await client.tooldocumentation.update(where={"id": tool_id}, data={"craftName": renamed})

    await client.craft.update(
        where={"id": target.id},
        data={"extraMetadata": as_json(with_alias(getattr(target, "extraMetadata", None), source.name))},
    )
    await remember_spelling(client, target, source.name, user_id=user_id)
    await client.craft.update(
        where={"id": source.id},
        data={"status": MERGED, "mergedIntoId": target.id},
    )
    return {k: int(v or 0) for k, v in moved.items()}


async def remember_spelling(client: Any, craft: Any, spelling: str, *, user_id: str | None) -> None:
    from app.services import cross_portal_codes

    code = getattr(craft, "craftCode", None)
    key = cross_portal_codes.normalise(spelling)
    if not code or not key:
        return
    if await client.craftspelling.find_unique(where={"spelling": key}) is not None:
        return
    await client.craftspelling.create(
        data={
            "spelling": key,
            "label": spelling,
            "craftCode": code,
            "source": cross_portal_codes.SOURCE_ADMIN,
            "createdById": user_id,
        }
    )


async def delete_craft(client: Any, craft: Any, *, user_id: str | None) -> None:
    if getattr(craft, "sourceSerial", None) is not None:
        await client.recordrevision.create(
            data={
                "recordType": DELETED_MASTER_TYPE,
                "recordId": craft.slug,
                "editedById": user_id,
                "changes": as_json(
                    {"id": craft.id, "name": craft.name, "sourceSerial": craft.sourceSerial}
                ),
            }
        )
    await client.craft.delete(where={"id": craft.id})



async def unique_slug(delegate: Any, name: str, *, fallback: str = "Craft", exclude_id: str | None = None) -> str:
    from app.services.naming import field

    base = field(name) or fallback
    candidate, n = base, 1
    while True:
        found = await delegate.find_unique(where={"slug": candidate})
        if found is None or found.id == exclude_id:
            return candidate
        n += 1
        candidate = f"{base}-{n}"



def _craft_columns(craft: MasterCraft, sub_category_id: str) -> dict[str, Any]:
    return {
        "name": craft.name,
        "subCategoryId": sub_category_id,
        "category": craft.category,
        "sourceSerial": craft.source_serial,
        "stateOfOrigin": list(craft.state_of_origin),
        "stateOfPractice": list(craft.state_of_practice),
        "giStatus": craft.gi_status,
        "isCatchAll": craft.is_catch_all,
        "sourceNotes": list(craft.notes),
    }


_ADMIN_KEYS: dict[str, tuple[str, ...]] = {
    "name": ("name",),
    "subCategoryId": ("subCategoryId", "category"),
    "category": ("subCategoryId", "category"),
    "stateOfOrigin": ("stateOfOrigin",),
    "stateOfPractice": ("stateOfPractice",),
    "giStatus": ("giStatus",),
}


def craft_meta(row: Any) -> dict[str, Any]:
    meta = getattr(row, "extraMetadata", None)
    if isinstance(meta, str):
        try:
            meta = json.loads(meta)
        except ValueError:
            meta = None
    return dict(meta) if isinstance(meta, dict) else {}


def _same(stored: Any, wanted: Any) -> bool:
    stored = enum_value(stored)
    if isinstance(wanted, list):
        return list(stored or []) == wanted
    return stored == wanted


def without_master_provenance(meta: Any) -> dict[str, Any]:
    base = dict(meta) if isinstance(meta, dict) else {}
    provenance = base.get("fieldProvenance")
    if isinstance(provenance, dict):
        owned = {key for keys in _ADMIN_KEYS.values() for key in keys}
        kept = {key: value for key, value in provenance.items() if key not in owned}
        if kept:
            base["fieldProvenance"] = kept
        else:
            base.pop("fieldProvenance")
    return base


def _file_changes(row: Any, wanted: dict[str, Any]) -> tuple[dict[str, Any], int]:
    meta = craft_meta(row)
    seed = meta.get(MASTER_SEED_KEY)
    provenance = meta.get("fieldProvenance") if isinstance(meta.get("fieldProvenance"), dict) else {}
    changed: dict[str, Any] = {}
    kept = 0
    for key, value in wanted.items():
        stored = getattr(row, key, None)
        if _same(stored, value):
            continue
        if isinstance(seed, dict) and key in seed:
            edited = not _same(stored, seed[key])
        else:
            edited = any(name in provenance for name in _ADMIN_KEYS.get(key, ()))
        if edited:
            kept += 1
        else:
            changed[key] = value
    return changed, kept


async def sync_master(client: Any, *, dry_run: bool = False) -> dict[str, int]:
    from app.services import cross_portal_codes

    report = {
        "categoriesCreated": 0,
        "subCategoriesCreated": 0,
        "created": 0,
        "updated": 0,
        "kept": 0,
        "skippedDeleted": 0,
        "retired": 0,
    }
    categories = master_categories()
    master_crafts = load_master()
    categories_by_slug = {
        row.slug: row
        for row in await client.craftcategory.find_many(where={"slug": {"in": [c.slug for c in categories]}})
    }
    subs_by_slug = {
        row.slug: row
        for row in await client.craftsubcategory.find_many(
            where={"slug": {"in": [s.slug for c in categories for s in c.sub_categories]}}
        )
    }
    crafts_by_slug = {
        row.slug: row
        for row in await client.craft.find_many(where={"slug": {"in": [c.slug for c in master_crafts]}})
    }
    index = await spelling_index(client)

    sub_ids: dict[str, str] = {}
    for category in categories:
        row = categories_by_slug.get(category.slug)
        if row is None:
            report["categoriesCreated"] += 1
            category_id = master_id("k", category.slug)
            if not dry_run:
                row = await client.craftcategory.create(
                    data={"id": category_id, "slug": category.slug, "name": category.name, "sortOrder": category.sort_order}
                )
                categories_by_slug[category.slug] = row
        category_id = row.id if row is not None else master_id("k", category.slug)
        for sub in category.sub_categories:
            found = subs_by_slug.get(sub.slug)
            if found is None:
                report["subCategoriesCreated"] += 1
                if not dry_run:
                    found = await client.craftsubcategory.create(
                        data={
                            "id": master_id("s", sub.slug),
                            "slug": sub.slug,
                            "name": sub.name,
                            "categoryId": category_id,
                            "sortOrder": sub.sort_order,
                        }
                    )
                    subs_by_slug[sub.slug] = found
            sub_ids[sub.slug] = found.id if found is not None else master_id("s", sub.slug)

    deleted = {
        row.recordId for row in await client.recordrevision.find_many(where={"recordType": DELETED_MASTER_TYPE})
    }
    in_file: set[str] = set()
    for craft in master_crafts:
        in_file.add(craft.slug)
        wanted = _craft_columns(craft, sub_ids[craft.sub_category_slug])
        row = crafts_by_slug.get(craft.slug)
        if row is None:
            if craft.slug in deleted:
                report["skippedDeleted"] += 1
                continue
            report["created"] += 1
            if not dry_run:
                code = await cross_portal_codes.code_for_new_craft(client, craft.name, spellings=index)
                crafts_by_slug[craft.slug] = await client.craft.create(
                    data={
                        "id": master_id("c", craft.slug),
                        "slug": craft.slug,
                        **wanted,
                        "status": ACTIVE,
                        "craftCode": code,
                        "extraMetadata": as_json({MASTER_SEED_KEY: wanted}),
                    }
                )
            continue
        changed, kept = _file_changes(row, wanted)
        report["kept"] += kept
        if getattr(row, "craftCode", None) is None:
            code = await cross_portal_codes.resolve_craft_code(client, craft.name, spellings=index)
            if code is not None:
                changed["craftCode"] = code
        meta = craft_meta(row)
        if changed:
            report["updated"] += 1
        if (changed or meta.get(MASTER_SEED_KEY) != wanted) and not dry_run:
            if "craftCode" in changed:
                await cross_portal_codes.ensure_code_row(client, changed["craftCode"])
            changed["extraMetadata"] = as_json({**meta, MASTER_SEED_KEY: wanted})
            await client.craft.update(where={"id": row.id}, data=changed)

    for row in await client.craft.find_many(where={"status": ACTIVE, "sourceSerial": {"not": None}}):
        if row.slug not in in_file:
            report["retired"] += 1
            if not dry_run:
                await client.craft.update(where={"id": row.id}, data={"status": LEGACY, "sourceSerial": None})
    return report
