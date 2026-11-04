from __future__ import annotations

KIND_LABELS: dict[str, str] = {
    "WORKSHOP_INTERVIEW": "Workshop interview",
    "MARKET_SURVEY": "Market survey",
}
KIND_TOKENS: frozenset[str] = frozenset(KIND_LABELS)
NOT_STATED_LABEL = "Kind not stated"

STAGE_KEYS: dict[str, dict[str, str]] = {
    "designer-portal": {
        "WORKSHOP_INTERVIEW": "EXISTING_PRODUCTS_BASELINE",
        "MARKET_SURVEY": "MARKET_SURVEY_CAPTURE",
    },
    "documentation-portal": {
        "WORKSHOP_INTERVIEW": "WORKSHOP_EXPORT_ANNEXURE",
        "MARKET_SURVEY": "WORKSHOP_EXPORT_ANNEXURE",
    },
}

SECTION_STATUS_LABELS: dict[str, str] = {
    "COMPLETED": "Marked complete",
    "NEEDS_REVIEW": "Needs review",
    "NEEDS_REDO": "Needs redo",
}

SECTION_STATUS_ALIASES: dict[str, str] = {"MARKED_COMPLETE": "COMPLETED"}


def normalise_section_status(value: str | None) -> str | None:
    if value is None or not str(value).strip():
        return None
    token = str(value).strip().upper()
    token = SECTION_STATUS_ALIASES.get(token, token)
    if token not in SECTION_STATUS_LABELS:
        raise ValueError(f"status must be one of {', '.join(SECTION_STATUS_LABELS)} or null")
    return token
SECTION_STATUSES: tuple[str, ...] = tuple(SECTION_STATUS_LABELS)


def is_kind(value: str | None) -> bool:
    return bool(value) and value in KIND_TOKENS


def label_for(value: str | None) -> str:
    if not value:
        return NOT_STATED_LABEL
    return KIND_LABELS.get(value, value)


def stage_key_for(value: str | None, portal: str) -> str | None:
    if not value:
        return None
    return STAGE_KEYS.get(portal, {}).get(value)


def coerce_kind(value: str | None) -> str | None:
    if value is None:
        return None
    token = value.strip().upper()
    if not token:
        return None
    if token not in KIND_TOKENS:
        allowed = ", ".join(sorted(KIND_TOKENS))
        raise ValueError(f"'{value}' is not a questionnaire kind. Use one of: {allowed}.")
    return token


def coerce_section_status(value: str | None) -> str:
    token = (value or "").strip().upper()
    token = SECTION_STATUS_ALIASES.get(token, token)
    if token not in SECTION_STATUS_LABELS:
        raise ValueError(f"'{value}' is not a section status. Use one of: {', '.join(SECTION_STATUSES)}.")
    return token
