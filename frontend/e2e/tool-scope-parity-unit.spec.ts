import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { toolArtisanNames, toolRowLabel } from "@/lib/toolArtisans";
import type { ToolDocumentation } from "@/lib/types";

const ROOT = join(__dirname, "..", "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8").replace(/\r\n/g, "\n");
const vectors = JSON.parse(read("shared/tool-scope-vectors.json")) as {
  artisanNames: { case: string; tool: ToolDocumentation; expected: string | null }[];
};

for (const row of vectors.artisanNames) {
  test(`artisan names: ${row.case}`, () => {
    expect(toolArtisanNames(row.tool)).toBe(row.expected);
  });
}

test("a tool row names every linked artisan, and a tool with none keeps its own name", () => {
  const shared = vectors.artisanNames[1].tool;
  expect(toolRowLabel({ ...shared, toolkitName: "Wooden block" })).toBe("Wooden block · Kamla Devi, Ramesh Chhipa");
  expect(toolRowLabel({ ...vectors.artisanNames[5].tool, toolkitName: "Mallet" })).toBe("Mallet");
});

test("the data and media pages label tools through the shared helper", () => {
  const data = read("frontend/app/(protected)/data/page.tsx");
  expect(data).toContain("toolRowLabel(");
  expect(data).not.toMatch(/x\.toolkitName\} · \$\{x\.artisanName/);
  const media = read("frontend/app/(protected)/media/page.tsx");
  expect(media).toContain("toolArtisanNames(");
  expect(media).not.toContain("text(row.toolkitName), text(row.artisanName)");
});
