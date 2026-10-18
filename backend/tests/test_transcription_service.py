"""The shared transcription package, tested the same way in both portals.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (it is listed in
``shared/transcription.sha256``). Nothing here touches the network: ``requests.post`` is replaced
with a recorder that hands back recorded provider answers, and ffmpeg is replaced with a fake
cutter, so no test spends a provider credit or needs ffmpeg installed.
"""

from __future__ import annotations

import json
import os
import tempfile
from datetime import UTC, datetime, timedelta

import pytest
import requests

from app.transcription import audio as audio_mod, chain, dedup, merge, schedule, service
from app.transcription.audio import PartPlan
from app.transcription.contract import (
    ApiKey,
    AudioInput,
    KeyRejected,
    OptionRejected,
    ProviderFailed,
    Segment,
    SttOptions,
    SttResult,
    Throttled,
    TooLarge,
)
from app.transcription.providers import ADAPTERS, muse


class _Response:
    def __init__(
        self,
        status_code: int = 200,
        payload: dict | None = None,
        headers: dict | None = None,
        text: str = "",
    ):
        self.status_code = status_code
        self.headers = headers or {}
        self.text = text
        self._payload = payload or {}

    def json(self) -> dict:
        return self._payload


class _Recorder:
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
    def install(*responses) -> _Recorder:
        recorder = _Recorder(list(responses))
        monkeypatch.setattr(requests, "post", recorder)
        return recorder

    return install


KEY = ApiKey("TEST_KEY", "secret-value")
CLIP = AudioInput(b"audio", None, "clip.wav", "audio/wav", 30_000)
WAV_PART = AudioInput(b"RIFF....", None, "chunk_001_of_001.wav", "audio/wav", 30_000)

# Recorded answers, trimmed to the fields the adapters read.
MUSE_FIXTURE = {
    "transcript": "Aap kitne saal se dabu kar rahe hain? Bees saal se.",
    "audioDurationMs": 30_000,
    "turns": [
        {
            "turnId": 1,
            "startMs": 0,
            "endMs": 4_000,
            "transcript": "Aap kitne saal se dabu kar rahe hain?",
            "speaker": "A",
        },
        {
            "turnId": 2,
            "startMs": 4_200,
            "endMs": 6_000,
            "transcript": "Bees saal se.",
            "speaker": "B",
        },
    ],
}


def _deepgram(alternative: dict) -> dict:
    return {"results": {"channels": [{"alternatives": [alternative]}]}}


# --- Muse: the request and the answer --------------------------------------------------------


def test_muse_sends_the_documented_request(post) -> None:
    recorder = post(_Response(payload=MUSE_FIXTURE))
    opts = SttOptions(
        language_bias=["Hindi", "Odia"], keyterms=["dabu", "ajrakh"], session_id="media1-0"
    )

    result = ADAPTERS["muse"].transcribe(WAV_PART, opts, KEY)

    sent = recorder.calls[0]
    assert sent["url"] == "https://api.meta.ai/v1/asr/transcribe"
    assert sent["params"] == {"sessionId": "media1-0"}
    assert sent["headers"]["Authorization"] == "Bearer secret-value"
    body = json.loads(sent["files"]["request"][1])
    assert body == {
        "model": "muse-voice-transcribe-1.0",
        "audioEncoding": "WAV",
        "mode": "DIARIZATION",
        "languageBias": ["Hindi", "Odia"],
        "keywords": ["dabu", "ajrakh"],
    }
    assert sent["files"]["audio"][2] == "audio/wav"
    assert result.status == "COMPLETED"
    assert result.text.startswith("**Speaker 1:** Aap kitne saal se dabu")
    assert "**Speaker 2:** Bees saal se." in result.text
    assert [s.as_json() for s in result.segments][1] == {
        "startMs": 4200,
        "endMs": 6000,
        "speaker": "B",
        "text": "Bees saal se.",
    }
    assert result.audio_ms == 30_000


