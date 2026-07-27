import { expect, test, devices, type Page } from "@playwright/test";

/**
 * The navigation sheet scrolls itself, and the page behind it does not move.
 *
 * Reported from a phone: the menu holds a dozen-odd destinations, it is taller than the screen, and
 * dragging it scrolled the DOCUMENT underneath instead of the list — so the last few entries were
 * unreachable and the reader lost their place in whatever record they had open.
 *
 * The assertions therefore come in pairs. That the panel scrolled is not enough on its own (the page
 * can move too), and that the page held still is not enough either (a panel that cannot scroll also
 * holds still). Both numbers are read before and after every gesture.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  // Anchored: the page also carries "Sign in to your account" and, on a narrow viewport, a second
  // control whose name contains the same words.
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

const hamburger = (page: Page) => page.getByRole("button", { name: "Toggle navigation menu" });
const sheet = (page: Page) => page.getByRole("dialog", { name: "Navigation" });

/**
 * A bounding box read only once the element has genuinely stopped moving.
 *
 * The island is a framer-motion `layout` component that springs between a wide and a compact shape
 * on every scroll, and its projection animation neither starts immediately nor moves monotonically.
 * Two matching samples are therefore not proof of rest — an earlier version of this spec took two
 * such samples during the run-up to the spring and accused the scroll lock of a 276px drift that
 * turned out to be the island still on its way to a position it had already committed to. Hence a
 * floor on the settling time and three consecutive agreeing samples.
 */
async function settledBox(page: Page, locator: ReturnType<Page["getByRole"]>) {
  await page.waitForTimeout(700);
  let agreements = 0;
  let previous = await locator.boundingBox();
  for (let i = 0; i < 40; i += 1) {
    await page.waitForTimeout(100);
    const next = await locator.boundingBox();
    const same =
      previous && next && Math.abs(next.x - previous.x) < 0.5 && Math.abs(next.width - previous.width) < 0.5;
    agreements = same ? agreements + 1 : 0;
    previous = next;
    if (agreements >= 3 && next) return next;
  }
  throw new Error("the island never stopped animating");
}

/**
 * A real finger drag, not a wheel tick. Only the touch path exercises `touch-action` and the
 * overscroll chaining rules this fix turns on, so the wheel alone would pass over the reported bug.
 *
 * Hand-dispatched touch points rather than `Input.synthesizeScrollGesture`: the synthesized fling is
 * silently a no-op in this headless harness — it delivers the touchstart and then scrolls nothing —
 * which would have made every assertion below pass for the wrong reason.
 */
async function touchScroll(page: Page, x: number, yFrom: number, yTo: number) {
  const step = 30;
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y: yFrom }] });
  for (let y = yFrom - step; y >= yTo; y -= step) {
    await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x, y }] });
  }
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
  await cdp.detach();
}

/** Everything the assertions need, read in one round trip so the numbers are from one moment. */
async function readState(page: Page) {
  return page.evaluate(() => {
    const panel = document.querySelector<HTMLElement>('[role="dialog"][aria-label="Navigation"]');
    return {
      pageY: window.scrollY,
      panelTop: panel ? panel.scrollTop : null,
      panelScrollHeight: panel ? panel.scrollHeight : null,
      panelClientHeight: panel ? panel.clientHeight : null,
      overflowY: panel ? getComputedStyle(panel).overflowY : null,
      overscroll: panel ? getComputedStyle(panel).overscrollBehaviorY : null,
      maxHeight: panel ? getComputedStyle(panel).maxHeight : null,
      paddingBottom: panel ? getComputedStyle(panel).paddingBottom : null,
      viewportHeight: window.innerHeight,
      locked: document.documentElement.classList.contains("nav-scroll-locked"),
      scrollbarGutter: window.innerWidth - document.documentElement.clientWidth
    };
  });
}

/**
 * The descriptor supplies the viewport, touch, mobile flag and device-pixel-ratio. Its browser
 * preference is dropped: Playwright refuses to switch engines inside a describe block, and chromium
 * is the only one installed on this machine — so the iPhone run is an iPhone-shaped chromium.
 */
function viewportOf(name: keyof typeof devices) {
  const { defaultBrowserType: _engine, ...rest } = devices[name];
  return rest;
}

