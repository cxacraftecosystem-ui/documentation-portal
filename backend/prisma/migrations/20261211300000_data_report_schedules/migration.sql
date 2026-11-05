CREATE TABLE IF NOT EXISTS "DataReportSchedule" (
    "id" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "savedViewId" TEXT NOT NULL,
    "weekday" INTEGER NOT NULL,
    "hourLocal" INTEGER NOT NULL,
    "timezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    "options" JSONB NOT NULL,
    "pausedAt" TIMESTAMP(3),
    "lastSlotAt" TIMESTAMP(3),
    "retryAfter" TIMESTAMP(3),
    "lastSentAt" TIMESTAMP(3),
    "lastError" TEXT,
    "lastFileKey" TEXT,
    "lastFileName" TEXT,
    "lastFileBytes" INTEGER,
    "lastAttached" BOOLEAN,
    "unsubscribeTokenHash" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "DataReportSchedule_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DataReportSchedule_ownerId_fkey" FOREIGN KEY ("ownerId")
        REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "DataReportSchedule_savedViewId_fkey" FOREIGN KEY ("savedViewId")
        REFERENCES "DataSavedView"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "DataReportSchedule_unsubscribeTokenHash_key"
    ON "DataReportSchedule"("unsubscribeTokenHash");
CREATE INDEX IF NOT EXISTS "DataReportSchedule_ownerId_createdAt_idx"
    ON "DataReportSchedule"("ownerId", "createdAt");
CREATE INDEX IF NOT EXISTS "DataReportSchedule_pausedAt_idx"
    ON "DataReportSchedule"("pausedAt");
CREATE INDEX IF NOT EXISTS "DataReportSchedule_savedViewId_idx"
    ON "DataReportSchedule"("savedViewId");
