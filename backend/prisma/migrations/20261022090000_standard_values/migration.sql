-- CONVERGENCE POINT 10: STANDARD VALUES. Additive and live-safe: two nullable columns, no default,
-- no constraint, no data change. Existing rows keep their values until the dry-run-first script
-- backend/scripts/standardise_values.py is approved and applied after deploy. Guarded, so a re-run
-- changes nothing.

-- The phone's country code; Artisan.phone becomes the national number as digits only.
ALTER TABLE "Artisan" ADD COLUMN IF NOT EXISTS "phoneCountryCode" TEXT;

-- The tool's material family from the shared list; ToolDocumentation.material stays free text.
ALTER TABLE "ToolDocumentation" ADD COLUMN IF NOT EXISTS "materialFamily" TEXT;
