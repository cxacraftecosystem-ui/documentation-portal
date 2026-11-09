import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

import { cellMark } from "@/components/questionnaire/CoverageGrid";

const ROOT = join(__dirname, "..");
const TREES = ["components", "app", "lib"];
const GLYPH = /[\u2713\u2714\u2611\u2705]|\\u(?:2713|2714|2611|2705)|&#(?:10003|10004|9745|9989);|&check;/;
const CURVED = new Set(["CheckCircle", "CircleCheckBig", "SquareCheckBig", "CheckCircleIcon", "CircleCheckBigIcon", "SquareCheckBigIcon", "LucideCheckCircle", "LucideCircleCheckBig"]);
const EXPLORER_TICKS = [
  "components/dataExplorer/DownloadsTray.tsx",
  "components/dataExplorer/PreviewDialog.tsx",
  "components/dataExplorer/SavedViewsDialog.tsx",
  "components/dataExplorer/SortMenu.tsx",
  "components/dataExplorer/TranscriptsTab.tsx",
  "components/dataExplorer/ReportShareControls.tsx"
];

function files(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(join(ROOT, dir))) {
    const rel = join(dir, name);
    if (statSync(join(ROOT, rel)).isDirectory()) out.push(...files(rel));
    else if (/\.(tsx?|json)$/.test(name)) out.push(rel);
  }
  return out;
}

const SOURCES = TREES.flatMap(files).map((rel) => ({ rel: relative(ROOT, join(ROOT, rel)).split("\\").join("/"), source: readFileSync(join(ROOT, rel), "utf8") }));

function lucideNames(source: string): string[] {
  return [...source.matchAll(/import\s*\{([^}]*)\}\s*from\s*["']lucide-react["']/g)].flatMap((match) =>
    match[1]
      .split(",")
      .map((name) => name.replace(/^\s*type\s+/, "").trim().split(/\s+as\s+/)[0])
      .filter(Boolean)
  );
}

test("no screen draws a font tick", () => {
  const offenders = SOURCES.flatMap(({ rel, source }) =>
    source.split("\n").flatMap((line, index) => (GLYPH.test(line) ? [`${rel}:${index + 1}`] : []))
  );
  expect(offenders, "draw ticks with the straight lucide Check").toEqual([]);
});

test("no screen imports a curved tick icon", () => {
  const offenders = SOURCES.flatMap(({ rel, source }) => lucideNames(source).filter((name) => CURVED.has(name)).map((name) => `${rel}: ${name}`));
  expect(offenders, "draw ticks with the straight lucide Check").toEqual([]);
});

test("the data explorer ticks with the straight Check", () => {
  for (const rel of EXPLORER_TICKS) {
    const source = SOURCES.find((file) => file.rel === rel)?.source ?? "";
    expect(source, rel).not.toBe("");
    expect(lucideNames(source), rel).toContain("Check");
    expect(source, rel).toMatch(/<Check\b/);
  }
});

test("a completed coverage cell is the straight tick, not a glyph", () => {
  const mark = cellMark({ state: "COMPLETED", count: 0, partial: null } as Parameters<typeof cellMark>[0]);
  expect(mark.tick).toBe(true);
  expect(mark.mark).toBe("");
});
