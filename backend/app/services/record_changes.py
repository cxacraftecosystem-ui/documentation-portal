import json
import re
from collections.abc import Collection
from typing import Any

from app.core.roles import as_instant, get_value, is_empty_value, values_match

SERVER_EXTRA_KEYS = frozenset({"fieldProvenance", "workshopSubmission"})

POLICY_FIELDS = frozenset({"status"})

ADDITIVE_EXTRA_KEYS = frozenset({"mediaExif"})

DATE_ONLY_FIELDS = frozenset({"dateOfBirth", "craftStartDate", "date", "startDate", "endDate"})

LOCATION_POINT_KEYS = ("latitude", "longitude")

LOCATION_STATED_KEYS = (
    "address",
    "placeName",
    "state",
    "district",
    "village",
    "pincode",
    "subjectLatitude",
    "subjectLongitude",
)

_FIELD_WORDS = {
    "aadhaarNumber": "Aadhaar number",
    "aadhaarFingerprint": "Aadhaar number",
    "aadhaarLast4": "Aadhaar number",
    "pehchanCardNumber": "Pehchan card number",
    "pehchanCardAvailable": "Pehchan card",
    "dos": "do's",
    "donts": "don'ts",
    "extraMetadata": "extra details",
    "phoneCountryCode": "phone country code",
}


def field_words(field: str) -> str:
    if field in _FIELD_WORDS:
        return _FIELD_WORDS[field]
    head = field.rsplit(".", maxsplit=1)[-1]
    if head.endswith("Ids") and len(head) > 3:
        head = head[:-3] + "s"
    elif head.endswith("Id") and len(head) > 2:
        head = head[:-2]
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", head).lower()


def locked_fields_sentence(fields: Collection[str]) -> str:
    words: list[str] = []
    for field in sorted(fields):
        word = field_words(field)
        if word not in words:
            words.append(word)
    noun = "this filled-in field" if len(words) == 1 else "these filled-in fields"
    return (
        f"Only the person who recorded this, an admin, or someone with Edit access to its workshop "
        f"can change {noun}: {', '.join(words)}."
    )


def _title_cased(value: Any) -> Any:
    from app.services.text_format import title_case

    return title_case(value) if isinstance(value, str) else value


def _standardised_stored(record: Any, field: str, stored: Any) -> Any:
    from app.services import standard_values
    from app.services.records import TITLE_CASE_FIELDS

    try:
        if field in TITLE_CASE_FIELDS:
            return _title_cased(stored)
        if field == "phone":
            return standard_values.normalize_phone(get_value(record, "phoneCountryCode"), stored)[1]
        if field == "phoneCountryCode":
            return standard_values.normalize_country_code(stored)
        if field == "gender":
            return standard_values.normalize_gender(stored)
        if field == "language":
            return standard_values.normalize_language(stored)
        if field == "traditionType":
            return standard_values.normalize_tradition(stored)
        if field == "materialFamily":
            return standard_values.normalize_material_family(stored)
    except (standard_values.StandardValueError, TypeError, ValueError):
        return stored
    return stored


def _same_text(stored: Any, incoming: Any) -> bool:
    return isinstance(stored, str) and isinstance(incoming, str) and stored.strip() == incoming.strip()


def _same_day(stored: Any, incoming: Any) -> bool:
    stored_instant = as_instant(stored)
    incoming_instant = as_instant(incoming)
    if stored_instant is None or incoming_instant is None:
        return False
    return stored_instant.date() == incoming_instant.date()


def same_value(stored: Any, incoming: Any) -> bool:
    if is_empty_value(stored) and is_empty_value(incoming):
        return True
    if values_match(stored, incoming) or _same_text(stored, incoming):
        return True
    if isinstance(incoming, dict) and isinstance(stored, dict):
        return all(
            same_value(stored.get(key), incoming.get(key)) for key in set(stored) | set(incoming)
        )
    if isinstance(incoming, (list, tuple)) and isinstance(stored, (list, tuple)):
        return len(incoming) == len(stored) and all(
            same_value(a, b) for a, b in zip(stored, incoming, strict=True)
        )
    return False


def field_unchanged(record: Any, field: str, incoming: Any) -> bool:
    stored = get_value(record, field)
    if same_value(stored, incoming):
        return True
    if field in DATE_ONLY_FIELDS and _same_day(stored, incoming):
        return True
    standardised = _standardised_stored(record, field, stored)
    return standardised is not stored and same_value(standardised, incoming)


