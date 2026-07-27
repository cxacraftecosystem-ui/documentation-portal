import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, type Locator, type Page } from "@playwright/test";

/**
 * Shared plumbing for the four location specs.
 *
 * These live together because they all depend on the same two facts about the card, and a spec that
 * learns them separately learns them differently: the coordinate now lives inside a collapsed
 * drawer, and the state and district are required with no automatic source. Both changed when the
 * card was split into a STATED ADDRESS the researcher owns and a CAPTURED AT the device owns — see
 * components/forms/LocationFields.
 */

/**
 * The reference payload, served from a fixture rather than from the API.
 *
 * The district list ships in the same change as this spec, so the DEPLOYED api the dev server talks
 * to does not have it yet and a spec pointed at it would be testing yesterday's contract. The file
 * is the byte-for-byte output of `app.services.address.address_reference()`, so what is under test
 * is the real shape and the real 795 names — not a hand-written stand-in that could agree with a
 * bug.
 *
 * Regenerate it whenever the district list moves, from backend/:
 *
 *     ./.venv/Scripts/python.exe -c "import json; from app.services.address import \
 *       address_reference; print(json.dumps(address_reference()))" \
 *       > ../frontend/e2e/fixtures/address-reference.json
 *
 * Delete it once the API in front of the dev server serves `districts`; the route below can go with
 * it, and these specs will exercise the real payload.
 */
const payload = JSON.parse(readFileSync(join(__dirname, "address-reference.json"), "utf-8")) as unknown;

export async function serveAddressReference(page: Page) {
  await page.route("**/reference/address", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(payload) })
  );
}

/**
 * Unfold "Captured at".
 *
 * Collapsed is its resting state, because the entire point of the split is that a reader never
 * mistakes where the DEVICE was for where the SUBJECT is. Anything asserting on the coordinate, the
 * radius or the GPS button has to open the drawer first — which is exactly what a researcher does.
 */
export async function openCapturedAt(page: Page) {
  const group = page.locator("details").filter({ hasText: /Captured at/ }).first();
  await expect(group).toBeVisible({ timeout: 30_000 });
  if (!(await group.evaluate((node) => (node as HTMLDetailsElement).open))) {
    await group.getByText("Captured at").click();
  }
  return group;
}

/**
 * The dropdown trigger inside the field whose label is exactly `label`.
 *
 * Matched through the `.field-label` span rather than the accessible name because
 * `FormControls.Field` wraps every control in a `<label>`, so the button's accessible name is the
 * label text concatenated with its own.
 */
export function fieldSelect(page: Page, label: string): Locator {
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${label}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

/**
 * Open a dropdown, filter it, take the exact match.
 *
 * `.last()` rather than the bare panel: a panel that has just been chosen from can still be in the
 * DOM when the next one opens, so scoping to the newest is what keeps two open lists from colliding
 * in strict mode. Escape afterwards leaves the page in a known state for the next step.
 */
export async function pick(page: Page, label: string, value: string) {
  await fieldSelect(page, label).click();
  const open = page.locator("[data-anchored-popover]").last();
  await open.getByRole("combobox").fill(value);
  await open.getByRole("option", { name: value, exact: true }).first().click();
  await page.keyboard.press("Escape");
  await expect(fieldSelect(page, label)).toContainText(value);
}

/**
 * Answer the state and district, which are required on a create since the card was split.
 *
 * A spec that means to prove something about the COORDINATE has to satisfy them first, because they
 * have no automatic source and the browser will not submit without them.
 */
export async function stateTheAddress(page: Page, state = "Rajasthan", district = "Jaipur") {
  await pick(page, "State", state);
  await pick(page, "District", district);
}
