/**
 * "CONTINUE WITH MICROSOFT" AND "CONTINUE WITH YAHOO" — the browser's half, pinned.
 *
 * Pure functions from `lib/oidcSignIn.ts`, plus source assertions on the login page and the callback
 * route for the judgements that live in JSX (there is no React renderer in devDependencies). What is
 * pinned, and why each would ship unnoticed:
 *
 *   1. A provider with no client ID has NO button — the "Coming soon" badge and toast are gone and
 *      must not come back as a disabled control.
 *   2. The authorization request carries PKCE S256 and the DIGEST of the nonce; the raw nonce and the
 *      verifier go only to our own sign-in route.
 *   3. A callback this tab did not start (wrong `state`), or one too old, is never completed.
 *   4. The callback hands the code back in the FRAGMENT, and hands an `app.` state to the Android app.
 *
 * ⚠ Source assertions are line-ending agnostic: `\s` and `[\s\S]`, never `\n`.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  ANDROID_SIGN_IN_SCHEME,
  PENDING_LIFETIME_MS,
  authorizeUrl,
  beginSignIn,
  callbackErrorMessage,
  callbackTarget,
  configuredOidcProviders,
  loginBody,
  microsoftTenant,
  parseCallbackFragment,
  sha256Base64Url,
  takePendingSignIn,
  type PendingSignIn
} from "@/lib/oidcSignIn";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const LOGIN = read("app", "login", "page.tsx");
const CALLBACK_ROUTE = read("app", "login", "callback", "route.ts");

class MemoryStorage {
  values = new Map<string, string>();
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
}

/* 1. Shown only when configured */

test("no client ID, no provider", () => {
  expect(configuredOidcProviders({})).toEqual([]);
  expect(configuredOidcProviders({ microsoftClientId: "  ", yahooClientId: "" })).toEqual([]);
});

test("each configured provider is offered, Microsoft first", () => {
  const providers = configuredOidcProviders({ microsoftClientId: "ms-id", yahooClientId: "y-id" });
  expect(providers.map((p) => p.id)).toEqual(["MICROSOFT", "YAHOO"]);
  expect(providers[0].authorizeEndpoint).toBe("https://login.microsoftonline.com/common/oauth2/v2.0/authorize");
  expect(providers[1].authorizeEndpoint).toBe("https://api.login.yahoo.com/oauth2/request_auth");
});

test("the Microsoft tenant is a keyword or a tenant ID, and anything else hides the button", () => {
  expect(microsoftTenant(undefined)).toBe("common");
  expect(microsoftTenant("Organizations")).toBe("organizations");
  expect(microsoftTenant("72F988BF-86F1-41AF-91AB-2D7CD011DB47")).toBe("72f988bf-86f1-41af-91ab-2d7cd011db47");
  expect(microsoftTenant("contoso.onmicrosoft.com")).toBeNull();
  expect(configuredOidcProviders({ microsoftClientId: "ms-id", microsoftTenant: "contoso.com" })).toEqual([]);
});

