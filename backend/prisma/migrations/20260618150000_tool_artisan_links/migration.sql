-- Many-to-many: assign one documented tool to several artisans (same or different crafts).
CREATE TABLE "ToolArtisan" (
    "id" TEXT NOT NULL,
    "toolId" TEXT NOT NULL,
    "artisanId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ToolArtisan_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "ToolArtisan_toolId_artisanId_key" ON "ToolArtisan"("toolId", "artisanId");
CREATE INDEX "ToolArtisan_toolId_idx" ON "ToolArtisan"("toolId");
CREATE INDEX "ToolArtisan_artisanId_idx" ON "ToolArtisan"("artisanId");

ALTER TABLE "ToolArtisan" ADD CONSTRAINT "ToolArtisan_toolId_fkey"
    FOREIGN KEY ("toolId") REFERENCES "ToolDocumentation"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "ToolArtisan" ADD CONSTRAINT "ToolArtisan_artisanId_fkey"
    FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;
