"""One registry describing how every record type is presented, everywhere.

Before this module the same field list was written out four separate times per record
type — the browser's info panel, the browser's report sheet, export.py's ``details.txt``
and csv_export.py's CSV columns — so adding a column meant editing four places and
silently under-reporting in the three you forgot.

ALL FOUR now derive from the ``RecordSpec`` entries below: the data browser's ``/data/tree``
info panels and generated ``details.txt``, every sheet in the ``/data/report`` workbook, the
``details.txt`` files inside the ``/export/dataset`` zip, and the ``/export/products.csv`` /
``/export/tools.csv`` downloads. A column added here reaches every one of them, and — just as
importantly — the masking applied here (an artisan's Aadhaar number) can no longer be missed
by a surface that built its own field list.

Three presentations are built from one spec:

- :func:`info_panel` — ``{title, fields:[{label,value}]}`` with empty values dropped.
  Used for the data browser's record card and for generated ``details.txt`` bodies.
- :func:`record_table` — a rectangular ``{columns, rows}`` grid that keeps every column
  even when a cell is blank, so a folder can render its records as a real table.
- :func:`sheet_columns` / :func:`sheet_row` — the same grid shaped for an .xlsx sheet,
  with provenance (status / created by / created on) and the media columns appended.

A spec's ``fields`` are the human-meaningful record fields. Provenance and media are
added by the table/sheet builders rather than the spec, because the info panel
deliberately omits them.

Value coercion lives here too (:func:`num`, :func:`money`, :func:`dims`, :func:`date_str`,
:func:`enum_label`). Prisma ``Decimal`` columns arrive as objects that stringify with
trailing zeros, and Prisma enums stringify as ``MediaType.AUDIO``; both are normalised
before they ever reach a cell.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Iterable

from app.services.artisan_identity import mask_aadhaar

# ---------------------------------------------------------------------------
# Value coercion
# ---------------------------------------------------------------------------


def ev(value: Any) -> Any:
    """Prisma enum -> its plain string value (``MediaType.AUDIO`` -> ``AUDIO``)."""
    return getattr(value, "value", value)


def num(value: Any) -> str | None:
    """Decimal/number -> compact string without trailing zeros ("1500.00" -> "1500")."""
    if value is None:
        return None
    text = str(value)
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or None


def money(value: Any) -> str | None:
    parsed = num(value)
    return f"₹{parsed}" if parsed is not None else None


def dims(*parts: Any) -> str | None:
    values = [num(p) for p in parts if p is not None]
    values = [v for v in values if v]
    return " x ".join(values) if values else None


def date_str(value: Any) -> str | None:
    if value is None:
        return None
    try:
        return value.strftime("%d %b %Y")
    except (AttributeError, ValueError):
        return str(value)


def enum_label(value: Any) -> str | None:
    """Enum -> "Local Blacksmith". The UNKNOWN/OTHER defaults carry no information, so
    they are dropped rather than rendered as a meaningless cell."""
    raw = str(ev(value) or "").strip()
    if not raw or raw.upper() in ("UNKNOWN", "OTHER"):
        return None
    return raw.replace("_", " ").title()


def human_size(value: Any) -> str:
    if value is None:
        return ""
    size = float(int(value))
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return ""  # pragma: no cover - the TB branch always returns


def cell(value: Any) -> str:
    """Any value -> a trimmed string safe to drop straight into a table cell."""
    return "" if value is None else str(value).strip()


def meta_of(record: Any) -> dict[str, Any]:
    meta = getattr(record, "extraMetadata", None)
    return meta if isinstance(meta, dict) else {}


def meta_val(meta: dict[str, Any], *keys: str) -> Any:
    """First populated scalar stored under any of the given extraMetadata keys."""
    for key in keys:
        value = meta.get(key)
        if isinstance(value, (str, int, float)) and str(value).strip():
            return value
    return None


def _rel(record: Any, relation: str, attr: str) -> Any:
    """``record.<relation>.<attr>`` when the relation was included, else None."""
    return getattr(getattr(record, relation, None), attr, None)


def _workshop_titles(record: Any, relation: str = "workshops") -> str | None:
    """Join the titles behind a many-to-many workshop link table."""
    titles = [
        getattr(getattr(link, "workshop", None), "title", None)
        for link in getattr(record, relation, None) or []
    ]
    joined = ", ".join(t for t in titles if t)
    return joined or None


def artisan_names(interview: Any) -> list[str]:
    names: list[str] = []
    for link in getattr(interview, "artisans", None) or []:
        name = (getattr(getattr(link, "artisan", None), "name", None) or "").strip()
        if name:
            names.append(name)
    return names


def interview_label(interview: Any) -> str:
    """An interview is identified by the artisans it covers, not its internal title."""
    names = artisan_names(interview)
    if names:
        return ", ".join(names)
    return (getattr(interview, "title", None) or "").strip() or "Interview"


# ---------------------------------------------------------------------------
# Spec model
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class FieldSpec:
    label: str
    get: Callable[[Any], Any]


@dataclass(frozen=True)
class RecordSpec:
    """How one record type is titled, coloured and described."""

    kind: str
    """Stable slug: workshop, craft, artisan, product, process, tool, interview."""
    label: str
    """Singular human noun, used as the first table column ("Artisan")."""
    plural: str
    """Sheet name / folder heading ("Artisans")."""
    color: str
    """Brand colour for the sheet tab and the UI pill."""
    title: Callable[[Any], str]
    """The record's display name."""
    fields: tuple[FieldSpec, ...]

    def field_pairs(self, record: Any) -> list[tuple[str, Any]]:
        return [(f.label, f.get(record)) for f in self.fields]


