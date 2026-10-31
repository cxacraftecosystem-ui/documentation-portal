import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { chipDay, chipDuration, displayChips, sessionChips, type RecordingChips } from "@/lib/questionnaire/chips";
import { activeCount, applyChip, filtersAsParams, filtersFromQuery, filtersToQuery, FILTER_KEYS, isChipActive, MULTI_KEYS, type Filters } from "@/lib/questionnaire/filters";

/**
 * Recording chips and the shared filters, the pure rules (owner, 31 Oct 2026). Every case of
 * `shared/questionnaire-chips.json` — the labels and filters each chip carries, what tapping one does,
 * and the canonical query — runs here; Android's `RecordingChipsTest` runs the same file. No server,
 * no browser. This spec is identical in both portals.
 */

type Vectors = {
  maxTargets: number;
  maxPeople: number;
  filterKeys: string[];
  multiKeys: string[];
  cases: { name: string; chips: RecordingChips; expect: unknown[] }[];
  applyCases: { name: string; before: Filters; chip: { key: string; value: string }; after: Filters }[];
  queryCases: { filters: Filters; query: string }[];
};

const vectors = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "questionnaire-chips.json"), "utf-8")) as Vectors;

test("@qf:E1 every vector draws exactly its chips: labels, order, filters and the +N titles", () => {
  for (const c of vectors.cases) {
    expect(displayChips(c.chips), c.name).toEqual(c.expect);
  }
});

test("@qf:E1 a session's chips leave out the take: no target, no duration, and people can be left to the row", () => {
  const take = vectors.cases[0].chips;
  const kinds = sessionChips(take).map((c) => c.kind);
  expect(kinds).toEqual(["questionnaire", "artisan", "artisan", "date", "workshop", "language"]);
  expect(sessionChips(take, { people: false }).map((c) => c.kind)).toEqual(["questionnaire", "date", "workshop", "language"]);
});

test("@qf:E1 days and durations read the same on every client", () => {
  expect(chipDay("2026-07-07")).toBe("7 Jul 2026");
  expect(chipDay("2026-12-31")).toBe("31 Dec 2026");
  expect(chipDuration(0)).toBe("0:00");
  expect(chipDuration(59_400)).toBe("0:59");
  expect(chipDuration(3_600_000)).toBe("1:00:00");
});

test("@qf:E2 tapping a chip toggles its filter, and a day sets or clears both ends", () => {
  for (const c of vectors.applyCases) {
    expect(applyChip(c.before, c.chip), c.name).toEqual(c.after);
  }
  const day = { key: "date", value: "2026-10-22" };
  expect(isChipActive(applyChip({}, day), day)).toBe(true);
  expect(isChipActive({ artisanId: ["a1"] }, { key: "artisanId", value: "a1" })).toBe(true);
  expect(isChipActive({}, null)).toBe(false);
});

test("@qf:E3 @qf:E6 the filters travel as one canonical query, and read back from it", () => {
  expect([...FILTER_KEYS]).toEqual(vectors.filterKeys);
  expect([...MULTI_KEYS]).toEqual(vectors.multiKeys);
  for (const c of vectors.queryCases) {
    expect(filtersToQuery(c.filters), JSON.stringify(c.filters)).toBe(c.query);
    expect(filtersFromQuery(c.query)).toEqual(c.filters);
  }
  // Repeated parameters and commas are one list; unknown keys are ignored.
  expect(filtersFromQuery("artisanId=a1&artisanId=a2,a1&page=3")).toEqual({ artisanId: ["a1", "a2"] });
  expect(filtersAsParams({ artisanId: ["a1", "a2"], q: "loom" })).toEqual({ q: "loom", artisanId: "a1,a2" });
  expect(activeCount({ q: "loom", artisanId: ["a1", "a2"], dateFrom: "2026-10-01" })).toBe(3);
});
