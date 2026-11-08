from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

from app.services.datareport import tables, transcript_status
from app.services.datareport.contract import (
    ROW_FULL,
    ROW_KIND,
    ROW_PAIR,
    Column,
    Link,
    ReportContext,
    Rich,
    Sheet,
    query_words,
    register,
)

CHUNK = 100

_MEDIA_SQL = 'SELECT "id", "transcriptText", "transcriptLanguage" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
_VERSIONS_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "id", "mediaFileId", "version", "languages", "durationMs" '
    'FROM "TranscriptVersion" WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL '
    'ORDER BY "mediaFileId", "version" DESC'
)
_TURNS_SQL = (
    'SELECT "id", "versionId", "mediaFileId", "position", "speakerKey", "startMs", "endMs", "language", '
    '"machineText", "editedText", "machineEnglish", "editedEnglish", "translationStatus", "translationModel", '
    '"editedAt", "editedById", "revision", "createdAt", "updatedAt" FROM "TranscriptTurn" '
    'WHERE "versionId" = ANY($1::text[]) ORDER BY "versionId", "position"'
)
_SPEAKERS_SQL = 'SELECT "mediaFileId", "speakerKey", "name", "role" FROM "TranscriptSpeaker" WHERE "mediaFileId" = ANY($1::text[])'
_MANUAL_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "mediaFileId", "translationText" FROM "TranscriptInsight" '
    """WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL AND "provider" = 'manual' """
    'ORDER BY "mediaFileId", "version" DESC'
)

S_TEXT = "TranscriptTurn.machineText,TranscriptTurn.editedText,MediaFile.transcriptText"
S_ENGLISH = "TranscriptTurn.machineEnglish,TranscriptTurn.editedEnglish,TranscriptInsight.translationText"
PREVIEW_MAX = 1200

COLUMNS = [
    Column("file", "File", "text", "The recording's file name.", width=28, source="MediaFile.originalFilename"),
    Column("row", "Row", "text", "The whole transcript or one speaker turn, in the original or in English.", width=20),
    Column("segment", "Turn", "int", "The turn's place in the transcript (0 for the whole-transcript rows).", width=7, source="TranscriptTurn.position"),
    Column("start", "Start", "text", "Where the turn starts in the recording (m:ss), as text.", width=8, source="TranscriptTurn.startMs"),
    Column("end", "End", "text", "Where the turn ends (m:ss), as text.", width=8, source="TranscriptTurn.endMs"),
    Column("speaker", "Speaker", "text", "Who is speaking, as named in the portal.", width=18, source="TranscriptTurn.speakerKey,TranscriptSpeaker.name,TranscriptSpeaker.role"),
    Column("language", "Language", "text", "Language of the text in this row.", width=10, source="TranscriptTurn.language,MediaFile.transcriptLanguage,TranscriptVersion.languages"),
    Column("text", "Text", "longtext", "The turn with the speaker in bold, in the original or in English; the whole-transcript rows open the full transcript from their link.", width=60, source=f"{S_TEXT},{S_ENGLISH}"),
    Column("status", "Status", "status", "Transcript status on original rows; translation status on translation rows: Translated, Translating N%, Failed with the reason, or Not requested.", width=20, source="TranscriptTurn.translationStatus,TranscriptVersion.translationStatus,MediaFile.transcriptStatus,MediaFile.transcriptError"),
    Column("playFrom", "Open", "link", "Opens the recording in the portal's player from the start, after sign-in.", width=16),
    Column("docx", "Transcript (.docx)", "link", "The bilingual transcript as a Word file.", width=16),
    Column("srt", "Subtitles (.srt)", "link", "Subtitles with speakers.", width=14),
    Column("attachedTo", "Attached to", "text", "The record the recording belongs to.", width=24, source="MediaFile.linkedRecordType,MediaFile.linkedRecordId"),
    Column("recordedAt", "Recorded on", "datetime", "When the recording was made (IST).", source="MediaFile.recordedAt"),
    Column("edited", "Edited", "bool", "Whether a person corrected this text.", source="TranscriptTurn.editedAt"),
    Column("recordId", "Record ID", "text", "The turn's identifier on turn rows and the recording's on the whole-transcript rows; the Provenance sheet lists their technical details under it.", width=28, tech=False, source="TranscriptTurn.id,MediaFile.id"),
    Column("editedBy", "Edited by", "text", "Who corrected it.", tech=True, source="TranscriptTurn.editedById"),
    Column("editedAt", "Edited on", "datetime", "When it was corrected.", tech=True, source="TranscriptTurn.editedAt"),
    Column("model", "Translation model", "text", "The model that translated this turn.", hidden=True, group="details", source="TranscriptTurn.translationModel"),
    Column("revision", "Revision", "int", "How many times this turn changed.", hidden=True, group="details", source="TranscriptTurn.revision"),
    Column("turnCreated", "Turn created on", "datetime", "When the turn was first written.", hidden=True, group="details", source="TranscriptTurn.createdAt"),
    Column("turnUpdated", "Turn updated on", "datetime", "When the turn last changed.", hidden=True, group="details", source="TranscriptTurn.updatedAt"),
    Column("mediaId", "Media ID", "id", "The recording's identifier.", hidden=True, group="ids", source="TranscriptTurn.mediaFileId"),
    Column("versionId", "Version ID", "id", "The transcript version.", hidden=True, group="ids", source="TranscriptTurn.versionId"),
]


