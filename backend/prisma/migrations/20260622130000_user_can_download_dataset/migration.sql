-- Grantable "download entire dataset" privilege: the full-dataset export (the "Download entire
-- dataset" control on the View Data screen) is now admin-only, but the master admin can grant it to
-- individual researchers. Admins always have it implicitly; this flag covers non-admins.
ALTER TABLE "User" ADD COLUMN "canDownloadDataset" BOOLEAN NOT NULL DEFAULT false;
