"""``python -m app.scripts.map_crafts`` on a fixture built from the Field Repository's 15 live crafts.

The dry run must equal the curated table in docs/CRAFTS.md; applying it must leave nothing to move
on a second dry run; and the seeded list is restored afterwards. Against a LOCAL database the
migrations have been applied to; skipped otherwise. The same file in both portals.
"""

from __future__ import annotations

import asyncio
import os
from typing import Any

import pytest

from app.core.db import db
from app.scripts import map_crafts
from app.services import craft_master as cm

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

#: old name -> (action, target), the table in docs/CRAFTS.md.
EXPECTED: dict[str, tuple[str, str]] = {
    "Bagru Hand Block Printing": ("inPlace", "Bagru-Hand-Block-Print (#41)"),
    "Bamboo Craft": ("inPlace", "Cane-Bamboo-Craft (#226)"),
    "Cane and Bamboo": ("merge", "Cane-Bamboo-Craft (#226)"),
    "Bareilly Cane and Bamboo Craft": ("inPlace", "Bareilly-Cane-Bamboo-Craft (#225)"),
    "Bishnupur Terracotta Craft": ("adopt", "Terracotta-Pottery-Works"),
    "Dabu Hand Block Printing": ("adopt", "Hand-Block-Printing"),
    "Ghurni Clay Doll Craft": ("adopt", "Figurines-And-Toys"),
    "Jaipur Blue Pottery": ("inPlace", "Blue-Pottery-Of-Jaipur (#255)"),
    "Kalamkari Block Printing": ("inPlace", "Machilipatnam-Kalamkari (#67)"),
    "Krishnanagar Terracotta": ("adopt", "Terracotta-Pottery-Works"),
    "Kutch Ajrakh (GI)": ("inPlace", "Kutch-Ajrakh (#46)"),
    "Kutch Lippan Mud-and-Mirror Work": ("inPlace", "Mud-Mirror-Work-Lippon-Painting (#331)"),
    "Nizamabad Black Pottery": ("inPlace", "Nizamabad-Black-Pottery (#259)"),
    "Sanganeri Hand Block Printing": ("inPlace", "Sanganeri-Hand-Block-Printing (#40)"),
    "Uttarakhand Ringal (GI)": ("inPlace", "Uttarakhand-Ringal-Craft (#223)"),
}

#: product craftName -> (action, target)
EXPECTED_TEXT: dict[str, tuple[str, str | None]] = {
    "Bagru Hand Block printing": ("link", "Bagru-Hand-Block-Print (#41)"),
    "Dabu Hand Block printing Balotra": ("link", "Dabu-Hand-Block-Printing-Map-Test"),
    "Portrait, Jammu, Jammu & Kashmir": ("legacy", None),
}


async def _run() -> dict[str, Any]:
    await db.connect()
    ids: list[str] = []
    product_ids: list[str] = []
    user = None
    try:
        user = await db.user.create(data={"email": "map-crafts-fixture@example.org", "name": "Map Crafts", "role": "ADMIN"})
        for name in EXPECTED:
            slug = cm.normalise(name).title().replace(" ", "-") + "-Map-Test"
            craft = await db.craft.create(data={"name": name, "slug": slug, "status": cm.LEGACY})
            ids.append(craft.id)
        product_names = {}
        for text in EXPECTED_TEXT:
            product = await db.productdocumentation.create(
                data={
                    "craftName": text,
                    "place": "Fixture",
                    "artisanName": "Fixture Artisan",
                    "productName": "Map crafts fixture",
                    "createdById": user.id,
                }
            )
            product_ids.append(product.id)
            product_names[product.id] = text

        before = [r for r in await map_crafts.plan(db) if r.id in set(ids) | set(product_ids)]
        await map_crafts.apply(db, before)
        after = [r for r in await map_crafts.plan(db) if r.id in set(ids) | set(product_ids)]
        products = {p.id: p for p in await db.productdocumentation.find_many(where={"id": {"in": product_ids}})}
        crafts = {c.id: c for c in await db.craft.find_many(where={"id": {"in": ids}})}
        return {"before": before, "after": after, "products": products, "crafts": crafts, "names": product_names}
    finally:
        await db.productdocumentation.delete_many(where={"id": {"in": product_ids}})
        await db.craftspelling.delete_many(
            where={"source": "ADMIN", "label": {"in": list(EXPECTED)}}
        )
        await db.craft.update_many(where={"id": {"in": ids}}, data={"mergedIntoId": None})
        await db.craft.delete_many(where={"id": {"in": ids}})
        await cm.sync_master(db)
        if user is not None:
            await db.user.delete(where={"id": user.id})
        await db.disconnect()


@pytest.fixture(scope="module")
def outcome() -> dict[str, Any]:
    return asyncio.run(_run())


def test_the_dry_run_equals_the_curated_table(outcome):
    crafts = {r.oldName: (r.action, r.target) for r in outcome["before"] if r.kind == "craft"}
    assert crafts == EXPECTED


def test_free_text_follows_the_same_decisions(outcome):
    texts = {r.oldName: (r.action, r.target) for r in outcome["before"] if r.kind == "freeText"}
    assert texts == EXPECTED_TEXT


def test_applying_leaves_nothing_to_move(outcome):
    assert not [r for r in outcome["after"] if r.action in ("inPlace", "merge", "adopt", "link")]
    crafts = outcome["crafts"]
    statuses = sorted(cm.status_of(c) for c in crafts.values())
    assert statuses.count("MERGED") == 1 and statuses.count("ACTIVE") == 14
    in_place = next(c for c in crafts.values() if c.slug == "Kutch-Ajrakh")
    assert in_place.sourceSerial == 46 and "Kutch Ajrakh (GI)" in cm.craft_aliases(in_place)
    by_text = {outcome["names"][pid]: p for pid, p in outcome["products"].items()}
    assert by_text["Bagru Hand Block printing"].craftName == "Bagru Hand Block Print"
    assert by_text["Dabu Hand Block printing Balotra"].craftId in crafts
    assert by_text["Portrait, Jammu, Jammu & Kashmir"].craftId is None


def test_the_report_has_the_agreed_columns(outcome):
    rows = map_crafts.report(outcome["before"][:1])
    assert list(rows[0]) == ["kind", "id", "oldName", "action", "target", "references"]
