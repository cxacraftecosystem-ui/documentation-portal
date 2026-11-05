CREATE TABLE IF NOT EXISTS "DataReportShare" (
    "id" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "path" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "options" JSONB NOT NULL,
    "snapshot" JSONB NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DataReportShare_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DataReportShare_ownerId_fkey" FOREIGN KEY ("ownerId")
        REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "DataReportShare_tokenHash_key"
    ON "DataReportShare"("tokenHash");
CREATE INDEX IF NOT EXISTS "DataReportShare_ownerId_createdAt_idx"
    ON "DataReportShare"("ownerId", "createdAt");

CREATE TABLE IF NOT EXISTS "DataReportShareView" (
    "id" TEXT NOT NULL,
    "shareId" TEXT NOT NULL,
    "viewerId" TEXT,
    "ipHash" TEXT NOT NULL,
    "userAgent" TEXT,
    "openedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DataReportShareView_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DataReportShareView_shareId_fkey" FOREIGN KEY ("shareId")
        REFERENCES "DataReportShare"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "DataReportShareView_viewerId_fkey" FOREIGN KEY ("viewerId")
        REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "DataReportShareView_shareId_openedAt_idx"
    ON "DataReportShareView"("shareId", "openedAt");

ALTER TABLE "DataReportShare" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "DataReportShareView" ENABLE ROW LEVEL SECURITY;
