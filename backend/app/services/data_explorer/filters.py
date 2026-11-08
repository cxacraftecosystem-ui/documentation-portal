from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import HTTPException, Request, status

from app.core.db import db

LOCAL_ZONE = ZoneInfo("Asia/Kolkata")
MEDIA_TYPES: tuple[str, ...] = ("IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER")
STATUSES: tuple[str, ...] = ("DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION")
PORTALS: tuple[str, ...] = ("this", "other")
RECORD_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media", "designWorkshops")
TRANSCRIPT_CHOICES: tuple[str, ...] = ("yes", "no")
DESIGN_WORKSHOP_TAGS: list[str] = ["designWorkshop", "designworkshop"]
FACET_OPTIONS_MAX = 500
VALUES_MAX = 200
VALUE_CHARS_MAX = 64
GROUP_TAKE = 20000

PARAMS: dict[str, tuple[str, str]] = {
    "recordTypes": ("recordType", "Record type"),
    "workshopIds": ("workshop", "Workshop"),
    "designWorkshopIds": ("designWorkshop", "Design workshop"),
    "craftIds": ("craft", "Craft"),
    "artisanIds": ("artisan", "Artisan"),
    "mediaTypes": ("mediaType", "Media type"),
    "statuses": ("status", "Review status"),
    "recordedByIds": ("recordedBy", "Recorded by"),
    "languages": ("language", "Language"),
    "hasTranscript": ("hasTranscript", "Has transcript"),
}
FACET_ORDER: tuple[str, ...] = (
    "portal",
    "recordType",
    "workshop",
    "designWorkshop",
    "craft",
    "artisan",
    "mediaType",
    "status",
    "recordedBy",
    "language",
    "hasTranscript",
    "date",
)
MEDIA_TYPE_LABELS = {
    "IMAGE": "Images",
    "VIDEO": "Videos",
    "AUDIO": "Audio",
    "PDF": "PDFs",
    "DOCUMENT": "Documents",
    "OTHER": "Other",
}
STATUS_LABELS = {
    "DRAFT": "Draft",
    "PENDING": "Pending",
    "APPROVED": "Approved",
    "REJECTED": "Rejected",
    "NEEDS_REVISION": "Needs revision",
}
RECORD_TYPE_LABELS = {
    "artisans": "Artisans",
    "workshops": "Workshops",
    "products": "Products",
    "tools": "Tools",
    "media": "Media",
    "designWorkshops": "Design workshops",
}
TRANSCRIPT_LABELS = {"yes": "Yes", "no": "No"}
RECORD_TYPE_OF_KIND = {
    "artisan": "artisans",
    "workshop": "workshops",
    "product": "products",
    "tool": "tools",
    "process": "products",
    "media": "media",
    "designWorkshop": "designWorkshops",
}
FIELD_OF_FACET = {
    "recordType": "record_types",
    "workshop": "workshops",
    "designWorkshop": "design_workshops",
    "craft": "crafts",
    "artisan": "artisans",
    "mediaType": "media_types",
    "status": "statuses",
    "recordedBy": "recorded_by",
    "language": "languages",
    "hasTranscript": "has_transcript",
}
FOLDER_FACET = {
    "workshop": "workshop",
    "designWorkshop": "designWorkshop",
    "craft": "craft",
    "artisan": "artisan",
    "user": "recordedBy",
}
NO_CRAFT_LEAF = "_none"


@dataclass(frozen=True)
class Filter:
    workshops: tuple[str, ...] = ()
    design_workshops: tuple[str, ...] = ()
    crafts: tuple[str, ...] = ()
    artisans: tuple[str, ...] = ()
    media_types: tuple[str, ...] = ()
    statuses: tuple[str, ...] = ()
    recorded_by: tuple[str, ...] = ()
    languages: tuple[str, ...] = ()
    date_from: date | None = None
    date_to: date | None = None
    portals: tuple[str, ...] = ("this",)
    record_types: tuple[str, ...] = ()
    has_transcript: tuple[str, ...] = ()

    @property
    def active(self) -> bool:
        return bool(
            self.record_types
            or self.has_transcript
            or self.workshops
            or self.design_workshops
            or self.crafts
            or self.artisans
            or self.media_types
            or self.statuses
            or self.recorded_by
            or self.languages
            or self.date_from
            or self.date_to
        )

    @property
    def media_only(self) -> bool:
        return bool(
            self.media_types
            or self.recorded_by
            or self.languages
            or self.has_transcript
            or self.date_from
            or self.date_to
        )

    @property
    def transcript(self) -> str | None:
        return self.has_transcript[0] if self.has_transcript else None

    def allows(self, record_type: str | None) -> bool:
        return not self.record_types or record_type is None or record_type in self.record_types

    def allows_kind(self, kind: str) -> bool:
        return self.allows(RECORD_TYPE_OF_KIND.get(kind))

    def values(self, facet: str) -> tuple[str, ...]:
        return getattr(self, FIELD_OF_FACET[facet], ())

    def without(self, facet: str) -> Filter:
        if facet == "date":
            return dataclasses.replace(self, date_from=None, date_to=None)
        return dataclasses.replace(self, **{FIELD_OF_FACET[facet]: ()})


