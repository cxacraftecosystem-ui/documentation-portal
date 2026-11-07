from __future__ import annotations

import logging
import math
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

from app.services.datareport import tables, transcript_status
from app.services.datareport.contract import (
    ROW_DETAIL,
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

logger = logging.getLogger(__name__)

CHUNK = 100
PART_MAX = 30000
TURN_MAX = 12000
DETAIL_ROW_BYTES = 600

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
_DETAIL_SQL = (
    'SELECT count(*)::int AS turns, coalesce(sum(octet_length(coalesce(t."editedText", t."machineText", \'\')) '
    '+ octet_length(coalesce(t."editedEnglish", t."machineEnglish", \'\'))), 0)::bigint AS bytes '
    'FROM "TranscriptTurn" t JOIN "TranscriptVersion" v ON v."id" = t."versionId" '
    'WHERE v."mediaFileId" = ANY($1::text[]) AND v."supersededAt" IS NULL'
)

S_TEXT = "TranscriptTurn.machineText,TranscriptTurn.editedText,MediaFile.transcriptText"
S_ENGLISH = "TranscriptTurn.machineEnglish,TranscriptTurn.editedEnglish,TranscriptInsight.translationText"

COLUMNS = [
    Column("file", "File", "text", "The recording's file name.", width=28, source="MediaFile.originalFilename"),
    Column("row", "Row", "text", "The whole transcript or one speaker turn, in the original or in English.", width=20),
    Column("segment", "Turn", "int", "The turn's place in the transcript; consecutive segments by one speaker make one turn (0 for the whole-transcript rows).", width=7, source="TranscriptTurn.position"),
    Column("start", "Start", "text", "Where the turn starts in the recording (m:ss), as text.", width=8, source="TranscriptTurn.startMs"),
    Column("end", "End", "text", "Where the turn ends (m:ss), as text.", width=8, source="TranscriptTurn.endMs"),
    Column("speaker", "Speaker", "text", "Who is speaking, as named in the portal.", width=18, source="TranscriptTurn.speakerKey,TranscriptSpeaker.name,TranscriptSpeaker.role"),
    Column("language", "Language", "text", "Language of the text in this row.", width=10, source="TranscriptTurn.language,MediaFile.transcriptLanguage,TranscriptVersion.languages"),
    Column("text", "Text", "longtext", "The dialogue: each turn on its own line with the speaker in bold, in the original or in English.", width=80, source=f"{S_TEXT},{S_ENGLISH}", wrap=True),
    Column("status", "Status", "status", "Transcript status on original rows; translation status on translation rows: Translated, Translating N%, Failed with the reason, or Not requested.", width=20, source="TranscriptTurn.translationStatus,TranscriptVersion.translationStatus,MediaFile.transcriptStatus,MediaFile.transcriptError"),
    Column("playFrom", "Open", "link", "Opens the recording in the portal's player from the start, after sign-in.", width=16),
    Column("docx", "Transcript (.docx)", "link", "The bilingual transcript as a Word file.", width=16),
    Column("srt", "Subtitles (.srt)", "link", "Subtitles with speakers.", width=14),
    Column("attachedTo", "Attached to", "text", "The record the recording belongs to.", width=24, source="MediaFile.linkedRecordType,MediaFile.linkedRecordId"),
    Column("recordedAt", "Recorded on", "datetime", "When the recording was made (IST).", source="MediaFile.recordedAt"),
    Column("edited", "Edited", "bool", "Whether a person corrected this text.", source="TranscriptTurn.editedAt"),
    Column("editedBy", "Edited by", "text", "Who corrected it.", source="TranscriptTurn.editedById"),
    Column("editedAt", "Edited on", "datetime", "When it was corrected.", source="TranscriptTurn.editedAt"),
    Column("model", "Translation model", "text", "The model that translated this turn.", hidden=True, group="details", source="TranscriptTurn.translationModel"),
    Column("revision", "Revision", "int", "How many times this turn changed.", hidden=True, group="details", source="TranscriptTurn.revision"),
    Column("turnCreated", "Segment created on", "datetime", "When the turn's first segment was written.", hidden=True, group="details", source="TranscriptTurn.createdAt"),
    Column("turnUpdated", "Segment updated on", "datetime", "When the turn last changed.", hidden=True, group="details", source="TranscriptTurn.updatedAt"),
    Column("mediaId", "Media ID", "id", "The recording's identifier.", hidden=True, group="ids", source="TranscriptTurn.mediaFileId"),
    Column("versionId", "Version ID", "id", "The transcript version.", hidden=True, group="ids", source="TranscriptTurn.versionId"),
    Column("turnId", "Segment ID", "id", "The identifiers of the segments in this turn.", hidden=True, group="ids", source="TranscriptTurn.id"),
]


@dataclass
class Turn:
    speaker: str
    start: int
    end: int
    language: Any
    original: list[str] = field(default_factory=list)
    english: list[str] = field(default_factory=list)
    missing: str | None = None
    already: bool = True
    edited: bool = False
    edited_english: bool = False
    edited_by: Any = None
    edited_at: Any = None
    models: list[str] = field(default_factory=list)
    revision: int = 0
    created: Any = None
    updated: Any = None
    ids: list[str] = field(default_factory=list)

    @property
    def text(self) -> str:
        return " ".join(part for part in self.original if part)

    @property
    def english_text(self) -> str:
        return " ".join(part for part in self.english if part)


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


def _later(a: Any, b: Any) -> Any:
    if a is None:
        return b
    if b is None:
        return a
    try:
        return max(a, b)
    except TypeError:
        return b


def _earlier(a: Any, b: Any) -> Any:
    if a is None:
        return b
    if b is None:
        return a
    try:
        return min(a, b)
    except TypeError:
        return a


def group_turns(rows: list[dict[str, Any]], labels: dict[str, str], pending: str) -> list[Turn]:
    turns: list[Turn] = []
    for row in rows:
        text = row["editedText"] if row.get("editedText") is not None else (row.get("machineText") or "")
        english = _english(row, text)
        key = row.get("speakerKey") or ""
        speaker = labels.get(key, "")
        current = turns[-1] if turns and turns[-1].speaker == speaker else None
        if current is None:
            current = Turn(speaker=speaker, start=int(row.get("startMs") or 0), end=int(row.get("endMs") or 0), language=row.get("language"))
            turns.append(current)
        current.end = max(current.end, int(row.get("endMs") or 0))
        current.original.append(text)
        if english:
            current.english.append(english)
        elif current.missing is None:
            if row.get("translationStatus") == "FAILED":
                current.missing = pending if pending.startswith("Failed") else "Failed: the translation service did not return this segment."
            else:
                current.missing = pending
        if row.get("translationStatus") != "ALREADY_ENGLISH":
            current.already = False
        if row.get("editedText") is not None:
            current.edited = True
            current.edited_by = row.get("editedById") or current.edited_by
            current.edited_at = _later(current.edited_at, row.get("editedAt"))
        if row.get("editedEnglish") is not None:
            current.edited_english = True
        model = row.get("translationModel")
        if model and model not in current.models:
            current.models.append(str(model))
        current.revision = max(current.revision, int(row.get("revision") or 0))
        current.created = _earlier(current.created, row.get("createdAt"))
        current.updated = _later(current.updated, row.get("updatedAt"))
        current.ids.append(str(row["id"]))
    out: list[Turn] = []
    for turn in turns:
        out.extend(_split_long(turn))
    return out


def _pieces(text: str, n: int) -> list[str]:
    if n <= 1:
        return [text]
    size = math.ceil(len(text) / n) or 1
    return [text[i : i + size] for i in range(0, len(text), size)][:n] or [""]


def _split_long(turn: Turn) -> list[Turn]:
    original, english = turn.text, turn.english_text
    n = max(math.ceil(len(original) / TURN_MAX), math.ceil(len(english) / TURN_MAX), 1)
    if n == 1:
        return [turn]
    first, second = _pieces(original, n), _pieces(english, n)
    out = []
    for index in range(n):
        piece = Turn(**{**turn.__dict__, "original": [first[index] if index < len(first) else ""], "english": [second[index] if index < len(second) else ""], "models": list(turn.models), "ids": list(turn.ids)})
        out.append(piece)
    return out


def clock(ms: Any) -> str:
    total = max(0, int(ms or 0)) // 1000
    hours, minutes, seconds = total // 3600, total % 3600 // 60, total % 60
    return f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes}:{seconds:02d}"


