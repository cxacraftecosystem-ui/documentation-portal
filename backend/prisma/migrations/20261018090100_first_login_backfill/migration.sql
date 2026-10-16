-- "Has this account ever signed in?" for every account that existed before "firstLoginAt" was written.
--
-- WHY. A set-password link is a 72-hour invitation for an account nobody has started using and a
-- two-hour reset for a live one (app/services/credential_links.py::purpose_for). "firstLoginAt" is what
-- tells the two apart, but 20261018090000_account_passwords added it with no values, so every account
-- already in use read as "never signed in". The stop-gap was to trust a NULL only for accounts created
-- after that migration; that gave a never-used account created earlier a two-hour link.
--
-- THE EVIDENCE. Since 20260816120000_access_roster_signin_gate every successful sign-in has stamped
-- "AccessRoster"."firstSeenAt" once, and that migration filled it in for every account that existed
-- then. So:
--   * a roster row with a "firstSeenAt"  -> signed in at that moment;
--   * no roster row at all               -> an account admitted without the roster (the master admin):
--                                           treated as in use from the day it was created;
--   * a roster row with no "firstSeenAt" -> never got in. Left NULL, so its link is an invitation.
--
-- Rows already stamped are left alone, which also makes re-running this file harmless.
UPDATE "User" AS u
SET "firstLoginAt" = COALESCE(r."firstSeenAt", u."createdAt")
FROM "User" AS self
LEFT JOIN "AccessRoster" AS r ON r."email" = lower(self."email")
WHERE self."id" = u."id"
  AND u."firstLoginAt" IS NULL
  AND (r."id" IS NULL OR r."firstSeenAt" IS NOT NULL);
