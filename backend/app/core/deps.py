from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.db import db
from app.core.security import decode_access_token

bearer_scheme = HTTPBearer(auto_error=False)


def get_value(obj: Any, field: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(field)
    return getattr(obj, field, None)


def role_value(user: Any) -> str:
    role = get_value(user, "role")
    return str(getattr(role, "value", role))


# The six-tier role ladder, strictly ordered. Higher rank inherits every power of the ranks below
# it; grantable capability booleans can additionally lift a specific power for a lower tier.
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
    """Rank of a user object or a bare role string; unknown roles rank lowest."""
    role = user_or_role if isinstance(user_or_role, str) else role_value(user_or_role)
    return ROLE_RANK.get(str(role), 0)


def has_rank(user: Any, role: str) -> bool:
    return role_rank(user) >= ROLE_RANK[role]


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


def can_manage_questionnaire(user: Any) -> bool:
    """Edit the questionnaire structure: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageCrafts"))


def can_manage_workshops(user: Any) -> bool:
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageWorkshops"))


def can_review_record(reviewer: Any, creator_role: Any) -> bool:
    """The peer-review hierarchy: the master admin reviews EVERYONE's work; everyone else may only
    review records whose creator ranks STRICTLY below them (admin reviews everyone beneath,
    professor reviews researchers and below, researcher reviews field contributors and volunteers,
    field contributor reviews volunteers). A record with no creator role on file is treated as a
    researcher's work."""
    if is_master_admin(reviewer):
        return True
    role = getattr(creator_role, "value", creator_role)
    if not role:
        role = "RESEARCHER"
    return role_rank(reviewer) > ROLE_RANK.get(str(role), ROLE_RANK["RESEARCHER"])


def can_access_review(user: Any) -> bool:
    """May open the review queue: Field Contributor and above (everyone who has someone beneath
    them on the ladder), or an explicit review grant. Which specific records they may act on is
    decided per record by can_review_record."""
    return has_rank(user, "FIELD_CONTRIBUTOR") or bool(get_value(user, "canReview"))


def can_review(user: Any) -> bool:
    """Back-compat alias for can_access_review — page-level review access, not per-record."""
    return can_access_review(user)


def can_download_dataset(user: Any) -> bool:
    """May download the entire dataset: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canDownloadDataset"))


def can_create_records(user: Any) -> bool:
    """May create core records (artisans, products, tools, processes): Field Contributor and above.
    Crowdsource Volunteers contribute media, questionnaire answers, and comments only."""
    return has_rank(user, "FIELD_CONTRIBUTOR")


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    if not credentials:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    try:
        payload = decode_access_token(credentials.credentials)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject")

    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer exists")
    return user


async def require_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user


async def require_master_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Master admin access required",
        )
    return current_user


async def require_reviewer(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_access_review(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Review access required")
    return current_user


async def require_professor(current_user: Any = Depends(get_current_user)) -> Any:
    if not has_rank(current_user, "PROFESSOR"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Professor access or above required",
        )
    return current_user


async def require_dataset_downloader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required. Ask an admin to grant it.",
        )
    return current_user


async def require_record_creator(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_create_records(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Creating records requires Field Contributor access or above. Volunteers can add "
                "media, questionnaire answers, and comments to existing records."
            ),
        )
    return current_user


async def require_questionnaire_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_questionnaire(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Questionnaire management access required",
        )
    return current_user


async def require_craft_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_crafts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Craft creation access required. Ask the master admin to grant it.",
        )
    return current_user


async def require_workshop_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_workshops(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Workshop creation access required. Ask the master admin to grant it.",
        )
    return current_user


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


def assert_can_contribute_fields(record: Any, user: Any, data: dict[str, Any], owner_field: str = "createdById") -> None:
    if is_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return

    # A populated field is locked to non-privileged editors whether they try to CHANGE it or CLEAR it.
    # (The earlier version skipped an incoming empty value, which let anyone blank out a populated field.)
    locked_fields = [
        field
        for field, next_value in data.items()
        if not is_empty_value(get_value(record, field))
        and (is_empty_value(next_value) or not values_match(get_value(record, field), next_value))
    ]
    if locked_fields:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change or clear populated field(s): {', '.join(sorted(locked_fields))}",
        )


def assert_can_contribute_relation(record: Any, user: Any, populated: bool, field_name: str, owner_field: str = "createdById") -> None:
    if is_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return
    if populated:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change populated relation: {field_name}",
        )


def assert_owner_or_admin(record: Any, user: Any, owner_field: str = "createdById") -> None:
    if is_admin(user):
        return
    if get_value(record, owner_field) != get_value(user, "id"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only access records you created",
        )


def assert_admin_or_owner(record: Any, user: Any, owner_field: str = "createdById") -> None:
    assert_owner_or_admin(record, user, owner_field)


def assert_can_delete(user: Any) -> None:
    if not is_admin(user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required to delete records")
