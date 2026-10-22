-- U5 (UNIFY_2026-10-21.md section 2.1): a title is unique among the SHARED, live forms only, as in
-- designer-portal (the same index by the same name). Researchers may reuse a title on their private
-- forms, so the global "Questionnaire_title_key" goes. Field Repository's own reads by title
-- (find_first, the clash check on rename) need no unique. Deferred: the switch-on commit moves this
-- folder into prisma/migrations together with dropping @@unique([title]) from schema.prisma.
-- Idempotent.
DROP INDEX IF EXISTS "Questionnaire_title_key";
CREATE UNIQUE INDEX IF NOT EXISTS "Questionnaire_shared_title_key"
  ON "Questionnaire"("title") WHERE "isShared" AND "deletedAt" IS NULL;
