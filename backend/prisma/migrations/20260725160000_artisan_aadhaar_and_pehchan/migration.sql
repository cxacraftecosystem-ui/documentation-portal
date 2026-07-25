-- Artisan identity: Aadhaar number + Artisan Pehchan Card, so the same person documented at two
-- workshops by two researchers collapses to ONE artisan record instead of silently becoming two.
--
--   * "aadhaarNumber" is the deduplication key and carries a UNIQUE index. It is NULLABLE and is not
--     backfilled: every artisan recorded before this migration keeps NULL, and Postgres permits any
--     number of NULLs under a unique index, so no existing row conflicts and nothing is rejected on
--     deploy. Only newly supplied numbers have to be distinct from each other.
--   * "pehchanCardAvailable" defaults to TRUE, matching the form's default answer for NEW records.
--     Existing rows are then explicitly backfilled to FALSE, because they have no card number: the
--     column default would otherwise assert "this artisan holds a card" for every legacy row while
--     the number sits NULL, putting the entire pre-existing table in violation of the
--     Yes-implies-a-number invariant the API enforces — and forcing a researcher to invent a card
--     number before they could save any unrelated edit to an old artisan. FALSE says only "no card
--     number on file", which is exactly what is known, and a researcher can answer Yes later.
--   * "pehchanCardNumber" is also UNIQUE — one physical card belongs to one artisan — and likewise
--     nullable/not backfilled.
--
-- Safe on populated tables: two nullable columns, one boolean column with a DEFAULT (Postgres 11+
-- adds a defaulted column without rewriting the table), and two unique indexes over columns that are
-- entirely NULL at the moment they are created.

-- AlterTable
ALTER TABLE "Artisan" ADD COLUMN "aadhaarNumber" TEXT;

-- AlterTable
ALTER TABLE "Artisan" ADD COLUMN "pehchanCardAvailable" BOOLEAN NOT NULL DEFAULT true;

-- AlterTable
ALTER TABLE "Artisan" ADD COLUMN "pehchanCardNumber" TEXT;

-- Backfill: every row that predates these columns has no card number, so record "no card on file"
-- rather than letting the DEFAULT claim a card that was never entered. Runs while the column is
-- uniformly NULL, so it touches every existing row and no new one.
UPDATE "Artisan" SET "pehchanCardAvailable" = false WHERE "pehchanCardNumber" IS NULL;

-- CreateIndex
CREATE UNIQUE INDEX "Artisan_aadhaarNumber_key" ON "Artisan"("aadhaarNumber");

-- CreateIndex
CREATE UNIQUE INDEX "Artisan_pehchanCardNumber_key" ON "Artisan"("pehchanCardNumber");
