import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CROSS_SITE_DETAIL,
  PROXY_SECRET_HEADER,
  VIEWER_ADDRESS_HEADER,
  apiOrigin,
  csrfRefusal,
  handleAdopt,
  handleChangePassword,
  handleEnd,
  handleLogout,
  handleSignIn,
  sessionCookieLines,
  tokenLifetimeSeconds,
  upstreamHeaders,
  viewerAddress
} from "@/lib/server/apiProxy";
import { getSessionMarker, migrateStoredToken, resetSessionStateForTests } from "@/lib/session";
import { LEGACY_TOKEN_STORAGE_KEY, SESSION_COOKIE, SESSION_MARKER_COOKIE, SESSION_ROTATED_HEADER } from "@/lib/sessionCookie";

import { installSessionCookie, removeSessionCookie } from "./support/fakeSessionCookie";

/**
 * THE COOKIE SESSION'S SERVER HALF (2026-10-17), driven for real in Node.
 *
 * `lib/server/apiProxy.ts` is plain functions over the web-standard Request/Response, so every rule
 * the design rests on is exercised here against a stubbed API rather than read off the source: the
 * cookie's flags, the token never reaching the page, the cross-site refusal, the renewal after a
 * password change, the sign-out, the one-time move out of localStorage, and the visitor's address
 * travelling to the API only beside the proxy secret.
 */

const SITE = "https://field-repository.vercel.app";
const realFetch = globalThis.fetch;
const savedEnv = { ...process.env };

/** A JWT-shaped token whose `exp` is `secondsFromNow` ahead. Only the payload is read. */
function tokenExpiringIn(secondsFromNow: number, label = "t"): string {
  const payload = Buffer.from(JSON.stringify({ sub: label, exp: Math.floor(Date.now() / 1000) + secondsFromNow })).toString("base64url");
  return `header.${payload}.signature-${label}`;
}

type Sent = { url: string; method: string; headers: Headers; body: string };

/** Stub the API: record every call, answer with `reply`. */
function stubApi(reply: (sent: Sent) => Response): Sent[] {
  const sent: Sent[] = [];
  (globalThis as Record<string, unknown>).fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const body = init.body instanceof ArrayBuffer ? Buffer.from(init.body).toString("utf8") : init.body ? String(init.body) : "";
    const call = { url: String(input), method: (init.method ?? "GET").toUpperCase(), headers: new Headers(init.headers), body };
    sent.push(call);
    return reply(call);
  };
  return sent;
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

/** A same-origin browser request to this site. */
function siteRequest(path: string, init: { method?: string; body?: unknown; cookie?: string; headers?: Record<string, string> } = {}) {
  const headers = new Headers({
    host: "field-repository.vercel.app",
    "x-forwarded-proto": "https",
    "sec-fetch-site": "same-origin",
    origin: SITE,
    "content-type": "application/json",
    ...init.headers
  });
  if (init.cookie) headers.set("cookie", init.cookie);
  return new Request(`${SITE}${path}`, {
    method: init.method ?? "POST",
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body)
  });
}

function setCookies(response: Response): string[] {
  return response.headers.getSetCookie();
}

test.beforeEach(() => {
  process.env.NEXT_PUBLIC_API_URL = "https://api.example.test";
  process.env.WEB_PROXY_SECRET = "s3cret-for-tests-only";
  delete process.env.API_ORIGIN;
});

test.afterEach(() => {
  (globalThis as Record<string, unknown>).fetch = realFetch;
  for (const key of ["NEXT_PUBLIC_API_URL", "WEB_PROXY_SECRET", "API_ORIGIN"]) {
    if (savedEnv[key] === undefined) delete process.env[key];
    else process.env[key] = savedEnv[key];
  }
  delete (globalThis as Record<string, unknown>).window;
  removeSessionCookie();
});

/* ── 1. Sign-in: the token goes into an HttpOnly cookie and never reaches the page ─────────── */

