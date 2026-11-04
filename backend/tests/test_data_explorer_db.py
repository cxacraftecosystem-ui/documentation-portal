from __future__ import annotations

import asyncio
import contextlib
import tracemalloc
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx
from qv2_fixtures import connect, needs_db, patch_db
from query_counter import counting

from app.core.security import create_access_token

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]


class _Db:
    def __getattr__(self, name: str) -> Any:
        import app.core.db as core_db

        return getattr(core_db.db, name)


db = _Db()


def _private(monkeypatch: Any, work: Any) -> Any:
    from app.api.routes import data_browser, data_explorer, datasets, export
    from app.services.data_explorer import browse, slim

    assert data_browser and data_explorer and datasets and export and browse and slim

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


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


async def _seed() -> dict[str, Any]:
    admin = await db.user.create(
        data={
            "email": f"dx-admin-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": "Explorer Admin",
            "role": "MASTER_ADMIN",
        }
    )
    workshop = await db.workshop.create(
        data={"title": f"Explorer {STAMP}", "date": datetime.now(UTC), "place": "Bagru", "createdById": admin.id}
    )
    artisan = await db.artisan.create(
        data={"name": f"Kamla {STAMP}", "place": "Bagru", "createdById": admin.id, "workshopId": workshop.id}
    )
    await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
    return {"admin": admin, "workshop": workshop, "artisan": artisan, "media": []}


async def _media(seed: dict[str, Any], kind: str, name: str, size: int, **extra: Any) -> Any:
    mime = {"IMAGE": "image/jpeg", "AUDIO": "audio/mp4", "VIDEO": "video/mp4"}[kind]
    row = await db.mediafile.create(
        data={
            "originalFilename": name,
            "mediaType": kind,
            "mimeType": mime,
            "sizeBytes": size,
            "bucket": "test-bucket",
            "objectKey": f"media/{seed['admin'].id}/{uuid.uuid4().hex}/{name}",
            "uploadedById": seed["admin"].id,
            "artisanId": seed["artisan"].id,
            "workshopId": seed["workshop"].id,
            **extra,
        }
    )
    seed["media"].append(row.id)
    return row