APPENDABLE_TEXT_FIELDS = frozenset(
    {
        "notes",
        "remarks",
        "dos",
        "donts",
        "description",
        "suggestionsForToolImprovement",
        "rawMaterialsUsed",
        "mainToolsUsed",
    }
)


def _blocks(value: Any) -> list[Any] | None:
    if not isinstance(value, str):
        return None
    try:
        decoded = json.loads(value)
    except ValueError:
        return None
    blocks = decoded.get("blocks") if isinstance(decoded, dict) else None
    return blocks if isinstance(blocks, list) else None


def _appends_text(stored: Any, incoming: Any) -> bool:
    if not isinstance(stored, str) or not isinstance(incoming, str):
        return False
    before = _blocks(stored)
    after = _blocks(incoming)
    if before is not None and after is not None:
        return len(after) >= len(before) and after[: len(before)] == before
    if before is not None or after is not None:
        return False
    kept = stored.rstrip()
    if not incoming.startswith(kept):
        return False
    added = incoming[len(kept):]
    return not added.strip() or added[0].isspace()


def adds_only(field: str, stored: Any, incoming: Any) -> bool:
    if isinstance(stored, (list, tuple, set)) and isinstance(incoming, (list, tuple, set)):
        remaining = list(incoming)
        for value in stored:
            match = next((item for item in remaining if same_value(value, item)), _MISSING)
            if match is _MISSING:
                return False
            remaining.remove(match)
        return True
    if field in APPENDABLE_TEXT_FIELDS:
        return _appends_text(stored, incoming)
    return False


_MISSING = object()


def _locked_extra(stored: Any, incoming: Any) -> list[str]:
    if not isinstance(incoming, dict):
        return []
    stored = stored if isinstance(stored, dict) else {}
    return [
        f"extraMetadata.{key}"
        for key, value in incoming.items()
        if key not in SERVER_EXTRA_KEYS
        and not (key in ADDITIVE_EXTRA_KEYS and isinstance(value, list))
        and not is_empty_value(stored.get(key))
        and (
            is_empty_value(value)
            or not (same_value(stored.get(key), value) or adds_only(key, stored.get(key), value))
        )
    ]


def locked_fields(record: Any, data: dict[str, Any], *, unchanged: Collection[str] = ()) -> list[str]:
    locked: list[str] = []
    for field, incoming in data.items():
        if field in unchanged or field in POLICY_FIELDS:
            continue
        if field == "extraMetadata":
            locked.extend(_locked_extra(get_value(record, field), incoming))
            continue
        if is_empty_value(get_value(record, field)):
            continue
        if is_empty_value(incoming) or (not field_unchanged(record, field, incoming) and not adds_only(
            field, get_value(record, field), incoming
        )):
            locked.append(field)
    return sorted(set(locked))


def _close(a: Any, b: Any) -> bool:
    try:
        return abs(float(a) - float(b)) < 1e-9
    except (TypeError, ValueError):
        return False


def location_compared(stored: Any, fresh: Any) -> str:
    if stored is None or fresh is None:
        return "moved"
    for key in LOCATION_POINT_KEYS:
        if not _close(get_value(stored, key), get_value(fresh, key)):
            return "moved"
    adds = False
    for key in LOCATION_STATED_KEYS:
        before = get_value(stored, key)
        after = get_value(fresh, key)
        if is_empty_value(after):
            continue
        if is_empty_value(before):
            adds = True
            continue
        if not (same_value(before, after) or _close(before, after)):
            return "moved"
    return "adds" if adds else "same"


async def settle_location(record: Any, data: dict[str, Any], *, client: Any = None) -> set[str]:
    from app.core.db import db

    stored_id = get_value(record, "locationId")
    fresh_id = data.get("locationId")
    if "locationId" not in data or is_empty_value(stored_id) or is_empty_value(fresh_id):
        return set()
    if stored_id == fresh_id:
        return {"locationId"}
    reader = client if client is not None else db
    rows = await reader.location.find_many(where={"id": {"in": [stored_id, fresh_id]}})
    by_id = {get_value(row, "id"): row for row in rows}
    verdict = location_compared(by_id.get(stored_id), by_id.get(fresh_id))
    if verdict == "same":
        data.pop("locationId", None)
        return {"locationId"}
    if verdict == "adds":
        return {"locationId"}
    return set()
