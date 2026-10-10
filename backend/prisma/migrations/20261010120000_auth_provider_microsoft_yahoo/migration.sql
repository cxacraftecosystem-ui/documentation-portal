-- Microsoft and Yahoo sign-in (app/services/oidc_sign_in.py). Additive only: no existing row
-- changes, and an account a Microsoft or Yahoo sign-in creates is recorded under its provider.
ALTER TYPE "AuthProvider" ADD VALUE IF NOT EXISTS 'MICROSOFT';
ALTER TYPE "AuthProvider" ADD VALUE IF NOT EXISTS 'YAHOO';
