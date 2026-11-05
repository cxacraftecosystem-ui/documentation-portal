CREATE TABLE IF NOT EXISTS "DataBundle" (
    "id" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "label" TEXT NOT NULL,
    "path" TEXT NOT NULL DEFAULT '',
    "digest" TEXT NOT NULL,
    "mediaIds" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "fileCount" INTEGER NOT NULL DEFAULT 0,
    "bytes" BIGINT NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastOpenedAt" TIMESTAMP(3),
    CONSTRAINT "DataBundle_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DataBundle_ownerId_fkey" FOREIGN KEY ("ownerId")
        REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "DataBundle_ownerId_digest_key"
    ON "DataBundle"("ownerId", "digest");
CREATE INDEX IF NOT EXISTS "DataBundle_createdAt_idx"
    ON "DataBundle"("createdAt");
