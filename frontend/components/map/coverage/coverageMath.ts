/**
 * The coverage view's pure half: URL <-> filters, the query string, the quantile bins, the metric
 * reading and the code register. No React, no fetch — `e2e/coverage-unit.spec.ts` calls these
 * directly, the same split as `components/ui/selectFilter.ts`.
 */

import type {
  CoverageCounts,
  CoverageMetric,
  CoverageQuery,
  CoverageResponse
} from "@/components/map/coverage/coverageTypes";

export const EMPTY_COVERAGE_QUERY: CoverageQuery = {
  level: "DISTRICT",
  craftCode: [],
  scheme: [],
  fy: [],
  stateLgd: null
};

export const METRICS: Array<{ value: CoverageMetric; label: string }> = [
  { value: "workshops", label: "Workshops" },
  { value: "artisans", label: "Artisans" },
  { value: "all", label: "All records" }
];

function list(params: URLSearchParams, key: string): string[] {
  return params
    .getAll(key)
    .flatMap((value) => value.split(","))
    .map((value) => value.trim())
    .filter(Boolean);
}

/** The filters a `?view=coverage&...` URL carries. Unknown values are dropped, never guessed. */
export function coverageQueryFromParams(params: URLSearchParams): CoverageQuery {
  const level = (params.get("level") ?? "").toUpperCase() === "STATE" ? "STATE" : "DISTRICT";
  const fy = list(params, "fy")
    .map(Number)
    .filter((year) => Number.isInteger(year) && year >= 1990 && year <= 2100);
  const state = Number(params.get("stateLgd"));
  return {
    level,
    craftCode: list(params, "craftCode"),
    scheme: list(params, "scheme"),
    fy,
    stateLgd: Number.isInteger(state) && state > 0 ? state : null
  };
}

/** The query object `buildQuery` takes: arrays comma-joined, empty values left out. */
export function coverageParams(query: CoverageQuery): Record<string, string | undefined> {
  return {
    level: query.level === "DISTRICT" ? undefined : query.level,
    craftCode: query.craftCode.join(",") || undefined,
    scheme: query.scheme.join(",") || undefined,
    fy: query.fy.join(",") || undefined,
    stateLgd: query.stateLgd ? String(query.stateLgd) : undefined
  };
}

/** The page URL for a coverage view, so a filtered view can be linked and Back restores it. */
export function coverageHref(query: CoverageQuery, metric: CoverageMetric): string {
  const params = new URLSearchParams({ view: "coverage" });
  for (const [key, value] of Object.entries(coverageParams(query))) {
    if (value) params.set(key, value);
  }
  if (metric !== "workshops") params.set("metric", metric);
  return `/map?${params.toString()}`;
}

export function metricFromParams(params: URLSearchParams): CoverageMetric {
  const value = params.get("metric");
  return value === "artisans" || value === "all" ? value : "workshops";
}

/** `2025` -> `"2025-26"`: the financial year that starts 1 April 2025. */
export function fyLabel(fy: number): string {
  return `${fy}-${String((fy + 1) % 100).padStart(2, "0")}`;
}

/** The number a unit is coloured by. "All records" sums the record buckets, never crafts. */
export function metricValue(counts: CoverageCounts, metric: CoverageMetric): number {
  if (metric === "workshops") return counts.workshops;
  if (metric === "artisans") return counts.artisans;
  return counts.workshops + counts.artisans + counts.products + counts.tools + counts.media;
}

export const BIN_COUNT = 5;

/**
 * Upper bounds of up to five quantile bins over the NON-ZERO values. Zero is not a bin: it is "no
 * record" and is drawn in the base tone. Duplicate bounds collapse, so three distinct values give
 * three bins rather than five with two empty.
 */
export function quantileBreaks(values: number[], bins: number = BIN_COUNT): number[] {
  const sorted = values.filter((value) => value > 0).sort((a, b) => a - b);
  if (!sorted.length) return [];
  const breaks: number[] = [];
  for (let k = 1; k <= bins; k += 1) {
    const at = Math.min(sorted.length - 1, Math.ceil((k / bins) * sorted.length) - 1);
    const bound = sorted[at];
    if (!breaks.length || bound > breaks[breaks.length - 1]) breaks.push(bound);
  }
  return breaks;
}

/** Which bin a value falls in: -1 for zero, else the first bin whose bound holds it. */
export function binOf(value: number, breaks: number[]): number {
  if (value <= 0 || !breaks.length) return -1;
  const index = breaks.findIndex((bound) => value <= bound);
  return index === -1 ? breaks.length - 1 : index;
}

/** Legend text for each bin: "1", "2–4", "5–12". */
export function binLabels(breaks: number[]): string[] {
  return breaks.map((bound, index) => {
    const low = index === 0 ? 1 : breaks[index - 1] + 1;
    return low >= bound ? String(bound) : `${low}–${bound}`;
  });
}

export type RegisterState = { lgd: number; name: string; districts: Array<{ lgd: number; name: string }> };

/**
 * Every state and district with an LGD code, read off an UNFILTERED district-level answer.
 *
 * Every coded district is either a unit (it has a workshop) or in the "without workshop" list, so
 * the two together are the register — no second endpoint needed for the alias pickers. Pending
 * districts (no code yet) cannot be the target of an alias and are left out.
 */
export function registerFromCoverage(body: CoverageResponse): RegisterState[] {
  const states = new Map<number, { name: string; districts: Map<number, string> }>();
  const touch = (lgd: number, name: string) => {
    let entry = states.get(lgd);
    if (!entry) {
      entry = { name, districts: new Map() };
      states.set(lgd, entry);
    }
    return entry;
  };
  for (const row of body.gaps.districtsWithoutWorkshop.byState) {
    const entry = touch(row.stateLgd, row.state);
    for (const district of row.without) {
      if (district.lgd !== null) entry.districts.set(district.lgd, district.name);
    }
  }
  for (const state of body.gaps.statesWithoutWorkshop) touch(state.lgd, state.name);
  for (const unit of body.units) {
    const entry = touch(unit.stateLgd, unit.state);
    if (unit.districtLgd !== null && unit.district) entry.districts.set(unit.districtLgd, unit.district);
  }
  return [...states.entries()]
    .map(([lgd, entry]) => ({
      lgd,
      name: entry.name,
      districts: [...entry.districts.entries()]
        .map(([code, name]) => ({ lgd: code, name }))
        .sort((a, b) => a.name.localeCompare(b.name))
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** "Shown inside Barmer (parent)" for a district drawn in the polygon it was carved from. */
export function parentNote(parentName: string | null | undefined): string | null {
  return parentName ? `Shown inside ${parentName} (parent)` : null;
}