def test_muse_one_voice_keeps_the_plain_transcript(post) -> None:
    post(
        _Response(
            payload={
                "transcript": "Main Bagru mein kaam karta hoon.",
                "turns": [
                    {
                        "startMs": 0,
                        "endMs": 2000,
                        "transcript": "Main Bagru mein kaam karta hoon.",
                        "speaker": "A",
                    }
                ],
            }
        )
    )
    result = ADAPTERS["muse"].transcribe(WAV_PART, SttOptions(), KEY)
    assert result.text == "Main Bagru mein kaam karta hoon."
    assert result.speakers == 1


@pytest.mark.parametrize(
    ("status", "error"),
    [
        (400, OptionRejected),
        (413, TooLarge),
        (429, Throttled),
        (401, KeyRejected),
        (502, ProviderFailed),
    ],
)
def test_muse_errors_map_to_typed_failures(post, status, error) -> None:
    post(_Response(status, headers={"Retry-After": "12"}))
    with pytest.raises(error) as caught:
        ADAPTERS["muse"].transcribe(WAV_PART, SttOptions(), KEY)
    assert isinstance(caught.value, requests.HTTPError)  # older callers still catch these
    if status == 429:
        assert caught.value.retry_after == 12.0


def test_muse_retries_a_500_once_then_fails(post) -> None:
    recorder = post(_Response(500), _Response(500))
    with pytest.raises(ProviderFailed):
        ADAPTERS["muse"].transcribe(WAV_PART, SttOptions(), KEY)
    assert len(recorder.calls) == 2

    recorder = post(_Response(500), _Response(payload=MUSE_FIXTURE))
    assert ADAPTERS["muse"].transcribe(WAV_PART, SttOptions(), KEY).status == "COMPLETED"


def test_muse_never_uploads_anything_but_normalised_wav(post) -> None:
    recorder = post(_Response(payload=MUSE_FIXTURE))
    with pytest.raises(TooLarge):
        ADAPTERS["muse"].transcribe(
            AudioInput(b"x", None, "a.m4a", "audio/mp4", 1000), SttOptions(), KEY
        )
    with pytest.raises(TooLarge):
        ADAPTERS["muse"].transcribe(
            AudioInput(b"x", None, "a.wav", "audio/wav", 11 * 60_000), SttOptions(), KEY
        )
    assert recorder.calls == []


def test_muse_is_disabled_by_default_and_last_in_the_default_order() -> None:
    from app.transcription.contract import DISABLED_BY_DEFAULT, PROVIDER_IDS

    assert PROVIDER_IDS[-1] == "muse"
    assert "muse" in DISABLED_BY_DEFAULT
    assert service.sanitise_order(["deepgram", "nope", "deepgram"]) == [
        "deepgram",
        "elevenlabs",
        "whisper",
        "muse",
    ]


# --- The other three adapters: segments come back with the text ------------------------------


def test_deepgram_paragraph_segments_carry_timings(post) -> None:
    post(
        _Response(
            payload=_deepgram(
                {
                    "transcript": "a b",
                    "paragraphs": {
                        "paragraphs": [
                            {
                                "speaker": 0,
                                "start": 0.5,
                                "end": 2.0,
                                "sentences": [{"text": "Aap?", "start": 0.5, "end": 2.0}],
                            },
                            {
                                "speaker": 1,
                                "start": 2.1,
                                "end": 3.0,
                                "sentences": [{"text": "Haan.", "start": 2.1, "end": 3.0}],
                            },
                        ]
                    },
                }
            )
        )
    )
    result = ADAPTERS["deepgram"].transcribe(CLIP, SttOptions(), KEY)
    assert [s.as_json() for s in result.segments] == [
        {"startMs": 500, "endMs": 2000, "speaker": "0", "text": "Aap?"},
        {"startMs": 2100, "endMs": 3000, "speaker": "1", "text": "Haan."},
    ]


