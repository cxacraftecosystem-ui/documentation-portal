import csv
import re
from datetime import UTC, datetime
from io import StringIO
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from prisma.errors import UniqueViolationError

# csv_response is SHARED with the export router rather than restated: both hand a researcher a .csv
# over the same Content-Disposition and charset, and a download that saves differently depending on
# which endpoint produced it is exactly the drift that helper exists to prevent. (export.py imports
# from data_browser.py for the same reason; nothing in that chain imports this module back.)
from app.api.routes.export import csv_response
from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_create_records,
    assert_can_delete,
    get_current_user,
    get_value,
    is_admin,
    is_empty_value,
    require_admin,
    require_questionnaire_manager,
)
from app.services.access import guard_record_edit, owner_download_scope
from app.schemas.questionnaire import (
    CompletionCellUpdate,
    QuestionnaireInterviewCreate,
    QuestionnaireInterviewUpdate,
    QuestionnaireQuestionCreate,
    QuestionnaireQuestionReorder,
    QuestionnaireQuestionUpdate,
    QuestionnaireSectionCreate,
    QuestionnaireSectionReorder,
    QuestionnaireSectionUpdate,
)
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import (
    artisan_workshop_clause,
    resolve_workshop_ids,
    workshop_clause,
)
from app.services.questionnaire_consolidation import (
    CSV_COLUMNS,
    consolidate_for_artisan,
    consolidated_rows,
)
from app.services.records import (
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    jsonify_metadata,
    merge_field_provenance,
    public_encode,
    require_record,
    resubmit_status,
    viewable_where,
)
from app.services.workshop_access import (
    WorkshopSubmissionCheck,
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)
from app.services.workshop_inference import count_unassigned_interviews

router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])


def _norm_code(value: str | None) -> str:
    """Uppercase, alphanumerics-only form of a section code — mirrors the app's filename token() so a
    clip filename's leading section token resolves back to its section."""
    return "".join(ch for ch in (value or "") if ch.isalnum()).upper()


_SECTION_IN_TITLE_RE = re.compile(r"sections?\s+([a-zA-Z][a-zA-Z\s,&/\-]{0,24})", re.IGNORECASE)
_LETTER_RUN_RE = re.compile(r"\b([a-zA-Z](?:\s*,\s*[a-zA-Z]){2,})\b")
_REAL_WORD_RE = re.compile(r"\b(?!and\b)[a-zA-Z]{2,}\b", re.IGNORECASE)


def section_codes_from_title(title: str | None, valid_codes: set[str]) -> set[str]:
    """Best-effort section codes named in an interview's TITLE — the only completion signal for
    interviews recorded BEFORE the clip-filename nomenclature existed. Researchers titled those by the
    sections covered, e.g. "Section K & L", "section F", or "Rudraprayag G,H,I,J,Q". Only tokens that
    exactly match a real section code count, so unrelated words are ignored; admins can still override.
    """
    if not title:
        return set()
    found: set[str] = set()
    # Letters right after the word "section"/"sections", through a short run of separators, stopping at
    # the first real word (2+ letters that isn't "and") so we don't sweep up the rest of the title.
    for match in _SECTION_IN_TITLE_RE.finditer(title):
        run = _REAL_WORD_RE.split(match.group(1))[0]
        for token in re.split(r"[^a-zA-Z]+", run):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    # Bare comma-separated single-letter runs, e.g. "G,H,I,J,Q".
    for match in _LETTER_RUN_RE.finditer(title):
        for token in re.split(r"[^a-zA-Z]+", match.group(1)):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    return found


# What an interview carries on the wire — the widest payload in the app: six relations, two of them
# nested. Read as a Prisma ``include`` that was TWELVE sequential statements for a page of four
# interviews, and on this deployment every one of them is a cross-region round trip. They load in
# one parallel wave instead (see services/records.py), on the write paths too: those hydrate the row
# they just saved, so this is the single description of an interview's relations.
RELATIONS = (
    Relation("createdBy", "user", "createdById"),
    Relation("location", "location", "locationId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation(
        "artisans",
        "questionnaireinterviewartisan",
        "interviewId",
        many=True,
        include={"artisan": {"include": {"craft": True}}},
    ),
    Relation(
        "responses",
        "questionnaireresponse",
        "interviewId",
        many=True,
        include={"question": True, "answeredBy": True},
    ),
    Relation("media", "mediafile", "questionnaireInterviewId", many=True),
)


_DUPLICATE_SET_DETAIL = (
    "An interview already exists for this exact set of artisans. There is a single shared entry per "
    "artisan set — open it to add or view answers instead of creating another."
)