test("a sign-in answer becomes a __Host- HttpOnly Secure Lax cookie, and the token leaves the body", async () => {
  const token = tokenExpiringIn(3600);
  const sent = stubApi(() => json(200, { accessToken: token, tokenType: "bearer", user: { id: "u1", email: "a@b.c" } }));
  const response = await handleSignIn(siteRequest("/api/auth/login", { body: { email: "a@b.c", password: "pw" } }), "/api/auth/login");

  expect(response.status).toBe(200);
  const body = await response.json();
  expect(body, "the account reaches the page").toEqual({ user: { id: "u1", email: "a@b.c" } });
  expect(JSON.stringify(body), "and the token does not").not.toContain(token);

  const [session, marker] = setCookies(response);
  expect(session.startsWith(`${SESSION_COOKIE}=`)).toBe(true);
  expect(session).toContain("HttpOnly");
  expect(session).toContain("Secure");
  expect(session).toContain("SameSite=Lax");
  expect(session).toContain("Path=/");
  expect(session, "no Domain: the cookie is this host's alone").not.toMatch(/domain=/i);
  const maxAge = Number(/Max-Age=(\d+)/.exec(session)?.[1]);
  expect(maxAge, "Max-Age is what is left of the token's own life").toBeGreaterThan(3590);
  expect(maxAge).toBeLessThanOrEqual(3600);
  expect(decodeURIComponent(session.split(";")[0].split("=").slice(1).join("="))).toBe(token);

  expect(marker.startsWith(`${SESSION_MARKER_COOKIE}=`)).toBe(true);
  expect(marker, "the marker is for scripts to read").not.toContain("HttpOnly");
  expect(marker).not.toContain(token);
  expect(Number(/Max-Age=(\d+)/.exec(marker)?.[1])).toBe(maxAge);

  // The API was asked exactly as before, with the proxy's two headers and no browser cookie.
  expect(sent).toHaveLength(1);
  expect(sent[0].url).toBe("https://api.example.test/api/auth/login");
  expect(JSON.parse(sent[0].body)).toEqual({ email: "a@b.c", password: "pw" });
  expect(sent[0].headers.get("cookie")).toBeNull();
  expect(sent[0].headers.get(PROXY_SECRET_HEADER)).toBe("s3cret-for-tests-only");
});

test("a two-step challenge, a refusal and its headers pass through untouched, with no cookie", async () => {
  stubApi(() => json(200, { two_factor_required: true, challenge_token: "challenge-1" }));
  const challenge = await handleSignIn(siteRequest("/api/auth/login", { body: {} }), "/api/auth/login");
  expect(await challenge.json()).toEqual({ two_factor_required: true, challenge_token: "challenge-1" });
  expect(setCookies(challenge)).toEqual([]);

  stubApi(() => json(403, { detail: "Your access request is waiting for approval." }, { "X-Access-Status": "PENDING" }));
  const refused = await handleSignIn(siteRequest("/api/auth/login", { body: {} }), "/api/auth/login");
  expect(refused.status).toBe(403);
  expect(refused.headers.get("x-access-status")).toBe("PENDING");
  expect(setCookies(refused)).toEqual([]);
});

test("a plain-HTTP page that is not localhost gets the same cookies without the __Host- prefix or Secure", () => {
  const [session, marker] = sessionCookieLines(tokenExpiringIn(60), false);
  expect(session.startsWith("session=")).toBe(true);
  expect(session).toContain("HttpOnly");
  expect(session).not.toContain("Secure");
  expect(marker.startsWith("session-marker=")).toBe(true);
});

test("the cookie dies with the token, and an unreadable token gets the API's default week", () => {
  expect(tokenLifetimeSeconds(tokenExpiringIn(120))).toBeLessThanOrEqual(120);
  expect(tokenLifetimeSeconds(tokenExpiringIn(-50)), "already expired: gone at once").toBe(0);
  expect(tokenLifetimeSeconds("not-a-jwt")).toBe(7 * 24 * 60 * 60);
});

/* ── 2. Cross-site requests are refused before the API is asked ────────────────────────────── */

test("a write from another site is refused with 403 and never reaches the API", async () => {
  const sent = stubApi(() => json(200, { ok: true }));
  const cases: Array<Record<string, string>> = [
    { "sec-fetch-site": "cross-site", origin: SITE },
    { "sec-fetch-site": "same-site", origin: SITE }, // another *.vercel.app project: vercel.app is a public suffix
    { "sec-fetch-site": "", origin: "https://evil.example" },
    { "sec-fetch-site": "", origin: "" }
  ];
  for (const headers of cases) {
    const request = siteRequest("/api/auth/change-password", { body: { newPassword: "x" }, cookie: `${SESSION_COOKIE}=t`, headers });
    if (!headers.origin) request.headers.delete("origin");
    if (!headers["sec-fetch-site"]) request.headers.delete("sec-fetch-site");
    const response = await handleChangePassword(request, "/api/auth/change-password");
    expect(response.status, JSON.stringify(headers)).toBe(403);
    expect((await response.json()).detail).toBe(CROSS_SITE_DETAIL);
  }
  expect(sent, "nothing was sent on").toHaveLength(0);
});

