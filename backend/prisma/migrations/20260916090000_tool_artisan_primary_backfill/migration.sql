-- Every tool that already names an artisan gains its link row. One INSERT, no DDL at all.
--
-- =============================================================================================
-- WHY A JOIN TABLE THAT HAS EXISTED SINCE JUNE STILL NEEDS A BACKFILL
-- =============================================================================================
--
-- "ToolArtisan" was created by 20260618150000 to serve ONE endpoint: POST /tools/{id}/artisans,
-- which is purely additive and whose own docstring says it "never touches artisanId/artisanName/
-- place". So for four months the table and the "ToolDocumentation"."artisanId" column answered two
-- different questions -- "who else was this tool assigned to" and "whose tool is this" -- and
-- nothing kept them in agreement, because nothing had to.
--
-- 20260915100000 changed that. The tool record body now carries "artisanIds", both clients seed
-- their artisan multi-select from "artisanLinks", and `routes/tools._resolve_tool_links` DERIVES
-- "artisanId" from element 0 of whatever comes back. On a tool whose links were written by the
-- assignment endpoint and whose "artisanId" was set by the old single-select, the form ticks the
-- LINKS and the column is not among them -- so the first PATCH of that tool, even one that only
-- fixes a typo in the remarks, re-points "artisanId" at whichever artisan led the assignment list,
-- while "artisanName" and "place" (which the route deliberately does not re-derive) keep naming the
-- original. The row then says one artisan and reads another, on a save that touched neither.
--
-- THIS IS THE ARTISAN HALF OF WHAT 20260915100000 DID FOR CRAFTS, and it was missed there. That
-- file backfilled "ToolCraft" from "ToolDocumentation"."craftId" for exactly this reason -- "a tool
-- whose craft 'disappeared' is indistinguishable, on screen, from a tool that never had one" -- and
-- the artisan column needed the same treatment the moment its list became a replacement rather than
-- an addition. Ordering the read (`routes/tools._order_artisan_links`, same wave as this file) fixes
-- the tools whose primary artisan IS linked; only a backfill can fix the ones where it is not.
--
-- =============================================================================================
-- WHAT IT ASSERTS, AND WHY THAT IS NOT A NEW CLAIM
-- =============================================================================================
--
-- A backfilled row says "this tool is assigned to this artisan". "ToolDocumentation"."artisanId"
-- already said exactly that, in the column every filter, report and map pin reads, and it is what a
-- save through the new multi-select writes for the same tool today. The link table is being brought
-- into line with a fact the row has always carried -- not given a new one.
--
-- A tool with a NULL "artisanId" gains nothing. A pair that is already linked keeps the row it has,
-- with its own id and its own "createdAt" (ON CONFLICT DO NOTHING) -- so a link the assignment
-- endpoint wrote is never restamped, and GET /tools/{id}/artisans reports it exactly as it did.
--
-- =============================================================================================
-- THE ID IS DERIVED FROM THE PAIR, AND "createdAt" IS THE TOOL'S OWN
-- =============================================================================================
--
-- 'c' || substr(md5(...), 1, 24) is 25 characters beginning with 'c' -- the shape @default(cuid())
-- produces -- over a pair that is already unique, so a re-run of a half-applied migration cannot
-- mint a second row for the same tool and artisan even if it had to run before the unique index
-- existed. Prisma only generates ids for rows IT creates, so a hand-shaped id here is read back
-- exactly like any other. It is also what makes the rollback below exact rather than approximate.
--
-- "createdAt" IS THE TOOL'S OWN, NOT now(), and here that is load-bearing rather than tidy: it makes
-- the primary artisan's link the OLDEST of the tool's links, so `_assigned_artisans`' "oldest first"
-- and `_order_artisan_links`' "the one artisanId names, first" agree for every row this file
-- touches. Stamping CURRENT_TIMESTAMP would have put the tool's own artisan LAST in a list ordered
-- by age, which is the opposite of what both readers mean.
--
-- =============================================================================================
-- NO DDL, SO NOTHING TO SAY ABOUT `CONCURRENTLY`
-- =============================================================================================
--
-- This file creates no table, no column and no index -- the only statement is one INSERT ... SELECT
-- against a table that already exists. The transaction note 20260726200000 sets out at length
-- applies to index builds and there are none here. The INSERT is one pass over "ToolDocumentation"
-- with a NOT NULL filter on an indexed column ("ToolDocumentation_artisanId_idx" has existed since
-- the table did), and it writes at most one row per tool.
--
-- Rolling back: DELETE FROM "ToolArtisan"
--                WHERE "id" = 'c' || substr(md5("toolId" || ':' || "artisanId"), 1, 24);
-- which removes exactly the rows this file inserted and nothing else -- a link the assignment
-- endpoint wrote carries a random cuid and cannot match its own derived id. Note that rolling this
-- back re-opens the defect above for the tools it touched; it is here because a migration without a
-- stated rollback is one nobody can undo under pressure, not because undoing it is advisable.

INSERT INTO "ToolArtisan" ("id", "toolId", "artisanId", "createdAt")
SELECT
    'c' || substr(md5(t."id" || ':' || t."artisanId"), 1, 24),
    t."id",
    t."artisanId",
    t."createdAt"
FROM "ToolDocumentation" t
WHERE t."artisanId" IS NOT NULL
ON CONFLICT ("toolId", "artisanId") DO NOTHING;
