import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * The date picker floats. It does not push the page around.
 *
 * Reported by the user: "when our calendar opens, instead of overlaying upon other components while
 * it is active, it pushes the rest down". The calendar was rendered inline, in document flow, so it
 * occupied real layout space and every field below the date row slid down the page as it opened —
 * which also meant the control the reader was heading for moved out from under their cursor.
 *
 * The first test is that complaint, stated as an assertion: read the bounding box of a field BELOW
 * the trigger, open the picker, read it again, and require the two to be identical. Everything after
 * it covers the ways a floating panel goes wrong once it no longer reflows — opening off the bottom
 * of the screen, hanging off the right edge, detaching on scroll, or trapping the keyboard.
 *
 * The workshop form is the page under test because its date field sits in the middle of a long form,
 * which is precisely the shape that made the bug visible.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

// Exact, by role: the icon button beside each input is named "Open calendar for start date", which
// a substring label match would also pick up.
const startInput = (page: Page) => page.getByRole("textbox", { name: "Start date", exact: true });
const endInput = (page: Page) => page.getByRole("textbox", { name: "End date", exact: true });
const panel = (page: Page) => page.getByRole("dialog", { name: "Choose a date range" });
/** A field well below the date row: if anything reflows, this is what moves. */
const below = (page: Page) => page.getByLabel("Description", { exact: true });

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

/** Page-level geometry, in one round trip so every number is from the same moment. */
function readPage(page: Page) {
  return page.evaluate(() => ({
    scrollHeight: document.documentElement.scrollHeight,
    scrollY: window.scrollY,
    viewportHeight: document.documentElement.clientHeight,
    viewportWidth: document.documentElement.clientWidth
  }));
}

/** Where the panel actually is, and how it is positioned — the two things the fix is about. */
function readPanel(page: Page) {
  return page.evaluate(() => {
    const node = document.querySelector<HTMLElement>("[data-anchored-popover]");
    if (!node) return null;
    const rect = node.getBoundingClientRect();
    return {
      top: rect.top,
      left: rect.left,
      right: rect.right,
      bottom: rect.bottom,
      width: rect.width,
      height: rect.height,
      position: getComputedStyle(node).position,
      side: node.dataset.side,
      strategy: node.dataset.strategy,
      parentIsBody: node.parentElement === document.body,
      zIndex: Number(getComputedStyle(node).zIndex)
    };
  });
}

async function openWorkshopForm(page: Page) {
  await page.goto("/workshops");
  await expect(startInput(page)).toBeVisible({ timeout: 30_000 });
  // The island nav springs between shapes for a moment after load; measuring through that would
  // read a page that is still settling rather than one at rest.
  await page.waitForTimeout(600);
}

