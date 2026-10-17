import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * PROGRESS INDICATORS: A TINTED TRACK, A 3:1 FILL, IN BOTH THEMES, FROM ONE SOURCE.
 *
 * Owner report, 2026-10-17: the voice-note progress bar disappeared against the white of its card.
 * Its track was `--line-200` (1.28:1 on white) and in dark the purple-700 fill was 2.32:1 on the
 * card. Every bar now reads `--progress-track` / `--progress-fill` / `--progress-done` through
 * `.progress-track` / `.progress-fill` (globals.css) and `components/ui/ProgressBar.tsx`.
 *
 * Pinned here:
 *  1. the tokens, light and dark — track visibly distinct from the card (>= 1.5:1), every fill
 *     >= 3:1 (WCAG 1.4.11) against the track AND the card;
 *  2. the recipe — at least 4px tall, round ends;
 *  3. no hand-rolled bar: a `h-full rounded-full bg-purple-…` fill with an inline width, or a track
 *     drawn in `bg-line-200` / `bg-field-200` around one, outside the shared component.
 * Android holds the same numbers in `ProgressIndicatorSourceTest`.
 */

const FRONTEND = join(__dirname, "..");
const CSS = readFileSync(join(FRONTEND, "app", "globals.css"), "utf8").split("\r\n").join("\n");

type Rgb = [number, number, number];

function block(selector: string): string {
  const start = CSS.indexOf(`${selector} {`);
  expect(start, `${selector} block`).toBeGreaterThanOrEqual(0);
  return CSS.slice(start, CSS.indexOf("}", start));
}

function token(body: string, name: string): Rgb {
  const match = body.match(new RegExp(`--${name}:\\s*(\\d+)\\s+(\\d+)\\s+(\\d+);`));
  expect(match, `--${name}`).not.toBeNull();
  return [Number(match![1]), Number(match![2]), Number(match![3])];
}

function luminance([r, g, b]: Rgb): number {
  const lin = (c: number) => {
    const v = c / 255;
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function ratio(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

for (const [theme, selector] of [
  ["light", ":root"],
  ["dark", ':root[data-theme="dark"]']
] as const) {
  test(`${theme}: the track is distinct from the card and both fills clear 3:1`, () => {
    const body = block(selector);
    const card = token(body, "card");
    const track = token(body, "progress-track");
    expect(ratio(track, card)).toBeGreaterThanOrEqual(1.5);
    for (const fill of ["progress-fill", "progress-done"]) {
      const value = token(body, fill);
      expect(ratio(value, track), `${fill} vs track`).toBeGreaterThanOrEqual(3);
      expect(ratio(value, card), `${fill} vs card`).toBeGreaterThanOrEqual(3);
    }
  });
}

test("the recipe is at least 4px tall with round ends", () => {
  const track = CSS.slice(CSS.indexOf(".progress-track {"), CSS.indexOf("}", CSS.indexOf(".progress-track {")));
  expect(track).toContain("rounded-full");
  expect(track).toContain("bg-progress-track");
  expect(track).toMatch(/min-height:\s*4px/);
  const fill = CSS.slice(CSS.indexOf(".progress-fill {"), CSS.indexOf("}", CSS.indexOf(".progress-fill {")));
  expect(fill).toContain("rounded-full");
  expect(fill).toContain("bg-progress-fill");
});

test("the audio seek bar reads the progress tokens", () => {
  const player = readFileSync(join(FRONTEND, "components", "ui", "AudioPlayer.tsx"), "utf8");
  expect(player).toContain("rgb(var(--progress-fill))");
  expect(player).toContain("rgb(var(--progress-track))");
  expect(player).not.toContain("rgb(var(--line-200)) ${pct}%");
});

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return name.endsWith(".tsx") ? [path] : [];
  });
}

test("no screen hand-rolls a progress bar", () => {
  const handRolledFill = /className="h-full rounded-full bg-(purple|field|primary)[^"]*"\s*style=\{\{\s*width/;
  const handRolledTrack = /overflow-hidden rounded-full bg-(line-200|field-200)"[^>]*>\s*<div[^>]*h-full rounded-full/;
  const offenders = [...sources(join(FRONTEND, "app")), ...sources(join(FRONTEND, "components"))]
    .filter((path) => !path.endsWith("ProgressBar.tsx"))
    .filter((path) => {
      const text = readFileSync(path, "utf8").split("\r\n").join("\n");
      return handRolledFill.test(text) || handRolledTrack.test(text);
    })
    .map((path) => relative(FRONTEND, path));
  expect(offenders, "use <ProgressBar> or .progress-track / .progress-fill").toEqual([]);
});
