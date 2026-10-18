-- Coverage map: an administrator's mapping of one free-text place spelling to an LGD code.
-- Additive: one new table, nothing existing is altered and nothing is backfilled.

CREATE TABLE IF NOT EXISTS "GeoAlias" (
    "id" TEXT NOT NULL,
    "rawStateKey" TEXT NOT NULL,
    "rawDistrictKey" TEXT NOT NULL DEFAULT '',
    "stateLgd" INTEGER NOT NULL,
    "districtLgd" INTEGER,
    "createdById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "GeoAlias_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "GeoAlias_rawStateKey_rawDistrictKey_key"
    ON "GeoAlias"("rawStateKey", "rawDistrictKey");
CREATE INDEX IF NOT EXISTS "GeoAlias_createdById_idx" ON "GeoAlias"("createdById");

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'GeoAlias_createdById_fkey') THEN
        ALTER TABLE "GeoAlias" ADD CONSTRAINT "GeoAlias_createdById_fkey"
            FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
    END IF;
END $$;
