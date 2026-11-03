import { expect, test, type Locator, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

let session: Record<string, string> | null = null;

async function signIn(page: Page) {
  if (session) {
    await page.addInitScript((entries: Record<string, string>) => {
      for (const [key, value] of Object.entries(entries)) window.localStorage.setItem(key, value);
    }, session);
    return;
  }
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  const captured = await page.evaluate(() => Object.fromEntries(Object.entries(window.localStorage)));
  delete captured.field_repo_preferences;
  session = captured as Record<string, string>;
}

function fieldSelect(page: Page, label: string): Locator {
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${label}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

const panel = (page: Page) => page.locator("[data-anchored-popover]");
const filterBox = (page: Page) => panel(page).getByRole("combobox");
const rows = (page: Page) => panel(page).getByRole("option");

async function openSelect(page: Page, label: string, minOptions = 2) {
  await fieldSelect(page, label).click();
  await expect(panel(page)).toBeVisible();
  await expect.poll(() => rows(page).count(), { timeout: 30_000 }).toBeGreaterThanOrEqual(minOptions);
}

async function box(locator: Locator) {
  const value = await locator.boundingBox();
  if (!value) throw new Error("element has no bounding box");
  return {
    x: Math.round(value.x * 10) / 10,
    y: Math.round(value.y * 10) / 10,
    width: Math.round(value.width * 10) / 10,
    height: Math.round(value.height * 10) / 10
  };
}

function readPage(page: Page) {
  return page.evaluate(() => ({
    scrollHeight: document.documentElement.scrollHeight,
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    scrollY: window.scrollY
  }));
}

function readHighlight(page: Page) {
  return page.evaluate(() => {
    const panelNode = document.querySelector<HTMLElement>("[data-anchored-popover]");
    if (!panelNode) return null;
    const owner =
      panelNode.querySelector<HTMLElement>("[aria-activedescendant]") ??
      document.querySelector<HTMLElement>("[data-searchable-select][aria-activedescendant]");
    const id = owner?.getAttribute("aria-activedescendant") ?? null;
    const node = id ? document.getElementById(id) : null;
    const list = panelNode.querySelector<HTMLElement>('[role="listbox"]');
    const all = list ? Array.from(list.querySelectorAll('[role="option"]')) : [];
    if (!node || !list) return { id, exists: false, index: -1, count: all.length, visible: false, text: null };
    const a = node.getBoundingClientRect();
    const b = list.getBoundingClientRect();
    return {
      id,
      exists: true,
      index: all.indexOf(node),
      count: all.length,
      visible: a.top >= b.top - 1 && a.bottom <= b.bottom + 1,
      text: node.textContent?.trim() ?? null,
      highlighted: getComputedStyle(node).backgroundColor
    };
  });
}

function readPanelGeometry(page: Page) {
  return page.evaluate(() => {
    const node = document.querySelector<HTMLElement>("[data-anchored-popover]");
    if (!node) return null;
    const rect = node.getBoundingClientRect();
    return {
      position: getComputedStyle(node).position,
      strategy: node.dataset.strategy,
      parentIsBody: node.parentElement === document.body,
      zIndex: Number(getComputedStyle(node).zIndex),
      right: rect.right,
      left: rect.left,
      bottom: rect.bottom,
      top: rect.top,
      width: rect.width
    };
  });
}

async function openArtisanForm(page: Page) {
  await page.goto("/artisans/new");
  await expect(fieldSelect(page, "Craft")).toBeVisible({ timeout: 30_000 });
  await openSelect(page, "Craft", 2);
  await page.keyboard.press("Escape");
  await expect(panel(page)).toBeHidden();
  await page.waitForTimeout(600);
}

test.describe("Searchable select", () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page);
  });

  test("opening a dropdown moves nothing below it", async ({ page }) => {
    await openArtisanForm(page);

    const below = fieldSelect(page, "Gender");
    const beforeBelow = await box(below);
    const beforeTrigger = await box(fieldSelect(page, "Craft"));
    const beforePage = await readPage(page);

    await fieldSelect(page, "Craft").click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(400);

    const afterBelow = await box(below);
    const afterTrigger = await box(fieldSelect(page, "Craft"));
    const afterPage = await readPage(page);
    const geometry = await readPanelGeometry(page);
    console.log("[float]", JSON.stringify({ beforeBelow, afterBelow, geometry }));

    expect(afterBelow, "the Gender field moved when the Craft dropdown opened").toEqual(beforeBelow);
    expect(afterTrigger).toEqual(beforeTrigger);
    expect(afterPage.scrollHeight, "the page got taller when the dropdown opened").toBe(beforePage.scrollHeight);
    expect(afterPage.scrollY).toBe(beforePage.scrollY);

    expect(geometry).not.toBeNull();
    expect(geometry!.position).toBe("fixed");
    expect(geometry!.parentIsBody).toBe(true);
    expect(geometry!.zIndex).toBeGreaterThan(60);
    expect(geometry!.zIndex).toBeLessThan(100);
  });

  test("typing filters, the first match is really highlighted, and Enter takes it", async ({ page }) => {
    await openArtisanForm(page);
    await fieldSelect(page, "Craft").click();
    await expect(filterBox(page)).toBeFocused();

    const allLabels = await rows(page).allInnerTexts();
    expect(allLabels.length, "the craft list should be long enough to be worth searching").toBeGreaterThan(1);

    const target = allLabels[allLabels.length - 1].trim();
    const query = target.slice(0, Math.min(4, target.length));
    await page.keyboard.type(query);
    await page.waitForTimeout(200);

    const filtered = await rows(page).allInnerTexts();
    console.log("[filter]", JSON.stringify({ query, target, before: allLabels.length, after: filtered.length }));
    expect(filtered.length).toBeLessThanOrEqual(allLabels.length);
    expect(filtered.length).toBeGreaterThan(0);
    for (const label of filtered) {
      expect(label.toLowerCase()).toContain(query.toLowerCase());
    }

    const highlight = await readHighlight(page);
    console.log("[highlight]", JSON.stringify(highlight));
    expect(highlight, "no aria-activedescendant while filtering").not.toBeNull();
    expect(highlight!.exists, "aria-activedescendant points at nothing").toBe(true);
    expect(highlight!.index, "the highlight is not on the first match").toBe(0);
    expect(highlight!.visible, "the highlighted row is scrolled out of sight").toBe(true);
    expect(highlight!.highlighted).not.toBe("rgba(0, 0, 0, 0)");

    const chosen = highlight!.text;
    await filterBox(page).press("Enter");
    await expect(panel(page)).toBeHidden();
    await expect(fieldSelect(page, "Craft")).toContainText(chosen!);
  });

  test("a short fixed vocabulary keeps its plain list — no search box", async ({ page }) => {
    await openArtisanForm(page);

    await fieldSelect(page, "Gender").click();
    await expect(panel(page)).toBeVisible();
    await expect(rows(page)).toHaveCount(4);
    await expect(filterBox(page), "a 4-option enum grew a search box").toHaveCount(0);
    await page.keyboard.press("Escape");

    await expect(fieldSelect(page, "Gender")).toBeFocused();
    await page.keyboard.press("f");
    await expect(fieldSelect(page, "Gender")).toContainText("Female");
    await page.waitForTimeout(800);
    await page.keyboard.press("t");
    await expect(fieldSelect(page, "Gender")).toContainText("Transgender");

    await fieldSelect(page, "Craft").click();
    await expect(filterBox(page), "the craft list should be searchable").toHaveCount(1);
  });

  test("Escape closes, restores focus to the trigger, and keeps the old value", async ({ page }) => {
    await openArtisanForm(page);
    const trigger = fieldSelect(page, "Craft");
    const before = (await trigger.innerText()).trim();

    await openSelect(page, "Craft", 2);
    await page.keyboard.type("zzz");
    await expect(panel(page).getByText("No matches")).toBeVisible();
    await page.keyboard.press("Escape");

    await expect(panel(page)).toBeHidden();
    await expect(trigger).toBeFocused();
    expect((await trigger.innerText()).trim()).toBe(before);
  });

  test("keyboard alone: arrows move the highlight and Enter commits", async ({ page }) => {
    await openArtisanForm(page);
    await fieldSelect(page, "Craft").focus();
    await page.keyboard.press("ArrowDown");
    await expect(panel(page)).toBeVisible();

    await page.keyboard.press("ArrowDown");
    const moved = await readHighlight(page);
    expect(moved!.exists).toBe(true);
    expect(moved!.visible).toBe(true);
    const label = moved!.text;

    await page.keyboard.press("Enter");
    await expect(panel(page)).toBeHidden();
    await expect(fieldSelect(page, "Craft")).toContainText(label!);
  });
});

