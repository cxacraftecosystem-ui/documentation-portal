from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any

from app.services.datareport import tables, transcript_status
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

CHUNK = 100
SUMMARY_MAX = 30000

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

COLUMNS = [
    Column("file", "File", "link", "The recording; opens it in the portal's player after sign-in.", width=28, source="MediaFile.originalFilename"),
    Column("row", "Row", "text", "Original speech, its English translation, or the whole transcript.", width=18),
    Column("segment", "Segment", "int", "The segment's place in the transcript (0 for the whole-transcript rows).", width=9, source="TranscriptTurn.position"),
    Column("start", "Start", "duration_ms", "Where the segment starts in the recording (mm:ss).", source="TranscriptTurn.startMs"),
    Column("end", "End", "duration_ms", "Where the segment ends (mm:ss).", source="TranscriptTurn.endMs"),
    Column("speaker", "Speaker", "text", "Who is speaking, as named in the portal.", width=18, source="TranscriptTurn.speakerKey,TranscriptSpeaker.name,TranscriptSpeaker.role"),
    Column("language", "Language", "text", "Language of the text in this row.", width=12, source="TranscriptTurn.language,MediaFile.transcriptLanguage,TranscriptVersion.languages"),
    Column("text", "Text", "longtext", "What was said (original rows) or its English translation (translation rows).", width=70, source=f"{S_TEXT},{S_ENGLISH}", wrap=True),
    Column("status", "Status", "status", "Transcript status on original rows; translation status on translation rows: Translated, Translating N%, Failed with the reason, or Not requested.", width=22, source="TranscriptTurn.translationStatus,TranscriptVersion.translationStatus,MediaFile.transcriptStatus,MediaFile.transcriptError"),
    Column("playFrom", "Play from", "link", "Opens the recording in the portal and plays from this moment.", width=14),
    Column("docx", "Transcript (.docx)", "link", "The bilingual transcript as a Word file.", width=16),
    Column("srt", "Subtitles (.srt)", "link", "Subtitles with speakers.", width=14),
    Column("attachedTo", "Attached to", "text", "The record the recording belongs to.", width=24, source="MediaFile.linkedRecordType,MediaFile.linkedRecordId"),
    Column("recordedAt", "Recorded on", "datetime", "When the recording was made (IST).", source="MediaFile.recordedAt"),
    Column("edited", "Edited", "bool", "Whether a person corrected this text.", source="TranscriptTurn.editedAt"),
    Column("editedBy", "Edited by", "text", "Who corrected it.", source="TranscriptTurn.editedById"),
    Column("editedAt", "Edited on", "datetime", "When it was corrected.", source="TranscriptTurn.editedAt"),
    Column("model", "Translation model", "text", "The model that translated this segment.", hidden=True, group="details", source="TranscriptTurn.translationModel"),
    Column("revision", "Revision", "int", "How many times this segment changed.", hidden=True, group="details", source="TranscriptTurn.revision"),
    Column("turnCreated", "Segment created on", "datetime", "When the segment was first written.", hidden=True, group="details", source="TranscriptTurn.createdAt"),
    Column("turnUpdated", "Segment updated on", "datetime", "When the segment last changed.", hidden=True, group="details", source="TranscriptTurn.updatedAt"),
    Column("mediaId", "Media ID", "id", "The recording's identifier.", hidden=True, group="ids", source="TranscriptTurn.mediaFileId"),
    Column("versionId", "Version ID", "id", "The transcript version.", hidden=True, group="ids", source="TranscriptTurn.versionId"),
    Column("turnId", "Segment ID", "id", "The segment's identifier.", hidden=True, group="ids", source="TranscriptTurn.id"),
]


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


