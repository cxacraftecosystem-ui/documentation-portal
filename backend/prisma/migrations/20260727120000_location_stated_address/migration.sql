-- The stated address, separated from the device fix.
--
-- WHY. Every one of the fifteen artisans on the live database that carries a location sits between
-- 22.313 and 22.315 N, 87.309 and 87.313 E — Kharagpur, West Bengal — while the places the
-- researchers typed are "Bagru, Jaipur, Rajasthan", "Balotra, Rajasthan", "Kutch, Gujrat",
-- "Rudraprayag, Dehradun", "Ballupur, Dehradun", "Jaipur, Sanganeri, Rajasthan" and
-- "Kappaladoddi, Andhra Pradesh". The coordinates are not a bug and not a hardcoded constant: they
-- jitter naturally and carry real accuracy values from 26 m to 2,506 m. They are genuine GPS fixes
-- of the desk the record was typed at, 1,500 km from the artisan — which is ordinary, reasonable
-- behaviour that this table had no way to express. So the fix was read as the artisan's address,
-- `state` and `pincode` stayed NULL on all fifteen, and the researchers hand-encoded the village
-- and district into the free-text `place` column because there was nowhere else to put them.
--
-- Two of the four columns below are that missing nowhere-else. The other two are the pin a
-- researcher may drop on the SUBJECT'S place, which cannot share "latitude"/"longitude" with the
-- device fix: one pair that sometimes means the artisan and sometimes means the desk is unusable in
-- an export, and telling the two apart afterwards is exactly the problem this migration ends.
--
--   * "district" — the canonical district within "state", from the same closed list
--     app/services/address.py holds and GET /api/reference/address serves. It is not new to the
--     product: "District" has been a user-visible column in the record-field registry since that
--     registry was written, feeding the data browser's info card and record table, every
--     details.txt in /export/dataset and the artisan sheet of the /data/report workbook. It read an
--     extraMetadata key that nothing has written since the artisan form stopped writing
--     extraMetadata, so it has been blank on every record. This column is what it reads now.
--   * "village" — free text, because no closed list of Indian villages exists that a field
--     researcher could pick from. District is the finest unit that can be CHECKED; village is the
--     finest that can be NAMED.
--   * "subjectLatitude" / "subjectLongitude" — the optional precise pin on the subject's place.
--     NULL on every existing row, which is the honest answer: nobody has ever been asked for one.
--
-- WHAT THIS MIGRATION DOES NOT DO, deliberately and permanently. It moves no value, backfills
-- nothing and guesses nothing. The fifteen coordinates stay exactly where they are, because as
-- PROVENANCE they were always correct — it was the reading of them that was wrong. Their stated
-- address stays NULL until a researcher fills it in, and the disagreement between a West Bengal
-- coordinate and a Rajasthan place name is FLAGGED in the form for the person who was there,
-- never resolved here. Parsing "Rudraprayag, Dehradun" into a state and a district would be this
-- migration inventing research data: Rudraprayag is a district of Uttarakhand and Dehradun is a
-- different district of the same state, so even that one line has no single correct reading.
--
-- Four NULLable columns with no default. Postgres adds these as a catalogue-only change — no table
-- rewrite, no row lock beyond the ALTER itself — and no index is created (see schema.prisma for why
-- district gets none).

-- AlterTable
ALTER TABLE "Location" ADD COLUMN "district" TEXT;

-- AlterTable
ALTER TABLE "Location" ADD COLUMN "village" TEXT;

-- AlterTable
ALTER TABLE "Location" ADD COLUMN "subjectLatitude" DOUBLE PRECISION;

-- AlterTable
ALTER TABLE "Location" ADD COLUMN "subjectLongitude" DOUBLE PRECISION;
