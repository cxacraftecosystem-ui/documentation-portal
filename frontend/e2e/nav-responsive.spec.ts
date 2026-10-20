import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const CREDENTIALS_MISSING = !EMAIL || !PASSWORD ? "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs." : "";

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * Responsive navigation, in a browser (REQUIREMENTS_2026-10-20 §4). Signed in, against a running
 * stack. The pure rules are `island-nav-unit.spec.ts`; the source shape is `nav-responsive-unit.spec.ts`.
 *
 * Below 1024 px: the search header at the top and the bottom island (at most five slots, the fifth
 * "More" when there are more) — and no desktop island. From 1024 px: the desktop island, expanded,
 * with its strip and bell — and neither the header nor the bar.
 */

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);

const bar = (page: Page) => page.getByTestId("bottom-island-nav");
const header = (page: Page) => page.getByTestId("app-search-header");
const islandMenu = (page: Page) => page.getByRole("button", { name: "Toggle navigation menu" });

async function tallPage(page: Page) {
  await page.evaluate(() => {
    document.body.style.minHeight = "4000px";
  });
}

for (const size of [
  { width: 390, height: 844 },
  { width: 820, height: 1180 }
]) {
  test.describe(`at ${size.width}×${size.height}`, () => {
    test.use({ viewport: size, hasTouch: true });

    test.beforeEach(async ({ page }) => {
      await signIn(page);
      await page.goto("/dashboard");
      await expect(bar(page)).toBeVisible();
    });

    test("the bottom island and the header replace the desktop island", async ({ page }) => {
      await expect(header(page)).toBeVisible();
      await expect(islandMenu(page)).toBeHidden();
      // At most five slots, "More" included, then the menu button.
      const slots = bar(page).locator(":scope > div > div");
      const count = await slots.count();
      expect(count).toBeGreaterThan(0);
      expect(count).toBeLessThanOrEqual(5);
      await expect(bar(page).getByRole("button", { name: "Open menu" })).toBeVisible();
      await expect(bar(page).getByRole("link", { name: /^Dashboard/ })).toHaveAttribute("aria-current", "page");
      if (size.width >= 640) {
        const box = await bar(page).boundingBox();
        expect(box!.width).toBeLessThanOrEqual(560.5);
      }
    });

    test("More, when drawn, opens a sheet of the rest", async ({ page }) => {
      const more = bar(page).getByRole("button", { name: /^More/ });
      test.skip((await more.count()) === 0, "this account has five slots or fewer");
      await more.click();
      const sheet = page.getByRole("dialog", { name: "More" });
      await expect(sheet).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(sheet).toBeHidden();
      await expect(more).toBeFocused();
    });

    test("the header hides on the way down and returns on any move up", async ({ page }) => {
      await tallPage(page);
      const frame = page.locator(".app-search-header-frame");
      await page.mouse.wheel(0, 200);
      await expect(frame).toHaveAttribute("data-shown", "false");
      await page.mouse.wheel(0, -10);
      await expect(frame).toHaveAttribute("data-shown", "true");
    });

    test("the header opens search, and the bar hides while it is open", async ({ page }) => {
      await header(page).getByRole("button", { name: /Search$/ }).click();
      const panel = page.getByTestId("app-search-panel");
      await expect(panel).toBeVisible();
      await expect(bar(page)).toBeHidden();
      await page.keyboard.press("Escape");
      await expect(panel).toBeHidden();
      await expect(bar(page)).toBeVisible();
    });

    test("the bar hides while the on-screen keyboard is open", async ({ page }) => {
      await page.evaluate(() => {
        const viewport = window.visualViewport!;
        Object.defineProperty(viewport, "height", { configurable: true, get: () => window.innerHeight - 300 });
        viewport.dispatchEvent(new Event("resize"));
      });
      await expect(bar(page)).toBeHidden();
    });
  });
}

test.describe("at 1440×900", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("the island is drawn expanded, and neither the header nor the bar", async ({ page }) => {
    await signIn(page);
    await page.goto("/dashboard");
    await expect(islandMenu(page)).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Notifications/ }).first()).toBeVisible();
    await expect(bar(page)).toHaveCount(1);
    await expect(bar(page)).toBeHidden();
    await expect(header(page)).toBeHidden();
    const pill = page.locator(".nav-island-frame header");
    await expect(pill).toHaveClass(/px-4 py-2/);
    const logo = await page.locator(".nav-island-frame header a[href='/dashboard'] svg, .nav-island-frame header a[href='/dashboard'] img").first().boundingBox();
    expect(Math.round(logo!.height)).toBe(32);
    const name = page.locator(".nav-island-frame header a[href='/dashboard'] span").first();
    await expect(name).toHaveCSS("font-size", "16px");
  });
});
