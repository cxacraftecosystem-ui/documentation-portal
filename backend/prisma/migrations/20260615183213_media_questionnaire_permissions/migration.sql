-- AlterEnum
ALTER TYPE "UserRole" ADD VALUE 'MASTER_ADMIN';

-- AlterTable
ALTER TABLE "MediaFile" ADD COLUMN     "questionnaireInterviewId" TEXT,
ADD COLUMN     "transcriptError" TEXT,
ADD COLUMN     "transcriptStatus" TEXT,
ADD COLUMN     "transcriptSummary" TEXT,
ADD COLUMN     "transcriptText" TEXT;

-- AlterTable
ALTER TABLE "ProductDocumentation" ADD COLUMN     "breadthInches" DECIMAL(10,2),
ADD COLUMN     "lengthInches" DECIMAL(10,2),
ADD COLUMN     "measurementAnalysis" JSONB,
ADD COLUMN     "measurementAnalysisStatus" TEXT,
ADD COLUMN     "measurementImageId" TEXT;

-- AlterTable
ALTER TABLE "ToolDocumentation" ADD COLUMN     "breadthInches" DECIMAL(10,2),
ADD COLUMN     "lengthInches" DECIMAL(10,2),
ADD COLUMN     "measurementAnalysis" JSONB,
ADD COLUMN     "measurementAnalysisStatus" TEXT,
ADD COLUMN     "measurementImageId" TEXT;

-- CreateTable
CREATE TABLE "QuestionnaireQuestion" (
    "id" TEXT NOT NULL,
    "sectionCode" TEXT NOT NULL,
    "sectionTitle" TEXT NOT NULL,
    "prompt" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "QuestionnaireQuestion_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "QuestionnaireInterview" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "interviewDate" TIMESTAMP(3),
    "place" TEXT,
    "language" TEXT,
    "notes" TEXT,
    "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT NOT NULL,
    "locationId" TEXT,

    CONSTRAINT "QuestionnaireInterview_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "QuestionnaireInterviewArtisan" (
    "interviewId" TEXT NOT NULL,
    "artisanId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "QuestionnaireInterviewArtisan_pkey" PRIMARY KEY ("interviewId","artisanId")
);

-- CreateTable
CREATE TABLE "QuestionnaireResponse" (
    "id" TEXT NOT NULL,
    "interviewId" TEXT NOT NULL,
    "questionId" TEXT NOT NULL,
    "answerText" TEXT,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "answeredById" TEXT NOT NULL,

    CONSTRAINT "QuestionnaireResponse_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "QuestionnaireQuestion_sectionCode_idx" ON "QuestionnaireQuestion"("sectionCode");

-- CreateIndex
CREATE UNIQUE INDEX "QuestionnaireQuestion_sectionCode_sortOrder_key" ON "QuestionnaireQuestion"("sectionCode", "sortOrder");

-- CreateIndex
CREATE INDEX "QuestionnaireInterview_createdById_idx" ON "QuestionnaireInterview"("createdById");

-- CreateIndex
CREATE INDEX "QuestionnaireInterview_status_idx" ON "QuestionnaireInterview"("status");

-- CreateIndex
CREATE INDEX "QuestionnaireInterview_interviewDate_idx" ON "QuestionnaireInterview"("interviewDate");

-- CreateIndex
CREATE INDEX "QuestionnaireInterviewArtisan_artisanId_idx" ON "QuestionnaireInterviewArtisan"("artisanId");

-- CreateIndex
CREATE INDEX "QuestionnaireResponse_questionId_idx" ON "QuestionnaireResponse"("questionId");

-- CreateIndex
CREATE INDEX "QuestionnaireResponse_answeredById_idx" ON "QuestionnaireResponse"("answeredById");

-- CreateIndex
CREATE UNIQUE INDEX "QuestionnaireResponse_interviewId_questionId_key" ON "QuestionnaireResponse"("interviewId", "questionId");

-- CreateIndex
CREATE INDEX "MediaFile_questionnaireInterviewId_idx" ON "MediaFile"("questionnaireInterviewId");

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_questionnaireInterviewId_fkey" FOREIGN KEY ("questionnaireInterviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireInterview" ADD CONSTRAINT "QuestionnaireInterview_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireInterview" ADD CONSTRAINT "QuestionnaireInterview_locationId_fkey" FOREIGN KEY ("locationId") REFERENCES "Location"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireInterviewArtisan" ADD CONSTRAINT "QuestionnaireInterviewArtisan_interviewId_fkey" FOREIGN KEY ("interviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireInterviewArtisan" ADD CONSTRAINT "QuestionnaireInterviewArtisan_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireResponse" ADD CONSTRAINT "QuestionnaireResponse_interviewId_fkey" FOREIGN KEY ("interviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireResponse" ADD CONSTRAINT "QuestionnaireResponse_questionId_fkey" FOREIGN KEY ("questionId") REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestionnaireResponse" ADD CONSTRAINT "QuestionnaireResponse_answeredById_fkey" FOREIGN KEY ("answeredById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
