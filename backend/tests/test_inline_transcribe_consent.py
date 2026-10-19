"""Two media routes that send to an outside service, held to the one consent store.

* ``POST /media/transcribe`` carries a clip that names no recording, interview or artisan, so no
  consent can be asked about it. It is admin-only: a researcher the gate refuses on a stored
  recording cannot post the downloaded file here to get the words back.
* ``POST /media/{id}/refine-transcript`` sends a stored transcript to the language model. It asks
  ``consent_gate.verdict`` for that recording first, and a withdrawal stops the next refinement.

No database and no provider: the record read, the verdict and both provider calls are stand-ins.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.api.routes.media as media_routes
from app.api.router import api_router
from app.core import deps
from app.services import consent_gate

RESEARCHER = SimpleNamespace(id="user-1", email="r@example.test", name="Researcher", role="RESEARCHER")
ADMIN = SimpleNamespace(id="admin-1", email="a@example.test", name="Admin", role="ADMIN")


def _client_for(user: Any) -> FastAPI:
    app = FastAPI()
    app.include_router(api_router)
    app.dependency_overrides[deps.get_current_user] = lambda: user
    return app


def _post(user: Any, path: str, **kwargs: Any) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_client_for(user))
        async with httpx.AsyncClient(transport=transport, base_url="http://routes.test") as client:
            return await client.post(f"/api{path}", **kwargs)

    return asyncio.run(run())


@pytest.fixture
def sends(monkeypatch: pytest.MonkeyPatch) -> dict[str, list[Any]]:
    calls: dict[str, list[Any]] = {"transcribe": [], "refine": []}

    async def transcribe(content: bytes, *_a: Any, **_k: Any) -> dict[str, Any]:
        calls["transcribe"].append(content)
        return {"available": True, "status": "COMPLETED", "text": "hello"}

    async def refine(text: str, *_a: Any, **_k: Any) -> dict[str, Any]:
        calls["refine"].append(text)
        return {"available": True, "status": "COMPLETED", "text": "refined"}

    async def unpaused(*_a: Any, **_k: Any) -> bool:
        return False

    monkeypatch.setattr(media_routes, "transcribe_audio_bytes", transcribe)
    monkeypatch.setattr(media_routes, "refine_transcript_text", refine)
    monkeypatch.setattr(media_routes, "load_transcription_paused", unpaused)
    monkeypatch.setattr(media_routes.transcription, "is_paused", unpaused)
    return calls


def _clip() -> dict[str, Any]:
    return {"files": {"file": ("answer.webm", b"RIFF" + b"\x01" * 64, "audio/webm")}}


def test_a_researcher_cannot_post_a_clip_for_an_inline_transcript(sends: dict[str, list[Any]]):
    response = _post(RESEARCHER, "/media/transcribe", **_clip())
    assert response.status_code == 403, response.text
    assert sends["transcribe"] == [], "nothing may reach the transcription service"


def test_an_admin_still_can(sends: dict[str, list[Any]]):
    response = _post(ADMIN, "/media/transcribe", **_clip())
    assert response.status_code == 200, response.text
    assert len(sends["transcribe"]) == 1


@pytest.fixture
def recording(monkeypatch: pytest.MonkeyPatch) -> dict[str, str]:
    """One stored recording uploaded by the researcher, and its consent verdict (changeable)."""
    state = {"verdict": consent_gate.ALLOW}
    media = SimpleNamespace(id="m1", uploadedById=RESEARCHER.id, transcriptText="the artisan's words")

    async def require_record(_model: Any, media_id: str) -> Any:
        assert media_id == "m1"
        return media

    async def verdict(media_id: Any, *, client: Any = None) -> str:
        assert media_id == "m1"
        return state["verdict"]

    monkeypatch.setattr(media_routes, "require_record", require_record)
    monkeypatch.setattr(consent_gate, "verdict", verdict)
    return state


def test_refining_a_transcript_stops_once_consent_is_withdrawn(
    sends: dict[str, list[Any]], recording: dict[str, str]
):
    allowed = _post(RESEARCHER, "/media/m1/refine-transcript", json={"translate": False})
    assert allowed.status_code == 200, allowed.text
    assert sends["refine"] == ["the artisan's words"]

    recording["verdict"] = consent_gate.REFUSED
    withdrawn = _post(RESEARCHER, "/media/m1/refine-transcript", json={"translate": False})
    assert withdrawn.status_code == 409, withdrawn.text
    assert withdrawn.json()["detail"]["code"] == "CONSENT_REFUSED"
    assert sends["refine"] == ["the artisan's words"], "the withdrawal must stop the next send"


def test_refining_waits_while_no_consent_is_recorded(
    sends: dict[str, list[Any]], recording: dict[str, str]
):
    recording["verdict"] = consent_gate.AWAITING
    response = _post(RESEARCHER, "/media/m1/refine-transcript", json={"translate": False})
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "CONSENT_NOT_RECORDED"
    assert sends["refine"] == []
