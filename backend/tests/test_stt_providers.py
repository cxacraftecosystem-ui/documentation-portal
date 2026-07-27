"""What each speech-to-text provider is actually asked for, and what it is understood to answer.

Nothing here touches the network or spends a provider credit: ``requests.post`` is replaced with a
recorder that hands back canned payloads, so every assertion is about the request this repository
builds and the transcript it derives — the two halves nobody can see from a passing job.

They are worth pinning because the audio is specific. Field interviews with artisans, code-switched
Hindi and English, recorded next to the work, and often several artisans at once. Diarization,
multilingual decoding and craft-term boosting are the options that make a transcript of THAT
useful, and each of them is one query parameter away from silently not happening.
"""

import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest
import requests

from app.services import ai, transcript_format


class _Response:
    """The three things the provider code asks of a response: status, body, raise_for_status."""

    def __init__(self, status_code: int = 200, payload: dict | None = None, headers: dict | None = None,
                 text: str = "") -> None:
        self.status_code = status_code
        self.headers = headers or {}
        self.text = text
        self._payload = payload or {}

    def json(self) -> dict:
        return self._payload

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)


class _Recorder:
    """Stands in for ``requests.post``; returns each queued response in turn, keeping every call."""

    def __init__(self, responses: list) -> None:
        self._responses = list(responses)
        self.calls: list[dict] = []

    def __call__(self, url: str, **kwargs):
        self.calls.append({"url": url, **kwargs})
        nxt = self._responses.pop(0) if len(self._responses) > 1 else self._responses[0]
        if isinstance(nxt, Exception):
            raise nxt
        return nxt


@pytest.fixture
def post(monkeypatch: pytest.MonkeyPatch):
    """Install a response queue in place of the real transport, and a key for every provider."""

    def install(*responses) -> _Recorder:
        recorder = _Recorder(list(responses))
        monkeypatch.setattr(ai.requests, "post", recorder)
        monkeypatch.setattr(ai.managed_secrets, "peek_secret", lambda name: "test-key")
        return recorder

    return install