test.describe("Select all in a multi-select", () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page);
    await page.goto("/tools");
    await expect(fieldSelect(page, "Crafts")).toBeVisible({ timeout: 30_000 });
    await fieldSelect(page, "Crafts").scrollIntoViewIfNeeded();
    await page.waitForTimeout(500);
  });

  test("with a filter active it takes the matches, says so, and leaves the rest alone", async ({ page }) => {
    const trigger = fieldSelect(page, "Crafts");
    await openSelect(page, "Crafts", 3);

    const total = await rows(page).count();
    expect(total, "need several crafts for this to prove anything").toBeGreaterThan(2);
    await expect(panel(page).getByRole("button", { name: `Select all ${total}` })).toBeVisible();

    const labels = (await rows(page).allInnerTexts()).map((s) => s.trim().toLowerCase());
    let query = "";
    let expected = 0;
    outer: for (let length = 1; length <= 4; length++) {
      for (const label of labels) {
        for (let start = 0; start + length <= label.length; start++) {
          const candidate = label.slice(start, start + length);
          if (!candidate.trim()) continue;
          const n = labels.filter((other) => other.includes(candidate)).length;
          if (n > 1 && n < total) {
            query = candidate;
            expected = n;
            break outer;
          }
        }
      }
    }
    test.skip(!query, "no prefix on this corpus matches a strict subset of the crafts");
    console.log("[select-all]", JSON.stringify({ total, query, expected }));

    await page.keyboard.type(query);
    await page.waitForTimeout(200);
    await expect(rows(page)).toHaveCount(expected);

    const bulk = panel(page).getByRole("button", { name: `Select ${expected} matching` });
    await expect(bulk, "the button did not scope itself to the filter").toBeVisible();
    await bulk.click();

    await expect(panel(page).getByRole("button", { name: `Clear ${expected} matching` })).toBeVisible();
    for (const row of await rows(page).all()) {
      await expect(row).toHaveAttribute("aria-selected", "true");
    }

    for (let i = 0; i < query.length; i++) await page.keyboard.press("Backspace");
    await page.waitForTimeout(200);
    await expect(rows(page)).toHaveCount(total);
    await expect(trigger, "select-all reached past the filter").toContainText(`${expected} selected`);
    await expect(panel(page).getByRole("button", { name: `Select all ${total}` })).toBeVisible();

    await panel(page).getByRole("button", { name: `Select all ${total}` }).click();
    await expect(trigger).toContainText(`${total} selected`);
    await expect(panel(page).getByRole("button", { name: `Clear all ${total}` })).toBeVisible();
    await panel(page).getByRole("button", { name: `Clear all ${total}` }).click();
    await expect(trigger).toContainText("Select crafts");
  });
});

