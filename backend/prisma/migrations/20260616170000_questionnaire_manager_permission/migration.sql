ALTER TABLE "User"
ADD COLUMN "canManageQuestionnaire" BOOLEAN NOT NULL DEFAULT false;

UPDATE "User"
SET "canManageQuestionnaire" = true
WHERE "role" = 'MASTER_ADMIN';

CREATE INDEX "User_canManageQuestionnaire_idx" ON "User"("canManageQuestionnaire");