def line_runs(speaker: str, start: int, words: str) -> list[tuple[str, bool]]:
    runs: list[tuple[str, bool]] = []
    if speaker:
        runs.append((speaker, True))
        runs.append((f"  {clock(start)}  {words}", False))
    else:
        runs.append((f"{clock(start)}  {words}", False))
    return runs


def turn_runs(speaker: str, words: str) -> Rich | str:
    if not speaker:
        return words
    return Rich(((speaker, True), (f"  {words}", False)))


def dialogue_parts(lines: list[tuple[list[tuple[str, bool]], list[tuple[str, bool]]]]) -> list[tuple[Rich, Rich]]:
    parts: list[tuple[list[tuple[str, bool]], list[tuple[str, bool]]]] = []
    size_o = size_e = 0
    for original, english in lines:
        lo = sum(len(t) for t, _b in original) + 1
        le = sum(len(t) for t, _b in english) + 1
        if not parts or size_o + lo > PART_MAX or size_e + le > PART_MAX:
            parts.append(([], []))
            size_o = size_e = 0
        mine_o, mine_e = parts[-1]
        if mine_o:
            mine_o.append(("\n", False))
            mine_e.append(("\n", False))
        mine_o.extend(original)
        mine_e.extend(english)
        size_o += lo
        size_e += le
    return [(Rich(tuple(o)), Rich(tuple(e))) for o, e in parts]


