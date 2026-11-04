from __future__ import annotations

import contextlib
import json
import uuid
from datetime import UTC, datetime
from typing import Any

from qv2_fixtures import connect, needs_db, patch_db, run
from query_counter import counting

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]


def _db() -> Any:
    import app.core.db as core_db

    return core_db.db


def _private(monkeypatch: Any, work: Any) -> Any:
    from app.api.routes import data_browser, datasets, export, tools
    from app.services import record_filters, tool_scope

    assert data_browser and datasets and export and tools and record_filters and tool_scope

    async def connected() -> Any:
        client = await connect()
        patch_db(monkeypatch, client)
        try:
            return await work()
        finally:
            await client.disconnect()

    return run(connected())


async def _user(role: str, tag: str) -> Any:
    return await _db().user.create(
        data={
            "email": f"tms-{tag}-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": f"Scope {tag.title()}",
            "role": role,
        }
    )


async def _workshop(owner: Any, title: str) -> Any:
    return await _db().workshop.create(
        data={
            "title": f"{title} {STAMP}",
            "date": datetime.now(UTC),
            "place": "Bagru",
            "createdById": owner.id,
        }
    )


async def _artisan(owner: Any, name: str, workshop: Any, *, joined: bool) -> Any:
    artisan = await _db().artisan.create(
        data={
            "name": f"{name} {STAMP}",
            "place": "Bagru",
            "createdById": owner.id,
            "workshopId": workshop.id,
        }
    )
    if joined:
        await _db().workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
    return artisan


async def _tool(owner: Any, name: str, artisans: list[Any], workshop: Any | None) -> Any:
    tool = await _db().tooldocumentation.create(
        data={
            "craftName": "Block printing",
            "place": "Bagru",
            "artisanName": artisans[0].name,
            "toolkitName": f"{name} {STAMP}",
            "createdById": owner.id,
            "artisanId": artisans[0].id,
            "workshopId": workshop.id if workshop else None,
        }
    )
    await _db().toolartisan.create_many(
        data=[{"toolId": tool.id, "artisanId": artisan.id} for artisan in artisans]
    )
    return tool


async def _seed() -> dict[str, Any]:
    admin = await _user("MASTER_ADMIN", "admin")
    researcher = await _user("RESEARCHER", "researcher")
    w1 = await _workshop(admin, "Sanganer")
    w2 = await _workshop(researcher, "Bagru")
    a1 = await _artisan(admin, "Kamla", w1, joined=True)
    a2 = await _artisan(researcher, "Ramesh", w2, joined=False)
    shared = await _tool(admin, "Wooden block", [a1, a2], w1)
    loose = await _tool(admin, "Mallet", [a1], None)
    mine = await _tool(researcher, "Brush", [a2, a1], None)
    return {
        "admin": admin,
        "researcher": researcher,
        "w1": w1,
        "w2": w2,
        "a1": a1,
        "a2": a2,
        "shared": shared,
        "loose": loose,
        "mine": mine,
    }


async def _cleanup(seed: dict[str, Any]) -> None:
    tools = [seed[k].id for k in ("shared", "loose", "mine") if k in seed]
    tools.extend(seed.get("extra_tools", []))
    artisans = [seed[k].id for k in ("a1", "a2") if k in seed] + seed.get("extra_artisans", [])
    workshops = [seed[k].id for k in ("w1", "w2") if k in seed] + seed.get("extra_workshops", [])
    users = [seed[k].id for k in ("admin", "researcher") if k in seed]
    for step in (
        _db().toolartisan.delete_many(where={"toolId": {"in": tools}}),
        _db().tooldocumentation.delete_many(where={"id": {"in": tools}}),
        _db().workshopartisan.delete_many(where={"artisanId": {"in": artisans}}),
        _db().artisan.delete_many(where={"id": {"in": artisans}}),
        _db().workshop.delete_many(where={"id": {"in": workshops}}),
        _db().user.delete_many(where={"id": {"in": users}}),
    ):
        with contextlib.suppress(Exception):
            await step


