import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.schemas.records import ArtisanUpdate, drop_masked_identity_numbers
from app.services import record_fields, records
from app.services.artisan_identity import validate_pehchan, verhoeff_ok

PEHCHAN = "PMVY12345678"
PEHCHAN_MASK = "XXXX XXXX 5678"


def _valid_aadhaar() -> str:
    for last in "0123456789":
        candidate = f"22345678901{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


AADHAAR = _valid_aadhaar()
AADHAAR_MASK = f"XXXX XXXX {AADHAAR[-4:]}"


def _artisan(**overrides: Any) -> SimpleNamespace:
    row = {
        "id": "a1",
        "name": "Kalu Ram",
        "localName": None,
        "craft": SimpleNamespace(name="Bagru Hand Block Printing"),
        "location": SimpleNamespace(village="Bagru", district="Jaipur", state="Rajasthan", pincode="303007"),
        "workshops": [],
        "workshopId": None,
        "place": "Bagru",
        "address": "By the dyeing tanks",
        "phone": "9876543210",
        "email": None,
        "aadhaarFingerprint": "ab" * 32,
        "aadhaarLast4": AADHAAR[-4:],
        "pehchanCardAvailable": True,
        "pehchanCardNumber": PEHCHAN,
        "gender": "Male",
        "dos": "Greet the artisan first.",
        "donts": "Do not photograph without asking.",
        "notes": None,
        "extraMetadata": {},
        "status": "APPROVED",
        "createdById": "owner",
        "createdBy": SimpleNamespace(name="Owning Researcher"),
        "createdAt": None,
    }
    row.update(overrides)
    return SimpleNamespace(**row)



def test_the_registry_masks_both_identity_numbers() -> None:
    panel = record_fields.info_panel("artisan", _artisan())
    values = {field["label"]: field["value"] for field in panel["fields"]}

    assert values["Aadhaar number"] == AADHAAR_MASK
    assert values["Pehchan card number"] == PEHCHAN_MASK
    assert values["Artisan Pehchan Card"] == "Yes"


@pytest.mark.parametrize("number", [AADHAAR, PEHCHAN])
def test_no_surface_built_from_the_registry_prints_a_full_number(number: str) -> None:
    artisan = _artisan()

    rendered = record_fields.info_text(record_fields.info_panel("artisan", artisan))
    row = " | ".join(record_fields.sheet_row("artisan", artisan))

    assert number not in rendered
    assert number not in row


def test_the_registry_and_the_api_mask_the_card_number_identically() -> None:
    panel = record_fields.info_panel("artisan", _artisan())
    values = {field["label"]: field["value"] for field in panel["fields"]}

    assert values["Pehchan card number"] == records.mask_identity_number(PEHCHAN)
    assert values["Aadhaar number"] == records.mask_identity_number(AADHAAR)


def test_an_artisan_with_no_card_shows_no_card_row() -> None:
    panel = record_fields.info_panel("artisan", _artisan(pehchanCardNumber=None, pehchanCardAvailable=False))
    labels = {field["label"] for field in panel["fields"]}

    assert "Pehchan card number" not in labels



def test_the_card_number_validator_would_have_accepted_its_own_mask() -> None:
    assert validate_pehchan(PEHCHAN_MASK) == "XXXXXXXX5678"


def test_the_update_schema_hands_a_masked_number_on_untouched() -> None:
    parsed = ArtisanUpdate(aadhaarNumber=AADHAAR_MASK, pehchanCardNumber=PEHCHAN_MASK)

    assert parsed.aadhaarNumber == AADHAAR_MASK
    assert parsed.pehchanCardNumber == PEHCHAN_MASK


def test_both_masked_numbers_are_dropped_and_real_ones_are_kept() -> None:
    dropped = drop_masked_identity_numbers(
        {"aadhaarNumber": AADHAAR_MASK, "pehchanCardNumber": PEHCHAN_MASK, "notes": "Met again"}
    )
    kept = drop_masked_identity_numbers(
        {"aadhaarNumber": AADHAAR, "pehchanCardNumber": PEHCHAN, "notes": "Met again"}
    )

    assert dropped == {"notes": "Met again"}
    assert kept == {"aadhaarNumber": AADHAAR, "pehchanCardNumber": PEHCHAN, "notes": "Met again"}


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _ArtisanDelegate:
    def __init__(self, row: SimpleNamespace) -> None:
        self.row = row
        self.written: dict[str, Any] | None = None

    async def find_unique(self, where: dict, include: dict | None = None) -> SimpleNamespace:
        return self.row

    async def find_first(self, where: dict, include: dict | None = None) -> None:
        return None

    async def find_many(self, where: dict, include: dict | None = None) -> list:
        return []

    async def update(self, where: dict, data: dict, include: dict | None = None) -> SimpleNamespace:
        self.written = data
        return self.row


class _Creating:
    def __init__(self) -> None:
        self.created: list[dict] = []

    async def create(self, data: dict) -> SimpleNamespace:
        self.created.append(data)
        return SimpleNamespace(id="new")


@pytest.fixture
def route(monkeypatch: pytest.MonkeyPatch):
    artisans = _ArtisanDelegate(_artisan())
    revisions = _Creating()
    grant = SimpleNamespace(status="GRANTED", tier="EDIT", allData=True, scopeItems=[])

    async def find_grant(where: dict, include: dict | None = None) -> SimpleNamespace:
        return grant

    fake_db = SimpleNamespace(
        artisan=artisans,
        recordrevision=revisions,
        dataaccessgrant=SimpleNamespace(find_unique=find_grant),
    )
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake_db)
    _CURRENT["user"] = SimpleNamespace(
        id="grantee", email="grantee@example.test", name="Grantee", role="RESEARCHER",
        canDownloadDataset=False, canReview=False,
    )

    def patch(body: dict) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://mask.test") as client:
                return await client.patch("/api/artisans/a1", json=body)

        return asyncio.run(run())

    yield SimpleNamespace(patch=patch, artisans=artisans, revisions=revisions)
    _CURRENT["user"] = None


def test_saving_the_form_with_both_numbers_masked_writes_neither(route) -> None:
    response = route.patch(
        {"aadhaarNumber": AADHAAR_MASK, "pehchanCardNumber": PEHCHAN_MASK, "notes": "Met again at the fair."}
    )

    assert response.status_code == 200, response.text
    written = route.artisans.written
    assert "pehchanCardNumber" not in written
    assert "aadhaarNumber" not in written
    assert written["notes"] == "Met again at the fair."
    for revision in route.revisions.created:
        assert "pehchanCardNumber" not in revision["changes"].data
        assert "aadhaarNumber" not in revision["changes"].data


def test_the_response_shows_that_editor_masks_in_the_first_place(route) -> None:
    response = route.patch({"notes": "Nothing to do with identity."})

    body = response.json()
    assert body["pehchanCardNumber"] == PEHCHAN_MASK
    assert body["aadhaarNumber"] == AADHAAR_MASK


def test_a_real_new_card_number_still_saves(route) -> None:
    response = route.patch({"pehchanCardNumber": "PMVY99998888"})

    assert response.status_code == 200, response.text
    assert route.artisans.written["pehchanCardNumber"] == "PMVY99998888"
