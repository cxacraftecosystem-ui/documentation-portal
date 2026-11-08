from __future__ import annotations

import asyncio
import importlib.util
import inspect
import logging
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.services import naming, transcript_export, transcript_pipeline
from app.services.data_explorer import (
    overview,
    report_cache,
    report_options,
    stats,
    transcript_zip,
    walk,
)
from app.services.data_explorer.zipstream import StreamingZip
from app.services.datareport import i18n, pdf_summary
from app.services.naming_portal import PORTAL

logger = logging.getLogger(__name__)

ZIP_MIME = "application/zip"
XLSX_SIGNATURE = b"PK\x03\x04"
TRANSCRIPTS_MAX = 1000
QUOTES_MAX = 40
QUOTES_PER_TRANSCRIPT = 3
CHUNK = 500
XLSX_PIECE = 1 << 20
TRANSCRIPT_DIR = "Transcripts"
README = "README.txt"
PACK_FORMATS: tuple[tuple[str, str, str], ...] = (
    ("Transcript-Bilingual", "docx", "both"),
    ("Subtitles-With-Speakers", "srt", "original"),
    ("Subtitles-With-Speakers-English", "srt", "english"),
)
PORTAL_TITLES = {"DesignWorkshop": "Design Workshop", "FieldRepository": "Field Repository"}
TYPE_LABELS = (
    ("images", "Images"),
    ("videos", "Videos"),
    ("audios", "Audio recordings"),
    ("documents", "Documents"),
    ("other", "Other files"),
)
ANSWER_LABELS = {"original": "Original only", "english": "English only", "both": "Original and English"}
Say = Callable[..., str]
ENGLISH = i18n.translator("en")


def portal_title() -> str:
    return PORTAL_TITLES.get(PORTAL, PORTAL)


def _browser() -> Any:
    from app.api.routes import data_browser

    return data_browser


def _explorer() -> Any:
    from app.api.routes import data_explorer

    return data_explorer


def _takes(fn: Any, name: str) -> bool:
    try:
        return name in inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return False


@dataclass(frozen=True)
class PackParams:
    path: str = ""
    q: str | None = None
    sheets: str | None = None
    answers: str | None = None
    rows: str | None = None
    links: str | None = None
    name: str | None = None
    status: str | None = None
    extra: dict[str, str] = field(default_factory=dict)
    filters: dict[str, str] = field(default_factory=dict)

    def options(self) -> report_options.ReportOptions:
        try:
            return report_options.parse(
                sheets=self.sheets, answers=self.answers, rows=self.rows, links=self.links, q=self.q
            )
        except report_options.OptionsInvalid as bad:
            raise HTTPException(status_code=422, detail=str(bad)) from None


@dataclass
class Prepared:
    zip_name: str
    xlsx_name: str
    xlsx: bytes
    pdf_name: str
    pdf: bytes
    folder: str
    path: str
    generated_by: str
    generated_at: datetime
    data_version: str
    transcripts: list[tuple[str, str]]
    withheld: int
    capped: bool
    files: int
    bytes: int
    quotes: int
    coverage: bool
    scope_media: Any = None
    delivered: list[Callable[[], Awaitable[None]]] = field(default_factory=list)
    locale: str = "en"


XlsxBuilder = Callable[[Any, PackParams], Awaitable[tuple[Any, ...]]]


async def legacy_xlsx(user: Any, params: PackParams) -> tuple[bytes, str]:
    browser = _browser()
    options = params.options()
    kwargs: dict[str, Any] = {
        "q": params.q,
        "options": options,
        "file_name": report_options.file_name(params.name),
    }
    if _takes(browser._build_report, "status_filter"):
        response = await browser._build_report(params.path, "xlsx", params.status, user, **kwargs)
    else:
        response = await browser._build_report(params.path, "xlsx", user, **kwargs)
    body = bytes(response.body)
    disposition = str(response.headers.get("content-disposition") or "")
    name = kwargs["file_name"] or _disposition_name(disposition) or "Report.xlsx"
    return body, name


def locale_of(params: PackParams) -> str:
    return "hi" if str(params.extra.get("locale") or "").strip().lower() == "hi" else "en"


def _flag(value: str | None) -> bool:
    return str(value or "").strip().lower() in ("1", "true", "yes", "on")


