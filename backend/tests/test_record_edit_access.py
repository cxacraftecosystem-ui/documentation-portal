import asyncio
from datetime import UTC, datetime
from typing import Any

import pytest
from fastapi import HTTPException


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


MASTER_ADMIN_ID = "usr_master"
RESEARCHER = _Row(id="usr_researcher", name="Asha", role="RESEARCHER")
WORKSHOP_ID = "wks_stone"


def _stored_artisan(**overrides: Any) -> _Row:
    columns: dict[str, Any] = {
        "id": "art_1",
        "createdById": MASTER_ADMIN_ID,
        "name": "Ramesh Kumar",
        "localName": None,
        "gender": "Male",
        "phone": "9876543210",
        "phoneCountryCode": "+91",
        "email": None,
        "place": "Bagru",
        "address": None,
        "notes": "Carves temple pillars.",
        "pehchanCardAvailable": False,
        "pehchanCardNumber": None,
        "dateOfBirth": datetime(1980, 4, 2, tzinfo=UTC),
        "craftStartDate": datetime(2000, 1, 1, tzinfo=UTC),
        "experienceYears": 24,
        "experienceMonths": None,
        "dos": "Wet the stone first.",
        "donts": None,
        "craftId": "crf_stone",
        "workshopId": WORKSHOP_ID,
        "designWorkshopId": None,
        "status": "APPROVED",
        "recordedTimezone": "Asia/Kolkata",
        "locationId": "loc_1",
        "extraMetadata": {
            "fieldProvenance": {"name": {"by": MASTER_ADMIN_ID}},
            "mediaExif": [{"filename": "first.jpg", "metadata": {"Make": "Pixel"}}],
        },
    }
    columns.update(overrides)
    return _Row(**columns)


def _unchanged_web_body(**overrides: Any) -> dict[str, Any]:
    from app.services.artisan_identity import mask_aadhaar

    body: dict[str, Any] = {
        "name": "Ramesh Kumar",
        "localName": None,
        "gender": "Male",
        "dateOfBirth": "1980-04-02",
        "craftStartDate": "2000-01-01",
        "experienceYears": 24,
        "experienceMonths": None,
        "phone": "98765 43210",
        "phoneCountryCode": "+91",
        "email": None,
        "place": "Bagru",
        "address": None,
        "notes": "Carves temple pillars.",
        "aadhaarNumber": mask_aadhaar("123412341234"),
        "pehchanCardAvailable": False,
        "pehchanCardNumber": None,
        "dos": "Wet the stone first.",
        "donts": "",
        "craftId": "crf_stone",
        "workshopId": WORKSHOP_ID,
        "status": "APPROVED",
        "recordedTimezone": "Asia/Kolkata",
        "extraMetadata": {},
        "formContract": 2,
    }
    body.update(overrides)
    return body


def _as_the_route_prepares_it(body: dict[str, Any], stored: Any) -> dict[str, Any]:
    from app.api.routes import artisans
    from app.schemas.records import ArtisanUpdate, drop_masked_identity_numbers
    from app.services.artisan_form_contract import assert_form_contract, take_form_contract
    from app.services.records import clean_data, take_expected_updated_at
    from app.services.standard_values import apply_artisan_values

    data = clean_data(
        ArtisanUpdate(**body).model_dump(exclude_unset=True), clearable=artisans._CLEARABLE_COLUMNS
    )
    artisans._standardise(apply_artisan_values, data, stored)
    take_expected_updated_at(data)
    assert_form_contract(take_form_contract(data), data, stored=stored)
    return artisans.reconcile_aadhaar_not_shared(drop_masked_identity_numbers(data), stored=stored)


def _workshop_level(level: str | None):
    from app.services.workshop_access import WorkshopAccess

    async def _resolve(_user: Any, _workshop_id: str) -> WorkshopAccess:
        return WorkshopAccess(level, restricted=True, rowStatus="GRANTED" if level else None, rowLevel=level)

    return _resolve


