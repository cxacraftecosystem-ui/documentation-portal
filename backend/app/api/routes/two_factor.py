import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.api.routes.auth import assert_access_admitted, session_response
from app.core.db import db
from app.core.deps import get_current_user, invalidate_cached_user
from app.schemas.auth import TokenResponse, TwoFactorCodeRequest, TwoFactorVerifyRequest
from app.services import two_factor

router = APIRouter(prefix="/auth/two-factor", tags=["auth"])
logger = logging.getLogger(__name__)

ALREADY_ON = "Two-step verification is already on. Turn it off first to move it to a new device."
NOT_ON = "Two-step verification is off for this account."
NO_PENDING = "Start the setup again to get a new code to scan."
SETUP_EXPIRED = "That setup has expired. Start again to get a new code to scan."


def _bad_code() -> HTTPException:
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=two_factor.WRONG_CODE_DETAIL)


def _too_many() -> HTTPException:
    return HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL)


async def _second_factor_or_refuse(user: Any, code: str, *, allow_recovery: bool = True) -> None:
    try:
        accepted = await two_factor.check_second_factor(user, code, allow_recovery=allow_recovery)
    except two_factor.SecondFactorLocked as exc:
        raise _too_many() from exc
    if not accepted:
        raise _bad_code()


async def _fresh(user_id: str) -> Any:
    user = await db.user.find_unique(where={"id": user_id})
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer exists")
    return user


@router.post("/verify", response_model=TokenResponse)
async def verify(payload: TwoFactorVerifyRequest) -> dict[str, Any]:
    try:
        user_id, jti = two_factor.decode_challenge(payload.challenge_token)
        await two_factor.claim_attempt(jti)
    except two_factor.ChallengeRefused as exc:
        logger.info("two-factor: challenge refused (%s)", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.CHALLENGE_DEAD_DETAIL
        ) from exc

    user = await db.user.find_unique(where={"id": user_id})
    if user is None or not user.twoFactorEnabled:
        await two_factor.finish_challenge(jti)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.CHALLENGE_DEAD_DETAIL)

    await _second_factor_or_refuse(user, payload.code)

    await two_factor.finish_challenge(jti)
    await assert_access_admitted(user.email, user)
    invalidate_cached_user(user.id)
    return await session_response(await _fresh(user.id))


@router.get("")
async def get_status(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    user = await _fresh(current_user.id)
    if two_factor.has_stale_pending(user):
        await db.user.update_many(
            where={"id": user.id, "twoFactorPendingSecret": user.twoFactorPendingSecret},
            data=dict(two_factor.CLEAR_PENDING),
        )
        invalidate_cached_user(user.id)
        user = await _fresh(user.id)
    return two_factor.status_of(user)


@router.post("/setup")
async def setup(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    user = await _fresh(current_user.id)
    if user.twoFactorEnabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_ON)
    secret = two_factor.generate_totp_secret()
    await db.user.update(
        where={"id": user.id},
        data={
            "twoFactorPendingSecret": two_factor.encrypt_secret(secret),
            "twoFactorPendingAt": two_factor.pending_now(),
        },
    )
    invalidate_cached_user(user.id)
    uri = two_factor.totp_uri(secret, user.email)
    return {
        "secret": secret,
        "otpauth_uri": uri,
        "qr_svg": two_factor.qr_svg(uri),
        "expires_in_seconds": int(two_factor.PENDING_TTL.total_seconds()),
    }


@router.post("/setup/cancel", status_code=status.HTTP_204_NO_CONTENT)
async def cancel_setup(current_user: Any = Depends(get_current_user)) -> Response:
    await db.user.update_many(
        where={"id": current_user.id, "twoFactorEnabled": False},
        data=dict(two_factor.CLEAR_PENDING),
    )
    invalidate_cached_user(current_user.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/enable")
async def enable(payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    user = await _fresh(current_user.id)
    if user.twoFactorEnabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_ON)
    if user.twoFactorPendingSecret and not two_factor.pending_is_live(user):
        await db.user.update_many(
            where={"id": user.id, "twoFactorPendingSecret": user.twoFactorPendingSecret},
            data=dict(two_factor.CLEAR_PENDING),
        )
        invalidate_cached_user(user.id)
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=SETUP_EXPIRED)
    pending = two_factor.decrypt_secret(user.twoFactorPendingSecret)
    if not pending:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NO_PENDING)
    step = two_factor.match_totp_step(pending, payload.code)
    if step is None:
        raise _bad_code()

    codes = two_factor.generate_recovery_codes()
    hashes = await two_factor.hash_recovery_codes(codes)
    switched = await db.user.update_many(
        where={
            "id": user.id,
            "twoFactorEnabled": False,
            "twoFactorPendingSecret": user.twoFactorPendingSecret,
        },
        data={
            "twoFactorEnabled": True,
            "twoFactorSecret": user.twoFactorPendingSecret,
            "twoFactorPendingSecret": None,
            "twoFactorPendingAt": None,
            "twoFactorRecoveryCodes": hashes,
            "twoFactorLastStep": step,
            "twoFactorEnabledAt": datetime.now(UTC),
        },
    )
    if not switched:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NO_PENDING)
    invalidate_cached_user(user.id)
    logger.warning("AUDIT two-factor enabled: user=%s (%s)", user.id, user.email)
    return {"recovery_codes": codes}


@router.post("/disable", status_code=status.HTTP_204_NO_CONTENT)
async def disable(payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)) -> Response:
    user = await _fresh(current_user.id)
    if not user.twoFactorEnabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NOT_ON)
    await _second_factor_or_refuse(user, payload.code)
    await db.user.update(where={"id": user.id}, data=_switched_off())
    invalidate_cached_user(user.id)
    logger.warning("AUDIT two-factor disabled by its owner: user=%s (%s)", user.id, user.email)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/recovery-codes")
async def regenerate_recovery_codes(
    payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    user = await _fresh(current_user.id)
    if not user.twoFactorEnabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NOT_ON)
    await _second_factor_or_refuse(user, payload.code, allow_recovery=False)
    codes = two_factor.generate_recovery_codes()
    hashes = await two_factor.hash_recovery_codes(codes)
    await db.user.update(where={"id": user.id}, data={"twoFactorRecoveryCodes": hashes})
    invalidate_cached_user(user.id)
    logger.warning("AUDIT two-factor recovery codes replaced: user=%s (%s)", user.id, user.email)
    return {"recovery_codes": codes}


def _switched_off() -> dict[str, Any]:
    return {
        "twoFactorEnabled": False,
        "twoFactorSecret": None,
        "twoFactorPendingSecret": None,
        "twoFactorPendingAt": None,
        "twoFactorRecoveryCodes": [],
        "twoFactorLastStep": None,
        "twoFactorEnabledAt": None,
        **two_factor.CLEAR_FAILURES,
    }
