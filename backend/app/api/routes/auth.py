"""Sign-in: the password branch, the Google branch, and THE GATE they both pass through.

THE GATE IS NEW AND IT IS THE POINT OF THIS MODULE. Until it existed, this application refused
nobody: a wrong password was the only failure it could produce, and any Google address whose owner
Google would vouch for silently self-provisioned an account and walked out with a bearer token. Both
branches now converge on :func:`assert_access_admitted`, and an address that is not ACTIVE on the
``AccessRoster`` gets no token — including on the Google branch, where it also gets NO ACCOUNT.

THREE RULES THAT MUST SURVIVE ANY REWRITE OF THIS FILE:

1. **ONE GATE, AT THE CONFLUENCE.** Both branches are checked, and they are checked in one place
   each for one reason: two copies of an authorisation check are two chances to forget it, and the
   one that gets forgotten is always the one an attacker uses. The Google branch is checked inside
   :func:`login_with_google` rather than in :func:`login` because it has to be checked BEFORE the
   account is created — see that function.
2. **THE GATE RUNS AFTER THE CREDENTIAL IS PROVED, NEVER BEFORE.** Refusing an unproved caller with
   "your access request is awaiting approval" would hand anybody a free oracle for which addresses
   this institution knows, without them even holding the address. The ordering is also what bounds
   the unauthenticated write: see ``access_roster.record_access_request``.
3. **MASTER_ADMIN IS NEVER GATED.** Exempted both by the configured ``MASTER_ADMIN_EMAIL`` (which
   works before the account exists) and by a live ``MASTER_ADMIN`` role on the row (which covers a
   second master admin promoted through the users screen). This is the break-glass: the roster is a
   table only an administrator can edit, so an administrator locked out by it is an outage with no
   remedy inside the product.
"""

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import ROLE_RANK, get_current_user, invalidate_cached_user, role_rank
from app.core.security import create_access_token, verify_password
from app.schemas.auth import LoginRequest, TokenResponse
from app.services.access_roster import (
    ADMITTING_STATUS,
    INVALID_CREDENTIALS_DETAIL,
    gate_enforced,
    is_master_admin_email,
    mark_first_seen,
    normalise_email,
    record_access_request,
    refusal_detail,
    role_value,
    roster_row,
    status_value,
)

router = APIRouter(prefix="/auth", tags=["auth"])
logger = logging.getLogger(__name__)


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def enum_value(value: Any) -> str:
    return str(getattr(value, "value", value))


def role_for_email(email: str) -> str:
    settings = get_settings()
    if is_master_admin_email(email):
        return "MASTER_ADMIN"
    # New self-registered Google accounts start at the configured signup tier (lowest tier by
    # default) and are elevated by an admin — an unknown Google account no longer becomes a
    # full researcher automatically.
    #
    # Since the gate exists, reaching this line at all means an administrator has ALREADY admitted
    # the address; ``AccessRoster.grantedRole`` is what actually decides the tier, and this is only
    # the floor it cannot fall below. "All the users by default join as the lowest rung unless
    # promoted there itself" — the promotion happens on the roster screen.
    return settings.default_signup_role


def is_gate_exempt(email: Any, user: Any = None) -> bool:
    """Is this sign-in the break-glass? See rule 3 of the module docstring.

    TWO TESTS AND NOT ONE. The configured address covers the case where the account does not exist
    yet or has been deleted — the situation a break-glass has to survive, and the situation in which
    a role column cannot help because there is no row to read it from. The live ``MASTER_ADMIN``
    role covers a SECOND master admin, minted through ``POST /users`` by the first, whose address is
    not the one in the environment. Dropping either test leaves a way for this institution to lock
    itself out of its own repository with no in-product remedy.
    """
    if is_master_admin_email(email):
        return True
    return role_value(getattr(user, "role", None)) == "MASTER_ADMIN"


