-- Index coverage: match the indexes to the queries the routes actually issue.
--
-- HOW TO APPLY THIS ONE. There are two files in this directory and they do the same work:
--
--   apply_concurrently.sql  the one to run against production, by hand, BEFORE deploying
--   migration.sql           this file, which `prisma migrate deploy` runs during the deploy
--
-- They exist as a pair because CREATE INDEX CONCURRENTLY cannot run inside a transaction block,
-- and `prisma migrate deploy` sends a migration file's statements as ONE multi-statement query,
-- which Postgres wraps in an implicit transaction. Putting CONCURRENTLY here does not degrade
-- gracefully — it fails the deploy outright with
--
--     ERROR 25001: CREATE INDEX CONCURRENTLY cannot run inside a transaction block
--     Prisma P3018: A migration failed to apply. New migrations cannot be applied before the
--                   error is recovered from.
--
-- which also leaves a failed row in _prisma_migrations that blocks every later migration until
-- somebody runs `prisma migrate resolve`. (Verified against Prisma 5.17 / Postgres 16: a file with
-- a SINGLE concurrent statement is accepted; two or more is not. Do not rely on the single-statement
-- case — it is an accident of how the file is sent, not a guarantee.)
--
-- So the intended sequence is:
--
--   1. psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f apply_concurrently.sql
--      psql runs each statement in its own implicit transaction, so CONCURRENTLY is legal there.
--      No table is write-locked; the app keeps serving throughout.
--   2. Deploy as usual. `prisma migrate deploy` then runs THIS file, every statement of which is
--      IF EXISTS / IF NOT EXISTS and therefore a no-op against the work step 1 already did. The
--      migration is recorded, history stays honest, and nothing is rebuilt.
--
-- If step 1 is skipped, this file still produces exactly the right schema — it just builds the
-- indexes with a brief ACCESS SHARE/SHARE lock instead. At today's volumes (925 media rows, the
-- largest table) that is single-digit milliseconds and nobody notices. At a hundred times the data
-- it would not be, which is the whole reason step 1 exists.

-- ---------------------------------------------------------------------------------------------
-- ADDED (15)
-- ---------------------------------------------------------------------------------------------

-- MediaFile's parent foreign keys. Nothing writes `where={"toolId": …}` by hand, which is why these
-- were missed: they serve the reverse walk that `include={"media": True}` compiles to, a separate
-- `WHERE "<fk>" IN (…the page's twenty parents…)`. Unindexed, that is a sequential scan of the
-- biggest table in the database on every page of the tools and products lists. Measured on a 100x
-- copy of production (92,500 media rows): the tools-list media fetch went from a 2,304-buffer
-- sequential scan discarding 92,413 rows in 87.8 ms, to a 57-buffer bitmap index scan in 0.95 ms.
-- Equality lookups with no sort to fold in, so single-column is the right shape.
CREATE INDEX IF NOT EXISTS "MediaFile_artisanId_idx" ON "MediaFile" ("artisanId");
CREATE INDEX IF NOT EXISTS "MediaFile_productId_idx" ON "MediaFile" ("productId");
CREATE INDEX IF NOT EXISTS "MediaFile_toolId_idx"    ON "MediaFile" ("toolId");

-- Composite here and not on the three above, because a workshop's media is the one parent set large
-- enough for the sort to matter: the data browser reads it as `workshopId = ? ORDER BY createdAt
-- DESC LIMIT 500`. Equality column first, sort column second — that ordering is what lets Postgres
-- walk 500 rows off the index and stop. Reversed, `(createdAt, workshopId)` would serve the sort but
-- force a filter over every row. Before: an Index Scan on MediaFile_createdAt_idx that discarded
-- 49,404 rows to find 500, 1,740 buffers, 56 ms. After: 507 buffers, 2.9 ms.
CREATE INDEX IF NOT EXISTS "MediaFile_workshopId_createdAt_idx" ON "MediaFile" ("workshopId", "createdAt");

