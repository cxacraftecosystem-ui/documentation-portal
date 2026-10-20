"""THE ARTISAN FORM CONTRACT — one form in both portals, on the web and on Android (2026-10-17).

The owner's decision: the designer portal adopts the documentation portal's artisan form. Date of
birth and Craft start date are required on every NEW save, and so is the answer to "Artisan Pehchan
Card available" (2026-10-19: a new artisan's form starts with it unanswered, on both clients); experience is two optional boxes,
"Experience (years)" and "Experience (months)", whose empty option is "Not stated"; Do's and Don'ts
are "Do's" and "Don'ts". ``shared/artisan-form-contract.json`` declares it once and is byte-identical
in both repositories. This file is identical in both too, and it holds:

* the server's rule (``services/artisan_form_contract``) to the shared sentence vectors, and both
  routes to the transition: a body carrying ``formContract`` 2 is refused when a date would be empty,
  a body without it (an outbox entry queued by an older build, the spreadsheet import, a review edit)
  lands exactly as before;
* each client's artisan form to the declared labels, in order, the declared copy and none of the
  retired words — read from source, no browser and no emulator;
* the two repositories to each other, when the sibling checkout is present: the shared table and the
  four files that implement it must be the same bytes on both sides.

No database. The routes are driven against fakes up to the first write gate, which is all the
contract decides.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.api.routes import artisans as artisan_routes
from app.api.routes.review import _NOT_REVIEW_EDITABLE
from app.schemas.records import ArtisanCreate, ArtisanUpdate
from app.services import artisan_form_contract as afc

pytestmark = pytest.mark.anyio

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT_FILE = ROOT / "shared" / "artisan-form-contract.json"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def contract() -> dict[str, Any]:
    return json.loads(CONTRACT_FILE.read_text(encoding="utf-8"))


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The rule
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_the_module_speaks_the_contracts_key_value_and_dates():
    table = contract()
    assert table["bodyKey"] == afc.FORM_CONTRACT_KEY
    assert table["datesRequiredFrom"] == afc.DATES_REQUIRED_CONTRACT
    assert [{"field": f, "words": w} for f, w in afc.REQUIRED_DATES] == [
        {"field": d["field"], "words": d["words"]} for d in table["requiredDates"]
    ]


def test_the_module_speaks_the_contracts_required_answers():
    assert [{"field": f, "sentence": s} for f, s in afc.REQUIRED_ANSWERS] == [
        {"field": a["field"], "sentence": a["sentence"]} for a in contract()["requiredAnswers"]
    ]
    assert afc.missing_required_answers({}) == ["Answer whether the artisan has a Pehchan card."]
    assert afc.missing_required_answers({"pehchanCardAvailable": False}) == []
    stored = SimpleNamespace(pehchanCardAvailable=True)
    assert afc.missing_required_answers({}, stored=stored) == []
    assert afc.missing_required_answers({"pehchanCardAvailable": None}, stored=stored) == [
        "Answer whether the artisan has a Pehchan card."
    ]


def test_the_refusal_sentence_matches_every_shared_vector():
    vectors = contract()["refusalVectors"]
    assert len(vectors) >= 4, "a vector table that failed to load would pass vacuously"
    for row in vectors:
        data = {"dateOfBirth": row["dateOfBirth"], "craftStartDate": row["craftStartDate"]}
        missing = afc.missing_required_dates(data)
        got = afc.refusal_sentence(missing) if missing else None
        assert got == row["sentence"], row


def test_the_old_shape_is_never_refused():
    afc.assert_required_dates(None, {})
    afc.assert_required_dates(1, {})


def test_an_edit_counts_the_stored_date_but_never_an_explicit_null():
    stored = SimpleNamespace(dateOfBirth="1971-03-04", craftStartDate="1994-06-01")
    afc.assert_required_dates(2, {}, stored=stored)
    with pytest.raises(HTTPException) as refused:
        afc.assert_required_dates(2, {"craftStartDate": None}, stored=stored)
    assert refused.value.status_code == 422
    assert refused.value.detail == "Enter the craft start date."
    legacy = SimpleNamespace(dateOfBirth=None, craftStartDate=None)
    with pytest.raises(HTTPException) as asked:
        afc.assert_required_dates(2, {"phone": "+91 98290 00000"}, stored=legacy)
    assert asked.value.detail == "Enter the date of birth and the craft start date."


def test_both_schemas_take_the_key_bounded_and_optional():
    assert _create_body().formContract is None
    assert _create_body(formContract=2).formContract == 2
    assert ArtisanUpdate(formContract=2).formContract == 2
    for bad in (0, 3):
        with pytest.raises(ValidationError):
            _create_body(formContract=bad)


def test_a_review_edit_cannot_carry_the_key():
    assert "formContract" in _NOT_REVIEW_EDITABLE


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The routes, up to the first gate
# ──────────────────────────────────────────────────────────────────────────────────────────────


class _Reached(Exception):
    """Raised by the first gate after the contract check: the save got past it."""


CALLER = SimpleNamespace(id="u-1", name="R. Menon", email="r@example.org", role="DESIGNER")


def _create_body(**extra: Any) -> ArtisanCreate:
    return ArtisanCreate(
        name="Asha",
        place="Bagru",
        craftId="craft-1",
        aadhaarNumber="234567890124",
        dos="Dry the blocks.",
        donts="Do not soak them.",
        location={
            "latitude": 26.81,
            "longitude": 75.54,
            "state": "Rajasthan",
            "district": "Jaipur",
            "village": "Bagru",
        },
        **extra,
    )


@pytest.fixture
def create_gate(monkeypatch):
    """Lets a create through the workshop gates and stops it at the first helper handed the body.

    ``resolve_craft_id`` is that helper in both portals' create routes, so the same fake stands in
    for both; the designer portal's ``assert_payload_workshop`` has no twin in the other, hence
    ``raising=False``.
    """
    seen: list[dict[str, Any]] = []

    async def _pass(*args: Any, **kwargs: Any) -> Any:
        return None

    async def _gate(data: dict[str, Any], *args: Any, **kwargs: Any) -> Any:
        seen.append(dict(data))
        raise _Reached

    async def _none(*args: Any, **kwargs: Any) -> list[Any]:
        return []

    monkeypatch.setattr(artisan_routes, "enforce_workshop_submission", _pass)
    monkeypatch.setattr(artisan_routes, "assert_payload_workshop", _pass, raising=False)
    # The identity guard and the likely-duplicate score read the register, and on the designer
    # portal both run before the craft is resolved; neither is what these tests are about.
    monkeypatch.setattr(artisan_routes, "_guard_identity_conflicts", _pass, raising=False)
    monkeypatch.setattr(artisan_routes, "_likely_duplicates", _none, raising=False)
    monkeypatch.setattr(artisan_routes, "resolve_craft_id", _gate)
    return seen


async def test_a_create_under_the_contract_without_the_dates_is_refused(create_gate):
    with pytest.raises(HTTPException) as refused:
        await artisan_routes.create_artisan(_create_body(formContract=2), current_user=CALLER)
    assert refused.value.status_code == 422
    assert refused.value.detail == "Enter the date of birth and the craft start date."
    assert create_gate == [], "refused before any helper is handed the body"


async def test_a_create_under_the_contract_without_the_pehchan_answer_is_refused(create_gate):
    body = _create_body(formContract=2, dateOfBirth="1971-03-04", craftStartDate="1994-06-01")
    with pytest.raises(HTTPException) as refused:
        await artisan_routes.create_artisan(body, current_user=CALLER)
    assert refused.value.status_code == 422
    assert refused.value.detail == "Answer whether the artisan has a Pehchan card."
    assert create_gate == [], "refused before any helper is handed the body"


async def test_a_create_under_the_contract_with_both_dates_goes_on_and_the_key_is_gone(create_gate):
    body = _create_body(
        formContract=2,
        dateOfBirth="1971-03-04",
        craftStartDate="1994-06-01",
        pehchanCardAvailable=False,
    )
    with pytest.raises(_Reached):
        await artisan_routes.create_artisan(body, current_user=CALLER)
    assert create_gate and "formContract" not in create_gate[0], "the key is not a column"


async def test_an_old_shape_create_without_the_dates_lands_as_before(create_gate):
    with pytest.raises(_Reached):
        await artisan_routes.create_artisan(_create_body(), current_user=CALLER)
    assert create_gate and "dateOfBirth" not in create_gate[0]
    # Unanswered Pehchan in the old shape resolves from the number, as it always did.
    assert create_gate[0]["pehchanCardAvailable"] is False


def _stored(**columns: Any) -> Any:
    base = {
        "id": "a-1",
        "workshopId": None,
        "designWorkshopId": None,
        "dateOfBirth": None,
        "craftStartDate": None,
        "pehchanCardAvailable": False,
        "pehchanCardNumber": None,
    }
    base.update(columns)
    return SimpleNamespace(**base)


@pytest.fixture
def stored_row(monkeypatch):
    holder: dict[str, Any] = {"row": _stored()}

    async def _require_record(delegate: Any, record_id: str) -> Any:
        return holder["row"]

    def _gate(data: dict[str, Any], *args: Any, **kwargs: Any) -> Any:
        # The first helper both portals' edit routes hand the body to after the contract check.
        assert "formContract" not in data, "the key is not a column"
        raise _Reached

    monkeypatch.setattr(artisan_routes, "require_record", _require_record)
    monkeypatch.setattr(artisan_routes, "drop_masked_identity_numbers", _gate)
    return holder


async def test_an_edit_of_a_record_saved_before_the_rule_asks_for_both_dates(stored_row):
    with pytest.raises(HTTPException) as asked:
        await artisan_routes.update_artisan(
            "a-1", ArtisanUpdate(phone="+91 98290 00000", formContract=2), current_user=CALLER
        )
    assert asked.value.detail == "Enter the date of birth and the craft start date."
    with pytest.raises(_Reached):
        await artisan_routes.update_artisan(
            "a-1",
            ArtisanUpdate(dateOfBirth="1971-03-04", craftStartDate="1994-06-01", formContract=2),
            current_user=CALLER,
        )


async def test_an_edit_keeps_a_stored_date_and_refuses_clearing_one(stored_row):
    stored_row["row"] = _stored(dateOfBirth="1971-03-04", craftStartDate="1994-06-01")
    with pytest.raises(_Reached):
        await artisan_routes.update_artisan(
            "a-1", ArtisanUpdate(phone="x", formContract=2), current_user=CALLER
        )
    with pytest.raises(HTTPException) as refused:
        await artisan_routes.update_artisan(
            "a-1", ArtisanUpdate(dateOfBirth=None, formContract=2), current_user=CALLER
        )
    assert refused.value.detail == "Enter the date of birth."


async def test_an_edit_counts_the_stored_pehchan_answer(stored_row):
    # Every stored row holds an answer (the column is never empty), so reading one is never blocked
    # and an edit made under the contract is not refused for a question the record already answers.
    stored_row["row"] = _stored(
        dateOfBirth="1971-03-04", craftStartDate="1994-06-01", pehchanCardAvailable=True
    )
    with pytest.raises(_Reached):
        await artisan_routes.update_artisan(
            "a-1", ArtisanUpdate(phone="x", formContract=2), current_user=CALLER
        )


async def test_an_old_shape_edit_of_a_legacy_record_lands_as_before(stored_row):
    with pytest.raises(_Reached):
        await artisan_routes.update_artisan("a-1", ArtisanUpdate(phone="x"), current_user=CALLER)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The clients, read from source
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _strip_comments(source: str) -> str:
    """Block comments (TS, JSX and Kotlin) and line comments, so prose never satisfies a check."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return re.sub(r"(?m)(?<![:\"'\\])//.*$", "", source)


def _one(pattern: str) -> pathlib.Path:
    found = sorted(ROOT.glob(pattern))
    assert len(found) == 1, f"expected exactly one {pattern}, found {found}"
    return found[0]


WEB_FORM = ROOT / "frontend" / "components" / "forms" / "ArtisanForm.tsx"
WEB_LIB = ROOT / "frontend" / "lib" / "artisanFormContract.ts"
WEB_MEDIA = ROOT / "frontend" / "components" / "forms" / "MediaCaptureField.tsx"


def _android_main() -> pathlib.Path:
    return _one("android/app/src/main/java/com/*/*/MainActivity.kt")


def _android_helper() -> pathlib.Path:
    return _one("android/app/src/main/java/com/*/*/data/ArtisanFormContract.kt")


def _web_region() -> str:
    source = _strip_comments(WEB_FORM.read_text(encoding="utf-8"))
    anchor = source.index("<DictationUnavailableNotice />")
    return source[anchor:]


def _android_region() -> str:
    source = _strip_comments(_android_main().read_text(encoding="utf-8"))
    start = re.search(r"(?m)^private fun ArtisanForm\(", source)
    assert start, "the handset's ArtisanForm composable is not where this test looks"
    end = re.search(r"(?m)^(?:private |internal )?fun ", source[start.end() :])
    return source[start.start() : start.end() + (end.start() if end else len(source))]


def _web_position(region: str, label: str) -> int:
    found = re.search(r"(?:label=[\"']|>\s*)" + re.escape(label) + r"(?:[\"']|\s*<)", region)
    return found.start() if found else -1


def _android_position(region: str, label: str) -> int:
    return region.find(f'"{label}"')


@pytest.mark.parametrize(
    "client,region,position",
    [("web", _web_region, _web_position), ("android", _android_region, _android_position)],
)
def test_each_client_draws_the_declared_labels_in_the_declared_order(client, region, position):
    source = region()
    order = contract()[client]["order"]
    positions = [(label, position(source, label)) for label in order]
    missing = [label for label, at in positions if at < 0]
    assert not missing, f"{client} artisan form does not draw {missing}"
    ats = [at for _, at in positions]
    assert ats == sorted(ats), f"{client} artisan form draws them out of order: {positions}"


def test_each_client_carries_the_declared_copy_and_none_of_the_retired_words():
    table = contract()
    web = _web_region() + _strip_comments(WEB_LIB.read_text(encoding="utf-8"))
    for text in table["web"]["copy"]:
        assert text in web, f"web artisan form lost {text!r}"
    web_all = web + _strip_comments(WEB_MEDIA.read_text(encoding="utf-8"))
    assert table["web"]["mediaHint"] in web_all
    for text in table["web"]["retired"]:
        assert text.lower() not in web_all.lower(), f"web artisan form still says {text!r}"
    android = _android_region() + _strip_comments(_android_helper().read_text(encoding="utf-8"))
    for text in table["android"]["copy"]:
        assert text in android, f"android artisan form lost {text!r}"
    for text in table["android"]["retired"]:
        assert text not in android, f"android artisan form still says {text!r}"


def test_both_clients_send_the_key_on_every_save():
    web = _strip_comments(WEB_FORM.read_text(encoding="utf-8"))
    assert "formContract: ARTISAN_FORM_CONTRACT" in web
    assert re.search(r"name=\"dateOfBirth\"[^/]*?required", web, re.DOTALL)
    assert re.search(r"name=\"craftStartDate\"[^/]*?required", web, re.DOTALL)
    android = _android_region()
    assert "formContract = ARTISAN_FORM_CONTRACT" in android
    assert "missingArtisanDatesSentence(" in android


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The two repositories
# ──────────────────────────────────────────────────────────────────────────────────────────────

#: The files that must be the same bytes in both repositories (line endings aside).
SHARED_FILES = (
    "shared/artisan-form-contract.json",
    "frontend/lib/artisanFormContract.ts",
    "frontend/e2e/artisan-form-contract-unit.spec.ts",
    "backend/app/services/artisan_form_contract.py",
    "backend/tests/test_artisan_form_contract.py",
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
        if candidate and (pathlib.Path(candidate) / CONTRACT_FILE.relative_to(ROOT)).is_file():
            return pathlib.Path(candidate)
    return None


def _text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def test_the_sibling_portal_carries_the_same_contract_and_implementation():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the contract (CI has one repository)")
    for rel in SHARED_FILES:
        theirs = sibling / rel
        assert theirs.is_file(), f"{sibling} lacks {rel}"
        assert _text(ROOT / rel) == _text(theirs), f"{rel} differs from {theirs}"
    # The two Kotlin files differ by their package line and by nothing else.
    for pattern in (
        "android/app/src/main/java/com/*/*/data/ArtisanFormContract.kt",
        "android/app/src/test/java/com/*/*/data/ArtisanFormContractTest.kt",
    ):
        mine, theirs = sorted(ROOT.glob(pattern)), sorted(sibling.glob(pattern))
        assert len(mine) == 1 and len(theirs) == 1, f"{pattern}: {mine} / {theirs}"
        assert _text(mine[0]).split("\n", 1)[1] == _text(theirs[0]).split("\n", 1)[1], (
            f"{pattern} differs beyond its package line"
        )
