-- Cross-device create idempotency: "clientKey" on the four record models an outbox can replay --
-- "Workshop", "ProductDocumentation", "ToolDocumentation" and "Process".
--
-- =============================================================================================
-- THE DUPLICATE THIS CLOSES, AND WHY THE TWO CLIENT-SIDE GUARDS COULD NOT CLOSE IT
-- =============================================================================================
--
-- A queued create is POSTed, this server writes the row, and the answer is lost on the way back --
-- a tunnel, a captive portal, the process killed by the OS while the request was in flight. The
-- client learned nothing, so the entry is still in its queue and the next pass sends it again.
--
-- Both clients already guard the case they CAN see. `frontend/lib/offline.ts` writes `createdId` the
-- moment an answer lands and the drain reads it back; Android's `PendingEntry.createdId`
-- (data/Offline.kt) does the same, and its replay skips the create when it is set. NEITHER CAN GUARD
-- AN ANSWER THAT NEVER ARRIVED, because both are records of a REPLY. This repository's own outbox
-- says so, in `persistProgress` (frontend/lib/offline.ts): *"a few milliseconds of IndexedDB is as
-- small as that window gets without idempotency keys on the API."* This is that key, and the
-- sentence is quoted rather than paraphrased because it IS the specification.
--
-- AND IT IS THE ONLY GUARD THAT CROSSES A DEVICE OR A PROFILE. `createdId` is a fact ONE browser
-- profile -- or one handset's queue file -- holds about its own send. A queue restored onto a second
-- handset, or drained after a sign-out and back in, holds no such record, and the same fieldwork is
-- filed twice under one researcher's name in a register nobody reconciles.
--
-- =============================================================================================
-- ONLY FOUR MODELS, BECAUSE THE OTHER TWO ARE ALREADY PROTECTED
-- =============================================================================================
--
-- "Artisan" has "aadhaarNumber" @unique plus `artisans._guard_identity_conflicts`, a pre-write 409
-- NAMING the artisan that already holds the number -- so a replayed artisan create is refused by the
-- dedup key that exists for exactly that reason. "Craft" is untouched for the same shape of reason:
-- "Craft"."name" is @unique with its own 409. Adding a second idempotency mechanism beside either
-- would be two guards that can disagree about what a duplicate is, and the 409 arm of the replay is
-- written on the assumption that a clash is SOMEBODY ELSE'S record.
--
-- =============================================================================================
-- A PLAIN NULLABLE UNIQUE, WHICH *IS* THE PARTIAL INDEX -- NOT A SECOND, HAND-WRITTEN ONE
-- =============================================================================================
--
-- Postgres treats NULLs as distinct under a unique index, so a unique index on a nullable column
-- already permits any number of rows with no key: `CREATE UNIQUE INDEX ... WHERE "clientKey" IS NOT
-- NULL` would build exactly the same guarantee. The difference is only that the partial form is
-- INEXPRESSIBLE in schema.prisma, and a constraint the schema cannot state is a constraint the
-- schema disagrees with the database about -- which is the condition every future `prisma migrate
-- diff` would try to "fix".
--
-- So this file writes the index Prisma itself would write for `clientKey String? @unique`, including
-- the name it would choose ("<Table>_clientKey_key"), and the schema states the same thing.
-- "Artisan"."aadhaarNumber" settled this once already, in a column comment that says the quiet part:
-- existing rows "keep NULL -- readable, editable, and exempt from the unique index, since Postgres
-- permits any number of NULLs under one."
--
-- =============================================================================================
-- NULLABLE, NO DEFAULT, NO BACKFILL -- WHICH IS THE WIRE CONTRACT, NOT AN OMISSION
-- =============================================================================================
--
-- Every row already in these four tables was created without a key and nothing can invent one for it
-- retroactively: a key identifies a REQUEST, and the requests are gone. So an absent key has to go on
-- meaning what it means today -- create the row, answer 201 -- which is exactly what every fielded
-- APK and every cached web bundle sends. `NOT NULL DEFAULT ''` was considered and is worse than
-- useless: one empty string would collide with the next, and the second create of any kind on this
-- deployment would be refused.
--
-- CREATING THE INDEX CANNOT FAIL ON EXISTING DATA. Every existing row gets NULL from the ADD COLUMN,
-- and NULLs do not collide, so there is no duplicate for the unique build to trip over.
--
-- NOT `CONCURRENTLY`, and 20260726200000 explains why at length: prisma sends a file as one
-- multi-statement query in an implicit transaction, so CREATE INDEX CONCURRENTLY fails with PG 25001
-- / P3018 and leaves a failed _prisma_migrations row blocking every later migration. These four
-- indexes are built on an empty column and take a brief ACCESS EXCLUSIVE lock; that is acceptable
-- here in a way a large index build would not be.
--
-- =============================================================================================
-- A REPLAY THAT ANSWERS CORRECTLY AND LEAVES THE CHILDREN UNWRITTEN IS THE QUIETEST FAILURE HERE
-- =============================================================================================
--
-- THIS BACKEND HAS NO TRANSACTIONS -- not one `db.tx()` call site exists -- so "Workshop" and
-- "Process" can commit their row and then fail on the rosters or the step list that follow. Without
-- care the key would make that PERMANENT: every later replay finds the row, answers 200 from it, and
-- the roster or the steps stay gone while the outbox reports success. So the replay branches in
-- `routes/workshops.create_workshop` and `routes/processes.create_process` repair EMPTY children and
-- never touch populated ones -- `replace_workshop_artisans` is a delete_many + create_many, so an
-- unconditional re-run would wipe a roster edited through PATCH while the entry sat in a queue for a
-- fortnight, and a re-run of `_sync_steps` would mint FRESH ProcessStep ids (a create body carries no
-- step ids, so every step lands in `to_create` and the originals are deleted), orphaning every
-- step-linked "MediaFile" through the FK-less "linkedRecordId" pair.
--
-- =============================================================================================
-- WHAT THIS WAVE DELIBERATELY DOES NOT BRING ACROSS
-- =============================================================================================
--
-- The sibling repository adds a sixth column in the same family of changes -- `designWorkshopId` on
-- Artisan / ProductDocumentation / ToolDocumentation / Process / MediaFile AND on
-- QuestionnaireInterview, a nullable FK onto a "DesignWorkshop" table -- and it is refused here on
-- three independent grounds. The FK has no target: this database has no such table, and the ADD
-- CONSTRAINT statements would fail and leave a failed _prisma_migrations row blocking every later
-- migration in this directory (20260726200000/migration.sql:3-34 sets out that failure at length).
-- The write gate does not exist: over there the column is gated on every write by
-- `record_design_workshop.assert_payload_workshop`, and ungated it is a free-text field into which
-- any signed-in caller could post a stranger's cuid and file their record under somebody else's
-- scoped lists and totals. And nothing here reads it -- the five designer list routes that filter on
-- it have no counterpart in this product. A field-app record is not filed under a design workshop
-- because this product has no such object; NULL is the correct and permanent answer.
--
-- Rolling back: DROP the four indexes, then DROP the four columns. Nothing else references them.

ALTER TABLE "Workshop"             ADD COLUMN IF NOT EXISTS "clientKey" TEXT;
ALTER TABLE "ProductDocumentation" ADD COLUMN IF NOT EXISTS "clientKey" TEXT;
ALTER TABLE "ToolDocumentation"    ADD COLUMN IF NOT EXISTS "clientKey" TEXT;
ALTER TABLE "Process"              ADD COLUMN IF NOT EXISTS "clientKey" TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS "Workshop_clientKey_key"
  ON "Workshop" ("clientKey");
CREATE UNIQUE INDEX IF NOT EXISTS "ProductDocumentation_clientKey_key"
  ON "ProductDocumentation" ("clientKey");
CREATE UNIQUE INDEX IF NOT EXISTS "ToolDocumentation_clientKey_key"
  ON "ToolDocumentation" ("clientKey");
CREATE UNIQUE INDEX IF NOT EXISTS "Process_clientKey_key"
  ON "Process" ("clientKey");
