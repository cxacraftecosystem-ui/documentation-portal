from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from app.services.field_voices import contract, items, people, scrub
from app.services.field_voices.items import Labels, Outcome, Subjects
from app.services.field_voices.sources import Sources, day_text, iso_moment, ist_day, load_sources
from app.services.field_voices.stages import StagePlacement
from app.transcription import languages as spoken_languages
from app.transcription.transcript import preview

__all__ = ["CANDIDATES_SQL", "TranscriptRows", "build", "candidates", "evaluate"]

TURNS = "TURNS"
TEXT = "TEXT"
TYPED = "TYPED"
EDITED = "EDITED"
MACHINE = "MACHINE"
ORIGINAL = "ORIGINAL"
UNDETERMINED = "und"
MAX_SEGMENT_CHARACTERS = 20_000
SPEAKER_ROLES = ("ARTISAN", "RESEARCHER", "OTHER")
MEDIA_TYPES = ("AUDIO", "VIDEO")
LANGUAGE_TAG = re.compile(r"^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")
NOT_LANGUAGES = frozenset({"multi", "mul", "auto", "mixed", "und", "zxx"})
TWO_LETTER = {
    "asm": "as", "ben": "bn", "eng": "en", "guj": "gu", "hin": "hi", "kan": "kn", "kas": "ks", "mal": "ml",
    "mar": "mr", "nep": "ne", "ori": "or", "ory": "or", "pan": "pa", "san": "sa", "snd": "sd", "tam": "ta",
    "tel": "te", "urd": "ur",
}
SENTENCE_END = re.compile(r"(?<=[.!?।॥])\s+")

CANDIDATES_SQL = (
    'SELECT m."id" FROM "MediaFile" m WHERE m."mediaType"::text IN (\'AUDIO\', \'VIDEO\') '
    'AND m."removedAt" IS NULL AND m."status"::text NOT IN (\'DRAFT\', \'REJECTED\') '
    "AND (btrim(COALESCE(m.\"transcriptText\", '')) <> '' OR EXISTS (SELECT 1 FROM \"TranscriptVersion\" v "
    'JOIN "TranscriptTurn" t ON t."versionId" = v."id" WHERE v."mediaFileId" = m."id" '
    'AND v."supersededAt" IS NULL)) ORDER BY m."id"'
)
RECORDING_SQL = (
    'SELECT r."id", r."mediaFileId", r."capturedAt", r."durationMs", r."removedAt", '
    'n."mediaFileId" AS "successor" FROM "QuestionnaireRecording" r '
    'LEFT JOIN LATERAL (SELECT x."mediaFileId" FROM "QuestionnaireRecording" x '
    'WHERE x."replacesRecordingId" = r."id" AND x."removedAt" IS NULL ORDER BY x."createdAt" DESC LIMIT 1) n '
    'ON TRUE WHERE r."mediaFileId" = ANY($1::text[])'
)
TARGET_SQL = (
    'SELECT t."recordingId", t."targetKey", COALESCE(t."sectionId", q."sectionId") AS "sectionId", '
    'COALESCE(t."askedQuestionId", t."questionLineageId") AS "questionId", '
    'COALESCE(s."code", q."sectionCode") AS "sectionCode", COALESCE(s."title", q."sectionTitle") AS "sectionTitle", '
    'q."prompt", q."displayCode", q."questionCode" FROM "QuestionnaireRecordingTarget" t '
    'LEFT JOIN "QuestionnaireQuestion" q ON q."id" = COALESCE(t."askedQuestionId", t."questionLineageId") '
    'LEFT JOIN "QuestionnaireSection" s ON s."id" = COALESCE(t."sectionId", q."sectionId") '
    'WHERE t."recordingId" = ANY($1::text[]) ORDER BY t."recordingId", t."targetKey"'
)
SUBJECT_SQL = (
    'SELECT "recordingId", "artisanId" FROM "QuestionnaireRecordingSubject" '
    'WHERE "recordingId" = ANY($1::text[]) ORDER BY "recordingId", "artisanId"'
)
VERSION_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "id", "mediaFileId", "version", "languages", "detectedLanguage", '
    '"durationMs" FROM "TranscriptVersion" WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL '
    'ORDER BY "mediaFileId", "version" DESC'
)
TURN_SQL = (
    'SELECT "id", "versionId", "mediaFileId", "position", "speakerKey", "startMs", "endMs", "language", '
    '"machineText", "editedText", "machineEnglish", "editedEnglish", "translationStatus" FROM "TranscriptTurn" '
    'WHERE "versionId" = ANY($1::text[]) ORDER BY "versionId", "position"'
)
SPEAKER_SQL = (
    'SELECT "mediaFileId", "speakerKey", "name", "role", "personId" FROM "TranscriptSpeaker" '
    'WHERE "mediaFileId" = ANY($1::text[]) ORDER BY "mediaFileId", "speakerKey"'
)
INSIGHT_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "mediaFileId", "translationStatus", "translationText", "editedAt" '
    'FROM "TranscriptInsight" WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL '
    'ORDER BY "mediaFileId", "version" DESC'
)


