-- The day an artisan began practising, and the odd months on top of the years -- so experience can
-- be DERIVED from a date instead of stored as a number that decays, and so the two dropdowns the
-- form asks for can both be answered.
--
-- =============================================================================================
-- WHY A DATE IS A DIFFERENT KIND OF ANSWER FROM A NUMBER
-- =============================================================================================
--
-- "Artisan"."experienceYears" has held a stated number since 20260816170000 added it, and that
-- column's own comment in schema.prisma records the cost of the choice rather than hiding it: the
-- value "ages with the record rather than with the artisan ... an artisan documented in 2024 with
-- 30 years reads 30 in 2030". That decay prints. `record_fields.py`'s "Experience (years)" row
-- feeds the data browser's info card, the /data/report workbook, details.txt inside the dataset zip
-- and both /export CSVs -- four surfaces a ministry reader sees, all reading one stale number.
--
-- A DATE DOES NOT DECAY. `derive_experience_years` in services/records -- the sibling of `derive_age`
-- that this file has computed birthdays with since 20260816170000 -- turns this column into a number
-- at READ time, with nobody editing anything. That is the whole of the change.
--
-- =============================================================================================
-- WHY THERE IS NO BACKFILL, WHICH IS THE POINT OF THIS FILE
-- =============================================================================================
--
-- The migration this one sits beside -- 20260816170000_artisan_dob_and_experience -- opens with "WHY
-- THE BACKFILL IS THE POINT OF THIS MIGRATION", and it was right to. It computed a "dateOfBirth"
-- out of a legacy age and a known "recordedAt" BECAUSE THE COLUMNS IT ADDED WERE THE ONLY PLACE
-- THAT DATA COULD LIVE: the values existed nowhere but an unstructured `extraMetadata` blob no form
-- had written in years.
--
-- The situation here is the exact inverse, and the same arithmetic that was right there is wrong
-- here. A backfill would be `recordedAt - experienceYears`, and it is refused for two reasons.
--
-- 1. IT ADDS NO INFORMATION. Any date it could compute must be computed FROM "experienceYears", or
--    from the legacy `extraMetadata` spelling behind it -- and BOTH are already read, by the second
--    and third branches of `record_fields.py`'s "Experience (years)" lambda. On a row that has the
--    column, a backfilled date evaluated on "recordedAt" derives to exactly the number already
--    stored. On a row where the column is NULL because the legacy value was "30+" or "about 30", no
--    date is computable at all -- which is precisely why 20260816170000 left those rows alone and
--    told the readers to keep the JSON fallback. Net new information: zero, on every row.
--
-- 2. IT WOULD COST THE STATED/GUESSED DISTINCTION FOR EVER. The instant such an UPDATE runs, "a
--    number an artisan stated on a known day" and "a date a script inferred" are indistinguishable,
--    and `derive_experience_years` starts advancing the inference with the calendar. "She said 30
--    years in 2024" becomes "she has practised since ~1994 and therefore has 32 now" -- a claim
--    nobody made, on the oldest and most thoroughly documented rows in the repository. An age
--    advances unconditionally; experience is conditional on continued practice, and this schema
--    models neither retirement nor death. THERE IS NO DOWN-MIGRATION IN THIS PROJECT, so the only
--    walk-back would be a second migration NULLing rows chosen by a signature date -- guesswork
--    stacked on guesswork, over a column an export has already printed from.
--
-- So every row that exists on the day this runs keeps "craftStartDate" NULL and therefore prints,
-- character for character, what it printed yesterday.
--
-- =============================================================================================
-- WHY "experienceMonths" IS NULLABLE AND NOT DEFAULT 0
-- =============================================================================================
--
-- Every row in this table was written by a form that asked for years alone. A zero default would
-- put "and no months" on record for ALL of them as a stated fact, and the second dropdown would
-- open with an answer already selected on records whose artisan was never asked the question. An
-- artisan who said "about thirty years" said nothing whatever about months; ABSENT AND ZERO ARE
-- DIFFERENT ANSWERS, and NULL is the only one of the two that is true here.
--
-- A REMAINDER AND NEVER A TOTAL, which is why it is a second column rather than an
-- "experienceTotalMonths" the two boxes are divided out of. A single stored 66 has to be re-divided
-- on every read, and that arithmetic cannot tell "5 years and 6 months" from "66 months" from "five
-- and a half years"; it would also make "experienceYears" beside it a DERIVED column overnight -- a
-- column four export surfaces read -- to save one integer of storage.
--
-- =============================================================================================
-- WHY THIS IS SAFE TO APPLY, AND ROLLING BACK
-- =============================================================================================
--
-- Two nullable columns and one CHECK that NULL satisfies. Nothing is dropped, retyped, re-defaulted
-- or relaxed; no index changes, so every existing query plans exactly as it does today. A client
-- that has never heard of either column is unaffected, which matters because the field handsets run
-- offline for a fortnight at a time.
--
-- IF NOT EXISTS throughout, for 20260726200000's stated reason: migrations here are hand-authored
-- and a re-run of a half-applied directory must not fail on the statement that did land.
--
-- THE `DO $$` BLOCK IS NEW TO THIS DIRECTORY AND IS NOT NEW TO THIS CONSTRAINT. `ADD CONSTRAINT` has
-- no IF NOT EXISTS form, and the block below is the sibling repository's, which has shipped there
-- under the same prisma pin and the same `migrate deploy`, for a constraint of the same name
-- (designer-portal/backend/prisma/migrations/20260829090000_profile_location_experience_and_usage
-- /migration.sql:206-214). That is good evidence and not proof, and the failure mode is a refused
-- deploy rather than bad data: if `migrate deploy` ever rejects the block, the fallback is the bare
-- unguarded `ALTER TABLE ... ADD CONSTRAINT`, which can only fail on a re-run of a directory that
-- already applied.
--
-- Rolling back is hand-run (`deploy-backend.yml` has no automatic rollback):
--
--   ALTER TABLE "Artisan" DROP CONSTRAINT "Artisan_experienceMonths_range";
--   ALTER TABLE "Artisan" DROP COLUMN "experienceMonths";
--   ALTER TABLE "Artisan" DROP COLUMN "craftStartDate";
--
-- No data is lost by any of the three, because no data is moved or copied by this file.

