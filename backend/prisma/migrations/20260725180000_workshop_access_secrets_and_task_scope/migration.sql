-- Three additions, all backwards compatible:
--
--   1. WorkshopAssignment becomes the single record of BOTH an admin's grant and a user's request
--      for access to a workshop (accessLevel + status + the request/decision columns). Existing rows
--      keep the defaults 'CONTRIBUTE'/'GRANTED', which is exactly what they meant before these
--      columns existed, so nobody's current access changes.
--   2. ManagedSecret stores runtime-editable API keys (Fernet ciphertext, never plaintext). An empty
--      table behaves exactly like today because services fall back to the environment.
--   3. AssignedTask gains the scope needed to describe work that does not exist yet: a workshop, a
--      list of record types, an artisan subset, questionnaire sections, a target count and a batch id.
--
-- Generated with `prisma migrate diff` and then corrected by hand — see the updatedAt note below.

-- AlterTable: task scope. Array columns default to empty, so every existing task reads as
-- "unscoped", which is how it behaved before.
ALTER TABLE "AssignedTask" ADD COLUMN     "artisanIds" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "batchId" TEXT,
ADD COLUMN     "progressCount" INTEGER NOT NULL DEFAULT 0,
ADD COLUMN     "recordTypes" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "sectionIds" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "targetCount" INTEGER,
ADD COLUMN     "workshopId" TEXT;

-- AlterTable: workshop access.
ALTER TABLE "WorkshopAssignment" ADD COLUMN     "accessLevel" TEXT NOT NULL DEFAULT 'CONTRIBUTE',
ADD COLUMN     "decidedAt" TIMESTAMP(3),
ADD COLUMN     "decidedById" TEXT,
ADD COLUMN     "decisionNote" TEXT,
ADD COLUMN     "requestNote" TEXT,
ADD COLUMN     "requestedById" TEXT;

-- `status` is added separately so the backfill below reads clearly: every pre-existing row was an
-- admin's direct assignment, which is unambiguously GRANTED.
ALTER TABLE "WorkshopAssignment" ADD COLUMN     "status" TEXT NOT NULL DEFAULT 'GRANTED';

-- HAND-CORRECTED. `prisma migrate diff` emits this as a bare
--     ADD COLUMN "updatedAt" TIMESTAMP(3) NOT NULL;
-- because `@updatedAt` is maintained by the Prisma client and so carries no database default. That
-- form aborts with "column contains null values" on any table that already has rows — which is
-- every deployed environment. Adding it WITH a default backfills the existing rows, and dropping the
-- default immediately afterwards leaves the column exactly as the schema declares it.
ALTER TABLE "WorkshopAssignment" ADD COLUMN     "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "WorkshopAssignment" ALTER COLUMN "updatedAt" DROP DEFAULT;

-- CreateTable
CREATE TABLE "ManagedSecret" (
    "id" TEXT NOT NULL,
    "key" TEXT NOT NULL,
    "valueEnc" TEXT NOT NULL,
    "hint" TEXT,
    "description" TEXT,
    "lastStatus" TEXT NOT NULL DEFAULT 'UNKNOWN',
    "lastCheckedAt" TIMESTAMP(3),
    "lastError" TEXT,
    "updatedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "ManagedSecret_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "ManagedSecret_key_key" ON "ManagedSecret"("key");

-- CreateIndex
CREATE INDEX "ManagedSecret_key_idx" ON "ManagedSecret"("key");

-- CreateIndex
CREATE INDEX "AssignedTask_workshopId_idx" ON "AssignedTask"("workshopId");

-- CreateIndex
CREATE INDEX "AssignedTask_batchId_idx" ON "AssignedTask"("batchId");

-- CreateIndex
CREATE INDEX "WorkshopAssignment_status_idx" ON "WorkshopAssignment"("status");

-- AddForeignKey
ALTER TABLE "AssignedTask" ADD CONSTRAINT "AssignedTask_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkshopAssignment" ADD CONSTRAINT "WorkshopAssignment_requestedById_fkey" FOREIGN KEY ("requestedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkshopAssignment" ADD CONSTRAINT "WorkshopAssignment_decidedById_fkey" FOREIGN KEY ("decidedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ManagedSecret" ADD CONSTRAINT "ManagedSecret_updatedById_fkey" FOREIGN KEY ("updatedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
