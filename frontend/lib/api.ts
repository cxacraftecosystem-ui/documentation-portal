import type { PageResult } from "@/lib/types";

import { endSession, getSessionMarker } from "@/lib/session";

/**
 * WHERE THE BROWSER SENDS API CALLS: THIS SITE (2026-10-17).
 *
 * Every call goes to `/api/...` on the page's own origin. `proxy.ts` and the session route handlers
 * under `app/api/` pass it on to the API and turn the HttpOnly session cookie into the bearer token
 * the API expects — the page never holds the token (`lib/sessionCookie.ts`, docs/SECURITY.md). So
 * this base is empty, and the API's real address (`NEXT_PUBLIC_API_URL`) is the server's business.
 *
 * A deployment that was never told the API's address now answers 503 from its own server rather
 * than shipping a bundle that quietly calls the visitor's own machine.
 */
export const API_BASE = "";

/**
 * The API's own address, for the few PUBLIC links a browser follows without a session — the Android
 * download, which is a large file and has no reason to cross this site. Never for a signed-in call.
 */
export const PUBLIC_API_ORIGIN = (process.env.NEXT_PUBLIC_API_URL ?? "").trim().replace(/\/+$/, "") || "http://localhost:8000";

/**
 * The header the server adds to a 401 it sends because the account must choose a new password
 * (`deps.PASSWORD_CHANGE_REQUIRED_HEADER`). That 401 is NOT a dead session: the token stays, and the
 * protected tree draws `FirstPasswordGate` in place of the page. Lower-case because `Headers.get` is
 * case-insensitive and this is how the browser reports it.
 */
export const PASSWORD_CHANGE_REQUIRED_HEADER = "x-password-change-required";

export class ApiError extends Error {
  status: number;
  payload: unknown;
  /** The response's headers, where the request got one. */
  headers: Headers | null;
  /** A 401 carrying {@link PASSWORD_CHANGE_REQUIRED_HEADER}: keep the session, show the gate. */
  passwordChangeRequired: boolean;
  /** A 401 for a token this tab has already replaced (a password change hands back a new one). */
  sessionReplaced: boolean;

  constructor(status: number, message: string, payload: unknown, headers: Headers | null = null) {
    super(message);
    this.status = status;
    this.payload = payload;
    this.headers = headers;
    this.passwordChangeRequired = status === 401 && headers?.get(PASSWORD_CHANGE_REQUIRED_HEADER) === "1";
    this.sessionReplaced = false;
  }
}

/** Pages a signed-out person uses; an expired token found there is dropped without navigating away. */
export const SIGNED_OUT_PAGES = new Set(["/login", "/set-password", "/forgot-password"]);

let passwordChangeListener: (() => void) | null = null;
let passwordChangeOwed = false;

/**
 * `AuthProvider` registers here to be told when the server refuses a request because the account owes
 * a new password; it re-reads `/me`, which carries `mustChangePassword`, and the shell draws the gate.
 */
export function onPasswordChangeRequired(listener: (() => void) | null) {
  passwordChangeListener = listener;
}

/** Has the server told this tab that the signed-in account must choose a new password? */
export function sessionOwesPasswordChange(): boolean {
  return passwordChangeOwed;
}

/** Called once the gate has been satisfied (or the session ends). */
export function clearPasswordChangeOwed() {
  passwordChangeOwed = false;
}

/**
 * Raised instead of a request that could not possibly have worked: a deployed page whose only known
 * API address is the visitor's own machine.
 *
 * It extends {@link ApiError} for two concrete reasons. Every screen already renders an ApiError's
 * `message`, so the sentence below lands in the UI the researcher is looking at. And `lib/offline.ts`
 * treats a NON-ApiError as "the network is down" and quietly banks the save in the outbox — the same
 * defect all over again, a failure reported as success. The 503 is not a response anyone received;
 * no request was made. It is the code the rest of the app already reads as "the service is not
 * answering right now", which is the behaviour we want: queued work stays queued and drains by
 * itself once an administrator redeploys, while the person in front of the screen is still told.
 */
export class ApiUnconfiguredError extends ApiError {
  constructor() {
    super(
      503,
      "The site can’t connect right now. Please try again later.",
      null
    );
    this.name = "ApiUnconfiguredError";
  }
}

/**
 * Kept for the call sites that build their own `fetch` (downloads): there is no longer anything to
 * check in the browser, since the address the page calls is its own and only the server knows the
 * API's. A server with no API address answers 503.
 */
export function assertApiConfigured(): void {}

/**
 * The sentence a failed response is actually carrying, dug out of whatever shape `detail` took.
 *
 * A route's `detail` is not always a string. FastAPI's own 422 makes it a LIST of per-field error
 * objects, and a few routes raise a structured object instead (the artisan identity conflict carries
 * the clashing artisan along with the message, so the form can offer to open it). Both used to go
 * through `String(detail)`, which yields the literal "[object Object]" — and since every screen
 * renders `ApiError.message` and nothing else, that string was the entire answer a researcher got
 * from a rejected save on every form but the two that had privately re-parsed the response body for
 * themselves. Unpacking it once, here, is what gives the rest of them a real sentence.
 *
 * Exported because those private copies (`components/forms/ArtisanForm`, `components/review/
 * reviewErrors`) exist only to work around this and should eventually just call it.
 */
