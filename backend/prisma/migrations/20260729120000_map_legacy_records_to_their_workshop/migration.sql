-- Map the records that were captured BEFORE `workshopId` existed to the workshop they were captured at.
--
-- THE BUG THIS CLOSES. Every screen that narrows by workshop reads `workshopId` — the search box, the
-- map, the data browser, the consolidated questionnaire, the XLSX export, and the completion matrix's
-- derived green. A row with a NULL `workshopId` therefore counts towards NO workshop scope, while
-- remaining perfectly visible under "All records". Since the app OPENS scoped to the most recent
-- workshop, that reads as "nothing was documented at this workshop" rather than as a filter excluding
-- data that is sitting right there.
--
-- In production it was exactly that: 25 questionnaire interviews and 924 media files, every one of them
-- recorded at the single workshop in the repository (17-27 June 2026), none of them carrying its id.
-- The completion matrix showed only the admin overrides — which are keyed on (artisan, section) with no
-- workshop at all — so the workshop looked empty apart from the cells a person had ticked by hand.
--
-- WHY A MIGRATION AND NOT ONLY THE ADMIN BUTTON. `GET /workshops/unmapped` +
-- `POST /workshops/unmapped/map` do exactly this, on demand, for gaps that appear later. This closes
-- the gap that exists NOW, on deploy, without anybody having to remember. The two share one ladder by
-- design — see `backend/app/services/workshop_inference.py`, whose module header is the specification
-- these statements implement — so the migration and the button can never disagree about a row.
--
-- THE LADDER, strongest evidence first, identical to the service:
--   PARENT    the row hangs off a record that already names a workshop (a clip's interview, a
--             process's product, an artisan's roster row). Not inference: the same fact, read off the
--             parent.
--   ARTISANS  every artisan the row covers points at the SAME single workshop, by either
--             `Artisan.workshopId` or the `WorkshopArtisan` roster. Both count, because the column
--             arrived after the roster and a row predating it has only the roster.
--   WINDOW    the capture stamp falls inside exactly ONE workshop's dates.
--
-- AMBIGUITY LOSES AND STOPS THE LADDER. A rung naming two workshops does NOT fall through to a weaker
-- rung that would break the tie arbitrarily — an interview whose artisans span two workshops needs a
-- person, and the date window would silently pick one of the two and make the row look mapped. So each
-- rung is `n = 1` to resolve, `n IS NULL` (no evidence at all) to fall through, and anything else stops.
--
-- "ONLY ONE WORKSHOP EXISTS, SO EVERYTHING IS ITS" IS DELIBERATELY NOT A RUNG. It would sweep up the
-- rows that are correctly unassigned — anything recorded before the workshop existed — and afterwards
-- there would be no way to tell them back apart.
--
-- THE STEP ORDER IS A CORRECTNESS ORDER, and it is the same cascade `workshop_inference.run_ladder`
-- walks. Each step may produce parents for the steps below it, so nothing is decided after something
-- that could inherit from it: artisans parent products, tools and media; products parent processes and
-- media; interviews parent media. Reordering these statements would make the migration and the service
-- disagree about a row, which is the one way they can drift.
--
-- IDEMPOTENT AND NON-DESTRUCTIVE. Every UPDATE carries `"workshopId" IS NULL`, so re-running changes
-- nothing, a fresh database is a no-op, and a row a person assigned by hand is never overwritten.
-- Nothing here clears or moves an existing link.
--
-- Craft is deliberately untouched. A craft is taxonomy — "Dabu hand block printing" did not happen at a
-- workshop — and no screen narrows crafts by workshop, so filling the column would invent a fact.
--
-- All timestamp columns here are TIMESTAMP(3) WITHOUT TIME ZONE, so every comparison below is in one
-- frame and no cast is needed.