def _settings(**overrides) -> SimpleNamespace:
    base = {
        "elevenlabs_stt_model": "scribe_v1",  # the stale default in config.py
        "deepgram_stt_model": "nova-3",
        "openai_transcription_model": "whisper-1",
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def _deepgram_payload(alternative: dict) -> dict:
    return {"results": {"channels": [{"alternatives": [alternative]}]}}


# --------------------------------------------------------------------------------------------
# The craft vocabulary: the list itself, and the ceilings each provider puts on it.
# --------------------------------------------------------------------------------------------


def test_the_craft_vocabulary_loads_from_its_data_file() -> None:
    terms = ai.craft_keyterms()
    assert "dabu" in terms and "bagru" in terms and "ringal" in terms
    assert len({term.casefold() for term in terms}) == len(terms)


def test_every_term_fits_what_a_provider_will_accept() -> None:
    for term in ai.craft_keyterms():
        assert len(term) <= 50, term
        assert len(term.split()) <= 5, term
    assert len(ai.craft_keyterms()) <= ai._ELEVENLABS_KEYTERM_LIMIT


def test_deepgram_gets_no_more_terms_than_its_ceilings_allow() -> None:
    chosen = ai._deepgram_keyterms()
    assert 0 < len(chosen) <= ai._DEEPGRAM_KEYTERM_LIMIT
    assert sum(max(1, -(-len(t) // 3)) for t in chosen) <= ai._DEEPGRAM_KEYTERM_TOKEN_BUDGET
    assert chosen == list(ai.craft_keyterms()[: len(chosen)])  # file order, truncated from the tail


def test_a_missing_vocabulary_file_costs_the_boost_and_nothing_else(monkeypatch, post) -> None:
    monkeypatch.setattr(ai, "_CRAFT_VOCABULARY_PATH", Path("no", "such", "vocabulary.txt"))
    ai.craft_keyterms.cache_clear()
    try:
        assert ai.craft_keyterms() == ()
        recorder = post(_Response(payload=_deepgram_payload({"transcript": "hello"})))
        result = ai._post_deepgram_transcription(b"audio", "a.wav", "audio/wav", _settings())
        assert result["status"] == "COMPLETED"
        assert "keyterm" not in recorder.calls[0]["params"]
    finally:
        ai.craft_keyterms.cache_clear()


# --------------------------------------------------------------------------------------------
# Deepgram: the request.
# --------------------------------------------------------------------------------------------


def test_deepgram_asks_nova_3_for_multilingual_diarized_boosted_audio(post) -> None:
    recorder = post(_Response(payload=_deepgram_payload({"transcript": "hello"})))

    ai._post_deepgram_transcription(b"audio-bytes", "clip.m4a", "audio/mp4", _settings())

    sent = recorder.calls[0]
    assert sent["url"] == "https://api.deepgram.com/v1/listen"
    params = sent["params"]
    assert params["model"] == "nova-3"
    assert params["language"] == "multi"  # Hindi/English code-switching, not one or the other
    assert params["smart_format"] == "true"
    assert params["paragraphs"] == "true"
    assert params["diarize_model"] == "latest"
    assert "dabu" in params["keyterm"] and "ringal" in params["keyterm"]
    assert sent["headers"]["Authorization"] == "Token test-key"
    assert sent["headers"]["Content-Type"] == "audio/mp4"
    assert sent["data"] == b"audio-bytes"


def test_deepgram_never_sends_both_diarize_forms(post) -> None:
    """Deepgram rejects a request carrying the boolean and the model selector together."""
    for conservative in (False, True):
        params = ai._deepgram_params(_settings(), conservative=conservative)
        assert not ("diarize" in params and "diarize_model" in params)
    assert ai._deepgram_params(_settings(), conservative=True)["diarize"] == "true"


def test_a_deepgram_model_override_is_honoured(post) -> None:
    recorder = post(_Response(payload=_deepgram_payload({"transcript": "hi"})))
    ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings(deepgram_stt_model="nova-2"))
    assert recorder.calls[0]["params"]["model"] == "nova-2"


# --------------------------------------------------------------------------------------------
# Deepgram: the response.
# --------------------------------------------------------------------------------------------


def test_deepgram_paragraphs_become_labelled_speaker_turns(post) -> None:
    post(
        _Response(
            payload=_deepgram_payload(
                {
                    "transcript": "aap kitne saal se dabu kar rahe hain? bees saal se.",
                    "paragraphs": {
                        "paragraphs": [
                            {"speaker": 0, "sentences": [{"text": "Aap kitne saal se dabu kar rahe hain?"}]},
                            {"speaker": 1, "sentences": [{"text": "Bees saal se."}, {"text": "Pita ji se seekha."}]},
                            {"speaker": 0, "sentences": [{"text": "Aur ringal?"}]},
                        ]
                    },
                }
            )
        )
    )

    result = ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())

    assert result["speakers"] == 2
    assert result["text"].splitlines()[0] == "**Speaker 1:** Aap kitne saal se dabu kar rahe hain?"
    assert "**Speaker 2:** Bees saal se. Pita ji se seekha." in result["text"]
    assert result["text"].count("**Speaker 1:**") == 2  # the interviewer comes back


def test_deepgram_words_carry_the_speakers_when_paragraphs_do_not(post) -> None:
    post(
        _Response(
            payload=_deepgram_payload(
                {
                    "transcript": "haan ji bilkul",
                    "words": [
                        {"punctuated_word": "Haan", "speaker": 0},
                        {"punctuated_word": "ji,", "speaker": 0},
                        {"punctuated_word": "bilkul.", "speaker": 1},
                    ],
                }
            )
        )
    )

    result = ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())

    assert result["text"] == "**Speaker 1:** Haan ji,\n\n**Speaker 2:** bilkul."
    assert result["speakers"] == 2


def test_one_voice_is_not_decorated_with_a_label_that_says_nothing(post) -> None:
    post(
        _Response(
            payload=_deepgram_payload(
                {
                    "transcript": "Main Bagru mein kaam karta hoon.",
                    "paragraphs": {
                        "paragraphs": [
                            {"speaker": 0, "sentences": [{"text": "Main Bagru mein kaam karta hoon."}]}
                        ]
                    },
                }
            )
        )
    )

    result = ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())

    assert result["text"] == "Main Bagru mein kaam karta hoon."
    assert result["speakers"] == 1


def test_a_response_without_any_diarization_still_transcribes(post) -> None:
    post(_Response(payload=_deepgram_payload({"transcript": "kalamkari block printing"})))
    result = ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())
    assert result["status"] == "COMPLETED"
    assert result["text"] == "kalamkari block printing"
    assert result["speakers"] == 0
    assert result["formattedTranscript"] == "Transcript\n\nkalamkari block printing"


def test_an_empty_deepgram_response_is_empty_not_broken(post) -> None:
    post(_Response(payload={"results": {"channels": []}}))
    result = ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())
    assert result["status"] == "EMPTY"
    assert result["text"] == ""


# --------------------------------------------------------------------------------------------
# ElevenLabs.
# --------------------------------------------------------------------------------------------


