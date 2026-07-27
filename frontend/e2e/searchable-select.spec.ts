import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * Searchable single- and multi-selects.
 *
 * Asked for by the user: "make the single select and multi-select drop downs searchable... if they
 * press enter then the first value gets chosen, in multi-select everywhere, also give the option
 * select all."
 *
 * Three of these tests are the three ways the feature can be quietly wrong rather than visibly
 * broken, so each is written as the failure rather than as the happy path:
 *
 * - **Enter takes a row nobody can see.** The whole point of a filter is that the top match is the
 *   one you meant; if the highlight is a stale index the panel commits something else and the
 *   researcher does not find out until the record is saved. So the spec reads
 *   `aria-activedescendant`, resolves it to a real node, and requires that node to be both the
 *   FIRST rendered row and inside the visible bounds of the scroller before it presses Enter.
 * - **"Select all" takes the whole corpus.** With a filter active it must act on the matches only.
 *   The test proves the negative: after selecting 2 matches out of 9 it clears the query and
 *   requires the count to still be 2, which is the only way to tell "selected the matches" apart
 *   from "selected everything and happened to show 2".
 * - **The panel pushes the page down.** Already reported once for the calendar. Same assertion
 *   shape here: box a field below the trigger, open, box again, require them identical.
 *
 * Read-only against the live API throughout — forms are opened and typed into, never submitted.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * Signed in once, then replayed.
 *
 * The first version of this spec logged in per test and the last three runs died on the sign-in
 * redirect: the live API rate-limits repeated logins from one address, so a nine-test file was
 * testing the throttle rather than the dropdowns. The session is captured out of localStorage on
 * the first pass and seeded before page scripts on every later one.
 *
 * `field_repo_preferences` is deliberately NOT replayed — the theme tests set it themselves, and
 * restoring a captured copy afterwards would quietly put the dark screenshot back into light.
 */
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

/**
 * The trigger inside the field whose label is exactly `label`.
 *
 * Matched through the `.field-label` span rather than the accessible name because `FormControls.Field`
 * wraps every control in a `<label>`, so the button's accessible name is the label text CONCATENATED
 * with its own — "Craft * Select existing craft" — which no readable name-based locator survives.
 * Anchoring the span text also keeps "Craft" from matching "Or new craft name".
 */
