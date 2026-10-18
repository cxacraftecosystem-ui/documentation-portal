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
    """The row as the users screen reads it: no secrets, plus ``hasPassword`` (whether a link or a
    temporary password makes sense). ``mustChangePassword``, ``passwordSetAt`` and ``firstLoginAt``
    ride along, so the screen can say whether a temporary password is still live."""
    return public_user(jsonable_encoder(user), user)


#: A password, the password flag, or "sign out of every device" asked for on your own account. Your
#: own password is changed from the account screen, which asks for the current one.
OWN_CREDENTIALS_DETAIL = (
    "You cannot set or reset your own password here. Change it from your own account (Change "
    "password), which asks for the password you use now."
)

#: ``mustChangePassword: true`` on an account that has no password to change.
NO_PASSWORD_TO_CHANGE_DETAIL = (
    "This account has no password to change. Set a temporary password or make a password link instead."
)

#: "Sign out of every device" on your own account.
OWN_SESSIONS_DETAIL = (
    "Use Sign out on this device instead; your own sessions end when you change your password."
)

#: Your own email address, through the users screen (security review B3, 2026-10-16). The address is
#: what "Forgot your password?" mails a link to and what the allow-list admits, so moving it is the
#: one edit that would hand a stolen session the account for good. Another administrator changes it.
OWN_EMAIL_DETAIL = "Ask another administrator to change your email address."

#: A master admin's password, sessions or second factor, touched by ANOTHER master admin (security
#: review B4, 2026-10-16). The Design Prototype Workshop refuses it, and so does this portal: two
#: master admins are peers, and a peer who could reset the other's password, sign them out or turn off
#: their two-step could take their account. Since B4's leftover (same day) it covers the whole account:
#: a master admin does not demote, rename or delete another one either (assert_can_manage_target).
MASTER_PEER_DETAIL = (
    "Master admin accounts can't change or remove each other. Ask them to change their own password, "
    "sessions or two-step verification from their own account."
)


def assert_not_another_master(actor: Any, target: Any) -> None:
    """403 when ``target`` is a master admin other than ``actor`` — for the credential doors only:
    password links, a temporary password or the password flag, "sign out of every device", and
    turning off two-step. Since B4's leftover :func:`assert_can_manage_target` refuses the same pair
    for every action; this stays as the credential doors' explicit, second statement of it."""
    if is_master_admin(target) and getattr(target, "id", None) != getattr(actor, "id", None):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_PEER_DETAIL)


def assert_role(role: str | None, current_user: Any) -> None:
    """A user may assign roles at or below their own tier: admins promote to their level and
    beneath; only the master admin can mint MASTER_ADMIN."""
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
    """Nobody manages a peer, INCLUDING the master admin; everyone else manages strictly lower tiers.
    This blocks one admin from silently rewriting another admin's account.

    A MASTER ADMIN OVER ANOTHER MASTER ADMIN (security review B4, 2026-10-16), as the Design
    Prototype Workshop's ``account_provisioning.assert_can_manage_target`` has it: the only peer
    protection here used to key on ``MASTER_ADMIN_EMAIL`` (``assert_not_demoting_master`` and the
    delete route), so a second MASTER_ADMIN could be demoted or deleted by the first with one call.
    Protection follows the privilege now, not one address in the environment. Every caller branches
    on "your own account" before reaching here; the self-exception is written anyway."""
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
    """A minimal directory of all users (id, name, email, role) readable by ANY authenticated user.

    Powers the data-access "request access from a researcher" picker, where a non-admin needs to choose
    a colleague. Returns no privileges or password material — just enough to identify a person.
    """
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
    """One account, read-only, for the users screen's view of a person.

    THE SAME GATE AND THE SAME SHAPE AS THE LIST ABOVE, and nothing more: anybody who may page through
    ``GET /users`` already reads exactly this row there, so opening one person shows no field the list
    does not. Declared below ``/directory`` so that path is never read as an id.
    """
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return serialize_user(user)


