from __future__ import annotations

import asyncio
import json

import httpx
from fastapi import FastAPI

from app.api.routes import data_browser, export

SCHEME = "হ্যালো সরি বাংলা মনোজ কুমার মন্ডল আমি আজকে কলকাতা থেকে এসেছি ।"


def _get(app: FastAPI, path: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://export.test") as client:
            return await client.get(path)

    return asyncio.run(run())


def test_a_csv_export_carries_a_byte_order_mark_so_spreadsheets_read_bengali():
    body = f"ID,Scheme\nw1,{SCHEME}\n"
    response = export.csv_response("Workshop_Export.csv", body)
    raw = bytes(response.body)
    assert raw.startswith(b"\xef\xbb\xbf")
    assert raw.decode("utf-8-sig") == body
    assert "charset=utf-8" in response.headers["content-type"]
    assert SCHEME.encode("utf-8") in raw
    again = export.csv_response("Workshop_Export.csv", raw.decode("utf-8"))
    assert bytes(again.body).count(b"\xef\xbb\xbf") == 1


def test_the_dataset_manifest_names_its_charset_and_sends_bengali_as_itself():
    files = [{"path": "Design workshops/Test/details.txt", "content": f"Workshop title: Test\nScheme: {SCHEME}\n"}]
    app = FastAPI()

    @app.get("/manifest")
    async def manifest():
        return data_browser.manifest_ndjson_response(list(files), 0, False, "dataset.ndjson")

    response = _get(app, "/manifest")
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/x-ndjson; charset=utf-8"
    line = response.content.decode("utf-8").strip()
    assert SCHEME in line
    assert json.loads(line)["content"].endswith(f"Scheme: {SCHEME}\n")
