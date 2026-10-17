"""Routes for AI-drafted interview answers (C28). See ``services/answer_drafts`` for the rules.

``/questionnaire/interviews/{id}/drafts`` — list, draft one section, accept one section, dismiss one
section. All answer 404 while ``AI_ANSWER_DRAFTS_ENABLED`` is off. Drafting, accepting and READING
the drafts need the same standing as editing the interview (``access.record_edit_privilege``): a
pending draft is unfinished work for the people who may accept it, and the transcript names it lists
are the interview's recordings. Anyone else, inside the interview read scope, is told there is
nothing to show; outside it, the interview does not exist.

Accepting is one transaction, and every question it writes must belong to the section it names and
that section to the interview's questionnaire.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value
from app.services import answer_drafts as ad
from app.services.access import record_edit_privilege
from app.services.records import public_encode, require_record, viewable_where

router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])


def _enabled_or_404() -> Any:
    settings = get_settings()
    if not settings.ai_answer_drafts_enabled:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


async def _editable_interview(interview_id: str, user: Any) -> Any:
    interview = await require_record(db.questionnaireinterview, interview_id)
    if not await record_edit_privilege(interview, user, "questionnaire"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only someone who may edit this interview can draft or accept answers.",
        )
    return interview


class DraftIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sectionId: str = Field(min_length=1, max_length=64)
    #: A recording attached to this interview whose transcript is the source...
    mediaId: str | None = Field(default=None, max_length=64)
    #: ...or the transcript text itself.
    transcript: str | None = Field(default=None, max_length=ad.TRANSCRIPT_MAX_CHARS)


class AcceptedAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    questionId: str = Field(min_length=1, max_length=64)
    #: What the person kept: the draft as written, their edit of it, or empty to leave it unanswered.
    answerText: str = Field(default="", max_length=ad.ANSWER_MAX_CHARS * 2)


class AcceptIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sectionId: str = Field(min_length=1, max_length=64)
    answers: list[AcceptedAnswer] = Field(default_factory=list, max_length=200)


class DismissIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sectionId: str = Field(min_length=1, max_length=64)


@router.get("/answer-drafts/status")
async def answer_drafts_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return {"enabled": bool(get_settings().ai_answer_drafts_enabled)}


async def _readable_interview(interview_id: str, user: Any) -> Any:
    """The interview, inside the same read scope as the interview list; 404 outside it."""
    vis = await viewable_where(user)
    if not vis:
        return await require_record(db.questionnaireinterview, interview_id)
    interview = await db.questionnaireinterview.find_first(where={"AND": [{"id": interview_id}, vis]})
    if interview is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return interview


@router.get("/interviews/{interview_id}/drafts")
async def list_drafts(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    _enabled_or_404()
    interview = await _readable_interview(interview_id, current_user)
    if not await record_edit_privilege(interview, current_user, "questionnaire"):
        return {"items": [], "transcripts": [], "mayDraft": False}
    rows = await db.questionnaireanswerdraft.find_many(
        where={"interviewId": interview_id}, order={"createdAt": "asc"}
    )
    transcripts = await db.mediafile.find_many(
        where={"questionnaireInterviewId": interview_id, "transcriptText": {"not": None}}
    )
    return {
        "mayDraft": True,
        "items": public_encode(rows),
        "transcripts": [
            {"id": m.id, "name": m.originalFilename or m.id}
            for m in transcripts
            if (m.transcriptText or "").strip()
        ],
    }


@router.post("/interviews/{interview_id}/drafts")
async def draft_section(
    interview_id: str, payload: DraftIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    settings = _enabled_or_404()
    interview = await _editable_interview(interview_id, current_user)
    section = await db.questionnairesection.find_unique(where={"id": payload.sectionId})
    if section is None or section.questionnaireId != get_value(interview, "questionnaireId"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Section not found")

    transcript = (payload.transcript or "").strip()
    source_media_id = None
    if payload.mediaId:
        media = await db.mediafile.find_unique(where={"id": payload.mediaId})
        if media is None or media.questionnaireInterviewId != interview_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recording not found")
        transcript = (media.transcriptText or "").strip()
        source_media_id = media.id
    if not transcript:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="There's no transcript to draft from yet.",
        )

    questions = await db.questionnairequestion.find_many(
        where={"sectionId": section.id, "isActive": True}, order={"sortOrder": "asc"}
    )
    answered = {
        r.questionId
        for r in await db.questionnaireresponse.find_many(
            where={"interviewId": interview_id, "questionId": {"in": [q.id for q in questions]}}
        )
        if (r.answerText or "").strip()
    }
    open_questions = [q for q in questions if q.id not in answered]
    if not open_questions:
        return {"drafted": 0, "asked": 0, "items": []}

    numbered = [(i, q.prompt) for i, q in enumerate(open_questions, start=1)]
    try:
        answers, provider, model = await ad.draft_answers(
            numbered, transcript, settings, user_id=get_value(current_user, "id")
        )
    except ad.DraftUnavailable as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None

    written = []
    for n, question in enumerate(open_questions, start=1):
        text = answers.get(n)
        if not text:
            continue
        data = {
            "sectionId": section.id,
            "draftText": text,
            "status": ad.STATUS_PENDING,
            "provider": provider,
            "model": model,
            "sourceMediaId": source_media_id,
            "createdById": get_value(current_user, "id"),
            "decidedById": None,
            "decidedAt": None,
        }
        row = await db.questionnaireanswerdraft.upsert(
            where={"interviewId_questionId": {"interviewId": interview_id, "questionId": question.id}},
            data={
                "create": {"interviewId": interview_id, "questionId": question.id, **data},
                "update": data,
            },
        )
        written.append(row)
    return {"drafted": len(written), "asked": len(open_questions), "items": public_encode(written)}


@router.post("/interviews/{interview_id}/drafts/accept")
async def accept_section(
    interview_id: str, payload: AcceptIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Write the section's kept answers, each labelled machine-suggested (edited or not).

    One transaction: a failure part-way leaves no answer written and every draft still pending.
    """
    _enabled_or_404()
    interview = await _editable_interview(interview_id, current_user)
    section = await db.questionnairesection.find_unique(where={"id": payload.sectionId})
    if section is None or section.questionnaireId != get_value(interview, "questionnaireId"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Section not found")
    asked = sorted({a.questionId for a in payload.answers})
    in_section = {
        q.id
        for q in await db.questionnairequestion.find_many(
            where={"id": {"in": asked}, "sectionId": section.id}
        )
    } if asked else set()
    if set(asked) - in_section:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Some of these questions aren't in this section.",
        )
    now = datetime.now(UTC)
    user_id = get_value(current_user, "id")
    async with db.tx() as tx:
        accepted, edited, skipped = await _accept_in(
            tx, interview_id, section.id, payload.answers, user_id, now
        )
    return {"accepted": accepted, "edited": edited, "skipped": skipped}