test("the login page has no coming-soon copy and draws only configured providers", () => {
  expect(LOGIN).not.toContain("comingSoon(");
  expect(LOGIN).not.toMatch(/coming soon/i);
  expect(LOGIN).not.toContain("ComingSoonBadge");
  expect(LOGIN).not.toContain("no endpoint behind them");
  expect(LOGIN).toMatch(/oidcProviders\.map\(\(provider\) =>/);
  expect(LOGIN).toContain("configuredOidcProviders()");
});

/* 2. The authorization request */

test("the request carries PKCE S256 and the nonce's digest, never the raw values", async () => {
  const storage = new MemoryStorage();
  const [microsoft] = configuredOidcProviders({ microsoftClientId: "ms-id" });
  const url = new URL(await beginSignIn(microsoft, "https://portal.example.org", "2026-10-10T10:00:00.000Z", storage));
  const pending = JSON.parse(storage.getItem("frp.oidc.pending") ?? "{}") as PendingSignIn;
  expect(url.searchParams.get("response_type")).toBe("code");
  expect(url.searchParams.get("response_mode")).toBe("query");
  expect(url.searchParams.get("code_challenge_method")).toBe("S256");
  expect(url.searchParams.get("redirect_uri")).toBe("https://portal.example.org/login/callback");
  expect(url.searchParams.get("code_challenge")).toBe(await sha256Base64Url(pending.codeVerifier));
  expect(url.searchParams.get("nonce")).toBe(await sha256Base64Url(pending.rawNonce));
  expect(url.toString()).not.toContain(pending.codeVerifier);
  expect(url.toString()).not.toContain(pending.rawNonce);
  expect(pending.state.startsWith("web.")).toBe(true);
  expect(pending.codeVerifier.length).toBeGreaterThanOrEqual(43);
  expect(pending.agreedAt).toBe("2026-10-10T10:00:00.000Z");
});

test("the sha-256 digest is base64url without padding (RFC 7636 appendix B)", async () => {
  expect(await sha256Base64Url("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe(
    "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  );
});

test("Yahoo is asked for a code with the same parameters and no Microsoft-only ones", () => {
  const [yahoo] = configuredOidcProviders({ yahooClientId: "y-id" });
  const url = new URL(authorizeUrl(yahoo, { redirectUri: "https://x/login/callback", state: "web.s", codeChallenge: "c", nonceDigest: "n" }));
  expect(url.searchParams.get("client_id")).toBe("y-id");
  expect(url.searchParams.get("scope")).toBe("openid email profile");
  expect(url.searchParams.has("response_mode")).toBe(false);
});

test("the sign-in body carries the raw nonce and the verifier to our own route only", () => {
  const pending: PendingSignIn = {
    provider: "YAHOO",
    state: "web.s",
    codeVerifier: "v".repeat(64),
    rawNonce: "raw-nonce-value-0123",
    redirectUri: "https://x/login/callback",
    agreedAt: null,
    startedAt: 0
  };
  expect(loginBody(pending, "the-code")).toEqual({
    oidcProvider: "YAHOO",
    oidcCode: "the-code",
    oidcCodeVerifier: "v".repeat(64),
    oidcRedirectUri: "https://x/login/callback",
    oidcNonce: "raw-nonce-value-0123"
  });
});

/* 3. Completing only what this tab started */

test("a callback for another state, a second read, or a stale sign-in completes nothing", async () => {
  const storage = new MemoryStorage();
  const [yahoo] = configuredOidcProviders({ yahooClientId: "y-id" });
  await beginSignIn(yahoo, "https://x", null, storage);
  const state = (JSON.parse(storage.getItem("frp.oidc.pending") ?? "{}") as PendingSignIn).state;
  expect(takePendingSignIn("web.somebody-else", storage)).toBeNull();
  // The wrong state consumed it: a code is single use and so is its verifier.
  expect(takePendingSignIn(state, storage)).toBeNull();

  await beginSignIn(yahoo, "https://x", null, storage);
  const fresh = JSON.parse(storage.getItem("frp.oidc.pending") ?? "{}") as PendingSignIn;
  expect(takePendingSignIn(fresh.state, storage, fresh.startedAt + PENDING_LIFETIME_MS + 1)).toBeNull();

  await beginSignIn(yahoo, "https://x", null, storage);
  const current = JSON.parse(storage.getItem("frp.oidc.pending") ?? "{}") as PendingSignIn;
  expect(takePendingSignIn(current.state, storage)?.provider).toBe("YAHOO");
  expect(takePendingSignIn(current.state, storage)).toBeNull();
});

test("a cancelled sign-in says so without blaming anybody", () => {
  expect(callbackErrorMessage("MICROSOFT", "access_denied")).toBe(
    "Signing in with Microsoft was cancelled. You can try again."
  );
  expect(callbackErrorMessage("YAHOO", "server_error")).not.toMatch(/server|endpoint|http/i);
});

/* 4. The callback route */

test("a web state goes back to /login in the fragment, with nothing but code, state and error", () => {
  const target = callbackTarget(new URLSearchParams("code=abc&state=web.s1&session_state=zzz&iss=x"));
  expect(target.startsWith("/login#oidc=")).toBe(true);
  const parsed = parseCallbackFragment(target.slice("/login".length));
  expect(parsed).toEqual({ code: "abc", state: "web.s1", error: null });
  expect(target).not.toContain("session_state");
});

test("an app state is handed to the Android app over its own scheme", () => {
  const target = callbackTarget(new URLSearchParams("code=abc&state=app.s2"));
  expect(target).toBe(`${ANDROID_SIGN_IN_SCHEME}://oidc/callback?code=abc&state=app.s2`);
  expect(callbackTarget(new URLSearchParams("error=access_denied&state=app.s3"))).toBe(
    `${ANDROID_SIGN_IN_SCHEME}://oidc/callback?state=app.s3&error=access_denied`
  );
});

test("the route never caches the code and never leaks it in a Referer", () => {
  expect(CALLBACK_ROUTE).toContain('"Cache-Control", "no-store"');
  expect(CALLBACK_ROUTE).toContain('"Referrer-Policy", "no-referrer"');
  expect(CALLBACK_ROUTE).toContain("callbackTarget(request.nextUrl.searchParams");
});

test("the page strips the answer off the address bar before using it", () => {
  const from = LOGIN.indexOf("parseCallbackFragment(window.location.hash)");
  const effect = from < 0 ? "" : LOGIN.slice(from, LOGIN.indexOf("loginWithOidc(", from));
  expect(effect.indexOf("history.replaceState")).toBeGreaterThan(-1);
  expect(effect.indexOf("history.replaceState")).toBeLessThan(effect.indexOf("takePendingSignIn("));
});

test("the Android scheme is the one the app registers", () => {
  const gradle = read("..", "android", "app", "build.gradle.kts");
  expect(gradle).toContain(`manifestPlaceholders["appAuthRedirectScheme"] = "${ANDROID_SIGN_IN_SCHEME}"`);
});
