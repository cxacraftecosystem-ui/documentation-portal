from __future__ import annotations

import json
import tracemalloc
import uuid
from typing import Any

from qv2_fixtures import connect, needs_db, patch_db, run
from query_counter import counting

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
SEEDED = 14
PREVIEW = 5
RUNS = 10
MIB = 1024 * 1024


async def _seed(client: Any) -> dict[str, Any]:
    admin = await client.user.create(
        data={"email": f"d39-{STAMP}-{uuid.uuid4().hex[:4]}@example.org", "name": "Report Admin", "role": "MASTER_ADMIN"}
    )
    media = []
    for n in range(SEEDED):
        media.append(
            await client.mediafile.create(
                data={
                    "originalFilename": f"d39-{STAMP}-{n:02d}.m4a",
                    "mediaType": "AUDIO",
                    "mimeType": "audio/mp4",
                    "sizeBytes": 1024,
                    "bucket": "ci",
                    "objectKey": f"media/{admin.id}/d39-{STAMP}-{n}.m4a",
                    "transcriptText": f"**Speaker 1:** words {n} " + "dabu " * 4000,
                    "transcriptStatus": "COMPLETED",
                    "uploadedBy": {"connect": {"id": admin.id}},
                }
            )
        )
    return {"admin": admin, "media": media}


async def _cleanup(client: Any, seed: dict[str, Any]) -> None:
    await client.mediafile.delete_many(where={"id": {"in": [m.id for m in seed["media"]]}})
    await client.user.delete_many(where={"id": seed["admin"].id})


def _ours(sheet: dict[str, Any]) -> list[list[Any]]:
    at = sheet["columns"].index("File")
    return [row for row in sheet["rows"] if str(row[at]).startswith(f"d39-{STAMP}-")]


async def _ten_reports(monkeypatch: Any) -> dict[str, Any]:
    from app.api.routes import data_browser
    from app.services import transcript_export  # noqa: F401

    client = await connect()
    patch_db(monkeypatch, client)
    seed = await _seed(client)
    try:
        statements: list[int] = []
        peaks: list[int] = []
        currents: list[int] = []
        tracemalloc.start()
        try:
            for _ in range(RUNS):
                tracemalloc.reset_peak()
                with counting(client) as seen:
                    response = await data_browser.data_report(
                        path="", format="json", status_value=None, current_user=seed["admin"]
                    )
                assert response.status_code == 200
                del response
                statements.append(seen.count)
                current, peak = tracemalloc.get_traced_memory()
                peaks.append(peak)
                currents.append(current)
        finally:
            tracemalloc.stop()
        return {"statements": statements, "peaks": peaks, "currents": currents}
    finally:
        await _cleanup(client, seed)
        await client.disconnect()


def test_ten_root_reports_keep_statements_and_memory_flat(monkeypatch):
    seen = run(_ten_reports(monkeypatch))
    statements, peaks, currents = seen["statements"], seen["peaks"], seen["currents"]
    assert len(set(statements[1:])) == 1, f"round trips per root report grew: {statements}"
    assert statements[0] >= statements[1], statements
    assert max(peaks[2:]) <= peaks[1] * 1.25 + 4 * MIB, f"peak heap per build: {peaks}"
    assert currents[-1] - currents[1] <= 4 * MIB, f"heap held between builds: {currents}"


async def _preview_and_download(monkeypatch: Any) -> dict[str, Any]:
    from app.api.routes import data_browser
    from app.services import transcript_export  # noqa: F401

    monkeypatch.setattr(data_browser, "PREVIEW_ROWS", PREVIEW)
    handed: dict[str, Any] = {}
    real_build = data_browser.build_report_workbook

    def capture(sheets: list[dict[str, Any]], title: str, **_kwargs: Any) -> bytes:
        handed["sheets"] = sheets
        return real_build(sheets, title)

    monkeypatch.setattr(data_browser, "build_report_workbook", capture)
    monkeypatch.setattr(data_browser, "_rendered", lambda sheets: sheets)

    client = await connect()
    patch_db(monkeypatch, client)
    seed = await _seed(client)
    try:
        shown = await data_browser.data_report(path="", format="json", status_value=None, current_user=seed["admin"])
        download = await data_browser.data_report(path="", format="xlsx", status_value=None, current_user=seed["admin"])
        body = bytes(download.body)
        return {"json": json.loads(shown.body), "xlsx": body, "sheets": handed["sheets"]}
    finally:
        await _cleanup(client, seed)
        await client.disconnect()


def test_the_json_preview_is_cut_and_the_xlsx_gets_every_row(monkeypatch):
    seen = run(_preview_and_download(monkeypatch))
    payload = seen["json"]
    assert payload["previewRows"] == PREVIEW
    by_name = {sheet["name"]: sheet for sheet in payload["sheets"]}
    media = by_name["Media by type"]
    assert len(media["rows"]) == PREVIEW
    assert media["truncated"] is True and media["preview"] is True
    assert media["totalRows"] >= SEEDED

    assert seen["xlsx"][:2] == b"PK"
    full = {sheet["name"]: sheet for sheet in seen["sheets"]}
    assert len(full["Media by type"]["rows"]) == media["totalRows"]
    assert len(_ours(full["Media by type"])) == SEEDED
    words = _ours(full["Transcripts"])
    assert len(words) == SEEDED
    assert all("words" in str(row[-1]) for row in words)
