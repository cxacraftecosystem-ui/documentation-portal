"""Bring stored values onto the shared standard lists (convergence point 10). Dry run by default.

Identical in the designer portal and the documentation portal; it finds out which one it is
running in from the database client (only the designer portal has ``DesignerProfile``).

WHAT IT LOOKS AT, AND WHAT --apply WRITES
-----------------------------------------
* ``Artisan.phone`` (and ``DesignerProfile.phone`` on the designer portal): the country code moves
  to ``phoneCountryCode`` (``+91`` when the number carries none) and the number becomes digits
  only. ``98765 43210``, ``+91 9876543210``, ``09876543210`` and ``919876543210`` all become
  ``+91`` / ``9876543210``.
* ``Artisan.gender``: any spelling of a list entry becomes its label (``female`` -> ``Female``).
* ``QuestionnaireInterview.language``: any spelling of a listed language becomes its code
  (``Bangla``, ``Bengali``, ``bengali`` -> ``bn``).
* ``ToolDocumentation.materialFamily``: set from the free ``material`` text only when that text
  names exactly one family (``Iron`` -> ``METAL``) and no family is set yet.
* ``ToolDocumentation.traditionType``: the codes retired on 2026-10-19 become the owner's wording
  (``MODERN`` -> ``CONTEMPORARY``, shown "Contemporary"; ``HYBRID`` -> ``TRANSITIONAL``, shown
  "Transitional (Hybrid)"). ``TRADITIONAL`` and the unanswered ``UNKNOWN`` stay as they are.
* ``Craft.craftCode``: NEVER written. Every craft with no shared code is listed with the existing
  shared codes whose spellings share words with its name, for the owner to decide. Nothing is
  invented: a craft with no candidate says so.

A value that matches nothing is never guessed at: it is left as it is and listed in the report.

Writes go through plain UPDATE statements that leave ``updatedAt`` alone, so a phone holding an
offline edit of the same record does not hit an edit conflict over a formatting change; the save
paths already treat the old and the new form of a value as the same value.

SAFE TO RE-RUN: a second ``--apply`` finds nothing left to change.

RUN IT AFTER THE RELEASE, on the box, against the live database, and only after the owner has
read the dry-run report. Never from a laptop and never as part of a test run.

    cd backend
    python scripts/standardise_values.py                 # dry run: counts and every unmappable value
    python scripts/standardise_values.py --report-dir .  # also write the report as JSON
    python scripts/standardise_values.py --apply         # write the changes

Phone numbers in the printed report show their last four digits only; pass --show-values to print
them in full.
"""

from __future__ import annotations

import argparse
import asyncio
import importlib
import json
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

sv = importlib.import_module("app.services.standard_values")

IST = timezone(timedelta(hours=5, minutes=30))
BATCH = 500
#: Columns that are a Postgres enum, with the cast their UPDATE needs.
ENUM_COLUMNS = {"traditionType": '::"TraditionType"'}
#: Columns whose report spells out each old value and what it becomes.
MAPPED_COLUMNS = ("gender", "language", "traditionType")


@dataclass
class FieldReport:
    table: str
    column: str
    checked: int = 0
    standard: int = 0
    blank: int = 0
    #: (id, old, new) for every row the run changes (or would change).
    changes: list[tuple[str, Any, Any]] = field(default_factory=list)
    #: value -> ids of the rows holding it that no list entry matches.
    unmappable: dict[str, list[str]] = field(default_factory=dict)
    values: Counter = field(default_factory=Counter)

    def cannot_map(self, value: Any, row_id: str) -> None:
        self.unmappable.setdefault(str(value), []).append(row_id)

    def mapped(self) -> list[dict[str, Any]]:
        """Each old value the run rewrites, what it becomes and on how many rows."""
        if self.column not in MAPPED_COLUMNS:
            return []
        pairs = Counter((str(old), str(new)) for _id, old, new in self.changes)
        return [
            {"from": old, "to": new, "rows": count} for (old, new), count in sorted(pairs.items())
        ]

    def as_dict(self) -> dict[str, Any]:
        return {
            "table": self.table,
            "column": self.column,
            "checked": self.checked,
            "alreadyStandard": self.standard,
            "blank": self.blank,
            "toChange": len(self.changes),
            "mapped": self.mapped(),
            "unmappableRows": sum(len(ids) for ids in self.unmappable.values()),
            "unmappable": [
                {"value": value, "rows": ids} for value, ids in sorted(self.unmappable.items())
            ],
            "values": dict(self.values.most_common()) if self.values else None,
        }


@dataclass
class Report:
    portal: str
    applied: bool
    fields: list[FieldReport] = field(default_factory=list)
    crafts: list[dict[str, Any]] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            "portal": self.portal,
            "applied": self.applied,
            "generatedAt": datetime.now(IST).isoformat(timespec="minutes"),
            "fields": [item.as_dict() for item in self.fields],
            "craftsWithoutSharedCode": self.crafts,
        }


