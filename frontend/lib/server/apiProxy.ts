/**
 * THE SITE'S OWN DOOR TO THE API (2026-10-17): the server half of the cookie session.
 *
 * The browser no longer talks to the API's CloudFront address. It calls `/api/*` on this site, and
 * two pieces of server code here pass the call on:
 *
 * - `proxy.ts` (Next.js Proxy, which Vercel runs as Routing Middleware) handles EVERY `/api/*` request
 *   except the six session routes below. It checks the request came from this site, turns the session
 *   cookie into `Authorization: Bearer`, and REWRITES the request to the API. A rewrite is forwarded by
 *   Vercel's network rather than by a function, so request and response bodies stream through without
 *   the 4.5 MB function payload limit and without being held in memory (`vercel.json` also sets
 *   `skipMiddlewareRequestBody`, so the body never even passes through the Proxy code).
 * - The route handlers under `app/api/auth/*` and `app/api/session/*` handle the routes whose ANSWER
 *   carries or ends a session. They must read the answer — to take the token out of it and put it in
 *   the cookie — which a rewrite cannot do. Their bodies are a few hundred bytes of JSON.
 *
 * Everything that decides anything lives in this file, as plain functions over the web-standard
 * `Request`/`Response`/`Headers`, so `e2e/cookie-session-proxy-unit.spec.ts` can drive it in Node
 * without a Next.js server.
 *
 * WHAT THE API IS TOLD BESIDES THE TOKEN.
 *
 * - `X-Web-Proxy-Secret`: the shared secret `WEB_PROXY_SECRET` (a Vercel server variable, never sent to
 *   the browser), so the API can tell a call relayed by this site from one sent straight to CloudFront.
 *   `X-Origin-Verify` cannot do that job: CloudFront itself puts it on EVERY request it forwards, from
 *   anybody, and replaces whatever value the caller sent.
 * - `X-Viewer-Address`: the visitor's own address, as Vercel saw it. Every call now reaches CloudFront
 *   from Vercel, so without this the API's per-address limits ("Forgot your password?", the sign-in
 *   budget) would count every visitor as one. The API believes this header ONLY when the secret above
 *   is right (`backend/app/scale/rate_limit.py::trusted_viewer_address`).
 */

import {
  INSECURE_SESSION_COOKIE,
  INSECURE_SESSION_MARKER_COOKIE,
  SESSION_COOKIE,
  SESSION_MARKER_COOKIE,
  SESSION_ROTATED_HEADER
} from "@/lib/sessionCookie";

export const PROXY_SECRET_HEADER = "x-web-proxy-secret";
export const VIEWER_ADDRESS_HEADER = "x-viewer-address";

/** The API's header for the fresh session after a password change. Never reaches the page. */
export const API_SESSION_TOKEN_HEADER = "x-session-token";

/** A session token's lifetime when it cannot be read off the token (the API's default: 7 days). */
const FALLBACK_LIFETIME_SECONDS = 7 * 24 * 60 * 60;

/** Same sentence as `ApiUnconfiguredError` in `lib/api.ts`, which is what the page shows for a 503. */
const UNCONFIGURED_DETAIL =
  "The service isn't available right now, so you can't sign in or load records. " +
  "Please try again later, or contact your administrator if this continues.";

export const CROSS_SITE_DETAIL = "This request didn't come from this site, so it was refused.";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * Headers never copied from the browser's request onto the API call. `cookie` because the API reads
 * no cookie and the session cookie must not travel further than this site; `authorization` because
 * it is rebuilt from the cookie; the two proxy headers because only this code may set them; the rest
 * are about the connection to this site, not the one to the API.
 */
const DROPPED_REQUEST_HEADERS = [
  "cookie",
  "host",
  "connection",
  "keep-alive",
  "content-length",
  "transfer-encoding",
  "upgrade",
  "proxy-authorization",
  PROXY_SECRET_HEADER,
  VIEWER_ADDRESS_HEADER
];

/**
 * Headers never copied from the API's answer onto this site's. `fetch` has already decoded the body,
 * so the encoding and length describe bytes that no longer exist; the API sets no cookies; and the
 * session token header is exactly what this whole design keeps away from scripts.
 */
