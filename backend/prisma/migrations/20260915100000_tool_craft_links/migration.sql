-- Many-to-many between a tool and its crafts: one new table, one backfill, nothing else touched.
--
-- =============================================================================================
-- WHAT THE SINGLE "craftId" COLUMN COULD NOT SAY
-- =============================================================================================
--
-- "ToolDocumentation" has carried ONE "craftId" and one free-text "craftName" since it was written,
-- so a chisel used by a block printer and by a dyer had to be documented twice -- once per craft --
-- and the two rows then drifted apart on every later correction. "ToolArtisan" already solved the
-- same shape of problem for the artisans a tool is used by; this is that table's twin for crafts.
--
-- ADDITIVE ONLY. Not one column added, dropped or retyped on any existing table, not one constraint
-- relaxed. "ToolDocumentation"."craftId" and ."craftName" keep their meaning and their readers: the
-- column holds the FIRST of the selected crafts and the string holds every selected name joined
-- ", " in link order. Every filter, index, report, carry-forward and data-browser branch that reads
-- "craftId" today reads exactly the same value after this migration -- `routes/tools.list_tools`'s
-- craftId filter, `record_filters.build_record_wheres` and `data_browser`'s craft folders included,
-- none of which is widened here. A tool linked to three crafts therefore still answers the
-- single-craft filter under the FIRST of them only; see the note above that filter for why
-- widening it is a separate decision.
--
-- =============================================================================================
-- IT MIRRORS "ToolArtisan" COLUMN FOR COLUMN, AND THE ONE DIFFERENCE IS DELIBERATE
-- =============================================================================================
--
-- Same four columns, same unique, same single secondary index, same CASCADE on both sides. Two join
-- tables hanging off one parent that disagree about their own shape is how a later reader comes to
-- believe one of them means something the other does not. See 20260618150000_tool_artisan_links.
--
-- THE ONE DIFFERENCE: that file also created "ToolArtisan_toolId_idx", which schema.prisma does not
-- declare and which is redundant -- "toolId" is the LEADING column of the unique index beside it, so
-- the tool-to-artisans lookup is already served by that index. It is left where it is (dropping it
-- is a separate decision with its own migration) and it is NOT reproduced here, because this file
-- must be what `prisma migrate diff` emits for the model text in schema.prisma. Do not "make the
-- two match".
--
-- =============================================================================================
-- THE BACKFILL, AND WHY ITS IDS ARE DERIVED RATHER THAN RANDOM
-- =============================================================================================
--
-- Every tool that already names a craft gains its link row, which is the whole reason this file is
-- not just a CREATE TABLE. Without it, the first save of any existing tool through the new
-- multi-select would find "craftLinks" empty, tick nothing, and the researcher's obvious repair --
-- pick the craft again -- would be the one action that writes the link back. A tool whose craft
-- "disappeared" is indistinguishable, on screen, from a tool that never had one.
--
-- THE ID IS DERIVED FROM THE PAIR, NOT RANDOM, so a re-run of a half-applied migration cannot mint a
-- second row for the same tool and craft even if the unique index build had not landed.
-- 'c' || substr(md5(...), 1, 24) is 25 characters beginning with 'c' -- the shape @default(cuid())
-- produces -- over a pair that is already unique. Prisma only generates ids for rows IT creates, so
-- a hand-shaped id here is read back exactly like any other.
--
-- "createdAt" IS THE TOOL'S OWN, NOT now(). Copying CURRENT_TIMESTAMP would stamp every backfilled
-- link with one instant, and "oldest first" over the whole table would then be arbitrary -- which is
-- the order `routes/tools._assigned_artisans` already promises for the artisan twin.
--
-- =============================================================================================
-- NOT "CONCURRENTLY"
-- =============================================================================================
--
-- 20260726200000 sets this out at length: prisma sends a migration file as one multi-statement query
-- inside an implicit transaction, so CREATE INDEX CONCURRENTLY fails with PG 25001 / P3018 and
-- leaves a failed _prisma_migrations row blocking every later migration in this directory. Both
-- indexes here are built on a table this file has just created, so they are empty and the build is
-- instant; that is acceptable here in a way a large index build would not be.
--
-- =============================================================================================
-- WHAT THIS WAVE DELIBERATELY DOES NOT BRING ACROSS
-- =============================================================================================
--
-- The sibling repository lands the identical table in the same wave, and two of its obligations have
-- no counterpart in this directory.
--
-- THERE IS NO DESIGN-WORKSHOP CARRY HERE. Over there a stage entry copies a tool's craft into a
-- questionnaire answer, and a RELATION_LEDGER in `backend/tests/test_reference_carry.py` fails the
-- build for any schema relation not accounted for -- so "craftLinks" and "toolLinks" have to be
-- entered in it in the same commit or the suite goes red. This product has no "DesignWorkshop"
-- table, no `services/stage_definitions.py`, and no such test (`ls backend/tests` is the whole
-- proof), so the two new relations need no ledger entry and nothing here carries a craft into a
-- stage.
--
-- AND THERE IS NO TRANSACTION TO WRAP THE LINK WRITES IN. This backend has no `db.tx()` call site at
-- all -- 20260913120400 says so in as many words -- so `routes/tools` writes the tool row and then
-- its link rows, and the window between the two is the same window every other child write in this
-- product already lives with (the workshop roster, the process step list). The create route's
-- clientKey replay is what closes it on a retry.
--
-- Rolling back: DROP TABLE "ToolCraft"; and nothing else -- no other table references it, and both
-- of its foreign keys point away from it.

CREATE TABLE IF NOT EXISTS "ToolCraft" (
    "id"        TEXT NOT NULL,
    "toolId"    TEXT NOT NULL,
    "craftId"   TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ToolCraft_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "ToolCraft_toolId_craftId_key" ON "ToolCraft"("toolId", "craftId");
CREATE INDEX IF NOT EXISTS "ToolCraft_craftId_idx" ON "ToolCraft"("craftId");

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ToolCraft_toolId_fkey') THEN
    ALTER TABLE "ToolCraft" ADD CONSTRAINT "ToolCraft_toolId_fkey"
      FOREIGN KEY ("toolId") REFERENCES "ToolDocumentation"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ToolCraft_craftId_fkey') THEN
    ALTER TABLE "ToolCraft" ADD CONSTRAINT "ToolCraft_craftId_fkey"
      FOREIGN KEY ("craftId") REFERENCES "Craft"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

-- AFTER the constraints, so a row that cannot satisfy them is refused rather than inserted, and
-- after the unique index, so ON CONFLICT has a constraint to name.
INSERT INTO "ToolCraft" ("id", "toolId", "craftId", "createdAt")
SELECT
    'c' || substr(md5(t."id" || ':' || t."craftId"), 1, 24),
    t."id",
    t."craftId",
    t."createdAt"
FROM "ToolDocumentation" t
WHERE t."craftId" IS NOT NULL
ON CONFLICT ("toolId", "craftId") DO NOTHING;
