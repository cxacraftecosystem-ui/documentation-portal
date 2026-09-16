import { expect, test, type Page } from "@playwright/test";

import { serveAddressReference, stateTheAddress } from "./fixtures/location";

/**
 * The artisan a researcher was last documenting must survive LEAVING the flow.
 *
 * `CarryForwardCards` has always carried the artisan into the next record through the query string,
 * which works for exactly one journey: click a card the instant the artisan saves. Nobody works
 * that way for long. They save the artisan, go back to the dashboard to check something, open the
 * product form ten minutes later — and every field is empty again. So the away-and-back path is the
 * only path worth asserting here; testing the click-through would prove nothing that already
 * worked.
 *
 * READ-ONLY, DELIBERATELY. The dev server this runs against points at the live production corpus,
 * so the spec never creates an artisan. It enters the flow at the URL `CarryForwardCards` builds
 * after a save — same params, same values, same code path into `lib/carryContext` — using a real
 * artisan read from the API. What is under test is what happens AFTER that: leaving, coming back,
 * and changing your mind.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

type ArtisanRow = { id: string; name: string; place: string; craftId?: string | null; craft?: { name?: string } | null };

/**
 * `FormControls.Select` is not a native <select>: it renders the themed SearchableSelect trigger
 * plus a zero-size input that carries the value into FormData. So the value is read off that input
 * and the choice is made through the listbox, the way a researcher makes it.
 */
async function chooseOption(page: Page, fieldName: string, optionLabel: string | RegExp) {
  await page.locator(`label:has(input[name="${fieldName}"]) button[data-searchable-select]`).click();
  // The floating panel animates in and re-anchors on scroll, so Playwright's stability wait can
  // outlast the test. Dispatching the click is enough: the handler is a plain onClick.
  await page.getByRole("option", { name: optionLabel }).first().dispatchEvent("click");
}

/**
 * The same act on a MULTI-select, which has to be found and dismissed differently.
 *
 * THE TOOL FORM'S CRAFT AND ARTISAN PICKERS TAKE SEVERAL ANSWERS NOW, so there is no hidden mirror
 * input carrying a `name` for `chooseOption` above to find them by — the value is a list and it
 * reaches the save from React state. They are located by their FIELD LABEL instead, exactly as
 * `processProductField` at the bottom of this file locates the one other control in the app with no
 * name of its own.
 *
 * AND THE PANEL IS DISMISSED EXPLICITLY. A single-select closes on a pick because picking one option
 * is the whole answer; a multi-select deliberately does not, since several usually are. Leaving it
 * open would float it over whichever control the next line reaches for.
 */
