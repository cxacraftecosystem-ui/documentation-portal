-- Give every record type an explicit workshop, and back the late-submission approval gate.
--   * Artisan / Craft / Process / QuestionnaireInterview each gain a nullable "workshopId" FK, so a
--     record names the workshop it was documented at DIRECTLY. Until now artisans and crafts reached
--     a workshop only through the WorkshopArtisan / WorkshopCraft join tables, a process only through
--     its parent product's workshopId, and an interview had no path to a workshop at all.
--   * The columns are ADDITIVE: the join tables are still written (and still read by every existing
--     query), and a process still inherits its product's workshop. Nothing is backfilled, so every
--     existing row keeps workshopId NULL and behaves exactly as it did before this migration — which
--     is what makes this safe to run against live data.
--   * The late-submission gate itself needs no DDL: it lives in each record's existing extraMetadata
--     JSON column, under "workshopSubmission" {outOfWindow, needsAdminApproval, submittedAt, endDate}.

-- AlterTable
ALTER TABLE "Artisan" ADD COLUMN "workshopId" TEXT;

-- AlterTable
ALTER TABLE "Craft" ADD COLUMN "workshopId" TEXT;

-- AlterTable
ALTER TABLE "Process" ADD COLUMN "workshopId" TEXT;

-- AlterTable
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "workshopId" TEXT;

-- CreateIndex
CREATE INDEX "Artisan_workshopId_idx" ON "Artisan"("workshopId");

-- CreateIndex
CREATE INDEX "Craft_workshopId_idx" ON "Craft"("workshopId");

-- CreateIndex
CREATE INDEX "Process_workshopId_idx" ON "Process"("workshopId");

-- CreateIndex
CREATE INDEX "QuestionnaireInterview_workshopId_idx" ON "QuestionnaireInterview"("workshopId");

-- AddForeignKey
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_workshopId_fkey"
    FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Craft" ADD CONSTRAINT "Craft_workshopId_fkey"
    FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Process" ADD CONSTRAINT "Process_workshopId_fkey"
    FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireInterview" ADD CONSTRAINT "QuestionnaireInterview_workshopId_fkey"
    FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;
