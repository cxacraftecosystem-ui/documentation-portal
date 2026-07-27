import { expect, test, type Page } from "@playwright/test";

/**
 * The transcription provider ranking on /settings/api-keys.
 *
 * This spec exists because the control was reported missing twice while the code for it was sitting
 * in the repository. It rendered only for MASTER_ADMIN, and `/api/settings` — the endpoint it read —
 * was master-admin too, so the ADMIN account the repository is actually run from could neither see
 * it nor load it. "It is there" and "you can use it" were two different claims, and only the first
 * was true. So the assertions here are deliberately about the second: an ADMIN, not a master admin,
 * signs in, reorders, and the new order is still there after a full reload.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const PROFESSOR_EMAIL = process.env.E2E_PROFESSOR_EMAIL ?? "";
/**
 * Where the API this run should talk to actually lives. The dev server's API base is inlined at
 * build time, so pointing a running dev server somewhere else means restarting it; instead, when
 * E2E_API_ORIGIN is set, every API call the page makes is re-issued against it. The page, the
 * component and the HTTP round trip are all real — only the destination is rewritten.
 */
const API_ORIGIN = process.env.E2E_API_ORIGIN ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

test.beforeEach(async ({ page }) => {
  if (!API_ORIGIN) return;
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const url = new URL(route.request().url());
      const response = await route.fetch({ url: `${API_ORIGIN}${url.pathname}${url.search}` });
      await route.fulfill({ response });
    }
  );
});

/**
 * Sign in, defensively.
 *
 * `fill()` can land before React has hydrated the controlled inputs, in which case hydration wipes
 * what was typed and the form posts two empty strings — which the API answers with a 422 that looks
 * nothing like a test bug. So the values are re-checked immediately before the click.
 */
async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  // Hydration is the whole reason for the care below: typing into a not-yet-hydrated controlled
  // input is undone the moment React takes over.
  await page.waitForLoadState("networkidle").catch(() => {});
  const emailBox = page.getByPlaceholder("Enter your email");
  const passwordBox = page.getByPlaceholder("Enter your password");
  const button = page.getByRole("button", { name: /sign in/i });
  await expect(button).toBeEnabled();

  await expect(async () => {
    await emailBox.fill(email);
    await passwordBox.fill(password);
    expect(await emailBox.inputValue()).toBe(email);
    expect(await passwordBox.inputValue()).toBe(password);
  }).toPass({ timeout: 30_000 });

  await button.click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * Admin view defaults to OFF for a plain ADMIN (only the master admin opens in it), and every admin
 * route is behind that toggle — so without this the page under test never mounts and the spec would
 * be asserting against AppShell's lock panel. Set the same preference the toggle writes.
 */
async function enableAdminView(page: Page) {
  const id = await page.evaluate(() => {
    const token = window.localStorage.getItem("field_repo_token") ?? "";
    try {
      return String(JSON.parse(atob(token.split(".")[1] ?? "")).sub ?? "");
    } catch {
      return "";
    }
  });
  expect(id, "expected a subject claim in the stored JWT after sign-in").toBeTruthy();
  await page.evaluate((sub) => window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on"), id);
}

/** The provider names in the order they are ranked on screen. */
async function rankedNames(page: Page): Promise<string[]> {
  const rows = page.locator('[data-testid="provider-order-list"] li');
  await expect(rows).toHaveCount(3);
  const names: string[] = [];
  for (let index = 0; index < 3; index += 1) {
    names.push(((await rows.nth(index).locator("span.font-semibold").first().textContent()) ?? "").trim());
  }
  return names;
}

test.describe("transcription provider ranking", () => {
  test("an ADMIN can reorder it, and the order survives a reload", async ({ page }) => {
    await signIn(page, EMAIL, PASSWORD);
    await enableAdminView(page);

    await page.goto("/settings/api-keys");
    const panel = page.getByTestId("provider-order-panel");
    await expect(panel).toBeVisible();

    // The account under test is an ADMIN, not the master admin: it must get the ranking and NOT the
    // key list, which stays master-admin-only.
    await expect(page.getByRole("heading", { name: "Transcription provider order" })).toBeVisible();

    const before = await rankedNames(page);
    expect(before).toHaveLength(3);

    // Requirement: each provider's configured state is visible, not inferred.
    await expect(panel.getByText(/Key configured|No key — will be skipped/).first()).toBeVisible();

    // Move whichever provider is last up to the top, using the KEYBOARD affordance only. Pressing
    // the button by its accessible name is exactly what a screen-reader user does.
    const target = before[2];
    const up = panel.getByRole("button", { name: `Move ${target} up`, exact: true });
    await up.click();
    await up.click();

    const reordered = await rankedNames(page);
    expect(reordered[0]).toBe(target);

    await panel.getByRole("button", { name: /save order/i }).click();
    await expect(panel.getByText(/Saved\. The next transcription job uses this order/)).toBeVisible();

    // The real assertion: a full reload, which re-reads the server rather than any local state.
    await page.reload();
    await expect(page.getByTestId("provider-order-panel")).toBeVisible();
    const afterReload = await rankedNames(page);
    expect(afterReload).toEqual(reordered);
    expect(afterReload[0]).toBe(target);
  });

  test("the ranking is reachable by drag and drop too", async ({ page }) => {
    await signIn(page, EMAIL, PASSWORD);
    await enableAdminView(page);
    await page.goto("/settings/api-keys");

    const panel = page.getByTestId("provider-order-panel");
    await expect(panel).toBeVisible();
    const before = await rankedNames(page);

    const rows = page.locator('[data-testid="provider-order-list"] li');
    await rows.nth(2).dragTo(rows.nth(0));

    const after = await rankedNames(page);
    expect(after[0]).toBe(before[2]);
  });

  test("a PROFESSOR is refused", async ({ page }) => {
    test.skip(!PROFESSOR_EMAIL, "Set E2E_PROFESSOR_EMAIL to run the negative-role check.");
    await signIn(page, PROFESSOR_EMAIL, PASSWORD);
    await page.goto("/settings/api-keys");
    await expect(page.getByTestId("provider-order-panel")).toHaveCount(0);
    await expect(page.getByText(/Admin access required/i)).toBeVisible();
  });
});
