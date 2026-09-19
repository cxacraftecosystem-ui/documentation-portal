"""Bind an instrument to a workshop — the deliberate half of seeding one.

`seed_questionnaire_w3.py` creates the 3rd Craft Toolkit Workshop's instrument and says, in as many
words, that it is "NOT made the default and NOT bound to any workshop. Do that deliberately." This
is that deliberate step, as a script rather than a one-off curl, so the decision is reviewable and
repeatable.

WHY BINDING AND NOT THE DEFAULT. `services/questionnaire_instruments.resolve_questionnaire_id` has
three steps: the caller's explicit id, then ``Workshop.questionnaireId``, then
``Questionnaire.isDefault``. Binding writes step TWO, which is scoped to one event; flipping the
default rewrites step THREE, which catches every client that names no instrument at all. That
includes Android builds predating 2026-09-13 and any offline payload queued by one, which replays
against whatever the default is AT REPLAY TIME — filing 2nd-workshop fieldwork onto the 3rd
instrument's questions. The section codes collide completely, so nothing would raise. That is the
hazard `questionnaire_instruments.py` records at the top of the module and RISK R5 in the workstream
spec; binding has none of it, because a request naming this workshop is a request that has already
said which event it belongs to.

SAFE TO RE-RUN. It reads the current binding first and writes nothing when it already matches, so a
second run reports "already bound" rather than issuing a redundant write.

DRY BY DEFAULT. Nothing is written without ``--apply``.

    cd backend
    .\\.venv\\Scripts\\Activate.ps1
    python scripts/bind_workshop_questionnaire.py --list
    python scripts/bind_workshop_questionnaire.py --workshop "3rd" --instrument w3
    python scripts/bind_workshop_questionnaire.py --workshop "3rd" --instrument w3 --apply
"""

import argparse
import asyncio
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services.questionnaire_instruments import W2_ID, W3_ID

#: Task statuses that still have somebody working to them. Mirrors `_LIVE_TASK_STATUSES` in
#: `app/api/routes/workshops.py`; a task that is done or cancelled cannot be made unachievable.
LIVE_TASK_STATUSES = ("PENDING", "IN_PROGRESS", "BLOCKED")

ALIASES = {"w2": W2_ID, "w3": W3_ID}


def value(row: Any, field: str) -> Any:
    return getattr(row, field, None) if not isinstance(row, dict) else row.get(field)


async def show_instruments() -> None:
    rows = await db.questionnaire.find_many()
    print("\nINSTRUMENTS")
    if not rows:
        print("  (none — run scripts/seed_questionnaire.py and scripts/seed_questionnaire_w3.py)")
    for row in sorted(rows, key=lambda r: value(r, "sortOrder") or 0):
        flags = []
        if value(row, "isDefault"):
            flags.append("DEFAULT")
        if not value(row, "isActive"):
            flags.append("retired")
        suffix = f"  [{', '.join(flags)}]" if flags else ""
        print(f"  {value(row, 'id'):<40} {value(row, 'title')}{suffix}")


async def show_workshops() -> None:
    rows = await db.workshop.find_many()
    print("\nWORKSHOPS (and the instrument each resolves to)")
    for row in sorted(rows, key=lambda r: str(value(r, "startDate") or value(r, "date") or "")):
        bound = value(row, "questionnaireId")
        where = bound or "— none bound, falls back to the default —"
        print(f"  {value(row, 'id'):<40} {str(value(row, 'title'))[:44]:<46} {where}")


async def blocked_tasks(workshop_id: str, outgoing: str) -> list[Any]:
    """Open tasks at this workshop scoped to sections of the instrument being replaced.

    The same check `set_workshop_questionnaire` makes, replicated rather than imported because that
    helper is route-private. Rebinding past these would leave their assignees holding sections that
    are on no form they can open, with a progress denominator nobody can reach.
    """
    sections = await db.questionnairesection.find_many(where={"questionnaireId": outgoing})
    section_ids = {value(s, "id") for s in sections}
    if not section_ids:
        return []
    tasks = await db.assignedtask.find_many(
        where={"workshopId": workshop_id, "status": {"in": list(LIVE_TASK_STATUSES)}}
    )
    return [t for t in tasks if section_ids & set(value(t, "sectionIds") or [])]


async def run(args: argparse.Namespace) -> int:
    await connect_db()
    try:
        await show_instruments()
        await show_workshops()
        if args.list:
            return 0

        target = ALIASES.get(args.instrument.lower(), args.instrument)
        instrument = await db.questionnaire.find_unique(where={"id": target})
        if instrument is None:
            print(f"\nFAIL: no instrument with id {target!r}. See the list above.")
            return 2
        if not value(instrument, "isActive"):
            # The endpoint refuses this with a 422; refusing here too keeps the script and the API
            # answering the same question the same way.
            print(f"\nFAIL: “{value(instrument, 'title')}” is retired and cannot be bound.")
            return 2

        workshops = await db.workshop.find_many()
        needle = args.workshop.lower()
        matches = [
            w
            for w in workshops
            if needle == str(value(w, "id")).lower() or needle in str(value(w, "title") or "").lower()
        ]
        if not matches:
            print(f"\nFAIL: no workshop whose id or title matches {args.workshop!r}.")
            return 2
        if len(matches) > 1 and not args.all:
            print(f"\nFAIL: {args.workshop!r} matches {len(matches)} workshops:")
            for w in matches:
                print(f"    {value(w, 'id')}  {value(w, 'title')}")
            print("  Name one by id, or pass --all to bind every match.")
            return 2

        print(f"\nPLAN — bind “{value(instrument, 'title')}” ({target})")
        writes: list[Any] = []
        for w in matches:
            wid, title = value(w, "id"), value(w, "title")
            current = value(w, "questionnaireId")
            if current == target:
                print(f"  = {title}: already bound. Nothing to write.")
                continue
            if current:
                held = await blocked_tasks(wid, current)
                if held and not args.reassign_tasks:
                    print(
                        f"  ! {title}: {len(held)} open task(s) are scoped to sections of the "
                        f"current instrument ({current}). Re-scope them, or pass --reassign-tasks "
                        f"to clear their sectionIds. REFUSING."
                    )
                    continue
                print(f"  ~ {title}: {current} -> {target}")
            else:
                print(f"  + {title}: (none) -> {target}")
            writes.append(w)

        if not writes:
            print("\nNothing to do.")
            return 0
        if not args.apply:
            print(f"\nDRY RUN — {len(writes)} workshop(s) would change. Re-run with --apply to write.")
            return 0

        for w in writes:
            await db.workshop.update(
                where={"id": value(w, "id")}, data={"questionnaireId": target}
            )
            print(f"  written: {value(w, 'title')} -> {target}")
        print(f"\nDone. {len(writes)} workshop(s) bound.")
        return 0
    finally:
        await disconnect_db()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="show instruments and workshops, write nothing")
    parser.add_argument("--workshop", default="", help="workshop id, or a substring of its title")
    parser.add_argument("--instrument", default="w3", help="instrument id, or the alias w2 / w3")
    parser.add_argument("--all", action="store_true", help="bind every workshop matching --workshop")
    parser.add_argument(
        "--reassign-tasks",
        action="store_true",
        help="proceed even when open tasks are scoped to the outgoing instrument (does NOT clear them; "
        "use the API for that)",
    )
    parser.add_argument("--apply", action="store_true", help="actually write (default is a dry run)")
    args = parser.parse_args()
    if not args.list and not args.workshop:
        parser.error("name a workshop with --workshop, or pass --list")
    raise SystemExit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
