from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.questionnaire.live import live_media_where
from app.core.deps import get_value, is_empty_value
from app.services import answer_drafts
from app.services.concurrency import gather_reads
from app.services.record_filters import resolve_workshop_ids, workshop_clause
from app.services.records import media_url_owners, public_encode, signed_media_url, viewable_where

QUERY_COUNT = 8

SOLE = "SOLE"
GROUP = "GROUP"

TYPED = "TYPED"
RECORDED = "RECORDED"


def _norm_code(value: str | None) -> str:
    return "".join(ch for ch in (value or "") if ch.isalnum()).upper()


def _aware(value: Any) -> datetime:
    if not isinstance(value, datetime):
        return datetime.min.replace(tzinfo=UTC)
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _interview_date(interview: Any) -> tuple[Any, str]:
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
    return SOLE if artisan_count <= 1 else GROUP


def _provenance(interview_meta: dict[str, Any]) -> dict[str, Any]:
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
    return media_owners is None or get_value(media, "uploadedById") in media_owners


def _answer_key(text: str | None) -> str:
    return " ".join((text or "").split()).casefold()


async def consolidate_for_artisan(
    artisan_id: str,
    current_user: Any,
    workshop_ids: list[str] | None = None,
    questionnaire_id: str | None = None,
) -> dict[str, Any] | None:
    vis = await viewable_where(current_user)
    media_vis = await viewable_where(current_user, owner_field="uploadedById")
    media_owners = await media_url_owners(current_user)

    interview_where: dict[str, Any] = {"artisans": {"some": {"artisanId": artisan_id}}}
    if questionnaire_id:
        interview_where["questionnaireId"] = questionnaire_id
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

    instrument_where: dict[str, Any] = {"id": questionnaire_id} if questionnaire_id else {}
    section_where: dict[str, Any] = {"isActive": True}
    question_where: dict[str, Any] = {}
    if questionnaire_id:
        section_where["questionnaireId"] = questionnaire_id
        question_where["questionnaireId"] = questionnaire_id
    artisan, instruments, sections, questions, interviews = await gather_reads(
        db.artisan.find_unique(where={"id": artisan_id}, include={"craft": True}),
        db.questionnaire.find_many(where=instrument_where),
        db.questionnairesection.find_many(where=section_where, order={"sortOrder": "asc"}),
        db.questionnairequestion.find_many(
            where=question_where, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]
        ),
        db.questionnaireinterview.find_many(where=interview_where, include={"workshop": True}),
    )
    if artisan is None:
        return None
    instrument_by_id = {row.id: row for row in instruments}

    interview_ids = [row.id for row in interviews]
    if interview_ids:
        media_where: dict[str, Any] = {"questionnaireInterviewId": {"in": interview_ids}}
        if media_vis:
            media_where = {"AND": [media_where, media_vis]}
        responses, media_rows, links = await gather_reads(
            db.questionnaireresponse.find_many(
                where={"interviewId": {"in": interview_ids}}, include={"answeredBy": True}
            ),
            db.mediafile.find_many(where=live_media_where(media_where)),
            db.questionnaireinterviewartisan.find_many(
                where={"interviewId": {"in": interview_ids}}, include={"artisan": True}
            ),
        )
    else:
        responses, media_rows, links = [], [], []

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
            "questionnaireId": get_value(row, "questionnaireId"),
            "questionnaireTitle": get_value(
                instrument_by_id.get(get_value(row, "questionnaireId")), "title"
            ),
            "date": date,
            "dateBasis": basis,
            "status": row.status,
            "workshopTitle": get_value(get_value(row, "workshop"), "title"),
            "artisanCount": len(names),
            "coParticipants": [n for n in names if n != subject_name],
            "attribution": _attribution(len(names)),
        }

    section_by_id = {s.id: s for s in sections}
    section_id_by_code = {(s.questionnaireId, s.code): s.id for s in sections}
    section_id_by_norm_code = {
        (s.questionnaireId, _norm_code(s.code)): s.id for s in sections if _norm_code(s.code)
    }
    section_of_question = {q.id: q.sectionId for q in questions if q.sectionId}
    questions_by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId in section_by_id:
            questions_by_section.setdefault(question.sectionId, []).append(question)

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
                "answerOrigin": get_value(response, "answerOrigin"),
                "recordedByName": get_value(get_value(response, "answeredBy"), "name"),
                "sortAt": _aware(meta["date"]),
                **_provenance(meta),
            }
        )

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
        instrument_key = meta["questionnaireId"]
        if section_id is None:
            section_id = section_id_by_code.get((instrument_key, extra.get("sectionCode")))
        if section_id is None:
            first_token = (media.originalFilename or "").split("_", 1)[0]
            section_id = section_id_by_norm_code.get((instrument_key, _norm_code(first_token)))

        entry = {
            "kind": RECORDED,
            "sourceId": media.id,
            "mediaId": media.id,
            "filename": media.originalFilename,
            "mediaType": media.mediaType,
            "url": signed_media_url(media.objectKey, media.url, media.mimeType, media.originalFilename)
            if _may_take(media, media_owners)
            else None,
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

    def newest_first(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        rows.sort(key=lambda row: (row["sortAt"], row["interviewId"]), reverse=True)
        for row in rows:
            row.pop("sortAt", None)
        return rows

    section_payloads: list[dict[str, Any]] = []
    answered_questions = conflicts = typed_total = recorded_total = 0
    ordered_sections = sorted(
        sections,
        key=lambda s: (
            get_value(instrument_by_id.get(s.questionnaireId), "sortOrder") or 0,
            s.questionnaireId or "",
            s.sortOrder,
        ),
    )
    for section in ordered_sections:
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
                "questionnaireId": section.questionnaireId,
                "questionnaireTitle": get_value(
                    instrument_by_id.get(section.questionnaireId), "title"
                ),
                "questions": question_payloads,
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
    return public_encode(payload, current_user, media_urls=media_owners)


CSV_COLUMNS = (
    "Source ID",
    "Questionnaire",
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
    "Answer origin",
)


def _csv_row(
    questionnaire_title: str,
    section_code: str,
    section_title: str,
    prompt: str,
    conflict: bool,
    row: dict[str, Any],
) -> list[Any]:
    date = row.get("interviewDate") or ""
    return [
        row.get("sourceId"),
        questionnaire_title,
        section_code,
        section_title,
        prompt,
        "Typed" if row.get("kind") == TYPED else "Recording",
        row.get("answerText") or row.get("transcriptText") or "",
        row.get("notes") or "",
        row.get("transcriptStatus") or "",
        row.get("attribution"),
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
        answer_drafts.origin_label(row.get("answerOrigin")),
    ]


def consolidated_rows(payload: dict[str, Any]) -> list[list[Any]]:
    rows: list[list[Any]] = []
    for section in payload.get("sections", []):
        code = section.get("code") or ""
        title = section.get("title") or ""
        instrument = section.get("questionnaireTitle") or ""
        for question in section.get("questions", []):
            conflict = bool(question.get("conflict"))
            for answer in question.get("answers", []):
                rows.append(
                    _csv_row(instrument, code, title, question.get("prompt") or "", conflict, answer)
                )
        for recording in section.get("recordings", []):
            rows.append(_csv_row(instrument, code, title, "(section recording)", False, recording))
    for recording in payload.get("unfiled", []):
        rows.append(
            _csv_row("", "", "(unfiled)", "(section could not be determined)", False, recording)
        )
    return rows
