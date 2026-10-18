"""STANDARD VALUES (convergence point 10): one list per vocabulary, both portals, three clients.

This file is identical in the designer portal and the documentation portal. It holds:

* the shared list to itself: codes unique, no input spelling claimed by two entries;
* the server to ``shared/standard-values-vectors.json``, the table the web and Android ports are
  tested against too;
* the write-path helpers: a stored legacy value re-sent unchanged is not refused;
* the write paths to the helpers, read from source;
* the Prisma ``TraditionType`` enum to the shared tradition codes;
* the two repositories to each other when the sibling checkout is present, and to a pinned digest
  when it is not (CI has one repository).

No database.
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
import sys
from types import SimpleNamespace
from typing import Any, Self

import pytest

from app.services import standard_values as sv

BACKEND = pathlib.Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
VECTORS = ROOT / "shared" / "standard-values-vectors.json"
IS_DESIGNER_PORTAL = any(ROOT.glob("android/app/src/main/java/com/designprototype"))

#: sha256 of the two shared files with LF line endings. Changing either file means changing this
#: constant, and this test file is itself shared, so the other portal has to take the same change.
PINNED = {
    "backend/app/data/standard_values.json": "4776fcc1fe70c47e748987a763f39013f2def133543942ffc6cff7ebd49fe0ab",
    "shared/standard-values-vectors.json": "f71e585c317cf5837ec171ceaa6caf5e165c195639e591f1757dbefe0baa850c",
}

SHARED_FILES = (
    "backend/app/data/standard_values.json",
    "backend/app/services/standard_values.py",
    "backend/tests/test_standard_values.py",
    "backend/scripts/standardise_values.py",
    "shared/standard-values-vectors.json",
    "frontend/lib/standard-values.json",
    "frontend/lib/standardValues.ts",
    "frontend/e2e/standard-values-parity-unit.spec.ts",
)


def _lf(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def vectors() -> dict[str, Any]:
    return json.loads(VECTORS.read_text(encoding="utf-8"))


# ── The list ─────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("list_key", ["languages", "genders", "traditionTypes", "materialFamilies"])
def test_codes_are_unique_and_no_spelling_means_two_entries(list_key: str) -> None:
    entries = sv.shared()[list_key]
    codes = [entry["code"] for entry in entries]
    assert len(codes) == len(set(codes))
    owner: dict[str, str] = {}
    for entry in entries:
        label = entry.get("label") or entry.get("name")
        for spelling in (entry["code"], label, *entry["aliases"]):
            folded = sv.fold(spelling)
            assert folded, (list_key, spelling)
            assert owner.setdefault(folded, entry["code"]) == entry["code"], (list_key, spelling)


def test_bengali_is_the_standard_spelling_and_codes_are_standard() -> None:
    names = {entry["code"]: entry["name"] for entry in sv.shared()["languages"]}
    assert names["bn"] == "Bengali"
    # ISO 639-1 where the language has one, otherwise ISO 639-2/3; never a made-up code.
    assert all(re.fullmatch(r"[a-z]{2,3}", code) for code in names)


def test_the_tradition_codes_are_the_prisma_enum() -> None:
    schema = (BACKEND / "prisma" / "schema.prisma").read_text(encoding="utf-8")
    block = re.search(r"enum TraditionType \{([^}]*)\}", schema)
    assert block
    assert block.group(1).split() == sv.tradition_codes()


def test_the_web_copy_is_the_same_bytes() -> None:
    web = ROOT / "frontend" / "lib" / "standard-values.json"
    assert _lf(web) == _lf(sv.STANDARD_VALUES_FILE)


# ── The vectors ──────────────────────────────────────────────────────────────────────────────


def test_phone_vectors() -> None:
    rows = vectors()["phone"]
    assert len(rows) >= 25
    for row in rows:
        if "error" in row:
            with pytest.raises(sv.StandardValueError) as caught:
                sv.normalize_phone(row["countryCode"], row["number"])
            assert caught.value.message == row["error"], row
            continue
        assert sv.normalize_phone(row["countryCode"], row["number"]) == (
            row["code"],
            row["digits"],
        ), row
        assert sv.format_phone(row["code"], row["digits"]) == row["display"], row


@pytest.mark.parametrize(
    "section, fn",
    [
        ("language", sv.normalize_language),
        ("gender", sv.normalize_gender),
        ("traditionType", sv.normalize_tradition),
        ("materialFamily", sv.normalize_material_family),
    ],
)
def test_list_vectors(section: str, fn: Any) -> None:
    rows = vectors()[section]
    assert rows
    for row in rows:
        if "error" in row:
            with pytest.raises(sv.StandardValueError) as caught:
                fn(row["input"])
            assert caught.value.message == row["error"], row
        else:
            assert fn(row["input"]) == row["value"], row


def test_text_and_name_vectors() -> None:
    for row in vectors()["materialFamilyFromText"]:
        assert sv.material_family_from_text(row["input"]) == row["value"], row
    for row in vectors()["languageName"]:
        assert sv.language_name(row["input"]) == row["value"], row


def test_the_display_shape() -> None:
    assert sv.format_phone("+91", "9876543210") == "+91 98765 43210"
    assert sv.compose_phone(None, "+91 98765 43210") == "+91 9876543210"
    assert sv.phone_of({"phone": "9876543210", "phoneCountryCode": "+91"}) == "+91 98765 43210"
    assert (
        sv.phone_of(SimpleNamespace(phone="9876543210", phoneCountryCode=None)) == "+91 98765 43210"
    )
    assert sv.phone_of({"phone": "not a number"}) == "not a number"


# ── The write-path helpers ───────────────────────────────────────────────────────────────────


def test_apply_phone_splits_an_old_build_value_and_a_new_build_pair() -> None:
    assert sv.apply_phone({"phone": "+91 98765 43210"}) == {
        "phone": "9876543210",
        "phoneCountryCode": "+91",
    }
    assert sv.apply_phone({"phone": "09876543210", "phoneCountryCode": "+91"}) == {
        "phone": "9876543210",
        "phoneCountryCode": "+91",
    }
    assert sv.apply_phone({"phone": "2071234567", "phoneCountryCode": "44"}) == {
        "phone": "2071234567",
        "phoneCountryCode": "+44",
    }


def test_apply_phone_refuses_a_new_bad_number() -> None:
    with pytest.raises(sv.StandardValueError) as caught:
        sv.apply_phone(
            {"phone": "12345"}, stored=SimpleNamespace(phone=None, phoneCountryCode=None)
        )
    assert caught.value.field == "phone"
    assert caught.value.message == sv.PHONE_MESSAGE_INDIA


def test_apply_phone_leaves_a_stored_legacy_value_alone() -> None:
    stored = SimpleNamespace(phone="ask the sarpanch", phoneCountryCode=None)
    assert sv.apply_phone({"phone": "ask the sarpanch", "name": "x"}, stored=stored) == {
        "name": "x"
    }
    legacy = SimpleNamespace(phone="+91 98765", phoneCountryCode=None)
    # The new clients split the stored value into a code and its digits before re-sending it.
    assert sv.apply_phone({"phone": "98765", "phoneCountryCode": "+91"}, stored=legacy) == {}


def test_apply_phone_clears_both_columns_together() -> None:
    stored = SimpleNamespace(phone="9876543210", phoneCountryCode="+91")
    assert sv.apply_phone({"phone": None}, stored=stored) == {
        "phone": None,
        "phoneCountryCode": None,
    }
    assert sv.apply_phone({"phone": ""}, stored=stored) == {"phone": None, "phoneCountryCode": None}
    # The code alone, cleared: the number then reads as the default, +91.
    assert sv.apply_phone({"phoneCountryCode": None}, stored=stored) == {"phoneCountryCode": None}


def test_apply_phone_takes_a_code_change_alone() -> None:
    stored = SimpleNamespace(phone="2071234567", phoneCountryCode="+91")
    assert sv.apply_phone({"phoneCountryCode": "+44"}, stored=stored) == {
        "phone": "2071234567",
        "phoneCountryCode": "+44",
    }


def test_apply_gender_tool_and_interview_values() -> None:
    assert sv.apply_artisan_values({"gender": "female"}) == {"gender": "Female"}
    assert (
        sv.apply_artisan_values({"gender": "Unknown"}, stored=SimpleNamespace(gender="Unknown"))
        == {}
    )
    with pytest.raises(sv.StandardValueError):
        sv.apply_artisan_values({"gender": "Unknown"}, stored=SimpleNamespace(gender=None))
    assert sv.apply_tool_values({"traditionType": "CONTEMPORARY", "materialFamily": "metal"}) == {
        "traditionType": "MODERN",
        "materialFamily": "METAL",
    }
    assert sv.apply_tool_values({"traditionType": ""}) == {"traditionType": "UNKNOWN"}
    assert sv.apply_interview_values({"language": "Bangla"}) == {"language": "bn"}
    kutchi = SimpleNamespace(language="Kutchi")
    assert sv.apply_interview_values({"language": "Kutchi"}, stored=kutchi) == {}


def test_catalogue_serves_the_lists_without_aliases() -> None:
    served = sv.catalogue()
    assert [entry["code"] for entry in served["languages"]] == sv.language_codes()
    assert "aliases" not in json.dumps(served)


# ── The write paths, read from source ────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "rel, helper",
    [
        ("app/api/routes/artisans.py", "apply_artisan_values"),
        ("app/api/routes/tools.py", "apply_tool_values"),
        ("app/api/routes/questionnaire.py", "apply_interview_values"),
    ],
)
def test_every_write_route_applies_the_standard_values(rel: str, helper: str) -> None:
    source = (BACKEND / rel).read_text(encoding="utf-8")
    # Once on create and once on update.
    assert source.count(f"_standardise({helper}, data") >= 2, rel


def test_records_print_the_phone_with_its_code() -> None:
    source = (BACKEND / "app" / "services" / "record_fields.py").read_text(encoding="utf-8")
    assert "lambda a: a.phone)" not in source
    assert '_f("Phone", phone_of)' in source


# ── The two repositories ─────────────────────────────────────────────────────────────────────


def test_the_shared_files_match_their_pinned_digest() -> None:
    for rel, digest in PINNED.items():
        actual = hashlib.sha256(_lf(ROOT / rel).encode("utf-8")).hexdigest()
        assert actual == digest, f"{rel} changed: update it in BOTH portals, then this pin"


def _sibling() -> pathlib.Path | None:
    other = "documentation-portal" if IS_DESIGNER_PORTAL else "designer-portal"
    worktree_twin = (
        ROOT.name.replace("dp-", "doc-", 1)
        if IS_DESIGNER_PORTAL
        else ROOT.name.replace("doc-", "dp-", 1)
    )
    candidates = [
        os.environ.get("SIBLING_REPO"),
        ROOT.parent / worktree_twin,
        ROOT.parent / other,
        pathlib.Path("C:/dev/IIT") / other,
    ]
    for candidate in candidates:
        if candidate and (pathlib.Path(candidate) / SHARED_FILES[0]).is_file():
            return pathlib.Path(candidate)
    return None


def test_the_sibling_portal_carries_the_same_files() -> None:
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the standard values (CI has one repository)")
    for rel in SHARED_FILES:
        assert _lf(ROOT / rel) == _lf(sibling / rel), f"{rel} differs from {sibling / rel}"


# ── The normalisation script, against a fake client ──────────────────────────────────────────


def _script() -> Any:
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "standardise_values", BACKEND / "scripts" / "standardise_values.py"
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses resolve their module by name
    spec.loader.exec_module(module)
    return module


class _Delegate:
    def __init__(self, rows: list[dict[str, Any]]) -> None:
        self.rows = rows

    async def find_many(
        self, where: dict[str, Any], order: Any = None, take: int = 500
    ) -> list[Any]:
        rows = [r for r in self.rows if all(r.get(k) == v for k, v in where.items() if k != "id")]
        after = (where.get("id") or {}).get("gt")
        rows = sorted((r for r in rows if after is None or r["id"] > after), key=lambda r: r["id"])
        return [SimpleNamespace(**r) for r in rows[:take]]


class _Tx:
    def __init__(self, log: list[tuple[Any, ...]]) -> None:
        self.log = log

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(self, *_: object) -> None:
        return None

    async def execute_raw(self, query: str, *args: Any) -> int:
        self.log.append((query, *args))
        return 1


class _Client:
    def __init__(self, designer: bool) -> None:
        self.writes: list[tuple[Any, ...]] = []
        self.artisan = _Delegate(
            [
                {
                    "id": "a1",
                    "phone": "+91 98765 43210",
                    "phoneCountryCode": None,
                    "gender": "female",
                },
                {"id": "a2", "phone": "9876543210", "phoneCountryCode": "+91", "gender": "Male"},
                {
                    "id": "a3",
                    "phone": "ask the sarpanch",
                    "phoneCountryCode": None,
                    "gender": "Unknown",
                },
                {"id": "a4", "phone": None, "phoneCountryCode": None, "gender": None},
            ]
        )
        self.questionnaireinterview = _Delegate(
            [
                {"id": "i1", "language": "Bangla"},
                {"id": "i2", "language": "bn"},
                {"id": "i3", "language": "Kutchi"},
            ]
        )
        self.tooldocumentation = _Delegate(
            [
                {
                    "id": "t1",
                    "material": "Iron",
                    "materialFamily": None,
                    "traditionType": "UNKNOWN",
                },
                {
                    "id": "t2",
                    "material": "mango wood with an iron collar",
                    "materialFamily": None,
                    "traditionType": "MODERN",
                },
            ]
        )
        self.craft = _Delegate(
            [
                {"id": "c1", "name": "Bandhej work", "craftCode": None},
                {"id": "c2", "name": "Zzzq", "craftCode": None},
            ]
        )
        if designer:
            self.designerprofile = _Delegate(
                [{"id": "d1", "phone": "09876543210", "phoneCountryCode": None}]
            )

    def tx(self, **_: Any) -> _Tx:
        return _Tx(self.writes)


def test_the_script_is_dry_by_default_and_reports_every_unmappable_value() -> None:
    import asyncio

    script = _script()
    client = _Client(designer=IS_DESIGNER_PORTAL)
    report = asyncio.run(script.run(client, apply=False))
    assert client.writes == []
    by = {(f.table, f.column): f for f in report.fields}
    phone = by[("Artisan", "phone")]
    assert [c[0] for c in phone.changes] == ["a1"] and phone.standard == 1 and phone.blank == 1
    assert phone.unmappable == {"ask the sarpanch": ["a3"]}
    assert by[("Artisan", "gender")].unmappable == {"Unknown": ["a3"]}
    language = by[("QuestionnaireInterview", "language")]
    assert language.changes == [("i1", "Bangla", "bn")] and language.unmappable == {
        "Kutchi": ["i3"]
    }
    material = by[("ToolDocumentation", "materialFamily")]
    assert material.changes == [("t1", None, "METAL")]
    assert list(material.unmappable) == ["mango wood with an iron collar"]
    crafts = {c["craftId"]: c for c in report.crafts}
    assert crafts["c1"]["candidates"][0]["code"] == "bandhani"
    assert crafts["c2"]["candidates"] == []
    text = "\n".join(script.lines(report, show_values=False))
    assert "Dry run: nothing was written." in text
    assert script.report_filename(report).endswith("_Standard-Values_Report.json")
    assert "_Database_" in script.report_filename(report)


def test_the_script_applies_without_touching_updated_at() -> None:
    import asyncio

    script = _script()
    client = _Client(designer=IS_DESIGNER_PORTAL)
    asyncio.run(script.run(client, apply=True))
    assert client.writes
    assert all("updatedAt" not in write[0] for write in client.writes)
    assert (
        'UPDATE "Artisan" SET "phone" = $1, "phoneCountryCode" = $2 WHERE "id" = $3',
        "9876543210",
        "+91",
        "a1",
    ) in client.writes
    assert not any("craftCode" in write[0] for write in client.writes)
