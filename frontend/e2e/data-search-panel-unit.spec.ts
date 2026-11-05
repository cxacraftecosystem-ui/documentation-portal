import { expect, test } from "@playwright/test";

import { activeFilters, clock, EMPTY_STATE, flattenGroups, hasMore, legacyRows, legacyTotals, mergeRows, moreLabel, portalCounts, rangeBounds, sections, showAllLabel, unifiedTotals } from "@/lib/dataSearch";
import type { UnifiedResponse } from "@/lib/unifiedSearch";

const DATA: UnifiedResponse = {
  query: "indigo",
  mode: "text",
  remote: { portal: "designer", label: "Designer portal", status: "ok" },
  typesRefused: [],
  groups: [
    { type: "transcripts", label: "Transcripts", total: 21, localTotal: 14, remoteTotal: 7, items: [{ portal: "fieldRepository", local: true, type: "transcripts", id: "m1", title: "Section D", href: "/media/m1", startMs: 724000 }] },
    { type: "tools", label: "Tools", total: 3, localTotal: 3, remoteTotal: 0, items: [{ portal: "fieldRepository", local: true, type: "tools", id: "t1", title: "Indigo vat stirrer", href: "/tools/t1" }] }
  ]
};

test("Play from reads as mm:ss, and h:mm:ss past an hour", () => {
  expect(clock(724000)).toBe("12:04");
  expect(clock(3723000)).toBe("1:02:03");
  expect(clock(-5)).toBe("00:00");
});

test("results flatten in group order, or one group when a pill is chosen", () => {
  expect(flattenGroups(DATA, null).map((row) => row.id)).toEqual(["m1", "t1"]);
  expect(flattenGroups(DATA, "tools").map((row) => row.id)).toEqual(["t1"]);
});

test("each portal's count is the sum of its totals", () => {
  expect(portalCounts(DATA)).toEqual({ designer: 7, fieldRepository: 17 });
});

test("filters are counted once each, and a date preset starts the right day", () => {
  expect(activeFilters(EMPTY_STATE)).toBe(0);
  expect(activeFilters({ ...EMPTY_STATE, workshopIds: ["w1", "w2"], artisanId: "a", transcriptOnly: true })).toBe(3);
  const now = new Date(2026, 10, 5, 15, 0, 0);
  expect(new Date(rangeBounds("7d", now).dateFrom!).getDate()).toBe(30);
  expect(rangeBounds("any", now)).toEqual({});
});

test("the legacy search fills the same cards when unified search is off", () => {
  const rows = legacyRows({ artisans: [{ id: "a1", name: "Kamla Devi", place: "Bagru" } as never], media: [{ id: "m1", caption: "", originalFilename: "loom.mp4", mediaType: "VIDEO" } as never] });
  expect(rows.map((row) => [row.type, row.title, row.href])).toEqual([
    ["artisans", "Kamla Devi", "/artisans/a1"],
    ["media", "loom.mp4", "/media/m1"]
  ]);
  expect(rows.every((row) => row.portal === "fieldRepository" && row.local)).toBe(true);
});

test("paging helpers: rows merge once, All keeps a way forward, a pill pages its own type", () => {
  const rows = flattenGroups(DATA, null);
  expect(mergeRows(rows, rows)).toHaveLength(2);
  const groups = unifiedTotals(DATA);
  expect(hasMore(null, groups, rows, true)).toBe(true);
  expect(hasMore(null, groups, rows, false)).toBe(false);
  expect(hasMore("tools", groups, rows, true)).toBe(true);
  expect(moreLabel("transcripts", groups, rows)).toBe("Show 20 more");
  expect(moreLabel("tools", groups, rows)).toBe("Show 2 more");
  expect(moreLabel(null, groups, rows)).toBe("Load more");
  expect(sections(groups, rows).map((section) => section.group.type)).toEqual(["transcripts", "tools"]);
  expect(showAllLabel(groups[0])).toBe("Show all 21 transcripts");
  const all = Array.from({ length: 31 }, (_, index) => ({ portal: DATA.groups[0].items[0].portal, local: true, type: "artisans" as const, id: `a${index}`, title: `A${index}`, href: "" }));
  expect(hasMore("artisans", [{ type: "artisans", label: "Artisans", total: 31 }], all, true)).toBe(false);
  expect(legacyTotals({ artisans: [{ id: "a1", name: "K" } as never], totals: { artisans: 40 } })).toEqual([{ type: "artisans", label: "Artisans", total: 40 }]);
});
