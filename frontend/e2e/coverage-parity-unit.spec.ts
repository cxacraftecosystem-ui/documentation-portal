import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { binOf, polygonFills, quantileBreaks } from "@/components/map/coverage/coverageMath";
import type { CoverageLevel, CoverageMetric, CoverageUnit } from "@/components/map/coverage/coverageTypes";

/*
  ONE CHOROPLETH ON FOUR SURFACES. `e2e/fixtures/coverage-parity.json` is byte-identical in both
  portals and in both apps' `src/test/resources`: the designer portal's web spec, this one and both
  apps' `CoverageMathTest` read the same rows, so the breaks, the bins and which polygon carries which value
  cannot drift between the web and a phone, or between the two portals.
*/

type Fixture = {
  quantile: Array<{ name: string; values: number[]; breaks: number[]; bins: number[] }>;
  fills: Array<{
    name: string;
    level: CoverageLevel;
    metric: CoverageMetric;
    shapes: Array<{ lgd: number; stateLgd: number }>;
    units: CoverageUnit[];
    fills: Array<{ lgd: number; value: number; hostsChild: boolean }>;
  }>;
};

const ROOT = join(__dirname, "..", "..");
const FIXTURE = join(__dirname, "fixtures", "coverage-parity.json");
const fixture = JSON.parse(readFileSync(FIXTURE, "utf8")) as Fixture;

test("the fixture is the one Android reads", () => {
  const android = join(ROOT, "android", "app", "src", "test", "resources", "coverage-parity.json");
  const strip = (text: string) => text.replace(/\r\n/g, "\n");
  expect(strip(readFileSync(android, "utf8"))).toBe(strip(readFileSync(FIXTURE, "utf8")));
});

for (const row of fixture.quantile) {
  test(`breaks and bins: ${row.name}`, () => {
    const breaks = quantileBreaks(row.values);
    expect(breaks).toEqual(row.breaks);
    expect(row.values.map((value) => binOf(value, breaks))).toEqual(row.bins);
  });
}

for (const row of fixture.fills) {
  test(`fills: ${row.name}`, () => {
    const fills = polygonFills(row.units, row.level, row.metric, row.shapes);
    const got = [...fills.entries()]
      .map(([lgd, fill]) => ({ lgd, value: fill.value, hostsChild: fill.hostsChild }))
      .sort((a, b) => a.lgd - b.lgd);
    expect(got).toEqual([...row.fills].sort((a, b) => a.lgd - b.lgd));
  });
}