test.describe("Small screens and both themes", () => {
  test.use({ viewport: { width: 360, height: 740 } });

  test("360px: the panel fits, scrolls, and overflows nothing", async ({ page }) => {
    await signIn(page);
    await openArtisanForm(page);

    const before = await readPage(page);

    await openSelect(page, "Craft", 2);
    await page.waitForTimeout(400);

    const geometry = await readPanelGeometry(page);
    const metrics = await readPage(page);
    console.log("[360]", JSON.stringify({ before, geometry, metrics }));

    expect(geometry!.left).toBeGreaterThanOrEqual(0);
    expect(geometry!.right).toBeLessThanOrEqual(metrics.clientWidth);
    expect(geometry!.bottom).toBeLessThanOrEqual(740);
    expect(metrics.scrollWidth, "the panel widened the page at 360px").toBeLessThanOrEqual(before.scrollWidth);
    expect(before.scrollWidth, "the page itself overflows at 360px before anything is opened").toBe(before.clientWidth);

    const scroller = await page.evaluate(() => {
      const list = document.querySelector<HTMLElement>('[data-anchored-popover] [role="listbox"]');
      if (!list) return null;
      return { overflowY: getComputedStyle(list).overflowY, scrollHeight: list.scrollHeight, clientHeight: list.clientHeight };
    });
    expect(scroller!.overflowY).toBe("auto");
  });

  for (const theme of ["light", "dark"] as const) {
    test(`screenshot — ${theme}`, async ({ page }) => {
      await page.addInitScript(
        ([key, value]) => window.localStorage.setItem(key, value),
        ["field_repo_preferences", JSON.stringify({ theme })] as const
      );
      await page.emulateMedia({ colorScheme: theme });
      await signIn(page);
      await openArtisanForm(page);
      await page.evaluate((value) => document.documentElement.setAttribute("data-theme", value), theme);
      await expect(page.locator("html")).toHaveAttribute("data-theme", theme);

      await openSelect(page, "Craft", 2);
      await page.keyboard.type("a");
      await page.waitForTimeout(400);
      await page.screenshot({ path: `pw-screens/searchable-select-single-${theme}.png`, fullPage: false });

      await page.keyboard.press("Escape");
      await page.goto("/tools");
      await page.evaluate((value) => document.documentElement.setAttribute("data-theme", value), theme);
      await expect(fieldSelect(page, "Crafts")).toBeVisible({ timeout: 30_000 });
      await fieldSelect(page, "Crafts").scrollIntoViewIfNeeded();
      await page.waitForTimeout(400);
      await openSelect(page, "Crafts", 3);
      await page.waitForTimeout(400);
      await page.screenshot({ path: `pw-screens/searchable-select-multi-${theme}.png`, fullPage: false });
    });
  }
});
