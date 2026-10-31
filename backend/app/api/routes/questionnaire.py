import csv
import re
from datetime import UTC, datetime, timedelta
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
from fastapi.encoders import jsonable_encoder
from fastapi.responses import Response
from prisma import Json
from prisma.errors import UniqueViolationError

# csv_response is SHARED with the export router rather than restated: both hand a researcher a .csv
# over the same Content-Disposition and charset, and a download that saves differently depending on
# which endpoint produced it is exactly the drift that helper exists to prevent. (export.py imports
# from data_browser.py for the same reason; nothing in that chain imports this module back.)
from app.api.routes.export import csv_response
from app.core.db import db
from app.services.artisan_merge import redirect_merged_artisans
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_create_records,
    assert_can_delete,
    can_access_review,
    can_create_records,
    can_manage_questionnaire,
    get_current_user,
    get_value,
    is_admin,
    is_content_admin,
    is_empty_value,
    require_admin,
    require_content_admin,
    require_questionnaire_manager,
)
from app.services.access import (
    guard_record_edit,
    owner_download_scope,
    record_edit_privilege,
)
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
    QuestionnaireResponseInput,
    QuestionnaireReuse,
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
from app.services import answer_drafts, cross_portal_codes, questionnaire_library
from app.services import completeness as completeness_module
from app.questionnaire import authoring as questionnaire_authoring
from app.questionnaire import kinds as questionnaire_kinds
from app.questionnaire import shims_fr as v2_shims
from app.questionnaire import sessions as v2_sessions
from app.questionnaire.common import fail as v2_fail
from app.questionnaire.locks import lock_key
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
from app.services.standard_values import StandardValueError, apply_interview_values
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
from app.services import naming

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
    # Live files only (audit M19): a soft-removed copy is not one of the sitting's recordings.
    Relation("media", "mediafile", "questionnaireInterviewId", many=True, where={"removedAt": None}),
)


_DUPLICATE_SET_DETAIL = (
    "An interview already exists for this exact set of artisans. There is a single shared entry per "
    "artisan set — open it to add or view answers instead of creating another."
)

#: The stable discriminator on that 409's body. A client branches on THIS and never on the prose
#: above: the sentence is written for a human, has been reworded before, and any client that greps
#: it for "already exists" silently stops offering the move the next time somebody improves it.
_DUPLICATE_SET_CODE = "artisan_set_taken"


async def duplicate_set_conflict(interview_id: str, set_key: str | None) -> HTTPException:
    """The 409 for "this artisan set is already taken" — NAMING THE INTERVIEW THAT HOLDS IT.

    THE PROSE ALONE WAS NOT ENOUGH TO ACT ON, and that is the whole reason this exists. Two
    researchers recorded one artisan set as two sittings titled by the sections they covered
    ("D Black Pottery" and an "F" one — see :func:`section_codes_from_title`, which documents that
    practice); the F sitting had missed an artisan. Adding that artisan makes F's set key equal D's,
    and the refusal a client could read said only that *an* interview exists. It could not say
    WHICH, so it could not offer "D Black Pottery already covers this set — move this interview's
    sections and recordings into it?", and the researcher had no way forward at all.

    ``holder`` is ``{"id", "title"}`` under exactly those keys, or ``None`` when the holder cannot
    be identified (a concurrent delete, or a NULL set key — those are not deduped). ``None`` is the
    honest answer and is distinguishable from a holder that is merely untitled; a client that gets
    it falls back to printing ``message``, which is what every client does today.

    TWO READS, ON THE FAILURE PATH ONLY, AND THEY ARE SAFE HERE FOR A REASON THAT IS NOT PORTABLE.
    A unique violation aborts the enclosing transaction in Postgres, so issuing another statement
    after one is a second error rather than an answer — the sibling designer-portal's copy of this
    refusal runs inside ``db.tx()`` and must build its body from what the caller already holds. This
    backend has no transaction idiom at all (zero ``db.tx()`` call sites, see
    ``services/questionnaire_seeding``), so the violation kills one statement and nothing else, and
    these two reads answer normally. Anyone porting this into a transaction must move the lookup.
    """
    holder: dict[str, Any] | None = None
    if set_key:
        interview = await db.questionnaireinterview.find_unique(where={"id": interview_id})
        # SCOPED BY INSTRUMENT, because the index is. `@@unique([questionnaireId, artisanSetKey])`
        # (schema.prisma:1461) is what was violated, so the row that holds the set is the one on
        # THIS sitting's instrument — an unscoped lookup would name the other instrument's sitting,
        # and a client would then offer to move an interview into a questionnaire it was never
        # asked against. See migration 20260913100000 for why the scope moved.
        questionnaire_id = get_value(interview, "questionnaireId") if interview else None
        if questionnaire_id:
            row = await db.questionnaireinterview.find_first(
                where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
            )
            if row is not None and get_value(row, "id") != interview_id:
                holder = {"id": get_value(row, "id"), "title": get_value(row, "title")}
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        # A DICT, KEEPING THE SENTENCE, IN THE SHAPE THIS REPOSITORY ALREADY USES FOR EXACTLY THIS
        # — `routes/artisans._identity_conflict`, which is the same problem one table over: a unique
        # index refused the save, and the refusal carries the record that holds the value so the
        # form can offer to open it instead. Same three parts, same order: a snake_case `code`, the
        # human `message`, and the named record. Copying that shape rather than inventing a second
        # one is what makes the web client work UNCHANGED: `frontend/lib/api.describeApiDetail`
        # already unpacks `{message: …}` out of an object detail (it was written for the artisan
        # conflict above), so the prose keeps rendering on every screen and only the new `holder`
        # half needs client code. Nothing in this repository or either client read the old bare
        # string (grepped 2026-09-20).
        #
        # The SENTENCE IS UNCHANGED even when the holder is known — deliberately, where the artisan
        # conflict rewrites its message to name the artisan. The naming sentence the researcher sees
        # ("D Black Pottery already covers this set. Move this interview's sections and recordings
        # into it?") is an OFFER with a button on it, and it belongs to the client that owns the
        # button; the server's job is to hand over the title it needs to write it.
        detail={
            "code": _DUPLICATE_SET_CODE,
            "message": _DUPLICATE_SET_DETAIL,
            "holder": holder,
        },
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


#: The 403 for a delete from a form by somebody who is neither its author nor a content admin.
FORM_DELETE_REFUSAL = "Only the questionnaire's author or an admin can delete from it."
#: The 403 for an edit of a form by somebody who is neither its author nor a content admin.
FORM_EDIT_REFUSAL = "Only the questionnaire's author or an admin can edit it."


def is_form_author(form: Any, user: Any) -> bool:
    """Whether ``user`` created this questionnaire (``Questionnaire.createdById``).

    A form with no recorded author (the seeded default, or one made before the column existed) has
    no author, so only a content admin deletes from it.
    """
    # The one rule, shared with designer-portal (app/questionnaire/authoring.py).
    return questionnaire_authoring.is_form_author(form, user)


async def assert_may_delete_from_form(questionnaire_id: str | None, user: Any) -> None:
    """THE FORM'S DELETE RULE (owner, 21 Oct 2026, amended 22 Oct 2026): "the author should be able to
    edit/delete the questionnaire apart from the admins".

    A questionnaire and everything in it — retiring or deleting the form, deleting a section or a
    question, switching one off through its PATCH — is its author's or a content admin's
    (``deps.CONTENT_ADMIN_ROLES``). Everyone else is refused 403, questionnaire managers included.
    Deleting sessions, interviews and recordings stays a content admin's alone (``assert_can_delete``),
    and so do sharing, the default form and workshop binding.

    A content admin is answered from memory, so their delete pays no extra query.
    """
    if not await may_change_form(questionnaire_id, user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_DELETE_REFUSAL)


async def may_change_form(questionnaire_id: str | None, user: Any) -> bool:
    """The one rule behind editing and deleting a form: its author or a content admin."""
    if is_content_admin(user):
        return True
    form = await db.questionnaire.find_unique(where={"id": questionnaire_id}) if questionnaire_id else None
    return questionnaire_authoring.may_change_form(user, form)


async def assert_may_edit_form(questionnaire_id: str | None, user: Any) -> None:
    """THE FORM'S EDIT RULE (owner, 22 Oct 2026, applied strictly): editing a questionnaire (its
    title and description, adding, editing and reordering its sections and questions, re-uploading
    its workbook) is its author's (``Questionnaire.createdById``) or a content admin's (Admin, Master
    Admin, Ministry Admin). Every other questionnaire manager is refused 403. A form with no recorded
    author is a content admin's alone. Creating a NEW form is unchanged (a questionnaire manager, or
    an admin from a workbook), and whoever creates it becomes its author."""
    if not await may_change_form(questionnaire_id, user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_EDIT_REFUSAL)


async def require_form_editor(questionnaire_id: str, current_user: Any = Depends(get_current_user)) -> Any:
    """``Depends`` form of :func:`assert_may_edit_form` for routes with the form's id in the path, so
    the refusal comes before the body (a workbook) is read."""
    await assert_may_edit_form(questionnaire_id, current_user)
    return current_user


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


class _SetHeld(Exception):
    """Another interview on the same instrument already holds this artisan set (legacy rule)."""


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
        # With v2 on, a set may sit many times (a session is a dated event), so no guard.
        if set_key and not v2_shims.enabled():
            # The guard the dropped unique used to give (M1): another interview on this instrument
            # already holding the set refuses the edit.
            row = await db.questionnaireinterview.find_unique(where={"id": interview_id})
            holders = await db.questionnaireinterview.find_many(
                where={"questionnaireId": row.questionnaireId if row else None, "artisanSetKey": set_key, "mergedIntoId": None}
            )
            if any(h.id != interview_id for h in holders):
                raise _SetHeld()
        await db.questionnaireinterview.update(where={"id": interview_id}, data={"artisanSetKey": set_key})
    except (UniqueViolationError, _SetHeld) as exc:
        # NAMES THE HOLDER. The refusal stands — this edit still does not happen — but the body now
        # carries what a client needs to offer the move instead of only printing prose.
        raise (await duplicate_set_conflict(interview_id, set_key)) from exc
    await db.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    if unique_ids:
        await db.questionnaireinterviewartisan.create_many(
            data=[{"interviewId": interview_id, "artisanId": aid} for aid in unique_ids]
        )


