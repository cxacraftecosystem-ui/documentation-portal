-- U5 (UNIFY_2026-10-21.md section 2.2): answers imported from a hand-filled workbook keep their origin.
-- Idempotent: designer-portal's questionnaire_unify.py --apply runs its copy before it writes; the switch-on commit moves
-- this folder into prisma/migrations, where migrate deploy runs it again as a no-op.
ALTER TYPE "AnswerOrigin" ADD VALUE IF NOT EXISTS 'WORKBOOK';
