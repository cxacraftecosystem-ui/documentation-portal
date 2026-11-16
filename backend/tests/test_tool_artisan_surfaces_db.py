from __future__ import annotations

import uuid
from types import SimpleNamespace
from typing import Any

from qv2_fixtures import connect, needs_db, patch_db, run

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]


def _db() -> Any:
    import app.core.db as core_db

    return core_db.db


def _private(monkeypatch: Any, work: Any) -> Any:
    from app.api.routes import data_browser, search, tools
    from app.services import record_filters, search_index
    from app.services.data_explorer import browse

    assert data_browser and search and tools and record_filters and search_index and browse

    async def connected() -> Any:
        client = await connect()
        patch_db(monkeypatch, client)
        try:
            return await work()
        finally:
            await client.disconnect()

    return run(connected())


async def _grinder() -> dict[str, Any]:
    db = _db()
    owner = await db.user.create(
        data={"email": f"tas-{STAMP}-{uuid.uuid4().hex[:6]}@example.org", "name": f"Tool Admin {STAMP}", "role": "MASTER_ADMIN"}
    )
    names = {"fahim": f"Fahim {STAMP}", "moin": f"Moin Khan {STAMP}"}
    artisans = {
        key: await db.artisan.create(data={"name": name, "place": "Moradabad", "createdById": owner.id})
        for key, name in names.items()
    }
    tool = await db.tooldocumentation.create(
        data={
            "craftName": "Metal craft",
            "place": "Moradabad",
            "artisanName": names["fahim"],
            "toolkitName": f"Grinder {STAMP}",
            "createdById": owner.id,
            "artisanId": artisans["fahim"].id,
        }
    )
    for key in ("moin", "fahim"):
        await db.toolartisan.create(data={"toolId": tool.id, "artisanId": artisans[key].id})
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
    every = f"{names['fahim']}, {names['moin']}"
    head_first = [artisans["fahim"].id, artisans["moin"].id]
    return {
        "owner": owner,
        "artisans": artisans,
        "tool": tool,
        "media": media,
        "names": names,
        "every": every,
        "head_first": head_first,
    }


async def _cleanup(seed: dict[str, Any]) -> None:
    db = _db()
    await db.mediafile.delete_many(where={"id": seed["media"].id})
    await db.toolartisan.delete_many(where={"toolId": seed["tool"].id})
    await db.tooldocumentation.delete_many(where={"id": seed["tool"].id})
    await db.artisan.delete_many(where={"id": {"in": [a.id for a in seed["artisans"].values()]}})
    await db.user.delete_many(where={"id": seed["owner"].id})


def _run(monkeypatch: Any, check: Any) -> None:
    async def body() -> None:
        seed = await _grinder()
        try:
            await check(seed)
        finally:
            await _cleanup(seed)

    _private(monkeypatch, body)


def _link_ids(row: dict[str, Any]) -> list[str]:
    return [link["artisanId"] for link in row.get("artisanLinks") or []]


def test_the_unified_search_document_names_and_files_a_tool_under_every_artisan_head_first(monkeypatch: Any) -> None:
    from app.services import search_index

    async def check(seed: dict[str, Any]) -> None:
        doc = await search_index._tool(seed["tool"].id)
        assert doc is not None
        assert seed["every"] in (doc.get("subtitle") or ""), doc.get("subtitle")
        assert seed["names"]["moin"] in (doc.get("body") or ""), "a linked artisan's name is searchable"
        assert set(doc.get("artisanIds") or []) == set(seed["head_first"])

    _run(monkeypatch, check)


def test_a_browse_row_and_its_search_name_every_artisan_of_the_tool(monkeypatch: Any) -> None:
    from app.services.data_explorer import browse

    async def check(seed: dict[str, Any]) -> None:
        scope = SimpleNamespace(records=None, media=None)
        found = await browse.page("tool", scope, page_number=1, page_size=50, q=seed["names"]["moin"], workshop_ids=None)
        rows = [row for row in found["items"] if row["id"] == seed["tool"].id]
        assert rows, "a tool is found by a linked artisan's name"
        assert rows[0]["name"] == f"Grinder {STAMP} · {seed['every']}"

    _run(monkeypatch, check)


def test_the_report_sheets_name_every_artisan_of_a_tool_and_of_its_media(monkeypatch: Any) -> None:
    from app.api.routes import data_browser

    async def check(seed: dict[str, Any]) -> None:
        db = _db()
        tool = await db.tooldocumentation.find_unique(where={"id": seed["tool"].id}, include=data_browser._TOOL_INCLUDE)
        _workshop, _craft, artisan = data_browser._hierarchy_of("tool", tool)
        assert artisan == seed["every"], "the All records sheet"
        info = data_browser._tool_info(tool)
        assert {"label": "Artisan", "value": seed["every"]} in info["fields"], "the explorer's details preview"
        media = await db.mediafile.find_unique(where={"id": seed["media"].id}, include=data_browser._NAMING_INCLUDE)
        context = data_browser._media_context(media, {}, {})
        assert context["artisan"] == seed["every"], "the media and transcript sheets"

    _run(monkeypatch, check)


def test_the_tools_list_and_the_legacy_search_find_a_tool_by_any_artisan_and_return_every_link_head_first(monkeypatch: Any) -> None:
    from app.api.routes import tools
    from app.services.record_filters import build_record_wheres

    async def check(seed: dict[str, Any]) -> None:
        listed = await tools.list_tools(current_user=seed["owner"], search=seed["names"]["moin"], page=1, pageSize=20)
        mine = [row for row in listed["items"] if row["id"] == seed["tool"].id]
        assert mine, "GET /tools?search= finds a tool by a linked artisan"
        assert _link_ids(mine[0]) == seed["head_first"], "GET /tools lists the links head first"
        assert mine[0]["artisanId"] == seed["artisans"]["fahim"].id
        one = await tools.get_tool(seed["tool"].id, current_user=seed["owner"])
        assert _link_ids(one) == seed["head_first"], "GET /tools/{id} lists the links head first"
        wheres = await build_record_wheres(seed["owner"], q=seed["names"]["moin"])
        found = await _db().tooldocumentation.find_many(where=wheres["tools"])
        assert seed["tool"].id in {row.id for row in found}, "the legacy search finds a tool by a linked artisan"

    _run(monkeypatch, check)


def test_the_tool_artisans_list_leads_with_the_head_once(monkeypatch: Any) -> None:
    from app.api.routes import tools

    async def check(seed: dict[str, Any]) -> None:
        listed = await tools.list_tool_artisans(seed["tool"].id, current_user=seed["owner"])
        assert [row["id"] for row in listed] == seed["head_first"]
        assert [row["isHead"] for row in listed] == [True, False]

    _run(monkeypatch, check)


def test_search_tool_results_carry_every_link_head_first(monkeypatch: Any) -> None:
    from app.api.routes import search
    from app.services.data_explorer import filters as explorer_filters

    async def check(seed: dict[str, Any]) -> None:
        result = await search.global_search(
            current_user=seed["owner"],
            q=seed["names"]["moin"],
            types=["tools"],
            workshopIds=None,
            page=1,
            pageSize=20,
            flt=explorer_filters.Filter(),
        )
        mine = [row for row in result["tools"] if row["id"] == seed["tool"].id]
        assert mine, "/search finds a tool by a linked artisan"
        assert _link_ids(mine[0]) == seed["head_first"]
        assert {link["artisan"]["name"] for link in mine[0]["artisanLinks"]} == set(seed["names"].values())

    _run(monkeypatch, check)
