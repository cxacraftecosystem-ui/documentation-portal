-- CreateEnum
CREATE TYPE "MediaProcessingJobType" AS ENUM ('TRANSCRIPTION', 'MEASUREMENT');

-- CreateEnum
CREATE TYPE "MediaProcessingJobStatus" AS ENUM ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED');

-- CreateTable
CREATE TABLE "MediaProcessingJob" (
    "id" TEXT NOT NULL,
    "jobType" "MediaProcessingJobType" NOT NULL,
    "status" "MediaProcessingJobStatus" NOT NULL DEFAULT 'QUEUED',
    "priority" INTEGER NOT NULL DEFAULT 100,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "maxAttempts" INTEGER NOT NULL DEFAULT 3,
    "error" TEXT,
    "result" JSONB,
    "lockedAt" TIMESTAMP(3),
    "lockedBy" TEXT,
    "runAfter" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "startedAt" TIMESTAMP(3),
    "completedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "mediaFileId" TEXT NOT NULL,
    "requestedById" TEXT,
    "productId" TEXT,
    "toolId" TEXT,

    CONSTRAINT "MediaProcessingJob_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "MediaProcessingJob_status_runAfter_priority_createdAt_idx" ON "MediaProcessingJob"("status", "runAfter", "priority", "createdAt");

-- CreateIndex
CREATE INDEX "MediaProcessingJob_mediaFileId_idx" ON "MediaProcessingJob"("mediaFileId");

-- CreateIndex
CREATE INDEX "MediaProcessingJob_requestedById_idx" ON "MediaProcessingJob"("requestedById");

-- CreateIndex
CREATE INDEX "MediaProcessingJob_productId_idx" ON "MediaProcessingJob"("productId");

-- CreateIndex
CREATE INDEX "MediaProcessingJob_toolId_idx" ON "MediaProcessingJob"("toolId");

-- AddForeignKey
ALTER TABLE "MediaProcessingJob" ADD CONSTRAINT "MediaProcessingJob_mediaFileId_fkey" FOREIGN KEY ("mediaFileId") REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaProcessingJob" ADD CONSTRAINT "MediaProcessingJob_requestedById_fkey" FOREIGN KEY ("requestedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaProcessingJob" ADD CONSTRAINT "MediaProcessingJob_productId_fkey" FOREIGN KEY ("productId") REFERENCES "ProductDocumentation"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaProcessingJob" ADD CONSTRAINT "MediaProcessingJob_toolId_fkey" FOREIGN KEY ("toolId") REFERENCES "ToolDocumentation"("id") ON DELETE SET NULL ON UPDATE CASCADE;
