"""The artisan register keeps a keyed fingerprint and the last four digits, never the Aadhaar number.

Owner decision #2 (2026-10-17). No database: the routes run against an in-memory artisan table that
answers the where-clauses they send, the way ``test_identity_masking`` drives the update route.

Pinned: the fingerprint is deterministic, keyed, over the normalised number only, and byte for byte
the cross-portal code (one key on both portals); duplicates are found by fingerprint and, before the
backfill, by the legacy column; no response, export or edit-history entry carries the number or the
fingerprint; the backfill is a dry run by default, idempotent and scrubs old history values; the
drop migration is deferred and guarded.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import random
import re
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from conftest import TEST_AADHAAR_FINGERPRINT_KEY
from fastapi import FastAPI, HTTPException

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services import aadhaar_fingerprint as fp
from app.services import record_fields
from app.services.artisan_identity import validate_aadhaar, verhoeff_ok

KEY = TEST_AADHAAR_FINGERPRINT_KEY
BACKEND = Path(__file__).resolve().parents[1]
DROP_MIGRATION = (
    BACKEND / "prisma" / "deferred_migrations" / "20261029090000_drop_aadhaar_number" / "migration.sql"
)


def _fresh_aadhaar() -> str:
    while True:
        stem = str(random.randint(2, 9)) + "".join(random.choices("0123456789", k=10))
        for last in "0123456789":
            if verhoeff_ok(stem + last):
                return stem + last


def _settings(key: str | None = KEY) -> SimpleNamespace:
    return SimpleNamespace(aadhaar_fingerprint_key=key)


def _no_secret_in(text: str, number: str) -> None:
    assert number not in text
    assert fp.fingerprint(number, KEY) not in text
    assert "aadhaarFingerprint" not in text


# ── The fingerprint ─────────────────────────────────────────────────────────────────────────────


def test_the_fingerprint_is_hmac_sha256_of_the_twelve_digits_and_the_cross_portal_code() -> None:
    number = _fresh_aadhaar()
    expected = hmac.new(KEY.encode("utf-8"), number.encode("ascii"), hashlib.sha256).hexdigest()
    assert fp.fingerprint(number, KEY) == expected == fp.fingerprint(number, f" {KEY}\n")
    assert re.fullmatch(r"[0-9a-f]{64}", expected)
    assert fp.fingerprint(number, KEY + "x") != expected


@pytest.mark.parametrize("spelling", ["{a} {b} {c}", "{a}-{b}-{c}", " {a}{b}{c} "])
def test_every_spelling_of_one_number_is_one_fingerprint(spelling: str) -> None:
    number = _fresh_aadhaar()
    typed = spelling.format(a=number[:4], b=number[4:8], c=number[8:])
    assert fp.fingerprint(validate_aadhaar(typed), KEY) == fp.fingerprint(number, KEY)


@pytest.mark.parametrize("raw", ["1234 5678 9012", "12345678901", "１２３４５６７８９０１２", "XXXX XXXX 9012", None])
def test_only_the_normalised_number_is_fingerprinted(raw: Any) -> None:
    with pytest.raises(ValueError):
        fp.fingerprint(raw, KEY)


def test_no_key_means_nothing_is_stored() -> None:
    for key in (None, "", "short"):
        with pytest.raises(fp.FingerprintKeyMissing):
            fp.seal({"aadhaarNumber": _fresh_aadhaar()}, _settings(key))


def test_seal_keeps_only_the_fingerprint_and_last_four() -> None:
    number = _fresh_aadhaar()
    data: dict[str, Any] = {"aadhaarNumber": number}
    assert fp.seal(data, _settings()) == number
    assert data == {
        "aadhaarNumber": None,
        "aadhaarFingerprint": fp.fingerprint(number, KEY),
        "aadhaarLast4": number[-4:],
    }
    retract: dict[str, Any] = {"aadhaarNumber": None}
    fp.seal(retract, _settings())
    assert retract == {"aadhaarNumber": None, "aadhaarFingerprint": None, "aadhaarLast4": None}


# ── An in-memory register, and the real routes against it ───────────────────────────────────────


def _matches(row: Any, where: dict[str, Any]) -> bool:
    for key, want in where.items():
        if key == "OR":
            if not any(_matches(row, clause) for clause in want):
                return False
        elif key == "AND":
            if not all(_matches(row, clause) for clause in want):
                return False
        elif isinstance(want, dict) and ("in" in want or "gt" in want):
            value = getattr(row, key, None)
            if "in" in want and value not in want["in"]:
                return False
            if "gt" in want and not (value is not None and value > want["gt"]):
                return False
        elif isinstance(want, dict) and "not" in want:
            if getattr(row, key, None) == want["not"]:
                return False
        elif getattr(row, key, None) != want:
            return False
    return True


class _Artisans:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.written: list[dict[str, Any]] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return next((r for r in self.rows if _matches(r, where)), None)

    async def find_first(self, where: dict, include: dict | None = None, **_: Any) -> Any:
        return next((r for r in self.rows if _matches(r, where)), None)

    async def find_many(self, where: dict | None = None, include: dict | None = None, **kw: Any) -> list:
        found = [r for r in self.rows if _matches(r, where or {})]
        if kw.get("order"):
            found.sort(key=lambda r: r.id)
        return found[: kw["take"]] if kw.get("take") else found

    async def update(self, where: dict, data: dict, include: dict | None = None) -> Any:
        self.written.append(dict(data))
        row = await self.find_unique(where)
        for key, value in data.items():
            if key in ("aadhaarFingerprint", "aadhaarLast4", "aadhaarNumber", "notes", "phone"):
                setattr(row, key, value)
        return row

    async def count(self, where: dict | None = None) -> int:
        return len([r for r in self.rows if _matches(r, where or {})])

    async def update_many(self, where: dict, data: dict) -> int:
        hits = [r for r in self.rows if _matches(r, where)]
        for row in hits:
            for key, value in data.items():
                setattr(row, key, value)
        return len(hits)


def _artisan(**overrides: Any) -> SimpleNamespace:
    row = {
        "id": "a1", "name": "Kalu Ram", "localName": None, "gender": None, "phone": None,
        "email": None, "place": "Bagru", "address": None, "notes": None,
        "craft": SimpleNamespace(name="Bagru Hand Block Printing"), "craftId": None,
        "location": None, "locationId": None, "workshop": None, "workshopId": None, "workshops": [],
        "aadhaarNumber": None, "aadhaarFingerprint": None, "aadhaarLast4": None,
        "pehchanCardAvailable": False, "pehchanCardNumber": None, "dateOfBirth": None,
        "craftStartDate": None, "experienceYears": None, "experienceMonths": None,
        "dos": "d", "donts": "d", "extraMetadata": {}, "status": "APPROVED",
        "createdById": "owner", "createdBy": SimpleNamespace(name="Owner"), "createdAt": None,
        "updatedAt": None, "recordedAt": None, "recordedTimezone": None,
    }
    row.update(overrides)
    return SimpleNamespace(**row)


class _Revisions:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, data: dict) -> Any:
        self.created.append(data)
        return SimpleNamespace(id=f"r{len(self.created)}")


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


@pytest.fixture
def register(monkeypatch: pytest.MonkeyPatch):
    number, other = _fresh_aadhaar(), _fresh_aadhaar()
    stored = _artisan(
        aadhaarFingerprint=fp.fingerprint(number, KEY), aadhaarLast4=number[-4:]
    )
    legacy = _artisan(id="a2", name="Legacy", aadhaarNumber=other)
    artisans = _Artisans([stored, legacy])
    revisions = _Revisions()
    fake_db = SimpleNamespace(artisan=artisans, recordrevision=revisions)
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake_db)
    # The record's own creator and an admin: the people who used to be shown the full number.
    _CURRENT["user"] = SimpleNamespace(
        id="owner", email="o@example.test", name="Owner", role="ADMIN",
        canDownloadDataset=True, canReview=True,
    )

    def call(method: str, path: str, **kwargs: Any) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://fp.test") as client:
                return await client.request(method, f"/api{path}", **kwargs)

        return asyncio.run(run())

    yield SimpleNamespace(
        call=call, artisans=artisans, revisions=revisions, number=number, other=other,
        stored=stored, legacy=legacy,
    )
    _CURRENT["user"] = None


def test_the_lookup_finds_by_fingerprint_and_legacy_column_in_the_body_and_the_query(register) -> None:
    n = register.number
    spaced = f"{n[:4]} {n[4:8]} {n[8:]}"
    body = register.call("POST", "/artisans/lookup/aadhaar", json={"number": spaced}).json()
    assert body["found"] is True and body["artisan"]["id"] == "a1"
    query = register.call("GET", "/artisans/lookup/aadhaar", params={"number": n}).json()
    assert query["found"] is True
    legacy = register.call("POST", "/artisans/lookup/aadhaar", json={"number": register.other}).json()
    assert legacy["artisan"]["id"] == "a2", "a row the backfill has not reached is still a duplicate"
    assert register.call("POST", "/artisans/lookup/aadhaar", json={"number": _fresh_aadhaar()}).json() == {
        "found": False,
        "artisan": None,
    }


def test_the_duplicate_guard_names_the_artisan_and_masks_the_number(register) -> None:
    from app.api.routes.artisans import _guard_identity_conflicts

    for number, holder in ((register.number, "a1"), (register.other, "a2")):
        with pytest.raises(HTTPException) as clash:
            asyncio.run(_guard_identity_conflicts({}, aadhaar=number))
        detail = clash.value.detail
        assert detail["field"] == "aadhaarNumber" and detail["existingArtisan"]["id"] == holder
        assert detail["maskedValue"] == f"XXXX XXXX {number[-4:]}"
        _no_secret_in(repr(detail), number)
    # Its own number is not a clash with itself.
    asyncio.run(_guard_identity_conflicts({}, exclude_id="a1", aadhaar=register.number))


def test_an_edit_stores_only_the_fingerprint_and_the_history_records_no_value(register) -> None:
    replacement = _fresh_aadhaar()
    response = register.call("PATCH", "/artisans/a1", json={"aadhaarNumber": replacement})
    assert response.status_code == 200, response.text
    written = register.artisans.written[-1]
    assert written["aadhaarFingerprint"] == fp.fingerprint(replacement, KEY)
    assert written["aadhaarLast4"] == replacement[-4:]
    assert written["aadhaarNumber"] is None
    assert response.json()["aadhaarNumber"] == f"XXXX XXXX {replacement[-4:]}"
    _no_secret_in(response.text, replacement)
    changes = register.revisions.created[-1]["changes"].data
    assert set(changes) == {"aadhaarNumber"} and changes["aadhaarNumber"]["redacted"] is True
    _no_secret_in(repr(register.revisions.created), replacement)
    _no_secret_in(repr(register.revisions.created), register.number)


def test_re_sending_the_number_on_record_changes_nothing(register) -> None:
    response = register.call("PATCH", "/artisans/a1", json={"aadhaarNumber": register.number, "notes": "Met again"})
    assert response.status_code == 200, response.text
    written = register.artisans.written[-1]
    assert not {"aadhaarFingerprint", "aadhaarLast4", "aadhaarNumber"} & set(written)
    for revision in register.revisions.created:
        assert "aadhaarNumber" not in revision["changes"].data


def test_every_viewer_is_served_the_mask_and_a_history_value_is_never_served() -> None:
    from app.services.records import public_encode

    number, older = _fresh_aadhaar(), _fresh_aadhaar()
    admin = SimpleNamespace(id="owner", role="MASTER_ADMIN")
    nodes = public_encode(
        {
            "fresh": {"createdById": "owner", "aadhaarNumber": None,
                      "aadhaarFingerprint": fp.fingerprint(number, KEY), "aadhaarLast4": number[-4:]},
            "legacy": {"createdById": "owner", "aadhaarNumber": number, "aadhaarLast4": None},
            "history": {"changes": {"aadhaarNumber": {"old": older, "new": number}}},
        },
        admin,
    )
    assert nodes["fresh"]["aadhaarNumber"] == nodes["legacy"]["aadhaarNumber"] == f"XXXX XXXX {number[-4:]}"
    assert nodes["history"]["changes"]["aadhaarNumber"] == {
        "old": "(value recorded)", "new": "(value replaced)", "redacted": True,
    }
    _no_secret_in(repr(nodes), number)
    _no_secret_in(repr(nodes), older)


def test_every_export_surface_prints_the_mask_from_the_last_four() -> None:
    number = _fresh_aadhaar()
    artisan = _artisan(aadhaarFingerprint=fp.fingerprint(number, KEY), aadhaarLast4=number[-4:])
    panel = record_fields.info_panel("artisan", artisan)
    values = {field["label"]: field["value"] for field in panel["fields"]}
    assert values["Aadhaar number"] == f"XXXX XXXX {number[-4:]}"
    rendered = record_fields.info_text(panel) + " | ".join(record_fields.sheet_row("artisan", artisan))
    _no_secret_in(rendered, number)


# ── The backfill and the guarded drop ───────────────────────────────────────────────────────────


class _BackfillDb:
    def __init__(self, rows: list[Any], revisions: list[dict[str, Any]]) -> None:
        self.artisan = _Artisans(rows)
        self.revisions = revisions
        self.recordrevision = SimpleNamespace(update=self._update_revision)

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        return [dict(r) for r in self.revisions if "aadhaarNumber" in r["changes"]]

    async def _update_revision(self, where: dict, data: dict) -> None:
        for r in self.revisions:
            if r["id"] == where["id"]:
                r["changes"] = data["changes"].data


def test_the_backfill_is_a_dry_run_by_default_idempotent_and_scrubs_history(monkeypatch) -> None:
    import scripts.backfill_aadhaar_fingerprint as backfill

    number, older, broken = _fresh_aadhaar(), _fresh_aadhaar(), "12345"
    rows = [
        _artisan(id="a1", aadhaarNumber=number),
        _artisan(id="a2", aadhaarNumber=broken),
        _artisan(id="a3"),
    ]
    revisions = [{"id": "r1", "recordId": "a1", "changes": {"aadhaarNumber": {"old": older, "new": number}}}]
    fake = _BackfillDb(rows, revisions)
    monkeypatch.setattr(backfill, "db", fake)

    dry = asyncio.run(backfill.run(apply=False, batch=1))
    assert (dry.would_fill, dry.unusable, dry.revisions_would_scrub) == (1, 1, 1)
    assert rows[0].aadhaarFingerprint is None and older in repr(revisions)

    applied = asyncio.run(backfill.run(apply=True, batch=1))
    assert (applied.filled, applied.verified, applied.revisions_scrubbed) == (1, 1, 1)
    assert rows[0].aadhaarFingerprint == fp.fingerprint(number, KEY)
    assert rows[0].aadhaarLast4 == number[-4:]
    assert applied.unfinished == 1, "the unusable legacy value still needs a decision"
    _no_secret_in(repr(revisions), number)
    _no_secret_in(repr(revisions), older)

    again = asyncio.run(backfill.run(apply=True, retract_unusable=True, batch=1))
    assert (again.filled, again.done_already, again.retracted, again.revisions_scrubbed) == (0, 1, 1, 0)
    assert again.unfinished == 0 and rows[1].aadhaarNumber is None


@pytest.mark.parametrize("sep", ["", "%20", "+", "-"])
def test_an_aadhaar_in_a_logged_query_string_is_redacted(sep: str) -> None:
    """Old app builds send the lookup as ``GET ...?number=``; uvicorn's access line is redacted."""
    import logging as _logging

    number = _fresh_aadhaar()
    typed = sep.join((number[:4], number[4:8], number[8:]))
    path = f"/api/artisans/lookup/aadhaar?number={typed}&page=2"
    assert fp.redact_path(path) == "/api/artisans/lookup/aadhaar?number=[redacted]&page=2"
    record = _logging.LogRecord(
        "uvicorn.access", _logging.INFO, __file__, 1, '%s - "%s %s HTTP/%s" %d',
        ("1.2.3.4:5", "GET", path, "1.1", 200), None,
    )
    assert fp.AccessLogRedaction().filter(record) is True
    assert typed not in record.getMessage() and number not in record.getMessage()
    assert "[redacted]" in record.getMessage()
    # A path without a query, or a query with no Aadhaar-shaped value, is left alone.
    assert fp.redact_path("/api/artisans?page=2&pageSize=20") == "/api/artisans?page=2&pageSize=20"
    assert fp.redact_path("/api/artisans/abc") == "/api/artisans/abc"


def test_the_drop_migration_is_deferred_and_guarded() -> None:
    assert not (BACKEND / "prisma" / "migrations" / DROP_MIGRATION.parent.name).exists()
    sql = DROP_MIGRATION.read_text(encoding="utf-8")
    guard_at = sql.index("RAISE EXCEPTION")
    assert guard_at < sql.index('UPDATE "Artisan" SET "aadhaarNumber" = NULL')
    assert guard_at < sql.index('DROP COLUMN "aadhaarNumber"')
    assert fp.LEGACY_COLUMN_PRESENT, "flip this only in the commit that ships the drop migration"
