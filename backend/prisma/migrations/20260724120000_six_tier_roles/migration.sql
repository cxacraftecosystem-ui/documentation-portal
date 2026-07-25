-- Six-tier role ladder: MASTER_ADMIN > ADMIN > PROFESSOR > RESEARCHER > FIELD_CONTRIBUTOR
-- > CROWDSOURCE_VOLUNTEER. Existing rows keep their current roles; the three new values are
-- appended to the enum. (Postgres 12+ allows ADD VALUE inside a transaction as long as the new
-- value is not used in the same transaction — nothing here uses them.)
ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'PROFESSOR';
ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'FIELD_CONTRIBUTOR';
ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'CROWDSOURCE_VOLUNTEER';
