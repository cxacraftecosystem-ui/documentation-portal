import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { chromium, expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import tailwind from "@tailwindcss/postcss";
import { Users } from "lucide-react";
import postcss from "postcss";
import { createElement, Fragment, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { DashboardCard } from "@/components/DashboardCard";
import { ButtonRow, buttonCount } from "@/components/ui/ButtonRow";

/**
 * THE BUTTON-ROW RULE, owner ruling 2026-10-17: one button centred, two on one line, three or more
 * stacked on a phone and pills from `sm` up. It is decided once, in `components/ui/ButtonRow.tsx`,
 * and the shared hosts route through it so no screen hand-styles its own row. There is no React
 * renderer in devDependencies, so the count is tested as a function and the layout as source.
 */
const read = (...parts: string[]) => readFileSync(join(process.cwd(), ...parts), "utf8").replace(/\r\n/g, "\n");
const ROW = read("components", "ui", "ButtonRow.tsx");

test("the count unwraps fragments and ignores what renders nothing", () => {
  const button = (key: string) => createElement("button", { key }, key);
  expect(buttonCount([button("a")])).toBe(1);
  expect(buttonCount(createElement(Fragment, null, button("a"), button("b")))).toBe(2);
  expect(buttonCount([button("a"), null, false, undefined, button("b")])).toBe(2);
  expect(buttonCount([button("a"), button("b")], button("delete"))).toBe(3);
  expect(buttonCount(null)).toBe(0);
});

test("one is centred whatever the alignment, two share a line, three or more stack then become pills", () => {
  expect(ROW).toContain('data-button-row="1" className={`flex justify-center');
  expect(ROW).toContain("grid grid-cols-2 gap-2");
  expect(ROW).toContain("max-sm:[&>*]:whitespace-normal");
  expect(ROW).toContain("flex flex-col gap-2");
  expect(ROW).toContain("sm:[&>*]:rounded-full");
});

test("the shared hosts render through it rather than a flex div of their own", () => {
  expect(read("components", "PageHeader.tsx")).toContain('<ButtonRow align="end">{actions}</ButtonRow>');
  expect(read("components", "RowActions.tsx")).toContain('<ButtonRow align="end" size="sm" destructive={destructive}>');
  expect(read("components", "dialogs", "FieldDialog.tsx")).toContain(
    '<ButtonRow align="end" className="mt-5">{footer}</ButtonRow>'
  );
  expect(read("components", "dialogs", "FieldDialog.tsx")).not.toContain('"mt-5 flex flex-wrap justify-end gap-2"');
});

/*
 * CARD FOOTERS FILL THE CARD, owner ruling 2026-10-20. `fill` widens the click area of a card's one
 * or two buttons to the card's content edges at every width; the height is untouched.
 */
const html = (node: ReactNode) => renderToStaticMarkup(createElement(Fragment, null, node));
const btn = (label: string) =>
  createElement("button", { key: label, type: "button", className: "field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11", "data-b": label }, label);

test("fill: one button spans the row and two keep one grid at every width, with no sm switch", () => {
  const one = html(createElement(ButtonRow, { size: "sm", fill: true }, btn("Update")));
  expect(one).toContain('data-button-row="1"');
  expect(one).toContain("[&amp;&gt;*]:w-full");
  expect(one).not.toContain("justify-center");

  const two = html(createElement(ButtonRow, { size: "sm", fill: true }, btn("New"), btn("Update")));
  expect(two).toContain('data-button-row="2"');
  expect(two).toContain("grid grid-cols-2 gap-2");
  expect(two).toContain("[&amp;&gt;*]:min-w-0");
  expect(two).not.toContain("sm:flex");
  expect(two).not.toContain("sm:[&amp;&gt;*]:w-auto");

  const many = html(createElement(ButtonRow, { size: "sm", fill: true }, btn("A"), btn("B"), btn("C")));
  expect(many).toBe(html(createElement(ButtonRow, { size: "sm" }, btn("A"), btn("B"), btn("C"))));
});

test("without fill nothing changes", () => {
  expect(html(createElement(ButtonRow, null, btn("A")))).toContain('class="flex justify-center"');
  const two = html(createElement(ButtonRow, { align: "end" }, btn("A"), btn("B")));
  expect(two).toContain("sm:flex sm:flex-wrap sm:[&amp;&gt;*]:w-auto sm:justify-end");
  expect(two).not.toContain("data-fill");
});

test("the card footers ask for fill; the rows that are not cards do not", () => {
  expect(read("components", "DashboardCard.tsx")).toContain('<ButtonRow size="sm" fill className="mt-auto pt-1">');
  expect(read("app", "(protected)", "sent-back", "page.tsx")).toContain('<ButtonRow size="sm" fill>');
  expect(read("components", "questionnaire", "InboxList.tsx")).toContain('<ButtonRow size="sm" fill>');
  const review = read("app", "(protected)", "review", "page.tsx");
  expect(review).toContain('<ButtonRow size="sm" fill={fill} className="mt-2">');
  expect(review).toContain("{actionPanel(item, activeAction, true)}");
  // The wide table row's panel and the folder browser's empty state span the page, not a card.
  expect(review).toContain("{actionPanel(item, activeAction)}");
  expect(read("app", "(protected)", "data", "page.tsx")).not.toContain("<ButtonRow size=\"sm\" fill");
});

/*
 * GEOMETRY, measured in a real browser against the app's own stylesheet, compiled here from
 * app/globals.css so no build or server is needed. The unit job installs no Playwright browser, so
 * this launches whichever Chromium the machine has (Playwright's, else Chrome, else Edge) and skips
 * with that reason only when there is none.
 */
let browser: Browser | null = null;
let css = "";

async function launch(): Promise<Browser | null> {
  for (const options of [{}, { channel: "chrome" }, { channel: "msedge" }]) {
    try {
      return await chromium.launch(options);
    } catch {
      /* try the next one */
    }
  }
  return null;
}

const card = (row: ReactNode) => html(createElement("div", { className: "panel p-4", "data-card": "" }, row));
const NO_BROWSER = "No Chromium, Chrome or Edge on this machine to measure in.";

test.describe("measured", () => {
  test.beforeAll(async () => {
    browser = await launch();
    if (!browser) return;
    const from = join(process.cwd(), "app", "globals.css");
    css = (await postcss([tailwind()]).process(readFileSync(from, "utf8"), { from })).css;
  });
  test.afterAll(async () => {
    await browser?.close();
  });

  async function open(width: number, coarse: boolean, body: string): Promise<{ context: BrowserContext; page: Page }> {
    const context = await browser!.newContext({ viewport: { width, height: 900 }, hasTouch: coarse, isMobile: coarse });
    const page = await context.newPage();
    await page.setContent(
      `<!doctype html><html data-theme="light"><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>${css}</style></head>` +
        `<body class="bg-surface-50"><div class="p-4">${body}</div></body></html>`
    );
    return { context, page };
  }

  /** The card's content box, inside its padding and border, and each button's box. */
  async function measure(page: Page, selector: string) {
    return page.locator(selector).evaluate((el) => {
      const r = el.getBoundingClientRect();
      const st = getComputedStyle(el);
      const content = {
        left: r.left + parseFloat(st.paddingLeft) + parseFloat(st.borderLeftWidth),
        right: r.right - parseFloat(st.paddingRight) - parseFloat(st.borderRightWidth)
      };
      const buttons = [...el.querySelectorAll("[data-button-row] > *")].map((b) => {
        const box = b.getBoundingClientRect();
        return { left: box.left, right: box.right, width: box.width, height: box.height, top: box.top };
      });
      return { content, buttons };
    });
  }

  for (const width of [360, 768, 1280]) {
    for (const coarse of [false, true]) {
      test(`fill at ${width}px, ${coarse ? "coarse" : "fine"} pointer: the buttons reach the card's content edges`, async () => {
        test.skip(!browser, NO_BROWSER);
        const height = coarse ? 44 : 36;
        const { context, page } = await open(
          width,
          coarse,
          `<div id="one">${card(createElement(ButtonRow, { size: "sm", fill: true }, btn("Update")))}</div>` +
            `<div id="two" class="mt-4">${card(createElement(ButtonRow, { size: "sm", fill: true }, btn("New"), btn("Update")))}</div>`
        );
        const one = await measure(page, "#one [data-card]");
        expect(one.buttons).toHaveLength(1);
        expect(one.buttons[0].left).toBeCloseTo(one.content.left, 0);
        expect(one.buttons[0].right).toBeCloseTo(one.content.right, 0);
        expect(one.buttons[0].height).toBe(height);

        const two = await measure(page, "#two [data-card]");
        expect(two.buttons).toHaveLength(2);
        const [left, right] = two.buttons;
        expect(left.top).toBe(right.top);
        expect(left.left).toBeCloseTo(two.content.left, 0);
        expect(right.right).toBeCloseTo(two.content.right, 0);
        expect(right.left - left.right).toBeCloseTo(8, 0);
        expect(left.width).toBeCloseTo(right.width, 0);
        expect(left.height).toBe(height);
        expect(right.height).toBe(height);
        await context.close();
      });
    }
  }

  test("without fill the existing layout holds: one centred at its own width, two at content width from sm", async () => {
    test.skip(!browser, NO_BROWSER);
    const { context, page } = await open(
      1280,
      false,
      `<div id="one">${card(createElement(ButtonRow, { size: "sm" }, btn("Update")))}</div>` +
        `<div id="two" class="mt-4">${card(createElement(ButtonRow, { size: "sm" }, btn("New"), btn("Update")))}</div>`
    );
    const one = await measure(page, "#one [data-card]");
    const mid = (one.content.left + one.content.right) / 2;
    expect(one.buttons[0].width).toBeLessThan(one.content.right - one.content.left - 100);
    expect(Math.abs((one.buttons[0].left + one.buttons[0].right) / 2 - mid)).toBeLessThan(1);
    const two = await measure(page, "#two [data-card]");
    expect(two.buttons[0].left).toBeGreaterThan(two.content.left + 100);
    expect(two.buttons[1].right).toBeLessThan(two.content.right - 100);
    await context.close();
  });

  test("DashboardCard: New and Update fill the tile at phone and desktop widths", async () => {
    test.skip(!browser, NO_BROWSER);
    const shots = join(process.cwd(), "test-results", "button-row-unit");
    mkdirSync(shots, { recursive: true });
    for (const width of [360, 1280]) {
      const tiles = html(
        createElement(
          "div",
          { className: "grid grid-cols-2 gap-3 lg:grid-cols-4", "data-tiles": "" },
          createElement(DashboardCard, { label: "Artisans", icon: Users, newHref: "/artisans/new", updateHref: "/artisans" }),
          createElement(DashboardCard, { label: "View Data", icon: Users, newHref: "/data", newLabel: "Open" })
        )
      );
      const { context, page } = await open(width, false, tiles);
      const pair = await measure(page, "[data-tiles] > :nth-child(1)");
      expect(pair.buttons).toHaveLength(2);
      expect(pair.buttons[0].left).toBeCloseTo(pair.content.left, 0);
      expect(pair.buttons[1].right).toBeCloseTo(pair.content.right, 0);
      expect(pair.buttons[1].left - pair.buttons[0].right).toBeCloseTo(8, 0);
      expect(pair.buttons[0].height).toBe(36);
      expect(pair.buttons[1].height).toBe(36);
      const single = await measure(page, "[data-tiles] > :nth-child(2)");
      expect(single.buttons).toHaveLength(1);
      expect(single.buttons[0].left).toBeCloseTo(single.content.left, 0);
      expect(single.buttons[0].right).toBeCloseTo(single.content.right, 0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      await page.locator("[data-tiles]").screenshot({ path: join(shots, `dashboard-card-${width}.png`) });
      await context.close();
    }
  });
});
