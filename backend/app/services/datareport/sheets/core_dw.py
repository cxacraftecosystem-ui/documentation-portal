from __future__ import annotations

import re
from typing import Any

from app.services.datareport import tables
from app.services.datareport.contract import Column, ReportContext, Sheet, register

GROUP = "designWorkshop"
_KEY = re.compile(r"[^A-Za-z0-9]+")


def _key(name: str) -> str:
    words = [w for w in _KEY.split(name) if w]
    if not words:
        return "dwSheet"
    return "dw" + "".join(w[:1].upper() + w[1:] for w in words)


def _column_key(label: str, used: set[str]) -> str:
    words = [w for w in _KEY.split(label) if w]
    base = (words[0].lower() + "".join(w[:1].upper() + w[1:] for w in words[1:])) if words else "column"
    key, n = base, 2
    while key in used:
        key = f"{base}{n}"
        n += 1
    used.add(key)
    return key


def adapt(sheet: dict[str, Any]) -> Sheet:
    labels = [str(c) for c in sheet.get("columns") or []]
    used: set[str] = set()
    keys = [_column_key(label, used) for label in labels]
    prose = set(sheet.get("prose") or [])
    columns = [
        Column(key, label, "longtext" if index in prose else "text", f"{label} (design workshop stage data).", wrap=index in prose or None, source="DwStageEntry.data,DwStageEntry.fieldProvenance")
        for index, (key, label) in enumerate(zip(keys, labels, strict=True))
    ]
    data = list(sheet.get("rows") or [])
    if sheet.get("noteRow") and data:
        data = data[:-1]
    rows = [{key: (row[i] if i < len(row) else None) for i, key in enumerate(keys)} for row in data]
    name = str(sheet.get("name") or "Design workshop")
    return Sheet(
        key=_key(name),
        name=name,
        columns=columns,
        rows=rows,
        color=str(sheet.get("color") or tables.DW).lstrip("#"),
        note=sheet.get("truncatedNote") or (f"Capped at {len(rows):,} rows — the full data set has more." if sheet.get("truncated") else None),
        group=GROUP,
        count=len(rows),
    )


class DesignWorkshopStagesPlugin:
    key = "designWorkshopStages"
    title = "Design workshop stages"
    order = 199
    option = None
    portals = ("designer",)

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        return []

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        legacy = [s for s in ctx.material.get("sheets") or [] if s.get("group") == GROUP]
        if not legacy:
            return []
        if not bool(getattr(ctx.scope, "design_workshop_downloads", False)):
            note = ctx.t(
                "banner.withheld",
                "Design & prototype workshop tables are readable on screen at your access level and are not included in downloads. An admin or the master admin can export them.",
            )
            columns = [
                Column("sheet", "Sheet not included", "text", "A design workshop table left out of this download."),
                Column("rows", "Rows it holds", "int", "How many rows it holds on screen."),
            ]
            rows = [{"sheet": s.get("name"), "rows": max(len(s.get("rows") or []) - (1 if s.get("noteRow") else 0), 0)} for s in legacy]
            return [Sheet(key="designWorkshopsWithheld", name="Design workshops (withheld)", columns=columns, rows=rows, color=tables.DW, note=note, count=len(rows))]
        return [adapt(s) for s in legacy]


register(DesignWorkshopStagesPlugin())
