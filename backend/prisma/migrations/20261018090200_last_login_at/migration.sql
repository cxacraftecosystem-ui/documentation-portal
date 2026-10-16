-- The most recent sign-in, for the users screen's "Last signed in". Additive: one nullable column,
-- written from the next sign-in on (app/services/access_roster.py::mark_first_seen). Not back-filled:
-- nothing recorded the last sign-in before this, and a guessed date would be read as a fact.
ALTER TABLE "User" ADD COLUMN "lastLoginAt" TIMESTAMP(3);
