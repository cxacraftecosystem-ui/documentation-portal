import { expect, test, type Page, type Route } from "@playwright/test";

/**
 * Responsive navigation, in a browser (REQUIREMENTS_2026-10-20 §4.3 acceptance). Point it at a
 * running web server (`npm run dev`, or E2E_BASE_URL). The API is answered here, the way
 * `map-coverage.spec.ts` does it: the navigation is decided by the signed-in account's role and by
 * the viewport alone, so an admin and a researcher are two `/me` answers, and no database or
 * shared password is needed. The pure rules are `island-nav-unit.spec.ts`; the source shape is
 * `nav-responsive-unit.spec.ts`.
 *
 * Below 1024 px: the search header at the top and the bottom island (at most five slots, the fifth
 * "More" when there are more) — and no desktop island. From 1024 px: the desktop island, expanded,
 * with its strip, admin-view pill and bell — and neither the header nor the bar.
 */

type Account = { id: string; email: string; name: string; role: string };

const ADMIN: Account = { id: "u-admin", email: "admin@example.org", name: "Asha Admin", role: "MASTER_ADMIN" };
const RESEARCHER: Account = { id: "u-res", email: "researcher@example.org", name: "Ravi Researcher", role: "RESEARCHER" };

async function signInAs(page: Page, account: Account) {
  await page.route("**/api/**", async (route: Route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api/, "");
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/me") return json(account);
    if (path === "/session/adopt") return json({ ok: true });
    if (path.includes("unread")) return json({ unreadCount: 0 });
    if (path.includes("count")) return json({ count: 0, total: 0 });
    // Any list a page asks for comes back empty: these specs read the chrome, not the page.
    return json({ items: [], total: 0, page: 1, pageSize: 20, pages: 0 });
  });
  await page.addInitScript(() => window.localStorage.setItem("field_repo_token", "test-token"));
}

const bar = (page: Page) => page.getByTestId("bottom-island-nav");
const header = (page: Page) => page.getByTestId("app-search-header");
const islandMenu = (page: Page) => page.getByRole("button", { name: "Toggle navigation menu" });
const slotWords = (page: Page) =>
  bar(page)
    .locator(":scope > div > div")
    .evaluateAll((slots) => slots.map((slot) => (slot as HTMLElement).innerText.trim()));

async function open(page: Page, account: Account, path = "/guide") {
  await signInAs(page, account);
  await page.goto(path);
  await expect(bar(page)).toBeVisible({ timeout: 60_000 });
}

async function tallPage(page: Page) {
  await page.evaluate(() => {
    document.body.style.minHeight = "4000px";
  });
}

test.describe.configure({ timeout: 180_000 });

