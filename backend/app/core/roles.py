from decimal import Decimal, InvalidOperation
from typing import Any


def get_value(obj: Any, field: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(field)
    return getattr(obj, field, None)


def role_value(user: Any) -> str:
    role = get_value(user, "role")
    return str(getattr(role, "value", role))


ROLE_RANK: dict[str, int] = {
    "CROWDSOURCE_VOLUNTEER": 10,
    "FIELD_CONTRIBUTOR": 20,
    "RESEARCHER": 30,
    "PROFESSOR": 40,
    "ADMIN": 50,
    "MASTER_ADMIN": 60,
}

ROLE_LABELS: dict[str, str] = {
    "CROWDSOURCE_VOLUNTEER": "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR": "Field Contributor",
    "RESEARCHER": "Researcher",
    "PROFESSOR": "Professor",
    "ADMIN": "Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_rank(user_or_role: Any) -> int:
    role = user_or_role if isinstance(user_or_role, str) else role_value(user_or_role)
    return ROLE_RANK.get(str(role), 0)


def has_rank(user: Any, role: str) -> bool:
    return role_rank(user) >= ROLE_RANK[role]


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


CONTENT_ADMIN_ROLES = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})


def is_content_admin(user: Any) -> bool:
    return role_value(user) in CONTENT_ADMIN_ROLES


CONTENT_DELETE_REFUSAL = "Only an admin can delete this."


def can_manage_questionnaire(user: Any) -> bool:
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    return has_rank(user, "PROFESSOR")


def can_manage_workshops(user: Any) -> bool:
    return has_rank(user, "PROFESSOR")


def can_review_record(reviewer: Any, creator_role: Any) -> bool:
    if is_master_admin(reviewer):
        return True
    role = getattr(creator_role, "value", creator_role)
    if not role:
        role = "RESEARCHER"
    return role_rank(reviewer) > ROLE_RANK.get(str(role), ROLE_RANK["RESEARCHER"])


def can_edit_others_record(user: Any, creator_role: Any) -> bool:
    return has_rank(user, "PROFESSOR") and can_review_record(user, creator_role)


def can_access_review(user: Any) -> bool:
    return has_rank(user, "FIELD_CONTRIBUTOR") or bool(get_value(user, "canReview"))


def can_review(user: Any) -> bool:
    return can_access_review(user)


def can_download_dataset(user: Any) -> bool:
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canDownloadDataset"))


def can_create_records(user: Any) -> bool:
    return has_rank(user, "RESEARCHER")


def is_empty_value(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    if isinstance(value, (list, tuple, set, dict)):
        return len(value) == 0
    return False


def enum_or_raw(value: Any) -> Any:
    return getattr(value, "value", value)


def values_match(current_value: Any, next_value: Any) -> bool:
    current_value = enum_or_raw(current_value)
    next_value = enum_or_raw(next_value)
    if current_value == next_value:
        return True
    try:
        return Decimal(str(current_value)) == Decimal(str(next_value))
    except (InvalidOperation, ValueError):
        return str(current_value) == str(next_value)
