// Playwright smoke/observation of the web app against the live API.
// Logs in as admin, walks every protected page, and records console errors,
// uncaught page errors, and any HTTP responses >= 400. Screenshots go to pw-screens/.
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const BASE = process.env.PW_BASE || "http://localhost:3000";
const EMAIL = process.env.PW_EMAIL || "admin@example.com";
const PASSWORD = process.env.PW_PASSWORD || "";
const PAGES = [
  "/dashboard", "/artisans", "/crafts", "/products", "/tools",
  "/workshops", "/questionnaire", "/media", "/users", "/review", "/search"
];

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
await page.fill("input[type=email]", EMAIL);
await page.fill("input[type=password]", PASSWORD);
await page.click('button:has-text("Login")');
await page.waitForURL("**/dashboard", { timeout: 30000 }).catch(() => {});
await page.waitForTimeout(2500);
report.loginEndedAt = page.url();
report.loggedIn = page.url().includes("/dashboard");

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

await browser.close();
console.log(JSON.stringify({
  report,
  consoleErrors: [...new Set(consoleErrors)].slice(0, 25),
  pageErrors: [...new Set(pageErrors)].slice(0, 20),
  badRequests: [...new Set(badRequests)].slice(0, 40)
}, null, 2));
