"""The artisan card's code: an opaque, signed token both portals issue and both can read.

── WHAT IS ON A CARD ────────────────────────────────────────────────────────────────────────────

A printed artisan card carries a QR code and, under it, a short code a person can type. Neither holds
anything about the artisan: not the record id, not a number, not a name. Both are made of

* ``code`` — 10 Crockford base32 characters (50 bits) from :mod:`secrets`, unique per card and
  never derived from the artisan;
* ``issuer`` — ``D`` for the Design Prototype Workshop portal, ``F`` for the Field Repository
  (``settings.artisan_card_issuer``), never reused;
* ``keyVersion`` — one digit, the version of ``ARTISAN_CARD_KEY`` that signed the QR;
* ``tag`` — the first 10 bytes of ``HMAC-SHA256(ARTISAN_CARD_KEY, "CXA1:" + issuer + keyVersion +
  ":" + code)``, as 16 Crockford characters. Only the issuing server holds the key, so only it can
  tell a printed QR from a made-up one. Clients never see the key.

QR payload: ``CXA1:<issuer><keyVersion>:<code>:<tag>`` (35 characters, all in the QR alphanumeric
set, drawn at error level Q). Short code: ``<issuer>-<code[0:5]>-<code[5:10]>-<check>``, where the
check is FNV-1a 32-bit over ``"CXA1:" + issuer + ":" + code``, mod 1024, as 2 Crockford characters.
The check catches a mistyped character before anything is looked up.

The parse rules are the same in Python, TypeScript (``frontend/lib/artisanCard.ts``) and Kotlin
(``data/ArtisanCardCode.kt``), held together by ``shared/artisan-card-vectors.json``.

Both portals carry this module under the same name, byte for byte; change both.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
from dataclasses import dataclass
from typing import Any

#: Crockford base32: no I, L, O or U.
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_INDEX = {ch: i for i, ch in enumerate(ALPHABET)}

PREFIX = "CXA1"
ISSUERS = ("D", "F")
CODE_LENGTH = 10
TAG_LENGTH = 16
CHECK_LENGTH = 2
SHORT_LENGTH = 1 + CODE_LENGTH + CHECK_LENGTH

#: Shortest key accepted. 32 random bytes in base64 is 44 characters.
MIN_KEY_LENGTH = 32

#: Why a parse failed. Every one is the same 422 sentence on the resolve route.
NOT_A_CARD = "NOT_A_CARD"
BAD_CHECK = "BAD_CHECK"
BAD_ISSUER = "BAD_ISSUER"
BAD_CHARACTER = "BAD_CHARACTER"

_CONFUSABLES = str.maketrans({"I": "1", "L": "1", "O": "0"})
_STRIPPED = str.maketrans("", "", " \t\r\n-.")


class CardParseError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class ParsedCard:
    issuer: str
    code: str
    keyVersion: int | None
    tag: str | None
    typed: bool

    def as_dict(self) -> dict[str, Any]:
        return {
            "issuer": self.issuer,
            "code": self.code,
            "keyVersion": self.keyVersion,
            "tag": self.tag,
            "typed": self.typed,
        }


def encode_base32(data: bytes) -> str:
    """Big-endian Crockford base32 of ``data``; a partial last group is padded with zero bits."""
    bits = 0
    value = 0
    out: list[str] = []
    for byte in data:
        value = (value << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append(ALPHABET[(value >> bits) & 31])
    if bits:
        out.append(ALPHABET[(value << (5 - bits)) & 31])
    return "".join(out)


def new_code() -> str:
    """10 random Crockford characters (50 bits)."""
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def fnv1a32(text: str) -> int:
    h = 0x811C9DC5
    for byte in text.encode("ascii"):
        h ^= byte
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def check(issuer: str, code: str) -> str:
    value = fnv1a32(f"{PREFIX}:{issuer}:{code}") % 1024
    return ALPHABET[value >> 5] + ALPHABET[value & 31]


def tag(key: str, issuer: str, key_version: int, code: str) -> str:
    material = f"{PREFIX}:{issuer}{key_version}:{code}".encode("ascii")
    digest = hmac.new(key.strip().encode("utf-8"), material, hashlib.sha256).digest()
    return encode_base32(digest[:10])


def short_code(issuer: str, code: str) -> str:
    return f"{issuer}-{code[:5]}-{code[5:]}-{check(issuer, code)}"


def payload(key: str, issuer: str, key_version: int, code: str) -> str:
    return f"{PREFIX}:{issuer}{key_version}:{code}:{tag(key, issuer, key_version, code)}"


def tag_matches(key: str, issuer: str, key_version: int, code: str, given: str) -> bool:
    return hmac.compare_digest(tag(key, issuer, key_version, code), given)


def _crockford(text: str) -> bool:
    return all(ch in _INDEX for ch in text)


def parse(text: Any) -> ParsedCard:
    """A full QR payload or a typed short code, or :class:`CardParseError` with the reason.

    Upper-cased; spaces, ``-`` and ``.`` dropped; ``I``/``L`` read as ``1`` and ``O`` as ``0``
    (every character of a card is Crockford, so the mapping cannot change a valid code). ``U`` is
    refused. Anything with a ``:`` that is not ``CXA1:`` (an old ``DPW1:`` code) is NOT_A_CARD, so
    the caller can hand it to the old decoder.
    """
    if not isinstance(text, str):
        raise CardParseError(NOT_A_CARD)
    cleaned = text.upper().translate(_STRIPPED)
    if not cleaned:
        raise CardParseError(NOT_A_CARD)
    if cleaned.startswith(PREFIX + ":"):
        parts = cleaned.split(":")
        if len(parts) != 4 or len(parts[1]) != 2:
            raise CardParseError(NOT_A_CARD)
        head, body, mac = parts[1], parts[2], parts[3]
        if "U" in body or "U" in mac:
            raise CardParseError(BAD_CHARACTER)
        issuer, version = head[0], head[1]
        if issuer not in ISSUERS:
            raise CardParseError(BAD_ISSUER)
        if version not in "123456789":
            raise CardParseError(NOT_A_CARD)
        body = body.translate(_CONFUSABLES)
        mac = mac.translate(_CONFUSABLES)
        if len(body) != CODE_LENGTH or len(mac) != TAG_LENGTH:
            raise CardParseError(NOT_A_CARD)
        if not _crockford(body) or not _crockford(mac):
            raise CardParseError(NOT_A_CARD)
        return ParsedCard(issuer, body, int(version), mac, False)
    if ":" in cleaned:
        raise CardParseError(NOT_A_CARD)
    if "U" in cleaned:
        raise CardParseError(BAD_CHARACTER)
    mapped = cleaned.translate(_CONFUSABLES)
    if len(mapped) != SHORT_LENGTH or not _crockford(mapped):
        raise CardParseError(NOT_A_CARD)
    issuer, body, given = mapped[0], mapped[1 : 1 + CODE_LENGTH], mapped[1 + CODE_LENGTH :]
    if issuer not in ISSUERS:
        raise CardParseError(BAD_ISSUER)
    if check(issuer, body) != given:
        raise CardParseError(BAD_CHECK)
    return ParsedCard(issuer, body, None, None, True)


def _key_ok(value: Any) -> bool:
    return isinstance(value, str) and len(value.strip()) >= MIN_KEY_LENGTH


def active(settings: Any) -> bool:
    """The flag AND a key of 32 characters or more. Off: every card route answers 404."""
    if not getattr(settings, "artisan_cards_enabled", False):
        return False
    return _key_ok(getattr(settings, "artisan_card_key", None))


def issuer_of(settings: Any) -> str:
    return (getattr(settings, "artisan_card_issuer", None) or "").strip().upper()[:1]


def key_version(settings: Any) -> int:
    return int(getattr(settings, "artisan_card_key_version", 1) or 1)


def key_for_version(settings: Any, version: int | None) -> str | None:
    """The key that signed ``version``: the current one, or the previous one after a rotation."""
    current = key_version(settings)
    if version == current:
        return (getattr(settings, "artisan_card_key", None) or "").strip() or None
    previous = getattr(settings, "artisan_card_key_previous", None)
    if version == current - 1 and _key_ok(previous):
        return previous.strip()
    return None


def token_for(settings: Any, code: str) -> str:
    """The QR payload for ``code`` under the current key."""
    return payload(settings.artisan_card_key, issuer_of(settings), key_version(settings), code)


def verify(settings: Any, parsed: ParsedCard) -> bool:
    """True when the parsed card's tag (if it has one) was made by this server's key."""
    if parsed.tag is None:
        return True
    key = key_for_version(settings, parsed.keyVersion)
    if key is None or parsed.keyVersion is None:
        return False
    return tag_matches(key, parsed.issuer, parsed.keyVersion, parsed.code, parsed.tag)
