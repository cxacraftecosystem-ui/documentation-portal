-- Web capture (2026-10-17): three additive columns, safe on a live database (no rewrite, no lock
-- beyond the catalogue update, every existing row keeps its meaning).
--
-- Artisan.aadhaarNotShared: the explicit "Not shared by artisan" outcome (C18). Defaults to false,
-- which is what every existing row already means.
-- QuestionnaireQuestion.artisanField: which artisan field a question is answered from (B8). NULL on
-- every existing question, which keeps today's behaviour.
-- QuestionnaireResponse.answerSource: "TRANSCRIPT" when an answer was taken from its clip (A7). NULL
-- on every existing answer, which is what a typed answer is.

ALTER TABLE "Artisan" ADD COLUMN "aadhaarNotShared" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "QuestionnaireQuestion" ADD COLUMN "artisanField" TEXT;
ALTER TABLE "QuestionnaireResponse" ADD COLUMN "answerSource" TEXT;
