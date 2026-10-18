-- Artisan cards: the card table, its event log, and the designer portal's card code on a link.
-- Additive and guarded: two new tables, one nullable column, indexes. No existing row changes.
-- Cards are issued by scripts/backfill_artisan_cards.py at deploy, and lazily on first read.

CREATE TABLE IF NOT EXISTS "ArtisanCard" (
    "id" TEXT NOT NULL,
    "artisanId" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "keyVersion" INTEGER NOT NULL DEFAULT 1,
    "issuedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "issuedById" TEXT,
    "revokedAt" TIMESTAMP(3),
    "revokedById" TEXT,
    "revokedReason" TEXT,
    CONSTRAINT "ArtisanCard_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "ArtisanCard_code_key" ON "ArtisanCard"("code");
CREATE INDEX IF NOT EXISTS "ArtisanCard_artisanId_idx" ON "ArtisanCard"("artisanId");

-- One live card per artisan. Prisma cannot declare a partial index, so it lives here only; two
-- first reads racing each other both try to insert, and this is what lets exactly one win.
CREATE UNIQUE INDEX IF NOT EXISTS "ArtisanCard_one_live_per_artisan"
    ON "ArtisanCard"("artisanId") WHERE "revokedAt" IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ArtisanCard_artisanId_fkey') THEN
        ALTER TABLE "ArtisanCard" ADD CONSTRAINT "ArtisanCard_artisanId_fkey"
            FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS "ArtisanCardEvent" (
    "id" TEXT NOT NULL,
    "artisanId" TEXT,
    "cardCode" TEXT,
    "action" TEXT NOT NULL,
    "source" TEXT NOT NULL,
    "userId" TEXT,
    "count" INTEGER NOT NULL DEFAULT 1,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ArtisanCardEvent_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "ArtisanCardEvent_userId_action_createdAt_idx"
    ON "ArtisanCardEvent"("userId", "action", "createdAt");
CREATE INDEX IF NOT EXISTS "ArtisanCardEvent_artisanId_createdAt_idx"
    ON "ArtisanCardEvent"("artisanId", "createdAt");

ALTER TABLE "CrossPortalArtisanLink" ADD COLUMN IF NOT EXISTS "remoteCardCode" TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS "CrossPortalArtisanLink_remoteCardCode_key"
    ON "CrossPortalArtisanLink"("remoteCardCode");

