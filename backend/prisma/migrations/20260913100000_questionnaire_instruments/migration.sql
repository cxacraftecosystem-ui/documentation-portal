-- ONE INSTRUMENT BECOMES MANY. The 24 sections, 285 questions and every interview already in this
-- database move under a parent WITHOUT MOVING — not one row is rewritten, not one id changes, not
-- one answer is touched. What changes is the scope of three unique indexes.
--
-- (285, not 284: the committed corpus `app/data/questionnaire_questions.json` holds 284 questions
-- and the live database holds 285. One question was added through the builder and exists only in
-- the database. The backfill below is a blanket `WHERE "questionnaireId" IS NULL`, which is exactly
-- why that row survives: nothing here reads the corpus, so nothing here can lose a row the corpus
-- does not know about. The seeder is the other half of that promise — see
-- app/services/questionnaire_seeding.py, "kept, not in the corpus".)
--
-- WHAT WOULD HAVE HAPPENED WITHOUT THIS MIGRATION
-- ===============================================
-- The 3rd Craft Toolkit Workshop's instrument is 22 sections coded A..V and 81 questions. The
-- corpus already seeded here is 24 sections coded RESP, A..W. EVERY ONE of the 22 new codes already
-- exists. `QuestionnaireSection.code` was `@unique` GLOBALLY (schema.prisma:964 before this change)
-- and `scripts/seed_questionnaire.py:18-19` upserted ON that code, so seeding the new instrument
-- would not have created 22 sections: it would have RETITLED 22 live ones, leaving the questions
-- and every recorded answer hanging under headings that no longer describe them. Section V changes
-- meaning outright — "International Exposure and Overseas Travel" becomes "NETWORK / ECOSYSTEM
-- MAPPING" — and section W (GSHPP) has no counterpart at all and would have been orphaned with its
-- answers still attached.
--
-- It would not even have got that far. `QuestionnaireSection` also carried `@@unique([sortOrder])`
-- globally (schema.prisma:974 before this change) and the seeder wrote `sortOrder = the 1-based
-- index of the section in the JSON array` in a single pass (seed_questionnaire.py:15,24,29). The new
-- corpus drops RESP, which holds sortOrder 1 today, so the new section A would have claimed
-- sortOrder 1 against a live row on the VERY FIRST upsert: UniqueViolationError, corpus
-- half-written, no transaction to roll it back (this backend has zero `db.tx()` call sites).
--
-- WHY THE THIRD INDEX HAD TO MOVE TOO
-- ===================================
-- `QuestionnaireInterview.artisanSetKey` was `@unique` globally (schema.prisma:1045 before this
-- change), meaning one artisan set can hold exactly one interview repository-wide. The same artisans
-- sitting for a second instrument would have FOLDED into their first sitting
-- (routes/questionnaire.create_interview) and the 3rd workshop's answers would have been written
-- onto the 2nd workshop's interview. The key itself is unchanged — `artisan_set_key` still returns
-- sorted, comma-joined artisan ids — and this migration rewrites no key values. Only the index moved.
--
-- IDEMPOTENT. Every statement below is guarded (`IF NOT EXISTS`, `WHERE NOT EXISTS`, `WHERE … IS
-- NULL`, `DROP CONSTRAINT IF EXISTS` before each `ADD CONSTRAINT`). A second run changes nothing.
-- TWO things a second run deliberately does NOT do, and both are decisions an operator may have
-- made since:
--   * it does not re-assert `isDefault` on the 2nd-workshop row (see the `WHERE NOT EXISTS` on the
--     INSERT in step 2);
--   * it does not re-attach a workshop an admin has DETACHED. `Workshop.questionnaireId` is
--     legitimately settable back to NULL by `PUT /api/workshops/{id}/questionnaire` with a null
--     body, so a bare `WHERE "questionnaireId" IS NULL` on a hand re-run would silently undo every
--     such detach. The marker table in step 4 is what stops that. Do not "simplify" it away.
--
-- THE COMPOSITE UNIQUES CANNOT FAIL ON EXISTING DATA. Their left column is a constant (every
-- existing row is backfilled to the same instrument id) and their right column was already globally
-- unique. (constant, unique) is unique.


-- ---------------------------------------------------------------------------------------------
-- 1. THE CONTAINER
-- ---------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "Questionnaire" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "isDefault" BOOLEAN NOT NULL DEFAULT false,
    "sortOrder" INTEGER NOT NULL,
    "createdById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Questionnaire_pkey" PRIMARY KEY ("id")
);

-- The title is the identity both seeders find their row by on a re-run. Unique in the DATABASE and
-- not only in the script, because neither script takes a lock: two operators running the seeder at
-- the same moment would otherwise publish two "3rd Craft Toolkit Workshop" rows and no client could
-- tell which is current.
CREATE UNIQUE INDEX IF NOT EXISTS "Questionnaire_title_key" ON "Questionnaire"("title");

