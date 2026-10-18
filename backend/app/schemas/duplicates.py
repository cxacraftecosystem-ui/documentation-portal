"""Request bodies for the duplicate-artisan finder (routes/artisan_duplicates.py)."""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import Field

from app.schemas.common import APIModel


class DuplicateCheckIn(APIModel):
    """What the artisan form knows so far. The Aadhaar travels in the body only, is fingerprinted
    and dropped; it is never stored or echoed."""

    name: str = Field(min_length=1, max_length=180)
    localName: str | None = Field(default=None, max_length=180)
    gender: str | None = Field(default=None, max_length=40)
    dateOfBirth: str | None = Field(default=None, max_length=40)
    phone: str | None = Field(default=None, max_length=40)
    place: str | None = Field(default=None, max_length=180)
    state: str | None = Field(default=None, max_length=120)
    district: str | None = Field(default=None, max_length=120)
    village: str | None = Field(default=None, max_length=180)
    craftId: str | None = Field(default=None, max_length=64)
    craftName: str | None = Field(default=None, max_length=180)
    aadhaar: str | None = Field(default=None, max_length=20)
    pehchanCardNumber: str | None = Field(default=None, max_length=40)
    excludeArtisanId: str | None = Field(default=None, max_length=64)


class MergeIn(APIModel):
    survivor: Literal["A", "B"]
    choices: dict[str, Literal["A", "B", "BOTH"]] = Field(default_factory=dict)
    expectedUpdatedAt: dict[Literal["a", "b"], datetime | None] | None = None
    reason: str | None = Field(default=None, max_length=500)


class DismissIn(APIModel):
    note: str | None = Field(default=None, max_length=500)

