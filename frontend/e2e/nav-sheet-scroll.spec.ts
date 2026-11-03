import { expect, test, devices, type Page } from "@playwright/test";

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

const hamburger = (page: Page) => page.getByRole("button", { name: /^(Toggle navigation menu|Open menu)$/ });
const sheet = (page: Page) => page.getByRole("dialog", { name: "Navigation" });

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
      test.slow();

      await page.evaluate(() => window.scrollTo(0, 240));
      await page.waitForFunction(() => window.scrollY > 0);
      const beforeOpen = await readState(page);
      console.log(`[${deviceName}] before open`, JSON.stringify(beforeOpen));
      const parked = beforeOpen.pageY;
      expect(parked, "the dashboard must be taller than the phone screen for this test to mean anything").toBeGreaterThan(0);

      await hamburger(page).click();
      await expect(sheet(page)).toBeVisible();
      await page.waitForTimeout(400);

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

      const box = (await sheet(page).boundingBox())!;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.wheel(0, 400);
      await page.waitForTimeout(300);
      const afterWheel = await readState(page);
      console.log(`[${deviceName}] after wheel`, JSON.stringify(afterWheel));
      expect(afterWheel.panelTop!).toBeGreaterThan(0);
      expect(afterWheel.pageY).toBe(parked);

      await page.evaluate(() => {
        document.querySelector<HTMLElement>('[role="dialog"][aria-label="Navigation"]')!.scrollTop = 0;
      });
      await page.waitForTimeout(150);
      expect((await readState(page)).panelTop).toBe(0);

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

      const scrimY = box.y + box.height + 20;
      await touchScroll(page, midX, Math.min(scrimY + 200, opened.viewportHeight - 10), scrimY);
      await page.waitForTimeout(300);
      expect((await readState(page)).pageY).toBe(parked);

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
      expect(parseFloat(portrait.paddingBottom!)).toBeGreaterThanOrEqual(24);

      const size = page.viewportSize()!;
      await page.setViewportSize({ width: size.height, height: size.width });
      await page.waitForTimeout(300);
      const landscape = await readState(page);
      console.log(`[${deviceName}] landscape`, JSON.stringify(landscape));

      expect(landscape.panelClientHeight!).toBeLessThan(landscape.viewportHeight);
      expect(landscape.overflowY).toBe("auto");
      const bottom = await sheet(page).evaluate((el) => el.getBoundingClientRect().bottom);
      expect(bottom).toBeLessThanOrEqual(landscape.viewportHeight);

      const last = sheet(page).getByRole("button", { name: /logout/i });
      await last.scrollIntoViewIfNeeded();
      await expect(last).toBeInViewport();
    });

    test("Escape closes the sheet and hands focus back to the hamburger", async ({ page }) => {
      await hamburger(page).click();
      await expect(sheet(page)).toBeVisible();

      const startedInside = await page.evaluate(() =>
        document.querySelector('[role="dialog"][aria-label="Navigation"]')!.contains(document.activeElement)
      );
      expect(startedInside).toBe(true);

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