-- AlterTable
-- TIMESTAMP(3) and not DATE, matching "dateOfBirth" beside it. Prisma maps `DateTime?` to
-- TIMESTAMP(3), the clients send a bare `yyyy-mm-dd` which Pydantic reads as midnight, and every
-- reader goes through `derive_experience_years`, which takes the date part. A DATE column here would
-- be the one column in this table Prisma did not expect.
ALTER TABLE "Artisan" ADD COLUMN IF NOT EXISTS "craftStartDate" TIMESTAMP(3);

-- AlterTable
-- INTEGER and not SMALLINT, matching "experienceYears" beside it. Prisma maps `Int?` to INTEGER, and
-- a SMALLINT here would be the one column in this table the client did not expect.
ALTER TABLE "Artisan" ADD COLUMN IF NOT EXISTS "experienceMonths" INTEGER;

-- AddCheckConstraint
-- 0..11: this is the calendar and not a policy. Twelve months is not a bigger month, it is a year
-- the column above already holds. NULL passes a CHECK in Postgres, so no existing row is touched and
-- no backfill is implied. `ArtisanCreate.experienceMonths` carries ge=0/le=11 for the OTHER half of
-- the job: a CHECK violation surfaces as a driver error raised from inside the write -- a bare 500
-- naming no field, on a save the researcher cannot correct -- whereas ge/le is a 422 whose `loc`
-- names the box.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'Artisan_experienceMonths_range'
  ) THEN
    ALTER TABLE "Artisan"
      ADD CONSTRAINT "Artisan_experienceMonths_range" CHECK ("experienceMonths" BETWEEN 0 AND 11);
  END IF;
END
$$;
