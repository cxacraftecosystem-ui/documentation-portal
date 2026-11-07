import hashlib
import hmac
import logging
import secrets
from datetime import UTC, datetime
from typing import Any

import jwt
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request, Response, status
from fastapi.encoders import jsonable_encoder

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    get_current_user,
    invalidate_cached_user,
    is_master_admin,
    password_credential,
    require_admin,
    role_rank,
)
from app.core.security import (
    create_access_token,
    hash_password,
    verify_password,
    verify_password_or_decoy_off_loop,
)
from app.core.user_fields import public_user
from app.scale.rate_limit import (
    account_credential_attempt,
    account_credential_refund,
    address_credential_attempt,
    client_address,
    forgot_password_attempt,
    source_credential_attempt,
    source_credential_refund,
)
from app.schemas.auth import (
    ChangePasswordRequest,
    ForgotPasswordRequest,
    IssuePasswordLinkRequest,
    LoginRequest,
    PasswordLinkCheckRequest,
    SetPasswordRequest,
    TokenResponse,
    TwoFactorChallengeResponse,
)
from app.services import (
    credential_links,
    email_outbox,
    mailer,
    oidc_sign_in,
    password_reset,
    signin_role,
    two_factor,
)
from app.services.access_roster import (
    ADMITTING_STATUS,
    INVALID_CREDENTIALS_DETAIL,
    account_barred,
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


ACCOUNT_THROTTLED_DETAIL = (
    "Too many failed sign-in attempts for this account. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)


def serialize_user(user: Any) -> dict[str, Any]:
    return public_user(jsonable_encoder(user), user)


def enum_value(value: Any) -> str:
    return str(getattr(value, "value", value))


def role_for_email(email: str) -> str:
    settings = get_settings()
    if is_master_admin_email(email):
        return "MASTER_ADMIN"
    return settings.default_signup_role


def is_gate_exempt(email: Any, user: Any = None) -> bool:
    if is_master_admin_email(email):
        return True
    return role_value(getattr(user, "role", None)) == "MASTER_ADMIN"


async def assert_access_admitted(email: Any, user: Any = None) -> None:
    if not gate_enforced():
        return
    if is_gate_exempt(email, user):
        return

    row = await roster_row(email)
    if row is not None and status_value(row.status) == ADMITTING_STATUS:
        return

    refused_status = status_value(row.status) if row is not None else "PENDING"
    await record_access_request(email)
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail={
            "code": f"ACCESS_{refused_status}",
            "message": refusal_detail(refused_status),
        },
    )


async def assert_not_barred(user: Any) -> None:
    if not await account_barred(user):
        return
    row = await roster_row(getattr(user, "email", None))
    refused_status = status_value(row.status) if row is not None else "SUSPENDED"
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail={"code": f"ACCESS_{refused_status}", "message": refusal_detail(refused_status)},
    )


async def password_matches(scope: Any, user: Any, email: str, password: str) -> bool:
    if user is not None:
        allowed, _retry = account_credential_attempt(user.id)
    else:
        allowed, _retry = address_credential_attempt(email)
    if allowed:
        allowed, _retry = source_credential_attempt(scope)
    if not allowed:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=ACCOUNT_THROTTLED_DETAIL)
    matched = await verify_password_or_decoy_off_loop(password, user.passwordHash if user else None)
    if matched and user is not None:
        account_credential_refund(user.id)
        source_credential_refund(scope)
        return True
    return False


def verify_google_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    if not settings.google_client_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Google sign-in isn't available. Use your email and password.",
        )
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token as google_id_token

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
    return await _sign_in_verified_address(
        email, display_name=id_info.get("name"), avatar_url=id_info.get("picture"), provider="GOOGLE"
    )


def _oidc_refused_detail(label: str) -> str:
    return f"Signing in with {label} did not complete. Try again, or use another way to sign in."


def _oidc_unverified_detail(label: str) -> str:
    return (
        f"{label} has not confirmed the email address on this account, so it cannot be used to sign "
        f"in here. Confirm the address with {label}, or use another way to sign in."
    )


