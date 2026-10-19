-- QUESTIONNAIRE V2, M3 (DESIGN_2026-10-18.md section 7.1). WRITTEN NOW, APPLIED LATER.
--
-- It lives in prisma/migrations_pending/ and not in prisma/migrations/ so that `prisma migrate
-- deploy` cannot apply it with M1. Rollout step 6 moves this folder into prisma/migrations/ once the
-- v2 delete routes (which answer 409 HAS_RECORDINGS / PARTICIPANT_HAS_TAKES) have been live and the
-- backfill has run. schema.prisma keeps the old onDelete until then; the move commit changes both.
--
-- What it does: an artisan who is a participant can no longer be deleted out from under a session,
-- and a session that holds clips can no longer be deleted out from under them. Both were SET NULL or
-- CASCADE, which is how 173 orphan objects and artisan-less sessions came about.

ALTER TABLE "QuestionnaireInterviewArtisan" DROP CONSTRAINT "QuestionnaireInterviewArtisan_artisanId_fkey";
ALTER TABLE "QuestionnaireInterviewArtisan" ADD CONSTRAINT "QuestionnaireInterviewArtisan_artisanId_fkey"
  FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "MediaFile" DROP CONSTRAINT "MediaFile_questionnaireInterviewId_fkey";
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_questionnaireInterviewId_fkey"
  FOREIGN KEY ("questionnaireInterviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