def test_elevenlabs_word_segments_group_by_voice(post) -> None:
    post(
        _Response(
            payload={
                "text": "x",
                "words": [
                    {"text": "Dabu", "type": "word", "speaker_id": "s0", "start": 0.0, "end": 0.4},
                    {"text": " ", "type": "spacing", "speaker_id": "s0", "start": 0.4, "end": 0.5},
                    {
                        "text": "kaise?",
                        "type": "word",
                        "speaker_id": "s0",
                        "start": 0.5,
                        "end": 0.9,
                    },
                    {
                        "text": "Mitti.",
                        "type": "word",
                        "speaker_id": "s1",
                        "start": 1.0,
                        "end": 1.4,
                    },
                ],
            }
        )
    )
    result = ADAPTERS["elevenlabs"].transcribe(CLIP, SttOptions(), KEY)
    assert [(s.speaker, s.text, s.start_ms, s.end_ms) for s in result.segments] == [
        ("s0", "Dabu kaise?", 0, 900),
        ("s1", "Mitti.", 1000, 1400),
    ]


# --- The chain decision matrix ---------------------------------------------------------------


class _Fake:
    """An adapter that answers from a script, keeping every call."""

    def __init__(
        self, provider: str, script: list, *, max_bytes=None, needs_wav=False, chunkable=False
    ) -> None:
        self.id = provider
        self.display_name = provider
        self.price_per_hour_usd = 0.36
        self.max_bytes = max_bytes
        self.max_duration_ms = None
        self.needs_wav = needs_wav
        self.chunkable = chunkable
        self.script = list(script)
        self.calls: list[AudioInput] = []

    def transcribe(self, audio, opts, key):
        self.calls.append(audio)
        step = self.script.pop(0) if len(self.script) > 1 else self.script[0]
        if isinstance(step, Exception):
            raise step
        return step


def _ok(provider: str, text: str = "sanganeri chhapai") -> SttResult:
    return SttResult("COMPLETED", text, provider, "m")


def _slots(*fakes: _Fake) -> list[chain.Slot]:
    return [chain.Slot(f.id, f, ApiKey(f"{f.id.upper()}_KEY", "k")) for f in fakes]


class _Hooks(chain.NullHooks):
    def __init__(self) -> None:
        self.events: list = []
        self.throttles: list = []
        self.parts: list = []
        self.successes: list = []

    def usage(self, event):
        self.events.append(event)

    def throttled(self, provider, key_ref, retry_after):
        self.throttles.append((provider, key_ref, retry_after))
        return

    def succeeded(self, provider, key_ref):
        self.successes.append(provider)

    def part_done(self, provider, plan, result):
        self.parts.append((provider, plan.index))


def test_a_throttled_provider_hands_over_to_the_next_and_starts_its_own_cooldown() -> None:
    a = _Fake("elevenlabs", [Throttled("elevenlabs", 429, retry_after=45)])
    b = _Fake("deepgram", [_ok("deepgram")])
    hooks = _Hooks()

    outcome = chain.run_chain(CLIP, _slots(a, b), SttOptions(), hooks=hooks)

    assert outcome.status == "COMPLETED"
    assert outcome.result.provider == "deepgram"
    assert hooks.throttles == [("elevenlabs", "ELEVENLABS_KEY", 45)]
    assert hooks.successes == ["deepgram"]
    assert [e.outcome for e in hooks.events] == ["throttled", "COMPLETED"]


def test_a_cooling_provider_is_skipped_without_a_call() -> None:
    now = datetime(2026, 10, 18, 3, 0, tzinfo=UTC)
    a = _Fake("elevenlabs", [_ok("elevenlabs")])
    b = _Fake("deepgram", [_ok("deepgram")])
    state = chain.ChainState(
        cooldowns={("elevenlabs", "ELEVENLABS_KEY"): now + timedelta(minutes=5)}
    )

    outcome = chain.run_chain(CLIP, _slots(a, b), SttOptions(), state, now=now)

    assert outcome.result.provider == "deepgram"
    assert a.calls == []


