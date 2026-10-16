-- Recovery codes get their own failure budget and lock (app/services/two_factor.py), independent of
-- the six-digit one. Additive only: every account starts with no failures and no lock.
ALTER TABLE "User" ADD COLUMN "twoFactorRecoveryFailedAttempts" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "User" ADD COLUMN "twoFactorRecoveryWindowStartedAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "twoFactorRecoveryLockedUntil" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "twoFactorRecoveryLockouts" INTEGER NOT NULL DEFAULT 0;
