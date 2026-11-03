import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const DESTINATIONS = [
  { href: "/map", tile: "Map", nav: "Map", heading: /Where the work comes from/i },
  {
    href: "/questionnaire/consolidated",
    tile: "Consolidated questionnaire",
    nav: "Consolidated questionnaire",
    heading: /Consolidated questionnaire/i
  }
] as const;

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

function tile(page: Page, label: string) {
  return page.getByText(label, { exact: true }).locator("xpath=ancestor::div[.//a][1]");
}

test.describe("Entry points", () => {
  for (const destination of DESTINATIONS) {
    test(`the dashboard offers a tile that opens ${destination.href}`, async ({ page }) => {
      await signIn(page);
      await page.goto("/dashboard");
      await expect(page.getByRole("heading", { name: "What would you like to do?" })).toBeVisible();

      const card = tile(page, destination.tile);
      await expect(card).toBeVisible();
      const open = card.getByRole("link").first();
      await expect(open).toHaveAttribute("href", destination.href);

      await open.click();
      await page.waitForURL(`**${destination.href}`);
      await expect(page.getByRole("heading", { name: destination.heading }).first()).toBeVisible();
    });

    test(`the navigation sheet lists ${destination.nav}`, async ({ page }) => {
      await signIn(page);
      await page.goto("/dashboard");

      await page.getByRole("button", { name: /toggle navigation menu/i }).click();
      const sheet = page.getByRole("dialog", { name: "Navigation" });
      await expect(sheet).toBeVisible();

      const link = sheet.getByRole("link", { name: destination.nav, exact: true });
      await expect(link).toHaveAttribute("href", destination.href);

      await link.click();
      await page.waitForURL(`**${destination.href}`);
      await expect(page.getByRole("heading", { name: destination.heading }).first()).toBeVisible();
    });
  }

  test("the consolidated view does not also light up 'Take interview' as the current page", async ({ page }) => {
    await signIn(page);
    await page.goto("/questionnaire/consolidated");

    await page.getByRole("button", { name: /toggle navigation menu/i }).click();
    const sheet = page.getByRole("dialog", { name: "Navigation" });
    await expect(sheet.getByRole("link", { name: "Consolidated questionnaire", exact: true })).toHaveAttribute(
      "aria-current",
      "page"
    );
    await expect(sheet.getByRole("link", { name: "Take interview", exact: true })).not.toHaveAttribute(
      "aria-current",
      /.*/
    );
  });
});
