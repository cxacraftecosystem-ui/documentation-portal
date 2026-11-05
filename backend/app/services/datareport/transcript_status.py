from __future__ import annotations

from dataclasses import dataclass
from typing import Any

BATCH = 500
TRANSLATE_JOB = "TRANSCRIPT_INSIGHTS"
OPEN_JOBS = ("QUEUED", "PROCESSING")

_VERSIONS_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "id", "mediaFileId", "translationStatus", "durationMs", "languages", '
    '"turnCount" FROM "TranscriptVersion" WHERE "mediaFileId" = ANY($1::text[]) AND "supersededAt" IS NULL '
    'ORDER BY "mediaFileId", "version" DESC'
)
_JOBS_SQL = (
    'SELECT DISTINCT ON ("mediaFileId") "mediaFileId", "jobType"::text AS "jobType", "status"::text AS status, "error" '
    'FROM "MediaProcessingJob" WHERE "mediaFileId" = ANY($1::text[]) AND "jobType"::text = $2 '
    'ORDER BY "mediaFileId", "createdAt" DESC'
)
_PROGRESS_SQL = (
    'SELECT "versionId" AS id, '
    "count(*) FILTER (WHERE \"translationStatus\" <> 'ALREADY_ENGLISH')::int AS total, "
    "count(*) FILTER (WHERE \"translationStatus\" = 'TRANSLATED' OR \"editedEnglish\" IS NOT NULL)::int AS done "
    'FROM "TranscriptTurn" WHERE "versionId" = ANY($1::text[]) GROUP BY "versionId"'
)


@dataclass(frozen=True)
class State:
    media_id: str
    transcript: str
    translation: str
    percent: int | None
    reason: str | None
    duration_ms: int | None
    languages: tuple[str, ...]
    version_id: str | None

    @property
    def ready(self) -> bool:
        return self.transcript == "Ready"

    @property
    def translating(self) -> bool:
        return self.translation.startswith("Translating")

    @property
    def failed(self) -> bool:
        return self.transcript.startswith("Failed") or self.translation.startswith("Failed")


def _v(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def _status(value: Any) -> str:
    return str(getattr(value, "value", value) or "").split(".")[-1].upper()


def translation_text(version_status: str | None, done: int, total: int, job: dict[str, Any] | None) -> tuple[str, int | None, str | None]:
    status = (version_status or "").upper()
    job_status = _status((job or {}).get("status"))
    error = str((job or {}).get("error") or "").strip() or None
    percent = round(done * 100 / total) if total else None
    if status == "NOT_NEEDED" or (total == 0 and status in ("COMPLETED", "")):
        return "Already English", None, None
    if status == "COMPLETED" or (total and done >= total):
        return "Translated", 100, None
    if job_status in OPEN_JOBS:
        return f"Translating {percent or 0}%", percent or 0, None
    if status in ("FAILED", "DECLINED") or job_status == "FAILED":
        reason = error or "The translation service did not return a translation."
        return f"Failed: {reason}", percent, reason
    if status == "PARTIAL" and done:
        return f"Translating {percent or 0}%", percent or 0, None
    return "Not requested", percent, None


def transcript_text(media: Any, has_text: bool) -> tuple[str, str | None]:
    if has_text:
        return "Ready", None
    status = _status(_v(media, "transcriptStatus"))
    error = str(_v(media, "transcriptError") or "").strip() or None
    if status in ("PENDING", "QUEUED", "PROCESSING", "RUNNING"):
        return "Transcribing", None
    if status in ("FAILED", "ERROR"):
        reason = error or "Speech-to-text failed."
        return f"Failed: {reason}", reason
    return "Not requested", None


async def states(db: Any, media: list[Any], withheld: set[str] | None = None) -> dict[str, State]:
    withheld = withheld or set()
    ids = [str(_v(m, "id")) for m in media if _v(m, "id")]
    versions: dict[str, dict[str, Any]] = {}
    jobs: dict[str, dict[str, Any]] = {}
    progress: dict[str, tuple[int, int]] = {}
    for start in range(0, len(ids), BATCH):
        chunk = ids[start : start + BATCH]
        try:
            for row in await db.query_raw(_VERSIONS_SQL, chunk):
                versions[str(row["mediaFileId"])] = row
            for row in await db.query_raw(_JOBS_SQL, chunk, TRANSLATE_JOB):
                jobs[str(row["mediaFileId"])] = row
        except Exception:  # noqa: BLE001 - a missing table on an old database reports no transcript state
            break
    version_ids = [str(v["id"]) for v in versions.values()]
    for start in range(0, len(version_ids), BATCH):
        try:
            for row in await db.query_raw(_PROGRESS_SQL, version_ids[start : start + BATCH]):
                progress[str(row["id"])] = (int(row["done"] or 0), int(row["total"] or 0))
        except Exception:  # noqa: BLE001
            break
    out: dict[str, State] = {}
    for m in media:
        mid = str(_v(m, "id"))
        version = versions.get(mid)
        has_text = bool(_v(m, "hasTranscript")) or bool(str(_v(m, "transcriptText") or "").strip()) or bool(version and int(version.get("turnCount") or 0))
        if mid in withheld:
            out[mid] = State(mid, "Withheld: consent refused", "Withheld: consent refused", None, "consent refused", None, (), None)
            continue
        transcript, reason = transcript_text(m, has_text)
        if version is not None and has_text:
            done, total = progress.get(str(version["id"]), (0, 0))
            translation, percent, why = translation_text(version.get("translationStatus"), done, total, jobs.get(mid))
        elif has_text:
            translation, percent, why = "Not requested", None, None
        else:
            translation, percent, why = "Not requested", None, reason
        out[mid] = State(
            media_id=mid,
            transcript=transcript,
            translation=translation,
            percent=percent,
            reason=why,
            duration_ms=int(version["durationMs"]) if version and version.get("durationMs") is not None else None,
            languages=tuple(version.get("languages") or ()) if version else (),
            version_id=str(version["id"]) if version else None,
        )
    return out


def localise(t: Any, text: str) -> str:
    if text.startswith("Translating "):
        pct = text[len("Translating ") :].rstrip("%")
        return t("status.translatingPct", "Translating {pct}%").format(pct=pct)
    if text.startswith("Failed: "):
        return t("status.failedReason", "Failed: {reason}").format(reason=text[len("Failed: ") :])
    keys = {
        "Ready": "status.ready",
        "Translated": "status.translated",
        "Already English": "status.alreadyEnglish",
        "Not requested": "status.notRequested",
        "Transcribing": "status.transcribing",
        "Withheld: consent refused": "status.withheld",
    }
    key = keys.get(text)
    return t(key, text) if key else text
