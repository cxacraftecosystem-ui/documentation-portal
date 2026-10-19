"""THE NAMING CONVENTION (owner correction, 18 Oct 2026; docs/conventions/NAMING_2026-10-18.md).

``app/services/naming.py`` is held here to two tables that ``frontend/lib/naming.ts``
(``e2e/naming-unit.spec.ts``) and ``data/Naming.kt`` (``NamingTest``) answer to as well:
``shared/session-title.json`` (the frozen questionnaire contract: titles, recording names,
transliteration, exports, collisions; DESIGN tests 35a and 35b) and ``shared/naming-golden.json``
(every other generated name). When the sibling checkout is present, the tables and the three
implementations must be the same bytes in both portals. No database.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
from datetime import UTC, date, datetime

import pytest

from app.services import naming
from app.services.naming_portal import PORTAL
from app.services.s3 import make_object_key, safe_filename

ROOT = pathlib.Path(__file__).resolve().parents[2]
GOLDEN_FILE = ROOT / "shared" / "naming-golden.json"
CONTRACT = json.loads((ROOT / "shared" / "session-title.json").read_text(encoding="utf-8"))
ROWS = CONTRACT["golden"]
LEAF_KEY = KEY_RE = re.compile(r"^media/[^/]+/[0-9a-f]{32}/[A-Za-z0-9._-]{1,150}$")


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The questionnaire contract (shared/session-title.json)
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _here(name: str) -> str:
    """The contract's rows are written for documentation-portal; designer-portal's word replaces it."""
    return name.replace("FieldRepository_", f"{PORTAL}_", 1)


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
    assert name == _here(row["fileName"])
    assert name.isascii() and len(name) <= naming.FILE_MAX
    assert re.fullmatch(r"[A-Za-z0-9._-]+", name)


@pytest.mark.parametrize("row", _rows("transliteration"), ids=lambda r: r["case"])
def test_transliteration_rows(row: dict) -> None:
    assert "-".join(naming.field_words(row["input"], ascii_only=True)) == row["ascii"]


@pytest.mark.parametrize("row", _rows("export"), ids=lambda r: r["case"])
def test_export_names(row: dict) -> None:
    assert naming.export_filename(**row["input"]) == _here(row["fileName"])


def test_collisions_get_a_stable_suffix() -> None:
    (row,) = _rows("collision")
    assert naming.dedupe_names(row["input"]) == row["names"]
    assert naming.dedupe_names(row["input"]) == naming.dedupe_names(list(row["input"]))


def test_an_estimated_time_is_left_out_and_never_invented() -> None:
    session = {"sessionDate": "2026-07-01", "participants": [{"name": "Meena Pal"}]}
    for source in ("SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW"):
        name = naming.recording_filename(session, captured_at="2026-07-01T06:30:00Z", captured_at_source=source)
        assert name == f"{PORTAL}_Interview_2026-07-01_Meena-Pal_Recording.m4a"
    assert "_1200_" in naming.recording_filename(session, captured_at="2026-07-01T06:30:00Z", captured_at_source="DEVICE")


def test_unknown_fields_are_left_out() -> None:
    assert naming.session_title({}) == "Interview"
    assert naming.recording_filename({}, captured_at=None, captured_at_source=None) == f"{PORTAL}_Interview_Recording.m4a"


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
    assert name.startswith(f"{PORTAL}_Interview_2026-07-01_1824_")
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


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Every other generated name (shared/naming-golden.json)
# ──────────────────────────────────────────────────────────────────────────────────────────────


def golden() -> dict:
    return json.loads(GOLDEN_FILE.read_text(encoding="utf-8"))


def test_the_table_is_present_and_not_empty():
    g = golden()
    assert g["version"] == 1
    assert len(g["fileNames"]) > 30
    assert len(g["titles"]) > 8
    assert g["limits"] == {"fileName": 150, "title": 120, "subjectFloor": 40}


@pytest.mark.parametrize("row", golden()["fileNames"], ids=lambda r: r["name"][:60])
def test_file_names_match_the_golden_table(row):
    assert naming.file_name(**row["input"]) == row["name"]
    assert len(row["name"]) <= 150
    assert row["name"].isascii() and " " not in row["name"]


@pytest.mark.parametrize("row", golden()["titles"], ids=lambda r: r["title"][:60])
def test_plain_field_titles_match_the_golden_table(row):
    assert naming.session_title(row["input"]) == row["title"]
    assert len(row["title"]) <= 120


def test_fields_places_sequences_leaves_and_collisions_match_the_golden_table():
    g = golden()
    for row in g["transliteration"] + g["fields"]:
        assert naming.field(row["input"]) == row["field"], row
    for row in g["places"]:
        assert naming.place_field(**row["input"]) == row["place"], row
    for row in g["sequences"]:
        i = row["input"]
        got = (
            naming.question_sequence(i["section"], i["question"], take=i.get("take"), part=i.get("part"))
            if "question" in i
            else naming.section_sequence(i["section"])
        )
        assert got == row["sequence"], row
    for row in g["asciiLeaves"]:
        assert naming.ascii_leaf(row["input"]) == row["leaf"], row
    for row in g["dedupe"]:
        assert naming.dedupe_names(row["input"]) == row["names"], row


@pytest.mark.parametrize("row", golden()["recordingSequences"], ids=lambda r: json.dumps(r["input"])[:60])
def test_recording_sequences_read_the_question_after_its_own_section_code(row):
    # "F21" in section F2 is question 1: the section comes from the target, never from the display code.
    i = row["input"]
    got = naming.recording_sequence(
        i["targets"], part=i.get("part"), position=i.get("position"), roster_count=i.get("rosterCount")
    )
    assert got == row["sequence"]


def test_workshop_file_names_match_the_golden_table():
    for row in golden()["workshops"]:
        i = row["input"]
        got = naming.workshop_filename(
            i["workshop"], i["what"], i["ext"], at=i["at"], portal="DesignWorkshop"
        )
        assert got == row["name"], row


def test_the_portal_word_is_this_repositorys():
    if not (ROOT / "android").is_dir():
        pytest.skip("the repository root is not checked out beside the backend")
    android = any(ROOT.glob("android/app/src/main/java/com/designprototype"))
    assert ("DesignWorkshop" if android else "FieldRepository") == PORTAL
    assert naming.export_filename("Product", "Export", "csv", at=date(2026, 10, 18)) == (
        f"{PORTAL}_Product_2026-10-18_Export.csv"
    )


def test_exports_are_dated_in_ist_and_templates_are_not():
    late_utc = datetime(2026, 10, 18, 20, 0, tzinfo=UTC)  # 01:30 on the 19th in IST
    assert "_2026-10-19_" in naming.export_filename("Dataset", "Export", "zip", at=late_utc)
    assert naming.export_filename("Annual-Plan", "Pro-Forma", "xlsx", dated=False) == (
        f"{PORTAL}_Annual-Plan_Pro-Forma.xlsx"
    )
    assert "_2026-10-19_0130_" in naming.export_filename(
        "Dataset", "Export", "zip", at=late_utc, with_time=True
    )


def test_an_estimated_capture_time_is_left_out():
    at = datetime(2026, 7, 1, 12, 54, tzinfo=UTC)
    known = naming.recording_filename("Interview", "m4a", captured_at=at, subject="Giriraj")
    estimated = naming.recording_filename(
        "Interview", "m4a", captured_at=at, time_known=False, subject="Giriraj"
    )
    assert "_2026-07-01_1824_" in known
    assert "_1824" not in estimated and "_2026-07-01_" in estimated


def test_a_new_s3_key_is_ascii_whatever_the_client_named_the_file():
    from app.services import s3

    for raw in ("गिरिराज प्रसाद.m4a", "a/b\\c.JPG", "  ", "NUL", "x" * 400, "IMG 0001 (1).jpg"):
        key = s3.make_object_key("cm1x2y3z", raw)
        assert LEAF_KEY.match(key), key


def test_content_disposition_carries_an_ascii_name_and_the_utf8_one():
    header = naming.content_disposition("गिरिराज.m4a")
    assert header.startswith('attachment; filename="giriraj.m4a"; ')
    assert "filename*=UTF-8''%E0%A4%97" in header
    assert naming.content_disposition("a.pdf", inline=True).startswith("inline; ")


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The two repositories
# ──────────────────────────────────────────────────────────────────────────────────────────────

#: The files that must be the same bytes in both repositories (line endings aside).
SHARED_FILES = (
    "shared/naming-golden.json",
    "shared/session-title.json",
    "backend/app/services/naming.py",
    "backend/tests/test_naming.py",
    "frontend/lib/naming.ts",
    "frontend/e2e/naming-unit.spec.ts",
    ".claude/skills/naming/SKILL.md",
)


def _sibling() -> pathlib.Path | None:
    other = (
        "documentation-portal"
        if any(ROOT.glob("android/app/src/main/java/com/designprototype"))
        else "designer-portal"
    )
    candidates = [
        os.environ.get("SIBLING_REPO"),
        ROOT.parent / other,
        pathlib.Path("C:/dev/IIT") / other,
    ]
    for candidate in candidates:
        if candidate and (pathlib.Path(candidate) / GOLDEN_FILE.relative_to(ROOT)).is_file():
            return pathlib.Path(candidate)
    return None


def _text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def test_the_sibling_portal_carries_the_same_table_and_implementation():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the naming table (CI has one repository)")
    for rel in SHARED_FILES:
        theirs = sibling / rel
        assert theirs.is_file(), f"{sibling} lacks {rel}"
        assert _text(ROOT / rel) == _text(theirs), f"{rel} differs from {theirs}"
    for pattern in (
        "android/app/src/main/java/com/*/*/data/Naming.kt",
        "android/app/src/test/java/com/*/*/data/NamingTest.kt",
    ):
        mine, theirs = sorted(ROOT.glob(pattern)), sorted(sibling.glob(pattern))
        assert len(mine) == 1 and len(theirs) == 1, f"{pattern}: {mine} / {theirs}"
        assert _text(mine[0]).split("\n", 1)[1] == _text(theirs[0]).split("\n", 1)[1], (
            f"{pattern} differs beyond its package line"
        )
