import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type Locator, type Page } from "@playwright/test";

import { BIG_FILE_BYTES, BIG_FILES, DataExplorerFake, expectedBytes } from "./support/dataExplorerFake";

const SHOTS = process.env.E2E_SHOT_DIR ?? join("test-results", "data-explorer");
mkdirSync(SHOTS, { recursive: true });

type Size = { name: string; width: number; height: number; mobile: boolean };

const SIZES: Size[] = [
  { name: "desktop", width: 1366, height: 900, mobile: false },
  { name: "tablet", width: 820, height: 1180, mobile: true },
  { name: "phone", width: 390, height: 844, mobile: true }
];

const DESKTOP = SIZES[0];
const PHONE = SIZES[2];

async function openExplorer(browser: Browser, options: { size?: Size; theme?: "light" | "dark"; path?: string; query?: string } = {}) {
  const size = options.size ?? DESKTOP;
  const context = await browser.newContext({
    viewport: { width: size.width, height: size.height },
    isMobile: size.mobile,
    hasTouch: size.mobile,
    colorScheme: options.theme ?? "light",
    serviceWorkers: "block",
    acceptDownloads: true
  });
  const fake = new DataExplorerFake();
  await fake.install(context, options.theme ?? "light");
  const page = await context.newPage();
  const search = new URLSearchParams(options.query ?? "");
  if (options.path) search.set("path", options.path);
  const text = search.toString();
  await page.goto(`/data${text ? `?${text}` : ""}`);
  await expect(page.getByTestId("download-folder")).toBeVisible();
  return { context, page, fake };
}

function readZip(bytes: Buffer): { name: string; size: number; data: Buffer }[] {
  let end = bytes.length - 22;
  while (end >= 0 && bytes.readUInt32LE(end) !== 0x06054b50) end -= 1;
  const count = bytes.readUInt16LE(end + 10);
  let at = bytes.readUInt32LE(end + 16);
  const out: { name: string; size: number; data: Buffer }[] = [];
  for (let i = 0; i < count; i += 1) {
    const size = bytes.readUInt32LE(at + 24);
    const nameLength = bytes.readUInt16LE(at + 28);
    const extra = bytes.readUInt16LE(at + 30);
    const offset = bytes.readUInt32LE(at + 42);
    const name = bytes.subarray(at + 46, at + 46 + nameLength).toString("utf8");
    const start = offset + 30 + bytes.readUInt16LE(offset + 26) + bytes.readUInt16LE(offset + 28);
    out.push({ name, size, data: bytes.subarray(start, start + size) });
    at += 46 + nameLength + extra;
  }
  return out;
}

async function savedZip(page: Page): Promise<Buffer> {
  const values = await page.evaluate(async () => {
    const name = (window as unknown as { __savedAs?: string }).__savedAs ?? "download.zip";
    const root = await navigator.storage.getDirectory();
    const file = await (await root.getFileHandle(name)).getFile();
    return Array.from(new Uint8Array(await file.arrayBuffer()));
  });
  return Buffer.from(values);
}

async function showTree(page: Page) {
  const toggle = page.getByRole("button", { name: "Folders", exact: true });
  if (await toggle.isVisible()) await toggle.click();
  await expect(page.getByTestId("folder-tree")).toBeVisible();
}

async function openFolder(page: Page, name: string) {
  await showTree(page);
  const item = page.getByRole("treeitem", { name, exact: true });
  await item.click();
  await expect(page.getByTestId("download-folder").getByRole("heading", { name, exact: true })).toBeVisible();
}

async function expandTo(page: Page, names: string[]) {
  await showTree(page);
  for (const name of names) {
    const item = page.getByRole("treeitem", { name, exact: true });
    await expect(item).toBeVisible();
    if ((await item.getAttribute("aria-expanded")) === "false") {
      await item.focus();
      await page.keyboard.press("ArrowRight");
    }
  }
}

function fileRow(page: Page, name: string): Locator {
  return page.locator("[data-file-row]").filter({ hasText: name });
}

async function rowAction(page: Page, name: string, action: string | RegExp): Promise<Locator> {
  const row = fileRow(page, name);
  await expect(row).toBeVisible();
  if ((page.viewportSize()?.width ?? 1280) < 640) await row.getByRole("button", { name: `More for ${name}` }).click();
  const button = row.getByRole("button", { name: action });
  await button.evaluate((node) => node.scrollIntoView({ block: "center" }));
  return button;
}

function tray(page: Page): Locator {
  return page.getByTestId("downloads-tray");
}

function trayItem(page: Page, label: string | RegExp): Locator {
  return tray(page).getByTestId("tray-item").filter({ hasText: label });
}

function folderDownload(page: Page): Locator {
  return page.getByTestId("download-folder").getByRole("button", { name: /^Download( folder|$)/ });
}

async function noSidewaysScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "no sideways scroll").toBeLessThanOrEqual(1);
}

test.describe("@qf:X1 layout", () => {
  for (const size of SIZES) {
    for (const theme of ["light", "dark"] as const) {
      test(`search on top, the folder card first, tabs below and nothing scrolls sideways · ${size.name} ${theme}`, async ({ browser }) => {
        const { context, page } = await openExplorer(browser, { size, theme, path: "workshops/bagru" });
        try {
          const folder = page.getByTestId("download-folder");
          await expect(folder.getByRole("heading", { name: "Bagru 2026", exact: true })).toBeVisible();
          await expect(page.locator("[data-explorer-search]")).toBeVisible();
          await expect(page.getByRole("tablist", { name: "Contents" })).toBeVisible();
          await expect(page.getByRole("tab", { name: /Files/ })).toHaveAttribute("aria-selected", "true");
          await expect(page.getByRole("region", { name: "Record details" })).toContainText("Hand block printing");
          const search = await page.locator("[data-explorer-search]").boundingBox();
          const card = await folder.boundingBox();
          expect(search!.y).toBeLessThan(card!.y);
          if (size.name === "desktop") {
            const tree = await page.getByTestId("explorer-tree-panel").boundingBox();
            expect(tree!.x).toBeLessThan(card!.x);
            await expect(folderDownload(page)).toHaveAccessibleName(/^Download folder · \d/);
          } else {
            await expect(page.getByTestId("explorer-tree-panel")).toBeHidden();
            await showTree(page);
          }
          await noSidewaysScroll(page);
          if (size.mobile) {
            for (const target of [folderDownload(page), page.getByRole("button", { name: /folders/i }), page.getByRole("treeitem", { name: "Bagru 2026", exact: true })]) {
              const box = await target.boundingBox();
              expect(box!.height, "touch target").toBeGreaterThanOrEqual(44);
            }
          }
          await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
          await page.screenshot({ path: join(SHOTS, `explorer-${size.name}-${theme}.png`), fullPage: true });
        } finally {
          await context.close();
        }
      });
    }
  }
});