def _f(label: str, get: Callable[[Any], Any]) -> FieldSpec:
    return FieldSpec(label=label, get=get)


# ---------------------------------------------------------------------------
# The registry
# ---------------------------------------------------------------------------

WORKSHOP = RecordSpec(
    kind="workshop",
    label="Workshop",
    plural="Workshops",
    color="#5B21B6",
    title=lambda w: (getattr(w, "title", None) or "").strip() or "Workshop",
    fields=(
        _f("Place", lambda w: w.place),
        _f("Start date", lambda w: date_str(w.startDate or w.date)),
        _f("End date", lambda w: date_str(w.endDate)),
        _f("Description", lambda w: w.description),
        _f("Notes", lambda w: w.notes),
    ),
)

CRAFT = RecordSpec(
    kind="craft",
    label="Craft",
    plural="Crafts",
    color="#7C3AED",
    title=lambda c: (getattr(c, "name", None) or "").strip() or "Craft",
    fields=(
        _f("Local name", lambda c: getattr(c, "localName", None)),
        _f("Category", lambda c: c.category),
        _f("Place", lambda c: c.place),
        _f("Description", lambda c: c.description),
        _f("Workshops", lambda c: _workshop_titles(c)),
    ),
)

ARTISAN = RecordSpec(
    kind="artisan",
    label="Artisan",
    plural="Artisans",
    color="#0E7490",
    title=lambda a: (getattr(a, "name", None) or "").strip() or "Artisan",
    fields=(
        _f("Local name", lambda a: a.localName),
        _f("Workshop", lambda a: _workshop_titles(a)),
        _f("Craft", lambda a: _rel(a, "craft", "name")),
        _f("Village/Place", lambda a: a.place),
        _f("District", lambda a: meta_val(meta_of(a), "district")),
        # State and pincode became real columns on the shared Location model, but the artisans
        # recorded before that kept their state as free text in extraMetadata. Reading the column
        # first and falling back to the metadata is what lets both generations print in one sheet;
        # dropping the fallback would blank the historical rows, and keeping only the fallback would
        # blank every row entered from the new dropdown. ``_rel`` answers None when the location
        # relation was not included, so a caller that loads artisans without it degrades to exactly
        # the old behaviour rather than erroring.
        _f("State", lambda a: _rel(a, "location", "state") or meta_val(meta_of(a), "state")),
        _f("Address", lambda a: a.address),
        _f(
            "Pincode",
            lambda a: _rel(a, "location", "pincode")
            or meta_val(meta_of(a), "pincode", "pinCode", "postalCode"),
        ),
        _f("Phone", lambda a: a.phone),
        _f("Email", lambda a: a.email),
        # Aadhaar is regulated personal data and this spec feeds every SHARED surface — the data
        # browser, the .xlsx workbook, generated details.txt. Only the last four digits go out, which
        # is enough to confirm the right person and useless as an identifier. The full number stays
        # readable through the artisan's own record for the people entitled to it.
        _f("Aadhaar number", lambda a: mask_aadhaar(a.aadhaarNumber)),
        _f(
            "Artisan Pehchan Card",
            lambda a: "Yes" if a.pehchanCardAvailable else "No",
        ),
        _f("Pehchan card number", lambda a: a.pehchanCardNumber),
        _f("Age", lambda a: meta_val(meta_of(a), "age")),
        _f("Gender", lambda a: a.gender),
        _f(
            "Experience (years)",
            lambda a: meta_val(meta_of(a), "experienceYears", "experience", "yearsOfExperience"),
        ),
        _f("Do's", lambda a: a.dos),
        _f("Don'ts", lambda a: a.donts),
        _f("Notes", lambda a: a.notes),
    ),
)

