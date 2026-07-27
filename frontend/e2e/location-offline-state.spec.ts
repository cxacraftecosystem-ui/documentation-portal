import { expect, test, type Locator, type Page } from "@playwright/test";

import { fieldSelect, serveAddressReference } from "./fixtures/location";

/**
 * WITH NO CONNECTION, A RECORD MUST STILL BE SAVEABLE. This spec exists because it was not.
 *
 * The state list came only from `GET /reference/address`, and the State dropdown is REQUIRED on
 * every new record. On a dropped connection that fetch fails, so the dropdown rendered its
 * "Select state" placeholder and nothing else: a required closed list with no members. The
 * researcher read "Choose the state the artisan is in" and was given nothing to choose. Native
 * constraint validation then refused the submit, so `saveOrQueue` was NEVER REACHED and the
 * IndexedDB outbox — which exists precisely so a half-finished interview survives no signal — never
 * saw the record. The interview and its photographs died with the tab. Six forms render this card,
 * /media included.
 *
 * The district dropdown already stood down from required when its list was missing. The state did
 * not, and that asymmetry was the bug.
 *
 * WHAT IS ASSERTED, and why it is not a submit. These specs run against the live API, so nothing
 * here writes. The failure was entirely local — a required control with no satisfiable answer — so
 * it is provable exactly where it happened: `checkValidity()` on the mirror input that blocks the
 * form. If that reports valid, the submit handler runs, and `saveOrQueue` queues offline on its own
 * (lib/offline.ts returns `queue()` the moment `navigator.onLine` is false).
 *
 * The last test is the drift guard. Bundling the 36 names is only defensible while they are the
 * same 36 the server validates against, so the two lists are read out of the same dropdown — once
 * with the endpoint dead, once with it serving — and compared.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Bagru, Rajasthan — a block-printing cluster the repository already documents. */
const BAGRU = { latitude: 26.8137, longitude: 75.545 };

/** The register the form is held to: 28 states and 8 union territories. */
const STATE_COUNT = 36;

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * The reference endpoint, dead.
 *
 * `abort` rather than a 500, because a dropped connection is what this is about and the two reach
 * the component by different paths — a rejected fetch, not a resolved response the code could have
 * inspected. It is also the harsher of the two: nothing arrives that could be salvaged.
 */
async function breakAddressReference(page: Page) {
  await page.route("**/reference/address", (route) => route.abort("failed"));
}

/** Everything the dropdown is offering, placeholder included, read from the open panel. */
async function readOptions(page: Page, field: string): Promise<string[]> {
  await fieldSelect(page, field).click();
  const panel = page.locator("[data-anchored-popover]").last();
  await expect(panel.getByRole("option").first()).toBeVisible();
  // textContent rather than innerText: a long list scrolls inside the panel, and a row below the
  // fold is rendered but not laid out on screen.
  const labels = await panel.getByRole("option").allTextContents();
  await page.keyboard.press("Escape");
  return labels.map((entry) => entry.trim());
}

/** Does the browser consider this control answered? The mirror input is what blocks the submit. */
function isValid(input: Locator) {
  return input.evaluate((node) => (node as HTMLInputElement).checkValidity());
}

test.describe("Location: the state list with no connection", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("the required State dropdown is still answerable when /reference/address fails", async ({ page }) => {
    await signIn(page);
    await breakAddressReference(page);
    await page.goto("/artisans/new");

    const state = page.locator('input[name="state"]');
    await expect(state).toBeAttached();
    // Still required — the fix is a list to choose from, not a relaxed rule. A state that became
    // optional the moment the network wobbled would let a researcher WITH signal skip the one field
    // the whole dataset is grouped by.
    await expect(state).toHaveAttribute("required", "");
    // Unanswered, so the browser refuses the form. This is the state the researcher was stranded in.
    expect(await isValid(state)).toBe(false);

    const offered = await readOptions(page, "State");
    // The placeholder plus all 36. Before the fix this array had exactly one entry, "Select state",
    // and there was no second move available to anybody.
    expect(offered).toHaveLength(STATE_COUNT + 1);
    expect(offered[0]).toBe("Select state");
    // Spot the four corners of the register, so a truncated list cannot pass by counting right.
    expect(offered).toEqual(expect.arrayContaining(["Rajasthan", "West Bengal", "Ladakh", "Lakshadweep"]));

    // And it can actually be answered, which is the whole finding.
    await fieldSelect(page, "State").click();
    await page.locator("[data-anchored-popover]").last().getByRole("option", { name: "Rajasthan", exact: true }).click();
    await expect(state).toHaveValue("Rajasthan");
    expect(await isValid(state)).toBe(true);
  });

  test("the district stands down, and says why, rather than blocking the save", async ({ page }) => {
    await signIn(page);
    await breakAddressReference(page);
    await page.goto("/artisans/new");

    await fieldSelect(page, "State").click();
    await page.locator("[data-anchored-popover]").last().getByRole("option", { name: "Rajasthan", exact: true }).click();

    const district = page.locator('input[name="district"]');
    // The 795 district names genuinely cannot be bundled, so the district must not be demanded when
    // it has nothing to offer — the guard the state was missing.
    await expect(district).not.toHaveAttribute("required", /.*/);
    expect(await isValid(district)).toBe(true);
    // An empty dropdown with nothing said about it reads as a broken form.
    await expect(page.getByText(/district list has not loaded/i)).toBeVisible();
  });

  test("the bundled list is exactly the list the API serves", async ({ page }) => {
    await signIn(page);

    await breakAddressReference(page);
    await page.goto("/artisans/new");
    const bundled = await readOptions(page, "State");

    // Same dropdown, same page, with the endpoint answering — so the two lists are compared as the
    // researcher meets them rather than as two files somebody remembered to keep in step.
    await page.unroute("**/reference/address");
    await serveAddressReference(page);
    await page.goto("/artisans/new");
    const served = await readOptions(page, "State");

    expect(served).toHaveLength(STATE_COUNT + 1);
    // Order is allowed to differ — the API groups states before union territories, the bundled list
    // is plainly alphabetical — but the membership may not.
    expect([...bundled].sort()).toEqual([...served].sort());
  });
});