-- AT MOST ONE DEFAULT, enforced by the database rather than by a read-then-write in Python that two
-- admins clicking at the same moment would interleave.
--
-- A PARTIAL UNIQUE INDEX, WHICH PRISMA CANNOT EXPRESS. It is therefore absent from schema.prisma on
-- purpose and invisible to `prisma migrate deploy`, which is exactly what this repository runs in
-- production and in docker. `prisma migrate dev` — which appears once, in the README's local
-- bootstrap (README.md:83) — does not know about it and would not recreate it on a regenerated
-- baseline. `backend/tests/test_questionnaire_instruments_migration.py` executes THIS FILE and
-- asserts the index exists, so the day somebody regenerates the baseline the test goes red instead
-- of the constraint going missing.
CREATE UNIQUE INDEX IF NOT EXISTS "Questionnaire_isDefault_key"
    ON "Questionnaire"("isDefault") WHERE "isDefault";

CREATE INDEX IF NOT EXISTS "Questionnaire_isActive_sortOrder_idx"
    ON "Questionnaire"("isActive", "sortOrder");

ALTER TABLE "Questionnaire" DROP CONSTRAINT IF EXISTS "Questionnaire_createdById_fkey";
ALTER TABLE "Questionnaire" ADD CONSTRAINT "Questionnaire_createdById_fkey"
    FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;


-- ---------------------------------------------------------------------------------------------
-- 2. THE INSTRUMENT THAT IS ALREADY HERE, NAMED
-- ---------------------------------------------------------------------------------------------
--
-- A LITERAL id rather than a generated one, so the four backfill UPDATEs below need no subquery, so
-- an operator reading a row knows what it is without a join, and so a hand-written SQL repair is
-- possible on a bad night. Rows created through the API still get `@default(cuid())`.
--
-- `isDefault = true`: until an admin says otherwise, an interview that names no instrument belongs
-- to the instrument that every interview before today belonged to. Anything else would silently
-- re-file old fieldwork.
--
-- The creator is resolved as master admin, else the oldest admin, else the oldest account, else
-- NULL. NULL is reachable and is not an error: `migrate deploy` runs BEFORE `seed_admin.py` on a
-- fresh database (README.md:83-84), and a migration whose success depends on seed ORDER is the
-- failure `20260725170000_repair_migration_history_drift` exists to record.
INSERT INTO "Questionnaire" (
    "id", "title", "description", "isActive", "isDefault", "sortOrder",
    "createdById", "createdAt", "updatedAt"
)
SELECT
    'qnr_2nd_craft_toolkit_workshop',
    '2nd Craft Toolkit Workshop',
    'The instrument used at the 2nd Craft Toolkit Workshop: 24 sections, seeded from "2nd Workshop_Interview Questions.docx". Every interview recorded before 2026-09-13 belongs to it.',
    true,
    true,
    1,
    (
        SELECT u."id" FROM "User" u
        ORDER BY
            CASE u."role"::text WHEN 'MASTER_ADMIN' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,
            u."createdAt" ASC
        LIMIT 1
    ),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM "Questionnaire" WHERE "id" = 'qnr_2nd_craft_toolkit_workshop'
);


-- ---------------------------------------------------------------------------------------------
-- 3. THE PARENT KEY, ADDED NULLABLE
-- ---------------------------------------------------------------------------------------------
-- Added nullable first and tightened in step 5, because adding a NOT NULL column to a populated
-- table without a default is an immediate failure. `IF NOT EXISTS` follows the precedent in
-- 20260725170000_repair_migration_history_drift/migration.sql:16-17.

ALTER TABLE "QuestionnaireSection"   ADD COLUMN IF NOT EXISTS "questionnaireId" TEXT;
ALTER TABLE "QuestionnaireQuestion"  ADD COLUMN IF NOT EXISTS "questionnaireId" TEXT;
ALTER TABLE "QuestionnaireInterview" ADD COLUMN IF NOT EXISTS "questionnaireId" TEXT;
ALTER TABLE "Workshop"               ADD COLUMN IF NOT EXISTS "questionnaireId" TEXT;