for (const size of [
  { width: 390, height: 844 },
  { width: 820, height: 1180 }
]) {
  test.describe(`at ${size.width}×${size.height}`, () => {
    test.use({ viewport: size, hasTouch: true });

    test("an admin: the header and Home, Tour, Record, Browse, More replace the island", async ({ page }) => {
      await open(page, ADMIN);
      await expect(header(page)).toBeVisible();
      await expect(islandMenu(page)).toBeHidden();
      expect(await slotWords(page)).toEqual(["Home", "Tour", "Record", "Browse", "More"]);
      await expect(bar(page).getByRole("button", { name: "Open menu" })).toBeVisible();
      await expect(bar(page).getByRole("link", { name: "Walkthrough" })).toHaveAttribute("aria-current", "page");
      const box = (await bar(page).boundingBox())!;
      if (size.width >= 640) expect(box.width).toBeLessThanOrEqual(560.5);
      // 48px slots + 6px padding + 1px border, each side.
      expect(Math.round(box.height)).toBe(62);
    });

    test("an admin's More sheet lists Admin and Account", async ({ page }) => {
      await open(page, ADMIN);
      const more = bar(page).getByRole("button", { name: /^More/ });
      await more.click();
      const sheet = page.getByRole("dialog", { name: "More" });
      await expect(sheet).toBeVisible();
      await expect(sheet.getByRole("heading", { level: 3 })).toHaveText(["Admin", "Account"]);
      await page.keyboard.press("Escape");
      await expect(sheet).toBeHidden();
      await expect(more).toBeFocused();
    });

    test("a researcher sees five slots and no More", async ({ page }) => {
      await open(page, RESEARCHER);
      expect(await slotWords(page)).toEqual(["Home", "Tour", "Record", "Browse", "Account"]);
      await expect(bar(page).getByRole("button", { name: /^More/ })).toHaveCount(0);
    });

    test("the header hides after 200px down and returns after 10px up", async ({ page }) => {
      await open(page, RESEARCHER);
      await tallPage(page);
      const frame = page.locator(".app-search-header-frame");
      await expect(frame).toHaveAttribute("data-shown", "true");
      await page.mouse.wheel(0, 200);
      await expect(frame).toHaveAttribute("data-shown", "false");
      await page.mouse.wheel(0, -10);
      await expect(frame).toHaveAttribute("data-shown", "true");
    });

    test("the header opens search, and the bar hides while it is open", async ({ page }) => {
      await open(page, RESEARCHER);
      await header(page).getByRole("button", { name: "Field Repository. Search" }).click();
      const panel = page.getByTestId("app-search-panel");
      await expect(panel).toBeVisible();
      await expect(bar(page)).toBeHidden();
      await page.keyboard.press("Escape");
      await expect(panel).toBeHidden();
      await expect(bar(page)).toBeVisible();
    });

    test("the menu sheet ends above the bar, and the header closes it to search", async ({ page }) => {
      await open(page, ADMIN);
      await bar(page).getByRole("button", { name: "Open menu" }).click();
      const sheet = page.locator(".nav-sheet");
      await expect(sheet).toBeVisible();
      await expect(sheet.getByRole("button", { name: /log ?out/i })).toBeVisible();
      const sheetBox = (await sheet.boundingBox())!;
      const barBox = (await bar(page).boundingBox())!;
      expect(sheetBox.y + sheetBox.height).toBeLessThanOrEqual(barBox.y);
      await header(page).getByRole("button", { name: "Field Repository. Search" }).click();
      await expect(page.getByTestId("app-search-panel")).toBeVisible();
      await expect(sheet).toHaveCount(0);
    });

    test("the bar hides while the on-screen keyboard is open", async ({ page }) => {
      await open(page, RESEARCHER);
      await page.evaluate(() => {
        const viewport = window.visualViewport!;
        Object.defineProperty(viewport, "height", { configurable: true, get: () => window.innerHeight - 300 });
        viewport.dispatchEvent(new Event("resize"));
      });
      await expect(bar(page)).toBeHidden();
    });

    test("the upload tray and toasts clear the bar", async ({ page }) => {
      await open(page, RESEARCHER);
      const lift = await page.evaluate(() => {
        const probe = document.createElement("div");
        probe.style.position = "fixed";
        probe.style.height = "var(--bottom-nav-h, 0px)";
        document.body.appendChild(probe);
        const height = probe.getBoundingClientRect().height;
        probe.remove();
        return height;
      });
      const barBox = (await bar(page).boundingBox())!;
      // The tray's dock sits on `--bottom-nav-h`: at or above the bar's top edge.
      expect(size.height - lift).toBeLessThanOrEqual(barBox.y + 0.5);
    });
  });
}

test.describe("at 1440×900", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("the island is drawn expanded with its admin pill and bell, and neither the header nor the bar", async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto("/guide");
    await expect(islandMenu(page)).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Notifications/ }).first()).toBeVisible();
    const pillSwitch = page.getByTestId("island-admin-view").getByRole("switch", { name: "Admin view" });
    await expect(pillSwitch).toBeVisible();
    const checked = await pillSwitch.getAttribute("aria-checked");
    await page.getByTestId("island-admin-view").click({ position: { x: 12, y: 10 } });
    await expect(pillSwitch).not.toHaveAttribute("aria-checked", checked!);
    await expect(bar(page)).toHaveCount(1);
    await expect(bar(page)).toBeHidden();
    await expect(header(page)).toBeHidden();
    const pill = page.locator(".nav-island-frame header");
    await expect(pill).toHaveClass(/px-4 py-2/);
    const logo = await page
      .locator(".nav-island-frame header a[href='/dashboard'] svg, .nav-island-frame header a[href='/dashboard'] img")
      .first()
      .boundingBox();
    expect(Math.round(logo!.height)).toBe(32);
    const name = page.locator(".nav-island-frame header a[href='/dashboard'] span").first();
    await expect(name).toHaveCSS("font-size", "16px");
  });
});
