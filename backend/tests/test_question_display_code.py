"""Question display codes from the insert trigger, and the backfill of codes the old trigger got wrong
(prisma/migrations/20261102090000_question_display_code). Real Postgres.

The old trigger read the next number from the trailing digits of the code, so a section code ending
in a digit lent its digits to the number: "F2" gave F21, F222, F2223, and an all-digit stamp
overflowed integer within a few inserts (CI run 39207911785, tests/search_support.py).
"""

from __future__ import annotations

import uuid
from pathlib import Path

from qv2_fixtures import connect, needs_db, run, seed

pytestmark = needs_db

MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "prisma"
    / "migrations"
    / "20261102090000_question_display_code"
    / "migration.sql"
)


async def _instrument(client) -> str:
    qid = f"qdc{uuid.uuid4().hex[:10]}"
    await client.execute_raw(
        'INSERT INTO "Questionnaire" ("id", "title", "sortOrder", "updatedAt") VALUES ($1, $2, 1, now())',
        qid,
        f"Display codes {qid}",
    )
    return qid


async def _question(
    client,
    questionnaire: str,
    section: str,
    n: int,
    *,
    code: str | None = None,
    lineage: str | None = None,
) -> str:
    qid = f"qq{uuid.uuid4().hex[:12]}"
    await client.execute_raw(
        'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", '
        '"displayCode", "lineageId", "createdAt", "updatedAt") '
        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, TIMESTAMP '2026-10-19 09:00:00' + $6 * INTERVAL '1 second', now())",
        qid,
        questionnaire,
        section,
        f"Section {section}",
        f"Question {n} of {section}",
        n,
        code,
        lineage,
    )
    return qid


async def _codes(client, ids: list[str]) -> list[str]:
    rows = await client.query_raw(
        'SELECT "id", "displayCode" FROM "QuestionnaireQuestion" WHERE "id" = ANY($1::text[])', ids
    )
    by_id = {str(r["id"]): r["displayCode"] for r in rows}
    return [by_id[i] for i in ids]


def test_a_section_code_ending_in_digits_numbers_after_its_own_prefix() -> None:
    async def go() -> None:
        client = await connect()
        q = await _instrument(client)
        f2 = [await _question(client, q, "F2", n) for n in range(1, 4)]
        f = [await _question(client, q, "F", n) for n in range(1, 3)]
        lower = await _question(client, q, "f2", 4)
        assert await _codes(client, f2) == ["F21", "F22", "F23"]
        # "F" does not read F21..F23 of section "F2" as its own 21..23
        assert await _codes(client, f) == ["F1", "F2"]
        assert await _codes(client, [lower]) == ["F24"]
        await client.disconnect()

    run(go())


def test_an_all_digit_stamp_never_overflows_and_counts_up_by_one() -> None:
    async def go() -> None:
        client = await connect()
        q = await _instrument(client)
        for section in ("S-202610191234", "2026101912345678"):
            made = [await _question(client, q, section, n) for n in range(1, 13)]
            assert await _codes(client, made) == [f"{section}{n}" for n in range(1, 13)]
        await client.disconnect()

    run(go())


def test_the_next_code_steps_past_a_code_the_instrument_already_holds() -> None:
    async def go() -> None:
        client = await connect()
        q = await _instrument(client)
        first_f2 = await _question(client, q, "F2", 1)
        f = [await _question(client, q, "F", n) for n in range(1, 22)]
        second_f2 = await _question(client, q, "F2", 2)
        # "F" + "21" and "F2" + "1" spell the same code: whichever comes second steps past it
        assert await _codes(client, [first_f2, f[19], f[20], second_f2]) == [
            "F21",
            "F20",
            "F22",
            "F23",
        ]
        await client.disconnect()

    run(go())


def test_the_backfill_renumbers_unread_mis_assigned_codes_and_keeps_referenced_ones() -> None:
    backfill = MIGRATION.read_text(encoding="utf-8")
    backfill = backfill[backfill.index("DO $$") :]

    async def go() -> None:
        client = await connect()
        ids = await seed(client, sections={"B": 1}, artisans=1)
        q = ids.q
        # what the old trigger wrote for four questions in section "F2"
        good = await _question(client, q, "F2", 1, code="F21")
        bad = await _question(client, q, "F2", 2, code="F222")
        read = await _question(client, q, "F2", 3, code="F2223")
        overflowed = await _question(client, q, "F2", 4, code="F22232224222")
        # a reword of the second: the supersede trigger carries the frozen (wrong) code to the replacement
        reword = await _question(client, q, "F2", 2, lineage=bad)
        await client.execute_raw(
            'UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false, "supersededById" = $2 WHERE "id" = $1',
            bad,
            reword,
        )
        # the third has an answer: its code may already have been read, so it must not move
        await client.execute_raw(
            'INSERT INTO "QuestionnaireAnswer" ("id", "artisanId", "questionnaireId", "questionLineageId", "updatedAt") '
            "VALUES ($1, $2, $3, $4, now())",
            f"ans{uuid.uuid4().hex[:10]}",
            ids.artisans[0],
            q,
            read,
        )
        await client.execute_raw(backfill)
        assert await _codes(client, [good, bad, reword, read, overflowed]) == [
            "F21",
            "F22",
            "F22",
            "F2223",
            "F23",
        ]
        # running it again changes nothing
        await client.execute_raw(backfill)
        assert await _codes(client, [good, bad, reword, read, overflowed]) == [
            "F21",
            "F22",
            "F22",
            "F2223",
            "F23",
        ]
        # the seeded section stays as it was
        assert await _codes(client, [ids.questions["B1"]]) == ["B1"]
        # the fixed trigger continues after the kept code
        nxt = await _question(client, q, "F2", 5)
        assert await _codes(client, [nxt]) == ["F2224"]
        await client.disconnect()

    run(go())
