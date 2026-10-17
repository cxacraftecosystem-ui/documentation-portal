"""The approval cascade RUN against a real, migrated Postgres (owner decision 2026-10-18).

``test_review_media_cascade.py`` holds the rules against an in-memory stand-in. What only Postgres
and the Prisma engine can judge is held here: the ``OR`` of tag and column clauses with an
insensitive match, the first interactive transaction this backend opens (``db.tx()``) committing a
record with its files, and rolling all of it back when a write fails part-way.

It needs its own database, never ``backend/.env`` (that is production). Point
``REVIEW_SQL_TEST_DATABASE_URL`` at a migrated throwaway clone; without it the test skips and says
why. It seeds a few rows under unique ids and deletes them again.
"""

from __future__ import annotations

import asyncio
import os
import uuid
from typing import Any

import pytest

import scripts.approve_media_of_approved_records as backfill
from app.api.routes import review
from app.schemas.common import ReviewAction
from app.services import email_outbox

URL = os.environ.get("REVIEW_SQL_TEST_DATABASE_URL", "").strip()

pytestmark = pytest.mark.skipif(
    not URL, reason="REVIEW_SQL_TEST_DATABASE_URL is not set (a migrated throwaway database)"
)


async def _seed(client: Any, tag: str) -> dict[str, str]:
    ids = {
        "author": f"u{tag}", "prof": f"p{tag}", "a1": f"a1{tag}", "a2": f"a2{tag}",
        "m1": f"m1{tag}", "m2": f"m2{tag}", "m3": f"m3{tag}", "rej": f"mr{tag}", "m4": f"m4{tag}",
    }
    await client.execute_raw(
        'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES '
        "($1, $2, 'Author', 'RESEARCHER', now()), ($3, $4, 'Prof', 'PROFESSOR', now())",
        ids["author"], f"{ids['author']}@example.test", ids["prof"], f"{ids['prof']}@example.test",
    )
    await client.execute_raw(
        'INSERT INTO "Artisan" ("id", "name", "place", "status", "createdById", "updatedAt", "submittedAt") VALUES '
        "($1, 'One', 'Bagru', 'PENDING', $3, now(), now()), ($2, 'Two', 'Bagru', 'PENDING', $3, now(), now())",
        ids["a1"], ids["a2"], ids["author"],
    )
    rows = [
        (ids["m1"], "PENDING", "artisan", ids["a1"]),
        (ids["m2"], "PENDING", "ARTISAN", ids["a1"]),
        (ids["m3"], "PENDING", None, None),
        (ids["rej"], "REJECTED", "artisan", ids["a1"]),
        (ids["m4"], "PENDING", "artisan", ids["a2"]),
    ]
    for media_id, state, linked_type, linked_id in rows:
        await client.execute_raw(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", '
            '"objectKey", "status", "linkedRecordType", "linkedRecordId", "artisanId", "uploadedById", "updatedAt") '
            "VALUES ($1, 'photo.jpg', 'IMAGE', 'image/jpeg', 10, 'test', $2, $3::\"RecordStatus\", $4, $5, $6, $7, now())",
            media_id, f"test/{media_id}.jpg", state, linked_type, linked_id,
            ids["a2"] if media_id == ids["m4"] else ids["a1"], ids["author"],
        )
    return ids


async def _cleanup(client: Any, ids: dict[str, str]) -> None:
    media = [ids[k] for k in ("m1", "m2", "m3", "rej", "m4")]
    await client.execute_raw('DELETE FROM "ReviewLog" WHERE "reviewerId" = $1', ids["prof"])
    await client.execute_raw('DELETE FROM "MediaFile" WHERE "id" = ANY($1::text[])', media)
    await client.execute_raw('DELETE FROM "Artisan" WHERE "id" IN ($1, $2)', ids["a1"], ids["a2"])
    await client.execute_raw('DELETE FROM "User" WHERE "id" IN ($1, $2)', ids["author"], ids["prof"])


