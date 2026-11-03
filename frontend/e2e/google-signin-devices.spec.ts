import { writeFileSync } from "node:fs";

import { expect, test, type Page, type TestInfo } from "@playwright/test";

const GOOGLE_FRAME = 'iframe[src*="accounts.google.com/gsi/button"]';
const AGREE_BOX = "#usage-consent-agree";
const HEADERS_OF_INTEREST = [
  "cross-origin-opener-policy",
  "cross-origin-embedder-policy",
  "content-security-policy",
  "x-frame-options",
  "permissions-policy",
  "referrer-policy"
];

function visibleGoogleButton(page: Page) {
  return page
    .locator("[data-google-button]")
    .or(page.getByText("Continue with Google", { exact: true }).locator("xpath=ancestor::div[contains(@class,'relative')][1]"))
    .first();
}

async function evidence(info: TestInfo, name: string, body: string, contentType: string) {
  writeFileSync(info.outputPath(name), body);
  await info.attach(name, { body, contentType });
}

test("tapping Continue with Google opens Google's sign-in window", async ({ page, context, hasTouch }, info) => {
  await page.route("**/_next/static/chunks/**", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 2_500));
    await route.continue();
  });

  const consoleLines: string[] = [];
  page.on("console", (message) => consoleLines.push(`[${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => consoleLines.push(`[pageerror] ${error.message}`));
  page.on("requestfailed", (request) => {
    if (/google/.test(request.url())) consoleLines.push(`[requestfailed] ${request.url()} ${request.failure()?.errorText ?? ""}`);
  });

  const response = await page.goto("/login", { waitUntil: "domcontentloaded" });
  const all = response?.headers() ?? {};
  const headers = Object.fromEntries(HEADERS_OF_INTEREST.map((name) => [name, all[name] ?? null]));
  await evidence(info, "login-headers.json", JSON.stringify({ url: response?.url(), status: response?.status(), headers }, null, 2), "application/json");

  const agree = page.locator(AGREE_BOX);
  if ((await agree.count()) > 0) {
    const row = page.locator("[data-google-button]");
    if ((await row.count()) > 0) await expect(row).toHaveAttribute("aria-disabled", "true");
    await agree.check();
    await expect(agree).toBeChecked();
  }

  await expect(page.locator(GOOGLE_FRAME), "Google's button frame never rendered").toHaveCount(1, { timeout: 30_000 });
  if ((await agree.count()) > 0) {
    await page.waitForLoadState("load");
    await expect(agree, "the terms tick was undone while the page loaded").toBeChecked();
  }
  const button = visibleGoogleButton(page);
  await expect(button).toBeVisible();
  await button.scrollIntoViewIfNeeded();
  await page.waitForTimeout(1_500);

  const geometry = await button.evaluate((chrome, frameSelector) => {
    const frame = document.querySelector<HTMLIFrameElement>(frameSelector);
    const box = (element: Element | null | undefined) => {
      if (!element) return null;
      const r = element.getBoundingClientRect();
      return { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) };
    };
    const at = (x: number, y: number) => {
      const hit = document.elementFromPoint(x, y);
      if (!hit) return "nothing";
      if (hit === frame) return "GOOGLE FRAME";
      return `${hit.tagName.toLowerCase()}${hit.id ? `#${hit.id}` : ""}`;
    };
    const r = chrome.getBoundingClientRect();
    const middle = r.top + r.height / 2;
    const transparent: string[] = [];
    const transforms: string[] = [];
    for (let node: Element | null = frame; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.opacity !== "1") transparent.push(`${node.tagName.toLowerCase()} opacity ${style.opacity}`);
      if (style.transform !== "none") transforms.push(`${node.tagName.toLowerCase()} ${style.transform}`);
    }
    return {
      viewport: { width: innerWidth, height: innerHeight },
      chrome: box(chrome),
      googleFrame: box(frame),
      tapLandsOn: {
        centre: at(r.left + r.width / 2, middle),
        leftEdge: at(r.left + 8, middle),
        rightEdge: at(r.right - 8, middle),
        topEdge: at(r.left + r.width / 2, r.top + 3),
        bottomEdge: at(r.left + r.width / 2, r.bottom - 3)
      },
      frameAncestorsNotOpaque: transparent,
      frameAncestorsTransformed: transforms,
      frameInert: Boolean(frame?.closest("[inert]"))
    };
  }, GOOGLE_FRAME);
  await evidence(info, "button-geometry.json", JSON.stringify(geometry, null, 2), "application/json");
  await page.screenshot({ path: info.outputPath("before-tap.png"), fullPage: false });

  const box = await button.boundingBox();
  if (!box) throw new Error("the Google button has no box");
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  const opened = context.waitForEvent("page", { timeout: 20_000 }).catch(() => null);
  if (hasTouch) await page.touchscreen.tap(x, y);
  else await page.mouse.click(x, y);
  const popup = await opened;
  if (popup) await popup.waitForLoadState("domcontentloaded").catch(() => undefined);
  await page.screenshot({ path: info.outputPath("after-tap.png"), fullPage: false });

  await evidence(info, "console.txt", consoleLines.join("\n") || "(empty)", "text/plain");
  await evidence(
    info,
    "outcome.json",
    JSON.stringify({ input: hasTouch ? "touch tap" : "mouse click", popupUrl: popup ? popup.url() : null }, null, 2),
    "application/json"
  );

  expect(popup, `no window opened (tap at centre landed on: ${geometry.tapLandsOn.centre})`).not.toBeNull();
  expect(new URL(popup!.url()).hostname).toBe("accounts.google.com");
  await popup!.close();
});