for (const deviceName of ["Pixel 7", "iPhone 13"] as const) {
  test.describe(`Navigation sheet on ${deviceName}`, () => {
    test.use(viewportOf(deviceName));

    test.beforeEach(async ({ page }) => {
      await signIn(page);
      await page.goto("/dashboard");
      await expect(hamburger(page)).toBeVisible();
    });

    test("the panel scrolls and the page behind it does not", async ({ page }) => {
      test.slow(); // a sign-in, four gestures and a settling animation do not fit the default budget

      // Park the document part-way down: a lock that silently resets the page to the top only shows
      // up when there was somewhere to be returned to.
      await page.evaluate(() => window.scrollTo(0, 240));
      await page.waitForFunction(() => window.scrollY > 0);
      const beforeOpen = await readState(page);
      console.log(`[${deviceName}] before open`, JSON.stringify(beforeOpen));
      const parked = beforeOpen.pageY;
      expect(parked, "the dashboard must be taller than the phone screen for this test to mean anything").toBeGreaterThan(0);

      await hamburger(page).click();
      await expect(sheet(page)).toBeVisible();
      await page.waitForTimeout(400); // let the open spring settle before measuring

      const opened = await readState(page);
      console.log(`[${deviceName}] opened`, JSON.stringify(opened));

      expect(opened.locked).toBe(true);
      expect(opened.overflowY).toBe("auto");
      expect(opened.overscroll).toBe("contain");
      expect(opened.panelClientHeight!).toBeLessThan(opened.viewportHeight);
      expect(
        opened.panelScrollHeight!,
        "the menu must actually overflow its panel, otherwise nothing is being proven"
      ).toBeGreaterThan(opened.panelClientHeight!);
      expect(opened.panelTop).toBe(0);
      expect(opened.pageY).toBe(parked);

      // 1. Wheel over the middle of the panel.
      const box = (await sheet(page).boundingBox())!;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.wheel(0, 400);
      await page.waitForTimeout(300);
      const afterWheel = await readState(page);
      console.log(`[${deviceName}] after wheel`, JSON.stringify(afterWheel));
      expect(afterWheel.panelTop!).toBeGreaterThan(0);
      expect(afterWheel.pageY).toBe(parked);

      // 2. A finger drag inside the panel, far enough to bottom it out — the moment scroll chaining
      //    would previously have handed the rest of the gesture to the document. Rewound first,
      //    because the wheel above has already reached the end of a list this short.
      await page.evaluate(() => {
        document.querySelector<HTMLElement>('[role="dialog"][aria-label="Navigation"]')!.scrollTop = 0;
      });
      await page.waitForTimeout(150);
      expect((await readState(page)).panelTop).toBe(0);

      // One long stroke, from near the bottom of the panel to near its top. It travels far enough to
      // bottom the list out and then keeps going — that overrun is the exact instant the gesture used
      // to be handed to the document behind.
      const midX = box.x + box.width / 2;
      const strokeFrom = box.y + box.height - 40;
      const strokeTo = box.y + 40;
      await touchScroll(page, midX, strokeFrom, strokeTo);
      await touchScroll(page, midX, strokeFrom, strokeTo);
      await page.waitForTimeout(400);
      const afterDrag = await readState(page);
      console.log(`[${deviceName}] after touch drag`, JSON.stringify(afterDrag));
      const maxTop = afterDrag.panelScrollHeight! - afterDrag.panelClientHeight!;
      expect(afterDrag.panelTop!, "a finger drag must move the panel").toBeGreaterThan(0);
      expect(afterDrag.panelTop!, "the drag should reach the end of the list").toBeGreaterThanOrEqual(maxTop - 2);
      expect(afterDrag.pageY, "the page behind must not have moved a pixel").toBe(parked);

      // 3. And a drag on the dimmed backdrop, which is the other way the page used to get away.
      const scrimY = box.y + box.height + 20;
      await touchScroll(page, midX, Math.min(scrimY + 200, opened.viewportHeight - 10), scrimY);
      await page.waitForTimeout(300);
      expect((await readState(page)).pageY).toBe(parked);

      // 4. Closing restores the reader to exactly where they were.
      await page.keyboard.press("Escape");
      await expect(sheet(page)).toBeHidden();
      await page.waitForTimeout(300);
      const closed = await readState(page);
      console.log(`[${deviceName}] closed`, JSON.stringify(closed));
      expect(closed.locked).toBe(false);
      expect(closed.pageY).toBe(parked);
    });

    test("the last item clears the home indicator and the list survives landscape", async ({ page }) => {
      await hamburger(page).click();
      await expect(sheet(page)).toBeVisible();
      const portrait = await readState(page);
      // env(safe-area-inset-bottom) resolves to 0 in a headless browser with no notch, so the floor
      // is the sheet's own 24px; what matters is that the padding is declared and non-zero.
      expect(parseFloat(portrait.paddingBottom!)).toBeGreaterThanOrEqual(24);

      const size = page.viewportSize()!;
      await page.setViewportSize({ width: size.height, height: size.width });
      await page.waitForTimeout(300);
      const landscape = await readState(page);
      console.log(`[${deviceName}] landscape`, JSON.stringify(landscape));

      // Still bounded, still scrollable, still inside the short viewport.
      expect(landscape.panelClientHeight!).toBeLessThan(landscape.viewportHeight);
      expect(landscape.overflowY).toBe("auto");
      const bottom = await sheet(page).evaluate((el) => el.getBoundingClientRect().bottom);
      expect(bottom).toBeLessThanOrEqual(landscape.viewportHeight);

      // The final destination in the list is reachable by scrolling to it.
      const last = sheet(page).getByRole("button", { name: /logout/i });
      await last.scrollIntoViewIfNeeded();
      await expect(last).toBeInViewport();
    });

    test("Escape closes the sheet and hands focus back to the hamburger", async ({ page }) => {
      await hamburger(page).click();
      await expect(sheet(page)).toBeVisible();

      // Focus lands inside the sheet on open.
      const startedInside = await page.evaluate(() =>
        document.querySelector('[role="dialog"][aria-label="Navigation"]')!.contains(document.activeElement)
      );
      expect(startedInside).toBe(true);

      // Tab cycles within the sheet instead of walking out onto the page behind it. The watcher runs
      // in the page rather than a round trip per keystroke, which is the difference between this
      // test taking four seconds and timing out.
      await page.evaluate(() => {
        const win = window as unknown as { __escapes: number };
        win.__escapes = 0;
        document.addEventListener(
          "focusin",
          () => {
            const panel = document.querySelector('[role="dialog"][aria-label="Navigation"]');
            if (!panel || !panel.contains(document.activeElement)) win.__escapes += 1;
          },
          true
        );
      });

      for (let i = 0; i < 30; i += 1) await page.keyboard.press("Tab");

      const escapes = await page.evaluate(() => (window as unknown as { __escapes: number }).__escapes);
      expect(escapes, "focus left the sheet while tabbing").toBe(0);
      const stillInside = await page.evaluate(() =>
        document.querySelector('[role="dialog"][aria-label="Navigation"]')!.contains(document.activeElement)
      );
      expect(stillInside).toBe(true);

      await page.keyboard.press("Escape");
      await expect(sheet(page)).toBeHidden();
      await expect(hamburger(page)).toBeFocused();
    });
  });
}

