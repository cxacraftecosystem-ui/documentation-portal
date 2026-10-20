"""The crafts API on the master list (docs/CRAFTS.md, §2.3 of the 2026-10-20 requirements).

The catalogue every picker reads, craft writes and their refusals, merge and adopt, the category and
sub-category routes, and the matcher that keeps an old client's typed craft name. Against a LOCAL
database the migrations have been applied to; skipped otherwise. The same file in both portals.
"""

from __future__ import annotations

import asyncio
import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import craft_master as cm

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

EMBROIDERY = cm.master_id("s", "Embroidery")
HAND_BLOCK = cm.master_id("s", "Hand-Block-Printing")
THARU = cm.master_id("c", "Tharu-Embroidery")


async def _seed(stamp: str) -> dict[str, Any]:
    await db.connect()
    try:
        users = {}
        for role in ("ADMIN", "PROFESSOR", "RESEARCHER"):
            users[role] = await db.user.create(
                data={
                    "email": f"crafts-{role.lower()}-{stamp}@example.org",
                    "name": f"Crafts {role.title()}",
                    "role": role,
                    "passwordHash": hash_password("crafts-api-test-password"),
                }
            )
        before = await db.craft.count()
        # The matcher, on the real list: a typed name that is on it, one only an alias reaches, and
        # one that is on nothing. Nothing is ever created.
        known = await cm.resolve_record_craft(db, {"craftName": "kutch ajrakh"})
        unknown = await cm.resolve_record_craft(db, {"craftName": f"Portrait {stamp}"})
        product_kept = await cm.resolve_record_craft(db, {"craftName": f"Portrait {stamp}"}, keep_name=True)
        after = await db.craft.count()
        legacy = await db.craft.create(
            data={"name": f"Old Craft {stamp}", "slug": f"Old-Craft-{stamp}", "status": cm.LEGACY}
        )
        return {
            "users": users,
            "known": known,
            "unknown": unknown,
            "product_kept": product_kept,
            "created_by_matcher": after - before,
            "legacy": legacy,
        }
    finally:
        await db.disconnect()


async def _cleanup(world: dict[str, Any]) -> None:
    stamp = world["stamp"]
    await db.connect()
    try:
        await db.productdocumentation.delete_many(where={"productName": {"contains": stamp}})
        await db.craft.update_many(where={"mergedIntoId": {"not": None}, "name": {"contains": stamp}}, data={"mergedIntoId": None})
        await db.craft.delete_many(where={"name": {"contains": stamp}})
        await db.craft.update(where={"id": THARU}, data={"extraMetadata": cm.as_json({})})
        await db.craftsubcategory.delete_many(where={"name": {"contains": stamp}})
        await db.craftcategory.delete_many(where={"name": {"contains": stamp}})
        await db.user.delete_many(where={"email": {"contains": stamp}})
    finally:
        await db.disconnect()


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    seeded = asyncio.run(_seed(stamp))
    seeded["stamp"] = stamp
    try:
        with TestClient(app) as client:
            seeded["client"] = client
            yield seeded
    finally:
        asyncio.run(_cleanup(seeded))


def _as(world: dict[str, Any], role: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['users'][role].id)}"}


def _new_craft(world: dict[str, Any], name: str, role: str = "PROFESSOR", sub: str = EMBROIDERY) -> Any:
    return world["client"].post(
        "/api/crafts",
        json={"name": name, "subCategoryId": sub, "giStatus": "NON_GI", "stateOfPractice": ["Gujarat"]},
        headers=_as(world, role),
    )


def test_the_matcher_links_known_names_and_keeps_unknown_text_without_creating_a_craft(world):
    assert world["known"]["craftId"] == cm.master_id("c", "Kutch-Ajrakh")
    assert "craftName" not in world["known"]
    assert "craftId" not in world["unknown"]
    assert world["unknown"]["extraMetadata"][cm.LEGACY_TEXT_KEY] == f"Portrait {world['stamp']}"
    assert world["product_kept"]["craftName"] == f"Portrait {world['stamp']}"
    assert world["created_by_matcher"] == 0


