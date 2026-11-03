SECRET_USER_FIELDS: tuple[str, ...] = (
    "passwordHash",
    "twoFactorSecret",
    "twoFactorPendingSecret",
    "twoFactorPendingAt",
    "twoFactorRecoveryCodes",
    "twoFactorLastStep",
    "twoFactorFailedAttempts",
    "twoFactorFailureWindowStartedAt",
    "twoFactorLockedUntil",
    "twoFactorLockouts",
    "twoFactorRecoveryFailedAttempts",
    "twoFactorRecoveryWindowStartedAt",
    "twoFactorRecoveryLockedUntil",
    "twoFactorRecoveryLockouts",
    "firstPasswordCodeAttempts",
    "firstPasswordCodeWindowStartedAt",
)

HAS_PASSWORD_KEY = "hasPassword"


def public_user(payload: dict, user: object) -> dict:
    for field in SECRET_USER_FIELDS:
        payload.pop(field, None)
    payload[HAS_PASSWORD_KEY] = bool(getattr(user, "passwordHash", None))
    return payload
