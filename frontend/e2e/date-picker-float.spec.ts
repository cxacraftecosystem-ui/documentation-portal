import { expect, test, type Locator, type Page } from "@playwright/test";

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

const startInput = (page: Page) => page.getByRole("textbox", { name: "Start date", exact: true });
const endInput = (page: Page) => page.getByRole("textbox", { name: "End date", exact: true });
const panel = (page: Page) => page.getByRole("dialog", { name: "Choose a date range" });
const below = (page: Page) => page.getByLabel("Description", { exact: true });

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
    scrollY: window.scrollY,
    viewportHeight: document.documentElement.clientHeight,
    viewportWidth: document.documentElement.clientWidth
  }));
}

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
  await page.waitForTimeout(600);
}

test.describe("Date picker floats over the form", () => {
  test.beforeEach(async ({ page }) => {
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
    await page.waitForTimeout(400);

    const afterBelow = await box(below(page));
    const afterStart = await box(startInput(page));
    const afterPage = await readPage(page);
    const geometry = await readPanel(page);
    console.log("[reflow] after", JSON.stringify({ afterBelow, afterPage, geometry }));

    expect(afterBelow, "a field below the trigger moved when the calendar opened").toEqual(beforeBelow);
    expect(afterStart).toEqual(beforeStart);
    expect(afterPage.scrollHeight, "the page got taller when the calendar opened").toBe(beforePage.scrollHeight);
    expect(afterPage.scrollY).toBe(beforePage.scrollY);

    expect(geometry).not.toBeNull();
    expect(geometry!.position).toBe("fixed");
    expect(geometry!.strategy).toBe("fixed");
    expect(geometry!.parentIsBody).toBe(true);
    expect(geometry!.zIndex).toBeGreaterThan(60);
    expect(geometry!.zIndex).toBeLessThan(100);

    const hitsPanel = await page.evaluate(() => {
      const node = document.querySelector<HTMLElement>("[data-anchored-popover]")!;
      const rect = node.getBoundingClientRect();
      const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
      return Boolean(hit && node.contains(hit));
    });
    expect(hitsPanel, "something is painted over the calendar").toBe(true);
  });

  test("near the bottom of the viewport it opens upward instead of off-screen", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 420 });
    await page.waitForTimeout(400);

    const trigger = await box(startInput(page));
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();
    await page.waitForTimeout(400);

    const geometry = (await readPanel(page))!;
    const viewport = await readPage(page);
    console.log("[flip] ", JSON.stringify({ trigger, geometry, viewport }));

    const roomBelow = viewport.viewportHeight - (trigger.y + trigger.height);
    expect(roomBelow, "staging is wrong — the panel would have fitted below anyway").toBeLessThan(geometry.height);

    expect(geometry.side, "the panel should have flipped above the trigger").toBe("top");
    expect(geometry.bottom).toBeLessThanOrEqual(trigger.y + 1);
    expect(geometry.top).toBeGreaterThanOrEqual(0);
    expect(geometry.bottom).toBeLessThanOrEqual(viewport.viewportHeight);
  });

  test("near the right edge it shifts left to stay on screen", async ({ page }) => {
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

    expect(anchorLeft + geometry.width, "staging is wrong — the panel would have fitted anyway").toBeGreaterThan(
      viewport.viewportWidth
    );

    expect(geometry.right, "the panel hangs off the right edge").toBeLessThanOrEqual(viewport.viewportWidth);
    expect(geometry.left).toBeGreaterThanOrEqual(0);
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

    expect(Math.abs(after.top - (movedTrigger.y + movedTrigger.height) - gap)).toBeLessThanOrEqual(1.5);

    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(400);
    await expect(panel(page)).toBeHidden();
  });

  test("keyboard: arrows move by day, PageDown by month, Escape returns focus", async ({ page }) => {
    await startInput(page).focus();
    await expect(panel(page)).toBeHidden();

    await page.keyboard.press("ArrowDown");
    await expect(panel(page)).toBeVisible();

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

    await page.keyboard.press("Home");
    const weekStart = await readFocus();
    expect(weekStart.label).not.toBe(nextMonth.label);

    await page.keyboard.press("Escape");
    await expect(panel(page)).toBeHidden();
    await expect(startInput(page)).toBeFocused();
  });

  test("typing a date still works, and drives the calendar", async ({ page }) => {
    await startInput(page).click();
    await expect(panel(page)).toBeVisible();

    await expect(startInput(page)).toBeFocused();
    await startInput(page).fill("");
    await startInput(page).pressSequentially("14/03/2027", { delay: 20 });

    const submitted = await page.evaluate(() => ({
      startDate: document.querySelector<HTMLInputElement>("input[name='startDate']")?.value ?? null,
      date: document.querySelector<HTMLInputElement>("input[name='date']")?.value ?? null
    }));
    console.log("[typing] ", JSON.stringify(submitted));
    expect(submitted.startDate).toBe("2027-03-14T00:00:00.000Z");
    expect(submitted.date).toBe(submitted.startDate);

    await expect(panel(page).getByText("March 2027")).toBeVisible();

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

    await day("2026-07-06").click();
    await expect(startInput(page)).toHaveValue("06/07/2026");
    await expect(endInput(page)).toHaveValue("27/07/2026");

    await day("2026-07-16").click();
    await expect(startInput(page)).toHaveValue("06/07/2026");
    await expect(endInput(page)).toHaveValue("16/07/2026");

    await day("2026-07-21").click();
    await expect(endInput(page)).toHaveValue("21/07/2026");

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

      expect(paint.start.cellBackground).toBe(band);
      expect(paint.middle.cellBackground).toBe(band);
      expect(paint.end.cellBackground).toBe(band);
      expect(paint.outsideBand.cellBackground).toBe("rgba(0, 0, 0, 0)");

      expect(paint.start.buttonBackground).toBe("oklch(0.47 0.198 305)");
      expect(paint.end.buttonBackground).toBe("oklch(0.47 0.198 305)");
      expect(paint.middle.buttonBackground).toBe("rgba(0, 0, 0, 0)");

      expect(paint.todayColor).toBe(todayText);
      expect(paint.todayShadow).toContain(todayRing);
    }
  });

  test("inside a dialog it portals into the dialog panel, not over it", async ({ page }) => {
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

    expect(parent.isDialogPanel).toBe(true);
    expect(geometry.strategy).toBe("absolute");
    expect(geometry.position).toBe("absolute");
    expect(geometry.parentIsBody).toBe(false);

    expect(Math.abs(geometry.left - trigger.x)).toBeLessThanOrEqual(1);
    expect(geometry.top).toBeGreaterThanOrEqual(trigger.y + trigger.height);
    expect(geometry.top - (trigger.y + trigger.height)).toBeLessThanOrEqual(10);
  });

  test("Escape is swallowed before a dialog's document-level handler can see it", async ({ page }) => {
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

test.describe("Reduced motion", () => {
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
    test.slow();
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

    const geometry = (await readPanel(page))!;
    const trigger = await box(startInput(page));
    expect(geometry.position).toBe("fixed");
    expect(Math.abs(geometry.left - trigger.x)).toBeLessThanOrEqual(1);
  });

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
