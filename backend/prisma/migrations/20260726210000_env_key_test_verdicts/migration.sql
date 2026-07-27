-- Persist the verdict of a provider-key test when the key comes from the ENVIRONMENT.
--
-- WHAT WAS BROKEN. An engine (ElevenLabs / Deepgram / Whisper) is frozen at the bottom of the
-- transcription ranking until its API key has been tested and passes. `managed_secrets.test_secret`
-- records that verdict on the ManagedSecret row — but a row exists only for keys entered through
-- the Settings hub. On production every key is supplied by the environment, so there was nothing to
-- write to and the verdict was held in process memory: lost on every restart and every deploy, and
-- the freeze reset to "nothing verified" each time.
--
-- WHY NOT JUST CREATE A ManagedSecret ROW. Because that row is an OVERRIDE — the resolver prefers
-- it over the environment — so creating one copies the deployed secret value into the database AND
-- changes which value the transcription chain actually sends. Persisting a boolean must not do
-- either of those things.
--
-- WHAT IS STORED. The verdict only: which key, pass or fail, when, and the short redacted error.
-- `fingerprint` is HMAC-SHA256 of the key value under a pepper derived (HKDF) from
-- SECRETS_ENCRYPTION_KEY, or from JWT_SECRET when that is unset — the same fallback ManagedSecret's
-- encryption uses. It is not the value, not a prefix of it and not an unkeyed hash of it: without
-- the pepper, which is not in the database, it cannot be computed for a candidate key and so cannot
-- be brute-forced from a dump. Its ONE job is that rotating a key invalidates its verdict, which is
-- what `set_secret` already does for database-backed keys. A fingerprint that does not match the
-- current environment value (a rotation) or that cannot be recomputed at all (the pepper changed
-- because JWT_SECRET was rotated) makes the engine read as UNTESTED and re-freeze — the safe
-- direction, since an unproven key must never inherit a proven one's pass.
--
-- HOW TO APPLY IT. Ordinarily, i.e. `prisma migrate deploy` during the normal backend deploy. There
-- is nothing concurrent here and nothing to run by hand: one CREATE TABLE on a table that does not
-- exist yet, no lock on anything the app is using, no backfill.
--
-- ORDER RELATIVE TO 20260726200000_index_coverage. This migration sorts AFTER it and is independent
-- of it — the two touch no common object. If the index migration's manual step is being used, the
-- intended sequence is unchanged: run its `apply_concurrently.sql` by hand first, then deploy, and
-- `prisma migrate deploy` applies 20260726200000 (a no-op against the concurrent build) followed by
-- this one. Applying this one alone, or first, would also be correct; the numbering only reflects
-- the order they were written.
--
-- ROLLING BACK is `DROP TABLE "SecretTestResult";`. The application treats an absent row exactly as
-- it treated a cold process before this existed: the key reads as untested and the engine freezes
-- until somebody presses Test.

-- CreateTable
CREATE TABLE "SecretTestResult" (
    "key" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "checkedAt" TIMESTAMP(3) NOT NULL,
    "error" TEXT,
    "fingerprint" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "SecretTestResult_pkey" PRIMARY KEY ("key")
);
