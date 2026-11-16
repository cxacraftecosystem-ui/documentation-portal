from __future__ import annotations

import asyncio
import uuid
from types import SimpleNamespace
from typing import Any

from conftest import needs_db

from app.core.db import db

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


async def _grinder() -> dict[str, Any]:
    owner = await db.user.create(
        data={"email": f"tas-{STAMP}-{uuid.uuid4().hex[:6]}@example.org", "name": f"Tool Admin {STAMP}", "role": "MASTER_ADMIN"}
    )
    names = {"head": f"Salim {STAMP}", "one": f"Fahim {STAMP}", "two": f"Moin Khan {STAMP}"}
    artisans = {
        key: await db.artisan.create(data={"name": name, "place": "Moradabad", "createdById": owner.id})
        for key, name in names.items()
    }
    tool = await db.tooldocumentation.create(
        data={
            "craftName": "Metal craft",
            "place": "Moradabad",
            "artisanName": names["head"],
            "toolkitName": f"Grinder {STAMP}",
            "createdById": owner.id,
            "artisanId": artisans["head"].id,
        }
    )
    await db.toolartisan.create_many(
        data=[{"toolId": tool.id, "artisanId": artisans[key].id} for key in ("one", "two")]
    )
    media = await db.mediafile.create(
        data={
            "originalFilename": f"grinder-{STAMP}.jpg",
            "mediaType": "IMAGE",
            "mimeType": "image/jpeg",
            "sizeBytes": 10,
            "bucket": "test-bucket",
            "objectKey": f"media/{owner.id}/{STAMP}/grinder.jpg",
            "uploadedById": owner.id,
            "toolId": tool.id,
        }
    )
    every = f"{names['head']}, {names['one']}, {names['two']}"
    return {"owner": owner, "artisans": artisans, "tool": tool, "media": media, "names": names, "every": every}


async def _cleanup(seed: dict[str, Any]) -> None:
    await db.mediafile.delete_many(where={"id": seed["media"].id})
    await db.toolartisan.delete_many(where={"toolId": seed["tool"].id})
    await db.tooldocumentation.delete_many(where={"id": seed["tool"].id})
    await db.artisan.delete_many(where={"id": {"in": [a.id for a in seed["artisans"].values()]}})
    await db.user.delete_many(where={"id": seed["owner"].id})


def _run(check: Any) -> None:
    async def body() -> None:
        seed = await _grinder()
        try:
            await check(seed)
        finally:
            await _cleanup(seed)

    _private(body)


def test_the_unified_search_document_names_and_files_a_tool_under_every_artisan_head_first() -> None:
    from app.services import search_index

    async def check(seed: dict[str, Any]) -> None:
        doc = await search_index._tool(seed["tool"].id)
        assert doc is not None
        assert seed["every"] in (doc.get("subtitle") or ""), doc.get("subtitle")
        assert set(doc.get("artisanIds") or []) == {a.id for a in seed["artisans"].values()}

    _run(check)


def test_a_browse_row_and_its_search_name_every_artisan_of_the_tool() -> None:
    from app.services.data_explorer import browse

    async def check(seed: dict[str, Any]) -> None:
        scope = SimpleNamespace(records=None, media=None)
        found = await browse.page("tool", scope, page_number=1, page_size=50, q=seed["names"]["two"], workshop_ids=None)
        rows = [row for row in found["items"] if row["id"] == seed["tool"].id]
        assert rows, "a tool is found by a linked artisan's name"
        assert rows[0]["name"] == f"Grinder {STAMP} · {seed['every']}"

    _run(check)


def test_the_report_sheets_name_every_artisan_of_a_tool_and_of_its_media() -> None:
    from app.api.routes import data_browser

    async def check(seed: dict[str, Any]) -> None:
        tool = await db.tooldocumentation.find_unique(where={"id": seed["tool"].id}, include=data_browser._TOOL_INCLUDE)
        _workshop, _craft, artisan = data_browser._hierarchy_of("tool", tool)
        assert artisan == seed["every"], "the All records sheet"
        media = await db.mediafile.find_unique(where={"id": seed["media"].id}, include=data_browser._NAMING_INCLUDE)
        context = data_browser._media_context(media, {}, {})
        assert context["artisan"] == seed["every"], "the media and transcript sheets"

    _run(check)


def test_the_tools_list_and_the_legacy_search_find_a_tool_by_any_artisan_and_return_every_link() -> None:
    from app.api.routes import tools
    from app.services.record_filters import build_record_wheres

    async def check(seed: dict[str, Any]) -> None:
        listed = await tools.list_tools(current_user=seed["owner"], search=seed["names"]["one"], page=1, pageSize=20)
        mine = [row for row in listed["items"] if row["id"] == seed["tool"].id]
        assert mine, "GET /tools?search= finds a tool by a linked artisan"
        assert {link["artisanId"] for link in mine[0]["artisanLinks"]} == {seed["artisans"]["one"].id, seed["artisans"]["two"].id}
        assert mine[0]["artisanId"] == seed["artisans"]["head"].id
        one = await tools.get_tool(seed["tool"].id, current_user=seed["owner"])
        assert one["artisanId"] == seed["artisans"]["head"].id and len(one["artisanLinks"]) == 2, "GET /tools/{id} returns the head and both links"
        wheres = await build_record_wheres(seed["owner"], q=seed["names"]["two"])
        found = await db.tooldocumentation.find_many(where=wheres["tools"])
        assert seed["tool"].id in {row.id for row in found}, "the legacy search finds a tool by a linked artisan"

    _run(check)
