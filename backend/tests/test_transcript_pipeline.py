"""The transcript pipeline's pure half, against recorded Scribe v2 and Gemini responses. No database.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (``shared/transcription.sha256``).
Nothing here calls ElevenLabs or Google: every provider answer is a file under
``tests/fixtures/transcript/`` and every HTTP call goes to a fake ``post``.
"""

from __future__ import annotations

import asyncio
import io
import json
import logging
import zipfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from app.transcription.contract import ApiKey, SttOptions
from app.transcription.providers import elevenlabs
from app.transcription.transcript import citations, gemini, insight, keyterms, render, translate
from app.transcription.transcript.turns import (
    language_label,
    short_language,
    turn_language,
    turns_from_scribe,
    turns_from_segments,
    turns_from_text,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "transcript"
KEYS = [ApiKey("GEMINI_API_KEY#1", "k1"), ApiKey("GEMINI_API_KEY#2", "k2")]


def fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


class FakeResponse:
    def __init__(self, status: int, payload: Any = None) -> None:
        self.status_code = status
        self._payload = payload
        self.headers: dict[str, str] = {}
        self.text = ""

    def json(self) -> Any:
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class FakePost:
    """Answers each call from a queue of ``(status, payload)``; records model and key per call."""

    def __init__(self, *answers: tuple[int, Any]) -> None:
        self.answers = list(answers)
        self.calls: list[dict[str, Any]] = []

    def __call__(self, url: str, **kwargs: Any) -> FakeResponse:
        self.calls.append({"url": url, "key": kwargs["headers"]["x-goog-api-key"], "body": kwargs["json"]})
        status, payload = self.answers.pop(0) if self.answers else (500, None)
        return FakeResponse(status, payload)

    def sent_user(self, index: int = 0) -> dict[str, Any]:
        return json.loads(self.calls[index]["body"]["contents"][0]["parts"][0]["text"])


# --- Scribe v2: the adapter and the turns -----------------------------------------------------


def test_scribe_is_billed_at_the_pay_as_you_go_rate_and_keeps_its_words() -> None:
    assert elevenlabs.ElevenLabs.price_per_hour_usd == 0.22
    words = elevenlabs.parse_words(fixture("scribe_v2"))
    assert all(w["type"] != "audio_event" for w in words), "an audio event is not speech"
    assert words[0] == {"text": "आप", "start": 4.0, "end": 4.3, "type": "word", "speaker_id": "speaker_0", "logprob": -0.05}


def test_the_adapter_asks_for_scribe_v2_with_the_recordings_key_terms() -> None:
    adapter = elevenlabs.ElevenLabs()
    opts = SttOptions(keyterms=["Giriraj Prasad Chhipa", "Dabu"])
    fields = adapter.fields(opts, conservative=False)
    assert ("model_id", "scribe_v2") in fields
    assert [v for k, v in fields if k == "keyterms"] == ["Giriraj Prasad Chhipa", "Dabu"]


def test_scribe_words_become_speaker_turns_with_timings_offsets_and_unsure_spans() -> None:
    turns = turns_from_scribe(fixture("scribe_v2"))
    assert [t.speaker_key for t in turns] == ["speaker_0", "speaker_1", "speaker_0", "speaker_1"]
    assert [t.text for t in turns] == [
        "आप ये रंग कैसे बनाते हैं?",
        "हम dabu के लिए मिट्टी और गोंद मिलाते हैं",
        "And how long does it dry?",
        "दो दिन, 2 दिन धूप में।",
    ]
    assert [(t.start_ms, t.end_ms) for t in turns] == [(4000, 7600), (9000, 15200), (16000, 18500), (19000, 24000)]
    assert [t.language for t in turns] == ["hi", "hi", "en", "hi"], "language follows each turn's script"
    first = turns[0]
    assert [(w.text, w.offset) for w in first.words] == [
        ("आप", 0), ("ये", 3), ("रंग", 6), ("कैसे", 10), ("बनाते", 15), ("हैं?", 21)
    ]
    for turn in turns:  # every offset points at its word: karaoke depends on it
        for word in turn.words:
            assert turn.text[word.offset : word.offset + len(word.text)] == word.text
    second = turns[1]
    assert second.low_confidence == [[3, 7], [26, 30]]
    assert [second.text[a:b] for a, b in second.low_confidence] == ["dabu", "गोंद"]
    assert first.low_confidence == []


def test_a_long_monologue_splits_only_at_a_pause() -> None:
    words = []
    t = 0.0
    for i in range(80):  # 80 words over 40 s, then a 3 s pause, then more of the same voice
        words.append({"text": f"w{i}", "start": t, "end": t + 0.4, "type": "word", "speaker_id": "s"})
        words.append({"text": " ", "type": "spacing", "speaker_id": "s"})
        t += 0.5
    t += 3.0
    words.append({"text": "after", "start": t, "end": t + 0.4, "type": "word", "speaker_id": "s"})
    turns = turns_from_scribe({"words": words, "language_code": "eng"})
    assert len(turns) == 2 and turns[1].text == "after" and turns[1].index == 1


def test_turns_without_words_come_from_segments_or_paragraphs() -> None:
    segs = turns_from_segments(
        [{"startMs": 0, "endMs": 900, "speaker": "1", "text": " नमस्ते  जी "}, {"startMs": 900, "endMs": 1000, "text": ""}],
        detected="hin",
    )
    assert len(segs) == 1 and segs[0].text == "नमस्ते जी" and segs[0].words == [] and segs[0].language == "hi"
    paragraphs = turns_from_text("One.\n\nदो।", detected=None, duration_ms=5000)
    assert [p.language for p in paragraphs] == ["en", "hi"] and all(p.start_ms == 0 for p in paragraphs)


def test_language_codes_read_as_the_portals_short_codes() -> None:
    assert short_language("hin") == "hi" and short_language("en-IN") == "en" and short_language("ory") == "or"
    assert short_language(None) is None and short_language("") is None
    assert language_label("hi") == "Hindi" and language_label("bn") == "Bengali"
    assert turn_language("আমরা কাপড় বুনি", "as") == "as", "Bengali script keeps a detected Assamese"
    assert turn_language("आम्ही विणतो", "mr") == "mr"
    assert turn_language("1 2 3", "gu") == "gu"


# --- Key terms --------------------------------------------------------------------------------


def test_key_terms_put_the_most_specific_first_and_stop_at_one_hundred() -> None:
    terms = keyterms.assemble(
        artisan_names=["Giriraj Prasad Chhipa", "  giriraj prasad chhipa "],
        craft_names=["Dabu Printing"],
        glossary_terms=["dabu", "a term far too long to be a key term because it has more than five words"],
        regional_crafts=["Bagru Print", "Dabu Printing"],
        vocabulary=["kalamkari"],
        master_crafts=[f"Craft {i}" for i in range(300)],
    )
    assert terms[:5] == ["Giriraj Prasad Chhipa", "Dabu Printing", "dabu", "Bagru Print", "kalamkari"]
    assert len(terms) == keyterms.LIMIT
    assert all(len(t) <= keyterms.MAX_CHARS and len(t.split()) <= keyterms.MAX_WORDS for t in terms)


# --- Gemini transport -------------------------------------------------------------------------


def test_a_throttled_key_moves_to_the_next_key_and_a_missing_model_to_the_fallback() -> None:
    post = FakePost((429, None), (404, None), (200, fixture("gemini_ask")))
    reply = gemini.generate_json(system="s", user="u", keys=KEYS, post=post)
    assert reply.model == gemini.FALLBACK_MODEL and reply.key_ref == "GEMINI_API_KEY#1"
    assert [c["key"] for c in post.calls] == ["k1", "k2", "k1"]
    assert "gemini-3.8-flash" in post.calls[0]["url"] and "gemini-3.7-flash" in post.calls[2]["url"]
    assert reply.cost_usd == gemini.cost_usd(gemini.FALLBACK_MODEL, 700, 80) > 0
    body = post.calls[0]["body"]
    assert body["generationConfig"]["responseMimeType"] == "application/json"


def test_every_key_refused_is_final_and_a_busy_provider_is_retryable() -> None:
    with pytest.raises(gemini.GeminiError) as refused:
        gemini.generate_json(system="s", user="u", keys=KEYS, post=FakePost((401, None), (403, None)))
    assert refused.value.kind == gemini.KEY and refused.value.retry is False
    with pytest.raises(gemini.GeminiError) as busy:
        gemini.generate_json(system="s", user="u", keys=KEYS, post=FakePost(*[(429, None)] * 4))
    assert busy.value.kind == gemini.THROTTLED and busy.value.retry is True
    with pytest.raises(gemini.GeminiError) as blocked:
        gemini.generate_json(system="s", user="u", keys=KEYS[:1], post=FakePost((200, fixture("gemini_blocked")), (200, fixture("gemini_blocked"))))
    assert blocked.value.kind == gemini.DECLINED
    with pytest.raises(gemini.GeminiError) as none:
        gemini.generate_json(system="s", user="u", keys=[], post=FakePost())
    assert none.value.kind == gemini.UNAVAILABLE


def test_nothing_the_model_was_sent_reaches_a_log_line(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)
    turns = [translate.TurnIn("A", "Speaker 1", "hi", "गुप्त बात जो लॉग में नहीं जानी चाहिए")]
    with pytest.raises(gemini.GeminiError):
        translate.run_pass(turns, keys=KEYS, post=FakePost((500, None), (500, None), (500, None), (500, None)))
    joined = "\n".join(record.getMessage() for record in caplog.records)
    assert "गुप्त" not in joined and "k1" not in joined and "k2" not in joined


# --- Translation ------------------------------------------------------------------------------


def _turns_in() -> list[translate.TurnIn]:
    turns = turns_from_scribe(fixture("scribe_v2"))
    return [translate.TurnIn(chr(65 + t.index), f"Speaker {t.index % 2 + 1}", t.language, t.text) for t in turns]


def test_one_pass_translates_turn_by_turn_keyed_by_id_and_keeps_english_as_it_is() -> None:
    post = FakePost((200, fixture("gemini_translate")))
    result = translate.run_pass(_turns_in(), keys=KEYS, glossary=[{"term": "dabu", "gloss": "mud-resist printing"}], post=post)
    out = result.translations
    assert out["A"].english == "How do you make these colours?" and out["A"].status == translate.TRANSLATED
    assert out["B"].english == "We mix clay and gum for dabu (mud-resist printing)."
    assert out["C"].status == translate.ALREADY_ENGLISH and out["C"].english == "And how long does it dry?"
    assert out["D"].english == "Two days, 2 days in the sun."
    assert set(out) == {"A", "B", "C", "D"}, "an id the transcript does not have is ignored"
    sent = post.sent_user()
    assert [t["translate"] for t in sent["turns"]] == [True, True, False, True], "English is not sent to translate"
    assert sent["glossary"] == [{"term": "dabu", "gloss": "mud-resist printing"}]
    assert result.primary_language == "hi" and len(result.summary) == 2 and result.calls == 1
    assert result.quotes == [{"turnId": "B", "original": "मिट्टी और गोंद", "english": "clay and gum"}], (
        "a quote nobody said is dropped, and a real one is tied to its turn"
    )
    assert [t["term"] for t in result.terms] == ["dabu", "ajrakh"]
    assert result.cost_usd == gemini.cost_usd(gemini.MODEL, 900, 420)


def test_a_turn_left_out_is_asked_for_again_once_on_its_own() -> None:
    post = FakePost((200, fixture("gemini_translate_missing")), (200, fixture("gemini_translate_retry")))
    result = translate.run_pass(_turns_in(), keys=KEYS, post=post)
    assert result.translations["B"].english == "Clay."
    assert result.translations["D"].english == "Two days in the sun.", "the second ask does not insist on digits"
    retry = post.sent_user(1)
    assert retry["translationsOnly"] is True and [t["id"] for t in retry["turns"] if t["translate"]] == ["t4"]
    assert result.calls == 2 and result.failed_ids == []


def test_a_turn_still_missing_after_the_second_ask_is_failed_never_guessed() -> None:
    post = FakePost((200, fixture("gemini_translate_missing")), (500, None), (500, None), (500, None), (500, None))
    result = translate.run_pass(_turns_in(), keys=KEYS, post=post)
    assert result.translations["D"].status == translate.FAILED and result.translations["D"].english is None
    assert result.failed_ids == ["D"]


def test_a_summary_or_a_dropped_number_is_not_a_translation() -> None:
    long = "हम " * 60
    assert translate.suspicious(long, "We do.") is True
    assert translate.suspicious("२ दिन", "Two days") is True, "an Indic digit read as 2 must survive"
    assert translate.suspicious("२ दिन", "2 days") is False
    assert translate.suspicious("२ दिन", "Two days", numbers=False) is False
    assert translate.suspicious("", "") is False


def test_long_transcripts_split_on_turn_boundaries_and_gloss_a_term_once() -> None:
    turns = [translate.TurnIn(f"id{i}", "S", "hi", "क" * 30) for i in range(5)]
    parts = translate.batches(turns, limit=70)
    assert [[t.id for t in p] for p in parts] == [["id0", "id1"], ["id2", "id3"], ["id4"]]
    first = {"translations": [{"id": "t1", "english": "a dabu (mud-resist) b " + "x" * 10, "alreadyEnglish": False},
                              {"id": "t2", "english": "y" * 12, "alreadyEnglish": False}], "summary": ["one"]}
    second = {"translations": [{"id": "t3", "english": "z" * 12, "alreadyEnglish": False},
                               {"id": "t4", "english": "w" * 12, "alreadyEnglish": False}], "summary": ["two"]}
    third = {"translations": [{"id": "t5", "english": "v" * 12, "alreadyEnglish": False}], "summary": ["three"]}

    def reply(obj: dict[str, Any]) -> tuple[int, Any]:
        return 200, {"candidates": [{"content": {"parts": [{"text": json.dumps(obj)}]}, "finishReason": "STOP"}]}

    post = FakePost(reply(first), reply(second), reply(third))
    result = translate.run_pass(turns, keys=KEYS, post=post, batch_chars=70)
    assert result.calls == 3 and not result.failed_ids
    assert post.sent_user(1)["alreadyGlossed"] == ["dabu"], "a gloss from part one is passed to part two"
    assert result.summary == ["one", "two", "three"]


# --- Renderers: see test_transcript_contract.py for the byte-for-byte fixtures -----------------


def _doc() -> render.RenderDoc:
    return render.RenderDoc(
        title="Interview · A · 1 Nov 2026",
        languages=["Hindi", "English"],
        duration_ms=24_000,
        speaker_count=2,
        turns=[
            render.RenderTurn("A", "Nandini (researcher)", 4000, 7600, "आप?", "You?\nReally?", "TRANSLATED"),
            render.RenderTurn("C", "Na*n|di`ni", 16000, 18500, "And how long --> dry?", "And how long --> dry?", "ALREADY_ENGLISH"),
            render.RenderTurn("D", "Giriraj", 19000, 19000, "दो दिन", None, "FAILED"),
        ],
    )


def test_copies_quote_every_english_line_and_never_repeat_english() -> None:
    both = render.copy_all(_doc(), render.BOTH)
    assert "आप?\n> You?\n> Really?" in both
    assert "**Nandini** · `00:00:16`\nAnd how long --> dry?\n\n" in both, "already English: printed once"
    assert both.endswith("**Giriraj** · `00:00:19`\nदो दिन"), "no translation: no quote line"
    assert render.copy_all(_doc(), render.ENGLISH).endswith("दो दिन"), "no translation: the original stands in"


def test_subtitles_never_carry_a_cue_arrow_or_an_empty_cue() -> None:
    vtt = render.vtt(_doc(), render.ORIGINAL)
    assert vtt.count("-->") == 3 and "→" in vtt
    srt = render.srt(_doc(), render.ENGLISH)
    assert "00:00:19,000 --> 00:00:20,000" in srt, "a zero-length turn gets one second"


def test_the_docx_is_a_word_package_with_every_turn() -> None:
    data = render.docx(_doc(), render.BOTH)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert set(archive.namelist()) == {"[Content_Types].xml", "_rels/.rels", "word/document.xml"}
        xml = archive.read("word/document.xml").decode("utf-8")
    assert "आप?" in xml and "You?" in xml and "&gt; dry?" in xml and "<w:i/>" in xml


# --- Citations: Ask and auto-draft ------------------------------------------------------------


def _cite_turns() -> list[citations.CiteTurn]:
    turns = turns_from_scribe(fixture("scribe_v2"))
    english = {0: "How do you make these colours?", 1: "We mix clay and gum for dabu.", 2: None, 3: "Two days, 2 days in the sun."}
    return [citations.CiteTurn(f"turn_{t.index}", t.start_ms, "S", t.text, english[t.index]) for t in turns]


def test_ask_keeps_only_citations_of_real_turns_whose_quote_is_in_that_turn() -> None:
    post = FakePost((200, fixture("gemini_ask")))
    turns = _cite_turns()
    result = citations.ask("How does he make the paste?", turns, keys=KEYS, post=post)
    assert result.covered is True
    assert [(c.turn_id, c.quote) for c in result.citations] == [("turn_1", "मिट्टी और गोंद"), ("turn_3", "2 days in the sun")]
    by_id = {t.id: t for t in turns}
    for c in result.citations:  # the integrity rule, stated as the test
        turn = by_id[c.turn_id]
        assert c.start_ms == turn.start_ms
        assert translate.normalise(c.quote) in translate.normalise(turn.text) or translate.normalise(c.quote) in translate.normalise(turn.english or "")
    assert post.sent_user()["turns"][1] == {"id": "t2", "time": "00:00:09", "speaker": "S", "original": turns[1].text, "english": turns[1].english}


def test_an_answer_that_cannot_point_at_the_transcript_says_it_is_not_covered() -> None:
    result = citations.ask("What mordant?", _cite_turns(), keys=KEYS, post=FakePost((200, fixture("gemini_ask_uncited"))))
    assert result.covered is False and result.answer == citations.NOT_COVERED and result.citations == []
    assert citations.ask("Anything?", [], keys=KEYS, post=FakePost()).covered is False


def test_auto_draft_keeps_only_cited_answers_to_questions_it_was_asked() -> None:
    drafts, reply = citations.draft([(1, "How is the paste made?"), (2, "How long does it dry?"), (3, "Which dye?")],
                                    _cite_turns(), keys=KEYS, post=FakePost((200, fixture("gemini_autodraft"))))
    assert [d.n for d in drafts] == [1, 2], "an answer citing a quote not in its turn, and question 7, are dropped"
    assert drafts[0].english == "They mix clay and gum." and drafts[1].citations[0].quote is None
    assert reply is not None and reply.model == gemini.MODEL


# --- The small rules --------------------------------------------------------------------------


def test_the_budget_alerts_at_eighty_and_pauses_at_one_hundred_once_a_month() -> None:
    meter = insight.Meter("2026-10", 100.0, 70.0, 11.5)
    assert meter.state == insight.ALERT and meter.as_json(paused_by_budget=False)["percent"] == 81.5
    assert insight.budget_actions(meter, alerted_month=None, paused_month=None) == insight.BudgetActions(True, False)
    assert insight.budget_actions(meter, alerted_month="2026-10", paused_month=None) == insight.BudgetActions(False, False)
    full = insight.Meter("2026-10", 100.0, 90.0, 10.0)
    assert full.state == insight.PAUSED
    assert insight.budget_actions(full, alerted_month="2026-10", paused_month=None).pause is True
    assert insight.budget_actions(full, alerted_month="2026-10", paused_month="2026-10").pause is False
    assert insight.Meter("2026-10", None, 5.0, 0.0).state == insight.UNSET
    assert insight.month_key(datetime(2026, 10, 31, 19, 0, tzinfo=UTC)) == "2026-11", "India time"


def _status(**overrides: Any) -> str:
    base = {
        "transcript_status": "COMPLETED", "transcript_error": None, "has_text": True, "stt_job": "COMPLETED",
        "translate_job": "COMPLETED", "translate_error": None, "version_ready": True, "pending_turns": 0,
        "paused": False, "budget_paused": False, "in_window": True, "consent_refused": False,
    }
    base.update(overrides)
    state = insight.pipeline_status(**base)
    return f"{state.status}:{state.queue_reason}" if state.queue_reason else state.status


def test_the_six_states() -> None:
    assert _status() == "READY"
    assert _status(has_text=False, consent_refused=True) == "WAITING_FOR_CONSENT"
    assert _status(has_text=False, stt_job="QUEUED", transcript_status="QUEUED", paused=True) == "QUEUED:PAUSED"
    assert _status(has_text=False, stt_job="QUEUED", budget_paused=True, paused=True) == "QUEUED:BUDGET_PAUSED"
    assert _status(has_text=False, stt_job="QUEUED", in_window=False) == "QUEUED:WINDOW"
    assert _status(has_text=False, stt_job="PROCESSING") == "TRANSCRIBING"
    assert _status(translate_job="PROCESSING") == "TRANSLATING"
    assert _status(has_text=False, stt_job="FAILED", transcript_status="FAILED") == "NEEDS_ATTENTION"
    assert _status(translate_job="FAILED", version_ready=False) == "NEEDS_ATTENTION"


class _T:
    def __init__(self, id: str, start: int, end: int, text: str, english: str = "") -> None:
        self.id, self.start_ms, self.end_ms, self.text, self.english = id, start, end, text, english


def test_glossary_terms_are_only_what_was_heard_and_link_to_the_master_list() -> None:
    turns = [_T("a", 0, 1, "हम dabu करते हैं", "We do dabu (mud-resist)."), _T("b", 5, 6, "Ajrakh nahi", "")]
    found = insight.sightings([{"term": "Dabu", "gloss": "mud-resist"}, {"term": "kalamkari", "gloss": "pen art"},
                               {"term": "ajrakh", "gloss": ""}], turns)
    assert [(s.term, s.turn_id) for s in found] == [("Dabu", "a"), ("ajrakh", "b")]
    crafts = [("c1", "Dabu Printing"), ("c2", "Ajrakh"), ("c3", "Bandhani")]
    assert insight.match_craft("ajrakh", crafts) == "c2"
    assert insight.match_craft("Dabu", crafts) == "c1"
    assert insight.match_craft("pot", crafts) is None
    assert insight.term_key("Bāndhanī") == "bandhani"


def test_a_quote_card_clip_is_padded_and_clamped() -> None:
    assert insight.clip_range([_T("a", 300, 2000, ""), _T("b", 4000, 9800, "")], 10_000) == (0, 10_000)
    assert insight.clip_range([_T("a", 4000, 5000, "")], None) == (3500, 5500)
    assert insight.card_date(datetime(2026, 6, 24, 20, 0, tzinfo=UTC)) == "25 Jun 2026"


# --- Consent and budget gates before an on-demand send ----------------------------------------


def test_every_on_demand_send_re_reads_consent_and_the_budget(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import transcript_pipeline as pipeline, transcript_portal

    async def refused(_media: Any) -> tuple[bool, str | None]:
        return False, "Consent was refused, so this recording stays on this server."

    async def allowed(_media: Any) -> tuple[bool, str | None]:
        return True, None

    async def row() -> Any:
        return {"transcriptionPaused": False, "transcriptMonthlyBudgetUsd": 100.0}

    async def spent() -> tuple[float, float]:
        return 99.0, 2.0

    async def keys() -> list[ApiKey]:
        return KEYS

    monkeypatch.setattr(pipeline, "enabled", lambda: True)
    monkeypatch.setattr(pipeline, "_settings_row", row)
    monkeypatch.setattr(pipeline, "gemini_keys", keys)
    monkeypatch.setattr(pipeline.transcript_store, "month_spend", spent)
    monkeypatch.setattr(transcript_portal, "may_send", refused)
    with pytest.raises(pipeline.PipelineRefused) as consent:
        asyncio.run(pipeline._send_gate({"id": "m1"}))
    assert consent.value.code == "CONSENT_REFUSED"
    monkeypatch.setattr(transcript_portal, "may_send", allowed)
    with pytest.raises(pipeline.PipelineRefused) as budget:
        asyncio.run(pipeline._send_gate({"id": "m1"}))
    assert budget.value.code == "BUDGET_PAUSED"

    async def cheap() -> tuple[float, float]:
        return 1.0, 0.5

    monkeypatch.setattr(pipeline.transcript_store, "month_spend", cheap)
    assert asyncio.run(pipeline._send_gate({"id": "m1"})) == KEYS
    monkeypatch.setattr(pipeline, "enabled", lambda: False)
    with pytest.raises(pipeline.PipelineRefused) as off:
        asyncio.run(pipeline._send_gate({"id": "m1"}))
    assert off.value.code == "NOT_FOUND"


def test_translation_waits_while_the_budget_paused_transcription_this_month() -> None:
    from app.services import transcript_pipeline as pipeline

    month = insight.month_key()
    assert pipeline.holds_translation({"transcriptionPaused": True, "transcriptBudgetPausedMonth": month})
    assert not pipeline.holds_translation({"transcriptionPaused": False, "transcriptBudgetPausedMonth": month})
    assert not pipeline.holds_translation({"transcriptionPaused": True, "transcriptBudgetPausedMonth": "2020-01"})


def test_a_completed_result_becomes_turns_whichever_shape_it_arrived_in() -> None:
    from app.services import transcript_pipeline as pipeline

    scribe = fixture("scribe_v2")
    words = elevenlabs.parse_words(scribe)
    assert len(pipeline.turns_from_result({"words": words, "languageCode": "hin"})) == 4
    assert len(pipeline.turns_from_result({"segments": [{"startMs": 0, "endMs": 5, "text": "hi there"}]})) == 1
    assert len(pipeline.turns_from_result({"text": "a\n\nb"})) == 2

    def strict(content, filename, mime_type, settings, *, user_id=None):  # noqa: ANN001
        return None

    def wide(content, filename, mime_type, settings, **kwargs):  # noqa: ANN001
        return None

    assert pipeline.accepts_keyterms(strict) is False, "an old fake is never handed a new argument"
    assert pipeline.accepts_keyterms(wide) is True


# --- The backfill ------------------------------------------------------------------------------


def _backfill() -> Any:
    import importlib.util

    path = Path(__file__).resolve().parents[1] / "scripts" / "backfill_transcripts.py"
    spec = importlib.util.spec_from_file_location("backfill_transcripts", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_the_backfill_goes_oldest_first_inside_the_budget() -> None:
    backfill = _backfill()
    c = backfill.Candidate
    candidates = [
        c("new", backfill.UNTRANSCRIBED, "2026-10-20", 60.0),
        c("old", backfill.FAILED, "2026-06-01", 60.0),
        c("mid", backfill.QUEUED, "2026-08-01", 60.0),
    ]
    one_hour = candidates[0].estimate_usd
    assert one_hour == round(60 * (0.22 / 60 + 0.002), 6)
    assert [x.media_id for x in backfill.next_batch(candidates, limit=10, budget_left_usd=None)] == ["old", "mid", "new"]
    assert [x.media_id for x in backfill.next_batch(candidates, limit=2, budget_left_usd=None)] == ["old", "mid"]
    assert [x.media_id for x in backfill.next_batch(candidates, limit=10, budget_left_usd=one_hour * 2.5)] == ["old", "mid"]
    assert backfill.next_batch(candidates, limit=10, budget_left_usd=0.0) == []
    assert backfill.minutes_of(90_000, None) == 1.5 and backfill.minutes_of(None, 480_000) == 1.0
