"""Reconcile QuestionnaireInterview.artisanSetKey with each interview's CURRENT artisan links.

Why: an out-of-band change to the artisan links (e.g. the artisan-merge script) can leave the
denormalised ``artisanSetKey`` stale, and can make two interviews share the same actual artisan set
ON THE SAME INSTRUMENT. Editing such an interview recomputes the key and collides with the sibling
-> HTTP 409. This script heals both: it consolidates genuinely-duplicate interviews into one
canonical row (re-pointing media and responses, like the idempotency migration) and rewrites every
surviving key to match its links.

================================================================================================
THIS SCRIPT WAS MADE INSTRUMENT-BLIND BY THE MIGRATION OF 2026-09-13, AND --execute WOULD HAVE
DELETED A WHOLE WORKSHOP'S FIELDWORK
================================================================================================

``20260913100000_questionnaire_instruments`` dropped the GLOBAL ``@unique`` on
``QuestionnaireInterview.artisanSetKey`` and replaced it with ``@@unique([questionnaireId,
artisanSetKey])`` (prisma/schema.prisma:1411). One artisan set holding one interview
repository-wide was the thing that had to go: the same five artisans sat once for the 2nd Craft
Toolkit Workshop's instrument and sat again for the 3rd, and under the old index the second sitting
FOLDED INTO THE FIRST — the 3rd workshop's answers were written onto the 2nd workshop's interview.
The migration's own header argues that at length. Nothing about the KEY changed;
``artisan_set_key`` (app/api/routes/questionnaire.py:204) still returns sorted, comma-joined
artisan ids. Only the SCOPE of uniqueness moved.

This file predates that migration and still grouped by ``artisanSetKey`` ALONE. After the
migration, two interviews sharing a key on two different instruments are not a duplicate and not a
defect — they are the exact thing the migration was written to make possible. Grouping by the key
alone classified them as a duplicate pair, and every phase downstream acted on that classification:

  * the dry run printed ``consolidate set …: keep <id>, fold [<id>]`` AS THOUGH IT WERE RIGHT,
    which is the worst half — an operator reads a dry run precisely to decide whether to pass
    ``--execute``, and this one recommended the deletion in a confident sentence;
  * ``--execute`` then folded the loser's media onto the winner, moved the responses that did not
    collide, and DELETED THE LOSER. ``QuestionnaireResponse.interview`` is ``onDelete: Cascade``
    (schema.prisma:1445), so every answer that DID collide — i.e. every question both sittings
    answered, which for two sittings of overlapping instruments is most of them — went with the
    row. One instrument's fieldwork, gone, with no backup taken by this script and nothing in the
    output to say a second instrument had ever existed.

The fix is to key the whole reconciliation by ``(questionnaireId, artisanSetKey)`` — the SAME pair
the database now enforces. A script that heals a unique index must group by that index's columns;
grouping by a subset of them is how it starts deleting rows the index was happy to keep. The
tuple is unpacked at the write-back below, because ``artisanSetKey`` is a TEXT column and writing
a Python tuple into it would either be rejected by the driver or stored as ``"('qnr_x', 'a,b')"``,
which no client computes and no index matches.

Pinned by ``tests/test_reconcile_interview_set_keys.py::test_reconcile_groups_by_instrument``.

------------------------------------------------------------------------------------------------
ORDER IS COLLISION-SAFE, AND THE GLOBAL NULL IS STILL THE RIGHT HAMMER
------------------------------------------------------------------------------------------------

Phase 1 NULLs every key, then phase 2 consolidates, then phase 3 writes the correct keys back. The
null exists because the writes in phase 3 are not ordered with respect to each other: healing a
stale key can mean writing a value that a DIFFERENT row is still holding, and without clearing the
board first that write fails on the unique index rather than on anything real.

THAT ARGUMENT SURVIVES THE MOVE TO A COMPOSITE INDEX, and it is restated here rather than assumed
because the index it reasons about is not the one it was written for. Postgres treats NULLs as
distinct in a unique index, and that is true of a composite index too: with ``artisanSetKey`` NULL
the pair ``(questionnaireId, NULL)`` never equals another ``(questionnaireId, NULL)``, so any
number of rows may sit in the cleared state at once. The exemption is on the nullable column, and
that column is still nullable. ``questionnaireId`` is NOT NULL and is deliberately never touched
here — nulling it is not possible and moving an interview between instruments is not this script's
business.

IT IS NOT NARROWED TO THE ROWS BEING HEALED, deliberately. A narrower ``where`` would have to name
exactly the rows phase 3 writes to plus every row that currently holds a value phase 3 is about to
write, which is the same set only when the data is already consistent — precisely the assumption
this script exists because it cannot make. The cost of the blanket clear is real and worth stating:
if the process dies between phase 1 and phase 3, every key in the table is NULL, the composite
index constrains nothing until this script is re-run, and ``create_interview`` will happily create
a second sitting for a set that already has one. That is recoverable — re-running this script
rebuilds every key from the links, which are the source of truth — whereas a phase-3 write failing
halfway through a partial clear is not obviously recoverable by anything. Take the database backup
that ``--execute`` deserves and re-run on failure.

Usage:
    python -m scripts.reconcile_interview_set_keys            # DRY RUN
    python -m scripts.reconcile_interview_set_keys --execute  # apply
"""

