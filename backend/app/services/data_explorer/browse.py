from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

from app.core.db import db
from app.services.data_explorer import slim, sorting

PAGE_SIZE_DEFAULT = 50
PAGE_SIZE_MAX = 100


@dataclass(frozen=True)
class Kind:
    delegate: str
    search: tuple[str, ...]
    scoped: str
    live: tuple[tuple[str, Any], ...] = ()
    linked: str = ""


KINDS: dict[str, Kind] = {
    "artisan": Kind("artisan", ("name", "place"), "records", linked="artisan"),
    "product": Kind("productdocumentation", ("productName", "artisanName"), "records", linked="product"),
    "tool": Kind("tooldocumentation", ("toolkitName", "artisanName"), "records", linked="tool"),
    "workshop": Kind("workshop", ("title", "place"), "records", linked="workshop"),
    "craft": Kind("craft", ("name", "place"), "none", (("mergedIntoId", None),), linked="craft"),
    "interview": Kind(
        "questionnaireinterview",
        ("title", "place"),
        "records",
        (("deletedAt", None), ("mergedIntoId", None)),
        linked="questionnaire",
    ),
    "process": Kind("process", ("name",), "records", linked="process"),
    "media": Kind("mediafile", ("originalFilename",), "media", (("removedAt", None),)),
}

_MEDIA_FK = {
    "artisan": "artisanId",
    "product": "productId",
    "tool": "toolId",
    "workshop": "workshopId",
    "craft": "craftId",
    "questionnaire": "questionnaireInterviewId",
}
_MEDIA_TAGS = {
    "artisan": ["artisan"],
    "product": ["product"],
    "tool": ["tool"],
    "workshop": ["workshop"],
    "craft": ["craft"],
    "questionnaire": ["questionnaire", "questionnaireinterview"],
    "process": ["process"],
}


_RECORDED = {"workshop": "date"}
_MEDIA_FIELDS = {
    "name": "originalFilename",
    "recorded": "recordedAt",
    "added": "createdAt",
    "modified": "updatedAt",
    "size": "sizeBytes",
}


def _ordered(field: str, direction: str, name_field: str) -> list[dict[str, str]]:
    if field == name_field:
        return [{field: direction}, {"id": "asc"}]
    return [{field: direction}, {name_field: "asc"}, {"id": "asc"}]


def order_for(kind_key: str, sort: sorting.Sort) -> list[dict[str, str]]:
    if sort == sorting.BROWSE_DEFAULT:
        return [{"createdAt": "desc"}, {"id": "desc"}]
    kind = KINDS[kind_key]
    name_field = kind.search[0]
    field = {
        "name": name_field,
        "recorded": _RECORDED.get(kind_key, "recordedAt"),
        "added": "createdAt",
        "modified": "updatedAt",
    }[sort.key]
    return _ordered(field, sort.dir, name_field)


def media_order(sort: sorting.Sort) -> list[dict[str, str]]:
    if sort == sorting.BROWSE_DEFAULT:
        return [{"createdAt": "desc"}, {"id": "desc"}]
    return _ordered(_MEDIA_FIELDS[sort.key], sort.dir, _MEDIA_FIELDS["name"])


def page_args(page: int | None, page_size: int | None) -> tuple[int, int]:
    size = PAGE_SIZE_DEFAULT if page_size is None else max(1, min(PAGE_SIZE_MAX, int(page_size)))
    return max(1, int(page or 1)), size


def _and(*parts: dict[str, Any] | None) -> dict[str, Any]:
    kept = [p for p in parts if p]
    if not kept:
        return {}
    if len(kept) == 1:
        return kept[0]
    return {"AND": kept}


def where_for(kind: Kind, scope: Any, q: str | None, workshop_ids: list[str] | None) -> dict[str, Any]:
    scoped: dict[str, Any] | None = None
    if kind.scoped == "records":
        scoped = getattr(scope, "records", None) or None
    elif kind.scoped == "media":
        scoped = getattr(scope, "media", None) or None
    live = dict(kind.live) or None
    words = [w for w in (q or "").split() if w]
    search = (
        {
            "AND": [
                {"OR": [{field: {"contains": w, "mode": "insensitive"}} for field in kind.search]}
                for w in words
            ]
        }
        if words
        else None
    )
    return _and(scoped, live, search, workshop_where(kind, workshop_ids))


