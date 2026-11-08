import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type Locator, type Page } from "@playwright/test";

import { BENGAL_FOLDER, DataExplorerFake } from "./support/dataExplorerFake";

const SHOTS = process.env.E2E_SHOT_DIR ?? join("test-results", "data-explorer");
const OUT = join(SHOTS, "explorer-0016");
mkdirSync(OUT, { recursive: true });

type Size = { name: string; width: number; height: number; mobile: boolean };

const WIDE: Size = { name: "1440", width: 1440, height: 900, mobile: false };
const TABLET: Size = { name: "834", width: 834, height: 1112, mobile: true };
const PHONE: Size = { name: "390", width: 390, height: 844, mobile: true };
const SIZES: Size[] = [WIDE, TABLET, PHONE];
const PANE_SIZES: Size[] = [WIDE, { name: "1280", width: 1280, height: 860, mobile: false }, { name: "1024", width: 1024, height: 800, mobile: false }];

test.describe.configure({ mode: "parallel" });

async function open(browser: Browser, size: Size, query: string) {
  const context = await browser.newContext({
    viewport: { width: size.width, height: size.height },
    isMobile: size.mobile,
    hasTouch: size.mobile,
    colorScheme: "light",
    serviceWorkers: "block",
    reducedMotion: "reduce"
  });
  const fake = new DataExplorerFake();
  await fake.install(context, "light");
  const page = await context.newPage();
  await page.goto(`/data?${query}`);
  await expect(page.getByTestId("download-folder")).toBeVisible();
  await expect(page.getByTestId("explorer-filters")).toBeVisible();
  return { context, page, fake };
}