async def assert_access_admitted(email: Any, user: Any = None) -> None:
    """THE GATE. Raise the appropriate refusal unless this address is ACTIVE on the roster.

    CALLED ONLY AFTER THE CREDENTIAL HAS BEEN PROVED — a verified Google ID token, or a password
    that matched an existing hash. Rule 2 of the module docstring says why, and
    ``access_roster.record_access_request`` says what depends on it.

    THE TWO ANSWERS ARE DELIBERATELY DIFFERENT, and this is the ruling the whole feature turns on.
    A person waiting on an administrator is TOLD they are waiting; they do not get "invalid email or
    password". That widens account enumeration — a caller who holds an address can learn whether
    this institution knows it — and the trade was made knowingly, because the alternative is
    somebody spending a week resetting a password that was never wrong and telephoning the wrong
    desk about it. What must NOT widen past that: the refusal names no person, no role, no other
    account, and never says whether a password was ever set. Read
    ``access_roster.ACCESS_PENDING_DETAIL`` and its neighbours before touching the wording.

    THE BODY IS ``{"detail": {"code": ..., "message": ...}}`` RATHER THAN A BARE STRING because both
    clients already understand that shape — ``frontend/lib/api.ts::describeApiDetail`` and
    ``android/…/FieldRepository.kt::detailMessage`` each pull ``detail.message`` out of an object —
    so the human sentence still renders everywhere, while ``code`` gives a client something stable
    to branch on that is not a string comparison against English prose.
    """
    if not gate_enforced():
        return
    if is_gate_exempt(email, user):
        return

    row = await roster_row(email)
    if row is not None and status_value(row.status) == ADMITTING_STATUS:
        return

    # Read the status BEFORE recording, because recording deliberately does not change it: a
    # REJECTED person's re-attempt stays REJECTED and must be answered as such, not as pending.
    # A brand-new address is answered as PENDING because that is what the write below makes it.
    refused_status = status_value(row.status) if row is not None else "PENDING"
    await record_access_request(email)
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail={
            "code": f"ACCESS_{refused_status}",
            "message": refusal_detail(refused_status),
        },
    )


def verify_google_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    if not settings.google_client_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Google OAuth is not configured on this server",
        )
    last_error: ValueError | None = None
    for client_id in settings.google_client_ids:
        try:
            return google_id_token.verify_oauth2_token(
                token,
                google_requests.Request(),
                client_id,
            )
        except ValueError as exc:
            last_error = exc
            logger.info("Google token rejected for configured audience %s: %s", client_id, exc)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Google ID token") from last_error


async def login_with_google(token: str) -> Any:
    """Google sign-in — GATED, which is the single biggest behavioural change in this feature.

    WHAT THIS USED TO DO. It took any Google ID token this server could verify, and if the address
    was unknown it CREATED a ``User`` row and returned a token. That is how every account in this
    application other than the seeded admins came to exist. It also meant that the set of people who
    could sign in was "everyone with a Google account", which is roughly three billion people, and
    the institution had no say in it whatsoever.

    WHAT IT DOES NOW. A verified Google identity that is not ACTIVE on the roster gets **no account
    and no token**. It becomes a PENDING roster entry and reads the same sentence a refused password
    sign-in reads. The product owner's ruling is explicit: "google signin should have the caveat of
    being in the allowlist as well, that should also go into pending if not already in the list."

    THE GATE IS CHECKED HERE, BEFORE ANY WRITE, AND NOT IN :func:`login` WITH THE OTHER BRANCH.
    That is the one place this file's "one gate at the confluence" rule bends, and it has to:
    ``login`` only sees the user AFTER this function has run, and by then the damage — an account
    provisioned for a stranger, or an existing account's name and avatar overwritten from an
    unadmitted token — is already committed. The refusal has to land before the first write.
    :func:`login` still calls :func:`assert_access_admitted` afterwards for BOTH branches; on this
    path that second call is a cheap, deliberate belt-and-braces re-check rather than the real gate.

    THE ORDER OF THE THREE CHECKS BELOW IS LOAD-BEARING: verify the token, THEN require a verified
    email, THEN consult the roster. Consulting the roster first would answer "is this address known
    to the institution" to anybody who typed an address, with no token at all.
    """
    id_info = verify_google_token(token)

    if not id_info.get("email") or not id_info.get("email_verified"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google account email is not verified",
        )

    email = normalise_email(id_info["email"])
    if not email:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google account email is not verified",
        )

    settings = get_settings()
    role = role_for_email(email)
    name = settings.master_admin_name if role == "MASTER_ADMIN" else id_info.get("name") or email.split("@")[0]
    avatar_url = id_info.get("picture")

    existing = await db.user.find_unique(where={"email": email})

    # THE GATE, before the account is created and before the existing one is touched. ``existing``
    # is passed so that a second master admin — MASTER_ADMIN by role rather than by the configured
    # address — is exempt here exactly as they are on the password branch.
    await assert_access_admitted(email, existing)

    # Only reachable for an admitted address (or the break-glass). ``grantedRole`` is the tier an
    # administrator chose when they approved this person, and it is applied ONLY when provisioning
    # or when it would be a PROMOTION — never as a demotion. A roster row that said
    # CROWDSOURCE_VOLUNTEER because that was the default at approval time must not knock an admin,
    # promoted afterwards through the users screen, back down to the bottom of the ladder on their
    # next sign-in; that demotion would be invisible in the login response and would silently
    # change who may review whose work.
    admitted = await roster_row(email)
    granted = role_value(getattr(admitted, "grantedRole", None))

    if existing:
        data = {"name": name, "avatarUrl": avatar_url, "authProvider": "GOOGLE"}
        if role == "MASTER_ADMIN":
            data["role"] = "MASTER_ADMIN"
            data["canManageQuestionnaire"] = True
        elif granted and ROLE_RANK.get(granted, 0) > role_rank(existing):
            data["role"] = granted
        updated = await db.user.update(
            where={"email": email},
            data=data,
        )
        # Sign-in is a WRITE to the identity the rest of the app authorises against: it can rename
        # the account and, for the master-admin email, hand it MASTER_ADMIN and
        # canManageQuestionnaire. Drop the cached row so the token minted below is never validated
        # against the pre-login one. (Keyed by id, which is why the update's return value is used.)
        invalidate_cached_user(updated.id)
        return updated

    if granted and ROLE_RANK.get(granted, 0) > ROLE_RANK.get(role, 0):
        # A brand-new account for an address an administrator admitted at a tier above the signup
        # floor. Without this the person an admin deliberately approved as, say, a RESEARCHER would
        # arrive at a home screen with a volunteer's powers and no way to create anything, and the
        # admin would have to go and promote them a second time — the manual step the roster's
        # ``grantedRole`` column exists to remove.
        role = granted

    created = await db.user.create(
        data={
            "email": email,
            "name": name,
            "avatarUrl": avatar_url,
            "authProvider": "GOOGLE",
            "role": role,
            "canManageQuestionnaire": role == "MASTER_ADMIN",
        }
    )
    invalidate_cached_user(created.id)
    return created


