import { expect, test, type Page } from "@playwright/test";

import { QuestionnaireFake } from "./support/qv2Fake";

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/questionnaire-session-place";
const WIDTHS = [1440, 834, 390] as const;
const BAGRU = { id: "place.1", text: "Bagru", place_name: "Bagru, Jaipur, Rajasthan, India", center: [75.5466, 26.8105] };

async function geocoder(page: Page): Promise<string[]> {
  const asked: string[] = [];
  await page.route("https://api.maptiler.com/geocoding/**", async (route) => {
    asked.push(decodeURIComponent(new URL(route.request().url()).pathname.split("/").pop() ?? ""));
    await route.fulfill({ json: { features: [BAGRU] } });
  });
  return asked;
}

test.describe.configure({ mode: "serial" });

for (const width of WIDTHS) {
  test(`a new session's place is searched on MapTiler, filled, pinned and sent at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 });
    const fake = new QuestionnaireFake();
    const asked = await geocoder(page);
    await fake.install(page);
    await page.goto("/questionnaire/sessions/new?artisanId=a1");
    const sheet = page.locator("[data-start-sheet]");
    const place = sheet.locator("[data-session-place]");
    await expect(place.getByRole("button", { name: "Use my location" })).toBeVisible();
    await place.getByRole("combobox", { name: "Search a place" }).fill("Bagru");
    await place.getByRole("option").first().locator("button").click();
    expect(asked).toContain("Bagru.json");
    await expect(place.getByLabel("Place", { exact: true })).toHaveValue("Bagru, Jaipur, Rajasthan");
    await expect(place.locator("[data-session-place-pin]")).toContainText("Pinned at 26.81050, 75.54660");
    const box = await place.boundingBox();
    expect(box && box.x + box.width).toBeLessThanOrEqual(width);
    await place.screenshot({ path: `${SHOTS}/new-session-place-${width}.png` });
    await sheet.getByRole("button", { name: "Start session" }).click();
    await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions").length, { timeout: 15_000 }).toBe(1);
    expect(fake.calledWith("POST", "/questionnaire/sessions")[0].body).toMatchObject({
      place: "Bagru, Jaipur, Rajasthan",
      location: { latitude: 26.8105, longitude: 75.5466, placeName: "Bagru, Jaipur, Rajasthan, India" }
    });
  });

  test(`editing a session pre-fills its pin, searches again and can remove it at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 });
    const fake = new QuestionnaireFake();
    Object.assign(fake.sessions[0], { place: "Sanganer", location: { latitude: 26.8213, longitude: 75.7849, accuracy: null, placeName: "Sanganer" } });
    await geocoder(page);
    await fake.install(page);
    await page.goto("/questionnaire/sessions/s-1");
    await page.locator("[data-session-actions]").getByRole("button", { name: "Edit details" }).click();
    const dialog = page.getByRole("dialog");
    const place = dialog.locator("[data-session-place]");
    await expect(place.getByLabel("Place", { exact: true })).toHaveValue("Sanganer");
    await expect(place.locator("[data-session-place-pin]")).toContainText("Pinned at 26.82130, 75.78490");
    await expect(place.getByRole("button", { name: "Use my location" })).toHaveCount(0);
    await place.screenshot({ path: `${SHOTS}/edit-session-place-${width}.png` });
    await place.getByRole("combobox", { name: "Search a place" }).fill("Bagru");
    await place.getByRole("option").first().locator("button").click();
    await expect(place.getByLabel("Place", { exact: true })).toHaveValue("Bagru, Jaipur, Rajasthan");
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect.poll(() => fake.calledWith("PATCH", "/questionnaire/sessions/s-1").length).toBe(1);
    expect(fake.calledWith("PATCH", "/questionnaire/sessions/s-1")[0].body).toMatchObject({
      place: "Bagru, Jaipur, Rajasthan",
      location: { latitude: 26.8105, longitude: 75.5466 }
    });
    await page.locator("[data-session-actions]").getByRole("button", { name: "Edit details" }).click();
    await page.getByRole("dialog").locator("[data-session-place]").getByRole("button", { name: "Remove location" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
    await expect.poll(() => fake.calledWith("PATCH", "/questionnaire/sessions/s-1").length).toBe(2);
    expect(fake.calledWith("PATCH", "/questionnaire/sessions/s-1")[1].body).toMatchObject({ location: null });
  });
}
