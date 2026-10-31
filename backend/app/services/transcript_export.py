"""Transcripts in "View data" and "Download data": turns, summaries, quotes, glossary, chips (0.0.6).

IDENTICAL IN BOTH PORTALS (listed in ``shared/transcription.sha256``). The data browser's manifest,
its report and the streamed transcript datasets (``api/routes/transcript_data.py``) all read
recordings through :func:`load`, so the three agree on every rule below.

WHAT A RECORDING CARRIES. Its turns (speaker under the mapped name, start and end, language, the
original and its English), its summary lines and quotes, the glossary terms heard in it, and its
recording chips (form, session, section or question, artisans, researcher, workshop, language,
duration). A recording transcribed before the turn pipeline has no timed turns: its stored markdown
is read into speaker turns with no times.

CONSENT. A recording whose consent was refused or withdrawn (the one ledger rule, through the
portal's ``consent_gate``) is listed with ``withheld = CONSENT_REFUSED`` and NO words at all: no
turns, no summary, no quotes, no glossary, no transcript files.

COST AND MODELS are added only for the three administrator roles (``sees_spend``), as on the
transcript page.

MEMORY. Nothing here reads "everything". :func:`load` takes one batch of ids (:data:`BATCH`) and
answers it in a FIXED number of reads, whatever the batch holds (``tests/test_transcript_export_db``
counts them); :func:`select_page` pages the selection by keyset. A caller streams batch by batch and
drops each before reading the next: the boxes are a t3.medium (designer) and a t3.small (field).
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.transcription.transcript import preview, render
from app.transcription.transcript.turns import language_label, speaker_order

#: Recordings per :func:`load` call. Turns dominate: 50 long interviews is a few MB.
BATCH = 50
WITHHELD = "CONSENT_REFUSED"
ALLOW = "ALLOW"

#: The transcript files a download bundle carries per recording (contract ``transcriptFile``):
#: ``(what, extension, format, track, timestamps, needs_times)``. ``what`` follows the naming skill
#: and ``download_name``; subtitles are only offered for timed turns.
BUNDLE_FILES: tuple[tuple[str, str, str, str, bool, bool], ...] = (
    ("Transcript-Bilingual", ".md", "md", "both", True, False),
    ("Transcript-Bilingual-No-Timestamps", ".md", "md", "both", False, False),
    ("Transcript-Bilingual", ".docx", "docx", "both", True, False),
    ("Transcript-Bilingual-No-Timestamps", ".docx", "docx", "both", False, False),
    ("Subtitles-With-Speakers", ".srt", "srt", "original", True, True),
    ("Subtitles-With-Speakers-English", ".srt", "srt", "english", True, True),
    ("Subtitles-With-Speakers", ".vtt", "vtt", "original", True, True),
    ("Subtitles-With-Speakers-English", ".vtt", "vtt", "english", True, True),
)


def transcript_file_url(media_id: str, fmt: str, track: str, timestamps: bool) -> str:
    """The portal API path that renders one transcript file (``GET /api/transcripts/{id}/download``).
    Relative: the web fetches it through its own origin with the session; the apps prefix their API."""
    return f"/api/transcripts/{media_id}/download?format={fmt}&track={track}&timestamps={'true' if timestamps else 'false'}"


def _conn(client: Any) -> Any:
    return client if client is not None else db


def _iso(value: Any) -> str | None:
    if isinstance(value, datetime):
        moment = value if value.tzinfo else value.replace(tzinfo=UTC)
        return moment.astimezone(UTC).isoformat().replace("+00:00", "Z")
    return str(value) if value else None


async def _verdicts(ids: list[str], client: Any) -> dict[str, str]:
    """``{mediaId: ALLOW|REFUSED|...}`` from the portal's consent gate, in its fixed reads. A failed
    read withholds (the safe answer for an export)."""
    from app.questionnaire import ports

    gate = ports.module("consent_gate")
    try:
        details = await gate.verdict_details(ids, client=client)
    except Exception:  # noqa: BLE001 - an unreadable consent store exports no words
        return dict.fromkeys(ids, WITHHELD)
    allow = getattr(gate, "ALLOW", ALLOW)
    return {mid: (ALLOW if (details.get(mid) or {}).get("verdict") == allow else WITHHELD) for mid in ids}


def _labels(rows: list[dict[str, Any]], names: dict[str, dict[str, Any]]) -> dict[str, str]:
    """The transcript page's labels: the mapped name (``(researcher)`` for a researcher), else Speaker N."""
    labels: dict[str, str] = {}
    for number, key in enumerate(speaker_order(rows), start=1):
        mapped = names.get(key)
        if mapped and mapped.get("name"):
            labels[key] = f"{mapped['name']} (researcher)" if mapped.get("role") == "RESEARCHER" else str(mapped["name"])
        else:
            labels[key] = f"Speaker {number}"
    return labels