const DROPPED_RESPONSE_HEADERS = [
  "content-encoding",
  "content-length",
  "transfer-encoding",
  "connection",
  "keep-alive",
  "set-cookie",
  API_SESSION_TOKEN_HEADER
];

type Env = Record<string, string | undefined>;

function env(): Env {
  return process.env as Env;
}

/**
 * The API's origin (no `/api`), or null when this deployment was not told one.
 *
 * `API_ORIGIN` wins when set; otherwise the `NEXT_PUBLIC_API_URL` every deployment already has. A
 * developer checkout with neither talks to `http://localhost:8000`, as it always did; a production
 * build with neither answers 503 rather than quietly calling a laptop's port.
 */
export function apiOrigin(source: Env = env()): string | null {
  const configured = (source["API_ORIGIN"] ?? source["NEXT_PUBLIC_API_URL"] ?? "").trim().replace(/\/+$/, "");
  if (configured) return configured;
  return source["NODE_ENV"] === "production" ? null : "http://localhost:8000";
}

/** The proxy secret, or "" when this deployment has none (a laptop; a preview without the variable). */
export function proxySecret(source: Env = env()): string {
  // Bracket access so the bundler cannot inline a build-time value: the variable is a runtime secret.
  return (source["WEB_PROXY_SECRET"] ?? "").trim();
}

const LOOPBACK = /^(localhost|127\.0\.0\.1|\[::1\])$/i;

function hostOf(value: string | null): string {
  return (value ?? "").split(",")[0].trim().replace(/:\d+$/, "");
}

/**
 * Can the browser hold a `Secure` cookie for this page? True over HTTPS, and on localhost, which
 * browsers treat as secure even over plain HTTP.
 */
export function isSecureContext(request: Request): boolean {
  const forwardedProto = (request.headers.get("x-forwarded-proto") ?? "").split(",")[0].trim().toLowerCase();
  const url = new URL(request.url);
  const protocol = forwardedProto ? `${forwardedProto}:` : url.protocol;
  if (protocol === "https:") return true;
  const host = hostOf(request.headers.get("x-forwarded-host") ?? request.headers.get("host")) || url.hostname;
  return LOOPBACK.test(host);
}

export function cookieNames(secure: boolean): { session: string; marker: string } {
  return secure
    ? { session: SESSION_COOKIE, marker: SESSION_MARKER_COOKIE }
    : { session: INSECURE_SESSION_COOKIE, marker: INSECURE_SESSION_MARKER_COOKIE };
}

/** One cookie's value from a `Cookie` header, or null. */
export function readCookie(headers: Headers, name: string): string | null {
  const raw = headers.get("cookie");
  if (!raw) return null;
  for (const part of raw.split(";")) {
    const index = part.indexOf("=");
    if (index < 0) continue;
    if (part.slice(0, index).trim() !== name) continue;
    const value = part.slice(index + 1).trim();
    if (!value) return null;
    try {
      return decodeURIComponent(value);
    } catch {
      return value;
    }
  }
  return null;
}

/** The session token this request carries, under whichever of the two names this page uses. */
export function sessionTokenOf(request: Request): string | null {
  const names = cookieNames(isSecureContext(request));
  return readCookie(request.headers, names.session);
}

export function sessionMarkerOf(request: Request): string | null {
  const names = cookieNames(isSecureContext(request));
  return readCookie(request.headers, names.marker);
}

/**
 * CROSS-SITE REQUEST FORGERY: why a state-changing call is refused, or null when it may go.
 *
 * `SameSite=Lax` already keeps the session cookie off a POST that another site's form or script sends.
 * This is the second lock, and it does not depend on the browser's cookie rules at all: a write must
 * say it came from this very origin. `Sec-Fetch-Site` is set by the browser and cannot be forged by a
 * page; every browser this product supports sends it. Only `same-origin` passes — `same-site` would
 * admit any other `*.vercel.app` project, since `vercel.app` is a public suffix. A browser too old to
 * send it is judged on `Origin` instead, which must name this host exactly. A write with neither is
 * refused: nothing this site's own pages send looks like that.
 *
 * Reads (GET, HEAD) are not checked. They change nothing, and a cross-site page cannot read the answer.
 */
