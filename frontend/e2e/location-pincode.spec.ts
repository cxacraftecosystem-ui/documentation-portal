import { expect, test, type Page } from "@playwright/test";

import { openCapturedAt, serveAddressReference } from "./fixtures/location";

/**
 * The pincode offered for a location must belong to THAT location.
 *
 * This spec exists because it did not. The geocoder has no postal code for most rural Indian
 * points — 95% of sampled coordinates in Rajasthan, Uttarakhand and Jammu & Kashmir come back
 * with no `postal_code` feature at all — and the prefill treated "no answer" as "no change". So
 * the second artisan of the day inherited the first artisan's pincode: the state dropdown moved
 * (a region is almost always resolvable), the six digits under it did not, and the record saved
 * with a Rajasthan PIN under Uttarakhand. Nothing in either client or the API objected.
 *
 * REWRITTEN FOR THE TWO-FIELD MODEL, and the bug it guards is now reachable by exactly one door
 * instead of every door. Nothing is auto-filled any more: a fix produces a SUGGESTION the
 * researcher accepts or refuses (see components/forms/LocationFields), so an unaccepted answer can
 * no longer go stale in a box. What remains is the accept path, which has to hand over the WHOLE
 * address of the point it names — including a postal code that is empty — or the same wrong record
 * comes out of the same 95% of points, one click later. That is what the first test drives.
 *
 * The zone check below is the second half and still stands on its own: clearing on a blank answer
 * would not catch a stale code from a point in the SAME state, and the zone digit would not be
 * worth having if nothing could ever reach it.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Bagru, Rajasthan — a block-printing cluster the repository already documents. PIN 303007. */
const BAGRU = { latitude: 26.8137, longitude: 75.545 };

/**
 * Hill country above Kalsi, Uttarakhand. Chosen because the geocoder resolves the REGION here but
 * has no postal code — which is the exact shape of answer that used to leave the previous
 * artisan's pincode standing.
 */
const RURAL_UTTARAKHAND = { latitude: 30.61, longitude: 77.92 };

async function signIn(page: Page) {
  await serveAddressReference(page);
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * The state control is the themed `Dropdown` — a button and a listbox, not a `<select>` — mirrored
 * into a zero-size named input so the form still submits and still validates. Read the mirror,
 * drive the buttons.
 */
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
    // "Use current GPS" lives in the provenance drawer now, and the drawer starts folded.
    await openCapturedAt(page);

    const pincode = page.locator('input[name="pincode"]');
    const accept = page.getByRole("button", { name: /Yes, use this/ });

    await context.setGeolocation(BAGRU);
    await page.getByRole("button", { name: "Use current GPS" }).click();
    // Offered, not applied — the boxes are still empty until the button below is pressed.
    await expect(page.getByText(/This device is in/)).toBeVisible({ timeout: 30_000 });
    await expect(pincode).toHaveValue("");
    await accept.click();
    await expect(pincode).toHaveValue("303007");
    await expect(stateValue(page)).toHaveValue("Rajasthan");

    // Move to a place the geocoder can name but cannot post to, and accept THAT. The state follows;
    // the pincode must not stay behind as Bagru's.
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
    // "Use current GPS" lives in the provenance drawer now, and the drawer starts folded.
    await openCapturedAt(page);

    // Bagru's coordinates with the accuracy a browser reports when it has no satellite lock and
    // falls back to the network. The point is right; the radius covers half the district.
    await context.setGeolocation({ ...BAGRU, accuracy: 14_000 });
    await page.getByRole("button", { name: "Use current GPS" }).click();

    // No offer, because a one-tap Yes over a 14 km circle is exactly as wrong as a silent write.
    await expect(page.getByText(/No district was suggested/)).toBeVisible({ timeout: 30_000 });
    // Three times over: in the notice, on the summary line, and in the capture confirmation. The
    // radius is the whole reason nothing was offered, so it is said wherever it can be read.
    await expect(page.getByText(/±14\.0 km/).first()).toBeVisible();
    await expect(page.getByText(/±14\.0 km/)).toHaveCount(3);
    await expect(page.getByText(/This device is in/)).toHaveCount(0);
    await expect(stateValue(page)).toHaveValue("");
    await expect(page.locator('input[name="pincode"]')).toHaveValue("");
  });

  test("a pincode from the wrong postal zone is named, not silently kept", async ({ page }) => {
    await signIn(page);
    await page.goto("/artisans/new");
    // "Use current GPS" lives in the provenance drawer now, and the drawer starts folded.
    await openCapturedAt(page);

    // Typed by hand, so the geocoder will not touch it — a researcher copying an address off a
    // form is exactly how a wrong-state pincode gets in once the automatic route is closed.
    await chooseState(page, "Uttarakhand");
    await page.locator('input[name="pincode"]').fill("303007");

    await expect(page.getByText(/does not match Uttarakhand/i)).toBeVisible();
  });
});
