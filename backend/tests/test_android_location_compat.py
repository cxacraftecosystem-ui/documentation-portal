from app.schemas.questionnaire import QuestionnaireInterviewCreate
from app.schemas.records import ArtisanCreate, ProductCreate, ToolCreate, WorkshopCreate
from app.services.records import lift_stated_address

ANDROID_LOCATION = {
    "latitude": 22.3145,
    "longitude": 87.3101,
    "state": "Rajasthan",
    "pincode": "303007",
    "extraMetadata": {
        "district": "Jaipur",
        "village": "Bagru",
        "artisanLatitude": 26.8137,
        "artisanLongitude": 75.5450,
    },
}

WEB_LOCATION = {
    "latitude": 22.3145,
    "longitude": 87.3101,
    "state": "Rajasthan",
    "district": "Jaipur",
    "village": "Bagru",
}

CREATE_MODELS = [
    (
        ArtisanCreate,
        {
            "name": "Test artisan",
            "place": "Bagru",
            "craftId": "craft-1",
            "aadhaarNumber": "234567890124",
            "dos": "Handle the blocks with dry hands.",
            "donts": "Do not soak the blocks overnight.",
        },
    ),
    (
        ProductCreate,
        {
            "productName": "Test product",
            "craftName": "Dabu Block Printing",
            "place": "Bagru",
            "artisanName": "Test artisan",
        },
    ),
    (
        ToolCreate,
        {
            "toolkitName": "Test toolkit",
            "craftName": "Dabu Block Printing",
            "place": "Bagru",
            "artisanName": "Test artisan",
        },
    ),
    (WorkshopCreate, {"title": "Test workshop", "place": "Bagru"}),
    (QuestionnaireInterviewCreate, {"title": "Test interview"}),
]

STATED_ADDRESS_ERROR = "state and the district"


def _location_rule_passed(model, body, location) -> bool:
    try:
        model(**body, location=location)
        return True
    except Exception as exc:  # noqa: BLE001 - any validation error, we only inspect the message
        return STATED_ADDRESS_ERROR not in str(exc).replace("\n", " ")


def test_a_phone_that_sends_district_only_in_metadata_can_still_create_records():
    for model, body in CREATE_MODELS:
        assert _location_rule_passed(model, body, ANDROID_LOCATION), (
            f"{model.__name__} rejected the Android payload shape. Every installed phone posts "
            f"district inside extraMetadata; refusing it takes the field client read-only."
        )


def test_the_web_shape_is_unaffected():
    for model, body in CREATE_MODELS:
        assert _location_rule_passed(model, body, WEB_LOCATION), f"{model.__name__} rejected the web payload"


def _location_rule_refused(model, body, location) -> bool:
    try:
        model(**body, location=location)
        return False
    except Exception as exc:  # noqa: BLE001 - any validation error, we only inspect the message
        return STATED_ADDRESS_ERROR in str(exc).replace("\n", " ")


def test_a_location_with_no_district_in_either_place_is_still_refused():
    bare = {"latitude": 22.3145, "longitude": 87.3101, "state": "Rajasthan"}
    meta_without_district = {**bare, "extraMetadata": {"village": "Bagru"}}
    for model, body in CREATE_MODELS:
        assert _location_rule_refused(model, body, bare), f"{model.__name__} accepted a missing district"
        assert _location_rule_refused(model, body, meta_without_district), (
            f"{model.__name__} accepted metadata that carries no district"
        )


def test_lift_copies_metadata_into_the_columns_the_exports_read():
    lifted = lift_stated_address(dict(ANDROID_LOCATION))
    assert lifted["district"] == "Jaipur"
    assert lifted["village"] == "Bagru"
    assert lifted["subjectLatitude"] == 26.8137
    assert lifted["subjectLongitude"] == 75.5450


def test_lift_leaves_the_metadata_in_place():
    lifted = lift_stated_address(dict(ANDROID_LOCATION))
    assert lifted["extraMetadata"]["district"] == "Jaipur"
    assert lifted["extraMetadata"]["village"] == "Bagru"


def test_an_explicit_column_beats_the_metadata():
    conflicting = {**ANDROID_LOCATION, "district": "Balotra"}
    assert lift_stated_address(dict(conflicting))["district"] == "Balotra"


def test_lift_is_a_no_op_without_metadata():
    for meta in (None, {}, "not-a-dict", []):
        payload = {"latitude": 1.0, "longitude": 2.0, "extraMetadata": meta}
        assert lift_stated_address(dict(payload)).get("district") is None