async def login_with_oidc(payload: LoginRequest) -> Any:
    name = payload.oidcProvider or ""
    label = oidc_sign_in.label_of(name)
    if oidc_sign_in.provider(name) is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Signing in with {label} is not available here. Use another way to sign in.",
        )
    if not oidc_sign_in.acceptable_redirect_uri(payload.oidcRedirectUri or ""):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_refused_detail(label))
    try:
        proved = await oidc_sign_in.prove(
            name,
            code=payload.oidcCode or "",
            code_verifier=payload.oidcCodeVerifier or "",
            redirect_uri=payload.oidcRedirectUri or "",
            raw_nonce=payload.oidcNonce or "",
        )
    except oidc_sign_in.EmailNotVerified as exc:
        logger.info("auth: %s sign-in refused (%s)", name, exc.reason)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_unverified_detail(label)
        ) from exc
    except oidc_sign_in.SignInRefused as exc:
        logger.info("auth: %s sign-in refused (%s)", name, exc.reason)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_refused_detail(label)) from exc
    email = normalise_email(proved.email)
    if not email:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_unverified_detail(label))
    return await _sign_in_verified_address(
        email, display_name=proved.name, avatar_url=None, provider=proved.provider
    )


async def _sign_in_verified_address(
    email: str, *, display_name: str | None, avatar_url: str | None, provider: str
) -> Any:
    settings = get_settings()
    role = role_for_email(email)
    name = settings.master_admin_name if role == "MASTER_ADMIN" else display_name or email.split("@")[0]

    existing = await db.user.find_unique(where={"email": email})

    await assert_access_admitted(email, existing)

    admitted = await roster_row(email)
    granted = role_value(getattr(admitted, "grantedRole", None))

    if existing:
        data: dict[str, Any] = {"name": name}
        if provider == "GOOGLE":
            data.update({"avatarUrl": avatar_url, "authProvider": "GOOGLE"})
        elif str(getattr(existing.authProvider, "value", existing.authProvider) or "").upper() == "LOCAL":
            data["authProvider"] = provider
        if role == "MASTER_ADMIN":
            data["role"] = "MASTER_ADMIN"
            data["canManageQuestionnaire"] = True
        updated = await db.user.update(
            where={"email": email},
            data=data,
        )
        invalidate_cached_user(updated.id)
        return updated

    if role != "MASTER_ADMIN":
        role = signin_role.role_for_new_account(role, granted)

    created = await db.user.create(
        data={
            "email": email,
            "name": name,
            "avatarUrl": avatar_url,
            "authProvider": provider,
            "role": role,
            "canManageQuestionnaire": role == "MASTER_ADMIN",
        }
    )
    invalidate_cached_user(created.id)
    return created


@router.post("/login", response_model=TokenResponse | TwoFactorChallengeResponse)
async def login(payload: LoginRequest, request: Request) -> dict[str, Any]:
    if payload.googleIdToken:
        user = await login_with_google(payload.googleIdToken)
    elif payload.has_oidc_login:
        user = await login_with_oidc(payload)
    else:
        email = normalise_email(payload.email)
        user = await db.user.find_unique(where={"email": email}) if email else None
        if not await password_matches(request.scope, user, email, payload.password or ""):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=INVALID_CREDENTIALS_DETAIL,
            )

    await assert_access_admitted(user.email, user)

    if getattr(user, "twoFactorEnabled", False):
        if two_factor.fully_locked(user):
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL
            )
        return {
            "two_factor_required": True,
            "challenge_token": await two_factor.issue_challenge(user),
        }

    return await session_response(user)


async def session_response(user: Any) -> dict[str, Any]:
    await mark_first_seen(user.email)
    now = datetime.now(UTC)
    stamps: dict[str, Any] = {"lastLoginAt": now}
    if getattr(user, "firstLoginAt", None) is None:
        stamps["firstLoginAt"] = now
    user = await db.user.update(where={"id": user.id}, data=stamps) or user
    invalidate_cached_user(user.id)
    return {"accessToken": _session_token(user), "tokenType": "bearer", "user": serialize_user(user)}


def _session_token(user: Any) -> str:
    return create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
        credential=password_credential(user),
    )


