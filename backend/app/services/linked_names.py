"""The names a product carries, filled from the records it links to (A12, 2026-10-17).

A product stores three typed copies — ``craftName``, ``artisanName`` and ``place`` — beside the ids
of the craft and artisan it is about. While the ids were set by a picker and the copies by a person,
the two drifted: an artisan renamed or re-placed after the product was recorded left the product
saying the old thing, and a researcher who typed a variant spelling beside a correct link stored a
record that disagreed with itself.

So when an id is present the server fills the copy from the record it names, and the typed value is
only the answer for an UNLINKED product. In order:

* ``artisanId`` → ``artisanName`` and ``place`` from the artisan; and the craft from the artisan's
  own craft when the body names no ``craftId`` of its own (an artisan practises one craft, so the
  product is of that craft unless the researcher said otherwise).
* ``craftId`` → ``craftName`` from the craft.

An id that names no row is left to the route's own handling (Prisma refuses the foreign key); this
module never invents a value and never blanks one. It reads at most two rows.

The designer portal carries this file byte for byte (the master craft list, 2026-10-20) and uses only
:func:`fill_craft_name`: its product form still sends the artisan's name and place as typed.

ON AN EDIT ONLY A LINK THAT CHANGES re-derives. The web sends ``artisanId`` and ``craftId`` on every
PATCH; re-deriving on an unchanged link would rewrite names that drifted from the artisan's current
record, and an ordinary contributor would then be refused for "changing" populated names they never
touched (``assert_can_contribute_fields``).
"""

from typing import Any

from fastapi import HTTPException, status

from app.core.db import db

#: What to say when a linked create still has no value for a column, by column.
_MISSING_NAME = {
    # The master craft list (2026-10-20): a craft is picked from the list, never typed.
    "craftName": "Pick a craft from the list.",
    "artisanName": "Enter the artisan name.",
    "place": "The linked artisan has no place on file. Enter the place.",
}


def _sent_link(data: dict[str, Any], key: str, stored: Any | None) -> Any:
    """The id this body sends for ``key`` when it is NEW — absent, or equal to the stored link, is None."""
    if key not in data:
        return None
    value = data.get(key)
    if stored is not None and value == getattr(stored, key, None):
        return None
    return value


async def fill_product_names(data: dict[str, Any], *, stored: Any | None = None) -> dict[str, Any]:
    """Overwrite the product's typed names from its linked artisan and craft. Mutates and returns.

    ``stored`` is the product an edit applies to: an id the body does not mention, or sends unchanged,
    keeps the stored link and the stored names — only a link the edit CHANGES re-derives them, so an
    unrelated edit to an old product never rewrites its names behind the editor's back.

    A create that still has no value for a name — nothing typed and nothing on the linked record —
    is refused with a 422 that says which, instead of storing an empty name that no edit could send
    back (``ProductUpdate`` takes no empty name).
    """
    artisan_id = _sent_link(data, "artisanId", stored)
    craft_id = _sent_link(data, "craftId", stored)
    if artisan_id:
        artisan = await db.artisan.find_unique(where={"id": artisan_id}, include={"craft": True})
        if artisan is not None:
            if (artisan.name or "").strip():
                data["artisanName"] = artisan.name
            if (artisan.place or "").strip():
                data["place"] = artisan.place
            stored_craft = getattr(stored, "craftId", None) if stored is not None else None
            if not craft_id and not stored_craft and artisan.craftId:
                craft_id = artisan.craftId
                data["craftId"] = artisan.craftId
    if craft_id:
        craft = await db.craft.find_unique(where={"id": craft_id})
        if craft is not None and (craft.name or "").strip():
            data["craftName"] = craft.name
    if stored is None:
        for column, message in _MISSING_NAME.items():
            if not (data.get(column) or "").strip():
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=message)
    return data


async def fill_craft_name(data: dict[str, Any], *, stored: Any | None = None) -> dict[str, Any]:
    """Only the craft half of :func:`fill_product_names`: ``craftId`` -> ``craftName``. Mutates and returns.

    A product's craft name is a copy of its craft's, so a link the body sends or changes re-derives
    it. A create with neither a craft nor a typed name (from an old client) is refused.
    """
    craft_id = _sent_link(data, "craftId", stored)
    if craft_id:
        craft = await db.craft.find_unique(where={"id": craft_id})
        if craft is not None and (craft.name or "").strip():
            data["craftName"] = craft.name
    if stored is None and not (data.get("craftName") or "").strip():
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=_MISSING_NAME["craftName"])
    return data
