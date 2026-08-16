-- THE SIGN-IN GATE. This application has never refused anybody before; from this migration on, an
-- email address must appear on "AccessRoster" with status ACTIVE to sign in. See
-- prisma/schema.prisma (model AccessRoster) for why the roster is keyed by email rather than by
-- user id, and app/services/access_roster.py for the gate itself.
--
-- THE ONLY STATEMENT IN THIS FILE THAT CAN CAUSE AN OUTAGE IS THE LAST ONE. Creating a table and an
-- enum breaks nothing. The back-fill at the bottom is what stands between every existing account
-- and a permanent lockout, and it is covered by tests/test_access_roster_grandfathering.py, which
-- executes THIS FILE against a database seeded with one account per role and then drives each of
-- them through the real gate. If you edit the back-fill, that test is the thing that tells you
-- whether you just locked the institution out of its own repository.

-- CreateEnum
CREATE TYPE "AccessStatus" AS ENUM ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED');

-- CreateTable
CREATE TABLE "AccessRoster" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "status" "AccessStatus" NOT NULL DEFAULT 'PENDING',
    "grantedRole" "UserRole" NOT NULL DEFAULT 'CROWDSOURCE_VOLUNTEER',
    "fullName" TEXT,
    "notes" TEXT,
    "joinedAt" TIMESTAMP(3),
    "firstSeenAt" TIMESTAMP(3),
    "requestCount" INTEGER NOT NULL DEFAULT 1,
    "firstRequestedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastRequestedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decidedAt" TIMESTAMP(3),
    "decidedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AccessRoster_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "AccessRoster_email_key" ON "AccessRoster"("email");

-- CreateIndex
CREATE INDEX "AccessRoster_status_idx" ON "AccessRoster"("status");

-- CreateIndex
CREATE INDEX "AccessRoster_status_lastRequestedAt_idx" ON "AccessRoster"("status", "lastRequestedAt");

-- CreateIndex
CREATE INDEX "AccessRoster_decidedById_idx" ON "AccessRoster"("decidedById");

-- AddForeignKey
ALTER TABLE "AccessRoster" ADD CONSTRAINT "AccessRoster_decidedById_fkey" FOREIGN KEY ("decidedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------
-- GRANDFATHERING: EVERY ACCOUNT THAT EXISTS TODAY IS ADMITTED.
-- ---------------------------------------------------------------------------------------------
--
-- This is a live repository with real users, and the gate above refuses anybody who is not on the
-- list. Without this statement, deploying this migration signs every researcher, professor, field
-- contributor and volunteer out of the application permanently, and the only account left able to
-- put them back is the master admin — one person, by hand, for the whole institution.
--
-- THERE IS DELIBERATELY NO "WHERE" FILTERING BY ROLE. Every role goes on, including the bottom two
-- tiers, because the requirement is that no EXISTING user faces any trouble logging in — not that
-- the roles we happen to think matter are spared. A future reader adding `WHERE u."role" <> ...`
-- here is choosing to lock a group of real people out.
--
-- "joinedAt" IS BACK-FILLED FROM "User"."createdAt", which is the requirement's "date of joining
-- the platform" for accounts that predate the roster. The alternative — leaving it NULL, or
-- stamping today — would tell every admin that the entire institution joined on the day this
-- feature shipped, which is the one date it certainly was not.
--
-- "firstSeenAt" is back-filled too: these people have demonstrably already used the app, so leaving
-- it NULL would drop the whole existing user base into the "approved but never signed in" bucket
-- that column exists to identify.
--
-- "requestCount" IS ZERO, NOT THE COLUMN DEFAULT OF ONE. These rows were not created by somebody
-- asking to be let in; they were created by an administrator's decision to grandfather them. Zero
-- is what distinguishes "admitted without ever having to ask" from "asked once and was approved".
--
-- LOWER-CASING AND THE CASE COLLISION. "User"."email" is unique case-SENSITIVELY, so the table can
-- in principle hold both `A@x.org` and `a@x.org`. Everything downstream of this migration matches
-- on the lower-cased address (`normalise_email`), and so does the pre-existing sign-in code —
-- auth.py has always looked the account up with `payload.email.lower()`, which means a row stored
-- with capitals has never been able to sign in and cannot be locked out by anything done here. The
-- NOT EXISTS clause therefore picks exactly one "User" per lower-cased address — the OLDEST, ties
-- broken by id, so the back-filled "joinedAt" is the earliest date that address was known — and the
-- ON CONFLICT is a second belt on the same braces (it also makes re-running this file harmless).
--
-- "fullName" IS COPIED FROM "User"."name" HERE, and this is the ONE place a name is written into
-- this table by anything other than an administrator. That is safe precisely because these are
-- accounts that already exist: their names are already rendered on the users admin screen, the
-- review queue and every record they created. The rule the runtime code enforces — never store a
-- display name from an unverified source (see access_roster.record_access_request) — is about rows
-- a STRANGER causes to appear in the admin's pending queue, and no row created here is one of those.
INSERT INTO "AccessRoster" (
    "id",
    "email",
    "status",
    "grantedRole",
    "fullName",
    "notes",
    "joinedAt",
    "firstSeenAt",
    "requestCount",
    "firstRequestedAt",
    "lastRequestedAt",
    "decidedAt",
    "createdAt",
    "updatedAt"
)
SELECT
    gen_random_uuid()::text,
    lower(u."email"),
    'ACTIVE',
    u."role",
    u."name",
    'Grandfathered when the sign-in gate was introduced: this account already existed.',
    u."createdAt",
    u."createdAt",
    0,
    u."createdAt",
    u."createdAt",
    NULL,
    u."createdAt",
    CURRENT_TIMESTAMP
FROM "User" u
WHERE NOT EXISTS (
    SELECT 1
    FROM "User" v
    WHERE lower(v."email") = lower(u."email")
      AND (
        v."createdAt" < u."createdAt"
        OR (v."createdAt" = u."createdAt" AND v."id" < u."id")
      )
)
ON CONFLICT ("email") DO NOTHING;