export function describeApiDetail(detail: unknown, fallback: string): string {
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => {
        if (!entry || typeof entry !== "object") return "";
        const record = entry as { msg?: unknown; loc?: unknown };
        const message = "msg" in record ? String(record.msg) : "";
        if (!message) return "";
        // `loc` is ["body", "<field>"] — naming the field is the whole point of showing a 422 raised
        // by a form with a dozen boxes in it. A bare ["body"] names nothing, so it is left off.
        const field = Array.isArray(record.loc)
          ? record.loc.filter((part): part is string => typeof part === "string").at(-1)
          : null;
        // Pydantic prefixes every custom validator message with "Value error, "; the researcher only
        // needs the sentence after it.
        const cleaned = message.replace(/^Value error,\s*/, "").trim();
        return field && field !== "body" ? `${field}: ${cleaned}` : cleaned;
      })
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  return fallback;
}

/**
 * The page's view of the session since 2026-10-17: its MARKER, never the token, which is in an
 * HttpOnly cookie no script can read (`lib/session.ts`). `getSessionMarker()` is non-null while
 * somebody is signed in; `endSession()` forgets a session the API has refused.
 */
export { endSession, getSessionMarker } from "@/lib/session";

/**
 * The build this page is, sent as `X-App-Version` on every call (questionnaire contract §1.1) so the
 * server can log versions and refuse creates from a build past its cutoff.
 */
export const APP_VERSION = `web-${(process.env.NEXT_PUBLIC_APP_VERSION ?? "").trim() || "0.1.0"}`;

let clockOffsetMs: number | null = null;
let clockOffsetAt = 0;

/**
 * `serverDate - clientTime` from the last response that carried a `Date` header, and when it was
 * measured. The questionnaire's `capturedAt` correction reads it (DESIGN §3.9); an offset older than
 * seven days makes a capture time "estimated" on the server.
 */
export function clockOffset(): { offsetMs: number; measuredAt: number } | null {
  return clockOffsetMs === null ? null : { offsetMs: clockOffsetMs, measuredAt: clockOffsetAt };
}

/** Exported for the unit spec: a `Date` header against the client time the request left with. */
export function recordClockOffset(dateHeader: string | null, sentAt: number, now: number = Date.now()) {
  if (!dateHeader) return;
  const server = Date.parse(dateHeader);
  if (Number.isNaN(server)) return;
  // `Date` has whole-second resolution and the response took time to come back: compare against the
  // midpoint of the round trip, which halves the error.
  clockOffsetMs = server - Math.round((sentAt + now) / 2);
  clockOffsetAt = now;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  return (await apiFetchWithHeaders<T>(path, init)).body;
}

/**
 * {@link apiFetch}, with the response's headers handed back too — for the one answer that carries
 * something in a header: `POST /auth/change-password` puts the fresh session token in
 * `X-Session-Token` (see `lib/signIn.ts`). Same refusals, same 401 handling.
 */
export async function apiFetchWithHeaders<T>(
  path: string,
  init: RequestInit = {}
): Promise<{ body: T; headers: Headers }> {
  assertApiConfigured();

  const headers = new Headers(init.headers);
  /** The session marker this request left with (not a credential): see the 401 branch. */
  const token = getSessionMarker();
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const sentAt = Date.now();
  headers.set("X-Client-Time", new Date(sentAt).toISOString());
  headers.set("X-App-Version", APP_VERSION);

  const response = await fetch(`${API_BASE}/api${path}`, {
    ...init,
    headers,
    cache: "no-store"
  });

  recordClockOffset(response.headers.get("date"), sentAt);

  if (response.status === 204) return { body: undefined as T, headers: response.headers };

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();

  if (!response.ok) {
    const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it cannot be the last
    // resort on its own, or a body-less failure reaches the screen as a blank error box.
    const message = describeApiDetail(detail, response.statusText || "Something went wrong. Try again.");
    const error = new ApiError(response.status, message, body, response.headers);
    if (error.passwordChangeRequired) {
      // NOT a dead session: the account must choose a new password first. Keep the token, never
      // navigate, and let AuthProvider re-read `/me` so the shell draws the gate in place.
      passwordChangeOwed = true;
      passwordChangeListener?.();
      throw error;
    }
    const held = getSessionMarker();
    if (response.status === 401 && token && held !== null && held !== token) {
      // The session this request carried has already been replaced in this tab (a password change
      // renews it). The 401 is about a session already let go of; the live one is untouched.
      error.sessionReplaced = true;
      throw error;
    }
    if (response.status === 401 && token && typeof window !== "undefined") {
      // A previously-valid session expired: end it and send the user to login
      // (unless they are already there). Anonymous requests — e.g. the landing page's /me probe —
      // sent no token, so they fail without navigating the visitor away from public pages.
      endSession();
      // A FULL page load on purpose, which is the one thing the rule below (new in
      // eslint-config-next 16.3) exists to discourage. This module sits outside React, so there is
      // no router to push with, and the session has just ended: a page load is what discards the
      // signed-out session's in-memory state instead of carrying it onto the login screen.
      // Never away from a signed-out page: somebody holding a password link or asking for one must not
      // lose that screen because an old token was still in this browser.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      if (!SIGNED_OUT_PAGES.has(window.location.pathname)) window.location.assign("/login");
    }
    throw error;
  }

  return { body: body as T, headers: response.headers };
}

export function buildQuery(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export async function listResource<T>(path: string, params: Record<string, string | number | undefined | null>) {
  return apiFetch<PageResult<T>>(`${path}${buildQuery(params)}`);
}
