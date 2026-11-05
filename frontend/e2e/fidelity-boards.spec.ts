import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { test } from "@playwright/test";

const REPO = join(__dirname, "..", "..");
const BOARDS = join(REPO, "docs", "design-fidelity", "boards");
const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");

type Board = { w: number; h: number; title: string };

const canvas = JSON.parse(readFileSync(join(BOARDS, "canvas.json"), "utf8")) as { boards: Record<string, Board>; order: string[] };

function scaleFor(width: number): number {
  return width <= 600 ? 2 : 1;
}

test.describe.configure({ mode: "parallel" });

for (const file of canvas.order) {
  const board = canvas.boards[file];
  const name = file.replace(/\.dc\.html$/, "");
  test(`board ${name} renders at ${board.w}×${board.h}`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: board.w, height: board.h }, deviceScaleFactor: scaleFor(board.w) });
    try {
      const page = await context.newPage();
      await page.route((url) => url.pathname.endsWith("/support.js"), (route) => route.fulfill({ status: 200, contentType: "text/javascript", body: "" }));
      await page.goto(pathToFileURL(join(BOARDS, file)).href);
      await page.waitForLoadState("networkidle").catch(() => undefined);
      await page.evaluate(() => document.fonts.ready.then(() => undefined));
      mkdirSync(join(OUT, name), { recursive: true });
      await page.screenshot({ path: join(OUT, name, "board.png"), clip: { x: 0, y: 0, width: board.w, height: board.h } });
    } finally {
      await context.close();
    }
  });
}
