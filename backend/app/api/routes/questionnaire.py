import csv
import re
from datetime import UTC, datetime
from io import StringIO
from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
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
    can_manage_questionnaire,
    get_current_user,
    get_value,
    is_admin,
    is_empty_value,
    require_admin,
    require_questionnaire_manager,
)
from app.services.access import guard_record_edit, owner_download_scope
from app.schemas.questionnaire import (
    MAX_HELP_CHARS,
    CompletionCellUpdate,
    QuestionnaireCreate,
    QuestionnaireDefaultUpdate,
    QuestionnaireInterviewCreate,
    QuestionnaireInterviewUpdate,
    QuestionnaireQuestionCreate,
    QuestionnaireQuestionReorder,
    QuestionnaireQuestionUpdate,
    QuestionnaireSectionCreate,
    QuestionnaireSectionReorder,
    QuestionnaireSectionUpdate,
    QuestionnaireUpdate,
)
from app.services.questionnaire_instruments import (
    default_questionnaire_id,
    require_questionnaire,
    resolve_questionnaire_id,
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
from app.services.questionnaire_xlsx import (
    MAX_SECTIONS,
    PRO_FORMA_FILENAME,
    XLSX_MIME,
    ParsedQuestionnaire,
    QuestionnaireXlsxError,
    build_pro_forma,
    build_question_set_workbook,
    build_questionnaire_workbook,
    derive_section_code,
    download_filename,
    parse_questionnaire_workbook,
    question_set_filename,
)
from app.services.uploads import read_upload_bounded
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

# TWO PREFIXES, ONE MOUNT POINT.
#
# `router` carries no prefix of its own. It exists so `api/router.py`'s single
# `include_router(questionnaire.router)` mounts BOTH of the routers below without that file having
# to learn about the second one — which matters because the plural router is the PARENT of the
# singular one's tables, added in the same change, and a registration split across two files is how
# a route ships 404ing with nothing in the diff to show why.
#
# `/questionnaire` (SINGULAR) is the instrument's CONTENT: sections, questions, interviews, answers,
# the completion matrix. `/questionnaires` (PLURAL) is the list of instruments themselves. The split
# is deliberate and the designer portal records the same hazard for the same pair of names
# (designer-portal/backend/app/api/routes/questionnaire_forms.py:3-8): a client counting characters
# to tell `/questionnaire` from `/questionnaires` apart will get it wrong, so they are never
# interchangeable and never fall through to one another.
router = APIRouter()
questionnaire_router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])
instruments_router = APIRouter(prefix="/questionnaires", tags=["questionnaires"])


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
    # WHICH INSTRUMENT THIS SITTING WAS TAKEN ON, hydrated like every other relation. It costs no
    # wall time (the wave is parallel) and it is what lets a list of interviews say which
    # questionnaire each was answered against — with two instruments running overlapping section
    # codes, an interview that does not name its instrument is not fully identified.
    Relation("questionnaire", "questionnaire", "questionnaireId"),
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


async def next_section_sort_order(questionnaire_id: str) -> int:
    """The next free slot IN ONE INSTRUMENT.

    SCOPED, and the unscoped version was the easiest thing in this change to miss. It read the
    GLOBAL max, so the builder's first "add a section" on the 3rd-workshop instrument would compute
    max(every instrument) + 1 and write 25 into an instrument whose sections run 1..22. That does
    not collide with anything, so nothing raises — it simply puts every newly added section in a
    silently wrong place, and the gap compounds with each one.
    """
    sections = await db.questionnairesection.find_many(
        where={"questionnaireId": questionnaire_id}, order={"sortOrder": "desc"}, take=1
    )
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


async def section_payloads(questionnaire_id: str, active_only: bool = True) -> list[dict[str, Any]]:
    """One instrument's sections with their questions nested, in order.

    THE SINGLE CHOKE POINT for every structure read — /questions, /sections, and the two reorder
    routes all return through here, so narrowing it once narrows all four. ``questionnaire_id`` is a
    REQUIRED positional rather than an optional keyword on purpose: every one of those four call
    sites has to answer "which instrument", and a default would let a new caller ship serving
    whichever instrument the database happened to return first.
    """
    section_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    question_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    if active_only:
        section_where["isActive"] = True
        question_where["isActive"] = True
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


async def upsert_responses(
    interview_id: str, responses: list[Any], current_user: Any, questionnaire_id: str
) -> None:
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
    # ANSWERS MAY NOT CROSS INSTRUMENTS. The questions were already read above, so this costs
    # nothing — and without it a client holding a stale section list from the 2nd workshop's
    # instrument would write its answers onto a 3rd-workshop sitting, where the completion matrix,
    # the consolidated document and the CSV would all file them under the wrong headings. Checked
    # here rather than by narrowing the read above, so the message says what actually went wrong
    # instead of reporting a question that plainly exists as missing.
    #
    # This catches a client with a STALE question list. It cannot catch a client with NO list — an
    # Android build predating 2026-09-13 sends no questionnaireId and resolves to the default, whose
    # questions all pass this check. That is why the default must not be moved until those handsets
    # have updated AND their outboxes have drained; see questionnaire_instruments.py.
    strays = sorted({q.id for q in questions if q.questionnaireId != questionnaire_id})
    if strays:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"These questions belong to a different questionnaire than this interview: "
                f"{strays}. Reload the questionnaire and try again."
            ),
        )
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


@questionnaire_router.get("/questions")
async def list_questions(
    _: Any = Depends(get_current_user),
    sectionCode: str | None = None,
    activeOnly: bool = True,
    questionnaireId: str | None = None,
    workshopId: str | None = None,
) -> list[dict[str, Any]]:
    """One instrument's questions, flattened. ``questionnaireId`` omitted means the workshop's bound
    instrument, and failing that the default — which is what every client sent before 2026-09-13 and
    what they keep getting.

    WHY ``workshopId`` IS HERE AND WHAT IT FIXES. ``resolve_questionnaire_id`` is a three-step rule —
    explicit id, then the workshop's bound instrument, then the default — and this handler used to
    call it with only the first step, so the second was unreachable from a read. Binding an
    instrument to a workshop (``PUT /workshops/{id}/questionnaire``) therefore changed what
    ``POST /interviews`` FILED (it passes the workshop, :851) and changed nothing about what a
    researcher SAW. That split is the worst arrangement available: on 2026-09-14 the 3rd Craft
    Toolkit Workshop was bound, and this endpoint went on serving the 2nd instrument's 24 sections —
    RESP, A..W, whose V is "International Exposure and Overseas Travel" where the 3rd's is
    "Network / Ecosystem Mapping". A researcher would have answered one instrument's questions and
    had them stored under the other's.

    An absent ``workshopId`` still resolves to the default, so every client written before
    2026-09-13 keeps the behaviour it has always had.
    """
    qid = await resolve_questionnaire_id(questionnaireId, workshopId)
    sections = await section_payloads(qid, activeOnly)
    flattened = [
        question
        for section in sections
        for question in section["questions"]
        if not sectionCode or question["sectionCode"] == sectionCode
    ]
    return public_encode(flattened)


@questionnaire_router.get("/sections")
async def list_sections(
    _: Any = Depends(get_current_user),
    activeOnly: bool = True,
    questionnaireId: str | None = None,
    workshopId: str | None = None,
) -> list[dict[str, Any]]:
    """One instrument's sections, each carrying its questions.

    WHY ``workshopId`` IS HERE AND WHAT IT FIXES. ``resolve_questionnaire_id`` is a three-step rule —
    explicit id, then the workshop's bound instrument, then the default — and this handler used to
    call it with only the first step, so the second was unreachable from a read. Binding an
    instrument to a workshop (``PUT /workshops/{id}/questionnaire``) therefore changed what
    ``POST /interviews`` FILED (it passes the workshop, :851) and changed nothing about what a
    researcher SAW. That split is the worst arrangement available: on 2026-09-14 the 3rd Craft
    Toolkit Workshop was bound, and this endpoint went on serving the 2nd instrument's 24 sections —
    RESP, A..W, whose V is "International Exposure and Overseas Travel" where the 3rd's is
    "Network / Ecosystem Mapping". A researcher would have answered one instrument's questions and
    had them stored under the other's.

    An absent ``workshopId`` still resolves to the default, so every client written before
    2026-09-13 keeps the behaviour it has always had.
    """
    qid = await resolve_questionnaire_id(questionnaireId, workshopId)
    return await section_payloads(qid, activeOnly)


