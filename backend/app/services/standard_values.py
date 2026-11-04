from __future__ import annotations

import json
import re
from functools import cache, lru_cache
from pathlib import Path
from typing import Any

STANDARD_VALUES_FILE = Path(__file__).resolve().parents[1] / "data" / "standard_values.json"

PHONE_MESSAGE_INDIA = "Enter a 10-digit number for +91."
COUNTRY_CODE_MESSAGE = "Choose a country code, such as +91."
GENDER_MESSAGE = "Choose a gender from the list."
LANGUAGE_MESSAGE = "Choose a language from the list."
TRADITION_MESSAGE = "Choose a tradition type from the list."
MATERIAL_MESSAGE = "Choose a material type from the list."

_FOLD_SEPARATORS = re.compile("[ \t\r\n ‐-―_./,()\\[\\]-]+")
_PHONE_SEPARATORS = re.compile("[ \t\r\n ‐-―().-]+")
_ASCII_DIGITS = re.compile(r"[0-9]+")
_COUNTRY_CODE = re.compile(r"\+[1-9][0-9]{0,3}")
_LANGUAGE_TAG = re.compile(r"([a-z]{2,3})[-_][a-z]{2}")


class StandardValueError(ValueError):
    def __init__(self, field: str, message: str) -> None:
        super().__init__(message)
        self.field = field
        self.message = message


def fold(text: Any) -> str:
    if text is None:
        return ""
    return _FOLD_SEPARATORS.sub(" ", str(text).lower()).strip()


@lru_cache(maxsize=1)
def shared() -> dict[str, Any]:
    return json.loads(STANDARD_VALUES_FILE.read_text(encoding="utf-8"))


