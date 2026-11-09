ALTER TABLE "QuestionnaireInterview" ADD COLUMN IF NOT EXISTS "crafts" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "QuestionnaireInterview" ADD COLUMN IF NOT EXISTS "craftsByHand" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

UPDATE "QuestionnaireInterview" i
SET "crafts" = sub.crafts
FROM (
  SELECT x."interviewId" AS id, ARRAY_AGG(DISTINCT c."name" ORDER BY c."name") AS crafts
  FROM "QuestionnaireInterviewArtisan" x
  JOIN "Artisan" a ON a."id" = x."artisanId"
  JOIN "Craft" c ON c."id" = a."craftId"
  WHERE c."name" IS NOT NULL AND btrim(c."name") <> ''
  GROUP BY x."interviewId"
) sub
WHERE i."id" = sub.id AND cardinality(i."crafts") = 0;
