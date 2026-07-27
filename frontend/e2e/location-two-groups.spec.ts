import { expect, test, type Page, type Request } from "@playwright/test";

import { fieldSelect, pick, serveAddressReference } from "./fixtures/location";

/**
 * The two-field location model, proved from both sides of the permission prompt.
 *
 * WHAT IS BEING PROVED, and why each of these is a separate test rather than one long one:
 *
 *   1. The automatic capture fills the PROVENANCE group and nothing else. Every one of the fifteen
 *      artisans on the live database carries a Kharagpur coordinate under a Rajasthan, Gujarat,
 *      Uttarakhand or Andhra Pradesh place name, because the only place the form offered for "where
 *      the artisan is" was the field that means "where the device is". This assertion — state,
 *      district and village still empty after a fix lands — is the fix.
 *   2. The geocoder SUGGESTS. A researcher on site accepts in one tap; a researcher at a desk
 *      declines and the artisan's address stays empty for the person who knows it. Neither is
 *      possible if the form has already decided.
 *   3. A coarse fix offers nothing. One live record has a 2,506 m radius, which is a network
 *      estimate wearing a satellite fix's clothes, and a one-tap Yes over a district picked out of
 *      a 2.5 km circle is exactly as wrong as writing it in silently.
 *   4. A researcher whose browser blocks location can still complete and save. A mandatory field
 *      whose only supplier can be switched off is a field that loses a day's fieldwork.
 *   5. A stored record whose coordinates and place disagree is FLAGGED and not touched.
 *
 * NOTHING IS WRITTEN TO PRODUCTION. The dev server points at the live API, so every create is
 * intercepted and answered locally — which also lets the payload itself be asserted, and the
 * payload is the actual claim. The reference list is served from a fixture because the districts
 * are new in this same run and the deployed API has not got them yet; the fixture is the exact
 * output of `app.services.address.address_reference()`, so the contract under test is the real one.
 * The geocoder is stubbed for the same reason a clock is frozen in a test: MapTiler's answer for
 * Bagru is not the thing being tested, and a live reply makes the run depend on a third party.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const SHOT_DIR = process.env.SHOT_DIR ?? "test-results/location-two-groups";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Bagru, Rajasthan — the block-printing cluster three of the fifteen records name in prose. */
const BAGRU = { latitude: 26.8137, longitude: 75.545 };

/**
 * Khatri Ibrahim Isha, read from the live API: place "Kutch, Gujrat", coordinates 22.313, 87.313.
 * Gujarat and West Bengal, on one record, and nobody has ever been asked which one is the artisan.
 */
const LEGACY_ARTISAN = "cmqj4o5rc0079kb592tclmly4";

/**
 * Signed in once, then replayed. The live API rate-limits repeated logins from one address, so a
 * spec that signs in per test ends up testing the throttle. Same idiom as searchable-select.spec.
 */
let session: Record<string, string> | null = null;

async function signIn(page: Page) {
  if (session) {
    await page.addInitScript((entries: Record<string, string>) => {
      for (const [key, value] of Object.entries(entries)) window.localStorage.setItem(key, value);
    }, session);
    return;
  }
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  const captured = await page.evaluate(() => Object.fromEntries(Object.entries(window.localStorage)));
  delete captured.field_repo_preferences;
  session = captured as Record<string, string>;
}

/**
 * MapTiler, answering from a small table keyed by longitude.
 *
 * The shape matters and is copied from the real thing: `features[0]` is a ROAD, and the state and
 * district live in its `context`. That is exactly why a place name taken from `features[0].text` is
 * wrong and why the card reads the context instead. `subregion` is the district; `county` is the
 * tehsil and is present here precisely so a regression that starts reading it would show up as
 * "Sanganer Tehsil" in the suggestion.
 */
