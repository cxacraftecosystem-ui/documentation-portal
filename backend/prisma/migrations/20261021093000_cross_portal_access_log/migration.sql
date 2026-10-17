-- C27: the access log for the designer portal's lookups here. Additive and guarded; nothing reads
-- or writes it while CROSS_PORTAL_IDENTITY_ENABLED is false. No identity number is ever stored in it.

CREATE TABLE IF NOT EXISTS "CrossPortalAccessLog" (
    "id" TEXT NOT NULL,
    "action" TEXT NOT NULL,
    "outcome" TEXT NOT NULL,
    "artisanId" TEXT,
    "remoteUserId" TEXT,
    "remoteArtisanId" TEXT,
    "matchedBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CrossPortalAccessLog_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_artisanId_createdAt_idx"
    ON "CrossPortalAccessLog"("artisanId", "createdAt");
CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_remoteUserId_createdAt_idx"
    ON "CrossPortalAccessLog"("remoteUserId", "createdAt");
CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_createdAt_idx"
    ON "CrossPortalAccessLog"("createdAt");
