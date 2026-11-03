import { expect, test, type Page } from "@playwright/test";

import { serveAddressReference, stateTheAddress } from "./fixtures/location";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

type ArtisanRow = { id: string; name: string; place: string; craftId?: string | null; craft?: { name?: string } | null };

async function chooseOption(page: Page, fieldName: string, optionLabel: string | RegExp) {
  await page.locator(`label:has(input[name="${fieldName}"]) button[data-searchable-select]`).click();
  await page.getByRole("option", { name: optionLabel }).first().dispatchEvent("click");
}

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

const TOOL_CRAFTS = "Linked crafts";

async function chooseCraft(page: Page, ariaLabel: string, craftName: string) {
  const box = page.getByRole("combobox", { name: ariaLabel });
  await box.click();
  await box.fill(craftName);
  const escaped = craftName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  await page.getByRole("option", { name: new RegExp(escaped) }).first().click();
  await page.keyboard.press("Escape");
}

const TOOL_ARTISANS = "Linked artisans (fills artisan + place)";

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

async function twoArtisans(page: Page): Promise<[ArtisanRow, ArtisanRow]> {
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

    await page.goto(handoffHref("/products/new", first));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toContainText(first.name);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(first.name);

    await page.goto("/dashboard");
    await expect(page.getByRole("heading", { name: /what would you like to do/i })).toBeVisible();

    await page.goto("/tools/new");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(first.name);
    await expect(banner).toContainText(/you documented/);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(first.name);
    await expect(page.locator('input[name="place"]')).toHaveValue(first.place);
    const artisanPicker = page
      .locator("label")
      .filter({ has: page.locator("span.field-label", { hasText: TOOL_ARTISANS }) })
      .locator("[data-searchable-select]");
    await expect(artisanPicker).toContainText("1 selected");

    await banner.getByRole("button", { name: "Change" }).click();
    await expect(banner).toHaveCount(0);
    await expect(page.locator('input[name="artisanName"]')).toHaveValue("");
    await expect(page.locator('input[name="place"]')).toHaveValue("");
    await expect(artisanPicker).not.toContainText("selected");

    await chooseCraft(page, TOOL_CRAFTS, second.craft?.name ?? "");
    await chooseInMultiSelect(page, TOOL_ARTISANS, toolArtisanRow(second));
    await expect(page.locator('input[name="artisanName"]')).toHaveValue(second.name);
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
    expect(stored.raw?.toLowerCase()).not.toContain("aadhaar");
    expect(stored.raw?.toLowerCase()).not.toContain("pehchan");
    expect(JSON.parse(stored.raw as string).userId).toBeTruthy();
    expect(stored.key).toMatch(/^field_repo_carry_context:.+/);

    await page.evaluate((key) => {
      const row = JSON.parse(window.localStorage.getItem(key as string) as string);
      row.userId = "someone-else";
      window.localStorage.setItem(key as string, JSON.stringify(row));
    }, stored.key);
    await page.goto("/tools/new");
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toHaveCount(0);
  });
});


type ProductRow = { id: string; productName: string; artisanId?: string | null; craftId?: string | null };

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
    await expect(page.locator('input[name="latitude"]')).toHaveValue(/26\.81/, { timeout: 30_000 });

    await page.getByRole("button", { name: /^Save product$/ }).click();
    const lateSubmission = page.getByRole("button", { name: "Submit anyway" });
    if (await lateSubmission.isVisible().catch(() => false)) await lateSubmission.click();
    await page.waitForURL(/\/products$/, { timeout: 60_000 });

    await page.goto("/dashboard");
    await expect(page.getByRole("heading", { name: /what would you like to do/i })).toBeVisible();

    await page.goto("/processes?new=1");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(artisan.name);
    await expect(banner).toContainText(product.productName);
    await expect(banner).toContainText(/came with it/);

    await expect(processProductField(page)).toContainText(product.productName, { timeout: 30_000 });
  });

  test("a contradicting artisan takes the previous artisan's product with them", async ({ page }) => {
    const { product, artisan } = await aLinkedProduct(page);
    const [first, second] = await twoArtisans(page);
    const other = second.id === artisan.id ? first : second;
    test.skip(other.id === artisan.id, "Need a second artisan who is not the one the product belongs to.");

    await page.goto(productHandoff(product, artisan));
    await expect(page.getByRole("status").filter({ hasText: "Continuing with" })).toContainText(product.productName);

    await page.goto("/tools/new");
    await chooseCraft(page, TOOL_CRAFTS, other.craft?.name ?? "");
    await chooseInMultiSelect(page, TOOL_ARTISANS, toolArtisanRow(other));

    await page.goto("/dashboard");
    await page.goto("/processes?new=1");
    const banner = page.getByRole("status").filter({ hasText: "Continuing with" });
    await expect(banner).toContainText(other.name);
    await expect(banner).not.toContainText(product.productName);
    await expect(processProductField(page)).not.toContainText(product.productName);
  });
});

function processProductField(page: Page) {
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: /^Product \*$/ }) })
    .locator("[data-searchable-select]");
}
