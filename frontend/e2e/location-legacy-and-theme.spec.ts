import { expect, test, type Page } from "@playwright/test";

import { openCapturedAt, serveAddressReference } from "./fixtures/location";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const ARTISAN_WITHOUT_LOCATION = process.env.E2E_LEGACY_ARTISAN_ID ?? "cmqlz7eom009uejb8rq6gm8a9";
const ARTISAN_WITH_LOCATION = process.env.E2E_LOCATED_ARTISAN_ID ?? "cmqlz12cd007sejb8srzdvrak";

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

async function signIn(page: Page) {
  await serveAddressReference(page);
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

test.describe("Location on records that predate the rule", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("a record with no stored location stays saveable, and is not stamped with the editor's own", async ({
    page
  }) => {
    await signIn(page);
    await page.goto(`/artisans/${ARTISAN_WITHOUT_LOCATION}/edit`);

    await openCapturedAt(page);
    const latitude = page.locator('input[name="latitude"]');
    await expect(latitude).toBeVisible({ timeout: 30_000 });

    await page.waitForTimeout(6_000);
    await expect(latitude).toHaveValue("");
    await expect(page.getByText(/Device location:/i)).toHaveCount(0);

    await expect(latitude).toHaveJSProperty("validity.valid", true);
    await expect(latitude).not.toHaveAttribute("required", /.*/);
    await expect(page.getByText(/Coordinates optional/i)).toBeVisible();

    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="state"]')).not.toHaveAttribute("required", /.*/);
    await expect(page.locator('input[name="district"]')).not.toHaveAttribute("required", /.*/);
  });

  test("a record that has one keeps it, and cannot be emptied", async ({ page }) => {
    await signIn(page);
    await page.goto(`/artisans/${ARTISAN_WITH_LOCATION}/edit`);

    await openCapturedAt(page);
    const latitude = page.locator('input[name="latitude"]');
    await expect(latitude).not.toHaveValue("", { timeout: 30_000 });
    await expect(latitude).toHaveValue(/^22\./);

    await latitude.fill("");
    await expect(latitude).toHaveJSProperty("validity.valid", false);
  });
});

test.describe("Location card in the dark theme, at 360px", () => {
  test.use({ permissions: [], viewport: { width: 360, height: 780 }, colorScheme: "dark" });

  test("the refusal notice is legible and the card does not overflow", async ({ page }) => {
    await signIn(page);
    await page.emulateMedia({ colorScheme: "dark" });
    await page.goto("/workshops");

    const notice = page.getByText(/Location is blocked/i);
    await expect(notice).toBeVisible({ timeout: 30_000 });

    const painted = await notice.evaluate((element) => {
      const panel = element.closest("div");
      const style = panel ? getComputedStyle(panel) : null;
      return { background: style?.backgroundColor ?? "", colour: style?.color ?? "" };
    });
    expect(painted.background).not.toBe("rgba(0, 0, 0, 0)");
    expect(painted.background).not.toBe("transparent");

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