def test_every_provider_throttled_or_cooling_defers_without_failing() -> None:
    now = datetime(2026, 10, 18, 3, 0, tzinfo=UTC)
    a = _Fake("elevenlabs", [Throttled("elevenlabs", 429, retry_after=45)])
    b = _Fake("deepgram", [_ok("deepgram")])
    state = chain.ChainState(cooldowns={("deepgram", "DEEPGRAM_KEY"): now + timedelta(seconds=90)})

    outcome = chain.run_chain(CLIP, _slots(a, b), SttOptions(), state, now=now)

    assert outcome.status == "RATE_LIMITED"
    assert outcome.retry_after == 45


def test_a_rejected_key_is_a_hard_failure_when_nothing_else_answers() -> None:
    a = _Fake("elevenlabs", [KeyRejected("elevenlabs", 401)])
    b = _Fake("deepgram", [Throttled("deepgram", 429)])
    outcome = chain.run_chain(CLIP, _slots(a, b), SttOptions())
    assert outcome.status == "FAILED"
    assert [f.kind for f in outcome.failures] == ["key", "throttled"]


def test_budget_exhausted_moves_on_and_all_budgets_exhausted_defers() -> None:
    a = _Fake("elevenlabs", [_ok("elevenlabs")])
    b = _Fake("deepgram", [_ok("deepgram")])
    state = chain.ChainState(budget_minutes_left={"elevenlabs": 0.1})  # the clip is 0.5 minutes

    assert chain.run_chain(CLIP, _slots(a, b), SttOptions(), state).result.provider == "deepgram"
    assert a.calls == []

    state = chain.ChainState(budget_minutes_left={"elevenlabs": 0.1, "deepgram": 0.0})
    outcome = chain.run_chain(CLIP, _slots(a, b), SttOptions(), state)
    assert outcome.status == "BUDGET"
    assert service.outcome_dict(outcome, _messages())["status"] == "RATE_LIMITED"
    assert service.outcome_dict(outcome, _messages())["reason"] == "budget"


def test_disabled_skipped_and_unkeyed_providers_are_never_called() -> None:
    a = _Fake("muse", [_ok("muse")])
    b = _Fake("deepgram", [_ok("deepgram")])
    c = _Fake("whisper", [_ok("whisper")])
    slots = _slots(a, b, c)
    slots[2].key = None
    state = chain.ChainState(disabled={"muse"}, skip={"deepgram"})
    outcome = chain.run_chain(CLIP, slots, SttOptions(), state)
    assert outcome.status == "UNAVAILABLE"
    assert a.calls == b.calls == c.calls == []


def test_an_empty_answer_is_kept_but_the_next_provider_still_gets_a_turn() -> None:
    a = _Fake("elevenlabs", [SttResult("EMPTY", "", "elevenlabs", "m")])
    b = _Fake("deepgram", [_ok("deepgram")])
    assert chain.run_chain(CLIP, _slots(a, b), SttOptions()).result.provider == "deepgram"
    b.script = [SttResult("EMPTY", "", "deepgram", "m")]
    assert chain.run_chain(CLIP, _slots(a, b), SttOptions()).status == "EMPTY"


def test_an_adapter_bug_falls_through_instead_of_crashing_the_job() -> None:
    a = _Fake("elevenlabs", [KeyError("words")])
    b = _Fake("deepgram", [_ok("deepgram")])
    assert chain.run_chain(CLIP, _slots(a, b), SttOptions()).result.provider == "deepgram"


def test_cooldown_honours_retry_after_doubles_caps_and_only_jitters_upwards() -> None:
    assert chain.cooldown_seconds(1, None, rng=lambda: 0.0) == 30
    assert chain.cooldown_seconds(3, None, rng=lambda: 0.0) == 120
    assert chain.cooldown_seconds(20, None, rng=lambda: 0.0) == 900
    assert chain.cooldown_seconds(1, 45, rng=lambda: 0.0) == 45
    assert chain.cooldown_seconds(1, 5, rng=lambda: 0.0) == 30  # never shorter than the base
    assert chain.cooldown_seconds(1, None, rng=lambda: 1.0) == pytest.approx(37.5)


# --- Parts: planning, resume and stitching ---------------------------------------------------


