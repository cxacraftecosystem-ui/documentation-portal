import { expect, test, type Page } from "@playwright/test";

import { openCapturedAt, serveAddressReference } from "./fixtures/location";

/**
 * The two things the mandatory rule must NOT do: lock a researcher out of a record that predates it,
 * and render its warnings in a colour the dark theme swallows.
 *
 * THE LEGACY ROW. Fifteen of the sixteen artisans on this database carry a location. The sixteenth
 * does not, and a rule that refuses to save it is a rule that stops that artisan's phone number
 * ever being corrected — while a rule that DEMANDS one teaches the researcher to satisfy the form
 * with wherever they happen to be sitting, which is worse than the gap it filled. So the card drops
 * the requirement for exactly that record, says why, and does not auto-capture over it.
 *
 * The spec reads a live record and never writes: it asserts the form's own state, and no save is
 * ever submitted.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Giriraj Prasad Chhipa — the one live artisan with no Location row. */
const ARTISAN_WITHOUT_LOCATION = process.env.E2E_LEGACY_ARTISAN_ID ?? "cmqlz7eom009uejb8rq6gm8a9";
/** Sanjay Chhipa — a live artisan that has one, for the opposite assertion. */
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
  // Granted on purpose. The point is that the card declines to capture anyway, not that it cannot.
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("a record with no stored location stays saveable, and is not stamped with the editor's own", async ({
    page
  }) => {
    await signIn(page);
    await page.goto(`/artisans/${ARTISAN_WITHOUT_LOCATION}/edit`);

    await openCapturedAt(page);
    const latitude = page.locator('input[name="latitude"]');
    await expect(latitude).toBeVisible({ timeout: 30_000 });

    // Give the automatic capture longer than it would ever need. It must still not have run: this
    // browser is in Bagru and the artisan was documented in Kolkata.
    await page.waitForTimeout(6_000);
    await expect(latitude).toHaveValue("");
    await expect(page.getByText(/captured automatically/i)).toHaveCount(0);

    // The requirement is off for this record, so the form is not held hostage by it...
    await expect(latitude).toHaveJSProperty("validity.valid", true);
    await expect(latitude).not.toHaveAttribute("required", /.*/);
    // ...and the card explains the exception rather than leaving it to be inferred.
    await expect(page.getByText(/created before a coordinate was required/i)).toBeVisible();

    // The stated address is off too, for the same reason and on the same record: all fifteen live
    // locations have a NULL state and district, and demanding them here would be demanding a guess.
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
    // The stored coordinate, not this browser's. Bagru is 26.8; the record is 22.3.
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

    const notice = page.getByText(/blocking location/i);
    await expect(notice).toBeVisible({ timeout: 30_000 });

    /*
     * The reason this assertion exists: the repo's usual warning pairing is
     * `border-amber-200 bg-amber-50`, and neither shade is in this project's amber ramp
     * (tailwind.config.ts defines 100/500/800 only). Those classes therefore resolve to nothing,
     * leaving dark-brown text on whatever the card is — unreadable on the dark theme. These
     * notices are the only thing standing between a network estimate and a research record, so
     * they are painted with tokens that exist.
     */
    const painted = await notice.evaluate((element) => {
      const panel = element.closest("div");
      const style = panel ? getComputedStyle(panel) : null;
      return { background: style?.backgroundColor ?? "", colour: style?.color ?? "" };
    });
    expect(painted.background).not.toBe("rgba(0, 0, 0, 0)");
    expect(painted.background).not.toBe("transparent");

    // 360px is the narrowest phone this app supports; nothing in the card may push the page sideways.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
