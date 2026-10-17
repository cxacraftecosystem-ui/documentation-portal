-- The first-password code check's budget, moved out of process memory (2026-10-17): five checks per
-- account per fifteen minutes, now surviving a restart. Additive only: two defaulted/nullable User
-- columns that nothing else reads.

ALTER TABLE "User" ADD COLUMN "firstPasswordCodeAttempts" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "User" ADD COLUMN "firstPasswordCodeWindowStartedAt" TIMESTAMP(3);