def test_elevenlabs_asks_scribe_v2_to_diarize_and_bias_towards_the_craft(post) -> None:
    recorder = post(_Response(payload={"text": "hello", "language_code": "hin"}))

    result = ai._post_elevenlabs_transcription(b"audio", "clip.webm", "audio/webm", _settings())

    sent = recorder.calls[0]
    assert sent["url"] == "https://api.elevenlabs.io/v1/speech-to-text"
    assert sent["headers"]["xi-api-key"] == "test-key"
    fields = dict(field for field in sent["data"] if field[0] != "keyterms")
    assert fields["model_id"] == "scribe_v2"  # the config.py default still says scribe_v1
    assert fields["diarize"] == "true"
    assert fields["tag_audio_events"] == "false"  # workshop noise is not dialogue
    assert fields["timestamps_granularity"] == "word"
    assert "language_code" not in fields  # auto-detect: these interviews code-switch
    keyterms = [value for name, value in sent["data"] if name == "keyterms"]
    assert "dabu" in keyterms and "ajrakh" in keyterms
    assert sent["files"]["file"] == ("clip.webm", b"audio", "audio/webm")
    assert result["languageCode"] == "hin"
    assert result["model"] == "scribe_v2"


def test_an_explicit_elevenlabs_model_wins_over_the_upgrade(post) -> None:
    recorder = post(_Response(payload={"text": "hi"}))
    ai._post_elevenlabs_transcription(b"a", "a.webm", "audio/webm", _settings(elevenlabs_stt_model="scribe_v3"))
    assert dict(f for f in recorder.calls[0]["data"] if f[0] != "keyterms")["model_id"] == "scribe_v3"


def test_elevenlabs_speaker_ids_become_the_same_labels_deepgram_produces(post) -> None:
    post(
        _Response(
            payload={
                "language_code": "hin",
                "text": "Dabu kaise banate hain? Kalli mitti se. Haan.",
                "words": [
                    {"text": "Dabu", "type": "word", "speaker_id": "speaker_0"},
                    {"text": " ", "type": "spacing", "speaker_id": "speaker_0"},
                    {"text": "kaise", "type": "word", "speaker_id": "speaker_0"},
                    {"text": "banate", "type": "word", "speaker_id": "speaker_0"},
                    {"text": "hain?", "type": "word", "speaker_id": "speaker_0"},
                    {"text": "Kalli", "type": "word", "speaker_id": "speaker_2"},
                    {"text": "mitti", "type": "word", "speaker_id": "speaker_2"},
                    {"text": "se.", "type": "word", "speaker_id": "speaker_2"},
                    {"text": "(hammering)", "type": "audio_event", "speaker_id": "speaker_2"},
                    {"text": "Haan.", "type": "word", "speaker_id": "speaker_1"},
                ],
            }
        )
    )

    result = ai._post_elevenlabs_transcription(b"a", "a.webm", "audio/webm", _settings())

    assert result["text"] == (
        "**Speaker 1:** Dabu kaise banate hain?\n\n"
        "**Speaker 2:** Kalli mitti se.\n\n"
        "**Speaker 3:** Haan."
    )
    assert result["speakers"] == 3  # renumbered in order of first appearance, not by provider id
    assert "(hammering)" not in result["text"]
    assert result["raw"] is None  # a word-level payload is far too big to persist per clip


def test_a_solo_elevenlabs_recording_keeps_its_plain_transcript(post) -> None:
    post(
        _Response(
            payload={
                "text": "Bees saal se kaam kar raha hoon.",
                "words": [{"text": "Bees", "type": "word", "speaker_id": "speaker_0"}],
            }
        )
    )
    result = ai._post_elevenlabs_transcription(b"a", "a.webm", "audio/webm", _settings())
    assert result["text"] == "Bees saal se kaam kar raha hoon."
    assert result["speakers"] == 1


# --------------------------------------------------------------------------------------------
# A refused OPTION must not cost the provider its turn in the chain.
# --------------------------------------------------------------------------------------------


def test_deepgram_retries_once_without_the_options_it_was_refused(post) -> None:
    recorder = post(
        _Response(400, text="unknown query parameter"),
        _Response(payload=_deepgram_payload({"transcript": "ringal ki tokri"})),
    )

    result = ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())

    assert result["status"] == "COMPLETED"
    assert result["text"] == "ringal ki tokri"
    first, second = recorder.calls[0]["params"], recorder.calls[1]["params"]
    assert "keyterm" in first and "diarize_model" in first
    assert "keyterm" not in second and "diarize_model" not in second
    assert second["diarize"] == "true"  # diarization survives the retreat, boosting does not
    assert second["language"] == "multi"
    assert len(recorder.calls) == 2  # exactly once, never a loop


def test_elevenlabs_falls_back_to_the_model_that_has_always_existed(post) -> None:
    recorder = post(
        _Response(422, text="model_id not available"),
        _Response(payload={"text": "bagru printing"}),
    )

    result = ai._post_elevenlabs_transcription(b"a", "a.webm", "audio/webm", _settings())

    assert result["status"] == "COMPLETED"
    assert result["model"] == "scribe_v1"
    second = dict(field for field in recorder.calls[1]["data"] if field[0] != "keyterms")
    assert second["model_id"] == "scribe_v1"
    assert second["diarize"] == "true"
    assert not [f for f in recorder.calls[1]["data"] if f[0] == "keyterms"]
    assert len(recorder.calls) == 2


