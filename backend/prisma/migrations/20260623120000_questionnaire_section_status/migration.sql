-- Admin-set completion status per (artisan, section) for the "Check completion" matrix on the View
-- Data screen. A row is an explicit verdict (COMPLETED / NEEDS_REVIEW / NEEDS_REDO) that overrides
-- the data-derived green; no row means "fall back to derived completion".
CREATE TABLE "QuestionnaireSectionStatus" (
    "id" TEXT NOT NULL,
    "artisanId" TEXT NOT NULL,
    "sectionId" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "setById" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "QuestionnaireSectionStatus_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "QuestionnaireSectionStatus_artisanId_sectionId_key" ON "QuestionnaireSectionStatus"("artisanId", "sectionId");
CREATE INDEX "QuestionnaireSectionStatus_artisanId_idx" ON "QuestionnaireSectionStatus"("artisanId");
CREATE INDEX "QuestionnaireSectionStatus_sectionId_idx" ON "QuestionnaireSectionStatus"("sectionId");

-- AddForeignKey
ALTER TABLE "QuestionnaireSectionStatus" ADD CONSTRAINT "QuestionnaireSectionStatus_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "QuestionnaireSectionStatus" ADD CONSTRAINT "QuestionnaireSectionStatus_sectionId_fkey" FOREIGN KEY ("sectionId") REFERENCES "QuestionnaireSection"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "QuestionnaireSectionStatus" ADD CONSTRAINT "QuestionnaireSectionStatus_setById_fkey" FOREIGN KEY ("setById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
