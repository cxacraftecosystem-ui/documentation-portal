"""No API key leaves this machine in a URL, and no provider's own words reach a caller.

THE BUG THESE PIN. ``_post_gemini_measurement`` authenticated with ``params={"key": key}``, so the
key was part of the prepared URL — and ``requests`` builds every exception message out of the
prepared request. ``analyze_measurement_image_bytes`` then returned ``str(exc)`` verbatim as
``message``, which travelled to exactly two places:

  1. the JSON body of ``POST /api/media/analyze-measurement``, whose only gate is
     ``get_current_user`` — a crowdsource volunteer with a broken image got the Gemini key back;
  2. ``MediaFile.extraMetadata.measurementProcessing``, written by the queue and served with the
     media row from then on.

Both halves are tested here: the request no longer carries a credential in its URL, and no result
this module returns repeats a provider's exception text. The first test walks EVERY provider call in
the module rather than the one that was wrong, because the shape is what matters and the next
integration is written by someone who has not read this file.
"""

import asyncio
import json
from types import SimpleNamespace

import pytest
import requests

from app.services import ai, media_queue

# Stands in for a real key everywhere below. Distinctive enough that finding it in a URL, a message
# or a database column is unambiguous, and shaped like the Google keys that were actually at risk.
SECRET = "AIzaSyLEAKED-if-you-can-read-this"


class _Response:
    def __init__(self, status_code: int = 200, payload: dict | None = None, text: str = "") -> None:
        self.status_code = status_code
        self.headers: dict[str, str] = {}
        self.text = text
        self._payload = payload or {}

    def json(self) -> dict:
        return self._payload

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)


class _Recorder:
    def __init__(self, response) -> None:
        self._response = response
        self.calls: list[dict] = []

    def __call__(self, url: str, **kwargs):
        self.calls.append({"url": url, **kwargs})
        if isinstance(self._response, Exception):
            raise self._response
        return self._response


@pytest.fixture
def transport(monkeypatch: pytest.MonkeyPatch):
    """Replace the network with a recorder, and make every provider key the same sentinel."""

    def install(response) -> _Recorder:
        recorder = _Recorder(response)
        monkeypatch.setattr(ai.requests, "post", recorder)
        monkeypatch.setattr(ai.managed_secrets, "peek_secret", lambda name: SECRET)
        monkeypatch.setattr(ai.managed_secrets, "gemini_key_pool", lambda: [SECRET])

        async def _primed() -> None:
            return None

        monkeypatch.setattr(ai.managed_secrets, "refresh_if_stale", _primed)
        return recorder

    return install


def _settings(**overrides) -> SimpleNamespace:
    base = {
        "elevenlabs_stt_model": "scribe_v2",
        "deepgram_stt_model": "nova-3",
        "openai_transcription_model": "whisper-1",
        "openai_chat_model": "gpt-4o-mini",
        "gemini_measurement_model": "gemini-2.5-flash-lite",
    }
    base.update(overrides)
    return SimpleNamespace(**base)


# (name, callable taking the recorder-backed settings, the canned success body)
PROVIDER_CALLS = (
    (
        "whisper",
        lambda s: ai._post_openai_transcription(b"audio", "a.wav", "audio/wav", s),
        {"text": "hello"},
    ),
    (
        "elevenlabs",
        lambda s: ai._post_elevenlabs_transcription(b"audio", "a.wav", "audio/wav", s),
        {"text": "hello"},
    ),
    (
        "deepgram",
        lambda s: ai._post_deepgram_transcription(b"audio", "a.wav", "audio/wav", s),
        {"results": {"channels": [{"alternatives": [{"transcript": "hello"}]}]}},
    ),
    (
        "openai-chat",
        lambda s: ai._post_openai_chat([{"role": "user", "content": "hi"}], s),
        {"choices": [{"message": {"content": "hello"}}]},
    ),
    (
        "gemini",
        lambda s: ai._post_gemini_measurement(b"image", "image/jpeg", s),
        {"candidates": [{"content": {"parts": [{"text": '{"lengthInches": 4}'}]}}]},
    ),
)


@pytest.mark.parametrize("name,call,payload", PROVIDER_CALLS)
def test_no_provider_call_carries_its_key_in_the_url(transport, name: str, call, payload: dict) -> None:
    """A key in the query string is a key in every exception message, proxy log and access log the
    request passes through. Every provider here supports a header; every provider here uses one."""
    recorder = transport(_Response(payload=payload))

    call(_settings())

    sent = recorder.calls[0]
    assert SECRET not in sent["url"], name
    assert SECRET not in json.dumps(sent.get("params") or {}), name
    # ...and it did travel, in the one place a credential belongs.
    assert SECRET in json.dumps(sent.get("headers") or {}), name


def test_gemini_authenticates_with_the_header_google_documents(transport) -> None:
    recorder = transport(
        _Response(payload={"candidates": [{"content": {"parts": [{"text": "{}"}]}}]})
    )

    ai._post_gemini_measurement(b"image", "image/jpeg", _settings())

    sent = recorder.calls[0]
    assert sent["headers"] == {"x-goog-api-key": SECRET}
    assert "params" not in sent
    assert sent["url"].endswith("/models/gemini-2.5-flash-lite:generateContent")


# --------------------------------------------------------------------------------------------
# The second half: what a caller is told when a provider fails.
# --------------------------------------------------------------------------------------------

