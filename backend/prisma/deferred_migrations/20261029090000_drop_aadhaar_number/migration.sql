-- Step 3 of 3: the artisan register forgets the full Aadhaar number (backend/DEPLOY_AWS.md).
--
-- NOT IN prisma/migrations ON PURPOSE. `prisma migrate deploy` runs at every deploy, BEFORE anyone
-- has run the backfill on the box; shipped there, this file would refuse and fail the deploy. It is
-- moved into prisma/migrations/ (same folder name) in a LATER release, together with removing
-- `aadhaarNumber` from schema.prisma and setting `LEGACY_COLUMN_PRESENT = False` in
-- app/services/aadhaar_fingerprint.py — and only after scripts/backfill_aadhaar_fingerprint.py
-- --apply has printed "Nothing left" on that box.
--
-- GUARDED. It refuses, changing nothing, while any artisan still holds a number without its
-- fingerprint, or a fingerprint's last four that disagree with the number, or while any artisan's
-- edit history still holds a real value under "aadhaarNumber". A refused migration is recorded as
-- failed; after running the backfill, `prisma migrate resolve --rolled-back
-- 20261029090000_drop_aadhaar_number` and deploy again.

DO $$
DECLARE
    unfingerprinted integer;
    mismatched integer;
    history integer;
BEGIN
    SELECT count(*) INTO unfingerprinted FROM "Artisan"
        WHERE "aadhaarNumber" IS NOT NULL AND "aadhaarFingerprint" IS NULL;
    SELECT count(*) INTO mismatched FROM "Artisan"
        WHERE "aadhaarNumber" IS NOT NULL AND "aadhaarFingerprint" IS NOT NULL
          AND "aadhaarLast4" IS DISTINCT FROM right(regexp_replace("aadhaarNumber", '\D', '', 'g'), 4);
    SELECT count(*) INTO history FROM "RecordRevision"
        WHERE "recordType" = 'artisan' AND jsonb_typeof("changes") = 'object'
          AND "changes" ? 'aadhaarNumber'
          AND jsonb_typeof("changes" -> 'aadhaarNumber') = 'object'
          AND NOT coalesce(("changes" -> 'aadhaarNumber' ->> 'redacted')::boolean, false);
    IF unfingerprinted > 0 OR mismatched > 0 OR history > 0 THEN
        RAISE EXCEPTION
            'Refusing to drop "Artisan"."aadhaarNumber": % artisan(s) without a fingerprint, % with mismatched last four, % edit-history entr(ies) holding a value. Run scripts/backfill_aadhaar_fingerprint.py --apply on this box first (backend/DEPLOY_AWS.md).',
            unfingerprinted, mismatched, history;
    END IF;
END $$;

UPDATE "Artisan" SET "aadhaarNumber" = NULL WHERE "aadhaarNumber" IS NOT NULL;

DROP INDEX IF EXISTS "Artisan_aadhaarNumber_key";
ALTER TABLE "Artisan" DROP COLUMN "aadhaarNumber";
