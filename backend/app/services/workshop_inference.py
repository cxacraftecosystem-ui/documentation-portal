from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Iterable

from app.core.db import db
from app.services import naming
from app.services.concurrency import gather_reads

RUNG_PARENT = "PARENT"
RUNG_ARTISANS = "ARTISANS"
RUNG_WINDOW = "WINDOW"

RUNGS: tuple[str, ...] = (RUNG_PARENT, RUNG_ARTISANS, RUNG_WINDOW)

CLEAR_CUT_RUNGS: frozenset[str] = frozenset({RUNG_PARENT})

RUNG_COPY: dict[str, str] = {
    RUNG_PARENT: "the record it belongs to already names that workshop",
    RUNG_ARTISANS: "every artisan in it belongs to that one workshop",
    RUNG_WINDOW: "it was recorded inside that workshop's dates",
}

REASON_NO_EVIDENCE = "NO_EVIDENCE"
REASON_AMBIGUOUS = "AMBIGUOUS"

REASON_COPY: dict[str, str] = {
    REASON_NO_EVIDENCE: "nothing on the record points at a workshop",
    REASON_AMBIGUOUS: "the evidence points at more than one workshop",
}

_SINGLE_DAY = timedelta(days=1)

BUCKETS: tuple[tuple[str, str, str, str], ...] = (
    ("interviews", "questionnaireinterview", "questionnaire interview", "questionnaire interviews"),
    ("media", "mediafile", "media file", "media files"),
    ("products", "productdocumentation", "product record", "product records"),
    ("tools", "tooldocumentation", "tool record", "tool records"),
    ("processes", "process", "process record", "process records"),
    ("artisans", "artisan", "artisan", "artisans"),
)

BUCKET_KEYS: tuple[str, ...] = tuple(bucket for bucket, _, _, _ in BUCKETS)

_MAX_ROWS_PER_BUCKET = 40

_WRITE_CHUNK = 200


@dataclass(frozen=True)
class WorkshopWindow:
    id: str
    title: str
    start: datetime
    end: datetime

    def contains(self, moment: datetime) -> bool:
        return self.start <= moment < self.end


@dataclass
class RowPlan:
    id: str
    title: str
    workshopId: str | None = None
    rung: str | None = None
    reason: str | None = None
    candidates: list[str] = field(default_factory=list)


@dataclass
class BucketPlan:
    bucket: str
    unassigned: int = 0
    rows: list[RowPlan] = field(default_factory=list)

    @property
    def resolved(self) -> list[RowPlan]:
        return [row for row in self.rows if row.workshopId]

    @property
    def unresolved(self) -> list[RowPlan]:
        return [row for row in self.rows if not row.workshopId]

    @property
    def clear_cut(self) -> list[RowPlan]:
        return [row for row in self.rows if row.workshopId and row.rung in CLEAR_CUT_RUNGS]

    @property
    def suggested(self) -> list[RowPlan]:
        return [row for row in self.rows if row.workshopId and row.rung not in CLEAR_CUT_RUNGS]


@dataclass
class LadderRun:
    plans: dict[str, BucketPlan]
    workshopTitles: dict[str, str]
    windows: list[WorkshopWindow]



