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
"""

from typing import Any

from app.core.db import db


async def fill_product_names(data: dict[str, Any], *, stored: Any | None = None) -> dict[str, Any]:
    """Overwrite the product's typed names from its linked artisan and craft. Mutates and returns.

    ``stored`` is the product an edit applies to: an id the body does not mention keeps the stored
    link, but only a link the body SENDS re-derives the names — an unrelated edit to an old product
    never rewrites its names behind the editor's back.
    """
    artisan_id = data.get("artisanId") if "artisanId" in data else None
    craft_id = data.get("craftId") if "craftId" in data else None
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
        # A create whose link supplied nothing for a column (an artisan with no craft and no place)
        # stores an empty string rather than failing the NOT NULL: the schema already refused the
        # body that was missing a name it could not get from anywhere.
        for column in ("craftName", "artisanName", "place"):
            if data.get(column) is None:
                data[column] = ""
    return data
