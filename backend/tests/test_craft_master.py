"""The master craft list (2026-10-20): the file, the service's pure parts, and the two portals' parity.

No database. The database halves are ``test_craft_master_migration.py``, ``test_craft_catalogue_api.py``
and ``test_map_crafts.py``. This file is the same in both portals.
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
from collections import Counter

import pytest

from app.services import craft_master as cm

BACKEND = pathlib.Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
IS_DESIGNER_PORTAL = (BACKEND / "app" / "services" / "stage_schema.py").is_file()

#: Byte-identical in both portals (line endings normalised, as git stores them).
SHARED_FILES = (
    "backend/app/data/craft_master_list.json",
    "backend/app/data/craft_master_aliases.json",
    "backend/app/services/craft_master.py",
    "backend/app/services/linked_names.py",
    "backend/app/api/routes/craft_categories.py",
    "backend/app/scripts/map_crafts.py",
    "backend/app/scripts/sync_craft_master.py",
    "backend/scripts/gen_craft_master_sql.py",
    "backend/prisma/migrations/20261104090000_craft_master_list/migration.sql",
    "backend/tests/test_craft_master.py",
    "backend/tests/test_craft_master_migration.py",
    "backend/tests/test_map_crafts.py",
)


def test_the_list_holds_455_crafts_in_9_categories_and_36_sub_categories():
    crafts = cm.load_master()
    assert len(crafts) == cm.MASTER_COUNT == 455
    assert [c.source_serial for c in crafts] == list(range(1, 456))
    categories = cm.master_categories()
    assert len(categories) == 9
    assert sum(len(c.sub_categories) for c in categories) == 36
    assert Counter(c.gi_status for c in crafts) == {"GI": 209, "NON_GI": 246}
    assert sum(1 for c in crafts if c.is_catch_all) == 35


def test_every_slug_is_unique_and_slug_shaped():
    crafts = cm.load_master()
    assert len({c.slug for c in crafts}) == 455
    subs = [s.slug for c in cm.master_categories() for s in c.sub_categories]
    assert len(set(subs)) == 36
    for slug in [c.slug for c in crafts] + subs + [c.slug for c in cm.master_categories()]:
        assert slug and all(ch.isascii() and (ch.isalnum() or ch == "-") for ch in slug), slug


def test_every_state_is_a_state_union_territory_or_all_india():
    allowed = set(cm.state_choices())
    assert len(allowed) == 37
    for craft in cm.load_master():
        assert set(craft.state_of_origin) <= allowed, craft.slug
        assert set(craft.state_of_practice) <= allowed, craft.slug
        if craft.gi_status == cm.GI:
            assert craft.state_of_origin, f"#{craft.source_serial} is GI with no state of origin"


def test_the_category_order_follows_the_file():
    names = [c.name for c in cm.master_categories()]
    assert names[0] == "Textiles based Craft Category"
    assert names[-1] == "Miscellaneous"
    textiles = cm.master_categories()[0]
    assert [s.name for s in textiles.sub_categories] == [
        "Embroidery",
        "Hand Block Printing",
        "Carpet, Rugs & Durries",
        "Other Textiles based Craft",
    ]


def test_seeded_ids_are_derived_from_slugs():
    assert cm.master_id("c", "Kutch-Ajrakh") == cm.master_id("c", "Kutch-Ajrakh")
    assert cm.master_id("c", "Kutch-Ajrakh") != cm.master_id("s", "Kutch-Ajrakh")
    assert cm.master_id("s", "Embroidery") == "cms" + hashlib.md5(b"s:Embroidery").hexdigest()[:22]


@pytest.mark.parametrize(
    ("a", "b"),
    [
        ("Cane & Bamboo", "cane and bamboo"),
        ("Kutch-Ajrakh", "Kutch Ajrakh"),
        ("  Bagru   Hand Block printing ", "Bagru Hand Block Printing"),
        ("Pāṭṭachitra", "Pattachitra"),
    ],
)
def test_names_that_differ_only_in_case_accents_ampersands_and_punctuation_normalise_alike(a, b):
    assert cm.normalise(a) == cm.normalise(b)


def test_the_fuzzy_score():
    assert cm.token_set_ratio("Kutch Ajrakh (GI)", "Kutch Ajrakh") == 1.0
    assert cm.token_set_ratio("Ajrakh", "Kutch Ajrakh") == 1.0
    assert cm.token_set_ratio("Bandhani", "Blue Pottery") < 0.5
    assert cm.token_set_ratio("", "Anything") == 0.0


def test_aliases_are_kept_once():
    meta = cm.with_alias({"other": 1}, "Bamboo Craft")
    meta = cm.with_alias(meta, "bamboo  craft")
    assert meta == {"other": 1, "aliases": ["Bamboo Craft"]}


def test_a_state_off_the_list_is_refused():
    from fastapi import HTTPException

    assert cm.clean_states([" Gujarat", "Gujarat", "All India"]) == ["Gujarat", "All India"]
    with pytest.raises(HTTPException) as refused:
        cm.clean_states(["Atlantis"])
    assert refused.value.status_code == 422
    assert refused.value.detail["code"] == "UNKNOWN_STATE"


def test_error_bodies_carry_a_code_and_the_agreed_sentence():
    error = cm.craft_error("CRAFT_IN_USE", 409, referenceCount=3)
    assert error.status_code == 409
    assert error.detail == {
        "code": "CRAFT_IN_USE",
        "message": "Used by 3 records. Merge it into another craft instead.",
        "referenceCount": 3,
    }
    assert cm.MESSAGES["CRAFT_NOT_IN_LIST"] == "Pick a craft from the list."


def test_every_curated_decision_names_something_on_the_list():
    aliases = cm.load_aliases()
    serials = {c.source_serial for c in cm.load_master()}
    subs = {s.slug for c in cm.master_categories() for s in c.sub_categories}
    old_names = set()
    for entry in aliases["crafts"]:
        old_names.add(cm.normalise(entry["oldName"]))
        assert ("sourceSerial" in entry) + ("adopt" in entry) + bool(entry.get("legacy")) == 1, entry
        if "sourceSerial" in entry:
            assert entry["sourceSerial"] in serials, entry
        if "adopt" in entry:
            assert entry["adopt"] in subs, entry
    assert len(old_names) == len(aliases["crafts"]) == 15
    for entry in aliases["freeText"]:
        if "sameAs" in entry:
            assert cm.normalise(entry["sameAs"]) in old_names, entry


def test_the_exact_and_alias_matchers_work_on_rows():
    from types import SimpleNamespace

    rows = [
        SimpleNamespace(id="1", name="Kutch Ajrakh", slug="Kutch-Ajrakh", extraMetadata=None),
        SimpleNamespace(id="2", name="Bagru Hand Block Print", slug="Bagru-Hand-Block-Print", extraMetadata={"aliases": ["Bagru Hand Block Printing"]}),
    ]
    assert [c.id for c in cm.exact_matches("kutch ajrakh", rows)] == ["1"]
    assert [c.id for c in cm.exact_matches("Kutch-Ajrakh", rows)] == ["1"]
    assert [c.id for c in cm.exact_matches("Bagru Hand Block printing", rows)] == ["2"]
    assert cm.exact_matches("Ajrakh", rows) == []


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The two repositories
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _sibling() -> pathlib.Path | None:
    other = "documentation-portal" if IS_DESIGNER_PORTAL else "designer-portal"
    twin = ROOT.name.replace("-dp", "-doc", 1) if IS_DESIGNER_PORTAL else ROOT.name.replace("-doc", "-dp", 1)
    for candidate in (os.environ.get("SIBLING_REPO"), ROOT.parent / twin, ROOT.parent / other, pathlib.Path("C:/dev/IIT") / other):
        if candidate and (pathlib.Path(candidate) / SHARED_FILES[0]).is_file():
            return pathlib.Path(candidate)
    return None


def _normalised(path: pathlib.Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


def test_the_master_list_is_the_one_the_requirements_name():
    digest = hashlib.sha256(_normalised(BACKEND / "app" / "data" / "craft_master_list.json")).hexdigest()
    assert digest == "3ab7073e644bfcd96e580dc53e4ad00633ef70a81de3475a0fd552c7a80019a2"
    assert len(json.loads((BACKEND / "app" / "data" / "craft_master_list.json").read_text(encoding="utf-8"))) == 455


@pytest.mark.parametrize("rel", SHARED_FILES)
def test_the_sibling_portal_carries_the_same_file(rel):
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout (CI has one repository)")
    theirs = sibling / rel
    if not theirs.is_file():
        pytest.skip(f"the sibling checkout has no {rel} yet (it is on another branch)")
    assert hashlib.sha256(_normalised(ROOT / rel)).hexdigest() == hashlib.sha256(_normalised(theirs)).hexdigest(), rel
