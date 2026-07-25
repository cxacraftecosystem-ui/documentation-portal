-- Make processes and questionnaire interviews reviewable.
--
-- Both carry a `status` column and both can now be forced to PENDING by the late-submission gate
-- (services/workshop_access.py::pin_pending_if_late), but ReviewRecordType had no value for them,
-- so POST /review/process/{id}/approve 404'd and a late process or interview could never leave
-- PENDING. These two enum values close that dead end.
--
-- Additive only: existing ReviewLog rows are untouched. (Postgres 12+ allows ADD VALUE inside a
-- migration transaction as long as the new value is not used in the same transaction — nothing
-- here uses them.)
ALTER TYPE "ReviewRecordType" ADD VALUE IF NOT EXISTS 'PROCESS';
ALTER TYPE "ReviewRecordType" ADD VALUE IF NOT EXISTS 'QUESTIONNAIRE';