NONE = Filter()


def real(flt: Any) -> Filter | None:
    return flt if isinstance(flt, Filter) else None


def has_design_workshops() -> bool:
    return hasattr(db, "designworkshop")


def _split(raw: list[str]) -> tuple[str, ...]:
    seen: dict[str, None] = {}
    for value in raw:
        for part in str(value).split(","):
            text = part.strip()
            if text:
                seen.setdefault(text, None)
    return tuple(seen)


def _refuse(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=detail)


def _ids(name: str, raw: list[str]) -> tuple[str, ...]:
    values = _split(raw)
    if len(values) > VALUES_MAX:
        raise _refuse(f"{name} takes at most {VALUES_MAX} values")
    if any(len(v) > VALUE_CHARS_MAX for v in values):
        raise _refuse(f"{name} has a value longer than {VALUE_CHARS_MAX} characters")
    return values


def _enum(
    name: str, raw: list[str], allowed: tuple[str, ...], *, upper: bool = True
) -> tuple[str, ...]:
    values = tuple(v.upper() if upper else v.lower() for v in _split(raw))
    bad = [v for v in values if v not in allowed]
    if bad:
        raise _refuse(f"{name} takes {', '.join(allowed)}")
    return values


def _day(name: str, raw: str | None) -> date | None:
    text = (raw or "").strip()
    if not text:
        return None
    try:
        return date.fromisoformat(text[:10])
    except ValueError:
        raise _refuse(f"{name} must be a date written yyyy-mm-dd") from None


def _record_types(raw: list[str]) -> tuple[str, ...]:
    canonical = {t.lower(): t for t in RECORD_TYPES}
    values = _split(raw)
    if any(v.lower() not in canonical for v in values):
        raise _refuse(f"recordTypes takes {', '.join(RECORD_TYPES)}")
    return tuple(dict.fromkeys(canonical[v.lower()] for v in values))


def _transcript_choice(raw: list[str]) -> tuple[str, ...]:
    picked = _enum("hasTranscript", raw, (*TRANSCRIPT_CHOICES, "any"), upper=False)
    values = [v for v in picked if v != "any"]
    if len(values) > 1:
        raise _refuse("hasTranscript takes one of yes, no")
    return tuple(values)


def parse(query: Any) -> Filter:
    def every(name: str) -> list[str]:
        getter = getattr(query, "getlist", None)
        if callable(getter):
            return list(getter(name))
        value = query.get(name) if hasattr(query, "get") else None
        if value is None:
            return []
        return list(value) if isinstance(value, list | tuple) else [str(value)]

    def first(name: str) -> str | None:
        values = every(name)
        return values[-1] if values else None

    date_from = _day("dateFrom", first("dateFrom"))
    date_to = _day("dateTo", first("dateTo"))
    if date_from and date_to and date_from > date_to:
        raise _refuse("dateFrom must be on or before dateTo")
    portals = _enum("portals", every("portals"), PORTALS, upper=False) or ("this",)
    return Filter(
        workshops=_ids("workshopIds", every("workshopIds")),
        design_workshops=_ids("designWorkshopIds", every("designWorkshopIds")),
        crafts=_ids("craftIds", every("craftIds")),
        artisans=_ids("artisanIds", every("artisanIds")),
        media_types=_enum("mediaTypes", every("mediaTypes"), MEDIA_TYPES),
        statuses=_enum("statuses", every("statuses"), STATUSES),
        recorded_by=_ids("recordedByIds", every("recordedByIds")),
        languages=tuple(v.lower() for v in _ids("languages", every("languages"))),
        date_from=date_from,
        date_to=date_to,
        portals=portals,
        record_types=_record_types(every("recordTypes")),
        has_transcript=_transcript_choice(every("hasTranscript")),
    )


def from_request(request: Request) -> Filter:
    return parse(request.query_params)


def day_bounds(start: date | None, end: date | None) -> tuple[datetime | None, datetime | None]:
    low = datetime.combine(start, time.min, LOCAL_ZONE) if start else None
    high = datetime.combine(end + timedelta(days=1), time.min, LOCAL_ZONE) if end else None
    return low, high


def _workshop_clause(ids: list[str]) -> dict[str, Any]:
    inside = {"in": ids}
    return {
        "OR": [
            {"workshopId": inside},
            {
                "artisan": {
                    "is": {
                        "OR": [
                            {"workshopId": inside},
                            {"workshops": {"some": {"workshopId": inside}}},
                        ]
                    }
                }
            },
            {"product": {"is": {"workshopId": inside}}},
            {"tool": {"is": {"workshopId": inside}}},
            {"questionnaireInterview": {"is": {"workshopId": inside}}},
        ]
    }


