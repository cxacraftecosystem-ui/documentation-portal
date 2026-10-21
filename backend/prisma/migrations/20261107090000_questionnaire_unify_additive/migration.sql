-- QUESTIONNAIRE UNIFICATION, U-M0 (C:/dev/IIT/docs/questionnaire/UNIFY_2026-10-21.md section 3.1),
-- Field Repository's half. Designer-portal's half is the migration of the same name there; every
-- table this migration creates is created there with the same columns, keys and checks, and the
-- model blocks in schema.prisma are byte-identical (shared/questionnaire.prisma,
-- tests/test_questionnaire_schema_parity.py).
--
-- ADDITIVE AND LIVE-SAFE. New enums, new tables, new nullable or defaulted columns, one relaxed NOT
-- NULL (QuestionnaireAnswer."artisanId": an answer is now an artisan's OR a guest respondent's, with
-- a CHECK that exactly one is set, so every existing row stays valid), the take view recreated with
-- two trailing columns and the live-session filter (no session is deleted yet, so it returns exactly
-- the rows it returned before), and triggers that keep the bindings and Workshop."questionnaireId" in
-- step. Nothing here changes what the app does until a route uses it.
--
-- NOT HERE, deliberately: the partial title unique (title WHERE "isShared" AND "deletedAt" IS NULL)
-- replacing "Questionnaire_title_key". Field Repository reads forms by title, and loosening that key is
-- the U5 migration's, with its dry run.

-- ── enums ──────────────────────────────────────────────────────────────────────────────────────
CREATE TYPE "RespondentKind" AS ENUM ('GUEST', 'SELF');
CREATE TYPE "SessionSource" AS ENUM ('APP', 'UPLOAD', 'HANDOFF');

-- ── forms: author, sharing, kind, restorable soft delete ───────────────────────────────────────
ALTER TABLE "Questionnaire"
  ADD COLUMN "ownerId" TEXT,
  ADD COLUMN "isShared" BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN "kind" TEXT,
  ADD COLUMN "deletedAt" TIMESTAMP(3),
  ADD COLUMN "deletedById" TEXT;
-- The author is whoever created the form; Field Repository's instruments were made for everyone.
UPDATE "Questionnaire" SET "ownerId" = "createdById" WHERE "ownerId" IS NULL;
UPDATE "Questionnaire" SET "isShared" = true;
CREATE INDEX "Questionnaire_ownerId_idx" ON "Questionnaire"("ownerId");
CREATE INDEX "Questionnaire_deletedAt_idx" ON "Questionnaire"("deletedAt");

-- ── sessions: restorable soft delete and where the sitting came from ───────────────────────────
ALTER TABLE "QuestionnaireInterview"
  ADD COLUMN "deletedAt" TIMESTAMP(3),
  ADD COLUMN "deletedById" TEXT,
  ADD COLUMN "source" "SessionSource" NOT NULL DEFAULT 'APP';
CREATE INDEX "QuestionnaireInterview_deletedAt_idx" ON "QuestionnaireInterview"("deletedAt");

-- ── guest respondents ──────────────────────────────────────────────────────────────────────────
CREATE TABLE "QuestionnaireRespondent" (
  "id" TEXT NOT NULL,
  "sessionId" TEXT NOT NULL REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "name" TEXT NOT NULL,
  "kind" "RespondentKind" NOT NULL DEFAULT 'GUEST',
  "userId" TEXT,
  "notes" TEXT,
  "artisanId" TEXT REFERENCES "Artisan"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "registeredAt" TIMESTAMP(3),
  "createdById" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "QuestionnaireRespondent_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "QuestionnaireRespondent_name_len" CHECK (char_length("name") BETWEEN 1 AND 120)
);
CREATE INDEX "QuestionnaireRespondent_sessionId_idx" ON "QuestionnaireRespondent"("sessionId");
CREATE INDEX "QuestionnaireRespondent_artisanId_idx" ON "QuestionnaireRespondent"("artisanId");

-- A guest on a take, beside the artisan subjects (whose key is (recordingId, artisanId)).
CREATE TABLE "QuestionnaireRecordingRespondent" (
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "respondentId" TEXT NOT NULL REFERENCES "QuestionnaireRespondent"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "role" "SubjectRole" NOT NULL,
  "named" BOOLEAN NOT NULL DEFAULT false,
  "repeatReason" "RepeatReason",
  "flag" "TakeFlag",
  CONSTRAINT "QuestionnaireRecordingRespondent_pkey" PRIMARY KEY ("recordingId", "respondentId")
);
CREATE INDEX "QuestionnaireRecordingRespondent_respondentId_idx" ON "QuestionnaireRecordingRespondent"("respondentId");

