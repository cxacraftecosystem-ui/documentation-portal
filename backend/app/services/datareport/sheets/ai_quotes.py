from __future__ import annotations

import logging
from typing import Any

from app.services.datareport import ai_cache
from app.services.datareport.contract import Banner, Column, ReportContext, Sheet, register

logger = logging.getLogger(__name__)

QUOTES_CAP = 1500
GENERATE_CAP = 20
LOAD_BATCH = 50


def _languages(rec: dict[str, Any]) -> str:
    return ", ".join(
        str(lang.get("label") or lang.get("code") or "")
        for lang in rec.get("languages") or []
        if lang
    )


async def _queue_missing(ctx: ReportContext, media_ids: list[str]) -> int:
    from app.core.db import db
    from app.services import transcript_insights, transcript_portal

    if not media_ids or not transcript_insights.enabled():
        return 0
    try:
        stored = await transcript_insights.current_insights(media_ids)
        jobs = await transcript_insights.latest_jobs(media_ids)
    except Exception as exc:  # noqa: BLE001 - quotes come from stored rows; queueing is a bonus
        logger.warning("insight lookup failed for the quotes sheet (%s)", type(exc).__name__)
        return 0
    missing = [
        mid
        for mid in media_ids
        if mid not in stored and str(getattr(jobs.get(mid), "status", "") or "") != "FAILED"
    ][:GENERATE_CAP]
    queued = 0
    requester = getattr(ctx.user, "id", None)
    for mid in missing:
        try:
            media = await db.mediafile.find_unique(where={"id": mid})
            if media is None:
                continue
            may, _refusal = await transcript_portal.may_send(media)
            if not may:
                continue
            if (
                str(getattr(jobs.get(mid), "status", "") or "")
                in transcript_insights.ACTIVE_JOB_STATUSES
            ):
                queued += 1
                continue
            if await transcript_insights.enqueue(mid, requested_by_id=requester) is not None:
                queued += 1
        except Exception as exc:  # noqa: BLE001 - one recording never stops the report
            logger.warning("could not queue insights for %s (%s)", mid, type(exc).__name__)
    return queued


async def collect(ctx: ReportContext) -> dict[str, Any]:
    from app.services import transcript_export

    media = ai_cache.transcribed(ctx)
    ids = [str(ai_cache.value(m, "id")) for m in media]
    by_id = {str(ai_cache.value(m, "id")): m for m in media}
    names = ai_cache.artisan_names(ctx)

    async def build() -> dict[str, Any]:
        rows: list[dict[str, Any]] = []
        lacking: list[str] = []
        withheld = 0
        capped = False
        for chunk in ai_cache.chunks(ids, LOAD_BATCH):
            loaded = await transcript_export.load(chunk)
            for mid in chunk:
                rec = loaded.get(mid)
                if rec is None:
                    continue
                if rec.get("withheld"):
                    withheld += 1
                    continue
                if not rec.get("source"):
                    continue
                quotes = rec.get("quotes") or []
                if not quotes:
                    lacking.append(mid)
                    continue
                m = by_id.get(mid)
                turns = rec.get("turns") or []
                for position, quote in enumerate(quotes, start=1):
                    if len(rows) >= QUOTES_CAP:
                        capped = True
                        break
                    rows.append(
                        {
                            "mediaId": mid,
                            "file": ai_cache.file_label(m) or rec.get("file") or mid,
                            "artisan": ai_cache.media_artisan(m, names),
                            "rank": position,
                            "speaker": quote.get("speaker") or "",
                            "original": quote.get("original") or "",
                            "english": quote.get("english") or "",
                            "language": _languages(rec),
                            "startMs": ai_cache.find_start(
                                turns, quote.get("original"), quote.get("english")
                            ),
                        }
                    )
            del loaded
            if capped:
                break
        return {"rows": rows, "lacking": lacking, "withheld": withheld, "capped": capped}

    return await ai_cache.cached(ctx, "quotes", ids, build)


async def queue_missing(ctx: ReportContext, found: dict[str, Any]) -> int:
    lacking = list(found.get("lacking") or [])
    if not lacking:
        return 0
    return await ai_cache.cached(ctx, "quotes-queue", lacking, lambda: _queue_missing(ctx, lacking))


