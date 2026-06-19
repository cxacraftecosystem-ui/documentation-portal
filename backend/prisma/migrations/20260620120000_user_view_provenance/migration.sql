-- Grantable "view provenance" privilege: lets a non-admin user see record provenance
-- (created-by + per-field edit history) on the View Data screen.
ALTER TABLE "User" ADD COLUMN "canViewProvenance" BOOLEAN NOT NULL DEFAULT false;