def _english(row: dict[str, Any], text: str) -> str | None:
    if row.get("editedEnglish") is not None:
        return row["editedEnglish"]
    if row.get("translationStatus") == "ALREADY_ENGLISH":
        return text
    return row.get("machineEnglish")


async def load(media_ids: list[str], *, sees_spend: bool = False, client: Any = None) -> dict[str, dict[str, Any]]:
    """``{mediaId: recording}`` for at most :data:`BATCH` ids, in a fixed number of reads."""
    from app.questionnaire import chips as q_chips

    ids = list(dict.fromkeys(str(i) for i in media_ids if i))[:BATCH]
    if not ids:
        return {}
    conn = _conn(client)
    media = await conn.query_raw(
        'SELECT m."id", m."transcriptText", m."transcriptStatus"::text AS status, m."originalFilename", m."createdAt", '
        'm."mediaType"::text AS "mediaType", u."name" AS "uploadedByName" FROM "MediaFile" m '
        'LEFT JOIN "User" u ON u."id" = m."uploadedById" WHERE m."id" = ANY($1::text[])',
        ids,
    )
    verdicts = await _verdicts(ids, conn)
    allowed = [mid for mid in ids if verdicts.get(mid) == ALLOW]
    versions = await conn.query_raw(
        'SELECT DISTINCT ON ("mediaFileId") "id", "mediaFileId", "version", "languages", "durationMs", "speakerCount", '
        '"sttProvider", "sttModel", "translationModel", "sttCostUsd", "translationCostUsd" FROM "TranscriptVersion" '
        'WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL ORDER BY "mediaFileId", "version" DESC',
        allowed,
    ) if allowed else []
    version_by = {str(v["mediaFileId"]): v for v in versions}
    turns = await conn.query_raw(
        'SELECT "versionId", "mediaFileId", "position", "speakerKey", "startMs", "endMs", "language", "machineText", '
        '"editedText", "machineEnglish", "editedEnglish", "translationStatus" FROM "TranscriptTurn" '
        'WHERE "versionId" = ANY($1::text[]) ORDER BY "versionId", "position"',
        [str(v["id"]) for v in versions],
    ) if versions else []
    speakers = await conn.query_raw(
        'SELECT "mediaFileId", "speakerKey", "name", "role" FROM "TranscriptSpeaker" WHERE "mediaFileId" = ANY($1::text[])',
        allowed,
    ) if allowed else []
    insights = await conn.query_raw(
        'SELECT DISTINCT ON ("mediaFileId") "mediaFileId", "summaryLines", "quotes" FROM "TranscriptInsight" '
        'WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL ORDER BY "mediaFileId", "version" DESC',
        allowed,
    ) if allowed else []
    glossary = await conn.query_raw(
        'SELECT s."mediaFileId", g."term", g."gloss", c."name" AS craft FROM "TranscriptGlossarySighting" s '
        'JOIN "TranscriptGlossaryTerm" g ON g."id" = s."termId" LEFT JOIN "Craft" c ON c."id" = g."craftId" '
        'WHERE s."mediaFileId" = ANY($1::text[]) ORDER BY s."mediaFileId", g."term"',
        allowed,
    ) if allowed else []
    chip_by = await q_chips.for_media(conn, ids)

    turns_by: dict[str, list[dict[str, Any]]] = {}
    for row in turns:
        turns_by.setdefault(str(row["mediaFileId"]), []).append(row)
    names_by: dict[str, dict[str, dict[str, Any]]] = {}
    for row in speakers:
        names_by.setdefault(str(row["mediaFileId"]), {})[str(row["speakerKey"])] = row
    insight_by = {str(r["mediaFileId"]): r for r in insights}
    gloss_by: dict[str, list[dict[str, Any]]] = {}
    for row in glossary:
        gloss_by.setdefault(str(row["mediaFileId"]), []).append(
            {"term": row["term"], "gloss": row.get("gloss"), "craft": row.get("craft")}
        )

    out: dict[str, dict[str, Any]] = {}
    for m in media:
        mid = str(m["id"])
        rec: dict[str, Any] = {
            "mediaId": mid,
            "file": m.get("originalFilename"),
            "mediaType": m.get("mediaType"),
            "uploadedBy": m.get("uploadedByName"),
            "uploadedAt": _iso(m.get("createdAt")),
            "transcriptStatus": m.get("status"),
            "withheld": None if verdicts.get(mid) == ALLOW else WITHHELD,
            "source": None,
            "version": None,
            "languages": [],
            "durationMs": None,
            "speakers": [],
            "turns": [],
            "timed": False,
            "summary": [],
            "quotes": [],
            "glossary": [],
            "chips": chip_by.get(mid),
        }
        out[mid] = rec
        if rec["withheld"]:
            continue
        version = version_by.get(mid)
        rows = turns_by.get(mid) or []
        if version is not None and rows:
            labels = _labels(rows, names_by.get(mid, {}))
            rec.update(
                source="TURNS",
                version=version.get("version"),
                languages=[{"code": c, "label": language_label(c)} for c in (version.get("languages") or [])],
                durationMs=version.get("durationMs"),
                speakers=[
                    {"speakerKey": k, "label": v, "colorKey": preview.color_key(k), "role": (names_by.get(mid, {}).get(k) or {}).get("role")}
                    for k, v in labels.items()
                ],
            )
            for row in rows:
                text = row["editedText"] if row.get("editedText") is not None else (row.get("machineText") or "")
                key = row.get("speakerKey")
                rec["turns"].append({
                    "index": int(row["position"]),
                    "speakerKey": key,
                    "speaker": labels.get(key or "", "Speaker") if key else "Speaker",
                    "colorKey": preview.color_key(key),
                    "startMs": int(row.get("startMs") or 0),
                    "endMs": int(row.get("endMs") or 0),
                    "start": render.timestamp(row.get("startMs")),
                    "end": render.timestamp(row.get("endMs")),
                    "language": row.get("language"),
                    "text": text,
                    "english": _english(row, text),
                    "translationStatus": row.get("translationStatus"),
                    "edited": row.get("editedText") is not None,
                })
            rec["timed"] = any(t["endMs"] > 0 for t in rec["turns"])
            if sees_spend:
                rec["models"] = {"stt": version.get("sttModel"), "translation": version.get("translationModel")}
                rec["costUsd"] = round(float(version.get("sttCostUsd") or 0) + float(version.get("translationCostUsd") or 0), 4)
        elif str(m.get("transcriptText") or "").strip():
            legacy = preview.turns_from_markdown(m["transcriptText"])
            rec["source"] = "TEXT"
            for index, turn in enumerate(legacy):
                rec["turns"].append({
                    "index": index, "speakerKey": None, "speaker": turn.speaker or "",
                    "colorKey": preview.color_key(None, turn.speaker), "startMs": None, "endMs": None,
                    "start": None, "end": None, "language": None, "text": turn.text, "english": None,
                    "translationStatus": None, "edited": False,
                })
            rec["speakers"] = [
                {"speakerKey": None, "label": name, "colorKey": preview.color_key(None, name), "role": None}
                for name in dict.fromkeys(t["speaker"] for t in rec["turns"] if t["speaker"])
            ]
        insight = insight_by.get(mid)
        if insight is not None:
            rec["summary"] = [str(s) for s in (insight.get("summaryLines") or []) if str(s).strip()]
            quotes = insight.get("quotes") or []
            if isinstance(quotes, str):
                import json

                try:
                    quotes = json.loads(quotes)
                except ValueError:
                    quotes = []
            rec["quotes"] = [
                {"speaker": q.get("speaker"), "original": q.get("original") or "", "english": q.get("english") or ""}
                for q in quotes if isinstance(q, dict) and q.get("original")
            ]
        rec["glossary"] = gloss_by.get(mid, [])
    return out