def source_of(response: Any) -> str | None:
    """The ``answerSource`` a response input carries (A7), or None for a typed answer."""
    return getattr(response, "answerSource", None) or None


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
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
                    "answerSource": source_of(response),
                    "answeredById": current_user.id,
                }
            )
            continue
        if (
            existing.answerText
            and existing.answeredById != current_user.id
            and not is_content_admin(current_user)
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
                    # A changed answer says where its NEW words came from; a typed change is NULL.
                    # A notes-only change keeps the stored mark: the words did not change.
                    "answerSource": (
                        source_of(response)
                        if existing.answerText != response.answerText
                        else get_value(existing, "answerSource")
                    ),
                    "answeredById": current_user.id,
                    # C28: a person's edit of a machine-suggested answer keeps that provenance.
                    "answerOrigin": answer_drafts.origin_after_edit(
                        getattr(existing, "answerOrigin", None),
                        existing.answerText,
                        response.answerText,
                    ),
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
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Add a section to ONE instrument: its author or a content admin (owner, 22 Oct 2026)."""
    # `payload.questionnaireId` is optional (see the schema): an un-updated builder sends nothing
    # and lands on the default, exactly as it did before this change existed.
    questionnaire_id = await resolve_questionnaire_id(payload.questionnaireId)
    await assert_may_edit_form(questionnaire_id, user)
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
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    section = await require_section(section_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if data.get("isActive") is False and section.isActive:
        # Switching a section off is taking it out of the form: the delete rule, not the edit rule.
        await assert_may_delete_from_form(section.questionnaireId, user)
    else:
        await assert_may_edit_form(section.questionnaireId, user)
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
        await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=updated.questionnaireId)
    if data.get("isActive") is True:
        # Restoring a section restores the questions its delete switched off, and only those (audit
        # M16): a question deleted on its own before stays deleted.
        switched = await _questions_switched_off_with(section_id)
        if switched:
            await db.questionnairequestion.update_many(where={"id": {"in": switched}}, data={"isActive": True})
    return public_encode(updated)


SECTION_DELETE_REVISION = "questionnaire_section"


async def _questions_switched_off_with(section_id: str) -> list[str]:
    """The questions the most recent delete of this section switched off (its RecordRevision)."""
    rows = await db.query_raw(
        'SELECT "changes" FROM "RecordRevision" WHERE "recordType" = $1 AND "recordId" = $2 ORDER BY "createdAt" DESC LIMIT 1',
        SECTION_DELETE_REVISION, section_id,
    )
    changes = rows[0].get("changes") if rows else None
    if isinstance(changes, str):
        import json

        changes = json.loads(changes)
    return [str(i) for i in ((changes or {}).get("questionsSwitchedOff") or [])]


@questionnaire_router.delete("/sections/{section_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_section(
    section_id: str,
    user: Any = Depends(get_current_user),
) -> None:
    """Switch a section and its questions off. The form's author or a content admin (22 Oct 2026)."""
    section = await require_section(section_id)
    await assert_may_delete_from_form(section.questionnaireId, user)
    live = await db.questionnairequestion.find_many(where={"sectionId": section_id, "isActive": True})
    await db.questionnairesection.update(where={"id": section_id}, data={"isActive": False})
    await db.questionnairequestion.update_many(where={"sectionId": section_id}, data={"isActive": False})
    # Which questions THIS delete switched off, so a restore brings back exactly those (audit M16).
    await db.recordrevision.create(
        data={"recordType": SECTION_DELETE_REVISION, "recordId": section_id, "editedById": getattr(user, "id", None),
              "changes": Json({"questionsSwitchedOff": [q.id for q in live]})}
    )


@questionnaire_router.post("/sections/reorder")
async def reorder_sections(
    payload: QuestionnaireSectionReorder,
    user: Any = Depends(get_current_user),
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
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"sectionIds must all belong to one questionnaire; these span "
                f"{len(instrument_ids)}."
            ),
        )
    questionnaire_id = next(iter(instrument_ids))
    await assert_may_edit_form(questionnaire_id, user)
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
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    section = await require_section(payload.sectionId)
    await assert_may_edit_form(section.questionnaireId, user)
    sort_order = payload.sortOrder or await next_question_sort_order(section.id)
    created = await db.questionnairequestion.create(
        data={
            # The question inherits its instrument FROM THE SECTION and from nothing else — there
            # is no client-supplied questionnaireId on this route, so the two can never disagree.
            "questionnaireId": section.questionnaireId,
            **section_question_data(section),
            "prompt": payload.prompt.strip(),
            # The shared question code (C26), from the section and the wording; None off the file.
            "questionCode": cross_portal_codes.question_code(section.code, payload.prompt.strip()),
            # NAMED EXPLICITLY, because this handler builds its data dict field by field while
            # `update_question` below uses `model_dump(exclude_unset=True)`. Adding the two fields to
            # the schema and stopping there gives an editor whose PATCH stores help text and whose
            # POST answers 201 and stores nothing — two halves of one editor disagreeing, with no
            # error anywhere. `test_a_created_question_keeps_its_help_text_and_required_flag` pins it.
            "helpText": payload.helpText,
            "isRequired": payload.isRequired,
            # B8: which artisan field answers this question. Named here for the same reason as
            # helpText — this handler builds its dict field by field.
            "artisanField": payload.artisanField,
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@questionnaire_router.patch("/questions/{question_id}")
async def update_question(
    question_id: str,
    payload: QuestionnaireQuestionUpdate,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    question = await require_record(db.questionnairequestion, question_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if data.get("isActive") is False and question.isActive:
        # Switching a question off is removing it: the delete rule, not the edit rule (22 Oct 2026).
        await assert_may_delete_from_form(question.questionnaireId, _)
    else:
        await assert_may_edit_form(question.questionnaireId, _)
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
    # The same, for removing a question's artisan-field mapping (B8).
    if "artisanField" in payload.model_fields_set and payload.artisanField is None:
        data["artisanField"] = None
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_section(section_id)
        if section.questionnaireId != question.questionnaireId:
            # A QUESTION CANNOT CHANGE INSTRUMENT. Its answers were given under this instrument's
            # sitting, and `QuestionnaireResponse.question` is `Restrict`, so moving the question
            # would leave those answers attached to a row that is no longer on any form they were
            # collected against. Refused rather than repaired: there is no honest destination.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    f"A question cannot be moved between questionnaires. {question.sectionCode} "
                    f"#{question.sortOrder} belongs to «{question.questionnaireId}»; the target "
                    f"section belongs to «{section.questionnaireId}»."
                ),
            )
        if section.id != question.sectionId:
            # DESIGN 3.8: a question that has takes is never re-parented, whatever the v2 switch says
            # (the backfill's takes exist while it is off; audit M16). Its recordings point at it by
            # id, and coverage would silently move to another section.
            await _refuse_reparent_with_takes([question])
        data.update(section_question_data(section))
        if "sortOrder" not in data or data["sortOrder"] is None:
            data["sortOrder"] = await next_question_sort_order(section.id)
    elif question.sectionId and ("sectionCode" not in data or "sectionTitle" not in data):
        section = await require_section(question.sectionId)
        data.update({"sectionCode": section.code, "sectionTitle": section.title})
    # The shared question code follows the section and wording this row will have AFTER the write.
    data["questionCode"] = cross_portal_codes.question_code(
        data.get("sectionCode", question.sectionCode), data.get("prompt", question.prompt)
    )
    if "prompt" in data and data["prompt"] != question.prompt and await _question_has_evidence(question):
        # A REWORD OF A QUESTION SOMEONE ALREADY ANSWERED IS A NEW WORDING, NEVER AN EDIT IN PLACE
        # (DESIGN 7.2; audit M16): the old row is superseded, the new one carries the lineage and the
        # frozen display code, and a take shows "Recorded under earlier wording".
        return public_encode(await _supersede_question(question, data, getattr(_, "id", None)))
    updated = await db.questionnairequestion.update(where={"id": question_id}, data=data)
    # From questionnaire v2 on, every edit leaves history (DESIGN 7.2), so a later reader can tell
    # what a recording was asked under.
    changed = {
        key: {"old": jsonable_encoder(getattr(question, key, None)), "new": jsonable_encoder(value)}
        for key, value in data.items()
        if key in ("prompt", "sectionId", "sortOrder", "helpText", "isActive", "artisanField")
        and getattr(question, key, None) != value
    }
    if changed and v2_shims.enabled():
        await db.recordrevision.create(
            data={"recordType": "questionnaire_question", "recordId": question_id, "editedById": getattr(_, "id", None),
                  "changes": Json(changed)}
        )
    return public_encode(updated)


async def _question_has_evidence(question: Any) -> bool:
    """Takes, answers or drafts were given under this exact wording."""
    rows = await db.query_raw(
        'SELECT (EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t WHERE t."askedQuestionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" v WHERE v."askedQuestionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireResponse" r WHERE r."questionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireAnswerDraft" d WHERE d."questionId" = $1)) AS used',
        question.id,
    )
    return bool(rows and rows[0].get("used"))


async def _supersede_question(question: Any, data: dict[str, Any], editor_id: str | None) -> Any:
    """Write the new wording as a NEW row of the same lineage and retire the old one, in one
    transaction. The lineage trigger carries ``lineageId`` and ``displayCode`` across."""
    fields = ("questionnaireId", "sectionId", "sectionCode", "sectionTitle", "prompt", "questionCode", "helpText",
              "isRequired", "artisanField", "sortOrder", "isActive")
    row = {key: data.get(key, getattr(question, key, None)) for key in fields}
    async with db.tx() as tx:
        # The old row leaves the live set FIRST: the partial unique on (questionnaireId, displayCode)
        # admits one live row per code.
        await tx.execute_raw(
            'UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false '
            'WHERE "id" = $1',
            question.id,
        )
        created = await tx.questionnairequestion.create(data={
            **{k: v for k, v in row.items() if v is not None},
            "lineageId": getattr(question, "lineageId", None) or question.id,
            "displayCode": getattr(question, "displayCode", None),
        })
        await tx.execute_raw('UPDATE "QuestionnaireQuestion" SET "supersededById" = $2 WHERE "id" = $1', question.id, created.id)
        await tx.recordrevision.create(
            data={"recordType": "questionnaire_question", "recordId": question.id, "editedById": editor_id,
                  "changes": Json({"supersededBy": created.id, "prompt": {"old": question.prompt, "new": row["prompt"]}})}
        )
    return await db.questionnairequestion.find_unique(where={"id": created.id})


async def _refuse_reparent_with_takes(questions: list[Any]) -> None:
    lineages = sorted({getattr(q, "lineageId", None) or q.id for q in questions})
    takes = await db.query_raw(
        'SELECT count(*) AS n FROM "QuestionnaireRecordingTarget" t JOIN "QuestionnaireRecording" r '
        'ON r."id" = t."recordingId" WHERE r."removedAt" IS NULL AND t."questionLineageId" = ANY($1::text[])',
        lineages,
    )
    if takes and int(takes[0]["n"]):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "HAS_RECORDINGS", "message": "This question has recordings. Add a new question in the other section instead.",
                    "count": int(takes[0]["n"])},
        )


@questionnaire_router.delete("/questions/{question_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_question(
    question_id: str,
    user: Any = Depends(get_current_user),
) -> None:
    """Switch a question off. The form's author or a content admin (owner, 22 Oct 2026)."""
    question = await require_record(db.questionnairequestion, question_id)
    await assert_may_delete_from_form(question.questionnaireId, user)
    await db.questionnairequestion.update(where={"id": question_id}, data={"isActive": False})


