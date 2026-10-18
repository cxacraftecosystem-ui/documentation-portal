-- One search across both portals: the index this portal keeps of its own records (docs/SEARCH.md).
--
-- Additive and safe on live data. Two new tables, four new nullable columns on the cross-portal
-- access log, one trigger function and its triggers, and a queue backfill. Nothing heavy runs
-- here: the backfill only lists ids in "SearchIndexQueue", and the worker builds the documents in
-- small batches once UNIFIED_SEARCH_ENABLED is on.
--
-- pg_trgm is a trusted extension on Postgres 13+ and is available on the managed instance; the
-- vector block further down installs only where the extension exists, so a stock postgres image
-- still migrates.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS "SearchDocument" (
    "id" TEXT NOT NULL,
    "entityType" TEXT NOT NULL,
    "entityId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "subtitle" TEXT,
    "body" TEXT NOT NULL DEFAULT '',
    "place" TEXT,
    "craftIds" TEXT[] NOT NULL DEFAULT '{}',
    "craftCodes" TEXT[] NOT NULL DEFAULT '{}',
    "workshopIds" TEXT[] NOT NULL DEFAULT '{}',
    "artisanIds" TEXT[] NOT NULL DEFAULT '{}',
    "designWorkshopId" TEXT,
    "audience" TEXT NOT NULL DEFAULT 'ALL',
    "shareable" BOOLEAN NOT NULL DEFAULT false,
    "openPath" TEXT NOT NULL,
    "recordDate" TIMESTAMPTZ,
    "contentHash" TEXT NOT NULL,
    "embeddedHash" TEXT,
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "tsv" tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce("title", '')), 'A') ||
        setweight(to_tsvector('english', coalesce("title", '')), 'A') ||
        setweight(to_tsvector('simple', coalesce("subtitle", '') || ' ' || coalesce("place", '')), 'B') ||
        setweight(to_tsvector('english', coalesce("body", '')), 'C') ||
        setweight(to_tsvector('simple', coalesce("body", '')), 'D')
    ) STORED,
    CONSTRAINT "SearchDocument_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "SearchDocument_entityType_entityId_key" UNIQUE ("entityType", "entityId")
);

CREATE INDEX IF NOT EXISTS "SearchDocument_tsv_idx" ON "SearchDocument" USING gin ("tsv");
CREATE INDEX IF NOT EXISTS "SearchDocument_title_trgm_idx"
    ON "SearchDocument" USING gin (lower("title") gin_trgm_ops);
CREATE INDEX IF NOT EXISTS "SearchDocument_craftCodes_idx" ON "SearchDocument" USING gin ("craftCodes");
CREATE INDEX IF NOT EXISTS "SearchDocument_artisanIds_idx" ON "SearchDocument" USING gin ("artisanIds");
CREATE INDEX IF NOT EXISTS "SearchDocument_entityType_recordDate_idx"
    ON "SearchDocument" ("entityType", "recordDate" DESC);

