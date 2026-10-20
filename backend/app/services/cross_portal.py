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
   ``HMAC-SHA256(key, the 12 digits)``; the documentation portal compares it with the code of each
   artisan whose consent it holds. The key is ``AADHAAR_FINGERPRINT_KEY``, the artisan register's
   own fingerprint key (SSM ``/iit/shared/aadhaar-fingerprint-key``, the same value on both
   servers), and nothing else knows it, so the code cannot be reversed by hashing all 10^12
   numbers — that is why it is an HMAC and not a plain hash. :func:`aadhaar_code`. The code IS the
   stored ``Artisan.aadhaarFingerprint`` (same key, same formula), so neither portal needs the number
   to make it: :func:`stored_aadhaar_code` reads the fingerprint, and falls back to the legacy
   ``aadhaarNumber`` only for a row the fingerprint backfill has not reached. There is no separate
   cross-portal key: changing this one would orphan every stored fingerprint, so it is never rotated.
2. **Both servers prove who they are.** The caller sends ``Authorization: Bearer <token>`` and a
   fresh ``X-Cross-Portal-Nonce``; the answer carries ``X-Cross-Portal-Signature``, an HMAC under the
   same token of the REQUEST (method, peer path, the nonce) and the exact body bytes, which the
   caller checks before it reads a word. A man in the middle holding neither can neither ask nor
   answer, and a captured answer cannot be replayed against another request: its nonce is not the
   one the caller just sent. :func:`bearer_matches`, :func:`sign`, :func:`signature_matches`.
3. **Nothing works until both servers are configured.** :func:`active` is the flag AND both secrets
   (32 characters or more each); the designer portal adds its URL for the other server. A server
   that is not active answers 404 on every route here, so a half-configured pair fails closed.

What a lookup shares is :data:`SHARED_FIELDS` plus the craft and the stated address, and the words
the artisan agrees to are built from the same list (:func:`consent_fields_phrase`), so the consent
can never name less than is sent.

