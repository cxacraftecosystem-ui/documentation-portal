from __future__ import annotations

import re

_D = (
    (0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    (1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    (2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
    (3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    (4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
    (5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    (6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
    (7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    (8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
    (9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
)

_P = (
    (0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    (1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    (5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
    (8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    (9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
    (4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    (2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
    (7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
)

AADHAAR_LENGTH = 12

_SEPARATORS = re.compile(r"[\s‐-―-]+")
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")

_ASCII_DIGITS = re.compile(r"[0-9]+")

PEHCHAN_MIN_LENGTH = 4
PEHCHAN_MAX_LENGTH = 32


def verhoeff_ok(digits: str) -> bool:
    checksum = 0
    for index, char in enumerate(reversed(digits)):
        checksum = _D[checksum][_P[index % 8][int(char)]]
    return checksum == 0


def normalize_aadhaar(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = _SEPARATORS.sub("", str(value)).strip()
    return cleaned or None


def aadhaar_error(value: str | None) -> str | None:
    if value is None:
        return None
    if not _ASCII_DIGITS.fullmatch(value):
        return "Aadhaar number must be 12 digits — remove any letters or symbols."
    if len(value) != AADHAAR_LENGTH:
        return f"Aadhaar number must be exactly 12 digits (this one has {len(value)})."
    if value[0] in "01":
        return "Aadhaar numbers never start with 0 or 1 — please re-check the first digit."
    if not verhoeff_ok(value):
        return (
            "That Aadhaar number fails its checksum, so at least one digit is wrong. "
            "Please re-read the card and enter it again."
        )
    return None


def validate_aadhaar(value: str | None) -> str | None:
    normalized = normalize_aadhaar(value)
    error = aadhaar_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def require_aadhaar(value: str) -> str:
    normalized = normalize_aadhaar(value)
    if not normalized:
        raise ValueError(
            "Enter the artisan's 12-digit Aadhaar number — it is what keeps the same artisan from "
            "being recorded twice."
        )
    error = aadhaar_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def mask_aadhaar(value: str | None) -> str | None:
    normalized = normalize_aadhaar(value)
    if not normalized:
        return None
    if len(normalized) < 4:
        return "XXXX XXXX XXXX"
    return f"XXXX XXXX {normalized[-4:]}"


def is_masked_aadhaar(value: str | None) -> bool:
    if value is None:
        return False
    return "X" in str(value).upper()


def normalize_pehchan(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = _NON_ALNUM.sub("", str(value)).upper().strip()
    return cleaned or None


def pehchan_error(value: str | None) -> str | None:
    if value is None:
        return None
    if not value.isalnum():
        return "Pehchan card number must be letters and digits only."
    if not PEHCHAN_MIN_LENGTH <= len(value) <= PEHCHAN_MAX_LENGTH:
        return (
            f"Pehchan card number must be between {PEHCHAN_MIN_LENGTH} and "
            f"{PEHCHAN_MAX_LENGTH} characters."
        )
    return None


def validate_pehchan(value: str | None) -> str | None:
    normalized = normalize_pehchan(value)
    error = pehchan_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def resolve_pehchan_fields(
    available: bool | None, number: str | None
) -> tuple[bool | None, str | None]:
    if available is False:
        return False, None
    return available, number
