-- The per-account budget for wrong two-step verification codes (app/services/two_factor.py).
-- Additive only: every existing account starts with no failures and no lock.
ALTER TABLE "User" ADD COLUMN "twoFactorFailedAttempts" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "User" ADD COLUMN "twoFactorFailureWindowStartedAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "twoFactorLockedUntil" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "twoFactorLockouts" INTEGER NOT NULL DEFAULT 0;
