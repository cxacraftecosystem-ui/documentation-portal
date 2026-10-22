"""The v2 session start sheet in ``shared/questionnaire-form-contract.json`` (version 2) agrees with
the server: no title field, a note of at most 80 characters, a language from
``shared/languages.json`` with "Other", and every field a ``SessionCreate`` key (or the display-only
mode)."""

from __future__ import annotations

import json
from pathlib import Path

from app.questionnaire.schemas import NOTE_MAX, SessionCreate

ROOT = Path(__file__).resolve().parents[2]
FORM = json.loads((ROOT / "shared" / "questionnaire-form-contract.json").read_text(encoding="utf-8"))
LANGUAGES = json.loads((ROOT / "shared" / "languages.json").read_text(encoding="utf-8"))


def test_version_two_has_a_start_sheet_with_no_title() -> None:
    sheet = FORM["sessionStartSheet"]
    assert FORM["version"] == 2 and sheet["version"] == 2 and sheet["titleField"] is False
    keys = [f["key"] for f in sheet["fields"]]
    assert "title" not in keys
    assert keys == ["workshopId", "questionnaireId", "sessionDate", "mode", "participants", "purpose", "place", "languageCode", "note"]


def test_every_field_is_a_server_field_and_the_limits_agree() -> None:
    sheet = {f["key"]: f for f in FORM["sessionStartSheet"]["fields"]}
    server = set(SessionCreate.model_fields)
    for key, field in sheet.items():
        assert key in server or field.get("stored") is False, f"{key} is not a SessionCreate field"
    assert sheet["note"]["maxLength"] == NOTE_MAX == 80
    assert sheet["languageCode"]["other"]["key"] == "languageOther"
    assert "other" in {lang["code"] for lang in LANGUAGES["languages"]}
    assert sheet["purpose"]["options"] == ["FIRST_SITTING", "FOLLOW_UP", "REDO"]
