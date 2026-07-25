-- Give QuestionnaireInterview the review-audit columns every other reviewable record already had.
--
-- THE BUG THIS FIXES (reproduced against production): interviews became reviewable when
-- `questionnaire` was added to review.py's delegate_for, but the table had no reviewNotes /
-- reviewedById / reviewedAt. Every approve therefore raised
--
--     prisma.errors.FieldNotFoundError:
--         Could not find field at `updateOneQuestionnaireInterview.data.reviewNotes`
--
-- from services/records.py::review_update, which writes all four review fields for every record
-- type. The exception escaped as a bare text/plain 500 with NO CORS headers, so the browser
-- reported "Failed to fetch" and Android showed HTTP 500. All 25 rows in the production review
-- queue are interviews, so approving anything at all was impossible.
--
-- Three nullable columns, no backfill, no rewrite: existing interviews simply have no review
-- recorded yet, which is exactly true. Matches the Artisan/Workshop/Product/Tool/Process/Media
-- declaration byte for byte (reviewedById is a plain scalar there too, with no FK).
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "reviewNotes" TEXT;
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "reviewedById" TEXT;
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "reviewedAt" TIMESTAMP(3);
