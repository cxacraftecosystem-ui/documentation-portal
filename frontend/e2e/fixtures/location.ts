import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, type Locator, type Page } from "@playwright/test";

const payload = JSON.parse(readFileSync(join(__dirname, "address-reference.json"), "utf-8")) as unknown;

export async function serveAddressReference(page: Page) {
  await page.route("**/reference/address", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(payload) })
  );
}

export async function openCapturedAt(page: Page) {
  const group = page.locator("details").filter({ hasText: /Captured at/ }).first();
  await expect(group).toBeVisible({ timeout: 30_000 });
  if (!(await group.evaluate((node) => (node as HTMLDetailsElement).open))) {
    await group.getByText("Captured at").click();
  }
  return group;
}

export function fieldSelect(page: Page, label: string): Locator {
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${label}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

export async function pick(page: Page, label: string, value: string) {
  await fieldSelect(page, label).click();
  const open = page.locator("[data-anchored-popover]").last();
  await open.getByRole("combobox").fill(value);
  await open.getByRole("option", { name: value, exact: true }).first().click();
  await page.keyboard.press("Escape");
  await expect(fieldSelect(page, label)).toContainText(value);
}

export async function stateTheAddress(page: Page, state = "Rajasthan", district = "Jaipur") {
  await pick(page, "State", state);
  await pick(page, "District", district);
}
