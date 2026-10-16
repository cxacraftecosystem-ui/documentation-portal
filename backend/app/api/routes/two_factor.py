"""Two-step verification: the sign-in second step, and turning it on and off from the account screen.

The mechanics live in ``app/services/two_factor.py``; this module is the HTTP surface and the rules
about WHO may do WHAT:

* **Setup stores the new secret as PENDING**, encrypted, and changes nothing about how the account
  signs in. Only ``/enable`` with a working code for that secret switches it on, which proves the
  authenticator app and the server agree before the account depends on them. A second ``/setup``
  replaces an abandoned pending secret.
* **A setup that is left is DISCARDED.** ``POST /setup/cancel`` clears the pending secret (idempotent,
  and it never touches an enabled secret); the client calls it when the person cancels, navigates
  away, closes the page or leaves the app. A pending secret older than ``two_factor.PENDING_TTL``
  (15 minutes) is ignored by ``/enable`` and cleared by the status read, so an account whose owner
  opened setup and walked away reads as Off, never On. ``GET`` reports ``pending`` for the one
  honest in-between state: "Setting up", which is still Off. Sign-in asks for a code ONLY when
  ``twoFactorEnabled`` is true, so a pending secret never changes how anybody signs in.
* **Turning it off and making new recovery codes both need a current code.** A session is not proof
  of presence: somebody at an unlocked laptop must not be able to remove the control that exists to
  stop them. Disabling accepts a recovery code too, for the person whose phone is gone.
* **Every failure reads one sentence** (``two_factor.WRONG_CODE_DETAIL``): wrong digits, a replayed
  code and a used recovery code are not told apart. The log line says which.
* **Wrong codes are counted against the account** (``two_factor.check_second_factor``): verify,
  disable and recovery-codes share one budget with ``POST /datasets/token``, and a spent budget is a
  429 here until the lock lapses.
* Enabling, disabling and recovery-code use are written to the audit log (``AUDIT`` lines at WARNING,
  which reach journald on the box), as the managed-secret reveal is.
"""

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
    # 400, not 401: a 401 sends the web client off to refresh a perfectly good session.
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=two_factor.WRONG_CODE_DETAIL)


def _too_many() -> HTTPException:
    return HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL)


async def _second_factor_or_refuse(user: Any, code: str, *, allow_recovery: bool = True) -> None:
    """The account's lock, then the code, then the budget (``two_factor.check_second_factor``)."""
    try:
        accepted = await two_factor.check_second_factor(user, code, allow_recovery=allow_recovery)
    except two_factor.SecondFactorLocked as exc:
        raise _too_many() from exc
    if not accepted:
        raise _bad_code()


async def _fresh(user_id: str) -> Any:
    """The row as it is now: the identity cache may be up to five seconds behind a code just used."""
    user = await db.user.find_unique(where={"id": user_id})
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer exists")
    return user


@router.post("/verify", response_model=TokenResponse)
async def verify(payload: TwoFactorVerifyRequest) -> dict[str, Any]:
    """The second step of sign-in: a challenge token from ``POST /auth/login`` and a code.

    The attempt is claimed BEFORE the code is checked (see ``two_factor.claim_attempt``), so the
    fifth wrong code kills the challenge however the guesses are timed. The roster gate runs again
    because up to five minutes have passed since the first factor, which is long enough for an
    administrator to have suspended this person.
    """
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
        # Switched off by an administrator mid-challenge, or the account is gone: sign in again.
        await two_factor.finish_challenge(jti)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.CHALLENGE_DEAD_DETAIL)

    # A wrong code costs this challenge one of its five AND the account one of its budget, so
    # signing in again for a fresh challenge buys no more guesses than the budget allows.
    await _second_factor_or_refuse(user, payload.code)

    await two_factor.finish_challenge(jti)
    await assert_access_admitted(user.email, user)
    invalidate_cached_user(user.id)
    return await session_response(await _fresh(user.id))


@router.get("")
async def get_status(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    user = await _fresh(current_user.id)
    if two_factor.has_stale_pending(user):
        # An abandoned setup (older than PENDING_TTL, or left beside an enabled secret): forget it
        # so nothing half-made lingers. Conditional on the same ciphertext, so a /setup that raced
        # this read keeps its fresh secret.
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
    # The only response that carries the secret, and only while it is still pending.
    return {
        "secret": secret,
        "otpauth_uri": uri,
        "qr_svg": two_factor.qr_svg(uri),
        "expires_in_seconds": int(two_factor.PENDING_TTL.total_seconds()),
    }


@router.post("/setup/cancel", status_code=status.HTTP_204_NO_CONTENT)
async def cancel_setup(current_user: Any = Depends(get_current_user)) -> Response:
    """Forget a setup that was started and not confirmed. Idempotent: no pending secret is a 204
    too, so a client may call it on every way out of setup (Cancel, Back, closing the page, the app
    going to the background) without asking first. Never touches an ENABLED secret — the condition
    on ``twoFactorEnabled`` makes this a no-op for an account that is on."""
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
        # Abandoned: the QR on the screen is older than PENDING_TTL. Clear it and say to start again.
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
    # Conditional on the pending secret still being the one checked, so a second /setup that raced
    # this request cannot leave the account enabled with a secret nobody scanned.
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
            # The enrolment code counts as used: it cannot also sign somebody in.
            "twoFactorLastStep": step,
            "twoFactorEnabledAt": datetime.now(UTC),
        },
    )
    if not switched:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NO_PENDING)
    invalidate_cached_user(user.id)
    logger.warning("AUDIT two-factor enabled: user=%s (%s)", user.id, user.email)
    # The only time the plain codes exist outside the person's hands.
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
    """New codes replace ALL the old ones. Needs a six-digit code: a recovery code cannot mint ten
    more, or one leaked code would be a permanent key."""
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
    """Every two-step column back to "never set up". The recovery codes go WITH the secret: codes
    left behind would still open the account."""
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
