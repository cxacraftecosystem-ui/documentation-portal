from __future__ import annotations

import re
from collections.abc import Iterator
from typing import Any

__all__ = [
    "ACTIONS",
    "FORBIDDEN_KEYS",
    "FORBIDDEN_KEY_PARTS",
    "PERMISSION_STATEMENT",
    "PORTALS",
    "REASONS",
    "SCHEMA_VERSION",
    "STREAMS",
    "VIEWS",
    "NeverSent",
    "assert_sendable",
    "forbidden_keys",
    "other_portal",
]

SCHEMA_VERSION = 2

DESIGNER = "DESIGNER"
FIELD_REPOSITORY = "FIELD_REPOSITORY"
PORTALS = (DESIGNER, FIELD_REPOSITORY)

TRANSCRIPTS = "transcripts"
IMAGES = "images"
STREAMS = (TRANSCRIPTS, IMAGES)

VIEW_ALL = "all"
VIEW_GONE = "gone"
VIEWS = (VIEW_ALL, VIEW_GONE)

LIVE = "LIVE"
GONE = "GONE"
STATES = (LIVE, GONE)

DELETED = "DELETED"
WITHDRAWN = "WITHDRAWN"
REPLACED = "REPLACED"
MERGED = "MERGED"
UNLISTED = "UNLISTED"
REASONS = (DELETED, WITHDRAWN, REPLACED, MERGED, UNLISTED)

PAGE_CHANGES = {TRANSCRIPTS: 20, IMAGES: 100}
PAGE_BYTES = {TRANSCRIPTS: 8 * 1024 * 1024}
MAX_CURSOR_LENGTH = 512
WRITE_BATCH = 50

RATE_LIMIT_CALLS = 600
RATE_LIMIT_WINDOW_SECONDS = 600
BUSY_RETRY_AFTER_SECONDS = 30
LOCK_TIMEOUT_SECONDS = 10
REFRESH_INTERVAL_SECONDS = 900
COPIES_PER_REFRESH = 200

REQUESTED_BY = "kalavigyan-field-voices"

ACTION_STATE = "FIELD_VOICES_STATE"
ACTION_TRANSCRIPTS = "FIELD_VOICES_TRANSCRIPTS"
ACTION_IMAGES = "FIELD_VOICES_IMAGES"
ACTIONS = {TRANSCRIPTS: ACTION_TRANSCRIPTS, IMAGES: ACTION_IMAGES}

OUTCOME_OK = "OK"
OUTCOME_BAD_CURSOR = "BAD_CURSOR"
OUTCOME_BAD_VIEW = "BAD_VIEW"
OUTCOME_RATE_LIMITED = "RATE_LIMITED"
OUTCOME_BUSY = "BUSY"

ENTITY_STREAM = "STREAM"

DETAIL_UNKNOWN_CURSOR = "Unknown cursor."
DETAIL_UNKNOWN_VIEW = "Unknown view."
DETAIL_TOO_MANY = "Too many requests."
DETAIL_BUSY = "Busy, try again."

PERMISSION_STATEMENT = (
    "Shown on KalaVigyan with the consent of the people pictured. Not openly licensed: ask the "
    "rights holder before any other use."
)

IMAGE_URL_SECONDS = 900
IMAGE_URL_HALF_LIFE_SECONDS = 450

FORBIDDEN_KEYS = frozenset(
    {
        "accuracy",
        "age",
        "agegroup",
        "ageyears",
        "altitude",
        "city",
        "clustername",
        "coordinates",
        "dob",
        "gender",
        "geo",
        "geolocation",
        "gps",
        "ifsc",
        "lat",
        "lng",
        "locality",
        "lon",
        "long",
        "mobile",
        "otp",
        "place",
        "placename",
        "upi",
        "venue",
        "village",
        "whatsapp",
    }
)
FORBIDDEN_KEY_PARTS = (
    "phone",
    "email",
    "aadhaar",
    "pehchan",
    "fingerprint",
    "birth",
    "address",
    "pincode",
    "postcode",
    "latitude",
    "longitude",
    "bank",
    "password",
    "signature",
    "empanelment",
)
KEY_NOISE = re.compile(r"[^a-z0-9]")


class NeverSent(Exception):
    def __init__(self, keys: list[str]) -> None:
        self.keys = keys
        super().__init__(f"a field voices answer names keys that never leave the portal: {keys}")


def other_portal(portal: str) -> str:
    if portal not in PORTALS:
        raise ValueError(f"unknown portal {portal!r}")
    return FIELD_REPOSITORY if portal == DESIGNER else DESIGNER


def forbidden_keys(payload: Any) -> list[str]:
    found: list[str] = []
    for path, key in _keys(payload):
        normal = KEY_NOISE.sub("", key.lower())
        if normal in FORBIDDEN_KEYS or any(part in normal for part in FORBIDDEN_KEY_PARTS):
            found.append(f"{path}.{key}" if path else key)
    return sorted(found)


def assert_sendable(payload: Any) -> None:
    found = forbidden_keys(payload)
    if found:
        raise NeverSent(found)


def _keys(payload: Any) -> Iterator[tuple[str, str]]:
    stack: list[tuple[str, Any]] = [("", payload)]
    while stack:
        path, value = stack.pop()
        if isinstance(value, list):
            stack.extend((f"{path}[{index}]", item) for index, item in enumerate(value))
        elif isinstance(value, dict):
            for key, item in value.items():
                yield path, str(key)
                stack.append((f"{path}.{key}" if path else str(key), item))
