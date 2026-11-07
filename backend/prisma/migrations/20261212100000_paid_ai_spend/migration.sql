CREATE TABLE IF NOT EXISTS "PaidAiSpend" (
    "id" TEXT NOT NULL,
    "bucket" TEXT NOT NULL,
    "userId" TEXT NOT NULL DEFAULT '',
    "spentAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "PaidAiSpend_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "PaidAiSpend_bucket_spentAt_idx" ON "PaidAiSpend"("bucket", "spentAt");

CREATE INDEX IF NOT EXISTS "PaidAiSpend_bucket_userId_spentAt_idx" ON "PaidAiSpend"("bucket", "userId", "spentAt");