-- An answer is an artisan's OR a guest's, never both, never neither; one per owner per lineage.
ALTER TABLE "QuestionnaireAnswer"
  ALTER COLUMN "artisanId" DROP NOT NULL,
  ADD COLUMN "respondentId" TEXT REFERENCES "QuestionnaireRespondent"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  ADD CONSTRAINT "QuestionnaireAnswer_one_owner" CHECK (num_nonnulls("artisanId", "respondentId") = 1);
CREATE UNIQUE INDEX "QuestionnaireAnswer_respondentId_questionLineageId_key"
  ON "QuestionnaireAnswer"("respondentId", "questionLineageId");

-- The answer note (A20) lives on the revision.
ALTER TABLE "QuestionnaireAnswerRevision" ADD COLUMN "note" TEXT;

-- ── bindings: several forms per workshop, one primary ──────────────────────────────────────────
CREATE TABLE "QuestionnaireBinding" (
  "id" TEXT NOT NULL,
  "questionnaireId" TEXT NOT NULL REFERENCES "Questionnaire"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "workshopId" TEXT REFERENCES "Workshop"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "designWorkshopId" TEXT,
  "isPrimary" BOOLEAN NOT NULL DEFAULT false,
  "createdById" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireBinding_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "QuestionnaireBinding_one_workshop" CHECK (num_nonnulls("workshopId", "designWorkshopId") = 1)
);
CREATE UNIQUE INDEX "QuestionnaireBinding_questionnaireId_workshopId_key" ON "QuestionnaireBinding"("questionnaireId", "workshopId");
CREATE UNIQUE INDEX "QuestionnaireBinding_questionnaireId_designWorkshopId_key" ON "QuestionnaireBinding"("questionnaireId", "designWorkshopId");
CREATE UNIQUE INDEX "QuestionnaireBinding_one_primary_workshop" ON "QuestionnaireBinding"("workshopId") WHERE "isPrimary" AND "workshopId" IS NOT NULL;
CREATE UNIQUE INDEX "QuestionnaireBinding_one_primary_design_workshop" ON "QuestionnaireBinding"("designWorkshopId") WHERE "isPrimary" AND "designWorkshopId" IS NOT NULL;
CREATE INDEX "QuestionnaireBinding_workshopId_idx" ON "QuestionnaireBinding"("workshopId");
CREATE INDEX "QuestionnaireBinding_designWorkshopId_idx" ON "QuestionnaireBinding"("designWorkshopId");

-- Today's one-instrument-per-workshop becomes that workshop's primary binding.
INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "isPrimary")
SELECT gen_random_uuid()::text, w."questionnaireId", w."id", true
  FROM "Workshop" w WHERE w."questionnaireId" IS NOT NULL
ON CONFLICT DO NOTHING;

-- THE MIRROR, until the cleanup migration drops Workshop."questionnaireId": the old admin route
-- (PUT /workshops/{id}/questionnaire) and the binding routes each keep the other in step. Every
-- write is guarded by IS DISTINCT FROM, so the two triggers settle after one round.
CREATE FUNCTION questionnaire_binding_from_workshop() RETURNS trigger AS $$
BEGIN
  IF NEW."questionnaireId" IS NOT DISTINCT FROM OLD."questionnaireId" THEN RETURN NEW; END IF;
  DELETE FROM "QuestionnaireBinding"
   WHERE "workshopId" = NEW."id" AND "isPrimary"
     AND "questionnaireId" IS DISTINCT FROM NEW."questionnaireId";
  IF NEW."questionnaireId" IS NOT NULL THEN
    UPDATE "QuestionnaireBinding" SET "isPrimary" = true
     WHERE "workshopId" = NEW."id" AND "questionnaireId" = NEW."questionnaireId" AND NOT "isPrimary";
    INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "isPrimary")
    VALUES (gen_random_uuid()::text, NEW."questionnaireId", NEW."id", true)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_binding_from_workshop AFTER UPDATE OF "questionnaireId" ON "Workshop"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_binding_from_workshop();

