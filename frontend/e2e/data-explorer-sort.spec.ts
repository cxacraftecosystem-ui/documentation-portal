import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type Locator, type Page } from "@playwright/test";

import { DataExplorerFake, fakeSorted, LONG_NAME, LONG_PARENT, SORTED_FILES, SORTED_FOLDER } from "./support/dataExplorerFake";

const SHOTS = process.env.E2E_SHOT_DIR ?? join("test-results", "data-explorer");
mkdirSync(SHOTS, { recursive: true });

type Size = { name: string; width: number; height: number; mobile: boolean };

const SIZES: Size[] = [
  { name: "desktop", width: 1440, height: 900, mobile: false },
  { name: "tablet", width: 834, height: 1194, mobile: true },
  { name: "phone", width: 390, height: 844, mobile: true }
];

async function openWith(browser: Browser, size: Size, query: Record<string, string>, ready: (fake: DataExplorerFake) => void = () => undefined) {
  const context = await browser.newContext({
    viewport: { width: size.width, height: size.height },
    isMobile: size.mobile,
    hasTouch: size.mobile,
    colorScheme: "light",
    serviceWorkers: "block"
  });
  const fake = new DataExplorerFake();
  ready(fake);
  await fake.install(context, "light");
  const page = await context.newPage();
  await page.goto(`/data?${new URLSearchParams(query).toString()}`);
  await expect(page.getByTestId("download-folder")).toBeVisible();
  return { context, page, fake };
}

function fileNames(page: Page): Locator {
  return page.getByRole("list", { name: "Files" }).locator("[data-file-row]").getByTestId("row-preview-name");
}

function expected(sort: string): string[] {
  return fakeSorted(
    SORTED_FILES.map((file) => ({ ...file, kind: "file", path: `${SORTED_FOLDER}/${file.name}`, sizeBytes: file.bytes })),
    sort
  ).map((file) => file.name);
}

function treeCalls(fake: DataExplorerFake) {
  return fake.calls.filter((call) => call.path === "/data/tree" && call.query.get("path") === SORTED_FOLDER);
}

async function withinViewport(page: Page, target: Locator) {
  const box = await target.boundingBox();
  const width = page.viewportSize()?.width ?? 0;
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(width + 0.5);
}

async function noSidewaysScroll(page: Page) {
  const found = await page.evaluate(() => {
    const limit = document.documentElement.clientWidth;
    const offenders = [...document.querySelectorAll<HTMLElement>("body *")]
      .filter((node) => node.getBoundingClientRect().right + window.scrollX > limit + 1 && getComputedStyle(node).position !== "fixed")
      .slice(-5)
      .map((node) => `${node.tagName.toLowerCase()}.${String(node.className).slice(0, 80)} → ${Math.round(node.getBoundingClientRect().right + window.scrollX)}`);
    return { wide: document.documentElement.scrollWidth - limit, offenders };
  });
  expect(found.wide, found.offenders.join(" | ")).toBeLessThanOrEqual(1);
}