async def top_quotes(ctx: ReportContext, limit: int = 40) -> list[dict[str, Any]]:
    found = await collect(ctx)
    keep = ("mediaId", "file", "artisan", "speaker", "original", "english", "language", "startMs")
    return [{k: row.get(k) for k in keep} for row in (found.get("rows") or [])[: max(limit, 0)]]


def columns(ctx: ReportContext | None = None) -> list[Column]:
    answers, links = ai_cache.options_of(ctx)
    cols = [
        Column(
            "file",
            "Recording",
            "text",
            "The recording the quote was spoken in.",
            28,
            source="MediaFile.originalFilename",
        ),
        Column(
            "artisan",
            "Artisan",
            "text",
            "The artisan the recording is filed under.",
            22,
            source="Artisan.name",
        ),
        Column(
            "rank",
            "Quote",
            "int",
            "The quote's place among this recording's key quotes.",
            8,
            source="TranscriptInsight.quotes",
        ),
        Column(
            "speaker",
            "Speaker",
            "text",
            "Who said it, where the summary names them.",
            16,
            source="TranscriptInsight.quotes",
        ),
    ]
    if answers in ("original", "both"):
        cols.append(
            Column(
                "original",
                "Original",
                "longtext",
                "The words exactly as spoken, in their own language and script.",
                60,
                source="TranscriptInsight.quotes",
                lang="original",
            )
        )
    if answers in ("english", "both"):
        cols.append(
            Column(
                "english",
                "English",
                "longtext",
                "The quote in English.",
                60,
                source="TranscriptInsight.quotes",
                lang="english",
            )
        )
    cols += [
        Column(
            "language",
            "Language",
            "text",
            "The languages heard in the recording.",
            14,
            source="TranscriptVersion.languages",
        ),
        Column(
            "at",
            "At",
            "duration_ms",
            "Where in the recording the quote starts.",
            9,
            source="TranscriptTurn.startMs",
        ),
    ]
    if links:
        cols += [
            Column(
                "play",
                "Play from",
                "link",
                "Opens the recording in the portal at the quote.",
                12,
                source="TranscriptTurn.startMs",
            ),
            Column(
                "open",
                "Recording",
                "link",
                "Opens the recording in the portal.",
                12,
                source="MediaFile.id",
            ),
        ]
    cols.append(
        Column(
            "mediaId",
            "Media ID",
            "id",
            "The recording's id.",
            26,
            hidden=True,
            group="ids",
            source="MediaFile.id",
        )
    )
    return cols


def rows(ctx: ReportContext, found: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for raw in found:
        row = {k: v for k, v in raw.items() if k != "startMs"}
        row["at"] = raw.get("startMs")
        if ctx.options.links:
            row["play"] = ai_cache.play(ctx, raw["mediaId"], raw.get("startMs"))
            row["open"] = ai_cache.open_media(ctx, raw["mediaId"], "Open")
        out.append(row)
    return out


class QuotesSheet:
    key = "quotes"
    title = "Quotes"
    order = 150
    option = "quotes"
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[Sheet]:
        return [Sheet(key=self.key, name="Quotes", columns=columns(), rows=[], group="transcripts")]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        found = await collect(ctx)
        queued = await queue_missing(ctx, found)
        banners: list[Banner] = []
        if queued:
            banners.append(
                Banner(
                    ai_cache.say(
                        ctx,
                        "banner.quotes.queued",
                        "{n} more recordings are being summarised. Their quotes join the next report.",
                        n=queued,
                    )
                )
            )
        notes: list[str] = []
        if found.get("capped"):
            notes.append(
                ai_cache.say(
                    ctx,
                    "banner.quotes.capped",
                    "The first {n:,} quotes, newest recordings first.",
                    n=QUOTES_CAP,
                )
            )
        if found.get("withheld"):
            notes.append(
                ai_cache.say(
                    ctx,
                    "banner.consent.withheld",
                    "{n} recordings are left out because consent withholds their words.",
                    n=found["withheld"],
                )
            )
        return [
            Sheet(
                key=self.key,
                name="Quotes",
                columns=columns(ctx),
                rows=rows(ctx, found.get("rows") or []),
                color="0F766E",
                banners=banners,
                note=" ".join(notes) or None,
                group="transcripts",
            )
        ]


register(QuotesSheet())
