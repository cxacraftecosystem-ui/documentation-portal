import { expect, test, type Locator, type Page } from "@playwright/test";

import { QuestionnaireFake } from "./support/qv2Fake";

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/questionnaire-bottom-actions";
const VIEWPORTS = [
  { width: 390, height: 844 },
  { width: 834, height: 1112 }
] as const;
const PAGES = [
  { path: "/questionnaires", name: "new-form", action: (page: Page) => page.getByRole("button", { name: "New form" }).first() },
  { path: "/questionnaire", name: "new-session", action: (page: Page) => page.getByRole("link", { name: "New session" }).first() }
] as const;

type Box = { x: number; y: number; width: number; height: number };

function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

async function box(locator: Locator): Promise<Box> {
  const found = await locator.boundingBox();
  expect(found).not.toBeNull();
  return found!;
}

async function pageEnd(page: Page): Promise<number> {
  return page.evaluate(() => {
    const main = document.getElementById("main-content");
    if (!main) return 0;
    return Array.from(main.children).reduce((end, child) => {
      const position = getComputedStyle(child).position;
      if (position === "fixed" || position === "absolute") return end;
      return Math.max(end, child.getBoundingClientRect().bottom);
    }, 0);
  });
}

test.describe.configure({ mode: "serial" });

for (const viewport of VIEWPORTS) {
  for (const target of PAGES) {
    test(`${target.path} keeps its primary action and its last row clear of the bottom bar at ${viewport.width}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const fake = new QuestionnaireFake();
      await fake.install(page);
      await page.goto(target.path);
      const nav = page.getByTestId("bottom-island-nav");
      const action = target.action(page);
      await expect(nav).toBeVisible();
      await expect(action).toBeVisible();
      await page.evaluate(() => document.fonts.ready.then(() => undefined));
      expect(await page.evaluate(() => window.scrollY)).toBe(0);
      const bar = await box(nav);
      const button = await box(action);
      expect(overlaps(button, bar)).toBe(false);
      expect(button.y).toBeGreaterThanOrEqual(0);
      expect(button.y + button.height).toBeLessThanOrEqual(bar.y);
      await page.screenshot({ path: `${SHOTS}/${target.name}-${viewport.width}-top.png` });
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      await expect.poll(async () => (await pageEnd(page)) - (await box(nav)).y).toBeLessThanOrEqual(0);
      await page.screenshot({ path: `${SHOTS}/${target.name}-${viewport.width}-end.png` });
    });
  }
}