def test_the_catalogue_is_the_whole_list_in_order_with_an_etag(world):
    client = world["client"]
    response = client.get("/api/crafts/catalogue", headers=_as(world, "RESEARCHER"))
    assert response.status_code == 200, response.text
    body = response.json()
    seeded = [c for c in body["crafts"] if c["sourceSerial"] is not None]
    assert len(seeded) == 455
    assert seeded[0]["slug"] == "Tharu-Embroidery"
    assert [c["sourceSerial"] for c in seeded] == [m.source_serial for m in cm.load_master()]
    assert len(body["categories"]) >= 9
    textiles = next(c for c in body["categories"] if c["slug"] == "Textiles-Based-Craft-Category")
    embroidery = next(s for s in textiles["subCategories"] if s["slug"] == "Embroidery")
    assert embroidery["craftCount"] >= 37
    ajrakh = next(c for c in body["crafts"] if c["slug"] == "Kutch-Ajrakh")
    assert ajrakh["giStatus"] == "GI" and ajrakh["subCategoryId"] == HAND_BLOCK and ajrakh["categoryId"] == textiles["id"]
    assert all(c["id"] != world["legacy"].id for c in body["crafts"]), "a LEGACY craft is never pickable"

    etag = response.headers["etag"]
    assert etag == f'"{body["version"]}"'
    again = client.get("/api/crafts/catalogue", headers={**_as(world, "RESEARCHER"), "If-None-Match": etag})
    assert again.status_code == 304


def test_only_a_content_admin_sees_inactive_crafts(world):
    client = world["client"]
    refused = client.get("/api/crafts/catalogue?includeInactive=true", headers=_as(world, "RESEARCHER"))
    assert refused.status_code == 403
    allowed = client.get("/api/crafts/catalogue?includeInactive=true", headers=_as(world, "ADMIN"))
    assert allowed.status_code == 200
    legacy = next(c for c in allowed.json()["crafts"] if c["id"] == world["legacy"].id)
    assert legacy["status"] == "LEGACY" and legacy["referenceCount"] == 0


def test_a_new_craft_joins_a_sub_category_and_bumps_the_version(world):
    client = world["client"]
    version = client.get("/api/crafts/catalogue", headers=_as(world, "RESEARCHER")).json()["version"]
    created = _new_craft(world, f"Suf Embroidery {world['stamp']}")
    assert created.status_code == 201, created.text
    craft = created.json()
    assert craft["status"] == "ACTIVE" and craft["subCategoryId"] == EMBROIDERY
    assert craft["category"] == "Textiles based Craft Category" and craft["categoryId"]
    assert craft["slug"].startswith("Suf-Embroidery")
    assert client.get("/api/crafts/catalogue", headers=_as(world, "RESEARCHER")).json()["version"] != version

    clash = _new_craft(world, f"Suf Embroidery {world['stamp']}")
    assert clash.status_code == 409
    assert clash.json()["detail"]["code"] == "CRAFT_EXISTS"

    no_sub = world["client"].post(
        "/api/crafts", json={"name": f"Nowhere {world['stamp']}", "giStatus": "GI"}, headers=_as(world, "PROFESSOR")
    )
    assert no_sub.status_code == 422

    listed = client.get(
        f"/api/crafts?subCategoryId={EMBROIDERY}&state=Gujarat&pageSize=500", headers=_as(world, "RESEARCHER")
    ).json()
    assert any(c["id"] == craft["id"] for c in listed["items"])
    assert all(c["subCategoryId"] == EMBROIDERY for c in listed["items"])


def test_only_a_content_admin_deletes_and_never_a_craft_in_use(world):
    client = world["client"]
    craft = _new_craft(world, f"Delete Me {world['stamp']}").json()
    assert client.delete(f"/api/crafts/{craft['id']}", headers=_as(world, "PROFESSOR")).status_code == 403

    product = client.post(
        "/api/products",
        json={
            "craftId": craft["id"],
            "place": "Bhuj",
            "artisanName": "Test Artisan",
            "productName": f"Stole {world['stamp']}",
            "location": {"latitude": 23.25, "longitude": 69.67, "state": "Gujarat", "district": "Kachchh"},
        },
        headers=_as(world, "ADMIN"),
    )
    assert product.status_code == 201, product.text
    assert product.json()["craftName"] == craft["name"]

    in_use = client.delete(f"/api/crafts/{craft['id']}", headers=_as(world, "ADMIN"))
    assert in_use.status_code == 409
    assert in_use.json()["detail"]["code"] == "CRAFT_IN_USE"
    assert in_use.json()["detail"]["referenceCount"] == 1

    unused = _new_craft(world, f"Unused {world['stamp']}").json()
    assert client.delete(f"/api/crafts/{unused['id']}", headers=_as(world, "ADMIN")).status_code == 204


