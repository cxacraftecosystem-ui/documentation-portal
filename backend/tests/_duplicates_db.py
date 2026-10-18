"""Shared set-up for the duplicate-artisan tests that need a REAL Postgres.

Opt-in: those tests run only with ``ARTISAN_DUPLICATE_DB_TESTS=1`` and a ``DATABASE_URL`` that is a
throwaway database with every migration applied (never ``backend/.env``, which points at
production). CI's placeholder DSN and an ordinary ``pytest`` skip them; what they cover is what only
a database can show — the raw ``UPDATE ... RETURNING`` re-pointing, the partial unique indexes the
pair upserts target, the transaction, and a merge plus undo leaving every row exactly as it was.

Every artisan gets a freshly invented name, so pairs from one test never reach another's scorer.
"""

from __future__ import annotations

import os
import random
import secrets
import string
from datetime import datetime, timedelta
from typing import Any

import pytest

DB_TESTS = os.environ.get("ARTISAN_DUPLICATE_DB_TESTS") == "1"

skip_without_db = pytest.mark.skipif(
    not DB_TESTS, reason="needs ARTISAN_DUPLICATE_DB_TESTS=1 and a throwaway DATABASE_URL"
)


async def connect() -> Any:
    from app.core.db import db

    url = os.environ.get("DATABASE_URL", "")
    assert "supabase" not in url and "pooler" not in url, "refusing to run against a hosted database"
    if not db.is_connected():
        # A loaded test box can take longer than the engine's default 10 s to answer its first call.
        await db.connect(timeout=timedelta(seconds=180))
    return db


async def disconnect() -> None:
    from app.core.db import db

    if db.is_connected():
        await db.disconnect()


_CONS = "bdfgklmnprstvz"
_VOWS = "aeiou"


def word(length: int = 7) -> str:
    """A pronounceable invented word, unlike any other test's."""
    rng = random.SystemRandom()
    letters = [rng.choice(_CONS if i % 2 == 0 else _VOWS) for i in range(length)]
    return "".join(letters).capitalize()


def person() -> str:
    return f"{word()} {word(8)}"


def valid_aadhaar() -> str:
    """A fresh number the app's own Verhoeff routine accepts."""
    from app.services.artisan_identity import verhoeff_ok

    rng = random.SystemRandom()
    while True:
        stem = str(rng.randint(2, 9)) + "".join(rng.choice(string.digits) for _ in range(10))
        for last in string.digits:
            if verhoeff_ok(stem + last):
                return stem + last


def pehchan() -> str:
    return "PMV" + "".join(random.SystemRandom().choice(string.digits) for _ in range(9))


async def make_user(db: Any, role: str = "ADMIN") -> Any:
    tag = secrets.token_hex(5)
    return await db.user.create(
        data={"email": f"dupes-{tag}@example.invalid", "name": f"Dupes {role.title()} {tag}", "role": role}
    )


async def make_location(db: Any, state: str | None = "Gujarat", district: str | None = "Kachchh",
                        village: str | None = None) -> Any:
    return await db.location.create(
        data={"latitude": 23.25, "longitude": 69.67, "state": state, "district": district, "village": village}
    )


async def make_artisan(db: Any, user: Any, *, state: str | None = "Gujarat",
                       district: str | None = "Kachchh", **fields: Any) -> Any:
    from app.services import aadhaar_fingerprint

    data: dict[str, Any] = {
        "name": fields.pop("name", None) or person(),
        "place": fields.pop("place", "Bhuj"),
        "createdById": user.id,
        "dos": "1. Ask before photographing",
        "donts": "1. Do not rush",
    }
    aadhaar = fields.pop("aadhaar", None)
    if aadhaar:
        data["aadhaarFingerprint"] = aadhaar_fingerprint.fingerprint_of(aadhaar)
        data["aadhaarLast4"] = aadhaar[-4:]
    if state or district:
        location = await make_location(db, state, district, fields.pop("village", None))
        data["locationId"] = location.id
    if isinstance(fields.get("dateOfBirth"), str):
        fields["dateOfBirth"] = datetime.fromisoformat(fields["dateOfBirth"])
    data.update(fields)
    return await db.artisan.create(data=data)


def payload(name: str, *, aadhaar: str | None = None, ack: list[str] | None | str = "absent",
            **extra: Any) -> dict[str, Any]:
    """An ``ArtisanCreate`` body as the form sends it. ``ack="absent"`` leaves the field out."""
    body: dict[str, Any] = {
        "name": name,
        "place": extra.pop("place", "Bhuj"),
        "aadhaarNumber": aadhaar or valid_aadhaar(),
        "pehchanCardAvailable": False,
        "dos": "1. Ask before photographing",
        "donts": "1. Do not rush",
        "craftName": extra.pop("craftName", None),
        "craftId": extra.pop("craftId", None),
        "location": {
            "latitude": 23.25,
            "longitude": 69.67,
            "state": extra.pop("state", "Gujarat"),
            "district": extra.pop("district", "Kachchh"),
        },
    }
    if ack != "absent":
        body["duplicateAcknowledged"] = ack
    body.update(extra)
    return body