@router.post("/google", response_model=TokenResponse | TwoFactorChallengeResponse)
async def google_login(payload: LoginRequest, request: Request) -> dict[str, Any]:
    if not payload.googleIdToken:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Missing Google ID token")
    return await login(payload, request)


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)



def _auth_provider(account: Any) -> str:
    provider = getattr(account, "authProvider", None)
    return str(getattr(provider, "value", provider) or "").upper()


def _is_google_only(account: Any) -> bool:
    return getattr(account, "passwordHash", None) is None and _auth_provider(account) == "GOOGLE"


GOOGLE_ONLY_LINK_DETAIL = (
    "This account signs in with Google and has no password, so there is no password link to issue."
)

OWN_CREDENTIALS_DETAIL = (
    "You cannot set or reset your own password here. Change it from your own account (Change "
    "password), which asks for the password you use now."
)


def assert_may_reset_credentials(actor: Any, target: Any) -> None:
    from app.api.routes.users import assert_can_manage_target, assert_not_another_master

    if getattr(target, "id", None) == getattr(actor, "id", None):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
    assert_can_manage_target(actor, target)
    assert_not_another_master(actor, target)


def issuer_still_manages(issuer: Any, target: Any) -> bool:
    if issuer is None or is_master_admin(issuer):
        return True
    return role_rank(target) < role_rank(issuer)


def _link_payload(issued: credential_links.DeliveredLink) -> dict[str, Any]:
    return {
        "id": issued.id,
        "link": issued.link,
        "expiresAt": issued.expiresAt,
        "purpose": issued.purpose,
        "deliveredBy": issued.deliveredBy,
    }


@router.post("/password-links", status_code=status.HTTP_201_CREATED)
async def issue_password_link(
    payload: IssuePasswordLinkRequest, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    target = await db.user.find_unique(where={"id": payload.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    assert_may_reset_credentials(current_user, target)
    if _is_google_only(target):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=GOOGLE_ONLY_LINK_DETAIL)
    try:
        issued = await credential_links.issue_link(
            user=target,
            purpose=credential_links.purpose_for(target),
            issued_by_id=current_user.id,
            deliver_by=payload.delivery,
        )
    except credential_links.DeliveryUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="This link cannot be sent by e-mail. Copy it instead and pass it on yourself.",
        ) from exc
    except credential_links.IssueThrottled as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=(
                "Several password links have already been issued for this account. Wait an hour, "
                "or ask the person to use one of them."
            ),
            headers={"retry-after": str(exc.retry_after_minutes * 60)},
        ) from exc
    logger.warning(
        "AUDIT password link issued: purpose=%s link=%s user=%s by=%s delivery=%s",
        issued.purpose,
        issued.id,
        target.id,
        current_user.id,
        issued.deliveredBy,
    )
    return _link_payload(issued)