@questionnaire_router.post("/questions/reorder")
async def reorder_questions(
    payload: QuestionnaireQuestionReorder,
    user: Any = Depends(get_current_user),
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
    await assert_may_edit_form(section.questionnaireId, user)
    questions = await db.questionnairequestion.find_many(where={"id": {"in": payload.questionIds}})
    by_id = {question.id: question for question in questions}
    if len(by_id) != len(set(payload.questionIds)):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    strays = sorted(
        q.id for q in questions if q.questionnaireId != section.questionnaireId
    )
    if strays:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"A question cannot be moved between questionnaires. These belong to a different "
                f"one from section {section.code}: {strays}."
            ),
        )
    # The reorder is also a re-parent: the has-takes rule holds here too (audit M16).
    moving = [q for q in questions if q.sectionId != section.id]
    if moving:
        await _refuse_reparent_with_takes(moving)
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
    # The workshop SCOPE, plural, from the shared filter vocabulary — repeatable or comma-joined ids
    # plus the reserved value "none"; absent means every workshop. Distinct from the singular
    # ``workshopId`` above, which stays because every existing caller sends it; WHEN BOTH ARE SENT
    # BOTH NARROW.
    #
    # IT IS SPELLED EXACTLY AS ``artisans.list_artisans`` SPELLS ITS OWN — same parser, same
    # sentinel, same "absent means every workshop" default — and that is the point rather than a
    # coincidence. These two routes back the SAME control on both clients: Android's
    # ``rememberWorkshopScope`` already hands ``scope.workshopIds`` to the artisan list, and the web
    # questionnaire form scopes its artisan picker and its interview lookup from one dropdown. A
    # scope that meant something subtly different on this route would offer one workshop's artisans
    # against another workshop's interviews, on a form whose entire job is linking the two. The two
    # routes cannot drift because the narrowing is the same three lines over the same helper.
    #
    # WHY THE SINGULAR ABOVE IS NOT ENOUGH, given it works and is staying. The scope control is a
    # MULTI-select on both clients (``Set<String>`` on Android, sorted and comma-joined onto the
    # query string), and it may hold the reserved "none". Neither is expressible singularly. Two
    # ticked workshops would have to be sent as the first id alone — silently answering a narrower
    # question than the one asked, with nothing on screen to say so — or as one request per ticked
    # workshop, which is the N-requests-for-N-ids shape ``list_artisans`` rejects in its own note.
    # And ``workshopId=none`` would test the column against the literal string "none", matching
    # nothing at all rather than the unlinked interviews the sentinel exists to name.
    workshopIds: list[str] | None = Query(None),
    questionnaireId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    # incomplete | required | complete — see services/completeness.py.
    completeness: str | None = None,
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
        # Equality on the interview's own column. Deliberately NOT routed through
        # ``workshop_clause`` even though the plural below is: for one real id that helper returns
        # ``{"workshopId": {"in": [id]}}``, which is this same predicate spelled longer, so there is
        # no behaviour to share and the existing filter keeps the exact shape every caller has been
        # getting.
        where["workshopId"] = workshopId
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        ids, include_unassigned = resolved_workshops
        # An interview carries its OWN ``workshopId`` foreign key, so this takes the ordinary branch
        # of the shared clause builder rather than the workshop-table one — the identical call
        # ``_derived_completed_sections`` makes for this identical table, so the interview LIST and
        # the completion MATRIX cannot disagree about which sittings a workshop scope contains.
        clause = workshop_clause(ids, include_unassigned)
        # INTO ``and_filters``, AND NOT a plain top-level key. The note immediately below explains
        # why every SCALAR filter on this route is a plain key; this one is not a scalar, and the
        # contrast is the reason it is written here rather than beside them. ``workshop_clause``
        # returns an ``OR`` the moment a scope names real workshops AND the reserved "none"
        # together, and ``where["OR"]`` is ASSIGNED OUTRIGHT by the free-text search above — parked
        # there, the entire scope would vanish for any caller who had also typed in the search box,
        # which is the browse screen's ordinary state. ``and_filters`` is the list the read
        # predicate already rides and is consumed into ``where["AND"]`` further down, so this
        # composes with the search ``OR`` instead of racing it. Writing ``where["AND"]`` here
        # directly would be a KeyError for the same reason that note gives: the key does not exist
        # yet.
        #
        # An impossible selection is written as IMPOSSIBLE rather than dropped, mirroring
        # ``artisan_workshop_clause``: "matches no interview" must never be mistakable for "do not
        # filter", which is the failure that shows the whole repository under a scope that excludes
        # all of it.
        and_filters.append(clause if clause else {"id": {"in": []}})
    # A PLAIN TOP-LEVEL KEY, beside `workshopId`, and neither under `where["OR"]` nor under
    # `where["AND"]`. `where["OR"]` is ASSIGNED OUTRIGHT by the free-text search above, so anything
    # parked there is dropped the moment a caller also passes `search=`; `where["AND"]` does not
    # exist yet at this point in the function (it is assigned from `and_filters` below, and only if
    # that list is non-empty), so writing into it would be a KeyError. Every other scalar filter on
    # this route is a plain key for exactly these two reasons.
    if questionnaireId:
        where["questionnaireId"] = questionnaireId
    # A session merged into another (H6 routes a merge with v2 data to sessions.merge_into, which
    # keeps the source so old links resolve) is not listed; its content is on the survivor.
    where["mergedIntoId"] = None
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if completeness_mode := completeness_module.parse_mode(completeness):
        and_filters.append(completeness_module.missing_where("interview", completeness_mode))
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
    # THE VIEWER, exactly as `routes/artisans.py` passes it (:358, :392, :402, :476) and for the
    # second of the two reasons that encoder takes one. Without it `public_encode` masks every
    # embedded artisan's Aadhaar AND — the half this route actually depends on — drops `url`,
    # `publicUrl` and `objectKey` from every media node, because no viewer means "withhold every
    # URL" (services/records.py:248-252, the safe default a route reaches by NOT thinking about
    # it). An interview on this instrument IS largely its recordings, so a saved interview came
    # back with its clips listed and unplayable: the client held the media rows and had no URL to
    # draw them from. `media_urls` is left to the encoder's own derivation, which is what the
    # record routes do — every URL for professor-and-above and for a dataset downloader, the
    # caller's own uploads otherwise. `media_url_owners` is the media LIST's answer, one query
    # dearer, and copying it here would be the second spelling of one rule this repository keeps
    # paying for.
    encoded = await completeness_module.attach("interview", public_encode(items, current_user), client=db)
    return page_payload(encoded, total, page, page_size)


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
    # The viewer, for the reason `list_interviews` states in full. A fold ANSWERS WITH THE
    # CANONICAL ROW so the client attaches its media to the shared entry — which it cannot do
    # while the recordings already on that row come back without a URL to play them from.
    return await completeness_module.attach("interview", public_encode(canonical, current_user), client=db)


def interview_title_from(
    people: list[Any], when: datetime | None, *, workshop: dict[str, Any] | None = None
) -> str:
    """The generated default (NAMING: ``Entity_Date_Place_Craft_Subject``), e.g.
    ``Interview_2026-10-17_Ramesh`` or ``Group-Interview_2026-10-17_2-Artisans``. Never the old
    " · " separators (audit Low)."""
    moment = when or datetime.now(UTC)
    day = naming.as_ist(moment if moment.tzinfo else moment.replace(tzinfo=UTC))
    return naming.session_title(naming.TitleInput(
        sessionDate=day.date() if isinstance(day, datetime) else day,
        workshop=workshop,
        participants=[p if isinstance(p, naming.Person) else naming.Person(name=str(p)) for p in people if p],
    ))


async def automatic_interview_title(
    artisan_ids: list[str], questionnaire_id: str | None, when: datetime | None
) -> str:
    """The title a sitting gets when its creator left the box empty (B8). ``questionnaire_id`` is
    kept for the callers; an instrument's title is not part of a generated name."""
    ids = [aid for aid in artisan_ids if aid]
    people: list[Any] = []
    for aid in ids:
        artisan = await db.artisan.find_unique(where={"id": aid})
        if artisan is not None:
            people.append(naming.Person(name=get_value(artisan, "name"), localName=get_value(artisan, "localName")))
    return interview_title_from(people, when)


# The create's transaction: a lock, a lookup, a row and its links. The slow box needs the headroom.
CREATE_TX_MAX_WAIT = timedelta(seconds=15)
CREATE_TX_TIMEOUT = timedelta(seconds=30)


async def create_interview_atomically(
    data: dict[str, Any], questionnaire_id: str, set_key: str | None, artisan_ids: list[str]
) -> tuple[Any | None, Any | None]:
    """``(created, None)``, or ``(None, winner)`` when this artisan set already has a sitting.

    ONE TRANSACTION, UNDER A LOCK ON THE SET (audit M21). The old path created the row, then looked
    for racers, then wrote the artisan links outside any transaction: two racing creates could each
    see the other's bare row, the one that won hit the set-held guard while writing its links and
    was left with none. Now the lookup, the row and its links happen together while holding
    ``pg_advisory_xact_lock`` on (instrument, set), so the second racer waits, finds the first one's
    finished row and folds into it.
    """
    unique_ids = sorted({aid for aid in artisan_ids if aid})
    if unique_ids:
        found = await db.artisan.find_many(where={"id": {"in": unique_ids}})
        if len(found) != len(unique_ids):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    async with db.tx(max_wait=CREATE_TX_MAX_WAIT, timeout=CREATE_TX_TIMEOUT) as tx:
        if set_key:
            await lock_key(tx, f"legacy-set:{questionnaire_id}:{set_key}")
            winner = await tx.questionnaireinterview.find_first(
                where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
            )
            if winner is not None:
                return None, winner
        created = await tx.questionnaireinterview.create(data=data)
        if unique_ids:
            await tx.questionnaireinterviewartisan.create_many(
                data=[{"interviewId": created.id, "artisanId": aid} for aid in unique_ids]
            )
    return created, None