import asyncio
import sys
from collections import defaultdict

from app.core.db import connect_db, db, disconnect_db
from app.api.routes.questionnaire import artisan_set_key

#: One reconciliation slot: ``(questionnaireId, artisanSetKey)``, the exact column pair
#: ``@@unique([questionnaireId, artisanSetKey])`` enforces. Named rather than spelled inline so the
#: three places that build, read and unpack it cannot drift into disagreeing about which half is
#: which — the write-back below puts ``slot[1]`` into a TEXT column and ``slot[0]`` into nothing.
Slot = tuple[str, str]


async def media_count(interview_id: str) -> int:
    return await db.mediafile.count(where={"questionnaireInterviewId": interview_id})


def group_by_slot(interviews: list) -> tuple[dict[str, str | None], dict[Slot, list[str]]]:
    """``(correct key per interview id, interview ids per (instrument, set) slot)``.

    SPLIT OUT OF ``main`` SO THE CLASSIFICATION CAN BE TESTED WITHOUT A DATABASE. This is the
    function that decides which rows are duplicates, and the 2026-09-13 defect described in the
    module docstring was entirely in this decision — every destructive step below merely carried it
    out faithfully. A grouping that can only be exercised through ``--execute`` against live data is
    a grouping nobody checks.

    ``iv.questionnaireId`` is read as a plain attribute rather than through ``getattr(…, None)``.
    The column is NOT NULL (schema.prisma:1404) and a missing one must raise here, loudly, at the
    top of the script: a ``None`` default would put every affected interview into one
    ``(None, key)`` slot, which is the instrument-blind grouping this file was just fixed to stop
    doing, arriving back silently through the defensive branch.
    """
    correct_key: dict[str, str | None] = {}
    by_slot: dict[Slot, list[str]] = defaultdict(list)
    for iv in interviews:
        key = artisan_set_key([link.artisanId for link in (iv.artisans or [])])
        correct_key[iv.id] = key
        if key:
            by_slot[(iv.questionnaireId, key)].append(iv.id)
    return correct_key, dict(by_slot)


async def main() -> None:
    execute = "--execute" in sys.argv
    print("MODE:", "EXECUTE" if execute else "DRY RUN (no changes)")
    await connect_db()
    try:
        interviews = await db.questionnaireinterview.find_many(include={"artisans": True})
        correct_key, by_slot = group_by_slot(interviews)

        stale = [iv.id for iv in interviews if correct_key[iv.id] != iv.artisanSetKey]
        dup_groups = {k: v for k, v in by_slot.items() if len(v) > 1}
        print(
            f"interviews={len(interviews)} stale_keys={len(stale)} "
            f"duplicate_set_groups={len(dup_groups)}"
        )

        # Phase 1: clear all keys so later writes can't transiently collide. See the module
        # docstring for why this is still a blanket clear under the composite index.
        if execute:
            await db.questionnaireinterview.update_many(where={}, data={"artisanSetKey": None})

        # Phase 2: consolidate duplicate-set interviews into a canonical survivor. "Duplicate"
        # means SAME INSTRUMENT AND SAME SET — the same set on two instruments is two legitimate
        # sittings and lands in two slots, so it never reaches this branch at all.
        survivors: dict[Slot, str] = {}  # (questionnaireId, key) -> survivor interview id
        for slot, ids in by_slot.items():
            questionnaire_id, key = slot
            if len(ids) == 1:
                survivors[slot] = ids[0]
                continue
            # Pick canonical = most media, tie-break earliest createdAt then id.
            counts = {i: await media_count(i) for i in ids}
            iv_by_id = {iv.id: iv for iv in interviews}
            canonical = sorted(ids, key=lambda i: (-counts[i], iv_by_id[i].createdAt, i))[0]
            survivors[slot] = canonical
            dups = [i for i in ids if i != canonical]
            # THE INSTRUMENT IS PRINTED, not just the set. An operator reading a dry run has to be
            # able to tell "these two rows are the same sitting recorded twice" from "these two
            # rows are two workshops", and the artisan ids alone cannot tell them apart.
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
        #
        # THE SLOT IS UNPACKED HERE AND ONLY ITS SECOND HALF IS WRITTEN. ``artisanSetKey`` is a TEXT
        # column; handing it the whole ``(questionnaireId, key)`` tuple would either be refused by
        # the driver or stored as the repr of a Python tuple, which ``artisan_set_key`` never
        # produces, no client ever computes, and the composite index therefore never matches — so
        # every interview would look permanently stale and every edit would recompute a key that
        # disagreed with the stored one. ``questionnaireId`` itself is NEVER written: which
        # instrument a sitting was taken on is not this script's fact to change.
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
