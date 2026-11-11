import asyncio
import json
from types import SimpleNamespace

import pytest
import requests

from app.services import ai, media_queue

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
        "elevenlabs_stt_model": "scribe_v3",
        "deepgram_stt_model": "nova-3",
        "openai_transcription_model": "whisper-1",
        "openai_chat_model": "gpt-6-luna",
        "gemini_measurement_model": "gemini-2.5-flash-lite",
    }
    base.update(overrides)
    return SimpleNamespace(**base)


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
    recorder = transport(_Response(payload=payload))

    call(_settings())

    sent = recorder.calls[0]
    assert SECRET not in sent["url"], name
    assert SECRET not in json.dumps(sent.get("params") or {}), name
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
    assert "manually" in message


def test_the_measurement_column_the_queue_writes_holds_no_credential(transport) -> None:
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
    assert "ElevenLabs couldn't be reached" in message
    assert "Deepgram" in message and "Whisper" in message


def test_a_refused_gemini_key_reports_the_status_without_the_body(transport) -> None:
    transport(_Response(429, text=f'{{"error":{{"message":"quota for key {SECRET} exhausted"}}}}'))

    result = asyncio.run(ai.analyze_measurement_image_bytes(b"image", "m.jpg", "image/jpeg", _settings()))

    assert result["status"] == "FAILED"
    assert SECRET not in result["message"]
    assert "HTTP" not in result["message"]
    assert "manually" in result["message"]


def test_a_failed_refinement_leaves_the_transcript_and_says_so(transport, monkeypatch) -> None:
    transport(LEAKY_TRANSPORT_ERROR)

    async def _configured(name: str) -> str:
        return SECRET

    monkeypatch.setattr(ai.managed_secrets, "get_secret", _configured)
    result = asyncio.run(ai.refine_transcript_text("raw text", False, _settings()))

    assert result["status"] == "FAILED"
    assert SECRET not in result["message"]
    assert "unchanged" in result["message"]



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

    asyncio.run(media_queue._handle_job_failure("job1", media_queue.JobFailure(str(LEAKY_TRANSPORT_ERROR))))

    assert SECRET not in written["error"]
    assert "REDACTED" in written["error"]


def test_an_unanticipated_job_failure_stores_a_plain_sentence_and_logs_the_rest(monkeypatch, caplog) -> None:
    written: dict[str, object] = {}

    async def find_unique(where: dict, **_: object):
        return SimpleNamespace(id="job1", attempts=3, maxAttempts=3)

    async def update(where: dict, data: dict):
        written.update(data)

    monkeypatch.setattr(
        media_queue,
        "db",
        SimpleNamespace(mediaprocessingjob=SimpleNamespace(find_unique=find_unique, update=update)),
    )
    with caplog.at_level("ERROR"):
        asyncio.run(media_queue._handle_job_failure("job1", KeyError(str(LEAKY_TRANSPORT_ERROR))))

    assert written["error"] == media_queue.UNEXPECTED_JOB_FAILURE
    assert written["status"] == "FAILED"
    assert "KeyError" in caplog.text and SECRET not in caplog.text