@dataclass
class Turn:
    speaker: str
    start: int
    end: int
    language: Any
    text: str = ""
    english_text: str = ""
    missing: str | None = None
    already: bool = True
    edited: bool = False
    edited_english: bool = False
    edited_by: Any = None
    edited_at: Any = None
    model: str | None = None
    revision: int = 0
    created: Any = None
    updated: Any = None
    turn_id: str = ""

    @property
    def english(self) -> bool:
        return bool(self.english_text)


def _v(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def _type(m: Any) -> str:
    return str(getattr(_v(m, "mediaType"), "value", _v(m, "mediaType")) or "").upper().split(".")[-1]


def _labels(rows: list[dict[str, Any]], names: dict[str, dict[str, Any]]) -> dict[str, str]:
    labels: dict[str, str] = {}
    for row in rows:
        key = row.get("speakerKey") or ""
        if key in labels:
            continue
        mapped = names.get(key)
        if mapped and mapped.get("name"):
            labels[key] = f"{mapped['name']} (researcher)" if mapped.get("role") == "RESEARCHER" else str(mapped["name"])
        else:
            labels[key] = f"Speaker {len(labels) + 1}"
    return labels


def _english(row: dict[str, Any], text: str) -> str | None:
    if row.get("editedEnglish") is not None:
        return row["editedEnglish"]
    if row.get("translationStatus") == "ALREADY_ENGLISH":
        return text
    return row.get("machineEnglish")


def attached_to(store: tables.Store, m: Any) -> str:
    for fk, model in (("productId", "ProductDocumentation"), ("toolId", "ToolDocumentation"), ("questionnaireInterviewId", "QuestionnaireInterview"), ("artisanId", "Artisan"), ("workshopId", "Workshop"), ("craftId", "Craft"), ("designWorkshopId", "DesignWorkshop")):
        value = _v(m, fk)
        if value:
            label = tables.schema.humanize(model if model != "ProductDocumentation" else "Product").replace("documentation", "").strip()
            if model == "ToolDocumentation":
                label = "Tool"
            if model == "QuestionnaireInterview":
                label = "Sitting"
            return f"{label}: {store.name(model, value) or value}"
    tag = str(_v(m, "linkedRecordType") or "")
    return tag.capitalize() if tag else ""


def turns_of(rows: list[dict[str, Any]], labels: dict[str, str], pending: str) -> list[Turn]:
    turns: list[Turn] = []
    for row in rows:
        text = row["editedText"] if row.get("editedText") is not None else (row.get("machineText") or "")
        english = _english(row, text) or ""
        turn = Turn(
            speaker=labels.get(row.get("speakerKey") or "", ""),
            start=int(row.get("startMs") or 0),
            end=int(row.get("endMs") or 0),
            language=row.get("language"),
            text=text,
            english_text=english,
            already=row.get("translationStatus") == "ALREADY_ENGLISH",
            edited=row.get("editedText") is not None,
            edited_english=row.get("editedEnglish") is not None,
            edited_by=row.get("editedById") if row.get("editedText") is not None else None,
            edited_at=row.get("editedAt") if row.get("editedText") is not None else None,
            model=str(row["translationModel"]) if row.get("translationModel") else None,
            revision=int(row.get("revision") or 0),
            created=row.get("createdAt"),
            updated=row.get("updatedAt"),
            turn_id=str(row["id"]),
        )
        if not english:
            if row.get("translationStatus") == "FAILED":
                turn.missing = pending if pending.startswith("Failed") else "Failed: the translation service did not return this segment."
            else:
                turn.missing = pending
        turns.append(turn)
    return turns


def clock(ms: Any) -> str:
    total = max(0, int(ms or 0)) // 1000
    hours, minutes, seconds = total // 3600, total % 3600 // 60, total % 60
    return f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes}:{seconds:02d}"