def _ours(ids: list[str], seed: dict[str, Any]) -> list[str]:
    known = {seed[k].id: k for k in ("shared", "loose", "mine")}
    return [known[i] for i in ids if i in known]


async def _report_tools(path: str, user: Any) -> list[str]:
    from app.api.routes import data_browser

    scope = await data_browser._scope_for(user)
    segs = [s for s in path.split("/") if s]
    records = await data_browser._report_records(segs, scope)
    return [t.id for t in records["tools"]]


async def _tree_tools(path: str, user: Any) -> list[str]:
    from app.api.routes import data_browser

    scope = await data_browser._scope_for(user)
    entries, _info = await data_browser._list_level(path, scope)
    return [e["path"].rsplit("/", 1)[-1] for e in entries if e.get("recordType") == "tool"]


async def _workshop_artisans(workshop: Any, user: Any) -> list[str]:
    from app.api.routes import data_browser

    scope = await data_browser._scope_for(user)
    ws = await _db().workshop.find_unique(
        where={"id": workshop.id}, include={"artisans": True, "crafts": True}
    )
    return [a.id for a in await data_browser._linked_artisans(ws, scope)]


async def _tool_sheet(path: str, user: Any) -> dict[str, Any]:
    from app.api.routes import data_browser

    response = await data_browser.data_report(path=path, format="json", status_value=None, current_user=user)
    payload = json.loads(response.body)
    return next(sheet for sheet in payload["sheets"] if sheet["name"] == "Tools")


