import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";

import { SORTED_FOLDER } from "./support/dataExplorerFake";
import { GIRIRAJ, WORKSHOP } from "./support/designFidelityFake";
import { DesignFidelityWebFake, KILN_ID, KILN_NAME } from "./support/designFidelityWebFake";

const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");

type Theme = "light" | "dark";
type Size = { width: number; height: number };

const DESKTOP: Size = { width: 1440, height: 900 };
const TABLET: Size = { width: 834, height: 1112 };
const PHONE: Size = { width: 390, height: 844 };

test.describe.configure({ mode: "parallel" });

function folderUrl(path: string, extra: Record<string, string> = {}): string {
  return `/data?${new URLSearchParams({ path, ...extra }).toString()}`;
}

async function open(browser: Browser, size: Size, theme: Theme = "light", scale?: number): Promise<{ context: BrowserContext; page: Page; fake: DesignFidelityWebFake }> {
  const mobile = size.width < 900;
  const context = await browser.newContext({
    viewport: size,
    deviceScaleFactor: scale ?? (size.width <= 600 ? 2 : 1),
    isMobile: mobile,
    hasTouch: mobile,
    colorScheme: theme,
    serviceWorkers: "block",
    acceptDownloads: true,
    reducedMotion: "reduce"
  });
  const fake = new DesignFidelityWebFake();
  await fake.install(context, theme);
  const page = await context.newPage();
  return { context, page, fake };
}

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.addStyleTag({ content: "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}" });
  await page.waitForTimeout(300);
}

function shotPath(board: string, variant?: string): string {
  const dir = join(OUT, board);
  mkdirSync(dir, { recursive: true });
  return join(dir, `impl${variant ? `-${variant}` : ""}.png`);
}

async function shoot(page: Page, board: string, variant?: string) {
  await settle(page);
  await page.screenshot({ path: shotPath(board, variant) });
}

async function shootAround(page: Page, board: string, variant: string | undefined, targets: Locator[], pad = { x: 40, top: 32, bottom: 32 }) {
  await settle(page);
  const boxes: { x: number; y: number; width: number; height: number }[] = [];
  for (const target of targets) {
    if (!(await target.count())) continue;
    const box = await target.first().evaluate((node) => {
      const rect = node.getBoundingClientRect();
      return { x: rect.left + window.scrollX, y: rect.top + window.scrollY, width: rect.width, height: rect.height };
    });
    boxes.push(box);
  }
  const width = await page.evaluate(() => document.documentElement.clientWidth);
  const top = Math.min(...boxes.map((box) => box.y));
  const bottom = Math.max(...boxes.map((box) => box.y + box.height));
  const left = Math.min(...boxes.map((box) => box.x));
  const right = Math.max(...boxes.map((box) => box.x + box.width));
  const x = Math.max(0, left - pad.x);
  const y = Math.max(0, top - pad.top);
  await page.screenshot({ path: shotPath(board, variant), clip: { x, y, width: Math.min(width - x, right - left + pad.x * 2), height: bottom - top + pad.top + pad.bottom }, fullPage: true });
}

async function overflowReport(page: Page, label: string) {
  const wide = await page.evaluate(() => {
    const limit = document.documentElement.clientWidth + 1;
    const out: string[] = [];
    for (const node of Array.from(document.querySelectorAll("body *"))) {
      const rect = node.getBoundingClientRect();
      if (rect.width > 0 && rect.right > limit) {
        const parentWide = node.parentElement ? node.parentElement.getBoundingClientRect().right > limit : false;
        if (!parentWide || out.length < 3) out.push(`${node.tagName.toLowerCase()}.${String((node as HTMLElement).className).slice(0, 120)} right=${Math.round(rect.right)}`);
      }
      if (out.length > 12) break;
    }
    return { scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth, out };
  });
  console.log(`[overflow ${label}] scrollWidth=${wide.scroll} clientWidth=${wide.client} | ${wide.out.join(" | ")}`);
}

async function folderReady(page: Page) {
  await expect(page.getByTestId("download-folder")).toBeVisible();
  await expect(page.getByTestId("folder-size")).not.toContainText("Checking size");
}

