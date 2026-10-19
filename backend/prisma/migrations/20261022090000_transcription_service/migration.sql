-- The shared transcription service (docs/transcription/TRANSCRIPTION_SERVICE_2026-10-18.md). Additive
-- and live-safe: new nullable columns, new tables, and new column defaults.
--
-- THE ONE CHANGE TO A LIVE ROW IS DELIBERATE: "AppSetting"."transcriptionPaused" is added NOT NULL
-- DEFAULT true, so the existing settings row reads PAUSED the moment this runs. That is the owner's
-- hold of 18 Oct 2026: nothing transcribes on deploy, queued jobs stay QUEUED, and only an admin
-- turning the switch off in Settings starts the queue again. Muse is added switched off.
-- IF NOT EXISTS because 20261022090000_consent_and_pause (questionnaire M0) adds the same column,
-- with the same default, and sorts first.
--
-- "MediaProcessingJob"."openKey" is a plain nullable unique, which is the partial index Prisma can
-- state: every existing row keeps NULL, so the index builds on live data without a conflict.
--
-- No consent row, checksum or job is written here. Those are dry-run-first scripts run after
-- deploy (scripts/backfill_consent_owner_attestation.py, scripts/backfill_media_checksums.py).

-- AlterTable
ALTER TABLE "MediaFile" ADD COLUMN     "transcriptLanguage" TEXT,
ADD COLUMN     "transcriptProvider" TEXT,
ADD COLUMN     "transcriptSegments" JSONB;

-- AlterTable
ALTER TABLE "MediaProcessingJob" ADD COLUMN     "openKey" TEXT;

-- AlterTable
ALTER TABLE "AppSetting" ADD COLUMN     "sttProvidersDisabled" TEXT[] DEFAULT ARRAY['muse']::TEXT[],
ADD COLUMN IF NOT EXISTS "transcriptionPaused" BOOLEAN NOT NULL DEFAULT true,
ALTER COLUMN "batchWindowEnabled" SET DEFAULT true;

-- CreateTable
CREATE TABLE "SttProviderState" (
    "id" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "keyRef" TEXT NOT NULL,
    "cooldownUntil" TIMESTAMP(3),
    "consecutive429" INTEGER NOT NULL DEFAULT 0,
    "lastError" TEXT,
    "lastErrorAt" TIMESTAMP(3),
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "SttProviderState_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SttUsage" (
    "id" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "model" TEXT,
    "keyRef" TEXT,
    "mediaFileId" TEXT,
    "jobId" TEXT,
    "partIndex" INTEGER,
    "audioMs" INTEGER,
    "latencyMs" INTEGER NOT NULL DEFAULT 0,
    "httpStatus" INTEGER,
    "outcome" TEXT NOT NULL,
    "costUsd" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "cacheHit" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SttUsage_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SttBudget" (
    "id" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "period" TEXT NOT NULL,
    "minutesCap" INTEGER,
    "spendCapUsd" DOUBLE PRECISION,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "updatedById" TEXT,

    CONSTRAINT "SttBudget_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "TranscriptionChunk" (
    "id" TEXT NOT NULL,
    "jobId" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "partIndex" INTEGER NOT NULL,
    "partCount" INTEGER NOT NULL,
    "startMs" INTEGER NOT NULL,
    "endMs" INTEGER NOT NULL,
    "status" TEXT NOT NULL,
    "text" TEXT,
    "segments" JSONB,
    "speakers" INTEGER NOT NULL DEFAULT 0,
    "language" TEXT,
    "model" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "TranscriptionChunk_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "TranscriptCache" (
    "id" TEXT NOT NULL,
    "sha256" TEXT NOT NULL,
    "optionsHash" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "model" TEXT,
    "text" TEXT NOT NULL,
    "segments" JSONB,
    "language" TEXT,
    "speakers" INTEGER NOT NULL DEFAULT 0,
    "hits" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "TranscriptCache_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RecordingConsent" (
    "id" TEXT NOT NULL,
    "mediaFileId" TEXT,
    "artisanId" TEXT,
    "decision" TEXT NOT NULL,
    "source" TEXT,
    "attestedById" TEXT,
    "attestedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "note" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RecordingConsent_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "SttProviderState_provider_keyRef_key" ON "SttProviderState"("provider", "keyRef");

-- CreateIndex
CREATE INDEX "SttUsage_provider_createdAt_idx" ON "SttUsage"("provider", "createdAt");

-- CreateIndex
CREATE INDEX "SttUsage_createdAt_idx" ON "SttUsage"("createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "SttBudget_provider_period_key" ON "SttBudget"("provider", "period");

-- CreateIndex
CREATE UNIQUE INDEX "TranscriptionChunk_jobId_provider_partIndex_key" ON "TranscriptionChunk"("jobId", "provider", "partIndex");

-- CreateIndex
CREATE UNIQUE INDEX "TranscriptCache_sha256_optionsHash_key" ON "TranscriptCache"("sha256", "optionsHash");

-- CreateIndex
CREATE INDEX "RecordingConsent_mediaFileId_createdAt_idx" ON "RecordingConsent"("mediaFileId", "createdAt");

-- CreateIndex
CREATE INDEX "RecordingConsent_artisanId_createdAt_idx" ON "RecordingConsent"("artisanId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "MediaProcessingJob_openKey_key" ON "MediaProcessingJob"("openKey");

-- AddForeignKey
ALTER TABLE "TranscriptionChunk" ADD CONSTRAINT "TranscriptionChunk_jobId_fkey" FOREIGN KEY ("jobId") REFERENCES "MediaProcessingJob"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RecordingConsent" ADD CONSTRAINT "RecordingConsent_mediaFileId_fkey" FOREIGN KEY ("mediaFileId") REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RecordingConsent" ADD CONSTRAINT "RecordingConsent_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
