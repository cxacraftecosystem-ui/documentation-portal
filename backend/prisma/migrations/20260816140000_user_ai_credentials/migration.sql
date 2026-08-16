-- A designer's own provider key, encrypted at rest with the same Fernet as ManagedSecret.
--
-- PURELY ADDITIVE: one new table, two indexes, one foreign key. Nothing existing is altered, so a
-- deployment that applies this and then rolls the code back behaves exactly as it did before — the
-- table is simply never read. That matters because the feature it backs (a designer paying for
-- their own model calls) must never be able to take down the shared, app-level path everybody else
-- is using.
--
-- ON DELETE CASCADE on the owner is deliberate and is the only correct choice here: a deleted user
-- must not leave an encrypted credential behind that nobody can see, rotate, or account for. There
-- is no reveal endpoint for these rows, so an orphan would be unreachable ciphertext billed to a
-- person who no longer has an account.
CREATE TABLE "UserAiCredential" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "valueEnc" TEXT NOT NULL,
    "hint" TEXT,
    "model" TEXT,
    "lastStatus" TEXT NOT NULL DEFAULT 'UNKNOWN',
    "lastCheckedAt" TIMESTAMP(3),
    "lastError" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "UserAiCredential_pkey" PRIMARY KEY ("id")
);

-- One key per provider per person: a designer with two OpenAI keys has no way to say which one a
-- given job should use, so the write path upserts on this pair.
CREATE UNIQUE INDEX "UserAiCredential_userId_provider_key" ON "UserAiCredential"("userId", "provider");

CREATE INDEX "UserAiCredential_userId_idx" ON "UserAiCredential"("userId");

ALTER TABLE "UserAiCredential" ADD CONSTRAINT "UserAiCredential_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
