"""THE MOST IMPORTANT TEST IN THE SIGN-IN GATE: nobody who could sign in yesterday is locked out.

This is a live repository with real users. Before ``20260816120000_access_roster_signin_gate`` there
was no refusal of any kind; after it, an address must be ACTIVE on ``AccessRoster`` to get a token.
The single statement standing between the existing user base and a permanent lockout is the
back-fill at the bottom of that migration, and the only account that could put anybody back is the
master admin — one person, by hand, for the whole institution. So this file does not assert that the
back-fill LOOKS right. It runs it, and then it signs everybody in.

WHAT "RUNS IT" MEANS HERE, AND WHY IT IS NOT A CHEAT. There is no local Postgres on this machine and
``backend/.env`` points ``DATABASE_URL`` at the LIVE production pooler — a test that connected to a
database would be exercising production. So the migration's own text is read off disk and executed
against an in-memory SQLite database through the small, EXPLICIT set of dialect rewrites in
:data:`REWRITES`. Every rewrite is asserted to have applied (:func:`_translate` fails loudly if one
stops matching), so the day somebody adds a Postgres-only construct to that file, this test says so
instead of quietly skipping it.

The parts of the statement that matter are executed VERBATIM: the column list, the ``SELECT`` that
projects a ``User`` row onto a roster row, the ``NOT EXISTS`` clause that de-duplicates addresses
differing only in case, and the ``ON CONFLICT DO NOTHING``. Nothing in :data:`REWRITES` touches any
of them — they are type names, one id generator, and two DDL constructs SQLite spells differently.

Then the rows that come out of that are loaded into the fake database and driven through the REAL
``POST /api/auth/login`` handler, with the real gate, once per role. A pass means: this migration,
applied to a table holding one account of every role this application has, admits all of them.
"""

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

#: Every role in this application's ladder. Hard-coded rather than read from ``deps.ROLE_RANK``
#: BECAUSE it is hard-coded: if a seventh tier is added and this list is not updated, that tier goes
#: untested here, and a list derived from the ladder would have hidden that from the person adding
#: it. The failure this file exists to prevent is silent, so its inputs are written out.
ALL_ROLES = (
    "MASTER_ADMIN",
    "ADMIN",
    "PROFESSOR",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER",
)

PASSWORD = "correct-horse-battery"

# --- Postgres -> SQLite, exhaustively and on purpose ---------------------------------------------
#
# (pattern, replacement, why). Each is checked to have matched at least once; see _translate.
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
    """The migration as SQLite understands it, or an assertion failure naming the rewrite that
    stopped applying."""
    for pattern, replacement, why in REWRITES:
        sql, applied = re.subn(pattern, replacement, sql)
        assert applied, (
            f"The SQLite rewrite for {pattern!r} matched nothing in the migration. Either the "
            f"migration no longer contains that construct (delete the rewrite) or it was written "
            f"differently (fix the rewrite). It exists because: {why}"
        )
    return sql


def _seed_users(connection: sqlite3.Connection, users: list[dict[str, Any]]) -> None:
    """The ``User`` columns the back-fill reads. Only those: this test is about the migration, not
    about reproducing a 50-column table."""
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
    """Seed ``User``, execute the real migration, and hand back the ``AccessRoster`` rows it wrote."""
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
    """One account per role, each with its own joining date, as the database held them the moment
    before the gate shipped."""
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


# --- The application, mounted once ----------------------------------------------------------------

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
    """The world immediately AFTER the migration: the six pre-existing accounts, and whatever roster
    rows the back-fill actually produced for them."""
    users = _existing_users()
    roster = _run_migration(users)
    fake = FakeDb(
        users=[{**user, "passwordHash": hash_password(PASSWORD)} for user in users],
        roster=roster,
    )
    install(monkeypatch, fake)
    return fake, users


# --- The tests -------------------------------------------------------------------------------------


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
    """THE ONE THAT MATTERS. Six roles, six sign-ins, all through the real gate."""
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
    """``joinedAt`` must be back-filled from ``User.createdAt``, not stamped with today.

    Stamping today would tell every administrator that the entire institution joined on the day this
    feature shipped, which is the one date it certainly did not.
    """
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
    """``grantedRole`` carries each account's real tier across, so a re-provisioned account does not
    come back at the bottom of the ladder."""
    fake, users = migrated
    by_email = {row.email: row for row in fake.accessroster.rows}
    for user in users:
        assert by_email[user["email"]].grantedRole == user["role"]


def test_the_backfill_does_not_filter_by_role() -> None:
    """A guard on the migration's TEXT, aimed at one specific future edit.

    The tests above would still pass if somebody added ``WHERE u."role" IN (...)`` and this file's
    seed happened to list only admitted roles. This one refuses the clause outright: the back-fill's
    only WHERE is the case-collision de-duplicator, and any other predicate on it is somebody
    choosing to lock a group of real people out.
    """
    sql = MIGRATION.read_text(encoding="utf-8")
    backfill = sql[sql.index('INSERT INTO "AccessRoster"') :]
    statement = backfill[: backfill.index(";") + 1]
    assert '"role"' not in statement.split("FROM \"User\" u")[1], (
        "The grandfathering statement has grown a predicate on role. Every existing account is "
        "admitted, without exception — see the comment above the statement."
    )


def test_two_accounts_differing_only_in_case_produce_one_admitted_row() -> None:
    """The unique index is on the lower-cased address, so the migration must not try to insert two.

    ``User.email`` is unique CASE-SENSITIVELY, so the table can hold both ``A@x.test`` and
    ``a@x.test``. Only the lower-cased one has ever been able to sign in (``auth.login`` has always
    looked accounts up with ``.lower()``), so nothing is being locked out here — but a migration that
    raised a unique-violation on such a pair would fail to apply AT ALL, and take every other
    account's admission down with it.
    """
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
    """Applying the back-fill twice must not raise. Re-running a migration is a thing that happens
    to people repairing a broken deploy, and the ON CONFLICT is what makes it survivable."""
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
