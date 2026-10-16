-- Two-step verification (TOTP). Additive only: every existing account starts with it off, and no
-- existing row changes. See app/services/two_factor.py.
ALTER TABLE "User" ADD COLUMN "twoFactorEnabled" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "User" ADD COLUMN "twoFactorSecret" TEXT;
ALTER TABLE "User" ADD COLUMN "twoFactorPendingSecret" TEXT;
ALTER TABLE "User" ADD COLUMN "twoFactorRecoveryCodes" TEXT[] DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "User" ADD COLUMN "twoFactorLastStep" INTEGER;
ALTER TABLE "User" ADD COLUMN "twoFactorEnabledAt" TIMESTAMP(3);

-- One row per sign-in waiting for its authenticator code; counts the attempts against it.
CREATE TABLE "TwoFactorChallenge" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "TwoFactorChallenge_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "TwoFactorChallenge_userId_idx" ON "TwoFactorChallenge"("userId");
CREATE INDEX "TwoFactorChallenge_expiresAt_idx" ON "TwoFactorChallenge"("expiresAt");

ALTER TABLE "TwoFactorChallenge" ADD CONSTRAINT "TwoFactorChallenge_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