-- `ORDER BY createdAt DESC LIMIT n` with no filter is the single most-executed shape in the app: it
-- is every record list as an admin or professor sees it (visibility_where returns an empty filter
-- for professor and above), the dashboard's four "recent submissions" reads, the data browser's
-- report sheets, and the default order of every /search bucket. Not one of these tables had an
-- index on createdAt, so all of them were a full sequential scan plus a top-N heapsort per page.
-- On the 100x copy the tools list went from a 7,400-row Seq Scan + sort (162 buffers) to a 3-buffer
-- Index Scan Backward. A plain ascending btree is correct: Postgres scans it backwards for DESC, so
-- an explicit DESC in the definition would buy nothing.
CREATE INDEX IF NOT EXISTS "Artisan_createdAt_idx"                ON "Artisan" ("createdAt");
CREATE INDEX IF NOT EXISTS "Workshop_createdAt_idx"               ON "Workshop" ("createdAt");
CREATE INDEX IF NOT EXISTS "ProductDocumentation_createdAt_idx"   ON "ProductDocumentation" ("createdAt");
CREATE INDEX IF NOT EXISTS "ToolDocumentation_createdAt_idx"      ON "ToolDocumentation" ("createdAt");
CREATE INDEX IF NOT EXISTS "Process_createdAt_idx"                ON "Process" ("createdAt");
CREATE INDEX IF NOT EXISTS "QuestionnaireInterview_createdAt_idx" ON "QuestionnaireInterview" ("createdAt");

-- The workshop-scoped list, which is how the app is actually navigated: workshopId is the organising
-- dimension of every record type and the root of the whole data browser tree. These REPLACE the bare
-- [workshopId] indexes dropped below — same leading column, so nothing that used the old one loses
-- an index, and the sort now comes free. The win grows with the data rather than the row count:
-- workshopId is high-cardinality, so a workshop's slice grows as fast as the table does, and
-- filter-then-sort is O(slice) per page where index-order is O(20).
CREATE INDEX IF NOT EXISTS "Artisan_workshopId_createdAt_idx"                ON "Artisan" ("workshopId", "createdAt");
CREATE INDEX IF NOT EXISTS "ProductDocumentation_workshopId_createdAt_idx"   ON "ProductDocumentation" ("workshopId", "createdAt");
CREATE INDEX IF NOT EXISTS "ToolDocumentation_workshopId_createdAt_idx"      ON "ToolDocumentation" ("workshopId", "createdAt");
CREATE INDEX IF NOT EXISTS "Process_workshopId_createdAt_idx"                ON "Process" ("workshopId", "createdAt");
CREATE INDEX IF NOT EXISTS "QuestionnaireInterview_workshopId_createdAt_idx" ON "QuestionnaireInterview" ("workshopId", "createdAt");

-- ---------------------------------------------------------------------------------------------
-- DROPPED (36). Every index below is either provably duplicated by another index, or unreachable by
-- any query the code can issue. Each one costs a write on every insert and update of its table.
-- ---------------------------------------------------------------------------------------------

-- Superseded by the (workshopId, createdAt) composites above, which lead with the same column.
DROP INDEX IF EXISTS "Artisan_workshopId_idx";
DROP INDEX IF EXISTS "ProductDocumentation_workshopId_idx";
DROP INDEX IF EXISTS "ToolDocumentation_workshopId_idx";
DROP INDEX IF EXISTS "Process_workshopId_idx";
DROP INDEX IF EXISTS "QuestionnaireInterview_workshopId_idx";

-- Exact duplicates: the column is already @unique, and a unique constraint IS a btree index.
DROP INDEX IF EXISTS "Craft_name_idx";                 -- Craft_name_key
DROP INDEX IF EXISTS "QuestionnaireSection_code_idx";  -- QuestionnaireSection_code_key
DROP INDEX IF EXISTS "ManagedSecret_key_idx";          -- ManagedSecret_key_key

-- Redundant prefixes: a btree on (a, b) already answers everything a btree on (a) answers.
DROP INDEX IF EXISTS "ToolArtisan_toolId_idx";                -- ToolArtisan_toolId_artisanId_key
DROP INDEX IF EXISTS "WorkshopAssignment_workshopId_idx";     -- WorkshopAssignment_workshopId_userId_key
DROP INDEX IF EXISTS "QuestionnaireSectionStatus_artisanId_idx"; -- QuestionnaireSectionStatus_artisanId_sectionId_key
DROP INDEX IF EXISTS "DataAccessScopeItem_grantId_idx";       -- DataAccessScopeItem_grantId_recordType_recordId_key
DROP INDEX IF EXISTS "DataAccessGrant_ownerId_idx";           -- DataAccessGrant_ownerId_granteeId_key
-- (The sibling [granteeId] and [sectionId] indexes are KEPT: those columns are not leading columns
-- of their uniques, and granteeId in particular is read by visibility_where on every list request.)

