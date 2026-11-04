from __future__ import annotations

from typing import Any

from app.core.roles import ROLE_RANK, is_master_admin, role_rank

PENDING_RECORD_DELEGATES: tuple[tuple[str, str], ...] = (
    ("artisan", "artisan"),
    ("workshop", "workshop"),
    ("product", "productdocumentation"),
    ("tool", "tooldocumentation"),
    ("process", "process"),
    ("questionnaire", "questionnaireinterview"),
)


def reviewable_creator_roles(reviewer: Any) -> list[str] | None:
    if is_master_admin(reviewer):
        return None
    rank = role_rank(reviewer)
    return [role for role, value in ROLE_RANK.items() if value < rank]


def pending_where(reviewer: Any) -> dict[str, Any] | None:
    roles = reviewable_creator_roles(reviewer)
    if roles is not None and not roles:
        return None
    where: dict[str, Any] = {"status": "PENDING"}
    if roles is not None:
        where["createdBy"] = {"is": {"role": {"in": roles}}}
    return where