async function chooseInMultiSelect(page: Page, fieldLabel: string, optionLabel: string | RegExp) {
  const escaped = fieldLabel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  await page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${escaped}( \\*)?$`) }) })
    .locator("[data-searchable-select]")
    .click();
  await page.getByRole("option", { name: optionLabel }).first().dispatchEvent("click");
  await page.keyboard.press("Escape");
}

/** The tool form's craft picker, by the label a researcher reads. */
const TOOL_CRAFTS = "Linked crafts (fills craft name)";
/** The tool form's artisan picker. Its rows read "Craft · Name · Place", craft FIRST. */
const TOOL_ARTISANS = "Linked artisans (fills artisan + place)";

/** One row of the tool form's artisan picker, composed by the form's own rule. */
function toolArtisanRow(row: ArtisanRow) {
  return [row.craft?.name, row.name, row.place].filter(Boolean).join(" · ");
}

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/** Two real artisans of the same craft: one to carry, one to switch to. */
async function twoArtisans(page: Page): Promise<[ArtisanRow, ArtisanRow]> {
  // Read through the app's own origin and token rather than a second config: whatever API the
  // running dev server talks to is the API whose artisans this spec should be using.
  const rows = await page.evaluate(async (apiBase) => {
    const token = window.localStorage.getItem("field_repo_token");
    const response = await fetch(`${apiBase}/api/artisans?pageSize=100`, { headers: { Authorization: `Bearer ${token}` } });
    return (await response.json()).items as ArtisanRow[];
  }, process.env.NEXT_PUBLIC_API_URL ?? "https://d2b34i3e92al6i.cloudfront.net");
  const byCraft = new Map<string, ArtisanRow[]>();
  for (const row of rows) {
    if (!row.craftId) continue;
    byCraft.set(row.craftId, [...(byCraft.get(row.craftId) ?? []), row]);
  }
  const pair = [...byCraft.values()].find((group) => group.length >= 2);
  if (!pair) throw new Error("Need two artisans sharing a craft to test the switch.");
  return [pair[0], pair[1]];
}

/** The href CarryForwardCards renders on the artisan save screen. */
function handoffHref(path: string, artisan: ArtisanRow) {
  const params = new URLSearchParams({
    artisanId: artisan.id,
    artisanName: artisan.name,
    place: artisan.place,
    craftId: artisan.craftId ?? "",
    craftName: artisan.craft?.name ?? ""
  });
  return `${path}?${params.toString()}`;
}

test.describe("Carry-forward artisan context", () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page);
    await page.evaluate(() => {
      for (const key of Object.keys(window.localStorage)) {
        if (key.startsWith("field_repo_carry_context:")) window.localStorage.removeItem(key);
      }
    });
  });

  test("survives a trip to the dashboard, and a change sticks", async ({ page }) => {
    const [first, second] = await twoArtisans(page);

    // 1. Enter the flow exactly as the save screen would.
    await page.goto(handoffHref("/products/new", first));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toContainText(first.name);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(first.name);

    // 2. Leave. This is the step the query string cannot survive.
    await page.goto("/dashboard");
    await expect(page.getByRole("heading", { name: /what would you like to do/i })).toBeVisible();

    // 3. Come back cold — no params on the URL at all.
    await page.goto("/tools/new");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(first.name);
    await expect(banner).toContainText(/you documented/);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(first.name);
    await expect(page.locator('input[name="place"]')).toHaveValue(first.place);
    /*
      THE LINK ITSELF, ASSERTED THROUGH THE CONTROL RATHER THAN THROUGH A HIDDEN INPUT.

      This read `input[name="artisanId"]` until the tool form's artisan picker became a MULTI-select:
      the value is a list now and it reaches the save from React state, so there is no mirror input
      to carry an id into FormData and none is wanted — a hidden input holding one of several ids
      would be a second, narrower answer to the same question. The trigger counts what is ticked, so
      "1 selected" is this form saying the carried artisan is linked, and the banner plus the two
      boxes above already say WHICH.
    */
    const artisanPicker = page
      .locator("label")
      .filter({ has: page.locator("span.field-label", { hasText: TOOL_ARTISANS }) })
      .locator("[data-searchable-select]");
    await expect(artisanPicker).toContainText("1 selected");

    // 4. One action clears it, and the fields go with the banner.
    await banner.getByRole("button", { name: "Change" }).click();
    await expect(banner).toHaveCount(0);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue("");
    await expect(page.locator('input[name="place"]')).toHaveValue("");
    // And the link with them: "Change" empties both pickers, so the artisan one is back to its
    // "pick a craft first" placeholder rather than still holding the artisan it was cleared of.
    await expect(artisanPicker).not.toContainText("selected");

    // 5. Choose somebody else, and confirm THAT is what comes back next time.
    await chooseInMultiSelect(page, TOOL_CRAFTS, second.craft?.name ?? "");
    await chooseInMultiSelect(page, TOOL_ARTISANS, toolArtisanRow(second));
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(second.name);
    // An explicit pick is the researcher's own choice, so nothing claims to have prefilled it.
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toHaveCount(0);

    await page.goto("/dashboard");
    await page.goto("/products/new");
    const afterChange = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(afterChange).toContainText(second.name);
    await expect(afterChange).not.toContainText(first.name);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(second.name);
  });

  test("an expired context is not offered", async ({ page }) => {
    const [first] = await twoArtisans(page);
    await page.goto(handoffHref("/products/new", first));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toBeVisible();

    // Backdate the stored row past the twelve-hour window: a context from yesterday is not context.
    await page.evaluate(() => {
      const key = Object.keys(window.localStorage).find((k) => k.startsWith("field_repo_carry_context:"));
      if (!key) throw new Error("Nothing was stored — the carry context never reached localStorage.");
      const row = JSON.parse(window.localStorage.getItem(key) as string);
      row.savedAt = Date.now() - 13 * 60 * 60 * 1000;
      window.localStorage.setItem(key, JSON.stringify(row));
    });

    await page.goto("/tools/new");
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toHaveCount(0);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue("");
  });

  test("still offers the context when the artisan list cannot be loaded", async ({ page }) => {
    const [first] = await twoArtisans(page);
    await page.goto(handoffHref("/products/new", first));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toBeVisible();

    // Offline is the normal state in Bagru. Suppressing the prefill whenever the artisan list is
    // unreachable would disable the feature in exactly the conditions it was written for.
    await page.route("**/api/artisans**", (route) => route.abort());
    await page.goto("/tools/new");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(first.name);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(first.name);
    await expect(page.locator('input[name="place"]')).toHaveValue(first.place);
  });

  test("never stores identity numbers, and never leaks across accounts", async ({ page }) => {
    const [first] = await twoArtisans(page);
    await page.goto(handoffHref("/products/new", first));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toBeVisible();

    const stored = await page.evaluate(() => {
      const key = Object.keys(window.localStorage).find((k) => k.startsWith("field_repo_carry_context:"));
      return { key, raw: key ? window.localStorage.getItem(key) : null };
    });
    expect(stored.raw).toBeTruthy();
    // Aadhaar and Pehchan are regulated PII: they may not be copied between records, and they may
    // not sit in local storage on a laptop three researchers share.
    expect(stored.raw?.toLowerCase()).not.toContain("aadhaar");
    expect(stored.raw?.toLowerCase()).not.toContain("pehchan");
    expect(JSON.parse(stored.raw as string).userId).toBeTruthy();
    // Namespaced per account, so a colleague signing in on the same machine starts clean.
    expect(stored.key).toMatch(/^field_repo_carry_context:.+/);

    // A row written under another account is ignored rather than shown.
    await page.evaluate((key) => {
      const row = JSON.parse(window.localStorage.getItem(key as string) as string);
      row.userId = "someone-else";
      window.localStorage.setItem(key as string, JSON.stringify(row));
    }, stored.key);
    await page.goto("/tools/new");
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toHaveCount(0);
  });
});


/**
 * The same journey, one record type further along.
 *
 * The context is no longer a pointer at an artisan: it is a bag that accumulates the craft, the
 * artisan, the workshop AND the records made under them. The claim these tests exist to prove is
 * the one that was actually asked for — record a product, wander off, open a PROCESS form, and find
 * the product already chosen — plus the rule that makes it safe, which is that a contradicting
 * artisan takes their products with them.
 *
 * NOTHING IS WRITTEN. The dev server points at the production corpus, so the product create is
 * answered locally — with a product that genuinely exists, because the process form has to find the
 * carried id in the artisan's real product list before it will let the prefill stand. Everything
 * between the Save click and that list is the app's own code.
 */
type ProductRow = { id: string; productName: string; artisanId?: string | null; craftId?: string | null };

/** A real product whose artisan is linked to a craft, so the create form can be filled by picking. */
async function aLinkedProduct(page: Page): Promise<{ product: ProductRow; artisan: ArtisanRow; craftName: string }> {
  const { products, artisans } = await page.evaluate(async (apiBase) => {
    const token = window.localStorage.getItem("field_repo_token");
    const headers = { Authorization: `Bearer ${token}` };
    const [productResponse, artisanResponse] = await Promise.all([
      fetch(`${apiBase}/api/products?pageSize=100`, { headers }),
      fetch(`${apiBase}/api/artisans?pageSize=100`, { headers })
    ]);
    return { products: (await productResponse.json()).items, artisans: (await artisanResponse.json()).items };
  }, process.env.NEXT_PUBLIC_API_URL ?? "https://d2b34i3e92al6i.cloudfront.net");

  for (const product of products as ProductRow[]) {
    const artisan = (artisans as ArtisanRow[]).find((row) => row.id === product.artisanId);
    const craftName = artisan?.craft?.name;
    if (artisan && artisan.craftId && craftName) return { product, artisan, craftName };
  }
  throw new Error("Need a product linked to an artisan who is linked to a craft.");
}

/** The handoff URL a post-save screen builds, now that it carries the product too. */
function productHandoff(product: ProductRow, artisan: ArtisanRow): string {
  const params = new URLSearchParams({
    artisanId: artisan.id,
    artisanName: artisan.name,
    place: artisan.place,
    craftId: artisan.craftId ?? "",
    craftName: artisan.craft?.name ?? "",
    productId: product.id,
    productName: product.productName
  });
  return `/processes?new=1&${params.toString()}`;
}

test.describe("Carry-forward across record types", () => {
  // The coordinate is mandatory and captures itself when the browser is allowed to answer, which is
  // the only reason this spec can reach the Save button at all.
  test.use({ permissions: ["geolocation"], geolocation: { latitude: 26.8137, longitude: 75.545 } });

  test.beforeEach(async ({ page }) => {
    await serveAddressReference(page);
    await signIn(page);
    await page.evaluate(() => {
      for (const key of Object.keys(window.localStorage)) {
        if (key.startsWith("field_repo_carry_context:")) window.localStorage.removeItem(key);
      }
    });
  });

  test("a saved product is offered by the process form after a trip to the dashboard", async ({ page }) => {
    const { product, artisan, craftName } = await aLinkedProduct(page);

    // Answer the create locally. The response is a real row, so the id the form banks is one the
    // process form's product list will actually contain.
    await page.route("**/api/products", async (route) => {
      if (route.request().method() !== "POST") return route.fallback();
      await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(product) });
    });

    await page.goto("/products/new");
    await chooseOption(page, "craftId", craftName);
    await chooseOption(page, "artisanId", `${artisan.name} · ${artisan.place}`);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(artisan.name);
    await page.locator('input[name="productName"]').fill(product.productName);
    await stateTheAddress(page);
    // The card takes its own fix; the save is blocked until it has one.
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.81/, { timeout: 30_000 });

    await page.getByRole("button", { name: /^Save product$/ }).click();
    // A workshop that has already ended asks before it saves, and this run is a late submission by
    // construction, so the honest answer is yes.
    const lateSubmission = page.getByRole("button", { name: "Submit anyway" });
    if (await lateSubmission.isVisible().catch(() => false)) await lateSubmission.click();
    await page.waitForURL(/\/products$/, { timeout: 60_000 });

    // Leave. This is the step the query string cannot survive.
    await page.goto("/dashboard");
    await expect(page.getByRole("heading", { name: /what would you like to do/i })).toBeVisible();

    // Come back cold, into a different record type, with no params on the URL at all.
    await page.goto("/processes?new=1");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(artisan.name);
    // The implication is on screen rather than only in the fields: the product is named, and so is
    // the fact that its artisan came along with it.
    await expect(banner).toContainText(product.productName);
    await expect(banner).toContainText(/came with it/);

    // And it really is in the field, not just in the banner.
    await expect(processProductField(page)).toContainText(product.productName, { timeout: 30_000 });
  });

  test("a contradicting artisan takes the previous artisan's product with them", async ({ page }) => {
    const { product, artisan } = await aLinkedProduct(page);
    const [first, second] = await twoArtisans(page);
    const other = second.id === artisan.id ? first : second;
    test.skip(other.id === artisan.id, "Need a second artisan who is not the one the product belongs to.");

    await page.goto(productHandoff(product, artisan));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toContainText(product.productName);

    // Choosing somebody else is a contradiction, not an addition: the product belonged to the
    // artisan being replaced, so keeping it would file it under a person who never made it.
    await page.goto("/tools/new");
    await chooseInMultiSelect(page, TOOL_CRAFTS, other.craft?.name ?? "");
    await chooseInMultiSelect(page, TOOL_ARTISANS, toolArtisanRow(other));

    await page.goto("/dashboard");
    await page.goto("/processes?new=1");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(other.name);
    await expect(banner).not.toContainText(product.productName);
    await expect(processProductField(page)).not.toContainText(product.productName);
  });
});

/** The process form's product dropdown, found by its label — the control carries no name. */
function processProductField(page: Page) {
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: /^Product \*$/ }) })
    .locator("[data-searchable-select]");
}
