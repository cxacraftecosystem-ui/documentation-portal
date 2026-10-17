"""``20261018090100_first_login_backfill``, executed, not read.

The back-fill decides, once and for every account that predates ``User.firstLoginAt``, whether the
account is in use. That decision is what ``credential_links.purpose_for`` reads to choose between a
72-hour invitation and a two-hour reset, so a wrong guess either hands a live account a three-day
spare key or gives a never-used account a link that dies before lunch.

Like ``test_access_roster_grandfathering.py``, this runs the migration's own text against an
in-memory SQLite database (``backend/.env`` is production; there is no database a test may touch),
through the explicit rewrites in :data:`REWRITES`, each asserted to have applied. The ``UPDATE``,
its join, the case folding and the conditions run verbatim.
"""

import re
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

from app.services.credential_links import INVITE, RESET, purpose_for

MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "prisma"
    / "migrations"
    / "20261018090100_first_login_backfill"
    / "migration.sql"
)

#: (pattern, replacement, why). Each must match at least once.
REWRITES: tuple[tuple[str, str, str], ...] = (
    (
        r"(\S+) > (\S+) \+ interval '5 minutes'",
        r"julianday(\1) > julianday(\2) + 5.0 / 1440",
        "SQLite has no interval type; julianday counts days, so five minutes is 5/1440 of one.",
    ),
)

STAMP = "%Y-%m-%d %H:%M:%S"
CREATED = datetime(2026, 9, 1, 10, 0, 0)


def _translate(sql: str) -> str:
    for pattern, replacement, why in REWRITES:
        sql, applied = re.subn(pattern, replacement, sql)
        assert applied, f"The SQLite rewrite {pattern!r} matched nothing in the migration. It exists because: {why}"
    return sql


def _at(moment: datetime | None) -> str | None:
    return moment.strftime(STAMP) if moment else None


def _run(users: list[dict], roster: list[dict]) -> dict[str, str | None]:
    """Seed both tables, execute the migration, and hand back ``firstLoginAt`` by account id."""
    connection = sqlite3.connect(":memory:")
    try:
        connection.execute(
            'CREATE TABLE "User" ("id" TEXT PRIMARY KEY, "email" TEXT NOT NULL UNIQUE, '
            '"createdAt" TEXT NOT NULL, "firstLoginAt" TEXT)'
        )
        connection.execute(
            'CREATE TABLE "AccessRoster" ("id" TEXT PRIMARY KEY, "email" TEXT NOT NULL UNIQUE, '
            '"firstSeenAt" TEXT, "createdAt" TEXT NOT NULL)'
        )
        connection.executemany(
            'INSERT INTO "User" VALUES (?, ?, ?, ?)',
            [(u["id"], u["email"], _at(u["createdAt"]), _at(u.get("firstLoginAt"))) for u in users],
        )
        connection.executemany(
            'INSERT INTO "AccessRoster" VALUES (?, ?, ?, ?)',
            [(f"r{i}", r["email"], _at(r.get("firstSeenAt")), _at(r["createdAt"])) for i, r in enumerate(roster)],
        )
        connection.executescript(_translate(MIGRATION.read_text(encoding="utf-8")))
        # Twice: re-running the file must change nothing.
        connection.executescript(_translate(MIGRATION.read_text(encoding="utf-8")))
        return dict(connection.execute('SELECT "id", "firstLoginAt" FROM "User"').fetchall())
    finally:
        connection.close()


def _purpose(first_login_at: str | None) -> str:
    return purpose_for(SimpleNamespace(passwordHash="hash", firstLoginAt=first_login_at))


def test_the_backfill_tells_live_accounts_from_never_used_ones() -> None:
    seen = CREATED + timedelta(days=3)
    already = CREATED + timedelta(days=9)
    users = [
        # Signed in on day 3; the roster stamped it. Capitals on the account, as old rows may have.
        {"id": "stamped", "email": "Stamped@Example.org", "createdAt": CREATED},
        # Admitted with the account (POST /users) and never signed in: the one that stays empty.
        {"id": "never", "email": "never@example.org", "createdAt": CREATED},
        # No roster row at all: the master admin.
        {"id": "master", "email": "master@example.org", "createdAt": CREATED},
        # Address changed a month later: a fresh unstamped row; the stamped one is under the old address.
        {"id": "moved", "email": "new@example.org", "createdAt": CREATED},
        # Admitted ahead of time, long before the account existed, and never signed in.
        {"id": "ahead", "email": "ahead@example.org", "createdAt": CREATED},
        # Already stamped by a sign-in after the column arrived: left alone.
        {"id": "already", "email": "already@example.org", "createdAt": CREATED, "firstLoginAt": already},
    ]
    roster = [
        {"email": "stamped@example.org", "firstSeenAt": seen, "createdAt": CREATED},
        {"email": "never@example.org", "firstSeenAt": None, "createdAt": CREATED + timedelta(seconds=1)},
        {"email": "old@example.org", "firstSeenAt": seen, "createdAt": CREATED},
        {"email": "new@example.org", "firstSeenAt": None, "createdAt": CREATED + timedelta(days=30)},
        {"email": "ahead@example.org", "firstSeenAt": None, "createdAt": CREATED - timedelta(days=5)},
        {"email": "already@example.org", "firstSeenAt": seen, "createdAt": CREATED},
    ]

    first = _run(users, roster)

    assert first == {
        "stamped": _at(seen),
        "never": None,
        "master": _at(CREATED),
        "moved": _at(CREATED),
        "ahead": None,
        "already": _at(already),
    }
    assert {account: _purpose(value) for account, value in first.items()} == {
        "stamped": RESET,
        "never": INVITE,
        "master": RESET,
        "moved": RESET,
        "ahead": INVITE,
        "already": RESET,
    }
