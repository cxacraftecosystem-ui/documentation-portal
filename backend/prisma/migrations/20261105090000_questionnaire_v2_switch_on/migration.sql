-- QUESTIONNAIRE V2, SAFE TO SWITCH ON (audit of 21 Oct 2026, C:/dev/IIT/docs/questionnaire/AUDIT_FR_2026-10-21.md).
--
-- ADDITIVE AND LIVE-SAFE: one nullable column, one index and one new table. Nothing is rewritten.
--
-- 1. QuestionnaireInterview."pendingChangesAt" (audit M13): new content on an APPROVED session stays
--    PENDING and puts the session back in the review queue until it is approved again.
-- 2. AppClientVersion (audit H22): which builds are in use, per account, platform and build number,
--    so the 30-day gate of owner decision 5 can be measured before the old-build cutoff is set.

ALTER TABLE "QuestionnaireInterview" ADD COLUMN "pendingChangesAt" TIMESTAMP(3);

CREATE INDEX "QuestionnaireInterview_pendingChangesAt_idx" ON "QuestionnaireInterview"("pendingChangesAt");

CREATE TABLE "AppClientVersion" (
    "userId" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "versionCode" INTEGER NOT NULL,
    "versionName" TEXT,
    "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AppClientVersion_pkey" PRIMARY KEY ("userId","platform","versionCode")
);

CREATE INDEX "AppClientVersion_platform_versionCode_idx" ON "AppClientVersion"("platform", "versionCode");

CREATE INDEX "AppClientVersion_lastSeenAt_idx" ON "AppClientVersion"("lastSeenAt");

ALTER TABLE "AppClientVersion" ADD CONSTRAINT "AppClientVersion_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- The Supabase Data API stays locked down (RLS on every table, anon revoked): the event trigger adds
-- RLS to a new table automatically where it is installed; this makes it explicit everywhere.
ALTER TABLE "AppClientVersion" ENABLE ROW LEVEL SECURITY;
