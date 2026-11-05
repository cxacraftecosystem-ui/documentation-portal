from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any

from app.services.datareport import tables, transcript_status
from app.services.datareport.contract import (
    Column,
    Image,
    Link,
    ReportContext,
    Sheet,
    query_words,
    register,
)
from app.services.datareport.sheets.core_transcripts import attached_to

CHUNK = 200
SKIP = ("transcriptText", "transcriptSummary", "transcriptError")

MEDIA_TABLE = tables.Table("media", "Media", "MediaFile", 85, (), "Every file in this folder, with links that download it or open it in the portal.", tables.MEDIA)

EXTRA = [
    Column("thumbnail", "Preview", "image", "A small preview of the picture (when Report options include thumbnails).", width=9),
    Column("attachedTo", "Attached to", "text", "The record the file belongs to.", width=26, source="MediaFile.linkedRecordType,MediaFile.linkedRecordId"),
    Column("durationMs", "Duration", "duration_ms", "Length of the recording (mm:ss), from its transcript.", total="sum", source="TranscriptVersion.durationMs"),
    Column("transcriptState", "Transcript", "status", "Ready, Transcribing, Failed with the reason, or Not requested.", width=20, source="MediaFile.transcriptStatus,MediaFile.transcriptError"),
    Column("translationState", "Translation", "status", "Translated, Translating N%, Failed with the reason, or Not requested.", width=22, source="TranscriptVersion.translationStatus"),
    Column("summary", "Transcript summary", "longtext", "The short summary written for the transcript.", width=50, wrap=True, source="MediaFile.transcriptSummary"),
    Column("place", "Place", "text", "Where the file was captured.", width=22),
    Column("mapLink", "Map", "link", "Opens the capture location in Google Maps.", width=10),
]


def columns(portal: str) -> list[Column]:
    p = tables.plan(MEDIA_TABLE, portal, skip=SKIP)
    lead = [c for c in p.columns if c.key in ("open", "download")]
    rest = [c for c in p.columns if c.key not in ("open", "download")]
    return [*lead, *EXTRA, *rest]


class MediaPlugin:
    key = "media"
    title = "Media"
    order = 85
    option = None
    portals = ("designer", "fieldRepository")

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        return [(self.key, columns("designer"), self.title)]

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        store = await tables.shared_store(ctx)
        db = ctx.shared.get("db")
        if db is None:
            from app.core.db import db as default_db

            db = default_db
        media = list(store.rows.get("MediaFile", ()))
        states = ctx.shared.get("transcriptStates")
        if states is None:
            states = await transcript_status.states(db, media, store.withheld)
            ctx.shared["transcriptStates"] = states
        p = tables.plan(MEDIA_TABLE, ctx.portal, skip=SKIP)
        cols = columns(ctx.portal)
        if not ctx.options.thumbnails:
            cols = [c for c in cols if c.key != "thumbnail"]
        return [
            Sheet(
                key=self.key,
                name=self.title,
                columns=cols,
                rows=self._rows(ctx, db, store, media, states, p),
                color=tables.MEDIA,
                description=MEDIA_TABLE.description,
                media_ids=[str(getattr(m, "id", "")) for m in media],
                media_bytes=sum(int(getattr(m, "sizeBytes", 0) or 0) for m in media),
                count=len(media),
                databar_columns=["sizeBytes", "durationMs"],
            )
        ]

    async def _rows(self, ctx: ReportContext, db: Any, store: tables.Store, media: list[Any], states: dict[str, Any], p: tables.Plan) -> AsyncIterator[dict[str, Any]]:
        t = ctx.t
        wanted = query_words(ctx.options.q) if ctx.options.rows == "filtered" else []
        ids = [str(getattr(m, "id", "")) for m in media]
        slim = {str(getattr(m, "id", "")): m for m in media}
        for start in range(0, len(ids), CHUNK):
            chunk = ids[start : start + CHUNK]
            found = {str(r.id): r for r in await db.mediafile.find_many(where={"id": {"in": chunk}})}
            for mid in chunk:
                row = found.get(mid) or slim.get(mid)
                if row is None:
                    continue
                if mid in store.withheld:
                    for name in ("transcriptText", "transcriptSummary"):
                        try:
                            setattr(row, name, None)
                        except (AttributeError, TypeError, ValueError):
                            object.__setattr__(row, name, None)
                out: dict[str, Any] = {}
                if ctx.options.links:
                    out["open"] = Link(t("link.openInPortal", "Open in portal"), ctx.links.open_media(mid))
                    out["download"] = Link(t("link.download", "Download"), ctx.links.download(mid))
                for key, name, target, kind in p.fields:
                    value = getattr(row, name, None)
                    out[key] = (store.name(target, value) or (str(value) if value else None)) if target else tables.convert(value, kind)
                state = states.get(mid)
                light = slim.get(mid)
                out["attachedTo"] = attached_to(store, row)
                out["durationMs"] = (state.duration_ms if state else None) or getattr(light, "durationMs", None)
                if state is not None and str(getattr(row, "mediaType", "")).upper().endswith(("AUDIO", "VIDEO")):
                    out["transcriptState"] = transcript_status.localise(t, state.transcript)
                    out["translationState"] = transcript_status.localise(t, state.translation)
                out["summary"] = None if mid in store.withheld else getattr(row, "transcriptSummary", None)
                location = getattr(light, "location", None) or getattr(row, "location", None)
                if location is None and getattr(row, "locationId", None):
                    location = store.get("Location", row.locationId)
                if location is not None:
                    out["place"] = ", ".join(str(v) for v in (getattr(location, "placeName", None), getattr(location, "village", None), getattr(location, "district", None), getattr(location, "state", None)) if v)
                    lat, lng = getattr(location, "latitude", None), getattr(location, "longitude", None)
                    if lat is not None and lng is not None and ctx.options.links:
                        out["mapLink"] = Link(t("link.map", "Open in Google Maps"), f"https://www.google.com/maps/search/?api=1&query={lat},{lng}")
                if ctx.options.thumbnails and mid in ctx.thumbnails:
                    out["thumbnail"] = Image(mid, str(getattr(row, "originalFilename", "") or ""))
                if wanted and not tables.row_matches(out, wanted):
                    continue
                yield out
            found.clear()


register(MediaPlugin())
