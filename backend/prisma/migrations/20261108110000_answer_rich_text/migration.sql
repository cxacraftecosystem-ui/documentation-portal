-- Decision C8 (questionnaire unification): an answer keeps canonical plain text (coverage, the lock,
-- search and exports read it) and, beside it, the rich-text document an editor wrote, in
-- designer-portal's stored format. Additive and nullable; the same column in documentation-portal.
ALTER TABLE "QuestionnaireAnswerRevision" ADD COLUMN IF NOT EXISTS "richText" TEXT;