function fieldSelect(page: Page, label: string): Locator {
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${label}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

const panel = (page: Page) => page.locator("[data-anchored-popover]");
const filterBox = (page: Page) => panel(page).getByRole("combobox");
const rows = (page: Page) => panel(page).getByRole("option");

/**
 * Open a select and wait for the options that arrive over the network.
 *
 * Crafts, artisans and tools are all fetched after first paint, so a select can be visible, open
 * and genuinely empty for a second. Polling with the panel already open is enough — React fills the
 * live panel in when the response lands — and it keeps the wait out of the assertions themselves.
 */
async function openSelect(page: Page, label: string, minOptions = 2) {
  await fieldSelect(page, label).click();
  await expect(panel(page)).toBeVisible();
  await expect.poll(() => rows(page).count(), { timeout: 30_000 }).toBeGreaterThanOrEqual(minOptions);
}

/** Rounded to a tenth of a pixel — sub-pixel jitter from font loading is not a reflow. */
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

/**
 * The highlighted row, resolved the way a screen reader would resolve it, plus whether it is
 * actually on screen inside its scroller. "Highlighted but scrolled out of view" is precisely the
 * bug this returns enough information to catch.
 */
function readHighlight(page: Page) {
  return page.evaluate(() => {
    const panelNode = document.querySelector<HTMLElement>("[data-anchored-popover]");
    if (!panelNode) return null;
    // Whoever currently owns the keyboard: the filter box on a long list, the trigger on a short
    // one that never grew a filter box. Looking only inside the panel would miss the second.
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
      // Fully inside the scroller's own viewport, not merely present in the DOM.
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
  // Prime the craft list once, here, rather than sprinkling waits through the assertions: until it
  // lands the select holds only its placeholder, which is one option — below the search threshold —
  // so a test that opened too early would be measuring the plain-list behaviour by accident.
  await openSelect(page, "Craft", 2);
  await page.keyboard.press("Escape");
  await expect(panel(page)).toBeHidden();
  // The island nav springs between shapes for a moment after load; measuring through that would
  // read a page that is still settling rather than one at rest.
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
    // Let the open spring finish: a panel still animating could mask a reflow that arrives late.
    await page.waitForTimeout(400);

    const afterBelow = await box(below);
    const afterTrigger = await box(fieldSelect(page, "Craft"));
    const afterPage = await readPage(page);
    const geometry = await readPanelGeometry(page);
    console.log("[float]", JSON.stringify({ beforeBelow, afterBelow, geometry }));

    // THE assertion — the field below the trigger is where a push-down shows up.
    expect(afterBelow, "the Gender field moved when the Craft dropdown opened").toEqual(beforeBelow);
    expect(afterTrigger).toEqual(beforeTrigger);
    expect(afterPage.scrollHeight, "the page got taller when the dropdown opened").toBe(beforePage.scrollHeight);
    expect(afterPage.scrollY).toBe(beforePage.scrollY);

    // Genuinely floating rather than merely small: out of flow, portalled clear of any clipping
    // ancestor, and stacked over the island nav (z-50) but under the dialog layer (100).
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

    // A query taken from a craft that is NOT already first, so "Enter picked the top match" cannot
    // pass by accident on a list that was already in the right order.
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

    // The highlight has to be a real, visible, FIRST row before Enter is allowed to mean anything.
    const highlight = await readHighlight(page);
    console.log("[highlight]", JSON.stringify(highlight));
    expect(highlight, "no aria-activedescendant while filtering").not.toBeNull();
    expect(highlight!.exists, "aria-activedescendant points at nothing").toBe(true);
    expect(highlight!.index, "the highlight is not on the first match").toBe(0);
    expect(highlight!.visible, "the highlighted row is scrolled out of sight").toBe(true);
    // Painted, not merely flagged — a highlight the eye cannot find is the bug.
    expect(highlight!.highlighted).not.toBe("rgba(0, 0, 0, 0)");

    const chosen = highlight!.text;
    await filterBox(page).press("Enter");
    await expect(panel(page)).toBeHidden();
    await expect(fieldSelect(page, "Craft")).toContainText(chosen!);
  });

  test("a short fixed vocabulary keeps its plain list — no search box", async ({ page }) => {
    await openArtisanForm(page);

    // Gender is four options and will never be more. A filter box there is noise, an extra tab
    // stop and a "no matches" state on a list you can read in one glance.
    await fieldSelect(page, "Gender").click();
    await expect(panel(page)).toBeVisible();
    await expect(rows(page)).toHaveCount(4);
    await expect(filterBox(page), "a 4-option enum grew a search box").toHaveCount(0);
    await page.keyboard.press("Escape");

    // And it keeps what the native <select> was good at. Focus the closed control, press a letter,
    // get the value — the fastest way through a four-option enum, and the thing a filter box would
    // have quietly taken away from every Status and Gender field in the app.
    await expect(fieldSelect(page, "Gender")).toBeFocused();
    await page.keyboard.press("f");
    await expect(fieldSelect(page, "Gender")).toContainText("Female");
    // Past the 700ms window, so this is a fresh search and not "ft" — the rolling buffer is the
    // native behaviour and pressing the two keys together really should find nothing.
    await page.waitForTimeout(800);
    await page.keyboard.press("t");
    await expect(fieldSelect(page, "Gender")).toContainText("Transgender");

    // Craft is nine-plus and backed by records, so it does get one.
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

    // A query that matches more than one but fewer than all — otherwise "matching" and "all" are
    // the same set and the test proves nothing.
    //
    // Counted by SUBSTRING, because that is what the control does. An earlier version of this block
    // counted by prefix and then asserted 3 where the panel quite rightly showed 7: typing "b" into
    // nine crafts finds "Dabu" and "Block Printing" as well as the three that start with it. The
    // expectation, not the filter, was wrong.
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

    // Typed, not filled. `fill` scrolls its target into view; the panel is a fixed-position portal,
    // so that scrolls the PAGE, which carries the anchor out of the viewport, which AnchoredPopover
    // correctly treats as "close". The filter box is already focused on open, so typing is both
    // safe and what a reader actually does.
    await page.keyboard.type(query);
    await page.waitForTimeout(200);
    await expect(rows(page)).toHaveCount(expected);

    // The label has to say which of the two things it is about to do.
    const bulk = panel(page).getByRole("button", { name: `Select ${expected} matching` });
    await expect(bulk, "the button did not scope itself to the filter").toBeVisible();
    await bulk.click();

    // It toggles to Clear, rather than making the reader guess whether it worked.
    await expect(panel(page).getByRole("button", { name: `Clear ${expected} matching` })).toBeVisible();
    for (const row of await rows(page).all()) {
      await expect(row).toHaveAttribute("aria-selected", "true");
    }

    // THE negative. Clearing the query must reveal exactly the matches and nothing else; if
    // "select all" had ignored the filter this count would be `total`.
    for (let i = 0; i < query.length; i++) await page.keyboard.press("Backspace");
    await page.waitForTimeout(200);
    await expect(rows(page)).toHaveCount(total);
    await expect(trigger, "select-all reached past the filter").toContainText(`${expected} selected`);
    await expect(panel(page).getByRole("button", { name: `Select all ${total}` })).toBeVisible();

    // And the unfiltered button really does take everything when nothing is filtered.
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

    // Measured with the panel SHUT first. Other work is live in this repo and a page can arrive
    // carrying its own overflow; comparing before with after is the only way to attribute one to
    // the panel rather than to whatever else is on the page that day.
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

    // The list scrolls inside the panel rather than the panel running off the screen.
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
      // Seeding localStorage is not enough on its own: the app also GETs /preferences/me and
      // re-applies the ACCOUNT's stored theme once it lands, which put the dark run back into
      // light. Stamping the attribute after the page has settled is the client-only way to pin it
      // — writing the preference back would mean a PUT against the production database.
      await page.evaluate((value) => document.documentElement.setAttribute("data-theme", value), theme);
      await expect(page.locator("html")).toHaveAttribute("data-theme", theme);

      await openSelect(page, "Craft", 2);
      // Typed rather than filled: `fill` scrolls its target into view, and at 360px that scrolls
      // the ANCHOR out of the viewport, which AnchoredPopover quite correctly treats as "close".
      // The box is already focused on open, so typing is also what a reader actually does.
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
