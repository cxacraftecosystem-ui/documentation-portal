-- Do's (positive prompt) and Don'ts (negative prompt) for an artisan. Nullable so the existing rows
-- recorded before this field are valid and can be backfilled later; new records require them at the
-- API layer (ArtisanCreate).
ALTER TABLE "Artisan" ADD COLUMN "dos" TEXT;
ALTER TABLE "Artisan" ADD COLUMN "donts" TEXT;
