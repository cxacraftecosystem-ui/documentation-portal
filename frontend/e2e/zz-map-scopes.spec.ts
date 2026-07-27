import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

/**
 * The map at its three scopes, at desktop and phone widths.
 *
 * WHY THE MAP CALLS ARE PROXIED. The dev server talks to the deployed API, which does not have
 * `/map/*` until this branch ships. So the page is signed in against the real API as usual — every
 * other request is genuine — and only `/api/map/**` is forwarded to a locally-run backend that IS
 * on this branch and IS pointed at the same production database. What renders below is therefore
 * this branch's real code over the real corpus; the one thing swapped is which process answers.
 * `E2E_MAP_API` and `E2E_MAP_TOKEN` carry the address and a token minted by that local backend,
 * whose signing secret is its own.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const MAP_API = process.env.E2E_MAP_API ?? "";
const MAP_TOKEN = process.env.E2E_MAP_TOKEN ?? "";
const OUT = process.env.SHOT_DIR ?? "shots/map";
fs.mkdirSync(OUT, { recursive: true });

/**
 * ONE sign-in for the whole file, replayed into every test as the stored session token.
 *
 * Six tests each driving the login form meant six round trips through a real auth endpoint in one
 * run, and two of them timed out on any given run — a flake in the harness, reported as a failure of
 * the map. The session is a precondition of these tests, not the thing under test.
 */
let sessionToken = "";

const DESKTOP = { width: 1280, height: 900 };
const PHONE = { width: 390, height: 844 };
const NARROW = { width: 360, height: 780 };

async function proxyMapApi(page: Page) {
  await page.route("**/api/map/**", async (route) => {
    const url = new URL(route.request().url());
    const response = await page.request.fetch(`${MAP_API}${url.pathname}${url.search}`, {
      headers: { Authorization: `Bearer ${MAP_TOKEN}` }
    });
    await route.fulfill({
      status: response.status(),
      contentType: "application/json",
      body: await response.text()
    });
  });
}

test.beforeAll(async ({ browser }) => {
  test.skip(!MAP_API || !MAP_TOKEN, "E2E_MAP_API and E2E_MAP_TOKEN must point at a local backend");
  // Generous, and deliberately more than a test's own budget: this is one cold start of the dev
  // server's /login route plus a round trip to a real auth endpoint behind a CDN, and it is paid
  // once for the whole file rather than once per test.
  test.setTimeout(240_000);

  const context = await browser.newContext();
  const page = await context.newPage();
  for (let attempt = 1; attempt <= 3 && !sessionToken; attempt += 1) {
    try {
      await page.goto("/login", { timeout: 60_000 });
      await page.getByPlaceholder("Enter your email").fill(EMAIL);
      await page.getByPlaceholder("Enter your password").fill(PASSWORD);
      await page.getByRole("button", { name: /sign in/i }).click();
      await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
      sessionToken = await page.evaluate(() => window.localStorage.getItem("field_repo_token") ?? "");
    } catch {
      // Retried rather than failed: a cold compile or a slow CDN hop is not a result about the map.
    }
  }
  expect(sessionToken, "could not sign in against the API the dev server is pointed at").not.toBe("");
  await context.close();
});

async function openMap(page: Page, query = "") {
  await page.goto(`/map${query}`);
  await expect(page.getByRole("heading", { name: "Where the work comes from" })).toBeVisible();
  // The list is the accessible half of the map, so waiting on it also proves it rendered.
  await expect(page.getByRole("heading", { name: "Every place, as a list" })).toBeVisible({
    timeout: 45_000
  });
  await page.waitForTimeout(500);
}

/** No page may scroll sideways — the quality floor, checked rather than eyeballed. */
async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1);
}

test.beforeEach(async ({ page }) => {
  // The app reads its session straight out of localStorage, so seeding the key it looks for is the
  // whole of "already signed in" — no second trip through the login form.
  await page.addInitScript(
    (token) => window.localStorage.setItem("field_repo_token", token),
    sessionToken
  );
  await proxyMapApi(page);
});