async function startWorkshopZip(page: Page, fake: DesignFidelityWebFake) {
  fake.workshopZip = true;
  fake.holdFrom = 613;
  await page.goto(folderUrl(WORKSHOP));
  await folderReady(page);
  await page.getByTestId("download-folder").getByRole("button", { name: /^Download( workshop| folder|$)/ }).click();
}

async function zipHeld(page: Page) {
  const tray = page.getByTestId("downloads-tray");
  await expect(tray).toContainText(/(file|File) [4-9]\d\d of 926/, { timeout: 60_000 });
  let last = "";
  await expect
    .poll(
      async () => {
        const now = (await tray.textContent()) ?? "";
        const same = now === last;
        last = now;
        return same;
      },
      { timeout: 30_000, intervals: [1500] }
    )
    .toBe(true);
}

async function toGiriraj(page: Page) {
  const open = page.getByTestId("workshop-overview").getByRole("row", { name: /Giriraj Prasad Chhipa/ }).getByRole("button", { name: "Open" });
  await open.scrollIntoViewIfNeeded();
  await open.evaluate((node) => (node as HTMLButtonElement).click());
  await expect(page.getByTestId("download-folder").getByRole("heading", { name: "Giriraj Prasad Chhipa" })).toBeVisible();
  await folderReady(page);
}

function row(page: Page, name: string): Locator {
  return page.locator("[data-file-row]").filter({ hasText: name }).first();
}

async function check(page: Page, file: string) {
  await row(page, file.replace(/\.[^.]+$/, "")).getByRole("checkbox", { name: `Select ${file}` }).check();
}

async function main(browser: Browser, theme: Theme) {
  const { context, page, fake } = await open(browser, { width: 1440, height: 1240 }, theme);
  try {
    await startWorkshopZip(page, fake);
    await toGiriraj(page);
    await check(page, "Interview_Dabu-mud-resist_Take-1.mp4");
    await check(page, "Block-carving_close-up_03.jpg");
    await expect(page.getByTestId("selection-size")).toHaveText(/2 selected · 704 MB/);
    await zipHeld(page);
    await page.getByRole("searchbox").first().focus();
    await page.evaluate(() => window.scrollTo(0, 0));
    await shoot(page, "Main", theme === "dark" ? "dark" : undefined);
  } finally {
    fake.release();
    await context.close();
  }
}

test("impl Main", async ({ browser }) => {
  test.setTimeout(150_000);
  await main(browser, "light");
});

test("impl Main · dark", async ({ browser }) => {
  test.setTimeout(150_000);
  await main(browser, "dark");
});

