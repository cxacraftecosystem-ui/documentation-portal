import { expect, test, type Page } from "@playwright/test";

/**
 * The Field Repository's "Scan a code" page (REQUIREMENTS_2026-10-20 §6.3): a typed six-character
 * session code calls `POST /sessions/join` and opens the session, and a browser with no camera still
 * offers the photo and the typed paths. The join answer is intercepted, so nothing is written.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

test("a typed session code joins through POST /sessions/join and opens the session", async ({ page }) => {
  await signIn(page);
  let posted: unknown = null;
  await page.route("**/sessions/join", async (route) => {
    posted = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: "cmsessionscan0001" }) });
  });
  await page.goto("/scan");
  await page.getByLabel("Type the code").fill("ab3-k9z");
  await page.getByRole("button", { name: "Look up" }).click();
  const card = page.locator('[data-scan-outcome="JOIN_SESSION"]');
  await expect(card).toContainText("A questionnaire session.");
  await card.getByRole("button", { name: "Join" }).click();
  await page.waitForURL(/\/questionnaire\/sessions\/cmsessionscan0001/);
  expect(posted).toEqual({ joinCode: "AB3K9Z" });
});

test("a browser with no camera still offers the photo and the typed code", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "mediaDevices", { value: undefined, configurable: true });
  });
  await signIn(page);
  await page.goto("/scan");
  await page.getByRole("button", { name: "Use the camera" }).click();
  await expect(page.getByText("No camera here. Choose a photo or type the code.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Choose a photo" })).toBeVisible();
  await expect(page.getByLabel("Type the code")).toBeVisible();
});