def _clip(text: str) -> str:
    return text if len(text) <= SUMMARY_MAX else text[: SUMMARY_MAX - 1] + "…"


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
                description="Every recording: the whole transcript, then each segment's original followed by its English translation.",
                row_pairs=True,
                media_ids=media_ids,
                media_bytes=size,
                count=None,
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
                name = str(_v(m, "originalFilename") or mid)
                base = {
                    "file": Link(name, ctx.links.open_media(mid)) if links else name,
                    "attachedTo": attached_to(store, m),
                    "recordedAt": _v(m, "recordedAt") or _v(m, "createdAt"),
                    "mediaId": mid,
                }
                if links and state is not None and state.ready:
                    base["docx"] = Link(t("link.docx", "Transcript (.docx)"), ctx.links.transcript_file(mid, "docx"))
                    base["srt"] = Link(t("link.srt", "Subtitles (.srt)"), ctx.links.transcript_file(mid, "srt"))
                rows = turns.get(mid) or []
                version = versions.get(mid)
                labels = _labels(rows, speakers.get(mid, {}))
                segments: list[tuple[dict[str, Any], dict[str, Any]]] = []
                full_original: list[str] = []
                full_english: list[str] = []
                translation_status = state.translation if state else "Not requested"
                if rows:
                    for row in rows:
                        text = row["editedText"] if row.get("editedText") is not None else (row.get("machineText") or "")
                        english = _english(row, text)
                        speaker = labels.get(row.get("speakerKey") or "", "")
                        full_original.append(f"{speaker}: {text}" if speaker else text)
                        if english:
                            full_english.append(f"{speaker}: {english}" if speaker else english)
                        common = {
                            **base,
                            "segment": int(row["position"]) + 1,
                            "start": int(row.get("startMs") or 0),
                            "end": int(row.get("endMs") or 0),
                            "speaker": speaker,
                            "versionId": str(row["versionId"]),
                            "turnId": str(row["id"]),
                            "model": row.get("translationModel"),
                            "revision": row.get("revision"),
                            "turnCreated": row.get("createdAt"),
                            "turnUpdated": row.get("updatedAt"),
                            ROW_PAIR: str(row["id"]),
                        }
                        if links:
                            common["playFrom"] = Link(t("link.playFrom", "Play from {time}").format(time=_clock(row.get("startMs"))), ctx.links.play_from(mid, int(row.get("startMs") or 0)))
                        original = {
                            **common,
                            ROW_KIND: "original",
                            "row": original_label,
                            "language": row.get("language"),
                            "text": text,
                            "status": transcript_status.localise(t, "Ready"),
                            "edited": row.get("editedText") is not None,
                            "editedBy": store.name("User", row.get("editedById")) if row.get("editedText") is not None else None,
                            "editedAt": row.get("editedAt") if row.get("editedText") is not None else None,
                        }
                        if row.get("translationStatus") == "ALREADY_ENGLISH":
                            line_status = "Already English"
                        elif english:
                            line_status = "Translated"
                        elif row.get("translationStatus") == "FAILED":
                            line_status = translation_status if translation_status.startswith("Failed") else "Failed: the translation service did not return this segment."
                        else:
                            line_status = translation_status
                        translated = {
                            **common,
                            ROW_KIND: "translation",
                            "row": english_label,
                            "language": "English",
                            "text": english or "",
                            "status": transcript_status.localise(t, line_status),
                            "edited": row.get("editedEnglish") is not None,
                        }
                        segments.append((original, translated))
                else:
                    legacy = str((texts.get(mid) or {}).get("transcriptText") or "")
                    if legacy.strip():
                        full_original.append(legacy.strip())
                english_whole = "\n".join(full_english) if full_english else manual.get(mid, "")
                if manual.get(mid) and not full_english:
                    translation_status = "Translated"
                languages = ", ".join(state.languages) if state and state.languages else str((texts.get(mid) or {}).get("transcriptLanguage") or "")
                summary = {
                    **base,
                    ROW_KIND: "summary",
                    ROW_PAIR: f"{mid}:summary",
                    "row": summary_label,
                    "segment": 0,
                    "start": 0,
                    "end": int(version["durationMs"]) if version and version.get("durationMs") else (state.duration_ms if state else None),
                    "speaker": "; ".join(dict.fromkeys(labels.values())),
                    "language": languages,
                    "text": _clip("\n".join(full_original)),
                    "status": transcript_status.localise(t, state.transcript if state else "Not requested"),
                    "versionId": str(version["id"]) if version else None,
                }
                if links:
                    summary["playFrom"] = Link(t("link.play", "Play"), ctx.links.play_from(mid, 0))
                summary_english = {
                    **summary,
                    ROW_KIND: "summary_translation",
                    "row": f"{summary_label} · {english_label}",
                    "language": "English",
                    "text": _clip(english_whole),
                    "status": transcript_status.localise(t, translation_status if (state is None or state.ready) else (state.transcript if state else "Not requested")),
                }
                pairs = [(summary, summary_english), *segments]
                for first, second in pairs:
                    if wanted and not _pair_matches(first, second, wanted):
                        continue
                    yield first
                    yield second


def _pair_matches(first: dict[str, Any], second: dict[str, Any], wanted: list[str]) -> bool:
    text = " ".join(str(v.text if isinstance(v, Link) else v) for row in (first, second) for k, v in row.items() if not k.startswith("_") and v is not None).lower()
    return all(w in text for w in wanted)


def _clock(ms: Any) -> str:
    total = max(0, int(ms or 0)) // 1000
    hours, minutes, seconds = total // 3600, total % 3600 // 60, total % 60
    return f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes}:{seconds:02d}"


register(TranscriptsPlugin())
