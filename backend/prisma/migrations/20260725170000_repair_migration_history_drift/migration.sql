-- Repair drift between prisma/schema.prisma and the migration history.
--
-- `User.canManageCrafts` and `User.canManageWorkshops` are declared in schema.prisma and read by the
-- permission layer (deps.can_manage_crafts / can_manage_workshops, mirrored in
-- frontend/lib/permissions.ts), but NO migration ever created them — they reached the live database
-- out-of-band during the grantable-capability work. The consequence only shows up on a database
-- built from the migrations alone: `prisma migrate deploy` produced a schema without those columns,
-- and the very first query touching a User (login) died with
--     prisma.errors.DataError: The column `User.canManageCrafts` does not exist in the current database.
-- So the repo could not stand up a fresh environment at all. Found by doing exactly that.
--
-- Written to be a NO-OP where the columns already exist (production, and any developer machine that
-- was provisioned before this) and corrective everywhere else, hence IF NOT EXISTS rather than a
-- plain ADD COLUMN, which would abort the whole migration on production with "column already
-- exists". Both are non-null with a false default, so no backfill and no table rewrite (PG 11+).
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "canManageCrafts" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "canManageWorkshops" BOOLEAN NOT NULL DEFAULT false;

-- `QuestionnaireSection.updatedAt` is `DateTime @updatedAt` in the schema, which Prisma maintains in
-- the client rather than the database, so it carries no DEFAULT. An early migration created it with
-- one; dropping it removes the last difference between a freshly-migrated database and the schema.
-- DROP DEFAULT is idempotent in Postgres, so this is safe to run on a column that never had one.
ALTER TABLE "QuestionnaireSection" ALTER COLUMN "updatedAt" DROP DEFAULT;