def _get(row: Any, key: str) -> Any:
    return row.get(key) if isinstance(row, dict) else getattr(row, key, None)


def _mask(value: str, show: bool) -> str:
    if show:
        return value
    digits = [index for index, ch in enumerate(value) if ch.isdigit()]
    keep = set(digits[-4:])
    return "".join(
        "•" if index in digits and index not in keep else ch for index, ch in enumerate(value)
    )


async def _rows(delegate: Any, where: dict[str, Any] | None = None):
    cursor: str | None = None
    while True:
        query: dict[str, Any] = dict(where or {})
        if cursor:
            query["id"] = {"gt": cursor}
        batch = await delegate.find_many(where=query, order={"id": "asc"}, take=BATCH)
        if not batch:
            return
        for row in batch:
            yield row
        cursor = str(_get(batch[-1], "id"))


async def check_phone(client: Any, model: str, table: str) -> FieldReport:
    report = FieldReport(table, "phone")
    async for row in _rows(getattr(client, model)):
        report.checked += 1
        phone, code = _get(row, "phone"), _get(row, "phoneCountryCode")
        if phone is None or not str(phone).strip():
            report.blank += 1
            continue
        try:
            new_code, digits = sv.normalize_phone(code, phone)
        except sv.StandardValueError:
            report.cannot_map(phone, str(_get(row, "id")))
            continue
        if (new_code, digits) == (code, phone):
            report.standard += 1
        else:
            report.changes.append((str(_get(row, "id")), (code, phone), (new_code, digits)))
    return report


async def check_list(
    client: Any, model: str, table: str, column: str, normaliser: Any
) -> FieldReport:
    report = FieldReport(table, column)
    async for row in _rows(getattr(client, model)):
        report.checked += 1
        value = _get(row, column)
        if value is None or not str(value).strip():
            report.blank += 1
            continue
        try:
            new = normaliser(value)
        except sv.StandardValueError:
            report.cannot_map(value, str(_get(row, "id")))
            continue
        report.values[new] += 1
        if new == value:
            report.standard += 1
        else:
            report.changes.append((str(_get(row, "id")), value, new))
    return report


async def check_material_family(client: Any) -> FieldReport:
    report = FieldReport("ToolDocumentation", "materialFamily")
    async for row in _rows(client.tooldocumentation):
        report.checked += 1
        family, material = _get(row, "materialFamily"), _get(row, "material")
        if family:
            try:
                report.values[sv.normalize_material_family(family)] += 1
                report.standard += 1
            except sv.StandardValueError:
                report.cannot_map(family, str(_get(row, "id")))
            continue
        if material is None or not str(material).strip():
            report.blank += 1
            continue
        proposed = sv.material_family_from_text(material)
        if proposed is None:
            report.cannot_map(material, str(_get(row, "id")))
        else:
            report.values[proposed] += 1
            report.changes.append((str(_get(row, "id")), None, proposed))
    return report


async def craft_proposals(client: Any) -> list[dict[str, Any]]:
    """Crafts with no shared code, each with the shared codes whose spellings share its words."""
    from app.services import cross_portal_codes as cpc

    entries = cpc.shared().crafts
    out: list[dict[str, Any]] = []
    async for row in _rows(client.craft, {"craftCode": None}):
        name = str(_get(row, "name") or "")
        words = set(cpc.normalise(name).split()) - {"and", "of", "the", "gi", "craft", "work"}
        scored: list[tuple[float, str, str]] = []
        for entry in entries:
            best = 0.0
            for spelling in (entry.name, *entry.spellings):
                theirs = set(cpc.normalise(spelling).split()) - {
                    "and",
                    "of",
                    "the",
                    "gi",
                    "craft",
                    "work",
                }
                if words and theirs:
                    best = max(best, len(words & theirs) / len(words | theirs))
            if best > 0:
                scored.append((best, entry.code, entry.name))
        scored.sort(key=lambda item: (-item[0], item[1]))
        out.append(
            {
                "craftId": str(_get(row, "id")),
                "name": name,
                "candidates": [
                    {"code": code, "name": label, "wordOverlap": round(score, 2)}
                    for score, code, label in scored[:3]
                ],
                "proposal": (
                    f"Map to an existing code if one of the candidates is the same tradition: {scored[0][1]}?"
                    if scored
                    else "No existing shared code shares a word with this name. The owner decides whether it "
                    "needs a new code in app/data/cross_portal_codes.json (both portals)."
                ),
            }
        )
    return out


