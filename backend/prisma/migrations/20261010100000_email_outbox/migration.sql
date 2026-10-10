-- E-mail: one new table (the outbox the queue worker drains, which is also the send log) and one
-- new opt-out column on UserPreference. ADDITIVE ONLY: the column carries a DEFAULT, so every
-- existing preference row reads "e-mail me" without an UPDATE, and the table starts empty. Written
-- by hand against schema.prisma; `IF NOT EXISTS` and the guarded constraint block let an interrupted
-- runner apply it again.

ALTER TABLE "UserPreference" ADD COLUMN IF NOT EXISTS "emailTaskUpdates" BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE IF NOT EXISTS "EmailMessage" (
    "id" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "toAddress" TEXT NOT NULL,
    "recipientId" TEXT,
    "subject" TEXT NOT NULL,
    "params" JSONB,
    "status" TEXT NOT NULL DEFAULT 'QUEUED',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "maxAttempts" INTEGER NOT NULL DEFAULT 5,
    "runAfter" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lockedAt" TIMESTAMP(3),
    "lockedBy" TEXT,
    "sentAt" TIMESTAMP(3),
    "providerMessageId" TEXT,
    "error" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "EmailMessage_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "EmailMessage_status_runAfter_createdAt_idx"
    ON "EmailMessage"("status", "runAfter", "createdAt");

CREATE INDEX IF NOT EXISTS "EmailMessage_recipientId_idx" ON "EmailMessage"("recipientId");

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'EmailMessage_recipientId_fkey'
    ) THEN
        ALTER TABLE "EmailMessage"
            ADD CONSTRAINT "EmailMessage_recipientId_fkey"
            FOREIGN KEY ("recipientId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
    END IF;
END $$;
