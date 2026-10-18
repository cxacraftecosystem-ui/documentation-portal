/**
 * A SIGNED-IN BROWSER, AS NODE CAN FAKE ONE — for the unit specs that drive `apiFetch` and the drains.
 *
 * Since 2026-10-17 the page cannot see the session token at all (it is an HttpOnly cookie); what tells
 * it "somebody is signed in" is the readable MARKER cookie (`lib/sessionCookie.ts`, `lib/session.ts`).
 * So where these specs used to plant `field_repo_token` in a fake `localStorage`, they now plant a
 * marker in a fake `document.cookie`.
 *
 * Installing one also resets what `lib/session.ts` remembers (the marker it last ended), because the
 * specs plant the same strings test after test in one module instance.
 */

import { resetSessionStateForTests } from "../../lib/session";
import { SESSION_MARKER_COOKIE } from "../../lib/sessionCookie";

export type FakeSessionCookie = {
  /** The marker planted, or null when the browser was signed out to begin with. */
  readonly marker: string | null;
  /** What the cookie holds now (the site's answer may replace or clear it). */
  current(): string | null;
  /** Replace the cookie, as a sign-in or a password change answered by the site would. */
  set(marker: string | null): void;
};

export function installSessionCookie(label: string | null): FakeSessionCookie {
  resetSessionStateForTests();
  const marker = label;
  let value = marker;
  (globalThis as Record<string, unknown>).document = {
    get cookie() {
      return value ? `${SESSION_MARKER_COOKIE}=${value}` : "";
    }
  };
  return {
    marker,
    current: () => value,
    set: (next) => {
      value = next;
    }
  };
}

export function removeSessionCookie(): void {
  delete (globalThis as Record<string, unknown>).document;
}

/** Is this fetch call the page asking the site to clear a session cookie? */
export function isSessionEndCall(input: unknown): boolean {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : (input as { url?: string })?.url ?? "";
  return url.endsWith("/api/session/end");
}
