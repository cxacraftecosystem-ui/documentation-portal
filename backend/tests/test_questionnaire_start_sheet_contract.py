from __future__ import annotations

import json
import re
from pathlib import Path

from app.questionnaire.schemas import LANGUAGE_OTHER_MAX, NOTE_MAX, SessionCreate

ROOT = Path(__file__).resolve().parents[2]
SHARED = ROOT / "shared"
SHEET = json.loads((SHARED / "questionnaire-start-sheet-contract.json").read_text(encoding="utf-8"))
FORM = json.loads((SHARED / "questionnaire-form-contract.json").read_text(encoding="utf-8"))
LANGUAGES = json.loads((SHARED / "languages.json").read_text(encoding="utf-8"))
PORTALS = {"DesignWorkshop": "designer", "FieldRepository": "fieldRepository"}


def _fields() -> dict[str, dict]:
    return {field["key"]: field for field in SHEET["fields"]}


def _portal() -> str:
    text = (ROOT / "backend" / "app" / "services" / "naming_portal.py").read_text(encoding="utf-8")
    found = [key for name, key in PORTALS.items() if f'"{name}"' in text]
    assert len(found) == 1, f"naming_portal.py names {found or 'no'} portal"
    return found[0]


def test_the_start_sheet_has_no_title_and_the_agreed_fields_in_order() -> None:
    assert SHEET["contract"] == "questionnaire-session-start-sheet"
    assert SHEET["version"] == 2 and SHEET["titleField"] is False
    keys = [field["key"] for field in SHEET["fields"]]
    assert "title" not in keys
    assert keys == [
        "workshopId",
        "questionnaireId",
        "sessionDate",
        "mode",
        "participants",
        "purpose",
        "place",
        "languageCode",
        "note",
    ]


def test_every_field_is_a_server_field_and_the_limits_agree() -> None:
    server = set(SessionCreate.model_fields)
    for key, field in _fields().items():
        assert key in server or field.get("stored") is False, f"{key} is not a SessionCreate field"
    sheet = _fields()
    assert sheet["note"]["maxLength"] == NOTE_MAX == 80
    assert sheet["languageCode"]["other"]["key"] == "languageOther"
    assert sheet["languageCode"]["other"]["maxLength"] == LANGUAGE_OTHER_MAX
    assert "other" in {lang["code"] for lang in LANGUAGES["languages"]}
    assert sheet["purpose"]["options"] == ["FIRST_SITTING", "FOLLOW_UP", "REDO"]


def test_the_workshop_is_stored_where_this_portal_files_a_session() -> None:
    stored_as = _fields()["workshopId"]["storedAs"]
    assert set(stored_as) == set(PORTALS.values())
    server = set(SessionCreate.model_fields)
    assert set(stored_as.values()) <= server, (
        f"{stored_as} names a column SessionCreate does not have"
    )
    switches = list(
        (ROOT / "android" / "app" / "src" / "main" / "java").rglob(
            "questionnaire/portal/QuestionnairePortal.kt"
        )
    )
    assert len(switches) == 1, switches
    match = re.search(
        r"FILES_UNDER_DESIGN_WORKSHOP\s*=\s*(true|false)", switches[0].read_text(encoding="utf-8")
    )
    assert match, f"{switches[0]} no longer declares FILES_UNDER_DESIGN_WORKSHOP"
    expected = "designWorkshopId" if match.group(1) == "true" else "workshopId"
    assert stored_as[_portal()] == expected


def test_the_portal_form_contract_does_not_declare_the_start_sheet_again() -> None:
    assert "sessionStartSheet" not in FORM, (
        "shared/questionnaire-form-contract.json is this portal's own legacy capture form. The start "
        "sheet is shared and lives in shared/questionnaire-start-sheet-contract.json only."
    )
