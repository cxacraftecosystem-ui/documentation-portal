import { expect, test } from "./gate";

test("the dashboard opens signed in on production data", async ({ page, gate }) => {
  await page.goto("/dashboard");
  await expect(page).not.toHaveURL(/\/login/);
  await page.waitForLoadState("networkidle");
  await gate.shot("dashboard");
  gate.expectRow("the dashboard opens signed in", "/dashboard", new URL(page.url()).pathname);
});
