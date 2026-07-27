import { expect, test, type Page, type Request } from "@playwright/test";

import { openCapturedAt, serveAddressReference, stateTheAddress } from "./fixtures/location";

/**
 * Location is mandatory, and it fills itself in — proved from both sides of the permission prompt.
 *
 * SCOPE, after the card was split in two. This file is about the COORDINATE half only: the
 * automatic capture, and the rule that a record cannot be saved without a point somebody stood at,
 * pointed at or typed. The stated address that now sits above it — state, district, village — has
 * its own spec in location-two-groups.spec.ts, including the assertion that matters most, which is
 * that the capture below never touches it.
 *
 * Since the split the coordinate lives inside the collapsed "Captured at" drawer, so these tests
 * open it. On the denied path it opens itself, because that is when there is something to do in it.
 *
 * The two paths here are the whole design, and neither is interesting without the other:
 *
 *   GRANTED. The card takes the fix ITSELF on open, with nothing clicked, and says so with the
 *   accuracy radius attached. That is the feature.
 *
 *   DENIED. The researcher can still finish and save the record. This is the assertion that
 *   actually matters, because a mandatory field whose only supplier can be switched off is a field
 *   that loses a day's fieldwork the first time somebody taps Block — and the whole reason
 *   "mandatory" was defined as "a coordinate must be present" rather than "the GPS must have
 *   worked". Chromium reports PERMISSION_DENIED for a context with no geolocation permission, which
 *   is the same error code a real Block produces, so this exercises the real branch.
 *
 * NOTHING IS WRITTEN. The create request is intercepted and answered locally: the dev server points
 * at a backend on the production database, and a spec that proves a save works by leaving records
 * behind is not a spec anybody can run twice. Intercepting also lets the payload itself be
 * asserted, which is the actual claim — that the location reached the request body.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Bagru, Rajasthan — a block-printing cluster the repository already documents. */
const BAGRU = { latitude: 26.8137, longitude: 75.545 };

async function signIn(page: Page) {
  await serveAddressReference(page);
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * Answer the workshop create locally with a plausible record, and hand back whatever was posted.
 *
 * `**` rather than the configured origin: the API base is inlined into the bundle at build time and
 * a spec that hard-codes it tests one developer's `.env.local` rather than the app.
 */
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

    // Nothing is clicked between here and the assertion. That is the point of the test — and the
    // summary line carries it, so it is readable without the drawer being opened at all.
    const summary = page.locator("details").filter({ hasText: /Captured at/ }).first().locator("summary");
    await expect(summary).toContainText("26.8137", { timeout: 30_000 });
    await expect(summary).toContainText(/±\s*\d/);

    await openCapturedAt(page);
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/);
    await expect(page.locator('input[name="longitude"]')).toHaveValue(/75\.54/);

    // Reported back rather than filled in silently: a researcher at a desk has to be able to notice
    // that the form has just answered a question about where they are.
    await expect(page.getByText(/captured automatically/i)).toBeVisible();

    // The requirement is satisfied, so the coordinate boxes raise no complaint.
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
  // No geolocation permission: every request is answered PERMISSION_DENIED, exactly as a browser
  // whose user pressed Block does.
  test.use({ permissions: [] });

  test("the refusal is explained, the save is blocked, and the fallback completes it", async ({ page }) => {
    const seen = await interceptWorkshopCreate(page);
    await signIn(page);
    await page.goto("/workshops");

    // 1. The card says what happened and what to do instead, rather than sitting there empty.
    await expect(page.getByText(/blocking location/i)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Pick on map/i).first()).toBeVisible();
    await expect(page.locator('input[name="latitude"]')).toHaveValue("");

    // 2. With no coordinate the record cannot be saved — and it is stopped in the browser, before a
    //    request exists, with a sentence that names the way out.
    await page.locator('input[name="title"]').fill("E2E denied-permission check");
    await page.locator('input[name="place"]').fill("Bagru");
    await stateTheAddress(page);
    await page.getByRole("button", { name: /create workshop/i }).click();
    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", false);
    const complaint = await page
      .locator('input[name="latitude"]')
      .evaluate((input: HTMLInputElement) => input.validationMessage);
    expect(complaint).toContain("A location is required");
    // The sentence has to name the way out, not just the rule — see LOCATION_REQUIRED_MESSAGE.
    expect(complaint).toContain("Pick on map");
    expect(seen).toHaveLength(0);

    // 3. The fallback. A coordinate the researcher supplied satisfies the requirement exactly as a
    //    satellite fix does — this is the assertion the whole design rests on, so it is made against
    //    a real save rather than against the validity flag alone.
    await page.locator('input[name="latitude"]').fill(String(BAGRU.latitude));
    await page.locator('input[name="longitude"]').fill(String(BAGRU.longitude));
    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", true);

    await page.getByRole("button", { name: /create workshop/i }).click();
    await expect.poll(() => seen.length, { timeout: 30_000 }).toBe(1);
    const body = seen[0].postDataJSON() as { location?: { latitude: number } };
    expect(body.location?.latitude).toBeCloseTo(BAGRU.latitude, 3);
  });
});
