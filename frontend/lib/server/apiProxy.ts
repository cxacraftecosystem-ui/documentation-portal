import {
  INSECURE_SESSION_COOKIE,
  INSECURE_SESSION_MARKER_COOKIE,
  SESSION_COOKIE,
  SESSION_MARKER_COOKIE,
  SESSION_ROTATED_HEADER
} from "@/lib/sessionCookie";

export const PROXY_SECRET_HEADER = "x-web-proxy-secret";
export const VIEWER_ADDRESS_HEADER = "x-viewer-address";

export const API_SESSION_TOKEN_HEADER = "x-session-token";

const FALLBACK_LIFETIME_SECONDS = 7 * 24 * 60 * 60;

const UNCONFIGURED_DETAIL =
  "The service isn't available right now, so you can't sign in or load records. " +
  "Please try again later, or contact your administrator if this continues.";

export const CROSS_SITE_DETAIL = "This request didn't come from this site, so it was refused.";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

const FORWARDED_REQUEST_HEADERS = new Set([
  "accept",
  "accept-encoding",
  "accept-language",
  "content-type",
  "range",
  "if-range",
  "if-match",
  "if-none-match",
  "if-modified-since",
  "if-unmodified-since",
  "user-agent",
  "origin",
  "x-forwarded-for",
  "idempotency-key",
  "x-client-app",
  "x-client-time",
  "x-app-version",
  "x-app-version-code",
  "x-app-platform",
  "x-confirm-provider",
  "x-confirm-sign-in",
  "x-sign-in-proof"
]);

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

export function apiOrigin(source: Env = env()): string | null {
  const configured = (source["API_ORIGIN"] ?? source["NEXT_PUBLIC_API_URL"] ?? "").trim().replace(/\/+$/, "");
  if (configured) return configured;
  return source["NODE_ENV"] === "production" ? null : "http://localhost:8000";
}

export function proxySecret(source: Env = env()): string {
  return (source["WEB_PROXY_SECRET"] ?? "").trim();
}

const LOOPBACK = /^(localhost|127\.0\.0\.1|\[::1\])$/i;

function hostOf(value: string | null): string {
  return (value ?? "").split(",")[0].trim().replace(/:\d+$/, "");
}

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

export function sessionTokenOf(request: Request): string | null {
  const names = cookieNames(isSecureContext(request));
  return readCookie(request.headers, names.session);
}

export function sessionMarkerOf(request: Request): string | null {
  const names = cookieNames(isSecureContext(request));
  return readCookie(request.headers, names.marker);
}

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

export function viewerAddress(headers: Headers): string | null {
  const real = (headers.get("x-real-ip") ?? "").trim();
  if (real) return real;
  const forwarded = (headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
  return forwarded || null;
}

export function upstreamHeaders(request: Request, token: string | null, source: Env = env()): Headers {
  const headers = new Headers();
  request.headers.forEach((value, name) => {
    if (FORWARDED_REQUEST_HEADERS.has(name.toLowerCase())) headers.set(name, value);
  });
  const callerAuthorization = request.headers.get("authorization");
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

export function upstreamUrl(request: Request, origin: string): string {
  const url = new URL(request.url);
  return `${origin}${url.pathname}${url.search}`;
}

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

export function sessionCookieLines(token: string, secure: boolean, nowMs: number = Date.now()): string[] {
  const names = cookieNames(secure);
  const maxAge = tokenLifetimeSeconds(token, nowMs);
  return [
    cookieLine(names.session, encodeURIComponent(token), maxAge, secure, true),
    cookieLine(names.marker, newMarker(), maxAge, secure, false)
  ];
}

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

export function downstreamHeaders(upstream: Headers): Headers {
  const headers = new Headers(upstream);
  for (const name of DROPPED_RESPONSE_HEADERS) headers.delete(name);
  headers.set("cache-control", "no-store");
  return headers;
}

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
  if (upstream.ok) {
    const headers = new Headers({ "cache-control": "no-store", "content-type": "application/json" });
    for (const line of sessionCookieLines(token, isSecureContext(request))) headers.append("set-cookie", line);
    return new Response(JSON.stringify({ adopted: true }), { status: 200, headers });
  }
  if (upstream.status === 401 || upstream.status === 403) return json(401, { detail: "That session has ended.", adopted: false });
  return json(502, { detail: UNCONFIGURED_DETAIL });
}

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
