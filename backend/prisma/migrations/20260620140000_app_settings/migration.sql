-- Master-admin-configurable global settings (singleton row id = 'singleton'): transcription output
-- mode and an optional off-peak processing window for transcription + refinement.
CREATE TABLE "AppSetting" (
    "id" TEXT NOT NULL DEFAULT 'singleton',
    "transcriptionMode" TEXT NOT NULL DEFAULT 'REFINED_TRANSLATED',
    "batchWindowEnabled" BOOLEAN NOT NULL DEFAULT false,
    "batchWindowStart" TEXT NOT NULL DEFAULT '02:00',
    "batchWindowEnd" TEXT NOT NULL DEFAULT '05:00',
    "batchTimezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "updatedById" TEXT,

    CONSTRAINT "AppSetting_pkey" PRIMARY KEY ("id")
);
