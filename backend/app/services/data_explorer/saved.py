from __future__ import annotations

from typing import Any

VIEW_KINDS = ("files", "tables", "transcripts")
NAME_MAX = 120
PATH_MAX = 1000
TEXT_MAX = 200
FILTERS_MAX = 30
FILTER_VALUE_MAX = 5400
FILTER_KEY_MAX = 64
VIEWS_PER_OWNER = 200
SHARED_LIST_MAX = 50


class Invalid(ValueError):
    pass


def name_of(value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise Invalid("Give the view a name.")
    name = " ".join(value.split())
    if len(name) > NAME_MAX:
        raise Invalid(f"A view name is at most {NAME_MAX} characters.")
    return name


def path_of(value: Any, norm: Any) -> str:
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise Invalid("path must be text")
    path = norm(value)
    if len(path) > PATH_MAX:
        raise Invalid(f"A folder path is at most {PATH_MAX} characters.")
    return path


def _text(value: Any, label: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise Invalid(f"{label} must be text")
    text = value.strip()
    if len(text) > TEXT_MAX:
        raise Invalid(f"{label} is at most {TEXT_MAX} characters.")
    return text or None


def state_of(value: Any) -> dict[str, Any]:
    if value is None:
        value = {}
    if not isinstance(value, dict):
        raise Invalid("state must be an object")
    view = value.get("view") or "files"
    if view not in VIEW_KINDS:
        raise Invalid(f"state.view takes {', '.join(VIEW_KINDS)}")
    raw_filters = value.get("filters")
    if raw_filters is None:
        raw_filters = {}
    if not isinstance(raw_filters, dict):
        raise Invalid("state.filters must be an object")
    if len(raw_filters) > FILTERS_MAX:
        raise Invalid(f"Save at most {FILTERS_MAX} filters.")
    filters: dict[str, str] = {}
    for key, item in raw_filters.items():
        if not isinstance(key, str) or not key.strip() or len(key) > FILTER_KEY_MAX:
            raise Invalid("A filter name is 1 to 64 characters.")
        if item is None:
            continue
        if isinstance(item, bool) or not isinstance(item, (str, int, float)):
            raise Invalid("A filter value must be text")
        text = str(item)
        if len(text) > FILTER_VALUE_MAX:
            raise Invalid(f"A filter value is at most {FILTER_VALUE_MAX} characters.")
        filters[key.strip()] = text
    return {
        "view": view,
        "sheet": _text(value.get("sheet"), "state.sheet"),
        "q": _text(value.get("q"), "state.q"),
        "filters": filters,
    }


def shared_of(value: Any) -> bool:
    if value is None:
        return False
    if not isinstance(value, bool):
        raise Invalid("shared must be true or false")
    return value


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def view_out(row: Any, user_id: str) -> dict[str, Any]:
    state = row.state if isinstance(row.state, dict) else {}
    try:
        state = state_of(state)
    except Invalid:
        state = state_of({})
    owner = getattr(row, "owner", None)
    return {
        "id": row.id,
        "name": row.name,
        "path": row.path,
        "state": state,
        "shared": bool(row.shared),
        "mine": str(row.ownerId) == str(user_id),
        "ownerName": getattr(owner, "name", None),
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
    }


def options_out(row: Any) -> dict[str, Any] | None:
    if row is None or not isinstance(row.options, dict):
        return None
    return dict(row.options)