Consent is recorded per artisan on the documentation portal (``ArtisanSharingConsent``) and per link
on the designer portal (``CrossPortalArtisanLink``, who recorded the artisan's agreement and when).

Both portals carry this module under the same name, byte for byte; change both.
"""

from __future__ import annotations

import hashlib
import hmac
import re
from typing import Any

from app.services.standard_values import compose_phone

#: Shortest secret either server accepts. A shorter one leaves the feature off.
MIN_SECRET_LENGTH = 32

#: The header the answering server signs its body in.
SIGNATURE_HEADER = "X-Cross-Portal-Signature"

#: The caller's one-time value, echoed into the signed material so an answer fits one request only.
NONCE_HEADER = "X-Cross-Portal-Nonce"

#: The designer-portal account that asked, for the answering server's access log. Never a number.
REQUESTED_BY_HEADER = "X-Cross-Portal-Requested-By"

#: Where the peer routes live on the answering server; the signed path starts here.
PEER_PREFIX = "/cross-portal/peer"

#: Shortest nonce the answering server accepts.
MIN_NONCE_LENGTH = 16

#: ``CrossPortalArtisanLink.matchedBy`` values.
MATCHED_BY_PEHCHAN = "PEHCHAN"
MATCHED_BY_AADHAAR = "AADHAAR"
#: Linked from a scanned or typed artisan card (services/artisan_card.py).
MATCHED_BY_CARD = "CARD"
MATCHED_BY = (MATCHED_BY_PEHCHAN, MATCHED_BY_AADHAAR, MATCHED_BY_CARD)

_TWELVE_DIGITS = re.compile(r"[0-9]{12}")
_CODE = re.compile(r"[0-9a-f]{64}")

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

#: The blocks an answer carries beside ``fields``: the craft and the stated address.
ANSWER_EXTRAS: tuple[str, ...] = ("craft", "statedAddress")

#: Every shared item in the words the artisan is asked to agree to, in the order they are read out.
#: The consent copy on BOTH portals, web and Android, is built from this (``/cross-portal/status``
#: sends :func:`consent_fields_phrase`), and a test holds its keys to SHARED_FIELDS + ANSWER_EXTRAS.
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
    """"name, local name, …, craft and craft experience" — what the artisan agrees to share."""
    labels: list[str] = []
    for label in SHARED_FIELD_LABELS.values():
        if label not in labels:
            labels.append(label)
    return ", ".join(labels[:-1]) + " and " + labels[-1]


def _secret_ok(value: str | None) -> bool:
    return bool(value) and len(value.strip()) >= MIN_SECRET_LENGTH  # type: ignore[union-attr]


def active(settings: Any, *, needs_url: bool = False) -> bool:
    """True only when this server may take part: the flag on and every secret it needs set."""
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
    """The key Aadhaar codes are made under: ``AADHAAR_FINGERPRINT_KEY``. Never logged or returned."""
    return (getattr(settings, "aadhaar_fingerprint_key", None) or "").strip() or None


def aadhaar_code(number: str, key: str) -> str:
    """``HMAC-SHA256(key, the 12 digits)`` as lower-case hex. ``number`` must already be normalised.

    Byte for byte ``aadhaar_fingerprint.fingerprint``: what the register stores as
    ``Artisan.aadhaarFingerprint`` is this code.
    """
    digits = "".join(ch for ch in number if ch.isdigit())
    return hmac.new(key.strip().encode("utf-8"), digits.encode("ascii"), hashlib.sha256).hexdigest()


def stored_aadhaar_code(artisan: Any, key: str | None) -> str | None:
    """The code for the Aadhaar an artisan row holds, or ``None`` when it holds none.

    The stored ``aadhaarFingerprint`` first: it is already the code. Otherwise the legacy
    ``aadhaarNumber``, for a row the fingerprint backfill has not reached (or a schema without the
    fingerprint columns). Read with ``getattr`` so this works before and after either column exists.
    """
    stored = getattr(artisan, "aadhaarFingerprint", None)
    if isinstance(stored, str) and _CODE.fullmatch(stored):
        return stored
    legacy = getattr(artisan, "aadhaarNumber", None)
    if key and isinstance(legacy, str) and _TWELVE_DIGITS.fullmatch(legacy):
        return aadhaar_code(legacy, key)
    return None


def peer_path(path: str) -> str:
    """The part of a request path the signature covers: from ``/cross-portal/peer`` on.

    The answering server may sit behind a proxy that adds a prefix (``/api``); both sides sign the
    same suffix whatever is in front of it.
    """
    index = path.find(PEER_PREFIX)
    return path[index:] if index >= 0 else path


def sign(token: str, body: bytes, *, method: str, path: str, nonce: str) -> str:
    """The signature the answering server puts on its body, bound to the request it answers."""
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
        if key == "phone":
            # One string, "+91 9876543210", which either portal's save splits into its country
            # code and digits whichever release the peer is on.
            value = compose_phone(getattr(artisan, "phoneCountryCode", None), value)
        if hasattr(value, "isoformat"):
            value = value.isoformat()
        out[key] = value
    return out

# ── ONE SEARCH ACROSS BOTH PORTALS (docs/SEARCH.md) ─────────────────────────────────────────────
#
# The same signed channel, now two-way: each portal answers the other's search over
# ``POST /cross-portal/peer/search`` and ``.../search/record``. What crosses is a text projection of
# each hit (:data:`PEER_ITEM_KEYS`) — never a file, a URL to one, or an identity field — and only for
# records the answering portal marks shareable and whose artisans' consent is live.

#: Every search type, in the order groups are shown. Both portals use this vocabulary; clusters,
#: designWorkshops and reports exist on the designer portal only.
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

#: Who may see the other portal's results. No field contributor, no crowdsource volunteer.
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

#: The keys one peer search item may carry, and nothing else.
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
)


def peer_api_url(settings: Any) -> str:
    """The other portal's API base up to ``/api``: FIELD_REPOSITORY_API_URL on the designer portal,
    DESIGNER_PORTAL_API_URL on the Field Repository. Empty when neither is set."""
    for name in ("field_repository_api_url", "designer_portal_api_url"):
        value = (getattr(settings, name, None) or "").strip()
        if value:
            return value.rstrip("/")
    return ""


def search_active(settings: Any) -> bool:
    """Cross-portal search may run: its own flag, both shared secrets, and the other portal's URL."""
    if not getattr(settings, "cross_portal_search_enabled", False):
        return False
    if not _secret_ok(getattr(settings, "cross_portal_token", None)):
        return False
    if not _secret_ok(aadhaar_key(settings)):
        return False
    return bool(peer_api_url(settings))
