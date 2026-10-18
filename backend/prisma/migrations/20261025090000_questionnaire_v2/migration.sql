-- QUESTIONNAIRE V2, M1 (C:/dev/IIT/docs/questionnaire/DESIGN_2026-10-18.md sections 2 and 7.1).
--
-- ADDITIVE AND LIVE-SAFE: new enums, new tables, new nullable or defaulted columns, one sequence, one
-- view and three triggers. Two changes touch existing rows, both checked before they run:
--
--   * the unique (questionnaireId, artisanSetKey) is DROPPED and a plain index takes its place. That
--     only loosens a rule (decision D1: a session is a dated event);
--   * QuestionnaireSectionStatus.status becomes the enum SectionStatus. The cast runs only when every
--     live value is already one of the three; otherwise the migration stops with the offending count
--     and nothing has changed.
--
-- BACKFILLED HERE because they are derived from the rows themselves and nothing else reads them yet:
-- lineageId and displayCode on every question, MediaFile.contentSha256 from checksum, and every
-- QuestionnaireResponse row (0 on production on 18 Oct) into a canonical answer plus one revision
-- per participant. The legacy SESSION backfill (dates, recordings, titles) is NOT here: it is
-- backend/scripts/questionnaire_v2_backfill.py, dry-run first, run after deploy with the owner's
-- approval.
--
-- NOT CHANGED HERE, deliberately (see the build report): MediaFile.transcriptStatus stays TEXT (live
-- values include UNAVAILABLE, which the shared TranscriptStatus list does not have) and
-- QuestionnaireQuestion.artisanField stays TEXT (live values spell the sitting date "interviewDate"
-- and old builders still send it). Both are validated against shared/enums.json in the application
-- and become enums with the M4 cleanup, when the old clients have gone.

-- ── enums ──────────────────────────────────────────────────────────────────────────────────────
CREATE TYPE "SessionState" AS ENUM ('OPEN', 'CLOSED');
CREATE TYPE "SessionPurpose" AS ENUM ('FIRST_SITTING', 'FOLLOW_UP', 'REDO');
CREATE TYPE "SessionOrigin" AS ENUM ('NATIVE', 'LEGACY_RECONSTRUCTED');
CREATE TYPE "ResearcherRole" AS ENUM ('LEAD', 'CONTRIBUTOR', 'VOLUNTEER');
CREATE TYPE "SubjectRole" AS ENUM ('ANSWERED', 'PRESENT');
CREATE TYPE "RepeatReason" AS ENUM ('REDO', 'FOLLOW_UP', 'RE_INTERVIEW', 'NEW_SETTING');
CREATE TYPE "TakeFlag" AS ENUM ('CONCURRENT', 'LATE_ARRIVAL', 'AFTER_CLOSE', 'LEGACY');
CREATE TYPE "CapturedAtSource" AS ENUM ('DEVICE', 'FILE_METADATA', 'SESSION_DATE', 'UPLOAD_TIME', 'UPLOAD_TIME_OUT_OF_WINDOW');
CREATE TYPE "RecordingFiling" AS ENUM ('FILED', 'NEEDS_DECISION');
CREATE TYPE "RecordingProvenance" AS ENUM ('NATIVE', 'SHIM', 'LEGACY_META', 'LEGACY_CAPTION_TEXT', 'LEGACY_FILENAME', 'MANUAL');
CREATE TYPE "AnswerOrigin" AS ENUM ('TYPED', 'TRANSCRIPT', 'MACHINE_SUGGESTED', 'MACHINE_SUGGESTED_EDITED', 'PREFILL');
CREATE TYPE "AnswerAttribution" AS ENUM ('INDIVIDUAL', 'GROUP_SHARED');
CREATE TYPE "SectionStatus" AS ENUM ('COMPLETED', 'NEEDS_REVIEW', 'NEEDS_REDO');
ALTER TYPE "MediaProcessingJobType" ADD VALUE IF NOT EXISTS 'SHA_VERIFY';

