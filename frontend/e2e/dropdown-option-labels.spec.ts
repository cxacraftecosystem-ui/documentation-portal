import { expect, test, type Locator, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const LOOKS_LIKE_AN_ID = /^[a-z0-9-]{16,}$/i;

function fieldSelect(page: Page, label: string): Locator {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${escaped}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

async function readOptions(page: Page, field: string): Promise<string[]> {
  const trigger = fieldSelect(page, field);
  await expect(trigger).toBeVisible({ timeout: 30_000 });
  await trigger.click();
  const panel = page.locator("[data-anchored-popover]").last();
  await expect(panel.getByRole("option").nth(1)).toBeVisible({ timeout: 30_000 });
  const labels = await panel.getByRole("option").allTextContents();
  await page.keyboard.press("Escape");
  return labels.map((entry) => entry.trim()).filter(Boolean);
}

function expectReadable(labels: string[], separator: string) {
  expect(labels.length).toBeGreaterThan(1);
  expect(labels.filter((label) => LOOKS_LIKE_AN_ID.test(label))).toEqual([]);
  expect(labels.some((label) => label.includes(separator))).toBe(true);
}

test.describe("Dropdown labels", () => {
  test("/questionnaire — the artisans picker reads as a name, craft and place", async ({ page }) => {
    await signIn(page);
    await page.goto("/questionnaire");
    expectReadable(await readOptions(page, "Artisans interviewed"), " - ");
  });

  test("/processes — the required artisan picker reads as a name and place", async ({ page }) => {
    await signIn(page);
    await page.goto("/processes?new=1");
    expectReadable(await readOptions(page, "Artisan"), " · ");
  });

  test("/activity — both the options and the closed trigger read as people", async ({ page }) => {
    await signIn(page);
    await page.goto("/activity");

    const trigger = fieldSelect(page, "Whose activity?");
    const rendered = await trigger
      .waitFor({ state: "visible", timeout: 30_000 })
      .then(() => true)
      .catch(() => false);
    if (!rendered) {
      test.skip(true, "This account oversees nobody, so the picker is not rendered.");
      return;
    }

    await expect(trigger).toHaveText(/^Me — .+/);

    expectReadable(await readOptions(page, "Whose activity?"), " — ");
  });
});