@questionnaire_router.post("/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    payload: QuestionnaireSectionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """Add a section to ONE instrument.

    STILL `require_questionnaire_manager`, deliberately. Choosing the default instrument and binding
    one to a workshop were narrowed to `require_admin` in this change; building a form was NOT, and
    a Professor who can build the form must not lose the ability to build it.
    """
    # `payload.questionnaireId` is optional (see the schema): an un-updated builder sends nothing
    # and lands on the default, exactly as it did before this change existed.
    questionnaire_id = await resolve_questionnaire_id(payload.questionnaireId)
    sort_order = payload.sortOrder or await next_section_sort_order(questionnaire_id)
    try:
        created = await db.questionnairesection.create(
            data={
                "questionnaireId": questionnaire_id,
                "code": payload.code.strip(),
                "title": payload.title.strip(),
                "sortOrder": sort_order,
                "isActive": payload.isActive,
            }
        )
    except UniqueViolationError as exc:
        # @@unique([questionnaireId, code]) and @@unique([questionnaireId, sortOrder]). Before this
        # handler caught it, a duplicate code came back as a bare JSON 500 out of
        # UnhandledErrorMiddleware — a builder typing a code that already exists got "something went
        # wrong" instead of being told what it was.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"A section with code “{payload.code.strip()}” (or that position) already exists "
                f"in this questionnaire."
            ),
        ) from exc
    return public_encode(created)


@questionnaire_router.patch("/sections/{section_id}")
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
    # NO INSTRUMENT CHANGE IS POSSIBLE HERE and none is checked for: `QuestionnaireSectionUpdate`
    # carries no `questionnaireId` and `APIModel` is extra="forbid", so a client that sends one is
    # refused by pydantic before this handler runs.
    try:
        updated = await db.questionnairesection.update(where={"id": section_id}, data=data)
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Another section in this questionnaire already has that code or position.",
        ) from exc
    if "code" in data or "title" in data:
        await db.questionnairequestion.update_many(
            where={"sectionId": section_id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
    return public_encode(updated)


@questionnaire_router.delete("/sections/{section_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_section(
    section_id: str,
    _: Any = Depends(require_questionnaire_manager),
) -> None:
    await require_section(section_id)
    await db.questionnairesection.update(where={"id": section_id}, data={"isActive": False})
    await db.questionnairequestion.update_many(where={"sectionId": section_id}, data={"isActive": False})


@questionnaire_router.post("/sections/reorder")
async def reorder_sections(
    payload: QuestionnaireSectionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    """Renumber one instrument's sections 1..n, in the order given.

    ONE INSTRUMENT AT A TIME, refused otherwise. `@@unique([questionnaireId, sortOrder])` is scoped,
    so a list spanning two instruments would renumber BOTH of them 1..n — silently re-ordering an
    instrument the caller never opened, and doing it through the negative pass so a concurrent
    reader sees it backwards on the way. The sections are loaded in ONE read rather than the old
    per-id `require_section` loop, because the check needs all of them before the first write.
    """
    sections = await db.questionnairesection.find_many(where={"id": {"in": payload.sectionIds}})
    by_id = {section.id: section for section in sections}
    if len(by_id) != len(set(payload.sectionIds)):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    instrument_ids = {section.questionnaireId for section in sections}
    if len(instrument_ids) != 1:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"sectionIds must all belong to one questionnaire; these span "
                f"{len(instrument_ids)}."
            ),
        )
    questionnaire_id = next(iter(instrument_ids))
    # The negative-then-positive two-pass, unchanged: @@unique([questionnaireId, sortOrder]) would
    # otherwise collide mid-loop with a row that has not been renumbered yet.
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": -(index + 1)})
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": index + 1})
    return await section_payloads(questionnaire_id, active_only=False)


@questionnaire_router.post("/questions", status_code=status.HTTP_201_CREATED)
async def create_question(
    payload: QuestionnaireQuestionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    section = await require_section(payload.sectionId)
    sort_order = payload.sortOrder or await next_question_sort_order(section.id)
    created = await db.questionnairequestion.create(
        data={
            # The question inherits its instrument FROM THE SECTION and from nothing else — there
            # is no client-supplied questionnaireId on this route, so the two can never disagree.
            "questionnaireId": section.questionnaireId,
            **section_question_data(section),
            "prompt": payload.prompt.strip(),
            # NAMED EXPLICITLY, because this handler builds its data dict field by field while
            # `update_question` below uses `model_dump(exclude_unset=True)`. Adding the two fields to
            # the schema and stopping there gives an editor whose PATCH stores help text and whose
            # POST answers 201 and stores nothing — two halves of one editor disagreeing, with no
            # error anywhere. `test_a_created_question_keeps_its_help_text_and_required_flag` pins it.
            "helpText": payload.helpText,
            "isRequired": payload.isRequired,
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@questionnaire_router.patch("/questions/{question_id}")
async def update_question(
    question_id: str,
    payload: QuestionnaireQuestionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    question = await require_record(db.questionnairequestion, question_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    # CLEARING HELP TEXT, PUT BACK BY HAND AFTER `clean_data` DROPPED IT.
    #
    # `clean_data` strips every None except the relation FKs in `CLEARABLE_KEYS`
    # (services/records.py), and its comment says why that list is FK-only: blanking a SCALAR is
    # governed per route. So `{"helpText": null}` — an admin deleting the guidance under a question —
    # arrives here, is stripped, and produces a 200 with the old help text still on the row. A
    # save that reports success and changes nothing is the worst of the three possible answers,
    # because the person watching has no reason to look again.
    #
    # `model_fields_set` is the test rather than `data`, because by this line the key is already gone.
    if "helpText" in payload.model_fields_set and payload.helpText is None:
        data["helpText"] = None
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_section(section_id)
        if section.questionnaireId != question.questionnaireId:
            # A QUESTION CANNOT CHANGE INSTRUMENT. Its answers were given under this instrument's
            # sitting, and `QuestionnaireResponse.question` is `Restrict`, so moving the question
            # would leave those answers attached to a row that is no longer on any form they were
            # collected against. Refused rather than repaired: there is no honest destination.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"A question cannot be moved between questionnaires. {question.sectionCode} "
                    f"#{question.sortOrder} belongs to «{question.questionnaireId}»; the target "
                    f"section belongs to «{section.questionnaireId}»."
                ),
            )
        data.update(section_question_data(section))
        if "sortOrder" not in data or data["sortOrder"] is None:
            data["sortOrder"] = await next_question_sort_order(section.id)
    elif question.sectionId and ("sectionCode" not in data or "sectionTitle" not in data):
        section = await require_section(question.sectionId)
        data.update({"sectionCode": section.code, "sectionTitle": section.title})
    updated = await db.questionnairequestion.update(where={"id": question_id}, data=data)
    return public_encode(updated)


@questionnaire_router.delete("/questions/{question_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_question(
    question_id: str,
    _: Any = Depends(require_questionnaire_manager),
) -> None:
    await require_record(db.questionnairequestion, question_id)
    await db.questionnairequestion.update(where={"id": question_id}, data={"isActive": False})


@questionnaire_router.post("/questions/reorder")
async def reorder_questions(
    payload: QuestionnaireQuestionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    """Renumber questions 1..n inside one section — AND re-parent them onto it.

    THIS ROUTE IS A RE-PARENT WEARING A REORDER'S NAME, and that is not an accident: it writes
    `section_question_data(section)` onto every id it is handed, which is how the web builder moves
    a question between sections (a PATCH followed by this call). Before instruments existed that was
    merely surprising. After them it is the ONE route that could produce a row with
    `questionnaireId = <W2>` and `sectionId = <a W3 section>` — invisible to BOTH instruments'
    `section_payloads`, which filter sections and questions by instrument, while its answers stay
    alive under `Restrict`. So the instrument is checked here, before the first write, with the same
    rule and the same message `update_question` uses.

    The questions are read in ONE statement rather than the old per-id `require_record` loop,
    because the check has to see all of them before anything moves.
    """
    section = await require_section(payload.sectionId)
    questions = await db.questionnairequestion.find_many(where={"id": {"in": payload.questionIds}})
    by_id = {question.id: question for question in questions}
    if len(by_id) != len(set(payload.questionIds)):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    strays = sorted(
        q.id for q in questions if q.questionnaireId != section.questionnaireId
    )
    if strays:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"A question cannot be moved between questionnaires. These belong to a different "
                f"one from section {section.code}: {strays}."
            ),
        )
    for index, question_id in enumerate(payload.questionIds):
        await db.questionnairequestion.update(
            where={"id": question_id},
            data={**section_question_data(section), "sortOrder": index + 1},
        )
    # The section's OWN instrument, not a default: this route used to call `section_payloads()` with
    # no argument at all, which after the signature change is a TypeError — a 500 on every question
    # reorder from both clients and from the builder's drag path.
    return await section_payloads(section.questionnaireId, active_only=False)


@questionnaire_router.get("/interviews")
async def list_interviews(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    questionnaireId: str | None = None,
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
    # A PLAIN TOP-LEVEL KEY, beside `workshopId`, and neither under `where["OR"]` nor under
    # `where["AND"]`. `where["OR"]` is ASSIGNED OUTRIGHT by the free-text search above, so anything
    # parked there is dropped the moment a caller also passes `search=`; `where["AND"]` does not
    # exist yet at this point in the function (it is assigned from `and_filters` below, and only if
    # that list is non-empty), so writing into it would be a KeyError. Every other scalar filter on
    # this route is a plain key for exactly these two reasons.
    if questionnaireId:
        where["questionnaireId"] = questionnaireId
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
#
# `questionnaireId` MUST NOT JOIN THIS LIST, and it is the one field here whose absence needs
# stating. A fold never changes an instrument: the row being folded into was FOUND by
# (questionnaireId, artisanSetKey), so it is already on the caller's instrument by construction —
# and if it were not, filling the column would silently move a sitting, and with it every answer
# already attached, onto questions it was never asked. It is also not "empty" on any row: the column
# is NOT NULL, so `is_empty_value` would never have selected it anyway. Written down because the
# next person adding a mergeable field will read this tuple and not the schema.
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
        # The instrument comes off the STORED row, not off the payload: this is the sitting the
        # answers are joining, so it is the sitting's questions they have to belong to.
        await upsert_responses(
            existing.id, payload.responses, current_user, get_value(existing, "questionnaireId")
        )
    await hydrate_relations([canonical], RELATIONS)
    return public_encode(canonical)


@questionnaire_router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    # Workshop entries: enforce assignment BEFORE the dedupe short-circuit, so folding into an
    # existing interview can never be used to slip past a workshop the user is not assigned to.
    check = await enforce_workshop_submission(current_user, payload.workshopId)

    # WHICH INSTRUMENT THIS SITTING IS ON, decided BEFORE the dedupe short-circuit, because the
    # dedupe key is now scoped by it. Order: what the client said, else what the workshop is bound
    # to, else the default. An old Android build sends nothing and lands on the default, which is
    # what it has always landed on.
    questionnaire_id = await resolve_questionnaire_id(payload.questionnaireId, payload.workshopId)

    # AN EXPLICIT INSTRUMENT THAT CONTRADICTS THE WORKSHOP'S is a deliberate act — a pilot — and
    # only somebody who could change the binding anyway may perform it. For everybody else it is far
    # more likely to be a stale client than a pilot, and a stale client filing 3rd-workshop answers
    # at a 2nd-workshop event is invisible until somebody reads the report.
    if payload.questionnaireId and payload.workshopId:
        workshop = await db.workshop.find_unique(where={"id": payload.workshopId})
        bound = get_value(workshop, "questionnaireId") if workshop else None
        if bound and bound != payload.questionnaireId and not can_manage_questionnaire(current_user):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "This workshop uses a different questionnaire. Record the interview on the "
                    "workshop's questionnaire, or ask a questionnaire manager to change which one "
                    "the workshop uses."
                ),
            )

    # One interview per exact artisan set PER INSTRUMENT: if one already exists for this set on this
    # instrument, fold into it instead of creating a duplicate. This holds for EVERY client (web +
    # old/new app) regardless of UI. The same five artisans sitting again for a DIFFERENT instrument
    # is a second, legitimate interview — that is the whole point of the scoped unique.
    set_key = artisan_set_key(payload.artisanIds)
    if set_key:
        # `find_first`, NOT `find_unique`, even though (questionnaireId, artisanSetKey) IS a unique
        # index. Prisma's generated compound `where` input for a unique containing a NULLABLE column
        # is a name and a shape this code would then depend on; `find_first` over the same two
        # columns is served by the same index, reads identically, and cannot be broken by a client
        # regeneration. The race is still caught by the UniqueViolationError handler below, which is
        # what actually enforces the rule.
        #
        # Bare row: the merge only reads scalar columns off it, and hydrates the relations once at
        # the end — so the common "fold into the existing entry" path stops paying for six relation
        # statements it was about to discard.
        existing = await db.questionnaireinterview.find_first(
            where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key}
        )
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
    # Written from the RESOLVED id, never from `payload.questionnaireId` directly: the payload's
    # value may be absent (old client) and, when present, has already been validated to exist and
    # checked against the workshop's binding above.
    data["questionnaireId"] = questionnaire_id
    merge_field_provenance(data, current_user, previous=None)
    jsonify_metadata(data)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.questionnaireinterview.create(data=data)
    except UniqueViolationError:
        # Race: a concurrent request won the create for this set ON THIS INSTRUMENT. Fold into the
        # canonical row. Scoped the same way the look-ahead above is, or the recovery would find the
        # other instrument's sitting and fold a 3rd-workshop submission into a 2nd-workshop one.
        existing = await db.questionnaireinterview.find_first(
            where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key}
        )
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user, check)
        raise
    if payload.artisanIds:
        await replace_interview_artisans(created.id, payload.artisanIds)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user, questionnaire_id)
    # The row we just inserted IS the response; only its links and answers were written afterwards,
    # and those load in one wave here instead of a re-read followed by six more statements.
    await hydrate_relations([created], RELATIONS)
    return public_encode(created)


@questionnaire_router.get("/interviews/by-artisans")
async def interview_for_artisan_set(
    artisanIds: list[str] = Query(default=[]),
    questionnaireId: str | None = None,
    workshopId: str | None = None,
    _: Any = Depends(get_current_user),
) -> dict[str, Any] | None:
    """The single canonical interview for an EXACT set of artisans ON ONE INSTRUMENT, or ``null``.

    Lets a client show the one shared entry — and which sections/questions others have already
    recorded — before offering to create. A subset of the artisans is a different set, so it will not
    match here. Declared before ``/{interview_id}`` so the literal path wins the route match.

    SCOPED BY INSTRUMENT, or the capture page shows the OTHER instrument's sitting for the artisans
    just selected and its submit button flips to "Add to shared entry" for a sitting the researcher
    is not in. ``questionnaireId`` omitted resolves to the default, matching what
    ``POST /interviews`` would do with the same (absent) field — the two have to agree or the page
    offers to join an entry the create would not fold into.
    """
    set_key = artisan_set_key(artisanIds)
    if not set_key:
        return None
    # ``workshopId`` IS PASSED HERE FOR THE REASON THE DOCSTRING ABOVE ALREADY GIVES. It says the two
    # "have to agree or the page offers to join an entry the create would not fold into" — and until
    # 2026-09-14 they did not: ``create_interview`` resolves with the workshop (:851) and this
    # resolved without it, so on a workshop with a bound instrument the lookup asked about the
    # DEFAULT instrument's sittings while the create filed into the workshop's. The stated invariant
    # was already written down; only the argument was missing.
    qid = await resolve_questionnaire_id(questionnaireId, workshopId)
    interview = await db.questionnaireinterview.find_first(
        where={"questionnaireId": qid, "artisanSetKey": set_key}
    )
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
    questionnaire_id: str,
    artisan_id: str | None = None,
    workshop_ids: list[str] | None = None,
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

    ``questionnaire_id`` NARROWS ALL THREE READS, AND THAT IS NOT AN OPTIMISATION. Every map built
    below is keyed by a section CODE, and section codes stopped being unique the day a second
    instrument arrived: the 2nd workshop's corpus runs RESP, A..W and the 3rd's runs A..V, so
    ``{s.code: s.id for s in sections}`` over two instruments resolves "A" to whichever row the
    database happened to return LAST. A clip tagged ``extraMetadata.sectionCode = "A"`` on a
    2nd-workshop interview would then turn the THIRD workshop's section A green. Nothing raises;
    the only symptom is a matrix that is confidently wrong. Narrowing the reads makes every map
    single-instrument by construction, which is a stronger guarantee than remembering to key them
    by (questionnaireId, code) at four separate sites.
    """
    interview_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    if artisan_id:
        interview_where["artisans"] = {"some": {"artisanId": artisan_id}}
    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        # An interview carries its own ``workshopId`` foreign key, so it takes the ordinary branch of
        # the shared clause builder rather than the workshop-table one.
        clause = workshop_clause(ids, include_unassigned)
        interview_where.setdefault("AND", []).append(clause if clause else {"id": {"in": []}})
    questions, sections, interviews = await gather_reads(
        db.questionnairequestion.find_many(where={"questionnaireId": questionnaire_id}),
        db.questionnairesection.find_many(where={"questionnaireId": questionnaire_id}),
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


@questionnaire_router.get("/completion")
async def completion_matrix(
    artisanId: str | None = None,
    # WHICH INSTRUMENT THE MATRIX IS ABOUT. Omitted resolves to the default, so every client that
    # predates this parameter keeps getting exactly the matrix it got before.
    questionnaireId: str | None = None,
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
    ``workshopIds`` to scope it to one or more workshops.

    ONE INSTRUMENT PER MATRIX, and the COLUMNS come from a read in this function that is separate
    from the three inside ``_derived_completed_sections``. Narrowing only the latter would leave a
    46-column matrix — two "A", two "B", … — whose derived half is single-instrument: half the
    columns permanently blank, the other half looking right. A test that asserts only "the payload
    names its instrument" goes green over that, which is why the suite also asserts the returned
    ``sections`` array has no duplicate codes.
    """
    instrument = await require_questionnaire(
        questionnaireId if questionnaireId else await default_questionnaire_id()
    )
    questionnaire_id = instrument.id
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
        # THE MATRIX'S COLUMNS. Scoped to the instrument for the reason in the docstring above —
        # this is questionnaire.py's fourth section read and the one nobody was told about.
        db.questionnairesection.find_many(
            where={"isActive": True, "questionnaireId": questionnaire_id},
            order={"sortOrder": "asc"},
        ),
        db.artisan.find_many(where=artisan_where, order={"name": "asc"}),
        _derived_completed_sections(questionnaire_id, artisanId, workshopIds),
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
        # WHICH INSTRUMENT THIS MATRIX IS ABOUT, said on the wire so both clients can put it in the
        # header rather than each deciding whether "Section A" means anything on its own. With two
        # instruments in play a matrix that does not name itself is an unlabelled claim.
        "questionnaireId": questionnaire_id,
        "questionnaireTitle": instrument.title,
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


@questionnaire_router.put("/completion")
async def set_completion_cell(
    payload: CompletionCellUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin/master-admin only: set or clear the manual status for one (artisan, section) cell.

    PER-INSTRUMENT BY CONSTRUCTION since 2026-09-13, with no column added and none needed: the cell
    is keyed on a ``sectionId``, and a section now belongs to exactly one instrument, so a verdict
    on the 2nd workshop's section D cannot leak onto the 3rd workshop's section D. It remains
    repository-wide ACROSS WORKSHOPS, which is what ``overridesAreRepositoryWide: True`` on the
    matrix payload says and which is still true.
    """
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


@questionnaire_router.get("/interviews/{interview_id}")
async def get_interview(interview_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    await hydrate_relations([interview], RELATIONS)
    return public_encode(interview)


@questionnaire_router.patch("/interviews/{interview_id}")
async def update_interview(
    interview_id: str,
    payload: QuestionnaireInterviewUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    # A SITTING NEVER CHANGES INSTRUMENT, and nothing below checks for it because nothing has to:
    # `QuestionnaireInterviewUpdate` carries no `questionnaireId` and `APIModel` is extra="forbid",
    # so a client that sends one is refused by pydantic with a 422 before this handler runs.
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
        await upsert_responses(
            interview_id, payload.responses, current_user, get_value(interview, "questionnaireId")
        )
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
    artisan_id: str,
    current_user: Any,
    workshop_ids: list[str] | None = None,
    questionnaire_id: str | None = None,
) -> dict[str, Any]:
    payload = await consolidate_for_artisan(
        artisan_id, current_user, workshop_ids, questionnaire_id
    )
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return payload


@questionnaire_router.get("/artisans/{artisan_id}/consolidated")
async def consolidated_questionnaire(
    artisan_id: str,
    # The workshop scope, from the shared filter vocabulary. Omitted means every interview this
    # artisan belongs to, which is the document's original meaning and stays the default.
    workshopIds: list[str] | None = Query(None),
    # WHICH INSTRUMENT. ABSENT MEANS EVERY INSTRUMENT, grouped and labelled — the opposite default
    # from every other route here, and deliberately so: "everything this artisan has ever told us"
    # is the document's meaning and narrowing it by default would silently drop half an account.
    questionnaireId: str | None = None,
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
    return await _consolidated_or_404(artisan_id, current_user, workshopIds, questionnaireId)


@questionnaire_router.get("/artisans/{artisan_id}/consolidated.csv")
async def consolidated_questionnaire_csv(
    artisan_id: str,
    workshopIds: list[str] | None = Query(None),
    questionnaireId: str | None = None,
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
    payload = await _consolidated_or_404(artisan_id, current_user, workshopIds, questionnaireId)
    output = StringIO()
    writer = csv.writer(output)
    writer.writerow(CSV_COLUMNS)
    for row in consolidated_rows(payload):
        writer.writerow(row)
    name = payload.get("artisan", {}).get("name") or artisan_id
    slug = re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-") or artisan_id
    return csv_response(f"questionnaire-{slug}.csv", output.getvalue())


@questionnaire_router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    await db.questionnaireinterview.delete(where={"id": interview_id})


# --- /questionnaires (PLURAL) — the instruments themselves ----------------------------------------
#
# The parent of every table the singular router above touches. Not a second subsystem: nothing here
# has storage of its own beyond the one `Questionnaire` row, and every route reads or writes rows
# the routes above also read.
#
# THERE IS NO DELETE, and its absence is the design. Each child FK is `onDelete: Restrict`, so a
# DELETE would be refused by the database anyway — but a route that always 500s is worse than no
# route, and "retire" is what an operator actually wants: `PATCH {isActive: false}` takes an
# instrument out of the picker and out of workshop binding while every interview already recorded
# against it keeps working. One level down, `DELETE /questionnaire/sections/{id}` has meant exactly
# that since it was written.


def _instrument_payload(row: Any, counts: dict[str, dict[str, int]]) -> dict[str, Any]:
    tally = counts.get(row.id, {})
    return {
        "id": row.id,
        "title": row.title,
        "description": row.description,
        "isActive": row.isActive,
        "isDefault": row.isDefault,
        "sortOrder": row.sortOrder,
        "sectionCount": tally.get("sections", 0),
        "questionCount": tally.get("questions", 0),
        "workshopCount": tally.get("workshops", 0),
        "createdAt": public_encode(row.createdAt),
    }


@instruments_router.get("")
async def list_questionnaires(
    _: Any = Depends(get_current_user),
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    """Every instrument, with the three counts a picker needs to describe one.

    FOUR STATEMENTS, in one gathered wave, whatever the instrument count — the same budget rule the
    consolidated document is built to (services/questionnaire_consolidation.QUERY_COUNT). Counting
    per instrument would be 1 + 3N sequential cross-region round trips to answer a question about a
    list of two rows; reading the three child tables whole and tallying in Python is one wave.
    Revisit that when an instrument's sections outgrow a page, not before.
    """
    where: dict[str, Any] = {"isActive": True} if activeOnly else {}
    rows, sections, questions, workshops = await gather_reads(
        db.questionnaire.find_many(where=where, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]),
        db.questionnairesection.find_many(where={"isActive": True}),
        db.questionnairequestion.find_many(where={"isActive": True}),
        db.workshop.find_many(),
    )
    counts: dict[str, dict[str, int]] = {}
    for bucket, collection in (("sections", sections), ("questions", questions), ("workshops", workshops)):
        for item in collection:
            key = get_value(item, "questionnaireId")
            if key:
                counts.setdefault(key, {}).setdefault(bucket, 0)
                counts[key][bucket] += 1
    return [_instrument_payload(row, counts) for row in rows]


# ==================================================================================================
# THE WORKBOOK: DOWNLOADING THE INSTRUMENT AS A SPREADSHEET, AND UPLOADING ONE BACK
# ==================================================================================================
#
# WHY THIS DOOR IS ADMIN-ONLY WHEN THE QUESTION EDITOR IN THE SAME FILE IS PROFESSOR-AND-ABOVE
# --------------------------------------------------------------------------------------------
#
# `require_questionnaire_manager` (app/core/deps.py:454) opens the question editor to professors and
# to anybody holding `canManageQuestionnaire`, and every structure route on `/questionnaire` above
# stands on it. That is right for what it guards: adding one question, renaming one section,
# switching one question off. Each of those is a single, visible, reversible act against a row the
# person is looking at.
#
# AN UPLOAD IS NOT THAT ACT. One spreadsheet re-states the WHOLE instrument, and everything the
# workbook does not mention is removed by rule — so a professor who downloads the questionnaire,
# deletes the rows they are not interested in, and uploads the file has retired every question they
# deleted, across twenty-two sections, against interviews already recorded by forty researchers. The
# edit rule below makes that safe in the sense that nothing is LOST; it does not make it small. The
# blast radius of one press is the difference, and `require_admin` (deps.py:396) is where this
# repository puts acts with that radius — the same place `set_default_questionnaire` already sits.
#
# THE SPLIT MUST NOT BE HARMONISED IN EITHER DIRECTION. Widening these routes to
# `require_questionnaire_manager` hands the whole-instrument press to every grant-holder. Narrowing
# the editor above to `require_admin` takes the section editor away from the professors who use it
# today, which nobody asked for and which no migration would announce.
#
# AND THE TWO GATES ARE DIFFERENT KINDS OF PREDICATE. `is_admin` (deps.py:59-60) is SET MEMBERSHIP
# over {"MASTER_ADMIN", "ADMIN"}; `can_manage_questionnaire` (deps.py:67-69) is a RANK FLOOR at
# PROFESSOR. A rank inserted between 40 and 50 therefore gains the editor automatically and is
# refused the workbook automatically. That is deliberate, it is written down in docs/PERMISSIONS.md
# §1.1, and tests/test_permission_matrix.py asserts it in both directions.
#
# NO ANSWER CROSSES THIS BOUNDARY, IN EITHER DIRECTION
# --------------------------------------------------------------------------------------------
#
# The download half calls `build_questionnaire_workbook` with `entry_labels=[]`, so the workbook
# carries one EMPTY Answer/Notes pair and no value in it. The upload half PARSES answer columns — it
# must, or a workbook carrying them would be silently truncated — counts them, reports the count and
# stores none. That is a narrowing of the module this was ported from, and the reason is the shape of
# an answer here: a `QuestionnaireResponse` hangs off a `QuestionnaireInterview`, which names its
# artisans through `QuestionnaireInterviewArtisan`, carries a location, carries a `RecordStatus` a
# reviewer acts on, and is unique on (questionnaireId, artisanSetKey). A spreadsheet column headed
# "Answer" supplies none of those, so importing one means inventing an interview, inventing its
# artisan set, and recording the uploading ADMIN as the person who sat with the artisan.
#
# WEB ONLY, DELIBERATELY
# --------------------------------------------------------------------------------------------
#
# There is no Android surface for any of this and none is planned. The app has exactly one
# `@Multipart` endpoint out of ~190 routes (`FieldRepositoryApi.kt:283-286`, `analyzeMeasurement`)
# and it sends a photo; every file picker it has is `GetContent()` with an image or audio filter, so
# a workbook door would need a `MultipartBody.Part` builder, a `ContentResolver` read of a document
# URI and a size check before the read, none of which exists. A half-registered admin tile that opens
# nothing — `AdminHubEntry` is a private enum at `MainActivity.kt:8563` whose members are dispatched
# by hand — is worse than no tile. And the act does not belong on a phone: typing eighty-one
# questions into a spreadsheet happens in Excel on a laptop, and the file an admin uploads is the one
# they just edited there. What the phone DOES get is `helpText` and `isRequired` on the question DTO
# and two lines rendering them on the answering screen, because a column an admin fills in that the
# primary client silently ignores is the defect this whole change exists to avoid.

# A questionnaire is a page of typing, not a media file. Generous enough for a two-thousand-question
# instrument with help text on every row, and low enough that this endpoint cannot be used to push a
# hundred megabytes through a synchronous openpyxl parser on a single-worker box. Enforced by
# `services/uploads.read_upload_bounded`, which refuses on the declared Content-Length before the
# spooled body is copied into the heap and counts what actually arrives — see that module's docstring
# for why the obvious `await file.read()` then `len()` spelling is the defect it exists to fix.
WORKBOOK_MAX_UPLOAD_BYTES = 8 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


class QuestionnaireWorkbookError(ValueError):
    """An upload that cannot be applied at all. The message is shown to the admin as-is."""


def _now() -> datetime:
    return datetime.now(UTC)


def _uploaded_answer_rows(parsed: ParsedQuestionnaire) -> int:
    """How many answer rows this workbook would write, counted exactly as a writer would count them.

    Derived rather than approximated by ``len(parsed.entryLabels)``: a workbook can carry six answer
    COLUMNS and one answer, and a number that said "six" would make the sentence below lie about the
    size of what it just declined to import. ``answerNotes`` counts too — a row with only a note and
    no answer text is still a row somebody typed.
    """
    total = 0
    for label in parsed.entryLabels:
        for section in parsed.sections:
            for question in section.questions:
                if question.answers.get(label) or question.answerNotes.get(label):
                    total += 1
    return total


def _came_out_of_the_platform(parsed: ParsedQuestionnaire) -> str | None:
    """Whether this workbook is a DOWNLOAD of a questionnaire rather than a hand-filled pro-forma.

    Returns a short clause naming the evidence, or ``None`` when nothing says it came out of the app.

    TWO SIGNALS, BOTH WRITTEN ONLY BY ``build_questionnaire_workbook``:

    1. ``Questionnaire ID`` on the Details sheet.
    2. Any filled-in cell in the ``Question ID`` column.

    The pro-forma writes both blank and the question-set export writes both blank, so an admin who
    typed a paper interview into a spreadsheet trips neither.

    THE SECOND SIGNAL IS NOT REDUNDANT. Somebody who deletes one cell on the Details sheet, or who
    copies the Questionnaire sheet into a fresh file (losing the Details sheet entirely), would
    otherwise arrive here looking exactly like their own typing while carrying question ids that
    belong to a live instrument.
    """
    origin = (parsed.questionnaireId or "").strip()
    if origin:
        return f"its Details sheet names questionnaire {origin}"
    if any(
        (question.questionId or "").strip()
        for section in parsed.sections
        for question in section.questions
    ):
        return "its Question ID column is filled in"
    return None


def _unique_code(code: str, title: str, taken: set[str]) -> str:
    """A section code unique within its questionnaire.

    The database enforces ``@@unique([questionnaireId, code])``, so a workbook carrying "A" twice
    would otherwise fail the whole upload on the second insert with a bare 500 — after the first half
    of the instrument had already been written, in a backend with no transaction idiom.
    """
    wanted = (code or "").strip()
    if not wanted or wanted.lower() in taken:
        wanted = derive_section_code(title or wanted or "Section", taken)
    else:
        taken.add(wanted.lower())
    return wanted


def workbook_question_data(section: Any) -> dict[str, str]:
    """The four columns every question row carries about where it lives.

    ``sectionCode`` and ``sectionTitle`` are DENORMALISED AND NOT NULL on QuestionnaireQuestion — a
    shape this repository has and the module this was ported from does not — and ``questionnaireId``
    is NOT NULL as well. Writing the section id and not the rest produces either a NOT NULL violation
    (loud, fine) or, on a rename, rows that are correct in the database and wrong on every screen
    that reads a flat question row without joining its section: `artisans.py` prints the heading and
    SORTS on the code, `export.py` writes it into the CSV a researcher cites, `data_browser.py` shows
    it on the browse rows, and `GET /questionnaire/questions?sectionCode=…` filters on it. That is
    why a section rename has to touch every question under it — see the `update_many` in
    `_apply_workbook_section`, which mirrors `update_section` above.
    """
    return {"questionnaireId": section.questionnaireId, **section_question_data(section)}


def _clip_help(
    text: str | None,
    *,
    parsed: ParsedQuestionnaire,
    row: int | None,
    problems: list[dict[str, Any]],
) -> str | None:
    """Help text, cut to what the editor's own schema will accept, and SAID when it was cut.

    MAX_HELP_CHARS is `QuestionnaireQuestionUpdate.helpText`'s `max_length`. The parser does not clip
    help text at all — it clips prompts and answers and deliberately leaves this one alone — so a
    4000-character cell would import happily and then 422 every subsequent PATCH from the builder,
    with a pydantic message about a limit the admin never met and cannot see. Clipping here makes the
    two agree. A CLIP WITH NO PROBLEM ROW IS THE SILENT DROP this module's parser exists to forbid,
    so the row number goes into the report.
    """
    if text is None:
        return None
    if len(text) <= MAX_HELP_CHARS:
        return text
    problems.append(
        {
            "sheet": parsed.sheet,
            "row": row,
            "severity": "warning",
            "reason": (
                f"The help text on this row was longer than {MAX_HELP_CHARS} characters and was "
                "shortened to fit. The question itself was imported in full."
            ),
            "value": text[:120],
        }
    )
    return text[: MAX_HELP_CHARS - 1] + "…"


async def _answer_evidence(question_ids: list[str]) -> tuple[set[str], set[str]]:
    """``(answered, has_any_response_row)`` for these questions — TWO SETS, AND THEY ARE NOT THE SAME.

    ``answered`` is what the edit-after-answers rule turns on: a question with real recorded content
    against it may not be reworded in place and may not be deleted. Filtered in PYTHON rather than in
    the ``where`` clause, because "not null" is not the test — an answer saved as a single space is
    null-ish to a person and non-null to Postgres, and only Python knows that. The app writes exactly
    such a row when somebody opens an interview, tabs through it and saves (see `upsert_responses`),
    and treating that as "answered" would freeze a question nobody ever answered.

    ``notes`` COUNTS AS CONTENT, WHICH IS A DIVERGENCE FROM THE PORT AND IS DELIBERATE. This
    repository's `QuestionnaireResponse` has a `notes` column the designer's answer table does not.
    An interviewer who wrote "artisan declined to answer, see audio" in the notes and left the answer
    blank has recorded something about that wording, and rewording the question would change what
    their note appears to be a note ABOUT.

    ``has_any_response_row`` is the second set and it exists because of a constraint, not a rule.
    ``QuestionnaireResponse.question`` is ON DELETE RESTRICT, so ANY response row — including a
    completely blank one — makes `db.questionnairequestion.delete` raise. The port has no such set
    and would therefore issue a delete that Postgres refuses, MID-LOOP, in a backend with no
    transaction: some questions deleted, some not, an "upload failed" on screen and no way for the
    admin to know what state their instrument is in. A question in this set and not in ``answered``
    is deactivated instead of deleted, and the report says so.
    """
    if not question_ids:
        return set(), set()
    rows = await db.questionnaireresponse.find_many(where={"questionId": {"in": question_ids}})
    answered = {
        row.questionId
        for row in rows
        if (row.answerText or "").strip() or (row.notes or "").strip()
    }
    return answered, {row.questionId for row in rows}


def _answers_not_imported_reason(answer_rows: int, evidence: str | None) -> str:
    """The one sentence both upload paths give an admin about the answers they typed in.

    ONE FUNCTION, so the create path and the edit path cannot drift into two accounts of one rule.
    """
    origin = f" (it came out of the platform — {evidence})" if evidence else ""
    plural = "answer was" if answer_rows == 1 else "answers were"
    return (
        f"The {answer_rows} {plural} typed into this workbook{origin} and NOT imported. An answer in "
        "this repository belongs to an interview: it names the artisan who gave it, where it was "
        "recorded and who recorded it, and a spreadsheet column cannot say any of that. The "
        "questions on those rows were imported in full. Record answers on the Questionnaire page, "
        "against an artisan."
    )


def _empty_workbook_report(version: int) -> dict[str, Any]:
    """Every key both paths return, initialised. ONE LITERAL, so the two reports are one shape.

    A DROPPED KEY IS A BLANK PANEL WITH NO ERROR. The client reads `report.superseded` and
    `report.details` unconditionally; a report that omitted either would make "nothing was
    superseded" indistinguishable from "this client is reading a response shape it does not
    understand". `test_the_report_carries_every_key_the_client_renders` asserts the exact key set
    against a literal, on both paths.
    """
    return {
        "created": 0,
        "updated": 0,
        # THE NUMBER OF SECTIONS IN THE WORKBOOK, on BOTH paths. The port used this key for "total
        # parsed sections" on create and "newly created sections" on edit — one key, two meanings,
        # one renderer, and an admin re-uploading an unchanged file reading "0 sections".
        "sections": 0,
        "sectionsCreated": 0,
        "sectionsRetired": 0,
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "unchanged": 0,
        "answersSkipped": 0,
        "assignedTasksAffected": 0,
        "provenance": None,
        "versionBefore": version,
        "versionAfter": version,
        "problems": [],
        "details": [],
    }


async def create_from_parsed(
    parsed: ParsedQuestionnaire,
    *,
    title: str | None = None,
    description: str | None = None,
    source_filename: str | None = None,
    created_by_id: str | None = None,
) -> tuple[str, dict[str, Any]]:
    """Store a freshly parsed workbook as a NEW questionnaire. Returns ``(id, report)``.

    NO ANSWER IS WRITTEN, AND THERE IS ONE BRANCH HERE RATHER THAN TWO. The module this was ported
    from has a second branch that IMPORTS the answers when the workbook was hand-filled rather than
    downloaded. There is no such branch here and there must not be one: the question is not whose
    answers they are, it is what an answer IS in this database. It is a `QuestionnaireResponse` on a
    `QuestionnaireInterview` that names artisans, carries a location and has a review status a
    reviewer acts on. An import has none of those to give it, so it would have to invent all three.
    """
    rows = await db.questionnaire.find_many(order={"sortOrder": "desc"}, take=1)
    wanted_title = (title or parsed.title or "Untitled questionnaire").strip()
    try:
        questionnaire = await db.questionnaire.create(
            data={
                "title": wanted_title,
                "description": (description or parsed.description or None),
                "sortOrder": (rows[0].sortOrder if rows else 0) + 1,
                "createdById": created_by_id,
                "sourceFilename": source_filename,
                # `isDefault` is NOT set and must not be. An upload creates an instrument; deciding
                # which one every unqualified client lands on is a separate admin act on a separate
                # route, and a partial unique index refuses a second true anyway.
            }
        )
    except UniqueViolationError as exc:
        # @@unique([title]). The title comes from the Details sheet or the filename, so a second
        # upload of the same workbook lands here — and the fix is a sentence, not a 500.
        raise QuestionnaireWorkbookError(
            f"A questionnaire titled “{wanted_title}” already exists. Change the title on "
            "the Details sheet, or upload this file over that questionnaire to edit it instead."
        ) from exc

    report = _empty_workbook_report(questionnaire.version)
    report["problems"] = [p.payload() for p in parsed.problems]
    kept_sections = parsed.sections[:MAX_SECTIONS]
    report["sections"] = len(kept_sections)

    codes_taken: set[str] = set()
    for index, section in enumerate(kept_sections, start=1):
        code = _unique_code(section.code, section.title, codes_taken)
        made = await db.questionnairesection.create(
            data={
                "questionnaireId": questionnaire.id,
                "code": code,
                "title": section.title or code,
                "sortOrder": index,
            }
        )
        report["sectionsCreated"] += 1
        # NO TWO-PASS NEEDED HERE, unlike the edit path: a brand-new questionnaire has no rows for
        # @@unique([questionnaireId, sortOrder]) to collide with, and the unique is scoped to this
        # instrument so nothing another instrument holds is in the way either.
        for position, question in enumerate(section.questions, start=1):
            await db.questionnairequestion.create(
                data={
                    **workbook_question_data(made),
                    "prompt": question.prompt,
                    "helpText": _clip_help(
                        question.helpText,
                        parsed=parsed,
                        row=question.row,
                        problems=report["problems"],
                    ),
                    "isRequired": question.isRequired,
                    "sortOrder": position,
                }
            )
            report["created"] += 1

    _note_skipped_answers(parsed, report)
    return questionnaire.id, report


def _note_skipped_answers(parsed: ParsedQuestionnaire, report: dict[str, Any]) -> None:
    """Count the answers this import declined to store, and SAY SO in both channels.

    The sentence goes into ``report["provenance"]`` for a client that renders a provenance block AND
    into ``report["problems"]`` for one that renders only the problem list, which is every client
    this repository has today. Silence was the whole defect the provenance field exists to prevent;
    it is not permitted in either direction.
    """
    answer_rows = _uploaded_answer_rows(parsed)
    report["answersSkipped"] = answer_rows
    if not answer_rows:
        return
    reason = _answers_not_imported_reason(answer_rows, _came_out_of_the_platform(parsed))
    report["provenance"] = {
        "action": "answersNotImported",
        "sourceQuestionnaireId": (parsed.questionnaireId or "").strip() or None,
        "answersSkipped": answer_rows,
        "reason": reason,
    }
    report["problems"].append(
        {
            "sheet": parsed.sheet,
            "row": None,
            "severity": "warning",
            "reason": reason,
            "value": None,
        }
    )


def _match_question(
    parsed_question: Any,
    section: Any,
    by_id: dict[str, Any],
    by_prompt: dict[tuple[str, str], Any],
    problems: list[dict[str, Any]],
    parsed: ParsedQuestionnaire,
) -> Any | None:
    """Which stored question this spreadsheet row is, or None if it is new.

    THREE STEPS, IN ORDER. (1) The Question ID column: an id naming a question of THIS questionnaire
    wins outright. (2) Failing that, the exact prompt text within the same section, lowercased and
    stripped, ACTIVE QUESTIONS ONLY — a retired question keeps its original wording for ever, so a
    re-used wording would otherwise be silently matched onto the retired row and reactivate a
    question deliberately replaced. (3) Otherwise the row is a NEW question.
    """
    wanted = (parsed_question.questionId or "").strip()
    if wanted:
        found = by_id.get(wanted)
        if found is not None:
            return found
        # An id from another instrument, or from a question that has since been deleted. Honouring it
        # would graft another questionnaire's question onto this one; refusing the whole upload over
        # one stale cell would be worse. Import the row as new and say so.
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": parsed_question.row,
                "severity": "warning",
                "reason": (
                    f"Question ID '{wanted}' does not belong to this questionnaire, so the row was "
                    "imported as a new question. If you meant to edit an existing question, "
                    "download this questionnaire again and edit that copy."
                ),
                "value": parsed_question.prompt[:120],
            }
        )
        return None
    return by_prompt.get((section.id, parsed_question.prompt.strip().lower()))


async def apply_parsed_edit(
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    *,
    title: str | None = None,
    description: str | None = None,
) -> dict[str, Any]:
    """Re-import a workbook over an existing questionnaire under the edit-after-answers rule.

    ==============================================================================================
    THE EDIT-AFTER-ANSWERS RULE
    ==============================================================================================

    An answer is evidence, and the words it was given under are part of that evidence. A researcher
    sat with an artisan, read question 54 out as it was worded that morning, and wrote down what they
    were told. Rewording question 54 afterwards does not correct the record — it changes what the
    recorded answer appears to be an answer TO, silently, for every interview that already has one.

    So, in six clauses:

    1. A question NOBODY has answered is fully editable and really deletable.
    2. An ANSWERED question may still change its help text, its Required flag and its position. None
       of those is the thing an answer answers.
    3. Rewording an ANSWERED question SUPERSEDES it: the old row is retired with ``retiredAt`` set
       and ``supersededById`` pointing at the replacement, the answers stay on the old wording, and
       the new wording becomes a new question in the same place.
    4. Deleting an ANSWERED question RETIRES it: ``isActive = false``, ``retiredAt`` set.
    5. A section is NEVER deleted by an upload — see ``_remove_absent`` for the reason, which is
       about a table this file never mentions otherwise.
    6. ``Questionnaire.version`` increments on every supersede and every retire, so a client holding
       a cached copy of the form can tell it is stale with an integer compare instead of a diff.

    THE DATABASE ENFORCES CLAUSES 3 AND 4 INDEPENDENTLY OF THIS FUNCTION, AND ALREADY DID.
    ``QuestionnaireResponse.question`` is ON DELETE RESTRICT, so a ``delete`` of an answered question
    raises rather than orphaning an answer. Nothing here may be written on the assumption that the
    guard is the Python; the Python exists so that the refusal is a sentence an admin can act on
    rather than a 500 out of Prisma, and so that it happens before any row is written rather than
    half way through.
    """
    existing = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if existing is None:
        raise QuestionnaireWorkbookError("That questionnaire no longer exists.")
    # READ ONCE, INTO AN INT, AND NEVER READ OFF `existing` AGAIN. The last thing this function does
    # is write `version = version_before + bumps` back to the row; computing `versionAfter` from
    # `existing.version` AFTER that write asks the question of its own answer, and gets
    # `before + bumps + bumps` the moment anything hands this function a live row rather than a
    # snapshot. The report is what the client compares against its cached copy of the form, so an
    # inflated number there makes every client believe it is stale on every upload.
    version_before = existing.version

    sections = await db.questionnairesection.find_many(
        where={"questionnaireId": questionnaire_id},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    # READ BY INSTRUMENT, NOT BY SECTION ID LIST, AND THAT IS A REPO-SPECIFIC CORRECTION.
    #
    # `QuestionnaireQuestion.sectionId` is NULLABLE here and the module this was ported from has it
    # NOT NULL, so the port's `where={"sectionId": {"in": section_ids}}` cannot see a row whose
    # section is NULL: never matched, never retired, never deleted, never reported — a live question
    # nothing in this file would ever mention again. `questionnaireId` is NOT NULL, so reading by
    # THAT sees every question of this instrument, orphans included; they are separated out below and
    # REPORTED rather than acted on.
    #
    # NO PATH CREATES ONE TODAY, and that is worth saying so the next reader does not go looking for
    # the leak. `QuestionnaireQuestion.section` was changed from SetNull to Restrict by the
    # questionnaire-instruments migration precisely because SetNull left questions invisible to every
    # client while their answers sat in the database — so a section delete is now refused rather than
    # orphaning, and neither `update_question` nor `reorder_questions` can write a NULL (the schema
    # has no way to send one and `clean_data` strips it). This read is therefore about rows that
    # PREDATE that change, and about the next change that reintroduces a way to make one.
    all_questions = await db.questionnairequestion.find_many(
        where={"questionnaireId": questionnaire_id},
        # ORDERED, and not for tidiness. `by_prompt` below keeps the FIRST question it sees for a
        # given wording, so an unordered read would resolve a duplicate prompt differently on
        # different runs — the same upload editing a different question depending on what Postgres
        # felt like returning first.
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    section_ids = {s.id for s in sections}
    questions = [q for q in all_questions if q.sectionId in section_ids]
    orphans = [q for q in all_questions if not q.sectionId]

    answered, has_response_row = await _answer_evidence([q.id for q in all_questions])

    report = _empty_workbook_report(version_before)
    report["problems"] = [p.payload() for p in parsed.problems]
    problems: list[dict[str, Any]] = report["problems"]

    for orphan in orphans:
        # REPORTED AND NEVER TOUCHED. A question with no section belongs to no part of the instrument
        # any client can render (`section_payloads` drops it), yet its answers are alive under
        # Restrict. Deciding which section it belongs to is a judgement about the contents of a
        # research instrument, and this import does not make those — it says the row exists so that
        # somebody who can make that judgement knows to.
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": None,
                "severity": "warning",
                "reason": (
                    f"Question {orphan.id} in this questionnaire is not attached to any section, so "
                    "it cannot be in this workbook and was left exactly as it is. It is invisible on "
                    "every screen that reads the questionnaire, and any answers recorded against it "
                    "are still in the database. Move it into a section from the questionnaire "
                    "builder if it should be asked, or ask an admin to remove it if it should not."
                ),
                "value": (orphan.prompt or "")[:120],
            }
        )

    by_id = {q.id: q for q in questions}
    section_by_code = {s.code.strip().lower(): s for s in sections}
    by_prompt: dict[tuple[str, str], Any] = {}
    for question in questions:
        if question.isActive:
            by_prompt.setdefault((question.sectionId, question.prompt.strip().lower()), question)

    version_bumps = 0
    touched_questions: set[str] = set()
    touched_sections: set[str] = set()
    codes_taken = {s.code.strip().lower() for s in sections}
    kept_sections = parsed.sections[:MAX_SECTIONS]
    report["sections"] = len(kept_sections)

    # ── NEGATE EVERY EXISTING SECTION'S sortOrder BEFORE ANY ROW IS RENUMBERED OR CREATED ─────────
    #
    # THIS IS NOT IN THE MODULE THIS WAS PORTED FROM AND IT IS NOT OPTIONAL HERE. The designer's
    # section table carries only an INDEX on (questionnaireId, sortOrder); this repository's
    # QuestionnaireSection carries a UNIQUE on it. So the port's straight "update this section to
    # position N" collides the instant a workbook swaps two sections — and it collides HALF WAY
    # THROUGH, because there is not a single transaction in this backend (`db.tx(` has zero call
    # sites in app/). The questionnaire is then left with some sections renumbered, some not, and an
    # UPLOAD FAILED message, which is the worst of the three possible outcomes: the admin does not
    # know what state their instrument is in.
    #
    # A CREATE COLLIDES TOO, which is why this runs before the loop rather than inside it: a workbook
    # whose first section is new asks for sortOrder 1 while an untouched section still holds it.
    #
    # Negation is injective over a set that is already unique, and no positive row can be in the
    # negative space, so the intermediate state cannot collide with itself. Exactly the two-pass
    # `reorder_sections` above uses, for exactly this constraint.
    #
    # AND THE IN-MEMORY OBJECT IS UPDATED TOO. Without that line, `section.sortOrder` in the loop
    # below is the STALE PRE-NEGATION value, a "did the position change?" guard would skip the update
    # for every section that did not move, and those rows would keep the negative number for ever —
    # sorting in front of everything on every read, and colliding with the NEXT upload's negation
    # pass so the admin sees a failure caused by an upload they made a month earlier. The guard is
    # gone below as well: a touched section always has its sortOrder written.
    for position, section in enumerate(sections, start=1):
        await db.questionnairesection.update(
            where={"id": section.id}, data={"sortOrder": -position}
        )
        section.sortOrder = -position

    for index, parsed_section in enumerate(kept_sections, start=1):
        code = (parsed_section.code or "").strip()
        section = section_by_code.get(code.lower()) if code else None
        if section is None:
            new_code = _unique_code(code, parsed_section.title, codes_taken)
            section = await db.questionnairesection.create(
                data={
                    "questionnaireId": questionnaire_id,
                    "code": new_code,
                    "title": parsed_section.title or new_code,
                    "sortOrder": index,
                }
            )
            section_by_code[new_code.lower()] = section
            report["sectionsCreated"] += 1
        else:
            section = await _apply_workbook_section(section, parsed_section, index)
        touched_sections.add(section.id)

        for position, parsed_question in enumerate(parsed_section.questions, start=1):
            help_text = _clip_help(
                parsed_question.helpText,
                parsed=parsed,
                row=parsed_question.row,
                problems=problems,
            )
            match = _match_question(parsed_question, section, by_id, by_prompt, problems, parsed)
            if match is None:
                await db.questionnairequestion.create(
                    data={
                        **workbook_question_data(section),
                        "prompt": parsed_question.prompt,
                        "helpText": help_text,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                report["created"] += 1
                continue

            touched_questions.add(match.id)
            reworded = match.prompt.strip() != parsed_question.prompt.strip()

            if not match.isActive and match.retiredAt is not None:
                # A RETIRED question, matched by the id in the file. The download deliberately writes
                # retired questions out — dropping them would lose the answers recorded against
                # them — so every download carries these rows and every re-upload matches them. They
                # are therefore LEFT ALONE: reactivating on sight would mean that downloading a
                # questionnaire and uploading it back unchanged resurrects every question anybody has
                # ever replaced, each one next to the replacement that superseded it.
                if reworded:
                    problems.append(
                        {
                            "sheet": parsed.sheet,
                            "row": parsed_question.row,
                            "severity": "warning",
                            "reason": (
                                "This question was retired because it already had answers recorded "
                                "against it, so the new wording on this row was NOT applied. Edit "
                                + (
                                    f"the question that replaced it ({match.supersededById})"
                                    if match.supersededById
                                    else "an active question"
                                )
                                + " instead, or add a new row with a blank Question ID."
                            ),
                            "value": parsed_question.prompt[:120],
                        }
                    )
                report["unchanged"] += 1
                continue

            data: dict[str, Any] = {}
            if not match.isActive:
                # SWITCHED OFF BY A PERSON, NOT RETIRED BY THE RULE — `retiredAt` is NULL, which in
                # this repository means DELETE /questionnaire/questions/{id} or a deleted section
                # flipped `isActive` and nothing else. The port has ONE branch here and treats both
                # the same, so a section a manager switched off comes back on re-upload while every
                # question under it stays off for ever, and a reworded row is answered with the
                # sentence "this question was retired because it already had answers recorded
                # against it" — which is false about a question nobody has answered. Naming it in the
                # workbook is the admin saying it is part of the instrument, so it comes back, and
                # the row is then applied normally by the lines below.
                data["isActive"] = True

            if reworded and match.id in answered:
                # RULE 3. The recorded answers keep the wording they were given under; the new
                # wording becomes a new question in the same place.
                replacement = await db.questionnairequestion.create(
                    data={
                        **workbook_question_data(section),
                        "prompt": parsed_question.prompt,
                        "helpText": help_text,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                await db.questionnairequestion.update(
                    where={"id": match.id},
                    data={
                        "isActive": False,
                        "retiredAt": _now(),
                        "supersededById": replacement.id,
                    },
                )
                touched_questions.add(replacement.id)
                report["superseded"] += 1
                version_bumps += 1
                report["details"].append(
                    {
                        "action": "superseded",
                        "questionId": match.id,
                        "replacementId": replacement.id,
                        "before": match.prompt,
                        "after": parsed_question.prompt,
                        "reason": (
                            "This question already has answers recorded against it in interviews, so "
                            "its original wording and those answers were kept and your new wording "
                            "was added as a new question."
                        ),
                    }
                )
                continue

            if reworded:
                data["prompt"] = parsed_question.prompt  # RULE 1: nobody has answered it
            if match.helpText != help_text:
                data["helpText"] = help_text
            if match.isRequired != parsed_question.isRequired:
                data["isRequired"] = parsed_question.isRequired
            if match.sortOrder != position:
                data["sortOrder"] = position
            if match.sectionId != section.id:
                data.update(workbook_question_data(section))
            if data:
                await db.questionnairequestion.update(where={"id": match.id}, data=data)
                report["updated"] += 1
            else:
                report["unchanged"] += 1

    version_bumps += await _remove_absent(
        questions,
        sections,
        answered,
        has_response_row,
        touched_questions,
        touched_sections,
        len(kept_sections) + 1,
        report,
    )

    _note_skipped_answers(parsed, report)

    updates: dict[str, Any] = {}
    if title and title.strip() and title.strip() != existing.title:
        updates["title"] = title.strip()
    elif parsed.title and parsed.title.strip() != existing.title:
        updates["title"] = parsed.title.strip()
    if description is not None and description != existing.description:
        updates["description"] = description
    elif parsed.description and parsed.description != existing.description:
        updates["description"] = parsed.description
    if version_bumps:
        updates["version"] = version_before + version_bumps
    if updates:
        try:
            await db.questionnaire.update(where={"id": questionnaire_id}, data=updates)
        except UniqueViolationError:
            # @@unique([title]). BY THIS LINE THE STRUCTURE IS ALREADY APPLIED — every section, every
            # question, every supersede — so raising here would answer 500 on an upload that
            # SUCCEEDED, and the admin would re-upload a file that has already been applied. The
            # title is the only field that can collide, so it is dropped, the rest is retried, and
            # the report says the title was left alone. A failure that is loud about the wrong half
            # of the work is worse than one sentence in the problem list.
            rejected = updates.pop("title", None)
            if rejected is None:
                raise
            problems.append(
                {
                    "sheet": parsed.sheet,
                    "row": None,
                    "severity": "warning",
                    "reason": (
                        "Another questionnaire already has the title on this workbook's Details "
                        "sheet, so the title was left as it was. Every question in the file was "
                        "applied as normal."
                    ),
                    "value": str(rejected),
                }
            )
            if updates:
                await db.questionnaire.update(where={"id": questionnaire_id}, data=updates)
    report["versionAfter"] = version_before + version_bumps
    return report


async def _apply_workbook_section(section: Any, parsed_section: Any, index: int) -> Any:
    """Bring one existing section into line with its row in the workbook.

    A SECTION TITLE MAY CHANGE EVEN WHEN ANSWERED, because a heading is not the thing an answer
    answers. Reactivated on sight, so re-uploading a section somebody had switched off brings it
    back rather than leaving a retired duplicate beside it.

    sortOrder IS ALWAYS WRITTEN, with no "did it move?" guard. Every section was negated before this
    loop ran, so a guard reading the pre-negation position would skip the write for exactly the
    sections that did not move and strand them at a negative number for ever. See the block comment
    on the negation pass.
    """
    wanted_title = parsed_section.title or section.title
    # CAPTURED BEFORE THE WRITE. Reading `section.title` after the update to decide whether the
    # update renamed anything is a question asked of the answer: it is true only while the object in
    # hand is a snapshot, and the moment anything hands this function a live row — a cache, a fake, a
    # future Prisma that returns the same instance — the comparison is against the new value and the
    # `update_many` below never runs. That failure is silent and its consequence is last year's
    # heading on every question in the consolidated export.
    renamed = section.title != wanted_title
    updated = await db.questionnairesection.update(
        where={"id": section.id},
        data={"title": wanted_title, "sortOrder": index, "isActive": True},
    )
    if renamed:
        # MIRRORS `update_section` above. The two strings are denormalised onto every question under
        # the section, and a rename that updated only the section row leaves every question carrying
        # last year's heading in the consolidated export, in the dataset CSV and on the browse rows —
        # with nothing wrong in the database to find.
        await db.questionnairequestion.update_many(
            where={"sectionId": section.id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
    return updated


async def _remove_absent(
    questions: list[Any],
    sections: list[Any],
    answered: set[str],
    has_response_row: set[str],
    touched_questions: set[str],
    touched_sections: set[str],
    next_section_order: int,
    report: dict[str, Any],
) -> int:
    """CLAUSES 4 AND 5: what happens to rows the admin took out of the spreadsheet.

    ==============================================================================================
    NO SECTION IS EVER DELETED HERE, AND THAT IS THE MOST IMPORTANT LINE IN THIS FUNCTION
    ==============================================================================================

    The module this was ported from deletes any section whose questions were never answered, on the
    argument that there is nothing to orphan. In THIS repository that statement is false about a
    table the port has no equivalent of. ``QuestionnaireSectionStatus.section`` is ON DELETE CASCADE
    and those rows are ADMIN COMPLETION VERDICTS — one explicit human judgement per (artisan,
    section) on the Check completion matrix, written one `upsert` at a time by
    `set_completion_cell`. The ``answered`` set is built from `QuestionnaireResponse` and knows
    nothing about them, so a section can carry dozens of COMPLETED / NEEDS_REDO verdicts and zero
    text answers — the port's "safe to delete, nothing was ever answered" test says yes, and every
    verdict for that section is gone, permanently, with nothing on any screen saying so. No fake in
    the test suite can catch that; only Postgres can, and only after the fact.

    Two more things hang off the same delete and neither is recoverable either.
    ``QuestionnaireQuestion.section`` is Restrict, so the delete would in fact RAISE on any section
    that still had a question — mid-loop, in a backend with no transaction. And
    ``AssignedTask.sectionIds`` is a bare `String[]` with no foreign key at all, so live tasks would
    quietly lose a section while their progress denominator kept counting it.

    So an absent section is DEACTIVATED, always, and the report says how many verdicts and how many
    assigned tasks still point at it. Rewriting somebody's assigned work from inside a spreadsheet
    import is a decision this module has no standing to make, so it reports and stops.

    AND EVERY ABSENT SECTION IS GIVEN A POSITIVE sortOrder. Each was negated before the main loop and
    only the ones the workbook named were renumbered back; a section left negative survives this
    upload and collides with the NEXT one's negation pass, at which point an admin sees a failure
    caused by an upload made a month ago on a questionnaire they have not touched since.

    A QUESTION, BY CONTRAST, IS REALLY DELETED WHEN NOTHING POINTS AT IT. Answered -> retired, and
    its answers stay. Carrying a response row but no content -> deactivated, because
    `QuestionnaireResponse.question` is Restrict and the delete would raise. Nothing at all -> gone,
    because a form littered with every question its author thought better of is not a form.
    """
    bumps = 0
    for question in questions:
        if question.id in touched_questions:
            continue
        if question.id in answered:
            if question.isActive:
                await db.questionnairequestion.update(
                    where={"id": question.id},
                    data={"isActive": False, "retiredAt": _now()},
                )
                report["retired"] += 1
                bumps += 1
                report["details"].append(
                    {
                        "action": "retired",
                        "questionId": question.id,
                        "before": question.prompt,
                        "reason": (
                            "This question was removed from your spreadsheet but answers have "
                            "already been recorded against it in interviews, so it was retired "
                            "rather than deleted. It is no longer asked, and its answers are still "
                            "in the record."
                        ),
                    }
                )
            continue
        if question.id in has_response_row:
            # A BLANK INTERVIEW ROW IS STILL A FOREIGN KEY. Somebody opened an interview covering
            # this question, tabbed through it and saved, so an empty `QuestionnaireResponse` exists
            # and ON DELETE RESTRICT refuses the delete. Deactivating is the honest answer: the row
            # carries nothing, so nothing is being preserved for its content, but deleting the
            # interview rows to clear the way is a write into somebody's fieldwork that a
            # spreadsheet import has no business making.
            if question.isActive:
                await db.questionnairequestion.update(
                    where={"id": question.id}, data={"isActive": False}
                )
                report["removed"] += 1
            continue
        await db.questionnairequestion.delete(where={"id": question.id})
        report["removed"] += 1

    order = next_section_order
    for section in sections:
        if section.id in touched_sections:
            continue
        verdicts = await db.questionnairesectionstatus.count(where={"sectionId": section.id})
        tasks = await db.assignedtask.count(where={"sectionIds": {"has": section.id}})
        await db.questionnairesection.update(
            where={"id": section.id},
            data={"isActive": False, "sortOrder": order},
        )
        order += 1
        report["sectionsRetired"] += 1
        report["assignedTasksAffected"] += tasks
        report["details"].append(
            {
                "action": "sectionRetired",
                "questionId": section.id,
                "before": f"{section.code} — {section.title}",
                "reason": (
                    f"Section {section.code} was not in your spreadsheet, so it was switched off "
                    "rather than deleted: "
                    f"{verdicts} completion verdict(s) and {tasks} assigned task(s) still point at "
                    "it. It is no longer asked. Nothing was removed from any task — check the "
                    "assignments if that section was somebody's work."
                ),
            }
        )
    return bumps


# --- Reading the instrument back out as a workbook ------------------------------------------------


async def _workbook_sections(questionnaire_id: str, *, active_only: bool) -> list[dict[str, Any]]:
    """Sections with their questions nested, in the shape the three writers take.

    TWO FLAT QUERIES IN ONE GATHERED WAVE, never a nested Prisma include: Prisma issues an include as
    its own sequential round trip per level, and on this deployment each of those is a cross-region
    hop. A twenty-two-section instrument would otherwise cost twenty-two.

    RE-BUCKETED BY sectionId BEFORE EMITTING. `QuestionnaireQuestion.sortOrder` is scoped to its
    section, so a flat cross-section read comes back interleaved (A1, B1, A2, B2) and a writer that
    trusted the read order would emit an interleaved instrument.

    NO ANSWER QUERY IS ISSUED AT ALL, on either path. A filter is a thing somebody can forget; a
    query that was never written is not.
    """
    section_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    question_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    if active_only:
        section_where["isActive"] = True
        question_where["isActive"] = True
    sections, questions = await gather_reads(
        db.questionnairesection.find_many(
            where=section_where, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]
        ),
        db.questionnairequestion.find_many(
            where=question_where, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]
        ),
    )
    by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId:
            by_section.setdefault(question.sectionId, []).append(question)
    return [
        {
            "code": section.code,
            "title": section.title,
            "questions": [
                {
                    "id": question.id,
                    "prompt": question.prompt,
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    # EMPTY, AND WRITTEN OUT RATHER THAN OMITTED. `_write_questions_sheet` reads
                    # `question.get("answers") or {}`, so omitting these would work — and would leave
                    # the next reader unable to tell "there are none" from "somebody forgot". This
                    # repository never exports an answer.
                    "answers": {},
                    "answerNotes": {},
                }
                for question in by_section.get(section.id, [])
            ],
        }
        for section in sections
    ]


async def export_instrument_payload(questionnaire_id: str) -> dict[str, Any] | None:
    """Exactly ``build_questionnaire_workbook``'s kwargs for one questionnaire, or None if it is gone.

    RETIRED SECTIONS AND QUESTIONS ARE INCLUDED. They have to be: everything absent from an upload is
    removed by rule, so a download that dropped them would retire-then-delete them on the next round
    trip and take the interview answers hanging off them with it — except that it could not, because
    `QuestionnaireResponse.question` is ON DELETE RESTRICT, so the upload would instead fail
    half-applied with a foreign key error. Either outcome is a bug; the download carrying them is the
    fix, and the instructions sheet says they will be there.
    """
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        return None
    return {
        "title": row.title,
        "description": row.description,
        "questionnaire_id": row.id,
        "version": row.version,
        "sections": await _workbook_sections(questionnaire_id, active_only=False),
        # THE KWARG THE WHOLE ANSWER-CONTAINMENT STORY RESTS ON. See
        # `build_questionnaire_workbook`'s docstring: empty means one blank Answer/Notes pair and no
        # value in it. It is written out here rather than left to the default so that the decision is
        # visible at the call site, where a reviewer diffing this against the port will look.
        "entry_labels": [],
    }


async def export_question_set_payload(questionnaire_id: str) -> dict[str, Any] | None:
    """The same questions with the ids blanked — a NEW instrument, not an edit of this one."""
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        return None
    sections = await _workbook_sections(questionnaire_id, active_only=True)
    for section in sections:
        for question in section["questions"]:
            # BLANKED HERE, VISIBLY, NEXT TO THE EMPTY ANSWERS, rather than by a flag inside the
            # writer. This file is uploaded to create a NEW questionnaire; an id left in would be an
            # id belonging to THIS one, and the receiving upload would report every row as "does not
            # belong to this questionnaire".
            question["id"] = ""
    return {
        "title": row.title,
        "description": row.description,
        "sections": sections,
        "source_title": row.title,
        "exported_on": datetime.now(UTC).date().isoformat(),
    }


async def load_workbook_form(
    questionnaire_id: str, *, include_retired: bool = False
) -> dict[str, Any] | None:
    """One instrument and its structure, for the panel drawn after an upload."""
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        return None
    payload = _instrument_payload(row, {})
    payload["version"] = row.version
    payload["sourceFilename"] = row.sourceFilename
    payload["sections"] = await section_payloads(
        questionnaire_id, active_only=not include_retired
    )
    payload["sectionCount"] = len(payload["sections"])
    payload["questionCount"] = sum(len(s["questions"]) for s in payload["sections"])
    return payload


# --- The routes -----------------------------------------------------------------------------------


async def _read_workbook_upload(file: UploadFile, request: Request | None = None) -> bytes:
    """The uploaded bytes, or a 4xx an admin can act on rather than a 500 out of openpyxl.

    THREE REFUSALS, CHEAPEST FIRST. The filename suffix is a string on the multipart part, so a .docx
    is turned away without the body being touched. The declared ``Content-Length`` costs a header
    lookup. Only then is the body read, and ``read_upload_bounded`` counts it as it arrives so a
    request that UNDERSTATES its length is stopped mid-read rather than after it.

    NO CONTENT-TYPE CHECK, DELIBERATELY, and this is not an omission. Browsers and Android disagree
    about the media type of an .xlsx (``application/octet-stream`` is routine from a file picker),
    and refusing on it would turn away correct files. The real check is the FILE'S OWN MAGIC BYTES,
    made inside ``parse_questionnaire_workbook``, which answers a different sentence for an old .xls,
    for a password-protected workbook and for a PDF somebody renamed.
    """
    name = (file.filename or "").lower()
    if name and not name.endswith(_XLSX_SUFFIXES):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{file.filename}' is not an Excel workbook. Fill in the .xlsx pro-forma and "
                "upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
            ),
        )
    # NO `remedy=`, DELIBERATELY. `read_upload_bounded` appends a second sentence for sites where
    # "send a smaller file" is not the useful instruction — photograph the grid sheet alone, put a
    # long recording in workshop audio. There is no such second action here: a questionnaire that
    # will not fit in 8 MB is not a questionnaire, and the only honest advice is the sentence the
    # helper already gives. A remedy invented to fill the slot would be noise on a refusal that is
    # already complete.
    content = await read_upload_bounded(
        file,
        WORKBOOK_MAX_UPLOAD_BYTES,
        request=request,
        purpose="questionnaire workbook",
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The upload was empty. Attach the filled-in pro-forma.",
        )
    return content


def _xlsx_response(payload: bytes, filename: str) -> Response:
    return Response(
        content=payload,
        media_type=XLSX_MIME,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _parse_or_422(content: bytes, filename: str | None) -> ParsedQuestionnaire:
    try:
        return parse_questionnaire_workbook(content, filename=filename)
    except QuestionnaireXlsxError as exc:
        # The message is written to be shown to the admin as-is — it names the remedy for an old
        # .xls, a password, a truncated upload or a file that is not a workbook at all.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc


@instruments_router.get("/pro-forma")
async def download_pro_forma(_: Any = Depends(require_admin)) -> Response:
    """The blank .xlsx the questionnaire is built in.

    DECLARED ABOVE ``GET /{questionnaire_id}`` IN THIS FILE, AND THAT ORDER IS LOAD-BEARING. A path
    parameter's ``[^/]+`` matches the literal string "pro-forma" perfectly happily, and FastAPI
    resolves routes in declaration order. Declared below the id route, this download answers
    404 "Record not found" — which reads as a broken database rather than as a routing problem, and
    would send somebody looking in Prisma. `test_the_pro_forma_path_is_not_swallowed_by_an_id_route`
    pins it, and anything later that adds a route at this prefix goes BELOW this line.

    Generated on every request rather than cached: it is a few kilobytes of openpyxl, and a stale
    cached copy whose headings no longer match the parser is an admin typing eighty-one questions
    into columns the app no longer recognises.
    """
    return _xlsx_response(build_pro_forma(), PRO_FORMA_FILENAME)


@instruments_router.get("/{questionnaire_id}/xlsx")
async def download_questionnaire_workbook(
    questionnaire_id: str, _: Any = Depends(require_admin)
) -> Response:
    """This questionnaire as the same workbook, with its Question IDs filled in.

    THE IDS ARE THE POINT. They are what make re-uploading this file an EDIT of these questions
    rather than a second copy of them, which is why the Question ID column is greyed on the sheet and
    the instructions say not to touch it. Without them a re-upload falls back to matching on the
    exact prompt text, and an admin who reworded question 54 AND moved it would get a new question
    plus a retirement rather than an edit.

    IT CARRIES NO ANSWER. See `export_instrument_payload`'s `entry_labels: []`.
    """
    payload = await export_instrument_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return _xlsx_response(
        build_questionnaire_workbook(**payload), download_filename(payload["title"])
    )


@instruments_router.get("/{questionnaire_id}/question-set.xlsx")
async def download_question_set(
    questionnaire_id: str, _: Any = Depends(require_admin)
) -> Response:
    """The same questions with the Question IDs blank — a NEW instrument, not an edit of this one.

    WHY THIS IS A SECOND ROUTE AND NOT A QUERY PARAMETER ON ``/xlsx``. Because the two files differ
    in the one respect that decides whether an upload edits the live instrument forty researchers are
    answering or creates a new one beside it, and ``?questionsOnly=true`` puts that decision inside a
    boolean that defaults. Two paths, two functions, no shared default.

    The difference is not a filter that could be forgotten either: `export_question_set_payload`
    blanks the id at the point the payload is built, visibly, and drops retired questions — a
    question the instrument deliberately replaced must not be planted into next year's copy next to
    its replacement.
    """
    payload = await export_question_set_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return _xlsx_response(
        build_question_set_workbook(**payload), question_set_filename(payload["title"])
    )


@instruments_router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_questionnaire(
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    description: str | None = Form(default=None),
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Create a questionnaire from a filled-in pro-forma.

    ``title`` AND ``description`` ARE FORM FIELDS ON THE SAME MULTIPART BODY AS THE FILE, NOT QUERY
    PARAMETERS. Left as bare ``str | None = None`` defaults FastAPI reads them as query parameters,
    and a client that posts them in the body has them silently ignored — an untitled questionnaire,
    with a 201 saying it went fine. ``Form(default=None)`` is what makes them body fields; there is
    no pydantic model on a multipart route to carry the declaration instead.

    ``request`` IS NOT A PARAMETER ANY CLIENT SENDS. FastAPI fills it from the connection, so it
    changes nothing about this route's contract. It is here only so `_read_workbook_upload` can read
    the declared ``Content-Length`` and refuse an oversized workbook before copying it into the heap.

    THE RESPONSE CARRIES ``report.problems``, AND THAT LIST IS THE FEATURE. An admin who uploads
    eighty-one questions and is shown seventy-nine, with no way to find out which two are missing or
    why, does not trust the import again — and every likely cause (a merged cell, a formula Excel
    never calculated, "maybe" typed in the Required column) is invisible from the result.
    """
    content = await _read_workbook_upload(file, request)
    parsed = _parse_or_422(content, file.filename)
    try:
        questionnaire_id, report = await create_from_parsed(
            parsed,
            title=title,
            description=description,
            source_filename=file.filename,
            created_by_id=get_value(current_user, "id"),
        )
    except QuestionnaireWorkbookError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    form = await load_workbook_form(questionnaire_id)
    return public_encode({"questionnaire": form, "report": report})


@instruments_router.post("/{questionnaire_id}/upload")
async def reupload_questionnaire(
    questionnaire_id: str,
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Re-upload an edited workbook over an existing questionnaire.

    Runs the edit-after-answers rule. ``report.details`` names every question that was superseded or
    retired and says why in a sentence meant to be shown verbatim — an admin whose six reworded
    questions came back as six NEW questions has to be told that happened and that the recorded
    answers are safe, and a question count cannot say it.
    """
    record = await require_questionnaire(questionnaire_id)
    content = await _read_workbook_upload(file, request)
    parsed = _parse_or_422(content, file.filename)

    # A Questionnaire ID on the Details sheet naming a DIFFERENT questionnaire means the admin picked
    # the wrong file out of their downloads folder. Applying it would switch off this questionnaire's
    # ENTIRE question set as "absent from the upload" in one press — every one of them, because none
    # of the ids in the file belongs here.
    if parsed.questionnaireId and parsed.questionnaireId != questionnaire_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That workbook was downloaded from a different questionnaire "
                f"(its Details sheet says {parsed.questionnaireId}). Download this questionnaire "
                "again and edit that copy, or upload the file as a new questionnaire."
            ),
        )
    try:
        report = await apply_parsed_edit(questionnaire_id, parsed, title=title)
    except QuestionnaireWorkbookError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc
    if file.filename and file.filename != getattr(record, "sourceFilename", None):
        await db.questionnaire.update(
            where={"id": questionnaire_id}, data={"sourceFilename": file.filename}
        )
    # RETIRED ROWS INCLUDED, unlike the create path, which has none. This is the one response whose
    # report can say "superseded 1, retired 1", and a form that then omitted both would leave the
    # client unable to show the admin the questions it has just told them about.
    form = await load_workbook_form(questionnaire_id, include_retired=True)
    return public_encode({"questionnaire": form, "report": report})


@instruments_router.get("/{questionnaire_id}")
async def get_questionnaire(
    questionnaire_id: str,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """One instrument and its whole structure, active sections included, for the builder."""
    row = await require_questionnaire(questionnaire_id)
    payload = _instrument_payload(row, {})
    payload["sections"] = await section_payloads(questionnaire_id, active_only=False)
    payload["sectionCount"] = len(payload["sections"])
    payload["questionCount"] = sum(len(s["questions"]) for s in payload["sections"])
    return payload


@instruments_router.post("", status_code=status.HTTP_201_CREATED)
async def create_questionnaire(
    payload: QuestionnaireCreate,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """Create an empty instrument. `require_questionnaire_manager`, the SAME tier as creating a
    section: an empty container is the same class of act as the first thing you put in it."""
    sort_order = payload.sortOrder
    if sort_order is None:
        rows = await db.questionnaire.find_many(order={"sortOrder": "desc"}, take=1)
        sort_order = (rows[0].sortOrder if rows else 0) + 1
    try:
        created = await db.questionnaire.create(
            data={
                "title": payload.title.strip(),
                "description": payload.description,
                "isActive": payload.isActive,
                "sortOrder": sort_order,
                "createdById": get_value(current_user, "id"),
                # `isDefault` is NOT settable here and is not in the schema either. A new instrument
                # is never the default; making one is a separate admin act on a separate route.
            }
        )
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A questionnaire titled “{payload.title.strip()}” already exists.",
        ) from exc
    return _instrument_payload(created, {})


@instruments_router.patch("/{questionnaire_id}")
async def update_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """Rename, re-describe, reorder or RETIRE an instrument.

    Retiring is the two-check route. An instrument that is the DEFAULT cannot be retired, because
    the default is where every client that names no instrument lands and retiring it would drop
    them onto the sortOrder fallback with nobody having decided that. An instrument BOUND TO A
    WORKSHOP cannot be retired either, because that workshop's capture form would keep opening on a
    retired instrument with nothing on screen saying so. Both are refused with the fix named.
    """
    row = await require_questionnaire(questionnaire_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "title" in data:
        data["title"] = data["title"].strip()
    if data.get("isActive") is False:
        if row.isDefault:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "This is the default questionnaire and cannot be retired. Point the default at "
                    "another questionnaire first."
                ),
            )
        bound = await db.workshop.find_many(where={"questionnaireId": questionnaire_id})
        if bound:
            titles = sorted(get_value(w, "title") or w.id for w in bound)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"{len(titles)} workshop(s) still use this questionnaire and it cannot be "
                    f"retired: {titles}. Rebind or detach them first."
                ),
            )
    if not data:
        return _instrument_payload(row, {})
    try:
        updated = await db.questionnaire.update(where={"id": questionnaire_id}, data=data)
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A questionnaire with that title already exists.",
        ) from exc
    return _instrument_payload(updated, {})


@instruments_router.put("/{questionnaire_id}/default")
async def set_default_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireDefaultUpdate,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin/master-admin only: which instrument an unqualified request belongs to.

    NARROWER THAN THE BUILDER ON PURPOSE, AND THE BUILDER MUST NOT FOLLOW IT DOWN. Every structure
    route on /questionnaire stays at `require_questionnaire_manager` — Professor and above, or the
    `canManageQuestionnaire` grant. This one is `require_admin` because it is not an edit to a form,
    it is a decision about where EVERY client that names no instrument lands, including Android
    builds that predate the field and will keep sending nothing for months, and including offline
    payloads queued before those builds that replay at whatever time the handset next finds a
    network. Do not flip this until both have gone.

    Clearing it (`isDefault: false`) is allowed and leaves the resolver falling back to the lowest
    active `sortOrder` (services/questionnaire_instruments.default_questionnaire_id), so the app
    keeps serving rather than 503ing. It is a fallback, not a second default.
    """
    row = await require_questionnaire(questionnaire_id)
    if payload.isDefault and not row.isActive:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A retired questionnaire cannot be the default. Reactivate it first.",
        )
    # Cleared FIRST and unconditionally, because the partial unique index
    # ("Questionnaire_isDefault_key" … WHERE "isDefault") will refuse the second true. There is no
    # transaction idiom in this backend, so for the width of one statement no default exists; the
    # resolver's sortOrder fallback covers that window rather than 503ing a researcher mid-save.
    await db.questionnaire.update_many(where={"isDefault": True}, data={"isDefault": False})
    updated = await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"isDefault": bool(payload.isDefault)}
    )
    return _instrument_payload(updated, {})


# Mounted LAST, after every decorator above has registered, because `include_router` copies the
# routes it finds at the moment it is called rather than holding a live reference.
router.include_router(questionnaire_router)
router.include_router(instruments_router)