async function stubGeocoder(page: Page) {
  const answers: Record<string, Array<{ id: string; text: string }>> = {
    // Bagru
    "75": [
      { id: "region.1", text: "Rajasthan" },
      { id: "subregion.1", text: "Jaipur District" },
      { id: "county.1", text: "Sanganer Tehsil" },
      { id: "place.1", text: "Bagru" },
      { id: "postal_code.1", text: "303007" }
    ],
    // Kharagpur
    "87": [
      { id: "region.2", text: "West Bengal" },
      { id: "subregion.2", text: "Paschim Medinipur" },
      { id: "municipality.2", text: "Kharagpur" }
    ]
  };
  await page.route("https://api.maptiler.com/geocoding/**", (route) => {
    const longitude = new URL(route.request().url()).pathname.split("/").pop()?.split(",")[0] ?? "";
    const context = answers[longitude.slice(0, 2)] ?? [];
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        features: context.length
          ? [{ id: "address.9", text: "NH52", place_type: ["address"], context }]
          : []
      })
    });
  });
}

/** Answer a create locally and hand back whatever was posted. */
function interceptCreate(page: Page, path: string) {
  const seen: Request[] = [];
  return page
    .route(`**/api/${path}`, async (route, request) => {
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

const statedGroup = (page: Page) => page.locator("section").filter({ hasText: /Location of the/ }).first();
const provenanceGroup = (page: Page) => page.locator("details").filter({ hasText: /Captured at/ }).first();

async function openArtisanForm(page: Page) {
  await serveAddressReference(page);
  await stubGeocoder(page);
  await signIn(page);
  await page.goto("/artisans/new");
  await expect(page.getByRole("heading", { name: /Location of the artisan/ })).toBeVisible({ timeout: 30_000 });
}

test.describe("Permission granted — the capture fills provenance and offers the rest", () => {
  test.use({ permissions: ["geolocation"], geolocation: { ...BAGRU, accuracy: 18 } });

  test("the fix lands by itself, and the artisan's address stays empty until a human says so", async ({ page }) => {
    await openArtisanForm(page);

    // Nothing is clicked between here and the assertions. That is the point of the test.
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/, { timeout: 30_000 });
    await expect(page.locator('input[name="longitude"]')).toHaveValue(/75\.54/);

    // THE ASSERTION THIS WHOLE STREAM EXISTS FOR. The device answered; the artisan's address did not
    // move. Fifteen live records exist because the previous version of this card did the opposite.
    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="district"]')).toHaveValue("");
    await expect(page.locator('input[name="village"]')).toHaveValue("");

    // Reported back on the always-visible summary line, radius and time included, because a field
    // that fills itself in and says nothing is the hazard the whole card is built around.
    await expect(provenanceGroup(page).locator("summary")).toContainText("26.8137, 75.5450");
    await expect(provenanceGroup(page).locator("summary")).toContainText("±18 m");
    await expect(provenanceGroup(page).locator("summary")).toContainText("where this device was");

    // ...and named as this device's, not the artisan's, once the panel is opened.
    await provenanceGroup(page).getByText("Captured at").click();
    await expect(provenanceGroup(page).getByText(/This device's location was captured automatically/i)).toBeVisible();
    await expect(provenanceGroup(page).getByText(/That is where you are, not where the artisan is/)).toBeVisible();

    // And offered, not applied.
    const offer = statedGroup(page).getByText(/This device is in/);
    await expect(offer).toBeVisible();
    // `subregion`, not `county`: "Jaipur", never "Sanganer Tehsil".
    await expect(offer).toContainText("Jaipur, Rajasthan");
    await expect(offer).not.toContainText("Tehsil");
    await expect(statedGroup(page).getByRole("button", { name: /Yes, use this/ })).toBeVisible();

    await page.screenshot({ path: `${SHOT_DIR}/light-granted-suggestion.png`, fullPage: false });
  });

  test("one click accepts it, and one click refuses it", async ({ page }) => {
    await openArtisanForm(page);
    await expect(statedGroup(page).getByText(/This device is in/)).toBeVisible({ timeout: 30_000 });

    await statedGroup(page).getByRole("button", { name: /Yes, use this/ }).click();
    await expect(page.locator('input[name="state"]')).toHaveValue("Rajasthan");
    await expect(page.locator('input[name="district"]')).toHaveValue("Jaipur");
    // The pincode rides along with the address it came from, under the same accuracy rule.
    await expect(page.locator('input[name="pincode"]')).toHaveValue("303007");
    // Answered, so the offer is gone rather than sitting there inviting a second click.
    await expect(statedGroup(page).getByText(/This device is in/)).toHaveCount(0);

    await page.screenshot({ path: `${SHOT_DIR}/light-accepted.png`, fullPage: false });
  });

  test("declining leaves the address empty for the person who knows it", async ({ page }) => {
    await openArtisanForm(page);
    await expect(statedGroup(page).getByText(/This device is in/)).toBeVisible({ timeout: 30_000 });

    await statedGroup(page).getByRole("button", { name: /No, the artisan is elsewhere/ }).click();
    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="district"]')).toHaveValue("");
    // The coordinates are untouched by the refusal — it was never a claim about them.
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/);
  });
});

test.describe("A fix too coarse to name a district", () => {
  // 2,506 m is not invented: it is the radius on Kavindra S. H. Bisht's live record.
  test.use({ permissions: ["geolocation"], geolocation: { ...BAGRU, accuracy: 2506 } });

  test("offers nothing, and says why", async ({ page }) => {
    await openArtisanForm(page);
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/, { timeout: 30_000 });

    await expect(statedGroup(page).getByText(/No district was suggested/)).toBeVisible();
    await expect(statedGroup(page).getByText(/±2\.5 km/)).toBeVisible();
    await expect(statedGroup(page).getByText(/This device is in/)).toHaveCount(0);
    // The coordinates are still KEPT — a rough position beats none, and the radius travels with it.
    await expect(page.locator('input[name="accuracy"]')).toHaveValue(/2506/);

    await page.screenshot({ path: `${SHOT_DIR}/light-coarse-fix.png`, fullPage: false });
  });
});