-- ---------------------------------------------------------------------------------------------
-- 4. THE BACKFILL — every section, every question, every interview, every workshop
-- ---------------------------------------------------------------------------------------------
--
-- All four are the same statement, because there is only one honest answer: the 2nd-workshop
-- instrument is the only instrument that has ever existed in this database. Nothing is inferred,
-- nothing is guessed, and `WHERE "questionnaireId" IS NULL` makes each one a no-op on a re-run.
--
-- QUESTIONS ARE SET DIRECTLY, NOT THROUGH THEIR SECTION. A question's `sectionId` is nullable and a
-- question with a NULL section would be skipped by a join — leaving exactly the rows that most need
-- a parent without one, and failing step 5. Every question in this database belongs to the
-- 2nd-workshop instrument whether or not it currently points at a section.
--
-- WORKSHOPS ARE BACKFILLED RATHER THAN LEFT NULL, and this is a decision, not tidiness. NULL means
-- "not chosen, use the default". If historical workshops were left NULL, the day an admin points
-- the default at the 3rd instrument every past workshop would silently change which instrument it
-- is recorded as having used. Writing the answer down freezes it.
--
-- AND IT IS ONE-SHOT, WHICH THE OTHER THREE ARE NOT. `Workshop.questionnaireId` is the only one of
-- the four that a running system may legitimately set back to NULL — `PUT
-- /api/workshops/{id}/questionnaire` with a null body DETACHES a workshop, and "not chosen" is a
-- real state there. A bare `WHERE "questionnaireId" IS NULL` on a hand re-run of this file would
-- therefore silently re-attach every workshop an admin had deliberately detached, which is the
-- symmetric case to the `isDefault` guard in step 2 and is just as invisible. The marker table below
-- records that the assertion-about-history has already been made, so it is never made twice.
--
-- THE MARKER IS NOT A PRISMA MODEL, deliberately. It is migration bookkeeping, not application
-- state: nothing in `app/` reads it, `prisma migrate deploy` never looks at tables it was not told
-- about, and putting it in schema.prisma would invite a route to start depending on it.

CREATE TABLE IF NOT EXISTS "QuestionnaireMigrationMarker" (
    "name" TEXT NOT NULL,
    "appliedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "QuestionnaireMigrationMarker_pkey" PRIMARY KEY ("name")
);

UPDATE "QuestionnaireSection"   SET "questionnaireId" = 'qnr_2nd_craft_toolkit_workshop' WHERE "questionnaireId" IS NULL;
UPDATE "QuestionnaireQuestion"  SET "questionnaireId" = 'qnr_2nd_craft_toolkit_workshop' WHERE "questionnaireId" IS NULL;
UPDATE "QuestionnaireInterview" SET "questionnaireId" = 'qnr_2nd_craft_toolkit_workshop' WHERE "questionnaireId" IS NULL;

UPDATE "Workshop" SET "questionnaireId" = 'qnr_2nd_craft_toolkit_workshop'
WHERE "questionnaireId" IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM "QuestionnaireMigrationMarker" WHERE "name" = 'workshop_questionnaire_backfill'
  );

INSERT INTO "QuestionnaireMigrationMarker" ("name")
SELECT 'workshop_questionnaire_backfill'
WHERE NOT EXISTS (
    SELECT 1 FROM "QuestionnaireMigrationMarker" WHERE "name" = 'workshop_questionnaire_backfill'
);


-- ---------------------------------------------------------------------------------------------
-- 5. TIGHTEN. Workshop.questionnaireId stays NULLABLE — a workshop created tomorrow has not chosen.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE "QuestionnaireSection" ALTER COLUMN "questionnaireId" SET NOT NULL;
ALTER TABLE "QuestionnaireQuestion" ALTER COLUMN "questionnaireId" SET NOT NULL;
ALTER TABLE "QuestionnaireInterview" ALTER COLUMN "questionnaireId" SET NOT NULL;


-- ---------------------------------------------------------------------------------------------
-- 6. DROP THE GLOBAL UNIQUES
-- ---------------------------------------------------------------------------------------------
-- The three that made one instrument the only instrument, plus two indexes they leave behind:
--   * `QuestionnaireSection_isActive_sortOrder_idx` — superseded by the questionnaire-scoped index
--     in step 7; every read of this table is now scoped by instrument first.
--   * `QuestionnaireSection_code_idx` — created by 20260616120000_questionnaire_sections:21 and
--     NEVER declared in schema.prisma (pre-existing drift). `code` alone no longer identifies
--     anything, and dropping it here removes a drift rather than adding one.

DROP INDEX IF EXISTS "QuestionnaireSection_code_key";
DROP INDEX IF EXISTS "QuestionnaireSection_sortOrder_key";
DROP INDEX IF EXISTS "QuestionnaireSection_isActive_sortOrder_idx";
DROP INDEX IF EXISTS "QuestionnaireSection_code_idx";
DROP INDEX IF EXISTS "QuestionnaireInterview_artisanSetKey_key";


-- ---------------------------------------------------------------------------------------------
-- 7. THE SCOPED UNIQUES AND THE INDEXES THAT REPLACE THEM
-- ---------------------------------------------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS "QuestionnaireSection_questionnaireId_code_key"
    ON "QuestionnaireSection"("questionnaireId", "code");

