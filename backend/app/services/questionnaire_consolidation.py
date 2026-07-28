"""One artisan's questionnaire, gathered from every interview they sat in, as a single document.

WHY THIS EXISTS. An interview belongs to an exact SET of artisans — ``artisanSetKey`` is the sorted,
comma-joined artisan ids under a unique constraint, and a subset is a different set, so it gets its
own entry. That storage rule is correct and is NOT changed here: it is what stops five clients
recording the same sitting five times. But it means an artisan's answers are scattered across every
set they happen to appear in, and on the live repository that scattering is the normal case, not an
edge case: of sixteen artisans, thirteen appear in more than one interview and three appear in four.
Reading such an artisan today means opening four separate entries and holding them in your head.

This module does the gathering on the READ side only. Nothing here writes, merges or dedupes stored
rows.

THE THREE THINGS IT REFUSES TO DO QUIETLY, because each is a way a consolidated view becomes worse
than the four entries it replaced:

1. It never drops the source. Every answer carries the interview it came from, that interview's
   date, and how many people were in the room. A quote from a five-person sitting is different
   evidence from the same sentence said alone, and a view that flattens the two is not citable.

2. It never picks a winner. The same question answered twice, differently, in two interviews is a
   FINDING — an artisan whose account changed — not a data error to be resolved by taking the newest
   row. Both are shown, most recent first, flagged as a conflict.

3. It never guesses who spoke. See ``_attribution`` below: the schema simply does not record which
   member of a group answered, so for a group sitting this says so on the row instead of implying
   the answer was the subject's.
"""

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.core.deps import get_value, is_empty_value
from app.services.concurrency import gather_reads
from app.services.record_filters import resolve_workshop_ids, workshop_clause
from app.services.records import media_url_owners, public_encode, viewable_where

# How many statements ``consolidate_for_artisan`` issues, whatever the artisan's interview count.
# Asserted by the route's ``meta.queryCount`` so the number is checkable from a live response rather
# than trusted from a comment. Wave one: artisan, sections, questions, interviews. Wave two:
# responses, media, participant links.
QUERY_COUNT = 7

# A response is attributable to the subject alone.
SOLE = "SOLE"
# A response was given in a sitting with other artisans present and CANNOT be attributed to one of
# them from the stored data.
GROUP = "GROUP"

# Typed into the questionnaire form (a QuestionnaireResponse row, question-level).
TYPED = "TYPED"
# Spoken into a recording attached to the interview (a MediaFile, section-level at best).
RECORDED = "RECORDED"


def _norm_code(value: str | None) -> str:
    """Uppercase, alphanumerics-only form of a section code.

    Deliberately identical to ``routes/questionnaire._norm_code`` — it mirrors the app's filename
    ``token()``, which is how a clip's leading filename token resolves back to a section.
    """
    return "".join(ch for ch in (value or "") if ch.isalnum()).upper()


def _aware(value: Any) -> datetime:
    """A datetime safe to sort against the others, oldest possible when absent.

    Sorting provenance dates mixes columns that are nullable (``interviewDate`` is null on every
    interview in the live repository) with ones that are not, and Python refuses to order a naive
    datetime against an aware one. Both cases collapse here so a missing date sorts last under
    "most recent first" rather than raising.
    """
    if not isinstance(value, datetime):
        return datetime.min.replace(tzinfo=UTC)
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _interview_date(interview: Any) -> tuple[Any, str]:
    """The date to show for an interview, AND which column it came from.

    The basis is returned with the value because ``interviewDate`` is optional and is in fact null on
    every interview currently stored — it stopped being a form field, and ``recordedAt`` is the same
    fact captured for free. Showing a fallback timestamp with no note would present "when the phone
    uploaded this" as "when the interview happened"; the reader is told which one they are looking at.
    """
    for field, basis in (
        ("interviewDate", "interviewDate"),
        ("recordedAt", "recordedAt"),
        ("createdAt", "createdAt"),
    ):
        value = get_value(interview, field)
        if value:
            return value, basis
    return None, "unknown"


