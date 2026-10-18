"""The artisan card's code (services/artisan_card.py) against the shared vectors.

``shared/artisan-card-vectors.json`` is identical in both repositories and is read by the web
(``e2e/artisan-card-unit.spec.ts``) and Android (``ArtisanCardCodeTest``) tests too, so the three
parsers cannot drift. No database.
"""

from __future__ import annotations

import json
import pathlib
import re
from types import SimpleNamespace

import pytest

from app.services import artisan_card as card

ROOT = pathlib.Path(__file__).resolve().parents[2]
VECTORS = json.loads((ROOT / "shared" / "artisan-card-vectors.json").read_text(encoding="utf-8"))
KEY = VECTORS["tags"]["key"]


def _settings(**over):
    base = {
        "artisan_cards_enabled": True,
        "artisan_card_key": KEY,
        "artisan_card_key_version": 1,
        "artisan_card_key_previous": None,
        "artisan_card_issuer": "F",
    }
    base.update(over)
    return SimpleNamespace(**base)


def test_alphabet_is_crockford_and_qr_alphanumeric():
    assert VECTORS["alphabet"] == card.ALPHABET
    assert len(card.ALPHABET) == 32
    assert not set("ILOU") & set(card.ALPHABET)
    qr_alphanumeric = set("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:")
    assert set(card.ALPHABET) <= qr_alphanumeric


@pytest.mark.parametrize("case", VECTORS["shortCodes"], ids=lambda c: c["shortCode"])
def test_short_code_and_check(case):
    assert card.check(case["issuer"], case["code"]) == case["check"]
    assert card.short_code(case["issuer"], case["code"]) == case["shortCode"]


@pytest.mark.parametrize("case", VECTORS["parse"], ids=lambda c: repr(c["input"]))
def test_parse_vectors(case):
    if "error" in case:
        with pytest.raises(card.CardParseError) as caught:
            card.parse(case["input"])
        assert caught.value.reason == case["error"]
    else:
        assert card.parse(case["input"]).as_dict() == case["expect"]


@pytest.mark.parametrize("case", VECTORS["tags"]["cases"], ids=lambda c: c["payload"])
def test_tag_vectors_under_the_test_key(case):
    tag = card.tag(KEY, case["issuer"], case["keyVersion"], case["code"])
    assert tag == case["tag"]
    assert len(tag) == card.TAG_LENGTH
    assert card.payload(KEY, case["issuer"], case["keyVersion"], case["code"]) == case["payload"]
    assert len(case["payload"]) == 35


def test_new_codes_are_random_crockford():
    codes = {card.new_code() for _ in range(500)}
    assert len(codes) == 500
    for code in codes:
        assert len(code) == card.CODE_LENGTH
        assert set(code) <= set(card.ALPHABET)


def test_verify_accepts_own_tag_and_refuses_a_forged_one():
    settings = _settings()
    token = card.token_for(settings, "7K3QM9XA2F")
    assert card.verify(settings, card.parse(token))
    forged = token[:-1] + ("0" if token[-1] != "0" else "1")
    assert not card.verify(settings, card.parse(forged))
    # A typed short code carries no tag; the lookup by code is what answers it.
    assert card.verify(settings, card.parse(card.short_code("F", "7K3QM9XA2F")))


def test_wrong_key_version_needs_the_previous_key():
    old = _settings()
    printed = card.parse(card.token_for(old, "ABCDEFGHJK"))
    rotated = _settings(artisan_card_key="n" * 44, artisan_card_key_version=2)
    assert not card.verify(rotated, printed)
    with_previous = _settings(
        artisan_card_key="n" * 44, artisan_card_key_version=2, artisan_card_key_previous=KEY
    )
    assert card.verify(with_previous, printed)
    # Two rotations back is never verified.
    twice = _settings(
        artisan_card_key="m" * 44, artisan_card_key_version=3, artisan_card_key_previous="n" * 44
    )
    assert not card.verify(twice, printed)


def test_active_needs_flag_and_long_key():
    assert card.active(_settings())
    assert not card.active(_settings(artisan_cards_enabled=False))
    assert not card.active(_settings(artisan_card_key=None))
    assert not card.active(_settings(artisan_card_key="short"))


def test_payload_never_carries_personal_data():
    artisan_id = "clx9artisan0000000001"
    fingerprint = "a" * 64
    pehchan = "PEH12345"
    for _ in range(200):
        code = card.new_code()
        token = card.payload(KEY, "F", 1, code)
        short = card.short_code("F", code)
        for text in (token, short):
            assert artisan_id.upper() not in text.upper()
            assert fingerprint not in text.lower()
            assert pehchan not in text
            assert not re.search(r"\d{12}", text)
