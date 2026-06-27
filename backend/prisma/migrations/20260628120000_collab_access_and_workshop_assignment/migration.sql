-- Cross-researcher collaboration + workshop assignment.
--   * WorkshopAssignment  : which researchers an admin assigned to a workshop.
--   * DataAccessGrant      : tiered (DOWNLOAD<COMMENT<EDIT) data-access requests/grants between users.
--   * DataAccessScopeItem  : the subset of records a non-allData grant covers.
--   * EntryComment         : comments on any record (generic recordType/recordId).
--   * RecordRevision       : immutable per-edit audit of field changes (original + every edit).

-- CreateEnum
CREATE TYPE "DataAccessTier" AS ENUM ('DOWNLOAD', 'COMMENT', 'EDIT');
CREATE TYPE "DataAccessStatus" AS ENUM ('PENDING', 'GRANTED', 'DENIED', 'REVOKED');

-- CreateTable
CREATE TABLE "WorkshopAssignment" (
    "id" TEXT NOT NULL,
    "workshopId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "assignedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WorkshopAssignment_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "DataAccessGrant" (
    "id" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "granteeId" TEXT NOT NULL,
    "tier" "DataAccessTier" NOT NULL DEFAULT 'DOWNLOAD',
    "status" "DataAccessStatus" NOT NULL DEFAULT 'PENDING',
    "allData" BOOLEAN NOT NULL DEFAULT true,
    "requestNote" TEXT,
    "decisionNote" TEXT,
    "requestedById" TEXT,
    "decidedById" TEXT,
    "decidedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DataAccessGrant_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "DataAccessScopeItem" (
    "id" TEXT NOT NULL,
    "grantId" TEXT NOT NULL,
    "recordType" TEXT NOT NULL,
    "recordId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DataAccessScopeItem_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "EntryComment" (
    "id" TEXT NOT NULL,
    "recordType" TEXT NOT NULL,
    "recordId" TEXT NOT NULL,
    "authorId" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "EntryComment_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "RecordRevision" (
    "id" TEXT NOT NULL,
    "recordType" TEXT NOT NULL,
    "recordId" TEXT NOT NULL,
    "editedById" TEXT,
    "changes" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RecordRevision_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "WorkshopAssignment_workshopId_userId_key" ON "WorkshopAssignment"("workshopId", "userId");
CREATE INDEX "WorkshopAssignment_userId_idx" ON "WorkshopAssignment"("userId");
CREATE INDEX "WorkshopAssignment_workshopId_idx" ON "WorkshopAssignment"("workshopId");

CREATE UNIQUE INDEX "DataAccessGrant_ownerId_granteeId_key" ON "DataAccessGrant"("ownerId", "granteeId");
CREATE INDEX "DataAccessGrant_granteeId_idx" ON "DataAccessGrant"("granteeId");
CREATE INDEX "DataAccessGrant_ownerId_idx" ON "DataAccessGrant"("ownerId");
CREATE INDEX "DataAccessGrant_status_idx" ON "DataAccessGrant"("status");

CREATE UNIQUE INDEX "DataAccessScopeItem_grantId_recordType_recordId_key" ON "DataAccessScopeItem"("grantId", "recordType", "recordId");
CREATE INDEX "DataAccessScopeItem_grantId_idx" ON "DataAccessScopeItem"("grantId");

CREATE INDEX "EntryComment_recordType_recordId_idx" ON "EntryComment"("recordType", "recordId");
CREATE INDEX "EntryComment_authorId_idx" ON "EntryComment"("authorId");

CREATE INDEX "RecordRevision_recordType_recordId_idx" ON "RecordRevision"("recordType", "recordId");
CREATE INDEX "RecordRevision_editedById_idx" ON "RecordRevision"("editedById");

-- AddForeignKey
ALTER TABLE "WorkshopAssignment" ADD CONSTRAINT "WorkshopAssignment_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "WorkshopAssignment" ADD CONSTRAINT "WorkshopAssignment_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "WorkshopAssignment" ADD CONSTRAINT "WorkshopAssignment_assignedById_fkey" FOREIGN KEY ("assignedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "DataAccessGrant" ADD CONSTRAINT "DataAccessGrant_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DataAccessGrant" ADD CONSTRAINT "DataAccessGrant_granteeId_fkey" FOREIGN KEY ("granteeId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DataAccessGrant" ADD CONSTRAINT "DataAccessGrant_requestedById_fkey" FOREIGN KEY ("requestedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "DataAccessGrant" ADD CONSTRAINT "DataAccessGrant_decidedById_fkey" FOREIGN KEY ("decidedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "DataAccessScopeItem" ADD CONSTRAINT "DataAccessScopeItem_grantId_fkey" FOREIGN KEY ("grantId") REFERENCES "DataAccessGrant"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "EntryComment" ADD CONSTRAINT "EntryComment_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "RecordRevision" ADD CONSTRAINT "RecordRevision_editedById_fkey" FOREIGN KEY ("editedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