CREATE UNIQUE INDEX IF NOT EXISTS "QuestionnaireSection_questionnaireId_sortOrder_key"
    ON "QuestionnaireSection"("questionnaireId", "sortOrder");

CREATE INDEX IF NOT EXISTS "QuestionnaireSection_questionnaireId_isActive_sortOrder_idx"
    ON "QuestionnaireSection"("questionnaireId", "isActive", "sortOrder");

CREATE INDEX IF NOT EXISTS "QuestionnaireQuestion_questionnaireId_isActive_idx"
    ON "QuestionnaireQuestion"("questionnaireId", "isActive");

-- ONE INTERVIEW PER ARTISAN SET PER INSTRUMENT. `artisanSetKey` is NULL for artisan-less
-- interviews and Postgres treats NULLs as distinct under a unique index, so several artisan-less
-- interviews per instrument remain allowed — the same exemption the old global index had
-- (20260622120000_questionnaire_artisan_set_idempotent/migration.sql:12-13).
CREATE UNIQUE INDEX IF NOT EXISTS "QuestionnaireInterview_questionnaireId_artisanSetKey_key"
    ON "QuestionnaireInterview"("questionnaireId", "artisanSetKey");

CREATE INDEX IF NOT EXISTS "QuestionnaireInterview_questionnaireId_createdAt_idx"
    ON "QuestionnaireInterview"("questionnaireId", "createdAt");

CREATE INDEX IF NOT EXISTS "Workshop_questionnaireId_idx"
    ON "Workshop"("questionnaireId");


-- ---------------------------------------------------------------------------------------------
-- 8. FOREIGN KEYS
-- ---------------------------------------------------------------------------------------------
-- RESTRICT on the three children: an instrument is the parent of institutional fieldwork and the
-- delete that looks convenient on a list screen takes the whole corpus and a year of answers with
-- it. There is no DELETE route for a questionnaire and this is the database saying so too.
-- SET NULL on Workshop: retiring an instrument must not delete the workshop that used it.

ALTER TABLE "QuestionnaireSection" DROP CONSTRAINT IF EXISTS "QuestionnaireSection_questionnaireId_fkey";
ALTER TABLE "QuestionnaireSection" ADD CONSTRAINT "QuestionnaireSection_questionnaireId_fkey"
    FOREIGN KEY ("questionnaireId") REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireQuestion" DROP CONSTRAINT IF EXISTS "QuestionnaireQuestion_questionnaireId_fkey";
ALTER TABLE "QuestionnaireQuestion" ADD CONSTRAINT "QuestionnaireQuestion_questionnaireId_fkey"
    FOREIGN KEY ("questionnaireId") REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireInterview" DROP CONSTRAINT IF EXISTS "QuestionnaireInterview_questionnaireId_fkey";
ALTER TABLE "QuestionnaireInterview" ADD CONSTRAINT "QuestionnaireInterview_questionnaireId_fkey"
    FOREIGN KEY ("questionnaireId") REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "Workshop" DROP CONSTRAINT IF EXISTS "Workshop_questionnaireId_fkey";
ALTER TABLE "Workshop" ADD CONSTRAINT "Workshop_questionnaireId_fkey"
    FOREIGN KEY ("questionnaireId") REFERENCES "Questionnaire"("id") ON DELETE SET NULL ON UPDATE CASCADE;


-- ---------------------------------------------------------------------------------------------
-- 9. THE ONE FK THAT CHANGES BEHAVIOUR: question -> section, SET NULL becomes RESTRICT
-- ---------------------------------------------------------------------------------------------
-- Created as SET NULL by 20260616120000_questionnaire_sections/migration.sql:61. Nothing in this
-- application hard-deletes a section — DELETE /questionnaire/sections/{id} flips `isActive`
-- (routes/questionnaire.py:396-403) — so this forbids nothing that works today. What it stops is
-- the SILENT version of the failure: under SET NULL a hard delete left the questions invisible to
-- every client (`section_payloads` drops a NULL sectionId) while their answers stayed in the
-- database, findable by nobody. A refusal at the FK is loud and recoverable; an orphan is neither.
--
-- `sectionId` itself stays NULLABLE. `ALTER COLUMN … SET NOT NULL` on production would abort the
-- whole migration on the first orphan and leave a failed `_prisma_migrations` row blocking every
-- later migration — the exact failure 20260726200000_index_coverage/migration.sql:3-34 documents.

ALTER TABLE "QuestionnaireQuestion" DROP CONSTRAINT IF EXISTS "QuestionnaireQuestion_sectionId_fkey";
ALTER TABLE "QuestionnaireQuestion" ADD CONSTRAINT "QuestionnaireQuestion_sectionId_fkey"
    FOREIGN KEY ("sectionId") REFERENCES "QuestionnaireSection"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
