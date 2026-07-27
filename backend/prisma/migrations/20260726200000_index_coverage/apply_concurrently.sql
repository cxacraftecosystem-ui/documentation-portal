-- Index coverage, applied WITHOUT locking the tables. Run this by hand against production BEFORE
-- deploying the code that expects it; then deploy normally and let `prisma migrate deploy` run
-- migration.sql, whose every statement is IF EXISTS / IF NOT EXISTS and therefore a no-op over the
-- work done here.
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f apply_concurrently.sql
--
-- WHY A SEPARATE FILE AND NOT JUST CONCURRENTLY IN THE MIGRATION. Postgres refuses CREATE INDEX
-- CONCURRENTLY and DROP INDEX CONCURRENTLY inside a transaction block, and `prisma migrate deploy`
-- sends a migration file as one multi-statement query that Postgres wraps in an implicit
-- transaction. It does not degrade to a plain build — it fails the deploy:
--
--     ERROR 25001: CREATE INDEX CONCURRENTLY cannot run inside a transaction block
--     Prisma P3018: A migration failed to apply.
--
-- and leaves a failed row in _prisma_migrations that blocks every later migration until someone runs
-- `prisma migrate resolve`. Reproduced against this exact schema on Prisma 5.17 / Postgres 16 rather
-- than taken on trust. psql is different: it sends each statement separately, in its own implicit
-- transaction, so CONCURRENTLY is legal here. Do not wrap this file in BEGIN/COMMIT, and do not run
-- it through a tool that does.
--
-- WHAT CONCURRENTLY COSTS. Each build scans the table twice and waits for transactions older than it
-- to finish, so it is slower in wall-clock than a plain build — but it takes only SHARE UPDATE
-- EXCLUSIVE, so reads and writes continue throughout. That is the trade this deployment wants: one
-- EC2 instance, no second node to fail over to.
--
-- IF A BUILD IS INTERRUPTED it leaves an invalid index behind, which costs writes and serves no
-- reads. Find and remove any before re-running:
--
--     SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
--     DROP INDEX CONCURRENTLY "<name>";
--
-- Safe to re-run: every statement is IF NOT EXISTS / IF EXISTS.

-- ADDED (15). See migration.sql for why each one exists.
CREATE INDEX CONCURRENTLY IF NOT EXISTS "MediaFile_artisanId_idx" ON "MediaFile" ("artisanId");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "MediaFile_productId_idx" ON "MediaFile" ("productId");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "MediaFile_toolId_idx"    ON "MediaFile" ("toolId");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "MediaFile_workshopId_createdAt_idx" ON "MediaFile" ("workshopId", "createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Artisan_createdAt_idx"                ON "Artisan" ("createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Workshop_createdAt_idx"               ON "Workshop" ("createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "ProductDocumentation_createdAt_idx"   ON "ProductDocumentation" ("createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "ToolDocumentation_createdAt_idx"      ON "ToolDocumentation" ("createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Process_createdAt_idx"                ON "Process" ("createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "QuestionnaireInterview_createdAt_idx" ON "QuestionnaireInterview" ("createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Artisan_workshopId_createdAt_idx"                ON "Artisan" ("workshopId", "createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "ProductDocumentation_workshopId_createdAt_idx"   ON "ProductDocumentation" ("workshopId", "createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "ToolDocumentation_workshopId_createdAt_idx"      ON "ToolDocumentation" ("workshopId", "createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Process_workshopId_createdAt_idx"                ON "Process" ("workshopId", "createdAt");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "QuestionnaireInterview_workshopId_createdAt_idx" ON "QuestionnaireInterview" ("workshopId", "createdAt");

-- DROPPED (36). Duplicated by another index, or read by no query. Each costs a write on every
-- insert and update of its table. See migration.sql for the reason against each name.
DROP INDEX CONCURRENTLY IF EXISTS "Artisan_workshopId_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ProductDocumentation_workshopId_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ToolDocumentation_workshopId_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Process_workshopId_idx";
DROP INDEX CONCURRENTLY IF EXISTS "QuestionnaireInterview_workshopId_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Craft_name_idx";                 -- Craft_name_key
DROP INDEX CONCURRENTLY IF EXISTS "QuestionnaireSection_code_idx";  -- QuestionnaireSection_code_key
DROP INDEX CONCURRENTLY IF EXISTS "ManagedSecret_key_idx";          -- ManagedSecret_key_key
DROP INDEX CONCURRENTLY IF EXISTS "ToolArtisan_toolId_idx";                -- ToolArtisan_toolId_artisanId_key
DROP INDEX CONCURRENTLY IF EXISTS "WorkshopAssignment_workshopId_idx";     -- WorkshopAssignment_workshopId_userId_key
DROP INDEX CONCURRENTLY IF EXISTS "QuestionnaireSectionStatus_artisanId_idx"; -- QuestionnaireSectionStatus_artisanId_sectionId_key
DROP INDEX CONCURRENTLY IF EXISTS "DataAccessScopeItem_grantId_idx";       -- DataAccessScopeItem_grantId_recordType_recordId_key
DROP INDEX CONCURRENTLY IF EXISTS "DataAccessGrant_ownerId_idx";           -- DataAccessGrant_ownerId_granteeId_key
DROP INDEX CONCURRENTLY IF EXISTS "Craft_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Artisan_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Workshop_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ProductDocumentation_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ToolDocumentation_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "MediaFile_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Process_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "QuestionnaireInterview_recordedAt_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Workshop_endDate_idx";
DROP INDEX CONCURRENTLY IF EXISTS "User_canManageQuestionnaire_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Artisan_place_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Craft_place_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Workshop_place_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ProductDocumentation_place_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ToolDocumentation_place_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ProductDocumentation_productName_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ProductDocumentation_craftName_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ProductDocumentation_artisanName_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ToolDocumentation_toolkitName_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ToolDocumentation_craftName_idx";
DROP INDEX CONCURRENTLY IF EXISTS "ToolDocumentation_artisanName_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Location_latitude_longitude_idx";
DROP INDEX CONCURRENTLY IF EXISTS "Location_placeName_idx";

-- Confirm afterwards: this should return no rows.
--   SELECT indexrelid::regclass AS invalid_index FROM pg_index WHERE NOT indisvalid;
