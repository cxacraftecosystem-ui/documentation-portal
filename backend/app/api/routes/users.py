import logging
import secrets
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.config import get_settings
from app.core.deps import (
    ROLE_LABELS,
    ROLE_RANK,
    get_current_user,
    invalidate_cached_user,
    is_admin,
    is_master_admin,
    require_admin,
    require_professor,
    role_rank,
)
from app.core.security import hash_password
from app.schemas.users import UserCreate, UserUpdate
from app.services import credential_links, email_outbox, mailer, signin_role
from app.services.access_roster import ensure_admitted
from app.core.user_fields import public_user
from app.services.two_factor import CLEAR_FAILURES
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains

router = APIRouter(prefix="/users", tags=["users"])
logger = logging.getLogger(__name__)

ALLOWED_ROLES = set(ROLE_RANK)


def serialize_user(user: Any) -> dict[str, Any]:
    return public_user(jsonable_encoder(user), user)


OWN_CREDENTIALS_DETAIL = (
    "You cannot set or reset your own password here. Change it from your own account (Change "
    "password), which asks for the password you use now."
)

NO_PASSWORD_TO_CHANGE_DETAIL = (
    "This account has no password to change. Set a temporary password or make a password link instead."
)

OWN_SESSIONS_DETAIL = (
    "Use Sign out on this device instead; your own sessions end when you change your password."
)

OWN_EMAIL_DETAIL = "Ask another administrator to change your email address."

MASTER_PEER_DETAIL = (
    "Master admin accounts can't change or remove each other. Ask them to change their own password, "
    "sessions or two-step verification from their own account."
)


def assert_not_another_master(actor: Any, target: Any) -> None:
    if is_master_admin(target) and getattr(target, "id", None) != getattr(actor, "id", None):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_PEER_DETAIL)


def assert_role(role: str | None, current_user: Any) -> None:
    if not role:
        return
    if role not in ALLOWED_ROLES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Invalid user role")
    if role == "MASTER_ADMIN" and not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the master admin can grant master admin")
    if ROLE_RANK[role] > role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign roles at or below your own tier",
        )


def assert_can_manage_target(current_user: Any, target_user: Any) -> None:
    if is_master_admin(current_user):
        if is_master_admin(target_user) and getattr(target_user, "id", None) != getattr(current_user, "id", None):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_PEER_DETAIL)
        return
    if role_rank(target_user) >= role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only manage users below your own tier",
        )


def is_master_email(email: str | None) -> bool:
    if not email:
        return False
    return email.lower() == get_settings().master_admin_email.lower()


def assert_not_demoting_master(target_user: Any, payload_role: str | None, current_user: Any) -> None:
    if not is_master_email(target_user.email):
        return
    if not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account is protected")
    if payload_role and payload_role != "MASTER_ADMIN":
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="The master admin's role can't be changed.")


@router.get("/directory")
async def user_directory(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
) -> list[dict[str, Any]]:
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    users = await db.user.find_many(where=where, order={"name": "asc"}, take=500)
    return [
        {"id": u.id, "name": u.name, "email": u.email, "role": str(getattr(u.role, "value", u.role))}
        for u in users
    ]


@router.get("")
async def list_users(
    current_user: Any = Depends(require_professor),
    search: str | None = None,
    role: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    assert_role(role, current_user)
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    if role:
        where["role"] = role
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    total = await db.user.count(where=where)
    users = await db.user.find_many(where=where, skip=skip, take=page_size, order={"createdAt": "desc"})
    return page_payload([serialize_user(user) for user in users], total, page, page_size)


@router.get("/{user_id}")
async def get_user(user_id: str, _: Any = Depends(require_professor)) -> dict[str, Any]:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return serialize_user(user)


EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"

_TEMPORARY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
TEMPORARY_PASSWORD_LENGTH = 16

ROLE_LISTS: dict[str, str] = {}


def generate_temporary_password() -> str:
    return "".join(secrets.choice(_TEMPORARY_ALPHABET) for _ in range(TEMPORARY_PASSWORD_LENGTH))


def assert_may_create_role(role: str | None, current_user: Any) -> None:
    if not role or role not in ALLOWED_ROLES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Invalid user role")
    if is_master_admin(current_user):
        return
    if ROLE_RANK[role] >= ROLE_RANK["ADMIN"]:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the master admin can add an administrator.",
        )
    if ROLE_RANK[role] >= role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only add people at a level below your own.",
        )


