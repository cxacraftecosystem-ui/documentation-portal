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
    from app.api.routes import data_browser, data_explorer, search
    from app.services.data_explorer import browse, filters, peer, slim

    assert data_browser and data_explorer and search and browse and filters and peer and slim

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


FILES = [
    ("a1", "IMAGE", "loom.jpg", 2, None, "APPROVED"),
    ("a1", "VIDEO", "weaving.mp4", 4, "ben", "APPROVED"),
    ("a2", "AUDIO", "song.m4a", 6, "hin", "PENDING"),
    ("a2", "IMAGE", "block.jpg", 8, None, "PENDING"),
]
MIME = {"IMAGE": "image/jpeg", "AUDIO": "audio/mp4", "VIDEO": "video/mp4"}


async def _seed() -> dict[str, Any]:
    admin = await db.user.create(
        data={"email": f"filter-admin-{STAMP}@example.org", "name": f"Filter Admin {STAMP}", "role": "MASTER_ADMIN"}
    )
    workshop = await db.workshop.create(
        data={"title": f"Filters {STAMP}", "date": BASE, "place": "Bagru", "createdById": admin.id}
    )
    c1 = await db.craft.create(data={"name": f"Dabu {STAMP}"})
    c2 = await db.craft.create(data={"name": f"Kantha {STAMP}"})
    seed: dict[str, Any] = {"admin": admin, "workshop": workshop, "c1": c1, "c2": c2, "media": {}}
    for key, craft in (("a1", c1), ("a2", c2)):
        artisan = await db.artisan.create(
            data={
                "name": f"{key.upper()} {STAMP}",
                "place": "Bagru",
                "createdById": admin.id,
                "workshopId": workshop.id,
                "craftId": craft.id,
            }
        )
        await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
        await db.workshopcraft.create(data={"workshopId": workshop.id, "craftId": craft.id})
        seed[key] = artisan
    for owner, kind, name, day, language, review in FILES:
        row = await db.mediafile.create(
            data={
                "originalFilename": f"{STAMP}-{name}",
                "mediaType": kind,
                "mimeType": MIME[kind],
                "sizeBytes": 100 * day,
                "bucket": "test-bucket",
                "objectKey": f"media/{admin.id}/{uuid.uuid4().hex}/{name}",
                "uploadedById": admin.id,
                "artisanId": seed[owner].id,
                "workshopId": workshop.id,
                "recordedAt": BASE + timedelta(days=day),
                "status": review,
                **({"transcriptLanguage": language, "transcriptText": f"words {name}"} if language else {}),
            }
        )
        seed["media"][name] = row.id
    return seed