def artisan_set_key(artisan_ids: list[str]) -> str | None:
    """Deterministic key for the exact set of artisans an interview covers.

    Sorted, de-duplicated, comma-joined artisan ids — identical to the SQL backfill in migration
    ``20260622120000`` (``string_agg(..., ',' ORDER BY ...)``). This is what makes one-interview-per-
    artisan-set enforceable: every client computing the same set lands on the same key. Returns
    ``None`` for an empty set (artisan-less interviews are not deduped). A subset yields a different
    key, i.e. a separate entry.
    """
    unique = sorted({aid for aid in artisan_ids if aid})
    return ",".join(unique) if unique else None


def default_interview_date(data: dict[str, Any]) -> dict[str, Any]:
    """Derive ``interviewDate`` server-side when the client did not send one. Mutates ``data``.

    ``interviewDate`` is no longer a form field. Asking a researcher to type the date of the interview
    they are recording right now was pure friction, and it was answered wrongly often enough (last
    week's date left in the form, a typo'd year) that the value could not be trusted for the date
    filters and exports built on it. ``recordedAt`` is the same fact captured for free: the client
    stamps it when the interview is actually recorded.

    The column STAYS — it is provenance for every interview created while the field existed, and old
    clients may still send it, in which case the value they sent is honoured untouched. It is only
    ever derived when absent, so a real answer is never overwritten. ``recordedAt`` itself may also be
    absent (it is DB-defaulted to ``now()``), so "now" is the last resort — the same instant the
    database would have stamped.
    """
    if data.get("interviewDate") is None:
        data["interviewDate"] = data.get("recordedAt") or datetime.now(UTC)
    return data


async def next_section_sort_order() -> int:
    sections = await db.questionnairesection.find_many(order={"sortOrder": "desc"}, take=1)
    return (sections[0].sortOrder if sections else 0) + 1


async def next_question_sort_order(section_id: str) -> int:
    questions = await db.questionnairequestion.find_many(
        where={"sectionId": section_id},
        order={"sortOrder": "desc"},
        take=1,
    )
    return (questions[0].sortOrder if questions else 0) + 1


async def require_section(section_id: str) -> Any:
    return await require_record(db.questionnairesection, section_id)


def section_question_data(section: Any) -> dict[str, str]:
    return {"sectionId": section.id, "sectionCode": section.code, "sectionTitle": section.title}


async def section_payloads(active_only: bool = True) -> list[dict[str, Any]]:
    section_where = {"isActive": True} if active_only else {}
    question_where: dict[str, Any] = {"isActive": True} if active_only else {}
    sections = await db.questionnairesection.find_many(where=section_where, order={"sortOrder": "asc"})
    questions = await db.questionnairequestion.find_many(
        where=question_where,
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    questions_by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId:
            questions_by_section.setdefault(question.sectionId, []).append(question)
    payload: list[dict[str, Any]] = []
    for section in sections:
        encoded = public_encode(section)
        encoded["questions"] = public_encode(questions_by_section.get(section.id, []))
        payload.append(encoded)
    return payload


async def replace_interview_artisans(interview_id: str, artisan_ids: list[str]) -> None:
    unique_ids = sorted({aid for aid in artisan_ids if aid})
    set_key = artisan_set_key(unique_ids)

    # Short-circuit when the artisan set is unchanged. A plain edit (new responses/media, title, notes)
    # re-sends the same artisanIds; rewriting the unique set key + links on every such save is both
    # wasteful and the ONLY thing that could trip the one-interview-per-set guard. Skipping it means an
    # ordinary edit can never 409. We still heal a drifted cached key (its own value, so no conflict).
    current = await db.questionnaireinterviewartisan.find_many(where={"interviewId": interview_id})
    if sorted({link.artisanId for link in current}) == unique_ids:
        existing = await db.questionnaireinterview.find_unique(where={"id": interview_id})
        if existing is not None and existing.artisanSetKey != set_key:
            await db.questionnaireinterview.update(
                where={"id": interview_id}, data={"artisanSetKey": set_key}
            )
        return

    # The set is genuinely changing. Validate every artisan up front so a bad id can't leave a
    # half-rewritten link set, then keep the unique set key in lock-step with the links. ONE query
    # answers "do all of these exist" — asking per artisan cost a cross-region round trip each, and
    # a group interview names a dozen.
    if unique_ids:
        found = await db.artisan.find_many(where={"id": {"in": unique_ids}})
        if len(found) != len(unique_ids):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    try:
        await db.questionnaireinterview.update(
            where={"id": interview_id}, data={"artisanSetKey": set_key}
        )
    except UniqueViolationError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=_DUPLICATE_SET_DETAIL) from exc
    await db.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    if unique_ids:
        await db.questionnaireinterviewartisan.create_many(
            data=[{"interviewId": interview_id, "artisanId": aid} for aid in unique_ids]
        )


