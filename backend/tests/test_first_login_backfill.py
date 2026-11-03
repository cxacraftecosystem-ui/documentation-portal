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
        {"id": "stamped", "email": "Stamped@Example.org", "createdAt": CREATED},
        {"id": "never", "email": "never@example.org", "createdAt": CREATED},
        {"id": "master", "email": "master@example.org", "createdAt": CREATED},
        {"id": "moved", "email": "new@example.org", "createdAt": CREATED},
        {"id": "ahead", "email": "ahead@example.org", "createdAt": CREATED},
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