async function shot(page: Page, name: string, full = false) {
  await page.addStyleTag({ content: "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}" });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${name}.png`), fullPage: full });
}

async function showFilters(page: Page) {
  const toggle = page.getByTestId("filters-toggle");
  if (await toggle.isVisible()) {
    if ((await toggle.getAttribute("aria-expanded")) !== "true") await toggle.click();
  }
  await expect(page.locator("#explorer-filter-controls")).toBeVisible();
}

function facetTrigger(page: Page, label: string): Locator {
  return page.locator(`[data-facet]`).getByRole("button", { name: new RegExp(`^${label}\\.`) }).first();
}

async function pick(page: Page, label: string, option: RegExp, screenshot?: string) {
  await showFilters(page);
  await facetTrigger(page, label).click();
  const list = page.getByRole("listbox", { name: label });
  await expect(list).toBeVisible();
  if (screenshot) await shot(page, screenshot);
  await list.getByRole("option", { name: option }).first().click();
  const confirm = page.getByRole("button", { name: /^Confirm/ });
  if (await confirm.isVisible().catch(() => false)) await confirm.click();
  await page.keyboard.press("Escape").catch(() => undefined);
}

async function viewText(contents: Locator, name: string) {
  const row = contents.locator("li").filter({ hasText: name }).first();
  const more = row.getByRole("button", { name: `More for ${name}` });
  if (await more.isVisible().catch(() => false)) await more.click();
  await row.locator("button:visible").filter({ hasText: /^View$/ }).first().click();
}

function overlaps(a: { x: number; y: number; width: number; height: number }, b: { x: number; y: number; width: number; height: number }): boolean {
  return a.x < b.x + b.width - 0.5 && b.x < a.x + a.width - 0.5 && a.y < b.y + b.height - 0.5 && b.y < a.y + a.height - 0.5;
}

for (const size of SIZES) {
  test.describe(`@qf:X13 explorer filters at ${size.name}`, () => {
    test(`F1 F2 a craft picked in a searchable multi-select narrows the tree, its counts and the folder (${size.name})`, async ({ browser }) => {
      const { context, page, fake } = await open(browser, size, "path=workshops");
      try {
        const contents = page.getByTestId("folder-contents");
        await expect(contents).toContainText("Sanganer");
        await pick(page, "Craft", /Hand block printing/, `multiselect-open-${size.name}`);
        await expect(page).toHaveURL(/craftIds=c-block/);
        await expect.poll(() => fake.calls.some((call) => call.path === "/data/tree" && call.query.get("craftIds") === "c-block" && (call.query.get("path") ?? "") === "workshops")).toBe(true);
        await expect(contents).not.toContainText("Sanganer");
        await expect(contents).toContainText("Bagru 2026");
        await expect.poll(() => fake.calls.some((call) => call.path === "/data/stats" && call.query.get("craftIds") === "c-block")).toBe(true);
        await expect.poll(() => fake.calls.some((call) => call.path === "/data/filters" && call.query.get("craftIds") === "c-block")).toBe(true);
        await page.keyboard.press("Escape");
        await shot(page, `filter-craft-folder-${size.name}`, true);
        if (!size.mobile) {
          const tree = page.getByTestId("folder-tree");
          await expect(tree.getByRole("treeitem", { name: /Sanganer/ })).toHaveCount(0);
          await expect(page.getByTestId("explorer-tree-panel")).toContainText("hidden by filters");
          await shot(page, `filter-craft-tree-${size.name}`);
        }
      } finally {
        await context.close();
      }
    });

    test(`F1 the media-type filter reaches the folder, Tables and Transcripts, and the empty state names it (${size.name})`, async ({ browser }) => {
      const { context, page, fake } = await open(browser, size, "path=workshops/sanganer&mediaTypes=VIDEO");
      try {
        const empty = page.getByTestId("filtered-empty");
        await expect(empty).toBeVisible();
        await expect(empty).toContainText("Media type: Videos");
        await shot(page, `filter-empty-${size.name}`);
        await page.goto(`/data?path=workshops/bagru&mediaTypes=VIDEO&view=transcripts`);
        await expect.poll(() => fake.calls.some((call) => call.path === "/data/transcripts/list" && call.query.get("mediaTypes") === "VIDEO")).toBe(true);
        await page.goto(`/data?path=workshops/bagru&mediaTypes=VIDEO&view=tables`);
        await expect.poll(() => fake.calls.some((call) => (call.path === "/data/report" || call.path === "/data/report/sheets") && call.query.get("mediaTypes") === "VIDEO")).toBe(true);
        await page.goto(`/data?path=workshops/sanganer&mediaTypes=VIDEO`);
        await page.getByTestId("filtered-empty").getByRole("button", { name: "Clear filters" }).click();
        await expect(page).not.toHaveURL(/mediaTypes=/);
        await expect(page.getByTestId("folder-contents")).toContainText("a.jpg");
      } finally {
        await context.close();
      }
    });

    test(`F2 every filter is a searchable multi-select and the date is a calendar (${size.name})`, async ({ browser }) => {
      const { context, page } = await open(browser, size, "path=workshops");
      try {
        await showFilters(page);
        for (const label of ["Portal", "Workshop", "Design workshop", "Craft", "Artisan", "Media type", "Review status", "Recorded by", "Language"]) {
          await expect(facetTrigger(page, label)).toBeVisible();
        }
        await expect(page.locator("#explorer-filter-controls select")).toHaveCount(0);
        await expect(page.locator('#explorer-filter-controls input[type="date"]')).toHaveCount(0);
        await expect(page.locator('[data-facet="date"]')).toContainText("From");
        await expect(page.locator('[data-facet="date-to"]')).toContainText("To");
        await facetTrigger(page, "Artisan").click();
        const box = page.getByPlaceholder("Type to filter").last();
        await expect(box).toBeVisible();
        await box.fill("meera");
        await expect(page.getByRole("listbox", { name: "Artisan" }).getByRole("option")).toHaveCount(1);
        await shot(page, `multiselect-search-${size.name}`);
      } finally {
        await context.close();
      }
    });

    test(`F3 the design-workshop filter is a multi-select that narrows the folder (${size.name})`, async ({ browser }) => {
      const { context, page, fake } = await open(browser, size, `path=${BENGAL_FOLDER}`);
      try {
        await pick(page, "Design workshop", /Bolpur design lab/, `design-workshop-open-${size.name}`);
        await expect(page).toHaveURL(/designWorkshopIds=dw-bolpur/);
        await expect.poll(() => fake.calls.some((call) => call.path === "/data/tree" && call.query.get("designWorkshopIds") === "dw-bolpur")).toBe(true);
        await page.keyboard.press("Escape");
        await expect(page.getByTestId("folder-contents")).toContainText("kantha.jpg");
        await shot(page, `design-workshop-filter-${size.name}`);
      } finally {
        await context.close();
      }
    });

    test(`F4 Other portal lists the peer's rows with a portal chip and the cross-portal link (${size.name})`, async ({ browser }) => {
      const { context, page, fake } = await open(browser, size, "path=workshops/bagru&portals=this,other");
      try {
        const peer = page.getByTestId("peer-results").first();
        await expect(peer).toBeVisible();
        await expect(peer.getByTestId("portal-chip").first()).toHaveText("Field Repository");
        await expect(peer.getByRole("link", { name: "Rina Das", exact: true })).toHaveAttribute("href", "/remote/artisans/fr-a1");
        expect(fake.calls.some((call) => call.path === "/data/peer")).toBe(true);
        expect(fake.calls.filter((call) => call.path === "/data/tree").every((call) => !call.query.has("portals"))).toBe(true);
        await peer.scrollIntoViewIfNeeded();
        await shot(page, `other-portal-${size.name}`);
        await page.goto("/data?path=workshops/bagru&portals=other&browse=media");
        await expect(page.getByTestId("filtered-empty").first()).toContainText("This portal is filtered out");
        const browse = page.getByRole("region", { name: "Records by type" });
        await expect(browse.getByTestId("peer-results")).toBeVisible();
        await expect(browse.getByRole("link", { name: "kantha-stitch.mp4", exact: true })).toHaveAttribute("href", "/remote/media/fr-m1");
        await expect.poll(() => fake.calls.some((call) => call.path === "/data/peer" && call.query.get("type") === "media")).toBe(true);
        await browse.scrollIntoViewIfNeeded();
        await shot(page, `other-portal-browse-${size.name}`);
      } finally {
        await context.close();
      }
    });

    test(`F6 the Tables tab wraps long Bengali text in bounded columns with no cell scrollbars (${size.name})`, async ({ browser }) => {
      const { context, page } = await open(browser, size, `path=${BENGAL_FOLDER}&view=tables`);
      try {
        if (size.mobile && size.width < 640) {
          const card = page.getByRole("article").filter({ hasText: "Bolpur design lab" }).first();
          await expect(card).toBeVisible();
          const scrolling = await card.evaluate((node) => [...node.querySelectorAll("*")].filter((el) => ["auto", "scroll"].includes(getComputedStyle(el).overflowY) && el.scrollHeight > el.clientHeight + 1).length);
          expect(scrolling).toBe(0);
          await shot(page, `tables-bengali-${size.name}`);
          return;
        }
        const table = page.getByTestId("data-table");
        await expect(table).toBeVisible();
        await expect(table).toContainText("প্রধানমন্ত্রী");
        const report = await table.evaluate((node) => {
          const cells = [...node.querySelectorAll("td, th")] as HTMLElement[];
          const scrollers = [...node.querySelectorAll("*")].filter((el) => ["auto", "scroll"].includes(getComputedStyle(el).overflowY) || ["auto", "scroll"].includes(getComputedStyle(el).overflowX)).length;
          const clipped = cells.filter((cell) => cell.scrollWidth > cell.clientWidth + 1 || cell.scrollHeight > cell.clientHeight + 1).length;
          const widths = [...node.querySelectorAll("col")].map((col) => col.getBoundingClientRect().width);
          const rows = [...node.querySelectorAll("tbody tr")].map((row) => row.getBoundingClientRect().height);
          const boxes = cells.map((cell) => cell.getBoundingClientRect());
          let overlapping = 0;
          for (let i = 0; i < boxes.length; i += 1)
            for (let j = i + 1; j < boxes.length; j += 1) {
              const a = boxes[i];
              const b = boxes[j];
              if (a.left < b.right - 0.5 && b.left < a.right - 0.5 && a.top < b.bottom - 0.5 && b.top < a.bottom - 0.5) overlapping += 1;
            }
          return { scrollers, clipped, widths, rows, overlapping };
        });
        expect(report.scrollers).toBe(0);
        expect(report.clipped).toBe(0);
        expect(report.overlapping).toBe(0);
        for (const width of report.widths) {
          expect(width).toBeGreaterThanOrEqual(111);
          expect(width).toBeLessThanOrEqual(421);
        }
        expect(Math.max(...report.widths)).toBeGreaterThanOrEqual(400);
        expect(report.rows[1]).toBeGreaterThan(report.rows[2]);
        const region = page.getByTestId("data-table-scroll");
        const widths = await region.evaluate((node) => ({ scroll: node.scrollWidth, client: node.clientWidth }));
        if (widths.scroll > widths.client) {
          await region.evaluate((node) => node.scrollTo({ left: node.scrollWidth }));
          await expect.poll(() => region.evaluate((node) => node.scrollLeft)).toBeGreaterThan(0);
          await region.evaluate((node) => node.scrollTo({ left: 0 }));
        }
        await region.scrollIntoViewIfNeeded();
        await shot(page, `tables-bengali-${size.name}`);
      } finally {
        await context.close();
      }
    });

    test(`F7 a key: value text body reads as a Field / Value table, other text as paragraphs (${size.name})`, async ({ browser }) => {
      const { context, page } = await open(browser, size, `path=${BENGAL_FOLDER}`);
      try {
        const contents = page.getByTestId("folder-contents");
        await viewText(contents, "details.txt");
        const table = page.getByTestId("field-table").first();
        await expect(table).toBeVisible();
        await expect(table.getByRole("columnheader", { name: "Field" })).toBeVisible();
        await expect(table.getByRole("columnheader", { name: "Value" })).toBeVisible();
        await expect(table.getByRole("rowheader", { name: "Workshop id" })).toBeVisible();
        await expect(table.getByRole("rowheader", { name: "Scheme" })).toBeVisible();
        await expect(table).toContainText("প্রধানমন্ত্রী");
        const clipped = await table.evaluate((node) => [...node.querySelectorAll("td, th")].filter((cell) => cell.scrollWidth > cell.clientWidth + 1).length);
        expect(clipped).toBe(0);
        await table.scrollIntoViewIfNeeded();
        await shot(page, `details-table-${size.name}`);
        await viewText(contents, "notes.txt");
        const prose = page.getByTestId("text-paragraphs").first();
        await expect(prose.locator("p")).toHaveCount(2);
      } finally {
        await context.close();
      }
    });
  });
}

