"use client";

import { useCallback, useEffect, useState } from "react";

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
 *
 * ONE STORE FOR EVERY MOUNT. The nav and the admin pages mount this hook together; they share one
 * value, one timer and one request at a time (the subscriber shape of `useUnreadNotifications`), and
 * a `refresh()` from any of them updates every badge.
 */
export const REFRESH_MS = 60_000;

type Listener = (value: number | null) => void;

export type PollTimers = {
  every: (run: () => void, ms: number) => unknown;
  stop: (handle: unknown) => void;
};

const browserTimers: PollTimers = {
  every: (run, ms) => window.setInterval(run, ms),
  stop: (handle) => window.clearInterval(handle as number)
};

/** The shared count. Pure apart from the injected fetcher and timers, so the unit spec drives it. */
export function createPendingCountStore(fetchCount: () => Promise<number>, timers: PollTimers = browserTimers) {
  let value: number | null = null;
  /**
   * Fetch generations, the same guard every list page in this app uses. Two reads can be in flight
   * at once — the interval and a post-decision `refresh()` — and the older answer must not
   * overwrite the newer one, or approving the last pending request leaves a "1" on the badge until
   * the next minute elapses.
   */
  let generation = 0;
  let inFlight: Promise<void> | null = null;
  let timer: unknown = null;
  const listeners = new Set<Listener>();

  function read(): Promise<void> {
    const mine = ++generation;
    const request = fetchCount()
      .then((next) => {
        if (mine !== generation || listeners.size === 0) return;
        value = next;
        listeners.forEach((listener) => listener(value));
      })
      .catch(() => {
        // Deliberately silent, and the previous count is deliberately LEFT STANDING. This is a badge
        // on somebody else's page: a failed poll must never put an error in front of an admin who is
        // in the middle of something unrelated, and blanking a count that was true a minute ago would
        // read as "the queue emptied" — the one wrong answer this number can give.
      })
      .finally(() => {
        if (inFlight === request) inFlight = null;
      });
    inFlight = request;
    return request;
  }

  /** `force` starts a fresh read even while one is out (after a decision); otherwise one is shared. */
  function refresh(force = false): Promise<void> {
    if (!force && inFlight) return inFlight;
    return read();
  }

  function subscribe(listener: Listener): () => void {
    listeners.add(listener);
    listener(value);
    void refresh();
    if (timer === null) timer = timers.every(() => void refresh(), REFRESH_MS);
    return () => {
      listeners.delete(listener);
      if (listeners.size > 0) return;
      if (timer !== null) timers.stop(timer);
      timer = null;
      // Nobody is showing it: forget it, so a later sign-in never starts from somebody else's count.
      generation += 1;
      inFlight = null;
      value = null;
    };
  }

  return { subscribe, refresh };
}

const sharedStore = createPendingCountStore(accessRosterPendingCount);

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

  useEffect(() => {
    if (!permitted) {
      // A user who is not an admin (or who was demoted mid-session) must not keep a stale count on
      // screen — and must not keep polling an endpoint that would 403 them.
      setPending(null);
      return;
    }
    return sharedStore.subscribe(setPending);
  }, [permitted]);

  const refresh = useCallback(() => {
    if (permitted) void sharedStore.refresh(true);
  }, [permitted]);

  return { pending, refresh };
}