@router.post("/password-links/{link_id}/revoke")
async def revoke_password_link(link_id: str, current_user: Any = Depends(require_admin)) -> dict[str, bool]:
    row = await credential_links.link_row(link_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    target = await db.user.find_unique(where={"id": row.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    assert_may_reset_credentials(current_user, target)
    if not await credential_links.revoke_link(link_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    logger.warning("AUDIT password link withdrawn: link=%s user=%s by=%s", link_id, target.id, current_user.id)
    return {"ok": True}


@router.get("/set-password")
async def check_set_password_token(token: str = "") -> dict[str, Any]:
    return await _link_check(token)


@router.post("/set-password/check")
async def check_set_password_token_in_body(payload: PasswordLinkCheckRequest) -> dict[str, Any]:
    return await _link_check(payload.token)


async def _link_check(raw: str | None) -> dict[str, Any]:
    verdict = await _link_verdict(raw)
    return {"valid": verdict.ok, "reason": verdict.reason, "purpose": verdict.purpose}


async def _link_verdict(raw: str | None) -> credential_links.TokenVerdict:
    verdict = await credential_links.describe_token(raw)
    if not verdict.ok or verdict.issued_by_id is None:
        return verdict
    issuer = await db.user.find_unique(where={"id": verdict.issued_by_id})
    target = await db.user.find_unique(where={"id": verdict.user_id})
    if target is not None and (not issuer_still_manages(issuer, target) or await account_barred(issuer)):
        logger.info(
            "auth: refused password link for account %s: its issuer %s can no longer manage it",
            verdict.user_id,
            verdict.issued_by_id,
        )
        return credential_links.TokenVerdict(False, credential_links.REVOKED)
    return verdict


SAME_PASSWORD_DETAIL = (
    "Choose a password different from the one you have now. A password somebody else gave you has "
    "to be replaced, not typed again."
)

CURRENT_PASSWORD_WRONG_DETAIL = "Current password is incorrect"

_SET_PASSWORD_REFUSALS = {
    credential_links.MISSING: "This link is incomplete. Open the whole link you were sent.",
    credential_links.MALFORMED: "This is not a link this site issued. Ask the administrator for another.",
    credential_links.EXPIRED: "This link has expired. Ask for a new one.",
    credential_links.REVOKED: "This link was withdrawn. Ask the administrator for a new one.",
    credential_links.SPENT: "This link has already been used. Sign in with the password you set.",
    credential_links.UNKNOWN_ACCOUNT: "This link no longer points at an account.",
}


@router.post("/set-password")
async def set_password(payload: SetPasswordRequest) -> dict[str, bool]:
    verdict = await _link_verdict(payload.token)
    if not verdict.ok:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_SET_PASSWORD_REFUSALS.get(verdict.reason or "", "This password link is not valid."),
        )
    account = await db.user.find_unique(where={"id": verdict.user_id})
    if (
        account is not None
        and account.mustChangePassword
        and verify_password(payload.password, account.passwordHash)
    ):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=SAME_PASSWORD_DETAIL)
    password_hash = hash_password(payload.password)
    now = datetime.now(UTC)
    await db.user.update(
        where={"id": verdict.user_id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": now,
            "mustChangePassword": False,
            "sessionsValidFrom": now,
        },
    )
    invalidate_cached_user(verdict.user_id)
    await credential_links.mark_used(payload.token)
    logger.warning("AUDIT password set through a %s link: user=%s", verdict.purpose, verdict.user_id)
    if account is not None:
        kind = mailer.PASSWORD_ADDED if account.passwordHash is None else mailer.PASSWORD_CHANGED
        await email_outbox.notify_password_event(account, kind, when=now)
    return {"ok": True}


@router.post("/forgot-password")
async def forgot_password(
    payload: ForgotPasswordRequest,
    request: Request,
    response: Response,
    background: BackgroundTasks,
) -> dict[str, str]:
    allowed, retry_after = forgot_password_attempt(request.scope)
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=password_reset.TOO_MANY_REQUESTS_DETAIL,
            headers={"Retry-After": str(max(1, int(retry_after + 0.999)))},
        )
    address = client_address(request.scope)
    background.add_task(
        password_reset.request_password_reset,
        str(payload.email),
        ip_address=address.removeprefix("ip:"),
        user_agent=request.headers.get("user-agent"),
    )
    response.headers["Cache-Control"] = "no-store"
    return {"message": password_reset.FORGOT_PASSWORD_MESSAGE}


SESSION_TOKEN_HEADER = "X-Session-Token"

PROVIDER_PROOF_MAX_AGE_SECONDS = 300
PROVIDER_PROOF_CLOCK_SKEW_SECONDS = 60

CONFIRM_SIGN_IN_HEADER = "X-Confirm-Sign-In"

TWO_FACTOR_REQUIRED_HEADER = "X-Two-Factor-Required"

SIGN_IN_PROOF_HEADER = "X-Sign-In-Proof"
_SIGN_IN_PROOF_TYPE = "first-password-proof"
_spent_sign_in_proofs: dict[str, float] = {}


def _sign_in_proof_key() -> bytes:
    return hmac.new(get_settings().jwt_secret.encode("utf-8"), _SIGN_IN_PROOF_TYPE.encode(), hashlib.sha256).digest()


def _mint_sign_in_proof(account_id: str, provider: str, expires_at: float) -> str:
    claims = {
        "sub": account_id,
        "typ": _SIGN_IN_PROOF_TYPE,
        "prv": provider,
        "jti": secrets.token_urlsafe(16),
        "exp": int(expires_at),
    }
    return jwt.encode(claims, _sign_in_proof_key(), algorithm="HS256")


def _redeem_sign_in_proof(token: str, account_id: str) -> tuple[str, float] | None:
    try:
        claims = jwt.decode(token, _sign_in_proof_key(), algorithms=["HS256"], options={"require": ["exp", "sub", "jti"]})
    except jwt.PyJWTError:
        return None
    if claims.get("typ") != _SIGN_IN_PROOF_TYPE or claims.get("sub") != account_id:
        return None
    now = datetime.now(UTC).timestamp()
    for spent, until in list(_spent_sign_in_proofs.items()):
        if until < now:
            _spent_sign_in_proofs.pop(spent, None)
    jti = str(claims["jti"])
    if jti in _spent_sign_in_proofs:
        return None
    _spent_sign_in_proofs[jti] = float(claims["exp"])
    provider = claims.get("prv")
    return (provider if provider in _PROVIDER_LABELS else "GOOGLE"), float(claims["exp"])

TWO_FACTOR_CODE_NEEDED_DETAIL = "Enter the code from your authenticator app to set a password."

_PROVIDER_LABELS = {"GOOGLE": "Google", "MICROSOFT": "Microsoft", "YAHOO": "Yahoo"}


def _proof_provider(account: Any) -> str:
    provider = _auth_provider(account)
    return provider if provider in _PROVIDER_LABELS else "GOOGLE"


def confirm_sign_in_detail(provider: str) -> str:
    label = _PROVIDER_LABELS.get(provider, "Google")
    return f"To set a password, first confirm it's you: sign in with {label} again, then save within 5 minutes."


def proof_refused_detail(provider: str, email: str) -> str:
    label = _PROVIDER_LABELS.get(provider, "Google")
    return f"That {label} sign-in didn't match this account or has expired. Sign in with {label} again as {email}, then save."


@router.post("/change-password")
async def change_password(
    payload: ChangePasswordRequest,
    response: Response,
    current_user: Any = Depends(get_current_user),
) -> dict[str, bool]:
    await assert_not_barred(current_user)
    if current_user.passwordHash is None:
        return await _set_first_password(payload, response, current_user)
    allowed, _retry = account_credential_attempt(current_user.id)
    if not allowed:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=ACCOUNT_THROTTLED_DETAIL)
    if not verify_password(payload.currentPassword, current_user.passwordHash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=CURRENT_PASSWORD_WRONG_DETAIL)
    account_credential_refund(current_user.id)
    if verify_password(payload.newPassword, current_user.passwordHash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=SAME_PASSWORD_DETAIL)
    updated = await _write_own_password(current_user, payload.newPassword)
    logger.warning("AUDIT password changed by the account itself: user=%s", current_user.id)
    response.headers[SESSION_TOKEN_HEADER] = _session_token(updated)
    await email_outbox.notify_password_event(updated, mailer.PASSWORD_CHANGED)
    return {"ok": True}


