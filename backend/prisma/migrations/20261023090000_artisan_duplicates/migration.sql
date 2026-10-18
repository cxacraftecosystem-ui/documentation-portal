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

CREATE UNIQUE INDEX "ArtisanMerge_mergedId_key" ON "ArtisanMerge"("mergedId");
CREATE INDEX "ArtisanMerge_survivorId_idx" ON "ArtisanMerge"("survivorId");
CREATE INDEX "ArtisanMerge_createdAt_idx" ON "ArtisanMerge"("createdAt");
CREATE INDEX "ArtisanMerge_mergedById_idx" ON "ArtisanMerge"("mergedById");
CREATE INDEX "ArtisanMerge_undoneById_idx" ON "ArtisanMerge"("undoneById");

ALTER TABLE "ArtisanMerge" ADD CONSTRAINT "ArtisanMerge_mergedById_fkey"
    FOREIGN KEY ("mergedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "ArtisanMerge" ADD CONSTRAINT "ArtisanMerge_undoneById_fkey"
    FOREIGN KEY ("undoneById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
