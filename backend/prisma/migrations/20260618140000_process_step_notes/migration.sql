-- Add optional per-step notes ("record additional information") to process steps.
ALTER TABLE "ProcessStep" ADD COLUMN "notes" TEXT;