CREATE TABLE IF NOT EXISTS "SearchIndexQueue" (
    "id" BIGSERIAL NOT NULL,
    "entityType" TEXT NOT NULL,
    "entityId" TEXT NOT NULL,
    "enqueuedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "SearchIndexQueue_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "SearchIndexQueue_enqueuedAt_idx" ON "SearchIndexQueue" ("enqueuedAt");

-- Meaning ranking. Only where the extension exists; the column is never declared in Prisma.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
    CREATE EXTENSION IF NOT EXISTS vector;
    ALTER TABLE "SearchDocument" ADD COLUMN IF NOT EXISTS "embedding" vector(768);
    CREATE INDEX IF NOT EXISTS "SearchDocument_embedding_hnsw"
        ON "SearchDocument" USING hnsw ("embedding" vector_cosine_ops);
  END IF;
END $$;

-- Search and record views over the cross-portal channel are logged beside the identity lookups.
-- "userId" is this portal's own account behind a RECORD_VIEW it asked the designer portal for; the
-- designer portal's log already had the column.
ALTER TABLE "CrossPortalAccessLog" ADD COLUMN IF NOT EXISTS "userId" TEXT;
ALTER TABLE "CrossPortalAccessLog" ADD COLUMN IF NOT EXISTS "entityType" TEXT;
ALTER TABLE "CrossPortalAccessLog" ADD COLUMN IF NOT EXISTS "entityId" TEXT;
ALTER TABLE "CrossPortalAccessLog" ADD COLUMN IF NOT EXISTS "resultCount" INTEGER;
CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_remoteUserId_action_createdAt_idx"
    ON "CrossPortalAccessLog" ("remoteUserId", "action", "createdAt");

-- One trigger function for every source table.
--   TG_ARGV[0]  the search type the row feeds
--   TG_ARGV[1]  the column holding the document key (default "id")
-- Keyed on the row's own id: insert/update enqueue it, delete removes the document at once.
-- Keyed on another column (a process step's process, a response's interview, a consent row's
-- artisan): every change enqueues the key, old and new, and the worker rebuilds it.
CREATE OR REPLACE FUNCTION search_enqueue() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  kind TEXT := TG_ARGV[0];
  col TEXT := coalesce(TG_ARGV[1], 'id');
  new_key TEXT;
  old_key TEXT;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    old_key := to_jsonb(OLD) ->> col;
  END IF;
  IF TG_OP <> 'DELETE' THEN
    new_key := to_jsonb(NEW) ->> col;
  END IF;

  IF TG_OP = 'DELETE' AND col = 'id' THEN
    DELETE FROM "SearchDocument" WHERE "entityType" = kind AND "entityId" = old_key;
    RETURN OLD;
  END IF;

  IF new_key IS NOT NULL THEN
    INSERT INTO "SearchIndexQueue" ("entityType", "entityId") VALUES (kind, new_key);
  END IF;
  IF old_key IS NOT NULL AND old_key IS DISTINCT FROM new_key THEN
    INSERT INTO "SearchIndexQueue" ("entityType", "entityId") VALUES (kind, old_key);
  END IF;
  RETURN coalesce(NEW, OLD);
END $$;

DROP TRIGGER IF EXISTS "search_artisans" ON "Artisan";
CREATE TRIGGER "search_artisans" AFTER INSERT OR UPDATE OR DELETE ON "Artisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('artisans');
DROP TRIGGER IF EXISTS "search_artisan_workshops" ON "WorkshopArtisan";
CREATE TRIGGER "search_artisan_workshops" AFTER INSERT OR UPDATE OR DELETE ON "WorkshopArtisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('artisans', 'artisanId');
DROP TRIGGER IF EXISTS "search_artisan_consent" ON "ArtisanSharingConsent";
CREATE TRIGGER "search_artisan_consent" AFTER INSERT OR UPDATE OR DELETE ON "ArtisanSharingConsent"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('artisans', 'artisanId');
DROP TRIGGER IF EXISTS "search_crafts" ON "Craft";
CREATE TRIGGER "search_crafts" AFTER INSERT OR UPDATE OR DELETE ON "Craft"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('crafts');
DROP TRIGGER IF EXISTS "search_workshops" ON "Workshop";
CREATE TRIGGER "search_workshops" AFTER INSERT OR UPDATE OR DELETE ON "Workshop"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('workshops');
DROP TRIGGER IF EXISTS "search_products" ON "ProductDocumentation";
CREATE TRIGGER "search_products" AFTER INSERT OR UPDATE OR DELETE ON "ProductDocumentation"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('products');
DROP TRIGGER IF EXISTS "search_tools" ON "ToolDocumentation";
CREATE TRIGGER "search_tools" AFTER INSERT OR UPDATE OR DELETE ON "ToolDocumentation"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('tools');
DROP TRIGGER IF EXISTS "search_tool_artisans" ON "ToolArtisan";
CREATE TRIGGER "search_tool_artisans" AFTER INSERT OR UPDATE OR DELETE ON "ToolArtisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('tools', 'toolId');
DROP TRIGGER IF EXISTS "search_processes" ON "Process";
CREATE TRIGGER "search_processes" AFTER INSERT OR UPDATE OR DELETE ON "Process"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('processes');
DROP TRIGGER IF EXISTS "search_process_steps" ON "ProcessStep";
CREATE TRIGGER "search_process_steps" AFTER INSERT OR UPDATE OR DELETE ON "ProcessStep"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('processes', 'processId');
DROP TRIGGER IF EXISTS "search_media" ON "MediaFile";
CREATE TRIGGER "search_media" AFTER INSERT OR UPDATE OR DELETE ON "MediaFile"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('media');
DROP TRIGGER IF EXISTS "search_transcripts" ON "MediaFile";
CREATE TRIGGER "search_transcripts" AFTER INSERT OR UPDATE OR DELETE ON "MediaFile"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('transcripts');
DROP TRIGGER IF EXISTS "search_interviews" ON "QuestionnaireInterview";
CREATE TRIGGER "search_interviews" AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireInterview"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('interviews');
DROP TRIGGER IF EXISTS "search_interview_responses" ON "QuestionnaireResponse";
CREATE TRIGGER "search_interview_responses" AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireResponse"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('interviews', 'interviewId');
DROP TRIGGER IF EXISTS "search_interview_artisans" ON "QuestionnaireInterviewArtisan";
CREATE TRIGGER "search_interview_artisans" AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireInterviewArtisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('interviews', 'interviewId');

-- Backfill: list every record once. The worker drains the queue; nothing is built here.
INSERT INTO "SearchIndexQueue" ("entityType", "entityId")
SELECT 'artisans', "id" FROM "Artisan"
UNION ALL SELECT 'crafts', "id" FROM "Craft"
UNION ALL SELECT 'workshops', "id" FROM "Workshop"
UNION ALL SELECT 'products', "id" FROM "ProductDocumentation"
UNION ALL SELECT 'tools', "id" FROM "ToolDocumentation"
UNION ALL SELECT 'processes', "id" FROM "Process"
UNION ALL SELECT 'media', "id" FROM "MediaFile"
UNION ALL SELECT 'transcripts', "id" FROM "MediaFile"
    WHERE "transcriptText" IS NOT NULL AND btrim("transcriptText") <> ''
UNION ALL SELECT 'interviews', "id" FROM "QuestionnaireInterview";
