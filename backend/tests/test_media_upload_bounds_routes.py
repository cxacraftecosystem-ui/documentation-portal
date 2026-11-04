import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.api.routes.media as media_routes
from app.api.router import api_router
from app.core import deps

from tests.test_upload_bounds import CARD_REMEDY, DICTATION_REMEDY

MB = 1024 * 1024

RESEARCHER = SimpleNamespace(
    id="user-1",
    email="researcher@example.test",
    name="Researcher",
    role="ADMIN",
)

_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: RESEARCHER


def _post(path: str, *, files: Any = None, params: Any = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://routes.test") as client:
            return await client.post(f"/api{path}", files=files, params=params)

    return asyncio.run(run())


class _Twin:
    def __init__(self, answer: dict[str, Any] | None = None) -> None:
        self.calls: list[tuple[tuple, dict]] = []
        self._answer = answer or {"available": True, "status": "COMPLETED"}

    async def __call__(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
        self.calls.append((args, kwargs))
        return self._answer


@pytest.fixture
def transcriber(monkeypatch: pytest.MonkeyPatch) -> _Twin:
    twin = _Twin({"available": True, "status": "COMPLETED", "text": "hello"})
    monkeypatch.setattr(media_routes, "transcribe_audio_bytes", twin)

    async def _unpaused(*_a: Any, **_k: Any) -> bool:
        return False

    monkeypatch.setattr(media_routes, "load_transcription_paused", _unpaused)
    monkeypatch.setattr(media_routes.transcription, "is_paused", _unpaused)
    return twin


@pytest.fixture
def analyser(monkeypatch: pytest.MonkeyPatch) -> _Twin:
    twin = _Twin({"available": True, "status": "COMPLETED", "analysis": {"valueInches": 12}})
    monkeypatch.setattr(media_routes, "analyze_measurement_image_bytes", twin)
    return twin



def test_an_oversized_dictation_clip_is_refused_before_a_provider_is_paid(transcriber: _Twin):
    oversized = b"\x00" * (26 * MB)

    response = _post("/media/transcribe", files={"file": ("long.webm", oversized, "audio/webm")})

    assert response.status_code == 413, response.status_code
    detail = response.json()["detail"]
    assert "25 MB" in detail
    assert "recording" in detail
    assert transcriber.calls == []


def test_an_oversized_grid_sheet_photograph_is_refused_at_eight_megabytes(analyser: _Twin):
    oversized = b"\xff\xd8" + b"\x00" * (9 * MB)

    response = _post(
        "/media/analyze-measurement", files={"file": ("sheet.jpg", oversized, "image/jpeg")}
    )

    assert response.status_code == 413, response.status_code
    detail = response.json()["detail"]
    assert "8 MB" in detail
    assert "grid-sheet photograph" in detail
    assert analyser.calls == []


def test_the_two_routes_do_not_share_one_ceiling(transcriber: _Twin, analyser: _Twin):
    body = b"\x00" * (9 * MB)

    clip = _post("/media/transcribe", files={"file": ("answer.webm", body, "audio/webm")})
    photo = _post("/media/analyze-measurement", files={"file": ("sheet.jpg", body, "image/jpeg")})

    assert clip.status_code == 200, clip.text
    assert photo.status_code == 413, photo.status_code



def test_the_dictation_refusal_names_the_route_that_does_accept_a_long_recording(transcriber: _Twin):
    response = _post(
        "/media/transcribe", files={"file": ("long.webm", b"\x00" * (26 * MB), "audio/webm")}
    )
    detail = response.json()["detail"]
    assert detail.endswith(DICTATION_REMEDY)
    assert detail.startswith("That recording is over the 25 MB limit;")


def test_the_measurement_refusal_names_the_action_that_clears_it_from_a_phone(analyser: _Twin):
    response = _post(
        "/media/analyze-measurement",
        files={"file": ("bench.jpg", b"\x00" * (9 * MB), "image/jpeg")},
    )
    detail = response.json()["detail"]
    assert detail.endswith(CARD_REMEDY)
    assert detail.startswith("That grid-sheet photograph is over the 8 MB limit;")



def test_a_clip_inside_the_limit_reaches_the_transcriber_whole(transcriber: _Twin):
    payload = b"RIFF" + b"\x01" * (2 * MB)

    response = _post("/media/transcribe", files={"file": ("answer.webm", payload, "audio/webm")})

    assert response.status_code == 200, response.text
    assert response.json()["status"] == "COMPLETED"
    (args, kwargs), = transcriber.calls
    assert args[0] == payload
    assert args[1] == "answer.webm"
    assert args[2] == "audio/webm"
    assert kwargs["user_id"] == RESEARCHER.id


def test_a_photograph_inside_the_limit_reaches_the_analyser_whole_with_its_dimension(analyser: _Twin):
    payload = b"\xff\xd8" + b"\x02" * (1 * MB)

    response = _post(
        "/media/analyze-measurement",
        files={"file": ("sheet.jpg", payload, "image/jpeg")},
        params={"dimension": "height"},
    )

    assert response.status_code == 200, response.text
    (args, _kwargs), = analyser.calls
    assert args[0] == payload
    assert args[1] == "sheet.jpg"
    assert args[2] == "image/jpeg"
    assert args[4] == "height"


def test_the_unbounded_upload_shims_are_gone_from_the_services_module(transcriber: _Twin):
    from app.services import ai

    assert not hasattr(ai, "transcribe_audio")
    assert not hasattr(ai, "analyze_measurement_image")
    assert hasattr(ai, "transcribe_audio_bytes")
    assert hasattr(ai, "analyze_measurement_image_bytes")