def test_merge_moves_every_reference_and_keeps_the_source(world):
    client = world["client"]
    source = _new_craft(world, f"Merge Source {world['stamp']}").json()
    product = client.post(
        "/api/products",
        json={
            "craftId": source["id"],
            "place": "Bhuj",
            "artisanName": "Test Artisan",
            "productName": f"Shawl {world['stamp']}",
            "location": {"latitude": 23.25, "longitude": 69.67, "state": "Gujarat", "district": "Kachchh"},
        },
        headers=_as(world, "ADMIN"),
    ).json()

    refused = client.post(f"/api/crafts/{source['id']}/merge", json={"intoId": THARU}, headers=_as(world, "PROFESSOR"))
    assert refused.status_code == 403
    merged = client.post(f"/api/crafts/{source['id']}/merge", json={"intoId": THARU}, headers=_as(world, "ADMIN"))
    assert merged.status_code == 200, merged.text
    assert merged.json()["craft"]["status"] == "MERGED"
    assert merged.json()["craft"]["mergedIntoId"] == THARU

    moved = client.get(f"/api/products/{product['id']}", headers=_as(world, "ADMIN")).json()
    assert moved["craftId"] == THARU
    assert moved["craftName"] == "Tharu Embroidery"
    catalogue = client.get("/api/crafts/catalogue", headers=_as(world, "RESEARCHER")).json()
    tharu = next(c for c in catalogue["crafts"] if c["id"] == THARU)
    assert cm.normalise(source["name"]) in tharu["aliases"]

    picked_merged = client.post(
        "/api/products",
        json={
            "craftId": source["id"],
            "place": "Bhuj",
            "artisanName": "Test Artisan",
            "productName": f"Refused {world['stamp']}",
            "location": {"latitude": 23.25, "longitude": 69.67, "state": "Gujarat", "district": "Kachchh"},
        },
        headers=_as(world, "ADMIN"),
    )
    assert picked_merged.status_code == 422
    assert picked_merged.json()["detail"]["code"] == "CRAFT_NOT_IN_LIST"


def test_the_review_queue_lists_legacy_crafts_for_content_admins(world):
    client = world["client"]
    assert client.get("/api/crafts/review", headers=_as(world, "PROFESSOR")).status_code == 403
    queue = client.get("/api/crafts/review", headers=_as(world, "ADMIN"))
    assert queue.status_code == 200, queue.text
    legacy = next(c for c in queue.json()["crafts"] if c["id"] == world["legacy"].id)
    assert legacy["text"] == f"Old Craft {world['stamp']}" and legacy["references"] == 0


def test_adopt_puts_a_legacy_craft_on_the_list(world):
    client = world["client"]
    adopted = client.post(
        f"/api/crafts/{world['legacy'].id}/adopt", json={"subCategoryId": HAND_BLOCK}, headers=_as(world, "ADMIN")
    )
    assert adopted.status_code == 200, adopted.text
    assert adopted.json()["status"] == "ACTIVE" and adopted.json()["subCategoryId"] == HAND_BLOCK


def test_categories_and_sub_categories(world):
    client = world["client"]
    stamp = world["stamp"]
    tree = client.get("/api/craft-categories", headers=_as(world, "RESEARCHER")).json()
    assert [c["slug"] for c in tree][:1] == ["Textiles-Based-Craft-Category"]

    assert client.post("/api/craft-categories", json={"name": f"Cat {stamp}"}, headers=_as(world, "PROFESSOR")).status_code == 403
    category = client.post("/api/craft-categories", json={"name": f"Cat {stamp}"}, headers=_as(world, "ADMIN"))
    assert category.status_code == 201, category.text
    category_id = category.json()["id"]
    sub = client.post(
        "/api/craft-sub-categories", json={"name": f"Sub {stamp}", "categoryId": category_id}, headers=_as(world, "ADMIN")
    )
    assert sub.status_code == 201, sub.text
    renamed = client.patch(
        f"/api/craft-sub-categories/{sub.json()['id']}", json={"name": f"Sub Renamed {stamp}"}, headers=_as(world, "ADMIN")
    )
    assert renamed.status_code == 200 and renamed.json()["name"] == f"Sub Renamed {stamp}"

    not_empty = client.delete(f"/api/craft-categories/{category_id}", headers=_as(world, "ADMIN"))
    assert not_empty.status_code == 409 and not_empty.json()["detail"]["code"] == "CATEGORY_NOT_EMPTY"
    assert client.delete(f"/api/craft-sub-categories/{sub.json()['id']}", headers=_as(world, "PROFESSOR")).status_code == 403
    assert client.delete(f"/api/craft-sub-categories/{sub.json()['id']}", headers=_as(world, "ADMIN")).status_code == 204
    assert client.delete(f"/api/craft-categories/{category_id}", headers=_as(world, "ADMIN")).status_code == 204

    full = client.delete(f"/api/craft-sub-categories/{EMBROIDERY}", headers=_as(world, "ADMIN"))
    assert full.status_code == 409 and full.json()["detail"]["code"] == "SUB_CATEGORY_NOT_EMPTY"
