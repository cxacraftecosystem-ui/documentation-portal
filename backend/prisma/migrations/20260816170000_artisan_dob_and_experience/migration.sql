-- Give Artisan the two facts the design workshop has always asked for and this table could not
-- answer: date of birth (from which age is derived) and years of experience.
--
-- WHY THE BACKFILL IS THE POINT OF THIS MIGRATION, not an afterthought. Both values have existed
-- for years in `extraMetadata`, under the spellings researchers used before the artisan form was
-- structured — and the reference hydration, the artisan record sheet and the participant table all
-- still read those spellings today. Adding the columns without moving the data would leave the
-- oldest and best-documented artisans reading blank in the new columns while the legacy JSON they
-- do have is quietly ignored: a regression delivered by the migration that was meant to fix them.
--
-- So the columns are added, the legacy keys are copied into them, and the readers keep a fallback
-- to the JSON for anything this copy could not parse.

ALTER TABLE "Artisan" ADD COLUMN "dateOfBirth" TIMESTAMP(3);
ALTER TABLE "Artisan" ADD COLUMN "experienceYears" INTEGER;

-- EXPERIENCE: three legacy spellings, in the order the readers try them, first non-null wins.
-- Only clean whole numbers are taken. A value like "30+" or "about 30" stays in the JSON rather
-- than being guessed at — the readers still fall back to it, and a number this migration invented
-- would be indistinguishable from one an artisan stated.
UPDATE "Artisan"
SET "experienceYears" = LEAST(
        GREATEST(
            (COALESCE(
                "extraMetadata" ->> 'experienceYears',
                "extraMetadata" ->> 'experience',
                "extraMetadata" ->> 'yearsOfExperience'
            ))::INTEGER,
            0
        ),
        99
    )
WHERE "extraMetadata" IS NOT NULL
  AND COALESCE(
        "extraMetadata" ->> 'experienceYears',
        "extraMetadata" ->> 'experience',
        "extraMetadata" ->> 'yearsOfExperience'
      ) ~ '^[0-9]{1,3}$';

-- DATE OF BIRTH: derived from a legacy AGE, and this is the one lossy step in the migration, so it
-- is spelled out. A record that says "42" was written on a known day (`recordedAt`), so the year of
-- birth is recoverable to within a year and nothing better exists. The day and month are NOT
-- invented: 1 July is used as the mid-year point, which keeps the derived age correct to ±6 months
-- either side rather than systematically over- or under-stating it as 1 January would.
--
-- An artisan who later supplies a real date of birth simply overwrites this. Anyone who needs to
-- know which dates were derived can find them by the 1 July: no artisan form has ever defaulted a
-- birthday, so a 1 July here is this migration's signature.
UPDATE "Artisan"
SET "dateOfBirth" = MAKE_TIMESTAMP(
        EXTRACT(YEAR FROM "recordedAt")::INT - ("extraMetadata" ->> 'age')::INT,
        7, 1, 0, 0, 0
    )
WHERE "extraMetadata" IS NOT NULL
  AND "extraMetadata" ->> 'age' ~ '^[0-9]{1,3}$'
  AND ("extraMetadata" ->> 'age')::INT BETWEEN 10 AND 110;
