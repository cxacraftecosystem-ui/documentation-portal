from __future__ import annotations

import hashlib
import hmac
import re
from typing import Any

from app.services.standard_values import compose_phone

MIN_SECRET_LENGTH = 32

SIGNATURE_HEADER = "X-Cross-Portal-Signature"

NONCE_HEADER = "X-Cross-Portal-Nonce"

REQUESTED_BY_HEADER = "X-Cross-Portal-Requested-By"

PEER_PREFIX = "/cross-portal/peer"

MIN_NONCE_LENGTH = 16

MATCHED_BY_PEHCHAN = "PEHCHAN"
MATCHED_BY_AADHAAR = "AADHAAR"
MATCHED_BY_CARD = "CARD"
MATCHED_BY = (MATCHED_BY_PEHCHAN, MATCHED_BY_AADHAAR, MATCHED_BY_CARD)

_TWELVE_DIGITS = re.compile(r"[0-9]{12}")
_CODE = re.compile(r"[0-9a-f]{64}")

IDENTITY_FIELDS: tuple[str, ...] = (
    "name",
    "localName",
    "gender",
    "dateOfBirth",
    "pehchanCardAvailable",
    "pehchanCardNumber",
)

SUGGESTED_FIELDS: tuple[str, ...] = (
    "craftStartDate",
    "experienceYears",
    "experienceMonths",
    "phone",
    "place",
)

SHARED_FIELDS: tuple[str, ...] = IDENTITY_FIELDS + SUGGESTED_FIELDS

ANSWER_EXTRAS: tuple[str, ...] = ("craft", "statedAddress")

SHARED_FIELD_LABELS: dict[str, str] = {
    "name": "name",
    "localName": "local name",
    "gender": "gender",
    "dateOfBirth": "date of birth",
    "pehchanCardAvailable": "Pehchan card",
    "pehchanCardNumber": "Pehchan card",
    "phone": "phone number",
    "statedAddress": "address",
    "place": "place",
    "craft": "craft",
    "craftStartDate": "craft experience",
    "experienceYears": "craft experience",
    "experienceMonths": "craft experience",
}


def consent_fields_phrase() -> str:
    labels: list[str] = []
    for label in SHARED_FIELD_LABELS.values():
        if label not in labels:
            labels.append(label)
    return ", ".join(labels[:-1]) + " and " + labels[-1]


def _secret_ok(value: str | None) -> bool:
    return bool(value) and len(value.strip()) >= MIN_SECRET_LENGTH  # type: ignore[union-attr]


def active(settings: Any, *, needs_url: bool = False) -> bool:
    if not getattr(settings, "cross_portal_identity_enabled", False):
        return False
    if not _secret_ok(getattr(settings, "cross_portal_token", None)):
        return False
    if not _secret_ok(aadhaar_key(settings)):
        return False
    if needs_url and not (getattr(settings, "field_repository_api_url", None) or "").strip():
        return False
    return True


def aadhaar_key(settings: Any) -> str | None:
    return (getattr(settings, "aadhaar_fingerprint_key", None) or "").strip() or None


def aadhaar_code(number: str, key: str) -> str:
    digits = "".join(ch for ch in number if ch.isdigit())
    return hmac.new(key.strip().encode("utf-8"), digits.encode("ascii"), hashlib.sha256).hexdigest()


def stored_aadhaar_code(artisan: Any, key: str | None) -> str | None:
    stored = getattr(artisan, "aadhaarFingerprint", None)
    if isinstance(stored, str) and _CODE.fullmatch(stored):
        return stored
    legacy = getattr(artisan, "aadhaarNumber", None)
    if key and isinstance(legacy, str) and _TWELVE_DIGITS.fullmatch(legacy):
        return aadhaar_code(legacy, key)
    return None


def peer_path(path: str) -> str:
    index = path.find(PEER_PREFIX)
    return path[index:] if index >= 0 else path


def sign(token: str, body: bytes, *, method: str, path: str, nonce: str) -> str:
    head = "\n".join((method.upper(), peer_path(path), nonce, ""))
    material = head.encode("utf-8") + body
    return hmac.new(token.strip().encode("utf-8"), material, hashlib.sha256).hexdigest()


def signature_matches(
    token: str, body: bytes, signature: str | None, *, method: str, path: str, nonce: str
) -> bool:
    return bool(signature) and hmac.compare_digest(
        sign(token, body, method=method, path=path, nonce=nonce), signature or ""
    )


def codes_match(mine: str, theirs: str | None) -> bool:
    return bool(theirs) and hmac.compare_digest(mine, (theirs or "").strip().lower())


def bearer_matches(token: str, authorization: str | None) -> bool:
    if not authorization or not authorization.startswith("Bearer "):
        return False
    return hmac.compare_digest(authorization[len("Bearer ") :].strip(), token.strip())


def shared_fields(artisan: Any) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key in SHARED_FIELDS:
        value = getattr(artisan, key, None)
        if key == "phone":
            value = compose_phone(getattr(artisan, "phoneCountryCode", None), value)
        if hasattr(value, "isoformat"):
            value = value.isoformat()
        out[key] = value
    return out

SEARCH_TYPES: tuple[str, ...] = (
    "artisans",
    "crafts",
    "clusters",
    "workshops",
    "designWorkshops",
    "interviews",
    "transcripts",
    "reports",
    "products",
    "tools",
    "processes",
    "media",
)

CROSS_PORTAL_SEARCH_ROLES: frozenset[str] = frozenset(
    {
        "MASTER_ADMIN",
        "ADMIN",
        "MINISTRY_ADMIN",
        "REGIONAL_DIRECTOR",
        "ASSISTANT_DIRECTOR",
        "PROFESSOR",
        "INSPECTOR",
        "DESIGNER",
        "RESEARCHER",
    }
)

PEER_ITEM_KEYS: tuple[str, ...] = (
    "type",
    "id",
    "title",
    "subtitle",
    "snippet",
    "highlights",
    "place",
    "craftCodes",
    "recordDate",
    "openUrl",
    "startMs",
    "mediaType",
)


def peer_api_url(settings: Any) -> str:
    for name in ("field_repository_api_url", "designer_portal_api_url"):
        value = (getattr(settings, name, None) or "").strip()
        if value:
            return value.rstrip("/")
    return ""


def search_active(settings: Any) -> bool:
    if not getattr(settings, "cross_portal_search_enabled", False):
        return False
    if not _secret_ok(getattr(settings, "cross_portal_token", None)):
        return False
    if not _secret_ok(aadhaar_key(settings)):
        return False
    return bool(peer_api_url(settings))
