-- What KIND of workshop a "Workshop" row records.
--
-- =============================================================================================
-- ADDITIVE AND DEFAULTED
-- =============================================================================================
--
-- A design & prototype workshop and an ordinary documentation visit are both "Workshop" rows with
-- nothing to tell them apart. Every existing row becomes OTHER, which is what they were implicitly.
-- Nothing changes for any reader that does not ask about the column.
--
-- WHY THIS PRODUCT CARRIES A TOKEN IT HAS NO SCREEN FOR. `DESIGN_PROTOTYPE` names a thing this
-- repository does not model -- there is no DesignWorkshop table here and this change does not add
-- one. It is carried because the two products coordinate their record vocabularies ON PURPOSE:
-- `schemas/records.py`'s `experienceYears` bound is 0..90 with the written reason "the same bound
-- the sibling repository's design-workshop registry uses, so an artisan exported from one product
-- and imported into the other cannot carry a number the other side refuses". A workshop recorded
-- here that the sibling later adopts must be able to carry the same mark; without this column every
-- workshop this product writes is permanently unclassifiable to it.
--
-- AND A COLUMN NO CLIENT CAN SET IS A COLUMN THAT CAN NEVER HOLD `DESIGN_PROTOTYPE`. The wire half
-- lands with this file: `WorkshopCreate.workshopType` / `WorkshopUpdate.workshopType`, the
-- `workshopType` filter on `GET /workshops`, `Workshop.workshopType` in frontend/lib/types.ts and
-- `WorkshopCreateRequest`/`WorkshopDetailDto` in the Android models. The two form CONTROLS belong to
-- files this workstream does not own and are named in its handoff.
--
-- =============================================================================================
-- WHY `CREATE TYPE` MAY SHARE A FILE WITH THE COLUMN THAT USES IT
-- =============================================================================================
--
-- The house rule that an enum change must be a migration's ONLY statement is about `ALTER TYPE ...
-- ADD VALUE`: a value added that way cannot be USED in the same implicit transaction, and prisma
-- sends a migration file as one. `CREATE TYPE` carries no such restriction -- a type created in a
-- transaction is usable in it -- so the type, the column and the index are one file here. The
-- sibling repository's 20260807180000_workshop_type does exactly this (bare `CREATE TYPE` +
-- `ADD COLUMN` + `CREATE INDEX`, one file). 20260724120000_six_tier_roles is the other rule, and it
-- still applies to any later token added to this enum.
--
-- `CREATE TYPE` has no IF NOT EXISTS form, hence the DO block: this directory's convention is that a
-- re-run of a half-applied migration must not fail on the statement that did land. See
-- 20260913120000's note for the precedent behind the block itself and for the fallback if a
-- `migrate deploy` ever refuses it.
--
-- Rolling back:
--   DROP INDEX "Workshop_workshopType_startDate_idx";
--   ALTER TABLE "Workshop" DROP COLUMN "workshopType";
--   DROP TYPE "WorkshopType";

-- CreateEnum
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'WorkshopType') THEN
    CREATE TYPE "WorkshopType" AS ENUM ('DESIGN_PROTOTYPE', 'OTHER');
  END IF;
END
$$;

-- AlterTable
ALTER TABLE "Workshop"
  ADD COLUMN IF NOT EXISTS "workshopType" "WorkshopType" NOT NULL DEFAULT 'OTHER';

-- CreateIndex
-- The list filters on the type and orders by date; one composite index serves both. Equality first,
-- then the sort column, for the reason the "Artisan" index block states.
CREATE INDEX IF NOT EXISTS "Workshop_workshopType_startDate_idx"
  ON "Workshop" ("workshopType", "startDate");