@questionnaire_router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    request: Request,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    # A queued write naming an artisan merged away since lands on the survivor (services/artisan_merge).
    await redirect_merged_artisans(payload)
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
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    "This workshop uses a different questionnaire. Record the interview on the "
                    "workshop's questionnaire, or ask a questionnaire manager to change which one "
                    "the workshop uses."
                ),
            )

    # QUESTIONNAIRE V2 ON: the old-build shim (app/questionnaire/shims_fr). Every create makes a
    # NEW session (a session is a dated event; no fold), the typed title is kept as legacyTitle and
    # the title is generated. Same request and response shapes; never a new error code — after the
    # cutoff an old build's create gets 503 + Retry-After, which it treats as transient.
    if v2_shims.enabled():
        v2_shims.assert_old_build_may_create(request.headers)
        if not can_create_records(current_user):
            # A contributor or volunteer never created: the old route folded them into the row for
            # these artisans. Its v2 meaning is the matching session; they are attached to it, never
            # refused where they used to be accepted (audit H5).
            match = await v2_shims.matching_session(questionnaire_id, payload.workshopId, payload.artisanIds)
            if match:
                attached = await v2_shims.attach_to_session(match, payload, current_user, questionnaire_id)
                await hydrate_relations([attached], RELATIONS)
                return public_encode(attached, current_user)
        assert_can_create_records(current_user)
        created = await v2_shims.create_interview_shim(payload, current_user, questionnaire_id, check)
        await hydrate_relations([created], RELATIONS)
        return public_encode(created, current_user)

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
            where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
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
    _standardise(apply_interview_values, data)
    # B8: a sitting with no title is named for who, what and when — never refused for it.
    if not (data.get("title") or "").strip():
        data["title"] = await automatic_interview_title(
            payload.artisanIds, questionnaire_id, payload.recordedAt
        )
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
        created, winner = await create_interview_atomically(data, questionnaire_id, set_key, payload.artisanIds)
    except UniqueViolationError:
        # A database that still carries the old (questionnaireId, artisanSetKey) unique refuses the
        # loser of a race outright; fold it into the row that won, as before.
        created, winner = None, await db.questionnaireinterview.find_first(
            where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
        )
        if winner is None:
            raise
    if winner is not None:
        return await merge_into_interview(winner, payload, current_user, check)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user, questionnaire_id)
    # The row we just inserted IS the response; only its links and answers were written afterwards,
    # and those load in one wave here instead of a re-read followed by six more statements.
    await hydrate_relations([created], RELATIONS)
    # The viewer, for the reason `list_interviews` states in full.
    return await completeness_module.attach("interview", public_encode(created, current_user), client=db)


@questionnaire_router.get("/interviews/by-artisans")
async def interview_for_artisan_set(
    artisanIds: list[str] = Query(default=[]),
    questionnaireId: str | None = None,
    workshopId: str | None = None,
    current_user: Any = Depends(get_current_user),
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
        where={"questionnaireId": qid, "artisanSetKey": set_key, "mergedIntoId": None}
    )
    if not interview:
        return None
    await hydrate_relations([interview], RELATIONS)
    # The viewer, for the reason `list_interviews` states in full. THIS route is the one the capture
    # page calls before it offers to join a shared entry, so the recordings it shows as already
    # gathered are exactly the ones that were coming back unplayable.
    return public_encode(interview, current_user)


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
            where={"AND": [interview_where, {"mergedIntoId": None}]},
            # Live files only (audit M19): a removed copy is not evidence a section was recorded.
            include={"artisans": True, "responses": True, "media": {"where": {"removedAt": None}}},
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
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    """Content admins only: set or clear the manual status for one (artisan, section) cell.

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
    # The shared web client names "Marked complete" MARKED_COMPLETE; the stored token is COMPLETED.
    if payload.status == "MARKED_COMPLETE":
        payload = payload.model_copy(update={"status": "COMPLETED"})
    if payload.status not in COMPLETION_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
async def get_interview(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    await hydrate_relations([interview], RELATIONS)
    # The viewer, for the reason `list_interviews` states in full. The dependency was named `_`
    # while the value was genuinely unused; it is used now, so it is named.
    return await completeness_module.attach("interview", public_encode(interview, current_user), client=db)


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
    # A queued write naming an artisan merged away since lands on the survivor (services/artisan_merge).
    await redirect_merged_artisans(payload)
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}))
    _standardise(apply_interview_values, data, interview)
    data = await attach_location(data)
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != interview.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    privileged = await guard_record_edit(interview, current_user, data, "questionnaire")
    v2_on = v2_shims.enabled()
    if v2_on:
        # An old build's edit with v2 on (audit H5): the typed title is kept as legacyTitle and the
        # generated title is never overwritten.
        v2_shims.legacy_title_change(data)
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
        if not v2_on:
            await replace_interview_artisans(interview_id, payload.artisanIds)
    if payload.responses is not None and not v2_on:
        await upsert_responses(
            interview_id, payload.responses, current_user, get_value(interview, "questionnaireId")
        )
    if v2_on:
        # Artisan changes become participant changes (a participant with takes is kept), responses
        # become answers on the frozen-table-free path v2 reads, and the generated title follows.
        await v2_shims.patch_interview_shim(interview, payload, current_user)
        from app.questionnaire import titles as v2_titles

        await v2_titles.regenerate_title(db, interview_id)
    # Re-read the row itself (the PATCH may have changed its columns), but graft the six relations on
    # in one parallel wave rather than letting the include walk them one after another.
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([updated], RELATIONS)
    # The viewer, for the reason `list_interviews` states in full.
    return await completeness_module.attach("interview", public_encode(updated, current_user), client=db)


_MARKDOWN_BOLD = re.compile(r"\*\*(.+?)\*\*")
_MARKDOWN_RULE = re.compile(r"(?m)^\s*-{3,}\s*$")


def transcript_answer_text(transcript: str | None) -> str:
    """A transcript as answer text: the Markdown bold markers and rules taken out, nothing else.

    Transcripts are stored as Markdown ("**Speaker 1:** …", ``---`` between turns). An answer box is
    plain text, so the markers would print as asterisks. The words are never changed. The web's
    ``transcriptAnswerText`` (``lib/transcriptAnswer.ts``) is the same rule, held by the same vectors.
    """
    text = _MARKDOWN_BOLD.sub(r"\1", transcript or "")
    text = _MARKDOWN_RULE.sub("", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


# "Question audio: D3 - <prompt>" (web) or "Question audio: D3 <prompt>" (handset). The code and
# number are read only to narrow the search to a section; the PROMPT is what places the clip.
_CLIP_QUESTION_CAPTION = re.compile(r"^question\s+audio:\s*([A-Za-z]+?)\s*(\d+)\b\s*-?\s*(.*)$", re.IGNORECASE | re.DOTALL)


def _same_words(a: str | None, b: str | None) -> bool:
    return bool(a) and bool(b) and " ".join(str(a).split()).casefold() == " ".join(str(b).split()).casefold()


def clip_question_id(
    media: Any, questions: list[Any], links: list[dict[str, Any]] | None = None
) -> str | None:
    """The ONE question a clip was recorded against, or None for a section take or an unfiled clip.

    BY THE CLIP'S LINK TO ITS QUESTION, NEVER BY POSITION (audit M20). The caption's "D3" is the
    question's sortOrder on the day it was recorded; after a reorder or a deletion "D3" is a different
    question, and its transcript filled the wrong answer. In order:

    1. ``extraMetadata.questionId`` — the web writes it on every question clip;
    2. the clip's recording target (``links``: ``askedQuestionId`` / ``questionLineageId``), which
       the v2 backfill and the old-build shim resolve by content;
    3. the prompt text in the caption, matched word for word within the caption's section (the
       handset writes the prompt but not the id). A caption with no prompt is not placed.

    ``questions`` are this instrument's ACTIVE questions. A whole-section take answers no single
    question and is never matched.
    """
    by_id = {str(get_value(q, "id")): q for q in questions}
    extra = get_value(media, "extraMetadata")
    question_id = extra.get("questionId") if isinstance(extra, dict) else None
    if isinstance(question_id, str) and question_id in by_id:
        return question_id
    for link in links or []:
        asked = link.get("askedQuestionId")
        if asked and str(asked) in by_id:
            return str(asked)
        lineage = link.get("questionLineageId")
        if lineage:
            for q in questions:
                if str(get_value(q, "lineageId") or get_value(q, "id")) == str(lineage):
                    return str(get_value(q, "id"))
    found = _CLIP_QUESTION_CAPTION.match((get_value(media, "caption") or "").strip())
    if found:
        code, prompt = found.group(1).upper(), found.group(3).strip()
        if isinstance(extra, dict) and extra.get("questionPrompt"):
            prompt = str(extra["questionPrompt"])
        matches = [
            q for q in questions
            if (get_value(q, "sectionCode") or "").upper() == code and _same_words(get_value(q, "prompt"), prompt)
        ]
        if len(matches) == 1:
            return str(get_value(matches[0], "id"))
    return None


def question_clip_transcripts(
    media_rows: list[Any], questions: list[Any], links_by_media: dict[str, list[dict[str, Any]]] | None = None
) -> dict[str, str]:
    """question id → the transcript of its NEWEST transcribed clip, for clips filed to one question."""
    newest: dict[str, tuple[Any, str]] = {}
    for media in media_rows:
        question_id = clip_question_id(media, questions, (links_by_media or {}).get(str(get_value(media, "id"))))
        text = transcript_answer_text(get_value(media, "transcriptText"))
        if not question_id or not text:
            continue
        stamp = get_value(media, "createdAt")
        held = newest.get(question_id)
        if held is None or (stamp is not None and (held[0] is None or stamp > held[0])):
            newest[question_id] = (stamp, text)
    return {question_id: text for question_id, (_, text) in newest.items()}


async def _clip_question_links(media_rows: list[Any]) -> dict[str, list[dict[str, Any]]]:
    """media id → its live recording's question targets (``askedQuestionId``, ``questionLineageId``)."""
    ids = [str(get_value(m, "id")) for m in media_rows if get_value(m, "id")]
    if not ids:
        return {}
    recordings = await db.questionnairerecording.find_many(
        where={"mediaFileId": {"in": ids}, "removedAt": None}, include={"targets": True}
    )
    links: dict[str, list[dict[str, Any]]] = {}
    for recording in recordings:
        links[str(get_value(recording, "mediaFileId"))] = [
            {"askedQuestionId": get_value(t, "askedQuestionId"), "questionLineageId": get_value(t, "questionLineageId")}
            for t in (get_value(recording, "targets") or [])
            if get_value(t, "askedQuestionId") or get_value(t, "questionLineageId")
        ]
    return links


