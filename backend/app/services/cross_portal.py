"""One artisan across both portals (C27): the rules both servers apply, in one module.

── WHAT IT DOES ─────────────────────────────────────────────────────────────────────────────────

A designer entering an artisan the documentation portal already holds types the Aadhaar number (or
the Pehchan card number) and asks the documentation portal for the rest. The documentation portal
answers with the identity fields of the ONE artisan that matches, if that artisan's sharing consent
is recorded there; the designer's form is filled, the designer saves, and the two records are linked.
The documentation portal is the source of truth for the identity fields: a linked designer-portal
record is refreshed FROM it, never the other way round.

── THE THREE RULES, AND WHERE EACH LIVES ────────────────────────────────────────────────────────

1. **The raw Aadhaar number never leaves either system.** The designer portal sends
   ``HMAC-SHA256(key, the 12 digits)``; the documentation portal computes the same code over the
   artisans whose consent it holds and compares. The key is a secret both servers share
   (``CROSS_PORTAL_AADHAAR_HMAC_KEY``) and nothing else knows, so the code cannot be reversed by
   hashing all 10^12 numbers — that is why it is an HMAC and not a plain hash. :func:`aadhaar_code`.
2. **Both servers prove who they are.** The caller sends ``Authorization: Bearer <token>``; the
   answer carries ``X-Cross-Portal-Signature``, an HMAC of the exact body bytes under the same token,
   which the caller checks before it reads a word. A man in the middle holding neither can neither
   ask nor answer. :func:`bearer_matches`, :func:`sign`, :func:`signature_matches`.
3. **Nothing works until both servers are configured.** :func:`active` is the flag AND both secrets
   (32 characters or more each); the designer portal adds its URL for the other server. A server
   that is not active answers 404 on every route here, so a half-configured pair fails closed.

Consent is recorded per artisan on the documentation portal (``ArtisanSharingConsent``) and per link
on the designer portal (``CrossPortalArtisanLink``, who recorded the artisan's agreement and when).

Both portals carry this module under the same name, byte for byte; change both.
"""

from __future__ import annotations

import hashlib
import hmac
from typing import Any

#: Shortest secret either server accepts. A shorter one leaves the feature off.
MIN_SECRET_LENGTH = 32

#: The header the answering server signs its body in.
SIGNATURE_HEADER = "X-Cross-Portal-Signature"

#: ``CrossPortalArtisanLink.matchedBy`` values.
MATCHED_BY_PEHCHAN = "PEHCHAN"
MATCHED_BY_AADHAAR = "AADHAAR"
MATCHED_BY = (MATCHED_BY_PEHCHAN, MATCHED_BY_AADHAAR)

#: The fields the documentation portal is the source of truth for. A refresh copies exactly these.
IDENTITY_FIELDS: tuple[str, ...] = (
    "name",
    "localName",
    "gender",
    "dateOfBirth",
    "pehchanCardAvailable",
    "pehchanCardNumber",
)

#: Offered on the first fill as well, and editable after it: facts about the artisan's craft and
#: contact that the designer may know better on the day. Never copied by a refresh.
SUGGESTED_FIELDS: tuple[str, ...] = (
    "craftStartDate",
    "experienceYears",
    "experienceMonths",
    "phone",
    "place",
)

#: Everything a lookup answer may carry, and nothing else: no Aadhaar, no notes, no review state.
SHARED_FIELDS: tuple[str, ...] = IDENTITY_FIELDS + SUGGESTED_FIELDS


def _secret_ok(value: str | None) -> bool:
    return bool(value) and len(value.strip()) >= MIN_SECRET_LENGTH  # type: ignore[union-attr]


def active(settings: Any, *, needs_url: bool = False) -> bool:
    """True only when this server may take part: the flag on and every secret it needs set."""
    if not getattr(settings, "cross_portal_identity_enabled", False):
        return False
    if not _secret_ok(getattr(settings, "cross_portal_token", None)):
        return False
    if not _secret_ok(getattr(settings, "cross_portal_aadhaar_hmac_key", None)):
        return False
    if needs_url and not (getattr(settings, "field_repository_api_url", None) or "").strip():
        return False
    return True


def aadhaar_code(number: str, key: str) -> str:
    """``HMAC-SHA256(key, the 12 digits)`` as lower-case hex. ``number`` must already be normalised."""
    digits = "".join(ch for ch in number if ch.isdigit())
    return hmac.new(key.strip().encode("utf-8"), digits.encode("ascii"), hashlib.sha256).hexdigest()


def sign(token: str, body: bytes) -> str:
    """The signature the answering server puts on its body."""
    return hmac.new(token.strip().encode("utf-8"), body, hashlib.sha256).hexdigest()


def signature_matches(token: str, body: bytes, signature: str | None) -> bool:
    return bool(signature) and hmac.compare_digest(sign(token, body), signature or "")


def codes_match(mine: str, theirs: str | None) -> bool:
    """Constant-time comparison of two Aadhaar codes."""
    return bool(theirs) and hmac.compare_digest(mine, (theirs or "").strip().lower())


def bearer_matches(token: str, authorization: str | None) -> bool:
    """Constant-time check of ``Authorization: Bearer <token>``."""
    if not authorization or not authorization.startswith("Bearer "):
        return False
    return hmac.compare_digest(authorization[len("Bearer ") :].strip(), token.strip())


def shared_fields(artisan: Any) -> dict[str, Any]:
    """The answer's ``fields`` for one artisan: :data:`SHARED_FIELDS`, dates as ISO strings."""
    out: dict[str, Any] = {}
    for key in SHARED_FIELDS:
        value = getattr(artisan, key, None)
        if hasattr(value, "isoformat"):
            value = value.isoformat()
        out[key] = value
    return out
