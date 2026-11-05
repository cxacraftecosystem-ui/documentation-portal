from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any, Callable, Iterable

from app.services import aadhaar_fingerprint
from app.services.artisan_identity import mask_aadhaar
from app.services.records import derive_age, derive_experience_years
from app.services.standard_values import (
    TRADITION_UNANSWERED,
    language_name,
    material_family_label,
    phone_of,
    tradition_label,
)
from app.services.tool_scope import tool_artisan_names, tool_workshop_titles


def ev(value: Any) -> Any:
    return getattr(value, "value", value)


def num(value: Any) -> str | None:
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


def tradition_text(value: Any) -> str | None:
    raw = str(ev(value) or "").strip()
    if not raw or raw == TRADITION_UNANSWERED:
        return None
    return tradition_label(raw) or enum_label(raw)


def enum_label(value: Any) -> str | None:
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
    return ""  # pragma: no cover


def flatten_rich_text(value: str) -> str | None:
    text = value.strip()
    if not (text.startswith("{") and '"blocks"' in text):
        return None
    try:
        doc = json.loads(text)
    except ValueError:
        return None
    blocks = doc.get("blocks") if isinstance(doc, dict) else None
    if not isinstance(blocks, list) or not all(isinstance(b, dict) for b in blocks):
        return None

    def spans_text(spans: Any) -> str:
        if not isinstance(spans, list):
            return ""
        return "".join(str(s.get("text", "")) for s in spans if isinstance(s, dict))

    lines: list[str] = []
    ordinal = 0
    for block in blocks:
        kind = str(block.get("kind", "PARAGRAPH"))
        level = block.get("level") if isinstance(block.get("level"), int) else 0
        if kind == "IMAGE":
            ordinal = 0
            caption = spans_text(block.get("spans")).strip()
            if caption:
                lines.append(caption)
            continue
        if kind == "TABLE":
            ordinal = 0
            for row in block.get("rows") or []:
                if isinstance(row, list):
                    lines.append(" | ".join(spans_text(c).strip() for c in row))
            continue
        body = spans_text(block.get("spans")).strip()
        if kind == "ORDERED_ITEM":
            ordinal += 1
            lines.append("  " * level + f"{ordinal}. " + body)
            continue
        ordinal = 0
        lines.append("  " * level + "• " + body if kind == "BULLET_ITEM" else body)
    return "\n".join(lines).strip()


