import { expect, test, type Page, type Request } from "@playwright/test";

import { fieldSelect, pick, serveAddressReference } from "./fixtures/location";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const SHOT_DIR = process.env.SHOT_DIR ?? "test-results/location-two-groups";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

const LEGACY_ARTISAN = "cmqj4o5rc0079kb592tclmly4";

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

async function stubGeocoder(page: Page) {
  const answers: Record<string, Array<{ id: string; text: string }>> = {
    "75": [
      { id: "region.1", text: "Rajasthan" },
      { id: "subregion.1", text: "Jaipur District" },
      { id: "county.1", text: "Sanganer Tehsil" },
      { id: "place.1", text: "Bagru" },
      { id: "postal_code.1", text: "303007" }
    ],
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

    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/, { timeout: 30_000 });
    await expect(page.locator('input[name="longitude"]')).toHaveValue(/75\.54/);

    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="district"]')).toHaveValue("");
    await expect(page.locator('input[name="village"]')).toHaveValue("");

    await expect(provenanceGroup(page).locator("summary")).toContainText("26.8137, 75.5450");
    await expect(provenanceGroup(page).locator("summary")).toContainText("±18 m");
    await expect(provenanceGroup(page).locator("summary")).toContainText("where this device was");

    await provenanceGroup(page).getByText("Captured at").click();
    await expect(provenanceGroup(page).getByText(/Device location:/i)).toBeVisible();

    const offer = statedGroup(page).getByText(/This device is in/);
    await expect(offer).toBeVisible();
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
    await expect(page.locator('input[name="pincode"]')).toHaveValue("303007");
    await expect(statedGroup(page).getByText(/This device is in/)).toHaveCount(0);

    await page.screenshot({ path: `${SHOT_DIR}/light-accepted.png`, fullPage: false });
  });

  test("declining leaves the address empty for the person who knows it", async ({ page }) => {
    await openArtisanForm(page);
    await expect(statedGroup(page).getByText(/This device is in/)).toBeVisible({ timeout: 30_000 });

    await statedGroup(page).getByRole("button", { name: /No, the artisan is elsewhere/ }).click();
    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="district"]')).toHaveValue("");
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/);
  });
});

test.describe("A fix too coarse to name a district", () => {
  test.use({ permissions: ["geolocation"], geolocation: { ...BAGRU, accuracy: 2506 } });

  test("offers nothing, and says why", async ({ page }) => {
    await openArtisanForm(page);
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.813/, { timeout: 30_000 });

    await expect(statedGroup(page).getByText(/choose the district yourself/)).toBeVisible();
    await expect(statedGroup(page).getByText(/±2\.5 km/)).toBeVisible();
    await expect(statedGroup(page).getByText(/This device is in/)).toHaveCount(0);
    await expect(page.locator('input[name="accuracy"]')).toHaveValue(/2506/);

    await page.screenshot({ path: `${SHOT_DIR}/light-coarse-fix.png`, fullPage: false });
  });
});

test.describe("Permission denied", () => {
  test.use({ permissions: [] });

  test("the researcher can still complete the record and save it", async ({ page }) => {
    const seen = await interceptCreate(page, "workshops");
    await serveAddressReference(page);
    await stubGeocoder(page);
    await signIn(page);
    await page.goto("/workshops");
    await expect(page.getByRole("heading", { name: /Location of this record/ })).toBeVisible({ timeout: 30_000 });

    await expect(page.getByText(/Location is blocked/i)).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('input[name="latitude"]')).toBeVisible();
    await expect(page.locator('input[name="latitude"]')).toHaveValue("");

    await expect(fieldSelect(page, "District")).toBeDisabled();
    await pick(page, "State", "Rajasthan");
    await expect(fieldSelect(page, "District")).toBeEnabled();
    await pick(page, "District", "Jaipur");
    await page.locator('input[name="village"]').fill("Bagru");

    await page.locator('input[name="title"]').fill("E2E denied-permission check");
    await page.locator('input[name="place"]').fill("Bagru");

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

    const flag = page.getByText(/Check the place\./);
    await expect(flag).toBeVisible({ timeout: 30_000 });
    await expect(flag.locator("..")).toContainText("Paschim Medinipur, West Bengal");
    await expect(flag.locator("..")).toContainText("Kutch, Gujrat");

    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="district"]')).toHaveValue("");
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/22\.313/);

    await page.screenshot({ path: `${SHOT_DIR}/light-review-flag.png`, fullPage: true });
  });
});

test.describe("Both groups, both themes, at 360px", () => {
  test.use({ permissions: ["geolocation"], geolocation: { ...BAGRU, accuracy: 18 }, viewport: { width: 360, height: 900 } });

  for (const theme of ["light", "dark"] as const) {
    test(`the two groups stay distinguishable in ${theme}`, async ({ page }) => {
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

      await expect(provenanceGroup(page)).not.toHaveAttribute("open", /.*/);
      await expect(provenanceGroup(page).getByText(/where this device was, recorded automatically/)).toBeVisible();

      const contrasts = await page.locator("[data-location-suggestion]").evaluate((chip) => {
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
      await expect(provenanceGroup(page).getByText(/Not used as the address/)).toBeVisible();
      await page.screenshot({ path: `${SHOT_DIR}/${theme}-expanded.png`, fullPage: true });
    });
  }
});
