import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type Locator, type Page } from "@playwright/test";

import { DataExplorerFake, SORTED_FOLDER } from "./support/dataExplorerFake";

const SHOTS = join(process.env.E2E_SHOT_DIR ?? join("test-results", "data-explorer"), "ticks");
mkdirSync(SHOTS, { recursive: true });

type Size = { name: string; width: number; height: number; mobile: boolean };

const SIZES: Size[] = [
  { name: "1440", width: 1440, height: 900, mobile: false },
  { name: "390", width: 390, height: 844, mobile: true }
];

const FONT_TICK = /[\u2713\u2714\u2611\u2705]/;

async function open(browser: Browser, size: Size, path: string) {
  const context = await browser.newContext({
    viewport: { width: size.width, height: size.height },
    isMobile: size.mobile,
    hasTouch: size.mobile,
    colorScheme: "light",
    serviceWorkers: "block",
    acceptDownloads: true
  });
  const fake = new DataExplorerFake();
  await fake.install(context, "light");
  const page = await context.newPage();
  await page.goto(`/data?${new URLSearchParams({ path }).toString()}`);
  await expect(page.getByTestId("download-folder")).toBeVisible();
  return { context, page, fake };
}

async function straight(scope: Locator) {
  await expect(scope.locator("svg.lucide-check:visible").first()).toBeVisible();
  expect(await scope.innerText()).not.toMatch(FONT_TICK);
}

async function shoot(page: Page, scope: Locator, name: string, size: Size) {
  await straight(scope);
  expect(await page.locator("body").innerText()).not.toMatch(FONT_TICK);
  await scope.screenshot({ path: join(SHOTS, `${name}-${size.name}.png`) });
  await page.screenshot({ path: join(SHOTS, `${name}-${size.name}-page.png`) });
}

async function rowAction(page: Page, name: string, action: string): Promise<Locator> {
  const row = page.locator("[data-file-row]").filter({ hasText: name });
  await expect(row).toBeVisible();
  if ((page.viewportSize()?.width ?? 1280) < 640) await row.getByRole("button", { name: `More for ${name}` }).click();
  const button = row.getByRole("button", { name: action });
  await button.evaluate((node) => node.scrollIntoView({ block: "center" }));
  return button;
}

for (const size of SIZES) {
  test.describe(`straight ticks · ${size.name}`, () => {
    test(`the sort panel ticks the chosen order · ${size.name}`, async ({ browser }) => {
      const { context, page } = await open(browser, size, SORTED_FOLDER);
      try {
        await page.getByRole("button", { name: /^Sort folder: / }).click();
        const panel = page.getByRole("dialog", { name: "Sort folder" });
        await expect(panel.getByRole("radio", { name: "Name" })).toHaveAttribute("aria-checked", "true");
        await shoot(page, panel, "sort", size);
      } finally {
        await context.close();
      }
    });

    test(`a ready transcript and a saved download tick straight · ${size.name}`, async ({ browser }) => {
      const { context, page } = await open(browser, size, "workshops/bagru");
      try {
        await page.getByRole("tab", { name: /Transcripts/ }).click();
        const tab = page.getByTestId("transcripts-tab");
        const ready = tab.locator("[data-transcript-row]").filter({ hasText: "interview" });
        await expect(ready).toHaveAttribute("data-state", "ready");
        await shoot(page, ready, "transcript-ready", size);
        await tab.getByRole("button", { name: /^Transcripts \(\.zip\)/ }).click();
        const item = page.getByTestId("downloads-tray").getByTestId("tray-item").filter({ hasText: "Transcripts · Bagru 2026" });
        await expect(item).toHaveAttribute("data-status", "saved");
        await expect(item.getByTestId("tray-tick")).toBeVisible();
        await shoot(page, item, "tray-saved", size);
      } finally {
        await context.close();
      }
    });

    test(`a copied saved-view link ticks straight · ${size.name}`, async ({ browser }) => {
      const { context, page } = await open(browser, size, "workshops/bagru");
      try {
        await context.grantPermissions(["clipboard-read", "clipboard-write"]);
        await page.getByRole("button", { name: "Saved views" }).click();
        const dialog = page.getByRole("dialog", { name: "Saved views" });
        await dialog.getByRole("button", { name: "Save current view" }).click();
        const status = dialog.getByRole("status").filter({ hasText: "Link copied" });
        await expect(status).toBeVisible();
        await shoot(page, status, "link-copied", size);
      } finally {
        await context.close();
      }
    });

    test(`the preview ticks follow and clear voices straight · ${size.name}`, async ({ browser }) => {
      test.skip(size.name === "390", "the preview switches are captured at 1440");
      const { context, page } = await open(browser, size, "workshops/bagru");
      try {
        await (await rowAction(page, "interview.m4a", "Play")).click();
        const dialog = page.getByRole("dialog", { name: "interview.m4a" });
        await expect(dialog.getByTestId("preview-lines")).toContainText("Chuna aur gond");
        const follow = dialog.getByRole("switch", { name: /Text follows the audio|Follow/ }).first();
        await expect(follow).toHaveAttribute("aria-checked", "true");
        await shoot(page, follow, "preview-follow", size);
        await dialog.getByRole("button", { name: "Make a clear-voices copy" }).click();
        const clear = dialog.getByRole("switch", { name: /Clear/ }).first();
        await expect(clear).toHaveAttribute("aria-checked", "true", { timeout: 25_000 });
        await shoot(page, clear, "preview-clear", size);
      } finally {
        await context.close();
      }
    });
  });
}
