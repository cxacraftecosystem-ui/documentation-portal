from __future__ import annotations

import asyncio
import logging
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.testclient import TestClient
from pydantic import BaseModel, Field, ValidationError

from app.core.validation_errors import (
    human_message,
    humanise_field_name,
    install_validation_handler,
    validation_body,
)
from app.schemas.blank_text import BlankTextModel
from app.schemas.common import APIModel, LocationInput
from app.schemas.record_names import (
    ARTISAN_NAME_REQUIRED,
    CRAFT_NAME_REQUIRED,
    PLACE_REQUIRED,
)
from app.schemas.records import ArtisanCreate, ToolCreate, ToolUpdate
from app.services.artisan_identity import verhoeff_ok
from app.services.record_names import fill_linked_names, fill_place_from_artisans

PII_LIKE = "Rukhsana Begum 9876543210"
LOCATION = {"latitude": 27.1767, "longitude": 78.0081, "state": "Uttar Pradesh", "district": "Agra"}


class _Form(APIModel):
    title: str = Field(min_length=1, max_length=20)
    craftName: str | None = Field(default=None, min_length=1)
    remarks: str | None = None
    count: int | None = Field(default=None, ge=0)
    when: Any = None
    location: LocationInput | None = None


def _aadhaar() -> str:
    for tail in range(10**10, 10**10 + 1000):
        candidate = f"2{tail}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no checksum-valid test number in range")


def _android_tool(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "craftName": "",
        "place": "Agra",
        "artisanName": "",
        "toolkitName": "Pietra Dura Chisel",
        "craftIds": ["crf_agra"],
        "artisanIds": ["art_fahim", "art_moin"],
        "location": dict(LOCATION),
    }
    body.update(overrides)
    return body


def test_a_blank_optional_string_becomes_none():
    form = _Form.model_validate({"title": "Kiln", "craftName": "", "remarks": "   "})
    assert form.craftName is None
    assert form.remarks is None
    assert form.model_dump(exclude_unset=True) == {
        "title": "Kiln",
        "craftName": None,
        "remarks": None,
    }


def test_a_blank_string_in_a_nullable_non_text_field_becomes_none():
    assert _Form.model_validate({"title": "Kiln", "count": " "}).count is None


def test_an_any_field_keeps_its_blank_string():
    assert _Form.model_validate({"title": "Kiln", "when": ""}).when == ""


def test_a_nested_model_is_normalised_by_its_own_validator():
    form = _Form.model_validate(
        {"title": "Kiln", "location": {**LOCATION, "village": "  ", "address": ""}}
    )
    assert form.location is not None
    assert form.location.village is None
    assert form.location.address is None


@pytest.mark.parametrize("blank", ["", "   ", "\t\n"])
def test_a_blank_required_string_still_fails_as_empty(blank):
    with pytest.raises(ValidationError) as refusal:
        _Form.model_validate({"title": blank})
    errors = refusal.value.errors()
    assert [(e["type"], e["loc"]) for e in errors] == [("string_too_short", ("title",))]


def test_a_whitespace_required_toolkit_name_fails_like_an_empty_one():
    with pytest.raises(ValidationError) as refusal:
        ToolCreate.model_validate(_android_tool(toolkitName="   "))
    assert [(e["type"], e["loc"]) for e in refusal.value.errors()] == [
        ("string_too_short", ("toolkitName",))
    ]


def test_extra_keys_are_still_forbidden():
    with pytest.raises(ValidationError) as refusal:
        _Form.model_validate({"title": "Kiln", "bogus": ""})
    assert refusal.value.errors()[0]["type"] == "extra_forbidden"


def test_a_plain_blank_text_model_keeps_its_own_config():
    class Loose(BlankTextModel):
        note: str | None = None

    loose = Loose.model_validate({"note": " ", "other": 1})
    assert loose.note is None


def test_non_dict_input_passes_through_untouched():
    form = _Form.model_validate(_Form(title="Kiln"))
    assert form.title == "Kiln"


def test_the_0_0_2_android_tool_body_validates_with_blank_names():
    tool = ToolCreate.model_validate(_android_tool())
    assert tool.craftName is None
    assert tool.artisanName is None
    assert tool.place == "Agra"
    assert tool.craftIds == ["crf_agra"]


def test_a_tool_with_neither_names_nor_links_names_every_missing_choice():
    with pytest.raises(ValidationError) as refusal:
        ToolCreate.model_validate(_android_tool(craftIds=[], artisanIds=[], place=" "))
    message = str(refusal.value)
    assert CRAFT_NAME_REQUIRED in message
    assert ARTISAN_NAME_REQUIRED in message
    assert PLACE_REQUIRED in message


