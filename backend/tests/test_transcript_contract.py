"""``shared/transcript-contract.json`` is what the server does: routes, shapes, and copy fixtures.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (``shared/transcription.sha256``).
The web and Android clients load the same fixtures and must reproduce them byte for byte when they
copy locally; this test proves the server's renderer does. No database.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from app.transcription.transcript import render
from app.transcription.transcript.schema import (
    AskBody,
    BudgetBody,
    CopyBody,
    QuoteCardBody,
    SpeakerMappingBody,
    TurnEditBody,
)

REPO = Path(__file__).resolve().parents[2]
CONTRACT = json.loads((REPO / "shared" / "transcript-contract.json").read_text(encoding="utf-8"))
STRINGS = json.loads((REPO / "shared" / "strings" / "transcript.json").read_text(encoding="utf-8"))
VIEW = CONTRACT["examples"]["TranscriptView"]
FIX = CONTRACT["fixtures"]


def doc_from(view: dict[str, Any]) -> render.RenderDoc:
    return render.RenderDoc(
        title=view["title"],
        languages=[lang["label"] for lang in view["languages"]],
        duration_ms=view["durationMs"],
        speaker_count=view["speakerCount"],
        turns=[
            render.RenderTurn(
                t["id"], t["speakerName"], t["startMs"], t["endMs"], t["text"],
                t["translation"]["text"], t["translation"]["status"],
            )
            for t in view["turns"]
        ],
    )


def test_every_contract_route_is_served_with_its_method() -> None:
    from app.api.routes.transcripts import router

    served = {(method, f"/api{route.path}") for route in router.routes for method in getattr(route, "methods", ())}
    missing = [(r["method"], r["path"]) for r in CONTRACT["routes"] if (r["method"], r["path"].replace("{mediaId}", "{media_id}").replace("{turnId}", "{turn_id}")) not in served]
    assert missing == [], f"routes in the contract that the server does not serve: {missing}"


def test_static_paths_are_declared_before_the_media_id_path() -> None:
    from app.api.routes.transcripts import router

    paths = [route.path for route in router.routes]
    first_dynamic = paths.index("/transcripts/{media_id}")
    for static in ("/transcripts/batch", "/transcripts/glossary", "/transcripts/budget"):
        assert paths.index(static) < first_dynamic, f"{static} would be matched as a media id"


def test_the_examples_carry_every_field_their_type_names() -> None:
    types = CONTRACT["types"]
    assert set(VIEW) == set(types["TranscriptView"])
    for turn in VIEW["turns"]:
        assert set(turn) == set(types["Turn"])
    assert set(CONTRACT["examples"]["BudgetMeter"]) == set(types["BudgetMeter"])
    assert set(CONTRACT["examples"]["AskAnswer"]) == set(types["AskAnswer"])


def test_the_example_bodies_validate() -> None:
    SpeakerMappingBody.model_validate({"baseRevision": 3, "mappings": [{"speakerKey": "speaker_0", "name": "Nandini", "role": "RESEARCHER", "personId": "u_1"}]})
    TurnEditBody.model_validate({"baseRevision": 1, "text": "नया"})
    CopyBody.model_validate({"track": "both", "turnIds": ["turn_b"], "single": False})
    AskBody.model_validate({"question": "How does he prepare the mordant?"})
    QuoteCardBody.model_validate({"turnIds": ["turn_a", "turn_b"]})
    BudgetBody.model_validate({"monthlyUsd": 100})
    with pytest.raises(ValueError):
        CopyBody.model_validate({"track": "french"})
    with pytest.raises(ValueError):
        QuoteCardBody.model_validate({"turnIds": []})


@pytest.mark.parametrize(("ms", "expected"), [tuple(case) for case in FIX["timestamp"]["cases"]])
def test_timestamps(ms: int, expected: str) -> None:
    assert render.timestamp(ms) == expected


@pytest.mark.parametrize(("ms", "expected"), [tuple(case) for case in FIX["length"]["cases"]])
def test_lengths(ms: int, expected: str) -> None:
    assert render.length(ms) == expected


def test_the_copy_fixtures_byte_for_byte() -> None:
    doc = doc_from(VIEW)
    copy = FIX["copy"]
    assert render.copy_all(doc, "original") == copy["all_original"]
    assert render.copy_all(doc, "english") == copy["all_english"]
    assert render.copy_all(doc, "both") == copy["all_both"]
    assert render.copy_all(doc, "both", timestamps=False) == copy["all_both_no_timestamps"]
    assert "`00:" not in copy["all_both_no_timestamps"], "no timestamp on any turn"
    assert render.copy_selected(doc, ["turn_b"], "both") == copy["selected_both_turn_b"]
    first = doc.turns[0]
    assert render.copy_single(first, "original") == copy["single_original_turn_a"]
    assert render.copy_single(first, "english") == copy["single_english_turn_a"]
    assert render.copy_single(first, "both") == copy["single_both_turn_a"]


def test_the_subtitle_fixtures_byte_for_byte() -> None:
    doc = doc_from(VIEW)
    assert render.srt(doc, "original") == FIX["srt_original"]
    assert render.vtt(doc, "english") == FIX["vtt_english"]


def test_the_example_offsets_point_at_their_words() -> None:
    for turn in VIEW["turns"]:
        for word in turn["words"]:
            assert turn["text"][word["offset"] : word["offset"] + len(word["text"])] == word["text"]
        for start, end in turn["lowConfidence"]:
            assert 0 <= start < end <= len(turn["text"])


def test_the_strings_are_complete_sentences_with_no_gap_admissions() -> None:
    strings = STRINGS["strings"]
    statuses = {s["value"]: s["label"] for s in CONTRACT["enums"]["pipelineStatus"]}
    assert strings["statusReady"] == statuses["READY"] and strings["statusNeedsAttention"] == statuses["NEEDS_ATTENTION"]
    for key, value in strings.items():
        lowered = value.lower()
        assert value.strip() == value and value, key
        assert "not built" not in lowered and "coming soon" not in lowered and "no endpoint" not in lowered, key


# --- 0.0.6: speaker colours and list previews (contractVersion 2) ----------------------------


@pytest.mark.parametrize(("key", "label", "expected"), [tuple(case) for case in FIX["speakerColor"]["cases"]])
def test_speaker_colours(key: str | None, label: str | None, expected: str) -> None:
    from app.transcription.transcript import preview

    assert preview.color_key(key, label) == expected


def test_the_palette_has_a_light_and_dark_colour_for_every_key() -> None:
    from app.transcription.transcript import preview

    palette = CONTRACT["speakerPalette"]
    keys = {f"s{n}" for n in range(preview.PALETTE_SIZE)} | {preview.NEUTRAL}
    assert keys <= set(palette)
    for key in keys:
        assert set(palette[key]) == {"light", "dark"}
        for value in palette[key].values():
            assert len(value) == 7 and value.startswith("#"), (key, value)
    assert len({palette[k]["light"] for k in keys}) == len(keys), "every speaker reads differently"


def test_the_example_colour_keys_follow_the_rule() -> None:
    from app.transcription.transcript import preview

    for speaker in VIEW["speakers"]:
        assert speaker["colorKey"] == preview.color_key(speaker["speakerKey"])
    for turn in VIEW["turns"]:
        assert turn["colorKey"] == preview.color_key(turn["speakerKey"])


@pytest.mark.parametrize("case", FIX["preview"]["cases"], ids=lambda c: c["name"])
def test_the_preview_fixtures(case: dict[str, Any]) -> None:
    from app.transcription.transcript import preview

    if "markdown" in case:
        got = preview.preview_markdown(case["markdown"])
    else:
        got = preview.preview([preview.PreviewTurn(t["speakerKey"], t["speaker"], t["text"]) for t in case["turns"]])
    assert got == case["expected"]
    if got is not None:
        assert set(got) == set(CONTRACT["types"]["TranscriptPreview"])
        for line in got["lines"]:
            assert set(line) == set(CONTRACT["types"]["PreviewLine"])
            assert "*" not in line["text"] and "`" not in line["text"], "a preview is plain text"
            assert len(line["text"]) <= preview.LINE_CHARS
        assert 1 <= len(got["lines"]) <= preview.PREVIEW_LINES


def test_a_legacy_preview_has_no_transcript_prefix_or_markdown() -> None:
    from app.transcription.transcript import preview

    got = preview.preview_markdown("Transcript **Speaker 1:** Wow! It is *lovely*.")
    assert got == {
        "text": "Speaker 1: Wow! It is lovely.",
        "lines": [{"speaker": "Speaker 1", "speakerKey": None, "colorKey": "s0", "text": "Wow! It is lovely."}],
    }
