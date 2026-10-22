-- A form imported from a handoff bundle remembers the bundle, so a second import of the same file by
-- the same person returns the first copy (UNIFY A10; shared/questionnaire-bundle.json). Additive.
ALTER TABLE "Questionnaire" ADD COLUMN "importedBundleId" TEXT;
CREATE UNIQUE INDEX "Questionnaire_importedBundleId_ownerId_key" ON "Questionnaire"("importedBundleId", "ownerId");
