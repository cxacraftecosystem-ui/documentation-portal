-- CreateTable
CREATE TABLE "QuestionnaireSection" (
    "id" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "QuestionnaireSection_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "QuestionnaireSection_code_key" ON "QuestionnaireSection"("code");

-- CreateIndex
CREATE UNIQUE INDEX "QuestionnaireSection_sortOrder_key" ON "QuestionnaireSection"("sortOrder");

-- CreateIndex
CREATE INDEX "QuestionnaireSection_code_idx" ON "QuestionnaireSection"("code");

-- CreateIndex
CREATE INDEX "QuestionnaireSection_isActive_sortOrder_idx" ON "QuestionnaireSection"("isActive", "sortOrder");

-- AlterTable
ALTER TABLE "QuestionnaireQuestion" ADD COLUMN "sectionId" TEXT;

-- Backfill one editable section per existing copied section code/title.
INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "isActive", "createdAt", "updatedAt")
SELECT
    'section_' || substr(md5("sectionCode"), 1, 24) AS "id",
    "sectionCode" AS "code",
    MIN("sectionTitle") AS "title",
    ROW_NUMBER() OVER (
        ORDER BY
            CASE WHEN "sectionCode" = 'RESP' THEN 0 ELSE 1 END,
            "sectionCode"
    ) AS "sortOrder",
    bool_or("isActive") AS "isActive",
    CURRENT_TIMESTAMP AS "createdAt",
    CURRENT_TIMESTAMP AS "updatedAt"
FROM "QuestionnaireQuestion"
GROUP BY "sectionCode";

UPDATE "QuestionnaireQuestion" AS question
SET "sectionId" = section."id"
FROM "QuestionnaireSection" AS section
WHERE question."sectionCode" = section."code";

-- The old unique constraint blocked moving questions between sections during reordering.
DROP INDEX IF EXISTS "QuestionnaireQuestion_sectionCode_sortOrder_key";

-- CreateIndex
CREATE INDEX "QuestionnaireQuestion_sectionId_idx" ON "QuestionnaireQuestion"("sectionId");

-- CreateIndex
CREATE INDEX "QuestionnaireQuestion_isActive_idx" ON "QuestionnaireQuestion"("isActive");

-- AddForeignKey
ALTER TABLE "QuestionnaireQuestion" ADD CONSTRAINT "QuestionnaireQuestion_sectionId_fkey" FOREIGN KEY ("sectionId") REFERENCES "QuestionnaireSection"("id") ON DELETE SET NULL ON UPDATE CASCADE;
