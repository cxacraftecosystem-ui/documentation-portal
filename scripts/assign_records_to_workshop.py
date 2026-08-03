"""Attach every unassigned record to one workshop, and mirror the link into the join tables.

WHY THIS EXISTS. ``workshopId`` was added to every record type after the repository already had
data in it, so the rows entered before the column existed carry NULL. A NULL ``workshopId`` matches
NO workshop scope — not "all workshops", none — and the workshop scope is the first control on the
search page, the map, the completion matrix, the consolidated questionnaire and the new bulk dataset
API. A record with a NULL there is therefore invisible on every screen that scopes by workshop,
while the record itself looks perfectly healthy when opened directly. That symptom ("this workshop
looks empty") is the most repeated bug class in this repository, and this script is the blunt
instrument for the case where the answer is known: everything currently in the repository was
documented at one workshop.

IT ONLY EVER FILLS A BLANK. A row already pointing at a workshop is left alone and reported, never
re-pointed — including a row pointing at a DIFFERENT workshop. Reassignment is not a backfill: it
would move somebody's field data out of the workshop they filed it under, it cannot be undone from
the information this script has, and a re-run months later (when a second workshop's data has
arrived) would quietly swallow it. ``services/workshop_inference`` takes the same line for the same
reason — where the evidence is ambiguous it stops rather than guessing.

BOTH SPELLINGS OF AN ARTISAN'S LINK ARE WRITTEN. An artisan reaches a workshop through the
``WorkshopArtisan`` join as well as through its own column, and different screens read different
halves: the workshop form's roster picker and the export tree read the join, the record lists read the
column. Writing one and not the other produces an artisan who is on the workshop in one place and
missing from it in another. ``PATCH /artisans`` keeps the pair in step via ``link_workshop_artisan``;
this reuses that very function rather than restating what it does, so the script cannot drift from the
endpoint.

CRAFTS ARE NOT TOUCHED AT ALL — see the note on ``RECORD_TYPES`` for the rule and for the three
regressions that filling their column produced the one time this script did it.

IDEMPOTENT. Every write is conditional on the row still being blank and every join insert is a
find-then-create, so a second run reports zero changes and performs none.

Usage (from ``backend/``):
    python -m scripts.assign_records_to_workshop                      # DRY RUN — prints the plan
    python -m scripts.assign_records_to_workshop --execute            # apply it
    python -m scripts.assign_records_to_workshop --workshop-id <id>   # target a specific workshop
    python -m scripts.assign_records_to_workshop --workshop "partial title"
"""

import argparse
import asyncio
import sys
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services.workshop_access import link_workshop_artisan

# The default target: the workshop every record currently in the repository was documented at.
DEFAULT_WORKSHOP_TITLE = "Shristi O Anusandhan 2nd Toolkit Workshop"

# Every record type that carries a workshopId column, in the order they are reported. `media` is
# included deliberately: a photograph is scoped by workshop exactly like the record it hangs off,
# and MediaFile.workshopId is the column the map and the data browser read.
#
# CRAFT IS DELIBERATELY ABSENT, and this is the one entry it is tempting to add. The rule and its
# reason are stated at ``app/services/workshop_inference.BUCKETS``: "A craft is taxonomy — 'Dabu hand
# block printing' is not something that happened at a workshop — and while the column exists for the
# roster join, no screen narrows crafts by workshop. Assigning them would be inventing a fact to fill
# a column."
#
# This script DID fill it once, for the 9 crafts on the live repository, and the invented fact turned
# out to have three consumers — which is exactly why the rule exists:
#
#   1. ``media.inherit_parent_workshop`` stamps an upload with its PARENT's workshop, and a craft is a
#      valid media parent. A researcher at workshop B attaching a photo to the shared craft "Cane and
#      Bamboo" had it filed under workshop A, where B's scope could never see it.
#   2. ``workshop_inference`` reads ``craftId`` on the PARENT rung, which outranks the date WINDOW
#      rung. Unassigned media hanging off a shared craft was then mapped confidently and
#      unambiguously to workshop A, so the ladder's ambiguity brake never engaged.
#   3. On the web craft form, a stored workshop makes ``confirmSubmission`` fire: editing any of those
#      crafts raised a blocking dialog saying the save "needs an admin's approval" — a claim the
#      backend does not honour, because ``update_craft`` only gates a workshopId that CHANGED and
#      Craft has no status column to pin.
#
# THE RELATIONSHIP DOES NOT NEED THE COLUMN. A craft is linked to a workshop through ``WorkshopCraft``,
# and every craft-scoping query in the app reads that join — ``GET /crafts?workshopId=`` ORs the two
# readings, and so does the shared ``record_filters.craft_workshop_clause``. ``mirror_join_tables``
# below still keeps that join complete, which is the half that is actually read.
RECORD_TYPES: tuple[tuple[str, str], ...] = (
    ("artisans", "artisan"),
    ("products", "productdocumentation"),
    ("tools", "tooldocumentation"),
    ("processes", "process"),
    ("interviews", "questionnaireinterview"),
    ("media", "mediafile"),
)


