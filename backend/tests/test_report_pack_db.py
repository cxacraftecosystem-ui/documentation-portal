from __future__ import annotations

import asyncio
import contextlib
import io
import uuid
import zipfile
from datetime import UTC, datetime
from typing import Any

import httpx
from pypdf import PdfReader
from qv2_fixtures import connect, needs_db, patch_db

from app.core.security import create_access_token
from prisma import Json

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
SEGMENTS = [
    {"startMs": 0, "endMs": 2400, "speaker": "0", "text": "हम नील से रंगते हैं।"},
    {"startMs": 2400, "endMs": 5100, "speaker": "1", "text": "यह हमारे दादा से आया।"},
]


class _Db:
    def __getattr__(self, name: str) -> Any:
        import app.core.db as core_db

        return getattr(core_db.db, name)


db = _Db()


def _private(monkeypatch: Any, work: Any) -> Any:
    from app.api.routes import data_browser, data_explorer, data_report_pack
    from app.services.data_explorer import overview, slim
    from app.services.datareport import pack

    assert data_browser and data_explorer and data_report_pack and overview and slim and pack

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

    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://pack.test")


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


def _consented(monkeypatch: Any) -> None:
    from app.services import transcript_export

    async def allow(ids: list[str], _client: Any) -> dict[str, str]:
        return dict.fromkeys(ids, transcript_export.ALLOW)

    monkeypatch.setattr(transcript_export, "_verdicts", allow)


async def _user(role: str, **extra: Any) -> Any:
    return await db.user.create(
        data={
            "email": f"pack-{role.lower()}-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": f"Pack {role.title()}",
            "role": role,
            **extra,
        }
    )


async def _seed() -> dict[str, Any]:
    admin = await _user("MASTER_ADMIN")
    workshop = await db.workshop.create(
        data={"title": f"Pack {STAMP}", "date": datetime.now(UTC), "place": "Bagru", "createdById": admin.id}
    )
    artisan = await db.artisan.create(
        data={"name": f"Kamla {STAMP}", "place": "Bagru", "createdById": admin.id, "workshopId": workshop.id}
    )
    await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
    seed: dict[str, Any] = {"admin": admin, "workshop": workshop, "artisan": artisan, "media": [], "users": [admin.id]}
    for kind, name, extra in (
        ("IMAGE", "loom.jpg", {}),
        (
            "AUDIO",
            "talk.m4a",
            {
                "transcriptText": "हम नील से रंगते हैं।\n\nयह हमारे दादा से आया।",
                "transcriptSegments": Json(SEGMENTS),
                "transcriptStatus": "COMPLETED",
                "transcriptLanguage": "hi",
            },
        ),
    ):
        row = await db.mediafile.create(
            data={
                "originalFilename": name,
                "mediaType": kind,
                "mimeType": "image/jpeg" if kind == "IMAGE" else "audio/mp4",
                "sizeBytes": 4096,
                "bucket": "test-bucket",
                "objectKey": f"media/{admin.id}/{uuid.uuid4().hex}/{name}",
                "uploadedById": admin.id,
                "artisanId": artisan.id,
                "workshopId": workshop.id,
                **extra,
            }
        )
        seed["media"].append(row.id)
        seed[kind] = row
    await db.transcriptinsight.create(
        data={
            "mediaFileId": seed["AUDIO"].id,
            "version": 1,
            "sourceHash": "0" * 64,
            "sourceLanguage": "hi",
            "summaryLines": ["Indigo dyeing"],
            "quotes": Json([{"original": "हम नील से रंगते हैं।", "english": "We dye with indigo.", "speaker": "Kamla"}]),
            "translationStatus": "COMPLETED",
            "provider": "test",
            "modelId": "test",
            "promptVersion": "1",
            "generated": Json({}),
            "generatedAt": datetime.now(UTC),
        }
    )
    return seed


async def _cleanup(seed: dict[str, Any]) -> None:
    for step in (
        db.transcriptinsight.delete_many(where={"mediaFileId": {"in": seed["media"]}}),
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.workshopartisan.delete_many(where={"artisanId": seed["artisan"].id}),
        db.artisan.delete_many(where={"id": seed["artisan"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": {"in": seed["users"]}}),
    ):
        with contextlib.suppress(Exception):
            await step


def test_the_report_pack_holds_the_workbook_summary_transcripts_and_readme(monkeypatch):
    _consented(monkeypatch)

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            path = f"by-workshop/{seed['workshop'].id}"
            async with asgi_client() as client:
                packed = await client.get("/api/data/report/pack", params={"path": path}, headers=_auth(seed["admin"]))
                bad = await client.get(
                    "/api/data/report/pack", params={"path": path, "answers": "klingon"}, headers=_auth(seed["admin"])
                )
            return {
                "status": packed.status_code,
                "type": packed.headers.get("content-type"),
                "disposition": packed.headers.get("content-disposition"),
                "body": packed.content,
                "bad": (bad.status_code, bad.headers.get("content-type")),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["status"] == 200, seen["body"][:400]
    assert seen["type"] == "application/zip"
    assert "Report-Pack" in seen["disposition"] and ".zip" in seen["disposition"]
    archive = zipfile.ZipFile(io.BytesIO(seen["body"]))
    assert archive.testzip() is None
    names = archive.namelist()
    workbook = next(n for n in names if n.endswith(".xlsx"))
    assert archive.read(workbook).startswith(b"PK\x03\x04")
    summary = next(n for n in names if n.endswith(".pdf"))
    reader = PdfReader(io.BytesIO(archive.read(summary)))
    text = "\n".join(page.extract_text() or "" for page in reader.pages)
    assert "Report summary" in text and "Media by type" in text and "Transcript status" in text
    assert "We dye with indigo." in text
    assert any(n.startswith("Transcripts/") and n.endswith(".docx") for n in names), names
    assert any(n.startswith("Transcripts/") and n.endswith(".srt") for n in names), names
    readme = archive.read("README.txt").decode("utf-8")
    for name in names:
        assert name in readme, name
    assert seen["bad"][0] == 422 and "json" in str(seen["bad"][1])


def test_the_report_pack_is_permission_checked(monkeypatch):
    _consented(monkeypatch)

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            plain = await _user("RESEARCHER")
            granted = await _user("RESEARCHER", canDownloadDataset=True)
            seed["users"] += [plain.id, granted.id]
            path = f"by-workshop/{seed['workshop'].id}"
            async with asgi_client() as client:
                refused = await client.get("/api/data/report/pack", params={"path": path}, headers=_auth(plain))
                other = await client.get("/api/data/report/pack", params={"path": path}, headers=_auth(granted))
                anonymous = await client.get("/api/data/report/pack", params={"path": path})
            return {
                "refused": refused.status_code,
                "anonymous": anonymous.status_code,
                "other": (other.status_code, other.headers.get("content-type"), other.content),
            }
        finally:
            await _cleanup(seed)

    seen = _private(monkeypatch, work)
    assert seen["refused"] == 403
    assert seen["anonymous"] == 401
    status_code, kind, body = seen["other"]
    if status_code == 200:
        assert kind == "application/zip"
        names = zipfile.ZipFile(io.BytesIO(body)).namelist()
        assert not any(n.startswith("Transcripts/") for n in names), names
    else:
        assert status_code in (403, 404) and "json" in str(kind)