for (const size of SIZES) {
  test.describe(`@qf:X1 sorting · ${size.name}`, () => {
    test(`a folder sorts on the server, keeps the order past the page boundary and remembers it · ${size.width}`, async ({ browser }) => {
      const { context, page, fake } = await openWith(browser, size, { path: SORTED_FOLDER });
      try {
        await expect(fileNames(page)).toHaveCount(SORTED_FILES.length);
        await expect(fileNames(page)).toHaveText(expected("name:asc"));
        const button = page.getByRole("button", { name: /^Sort folder: Name · A–Z$/ });
        await expect(button).toBeVisible();
        await button.click();
        const panel = page.getByRole("dialog", { name: "Sort folder" });
        await expect(panel).toBeVisible();
        await withinViewport(page, panel);
        await expect(panel.getByRole("radio", { name: "Name" })).toHaveAttribute("aria-checked", "true");
        await expect(panel.getByRole("radio", { name: "Duration" })).toHaveCount(0);
        await page.screenshot({ path: join(SHOTS, `sort-open-${size.name}.png`) });
        await panel.getByRole("radio", { name: "Size" }).click();
        await expect(panel.getByRole("radio", { name: "Largest" })).toHaveAttribute("aria-checked", "true");
        await expect(fileNames(page)).toHaveText(expected("size:desc"));
        const sized = treeCalls(fake).filter((call) => call.query.get("sort") === "size:desc");
        expect(sized.map((call) => call.query.get("cursor") ?? "")).toEqual(expect.arrayContaining(["", "2", "4"]));
        await panel.getByRole("radio", { name: "Smallest" }).click();
        await expect(fileNames(page)).toHaveText(expected("size:asc"));
        await page.keyboard.press("Escape");
        await expect(panel).toHaveCount(0);
        await expect(page.getByRole("button", { name: "Sort folder: Size · Smallest" })).toBeVisible();
        await noSidewaysScroll(page);
        await page.screenshot({ path: join(SHOTS, `sort-closed-${size.name}.png`) });

        const before = treeCalls(fake).length;
        await page.reload();
        await expect(fileNames(page)).toHaveCount(SORTED_FILES.length);
        await expect(fileNames(page)).toHaveText(expected("size:asc"));
        const first = treeCalls(fake).slice(before).find((call) => !call.query.get("cursor"));
        expect(first?.query.get("sort")).toBe("size:asc");

        await page.getByRole("button", { name: "Sort folder: Size · Smallest" }).click();
        await page.getByRole("dialog", { name: "Sort folder" }).getByRole("radio", { name: "Date recorded" }).click();
        await expect(fileNames(page)).toHaveText(expected("recorded:desc"));
        await page.getByRole("dialog", { name: "Sort folder" }).getByRole("radio", { name: "Name" }).click();
        await expect(fileNames(page)).toHaveText(expected("name:asc"));
        const last = treeCalls(fake).at(-1);
        expect(last?.query.get("sort")).toBeNull();
      } finally {
        await context.close();
      }
    });

    test(`a 200-character folder name truncates and never makes the page scroll sideways · ${size.width}`, async ({ browser }) => {
      const { context, page } = await openWith(browser, size, { path: LONG_PARENT });
      try {
        const folders = page.getByRole("list", { name: "Folders" });
        const long = folders.getByTitle(LONG_NAME, { exact: true });
        await expect(long).toBeVisible();
        expect(LONG_NAME).toHaveLength(200);
        const box = await long.boundingBox();
        expect(box!.x + box!.width).toBeLessThanOrEqual(size.width);
        await noSidewaysScroll(page);
        const sorted = page.getByRole("button", { name: /^Sort folder:/ });
        if (await sorted.isVisible().catch(() => false)) await sorted.click();
        await noSidewaysScroll(page);
      } finally {
        await context.close();
      }
    });

    test(`the Transcripts tab asks the server for the chosen order · ${size.width}`, async ({ browser }) => {
      const { context, page, fake } = await openWith(browser, size, { path: "workshops/bagru/kamla", view: "transcripts" }, (made) => {
        made.transcriptSorting = true;
      });
      try {
        const tab = page.getByTestId("transcripts-tab");
        const rows = tab.locator("[data-transcript-row]");
        await expect(rows).toHaveCount(2);
        await noSidewaysScroll(page);
        await tab.getByRole("button", { name: /^Sort transcripts: Folder · A–Z$/ }).click();
        const panel = page.getByRole("dialog", { name: "Sort transcripts" });
        await withinViewport(page, panel);
        await panel.getByRole("radio", { name: "Name" }).click();
        await panel.getByRole("radio", { name: "Z–A" }).click();
        await expect(rows.first()).toContainText("loom");
        await expect(rows.last()).toContainText("interview");
        const asked = fake.calls.filter((call) => call.path === "/data/transcripts/list").at(-1);
        expect(asked?.query.get("sort")).toBe("name:desc");
        await page.keyboard.press("Escape");
        await expect(panel).toHaveCount(0);
        await noSidewaysScroll(page);
      } finally {
        await context.close();
      }
    });

    test(`browse by type sorts every page on the server · ${size.width}`, async ({ browser }) => {
      const { context, page, fake } = await openWith(browser, size, { path: SORTED_FOLDER, browse: "artisan" });
      try {
        const section = page.getByRole("region", { name: "Records by type" });
        await section.scrollIntoViewIfNeeded();
        const names = section.locator("tbody tr td:first-child a, tbody tr td:first-child > span:first-child");
        await expect(names.first()).toHaveText("Artisan 1");
        await section.getByRole("button", { name: /^Sort artisans:/ }).click();
        const panel = page.getByRole("dialog", { name: "Sort artisans" });
        await withinViewport(page, panel);
        await panel.getByRole("radio", { name: "Name" }).click();
        await panel.getByRole("radio", { name: "Z–A" }).click();
        await page.keyboard.press("Escape");
        const all = Array.from({ length: 120 }, (_, index) => `Artisan ${index + 1}`).sort((a, b) => (a < b ? 1 : a > b ? -1 : 0));
        await expect(names.first()).toHaveText(all[0]);
        await section.getByRole("button", { name: "Next" }).click();
        await expect(names.first()).toHaveText(all[50]);
        const asked = fake.calls.filter((call) => call.path === "/data/browse").at(-1);
        expect(asked?.query.get("sort")).toBe("name:desc");
        expect(asked?.query.get("page")).toBe("2");
        await noSidewaysScroll(page);
      } finally {
        await context.close();
      }
    });
  });
}