-- ── section status: cast only over values that already conform ─────────────────────────────────
DO $$
DECLARE bad integer;
BEGIN
  SELECT count(*) INTO bad FROM "QuestionnaireSectionStatus"
   WHERE "status" NOT IN ('COMPLETED', 'NEEDS_REVIEW', 'NEEDS_REDO');
  IF bad > 0 THEN
    RAISE EXCEPTION 'QuestionnaireSectionStatus has % row(s) outside COMPLETED/NEEDS_REVIEW/NEEDS_REDO; fix them before M1', bad;
  END IF;
END $$;
ALTER TABLE "QuestionnaireSectionStatus"
  ALTER COLUMN "status" TYPE "SectionStatus" USING ("status"::"SectionStatus");

-- ── MediaFile ──────────────────────────────────────────────────────────────────────────────────
ALTER TABLE "MediaFile"
  ADD COLUMN "removedAt" TIMESTAMP(3),
  ADD COLUMN "removedById" TEXT,
  ADD COLUMN "contentSha256" TEXT,
  ADD COLUMN "shaVerifiedAt" TIMESTAMP(3),
  ADD COLUMN "consentAtSend" "ConsentState";
UPDATE "MediaFile" SET "contentSha256" = lower("checksum")
 WHERE "checksum" ~* '^[0-9a-f]{64}$' AND "contentSha256" IS NULL;
CREATE INDEX "MediaFile_contentSha256_idx" ON "MediaFile"("contentSha256");

-- ── QuestionnaireQuestion: lineage and frozen display codes ────────────────────────────────────
ALTER TABLE "QuestionnaireQuestion"
  ADD COLUMN "lineageId" TEXT,
  ADD COLUMN "displayCode" TEXT;

-- lineage = the root of the supersede chain (a row nobody supersedes into)
WITH RECURSIVE chain AS (
  SELECT q."id" AS root, q."id" AS id
    FROM "QuestionnaireQuestion" q
   WHERE NOT EXISTS (SELECT 1 FROM "QuestionnaireQuestion" p WHERE p."supersededById" = q."id")
  UNION ALL
  SELECT c.root, q."supersededById"
    FROM chain c JOIN "QuestionnaireQuestion" q ON q."id" = c.id
   WHERE q."supersededById" IS NOT NULL
)
UPDATE "QuestionnaireQuestion" q SET "lineageId" = chain.root FROM chain WHERE chain.id = q."id";
UPDATE "QuestionnaireQuestion" SET "lineageId" = "id" WHERE "lineageId" IS NULL;

-- code = section code + the lineage root's position in its section at backfill, then frozen
WITH roots AS (
  SELECT q."id", q."questionnaireId", upper(q."sectionCode") AS sec,
         ROW_NUMBER() OVER (PARTITION BY q."questionnaireId", upper(q."sectionCode")
                            ORDER BY q."sortOrder", q."createdAt", q."id") AS n
    FROM "QuestionnaireQuestion" q WHERE q."lineageId" = q."id"
)
UPDATE "QuestionnaireQuestion" q SET "displayCode" = roots.sec || roots.n::text
  FROM roots WHERE roots."id" = q."lineageId";

CREATE INDEX "QuestionnaireQuestion_lineageId_idx" ON "QuestionnaireQuestion"("lineageId");
CREATE UNIQUE INDEX "QuestionnaireQuestion_live_displayCode_key"
  ON "QuestionnaireQuestion"("questionnaireId", "displayCode")
  WHERE "retiredAt" IS NULL AND "supersededById" IS NULL;

-- New rows (builder, workbook import, seeds) get their lineage and next code from the database, so
-- every writer that predates v2 keeps working unchanged.
CREATE FUNCTION questionnaire_question_lineage_ins() RETURNS trigger AS $$
DECLARE next_n integer;
BEGIN
  IF NEW."lineageId" IS NULL THEN NEW."lineageId" := NEW."id"; END IF;
  IF NEW."displayCode" IS NULL THEN
    SELECT COALESCE(max(substring(q."displayCode" FROM '[0-9]+$')::integer), 0) + 1 INTO next_n
      FROM "QuestionnaireQuestion" q
     WHERE q."questionnaireId" = NEW."questionnaireId"
       AND q."displayCode" ~ ('^' || upper(NEW."sectionCode") || '[0-9]+$');
    NEW."displayCode" := upper(NEW."sectionCode") || next_n::text;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_question_lineage_ins BEFORE INSERT ON "QuestionnaireQuestion"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_question_lineage_ins();