async def _write_own_password(current_user: Any, new_password: str) -> Any:
    password_hash = hash_password(new_password)
    now = datetime.now(UTC).replace(microsecond=0)
    updated = await db.user.update(
        where={"id": current_user.id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": now,
            "mustChangePassword": False,
            "sessionsValidFrom": now,
        },
    )
    invalidate_cached_user(current_user.id)
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="This account no longer exists. Sign in again."
        )
    return updated


async def _provider_proof(payload: ChangePasswordRequest, account: Any) -> tuple[str, float]:
    wanted = _proof_provider(account)
    email = normalise_email(getattr(account, "email", "")) or ""

    def refuse(provider: str, detail: str | None = None) -> HTTPException:
        return HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=detail or proof_refused_detail(provider, email),
            headers={CONFIRM_SIGN_IN_HEADER: wanted},
        )

    proved_email: str | None = None
    subject: str | None = None
    issued_at: float | None = None
    if payload.providerProof:
        carried = _redeem_sign_in_proof(payload.providerProof, getattr(account, "id", ""))
        if carried is None:
            raise refuse(wanted)
        return carried
    if payload.googleIdToken:
        used = "GOOGLE"
        try:
            id_info = verify_google_token(payload.googleIdToken)
        except HTTPException as exc:
            logger.info("auth: first-password Google proof refused (%s)", exc.status_code)
            raise refuse(used) from exc
        if id_info.get("email_verified"):
            proved_email = normalise_email(id_info.get("email"))
        raw_sub, raw_iat = id_info.get("sub"), id_info.get("iat")
        subject = raw_sub if isinstance(raw_sub, str) and raw_sub else None
        if isinstance(raw_iat, int | float) and not isinstance(raw_iat, bool):
            issued_at = float(raw_iat)
    elif payload.has_oidc_proof:
        used = payload.oidcProvider or wanted
        if oidc_sign_in.provider(used) is None or not oidc_sign_in.acceptable_redirect_uri(
            payload.oidcRedirectUri or ""
        ):
            raise refuse(used)
        try:
            proved = await oidc_sign_in.prove(
                used,
                code=payload.oidcCode or "",
                code_verifier=payload.oidcCodeVerifier or "",
                redirect_uri=payload.oidcRedirectUri or "",
                raw_nonce=payload.oidcNonce or "",
            )
        except (oidc_sign_in.SignInRefused, oidc_sign_in.EmailNotVerified) as exc:
            logger.info("auth: first-password %s proof refused (%s)", used, getattr(exc, "reason", "refused"))
            raise refuse(used) from exc
        proved_email = normalise_email(proved.email)
        subject, issued_at = proved.subject, proved.issued_at
    else:
        raise refuse(wanted, confirm_sign_in_detail(wanted))

    now = datetime.now(UTC).timestamp()
    fresh = (
        issued_at is not None
        and now - PROVIDER_PROOF_MAX_AGE_SECONDS <= issued_at <= now + PROVIDER_PROOF_CLOCK_SKEW_SECONDS
    )
    if not (proved_email and email and proved_email == email and subject and fresh):
        logger.info(
            "auth: first-password proof refused for user %s (address %s, subject %s, fresh %s)",
            getattr(account, "id", None),
            "matched" if proved_email and proved_email == email else "different",
            "present" if subject else "missing",
            fresh,
        )
        raise refuse(used)
    return used, (issued_at or 0.0) + PROVIDER_PROOF_MAX_AGE_SECONDS


