-- The artisan register stops holding Aadhaar numbers (owner decision #2, 2026-10-17).
--
-- Step 1 of 3 (backend/DEPLOY_AWS.md, "Aadhaar fingerprint"):
--   1. THIS MIGRATION adds the two columns the register keeps from now on, both nullable.
--   2. scripts/backfill_aadhaar_fingerprint.py, run on the box after the deploy, fills them from
--      the legacy "aadhaarNumber" column (dry run by default).
--   3. prisma/deferred_migrations/20261029090000_drop_aadhaar_number, moved into migrations/ in a
--      LATER release, refuses to run while any row still holds a number without a fingerprint, then
--      clears and drops "aadhaarNumber".
--
-- "aadhaarFingerprint" is HMAC-SHA256(AADHAAR_FINGERPRINT_KEY, the 12 digits) in lower-case hex,
-- computed by the application (services/aadhaar_fingerprint.py); the key never reaches the
-- database. Unique where present, like "aadhaarNumber" was. "aadhaarLast4" is what every screen
-- shows. The CHECKs hold both to their shape, so a full number can never be written into either.

ALTER TABLE "Artisan" ADD COLUMN "aadhaarFingerprint" TEXT;
ALTER TABLE "Artisan" ADD COLUMN "aadhaarLast4" TEXT;

CREATE UNIQUE INDEX "Artisan_aadhaarFingerprint_key" ON "Artisan"("aadhaarFingerprint");

ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_aadhaarFingerprint_shape"
    CHECK ("aadhaarFingerprint" IS NULL OR "aadhaarFingerprint" ~ '^[0-9a-f]{64}$');
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_aadhaarLast4_shape"
    CHECK ("aadhaarLast4" IS NULL OR "aadhaarLast4" ~ '^[0-9]{4}$');
-- Both halves or neither: a fingerprint without its last four would show as "no Aadhaar".
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_aadhaarFingerprint_pair"
    CHECK (("aadhaarFingerprint" IS NULL) = ("aadhaarLast4" IS NULL));
