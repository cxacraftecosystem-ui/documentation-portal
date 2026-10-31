-- 0.0.5: ElevenLabs Voice Isolator, switched OFF by default (AppSetting.audioIsolationEnabled).
-- The cleaned copy is stored BESIDE the original, never over it: MediaFile.objectKey still names the
-- recording as uploaded, and cleanedObjectKey names the isolated copy (same file name, new key).
-- Additive only. Roll back: DROP the three MediaFile columns and the AppSetting column; the enum
-- value stays (Postgres cannot drop one), unused.
ALTER TYPE "MediaProcessingJobType" ADD VALUE IF NOT EXISTS 'AUDIO_ISOLATION';
ALTER TABLE "AppSetting" ADD COLUMN IF NOT EXISTS "audioIsolationEnabled" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "MediaFile" ADD COLUMN IF NOT EXISTS "cleanedObjectKey" TEXT;
ALTER TABLE "MediaFile" ADD COLUMN IF NOT EXISTS "cleanedMimeType" TEXT;
ALTER TABLE "MediaFile" ADD COLUMN IF NOT EXISTS "cleanedAt" TIMESTAMP(3);
