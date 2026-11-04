import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const MAP_API = process.env.E2E_MAP_API ?? "";
const MAP_TOKEN = process.env.E2E_MAP_TOKEN ?? "";
const OUT = process.env.SHOT_DIR ?? "shots/map";
fs.mkdirSync(OUT, { recursive: true });

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
    }
  }
  expect(sessionToken, "could not sign in against the API the dev server is pointed at").not.toBe("");
  await context.close();
});

async function openMap(page: Page, query = "", { allRecords = true } = {}) {
  await page.goto(`/map${query}`);
  await expect(page.getByRole("heading", { name: "Where the work comes from" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Every place, as a list" })).toBeVisible({
    timeout: 45_000
  });
  if (allRecords) {
    await page.getByRole("button", { name: "All records" }).first().click();
    await expect(page.getByText(/Every workshop, and records not linked to one/)).toBeVisible();
  }
  await page.waitForTimeout(500);
}

const PLACE_LABEL = /Kharagpur|Paschim Medinipur|Bareilly|Bagru|Jaipur/;

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1);
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    (token) => window.localStorage.setItem("field_repo_token", token),
    sessionToken
  );
  await proxyMapApi(page);
});

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("scope 1 of 3 — the whole repository", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await openMap(page);

  const places = page.getByRole("button", { name: PLACE_LABEL });
  await expect(places.first()).toBeVisible();

  await expect(page.getByText("Placed by address")).toBeVisible();
  await expect(page.getByText("Outline: international border")).toBeVisible();
  await expect(page.getByText("State borders", { exact: true })).toBeVisible();
  await expect(page.getByText("District borders", { exact: true })).toBeVisible();
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

  const record = panel.locator("a[href^='/artisans/'], a[href^='/products/'], a[href^='/tools/']");
  await expect(record.first()).toBeVisible({ timeout: 30_000 });
  await expect(panel.getByRole("link", { name: /Open these in Search/ })).toBeVisible();

  await page.screenshot({ path: path.join(OUT, "1280-pin-panel.png"), fullPage: true });
  await expectNoHorizontalOverflow(page);

  await record.first().click();
  await page.waitForURL(/\/(artisans|products|tools)\/[^/?]+$/, { timeout: 45_000 });
});

test("the graphic is hidden from assistive technology and holds nothing focusable", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await openMap(page);

  const svg = page.locator("svg[aria-hidden='true'][role='presentation']");
  await expect(svg).toHaveCount(1);
  expect(await svg.locator("a, button, [tabindex]:not([tabindex='-1'])").count()).toBe(0);

  const first = page.getByRole("button", { name: PLACE_LABEL }).first();
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
