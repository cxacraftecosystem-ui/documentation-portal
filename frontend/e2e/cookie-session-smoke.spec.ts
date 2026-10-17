import { readFileSync } from "node:fs";

import { expect, test, type Page } from "@playwright/test";

/**
 * THE COOKIE SESSION, END TO END (2026-10-17): sign in, move around, upload, sign out — against a real
 * API, through this site's own `/api/*` proxy, in a real browser.
 *
 * What it proves that the unit spec (`cookie-session-proxy-unit.spec.ts`) cannot: that a browser
 * really stores the `__Host-` cookie HttpOnly, that the app works with nothing in localStorage, that a
 * multipart upload (and a large one) streams through `proxy.ts` to the API, that a write without the
 * browser's same-origin headers is refused, that sign-out clears the cookie, and that a browser still
 * holding an old localStorage token is moved onto the cookie without being signed out.
 *
 * Needs the stack: a production build (`next build && next start`) with `NEXT_PUBLIC_API_URL` pointing
 * at a local API, and that API seeded with `backend/scripts/seed_test_accounts.py`.
 *
 *   E2E_BASE_URL=http://localhost:3010 E2E_API_URL=http://localhost:8010 \
 *   E2E_EMAIL=designer@example.org E2E_PASSWORD=LocalDev123! \
 *   E2E_QUESTIONNAIRE_XLSX=<a questionnaire workbook> npx playwright test cookie-session-smoke
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";
const WORKBOOK = process.env.E2E_QUESTIONNAIRE_XLSX ?? "";

test.skip(!EMAIL || !PASSWORD || !WORKBOOK, "Set E2E_EMAIL, E2E_PASSWORD and E2E_QUESTIONNAIRE_XLSX to run the cookie smoke.");

async function signInThroughTheForm(page: Page) {
  await page.goto("/login");
  const email = page.locator("#email");
  await email.waitFor({ state: "visible", timeout: 60_000 });
  await email.fill(EMAIL);
  await page.locator("#password").fill(PASSWORD);
  // The terms box is required to sign in when the account has not yet agreed.
  const terms = page.getByRole("checkbox", { name: /I agree to the/i });
  if (await terms.isVisible()) await terms.check();
  const submit = page.getByRole("button", { name: /sign in/i });
  await submit.click();
  try {
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 30_000 });
  } catch {
    // The hydration race `a11y-barriers.spec.ts` describes: press once more when certainly live.
    await submit.click();
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  }
}

test("sign in, navigate, upload and sign out — with the session only ever in an HttpOnly cookie", async ({ page, context }) => {
  await signInThroughTheForm(page);

  // The cookie the browser holds, and what a script on the page can see of it.
  const cookies = await context.cookies();
  const session = cookies.find((cookie) => cookie.name === "__Host-session");
  const marker = cookies.find((cookie) => cookie.name === "__Host-session-marker");
  expect(session, "the session cookie was set").toBeTruthy();
  expect(session!.httpOnly).toBe(true);
  expect(session!.secure).toBe(true);
  expect(session!.sameSite).toBe("Lax");
  expect(session!.path).toBe("/");
  expect(marker!.httpOnly).toBe(false);
  const seenByScript = await page.evaluate(() => ({
    cookie: document.cookie,
    stored: Object.keys(window.localStorage).filter((key) => /token/i.test(key)),
    session: Object.keys(window.sessionStorage).filter((key) => /token/i.test(key))
  }));
  expect(seenByScript.cookie, "a script cannot read the token").not.toContain(session!.value);
  expect(seenByScript.cookie).toContain("__Host-session-marker=");
  expect(seenByScript.stored, "nothing token-shaped in localStorage").toEqual([]);
  expect(seenByScript.session, "nor in sessionStorage").toEqual([]);

  // Navigate: two signed-in screens load, and every API call they make is same-origin.
  const crossOrigin: string[] = [];
  page.on("request", (request) => {
    if (request.url().startsWith(API)) crossOrigin.push(request.url());
  });
  await page.goto("/dashboard");
  await expect(page).not.toHaveURL(/\/login/);
  await page.goto("/settings");
  await expect(page).not.toHaveURL(/\/login/);
  await page.waitForLoadState("networkidle");
  expect(crossOrigin, "the browser never calls the API's own address").toEqual([]);

  // Upload: the same multipart call the questionnaire screen makes, through proxy.ts to the API.
  const workbook = readFileSync(WORKBOOK).toString("base64");
  const upload = await page.evaluate(async (data) => {
    const bytes = Uint8Array.from(atob(data), (c) => c.charCodeAt(0));
    const form = new FormData();
    form.append("file", new Blob([bytes], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }), "smoke.xlsx");
    form.append("title", `Cookie smoke ${Date.now()}`);
    const response = await fetch("/api/questionnaires/upload", { method: "POST", body: form });
    return { status: response.status, body: (await response.text()).slice(0, 300) };
  }, workbook);
  expect(upload.status, upload.body).toBe(201);

  // A body larger than a Vercel Function would accept (4.5 MB) reaches the API whole: the API reads
  // it and refuses it as a workbook (422), rather than the proxy failing or truncating it.
  const large = await page.evaluate(async () => {
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(6 * 1024 * 1024)]), "large.xlsx");
    const response = await fetch("/api/questionnaires/upload", { method: "POST", body: form });
    return { status: response.status, body: (await response.text()).slice(0, 200) };
  });
  expect(large.status, large.body).toBe(422);

  // A write without the browser's same-origin headers (what a forged cross-site POST looks like to
  // this site once the cookie is there) is refused before the API is asked.
  const forged = await page.request.post("/api/questionnaires/upload", {
    headers: { Origin: "https://evil.example", "Sec-Fetch-Site": "cross-site" },
    multipart: { file: { name: "x.xlsx", mimeType: "application/octet-stream", buffer: Buffer.from("x") } }
  });
  expect(forged.status()).toBe(403);

  // Sign out through the app's own control, from a signed-in screen.
  await page.goto("/dashboard");
  const logout = page.getByRole("button", { name: "Logout" });
  if (!(await logout.isVisible())) await page.getByRole("button", { name: "Toggle navigation menu" }).click();
  await logout.click();
  await page.waitForURL(/\/login/);
  const after = await context.cookies();
  expect(after.find((cookie) => cookie.name === "__Host-session"), "the session cookie is gone").toBeUndefined();
  expect(after.find((cookie) => cookie.name === "__Host-session-marker")).toBeUndefined();
  const me = await page.evaluate(async () => (await fetch("/api/me")).status);
  expect(me, "and the site no longer acts as the user").toBe(401);
});

test("a browser signed in before the change keeps its session, moved into the cookie", async ({ page, context, request }) => {
  const login = await request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(login.ok(), await login.text()).toBeTruthy();
  const token = ((await login.json()) as { accessToken: string }).accessToken;

  // Exactly what an old bundle left behind.
  await page.goto("/login");
  await page.evaluate((value) => window.localStorage.setItem("field_repo_token", value), token);
  await page.goto("/dashboard");
  await expect(page).not.toHaveURL(/\/login/);
  await expect.poll(async () => (await context.cookies()).some((cookie) => cookie.name === "__Host-session")).toBe(true);
  expect(await page.evaluate(() => window.localStorage.getItem("field_repo_token")), "and deleted from storage").toBeNull();
  const session = (await context.cookies()).find((cookie) => cookie.name === "__Host-session");
  expect(session!.httpOnly).toBe(true);
  expect(session!.value).toBe(token);
});
