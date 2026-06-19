-- Expand feedback into a more detailed, structured form: per-aspect quantitative sub-ratings (1–5)
-- alongside the overall rating, plus targeted qualitative prompts alongside the general comment.
ALTER TABLE "Feedback" ADD COLUMN "easeOfUse" INTEGER;
ALTER TABLE "Feedback" ADD COLUMN "reliability" INTEGER;
ALTER TABLE "Feedback" ADD COLUMN "performance" INTEGER;
ALTER TABLE "Feedback" ADD COLUMN "design" INTEGER;
ALTER TABLE "Feedback" ADD COLUMN "features" INTEGER;
ALTER TABLE "Feedback" ADD COLUMN "recommend" INTEGER;
ALTER TABLE "Feedback" ADD COLUMN "likeMost" TEXT;
ALTER TABLE "Feedback" ADD COLUMN "improve" TEXT;
ALTER TABLE "Feedback" ADD COLUMN "bugs" TEXT;
ALTER TABLE "Feedback" ADD COLUMN "featureRequests" TEXT;
ALTER TABLE "Feedback" ADD COLUMN "role" TEXT;
