-- Four columns on "QuestionnaireQuestion" and two on "Questionnaire", so that a spreadsheet the
-- pro-forma itself asked an admin to fill in can actually be stored.
--
-- ORDERING IS HARD, NOT ADVISORY. This file ALTERs "Questionnaire", a table created by
-- 20260913100000_questionnaire_instruments. That directory sorts before this one, so
-- `prisma migrate deploy` applies it first and the ordering is automatic — but only on a database
-- that has it. `ADD COLUMN IF NOT EXISTS` covers a missing COLUMN and says nothing about a missing
-- TABLE: against a database where the instruments migration has not run, the statement is
-- `ERROR: relation "Questionnaire" does not exist`, the deploy fails, and a failed `_prisma_migrations`
-- row then blocks EVERY later migration until somebody runs `prisma migrate resolve`. The guard that
-- opens this file turns that into a sentence naming the cause; it does not remove the requirement. (20260726200000_index_coverage/migration.sql:3-34 records the same failure shape
-- arrived at from CREATE INDEX CONCURRENTLY, which is why there is none here either: this index is
-- on a table of a few hundred rows and the concurrent form cannot run inside Prisma's implicit
-- transaction.)
--
-- WHY helpText AND isRequired. app/services/questionnaire_xlsx.py writes a "Help text" column and a
-- "Required" column into every pro-forma it generates (`_question_columns`) and parses both back out
-- of an upload (`_read_questions`). Without somewhere to put them the import would read two columns
-- an admin had typed into and silently throw the values away — which is worse than the silent drop
-- that module's docstring forbids ("a row this parser cannot read is REPORTED, never dropped"),
-- because there is no row-level failure to report. The app asked the question and then ignored the
-- answer.
--
-- WHY retiredAt, GIVEN THAT isActive ALREADY EXISTS. Because isActive already has an owner.
-- DELETE /questionnaire/questions/{id} (app/api/routes/questionnaire.py) soft-deletes by writing
-- isActive = false and nothing else, and DELETE /questionnaire/sections/{id} does the same to every
-- question under the section. So isActive alone cannot distinguish "a professor switched this
-- question off last March" from "this question was retired because answers were already recorded
-- against it". The re-upload path needs that distinction and cannot fake it: it REACTIVATES a
-- question it finds named in the workbook, and it must NOT reactivate a retired one, or downloading
-- a questionnaire and uploading it back unchanged resurrects every question anybody ever replaced,
-- each one standing next to its replacement, in the instrument forty researchers are answering that
-- week.
--
-- WHY supersededById, AS A PLAIN COLUMN. When an answered question is reworded, the old wording is
-- kept with its answers and the new wording is stored as a new row. With no link between the two,
-- app/services/questionnaire_consolidation.py prints them as two unrelated questions in the
-- consolidated export — the same question asked twice, with half the answers filed under each, and
-- nothing on the page saying they are the same question. It is only ever read, to render that link,
-- so it is a String rather than a Prisma self-relation: a self-relation costs a join on every
-- question read for one string on one screen.
--
-- AND NO FOREIGN KEY ON IT, DELIBERATELY. The row it names is in the same table and cannot be gone
-- while it is named: a replacement is created active, and an active question that has collected an
-- answer cannot be deleted, because "QuestionnaireResponse_questionId_fkey" is ON DELETE RESTRICT
-- (prisma/schema.prisma, QuestionnaireResponse.question). A FK here would buy nothing and would turn
-- the replacement's own eventual retirement into a constraint error on a column that is
-- documentation.
--
-- WHY "Questionnaire"."version" IS HERE RATHER THAN IN THE INSTRUMENTS MIGRATION. Because nothing
-- moved it before this change: the number counts supersedes and retires, and neither existed until
-- the columns above did. A client holding a cached form detects staleness with an integer compare
-- instead of a question diff, and "sourceFilename" is what the admin screen shows so two workbooks a
-- fortnight apart are tellable apart. Both are written with IF NOT EXISTS so that this file is a
-- no-op against a database where a future edit to the instruments migration has already declared
-- them — the same shape 20260725170000_repair_migration_history_drift used, for the same reason.

-- THE ORDERING GUARD, AND IT IS THE FIRST STATEMENT SO THAT NOTHING ELSE IS ATTEMPTED. It raises a
-- sentence naming the cause instead of `relation "Questionnaire" does not exist`, which reads as a
-- corrupt database rather than as two migrations applied out of order. Prisma runs a migration file
-- in one implicit transaction, so a failure here leaves the four columns below unapplied as well —
-- the file is all-or-nothing either way; this only decides which message the operator gets.
DO $$
BEGIN
  IF to_regclass('"Questionnaire"') IS NULL THEN
    RAISE EXCEPTION
      'Migration 20260913110000_questionnaire_workbook_columns needs the "Questionnaire" table, which migration 20260913100000_questionnaire_instruments creates. Apply that one first (prisma migrate deploy applies them in directory order, so this normally means the instruments migration was skipped or rolled back).';
  END IF;
END
$$;

-- AlterTable
ALTER TABLE "QuestionnaireQuestion" ADD COLUMN "helpText" TEXT;
ALTER TABLE "QuestionnaireQuestion" ADD COLUMN "isRequired" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "QuestionnaireQuestion" ADD COLUMN "retiredAt" TIMESTAMP(3);
ALTER TABLE "QuestionnaireQuestion" ADD COLUMN "supersededById" TEXT;

-- CreateIndex
CREATE INDEX "QuestionnaireQuestion_supersededById_idx" ON "QuestionnaireQuestion"("supersededById");

-- AlterTable
ALTER TABLE "Questionnaire" ADD COLUMN IF NOT EXISTS "version" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "Questionnaire" ADD COLUMN IF NOT EXISTS "sourceFilename" TEXT;

-- THERE IS DELIBERATELY NO BACKFILL, AND THAT IS A DECISION RATHER THAN AN OMISSION.
--
-- Every question in this database that is currently inactive was switched off by a person through
-- the builder, because the rule that sets retiredAt does not exist until this migration lands. So
-- NULL is the correct and honest value on every one of those rows: they were not retired, they were
-- deactivated, and the re-upload path is entitled to reactivate them if a workbook names them again.
-- Writing a timestamp onto them would freeze a question nobody ever answered, for ever, on the
-- strength of a guess.
--
-- isRequired defaults to false on every existing row for the same reason: the instrument has never
-- had a Required flag, so "not required" is what every question has always meant. A default of true
-- would make eighty-one questions mandatory overnight on a form researchers are part-way through.
--
-- "version" defaults to 1 on every existing instrument rather than to a count derived from anything,
-- because no supersede has ever happened: the first number a client sees is the first number it can
-- compare against, and that is all the column is for.