/**
 * Desktop is where the second regression lives: locking the document takes the scrollbar with it,
 * and an uncompensated lock shunts every centred thing on the page sideways as the menu opens.
 */
test.describe("Navigation sheet on the desktop", () => {
  test.use(viewportOf("Desktop Chrome"));

  test("opening the sheet moves nothing sideways", async ({ page }) => {
    await signIn(page);
    await page.goto("/dashboard");
    await expect(hamburger(page)).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 300));
    await page.waitForTimeout(200);

    const before = await readState(page);
    const islandBefore = (await settledBox(page, hamburger(page)))!;
    const headingBefore = (await page.getByRole("heading", { level: 1 }).first().boundingBox())!;
    console.log("[desktop] before", JSON.stringify({ ...before, islandX: islandBefore.x, headingX: headingBefore.x }));

    await hamburger(page).click();
    await expect(sheet(page)).toBeVisible();

    const during = await readState(page);
    const islandDuring = (await settledBox(page, hamburger(page)))!;
    const headingDuring = (await page.getByRole("heading", { level: 1 }).first().boundingBox())!;
    console.log("[desktop] during", JSON.stringify({ ...during, islandX: islandDuring.x, headingX: headingDuring.x }));

    expect(during.locked).toBe(true);

    // The width handed back must be exactly the width the scrollbar was occupying a moment earlier.
    // Asserted separately because headless Chromium is launched with --hide-scrollbars, where that
    // width is legitimately 0 and the geometry checks below would pass without compensating anything.
    const gutter = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue("--nav-scroll-gutter").trim()
    );
    console.log(`[desktop] scrollbar was ${before.scrollbarGutter}px, sheet reserved ${gutter}`);
    expect(gutter).toBe(`${before.scrollbarGutter}px`);

    expect(Math.abs(islandDuring.x - islandBefore.x), "the floating island must not drift").toBeLessThanOrEqual(0.5);
    expect(Math.abs(headingDuring.x - headingBefore.x), "page content must not drift").toBeLessThanOrEqual(0.5);
    expect(during.pageY).toBe(before.pageY);

    await page.keyboard.press("Escape");
    await expect(sheet(page)).toBeHidden();
    const after = await readState(page);
    const islandAfter = (await settledBox(page, hamburger(page)))!;
    console.log("[desktop] after", JSON.stringify({ ...after, islandX: islandAfter.x }));
    expect(after.pageY).toBe(before.pageY);
    expect(Math.abs(islandAfter.x - islandBefore.x)).toBeLessThanOrEqual(0.5);
  });
});