test("same-origin writes pass, an old browser is judged on Origin, and reads are never checked", () => {
  expect(csrfRefusal(siteRequest("/api/artisans"))).toBeNull();
  const old = siteRequest("/api/artisans");
  old.headers.delete("sec-fetch-site");
  expect(csrfRefusal(old), "no Sec-Fetch-Site, Origin is this host").toBeNull();
  const read = siteRequest("/api/artisans", { method: "GET", headers: { "sec-fetch-site": "cross-site" } });
  expect(csrfRefusal(read)).toBeNull();
});

test("every session route refuses a cross-site write", async () => {
  stubApi(() => json(200, {}));
  const cross = (path: string) => siteRequest(path, { body: { token: "t", marker: "m" }, headers: { "sec-fetch-site": "cross-site" } });
  expect((await handleSignIn(cross("/api/auth/login"), "/api/auth/login")).status).toBe(403);
  expect((await handleSignIn(cross("/api/auth/google"), "/api/auth/google")).status).toBe(403);
  expect((await handleLogout(cross("/api/auth/logout"), "/api/auth/logout")).status).toBe(403);
  expect((await handleAdopt(cross("/api/session/adopt"))).status, "a forged adoption is a login CSRF").toBe(403);
  expect((await handleEnd(cross("/api/session/end"))).status).toBe(403);
});

/* ── 3. The password change renews the session in the cookie ───────────────────────────────── */

test("the fresh token from X-Session-Token replaces the cookie, and the page sees only X-Session-Rotated", async () => {
  const old = tokenExpiringIn(600, "old");
  const fresh = tokenExpiringIn(7200, "fresh");
  const sent = stubApi(() => json(200, { ok: true }, { "X-Session-Token": fresh }));
  const response = await handleChangePassword(
    siteRequest("/api/auth/change-password", { body: { currentPassword: "a", newPassword: "b" }, cookie: `${SESSION_COOKIE}=${old}` }),
    "/api/auth/change-password"
  );
  expect(response.status).toBe(200);
  expect(await response.json(), "the body is the API's, for the handsets").toEqual({ ok: true });
  expect(response.headers.get("x-session-token"), "the token itself never reaches the page").toBeNull();
  expect(response.headers.get(SESSION_ROTATED_HEADER)).toBe("1");
  const [session] = setCookies(response);
  expect(decodeURIComponent(session.split(";")[0].split("=").slice(1).join("="))).toBe(fresh);
  expect(sent[0].headers.get("authorization"), "the change is sent with the OLD session").toBe(`Bearer ${old}`);
});

test("a refused change keeps the cookie it had", async () => {
  stubApi(() => json(400, { detail: "Your current password is wrong." }));
  const response = await handleChangePassword(
    siteRequest("/api/auth/change-password", { body: {}, cookie: `${SESSION_COOKIE}=t` }),
    "/api/auth/change-password"
  );
  expect(response.status).toBe(400);
  expect(setCookies(response)).toEqual([]);
  expect(response.headers.get(SESSION_ROTATED_HEADER)).toBeNull();
});

/* ── 4. Sign-out clears both cookies, whatever the API says ────────────────────────────────── */

test("sign-out tells the API with the session's token and clears both cookies, even if the API is down", async () => {
  const sent = stubApi(() => json(200, { ok: true }));
  const response = await handleLogout(siteRequest("/api/auth/logout", { cookie: `${SESSION_COOKIE}=tok; ${SESSION_MARKER_COOKIE}=m` }), "/api/auth/logout");
  expect(sent[0].headers.get("authorization")).toBe("Bearer tok");
  const cleared = setCookies(response);
  expect(cleared).toHaveLength(2);
  for (const line of cleared) expect(line).toContain("Max-Age=0");

  (globalThis as Record<string, unknown>).fetch = async () => {
    throw new TypeError("fetch failed");
  };
  const offline = await handleLogout(siteRequest("/api/auth/logout", { cookie: `${SESSION_COOKIE}=tok` }), "/api/auth/logout");
  expect(offline.status).toBe(204);
  expect(setCookies(offline).every((line) => line.includes("Max-Age=0"))).toBe(true);
});

