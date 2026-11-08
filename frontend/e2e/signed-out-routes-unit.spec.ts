import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { isSignedOutRoute, mayHoldSession } from "@/lib/session";

import { installSessionCookie, removeSessionCookie } from "./support/fakeSessionCookie";

test.afterEach(() => removeSessionCookie());

test("the signed-out pages are known, and the app's own pages are not", () => {
  for (const path of ["/", "/login", "/login/", "/login/callback", "/forgot-password", "/set-password", "/terms", "/report-share/abc", "/report-schedule/x"]) {
    expect(isSignedOutRoute(path), path).toBe(true);
  }
  for (const path of ["/dashboard", "/data", "/loginx", "/terms-of-use", "/questionnaire"]) {
    expect(isSignedOutRoute(path), path).toBe(false);
  }
});

test("a signed-out visitor on a public page is never asked who they are", () => {
  installSessionCookie(null);
  expect(mayHoldSession("/login", false)).toBe(false);
  expect(mayHoldSession("/terms", false)).toBe(false);
  expect(mayHoldSession("/forgot-password", false)).toBe(false);
});

test("a session marker, an old stored token or an app page still reads the account", () => {
  installSessionCookie("marker-1");
  expect(mayHoldSession("/login", false)).toBe(true);
  removeSessionCookie();
  installSessionCookie(null);
  expect(mayHoldSession("/login", true)).toBe(true);
  expect(mayHoldSession("/dashboard", false)).toBe(true);
});

test("the provider skips the account read before it starts on a signed-out page", () => {
  const source = readFileSync(join(__dirname, "..", "components", "AuthProvider.tsx"), "utf8");
  expect(source).toContain("mayHoldSession(window.location.pathname, legacyToken)");
});
