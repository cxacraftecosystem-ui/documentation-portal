import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CONTENT_MIN_WIDTH, DIVIDER_WIDTH, treeWidthBounds } from "@/lib/dataExplorer/explorerView";
import { activeCount, cleared, describeFilters, FACET_ORDER, FILTER_PARAMS, filterQuery, outerSearchTypes, readFilters, RECORD_TYPE_VALUES, searchTypes, showsOther, showsThis, TRANSCRIPT_VALUES, urlFilterQuery, withPortals, withTranscript, withValues, writeFilters } from "@/lib/dataExplorer/filters";
import { UNIFIED_TYPES } from "@/lib/unifiedSearch";
import { columnWidth, columnWidths, TABLE_COLUMN } from "@/lib/dataExplorer/tableLayout";
import { parseTextBody } from "@/lib/dataExplorer/textBody";

const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "data-explorer-contract.json"), "utf8"));

test("@qf:X13 the filter params, facet order and portal values are the contract's", () => {
  const section = CONTRACT.filters;
  expect([...FACET_ORDER]).toEqual(section.facetOrder);
  for (const { param, facet, label } of FILTER_PARAMS) {
    expect(section.params[param].facet).toBe(facet);
    expect(section.params[param].label).toBe(label);
  }
  expect(section.params.portals.values).toEqual(["this", "other"]);
  expect(section.params.recordTypes.values).toEqual([...RECORD_TYPE_VALUES]);
  expect(section.params.hasTranscript.values).toEqual([...TRANSCRIPT_VALUES]);
  expect(Object.keys(section.params).filter((param) => !["dateFrom", "dateTo", "portals"].includes(param))).toEqual(FILTER_PARAMS.map((entry) => entry.param));
  expect(section.params.portals.default).toEqual(["this"]);
  expect(CONTRACT.routes.some((route: { path: string }) => route.path === "/data/filters")).toBe(true);
  expect(CONTRACT.routes.some((route: { path: string }) => route.path === "/data/peer")).toBe(true);
});

test("@qf:X13 filters read CSV or repeated params and write CSV, leaving the rest of the URL alone", () => {
  const filters = readFilters(new URLSearchParams("path=a&craftIds=c1,c2&craftIds=c3&mediaTypes=VIDEO&dateFrom=2026-11-01&dateTo=bad&portals=this,other,elsewhere"));
  expect(filters.values.craftIds).toEqual(["c1", "c2", "c3"]);
  expect(filters.values.mediaTypes).toEqual(["VIDEO"]);
  expect(filters.dateFrom).toBe("2026-11-01");
  expect(filters.dateTo).toBe("");
  expect(filters.portals).toEqual(["this", "other"]);
  expect(filterQuery(filters)).toEqual({ craftIds: "c1,c2,c3", mediaTypes: "VIDEO", dateFrom: "2026-11-01" });
  expect(urlFilterQuery(filters).portals).toBe("this,other");
  const written = writeFilters(withValues(filters, "craftIds", []), new URLSearchParams("path=a&view=tables&craftIds=old"));
  expect(written.get("path")).toBe("a");
  expect(written.get("view")).toBe("tables");
  expect(written.get("craftIds")).toBeNull();
  expect(written.get("mediaTypes")).toBe("VIDEO");
  expect(activeCount(filters)).toBe(3);
  expect(activeCount(cleared())).toBe(0);
  expect(writeFilters(cleared(), new URLSearchParams("portals=other&path=a")).toString()).toBe("path=a");
});

test("@qf:X13 the portal choice never ends up empty and the local routes never see it", () => {
  const filters = readFilters(new URLSearchParams(""));
  expect(filters.portals).toEqual(["this"]);
  expect(showsThis(filters) && !showsOther(filters)).toBe(true);
  const other = withPortals(filters, ["other"]);
  expect(showsThis(other)).toBe(false);
  expect(withPortals(filters, []).portals).toEqual(["this"]);
  expect(filterQuery(other)).toEqual({});
});

test("@qf:X13 the empty state names every active filter by label and value", () => {
  const filters = readFilters(new URLSearchParams("craftIds=c1&mediaTypes=VIDEO,AUDIO&statuses=APPROVED&dateFrom=2026-11-01&dateTo=2026-11-08"));
  const lines = describeFilters(filters, [{ facet: "craft", param: "craftIds", label: "Craft", options: [{ value: "c1", label: "Kantha", hint: null, count: 3 }], truncated: false, selected: ["c1"] }]);
  expect(lines[0]).toBe("Craft: Kantha");
  expect(lines[1]).toBe("Media type: Videos, Audio");
  expect(lines[2]).toBe("Review status: Approved");
  expect(lines[3]).toMatch(/^Date: 1 Nov 2026 – 8 Nov 2026$/);
});

