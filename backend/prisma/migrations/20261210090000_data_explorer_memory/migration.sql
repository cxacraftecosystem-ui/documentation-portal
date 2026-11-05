CREATE TABLE IF NOT EXISTS "DataReportPreference" (
    "userId" TEXT NOT NULL,
    "options" JSONB NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "DataReportPreference_pkey" PRIMARY KEY ("userId"),
    CONSTRAINT "DataReportPreference_userId_fkey" FOREIGN KEY ("userId")
        REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE IF NOT EXISTS "DataFolderVisit" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "path" TEXT NOT NULL,
    "visitedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DataFolderVisit_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DataFolderVisit_userId_fkey" FOREIGN KEY ("userId")
        REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "DataFolderVisit_userId_path_key"
    ON "DataFolderVisit"("userId", "path");

CREATE TABLE IF NOT EXISTS "DataSavedView" (
    "id" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "path" TEXT NOT NULL,
    "state" JSONB NOT NULL,
    "shared" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "DataSavedView_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DataSavedView_ownerId_fkey" FOREIGN KEY ("ownerId")
        REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "DataSavedView_ownerId_updatedAt_idx"
    ON "DataSavedView"("ownerId", "updatedAt");
CREATE INDEX IF NOT EXISTS "DataSavedView_shared_updatedAt_idx"
    ON "DataSavedView"("shared", "updatedAt");

CREATE TABLE IF NOT EXISTS "MediaWaveform" (
    "mediaFileId" TEXT NOT NULL,
    "peaks" JSONB NOT NULL,
    "durationMs" INTEGER,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "MediaWaveform_pkey" PRIMARY KEY ("mediaFileId"),
    CONSTRAINT "MediaWaveform_mediaFileId_fkey" FOREIGN KEY ("mediaFileId")
        REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE
);

ALTER TABLE "DataReportPreference" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "DataFolderVisit" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "DataSavedView" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "MediaWaveform" ENABLE ROW LEVEL SECURITY;