CREATE FUNCTION questionnaire_workshop_from_binding() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD."workshopId" IS NOT NULL AND OLD."isPrimary" THEN
      UPDATE "Workshop" SET "questionnaireId" = NULL
       WHERE "id" = OLD."workshopId" AND "questionnaireId" = OLD."questionnaireId";
    END IF;
    RETURN OLD;
  END IF;
  IF NEW."workshopId" IS NOT NULL AND NEW."isPrimary" THEN
    UPDATE "Workshop" SET "questionnaireId" = NEW."questionnaireId"
     WHERE "id" = NEW."workshopId" AND "questionnaireId" IS DISTINCT FROM NEW."questionnaireId";
  ELSIF TG_OP = 'UPDATE' AND NEW."workshopId" IS NOT NULL AND OLD."isPrimary" AND NOT NEW."isPrimary" THEN
    UPDATE "Workshop" SET "questionnaireId" = NULL
     WHERE "id" = NEW."workshopId" AND "questionnaireId" = NEW."questionnaireId";
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_workshop_from_binding AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireBinding"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_workshop_from_binding();

-- ── a default form per workshop type (owner, amendment 2 of 22 Oct 2026, point 2) ──────────────
CREATE TABLE "QuestionnaireTypeDefault" (
  "id" TEXT NOT NULL,
  "questionnaireId" TEXT NOT NULL REFERENCES "Questionnaire"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "workshopType" "WorkshopType",
  "designWorkshopKind" TEXT,
  "setById" TEXT NOT NULL,
  "setAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireTypeDefault_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "QuestionnaireTypeDefault_one_type" CHECK (num_nonnulls("workshopType", "designWorkshopKind") = 1)
);
CREATE UNIQUE INDEX "QuestionnaireTypeDefault_workshopType_key" ON "QuestionnaireTypeDefault"("workshopType");
CREATE UNIQUE INDEX "QuestionnaireTypeDefault_designWorkshopKind_key" ON "QuestionnaireTypeDefault"("designWorkshopKind");

-- ── coverage view: live sessions only, with the subject key and the design workshop ────────────
-- The same definition in both portals. A soft-deleted session hides its takes (owner decision 3).
DROP VIEW IF EXISTS questionnaire_take_v;
CREATE VIEW questionnaire_take_v AS
WITH RECURSIVE live AS (
  SELECT r.*, m."questionnaireInterviewId" AS "sessionId", m."createdAt" AS "mediaCreatedAt",
         m."contentSha256", i."questionnaireId", i."workshopId", i."designWorkshopId"
    FROM "QuestionnaireRecording" r
    JOIN "MediaFile" m ON m."id" = r."mediaFileId"
    JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId"
   WHERE r."removedAt" IS NULL AND m."removedAt" IS NULL AND i."deletedAt" IS NULL
),
roots AS (
  SELECT l."id", l."id" AS "rootId" FROM live l WHERE l."continuationOfId" IS NULL
  UNION ALL
  SELECT l."id", roots."rootId" FROM live l JOIN roots ON l."continuationOfId" = roots."id"
),
takes AS (
  SELECT l."id" AS "recordingId", COALESCE(ro."rootId", l."id") AS "rootRecordingId",
         l."sessionId", l."questionnaireId", l."workshopId", s."artisanId", t."targetKey",
         t."sectionId", t."questionLineageId", t."askedQuestionId", l."capturedAt", l."capturedAtSource",
         l."filing", l."continuationOfId", l."rosterCount", l."recordedById", l."durationMs",
         l."changeSeq", l."mediaFileId", l."mediaCreatedAt", s."repeatReason", s."flag", s."named",
         l."designWorkshopId", 'A:' || s."artisanId" AS "subjectKey"
    FROM live l
    LEFT JOIN roots ro ON ro."id" = l."id"
    JOIN "QuestionnaireRecordingTarget" t ON t."recordingId" = l."id"
    JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = l."id" AND s."role" = 'ANSWERED'
),
ranked AS (
  SELECT tk."recordingId", tk."artisanId", tk."targetKey",
         ROW_NUMBER() OVER (PARTITION BY tk."artisanId", tk."targetKey"
                            ORDER BY tk."capturedAt", tk."mediaCreatedAt", tk."recordingId") AS "position"
    FROM takes tk WHERE tk."continuationOfId" IS NULL
)
SELECT tk.*, rk."position"
  FROM takes tk
  LEFT JOIN ranked rk ON rk."recordingId" = tk."rootRecordingId"
                     AND rk."artisanId" = tk."artisanId" AND rk."targetKey" = tk."targetKey";

-- ── Supabase Data API: the new tables are server-only, like every other ────────────────────────
ALTER TABLE "QuestionnaireRespondent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRecordingRespondent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireBinding" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireTypeDefault" ENABLE ROW LEVEL SECURITY;
