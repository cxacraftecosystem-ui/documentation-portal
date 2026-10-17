-- Two data-hygiene tools (2026-10-20). Additive only: three nullable-or-defaulted columns on the
-- settings singleton and one new table. Nothing existing is rewritten, so it is safe on a live database
-- and an older API process keeps working against it.
--
-- 1. The nightly workshop filing (A6). The queue worker files, once a night, every record whose parent
--    record already names a workshop. On by default; the admin card on /workshops can switch it off.
ALTER TABLE "AppSetting" ADD COLUMN "workshopAutoMapEnabled" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "AppSetting" ADD COLUMN "workshopAutoMapLastRunAt" TIMESTAMP(3);
ALTER TABLE "AppSetting" ADD COLUMN "workshopAutoMapLastResult" JSONB;

-- 2. Merging duplicate artisans (C24): the audit row, the undo, and the redirect for queued writes that
--    still name the merged artisan. No foreign keys on purpose — see schema.prisma.
CREATE TABLE "ArtisanMerge" (
    "id" TEXT NOT NULL,
    "survivorId" TEXT NOT NULL,
    "mergedId" TEXT NOT NULL,
    "survivorName" TEXT NOT NULL,
    "mergedName" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    "picks" JSONB NOT NULL,
    "survivorBefore" JSONB NOT NULL,
    "survivorAfter" JSONB,
    "mergedSnapshot" JSONB NOT NULL,
    "moves" JSONB NOT NULL,
    "mergedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completedAt" TIMESTAMP(3),
    "undoneAt" TIMESTAMP(3),
    "undoneById" TEXT,
    "undoNotes" JSONB,

    CONSTRAINT "ArtisanMerge_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "ArtisanMerge_mergedId_idx" ON "ArtisanMerge"("mergedId");
CREATE INDEX "ArtisanMerge_survivorId_idx" ON "ArtisanMerge"("survivorId");
CREATE INDEX "ArtisanMerge_createdAt_idx" ON "ArtisanMerge"("createdAt");
