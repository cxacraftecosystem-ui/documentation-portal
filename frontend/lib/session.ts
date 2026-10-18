/**
 * THE PAGE'S VIEW OF THE SIGN-IN SESSION (2026-10-17), now that the token is out of its reach.
 *
 * The token lives in an HttpOnly cookie (`lib/sessionCookie.ts`), so nothing here can read, store or
 * send it; the browser attaches it to every same-origin `/api/*` call by itself. What the page can see
 * is the MARKER cookie: a random id that changes whenever the session does. It answers the two
 * questions the client used to ask of the token —
 *
 * - "is anybody signed in on this tab?" — {@link getSessionMarker} is non-null;
 * - "is this still the session a request left with?" — compare the markers (`sessionReplacedSince`
 *   in `lib/api.ts`).
 *
 * and {@link endSession} is what `setToken(null)` used to be: the page has learned the session is over.
 */

import {
  INSECURE_SESSION_MARKER_COOKIE,
  LEGACY_TOKEN_STORAGE_KEY,
  SESSION_ADOPT_PATH,
  SESSION_END_PATH,
  SESSION_MARKER_COOKIE
} from "@/lib/sessionCookie";

/**
 * A marker this page has already ended, while the request that clears the cookie is on its way (or
 * failed). Treated as absent, so the moment `endSession` returns, the tab reads as signed out — the
 * same instant answer clearing `localStorage` gave.
 */
let endedMarker: string | null = null;

/** The one migration run this page load makes. See `migrateStoredToken`. */
let migration: Promise<void> | null = null;

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  let raw = "";
  try {
    raw = document.cookie ?? "";
  } catch {
    return null;
  }
  for (const part of raw.split(";")) {
    const index = part.indexOf("=");
    if (index < 0) continue;
    if (part.slice(0, index).trim() !== name) continue;
    const value = part.slice(index + 1).trim();
    return value || null;
  }
  return null;
}

/** The current session's marker, or null when nobody is signed in on this browser. */
export function getSessionMarker(): string | null {
  const marker = readCookie(SESSION_MARKER_COOKIE) ?? readCookie(INSECURE_SESSION_MARKER_COOKIE);
  if (!marker || marker === endedMarker) return null;
  return marker;
}

/**
 * The session is over (the API refused it, or the person signed out): forget it here at once and ask
 * the site to clear the cookie.
 *
 * The request names the marker being ended, so a session that replaced it in the meantime survives
 * (`handleEnd` in `lib/server/apiProxy.ts`). `keepalive` because the caller often navigates away on
 * the next line. A failure is harmless: the dead token stays in the cookie, the API refuses it again
 * on the next call, and this runs again.
 */
export function endSession(): void {
  const marker = getSessionMarker();
  if (!marker) return;
  endedMarker = marker;
  if (typeof fetch === "undefined") return;
  try {
    void fetch(SESSION_END_PATH, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ marker }),
      cache: "no-store",
      keepalive: true
    }).catch(() => undefined);
  } catch {
    /* See above. */
  }
}

/** For the unit specs, which plant one fake cookie after another in a single module instance. */
export function resetSessionStateForTests(): void {
  endedMarker = null;
  migration = null;
}

function readLegacyToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(LEGACY_TOKEN_STORAGE_KEY);
  } catch {
    return null;
  }
}

function dropLegacyToken(): void {
  try {
    window.localStorage.removeItem(LEGACY_TOKEN_STORAGE_KEY);
  } catch {
    /* Storage blocked: there was nothing readable there either. */
  }
}

/**
 * THE ONE-TIME MOVE for a browser that signed in before the cookie session shipped.
 *
 * Its token is still in `localStorage`. If this browser already has a cookie session, the old token
 * is simply deleted. Otherwise it is posted once to `/api/session/adopt`, which checks it with the
 * API and turns it into the cookie, and then it is deleted — whatever the answer, as long as there
 * WAS an answer. Only a request that never got one (no connection) keeps it, for the next load: a
 * field worker who opens the new site offline must not be signed out by it.
 *
 * Run once per page load, before the first `/me` (`AuthProvider`). Safe to call again: every caller
 * shares the first run.
 */
export function migrateStoredToken(): Promise<void> {
  if (migration) return migration;
  migration = (async () => {
    const legacy = readLegacyToken();
    if (!legacy) return;
    if (getSessionMarker()) {
      dropLegacyToken();
      return;
    }
    try {
      const response = await fetch(SESSION_ADOPT_PATH, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: legacy }),
        cache: "no-store"
      });
      // The site says `adopted: true` or `false` once the API has given its verdict on the token. An
      // answer without that field means the API could not be asked (or the site is down); the token
      // may still be good, so it is kept for the next load.
      let verdict: unknown = undefined;
      try {
        verdict = ((await response.json()) as { adopted?: unknown }).adopted;
      } catch {
        verdict = undefined;
      }
      if (typeof verdict === "boolean") dropLegacyToken();
    } catch {
      /* No answer at all: keep it for the next load. */
    }
  })();
  return migration;
}
