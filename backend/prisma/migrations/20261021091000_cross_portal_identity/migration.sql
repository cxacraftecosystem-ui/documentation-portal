-- C27: ONE ARTISAN ACROSS BOTH PORTALS. Additive: new tables only, nothing existing is touched.
-- Every statement is guarded, so a re-run changes nothing. Nothing reads these tables while the
-- cross-portal feature is off (CROSS_PORTAL_IDENTITY_ENABLED, default false).

CREATE TABLE IF NOT EXISTS "ArtisanSharingConsent" (
    "artisanId" TEXT NOT NULL,
    "granted" BOOLEAN NOT NULL,
    "note" TEXT,
    "recordedById" TEXT,
    "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "ArtisanSharingConsent_pkey" PRIMARY KEY ("artisanId")
);

DO $$ BEGIN
    ALTER TABLE "ArtisanSharingConsent" ADD CONSTRAINT "ArtisanSharingConsent_artisanId_fkey"
        FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS "CrossPortalArtisanLink" (
    "id" TEXT NOT NULL,
    "artisanId" TEXT NOT NULL,
    "remoteArtisanId" TEXT NOT NULL,
    "matchedBy" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CrossPortalArtisanLink_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "CrossPortalArtisanLink_artisanId_remoteArtisanId_key"
    ON "CrossPortalArtisanLink"("artisanId", "remoteArtisanId");
CREATE INDEX IF NOT EXISTS "CrossPortalArtisanLink_remoteArtisanId_idx"
    ON "CrossPortalArtisanLink"("remoteArtisanId");

DO $$ BEGIN
    ALTER TABLE "CrossPortalArtisanLink" ADD CONSTRAINT "CrossPortalArtisanLink_artisanId_fkey"
        FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
