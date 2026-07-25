-- Peer-review "send for revision" status (additive enum value; nothing in this
-- transaction uses it, so ADD VALUE is safe inside the migration transaction).
ALTER TYPE "RecordStatus" ADD VALUE IF NOT EXISTS 'NEEDS_REVISION';

-- Documentation tasks assigned by admins/master admin.
CREATE TABLE "AssignedTask" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT,
    "status" TEXT NOT NULL DEFAULT 'OPEN',
    "dueAt" TIMESTAMP(3),
    "completedAt" TIMESTAMP(3),
    "recordType" TEXT,
    "recordId" TEXT,
    "assigneeId" TEXT NOT NULL,
    "createdById" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AssignedTask_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "AssignedTask_assigneeId_status_idx" ON "AssignedTask"("assigneeId", "status");
CREATE INDEX "AssignedTask_createdById_idx" ON "AssignedTask"("createdById");

ALTER TABLE "AssignedTask" ADD CONSTRAINT "AssignedTask_assigneeId_fkey"
    FOREIGN KEY ("assigneeId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "AssignedTask" ADD CONSTRAINT "AssignedTask_createdById_fkey"
    FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
