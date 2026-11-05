from __future__ import annotations

import inspect
import logging
from collections import Counter
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any

from app.core.db import db
from app.services import transcript_export
from app.services.data_explorer import overview, report_cache
from app.services.datareport import i18n
from app.services.datareport.share_links import locale_of
from app.services.naming_portal import PORTAL as NAMING_PORTAL

logger = logging.getLogger(__name__)

SNAPSHOT_VERSION = 1
DAY_SPAN_MAX = 62
WEEKS_MAX = 52
SECTIONS_MAX = 24
QUESTIONS_MAX = 2000
CHUNK = 500
IST = timezone(timedelta(hours=5, minutes=30))

PORTALS = {"DesignWorkshop": "designer", "FieldRepository": "fieldRepository"}
ENGLISH = i18n.translator("en")



TYPE_ORDER = (
    ("IMAGE", "Photos"),
    ("VIDEO", "Videos"),
    ("AUDIO", "Recordings"),
    ("DOCUMENT", "Documents"),
    ("OTHER", "Other"),
)
TYPE_TOKEN = {"IMAGE": "IMAGE", "VIDEO": "VIDEO", "AUDIO": "AUDIO", "PDF": "DOCUMENT", "DOCUMENT": "DOCUMENT"}

RECORD_KEYS = (
    "workshops",
    "crafts",
    "artisans",
    "products",
    "processes",
    "tools",
    "interviews",
    "designWorkshops",
)

TRANSCRIPT_STATES = (
    ("ready", "Ready"),
    ("translating", "Translating"),
    ("failed", "Failed"),
    ("none", "Not transcribed"),
)


def portal_key() -> str:
    return PORTALS.get(NAMING_PORTAL, "designer")


def type_of(media: Any) -> str:
    return TYPE_TOKEN.get(str(getattr(media, "mediaType", "") or "").split(".")[-1].upper(), "OTHER")


def is_recording(media: Any) -> bool:
    return type_of(media) in ("AUDIO", "VIDEO")


def size_of(media: Any) -> int:
    try:
        return max(0, int(getattr(media, "sizeBytes", 0) or 0))
    except (TypeError, ValueError):
        return 0


def media_by_type(media: list[Any], t: Any = ENGLISH) -> dict[str, Any]:
    counts = Counter(type_of(m) for m in media)
    shown = [(key, label) for key, label in TYPE_ORDER if counts.get(key)]
    return {
        "key": "mediaByType",
        "title": t("cover.chart.mediaByType", "Media by type"),
        "kind": "bar",
        "categories": [t(f"text.{label}", label) for _, label in shown],
        "series": [{"name": t("share.files", "Files"), "values": [counts[key] for key, _ in shown]}],
    }


def _day_of(value: Any) -> date | None:
    if isinstance(value, datetime):
        moment = value if value.tzinfo else value.replace(tzinfo=UTC)
        return moment.astimezone(IST).date()
    if isinstance(value, date):
        return value
    if isinstance(value, str) and value:
        try:
            return _day_of(datetime.fromisoformat(value))
        except ValueError:
            return None
    return None