test("ending a refused session clears the cookie only if it is still the session the page meant", async () => {
  const same = await handleEnd(siteRequest("/api/session/end", { body: { marker: "m1" }, cookie: `${SESSION_COOKIE}=t; ${SESSION_MARKER_COOKIE}=m1` }));
  expect(setCookies(same)).toHaveLength(2);
  // A sign-in or a password change replaced it in the meantime: the new session survives.
  const replaced = await handleEnd(siteRequest("/api/session/end", { body: { marker: "m1" }, cookie: `${SESSION_COOKIE}=t2; ${SESSION_MARKER_COOKIE}=m2` }));
  expect(setCookies(replaced)).toEqual([]);
});

/* ── 5. The one-time move out of localStorage ──────────────────────────────────────────────── */

test("a stored token is checked with the API before it becomes a cookie; a dead one is refused", async () => {
  const token = tokenExpiringIn(3000);
  const sent = stubApi((call) => (call.headers.get("authorization") === `Bearer ${token}` ? json(200, { id: "u1" }) : json(401, {})));
  const adopted = await handleAdopt(siteRequest("/api/session/adopt", { body: { token } }));
  expect(adopted.status).toBe(200);
  expect(await adopted.json()).toEqual({ adopted: true });
  expect(sent[0].url).toBe("https://api.example.test/api/me");
  expect(setCookies(adopted)[0]).toContain("HttpOnly");

  const dead = await handleAdopt(siteRequest("/api/session/adopt", { body: { token: "a-dead-one" } }));
  expect(dead.status).toBe(401);
  expect(setCookies(dead)).toEqual([]);
});

function installStorage(token: string | null) {
  const store = new Map<string, string>();
  if (token) store.set(LEGACY_TOKEN_STORAGE_KEY, token);
  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key)
    }
  };
  return store;
}

test("the page posts its stored token once, then deletes it — and keeps it only when there was no answer", async () => {
  installSessionCookie(null);
  let store = installStorage("legacy-token");
  const posted: string[] = [];
  (globalThis as Record<string, unknown>).fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    posted.push(`${String(input)} ${String(init.body)}`);
    return json(200, { adopted: true });
  };
  await migrateStoredToken();
  await migrateStoredToken();
  expect(posted, "once per page load").toEqual(['/api/session/adopt {"token":"legacy-token"}']);
  expect(store.has(LEGACY_TOKEN_STORAGE_KEY), "and gone from storage").toBe(false);

  // A dead token: refused, and still deleted.
  resetSessionStateForTests();
  store = installStorage("dead-token");
  (globalThis as Record<string, unknown>).fetch = async () => json(401, { adopted: false });
  await migrateStoredToken();
  expect(store.has(LEGACY_TOKEN_STORAGE_KEY)).toBe(false);

  // The API could not be asked (the site answers with no verdict): kept.
  resetSessionStateForTests();
  store = installStorage("unverified-token");
  (globalThis as Record<string, unknown>).fetch = async () => json(502, { detail: "The service isn't available right now." });
  await migrateStoredToken();
  expect(store.get(LEGACY_TOKEN_STORAGE_KEY)).toBe("unverified-token");

  // Offline at the first load: kept for the next one, so nobody is signed out by the release.
  resetSessionStateForTests();
  store = installStorage("offline-token");
  (globalThis as Record<string, unknown>).fetch = async () => {
    throw new TypeError("Failed to fetch");
  };
  await migrateStoredToken();
  expect(store.get(LEGACY_TOKEN_STORAGE_KEY)).toBe("offline-token");
});

test("a browser that already has a cookie session just drops the stored token, asking nothing", async () => {
  installSessionCookie("a-live-marker");
  const store = installStorage("legacy-token");
  let asked = 0;
  (globalThis as Record<string, unknown>).fetch = async () => {
    asked += 1;
    return json(200, {});
  };
  await migrateStoredToken();
  expect(asked).toBe(0);
  expect(store.has(LEGACY_TOKEN_STORAGE_KEY)).toBe(false);
  expect(getSessionMarker()).toBe("a-live-marker");
});