@dataclass
class TranscriptRows:
    recordings: dict[str, dict[str, Any]] = field(default_factory=dict)
    targets: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    subjects: dict[str, list[str]] = field(default_factory=dict)
    versions: dict[str, dict[str, Any]] = field(default_factory=dict)
    turns: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    speakers: dict[str, dict[str, dict[str, Any]]] = field(default_factory=dict)
    insights: dict[str, dict[str, Any]] = field(default_factory=dict)
    verdicts: dict[str, str] = field(default_factory=dict)


async def candidates(client: Any, placements: Iterable[StagePlacement]) -> list[str]:
    own = [str(row["id"]) for row in await client.query_raw(CANDIDATES_SQL)]
    typed = [p.media_id for p in placements if p.listed and (p.typed_transcript or "").strip()]
    return sorted(set(own) | set(typed))


def language_tag(value: Any) -> str:
    raw = str(value or "").strip().lower().replace("_", "-")
    if not raw:
        return UNDETERMINED
    head, _, rest = raw.partition("-")
    if head in TWO_LETTER:
        raw = TWO_LETTER[head] + (f"-{rest}" if rest else "")
    chosen = spoken_languages.choice_of(raw)
    if chosen and chosen not in (spoken_languages.AUTO, spoken_languages.MIXED):
        return chosen
    if raw in NOT_LANGUAGES or not LANGUAGE_TAG.match(raw):
        return UNDETERMINED
    return raw