for (const theme of ["light", "dark"] as const) {
  test(`impl WebTablet${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, TABLET, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      await check(page, "Interview_Dabu-mud-resist_Take-1.mp4");
      await check(page, "Block-carving_close-up_03.jpg");
      await expect(page.getByTestId("selection-size")).toHaveText(/2 selected/);
      await page.evaluate(() => window.scrollTo(0, 0));
      await shoot(page, "WebTablet", theme === "dark" ? "dark" : undefined);
    } finally {
      await context.close();
    }
  });

  test(`impl WebPhone${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    test.setTimeout(150_000);
    const { context, page, fake } = await open(browser, PHONE, theme);
    try {
      await startWorkshopZip(page, fake);
      await toGiriraj(page);
      await zipHeld(page);
      await page.evaluate(() => window.scrollTo(0, 0));
      await shoot(page, "WebPhone", theme === "dark" ? "dark" : undefined);
    } finally {
      fake.release();
      await context.close();
    }
  });

  test(`impl WebTranscripts${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 820 }, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ, { view: "transcripts" }));
      const tab = page.getByTestId("transcripts-tab");
      await expect(tab.locator("[data-transcript-row]").first()).toBeVisible();
      await expect(tab.locator("[data-state=working]")).toHaveCount(1);
      await shootAround(page, "WebTranscripts", theme === "dark" ? "dark" : undefined, [page.getByRole("region", { name: "Contents" })], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl WorkshopOverview${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, DESKTOP, theme);
    try {
      await page.goto(folderUrl(WORKSHOP));
      await folderReady(page);
      const overview = page.getByTestId("workshop-overview");
      await expect(overview.locator("tbody tr")).toHaveCount(4);
      await expect(overview).toContainText("+38", { timeout: 10_000 });
      await shootAround(page, "WorkshopOverview", theme === "dark" ? "dark" : undefined, [page.getByTestId("download-folder"), overview], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl WebTables${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 1000 }, theme);
    try {
      await page.route("**/api/data/report?*", async (route) => {
        const url = new URL(route.request().url());
        if (url.searchParams.get("format") === "xlsx") return;
        return route.fallback();
      });
      await page.goto(folderUrl(WORKSHOP, { view: "tables", sheet: "Tools", q: "indigo" }));
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByText("3 rows match “indigo”")).toBeVisible();
      await tables.getByRole("button", { name: "Full report (.xlsx)" }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      await expect(tables.getByText("Full report (.xlsx) is being prepared")).toBeVisible();
      await page.getByTestId("downloads-tray").getByRole("button", { name: "Minimise downloads" }).click({ timeout: 5_000 }).catch(() => undefined);
      await shootAround(page, "WebTables", theme === "dark" ? "dark" : undefined, [page.getByRole("region", { name: "Contents" })], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl WebTables error${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 1000 }, theme);
    try {
      await page.route("**/api/data/report?*", async (route) => {
        const url = new URL(route.request().url());
        if (url.searchParams.get("format") === "xlsx") return route.fulfill({ status: 500, contentType: "application/json", headers: { "retry-after": "20" }, body: JSON.stringify({ detail: "The report couldn't be built." }) });
        return route.fallback();
      });
      await page.goto(folderUrl(WORKSHOP, { view: "tables", sheet: "Tools", q: "indigo" }));
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByText("3 rows match “indigo”")).toBeVisible();
      await tables.getByRole("button", { name: "Full report (.xlsx)" }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      await expect(tables.getByRole("alert").filter({ hasText: "Nothing was saved." })).toBeVisible();
      const minimise = page.getByTestId("downloads-tray").getByRole("button", { name: "Minimise downloads" });
      if (await minimise.isVisible()) await minimise.click({ timeout: 5_000 }).catch(() => undefined);
      await shootAround(page, "WebTables", theme === "dark" ? "error-dark" : "error", [page.getByRole("region", { name: "Contents" })], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl PhoneTables${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, PHONE, theme);
    try {
      await page.goto(folderUrl(WORKSHOP, { view: "tables", sheet: "Tools", q: "indigo" }));
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByText("Row 14").or(tables.getByText("Row 1", { exact: true }))).toBeVisible();
      await overflowReport(page, "PhoneTables");
      await shoot(page, "PhoneTables", theme === "dark" ? "phone-web-dark" : "phone-web");
    } finally {
      await context.close();
    }
  });

  test(`impl AndroidSelect${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, PHONE, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      const first = row(page, "Interview_Dabu-mud-resist_Take-1");
      await first.dispatchEvent("pointerdown", { pointerType: "touch", isPrimary: true });
      await page.waitForTimeout(650);
      await first.dispatchEvent("pointerup", { pointerType: "touch", isPrimary: true });
      await expect(page.getByRole("region", { name: "Selecting" })).toBeVisible();
      await check(page, "Block-carving_close-up_03.jpg");
      await check(page, "Section-D_Materials_Group-of-3.m4a");
      await expect(page.getByRole("region", { name: "Selecting" })).toContainText("3 selected");
      await page.getByRole("group", { name: "Show file types" }).evaluate((node) => {
        const top = node.getBoundingClientRect().top + window.scrollY - 76;
        window.scrollTo(0, top);
      });
      await overflowReport(page, "AndroidSelect");
      await shoot(page, "AndroidSelect", theme === "dark" ? "phone-web-dark" : "phone-web");
    } finally {
      await context.close();
    }
  });
}