def test_a_single_craft_id_and_artisan_id_are_enough_for_the_names():
    body = _android_tool(craftId="crf_agra", artisanId="art_fahim", place="")
    del body["craftIds"], body["artisanIds"]
    tool = ToolCreate.model_validate(body)
    assert (tool.craftName, tool.artisanName, tool.place) == (None, None, None)


def test_typed_names_are_kept_as_sent():
    tool = ToolCreate.model_validate(
        _android_tool(
            craftName="Agra Marble Inlay", artisanName="Fahim", craftIds=[], artisanIds=[]
        )
    )
    assert (tool.craftName, tool.artisanName, tool.place) == ("Agra Marble Inlay", "Fahim", "Agra")


def test_an_artisan_without_a_craft_gets_the_human_message():
    with pytest.raises(ValidationError) as refusal:
        ArtisanCreate.model_validate(
            {
                "name": "Fahim",
                "place": "Agra",
                "dos": "Ask first.",
                "donts": "No flash.",
                "craftName": " ",
                "aadhaarNumber": _aadhaar(),
                "location": LOCATION,
            }
        )
    assert CRAFT_NAME_REQUIRED in str(refusal.value)


def test_a_blank_remark_on_patch_is_an_explicit_clear():
    assert ToolUpdate.model_validate({"remarks": ""}).model_dump(exclude_unset=True) == {
        "remarks": None
    }
    assert ToolUpdate.model_validate({"craftName": " "}).model_dump(exclude_unset=True) == {
        "craftName": None
    }
    assert ToolUpdate.model_validate({}).model_dump(exclude_unset=True) == {}


class _Rows:
    def __init__(self, rows: dict[str, Any]):
        self.rows = rows
        self.asked: list[dict[str, Any]] = []

    async def find_unique(self, where: dict[str, Any], **kwargs: Any) -> Any:
        self.asked.append({"where": where, **kwargs})
        return self.rows.get(where["id"])


def _client() -> SimpleNamespace:
    return SimpleNamespace(
        craft=_Rows(
            {
                "crf_agra": SimpleNamespace(id="crf_agra", name="Agra Marble Inlay"),
                "crf_blank": SimpleNamespace(id="crf_blank", name=" "),
            }
        ),
        artisan=_Rows(
            {
                "art_fahim": SimpleNamespace(
                    id="art_fahim", name="Fahim", place="Agra", location=None
                ),
                "art_village": SimpleNamespace(
                    id="art_village",
                    name="Moin Khan",
                    place="",
                    location=SimpleNamespace(village="Tajganj", district="Agra"),
                ),
                "art_district": SimpleNamespace(
                    id="art_district",
                    name="Asif",
                    place=" ",
                    location=SimpleNamespace(village=None, district="Agra"),
                ),
                "art_nowhere": SimpleNamespace(
                    id="art_nowhere", name="Nobody", place="", location=None
                ),
            }
        ),
    )


def _fill(data: dict[str, Any], **kwargs: Any) -> dict[str, Any]:
    return asyncio.run(
        fill_linked_names(data, client=kwargs.pop("client", None) or _client(), **kwargs)
    )


def test_missing_names_are_derived_from_the_linked_craft_and_artisan():
    data = _fill({"craftId": "crf_agra", "artisanId": "art_fahim"})
    assert (data["craftName"], data["artisanName"], data["place"]) == (
        "Agra Marble Inlay",
        "Fahim",
        "Agra",
    )


def test_sent_names_are_never_overwritten_and_nothing_is_read():
    client = _client()
    data = _fill(
        {
            "craftId": "crf_agra",
            "artisanId": "art_fahim",
            "craftName": "Pietra Dura",
            "artisanName": "F. Q.",
            "place": "Tajganj",
        },
        client=client,
    )
    assert (data["craftName"], data["artisanName"], data["place"]) == (
        "Pietra Dura",
        "F. Q.",
        "Tajganj",
    )
    assert client.craft.asked == [] and client.artisan.asked == []


@pytest.mark.parametrize(
    ("artisan_id", "place"), [("art_village", "Tajganj"), ("art_district", "Agra")]
)
def test_place_falls_back_to_the_artisan_location(artisan_id, place):
    data = _fill({"craftName": "X", "artisanName": "Y", "artisanId": artisan_id})
    assert data["place"] == place


