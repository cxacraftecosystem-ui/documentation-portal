"""The duplicate-artisan score, run from the vector file both portals share.

``shared/duplicate-score-vectors.json`` is identical in the designer portal; a vector that only the
designer portal can score (its Field Repository link reason) carries ``"portal": "designer"`` and is
skipped here. No database: the scorer is pure.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services import artisan_duplicates as dup

VECTORS = json.loads(
    (Path(__file__).resolve().parents[2] / "shared" / "duplicate-score-vectors.json").read_text(
        encoding="utf-8"
    )
)
PAIRS = [v for v in VECTORS["pairs"] if v.get("portal") in (None, "documentation")]


def test_thresholds_match_the_vector_file():
    assert VECTORS["thresholds"] == {
        "show": dup.SHOW_THRESHOLD,
        "hold": dup.HOLD_THRESHOLD,
        "veryLikely": dup.VERY_LIKELY_THRESHOLD,
    }
    assert dup.CHECK_MAX_CANDIDATES == 5
    assert dup.UNDO_DAYS == 30
    assert dup.PEER_MATCH_BATCH == 100


@pytest.mark.parametrize(("token", "expected"), VECTORS["skeletons"])
def test_skeletons(token, expected):
    assert dup.skeleton(token) == expected


@pytest.mark.parametrize("vector", VECTORS["names"], ids=lambda v: v["label"])
def test_name_similarity(vector):
    sim = dup.name_sim(vector["a"], vector["b"])
    if "nameSim" in vector:
        assert sim == pytest.approx(vector["nameSim"], abs=1e-6)
    else:
        assert sim < vector["nameSimBelow"]


@pytest.mark.parametrize("vector", PAIRS, ids=lambda v: v["label"])
def test_pairs(vector):
    a = dup.profile_from_dict(vector["a"])
    b = dup.profile_from_dict(vector["b"])
    for left, right in ((a, b), (b, a)):  # the score never depends on which side is which
        result = dup.score_pair(left, right)
        if vector["score"] is None:
            assert result is None
            continue
        assert result is not None
        assert result.score == pytest.approx(vector["score"], abs=1e-9)
        assert result.band == vector["band"]
        for code in vector.get("reasons", []):
            assert code in result.codes
        for code in vector.get("absentReasons", []):
            assert code not in result.codes


def test_designer_portal_vectors_are_skipped_here_not_lost():
    skipped = [v for v in VECTORS["pairs"] if v.get("portal") == "designer"]
    assert skipped, "the shared file still carries the designer portal's link vector"
    assert "SAME_FIELD_REPOSITORY_LINK" not in dup.REASON_CODES


def test_no_label_ever_carries_a_value():
    a = dup.profile_from_dict(
        {"id": "a", "name": "Giriraj Prasad", "phone": "9825012345", "dateOfBirth": "1971-04-02",
         "state": "Gujarat", "district": "Kachchh", "place": "Bhuj", "pehchanCardNumber": "PEH777"}
    )
    b = dup.profile_from_dict(
        {"id": "b", "name": "Giriraj Prasad", "phone": "9825012345", "dateOfBirth": "1971-04-02",
         "state": "Gujarat", "district": "Kachchh", "place": "Bhuj", "pehchanCardNumber": "PEH777"}
    )
    result = dup.score_pair(a, b)
    text = json.dumps(result.reasons_payload())
    for value in ("9825012345", "1971", "PEH777", "Kachchh", "Bhuj", "Giriraj"):
        assert value not in text
    assert {r["code"] for r in result.reasons_payload()} <= set(dup.REASON_CODES)


def test_candidate_payload_shows_birth_year_and_hides_aadhaar_unless_typed():
    other = dup.profile_from_dict(
        {"id": "x", "name": "Giriraj Prasad", "dateOfBirth": "1971-04-02", "aadhaarFingerprint": "f1",
         "pehchanCardNumber": "PEH1", "place": "Bhuj", "craftName": "Ajrakh"}
    )
    mine = dup.profile_from_dict({"name": "Giriraj Prasad", "aadhaarFingerprint": "f1"})
    result = dup.score_pair(mine, other)
    hidden = dup.candidate_payload(other, result, typed_aadhaar=False)
    shown = dup.candidate_payload(other, result, typed_aadhaar=True)
    assert hidden["artisan"]["birthYear"] == 1971
    assert "dateOfBirth" not in hidden["artisan"]
    assert "AADHAAR" not in [r["code"] for r in hidden["reasons"]]
    assert "AADHAAR" in [r["code"] for r in shown["reasons"]]
    for payload in (hidden, shown):
        text = json.dumps(payload, default=str)
        assert "PEH1" not in text and "f1" not in text


def test_scan_finds_pairs_only_inside_blocks():
    profiles = [
        dup.profile_from_dict({"id": "a1", "name": "Giriraj Prasad", "state": "Gujarat", "district": "Kachchh"}),
        dup.profile_from_dict({"id": "a2", "name": "Giriraj Prasaad", "state": "Gujarat", "district": "Kachchh", "place": "Bhuj"}),
        dup.profile_from_dict({"id": "a3", "name": "Meena Devi", "state": "Rajasthan", "district": "Jaipur"}),
        dup.profile_from_dict({"id": "a4", "name": "Someone Else", "pehchanCardNumber": "P9"}),
        dup.profile_from_dict({"id": "a5", "name": "Another Person", "pehchanCardNumber": "P9"}),
    ]
    found = dup.scan_pairs(profiles)
    assert ("a1", "a2") in found
    assert ("a4", "a5") in found and found[("a4", "a5")].score == 1.0
    assert all("a3" not in key for key in found)
