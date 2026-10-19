import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { promptButtonClass } from "@/components/questionnaire/Prompts";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { alreadyRecorded, prompts, type PromptSpec } from "@/lib/questionnairePrompts";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * DESIGN test 48: every prompt, light and dark, at 360 px, follows the button-row rule (one button
 * centred, two on one line, more stacked). Each prompt is drawn through the real `ButtonRow` and
 * `promptButtonClass` with the app's built stylesheet, so no server or sign-in is needed; it needs
 * only `npm run build` first. Screenshots land in SHOT_DIR for a person to look at.
 */

const SHOT_DIR = process.env.SHOT_DIR ?? "test-results/questionnaire-prompts-visual";

function builtCss(): string | null {
  const root = path.join(process.cwd(), ".next", "static");
  if (!existsSync(root)) return null;
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = path.join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (name.endsWith(".css")) out.push(readFileSync(full, "utf8"));
    }
  };
  walk(root);
  return out.length ? out.join("\n") : null;
}

const css = builtCss();
test.skip(!css, "Run `npm run build` first: the snapshots use the built stylesheet.");

const check = CONTRACT.examples.CoverageCheckResponse as Parameters<typeof alreadyRecorded>[0]["check"];

const SPECS: PromptSpec[] = [
  prompts.p1({ researcher: "Nandini", artisans: "Giriraj and Meena", time: "10:42" }),
  alreadyRecorded({ check, names: { a1: "Giriraj Prasad" }, targetLabel: "Section D", code: "D", part2Allowed: true, replaceAllowed: true }).spec,
  prompts.p3({ target: "Section D", elapsed: "12:40", code: "F3" }),
  prompts.p5({ name: "Giriraj", date: "7 Jul", case: "SAME_SESSION", addName: "Meena" }),
  prompts.p5({ name: "Giriraj", date: "7 Jul", case: "OTHER_SESSION" }),
  prompts.p5({ name: "Giriraj", date: "7 Jul", case: "OTHER" }),
  prompts.p7({ position: 1, target: "Section D" }),
  prompts.p7r({ position: 2, target: "Section D" }),
  prompts.p8({ name: "Giriraj", code: "D3", researcher: "Nandini", date: "7 Jul" }),
  prompts.p9({ researcher: "Nandini", name: "Giriraj", code: "D3", time: "11:04" }),
  prompts.p10({ name: "Ravi", count: 3 }),
  prompts.p13({ minutes: 12, target: "Section D" }),
  prompts.p14(),
  prompts.leave({ count: 2 }),
  prompts.finish({ sections: 6, artisans: 3, pending: 0 })
];

function card(spec: PromptSpec, index: number): string {
  const regular = spec.buttons.filter((b) => b.tone !== "danger");
  const danger = spec.buttons.filter((b) => b.tone === "danger");
  const button = (b: PromptSpec["buttons"][number]) =>
    createElement("button", { key: b.id, type: "button", className: promptButtonClass(b.tone), "data-prompt-button": b.id }, b.label);
  const more = spec.more?.length
    ? createElement("div", { className: "mt-3 grid gap-1" }, ...spec.more.map((b) => createElement("button", { key: b.id, type: "button", className: "text-left text-sm text-purple-700" }, b.label)))
    : null;
  return renderToStaticMarkup(
    createElement(
      "section",
      { className: "panel m-4 p-5", "data-prompt": spec.id, "data-prompt-index": index },
      spec.title ? createElement("h2", { className: "display-title text-lg" }, spec.title) : null,
      createElement("p", { className: "mt-2 text-sm text-ink-700" }, spec.body),
      more,
      createElement(ButtonRow, { className: "mt-5", destructive: danger.length ? danger.map(button) : undefined }, ...regular.map(button))
    )
  );
}

for (const theme of ["light", "dark"] as const) {
  test(`every prompt at 360 px, ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.setContent(
      `<!doctype html><html data-theme="${theme}"><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>${css}</style></head>` +
        `<body class="bg-surface-50">${SPECS.map((spec, i) => card(spec, i)).join("")}</body></html>`
    );
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
    for (const [index, spec] of SPECS.entries()) {
      const box = page.locator(`[data-prompt-index="${index}"]`);
      const buttons = box.locator("[data-prompt-button]");
      const rects = await buttons.evaluateAll((els) => els.map((el) => el.getBoundingClientRect()).map((r) => ({ top: r.top, left: r.left, right: r.right, width: r.width })));
      expect(rects.length, spec.id).toBe(spec.buttons.length);
      const frame = await box.evaluate((el) => el.getBoundingClientRect()).then((r) => ({ left: r.left, right: r.right }));
      for (const r of rects) {
        expect(r.left, `${spec.id} stays inside`).toBeGreaterThanOrEqual(frame.left);
        expect(r.right, `${spec.id} stays inside`).toBeLessThanOrEqual(frame.right + 0.5);
      }
      if (rects.length === 1) {
        const mid = (frame.left + frame.right) / 2;
        expect(Math.abs((rects[0].left + rects[0].right) / 2 - mid), `${spec.id} one button is centred`).toBeLessThan(2);
      } else if (rects.length === 2) {
        expect(Math.abs(rects[0].top - rects[1].top), `${spec.id} two buttons share a line`).toBeLessThan(2);
      } else if (rects.length > 2) {
        const tops = new Set(rects.map((r) => Math.round(r.top)));
        expect(tops.size, `${spec.id} many buttons stack`).toBe(rects.length);
      }
      await box.screenshot({ path: path.join(SHOT_DIR, `${theme}-${index}-${spec.id}.png`) });
    }
  });
}