test.describe("Date picker floats over the form", () => {
  test.beforeEach(async ({ page }) => {
    // Every test here pays a full sign-in against the live API plus a Next dev compile of
    // /workshops before it asserts anything. That does not fit the default budget on a loaded
    // machine — three different tests have timed out in this hook while the assertions themselves
    // were never reached — so the whole group gets the longer one.
    test.slow();
    await signIn(page);
    await openWorkshopForm(page);
  });

  test("opening the calendar moves nothing below it", async ({ page }) => {
    const beforeBelow = await box(below(page));
    const beforeStart = await box(startInput(page));
    const beforePage = await readPage(page);
    console.log("[reflow] before", JSON.stringify({ beforeBelow, beforePage }));

    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    // Let the open spring finish: a panel still animating could mask a reflow that arrives late.
    await page.waitForTimeout(400);

    const afterBelow = await box(below(page));
    const afterStart = await box(startInput(page));
    const afterPage = await readPage(page);
    const geometry = await readPanel(page);
    console.log("[reflow] after", JSON.stringify({ afterBelow, afterPage, geometry }));

    // THE assertion. The field below the trigger is where the push-down showed up.
    expect(afterBelow, "a field below the trigger moved when the calendar opened").toEqual(beforeBelow);
    // And the trigger itself, which would move if the panel were inserted above it instead.
    expect(afterStart).toEqual(beforeStart);
    // Nothing grew the document either — a reflow that happens entirely below the fold is still one.
    expect(afterPage.scrollHeight, "the page got taller when the calendar opened").toBe(beforePage.scrollHeight);
    expect(afterPage.scrollY).toBe(beforePage.scrollY);

    // It is genuinely floating, not merely small: taken out of flow, portalled clear of any clipping
    // ancestor, and stacked over the island nav (z-50) while staying under the dialog layer (100).
    expect(geometry).not.toBeNull();
    expect(geometry!.position).toBe("fixed");
    expect(geometry!.strategy).toBe("fixed");
    expect(geometry!.parentIsBody).toBe(true);
    expect(geometry!.zIndex).toBeGreaterThan(60);
    expect(geometry!.zIndex).toBeLessThan(100);

    // And it is on top: the topmost element at the panel's centre belongs to the panel.
    const hitsPanel = await page.evaluate(() => {
      const node = document.querySelector<HTMLElement>("[data-anchored-popover]")!;
      const rect = node.getBoundingClientRect();
      const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
      return Boolean(hit && node.contains(hit));
    });
    expect(hitsPanel, "something is painted over the calendar").toBe(true);
  });

  test("near the bottom of the viewport it opens upward instead of off-screen", async ({ page }) => {
    // A short viewport is how the trigger is put near the bottom edge. Scrolling cannot do it: the
    // form sits near the top of the document, and scrolling only ever moves it further UP the screen.
    await page.setViewportSize({ width: 1280, height: 420 });
    await page.waitForTimeout(400);

    const trigger = await box(startInput(page));
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(400);

    const geometry = (await readPanel(page))!;
    const viewport = await readPage(page);
    console.log("[flip] ", JSON.stringify({ trigger, geometry, viewport }));

    // The premise: there genuinely is not room below, or the flip proves nothing.
    const roomBelow = viewport.viewportHeight - (trigger.y + trigger.height);
    expect(roomBelow, "staging is wrong — the panel would have fitted below anyway").toBeLessThan(geometry.height);

    expect(geometry.side, "the panel should have flipped above the trigger").toBe("top");
    // Above the trigger, and wholly on screen — the point of flipping.
    expect(geometry.bottom).toBeLessThanOrEqual(trigger.y + 1);
    expect(geometry.top).toBeGreaterThanOrEqual(0);
    expect(geometry.bottom).toBeLessThanOrEqual(viewport.viewportHeight);
  });

  test("near the right edge it shifts left to stay on screen", async ({ page }) => {
    // Nothing on today's pages sits hard against the right edge, so the case is staged — but it is
    // the real anchor, really re-measured, and the panel is wider than the room left beside it.
    await page.evaluate(() => {
      const input = document.querySelector<HTMLElement>("input[placeholder='dd/mm/yyyy']")!;
      const anchor = input.closest<HTMLElement>("div.relative.grid")!;
      anchor.style.position = "fixed";
      anchor.style.display = "block";
      anchor.style.top = "180px";
      anchor.style.right = "8px";
      anchor.style.left = "auto";
      anchor.style.width = "200px";
    });
    await page.waitForTimeout(300);

    const anchorLeft = (await box(startInput(page))).x;
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(400);

    const geometry = (await readPanel(page))!;
    const viewport = await readPage(page);
    console.log("[edge] ", JSON.stringify({ geometry, anchorLeft, viewport }));

    // The premise: left-aligning the panel with the anchor would have hung it off the edge.
    expect(anchorLeft + geometry.width, "staging is wrong — the panel would have fitted anyway").toBeGreaterThan(
      viewport.viewportWidth
    );

    expect(geometry.right, "the panel hangs off the right edge").toBeLessThanOrEqual(viewport.viewportWidth);
    expect(geometry.left).toBeGreaterThanOrEqual(0);
    // It genuinely shifted rather than merely fitting by luck.
    expect(geometry.left).toBeLessThan(anchorLeft);
  });

  test("the panel follows the trigger on scroll and lets go when it leaves", async ({ page }) => {
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(400);

    const trigger = await box(startInput(page));
    const before = (await readPanel(page))!;
    const gap = before.top - (trigger.y + trigger.height);

    await page.mouse.wheel(0, 120);
    await page.waitForTimeout(300);

    const movedTrigger = await box(startInput(page));
    const after = (await readPanel(page))!;
    console.log("[scroll] ", JSON.stringify({ before, after, gap }));

    // Still glued to the trigger, at the same offset — not left hovering where the field used to be.
    expect(Math.abs(after.top - (movedTrigger.y + movedTrigger.height) - gap)).toBeLessThanOrEqual(1.5);

    // Scrolled clean past the field, it closes rather than floating over unrelated content.
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(400);
    await expect(panel(page)).toBeHidden();
  });

  test("keyboard: arrows move by day, PageDown by month, Escape returns focus", async ({ page }) => {
    await startInput(page).focus();
    // Focus alone must not open it, or tabbing through a form pops a panel at every date.
    await expect(panel(page)).toBeHidden();

    await page.keyboard.press("ArrowDown");
    await expect(panel(page)).toBeVisible();

    // ArrowDown from the input hands focus to the grid, on a real day.
    const readFocus = () =>
      page.evaluate(() => {
        const node = document.activeElement as HTMLElement | null;
        const panelNode = document.querySelector("[data-anchored-popover]");
        return {
          tag: node?.tagName ?? null,
          label: node?.getAttribute("aria-label") ?? null,
          insidePanel: Boolean(node && panelNode?.contains(node))
        };
      });

    const landed = await readFocus();
    expect(landed.insidePanel, "ArrowDown should move focus into the calendar").toBe(true);
    expect(landed.label, "focus should be on a day button, which is labelled with its date").toBeTruthy();

    await page.keyboard.press("ArrowRight");
    const nextDay = await readFocus();
    expect(nextDay.label).not.toBe(landed.label);

    await page.keyboard.press("PageDown");
    const nextMonth = await readFocus();
    console.log("[keys] ", JSON.stringify({ landed, nextDay, nextMonth }));
    expect(nextMonth.label).not.toBe(nextDay.label);

    // Home moves to the start of the week rather than anywhere else on the grid.
    await page.keyboard.press("Home");
    const weekStart = await readFocus();
    expect(weekStart.label).not.toBe(nextMonth.label);

    // Escape closes and puts the caret back where the typist left it.
    await page.keyboard.press("Escape");
    await expect(panel(page)).toBeHidden();
    await expect(startInput(page)).toBeFocused();
  });

  test("typing a date still works, and drives the calendar", async ({ page }) => {
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();

    // The panel is open and focus is still in the input: the calendar is an extra, not an interruption.
    await expect(startInput(page)).toBeFocused();
    await startInput(page).fill("");
    await startInput(page).pressSequentially("14/03/2027", { delay: 20 });

    // The typed date reached the form's wire value, in the UTC shape the endpoint expects.
    const submitted = await page.evaluate(() => ({
      startDate: document.querySelector<HTMLInputElement>("input[name='startDate']")?.value ?? null,
      date: document.querySelector<HTMLInputElement>("input[name='date']")?.value ?? null
    }));
    console.log("[typing] ", JSON.stringify(submitted));
    expect(submitted.startDate).toBe("2027-03-14T00:00:00.000Z");
    expect(submitted.date).toBe(submitted.startDate);

    // And the grid followed the typist to March 2027 rather than sitting on today.
    await expect(panel(page).getByText("March 2027")).toBeVisible();

    // The end date was dragged along rather than left behind the start.
    await expect(endInput(page)).toHaveValue("14/03/2027");
  });

  test("clicking days moves the range endpoints and the hidden inputs follow", async ({ page }) => {
    const day = (iso: string) => page.locator(`[data-anchored-popover] td[data-day="${iso}"] button`);
    const saved = () =>
      page.evaluate(() => ({
        start: (document.querySelector<HTMLInputElement>("input[name='startDate']")?.value ?? "").slice(0, 10),
        end: (document.querySelector<HTMLInputElement>("input[name='endDate']")?.value ?? "").slice(0, 10),
        date: (document.querySelector<HTMLInputElement>("input[name='date']")?.value ?? "").slice(0, 10)
      }));

    await startInput(page).click();
    await expect(panel(page)).toBeVisible();

    // The form opens on today..today, so this is react-day-picker's "add a date to a COMPLETE range"
    // rule, unchanged from before this work: a click before the start moves the start and leaves the
    // end alone; any later click moves the end. The selection is passed through exactly as the
    // library reports it — filling in a half-finished `to` here would quietly change these semantics.
    await day("2026-07-06").click();
    await expect(startInput(page)).toHaveValue("06/07/2026");
    await expect(endInput(page)).toHaveValue("27/07/2026");

    await day("2026-07-16").click();
    await expect(startInput(page)).toHaveValue("06/07/2026");
    await expect(endInput(page)).toHaveValue("16/07/2026");

    await day("2026-07-21").click();
    await expect(endInput(page)).toHaveValue("21/07/2026");

    // The wire contract the workshops endpoint depends on: start of the start day, end of the end
    // day, and `date` mirroring `startDate` — all three still derived from the visible selection.
    const wire = await saved();
    console.log("[range] ", JSON.stringify(wire));
    expect(wire).toEqual({ start: "2026-07-06", end: "2026-07-21", date: "2026-07-06" });

    const full = await page.evaluate(() => ({
      start: document.querySelector<HTMLInputElement>("input[name='startDate']")?.value,
      end: document.querySelector<HTMLInputElement>("input[name='endDate']")?.value
    }));
    expect(full.start).toBe("2026-07-06T00:00:00.000Z");
    expect(full.end).toBe("2026-07-21T23:59:59.999Z");
  });

  test("the in-range band, its endpoints and today all invert in dark mode", async ({ page }) => {
    // A range that does NOT contain today, so the band and the today marker are on screen together.
    await startInput(page).click();
    await startInput(page).fill("");
    await startInput(page).pressSequentially("05/07/2026", { delay: 15 });
    await endInput(page).click();
    await endInput(page).fill("");
    await endInput(page).pressSequentially("18/07/2026", { delay: 15 });
    await expect(panel(page)).toBeVisible();

    for (const [theme, band, todayText, todayRing] of [
      ["light", "oklch(0.977 0.013 305)", "oklch(0.47 0.198 305)", "oklch(0.9 0.058 305)"],
      ["dark", "oklch(0.255 0.108 305)", "oklch(0.828 0.1 305)", "oklch(0.4 0.18 305)"]
    ] as const) {
      await page.evaluate((value) => {
        document.documentElement.dataset.theme = value;
      }, theme);
      await page.waitForTimeout(250);

      const paint = await page.evaluate(() => {
        const node = document.querySelector<HTMLElement>("[data-anchored-popover]")!;
        const cell = (iso: string) => node.querySelector<HTMLElement>(`td[data-day="${iso}"]`);
        const read = (el: HTMLElement | null) => ({
          cellBackground: el ? getComputedStyle(el).backgroundColor : null,
          buttonBackground: el?.querySelector("button") ? getComputedStyle(el.querySelector("button")!).backgroundColor : null
        });
        const today = node.querySelector<HTMLElement>("[data-today] button");
        return {
          start: read(cell("2026-07-05")),
          middle: read(cell("2026-07-10")),
          end: read(cell("2026-07-18")),
          outsideBand: read(cell("2026-07-22")),
          todayColor: today ? getComputedStyle(today).color : null,
          todayShadow: today ? getComputedStyle(today).boxShadow : null
        };
      });
      console.log(`[band:${theme}]`, JSON.stringify(paint));

      // The band. purple-50 on a white card and purple-950 on a dark one — a calendar that keeps the
      // light wash in dark mode paints a white slab across the middle of the selection, which is the
      // single most likely way for this to regress.
      expect(paint.start.cellBackground).toBe(band);
      expect(paint.middle.cellBackground).toBe(band);
      expect(paint.end.cellBackground).toBe(band);
      // Days outside the range stay unpainted in both themes.
      expect(paint.outsideBand.cellBackground).toBe("rgba(0, 0, 0, 0)");

      // The endpoints are brand purple, which does NOT invert; the middle keeps a bare button.
      expect(paint.start.buttonBackground).toBe("oklch(0.47 0.198 305)");
      expect(paint.end.buttonBackground).toBe("oklch(0.47 0.198 305)");
      expect(paint.middle.buttonBackground).toBe("rgba(0, 0, 0, 0)");

      // Today lightens in dark mode, and is ringed as well as tinted so it survives colour blindness.
      expect(paint.todayColor).toBe(todayText);
      expect(paint.todayShadow).toContain(todayRing);
    }
  });

  test("inside a dialog it portals into the dialog panel, not over it", async ({ page }) => {
    // No date field sits inside a dialog today, so the branch is staged with the two things
    // FieldDialog's panel actually provides: the marker attribute the popover looks for, and the
    // positioned, padded, bordered box it has to measure its offsets against.
    await page.evaluate(() => {
      const form = document.querySelector<HTMLElement>("form")!;
      form.setAttribute("data-field-dialog", "");
      form.style.position = "relative";
    });
    await page.waitForTimeout(200);

    const trigger = await box(startInput(page));
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(400);

    const geometry = (await readPanel(page))!;
    const parent = await page.evaluate(() => {
      const node = document.querySelector<HTMLElement>("[data-anchored-popover]")!;
      return { tag: node.parentElement?.tagName ?? null, isDialogPanel: node.parentElement?.hasAttribute("data-field-dialog") ?? false };
    });
    console.log("[dialog] ", JSON.stringify({ trigger, geometry, parent }));

    // Inside the dialog's own panel, so focus stays where FieldDialog's focus guard expects it and
    // the popover cannot out-rank a dialog stacked above it.
    expect(parent.isDialogPanel).toBe(true);
    expect(geometry.strategy).toBe("absolute");
    expect(geometry.position).toBe("absolute");
    expect(geometry.parentIsBody).toBe(false);

    // And it still lands under the trigger in VIEWPORT terms — which is what proves the conversion
    // from viewport coordinates to the host's padding box (border correction included) is right.
    expect(Math.abs(geometry.left - trigger.x)).toBeLessThanOrEqual(1);
    expect(geometry.top).toBeGreaterThanOrEqual(trigger.y + trigger.height);
    expect(geometry.top - (trigger.y + trigger.height)).toBeLessThanOrEqual(10);
  });

  test("Escape is swallowed before a dialog's document-level handler can see it", async ({ page }) => {
    // FieldDialog closes on Escape from a document-level CAPTURE listener. A picker inside a dialog
    // must consume Escape first, or dismissing the calendar would take the whole dialog with it.
    // This stands in for that listener exactly: same node, same phase, registered first.
    await page.evaluate(() => {
      const win = window as unknown as { __dialogEscapes: number };
      win.__dialogEscapes = 0;
      document.addEventListener(
        "keydown",
        (event) => {
          if (event.key === "Escape") win.__dialogEscapes += 1;
        },
        true
      );
    });

    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(panel(page)).toBeHidden();

    const seen = await page.evaluate(() => (window as unknown as { __dialogEscapes: number }).__dialogEscapes);
    console.log("[escape] document-level capture listener fired", seen, "times");
    expect(seen, "Escape reached the dialog layer and would have closed the dialog too").toBe(0);

    // A second Escape, with the popover already closed, must pass straight through — the popover
    // swallows the key only while it is the thing being dismissed.
    await page.keyboard.press("Escape");
    const after = await page.evaluate(() => (window as unknown as { __dialogEscapes: number }).__dialogEscapes);
    expect(after, "the popover is still eating Escape after it has closed").toBe(1);
  });

  test("themed in light and dark", async ({ page }) => {
    for (const theme of ["light", "dark"] as const) {
      await page.evaluate((value) => {
        document.documentElement.dataset.theme = value;
      }, theme);
      await page.waitForTimeout(200);
      await startInput(page).click();
      await expect(panel(page)).toBeVisible();
      await page.waitForTimeout(400);

      // Nothing in the calendar may fall back to a raw browser default or a marketing colour. The
      // check is on the selected day, which is the one cell that must be brand purple in both themes.
      const paint = await page.evaluate(() => {
        const node = document.querySelector<HTMLElement>("[data-anchored-popover]")!;
        const selected = node.querySelector<HTMLElement>("[data-selected] button");
        const weekday = node.querySelector<HTMLElement>("th");
        return {
          panelBackground: getComputedStyle(node).backgroundColor,
          selectedBackground: selected ? getComputedStyle(selected).backgroundColor : null,
          selectedColor: selected ? getComputedStyle(selected).color : null,
          weekdayColor: weekday ? getComputedStyle(weekday).color : null,
          fontFamily: getComputedStyle(node).fontFamily
        };
      });
      console.log(`[theme:${theme}] `, JSON.stringify(paint));

      expect(paint.selectedBackground, "the selected day is not brand purple").toMatch(/^(rgb|oklch|color)/);
      // Inter first, and no serif face anywhere. Checked as whole families: the generic fallback
      // "sans-serif" contains the substring "serif", so a naive `toContain` passes nothing useful.
      const families = paint.fontFamily.toLowerCase().split(",").map((name) => name.trim().replace(/^["']|["']$/g, ""));
      expect(families[0]).toContain("inter");
      expect(families).not.toContain("serif");
      expect(families).not.toContain("ui-serif");
      expect(families).not.toContain("times new roman");

      await page.screenshot({ path: `${test.info().outputDir}/calendar-${theme}.png`, fullPage: false });
      await page.keyboard.press("Escape");
      await expect(panel(page)).toBeHidden();
    }
  });
});

/**
 * Reduced motion. The open/close transition is the only animation here, and it has to disappear
 * entirely — not merely shorten — for anyone who has asked the OS for less movement.
 */
test.describe("Reduced motion", () => {
  /** Every frame of the window the spring would have occupied. */
  async function sampleTransforms(page: Page) {
    await page.evaluate(() => {
      const win = window as unknown as { __transforms: string[] };
      win.__transforms = [];
      const tick = () => {
        const node = document.querySelector<HTMLElement>("[data-anchored-popover]");
        if (node) win.__transforms.push(getComputedStyle(node).transform);
        if (win.__transforms.length < 40) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
  }

  const readTransforms = (page: Page) =>
    page.evaluate(() => (window as unknown as { __transforms: string[] }).__transforms);

  const moving = (frames: string[]) => frames.filter((value) => value !== "none" && value !== "matrix(1, 0, 0, 1, 0, 0)");

  test("the OS setting removes the transition entirely", async ({ page }) => {
    test.slow(); // signs in and compiles /workshops before it measures anything, same as the group above
    // Set before anything mounts, so the panel is built with the preference already in force.
    await page.emulateMedia({ reducedMotion: "reduce" });
    await signIn(page);
    await openWorkshopForm(page);

    await sampleTransforms(page);
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(500);

    const frames = await readTransforms(page);
    console.log("[reduced:os] frames", frames.length, "moving:", JSON.stringify(moving(frames).slice(0, 4)));
    expect(frames.length, "the sampler never saw the panel").toBeGreaterThan(0);
    expect(moving(frames), "the panel still slides and scales under prefers-reduced-motion").toEqual([]);

    // Reduced motion must not cost correctness: still anchored, still floating.
    const geometry = (await readPanel(page))!;
    const trigger = await box(startInput(page));
    expect(geometry.position).toBe("fixed");
    expect(Math.abs(geometry.left - trigger.x)).toBeLessThanOrEqual(1);
  });

  /*
   * There is deliberately NO test here for the app's own reduced-motion toggle, and it is not an
   * oversight. The panel reads `data-reduced-motion` off <html> as well as the OS media query (see
   * `useLessMotion` in components/ui/AnchoredPopover) because globals.css answers that attribute by
   * disabling CSS transitions, which cannot reach framer-motion's frame-by-frame inline styles.
   *
   * That branch cannot be staged from a spec. ThemeProvider is the single writer of the attribute and
   * re-stamps <html> from its own state — on hydration, when the remote preferences arrive, and on
   * every later preferences render — so an injected value is erased, including after a retry loop had
   * already seen it hold. Seeding localStorage does not survive the `/preferences/me` fetch either.
   * The only faithful route is toggling the real setting, which would mutate a live account.
   *
   * So the attribute branch is reasoned but unverified in a browser; the OS branch above is the one
   * under test, and is the mechanism nearly every reader will actually hit.
   */

  test("without the preference, the panel does animate — so the tests above have teeth", async ({ page }) => {
    test.slow();
    await signIn(page);
    await openWorkshopForm(page);

    await sampleTransforms(page);
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(500);

    const frames = await readTransforms(page);
    console.log("[reduced:none] frames", frames.length, "moving:", moving(frames).length);
    expect(moving(frames).length, "no animation at all was observed, so the reduced-motion specs prove nothing").toBeGreaterThan(0);
  });
});