def _index(list_key: str, value_key: str, label_key: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for entry in shared()[list_key]:
        stored = entry[value_key]
        for spelling in (entry["code"], entry[label_key], *entry.get("aliases", ())):
            out[fold(spelling)] = stored
    return out


@cache
def _lookup(list_key: str) -> dict[str, str]:
    if list_key == "languages":
        return _index("languages", "code", "name")
    if list_key == "genders":
        return _index("genders", "label", "label")
    return _index(list_key, "code", "label")


def _blank(value: Any) -> bool:
    return value is None or not str(value).strip()


def _normalise(list_key: str, field: str, message: str, value: Any) -> str | None:
    if _blank(value):
        return None
    found = _lookup(list_key).get(fold(value))
    if found is None and list_key == "languages":
        tag = _LANGUAGE_TAG.fullmatch(str(value).strip().lower())
        if tag:
            found = _lookup(list_key).get(tag.group(1))
    if found is None:
        raise StandardValueError(field, message)
    return found



def language_codes() -> list[str]:
    return [entry["code"] for entry in shared()["languages"]]


def language_name(code: Any) -> str | None:
    if _blank(code):
        return None
    for entry in shared()["languages"]:
        if entry["code"] == code:
            return entry["name"]
    return str(code)


def gender_labels() -> list[str]:
    return [entry["label"] for entry in shared()["genders"]]


def tradition_codes() -> list[str]:
    return [entry["code"] for entry in shared()["traditionTypes"]]


def material_family_codes() -> list[str]:
    return [entry["code"] for entry in shared()["materialFamilies"]]


TRADITION_UNANSWERED = "UNKNOWN"


def retired_tradition_codes() -> dict[str, str]:
    return dict(shared()["retiredTraditionCodes"])


def tradition_options() -> list[dict[str, str]]:
    return [
        {"code": entry["code"], "label": entry["label"]}
        for entry in shared()["traditionTypes"]
        if entry["code"] != TRADITION_UNANSWERED
    ]


def tradition_label(code: Any) -> str | None:
    code = retired_tradition_codes().get(code, code)
    for entry in shared()["traditionTypes"]:
        if entry["code"] == code:
            return entry["label"]
    return None


def material_family_label(code: Any) -> str | None:
    for entry in shared()["materialFamilies"]:
        if entry["code"] == code:
            return entry["label"]
    return None



def normalize_language(value: Any) -> str | None:
    return _normalise("languages", "language", LANGUAGE_MESSAGE, value)


def normalize_gender(value: Any) -> str | None:
    return _normalise("genders", "gender", GENDER_MESSAGE, value)


def normalize_tradition(value: Any) -> str | None:
    return _normalise("traditionTypes", "traditionType", TRADITION_MESSAGE, value)


def normalize_material_family(value: Any) -> str | None:
    return _normalise("materialFamilies", "materialFamily", MATERIAL_MESSAGE, value)


def material_family_from_text(text: Any) -> str | None:
    folded = f" {fold(text)} "
    if not folded.strip():
        return None
    found: set[str] = set()
    for spelling, code in _lookup("materialFamilies").items():
        if code == "OTHER" or not spelling:
            continue
        if f" {spelling} " in folded:
            found.add(code)
    return found.pop() if len(found) == 1 else None



def default_country_code() -> str:
    return shared()["phone"]["defaultCountryCode"]


def _digit_window(code: str) -> tuple[int, int]:
    rules = shared()["phone"]
    if code in rules["nationalDigits"]:
        low, high = rules["nationalDigits"][code]
        return low, high
    low, high = rules["otherNationalDigits"]
    return low, min(high, rules["e164MaxDigits"] - (len(code) - 1))


def phone_message(code: str) -> str:
    if code == "+91":
        return PHONE_MESSAGE_INDIA
    low, high = _digit_window(code)
    return f"Enter a valid phone number ({low}–{high} digits)."


def normalize_country_code(value: Any) -> str:
    if _blank(value):
        return default_country_code()
    text = _PHONE_SEPARATORS.sub("", str(value))
    if text.startswith("00"):
        text = "+" + text[2:]
    elif not text.startswith("+"):
        text = "+" + text
    if not _COUNTRY_CODE.fullmatch(text):
        raise StandardValueError("phoneCountryCode", COUNTRY_CODE_MESSAGE)
    return text


def normalize_phone(country_code: Any, number: Any) -> tuple[str | None, str | None]:
    if _blank(number):
        return None, None
    raw = str(number).strip()
    code = normalize_country_code(country_code)
    compact = _PHONE_SEPARATORS.sub("", raw)
    if compact.startswith("00") and len(compact) > 2 and compact[2] != "0":
        compact = "+" + compact[2:]
    if compact.startswith("+"):
        if compact.startswith(code):
            compact = compact[len(code) :]
        elif compact.startswith("+91"):
            code, compact = "+91", compact[3:]
        else:
            head = re.split("[ \t ‐-―-]", raw, maxsplit=1)
            embedded = _PHONE_SEPARATORS.sub("", head[0]) if len(head) == 2 else ""
            if not _COUNTRY_CODE.fullmatch(embedded):
                raise StandardValueError("phone", phone_message(code))
            code, compact = embedded, compact[len(embedded) :]
    if not _ASCII_DIGITS.fullmatch(compact or "x"):
        raise StandardValueError("phone", phone_message(code))
    digits = compact
    code_digits = code[1:]
    low, high = _digit_window(code)
    if len(digits) > high and digits.startswith(code_digits):
        digits = digits[len(code_digits) :]
    if len(digits) > low and digits.startswith("0"):
        digits = digits[1:]
    if not low <= len(digits) <= high:
        raise StandardValueError("phone", phone_message(code))
    return code, digits


def split_legacy_phone(stored: Any) -> tuple[str, str]:
    text = "" if stored is None else str(stored).strip()
    try:
        code, digits = normalize_phone(None, text)
        return code or default_country_code(), digits or ""
    except StandardValueError:
        return default_country_code(), re.sub(r"[^0-9]", "", text)


def format_phone(country_code: Any, number: Any) -> str | None:
    if _blank(number):
        return None
    try:
        code, digits = normalize_phone(country_code, number)
    except StandardValueError:
        return str(number).strip()
    if code == "+91" and digits and len(digits) == 10:
        return f"{code} {digits[:5]} {digits[5:]}"
    return f"{code} {digits}"


def compose_phone(country_code: Any, number: Any) -> str | None:
    if _blank(number):
        return None
    try:
        code, digits = normalize_phone(country_code, number)
    except StandardValueError:
        return str(number).strip()
    return f"{code} {digits}"


def phone_of(row: Any) -> str | None:
    get = row.get if isinstance(row, dict) else lambda key: getattr(row, key, None)
    return format_phone(get("phoneCountryCode"), get("phone"))



def _stored(stored: Any, key: str) -> Any:
    if stored is None:
        return None
    if isinstance(stored, dict):
        return stored.get(key)
    return getattr(stored, key, None)


def _loose_pair(code: Any, number: Any) -> tuple[str, str]:
    text = "" if number is None else str(number).strip()
    if not _blank(code) and not text.startswith("+"):
        text = f"{code} {text}"
    return split_legacy_phone(text)


def apply_phone(data: dict[str, Any], stored: Any = None) -> dict[str, Any]:
    if "phone" not in data and "phoneCountryCode" not in data:
        return data
    if "phone" not in data and _blank(data["phoneCountryCode"]):
        data["phoneCountryCode"] = None
        return data
    number = data.get("phone", _stored(stored, "phone"))
    code = data.get("phoneCountryCode", _stored(stored, "phoneCountryCode"))
    if _blank(number):
        if "phone" in data:
            data["phone"] = None
            data["phoneCountryCode"] = None
        else:
            data.pop("phoneCountryCode", None)
        return data
    unchanged = stored is not None and _loose_pair(code, number) == _loose_pair(
        _stored(stored, "phoneCountryCode"), _stored(stored, "phone")
    )
    try:
        code, digits = normalize_phone(code, number)
    except StandardValueError:
        if unchanged:
            data.pop("phone", None)
            data.pop("phoneCountryCode", None)
            return data
        raise
    data["phone"] = digits
    data["phoneCountryCode"] = code
    return data


def _apply(data: dict[str, Any], stored: Any, key: str, normaliser: Any) -> None:
    if key not in data:
        return
    value = data[key]
    if _blank(value):
        data[key] = None
        return
    try:
        data[key] = normaliser(value)
    except StandardValueError:
        if value == _stored(stored, key):
            data.pop(key)
            return
        raise


def apply_artisan_values(data: dict[str, Any], stored: Any = None) -> dict[str, Any]:
    apply_phone(data, stored)
    _apply(data, stored, "gender", normalize_gender)
    return data


def apply_tool_values(data: dict[str, Any], stored: Any = None) -> dict[str, Any]:
    _apply(data, stored, "materialFamily", normalize_material_family)
    if "traditionType" in data:
        if _blank(data["traditionType"]):
            data["traditionType"] = "UNKNOWN"
        else:
            _apply(data, stored, "traditionType", normalize_tradition)
    return data


def apply_interview_values(data: dict[str, Any], stored: Any = None) -> dict[str, Any]:
    _apply(data, stored, "language", normalize_language)
    return data


def catalogue() -> dict[str, Any]:
    values = shared()
    return {
        "version": values["version"],
        "phone": values["phone"],
        "languages": [{"code": e["code"], "name": e["name"]} for e in values["languages"]],
        "genders": [{"code": e["code"], "label": e["label"]} for e in values["genders"]],
        "traditionTypes": [
            {"code": e["code"], "label": e["label"]} for e in values["traditionTypes"]
        ],
        "retiredTraditionCodes": values["retiredTraditionCodes"],
        "materialFamilies": [
            {"code": e["code"], "label": e["label"]} for e in values["materialFamilies"]
        ],
    }
