from __future__ import annotations

import asyncio
import logging
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

AGRA_MARBLE_INLAY = cm.master_id("c", "Agra-Marble-Inlay")
LOCATION = {
    "latitude": 27.1767,
    "longitude": 78.0081,
    "state": "Uttar Pradesh",
    "district": "Agra",
}
PII_LIKE = "Rukhsana Begum 9876543210"


async def _seed(stamp: str) -> dict[str, Any]:
    await db.connect()
    try:
        admin = await db.user.create(
            data={
                "email": f"blank-names-admin-{stamp}@example.org",
                "name": "Blank Names Admin",
                "role": "ADMIN",
                "passwordHash": hash_password("blank-names-test-password"),
            }
        )
        fahim = await db.artisan.create(
            data={"name": f"Fahim {stamp}", "place": "Agra", "createdById": admin.id}
        )
        moin = await db.artisan.create(
            data={"name": f"Moin Khan {stamp}", "place": "Agra", "createdById": admin.id}
        )
        village = await db.location.create(
            data={
                "latitude": 27.2,
                "longitude": 78.0,
                "state": "Uttar Pradesh",
                "district": "Agra",
                "village": "Tajganj",
            }
        )
        placeless = await db.artisan.create(
            data={
                "name": f"Placeless {stamp}",
                "place": "",
                "createdById": admin.id,
                "locationId": village.id,
            }
        )
        return {
            "admin": admin,
            "fahim": fahim.id,
            "moin": moin.id,
            "placeless": placeless.id,
            "village": village.id,
        }
    finally:
        await db.disconnect()


async def _cleanup(world: dict[str, Any]) -> None:
    stamp = world["stamp"]
    has_stamp = {"contains": stamp, "mode": "insensitive"}
    await db.connect()
    try:
        tools = [
            t.id for t in await db.tooldocumentation.find_many(where={"toolkitName": has_stamp})
        ]
        await db.toolcraft.delete_many(where={"toolId": {"in": tools}})
        await db.toolartisan.delete_many(where={"toolId": {"in": tools}})
        await db.tooldocumentation.delete_many(where={"id": {"in": tools}})
        await db.productdocumentation.delete_many(where={"productName": has_stamp})
        await db.artisan.delete_many(where={"name": has_stamp})
        await db.location.delete_many(where={"id": world.get("village", "")})
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


def _as(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _android_tool(world: dict[str, Any], **overrides: Any) -> dict[str, Any]:
    body = {
        "craftName": "",
        "place": "Agra",
        "artisanName": "",
        "toolkitName": f"Pietra Dura Chisel {world['stamp']}",
        "craftIds": [AGRA_MARBLE_INLAY],
        "artisanIds": [world["fahim"], world["moin"]],
        "maker": "UNKNOWN",
        "traditionType": "UNKNOWN",
        "status": "PENDING",
        "recordedTimezone": "Asia/Kolkata",
        "location": dict(LOCATION),
    }
    body.update(overrides)
    return body


def test_the_android_0_0_2_tool_body_with_blank_names_saves_and_derives_them(world):
    created = world["client"].post("/api/tools", json=_android_tool(world), headers=_as(world))

    assert created.status_code == 201, created.text
    body = created.json()
    assert body["craftName"] == "Agra Marble Inlay"
    assert body["craftId"] == AGRA_MARBLE_INLAY
    assert body["artisanName"] == f"Fahim {world['stamp']}"
    assert body["artisanId"] == world["fahim"]
    assert body["place"] == "Agra"


def test_a_typed_artisan_name_is_kept_when_the_craft_name_is_blank(world):
    created = world["client"].post(
        "/api/tools",
        json=_android_tool(
            world, artisanName="Fahim Qureshi", toolkitName=f"Kalam {world['stamp']}"
        ),
        headers=_as(world),
    )

    assert created.status_code == 201, created.text
    assert created.json()["craftName"] == "Agra Marble Inlay"
    assert created.json()["artisanName"] == "Fahim Qureshi"


def test_a_missing_place_comes_from_the_artisan_location_when_the_artisan_has_none(world):
    created = world["client"].post(
        "/api/tools",
        json=_android_tool(
            world,
            place="   ",
            artisanIds=[world["placeless"]],
            toolkitName=f"Rasp {world['stamp']}",
        ),
        headers=_as(world),
    )

    assert created.status_code == 201, created.text
    assert created.json()["place"] == "Tajganj"


def test_no_name_and_no_link_is_a_readable_422(world):
    refused = world["client"].post(
        "/api/tools",
        json=_android_tool(world, craftIds=[], artisanIds=[], place=""),
        headers=_as(world),
    )

    assert refused.status_code == 422, refused.text
    body = refused.json()
    assert body["code"] == "validation_failed"
    assert isinstance(body["detail"], list)
    assert "Choose the craft, or type the craft's name." in body["message"]
    assert "Choose the artisan, or type the artisan's name." in body["message"]


def test_a_blank_toolkit_name_is_a_labelled_422_that_logs_the_path_and_not_the_value(world, caplog):
    caplog.set_level(logging.WARNING, logger="app.validation")
    refused = world["client"].post(
        "/api/tools",
        json=_android_tool(world, toolkitName="   ", remarks=PII_LIKE, unexpected=PII_LIKE),
        headers=_as(world),
    )

    assert refused.status_code == 422, refused.text
    body = refused.json()
    entries = {entry["field"]: entry for entry in body["detail"]}
    assert entries["toolkitName"]["msg"] == "Toolkit name can't be empty."
    assert entries["toolkitName"]["label"] == "Toolkit name"
    assert entries["toolkitName"]["loc"] == ["body", "toolkitName"]
    assert entries["unexpected"]["type"] == "extra_forbidden"
    for entry in body["detail"]:
        assert set(entry) == {"type", "loc", "field", "label", "msg"}
    assert PII_LIKE not in refused.text

    lines = [record.getMessage() for record in caplog.records if record.name == "app.validation"]
    assert lines, "the 422 was not logged"
    assert any(
        "POST /api/tools " in line and "body.toolkitName string_too_short" in line for line in lines
    )
    assert all(PII_LIKE not in line for line in lines)


def test_clearing_remarks_on_patch_still_clears_them(world):
    client = world["client"]
    created = client.post(
        "/api/tools",
        json=_android_tool(world, remarks="Handle split", toolkitName=f"Scraper {world['stamp']}"),
        headers=_as(world),
    )
    assert created.status_code == 201, created.text
    assert created.json()["remarks"] == "Handle split"

    cleared = client.patch(
        f"/api/tools/{created.json()['id']}",
        json={"remarks": "", "craftName": "", "artisanName": "  "},
        headers=_as(world),
    )

    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["remarks"] is None
    assert cleared.json()["craftName"] == "Agra Marble Inlay"
    assert cleared.json()["artisanName"] == f"Fahim {world['stamp']}"
