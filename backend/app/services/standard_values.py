"""Standard values shared by both portals (convergence point 10): phone, language, gender, tool
tradition and material family.

The lists live in ``app/data/standard_values.json``. That file, this module and
``tests/test_standard_values.py`` are byte-identical in the designer portal and the documentation
portal, and ``shared/standard-values-vectors.json`` (repository root) holds the input -> output
pairs the server, the web client and the Android app are all tested against. Change both
repositories together.

WHAT IS STORED
--------------
* Phone: the country code in ``phoneCountryCode`` (``"+91"`` when none is given) and the national
  number as ASCII digits only in ``phone``. ``+91`` takes exactly 10 digits; any other code takes
  4 to 14 digits, and never more than E.164's 15 digits counting the code. Shown as
  ``"+91 98765 43210"``.
* Language: a standard code (ISO 639-1, or ISO 639-2/3 where a language has no two-letter code),
  shown by its name. ``"Bengali"`` is the standard spelling, so ``"Bangla"`` and ``"Bengla"`` store
  ``"bn"``.
* Gender: the label (``"Male"``, ``"Female"``, ``"Transgender"``, ``"Other"``), which is what both
  portals have always stored and printed. The code beside it is for the merged platform.
* Tool tradition: the ``TraditionType`` enum code, one of three answers shown as "Traditional",
  "Contemporary" and "Transitional (Hybrid)" (owner decision, 2026-10-19), the same three tokens
  as the designer portal's stage registry; ``UNKNOWN`` is the unanswered default and is never
  offered. ``MODERN`` and ``HYBRID``, the codes before that decision, stay in the enum so older rows
  and older builds still load; they read as their successors (``retiredTraditionCodes``), and the
  normalisation script rewrites the rows.
* Tool material family: a code from the material list, beside the free ``material`` description.

HOW INPUT IS MATCHED
--------------------
:func:`fold` lower-cases and turns every run of spaces and the punctuation people type between
words into one space. It is written with an explicit character class, not ``\\s``, because ``\\s``
means a different set in Python, JavaScript and Kotlin, and the three clients must agree on every
input. Nothing is guessed: an input that matches no code, label or alias is refused, or, in the
normalisation scripts, reported for the owner.

A SAVE THAT RE-SENDS A STORED LEGACY VALUE IS NOT REFUSED. An editor who opens an old record and
saves it re-sends whatever is stored. The ``apply_*`` helpers leave a value alone when it equals
the stored one, so legacy rows stay editable until the normalisation script cleans them.

PURE: imports nothing from the app, so the scripts and the DB-free tests can use it.
"""

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

#: Spaces, the no-break space, ASCII and Unicode hyphens/dashes, and the punctuation people type
#: between words. Spelled out so Python, JavaScript and Kotlin match the same set.
_FOLD_SEPARATORS = re.compile("[ \t\r\n ‐-―_./,()\\[\\]-]+")
#: What may sit between the digits of a phone number.
_PHONE_SEPARATORS = re.compile("[ \t\r\n ‐-―().-]+")
_ASCII_DIGITS = re.compile(r"[0-9]+")
_COUNTRY_CODE = re.compile(r"\+[1-9][0-9]{0,3}")
_LANGUAGE_TAG = re.compile(r"([a-z]{2,3})[-_][a-z]{2}")


class StandardValueError(ValueError):
    """A value that is not on its list. ``field`` is the column, ``message`` the sentence to show."""

    def __init__(self, field: str, message: str) -> None:
        super().__init__(message)
        self.field = field
        self.message = message


def fold(text: Any) -> str:
    """The form inputs are compared in: lower case, separators collapsed to one space, trimmed."""
    if text is None:
        return ""
    return _FOLD_SEPARATORS.sub(" ", str(text).lower()).strip()


@lru_cache(maxsize=1)
def shared() -> dict[str, Any]:
    return json.loads(STANDARD_VALUES_FILE.read_text(encoding="utf-8"))


def _index(list_key: str, value_key: str, label_key: str) -> dict[str, str]:
    """folded code, label and every alias -> the stored value."""
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


# ── Lists ─────────────────────────────────────────────────────────────────────────────────────


def language_codes() -> list[str]:
    return [entry["code"] for entry in shared()["languages"]]


def language_name(code: Any) -> str | None:
    """The name a stored language code is shown by; a legacy free-text value is shown as stored."""
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


#: The stored state of a tradition question nobody answered. Never one of the offered answers.
TRADITION_UNANSWERED = "UNKNOWN"


