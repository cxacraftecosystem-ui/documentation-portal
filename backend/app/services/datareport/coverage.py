from __future__ import annotations

from dataclasses import dataclass, field

from app.services.datareport import exclusions, schema, tables
from app.services.datareport.contract import Column, catalog_of, plugins

PORTAL_TITLES = {"designer": "Design Prototype Workshop", "fieldRepository": "Field Repository"}


@dataclass
class Coverage:
    portal: str
    exported: dict[str, list[tuple[str, str]]] = field(default_factory=dict)
    derived: list[tuple[str, str, str]] = field(default_factory=list)
    sheets: dict[str, str] = field(default_factory=dict)

    def where(self, source: str) -> list[tuple[str, str]]:
        return self.exported.get(source, [])


def sheet_columns(portal: str) -> list[tuple[str, str, list[Column]]]:
    from app.services.datareport.sheets import core_media

    out: list[tuple[str, str, list[Column]]] = []
    for table in tables.tables_for(portal):
        out.append((table.key, table.title, tables.plan(table, portal).columns))
    out.append(("media", "Media", core_media.columns(portal)))
    seen = {key for key, _, _ in out}
    for plugin in plugins(portal):
        if isinstance(plugin, tables.TablePlugin):
            continue
        listed = catalog_of(plugin) or []
        for key, name, columns in listed:
            if key in seen:
                continue
            seen.add(key)
            out.append((key, name, columns))
    return out


def collect(portal: str) -> Coverage:
    found = Coverage(portal)
    for key, name, columns in sheet_columns(portal):
        found.sheets[key] = name
        for column in columns:
            if not column.sources:
                found.derived.append((name, column.label, column.description))
            for source in column.sources:
                found.exported.setdefault(source, []).append((name, column.label))
    return found


def problems(portal: str) -> list[str]:
    models = schema.load(portal)
    found = collect(portal)
    out: list[str] = []
    for source in found.exported:
        model, _, name = source.partition(".")
        if model not in models or models[model].field(name) is None:
            out.append(f"{source} is exported but is not in the {portal} schema")
    for model in models.values():
        model_reason = exclusions.excluded(model.name)
        for f in model.fields:
            exported = bool(found.where(f.source))
            reason = model_reason or exclusions.excluded(model.name, f.name)
            if exported and reason:
                out.append(f"{f.source} is exported but also excluded ({reason})")
            elif not exported and not reason:
                out.append(f"{f.source} is neither exported nor on the exclusion list")
            if exported and exclusions.looks_sensitive(model.name, f.name):
                out.append(f"{f.source} looks like secret material; exclude it or add it to SENSITIVE_ALLOWED after review")
    return out


def _cell(text: str) -> str:
    return " ".join(str(text).replace("|", "\\|").split())


def render(portal: str) -> str:
    from app.services.datareport.sheets import core_bank

    models = schema.load(portal)
    found = collect(portal)
    lines = [
        f"## {PORTAL_TITLES.get(portal, portal)}",
        "",
        f"{len(models)} models, {sum(len(m.fields) for m in models.values())} fields. Generated from `backend/prisma/schema.prisma` by `python scripts/sync_data_report.py --audit`; `backend/tests/test_report_coverage.py` fails when this file or the mapping falls behind.",
        "",
        "### Models and fields",
        "",
    ]
    for model in sorted(models.values(), key=lambda m: m.name):
        model_reason = exclusions.excluded(model.name)
        if model_reason:
            lines.append(f"- **{model.name}**: excluded. {_cell(model_reason)}")
            continue
        lines.append(f"- **{model.name}**")
        for f in model.fields:
            where = found.where(f.source)
            if where:
                places = "; ".join(sorted({f"{sheet} › {column}" for sheet, column in where}))
                lines.append(f"  - `{f.name}`: {_cell(places)}")
            else:
                reason = exclusions.excluded(model.name, f.name) or "missing"
                lines.append(f"  - `{f.name}`: excluded. {_cell(reason)}")
    lines += ["", "### Questionnaire definitions (Question bank sheet)", ""]
    counts: dict[str, dict[str, int]] = {}
    for label, code, _title, _n, _prompt, name in core_bank.snapshot(portal):
        counts.setdefault(f"{label} (`{name}`)", {}).setdefault(code, 0)
        counts[f"{label} (`{name}`)"][code] += 1
    for label, sections in counts.items():
        summary = ", ".join(f"{code} ({n})" for code, n in sections.items())
        lines.append(f"- {label}: {sum(sections.values())} questions in {len(sections)} sections: {summary}")
    lines.append("- Questionnaires built in the portal (every version, including the field repository's v2 forms): the Questionnaires, Questionnaire sections and Questions sheets, one row per section and per question version.")
    lines += ["", "### Derived values", ""]
    for sheet, label, description in sorted(set(found.derived)):
        lines.append(f"- {_cell(sheet)} › {_cell(label)}: {_cell(description)}")
    return "\n".join(lines) + "\n"


HEADER = """# XLSX report audit

Every field of each portal's database, every questionnaire definition and every derived value, with the sheet and column of the data report (.xlsx) where it appears, or the reviewed reason it is left out. Secrets, password and two-factor material, raw Aadhaar data, tokens and storage keys are excluded on purpose.

The report is built by `backend/app/services/datareport/` (byte-identical in both portals). Each sheet is a plugin; the generic record sheets are listed in `tables.py`, the reviewed exclusions in `exclusions.py`.

"""


def document() -> str:
    return HEADER + "\n".join(render(portal) for portal in ("designer", "fieldRepository") if schema.load(portal))