async def _accept_in(
    tx: Any, interview_id: str, section_id: str, answers: list[AcceptedAnswer], user_id: Any, now: Any
) -> tuple[int, int, int]:
    drafts = {
        d.questionId: d
        for d in await tx.questionnaireanswerdraft.find_many(
            where={
                "interviewId": interview_id,
                "sectionId": section_id,
                "status": ad.STATUS_PENDING,
            }
        )
    }
    accepted = edited = skipped = 0
    for answer in answers:
        draft = drafts.get(answer.questionId)
        if draft is None:
            continue  # not a pending draft of this section: nothing to accept
        text = answer.answerText.strip()
        existing = await tx.questionnaireresponse.find_unique(
            where={"interviewId_questionId": {"interviewId": interview_id, "questionId": answer.questionId}}
        )
        if not text or (existing is not None and (existing.answerText or "").strip()):
            # Left empty by the person, or answered by a person since the draft: the draft goes.
            await tx.questionnaireanswerdraft.update(
                where={"id": draft.id},
                data={"status": ad.STATUS_DISMISSED, "decidedById": user_id, "decidedAt": now},
            )
            skipped += 1
            continue
        unchanged = text == draft.draftText.strip()
        origin = ad.ORIGIN_MACHINE if unchanged else ad.ORIGIN_MACHINE_EDITED
        if existing is None:
            await tx.questionnaireresponse.create(
                data={
                    "interviewId": interview_id,
                    "questionId": answer.questionId,
                    "answerText": text,
                    "answeredById": user_id,
                    "answerOrigin": origin,
                }
            )
        else:
            await tx.questionnaireresponse.update(
                where={"id": existing.id},
                data={"answerText": text, "answeredById": user_id, "answerOrigin": origin},
            )
        await tx.questionnaireanswerdraft.update(
            where={"id": draft.id},
            data={
                "status": ad.STATUS_ACCEPTED if unchanged else ad.STATUS_EDITED,
                "decidedById": user_id,
                "decidedAt": now,
            },
        )
        if unchanged:
            accepted += 1
        else:
            edited += 1
    return accepted, edited, skipped


@router.post("/interviews/{interview_id}/drafts/dismiss")
async def dismiss_section(
    interview_id: str, payload: DismissIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    _enabled_or_404()
    await _editable_interview(interview_id, current_user)
    result = await db.questionnaireanswerdraft.update_many(
        where={"interviewId": interview_id, "sectionId": payload.sectionId, "status": ad.STATUS_PENDING},
        data={
            "status": ad.STATUS_DISMISSED,
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
        },
    )
    return {"dismissed": result if isinstance(result, int) else getattr(result, "count", 0)}
