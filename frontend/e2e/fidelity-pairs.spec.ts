import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { test } from "@playwright/test";

const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");

function pairs(): { dir: string; shot: string }[] {
  if (!existsSync(OUT)) return [];
  return readdirSync(OUT).flatMap((board) => {
    const dir = join(OUT, board);
    if (!existsSync(join(dir, "board.png"))) return [];
    return readdirSync(dir)
      .filter((name) => name.startsWith("impl") && name.endsWith(".png"))
      .map((shot) => ({ dir, shot }));
  });
}

for (const { dir, shot } of pairs()) {
  test(`pair ${dir.split(/[\\/]/).pop()} · ${shot}`, async ({ browser }) => {
    const left = readFileSync(join(dir, "board.png")).toString("base64");
    const right = readFileSync(join(dir, shot)).toString("base64");
    const page = await browser.newPage({ viewport: { width: 400, height: 300 } });
    try {
      await page.setContent(
        `<!doctype html><body style="margin:0;background:#e9e7f2;font:600 14px system-ui;color:#1e1b2e"><div style="display:flex;gap:24px;padding:16px;align-items:flex-start;width:max-content"><figure style="margin:0"><figcaption style="padding:0 0 8px">Board</figcaption><img src="data:image/png;base64,${left}"></figure><figure style="margin:0"><figcaption style="padding:0 0 8px">Build · ${shot.replace(/\.png$/, "")}</figcaption><img src="data:image/png;base64,${right}"></figure></div></body>`
      );
      await page.evaluate(async () => {
        await Promise.all([...document.images].map((image) => (image.complete ? Promise.resolve() : new Promise((resolve) => image.addEventListener("load", resolve)))));
      });
      await page.screenshot({ path: join(dir, shot.replace(/^impl/, "compare")), fullPage: true });
    } finally {
      await page.close();
    }
  });
}