#: The header a duplicate-address refusal carries: the id of the account that already has the
#: address, so the screen can offer "Open their account" instead of a second account. In
#: ``expose_headers`` in app/main.py.
EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"

#: Unambiguous characters for a generated temporary password: no 0/O, 1/l/I, so it can be read out
#: over a telephone or typed from a WhatsApp message without a second try.
_TEMPORARY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
TEMPORARY_PASSWORD_LENGTH = 16

#: The per-role list question (section F of the parity contract): this repository keeps NO per-role
#: list beside the access list (no designer roster, no officer or inspector list). The access list's
#: ``grantedRole`` is the whole record, so a new account is written to two tables, never more.
ROLE_LISTS: dict[str, str] = {}


def generate_temporary_password() -> str:
    """A strong temporary password: 16 characters from :data:`_TEMPORARY_ALPHABET` (about 92 bits)."""
    return "".join(secrets.choice(_TEMPORARY_ALPHABET) for _ in range(TEMPORARY_PASSWORD_LENGTH))


def assert_may_create_role(role: str | None, current_user: Any) -> None:
    """Who may ADD a person at *role*: strictly below the actor's own rank, and only the master admin
    adds an administrator (or another master admin). Stricter than :func:`assert_role`, which lets an
    administrator move an existing account up to their own tier on the role ladder."""
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
    """409 naming the account that already holds the address, with its id in a header."""
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
    """Add a person in ONE step: the account, admitted to the access list at the same role.

    ``passwordMode`` says how they will sign in: SET (the administrator types a password), GENERATE
    (the server makes a strong temporary one, answered ONCE as ``temporaryPassword``, never logged or
    stored in clear) or NONE (Google, Microsoft or Yahoo; with ``sendPasswordLink`` they are also
    e-mailed an invitation link to set a password, sent only after the account is committed; the
    answer carries ``passwordLink``, or ``passwordLinkError`` when mail could not take it, and the
    account stands either way). A password the administrator chose or the server made is temporary
    by default (``mustChangePassword``, forced for GENERATE).

    ONE TRANSACTION: the ``User`` row and its ``AccessRoster`` row are written together or not at all.
    This repository keeps no per-role list beside the access list (:data:`ROLE_LISTS`).

    Strictly below the actor's own rank; only the master admin adds an administrator. An address
    that already has an account is a 409 naming it, with ``X-Existing-Account-Id``.
    """
    user, extra = await create_account(current_user, payload)
    return {**serialize_user(user), **extra}


