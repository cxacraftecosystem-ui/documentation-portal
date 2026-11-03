import hashlib
import re
import sqlite3
from pathlib import Path
from typing import Any

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (
    BACKEND_ROOT
    / "prisma"
    / "migrations"
    / "20260913100000_questionnaire_instruments"
    / "migration.sql"
)

W2 = "qnr_2nd_craft_toolkit_workshop"

TABLES = (
    "Questionnaire",
    "QuestionnaireSection",
    "QuestionnaireQuestion",
    "QuestionnaireInterview",
    "Workshop",
)

REWRITES: tuple[tuple[str, str, str], ...] = (
    (
        r"TIMESTAMP\(3\)",
        "TEXT",
        "SQLite has no parameterised timestamp type; it stores ISO strings.",
    ),
    (
        r'u\."role"::text',
        'u."role"',
        "SQLite has no `::` cast. The column is already text here, so dropping the cast changes "
        "which user the CASE picks not at all.",
    ),
    (
        r'WHERE "isDefault";',
        'WHERE "isDefault" = 1;',
        "SQLite has no boolean truthiness in an index predicate. `= 1` is the same predicate over "
        "the same storage, so the PARTIAL index this test exists to prove is still partial.",
    ),
    (
        r'ALTER TABLE "\w+"\s+(?:DROP CONSTRAINT IF EXISTS|ADD CONSTRAINT)[^;]*;',
        "",
        "SQLite cannot add or drop a foreign key after table creation. The FKs are not what this "
        "test is about — the backfill and the indexes are — and their absence changes none of it.",
    ),
    (
        r'ALTER TABLE "\w+"\s+ALTER COLUMN "\w+" SET NOT NULL;',
        "",
        "SQLite has no ALTER COLUMN. The columns are asserted to be fully populated instead, which "
        "is the property SET NOT NULL would enforce and the one that can actually fail.",
    ),
    (
        r'ALTER TABLE\s+("\w+")\s+ADD COLUMN IF NOT EXISTS',
        r"ALTER TABLE \1 ADD COLUMN",
        "SQLite has no IF NOT EXISTS on ADD COLUMN. Without this rewrite ALL FOUR step-3 statements "
        "raise and the harness never reaches an assertion. Because the rewrite removes the guard, "
        "step 3 may only run once per fresh in-memory database — which is exactly what the "
        "double-run test asserts about, catching `duplicate column name` and nothing else.",
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


def _statements(sql: str) -> list[str]:
    stripped = "\n".join(
        line for line in sql.splitlines() if not line.lstrip().startswith("--")
    )
    return [part.strip() for part in stripped.split(";") if part.strip()]


SCHEMA_BEFORE = """
CREATE TABLE "User" (
    "id" TEXT PRIMARY KEY, "email" TEXT NOT NULL UNIQUE, "name" TEXT NOT NULL,
    "role" TEXT NOT NULL, "createdAt" TEXT NOT NULL
);
CREATE TABLE "QuestionnaireSection" (
    "id" TEXT PRIMARY KEY, "code" TEXT NOT NULL, "title" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL, "isActive" INTEGER NOT NULL DEFAULT 1,
    "createdAt" TEXT NOT NULL, "updatedAt" TEXT NOT NULL
);
CREATE UNIQUE INDEX "QuestionnaireSection_code_key" ON "QuestionnaireSection"("code");
CREATE UNIQUE INDEX "QuestionnaireSection_sortOrder_key" ON "QuestionnaireSection"("sortOrder");
CREATE INDEX "QuestionnaireSection_isActive_sortOrder_idx"
    ON "QuestionnaireSection"("isActive", "sortOrder");
CREATE INDEX "QuestionnaireSection_code_idx" ON "QuestionnaireSection"("code");
CREATE TABLE "QuestionnaireQuestion" (
    "id" TEXT PRIMARY KEY, "sectionId" TEXT, "sectionCode" TEXT NOT NULL,
    "sectionTitle" TEXT NOT NULL, "prompt" TEXT NOT NULL, "sortOrder" INTEGER NOT NULL,
    "isActive" INTEGER NOT NULL DEFAULT 1, "createdAt" TEXT NOT NULL, "updatedAt" TEXT NOT NULL
);
CREATE TABLE "QuestionnaireInterview" (
    "id" TEXT PRIMARY KEY, "title" TEXT NOT NULL, "artisanSetKey" TEXT, "workshopId" TEXT,
    "createdAt" TEXT NOT NULL, "updatedAt" TEXT NOT NULL
);
CREATE UNIQUE INDEX "QuestionnaireInterview_artisanSetKey_key"
    ON "QuestionnaireInterview"("artisanSetKey");
CREATE TABLE "Workshop" (
    "id" TEXT PRIMARY KEY, "title" TEXT NOT NULL, "createdAt" TEXT NOT NULL,
    "updatedAt" TEXT NOT NULL
);
"""

W2_CODES = ["RESP"] + [chr(ord("A") + i) for i in range(23)]


def _seed(connection: sqlite3.Connection, *, with_users: bool = True) -> None:
    if with_users:
        connection.executemany(
            'INSERT INTO "User" VALUES (?, ?, ?, ?, ?)',
            [
                ("u-old", "old@example.test", "Old researcher", "RESEARCHER", "2024-01-01"),
                ("u-admin", "admin@example.test", "An admin", "ADMIN", "2025-01-01"),
                ("u-master", "master@example.test", "The owner", "MASTER_ADMIN", "2026-01-01"),
            ],
        )
    connection.executemany(
        'INSERT INTO "QuestionnaireSection" VALUES (?, ?, ?, ?, 1, ?, ?)',
        [
            (f"sec-{code}", code, f"Section {code}", order, "2026-01-01", "2026-01-01")
            for order, code in enumerate(W2_CODES, start=1)
        ],
    )
    per_section = [12] * 20 + [11] * 4
    assert sum(per_section) == 284
    questions = []
    for (order, code), count in zip(enumerate(W2_CODES, start=1), per_section, strict=True):
        for n in range(1, count + 1):
            questions.append(
                (
                    f"q-{code}-{n}",
                    f"sec-{code}",
                    code,
                    f"Section {code}",
                    f"Prompt {code}{n} — with an em dash",
                    n,
                    "2026-01-01",
                    "2026-01-01",
                )
            )
    questions.append(
        ("q-orphan", None, "A", "Section A", "A question added through the builder", 99,
         "2026-01-01", "2026-01-01")
    )
    connection.executemany(
        'INSERT INTO "QuestionnaireQuestion" '
        '("id", "sectionId", "sectionCode", "sectionTitle", "prompt", "sortOrder", "createdAt", '
        '"updatedAt") VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
        questions,
    )
    connection.executemany(
        'INSERT INTO "QuestionnaireInterview" VALUES (?, ?, ?, ?, ?, ?)',
        [
            ("iv-1", "Vikram alone", "a1", "w-1", "2026-02-01", "2026-02-01"),
            ("iv-2", "Group of three", "a1,a2,a3", "w-1", "2026-02-02", "2026-02-02"),
            ("iv-3", "No artisans named", None, None, "2026-02-03", "2026-02-03"),
        ],
    )
    connection.executemany(
        'INSERT INTO "Workshop" VALUES (?, ?, ?, ?)',
        [
            ("w-1", "2nd Craft Toolkit Workshop, Almora", "2026-01-10", "2026-01-10"),
            ("w-2", "3rd Craft Toolkit Workshop, Dehradun", "2026-09-01", "2026-09-01"),
        ],
    )


def _connect(*, with_users: bool = True) -> sqlite3.Connection:
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.executescript(SCHEMA_BEFORE)
    _seed(connection, with_users=with_users)
    return connection


def _migrate(connection: sqlite3.Connection) -> None:
    connection.executescript(_translate(MIGRATION.read_text(encoding="utf-8")))


def _rows(connection: sqlite3.Connection, table: str) -> list[dict[str, Any]]:
    return [dict(row) for row in connection.execute(f'SELECT * FROM "{table}" ORDER BY "id"')]


def _row_hash(connection: sqlite3.Connection) -> str:
    digest = hashlib.sha256()
    for table in TABLES:
        for row in _rows(connection, table):
            digest.update(repr(sorted(row.items())).encode("utf-8"))
    return digest.hexdigest()


@pytest.fixture()
def migrated() -> sqlite3.Connection:
    connection = _connect()
    try:
        _migrate(connection)
        yield connection
    finally:
        connection.close()



def test_every_existing_section_question_and_interview_is_adopted_by_the_second_workshop_instrument(
    migrated: sqlite3.Connection,
) -> None:
    for table, expected in (
        ("QuestionnaireSection", 24),
        ("QuestionnaireQuestion", 285),
        ("QuestionnaireInterview", 3),
        ("Workshop", 2),
    ):
        orphans = migrated.execute(
            f'SELECT COUNT(*) FROM "{table}" WHERE "questionnaireId" IS NULL'
        ).fetchone()[0]
        assert orphans == 0, f"{table} left {orphans} row(s) without an instrument"
        adopted = migrated.execute(
            f'SELECT COUNT(*) FROM "{table}" WHERE "questionnaireId" = ?', (W2,)
        ).fetchone()[0]
        assert adopted == expected, f"{table}: {adopted} adopted, expected {expected}"


def test_the_question_with_no_section_is_adopted_like_every_other(
    migrated: sqlite3.Connection,
) -> None:
    row = migrated.execute(
        'SELECT "sectionId", "questionnaireId" FROM "QuestionnaireQuestion" WHERE "id" = ?',
        ("q-orphan",),
    ).fetchone()
    assert row["sectionId"] is None
    assert row["questionnaireId"] == W2


def test_no_id_prompt_or_answer_is_rewritten_by_the_migration() -> None:
    connection = _connect()
    try:
        before_structure = [
            (r["id"], r["code"], r["title"], r["sortOrder"])
            for r in _rows(connection, "QuestionnaireSection")
        ] + [
            (r["id"], r["prompt"], r["sortOrder"], r["sectionCode"])
            for r in _rows(connection, "QuestionnaireQuestion")
        ]
        before_interviews = [
            (r["id"], r["artisanSetKey"]) for r in _rows(connection, "QuestionnaireInterview")
        ]

        _migrate(connection)

        after_structure = [
            (r["id"], r["code"], r["title"], r["sortOrder"])
            for r in _rows(connection, "QuestionnaireSection")
        ] + [
            (r["id"], r["prompt"], r["sortOrder"], r["sectionCode"])
            for r in _rows(connection, "QuestionnaireQuestion")
        ]
        after_interviews = [
            (r["id"], r["artisanSetKey"]) for r in _rows(connection, "QuestionnaireInterview")
        ]
        assert after_structure == before_structure
        assert after_interviews == before_interviews
    finally:
        connection.close()


def test_the_instrument_is_created_owned_by_the_master_admin_and_is_the_default(
    migrated: sqlite3.Connection,
) -> None:
    row = migrated.execute('SELECT * FROM "Questionnaire" WHERE "id" = ?', (W2,)).fetchone()
    assert row is not None
    assert row["title"] == "2nd Craft Toolkit Workshop"
    assert row["isDefault"] in (1, True)
    assert row["sortOrder"] == 1
    assert row["createdById"] == "u-master"



def _index_names(connection: sqlite3.Connection) -> set[str]:
    return {
        row["name"]
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'index'")
        if row["name"]
    }


def test_the_old_global_uniques_are_gone_and_the_scoped_ones_refuse_a_duplicate(
    migrated: sqlite3.Connection,
) -> None:
    names = _index_names(migrated)
    assert "QuestionnaireSection_code_key" not in names
    assert "QuestionnaireSection_sortOrder_key" not in names
    assert "QuestionnaireInterview_artisanSetKey_key" not in names
    assert "QuestionnaireSection_questionnaireId_code_key" in names
    assert "QuestionnaireSection_questionnaireId_sortOrder_key" in names

    migrated.execute(
        'INSERT INTO "Questionnaire" '
        '("id","title","isActive","isDefault","sortOrder","createdAt","updatedAt") '
        "VALUES ('qnr-w3','3rd Craft Toolkit Workshop',1,0,2,'2026-09-13','2026-09-13')"
    )

    migrated.execute(
        'INSERT INTO "QuestionnaireSection" '
        '("id","questionnaireId","code","title","sortOrder","isActive","createdAt","updatedAt") '
        "VALUES ('w3-a','qnr-w3','A','Origin and journey',1,1,'2026-09-13','2026-09-13')"
    )

    with pytest.raises(sqlite3.IntegrityError):
        migrated.execute(
            'INSERT INTO "QuestionnaireSection" '
            '("id","questionnaireId","code","title","sortOrder","isActive","createdAt","updatedAt") '
            "VALUES ('w3-a2','qnr-w3','A','A second A',9,1,'2026-09-13','2026-09-13')"
        )
    with pytest.raises(sqlite3.IntegrityError):
        migrated.execute(
            'INSERT INTO "QuestionnaireSection" '
            '("id","questionnaireId","code","title","sortOrder","isActive","createdAt","updatedAt") '
            "VALUES ('w3-b','qnr-w3','B','Slot one again',1,1,'2026-09-13','2026-09-13')"
        )


def test_two_interviews_may_share_an_artisan_set_across_instruments_and_not_within_one(
    migrated: sqlite3.Connection,
) -> None:
    migrated.execute(
        'INSERT INTO "Questionnaire" '
        '("id","title","isActive","isDefault","sortOrder","createdAt","updatedAt") '
        "VALUES ('qnr-w3','3rd Craft Toolkit Workshop',1,0,2,'2026-09-13','2026-09-13')"
    )
    migrated.execute(
        'INSERT INTO "QuestionnaireInterview" '
        '("id","title","artisanSetKey","questionnaireId","createdAt","updatedAt") '
        "VALUES ('iv-w3','Same artisans, third workshop','a1,a2,a3','qnr-w3','2026-09-13','2026-09-13')"
    )
    with pytest.raises(sqlite3.IntegrityError):
        migrated.execute(
            'INSERT INTO "QuestionnaireInterview" '
            '("id","title","artisanSetKey","questionnaireId","createdAt","updatedAt") '
            "VALUES ('iv-dup','A duplicate sitting','a1,a2,a3','qnr-w3','2026-09-13','2026-09-13')"
        )
    for suffix in ("x", "y"):
        migrated.execute(
            'INSERT INTO "QuestionnaireInterview" '
            '("id","title","artisanSetKey","questionnaireId","createdAt","updatedAt") '
            f"VALUES ('iv-none-{suffix}','No artisans',NULL,'qnr-w3','2026-09-13','2026-09-13')"
        )


def test_at_most_one_questionnaire_may_be_the_default(migrated: sqlite3.Connection) -> None:
    assert "Questionnaire_isDefault_key" in _index_names(migrated)
    with pytest.raises(sqlite3.IntegrityError):
        migrated.execute(
            'INSERT INTO "Questionnaire" '
            '("id","title","isActive","isDefault","sortOrder","createdAt","updatedAt") '
            "VALUES ('qnr-w3','3rd Craft Toolkit Workshop',1,1,2,'2026-09-13','2026-09-13')"
        )
    for suffix in ("a", "b"):
        migrated.execute(
            'INSERT INTO "Questionnaire" '
            '("id","title","isActive","isDefault","sortOrder","createdAt","updatedAt") '
            f"VALUES ('qnr-{suffix}','Instrument {suffix}',1,0,3,'2026-09-13','2026-09-13')"
        )



def test_running_the_migration_twice_changes_nothing() -> None:
    connection = _connect()
    try:
        _migrate(connection)
        before = _row_hash(connection)

        errors: list[str] = []
        for statement in _statements(_translate(MIGRATION.read_text(encoding="utf-8"))):
            try:
                connection.execute(statement)
            except sqlite3.OperationalError as exc:
                errors.append(str(exc))
        assert len(errors) == 4, errors
        assert all("duplicate column name" in message for message in errors), errors

        assert _row_hash(connection) == before
    finally:
        connection.close()


def test_a_detached_workshop_is_not_re_attached_by_a_second_run() -> None:
    connection = _connect()
    try:
        _migrate(connection)
        connection.execute(
            'UPDATE "Workshop" SET "questionnaireId" = NULL WHERE "id" = ?', ("w-2",)
        )

        for statement in _statements(_translate(MIGRATION.read_text(encoding="utf-8"))):
            try:
                connection.execute(statement)
            except sqlite3.OperationalError as exc:
                assert "duplicate column name" in str(exc), exc

        detached = connection.execute(
            'SELECT "questionnaireId" FROM "Workshop" WHERE "id" = ?', ("w-2",)
        ).fetchone()[0]
        assert detached is None, "the re-run re-attached a workshop an admin had detached"
        markers = connection.execute(
            'SELECT COUNT(*) FROM "QuestionnaireMigrationMarker" WHERE "name" = ?',
            ("workshop_questionnaire_backfill",),
        ).fetchone()[0]
        assert markers == 1
    finally:
        connection.close()


def test_a_second_run_does_not_re_assert_the_default_on_the_second_workshop() -> None:
    connection = _connect()
    try:
        _migrate(connection)
        connection.execute('UPDATE "Questionnaire" SET "isDefault" = 0 WHERE "id" = ?', (W2,))
        connection.execute(
            'INSERT INTO "Questionnaire" '
            '("id","title","isActive","isDefault","sortOrder","createdAt","updatedAt") '
            "VALUES ('qnr-w3','3rd Craft Toolkit Workshop',1,1,2,'2026-09-13','2026-09-13')"
        )

        for statement in _statements(_translate(MIGRATION.read_text(encoding="utf-8"))):
            try:
                connection.execute(statement)
            except sqlite3.OperationalError as exc:
                assert "duplicate column name" in str(exc), exc

        defaults = [
            row["id"]
            for row in connection.execute('SELECT "id" FROM "Questionnaire" WHERE "isDefault" = 1')
        ]
        assert defaults == ["qnr-w3"]
    finally:
        connection.close()



def test_the_migration_succeeds_on_a_database_with_no_accounts() -> None:
    connection = _connect(with_users=False)
    try:
        _migrate(connection)
        row = connection.execute('SELECT * FROM "Questionnaire" WHERE "id" = ?', (W2,)).fetchone()
        assert row is not None
        assert row["createdById"] is None
    finally:
        connection.close()
