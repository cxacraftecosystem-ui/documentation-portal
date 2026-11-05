from __future__ import annotations

import importlib
import io
import json
import os
import re
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from pypdf import PdfReader

from app.services.datareport import i18n, pack, pdf_summary, share_links, share_snapshot

NOW = datetime(2026, 11, 5, 10, 30, tzinfo=UTC)
DEVANAGARI = re.compile(r"[ऀ-ॿ]")


def _summary(locale: str) -> pdf_summary.Summary:
    t = i18n.translator(locale)
    return pdf_summary.Summary(
        portal_title="Design Workshop",
        folder="Bagru block printing",
        path="by-workshop/w1",
        generated_by="Explorer Admin",
        generated_at=NOW,
        data_version="12:3456",
        options=[
            (t("pack.option.answers", "Answers"), t("pack.answers.both", "Original and English"))
        ],
        kpis=[
            (t("pack.kpi.files", "Files"), "128", "3.2 GB"),
            (t("pack.kpi.coverage", "Coverage"), "64%", ""),
        ],
        media_by_type=[(t("pack.type.images", "Images"), 80, 1 << 30)],
        transcript_status=pack._status_bars(
            {"total": 4, "ready": 2, "translating": 1, "failed": 1}, t
        ),
        sections=[pdf_summary.SectionBar("Identity", 10, 2, 1)],
        coverage=[
            pdf_summary.Coverage(
                "Bagru",
                [("S1", "Identity"), ("S2", "Craft")],
                [
                    pdf_summary.CoverageRow(
                        "Kamla", "Dabu", 12, 40, 7, "3 of 4 ready", ["complete", "partial"]
                    )
                ],
            )
        ],
        quotes=[pdf_summary.Quote("Plain words", "", None, "clip.mp4")],
        pack_files=pack.pack_contents("Report.xlsx", "Summary.pdf", t),
        locale=locale,
    )


def _text(data: bytes) -> str:
    return "\n".join(page.extract_text() or "" for page in PdfReader(io.BytesIO(data)).pages)


def test_a_hindi_summary_draws_devanagari_in_the_bundled_noto_face():
    faces = pdf_summary.fonts()
    assert "Devanagari" in faces.scripts
    data = pdf_summary.build(_summary("hi"))
    assert data.startswith(b"%PDF")
    assert b"NotoSansDevanagari" in data
    text = _text(data)
    assert DEVANAGARI.search(text)
    for word in ("रिपोर्ट सारांश", "पृष्ठ"):
        assert word in text, word
    assert "Report summary" not in text
    assert "Page 1 of" not in text


def test_the_summary_stays_english_by_default():
    assert pdf_summary.Summary.__dataclass_fields__["locale"].default == "en"
    text = _text(pdf_summary.build(_summary("en")))
    assert "Report summary" in text and "Page 1 of" in text
    assert not DEVANAGARI.search(text)


def test_canvas_strings_switch_to_the_script_face():
    faces = pdf_summary.fonts()
    pieces = pdf_summary._faced("Files: फ़ाइलें 12", faces.regular)
    assert pieces[0][0] == faces.regular
    assert any(face == faces.scripts["Devanagari"][0] for face, _ in pieces)
    assert pdf_summary.text_width("फ़ाइलें", faces.regular, 10) > 0


def test_hindi_dates_name_the_month_in_hindi():
    assert pdf_summary.stamp(NOW, "hi") == "5 नवंबर 2026, 10:30"
    assert pdf_summary.stamp(NOW, "en") == "05 Nov 2026, 10:30"


def test_the_pack_reads_its_locale_from_the_report_options():
    assert pack.locale_of(pack.PackParams(extra={"locale": "hi"})) == "hi"
    assert pack.locale_of(pack.PackParams(extra={"locale": "fr"})) == "en"
    assert pack.locale_of(pack.PackParams()) == "en"
    rows = pack._option_rows(pack.PackParams(answers="both"), i18n.translator("hi"))
    assert rows[0] == ("उत्तर", "मूल एवं अंग्रेज़ी")


def _media(mid: str, kind: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=mid,
        mediaType=kind,
        sizeBytes=10,
        recordedAt=NOW,
        createdAt=NOW,
        transcriptStatus=None,
        transcriptText=None,
    )