def duplicate_account_refusal(existing: Any) -> HTTPException:
    role = str(getattr(existing.role, "value", existing.role))
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail=(
            f"{existing.email} already has an account ({existing.name}, {ROLE_LABELS.get(role, role)}). "
            "Open it to change their role or access instead."
        ),
        headers={EXISTING_ACCOUNT_HEADER: str(existing.id)},
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_user(payload: UserCreate, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    user, extra = await create_account(current_user, payload)
    return {**serialize_user(user), **extra}


async def create_account(
    current_user: Any, payload: UserCreate, *, via: str = "created this account"
) -> tuple[Any, dict[str, Any]]:
    email = payload.email.lower()
    role = "MASTER_ADMIN" if is_master_email(email) else payload.role
    assert_may_create_role(role, current_user)
    existing = await db.user.find_unique(where={"email": email})
    if existing:
        raise duplicate_account_refusal(existing)

    mode = payload.passwordMode or "SET"
    temporary: str | None = None
    password: str | None
    if mode == "GENERATE":
        temporary = generate_temporary_password()
        password = temporary
    elif mode == "SET":
        password = payload.password
    else:
        password = None
    password_hash = hash_password(password) if password is not None else None
    is_master = role == "MASTER_ADMIN"
    now = datetime.now(UTC)
    data: dict[str, Any] = {
        "email": email,
        "name": payload.name,
        "passwordHash": password_hash,
        "passwordSetAt": now if password_hash else None,
        "mustChangePassword": bool(password_hash) and bool(payload.mustChangePassword),
        "role": role,
        "authProvider": "LOCAL",
        "canManageQuestionnaire": is_master or payload.canManageQuestionnaire,
        "canManageCrafts": is_master or payload.canManageCrafts,
        "canManageWorkshops": is_master or payload.canManageWorkshops,
        "canReview": is_master or payload.canReview,
        "canViewProvenance": is_master or payload.canViewProvenance,
        "canDownloadDataset": is_master or payload.canDownloadDataset,
    }
    try:
        async with db.tx() as tx:
            user = await tx.user.create(data=data)
            await ensure_admitted(
                user.email,
                granted_role=role,
                full_name=user.name,
                notes=f"Admitted automatically: an administrator {via}.",
                decided_by_id=current_user.id,
                joined_at=user.createdAt,
                client=tx,
            )
    except UniqueViolationError as exc:
        clash = await db.user.find_unique(where={"email": email})
        if clash is not None:
            raise duplicate_account_refusal(clash) from exc
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists") from exc
    invalidate_cached_user(user.id)

    extra: dict[str, Any] = {}
    if temporary is not None:
        extra["temporaryPassword"] = temporary
    if mode == "NONE" and payload.sendPasswordLink:
        try:
            issued = await credential_links.issue_link(
                user=user,
                purpose=credential_links.INVITE,
                issued_by_id=current_user.id,
                deliver_by=credential_links.EMAIL,
            )
            extra["passwordLink"] = {
                "id": issued.id,
                "expiresAt": issued.expiresAt,
                "purpose": issued.purpose,
                "deliveredBy": issued.deliveredBy,
            }
        except credential_links.DeliveryUnavailable:
            extra["passwordLinkError"] = (
                "The account was created, but the link could not be e-mailed. Make a password link "
                "from their account and pass it on yourself."
            )
        except credential_links.IssueThrottled:
            extra["passwordLinkError"] = (
                "The account was created, but several links were issued for it already. Wait an hour "
                "before making another."
            )
    role_lists = ROLE_LISTS.get(role)
    logger.warning(
        "AUDIT account created: user=%s role=%s by=%s passwordMode=%s mustChangePassword=%s "
        "rosterAdded=True roleListsAdded=%s linkEmailed=%s",
        user.id,
        role,
        current_user.id,
        mode,
        bool(data["mustChangePassword"]),
        role_lists or "none",
        "passwordLink" in extra,
    )
    return user, extra


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    current_user: Any = Depends(require_professor),
) -> dict[str, Any]:
    assert_role(payload.role, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if not is_admin(current_user):
        extra_fields = set(data) - {"role"}
        if extra_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Professors can only change a user's role",
            )
    if "email" in data:
        data["email"] = data["email"].lower()
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if user.id == current_user.id:
        if "password" in data or "mustChangePassword" in data:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
        if "email" in data and data["email"] != (user.email or "").lower():
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_EMAIL_DETAIL)
        data.pop("email", None)
        privileged_fields = set(data) - {"name"}
        if privileged_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You cannot change your own role or privileges",
            )
    else:
        assert_can_manage_target(current_user, user)
        if "password" in data or "mustChangePassword" in data:
            assert_not_another_master(current_user, user)

    ends_sessions = False
    if "password" in data:
        data["passwordHash"] = hash_password(data.pop("password"))
        data.setdefault("mustChangePassword", True)
        ends_sessions = True
    if data.get("mustChangePassword") is True:
        if "passwordHash" not in data and user.passwordHash is None:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=NO_PASSWORD_TO_CHANGE_DETAIL)
        if not user.mustChangePassword:
            ends_sessions = True
    assert_not_demoting_master(user, data.get("role"), current_user)
    if "email" in data and data["email"] != user.email:
        if is_master_email(data["email"]) and not is_master_admin(current_user):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the master admin can assign the master admin email",
            )
        clash = await db.user.find_unique(where={"email": data["email"]})
        if clash and clash.id != user_id:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    if "email" in data and is_master_email(data["email"]):
        data["role"] = "MASTER_ADMIN"
    if data.get("role") == "MASTER_ADMIN":
        data["canManageQuestionnaire"] = True
        data["canManageCrafts"] = True
        data["canManageWorkshops"] = True
        data["canReview"] = True
        data["canViewProvenance"] = True
        data["canDownloadDataset"] = True
    previous_email = user.email
    now = datetime.now(UTC)
    if "passwordHash" in data:
        data["passwordSetAt"] = now
    if ends_sessions:
        data["sessionsValidFrom"] = now
    promoted = "role" in data and ROLE_RANK.get(str(data["role"]), 0) > role_rank(user)
    role_before = user.role
    updated = await db.user.update(where={"id": user_id}, data=data)
    if "role" in data:
        signin_role.audit_role_change(
            user_id=user_id, actor_id=current_user.id, before=role_before, after=updated.role, via="users"
        )
    if "email" in data and data["email"] != previous_email:
        await ensure_admitted(
            updated.email,
            granted_role=str(getattr(updated.role, "value", updated.role)),
            full_name=updated.name,
            notes=f"Address changed from {previous_email} by an administrator.",
            decided_by_id=current_user.id,
            joined_at=updated.createdAt,
        )
    invalidate_cached_user(user_id)
    links_withdrawn = await credential_links.revoke_outstanding(user_id) if promoted else 0
    if "passwordHash" in data or "mustChangePassword" in data or links_withdrawn:
        logger.warning(
            "AUDIT account credentials changed: user=%s by=%s passwordSet=%s mustChangePassword=%s "
            "sessionsEnded=%s linksWithdrawn=%s",
            user_id,
            current_user.id,
            "passwordHash" in data,
            bool(updated.mustChangePassword),
            ends_sessions,
            links_withdrawn,
        )
    if "passwordHash" in data:
        await email_outbox.notify_password_event(updated, mailer.PASSWORD_CHANGED, when=now)
        if previous_email and previous_email.lower() != (updated.email or "").lower():
            await email_outbox.notify_password_event(
                updated, mailer.PASSWORD_CHANGED, when=now, to_address=previous_email
            )
    return serialize_user(updated)