# --- Selection, filters and paging ------------------------------------------------------------


@dataclass
class Filters:
    """The recording fields a transcript export narrows by (query parameters of the same names)."""

    language: list[str] = field(default_factory=list)       # a language heard (TranscriptVersion.languages)
    status: list[str] = field(default_factory=list)         # MediaFile.transcriptStatus
    speaker: str | None = None                              # a mapped speaker name, contains
    researcher: list[str] = field(default_factory=list)     # uploader / recorder user ids
    since: datetime | None = None                           # uploaded on or after
    until: datetime | None = None                           # uploaded before
    has_english: bool | None = None                         # at least one translated turn
    q: str | None = None                                    # words in the transcript
    media_ids: list[str] | None = None                      # narrowed by questionnaire filters

    def echo(self) -> dict[str, Any]:
        return {
            "language": self.language, "status": self.status, "speaker": self.speaker, "researcher": self.researcher,
            "since": _iso(self.since), "until": _iso(self.until), "hasEnglish": self.has_english, "q": self.q,
        }


def _split(values: list[str] | None) -> list[str]:
    return [v.strip() for value in (values or []) for v in str(value).split(",") if v.strip()]


def parse_filters(params: Any) -> Filters:
    """``Filters`` from query parameters (repeatable or comma-separated)."""
    def many(name: str) -> list[str]:
        getter = getattr(params, "getlist", None)
        return _split(getter(name) if getter else ([params[name]] if params.get(name) else []))

    def moment(name: str) -> datetime | None:
        raw = params.get(name)
        if not raw:
            return None
        try:
            value = datetime.fromisoformat(str(raw))
        except ValueError:
            return None
        return value.astimezone(UTC).replace(tzinfo=None) if value.tzinfo else value

    english = params.get("hasEnglish")
    return Filters(
        language=many("language"),
        status=[s.upper() for s in many("status")],
        speaker=(params.get("speaker") or "").strip() or None,
        researcher=many("researcherId"),
        since=moment("since"),
        until=moment("until"),
        has_english=None if english in (None, "") else str(english).lower() in ("1", "true", "yes"),
        q=(params.get("q") or "").strip() or None,
    )


