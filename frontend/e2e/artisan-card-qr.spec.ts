import { expect, test } from "@playwright/test";

import { qrImageSrc } from "@/lib/artisanCardApi";

const SEGNO_INLINE =
  '<svg width="116" height="116" class="segno"><path transform="scale(4)" stroke="#000" class="qrline" d="M2 2.5h7m2 0h1M2 3.5h1m5 0h1m2 0h1M2 4.5h1m1 0h3m1 0h1"/></svg>';
const STANDALONE =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29" width="116" height="116" shape-rendering="crispEdges"><rect width="29" height="29" fill="#ffffff"/><path fill="#000000" d="M2 2h7v1h-7zM2 3h1v1h-1z"/></svg>';

for (const [name, svg] of [["an already-issued card's svg without a namespace", SEGNO_INLINE], ["the server's standalone svg", STANDALONE]] as const) {
  test(`the card QR image loads from ${name}`, async ({ page }) => {
    await page.setContent(`<img id="qr" alt="QR" src="${qrImageSrc(svg)}" />`);
    const size = await page.locator("#qr").evaluate(async (img: HTMLImageElement) => {
      if (!img.complete) {
        await new Promise((done) => {
          img.addEventListener("load", done, { once: true });
          img.addEventListener("error", done, { once: true });
        });
      }
      return { width: img.naturalWidth, height: img.naturalHeight };
    });
    expect(size.width).toBeGreaterThan(0);
    expect(size.height).toBeGreaterThan(0);
  });
}
