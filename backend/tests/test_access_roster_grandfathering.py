import asyncio
import re
import sqlite3
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeDb, install
from app.api.router import api_router
from app.core.security import hash_password

BACKEND_ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (
    BACKEND_ROOT
    / "prisma"
    / "migrations"
    / "20260816120000_access_roster_signin_gate"
    / "migration.sql"
)

ALL_ROLES = (
    "MASTER_ADMIN",
    "ADMIN",
    "PROFESSOR",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER",
)

PASSWORD = "correct-horse-battery"

REWRITES: tuple[tuple[str, str, str], ...] = (
    (
        r'CREATE TYPE "AccessStatus" AS ENUM \([^;]*\);',
        "",
        "SQLite has no enum type. The column becomes TEXT, which stores the same four strings.",
    ),
    (
        r'"(?:AccessStatus|UserRole)"(?=\s+NOT NULL|\s+DEFAULT|\s*,|\s*$)',
        "TEXT",
        "Enum-typed columns become TEXT. Only the TYPE position matches: the lookahead keeps this "
        "away from table and column names.",
    ),
    (
        r"TIMESTAMP\(3\)",
        "TEXT",
        "SQLite has no timestamp type; it stores ISO strings, which is what this test compares.",
    ),
    (
        r"gen_random_uuid\(\)::text",
        "lower(hex(randomblob(16)))",
        "pgcrypto's uuid generator. Both produce a unique opaque id, which is all the column needs.",
    ),
    (
        r'ALTER TABLE "AccessRoster" ADD CONSTRAINT[^;]*;',
        "",
        "SQLite cannot ADD CONSTRAINT after the fact. The foreign key is not what this test is "
        "about, and dropping it makes the back-fill's behaviour no different.",
    ),
)


def _translate(sql: str) -> str:
    for pattern, replacement, why in REWRITES:
        sql, applied = re.subn(pattern, replacement, sql)
        assert applied, (
            f"The SQLite rewrite for {pattern!r} matched nothing in the migration. Either the "
            f"migration no longer contains that construct (delete the rewrite) or it was written "
            f"differently (fix the rewrite). It exists because: {why}"
        )
    return sql


def _seed_users(connection: sqlite3.Connection, users: list[dict[str, Any]]) -> None:
    connection.execute(
        'CREATE TABLE "User" ('
        '"id" TEXT PRIMARY KEY, "email" TEXT NOT NULL UNIQUE, "name" TEXT NOT NULL, '
        '"role" TEXT NOT NULL, "createdAt" TEXT NOT NULL)'
    )
    connection.executemany(
        'INSERT INTO "User" ("id", "email", "name", "role", "createdAt") VALUES (?, ?, ?, ?, ?)',
        [(u["id"], u["email"], u["name"], u["role"], u["createdAt"]) for u in users],
    )


def _run_migration(users: list[dict[str, Any]]) -> list[dict[str, Any]]:
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    try:
        _seed_users(connection, users)
        connection.executescript(_translate(MIGRATION.read_text(encoding="utf-8")))
        rows = connection.execute('SELECT * FROM "AccessRoster"').fetchall()
        return [dict(row) for row in rows]
    finally:
        connection.close()


def _existing_users() -> list[dict[str, Any]]:
    base = datetime(2026, 3, 1, tzinfo=UTC)
    return [
        {
            "id": f"user-{index}",
            "email": f"{role.lower()}@example.org",
            "name": f"{role.title()} Person",
            "role": role,
            "createdAt": (base + timedelta(days=index)).isoformat(),
        }
        for index, role in enumerate(ALL_ROLES)
    ]


_APP = FastAPI()
_APP.include_router(api_router)