@questionnaire_router.post("/interviews/{interview_id}/apply-transcripts")
async def apply_interview_transcripts(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Fill every EMPTY answer of a sitting from its question's transcribed clip, in one call (A7).

    A typed answer is never overwritten — an answer with text is skipped, and ``upsert_responses``
    keeps its own rule that only the original contributor or an admin may change one. Each filled
    answer is stored with ``answerSource = "TRANSCRIPT"`` so every reader can tell it came from the
    recording. Answers ``{applied, skipped, interview}``.
    """
    # The same people the web offers the button to: a record creator or a reviewer. A volunteer
    # answers by typing; a one-tap fill of a whole sitting is not theirs to make.
    if not (can_create_records(current_user) or can_access_review(current_user)):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Using transcripts as answers needs Researcher or review access.",
        )
    interview = await require_record(db.questionnaireinterview, interview_id)
    questionnaire_id = get_value(interview, "questionnaireId")
    media_rows, responses, questions = await gather_reads(
        # Live files only (audit M19): a removed copy's transcript is not an answer.
        db.mediafile.find_many(where={"questionnaireInterviewId": interview_id, "removedAt": None}),
        db.questionnaireresponse.find_many(where={"interviewId": interview_id}),
        db.questionnairequestion.find_many(where={"questionnaireId": questionnaire_id, "isActive": True}),
    )
    transcripts = question_clip_transcripts(media_rows, questions, await _clip_question_links(media_rows))
    answered = {r.questionId for r in responses if (r.answerText or "").strip()}
    fills = [
        QuestionnaireResponseInput(questionId=qid, answerText=text, answerSource="TRANSCRIPT")
        for qid, text in sorted(transcripts.items())
        if qid not in answered
    ]
    if fills:
        # The chain `update_interview` runs for a PATCH that carries answers: the edit gate and its
        # revision row (the filled answers under one key, so the audit trail names them), then the
        # status rules — a creator's fill resubmits a sitting sent back, and a sitting flagged as a
        # late workshop submission stays PENDING.
        await guard_record_edit(
            interview,
            current_user,
            {"transcriptAnswers": {fill.questionId: fill.answerText for fill in fills}},
            "questionnaire",
        )
        data: dict[str, Any] = {}
        pin_pending_if_late(data, current_user, record=interview)
        resubmit_status(interview, current_user, data)
        await upsert_responses(interview_id, fills, current_user, questionnaire_id)
        if "status" in data:
            await db.questionnaireinterview.update(
                where={"id": interview_id}, data={"status": data["status"]}
            )
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([updated], RELATIONS)
    return {
        "applied": len(fills),
        "skipped": len(transcripts) - len(fills),
        "interview": public_encode(updated, current_user),
    }


#: The two columns of one answer that hold a researcher's words. A merge may FILL either where the
#: surviving row is empty and must REFUSE where both rows have said something different — per
#: column, because "same answer, extra note on one side" is a complement and not a disagreement.
_RESPONSE_TEXT_FIELDS = ("answerText", "notes")

_MERGE_CONFLICT_CODE = "merge_answer_conflict"


@questionnaire_router.post("/interviews/{interview_id}/merge-into/{target_id}")
async def merge_interview_into(
    interview_id: str,
    target_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Move this interview's answers and recordings onto ``target_id``, then remove this interview.

    LET AN EDIT FOLD, AS A CREATE ALREADY DOES — EXPLICITLY, NEVER SILENTLY. A create for a taken
    artisan set folds into the canonical row on its own (:func:`merge_into_interview`); an edit that
    arrives at the same set could not fold at all, and answered 409 with no way forward. That is the
    real case this exists for: two researchers recorded one artisan set as two sittings titled by
    the sections they covered, the "F" one had missed an artisan, and adding that artisan made F's
    set key equal D's. THE REFUSAL STILL STANDS — ``PATCH`` is unchanged and still refuses — and the
    fold happens only when a client, having read the holder off that 409, calls THIS route. The
    confirmation is the call.

    ── THE GATE IS THE ONE AN EDIT OF ``interview_id`` ALREADY PASSES, AND NOT A NEW ONE ──

    :func:`record_edit_privilege` (the question :func:`guard_record_edit` answers on its way past,
    asked early so a refusal below costs no ledger row), and for a caller it answers "no" to,
    ``deps.assert_can_contribute_relation`` — the SAME pair, in the same order, that
    :func:`update_interview` applies to ``artisanIds`` a few lines above. That is the right
    predicate because a merge is precisely what that guard is about: it empties this interview's
    populated relations onto another row and then removes the row itself, which is the strongest
    change to a record anybody but its author or an admin can be asking for. ``populated`` is passed
    ``True`` unconditionally for that reason — not derived from whether there happen to be answers
    to move — since the removal alone is already more than a contributor may do to someone else's
    sitting. No new permission is introduced and none is relaxed: whoever may edit this interview
    today, and nobody else, may fold it.

    THE TARGET IS NOT GATED SEPARATELY, and that is deliberate and not an oversight. The create-path
    fold writes onto a canonical row belonging to somebody else with no target-side check either;
    what protects the target THERE is that a fold can only ever fill what is empty. The same rule is
    what protects it here — a populated answer on the surviving row is never overwritten, it is
    either matched, complemented, or refused below — so the two paths are safe for one reason
    rather than two.

    ── WHAT IT REFUSES ──

    * A different instrument on either side: 422. The surviving row's answers must belong to the
      questions the sitting was actually taken on, and the two halves of
      ``@@unique([questionnaireId, artisanSetKey])`` only ever collide within one instrument
      anyway, so a cross-instrument merge cannot be the fold this route exists for.
    * ITSELF: 422. A self-merge would otherwise delete the row after moving its rows onto itself.
    * **BOTH ROWS ANSWERING ONE QUESTION WITH DIFFERENT TEXT: 409, naming every such question.**
      Nothing is written. Picking a winner would destroy a researcher's words under a 200, which is
      the worst outcome available here and the one thing this route must never do — a 409 costs the
      client one screen and costs nobody their work. Identical text is not a conflict; an answer
      only one side gave is not a conflict.

    ── THE WRITE ORDER IS THE ATOMICITY, BECAUSE THERE IS NO TRANSACTION TO BE ATOMIC WITH ──

    This backend has zero ``db.tx()`` call sites, so these five writes cannot be wrapped and an
    interruption between any two of them is a real state somebody will be left holding. The order is
    chosen so that EVERY such state is recoverable and none of them loses or orphans anything:

    1. delete the rows that cannot travel — source rows the survivor already says word for word, and
       EMPTY survivor rows about to be replaced by a real answer. Nothing here holds words.
    2. move the remaining answers. Stop here and they are on the survivor; the source still exists.
    3. fill the survivor's silent columns from the source rows being retired.
    4. repoint the media. Stop here and the clips are on the survivor; the source still exists.
    5. only now delete the source, whose responses and media are no longer its own.

    At no point is a recording attached to a row that has gone, and at no point are a researcher's
    words in neither place. The worst interruption leaves an emptied source interview still standing
    — visibly wrong, losing nothing, and finished by repeating the same call, which is why the whole
    route is safe to retry. Re-ordering these (deleting the row earlier, say) trades that for
    ``onDelete: SetNull`` orphaning every clip, which is the failure this ordering exists to avoid.

    Answers with the SURVIVING interview, hydrated and encoded for this viewer, so the client can
    redraw the whole screen from the response rather than guessing what the move produced.

    Content admins only since 2026-10-20 (``roles.CONTENT_ADMIN_ROLES``): a merge removes its source,
    and the owner's ruling counts that as a delete. The edit gate below still applies on top.
    """
    assert_can_delete(current_user)
    if interview_id == target_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="An interview cannot be merged into itself.",
        )
    source = await require_record(db.questionnaireinterview, interview_id)
    target = await require_record(db.questionnaireinterview, target_id)

    # SAME INSTRUMENT, CHECKED BEFORE THE GATE so a caller who may edit neither row still learns the
    # cheap, non-sensitive fact first and does not have a ledger row written for a doomed merge.
    if get_value(source, "questionnaireId") != get_value(target, "questionnaireId"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                "These two interviews were taken on different questionnaires. Answers cannot move "
                "between instruments — they would land under questions the sitting was never asked."
            ),
        )

    # The gate. Asked through `record_edit_privilege` rather than `guard_record_edit` so that every
    # refusal below happens before ANYTHING is written: `guard_record_edit` ends in `record_revision`
    # and this backend has no transaction to take a ledger row back with. It is called for real, with
    # this same answer handed to it, immediately before the first write.
    privileged = await record_edit_privilege(source, current_user, "questionnaire")
    if not privileged:
        assert_can_contribute_relation(source, current_user, True, "responses")

    source_rows, target_rows = await gather_reads(
        db.questionnaireresponse.find_many(where={"interviewId": interview_id}),
        db.questionnaireresponse.find_many(where={"interviewId": target_id}),
    )
    surviving = {get_value(row, "questionId"): row for row in target_rows}

    # One verdict per answer on the interview being folded away.
    conflicts: list[dict[str, Any]] = []
    #: question ids whose source row travels WHOLESALE — text, author and timestamps together.
    movable: list[str] = []
    #: (surviving row id, columns to fill) where the survivor is silent and the source is not.
    fills: list[tuple[str, dict[str, Any]]] = []
    #: rows to delete before the move: empty survivors being replaced, and source rows the survivor
    #: already says word for word. Both exist because `@@unique([interviewId, questionId])` allows
    #: exactly one row per question per interview, so two rows for one question cannot both land.
    stale: list[str] = []

    for row in source_rows:
        question_id = get_value(row, "questionId")
        twin = surviving.get(question_id)
        if twin is None:
            movable.append(question_id)
            continue
        clashing = [
            field
            for field in _RESPONSE_TEXT_FIELDS
            if not is_empty_value(get_value(row, field))
            and not is_empty_value(get_value(twin, field))
            and get_value(row, field) != get_value(twin, field)
        ]
        if clashing:
            conflicts.append({"questionId": question_id, "fields": clashing})
            continue
        if all(is_empty_value(get_value(twin, field)) for field in _RESPONSE_TEXT_FIELDS) and any(
            not is_empty_value(get_value(row, field)) for field in _RESPONSE_TEXT_FIELDS
        ):
            # The survivor holds an EMPTY row for this question and the source holds the words. Drop
            # the empty one and let the real one travel, rather than copying text across: the row
            # that moves keeps its own `answeredById` and its own timestamps, so the researcher who
            # actually gave the answer stays named as its author.
            stale.append(get_value(twin, "id"))
            movable.append(question_id)
            continue
        # Same words, or each side holding a different one of the two columns. Fill what is silent
        # on the survivor and drop the source row, which now says nothing the survivor does not.
        # `answeredById` is deliberately NOT restamped here: the survivor keeps the author of the
        # text it already carried, and only an empty column was filled.
        fill = {
            field: get_value(row, field)
            for field in _RESPONSE_TEXT_FIELDS
            if not is_empty_value(get_value(row, field)) and is_empty_value(get_value(twin, field))
        }
        if "answerText" in fill:
            # C28: the words travel with their origin, or a drafted answer would land unlabelled.
            fill["answerOrigin"] = get_value(row, "answerOrigin")
        if fill:
            fills.append((get_value(twin, "id"), fill))
        stale.append(get_value(row, "id"))

    if conflicts:
        # NAME THE QUESTIONS, not just the count. A researcher cannot act on "3 answers disagree";
        # they can act on the section and the prompt, which is what they will read on screen and
        # what they have to go and reconcile by hand before asking for the move again.
        questions = await db.questionnairequestion.find_many(
            where={"id": {"in": sorted({item["questionId"] for item in conflicts})}}
        )
        by_id = {get_value(question, "id"): question for question in questions}
        named = [
            {
                "questionId": item["questionId"],
                "sectionCode": get_value(by_id.get(item["questionId"]), "sectionCode"),
                "prompt": get_value(by_id.get(item["questionId"]), "prompt"),
                "fields": item["fields"],
            }
            for item in conflicts
        ]
        named.sort(key=lambda item: (item["sectionCode"] or "", item["prompt"] or ""))
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "code": _MERGE_CONFLICT_CODE,
                "message": (
                    f"Both interviews answer {len(named)} {'question' if len(named) == 1 else 'questions'} differently. Nothing was "
                    "moved. Make the answers match, then try again."
                ),
                "questions": named,
            },
        )

    # NOTHING ABOVE THIS LINE HAS WRITTEN ANYTHING. The ledger entry goes in first, naming the move
    # and who asked for it, because the row it describes is about to stop existing and this is the
    # only trace of the fold that outlives it. `privileged` is handed back so the two permission
    # reads are not repeated — it cannot grant what `record_edit_privilege` did not already find.
    await guard_record_edit(
        source, current_user, {"mergedIntoId": target_id}, "questionnaire", privileged=privileged
    )

    if stale:
        # BEFORE the move, or a row travelling into a question the survivor already has a row for
        # violates `@@unique([interviewId, questionId])` and the merge dies half-done.
        await db.questionnaireresponse.delete_many(where={"id": {"in": sorted(stale)}})
    if movable:
        await db.questionnaireresponse.update_many(
            where={"interviewId": interview_id, "questionId": {"in": sorted(movable)}},
            data={"interviewId": target_id},
        )
    for row_id, fill in fills:
        await db.questionnaireresponse.update(where={"id": row_id}, data=fill)

    # A SESSION WITH V2 DATA MERGES THE V2 WAY (audit H6), whatever the flag says. Recordings,
    # participants, researchers, answer revisions and upload intents move in ONE transaction, and the
    # source is kept with ``mergedIntoId`` so a queued clip or an old link still resolves. The legacy
    # path below moves only media and then deletes the row, which RESTRICTs on answer revisions (a
    # 500) and drops participants and researchers.
    if await _has_v2_data(interview_id):
        await v2_sessions.merge_into(interview_id, target_id, current_user)
        survivor = await db.questionnaireinterview.find_unique(where={"id": target_id})
        await hydrate_relations([survivor], RELATIONS)
        return public_encode(survivor, current_user)

    # THE RECORDINGS MOVE TOO, and on this instrument they are most of what an interview IS: the
    # app's questionnaire clips carry their section in the filename and are the completion signal
    # for a sitting whose answers were spoken rather than typed (`_derived_completed_sections`
    # reads them). A merge that moved only responses would move almost nothing — and it would do
    # worse than nothing, because `MediaFile.questionnaireInterviewId` is `onDelete: SetNull`
    # (schema.prisma:1118): the delete below would not have taken the clips with the row, it would
    # have ORPHANED them, leaving those recordings attached to no interview at all.
    await db.mediafile.update_many(
        where={"questionnaireInterviewId": interview_id},
        data={"questionnaireInterviewId": target_id},
    )

    # Now the row can go. Its artisan links cascade with it (`QuestionnaireInterviewArtisan`,
    # onDelete: Cascade), which is what frees the artisan set key and leaves ONE row for this set on
    # this instrument — the premise `by-artisans`, the consolidated view and the shared-entry banner
    # all rest on. Its responses are no longer its own by this point, so the `onDelete: Cascade` on
    # `QuestionnaireResponse` has nothing left to take.
    await db.questionnaireinterview.delete(where={"id": interview_id})

    survivor = await db.questionnaireinterview.find_unique(where={"id": target_id})
    await hydrate_relations([survivor], RELATIONS)
    # The viewer, for the reason `list_interviews` states in full — and most of all here: the point
    # of the move is that the recordings end up on this row, so an answer that listed them without
    # a URL would be a screen the researcher cannot tell apart from the merge having lost them.
    return public_encode(survivor, current_user)


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
    # NAMING 2.1: DesignWorkshop_Questionnaire_2026-10-18_Giriraj-Prasad-Chhipa_Export.csv
    return csv_response(
        naming.export_filename("Questionnaire", "Export", "csv", subject=name),
        output.getvalue(),
    )