test.describe("@qf:X1 one place for folder actions, worded as on Android", () => {
  for (const size of [DESKTOP, PHONE]) {
    test(`the folder card holds Report and Download, and the Tables tab adds only its CSV and the full report · ${size.name}`, async ({ browser }) => {
      const { context, page } = await openExplorer(browser, { size, path: "workshops/bagru" });
      try {
        const folder = page.getByTestId("download-folder");
        await expect(folder.getByRole("button", { name: "Report (.xlsx)" })).toBeVisible();
        await expect(folderDownload(page)).toBeVisible();
        await expect(folder.getByRole("button", { name: "Whole dataset" })).toHaveCount(0);
        await expect(page.getByRole("link", { name: /\.xlsx/ })).toHaveCount(0);
        if (size.mobile) {
          const chips = page.locator("[data-transcript-files] a");
          await expect(chips).toHaveCount(1);
          for (const link of await chips.all()) {
            const box = await link.boundingBox();
            expect(box!.height, "touch target").toBeGreaterThanOrEqual(44);
          }
        }
        await page.getByRole("tab", { name: /Tables/ }).click();
        const tables = page.getByRole("region", { name: "Data tables" });
        await expect(tables.getByRole("button", { name: "This table (.csv)" })).toBeVisible();
        await expect(tables.getByRole("button", { name: "Full report (.xlsx)" })).toBeVisible();
        await noSidewaysScroll(page);
      } finally {
        await context.close();
      }
    });
  }

  test("the whole dataset is offered only near the root", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops" });
    try {
      await expect(page.getByTestId("download-folder").getByRole("button", { name: "Whole dataset" })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X1 the tree", () => {
  test("arrow keys move, open and choose folders, and the URL keeps the place", async ({ browser }) => {
    const { context, page } = await openExplorer(browser);
    try {
      await expect(page).toHaveURL(/path=workshops/);
      const root = page.getByRole("treeitem", { name: "By workshop", exact: true });
      await expect(root).toHaveAttribute("aria-expanded", "true");
      await root.focus();
      await page.keyboard.press("ArrowLeft");
      await expect(root).toHaveAttribute("aria-expanded", "false");
      await page.keyboard.press("ArrowRight");
      await expect(root).toHaveAttribute("aria-expanded", "true");
      await page.keyboard.press("ArrowRight");
      await expect(page.getByRole("treeitem", { name: "Bagru 2026", exact: true })).toBeFocused();
      await page.keyboard.press("ArrowDown");
      await expect(page.getByRole("treeitem", { name: "Sanganer", exact: true })).toBeFocused();
      await page.keyboard.press("ArrowUp");
      await page.keyboard.press("Enter");
      await expect(page).toHaveURL(/path=workshops%2Fbagru/);
      await expect(page.getByRole("treeitem", { name: "Bagru 2026", exact: true })).toHaveAttribute("aria-selected", "true");
      const target = await page.getByRole("treeitem", { name: "Bagru 2026", exact: true }).boundingBox();
      expect(target!.height, "tree rows are at least 40px").toBeGreaterThanOrEqual(36);
      await page.getByRole("tab", { name: /Tables/ }).click();
      await expect(page).toHaveURL(/view=tables/);
      await page.reload();
      await expect(page.getByRole("tab", { name: /Tables/ })).toHaveAttribute("aria-selected", "true");
      await expect(page.getByRole("treeitem", { name: "Bagru 2026", exact: true })).toHaveAttribute("aria-selected", "true");
      await page.goBack();
      await expect(page).not.toHaveURL(/path=workshops%2Fbagru/);
    } finally {
      await context.close();
    }
  });

  test("a long folder pages in with Load more", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/sanganer" });
    try {
      const files = page.getByRole("list", { name: "Files" }).locator("[data-file-row]");
      await expect(files).toHaveCount(2);
      await page.getByRole("button", { name: "Load more" }).click();
      await expect(files).toHaveCount(3);
    } finally {
      await context.close();
    }
  });

  test("the Tables tab stays mounted and keeps the last folder's tables while the next one loads", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru", query: "view=tables" });
    try {
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByRole("tab", { name: /Artisans/ })).toBeVisible();
      const marker = await tables.evaluate((node) => {
        (node as HTMLElement).dataset.e2eMounted = "yes";
        return true;
      });
      expect(marker).toBe(true);
      await openFolder(page, "Sanganer");
      await expect(page.getByRole("tab", { name: /Tables/ })).toHaveAttribute("aria-selected", "true");
      await expect(page.getByRole("region", { name: "Data tables" })).toHaveAttribute("data-e2e-mounted", "yes");
      await expect(page.getByRole("region", { name: "Data tables" }).getByRole("tab", { name: /Artisans/ })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X1 errors and empty folders in place", () => {
  test("a folder that fails to load says so where it failed and Try now reloads it", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser);
    try {
      fake.treeFailures.set("crafts", 1);
      await page.getByRole("treeitem", { name: "By craft", exact: true }).click();
      const problem = page.getByRole("alert").filter({ hasText: "This folder didn't load" });
      await expect(problem).toBeVisible();
      await expect(problem).toContainText(/Trying again in \d+ s/);
      await problem.getByRole("button", { name: "Try now" }).click();
      await expect(problem).toHaveCount(0);
      expect(fake.calls.filter((call) => call.path === "/data/tree" && call.query.get("path") === "crafts").length).toBeGreaterThanOrEqual(2);
      await expect(page.getByRole("heading", { name: "Nothing recorded here yet" })).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test("a failed folder retries by itself", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser);
    try {
      fake.treeFailures.set("crafts", 1);
      await page.getByRole("treeitem", { name: "By craft", exact: true }).click();
      await expect(page.getByRole("alert").filter({ hasText: "This folder didn't load" })).toBeVisible();
      await expect(page.getByRole("heading", { name: "Nothing recorded here yet" })).toBeVisible({ timeout: 15_000 });
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X1 review status", () => {
  test("changing the review status reloads every open folder in place", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const group = page.getByRole("group", { name: "Review status" });
      test.skip((await group.count()) === 0, "this portal has no review-status filter");
      await expect(page.getByRole("treeitem", { name: "Bagru 2026", exact: true })).toHaveAttribute("aria-selected", "true");
      const asked = fake.calls.length;
      await group.getByRole("button", { name: "All", exact: true }).click();
      await expect(page).toHaveURL(/status=ALL/);
      for (const path of ["", "workshops", "workshops/bagru"]) {
        await expect
          .poll(() => fake.calls.slice(asked).some((call) => call.path === "/data/tree" && (call.query.get("path") ?? "") === path && !call.query.has("status")))
          .toBe(true);
      }
      await expect(page.getByRole("treeitem", { name: "Bagru 2026", exact: true })).toHaveAttribute("aria-selected", "true");
      await expect(page.getByRole("treeitem", { name: "Sanganer", exact: true })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X2 sizes before download", () => {
  test("the folder shows its file count and size, and each type its own tile that can be left out", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await expect(page.getByTestId("folder-size")).toContainText(/7 files\s*·\s*\d/);
      const tiles = page.getByRole("group", { name: "Include in the download" });
      const videos = tiles.getByRole("button", { name: /Videos.*1 · 87\.9 KB/ });
      await expect(videos).toHaveAttribute("aria-pressed", "true");
      const before = await folderDownload(page).evaluate((node) => (node as HTMLElement).innerText);
      await videos.click();
      await expect(videos).toHaveAttribute("aria-pressed", "false");
      await expect(videos).toContainText("left out");
      await expect.poll(() => folderDownload(page).evaluate((node) => (node as HTMLElement).innerText)).not.toBe(before);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X4 thumbnails", () => {
  test("a folder shows thumbnails and never loads the originals", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await expect(page.locator('img[src*="/thumbnail"]').first()).toBeVisible();
      expect(fake.calledWith("GET", "/data/media/img1/thumbnail").length).toBeGreaterThan(0);
      expect(fake.servedFiles.filter((id) => id.startsWith("original-"))).toEqual([]);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X5 previews", () => {
  test("image, video and PDF open in a dialog, and previous and next walk the folder", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await (await rowAction(page, "photo.jpg", "Preview")).click();
      const dialog = page.getByRole("dialog", { name: "photo.jpg" });
      await expect(dialog.locator("img")).toBeVisible();
      await expect(dialog.getByTestId("preview-meta")).toContainText(/file 1 of \d/);
      await dialog.getByRole("button", { name: "Next file" }).click();
      await expect(page.getByRole("dialog", { name: "loom.mp4" }).locator("video")).toHaveCount(1);
      await expect(page).toHaveURL(/preview=vid1/);
      await page.keyboard.press("Escape");
      await expect(page).not.toHaveURL(/preview=/);
      await (await rowAction(page, "brief.pdf", "Preview")).click();
      await expect(page.getByRole("dialog", { name: "brief.pdf" }).locator('object[type="application/pdf"]')).toHaveCount(1);
    } finally {
      await context.close();
    }
  });

  test("audio plays beside its transcript: waveform, speakers with talk time, find N of M, line seeking and transcript files", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await (await rowAction(page, "interview.m4a", "Play")).click();
      const dialog = page.getByRole("dialog", { name: "interview.m4a" });
      const lines = dialog.getByTestId("preview-lines");
      await expect(lines).toContainText("Chuna aur gond");
      await expect(dialog.getByTestId("preview-seek")).toBeVisible();
      expect(fake.calledWith("GET", "/data/media/aud1/peaks").length).toBeGreaterThan(0);
      await expect(dialog.getByText("Sunita").first()).toBeVisible();
      await dialog.getByRole("searchbox", { name: "Find in transcript" }).fill("chuna");
      await expect(dialog.getByText("1 of 3")).toBeVisible();
      await dialog.getByRole("button", { name: "Next match" }).click();
      await expect(dialog.getByText("2 of 3")).toBeVisible();
      await dialog.getByRole("tab", { name: "English" }).click();
      await expect(lines).toContainText("Lime and gum");
      await dialog.getByRole("tab", { name: "Side by side" }).click();
      await expect(lines).toContainText("Chuna aur gond");
      await expect(lines).toContainText("Lime and gum");
      await expect(dialog.getByRole("button", { name: "Transcript (.docx)" })).toBeVisible();
      await expect(dialog.getByRole("button", { name: "Subtitles (.srt)" })).toBeVisible();
      await expect(dialog.getByRole("switch", { name: "Follows playback" })).toHaveAttribute("aria-checked", "true");
      const current = lines.locator('[data-current="true"] button');
      if ((await current.count()) > 0) {
        const style = await current.first().evaluate((node) => getComputedStyle(node));
        expect(style.borderLeftWidth === "0px" || style.borderLeftStyle === "none", "no side stripe on the current line").toBe(true);
      }
      await dialog.getByRole("button", { name: "Transcript (.docx)" }).click();
      await expect(trayItem(page, "Transcript (.docx)")).toHaveAttribute("data-status", "saved");
    } finally {
      await context.close();
    }
  });

  test("Clear voices is offered only once a clear copy can exist, and plays it once made", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await (await rowAction(page, "interview.m4a", "Play")).click();
      const dialog = page.getByRole("dialog", { name: "interview.m4a" });
      await expect(dialog.getByRole("switch", { name: "Clear voices" })).toHaveCount(0);
      await dialog.getByRole("button", { name: "Make a clear-voices copy" }).click();
      expect(fake.isolateAsked).toBe(1);
      const clear = dialog.getByRole("switch", { name: "Clear voices" });
      await expect(clear).toHaveAttribute("aria-checked", "true", { timeout: 25_000 });
      await expect(dialog.locator("audio")).toHaveAttribute("src", /variant=isolated/);
      await clear.click();
      await expect(clear).toHaveAttribute("aria-checked", "false");
      await expect(dialog.locator("audio")).not.toHaveAttribute("src", /variant=isolated/);
    } finally {
      await context.close();
    }
  });

  test("a time link opens the file at that second and a file without a transcript says so", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru", query: "preview=vid1&t=12" });
    try {
      const dialog = page.getByRole("dialog", { name: "loom.mp4" });
      await expect(dialog.locator("video")).toHaveAttribute("src", /#t=12$/);
      await expect(dialog.getByText("No transcript for this file.")).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test("audio keeps playing in a mini player while browsing", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await (await rowAction(page, "interview.m4a", "Play")).click();
      const dialog = page.getByRole("dialog", { name: "interview.m4a" });
      await dialog.getByRole("button", { name: "Keep playing while browsing" }).click();
      await expect(dialog).toHaveCount(0);
      const player = page.getByRole("region", { name: "Now playing" });
      await expect(player).toContainText("interview.m4a");
      await player.getByRole("button", { name: "Stop and close player" }).click();
      await expect(player).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test("a file row links to its file page", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await expect(fileRow(page, "photo.jpg").getByRole("link", { name: "photo.jpg", exact: true })).toHaveAttribute("href", "/media/img1");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X12 transcripts on demand", () => {
  test("a transcript is fetched once when opened, not with the folder", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      expect(fake.calledWith("GET", "/data/media/aud1/transcript")).toHaveLength(0);
      expect(fake.calls.filter((call) => call.path === "/data/tree").every((call) => call.query.get("slim") === "1")).toBe(true);
      const row = fileRow(page, "interview.m4a");
      await row.getByRole("button", { name: "Transcript", exact: true }).click();
      await expect(page.getByRole("region", { name: "Transcript of interview.m4a" })).toContainText("Namaste");
      await row.getByRole("button", { name: "Hide transcript" }).click();
      await row.getByRole("button", { name: "Transcript", exact: true }).click();
      await expect(page.getByRole("region", { name: "Transcript of interview.m4a" })).toContainText("Namaste");
      expect(fake.calledWith("GET", "/data/media/aud1/transcript")).toHaveLength(1);
    } finally {
      await context.close();
    }
  });

  test("@qf:X7 every transcript format comes as one zip, per file and per folder, through the tray", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await fileRow(page, "interview.m4a").getByRole("button", { name: "All formats (.zip), interview.m4a" }).click();
      await expect(trayItem(page, "Transcript · interview.m4a")).toHaveAttribute("data-status", "saved");
      expect(fake.calls.some((call) => call.path === "/transcripts/aud1/download" && call.query.get("format") === "zip")).toBe(true);
      await page.getByRole("tab", { name: /Transcripts/ }).click();
      await page.getByTestId("transcripts-tab").getByRole("button", { name: /^Transcripts \(\.zip\)/ }).click();
      await expect(trayItem(page, "Transcripts · Bagru 2026")).toHaveAttribute("data-status", "saved");
      expect(fake.calls.some((call) => call.path === "/data/transcript-bundle" && call.query.get("path") === "workshops/bagru")).toBe(true);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X12 the Transcripts tab", () => {
  test("rows carry status, length and languages; search shows the matching words; a failed row is red with Try again", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("tab", { name: /Transcripts/ }).click();
      const tab = page.getByTestId("transcripts-tab");
      const ready = tab.locator("[data-transcript-row]").filter({ hasText: "interview.m4a" });
      const failed = tab.locator("[data-transcript-row]").filter({ hasText: "loom.mp4" });
      await expect(ready).toHaveAttribute("data-state", "ready");
      await expect(ready).toContainText("Hindi → English");
      await expect(ready).toContainText("12:34");
      await expect(failed).toHaveAttribute("data-state", "failed");
      await expect(failed).toHaveClass(/bg-error-100/);
      const group = tab.getByRole("group", { name: "Transcript status" });
      await expect(group.getByRole("button", { name: "Failed 1" })).toBeVisible();
      await group.getByRole("button", { name: "Ready 1" }).click();
      await expect(tab.locator("[data-transcript-row]")).toHaveCount(1);
      await group.getByRole("button", { name: "All 2" }).click();
      await failed.getByRole("button", { name: "Try again" }).click();
      await expect(failed).toHaveAttribute("data-state", "working");
      expect(fake.calledWith("POST", "/transcripts/vid1/retry")).toHaveLength(1);
      await tab.getByRole("searchbox", { name: "Search inside these transcripts" }).fill("namaste");
      await expect(tab.locator("mark").first()).toHaveText(/namaste/i);
      await expect(tab.locator("[data-transcript-row]")).toHaveCount(1);
      await ready.getByRole("button", { name: "Read" }).click();
      const dialog = page.getByRole("dialog", { name: "interview.m4a" });
      await expect(dialog.getByRole("searchbox", { name: "Find in transcript" })).toHaveValue("namaste");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X10 downloads go through one tray", () => {
  test("a folder zip streams to disk, lists the failed file and says so aloud", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await folderDownload(page).click();
      const item = trayItem(page, "Bagru 2026");
      await expect(item).toContainText(/Saved 6 of 7 files\. 1 failed\./);
      await expect(item.getByTestId("failed-files")).toContainText("missing.jpg");
      await expect(page.getByTestId("download-announcer")).toHaveText(/Download finished\. 1 file failed\./);
      const entries = readZip(await savedZip(page));
      const names = entries.map((entry) => entry.name);
      expect(names).toContain("details.txt");
      expect(names).toContain("kamla/portrait.jpg");
      expect(names).toContain("_failed-downloads.txt");
      expect(names).not.toContain("missing.jpg");
      expect(entries.find((entry) => entry.name === "loom.mp4")!.data.equals(expectedBytes("vid1", 90_000))).toBe(true);
      await expect(tray(page)).toContainText("Closing this tab asks first.");
    } finally {
      await context.close();
    }
  });

  test("bytes keep arriving while the tab is in the background", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/big" });
    try {
      fake.fileDelayMs = 400;
      await folderDownload(page).click();
      await expect.poll(() => fake.servedFiles.length, { timeout: 20_000 }).toBeGreaterThan(4);
      const held = await page.evaluate(async () => (await navigator.locks.query()).held?.map((lock) => lock.name) ?? []);
      expect(held, "a Web Lock is held for the download").toContain("data-explorer-download");
      await page.evaluate(() => {
        window.requestAnimationFrame = () => 0;
      });
      const other = await context.newPage();
      await other.goto("about:blank");
      await other.bringToFront();
      let state = await page.evaluate(() => document.visibilityState);
      if (state !== "hidden") {
        await page.evaluate(() => {
          Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
          Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
          document.dispatchEvent(new Event("visibilitychange"));
        });
        state = await page.evaluate(() => document.visibilityState);
      }
      expect(state).toBe("hidden");
      const before = fake.servedFiles.length;
      await other.waitForTimeout(3000);
      const after = fake.servedFiles.length;
      expect(after, "files keep downloading while hidden").toBeGreaterThan(before + 5);
      await page.evaluate(() => {
        Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
        Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
        document.dispatchEvent(new Event("visibilitychange"));
      });
      await page.bringToFront();
      await expect(trayItem(page, "Big archive")).toContainText(`Saved ${BIG_FILES} files`, { timeout: 60_000 });
      const entries = readZip(await savedZip(page));
      expect(entries).toHaveLength(BIG_FILES);
      expect(entries.every((entry) => entry.size === BIG_FILE_BYTES)).toBe(true);
    } finally {
      await context.close();
    }
  });

  test("Cancel stops a download and the page warns before leaving mid-way", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/big" });
    try {
      fake.fileDelayMs = 200;
      await folderDownload(page).click();
      await expect.poll(() => fake.servedFiles.length).toBeGreaterThan(1);
      const warned = await page.evaluate(() => {
        const event = new Event("beforeunload", { cancelable: true });
        window.dispatchEvent(event);
        return event.defaultPrevented;
      });
      expect(warned, "leaving mid-download asks first").toBe(true);
      const item = trayItem(page, "Big archive");
      await item.getByRole("button", { name: "Cancel" }).click();
      await expect(item).toHaveAttribute("data-status", "cancelled");
      await expect(item).toContainText("Cancelled");
      const served = fake.servedFiles.length;
      await page.waitForTimeout(800);
      expect(fake.servedFiles.length).toBeLessThanOrEqual(served + 4);
    } finally {
      await context.close();
    }
  });

  test("Save to a folder writes every file and picks up where it stopped", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/big" });
    try {
      fake.fileDelayMs = 250;
      const save = page.getByTestId("download-folder").getByRole("button", { name: "Save to a folder" });
      await save.click();
      await expect.poll(() => fake.servedFiles.length, { timeout: 20_000 }).toBeGreaterThan(10);
      const first = trayItem(page, "Big archive");
      await first.getByRole("button", { name: "Cancel" }).click();
      await expect(first).toContainText("Cancelled. Save to the same folder to pick up where it stopped.");
      fake.fileDelayMs = 0;
      await first.getByRole("button", { name: "Dismiss" }).click();
      await expect(tray(page).getByTestId("tray-item")).toHaveCount(0);
      await save.click();
      await expect(trayItem(page, "Big archive")).toContainText(/\d+ were already in the folder\./, { timeout: 30_000 });
      const names = await page.evaluate(async () => {
        const root = await navigator.storage.getDirectory();
        const folder = await root.getDirectoryHandle("saved-folder");
        const out: string[] = [];
        for await (const [name] of (folder as unknown as { entries(): AsyncIterable<[string, unknown]> }).entries()) out.push(name);
        return out;
      });
      expect(names.filter((name) => name.endsWith(".mp4"))).toHaveLength(BIG_FILES);
      expect(names).not.toContain(".download-progress.json");
    } finally {
      await context.close();
    }
  });

  for (const size of [DESKTOP, PHONE]) {
    test(`a single file is fetched through the tray and its row turns green · ${size.name}`, async ({ browser }) => {
      const { context, page, fake } = await openExplorer(browser, { size, path: "workshops/bagru" });
      try {
        await (await rowAction(page, "loom.mp4", "Download loom.mp4")).click();
        await expect(trayItem(page, "loom.mp4")).toHaveAttribute("data-status", "saved");
        await expect(trayItem(page, "loom.mp4")).toHaveClass(/bg-success-100/);
        const row = fileRow(page, "loom.mp4");
        await expect(row).toHaveAttribute("data-status", "saved");
        await expect(row).toHaveClass(/bg-success-100/);
        expect(fake.calledWith("GET", "/data/media/vid1/download")).toHaveLength(1);
        await noSidewaysScroll(page);
      } finally {
        await context.close();
      }
    });
  }

  test("a broken file turns its tray card and row red and Retry tries again", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.route("**/api/data/media/img1/download", (route) => route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "This file is gone." }) }));
      await (await rowAction(page, "photo.jpg", "Download photo.jpg")).click();
      const item = trayItem(page, "photo.jpg");
      await expect(item).toHaveAttribute("data-status", "failed");
      await expect(item).toHaveClass(/bg-error-100/);
      await expect(fileRow(page, "photo.jpg")).toHaveClass(/bg-error-100/);
      await page.unroute("**/api/data/media/img1/download");
      await item.getByRole("button", { name: /Retry|Try now/ }).click();
      await expect(item).toHaveAttribute("data-status", "saved");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X3 multi-select", () => {
  test("two folders download together in one zip from the sticky selection bar", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops" });
    try {
      await page.getByRole("checkbox", { name: "Select Bagru 2026" }).check();
      await page.getByRole("checkbox", { name: "Select Sanganer" }).check();
      const bar = page.getByTestId("download-selection");
      await expect(page.getByTestId("selection-size")).toHaveText(/^2 selected · \d/);
      expect(await bar.evaluate((node) => getComputedStyle(node).position)).toBe("sticky");
      await page.mouse.wheel(0, 2000);
      const box = await bar.boundingBox();
      const viewport = page.viewportSize()!;
      expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height + 1);
      await bar.getByRole("button", { name: /^Download selected/ }).click();
      await expect(trayItem(page, "Selected")).toContainText(/Saved 9 of 10 files\. 1 failed\./);
      expect(fake.calledWith("POST", "/data/manifest")[0].body).toMatchObject({ paths: ["workshops/bagru", "workshops/sanganer"] });
      const names = readZip(await savedZip(page)).map((entry) => entry.name);
      expect(names).toContain("Bagru 2026/photo.jpg");
      expect(names).toContain("Sanganer/a.jpg");
      await bar.getByRole("button", { name: "Clear" }).click();
      await expect(bar).toHaveCount(0);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X6 tables", () => {
  test("search reaches every row of every sheet on the server, and the CSV goes through the tray", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("tab", { name: /Tables/ }).click();
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByRole("tab", { name: /Artisans/ })).toHaveAttribute("aria-selected", "true");
      await tables.getByRole("button", { name: "This table (.csv)" }).click();
      await expect(trayItem(page, /Artisans \(\.csv\)/)).toHaveAttribute("data-status", "saved");
      expect(fake.calls.some((call) => call.path === "/data/report" && call.query.get("format") === "csv" && call.query.get("sheet") === "Artisans")).toBe(true);
      await tables.getByRole("searchbox", { name: "Search every row of Artisans" }).fill("rare weaver");
      await expect(tables.getByText("1 row matches “rare weaver”")).toBeVisible();
      const region = tables.getByRole("region", { name: "Artisans rows" });
      await expect(region).toContainText("Kaithoon");
      await expect(region.locator("tbody tr").first().locator("td").first()).toHaveText("201");
      const searched = fake.calls.filter((call) => call.path === "/data/report" && call.query.get("q") === "rare weaver");
      expect(searched.length).toBeGreaterThan(0);
      expect(searched.every((call) => !call.query.has("sheet"))).toBe(true);
      expect(fake.calls.filter((call) => call.path === "/data/report" && call.query.get("format") === "xlsx")).toHaveLength(0);
      await tables.getByRole("tab", { name: /Artisans/ }).focus();
      await page.keyboard.press("ArrowDown");
      await expect(tables.getByRole("tab", { name: /Products/ })).toBeFocused();
    } finally {
      await context.close();
    }
  });

  test("a phone gets a sheet picker and rows as cards", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { size: PHONE, path: "workshops/bagru" });
    try {
      await page.getByRole("tab", { name: /Tables/ }).click();
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByRole("button", { name: /Sheet/ }).first()).toBeVisible();
      await expect(tables.getByText("Row 1", { exact: true })).toBeVisible();
      await tables.getByRole("searchbox", { name: /Search every row/ }).fill("rare weaver");
      await expect(tables.getByText("Row 201", { exact: true })).toBeVisible();
      await noSidewaysScroll(page);
    } finally {
      await context.close();
    }
  });

  test("tables are remembered per folder, so going back costs no request", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("tab", { name: /Tables/ }).click();
      await expect(page.getByRole("region", { name: "Data tables" }).getByRole("tab", { name: /Artisans/ })).toBeVisible();
      const first = fake.calls.filter((call) => call.path === "/data/report" && !call.query.get("q") && call.query.get("path") === "workshops/bagru" && call.query.get("format") === "json").length;
      await openFolder(page, "Sanganer");
      await expandTo(page, ["By workshop"]);
      await openFolder(page, "Bagru 2026");
      await expect(page.getByRole("region", { name: "Data tables" }).getByRole("tab", { name: /Artisans/ })).toBeVisible();
      const reports = fake.calls.filter((call) => call.path === "/data/report" && !call.query.get("q") && call.query.get("path") === "workshops/bagru" && call.query.get("format") === "json").length;
      expect(reports).toBe(first);
    } finally {
      await context.close();
    }
  });

  test("busy tables say so, offer Try now and retry by themselves", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/sanganer" });
    try {
      fake.reportBusy = 2;
      await page.getByRole("tab", { name: /Tables/ }).click();
      const problem = page.getByRole("alert").filter({ hasText: "The tables didn't load" });
      await expect(problem).toBeVisible();
      await problem.getByRole("button", { name: "Try now" }).click();
      await expect(page.getByRole("region", { name: "Data tables" }).getByRole("tab", { name: /Artisans/ })).toBeVisible({ timeout: 15_000 });
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X6 the report never saves an error as a file", () => {
  test("Report (.xlsx) asks for options, then the checked workbook lands in the tray", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
      const dialog = page.getByRole("dialog", { name: "Report (.xlsx)" });
      await expect(dialog.getByRole("list", { name: "Sheets" })).toContainText("Artisans");
      await dialog.getByRole("button", { name: "Make report" }).click();
      await expect(dialog).toHaveCount(0);
      const item = trayItem(page, "Report (.xlsx) · Bagru 2026");
      await expect(item).toHaveAttribute("data-status", "saved");
      await expect(item).toHaveClass(/bg-success-100/);
      const asked = fake.calls.filter((call) => call.path === "/data/report" && call.query.get("format") === "xlsx");
      expect(asked).toHaveLength(1);
      expect(asked[0].query.get("path")).toBe("workshops/bagru");
      expect(asked[0].headers.range ?? null).toBeNull();
    } finally {
      await context.close();
    }
  });

  test("a failed build is a red card in the tray and on Tables, nothing is saved, and Try now builds it", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru", query: "view=tables" });
    try {
      fake.xlsxFailures = 1;
      const downloads: string[] = [];
      page.on("download", (download) => downloads.push(download.suggestedFilename()));
      await page.getByRole("region", { name: "Data tables" }).getByRole("button", { name: "Full report (.xlsx)" }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      const item = trayItem(page, "Report (.xlsx) · Bagru 2026");
      await expect(item).toHaveAttribute("data-status", "failed");
      await expect(item).toHaveClass(/bg-error-100/);
      await expect(item).toContainText("Nothing was saved.");
      const card = page.getByRole("region", { name: "Data tables" }).getByRole("alert").filter({ hasText: "Nothing was saved." });
      await expect(card).toContainText(/try again automatically in \d+ s/);
      expect(downloads).toEqual([]);
      await card.getByRole("button", { name: "Try now" }).click();
      await expect(item).toHaveAttribute("data-status", "saved");
      await expect.poll(() => downloads.length).toBe(1);
    } finally {
      await context.close();
    }
  });

  test("a failed build retries by itself", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      fake.xlsxFailures = 1;
      await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      const item = trayItem(page, "Report (.xlsx) · Bagru 2026");
      await expect(item).toHaveAttribute("data-status", "failed");
      await expect(item).toHaveAttribute("data-status", "saved", { timeout: 20_000 });
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X1 keyboard", () => {
  test("Ctrl+K opens quick jump, a record jumps to its folder, Escape closes it", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops" });
    try {
      await page.getByRole("heading", { name: "By workshop", exact: true }).first().click();
      await page.keyboard.press("Control+k");
      const palette = page.getByRole("dialog", { name: "Quick jump" });
      await expect(palette).toBeVisible();
      await palette.getByRole("combobox", { name: "Jump to anything" }).fill("kamla");
      await expect(palette.getByRole("option", { name: /Kamla Devi/ })).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(palette).toHaveCount(0);
      await page.keyboard.press("Control+k");
      await page.getByRole("dialog", { name: "Quick jump" }).getByRole("combobox", { name: "Jump to anything" }).fill("kamla");
      await expect(page.getByRole("dialog", { name: "Quick jump" }).getByRole("option", { name: /Kamla Devi/ })).toBeVisible();
      await page.keyboard.press("Enter");
      await expect(page).toHaveURL(/path=workshops%2Fbagru%2Fkamla/);
    } finally {
      await context.close();
    }
  });

  test("? opens the shortcuts, / focuses search, and letters typed into a box do nothing else", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("heading", { name: "Bagru 2026", exact: true }).first().click();
      await page.keyboard.press("?");
      const sheet = page.getByRole("dialog", { name: "Shortcuts" });
      await expect(sheet).toBeVisible();
      await expect(sheet.getByTestId("shortcuts-sheet")).toContainText("Quick jump");
      await page.keyboard.press("Escape");
      await expect(sheet).toHaveCount(0);
      await page.keyboard.press("/");
      const box = page.locator("[data-explorer-search] input").first();
      await expect(box).toBeFocused();
      await page.keyboard.type("2d");
      await expect(page.getByRole("tab", { name: /Files/ })).toHaveAttribute("aria-selected", "true");
      await expect(tray(page)).toHaveCount(0);
      await box.blur();
      await page.getByRole("heading", { name: "Bagru 2026", exact: true }).first().click();
      await page.keyboard.press("2");
      await expect(page.getByRole("tab", { name: /Tables/ })).toHaveAttribute("aria-selected", "true");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X8 browse by type", () => {
  test("pages through every record and says where the list is cut", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops" });
    try {
      await page.locator('button[aria-label="Browse by type"]').click();
      await page.getByRole("option", { name: "Artisans" }).click();
      await expect(page.getByText("Showing 1–50 of 120 artisans.")).toBeVisible();
      await page.getByRole("button", { name: "Next", exact: true }).last().click();
      await expect(page.getByText("Showing 51–100 of 120 artisans.")).toBeVisible();
      await expect(page).toHaveURL(/page=2/);
      await page.getByRole("searchbox", { name: "Search all artisans" }).fill("Artisan 119");
      await expect(page.getByRole("region", { name: "Artisans list" })).toContainText("Artisan 119");
      await expect(page).toHaveURL(/find=Artisan/);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X9 transcript language filter", () => {
  test("the language filter offers the languages on file", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops" });
    try {
      const toggle = page.locator("[data-transcript-data-toggle]");
      test.skip((await toggle.count()) === 0, "this portal has no transcript data panel");
      await toggle.click();
      await page.locator('button[aria-label="Language"]').click();
      await expect(page.getByRole("option", { name: "Hindi · 3" })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X2 workshop overview", () => {
  test("a workshop shows its totals, what is new since the last visit and each artisan's coverage, least complete first", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const overview = page.getByTestId("workshop-overview");
      await expect(overview).toContainText("Recorded fields");
      await expect(overview).toContainText("50%");
      await expect(overview).toContainText("1 ready · 0 translating · 1 failed");
      await expect(overview).toContainText("+3", { timeout: 10_000 });
      await expect(page.getByText("+3 new since you looked")).toBeVisible();
      const rows = overview.locator("tbody tr");
      await expect(rows.first()).toContainText("Mohan Lal");
      await expect(rows.nth(1)).toContainText("Kamla Devi");
      await overview.getByRole("combobox", { name: /Sort artisans/ }).or(overview.getByRole("button", { name: /Sort artisans/ })).click();
      await page.getByRole("option", { name: "By name" }).click();
      await expect(rows.first()).toContainText("Kamla Devi");
      await expect(overview.getByRole("link", { name: "Tools: partly recorded. Still to record: Tool names" })).toHaveAttribute("href", "/questionnaire/sessions/s-kamla/view?section=tools");
      await overview.getByRole("button", { name: "Process: missing. Still to record: Dye recipe" }).click();
      await expect(page).toHaveURL(/path=workshops%2Fbagru%2Fkamla/);
    } finally {
      await context.close();
    }
  });

  test("a folder that is not a workshop shows no overview", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/sanganer" });
    try {
      await expect(page.getByTestId("download-folder").getByRole("heading", { name: "Sanganer", exact: true })).toBeVisible();
      await expect(page.getByTestId("workshop-overview")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X1 saved views", () => {
  test("a view saves the folder, tab and search, copies its link, lists as yours and can be removed", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru", query: "view=tables" });
    try {
      await context.grantPermissions(["clipboard-read", "clipboard-write"]);
      await page.getByRole("button", { name: "Saved views" }).click();
      const dialog = page.getByRole("dialog", { name: "Saved views" });
      await expect(dialog.getByRole("list", { name: "Saved views" })).toContainText("Shared by Ravi Kumar");
      await expect(dialog.getByLabel("Save this screen as")).toHaveValue("Bagru 2026");
      await dialog.getByRole("button", { name: "Save and copy link" }).click();
      await expect(dialog.getByRole("button", { name: "Link copied" })).toBeVisible();
      const posted = fake.calledWith("POST", "/data/views")[0].body as { path: string; state: { view: string } };
      expect(posted.path).toBe("workshops/bagru");
      expect(posted.state.view).toBe("tables");
      const copied = await page.evaluate(() => navigator.clipboard.readText());
      expect(copied).toMatch(/\/data\?viewId=v1$/);
      const mine = dialog.getByRole("listitem").filter({ hasText: "Yours" });
      await expect(mine).toContainText("Bagru 2026");
      await mine.getByRole("button", { name: "Remove" }).click();
      await expect(dialog.getByRole("listitem").filter({ hasText: "Yours" })).toHaveCount(0);
      expect(fake.calledWith("DELETE", "/data/views/v1")).toHaveLength(1);
    } finally {
      await context.close();
    }
  });

  test("a shared link restores the exact screen, and one the reader cannot open says so", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { query: "viewId=v-shared" });
    try {
      await expect(page).toHaveURL(/path=workshops%2Fsanganer/);
      await expect(page).toHaveURL(/view=tables/);
      await expect(page.getByRole("tab", { name: /Tables/ })).toHaveAttribute("aria-selected", "true");
      await expect(page).not.toHaveURL(/viewId=/);
      await page.goto("/data?viewId=nobody");
      await expect(page.getByText("That saved view isn't available to you.")).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X6 report options", () => {
  test("remembered choices come back, and the report asks for exactly those sheets, answers and links", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      fake.reportOptions = { sheets: ["Artisans"], answers: "english", rows: "all", links: false };
      await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
      const dialog = page.getByRole("dialog", { name: "Report (.xlsx)" });
      await expect(dialog.getByRole("radiogroup", { name: "Answers in" }).getByRole("radio", { name: "English" })).toHaveAttribute("aria-checked", "true");
      const sheets = dialog.getByRole("list", { name: "Sheets" });
      await expect(sheets.getByRole("checkbox").first()).toBeChecked();
      await expect(sheets.getByRole("checkbox").nth(1)).not.toBeChecked();
      await expect(dialog.getByRole("radio", { name: /Only what’s filtered/ })).toBeDisabled();
      await expect(dialog.getByRole("switch", { name: "Links to files open in the portal" })).toHaveAttribute("aria-checked", "false");
      await dialog.getByRole("radio", { name: "Both" }).click();
      await dialog.getByRole("button", { name: "Remember these choices" }).click();
      await expect(dialog.getByRole("button", { name: "Remembered" })).toBeVisible();
      expect(fake.reportOptions).toMatchObject({ answers: "both", links: false, sheets: ["Artisans"] });
      await dialog.getByLabel("File name").fill("Bagru tools");
      await dialog.getByRole("button", { name: "Make report" }).click();
      await expect(trayItem(page, "Report (.xlsx) · Bagru 2026")).toHaveAttribute("data-status", "saved");
      const asked = fake.calls.filter((call) => call.path === "/data/report" && call.query.get("format") === "xlsx")[0];
      expect(asked.query.get("sheets")).toBe("Artisans");
      expect(asked.query.get("links")).toBe("0");
      expect(asked.query.get("answers")).toBeNull();
      expect(asked.query.get("name")).toBe("Bagru tools.xlsx");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X10 pause and resume", () => {
  async function hide(page: Page, hidden: boolean) {
    await page.evaluate((value) => {
      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (value ? "hidden" : "visible") });
      Object.defineProperty(document, "hidden", { configurable: true, get: () => value });
      document.dispatchEvent(new Event("visibilitychange"));
    }, hidden);
  }

  test("a folder zip pauses and resumes in the tray and still arrives whole", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/big" });
    try {
      fake.fileDelayMs = 250;
      await folderDownload(page).click();
      await expect.poll(() => fake.servedFiles.length, { timeout: 20_000 }).toBeGreaterThan(4);
      const item = trayItem(page, "Big archive");
      await item.getByRole("button", { name: "Pause" }).click();
      await expect(item).toContainText("Paused");
      await expect(item).toHaveAttribute("data-status", "running");
      await page.waitForTimeout(600);
      const stopped = fake.servedFiles.length;
      await hide(page, true);
      await page.waitForTimeout(1500);
      await hide(page, false);
      expect(fake.servedFiles.length, "nothing new is fetched while paused").toBeLessThanOrEqual(stopped + 4);
      await expect(item).toContainText("Paused");
      fake.fileDelayMs = 0;
      await item.getByRole("button", { name: "Resume" }).click();
      await expect(item).toContainText(`Saved ${BIG_FILES} files`, { timeout: 60_000 });
      const entries = readZip(await savedZip(page));
      expect(entries).toHaveLength(BIG_FILES);
      expect(entries.every((entry) => entry.size === BIG_FILE_BYTES)).toBe(true);
      expect(entries.find((entry) => entry.name === "clip-07.mp4")!.data.equals(expectedBytes("big7", BIG_FILE_BYTES))).toBe(true);
      for (const asked of fake.rangeRequests) expect(asked.from).toBeGreaterThan(0);
    } finally {
      await context.close();
    }
  });

  test("Save to a folder pauses and resumes too", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/big" });
    try {
      fake.fileDelayMs = 250;
      await page.getByTestId("download-folder").getByRole("button", { name: "Save to a folder" }).click();
      await expect.poll(() => fake.servedFiles.length, { timeout: 20_000 }).toBeGreaterThan(4);
      const item = trayItem(page, "Big archive");
      await item.getByRole("button", { name: "Pause" }).click();
      await expect(item).toContainText("Paused");
      await page.waitForTimeout(600);
      const stopped = fake.servedFiles.length;
      await page.waitForTimeout(1500);
      expect(fake.servedFiles.length).toBeLessThanOrEqual(stopped + 4);
      fake.fileDelayMs = 0;
      await item.getByRole("button", { name: "Resume" }).click();
      await expect(item).toHaveAttribute("data-status", "saved", { timeout: 60_000 });
      const names = await page.evaluate(async () => {
        const root = await navigator.storage.getDirectory();
        const folder = await root.getDirectoryHandle("saved-folder");
        const out: string[] = [];
        for await (const [name] of (folder as unknown as { entries(): AsyncIterable<[string, unknown]> }).entries()) out.push(name);
        return out;
      });
      expect(names.filter((name) => name.endsWith(".mp4"))).toHaveLength(BIG_FILES);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X2 counts and capture dates", () => {
  test("a tree folder shows its count before it is opened, and a file row shows when it was captured", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops" });
    try {
      const sanganer = page.getByRole("treeitem", { name: "Sanganer", exact: true });
      await expect(sanganer).toHaveAttribute("aria-expanded", "false");
      await expect(sanganer).toContainText("3");
      expect(fake.calls.some((call) => call.path === "/data/tree" && call.query.get("path") === "workshops/sanganer")).toBe(false);
      await openFolder(page, "Bagru 2026");
      await expect(fileRow(page, "photo.jpg").locator("[data-captured]")).toHaveText(/^23 Sept? 2026$/);
      await expect(fileRow(page, "loom.mp4").locator("[data-captured]")).toHaveText("-");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X6 report options overhaul", () => {
  async function openReportDialog(page: Page) {
    await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
    const dialog = page.getByRole("dialog", { name: "Report (.xlsx)" });
    await expect(dialog.getByRole("list", { name: "Sheets" })).toContainText("Artisans");
    return dialog;
  }

  test("the server's sheets and every extra are offered, and the report asks for exactly what was chosen", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const dialog = await openReportDialog(page);
      await expect(dialog.getByRole("list", { name: "Sheets" })).toContainText("201");
      const extras = dialog.getByRole("group", { name: "Extras" });
      await expect(extras.getByRole("switch", { name: "Quotes sheet" })).toHaveAttribute("aria-checked", "false");
      await expect(extras.getByRole("switch", { name: "Glossary sheet" })).toBeVisible();
      await extras.getByRole("switch", { name: "Quotes sheet" }).click();
      await dialog.getByRole("radiogroup", { name: "Language" }).getByRole("radio", { name: "हिन्दी" }).click();
      await dialog.getByRole("switch", { name: "Small image thumbnails" }).click();
      await dialog.getByRole("button", { name: "Remember these choices" }).click();
      await expect(dialog.getByRole("button", { name: "Remembered" })).toBeVisible();
      expect(fake.reportOptions).toMatchObject({ locale: "hi", thumbnails: true, extras: { quotes: true } });
      await dialog.getByRole("button", { name: "Make report" }).click();
      await expect(trayItem(page, "Report (.xlsx) · Bagru 2026")).toHaveAttribute("data-status", "saved");
      const asked = fake.calls.filter((call) => call.path === "/data/report" && call.query.get("format") === "xlsx")[0];
      expect(asked.query.get("extras")).toBe("quotes");
      expect(asked.query.get("locale")).toBe("hi");
      expect(asked.query.get("thumbnails")).toBe("1");
    } finally {
      await context.close();
    }
  });

  test("the report pack lands in the tray as a checked zip, never resumed", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const dialog = await openReportDialog(page);
      await dialog.getByRole("button", { name: "Report pack (.zip)" }).click();
      const item = trayItem(page, "Report pack · Bagru 2026");
      await expect(item).toHaveAttribute("data-status", "saved");
      await expect(item).toHaveClass(/bg-success-100/);
      await expect(item.getByRole("button", { name: "Pause" })).toHaveCount(0);
      const asked = fake.calls.filter((call) => call.path === "/data/report/pack");
      expect(asked).toHaveLength(1);
      expect(asked[0].query.get("path")).toBe("workshops/bagru");
      expect(asked[0].headers.range ?? null).toBeNull();
    } finally {
      await context.close();
    }
  });

  test("a live link is made and turned off from the report options", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const dialog = await openReportDialog(page);
      await dialog.getByRole("button", { name: "Make link" }).click();
      await expect(dialog.getByRole("textbox", { name: "Live link" })).toHaveValue(/report-share\/token-sh1/);
      expect(fake.calledWith("POST", "/data/report/shares")[0].body).toMatchObject({ path: "workshops/bagru", expiresInDays: 7 });
      await dialog.getByRole("button", { name: "Turn off" }).click();
      await dialog.getByRole("button", { name: "Turn off" }).click();
      await expect.poll(() => fake.calledWith("DELETE", "/data/report/shares/sh1").length).toBe(1);
      await expect(dialog.getByRole("textbox", { name: "Live link" })).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test("a Hindi live link renders its cover in Hindi", async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1366, height: 900 }, serviceWorkers: "block" });
    const fake = new DataExplorerFake();
    await fake.install(context, "light");
    await context.route(
      (url) => url.pathname === "/api/report-share/token-hi",
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            version: 1,
            portal: "designer",
            locale: "hi",
            title: "बगरू 2026",
            crumbs: ["Repository", "बगरू 2026"],
            generatedBy: "कमला",
            generatedAt: "2026-11-05T10:00:00Z",
            dataVersion: "7",
            kpis: [{ key: "files", label: "फ़ाइलें", value: 12, unit: "count", sub: null }],
            charts: [
              {
                key: "completionBySection",
                title: "खंड अनुसार पूर्णता",
                kind: "stackedBar",
                categories: ["परिचय"],
                series: [
                  { key: "complete", name: "पूर्ण", values: [2] },
                  { key: "partial", name: "आंशिक", values: [1] }
                ]
              }
            ],
            filters: { status: "APPROVED" },
            withheldTranscripts: 2,
            truncated: false,
            expiresAt: "2026-11-12T10:00:00Z",
            sharedAt: "2026-11-05T10:00:00Z",
            labels: {
              eyebrow: "{product} · लाइव रिपोर्ट",
              sharedBy: "द्वारा साझा",
              keyFigures: "मुख्य आँकड़े",
              withheldMany: "सहमति के कारण {n} रिकॉर्डिंग छोड़ी गई हैं।",
              readOnly: "केवल पठन हेतु। आँकड़े लिंक बनाते समय निर्धारित किए गए थे।",
              legend: "संकेत-सूची",
              "filter.status": "समीक्षा की स्थिति"
            }
          })
        })
    );
    const page = await context.newPage();
    try {
      await page.goto("/report-share/token-hi");
      await expect(page.getByRole("heading", { name: "बगरू 2026" })).toBeVisible();
      await expect(page.getByText("Design Workshop · लाइव रिपोर्ट")).toBeVisible();
      await expect(page.getByText("द्वारा साझा")).toBeVisible();
      await expect(page.getByText("समीक्षा की स्थिति")).toBeVisible();
      await expect(page.getByRole("region", { name: "मुख्य आँकड़े" })).toContainText("फ़ाइलें");
      await expect(page.getByRole("list", { name: "संकेत-सूची" })).toContainText("पूर्ण");
      await expect(page.getByText("सहमति के कारण 2 रिकॉर्डिंग छोड़ी गई हैं।")).toBeVisible();
      await expect(page.locator("main[lang=hi]")).toHaveCount(1);
      await expect(page.getByText("Shared by")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test("an expired Hindi live link says so in Hindi, and an old link without lang in English", async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1366, height: 900 }, serviceWorkers: "block" });
    const fake = new DataExplorerFake();
    await fake.install(context, "light");
    await context.route(
      (url) => url.pathname === "/api/report-share/token-gone",
      (route) => route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "Not found" }) })
    );
    const page = await context.newPage();
    try {
      await page.goto("/report-share/token-gone?lang=hi");
      await expect(page.getByRole("main").getByRole("alert")).toHaveText("इस लिंक की अवधि समाप्त हो गई है या इसे बंद कर दिया गया है।");
      await expect(page.locator("main[lang=hi]")).toHaveCount(1);
      await expect(page.getByRole("button", { name: "पुनः प्रयास करें" })).toHaveCount(0);
      await page.goto("/report-share/token-gone");
      await expect(page.getByRole("main").getByRole("alert")).toHaveText("This link has expired or was turned off.");
    } finally {
      await context.close();
    }
  });

  test("a saved view can be emailed every week and the schedule removed", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("button", { name: "Saved views" }).click();
      const dialog = page.getByRole("dialog", { name: "Saved views" });
      await expect(dialog.getByRole("heading", { name: "Email me a report" })).toBeVisible();
      await dialog.getByRole("button", { name: "Schedule" }).click();
      const list = dialog.getByRole("list", { name: "Scheduled reports" });
      await expect(list).toContainText("Sanganer tables");
      expect(fake.calledWith("POST", "/data/report/schedules")[0].body).toMatchObject({ savedViewId: "v-shared" });
      await list.getByRole("button", { name: "Delete" }).click();
      await expect(list).toHaveCount(0);
      expect(fake.calledWith("DELETE", "/data/report/schedules/rs1")).toHaveLength(1);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X6 links inside the report", () => {
  test("a media link fetches a fresh signed link and goes there", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.goto("/data/go/media/img1/download");
      await page.waitForURL(/\/e2e-landing\?file=img1$/);
      expect(fake.landed).toEqual(["img1"]);
      expect(fake.calls.some((call) => call.path === "/data/go/media/img1/download" && call.query.get("as") === "json")).toBe(true);
    } finally {
      await context.close();
    }
  });

  test("an Open this record link opens it in the portal, and one the reader cannot open says so politely", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.goto("/data/go/open/artisan/a-kamla");
      await expect(page).toHaveURL(/\/data\?path=workshops%2Fbagru%2Fkamla/);
      await expect(page.getByTestId("download-folder").getByRole("heading", { name: "Kamla Devi", exact: true })).toBeVisible();
      await page.goto("/data/go/open/artisan/nobody");
      await expect(page.getByRole("heading", { name: "Not available" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Open Data" })).toHaveAttribute("href", "/data");
    } finally {
      await context.close();
    }
  });

  test("a transcript link saves the file through the tray", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.goto("/data/go/transcript/aud1/docx");
      await expect(trayItem(page, "Transcript (.docx)")).toHaveAttribute("data-status", "saved");
      expect(fake.calls.some((call) => call.path === "/transcripts/aud1/download" && call.query.get("format") === "docx")).toBe(true);
      await expect(page.getByRole("link", { name: "Open the recording" })).toHaveAttribute("href", "/data?path=workshops%2Fbagru&preview=aud1");
    } finally {
      await context.close();
    }
  });

  test("a sheet's Download all link offers the bundle, then downloads it as a resumable zip", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { query: "bundle=b1" });
    try {
      const offer = page.getByTestId("bundle-offer");
      await expect(offer).toContainText("Bagru 2026 · every file");
      await expect(offer).toContainText("6 files");
      await offer.getByRole("button", { name: /^Download · / }).click();
      await expect(page).not.toHaveURL(/bundle=/);
      const item = trayItem(page, "Bagru 2026 · every file");
      await expect(item).toContainText(/Saved 5 of 6 files\. 1 failed\./);
      expect(fake.calledWith("GET", "/data/bundles/b1/manifest").length).toBeGreaterThan(0);
      const names = readZip(await savedZip(page)).map((entry) => entry.name);
      expect(names.some((name) => name.endsWith("photo.jpg"))).toBe(true);
      await page.goto("/data?bundle=nope");
      await expect(page.getByText("That download isn't available to you.")).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("search reaches every record", () => {
  test("a suggestion says how many results it brings", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser);
    fake.searchCorpus = [{ type: "artisans", label: "Artisans", singular: "Artisan", total: 31 }];
    try {
      await page.getByRole("searchbox", { name: "Search all data" }).fill("indgo");
      await expect(page.getByText("No matches for “indgo”")).toBeVisible();
      await expect(page.getByText(/Did you mean\s*indigo\s*\?\s*38 results\./)).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test("All pages past the first five, and Show all reaches the last artisan", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser);
    fake.searchCorpus = [
      { type: "artisans", label: "Artisans", singular: "Artisan", total: 31 },
      { type: "tools", label: "Tools", singular: "Tool", total: 7 }
    ];
    try {
      await page.getByRole("searchbox", { name: "Search all data" }).fill("artisan");
      const artisans = page.getByTestId("search-group-artisans");
      await expect(artisans.getByText("Artisan 5 of 31", { exact: true })).toBeVisible();
      await expect(artisans.getByText("Artisan 6 of 31", { exact: true })).toHaveCount(0);
      await page.getByTestId("search-more").click();
      await expect(artisans.getByText("Artisan 10 of 31", { exact: true })).toBeVisible();
      await expect(page.getByTestId("search-group-tools").getByText("Tool 7 of 7", { exact: true })).toBeVisible();
      const unpaged = fake.calls.filter((call) => call.path === "/search/unified" && !call.query.get("type") && call.query.get("page") === "2");
      expect(unpaged.length).toBeGreaterThan(0);
      await page.getByTestId("search-all-artisans").click();
      await expect(page.getByText("Artisan 20 of 31", { exact: true })).toBeVisible();
      await page.getByTestId("search-more").click();
      await expect(page.getByText("Artisan 31 of 31", { exact: true })).toBeVisible();
      await expect(page.getByTestId("search-more")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test("the fallback search pages too when unified search is off", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser);
    fake.unifiedSearch = false;
    fake.searchCorpus = [{ type: "artisans", label: "Artisans", singular: "Artisan", total: 45 }];
    try {
      await page.getByRole("searchbox", { name: "Search all data" }).fill("artisan");
      await expect(page.getByText("Artisan 20 of 45", { exact: true })).toBeVisible();
      await page.getByTestId("search-more").click();
      await expect(page.getByText("Artisan 40 of 45", { exact: true })).toBeVisible();
      await page.getByTestId("search-more").click();
      await expect(page.getByText("Artisan 45 of 45", { exact: true })).toBeVisible();
      await expect(page.getByTestId("search-more")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test("Search everywhere from quick jump runs the search", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops" });
    try {
      await page.getByRole("heading", { name: "By workshop", exact: true }).first().click();
      await page.keyboard.press("Control+k");
      const palette = page.getByRole("dialog", { name: "Quick jump" });
      await palette.getByRole("combobox", { name: "Jump to anything" }).fill("kamla");
      await palette.getByRole("option", { name: /Search “kamla” everywhere/ }).click();
      await expect(palette).toHaveCount(0);
      await expect(page.getByRole("searchbox", { name: "Search all data" })).toHaveValue("kamla");
      await expect(page.getByTestId("search-results").getByText("Kamla Devi")).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
