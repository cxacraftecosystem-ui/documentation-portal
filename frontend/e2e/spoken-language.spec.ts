import { expect, test, type Page } from "@playwright/test";

import { MEDIA_ID, TranscriptFake } from "./support/transcriptFake";

async function open(page: Page, fake: TranscriptFake) {
  await fake.install(page);
  await page.goto(`/media/${MEDIA_ID}`);
  const viewer = page.locator(`[data-transcript-viewer="${MEDIA_ID}"]`);
  await expect(viewer).toBeVisible({ timeout: 30_000 });
  return viewer;
}

function withRetranscribe(fake: TranscriptFake): TranscriptFake {
  fake.view.permissions = { ...(fake.view.permissions as Record<string, unknown>), retranscribe: true };
  return fake;
}

test.describe("spoken language", () => {
  test("the transcript says what was heard and flags an uncertain language", async ({ page }) => {
    const viewer = await open(page, withRetranscribe(new TranscriptFake()));
    await expect(viewer.locator("[data-detected-language]")).toHaveText("Detected: Hindi");
    await expect(viewer.locator("[data-language-uncertain]")).toHaveText("Language uncertain, check it");
    await expect(viewer.getByTestId("retranscribe-open")).toHaveText("Wrong language? Re-transcribe as…");
  });

  test("a confident transcript carries no uncertainty flag", async ({ page }) => {
    const fake = withRetranscribe(new TranscriptFake());
    fake.view.spokenLanguage = { ...(fake.view.spokenLanguage as Record<string, unknown>), probability: 0.99, uncertain: false };
    const viewer = await open(page, fake);
    await expect(viewer.locator("[data-detected-language]")).toHaveText("Detected: Hindi");
    await expect(viewer.locator("[data-language-uncertain]")).toHaveCount(0);
  });

  test("no edit permission, no re-transcribe action", async ({ page }) => {
    const viewer = await open(page, new TranscriptFake());
    await expect(viewer.locator("[data-detected-language]")).toBeVisible();
    await expect(viewer.getByTestId("retranscribe-open")).toHaveCount(0);
  });

  test("the picker searches every language and offers automatic and mixed first", async ({ page }) => {
    const viewer = await open(page, withRetranscribe(new TranscriptFake()));
    await viewer.getByTestId("retranscribe-open").click();
    const panel = viewer.locator("[data-retranscribe-panel]");
    const trigger = panel.locator("[data-spoken-language-picker] button").first();
    await expect(trigger).toContainText("Detect automatically");
    await trigger.click();
    const options = page.getByRole("option");
    await expect(options.nth(0)).toContainText("Detect automatically");
    await expect(options.nth(1)).toContainText("Mixed (several languages)");
    await page.getByPlaceholder("Type to filter").fill("odi");
    await expect(page.getByRole("option", { name: "Odia" })).toBeVisible();
    await expect(page.getByRole("option", { name: "Hindi" })).toHaveCount(0);
    await page.getByRole("option", { name: "Odia" }).click();
    await expect(trigger).toContainText("Odia");
  });

  test("re-transcribe shows the estimate, keeps the old version and sends the chosen language", async ({ page }) => {
    const fake = withRetranscribe(new TranscriptFake());
    const viewer = await open(page, fake);
    await viewer.getByTestId("retranscribe-open").click();
    const panel = viewer.locator("[data-retranscribe-panel]");
    await expect(panel.locator("[data-retranscribe-estimate]")).toContainText("min of audio");
    await expect(panel.locator("[data-retranscribe-estimate]")).toContainText("The current version is kept.");
    await panel.locator("[data-spoken-language-picker] button").first().click();
    await page.getByPlaceholder("Type to filter").fill("Odia");
    await page.getByRole("option", { name: "Odia" }).click();
    await panel.getByTestId("retranscribe-confirm").click();
    await expect(viewer.locator("[data-retranscribing]")).toHaveText("Re-transcribing…");
    await expect(viewer.locator("[data-retranscribe-panel]")).toHaveCount(0);
    const sent = fake.calledWith("POST", `/transcripts/${MEDIA_ID}/retranscribe`);
    expect(sent).toHaveLength(1);
    expect(sent[0].body).toEqual({ spokenLanguage: "or" });
  });

  test("mixed is sent as the multilingual choice", async ({ page }) => {
    const fake = withRetranscribe(new TranscriptFake());
    const viewer = await open(page, fake);
    await viewer.getByTestId("retranscribe-open").click();
    const panel = viewer.locator("[data-retranscribe-panel]");
    await panel.locator("[data-spoken-language-picker] button").first().click();
    await page.getByRole("option", { name: "Mixed (several languages)" }).click();
    await panel.getByTestId("retranscribe-confirm").click();
    await expect(viewer.locator("[data-retranscribing]")).toBeVisible();
    expect(fake.calledWith("POST", `/transcripts/${MEDIA_ID}/retranscribe`)[0].body).toEqual({ spokenLanguage: "mixed" });
  });
});
