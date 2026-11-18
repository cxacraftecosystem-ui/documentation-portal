ALTER TYPE "ConsentPurpose" ADD VALUE IF NOT EXISTS 'PUBLICATION';

CREATE TABLE IF NOT EXISTS "FieldVoiceItem" (
    "stream" TEXT NOT NULL,
    "itemId" TEXT NOT NULL,
    "state" TEXT NOT NULL,
    "revision" TEXT,
    "reason" TEXT,
    "successorId" TEXT,
    "payload" JSONB,
    "copyKey" TEXT,
    "copyOf" TEXT,
    "subjects" JSONB,
    "updatedAt" TIMESTAMP(6) NOT NULL,
    "firstListedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "FieldVoiceItem_pkey" PRIMARY KEY ("stream","itemId")
);

CREATE INDEX IF NOT EXISTS "FieldVoiceItem_stream_updatedAt_itemId_idx" ON "FieldVoiceItem"("stream", "updatedAt", "itemId");

CREATE INDEX IF NOT EXISTS "FieldVoiceItem_stream_state_updatedAt_itemId_idx" ON "FieldVoiceItem"("stream", "state", "updatedAt", "itemId");

CREATE TABLE IF NOT EXISTS "FieldVoiceRefresh" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "startedAt" TIMESTAMP(3),
    "finishedAt" TIMESTAMP(3),
    "counts" JSONB NOT NULL DEFAULT '{}',

    CONSTRAINT "FieldVoiceRefresh_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "FieldVoiceCopyRelease" (
    "copyKey" TEXT NOT NULL,
    "queuedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "attempts" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "FieldVoiceCopyRelease_pkey" PRIMARY KEY ("copyKey")
);

ALTER TABLE "FieldVoiceItem" ENABLE ROW LEVEL SECURITY;

ALTER TABLE "FieldVoiceRefresh" ENABLE ROW LEVEL SECURITY;

ALTER TABLE "FieldVoiceCopyRelease" ENABLE ROW LEVEL SECURITY;
