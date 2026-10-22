-- UNIFY U7 / A2: an answer imported from a hand-filled workbook (an UPLOAD session) carries origin WORKBOOK.
-- Additive; the same statement in both portals. ADD VALUE is not used in this migration, so it may run in its transaction.
ALTER TYPE "AnswerOrigin" ADD VALUE IF NOT EXISTS 'WORKBOOK';
