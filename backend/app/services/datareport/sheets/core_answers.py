from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from app.services.datareport import tables
from app.services.datareport.contract import (
    ROW_KIND,
    ROW_PAIR,
    Column,
    Link,
    ReportContext,
    Sheet,
    query_words,
    register,
)

COLUMNS = [
    Column("sitting", "Sitting", "link", "The questionnaire sitting; opens that section of the sitting in the portal.", width=26, source="QuestionnaireResponse.interviewId,QuestionnaireAnswerRevision.sessionId"),
    Column("artisan", "Artisan", "text", "Who the answer is about.", width=22, source="QuestionnaireAnswer.artisanId,QuestionnaireInterviewArtisan.artisanId"),
    Column("respondent", "Respondent", "text", "Who gave the answer, when not the artisan.", width=18, source="QuestionnaireAnswer.respondentId"),
    Column("section", "Section", "text", "The questionnaire section.", width=24, source="QuestionnaireQuestion.sectionCode,QuestionnaireQuestion.sectionTitle"),
    Column("code", "Question no.", "text", "The question's number in the questionnaire.", width=10, source="QuestionnaireQuestion.displayCode,QuestionnaireQuestion.questionCode"),
    Column("question", "Question", "longtext", "The question asked.", width=48, wrap=True, source="QuestionnaireQuestion.prompt,QuestionnaireAnswer.questionLineageId"),
    Column("row", "Row", "text", "Original answer, or its English translation for answers recorded as audio.", width=16),
    Column("language", "Language", "text", "Language of the answer text.", width=10, source="QuestionnaireAnswerRevision.languageCode"),
    Column("answer", "Answer", "longtext", "The answer as recorded, or its English translation on translation rows.", width=60, wrap=True, source="QuestionnaireResponse.answerText,QuestionnaireAnswerRevision.text,TranscriptDraftCitation.english"),
    Column("status", "Status", "status", "Answer status; on translation rows: Translated, Already English or Not requested.", width=18, source="QuestionnaireAnswerRevision.status"),
    Column("origin", "Origin", "text", "How the answer was made: typed, dictated, drafted from a recording.", width=16, source="QuestionnaireResponse.answerOrigin,QuestionnaireAnswerRevision.origin"),
    Column("notes", "Notes", "longtext", "Notes on the answer.", width=30, wrap=True, source="QuestionnaireResponse.notes,QuestionnaireAnswerRevision.note"),
    Column("answeredBy", "Answered by", "text", "Who recorded the answer.", width=18, source="QuestionnaireResponse.answeredById,QuestionnaireAnswerRevision.actorId"),
    Column("answeredOn", "Answered on", "datetime", "When the answer was recorded (IST).", source="QuestionnaireResponse.updatedAt,QuestionnaireAnswerRevision.createdAt"),
    Column("playFrom", "Play recording", "link", "Plays the recording this answer was taken from.", width=14, source="QuestionnaireAnswerRevision.sourceRecordingId"),
    Column("pinned", "Pinned", "bool", "Whether this answer is pinned against later edits.", source="QuestionnaireAnswer.pinned"),
    Column("answerId", "Answer ID", "id", "The answer's identifier.", hidden=True, group="ids", source="QuestionnaireResponse.id,QuestionnaireAnswer.id"),
    Column("questionId", "Question ID", "id", "The question's identifier.", hidden=True, group="ids", source="QuestionnaireResponse.questionId"),
]