PRODUCT = RecordSpec(
    kind="product",
    label="Product",
    plural="Products",
    color="#B45309",
    title=lambda p: (getattr(p, "productName", None) or "").strip() or "Product",
    fields=(
        _f("Local name", lambda p: p.localName),
        _f("Workshop", lambda p: _rel(p, "workshop", "title")),
        _f("Craft", lambda p: p.craftName),
        _f("Artisan", lambda p: p.artisanName),
        _f("Place", lambda p: p.place),
        _f("Type", lambda p: enum_label(p.productType)),
        _f(
            "Dimensions (LxBxH in)",
            lambda p: dims(p.lengthInches, p.breadthInches, p.heightInches),
        ),
        _f("Size", lambda p: p.size),
        _f("Time to complete", lambda p: p.timeTakenToCompleteProduct),
        _f("Cost of making", lambda p: money(p.costOfMaking)),
        _f("Selling price", lambda p: money(p.sellingPrice)),
        _f("Market demand", lambda p: enum_label(p.marketDemand)),
        _f("Raw materials", lambda p: p.rawMaterialsUsed),
        _f("Main tools", lambda p: p.mainToolsUsed),
        _f("Function/use", lambda p: p.productFunctionUse),
        _f("Remarks", lambda p: p.remarks),
    ),
)

TOOL = RecordSpec(
    kind="tool",
    label="Tool",
    plural="Tools",
    color="#15803D",
    title=lambda t: (getattr(t, "toolkitName", None) or "").strip() or "Tool",
    fields=(
        _f("Local name", lambda t: t.localName),
        _f("English name", lambda t: t.englishName),
        _f("Workshop", lambda t: _rel(t, "workshop", "title")),
        _f("Craft", lambda t: t.craftName),
        _f("Artisan", lambda t: t.artisanName),
        _f("Place", lambda t: t.place),
        _f("Usage", lambda t: t.processUsedIn),
        _f("Material", lambda t: t.material),
        _f("Years in use", lambda t: t.yearsInUse),
        _f("Dimensions (LxB in)", lambda t: dims(t.lengthInches, t.breadthInches)),
        _f("Height", lambda t: num(t.height)),
        _f("Width", lambda t: num(t.width)),
        _f("Thickness", lambda t: num(t.thickness)),
        _f("Weight", lambda t: num(t.weight)),
        _f("Radius", lambda t: num(t.radius)),
        _f("Cost", lambda t: money(t.replacementCost)),
        _f("Maker", lambda t: enum_label(t.maker)),
        _f("Tradition", lambda t: enum_label(t.traditionType)),
        _f("Improvement suggestions", lambda t: t.suggestionsForToolImprovement),
        _f("Remarks", lambda t: t.remarks),
    ),
)

PROCESS = RecordSpec(
    kind="process",
    label="Process",
    plural="Processes",
    color="#4338CA",
    title=lambda pr: (getattr(pr, "name", None) or "").strip() or "Process",
    fields=(
        _f("Product", lambda pr: _rel(pr, "product", "productName")),
        _f("Artisan", lambda pr: _rel(pr, "product", "artisanName")),
        _f("Craft", lambda pr: _rel(pr, "product", "craftName")),
        _f(
            "Pre-process available",
            lambda pr: "Yes" if getattr(pr, "preProcessAvailable", False) else None,
        ),
        _f("Steps", lambda pr: len(getattr(pr, "steps", None) or []) or None),
        _f("Notes", lambda pr: pr.notes),
    ),
)

