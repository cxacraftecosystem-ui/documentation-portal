CREATE TABLE IF NOT EXISTS "DeviceOutboxReport" (
    "userId" TEXT NOT NULL,
    "installId" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "appVersion" TEXT,
    "pending" INTEGER NOT NULL DEFAULT 0,
    "failed" INTEGER NOT NULL DEFAULT 0,
    "oldestAt" TIMESTAMP(3),
    "reportedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DeviceOutboxReport_pkey" PRIMARY KEY ("userId","installId")
);

CREATE INDEX IF NOT EXISTS "DeviceOutboxReport_pending_oldestAt_idx" ON "DeviceOutboxReport"("pending", "oldestAt");

ALTER TABLE "DeviceOutboxReport" ADD CONSTRAINT "DeviceOutboxReport_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "DeviceOutboxReport" ENABLE ROW LEVEL SECURITY;