-- The workshop windows, once, as HALF-OPEN spans [w_start, w_end) covering WHOLE DAYS.
--
-- A workshop with neither `startDate` nor the legacy `date` is skipped: a window with no start cannot
-- contain anything, and treating it as beginning at the epoch would make it contain everything.
--
-- WHY `w_end` IS MIDNIGHT OF THE DAY AFTER `endDate`, and not `endDate` itself. An end date is a DAY,
-- not an instant, and how that day is stored depends on which client wrote the workshop: the web form
-- sends the last millisecond (`…T23:59:59.999`), Android sends MIDNIGHT of that day, and
-- `workshops.normalize_workshop_dates` copies `startDate` into `endDate` whenever the payload omits one
-- — so a single-day workshop very often has `endDate = startDate`, at midnight. Reading `endDate` as an
-- instant therefore had two silent failure modes: a midnight end excluded the workshop's entire final
-- day, and `endDate = startDate` produced a ZERO-LENGTH window in which the WINDOW rung could never
-- fire at all. `date_trunc` + one day fixes both by construction, whichever client wrote the row. An
-- `endDate` before its `startDate` is a typo rather than a window, and is read as the start day alone.
--
-- This is deliberately NOT the arithmetic in `workshop_access.describe_workshop_submission`, which adds
-- a day to the raw instant: that answers "is this submission LATE?", where generosity is the point.
-- This answers "was it recorded DURING the workshop?", where an extra 24 hours would silently claim the
-- next day's records — and where two workshops really do touch, an overlap the ladder reports as
-- AMBIGUOUS is the honest outcome, not a row quietly assigned to one of them.
CREATE TEMP TABLE _ws_window ON COMMIT DROP AS
  SELECT w.id AS workshop_id,
         COALESCE(w."startDate", w."date") AS w_start,
         CASE
           WHEN date_trunc('day', COALESCE(w."endDate", w."startDate", w."date")) + interval '1 day'
                > COALESCE(w."startDate", w."date")
             THEN date_trunc('day', COALESCE(w."endDate", w."startDate", w."date")) + interval '1 day'
           ELSE date_trunc('day', COALESCE(w."startDate", w."date")) + interval '1 day'
         END AS w_end
  FROM "Workshop" w
  WHERE COALESCE(w."startDate", w."date") IS NOT NULL;

-- Step 1: artisans — PARENT (their roster row, a workshop saying "this person was here"), then WINDOW.
-- First, because an artisan is a parent of products, tools and media, and new evidence for any interview
-- they sat in.
WITH rung_parent AS (
  SELECT wa."artisanId" AS row_id,
         count(DISTINCT wa."workshopId") AS n,
         min(wa."workshopId") AS workshop_id
    FROM "WorkshopArtisan" wa
   GROUP BY wa."artisanId"
), rung_window AS (
  SELECT a.id AS row_id, count(*) AS n, min(v.workshop_id) AS workshop_id
    FROM "Artisan" a
    JOIN _ws_window v ON COALESCE(a."recordedAt", a."createdAt") >= v.w_start AND COALESCE(a."recordedAt", a."createdAt") < v.w_end
   WHERE a."workshopId" IS NULL
   GROUP BY a.id
), resolved AS (
  SELECT a.id AS row_id,
         CASE
           WHEN rp.n = 1 THEN rp.workshop_id
           WHEN rp.n IS NULL AND rw.n = 1 THEN rw.workshop_id
         END AS workshop_id
    FROM "Artisan" a
    LEFT JOIN rung_parent rp ON rp.row_id = a.id
    LEFT JOIN rung_window rw ON rw.row_id = a.id
   WHERE a."workshopId" IS NULL
)
UPDATE "Artisan" a
   SET "workshopId" = r.workshop_id
  FROM resolved r
 WHERE a.id = r.row_id AND r.workshop_id IS NOT NULL AND a."workshopId" IS NULL;

-- artisanId -> workshopId, by BOTH routes, de-duplicated by UNION. Built AFTER step 1 so an artisan
-- mapped a moment ago is evidence for the interviews they sat in.
CREATE TEMP TABLE _artisan_ws ON COMMIT DROP AS
  SELECT a.id AS artisan_id, a."workshopId" AS workshop_id
    FROM "Artisan" a
   WHERE a."workshopId" IS NOT NULL
  UNION
  SELECT wa."artisanId", wa."workshopId"
    FROM "WorkshopArtisan" wa;

