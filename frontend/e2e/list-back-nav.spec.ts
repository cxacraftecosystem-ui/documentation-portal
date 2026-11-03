import { existsSync } from "node:fs";
import { join } from "node:path";

import { devices, expect, test, type Locator, type Page } from "@playwright/test";

import { ListBackNavFake, rowName } from "./support/listBackNavFake";

const VIEWPORTS = [
  { name: "desktop", use: { viewport: { width: 1280, height: 900 } } },
  { name: "tablet", use: { viewport: { width: 820, height: 1180 }, hasTouch: true } },
  {
    name: "phone",
    use: (() => {
      const { defaultBrowserType: _ignored, ...pixel } = devices["Pixel 7"];
      void _ignored;
      return pixel;
    })()
  }
] as const;

const APP = join(__dirname, "..", "app", "(protected)");
const has = (route: string) => existsSync(join(APP, ...route.split("/").filter(Boolean), "page.tsx"));

const PAGED = [
  { name: "artisans", route: "/artisans", search: /^Search (artisans|by name, craft, place or notes)$/ },
  { name: "products", route: "/products", search: /^Search product/ },
  { name: "processes", route: "/processes", search: /^Search process/ },
  { name: "tools", route: "/tools", search: /^Search tool/ },
  { name: "workshops", route: "/workshops", search: /^Search workshops by/ },
  { name: "design workshops", route: "/design-workshops", search: /^Search design workshops/ },
  { name: "media", route: "/media", search: /^Search media/, needs: "/design-workshops" },
  { name: "forms", route: "/questionnaires", search: /^Search questionnaires$/, needs: "/design-workshops" }
] as const;

const CURSOR = [
  { name: "sessions", route: "/questionnaire", search: /^Search sessions/ },
  { name: "recordings", route: "/questionnaire/recordings", search: /^Search recordings/ }
] as const;

const visible = (locator: Locator) => locator.filter({ visible: true }).first();
const scrollY = (page: Page) => page.evaluate(() => Math.round(window.scrollY));
const row = (page: Page, n: number) => visible(page.getByText(new RegExp(`${rowName(n)}\\b`)));

async function press(locator: Locator) {
  await locator.focus();
  await locator.press("Enter");
}

const next = (page: Page) => page.getByRole("button", { name: "Next", exact: true }).filter({ visible: true }).last();

const link = (page: Page, n: number) => visible(page.getByRole("link", { name: new RegExp(`${rowName(n)}\\b`) }));

async function settleOn(page: Page, n: number): Promise<number> {
  await link(page, n).evaluate((element) => element.scrollIntoView({ block: "center" }));
  await page.waitForTimeout(400);
  return scrollY(page);
}

async function expectScrollBack(page: Page, saved: number) {
  const reachable = () => page.evaluate((want) => Math.min(want, Math.round(document.documentElement.scrollHeight - window.innerHeight)), saved);
  await expect.poll(async () => (await scrollY(page)) >= (await reachable()) - 4, { timeout: 10_000 }).toBe(true);
  await expect.poll(() => scrollY(page), { timeout: 10_000 }).toBeLessThanOrEqual(saved + 4);
}

for (const viewport of VIEWPORTS) {
  test.describe(viewport.name, () => {
    test.use(viewport.use);
    test.use({ actionTimeout: 20_000 });

    for (const list of PAGED) {
      test(`${list.name}: Back from a record lands on the same page, search and row`, async ({ page }) => {
        test.skip(!has(list.route) || ("needs" in list && !has(list.needs)), `${list.route} is not a record list in this portal`);
        await new ListBackNavFake().install(page);
        await page.goto(list.route);
        const box = visible(page.getByRole("searchbox", { name: list.search }));
        await expect(box).toBeVisible({ timeout: 30_000 });
        await box.fill("Record");
        await expect(page).toHaveURL(/[?&]search=Record\b/);
        await expect(row(page, 1)).toBeVisible();
        await press(next(page));
        await expect(row(page, 21)).toBeVisible();
        await expect(page).toHaveURL(/[?&]page=2\b/);

        const saved = await settleOn(page, 34);
        await link(page, 34).click();
        await expect(page).not.toHaveURL(new RegExp(`${list.route}(\\?|$)`));

        await page.goBack();
        await expect(page).toHaveURL(/[?&]page=2\b/);
        await expect(page).toHaveURL(/[?&]search=Record\b/);
        await expect(row(page, 21)).toBeVisible({ timeout: 30_000 });
        await expect(row(page, 1)).toHaveCount(0);
        await expect(visible(page.getByRole("searchbox", { name: list.search }))).toHaveValue("Record");
        await expectScrollBack(page, saved);
      });
    }

    for (const list of CURSOR) {
      test(`${list.name}: Back from a session lands on the same pages, filter and row`, async ({ page }) => {
        test.skip(!has(list.route), `${list.route} is not in this portal`);
        await new ListBackNavFake().install(page);
        await page.goto(list.route);
        const box = visible(page.getByRole("searchbox", { name: list.search }));
        await expect(box).toBeVisible({ timeout: 30_000 });
        await box.fill("Record");
        await expect(page).toHaveURL(/[?&]q=Record\b/);
        await expect(row(page, 1)).toBeVisible();
        await press(visible(page.getByRole("button", { name: "Show more", exact: true })));
        await expect(row(page, 30)).toBeVisible();

        const saved = await settleOn(page, 30);
        await link(page, 30).click();
        await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-30\/view/);

        await page.goBack();
        await expect(page).toHaveURL(/[?&]q=Record\b/);
        await expect(row(page, 30)).toBeVisible({ timeout: 30_000 });
        await expect(visible(page.getByRole("searchbox", { name: list.search }))).toHaveValue("Record");
        await expectScrollBack(page, saved);
      });
    }

    test("search results: Back from a record lands on the same page, query and row", async ({ page }) => {
      await new ListBackNavFake().install(page);
      await page.goto("/search");
      const box = page.getByPlaceholder(/^Search (repository|records)$/);
      await expect(box).toBeVisible({ timeout: 30_000 });
      await box.fill("Record");
      await box.press("Enter");
      await expect(row(page, 1)).toBeVisible();
      await press(next(page));
      await expect(row(page, 21)).toBeVisible();
      await expect(page).toHaveURL(/[?&]page=2\b/);

      const saved = await settleOn(page, 34);
      await link(page, 34).click();
      await expect(page).toHaveURL(/\/artisans\/artisans-34/);

      await page.goBack();
      await expect(page).toHaveURL(/[?&]q=Record\b/);
      await expect(page).toHaveURL(/[?&]page=2\b/);
      await expect(visible(page.locator('a[href="/artisans/artisans-21"]'))).toBeVisible({ timeout: 30_000 });
      await expect(page.locator('a[href="/artisans/artisans-1"]')).toHaveCount(0);
      await expect(page.getByPlaceholder(/^Search (repository|records)$/)).toHaveValue("Record");
      await expectScrollBack(page, saved);
    });
  });
}
