"""WHICH INSTRUMENT — the identities, and the three ways a request arrives at one.

Every read of the questionnaire now needs an instrument, and there are exactly three sources for
one, in this order:

  1. the caller said so (``?questionnaireId=``, or the field on an interview create body);
  2. the WORKSHOP said so (``Workshop.questionnaireId``, chosen once by an admin);
  3. nobody said, so the default (``Questionnaire.isDefault``).

Step 3 is not a nicety. An Android build that shipped before 2026-09-13 POSTs
/questionnaire/interviews with no ``questionnaireId`` at all and will keep doing so for as long as
handsets go unupdated; refusing it would take the app away from the researchers who are furthest
from a good network. It lands on the default, which is what it has always landed on.

THE ORDERING HAZARD THIS MODULE CANNOT FIX, stated here because this is where the fallback lives:
an old handset resolves to the DEFAULT AT THE TIME THE SERVER SEES THE REQUEST, not at the time the
researcher sat down. Flipping the default to the 3rd instrument before those handsets have updated
AND before their offline outboxes have drained files 2nd-workshop-shaped fieldwork onto the 3rd
instrument's questions, with the answers landing on questions that exist, so nothing validates and
nothing raises. See README.md's questionnaire section and RISK R5 in the workstream spec.
"""

from typing import Any

from fastapi import HTTPException, status

from app.core.db import db

#: The instrument that was already here on 2026-09-13, named by the migration of that date. The id
#: is a LITERAL and not a cuid so the migration's backfill needed no subquery; see that file.
W2_ID = "qnr_2nd_craft_toolkit_workshop"
W2_TITLE = "2nd Craft Toolkit Workshop"

#: The 3rd Craft Toolkit Workshop's instrument, created by scripts/seed_questionnaire_w3.py.
W3_ID = "qnr_3rd_craft_toolkit_workshop"
W3_TITLE = "3rd Craft Toolkit Workshop"

_NO_INSTRUMENT = (
    "No questionnaire instrument exists yet. Run `python scripts/seed_questionnaire.py` (and, for "
    "the 3rd workshop, `python scripts/seed_questionnaire_w3.py`) from the backend directory."
)


async def default_questionnaire_id() -> str:
    """The instrument an unqualified request belongs to.

    ``isDefault`` first — it is a decision somebody made and a partial unique index guarantees at
    most one row carries it. Falling back to the lowest active ``sortOrder`` rather than raising when
    nobody has set it keeps a database whose default was cleared by hand serving rather than 500ing
    on every questionnaire read. It is a FALLBACK, not a second default: nothing writes it down and
    nothing reports it as chosen.
    """
    row = await db.questionnaire.find_first(where={"isDefault": True})
    if row is None:
        row = await db.questionnaire.find_first(
            where={"isActive": True}, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]
        )
    if row is None:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=_NO_INSTRUMENT)
    return row.id


async def require_questionnaire(questionnaire_id: str) -> Any:
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return row


async def resolve_questionnaire_id(
    explicit: str | None = None, workshop_id: str | None = None
) -> str:
    """The instrument for a request, by the three-step rule in this module's docstring.

    The explicit id is VALIDATED, not trusted: it arrives from a query string and a typo would
    otherwise produce an empty questionnaire rather than a 404.
    """
    if explicit:
        await require_questionnaire(explicit)
        return explicit
    if workshop_id:
        workshop = await db.workshop.find_unique(where={"id": workshop_id})
        if workshop is not None and getattr(workshop, "questionnaireId", None):
            return workshop.questionnaireId
    return await default_questionnaire_id()
