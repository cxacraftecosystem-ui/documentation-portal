import asyncio
import time
from collections import OrderedDict
from typing import Any

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.requests import HTTPConnection

from app.core.config import get_settings
from app.core.db import db
from app.core.security import CREDENTIAL_CLAIM, decode_access_token
from app.core.roles import (  # noqa: F401
    CONTENT_ADMIN_ROLES,
    CONTENT_DELETE_REFUSAL,
    ROLE_LABELS,
    ROLE_RANK,
    can_access_review,
    can_create_records,
    can_download_dataset,
    can_edit_others_record,
    can_manage_crafts,
    can_manage_questionnaire,
    can_manage_workshops,
    can_review,
    can_review_record,
    enum_or_raw,
    get_value,
    has_rank,
    is_admin,
    is_content_admin,
    is_empty_value,
    is_master_admin,
    role_rank,
    role_value,
    values_match,
)
from app.services import credential_links

bearer_scheme = HTTPBearer(auto_error=False)


async def may_edit_lower_ranked_record(user: Any, creator_id: str | None) -> bool:
    if not creator_id or not has_rank(user, "PROFESSOR"):
        return False
    creator = await db.user.find_unique(where={"id": creator_id})
    return can_edit_others_record(user, get_value(creator, "role") if creator else None)


_USER_CACHE_INFLIGHT_WAIT_SECONDS = 5.0

_user_cache: "OrderedDict[str, tuple[float, Any]]" = OrderedDict()
_user_cache_inflight: dict[str, tuple[Any, "asyncio.Future[Any]"]] = {}
_user_cache_epoch = 0


class _LeaderGone(Exception):
    pass


def invalidate_cached_user(user_id: str | None) -> None:
    global _user_cache_epoch
    _user_cache_epoch += 1
    if user_id:
        _user_cache.pop(user_id, None)


def clear_user_cache() -> None:
    global _user_cache_epoch
    _user_cache_epoch += 1
    _user_cache.clear()


def _cached_user(user_id: str) -> Any | None:
    entry = _user_cache.get(user_id)
    if entry is None:
        return None
    expires_at, user = entry
    if expires_at <= time.monotonic():
        del _user_cache[user_id]
        return None
    _user_cache.move_to_end(user_id)
    return user


def _store_cached_user(user_id: str, user: Any, ttl: float) -> None:
    _user_cache[user_id] = (time.monotonic() + ttl, user)
    _user_cache.move_to_end(user_id)
    max_entries = max(1, get_settings().auth_user_cache_max_entries)
    while len(_user_cache) > max_entries:
        _user_cache.popitem(last=False)


async def _load_user(user_id: str, ttl: float) -> Any:
    loop = asyncio.get_running_loop()
    pending = _user_cache_inflight.get(user_id)
    if pending is not None and pending[0] is loop:
        try:
            return await asyncio.wait_for(asyncio.shield(pending[1]), _USER_CACHE_INFLIGHT_WAIT_SECONDS)
        except (_LeaderGone, TimeoutError):
            pass

    future: "asyncio.Future[Any]" = loop.create_future()
    future.add_done_callback(lambda done: done.cancelled() or done.exception())
    _user_cache_inflight[user_id] = (loop, future)
    epoch_at_start = _user_cache_epoch
    try:
        user = await db.user.find_unique(where={"id": user_id})
    except asyncio.CancelledError:
        _user_cache_inflight.pop(user_id, None)
        if not future.done():
            future.set_exception(_LeaderGone())
        raise
    except BaseException as exc:
        _user_cache_inflight.pop(user_id, None)
        if not future.done():
            future.set_exception(exc)
        raise
    _user_cache_inflight.pop(user_id, None)
    if user is not None and epoch_at_start == _user_cache_epoch:
        _store_cached_user(user_id, user, ttl)
    if not future.done():
        future.set_result(user)
    return user


async def resolve_user(user_id: str) -> Any:
    settings = get_settings()
    if not settings.auth_user_cache_enabled:
        return await db.user.find_unique(where={"id": user_id})
    cached = _cached_user(user_id)
    if cached is not None:
        return cached
    return await _load_user(user_id, settings.auth_user_cache_ttl_seconds)


DATASET_READ_SCOPE = "dataset:read"

TOKEN_SCOPE_CLAIM = "scope"


SESSION_ENDED_DETAIL = "This session is no longer valid. Sign in again."


def password_credential(user: Any) -> str:
    return credential_links.credential_fingerprint(get_value(user, "passwordHash"))


def token_issued_at(payload: dict[str, Any]) -> float | None:
    issued_at = payload.get("iat")
    return float(issued_at) if isinstance(issued_at, int | float) else None