@router.post("/{user_id}/sign-out-everywhere")
async def sign_out_everywhere(user_id: str, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if user.id == current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_SESSIONS_DETAIL)
    assert_can_manage_target(current_user, user)
    assert_not_another_master(current_user, user)
    updated = await db.user.update(where={"id": user_id}, data={"sessionsValidFrom": datetime.now(UTC)})
    invalidate_cached_user(user_id)
    logger.warning(
        "AUDIT signed out of every device by an administrator: user=%s by=%s", user_id, current_user.id
    )
    return serialize_user(updated or user)


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(user_id: str, current_user: Any = Depends(require_admin)) -> None:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if is_master_email(user.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account cannot be deleted")
    if user.id == current_user.id:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="You cannot delete your own account")
    assert_can_manage_target(current_user, user)
    await db.user.delete(where={"id": user_id})
    await db.accessroster.update_many(
        where={"email": user.email},
        data={
            "status": "SUSPENDED",
            "decidedAt": datetime.now(UTC),
            "decidedById": current_user.id,
        },
    )
    invalidate_cached_user(user_id)


@router.delete("/{user_id}/two-factor")
async def reset_two_factor(user_id: str, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if user.id == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Turn off your own two-step verification from your account screen.",
        )
    assert_can_manage_target(current_user, user)
    assert_not_another_master(current_user, user)
    if not user.twoFactorEnabled:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Two-step verification is already off for this person.",
        )
    updated = await db.user.update(
        where={"id": user_id},
        data={
            "twoFactorEnabled": False,
            "twoFactorSecret": None,
            "twoFactorPendingSecret": None,
            "twoFactorPendingAt": None,
            "twoFactorRecoveryCodes": [],
            "twoFactorLastStep": None,
            "twoFactorEnabledAt": None,
            **CLEAR_FAILURES,
            "sessionsValidFrom": datetime.now(UTC),
        },
    )
    invalidate_cached_user(user_id)
    logger.warning(
        "AUDIT two-factor reset by an administrator: user=%s (%s) by=%s (%s)",
        user.id,
        user.email,
        current_user.id,
        current_user.email,
    )
    return serialize_user(updated)