for (const size of PANE_SIZES) {
  test(`@qf:X13 F5 the folders pane never overlaps the content at its minimum and maximum (${size.name})`, async ({ browser }) => {
    const { context, page } = await open(browser, size, "path=workshops/bagru");
    try {
      const divider = page.getByRole("separator", { name: "Resize folders panel" });
      await expect(divider).toBeVisible();
      const panel = page.getByTestId("explorer-tree-panel");
      const content = page.getByTestId("explorer-content");
      const check = async (label: string) => {
        const a = await panel.boundingBox();
        const b = await content.boundingBox();
        const d = await divider.boundingBox();
        expect(a && b && d).toBeTruthy();
        expect(overlaps(a!, b!), `${label}: the pane and the content intersect`).toBe(false);
        expect(a!.x + a!.width).toBeLessThanOrEqual(b!.x + 0.5);
        expect(b!.width).toBeGreaterThan(200);
        const spill = await page.evaluate(() => {
          const pane = document.querySelector("[data-testid='explorer-tree-panel']")!.getBoundingClientRect();
          const area = document.querySelector("[data-testid='explorer-content']")!.getBoundingClientRect();
          const inPane = [...document.querySelectorAll("[data-testid='explorer-tree-panel'] *")].filter((el) => {
            const box = el.getBoundingClientRect();
            return box.width > 0 && box.right > pane.right + 0.5 && getComputedStyle(el).visibility !== "hidden";
          }).length;
          const inArea = [...document.querySelectorAll("[data-testid='explorer-content'] *")].filter((el) => {
            const box = el.getBoundingClientRect();
            return box.width > 0 && box.left < area.left - 0.5;
          }).length;
          return { inPane, inArea };
        });
        expect(spill.inArea, `${label}: content reaches under the pane`).toBe(0);
        const paneBox = a!;
        const visibleSpill = await page.evaluate((right) => {
          const pane = document.querySelector("[data-testid='explorer-tree-panel']") as HTMLElement;
          return pane.scrollWidth <= pane.clientWidth + 1 && right > 0;
        }, paneBox.x + paneBox.width);
        expect(visibleSpill, `${label}: the pane scrolls sideways`).toBe(true);
      };
      await divider.focus();
      await page.keyboard.press("Home");
      await expect(divider).toHaveAttribute("aria-valuenow", "240");
      await check("minimum");
      await shot(page, `pane-min-${size.name}`);
      const handle = await divider.boundingBox();
      await page.mouse.move(handle!.x + handle!.width / 2, handle!.y + 120);
      await page.mouse.down();
      await page.mouse.move(handle!.x + 3000, handle!.y + 120, { steps: 6 });
      await page.mouse.up();
      const max = await divider.getAttribute("aria-valuemax");
      await expect(divider).toHaveAttribute("aria-valuenow", String(max));
      await check("maximum");
      await shot(page, `pane-max-${size.name}`);
      await divider.focus();
      await page.keyboard.press("Home");
      const back = await divider.boundingBox();
      await page.mouse.move(back!.x + back!.width / 2, back!.y + 120);
      await page.mouse.down();
      await page.mouse.move(back!.x - 3000, back!.y + 120, { steps: 6 });
      await page.mouse.up();
      await expect(divider).toHaveAttribute("aria-valuenow", "240");
      await check("dragged to the minimum");
      await page.goto("/data?path=workshops/bagru&view=tables");
      await page.getByRole("button", { name: /Folders/ }).first().click();
      await expect(page.getByRole("separator", { name: "Resize folders panel" })).toBeVisible();
      await check("tables with folders");
    } finally {
      await context.close();
    }
  });
}

