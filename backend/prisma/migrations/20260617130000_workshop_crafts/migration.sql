-- Crafts covered by a workshop (many-to-many), mirroring "WorkshopArtisan".
CREATE TABLE "WorkshopCraft" (
    "workshopId" TEXT NOT NULL,
    "craftId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WorkshopCraft_pkey" PRIMARY KEY ("workshopId","craftId")
);

CREATE INDEX "WorkshopCraft_craftId_idx" ON "WorkshopCraft"("craftId");

ALTER TABLE "WorkshopCraft" ADD CONSTRAINT "WorkshopCraft_workshopId_fkey"
    FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "WorkshopCraft" ADD CONSTRAINT "WorkshopCraft_craftId_fkey"
    FOREIGN KEY ("craftId") REFERENCES "Craft"("id") ON DELETE CASCADE ON UPDATE CASCADE;
