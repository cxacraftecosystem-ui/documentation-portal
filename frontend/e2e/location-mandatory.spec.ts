import { expect, test, type Page, type Request } from "@playwright/test";

import { openCapturedAt, serveAddressReference, stateTheAddress } from "./fixtures/location";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

async function signIn(page: Page) {
  await serveAddressReference(page);
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

function interceptWorkshopCreate(page: Page) {
  const seen: Request[] = [];
  return page
    .route("**/api/workshops", async (route, request) => {
      if (request.method() !== "POST") return route.fallback();
      seen.push(request);
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          id: "e2e-not-a-real-record",
          title: "E2E",
          place: "E2E",
          status: "PENDING",
          createdAt: new Date().toISOString()
        })
      });
    })
    .then(() => seen);
}

test.describe("Location capture — permission granted", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("the card takes a fix on its own when the form opens, and names the radius", async ({ page }) => {
    await signIn(page);
    await page.goto("/artisans/new");

    const summary = page.locator("details").filter({ hasText: /Captured at/ }).first().locator("summary");
    await expect(summary).toContainText("26.8137", { timeout: 30_000 });
    await expect(summary).toContainText(/±\s*\d/);

    await openCapturedAt(page);
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/);
    await expect(page.locator('input[name="longitude"]')).toHaveValue(/75\.54/);

    await expect(page.getByText(/Device location:/i)).toBeVisible();

    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", true);
  });

  test("a captured location reaches the request body", async ({ page }) => {
    const seen = await interceptWorkshopCreate(page);
    await signIn(page);
    await page.goto("/workshops");

    await openCapturedAt(page);
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/, { timeout: 30_000 });
    await page.locator('input[name="title"]').fill("E2E location check");
    await page.locator('input[name="place"]').fill("Bagru");
    await stateTheAddress(page);
    await page.getByRole("button", { name: /create workshop/i }).click();

    await expect.poll(() => seen.length, { timeout: 30_000 }).toBe(1);
    const body = seen[0].postDataJSON() as { location?: { latitude: number; longitude: number } };
    expect(body.location?.latitude).toBeCloseTo(BAGRU.latitude, 2);
    expect(body.location?.longitude).toBeCloseTo(BAGRU.longitude, 2);
  });
});

test.describe("Location capture — permission denied", () => {
  test.use({ permissions: [] });

  test("the refusal is explained, the save is blocked, and the fallback completes it", async ({ page }) => {
    const seen = await interceptWorkshopCreate(page);
    await signIn(page);
    await page.goto("/workshops");

    await expect(page.getByText(/Location is blocked/i)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Pick on map/i).first()).toBeVisible();
    await expect(page.locator('input[name="latitude"]')).toHaveValue("");

    await page.locator('input[name="title"]').fill("E2E denied-permission check");
    await page.locator('input[name="place"]').fill("Bagru");
    await stateTheAddress(page);
    await page.getByRole("button", { name: /create workshop/i }).click();
    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", false);
    const complaint = await page
      .locator('input[name="latitude"]')
      .evaluate((input: HTMLInputElement) => input.validationMessage);
    expect(complaint).toContain("A location is required");
    expect(complaint).toContain("Pick on map");
    expect(seen).toHaveLength(0);

    await page.locator('input[name="latitude"]').fill(String(BAGRU.latitude));
    await page.locator('input[name="longitude"]').fill(String(BAGRU.longitude));
    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", true);

    await page.getByRole("button", { name: /create workshop/i }).click();
    await expect.poll(() => seen.length, { timeout: 30_000 }).toBe(1);
    const body = seen[0].postDataJSON() as { location?: { latitude: number } };
    expect(body.location?.latitude).toBeCloseTo(BAGRU.latitude, 3);
  });
});
