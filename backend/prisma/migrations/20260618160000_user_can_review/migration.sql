-- Distributable record-review permission (granted by the master admin, like questionnaire access).
ALTER TABLE "User" ADD COLUMN "canReview" BOOLEAN NOT NULL DEFAULT false;
