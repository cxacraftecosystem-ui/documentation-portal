-- Passwords and sessions, ported from the Design Prototype Workshop (2026-10-16). Additive only: four
-- nullable/defaulted User columns, one EmailMessage column, one enum and two new tables. Nothing
-- existing is rewritten, and every account behaves exactly as before until one of the new routes
-- writes to it (a NULL sessionsValidFrom skips the session check entirely).

ALTER TABLE "User" ADD COLUMN "passwordSetAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "mustChangePassword" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "User" ADD COLUMN "firstLoginAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "sessionsValidFrom" TIMESTAMP(3);

ALTER TABLE "EmailMessage" ADD COLUMN "sealed" TEXT;

CREATE TYPE "CredentialLinkPurpose" AS ENUM ('INVITE', 'RESET');

CREATE TABLE "PasswordResetToken" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "purpose" "CredentialLinkPurpose" NOT NULL DEFAULT 'RESET',
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "usedAt" TIMESTAMP(3),
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "issuedById" TEXT,

    CONSTRAINT "PasswordResetToken_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "PasswordResetToken_tokenHash_key" ON "PasswordResetToken"("tokenHash");
CREATE INDEX "PasswordResetToken_userId_createdAt_idx" ON "PasswordResetToken"("userId", "createdAt");
CREATE INDEX "PasswordResetToken_issuedById_idx" ON "PasswordResetToken"("issuedById");

ALTER TABLE "PasswordResetToken" ADD CONSTRAINT "PasswordResetToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "PasswordResetToken" ADD CONSTRAINT "PasswordResetToken_issuedById_fkey" FOREIGN KEY ("issuedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "PasswordResetRequest" (
    "id" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "email" TEXT NOT NULL,
    "accountId" TEXT,
    "outcome" TEXT NOT NULL,
    "ipAddress" TEXT,
    "userAgent" TEXT,

    CONSTRAINT "PasswordResetRequest_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "PasswordResetRequest_email_createdAt_idx" ON "PasswordResetRequest"("email", "createdAt");
CREATE INDEX "PasswordResetRequest_accountId_idx" ON "PasswordResetRequest"("accountId");
CREATE INDEX "PasswordResetRequest_createdAt_idx" ON "PasswordResetRequest"("createdAt");
