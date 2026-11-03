import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const PROFESSOR_EMAIL = process.env.E2E_PROFESSOR_EMAIL ?? "";
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

async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
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

    await expect(page.getByRole("heading", { name: "Transcription provider order" })).toBeVisible();

    const before = await rankedNames(page);
    expect(before).toHaveLength(3);

    await expect(panel.getByText(/Key configured|No key — will be skipped/).first()).toBeVisible();

    const target = before[2];
    const up = panel.getByRole("button", { name: `Move ${target} up`, exact: true });
    await up.click();
    await up.click();

    const reordered = await rankedNames(page);
    expect(reordered[0]).toBe(target);

    await panel.getByRole("button", { name: /save order/i }).click();
    await expect(panel.getByText(/Order saved\./).first()).toBeVisible();

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
