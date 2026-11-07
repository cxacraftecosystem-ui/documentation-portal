from __future__ import annotations

import asyncio
import contextlib
import importlib
import io
import logging
import os
import pickle
import tempfile
import zipfile
from collections.abc import AsyncIterator
from dataclasses import dataclass, field, replace
from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.services.datareport import (
    bundles,
    contract,
    i18n,
    links,
    portal as portals,
    tables,
    transcript_status,
    writer,
)
from app.services.datareport.contract import (
    ROW_DETAIL,
    ROW_KIND,
    Banner,
    Column,
    Link,
    ReportContext,
    Sheet,
)

logger = logging.getLogger(__name__)

WRITE_CHUNK = 2000
XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
ZIP_MIME = "application/zip"
DETAIL_INLINE_MAX = 8 * 1024 * 1024
ENGLISH_ROWS = ("translation", "summary_translation")
ORIGINAL_ROWS = ("original", "summary")
CHANGES_EXTRA = "changes"
ALWAYS = frozenset({"workshops", "artisans", "media", "transcripts", "answers"})
RECORD_SHEETS = frozenset({"workshops", "artisans", "products", "processes", "tools", "sittings", "media", "transcripts", "answers", "designWorkshops"})


@dataclass
class BuiltReport:
    payload: bytes
    file_name: str
    ctx: ReportContext
    cached: bool = False
    failed: list[str] = field(default_factory=list)
    companion: bytes | None = None
    companion_name: str | None = None


@dataclass
class Parts:
    main: bytes
    companion: bytes | None = None
    stats: dict[str, Any] = field(default_factory=dict)


def _browser() -> Any:
    return importlib.import_module("app.api.routes.data_browser")


def _db(browser: Any) -> Any:
    found = getattr(browser, "db", None)
    if found is not None:
        return found
    from app.core.db import db

    return db


def _report_options() -> Any:
    from app.services.data_explorer import report_options

    return report_options


def from_parsed(picked: Any, *, name: str | None = None) -> contract.ReportOptions:
    return contract.ReportOptions(
        sheets=list(picked.sheets) if picked.sheets is not None else None,
        answers=picked.answers,
        rows=picked.rows,
        links=bool(picked.links),
        thumbnails=bool(getattr(picked, "thumbnails", False)),
        locale=getattr(picked, "locale", "en") or "en",
        extras=dict.fromkeys(getattr(picked, "extras", ()) or (), True),
        q=picked.q,
        name=name,
    )


def options_from(raw: dict[str, Any] | None, *, q: str | None = None, name: str | None = None) -> contract.ReportOptions:
    raw = raw or {}
    ro = _report_options()
    try:
        picked = ro.parse(
            sheets=raw.get("sheets"),
            answers=raw.get("answers"),
            rows=raw.get("rows"),
            links=raw.get("links"),
            q=q if q is not None else raw.get("q"),
            thumbnails=raw.get("thumbnails"),
            locale=raw.get("locale"),
            extras=raw.get("extras"),
        )
    except ro.OptionsInvalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    return from_parsed(picked, name=ro.file_name(name) if name else None)


def signature(options: contract.ReportOptions, user_id: str | None) -> str:
    words = " ".join(contract.query_words(options.q)) if options.rows == "filtered" else ""
    parts = [
        ",".join(sorted(s.lower() for s in options.sheets)) if options.sheets is not None else "*",
        options.answers,
        options.rows,
        words,
        "1" if options.links else "0",
        "1" if options.thumbnails else "0",
        options.locale,
        ",".join(sorted(k for k, v in options.extras.items() if v)),
    ]
    if options.extras.get(CHANGES_EXTRA):
        parts.append(f"user:{user_id}")
    return "|".join(parts)


async def _scope(browser: Any, user: Any, status_filter: str | None) -> Any:
    if portals.current().status_filter:
        normalise = getattr(browser, "data_status", None)
        wanted = normalise(status_filter) if callable(normalise) else status_filter
        return await browser._scope_for(user, wanted)
    return await browser._scope_for(user)