test.describe("Permission denied", () => {
  // No geolocation permission: every request is answered PERMISSION_DENIED, exactly as a browser
  // whose user pressed Block does.
  test.use({ permissions: [] });

  test("the researcher can still complete the record and save it", async ({ page }) => {
    const seen = await interceptCreate(page, "workshops");
    await serveAddressReference(page);
    await stubGeocoder(page);
    await signIn(page);
    await page.goto("/workshops");
    await expect(page.getByRole("heading", { name: /Location of this record/ })).toBeVisible({ timeout: 30_000 });

    // The refusal is explained, and the panel that can fix it is open rather than folded away.
    await expect(page.getByText(/blocking location/i)).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('input[name="latitude"]')).toBeVisible();
    await expect(page.locator('input[name="latitude"]')).toHaveValue("");

    // The stated address needs no device and no network, so it can be answered anyway. Choosing a
    // state fills the district list; before that the district dropdown has nothing to offer.
    await expect(fieldSelect(page, "District")).toBeDisabled();
    await pick(page, "State", "Rajasthan");
    await expect(fieldSelect(page, "District")).toBeEnabled();
    await pick(page, "District", "Jaipur");
    await page.locator('input[name="village"]').fill("Bagru");

    await page.locator('input[name="title"]').fill("E2E denied-permission check");
    await page.locator('input[name="place"]').fill("Bagru");

    // The coordinate is still required, and is still satisfiable by hand.
    await page.getByRole("button", { name: /create workshop/i }).click();
    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", false);
    expect(seen).toHaveLength(0);

    await page.locator('input[name="latitude"]').fill(String(BAGRU.latitude));
    await page.locator('input[name="longitude"]').fill(String(BAGRU.longitude));
    await expect(page.locator('input[name="latitude"]')).toHaveJSProperty("validity.valid", true);

    await page.getByRole("button", { name: /create workshop/i }).click();
    await expect.poll(() => seen.length, { timeout: 30_000 }).toBe(1);
    const body = seen[0].postDataJSON() as {
      location?: { latitude: number; state: string; district: string; village: string };
    };
    expect(body.location?.state).toBe("Rajasthan");
    expect(body.location?.district).toBe("Jaipur");
    expect(body.location?.village).toBe("Bagru");
    expect(body.location?.latitude).toBeCloseTo(BAGRU.latitude, 3);
  });

  test("a district cannot be chosen before its state", async ({ page }) => {
    await openArtisanForm(page);
    await expect(fieldSelect(page, "District")).toBeDisabled();
    await expect(page.getByText("Choose a state first")).toBeVisible();
  });
});

