-- The weekly summary for reviewers and administrators (app/services/weekly_digest.py): the person's
-- opt-out, and one ledger row per person per reported week.
--
-- ADDITIVE ONLY: one column with a default (every existing account reads as "on", which is only ever
-- consulted for the accounts that get a summary), one new table and its indexes. Nothing existing is
-- rewritten, so it is safe on a live database and an older server simply never reads either.
-- `IF NOT EXISTS` throughout because the migration runner can be interrupted and re-run.
-- The same migration, by name, exists in the designer portal.

ALTER TABLE "UserPreference" ADD COLUMN IF NOT EXISTS "weeklyDigest" BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE IF NOT EXISTS "WeeklyDigest" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "weekStart" DATE NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "payload" JSONB,
    "notificationId" TEXT,
    "emailMessageId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "WeeklyDigest_pkey" PRIMARY KEY ("id")
);

-- THE IDEMPOTENCY KEY: one summary per person per week, however many workers or passes try.
CREATE UNIQUE INDEX IF NOT EXISTS "WeeklyDigest_userId_weekStart_key" ON "WeeklyDigest"("userId", "weekStart");

CREATE INDEX IF NOT EXISTS "WeeklyDigest_weekStart_status_idx" ON "WeeklyDigest"("weekStart", "status");

DO $$
BEGIN
    ALTER TABLE "WeeklyDigest" ADD CONSTRAINT "WeeklyDigest_userId_fkey"
        FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
