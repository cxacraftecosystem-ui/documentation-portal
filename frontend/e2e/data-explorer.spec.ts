import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type Page } from "@playwright/test";

import { BIG_FILE_BYTES, BIG_FILES, DataExplorerFake, expectedBytes } from "./support/dataExplorerFake";

const SHOTS = process.env.E2E_SHOT_DIR ?? join("test-results", "data-explorer");
mkdirSync(SHOTS, { recursive: true });

type Size = { name: string; width: number; height: number; mobile: boolean };

const SIZES: Size[] = [
  { name: "desktop", width: 1366, height: 900, mobile: false },
  { name: "tablet", width: 820, height: 1180, mobile: true },
  { name: "phone", width: 390, height: 844, mobile: true }
];

async function openExplorer(browser: Browser, options: { size?: Size; theme?: "light" | "dark"; path?: string } = {}) {
  const size = options.size ?? SIZES[0];
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
  await page.goto(`/data${options.path ? `?path=${encodeURIComponent(options.path)}` : ""}`);
  await expect(page.getByTestId("folder-tree")).toBeVisible();
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

async function openFolder(page: Page, name: string) {
  const item = page.getByRole("treeitem", { name, exact: true });
  await item.click();
  await expect(item).toHaveAttribute("aria-selected", "true");
}

async function expandTo(page: Page, names: string[]) {
  for (const name of names) {
    const item = page.getByRole("treeitem", { name, exact: true });
    await expect(item).toBeVisible();
    if ((await item.getAttribute("aria-expanded")) === "false") {
      await item.focus();
      await page.keyboard.press("ArrowRight");
    }
  }
}

test.describe("@qf:X1 layout", () => {
  for (const size of SIZES) {
    for (const theme of ["light", "dark"] as const) {
      test(`tree first with search as its toolbar, downloads in one place · ${size.name} ${theme}`, async ({ browser }) => {
        const { context, page } = await openExplorer(browser, { size, theme, path: "workshops/bagru" });
        try {
          const panel = page.getByTestId("explorer-tree-panel");
          await expect(panel.getByRole("search", { name: "Search the repository" })).toBeVisible();
          await expect(page.getByRole("heading", { name: "Downloads" })).toHaveCount(1);
          const tree = await panel.boundingBox();
          const downloads = await page.getByRole("heading", { name: "Downloads" }).boundingBox();
          expect(tree && downloads).toBeTruthy();
          if (size.name === "desktop") expect(tree!.x).toBeLessThan(downloads!.x);
          else expect(tree!.y).toBeLessThan(downloads!.y);
          const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
          expect(overflow, "no sideways scroll").toBeLessThanOrEqual(1);
          if (size.mobile) {
            for (const target of [page.getByRole("treeitem", { name: "Bagru 2026", exact: true }), page.getByRole("button", { name: "Download zip" })]) {
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

test.describe("@qf:X1 downloads in one place, worded as on Android", () => {
  for (const size of [SIZES[0], SIZES[2]]) {
    test(`every folder download sits in Downloads and the Tables tab keeps only its sheet CSV · ${size.name}`, async ({ browser }) => {
      const { context, page } = await openExplorer(browser, { size, path: "workshops/bagru" });
      try {
        const downloads = page.getByRole("region", { name: "Downloads" });
        const folder = downloads.getByTestId("download-folder");
        await expect(folder.getByRole("button", { name: "Download zip" })).toBeVisible();
        await expect(folder.getByRole("link", { name: "Transcripts, all formats" })).toHaveAttribute("href", /\/api\/data\/transcript-bundle\?path=workshops%2Fbagru/);
        await expect(folder.getByRole("link", { name: "Report (.xlsx)" })).toHaveAttribute("href", /\/api\/data\/report\?path=workshops%2Fbagru&format=xlsx/);
        await expect(page.getByRole("link", { name: /\.xlsx/ })).toHaveCount(1);
        const all = page.getByRole("checkbox", { name: "Select all", exact: true });
        await expect(all).not.toBeChecked();
        await page.getByText("Select all", { exact: true }).click();
        await expect(all).toBeChecked();
        await expect(downloads.getByTestId("download-selection")).toBeVisible();
        if (size.mobile) {
          const chips = page.locator("[data-transcript-files] a");
          await expect(chips).toHaveCount(1);
          for (const link of await chips.all()) {
            const box = await link.boundingBox();
            expect(box!.height, "touch target").toBeGreaterThanOrEqual(44);
          }
        }
        await page.getByRole("tab", { name: "Tables" }).click();
        const tables = page.getByRole("region", { name: "Data tables" });
        await expect(tables.getByRole("link", { name: "Artisans .csv" })).toBeVisible();
        await expect(tables.getByRole("link", { name: /\.xlsx/ })).toHaveCount(0);
      } finally {
        await context.close();
      }
    });
  }
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
      await page.getByRole("tab", { name: "Tables" }).click();
      await expect(page).toHaveURL(/view=tables/);
      await page.reload();
      await expect(page.getByRole("tab", { name: "Tables" })).toHaveAttribute("aria-selected", "true");
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
});

test.describe("@qf:X1 errors in place", () => {
  test("a folder that fails to load says so where it failed and Retry reloads it", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser);
    try {
      fake.treeFailures.set("crafts", 1);
      await page.getByRole("treeitem", { name: "By craft", exact: true }).click();
      const problem = page.getByRole("alert").filter({ has: page.getByRole("button", { name: "Retry" }) });
      await expect(problem).toBeVisible();
      await expect(problem).toContainText("This folder could not be loaded.");
      const retry = problem.getByRole("button", { name: "Retry" });
      const box = await retry.boundingBox();
      expect(box).not.toBeNull();
      await retry.click();
      await expect(problem).toHaveCount(0);
      expect(fake.calls.filter((call) => call.path === "/data/tree" && call.query.get("path") === "crafts").length).toBeGreaterThanOrEqual(2);
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
      await group.getByRole("button", { name: "All statuses" }).click();
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
  test("the folder shows its file count and size, and each type its own", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await expect(page.getByTestId("folder-size")).toHaveText(/^7 files · \d/);
      await page.locator('button[aria-label^="Include in downloads"]').click();
      await expect(page.getByRole("option", { name: /^Videos · 1 file · 87\.9 KB$/ })).toBeVisible();
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
  test("image, video and PDF open in a dialog", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const row = (name: string) => page.locator("[data-file-row]").filter({ hasText: name });
      await row("photo.jpg").getByRole("button", { name: "Preview" }).click();
      const dialog = page.getByRole("dialog", { name: "photo.jpg" });
      await expect(dialog.locator("img")).toBeVisible();
      await page.keyboard.press("Escape");
      await row("loom.mp4").getByRole("button", { name: "Preview" }).click();
      await expect(page.getByRole("dialog", { name: "loom.mp4" }).locator("video")).toHaveCount(1);
      await page.keyboard.press("Escape");
      await row("brief.pdf").getByRole("button", { name: "Preview" }).click();
      await expect(page.getByRole("dialog", { name: "brief.pdf" }).locator('object[type="application/pdf"]')).toHaveCount(1);
    } finally {
      await context.close();
    }
  });

  test("a file row links to its file page", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const row = page.locator("[data-file-row]").filter({ hasText: "photo.jpg" });
      await expect(row.getByRole("link", { name: "photo.jpg", exact: true })).toHaveAttribute("href", "/media/img1");
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
      const row = page.locator("[data-file-row]").filter({ hasText: "interview.m4a" });
      await row.getByRole("button", { name: "Transcript" }).click();
      await expect(page.getByRole("region", { name: "Transcript of interview.m4a" })).toContainText("Namaste");
      await row.getByRole("button", { name: "Hide transcript" }).click();
      await row.getByRole("button", { name: "Transcript" }).click();
      await expect(page.getByRole("region", { name: "Transcript of interview.m4a" })).toContainText("Namaste");
      expect(fake.calledWith("GET", "/data/media/aud1/transcript")).toHaveLength(1);
    } finally {
      await context.close();
    }
  });

  test("@qf:X7 every transcript format comes as one zip, per file and per folder", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const row = page.locator("[data-file-row]").filter({ hasText: "interview.m4a" });
      await expect(row.getByRole("link", { name: "All formats (.zip), interview.m4a" })).toHaveAttribute("href", /\/api\/transcripts\/aud1\/download\?format=zip/);
      await expect(page.getByRole("link", { name: "Transcripts, all formats" })).toHaveAttribute("href", /\/api\/data\/transcript-bundle\?path=workshops%2Fbagru/);
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X10 downloads", () => {
  test("a folder zip streams to disk, lists the failed file and says so aloud", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("button", { name: "Download zip" }).click();
      const progress = page.getByTestId("download-progress");
      await expect(progress).toContainText(/Saved 6 of 7 files\. 1 failed\./);
      await expect(page.getByTestId("failed-files")).toContainText("missing.jpg");
      await expect(page.getByTestId("download-announcer")).toHaveText(/Download finished\. 1 file failed\./);
      const entries = readZip(await savedZip(page));
      const names = entries.map((entry) => entry.name);
      expect(names).toContain("details.txt");
      expect(names).toContain("kamla/portrait.jpg");
      expect(names).toContain("_failed-downloads.txt");
      expect(names).not.toContain("missing.jpg");
      expect(entries.find((entry) => entry.name === "loom.mp4")!.data.equals(expectedBytes("vid1", 90_000))).toBe(true);
    } finally {
      await context.close();
    }
  });

  test("bytes keep arriving while the tab is in the background", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/big" });
    try {
      fake.fileDelayMs = 400;
      await page.getByRole("button", { name: "Download zip" }).click();
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
      await expect(page.getByTestId("download-progress")).toContainText(`Saved ${BIG_FILES} files.`, { timeout: 60_000 });
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
      await page.getByRole("button", { name: "Download zip" }).click();
      await expect.poll(() => fake.servedFiles.length).toBeGreaterThan(1);
      const warned = await page.evaluate(() => {
        const event = new Event("beforeunload", { cancelable: true });
        window.dispatchEvent(event);
        return event.defaultPrevented;
      });
      expect(warned, "leaving mid-download asks first").toBe(true);
      await page.getByTestId("download-progress").getByRole("button", { name: "Cancel" }).click();
      await expect(page.getByTestId("download-progress")).toContainText("Cancelled.");
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
      await page.getByTestId("download-progress").getByRole("button", { name: "Cancel" }).click();
      await expect(page.getByTestId("download-progress")).toContainText("Save to the same folder to pick up where it stopped.");
      fake.fileDelayMs = 0;
      await page.getByRole("button", { name: "Dismiss" }).click();
      await save.click();
      await expect(page.getByTestId("download-progress")).toContainText(/\d+ were already in the folder\./, { timeout: 30_000 });
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

  test("a single file goes to the browser's own downloads", async ({ browser }) => {
    const { context, page } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      const row = page.locator("[data-file-row]").filter({ hasText: "loom.mp4" });
      const link = row.getByRole("link", { name: "Download loom.mp4" });
      await expect(link).toHaveAttribute("href", "/api/data/media/vid1/download");
      await expect(link).toHaveAttribute("download", "");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X3 multi-select", () => {
  test("two folders and a file download together in one zip", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops" });
    try {
      await page.getByRole("checkbox", { name: "Select Bagru 2026" }).check();
      await page.getByRole("checkbox", { name: "Select Sanganer" }).check();
      const selected = page.getByTestId("download-selection");
      await expect(selected).toContainText("2 folders");
      await expect(page.getByTestId("selection-size")).toHaveText(/^10 files · /);
      await selected.getByRole("button", { name: "Download selected" }).click();
      await expect(page.getByTestId("download-progress")).toContainText(/Saved 9 of 10 files/);
      expect(fake.calledWith("POST", "/data/manifest")[0].body).toMatchObject({ paths: ["workshops/bagru", "workshops/sanganer"] });
      const names = readZip(await savedZip(page)).map((entry) => entry.name);
      expect(names).toContain("Bagru 2026/photo.jpg");
      expect(names).toContain("Sanganer/a.jpg");
    } finally {
      await context.close();
    }
  });
});

test.describe("@qf:X6 tables", () => {
  test("search reaches every row on the server, and each sheet has a CSV", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("tab", { name: "Tables" }).click();
      await expect(page.getByRole("tab", { name: /Artisans/ })).toHaveAttribute("aria-selected", "true");
      await expect(page.getByRole("link", { name: "Artisans .csv" })).toHaveAttribute("href", /format=csv.*sheet=Artisans/);
      await page.getByRole("searchbox", { name: "Search every row of Artisans" }).fill("rare weaver");
      await expect(page.getByText("1 matching rows")).toBeVisible();
      await expect(page.getByRole("region", { name: "Artisans rows" })).toContainText("Kaithoon");
      expect(fake.calls.some((call) => call.path === "/data/report" && call.query.get("q") === "rare weaver")).toBe(true);
      await page.getByRole("tab", { name: /Artisans/ }).focus();
      await page.keyboard.press("ArrowRight");
      await expect(page.getByRole("tab", { name: /Products/ })).toBeFocused();
    } finally {
      await context.close();
    }
  });

  test("tables are remembered per folder, so going back costs no request", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/bagru" });
    try {
      await page.getByRole("tab", { name: "Tables" }).click();
      await expect(page.getByRole("tab", { name: /Artisans/ })).toBeVisible();
      const first = fake.calls.filter((call) => call.path === "/data/report" && !call.query.get("q")).length;
      await openFolder(page, "Sanganer");
      await expandTo(page, ["By workshop"]);
      await openFolder(page, "Bagru 2026");
      await expect(page.getByRole("tab", { name: /Artisans/ })).toBeVisible();
      const reports = fake.calls.filter((call) => call.path === "/data/report" && !call.query.get("q") && call.query.get("path") === "workshops/bagru").length;
      expect(reports).toBe(first);
    } finally {
      await context.close();
    }
  });

  test("a busy report says so and retries once by itself", async ({ browser }) => {
    const { context, page, fake } = await openExplorer(browser, { path: "workshops/sanganer" });
    try {
      await page.clock.install();
      fake.reportBusy = 1;
      await page.getByRole("tab", { name: "Tables" }).click();
      await expect(page.getByText("Busy, retrying in 60 s.")).toBeVisible();
      await page.clock.runFor(61_000);
      await expect(page.getByRole("tab", { name: /Artisans/ })).toBeVisible();
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
      await page.getByRole("button", { name: "Next" }).click();
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
      await page.locator("[data-transcript-data-toggle]").click();
      await page.locator('button[aria-label="Language"]').click();
      await expect(page.getByRole("option", { name: "Hindi · 3" })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