def test_parts_cut_at_a_silence_inside_the_window_and_stay_under_ten_minutes() -> None:
    plans = audio_mod.plan_parts(1_500_000, [(540_000, 542_000), (1_090_000, 1_094_000)])
    assert [p.own_start_ms for p in plans] == [0, 541_000, 1_092_000]
    assert all(p.end_ms - p.start_ms <= muse.MAX_DURATION_MS for p in plans)
    assert plans[1].start_ms == 540_000  # one second of overlap
    assert [p.file_name for p in plans] == [
        "chunk_001_of_003.wav",
        "chunk_002_of_003.wav",
        "chunk_003_of_003.wav",
    ]
    assert audio_mod.plan_parts(60_000, []) == [PartPlan(0, 1, 0, 60_000, 0)]


class _FakeCutter(audio_mod.PartCutter):
    def __init__(self, plans: list[PartPlan]) -> None:
        super().__init__(runner=lambda args: None)
        self._plans = plans
        self._tmp = tempfile.mkdtemp()

    def prepare(self, source_path):
        return self._plans

    def cut(self, part):
        path = os.path.join(self._tmp, part.file_name)
        with open(path, "wb") as handle:
            handle.write(b"RIFF")
        return path

    def close(self):
        pass


def _three_parts() -> list[PartPlan]:
    return audio_mod.plan_parts(1_500_000, [])


def test_a_failed_part_is_the_only_part_sent_again() -> None:
    plans = _three_parts()
    seg = [Segment(0, 1000, "A", "x")]
    fake = _Fake(
        "muse",
        [
            SttResult("COMPLETED", "one", "muse", "m", segments=list(seg)),
            SttResult("COMPLETED", "two", "muse", "m", segments=list(seg)),
            ProviderFailed("muse", 502),
        ],
        needs_wav=True,
    )
    hooks = _Hooks()
    first = chain.run_chain(
        CLIP, _slots(fake), SttOptions(), hooks=hooks, cutter_factory=lambda: _FakeCutter(plans)
    )
    assert first.status == "FAILED"
    assert hooks.parts == [("muse", 0), ("muse", 1)]

    state = chain.ChainState(
        done_parts={
            ("muse", 0): SttResult("COMPLETED", "one", "muse", "m", segments=list(seg)),
            ("muse", 1): SttResult("COMPLETED", "two", "muse", "m", segments=list(seg)),
        }
    )
    fake.calls.clear()
    fake.script = [SttResult("COMPLETED", "three", "muse", "m", segments=list(seg))]
    second = chain.run_chain(
        CLIP,
        _slots(fake),
        SttOptions(session_id="media1"),
        state,
        cutter_factory=lambda: _FakeCutter(plans),
    )
    assert second.status == "COMPLETED"
    assert [c.filename for c in fake.calls] == ["chunk_003_of_003.wav"]
    assert second.result.text == "one\n\ntwo\n\nthree"
    assert second.result.chunks == 3


def test_stitching_offsets_segments_drops_the_overlap_and_numbers_speakers_per_part() -> None:
    plans = audio_mod.plan_parts(1_200_000, [])
    a = SttResult(
        "COMPLETED",
        "**Speaker 1:** a\n\n**Speaker 2:** b",
        "muse",
        "m",
        speakers=2,
        segments=[Segment(0, 1000, "X", "a"), Segment(1000, 2000, "Y", "b")],
    )
    b = SttResult(
        "COMPLETED",
        "c",
        "muse",
        "m",
        speakers=1,
        segments=[Segment(0, 500, "Q", "overlap"), Segment(2000, 3000, "Q", "c")],
    )
    stitched = merge.stitch([(plans[1], b), (plans[0], a)])
    assert [s.speaker for s in stitched.segments] == ["1.1", "1.2", "2.1"]
    assert stitched.segments[-1].start_ms == plans[1].start_ms + 2000
    assert stitched.note == merge.PER_PART_NOTE