def _plain_parts(original: str, english: str) -> list[tuple[str, str]]:
    n = max(math.ceil(len(original) / PART_MAX), math.ceil(len(english) / PART_MAX), 1)
    first, second = _pieces(original, n), _pieces(english, n)
    return [(first[i] if i < len(first) else "", second[i] if i < len(second) else "") for i in range(n)]


async def detail_bytes(db: Any, ids: list[str]) -> int:
    total = 0
    for start in range(0, len(ids), 1000):
        try:
            found = await db.query_raw(_DETAIL_SQL, ids[start : start + 1000])
        except Exception:
            logger.exception("transcript size estimate failed")
            return 0
        for row in found or []:
            total += int(row.get("turns") or 0) * 2 * DETAIL_ROW_BYTES + int(row.get("bytes") or 0)
    return total


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
        estimate = ctx.shared.get("transcriptDetailBytes")
        if estimate is None:
            estimate = await detail_bytes(db, [mid for mid in media_ids if mid not in store.withheld])
            ctx.shared["transcriptDetailBytes"] = estimate
        return [
            Sheet(
                key=self.key,
                name=self.title,
                columns=COLUMNS,
                rows=self._rows(ctx, db, store, media, states),
                color=tables.MEDIA,
                description="Every recording: the whole transcript as dialogue with its English translation beneath, then each speaker turn in the original followed by its translation.",
                row_pairs=True,
                media_ids=media_ids,
                media_bytes=size,
                count=None,
                link_bound=3 * len(media) if ctx.options.links else 0,
                detail_bytes=int(estimate or 0),
            )
        ]

    async def _rows(self, ctx: ReportContext, db: Any, store: tables.Store, media: list[Any], states: dict[str, Any]) -> AsyncIterator[dict[str, Any]]:
        t = ctx.t
        links = ctx.options.links
        wanted = query_words(ctx.options.q) if ctx.options.rows == "filtered" else []
        original_label = t("row.original", "Original")
        english_label = t("row.translation", "English translation")
        summary_label = t("row.summary", "Whole transcript")
        part_label = t("row.part", "part {n} of {total}")
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
                base = {
                    "file": str(_v(m, "originalFilename") or mid),
                    "attachedTo": attached_to(store, m),
                    "recordedAt": _v(m, "recordedAt") or _v(m, "createdAt"),
                    "mediaId": mid,
                    "versionId": str(version["id"]) if version else None,
                }
                grouped = group_turns(rows, labels, translation_status) if rows else []
                pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
                for index, turn in enumerate(grouped, start=1):
                    common = {
                        **base,
                        ROW_DETAIL: True,
                        ROW_PAIR: f"{mid}:{index}",
                        "segment": index,
                        "start": clock(turn.start),
                        "end": clock(turn.end),
                        "speaker": turn.speaker,
                        "model": ", ".join(turn.models) or None,
                        "revision": turn.revision or None,
                        "turnCreated": turn.created,
                        "turnUpdated": turn.updated,
                        "turnId": ", ".join(turn.ids),
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
                                "edited": turn.edited,
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
                                "edited": turn.edited_english,
                            },
                        )
                    )
                if grouped:
                    lines = []
                    for turn in grouped:
                        if turn.english and turn.missing is None:
                            words = turn.english_text
                        elif turn.english:
                            words = f"{turn.english_text} ({transcript_status.localise(t, turn.missing or translation_status)})"
                        else:
                            words = f"({transcript_status.localise(t, turn.missing or translation_status)})"
                        lines.append((line_runs(turn.speaker, turn.start, turn.text), line_runs(turn.speaker, turn.start, words)))
                    wholes: list[tuple[Any, Any]] = list(dialogue_parts(lines))
                    has_english = any(turn.english for turn in grouped)
                else:
                    legacy = str((texts.get(mid) or {}).get("transcriptText") or "").strip()
                    english_whole = manual.get(mid, "")
                    wholes = list(_plain_parts(legacy, english_whole)) if (legacy or english_whole) else [("", "")]
                    has_english = bool(english_whole)
                    if english_whole:
                        translation_status = "Translated"
                languages = ", ".join(state.languages) if state and state.languages else str((texts.get(mid) or {}).get("transcriptLanguage") or "")
                summary_status = transcript_status.localise(t, state.transcript if state else "Not requested")
                english_status = transcript_status.localise(t, translation_status if (state is None or state.ready) else (state.transcript if state else "Not requested"))
                if has_english and grouped and all(turn.english and turn.missing is None for turn in grouped):
                    english_status = transcript_status.localise(t, "Translated")
                total = len(wholes)
                summary_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
                for number, (whole_original, whole_english) in enumerate(wholes, start=1):
                    suffix = f" ({part_label.format(n=number, total=total)})" if total > 1 else ""
                    summary = {
                        **base,
                        ROW_KIND: "summary",
                        ROW_PAIR: f"{mid}:summary:{number}",
                        "row": f"{summary_label}{suffix}",
                        "segment": 0,
                        "start": clock(0),
                        "end": clock(int(version["durationMs"]) if version and version.get("durationMs") else (state.duration_ms if state else 0)),
                        "speaker": "; ".join(dict.fromkeys(labels.values())),
                        "language": languages,
                        "text": whole_original,
                        "status": summary_status,
                    }
                    summary_english = {
                        **summary,
                        ROW_KIND: "summary_translation",
                        "row": f"{summary_label} · {english_label}{suffix}",
                        "language": "English",
                        "text": whole_english,
                        "status": english_status,
                    }
                    if links and number == 1:
                        summary["playFrom"] = Link(t("link.openRecording", "Open recording"), ctx.links.play_from(mid, 0))
                        if state is not None and state.ready:
                            summary["srt"] = Link(t("link.srt", "Subtitles (.srt)"), ctx.links.transcript_file(mid, "srt"))
                            summary_english["docx"] = Link(t("link.docx", "Transcript (.docx)"), ctx.links.transcript_file(mid, "docx"))
                    summary_pairs.append((summary, summary_english))
                for first, second in [*summary_pairs, *pairs]:
                    if wanted and not _pair_matches(first, second, wanted):
                        continue
                    yield first
                    yield second


def _pair_matches(first: dict[str, Any], second: dict[str, Any], wanted: list[str]) -> bool:
    text = " ".join(str(v.text if isinstance(v, (Link, Rich)) else v) for row in (first, second) for k, v in row.items() if not k.startswith("_") and v is not None).lower()
    return all(w in text for w in wanted)


register(TranscriptsPlugin())
