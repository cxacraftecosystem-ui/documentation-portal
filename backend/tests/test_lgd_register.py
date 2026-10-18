"""The LGD register file against the district list it codes, and against the sibling portal's copy.

No database.
"""

from __future__ import annotations

import json
import os
import pathlib

import pytest

from app.services import address, lgd

ROOT = pathlib.Path(__file__).resolve().parents[2]
REGISTER = json.loads(lgd.REGISTER_FILE.read_text(encoding="utf-8"))
IS_DESIGNER_PORTAL = (ROOT / "backend" / "app" / "api" / "routes" / "design_workshops.py").exists()
SHARED_FILES = (
    "backend/app/data/lgd_register.json",
    "backend/app/services/lgd.py",
    "scripts/build_lgd_register.py",
)


def test_every_register_district_has_exactly_one_code_or_a_pending_entry():
    coded = {}
    for row in REGISTER["districts"]:
        state = lgd.state_name(row["stateLgd"])
        key = (state, row["name"])
        assert key not in coded, f"{key} coded twice"
        coded[key] = row["lgd"]
    pending = {(row["state"], row["district"]) for row in REGISTER["pending"]}
    assert not coded.keys() & pending
    for state, districts in address.DISTRICTS_BY_STATE.items():
        for district in districts:
            assert ((state, district) in coded) != ((state, district) in pending), (state, district)
    assert len(coded) + len(pending) == address.DISTRICT_COUNT


def test_pending_entries_are_few_and_say_why():
    assert len(REGISTER["pending"]) <= 33
    for row in REGISTER["pending"]:
        assert row["reason"].strip()
        assert row["district"] in address.DISTRICTS_BY_STATE[row["state"]]


def test_codes_are_unique_and_every_state_code_exists():
    codes = [row["lgd"] for row in REGISTER["districts"]]
    assert len(codes) == len(set(codes))
    state_codes = {row["lgd"] for row in REGISTER["states"]}
    assert len(state_codes) == len(REGISTER["states"]) == 36
    assert all(row["stateLgd"] in state_codes for row in REGISTER["districts"])


def test_names_equal_the_canonical_names():
    assert {row["name"] for row in REGISTER["states"]} == set(address.INDIAN_STATES_AND_UNION_TERRITORIES)
    for row in REGISTER["districts"]:
        state = lgd.state_name(row["stateLgd"])
        assert row["name"] in address.DISTRICTS_BY_STATE[state], (state, row["name"])


def test_list_version_matches_the_district_list():
    assert REGISTER["listVersion"] == address.DISTRICT_LIST_VERSION
    assert lgd.register().district_count == address.DISTRICT_COUNT
    assert lgd.register().state_count == 36


def test_well_known_codes():
    # Spot checks against lgdirectory.gov.in: a rename keeps its code, a 2023 district has its own.
    assert lgd.state_lgd("Rajasthan") == 8
    assert lgd.district_lgd("Rajasthan", "Barmer") == 90
    assert lgd.district_lgd("Rajasthan", "Balotra") == 775
    assert lgd.district_lgd("Maharashtra", "Aurangabad") == lgd.district_lgd(
        "Maharashtra", "Chhatrapati Sambhajinagar"
    )


def _sibling() -> pathlib.Path | None:
    other = "documentation-portal" if IS_DESIGNER_PORTAL else "designer-portal"
    twin = ROOT.name.replace("dp-", "doc-", 1) if IS_DESIGNER_PORTAL else ROOT.name.replace("doc-", "dp-", 1)
    for candidate in (os.environ.get("SIBLING_REPO"), ROOT.parent / twin, ROOT.parent / other):
        if candidate and (pathlib.Path(candidate) / SHARED_FILES[0]).is_file():
            return pathlib.Path(candidate)
    return None


def test_the_sibling_portal_carries_the_same_register_and_service():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the register (CI has one repository)")
    for rel in SHARED_FILES:
        mine = (ROOT / rel).read_text(encoding="utf-8").replace("\r\n", "\n")
        theirs = (sibling / rel).read_text(encoding="utf-8").replace("\r\n", "\n")
        assert mine == theirs, f"{rel} differs from {sibling / rel}"
