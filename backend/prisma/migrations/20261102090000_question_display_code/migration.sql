-- QUESTION DISPLAY CODES: READ THE NUMBER AFTER THE SECTION CODE, NOT THE TRAILING DIGITS.
--
-- The insert trigger from 20261025090000_questionnaire_v2 took the next number from the trailing
-- digits of the existing codes ('[0-9]+$'). When the section code itself ends in a digit those digits
-- were read as part of the number: section "F2" got F21, then F222 (21 + 1 = 22 after "F2"), then
-- F2223, and a section code that is all digits after a letter ("S-1729") overflowed integer within a
-- few inserts. The regex also matched a sibling section that extends this one ("F" read "F21" from
-- section "F2"), and the section code was spliced into the pattern unescaped.
--
-- The replacement strips the section code as a literal prefix, reads only what follows it, counts
-- only rows of the same section, ignores a remainder longer than 9 digits (so it can never overflow
-- integer), and steps past any code the instrument already holds, so the partial unique index on
-- (questionnaireId, displayCode) is never the thing that catches a collision.
--
-- ADDITIVE AND LIVE-SAFE: CREATE OR REPLACE of one function (the trigger keeps pointing at it), plus
-- a backfill that only touches codes the old trigger could have mis-assigned and nothing reads yet.
-- Production on 19 Oct 2026, read-only: 366 questions, none under a section code ending in a digit,
-- no mis-assigned code; the backfill is a no-op there and exists for every other database.

CREATE OR REPLACE FUNCTION questionnaire_question_lineage_ins() RETURNS trigger AS $$
DECLARE
  sec text := upper(COALESCE(NEW."sectionCode", ''));
  next_n integer;
BEGIN
  IF NEW."lineageId" IS NULL THEN NEW."lineageId" := NEW."id"; END IF;
  IF NEW."displayCode" IS NULL THEN
    SELECT COALESCE(max(substr(q."displayCode", length(sec) + 1)::integer), 0) + 1 INTO next_n
      FROM "QuestionnaireQuestion" q
     WHERE q."questionnaireId" = NEW."questionnaireId"
       AND upper(q."sectionCode") = sec
       AND starts_with(q."displayCode", sec)
       AND substr(q."displayCode", length(sec) + 1) ~ '^[0-9]{1,9}$';
    WHILE EXISTS (SELECT 1 FROM "QuestionnaireQuestion" q
                   WHERE q."questionnaireId" = NEW."questionnaireId" AND q."displayCode" = sec || next_n::text) LOOP
      next_n := next_n + 1;
    END LOOP;
    NEW."displayCode" := sec || next_n::text;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- ── backfill: codes the old trigger mis-assigned ───────────────────────────────────────────────
-- A lineage root is well formed when its code is its section code followed by n, with n between 1
-- and the number of roots in that section and no earlier root of the section holding the same n. The
-- v2 backfill numbered every root that existed then 1..k, and the fixed trigger continues from the
-- maximum, so only the old trigger produces anything else. A mis-assigned root takes the smallest
-- number not held by any code in the instrument, and the whole lineage (a reword carries the frozen
-- code) moves with it. A root is LEFT ALONE, and named in a NOTICE, when any row of its lineage is
-- referenced by an answer, a draft, a revision or a recording target: a code someone may already
-- have read, filed or exported does not move under them.
DO $$
DECLARE
  r record;
  n integer;
  moved integer := 0;
  kept integer := 0;
BEGIN
  FOR r IN
    WITH roots AS (
      SELECT q."id", q."questionnaireId" AS qn, upper(q."sectionCode") AS sec, q."displayCode" AS code, q."createdAt",
             count(*) OVER (PARTITION BY q."questionnaireId", upper(q."sectionCode")) AS k
        FROM "QuestionnaireQuestion" q WHERE q."lineageId" = q."id"
    ), parsed AS (
      SELECT ro.*, CASE WHEN starts_with(ro.code, ro.sec) AND substr(ro.code, length(ro.sec) + 1) ~ '^[1-9][0-9]{0,8}$'
                        THEN substr(ro.code, length(ro.sec) + 1)::integer END AS n
        FROM roots ro
    ), flagged AS (
      SELECT p.*, row_number() OVER (PARTITION BY p.qn, p.sec, p.n ORDER BY p."createdAt", p."id") AS dup FROM parsed p
    )
    SELECT f."id", f.qn, f.sec, f.code,
           EXISTS (SELECT 1 FROM "QuestionnaireQuestion" m WHERE m."lineageId" = f."id" AND (
                EXISTS (SELECT 1 FROM "QuestionnaireResponse" x WHERE x."questionId" = m."id")
             OR EXISTS (SELECT 1 FROM "QuestionnaireAnswerDraft" x WHERE x."questionId" = m."id")
             OR EXISTS (SELECT 1 FROM "QuestionnaireAnswer" x WHERE x."questionLineageId" = m."id")
             OR EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" x WHERE x."askedQuestionId" = m."id")
             OR EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" x
                         WHERE x."questionLineageId" = m."id" OR x."askedQuestionId" = m."id"))) AS referenced
      FROM flagged f
     WHERE f.code IS NULL OR f.n IS NULL OR f.n > f.k OR f.dup > 1
     ORDER BY f.qn, f.sec, f."createdAt", f."id"
  LOOP
    IF r.referenced THEN
      kept := kept + 1;
      RAISE NOTICE 'display code kept (referenced): question % in instrument %, section %, code %', r."id", r.qn, r.sec, r.code;
      CONTINUE;
    END IF;
    n := 1;
    WHILE EXISTS (SELECT 1 FROM "QuestionnaireQuestion" q WHERE q."questionnaireId" = r.qn AND q."displayCode" = r.sec || n::text) LOOP
      n := n + 1;
    END LOOP;
    UPDATE "QuestionnaireQuestion" SET "displayCode" = r.sec || n::text WHERE "lineageId" = r."id";
    moved := moved + 1;
  END LOOP;
  RAISE NOTICE 'question display codes: % renumbered, % kept because referenced', moved, kept;
END $$;
