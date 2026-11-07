from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
from qv2_fixtures import connect, needs_db, patch_db

from app.core.security import create_access_token

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
BASE = datetime(2026, 3, 1, 9, 0, tzinfo=UTC)


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


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


def _consented(monkeypatch: Any) -> None:
    from app.services import transcript_export

    async def allow(ids: list[str], _client: Any) -> dict[str, str]:
        return dict.fromkeys(ids, transcript_export.ALLOW)

    monkeypatch.setattr(transcript_export, "_verdicts", allow)


async def _seed() -> dict[str, Any]:
    admin = await db.user.create(
        data={
            "email": f"sort-admin-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": "Sort Admin",
            "role": "MASTER_ADMIN",
        }
    )
    workshop = await db.workshop.create(
        data={"title": f"Sorting {STAMP}", "date": BASE, "place": "Bagru", "createdById": admin.id}
    )
    artisan = await db.artisan.create(
        data={"name": f"Meera {STAMP}", "place": "Bagru", "createdById": admin.id, "workshopId": workshop.id}
    )
    await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
    return {"admin": admin, "workshop": workshop, "artisan": artisan, "media": []}


SPECS = [
    ("IMAGE", "beta.jpg", 300, 5, None),
    ("AUDIO", "Alpha.m4a", 900, 2, 61000),
    ("VIDEO", "gamma.mp4", 5000, 9, 15000),
    ("IMAGE", "delta.jpg", 300, 1, None),
    ("AUDIO", "epsilon.m4a", 120, 9, None),
    ("IMAGE", "zeta.jpg", 8000, 3, None),
    ("VIDEO", "Eta.mp4", 700, 7, 99000),
]


async def _files(seed: dict[str, Any], *, transcripts: bool = False) -> list[Any]:
    from prisma import Json

    mime = {"IMAGE": "image/jpeg", "AUDIO": "audio/mp4", "VIDEO": "video/mp4"}
    made = []
    for kind, name, size, day, duration in SPECS:
        extra: dict[str, Any] = {}
        if transcripts and kind != "IMAGE":
            extra["transcriptText"] = f"words for {name}"
        row = await db.mediafile.create(
            data={
                "originalFilename": f"{STAMP}-{name}",
                "mediaType": kind,
                "mimeType": mime[kind],
                "sizeBytes": size,
                "bucket": "test-bucket",
                "objectKey": f"media/{seed['admin'].id}/{uuid.uuid4().hex}/{name}",
                "uploadedById": seed["admin"].id,
                "artisanId": seed["artisan"].id,
                "workshopId": seed["workshop"].id,
                "recordedAt": BASE + timedelta(days=day),
                **extra,
            }
        )
        seed["media"].append(row.id)
        if duration is not None:
            await db.mediawaveform.create(data={"mediaFileId": row.id, "peaks": Json([0.1, 0.4]), "durationMs": duration})
        made.append(row)
    return await db.mediafile.find_many(where={"id": {"in": [m.id for m in made]}})