test("@qf:X13 a key: value body is a field table; anything else is paragraphs", () => {
  const scheme = "প্রধানমন্ত্রী বিশ্বকর্মা প্রকল্প";
  const fields = parseTextBody(`Workshop id: dw-1\nScheme: ${scheme}\n  continues here\nDistrict: Birbhum\n`);
  expect(fields.kind).toBe("fields");
  if (fields.kind !== "fields") return;
  expect(fields.rows.map((row) => row.field)).toEqual(["Workshop id", "Scheme", "District"]);
  expect(fields.rows[1].value).toBe(`${scheme}\ncontinues here`);
  expect(parseTextBody("The artisans chose indigo.\n\nNext visit later.")).toEqual({ kind: "prose", paragraphs: ["The artisans chose indigo.", "Next visit later."] });
  expect(parseTextBody("See https://example.org/a\nand https://example.org/b").kind).toBe("prose");
  expect(parseTextBody("   \n").kind).toBe("empty");
  expect(parseTextBody("Only: one").kind).toBe("prose");
});

test("@qf:X13 table columns sit between 160 and 420 px, short columns stay narrow", () => {
  expect(columnWidth("Scheme", ["x".repeat(600)])).toBe(TABLE_COLUMN.max);
  expect(columnWidth("Place", ["Bagru"])).toBe(TABLE_COLUMN.narrow);
  expect(columnWidth("Name", ["Kamla Devi of Bagru town"])).toBeGreaterThan(TABLE_COLUMN.min);
  expect(columnWidth("Age", ["42", "7"])).toBeGreaterThanOrEqual(TABLE_COLUMN.narrow);
  expect(columnWidth("Age", ["42", "7"])).toBeLessThanOrEqual(TABLE_COLUMN.min);
  const widths = columnWidths(["A long heading here", "B"], [["one", "2"], [null, "x".repeat(80)]]);
  expect(widths.every((width) => width >= TABLE_COLUMN.narrow && width <= TABLE_COLUMN.max)).toBe(true);
});

test("@qf:X13 the folders pane always leaves the content its minimum width", () => {
  for (const container of [992, 1100, 1240, 1360]) {
    const bounds = treeWidthBounds(container);
    expect(container - bounds.max - DIVIDER_WIDTH).toBeGreaterThanOrEqual(Math.min(CONTENT_MIN_WIDTH, container - 240 - DIVIDER_WIDTH));
  }
});

test("@qf:X13 a text file is a Text pill, the same word as its tile", async () => {
  const { filterOf, TYPE_FILTERS } = await import("@/lib/dataExplorer/explorerView");
  expect(filterOf({ name: "details.txt", path: "a/details.txt", kind: "file", content: "Scheme: x" })).toBe("text");
  expect(TYPE_FILTERS.find((option) => option.value === "text")?.label).toBe("Text");
});

test("@qf:X13 record type is a canonical multi-select and has transcript keeps one choice", () => {
  const filters = readFilters(new URLSearchParams("recordTypes=Media,artisans,clusters&hasTranscript=YES&hasTranscript=maybe"));
  expect(filters.values.recordTypes).toEqual(["artisans", "media"]);
  expect(filters.values.hasTranscript).toEqual(["yes"]);
  expect(filterQuery(filters)).toEqual({ recordTypes: "artisans,media", hasTranscript: "yes" });
  expect(activeCount(filters)).toBe(2);
  expect(withTranscript(filters, "").values.hasTranscript).toEqual([]);
  expect(withTranscript(filters, "no").values.hasTranscript).toEqual(["no"]);
  expect(describeFilters(filters, null)).toEqual(["Record type: Artisans, Media", "Has transcript: Yes"]);
  const written = writeFilters(cleared(), new URLSearchParams("path=a&recordTypes=tools&hasTranscript=no"));
  expect(written.toString()).toBe("path=a");
});

test("@qf:X13 search narrows its types by record type and treats Has transcript as the old transcript toggle", () => {
  const offered = [...UNIFIED_TYPES];
  const pick = (query: string) => searchTypes(readFilters(new URLSearchParams(query)), offered);
  expect(pick("")).toEqual([]);
  expect(pick("recordTypes=artisans,media")).toEqual(["artisans", "media", "transcripts"]);
  expect(pick("hasTranscript=yes")).toEqual(["transcripts"]);
  expect(pick("hasTranscript=yes&recordTypes=artisans,media")).toEqual(["artisans", "transcripts"]);
  expect(pick("hasTranscript=no")).toEqual([]);
  expect(pick("hasTranscript=no&recordTypes=media")).toEqual(["media"]);
  expect(outerSearchTypes(["designWorkshops"], "", ["artisans", "media"])).toBeNull();
  for (const type of RECORD_TYPE_VALUES) expect(offered).toContain(type);
});
