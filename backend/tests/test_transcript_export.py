"""Transcripts in View data / Download data (0.0.6): the pure half of ``services/transcript_export``.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (``shared/transcription.sha256``).
No database: the manifest settling, the tree settling, the flat rows, the filters and the bundle names.
The database half (a fixed number of reads, consent, mapped names) is ``test_transcript_export_db.py``.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import transcript_export as tx

REC: dict[str, Any] = {
    "mediaId": "m1", "file": "interview.m4a", "mediaType": "AUDIO", "uploadedBy": "Nandini", "uploadedAt": "2026-10-31T10:00:00Z",
    "transcriptStatus": "COMPLETED", "withheld": None, "source": "TURNS", "version": 1,
    "languages": [{"code": "hi", "label": "Hindi"}], "durationMs": 1_524_000,
    "speakers": [{"speakerKey": "speaker_0", "label": "Nandini (researcher)", "colorKey": "s0", "role": "RESEARCHER"},
                 {"speakerKey": "speaker_1", "label": "Giriraj", "colorKey": "s1", "role": "ARTISAN"}],
    "turns": [
        {"index": 0, "speakerKey": "speaker_0", "speaker": "Nandini (researcher)", "colorKey": "s0", "startMs": 4000, "endMs": 7600,
         "start": "00:00:04", "end": "00:00:07", "language": "hi", "text": "आप ये रंग कैसे बनाते हैं?",
         "english": "How do you make these colours?", "translationStatus": "TRANSLATED", "edited": False},
    ],
    "timed": True,
    "summary": ["Giriraj explains the dabu paste."],
    "quotes": [{"speaker": "Giriraj", "original": "मिट्टी और गोंद", "english": "clay and gum"}],
    "glossary": [{"term": "dabu", "gloss": "mud-resist printing", "craft": "Hand Block Printing"}],
    "chips": {"questionnaire": {"title": "Artisan interview"}, "session": {"title": "Interview_2026-07-01"},
              "targets": [{"kind": "QUESTION", "displayCode": "D3", "prompt": "Tools you use most"}],
              "artisans": [{"name": "Giriraj"}], "respondents": [], "researcher": {"name": "Nandini"},
              "sessionDate": "2026-07-01", "workshop": {"name": "Bagru"}, "languageCode": "hi", "durationMs": 852_000},
    "models": {"stt": "scribe_v2", "translation": "gemini-3.8-flash"}, "costUsd": 0.1234,
}


def test_a_recording_row_carries_everything_and_cost_only_for_administrators() -> None:
    row = tx.recording_row(REC, sees_spend=False)
    assert set(row) == set(tx.recording_columns(False))
    assert not set(tx.SPEND_COLUMNS) & set(row), "no model or cost column for a non-administrator"
    assert row["Summary"] == "Giriraj explains the dabu paste."
    assert row["Quotes"] == "Giriraj: मिट्टी और गोंद (clay and gum)"
    assert row["Glossary terms"] == "dabu - mud-resist printing"
    assert row["Section or question"] == "D3 Tools you use most" and row["Form"] == "Artisan interview"
    assert row["Speakers"] == "Nandini (researcher); Giriraj" and row["Duration"] == "25:24"
    admin = tx.recording_row(REC, sees_spend=True)
    assert admin["Speech-to-text model"] == "scribe_v2" and admin["Cost (USD)"] == "0.1234"


def test_turn_rows_have_speaker_times_and_english() -> None:
    (row,) = tx.turn_rows(REC)
    assert set(row) == set(tx.TURN_COLUMNS)
    assert (row["Turn"], row["Speaker"], row["Start"], row["Start (ms)"]) == ("1", "Nandini (researcher)", "00:00:04", "4000")
    assert row["English"] == "How do you make these colours?" and row["Colour key"] == "s0"
    assert tx.glossary_rows(REC) == [{"Media ID": "m1", "File": "interview.m4a", "Term": "dabu", "Gloss": "mud-resist printing", "Craft": "Hand Block Printing"}]


def test_a_withheld_recording_has_no_words_in_any_row() -> None:
    withheld = {**REC, "withheld": tx.WITHHELD, "turns": [], "summary": [], "quotes": [], "glossary": [], "source": None}
    row = tx.recording_row(withheld, sees_spend=False)
    assert row["Consent"] and not row["Summary"] and not row["Quotes"] and not row["Glossary terms"] and row["Turns"] == "0"
    assert tx.turn_rows(withheld) == [] and tx.glossary_rows(withheld) == []


def test_filters_parse_and_narrow() -> None:
    from starlette.datastructures import QueryParams

    f = tx.parse_filters(QueryParams("language=hi,en&status=completed&speaker=Giri&researcherId=u1&since=2026-10-01T00:00:00Z&hasEnglish=true&q=dabu"))
    assert f.language == ["hi", "en"] and f.status == ["COMPLETED"] and f.speaker == "Giri" and f.researcher == ["u1"]
    assert f.since == datetime(2026, 10, 1, tzinfo=UTC).replace(tzinfo=None) and f.has_english is True and f.q == "dabu"
    where, params = tx._where(f, "m0")
    for needle in ('"languages" &&', '"TranscriptSpeaker"', '"uploadedById"', '"createdAt" >=', '"machineEnglish"', 'ILIKE', 'm."id" >'):
        assert needle in where, needle
    assert params[0] == "m0" and ["hi", "en"] in params and "%Giri%" in params
    assert tx.parse_filters(QueryParams("")).echo()["language"] == []


def test_the_bundle_names_follow_the_naming_grammar() -> None:
    media = SimpleNamespace(
        id="m1", originalFilename="D_SEC_GIRIRAJ_001046_010720261824.m4a", mediaType="AUDIO", createdAt=datetime(2026, 7, 1, 12, 54, tzinfo=UTC),
        artisan=None, craft=None, workshop=None, product=None, tool=None, questionnaireInterview=None,
    )
    names = tx.bundle_names(media, {"position": 1})
    assert len(names) == len(tx.BUNDLE_FILES) == 8
    exts = sorted(n[0].rsplit(".", 1)[1] for n in names)
    assert exts == ["docx", "docx", "md", "md", "srt", "srt", "vtt", "vtt"]
    for name, *_ in names:
        assert name.isascii() and " " not in name and len(name) <= 150, name
    assert any("_Transcript-Bilingual-No-Timestamps" in n[0] for n in names)
    assert len({n[0] for n in names}) == 8, "no two files of one recording share a name"


def _entries(mid: str) -> list[dict[str, Any]]:
    legacy = {"path": f"{mid}.md", "content": "**Speaker 1:** hi", "mediaId": mid, "_tx": (mid, "legacy")}
    files = [
        {"path": f"{mid}{suffix}", "url": tx.transcript_file_url(mid, fmt, track, ts), "mediaId": mid, "mediaType": "TRANSCRIPT",
         "_tx": (mid, "pipeline", needs)}
        for _what, suffix, fmt, track, ts, needs in tx.BUNDLE_FILES
    ]
    return [legacy, *files]


def test_the_manifest_keeps_one_set_per_recording_and_none_when_withheld(monkeypatch: pytest.MonkeyPatch) -> None:
    plan = {
        "turns": {"withheld": False, "turns": True, "timed": True},
        "untimed": {"withheld": False, "turns": True, "timed": False},
        "legacy": {"withheld": False, "turns": False, "timed": False},
        "refused": {"withheld": True, "turns": True, "timed": True},
    }

    async def fake_plan(ids: list[str], *, client: Any = None) -> dict[str, Any]:
        return {i: plan[i] for i in ids}

    monkeypatch.setattr(tx, "bundle_plan", fake_plan)
    other = {"path": "photo.jpg", "url": "https://bucket/p", "mediaId": "p1"}
    files = [other, *[e for mid in plan for e in _entries(mid)]]
    asyncio.run(tx.finalize_manifest(files, pipeline_on=True))
    by: dict[str, list[dict[str, Any]]] = {}
    for f in files:
        assert "_tx" not in f, "markers never leave the server"
        by.setdefault(f["mediaId"], []).append(f)
    assert by["p1"] == [other]
    assert len(by["turns"]) == 8 and all(f.get("url", "").startswith("/api/transcripts/turns/download") for f in by["turns"])
    assert len(by["untimed"]) == 4 and not any(f["path"].endswith((".srt", ".vtt")) for f in by["untimed"])
    assert [f["path"] for f in by["legacy"]] == ["legacy.md"]
    assert "refused" not in by, "nothing of a recording whose consent was refused"

    off = [e for mid in ("turns", "refused") for e in _entries(mid)]
    asyncio.run(tx.finalize_manifest(off, pipeline_on=False))
    assert [f["path"] for f in off] == ["turns.md"], "with the pipeline off the stored transcript stays"


def test_the_tree_hides_a_withheld_transcript_and_lists_files(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_plan(ids: list[str], *, client: Any = None) -> dict[str, Any]:
        return {"ok": {"withheld": False, "turns": True, "timed": False}, "no": {"withheld": True, "turns": True, "timed": True}}

    monkeypatch.setattr(tx, "bundle_plan", fake_plan)
    monkeypatch.setattr(tx, "bundle_names", lambda media, kw: [
        (f"x{suffix}", fmt, track, ts, needs) for _w, suffix, fmt, track, ts, needs in tx.BUNDLE_FILES
    ])
    entries = [
        {"kind": "file", "mediaId": "ok", "transcriptAvailable": True, "_media": object()},
        {"kind": "file", "mediaId": "no", "transcriptAvailable": True, "_media": object()},
        {"kind": "file", "mediaId": "no", "content": "words", "_txMedia": "no"},
        {"kind": "folder", "name": "products"},
    ]
    out = asyncio.run(tx.settle_tree(entries, pipeline_on=True))
    assert len(out) == 3
    ok, no, folder = out
    assert [f["format"] for f in ok["transcriptFiles"]] == ["md", "md", "docx", "docx"]
    assert no["transcriptAvailable"] is False and "transcriptFiles" not in no
    assert folder["kind"] == "folder"