def retired_tradition_codes() -> dict[str, str]:
    """``{"MODERN": "CONTEMPORARY", "HYBRID": "TRANSITIONAL"}``: enum members no form writes now."""
    return dict(shared()["retiredTraditionCodes"])


def tradition_options() -> list[dict[str, str]]:
    """The answers a form offers, in order: every entry but the unanswered default."""
    return [
        {"code": entry["code"], "label": entry["label"]}
        for entry in shared()["traditionTypes"]
        if entry["code"] != TRADITION_UNANSWERED
    ]


def tradition_label(code: Any) -> str | None:
    """The label a stored code is shown by; a retired code reads as its successor's label."""
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


# ── Normalisers (raise StandardValueError; blank -> None) ────────────────────────────────────


def normalize_language(value: Any) -> str | None:
    return _normalise("languages", "language", LANGUAGE_MESSAGE, value)


def normalize_gender(value: Any) -> str | None:
    return _normalise("genders", "gender", GENDER_MESSAGE, value)


def normalize_tradition(value: Any) -> str | None:
    return _normalise("traditionTypes", "traditionType", TRADITION_MESSAGE, value)


def normalize_material_family(value: Any) -> str | None:
    return _normalise("materialFamilies", "materialFamily", MATERIAL_MESSAGE, value)


def material_family_from_text(text: Any) -> str | None:
    """The one material family a free description names, or None when it names none or several.

    "Iron" -> METAL; "mango wood with an iron collar" names WOOD and METAL, so None. Used by the
    normalisation scripts to PROPOSE a family; the write path never fills it in by itself.
    """
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


# ── Phone ────────────────────────────────────────────────────────────────────────────────────


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
    """``"+91"``, ``"91"`` and ``"0091"`` -> ``"+91"``; blank -> the default. Raises when malformed."""
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
    """``(code, digits)`` for a number typed in any common form; ``(None, None)`` when blank.

    Accepted for +91: ``9876543210``, ``09876543210``, ``919876543210``, ``+919876543210``,
    ``+91 98765 43210``, ``98765-43210``. A number that carries its own ``+code`` keeps that code
    (that is how older app builds send it). Raises :class:`StandardValueError` when the number is
    not a phone number for that code.
    """
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
            # "+44 2071234567": the code is what comes before the first separator.
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
    """A stored value from before the country-code column, as ``(code, digits)``, unvalidated."""
    text = "" if stored is None else str(stored).strip()
    try:
        code, digits = normalize_phone(None, text)
        return code or default_country_code(), digits or ""
    except StandardValueError:
        return default_country_code(), re.sub(r"[^0-9]", "", text)


def format_phone(country_code: Any, number: Any) -> str | None:
    """``"+91 98765 43210"``. A legacy value that does not normalise is shown as stored."""
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
    """``"+91 9876543210"``: the one-string shape the designer portal's stage boxes carry."""
    if _blank(number):
        return None
    try:
        code, digits = normalize_phone(country_code, number)
    except StandardValueError:
        return str(number).strip()
    return f"{code} {digits}"


def phone_of(row: Any) -> str | None:
    """:func:`format_phone` for a record (an object or a dict) carrying both columns."""
    get = row.get if isinstance(row, dict) else lambda key: getattr(row, key, None)
    return format_phone(get("phoneCountryCode"), get("phone"))


# ── Write-path helpers (mutate ``data``; raise StandardValueError) ───────────────────────────


def _stored(stored: Any, key: str) -> Any:
    if stored is None:
        return None
    if isinstance(stored, dict):
        return stored.get(key)
    return getattr(stored, key, None)


def _loose_pair(code: Any, number: Any) -> tuple[str, str]:
    """The same number however a client re-sends it: the stored one-string form, or code + digits."""
    text = "" if number is None else str(number).strip()
    if not _blank(code) and not text.startswith("+"):
        text = f"{code} {text}"
    return split_legacy_phone(text)


def apply_phone(data: dict[str, Any], stored: Any = None) -> dict[str, Any]:
    """Write ``phone`` as digits and ``phoneCountryCode`` beside it.

    Only acts when the body carries either key. Re-sending the stored value unchanged is not
    refused, so a legacy row stays editable. Clearing the number clears the code.
    """
    if "phone" not in data and "phoneCountryCode" not in data:
        return data
    if "phone" not in data and _blank(data["phoneCountryCode"]):
        # An explicit null on the code alone clears it; the number then reads as +91, the default.
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
    """What ``GET /reference/standard-values`` serves: the lists, without the input aliases."""
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
