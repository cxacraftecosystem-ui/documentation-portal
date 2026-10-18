import { expect, test, type Page } from "@playwright/test";

/**
 * A meter pill on an artisan's page opens the edit form AT the missing field (2026-10-18).
 *
 * READ-ONLY: the dev server may point at real data, so nothing is written. The first artisan the
 * list hands back is opened, and its GET is answered with that artisan minus its craft start date
 * and with the block the server would then send — the page under test is the client's, not the
 * server's arithmetic (which `backend/tests/test_record_completeness.py` holds).
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

test("clicking the craft start date pill focuses that field on the edit form", async ({ page }) => {
  await signIn(page);
  const listed = page.waitForResponse((response) => /\/api\/artisans\?/.test(response.url()) && response.ok());
  await page.goto("/artisans");
  const artisan = ((await (await listed).json()).items ?? [])[0] as { id: string } | undefined;
  test.skip(!artisan, "no artisan visible to this account");

  const block = {
    rulesVersion: 1,
    filled: 6,
    total: 7,
    percent: 85,
    requiredMissing: 1,
    missing: [{ key: "craftStartDate", label: "Craft start date", words: "craft start date", level: "required" }]
  };
  await page.route(new RegExp(`/api/artisans/${artisan!.id}$`), async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, craftStartDate: null, completeness: block } });
  });

  await page.goto(`/artisans/${artisan!.id}`);
  const meter = page.locator("[data-completeness-meter]").first();
  await expect(meter).toContainText("1 field left: craft start date");
  await meter.getByRole("link", { name: "Craft start date" }).click();
  await expect(page).toHaveURL(new RegExp(`/artisans/${artisan!.id}/edit\\?focus=craftStartDate`));
  await expect
    .poll(() => page.evaluate(() => Boolean(document.activeElement?.closest('[data-field="craftStartDate"]'))), { timeout: 15_000 })
    .toBe(true);
});
