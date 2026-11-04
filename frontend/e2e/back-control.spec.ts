import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const FORM_PAGES = [
  { name: "New artisan", path: "/artisans/new" },
  { name: "New product", path: "/products/new" },
  { name: "New tool", path: "/tools/new" },
  { name: "Crafts", path: "/crafts" },
  { name: "Workshops", path: "/workshops" }
];

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

async function makeDirty(page: Page) {
  const input = page
    .locator('form input[name][type="text"]:visible, form input[name]:not([type]):visible')
    .first();
  await input.waitFor({ state: "visible" });
  await input.fill("E2E unsaved-changes probe");
  await expect(input).toHaveValue("E2E unsaved-changes probe");
}

test.describe("Back control", () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page);
  });

  for (const target of FORM_PAGES) {
    test(`${target.name} shows exactly one back control`, async ({ page }) => {
      await page.goto(target.path);

      await expect(page.getByRole("button", { name: "Go back" })).toHaveCount(1);

      await expect(page.getByRole("button", { name: "Back", exact: true })).toHaveCount(0);
    });

    test(`${target.name} arrow prompts before discarding unsaved work`, async ({ page }) => {
      await page.goto(target.path);
      await makeDirty(page);

      const arrow = page.getByRole("button", { name: "Go back" });
      const dialog = page.getByRole("alertdialog").filter({ hasText: "Unsaved changes" });

      await arrow.click();
      await expect(dialog).toBeVisible();

      await dialog.getByRole("button", { name: "Keep editing" }).click();
      await expect(dialog).toBeHidden();
      expect(new URL(page.url()).pathname).toBe(target.path);

      await arrow.click();
      await expect(dialog).toBeVisible();
      await dialog.getByRole("button", { name: "Discard" }).click();
      await expect(dialog).toBeHidden();
      await expect
        .poll(() => new URL(page.url()).pathname, { timeout: 15_000 })
        .not.toBe(target.path);
    });
  }

  test("a clean form leaves immediately, with no prompt", async ({ page }) => {
    await page.goto("/artisans/new");
    await page.getByRole("button", { name: "Go back" }).click();
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect.poll(() => new URL(page.url()).pathname).not.toBe("/artisans/new");
  });
});
