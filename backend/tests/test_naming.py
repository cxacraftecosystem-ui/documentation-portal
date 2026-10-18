"""Every generated name follows one convention (owner correction (a), 18 Oct 2026).

The golden rows live in ``shared/session-title.json`` and the web (``lib/naming.ts``) and Android
(``data/Naming.kt``) helpers are held to the same rows, so this file is one of three that must agree.
DESIGN tests 35a and 35b; NAMING section 3 rows for this portal.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from app.services import naming
from app.services.s3 import make_object_key, safe_filename

GOLDEN = json.loads((Path(__file__).resolve().parents[2] / "shared" / "session-title.json").read_text(encoding="utf-8"))
ROWS = GOLDEN["golden"]
KEY_RE = re.compile(r"^media/[^/]+/[0-9a-f]{32}/[A-Za-z0-9._-]{1,150}$")


def _rows(kind: str) -> list[dict]:
    return [row for row in ROWS if row["kind"] == kind]


def test_the_golden_table_holds_the_design_rows() -> None:
    titles = {row["title"] for row in _rows("title")}
    for expected in (
        "Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa",
        "Group-Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_6-Artisans",
        "Group-Interview_2026-07-07_Kolkata_Terracotta_2-Artisans",
        "Interview_2026-10-18_Giriraj-Prasad-Chhipa",
        "Group-Interview_2026-09-16_Bankura_3-Artisans",
        "Interview_2026-09-23_Bankura_Dokra",
        "Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_गिरिराज-प्रसाद",
        "Interview_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa",
        "Workshop_2026-06-18_Bagru-Jaipur_Hand-Block-Printing_DW-RJ-0012",
    ):
        assert expected in titles
    files = {row["fileName"] for row in _rows("recording")}
    for expected in (
        "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a",
        "FieldRepository_Interview_2026-07-01_1902_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03-T2.m4a",
        "FieldRepository_Group-Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_6-Artisans_Recording_B-All.m4a",
        "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording.m4a",
        "FieldRepository_Interview_2026-06-24_Kolkata_Terracotta_Giriraj-Das_Recording_F-Q07.m4a",
        "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Transcript_D-Q03.md",
    ):
        assert expected in files


@pytest.mark.parametrize("row", _rows("title"), ids=lambda r: r["case"])
def test_titles_match_the_golden_table(row: dict) -> None:
    title = naming.session_title(row["input"])
    assert title == row["title"]
    assert len(title) <= naming.TITLE_MAX
    assert "·" not in title and " " not in title and "Unknown" not in title


@pytest.mark.parametrize("row", _rows("recording"), ids=lambda r: r["case"])
def test_recording_names_match_the_golden_table(row: dict) -> None:
    r = row["input"]
    name = naming.recording_filename(
        r["session"], captured_at=r["capturedAt"], captured_at_source=r["capturedAtSource"], targets=r["targets"],
        ext=r["ext"], what=r["what"], part=r["part"], position=r["position"], roster_count=r["rosterCount"],
    )
    assert name == row["fileName"]
    assert name.isascii() and len(name) <= naming.FILE_MAX
    assert re.fullmatch(r"[A-Za-z0-9._-]+", name)


@pytest.mark.parametrize("row", _rows("transliteration"), ids=lambda r: r["case"])
def test_transliteration_rows(row: dict) -> None:
    assert "-".join(naming.field_words(row["input"], ascii_only=True)) == row["ascii"]


@pytest.mark.parametrize("row", _rows("export"), ids=lambda r: r["case"])
def test_export_names(row: dict) -> None:
    assert naming.export_filename(**row["input"]) == row["fileName"]


def test_collisions_get_a_stable_suffix() -> None:
    (row,) = _rows("collision")
    assert naming.dedupe_names(row["input"]) == row["names"]
    assert naming.dedupe_names(row["input"]) == naming.dedupe_names(list(row["input"]))


def test_an_estimated_time_is_left_out_and_never_invented() -> None:
    session = {"sessionDate": "2026-07-01", "participants": [{"name": "Meena Pal"}]}
    for source in ("SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW"):
        name = naming.recording_filename(session, captured_at="2026-07-01T06:30:00Z", captured_at_source=source)
        assert name == "FieldRepository_Interview_2026-07-01_Meena-Pal_Recording.m4a"
    assert "_1200_" in naming.recording_filename(session, captured_at="2026-07-01T06:30:00Z", captured_at_source="DEVICE")


def test_unknown_fields_are_left_out() -> None:
    assert naming.session_title({}) == "Interview"
    assert naming.recording_filename({}, captured_at=None, captured_at_source=None) == "FieldRepository_Interview_Recording.m4a"


def test_150_character_file_limit_cuts_subject_then_craft_then_place_but_never_the_tail() -> None:
    session = {
        "sessionDate": "2026-07-01", "place": "Mahaveer Colony Ward Twelve Bagru Jaipur Rajasthan",
        "workshop": {"craft": "Hand Block Printing And Natural Dyeing With Indigo"},
        "participants": [{"name": "Giriraj Prasad Chhipa Son Of Ramnarayan Chhipa Of Mahaveer Colony"}],
    }
    name = naming.recording_filename(
        session, captured_at="2026-07-01T12:54:00Z", captured_at_source="DEVICE",
        targets=[{"sectionCode": "D", "displayCode": "D3"}], position=2, roster_count=1,
    )
    assert len(name) <= 150
    assert name.startswith("FieldRepository_Interview_2026-07-01_1824_")
    assert name.endswith("_Recording_D-Q03-T2.m4a")


def test_windows_reserved_stems_and_empty_names_are_guarded() -> None:
    assert naming.ascii_leaf("CON.txt") == "CON-File.txt"
    assert naming.ascii_leaf("nul") == "nul-File"
    assert naming.ascii_leaf("") == "upload.bin"
    assert naming.ascii_leaf("...") == "upload.bin"


def test_a_non_ascii_leaf_is_impossible() -> None:
    for raw in ("गिरिराज.jpg", "মীনা দাস.m4a", "a/b\\c:d*e?.wav", "‮reversed.exe", "x" * 400 + ".jpg"):
        leaf = safe_filename(raw)
        assert leaf.isascii() and re.fullmatch(r"[A-Za-z0-9._-]{1,150}", leaf), leaf
        assert KEY_RE.match(make_object_key("user1", raw))
    assert safe_filename("गिरिराज.jpg") == "giriraj.jpg"


def test_content_disposition_carries_ascii_and_utf8_names() -> None:
    header = naming.content_disposition("गिरिराज.m4a")
    assert header.startswith('attachment; filename="giriraj.m4a"; filename*=UTF-8\'\'')
    assert "%E0%A4%97" in header
