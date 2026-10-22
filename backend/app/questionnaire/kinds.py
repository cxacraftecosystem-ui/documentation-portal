"""The questionnaire vocabularies both portals print and validate: form KINDS and the admin SECTION
OVERRIDE. Mirrored, value for value, by ``shared/questionnaire-kinds.json`` and
``shared/questionnaire-enums.json`` (``tests/test_questionnaire_core.py`` holds them together).

KINDS (designer-portal's ``questionnaire_kinds``, now shared): ``WORKSHOP_INTERVIEW`` and
``MARKET_SURVEY``; ``None`` is "not stated" and never a token. A kind routes a form's material to a
report stage: designer-portal files a workshop interview under stage 6 and a market survey under
stage 8; Field Repository prints both in its workshop export annexure.

SECTION OVERRIDE (owner, amendment 2 of 22 Oct 2026, point 1): three states in both portals,
``COMPLETED`` (Marked complete), ``NEEDS_REVIEW`` (Needs review), ``NEEDS_REDO`` (Needs redo);
designer-portal's existing override rows keep their values.
"""

from __future__ import annotations

KIND_LABELS: dict[str, str] = {
    "WORKSHOP_INTERVIEW": "Workshop interview",
    "MARKET_SURVEY": "Market survey",
}
KIND_TOKENS: frozenset[str] = frozenset(KIND_LABELS)
NOT_STATED_LABEL = "Kind not stated"

#: Report stage per kind, per portal.
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

#: What older clients sent for "Marked complete" before the vocabulary was one end to end (U3's
#: report, 22 Oct 2026): accepted on write and stored as the canonical token, never sent back.
SECTION_STATUS_ALIASES: dict[str, str] = {"MARKED_COMPLETE": "COMPLETED"}


def normalise_section_status(value: str | None) -> str | None:
    """The canonical override token for ``value`` (an alias is mapped), or None when ``value`` is
    empty. Anything else is a ``ValueError`` naming the three states."""
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
    """The label for ``value``; an unknown token (a newer deployment's) shows as itself."""
    if not value:
        return NOT_STATED_LABEL
    return KIND_LABELS.get(value, value)


def stage_key_for(value: str | None, portal: str) -> str | None:
    if not value:
        return None
    return STAGE_KEYS.get(portal, {}).get(value)


def coerce_kind(value: str | None) -> str | None:
    """``None``/``""`` -> None; a token in any case -> the token; anything else -> ``ValueError``."""
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
