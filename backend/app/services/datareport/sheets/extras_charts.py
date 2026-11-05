from __future__ import annotations

from collections import Counter
from collections.abc import Callable
from datetime import date, timedelta, timezone
from typing import Any

from app.services.datareport.contract import ChartSpec, ReportContext, Sheet, register
from app.services.datareport.sheets import extras_coverage
from app.services.datareport.sheets.extras_common import (
    Plugin,
    g,
    ident,
    media_facts,
    records,
    transcript_state,
    when,
)

IST = timezone(timedelta(hours=5, minutes=30))
TYPE_LABELS = {
    "IMAGE": "Photos",
    "VIDEO": "Videos",
    "AUDIO": "Recordings",
    "DOCUMENT": "Documents",
}
TYPE_ORDER = ("Photos", "Videos", "Recordings", "Documents", "Other")
TRANSCRIPT_ORDER = ("Ready", "Translating", "Failed", "Not transcribed")
STATUS_KEYS = {
    "Ready": "status.ready",
    "Translating": "status.translating",
    "Failed": "status.failed",
    "Not transcribed": "status.notTranscribed",
}
WEEKLY_FROM_DAYS = 62
WEEKS_MAX = 52

Say = Callable[[str, str], str]


def _plain(_key: str, default: str) -> str:
    return default


def media_by_type(media: list[Any], t: Say = _plain) -> ChartSpec | None:
    counts = Counter(TYPE_LABELS.get(str(g(m, "mediaType") or "").upper(), "Other") for m in media)
    cats = [c for c in TYPE_ORDER if counts.get(c)]
    if not cats:
        return None
    return ChartSpec(
        t("cover.chart.mediaByType", "Files by type"),
        "doughnut",
        [t("cover.chart.type" + c, c) for c in cats],
        [(t("cover.chart.count", "Files"), [float(counts[c]) for c in cats])],
    )


def _days(media: list[Any]) -> list[date]:
    out: list[date] = []
    for m in media:
        if str(g(m, "mediaType") or "").upper() not in ("AUDIO", "VIDEO"):
            continue
        moment = when(g(m, "recordedAt")) or when(g(m, "createdAt"))
        if moment is not None:
            out.append(moment.astimezone(IST).date())
    return out


def recordings_per_day(media: list[Any], t: Say = _plain) -> ChartSpec | None:
    days = _days(media)
    if not days:
        return None
    first, last = min(days), max(days)
    counts = Counter(days)
    if (last - first).days >= WEEKLY_FROM_DAYS:
        start = last - timedelta(days=last.weekday()) - timedelta(weeks=WEEKS_MAX - 1)
        first_week = max(start, first - timedelta(days=first.weekday()))
        weeks: Counter[date] = Counter()
        for day, n in counts.items():
            week = day - timedelta(days=day.weekday())
            if week >= first_week:
                weeks[week] += n
        cats: list[date] = []
        cursor = first_week
        while cursor <= last:
            cats.append(cursor)
            cursor += timedelta(weeks=1)
        return ChartSpec(
            t("cover.chart.recordingsPerWeek", "Recordings per week"),
            "column",
            [d.strftime("%d %b %Y") for d in cats],
            [(t("cover.chart.count", "Recordings"), [float(weeks[d]) for d in cats])],
        )
    span = [first + timedelta(days=i) for i in range((last - first).days + 1)]
    return ChartSpec(
        t("cover.chart.recordingsPerDay", "Recordings per day"),
        "column",
        [d.strftime("%d %b") for d in span],
        [(t("cover.chart.count", "Recordings"), [float(counts[d]) for d in span])],
    )


def completion_by_section(data: dict[str, Any], t: Say = _plain) -> ChartSpec | None:
    sections = data.get("sections") or []
    rows = data.get("artisans") or []
    if not sections or not rows:
        return None
    values = []
    for index in range(len(sections)):
        done = sum(1 for r in rows if r["states"][index]["state"] == "complete")
        values.append(round(100.0 * done / len(rows), 1))
    return ChartSpec(
        t("cover.chart.completionBySection", "Artisans complete, by section"),
        "bar",
        [s["label"] for s in sections],
        [(t("cover.chart.percent", "% of artisans complete"), values)],
    )


def transcript_status(media: list[Any], facts: dict[str, Any], t: Say = _plain) -> ChartSpec | None:
    counts: Counter[str] = Counter()
    for m in media:
        state = transcript_state(facts.get(ident(m)), str(g(m, "mediaType") or "").upper())
        if state:
            counts[state] += 1
    cats = [c for c in TRANSCRIPT_ORDER if counts.get(c)]
    if not cats:
        return None
    return ChartSpec(
        t("cover.chart.transcriptStatus", "Transcripts"),
        "pie",
        [t(STATUS_KEYS[c], c) for c in cats],
        [(t("cover.chart.count", "Recordings"), [float(counts[c]) for c in cats])],
    )


async def charts_for(ctx: ReportContext) -> list[ChartSpec]:
    t = ctx.t or _plain
    media = records(ctx, "media")
    facts = await media_facts(ctx) if media else {}
    coverage = await extras_coverage.compute(ctx)
    found = (
        media_by_type(media, t),
        recordings_per_day(media, t),
        completion_by_section(coverage, t),
        transcript_status(media, facts, t),
    )
    return [chart for chart in found if chart is not None]


async def build(ctx: ReportContext) -> list[Sheet]:
    ctx.cover_charts.extend(await charts_for(ctx))
    return []


register(Plugin("extras_charts", "Charts on the cover", 100, "charts", build, list))