async def apply_changes(client: Any, report: FieldReport) -> None:
    table = report.table
    for start in range(0, len(report.changes), BATCH):
        chunk = report.changes[start : start + BATCH]
        async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=120)) as tx:
            for row_id, _old, new in chunk:
                if report.column == "phone":
                    code, digits = new
                    await tx.execute_raw(
                        f'UPDATE "{table}" SET "phone" = $1, "phoneCountryCode" = $2 WHERE "id" = $3',
                        digits,
                        code,
                        row_id,
                    )
                else:
                    # An enum column takes its value through a cast; a text column as it is.
                    cast = ENUM_COLUMNS.get(report.column, "")
                    await tx.execute_raw(
                        f'UPDATE "{table}" SET "{report.column}" = $1{cast} WHERE "id" = $2',
                        new,
                        row_id,
                    )


async def run(client: Any, *, apply: bool) -> Report:
    designer_portal = hasattr(client, "designerprofile")
    report = Report("DesignWorkshop" if designer_portal else "FieldRepository", apply)
    report.fields.append(await check_phone(client, "artisan", "Artisan"))
    if designer_portal:
        report.fields.append(await check_phone(client, "designerprofile", "DesignerProfile"))
    report.fields.append(
        await check_list(client, "artisan", "Artisan", "gender", sv.normalize_gender)
    )
    report.fields.append(
        await check_list(
            client,
            "questionnaireinterview",
            "QuestionnaireInterview",
            "language",
            sv.normalize_language,
        )
    )
    report.fields.append(await check_material_family(client))
    report.fields.append(
        await check_list(
            client,
            "tooldocumentation",
            "ToolDocumentation",
            "traditionType",
            sv.normalize_tradition,
        )
    )
    report.crafts = await craft_proposals(client)
    if apply:
        for item in report.fields:
            if item.changes:
                await apply_changes(client, item)
    return report


def lines(report: Report, *, show_values: bool) -> list[str]:
    verb = "Changed" if report.applied else "Will change"
    out = [f"{report.portal}: standard values ({'applied' if report.applied else 'dry run'})", ""]
    out.append(
        f"{'table.column':<40}{'checked':>9}{'standard':>10}{'blank':>7}{'change':>8}{'no match':>10}"
    )
    for item in report.fields:
        no_match = sum(len(ids) for ids in item.unmappable.values())
        out.append(
            f"{item.table + '.' + item.column:<40}{item.checked:>9}{item.standard:>10}{item.blank:>7}"
            f"{len(item.changes):>8}{no_match:>10}"
        )
    for item in report.fields:
        if item.values and item.column != "phone":
            counts = ", ".join(f"{value} {count}" for value, count in item.values.most_common())
            out += ["", f"{item.table}.{item.column} after the change: {counts}"]
        if item.changes and item.column in MAPPED_COLUMNS:
            mapped = ", ".join(
                f"{row['from']!r} -> {row['to']} x{row['rows']}" for row in item.mapped()
            )
            out.append(f"{item.table}.{item.column} mapped: {mapped}")
        if item.unmappable:
            out += [
                "",
                f"{item.table}.{item.column}: values no list entry matches (left as they are)",
            ]
            for value, ids in sorted(item.unmappable.items()):
                shown = _mask(value, show_values) if item.column == "phone" else value
                out.append(f"  {shown!r} x{len(ids)}: {', '.join(ids)}")
    out += ["", f"Crafts with no shared code: {len(report.crafts)} (proposal only; never written)"]
    for craft in report.crafts:
        out.append(f"  {craft['name']!r} ({craft['craftId']})")
        for candidate in craft["candidates"]:
            out.append(
                f"      candidate {candidate['code']}: {candidate['name']} (word overlap {candidate['wordOverlap']})"
            )
        out.append(f"      {craft['proposal']}")
    total = sum(len(item.changes) for item in report.fields)
    out += ["", f"{verb} {total} value{'' if total == 1 else 's'} in total."]
    if not report.applied:
        out.append("Dry run: nothing was written. Pass --apply to write.")
    return out


def report_filename(report: Report) -> str:
    """The naming convention (docs/conventions/NAMING_2026-10-18.md): Portal_Entity_Date_Time_Subject_What."""
    now = datetime.now(IST)
    return f"{report.portal}_Database_{now:%Y-%m-%d}_{now:%H%M}_Standard-Values_Report.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apply", action="store_true", help="write the changes (default: dry run)")
    parser.add_argument("--report-dir", help="also write the full report as JSON into this folder")
    parser.add_argument("--show-values", action="store_true", help="print phone numbers in full")
    args = parser.parse_args()

    async def go() -> None:
        from app.core.db import connect_db, db, disconnect_db

        await connect_db()
        try:
            report = await run(db, apply=args.apply)
        finally:
            await disconnect_db()
        print("\n".join(lines(report, show_values=args.show_values)))
        if args.report_dir:
            path = Path(args.report_dir) / report_filename(report)
            path.write_text(
                json.dumps(report.as_dict(), ensure_ascii=False, indent=1, default=str),
                encoding="utf-8",
            )
            print(f"Report written to {path}")

    asyncio.run(go())


if __name__ == "__main__":
    main()