def _snapshot(**extra: Any) -> dict[str, Any]:
    media = [_media("a", "AUDIO"), _media("b", "IMAGE")]
    return share_snapshot.assemble(
        title="Bagru",
        crumbs=["Repository", "Bagru"],
        generated_by="Kamla",
        generated_at=NOW,
        data_version="7",
        media=media,
        records={"artisans": [SimpleNamespace(id="x")], "media": media},
        flags={},
        withheld=set(),
        sections=[{"key": "A", "label": "Life", "questions": [("q1", "One")]}],
        answered_by_unit=[{"q1"}],
        truncated=False,
        **extra,
    )


def test_a_hindi_share_link_carries_hindi_labels():
    snap = _snapshot(locale="hi")
    assert snap["locale"] == "hi"
    labels = {k["key"]: k["label"] for k in snap["kpis"]}
    assert labels["files"] == "फ़ाइलें"
    assert all(DEVANAGARI.search(label) for label in labels.values())
    charts = {c["key"]: c for c in snap["charts"]}
    assert DEVANAGARI.search(charts["mediaByType"]["title"])
    assert [s["key"] for s in charts["completionBySection"]["series"]] == [
        "complete",
        "partial",
        "missing",
    ]
    assert charts["completionBySection"]["series"][0]["name"] == "पूर्ण"
    row = SimpleNamespace(snapshot=json.loads(json.dumps(snap)), expiresAt=NOW, createdAt=NOW)
    out = share_links.public_out(row)
    assert out["locale"] == "hi"
    assert out["labels"]["sharedBy"] == "द्वारा साझा"
    assert {name for name, _ in share_links.LABELS} == set(out["labels"])


def test_a_share_link_is_english_by_default():
    snap = _snapshot()
    assert snap["locale"] == "en"
    assert {k["key"]: k["label"] for k in snap["kpis"]}["files"] == "Files"
    out = share_links.public_out(
        SimpleNamespace(snapshot={"title": "T"}, expiresAt=NOW, createdAt=NOW)
    )
    assert out["locale"] == "en"
    assert out["labels"]["sharedBy"] == "Shared by"
    assert out["labels"]["eyebrow"] == "{product} · Live report"


def test_every_share_label_has_hindi():
    hindi = share_links.labels("hi")
    assert [name for name, english in share_links.LABELS if hindi[name] == english] == []


def test_devanagari_is_shaped_when_harfbuzz_is_installed():
    hb = (
        importlib.import_module("uharfbuzz")
        if os.environ.get("CI")
        else pytest.importorskip("uharfbuzz")
    )
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import ShapedStr, shapeStr

    assert pdf_summary.shaping()
    face = pdf_summary.fonts().scripts["Devanagari"][0]
    font = pdfmetrics.getFont(face)
    assert font.shapable
    shaper = hb.Font(font.hbFace)
    run = hb.Buffer()
    run.add_str("कि")
    run.guess_segment_properties()
    hb.shape(shaper, run, {})
    names = [shaper.glyph_to_string(info.codepoint) for info in run.glyph_infos]
    assert names[0].startswith("uni093F") and names[-1] == "uni0915"
    assert isinstance(shapeStr("कि", face, 10), ShapedStr)


def _prepared(locale: str) -> pack.Prepared:
    return pack.Prepared(
        zip_name="Pack.zip",
        xlsx_name="Report.xlsx",
        xlsx=b"",
        pdf_name="Summary.pdf",
        pdf=b"",
        folder="Bagru",
        path="by-workshop/w1",
        generated_by="Kamla",
        generated_at=NOW,
        data_version="7",
        transcripts=[],
        withheld=2,
        capped=True,
        files=3,
        bytes=2048,
        quotes=4,
        coverage=True,
        locale=locale,
    )


def test_the_pack_readme_is_written_in_the_report_language():
    written = [("Report.xlsx", 10), ("Transcripts/a.docx", 5)]
    hindi = pack.readme(_prepared("hi"), written, ["gone.m4a"])
    for words in ("रिपोर्ट पैक", "पैक में क्या है", "टिप्पणियाँ", "सभी फ़ाइलें", "सहमति", "नवंबर"):
        assert words in hindi, words
    assert "Report.xlsx" in hindi and "gone.m4a" in hindi
    assert "What is inside" not in hindi and "consent" not in hindi
    english = pack.readme(_prepared("en"), written, ["gone.m4a"])
    assert "What is inside" in english and "consent" in english and "gone.m4a" in english
    assert not DEVANAGARI.search(english)