-- `recordedAt` is written on every record and read back on every record, but it appears in no WHERE
-- and no ORDER BY anywhere in the codebase — the lists all order by createdAt and the date-range
-- filters all target createdAt, startDate or interviewDate. Eight indexes maintained for nobody.
DROP INDEX IF EXISTS "Craft_recordedAt_idx";
DROP INDEX IF EXISTS "Artisan_recordedAt_idx";
DROP INDEX IF EXISTS "Workshop_recordedAt_idx";
DROP INDEX IF EXISTS "ProductDocumentation_recordedAt_idx";
DROP INDEX IF EXISTS "ToolDocumentation_recordedAt_idx";
DROP INDEX IF EXISTS "MediaFile_recordedAt_idx";
DROP INDEX IF EXISTS "Process_recordedAt_idx";
DROP INDEX IF EXISTS "QuestionnaireInterview_recordedAt_idx";

-- endDate is only ever read off a row already in hand (the late-submission window check); the
-- workshop list filters and sorts on startDate. canManageQuestionnaire is a boolean that is written
-- and read per user but never filtered on — and a two-value column could not usefully narrow
-- anything if it were.
DROP INDEX IF EXISTS "Workshop_endDate_idx";
DROP INDEX IF EXISTS "User_canManageQuestionnaire_idx";

-- Text columns that only ever appear inside a search box. Every text filter in the app goes through
-- services/records.py::contains(), which is `mode: "insensitive"` — Postgres `ILIKE '%…%'`. A btree
-- cannot answer that: it can only seek on a prefix, and the pattern starts with a wildcard. Checked
-- on the 100x copy with the index present and the planner free to choose it, both for the wildcard
-- form and for the one non-wildcard case (products.py's `artisanName equals … mode insensitive`,
-- which is still ~~* and still took a Seq Scan). The planner used the createdAt index and filtered.
-- Serving these properly needs pg_trgm GIN indexes, which is a separate decision with a real size
-- cost — not these, which cost writes and return nothing.
DROP INDEX IF EXISTS "Artisan_place_idx";
DROP INDEX IF EXISTS "Craft_place_idx";
DROP INDEX IF EXISTS "Workshop_place_idx";
DROP INDEX IF EXISTS "ProductDocumentation_place_idx";
DROP INDEX IF EXISTS "ToolDocumentation_place_idx";
DROP INDEX IF EXISTS "ProductDocumentation_productName_idx";
DROP INDEX IF EXISTS "ProductDocumentation_craftName_idx";
DROP INDEX IF EXISTS "ProductDocumentation_artisanName_idx";
DROP INDEX IF EXISTS "ToolDocumentation_toolkitName_idx";
DROP INDEX IF EXISTS "ToolDocumentation_craftName_idx";
DROP INDEX IF EXISTS "ToolDocumentation_artisanName_idx";
-- Artisan_name_idx is deliberately KEPT: unlike the above it has a non-search reader, the
-- `ORDER BY name ASC` behind the artisan pickers in the questionnaire and task-assignment screens.

-- Location is written by `attach_location` on every record create that carries coordinates, and read
-- back only through the `location` relation — which is a primary-key lookup. `db.location` appears
-- exactly once in the backend, at services/records.py:219, and it is a `.create`. So these two were
-- charged to every create and collected on by nobody. They could not have served their own queries
-- either: a bounding box or a radius needs GiST rather than a btree over two independent floats, and
-- a place name reaches Postgres from a search box as ILIKE '%…%'. Location_state_idx stays — see the
-- schema note; it is the one column here whose eventual query is an equality a btree can answer.
DROP INDEX IF EXISTS "Location_latitude_longitude_idx";
DROP INDEX IF EXISTS "Location_placeName_idx";

