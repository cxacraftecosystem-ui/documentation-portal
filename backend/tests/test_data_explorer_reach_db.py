from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx
from query_counter import counting
from qv2_fixtures import connect, needs_db, patch_db

from app.core.security import create_access_token

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]


class _Db:
    def __getattr__(self, name: str) -> Any:
        import app.core.db as core_db

        return getattr(core_db.db, name)


db = _Db()


def _private(monkeypatch: Any, work: Any) -> Any:
    from app.api.routes import data_browser, data_explorer
    from app.services.data_explorer import overview, slim

    assert data_browser and data_explorer and overview and slim

    async def connected() -> Any:
        client = await connect()
        patch_db(monkeypatch, client)
        try:
            return await work()
        finally:
            await client.disconnect()

    return asyncio.run(connected())


def asgi_client() -> httpx.AsyncClient:
    from app.main import app

    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://explorer.test")


class _Count:
    value = 0


@contextlib.asynccontextmanager
async def count_statements() -> Any:
    import app.core.db as core_db

    box = _Count()
    with counting(core_db.db) as seen:
        yield box
    box.value = seen.count


async def _questionnaire_id(seed: dict[str, Any]) -> dict[str, Any]:
    form = await db.questionnaire.create(data={"title": f"Reach {STAMP}", "sortOrder": 9000})
    seed["extra"].append(form.id)
    return {"questionnaireId": form.id}


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


async def _artisan(seed: dict[str, Any], name: str, **extra: Any) -> Any:
    row = await db.artisan.create(
        data={"name": f"{name} {STAMP}", "place": "Bagru", "createdById": seed["admin"].id, **extra}
    )
    seed["artisans"].append(row.id)
    return row


async def _file(seed: dict[str, Any], name: str, size: int, **links: Any) -> Any:
    row = await db.mediafile.create(
        data={
            "originalFilename": name,
            "mediaType": "IMAGE",
            "mimeType": "image/jpeg",
            "sizeBytes": size,
            "bucket": "test-bucket",
            "objectKey": f"media/{seed['admin'].id}/{uuid.uuid4().hex}/{name}",
            "uploadedById": seed["admin"].id,
            **links,
        }
    )
    seed["media"].append(row.id)
    return row


async def _seed() -> dict[str, Any]:
    admin = await db.user.create(
        data={
            "email": f"dx-reach-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": "Reach Admin",
            "role": "MASTER_ADMIN",
            "canDownloadDataset": True,
        }
    )
    seed: dict[str, Any] = {"admin": admin, "artisans": [], "media": [], "extra": []}
    owner = {"place": "Bagru", "createdById": admin.id}
    seed["workshop"] = await db.workshop.create(
        data={"title": f"Reach {STAMP}", "date": datetime.now(UTC), **owner}
    )
    wid = seed["workshop"].id
    seed["craft"] = await db.craft.create(data={"name": f"Dabu {STAMP}"})
    await db.workshopcraft.create(data={"workshopId": wid, "craftId": seed["craft"].id})
    seed["a1"] = await _artisan(seed, "Kamla", workshopId=wid, craftId=seed["craft"].id)
    seed["a2"] = await _artisan(seed, "Asha")
    await db.workshopartisan.create(data={"workshopId": wid, "artisanId": seed["a2"].id})
    seed["a9"] = await _artisan(seed, "Orphan")
    seed["product"] = await db.productdocumentation.create(
        data={"productName": f"Stole {STAMP}", "artisanName": seed["a1"].name, "artisanId": seed["a1"].id,
              "craftName": seed["craft"].name, **owner}
    )
    seed["process"] = await db.process.create(
        data={"name": f"Vat {STAMP}", "productId": seed["product"].id, "createdById": admin.id}
    )
    seed["step"] = await db.processstep.create(
        data={"processId": seed["process"].id, "name": "Ferment", "sortOrder": 1}
    )
    seed["tool"] = await db.tooldocumentation.create(
        data={"toolkitName": f"Block {STAMP}", "englishName": "Block", "artisanName": seed["a1"].name,
              "artisanId": seed["a1"].id, "craftName": seed["craft"].name, **owner}
    )
    seed["filed"] = await db.tooldocumentation.create(
        data={"toolkitName": f"Filed {STAMP}", "englishName": "Filed", "artisanName": seed["a9"].name,
              "artisanId": seed["a9"].id, "craftName": seed["craft"].name, "workshopId": wid, **owner}
    )
    seed["interview"] = await db.questionnaireinterview.create(
        data={"title": f"Talk {STAMP}", "createdById": admin.id, **(await _questionnaire_id(seed))}
    )
    await db.questionnaireinterviewartisan.create(
        data={"interviewId": seed["interview"].id, "artisanId": seed["a2"].id}
    )
    await _file(seed, "misc.jpg", 1, workshopId=wid)
    await _file(seed, "craft.jpg", 2, craftId=seed["craft"].id)
    await _file(seed, "own.jpg", 4, artisanId=seed["a1"].id)
    await _file(seed, "product.jpg", 8, productId=seed["product"].id)
    await _file(seed, "process.jpg", 16, linkedRecordType="process", linkedRecordId=seed["process"].id)
    await _file(seed, "step.jpg", 32, linkedRecordType="processstep", linkedRecordId=seed["step"].id)
    await _file(seed, "tool.jpg", 64, toolId=seed["tool"].id)
    await _file(seed, "filed.jpg", 128, toolId=seed["filed"].id)
    await _file(seed, "talk.jpg", 256, questionnaireInterviewId=seed["interview"].id)
    await _file(seed, "asha.jpg", 512, artisanId=seed["a2"].id)
    await _file(seed, "gone.jpg", 1024, artisanId=seed["a1"].id, removedAt=datetime.now(UTC))
    return seed