async def resolve_workshop(workshop_id: str | None, title: str) -> Any:
    """The workshop to attach to, by id or by a case-insensitive title fragment.

    A title that matches more than one workshop is refused rather than resolved to the first hit:
    picking one silently is how a bulk write lands on the wrong workshop, and the whole point of
    this script is that it is aimed at exactly one.
    """
    if workshop_id:
        found = await db.workshop.find_unique(where={"id": workshop_id})
        if found is None:
            raise SystemExit(f"No workshop with id {workshop_id!r}")
        return found

    matches = await db.workshop.find_many(where={"title": {"contains": title, "mode": "insensitive"}})
    if not matches:
        available = await db.workshop.find_many()
        listing = "\n".join(f"  {w.id}  {w.title!r}" for w in available) or "  (none)"
        raise SystemExit(f"No workshop whose title contains {title!r}. Workshops:\n{listing}")
    if len(matches) > 1:
        listing = "\n".join(f"  {w.id}  {w.title!r}" for w in matches)
        raise SystemExit(
            f"{len(matches)} workshops match {title!r} — pass --workshop-id to choose:\n{listing}"
        )
    return matches[0]


async def plan_and_apply(workshop: Any, execute: bool) -> dict[str, int]:
    """Report every record type's state and, when executing, fill the blanks."""
    changed: dict[str, int] = {}
    print(f"{'record type':14} {'total':>7} {'blank':>7} {'already':>8} {'elsewhere':>10}")
    print("-" * 50)

    for label, delegate_name in RECORD_TYPES:
        model = getattr(db, delegate_name)
        total = await model.count()
        blank = await model.count(where={"workshopId": None})
        already = await model.count(where={"workshopId": workshop.id})
        # Pointing at some OTHER workshop. Counted and reported rather than silently ignored: this
        # is the number that says whether "attach everything" is still the right description of what
        # the script is about to do.
        elsewhere = total - blank - already

        print(f"{label:14} {total:7} {blank:7} {already:8} {elsewhere:10}")
        changed[label] = blank

        if execute and blank:
            await model.update_many(where={"workshopId": None}, data={"workshopId": workshop.id})

    return changed


async def mirror_join_tables(workshop: Any, execute: bool) -> dict[str, int]:
    """Ensure every ARTISAN this script attached to the workshop also has its ``WorkshopArtisan`` row.

    Artisans reach a workshop by column AND by roster, and different screens read different halves —
    the record lists read the column, the workshop form's roster picker and the export tree read the
    join. Filling one without the other produces an artisan who is on the workshop in one place and
    missing from it in another. ``link_workshop_artisan`` is the endpoints' own helper (find-then-
    create, idempotent), reused rather than restated so this cannot drift from ``PATCH /artisans``.

    CRAFTS ARE NOT MIRRORED HERE, and that is a consequence of not backfilling their column: with no
    column written there is nothing for a join row to be derived FROM. The craft-to-workshop link is
    the ``WorkshopCraft`` row itself — written by ``POST``/``PATCH /crafts`` and by the workshop form's
    "Crafts covered" picker, and read by every craft-scoping query — so it is the authority, not a
    mirror of something else. Deriving it from a column this script had just invented was how the
    invented fact got in (see RECORD_TYPES).

    THE DRY RUN COUNTS THE ROWS ``--execute`` WOULD FILL, not the rows already filled. It runs after
    ``plan_and_apply``, which writes nothing in a dry run — so selecting only
    ``workshopId == workshop.id`` would report the join work as 0 while the real run inserted a row per
    artisan it had just backfilled. The plan an operator approves has to be the plan that runs, and the
    dry run is this script's only safety mechanism.
    """
    added = {"workshopartisan": 0}
    # In a dry run, include the rows still blank: those are the ones --execute is about to claim.
    scope: dict[str, Any] = (
        {"workshopId": workshop.id}
        if execute
        else {"OR": [{"workshopId": workshop.id}, {"workshopId": None}]}
    )

    artisans = await db.artisan.find_many(where=scope)
    for artisan in artisans:
        existing = await db.workshopartisan.find_unique(
            where={"workshopId_artisanId": {"workshopId": workshop.id, "artisanId": artisan.id}}
        )
        if existing is None:
            added["workshopartisan"] += 1
            if execute:
                await link_workshop_artisan(workshop.id, artisan.id)

    return added


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="apply the changes (default: dry run)")
    parser.add_argument("--workshop-id", default=None, help="target workshop id")
    parser.add_argument("--workshop", default=DEFAULT_WORKSHOP_TITLE, help="target workshop title fragment")
    args = parser.parse_args()

    await connect_db()
    try:
        workshop = await resolve_workshop(args.workshop_id, args.workshop)
        mode = "EXECUTE" if args.execute else "DRY RUN"
        print(f"\n[{mode}] target workshop: {workshop.title!r}\n           id: {workshop.id}\n")

        changed = await plan_and_apply(workshop, args.execute)
        print()
        joins = await mirror_join_tables(workshop, args.execute)

        total_rows = sum(changed.values())
        total_joins = sum(joins.values())
        print(f"join rows to add: WorkshopArtisan {joins['workshopartisan']}")
        print()
        if args.execute:
            print(f"DONE — {total_rows} record(s) attached, {total_joins} join row(s) added.")
        else:
            print(f"PLAN — {total_rows} record(s) would be attached, {total_joins} join row(s) added.")
            print("Re-run with --execute to apply.")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except SystemExit as exc:
        print(f"\n{exc}", file=sys.stderr)
        raise