@pytest.fixture
def world(monkeypatch):
    from app.core import deps
    from app.services import access, workshop_access

    state: dict[str, Any] = {"level": "CONTRIBUTE", "tier": None, "ranked": False, "revisions": []}

    async def _ranked(*_args, **_kwargs):
        return state["ranked"]

    async def _tier(*_args, **_kwargs):
        return state["tier"]

    async def _resolve(user: Any, workshop_id: str):
        return await _workshop_level(state["level"])(user, workshop_id)

    async def _revision(_record, _user, data, _kind):
        state["revisions"].append(dict(data))

    monkeypatch.setattr(deps, "may_edit_lower_ranked_record", _ranked)
    monkeypatch.setattr(access, "effective_tier_for_record", _tier)
    monkeypatch.setattr(workshop_access, "resolve_workshop_access", _resolve)
    monkeypatch.setattr(access, "record_revision", _revision)
    return state


def _guard(stored: Any, user: Any, data: dict[str, Any]) -> bool:
    from app.services import access

    return asyncio.run(access.guard_record_edit(stored, user, data, "artisan"))


def test_an_untouched_artisan_save_by_a_contributor_is_not_refused(world):
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(), stored)

    assert _guard(stored, RESEARCHER, data) is False


def test_a_status_the_contributor_cannot_set_is_left_to_the_status_policy(world):
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(status="PENDING"), stored)

    assert _guard(stored, RESEARCHER, data) is False


def test_adding_to_a_list_or_appending_to_notes_is_allowed_but_removing_is_not(world):
    from app.services.record_changes import locked_fields

    stored = _Row(
        id="t1", createdById=MASTER_ADMIN_ID, stateOfPractice=["Rajasthan"], notes="One.", dos="Wet it."
    )
    assert locked_fields(stored, {"stateOfPractice": ["Rajasthan", "Gujarat"]}) == []
    assert locked_fields(stored, {"notes": "One. Two."}) == []
    assert locked_fields(stored, {"stateOfPractice": ["Gujarat"]}) == ["stateOfPractice"]
    assert locked_fields(stored, {"dos": "Dry it."}) == ["dos"]


def test_relation_links_may_be_added_but_not_removed():
    from app.core.deps import removes_links

    assert removes_links(["a1"], ["a1", "a2"]) is False
    assert removes_links([], ["a1"]) is False
    assert removes_links(["a1", "a2"], ["a2"]) is True
    assert removes_links(["a1"], []) is True


def test_the_fields_that_refused_the_production_save_are_equal_after_standardisation():
    from app.core.deps import values_match
    from app.services.record_changes import field_unchanged

    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(), stored)

    assert isinstance(data["dateOfBirth"], datetime) and data["dateOfBirth"].tzinfo is None
    assert values_match(stored.dateOfBirth, data["dateOfBirth"])
    assert values_match(stored.craftStartDate, data["craftStartDate"])
    assert field_unchanged(stored, "dateOfBirth", data["dateOfBirth"])
    assert field_unchanged(stored, "craftStartDate", data["craftStartDate"])
    assert data["extraMetadata"] == {}


def test_attaching_photos_with_their_exif_needs_no_change_to_a_filled_field(world):
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(
        _unchanged_web_body(
            extraMetadata={"mediaExif": [{"filename": "second.jpg", "metadata": {"Make": "Pixel"}}]}
        ),
        stored,
    )

    assert _guard(stored, RESEARCHER, data) is False


def test_the_record_keeps_every_photo_exif_entry_when_more_are_attached():
    from app.services.records import merge_field_provenance

    stored = _stored_artisan()
    data = {"extraMetadata": {"mediaExif": [{"filename": "second.jpg", "metadata": {}}]}}
    merge_field_provenance(data, RESEARCHER, previous=stored)

    names = [entry["filename"] for entry in data["extraMetadata"].data["mediaExif"]]
    assert names == ["first.jpg", "second.jpg"]
    assert data["extraMetadata"].data["fieldProvenance"] == stored.extraMetadata["fieldProvenance"]


def test_a_contributor_fills_blank_fields_on_someone_elses_record(world):
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(
        _unchanged_web_body(email="ramesh@example.com", donts="Never dry it in the sun."), stored
    )

    assert _guard(stored, RESEARCHER, data) is False
    assert world["revisions"][-1]["email"] == "ramesh@example.com"