/* ── 6. The visitor's address reaches the API, and only beside the secret ──────────────────── */

test("the viewer's address travels in X-Viewer-Address beside the proxy secret, never on its own", () => {
  const request = siteRequest("/api/auth/forgot-password", {
    headers: { "x-real-ip": "203.0.113.7", "x-forwarded-for": "203.0.113.7", [VIEWER_ADDRESS_HEADER]: "6.6.6.6" }
  });
  const withSecret = upstreamHeaders(request, null);
  expect(withSecret.get(VIEWER_ADDRESS_HEADER), "Vercel's address, not one the caller typed").toBe("203.0.113.7");
  expect(withSecret.get(PROXY_SECRET_HEADER)).toBe("s3cret-for-tests-only");

  delete process.env.WEB_PROXY_SECRET;
  const without = upstreamHeaders(request, null);
  expect(without.get(PROXY_SECRET_HEADER)).toBeNull();
  expect(without.get(VIEWER_ADDRESS_HEADER), "a caller-typed address is dropped, not passed on").toBeNull();

  expect(viewerAddress(new Headers({ "x-forwarded-for": "198.51.100.2, 10.0.0.1" }))).toBe("198.51.100.2");
  expect(viewerAddress(new Headers())).toBeNull();
});

test("the cookie becomes the bearer; a caller's own Authorization is kept only when there is no cookie", () => {
  const withCookie = siteRequest("/api/artisans", { cookie: `${SESSION_COOKIE}=from-cookie`, headers: { authorization: "Bearer typed" } });
  expect(upstreamHeaders(withCookie, "from-cookie").get("authorization")).toBe("Bearer from-cookie");
  expect(upstreamHeaders(withCookie, "from-cookie").get("cookie"), "the cookie stops at this site").toBeNull();
  const datasetScript = siteRequest("/api/datasets", { headers: { authorization: "Bearer dataset-token" } });
  expect(upstreamHeaders(datasetScript, null).get("authorization")).toBe("Bearer dataset-token");
});

test("a production build with no API address answers 503 instead of calling localhost", async () => {
  expect(apiOrigin({ NODE_ENV: "production" })).toBeNull();
  expect(apiOrigin({ NODE_ENV: "development" })).toBe("http://localhost:8000");
  expect(apiOrigin({ NEXT_PUBLIC_API_URL: "https://x.cloudfront.net/" })).toBe("https://x.cloudfront.net");
  expect(apiOrigin({ API_ORIGIN: "https://a", NEXT_PUBLIC_API_URL: "https://b" })).toBe("https://a");
});

/* ── 7. Nothing in the page can reach the token ────────────────────────────────────────────── */

test("no client code stores, reads or sends the session token", () => {
  const FRONTEND = join(__dirname, "..");
  const files = [
    "lib/api.ts",
    "lib/session.ts",
    "components/AuthProvider.tsx",
    "components/FirstPasswordGate.tsx",
    "components/settings/ChangePasswordCard.tsx",
    "components/DownloadCsvButton.tsx",
    "components/questionnaires/workbookApi.ts",
    "lib/media.ts",
    "app/(protected)/data/page.tsx"
  ];
  for (const file of files) {
    const source = readFileSync(join(FRONTEND, file), "utf8");
    expect(source, `${file} sets no bearer header`).not.toMatch(/Authorization["']?\s*[:,]\s*`Bearer/);
    expect(source, `${file} writes no token to storage`).not.toMatch(/localStorage\.setItem\(\s*["']field_repo_token/);
  }
  const session = readFileSync(join(FRONTEND, "lib", "session.ts"), "utf8");
  expect(session, "the only storage read is the one-time migration, which deletes it").toContain("dropLegacyToken()");
  const proxy = readFileSync(join(FRONTEND, "proxy.ts"), "utf8");
  expect(proxy, "the proxy never reads a body, so Vercel can skip sending it").not.toMatch(/request\.(json|text|arrayBuffer|formData|body)/);
  const vercel = JSON.parse(readFileSync(join(FRONTEND, "vercel.json"), "utf8")) as { skipMiddlewareRequestBody?: boolean };
  expect(vercel.skipMiddlewareRequestBody).toBe(true);
});
