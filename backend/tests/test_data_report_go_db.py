from __future__ import annotations

import uuid
from typing import Any

import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, patch_db, run

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]


async def _seed(db: Any) -> dict[str, Any]:
    owner = await db.user.create(
        data={"email": f"go-owner-{STAMP}@example.org", "name": "Owner", "role": "RESEARCHER", "canDownloadDataset": False}
    )
    stranger = await db.user.create(
        data={"email": f"go-stranger-{STAMP}@example.org", "name": "Stranger", "role": "FIELD_CONTRIBUTOR", "canDownloadDataset": False}
    )
    media = await db.mediafile.create(
        data={
            "originalFilename": f"go-{STAMP}.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 1024,
            "bucket": "ci",
            "objectKey": f"media/{owner.id}/go-{STAMP}.m4a",
            "transcriptText": "**Speaker 1:** clay and gum",
            "transcriptStatus": "COMPLETED",
            "uploadedBy": {"connect": {"id": owner.id}},
        }
    )
    artisan = await db.artisan.create(
        data={"name": f"Go Artisan {STAMP}", "place": "Bagru", "createdBy": {"connect": {"id": owner.id}}}
    )
    return {"owner": owner, "stranger": stranger, "media": media, "artisan": artisan}


async def _cleanup(db: Any, seed: dict[str, Any]) -> None:
    await db.mediafile.delete_many(where={"id": seed["media"].id})
    await db.artisan.delete_many(where={"id": seed["artisan"].id})
    await db.user.delete_many(where={"id": {"in": [seed["owner"].id, seed["stranger"].id]}})


async def _status(call: Any) -> Any:
    try:
        return await call
    except HTTPException as refused:
        return refused.status_code


def _patched(monkeypatch: pytest.MonkeyPatch) -> Any:
    from app.api.routes import data_browser, data_explorer, data_report
    from app.services import records, transcript_export, transcript_portal
    from app.services.data_explorer import downloads, slim
    from app.services.datareport import access

    return data_report, (data_browser, data_explorer, records, transcript_export, transcript_portal, downloads, slim, access)


def test_a_viewer_without_dataset_access_gets_what_they_can_see(monkeypatch: pytest.MonkeyPatch) -> None:
    data_report, _loaded = _patched(monkeypatch)

    async def work() -> dict[str, Any]:
        db = await connect()
        patch_db(monkeypatch, db)
        seed = await _seed(db)
        try:
            owner, stranger, media, artisan = seed["owner"], seed["stranger"], seed["media"], seed["artisan"]
            return {
                "owner_file": await _status(data_report.go_download(media.id, "json", owner)),
                "owner_redirect": await _status(data_report.go_download(media.id, None, owner)),
                "stranger_file": await _status(data_report.go_download(media.id, "json", stranger)),
                "missing_file": await _status(data_report.go_download(f"nope-{STAMP}", "json", owner)),
                "owner_open_media": await _status(data_report.go_open("media", media.id, owner)),
                "stranger_open_artisan": await _status(data_report.go_open("artisan", artisan.id, stranger)),
                "missing_open": await _status(data_report.go_open("artisan", f"nope-{STAMP}", owner)),
            }
        finally:
            await _cleanup(db, seed)
            await db.disconnect()

    seen = run(work())
    assert seen["owner_file"].status_code == 200
    assert seen["owner_redirect"].status_code == 307
    assert seen["stranger_file"] == 403
    assert seen["missing_file"] == 404
    assert seen["owner_open_media"]["href"].startswith("/media/")
    assert seen["stranger_open_artisan"]["href"].startswith("/artisans/")
    assert seen["missing_open"] == 404


@pytest.mark.parametrize("kind", ["workshop", "product", "tool", "process", "interview", "craft"])
def test_open_answers_404_for_a_record_that_does_not_exist(kind: str, monkeypatch: pytest.MonkeyPatch) -> None:
    data_report, _loaded = _patched(monkeypatch)

    async def work() -> Any:
        db = await connect()
        patch_db(monkeypatch, db)
        try:
            user = await db.user.create(data={"email": f"go-{kind}-{STAMP}@example.org", "name": "Viewer", "role": "RESEARCHER"})
            try:
                return await _status(data_report.go_open(kind, f"nope-{STAMP}", user))
            finally:
                await db.user.delete(where={"id": user.id})
        finally:
            await db.disconnect()

    assert run(work()) == 404
