"""The two inline media uploads, driven over the real multipart stack — A30-10, second half.

``tests/test_upload_bounds.py`` proves ``services/uploads.read_upload_bounded`` refuses. What it
cannot prove — and says so in its own docstring, which names these two routes as the ones still
unconverted at the time it was written — is that ``POST /media/transcribe`` and
``POST /media/analyze-measurement`` actually WIRE the helper up:

* that each passes ``request``, so the declared ``Content-Length`` is consulted at all;
* that each passes ITS OWN ceiling — 25 MB for a dictation clip, 8 MB for a grid-sheet photograph
  — and not the other route's, which a shared constant or a copy-paste would produce silently;
* that each passes the remedy sentence that belongs to it, since the whole reason ``remedy`` is a
  parameter is that moving these two routes onto a shared 413 would otherwise have deleted the two
  pieces of advice a researcher can actually act on;
* and that a body INSIDE the limit still arrives whole at the provider call, because a bound that
  also truncated would be a far worse defect than the one being fixed — a grid photograph half-read
  is a measurement quietly taken from the wrong picture, and a clip half-read is a transcript of
  half an answer, filed as though it were the answer.

WHAT THE DEFECT WAS. Both routes handed their ``UploadFile`` to a shim in ``app/services/ai.py``
whose first line was ``content = await file.read()``. ``read()`` with no argument reads to EOF, so
neither route had a ceiling at all: whatever arrived was materialised in one contiguous ``bytes``
in the heap and then sent to a provider. On this deployment — a single-worker uvicorn on 1 GiB
(MEASURED, docs/SCALABILITY.md §5.1), with no supervisor to respawn a worker that overcommits,
because ``--workers >1`` is what caused the outage ``app/worker.py``'s docstring describes — that
does not degrade one request, it takes every in-flight request on the box down with it, including
the sign-in of whoever is trying to find out why. The cheapest attempt was one signed-in account,
one request, one large file. Both shims are now deleted; block comments stand in their place at
``ai.py`` so nobody reintroduces the convenience.

THE BODIES ARE SENT FOR REAL, not stubbed, so starlette's own form parser runs and spools exactly
as it would in production. That is the only way to test the half of this that lives between the
socket and the handler.

THE PROVIDER CALL IS THE STUB. Nothing here should reach ElevenLabs or Gemini, and asserting that
the twin was NEVER CALLED on a refusal is how "it refused before doing the expensive thing" becomes
a fact about the calls made rather than an inference from a status code.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.api.routes.media as media_routes
from app.api.router import api_router
from app.core import deps

# The two sentences, imported FROM THE TEST THAT PINS THEIR SHAPE rather than restated here. Their
# home file argues at length that they must stay literals AT THE CALL SITES — one route each, so a
# third route cannot reach for a remedy naming a fallback it does not have — which leaves exactly
# one thing unchecked: whether the literal a route actually passes is still the sentence that was
# agreed. That is what these two imports check, and it is the only cross-file assertion here.
from tests.test_upload_bounds import CARD_REMEDY, DICTATION_REMEDY

MB = 1024 * 1024

RESEARCHER = SimpleNamespace(
    id="user-1",
    email="researcher@example.test",
    name="Researcher",
    role="RESEARCHER",
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
    """A stand-in for a ``*_bytes`` provider call that records every argument it was handed."""

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
    return twin


@pytest.fixture
def analyser(monkeypatch: pytest.MonkeyPatch) -> _Twin:
    twin = _Twin({"available": True, "status": "COMPLETED", "analysis": {"valueInches": 12}})
    monkeypatch.setattr(media_routes, "analyze_measurement_image_bytes", twin)
    return twin


# --------------------------------------------------------------------------------------
# 1. The refusals, at each route's own number
# --------------------------------------------------------------------------------------


def test_an_oversized_dictation_clip_is_refused_before_a_provider_is_paid(transcriber: _Twin):
    """THE DEFECT, AT THE ROUTE. 26 MB is over the 25 MB ceiling plus ``CONTENT_LENGTH_SLACK_BYTES``,
    so the declared length alone refuses it and the spooled body is never copied into the heap —
    and, just as importantly, no audio is sent to a transcription provider that bills by the
    minute. Before this change the body was read to EOF and forwarded."""
    oversized = b"\x00" * (26 * MB)

    response = _post("/media/transcribe", files={"file": ("long.webm", oversized, "audio/webm")})

    assert response.status_code == 413, response.status_code
    detail = response.json()["detail"]
    assert "25 MB" in detail
    assert "recording" in detail
    assert transcriber.calls == []


def test_an_oversized_grid_sheet_photograph_is_refused_at_eight_megabytes(analyser: _Twin):
    """The measurement route's ceiling is an order of magnitude below the dictation one on purpose:
    a grid-sheet photograph is one still from a phone camera, and anything past 8 MB is a video, a
    burst, or the whole workbench — none of which measures better than the sheet alone."""
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
    """THE SAME NINE MEGABYTES, TWO ANSWERS — which is the whole argument against a single body-size
    middleware, and the assertion a copy-pasted constant would fail. A 9 MB clip is an ordinary long
    field answer and must go through; a 9 MB photograph of a sheet of squared paper is not a
    measurement anybody needs and must not."""
    body = b"\x00" * (9 * MB)

    clip = _post("/media/transcribe", files={"file": ("answer.webm", body, "audio/webm")})
    photo = _post("/media/analyze-measurement", files={"file": ("sheet.jpg", body, "image/jpeg")})

    assert clip.status_code == 200, clip.text
    assert photo.status_code == 413, photo.status_code


# --------------------------------------------------------------------------------------
# 2. What the caller is told to do next
# --------------------------------------------------------------------------------------


def test_the_dictation_refusal_names_the_route_that_does_accept_a_long_recording(transcriber: _Twin):
    """"Send a smaller file" is not an instruction when what the researcher is holding is a
    forty-minute interview — there is no smaller version of it. Workshop audio is presigned to S3
    and transcribed by the queue in the background, and naming it is the reason this ceiling can be
    a low number instead of an arbitrary one."""
    response = _post(
        "/media/transcribe", files={"file": ("long.webm", b"\x00" * (26 * MB), "audio/webm")}
    )
    detail = response.json()["detail"]
    assert detail.endswith(DICTATION_REMEDY)
    assert detail.startswith("That recording is over the 25 MB limit;")


def test_the_measurement_refusal_names_the_action_that_clears_it_from_a_phone(analyser: _Twin):
    """Photographing the grid sheet alone is what a researcher standing in a courtyard can actually
    do; a smaller picture of the whole workbench measures no better than the large one did."""
    response = _post(
        "/media/analyze-measurement",
        files={"file": ("bench.jpg", b"\x00" * (9 * MB), "image/jpeg")},
    )
    detail = response.json()["detail"]
    assert detail.endswith(CARD_REMEDY)
    assert detail.startswith("That grid-sheet photograph is over the 8 MB limit;")


# --------------------------------------------------------------------------------------
# 3. The ordinary path, unchanged — the control
# --------------------------------------------------------------------------------------


def test_a_clip_inside_the_limit_reaches_the_transcriber_whole(transcriber: _Twin):
    """A bound that truncated would be worse than no bound: half a recording transcribed and filed
    as the answer is a fabricated field record, and nothing in the response would say so. The
    filename, the media type and the caller's id ride along exactly as the deleted shim passed
    them — ``user_id`` in particular, because a personal provider key belongs to the person at the
    microphone and a transcription that cannot say whose it is gets billed to the organisation."""
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
    """``dimension`` is a query parameter and decides whether the answer is one ``valueInches`` or
    the legacy length+breadth pair. It is asserted here because the conversion rewrote the call that
    forwards it, and dropping it would silently downgrade every single-dimension measurement in the
    app to the pair — a change no status code would report."""
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
    """THE REGRESSION GUARD, AND IT IS NOT PEDANTRY. ``transcribe_audio`` and
    ``analyze_measurement_image`` each took an ``UploadFile`` and read it to EOF. Leaving them in
    place, merely unused, is an invitation: the next route that needs a transcript reaches for the
    function that takes the thing it is already holding, and the bound is gone again with nothing
    to notice it. ``*_bytes`` is the only door, and a caller with an ``UploadFile`` has to decide a
    ceiling to get through it."""
    from app.services import ai

    assert not hasattr(ai, "transcribe_audio")
    assert not hasattr(ai, "analyze_measurement_image")
    assert hasattr(ai, "transcribe_audio_bytes")
    assert hasattr(ai, "analyze_measurement_image_bytes")