def test_a_tool_shared_by_two_artisans_in_two_workshops_shows_in_both_workshops(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            admin, w1, w2, a1, a2 = (seed[k] for k in ("admin", "w1", "w2", "a1", "a2"))
            return {
                "seed": seed,
                "w1": await _report_tools(f"by-workshop/{w1.id}", admin),
                "w2": await _report_tools(f"by-workshop/{w2.id}", admin),
                "tree_w1_a1": await _tree_tools(f"by-workshop/{w1.id}/artisans/{a1.id}/tools", admin),
                "tree_w2_a2": await _tree_tools(f"by-workshop/{w2.id}/artisans/{a2.id}/tools", admin),
                "w1_artisans": await _workshop_artisans(w1, admin),
                "w2_artisans": await _workshop_artisans(w2, admin),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    assert sorted(_ours(seen["w1"], seed)) == ["loose", "mine", "shared"]
    assert sorted(_ours(seen["w2"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["tree_w1_a1"], seed)) == ["loose", "mine", "shared"]
    assert sorted(_ours(seen["tree_w2_a2"], seed)) == ["mine", "shared"]
    assert seed["a2"].id in seen["w2_artisans"]
    assert seed["a1"].id not in seen["w2_artisans"]
    assert seed["a2"].id not in seen["w1_artisans"]


def test_a_tool_filed_at_a_workshop_none_of_its_artisans_belong_to_still_shows_there(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            admin, a1, w2 = seed["admin"], seed["a1"], seed["w2"]
            orphan = await _tool(admin, "Awl", [a1], w2)
            seed["extra_tools"] = [orphan.id]
            return {
                "seed": seed,
                "orphan": orphan.id,
                "w2_artisans": await _workshop_artisans(w2, admin),
                "w2": await _report_tools(f"by-workshop/{w2.id}", admin),
                "tree": await _tree_tools(f"by-workshop/{w2.id}/artisans/{a1.id}/tools", admin),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["seed"]["a1"].id in seen["w2_artisans"]
    assert seen["w2"].count(seen["orphan"]) == 1
    assert seen["orphan"] in seen["tree"]


def test_each_tool_appears_once_per_scope_and_rows_name_every_artisan_and_workshop(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            admin, w2 = seed["admin"], seed["w2"]
            return {
                "seed": seed,
                "w2": await _report_tools(f"by-workshop/{w2.id}", admin),
                "root": await _report_tools("by-workshop", admin),
                "sheet": await _tool_sheet(f"by-workshop/{w2.id}", admin),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    assert len(seen["w2"]) == len(set(seen["w2"]))
    assert len(seen["root"]) == len(set(seen["root"]))
    assert _ours(seen["root"], seed).count("shared") == 1
    sheet = seen["sheet"]
    columns = sheet["columns"]
    rows = [r for r in sheet["rows"] if str(r[columns.index("Tool")]).startswith("Wooden block")]
    assert len(rows) == 1
    row = rows[0]
    artisans = str(row[columns.index("Artisan")])
    linked = str(row[columns.index("Linked workshops")])
    assert seed["a1"].name in artisans and seed["a2"].name in artisans
    assert seed["w1"].title in linked and seed["w2"].title in linked


def test_selecting_one_artisan_includes_its_shared_tool_everywhere_it_is_filtered(monkeypatch):
    async def work() -> dict[str, Any]:
        from app.api.routes import tools as tools_route
        from app.services.record_filters import build_record_wheres

        seed = await _seed()
        try:
            admin, a2, w2 = seed["admin"], seed["a2"], seed["w2"]
            ours = {"id": {"in": [seed[k].id for k in ("shared", "loose", "mine")]}}
            wheres = await build_record_wheres(admin, artisan_id=a2.id)
            searched = await _db().tooldocumentation.find_many(
                where={"AND": [wheres["tools"], ours]}
            )
            listed = await tools_route.list_tools(
                current_user=admin, artisanId=a2.id, page=1, pageSize=100
            )
            by_workshop = await tools_route.list_tools(
                current_user=admin, workshopId=w2.id, page=1, pageSize=100
            )
            artisan_report = await _report_tools(
                f"by-workshop/{w2.id}/artisans/{a2.id}", admin
            )
            return {
                "seed": seed,
                "searched": [t.id for t in searched],
                "listed": [row["id"] for row in listed["items"]],
                "by_workshop": [row["id"] for row in by_workshop["items"]],
                "artisan_report": artisan_report,
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    assert sorted(_ours(seen["searched"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["listed"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["by_workshop"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["artisan_report"], seed)) == ["mine", "shared"]


def test_an_artisan_and_workshop_filter_combination_narrows_both_ways(monkeypatch):
    async def work() -> dict[str, Any]:
        from app.api.routes import datasets
        from app.services.record_filters import build_record_wheres

        seed = await _seed()
        try:
            admin, a1, a2, w1, w2 = (seed[k] for k in ("admin", "a1", "a2", "w1", "w2"))
            ours = {"id": {"in": [seed[k].id for k in ("shared", "loose", "mine")]}}

            async def search(artisan: Any, workshop: Any) -> list[str]:
                wheres = await build_record_wheres(
                    admin, artisan_id=artisan.id, workshop_ids=[workshop.id]
                )
                rows = await _db().tooldocumentation.find_many(where={"AND": [wheres["tools"], ours]})
                return [t.id for t in rows]

            async def dataset(workshop_ids: list[str]) -> list[str]:
                where = datasets._build_where(
                    datasets.DATASETS["tools"],
                    workshop_ids=workshop_ids,
                    created_by=None,
                    updated_since=None,
                    created_since=None,
                )
                rows = await _db().tooldocumentation.find_many(where={"AND": [where, ours]})
                return [t.id for t in rows]

            return {
                "seed": seed,
                "a2_in_w2": await search(a2, w2),
                "a1_in_w2": await search(a1, w2),
                "a2_in_w1": await search(a2, w1),
                "ds_w2": await dataset([w2.id]),
                "ds_both": await dataset([w1.id, w2.id]),
                "ds_none": await dataset(["none"]),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    assert sorted(_ours(seen["a2_in_w2"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["a1_in_w2"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["a2_in_w1"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["ds_w2"], seed)) == ["mine", "shared"]
    assert sorted(_ours(seen["ds_both"], seed)) == ["loose", "mine", "shared"]
    assert len(seen["ds_both"]) == len(set(seen["ds_both"]))
    assert _ours(seen["ds_none"], seed) == []


def test_a_user_who_cannot_see_a_workshop_does_not_get_the_tool_through_it(monkeypatch):
    async def work() -> dict[str, Any]:
        from fastapi import HTTPException

        seed = await _seed()
        try:
            researcher, w1, w2 = seed["researcher"], seed["w1"], seed["w2"]
            refused = False
            try:
                await _report_tools(f"by-workshop/{w1.id}", researcher)
            except HTTPException as error:
                refused = error.status_code == 404
            return {
                "seed": seed,
                "refused": refused,
                "w2": await _report_tools(f"by-workshop/{w2.id}", researcher),
                "sheet": await _tool_sheet(f"by-workshop/{w2.id}", researcher),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    assert seen["refused"] is True
    assert _ours(seen["w2"], seed) == ["mine"]
    sheet = seen["sheet"]
    columns = sheet["columns"]
    rows = [r for r in sheet["rows"] if str(r[columns.index("Tool")]).startswith("Brush")]
    assert len(rows) == 1
    linked = str(rows[0][columns.index("Linked workshops")])
    artisans = str(rows[0][columns.index("Artisan")])
    assert seed["w2"].title in linked
    assert seed["w1"].title not in linked
    assert seed["a1"].name not in artisans


def test_the_dataset_bundle_files_the_shared_tool_once_under_each_artisan(monkeypatch):
    async def work() -> dict[str, Any]:
        from app.api.routes import export

        seed = await _seed()
        try:
            manifest = await export.dataset_manifest(
                ownerId=None, stream=False, current_user=seed["admin"]
            )
            return {"seed": seed, "paths": [f["path"] for f in manifest["files"]]}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    shared = [
        p
        for p in seen["paths"]
        if f"/Tools/Wooden block {STAMP}/" in p and p.endswith("details.txt")
    ]
    assert len(shared) == len(set(shared)) == 2, shared
    assert any(seed["w1"].title in p and seed["a1"].name in p for p in shared), shared
    assert any(seed["w2"].title in p and seed["a2"].name in p for p in shared), shared


def test_a_workshop_report_costs_the_same_statements_for_one_shared_tool_or_six(monkeypatch):
    async def build(count: int) -> dict[str, Any]:
        researcher = await _user("RESEARCHER", f"qc{count}")
        home = await _workshop(researcher, f"Home {count}")
        away = await _workshop(researcher, f"Away {count}")
        near = await _artisan(researcher, f"Near {count}", home, joined=True)
        far = await _artisan(researcher, f"Far {count}", away, joined=False)
        tools = [
            await _tool(researcher, f"Tool {count}-{n}", [far, near], away) for n in range(count)
        ]
        return {
            "researcher": researcher,
            "extra_workshops": [home.id, away.id],
            "extra_artisans": [near.id, far.id],
            "extra_tools": [t.id for t in tools],
            "home": home,
        }

    async def work() -> dict[str, Any]:
        from app.api.routes import data_browser

        small = await build(1)
        large = await build(6)
        try:
            counts = {}
            found = {}
            for label, seed in (("small", small), ("large", large)):
                path = f"by-workshop/{seed['home'].id}"
                await data_browser.data_report(
                    path=path, format="json", status_value=None, current_user=seed["researcher"]
                )
                with counting(_db()) as counted:
                    await data_browser.data_report(
                        path=path, format="json", status_value=None, current_user=seed["researcher"]
                    )
                counts[label] = counted.count
                found[label] = await _report_tools(path, seed["researcher"])
            return {"counts": counts, "found": found, "small": small, "large": large}
        finally:
            await _cleanup(small)
            await _cleanup(large)

    seen = _private(monkeypatch, work)
    assert sorted(seen["found"]["small"]) == sorted(seen["small"]["extra_tools"])
    assert sorted(seen["found"]["large"]) == sorted(seen["large"]["extra_tools"])
    assert seen["counts"]["small"] == seen["counts"]["large"], seen["counts"]
