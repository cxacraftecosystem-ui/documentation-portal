-- C28: AI-DRAFTED ANSWERS FROM A TRANSCRIPT. Additive: one table and one nullable column, nothing
-- existing is changed (every current answer keeps answerOrigin NULL = typed by a person). Guarded,
-- so a re-run changes nothing. Nothing writes either while AI_ANSWER_DRAFTS_ENABLED is false.

ALTER TABLE "QuestionnaireResponse" ADD COLUMN IF NOT EXISTS "answerOrigin" TEXT;

CREATE TABLE IF NOT EXISTS "QuestionnaireAnswerDraft" (
    "id" TEXT NOT NULL,
    "interviewId" TEXT NOT NULL,
    "questionId" TEXT NOT NULL,
    "sectionId" TEXT,
    "draftText" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "provider" TEXT NOT NULL,
    "model" TEXT NOT NULL,
    "sourceMediaId" TEXT,
    "createdById" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decidedById" TEXT,
    "decidedAt" TIMESTAMP(3),
    CONSTRAINT "QuestionnaireAnswerDraft_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "QuestionnaireAnswerDraft_interviewId_questionId_key"
    ON "QuestionnaireAnswerDraft"("interviewId", "questionId");
CREATE INDEX IF NOT EXISTS "QuestionnaireAnswerDraft_interviewId_status_idx"
    ON "QuestionnaireAnswerDraft"("interviewId", "status");
CREATE INDEX IF NOT EXISTS "QuestionnaireAnswerDraft_questionId_idx"
    ON "QuestionnaireAnswerDraft"("questionId");

DO $$ BEGIN
    ALTER TABLE "QuestionnaireAnswerDraft" ADD CONSTRAINT "QuestionnaireAnswerDraft_interviewId_fkey"
        FOREIGN KEY ("interviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "QuestionnaireAnswerDraft" ADD CONSTRAINT "QuestionnaireAnswerDraft_questionId_fkey"
        FOREIGN KEY ("questionId") REFERENCES "QuestionnaireQuestion"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