def test_a_second_refusal_is_a_real_failure(post) -> None:
    post(_Response(400, text="nope"), _Response(400, text="still nope"))
    with pytest.raises(requests.HTTPError):
        ai._post_deepgram_transcription(b"a", "a.wav", "audio/wav", _settings())


# --------------------------------------------------------------------------------------------
# The label contract with the Excel exporter, which is the only reason to emit labels at all.
# --------------------------------------------------------------------------------------------


def test_the_speaker_label_is_the_shape_the_exporter_recognises() -> None:
    turns = [(0, "Aap kaunsa craft karte hain?"), (1, "Dabu block printing.")]
    markdown = ai._diarized_markdown(turns)

    assert transcript_format._SPEAKER_RE.match("**Speaker 1:**")
    cell = transcript_format.transcript_cell(markdown)
    bold = [str(run.text) for run in cell if hasattr(run, "font") and run.font.b]
    assert "Speaker 1:" in bold and "Speaker 2:" in bold
    assert "**" not in "".join(str(getattr(run, "text", run)) for run in cell)


# --------------------------------------------------------------------------------------------
# The chain: one provider's failure is the next provider's job.
# --------------------------------------------------------------------------------------------


def _transcribe(monkeypatch, order: list[str]) -> dict:
    async def _no_refresh() -> None:
        return None

    async def _order() -> list[str]:
        return order

    monkeypatch.setattr(ai.managed_secrets, "refresh_if_stale", _no_refresh)
    monkeypatch.setattr(ai.app_settings, "load_stt_provider_order", _order)
    return asyncio.run(ai.transcribe_audio_bytes(b"audio", "a.wav", "audio/wav", _settings()))


def test_a_rejected_key_hands_the_job_to_the_next_provider(monkeypatch, post) -> None:
    post(
        _Response(401, text="invalid api key"),  # ElevenLabs
        _Response(payload=_deepgram_payload({"transcript": "sanganeri chhapai"})),  # Deepgram
    )

    result = _transcribe(monkeypatch, ["elevenlabs", "deepgram", "whisper"])

    assert result["status"] == "COMPLETED"
    assert result["provider"] == "deepgram"
    assert result["text"] == "sanganeri chhapai"


def test_the_admin_ranking_decides_who_is_asked_first(monkeypatch, post) -> None:
    recorder = post(_Response(payload=_deepgram_payload({"transcript": "ok"})))

    result = _transcribe(monkeypatch, ["deepgram", "elevenlabs", "whisper"])

    assert result["provider"] == "deepgram"
    assert recorder.calls[0]["url"].startswith("https://api.deepgram.com")


def test_a_bad_key_everywhere_names_the_key_rather_than_the_status(monkeypatch, post) -> None:
    post(_Response(403, text="forbidden"))

    result = _transcribe(monkeypatch, ["elevenlabs", "deepgram", "whisper"])

    assert result["status"] == "FAILED"
    assert "ELEVENLABS_API_KEY" in result["message"]
    assert "DEEPGRAM_API_KEY" in result["message"]
    assert "OPENAI_API_KEY" in result["message"]


def test_throttling_everywhere_defers_instead_of_failing(monkeypatch, post) -> None:
    post(_Response(429, headers={"Retry-After": "45"}, text="slow down"))

    result = _transcribe(monkeypatch, ["elevenlabs", "deepgram", "whisper"])

    # RATE_LIMITED is what media_queue requeues without consuming one of the job's attempts.
    assert result["status"] == "RATE_LIMITED"
    assert result["retryAfter"] == 45.0
    assert "rate-limited" in result["message"]


def test_an_unavailable_provider_defers_but_a_broken_one_fails(monkeypatch, post) -> None:
    post(_Response(503, text="overloaded"))
    assert _transcribe(monkeypatch, ["elevenlabs", "deepgram", "whisper"])["status"] == "RATE_LIMITED"

    post(_Response(500, text="internal error"))
    failed = _transcribe(monkeypatch, ["elevenlabs", "deepgram", "whisper"])
    # A 500 will break the same way on the same bytes, so the job must be allowed to terminate.
    assert failed["status"] == "FAILED"


def test_throttling_mixed_with_a_hard_failure_still_terminates(monkeypatch, post) -> None:
    post(
        _Response(429, text="slow down"),  # ElevenLabs
        _Response(401, text="invalid key"),  # Deepgram
        _Response(429, text="slow down"),  # Whisper
    )

    result = _transcribe(monkeypatch, ["elevenlabs", "deepgram", "whisper"])

    assert result["status"] == "FAILED"
    assert "DEEPGRAM_API_KEY" in result["message"]
