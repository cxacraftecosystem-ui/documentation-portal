-- Add an optional height (inches) to product documentation so grid measurement can record height
-- alongside length and breadth. Nullable + no default => metadata-only change, safe on a live table.
ALTER TABLE "ProductDocumentation" ADD COLUMN "heightInches" DECIMAL(10,2);