async def core_xlsx(user: Any, params: PackParams) -> tuple[bytes, str, Any]:
    from app.services.datareport import build, contract

    legacy = params.options()
    extras = {key.strip(): True for key in str(params.extra.get("extras") or "").split(",") if key.strip()}
    locale = locale_of(params)
    options = contract.ReportOptions(
        sheets=list(legacy.sheets) if legacy.sheets is not None else None,
        answers=legacy.answers,
        rows=legacy.rows,
        links=legacy.links,
        thumbnails=_flag(params.extra.get("thumbnails")),
        locale=locale,
        extras=extras,
        q=params.q,
        name=params.name,
    )
    from app.services.data_explorer import filters as explorer_filters

    flt = explorer_filters.parse(params.filters) if params.filters else None
    built = await build.build_report(user, params.path, options, status=params.status, flt=flt)
    return built.payload, built.file_name, built.ctx


async def auto_xlsx(user: Any, params: PackParams) -> tuple[Any, ...]:
    ready = importlib.util.find_spec("app.services.datareport.build") is not None
    return await (core_xlsx if ready else legacy_xlsx)(user, params)


XLSX_BUILDER: XlsxBuilder = auto_xlsx


def _disposition_name(header: str) -> str | None:
    marker = 'filename="'
    at = header.find(marker)
    if at < 0:
        return None
    end = header.find('"', at + len(marker))
    return header[at + len(marker) : end] or None if end > at else None


async def _scope(user: Any, status_value: str | None) -> Any:
    scope_for = _browser()._scope_for
    if _takes(scope_for, "status_filter"):
        scope = await scope_for(user, status_value)
    else:
        scope = await scope_for(user)
    narrowed = getattr(scope, "for_download", None)
    return narrowed() if callable(narrowed) else scope


def _subject(level: str) -> str:
    return "All Records" if level == "Repository" else level


def _transcript_candidates(files: list[dict[str, Any]]) -> list[tuple[str, str]]:
    seen: dict[str, str] = {}
    for entry in files:
        media_id = entry.get("mediaId")
        kind = str(entry.get("mediaType") or "").upper()
        if not media_id or kind not in {"AUDIO", "VIDEO", "TRANSCRIPT"}:
            continue
        name = entry.get("originalFilename") or str(entry.get("path") or "").rsplit("/", 1)[-1]
        if str(media_id) not in seen or kind != "TRANSCRIPT":
            seen[str(media_id)] = str(name or media_id)
    return list(seen.items())


