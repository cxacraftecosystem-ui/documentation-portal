"""The Android client sends its stated address inside ``location.extraMetadata``.

Migration 20260727120000_location_stated_address promoted district, village and the subject pin to
real columns, and ``require_location`` began demanding ``district``. No Android build sends that
column — not the one that shipped with the migration, and not the ones already installed on phones
in the field, which is the part that matters: a device documenting an artisan in a workshop with no
signal cannot be handed a new APK before its next save.

So the server accepts both shapes. These tests are the guard on that promise. If someone later
"tidies" the metadata fallback away, the failure mode is not a test that goes red in isolation — it
is every phone in the field silently losing the ability to create a record, which is why the reason
is written out here rather than left to a commit message.
"""

from app.schemas.questionnaire import QuestionnaireInterviewCreate
from app.schemas.records import ArtisanCreate, ProductCreate, ToolCreate, WorkshopCreate
from app.services.records import lift_stated_address

# What a phone actually posts. `artisanLatitude`/`artisanLongitude` are the older key names for the
# subject pin; both spellings are accepted because both are in the wild.
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

# Every model that runs `require_location`, with the smallest body each one otherwise accepts.
# Bodies are COMPLETE on purpose. Pydantic reports missing-field errors before the model validator
# runs, so a partial body never reaches the stated-address rule and a negative test built on one
# passes for the wrong reason — which is exactly what the first version of this file did.
CREATE_MODELS = [
    (
        ArtisanCreate,
        {
            "name": "Test artisan",
            "place": "Bagru",
            "craftId": "craft-1",
            # Checksum-valid (Verhoeff) and not starting with 0 or 1. An invalid one is rejected by
            # its field validator BEFORE the model validator runs, which silently defeats the
            # negative test below — that produced a false pass while this file was being written.
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
    """True when the stated-address rule accepted this payload.

    Deliberately tolerant of the models' OTHER rules — an artisan needs a craft, a product needs an
    artisan — because this file is about one rule and should not fail when an unrelated one changes.
    """
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
    """True only when the stated-address rule ITSELF rejected the payload.

    Asserting the positive, rather than the absence of the message, is what makes the negative test
    trustworthy. Pydantic reports missing-field and field-validator errors before a model validator
    runs, so a body that is incomplete or slightly wrong never reaches this rule — and a test
    written as "the stated-address message is absent" then passes for entirely the wrong reason.
    That happened twice while this file was being written: once on a partial body, once on an
    Aadhaar number that failed its checksum.
    """
    try:
        model(**body, location=location)
        return False
    except Exception as exc:  # noqa: BLE001 - any validation error, we only inspect the message
        return STATED_ADDRESS_ERROR in str(exc).replace("\n", " ")


def test_a_location_with_no_district_in_either_place_is_still_refused():
    """The rule has to still be a rule — the fallback widens where it looks, not whether it looks."""
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
    """Older builds read these keys back. Popping them would blank the field on a phone mid-update."""
    lifted = lift_stated_address(dict(ANDROID_LOCATION))
    assert lifted["extraMetadata"]["district"] == "Jaipur"
    assert lifted["extraMetadata"]["village"] == "Bagru"


def test_an_explicit_column_beats_the_metadata():
    """A client sending both means the column; the metadata is the older, weaker source."""
    conflicting = {**ANDROID_LOCATION, "district": "Balotra"}
    assert lift_stated_address(dict(conflicting))["district"] == "Balotra"


def test_lift_is_a_no_op_without_metadata():
    for meta in (None, {}, "not-a-dict", []):
        payload = {"latitude": 1.0, "longitude": 2.0, "extraMetadata": meta}
        assert lift_stated_address(dict(payload)).get("district") is None