-- A reword (supersededById written on the old row) carries the lineage and the frozen code across.
CREATE FUNCTION questionnaire_question_lineage_upd() RETURNS trigger AS $$
BEGIN
  IF NEW."supersededById" IS NOT NULL AND NEW."supersededById" IS DISTINCT FROM OLD."supersededById" THEN
    UPDATE "QuestionnaireQuestion"
       SET "lineageId" = NEW."lineageId", "displayCode" = NEW."displayCode"
     WHERE "id" = NEW."supersededById";
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_question_lineage_upd AFTER UPDATE OF "supersededById" ON "QuestionnaireQuestion"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_question_lineage_upd();

-- ── QuestionnaireInterview: the session ────────────────────────────────────────────────────────
ALTER TABLE "QuestionnaireInterview"
  ADD COLUMN "clientKey" TEXT,
  ADD COLUMN "joinCode" TEXT,
  ADD COLUMN "sessionDate" DATE,
  ADD COLUMN "sessionDateEstimated" BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN "startedAt" TIMESTAMP(3),
  ADD COLUMN "endedAt" TIMESTAMP(3),
  ADD COLUMN "closedAt" TIMESTAMP(3),
  ADD COLUMN "state" "SessionState" NOT NULL DEFAULT 'OPEN',
  ADD COLUMN "purpose" "SessionPurpose" NOT NULL DEFAULT 'FIRST_SITTING',
  ADD COLUMN "origin" "SessionOrigin" NOT NULL DEFAULT 'NATIVE',
  ADD COLUMN "note" TEXT,
  ADD COLUMN "legacyTitle" TEXT,
  ADD COLUMN "languageCode" TEXT,
  ADD COLUMN "languageOther" TEXT,
  ADD COLUMN "designWorkshopId" TEXT,
  ADD COLUMN "mergedIntoId" TEXT,
  ADD COLUMN "version" INTEGER NOT NULL DEFAULT 1,
  ADD CONSTRAINT "QuestionnaireInterview_one_workshop_kind"
    CHECK (num_nonnulls("workshopId", "designWorkshopId") <= 1),
  ADD CONSTRAINT "QuestionnaireInterview_note_len" CHECK (char_length("note") <= 80);
CREATE UNIQUE INDEX "QuestionnaireInterview_clientKey_key" ON "QuestionnaireInterview"("clientKey");
CREATE UNIQUE INDEX "QuestionnaireInterview_joinCode_key" ON "QuestionnaireInterview"("joinCode");
CREATE INDEX "QuestionnaireInterview_sessionDate_idx" ON "QuestionnaireInterview"("sessionDate");
CREATE INDEX "QuestionnaireInterview_state_startedAt_idx" ON "QuestionnaireInterview"("state", "startedAt");
DROP INDEX IF EXISTS "QuestionnaireInterview_questionnaireId_artisanSetKey_key";
CREATE INDEX "QuestionnaireInterview_questionnaireId_artisanSetKey_idx"
  ON "QuestionnaireInterview"("questionnaireId", "artisanSetKey");

ALTER TABLE "QuestionnaireInterviewArtisan"
  ADD COLUMN "sortOrder" INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN "joinedAt" TIMESTAMP(3),
  ADD COLUMN "leftAt" TIMESTAMP(3);

CREATE TABLE "QuestionnaireInterviewResearcher" (
  "interviewId" TEXT NOT NULL REFERENCES "QuestionnaireInterview"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "userId" TEXT NOT NULL REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "role" "ResearcherRole" NOT NULL,
  "addedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireInterviewResearcher_pkey" PRIMARY KEY ("interviewId", "userId")
);
CREATE INDEX "QuestionnaireInterviewResearcher_userId_idx" ON "QuestionnaireInterviewResearcher"("userId");