async def _cleanup(seed: dict[str, Any]) -> None:
    ids = list(seed["media"].values())
    artisans = [seed[k].id for k in ("a1", "a2") if k in seed]
    for step in (
        db.mediafile.delete_many(where={"id": {"in": ids}}),
        db.workshopartisan.delete_many(where={"artisanId": {"in": artisans}}),
        db.workshopcraft.delete_many(where={"workshopId": seed["workshop"].id}),
        db.artisan.delete_many(where={"id": {"in": artisans}}),
        db.craft.delete_many(where={"id": {"in": [seed["c1"].id, seed["c2"].id]}}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": seed["admin"].id}),
    ):
        with contextlib.suppress(Exception):
            await step


def _folders(tree: dict[str, Any]) -> dict[str, Any]:
    return {e["path"].rsplit("/", 1)[-1]: e for e in tree["entries"] if e["kind"] == "folder"}


def _files(tree: dict[str, Any]) -> set[str]:
    return {e["mediaId"] for e in tree["entries"] if e.get("mediaId")}


def _facet(answer: dict[str, Any], facet: str) -> dict[str, int]:
    found = next(f for f in answer["facets"] if f["facet"] == facet)
    return {o["value"]: o["count"] for o in found["options"]}


def test_filters_narrow_the_tree_counts_contents_browse_tables_and_transcripts(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            wid = seed["workshop"].id
            c1, c2 = seed["c1"].id, seed["c2"].id
            out: dict[str, Any] = {"seed": seed}
            async with asgi_client() as client:

                async def get(url: str, **params: Any) -> Any:
                    answer = await client.get(url, params=params, headers=headers)
                    assert answer.status_code == 200, (url, params, answer.text)
                    return answer.json()

                out["plain"] = await get("/api/data/tree", path=f"by-workshop/{wid}")
                out["craft"] = await get("/api/data/tree", path=f"by-workshop/{wid}", craftIds=c1)
                out["video"] = await get("/api/data/tree", path=f"by-workshop/{wid}", mediaTypes="VIDEO")
                out["none"] = await get("/api/data/tree", path=f"by-workshop/{wid}", mediaTypes="PDF")
                out["top"] = await get("/api/data/tree", path="by-workshop", mediaTypes="AUDIO")
                out["artisan_files"] = await get(
                    "/api/data/tree",
                    path=f"by-workshop/{wid}/crafts/{c1}/artisans/{seed['a1'].id}",
                    mediaTypes="VIDEO",
                )
                out["dated"] = await get(
                    "/api/data/tree",
                    path=f"by-workshop/{wid}",
                    dateFrom=(BASE + timedelta(days=5)).date().isoformat(),
                    dateTo=(BASE + timedelta(days=7)).date().isoformat(),
                )
                out["facets"] = await get("/api/data/filters")
                out["facets_image"] = await get("/api/data/filters", mediaTypes="IMAGE")
                out["facets_c1"] = await get("/api/data/filters", craftIds=c1)
                out["browse"] = await get("/api/data/browse", type="artisan", q=STAMP, craftIds=c1)
                out["browse_media"] = await get("/api/data/browse", type="media", q=STAMP, statuses="PENDING")
                out["stats"] = await get("/api/data/stats", path=f"by-workshop/{wid}", mediaTypes="AUDIO")
                out["transcripts"] = await get("/api/data/transcripts/list", path=f"by-workshop/{wid}", languages="ben")
                out["report"] = await get("/api/data/report", path=f"by-workshop/{wid}", format="json", craftIds=c2)
                out["search"] = await get("/api/search", q=STAMP, types="artisans", craftIds=c2)
                out["bad"] = (await client.get("/api/data/tree", params={"mediaTypes": "MOVIE"}, headers=headers)).status_code
            return out
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    c1, c2 = seed["c1"].id, seed["c2"].id
    media = seed["media"]

    plain = _folders(seen["plain"])
    assert plain[c1]["count"] == 2 and plain[c2]["count"] == 2
    assert seen["plain"]["filters"] == {"active": [], "count": 0} and seen["plain"]["hidden"] == 0

    craft = _folders(seen["craft"])
    assert set(craft) == {c1} and craft[c1]["count"] == 2
    active = seen["craft"]["filters"]["active"]
    assert active[0]["param"] == "craftIds" and active[0]["values"][0]["label"] == f"Dabu {STAMP}"

    video = _folders(seen["video"])
    assert set(video) == {c1} and video[c1]["count"] == 1
    assert seen["video"]["hidden"] == 1

    assert _folders(seen["none"]) == {} and seen["none"]["hidden"] == 2

    top = _folders(seen["top"])
    assert top[seed["workshop"].id]["count"] == 1

    assert _files(seen["artisan_files"]) == {media["weaving.mp4"]}

    assert set(_folders(seen["dated"])) == {c2}

    crafts = _facet(seen["facets"], "craft")
    assert crafts[c1] == 2 and crafts[c2] == 2
    assert _facet(seen["facets_image"], "craft")[c1] == 1
    assert _facet(seen["facets_image"], "mediaType")["VIDEO"] == 1
    narrowed_types = _facet(seen["facets_c1"], "mediaType")
    assert narrowed_types["VIDEO"] == 1 and narrowed_types["AUDIO"] == 0
    assert _facet(seen["facets_c1"], "craft")[c2] == 2
    portal = {o["value"]: o for o in seen["facets"]["portal"]["options"]}
    assert portal["this"]["available"] is True and "other" in portal

    assert [i["id"] for i in seen["browse"]["items"]] == [seed["a1"].id]
    assert seen["browse"]["filters"]["count"] == 1
    assert {i["id"] for i in seen["browse_media"]["items"]} == {media["song.m4a"], media["block.jpg"]}

    by_type = seen["stats"]["byType"]
    assert by_type["audios"]["files"] == 1
    assert by_type["images"]["files"] == 0 and by_type["videos"]["files"] == 0

    assert [i["mediaId"] for i in seen["transcripts"]["items"]] == [media["weaving.mp4"]]

    artisans = next(s for s in seen["report"]["sheets"] if str(s["name"]).lower().startswith("artisan"))
    text = repr(artisans["rows"])
    assert f"A2 {STAMP}" in text and f"A1 {STAMP}" not in text

    found = [row["id"] for row in seen["search"]["artisans"]]
    assert found == [seed["a2"].id]

    assert seen["bad"] == 422


def test_record_type_and_has_transcript_reach_the_tree_browse_transcripts_tables_and_search(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            wid = seed["workshop"].id
            c1 = seed["c1"].id
            out: dict[str, Any] = {"seed": seed}
            async with asgi_client() as client:

                async def get(url: str, **params: Any) -> Any:
                    answer = await client.get(url, params=params, headers=headers)
                    assert answer.status_code == 200, (url, params, answer.text)
                    return answer.json()

                folder = f"by-workshop/{wid}"
                out["artisans"] = await get("/api/data/tree", path=folder, recordTypes="artisans")
                out["products"] = await get("/api/data/tree", path=folder, recordTypes="products")
                out["transcribed"] = await get("/api/data/tree", path=folder, hasTranscript="yes")
                out["plain_files"] = await get(
                    "/api/data/tree",
                    path=f"{folder}/crafts/{c1}/artisans/{seed['a1'].id}",
                    hasTranscript="no",
                )
                out["facets"] = await get("/api/data/filters", craftIds=c1)
                out["browse_products"] = await get("/api/data/browse", type="artisan", q=STAMP, recordTypes="products")
                out["browse_artisans"] = await get("/api/data/browse", type="artisan", q=STAMP, recordTypes="artisans")
                out["browse_media"] = await get("/api/data/browse", type="media", q=STAMP, hasTranscript="yes")
                out["browse_media_off"] = await get("/api/data/browse", type="media", q=STAMP, recordTypes="artisans")
                out["transcripts_yes"] = await get("/api/data/transcripts/list", path=folder, hasTranscript="yes")
                out["transcripts_no"] = await get("/api/data/transcripts/list", path=folder, hasTranscript="no")
                out["report"] = await get("/api/data/report", path=folder, format="json", recordTypes="products")
                out["search_typed"] = await get("/api/search", q=STAMP, types="artisans,media", recordTypes="artisans")
                out["search_transcribed"] = await get("/api/search", q=STAMP, types="artisans,media", hasTranscript="yes")
                out["search_plain"] = await get("/api/search", q=STAMP, types="media", hasTranscript="no")
                out["bad"] = (
                    await client.get("/api/data/tree", params={"recordTypes": "clusters"}, headers=headers)
                ).status_code
            return out
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    seed = seen["seed"]
    c1, c2 = seed["c1"].id, seed["c2"].id
    media = seed["media"]
    a1, a2 = seed["a1"].id, seed["a2"].id

    artisans = _folders(seen["artisans"])
    assert artisans[c1]["count"] == 2 and artisans[c2]["count"] == 2
    assert _folders(seen["products"]) == {} and seen["products"]["hidden"] == 2
    labels = [a["label"] for a in seen["products"]["filters"]["active"]]
    assert labels == ["Record type"]

    transcribed = _folders(seen["transcribed"])
    assert transcribed[c1]["count"] == 1 and transcribed[c2]["count"] == 1
    assert _files(seen["plain_files"]) == {media["loom.jpg"]}

    kinds = _facet(seen["facets"], "recordType")
    assert kinds["artisans"] == 2 and kinds["products"] == 0 and kinds["workshops"] == 0 and kinds["media"] == 2
    said = _facet(seen["facets"], "hasTranscript")
    assert said == {"yes": 1, "no": 1}

    assert seen["browse_products"]["items"] == []
    assert {i["id"] for i in seen["browse_artisans"]["items"]} == {a1, a2}
    assert {i["id"] for i in seen["browse_media"]["items"]} == {media["weaving.mp4"], media["song.m4a"]}
    assert seen["browse_media_off"]["items"] == [] and seen["browse_media_off"]["total"] == 0

    assert {i["mediaId"] for i in seen["transcripts_yes"]["items"]} == {media["weaving.mp4"], media["song.m4a"]}
    assert seen["transcripts_no"]["items"] == [] and seen["transcripts_no"]["nextOffset"] is None

    rows = "".join(
        repr(s.get("rows")) for s in seen["report"]["sheets"] if str(s["name"]).lower().startswith("artisan")
    )
    assert f"A1 {STAMP}" not in rows and f"A2 {STAMP}" not in rows

    typed = seen["search_typed"]
    assert {r["id"] for r in typed["artisans"]} == {a1, a2} and typed["media"] == []
    only = seen["search_transcribed"]
    assert only["artisans"] == []
    assert {r["id"] for r in only["media"]} == {media["weaving.mp4"], media["song.m4a"]}
    assert {r["id"] for r in seen["search_plain"]["media"]} == {media["loom.jpg"], media["block.jpg"]}

    assert seen["bad"] == 422
