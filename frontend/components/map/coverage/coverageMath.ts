import type {
  CoverageCounts,
  CoverageLevel,
  CoverageMetric,
  CoverageQuery,
  CoverageResponse,
  CoverageUnit
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

export function coverageParams(query: CoverageQuery): Record<string, string | undefined> {
  return {
    level: query.level === "DISTRICT" ? undefined : query.level,
    craftCode: query.craftCode.join(",") || undefined,
    scheme: query.scheme.join(",") || undefined,
    fy: query.fy.join(",") || undefined,
    stateLgd: query.stateLgd ? String(query.stateLgd) : undefined
  };
}

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

export function fyLabel(fy: number): string {
  return `${fy}-${String((fy + 1) % 100).padStart(2, "0")}`;
}

export function metricValue(counts: CoverageCounts, metric: CoverageMetric): number {
  if (metric === "workshops") return counts.workshops;
  if (metric === "artisans") return counts.artisans;
  return counts.workshops + counts.artisans + counts.products + counts.tools + counts.media;
}

export const BIN_COUNT = 5;

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

export function binOf(value: number, breaks: number[]): number {
  if (value <= 0 || !breaks.length) return -1;
  const index = breaks.findIndex((bound) => value <= bound);
  return index === -1 ? breaks.length - 1 : index;
}

export function binLabels(breaks: number[]): string[] {
  return breaks.map((bound, index) => {
    const low = index === 0 ? 1 : breaks[index - 1] + 1;
    return low >= bound ? String(bound) : `${low}–${bound}`;
  });
}

export type PolygonFill = {
  value: number;
  hostsChild: boolean;
};

export function polygonFills(
  units: CoverageUnit[],
  level: CoverageLevel,
  metric: CoverageMetric,
  shapes: Array<{ lgd: number; stateLgd: number }>
): Map<number, PolygonFill> {
  const map = new Map<number, PolygonFill>();
  if (level === "STATE") {
    const byState = new Map(units.map((unit) => [unit.stateLgd, metricValue(unit.counts, metric)]));
    for (const shape of shapes) {
      const value = byState.get(shape.stateLgd);
      if (value) map.set(shape.lgd, { value, hostsChild: false });
    }
    return map;
  }
  for (const unit of units) {
    if (unit.districtLgd === null) continue;
    const target = unit.polygon === "PARENT" ? unit.parentLgd : unit.polygon === "OWN" ? unit.districtLgd : null;
    if (target === null) continue;
    const current = map.get(target) ?? { value: 0, hostsChild: false };
    map.set(target, {
      value: current.value + metricValue(unit.counts, metric),
      hostsChild: current.hostsChild || unit.polygon === "PARENT"
    });
  }
  return map;
}

export type RegisterState = { lgd: number; name: string; districts: Array<{ lgd: number; name: string }> };

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

export function parentNote(parentName: string | null | undefined): string | null {
  return parentName ? `Shown inside ${parentName} (parent)` : null;
}