def workshop_where(kind: Kind, workshop_ids: list[str] | None) -> dict[str, Any] | None:
    from app.services.record_filters import artisan_workshop_clause, tool_workshop_clause

    if not workshop_ids:
        return None
    if kind.delegate == "tooldocumentation":
        return tool_workshop_clause(workshop_ids, False)
    if kind.delegate == "artisan":
        return artisan_workshop_clause(workshop_ids, False)
    if kind.delegate in ("questionnaireinterview", "productdocumentation", "process"):
        return {"workshopId": {"in": workshop_ids}}
    return None


def _creator(row: Any, media: bool) -> str | None:
    person = getattr(row, "uploadedBy" if media else "createdBy", None)
    name = (getattr(person, "name", None) or "").strip()
    return name or None


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    try:
        return value.isoformat()
    except AttributeError:
        return str(value)


def _join(*parts: Any) -> str:
    return " · ".join(str(p).strip() for p in parts if p and str(p).strip())


def label(kind_key: str, row: Any) -> str:
    if kind_key == "artisan":
        return _join(row.name, getattr(row, "place", None)) or "Artisan"
    if kind_key == "product":
        return _join(row.productName, getattr(row, "artisanName", None)) or "Product"
    if kind_key == "tool":
        return _join(row.toolkitName, getattr(row, "artisanName", None)) or "Tool"
    if kind_key == "workshop":
        return _join(row.title) or "Workshop"
    if kind_key == "craft":
        return _join(row.name, getattr(row, "place", None)) or "Craft"
    if kind_key == "interview":
        return _join(getattr(row, "title", None), getattr(row, "place", None)) or "Interview"
    if kind_key == "process":
        return _join(row.name) or "Process"
    media_type = getattr(row, "mediaType", None)
    media_type = getattr(media_type, "value", media_type)
    return _join(getattr(row, "originalFilename", None) or "Media", media_type)


async def page(
    kind_key: str,
    scope: Any,
    *,
    page_number: int,
    page_size: int,
    q: str | None,
    workshop_ids: list[str] | None,
    sort: sorting.Sort = sorting.BROWSE_DEFAULT,
    flt: Any = None,
) -> dict[str, Any]:
    from app.services.data_explorer.filters import browse_where

    kind = KINDS[kind_key]
    where = _and(where_for(kind, scope, q, workshop_ids), browse_where(kind_key, flt))
    is_media = kind.delegate == "mediafile"
    include = {"uploadedBy": True} if is_media else {"createdBy": True}
    kwargs: dict[str, Any] = {
        "take": page_size,
        "skip": (page_number - 1) * page_size,
        "order": order_for(kind_key, sort),
        "include": include,
    }
    if where:
        kwargs["where"] = where
    if is_media:
        rows = await slim.actions().find_many(**kwargs)
    else:
        rows = await getattr(db, kind.delegate).find_many(**kwargs)
    total = await getattr(db, kind.delegate).count(where=where) if where else await getattr(db, kind.delegate).count()
    return {
        "type": kind_key,
        "items": [
            {
                "id": str(row.id),
                "name": label(kind_key, row),
                "creator": _creator(row, is_media),
                "createdAt": _iso(getattr(row, "createdAt", None)),
            }
            for row in rows
        ],
        **envelope(total, page_number, page_size),
        "sort": sort.public(),
        "sorts": list(sorting.BROWSE_KEYS),
    }


def envelope(total: int, page_number: int, page_size: int) -> dict[str, Any]:
    return {
        "total": int(total),
        "page": page_number,
        "pageSize": page_size,
        "pages": max(1, math.ceil(int(total) / page_size)) if page_size else 1,
        "truncated": int(total) > page_number * page_size,
    }


def record_media_where(kind_key: str, record_id: str) -> dict[str, Any] | None:
    linked = KINDS[kind_key].linked
    if not linked:
        return None
    ors: list[dict[str, Any]] = []
    fk = _MEDIA_FK.get(linked)
    if fk:
        ors.append({fk: record_id})
    tags = _MEDIA_TAGS.get(linked)
    if tags:
        ors.append({"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": record_id}]})
    return {"OR": ors} if ors else None