def _aware(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def workshop_title(workshop: Any) -> str:
    return str(getattr(workshop, "title", "") or "") or naming.session_title(
        {
            "entity": "Workshop",
            "date": getattr(workshop, "date", None),
            "place": getattr(workshop, "place", None),
        }
    )


def build_windows(workshops: Iterable[Any]) -> list[WorkshopWindow]:
    windows: list[WorkshopWindow] = []
    for workshop in workshops:
        start = _aware(getattr(workshop, "startDate", None)) or _aware(getattr(workshop, "date", None))
        if start is None:
            continue
        last_day = _aware(getattr(workshop, "endDate", None)) or start
        end = last_day.replace(hour=0, minute=0, second=0, microsecond=0) + _SINGLE_DAY
        if end <= start:
            end = start.replace(hour=0, minute=0, second=0, microsecond=0) + _SINGLE_DAY
        windows.append(
            WorkshopWindow(
                id=workshop.id,
                title=workshop_title(workshop),
                start=start,
                end=end,
            )
        )
    return windows


def distinct(values: Iterable[str | None]) -> list[str]:
    seen: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.append(value)
    return seen


def windows_containing(moment: datetime | None, windows: list[WorkshopWindow]) -> list[str]:
    if moment is None:
        return []
    return [window.id for window in windows if window.contains(moment)]


def stamp_of(row: Any, *columns: str) -> datetime | None:
    for column in columns:
        stamp = _aware(getattr(row, column, None))
        if stamp is not None:
            return stamp
    return None


def decide(row_id: str, title: str, rungs: list[tuple[str, list[str]]]) -> RowPlan:
    for rung, candidates in rungs:
        found = distinct(candidates)
        if not found:
            continue
        if len(found) == 1:
            return RowPlan(id=row_id, title=title, workshopId=found[0], rung=rung, candidates=found)
        return RowPlan(
            id=row_id,
            title=title,
            reason=REASON_AMBIGUOUS,
            candidates=found,
        )
    return RowPlan(id=row_id, title=title, reason=REASON_NO_EVIDENCE)


def title_of(row: Any, *columns: str) -> str:
    for column in columns:
        value = getattr(row, column, None)
        if value:
            return str(value)
    return "Untitled record"



async def _empty() -> list[Any]:
    return []


async def run_ladder() -> LadderRun:
    (
        workshops,
        interviews,
        media,
        products,
        tools,
        processes,
        loose_artisans,
    ) = await gather_reads(
        db.workshop.find_many(),
        db.questionnaireinterview.find_many(
            where={"workshopId": None}, include={"artisans": True}, order={"createdAt": "asc"}
        ),
        db.mediafile.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.productdocumentation.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.tooldocumentation.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.process.find_many(where={"workshopId": None}, order={"createdAt": "asc"}),
        db.artisan.find_many(
            where={"workshopId": None}, include={"workshops": True}, order={"createdAt": "asc"}
        ),
    )

    windows = build_windows(workshops)
    workshop_titles: dict[str, str] = {
        workshop.id: workshop_title(workshop)
        for workshop in workshops
    }

    artisan_ids: set[str] = {
        link.artisanId
        for interview in interviews
        for link in (interview.artisans or [])
        if link.artisanId
    }
    for row in (*products, *tools, *media):
        parent = getattr(row, "artisanId", None)
        if parent:
            artisan_ids.add(parent)

    media_parent_ids: dict[str, set[str]] = {
        "questionnaireInterviewId": set(),
        "productId": set(),
        "toolId": set(),
        "craftId": set(),
    }
    for row in media:
        for column in media_parent_ids:
            value = getattr(row, column, None)
            if value:
                media_parent_ids[column].add(value)
    process_product_ids = {row.productId for row in processes if getattr(row, "productId", None)}
    tagged_process_ids = {
        row.linkedRecordId
        for row in media
        if str(getattr(row, "linkedRecordType", "") or "").strip().lower() == "process"
        and getattr(row, "linkedRecordId", None)
    }

    (
        artisan_rows,
        roster_rows,
        parent_interviews,
        parent_products,
        parent_tools,
        parent_crafts,
        tagged_processes,
    ) = await gather_reads(
        db.artisan.find_many(where={"id": {"in": sorted(artisan_ids)}}) if artisan_ids else _empty(),
        db.workshopartisan.find_many(where={"artisanId": {"in": sorted(artisan_ids)}})
        if artisan_ids
        else _empty(),
        db.questionnaireinterview.find_many(
            where={"id": {"in": sorted(media_parent_ids["questionnaireInterviewId"])}}
        )
        if media_parent_ids["questionnaireInterviewId"]
        else _empty(),
        db.productdocumentation.find_many(
            where={"id": {"in": sorted(media_parent_ids["productId"] | process_product_ids)}}
        )
        if (media_parent_ids["productId"] or process_product_ids)
        else _empty(),
        db.tooldocumentation.find_many(where={"id": {"in": sorted(media_parent_ids["toolId"])}})
        if media_parent_ids["toolId"]
        else _empty(),
        db.craft.find_many(where={"id": {"in": sorted(media_parent_ids["craftId"])}})
        if media_parent_ids["craftId"]
        else _empty(),
        db.process.find_many(where={"id": {"in": sorted(tagged_process_ids)}})
        if tagged_process_ids
        else _empty(),
    )

    artisan_workshops: dict[str, list[str]] = {}
    for row in artisan_rows or []:
        if row.workshopId:
            artisan_workshops.setdefault(row.id, []).append(row.workshopId)
    for row in roster_rows or []:
        bucket = artisan_workshops.setdefault(row.artisanId, [])
        if row.workshopId not in bucket:
            bucket.append(row.workshopId)

    parent_workshop: dict[str, str] = {}
    for row in (
        *(parent_interviews or []),
        *(parent_products or []),
        *(parent_tools or []),
        *(parent_crafts or []),
        *(artisan_rows or []),
        *(tagged_processes or []),
    ):
        workshop_id = getattr(row, "workshopId", None)
        if workshop_id:
            parent_workshop[row.id] = workshop_id

    plans: dict[str, BucketPlan] = {bucket: BucketPlan(bucket=bucket) for bucket in BUCKET_KEYS}

    def rung_window(row: Any, *columns: str) -> tuple[str, list[str]]:
        return RUNG_WINDOW, windows_containing(stamp_of(row, *columns), windows)

    def first_parent_workshop(row: Any, *columns: str) -> str | None:
        for column in columns:
            value = getattr(row, column, None)
            if value and parent_workshop.get(value):
                return parent_workshop[value]
        return None

    def record(bucket: str, rows: list[Any], plan_row: Any) -> None:
        bucket_plan = plans[bucket]
        bucket_plan.unassigned = len(rows)
        for row in rows:
            result = plan_row(row)
            bucket_plan.rows.append(result)
            if result.workshopId:
                parent_workshop[row.id] = result.workshopId

    record(
        "artisans",
        list(loose_artisans),
        lambda row: decide(
            row.id,
            title_of(row, "name", "localName"),
            [
                (RUNG_PARENT, [link.workshopId for link in (getattr(row, "workshops", None) or [])]),
                rung_window(row, "recordedAt", "createdAt"),
            ],
        ),
    )
    for artisan_row in loose_artisans:
        mapped = parent_workshop.get(artisan_row.id)
        if mapped and mapped not in artisan_workshops.setdefault(artisan_row.id, []):
            artisan_workshops[artisan_row.id].append(mapped)

    record(
        "interviews",
        list(interviews),
        lambda row: decide(
            row.id,
            title_of(row, "title"),
            [
                (
                    RUNG_ARTISANS,
                    [
                        workshop
                        for link in (row.artisans or [])
                        if link.artisanId
                        for workshop in artisan_workshops.get(link.artisanId, [])
                    ],
                ),
                rung_window(row, "recordedAt", "interviewDate", "createdAt"),
            ],
        ),
    )

    for bucket, rows in (("products", products), ("tools", tools)):
        record(
            bucket,
            list(rows),
            lambda row: decide(
                row.id,
                title_of(row, "productName", "toolkitName", "englishName", "craftName"),
                [
                    (RUNG_PARENT, [first_parent_workshop(row, "artisanId")]),
                    rung_window(row, "recordedAt", "createdAt"),
                ],
            ),
        )

    record(
        "processes",
        list(processes),
        lambda row: decide(
            row.id,
            title_of(row, "name"),
            [
                (RUNG_PARENT, [first_parent_workshop(row, "productId")]),
                rung_window(row, "recordedAt", "createdAt"),
            ],
        ),
    )

    record(
        "media",
        list(media),
        lambda row: decide(
            row.id,
            title_of(row, "originalFilename", "caption"),
            [
                (
                    RUNG_PARENT,
                    [
                        first_parent_workshop(
                            row,
                            "questionnaireInterviewId",
                            "productId",
                            "toolId",
                            "artisanId",
                            "craftId",
                            "linkedRecordId",
                        )
                    ],
                ),
                rung_window(row, "recordedAt", "createdAt"),
            ],
        ),
    )

    return LadderRun(plans=plans, workshopTitles=workshop_titles, windows=windows)


def payload_for(run: LadderRun, applied: dict[str, int] | None = None) -> dict[str, Any]:
    titles = run.workshopTitles
    buckets: list[dict[str, Any]] = []
    total_unassigned = 0
    total_resolved = 0
    total_clear_cut = 0
    for bucket, _delegate, singular, plural in BUCKETS:
        plan = run.plans[bucket]
        resolved = plan.resolved
        unresolved = plan.unresolved
        clear_cut = plan.clear_cut
        suggested = plan.suggested
        total_unassigned += plan.unassigned
        total_resolved += len(resolved)
        total_clear_cut += len(clear_cut)

        by_rung: dict[str, int] = {}
        by_workshop: dict[str, int] = {}
        for row in resolved:
            if row.rung:
                by_rung[row.rung] = by_rung.get(row.rung, 0) + 1
            if row.workshopId:
                by_workshop[row.workshopId] = by_workshop.get(row.workshopId, 0) + 1
        by_reason: dict[str, int] = {}
        for row in unresolved:
            reason = row.reason or REASON_NO_EVIDENCE
            by_reason[reason] = by_reason.get(reason, 0) + 1

        buckets.append(
            {
                "bucket": bucket,
                "singular": singular,
                "plural": plural,
                "unassigned": plan.unassigned,
                "resolved": len(resolved),
                "unresolved": len(unresolved),
                "clearCut": len(clear_cut),
                "needsReview": plan.unassigned - len(clear_cut),
                "byRung": [
                    {"rung": rung, "copy": RUNG_COPY.get(rung, rung), "count": by_rung[rung]}
                    for rung in RUNGS
                    if by_rung.get(rung)
                ],
                "byReason": [
                    {"reason": reason, "copy": REASON_COPY.get(reason, reason), "count": count}
                    for reason, count in sorted(by_reason.items(), key=lambda item: -item[1])
                ],
                "byWorkshop": [
                    {
                        "workshopId": workshop_id,
                        "title": titles.get(workshop_id, "Unknown workshop"),
                        "count": count,
                    }
                    for workshop_id, count in sorted(by_workshop.items(), key=lambda item: -item[1])
                ],
                "rows": [
                    {
                        "id": row.id,
                        "title": row.title,
                        "workshopId": row.workshopId,
                        "workshopTitle": titles.get(row.workshopId, "Unknown workshop")
                        if row.workshopId
                        else None,
                        "rung": row.rung,
                        "rungCopy": RUNG_COPY.get(row.rung or ""),
                        "reason": row.reason,
                        "reasonCopy": REASON_COPY.get(row.reason or ""),
                        "candidateTitles": [
                            titles.get(candidate, "Unknown workshop") for candidate in row.candidates
                        ],
                        "clearCut": bool(row.workshopId) and row.rung in CLEAR_CUT_RUNGS,
                    }
                    for row in (*unresolved, *suggested, *clear_cut)[:_MAX_ROWS_PER_BUCKET]
                ],
                "rowsTruncated": len(plan.rows) > _MAX_ROWS_PER_BUCKET,
                "applied": (applied or {}).get(bucket) if applied is not None else None,
            }
        )

    return {
        "workshops": [
            {
                "id": window.id,
                "title": window.title,
                "start": window.start.isoformat(),
                "end": window.end.isoformat(),
            }
            for window in run.windows
        ],
        "buckets": buckets,
        "totals": {
            "unassigned": total_unassigned,
            "resolved": total_resolved,
            "unresolved": total_unassigned - total_resolved,
            "clearCut": total_clear_cut,
            "needsReview": total_unassigned - total_clear_cut,
            "applied": sum((applied or {}).values()) if applied is not None else None,
        },
    }


async def plan_workshop_mapping() -> dict[str, Any]:
    return payload_for(await run_ladder())


def _chunks(values: list[str], size: int) -> list[list[str]]:
    return [values[index : index + size] for index in range(0, len(values), size)]


async def apply_workshop_mapping(rungs: frozenset[str] | None = None) -> dict[str, Any]:
    run = await run_ladder()
    applied: dict[str, int] = {}
    for bucket, delegate_name, _singular, _plural in BUCKETS:
        delegate = getattr(db, delegate_name)
        grouped: dict[str, list[str]] = {}
        for row in run.plans[bucket].resolved:
            if rungs is not None and row.rung not in rungs:
                continue
            grouped.setdefault(str(row.workshopId), []).append(row.id)
        changed = 0
        for workshop_id, ids in grouped.items():
            for chunk in _chunks(ids, _WRITE_CHUNK):
                result = await delegate.update_many(
                    where={"id": {"in": chunk}, "workshopId": None},
                    data={"workshopId": workshop_id},
                )
                changed += int(result or 0)
        applied[bucket] = changed
    return payload_for(run, applied)


async def count_unassigned_interviews(artisan_ids: Iterable[str] | None = None) -> int:
    where: dict[str, Any] = {"workshopId": None}
    if artisan_ids is not None:
        ids = [value for value in artisan_ids if value]
        if not ids:
            return 0
        where["artisans"] = {"some": {"artisanId": {"in": ids}}}
    return await db.questionnaireinterview.count(where=where)
