"""Phone prefill (owner correction (b); DESIGN test 35f): country code and national digits are
stored apart and shown as ``+91 98765 43210``; an empty phone prefills nothing."""

from types import SimpleNamespace

from app.questionnaire.answers import format_phone, prefill_value, split_phone


def test_country_code_and_digits_prefill_the_display_form() -> None:
    artisan = SimpleNamespace(phoneCountryCode="+91", phone="9876543210")
    assert prefill_value(artisan, "phone") == "+91 98765 43210"


def test_an_empty_phone_prefills_nothing() -> None:
    assert prefill_value(SimpleNamespace(phoneCountryCode="+91", phone=None), "phone") is None
    assert prefill_value(SimpleNamespace(phoneCountryCode="+91", phone=""), "phone") is None


def test_the_default_country_is_plus_91_and_a_legacy_combined_value_is_split() -> None:
    assert format_phone(None, "9876543210") == "+91 98765 43210"
    assert format_phone(None, "+91 98765-43210") == "+91 98765 43210"
    assert split_phone(None, "+919876543210") == ("+91", "9876543210")
    assert format_phone("+44", "2079460000") == "+44 2079460000"
    assert format_phone("44", "2079460000") == "+44 2079460000"


def test_other_fields_prefill_from_the_artisan_and_the_sitting() -> None:
    artisan = SimpleNamespace(name="Meena Pal", craft=SimpleNamespace(name="Terracotta"), place="Bishnupur")
    assert prefill_value(artisan, "name") == "Meena Pal"
    assert prefill_value(artisan, "craft") == "Terracotta"
    assert prefill_value(artisan, "sessionDate", session={"sessionDate": "2026-07-01"}) == "2026-07-01"
    assert prefill_value(artisan, "interviewer", interviewer="Nandini") == "Nandini"
    assert prefill_value(artisan, "email") is None