def _int(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _grouped(rows: Iterable[dict[str, Any]], key: str) -> dict[str, list[dict[str, Any]]]:
    out: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        out.setdefault(str(row[key]), []).append(dict(row))
    return out


async def _query(client: Any, sql: str, ids: Iterable[str]) -> list[dict[str, Any]]:
    wanted = sorted({str(i) for i in ids if i})
    if not wanted:
        return []
    return [dict(row) for row in await client.query_raw(sql, wanted)]


async def _verdicts(client: Any, media_ids: list[str]) -> dict[str, str]:
    from app.questionnaire import ports

    details = await ports.consent_gate.verdict_details(media_ids, client=client)
    return {mid: str((details.get(mid) or {}).get("verdict") or "") for mid in media_ids}


async def load_rows(client: Any, sources: Sources) -> TranscriptRows:
    media_ids = sorted(sources.media)
    rows = TranscriptRows()
    for row in await _query(client, RECORDING_SQL, media_ids):
        rows.recordings[str(row["mediaFileId"])] = row
    recording_ids = [r["id"] for r in rows.recordings.values()]
    rows.targets = _grouped(await _query(client, TARGET_SQL, recording_ids), "recordingId")
    for row in await _query(client, SUBJECT_SQL, recording_ids):
        rows.subjects.setdefault(str(row["recordingId"]), []).append(str(row["artisanId"]))
    for row in await _query(client, VERSION_SQL, media_ids):
        rows.versions[str(row["mediaFileId"])] = row
    rows.turns = _grouped(await _query(client, TURN_SQL, [v["id"] for v in rows.versions.values()]), "mediaFileId")
    for row in await _query(client, SPEAKER_SQL, media_ids):
        rows.speakers.setdefault(str(row["mediaFileId"]), {})[str(row["speakerKey"])] = row
    for row in await _query(client, INSIGHT_SQL, media_ids):
        rows.insights[str(row["mediaFileId"])] = row
    rows.verdicts = await _verdicts(client, media_ids) if media_ids else {}
    return rows


@dataclass
class Segments:
    source: str
    version: int | None
    languages: list[str]
    detected: str
    segments: list[dict[str, Any]]
    speakers: list[dict[str, Any]]
    english: dict[str, Any] | None
    counts: dict[str, int]


def _split_long(text: str) -> list[str]:
    if len(text) <= MAX_SEGMENT_CHARACTERS:
        return [text]
    parts: list[str] = []
    current = ""
    for whole in SENTENCE_END.split(text):
        sentence = whole
        while len(sentence) > MAX_SEGMENT_CHARACTERS:
            if current:
                parts.append(current)
                current = ""
            parts.append(sentence[:MAX_SEGMENT_CHARACTERS])
            sentence = sentence[MAX_SEGMENT_CHARACTERS:]
        joined = f"{current} {sentence}".strip() if current else sentence
        if len(joined) > MAX_SEGMENT_CHARACTERS:
            parts.append(current)
            current = sentence
        else:
            current = joined
    if current:
        parts.append(current)
    return parts


def _scrubbed(text: str | None, held: scrub.HeldBack, counts: dict[str, int], kinds: set[str]) -> str | None:
    if text is None:
        return None
    result = scrub.scrub(text, held)
    for kind, n in result.counts.items():
        counts[kind] = counts.get(kind, 0) + n
    kinds.update(result.kinds)
    return result.text


def _english_of(turn: dict[str, Any], text: str) -> tuple[str | None, str | None]:
    if turn.get("editedEnglish") is not None:
        return turn["editedEnglish"], EDITED
    status = str(turn.get("translationStatus") or "")
    if status == "ALREADY_ENGLISH":
        return text, ORIGINAL
    if status == "TRANSLATED" and turn.get("machineEnglish") is not None:
        return turn["machineEnglish"], MACHINE
    return None, None


def _times(turn: dict[str, Any], duration: int | None) -> tuple[int | None, int | None]:
    start, end = _int(turn.get("startMs")), _int(turn.get("endMs"))
    if start is None or end is None or end <= start or start < 0:
        return None, None
    if duration is not None:
        if start >= duration:
            return None, None
        end = min(end, duration)
    return start, end


def _segment(
    seg_id: str, position: int, speaker: str | None, times: tuple[int | None, int | None], language: str,
    text: str, text_source: str, english: str | None, english_source: str | None, kinds: set[str],
) -> dict[str, Any]:
    return {
        "id": seg_id,
        "position": position,
        "speakerKey": speaker,
        "startMs": times[0],
        "endMs": times[1],
        "language": language,
        "text": text,
        "textSource": text_source,
        "english": english,
        "englishSource": english_source if english is not None else None,
        "scrubbed": [kind for kind in scrub.KINDS if kind in kinds],
    }


def _turn_segments(
    turn: dict[str, Any], position: int, duration: int | None, held: scrub.HeldBack, counts: dict[str, int]
) -> list[dict[str, Any]]:
    edited = turn.get("editedText")
    raw = edited if edited is not None else (turn.get("machineText") or "")
    source = EDITED if edited is not None else MACHINE
    english_raw, english_source = _english_of(turn, raw)
    parts = _split_long(str(raw))
    english_parts = _split_long(str(english_raw)) if english_raw is not None else []
    start, end = _times(turn, duration)
    total = sum(len(p) for p in parts) or 1
    out: list[dict[str, Any]] = []
    offset = 0
    for index, part in enumerate(parts):
        kinds: set[str] = set()
        text = _scrubbed(part, held, counts, kinds) or ""
        english = _scrubbed(english_parts[index], held, counts, kinds) if index < len(english_parts) else None
        times: tuple[int | None, int | None] = (start, end)
        if len(parts) > 1 and start is not None and end is not None:
            first = start + (end - start) * offset // total
            last = start + (end - start) * (offset + len(part)) // total
            times = (first, last) if last > first else (None, None)
        offset += len(part)
        seg_id = str(turn["id"]) if len(parts) == 1 else f"{turn['id']}.{index + 1}"
        out.append(
            _segment(seg_id, position + index, turn.get("speakerKey"), times, language_tag(turn.get("language")),
                     text, source, english, english_source, kinds)
        )
    return out


def _speaker_rows(
    keys: list[str], mapped: dict[str, dict[str, Any]], directory: people.Directory, labels: Labels
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for number, key in enumerate(keys, start=1):
        row = mapped.get(key)
        fallback = f"Speaker {number}"
        if row is None:
            out.append({"key": key, "label": fallback, "role": None, "personId": None})
            continue
        role = str(row.get("role") or "").upper() or None
        role = role if role in SPEAKER_ROLES else ("OTHER" if role else None)
        person = _speaker_person(row, role, directory)
        label = labels.screen(f"speakers[{number - 1}].label", row.get("name")) or fallback
        out.append({"key": key, "label": label, "role": role, "personId": person})
    return out


def _speaker_person(row: dict[str, Any], role: str | None, directory: people.Directory) -> str | None:
    person = row.get("personId")
    if not person:
        return None
    if role == "ARTISAN":
        survivor = directory.survivor(str(person))
        return survivor if survivor in directory.artisans else None
    return str(person) if str(person) in directory.users else None


def _turns_transcript(
    media_id: str, rows: TranscriptRows, duration: int | None, directory: people.Directory,
    held: scrub.HeldBack, labels: Labels,
) -> Segments:
    version = rows.versions[media_id]
    counts: dict[str, int] = dict.fromkeys(scrub.KINDS, 0)
    segments: list[dict[str, Any]] = []
    keys: list[str] = []
    for turn in sorted(rows.turns.get(media_id, []), key=lambda t: int(t["position"])):
        key = turn.get("speakerKey")
        if key and key not in keys:
            keys.append(str(key))
        segments += _turn_segments(turn, len(segments), duration, held, counts)
    languages = [language_tag(c) for c in (version.get("languages") or [])]
    languages = list(dict.fromkeys(languages or [s["language"] for s in segments] or [UNDETERMINED]))
    return Segments(
        source=TURNS,
        version=_int(version.get("version")),
        languages=languages,
        detected=language_tag(version.get("detectedLanguage")),
        segments=segments,
        speakers=_speaker_rows(keys, rows.speakers.get(media_id, {}), directory, labels),
        english=None,
        counts=counts,
    )


def _plain_segments(
    media_id: str, pieces: list[tuple[str | None, str]], source: str, language: str, held: scrub.HeldBack,
) -> tuple[list[dict[str, Any]], list[str], dict[str, int]]:
    counts: dict[str, int] = dict.fromkeys(scrub.KINDS, 0)
    labels_seen: list[str] = []
    segments: list[dict[str, Any]] = []
    for speaker, text in pieces:
        for part in _split_long(text):
            kinds: set[str] = set()
            key = None
            if speaker:
                if speaker not in labels_seen:
                    labels_seen.append(speaker)
                key = f"L{labels_seen.index(speaker) + 1}"
            position = len(segments)
            segments.append(
                _segment(f"{media_id}.{position}", position, key, (None, None), language,
                         _scrubbed(part, held, counts, kinds) or "", source, None, None, kinds)
            )
    return segments, labels_seen, counts


def _text_transcript(
    media: dict[str, Any], rows: TranscriptRows, held: scrub.HeldBack, labels: Labels
) -> Segments:
    media_id = str(media["id"])
    language = language_tag(media.get("transcriptLanguage"))
    turns = preview.turns_from_markdown(str(media.get("transcriptText") or ""))
    source = EDITED if media.get("transcriptEditedAt") else MACHINE
    segments, seen, counts = _plain_segments(
        media_id, [(t.speaker, t.text) for t in turns if t.text.strip()], source, language, held
    )
    speakers = [
        {"key": f"L{n}", "label": labels.screen(f"speakers[{n - 1}].label", name) or f"Speaker {n}",
         "role": None, "personId": None}
        for n, name in enumerate(seen, start=1)
    ]
    english = None
    insight = rows.insights.get(media_id)
    if insight and str(insight.get("translationStatus") or "") == "COMPLETED" and (insight.get("translationText") or "").strip():
        kinds: set[str] = set()
        english = {
            "text": _scrubbed(str(insight["translationText"]), held, counts, kinds),
            "source": EDITED if insight.get("editedAt") else MACHINE,
        }
    return Segments(TEXT, None, [language], language, segments, speakers, english, counts)


def _typed_transcript(media_id: str, typed: str, held: scrub.HeldBack) -> Segments:
    paragraphs = [(None, p.strip()) for p in re.split(r"\n\s*\n|\n", typed) if p.strip()]
    segments, _seen, counts = _plain_segments(media_id, paragraphs, TYPED, UNDETERMINED, held)
    return Segments(TYPED, None, [UNDETERMINED], UNDETERMINED, segments, [], None, counts)


@dataclass
class Facts:
    media: dict[str, Any]
    recording: dict[str, Any] | None
    session: dict[str, Any] | None
    placements: list[StagePlacement]
    design_id: str | None
    record: tuple[str, str] | None


def _record_links(media: dict[str, Any]) -> list[tuple[str, str]]:
    kind = str(media.get("linkedRecordType") or "").lower()
    linked = media.get("linkedRecordId")
    found = [
        ("PRODUCT", media.get("productId")),
        ("PRODUCT", linked if kind == "product" else None),
        ("TOOL", media.get("toolId")),
        ("TOOL", linked if kind == "tool" else None),
        ("PROCESS", linked if kind == "process" else None),
        ("PROCESS_STEP", linked if kind == "processstep" else None),
    ]
    return list(dict.fromkeys((record, str(value)) for record, value in found if value))


def _record_link(sources: Sources, media: dict[str, Any]) -> tuple[str, str] | None:
    links = _record_links(media)
    return links[0] if links else None


def record_object(sources: Sources, link: tuple[str, str] | None, labels: Labels, path: str) -> dict[str, Any] | None:
    if link is None:
        return None
    kind, record_id = link
    table = {"PRODUCT": sources.products, "TOOL": sources.tools, "PROCESS": sources.processes,
             "PROCESS_STEP": sources.steps}[kind]
    row = table.get(record_id)
    if row is None:
        return None
    name = row.get("productName") or row.get("toolkitName") or row.get("name")
    return {
        "kind": kind,
        "id": record_id,
        "name": labels.screen(f"{path}.name", name),
        "localName": labels.screen(f"{path}.localName", row.get("localName")),
    }


def record_crafts(sources: Sources, link: tuple[str, str] | None) -> list[str | None]:
    if link is None:
        return []
    kind, record_id = link
    if kind == "PRODUCT":
        return items.product_crafts(sources, record_id)
    if kind == "TOOL":
        return items.tool_crafts(sources, record_id)
    if kind == "PROCESS":
        return items.product_crafts(sources, items.process_product(sources, record_id))
    return items.product_crafts(sources, items.process_product(sources, items.step_process(sources, record_id)))


def placement_crafts(sources: Sources, placement: StagePlacement) -> list[str | None]:
    found: list[str | None] = []
    for product_id in placement.product_ids:
        found += items.product_crafts(sources, product_id)
    for tool_id in placement.tool_ids:
        found += items.tool_crafts(sources, tool_id)
    design = sources.design_workshops.get(placement.design_workshop_id) or {}
    return [*found, design.get("craftId")]


def stage_object(placement: StagePlacement, labels: Labels) -> dict[str, Any]:
    return {
        "key": placement.stage_key,
        "title": placement.stage_title,
        "entity": placement.entity_key,
        "entityLabel": placement.entity_title,
        "rowId": placement.row_id,
        "rowLabel": labels.screen("stage.rowLabel", placement.row_label),
        "field": placement.field_key,
        "fieldLabel": placement.field_label,
    }


def _topics(rows: TranscriptRows, recording: dict[str, Any] | None, labels: Labels) -> list[dict[str, Any]]:
    if recording is None:
        return []
    out: list[dict[str, Any]] = []
    for index, target in enumerate(rows.targets.get(str(recording["id"]), [])):
        asked = target.get("questionId")
        out.append({
            "sectionId": target.get("sectionId"),
            "sectionCode": target.get("sectionCode"),
            "sectionTitle": labels.screen(f"topics[{index}].sectionTitle", target.get("sectionTitle")),
            "questionId": asked,
            "questionCode": (target.get("displayCode") or target.get("questionCode")) if asked else None,
            "question": labels.screen(f"topics[{index}].question", target.get("prompt")) if asked else None,
        })
    return out


def _session_object(session: dict[str, Any] | None, labels: Labels) -> dict[str, Any] | None:
    if session is None:
        return None
    form = session.get("questionnaireId")
    return {
        "id": session["id"],
        "title": labels.screen("session.title", session.get("title")),
        "date": day_text(session.get("sessionDate") or session.get("interviewDate")),
        "purpose": session.get("purpose"),
        "questionnaire": {
            "id": form,
            "title": labels.screen("session.questionnaire.title", session.get("questionnaireTitle")),
        } if form else None,
    }


def _facts(sources: Sources, rows: TranscriptRows, media_id: str, placements: dict[str, list[StagePlacement]]) -> Facts:
    media = sources.media[media_id]
    session = sources.session_end(media.get("questionnaireInterviewId"))
    own = [p for p in placements.get(media_id, []) if p.listed]
    design = sources.media_design_workshops.get(media_id) or (own[0].design_workshop_id if own else None)
    return Facts(media, rows.recordings.get(media_id), session, own, design, _record_link(sources, media))


def _excluded(sources: Sources, facts: Facts, placements: list[StagePlacement]) -> Outcome | None:
    media = facts.media
    media_id = str(media["id"])
    if media.get("removedAt") or str(media.get("status")) == "REJECTED":
        return items.gone(media_id, contract.DELETED, items.WITHHELD_REMOVED)
    if str(media.get("status")) == "DRAFT" or str(media.get("mediaType")) not in MEDIA_TYPES:
        return items.gone(media_id, contract.UNLISTED, items.WITHHELD_REMOVED)
    recording = facts.recording
    if recording is not None and recording.get("removedAt"):
        if recording.get("successor"):
            return items.gone(media_id, contract.REPLACED, items.WITHHELD_REMOVED, str(recording["successor"]))
        return items.gone(media_id, contract.DELETED, items.WITHHELD_REMOVED)
    if media.get("questionnaireInterviewId") and (facts.session is None or facts.session.get("deletedAt")):
        return items.gone(media_id, contract.DELETED, items.WITHHELD_REMOVED)
    design = sources.design_workshops.get(facts.design_id or "")
    if facts.design_id and (design is None or design.get("deletedAt")):
        return items.gone(media_id, contract.DELETED, items.WITHHELD_REMOVED)
    if any(not p.listed for p in placements):
        return items.gone(media_id, contract.UNLISTED, items.WITHHELD_IDENTITY)
    return None


def _source_of(media: dict[str, Any], rows: TranscriptRows, facts: Facts) -> str | None:
    media_id = str(media["id"])
    if media_id in rows.versions and rows.turns.get(media_id):
        return TURNS
    if str(media.get("transcriptText") or "").strip():
        return TEXT
    if any((p.typed_transcript or "").strip() for p in facts.placements):
        return TYPED
    return None


def _linked_artisans(sources: Sources, rows: TranscriptRows, facts: Facts) -> tuple[list[str], list[str]]:
    subjects = list(rows.subjects.get(str((facts.recording or {}).get("id") or ""), []))
    mapped = [
        str(s["personId"]) for s in rows.speakers.get(str(facts.media["id"]), {}).values()
        if s.get("personId") and str(s.get("role") or "").upper() == "ARTISAN"
    ]
    session_id = str((facts.session or {}).get("id") or "")
    extra = [facts.media.get("artisanId"), *sources.session_artisans.get(session_id, [])]
    for kind, record_id in _record_links(facts.media):
        extra += items.record_artisans(sources, kind, record_id)
    for placement in facts.placements:
        extra += items.stage_artisans(sources, placement)
    people_ids = list(dict.fromkeys(subjects + mapped))
    linked = list(dict.fromkeys(people_ids + [a for a in extra if a]))
    return people_ids, linked


def _mapped_users(rows: TranscriptRows, media_id: str) -> list[str]:
    return sorted({
        str(s["personId"]) for s in rows.speakers.get(media_id, {}).values()
        if s.get("personId") and str(s.get("role") or "").upper() != "ARTISAN"
    })


@dataclass
class Batch:
    sources: Sources
    rows: TranscriptRows
    directory: people.Directory
    placements: dict[str, list[StagePlacement]]
    designers: dict[str, list[str]]
    refused: set[str]
    subjects: dict[str, Subjects] = field(default_factory=dict)


def build(batch: Batch, media_id: str) -> Outcome:
    sources, rows, directory = batch.sources, batch.rows, batch.directory
    facts = _facts(sources, rows, media_id, batch.placements)
    excluded = _excluded(sources, facts, batch.placements.get(media_id, []))
    if excluded is not None:
        return excluded
    source = _source_of(facts.media, rows, facts)
    if source is None:
        return items.gone(media_id, contract.UNLISTED, items.WITHHELD_NO_TRANSCRIPT)
    if rows.verdicts.get(media_id) == "REFUSED" or media_id in batch.refused:
        return items.gone(media_id, contract.WITHDRAWN, items.WITHHELD_REFUSED)
    people_ids, linked = _linked_artisans(sources, rows, facts)
    if items.missing_artisans(directory, people_ids):
        return items.gone(media_id, contract.DELETED, items.WITHHELD_ARTISAN_GONE)
    moment = (facts.recording or {}).get("capturedAt") or facts.media.get("recordedAt")
    on = ist_day(moment)
    survivors = list(dict.fromkeys(directory.survivor(a) for a in linked))
    crafts = _crafts(sources, facts, [directory.artisans[a].craft_id for a in survivors if a in directory.artisans])
    if not crafts:
        return items.gone(media_id, contract.UNLISTED, items.WITHHELD_NO_CRAFT)
    stored = batch.subjects[media_id].stored() if media_id in batch.subjects else None
    return Outcome(
        item_id=media_id, payload=items.assert_payload(_payload(batch, facts, source, crafts, on)), subjects=stored
    )


def _crafts(sources: Sources, facts: Facts, artisan_crafts: list[str | None]) -> list[str]:
    session = facts.session or {}
    record_tier = record_crafts(sources, facts.record)
    for placement in facts.placements:
        record_tier += placement_crafts(sources, placement)
    workshop_ids = [facts.media.get("workshopId"), session.get("workshopId")]
    context = [sources.workshop_crafts.get(str(w), []) for w in workshop_ids if w]
    design = sources.design_workshops.get(facts.design_id or "") or {}
    context.append([design.get("craftId")])
    return items.crafts_for(
        sources, own=[facts.media.get("craftId")], records=record_tier, context=context, artisans=artisan_crafts
    )


def _workshop_ids(sources: Sources, facts: Facts) -> list[str | None]:
    session = facts.session or {}
    ids: list[str | None] = [facts.media.get("workshopId"), session.get("workshopId")]
    if facts.record:
        kind, record_id = facts.record
        table = {"PRODUCT": sources.products, "TOOL": sources.tools, "PROCESS": sources.processes}.get(kind, {})
        ids.append((table.get(record_id) or {}).get("workshopId"))
    return ids


def _payload(batch: Batch, facts: Facts, source: str, crafts: list[str], on: Any) -> dict[str, Any]:
    sources, rows, directory = batch.sources, batch.rows, batch.directory
    media = facts.media
    media_id = str(media["id"])
    people_ids, linked = _linked_artisans(sources, rows, facts)
    survivors = list(dict.fromkeys(directory.survivor(a) for a in people_ids))
    users = [u for u in _mapped_users(rows, media_id) if u in directory.users]
    designers = set(batch.designers.get(facts.design_id or "", []))
    session_area = items.area_of(facts.session)
    held = items.held_for(
        directory,
        artisans=list(dict.fromkeys(directory.survivor(a) for a in linked)),
        users=[*users, *sorted(u for u in designers if u in directory.users and u not in users)],
    )
    labels = Labels(held)
    workshop = items.workshop_for(
        sources, design_ids=[facts.design_id], workshop_ids=_workshop_ids(sources, facts), labels=labels
    )
    duration = _int((rows.versions.get(media_id) or {}).get("durationMs")) or _int((facts.recording or {}).get("durationMs"))
    if source == TURNS:
        segments = _turns_transcript(media_id, rows, duration, directory, held, labels)
    elif source == TEXT:
        segments = _text_transcript(media, rows, held, labels)
    else:
        typed = next(p.typed_transcript for p in facts.placements if (p.typed_transcript or "").strip())
        segments = _typed_transcript(media_id, str(typed), held)
    persons = [
        people.artisan_person(
            directory, a, on=on, labels=labels, path=f"people[{index}]",
            craft=items.artisan_craft(sources, directory, a),
        )
        for index, a in enumerate(survivors)
    ]
    persons += [
        people.user_person(
            directory, u, kind=people.user_kind(directory, u, designers), labels=labels,
            path=f"people[{len(survivors) + index}]",
        )
        for index, u in enumerate(users)
    ]
    stage = facts.placements[0] if facts.placements else None
    moment = (facts.recording or {}).get("capturedAt") or media.get("recordedAt")
    return {
        "id": media_id,
        "recordingId": (facts.recording or {}).get("id"),
        "mediaType": media.get("mediaType"),
        "recordedAt": iso_moment(moment, media.get("recordedTimezone")),
        "durationMs": duration,
        "crafts": [sources.craft_object(c) for c in crafts],
        "workshop": workshop,
        "session": _session_object(facts.session, labels),
        "topics": _topics(rows, facts.recording, labels),
        "stage": stage_object(stage, labels) if stage else None,
        "record": record_object(sources, facts.record, labels, "record"),
        "area": session_area or (workshop or {}).get("area"),
        "people": persons,
        "speakers": segments.speakers,
        "transcript": {
            "source": segments.source,
            "version": segments.version,
            "languages": segments.languages,
            "detectedLanguage": segments.detected,
            "segments": segments.segments,
            "english": segments.english,
        },
        "scrubbing": items.scrubbing_block(segments.counts, labels.nulled, labels.counts),
    }


def subjects_of(batch_sources: Sources, rows: TranscriptRows, media_id: str, placements: dict[str, list[StagePlacement]],
                directory: people.Directory) -> Subjects:
    facts = _facts(batch_sources, rows, media_id, placements)
    _people, linked = _linked_artisans(batch_sources, rows, facts)
    workshops = {w for w in _workshop_ids(batch_sources, facts) if w}
    if facts.design_id:
        workshops.add(facts.design_id)
    return Subjects(
        media={media_id},
        artisans={directory.survivor(a) for a in linked} | set(linked),
        workshops=workshops,
        users=set(_mapped_users(rows, media_id)),
    )


async def evaluate(
    client: Any, adapter: Any, media_ids: list[str], *, crafts: dict[str, dict[str, Any]],
    placements: dict[str, list[StagePlacement]],
) -> list[Outcome]:
    stage = [p for m in media_ids for p in placements.get(m, [])]
    sources = await load_sources(
        client, adapter, media_ids, crafts=crafts,
        extra_products=[x for p in stage for x in p.product_ids],
        extra_tools=[x for p in stage for x in p.tool_ids],
        extra_design_workshops=[p.design_workshop_id for p in stage],
    )
    rows = await load_rows(client, sources)
    designers = await adapter.designers(client, sorted(sources.design_workshops))
    artisan_ids: set[str] = set()
    user_ids: set[str] = {u for ids in designers.values() for u in ids}
    for media_id in sources.media:
        facts = _facts(sources, rows, media_id, placements)
        artisan_ids |= set(_linked_artisans(sources, rows, facts)[1])
        user_ids |= set(_mapped_users(rows, media_id))
    directory = await people.load_directory(client, adapter, artisan_ids=artisan_ids, user_ids=user_ids)
    subjects = {m: subjects_of(sources, rows, m, placements, directory) for m in sources.media}
    refused = await items.refused_items(client, adapter, subjects)
    batch = Batch(sources, rows, directory, placements, designers, refused, subjects)
    out = [build(batch, m) for m in media_ids if m in sources.media]
    out += [items.gone(m, contract.DELETED, items.WITHHELD_REMOVED) for m in media_ids if m not in sources.media]
    return out
