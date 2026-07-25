// Playwright smoke/observation of the web app against the live API.
// Logs in, walks every protected page, and records console errors, uncaught page errors, and any
// HTTP responses >= 400. Screenshots go to pw-screens/.
//
//   PW_PASSWORD='…' node scripts/pw-smoke.mjs
//   PW_BASE=http://localhost:3000 PW_EMAIL=admin@example.com PW_PASSWORD='…' node scripts/pw-smoke.mjs
//
// Exits non-zero when the login itself fails, so a broken smoke run cannot be mistaken for a clean
// one: this script silently walked nothing for weeks after the login button was renamed from
// "Login" to "Sign In", reporting zero errors because it never got past the login page. The submit
// button is now found by ROLE within the login form rather than by its label, so the next rewording
// of that button cannot blind the smoke test again.
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const BASE = process.env.PW_BASE || "http://localhost:3000";
const EMAIL = process.env.PW_EMAIL || "admin@example.com";
const PASSWORD = process.env.PW_PASSWORD || "";

// Every protected route that exists today (dynamic [id] routes are excluded — they need a real
// record id). Keep this in step with app/(protected)/**/page.tsx; a route missing here is a route
// nothing is watching.
const PAGES = [
  "/dashboard",
  // Records
  "/artisans", "/artisans/new",
  "/crafts",
  "/products", "/products/new",
  "/tools", "/tools/new",
  "/processes",
  "/workshops",
  "/questionnaire",
  "/media",
  // Browse, share, review
  "/data", "/search", "/sharing", "/review",
  // People and accountability
  "/users", "/activity", "/tasks", "/feedback", "/guide",
  // Admin. /admin, /settings/api-keys and the master-admin half of /settings render their own
  // "not permitted" state for a plain ADMIN — that state is worth screenshotting too, but the 403s
  // it produces are expected and show up in badRequests.
  "/admin",
  "/settings", "/settings/tasks", "/settings/workshop-access", "/settings/api-keys"
];

if (!PASSWORD) {
  console.error("PW_PASSWORD is not set — the walk would only ever screenshot the login page.");
  console.error("Usage: PW_PASSWORD='…' node scripts/pw-smoke.mjs");
  process.exit(2);
}

mkdirSync("pw-screens", { recursive: true });

const consoleErrors = [];
const pageErrors = [];
const badRequests = [];

const browser = await chromium.launch();
const page = await browser.newContext({ viewport: { width: 1366, height: 900 } }).then((c) => c.newPage());
page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text().slice(0, 300)); });
page.on("pageerror", (e) => pageErrors.push(String(e).slice(0, 300)));
page.on("response", (r) => { if (r.status() >= 400) badRequests.push(`${r.status()} ${r.request().method()} ${r.url()}`); });

const report = { pages: {} };

await page.goto(`${BASE}/login`, { waitUntil: "domcontentloaded" });
const loginForm = page.locator("form").filter({ has: page.locator("input[type=password]") });
const emailBox = page.locator("input[type=email]");
const passwordBox = page.locator("input[type=password]");
// The login inputs are CONTROLLED by React state, so anything typed before hydration finishes is
// wiped the moment React takes over the DOM — which silently emptied the email box and posted
// nothing at all. Wait for the page to settle, then re-fill until the values actually stick.
await page.waitForLoadState("networkidle").catch(() => {});
for (let attempt = 0; attempt < 5; attempt += 1) {
  await emailBox.fill(EMAIL);
  await passwordBox.fill(PASSWORD);
  await page.waitForTimeout(300);
  if ((await emailBox.inputValue()) === EMAIL && (await passwordBox.inputValue()) === PASSWORD) break;
}
// The submit button is found by type inside the credentials form, not by its label: it has been
// "Login" and is now "Sign In", and the OAuth buttons below are not submit buttons.
await loginForm.locator("button[type=submit]").click();
await page.waitForURL("**/dashboard", { timeout: 30000 }).catch(() => {});
await page.waitForTimeout(2500);
report.loginEndedAt = page.url();
report.loggedIn = page.url().includes("/dashboard");

if (!report.loggedIn) {
  // Whatever the login page said about why, verbatim — the point of the exercise.
  report.loginError = await page.locator("[role=alert], .text-error-600").first().textContent().catch(() => null);
  await page.screenshot({ path: "pw-screens/_login-failed.png", fullPage: true });
}

if (report.loggedIn) {
  for (const p of PAGES) {
    const before = consoleErrors.length + pageErrors.length;
    try {
      const resp = await page.goto(`${BASE}${p}`, { waitUntil: "networkidle", timeout: 30000 });
      await page.waitForTimeout(1500);
      await page.screenshot({ path: `pw-screens/${(p.replaceAll("/", "_") || "root")}.png`, fullPage: true });
      report.pages[p] = {
        status: resp?.status(),
        title: await page.title(),
        newJsErrors: consoleErrors.length + pageErrors.length - before
      };
    } catch (e) {
      report.pages[p] = { error: String(e).slice(0, 200) };
    }
  }
}

await browser.close();
console.log(JSON.stringify({
  report,
  consoleErrors: [...new Set(consoleErrors)].slice(0, 25),
  pageErrors: [...new Set(pageErrors)].slice(0, 20),
  badRequests: [...new Set(badRequests)].slice(0, 40)
}, null, 2));

// A failed login means the walk covered nothing at all; say so with the exit code as well as in the
// report, so CI or a caller piping this to a file notices.
if (!report.loggedIn) process.exit(1);
