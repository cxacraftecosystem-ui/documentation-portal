"""THE NAMING CONVENTION (owner correction, 18 Oct 2026; docs/conventions/NAMING_2026-10-18.md).

``app/services/naming.py`` is held here to ``shared/naming-golden.json``, the table
``frontend/lib/naming.ts`` (``e2e/naming-unit.spec.ts``) and ``data/Naming.kt`` (``NamingTest``)
answer to as well. When the sibling checkout is present, the table and the three implementations
must be the same bytes in both portals. No database.
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

ROOT = pathlib.Path(__file__).resolve().parents[2]
GOLDEN_FILE = ROOT / "shared" / "naming-golden.json"
LEAF_KEY = re.compile(r"^media/[^/]+/[0-9a-f]{32}/[A-Za-z0-9._-]{1,150}$")


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
def test_titles_match_the_golden_table(row):
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
