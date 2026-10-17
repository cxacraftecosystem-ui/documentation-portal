-- The review feedback loop (2026-10-20). Additive, and safe on a live database.
--
-- 1. "submittedAt" on the six reviewable record tables: when the record last entered the review
--    queue. The queue is served oldest first by it, so a record that was sent back and fixed goes to
--    the FRONT of the queue instead of sinking behind newer work. Back-filled from createdAt, except
--    a record that is pending again after a review (it was sent back and resubmitted), whose last
--    edit is the best evidence of when it came back.
-- 2. "reason" on ReviewLog: the short-list reason a reviewer picked when rejecting or sending back.
-- 3. Media inherits its record's approval: files hanging off an already-approved record, still
--    pending, become approved. A file submitted outside its workshop's dates keeps waiting for an
--    admin, exactly as the review routes keep it.

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

UPDATE "MediaFile" AS m
SET "status" = 'APPROVED'
WHERE m."status" = 'PENDING'
  AND COALESCE(m."extraMetadata" -> 'workshopSubmission' ->> 'needsAdminApproval', 'false') <> 'true'
  AND (
    (lower(m."linkedRecordType") = 'artisan' AND EXISTS (SELECT 1 FROM "Artisan" r WHERE r."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (lower(m."linkedRecordType") = 'workshop' AND EXISTS (SELECT 1 FROM "Workshop" r WHERE r."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (lower(m."linkedRecordType") = 'product' AND EXISTS (SELECT 1 FROM "ProductDocumentation" r WHERE r."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (lower(m."linkedRecordType") = 'tool' AND EXISTS (SELECT 1 FROM "ToolDocumentation" r WHERE r."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (lower(m."linkedRecordType") = 'process' AND EXISTS (SELECT 1 FROM "Process" r WHERE r."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (lower(m."linkedRecordType") = 'processstep' AND EXISTS (
          SELECT 1 FROM "ProcessStep" st JOIN "Process" r ON r."id" = st."processId"
          WHERE st."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (lower(m."linkedRecordType") IN ('questionnaire', 'questionnaireinterview') AND EXISTS (SELECT 1 FROM "QuestionnaireInterview" r WHERE r."id" = m."linkedRecordId" AND r."status" = 'APPROVED'))
    OR (m."linkedRecordType" IS NULL AND (
          EXISTS (SELECT 1 FROM "ProductDocumentation" r WHERE r."id" = m."productId" AND r."status" = 'APPROVED')
          OR EXISTS (SELECT 1 FROM "ToolDocumentation" r WHERE r."id" = m."toolId" AND r."status" = 'APPROVED')
          OR EXISTS (SELECT 1 FROM "QuestionnaireInterview" r WHERE r."id" = m."questionnaireInterviewId" AND r."status" = 'APPROVED')
          OR (m."productId" IS NULL AND m."toolId" IS NULL AND m."questionnaireInterviewId" IS NULL
              AND EXISTS (SELECT 1 FROM "Artisan" r WHERE r."id" = m."artisanId" AND r."status" = 'APPROVED'))
    ))
  );
