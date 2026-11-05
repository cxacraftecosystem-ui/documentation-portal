from __future__ import annotations

from typing import Any

from app.services.datareport import ai_cache
from app.services.datareport.contract import Column, ReportContext, Sheet, register

SIGHTINGS_CAP = 5000
QUERY_BATCH = 500

SIGHTINGS_SQL = (
    'SELECT s."id", s."mediaFileId", COALESCE(s."startMs", t."startMs") AS "startMs", g."id" AS "termId", '
    'g."term", g."gloss", g."occurrences", c."name" AS "craft", lc."name" AS "namesCraft" '
    'FROM "TranscriptGlossarySighting" s '
    'JOIN "TranscriptGlossaryTerm" g ON g."id" = s."termId" '
    'LEFT JOIN "TranscriptTurn" t ON t."id" = s."turnId" '
    'LEFT JOIN "Craft" c ON c."id" = g."craftId" '
    'LEFT JOIN "Craft" lc ON lc."id" = g."linkedCraftId" '
    'WHERE s."mediaFileId" = ANY($1::text[])'
)


async def _sightings(ids: list[str]) -> list[dict[str, Any]]:
    from app.core.db import db

    found: list[dict[str, Any]] = []
    for chunk in ai_cache.chunks(ids, QUERY_BATCH):
        found += await db.query_raw(SIGHTINGS_SQL, chunk)
    return found


async def collect(ctx: ReportContext) -> dict[str, Any]:
    from app.services import transcript_export

    media = ai_cache.transcribed(ctx)
    ids = [str(ai_cache.value(m, "id")) for m in media]
    order = {mid: index for index, mid in enumerate(ids)}
    by_id = {str(ai_cache.value(m, "id")): m for m in media}
    names = ai_cache.artisan_names(ctx)

    async def build() -> dict[str, Any]:
        withheld = await transcript_export.withheld_ids(ids)
        allowed = [mid for mid in ids if mid not in withheld]
        raw = await _sightings(allowed)
        raw.sort(
            key=lambda r: (
                ai_cache.normalise(r.get("term")),
                str(r.get("termId") or ""),
                order.get(str(r.get("mediaFileId")), len(order)),
                int(r.get("startMs") or 0),
            )
        )
        counts: dict[str, int] = {}
        for row in raw:
            counts[str(row["termId"])] = counts.get(str(row["termId"]), 0) + 1
        out: list[dict[str, Any]] = []
        for row in raw[:SIGHTINGS_CAP]:
            mid = str(row["mediaFileId"])
            m = by_id.get(mid)
            out.append(
                {
                    "termId": str(row["termId"]),
                    "term": str(row.get("term") or ""),
                    "meaning": str(row.get("gloss") or ""),
                    "namesCraft": str(row.get("namesCraft") or ""),
                    "craft": str(row.get("craft") or ""),
                    "inReport": counts[str(row["termId"])],
                    "heardOverall": int(row.get("occurrences") or 0),
                    "file": ai_cache.file_label(m) or mid,
                    "artisan": ai_cache.media_artisan(m, names),
                    "mediaId": mid,
                    "startMs": None if row.get("startMs") is None else int(row["startMs"]),
                }
            )
        return {
            "rows": out,
            "terms": len(counts),
            "capped": len(raw) > SIGHTINGS_CAP,
            "total": len(raw),
            "withheld": len(withheld),
        }

    return await ai_cache.cached(ctx, "glossary", ids, build)


def columns(ctx: ReportContext | None = None) -> list[Column]:
    _answers, links = ai_cache.options_of(ctx)
    cols = [
        Column(
            "term",
            "Term",
            "text",
            "The craft term as it was heard, in its own spelling.",
            20,
            source="TranscriptGlossaryTerm.term",
        ),
        Column(
            "meaning",
            "English meaning",
            "longtext",
            "What the term means, in English.",
            40,
            source="TranscriptGlossaryTerm.gloss",
        ),
        Column(
            "namesCraft",
            "Names the craft",
            "text",
            "The master craft this term is the name of, if any.",
            18,
            source="TranscriptGlossaryTerm.linkedCraftId",
        ),
        Column(
            "craft",
            "Craft",
            "text",
            "The craft the term was collected under.",
            18,
            source="TranscriptGlossaryTerm.craftId",
        ),
        Column(
            "inReport",
            "Times in this report",
            "int",
            "How many recordings in this report use the term.",
            10,
            source="TranscriptGlossarySighting.termId",
        ),
        Column(
            "heardOverall",
            "Times heard overall",
            "int",
            "How many recordings across the portal use the term.",
            10,
            source="TranscriptGlossaryTerm.occurrences",
        ),
        Column(
            "file",
            "Heard in",
            "text",
            "The recording the term was heard in.",
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
            "at",
            "At",
            "duration_ms",
            "Where in the recording the term is first heard.",
            9,
            source="TranscriptGlossarySighting.startMs",
        ),
    ]
    if links:
        cols.append(
            Column(
                "play",
                "Play from",
                "link",
                "Opens the recording in the portal where the term is heard.",
                12,
                source="TranscriptGlossarySighting.startMs",
            )
        )
    cols += [
        Column(
            "termId",
            "Term ID",
            "id",
            "The term's id.",
            26,
            hidden=True,
            group="ids",
            source="TranscriptGlossaryTerm.id",
        ),
        Column(
            "mediaId",
            "Media ID",
            "id",
            "The recording's id.",
            26,
            hidden=True,
            group="ids",
            source="TranscriptGlossarySighting.mediaFileId",
        ),
    ]
    return cols


def rows(ctx: ReportContext, found: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for raw in found:
        row = {k: v for k, v in raw.items() if k != "startMs"}
        row["at"] = raw.get("startMs")
        if ctx.options.links:
            row["play"] = ai_cache.play(ctx, raw["mediaId"], raw.get("startMs"))
        out.append(row)
    return out


class GlossarySheet:
    key = "glossary"
    title = "Glossary"
    order = 160
    option = "glossary"
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[Sheet]:
        return [
            Sheet(key=self.key, name="Glossary", columns=columns(), rows=[], group="transcripts")
        ]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        found = await collect(ctx)
        notes: list[str] = []
        if found.get("capped"):
            notes.append(
                ai_cache.say(
                    ctx,
                    "banner.glossary.capped",
                    "The first {n:,} of {total:,} sightings, by term.",
                    n=SIGHTINGS_CAP,
                    total=found["total"],
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
                name="Glossary",
                columns=columns(ctx),
                rows=rows(ctx, found.get("rows") or []),
                color="0F766E",
                note=" ".join(notes) or None,
                group="transcripts",
            )
        ]


register(GlossarySheet())