def recordings_over_time(media: list[Any], t: Any = ENGLISH) -> dict[str, Any]:
    days = [d for d in (_day_of(getattr(m, "recordedAt", None) or getattr(m, "createdAt", None)) for m in media if is_recording(m)) if d]
    chart: dict[str, Any] = {"key": "recordingsOverTime", "kind": "column", "categories": [], "series": [{"name": t("share.recordings", "Recordings"), "values": []}]}
    if not days:
        chart["title"] = t("cover.chart.recordingsPerDay", "Recordings per day")
        return chart
    first, last = min(days), max(days)
    if (last - first).days < DAY_SPAN_MAX:
        counts = Counter(days)
        span = [first + timedelta(days=i) for i in range((last - first).days + 1)]
        chart["title"] = t("cover.chart.recordingsPerDay", "Recordings per day")
        chart["categories"] = [d.isoformat() for d in span]
        chart["series"][0]["values"] = [counts.get(d, 0) for d in span]
        return chart
    weeks = Counter(d - timedelta(days=d.weekday()) for d in days)
    end = last - timedelta(days=last.weekday())
    start = max(first - timedelta(days=first.weekday()), end - timedelta(weeks=WEEKS_MAX - 1))
    span = [start + timedelta(weeks=i) for i in range((end - start).days // 7 + 1)]
    chart["title"] = t("cover.chart.recordingsPerWeek", "Recordings per week")
    chart["period"] = "week"
    chart["categories"] = [d.isoformat() for d in span]
    chart["series"][0]["values"] = [weeks.get(d, 0) for d in span]
    chart["earlierOmitted"] = sum(count for week, count in weeks.items() if week < start)
    return chart


def transcript_states(media: list[Any], flags: dict[str, dict[str, Any]], withheld: set[str]) -> dict[str, int]:
    counts = dict.fromkeys((key for key, _ in TRANSCRIPT_STATES), 0)
    for m in media:
        if not is_recording(m):
            continue
        mid = str(m.id)
        if mid in withheld:
            continue
        row = flags.get(mid) or {
            "mediaType": type_of(m),
            "transcriptStatus": getattr(m, "transcriptStatus", None),
            "hasText": transcript_export.has_text(m),
            "translating": False,
        }
        one = overview.transcript_counts([row])
        if one["failed"]:
            counts["failed"] += 1
        elif one["translating"]:
            counts["translating"] += 1
        elif one["ready"]:
            counts["ready"] += 1
        else:
            counts["none"] += 1
    return counts


def transcript_chart(counts: dict[str, int], t: Any = ENGLISH) -> dict[str, Any]:
    shown = [(key, label) for key, label in TRANSCRIPT_STATES if counts.get(key)]
    return {
        "key": "transcriptStatus",
        "title": t("cover.chart.transcriptStatus", "Transcript status"),
        "kind": "bar",
        "categories": [t(f"text.{label}", label) for _, label in shown],
        "series": [{"name": t("share.recordings", "Recordings"), "values": [counts[key] for key, _ in shown]}],
    }


def section_completion(
    sections: list[dict[str, Any]], answered_by_unit: list[set[str]], t: Any = ENGLISH
) -> tuple[dict[str, int], dict[str, Any]]:
    states: list[Counter[str]] = [Counter() for _ in sections]
    filled = total = 0
    for answered in answered_by_unit:
        fields, per_section = overview.coverage(sections, answered)
        filled += fields["filled"]
        total += fields["total"]
        for index, state in enumerate(per_section):
            states[index][state["state"]] += 1
    shown = [i for i, s in enumerate(sections) if s["questions"]][:SECTIONS_MAX]
    chart = {
        "key": "completionBySection",
        "title": t("cover.chart.completionBySection", "Completion by section"),
        "kind": "stackedBar",
        "categories": [str(sections[i]["label"]) for i in shown],
        "series": [
            {"key": key, "name": t(f"pdf.legend.{key}", label), "values": [states[i][key] for i in shown]}
            for key, label in (("complete", "Complete"), ("partial", "Partial"), ("missing", "Missing"))
        ],
        "sectionsOmitted": max(0, len([s for s in sections if s["questions"]]) - len(shown)),
    }
    return {"filled": filled, "total": total}, chart


def percent(part: int, whole: int) -> int | None:
    if whole <= 0:
        return None
    return round(100 * part / whole)


def kpis(
    media: list[Any],
    records: dict[str, list[Any]],
    transcripts: dict[str, int],
    fields: dict[str, int],
    t: Any = ENGLISH,
) -> list[dict[str, Any]]:
    record_count = sum(len(records.get(key) or []) for key in RECORD_KEYS)
    coverage = percent(fields["filled"], fields["total"])
    recordings = sum(transcripts.values())
    return [
        {"key": "files", "label": t("cover.kpi.files", "Files"), "value": len(media), "unit": "count", "sub": None},
        {"key": "size", "label": t("cover.kpi.size", "Size"), "value": sum(size_of(m) for m in media), "unit": "bytes", "sub": None},
        {"key": "records", "label": t("cover.kpi.records", "Records"), "value": record_count, "unit": "count", "sub": None},
        {
            "key": "transcriptsReady",
            "label": t("cover.kpi.transcriptsReady", "Transcripts ready"),
            "value": transcripts["ready"],
            "unit": "count",
            "sub": t("pack.kpi.readySub", "of {n} recordings", n=recordings) if recordings else None,
        },
        {"key": "transcriptsTranslating", "label": t("cover.kpi.transcriptsTranslating", "Translating"), "value": transcripts["translating"], "unit": "count", "sub": None},
        {"key": "transcriptsFailed", "label": t("cover.kpi.transcriptsFailed", "Failed"), "value": transcripts["failed"], "unit": "count", "sub": None},
        {
            "key": "coverage",
            "label": t("cover.kpi.coverage", "Coverage"),
            "value": coverage,
            "unit": "percent",
            "sub": t("pack.kpi.coverageSub", "{filled} of {total} answers", filled=fields["filled"], total=fields["total"])
            if fields["total"]
            else t("share.noAnswers", "No questionnaire answers"),
        },
    ]


def assemble(
    *,
    title: str,
    crumbs: list[str],
    generated_by: str | None,
    generated_at: datetime,
    data_version: str,
    media: list[Any],
    records: dict[str, list[Any]],
    flags: dict[str, dict[str, Any]],
    withheld: set[str],
    sections: list[dict[str, Any]],
    answered_by_unit: list[set[str]],
    truncated: bool,
    filters: dict[str, str] | None = None,
    charts: list[dict[str, Any]] | None = None,
    locale: str = "en",
) -> dict[str, Any]:
    locale = locale_of(locale)
    t = i18n.translator(locale)
    media = [m for m in media if str(m.id) not in withheld]
    transcripts = transcript_states(media, flags, withheld)
    fields, completion = section_completion(sections, answered_by_unit, t)
    if charts is None:
        charts = [media_by_type(media, t), recordings_over_time(media, t), completion, transcript_chart(transcripts, t)]
    return {
        "version": SNAPSHOT_VERSION,
        "portal": portal_key(),
        "locale": locale,
        "title": title,
        "crumbs": crumbs,
        "generatedBy": generated_by,
        "generatedAt": generated_at.isoformat(),
        "dataVersion": data_version,
        "kpis": kpis(media, records, transcripts, fields, t),
        "charts": charts,
        "filters": dict(filters or {}),
        "withheldTranscripts": len(withheld),
        "truncated": truncated,
    }


CHART_KINDS = {"bar": "bar", "column": "column", "line": "column", "pie": "bar", "doughnut": "bar"}


CHART_TITLES = {
    "mediaByType": "Files by type",
    "recordingsOverTime": "Recordings per day",
    "completionBySection": "Artisans complete, by section",
    "transcriptStatus": "Transcripts",
}


def chart_of(key: str, spec: Any, *, unit: str = "count", t: Any = ENGLISH, period: str | None = None) -> dict[str, Any]:
    if spec is None:
        title = CHART_TITLES.get(key, "")
        return {"key": key, "title": t(f"text.{title}", title), "kind": "bar", "categories": [], "series": [], "unit": unit}
    out = {
        "key": key,
        "title": str(spec.title),
        "kind": CHART_KINDS.get(str(spec.kind), "bar"),
        "categories": [str(c) for c in spec.categories],
        "series": [{"name": str(name), "values": [float(v or 0) for v in values]} for name, values in spec.series],
        "unit": unit,
    }
    if period:
        out["period"] = period
    return out


def _context(
    user: Any,
    scope: Any,
    norm: str,
    title: str,
    records: dict[str, list[Any]],
    version: str,
    now: datetime,
    locale: str = "en",
) -> Any:
    import dataclasses

    from app.services.datareport import contract

    values: dict[str, Any] = {
        "portal": portal_key(),
        "user": user,
        "scope": scope,
        "path": norm,
        "folder_name": title,
        "material": {"records": records},
        "options": contract.ReportOptions(locale=locale_of(locale)),
        "links": None,
        "t": i18n.translator(locale_of(locale)),
        "data_version": version,
        "generated_at": now.isoformat(),
        "cache": None,
    }
    names = {f.name for f in dataclasses.fields(contract.ReportContext)}
    return contract.ReportContext(**{k: v for k, v in values.items() if k in names})


async def shared_charts(
    user: Any,
    scope: Any,
    norm: str,
    title: str,
    records: dict[str, list[Any]],
    version: str,
    now: datetime,
    locale: str = "en",
) -> list[dict[str, Any]] | None:
    try:
        from app.services.datareport.sheets import extras_charts, extras_common, extras_coverage
    except ImportError:
        return None
    try:
        ctx = _context(user, scope, norm, title, records, version, now, locale)
        t = ctx.t
        media = extras_common.records(ctx, "media")
        facts = await extras_common.media_facts(ctx) if media else {}
        coverage = await extras_coverage.compute(ctx)
        over_time = extras_charts.recordings_per_day(media, t)
        weekly = over_time is not None and str(over_time.title) == t("cover.chart.recordingsPerWeek", "Recordings per week")
        return [
            chart_of("mediaByType", extras_charts.media_by_type(media, t), t=t),
            chart_of("recordingsOverTime", over_time, t=t, period="week" if weekly else None),
            chart_of("completionBySection", extras_charts.completion_by_section(coverage, t), unit="percent", t=t),
            chart_of("transcriptStatus", extras_charts.transcript_status(media, facts, t), t=t),
        ]
    except Exception:
        logger.exception("report share: the cover charts failed, using the share's own")
        return None


async def _flags(ids: list[str]) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for start in range(0, len(ids), CHUNK):
        for row in await overview._media_rows(ids[start : start + CHUNK]):
            out[str(row["id"])] = row
    return out


async def _answered_units(interviews: list[Any]) -> list[set[str]]:
    ids = [str(i.id) for i in interviews]
    if not ids:
        return []
    answered = await overview._answered(ids)
    links: dict[str, list[str]] = {}
    for start in range(0, len(ids), CHUNK):
        rows = await db.questionnaireinterview.find_many(
            where={"id": {"in": ids[start : start + CHUNK]}}, include={"artisans": True}
        )
        for row in rows:
            links[str(row.id)] = [str(link.artisanId) for link in (getattr(row, "artisans", None) or [])]
    units: dict[str, set[str]] = {}
    for iid in ids:
        keys = answered.get(iid, set())
        owners = links.get(iid) or [f"interview:{iid}"]
        for owner in owners:
            units.setdefault(owner, set()).update(keys)
    return list(units.values())


async def _sections() -> list[dict[str, Any]]:
    questions = await db.questionnairequestion.find_many(
        where={"isActive": True, "retiredAt": None},
        order=[{"sortOrder": "asc"}, {"id": "asc"}],
        take=QUESTIONS_MAX,
    )
    return overview.sections_of(list(questions))


def _tool_scope(scope: Any) -> Any:
    readable = getattr(scope, "readable", None)
    return readable if readable is not None else scope.records


async def _scope(browser: Any, user: Any, filters: dict[str, str]) -> Any:
    status_filter = filters.get("status")
    if status_filter and "status_filter" in inspect.signature(browser._scope_for).parameters:
        return await browser._scope_for(user, status_filter)
    return await browser._scope_for(user)


async def build(
    browser: Any,
    user: Any,
    norm: str,
    *,
    filters: dict[str, str] | None = None,
    now: datetime | None = None,
    locale: str = "en",
) -> dict[str, Any]:
    from app.services.tool_scope import hide_unseen

    applied = dict(filters or {})
    scope = await _scope(browser, user, applied)
    await browser._list_level(norm, scope)
    segs = [s for s in norm.split("/") if s]
    records = await browser._report_records(segs, scope)
    await hide_unseen(records.get("tools") or [], _tool_scope(scope))
    media = list(records.get("media") or [])
    withheld = await transcript_export.withhold_refused(media)
    recordings = [str(m.id) for m in media if is_recording(m)]
    flags = await _flags(recordings)
    interviews = list(records.get("interviews") or [])
    sections = await _sections() if interviews else []
    units = await _answered_units(interviews)
    crumbs = await browser._resolve_crumbs(norm)
    names = [str(c.get("name") or "") for c in crumbs]
    cap = int(getattr(browser, "REPORT_TAKE", 5000) or 5000)
    truncated = len(media) >= cap or any(len(records.get(key) or []) >= cap for key in RECORD_KEYS)
    title = names[-1] if names else "Repository"
    moment = now or datetime.now(UTC)
    version = await report_cache.data_version(db)
    kept = {**records, "media": [m for m in media if str(m.id) not in withheld]}
    charts = await shared_charts(user, scope, norm, title, kept, version, moment, locale_of(locale))
    return assemble(
        title=title,
        crumbs=names,
        generated_by=getattr(user, "name", None) or None,
        generated_at=moment,
        data_version=version,
        media=media,
        records=records,
        flags=flags,
        withheld=withheld,
        sections=sections,
        answered_by_unit=units,
        truncated=truncated,
        filters=applied,
        charts=charts,
        locale=locale,
    )