def _refuse_code(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail, headers={TWO_FACTOR_REQUIRED_HEADER: "1"})


async def _first_password_code_allowance(account: Any, code: str) -> Any:
    if not code:
        raise _refuse_code(TWO_FACTOR_CODE_NEEDED_DETAIL)
    fresh = await db.user.find_unique(where={"id": account.id}) or account
    too_many = HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL)
    if two_factor.is_locked(fresh, two_factor.budget_for(code)):
        raise too_many
    if not await two_factor.claim_first_password_check(account.id):
        raise too_many
    return fresh


async def _set_first_password(
    payload: ChangePasswordRequest,
    response: Response,
    current_user: Any,
) -> dict[str, bool]:
    two_step = bool(getattr(current_user, "twoFactorEnabled", False))
    code = (payload.twoFactorCode or "").strip()
    fresh = await _first_password_code_allowance(current_user, code) if two_step else current_user
    try:
        provider, proof_expires_at = await _provider_proof(payload, current_user)
    except HTTPException:
        if two_step:
            await two_factor.refund_first_password_check(current_user.id)
        raise
    if two_step:
        if not await two_factor.consume_any_code(fresh, code):
            logger.info("auth: first-password two-step code refused for user %s", current_user.id)
            refused = _refuse_code(two_factor.WRONG_CODE_DETAIL)
            refused.headers[SIGN_IN_PROOF_HEADER] = _mint_sign_in_proof(current_user.id, provider, proof_expires_at)
            raise refused
        await two_factor.refund_first_password_check(current_user.id)
    updated = await _write_own_password(current_user, payload.newPassword)
    logger.warning("AUDIT first password set by the account itself: user=%s", current_user.id)
    response.headers[SESSION_TOKEN_HEADER] = _session_token(updated)
    await email_outbox.notify_password_event(updated, mailer.PASSWORD_ADDED)
    return {"ok": True}
