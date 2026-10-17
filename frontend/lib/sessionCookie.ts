/**
 * THE SIGN-IN SESSION'S COOKIES, NAMED ONCE (2026-10-17).
 *
 * The session token used to live in `localStorage` (`field_repo_token`), where any script running on
 * the page could read it and send it anywhere. It now lives in an `HttpOnly` cookie that only the
 * site's own server code can see. The browser calls the API through this site (`/api/*`), and the
 * server side of this site (`proxy.ts` and the session route handlers under `app/api/`) turns the
 * cookie into the `Authorization: Bearer` header the API expects. See docs/SECURITY.md, "Sign-in
 * session".
 *
 * TWO COOKIES, AND ONLY ONE OF THEM IS SECRET.
 *
 * - The SESSION cookie holds the token. `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, no `Domain`,
 *   `Max-Age` = what is left of the token's own lifetime. No script can read it.
 * - The MARKER cookie holds a random id that changes every time the session does (sign-in, password
 *   change, sign-out). It is readable by scripts and says nothing about the token. The page needs it
 *   for two facts it used to read off the token: "is anybody signed in on this tab" (the background
 *   drains do not spend requests when nobody is), and "has the session changed while this request was
 *   out" (`sessionReplacedSince` in `lib/api.ts`). Knowing the marker gives an attacker nothing: the
 *   API never sees it.
 *
 * `__Host-` names on a secure page: the browser then refuses the cookie unless it is `Secure`, has
 * `Path=/` and no `Domain`, so no other site and no subdomain can plant or shadow it. A plain-HTTP
 * page that is not localhost (a laptop serving `next dev` over the LAN) cannot hold a `Secure`
 * cookie at all, so there the same cookies go out without the prefix and without `Secure`. Vercel
 * serves only HTTPS, so production always uses the prefixed names.
 */

export const SESSION_COOKIE = "__Host-session";
export const SESSION_MARKER_COOKIE = "__Host-session-marker";

/** The same two on a plain-HTTP, non-localhost page. Never used in production. */
export const INSECURE_SESSION_COOKIE = "session";
export const INSECURE_SESSION_MARKER_COOKIE = "session-marker";

/** Where the token used to be kept. Read once, by the migration in `lib/session.ts`, then deleted. */
export const LEGACY_TOKEN_STORAGE_KEY = "field_repo_token";

/**
 * The header the change-password route handler answers with when it has put a fresh session in the
 * cookie. Replaces the API's `X-Session-Token`, which carries the token itself and is stripped before
 * the answer reaches the page.
 */
export const SESSION_ROTATED_HEADER = "x-session-rotated";

/** The two session routes that exist only on this site, never on the API. */
export const SESSION_ADOPT_PATH = "/api/session/adopt";
export const SESSION_END_PATH = "/api/session/end";
