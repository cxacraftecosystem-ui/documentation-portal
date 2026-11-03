import { expect, test, type Locator, type Page } from "@playwright/test";

import { fieldSelect, serveAddressReference } from "./fixtures/location";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

const STATE_COUNT = 36;

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

async function breakAddressReference(page: Page) {
  await page.route("**/reference/address", (route) => route.abort("failed"));
}

async function readOptions(page: Page, field: string): Promise<string[]> {
  await fieldSelect(page, field).click();
  const panel = page.locator("[data-anchored-popover]").last();
  await expect(panel.getByRole("option").first()).toBeVisible();
  const labels = await panel.getByRole("option").allTextContents();
  await page.keyboard.press("Escape");
  return labels.map((entry) => entry.trim());
}

function isValid(input: Locator) {
  return input.evaluate((node) => (node as HTMLInputElement).checkValidity());
}

test.describe("Location: the state list with no connection", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("the required State dropdown is still answerable when /reference/address fails", async ({ page }) => {
    await signIn(page);
    await breakAddressReference(page);
    await page.goto("/artisans/new");

    const state = page.locator('input[name="state"]');
    await expect(state).toBeAttached();
    await expect(state).toHaveAttribute("required", "");
    expect(await isValid(state)).toBe(false);

    const offered = await readOptions(page, "State");
    expect(offered).toHaveLength(STATE_COUNT + 1);
    expect(offered[0]).toBe("Select state");
    expect(offered).toEqual(expect.arrayContaining(["Rajasthan", "West Bengal", "Ladakh", "Lakshadweep"]));

    await fieldSelect(page, "State").click();
    await page.locator("[data-anchored-popover]").last().getByRole("option", { name: "Rajasthan", exact: true }).click();
    await expect(state).toHaveValue("Rajasthan");
    expect(await isValid(state)).toBe(true);
  });

  test("the district stands down, and says why, rather than blocking the save", async ({ page }) => {
    await signIn(page);
    await breakAddressReference(page);
    await page.goto("/artisans/new");

    await fieldSelect(page, "State").click();
    await page.locator("[data-anchored-popover]").last().getByRole("option", { name: "Rajasthan", exact: true }).click();

    const district = page.locator('input[name="district"]');
    await expect(district).not.toHaveAttribute("required", /.*/);
    expect(await isValid(district)).toBe(true);
    await expect(page.getByText(/Districts need a connection/i)).toBeVisible();
  });

  test("the bundled list is exactly the list the API serves", async ({ page }) => {
    await signIn(page);

    await breakAddressReference(page);
    await page.goto("/artisans/new");
    const bundled = await readOptions(page, "State");

    await page.unroute("**/reference/address");
    await serveAddressReference(page);
    await page.goto("/artisans/new");
    const served = await readOptions(page, "State");

    expect(served).toHaveLength(STATE_COUNT + 1);
    expect([...bundled].sort()).toEqual([...served].sort());
  });
});