def _login(email: str, password: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
            return await client.post(
                "/api/auth/login", json={"email": email, "password": password}
            )

    return asyncio.run(run())


@pytest.fixture
def migrated(monkeypatch: pytest.MonkeyPatch) -> tuple[FakeDb, list[dict[str, Any]]]:
    users = _existing_users()
    roster = _run_migration(users)
    fake = FakeDb(
        users=[{**user, "passwordHash": hash_password(PASSWORD)} for user in users],
        roster=roster,
    )
    install(monkeypatch, fake)
    return fake, users



def test_the_backfill_admits_one_row_per_existing_account(
    migrated: tuple[FakeDb, list[dict[str, Any]]]
) -> None:
    fake, users = migrated
    admitted = {row.email: row for row in fake.accessroster.rows}
    assert set(admitted) == {user["email"] for user in users}
    assert all(row.status == "ACTIVE" for row in admitted.values()), (
        "Every grandfathered row must be ACTIVE. Any other status is a locked-out user."
    )


@pytest.mark.parametrize("role", ALL_ROLES)
def test_every_pre_existing_account_can_still_sign_in(
    migrated: tuple[FakeDb, list[dict[str, Any]]], role: str
) -> None:
    response = _login(f"{role.lower()}@example.org", PASSWORD)
    assert response.status_code == 200, (
        f"A pre-existing {role} was refused after the migration: {response.text}. "
        f"This is the lockout the back-fill exists to prevent."
    )
    assert response.json()["user"]["email"] == f"{role.lower()}@example.org"


@pytest.mark.parametrize("role", ALL_ROLES)
def test_the_joining_date_is_the_accounts_own_creation_date(
    migrated: tuple[FakeDb, list[dict[str, Any]]], role: str
) -> None:
    fake, users = migrated
    user = next(u for u in users if u["role"] == role)
    row = next(r for r in fake.accessroster.rows if r.email == user["email"])
    assert row.joinedAt == user["createdAt"]
    assert row.firstSeenAt == user["createdAt"], (
        "People who have demonstrably used the app must not land in the 'approved but never signed "
        "in' bucket."
    )
    assert row.requestCount == 0, (
        "Grandfathered rows were not requested by anybody; a count of 1 would read on the roster as "
        "'asked once and was approved'."
    )


def test_the_grandfathered_tier_is_the_accounts_own_role(
    migrated: tuple[FakeDb, list[dict[str, Any]]]
) -> None:
    fake, users = migrated
    by_email = {row.email: row for row in fake.accessroster.rows}
    for user in users:
        assert by_email[user["email"]].grantedRole == user["role"]


def test_the_backfill_does_not_filter_by_role() -> None:
    sql = MIGRATION.read_text(encoding="utf-8")
    backfill = sql[sql.index('INSERT INTO "AccessRoster"') :]
    statement = backfill[: backfill.index(";") + 1]
    assert '"role"' not in statement.split("FROM \"User\" u")[1], (
        "The grandfathering statement has grown a predicate on role. Every existing account is "
        "admitted, without exception — see the comment above the statement."
    )


def test_two_accounts_differing_only_in_case_produce_one_admitted_row() -> None:
    users = [
        {
            "id": "older",
            "email": "Shared@example.org",
            "name": "Older",
            "role": "ADMIN",
            "createdAt": "2026-01-01T00:00:00+00:00",
        },
        {
            "id": "newer",
            "email": "shared@example.org",
            "name": "Newer",
            "role": "RESEARCHER",
            "createdAt": "2026-05-01T00:00:00+00:00",
        },
    ]
    rows = _run_migration(users)
    assert len(rows) == 1
    assert rows[0]["email"] == "shared@example.org"
    assert rows[0]["joinedAt"] == "2026-01-01T00:00:00+00:00", (
        "The oldest account wins the address, so the back-filled joining date is the earliest that "
        "address was known."
    )


def test_the_migration_is_idempotent() -> None:
    users = _existing_users()
    connection = sqlite3.connect(":memory:")
    try:
        _seed_users(connection, users)
        script = _translate(MIGRATION.read_text(encoding="utf-8"))
        connection.executescript(script)
        backfill = script[script.index('INSERT INTO "AccessRoster"') :]
        connection.executescript(backfill[: backfill.index(";") + 1])
        (total,) = connection.execute('SELECT count(*) FROM "AccessRoster"').fetchone()
        assert total == len(users)
    finally:
        connection.close()