async def _cleanup(seed: dict[str, Any]) -> None:
    for step in (
        db.mediawaveform.delete_many(where={"mediaFileId": {"in": seed["media"]}}),
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.workshopartisan.delete_many(where={"artisanId": seed["artisan"].id}),
        db.artisan.delete_many(where={"id": seed["artisan"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": seed["admin"].id}),
    ):
        with contextlib.suppress(Exception):
            await step


def _artisan_path(seed: dict[str, Any]) -> str:
    return f"by-workshop/{seed['workshop'].id}/crafts/_none/artisans/{seed['artisan'].id}"


def _stamp(value: Any) -> float:
    moment = value if value.tzinfo else value.replace(tzinfo=UTC)
    return moment.timestamp()


DURATIONS = {name: duration for _kind, name, _size, _day, duration in SPECS}


def _value(row: Any, key: str) -> Any:
    kind = str(getattr(row.mediaType, "value", row.mediaType)).split(".")[-1]
    if key == "name":
        return row.originalFilename.casefold()
    if key == "recorded":
        return _stamp(row.recordedAt)
    if key == "added":
        return _stamp(row.createdAt)
    if key == "modified":
        return _stamp(row.updatedAt)
    if key == "size":
        return int(row.sizeBytes)
    if key == "type":
        return kind.casefold()
    if key == "duration":
        return DURATIONS[row.originalFilename.split("-", 1)[1]]
    raise AssertionError(key)


def _expected(rows: list[Any], key: str, direction: str) -> list[str]:
    ordered = sorted(rows, key=lambda r: (r.originalFilename.casefold(), r.id))
    present = [r for r in ordered if _value(r, key) is not None]
    missing = [r for r in ordered if _value(r, key) is None]
    present.sort(key=lambda r: _value(r, key), reverse=direction == "desc")
    return [r.id for r in present + missing]


TREE_CASES = [
    ("name", "asc"),
    ("name", "desc"),
    ("recorded", "desc"),
    ("recorded", "asc"),
    ("added", "desc"),
    ("modified", "asc"),
    ("size", "desc"),
    ("size", "asc"),
    ("type", "asc"),
    ("duration", "desc"),
    ("duration", "asc"),
]


def test_every_tree_sort_key_orders_the_whole_folder_across_pages(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            rows = await _files(seed)
            headers = _auth(seed["admin"])
            out: dict[str, Any] = {"rows": rows, "pages": {}, "bad": []}
            async with asgi_client() as client:
                for key, direction in TREE_CASES:
                    cursor = None
                    pages = []
                    for _ in range(10):
                        params = {"path": _artisan_path(seed), "slim": "1", "limit": "3", "sort": f"{key}:{direction}"}
                        if cursor:
                            params["cursor"] = cursor
                        answer = await client.get("/api/data/tree", params=params, headers=headers)
                        assert answer.status_code == 200, answer.text
                        page = answer.json()
                        pages.append(page)
                        cursor = page.get("nextCursor")
                        if not cursor:
                            break
                    out["pages"][(key, direction)] = pages
                for bad in ("shoe:asc", "name:up", "relevance:desc", "name;drop"):
                    answer = await client.get(
                        "/api/data/tree", params={"path": _artisan_path(seed), "sort": bad}, headers=headers
                    )
                    out["bad"].append(answer.status_code)
                plain = await client.get("/api/data/tree", params={"path": _artisan_path(seed), "slim": "1"}, headers=headers)
                out["plain"] = plain.json()
            return out
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    rows = seen["rows"]
    for (key, direction), pages in seen["pages"].items():
        assert len(pages) >= 3, (key, direction)
        entries = [e for page in pages for e in page["entries"]]
        kinds = [e["kind"] for e in entries]
        assert kinds == sorted(kinds, key=lambda k: 0 if k == "folder" else 1), (key, direction)
        media = [e["mediaId"] for e in entries if e.get("mediaId")]
        assert len(media) == len(set(media)), (key, direction)
        assert media == _expected(rows, key, direction), (key, direction)
        assert all(page["sort"] == {"key": key, "dir": direction} for page in pages)
        assert all(page["total"] == pages[0]["total"] for page in pages)
    sorts = seen["pages"][("name", "asc")][0]["sorts"]
    assert sorts[0] == "name"
    assert {"recorded", "added", "modified", "size", "duration", "type"} <= set(sorts)
    assert not {"workshop", "artisan", "craft", "status"} & set(sorts)
    assert seen["bad"] == [422, 422, 422, 422]
    assert seen["plain"]["sort"] == {"key": "name", "dir": "asc"}
    plain = [e["mediaId"] for e in seen["plain"]["entries"] if e.get("mediaId")]
    assert plain == _expected(rows, "name", "asc")


def test_transcripts_list_sorts_before_it_pages(monkeypatch):
    _consented(monkeypatch)
    from app.api.routes import data_explorer

    monkeypatch.setattr(data_explorer, "TRANSCRIPT_LIST_MAX", 2)

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            rows = await _files(seed, transcripts=True)
            headers = _auth(seed["admin"])
            out: dict[str, Any] = {"rows": rows, "walks": {}}
            async with asgi_client() as client:
                for sort in ("name:desc", "recorded:asc", "duration:desc"):
                    offset: int | None = 0
                    pages = []
                    while offset is not None and len(pages) < 10:
                        answer = await client.get(
                            "/api/data/transcripts/list",
                            params={"path": _artisan_path(seed), "offset": offset, "sort": sort},
                            headers=headers,
                        )
                        assert answer.status_code == 200, answer.text
                        page = answer.json()
                        pages.append(page)
                        offset = page["nextOffset"]
                    out["walks"][sort] = pages
                bad = await client.get(
                    "/api/data/transcripts/list", params={"path": _artisan_path(seed), "sort": "size:desc"}, headers=headers
                )
                out["bad"] = bad.status_code
            return out
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    timed = [r for r in seen["rows"] if r.transcriptText]
    for sort, pages in seen["walks"].items():
        key, direction = sort.split(":")
        listed = [item["mediaId"] for page in pages for item in page["items"]]
        assert listed == _expected(timed, key, direction), sort
        assert all(len(page["items"]) <= 2 for page in pages)
        assert all(not any(k.startswith("_") for k in item) for page in pages for item in page["items"])
        assert pages[0]["sort"] == {"key": key, "dir": direction}
        assert "duration" in pages[0]["sorts"]
    assert seen["bad"] == 422


def test_browse_and_record_media_sort_in_the_database_across_pages(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            rows = await _files(seed)
            headers = _auth(seed["admin"])
            out: dict[str, Any] = {"rows": rows}
            async with asgi_client() as client:
                for sort in ("name:asc", "name:desc", "recorded:desc", "added:asc"):
                    pages = []
                    for page in (1, 2, 3, 4):
                        answer = await client.get(
                            "/api/data/browse",
                            params={"type": "media", "q": STAMP, "pageSize": 2, "page": page, "sort": sort},
                            headers=headers,
                        )
                        assert answer.status_code == 200, answer.text
                        pages.append(answer.json())
                    out[f"browse {sort}"] = pages
                for sort in ("size:desc", "recorded:asc", "type:asc"):
                    pages = []
                    for page in (1, 2, 3, 4):
                        answer = await client.get(
                            f"/api/data/browse/artisan/{seed['artisan'].id}/media",
                            params={"pageSize": 2, "page": page, "sort": sort},
                            headers=headers,
                        )
                        assert answer.status_code == 200, answer.text
                        pages.append(answer.json())
                    out[f"media {sort}"] = pages
                out["bad"] = (
                    await client.get("/api/data/browse", params={"type": "media", "sort": "size:desc"}, headers=headers)
                ).status_code
                out["default"] = (
                    await client.get("/api/data/browse", params={"type": "media", "q": STAMP}, headers=headers)
                ).json()
            return out
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    rows = seen["rows"]
    by_name = {r.id: r.originalFilename for r in rows}
    for sort in ("name:asc", "name:desc", "recorded:desc", "added:asc"):
        key, direction = sort.split(":")
        pages = seen[f"browse {sort}"]
        listed = [item["id"] for page in pages for item in page["items"]]
        assert len(listed) == len(set(listed)) == len(rows), sort
        if key == "name":
            names = [by_name[i].casefold() for i in listed]
            assert names == sorted(names, reverse=direction == "desc"), sort
        else:
            assert listed == _expected(rows, key, direction), sort
        assert pages[0]["sort"] == {"key": key, "dir": direction}
        assert pages[0]["sorts"] == ["name", "recorded", "added", "modified"]
    for sort in ("size:desc", "recorded:asc", "type:asc"):
        key, direction = sort.split(":")
        pages = seen[f"media {sort}"]
        listed = [item["mediaId"] for page in pages for item in page["items"]]
        assert len(listed) == len(set(listed)) == len(rows), sort
        values = [_value(next(r for r in rows if r.id == i), key) for i in listed]
        assert values == sorted(values, reverse=direction == "desc"), sort
    assert seen["bad"] == 422
    assert seen["default"]["sort"] == {"key": "added", "dir": "desc"}
