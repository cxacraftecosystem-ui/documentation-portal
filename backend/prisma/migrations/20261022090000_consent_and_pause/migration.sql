CREATE TYPE "ConsentState"       AS ENUM ('NOT_RECORDED','GRANTED','REFUSED');
CREATE TYPE "ConsentSubjectType" AS ENUM ('ARTISAN','WORKSHOP','USER','RECORDING');
CREATE TYPE "ConsentPurpose"     AS ENUM ('EXTERNAL_PROCESSING','CROSS_PORTAL_SHARING','USAGE_ANALYTICS');
CREATE TYPE "ConsentBasis"       AS ENUM ('VERBAL_RECORDED','VERBAL_WITNESSED','SIGNED_FORM','OWNER_ATTESTATION');
CREATE TABLE "Consent" (
  "id" TEXT PRIMARY KEY, "subjectType" "ConsentSubjectType" NOT NULL, "subjectId" TEXT NOT NULL,
  "purpose" "ConsentPurpose" NOT NULL, "state" "ConsentState" NOT NULL, "basis" "ConsentBasis" NOT NULL,
  "source" TEXT NOT NULL, "version" INTEGER NOT NULL DEFAULT 1, "note" TEXT, "evidenceMediaId" TEXT,
  "actorId" TEXT NOT NULL REFERENCES "User"("id") ON DELETE RESTRICT,
  "recordedAt" TIMESTAMP(3) NOT NULL, "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX "Consent_subject_idx" ON "Consent"("subjectType","subjectId","purpose","recordedAt");
CREATE UNIQUE INDEX "Consent_idem_key" ON "Consent"("subjectType","subjectId","purpose","source","recordedAt");
CREATE VIEW consent_current_v AS
  SELECT DISTINCT ON ("subjectType","subjectId","purpose") *
  FROM "Consent" ORDER BY "subjectType","subjectId","purpose","recordedAt" DESC, "createdAt" DESC;
ALTER TABLE "AppSetting" ADD COLUMN "transcriptionPaused" BOOLEAN NOT NULL DEFAULT true;
