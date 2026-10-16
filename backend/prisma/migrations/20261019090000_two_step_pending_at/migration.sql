-- Two-step setup that was started and never confirmed (2026-10-17). One nullable column: when the
-- pending secret was issued. A pending secret older than fifteen minutes is treated as abandoned and
-- cleared, so an account whose owner opened setup and walked away reads as Off. Additive only.

ALTER TABLE "User" ADD COLUMN "twoFactorPendingAt" TIMESTAMP(3);