async def _cleanup(seed: dict[str, Any]) -> None:
    for step in (
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.workshopartisan.delete_many(where={"artisanId": seed["artisan"].id}),
        db.artisan.delete_many(where={"id": seed["artisan"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": seed["admin"].id}),
    ):
        with contextlib.suppress(Exception):
            await step


def _consented(monkeypatch: Any) -> None:
    from app.services import transcript_export

    async def allow(ids: list[str], _client: Any) -> dict[str, str]:
        return dict.fromkeys(ids, transcript_export.ALLOW)

    monkeypatch.setattr(transcript_export, "_verdicts", allow)


def _artisan_path(seed: dict[str, Any]) -> str:
    return f"by-workshop/{seed['workshop'].id}/crafts/_none/artisans/{seed['artisan'].id}"


def test_removed_recordings_never_reach_the_tree_manifest_stats_or_download(monkeypatch):
    _consented(monkeypatch)

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            kept = await _media(seed, "IMAGE", "loom.jpg", 1000)
            talk = await _media(seed, "AUDIO", "talk.m4a", 4000, transcriptText="Kamla explains the dye.")
            gone = await _media(seed, "VIDEO", "gone.mp4", 9000, removedAt=datetime.now(UTC))
            path = _artisan_path(seed)
            headers = _auth(seed["admin"])
            async with asgi_client() as client:
                tree = (await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)).json()
                manifest = (await client.get("/api/data/manifest", params={"path": path}, headers=headers)).json()
                folder_stats = (await client.get("/api/data/stats", params={"path": path}, headers=headers)).json()
                images_only = (
                    await client.get("/api/data/stats", params={"path": path, "include": "images"}, headers=headers)
                ).json()
                removed = await client.get(f"/api/data/media/{gone.id}/download", headers=headers)
                transcript = await client.get(f"/api/data/media/{talk.id}/transcript", headers=headers)
                picked = (
                    await client.post(
                        "/api/data/manifest",
                        json={"paths": [path], "mediaIds": [kept.id, gone.id]},
                        headers=headers,
                    )
                ).json()
                bundle = await client.get("/api/data/transcript-bundle", params={"path": path}, headers=headers)
                bad = await client.get("/api/data/stats", params={"path": path, "include": "nonsense"}, headers=headers)
            return {
                "tree": tree,
                "manifest": manifest,
                "stats": folder_stats,
                "images": images_only,
                "removed": removed.status_code,
                "transcript": transcript.json(),
                "picked": picked,
                "bundle": (bundle.status_code, bundle.headers.get("content-type"), bundle.content[:2]),
                "bad": bad.status_code,
                "ids": {"kept": kept.id, "talk": talk.id, "gone": gone.id},
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    ids = seen["ids"]
    tree_ids = {e.get("mediaId") for e in seen["tree"]["entries"]}
    assert ids["gone"] not in tree_ids and {ids["kept"], ids["talk"]} <= tree_ids
    talk_entry = next(e for e in seen["tree"]["entries"] if e.get("mediaId") == ids["talk"])
    assert talk_entry["transcriptPath"] == f"/data/media/{ids['talk']}/transcript"
    assert seen["tree"]["total"] == len(seen["tree"]["entries"]) and seen["tree"]["nextCursor"] is None
    loom = next(e for e in seen["tree"]["entries"] if e.get("mediaId") == ids["kept"])
    assert loom["thumbnailPath"].endswith("/thumbnail")
    manifest_ids = {f.get("mediaId") for f in seen["manifest"]["files"]}
    assert ids["gone"] not in manifest_ids
    assert seen["manifest"]["totalBytes"] >= 5000
    assert seen["stats"]["byType"]["images"] == {"files": 1, "bytes": 1000}
    assert seen["stats"]["byType"]["audios"] == {"files": 1, "bytes": 4000}
    assert seen["stats"]["byType"]["videos"]["files"] == 0
    assert seen["stats"]["byType"]["transcripts"]["files"] >= 1
    assert seen["images"]["files"] == 1 and seen["images"]["bytes"] == 1000
    assert seen["removed"] == 404
    assert seen["transcript"]["content"] == "Kamla explains the dye."
    picked_ids = [f.get("mediaId") for f in seen["picked"]["files"] if f.get("mediaType") == "IMAGE"]
    assert picked_ids.count(ids["kept"]) == 1 and ids["gone"] not in picked_ids
    assert seen["bundle"][0] == 200 and seen["bundle"][1] == "application/zip" and seen["bundle"][2] == b"PK"
    assert seen["bad"] == 422


def test_a_folder_listing_and_its_zip_walk_cost_the_same_statements_at_any_size(monkeypatch):
    async def work() -> dict[str, int]:
        seed = await _seed()
        try:
            path = _artisan_path(seed)
            headers = _auth(seed["admin"])
            out: dict[str, int] = {}
            async with asgi_client() as client:
                for size in (3, 12):
                    while len(seed["media"]) < size:
                        n = len(seed["media"])
                        await _media(seed, "IMAGE", f"p{n}.jpg", 100 + n, transcriptText="words" if n % 2 else None)
                    await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)
                    async with count_statements() as tree:
                        listed = await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)
                    async with count_statements() as walked:
                        manifest = await client.get("/api/data/manifest", params={"path": path}, headers=headers)
                    async with count_statements() as counted:
                        await client.get("/api/data/stats", params={"path": path}, headers=headers)
                    assert listed.status_code == manifest.status_code == 200, manifest.text
                    out[f"tree{size}"] = tree.value
                    out[f"walk{size}"] = walked.value
                    out[f"stats{size}"] = counted.value
            return out
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["tree3"] == seen["tree12"], seen
    assert seen["walk3"] == seen["walk12"], seen
    assert seen["stats3"] == seen["stats12"], seen


def test_a_large_folder_manifest_stays_within_a_memory_bound(monkeypatch):
    async def work() -> tuple[int, int]:
        seed = await _seed()
        try:
            rows = [
                {
                    "originalFilename": f"bulk{n}.jpg",
                    "mediaType": "IMAGE",
                    "mimeType": "image/jpeg",
                    "sizeBytes": 2048,
                    "bucket": "test-bucket",
                    "objectKey": f"media/{seed['admin'].id}/{uuid.uuid4().hex}/bulk{n}.jpg",
                    "uploadedById": seed["admin"].id,
                    "artisanId": seed["artisan"].id,
                    "workshopId": seed["workshop"].id,
                }
                for n in range(300)
            ]
            await db.mediafile.create_many(data=rows)
            made = await db.mediafile.find_many(where={"artisanId": seed["artisan"].id})
            seed["media"].extend(m.id for m in made)
            headers = _auth(seed["admin"])
            async with asgi_client() as client:
                tracemalloc.start()
                try:
                    response = await client.get(
                        "/api/data/manifest", params={"path": _artisan_path(seed), "stream": "true"}, headers=headers
                    )
                    _now, peak = tracemalloc.get_traced_memory()
                finally:
                    tracemalloc.stop()
            lines = [line for line in response.text.splitlines() if line.strip()]
            return len(lines), peak
        finally:
            await _cleanup(seed)

    lines, peak = _private(monkeypatch, work)
    assert lines == 300
    assert peak < 48 * 1024 * 1024, peak


def test_browse_by_type_pages_and_searches_every_row(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            for n in range(3):
                await _media(seed, "IMAGE", f"browse-{STAMP}-{n}.jpg", 10)
            await _media(seed, "IMAGE", f"gone-{STAMP}.jpg", 10, removedAt=datetime.now(UTC))
            headers = _auth(seed["admin"])
            async with asgi_client() as client:
                first = (
                    await client.get(
                        "/api/data/browse",
                        params={"type": "media", "q": f"browse-{STAMP}", "pageSize": 2},
                        headers=headers,
                    )
                ).json()
                last = (
                    await client.get(
                        "/api/data/browse",
                        params={"type": "media", "q": f"browse-{STAMP}", "pageSize": 2, "page": 2},
                        headers=headers,
                    )
                ).json()
                gone = (
                    await client.get(
                        "/api/data/browse", params={"type": "media", "q": f"gone-{STAMP}"}, headers=headers
                    )
                ).json()
                files = (
                    await client.get(
                        f"/api/data/browse/artisan/{seed['artisan'].id}/media",
                        params={"pageSize": 2},
                        headers=headers,
                    )
                ).json()
                artisans = (
                    await client.get(
                        "/api/data/browse", params={"type": "artisan", "q": f"kamla {STAMP}"}, headers=headers
                    )
                ).json()
            return {"first": first, "last": last, "gone": gone, "files": files, "artisans": artisans}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["first"]["total"] == 3 and seen["first"]["truncated"] is True and len(seen["first"]["items"]) == 2
    assert seen["last"]["truncated"] is False and len(seen["last"]["items"]) == 1
    assert seen["gone"]["total"] == 0
    assert seen["files"]["total"] == 3 and seen["files"]["truncated"] is True
    assert all(item["downloadPath"].endswith("/download") for item in seen["files"]["items"])
    assert seen["artisans"]["total"] == 1


def test_table_search_finds_a_row_past_the_preview_and_a_sheet_downloads_as_csv(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            await _media(seed, "IMAGE", f"needle-{STAMP}.jpg", 10)
            headers = _auth(seed["admin"])
            path = f"by-workshop/{seed['workshop'].id}"
            async with asgi_client() as client:
                found = (
                    await client.get(
                        "/api/data/report",
                        params={"path": path, "format": "json", "q": f"needle-{STAMP}", "sheet": "Media by type"},
                        headers=headers,
                    )
                ).json()
                as_csv = await client.get(
                    "/api/data/report",
                    params={"path": path, "format": "csv", "sheet": "Media by type"},
                    headers=headers,
                )
                no_sheet = await client.get(
                    "/api/data/report", params={"path": path, "format": "csv"}, headers=headers
                )
            return {"found": found, "csv": (as_csv.status_code, as_csv.text), "no_sheet": no_sheet.status_code}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    (sheet,) = seen["found"]["sheets"]
    assert sheet["name"] == "Media by type" and sheet["matchedRows"] == 1
    assert seen["csv"][0] == 200 and f"needle-{STAMP}" in seen["csv"][1]
    assert seen["csv"][1].startswith("﻿")
    assert seen["no_sheet"] == 422