@pytest.mark.parametrize(
    ("data", "message"),
    [
        ({"artisanName": "Y", "place": "Z"}, CRAFT_NAME_REQUIRED),
        ({"craftName": "X", "place": "Z"}, ARTISAN_NAME_REQUIRED),
        ({"craftName": "X", "artisanName": "Y"}, PLACE_REQUIRED),
        (
            {"craftId": "crf_blank", "artisanName": "Y", "place": "Z"},
            "The chosen craft has no name on file.",
        ),
        (
            {"craftName": "X", "artisanName": "Y", "artisanId": "art_nowhere"},
            "The chosen artisan has no place on file.",
        ),
    ],
)
def test_an_underivable_name_is_a_readable_422_and_never_a_500(data, message):
    with pytest.raises(HTTPException) as refusal:
        _fill(dict(data))
    assert refusal.value.status_code == 422
    assert isinstance(refusal.value.detail, str)
    assert refusal.value.detail.startswith(message)


def test_only_the_requested_columns_are_derived():
    data = _fill({"craftName": "X", "artisanId": "art_fahim"}, columns=("artisanName", "place"))
    assert (data["artisanName"], data["place"]) == ("Fahim", "Agra")


def test_an_interview_place_comes_from_its_first_artisan_and_is_optional():
    client = _client()
    assert asyncio.run(fill_place_from_artisans({}, ["", "art_village"], client=client)) == {
        "place": "Tajganj"
    }
    assert asyncio.run(
        fill_place_from_artisans({"place": "Agra"}, ["art_village"], client=client)
    ) == {"place": "Agra"}
    assert asyncio.run(fill_place_from_artisans({}, [], client=client)) == {}
    assert asyncio.run(fill_place_from_artisans({}, ["art_nowhere"], client=client)) == {}


@pytest.mark.parametrize(
    ("name", "label"),
    [
        ("craftName", "Craft name"),
        ("toolkitName", "Toolkit name"),
        ("artisanName", "Artisan name"),
        ("craftIds", "Crafts"),
        ("artisanId", "Artisan"),
        ("designWorkshopId", "Design workshop"),
        ("district", "District"),
        ("dos", "Do's"),
        ("pincode", "PIN code"),
        ("experience_years", "Experience years"),
    ],
)
def test_labels_are_humanised_field_names(name, label):
    assert humanise_field_name(name) == label


@pytest.mark.parametrize(
    ("error", "message"),
    [
        ({"type": "missing", "loc": ("body", "craftName")}, "Craft name is required."),
        ({"type": "missing", "loc": ("body",)}, "The request body is required."),
        (
            {"type": "string_too_short", "loc": ("body", "craftName"), "ctx": {"min_length": 1}},
            "Craft name can't be empty.",
        ),
        (
            {"type": "string_too_short", "loc": ("body", "phone"), "ctx": {"min_length": 10}},
            "Phone is too short (min 10 characters).",
        ),
        (
            {"type": "string_too_long", "loc": ("body", "toolkitName"), "ctx": {"max_length": 220}},
            "Toolkit name is too long (max 220 characters).",
        ),
        (
            {"type": "extra_forbidden", "loc": ("body", "bogus")},
            "Bogus isn't a field this form accepts.",
        ),
        (
            {"type": "int_parsing", "loc": ("body", "yearsInUse")},
            "Years in use has the wrong format.",
        ),
        (
            {"type": "float_type", "loc": ("body", "location", "latitude")},
            "Latitude has the wrong format.",
        ),
        (
            {"type": "greater_than_equal", "loc": ("body", "yearsInUse"), "ctx": {"ge": 0}},
            "Years in use must be at least 0.",
        ),
        (
            {
                "type": "less_than_equal",
                "loc": ("body", "location", "latitude"),
                "ctx": {"le": 90.0},
            },
            "Latitude must be at most 90.",
        ),
        (
            {
                "type": "literal_error",
                "loc": ("body", "maker"),
                "ctx": {"expected": "'SELF' or 'UNKNOWN'"},
            },
            "Maker must be one of 'SELF' or 'UNKNOWN'.",
        ),
        (
            {
                "type": "value_error",
                "loc": ("body",),
                "msg": "Value error, Choose the craft, or type the craft's name.",
            },
            "Choose the craft, or type the craft's name.",
        ),
        (
            {
                "type": "value_error",
                "loc": ("body", "craftIds"),
                "msg": "Value error, send [] to clear the links",
            },
            "Crafts: send [] to clear the links.",
        ),
        (
            {"type": "json_invalid", "loc": ("body", 12), "msg": "JSON decode error"},
            "The request body isn't valid JSON.",
        ),
        ({"type": "something_new", "loc": ("query", "page")}, "Page is invalid."),
    ],
)
def test_each_error_type_reads_as_a_sentence(error, message):
    assert human_message(error) == message