def test_a_hindi_live_link_url_carries_its_language():
    base = "https://dw.example.org/"
    assert (
        share_links.share_url(base, "tok", "hi")
        == "https://dw.example.org/report-share/tok?lang=hi"
    )
    assert share_links.share_url(base, "tok") == "https://dw.example.org/report-share/tok"
    assert share_links.share_url(base, "tok", "fr") == "https://dw.example.org/report-share/tok"
    hindi = share_links.labels("hi")
    assert DEVANAGARI.search(hindi["expired"]) and DEVANAGARI.search(hindi["opening"])


def _harfbuzz() -> Any:
    return (
        importlib.import_module("uharfbuzz")
        if os.environ.get("CI")
        else pytest.importorskip("uharfbuzz")
    )


def _real_summary(locale: str) -> pdf_summary.Summary:
    summary = _summary(locale)
    summary.quotes = [
        pdf_summary.Quote("हम नील से रंगते हैं।", "We dye with indigo.", "Kamla", "talk.m4a"),
        pdf_summary.Quote(
            "यह हमारे दादा से आया।", "It came from our grandfather.", None, "Kamla · talk.m4a"
        ),
        pdf_summary.Quote("Plain words", "", None, "clip.mp4"),
    ]
    summary.folder = "बगरू (Bagru) · ब्लॉक प्रिंटिंग"
    summary.generated_by = "कमला देवी"
    summary.coverage[0].rows.append(
        pdf_summary.CoverageRow(
            "सीता: “दाबू”", "दाबू/Dabu", 3, 40, 2, "1 of 2 ready", ["missing", "complete"]
        )
    )
    summary.sections = [pdf_summary.SectionBar("पहचान (Identity)", 1, 1, 1)]
    return summary


@pytest.mark.parametrize("locale", ["hi", "en"])
def test_a_full_pack_summary_builds_shaped_with_harfbuzz(locale, caplog):
    _harfbuzz()
    assert pdf_summary.shaping()
    with caplog.at_level("WARNING", logger=pdf_summary.logger.name):
        data = pdf_summary.build(_real_summary(locale))
    assert data.startswith(b"%PDF") and b"NotoSansDevanagari" in data
    assert [
        r.message
        for r in caplog.records
        if "shaping failed" in r.getMessage() or "unshaped" in r.getMessage()
    ] == []
    text = _text(data)
    assert "We dye with indigo." in text
    assert ("रिपोर्ट सारांश" in text) == (locale == "hi")


def test_words_never_mix_fonts_so_harfbuzz_can_shape_them():
    for script, piece in pdf_summary.runs("“हम नील से रंगते हैं।” Kamla · talk.m4a"):
        if script is None:
            assert not DEVANAGARI.search(piece)
        else:
            assert piece.startswith("“") and "”" in piece


def test_a_shaping_failure_falls_back_to_unshaped_and_never_breaks_the_pdf(monkeypatch, caplog):
    _harfbuzz()
    from reportlab.pdfgen import canvas
    from reportlab.platypus import paragraph

    def broken(*args: Any, **kwargs: Any) -> Any:
        raise AttributeError("'TTFont' object has no attribute 'hbAddPrivate'")

    monkeypatch.setattr(paragraph, "shapeFragWord", broken)
    real = canvas.Canvas.drawString

    def draw(
        self: Any, x: float, y: float, text: str, *args: Any, shaping: bool = False, **kwargs: Any
    ) -> Any:
        if shaping:
            raise KeyError(0)
        return real(self, x, y, text, *args, **kwargs)

    monkeypatch.setattr(canvas.Canvas, "drawString", draw)
    with caplog.at_level("WARNING", logger=pdf_summary.logger.name):
        data = pdf_summary.build(_real_summary("hi"))
    assert data.startswith(b"%PDF") and b"NotoSansDevanagari" in data
    assert any("unshaped" in r.getMessage() for r in caplog.records)
    assert DEVANAGARI.search(_text(data))
    assert pdf_summary.shaping()