for (const theme of ["light", "dark"] as const) {
  test(`impl WebTrayQueue${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    test.setTimeout(240_000);
    const { context, page, fake } = await open(browser, DESKTOP, theme, 2);
    try {
      await page.route(`**/api/data/media/${KILN_ID}/download`, (route) => route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "This file is no longer on the server." }) }));
      const tray = page.getByTestId("downloads-tray");
      await test.step("a file fails part-way and stops retrying", async () => {
        await page.goto(folderUrl(GIRIRAJ));
        await folderReady(page);
        const kiln = row(page, "Kiln-firing_wide_07");
        await kiln.getByRole("button", { name: `Download ${KILN_NAME}` }).click();
        const kilnItem = tray.getByTestId("tray-item").filter({ hasText: KILN_NAME });
        await expect(kilnItem).toHaveAttribute("data-status", "failed", { timeout: 30_000 });
        await expect(kilnItem.getByRole("button", { name: "Retry" })).toBeVisible({ timeout: 30_000 });
      });
      await test.step("a report is saved", async () => {
        await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
        await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
        await expect(tray.getByTestId("tray-item").filter({ hasText: "Report (.xlsx)" }).first()).toHaveAttribute("data-status", "saved", { timeout: 20_000 });
      });
      await test.step("the artisan folder is saved", async () => {
        await page.getByTestId("download-folder").getByRole("button", { name: /^Download/ }).click();
        await expect(tray.getByTestId("tray-item").filter({ hasText: "Giriraj Prasad Chhipa" }).first()).toHaveAttribute("data-status", "saved", { timeout: 30_000 });
      });
      await test.step("the workshop runs and a report waits", async () => {
        fake.workshopZip = true;
        fake.holdFrom = 613;
        await page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("button", { name: "3rd Craft Toolkit Workshop" }).click();
        await expect(page.getByTestId("download-folder").getByRole("heading", { name: "3rd Craft Toolkit Workshop" })).toBeVisible();
        await folderReady(page);
        await page.getByTestId("download-folder").getByRole("button", { name: /^Download/ }).click();
        await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
        await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
        await zipHeld(page);
        await expect(tray.getByTestId("tray-item").filter({ hasText: "Starts when the one above finishes" })).toBeVisible();
      });
      await settle(page);
      const box = (await tray.boundingBox())!;
      await page.screenshot({ path: shotPath("WebTrayQueue", theme === "dark" ? "dark" : undefined), clip: { x: box.x + box.width + 24 - 560, y: Math.max(0, box.y + box.height + 24 - 720), width: 560, height: Math.min(720, box.y + box.height + 24) } });
    } finally {
      fake.release();
      await context.close();
    }
  });
}

for (const theme of ["light", "dark"] as const) {
  const suffix = theme === "dark" ? "-dark" : "";

  test(`impl States${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    test.setTimeout(150_000);
    const { context, page, fake } = await open(browser, DESKTOP, theme);
    try {
      const contents = () => page.getByRole("region", { name: "Contents" });
      await page.route(/\/api\/data\/tree\?.*manisha/, () => undefined);
      await page.goto(folderUrl(`${WORKSHOP}/manisha`));
      await expect(page.getByRole("status", { name: "Loading" })).toBeVisible();
      await shootAround(page, "States", `loading${suffix}`, [contents()], { x: 0, top: 0, bottom: 0 });

      await page.goto(folderUrl(`${WORKSHOP}/deepak`));
      await expect(page.getByRole("heading", { name: "Nothing recorded here yet" })).toBeVisible();
      await shootAround(page, "States", `empty${suffix}`, [contents()], { x: 0, top: 0, bottom: 0 });

      fake.treeFailures.set(`${WORKSHOP}/nandkishor`, 50);
      await page.goto(folderUrl(`${WORKSHOP}/nandkishor`));
      await expect(page.getByRole("alert").filter({ hasText: "This folder didn't load" })).toBeVisible();
      await shootAround(page, "States", `error${suffix}`, [contents()], { x: 0, top: 0, bottom: 0 });

      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      await context.setOffline(true);
      await expect(page.getByText("You're offline.")).toBeVisible();
      await shootAround(page, "States", `offline${suffix}`, [page.getByText("You're offline.").locator("xpath=.."), page.getByTestId("download-folder")], { x: 0, top: 0, bottom: 0 });
      await context.setOffline(false);

      await page.getByRole("searchbox", { name: "Search all data" }).fill("indgo");
      const results = page.getByTestId("search-results");
      await expect(results.getByRole("heading", { name: /No matches/ })).toBeVisible();
      await shootAround(page, "States", `noresults${suffix}`, [results], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl CommandPalette${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 760 }, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      await page.getByTestId("download-folder").getByRole("heading").first().click();
      await page.mouse.move(4, 700);
      await page.keyboard.press("Control+k");
      const palette = page.getByRole("dialog", { name: "Quick jump" });
      await palette.getByRole("combobox", { name: "Jump to anything" }).fill("giri");
      await expect(palette.getByRole("option", { name: /Giridhar Soni/ })).toBeVisible();
      await shoot(page, "CommandPalette", theme === "dark" ? "dark" : undefined);
      await page.keyboard.press("Escape");
      await expect(palette).toHaveCount(0);
      await page.getByTestId("download-folder").getByRole("heading").first().click();
      await page.keyboard.press("?");
      await expect(page.getByRole("dialog", { name: /Shortcuts/ })).toBeVisible();
      await shoot(page, "CommandPalette", `shortcuts${suffix}`);
    } finally {
      await context.close();
    }
  });

  test(`impl ReportOptions${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 860 }, theme);
    try {
      await context.grantPermissions(["clipboard-read", "clipboard-write"]);
      await page.goto(folderUrl(GIRIRAJ, { view: "tables", q: "indigo" }));
      await folderReady(page);
      await page.getByTestId("download-folder").getByRole("button", { name: "Report (.xlsx)" }).click();
      await expect(page.getByRole("dialog", { name: "Report (.xlsx)" })).toBeVisible();
      await page.waitForTimeout(800);
      await shoot(page, "ReportOptions", theme === "dark" ? "dark" : undefined);
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "Saved views" }).click();
      const views = page.getByRole("dialog", { name: "Saved views" });
      await expect(views.getByRole("list", { name: "Saved views" })).toBeVisible();
      await views.getByRole("button", { name: "Save current view" }).click();
      await expect(views.getByRole("status").filter({ hasText: "Link copied" })).toBeVisible();
      await shoot(page, "ReportOptions", `saved-views${suffix}`);
    } finally {
      await context.close();
    }
  });

  test(`impl WebSearch${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, DESKTOP, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      await page.getByRole("searchbox", { name: "Search all data" }).fill("indigo mud resist");
      const results = page.getByTestId("search-results");
      await expect(results.getByText(/Section D · Materials/).first()).toBeVisible();
      await shootAround(page, "WebSearch", theme === "dark" ? "dark" : undefined, [page.locator("[data-explorer-search]")], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl SearchBoth${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, PHONE, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ, { portals: "this,other" }));
      await folderReady(page);
      await page.getByRole("searchbox", { name: "Search all data" }).fill("indigo vat");
      const results = page.getByTestId("search-results");
      await expect(results).toBeVisible();
      await expect(results.getByText(/Bagru dyers/)).toBeVisible();
      await page.locator("[data-explorer-search]").evaluate((node) => window.scrollTo(0, node.getBoundingClientRect().top + window.scrollY - 16));
      await shoot(page, "SearchBoth", theme === "dark" ? "phone-web-dark" : "phone-web");
    } finally {
      await context.close();
    }
  });
}

for (const theme of ["light", "dark"] as const) {
  for (const [label, size] of [["desktop", DESKTOP], ["tablet", TABLET], ["phone", PHONE]] as const) {
    test(`impl WebSort ${label}${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
      const { context, page } = await open(browser, size, theme);
      try {
        await page.goto(folderUrl(SORTED_FOLDER));
        await expect(page.getByTestId("download-folder")).toBeVisible();
        await page.getByRole("button", { name: /^Sort folder:/ }).click();
        const panel = page.getByRole("dialog", { name: "Sort folder" });
        await panel.getByRole("radio", { name: "Date recorded" }).click();
        await expect(panel.getByRole("radio", { name: "Newest" })).toHaveAttribute("aria-checked", "true");
        if (label === "phone") await shoot(page, "WebSort", `${label}${theme === "dark" ? "-dark" : ""}`);
        else await shootAround(page, "WebSort", `${label}${theme === "dark" ? "-dark" : ""}`, [page.getByRole("tablist", { name: "Contents" }), panel], { x: 24, top: 24, bottom: 24 });
      } finally {
        await context.close();
      }
    });
  }
}