def test_a_contributor_changing_a_filled_field_is_still_refused_in_plain_words(world):
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(dateOfBirth="1981-04-02"), stored)

    with pytest.raises(HTTPException) as refusal:
        _guard(stored, RESEARCHER, data)

    assert refusal.value.status_code == 403
    assert "date of birth" in refusal.value.detail
    assert "dateOfBirth" not in refusal.value.detail
    assert world["revisions"] == []


def test_a_contributor_clearing_a_filled_field_is_still_refused(world):
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(notes=""), stored)
    data["notes"] = None

    with pytest.raises(HTTPException) as refusal:
        _guard(stored, RESEARCHER, data)

    assert refusal.value.status_code == 403
    assert "notes" in refusal.value.detail


def test_view_access_to_the_workshop_refuses_even_a_blank_fill(world):
    world["level"] = "VIEW"
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(email="ramesh@example.com"), stored)

    with pytest.raises(HTTPException) as refusal:
        _guard(stored, RESEARCHER, data)

    assert refusal.value.status_code == 403
    assert world["revisions"] == []


def test_workshop_edit_access_changes_a_filled_field_and_the_change_is_recorded(world):
    world["level"] = "EDIT"
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(
        _unchanged_web_body(dateOfBirth="1981-04-02", notes="Carves temple pillars and idols."), stored
    )

    assert _guard(stored, RESEARCHER, data) is True
    assert world["revisions"][-1]["notes"] == "Carves temple pillars and idols."


def test_the_owner_changes_a_filled_field(world):
    world["level"] = "VIEW"
    owner = _Row(id=MASTER_ADMIN_ID, name="Owner", role="RESEARCHER")
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(notes="Rewritten."), stored)

    assert _guard(stored, owner, data) is True


def test_an_admin_changes_a_filled_field(world):
    world["level"] = None
    admin = _Row(id="usr_admin", name="Admin", role="ADMIN")
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(notes="Rewritten."), stored)

    assert _guard(stored, admin, data) is True


def test_a_data_access_edit_grant_changes_a_filled_field(world):
    world["tier"] = "EDIT"
    stored = _stored_artisan()
    data = _as_the_route_prepares_it(_unchanged_web_body(notes="Rewritten."), stored)

    assert _guard(stored, RESEARCHER, data) is True


def test_workshop_contribute_is_not_edit(world):
    from app.services import access

    stored = _stored_artisan()
    assert asyncio.run(access.record_edit_privilege(stored, RESEARCHER, "artisan")) is False
    assert asyncio.run(access.record_edit_access(stored, RESEARCHER, "artisan")) == "FILL_EMPTY"
    world["level"] = "EDIT"
    assert asyncio.run(access.record_edit_privilege(stored, RESEARCHER, "artisan")) is True
    assert asyncio.run(access.record_edit_access(stored, RESEARCHER, "artisan")) == "FULL"
    world["level"] = "VIEW"
    assert asyncio.run(access.record_edit_access(stored, RESEARCHER, "artisan")) == "NONE"


def test_a_workshop_record_uses_its_own_id_for_the_edit_level(world):
    from app.services import access

    workshop = _Row(id=WORKSHOP_ID, createdById=MASTER_ADMIN_ID)
    assert access.record_workshop_id(workshop, "workshop") == WORKSHOP_ID
    world["level"] = "EDIT"
    assert asyncio.run(access.record_edit_privilege(workshop, RESEARCHER, "workshop")) is True


def test_a_repost_of_the_same_location_is_not_a_change(monkeypatch):
    from app.services import record_changes

    same = record_changes.location_compared(
        _Row(latitude=26.82, longitude=75.54, state="Rajasthan", district="Jaipur"),
        _Row(latitude=26.82, longitude=75.54, state="Rajasthan"),
    )
    adds = record_changes.location_compared(
        _Row(latitude=26.82, longitude=75.54, state="Rajasthan"),
        _Row(latitude=26.82, longitude=75.54, state="Rajasthan", village="Bagru"),
    )
    moved = record_changes.location_compared(
        _Row(latitude=26.82, longitude=75.54),
        _Row(latitude=26.9, longitude=75.54),
    )
    assert (same, adds, moved) == ("same", "adds", "moved")


