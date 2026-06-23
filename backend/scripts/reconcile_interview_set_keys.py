"""Reconcile QuestionnaireInterview.artisanSetKey with each interview's CURRENT artisan links.

Why: an out-of-band change to the artisan links (e.g. the artisan-merge script) can leave the
denormalised ``artisanSetKey`` stale, and can make two interviews share the same actual artisan set.
Editing such an interview recomputes the key and collides with the sibling -> HTTP 409. This script
heals both: it consolidates duplicate-set interviews into one canonical row (re-pointing media and
responses, like the idempotency migration) and rewrites every surviving key to match its links.

Order is collision-safe: NULL every key first (NULLs are exempt from the unique index), consolidate,
then set the correct keys (one survivor per set, so no two writes ever clash).

Usage:
    python -m scripts.reconcile_interview_set_keys            # DRY RUN
    python -m scripts.reconcile_interview_set_keys --execute  # apply
"""

import asyncio
import sys
from collections import defaultdict

from app.core.db import connect_db, db, disconnect_db
from app.api.routes.questionnaire import artisan_set_key


async def media_count(interview_id: str) -> int:
    return await db.mediafile.count(where={"questionnaireInterviewId": interview_id})


async def main() -> None:
    execute = "--execute" in sys.argv
    print("MODE:", "EXECUTE" if execute else "DRY RUN (no changes)")
    await connect_db()
    try:
        interviews = await db.questionnaireinterview.find_many(include={"artisans": True})
        correct_key: dict[str, str | None] = {}
        by_key: dict[str, list[str]] = defaultdict(list)
        for iv in interviews:
            key = artisan_set_key([link.artisanId for link in (iv.artisans or [])])
            correct_key[iv.id] = key
            if key:
                by_key[key].append(iv.id)

        stale = [iv.id for iv in interviews if correct_key[iv.id] != iv.artisanSetKey]
        dup_groups = {k: v for k, v in by_key.items() if len(v) > 1}
        print(f"interviews={len(interviews)} stale_keys={len(stale)} duplicate_set_groups={len(dup_groups)}")

        # Phase 1: clear all keys so later writes can't transiently collide.
        if execute:
            await db.questionnaireinterview.update_many(where={}, data={"artisanSetKey": None})

        # Phase 2: consolidate duplicate-set interviews into a canonical survivor.
        survivors: dict[str, str] = {}  # key -> survivor interview id
        for key, ids in by_key.items():
            if len(ids) == 1:
                survivors[key] = ids[0]
                continue
            # Pick canonical = most media, tie-break earliest createdAt then id.
            counts = {i: await media_count(i) for i in ids}
            iv_by_id = {iv.id: iv for iv in interviews}
            canonical = sorted(ids, key=lambda i: (-counts[i], iv_by_id[i].createdAt, i))[0]
            survivors[key] = canonical
            dups = [i for i in ids if i != canonical]
            print(f"  consolidate set {key[:40]}…: keep {canonical} (media={counts[canonical]}), "
                  f"fold {dups}")
            for dup in dups:
                if execute:
                    await db.mediafile.update_many(
                        where={"questionnaireInterviewId": dup}, data={"questionnaireInterviewId": canonical}
                    )
                    await db.mediafile.update_many(
                        where={"linkedRecordType": "questionnaire", "linkedRecordId": dup},
                        data={"linkedRecordId": canonical},
                    )
                    await db.mediafile.update_many(
                        where={"linkedRecordType": "questionnaireinterview", "linkedRecordId": dup},
                        data={"linkedRecordId": canonical},
                    )
                    # Move responses that don't collide; the cascade drops the colliding ones on delete.
                    dup_resp = await db.questionnaireresponse.find_many(where={"interviewId": dup})
                    for r in dup_resp:
                        clash = await db.questionnaireresponse.find_first(
                            where={"interviewId": canonical, "questionId": r.questionId}
                        )
                        if not clash:
                            await db.questionnaireresponse.update(
                                where={"id": r.id}, data={"interviewId": canonical}
                            )
                    await db.questionnaireinterview.delete(where={"id": dup})

        # Phase 3: write the correct key onto each survivor.
        for key, survivor in survivors.items():
            if execute:
                await db.questionnaireinterview.update(
                    where={"id": survivor}, data={"artisanSetKey": key}
                )
        print(f"survivors={len(survivors)}")
    finally:
        await disconnect_db()
    print("\nDone." if execute else "\nDry run complete — re-run with --execute to apply.")


if __name__ == "__main__":
    asyncio.run(main())