-- ── recordings, targets, subjects, events ──────────────────────────────────────────────────────
CREATE SEQUENCE questionnaire_change_seq;

CREATE TABLE "QuestionnaireRecording" (
  "id" TEXT NOT NULL,
  "mediaFileId" TEXT NOT NULL REFERENCES "MediaFile"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "clientClipKey" TEXT NOT NULL,
  "deviceCapturedAt" TIMESTAMP(3),
  "clockOffsetMs" INTEGER,
  "capturedAt" TIMESTAMP(3) NOT NULL,
  "capturedAtSource" "CapturedAtSource" NOT NULL,
  "durationMs" INTEGER,
  "rosterCount" INTEGER NOT NULL,
  "recordedById" TEXT NOT NULL REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "continuationOfId" TEXT REFERENCES "QuestionnaireRecording"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "replacesRecordingId" TEXT REFERENCES "QuestionnaireRecording"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "ackedRecordingIds" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  "filing" "RecordingFiling" NOT NULL DEFAULT 'FILED',
  "decisionKind" TEXT,
  "provenance" "RecordingProvenance" NOT NULL,
  "languageCode" TEXT,
  "removedAt" TIMESTAMP(3),
  "removedById" TEXT,
  "changeSeq" BIGINT NOT NULL DEFAULT nextval('questionnaire_change_seq'::regclass),
  "version" INTEGER NOT NULL DEFAULT 1,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "QuestionnaireRecording_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "QuestionnaireRecording_mediaFileId_key" ON "QuestionnaireRecording"("mediaFileId");
CREATE UNIQUE INDEX "QuestionnaireRecording_clientClipKey_key" ON "QuestionnaireRecording"("clientClipKey");
CREATE INDEX "QuestionnaireRecording_changeSeq_idx" ON "QuestionnaireRecording"("changeSeq");
CREATE INDEX "QuestionnaireRecording_continuationOfId_idx" ON "QuestionnaireRecording"("continuationOfId");

CREATE TABLE "QuestionnaireRecordingTarget" (
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "targetKey" TEXT NOT NULL,
  "sectionId" TEXT REFERENCES "QuestionnaireSection"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "questionLineageId" TEXT REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "askedQuestionId" TEXT REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT "QuestionnaireRecordingTarget_pkey" PRIMARY KEY ("recordingId", "targetKey"),
  CONSTRAINT "QuestionnaireRecordingTarget_one_kind" CHECK (num_nonnulls("sectionId", "questionLineageId") = 1)
);
CREATE INDEX "QuestionnaireRecordingTarget_questionLineageId_idx" ON "QuestionnaireRecordingTarget"("questionLineageId");
CREATE INDEX "QuestionnaireRecordingTarget_sectionId_idx" ON "QuestionnaireRecordingTarget"("sectionId");

CREATE TABLE "QuestionnaireRecordingSubject" (
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "artisanId" TEXT NOT NULL REFERENCES "Artisan"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "role" "SubjectRole" NOT NULL,
  "named" BOOLEAN NOT NULL DEFAULT false,
  "repeatReason" "RepeatReason",
  "flag" "TakeFlag",
  CONSTRAINT "QuestionnaireRecordingSubject_pkey" PRIMARY KEY ("recordingId", "artisanId")
);
CREATE INDEX "QuestionnaireRecordingSubject_artisanId_idx" ON "QuestionnaireRecordingSubject"("artisanId");

CREATE TABLE "QuestionnaireRecordingEvent" (
  "id" TEXT NOT NULL,
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "kind" TEXT NOT NULL,
  "before" JSONB,
  "after" JSONB,
  "actorId" TEXT NOT NULL,
  "at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireRecordingEvent_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "QuestionnaireRecordingEvent_recordingId_at_idx" ON "QuestionnaireRecordingEvent"("recordingId", "at");

-- ── answers ────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE "QuestionnaireAnswer" (
  "id" TEXT NOT NULL,
  "artisanId" TEXT NOT NULL REFERENCES "Artisan"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "questionnaireId" TEXT NOT NULL REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "questionLineageId" TEXT NOT NULL REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "currentRevisionId" TEXT,
  "pinned" BOOLEAN NOT NULL DEFAULT false,
  "version" INTEGER NOT NULL DEFAULT 1,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "QuestionnaireAnswer_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "QuestionnaireAnswer_artisanId_questionLineageId_key" ON "QuestionnaireAnswer"("artisanId", "questionLineageId");
CREATE INDEX "QuestionnaireAnswer_questionnaireId_idx" ON "QuestionnaireAnswer"("questionnaireId");

CREATE TABLE "QuestionnaireAnswerRevision" (
  "id" TEXT NOT NULL,
  "answerId" TEXT NOT NULL REFERENCES "QuestionnaireAnswer"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "text" TEXT NOT NULL,
  "languageCode" TEXT,
  "origin" "AnswerOrigin" NOT NULL,
  "attribution" "AnswerAttribution" NOT NULL,
  "askedQuestionId" TEXT NOT NULL REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "sessionId" TEXT NOT NULL REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "sourceRecordingId" TEXT REFERENCES "QuestionnaireRecording"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "sourceTranscriptHash" TEXT,
  "sourceFieldValue" TEXT,
  "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
  "actorId" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireAnswerRevision_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "QuestionnaireAnswerRevision_answerId_createdAt_idx" ON "QuestionnaireAnswerRevision"("answerId", "createdAt");
CREATE INDEX "QuestionnaireAnswerRevision_sessionId_idx" ON "QuestionnaireAnswerRevision"("sessionId");

CREATE TABLE "QuestionnaireTakeDraft" (
  "id" TEXT NOT NULL,
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "questionLineageId" TEXT NOT NULL,
  "artisanId" TEXT NOT NULL DEFAULT '',
  "text" TEXT NOT NULL,
  "origin" "AnswerOrigin" NOT NULL,
  "model" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireTakeDraft_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "QuestionnaireTakeDraft_recording_lineage_artisan_key"
  ON "QuestionnaireTakeDraft"("recordingId", "questionLineageId", "artisanId");

-- ── upload intents ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE "MediaUploadIntent" (
  "id" TEXT NOT NULL,
  "objectKey" TEXT NOT NULL,
  "clientClipKey" TEXT NOT NULL,
  "sessionId" TEXT NOT NULL,
  "uploaderId" TEXT NOT NULL,
  "sizeBytes" BIGINT NOT NULL,
  "contentType" TEXT NOT NULL,
  "contentSha256" TEXT,
  "multipartUploadId" TEXT,
  "partSize" INTEGER,
  "expiresAt" TIMESTAMP(3) NOT NULL,
  "closedAt" TIMESTAMP(3),
  "mediaFileId" TEXT,
  "meta" JSONB,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "MediaUploadIntent_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "MediaUploadIntent_objectKey_key" ON "MediaUploadIntent"("objectKey");
CREATE UNIQUE INDEX "MediaUploadIntent_clientClipKey_key" ON "MediaUploadIntent"("clientClipKey");
CREATE INDEX "MediaUploadIntent_closedAt_expiresAt_idx" ON "MediaUploadIntent"("closedAt", "expiresAt");
CREATE INDEX "MediaUploadIntent_uploaderId_idx" ON "MediaUploadIntent"("uploaderId");

-- ── coverage, computed on read (decision D3) ───────────────────────────────────────────────────
-- One row per take: a live recording x one of its targets x one ANSWERED subject. `position` is the
-- take's 1-based place per (artisan, target) by capturedAt; a continuation (Part 2) shares the
-- position of the take it continues, followed back to the first part.
CREATE VIEW questionnaire_take_v AS
WITH RECURSIVE live AS (
  SELECT r.*, m."questionnaireInterviewId" AS "sessionId", m."createdAt" AS "mediaCreatedAt",
         m."contentSha256", i."questionnaireId", i."workshopId"
    FROM "QuestionnaireRecording" r
    JOIN "MediaFile" m ON m."id" = r."mediaFileId"
    JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId"
   WHERE r."removedAt" IS NULL AND m."removedAt" IS NULL
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
         l."changeSeq", l."mediaFileId", l."mediaCreatedAt", s."repeatReason", s."flag", s."named"
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

-- ── the frozen QuestionnaireResponse rows become canonical answers ─────────────────────────────
-- One answer per (artisan, lineage), one revision per (response, participant); the latest revision
-- is current. GROUP_SHARED when the sitting had more than one participant. A response on an
-- interview with no participants has nobody to belong to and stays where it is (the table is read
-- only from here on, and M4 drops it).
WITH src AS (
  SELECT r."id" AS rid, r."answerText" AS txt, r."answerSource", r."answerOrigin", r."answeredById",
         r."createdAt", r."updatedAt", r."interviewId", q."id" AS qid, q."lineageId", i."questionnaireId",
         i."status" AS istatus, a."artisanId",
         (SELECT count(*) FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = r."interviewId") AS n
    FROM "QuestionnaireResponse" r
    JOIN "QuestionnaireQuestion" q ON q."id" = r."questionId"
    JOIN "QuestionnaireInterview" i ON i."id" = r."interviewId"
    JOIN "QuestionnaireInterviewArtisan" a ON a."interviewId" = r."interviewId"
   WHERE coalesce(btrim(r."answerText"), '') <> ''
)
INSERT INTO "QuestionnaireAnswer" ("id", "artisanId", "questionnaireId", "questionLineageId", "updatedAt")
SELECT DISTINCT ON ("artisanId", "lineageId") gen_random_uuid()::text, "artisanId", "questionnaireId", "lineageId", now()
  FROM src
ON CONFLICT ("artisanId", "questionLineageId") DO NOTHING;

WITH src AS (
  SELECT r."answerText" AS txt, r."answerSource", r."answerOrigin", r."answeredById", r."updatedAt",
         r."interviewId", q."id" AS qid, q."lineageId", i."status" AS istatus, a."artisanId",
         (SELECT count(*) FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = r."interviewId") AS n
    FROM "QuestionnaireResponse" r
    JOIN "QuestionnaireQuestion" q ON q."id" = r."questionId"
    JOIN "QuestionnaireInterview" i ON i."id" = r."interviewId"
    JOIN "QuestionnaireInterviewArtisan" a ON a."interviewId" = r."interviewId"
   WHERE coalesce(btrim(r."answerText"), '') <> ''
)
INSERT INTO "QuestionnaireAnswerRevision"
  ("id", "answerId", "text", "origin", "attribution", "askedQuestionId", "sessionId", "status", "actorId", "createdAt")
SELECT gen_random_uuid()::text, ans."id", src.txt,
       (CASE WHEN src."answerOrigin" IN ('TYPED', 'TRANSCRIPT', 'MACHINE_SUGGESTED', 'MACHINE_SUGGESTED_EDITED', 'PREFILL')
               THEN src."answerOrigin"
             WHEN src."answerSource" = 'TRANSCRIPT' THEN 'TRANSCRIPT'
             ELSE 'TYPED' END)::"AnswerOrigin",
       (CASE WHEN src.n > 1 THEN 'GROUP_SHARED' ELSE 'INDIVIDUAL' END)::"AnswerAttribution",
       src.qid, src."interviewId", src.istatus, src."answeredById", src."updatedAt"
  FROM src JOIN "QuestionnaireAnswer" ans
    ON ans."artisanId" = src."artisanId" AND ans."questionLineageId" = src."lineageId";

UPDATE "QuestionnaireAnswer" a SET "currentRevisionId" = latest."id"
  FROM (SELECT DISTINCT ON ("answerId") "answerId", "id" FROM "QuestionnaireAnswerRevision"
         ORDER BY "answerId", "createdAt" DESC, "id" DESC) latest
 WHERE latest."answerId" = a."id" AND a."currentRevisionId" IS NULL;
