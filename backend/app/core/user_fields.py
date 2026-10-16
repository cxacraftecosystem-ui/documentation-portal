"""The User columns that never leave the API, in ANY payload — the one list every encoder imports.

``routes/auth.py`` and ``routes/users.py`` strip them from the user they serialise, and
``services/records.public_encode`` strips them from every User embedded in a record (a media file's
``uploadedBy``, a workshop's ``createdBy``, a task's assignee, the data browser, the dataset export).
One tuple, so a column added to the two-step machinery cannot be hidden in one place and leaked in
another. ``tests/test_two_factor.py`` walks a fully populated User through ``public_encode`` against it.
"""

SECRET_USER_FIELDS: tuple[str, ...] = (
    "passwordHash",
    # Two-step verification (app/services/two_factor.py). The secrets are ciphertext and the codes are
    # hashes, and none of them is anybody's business but the server's; the counters and the lock say
    # how close somebody is to guessing their way in.
    "twoFactorSecret",
    "twoFactorPendingSecret",
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
)
