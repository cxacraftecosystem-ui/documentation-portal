"""``pending_keys_sql`` RUN against a real, migrated Postgres (B13).

``test_review_pending_paging.py`` replaces the key query with a stub and reads the SQL as text, so
the parts only Postgres can judge — the enum compared as text, ``$N`` parameters reused across the
UNION ALL, the roster ``EXISTS``, the artisan clauses — are exercised here.

It needs its own database, never ``backend/.env`` (that is production). Point
``REVIEW_SQL_TEST_DATABASE_URL`` at a migrated throwaway clone; without it the test skips and says
why. It seeds a few rows under unique ids and deletes them again.
"""

from __future__ import annotations

import asyncio
import os
import uuid

import pytest

from app.api.routes import review

URL = os.environ.get("REVIEW_SQL_TEST_DATABASE_URL", "").strip()

pytestmark = pytest.mark.skipif(
    not URL, reason="REVIEW_SQL_TEST_DATABASE_URL is not set (a migrated throwaway database)"
)


def test_the_key_query_runs_with_every_filter_on_a_real_database() -> None:
    from prisma import Prisma

    async def run() -> None:
        client = Prisma(datasource={"url": URL})
        await client.connect()
        tag = uuid.uuid4().hex[:10]
        author, other = f"u{tag}", f"v{tag}"
        workshop, a1, a2, a3 = f"w{tag}", f"a1{tag}", f"a2{tag}", f"a3{tag}"
        try:
            await client.execute_raw(
                'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES '
                "($1, $2, 'Author', 'RESEARCHER', now()), ($3, $4, 'Other', 'ADMIN', now())",
                author, f"{author}@example.test", other, f"{other}@example.test",
            )
            await client.execute_raw(
                'INSERT INTO "Workshop" ("id", "title", "date", "place", "status", "createdById", "updatedAt", "submittedAt") '
                "VALUES ($1, 'Bagru', now(), 'Bagru', 'PENDING', $2, now(), now())",
                workshop, author,
            )
            await client.execute_raw(
                'INSERT INTO "Artisan" ("id", "name", "place", "status", "createdById", "workshopId", "updatedAt", "submittedAt") VALUES '
                "($1, 'One', 'Bagru', 'PENDING', $4, $5, now(), now() - interval '2 hours'),"
                "($2, 'Two', 'Bagru', 'PENDING', $4, NULL, now(), now() - interval '1 hour'),"
                "($3, 'Three', 'Bagru', 'PENDING', $6, $5, now(), now())",
                a1, a2, a3, author, workshop, other,
            )
            await client.execute_raw(
                'INSERT INTO "WorkshopArtisan" ("workshopId", "artisanId") VALUES ($1, $2)', workshop, a2
            )

            async def keys(filters: review.PendingFilters) -> list[tuple[str, str]]:
                sql, params = review.pending_keys_sql(filters, limit=50, offset=0)
                rows = await client.query_raw(sql, *params)
                return [(str(r["recordType"]), str(r["id"])) for r in rows]

            # Role ladder + workshop (column OR roster) + creator: a1 by column, a2 by roster, a3 is
            # an admin's (outside the ladder); the workshop row itself is the author's.
            found = await keys(
                review.PendingFilters(roles=["RESEARCHER", "FIELD_CONTRIBUTOR"], workshop_id=workshop, created_by=author)
            )
            assert ("artisan", a1) in found and ("artisan", a2) in found and ("workshop", workshop) in found
            assert ("artisan", a3) not in found
            assert [k for k in found if k[0] == "artisan"] == [("artisan", a1), ("artisan", a2)]  # oldest first

            # A task's artisans: only those, and never a workshop row (it has no artisan).
            scoped = await keys(review.PendingFilters(workshop_id=workshop, artisan_ids=(a2,)))
            assert scoped == [("artisan", a2)]

            # Every record type with every filter at once still compiles and runs.
            await keys(
                review.PendingFilters(
                    roles=["RESEARCHER"], workshop_id=workshop, created_by=author, artisan_ids=(a1, a2), newest_first=True
                )
            )
        finally:
            await client.execute_raw('DELETE FROM "WorkshopArtisan" WHERE "workshopId" = $1', workshop)
            await client.execute_raw('DELETE FROM "Artisan" WHERE "id" IN ($1, $2, $3)', a1, a2, a3)
            await client.execute_raw('DELETE FROM "Workshop" WHERE "id" = $1', workshop)
            await client.execute_raw('DELETE FROM "User" WHERE "id" IN ($1, $2)', author, other)
            await client.disconnect()

    asyncio.run(run())
