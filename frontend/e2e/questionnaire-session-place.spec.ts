import { expect, test } from "@playwright/test";

import { QuestionnaireFake } from "./support/qv2Fake";

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/questionnaire-session-place";
const WIDTHS = [1440, 834, 390] as const;

test.describe.configure({ mode: "serial" });

for (const width of WIDTHS) {
  test(`a new session fills its place from this device in the Captured at card and its crafts from the artisan at ${width}`, async ({ page, context }) => {
    await page.setViewportSize({ width, height: 1100 });
    await context.grantPermissions(["geolocation"]);
    await context.setGeolocation({ latitude: 26.8105, longitude: 75.5466, accuracy: 12 });
    const fake = new QuestionnaireFake();
    await fake.install(page);
    await page.goto("/questionnaire/sessions/new?artisanId=a1");
    const sheet = page.locator("[data-start-sheet]");
    const place = sheet.locator("[data-session-place]");
    await expect(place.getByText("Captured at", { exact: true })).toBeVisible();
    await expect(place.locator('input[name="latitude"]')).toHaveValue(/^26\.81/, { timeout: 20_000 });
    await expect(place.locator('input[name="longitude"]')).toHaveValue(/^75\.54/);
    await expect(sheet.locator("[data-session-crafts-field]")).toContainText("Hand Block Printing");
    const box = await place.boundingBox();
    expect(box && box.x + box.width).toBeLessThanOrEqual(width);
    await place.screenshot({ path: `${SHOTS}/new-session-captured-at-${width}.png` });
    await sheet.getByRole("button", { name: "Start session" }).click();
    await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions").length, { timeout: 15_000 }).toBe(1);
    expect(fake.calledWith("POST", "/questionnaire/sessions")[0].body).toMatchObject({
      location: { latitude: 26.8105, longitude: 75.5466, accuracy: 12 },
      crafts: ["Hand Block Printing"],
      craftsByHand: []
    });
  });

  test(`editing a session pre-fills its saved location and crafts and saves a change at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 });
    const fake = new QuestionnaireFake();
    Object.assign(fake.sessions[0], {
      place: "Sanganer",
      crafts: ["Hand Block Printing", "Weaving"],
      craftsByHand: ["Weaving"],
      location: { latitude: 26.8213, longitude: 75.7849, accuracy: 9, placeName: "Sanganer", state: null, district: null, village: "Sanganer" }
    });
    await fake.install(page);
    await page.goto("/questionnaire/sessions/s-1");
    await page.locator("[data-session-actions]").getByRole("button", { name: "Edit details" }).click();
    const dialog = page.getByRole("dialog");
    const place = dialog.locator("[data-session-place]");
    await expect(place.getByLabel("Place", { exact: true })).toHaveValue("Sanganer");
    await expect(place.locator('input[name="latitude"]')).toHaveValue("26.8213");
    await expect(place.locator('input[name="village"]')).toHaveValue("Sanganer");
    await expect(dialog.locator("[data-session-crafts-field]")).toContainText("Weaving");
    await dialog.screenshot({ path: `${SHOTS}/edit-session-captured-at-${width}.png` });
    await place.getByText("Captured at", { exact: true }).click();
    await place.locator('input[name="latitude"]').fill("26.9");
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect.poll(() => fake.calledWith("PATCH", "/questionnaire/sessions/s-1").length).toBe(1);
    expect(fake.calledWith("PATCH", "/questionnaire/sessions/s-1")[0].body).toMatchObject({
      place: "Sanganer",
      location: { latitude: 26.9, longitude: 75.7849, village: "Sanganer" }
    });
  });
}

test("the artisan picker lists the chosen workshop's artisans and offers Show from all records", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await fake.install(page);
  await page.goto("/questionnaire/sessions/new");
  const sheet = page.locator("[data-start-sheet]");
  await expect
    .poll(() => fake.calledWith("GET", "/artisans").some((c) => c.query.get("designWorkshopId") || c.query.get("workshopId")), { timeout: 15_000 })
    .toBe(true);
  const picker = sheet.locator("[data-searchable-select]").nth(2);
  await picker.click();
  await page.getByRole("option", { name: "Show from all records" }).click();
  await expect
    .poll(() => fake.calledWith("GET", "/artisans").some((c) => !c.query.get("designWorkshopId") && !c.query.get("workshopId")), { timeout: 15_000 })
    .toBe(true);
  await picker.click();
  await expect(page.getByRole("option", { name: "Show this workshop's artisans" })).toBeVisible();
  await sheet.screenshot({ path: `${SHOTS}/artisans-all-records.png` });
});