def test_whisper_over_its_ceiling_goes_in_parts_and_under_it_whole() -> None:
    plans = _three_parts()
    fake = _Fake("whisper", [_ok("whisper", "x")], max_bytes=4, chunkable=True)
    chain.run_chain(
        AudioInput(b"12345", None, "a.m4a", "audio/mp4", None),
        _slots(fake),
        SttOptions(),
        cutter_factory=lambda: _FakeCutter(plans),
    )
    assert len(fake.calls) == 3
    fake.calls.clear()
    chain.run_chain(
        AudioInput(b"123", None, "a.m4a", "audio/mp4", None),
        _slots(fake),
        SttOptions(),
        cutter_factory=lambda: _FakeCutter(plans),
    )
    assert len(fake.calls) == 1


# --- Pause, window, dedup --------------------------------------------------------------------


def test_the_pause_holds_everything_and_a_missing_row_reads_as_paused() -> None:
    assert schedule.queue_gate(None) == schedule.QueueGate(False, "paused")
    assert schedule.queue_gate({"transcriptionPaused": True}).reason == "paused"
    assert schedule.queue_gate({"transcriptionPaused": False}).may_run


def test_the_window_applies_once_unpaused_and_wraps_midnight() -> None:
    row = {
        "transcriptionPaused": False,
        "batchWindowEnabled": True,
        "batchWindowStart": "22:00",
        "batchWindowEnd": "05:00",
        "batchTimezone": "Asia/Kolkata",
    }
    ist_23 = datetime(2026, 10, 18, 17, 30, tzinfo=UTC)  # 23:00 IST
    ist_12 = datetime(2026, 10, 18, 6, 30, tzinfo=UTC)  # 12:00 IST
    assert schedule.queue_gate(row, ist_23).may_run
    assert schedule.queue_gate(row, ist_12).reason == "window"
    assert schedule.next_window_start(row, ist_12) == datetime(2026, 10, 18, 16, 30, tzinfo=UTC)


def test_same_bytes_same_options_share_one_cache_key() -> None:
    one = AudioInput(b"same", None, "a.wav", "audio/wav")
    with tempfile.NamedTemporaryFile(delete=False) as handle:
        handle.write(b"same")
    try:
        two = AudioInput(None, handle.name, "b.wav", "audio/wav")
        assert dedup.sha256_of(one) == dedup.sha256_of(two)
    finally:
        os.remove(handle.name)
    assert dedup.options_hash(SttOptions(keyterms=["a"])) == dedup.options_hash(
        SttOptions(keyterms=["b"])
    )
    assert dedup.options_hash(SttOptions(language_bias=["hi"])) != dedup.options_hash(SttOptions())


# --- The result dictionary every caller already reads ----------------------------------------


def _messages() -> service.Messages:
    return service.Messages(
        key_rejected=lambda n: f"{n} key",
        failed=lambda n, s: f"{n} failed",
        unreachable=lambda n: f"{n} unreachable",
        too_large=lambda n: f"{n} too large",
        busy=lambda n: f"{n} busy",
        rate_limited=lambda n: "busy, will retry",
        over_budget=lambda n: f"{n} over budget",
        all_failed="; ".join,
        unavailable="unavailable",
        budget_deferred="budget used up",
    )


def test_outcome_dict_keeps_the_shape_clients_read() -> None:
    a = _Fake("elevenlabs", [KeyRejected("elevenlabs", 401)])
    b = _Fake("deepgram", [Throttled("deepgram", 429)])
    out = service.outcome_dict(chain.run_chain(CLIP, _slots(a, b), SttOptions()), _messages())
    assert out == {
        "available": True,
        "status": "FAILED",
        "text": None,
        "formattedTranscript": None,
        "message": "ElevenLabs key; Deepgram busy",
    }
    done = service.outcome_dict(
        chain.run_chain(CLIP, _slots(_Fake("deepgram", [_ok("deepgram")])), SttOptions()),
        _messages(),
    )
    assert (
        done["status"] == "COMPLETED"
        and done["formattedTranscript"] == "Transcript\n\nsanganeri chhapai"
    )