def _attribution(artisan_count: int) -> str:
    """Can an answer recorded in this interview be attributed to ONE artisan?

    Read the models before trusting any other answer to this. ``QuestionnaireResponse`` is unique on
    ``(interviewId, questionId)`` and carries no artisan column at all; its ``answeredById`` is a
    User — the fieldworker who typed it — not the person who spoke. ``QuestionnaireInterviewArtisan``
    is a bare join of interview to artisan with no per-response link. A recording is likewise
    attached to the interview, not to a participant.

    So the data supports exactly one inference: when the sitting had a single artisan, everything
    recorded in it is theirs. When it had several, the stored row does not say who answered, and this
    view must not invent it. Misattributing a quote is the failure this repository exists to avoid,
    so the ambiguity is reported on the row rather than resolved.
    """
    return SOLE if artisan_count <= 1 else GROUP


def _provenance(interview_meta: dict[str, Any]) -> dict[str, Any]:
    """The source block copied onto every answer row: which sitting, when, and who else was there."""
    return {
        "interviewId": interview_meta["id"],
        "interviewTitle": interview_meta["title"],
        "interviewDate": interview_meta["date"],
        "dateBasis": interview_meta["dateBasis"],
        "interviewStatus": interview_meta["status"],
        "workshopTitle": interview_meta["workshopTitle"],
        "artisanCount": interview_meta["artisanCount"],
        "coParticipants": interview_meta["coParticipants"],
        "attribution": interview_meta["attribution"],
    }


def _may_take(media: Any, media_owners: set[str] | None) -> bool:
    """Whether this caller may be handed the fetchable URL for one media row.

    ``None`` is ``records.ALL_MEDIA_URLS`` — professor and above, or a holder of the global
    dataset-download permission. Otherwise the row's uploader has to be in the resolved set: the
    caller themselves, or a researcher who granted them data access.
    """
    return media_owners is None or get_value(media, "uploadedById") in media_owners


def _answer_key(text: str | None) -> str:
    """Normalised answer text, for deciding whether two answers actually differ.

    Whitespace and case only. Anything cleverer (stemming, fuzzy distance) would start silently
    merging answers that a reader would want to see side by side, which is the behaviour this whole
    module is built to prevent.
    """
    return " ".join((text or "").split()).casefold()


