-- Duplicate artisans (services/artisan_duplicates.py, services/artisan_merge.py): the likely pairs
-- an admin reviews, and the merge records that make a merge reversible for 30 days and redirect a
-- merged id forever. Additive: two new tables, three new enums, nothing existing is altered.

CREATE TYPE "DuplicateKind" AS ENUM ('WITHIN', 'CROSS_PORTAL');
CREATE TYPE "DuplicateStatus" AS ENUM ('OPEN', 'NOT_DUPLICATE', 'LINKED');
CREATE TYPE "DuplicateSource" AS ENUM ('SCAN', 'CREATE', 'IMPORT');

CREATE TABLE "ArtisanDuplicateCandidate" (
    "id" TEXT NOT NULL,
    "kind" "DuplicateKind" NOT NULL,
    "artisanAId" TEXT NOT NULL,
    "artisanBId" TEXT,
    "remoteArtisanId" TEXT,
    "remoteMatchedBy" TEXT,
    "score" DOUBLE PRECISION NOT NULL,
    "band" TEXT NOT NULL,
    "reasons" JSONB NOT NULL,
    "status" "DuplicateStatus" NOT NULL DEFAULT 'OPEN',
    "source" "DuplicateSource" NOT NULL,
    "creatorSaidDifferent" BOOLEAN NOT NULL DEFAULT false,
    "decidedById" TEXT,
    "decidedAt" TIMESTAMP(3),
    "decisionNote" TEXT,
    "scannedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "ArtisanDuplicateCandidate_pkey" PRIMARY KEY ("id"),
    -- A WITHIN pair names two local artisans in id order (one pair, one row); a CROSS_PORTAL pair
    -- names one local artisan and a remote id, and nothing else (designer portal only).
    CONSTRAINT "ArtisanDuplicateCandidate_shape_check" CHECK (
        ("kind" = 'WITHIN' AND "artisanBId" IS NOT NULL AND "remoteArtisanId" IS NULL
            AND "artisanAId" < "artisanBId")
        OR ("kind" = 'CROSS_PORTAL' AND "artisanBId" IS NULL AND "remoteArtisanId" IS NOT NULL)
    )
);

CREATE INDEX "ArtisanDuplicateCandidate_status_score_idx" ON "ArtisanDuplicateCandidate"("status", "score");
CREATE INDEX "ArtisanDuplicateCandidate_artisanAId_idx" ON "ArtisanDuplicateCandidate"("artisanAId");
CREATE INDEX "ArtisanDuplicateCandidate_artisanBId_idx" ON "ArtisanDuplicateCandidate"("artisanBId");
CREATE INDEX "ArtisanDuplicateCandidate_decidedById_idx" ON "ArtisanDuplicateCandidate"("decidedById");

-- The upsert targets (INSERT ... ON CONFLICT (...) WHERE kind = ... DO UPDATE).
CREATE UNIQUE INDEX "ArtisanDuplicateCandidate_within_pair_key"
    ON "ArtisanDuplicateCandidate"("artisanAId", "artisanBId") WHERE "kind" = 'WITHIN';
CREATE UNIQUE INDEX "ArtisanDuplicateCandidate_cross_portal_pair_key"
    ON "ArtisanDuplicateCandidate"("artisanAId", "remoteArtisanId") WHERE "kind" = 'CROSS_PORTAL';

ALTER TABLE "ArtisanDuplicateCandidate" ADD CONSTRAINT "ArtisanDuplicateCandidate_artisanAId_fkey"
    FOREIGN KEY ("artisanAId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "ArtisanDuplicateCandidate" ADD CONSTRAINT "ArtisanDuplicateCandidate_artisanBId_fkey"
    FOREIGN KEY ("artisanBId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "ArtisanDuplicateCandidate" ADD CONSTRAINT "ArtisanDuplicateCandidate_decidedById_fkey"
    FOREIGN KEY ("decidedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "ArtisanMerge" (
    "id" TEXT NOT NULL,
    "survivorId" TEXT NOT NULL,
    "mergedId" TEXT NOT NULL,
    "mergedById" TEXT,
    "reason" TEXT,
    "choices" JSONB NOT NULL,
    "snapshot" JSONB,
    "moves" JSONB,
    "crossPortal" JSONB,
    "undoUntil" TIMESTAMP(3) NOT NULL,
    "undoneAt" TIMESTAMP(3),
    "undoneById" TEXT,
    "snapshotPurgedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ArtisanMerge_pkey" PRIMARY KEY ("id")
);

-- One live merge per deleted id. An undone merge stays as history, so the same pair can be merged
-- again after an undo; the redirect reads only rows with "undoneAt" IS NULL.
CREATE UNIQUE INDEX "ArtisanMerge_mergedId_live_key" ON "ArtisanMerge"("mergedId") WHERE "undoneAt" IS NULL;
CREATE INDEX "ArtisanMerge_mergedId_idx" ON "ArtisanMerge"("mergedId");
CREATE INDEX "ArtisanMerge_survivorId_idx" ON "ArtisanMerge"("survivorId");
CREATE INDEX "ArtisanMerge_createdAt_idx" ON "ArtisanMerge"("createdAt");
CREATE INDEX "ArtisanMerge_mergedById_idx" ON "ArtisanMerge"("mergedById");
CREATE INDEX "ArtisanMerge_undoneById_idx" ON "ArtisanMerge"("undoneById");

ALTER TABLE "ArtisanMerge" ADD CONSTRAINT "ArtisanMerge_mergedById_fkey"
    FOREIGN KEY ("mergedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "ArtisanMerge" ADD CONSTRAINT "ArtisanMerge_undoneById_fkey"
    FOREIGN KEY ("undoneById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- The hosted database's HTTP data API: deny-all. The web-facing roles were revoked together with
-- the default privileges that would re-grant them, and both are stated here as well. The merge
-- snapshot holds identity data for 30 days. The app connects as the table owner, which RLS does
-- not restrict. The two roles exist only on the hosted project. The SQL is the same in both portals.
ALTER TABLE "ArtisanDuplicateCandidate" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "ArtisanMerge" ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'REVOKE ALL ON TABLE "ArtisanDuplicateCandidate", "ArtisanMerge" FROM anon';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
    EXECUTE 'REVOKE ALL ON TABLE "ArtisanDuplicateCandidate", "ArtisanMerge" FROM authenticated';
  END IF;
END
$$;
