-- One questionnaire interview per EXACT set of artisans.
--
-- Background: the create path was not idempotent, so taps / 504-retries / multiple researchers
-- produced many interview rows for the same artisan set (in prod: 133 rows for 18 real sets). This
-- migration consolidates those into one canonical row per set (preserving all media and responses),
-- backfills a deterministic set key, and adds a UNIQUE index so duplicates can never recur. A subset
-- of artisans is a different set => a different key => a separate (allowed) entry.
--
-- The deploy stops the app before migrating, so no rows change underneath these statements. On a
-- fresh database every step below is a harmless no-op.

-- Step 1: set-key column. NULL for interviews with no artisans (those are not deduped; Postgres
-- treats NULLs as distinct under a unique index, so several artisan-less interviews remain allowed).
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "artisanSetKey" TEXT;

-- Step 2: consolidate duplicates. Key = sorted, comma-joined artisan ids (identical to the app's
-- artisan_set_key()). Canonical row per key = the one with the most attached media, tie-broken by
-- earliest createdAt then id (stable).
-- COLLATE "C" => byte/codepoint ordering, which exactly matches Python's sorted() over the (ASCII)
-- cuid artisan ids. Without it a libc collation could order mixed letters/digits differently and the
-- app's computed key would not match this backfill.
CREATE TEMP TABLE _qi_keys ON COMMIT DROP AS
  SELECT a."interviewId" AS interview_id,
         string_agg(a."artisanId", ',' ORDER BY a."artisanId" COLLATE "C") AS setkey
  FROM "QuestionnaireInterviewArtisan" a
  GROUP BY a."interviewId";

CREATE TEMP TABLE _qi_rank ON COMMIT DROP AS
  SELECT k.interview_id,
         k.setkey,
         row_number() OVER (
           PARTITION BY k.setkey
           ORDER BY (SELECT count(*) FROM "MediaFile" m WHERE m."questionnaireInterviewId" = k.interview_id) DESC,
                    i."createdAt" ASC,
                    k.interview_id ASC
         ) AS rn
  FROM _qi_keys k
  JOIN "QuestionnaireInterview" i ON i.id = k.interview_id;

CREATE TEMP TABLE _qi_dups ON COMMIT DROP AS
  SELECT r.interview_id AS dup_id, c.interview_id AS canon_id
  FROM _qi_rank r
  JOIN _qi_rank c ON c.setkey = r.setkey AND c.rn = 1
  WHERE r.rn > 1;

-- Re-point media from duplicates to the canonical interview: the typed FK ...
UPDATE "MediaFile" m
SET "questionnaireInterviewId" = d.canon_id
FROM _qi_dups d
WHERE m."questionnaireInterviewId" = d.dup_id;

-- ... and the soft string link used by the dataset export zip.
UPDATE "MediaFile" m
SET "linkedRecordId" = d.canon_id
FROM _qi_dups d
WHERE m."linkedRecordType" IN ('questionnaire', 'questionnaireinterview')
  AND m."linkedRecordId" = d.dup_id;

-- Move responses that do not collide with one already on the canonical interview (the rest are
-- redundant and removed by the cascade when the duplicate row is deleted).
UPDATE "QuestionnaireResponse" r
SET "interviewId" = d.canon_id
FROM _qi_dups d
WHERE r."interviewId" = d.dup_id
  AND NOT EXISTS (
    SELECT 1 FROM "QuestionnaireResponse" c
    WHERE c."interviewId" = d.canon_id AND c."questionId" = r."questionId"
  );

-- Delete the duplicate interviews (cascades their artisan links + any leftover colliding responses).
DELETE FROM "QuestionnaireInterview" i
USING _qi_dups d
WHERE i.id = d.dup_id;

-- Step 3: backfill the set key for every surviving interview that has artisans.
UPDATE "QuestionnaireInterview" i
SET "artisanSetKey" = k.setkey
FROM (
  SELECT a."interviewId" AS interview_id,
         string_agg(a."artisanId", ',' ORDER BY a."artisanId" COLLATE "C") AS setkey
  FROM "QuestionnaireInterviewArtisan" a
  GROUP BY a."interviewId"
) k
WHERE i.id = k.interview_id;

-- Step 4: enforce one interview per artisan set going forward.
CREATE UNIQUE INDEX "QuestionnaireInterview_artisanSetKey_key" ON "QuestionnaireInterview"("artisanSetKey");