def _design_workshop_clause(ids: list[str]) -> dict[str, Any]:
    branches: list[dict[str, Any]] = [
        {
            "AND": [
                {"linkedRecordType": {"in": DESIGN_WORKSHOP_TAGS}},
                {"linkedRecordId": {"in": ids}},
            ]
        }
    ]
    if has_design_workshops():
        branches.append({"designWorkshopId": {"in": ids}})
    return {"OR": branches}


def _craft_clause(ids: list[str]) -> dict[str, Any]:
    inside = {"in": ids}
    return {
        "OR": [
            {"craftId": inside},
            {"artisan": {"is": {"craftId": inside}}},
            {"product": {"is": {"craftId": inside}}},
            {"tool": {"is": {"craftId": inside}}},
        ]
    }


def _artisan_clause(ids: list[str]) -> dict[str, Any]:
    inside = {"in": ids}
    return {
        "OR": [
            {"artisanId": inside},
            {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": inside}]},
            {"product": {"is": {"artisanId": inside}}},
            {"tool": {"is": {"artisanId": inside}}},
            {"questionnaireInterview": {"is": {"artisans": {"some": {"artisanId": inside}}}}},
        ]
    }


def _language_clause(codes: list[str]) -> dict[str, Any]:
    return {
        "OR": [
            {"transcriptLanguage": {"in": codes}},
            {
                "transcriptVersions": {
                    "some": {"supersededAt": None, "languages": {"has_some": codes}}
                }
            },
        ]
    }


TRANSCRIBED: dict[str, Any] = {
    "AND": [{"transcriptText": {"not": None}}, {"transcriptText": {"not": ""}}]
}
UNTRANSCRIBED: dict[str, Any] = {"OR": [{"transcriptText": None}, {"transcriptText": ""}]}
NOTHING: dict[str, Any] = {"id": {"in": []}}


def transcript_clause(choice: str | None) -> dict[str, Any] | None:
    if choice == "yes":
        return TRANSCRIBED
    if choice == "no":
        return UNTRANSCRIBED
    return None


def owner_clause(record_type: str) -> dict[str, Any] | None:
    if record_type == "artisans":
        return {
            "OR": [
                {"linkedRecordType": "artisan"},
                {"AND": [{"artisanId": {"not": None}}, {"linkedRecordType": None}]},
            ]
        }
    if record_type == "products":
        return {"OR": [{"productId": {"not": None}}, {"linkedRecordType": "product"}]}
    if record_type == "tools":
        return {"OR": [{"toolId": {"not": None}}, {"linkedRecordType": "tool"}]}
    if record_type == "workshops":
        return {
            "AND": [
                {"artisanId": None},
                {"productId": None},
                {"toolId": None},
                {"questionnaireInterviewId": None},
                {"OR": [{"workshopId": {"not": None}}, {"linkedRecordType": "workshop"}]},
            ]
        }
    if record_type == "designWorkshops":
        branches: list[dict[str, Any]] = [{"linkedRecordType": {"in": DESIGN_WORKSHOP_TAGS}}]
        if has_design_workshops():
            branches.append({"designWorkshopId": {"not": None}})
        return {"OR": branches}
    return None


def record_type_clause(types: tuple[str, ...]) -> dict[str, Any] | None:
    if not types or "media" in types:
        return None
    branches = [c for c in (owner_clause(t) for t in types) if c]
    if not branches:
        return NOTHING
    return branches[0] if len(branches) == 1 else {"OR": branches}


def media_where(flt: Filter | None) -> dict[str, Any] | None:
    flt = real(flt)
    if flt is None or not flt.active:
        return None
    parts: list[dict[str, Any]] = []
    owned = record_type_clause(flt.record_types)
    if owned:
        parts.append(owned)
    transcribed = transcript_clause(flt.transcript)
    if transcribed:
        parts.append(transcribed)
    if flt.workshops:
        parts.append(_workshop_clause(list(flt.workshops)))
    if flt.design_workshops:
        parts.append(_design_workshop_clause(list(flt.design_workshops)))
    if flt.crafts:
        parts.append(_craft_clause(list(flt.crafts)))
    if flt.artisans:
        parts.append(_artisan_clause(list(flt.artisans)))
    if flt.media_types:
        parts.append({"mediaType": {"in": list(flt.media_types)}})
    if flt.statuses:
        parts.append({"status": {"in": list(flt.statuses)}})
    if flt.recorded_by:
        parts.append({"uploadedById": {"in": list(flt.recorded_by)}})
    if flt.languages:
        parts.append(_language_clause(list(flt.languages)))
    low, high = day_bounds(flt.date_from, flt.date_to)
    if low or high:
        span: dict[str, Any] = {}
        if low:
            span["gte"] = low
        if high:
            span["lt"] = high
        parts.append({"recordedAt": span})
    if not parts:
        return None
    return parts[0] if len(parts) == 1 else {"AND": parts}


def record_status_where(flt: Filter | None) -> dict[str, Any] | None:
    flt = real(flt)
    if flt is None or not flt.statuses:
        return None
    return {"status": {"in": list(flt.statuses)}}


def _and(base: dict[str, Any] | None, extra: dict[str, Any] | None) -> dict[str, Any]:
    if not extra:
        return base or {}
    if not base:
        return dict(extra)
    return {"AND": [base, extra]}


def narrow(scope: Any, flt: Filter | None) -> Any:
    flt = real(flt)
    if flt is None or not flt.active or not dataclasses.is_dataclass(scope):
        return scope
    changes: dict[str, Any] = {"media": _and(getattr(scope, "media", None), media_where(flt))}
    names = {f.name for f in dataclasses.fields(scope)}
    if "explorer_filter" in names:
        changes["explorer_filter"] = flt
    return dataclasses.replace(scope, **{k: v for k, v in changes.items() if k in names})


MEDIA_RELATION_KINDS = frozenset({"artisan", "product", "tool", "workshop", "craft", "interview"})
STATUS_KINDS = frozenset({"artisan", "product", "tool", "workshop", "interview", "process"})


def browse_where(kind: str, flt: Filter | None) -> dict[str, Any] | None:
    flt = real(flt)
    from app.services.record_filters import artisan_workshop_clause, tool_workshop_clause

    if flt is None or not flt.active:
        return None
    if not flt.allows_kind(kind):
        return NOTHING
    if kind == "media":
        return None
    parts: list[dict[str, Any]] = []
    if flt.workshops:
        ids = list(flt.workshops)
        inside = {"in": ids}
        clause = {
            "artisan": artisan_workshop_clause(ids, False),
            "tool": tool_workshop_clause(ids, False),
            "product": {"workshopId": inside},
            "interview": {"workshopId": inside},
            "process": {"workshopId": inside},
            "workshop": {"id": inside},
            "craft": {
                "OR": [{"workshopId": inside}, {"workshops": {"some": {"workshopId": inside}}}]
            },
        }.get(kind)
        if clause:
            parts.append(clause)
    if flt.design_workshops:
        ids = list(flt.design_workshops)
        if not has_design_workshops():
            return {"id": {"in": []}}
        inside = {"in": ids}
        clause = {
            "artisan": {"designWorkshopId": inside},
            "product": {"designWorkshopId": inside},
            "tool": {"designWorkshopId": inside},
            "interview": {"designWorkshopId": inside},
            "process": {"product": {"is": {"designWorkshopId": inside}}},
            "workshop": {"designWorkshops": {"some": {"id": inside}}},
            "craft": {"designWorkshops": {"some": {"id": inside}}},
        }.get(kind)
        if clause:
            parts.append(clause)
    if flt.crafts:
        inside = {"in": list(flt.crafts)}
        clause = {
            "artisan": {"craftId": inside},
            "product": {"craftId": inside},
            "tool": {"craftId": inside},
            "craft": {"id": inside},
            "workshop": {"crafts": {"some": {"craftId": inside}}},
            "interview": {"artisans": {"some": {"artisan": {"is": {"craftId": inside}}}}},
            "process": {"product": {"is": {"craftId": inside}}},
        }.get(kind)
        if clause:
            parts.append(clause)
    if flt.artisans:
        inside = {"in": list(flt.artisans)}
        clause = {
            "artisan": {"id": inside},
            "product": {"artisanId": inside},
            "tool": {"artisanId": inside},
            "interview": {"artisans": {"some": {"artisanId": inside}}},
            "process": {"product": {"is": {"artisanId": inside}}},
            "workshop": {"artisans": {"some": {"artisanId": inside}}},
            "craft": {"artisans": {"some": {"id": inside}}},
        }.get(kind)
        if clause:
            parts.append(clause)
    if flt.statuses and kind in STATUS_KINDS:
        parts.append({"status": {"in": list(flt.statuses)}})
    if flt.recorded_by and kind != "craft":
        parts.append({"createdById": {"in": list(flt.recorded_by)}})
    if flt.media_only and kind in MEDIA_RELATION_KINDS:
        matching = media_where(dataclasses.replace(flt, record_types=()))
        if matching:
            parts.append({"media": {"some": {"AND": [{"removedAt": None}, matching]}}})
    if not parts:
        return None
    return parts[0] if len(parts) == 1 else {"AND": parts}


def _leaf(entry: dict[str, Any]) -> str:
    return str(entry.get("path") or "").rstrip("/").rsplit("/", 1)[-1]


def _selected_by(entry: dict[str, Any], flt: Filter) -> bool | None:
    if entry.get("kind") != "folder":
        return None
    facet = FOLDER_FACET.get(str(entry.get("recordType") or ""))
    if facet is None:
        return None
    chosen = flt.values(facet)
    if not chosen:
        return None
    return _leaf(entry) in chosen


def _documents_wanted(flt: Filter) -> bool:
    return not flt.media_types or "DOCUMENT" in flt.media_types


def prune(entries: list[dict[str, Any]], flt: Filter | None) -> list[dict[str, Any]]:
    flt = real(flt)
    if flt is None or not flt.active:
        return entries
    kept: list[dict[str, Any]] = []
    for entry in entries:
        if _selected_by(entry, flt) is False:
            continue
        if (
            entry.get("kind") == "folder"
            and flt.crafts
            and entry.get("recordType") == "category"
            and _leaf(entry) == NO_CRAFT_LEAF
        ):
            continue
        if entry.get("kind") == "file" and not entry.get("mediaId") and not _documents_wanted(flt):
            continue
        kept.append(entry)
    return kept


def drop_empty(
    entries: list[dict[str, Any]], flt: Filter | None
) -> tuple[list[dict[str, Any]], int]:
    flt = real(flt)
    if flt is None or not flt.active:
        return entries, 0
    kept: list[dict[str, Any]] = []
    hidden = 0
    for entry in entries:
        if entry.get("kind") == "folder" and entry.get("count") == 0:
            if _selected_by(entry, flt) and not flt.media_only and not flt.record_types:
                kept.append(entry)
                continue
            hidden += 1
            continue
        kept.append(entry)
    return kept, hidden


def keep_records(
    rows: list[Any], kind: str, flt: Filter | None, media: list[Any] | None = None
) -> list[Any]:
    flt = real(flt)
    if flt is None or not flt.active or not rows:
        return rows

    def attr(row: Any, name: str) -> Any:
        if isinstance(row, dict):
            return row.get(name)
        return getattr(row, name, None)

    def linked_workshops(row: Any) -> set[str]:
        found = {str(attr(row, "workshopId"))} if attr(row, "workshopId") else set()
        for link in attr(row, "workshops") or []:
            wid = attr(link, "workshopId")
            if wid:
                found.add(str(wid))
        return found

    def interview_artisans(row: Any) -> set[str]:
        return {
            str(attr(link, "artisanId"))
            for link in attr(row, "artisans") or []
            if attr(link, "artisanId")
        }

    owned: dict[str, set[str]] = {}
    if flt.media_only and media is not None:
        for m in media:
            for key, fk in (
                ("artisan", "artisanId"),
                ("product", "productId"),
                ("tool", "toolId"),
                ("interview", "questionnaireInterviewId"),
                ("workshop", "workshopId"),
                ("craft", "craftId"),
            ):
                value = attr(m, fk)
                if value:
                    owned.setdefault(key, set()).add(str(value))
            tag, tagged = attr(m, "linkedRecordType"), attr(m, "linkedRecordId")
            if tag and tagged:
                owned.setdefault(str(tag).lower(), set()).add(str(tagged))

    if not flt.allows_kind(kind):
        return []

    def passes(row: Any) -> bool:
        rid = str(attr(row, "id") or "")
        status_value = attr(row, "status")
        status_value = getattr(status_value, "value", status_value)
        if flt.statuses and kind in STATUS_KINDS and str(status_value or "") not in flt.statuses:
            return False
        if kind == "workshop":
            if flt.workshops and rid not in flt.workshops:
                return False
        elif kind == "craft":
            if flt.crafts and rid not in flt.crafts:
                return False
        elif kind == "artisan":
            if flt.artisans and rid not in flt.artisans:
                return False
            if flt.crafts and str(attr(row, "craftId") or "") not in flt.crafts:
                return False
            if flt.workshops and not (linked_workshops(row) & set(flt.workshops)):
                return False
            if (
                flt.design_workshops
                and str(attr(row, "designWorkshopId") or "") not in flt.design_workshops
            ):
                return False
        elif kind in ("product", "tool"):
            if flt.artisans and str(attr(row, "artisanId") or "") not in flt.artisans:
                return False
            if flt.crafts and str(attr(row, "craftId") or "") not in flt.crafts:
                return False
            if flt.workshops and str(attr(row, "workshopId") or "") not in flt.workshops:
                return False
            if (
                flt.design_workshops
                and str(attr(row, "designWorkshopId") or "") not in flt.design_workshops
            ):
                return False
        elif kind == "interview":
            if flt.workshops and str(attr(row, "workshopId") or "") not in flt.workshops:
                return False
            if flt.artisans and not (interview_artisans(row) & set(flt.artisans)):
                return False
            if (
                flt.design_workshops
                and str(attr(row, "designWorkshopId") or "") not in flt.design_workshops
            ):
                return False
        elif kind == "designWorkshop" and flt.design_workshops and rid not in flt.design_workshops:
            return False
        if flt.media_only and media is not None and kind in owned_keys:
            return rid in owned.get(owned_keys[kind], set())
        return True

    owned_keys = {
        "artisan": "artisan",
        "product": "product",
        "tool": "tool",
        "interview": "interview",
    }
    return [row for row in rows if passes(row)]


REPORT_KINDS = {
    "workshops": "workshop",
    "crafts": "craft",
    "artisans": "artisan",
    "products": "product",
    "tools": "tool",
    "interviews": "interview",
    "designWorkshops": "designWorkshop",
}


def filter_report(data: dict[str, list[Any]], flt: Filter | None) -> dict[str, list[Any]]:
    flt = real(flt)
    if flt is None or not flt.active:
        return data
    media = data.get("media")
    for key, kind in REPORT_KINDS.items():
        if data.get(key):
            data[key] = keep_records(data[key], kind, flt, media)
    if data.get("processes") and flt.active:
        products = {str(getattr(p, "id", "")) for p in data.get("products") or []}
        data["processes"] = [
            p for p in data["processes"] if str(getattr(p, "productId", "") or "") in products
        ]
    if data.get("dwEntries") and not flt.allows("designWorkshops"):
        data["dwEntries"] = []
    if data.get("dwEntries") and flt.design_workshops:
        data["dwEntries"] = [
            e
            for e in data["dwEntries"]
            if str(getattr(e, "designWorkshopId", "") or "") in flt.design_workshops
        ]
    return data


def as_query(flt: Filter | None) -> dict[str, str]:
    flt = real(flt)
    if flt is None:
        return {}
    out: dict[str, str] = {}
    for param, (facet, _label) in PARAMS.items():
        values = flt.values(facet)
        if values:
            out[param] = ",".join(values)
    if flt.date_from:
        out["dateFrom"] = flt.date_from.isoformat()
    if flt.date_to:
        out["dateTo"] = flt.date_to.isoformat()
    return out


async def _names(delegate: str, ids: list[str], field: str) -> dict[str, str]:
    if not ids or not hasattr(db, delegate):
        return {}
    rows = await getattr(db, delegate).find_many(where={"id": {"in": ids}})
    return {
        str(row.id): str(getattr(row, field, None) or "").strip() or str(row.id) for row in rows
    }


def language_label(code: str) -> str:
    try:
        from app.transcription.transcript.turns import language_label as label

        return label(code)
    except Exception:  # noqa: BLE001
        return code


async def labels(flt: Filter) -> dict[str, dict[str, str]]:
    workshops, design_workshops, crafts, artisans, users = (
        await _names("workshop", list(flt.workshops), "title"),
        await _names("designworkshop", list(flt.design_workshops), "title"),
        await _names("craft", list(flt.crafts), "name"),
        await _names("artisan", list(flt.artisans), "name"),
        await _names("user", list(flt.recorded_by), "name"),
    )
    return {
        "workshop": workshops,
        "designWorkshop": design_workshops,
        "craft": crafts,
        "artisan": artisans,
        "recordedBy": users,
        "mediaType": MEDIA_TYPE_LABELS,
        "status": STATUS_LABELS,
        "language": {code: language_label(code) for code in flt.languages},
        "recordType": RECORD_TYPE_LABELS,
        "hasTranscript": TRANSCRIPT_LABELS,
    }


async def describe(flt: Filter | None) -> dict[str, Any]:
    flt = real(flt)
    if flt is None or not flt.active:
        return {"active": [], "count": 0}
    names = await labels(flt)
    active: list[dict[str, Any]] = []
    for param, (facet, label) in PARAMS.items():
        values = flt.values(facet)
        if not values:
            continue
        known = names.get(facet, {})
        active.append(
            {
                "param": param,
                "facet": facet,
                "label": label,
                "values": [{"value": v, "label": known.get(v, v)} for v in values],
            }
        )
    if flt.date_from or flt.date_to:
        when = [
            {"value": v.isoformat(), "label": v.strftime("%d %b %Y")}
            for v in (flt.date_from, flt.date_to)
            if v is not None
        ]
        if flt.date_from:
            active.append(
                {"param": "dateFrom", "facet": "date", "label": "From", "values": when[:1]}
            )
        if flt.date_to:
            active.append({"param": "dateTo", "facet": "date", "label": "To", "values": when[-1:]})
    return {"active": active, "count": len(active)}


def _number(value: Any) -> int:
    if isinstance(value, dict):
        value = value.get("_all")
    try:
        return int(value or 0)
    except TypeError, ValueError:
        return 0


async def _grouped(column: str, where: dict[str, Any]) -> dict[str, int]:
    rows = await db.mediafile.group_by(
        by=[column], where=where, count=True, take=GROUP_TAKE, order={column: "asc"}
    )
    out: dict[str, int] = {}
    for row in rows:
        key = row.get(column)
        key = getattr(key, "value", key)
        if key is None:
            continue
        out[str(key)] = out.get(str(key), 0) + _number(row.get("_count"))
    return out


def _base(scope: Any, flt: Filter, facet: str) -> dict[str, Any]:
    parts: list[dict[str, Any]] = [{"removedAt": None}]
    media = getattr(scope, "media", None)
    if media:
        parts.append(media)
    other = media_where(flt.without(facet))
    if other:
        parts.append(other)
    return {"AND": parts}


def _option(value: str, label: str, count: int, hint: str | None = None) -> dict[str, Any]:
    return {"value": value, "label": label or value, "hint": hint or None, "count": int(count)}


def _ranked(
    options: list[dict[str, Any]], selected: tuple[str, ...]
) -> tuple[list[dict[str, Any]], bool]:
    chosen = [o for o in options if o["value"] in selected]
    rest = [o for o in options if o["value"] not in selected]
    rest.sort(key=lambda o: (-o["count"], o["label"].casefold()))
    room = max(0, FACET_OPTIONS_MAX - len(chosen))
    kept = chosen + rest[:room]
    kept.sort(key=lambda o: (o["label"].casefold(), o["value"]))
    return kept, len(rest) > room


async def _records(
    delegate: str, where: dict[str, Any] | None, ids: list[str] | None = None
) -> list[Any]:
    if not hasattr(db, delegate):
        return []
    parts: list[dict[str, Any]] = []
    if where:
        parts.append(where)
    if ids is not None:
        parts.append({"id": {"in": ids}})
    kwargs: dict[str, Any] = {"take": GROUP_TAKE}
    if parts:
        kwargs["where"] = parts[0] if len(parts) == 1 else {"AND": parts}
    return await getattr(db, delegate).find_many(**kwargs)


def _hint(*parts: Any) -> str | None:
    text = " · ".join(str(p).strip() for p in parts if p and str(p).strip())
    return text or None


async def facets(scope: Any, flt: Filter) -> list[dict[str, Any]]:
    flt = real(flt) or NONE
    records = getattr(scope, "records", None) or None
    out: list[dict[str, Any]] = []

    async def counts(column: str, facet: str, extra: dict[str, Any] | None = None) -> dict[str, int]:
        where = _base(scope, flt, facet)
        if extra:
            where = {"AND": [where, extra]}
        return await _grouped(column, where)

    async def counted(facet: str, extra: dict[str, Any] | None) -> int:
        where = _base(scope, flt, facet)
        if extra:
            where = {"AND": [where, extra]}
        return _number(await db.mediafile.count(where=where))

    offered = [
        t
        for t in RECORD_TYPES
        if t != "designWorkshops" or (has_design_workshops() and getattr(scope, "design_workshops", False))
    ]
    options = [
        _option(t, RECORD_TYPE_LABELS[t], await counted("recordType", owner_clause(t)))
        for t in offered
    ]
    options += [_option(t, RECORD_TYPE_LABELS[t], 0) for t in flt.record_types if t not in offered]
    out.append(
        {
            "facet": "recordType",
            "param": "recordTypes",
            "label": "Record type",
            "options": options,
            "truncated": False,
            "selected": list(flt.record_types),
        }
    )

    workshop_direct = await counts("workshopId", "workshop")
    by_artisan_for_workshop = await counts("artisanId", "workshop", {"workshopId": None})
    workshops = await _records("workshop", records)
    artisan_rows = await _records("artisan", records)
    artisan_workshop = {
        str(a.id): str(a.workshopId) for a in artisan_rows if getattr(a, "workshopId", None)
    }
    artisan_craft = {str(a.id): str(a.craftId) for a in artisan_rows if getattr(a, "craftId", None)}
    workshop_counts = dict(workshop_direct)
    for aid, count in by_artisan_for_workshop.items():
        wid = artisan_workshop.get(aid)
        if wid:
            workshop_counts[wid] = workshop_counts.get(wid, 0) + count
    options = [
        _option(
            str(w.id),
            str(getattr(w, "title", "") or "Workshop"),
            workshop_counts.get(str(w.id), 0),
            _hint(getattr(w, "place", None)),
        )
        for w in workshops
    ]
    kept, cut = _ranked(options, flt.workshops)
    out.append(
        {
            "facet": "workshop",
            "param": "workshopIds",
            "label": "Workshop",
            "options": kept,
            "truncated": cut,
            "selected": list(flt.workshops),
        }
    )

    if has_design_workshops() and getattr(scope, "design_workshops", False):
        direct = await counts("designWorkshopId", "designWorkshop")
        tagged_where = _base(scope, flt, "designWorkshop")
        tagged_where = {"AND": [tagged_where, {"linkedRecordType": {"in": DESIGN_WORKSHOP_TAGS}}]}
        tagged = await _grouped("linkedRecordId", tagged_where)
        rows = await _records("designworkshop", {"deletedAt": None})
        options = [
            _option(
                str(r.id),
                str(getattr(r, "title", "") or "Design workshop"),
                direct.get(str(r.id), 0) + tagged.get(str(r.id), 0),
                _hint(getattr(r, "craftName", None), getattr(r, "workshopCode", None)),
            )
            for r in rows
        ]
        kept, cut = _ranked(options, flt.design_workshops)
        if kept or flt.design_workshops:
            out.append(
                {
                    "facet": "designWorkshop",
                    "param": "designWorkshopIds",
                    "label": "Design workshop",
                    "options": kept,
                    "truncated": cut,
                    "selected": list(flt.design_workshops),
                }
            )

    craft_direct = await counts("craftId", "craft")
    by_artisan_for_craft = await counts("artisanId", "craft", {"craftId": None})
    craft_counts = dict(craft_direct)
    for aid, count in by_artisan_for_craft.items():
        cid = artisan_craft.get(aid)
        if cid:
            craft_counts[cid] = craft_counts.get(cid, 0) + count
    crafts = await _records("craft", {"mergedIntoId": None})
    options = [
        _option(
            str(c.id),
            str(getattr(c, "name", "") or "Craft"),
            craft_counts.get(str(c.id), 0),
            _hint(getattr(c, "place", None)),
        )
        for c in crafts
    ]
    kept, cut = _ranked(options, flt.crafts)
    out.append(
        {
            "facet": "craft",
            "param": "craftIds",
            "label": "Craft",
            "options": kept,
            "truncated": cut,
            "selected": list(flt.crafts),
        }
    )

    artisan_counts = await counts("artisanId", "artisan")
    options = [
        _option(
            str(a.id),
            str(getattr(a, "name", "") or "Artisan"),
            artisan_counts.get(str(a.id), 0),
            _hint(getattr(a, "place", None)),
        )
        for a in artisan_rows
    ]
    kept, cut = _ranked(options, flt.artisans)
    out.append(
        {
            "facet": "artisan",
            "param": "artisanIds",
            "label": "Artisan",
            "options": kept,
            "truncated": cut,
            "selected": list(flt.artisans),
        }
    )

    type_counts = await counts("mediaType", "mediaType")
    options = [_option(t, MEDIA_TYPE_LABELS[t], type_counts.get(t, 0)) for t in MEDIA_TYPES]
    out.append(
        {
            "facet": "mediaType",
            "param": "mediaTypes",
            "label": "Media type",
            "options": options,
            "truncated": False,
            "selected": list(flt.media_types),
        }
    )

    status_counts = await counts("status", "status")
    options = [_option(s, STATUS_LABELS[s], status_counts.get(s, 0)) for s in STATUSES]
    out.append(
        {
            "facet": "status",
            "param": "statuses",
            "label": "Review status",
            "options": options,
            "truncated": False,
            "selected": list(flt.statuses),
        }
    )

    uploader_counts = await counts("uploadedById", "recordedBy")
    uploader_ids = sorted(set(uploader_counts) | set(flt.recorded_by))
    names = await _names("user", uploader_ids, "name")
    options = [
        _option(uid, names.get(uid, uid), uploader_counts.get(uid, 0))
        for uid in uploader_ids
        if uid in names
    ]
    kept, cut = _ranked(options, flt.recorded_by)
    out.append(
        {
            "facet": "recordedBy",
            "param": "recordedByIds",
            "label": "Recorded by",
            "options": kept,
            "truncated": cut,
            "selected": list(flt.recorded_by),
        }
    )

    language_counts = await counts("transcriptLanguage", "language")
    codes = sorted(set(language_counts) | set(flt.languages))
    options = [
        _option(code, language_label(code), language_counts.get(code, 0)) for code in codes if code
    ]
    kept, cut = _ranked(options, flt.languages)
    if kept or flt.languages:
        out.append(
            {
                "facet": "language",
                "param": "languages",
                "label": "Language",
                "options": kept,
                "truncated": cut,
                "selected": list(flt.languages),
            }
        )

    options = [
        _option(c, TRANSCRIPT_LABELS[c], await counted("hasTranscript", transcript_clause(c)))
        for c in TRANSCRIPT_CHOICES
    ]
    out.append(
        {
            "facet": "hasTranscript",
            "param": "hasTranscript",
            "label": "Has transcript",
            "options": options,
            "truncated": False,
            "selected": list(flt.has_transcript),
        }
    )

    return [f for f in out if f["options"] or f["selected"]]


async def date_bounds(scope: Any) -> dict[str, str | None]:
    parts: list[dict[str, Any]] = [{"removedAt": None}]
    media = getattr(scope, "media", None)
    if media:
        parts.append(media)
    where = {"AND": parts}
    first = await db.mediafile.find_first(where=where, order={"recordedAt": "asc"})
    last = await db.mediafile.find_first(where=where, order={"recordedAt": "desc"})

    def local(row: Any) -> str | None:
        moment = getattr(row, "recordedAt", None) if row is not None else None
        if moment is None:
            return None
        if moment.tzinfo is None:
            from datetime import UTC

            moment = moment.replace(tzinfo=UTC)
        return moment.astimezone(LOCAL_ZONE).date().isoformat()

    return {"min": local(first), "max": local(last)}


SEARCH_KINDS = {"artisans": "artisan", "workshops": "workshop", "products": "product", "tools": "tool"}


def search_wheres(wheres: dict[str, Any], flt: Filter | None) -> dict[str, Any]:
    flt = real(flt)
    if flt is None or not flt.active:
        return wheres
    out = dict(wheres)
    records = dataclasses.replace(flt, has_transcript=())
    transcripts_only = flt.transcript == "yes" and not flt.record_types
    for key, kind in SEARCH_KINDS.items():
        if key in out:
            out[key] = NOTHING if transcripts_only else _and(out[key], browse_where(kind, records))
    if "media" in out:
        out["media"] = NOTHING if not flt.allows("media") else _and(out["media"], media_where(flt))
    if "designWorkshops" in out:
        if transcripts_only or not flt.allows("designWorkshops"):
            out["designWorkshops"] = NOTHING
        elif flt.design_workshops:
            out["designWorkshops"] = _and(out["designWorkshops"], {"id": {"in": list(flt.design_workshops)}})
    return out