export function csrfRefusal(request: Request): string | null {
  if (SAFE_METHODS.has(request.method.toUpperCase())) return null;
  const site = (request.headers.get("sec-fetch-site") ?? "").trim().toLowerCase();
  if (site) return site === "same-origin" ? null : `sec-fetch-site=${site}`;
  const origin = request.headers.get("origin");
  if (!origin || origin === "null") return "no-origin";
  let originHost: string;
  try {
    originHost = new URL(origin).host.toLowerCase();
  } catch {
    return "bad-origin";
  }
  const ownHost = (request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? new URL(request.url).host)
    .split(",")[0]
    .trim()
    .toLowerCase();
  return originHost === ownHost ? null : "origin-mismatch";
}

/**
 * The visitor's address, as Vercel reports it. Vercel REPLACES `x-real-ip` and `x-forwarded-for` with
 * the address it saw, so neither can be typed by the visitor once the request has crossed Vercel's
 * edge. Null where there is none to report (a laptop).
 */
export function viewerAddress(headers: Headers): string | null {
  const real = (headers.get("x-real-ip") ?? "").trim();
  if (real) return real;
  const forwarded = (headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
  return forwarded || null;
}

/**
 * The headers the API call goes out with: the browser's own, minus the ones in
 * `DROPPED_REQUEST_HEADERS`, plus the bearer token and the two proxy headers.
 *
 * WITHOUT A SESSION COOKIE, an `Authorization` header the caller set itself is passed on untouched: a
 * script that holds a dataset token of its own may still use it through this site. With one, the
 * cookie wins.
 */
export function upstreamHeaders(request: Request, token: string | null, source: Env = env()): Headers {
  const headers = new Headers(request.headers);
  const callerAuthorization = headers.get("authorization");
  for (const name of DROPPED_REQUEST_HEADERS) headers.delete(name);
  headers.delete("authorization");
  if (token) headers.set("authorization", `Bearer ${token}`);
  else if (callerAuthorization) headers.set("authorization", callerAuthorization);
  const secret = proxySecret(source);
  if (secret) {
    headers.set(PROXY_SECRET_HEADER, secret);
    const viewer = viewerAddress(request.headers);
    if (viewer) headers.set(VIEWER_ADDRESS_HEADER, viewer);
  }
  return headers;
}

/** The API URL a `/api/...` path on this site stands for. */
export function upstreamUrl(request: Request, origin: string): string {
  const url = new URL(request.url);
  return `${origin}${url.pathname}${url.search}`;
}

/**
 * Seconds left on a session token, read off its `exp` claim. The token came straight from the API on
 * the answer being handled, so it is not verified here — only its expiry is read, to make the cookie
 * die when the token does. Falls back to the API's default lifetime if it cannot be read.
 */
export function tokenLifetimeSeconds(token: string, nowMs: number = Date.now()): number {
  const payload = token.split(".")[1];
  if (!payload) return FALLBACK_LIFETIME_SECONDS;
  try {
    const json = JSON.parse(Buffer.from(payload.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8")) as {
      exp?: unknown;
    };
    if (typeof json.exp !== "number" || !Number.isFinite(json.exp)) return FALLBACK_LIFETIME_SECONDS;
    return Math.max(0, Math.floor(json.exp - nowMs / 1000));
  } catch {
    return FALLBACK_LIFETIME_SECONDS;
  }
}

/** A fresh marker: 128 random bits, URL-safe. */
export function newMarker(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Buffer.from(bytes).toString("base64url");
}

function cookieLine(name: string, value: string, maxAge: number, secure: boolean, httpOnly: boolean): string {
  const parts = [`${name}=${value}`, "Path=/", `Max-Age=${maxAge}`, "SameSite=Lax"];
  if (secure) parts.push("Secure");
  if (httpOnly) parts.push("HttpOnly");
  return parts.join("; ");
}

/** The two `Set-Cookie` lines that start a session: the token (HttpOnly) and a new marker. */
export function sessionCookieLines(token: string, secure: boolean, nowMs: number = Date.now()): string[] {
  const names = cookieNames(secure);
  const maxAge = tokenLifetimeSeconds(token, nowMs);
  return [
    cookieLine(names.session, encodeURIComponent(token), maxAge, secure, true),
    cookieLine(names.marker, newMarker(), maxAge, secure, false)
  ];
}

/** The two `Set-Cookie` lines that end one. */
export function clearedCookieLines(secure: boolean): string[] {
  const names = cookieNames(secure);
  return [cookieLine(names.session, "", 0, secure, true), cookieLine(names.marker, "", 0, secure, false)];
}

function json(status: number, body: unknown, extra?: HeadersInit): Response {
  const headers = new Headers(extra);
  headers.set("content-type", "application/json");
  headers.set("cache-control", "no-store");
  return new Response(JSON.stringify(body), { status, headers });
}

export function refuseCrossSite(): Response {
  return json(403, { detail: CROSS_SITE_DETAIL });
}

export function refuseUnconfigured(): Response {
  return json(503, { detail: UNCONFIGURED_DETAIL });
}

/** The API's answer headers that may be shown to the page. */
export function downstreamHeaders(upstream: Headers): Headers {
  const headers = new Headers(upstream);
  for (const name of DROPPED_RESPONSE_HEADERS) headers.delete(name);
  headers.set("cache-control", "no-store");
  return headers;
}

/**
 * Send one session-route call on to the API and hand back its answer, body read in full. Only the
 * session routes come through here, and their bodies are small JSON; every other call is a rewrite.
 */
async function relay(request: Request, path: string, token: string | null, body?: BodyInit | null): Promise<Response | null> {
  const origin = apiOrigin();
  if (!origin) return null;
  const method = request.method.toUpperCase();
  const outgoing = upstreamHeaders(request, token);
  return fetch(`${origin}${path}`, {
    method,
    headers: outgoing,
    body: body !== undefined ? body : SAFE_METHODS.has(method) ? undefined : await request.arrayBuffer(),
    cache: "no-store",
    redirect: "manual"
  });
}

/**
 * `POST /api/auth/login`, `/api/auth/google` and `/api/auth/two-factor/verify`: the API's answer, with
 * the session token moved out of the body and into the cookie.
 *
 * Every other field reaches the page as the API wrote it — the account, a two-step challenge (which
 * is not a session: it opens nothing until a code is given), a refusal and its headers.
 */
export async function handleSignIn(request: Request, path: string): Promise<Response> {
  if (csrfRefusal(request)) return refuseCrossSite();
  const upstream = await relay(request, path, null);
  if (!upstream) return refuseUnconfigured();
  const headers = downstreamHeaders(upstream.headers);
  const text = await upstream.text();
  if (upstream.ok && (upstream.headers.get("content-type") ?? "").includes("application/json")) {
    let parsed: unknown = null;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
    if (parsed && typeof parsed === "object" && typeof (parsed as { accessToken?: unknown }).accessToken === "string") {
      const answer = { ...(parsed as Record<string, unknown>) };
      const token = answer.accessToken as string;
      delete answer.accessToken;
      delete answer.tokenType;
      for (const line of sessionCookieLines(token, isSecureContext(request))) headers.append("set-cookie", line);
      headers.set("content-type", "application/json");
      return new Response(JSON.stringify(answer), { status: upstream.status, headers });
    }
  }
  return new Response(text, { status: upstream.status, headers });
}

/**
 * `POST /api/auth/change-password`: sent with the cookie's token, and — because a password change
 * retires every session opened with the old password, this one included — the API answers with a
 * fresh token in `X-Session-Token`. That token goes into the cookie here, the header is removed, and
 * the page is told only that it happened (`X-Session-Rotated: 1`).
 */
export async function handleChangePassword(request: Request, path: string): Promise<Response> {
  if (csrfRefusal(request)) return refuseCrossSite();
  const upstream = await relay(request, path, sessionTokenOf(request));
  if (!upstream) return refuseUnconfigured();
  const headers = downstreamHeaders(upstream.headers);
  const fresh = (upstream.headers.get(API_SESSION_TOKEN_HEADER) ?? "").trim();
  if (upstream.ok && fresh) {
    for (const line of sessionCookieLines(fresh, isSecureContext(request))) headers.append("set-cookie", line);
    headers.set(SESSION_ROTATED_HEADER, "1");
  }
  return new Response(upstream.status === 204 ? null : await upstream.arrayBuffer(), { status: upstream.status, headers });
}

/**
 * `POST /api/auth/logout`: told to the API, and the cookies cleared WHATEVER it answers — a sign-out
 * that leaves the session behind because the API was unreachable would be no sign-out.
 */
export async function handleLogout(request: Request, path: string): Promise<Response> {
  if (csrfRefusal(request)) return refuseCrossSite();
  const secure = isSecureContext(request);
  let upstream: Response | null = null;
  try {
    upstream = await relay(request, path, sessionTokenOf(request));
  } catch {
    upstream = null;
  }
  const headers = upstream ? downstreamHeaders(upstream.headers) : new Headers({ "cache-control": "no-store" });
  for (const line of clearedCookieLines(secure)) headers.append("set-cookie", line);
  if (!upstream) return new Response(null, { status: 204, headers });
  return new Response(upstream.status === 204 ? null : await upstream.arrayBuffer(), { status: upstream.status, headers });
}

/**
 * `POST /api/session/adopt` — THE ONE-TIME MOVE for a browser signed in before this change.
 *
 * Such a browser still holds its token in `localStorage`. On its first load of the new site,
 * `lib/session.ts` posts that token here once and deletes it from storage. The token is checked with
 * the API (`GET /api/me`) before it becomes a cookie, so a dead or forged one is refused rather than
 * stored. The page is told `{adopted}` and never sees the token again.
 *
 * Login forgery (another site making a visitor adopt the attacker's session) is the reason for the
 * cross-site check here; it is a write like any other.
 */
export async function handleAdopt(request: Request): Promise<Response> {
  if (csrfRefusal(request)) return refuseCrossSite();
  let token = "";
  try {
    const body = (await request.json()) as { token?: unknown };
    token = typeof body.token === "string" ? body.token.trim() : "";
  } catch {
    token = "";
  }
  if (!token || token.length > 4096) return json(400, { detail: "No session to move.", adopted: false });
  const origin = apiOrigin();
  if (!origin) return refuseUnconfigured();
  const probe = new Request(request.url, { method: "GET", headers: request.headers });
  const upstream = await fetch(`${origin}/api/me`, {
    method: "GET",
    headers: upstreamHeaders(probe, token),
    cache: "no-store",
    redirect: "manual"
  });
  // A password-change gate is a 401 on most routes but `/me` is on its allow-list, so a 200 here is
  // a live session either way.
  if (upstream.ok) {
    const headers = new Headers({ "cache-control": "no-store", "content-type": "application/json" });
    for (const line of sessionCookieLines(token, isSecureContext(request))) headers.append("set-cookie", line);
    return new Response(JSON.stringify({ adopted: true }), { status: 200, headers });
  }
  if (upstream.status === 401 || upstream.status === 403) return json(401, { detail: "That session has ended.", adopted: false });
  // No `adopted` field: the API could not be asked, so the page keeps the token for its next load.
  return json(502, { detail: UNCONFIGURED_DETAIL });
}

/**
 * `POST /api/session/end` — the page has learned the session is over (the API refused its token) and
 * the cookie must go too, since no script can delete an HttpOnly cookie.
 *
 * ONLY THE SESSION THE PAGE MEANT. The body names the marker the page saw when it decided; if the
 * cookie now carries a different one, a sign-in or a password change has replaced the session since,
 * and the new one is left alone. The same race `sessionReplacedSince` guards against in the page.
 */
export async function handleEnd(request: Request): Promise<Response> {
  if (csrfRefusal(request)) return refuseCrossSite();
  let marker = "";
  try {
    const body = (await request.json()) as { marker?: unknown };
    marker = typeof body.marker === "string" ? body.marker : "";
  } catch {
    marker = "";
  }
  const current = sessionMarkerOf(request);
  const headers = new Headers({ "cache-control": "no-store" });
  if (!current || current === marker) {
    for (const line of clearedCookieLines(isSecureContext(request))) headers.append("set-cookie", line);
  }
  return new Response(null, { status: 204, headers });
}
