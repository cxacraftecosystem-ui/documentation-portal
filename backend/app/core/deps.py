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


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


def can_manage_questionnaire(user: Any) -> bool:
    return is_master_admin(user) or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    return is_admin(user) or bool(get_value(user, "canManageCrafts"))


def can_manage_workshops(user: Any) -> bool:
    return is_admin(user) or bool(get_value(user, "canManageWorkshops"))


def can_review(user: Any) -> bool:
    """May approve/reject submitted records: any admin, or a user granted the review permission."""
    return is_admin(user) or bool(get_value(user, "canReview"))


def can_download_dataset(user: Any) -> bool:
    """May download the entire dataset: any admin, or a user granted the dataset-download permission."""
    return is_admin(user) or bool(get_value(user, "canDownloadDataset"))


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
    if not can_review(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Review access required")
    return current_user


async def require_dataset_downloader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required. Ask an admin to grant it.",
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

    locked_fields = [
        field
        for field, next_value in data.items()
        if not is_empty_value(next_value)
        and not is_empty_value(get_value(record, field))
        and not values_match(get_value(record, field), next_value)
    ]
    if locked_fields:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change populated field(s): {', '.join(sorted(locked_fields))}",
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
