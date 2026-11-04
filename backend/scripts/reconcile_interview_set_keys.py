import asyncio
import sys
from collections import defaultdict

from app.core.db import connect_db, db, disconnect_db
from app.api.routes.questionnaire import artisan_set_key

Slot = tuple[str, str]


async def media_count(interview_id: str) -> int:
    return await db.mediafile.count(where={"questionnaireInterviewId": interview_id})


def group_by_slot(interviews: list) -> tuple[dict[str, str | None], dict[Slot, list[str]]]:
    correct_key: dict[str, str | None] = {}
    by_slot: dict[Slot, list[str]] = defaultdict(list)
    for iv in interviews:
        key = artisan_set_key([link.artisanId for link in (iv.artisans or [])])
        correct_key[iv.id] = key
        if key:
            by_slot[(iv.questionnaireId, key)].append(iv.id)
    return correct_key, dict(by_slot)


V2_REFUSAL = (
    "REFUSED: questionnaire v2 sessions exist. Under v2 a session is a dated event and two sessions "
    "sharing their artisans is normal (design D1), and this script would merge them while moving only "
    "their media: recordings, participants, researchers and answers would be left on deleted rows. "
    "Merge sessions with POST /api/questionnaire/sessions/{id}/merge-into/{targetId} instead."
)


async def v2_in_use() -> bool:
    from app.core.config import get_settings

    if getattr(get_settings(), "questionnaire_v2", False):
        return True
    if await db.questionnairerecording.count() > 0:
        return True
    return await db.questionnaireinterview.count(where={"origin": "LEGACY_RECONSTRUCTED"}) > 0


async def main() -> None:
    execute = "--execute" in sys.argv
    print("MODE:", "EXECUTE" if execute else "DRY RUN (no changes)")
    await connect_db()
    try:
        if execute and await v2_in_use():
            print(V2_REFUSAL)
            return
        interviews = await db.questionnaireinterview.find_many(include={"artisans": True})
        correct_key, by_slot = group_by_slot(interviews)

        stale = [iv.id for iv in interviews if correct_key[iv.id] != iv.artisanSetKey]
        dup_groups = {k: v for k, v in by_slot.items() if len(v) > 1}
        print(
            f"interviews={len(interviews)} stale_keys={len(stale)} "
            f"duplicate_set_groups={len(dup_groups)}"
        )

        if execute:
            await db.questionnaireinterview.update_many(where={}, data={"artisanSetKey": None})

        survivors: dict[Slot, str] = {}
        for slot, ids in by_slot.items():
            questionnaire_id, key = slot
            if len(ids) == 1:
                survivors[slot] = ids[0]
                continue
            counts = {i: await media_count(i) for i in ids}
            iv_by_id = {iv.id: iv for iv in interviews}
            canonical = sorted(ids, key=lambda i: (-counts[i], iv_by_id[i].createdAt, i))[0]
            survivors[slot] = canonical
            dups = [i for i in ids if i != canonical]
            print(
                f"  consolidate set {key[:40]}… on instrument {questionnaire_id}: "
                f"keep {canonical} (media={counts[canonical]}), fold {dups}"
            )
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

        for (_questionnaire_id, key), survivor in survivors.items():
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