-- Step 2: interviews — ARTISANS, then WINDOW. Before media, because 566 of the unassigned media files
-- hang off these interviews and must be carried along by the same pass.
WITH rung_artisans AS (
  SELECT l."interviewId" AS row_id,
         count(DISTINCT aw.workshop_id) AS n,
         min(aw.workshop_id) AS workshop_id
    FROM "QuestionnaireInterviewArtisan" l
    JOIN _artisan_ws aw ON aw.artisan_id = l."artisanId"
   GROUP BY l."interviewId"
), rung_window AS (
  SELECT i.id AS row_id,
         count(*) AS n,
         min(v.workshop_id) AS workshop_id
    FROM "QuestionnaireInterview" i
    JOIN _ws_window v
      ON COALESCE(i."recordedAt", i."interviewDate", i."createdAt") >= v.w_start AND COALESCE(i."recordedAt", i."interviewDate", i."createdAt") < v.w_end
   WHERE i."workshopId" IS NULL
   GROUP BY i.id
), resolved AS (
  SELECT i.id AS row_id,
         CASE
           WHEN ra.n = 1 THEN ra.workshop_id
           WHEN ra.n IS NULL AND rw.n = 1 THEN rw.workshop_id
         END AS workshop_id
    FROM "QuestionnaireInterview" i
    LEFT JOIN rung_artisans ra ON ra.row_id = i.id
    LEFT JOIN rung_window  rw ON rw.row_id = i.id
   WHERE i."workshopId" IS NULL
)
UPDATE "QuestionnaireInterview" i
   SET "workshopId" = r.workshop_id
  FROM resolved r
 WHERE i.id = r.row_id
   AND r.workshop_id IS NOT NULL
   AND i."workshopId" IS NULL;

-- Step 3: products — PARENT (the artisan they document), then WINDOW.
WITH rung_parent AS (
  SELECT p.id AS row_id, a."workshopId" AS workshop_id
    FROM "ProductDocumentation" p
    JOIN "Artisan" a ON a.id = p."artisanId"
   WHERE p."workshopId" IS NULL AND a."workshopId" IS NOT NULL
), rung_window AS (
  SELECT p.id AS row_id, count(*) AS n, min(v.workshop_id) AS workshop_id
    FROM "ProductDocumentation" p
    JOIN _ws_window v ON COALESCE(p."recordedAt", p."createdAt") >= v.w_start AND COALESCE(p."recordedAt", p."createdAt") < v.w_end
   WHERE p."workshopId" IS NULL
   GROUP BY p.id
), resolved AS (
  SELECT p.id AS row_id,
         CASE
           WHEN rp.workshop_id IS NOT NULL THEN rp.workshop_id
           WHEN rp.workshop_id IS NULL AND rw.n = 1 THEN rw.workshop_id
         END AS workshop_id
    FROM "ProductDocumentation" p
    LEFT JOIN rung_parent rp ON rp.row_id = p.id
    LEFT JOIN rung_window rw ON rw.row_id = p.id
   WHERE p."workshopId" IS NULL
)
UPDATE "ProductDocumentation" p
   SET "workshopId" = r.workshop_id
  FROM resolved r
 WHERE p.id = r.row_id AND r.workshop_id IS NOT NULL AND p."workshopId" IS NULL;

-- Step 4: tools — the same two rungs as products.
WITH rung_parent AS (
  SELECT t.id AS row_id, a."workshopId" AS workshop_id
    FROM "ToolDocumentation" t
    JOIN "Artisan" a ON a.id = t."artisanId"
   WHERE t."workshopId" IS NULL AND a."workshopId" IS NOT NULL
), rung_window AS (
  SELECT t.id AS row_id, count(*) AS n, min(v.workshop_id) AS workshop_id
    FROM "ToolDocumentation" t
    JOIN _ws_window v ON COALESCE(t."recordedAt", t."createdAt") >= v.w_start AND COALESCE(t."recordedAt", t."createdAt") < v.w_end
   WHERE t."workshopId" IS NULL
   GROUP BY t.id
), resolved AS (
  SELECT t.id AS row_id,
         CASE
           WHEN rp.workshop_id IS NOT NULL THEN rp.workshop_id
           WHEN rp.workshop_id IS NULL AND rw.n = 1 THEN rw.workshop_id
         END AS workshop_id
    FROM "ToolDocumentation" t
    LEFT JOIN rung_parent rp ON rp.row_id = t.id
    LEFT JOIN rung_window rw ON rw.row_id = t.id
   WHERE t."workshopId" IS NULL
)
UPDATE "ToolDocumentation" t
   SET "workshopId" = r.workshop_id
  FROM resolved r
 WHERE t.id = r.row_id AND r.workshop_id IS NOT NULL AND t."workshopId" IS NULL;