-- ---------------------------------------------------------------------------------------------
-- CONSIDERED AND REJECTED. Kept in the file because the reasoning is the expensive part: without it
-- the next person to look at a slow list re-derives these from scratch, and two of them look so
-- obviously right on paper that they would be added on intuition alone.
--
-- Measured on the 100x copy of production described above (92,500 MediaFile / 7,400 tools / 1,600
-- artisans), `EXPLAIN (ANALYZE, BUFFERS)`, parallelism off, each candidate built and dropped so the
-- planner chose between exactly the two index sets being compared.
-- ---------------------------------------------------------------------------------------------
--
-- (status, createdAt) on the six reviewable record types — REJECTED, and this is the one that looks
-- most obviously correct on paper. /review/pending is `status = 'PENDING' ORDER BY createdAt DESC
-- LIMIT 200` over six tables, which is the exact equality-then-sort shape this migration argues for
-- everywhere else. It does not pay, because `status` is not an ordinary filter: PENDING is the
-- actively-drained slice, so it is either small — in which case the bare [status] index returns the
-- whole slice and sorting it is free — or large, in which case the planner ignores status entirely
-- and walks [createdAt] backwards, filling the LIMIT before it has read far. Both ends were checked.
-- On ToolDocumentation (40 PENDING of 7,400) bare [status] gave an index scan + a 40-row quicksort,
-- 5 buffers / 0.28 ms; the composite removed the sort but read a wider index for 25 buffers / 0.14 ms
-- — a 0.14 ms saving for 20 buffers more I/O. On MediaFile (10,277 PENDING of 92,500) the planner
-- declined the composite altogether, choosing Index Scan Backward on MediaFile_createdAt_idx and
-- discarding 160 rows to find 20, with the composite present and available. Building it on the seven
-- tables would mean seven wider indexes on the write path, including the largest table in the
-- database, in exchange for a sort the planner already avoids or does not mind doing.
--
-- (createdById, createdAt) — REJECTED, despite `visibility_where` being on nearly every list. The
-- reason is the OR: below professor the predicate is `createdById = me OR createdById IN (grantors)`,
-- and Postgres cannot walk a composite for a disjunction on the leading column AND come out in
-- createdAt order, so the sort would survive the index. Confirmed rather than assumed — built on
-- ToolDocumentation, the plan did not change: Index Scan Backward on ToolDocumentation_createdAt_idx,
-- filter `(createdById = $0) OR (hashed SubPlan 2)`, 41 buffers with the composite and 41 without.
-- An index the planner declines is pure write cost. The bare [createdById] stays for the equality
-- lookups that do use it.
--
-- pg_trgm GIN on the text columns whose btrees are dropped above — REJECTED HERE, not rejected as an
-- idea. It is the only thing that would make /search (8.9 s) index-servable, because every text
-- filter in the app is `mode: "insensitive"` contains and no btree answers a leading wildcard. It is
-- out of scope for an index-coverage pass: it needs a CREATE EXTENSION, GIN indexes are large and
-- slow to build, and on a 1 GiB box that is a sizing decision someone should make deliberately with
-- the search rewrite in front of them, not inherit from a migration named "index coverage".
--
-- (mediaType, createdAt) and (uploadedById, createdAt) on MediaFile — REJECTED. Same shape as the
-- workshop composites, but the media list's dominant read is unfiltered `ORDER BY createdAt DESC`,
-- which MediaFile_createdAt_idx already serves; mediaType is four values and cannot narrow enough to
-- beat it. MediaFile takes more writes than every other table combined, so the bar for adding to it
-- is the highest in the schema, and these do not clear it.
--
-- Reshaping MediaProcessingJob's (status, runAfter, priority, createdAt) to (status, priority,
-- createdAt) — REJECTED, though it is arguably the more correct shape. The queue reads `status =
-- QUEUED AND runAfter <= now() ORDER BY priority, createdAt`, and a range column sitting ahead of the
-- sort columns means the suffix cannot supply the ordering. Moving runAfter out to a filter would
-- restore it — but runAfter is exactly what the 429 backoff sets, so during a rate-limit cooldown the
-- not-yet-due rows are the majority and the reshaped index would scan past all of them to find a due
-- one. It trades a sort in the normal case for a scan in the degraded case, which is the wrong way
-- round. Left alone deliberately.
--
-- (assigneeId, dueAt) on AssignedTask — REJECTED. The list sorts `dueAt ASC, createdAt DESC` and
-- filters assigneeId, so the shape fits, but [assigneeId, status] already covers the filter and the
-- table is bounded by tasks actually handed out. Revisit if a task list ever gets slow; nothing
-- suggests it will.
--
-- (granteeId, status) on DataAccessGrant — REJECTED. `visibility_where` reads it as
-- `granteeId = me AND status = 'GRANTED'` on every list request from below professor, so it is hot.
-- But a single grantee's row count is bounded by the number of researchers who have ever granted to
-- them — tens, at a hundred times this pilot. [granteeId] alone narrows to that handful and the
-- status check is a comparison on rows already in hand.
--
-- QuestionnaireQuestion_isActive_idx — CONSIDERED FOR REMOVAL, KEPT. It is read as `isActive = true`,
-- which matches nearly every row, so the planner will never choose it: by the boolean-selectivity
-- argument used to drop User_canManageQuestionnaire_idx it is dead. The difference is that the table
-- is a question bank of a few hundred admin-edited rows, so dropping it saves no measurable write
-- work. Churn on a live database should buy something.