def turn_runs(speaker: str, words: str) -> Rich | str:
    if not speaker:
        return words
    if not (words or "").strip():
        return speaker
    return Rich(((speaker, True), (f"  {words}", False)))


def preview(lines: list[tuple[str, int, str]]) -> Rich | str:
    runs: list[tuple[str, bool]] = []
    used = 0
    for speaker, start, words in lines:
        if used >= PREVIEW_MAX:
            break
        if runs:
            runs.append(("   ", False))
        if speaker:
            runs.append((speaker, True))
            piece = f"  {clock(start)}  {words}"
        else:
            piece = f"{clock(start)}  {words}"
        runs.append((piece, False))
        used += len(speaker) + len(piece) + 3
    if not runs:
        return ""
    if not any(bold for _t, bold in runs):
        return "".join(t for t, _b in runs)
    return Rich(tuple(runs))


class TranscriptsPlugin:
    key = "transcripts"
    title = "Transcripts"
    order = 80
    option = None
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        return [(self.key, COLUMNS, self.title)]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        store = await tables.shared_store(ctx)
        db = ctx.shared.get("db")
        if db is None:
            from app.core.db import db as default_db

            db = default_db
        media = [m for m in store.rows.get("MediaFile", ()) if _type(m) in ("AUDIO", "VIDEO")]
        states = ctx.shared.get("transcriptStates")
        if states is None:
            states = await transcript_status.states(db, media, store.withheld)
            ctx.shared["transcriptStates"] = states
        media_ids = [str(_v(m, "id")) for m in media]
        size = sum(int(_v(m, "sizeBytes") or 0) for m in media)
        return [
            Sheet(
                key=self.key,
                name=self.title,
                columns=COLUMNS,
                rows=self._rows(ctx, db, store, media, states),
                color=tables.MEDIA,
                description="Every recording: a whole-transcript row that opens the full transcript, then each speaker turn in the original with its English translation directly beneath.",
                row_pairs=True,
                media_ids=media_ids,
                media_bytes=size,
                count=None,
                link_bound=3 * len(media) if ctx.options.links else 0,
                record_key="recordId",
            )
        ]

    async def _rows(self, ctx: ReportContext, db: Any, store: tables.Store, media: list[Any], states: dict[str, Any]) -> AsyncIterator[dict[str, Any]]:
        t = ctx.t
        links = ctx.options.links
        wanted = query_words(ctx.options.q) if ctx.options.rows == "filtered" else []
        original_label = t("row.original", "Original")
        english_label = t("row.translation", "English translation")
        summary_label = t("row.summary", "Whole transcript")
        for start in range(0, len(media), CHUNK):
            chunk = media[start : start + CHUNK]
            ids = [str(_v(m, "id")) for m in chunk if str(_v(m, "id")) not in store.withheld]
            texts: dict[str, dict[str, Any]] = {}
            versions: dict[str, dict[str, Any]] = {}
            turns: dict[str, list[dict[str, Any]]] = {}
            speakers: dict[str, dict[str, dict[str, Any]]] = {}
            manual: dict[str, str] = {}
            if ids:
                texts = {str(r["id"]): r for r in await db.query_raw(_MEDIA_SQL, ids)}
                versions = {str(r["mediaFileId"]): r for r in await db.query_raw(_VERSIONS_SQL, ids)}
                if versions:
                    for row in await db.query_raw(_TURNS_SQL, [str(v["id"]) for v in versions.values()]):
                        turns.setdefault(str(row["mediaFileId"]), []).append(row)
                for row in await db.query_raw(_SPEAKERS_SQL, ids):
                    speakers.setdefault(str(row["mediaFileId"]), {})[str(row["speakerKey"])] = row
                for row in await db.query_raw(_MANUAL_SQL, ids):
                    if str(row.get("translationText") or "").strip():
                        manual[str(row["mediaFileId"])] = str(row["translationText"]).strip()
            for m in chunk:
                mid = str(_v(m, "id"))
                state = states.get(mid)
                rows = turns.pop(mid, None) or []
                version = versions.get(mid)
                labels = _labels(rows, speakers.get(mid, {}))
                translation_status = state.translation if state else "Not requested"
                full = ctx.links.transcript_file(mid, "docx") if links and mid not in store.withheld else None
                base = {
                    "file": str(_v(m, "originalFilename") or mid),
                    "attachedTo": attached_to(store, m),
                    "recordedAt": _v(m, "recordedAt") or _v(m, "createdAt"),
                    ROW_FULL: full,
                }
                segments = turns_of(rows, labels, translation_status) if rows else []
                models = list(dict.fromkeys(turn.model for turn in segments if turn.model))
                pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
                for index, turn in enumerate(segments, start=1):
                    common = {
                        **base,
                        ROW_PAIR: f"{mid}:{index}",
                        "segment": index,
                        "start": clock(turn.start),
                        "end": clock(turn.end),
                        "speaker": turn.speaker,
                        "recordId": turn.turn_id,
                        "model": turn.model if len(models) > 1 else None,
                        "revision": turn.revision if turn.revision > 1 else None,
                        "turnCreated": turn.created,
                        "turnUpdated": turn.updated,
                    }
                    if turn.english and turn.missing is None:
                        line_status = "Already English" if turn.already else "Translated"
                    else:
                        line_status = turn.missing or translation_status
                    pairs.append(
                        (
                            {
                                **common,
                                ROW_KIND: "original",
                                "row": original_label,
                                "language": turn.language,
                                "text": turn_runs(turn.speaker, turn.text),
                                "status": transcript_status.localise(t, "Ready"),
                                "edited": True if turn.edited else None,
                                "editedBy": store.name("User", turn.edited_by) if turn.edited else None,
                                "editedAt": turn.edited_at if turn.edited else None,
                            },
                            {
                                **common,
                                ROW_KIND: "translation",
                                "row": english_label,
                                "language": "English",
                                "text": turn_runs(turn.speaker, turn.english_text) if turn.english_text else "",
                                "status": transcript_status.localise(t, line_status),
                                "edited": True if turn.edited_english else None,
                            },
                        )
                    )
                if segments:
                    original_lines = [(turn.speaker, turn.start, turn.text) for turn in segments]
                    english_lines = []
                    for turn in segments:
                        if turn.english and turn.missing is None:
                            words = turn.english_text
                        elif turn.english:
                            words = f"{turn.english_text} ({transcript_status.localise(t, turn.missing or translation_status)})"
                        else:
                            words = f"({transcript_status.localise(t, turn.missing or translation_status)})"
                        english_lines.append((turn.speaker, turn.start, words))
                    whole_original: Any = preview(original_lines)
                    whole_english: Any = preview(english_lines)
                else:
                    whole_original = str((texts.get(mid) or {}).get("transcriptText") or "").strip()
                    whole_english = manual.get(mid, "")
                    if whole_english:
                        translation_status = "Translated"
                languages = ", ".join(state.languages) if state and state.languages else str((texts.get(mid) or {}).get("transcriptLanguage") or "")
                summary_status = transcript_status.localise(t, state.transcript if state else "Not requested")
                english_status = transcript_status.localise(t, translation_status if (state is None or state.ready) else (state.transcript if state else "Not requested"))
                if segments and all(turn.english and turn.missing is None for turn in segments):
                    english_status = transcript_status.localise(t, "Translated")
                summary = {
                    **base,
                    ROW_KIND: "summary",
                    ROW_PAIR: f"{mid}:summary",
                    "row": summary_label,
                    "segment": 0,
                    "start": clock(0),
                    "end": clock(int(version["durationMs"]) if version and version.get("durationMs") else (state.duration_ms if state else 0)),
                    "speaker": "; ".join(dict.fromkeys(labels.values())),
                    "language": languages,
                    "text": whole_original,
                    "status": summary_status,
                    "recordId": mid,
                    "versionId": str(version["id"]) if version else None,
                    "model": ", ".join(models) or None,
                }
                summary_english = {
                    **summary,
                    ROW_KIND: "summary_translation",
                    "row": f"{summary_label} · {english_label}",
                    "language": "English",
                    "text": whole_english,
                    "status": english_status,
                }
                if links:
                    summary["playFrom"] = Link(t("link.openRecording", "Open recording"), ctx.links.play_from(mid, 0))
                    if state is not None and state.ready:
                        summary["srt"] = Link(t("link.srt", "Subtitles (.srt)"), ctx.links.transcript_file(mid, "srt"))
                        summary_english["docx"] = Link(t("link.docx", "Transcript (.docx)"), ctx.links.transcript_file(mid, "docx"))
                for first, second in [(summary, summary_english), *pairs]:
                    if wanted and not _pair_matches(first, second, wanted):
                        continue
                    yield first
                    yield second


def _pair_matches(first: dict[str, Any], second: dict[str, Any], wanted: list[str]) -> bool:
    text = " ".join(str(v.text if isinstance(v, (Link, Rich)) else v) for row in (first, second) for k, v in row.items() if not k.startswith("_") and v is not None).lower()
    return all(w in text for w in wanted)


register(TranscriptsPlugin())