async def create_account(
    current_user: Any, payload: UserCreate, *, via: str = "created this account"
) -> tuple[Any, dict[str, Any]]:
    """Every rule ``POST /users`` applies, and the write. Returns the account and the one-time extras
    for the answer (``temporaryPassword``, ``passwordLink`` or ``passwordLinkError``). The access
    list's "with a password" door (``POST /access-roster``) calls this too, so the two cannot drift."""
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
        # Dataset download is grantable by any admin (the whole route is admin-gated), unlike the
        # master-admin-only grants above, so it needs no extra permission assertion.
        "canDownloadDataset": is_master or payload.canDownloadDataset,
    }
    # -- ONE TRANSACTION: THE ACCOUNT AND ITS ADMISSION -----------------------------------------
    #
    # AN ACCOUNT CREATED HERE MUST ALSO BE ADMITTED BY THE SIGN-IN GATE, or the admin has just made
    # an account that cannot sign in. Creating the account IS the decision to let this person in, and
    # the roster row carries the same tier with the admin as the decider. Both writes go through
    # ``tx`` (``db.tx()`` hands back a DIFFERENT client, and a write through the module ``db`` would
    # escape the transaction), so a failure in either leaves neither.
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
        # Another request took the address between the check above and this write.
        clash = await db.user.find_unique(where={"email": email})
        if clash is not None:
            raise duplicate_account_refusal(clash) from exc
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists") from exc
    # A brand-new cuid cannot already be cached, but every write to a User row invalidates without
    # exception: a rule with a documented exception is a rule the next person has to re-derive.
    invalidate_cached_user(user.id)

    extra: dict[str, Any] = {}
    if temporary is not None:
        # ONCE, in this answer only. Never logged, never stored in clear.
        extra["temporaryPassword"] = temporary
    if mode == "NONE" and payload.sendPasswordLink:
        # AFTER THE COMMIT: an e-mail about an account a rollback then erased would be a link to
        # nothing. A link that cannot be sent leaves the account standing and says so.
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
    # Ids, the role and the mode only: never a password, never a link.
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
        # Professors manage the ladder, not accounts: they may promote/demote people below them
        # (up to their own tier, per assert_role + assert_can_manage_target) but everything else —
        # identity, passwords, privilege flags — stays admin-only.
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
        # YOUR OWN PASSWORD IS NOT SET HERE (2026-10-16): this route asks for no current password, so
        # it would turn any open session into a permanent password. ``POST /auth/change-password``
        # asks for the current one and ends every other session.
        if "password" in data or "mustChangePassword" in data:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
        # YOUR OWN EMAIL ADDRESS IS NOT CHANGED HERE EITHER (security review B3): a session that could
        # move the address could then ask "Forgot your password?" for the new one and keep the account.
        if "email" in data and data["email"] != (user.email or "").lower():
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_EMAIL_DETAIL)
        data.pop("email", None)
        # Self-service is limited to the name; nobody edits their own role or privileges.
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

    # ── A PASSWORD AN ADMINISTRATOR TYPES FOR SOMEBODY ELSE IS TEMPORARY (ported 2026-10-16) ─────
    #
    # A shared secret, exactly as at account creation: the account is sent to the change-password
    # screen at its next sign-in unless ``mustChangePassword: false`` is sent beside it. And EVERY
    # SESSION OF THE ACCOUNT ENDS (``sessionsValidFrom``) — the usual reason for this is "help them
    # back in", and a session somebody else holds must not survive it. Raising the flag alone ends the
    # sessions too, so "at the next sign-in" is literal on a handset that would otherwise not re-read
    # ``/me`` until a cold start. The two-step factor is untouched: their next sign-in still asks.
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
    # Read BEFORE the update. ``user`` is the pre-update row today, but "the object I read earlier
    # still holds the old value" is an assumption about the ORM's identity map, not about this code,
    # and the thing that depends on it below is whether somebody can still sign in.
    previous_email = user.email
    # The timestamps last, after bcrypt and every awaited check, immediately before the write.
    now = datetime.now(UTC)
    if "passwordHash" in data:
        data["passwordSetAt"] = now
    if ends_sessions:
        data["sessionsValidFrom"] = now
    promoted = "role" in data and ROLE_RANK.get(str(data["role"]), 0) > role_rank(user)
    updated = await db.user.update(where={"id": user_id}, data=data)
    # THE AUDIT ENTRY FOR A CHANGE OF ROLE (the sign-in list rule, ``services/signin_role``): this is
    # the one door that changes an existing account's role, so it is the one that records it.
    if "role" in data:
        signin_role.audit_role_change(
            user_id=user_id, actor_id=current_user.id, before=user.role, after=updated.role, via="users"
        )
    if "email" in data and data["email"] != previous_email:
        # CHANGING SOMEBODY'S ADDRESS MOVES THEM PAST THEIR OWN ROSTER ROW. The gate is keyed by
        # email — that is what lets an admin admit a person before their account exists — so an
        # account renamed from a@x to b@x is refused at the next sign-in by a roster that has never
        # heard of b@x, and the admin who renamed them has no reason to connect the two. The new
        # address is admitted at the account's live tier, and the old row is left exactly as it is:
        # it is the record that a@x was once recognised, and silently deleting an entry from an
        # audit trail because a related row changed is not something this table does.
        await ensure_admitted(
            updated.email,
            granted_role=str(getattr(updated.role, "value", updated.role)),
            full_name=updated.name,
            notes=f"Address changed from {previous_email} by an administrator.",
            decided_by_id=current_user.id,
            joined_at=updated.createdAt,
        )
    # This is the promotion/demotion route: the cached identity now describes authority the user no
    # longer has (or has not been given yet), so it must not outlive the write by even one request.
    invalidate_cached_user(user_id)
    # A password link is its issuer's credential; a promotion must not carry it above the issuer's
    # reach. A new password makes every outstanding link dead through the fingerprint anyway.
    links_withdrawn = await credential_links.revoke_outstanding(user_id) if promoted else 0
    if "passwordHash" in data or "mustChangePassword" in data or links_withdrawn:
        # Field names only, never the password.
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
        # The owner hears of it at their own address (security review B2): an administrator set a
        # temporary password, and if they did not ask for that, they need to know today.
        await email_outbox.notify_password_event(updated, mailer.PASSWORD_CHANGED, when=now)
        # AND AT THE ADDRESS IT HAD (security review N4): an address and a password changed in one
        # request would otherwise tell only the new inbox, which is exactly the one a hijacker
        # would choose. The previous owner hears of it where they still read their mail.
        if previous_email and previous_email.lower() != (updated.email or "").lower():
            await email_outbox.notify_password_event(
                updated, mailer.PASSWORD_CHANGED, when=now, to_address=previous_email
            )
    return serialize_user(updated)


