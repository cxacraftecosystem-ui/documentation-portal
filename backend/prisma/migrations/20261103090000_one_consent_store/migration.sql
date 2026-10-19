-- ONE CONSENT STORE (owner decision, 19 Oct 2026). The same file in designer-portal and
-- documentation-portal, listed in shared/transcription.sha256 and read by app/transcription/ledger.py.
--
-- Additive and safe on a live database: every statement is guarded, so it creates the ledger where
-- it does not exist yet (designer-portal), only adds the copy marker where it does
-- (documentation-portal), and is a no-op when run twice. Nothing is dropped, renamed or rewritten;
-- the older consent tables stay readable until each portal's data script has copied them in.

DO $$ BEGIN
  CREATE TYPE "ConsentState" AS ENUM ('NOT_RECORDED', 'GRANTED', 'REFUSED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE TYPE "ConsentSubjectType" AS ENUM ('ARTISAN', 'WORKSHOP', 'USER', 'RECORDING');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE TYPE "ConsentPurpose" AS ENUM ('EXTERNAL_PROCESSING', 'CROSS_PORTAL_SHARING', 'USAGE_ANALYTICS');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE TYPE "ConsentBasis" AS ENUM ('VERBAL_RECORDED', 'VERBAL_WITNESSED', 'SIGNED_FORM', 'OWNER_ATTESTATION');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS "Consent" (
  "id" TEXT PRIMARY KEY,
  "subjectType" "ConsentSubjectType" NOT NULL,
  "subjectId" TEXT NOT NULL,
  "purpose" "ConsentPurpose" NOT NULL,
  "state" "ConsentState" NOT NULL,
  "basis" "ConsentBasis" NOT NULL,
  "source" TEXT NOT NULL,
  "version" INTEGER NOT NULL DEFAULT 1,
  "note" TEXT,
  "evidenceMediaId" TEXT,
  "actorId" TEXT NOT NULL REFERENCES "User"("id") ON DELETE RESTRICT,
  "recordedAt" TIMESTAMP(3) NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Where a row was copied from ("RecordingConsent:<id>", "DwWorkshopConsentDecision:<id>"): NULL for
-- a consent recorded in the ledger itself. Unique, so a data script run twice copies nothing twice.
ALTER TABLE "Consent" ADD COLUMN IF NOT EXISTS "migratedFrom" TEXT;

CREATE INDEX IF NOT EXISTS "Consent_subject_idx" ON "Consent"("subjectType", "subjectId", "purpose", "recordedAt");
CREATE UNIQUE INDEX IF NOT EXISTS "Consent_idem_key" ON "Consent"("subjectType", "subjectId", "purpose", "source", "recordedAt");
CREATE UNIQUE INDEX IF NOT EXISTS "Consent_migratedFrom_key" ON "Consent"("migratedFrom");
CREATE INDEX IF NOT EXISTS "Consent_actorId_idx" ON "Consent"("actorId");

-- The newest row per subject and purpose. Replaced so it carries the new column too.
CREATE OR REPLACE VIEW consent_current_v AS
  SELECT DISTINCT ON ("subjectType", "subjectId", "purpose") *
  FROM "Consent"
  ORDER BY "subjectType", "subjectId", "purpose", "recordedAt" DESC, "createdAt" DESC;
