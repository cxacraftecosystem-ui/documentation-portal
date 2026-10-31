import { existsSync } from "node:fs";
import { join } from "node:path";

import { devices, expect, test, type Locator, type Page } from "@playwright/test";

import { ListBackNavFake, rowName } from "./support/listBackNavFake";

/**
 * BACK FROM A RECORD RESTORES THE LIST (owner, 1 Nov 2026): the exact page, the filters and the
 * scroll position, never the first record of page 1, on every list → record path. Identical in both
 * portals; a list this portal does not have is skipped by its route.
 *
 * A production build (`next build && next start`, the `list-back-nav` job in checks.yml) serves the
 * real pages; every `/api` call is answered by `support/listBackNavFake.ts` (45 rows, paged on the
 * server). Each path: search, go to page 2 (or "Show more"), scroll, open a row, press Back — the URL
 * carries the page and the search, the same rows and search are on screen, and the scroll is back.
 * On a desktop, a tablet and a phone viewport.
 */

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

/** The paged record lists: each one's route, and the accessible name of its search box. */
const PAGED = [
  { name: "artisans", route: "/artisans", search: /^Search (artisans|by name, craft, place or notes)$/ },
  { name: "products", route: "/products", search: /^Search product/ },
  { name: "processes", route: "/processes", search: /^Search process/ },
  { name: "tools", route: "/tools", search: /^Search tool/ },
  { name: "workshops", route: "/workshops", search: /^Search workshops by/ },
  { name: "design workshops", route: "/design-workshops", search: /^Search design workshops/ },
  // Field Repository's media rows open the file's preview in place, not a record page.
  { name: "media", route: "/media", search: /^Search media/, needs: "/design-workshops" },
  { name: "forms", route: "/questionnaires", search: /^Search questionnaires$/, needs: "/design-workshops" }
] as const;

/** The cursor lists ("Show more"), in the shared questionnaire screens. */
const CURSOR = [
  { name: "sessions", route: "/questionnaire", search: /^Search sessions/ },
  { name: "recordings", route: "/questionnaire/recordings", search: /^Search recordings/ }
] as const;

const visible = (locator: Locator) => locator.filter({ visible: true }).first();
const scrollY = (page: Page) => page.evaluate(() => Math.round(window.scrollY));
const row = (page: Page, n: number) => visible(page.getByText(new RegExp(`${rowName(n)}\\b`)));

/** Bring row `n` into view, let the list note the position, and return it. */
async function settleOn(page: Page, n: number): Promise<number> {
  await row(page, n).scrollIntoViewIfNeeded();
  await page.evaluate(() => window.scrollBy(0, 40));
  await page.waitForTimeout(400);
  return scrollY(page);
}

async function expectScrollBack(page: Page, saved: number) {
  await expect.poll(() => scrollY(page), { timeout: 10_000 }).toBeGreaterThanOrEqual(saved - 4);
  await expect.poll(() => scrollY(page), { timeout: 10_000 }).toBeLessThanOrEqual(saved + 4);
}

for (const viewport of VIEWPORTS) {
  test.describe(viewport.name, () => {
    test.use(viewport.use);

    for (const list of PAGED) {
      test(`${list.name}: Back from a record lands on the same page, search and row`, async ({ page }) => {
        test.skip(!has(list.route) || ("needs" in list && !has(list.needs)), `${list.route} is not a record list in this portal`);
        await new ListBackNavFake().install(page);
        await page.goto(list.route);
        const box = visible(page.getByRole("searchbox", { name: list.search }));
        await expect(box).toBeVisible({ timeout: 30_000 });
        await box.fill("Record");
        // The search is applied (and written to the URL) after its debounce; page 2 comes after that.
        await expect(page).toHaveURL(/[?&]search=Record\b/);
        await expect(row(page, 1)).toBeVisible();
        await visible(page.getByRole("button", { name: "Next", exact: true })).click();
        await expect(row(page, 21)).toBeVisible();
        await expect(page).toHaveURL(/[?&]page=2\b/);

        const saved = await settleOn(page, 34);
        await visible(page.getByRole("link", { name: new RegExp(`${rowName(34)}\\b`) })).click();
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
        await visible(page.getByRole("button", { name: "Show more", exact: true })).click();
        await expect(row(page, 30)).toBeVisible();

        const saved = await settleOn(page, 30);
        await visible(page.getByRole("link", { name: new RegExp(`${rowName(30)}\\b`) })).click();
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
      await visible(page.getByRole("button", { name: "Next", exact: true })).click();
      await expect(row(page, 21)).toBeVisible();
      await expect(page).toHaveURL(/[?&]page=2\b/);

      const saved = await settleOn(page, 34);
      await visible(page.getByRole("link", { name: new RegExp(`${rowName(34)}\\b`) })).click();
      await expect(page).toHaveURL(/\/artisans\/artisans-34/);

      await page.goBack();
      await expect(page).toHaveURL(/[?&]q=Record\b/);
      await expect(page).toHaveURL(/[?&]page=2\b/);
      await expect(row(page, 21)).toBeVisible({ timeout: 30_000 });
      await expect(row(page, 1)).toHaveCount(0);
      await expect(page.getByPlaceholder(/^Search (repository|records)$/)).toHaveValue("Record");
      await expectScrollBack(page, saved);
    });
  });
}
