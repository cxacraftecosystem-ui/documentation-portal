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
    Column("origin", "Origin", "text", "How the answer was made: typed, dictated, drafted from a recording.", width=16, tech=False, source="QuestionnaireResponse.answerOrigin,QuestionnaireAnswerRevision.origin"),
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


TEXT_MAX = 30000
_VERSIONS_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "id", "mediaFileId" FROM "TranscriptVersion" '
    'WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL ORDER BY "mediaFileId", "version" DESC'
)
_TURNS_SQL = (
    'SELECT "versionId", "position", "language", "machineText", "editedText", "machineEnglish", "editedEnglish", "translationStatus" '
    'FROM "TranscriptTurn" WHERE "versionId" = ANY($1::text[]) ORDER BY "versionId", "position"'
)
_MEDIA_TEXT_SQL = 'SELECT "id", "transcriptText", "transcriptLanguage" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
CHUNK = 200


def _clip(text: str) -> str:
    return text if len(text) <= TEXT_MAX else text[: TEXT_MAX - 1] + "…"


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
        spoken = await self._spoken(ctx, store)
        rows = list(self._rows(ctx, store, spoken))
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
                record_key="answerId",
            )
        ]

    async def _spoken(self, ctx: ReportContext, store: tables.Store) -> dict[str, dict[str, Any]]:
        live = {str(_v(m, "id")) for m in store.rows.get("MediaFile", ())}
        ids = sorted({str(_v(r, "mediaFileId")) for r in store.rows.get("QuestionnaireRecording", ()) if not _v(r, "removedAt") and str(_v(r, "mediaFileId")) in live} - store.withheld)
        if not ids:
            return {}
        db = ctx.shared.get("db")
        if db is None:
            from app.core.db import db as default_db

            db = default_db
        out: dict[str, dict[str, Any]] = {}
        for start in range(0, len(ids), CHUNK):
            part = ids[start : start + CHUNK]
            versions = {str(r["id"]): str(r["mediaFileId"]) for r in await db.query_raw(_VERSIONS_SQL, part)}
            turns: dict[str, list[dict[str, Any]]] = {}
            if versions:
                for row in await db.query_raw(_TURNS_SQL, list(versions)):
                    turns.setdefault(versions.get(str(row["versionId"]), ""), []).append(row)
            for mid, said in turns.items():
                original = [str(r.get("editedText") or r.get("machineText") or "").strip() for r in said]
                english: list[str] = []
                states: set[str] = set()
                for r, text in zip(said, original):
                    state = str(r.get("translationStatus") or "").upper()
                    states.add(state)
                    value = r.get("editedEnglish") or r.get("machineEnglish") or (text if state == "ALREADY_ENGLISH" else "")
                    english.append(str(value or "").strip())
                languages = sorted({str(r.get("language") or "") for r in said} - {""})
                out[mid] = {"original": "\n".join(t for t in original if t), "english": "\n".join(t for t in english if t), "language": ", ".join(languages), "states": states}
            missing = [m for m in part if m not in out]
            if missing:
                for row in await db.query_raw(_MEDIA_TEXT_SQL, missing):
                    text = str(row.get("transcriptText") or "").strip()
                    if text:
                        out[str(row["id"])] = {"original": text, "english": "", "language": str(row.get("transcriptLanguage") or ""), "states": set()}
        return out

    def _rows(self, ctx: ReportContext, store: tables.Store, spoken: dict[str, dict[str, Any]] | None = None) -> Iterator[dict[str, Any]]:
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

        yield from self._audio(ctx, store, spoken or {}, by_lineage, sitting_artisans, sitting_cell, keep, original, english)

    def _audio(self, ctx: ReportContext, store: tables.Store, spoken: dict[str, dict[str, Any]], by_lineage: dict[str, Any], sitting_artisans: dict[str, list[str]], sitting_cell: Any, keep: Any, original: str, english: str) -> Iterator[dict[str, Any]]:
        t = ctx.t
        links = ctx.options.links
        media = {str(_v(m, "id")): m for m in store.rows.get("MediaFile", ())}
        targets: dict[str, list[Any]] = {}
        for target in store.rows.get("QuestionnaireRecordingTarget", ()):
            targets.setdefault(str(_v(target, "recordingId")), []).append(target)
        subjects: dict[str, list[str]] = {}
        for subject in store.rows.get("QuestionnaireRecordingSubject", ()):
            name = store.name("Artisan", _v(subject, "artisanId"))
            if name:
                subjects.setdefault(str(_v(subject, "recordingId")), []).append(name)
        whole = t("answers.wholeSection", "Whole section")
        audio = t("answers.audio", "Audio recording")

        recordings = [r for r in store.rows.get("QuestionnaireRecording", ()) if not _v(r, "removedAt") and str(_v(r, "mediaFileId")) in media]
        for rec in sorted(recordings, key=lambda r: (str(_v(r, "capturedAt") or _v(r, "createdAt") or ""), str(_v(r, "id")))):
            mid = str(_v(rec, "mediaFileId"))
            m = media[mid]
            interview = _v(m, "questionnaireInterviewId")
            withheld = mid in store.withheld
            said = spoken.get(mid)
            for target in sorted(targets.get(str(_v(rec, "id")), []), key=lambda x: str(_v(x, "targetKey"))) or [None]:
                lineage = _v(target, "questionLineageId") if target is not None else None
                q = by_lineage.get(str(lineage)) if lineage else None
                section_row = store.get("QuestionnaireSection", _v(target, "sectionId")) if target is not None and _v(target, "sectionId") else None
                if q is not None:
                    code = str(_v(q, "sectionCode") or "")
                    section = " · ".join(x for x in (code, str(_v(q, "sectionTitle") or "")) if x)
                    number = _v(q, "displayCode") or _v(q, "questionCode")
                    question = _v(q, "prompt")
                elif section_row is not None:
                    code = str(_v(section_row, "code") or "")
                    section = " · ".join(x for x in (code, str(_v(section_row, "title") or "")) if x)
                    number = None
                    question = whole
                else:
                    code, section, number, question = "", "", None, lineage or ""
                pair = f"{_v(rec, 'id')}:{_v(target, 'targetKey') if target is not None else ''}"
                base = {
                    "sitting": sitting_cell(interview, code),
                    "artisan": ", ".join(subjects.get(str(_v(rec, "id")), []) or [a for a in sitting_artisans.get(str(interview), []) if a]),
                    "section": section,
                    "code": number,
                    "question": question,
                    "origin": audio,
                    "answeredBy": store.name("User", _v(rec, "recordedById")),
                    "answeredOn": _v(rec, "capturedAt") or _v(rec, "createdAt"),
                    "answerId": pair,
                    ROW_PAIR: pair,
                }
                if links:
                    base["playFrom"] = Link(t("link.play", "Play"), ctx.links.play_from(mid, 0))
                if withheld:
                    first = {**base, ROW_KIND: "plain", "row": original, "language": "", "answer": "", "status": t("status.withheld", "Withheld")}
                    if keep(first):
                        yield first
                    continue
                if said is None:
                    first = {**base, ROW_KIND: "plain", "row": original, "language": "", "answer": "", "status": t("status.notTranscribed", "Not transcribed")}
                    if keep(first):
                        yield first
                    continue
                states = said["states"]
                if states and states <= {"ALREADY_ENGLISH"}:
                    status = t("status.alreadyEnglish", "Already English")
                elif said["english"] and states <= {"TRANSLATED", "ALREADY_ENGLISH", "DONE", ""}:
                    status = t("status.translated", "Translated")
                elif states & {"PENDING", "QUEUED", "PROCESSING", "RUNNING"}:
                    status = t("status.translating", "Translating")
                elif said["english"]:
                    status = t("status.translated", "Translated")
                else:
                    status = t("status.notRequested", "Not requested")
                first = {**base, ROW_KIND: "original", "row": original, "language": said["language"], "answer": _clip(said["original"]), "status": t("status.transcribed", "Transcribed")}
                second = {**base, ROW_KIND: "translation", "row": english, "language": "English", "answer": _clip(said["english"]), "status": status}
                if keep(first, second):
                    yield first
                    yield second


register(AnswersPlugin())
