import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const LIGHT_FILL = /(?<![\w:-])((?:[a-z-]+:)*)(bg|border)-(purple-(?:50|100|200)|amber-(?:50|100|200)|success-100|error-100|green-(?:50|100)|red-(?:50|100))(?:\/\d+)?(?![\w-])/g;

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}

function lightFillsWithoutDark(source: string): string[] {
  const misses: string[] = [];
  source.split(/\r?\n/).forEach((line, index) => {
    for (const match of line.matchAll(LIGHT_FILL)) {
      const prefix = match[1].replace(/^dark:/, "");
      if (match[1].startsWith("dark:")) continue;
      if (!line.includes(`dark:${prefix}${match[2]}-`)) misses.push(`${index + 1}: ${match[0]}`);
    }
  });
  return misses;
}

test("the dark check catches a light fill with no dark variant and passes one that has it", () => {
  expect(lightFillsWithoutDark('className="bg-purple-50 text-purple-800"')).toEqual(["1: bg-purple-50"]);
  expect(lightFillsWithoutDark('className="hover:bg-purple-100"')).toEqual(["1: hover:bg-purple-100"]);
  expect(lightFillsWithoutDark('className="bg-purple-50 dark:bg-purple-950/40"')).toEqual([]);
  expect(lightFillsWithoutDark('className="hover:bg-purple-50 dark:hover:bg-purple-950/40"')).toEqual([]);
  expect(lightFillsWithoutDark('className="hover:bg-purple-50 dark:bg-purple-950/40"')).toEqual(["1: hover:bg-purple-50"]);
  expect(lightFillsWithoutDark('className="border-purple-200 bg-card"')).toEqual(["1: border-purple-200"]);
  expect(lightFillsWithoutDark('className="bg-purple-700 text-white"')).toEqual([]);
});

test("no questionnaire screen draws a light fill without a dark variant", () => {
  const misses: string[] = [];
  for (const path of [...files(join(ROOT, "components", "questionnaire")), ...files(join(ROOT, "lib", "questionnaire"))]) {
    for (const miss of lightFillsWithoutDark(readFileSync(path, "utf8"))) misses.push(`${relative(ROOT, path).split("\\").join("/")}:${miss}`);
  }
  expect(misses).toEqual([]);
});

test("the segmented control used by Record by has a dark hover", () => {
  expect(lightFillsWithoutDark(readFileSync(join(ROOT, "components", "ui", "SegmentedChoice.tsx"), "utf8"))).toEqual([]);
});