test.describe("A stored record whose coordinates and place disagree", () => {
  test.use({ permissions: [] });

  test("is flagged for review, and nothing is changed", async ({ page }) => {
    await serveAddressReference(page);
    await stubGeocoder(page);
    await signIn(page);
    await page.goto(`/artisans/${LEGACY_ARTISAN}/edit`);

    const flag = page.getByText(/Needs review — the coordinates and the recorded place disagree/);
    await expect(flag).toBeVisible({ timeout: 30_000 });
    await expect(flag.locator("..")).toContainText("Paschim Medinipur, West Bengal");
    await expect(flag.locator("..")).toContainText("Kutch, Gujrat");

    // FLAGGED, NEVER REWRITTEN. The dropdowns are exactly as empty as the database is.
    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="district"]')).toHaveValue("");
    // The coordinates are untouched too — an edit form never re-captures.
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/22\.313/);

    await page.screenshot({ path: `${SHOT_DIR}/light-review-flag.png`, fullPage: true });
  });
});

test.describe("Both groups, both themes, at 360px", () => {
  test.use({ permissions: ["geolocation"], geolocation: { ...BAGRU, accuracy: 18 }, viewport: { width: 360, height: 900 } });

  for (const theme of ["light", "dark"] as const) {
    test(`the two groups stay distinguishable in ${theme}`, async ({ page }) => {
      // The account's server-side preference row lands last and wins (see ThemeProvider), so seeding
      // localStorage alone is overwritten a moment later. Answering /preferences/me is also the
      // read-only choice: it stops the provider PUTting this device's theme to production.
      await page.route("**/preferences/me", async (route) => {
        if (route.request().method() !== "GET") return route.abort();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ theme, reducedMotion: false, largerText: false, highContrast: false })
        });
      });
      await openArtisanForm(page);
      await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
      await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/, { timeout: 30_000 });

      // Collapsed by default when the capture worked: there is nothing to decide in there, and a
      // reader who mistakes it for the address is the failure this card prevents.
      await expect(provenanceGroup(page)).not.toHaveAttribute("open", /.*/);
      await expect(provenanceGroup(page).getByText(/where this device was, recorded automatically/)).toBeVisible();

      /*
       * The suggestion chip, measured rather than looked at.
       *
       * The brand ramp does NOT invert (globals.css says so), while the ink and surface tokens do —
       * so a chip painted with a literal purple and lettered with a themed ink is light-on-light in
       * one theme and unreadable. `CardNotice` carries a comment about the same trap with amber,
       * which is how it is known to be a trap and not a hypothetical. This is the one control in
       * the card that can write an address in a single click, so it has to be legible in both.
       */
      const contrasts = await page.locator("[data-location-suggestion]").evaluate((chip) => {
        // Painted onto a canvas rather than parsed: this project's ramps are authored in OKLCH and
        // Chrome hands `oklch(0.977 0.013 305)` straight back out of getComputedStyle, so a naive
        // "pull the three numbers out" reads the hue angle as a blue channel and reports nonsense.
        const swatch = document.createElement("canvas").getContext("2d")!;
        const luminance = (colour: string) => {
          swatch.clearRect(0, 0, 1, 1);
          swatch.fillStyle = colour;
          swatch.fillRect(0, 0, 1, 1);
          const [r, g, b] = swatch.getImageData(0, 0, 1, 1).data;
          const channel = (value: number) => {
            const sRGB = value / 255;
            return sRGB <= 0.03928 ? sRGB / 12.92 : ((sRGB + 0.055) / 1.055) ** 2.4;
          };
          return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
        };
        const ratio = (front: string, back: string) => {
          const a = luminance(front);
          const b = luminance(back);
          return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        };
        const chipStyle = getComputedStyle(chip);
        return Array.from(chip.querySelectorAll("button")).map((button) => {
          const style = getComputedStyle(button);
          return ratio(style.color, style.backgroundColor);
        }).concat(ratio(chipStyle.color, chipStyle.backgroundColor));
      });
      for (const ratio of contrasts) expect(ratio).toBeGreaterThan(4.5);

      await statedGroup(page).scrollIntoViewIfNeeded();
      await page.screenshot({ path: `${SHOT_DIR}/${theme}-collapsed.png`, fullPage: false });

      await provenanceGroup(page).getByText("Captured at").click();
      await expect(provenanceGroup(page).getByText(/Provenance, not an address/)).toBeVisible();
      await page.screenshot({ path: `${SHOT_DIR}/${theme}-expanded.png`, fullPage: true });
    });
  }
});