def test_the_body_drops_input_ctx_and_url_and_joins_unique_messages():
    errors = [
        {
            "type": "string_too_short",
            "loc": ("body", "craftName"),
            "msg": "x",
            "input": PII_LIKE,
            "ctx": {"min_length": 1},
            "url": "https://errors.pydantic.dev",
        },
        {
            "type": "string_too_short",
            "loc": ("body", "craftName"),
            "msg": "x",
            "input": "",
            "ctx": {"min_length": 1},
        },
        {
            "type": "missing",
            "loc": ("body", "location", "district"),
            "msg": "Field required",
            "input": {},
        },
    ]
    body = validation_body(errors)
    assert body == {
        "detail": [
            {
                "type": "string_too_short",
                "loc": ["body", "craftName"],
                "field": "craftName",
                "label": "Craft name",
                "msg": "Craft name can't be empty.",
            },
            {
                "type": "string_too_short",
                "loc": ["body", "craftName"],
                "field": "craftName",
                "label": "Craft name",
                "msg": "Craft name can't be empty.",
            },
            {
                "type": "missing",
                "loc": ["body", "location", "district"],
                "field": "location.district",
                "label": "District",
                "msg": "District is required.",
            },
        ],
        "message": "Craft name can't be empty. District is required.",
        "code": "validation_failed",
    }
    assert PII_LIKE not in str(body)


def _app() -> FastAPI:
    app = FastAPI()
    install_validation_handler(app)

    @app.post("/api/things/{thing_id}")
    async def save(thing_id: str, payload: _Form) -> dict[str, Any]:
        return {"id": thing_id, "craftName": payload.craftName}

    @app.post("/api/manual")
    async def manual() -> None:
        raise RequestValidationError(
            [
                {
                    "type": "dict_type",
                    "loc": ("body",),
                    "msg": "An object is expected",
                    "input": PII_LIKE,
                }
            ]
        )

    return app


def test_the_handler_answers_with_the_human_shape_and_logs_paths_not_values(caplog):
    caplog.set_level(logging.WARNING, logger="app.validation")
    client = TestClient(_app())
    response = client.post(
        "/api/things/rec-secret-id-42",
        json={
            "title": "   ",
            "remarks": PII_LIKE,
            "bogus": PII_LIKE,
            "location": {"latitude": PII_LIKE, "longitude": 1},
        },
    )

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "validation_failed"
    fields = [entry["field"] for entry in body["detail"]]
    assert "title" in fields and "bogus" in fields and "location.latitude" in fields
    assert body["message"].startswith("Title can't be empty.")
    for entry in body["detail"]:
        assert set(entry) == {"type", "loc", "field", "label", "msg"}
    assert PII_LIKE not in response.text

    lines = [r.getMessage() for r in caplog.records if r.name == "app.validation"]
    assert len(lines) == 1
    line = lines[0]
    assert line.startswith("validation failed: POST /api/things/{thing_id} [")
    assert "body.title string_too_short" in line
    assert "body.bogus extra_forbidden" in line
    assert "body.location.latitude float_parsing" in line
    assert PII_LIKE not in line
    assert "rec-secret-id-42" not in line
    assert all(r.levelno == logging.WARNING for r in caplog.records if r.name == "app.validation")


def test_a_validation_error_raised_inside_a_route_goes_through_the_same_handler(caplog):
    caplog.set_level(logging.WARNING, logger="app.validation")
    response = TestClient(_app()).post("/api/manual", json={})
    assert response.status_code == 422
    assert response.json() == {
        "detail": [
            {
                "type": "dict_type",
                "loc": ["body"],
                "field": "",
                "label": "",
                "msg": "The request body must be a JSON object.",
            }
        ],
        "message": "The request body must be a JSON object.",
        "code": "validation_failed",
    }
    assert any("POST /api/manual [body dict_type]" in r.getMessage() for r in caplog.records)


def test_a_malformed_json_body_is_one_readable_sentence():
    response = TestClient(_app()).post(
        "/api/things/x", content=b"{not json", headers={"content-type": "application/json"}
    )
    assert response.status_code == 422
    assert response.json()["message"] == "The request body isn't valid JSON."


def test_old_clients_joining_msg_now_read_sentences():
    response = TestClient(_app()).post("/api/things/x", json={"title": "", "craftName": ""})
    joined = " ".join(entry["msg"] for entry in response.json()["detail"])
    assert joined == "Title can't be empty."


def test_a_plain_basemodel_is_not_normalised():
    class Plain(BaseModel):
        note: str | None = None

    assert Plain.model_validate({"note": ""}).note == ""
