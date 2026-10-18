-- The review feedback loop (2026-10-20). Additive, and safe on a live database.
--
-- 1. "submittedAt" on the six reviewable record tables: when the record last entered the review
--    queue. The queue is served oldest first by it, so a record that was sent back and fixed goes to
--    the FRONT of the queue instead of sinking behind newer work. Back-filled from createdAt, except
--    a record that is pending again after a review (it was sent back and resubmitted), whose last
--    edit is the best evidence of when it came back.
-- 2. "reason" on ReviewLog: the short-list reason a reviewer picked when rejecting or sending back.
-- 3. Files of records approved before this release are NOT approved here. That is
--    backend/scripts/approve_media_of_approved_records.py, run once on the box after the deploy
--    (DEPLOY_AWS.md section 11.1): it writes the ReviewLog line per file and leaves files uploaded
--    after their record's approval for review (owner decision 2026-10-18), which a blanket UPDATE
--    here could not.

ALTER TABLE "Artisan" ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "Workshop" ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "ProductDocumentation" ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "ToolDocumentation" ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "Process" ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE "Artisan" SET "submittedAt" = CASE WHEN "status" = 'PENDING' AND "reviewedAt" IS NOT NULL THEN "updatedAt" ELSE "createdAt" END;
UPDATE "Workshop" SET "submittedAt" = CASE WHEN "status" = 'PENDING' AND "reviewedAt" IS NOT NULL THEN "updatedAt" ELSE "createdAt" END;
UPDATE "ProductDocumentation" SET "submittedAt" = CASE WHEN "status" = 'PENDING' AND "reviewedAt" IS NOT NULL THEN "updatedAt" ELSE "createdAt" END;
UPDATE "ToolDocumentation" SET "submittedAt" = CASE WHEN "status" = 'PENDING' AND "reviewedAt" IS NOT NULL THEN "updatedAt" ELSE "createdAt" END;
UPDATE "Process" SET "submittedAt" = CASE WHEN "status" = 'PENDING' AND "reviewedAt" IS NOT NULL THEN "updatedAt" ELSE "createdAt" END;
UPDATE "QuestionnaireInterview" SET "submittedAt" = CASE WHEN "status" = 'PENDING' AND "reviewedAt" IS NOT NULL THEN "updatedAt" ELSE "createdAt" END;

CREATE INDEX "Artisan_status_submittedAt_idx" ON "Artisan"("status", "submittedAt");
CREATE INDEX "Workshop_status_submittedAt_idx" ON "Workshop"("status", "submittedAt");
CREATE INDEX "ProductDocumentation_status_submittedAt_idx" ON "ProductDocumentation"("status", "submittedAt");
CREATE INDEX "ToolDocumentation_status_submittedAt_idx" ON "ToolDocumentation"("status", "submittedAt");
CREATE INDEX "Process_status_submittedAt_idx" ON "Process"("status", "submittedAt");
CREATE INDEX "QuestionnaireInterview_status_submittedAt_idx" ON "QuestionnaireInterview"("status", "submittedAt");

ALTER TABLE "ReviewLog" ADD COLUMN "reason" TEXT;