test("@qf:X13 a saved view brings its filters back, from the dialog and from its link", async ({ browser }) => {
  const { context, page, fake } = await open(browser, WIDE, "path=workshops&craftIds=c-block&mediaTypes=VIDEO&portals=this,other");
  try {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.getByRole("button", { name: "Saved views" }).click();
    const dialog = page.getByRole("dialog", { name: "Saved views" });
    await dialog.getByLabel("Save this screen as").fill("Block printing videos");
    await dialog.getByRole("button", { name: "Save current view" }).click();
    await expect.poll(() => fake.calledWith("POST", "/data/views").length).toBe(1);
    const posted = fake.calledWith("POST", "/data/views")[0].body as { path: string; state: { filters: Record<string, string> } };
    expect(posted.path).toBe("workshops");
    expect(posted.state.filters).toMatchObject({ craftIds: "c-block", mediaTypes: "VIDEO", portals: "this,other" });
    await page.keyboard.press("Escape");
    await page.goto("/data?path=workshops/sanganer");
    await expect(page.getByTestId("download-folder")).toBeVisible();
    await expect(page).not.toHaveURL(/craftIds=/);
    await page.getByRole("button", { name: "Saved views" }).click();
    const again = page.getByRole("dialog", { name: "Saved views" });
    const saved = again.getByRole("listitem").filter({ hasText: "Block printing videos" });
    await saved.getByRole("button", { name: /^Block printing videos/ }).click();
    await saved.getByRole("button", { name: "Open", exact: true }).click();
    await expect(page).toHaveURL(/craftIds=c-block/);
    await expect(page).toHaveURL(/mediaTypes=VIDEO/);
    await expect(page).toHaveURL(/portals=this%2Cother/);
    await expect(page).toHaveURL(/path=workshops(&|$)/);
    await expect.poll(() => fake.calls.some((call) => call.path === "/data/tree" && call.query.get("craftIds") === "c-block" && call.query.get("mediaTypes") === "VIDEO")).toBe(true);
    await expect(page.getByTestId("explorer-filters")).toContainText("Craft: Hand block printing");
    await page.goto("/data?viewId=v1");
    await expect(page).toHaveURL(/craftIds=c-block/);
    await expect(page).toHaveURL(/mediaTypes=VIDEO/);
    await expect(page).not.toHaveURL(/viewId=/);
  } finally {
    await context.close();
  }
});

