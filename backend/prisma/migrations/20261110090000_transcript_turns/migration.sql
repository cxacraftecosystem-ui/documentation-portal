-- The transcript pipeline (31 Oct 2026, docs/transcription/DESIGN_2026-10-31_Scribe-Gemini-Pipeline.md).
-- IDENTICAL IN BOTH PORTALS (listed in shared/transcription.sha256). Additive only: new tables and
-- three nullable-or-defaulted AppSetting columns; nothing existing is altered or dropped.
--
-- A recording's transcript is stored as TURNS (one voice, one stretch), one set per run
-- (TranscriptVersion). The machine's words stay frozen in machineText / machineEnglish; a
-- researcher's edit goes to editedText / editedEnglish and into TranscriptTurnEdit's history.

CREATE TABLE IF NOT EXISTS "TranscriptVersion" (
    "id" TEXT NOT NULL,
    "mediaFileId" TEXT NOT NULL,
    "version" INTEGER NOT NULL,
    "sttProvider" TEXT,
    "sttModel" TEXT,
    "translationProvider" TEXT,
    "translationModel" TEXT,
    "promptVersion" TEXT,
    "detectedLanguage" TEXT,
    "languages" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    "durationMs" INTEGER,
    "speakerCount" INTEGER NOT NULL DEFAULT 0,
    "turnCount" INTEGER NOT NULL DEFAULT 0,
    "keytermCount" INTEGER NOT NULL DEFAULT 0,
    "sttCostUsd" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "translationCostUsd" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "translationStatus" TEXT NOT NULL DEFAULT 'PENDING',
    "translatedAt" TIMESTAMP(3),
    "revision" INTEGER NOT NULL DEFAULT 1,
    "supersededAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptVersion_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "TranscriptVersion_mediaFileId_fkey" FOREIGN KEY ("mediaFileId")
        REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "TranscriptVersion_mediaFileId_version_key"
    ON "TranscriptVersion"("mediaFileId", "version");
CREATE INDEX IF NOT EXISTS "TranscriptVersion_mediaFileId_supersededAt_idx"
    ON "TranscriptVersion"("mediaFileId", "supersededAt");
CREATE INDEX IF NOT EXISTS "TranscriptVersion_translationStatus_idx"
    ON "TranscriptVersion"("translationStatus");

CREATE TABLE IF NOT EXISTS "TranscriptTurn" (
    "id" TEXT NOT NULL,
    "versionId" TEXT NOT NULL,
    "mediaFileId" TEXT NOT NULL,
    "position" INTEGER NOT NULL,
    "speakerKey" TEXT,
    "startMs" INTEGER NOT NULL DEFAULT 0,
    "endMs" INTEGER NOT NULL DEFAULT 0,
    "language" TEXT NOT NULL DEFAULT 'und',
    "machineText" TEXT NOT NULL,
    "editedText" TEXT,
    "words" JSONB NOT NULL DEFAULT '[]',
    "lowConfidence" JSONB NOT NULL DEFAULT '[]',
    "machineEnglish" TEXT,
    "editedEnglish" TEXT,
    "translationStatus" TEXT NOT NULL DEFAULT 'PENDING',
    "translationModel" TEXT,
    "editedAt" TIMESTAMP(3),
    "editedById" TEXT,
    "revision" INTEGER NOT NULL DEFAULT 1,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptTurn_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "TranscriptTurn_versionId_fkey" FOREIGN KEY ("versionId")
        REFERENCES "TranscriptVersion"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "TranscriptTurn_versionId_position_key"
    ON "TranscriptTurn"("versionId", "position");
CREATE INDEX IF NOT EXISTS "TranscriptTurn_mediaFileId_idx" ON "TranscriptTurn"("mediaFileId");

CREATE TABLE IF NOT EXISTS "TranscriptTurnEdit" (
    "id" TEXT NOT NULL,
    "turnId" TEXT NOT NULL,
    "field" TEXT NOT NULL,
    "source" TEXT NOT NULL,
    "text" TEXT NOT NULL,
    "actorId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptTurnEdit_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "TranscriptTurnEdit_turnId_fkey" FOREIGN KEY ("turnId")
        REFERENCES "TranscriptTurn"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "TranscriptTurnEdit_turnId_createdAt_idx"
    ON "TranscriptTurnEdit"("turnId", "createdAt");

CREATE TABLE IF NOT EXISTS "TranscriptSpeaker" (
    "mediaFileId" TEXT NOT NULL,
    "speakerKey" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "role" TEXT,
    "personId" TEXT,
    "setById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptSpeaker_pkey" PRIMARY KEY ("mediaFileId", "speakerKey"),
    CONSTRAINT "TranscriptSpeaker_mediaFileId_fkey" FOREIGN KEY ("mediaFileId")
        REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- Every Gemini call the pipeline pays for. Speech-to-text spend is already in "SttUsage"; the
-- monthly budget meter adds the two.
CREATE TABLE IF NOT EXISTS "TranscriptSpend" (
    "id" TEXT NOT NULL,
    "mediaFileId" TEXT,
    "kind" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "model" TEXT,
    "keyRef" TEXT,
    "inputTokens" INTEGER NOT NULL DEFAULT 0,
    "outputTokens" INTEGER NOT NULL DEFAULT 0,
    "costUsd" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "outcome" TEXT NOT NULL DEFAULT 'COMPLETED',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptSpend_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "TranscriptSpend_mediaFileId_fkey" FOREIGN KEY ("mediaFileId")
        REFERENCES "MediaFile"("id") ON DELETE SET NULL ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "TranscriptSpend_createdAt_idx" ON "TranscriptSpend"("createdAt");
CREATE INDEX IF NOT EXISTS "TranscriptSpend_mediaFileId_idx" ON "TranscriptSpend"("mediaFileId");

-- The living craft glossary. "craftId" carries no foreign key on purpose: a craft merge re-points
-- references by its own registry, and a glossary row must never block one. "scopeKey" is the craft
-- id or '' (heard with no craft on record), so one term is one row per craft.
CREATE TABLE IF NOT EXISTS "TranscriptGlossaryTerm" (
    "id" TEXT NOT NULL,
    "scopeKey" TEXT NOT NULL DEFAULT '',
    "craftId" TEXT,
    "termKey" TEXT NOT NULL,
    "term" TEXT NOT NULL,
    "gloss" TEXT,
    "linkedCraftId" TEXT,
    "occurrences" INTEGER NOT NULL DEFAULT 0,
    "lastHeardAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptGlossaryTerm_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "TranscriptGlossaryTerm_scopeKey_termKey_key"
    ON "TranscriptGlossaryTerm"("scopeKey", "termKey");
CREATE INDEX IF NOT EXISTS "TranscriptGlossaryTerm_craftId_idx" ON "TranscriptGlossaryTerm"("craftId");

CREATE TABLE IF NOT EXISTS "TranscriptGlossarySighting" (
    "id" TEXT NOT NULL,
    "termId" TEXT NOT NULL,
    "mediaFileId" TEXT NOT NULL,
    "turnId" TEXT,
    "startMs" INTEGER,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptGlossarySighting_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "TranscriptGlossarySighting_termId_fkey" FOREIGN KEY ("termId")
        REFERENCES "TranscriptGlossaryTerm"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "TranscriptGlossarySighting_mediaFileId_fkey" FOREIGN KEY ("mediaFileId")
        REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "TranscriptGlossarySighting_termId_mediaFileId_key"
    ON "TranscriptGlossarySighting"("termId", "mediaFileId");

-- The English and the citations of a questionnaire draft made from a recording. The draft itself
-- stays a QuestionnaireTakeDraft row (the existing drafts flow); this is what sits beside it.
CREATE TABLE IF NOT EXISTS "TranscriptDraftCitation" (
    "recordingId" TEXT NOT NULL,
    "questionLineageId" TEXT NOT NULL,
    "mediaFileId" TEXT NOT NULL,
    "english" TEXT,
    "citations" JSONB NOT NULL DEFAULT '[]',
    "model" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TranscriptDraftCitation_pkey" PRIMARY KEY ("recordingId", "questionLineageId"),
    CONSTRAINT "TranscriptDraftCitation_mediaFileId_fkey" FOREIGN KEY ("mediaFileId")
        REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- The monthly budget: USD 100 per portal (owner, 31 Oct 2026), an alert at 80 % and a pause at 100 %
-- through the existing transcriptionPaused switch. The two month keys make each happen once a month.
ALTER TABLE "AppSetting" ADD COLUMN IF NOT EXISTS "transcriptMonthlyBudgetUsd" DOUBLE PRECISION DEFAULT 100;
ALTER TABLE "AppSetting" ADD COLUMN IF NOT EXISTS "transcriptBudgetAlertedMonth" TEXT;
ALTER TABLE "AppSetting" ADD COLUMN IF NOT EXISTS "transcriptBudgetPausedMonth" TEXT;