async def _user_from_bearer(
    credentials: HTTPAuthorizationCredentials | None,
    *,
    allowed_scopes: frozenset[str],
) -> Any:
    if not credentials:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    try:
        payload = decode_access_token(credentials.credentials)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    scope = payload.get(TOKEN_SCOPE_CLAIM)
    if scope is not None and str(scope) not in allowed_scopes:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This sign-in can't be used here. Please sign in again."
            ),
        )

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject")

    user = await resolve_user(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer exists")

    revoked_before = get_value(user, "sessionsValidFrom")
    if revoked_before is not None:
        issued_at = token_issued_at(payload)
        if issued_at is None or issued_at < revoked_before.timestamp():
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=SESSION_ENDED_DETAIL)

    if CREDENTIAL_CLAIM in payload:
        presented = payload[CREDENTIAL_CLAIM]
        if not (
            isinstance(presented, str)
            and presented.isascii()
            and credential_links.fingerprint_matches(get_value(user, "passwordHash"), presented)
        ):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=SESSION_ENDED_DETAIL)
    return user


PASSWORD_CHANGE_REQUIRED_HEADER = "X-Password-Change-Required"

PASSWORD_CHANGE_REQUIRED_DETAIL = "Choose a new password to continue."

PASSWORD_CHANGE_ALLOWED_ROUTES: frozenset[tuple[str, str]] = frozenset(
    {
        ("GET", "/api/auth/me"),
        ("POST", "/api/auth/change-password"),
        ("POST", "/api/auth/logout"),
        ("GET", "/api/app/release/latest"),
    }
)


def is_configured_master_admin(user: Any) -> bool:
    address = str(get_value(user, "email") or "").strip().lower()
    configured = (get_settings().master_admin_email or "").strip().lower()
    return bool(address) and bool(configured) and address == configured


def password_change_pending(user: Any) -> bool:
    if not get_value(user, "mustChangePassword"):
        return False
    if not get_value(user, "passwordHash"):
        return False
    return not is_configured_master_admin(user)


def _route_path(connection: HTTPConnection) -> str:
    path = str(connection.scope.get("path") or "")
    root = str(connection.scope.get("root_path") or "")
    if root and path.startswith(root) and path[len(root) : len(root) + 1] in ("/", ""):
        return path[len(root) :] or "/"
    return path


def refuse_while_password_change_pending(user: Any, connection: HTTPConnection) -> None:
    if not password_change_pending(user):
        return
    method = str(connection.scope.get("method") or "").upper()
    if (method, _route_path(connection)) in PASSWORD_CHANGE_ALLOWED_ROUTES:
        return
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=PASSWORD_CHANGE_REQUIRED_DETAIL,
        headers={PASSWORD_CHANGE_REQUIRED_HEADER: "1"},
    )


async def get_current_user(
    connection: HTTPConnection,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    user = await _user_from_bearer(credentials, allowed_scopes=frozenset())
    refuse_while_password_change_pending(user, connection)
    from app.services.app_versions import note_user

    note_user(connection, getattr(user, "id", None))
    return user


async def require_dataset_admin(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    user = await _user_from_bearer(credentials, allowed_scopes=frozenset({DATASET_READ_SCOPE}))
    if password_change_pending(user):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=PASSWORD_CHANGE_REQUIRED_DETAIL,
            headers={PASSWORD_CHANGE_REQUIRED_HEADER: "1"},
        )
    if not is_admin(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required for the bulk dataset API.",
        )
    from app.services.access_roster import account_barred

    if await account_barred(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This account's access has been withdrawn, so it can't download datasets. "
                "Ask an administrator to restore the account's access."
            ),
        )
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


def assert_can_create_records(user: Any) -> None:
    if not can_create_records(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Creating records requires Researcher access or above. Field contributors and "
                "volunteers can add media, questionnaire answers, and comments to existing records."
            ),
        )


async def require_record_creator(current_user: Any = Depends(get_current_user)) -> Any:
    assert_can_create_records(current_user)
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
            detail=(
                "Adding or editing a craft requires Professor access or above. Ask a professor or "
                "an admin to add it, then link your records to it."
            ),
        )
    return current_user


async def require_workshop_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_workshops(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Adding or editing a workshop requires Professor access or above. Ask a professor "
                "or an admin to set it up, then request access to submit records to it."
            ),
        )
    return current_user


def assert_can_contribute_fields(record: Any, user: Any, data: dict[str, Any], owner_field: str = "createdById") -> None:
    if is_content_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return

    locked_fields = [
        field
        for field, next_value in data.items()
        if not is_empty_value(get_value(record, field))
        and (is_empty_value(next_value) or not values_match(get_value(record, field), next_value))
    ]
    if locked_fields:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change or clear populated {'field' if len(locked_fields) == 1 else 'fields'}: {', '.join(sorted(locked_fields))}",
        )


def assert_can_contribute_relation(record: Any, user: Any, populated: bool, field_name: str, owner_field: str = "createdById") -> None:
    if is_content_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return
    if populated:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change populated relation: {field_name}",
        )


def assert_owner_or_admin(record: Any, user: Any, owner_field: str = "createdById") -> None:
    if is_content_admin(user):
        return
    if get_value(record, owner_field) != get_value(user, "id"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only access records you created",
        )


def assert_admin_or_owner(record: Any, user: Any, owner_field: str = "createdById") -> None:
    assert_owner_or_admin(record, user, owner_field)


def assert_can_delete(user: Any) -> None:
    if not is_content_admin(user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=CONTENT_DELETE_REFUSAL)


async def require_content_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_content_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user