async def upsert_responses(interview_id: str, responses: list[Any], current_user: Any) -> None:
    """Save a batch of answers: validate once, read once, insert once, and update only what changed.

    This was THREE cross-region round trips PER ANSWER — a question existence check, a read of the
    stored answer, and the upsert. A questionnaire section carries dozens of questions and the app
    submits the whole section, so a single save cost a hundred sequential round trips and minutes of
    wall time. All of the validation and all of the reading now happen in one statement each, every
    genuinely new answer goes in with one insert, and the only per-row writes left are the answers
    whose text or notes ACTUALLY differ from what is stored.

    Skipping the unchanged rows is not merely an optimisation: it also stops a save that touched one
    answer from re-stamping ``answeredById`` on every other answer in the section, which would have
    taken authorship of work the saver never edited. The permission rule directly below is unchanged
    — only the original contributor or an admin may alter an answer that already has text.
    """
    if not responses:
        return
    question_ids = sorted({r.questionId for r in responses if r.questionId})
    existing_rows, questions = await gather_reads(
        db.questionnaireresponse.find_many(
            where={"interviewId": interview_id, "questionId": {"in": question_ids}}
        ),
        db.questionnairequestion.find_many(where={"id": {"in": question_ids}}),
    )
    known = {q.id for q in questions}
    if len(known) != len(question_ids):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    by_question = {row.questionId: row for row in existing_rows}

    to_create: list[dict[str, Any]] = []
    to_update: list[tuple[str, dict[str, Any]]] = []
    for response in responses:
        existing = by_question.get(response.questionId)
        if existing is None:
            to_create.append(
                {
                    "interviewId": interview_id,
                    "questionId": response.questionId,
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": current_user.id,
                }
            )
            continue
        if (
            existing.answerText
            and existing.answeredById != current_user.id
            and not is_admin(current_user)
        ):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the original answer contributor or an admin can change this response",
            )
        if existing.answerText == response.answerText and existing.notes == response.notes:
            continue
        to_update.append(
            (
                existing.id,
                {
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": current_user.id,
                },
            )
        )

    # Validation for the WHOLE batch has already run, so nothing below can leave a half-written set.
    if to_create:
        await db.questionnaireresponse.create_many(data=to_create)
    for row_id, data in to_update:
        await db.questionnaireresponse.update(where={"id": row_id}, data=data)