async def _statuses(client: Any, ids: dict[str, str]) -> dict[str, str]:
    rows = await client.query_raw(
        'SELECT "id", "status"::text AS status FROM "MediaFile" WHERE "id" = ANY($1::text[]) '
        'UNION ALL SELECT "id", "status"::text FROM "Artisan" WHERE "id" IN ($2, $3)',
        [ids[k] for k in ("m1", "m2", "m3", "rej", "m4")], ids["a1"], ids["a2"],
    )
    back = {v: k for k, v in ids.items()}
    return {back[str(row["id"])]: str(row["status"]) for row in rows}


def test_an_approval_commits_the_record_with_its_files_and_a_failure_rolls_all_back(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from prisma import Prisma

    async def no_mail(**kwargs: Any) -> int:
        return 0

    monkeypatch.setattr(email_outbox, "notify_review_decision", no_mail)

    async def run() -> None:
        client = Prisma(datasource={"url": URL})
        await client.connect()
        monkeypatch.setattr(review, "db", client)
        ids = await _seed(client, uuid.uuid4().hex[:10])
        try:
            prof = await client.user.find_unique(where={"id": ids["prof"]})
            assert (await review.pending_media_counts([("artisan", ids["a1"]), ("artisan", ids["a2"])], prof)) == {
                ("artisan", ids["a1"]): 3, ("artisan", ids["a2"]): 1,
            }

            # A write failing inside the transaction takes the record's approval with it.
            real = review.approve_media_with_record

            async def fail_after_writing(client_: Any, *args: Any, **kwargs: Any) -> int:
                await real(client_, *args, **kwargs)
                raise RuntimeError("the connection dropped")

            monkeypatch.setattr(review, "approve_media_with_record", fail_after_writing)
            with pytest.raises(RuntimeError):
                await review.set_review_status("artisan", ids["a2"], "APPROVED", ReviewAction(notes=None), prof)
            assert (await _statuses(client, ids))["a2"] == "PENDING"
            assert (await _statuses(client, ids))["m4"] == "PENDING"
            assert await client.reviewlog.count(where={"reviewerId": ids["prof"]}) == 0
            monkeypatch.setattr(review, "approve_media_with_record", real)

            answer = await review.set_review_status("artisan", ids["a1"], "APPROVED", ReviewAction(notes=None), prof)
            assert answer["mediaApproved"] == 3
            states = await _statuses(client, ids)
            assert states == {"a1": "APPROVED", "a2": "PENDING", "m1": "APPROVED", "m2": "APPROVED", "m3": "APPROVED",
                              "rej": "REJECTED", "m4": "PENDING"}
            record = await client.artisan.find_unique(where={"id": ids["a1"]})
            m1 = await client.mediafile.find_unique(where={"id": ids["m1"]})
            assert m1.reviewedById == ids["prof"] and m1.reviewedAt == record.reviewedAt
            logs = await client.reviewlog.find_many(where={"reviewerId": ids["prof"], "recordType": "MEDIA"})
            assert sorted(log.recordId for log in logs) == sorted([ids["m1"], ids["m2"], ids["m3"]])

            # The backfill: a2 approved the old way (record only) has one file left to bring along.
            await client.execute_raw(
                'UPDATE "Artisan" SET "status" = \'APPROVED\', "reviewedById" = $2, "reviewedAt" = now() WHERE "id" = $1',
                ids["a2"], ids["prof"],
            )
            dry = await backfill.run(client, apply=False, kinds=("artisan",))
            assert dry.by_type["artisan"].files >= 1 and (await _statuses(client, ids))["m4"] == "PENDING"
            done = await backfill.run(client, apply=True, kinds=("artisan",))
            assert done.files == dry.files and (await _statuses(client, ids))["m4"] == "APPROVED"
            assert (await backfill.run(client, apply=False, kinds=("artisan",))).files == 0
        finally:
            await _cleanup(client, ids)
            await client.disconnect()

    asyncio.run(run())