test("@qf:X13 search has no filter row of its own and sends the explorer filters", async ({ browser }) => {
  const { context, page, fake } = await open(browser, WIDE, "path=workshops&craftIds=c-block&workshopIds=workshops/bagru&dateFrom=2026-10-01&dateTo=2026-10-31");
  try {
    await expect(page.locator("#data-search-filters")).toBeHidden();
    await expect(page.getByRole("radiogroup", { name: "Where" })).toHaveCount(0);
    const box = page.getByRole("searchbox").first();
    await box.fill("kamla");
    await box.press("Enter");
    await expect
      .poll(() => fake.calls.some((call) => call.path.startsWith("/search") && call.query.get("q") === "kamla" && (call.query.get("craftId") === "c-block" || call.query.get("craftIds") === "c-block")))
      .toBe(true);
    const asked = fake.calls.filter((call) => call.path === "/search/unified" && call.query.get("q") === "kamla");
    if (asked.length) {
      expect(asked[0].query.get("craftId")).toBe("c-block");
      expect(asked[0].query.get("workshopIds")).toBe("workshops/bagru");
      expect(asked[0].query.get("dateFrom")).toBeTruthy();
      expect(asked[0].query.get("dateTo")).toBeTruthy();
    }
  } finally {
    await context.close();
  }
});
