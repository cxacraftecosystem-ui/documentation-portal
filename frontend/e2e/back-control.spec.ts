import { expect, test, type Page } from "@playwright/test";

/**
 * There is ONE back control per page: the round arrow in `PageHeader`.
 *
 * This spec exists because the opposite kept shipping. Forms used to carry their own rounded
 * "Back" pill, because the unsaved-changes prompt needed to live where the form's `dirty` flag
 * was, and the header's arrow could not reach it. So every form page showed two back controls
 * stacked a few pixels apart — and the one that looked primary, the arrow, was the one that
 * discarded work silently. It was reported four separate times, and each attempted fix removed a
 * pill somewhere without moving the prompt, or moved the prompt without removing every pill.
 *
 * Hence two assertions per page, not one. Counting the arrow is not enough (a stray pill still
 * passes) and finding no pill is not enough (an arrow that skips the prompt still passes). Both
 * must hold together, on every page that can hold unsaved work.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Every route that renders a form capable of holding unsaved work behind a page header. */
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

/**
 * Type into the form so it becomes dirty.
 *
 * Requires `[name]`, which is load-bearing rather than incidental. The first visible text input on
 * most of these pages belongs to the workshop picker's type-to-search box, which is deliberately
 * unnamed and deliberately does NOT dirty the form — searching for a workshop changes nothing, so
 * prompting on the way out would be a false alarm. Filling it and expecting a prompt tests the
 * wrong thing and fails against correct code. A named input is a real field.
 */
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

      // The round arrow, identified by its accessible name.
      await expect(page.getByRole("button", { name: "Go back" })).toHaveCount(1);

      // And no rounded pill. An exact-name match, so the footer's "Cancel" and any legitimate
      // "Back to artisans" link are untouched — it is specifically a control labelled just "Back"
      // that must not exist.
      await expect(page.getByRole("button", { name: "Back", exact: true })).toHaveCount(0);
    });

    test(`${target.name} arrow prompts before discarding unsaved work`, async ({ page }) => {
      await page.goto(target.path);
      await makeDirty(page);

      const arrow = page.getByRole("button", { name: "Go back" });
      const dialog = page.getByRole("alertdialog").filter({ hasText: "Unsaved changes" });

      // The whole point of the fix: the arrow raises the prompt instead of navigating away.
      await arrow.click();
      await expect(dialog).toBeVisible();

      // "Keep editing" must leave the researcher exactly where they were, work intact.
      await dialog.getByRole("button", { name: "Keep editing" }).click();
      await expect(dialog).toBeHidden();
      expect(new URL(page.url()).pathname).toBe(target.path);

      // And "Discard" must actually perform the navigation that was asked for — a prompt that
      // swallows the intent is its own bug.
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
    // The guard must not fire when there is nothing to lose, or it becomes a nag users learn to
    // dismiss without reading — which is how a real unsaved-changes warning stops working.
    await page.goto("/artisans/new");
    await page.getByRole("button", { name: "Go back" }).click();
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect.poll(() => new URL(page.url()).pathname).not.toBe("/artisans/new");
  });
});
