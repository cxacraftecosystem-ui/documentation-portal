"""Merge duplicate artisan records into a canonical one, re-pointing every reference.

One-off data fix: two people were each documented twice (same phone, slightly different
name/craft). We keep the richer record as canonical, move all child rows onto it, then delete
the duplicate. Composite-key join rows (workshops, tools, interviews) are de-duplicated so the
move never violates a primary/unique key.

Usage:
    python -m scripts.merge_artisans            # DRY RUN — prints the plan, changes nothing
    python -m scripts.merge_artisans --execute  # apply the merge
"""

import asyncio
import sys

from app.core.db import connect_db, db, disconnect_db

# (canonical_id, duplicate_id, canonical_name_override_or_None)
MERGES = [
    # Ankit Shah — keep the rudraprayag/Ringal record (1 product, 3 tools, 2 media, 3 interviews).
    ("cmqj3o0b40064kb597dxk6edq", "cmqil3s4m000mpndnbu50bjwl", "Ankit Shah"),
    # Madan Mohan — keep the record with 10 tools (Choudhry, Jaipur Sanganeri).
    ("cmqj09jqo001pkb590htnwkjd", "cmqj1jxda002hkb59z9ta6y8r", None),
]


async def merge_one(canonical_id: str, dup_id: str, name_override: str | None, execute: bool) -> None:
    canonical = await db.artisan.find_unique(where={"id": canonical_id})
    dup = await db.artisan.find_unique(where={"id": dup_id})
    if canonical is None or dup is None:
        print(f"  SKIP: canonical present={canonical is not None} dup present={dup is not None} "
              f"({canonical_id} / {dup_id}) — already merged or missing")
        return
    canonical_name = name_override or canonical.name
    print(f"\nMerging {dup.name!r} ({dup_id}) -> {canonical_name!r} ({canonical_id})")

    # 1) Direct-FK docs: re-point + refresh the denormalized artisanName.
    for label, model in (("product", db.productdocumentation), ("tool", db.tooldocumentation)):
        rows = await model.find_many(where={"artisanId": dup_id})
        print(f"  {label}documentation: re-point {len(rows)} row(s)")
        if execute and rows:
            await model.update_many(
                where={"artisanId": dup_id},
                data={"artisanId": canonical_id, "artisanName": canonical_name},
            )

    # 2) MediaFile: simple re-point.
    media = await db.mediafile.find_many(where={"artisanId": dup_id})
    print(f"  mediafile: re-point {len(media)} row(s)")
    if execute and media:
        await db.mediafile.update_many(where={"artisanId": dup_id}, data={"artisanId": canonical_id})

    # 3) ToolArtisan join (surrogate id, unique toolId+artisanId): move, dropping collisions.
    ta = await db.toolartisan.find_many(where={"artisanId": dup_id})
    moved = collided = 0
    for row in ta:
        clash = await db.toolartisan.find_first(where={"toolId": row.toolId, "artisanId": canonical_id})
        if clash:
            collided += 1
            if execute:
                await db.toolartisan.delete(where={"id": row.id})
        else:
            moved += 1
            if execute:
                await db.toolartisan.update(where={"id": row.id}, data={"artisanId": canonical_id})
    print(f"  toolartisan: move {moved}, drop-as-duplicate {collided}")

    # 4) WorkshopArtisan join (composite PK): delete the dup row, create the canonical link if absent.
    wa = await db.workshopartisan.find_many(where={"artisanId": dup_id})
    moved = collided = 0
    for row in wa:
        clash = await db.workshopartisan.find_first(
            where={"workshopId": row.workshopId, "artisanId": canonical_id}
        )
        collided += 1 if clash else 0
        moved += 0 if clash else 1
        if execute:
            await db.workshopartisan.delete(
                where={"workshopId_artisanId": {"workshopId": row.workshopId, "artisanId": dup_id}}
            )
            if not clash:
                await db.workshopartisan.create(
                    data={"workshopId": row.workshopId, "artisanId": canonical_id}
                )
    print(f"  workshopartisan: move {moved}, drop-as-duplicate {collided}")

    # 5) QuestionnaireInterviewArtisan join (composite PK): delete dup row, create canonical if absent.
    qa = await db.questionnaireinterviewartisan.find_many(where={"artisanId": dup_id})
    moved = collided = 0
    for row in qa:
        clash = await db.questionnaireinterviewartisan.find_first(
            where={"interviewId": row.interviewId, "artisanId": canonical_id}
        )
        collided += 1 if clash else 0
        moved += 0 if clash else 1
        if execute:
            await db.questionnaireinterviewartisan.delete(
                where={"interviewId_artisanId": {"interviewId": row.interviewId, "artisanId": dup_id}}
            )
            if not clash:
                await db.questionnaireinterviewartisan.create(
                    data={"interviewId": row.interviewId, "artisanId": canonical_id}
                )
    print(f"  questionnaireinterviewartisan: move {moved}, drop-as-duplicate {collided}")

    # 6) Name normalisation on canonical, then delete the now-orphaned duplicate.
    if name_override and canonical.name != name_override:
        print(f"  rename canonical {canonical.name!r} -> {name_override!r}")
        if execute:
            await db.artisan.update(where={"id": canonical_id}, data={"name": name_override})
    print(f"  delete duplicate artisan {dup_id}")
    if execute:
        await db.artisan.delete(where={"id": dup_id})


async def main() -> None:
    execute = "--execute" in sys.argv
    print("MODE:", "EXECUTE" if execute else "DRY RUN (no changes)")
    await connect_db()
    try:
        for canonical_id, dup_id, name_override in MERGES:
            await merge_one(canonical_id, dup_id, name_override, execute)
    finally:
        await disconnect_db()
    print("\nDone." if execute else "\nDry run complete — re-run with --execute to apply.")


if __name__ == "__main__":
    asyncio.run(main())