def _where(f: Filters, cursor: str | None) -> tuple[str, list[Any]]:
    params: list[Any] = []

    def p(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    w = ['m."removedAt" IS NULL', '(m."transcriptText" IS NOT NULL OR EXISTS (SELECT 1 FROM "TranscriptVersion" v0 '
         'WHERE v0."mediaFileId" = m."id" AND v0."supersededAt" IS NULL))']
    if cursor:
        w.append(f'm."id" > {p(cursor)}')
    if f.media_ids is not None:
        w.append(f'm."id" = ANY({p(list(f.media_ids))}::text[])')
    if f.status:
        w.append(f'm."transcriptStatus"::text = ANY({p(f.status)}::text[])')
    if f.researcher:
        w.append(f'm."uploadedById" = ANY({p(f.researcher)}::text[])')
    if f.since:
        w.append(f'm."createdAt" >= {p(f.since.isoformat())}::timestamp')
    if f.until:
        w.append(f'm."createdAt" < {p(f.until.isoformat())}::timestamp')
    if f.language:
        w.append(f'EXISTS (SELECT 1 FROM "TranscriptVersion" v WHERE v."mediaFileId" = m."id" AND v."supersededAt" IS NULL '
                 f'AND v."languages" && {p(f.language)}::text[])')
    if f.speaker:
        w.append(f'EXISTS (SELECT 1 FROM "TranscriptSpeaker" s WHERE s."mediaFileId" = m."id" AND s."name" ILIKE {p("%" + f.speaker + "%")})')
    if f.has_english is not None:
        clause = ('EXISTS (SELECT 1 FROM "TranscriptTurn" t JOIN "TranscriptVersion" v ON v."id" = t."versionId" '
                  'AND v."supersededAt" IS NULL WHERE t."mediaFileId" = m."id" AND (t."machineEnglish" IS NOT NULL OR t."editedEnglish" IS NOT NULL))')
        w.append(clause if f.has_english else f"NOT {clause}")
    if f.q:
        w.append(f'm."transcriptText" ILIKE {p("%" + f.q + "%")}')
    return " AND ".join(w), params


async def select_page(f: Filters, cursor: str | None, *, limit: int = BATCH, client: Any = None) -> list[str]:
    """One keyset page of matching media ids (id order), at most ``limit``. One read."""
    where, params = _where(f, cursor)
    rows = await _conn(client).query_raw(
        f'SELECT m."id" FROM "MediaFile" m WHERE {where} ORDER BY m."id" LIMIT {max(1, min(int(limit), 500))}',
        *params,
    )
    return [str(r["id"]) for r in rows]


Visible = Callable[[list[str]], Awaitable[set[str]]]


async def iter_recordings(
    f: Filters, *, sees_spend: bool, visible: Visible | None = None, client: Any = None
) -> AsyncIterator[dict[str, Any]]:
    """Every matching recording the reader may see, batch by batch (one batch in memory at a time)."""
    cursor: str | None = None
    while True:
        page = await select_page(f, cursor, client=client)
        if not page:
            return
        cursor = page[-1]
        if visible is None:
            shown = page
        else:
            allowed = await visible(page)
            shown = [mid for mid in page if mid in allowed]
        if shown:
            loaded = await load(shown, sees_spend=sees_spend, client=client)
            for mid in shown:
                if mid in loaded:
                    yield loaded[mid]
            del loaded
        if len(page) < BATCH:
            return


# --- Flat rows (CSV / NDJSON) -----------------------------------------------------------------


def _chip_text(chips: dict[str, Any] | None) -> dict[str, str]:
    c = chips or {}
    targets = c.get("targets") or []
    return {
        "Form": ((c.get("questionnaire") or {}).get("title")) or "",
        "Session": ((c.get("session") or {}).get("title")) or "",
        "Section or question": "; ".join(
            (f"{t.get('displayCode')} {t.get('prompt')}".strip() if t.get("kind") == "QUESTION"
             else f"Section {t.get('sectionCode') or ''} {t.get('sectionTitle') or ''}".strip())
            for t in targets
        ),
        "Artisans": "; ".join(a.get("name") or "" for a in (c.get("artisans") or [])),
        "Respondents": "; ".join(a.get("name") or "" for a in (c.get("respondents") or [])),
        "Researcher": ((c.get("researcher") or {}).get("name")) or "",
        "Session date": c.get("sessionDate") or "",
        "Workshop": ((c.get("workshop") or {}).get("name")) or "",
        "Recording language": c.get("languageCode") or "",
        "Recording duration (s)": str(int(c["durationMs"]) // 1000) if c.get("durationMs") is not None else "",
    }


RECORDING_COLUMNS = (
    "Media ID", "File", "Uploaded by", "Uploaded on", "Transcript status", "Consent", "Source", "Languages",
    "Duration", "Speakers", "Turns", "Summary", "Quotes", "Glossary terms",
    "Form", "Session", "Section or question", "Artisans", "Respondents", "Researcher", "Session date", "Workshop",
    "Recording language", "Recording duration (s)",
)
SPEND_COLUMNS = ("Speech-to-text model", "Translation model", "Cost (USD)")
TURN_COLUMNS = (
    "Media ID", "File", "Turn", "Speaker", "Speaker key", "Colour key", "Start", "End", "Start (ms)", "End (ms)",
    "Language", "Original", "English", "Translation status", "Edited",
)
GLOSSARY_COLUMNS = ("Media ID", "File", "Term", "Gloss", "Craft")


def recording_columns(sees_spend: bool) -> tuple[str, ...]:
    return RECORDING_COLUMNS + (SPEND_COLUMNS if sees_spend else ())


def recording_row(rec: dict[str, Any], *, sees_spend: bool) -> dict[str, Any]:
    row = {
        "Media ID": rec["mediaId"],
        "File": rec.get("file") or "",
        "Uploaded by": rec.get("uploadedBy") or "",
        "Uploaded on": rec.get("uploadedAt") or "",
        "Transcript status": rec.get("transcriptStatus") or "",
        "Consent": "Withheld: consent refused" if rec.get("withheld") else "",
        "Source": {"TURNS": "Speaker turns", "TEXT": "Transcript text"}.get(rec.get("source") or "", ""),
        "Languages": ", ".join(lang["label"] for lang in rec.get("languages") or []),
        "Duration": render.length(rec["durationMs"]) if rec.get("durationMs") else "",
        "Speakers": "; ".join(s["label"] for s in rec.get("speakers") or []),
        "Turns": str(len(rec.get("turns") or [])),
        "Summary": "\n".join(rec.get("summary") or []),
        "Quotes": "\n".join(
            f"{q['speaker'] + ': ' if q.get('speaker') else ''}{q['original']}" + (f" ({q['english']})" if q.get("english") else "")
            for q in rec.get("quotes") or []
        ),
        "Glossary terms": "; ".join(
            f"{g['term']}{' - ' + g['gloss'] if g.get('gloss') else ''}" for g in rec.get("glossary") or []
        ),
        **_chip_text(rec.get("chips")),
    }
    if sees_spend:
        models = rec.get("models") or {}
        row["Speech-to-text model"] = models.get("stt") or ""
        row["Translation model"] = models.get("translation") or ""
        row["Cost (USD)"] = "" if rec.get("costUsd") is None else f"{rec['costUsd']:.4f}"
    return row


def turn_rows(rec: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {
            "Media ID": rec["mediaId"], "File": rec.get("file") or "", "Turn": str(t["index"] + 1),
            "Speaker": t.get("speaker") or "", "Speaker key": t.get("speakerKey") or "", "Colour key": t.get("colorKey") or "",
            "Start": t.get("start") or "", "End": t.get("end") or "",
            "Start (ms)": "" if t.get("startMs") is None else str(t["startMs"]),
            "End (ms)": "" if t.get("endMs") is None else str(t["endMs"]),
            "Language": t.get("language") or "", "Original": t.get("text") or "", "English": t.get("english") or "",
            "Translation status": t.get("translationStatus") or "", "Edited": "Yes" if t.get("edited") else "",
        }
        for t in rec.get("turns") or []
    ]


def glossary_rows(rec: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {"Media ID": rec["mediaId"], "File": rec.get("file") or "", "Term": g["term"], "Gloss": g.get("gloss") or "", "Craft": g.get("craft") or ""}
        for g in rec.get("glossary") or []
    ]


# --- The download bundle ----------------------------------------------------------------------


async def bundle_plan(media_ids: list[str], *, client: Any = None) -> dict[str, dict[str, Any]]:
    """``{mediaId: {"withheld", "turns", "timed"}}`` for a manifest: which transcript files a bundle
    carries. Batched: one read of turn facts plus the consent gate per :data:`BATCH` ids."""
    out: dict[str, dict[str, Any]] = {}
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    conn = _conn(client)
    for start in range(0, len(ids), BATCH * 4):
        chunk = ids[start : start + BATCH * 4]
        verdicts = await _verdicts(chunk, conn)
        facts = await conn.query_raw(
            'SELECT v."mediaFileId", count(t."id")::int AS turns, COALESCE(max(t."endMs"), 0)::int AS last '
            'FROM "TranscriptVersion" v LEFT JOIN "TranscriptTurn" t ON t."versionId" = v."id" '
            'WHERE v."mediaFileId" = ANY($1::text[]) AND v."supersededAt" IS NULL GROUP BY v."mediaFileId"',
            chunk,
        )
        fact_by = {str(r["mediaFileId"]): r for r in facts}
        for mid in chunk:
            fact = fact_by.get(mid) or {}
            out[mid] = {
                "withheld": verdicts.get(mid) != ALLOW,
                "turns": int(fact.get("turns") or 0) > 0,
                "timed": int(fact.get("last") or 0) > 0,
            }
    return out


async def finalize_manifest(files: list[dict[str, Any]], *, pipeline_on: bool, client: Any = None) -> list[dict[str, Any]]:
    """Settle a manifest's transcript entries (marked ``_tx``) once the whole walk is known.

    Each transcript-bearing recording was emitted twice by the walk: its stored markdown inline
    (``_tx = (mediaId, "legacy")``) and the per-recording files of :data:`BUNDLE_FILES`
    (``_tx = (mediaId, "pipeline", needs_times)``). Here, in batched reads: a withheld recording
    keeps NONE of them; a recording with turns keeps the files (subtitles only when timed) while
    the pipeline is on; any other keeps its markdown. Markers are removed. Returns ``files``.
    """
    marked = [f for f in files if f.get("_tx")]
    if not marked:
        return files
    plan = await bundle_plan([f["_tx"][0] for f in marked], client=client)
    keep: list[dict[str, Any]] = []
    for entry in files:
        tx = entry.pop("_tx", None)
        if tx is None:
            keep.append(entry)
            continue
        facts = plan.get(tx[0]) or {"withheld": True, "turns": False, "timed": False}
        if facts["withheld"]:
            continue
        files_ok = pipeline_on and facts["turns"]
        if (tx[1] == "legacy" and not files_ok) or (tx[1] == "pipeline" and files_ok and (facts["timed"] or not tx[2])):
            keep.append(entry)
    files[:] = keep
    return files


def bundle_names(media: Any, name_kw: dict[str, Any] | None) -> list[tuple[str, str, str, bool, bool]]:
    """``[(file name, format, track, timestamps, needs_times)]`` of :data:`BUNDLE_FILES` for one
    recording: the clip's own name (``media_naming.display_filename``) with each file's What."""
    from app.services.media_naming import display_filename

    kw = dict(name_kw or {})
    return [
        (display_filename(media, what=what, extension=ext, **kw), fmt, track, stamps, needs)
        for what, ext, fmt, track, stamps, needs in BUNDLE_FILES
    ]


def bundle_entries(media_id: str, names: list[tuple[str, str, str, bool, bool]]) -> list[tuple[str, dict[str, Any], bool]]:
    """``[(name, manifest entry, needs_times)]``: each file fetched from the transcript API."""
    return [
        (
            name,
            {
                "url": transcript_file_url(media_id, fmt, track, stamps),
                "mediaId": media_id,
                "mediaType": "TRANSCRIPT",
                "transcriptFile": {"format": fmt, "track": track, "timestamps": stamps},
            },
            needs,
        )
        for name, fmt, track, stamps, needs in names
    ]


async def settle_tree(entries: list[dict[str, Any]], *, pipeline_on: bool, client: Any = None) -> list[dict[str, Any]]:
    """One /tree level after consent and the turn pipeline (one batched plan for the level).

    A withheld recording shows no transcript (its ``.transcript.md`` leaves the level and its clip
    reads ``transcriptAvailable: false``). A recording with turns lists its bundle files under
    ``transcriptFiles`` (``[{name, format, track, timestamps, url}]``) while the pipeline is on."""
    ids = [str(e.get("_txMedia") or e.get("mediaId")) for e in entries
           if e.get("_txMedia") or (e.get("mediaId") and e.get("transcriptAvailable"))]
    if not ids:
        return entries
    plan = await bundle_plan(ids, client=client)
    out: list[dict[str, Any]] = []
    for e in entries:
        mid = str(e.get("_txMedia") or e.get("mediaId") or "")
        facts = plan.get(mid)
        if facts is None:
            out.append(e)
            continue
        if facts["withheld"]:
            if e.get("_txMedia"):
                continue
            e["transcriptAvailable"] = False
            out.append(e)
            continue
        if pipeline_on and facts["turns"] and e.get("_media") is not None:
            e["transcriptFiles"] = [
                {"name": name, "format": fmt, "track": track, "timestamps": stamps,
                 "url": transcript_file_url(mid, fmt, track, stamps)}
                for name, fmt, track, stamps, needs in bundle_names(e["_media"], e.get("_nameKw"))
                if facts["timed"] or not needs
            ]
        out.append(e)
    return out


# --- The data browser's report (View data tables and the .xlsx) --------------------------------


async def withhold_refused(media: list[Any], *, client: Any = None) -> set[str]:
    """Blank ``transcriptText`` on every row whose consent was refused (fixed reads per 4 batches).
    Returns the withheld ids. The report's own sheets then never print those words."""
    ids = [str(m.id) for m in media if str(getattr(m, "transcriptText", None) or "").strip()]
    withheld: set[str] = set()
    for start in range(0, len(ids), BATCH * 4):
        chunk = ids[start : start + BATCH * 4]
        verdicts = await _verdicts(chunk, _conn(client))
        withheld |= {mid for mid in chunk if verdicts.get(mid) != ALLOW}
    for m in media:
        if str(m.id) in withheld:
            try:
                m.transcriptText = None
            except (AttributeError, TypeError, ValueError):
                object.__setattr__(m, "transcriptText", None)
    return withheld


async def report_sheets(
    media_ids: list[str], *, sees_spend: bool, sheet: Callable[..., dict[str, Any]], cap: int, color: str,
    client: Any = None,
) -> list[dict[str, Any]]:
    """Three sheets for the report: recordings (summary, quotes, glossary, chips), turns, glossary.

    Loaded :data:`BATCH` recordings at a time; rows stop at ``cap`` per sheet (flagged truncated, with
    the sentence naming the streamed download that has every row)."""
    note = f"Note: capped at {cap} rows. Download data > Transcripts has every row."
    rec_cols = list(recording_columns(sees_spend))
    recs: list[list[Any]] = []
    turns: list[list[Any]] = []
    terms: list[list[Any]] = []
    cut = {"recs": False, "turns": False, "terms": False}
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    for start in range(0, len(ids), BATCH):
        loaded = await load(ids[start : start + BATCH], sees_spend=sees_spend, client=client)
        for rec in loaded.values():
            if rec.get("withheld") or not rec.get("source"):
                continue
            if len(recs) < cap:
                row = recording_row(rec, sees_spend=sees_spend)
                recs.append([row.get(c, "") for c in rec_cols])
            else:
                cut["recs"] = True
            for row in turn_rows(rec):
                if len(turns) < cap:
                    turns.append([row.get(c, "") for c in TURN_COLUMNS])
                else:
                    cut["turns"] = True
            for row in glossary_rows(rec):
                if len(terms) < cap:
                    terms.append([row.get(c, "") for c in GLOSSARY_COLUMNS])
                else:
                    cut["terms"] = True
        del loaded
        if cut["recs"] and cut["turns"] and cut["terms"]:
            break
    import inspect

    takes_note = "note" in inspect.signature(sheet).parameters

    def make(name: str, columns: list[str], rows: list[list[Any]], truncated: bool) -> dict[str, Any]:
        extra = {"note": note} if (truncated and takes_note) else {}
        return sheet(name, color, columns, rows, truncated, **extra)

    return [
        make("Transcript recordings", rec_cols, recs, cut["recs"]),
        make("Transcript turns", list(TURN_COLUMNS), turns, cut["turns"]),
        make("Transcript glossary", list(GLOSSARY_COLUMNS), terms, cut["terms"]),
    ]
