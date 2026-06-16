-- Record timestamp fields are stored as UTC instants. The timezone column
-- preserves the field interpretation preference, defaulting to IST.

-- AlterTable
ALTER TABLE "Craft"
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

-- AlterTable
ALTER TABLE "Artisan"
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

-- AlterTable
ALTER TABLE "Workshop"
ADD COLUMN "startDate" TIMESTAMP(3),
ADD COLUMN "endDate" TIMESTAMP(3),
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

UPDATE "Workshop"
SET "startDate" = COALESCE("startDate", "date"),
    "endDate" = COALESCE("endDate", "date");

-- AlterTable
ALTER TABLE "ProductDocumentation"
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

-- AlterTable
ALTER TABLE "ToolDocumentation"
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

-- AlterTable
ALTER TABLE "MediaFile"
ADD COLUMN "craftId" TEXT,
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

-- AlterTable
ALTER TABLE "QuestionnaireInterview"
ADD COLUMN "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN "recordedTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata';

-- CreateIndex
CREATE INDEX "Craft_recordedAt_idx" ON "Craft"("recordedAt");

-- CreateIndex
CREATE INDEX "Artisan_recordedAt_idx" ON "Artisan"("recordedAt");

-- CreateIndex
CREATE INDEX "Workshop_startDate_idx" ON "Workshop"("startDate");

-- CreateIndex
CREATE INDEX "Workshop_endDate_idx" ON "Workshop"("endDate");

-- CreateIndex
CREATE INDEX "Workshop_recordedAt_idx" ON "Workshop"("recordedAt");

-- CreateIndex
CREATE INDEX "ProductDocumentation_recordedAt_idx" ON "ProductDocumentation"("recordedAt");

-- CreateIndex
CREATE INDEX "ToolDocumentation_recordedAt_idx" ON "ToolDocumentation"("recordedAt");

-- CreateIndex
CREATE INDEX "MediaFile_craftId_idx" ON "MediaFile"("craftId");

-- CreateIndex
CREATE INDEX "MediaFile_recordedAt_idx" ON "MediaFile"("recordedAt");

-- CreateIndex
CREATE INDEX "QuestionnaireInterview_recordedAt_idx" ON "QuestionnaireInterview"("recordedAt");

-- AddForeignKey
ALTER TABLE "MediaFile"
ADD CONSTRAINT "MediaFile_craftId_fkey" FOREIGN KEY ("craftId") REFERENCES "Craft"("id") ON DELETE SET NULL ON UPDATE CASCADE;