async def _cleanup(seed: dict[str, Any]) -> None:
    ids = seed["artisans"]
    for step in (
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.processstep.delete_many(where={"id": seed["step"].id}),
        db.process.delete_many(where={"id": seed["process"].id}),
        db.productdocumentation.delete_many(where={"id": seed["product"].id}),
        db.tooldocumentation.delete_many(where={"id": {"in": [seed["tool"].id, seed["filed"].id]}}),
        db.questionnaireinterviewartisan.delete_many(where={"interviewId": seed["interview"].id}),
        db.questionnaireinterview.delete_many(where={"id": seed["interview"].id}),
        db.workshopartisan.delete_many(where={"artisanId": {"in": ids}}),
        db.workshopcraft.delete_many(where={"workshopId": seed["workshop"].id}),
        db.artisan.delete_many(where={"id": {"in": ids}}),
        db.craft.delete_many(where={"id": seed["craft"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": seed["admin"].id}),
        *(db.questionnaire.delete_many(where={"id": i}) for i in seed["extra"]),
    ):
        with contextlib.suppress(Exception):
            await step


async def _walked(path: str, scope: Any) -> tuple[int, int]:
    from app.api.routes import data_browser
    from app.services.data_explorer import walk

    files, _truncated = await walk.manifest_files(data_browser._list_level, scope, [(path, "")], None, fill=False)
    sizes: dict[str, int] = {}
    for entry in files:
        if walk._stored_file(entry):
            sizes.setdefault(str(entry["mediaId"]), int(entry.get("sizeBytes") or 0))
    return len(sizes), sum(sizes.values())


def test_every_by_workshop_folder_counts_what_its_zip_walk_holds(monkeypatch):
    async def work() -> dict[str, Any]:
        from app.api.routes import data_browser

        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            scope = await data_browser._scope_for(seed["admin"])
            wid = seed["workshop"].id
            craft = seed["craft"].id
            a1 = f"by-workshop/{wid}/crafts/{craft}/artisans/{seed['a1'].id}"
            levels = [
                f"by-workshop/{wid}",
                f"by-workshop/{wid}/crafts/{craft}",
                f"by-workshop/{wid}/crafts/_none",
                a1,
                f"{a1}/products",
                f"{a1}/products/{seed['product'].id}",
                f"{a1}/products/{seed['product'].id}/processes",
                f"{a1}/products/{seed['product'].id}/processes/{seed['process'].id}",
                f"{a1}/tools",
                f"by-workshop/{wid}/crafts/_none/artisans/{seed['a2'].id}",
                f"by-workshop/{wid}/crafts/_none/artisans/{seed['a2'].id}/questionnaire",
            ]
            checked: list[tuple[str, Any, Any]] = []
            async with asgi_client() as client:
                root = (await client.get("/api/data/tree", params={"path": "by-workshop", "slim": "1"}, headers=headers)).json()
                mine = [e for e in root["entries"] if e["path"] == f"by-workshop/{wid}"]
                for entry in mine:
                    checked.append((entry["path"], (entry["count"], entry["bytes"]), await _walked(entry["path"], scope)))
                for level in levels:
                    answer = await client.get("/api/data/tree", params={"path": level, "slim": "1"}, headers=headers)
                    assert answer.status_code == 200, (level, answer.text)
                    for entry in answer.json()["entries"]:
                        if entry["kind"] != "folder":
                            continue
                        checked.append(
                            (entry["path"], (entry["count"], entry["bytes"]), await _walked(entry["path"], scope))
                        )
            return {"checked": checked, "workshop": f"by-workshop/{wid}"}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert len(seen["checked"]) >= 12
    for path, told, walked in seen["checked"]:
        assert told == walked, (path, told, walked)
    workshop = next(t for p, t, _w in seen["checked"] if p == seen["workshop"])
    assert workshop == (10, 1 + 2 + 4 + 8 + 16 + 32 + 64 + 128 + 256 + 512)


def test_a_craft_listing_costs_the_same_with_two_or_eight_artisans(monkeypatch):
    async def work() -> dict[int, int]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            wid = seed["workshop"].id
            path = f"by-workshop/{wid}/crafts/{seed['craft'].id}"
            costs: dict[int, int] = {}
            async with asgi_client() as client:
                for size in (2, 8):
                    while len(seed["artisans"]) < size + 2:
                        made = await _artisan(seed, f"Weaver {len(seed['artisans'])}", workshopId=wid, craftId=seed["craft"].id)
                        await _file(seed, f"w{made.id}.jpg", 3, artisanId=made.id)
                    await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)
                    async with count_statements() as counted:
                        answer = await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)
                    assert answer.status_code == 200, answer.text
                    folders = [e for e in answer.json()["entries"] if e["kind"] == "folder"]
                    assert len(folders) == size
                    assert all(isinstance(e["count"], int) for e in folders)
                    costs[size] = counted.value
            return costs
        finally:
            await _cleanup(seed)

    costs = _private(monkeypatch, work)
    assert costs[2] == costs[8], costs
