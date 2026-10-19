-- TOOL TRADITION WORDING (owner decision, 2026-10-19): "Traditional", "Contemporary" and
-- "Transitional (Hybrid)". Additive and live-safe: two enum values, no data change. MODERN and HYBRID
-- stay so the rows that hold them, and any build still sending them, keep working; the dry-run-first
-- script backend/scripts/standardise_values.py rewrites those rows after deploy. Guarded, so a re-run
-- changes nothing.
ALTER TYPE "TraditionType" ADD VALUE IF NOT EXISTS 'CONTEMPORARY';
ALTER TYPE "TraditionType" ADD VALUE IF NOT EXISTS 'TRANSITIONAL';
