-- Interview summaries, key quotes and English translations (2026-10-18). Additive only.
--
-- One row per generation run. A regeneration never overwrites a row: it stamps the previous current
-- row's "supersededAt" and adds version + 1. A researcher's edit changes only the current row, and
-- the machine's own output stays frozen in "generated". "transcriptSummary" on MediaFile is NOT a
-- summary (the queue stores the raw provider text there) and nothing here writes to it.
--
-- The new enum value is not used anywhere in this migration: Postgres refuses a value added in the
-- same transaction it is used in.

ALTER TYPE "MediaProcessingJobType" ADD VALUE IF NOT EXISTS 'TRANSCRIPT_INSIGHTS';

CREATE TABLE "TranscriptInsight" (
  "id" TEXT PRIMARY KEY,
  "mediaFileId" TEXT NOT NULL REFERENCES "MediaFile"("id") ON DELETE CASCADE,
  "version" INTEGER NOT NULL,
  "sourceHash" TEXT NOT NULL,
  "sourceLanguage" TEXT,
  "summaryLines" TEXT[] NOT NULL DEFAULT '{}',
  "quotes" JSONB NOT NULL DEFAULT '[]',
  "translationStatus" TEXT NOT NULL,
  "translationText" TEXT,
  "translationNote" TEXT,
  "provider" TEXT NOT NULL,
  "modelId" TEXT NOT NULL,
  "modelVersion" TEXT,
  "promptVersion" TEXT NOT NULL,
  "generated" JSONB NOT NULL,
  "generatedAt" TIMESTAMP(3) NOT NULL,
  "requestedById" TEXT,
  "editedAt" TIMESTAMP(3),
  "editedById" TEXT,
  "supersededAt" TIMESTAMP(3),
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL
);

CREATE UNIQUE INDEX "TranscriptInsight_mediaFileId_version_key" ON "TranscriptInsight"("mediaFileId", "version");
CREATE INDEX "TranscriptInsight_mediaFileId_supersededAt_idx" ON "TranscriptInsight"("mediaFileId", "supersededAt");

-- Supabase Data API: deny-all, as on every other public table. The event trigger
-- enable_rls_on_new_tables already does the first statement on the live project; it is repeated so
-- the table is locked down on any database without the trigger. The roles exist only on Supabase.
ALTER TABLE "TranscriptInsight" ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'REVOKE ALL ON TABLE "TranscriptInsight" FROM anon';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
    EXECUTE 'REVOKE ALL ON TABLE "TranscriptInsight" FROM authenticated';
  END IF;
END
$$;