async def _media_rows(ids: list[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for start in range(0, len(ids), CHUNK):
        rows += await overview._media_rows(ids[start : start + CHUNK])
    return rows


def _status_bars(counts: dict[str, int], t: Say = ENGLISH) -> list[tuple[str, int, str]]:
    waiting = max(0, counts["total"] - counts["ready"] - counts["translating"] - counts["failed"])
    return [
        (t("pack.status.ready", "Ready"), counts["ready"], pdf_summary.GREEN),
        (t("pack.status.working", "Transcribing or translating"), counts["translating"], pdf_summary.AMBER),
        (t("pack.status.failed", "Failed"), counts["failed"], pdf_summary.RED),
        (t("pack.status.waiting", "Not transcribed yet"), waiting, pdf_summary.GREY),
    ]


async def _quotes(ids: list[str], names: dict[str, str]) -> list[pdf_summary.Quote]:
    found: list[pdf_summary.Quote] = []
    for start in range(0, len(ids), CHUNK):
        chunk = ids[start : start + CHUNK]
        rows = await db.transcriptinsight.find_many(
            where={"mediaFileId": {"in": chunk}, "supersededAt": None},
            order=[{"generatedAt": "desc"}, {"id": "desc"}],
        )
        taken: set[str] = set()
        for row in rows:
            media_id = str(row.mediaFileId)
            if media_id in taken:
                continue
            taken.add(media_id)
            raw = row.quotes if isinstance(row.quotes, list) else []
            for item in raw[:QUOTES_PER_TRANSCRIPT]:
                if not isinstance(item, dict):
                    continue
                original = str(item.get("original") or "").strip()
                if not original:
                    continue
                english = item.get("english")
                speaker = item.get("speaker")
                found.append(
                    pdf_summary.Quote(
                        original=original,
                        english=str(english).strip() if isinstance(english, str) else "",
                        speaker=str(speaker).strip() if isinstance(speaker, str) and speaker.strip() else None,
                        source=names.get(media_id, ""),
                    )
                )
                if len(found) >= QUOTES_MAX:
                    return found
    return found


async def _ctx_quotes(ctx: Any, allowed: set[str]) -> list[pdf_summary.Quote] | None:
    if ctx is None:
        return None
    try:
        from app.services.datareport.sheets import ai_quotes

        found = await ai_quotes.top_quotes(ctx, limit=QUOTES_MAX * 2)
    except Exception:
        logger.warning("report pack: the quotes helper failed; reading stored quotes", exc_info=True)
        return None
    out: list[pdf_summary.Quote] = []
    for row in found or []:
        original = str(row.get("original") or "").strip()
        if not original or str(row.get("mediaId") or "") not in allowed:
            continue
        source = " · ".join(str(x) for x in (row.get("artisan"), row.get("file")) if x)
        out.append(
            pdf_summary.Quote(
                original=original,
                english=str(row.get("english") or "").strip(),
                speaker=str(row.get("speaker") or "").strip() or None,
                source=source,
            )
        )
        if len(out) >= QUOTES_MAX:
            break
    return out


async def _coverage(norm: str, scope: Any, t: Say = ENGLISH) -> pdf_summary.Coverage | None:
    segs = [s for s in norm.split("/") if s]
    if len(segs) < 2 or segs[0] not in ("by-workshop", "workshops"):
        return None
    try:
        data = await overview.workshop_overview(_browser(), f"{segs[0]}/{segs[1]}", scope)
    except HTTPException:
        return None
    only = overview.artisan_of(norm)
    artisans = [a for a in data.get("artisans") or [] if only is None or a.get("id") == only]
    sections = [(str(s["key"]), str(s["label"])) for s in data.get("sections") or []]
    if not artisans or not sections:
        return None
    order = [key for key, _ in sections]
    rows = []
    for artisan in sorted(artisans, key=lambda a: (str(a.get("name") or "").lower(), str(a.get("id")))):
        states = {str(s["key"]): str(s["state"]) for s in artisan.get("sections") or []}
        counts = artisan.get("transcripts") or {}
        fields = artisan.get("fields") or {}
        rows.append(
            pdf_summary.CoverageRow(
                name=str(artisan.get("name") or t("pack.unnamedArtisan", "Unnamed artisan")),
                craft=str(artisan.get("craft") or ""),
                filled=int(fields.get("filled") or 0),
                total=int(fields.get("total") or 0),
                files=int((artisan.get("media") or {}).get("files") or 0),
                transcripts=t(
                    "pack.readyOf",
                    "{ready} of {total} ready",
                    ready=int(counts.get("ready") or 0),
                    total=int(counts.get("total") or 0),
                ),
                states=[states.get(key, "missing") for key in order],
            )
        )
    return pdf_summary.Coverage(title=str(data.get("title") or segs[1]), sections=sections, rows=rows)


def _section_bars(block: pdf_summary.Coverage) -> list[pdf_summary.SectionBar]:
    bars = []
    for index, (_key, label) in enumerate(block.sections):
        states = [row.states[index] for row in block.rows if index < len(row.states)]
        bars.append(
            pdf_summary.SectionBar(
                label=label,
                complete=states.count("complete"),
                partial=states.count("partial"),
                missing=states.count("missing"),
            )
        )
    return bars


def _who(user: Any) -> str:
    for attr in ("name", "email"):
        value = getattr(user, attr, None)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return "a portal user"


def _option_rows(params: PackParams, t: Say = ENGLISH) -> list[tuple[str, str]]:
    options = params.options()
    answer = ANSWER_LABELS.get(options.answers, options.answers)
    rows = [(t("pack.option.answers", "Answers"), t(f"pack.answers.{options.answers}", answer))]
    if options.sheets:
        rows.append((t("pack.option.sheets", "Sheets"), ", ".join(options.sheets)))
    if options.filters_rows:
        rows.append((t("pack.option.rows", "Rows"), t("pack.option.matching", "Only rows matching “{q}”", q=params.q)))
    if not options.links:
        rows.append((t("pack.option.links", "Links"), t("pack.option.noLinks", "File link columns left out")))
    if params.status:
        rows.append((t("pack.option.status", "Review status"), params.status))
    return rows


def pack_contents(xlsx_name: str, pdf_name: str, t: Say = ENGLISH) -> list[tuple[str, str]]:
    return [
        (xlsx_name, t("pack.contents.xlsx", "The full workbook: every sheet, link and data dictionary.")),
        (pdf_name, t("pack.contents.pdf", "This summary: cover, charts, coverage and quotes.")),
        (
            f"{TRANSCRIPT_DIR}/",
            t("pack.contents.transcripts", "Each transcript as a bilingual .docx and timed .srt subtitles."),
        ),
        (README, t("pack.contents.readme", "What every file in the pack is.")),
    ]


async def prepare(user: Any, params: PackParams, *, xlsx_builder: XlsxBuilder | None = None) -> Prepared:
    params.options()
    locale = locale_of(params)
    t = i18n.translator(locale)
    built = await (xlsx_builder or XLSX_BUILDER)(user, params)
    xlsx, xlsx_name, ctx = (*built, None)[:3]
    if not xlsx.startswith(XLSX_SIGNATURE):
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="The workbook could not be built.")
    browser = _browser()
    norm = browser._norm(params.path)
    async with report_cache.QUEUE.slot():
        scope = await _scope(user, params.status)
        files, truncated = await walk.manifest_files(browser._list_level, scope, [(norm, "")], None, fill=False)
        totals = stats.totals(files, truncated=truncated)
        candidates = _transcript_candidates(files)
        del files
        names = dict(candidates)
        rows = await _media_rows([mid for mid, _ in candidates])
        counts = overview.transcript_counts(rows)
        texted = [str(r["id"]) for r in rows if r.get("hasText")]
        withheld = await transcript_export.withheld_ids(texted) if texted else set()
        allowed = [mid for mid in texted if mid not in withheld]
        capped = len(allowed) > TRANSCRIPTS_MAX
        allowed = allowed[:TRANSCRIPTS_MAX]
        quotes = await _ctx_quotes(ctx, set(allowed))
        if quotes is None:
            quotes = await _quotes(allowed, names)
        coverage = await _coverage(norm, scope, t)
        crumbs = await browser._resolve_crumbs(norm)
        level = crumbs[-1]["name"] if crumbs else "Repository"
        version = await report_cache.data_version(db)
        generated_at = naming.now_ist()
        subject = _subject(level)
        pdf_name = naming.export_filename("Dataset", "Report-Summary", "pdf", subject=subject)
        by_type = totals.get("byType") or {}
        media_by_type = [
            (
                t(f"pack.type.{token}", label),
                int(by_type.get(token, {}).get("files") or 0),
                int(by_type.get(token, {}).get("bytes") or 0),
            )
            for token, label in TYPE_LABELS
        ]
        stored_files = sum(count for _, count, _ in media_by_type)
        stored_bytes = sum(size for _, _, size in media_by_type)
        kpis = [
            (t("pack.kpi.files", "Files"), f"{stored_files:,}", pdf_summary.human_size(stored_bytes)),
            (
                t("pack.kpi.recordings", "Recordings"),
                f"{counts['total']:,}",
                t("pack.kpi.recordingsSub", "{n} transcripts in this pack", n=f"{len(allowed):,}"),
            ),
            (
                t("pack.kpi.ready", "Transcripts ready"),
                f"{counts['ready']:,}",
                t("pack.kpi.readySub", "of {n} recordings", n=f"{counts['total']:,}"),
            ),
            (
                t("pack.kpi.translating", "Translating"),
                f"{counts['translating']:,}",
                t("pack.kpi.translatingSub", "transcribing or translating now"),
            ),
            (t("pack.kpi.failed", "Failed"), f"{counts['failed']:,}", t("pack.kpi.failedSub", "need a retry")),
        ]
        if coverage is not None:
            filled = sum(r.filled for r in coverage.rows)
            total = sum(r.total for r in coverage.rows)
            pct = f"{round(100 * filled / total)}%" if total else "–"
            sub = t("pack.kpi.coverageSub", "{filled} of {total} answers", filled=f"{filled:,}", total=f"{total:,}")
            kpis.append((t("pack.kpi.coverage", "Coverage"), pct, sub))
        else:
            kpis.append((t("pack.kpi.quotes", "Quotes"), f"{len(quotes):,}", t("pack.kpi.quotesSub", "from transcript insights")))
        summary = pdf_summary.Summary(
            portal_title=portal_title(),
            folder=level,
            path=norm,
            generated_by=_who(user),
            generated_at=generated_at,
            data_version=version,
            options=_option_rows(params, t),
            kpis=kpis,
            media_by_type=media_by_type,
            transcript_status=_status_bars(counts, t),
            sections=_section_bars(coverage) if coverage is not None else [],
            coverage=[coverage] if coverage is not None else [],
            quotes=quotes,
            pack_files=pack_contents(xlsx_name, pdf_name, t),
            locale=locale,
        )
        pdf = await asyncio.to_thread(pdf_summary.build, summary)
    zip_stem = report_options.file_name(params.name)
    zip_name = (
        f"{zip_stem[: -len('.xlsx')]}.zip"
        if zip_stem
        else naming.export_filename("Dataset", "Report-Pack", "zip", subject=subject)
    )
    return Prepared(
        zip_name=zip_name,
        xlsx_name=xlsx_name,
        xlsx=xlsx,
        pdf_name=pdf_name,
        pdf=pdf,
        folder=level,
        path=norm,
        generated_by=summary.generated_by,
        generated_at=generated_at,
        data_version=version,
        transcripts=[(mid, names.get(mid, mid)) for mid in allowed],
        withheld=len(withheld),
        capped=capped,
        files=stored_files,
        bytes=stored_bytes,
        quotes=len(quotes),
        coverage=coverage is not None,
        scope_media=getattr(scope, "media", None) or None,
        delivered=list(getattr(ctx, "delivered", None) or []),
        locale=locale,
    )


def transcript_files(media: Any, context: dict[str, Any], doc: Any) -> list[tuple[str, bytes]]:
    out: list[tuple[str, bytes]] = []
    for what, fmt, track in PACK_FORMATS:
        if not transcript_zip._wanted(doc, fmt, track, fmt == "srt"):
            continue
        data = transcript_zip.render_bytes(doc, fmt, track, True)
        if not data.strip():
            continue
        out.append((transcript_pipeline.download_name(media, context, fmt, track, what=what), data))
    return out


DocLoader = Callable[[str], Awaitable[tuple[Any, Any, dict[str, Any]] | None]]


def doc_loader(scope_media: dict[str, Any] | None) -> DocLoader:
    async def load(media_id: str) -> tuple[Any, Any, dict[str, Any]] | None:
        parts: list[dict[str, Any]] = [{"id": media_id}, {"removedAt": None}]
        if scope_media:
            parts.append(scope_media)
        media = await db.mediafile.find_first(where={"AND": parts})
        if media is None:
            return None
        try:
            doc, context = await transcript_pipeline.render_doc(media)
        except transcript_pipeline.PipelineRefused:
            return None
        return media, doc, context

    return load


def readme(prepared: Prepared, written: list[tuple[str, int]], skipped: list[str]) -> str:
    t = i18n.translator(prepared.locale)
    stamp = pdf_summary.stamp(prepared.generated_at, prepared.locale, zone=True)
    docx = sum(1 for name, _ in written if name.lower().endswith(".docx"))
    srt = sum(1 for name, _ in written if name.lower().endswith(".srt"))
    facts = [
        (t("readme.folder", "Folder"), prepared.folder),
        (t("readme.path", "Path"), prepared.path or t("pdf.repository", "Repository")),
        (t("readme.generated", "Generated"), stamp),
        (t("readme.generatedBy", "Generated by"), prepared.generated_by),
        (t("readme.dataVersion", "Data version"), prepared.data_version),
        (
            t("readme.storedFiles", "Stored files"),
            f"{prepared.files:,} ({pdf_summary.human_size(prepared.bytes)})",
        ),
    ]
    width = max(len(label) for label, _ in facts) + 2
    title = t("readme.title", "{portal} · Report pack", portal=portal_title())
    inside = t("readme.inside", "What is inside")
    summary = t(
        "readme.pdf",
        "A printable summary: cover with the key numbers, charts of media by type and transcript status.",
    )
    if prepared.coverage:
        summary += " " + t("readme.pdfCoverage", "It also has completion by questionnaire section and the coverage grid.")
    if prepared.quotes:
        summary += " " + t("readme.pdfQuotes", "It ends with {n} quotes from the transcripts.", n=prepared.quotes)
    lines = [
        title,
        "=" * 40,
        "",
        *[f"{label + ':':<{width}}{value}" for label, value in facts],
        "",
        inside,
        "-" * max(len(inside), 14),
        f"{prepared.xlsx_name}",
        "    "
        + t(
            "readme.xlsx",
            "The full workbook. Every sheet, column and link of the Report (.xlsx); the links open the files in the portal after you sign in.",
        ),
        f"{prepared.pdf_name}",
        "    " + summary,
        f"{TRANSCRIPT_DIR}/",
        "    "
        + t(
            "readme.transcripts",
            "{docx} Word transcripts (.docx, original with English beneath each turn) and {srt} subtitle files (.srt, timed, with speakers; an English .srt where a translation exists).",
            docx=docx,
            srt=srt,
        ),
        f"{README}",
        "    " + t("readme.self", "This file."),
        "",
    ]
    notes: list[str] = []
    if prepared.withheld:
        notes.append(
            t(
                "readme.withheld",
                "{n} transcript(s) are left out because the speakers' consent does not allow sharing them.",
                n=prepared.withheld,
            )
        )
    if prepared.capped:
        notes.append(
            t(
                "readme.capped",
                "The pack holds the first {n} transcripts; open a smaller folder for the rest.",
                n=TRANSCRIPTS_MAX,
            )
        )
    if skipped:
        notes.append(
            t(
                "readme.skipped",
                "{n} recording(s) had no transcript text to export: {names}.",
                n=len(skipped),
                names=", ".join(skipped[:20]),
            )
        )
    notes.append(t("readme.untimed", "A recording without timings has a .docx but no .srt."))
    heading = t("readme.notes", "Notes")
    every = t("readme.everyFile", "Every file")
    lines += [heading, "-" * max(len(heading), 5), *[f"- {note}" for note in notes], ""]
    lines += [every, "-" * max(len(every), 10)]
    lines += [f"{name}  ({pdf_summary.human_size(size)})" for name, size in written]
    lines.append(f"{README}")
    return "\n".join(lines) + "\n"


def _pieces(payload: bytes) -> Iterator[bytes]:
    view = memoryview(payload)
    return (bytes(view[start : start + XLSX_PIECE]) for start in range(0, len(view), XLSX_PIECE))


async def stream(prepared: Prepared, load: DocLoader) -> AsyncIterator[bytes]:
    archive = StreamingZip()
    written: list[tuple[str, int]] = []
    skipped: list[str] = []
    xlsx_name = archive.unique(prepared.xlsx_name)
    archive._names.discard(xlsx_name.lower())
    size = len(prepared.xlsx)
    for out in archive.add_chunks(xlsx_name, _pieces(prepared.xlsx), stored=True):
        yield out
    written.append((xlsx_name, size))
    prepared.xlsx = b""
    out = await asyncio.to_thread(archive.add_bytes, prepared.pdf_name, prepared.pdf)
    written.append((prepared.pdf_name, len(prepared.pdf)))
    prepared.pdf = b""
    if out:
        yield out
    for media_id, label in prepared.transcripts:
        try:
            loaded = await load(media_id)
        except Exception:
            logger.warning("report pack: transcript %s could not be rendered", media_id, exc_info=True)
            loaded = None
        if loaded is None:
            skipped.append(label)
            continue
        media, doc, context = loaded
        files = await asyncio.to_thread(transcript_files, media, context, doc)
        if not files:
            skipped.append(label)
            continue
        for name, data in files:
            path = archive.unique(f"{TRANSCRIPT_DIR}/{name}")
            archive._names.discard(path.lower())
            out = await asyncio.to_thread(archive.add_bytes, path, data)
            written.append((path, len(data)))
            if out:
                yield out
    text = readme(prepared, written, skipped).encode("utf-8")
    out = archive.add_bytes(README, text)
    if out:
        yield out
    yield await asyncio.to_thread(archive.close)
    for hook in prepared.delivered:
        try:
            await hook()
        except Exception:
            logger.warning("report pack: a delivery hook failed", exc_info=True)


async def response_parts(user: Any, params: PackParams) -> tuple[Prepared, AsyncIterator[bytes]]:
    prepared = await prepare(user, params)
    return prepared, stream(prepared, doc_loader(prepared.scope_media))
