CREATE TABLE IF NOT EXISTS "UsageEvent" (
    "id" TEXT NOT NULL,
    "userId" TEXT,
    "routeTemplate" TEXT NOT NULL,
    "method" TEXT NOT NULL,
    "statusCode" INTEGER NOT NULL,
    "durationMs" INTEGER NOT NULL,
    "clientApp" TEXT NOT NULL,
    "consentState" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "UsageEvent_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "UsageEvent_userId_createdAt_idx" ON "UsageEvent"("userId", "createdAt");
CREATE INDEX IF NOT EXISTS "UsageEvent_routeTemplate_createdAt_idx" ON "UsageEvent"("routeTemplate", "createdAt");

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'UsageEvent_userId_fkey') THEN
    ALTER TABLE "UsageEvent" ADD CONSTRAINT "UsageEvent_userId_fkey"
      FOREIGN KEY ("userId") REFERENCES "User"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

ALTER TABLE "UsageEvent" ENABLE ROW LEVEL SECURITY;
