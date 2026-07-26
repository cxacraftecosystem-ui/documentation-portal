-- Postal state / union territory and PIN code on the shared Location model, so every record type
-- that already links to a Location (artisan, workshop, product, tool, media, interview) gains both
-- at once instead of six near-identical column pairs drifting apart.
--
--   * "state" holds the CANONICAL name from the closed list in app/services/address.py — the same
--     list GET /api/reference/address serves to the web and Android forms. Nothing is backfilled:
--     the only state values in the system today sit in Artisan.extraMetadata as free text, and
--     guessing which of them mean which canonical name would write data nobody entered. The export
--     registry reads the column first and falls back to that metadata, so historical rows keep
--     showing exactly what they always showed.
--   * "pincode" holds the bare six digits. Validated (six ASCII digits, leading 1-9) at the API
--     layer rather than by a CHECK constraint, for the same reason the rest of this schema does:
--     a constraint violation surfaces as a 500 with no field name, while the validator returns a
--     422 that names the digit to re-read.
--
-- Safe on a populated table: two NULLable columns with no default (Postgres adds these as a
-- catalogue-only change, no table rewrite and no row lock beyond the ALTER itself) and one index
-- over a column that is entirely NULL at the moment it is created.

-- AlterTable
ALTER TABLE "Location" ADD COLUMN "state" TEXT;

-- AlterTable
ALTER TABLE "Location" ADD COLUMN "pincode" TEXT;

-- CreateIndex
-- "every record in Gujarat" is the query this column exists to answer, and without an index it is a
-- sequential scan of every coordinate ever captured.
CREATE INDEX "Location_state_idx" ON "Location"("state");