INTERVIEW = RecordSpec(
    kind="interview",
    label="Interview",
    plural="Questionnaires",
    color="#BE185D",
    title=interview_label,
    fields=(
        _f("Artisans", lambda i: ", ".join(artisan_names(i)) or None),
        _f("Location", lambda i: i.place),
        _f("Language", lambda i: i.language),
        _f("Recorded at", lambda i: date_str(i.interviewDate or i.recordedAt)),
        _f("Notes", lambda i: i.notes),
    ),
)

SPECS: dict[str, RecordSpec] = {
    spec.kind: spec for spec in (WORKSHOP, CRAFT, ARTISAN, PRODUCT, TOOL, PROCESS, INTERVIEW)
}

# Colours for the sheets that are not a single record type.
MEDIA_COLOR = "#334155"
TRANSCRIPT_COLOR = "#334155"
OVERVIEW_COLOR = "#5B21B6"


def spec_for(kind: str) -> RecordSpec | None:
    return SPECS.get(kind)


# ---------------------------------------------------------------------------
# Presentations
# ---------------------------------------------------------------------------

PROVENANCE_COLUMNS = ("Status", "Created by", "Created on")

# Every sheet/table carries its record's media inline so a reader never has to cross-
# reference the Media sheet to find out what was captured for a row.
MEDIA_COLUMNS = ("Media count", "Media files", "Media URLs")


def created_by(record: Any) -> str:
    return getattr(getattr(record, "createdBy", None), "name", None) or ""


def provenance_row(record: Any) -> list[str]:
    return [
        cell(enum_label(getattr(record, "status", None))),
        created_by(record),
        cell(date_str(getattr(record, "createdAt", None))),
    ]


def media_row(media: Iterable[Any]) -> list[str]:
    """The three media cells for one record: how many, their names, their URLs."""
    items = list(media or [])
    if not items:
        return ["0", "", ""]
    names = [cell(getattr(m, "originalFilename", None)) or cell(m.id) for m in items]
    urls = [cell(getattr(m, "url", None)) for m in items]
    return [str(len(items)), " | ".join(n for n in names if n), " | ".join(u for u in urls if u)]


def info_panel(kind: str, record: Any) -> dict[str, Any] | None:
    """``{title, fields}`` with blank values dropped — the browser's record card."""
    spec = SPECS.get(kind)
    if spec is None:
        return None
    fields: list[dict[str, str]] = []
    for label, value in spec.field_pairs(record):
        text = cell(value)
        if text:
            fields.append({"label": label, "value": text})
    return {"title": spec.title(record), "fields": fields}


def info_text(info: dict[str, Any] | None) -> str:
    """Render an info panel as ``details.txt`` content (title line + "Label: value")."""
    if not info:
        return ""
    lines = [str(info.get("title") or "").strip()]
    lines.extend(f"{f['label']}: {f['value']}" for f in info.get("fields") or [])
    return "\n".join(line for line in lines if line)


def sheet_columns(kind: str) -> list[str]:
    """Full column list for a record type's sheet or in-folder table."""
    spec = SPECS[kind]
    return [
        spec.label,
        *(f.label for f in spec.fields),
        *PROVENANCE_COLUMNS,
        *MEDIA_COLUMNS,
    ]


def sheet_row(kind: str, record: Any, media: Iterable[Any] = ()) -> list[str]:
    """One rectangular row matching :func:`sheet_columns` exactly."""
    spec = SPECS[kind]
    return [
        cell(spec.title(record)),
        *(cell(value) for _, value in spec.field_pairs(record)),
        *provenance_row(record),
        *media_row(media),
    ]


def record_table(
    kind: str,
    records: Iterable[Any],
    media_by_record: dict[str, list[Any]] | None = None,
) -> dict[str, Any]:
    """A rectangular ``{kind, label, color, columns, rows}`` grid for a set of records.

    Unlike :func:`info_panel` this keeps every column even when a cell is empty, so the
    browser can render it as a real table rather than a definition list.
    """
    spec = SPECS[kind]
    lookup = media_by_record or {}
    rows = [sheet_row(kind, record, lookup.get(record.id, [])) for record in records]
    return {
        "kind": kind,
        "label": spec.plural,
        "color": spec.color,
        "columns": sheet_columns(kind),
        "rows": rows,
    }
