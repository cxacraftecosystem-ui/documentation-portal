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
        good = await _question(client, q, "F2", 1, code="F21")
        bad = await _question(client, q, "F2", 2, code="F222")
        read = await _question(client, q, "F2", 3, code="F2223")
        overflowed = await _question(client, q, "F2", 4, code="F22232224222")
        reword = await _question(client, q, "F2", 2, lineage=bad)
        await client.execute_raw(
            'UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false, "supersededById" = $2 WHERE "id" = $1',
            bad,
            reword,
        )
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
        await client.execute_raw(backfill)
        assert await _codes(client, [good, bad, reword, read, overflowed]) == [
            "F21",
            "F22",
            "F22",
            "F2223",
            "F23",
        ]
        assert await _codes(client, [ids.questions["B1"]]) == ["B1"]
        nxt = await _question(client, q, "F2", 5)
        assert await _codes(client, [nxt]) == ["F2224"]
        await client.disconnect()

    run(go())