-- Step 5: processes — PARENT (their product, which is NOT NULL on the model), then WINDOW. After step 3
-- so a process inherits a workshop its product gained a moment ago; that is the older of the two
-- readings of "which workshop was this process documented at" — before the column existed a process
-- reached a workshop only through its product — and this keeps the two answers identical.
WITH rung_parent AS (
  SELECT pr.id AS row_id, p."workshopId" AS workshop_id
    FROM "Process" pr
    JOIN "ProductDocumentation" p ON p.id = pr."productId"
   WHERE pr."workshopId" IS NULL AND p."workshopId" IS NOT NULL
), rung_window AS (
  SELECT pr.id AS row_id, count(*) AS n, min(v.workshop_id) AS workshop_id
    FROM "Process" pr
    JOIN _ws_window v ON COALESCE(pr."recordedAt", pr."createdAt") >= v.w_start AND COALESCE(pr."recordedAt", pr."createdAt") < v.w_end
   WHERE pr."workshopId" IS NULL
   GROUP BY pr.id
), resolved AS (
  SELECT pr.id AS row_id,
         CASE
           WHEN rp.workshop_id IS NOT NULL THEN rp.workshop_id
           WHEN rp.workshop_id IS NULL AND rw.n = 1 THEN rw.workshop_id
         END AS workshop_id
    FROM "Process" pr
    LEFT JOIN rung_parent rp ON rp.row_id = pr.id
    LEFT JOIN rung_window rw ON rw.row_id = pr.id
   WHERE pr."workshopId" IS NULL
)
UPDATE "Process" pr
   SET "workshopId" = r.workshop_id
  FROM resolved r
 WHERE pr.id = r.row_id AND r.workshop_id IS NOT NULL AND pr."workshopId" IS NULL;

-- Step 6: media — PARENT, then WINDOW. LAST, so every parent above has already been mapped.
--
-- THE PARENT ORDER IS NARROWEST FIRST and it matters: a questionnaire clip carries an interview id and
-- sometimes also the artisan it was tagged with, and the interview is the sitting the clip was recorded
-- in, so it is the truer owner of the two. COALESCE walks exactly the order
-- `media.inherit_parent_workshop` walks, so an upload arriving through the API and a legacy row fixed
-- here land on the same workshop.
-- The LAST arm of the COALESCE is a STRING TAG, not a column, and it has to be: a clip attached to a
-- Process has no typed foreign key to read — MediaFile has no `processId`, because the model has none —
-- so its parent is reachable only through `linkedRecordType`/`linkedRecordId`. `media.create` inherits
-- the same way through `_tagged_parent`, so an upload arriving through the API and a legacy row fixed
-- here land on the same workshop.
WITH rung_parent AS (
  SELECT m.id AS row_id,
         COALESCE(qi."workshopId", p."workshopId", t."workshopId", a."workshopId", c."workshopId",
                  prc."workshopId")
           AS workshop_id
    FROM "MediaFile" m
    LEFT JOIN "QuestionnaireInterview"  qi ON qi.id = m."questionnaireInterviewId"
    LEFT JOIN "ProductDocumentation"    p  ON p.id  = m."productId"
    LEFT JOIN "ToolDocumentation"       t  ON t.id  = m."toolId"
    LEFT JOIN "Artisan"                 a  ON a.id  = m."artisanId"
    LEFT JOIN "Craft"                   c  ON c.id  = m."craftId"
    LEFT JOIN "Process"                 prc ON lower(m."linkedRecordType") = 'process'
                                           AND prc.id = m."linkedRecordId"
   WHERE m."workshopId" IS NULL
), rung_window AS (
  SELECT m.id AS row_id, count(*) AS n, min(v.workshop_id) AS workshop_id
    FROM "MediaFile" m
    JOIN _ws_window v ON COALESCE(m."recordedAt", m."createdAt") >= v.w_start AND COALESCE(m."recordedAt", m."createdAt") < v.w_end
   WHERE m."workshopId" IS NULL
   GROUP BY m.id
), resolved AS (
  SELECT m.id AS row_id,
         CASE
           WHEN rp.workshop_id IS NOT NULL THEN rp.workshop_id
           WHEN rp.workshop_id IS NULL AND rw.n = 1 THEN rw.workshop_id
         END AS workshop_id
    FROM "MediaFile" m
    LEFT JOIN rung_parent rp ON rp.row_id = m.id
    LEFT JOIN rung_window rw ON rw.row_id = m.id
   WHERE m."workshopId" IS NULL
)
UPDATE "MediaFile" m
   SET "workshopId" = r.workshop_id
  FROM resolved r
 WHERE m.id = r.row_id AND r.workshop_id IS NOT NULL AND m."workshopId" IS NULL;