@router.get("/questions")
async def list_questions(
    _: Any = Depends(get_current_user),
    sectionCode: str | None = None,
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    sections = await section_payloads(activeOnly)
    flattened = [
        question
        for section in sections
        for question in section["questions"]
        if not sectionCode or question["sectionCode"] == sectionCode
    ]
    return public_encode(flattened)


@router.get("/sections")
async def list_sections(
    _: Any = Depends(get_current_user),
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    return await section_payloads(activeOnly)


@router.post("/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    payload: QuestionnaireSectionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    sort_order = payload.sortOrder or await next_section_sort_order()
    created = await db.questionnairesection.create(
        data={
            "code": payload.code.strip(),
            "title": payload.title.strip(),
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@router.patch("/sections/{section_id}")
async def update_section(
    section_id: str,
    payload: QuestionnaireSectionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    await require_section(section_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "code" in data:
        data["code"] = data["code"].strip()
    if "title" in data:
        data["title"] = data["title"].strip()
    updated = await db.questionnairesection.update(where={"id": section_id}, data=data)
    if "code" in data or "title" in data:
        await db.questionnairequestion.update_many(
            where={"sectionId": section_id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
    return public_encode(updated)


@router.delete("/sections/{section_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_section(
    section_id: str,
    _: Any = Depends(require_questionnaire_manager),
) -> None:
    await require_section(section_id)
    await db.questionnairesection.update(where={"id": section_id}, data={"isActive": False})
    await db.questionnairequestion.update_many(where={"sectionId": section_id}, data={"isActive": False})


@router.post("/sections/reorder")
async def reorder_sections(
    payload: QuestionnaireSectionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    for index, section_id in enumerate(payload.sectionIds):
        await require_section(section_id)
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": -(index + 1)})
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": index + 1})
    return await section_payloads(active_only=False)


@router.post("/questions", status_code=status.HTTP_201_CREATED)
async def create_question(
    payload: QuestionnaireQuestionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    section = await require_section(payload.sectionId)
    sort_order = payload.sortOrder or await next_question_sort_order(section.id)
    created = await db.questionnairequestion.create(
        data={
            **section_question_data(section),
            "prompt": payload.prompt.strip(),
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@router.patch("/questions/{question_id}")
async def update_question(
    question_id: str,
    payload: QuestionnaireQuestionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    question = await require_record(db.questionnairequestion, question_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_section(section_id)
        data.update(section_question_data(section))
        if "sortOrder" not in data or data["sortOrder"] is None:
            data["sortOrder"] = await next_question_sort_order(section.id)
    elif question.sectionId and ("sectionCode" not in data or "sectionTitle" not in data):
        section = await require_section(question.sectionId)
        data.update({"sectionCode": section.code, "sectionTitle": section.title})
    updated = await db.questionnairequestion.update(where={"id": question_id}, data=data)
    return public_encode(updated)


@router.delete("/questions/{question_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_question(
    question_id: str,
    _: Any = Depends(require_questionnaire_manager),
) -> None:
    await require_record(db.questionnairequestion, question_id)
    await db.questionnairequestion.update(where={"id": question_id}, data={"isActive": False})


@router.post("/questions/reorder")
async def reorder_questions(
    payload: QuestionnaireQuestionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    section = await require_section(payload.sectionId)
    for index, question_id in enumerate(payload.questionIds):
        await require_record(db.questionnairequestion, question_id)
        await db.questionnairequestion.update(
            where={"id": question_id},
            data={**section_question_data(section), "sortOrder": index + 1},
        )
    return await section_payloads(active_only=False)


@router.get("/interviews")
async def list_interviews(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}

    # The read predicate, composed the way every other record list composes it: nested under AND so
    # it can never be overwritten by the free-text OR below, which is exactly how a filter like this
    # gets silently dropped (`where["OR"] = [...]` replaces the key outright).
    #
    # It is EMPTY today. Reading the repository is open to every signed-in account, and that is the
    # deliberate policy — see the banner above ``records.viewable_where``. It was not always: this
    # route was once the only record list without a row filter at all, and the note that replaced this
    # one recorded the consequence, that a CROWDSOURCE_VOLUNTEER could page through every interview a
    # hundred at a time. What changed is not the tier's entitlement to READ but the whole
    # repository's: everybody may now read everything, and what an interview's answers are protected
    # BY is the download gate on the CSV plus the identity masking in ``public_encode`` — not
    # concealment of the record's existence from the people documenting alongside it.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)

    if search:
        where["OR"] = [{"title": contains(search)}, {"place": contains(search)}, {"notes": contains(search)}]
    if artisanId:
        where["artisans"] = {"some": {"artisanId": artisanId}}
    if workshopId:
        where["workshopId"] = workshopId
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "interviewDate", dateFrom, dateTo)
    total, items = await count_and_page(
        db.questionnaireinterview,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    return page_payload(public_encode(items), total, page, page_size)


# Scalar fields a "create for an existing set" may back-fill on the canonical interview — but ONLY
# when that field is still empty, so one researcher's create can never overwrite another's content.
# workshopId joins the list so a later create can name the workshop an earlier one left blank.
_MERGEABLE_FILL_FIELDS = ("title", "place", "language", "notes", "interviewDate", "workshopId")


async def merge_into_interview(
    existing: Any,
    payload: QuestionnaireInterviewCreate,
    current_user: Any,
    check: WorkshopSubmissionCheck | None = None,
) -> dict[str, Any]:
    """Fold a create-for-an-already-existing-set into the single canonical interview.

    Fills only empty scalar fields (never clobbers a populated one), upserts the submitted answers
    (``upsert_responses`` already blocks a non-owner from changing someone else's answer), and returns
    the canonical row so the client attaches any media to the shared entry. When this fold is what
    first links the interview to a workshop, that link is a workshop submission like any other, so it
    carries the same late-submission stamp and PENDING pin.
    """
    incoming = payload.model_dump()
    fill = {
        field: incoming.get(field)
        for field in _MERGEABLE_FILL_FIELDS
        if not is_empty_value(incoming.get(field)) and is_empty_value(get_value(existing, field))
    }
    if "workshopId" in fill:
        # Seed from the stored metadata so stamping the submission never drops the canonical
        # interview's existing extraMetadata (field provenance and anything else already recorded).
        existing_extra = get_value(existing, "extraMetadata")
        if isinstance(existing_extra, dict):
            fill["extraMetadata"] = dict(existing_extra)
        stamp_workshop_submission(fill, check=check)
        pin_pending_if_late(fill, current_user, check=check, record=existing)
        jsonify_metadata(fill)
    # ``existing`` is passed in bare — only its scalar columns are read above — and ``update`` hands
    # back the saved row, so the canonical interview is never read a third time just to answer with it.
    canonical = existing
    if fill:
        canonical = await db.questionnaireinterview.update(where={"id": existing.id}, data=fill)
    if payload.responses:
        await upsert_responses(existing.id, payload.responses, current_user)
    await hydrate_relations([canonical], RELATIONS)
    return public_encode(canonical)


@router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    # Workshop entries: enforce assignment BEFORE the dedupe short-circuit, so folding into an
    # existing interview can never be used to slip past a workshop the user is not assigned to.
    check = await enforce_workshop_submission(current_user, payload.workshopId)
    # One interview per exact artisan set: if one already exists for this set, fold into it instead
    # of creating a duplicate. This holds for EVERY client (web + old/new app) regardless of UI.
    set_key = artisan_set_key(payload.artisanIds)
    if set_key:
        # Bare row: the merge only reads scalar columns off it, and hydrates the relations once at
        # the end — so the common "fold into the existing entry" path stops paying for six relation
        # statements it was about to discard.
        existing = await db.questionnaireinterview.find_unique(where={"artisanSetKey": set_key})
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user, check)

    # Everything above this line REACHES an interview that already exists; everything below OPENS a
    # new one, and only that is a create. The gate sits here rather than on the signature because a
    # field contributor or volunteer answering the questionnaire for an already-interviewed artisan
    # set posts to this same endpoint and folds into the canonical row — refusing them at the door
    # would take away the contribution path these two tiers exist for.
    assert_can_create_records(current_user)
    data = clean_data(payload.model_dump(exclude={"artisanIds", "responses"}))
    # No longer a user-facing field: fall back to when the interview was actually recorded.
    default_interview_date(data)
    data = await attach_location(data)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    data["artisanSetKey"] = set_key
    merge_field_provenance(data, current_user, previous=None)
    jsonify_metadata(data)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.questionnaireinterview.create(data=data)
    except UniqueViolationError:
        # Race: a concurrent request won the create for this set. Fold into the canonical row.
        existing = await db.questionnaireinterview.find_unique(where={"artisanSetKey": set_key})
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user, check)
        raise
    if payload.artisanIds:
        await replace_interview_artisans(created.id, payload.artisanIds)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user)
    # The row we just inserted IS the response; only its links and answers were written afterwards,
    # and those load in one wave here instead of a re-read followed by six more statements.
    await hydrate_relations([created], RELATIONS)
    return public_encode(created)


@router.get("/interviews/by-artisans")
async def interview_for_artisan_set(
    artisanIds: list[str] = Query(default=[]),
    _: Any = Depends(get_current_user),
) -> dict[str, Any] | None:
    """The single canonical interview for an EXACT set of artisans, or ``null``.

    Lets a client show the one shared entry — and which sections/questions others have already
    recorded — before offering to create. A subset of the artisans is a different set, so it will not
    match here. Declared before ``/{interview_id}`` so the literal path wins the route match.
    """
    set_key = artisan_set_key(artisanIds)
    if not set_key:
        return None
    interview = await db.questionnaireinterview.find_unique(where={"artisanSetKey": set_key})
    if not interview:
        return None
    await hydrate_relations([interview], RELATIONS)
    return public_encode(interview)


COMPLETION_STATUSES = {"COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"}


async def _zero() -> int:
    """An awaitable 0, so a conditional count keeps its slot in a ``gather_reads`` wave.

    Same reason as ``map_points._none``: the wave returns positionally, so a skipped read cannot simply
    be omitted without renumbering every unpack below it.
    """
    return 0


async def _derived_completed_sections(
    artisan_id: str | None = None, workshop_ids: list[str] | None = None
) -> dict[str, set[str]]:
    """artisanId -> set of sectionIds with recorded content in ANY interview containing the artisan.

    "Containing" covers the artisan interviewed alone, in a group, or as part of a larger superset —
    a section recorded for any interview the artisan belongs to counts as completed for that artisan.
    A section counts as recorded in an interview when it has a non-empty response, or media tagged
    (in ``extraMetadata``) with that section's question or code.

    The three reads do not depend on one another, so they are issued together rather than in series.
    ``artisan_id`` narrows the interview scan to the interviews that artisan belongs to: the
    per-artisan View Data view asks about ONE artisan, and reading every interview in the repository
    (with every answer and every media row attached) to answer that is the difference between a page
    that stays fast at a hundred artisans and one that does not.

    ``workshop_ids`` narrows it to the interviews taken AT those workshops, which is what makes the
    matrix answerable per workshop: "which sections did we cover at last week's workshop" is a
    different question from "which sections has this artisan ever answered", and before this the
    matrix could only answer the second one. The clause is built by the SHARED
    ``record_filters.workshop_clause`` so the interview scope means exactly what it means everywhere
    else, the reserved "none" value included.
    """
    interview_where: dict[str, Any] = (
        {"artisans": {"some": {"artisanId": artisan_id}}} if artisan_id else {}
    )
    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        # An interview carries its own ``workshopId`` foreign key, so it takes the ordinary branch of
        # the shared clause builder rather than the workshop-table one.
        clause = workshop_clause(ids, include_unassigned)
        interview_where.setdefault("AND", []).append(clause if clause else {"id": {"in": []}})
    questions, sections, interviews = await gather_reads(
        db.questionnairequestion.find_many(),
        db.questionnairesection.find_many(),
        db.questionnaireinterview.find_many(
            where=interview_where,
            include={"artisans": True, "responses": True, "media": True},
        ),
    )
    section_by_question = {q.id: q.sectionId for q in questions if q.sectionId}
    section_id_by_code = {s.code: s.id for s in sections}
    # Normalised code -> sectionId, matching how the app builds clip filenames (uppercase, strip
    # non-alphanumerics) so the SECTION_QUESTION_... nomenclature resolves back to its section.
    section_id_by_norm_code = {_norm_code(s.code): s.id for s in sections if _norm_code(s.code)}
    valid_codes = {_norm_code(s.code) for s in sections if _norm_code(s.code)}
    completed: dict[str, set[str]] = {}
    for interview in interviews:
        recorded: set[str] = set()
        # Title-named sections: the only signal for pre-nomenclature recordings (titled by section).
        for code in section_codes_from_title(interview.title, valid_codes):
            section_id = section_id_by_norm_code.get(code)
            if section_id:
                recorded.add(section_id)
        for response in interview.responses or []:
            section_id = section_by_question.get(response.questionId)
            if section_id and not is_empty_value(response.answerText):
                recorded.add(section_id)
        for media in interview.media or []:
            meta = media.extraMetadata if isinstance(media.extraMetadata, dict) else None
            if meta:
                section_id = section_by_question.get(meta.get("questionId"))
                if section_id:
                    recorded.add(section_id)
                code_section = section_id_by_code.get(meta.get("sectionCode"))
                if code_section:
                    recorded.add(code_section)
            # Fallback: the audio clip filename leads with the section code
            # (SECTIONCODE_QUESTION_INTERVIEW_DURATION_STAMP), the only section signal carried by the
            # app's recorded questionnaire clips.
            first_token = (media.originalFilename or "").split("_", 1)[0]
            code_section = section_id_by_norm_code.get(_norm_code(first_token))
            if code_section:
                recorded.add(code_section)
        if recorded:
            for link in interview.artisans or []:
                completed.setdefault(link.artisanId, set()).update(recorded)
    return completed


@router.get("/completion")
async def completion_matrix(
    artisanId: str | None = None,
    # The workshop scope, from the shared filter vocabulary: repeatable or comma-joined workshop ids
    # plus the reserved value "none"; omitted means every workshop. It narrows BOTH halves of the
    # matrix — which artisans are rows, and which interviews count towards a cell — because a matrix
    # that scoped only one of the two would show a workshop's artisans against every interview they
    # have ever sat in, which is precisely the confusion the control exists to remove.
    workshopIds: list[str] | None = Query(None),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Completion matrix: artisans (rows) x active sections (columns). Each populated cell carries the
    data-derived completion flag and any admin override (COMPLETED/NEEDS_REVIEW/NEEDS_REDO). Pass
    ``artisanId`` to scope it to a single artisan (the per-artisan View Data view), and/or
    ``workshopIds`` to scope it to one or more workshops."""
    # Four independent questions — which sections are active, which artisans are in scope, what the
    # data says is recorded, and what an admin has overridden — asked in one wave instead of one
    # after the other. The matrix was ten sequential cross-region round trips, and it is the first
    # thing the View Data screen loads.
    status_where = {"artisanId": artisanId} if artisanId else {}
    artisan_where: dict[str, Any] = {"id": artisanId} if artisanId else {}

    # WHICH ARTISANS ARE ROWS, under a workshop scope. An artisan belongs to a workshop two ways and
    # both count: the ``workshopId`` column on the artisan record, and the WorkshopArtisan roster that
    # carried the link before that column existed — the same OR ``GET /artisans?workshopId=`` uses. An
    # artisan who merely SAT IN an interview at the workshop counts too, because that is what makes
    # them a row worth checking; without it a workshop whose roster was never filled in would show an
    # empty matrix while its interviews sat right there.
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        artisan_where.setdefault("AND", []).append(
            artisan_workshop_clause(*resolved_workshops)
        )

    # HOW MANY INTERVIEWS THIS SCOPE CANNOT SEE, asked in the same wave so it costs no round trip.
    #
    # An interview with a NULL ``workshopId`` counts towards NO workshop scope. That is correct — it
    # genuinely does not say which workshop it was taken at — but it is also the exact shape of the bug
    # this number exists to make visible: the matrix used to answer "nothing was covered here" while
    # twenty-five interviews sat in the repository unlinked, and there was nothing on screen to tell a
    # reader that a filter, not the fieldwork, was the reason.
    #
    # Only asked when a scope is actually narrowing. With no scope, or with the reserved "none" value in
    # play, those interviews ARE in the derived scan, so there is no shortfall to report and a non-zero
    # number would be a warning about nothing.
    unassigned_relevant = resolved_workshops is not None and not resolved_workshops[1]
    sections, artisan_rows, derived, overrides, unassigned_interviews = await gather_reads(
        db.questionnairesection.find_many(where={"isActive": True}, order={"sortOrder": "asc"}),
        db.artisan.find_many(where=artisan_where, order={"name": "asc"}),
        _derived_completed_sections(artisanId, workshopIds),
        db.questionnairesectionstatus.find_many(where=status_where, include={"setBy": True}),
        # Narrowed to the one artisan on the per-artisan view, where the question is about them; left
        # repository-wide on the full matrix, because the artisans in scope are not known until the read
        # beside this one has returned and a second wave to refine a warning count is a round trip spent
        # on a sentence.
        count_unassigned_interviews([artisanId] if artisanId else None)
        if unassigned_relevant
        else _zero(),
    )
    if artisanId and not artisan_rows:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    artisans = list(artisan_rows)
    artisan_ids = {a.id for a in artisans}

    override_by_cell = {
        (o.artisanId, o.sectionId): o for o in overrides if o.artisanId in artisan_ids
    }

    section_ids = {s.id for s in sections}
    cells: list[dict[str, Any]] = []
    for artisan in artisans:
        derived_ids = derived.get(artisan.id, set())
        for section in sections:
            override = override_by_cell.get((artisan.id, section.id))
            is_derived = section.id in derived_ids
            if override is None and not is_derived:
                continue
            cells.append(
                {
                    "artisanId": artisan.id,
                    "sectionId": section.id,
                    "derived": is_derived,
                    "status": override.status if override else None,
                    "setByName": get_value(override.setBy, "name") if override else None,
                }
            )
    # Defensively drop overrides that point at now-inactive sections from the matrix output.
    cells = [c for c in cells if c["sectionId"] in section_ids]
    return {
        "sections": [
            {"id": s.id, "code": s.code, "title": s.title, "sortOrder": s.sortOrder}
            for s in sections
        ],
        "artisans": [{"id": a.id, "name": a.name} for a in artisans],
        "cells": cells,
        # THE SHORTFALL, stated rather than left to be inferred from an empty matrix. Zero whenever the
        # scope cannot hide anything (no workshop chosen, or "none" explicitly included). See the read
        # above; both clients render a notice when this is non-zero and an admin gets the way to fix it.
        "unassignedInterviews": int(unassigned_interviews or 0),
        # An admin mark is a judgement about (artisan, section) across the REPOSITORY — the override
        # table is keyed on those two columns alone and carries no workshop — so a marked cell shows
        # under every workshop scope, while the green DERIVED from recordings is scoped to the workshops
        # chosen. Said on the wire so both clients can say it in the legend instead of each deciding
        # whether it matters.
        "overridesAreRepositoryWide": True,
    }


@router.put("/completion")
async def set_completion_cell(
    payload: CompletionCellUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin/master-admin only: set or clear the manual status for one (artisan, section) cell."""
    await require_record(db.artisan, payload.artisanId)
    await require_record(db.questionnairesection, payload.sectionId)
    key = {"artisanId_sectionId": {"artisanId": payload.artisanId, "sectionId": payload.sectionId}}
    if payload.status is None:
        await db.questionnairesectionstatus.delete_many(
            where={"artisanId": payload.artisanId, "sectionId": payload.sectionId}
        )
        return {"cleared": True}
    if payload.status not in COMPLETION_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {sorted(COMPLETION_STATUSES)} or null",
        )
    record = await db.questionnairesectionstatus.upsert(
        where=key,
        data={
            "create": {
                "artisanId": payload.artisanId,
                "sectionId": payload.sectionId,
                "status": payload.status,
                "setById": current_user.id,
            },
            "update": {"status": payload.status, "setById": current_user.id},
        },
    )
    return public_encode(record)


@router.get("/interviews/{interview_id}")
async def get_interview(interview_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    await hydrate_relations([interview], RELATIONS)
    return public_encode(interview)


@router.patch("/interviews/{interview_id}")
async def update_interview(
    interview_id: str,
    payload: QuestionnaireInterviewUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}))
    data = await attach_location(data)
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != interview.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    privileged = await guard_record_edit(interview, current_user, data, "questionnaire")
    await apply_status_policy_update(current_user, interview, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=interview)
    pin_pending_if_late(data, current_user, check=check, record=interview)
    merge_field_provenance(data, current_user, previous=interview)
    resubmit_status(interview, current_user, data)
    jsonify_metadata(data)
    if data:
        await db.questionnaireinterview.update(where={"id": interview_id}, data=data)
    if payload.artisanIds is not None:
        link_count = await db.questionnaireinterviewartisan.count(where={"interviewId": interview_id})
        if not privileged:
            assert_can_contribute_relation(interview, current_user, link_count > 0, "artisanIds")
        await replace_interview_artisans(interview_id, payload.artisanIds)
    if payload.responses is not None:
        await upsert_responses(interview_id, payload.responses, current_user)
    # Re-read the row itself (the PATCH may have changed its columns), but graft the six relations on
    # in one parallel wave rather than letting the include walk them one after another.
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([updated], RELATIONS)
    return public_encode(updated)


# --- One artisan's questionnaire, gathered across every interview they sat in --------------------
#
# Lives on the questionnaire router rather than under /artisans because what it returns is
# questionnaire content — sections, questions, answers, the interviews they came from — merely keyed
# by an artisan. The artisan router's surfaces are about identity (name, craft, Aadhaar), which is
# the one thing this view must never carry.
#
# Declared after /interviews/{interview_id} because the paths cannot collide: these begin with the
# literal /artisans.


async def _consolidated_or_404(
    artisan_id: str, current_user: Any, workshop_ids: list[str] | None = None
) -> dict[str, Any]:
    payload = await consolidate_for_artisan(artisan_id, current_user, workshop_ids)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return payload


@router.get("/artisans/{artisan_id}/consolidated")
async def consolidated_questionnaire(
    artisan_id: str,
    # The workshop scope, from the shared filter vocabulary. Omitted means every interview this
    # artisan belongs to, which is the document's original meaning and stays the default.
    workshopIds: list[str] | None = Query(None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """This artisan's answers from every interview they belong to, as one ordered document.

    Ordered by questionnaire section then question — not by interview — so it reads as a single
    account. Every answer carries the interview it came from, that interview's date and the number of
    artisans in the sitting; a question answered differently in two interviews keeps BOTH answers,
    newest first, with ``conflict`` set. See services/questionnaire_consolidation for why nothing here
    picks a winner and why a group sitting's answers are not attributed to one person.

    Pass ``workshopIds`` to read the document as it stands FOR THOSE WORKSHOPS. The whole document is
    recomputed over the narrowed interview set — summary counts, conflict flags and sources alike —
    rather than being filtered after the fact, because a disagreement between two workshops is not a
    disagreement within one of them.

    The stored one-interview-per-artisan-SET rule is untouched: this reads, it never merges.
    """
    return await _consolidated_or_404(artisan_id, current_user, workshopIds)


@router.get("/artisans/{artisan_id}/consolidated.csv")
async def consolidated_questionnaire_csv(
    artisan_id: str,
    workshopIds: list[str] | None = Query(None),
    current_user: Any = Depends(get_current_user),
) -> Response:
    """The same document as a CSV — the artifact a researcher cites.

    GATED, and it did not used to be. The old justification was that this "returns exactly the rows
    the JSON endpoint already returned to this same caller", which was true while READING was itself
    owner-scoped: whoever could open the document could already have transcribed it. Reading is open
    now (``records.viewable_where``), and the repository's rule is that everybody may LOOK at every
    record while taking data out stays earned. A CSV is taking data out — it is the file, not the view
    — so the entitlement is checked here rather than inherited from the page beside it.

    The check is ``owner_download_scope`` against the ARTISAN'S OWNER, not the blanket
    dataset-download permission that /export/*.csv uses. Those are whole-table dumps and deserve the
    blunter gate; this is one artisan's document, so the right question is "may you download THIS
    researcher's data" — which is yes for an admin, for a global dataset downloader, for the
    researcher who recorded the artisan, and for anyone holding a DOWNLOAD+ grant from them. A subset
    grant that does not name this artisan is a 403, and so is no grant at all.

    One row per ANSWER, not per question, because that is what keeps a conflict legible in a
    spreadsheet: the two competing answers are two rows sharing a question, each with its own
    interview and date, rather than one cell with both crammed in.
    """
    artisan = await require_record(db.artisan, artisan_id)
    scope = await owner_download_scope(current_user, get_value(artisan, "createdById"))
    # ``None`` means "all of that owner's data". A subset grant has to actually name this artisan;
    # otherwise the caller holds a download right over some other record entirely.
    if scope is not None and artisan_id not in scope.get("artisans", set()):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Your download access to this researcher's data does not cover this artisan. "
            "You can still read the consolidated questionnaire on screen.",
        )
    payload = await _consolidated_or_404(artisan_id, current_user, workshopIds)
    output = StringIO()
    writer = csv.writer(output)
    writer.writerow(CSV_COLUMNS)
    for row in consolidated_rows(payload):
        writer.writerow(row)
    name = payload.get("artisan", {}).get("name") or artisan_id
    slug = re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-") or artisan_id
    return csv_response(f"questionnaire-{slug}.csv", output.getvalue())


@router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    await db.questionnaireinterview.delete(where={"id": interview_id})