@router.post("/{user_id}/sign-out-everywhere")
async def sign_out_everywhere(user_id: str, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    """"Sign out of every device": end every session of somebody else's account, now.

    The same authority as resetting their password: an administrator, over an account ranked below
    them (the master admin over anyone), never their own (403: their own sessions end with Sign out,
    and all of them when they change their password). Stamps ``sessionsValidFrom``, which every
    bearer token minted before it fails against (``deps._user_from_bearer``), and drops the cached
    identity so the refusal lands on the very next request. Nothing else changes: the password, the
    two-step factor and the access list are as they were, so the person signs in again exactly as
    before. Answers the user row as it now is.
    """
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
    # DELETING THE ACCOUNT MUST ALSO CLOSE THE DOOR. The roster is keyed by EMAIL, not by user id,
    # so the ACTIVE row survives the account — and an ACTIVE row is an instruction to provision. Left
    # alone, the person an admin just deleted signs in with Google and gets a brand-new account at
    # the tier the roster still remembers, which reads to the admin as the delete button not working.
    # SUSPENDED rather than deleted, for the reason the whole roster is: the record that this address
    # was once recognised is what an audit asks for, and a deleted row goes straight back into the
    # pending queue on their next attempt.
    await db.accessroster.update_many(
        where={"email": user.email},
        data={
            "status": "SUSPENDED",
            "decidedAt": datetime.now(UTC),
            "decidedById": current_user.id,
        },
    )
    # A deleted account must stop authenticating immediately, not when a TTL happens to expire.
    invalidate_cached_user(user_id)


@router.delete("/{user_id}/two-factor")
async def reset_two_factor(user_id: str, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    """Turn two-step verification off for somebody who has lost the device it was on.

    The same authority as resetting their password or deleting the account: an admin, over a person
    ranked below them (the master admin over anyone). An administrator may REMOVE a second factor
    and may never READ one: nothing here returns the secret, and the recovery codes go with it.
    Refused for your own account, which is turned off from the account screen with a current code.
    EVERY SESSION OF THE ACCOUNT ENDS (``sessionsValidFrom``, 2026-10-16): the person asking for this
    has usually lost the phone, and a session opened on it must not outlive the factor it was opened
    with. Answers the user row as it now is.
    """
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
            # The lock and the failure count go too: the owner is about to start again.
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
    # The row as the Users screen now shows it, so the badge goes without a reload.
    return serialize_user(updated)