@questionnaire_router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    """Delete an interview that holds nothing. Content admins only.

    409 ``HAS_RECORDINGS`` WHATEVER ``QUESTIONNAIRE_V2`` SAYS (audit H6). ``MediaFile`` points at the
    interview with ``onDelete: SetNull``, so deleting a sitting with clips detached every one of them
    (and its recording) from any session, and the coverage view dropped them. Answer revisions are
    RESTRICT, so a sitting with one failed with a 500. Same rule and body as the v2 session delete.
    """
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    live, revisions, pending = await gather_reads(
        db.mediafile.count(where={"questionnaireInterviewId": interview_id, "removedAt": None}),
        db.questionnaireanswerrevision.count(where={"sessionId": interview_id}),
        db.mediauploadintent.count(
            where={"sessionId": interview_id, "closedAt": None, "expiresAt": {"gt": datetime.now(UTC)}}
        ),
    )
    held = int(live or 0) + int(revisions or 0)
    if held:
        v2_fail("HAS_RECORDINGS", f"Move or remove its {held} recordings first.", count=held)
    if pending:
        v2_fail(
            "HAS_RECORDINGS",
            "A recording is still uploading to this interview. Try again once it lands.",
            count=int(pending),
            pending=int(pending),
        )
    await db.questionnaireinterview.delete(where={"id": interview_id})


async def _has_v2_data(interview_id: str) -> bool:
    """Whether this sitting carries anything only the v2 merge moves: a recording on one of its
    clips, an answer revision, a researcher row or an upload intent."""
    media = await db.mediafile.find_many(where={"questionnaireInterviewId": interview_id})
    media_ids = [str(get_value(m, "id")) for m in media]
    recordings, revisions, researchers, intents = await gather_reads(
        db.questionnairerecording.count(where={"mediaFileId": {"in": media_ids}}) if media_ids else _zero(),
        db.questionnaireanswerrevision.count(where={"sessionId": interview_id}),
        db.questionnaireinterviewresearcher.count(where={"interviewId": interview_id}),
        db.mediauploadintent.count(where={"sessionId": interview_id}),
    )
    return bool(recordings or revisions or researchers or intents)


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


def _instrument_payload(row: Any, counts: dict[str, dict[str, int]], user: Any = None) -> dict[str, Any]:
    """One instrument on the wire: ``services/questionnaire_library.form_payload``, which also names
    the AUTHOR (``createdById`` and the unified ``ownerId``), ``isShared``, ``kind`` and the soft
    delete, and with ``user`` what that account may do with it."""
    return questionnaire_library.form_payload(row, counts, user)


@instruments_router.get("")
async def list_questionnaires(
    current_user: Any = Depends(get_current_user),
    activeOnly: bool = True,
    scope: str | None = None,
    workshopId: str | None = None,
    kind: str | None = None,
) -> list[dict[str, Any]]:
    """Every instrument, with the counts a picker needs (one statement for the rows, one for the
    counts). With no ``scope`` the list is what every client has always read; the forms library asks
    for ``mine``, ``shared``, ``workshop`` (optionally one ``workshopId``) or, for content admins,
    ``deleted`` (UNIFY 4.1), optionally narrowed to one ``kind`` (or ``none``)."""
    return await questionnaire_library.list_forms(
        current_user, scope=scope, workshop_id=workshopId, kind=kind, active_only=activeOnly
    )


@instruments_router.get("/options")
async def questionnaire_options(
    designWorkshopId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Every live form a session can start on, as the shared handset start sheet reads it.

    THE SAME ROUTE, AND THE SAME ROW KEYS, AS THE DESIGNER PORTAL'S ``GET /questionnaires/options``.
    The questionnaire tree on Android is shared between the portals and asks both servers this one
    path (``QuestionnaireApi.formOptions``). Until this route existed here, Field Repository answered
    it from ``/{questionnaire_id}`` with ``"options"`` as the id — a 404 — so the handset's start
    sheet decoded nothing and offered no form at all. Declared above ``/{questionnaire_id}`` for that
    reason: FastAPI matches in declaration order.

    ``designWorkshopId`` is accepted so one client can call both portals with one signature; a form
    here binds to workshops through ``QuestionnaireBinding``, not one column, so the row's own
    ``designWorkshopId`` is always null and nothing is ever ``attachedElsewhere``.
    """
    forms = await questionnaire_library.list_forms(current_user, active_only=True)
    return [
        {
            "id": form["id"],
            "title": form["title"],
            "version": form["version"],
            "designWorkshopId": None,
            "isShared": form["isShared"],
            "kind": form["kind"],
            "kindLabel": form["kindLabel"],
            "isDefault": form["isDefault"],
            "sectionCount": form["sectionCount"],
            "attachedElsewhere": False,
        }
        for form in forms
        if form.get("deletedAt") is None
    ]


# ==================================================================================================
# THE WORKBOOK: DOWNLOADING THE INSTRUMENT AS A SPREADSHEET, AND UPLOADING ONE BACK
# ==================================================================================================
#
# WHO MAY PRESS IT (UNIFY 4.1, owner 22 Oct 2026)
# --------------------------------------------------------------------------------------------
#
# A NEW form from a workbook, the pro-forma and a form's question set are any questionnaire
# manager's (Professor and above): whoever uploads becomes the form's author. An EXISTING form's own
# workbook (`GET /{id}/xlsx`, `POST /{id}/upload`) is `require_form_editor`: its author or a content
# admin, the people who edit it one question at a time (`assert_may_edit_form`). A re-upload states
# the whole form, and what the file leaves out is retired when it has evidence; that is why the
# re-upload is held to the form's editors and not opened wider.
#
# ANSWERS
# --------------------------------------------------------------------------------------------
#
# A NEW form's hand-filled answers are imported as UPLOAD sessions (see `upload_questionnaire`).
# A re-upload never writes answers: its answer columns are counted and reported, because the
# sittings they came from already exist on the platform.

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


async def _answer_evidence(questions: list[Any], client: Any = None) -> tuple[set[str], set[str]]:
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
    client = client or db
    question_ids = [str(get_value(q, "id")) for q in questions]
    if not question_ids:
        return set(), set()
    lineage_of = {str(get_value(q, "id")): str(get_value(q, "lineageId") or get_value(q, "id")) for q in questions}
    lineages = sorted(set(lineage_of.values()))
    # NOT ONLY TYPED ANSWERS (audit H7). A recording filed to a question, a v2 answer or revision, and
    # a draft are evidence too: a question with only takes counted as unanswered, so it was reworded
    # in place under them, and if it was missing from the sheet it was hard-deleted until a RESTRICT
    # key stopped the loop half way. Removed takes count: they stay restorable.
    # One after another, not gathered: ``client`` is the re-upload's transaction.
    rows = await client.questionnaireresponse.find_many(where={"questionId": {"in": question_ids}})
    targets = await client.questionnairerecordingtarget.find_many(
        where={"OR": [{"askedQuestionId": {"in": question_ids}}, {"questionLineageId": {"in": lineages}}]}
    )
    answers = await client.questionnaireanswer.find_many(where={"questionLineageId": {"in": lineages}})
    revisions = await client.questionnaireanswerrevision.find_many(where={"askedQuestionId": {"in": question_ids}})
    take_drafts = await client.questionnairetakedraft.find_many(where={"questionLineageId": {"in": lineages}})
    drafts = await client.questionnaireanswerdraft.find_many(where={"questionId": {"in": question_ids}})
    answered = {
        row.questionId
        for row in rows
        if (row.answerText or "").strip() or (row.notes or "").strip()
    }
    asked = {str(get_value(r, "askedQuestionId")) for r in [*targets, *revisions] if get_value(r, "askedQuestionId")}
    asked |= {str(get_value(r, "questionId")) for r in drafts}
    held = {str(get_value(r, "questionLineageId")) for r in [*targets, *answers, *take_drafts] if get_value(r, "questionLineageId")}
    answered |= {qid for qid in question_ids if qid in asked or lineage_of[qid] in held}
    return answered, {row.questionId for row in rows}


def _answers_not_imported_reason(answer_rows: int, evidence: str | None) -> str:
    """The one sentence both upload paths give an admin about the answers they typed in.

    ONE FUNCTION, so the create path and the edit path cannot drift into two accounts of one rule.
    """
    origin = f" (it came out of the platform — {evidence})" if evidence else ""
    plural = "answer was" if answer_rows == 1 else "answers were"
    return (
        f"The {answer_rows} {plural} typed into this workbook{origin} and NOT imported. An answer "
        "belongs to an interview: it names the artisan who gave it, where it was "
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
    answers_imported: bool = False,
) -> tuple[str, dict[str, Any]]:
    """Store a freshly parsed workbook as a NEW questionnaire. Returns ``(id, report)``.

    NO ANSWER IS WRITTEN HERE. A hand-filled pro-forma's answers are imported by the upload route
    AFTER this returns, as UPLOAD sessions with a guest respondent per answer column and origin
    WORKBOOK (``questionnaire_library.import_workbook_answers``, UNIFY U7): a v2 session carries a
    date, a lead researcher and a respondent, so nothing has to be invented. ``answers_imported``
    says that will happen, so the answers are not reported as skipped.
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

    # ``answers_imported``: the route imports this workbook's answers as UPLOAD sessions once the
    # questions exist (UNIFY U7), so they are not reported as skipped.
    if not answers_imported:
        _note_skipped_answers(parsed, report)
    await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=questionnaire.id)
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


