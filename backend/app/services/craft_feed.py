from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.services import craft_master

SCHEMA_VERSION = 1
DESIGNER = "DESIGNER"
FIELD_REPOSITORY = "FIELD_REPOSITORY"
PORTALS = (DESIGNER, FIELD_REPOSITORY)

TAXONOMY_KEYS = ("id", "slug", "name", "sortOrder")


def stamp(value: Any) -> str | None:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def categories(tree: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            **{key: category[key] for key in TAXONOMY_KEYS},
            "subCategories": [
                {key: sub[key] for key in TAXONOMY_KEYS} for sub in category["subCategories"]
            ],
        }
        for category in tree
    ]


def merge_state(craft: Any, craft_ids: set[str]) -> tuple[str, str | None]:
    status = craft_master.status_of(craft)
    if status != craft_master.MERGED:
        return status, None
    target = getattr(craft, "mergedIntoId", None)
    if target and target != craft.id and target in craft_ids:
        return status, target
    return craft_master.LEGACY, None


def _order(craft: Any) -> tuple[bool, bool, int, str, str]:
    serial = getattr(craft, "sourceSerial", None)
    return (
        craft_master.status_of(craft) != craft_master.ACTIVE,
        serial is None,
        serial or 0,
        str(getattr(craft, "slug", "") or "").lower(),
        craft.id,
    )


def craft_row(
    craft: Any, *, craft_ids: set[str], sub_ids: set[str], codes: set[str]
) -> dict[str, Any]:
    status, merged_into = merge_state(craft, craft_ids)
    sub_id = getattr(craft, "subCategoryId", None)
    code = getattr(craft, "craftCode", None)
    return {
        "id": craft.id,
        "slug": craft.slug,
        "name": craft.name,
        "localName": getattr(craft, "localName", None),
        "subCategoryId": sub_id if sub_id in sub_ids else None,
        "stateOfOrigin": list(getattr(craft, "stateOfOrigin", None) or []),
        "stateOfPractice": list(getattr(craft, "stateOfPractice", None) or []),
        "giStatus": craft_master.enum_value(getattr(craft, "giStatus", None)),
        "isCatchAll": bool(getattr(craft, "isCatchAll", False)),
        "craftCode": code if code in codes else None,
        "status": status,
        "mergedIntoId": merged_into,
        "aliases": craft_master.craft_aliases(craft),
        "updatedAt": stamp(getattr(craft, "updatedAt", None)),
    }


async def build(client: Any, portal: str) -> dict[str, Any]:
    if portal not in PORTALS:
        raise ValueError(f"unknown portal {portal!r}")
    crafts = sorted(await client.craft.find_many(), key=_order)
    _rows, tree = await craft_master.category_tree(client)
    codes = sorted(await client.craftcode.find_many(), key=lambda row: row.code)
    spellings = sorted(await client.craftspelling.find_many(), key=lambda row: row.spelling)
    taxonomy = categories(tree)
    sub_ids = {sub["id"] for category in taxonomy for sub in category["subCategories"]}
    code_set = {row.code for row in codes}
    craft_ids = {craft.id for craft in crafts}
    return {
        "schemaVersion": SCHEMA_VERSION,
        "portal": portal,
        "generatedAt": stamp(datetime.now(UTC)),
        "categories": taxonomy,
        "crafts": [
            craft_row(craft, craft_ids=craft_ids, sub_ids=sub_ids, codes=code_set)
            for craft in crafts
        ],
        "codes": [{"code": row.code, "name": row.name} for row in codes],
        "spellings": [
            {
                "spelling": row.spelling,
                "label": row.label,
                "craftCode": row.craftCode,
                "source": row.source,
            }
            for row in spellings
            if row.craftCode in code_set
        ],
    }
