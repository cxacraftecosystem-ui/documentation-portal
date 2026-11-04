import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const SHOT_DIR = process.env.SHOT_DIR ?? "test-results/questionnaire-capture";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

test.use({
  launchOptions: { args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] },
  permissions: ["microphone"]
});

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

async function openFreshQuestionnaire(page: Page) {
  await page.evaluate(() => window.localStorage.removeItem("field_repo_questionnaire_capture"));
  await page.goto("/questionnaire", { waitUntil: "domcontentloaded" });
  await expect(sections(page).first()).toBeVisible({ timeout: 60_000 });
}

const modeTrigger = (page: Page) => page.getByRole("button", { name: "Recording mode", exact: true });
const answersToggle = (page: Page) => page.getByRole("button", { name: "Show answer text boxes" });
const sections = (page: Page) => page.locator("form.panel details").filter({ hasNotText: "Captured at" });
const firstSection = (page: Page) => sections(page).first();

async function chooseMode(page: Page, label: string) {
  await modeTrigger(page).click();
  await page.getByRole("option", { name: label }).click();
  await expect(modeTrigger(page)).toContainText(label);
}

test("opens on whole-section recording with the answer boxes hidden", async ({ page }) => {
  await signIn(page);
  await openFreshQuestionnaire(page);

  await expect(modeTrigger(page)).toContainText("Record the entire section at once");
  await expect(answersToggle(page)).toHaveAttribute("aria-pressed", "false");

  const section = firstSection(page);
  await expect(section.getByRole("button", { name: "Record section" })).toBeVisible();
  await expect(section.getByRole("button", { name: "Record this question" })).toHaveCount(0);
  await expect(section.locator("textarea")).toHaveCount(0);

  await page.screenshot({ path: `${SHOT_DIR}/light-default-controls.png`, fullPage: false });
  await section.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${SHOT_DIR}/light-default-section-hidden.png`, fullPage: false });
});

test("both controls flip, and the choice survives a reload", async ({ page }) => {
  await signIn(page);
  await openFreshQuestionnaire(page);

  await answersToggle(page).click();
  await expect(answersToggle(page)).toHaveAttribute("aria-pressed", "true");
  await expect(firstSection(page).locator("textarea").first()).toBeVisible();

  await chooseMode(page, "Record individual questions");
  const section = firstSection(page);
  await expect(section.getByRole("button", { name: "Record this question" }).first()).toBeVisible();
  await expect(section.getByRole("button", { name: "Record section" })).toHaveCount(0);

  await page.screenshot({ path: `${SHOT_DIR}/light-individual-controls.png`, fullPage: false });
  await section.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${SHOT_DIR}/light-individual-answers-shown.png`, fullPage: false });

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(firstSection(page)).toBeVisible({ timeout: 60_000 });
  await expect(modeTrigger(page)).toContainText("Record individual questions");
  await expect(answersToggle(page)).toHaveAttribute("aria-pressed", "true");
});

test.describe("a recording is never lost to a mode switch", () => {
  test("a whole-section take survives switching to individual questions", async ({ page }) => {
    test.setTimeout(150_000);
    await page.route("**/media/presign", (route) => route.abort());
    await signIn(page);
    await openFreshQuestionnaire(page);

    const section = firstSection(page);
    await section.getByRole("button", { name: "Record section" }).click();
    await expect(section.getByRole("button", { name: "Stop section recording" })).toBeVisible();
    await page.waitForTimeout(1200);
    await section.getByRole("button", { name: "Stop section recording" }).click();
    await expect(section.getByText(/1 clip/)).toBeVisible();

    await chooseMode(page, "Record individual questions");
    await expect(section.getByText("Earlier section take — still saved.")).toBeVisible();
    await expect(section.getByRole("button", { name: "Record section" })).toHaveCount(0);
    await expect(section.getByText(/1 clip/)).toBeVisible();
    await section.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `${SHOT_DIR}/light-kept-section-take.png`, fullPage: false });
  });
});

test("dark mode renders both states", async ({ page }) => {
  await page.route("**/preferences/me", async (route) => {
    if (route.request().method() !== "GET") return route.abort();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ theme: "dark", reducedMotion: false, largerText: false, highContrast: false })
    });
  });
  await signIn(page);
  await openFreshQuestionnaire(page);
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  await expect(modeTrigger(page)).toContainText("Record the entire section at once");
  await expect(answersToggle(page)).toHaveAttribute("aria-pressed", "false");
  await page.screenshot({ path: `${SHOT_DIR}/dark-default-controls.png`, fullPage: false });
  await firstSection(page).scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${SHOT_DIR}/dark-default-section-hidden.png`, fullPage: false });

  await answersToggle(page).click();
  await chooseMode(page, "Record individual questions");
  await expect(firstSection(page).getByRole("button", { name: "Record this question" }).first()).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/dark-individual-controls.png`, fullPage: false });
  await firstSection(page).scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${SHOT_DIR}/dark-individual-answers-shown.png`, fullPage: false });
});

test("stays usable at 360px", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 780 });
  await signIn(page);
  await openFreshQuestionnaire(page);
  await expect(modeTrigger(page)).toBeVisible();
  await expect(answersToggle(page)).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/light-360.png`, fullPage: false });

  const overflow = await page.evaluate(() => {
    const form = document.querySelector<HTMLElement>("form.panel");
    if (!form) return { form: 0, scroll: 0 };
    return { form: form.clientWidth, scroll: form.scrollWidth };
  });
  expect(overflow.scroll).toBeLessThanOrEqual(overflow.form + 1);
});