# The whole re-upload is one transaction (audit H7): every section and question write, or none.
WORKBOOK_TX_MAX_WAIT = timedelta(seconds=15)
WORKBOOK_TX_TIMEOUT = timedelta(seconds=120)


async def apply_parsed_edit(
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    *,
    title: str | None = None,
    description: str | None = None,
) -> dict[str, Any]:
    """:func:`apply_parsed_edit_in` inside ONE transaction (audit H7). A refusal part way (a RESTRICT
    key, a unique) used to leave the instrument half-applied behind a 500; now nothing is written."""
    async with db.tx(max_wait=WORKBOOK_TX_MAX_WAIT, timeout=WORKBOOK_TX_TIMEOUT) as tx:
        return await apply_parsed_edit_in(tx, questionnaire_id, parsed, title=title, description=description)


async def apply_parsed_edit_in(
    client: Any,
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
    existing = await client.questionnaire.find_unique(where={"id": questionnaire_id})
    if existing is None:
        raise QuestionnaireWorkbookError("That questionnaire no longer exists.")
    # READ ONCE, INTO AN INT, AND NEVER READ OFF `existing` AGAIN. The last thing this function does
    # is write `version = version_before + bumps` back to the row; computing `versionAfter` from
    # `existing.version` AFTER that write asks the question of its own answer, and gets
    # `before + bumps + bumps` the moment anything hands this function a live row rather than a
    # snapshot. The report is what the client compares against its cached copy of the form, so an
    # inflated number there makes every client believe it is stale on every upload.
    version_before = existing.version

    sections = await client.questionnairesection.find_many(
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
    all_questions = await client.questionnairequestion.find_many(
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

    answered, has_response_row = await _answer_evidence(all_questions, client)

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
                    "are kept. Move it into a section from the questionnaire "
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
        await client.questionnairesection.update(
            where={"id": section.id}, data={"sortOrder": -position}
        )
        section.sortOrder = -position

    for index, parsed_section in enumerate(kept_sections, start=1):
        code = (parsed_section.code or "").strip()
        section = section_by_code.get(code.lower()) if code else None
        if section is None:
            new_code = _unique_code(code, parsed_section.title, codes_taken)
            section = await client.questionnairesection.create(
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
            section = await _apply_workbook_section(section, parsed_section, index, client)
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
                await client.questionnairequestion.create(
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
                replacement = await client.questionnairequestion.create(
                    data={
                        **workbook_question_data(section),
                        "prompt": parsed_question.prompt,
                        "helpText": help_text,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                await client.questionnairequestion.update(
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
                await client.questionnairequestion.update(where={"id": match.id}, data=data)
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
        client,
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
    if updates.get("title"):
        # @@unique([title]), CHECKED BEFORE THE WRITE: inside the transaction a unique violation would
        # abort every section and question write above it (audit H7), where it used to cost only the
        # title. The title is dropped and the report says it was left alone.
        clash = await client.questionnaire.find_many(where={"title": updates["title"]})
        if any(get_value(row, "id") != questionnaire_id for row in clash):
            rejected = updates.pop("title")
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
        await client.questionnaire.update(where={"id": questionnaire_id}, data=updates)
    report["versionAfter"] = version_before + version_bumps
    await cross_portal_codes.reconcile_question_codes(client, questionnaire_id=questionnaire_id)
    return report


async def _apply_workbook_section(section: Any, parsed_section: Any, index: int, client: Any = None) -> Any:
    """Bring one existing section into line with its row in the workbook.

    A SECTION TITLE MAY CHANGE EVEN WHEN ANSWERED, because a heading is not the thing an answer
    answers. Reactivated on sight, so re-uploading a section somebody had switched off brings it
    back rather than leaving a retired duplicate beside it.

    sortOrder IS ALWAYS WRITTEN, with no "did it move?" guard. Every section was negated before this
    loop ran, so a guard reading the pre-negation position would skip the write for exactly the
    sections that did not move and strand them at a negative number for ever. See the block comment
    on the negation pass.
    """
    client = client or db
    wanted_title = parsed_section.title or section.title
    # CAPTURED BEFORE THE WRITE. Reading `section.title` after the update to decide whether the
    # update renamed anything is a question asked of the answer: it is true only while the object in
    # hand is a snapshot, and the moment anything hands this function a live row — a cache, a fake, a
    # future Prisma that returns the same instance — the comparison is against the new value and the
    # `update_many` below never runs. That failure is silent and its consequence is last year's
    # heading on every question in the consolidated export.
    renamed = section.title != wanted_title
    updated = await client.questionnairesection.update(
        where={"id": section.id},
        data={"title": wanted_title, "sortOrder": index, "isActive": True},
    )
    if renamed:
        # MIRRORS `update_section` above. The two strings are denormalised onto every question under
        # the section, and a rename that updated only the section row leaves every question carrying
        # last year's heading in the consolidated export, in the dataset CSV and on the browse rows —
        # with nothing wrong in the database to find.
        await client.questionnairequestion.update_many(
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
    client: Any = None,
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
    client = client or db
    bumps = 0
    for question in questions:
        if question.id in touched_questions:
            continue
        if question.id in answered:
            if question.isActive:
                await client.questionnairequestion.update(
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
                await client.questionnairequestion.update(
                    where={"id": question.id}, data={"isActive": False}
                )
                report["removed"] += 1
            continue
        await client.questionnairequestion.delete(where={"id": question.id})
        report["removed"] += 1

    order = next_section_order
    for section in sections:
        if section.id in touched_sections:
            continue
        verdicts = await client.questionnairesectionstatus.count(where={"sectionId": section.id})
        tasks = await client.assignedtask.count(where={"sectionIds": {"has": section.id}})
        await client.questionnairesection.update(
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
                    f"{verdicts} completion {'verdict' if verdicts == 1 else 'verdicts'} and {tasks} assigned "
                    f"{'task' if tasks == 1 else 'tasks'} still point at "
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
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
        ) from exc


@instruments_router.get("/pro-forma")
async def download_pro_forma(_: Any = Depends(require_questionnaire_manager)) -> Response:
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
    questionnaire_id: str, _: Any = Depends(require_form_editor)
) -> Response:
    """This questionnaire as the same workbook, with its Question IDs filled in. Whoever may
    re-upload it may download it: its author or a content admin (owner, 22 Oct 2026).

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
    questionnaire_id: str, _: Any = Depends(require_questionnaire_manager)
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
    kind: str | None = Form(default=None),
    workshopId: str | None = Form(default=None),
    importAnswers: bool = Form(default=True),
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """Create a questionnaire from a filled-in pro-forma: any questionnaire manager, who becomes its
    author (UNIFY 4.1: "authors, not admin only").

    ``title``, ``description``, ``kind`` and ``workshopId`` are FORM FIELDS on the multipart body (a
    bare default would read them from the query string and drop them silently).

    HAND-FILLED ANSWERS ARE IMPORTED AS UPLOAD SESSIONS (UNIFY U7, A2): each answer column becomes
    one session on the new form, its heading the session's guest respondent, every filled cell an
    answer with origin WORKBOOK, on ``workshopId`` when one is named. A workbook that came OUT of
    the platform (a Questionnaire ID on its Details sheet, or Question IDs filled in) never imports
    its answers: they belong to sessions that already exist, and the report says so.

    ``report.problems`` is the feature: every row the import could not take, with its Excel row.
    """
    content = await _read_workbook_upload(file, request)
    parsed = _parse_or_422(content, file.filename)
    try:
        wanted_kind = questionnaire_kinds.coerce_kind(kind)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    if workshopId:
        await require_record(db.workshop, workshopId)
    hand_filled = bool(importAnswers) and parsed.hasAnswers and _came_out_of_the_platform(parsed) is None
    try:
        questionnaire_id, report = await create_from_parsed(
            parsed,
            title=title,
            description=description,
            source_filename=file.filename,
            created_by_id=get_value(current_user, "id"),
            answers_imported=hand_filled,
        )
    except QuestionnaireWorkbookError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"ownerId": get_value(current_user, "id"), "kind": wanted_kind}
    )
    report["answersImported"] = 0
    report["sessionsCreated"] = 0
    if hand_filled:
        imported = await questionnaire_library.import_workbook_answers(
            questionnaire_id, parsed, current_user, workshop_id=workshopId
        )
        report["answersImported"] = imported["answers"]
        report["sessionsCreated"] = imported["sessions"]
        if imported["answers"]:
            sittings = imported["sessions"]
            report["provenance"] = {
                "action": "answersImported",
                "sourceQuestionnaireId": None,
                "answersSkipped": 0,
                "reason": (
                    f"{imported['answers']} answer{'s' if imported['answers'] != 1 else ''} imported as "
                    f"{sittings} uploaded session{'s' if sittings != 1 else ''}."
                ),
            }
    form = await load_workbook_form(questionnaire_id)
    return public_encode({"questionnaire": form, "report": report})


@instruments_router.post("/{questionnaire_id}/upload")
async def reupload_questionnaire(
    questionnaire_id: str,
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    _: Any = Depends(require_form_editor),
) -> dict[str, Any]:
    """Re-upload an edited workbook over an existing questionnaire: its author or a content admin,
    Ministry Admin included (owner, 22 Oct 2026). Creating a new one from a workbook stays an admin's.

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
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
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


@instruments_router.post("/bundles/import", status_code=status.HTTP_201_CREATED)
async def import_questionnaire_bundle(
    request: Request,
    response: Response,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """Open a handoff bundle (``.frwq`` or ``.dpwq``; UNIFY A10): a new form of your own, sections
    and questions only. The body is the bundle file (multipart ``file``) or its JSON. Importing the
    same bundle again returns the first copy with 200 rather than 201. A bundle from a newer build is
    ``BUNDLE_VERSION``."""
    limit = questionnaire_library.BUNDLE_LIMITS["maxUncompressedBytes"]
    if request.headers.get("content-type", "").startswith("multipart/"):
        form = await request.form()
        upload = form.get("file")
        if upload is None or not hasattr(upload, "read"):
            v2_fail("VALIDATION_FAILED", "Choose a bundle file.")
        raw = await upload.read(limit + 1)
    else:
        raw = await request.body()
    if len(raw) > limit:
        v2_fail("VALIDATION_FAILED", "This bundle is too large.")
    document = questionnaire_library.read_bundle_bytes(raw)
    payload, created = await questionnaire_library.import_bundle(document, current_user)
    if not created:
        response.status_code = status.HTTP_200_OK
    return {"questionnaire": payload, "created": created}


@instruments_router.get("/{questionnaire_id}")
async def get_questionnaire(
    questionnaire_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """One instrument and its whole structure, active sections included, for the builder and the
    form detail. A deleted form is shown to its author and the content admins (for Restore);
    anyone else gets ``FORM_DELETED``."""
    row = await require_questionnaire(questionnaire_id)
    if get_value(row, "deletedAt") is not None and not (
        is_content_admin(current_user) or is_form_author(row, current_user)
    ):
        v2_fail("FORM_DELETED")
    payload = _instrument_payload(row, {}, current_user)
    payload["sections"] = await section_payloads(questionnaire_id, active_only=False)
    payload["sectionCount"] = len(payload["sections"])
    payload["questionCount"] = sum(len(s["questions"]) for s in payload["sections"])
    return payload


@instruments_router.post("", status_code=status.HTTP_201_CREATED)
async def create_questionnaire(
    payload: QuestionnaireCreate,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """Create an empty instrument: `require_questionnaire_manager`. Whoever creates it is its author
    (``createdById`` and ``ownerId``), and from then on edits it alongside the content admins (owner,
    22 Oct 2026). It is private (not shared) until an admin shares it."""
    sort_order = payload.sortOrder
    if sort_order is None:
        rows = await db.questionnaire.find_many(order={"sortOrder": "desc"}, take=1)
        sort_order = (rows[0].sortOrder if rows else 0) + 1
    try:
        kind = questionnaire_kinds.coerce_kind(payload.kind)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    try:
        created = await db.questionnaire.create(
            data={
                "title": payload.title.strip(),
                "description": payload.description,
                "isActive": payload.isActive,
                "sortOrder": sort_order,
                "createdById": get_value(current_user, "id"),
                "ownerId": get_value(current_user, "id"),
                "kind": kind,
                # `isDefault` is NOT settable here and is not in the schema either. A new instrument
                # is never the default; making one is a separate admin act on a separate route.
            }
        )
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A questionnaire titled “{payload.title.strip()}” already exists.",
        ) from exc
    return _instrument_payload(created, {}, current_user)


@instruments_router.patch("/{questionnaire_id}")
async def update_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireUpdate,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Rename, re-describe, set the kind, reorder or RETIRE an instrument: its author or a content
    admin (owner, 22 Oct 2026); every other questionnaire manager is refused 403. ``isShared`` (share
    with everyone) is an admin act (UNIFY 2.3 rule 6). ``isActive: true`` brings a deleted form back,
    as ``POST /{id}/restore`` does: its author or a content admin.

    Retiring is the two-check route. An instrument that is the DEFAULT cannot be retired, because
    the default is where every client that names no instrument lands and retiring it would drop
    them onto the sortOrder fallback with nobody having decided that. An instrument BOUND TO A
    WORKSHOP cannot be retired either, because that workshop's capture form would keep opening on a
    retired instrument with nothing on screen saying so. Both are refused with the fix named.
    """
    row = await require_questionnaire(questionnaire_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "kind" in payload.model_fields_set:
        try:
            data["kind"] = questionnaire_kinds.coerce_kind(payload.kind)
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    sharing = data.pop("isShared", None)
    editing = bool({k for k in data if k != "isActive"}) or data.get("isActive") is True
    if editing and not (is_content_admin(user) or is_form_author(row, user)):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_EDIT_REFUSAL)
    if sharing is not None:
        if not questionnaire_authoring.may_admin_act(user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=questionnaire_authoring.ADMIN_ACT_REFUSAL)
        data["isShared"] = bool(sharing)
    if data.get("isActive") is True and get_value(row, "deletedAt") is not None:
        if not questionnaire_library.may_restore(user, row):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=questionnaire_library.RESTORE_REFUSAL)
        data["deletedAt"] = None
        data["deletedById"] = None
    if "title" in data:
        data["title"] = data["title"].strip()
    if data.get("isActive") is False:
        # Retiring is the delete of an instrument (audit M16): its author or a content admin, as
        # DELETE (owner, 22 Oct 2026).
        if not (is_content_admin(user) or is_form_author(row, user)):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the questionnaire's author or an admin can retire it.",
            )
        if row.isDefault:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
                    f"{len(titles)} {'workshop still uses' if len(titles) == 1 else 'workshops still use'} this questionnaire and it cannot be "
                    f"retired: {titles}. Rebind or detach them first."
                ),
            )
    if not data:
        return _instrument_payload(row, {}, user)
    try:
        updated = await db.questionnaire.update(where={"id": questionnaire_id}, data=data)
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A questionnaire with that title already exists.",
        ) from exc
    return _instrument_payload(updated, {}, user)


@instruments_router.delete("/{questionnaire_id}")
async def delete_questionnaire(
    questionnaire_id: str,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Delete an instrument: the restorable SOFT delete (``deletedAt``, and ``isActive`` false so
    every older reader drops it). Its author or a content admin (owner, 2026-10-20, amended
    2026-10-22). Its sessions and answers stay; content admins restore it (``POST /{id}/restore``).
    The default instrument (422) and one a workshop is bound to (409) are refused with the fix named.
    """
    row = await require_questionnaire(questionnaire_id)
    if not (is_content_admin(user) or is_form_author(row, user)):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_DELETE_REFUSAL)
    if get_value(row, "deletedAt") is not None:
        return _instrument_payload(row, {}, user)
    if row.isDefault:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="This is the default questionnaire. Choose another default first.",
        )
    bound = await db.workshop.find_many(where={"questionnaireId": questionnaire_id})
    if bound:
        titles = sorted(get_value(w, "title") or w.id for w in bound)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Unbind it from {', '.join(titles)} first.",
        )
    updated = await db.questionnaire.update(
        where={"id": questionnaire_id},
        data={"isActive": False, "deletedAt": datetime.now(UTC), "deletedById": get_value(user, "id")},
    )
    return _instrument_payload(updated, {}, user)


@instruments_router.post("/{questionnaire_id}/restore")
async def restore_questionnaire(questionnaire_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Bring a deleted form back, with everything recorded on it: its author or a content admin."""
    return await questionnaire_library.restore(questionnaire_id, user)


@instruments_router.post("/{questionnaire_id}/reuse", status_code=status.HTTP_201_CREATED)
async def reuse_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireReuse,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    """A new form of your own from this one's questions (UNIFY A5): sections and questions only."""
    return await questionnaire_library.reuse(
        questionnaire_id, current_user, title=payload.title, description=payload.description,
        kind=payload.kind, keep_kind="kind" not in payload.model_fields_set,
    )


@instruments_router.get("/{questionnaire_id}/bundle")
async def download_questionnaire_bundle(
    questionnaire_id: str, _: Any = Depends(require_questionnaire_manager)
) -> Response:
    """This form as a handoff bundle (``.frwq``; UNIFY A10): gzipped JSON with no ids, owner or
    author in it (shared/questionnaire-bundle.json), for another researcher to open."""
    document = await questionnaire_library.bundle_document(questionnaire_id)
    filename = questionnaire_library.bundle_filename(document["form"]["title"])
    return Response(
        content=questionnaire_library.bundle_bytes(document),
        media_type="application/gzip",
        headers={"Content-Disposition": naming.content_disposition(filename)},
    )


@instruments_router.put("/{questionnaire_id}/default")
async def set_default_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireDefaultUpdate,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin/master-admin only: which instrument an unqualified request belongs to.

    NOT AN EDIT TO A FORM, SO NOT THE FORM'S AUTHOR'S. Editing a form is its author's or a content
    admin's (`assert_may_edit_form`, owner 22 Oct 2026). This one is `require_admin` because it is not an edit to a form,
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
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="A retired questionnaire cannot be the default. Reactivate it first.",
        )
    # Cleared FIRST and unconditionally, because the partial unique index
    # ("Questionnaire_isDefault_key" … WHERE "isDefault") will refuse the second true. There is no
    # transaction idiom in this backend, so for the width of one statement no default exists; the
    # resolver's sortOrder fallback covers that window rather than 503ing a researcher mid-save.
    if not payload.isDefault:
        # Clearing touches THIS row only: ``isDefault: false`` on some other instrument must never
        # clear the real default (audit M16).
        updated = await db.questionnaire.update(where={"id": questionnaire_id}, data={"isDefault": False})
        return _instrument_payload(updated, {})
    await db.questionnaire.update_many(where={"isDefault": True}, data={"isDefault": False})
    updated = await db.questionnaire.update(where={"id": questionnaire_id}, data={"isDefault": True})
    return _instrument_payload(updated, {})


# Mounted LAST, after every decorator above has registered, because `include_router` copies the
# routes it finds at the moment it is called rather than holding a live reference.
router.include_router(questionnaire_router)
router.include_router(instruments_router)


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    """The shared standard values (``services/standard_values``): a value off its list is a 422."""
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc
