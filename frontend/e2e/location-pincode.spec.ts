import { expect, test, type Page } from "@playwright/test";

import { openCapturedAt, serveAddressReference } from "./fixtures/location";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

const RURAL_UTTARAKHAND = { latitude: 30.61, longitude: 77.92 };

async function signIn(page: Page) {
  await serveAddressReference(page);
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

function stateValue(page: Page) {
  return page.locator('input[name="state"]');
}

async function chooseState(page: Page, name: string) {
  await page.locator('input[name="state"]').locator("xpath=preceding-sibling::div[1]//button").click();
  await page.getByRole("option", { name, exact: true }).click();
}

test.describe("Location pincode", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("accepting a second place's address never leaves the first place's pincode behind", async ({
    page,
    context
  }) => {
    await signIn(page);
    await page.goto("/artisans/new");
    await openCapturedAt(page);

    const pincode = page.locator('input[name="pincode"]');
    const accept = page.getByRole("button", { name: /Yes, use this/ });

    await context.setGeolocation(BAGRU);
    await page.getByRole("button", { name: "Use current GPS" }).click();
    await expect(page.getByText(/This device is in/)).toBeVisible({ timeout: 30_000 });
    await expect(pincode).toHaveValue("");
    await accept.click();
    await expect(pincode).toHaveValue("303007");
    await expect(stateValue(page)).toHaveValue("Rajasthan");

    await context.setGeolocation(RURAL_UTTARAKHAND);
    await page.getByRole("button", { name: "Use current GPS" }).click();
    await expect(page.getByText(/This device is in.*Uttarakhand/)).toBeVisible({ timeout: 30_000 });
    await accept.click();
    await expect(stateValue(page)).toHaveValue("Uttarakhand");
    await expect(pincode).toHaveValue("");
  });

  test("a fix too coarse to pick a district offers nothing at all", async ({ page, context }) => {
    await signIn(page);
    await page.goto("/artisans/new");
    await openCapturedAt(page);

    await context.setGeolocation({ ...BAGRU, accuracy: 14_000 });
    await page.getByRole("button", { name: "Use current GPS" }).click();

    await expect(page.getByText(/choose the district yourself/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/±14\.0 km/).first()).toBeVisible();
    await expect(page.getByText(/±14\.0 km/)).toHaveCount(3);
    await expect(page.getByText(/This device is in/)).toHaveCount(0);
    await expect(stateValue(page)).toHaveValue("");
    await expect(page.locator('input[name="pincode"]')).toHaveValue("");
  });

  test("a pincode from the wrong postal zone is named, not silently kept", async ({ page }) => {
    await signIn(page);
    await page.goto("/artisans/new");
    await openCapturedAt(page);

    await chooseState(page, "Uttarakhand");
    await page.locator('input[name="pincode"]').fill("303007");

    await expect(page.getByText(/isn’t a Uttarakhand pincode/i)).toBeVisible();
  });
});