def _v(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def _set(row: Any, name: str, value: Any) -> None:
    try:
        setattr(row, name, value)
    except (AttributeError, TypeError, ValueError):
        object.__setattr__(row, name, value)


_MEDIA_FACTS_SQL = (
    'SELECT m."id", m."contentSha256", m."checksum", m."shaVerifiedAt", m."recordedAt", m."createdAt", m."updatedAt", '
    'm."status"::text AS status, m."reviewedById", m."reviewedAt", m."uploadedById", m."transcriptStatus", '
    'm."transcriptError", m."locationId", m."mimeType", m."caption", m."removedAt", '
    'v."durationMs" AS "durationMs", v."translationStatus" AS "versionTranslation" '
    'FROM "MediaFile" m LEFT JOIN LATERAL (SELECT "durationMs", "translationStatus" FROM "TranscriptVersion" '
    'WHERE "mediaFileId" = m."id" AND "supersededAt" IS NULL ORDER BY "version" DESC LIMIT 1) v ON true '
    'WHERE m."id" = ANY($1::text[])'
)


async def enrich_media(db: Any, media: list[Any], withheld: set[str]) -> None:
    ids = [str(_v(m, "id")) for m in media if _v(m, "id")]
    facts: dict[str, dict[str, Any]] = {}
    for start in range(0, len(ids), 1000):
        for row in await db.query_raw(_MEDIA_FACTS_SQL, ids[start : start + 1000]):
            facts[str(row["id"])] = row
    location_ids = sorted({str(r["locationId"]) for r in facts.values() if r.get("locationId")})
    locations: dict[str, Any] = {}
    for start in range(0, len(location_ids), 1000):
        for loc in await db.location.find_many(where={"id": {"in": location_ids[start : start + 1000]}}):
            locations[str(loc.id)] = loc
    for m in media:
        mid = str(_v(m, "id"))
        row = facts.get(mid) or {}
        for name in ("contentSha256", "checksum", "shaVerifiedAt", "recordedAt", "createdAt", "updatedAt", "status", "reviewedById", "reviewedAt", "uploadedById", "transcriptStatus", "transcriptError", "locationId", "mimeType", "caption", "durationMs"):
            if name in row:
                value = row.get(name)
                if name in ("recordedAt", "createdAt", "updatedAt", "shaVerifiedAt", "reviewedAt") and isinstance(value, str):
                    with contextlib.suppress(ValueError):
                        value = datetime.fromisoformat(value)
                _set(m, name, value)
            elif _v(m, name) is None:
                _set(m, name, None)
        if _v(m, "sizeBytes") is not None:
            _set(m, "sizeBytes", int(_v(m, "sizeBytes")))
        _set(m, "translating", str(row.get("versionTranslation") or "").upper() == "PENDING")
        _set(m, "hasTranscript", bool(_v(m, "hasTranscript")) and mid not in withheld)
        _set(m, "transcriptWithheld", mid in withheld)
        _set(m, "location", locations.get(str(row.get("locationId"))) if row.get("locationId") else None)


async def _records(browser: Any, norm: str, scope: Any) -> tuple[dict[str, Any], set[str]]:
    from app.services import transcript_export
    from app.services.tool_scope import hide_unseen

    segs = [s for s in norm.split("/") if s]
    records = await browser._report_records(segs, scope)
    await hide_unseen(records.get("tools") or [], getattr(scope, "readable", None) or scope.records)
    media = list(records.get("media") or [])
    withheld = await transcript_export.withheld_ids([str(m.id) for m in media if transcript_export.has_text(m)])
    await transcript_export.withhold_refused(media)
    return records, set(withheld)


def _user_name(user: Any) -> str:
    return str(_v(user, "name") or _v(user, "email") or "")


def _filters(options: contract.ReportOptions, status_filter: str | None, t: Any) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    if status_filter:
        out.append((t("cover.status", "Review status"), status_filter.replace("_", " ").capitalize()))
    if options.rows == "filtered" and contract.query_words(options.q):
        out.append((t("cover.search", "Search"), str(options.q)))
    answers = {"original": ("cover.answersOriginal", "Original language"), "english": ("cover.answersEnglish", "English"), "both": ("cover.answersBoth", "Original and English")}[options.answers]
    out.append((t("cover.answers", "Answers"), t(*answers)))
    if options.sheets:
        out.append((t("cover.sheets", "Sheets"), ", ".join(options.sheets)))
    if options.locale != "en":
        out.append((t("cover.language", "Report language"), "हिन्दी" if options.locale == "hi" else options.locale))
    return out


def make_cache(key_base: str) -> Any:
    from app.services.data_explorer import report_cache

    async def cache(key: str, factory: Any) -> Any:
        full = report_cache.CACHE.key("datareport", key_base, key)
        found = report_cache.CACHE.get(full)
        if found is not None:
            return found

        async def run() -> Any:
            again = report_cache.CACHE.get(full)
            if again is not None:
                return again
            value = await factory()
            report_cache.CACHE.put(full, value)
            return value

        return await report_cache.CACHE.once(full, run)

    return cache


async def context(user: Any, path: str, options: contract.ReportOptions, *, status_filter: str | None = None, db: Any = None, material: dict[str, Any] | None = None, scope: Any = None, records: dict[str, Any] | None = None, withheld: set[str] | None = None, data_version: str = "", cache_base: str = "") -> ReportContext:
    from app.services import transcript_portal

    if db is None:
        from app.core.db import db as default_db

        db = default_db
    config = portals.current()
    t = i18n.translator(options.locale)
    owner_id = str(_v(user, "id") or "")

    async def keep(label: str, media_ids: list[str]) -> str:
        sizes = {str(_v(m, "id")): int(_v(m, "sizeBytes") or 0) for m in (records or {}).get("media") or []}
        return await bundles.store(db, owner_id, label, media_ids, path=path, sizes=sizes)

    ctx = ReportContext(
        portal=config.key,
        user=user,
        scope=scope,
        path=path,
        folder_name=(material or {}).get("level") or "Repository",
        material={"records": records or {}, "sheets": (material or {}).get("sheets") or [], "level": (material or {}).get("level"), "withheld": withheld or set()},
        options=options,
        links=links.PortalLinks(portals.web_base(), config, store=keep if owner_id else None),
        t=t,
        data_version=data_version,
        generated_at=datetime.now(writer.IST).replace(microsecond=0).isoformat(),
        cache=make_cache(cache_base or data_version),
        portal_name=t(f"cover.{config.key}", config.name),
        generated_by=_user_name(user),
        filters=_filters(options, status_filter, t),
        sees_spend=bool(transcript_portal.may_see_spend(user)),
    )
    ctx.shared["db"] = db
    return ctx


LEGACY_SHEETS = {
    "all records": "everything",
    "transcript turns": "transcripts",
    "transcript recordings": "transcripts",
    "transcript glossary": "glossarysightings",
    "media by hierarchy": "media",
    "media by uploader": "media",
    "media by type": "media",
    "questionnaire answers": "answers",
    "interviews": "sittings",
}


def _wanted(options: contract.ReportOptions) -> set[str] | None:
    if options.sheets is None:
        return None
    picked = {s.strip().lower() for s in options.sheets if s.strip()}
    return picked | {LEGACY_SHEETS[s] for s in picked if s in LEGACY_SHEETS}


def _picked(wanted: set[str] | None, plugin: Any, sheet: Sheet) -> bool:
    if wanted is None:
        return True
    names = {str(plugin.key).lower(), str(plugin.title).lower(), sheet.key.lower(), sheet.name.lower(), sheet.name.lower().split(" · ")[0]}
    return bool(names & wanted)


def _drop(column: Column, options: contract.ReportOptions, langs: set[str]) -> bool:
    if column.kind == "link" and not options.links:
        return True
    if column.kind == "image" and not options.thumbnails:
        return True
    if options.answers == "original" and column.lang == "english":
        return True
    return options.answers == "english" and column.lang == "original" and "english" in langs


async def _iterate(rows: Any) -> AsyncIterator[dict[str, Any]]:
    if hasattr(rows, "__aiter__"):
        async for row in rows:
            yield row
        return
    for index, row in enumerate(rows or ()):
        yield row
        if index % 5000 == 4999:
            await asyncio.sleep(0)


def shape(sheet: Sheet, options: contract.ReportOptions) -> Sheet:
    langs = {c.lang for c in sheet.columns if c.lang}
    columns = [c for c in sheet.columns if not _drop(c, options, langs)] or list(sheet.columns[:1])
    skip: tuple[str, ...] = ()
    if sheet.row_pairs and options.answers == "original":
        skip = ENGLISH_ROWS
    elif sheet.row_pairs and options.answers == "english":
        skip = ORIGINAL_ROWS
    strip_links = not options.links

    async def rows() -> AsyncIterator[dict[str, Any]]:
        async for row in _iterate(sheet.rows):
            if skip and row.get(ROW_KIND) in skip:
                continue
            yield {k: (v.text if isinstance(v, Link) else v) for k, v in row.items()} if strip_links else row

    return replace(sheet, columns=columns, rows=rows(), banners=[b for b in sheet.banners if options.links or not b.url], media_ids=sheet.media_ids if options.links else [])


async def collect_sheets(ctx: ReportContext, failed: list[str]) -> list[tuple[Any, Sheet]]:
    wanted = _wanted(ctx.options)
    out: list[tuple[Any, Sheet]] = []
    for plugin in contract.plugins(ctx.portal):
        if plugin.option and not ctx.options.extras.get(plugin.option):
            continue
        try:
            made = await plugin.build(ctx)
        except Exception:
            logger.exception("report sheet %s failed", plugin.key)
            failed.append(str(plugin.title))
            continue
        for sheet in made or []:
            if _picked(wanted, plugin, sheet):
                out.append((plugin, sheet))
    return out


async def coverage_percent(ctx: ReportContext) -> tuple[int, int] | None:
    from app.services.data_explorer import overview

    store = await tables.shared_store(ctx)
    questions = [q for q in store.rows.get("QuestionnaireQuestion", ()) if _v(q, "isActive") and not _v(q, "retiredAt")]
    artisans = store.ids("Artisan")
    if not questions or not artisans:
        return None
    sections = overview.sections_of(questions)
    sittings: dict[str, list[str]] = {}
    for link in store.rows.get("QuestionnaireInterviewArtisan", ()):
        sittings.setdefault(str(_v(link, "artisanId")), []).append(str(_v(link, "interviewId")))
    interview_ids = sorted({i for ids in sittings.values() for i in ids})
    answered = await overview._answered(interview_ids) if interview_ids else {}
    filled = total = 0
    for aid in artisans:
        keys: set[str] = set()
        for iid in sittings.get(aid, []):
            keys |= answered.get(iid, set())
        counts, _states = overview.coverage(sections, keys)
        filled += counts["filled"]
        total += counts["total"]
    return filled, total


async def core_kpis(ctx: ReportContext) -> list[tuple[str, str, str]]:
    t = ctx.t
    store = await tables.shared_store(ctx)
    media = list(store.rows.get("MediaFile", ()))
    records = sum(len(store.rows.get(m, ())) for m in ("Workshop", "Craft", "Artisan", "ProductDocumentation", "Process", "ToolDocumentation", "QuestionnaireInterview", "DesignWorkshop"))
    states = ctx.shared.get("transcriptStates")
    if states is None:
        states = await transcript_status.states(ctx.shared["db"], [m for m in media if str(_v(m, "mediaType") or "").upper().endswith(("AUDIO", "VIDEO"))], store.withheld)
        ctx.shared["transcriptStates"] = states
    recordings = [s for s in states.values() if not s.transcript.startswith("Withheld")]
    ready = sum(1 for s in recordings if s.ready and not s.translating and not s.failed)
    translating = sum(1 for s in recordings if s.translating or s.transcript == "Transcribing")
    failed = sum(1 for s in recordings if s.failed)
    size = sum(int(_v(m, "sizeBytes") or 0) for m in media)
    out = [
        (t("cover.kpi.files", "Files"), f"{len(media):,}", t("cover.kpi.sub.files", "{n} files").format(n=f"{len(media):,}")),
        (t("cover.kpi.size", "Size"), writer.human_size(size), ""),
        (t("cover.kpi.records", "Records"), f"{records:,}", ""),
        (t("cover.kpi.transcriptsReady", "Transcripts ready"), f"{ready:,}", t("cover.kpi.sub.ofTotal", "of {total}").format(total=f"{len(recordings):,}")),
        (t("cover.kpi.transcriptsTranslating", "Translating"), f"{translating:,}", ""),
        (t("cover.kpi.transcriptsFailed", "Failed"), f"{failed:,}", ""),
    ]
    try:
        covered = await coverage_percent(ctx)
    except Exception:
        logger.exception("report coverage failed")
        covered = None
    if covered and covered[1]:
        out.append((t("cover.kpi.coverage", "Coverage"), f"{round(100 * covered[0] / covered[1])}%", t("cover.kpi.sub.sections", "of questionnaire sections answered")))
    return out


def split_needed(chosen: list[tuple[Any, Sheet]]) -> bool:
    return any(sheet.detail_bytes > DETAIL_INLINE_MAX for _plugin, sheet in chosen)


def _level(ctx: ReportContext) -> str:
    return str(ctx.material.get("level") or "Repository")


async def _materialise(sheet: Sheet) -> Sheet:
    if sheet.count is not None or sheet.link_bound is not None or isinstance(sheet.rows, (list, tuple)):
        return sheet
    if not any(c.kind == "link" for c in sheet.columns):
        return sheet
    rows = [row async for row in _iterate(sheet.rows)]
    return replace(sheet, rows=rows, count=len(rows))


def _stats(book: writer.ReportWriter) -> dict[str, Any]:
    return {
        "links": book.links,
        "sheets": [(s.title, s.rows, s.links, s.xml_bytes, s.table, s.url_text) for s in book.summaries],
    }


def _read(path: str) -> bytes:
    with open(path, "rb") as handle_in:
        return handle_in.read()


async def render_parts(ctx: ReportContext, failed: list[str] | None = None) -> Parts:
    failed = failed if failed is not None else []
    chosen = await collect_sheets(ctx, failed)
    if not chosen:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No such sheet")
    if ctx.options.thumbnails:
        from app.services.datareport import thumbnails

        store = await tables.shared_store(ctx)
        with contextlib.suppress(Exception):
            ctx.thumbnails.update(await thumbnails.collect(list(store.rows.get("MediaFile", ()))))
    kpis = await core_kpis(ctx)
    split = split_needed(chosen)
    paths: list[str] = []
    for _ in range(2 if split else 1):
        handle, path = tempfile.mkstemp(suffix=".xlsx", prefix="report-")
        os.close(handle)
        paths.append(path)
    try:
        brand = portals.current().brand
        book = writer.ReportWriter(paths[0], ctx, brand=brand)
        extra = writer.ReportWriter(paths[1], ctx, brand=brand) if split else None
        companion_name = _companion_name(ctx.options, _level(ctx)) if split else ""
        everything: dict[str, int] = {}
        sizes = {str(_v(m, "id")): int(_v(m, "sizeBytes") or 0) for m in (ctx.material.get("records") or {}).get("media") or []}
        explicit = ctx.options.sheets is not None
        for _plugin, raw in chosen:
            if raw.count == 0 and not explicit and raw.key not in ALWAYS:
                continue
            sheet = await _materialise(shape(raw, ctx.options))
            bundle_url = None
            if ctx.options.links and sheet.media_ids:
                try:
                    bundle_url = await ctx.links.bundle(sheet.name, sheet.media_ids)
                except Exception:
                    logger.exception("report bundle failed")
                for mid in sheet.media_ids:
                    everything[mid] = sizes.get(mid, everything.get(mid, 0))
            detail = extra is not None and sheet.detail_bytes > DETAIL_INLINE_MAX
            if detail:
                note = ctx.t("banner.companion", "Turn-by-turn rows are in the companion workbook {name}, which comes with this download and with the report pack.").format(name=companion_name)
                state = book.begin(replace(sheet, banners=[*sheet.banners, Banner(note)][:3]), bundle_url=bundle_url)
                about = ctx.t("note.transcriptTurns", "Each speaker turn of every recording, in the original followed by its English translation. The whole transcripts and their links are in the main workbook.")
                twin = extra.begin(replace(sheet, description=about, note=about, banners=[], media_ids=[], link_bound=0), bundle_url=None)
            else:
                state = book.begin(sheet, bundle_url=bundle_url)
                twin = None
            batch: list[dict[str, Any]] = []
            full: list[dict[str, Any]] = []
            async for row in _iterate(sheet.rows):
                if twin is not None:
                    if row.get(ROW_DETAIL):
                        full.append(row)
                    else:
                        batch.append(row)
                else:
                    batch.append(row)
                if len(batch) >= WRITE_CHUNK:
                    await asyncio.to_thread(state.write_rows, batch)
                    batch = []
                if len(full) >= WRITE_CHUNK:
                    await asyncio.to_thread(twin.write_rows, full)
                    full = []
            if batch:
                await asyncio.to_thread(state.write_rows, batch)
            if full and twin is not None:
                await asyncio.to_thread(twin.write_rows, full)
            await asyncio.to_thread(book.end, state)
            if twin is not None:
                await asyncio.to_thread(extra.end, twin)
        everything_url = None
        if ctx.options.links and everything:
            with contextlib.suppress(Exception):
                everything_url = await ctx.links.bundle(f"{ctx.folder_name} report", list(everything))
        if failed:
            ctx.filters.append((ctx.t("cover.notMade", "Sheets that could not be made"), ", ".join(failed)))
        await asyncio.to_thread(
            book.write_cover,
            everything_url=everything_url,
            everything_files=len(everything),
            everything_bytes=sum(everything.values()),
            kpis=[*kpis, *ctx.cover_kpis],
            charts=list(ctx.cover_charts),
        )
        await asyncio.to_thread(book.write_dictionary)
        stats = {"main": _stats(book)}
        await asyncio.to_thread(writer.close, book)
        main = _read(paths[0])
        companion = None
        if extra is not None:
            await asyncio.to_thread(extra.write_cover, everything_url=None, everything_files=0, everything_bytes=0, kpis=list(kpis), charts=[])
            await asyncio.to_thread(extra.write_dictionary)
            stats["companion"] = _stats(extra)
            await asyncio.to_thread(writer.close, extra)
            companion = _read(paths[1])
    finally:
        for path in paths:
            with contextlib.suppress(OSError):
                os.remove(path)
    for payload in (main, companion):
        if payload is not None and not payload.startswith(b"PK\x03\x04"):
            raise HTTPException(status_code=500, detail="The report could not be written. Try again.")
    return Parts(main, companion, stats)


async def render(ctx: ReportContext, failed: list[str] | None = None) -> bytes:
    return (await render_parts(ctx, failed)).main


def _companion_name(options: contract.ReportOptions, level: str) -> str:
    from app.services import naming

    if options.name:
        stem = options.name[: -len(".xlsx")] if options.name.lower().endswith(".xlsx") else options.name
        return f"{stem}_Transcripts.xlsx"
    return naming.export_filename("Dataset", "Report-Transcripts", "xlsx", subject="All Records" if level == "Repository" else level)


def zip_name(built: BuiltReport) -> str:
    stem = built.file_name[: -len(".xlsx")] if built.file_name.lower().endswith(".xlsx") else built.file_name
    return f"{stem}.zip"


def deliverable(built: BuiltReport, *, parts: bool) -> tuple[bytes, str, str]:
    if not parts or not built.companion:
        return built.payload, built.file_name, XLSX_MIME
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr(built.file_name, built.payload)
        archive.writestr(built.companion_name or "Transcripts.xlsx", built.companion)
    return out.getvalue(), zip_name(built), ZIP_MIME


def _file_name(options: contract.ReportOptions, level: str) -> str:
    from app.services import naming

    if options.name:
        return options.name
    return naming.export_filename("Dataset", "Report", "xlsx", subject="All Records" if level == "Repository" else level)


def _stamp_hooks(ctx: ReportContext, user: Any, path: str) -> None:
    async def stamp() -> None:
        await delivered(user, path)

    ctx.delivered.append(stamp)


async def delivered(user: Any, path: str) -> None:
    try:
        module = importlib.import_module("app.services.datareport.extras_changes")
    except ImportError:
        return
    hook = getattr(module, "stamp", None)
    if hook is None:
        return
    try:
        result = hook(str(_v(user, "id") or ""), path)
        if asyncio.iscoroutine(result):
            await result
    except Exception:
        logger.exception("report delivery stamp failed")


async def build_report(user: Any, path: str, options: contract.ReportOptions, *, status: str | None = None) -> BuiltReport:
    from app.services import transcript_portal
    from app.services.data_explorer import report_cache

    browser = _browser()
    db = _db(browser)
    scope = await _scope(browser, user, status)
    norm = browser._norm(path)
    sees_spend = bool(transcript_portal.may_see_spend(user))
    key, material, _cached = await browser._material(norm, scope, sees_spend)
    version = await report_cache.data_version(db)
    sig = signature(options, str(_v(user, "id") or ""))
    file_key = f"xlsx3:{sig}"
    level = str(material.get("level") or "Repository")
    stored = report_cache.CACHE.file(key, file_key)
    if stored is not None:
        payload, kpis, charts, companion = pickle.loads(stored)
        ctx = await context(user, path, options, status_filter=status, db=db, material=material, scope=scope, data_version=version, cache_base=key)
        ctx.cover_kpis.extend(kpis)
        ctx.cover_charts.extend(charts)
        _stamp_hooks(ctx, user, path)
        return BuiltReport(payload, _file_name(options, level), ctx, cached=True, companion=companion, companion_name=_companion_name(options, level) if companion else None)

    async def work() -> tuple[Parts, ReportContext, list[str]]:
        async with report_cache.QUEUE.slot():
            try:
                records, withheld = await _records(browser, norm, scope)
                await enrich_media(db, list(records.get("media") or []), withheld)
                ctx = await context(user, path, options, status_filter=status, db=db, material=material, scope=scope, records=records, withheld=withheld, data_version=version, cache_base=key)
                failed: list[str] = []
                parts = await render_parts(ctx, failed)
                return parts, ctx, failed
            finally:
                trim = getattr(browser, "_trim_heap", None)
                if callable(trim):
                    trim()

    parts, ctx, failed = await work()
    if not failed:
        with contextlib.suppress(Exception):
            report_cache.CACHE.put_file(key, file_key, pickle.dumps((parts.main, list(ctx.cover_kpis), list(ctx.cover_charts), parts.companion), protocol=pickle.HIGHEST_PROTOCOL))
    _stamp_hooks(ctx, user, path)
    return BuiltReport(parts.main, _file_name(options, level), ctx, failed=failed, companion=parts.companion, companion_name=_companion_name(options, level) if parts.companion else None)


async def build_xlsx(user: Any, path: str, options: contract.ReportOptions, *, status: str | None = None) -> tuple[bytes, str]:
    built = await build_report(user, path, options, status=status)
    return built.payload, built.file_name


def _count(sheet: Sheet) -> int | None:
    if sheet.count is not None:
        return sheet.count
    if isinstance(sheet.rows, (list, tuple)):
        return len(sheet.rows)
    return None


async def catalog(user: Any, path: str, *, status: str | None = None, q: str | None = None) -> dict[str, Any]:
    from app.services import transcript_portal
    from app.services.data_explorer import report_cache

    browser = _browser()
    db = _db(browser)
    scope = await _scope(browser, user, status)
    norm = browser._norm(path)
    sees_spend = bool(transcript_portal.may_see_spend(user))
    key, material, _cached = await browser._material(norm, scope, sees_spend)
    words = " ".join(contract.query_words(q))
    cache_key = report_cache.CACHE.key("datareport-sheets", key, words)
    found = report_cache.CACHE.get(cache_key)
    if found is not None:
        return found
    config = portals.current()
    options = contract.ReportOptions(rows="filtered", q=words) if words else contract.ReportOptions()
    async with report_cache.QUEUE.slot():
        records, withheld = await _records(browser, norm, scope)
        await enrich_media(db, list(records.get("media") or []), withheld)
        ctx = await context(user, path, options, status_filter=status, db=db, material=material, scope=scope, records=records, withheld=withheld, cache_base=key)
        ctx.links = links.PortalLinks(portals.web_base(), config)
        sheets: list[dict[str, Any]] = []
        for plugin in contract.plugins(config.key):
            if plugin.option:
                continue
            try:
                made = await plugin.build(ctx)
            except Exception:
                logger.exception("report sheet %s failed", plugin.key)
                continue
            for sheet in made or []:
                sheets.append({"key": sheet.key, "name": sheet.name, "plugin": plugin.key, "count": _count(sheet), "files": len(sheet.media_ids), "group": sheet.group, "description": sheet.description, "record": sheet.key in RECORD_SHEETS})
    extras = [{"key": option, "title": title} for option, title in contract.optional_keys(config.key)]
    total = sum(int(s["count"] or 0) for s in sheets if s["record"])
    normalise = getattr(browser, "data_status", None)
    shown = (normalise(status) if callable(normalise) else status) if config.status_filter and status else None
    answer = {"path": norm, "sheets": sheets, "extras": extras, "locales": list(i18n.LOCALES), "status": shown, "q": words or None, "records": total}
    report_cache.CACHE.put(cache_key, answer)
    return answer