# What `requests` actually raises when a connection to a query-authenticated endpoint dies: the
# message is built from the prepared URL, credential included.
LEAKY_TRANSPORT_ERROR = requests.ConnectionError(
    "HTTPSConnectionPool(host='generativelanguage.googleapis.com', port=443): Max retries exceeded "
    f"with url: /v1beta/models/gemini-2.5-flash-lite:generateContent?key={SECRET} "
    "(Caused by NewConnectionError('Connection refused'))"
)


def test_a_failed_measurement_tells_the_researcher_what_to_do_and_nothing_else(transport) -> None:
    transport(LEAKY_TRANSPORT_ERROR)

    result = asyncio.run(ai.analyze_measurement_image_bytes(b"image", "m.jpg", "image/jpeg", _settings()))

    assert result["status"] == "FAILED"
    message = result["message"]
    assert SECRET not in message
    assert "key=" not in message
    assert "generativelanguage.googleapis.com" not in message
    # Actionable rather than merely redacted: the researcher can finish the record by hand.
    assert "manually" in message


def test_the_measurement_column_the_queue_writes_holds_no_credential(transport) -> None:
    """The result is stored as ``extraMetadata.measurementProcessing`` and served with the media row
    forever after, so the leak outlives the request that caused it."""
    transport(LEAKY_TRANSPORT_ERROR)

    result = asyncio.run(ai.analyze_measurement_image_bytes(b"image", "m.jpg", "image/jpeg", _settings()))
    metadata = media_queue._merge_measurement_metadata({"existing": "kept"}, result)

    assert metadata["existing"] == "kept"
    assert SECRET not in json.dumps(metadata)


def test_a_failed_transcription_names_the_fault_not_the_providers_words(transport, monkeypatch) -> None:
    transport(LEAKY_TRANSPORT_ERROR)

    async def _order() -> list[str]:
        return ["elevenlabs", "deepgram", "whisper"]

    monkeypatch.setattr(ai.app_settings, "load_stt_provider_order", _order)
    result = asyncio.run(ai.transcribe_audio_bytes(b"audio", "a.wav", "audio/wav", _settings()))

    assert result["status"] == "FAILED"
    message = result["message"]
    assert SECRET not in message
    # Each provider is still accounted for by name and by how it failed — that is what an admin
    # reading transcriptError needs, and it is all they get.
    assert "elevenlabs: unreachable (ConnectionError)" in message
    assert "deepgram" in message and "whisper" in message


def test_a_refused_gemini_key_reports_the_status_without_the_body(transport) -> None:
    """A rotate-worthy status used to be re-raised with ``response.text[:200]`` glued on, so the
    provider's raw body reached the caller and the column alongside it."""
    transport(_Response(429, text=f'{{"error":{{"message":"quota for key {SECRET} exhausted"}}}}'))

    result = asyncio.run(ai.analyze_measurement_image_bytes(b"image", "m.jpg", "image/jpeg", _settings()))

    assert result["status"] == "FAILED"
    assert SECRET not in result["message"]
    assert "HTTP 429" in result["message"]


def test_a_failed_refinement_leaves_the_transcript_and_says_so(transport, monkeypatch) -> None:
    transport(LEAKY_TRANSPORT_ERROR)

    async def _configured(name: str) -> str:
        return SECRET

    monkeypatch.setattr(ai.managed_secrets, "get_secret", _configured)
    result = asyncio.run(ai.refine_transcript_text("raw text", False, _settings()))

    assert result["status"] == "FAILED"
    assert SECRET not in result["message"]
    assert "unchanged" in result["message"]


# --------------------------------------------------------------------------------------------
# The backstop, for the failure paths this module does not own.
# --------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw",
    [
        "GET https://x.test/v1?key=abc123&model=z failed",
        "https://x.test/v1?api_key=abc123",
        "https://x.test/v1?access_token=abc123",
        "https://x.test/v1?X-Amz-Signature=abc123",
    ],
)
def test_redact_secrets_blanks_a_credential_in_a_query_string(raw: str) -> None:
    redacted = ai.redact_secrets(raw)

    assert "abc123" not in redacted
    assert "REDACTED" in redacted


def test_redact_secrets_leaves_ordinary_text_alone() -> None:
    assert ai.redact_secrets("deepgram: unreachable (ConnectionError)") == (
        "deepgram: unreachable (ConnectionError)"
    )
    assert ai.redact_secrets("model=nova-3&language=multi") == "model=nova-3&language=multi"


def test_a_job_failure_is_redacted_before_it_reaches_the_error_column(monkeypatch) -> None:
    """``_handle_job_failure`` stores whatever the job raised. Everything ``services/ai`` returns is
    already clean; this is the guard for the libraries in between (S3, the driver, a future SDK)."""
    written: dict[str, object] = {}

    async def find_unique(where: dict, **_: object):
        return SimpleNamespace(id="job1", attempts=1, maxAttempts=3)

    async def update(where: dict, data: dict):
        written.update(data)

    monkeypatch.setattr(
        media_queue,
        "db",
        SimpleNamespace(mediaprocessingjob=SimpleNamespace(find_unique=find_unique, update=update)),
    )

    asyncio.run(media_queue._handle_job_failure("job1", RuntimeError(str(LEAKY_TRANSPORT_ERROR))))

    assert SECRET not in written["error"]
    assert "REDACTED" in written["error"]