@router.post("/login", response_model=TokenResponse)
async def login(payload: LoginRequest) -> dict[str, Any]:
    """The one sign-in endpoint. Two branches in, one gate, one token mint.

    ``POST /auth/google`` is a two-line wrapper around this, so there is genuinely nowhere else a
    token is minted in this application. That is why the gate can live here and be complete.
    """
    if payload.googleIdToken:
        user = await login_with_google(payload.googleIdToken)
    else:
        email = normalise_email(payload.email)
        user = await db.user.find_unique(where={"email": email}) if email else None
        if not user or not verify_password(payload.password or "", user.passwordHash):
            # UNCHANGED, AND DELIBERATELY SO. This is the wrong-credential answer, and it is the one
            # place the gate does NOT speak: an unknown address with a guessed password has proved
            # nothing, so it is told nothing and — crucially — it writes NOTHING to the roster. If
            # this branch recorded an access request, the administrators' pending queue would be
            # writable at line rate by any anonymous script, and the notification this whole feature
            # exists to deliver would drown in it. See
            # ``access_roster.record_access_request``: proof of ownership comes first.
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=INVALID_CREDENTIALS_DETAIL,
            )

    # THE GATE, ON THE LAST LINE BEFORE THE TOKEN. Both branches reach it and neither can avoid it,
    # which is the property that matters: this is the only place in the application a token is
    # minted, so a check here is a check everywhere.
    #
    # For the PASSWORD branch this is the only gate, and it is deliberately below the credential
    # check — the password matched an existing hash, so ownership of the address is proved and the
    # gate may now speak in full. After the grandfathering migration it can only refuse somebody an
    # administrator deliberately suspended or rejected; every account that existed when the gate
    # shipped went onto the roster as ACTIVE.
    #
    # For the GOOGLE branch it is the SECOND check, on purpose. ``login_with_google`` has to gate
    # before it writes, so it does its own; this one guarantees that no future edit to that function
    # can produce a user object which reaches the mint ungated. It costs one indexed lookup on a
    # path a person takes once a week.
    await assert_access_admitted(user.email, user)

    # Only after a successful admission: "first seen" means the first time somebody actually got IN,
    # so a suspended person's refused attempt must not consume the stamp an administrator reads as
    # "this approval was taken up".
    await mark_first_seen(user.email)

    access_token = create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
    )
    return {"accessToken": access_token, "tokenType": "bearer", "user": serialize_user(user)}


@router.post("/google", response_model=TokenResponse)
async def google_login(payload: LoginRequest) -> dict[str, Any]:
    if not payload.googleIdToken:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Missing Google ID token")
    return await login(payload)


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)
