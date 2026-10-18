import { expect, test } from "@playwright/test";
import { NextRequest } from "next/server";

import { PROXY_SECRET_HEADER, VIEWER_ADDRESS_HEADER } from "@/lib/server/apiProxy";
import { SESSION_COOKIE } from "@/lib/sessionCookie";

import { proxy } from "../proxy";

/**
 * `proxy.ts` ITSELF (2026-10-18): the code that carries every `/api/*` call, driven through Next's
 * own `NextRequest`/`NextResponse`.
 *
 * `cookie-session-proxy-unit.spec.ts` drives the helpers in `lib/server/apiProxy.ts`. This spec reads
 * what the proxy actually hands back to Next: the rewrite target (`x-middleware-rewrite`) and the
 * request headers the forwarded call is sent with (`x-middleware-override-headers` names every header
 * the API receives; anything not named there is removed before the call leaves). That is the contract
 * `next start` and Vercel's Routing Middleware both honour, so a header that is not in the override
 * list does not reach the API on either. `cookie-session-smoke.spec.ts` runs the same path against a
 * real server.
 */

const SITE = "https://field-repository.vercel.app";
const savedEnv = { ...process.env };

test.beforeEach(() => {
  process.env.NEXT_PUBLIC_API_URL = "https://api.example.test";
  process.env.WEB_PROXY_SECRET = "s3cret-for-tests-only";
  delete process.env.API_ORIGIN;
});

test.afterEach(() => {
  for (const key of ["NEXT_PUBLIC_API_URL", "WEB_PROXY_SECRET", "API_ORIGIN"]) {
    if (savedEnv[key] === undefined) delete process.env[key];
    else process.env[key] = savedEnv[key];
  }
});

function browserCall(path: string, init: { method?: string; headers?: Record<string, string> } = {}): NextRequest {
  return new NextRequest(`${SITE}${path}`, {
    method: init.method ?? "GET",
    headers: {
      host: "field-repository.vercel.app",
      "x-forwarded-proto": "https",
      "sec-fetch-site": "same-origin",
      origin: SITE,
      cookie: `${SESSION_COOKIE}=tok-123; other=1`,
      "x-real-ip": "203.0.113.7",
      "x-forwarded-for": "203.0.113.7",
      "x-vercel-oidc-token": "eyJ.oidc.credential",
      "x-vercel-id": "bom1::abc",
      ...init.headers
    }
  });
}

/** The headers the forwarded call leaves with, as Next will build them from the proxy's answer. */
function forwarded(response: Response): Map<string, string> {
  const names = (response.headers.get("x-middleware-override-headers") ?? "").split(",").filter(Boolean);
  const out = new Map<string, string>();
  for (const name of names) out.set(name, response.headers.get(`x-middleware-request-${name}`) ?? "");
  return out;
}

test("a call is rewritten to the API with the cookie turned into a bearer, and nothing of Vercel's", () => {
  const response = proxy(browserCall("/api/artisans?page=2", { headers: { range: "bytes=0-9" } }));
  expect(response.headers.get("x-middleware-rewrite")).toBe("https://api.example.test/api/artisans?page=2");
  expect(response.headers.get("x-vercel-enable-rewrite-caching")).toBe("0");
  const sent = forwarded(response);
  expect(sent.get("authorization")).toBe("Bearer tok-123");
  expect(sent.has("cookie"), "the cookie stops at this site").toBe(false);
  expect(sent.has("x-vercel-oidc-token"), "Vercel's OIDC token never leaves Vercel").toBe(false);
  expect(sent.has("x-vercel-id")).toBe(false);
  expect(sent.has("host")).toBe(false);
  expect(sent.get(PROXY_SECRET_HEADER)).toBe("s3cret-for-tests-only");
  expect(sent.get(VIEWER_ADDRESS_HEADER)).toBe("203.0.113.7");
  expect(sent.get("range")).toBe("bytes=0-9");
});

test("a cross-site write is answered 403 by the proxy and is not rewritten", async () => {
  const response = proxy(
    browserCall("/api/questionnaires/upload", { method: "POST", headers: { "sec-fetch-site": "cross-site", origin: "https://evil.example" } })
  );
  expect(response.status).toBe(403);
  expect(response.headers.get("x-middleware-rewrite")).toBeNull();
});

test("the session routes are left to their route handlers", () => {
  for (const path of [
    "/api/auth/login",
    "/api/auth/google",
    "/api/auth/two-factor/verify",
    "/api/auth/change-password",
    "/api/auth/logout",
    "/api/session/adopt",
    "/api/session/end"
  ]) {
    const response = proxy(browserCall(path, { method: "POST" }));
    expect(response.headers.get("x-middleware-rewrite"), path).toBeNull();
    expect(response.headers.get("x-middleware-next"), path).toBe("1");
  }
});

test("without the secret, no visitor address is named", () => {
  delete process.env.WEB_PROXY_SECRET;
  const sent = forwarded(proxy(browserCall("/api/me", { headers: { [VIEWER_ADDRESS_HEADER]: "6.6.6.6" } })));
  expect(sent.has(PROXY_SECRET_HEADER)).toBe(false);
  expect(sent.has(VIEWER_ADDRESS_HEADER)).toBe(false);
});