// A proxied request still in flight when a test ends fails inside the route callback and is
// reported as an error belonging to no test. Dropping the routes first makes the teardown quiet.
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("scope 1 of 3 — the whole repository", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await openMap(page);

  const places = page.getByRole("button", { name: /Kharagpur|Bareilly|Bagru/ });
  await expect(places.first()).toBeVisible();

  // Both layers are present and told apart in TEXT, not only by pin shape.
  await expect(page.getByText("Placed by craft origin")).toBeVisible();
  await expect(page.getByText("Placed by GPS fix")).toBeVisible();
  await expect(page.getByText(/GPS fix taken while recording/).first()).toBeVisible();

  await page.screenshot({ path: path.join(OUT, "1280-scope-all.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);
});

test("scope 2 of 3 — a filtered subset, driven by the shared filter vocabulary", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await openMap(page, "?place=Bareilly");

  await expect(page.getByText("The records matching your filters")).toBeVisible();
  await expect(page.getByRole("button", { name: /Bareilly/ })).toBeVisible();

  await page.screenshot({ path: path.join(OUT, "1280-scope-subset.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);
});

test("scope 3 of 3 — one record in context", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  // Resolved at run time so the spec does not carry a record id that will rot.
  const listed = await page.request.get(`${MAP_API}/api/artisans?pageSize=1`, {
    headers: { Authorization: `Bearer ${MAP_TOKEN}` }
  });
  const artisan = (await listed.json()).items[0];

  await openMap(page, `?focusType=artisans&focusId=${artisan.id}`);
  await expect(page.getByText("One record, shown in context")).toBeVisible();
  await expect(page.getByText(`Showing ${artisan.name} in context`)).toBeVisible();
  await expect(page.getByText("This record").first()).toBeVisible();

  await page.screenshot({ path: path.join(OUT, "1280-scope-record.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);
});

test("a pin can be navigated from", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await openMap(page);

  await page.getByRole("button", { name: /Bareilly/ }).click();
  const panel = page.getByRole("region", { name: "Bareilly" });
  await expect(panel).toBeVisible();

  // Specifically a RECORD link. The panel also carries an "Open these in Browse records" link, and
  // matching the first link in the region would pass on that one while the list was still loading.
  const record = panel.locator("a[href^='/artisans/'], a[href^='/products/'], a[href^='/tools/']");
  await expect(record.first()).toBeVisible({ timeout: 30_000 });
  await expect(panel.getByRole("link", { name: /Browse records/ })).toBeVisible();

  await page.screenshot({ path: path.join(OUT, "1280-pin-panel.png"), fullPage: true });
  // With the panel open is where the page DID scroll sideways: a record title is arbitrary text, and
  // an implicit grid track sized itself to the longest one.
  await expectNoHorizontalOverflow(page);

  // And it genuinely lands on the record, rather than being a link-shaped thing.
  await record.first().click();
  await page.waitForURL(/\/(artisans|products|tools)\/[^/]+\/edit/, { timeout: 45_000 });
});

test("the graphic is hidden from assistive technology and holds nothing focusable", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await openMap(page);

  const svg = page.locator("svg[aria-hidden='true'][role='presentation']");
  await expect(svg).toHaveCount(1);
  // Focusable content inside an aria-hidden subtree is the failure mode this asserts against: it is
  // unreachable by a screen reader yet still lands in the tab order.
  expect(await svg.locator("a, button, [tabindex]:not([tabindex='-1'])").count()).toBe(0);

  // Everything the picture says is reachable by keyboard through the list instead.
  const first = page.getByRole("button", { name: /Kharagpur|Bareilly|Bagru/ }).first();
  await first.focus();
  await expect(first).toBeFocused();
});

test("phone at 390 and no sideways scroll at 360", async ({ page }) => {
  await page.setViewportSize(PHONE);
  await openMap(page);
  await page.screenshot({ path: path.join(OUT, "390-scope-all.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);

  await page.getByRole("button", { name: /Bareilly/ }).click();
  await expect(page.getByRole("region", { name: "Bareilly" })).toBeVisible();
  await page.screenshot({ path: path.join(OUT, "390-pin-panel.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);

  await page.setViewportSize(NARROW);
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(OUT, "360-scope-all.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);
});
