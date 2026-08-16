"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { accessRosterPendingCount } from "@/lib/accessRoster";
import { canManageAccessRoster } from "@/lib/permissions";

/**
 * How many people are waiting on an administrator — THE NOTIFICATION, for every surface that wants
 * to show it.
 *
 * WHY A POLL AND NOT A PUSH. The requirement asks that admins be notified when somebody is turned
 * away. There is no email, no push and no websocket anywhere in this codebase — none has ever
 * existed in it — so the notification is a number on the surfaces admins already open. This hook is
 * how that number gets there, and it is deliberately the ONLY thing in the frontend that calls
 * `/access-roster/pending-count`, so the polling interval is one decision in one place rather than
 * a different one per screen.
 *
 * [REFRESH_MS] is a minute. A pending request is not an incident: nobody is blocked while it waits
 * except the applicant, who is already being told to wait, and an admin who has the page open sees
 * the badge appear within a minute of the attempt. Faster would buy nothing and would multiply a
 * query that every admin's browser runs, forever, on every protected page.
 */
const REFRESH_MS = 60_000;

export type PendingAccessCount = {
  /**
   * `null` until the first answer arrives, and `null` again is never written afterwards.
   *
   * Null vs 0 is a real distinction on this hook and every caller must honour it: 0 means "the
   * server said there is nobody waiting" and null means "we do not know yet". Rendering a "0"
   * badge while the request is in flight tells an admin the queue is empty at the exact moment it
   * might not be, and that is the failure this whole feature exists to avoid.
   */
  pending: number | null;
  /** Re-reads the count now. Call it after approving or rejecting, so the badge cannot lag. */
  refresh: () => void;
};

export function usePendingAccessCount(): PendingAccessCount {
  const { user } = useAuth();
  const permitted = canManageAccessRoster(user);
  const [pending, setPending] = useState<number | null>(null);

  /**
   * Fetch generations, the same guard every list page in this app uses. Two refreshes can be in
   * flight at once — the interval and a post-decision `refresh()` — and the older answer must not
   * overwrite the newer one, or approving the last pending request leaves a "1" on the badge until
   * the next minute elapses.
   */
  const generation = useRef(0);

  const load = useCallback(async () => {
    if (!permitted) return;
    const mine = ++generation.current;
    try {
      const next = await accessRosterPendingCount();
      if (mine !== generation.current) return;
      setPending(next);
    } catch {
      // Deliberately silent, and the previous count is deliberately LEFT STANDING. This is a badge
      // on somebody else's page: a failed poll must never put an error in front of an admin who is
      // in the middle of something unrelated, and blanking a count that was true a minute ago would
      // read as "the queue emptied" — the one wrong answer this number can give.
    }
  }, [permitted]);

  useEffect(() => {
    if (!permitted) {
      // A user who is not an admin (or who was demoted mid-session) must not keep a stale count on
      // screen — and must not keep polling an endpoint that would 403 them.
      generation.current += 1;
      setPending(null);
      return;
    }
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load, permitted]);

  return { pending, refresh: load };
}