def test_a_reposted_location_keeps_the_stored_one(monkeypatch):
    from app.services import record_changes

    class _Locations:
        async def find_many(self, where, **_kwargs):
            rows = {
                "loc_1": _Row(id="loc_1", latitude=26.82, longitude=75.54, state="Rajasthan"),
                "loc_2": _Row(id="loc_2", latitude=26.82, longitude=75.54, state="Rajasthan"),
            }
            return [rows[key] for key in where["id"]["in"]]

    class _Client:
        location = _Locations()

    stored = _stored_artisan()
    data = {"locationId": "loc_2"}
    unchanged = asyncio.run(record_changes.settle_location(stored, data, client=_Client()))

    assert unchanged == {"locationId"}
    assert "locationId" not in data


def test_a_contributor_cannot_wipe_the_photo_exif_list(world):
    from app.services.record_changes import locked_fields

    stored = _stored_artisan()
    for wipe in (None, {}, "gone"):
        assert locked_fields(stored, {"extraMetadata": {"mediaExif": wipe}}) == ["extraMetadata.mediaExif"]
    assert locked_fields(stored, {"extraMetadata": {"mediaExif": []}}) == []
    assert locked_fields(stored, {"extraMetadata": {"mediaExif": [{"filename": "second.jpg"}]}}) == []


def test_a_non_list_exif_never_replaces_the_stored_list():
    from app.services.records import merge_field_provenance

    for wipe in (None, {}, "gone"):
        stored = _stored_artisan()
        data = {"extraMetadata": {"mediaExif": wipe}}
        merge_field_provenance(data, RESEARCHER, previous=stored)
        assert data["extraMetadata"].data["mediaExif"] == stored.extraMetadata["mediaExif"]


def test_an_append_keeps_the_stored_text_exactly():
    from app.services.record_changes import locked_fields

    def appended(before: str, after: str) -> bool:
        return locked_fields(_Row(id="t1", notes=before), {"notes": after}) == []

    assert appended("Teak 10", "Teak 10\n\nWeighs 100 kg")
    assert appended("Teak 10", "Teak 10 and sheesham")
    assert appended("First.\n\nSecond.", "First.\n\nSecond.\n\nThird.")
    assert not appended("Teak 10", "Teak 100 kg")
    assert not appended("First.\n\nSecond.", "First. Second. Third.")
    assert not appended("Teak 10", "teak 10 more")


PROFESSOR = _Row(id="usr_prof", name="Prof", role="PROFESSOR")


def test_a_workshop_response_names_the_viewers_edit_level(world):
    from app.api.routes import workshops

    workshop = _Row(id=WORKSHOP_ID, createdById=MASTER_ADMIN_ID, title="Bagru", place="Bagru")

    def level_for(user: Any) -> str:
        return asyncio.run(workshops._with_workshop_edit_access({}, workshop, user))["editAccess"]

    assert level_for(RESEARCHER) == "NONE"
    assert level_for(PROFESSOR) == "FILL_EMPTY"
    world["level"] = "EDIT"
    assert level_for(PROFESSOR) == "FULL"
    world["level"] = "VIEW"
    assert level_for(PROFESSOR) == "NONE"


def test_a_craft_response_names_the_viewers_edit_level(world, monkeypatch):
    from app.api.routes import crafts

    craft = _Row(id="crf_1", createdById=MASTER_ADMIN_ID, name="Stone carving", workshopId=None)

    async def _found(*_args, **_kwargs):
        return craft

    async def _nothing(*_args, **_kwargs):
        return None

    async def _as_is(payload):
        return dict(payload)

    monkeypatch.setattr(crafts, "require_record", _found)
    monkeypatch.setattr(crafts, "hydrate_relations", _nothing)
    monkeypatch.setattr(crafts, "_with_taxonomy", _as_is)
    monkeypatch.setattr(crafts, "public_encode", lambda record, *_a, **_k: {"id": record.id})

    assert asyncio.run(crafts.get_craft("crf_1", RESEARCHER))["editAccess"] == "NONE"
    assert asyncio.run(crafts.get_craft("crf_1", PROFESSOR))["editAccess"] == "FILL_EMPTY"
    world["tier"] = "EDIT"
    assert asyncio.run(crafts.get_craft("crf_1", PROFESSOR))["editAccess"] == "FULL"
