from typing import Any

from fastapi import HTTPException, status

from app.core.db import db

W2_ID = "qnr_2nd_craft_toolkit_workshop"
W2_TITLE = "2nd Craft Toolkit Workshop"

W3_ID = "qnr_3rd_craft_toolkit_workshop"
W3_TITLE = "3rd Craft Toolkit Workshop"

W4_ID = "qnr_4th_craft_toolkit_workshop"
W4_TITLE = "4th Craft Toolkit Workshop"

_NO_INSTRUMENT = (
    "The interview questionnaire hasn't been set up. Ask an administrator."
)


async def default_questionnaire_id() -> str:
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
    if explicit:
        await require_questionnaire(explicit)
        return explicit
    if workshop_id:
        workshop = await db.workshop.find_unique(where={"id": workshop_id})
        if workshop is not None and getattr(workshop, "questionnaireId", None):
            return workshop.questionnaireId
    return await default_questionnaire_id()