def _v(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def _status(value: Any) -> str:
    raw = str(getattr(value, "value", value) or "")
    return raw.replace("_", " ").capitalize() if raw else ""


class AnswersPlugin:
    key = "answers"
    title = "Answers"
    order = 59
    option = None
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        return [(self.key, COLUMNS, self.title)]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        store = await tables.shared_store(ctx)
        rows = list(self._rows(ctx, store))
        return [
            Sheet(
                key=self.key,
                name=self.title,
                columns=COLUMNS,
                rows=rows,
                color=tables.SITTING,
                description="Every questionnaire answer, by sitting and question; answers recorded as audio carry their English translation underneath.",
                row_pairs=True,
                count=len(rows),
            )
        ]

    def _rows(self, ctx: ReportContext, store: tables.Store) -> Iterator[dict[str, Any]]:
        t = ctx.t
        links = ctx.options.links
        wanted = query_words(ctx.options.q) if ctx.options.rows == "filtered" else []
        by_lineage: dict[str, Any] = {}
        for q in store.rows.get("QuestionnaireQuestion", ()):
            lineage = _v(q, "lineageId") or _v(q, "id")
            current = by_lineage.get(str(lineage))
            if current is None or (_v(q, "isActive") and not _v(current, "isActive")):
                by_lineage[str(lineage)] = q
        sitting_artisans: dict[str, list[str]] = {}
        for link in store.rows.get("QuestionnaireInterviewArtisan", ()):
            sitting_artisans.setdefault(str(_v(link, "interviewId")), []).append(store.name("Artisan", _v(link, "artisanId")))
        citations = {(str(_v(c, "recordingId")), str(_v(c, "questionLineageId"))): c for c in store.rows.get("TranscriptDraftCitation", ())}
        recordings = {str(_v(r, "id")): r for r in store.rows.get("QuestionnaireRecording", ())}
        original = t("row.original", "Original")
        english = t("row.translation", "English translation")

        def sitting_cell(interview_id: Any, section: str) -> Any:
            if not interview_id:
                return None
            label = store.name("QuestionnaireInterview", interview_id) or str(interview_id)
            if links and section:
                return Link(label, ctx.links.section(str(interview_id), section))
            if links:
                return Link(label, ctx.links.open_record("interview", str(interview_id)))
            return label

        def keep(*rows: dict[str, Any]) -> bool:
            if not wanted:
                return True
            text = " ".join(str(v.text if isinstance(v, Link) else v) for r in rows for k, v in r.items() if not k.startswith("_") and v is not None).lower()
            return all(w in text for w in wanted)

        for r in store.rows.get("QuestionnaireResponse", ()):
            q = store.get("QuestionnaireQuestion", _v(r, "questionId"))
            section = str(_v(q, "sectionCode") or "") if q is not None else ""
            row = {
                ROW_KIND: "plain",
                "sitting": sitting_cell(_v(r, "interviewId"), section),
                "artisan": ", ".join(a for a in sitting_artisans.get(str(_v(r, "interviewId")), []) if a),
                "section": " · ".join(x for x in (section, str(_v(q, "sectionTitle") or "")) if x) if q is not None else "",
                "code": (_v(q, "displayCode") or _v(q, "questionCode")) if q is not None else None,
                "question": _v(q, "prompt") if q is not None else _v(r, "questionId"),
                "answer": _v(r, "answerText"),
                "origin": _v(r, "answerOrigin"),
                "notes": _v(r, "notes"),
                "answeredBy": store.name("User", _v(r, "answeredById")),
                "answeredOn": _v(r, "updatedAt") or _v(r, "createdAt"),
                "answerId": str(_v(r, "id")),
                "questionId": str(_v(r, "questionId") or ""),
            }
            if keep(row):
                yield row

        revisions: dict[str, Any] = {}
        for rev in store.rows.get("QuestionnaireAnswerRevision", ()):
            revisions[str(_v(rev, "id"))] = rev
        latest: dict[str, Any] = {}
        for rev in store.rows.get("QuestionnaireAnswerRevision", ()):
            key = str(_v(rev, "answerId"))
            current = latest.get(key)
            if current is None or (_v(rev, "createdAt") and _v(current, "createdAt") and _v(rev, "createdAt") > _v(current, "createdAt")):
                latest[key] = rev
        for a in store.rows.get("QuestionnaireAnswer", ()):
            rev = revisions.get(str(_v(a, "currentRevisionId") or "")) or latest.get(str(_v(a, "id")))
            q = by_lineage.get(str(_v(a, "questionLineageId")))
            section = str(_v(q, "sectionCode") or "") if q is not None else ""
            language = str(_v(rev, "languageCode") or "") if rev is not None else ""
            base = {
                "sitting": sitting_cell(_v(rev, "sessionId") if rev is not None else None, section),
                "artisan": store.name("Artisan", _v(a, "artisanId")),
                "respondent": store.name("QuestionnaireRespondent", _v(a, "respondentId")),
                "section": " · ".join(x for x in (section, str(_v(q, "sectionTitle") or "")) if x) if q is not None else "",
                "code": (_v(q, "displayCode") or _v(q, "questionCode")) if q is not None else None,
                "question": _v(q, "prompt") if q is not None else _v(a, "questionLineageId"),
                "origin": _v(rev, "origin") if rev is not None else None,
                "notes": _v(rev, "note") if rev is not None else None,
                "answeredBy": store.name("User", _v(rev, "actorId")) if rev is not None else "",
                "answeredOn": _v(rev, "createdAt") if rev is not None else _v(a, "updatedAt"),
                "pinned": bool(_v(a, "pinned")),
                "answerId": str(_v(a, "id")),
                ROW_PAIR: str(_v(a, "id")),
            }
            recording_id = _v(rev, "sourceRecordingId") if rev is not None else None
            recording = recordings.get(str(recording_id)) if recording_id else None
            if recording is not None and links:
                base["playFrom"] = Link(t("link.play", "Play"), ctx.links.play_from(str(_v(recording, "mediaFileId")), 0))
            first = {**base, "row": original, "language": language, "answer": _v(rev, "text") if rev is not None else None, "status": _status(_v(rev, "status")) if rev is not None else ""}
            if not recording_id:
                first[ROW_KIND] = "plain"
                if keep(first):
                    yield first
                continue
            first[ROW_KIND] = "original"
            cite = citations.get((str(recording_id), str(_v(a, "questionLineageId"))))
            text = _v(cite, "english") if cite is not None else None
            if language.lower().startswith("en"):
                status, text = t("status.alreadyEnglish", "Already English"), text or first["answer"]
            elif text:
                status = t("status.translated", "Translated")
            else:
                status = t("status.notRequested", "Not requested")
            second = {**base, ROW_KIND: "translation", "row": english, "language": "English", "answer": text or "", "status": status}
            if keep(first, second):
                yield first
                yield second


register(AnswersPlugin())
