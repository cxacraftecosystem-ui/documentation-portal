from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
from query_counter import counting
from qv2_fixtures import connect, needs_db, patch_db

from app.core.security import create_access_token
from app.services.data_explorer import peaks

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


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


async def _user(role: str, label: str) -> Any:
    return await db.user.create(
        data={
            "email": f"dx-{label}-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": f"Explorer {label}",
            "role": role,
            "canDownloadDataset": True,
        }
    )


async def _seed() -> dict[str, Any]:
    admin = await _user("MASTER_ADMIN", "admin")
    workshop = await db.workshop.create(
        data={"title": f"Memory {STAMP}", "date": datetime.now(UTC), "place": "Bagru", "createdById": admin.id}
    )
    artisan = await db.artisan.create(
        data={"name": f"Kamla {STAMP}", "place": "Bagru", "createdById": admin.id, "workshopId": workshop.id}
    )
    await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
    return {
        "admin": admin,
        "users": [admin.id],
        "workshop": workshop,
        "artisan": artisan,
        "media": [],
        "questions": [],
        "interviews": [],
        "forms": [],
    }


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
        db.mediawaveform.delete_many(where={"mediaFileId": {"in": seed["media"]}}),
        db.questionnaireresponse.delete_many(where={"interviewId": {"in": seed["interviews"]}}),
        db.questionnaireinterview.delete_many(where={"id": {"in": seed["interviews"]}}),
        db.questionnairequestion.delete_many(where={"id": {"in": seed["questions"]}}),
        db.questionnaire.delete_many(where={"id": {"in": seed["forms"]}}),
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.workshopartisan.delete_many(where={"artisanId": seed["artisan"].id}),
        db.artisan.delete_many(where={"id": seed["artisan"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.datasavedview.delete_many(where={"ownerId": {"in": seed["users"]}}),
        db.datafoldervisit.delete_many(where={"userId": {"in": seed["users"]}}),
        db.datareportpreference.delete_many(where={"userId": {"in": seed["users"]}}),
        db.user.delete_many(where={"id": {"in": seed["users"]}}),
    ):
        with contextlib.suppress(Exception):
            await step


def _workshop_path(seed: dict[str, Any]) -> str:
    return f"by-workshop/{seed['workshop'].id}"


def test_report_choices_are_remembered_per_user_and_checked(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            async with asgi_client() as client:
                empty = (await client.get("/api/data/report-options", headers=headers)).json()
                chosen = {
                    "sheets": ["Tools", "Artisans"],
                    "answers": "english",
                    "rows": "filtered",
                    "links": False,
                    "thumbnails": True,
                    "locale": "hi",
                    "extras": {"quotes": True},
                }
                saved = await client.put("/api/data/report-options", json={"options": chosen}, headers=headers)
                again = (await client.get("/api/data/report-options", headers=headers)).json()
                bad = await client.put(
                    "/api/data/report-options", json={"options": {"answers": "klingon"}}, headers=headers
                )
            return {"empty": empty, "saved": saved.json(), "again": again, "bad": bad.status_code, "chosen": chosen}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["empty"] == {"options": None}
    assert seen["saved"] == {"options": seen["chosen"]}
    assert seen["again"] == {"options": seen["chosen"]}
    assert seen["bad"] == 422


def test_a_second_visit_counts_what_arrived_since_the_first(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            path = _workshop_path(seed)
            async with asgi_client() as client:
                first = (await client.post("/api/data/visits", json={"path": path}, headers=headers)).json()
                await db.datafoldervisit.update_many(
                    where={"userId": seed["admin"].id},
                    data={"visitedAt": datetime.now(UTC) - timedelta(hours=1)},
                )
                await _media(seed, "IMAGE", "loom.jpg", 100)
                await _media(seed, "AUDIO", "talk.m4a", 200, transcriptText="The dye is indigo.")
                await _media(
                    seed, "IMAGE", "old.jpg", 50, createdAt=datetime.now(UTC) - timedelta(days=2)
                )
                second = (await client.post("/api/data/visits", json={"path": path}, headers=headers)).json()
                unknown = await client.post(
                    "/api/data/visits", json={"path": "by-workshop/nope"}, headers=headers
                )
            return {"first": first, "second": second, "unknown": unknown.status_code}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["first"]["previousVisitAt"] is None
    assert seen["first"]["newFiles"] == 0 and seen["first"]["newTranscripts"] == 0
    assert seen["second"]["previousVisitAt"] is not None
    assert seen["second"]["newFiles"] == 2
    assert seen["second"]["newTranscripts"] == 1
    assert seen["unknown"] == 404


def test_saved_views_are_private_until_shared_and_only_the_owner_changes_them(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            other = await _user("MASTER_ADMIN", "other")
            seed["users"].append(other.id)
            mine, theirs = _auth(seed["admin"]), _auth(other)
            body = {
                "name": "Kamla tables",
                "path": _workshop_path(seed),
                "state": {"view": "tables", "sheet": "Tools", "q": "loom", "filters": {"type": "audio"}},
                "shared": False,
            }
            async with asgi_client() as client:
                made = (await client.post("/api/data/views", json=body, headers=mine)).json()
                hidden = await client.get(f"/api/data/views/{made['id']}", headers=theirs)
                hijack = await client.patch(
                    f"/api/data/views/{made['id']}", json={"name": "Mine now"}, headers=theirs
                )
                shared = (
                    await client.patch(f"/api/data/views/{made['id']}", json={"shared": True}, headers=mine)
                ).json()
                opened = (await client.get(f"/api/data/views/{made['id']}", headers=theirs)).json()
                listed = (await client.get("/api/data/views", headers=theirs)).json()
                refused = await client.delete(f"/api/data/views/{made['id']}", headers=theirs)
                bad = await client.post("/api/data/views", json={**body, "state": {"view": "map"}}, headers=mine)
                gone = await client.delete(f"/api/data/views/{made['id']}", headers=mine)
                after = await client.get(f"/api/data/views/{made['id']}", headers=mine)
            return {
                "made": made, "hidden": hidden.status_code, "hijack": hijack.status_code, "shared": shared,
                "opened": opened, "listed": listed, "refused": refused.status_code, "bad": bad.status_code,
                "gone": gone.status_code, "after": after.status_code,
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["made"]["mine"] is True and seen["made"]["shared"] is False
    assert seen["made"]["state"] == {"view": "tables", "sheet": "Tools", "q": "loom", "filters": {"type": "audio"}}
    assert seen["made"]["ownerName"] == "Explorer admin"
    assert seen["hidden"] == 404 and seen["hijack"] == 404 and seen["refused"] == 404
    assert seen["shared"]["shared"] is True
    assert seen["opened"]["mine"] is False and seen["opened"]["id"] == seen["made"]["id"]
    assert [v["id"] for v in seen["listed"]["shared"]] == [seen["made"]["id"]]
    assert seen["listed"]["mine"] == []
    assert seen["bad"] == 422
    assert seen["gone"] == 204 and seen["after"] == 404


def test_the_waveform_is_made_once_and_only_for_sound(monkeypatch):
    calls: list[str] = []

    async def fake_peaks(source: str) -> tuple[list[float], int | None]:
        calls.append(source)
        return [0.5] * peaks.BUCKETS, 61000

    monkeypatch.setattr(peaks, "peaks_of", fake_peaks)

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            talk = await _media(seed, "AUDIO", "talk.m4a", 200)
            photo = await _media(seed, "IMAGE", "loom.jpg", 100)
            async with asgi_client() as client:
                first = (await client.get(f"/api/data/media/{talk.id}/peaks", headers=headers)).json()
                second = (await client.get(f"/api/data/media/{talk.id}/peaks", headers=headers)).json()
                image = await client.get(f"/api/data/media/{photo.id}/peaks", headers=headers)
            return {"first": first, "second": second, "image": image.status_code}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert len(seen["first"]["peaks"]) == peaks.BUCKETS and seen["first"]["durationMs"] == 61000
    assert seen["second"] == seen["first"]
    assert len(calls) == 1
    assert seen["image"] == 404


def test_the_workshop_overview_counts_coverage_and_costs_the_same_at_any_size(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            code = f"ZZ{STAMP}"
            form = await db.questionnaire.create(data={"title": f"Overview {STAMP}", "sortOrder": 9000})
            seed["forms"].append(form.id)
            asked = []
            for n in range(3):
                q = await db.questionnairequestion.create(
                    data={
                        "questionnaireId": form.id,
                        "sectionCode": code,
                        "sectionTitle": f"Section {STAMP}",
                        "prompt": f"Prompt {n}",
                        "sortOrder": 9000 + n,
                    }
                )
                asked.append(q)
                seed["questions"].append(q.id)
            interview = await db.questionnaireinterview.create(
                data={"title": f"Talk {STAMP}", "createdById": seed["admin"].id, "questionnaireId": form.id}
            )
            seed["interviews"].append(interview.id)
            await db.questionnaireinterviewartisan.create(
                data={"interviewId": interview.id, "artisanId": seed["artisan"].id}
            )
            await db.questionnaireresponse.create(
                data={"interviewId": interview.id, "questionId": asked[0].id, "answerText": "Indigo", "answeredById": seed["admin"].id}
            )
            await db.questionnaireresponse.create(
                data={"interviewId": interview.id, "questionId": asked[1].id, "answerText": "  ", "answeredById": seed["admin"].id}
            )
            path = _workshop_path(seed)
            costs: dict[int, int] = {}
            body: dict[str, Any] = {}
            async with asgi_client() as client:
                for size in (2, 8):
                    while len(seed["media"]) < size:
                        n = len(seed["media"])
                        await _media(seed, "AUDIO", f"t{n}.m4a", 100, transcriptText="words" if n % 2 else None)
                    await client.get("/api/data/overview", params={"path": path}, headers=headers)
                    async with count_statements() as counted:
                        answer = await client.get("/api/data/overview", params={"path": path}, headers=headers)
                    assert answer.status_code == 200, answer.text
                    costs[size] = counted.value
                    body = answer.json()
                artisan_only = await client.get(
                    "/api/data/overview", params={"path": f"{path}/crafts"}, headers=headers
                )
            return {"body": body, "costs": costs, "code": code, "not": artisan_only.status_code}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    body = seen["body"]
    assert seen["costs"][2] == seen["costs"][8], seen["costs"]
    assert seen["not"] == 404
    assert body["totals"]["files"] >= 8
    assert body["totals"]["transcripts"]["total"] == 8 and body["totals"]["transcripts"]["ready"] == 4
    assert any(s["key"] == seen["code"] for s in body["sections"])
    (artisan,) = body["artisans"]
    assert artisan["media"]["files"] == 8 and artisan["media"]["bytes"] == 800
    section = next(s for s in artisan["sections"] if s["key"] == seen["code"])
    assert section["state"] == "partial"
    assert section["missing"] == ["Prompt 1", "Prompt 2"]
    assert artisan["path"].endswith(f"/artisans/{artisan['id']}")


def test_tree_counts_and_capture_times_cost_the_same_at_any_size(monkeypatch):
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            wid = seed["workshop"].id
            costs: dict[int, int] = {}
            listed: dict[str, Any] = {}
            async with asgi_client() as client:
                for size in (2, 8):
                    while len(seed["media"]) < size:
                        n = len(seed["media"])
                        await _media(seed, "IMAGE" if n % 2 else "AUDIO", f"c{n}.bin", 10)
                    path = f"by-uploader/{wid}"
                    await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)
                    async with count_statements() as counted:
                        answer = await client.get("/api/data/tree", params={"path": path, "slim": "1"}, headers=headers)
                    assert answer.status_code == 200, answer.text
                    costs[size] = counted.value
                listed["people"] = answer.json()
                listed["types"] = (
                    await client.get(
                        "/api/data/tree",
                        params={"path": f"by-uploader/{wid}/{seed['admin'].id}/media", "slim": "1"},
                        headers=headers,
                    )
                ).json()
                listed["files"] = (
                    await client.get(
                        "/api/data/tree",
                        params={"path": f"by-uploader/{wid}/{seed['admin'].id}/media/images", "slim": "1"},
                        headers=headers,
                    )
                ).json()
                listed["workshop"] = (
                    await client.get("/api/data/tree", params={"path": f"by-workshop/{wid}", "slim": "1"}, headers=headers)
                ).json()
            return {"costs": costs, "listed": listed, "admin": seed["admin"].id}
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["costs"][2] == seen["costs"][8], seen["costs"]
    person = next(e for e in seen["listed"]["people"]["entries"] if e["path"].endswith(seen["admin"]))
    assert person["count"] == 8 and person["bytes"] == 80
    types = {e["path"].rsplit("/", 1)[-1]: e for e in seen["listed"]["types"]["entries"]}
    assert types["images"]["count"] == 4 and types["audios"]["count"] == 4
    assert types["transcripts"]["count"] is None
    files = [e for e in seen["listed"]["files"]["entries"] if e["kind"] == "file"]
    assert files and all(isinstance(e["capturedAt"], str) for e in files)
    folders = [e for e in seen["listed"]["workshop"]["entries"] if e["kind"] == "folder"]
    assert folders and all(isinstance(e["count"], int) and isinstance(e["bytes"], int) for e in folders)