async def consolidate_for_artisan(
    artisan_id: str, current_user: Any, workshop_ids: list[str] | None = None
) -> dict[str, Any] | None:
    """Build the consolidated questionnaire document for one artisan, or ``None`` if no such artisan.

    QUERY BUDGET — the reason this is shaped the way it is. The database is in a different AWS region
    from the web box, so a round trip costs 200-400ms and the only number that moves this page is how
    many run in SERIES. The obvious implementation asks per interview (answers, media, participants)
    and grows with the artisan's interview count: for an artisan in four sittings that is thirteen
    sequential trips, four seconds before anything renders. This issues seven statements in two
    gathered waves regardless of interview count — wave two only waits because it needs the interview
    ids wave one produced. An artisan in four interviews and an artisan in forty cost the same.

    Visibility is the shared ``viewable_where`` predicate, not a second rule written here: the
    interviews this returns are exactly the interviews the caller can already list, and media is
    additionally filtered by its own ``uploadedById`` owner column the way the export path does.

    ``workshop_ids`` narrows the document to the interviews taken AT those workshops, through the SAME
    ``record_filters`` clause builder every other screen uses (the reserved "none" value included).
    That is what lets a reader draw a conclusion from ONE workshop: "what did this artisan tell us at
    last week's workshop" is a different document from "everything this artisan has ever said", and the
    summary counts, the conflict flags and the sources panel all have to be computed over the narrowed
    set rather than filtered afterwards — a conflict between two workshops is not a conflict inside
    one of them, and post-filtering would leave the flag set with only one answer under it.
    """
    # Not a query — it reads the caller's rank and returns a predicate — so it can be resolved before
    # the wave rather than costing a trip of its own.
    vis = await viewable_where(current_user)
    media_vis = await viewable_where(current_user, owner_field="uploadedById")
    # WHOSE recordings this caller may actually be handed. Resolved once for the whole document, before
    # the wave, because it is a single grant lookup and every recording entry below consults it.
    media_owners = await media_url_owners(current_user)

    interview_where: dict[str, Any] = {"artisans": {"some": {"artisanId": artisan_id}}}
    and_clauses: list[dict[str, Any]] = []
    if vis:
        and_clauses.append(vis)
    resolved_workshops = resolve_workshop_ids(workshop_ids)
    if resolved_workshops is not None:
        ids, include_unassigned = resolved_workshops
        clause = workshop_clause(ids, include_unassigned)
        and_clauses.append(clause if clause else {"id": {"in": []}})
    if and_clauses:
        interview_where = {"AND": [interview_where, *and_clauses]}

    artisan, sections, questions, interviews = await gather_reads(
        db.artisan.find_unique(where={"id": artisan_id}, include={"craft": True}),
        db.questionnairesection.find_many(where={"isActive": True}, order={"sortOrder": "asc"}),
        db.questionnairequestion.find_many(order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]),
        db.questionnaireinterview.find_many(where=interview_where, include={"workshop": True}),
    )
    if artisan is None:
        return None

    interview_ids = [row.id for row in interviews]
    if interview_ids:
        media_where: dict[str, Any] = {"questionnaireInterviewId": {"in": interview_ids}}
        if media_vis:
            media_where = {"AND": [media_where, media_vis]}
        responses, media_rows, links = await gather_reads(
            # No `question` include: wave one already loaded every question, and a relation include
            # is its own round trip inside this call (see records.hydrate_relations). The prompt and
            # section come off that map instead.
            db.questionnaireresponse.find_many(
                where={"interviewId": {"in": interview_ids}}, include={"answeredBy": True}
            ),
            db.mediafile.find_many(where=media_where),
            db.questionnaireinterviewartisan.find_many(
                where={"interviewId": {"in": interview_ids}}, include={"artisan": True}
            ),
        )
    else:
        responses, media_rows, links = [], [], []

    # --- Who was in each sitting -------------------------------------------------------------
    participants: dict[str, list[str]] = {}
    for link in links:
        name = get_value(get_value(link, "artisan"), "name")
        if name:
            participants.setdefault(link.interviewId, []).append(name)
    for names in participants.values():
        names.sort()

    subject_name = get_value(artisan, "name")
    interview_meta: dict[str, dict[str, Any]] = {}
    for row in interviews:
        names = participants.get(row.id, [])
        date, basis = _interview_date(row)
        interview_meta[row.id] = {
            "id": row.id,
            "title": row.title,
            "date": date,
            "dateBasis": basis,
            "status": row.status,
            "workshopTitle": get_value(get_value(row, "workshop"), "title"),
            "artisanCount": len(names),
            "coParticipants": [n for n in names if n != subject_name],
            "attribution": _attribution(len(names)),
        }

    # --- Where the questionnaire's own order comes from ---------------------------------------
    section_by_id = {s.id: s for s in sections}
    section_id_by_code = {s.code: s.id for s in sections}
    section_id_by_norm_code = {_norm_code(s.code): s.id for s in sections if _norm_code(s.code)}
    section_of_question = {q.id: q.sectionId for q in questions if q.sectionId}
    questions_by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId in section_by_id:
            questions_by_section.setdefault(question.sectionId, []).append(question)

    # --- Typed answers, grouped by the question they answer -----------------------------------
    answers_by_question: dict[str, list[dict[str, Any]]] = {}
    for response in responses:
        if is_empty_value(response.answerText) and is_empty_value(response.notes):
            continue
        meta = interview_meta.get(response.interviewId)
        if meta is None:
            continue
        answers_by_question.setdefault(response.questionId, []).append(
            {
                "kind": TYPED,
                "sourceId": response.id,
                "answerText": response.answerText,
                "notes": response.notes,
                "recordedByName": get_value(get_value(response, "answeredBy"), "name"),
                "sortAt": _aware(meta["date"]),
                **_provenance(meta),
            }
        )

    # --- Recorded answers -----------------------------------------------------------------------
    # A clip resolves to a section three ways, and they are the SAME three the completion matrix
    # uses (routes/questionnaire._derived_completed_sections): the questionId an app wrote into
    # extraMetadata, the sectionCode it wrote there, or the leading token of the nomenclatured
    # filename. Measured against the live repository those rules place 321 of 566 interview clips;
    # two further filename shapes were tried and placed five more between them, which does not buy a
    # second, disagreeing answer to "which section is this clip". The 240 that resolve to nothing —
    # 237 of them one artisan's batch, uploaded with no section signal at all — are listed under
    # `unfiled` rather than dropped, because a recording this view silently omits is a recording a
    # researcher will conclude does not exist.
    recordings_by_section: dict[str, list[dict[str, Any]]] = {}
    recordings_by_question: dict[str, list[dict[str, Any]]] = {}
    unfiled: list[dict[str, Any]] = []
    for media in media_rows:
        meta = interview_meta.get(get_value(media, "questionnaireInterviewId"))
        if meta is None:
            continue
        extra = media.extraMetadata if isinstance(media.extraMetadata, dict) else {}
        question_id = extra.get("questionId")
        section_id = section_of_question.get(question_id) if question_id else None
        if section_id is None:
            section_id = section_id_by_code.get(extra.get("sectionCode"))
        if section_id is None:
            first_token = (media.originalFilename or "").split("_", 1)[0]
            section_id = section_id_by_norm_code.get(_norm_code(first_token))

        entry = {
            "kind": RECORDED,
            "sourceId": media.id,
            "mediaId": media.id,
            "filename": media.originalFilename,
            "mediaType": media.mediaType,
            # WITHHELD AT THE SOURCE, not by the encoder. This entry is a hand-built dict, so it does
            # not carry the ``objectKey`` marker ``records._redact_sensitive`` recognises a media node
            # by — the scrub would walk straight past it and ship the URL. The entitlement is therefore
            # applied here, against the same grant set every other media surface uses.
            #
            # A missing URL is a first-class state on this page: the transcript and the answer text are
            # the document, and the audio is the evidence behind them. A reader who may not take the
            # file still gets the whole account; the player simply is not offered.
            "url": media.url if _may_take(media, media_owners) else None,
            "transcriptText": media.transcriptText,
            "transcriptStatus": media.transcriptStatus,
            "recordedByName": None,
            "sortAt": _aware(get_value(media, "recordedAt") or get_value(media, "createdAt")),
            **_provenance(meta),
        }
        if question_id and question_id in section_of_question:
            recordings_by_question.setdefault(question_id, []).append(entry)
        elif section_id and section_id in section_by_id:
            recordings_by_section.setdefault(section_id, []).append(entry)
        else:
            unfiled.append(entry)

    # --- Assemble in the questionnaire's own order --------------------------------------------
    # By section, then by question — NOT by interview. Reading one artisan as one document is the
    # entire point; ordering by source would just re-create the four entries this replaces.
    def newest_first(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        # Most recent first: an artisan's current account leads, and the earlier one sits under it
        # where the change is visible. Interview id breaks ties so repeated loads never reshuffle.
        rows.sort(key=lambda row: (row["sortAt"], row["interviewId"]), reverse=True)
        for row in rows:
            row.pop("sortAt", None)
        return rows

    section_payloads: list[dict[str, Any]] = []
    answered_questions = conflicts = typed_total = recorded_total = 0
    for section in sections:
        question_payloads: list[dict[str, Any]] = []
        for question in questions_by_section.get(section.id, []):
            rows = answers_by_question.get(question.id, []) + recordings_by_question.get(
                question.id, []
            )
            if not rows:
                continue
            distinct = {
                _answer_key(row.get("answerText"))
                for row in rows
                if row["kind"] == TYPED and not is_empty_value(row.get("answerText"))
            }
            answered_questions += 1
            typed_total += sum(1 for row in rows if row["kind"] == TYPED)
            recorded_total += sum(1 for row in rows if row["kind"] == RECORDED)
            conflict = len(distinct) > 1
            if conflict:
                conflicts += 1
            question_payloads.append(
                {
                    "id": question.id,
                    "prompt": question.prompt,
                    "sortOrder": question.sortOrder,
                    "conflict": conflict,
                    "answers": newest_first(rows),
                }
            )
        section_recordings = newest_first(recordings_by_section.get(section.id, []))
        recorded_total += len(section_recordings)
        if not question_payloads and not section_recordings:
            continue
        section_payloads.append(
            {
                "id": section.id,
                "code": section.code,
                "title": section.title,
                "sortOrder": section.sortOrder,
                "questions": question_payloads,
                # Clips that name a section but not a question. They sit under the section heading
                # because that is genuinely all the data says; pinning them to a question would be
                # the same invention this module refuses to make about speakers.
                "recordings": section_recordings,
            }
        )

    ordered_interviews = sorted(
        interview_meta.values(), key=lambda meta: _aware(meta["date"]), reverse=True
    )
    group_sittings = sum(1 for meta in ordered_interviews if meta["attribution"] == GROUP)

    payload = {
        "artisan": {
            "id": artisan.id,
            "name": subject_name,
            "craftName": get_value(get_value(artisan, "craft"), "name"),
            "place": get_value(artisan, "place"),
        },
        "generatedAt": datetime.now(UTC),
        "interviews": ordered_interviews,
        "sections": section_payloads,
        "unfiled": newest_first(unfiled),
        "summary": {
            "interviewCount": len(ordered_interviews),
            "groupSittingCount": group_sittings,
            "soleSittingCount": len(ordered_interviews) - group_sittings,
            "answeredQuestionCount": answered_questions,
            "typedAnswerCount": typed_total,
            "recordedAnswerCount": recorded_total,
            "unfiledRecordingCount": len(unfiled),
            "conflictCount": conflicts,
        },
        "meta": {"queryCount": QUERY_COUNT},
    }
    # The artisan's own identity numbers are never selected onto this payload, but the encode runs
    # with no viewer anyway: it is the repository-wide guarantee that Aadhaar and Pehchan cannot
    # leave through a surface nobody has audited yet, and this is such a surface.
    # The caller is named so the identity masking is judged against them rather than defaulting to
    # masked, and ``media_urls`` is passed for the same reason it is resolved above — this payload is
    # hand-built, so the encoder cannot recognise its recording entries as media nodes.
    return public_encode(payload, current_user, media_urls=media_owners)


# Column order matches how the document reads on screen — section, question, answer, then the
# provenance that makes the answer citable. `ID` first is the CSV convention the /export downloads
# already follow (services/csv_export): a data extract needs a stable key to join on.
CSV_COLUMNS = (
    "Source ID",
    "Section code",
    "Section",
    "Question",
    "Answer kind",
    "Answer",
    "Notes",
    "Transcript status",
    "Attribution",
    "Attributable to this artisan",
    "Co-participants",
    "Conflicting answers on this question",
    "Interview",
    "Interview date",
    "Date basis",
    "Interview status",
    "Workshop",
    "Artisans in sitting",
    "Recorded by",
    "Interview ID",
    "Source file",
)


def _csv_row(
    section_code: str, section_title: str, prompt: str, conflict: bool, row: dict[str, Any]
) -> list[Any]:
    date = row.get("interviewDate") or ""
    return [
        row.get("sourceId"),
        section_code,
        section_title,
        prompt,
        "Typed" if row.get("kind") == TYPED else "Recording",
        row.get("answerText") or row.get("transcriptText") or "",
        row.get("notes") or "",
        row.get("transcriptStatus") or "",
        row.get("attribution"),
        # Spelled out as a word rather than left implicit in "GROUP": a spreadsheet column that reads
        # "No" cannot be skimmed past the way an enum can.
        "Yes" if row.get("attribution") == SOLE else "No - group sitting, speaker not recorded",
        "; ".join(row.get("coParticipants") or []),
        "Yes" if conflict else "",
        row.get("interviewTitle") or "",
        date,
        row.get("dateBasis") or "",
        row.get("interviewStatus") or "",
        row.get("workshopTitle") or "",
        row.get("artisanCount"),
        row.get("recordedByName") or "",
        row.get("interviewId"),
        row.get("filename") or "",
    ]


def consolidated_rows(payload: dict[str, Any]) -> list[list[Any]]:
    """Flatten the document into CSV rows, in the order it reads on screen."""
    rows: list[list[Any]] = []
    for section in payload.get("sections", []):
        code = section.get("code") or ""
        title = section.get("title") or ""
        for question in section.get("questions", []):
            conflict = bool(question.get("conflict"))
            for answer in question.get("answers", []):
                rows.append(_csv_row(code, title, question.get("prompt") or "", conflict, answer))
        for recording in section.get("recordings", []):
            # No question to name: the clip resolved to this section and no further.
            rows.append(_csv_row(code, title, "(section recording)", False, recording))
    for recording in payload.get("unfiled", []):
        rows.append(_csv_row("", "(unfiled)", "(section could not be determined)", False, recording))
    return rows