def cell(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip()
    if isinstance(value, str):
        flat = flatten_rich_text(text)
        if flat is not None:
            return flat
    return text


def _first_answer(*values: Any) -> Any:
    return next((v for v in values if v is not None), None)


def meta_of(record: Any) -> dict[str, Any]:
    meta = getattr(record, "extraMetadata", None)
    return meta if isinstance(meta, dict) else {}


def meta_val(meta: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = meta.get(key)
        if isinstance(value, (str, int, float)) and str(value).strip():
            return value
    return None


def _rel(record: Any, relation: str, attr: str) -> Any:
    return getattr(getattr(record, relation, None), attr, None)


def _workshop_titles(record: Any, relation: str = "workshops") -> str | None:
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
    names = artisan_names(interview)
    base = ", ".join(names) if names else (getattr(interview, "title", None) or "").strip()
    base = base or "Interview"
    instrument = getattr(getattr(interview, "questionnaire", None), "title", None)
    return f"{base} ({instrument})" if instrument else base



@dataclass(frozen=True)
class FieldSpec:
    label: str
    get: Callable[[Any], Any]


@dataclass(frozen=True)
class RecordSpec:
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
        _f("Village/Place", lambda a: _rel(a, "location", "village") or a.place),
        _f(
            "District",
            lambda a: _rel(a, "location", "district") or meta_val(meta_of(a), "district"),
        ),
        _f("State", lambda a: _rel(a, "location", "state") or meta_val(meta_of(a), "state")),
        _f("Address", lambda a: a.address),
        _f(
            "Pincode",
            lambda a: _rel(a, "location", "pincode")
            or meta_val(meta_of(a), "pincode", "pinCode", "postalCode"),
        ),
        _f("Phone", phone_of),
        _f("Email", lambda a: a.email),
        _f("Aadhaar number", aadhaar_fingerprint.stored_mask),
        _f(
            "Artisan Pehchan Card",
            lambda a: "Yes" if a.pehchanCardAvailable else "No",
        ),
        _f("Pehchan card number", lambda a: mask_aadhaar(a.pehchanCardNumber)),
        _f("Date of birth", lambda a: getattr(a, "dateOfBirth", None)),
        _f("Age", lambda a: _first_answer(
            derive_age(getattr(a, "dateOfBirth", None)),
            meta_val(meta_of(a), "age"),
        )),
        _f("Gender", lambda a: a.gender),
        _f("Practising since", lambda a: getattr(a, "craftStartDate", None)),
        _f(
            "Experience (years)",
            lambda a: _first_answer(
                derive_experience_years(getattr(a, "craftStartDate", None)),
                getattr(a, "experienceYears", None),
                meta_val(meta_of(a), "experienceYears", "experience", "yearsOfExperience"),
            ),
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
        _f("Linked workshops", tool_workshop_titles),
        _f("Craft", lambda t: t.craftName),
        _f("Artisan", tool_artisan_names),
        _f("Place", lambda t: t.place),
        _f("Usage", lambda t: t.processUsedIn),
        _f("Material", lambda t: t.material),
        _f("Material type", lambda t: material_family_label(t.materialFamily)),
        _f("Years in use", lambda t: t.yearsInUse),
        _f("Dimensions (LxBxH in)", lambda t: dims(t.lengthInches, t.breadthInches, t.heightInches)),
        _f("Height (cm)", lambda t: num(t.height)),
        _f("Width (cm)", lambda t: num(t.width)),
        _f("Thickness", lambda t: num(t.thickness)),
        _f("Weight", lambda t: num(t.weight)),
        _f("Radius", lambda t: num(t.radius)),
        _f("Cost", lambda t: money(t.replacementCost)),
        _f("Maker", lambda t: enum_label(t.maker)),
        _f("Tradition", lambda t: tradition_text(t.traditionType)),
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
        _f(
            "Questionnaire",
            lambda i: getattr(getattr(i, "questionnaire", None), "title", None),
        ),
        _f("Location", lambda i: i.place),
        _f("Language", lambda i: language_name(i.language)),
        _f("Recorded at", lambda i: date_str(i.interviewDate or i.recordedAt)),
        _f("Notes", lambda i: i.notes),
    ),
)

SPECS: dict[str, RecordSpec] = {
    spec.kind: spec for spec in (WORKSHOP, CRAFT, ARTISAN, PRODUCT, TOOL, PROCESS, INTERVIEW)
}

MEDIA_COLOR = "#334155"
TRANSCRIPT_COLOR = "#334155"
OVERVIEW_COLOR = "#5B21B6"


def spec_for(kind: str) -> RecordSpec | None:
    return SPECS.get(kind)


PROVENANCE_COLUMNS = ("Status", "Created by", "Created on")

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
    items = list(media or [])
    if not items:
        return ["0", "", ""]
    names = [cell(getattr(m, "originalFilename", None)) or cell(m.id) for m in items]
    urls = [cell(getattr(m, "url", None)) for m in items]
    return [str(len(items)), " | ".join(n for n in names if n), " | ".join(u for u in urls if u)]


def info_panel(kind: str, record: Any) -> dict[str, Any] | None:
    spec = SPECS.get(kind)
    if spec is None:
        return None
    fields: list[dict[str, str]] = []
    for label, value in spec.field_pairs(record):
        text = cell(value)
        if text:
            fields.append({"label": label, "value": text})
    panel: dict[str, Any] = {"title": spec.title(record), "fields": fields}
    record_id = getattr(record, "id", None)
    if isinstance(record_id, str) and record_id:
        panel["record"] = {"kind": kind, "id": record_id}
    return panel


def info_text(info: dict[str, Any] | None) -> str:
    if not info:
        return ""
    lines = [str(info.get("title") or "").strip()]
    lines.extend(f"{f['label']}: {f['value']}" for f in info.get("fields") or [])
    return "\n".join(line for line in lines if line)


def sheet_columns(kind: str) -> list[str]:
    spec = SPECS[kind]
    return [
        spec.label,
        *(f.label for f in spec.fields),
        *PROVENANCE_COLUMNS,
        *MEDIA_COLUMNS,
    ]


def sheet_row(kind: str, record: Any, media: Iterable[Any] = ()) -> list[str]:
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
