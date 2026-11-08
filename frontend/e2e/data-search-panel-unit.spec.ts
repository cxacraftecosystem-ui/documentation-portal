import { expect, test } from "@playwright/test";

import { activeFilters, clock, EMPTY_STATE, flattenGroups, hasMore, legacyRows, legacyTotals, mergeRows, moreLabel, offersOpenInData, portalCounts, rangeBounds, sameSearch, searchFromParams, searchSort, searchSortKeys, searchSortLevel, searchSortParam, searchToParams, sections, showAllLabel, unifiedTotals } from "@/lib/dataSearch";
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

test("a search round-trips through the address and leaves the explorer's own keys alone", () => {
  const state = { ...EMPTY_STATE, q: " indigo ", scope: "all" as const, types: ["tools" as const, "artisans" as const], range: "30d" as const, transcriptOnly: true, workshopIds: ["w1", "w2"], artisanId: "a1", craftId: "c1" };
  const text = searchToParams(state, "tools", "path=workshops%2Fbagru&view=tables&q=kamla");
  const params = new URLSearchParams(text);
  expect(params.get("path")).toBe("workshops/bagru");
  expect(params.get("view")).toBe("tables");
  expect(params.get("q")).toBe("kamla");
  expect(params.get("sq")).toBe("indigo");
  const back = searchFromParams(params);
  expect(back.focus).toBe("tools");
  expect(back.state).toEqual({ ...state, q: "indigo" });
  expect(sameSearch(back, { state, focus: "tools" })).toBe(true);
  expect(searchToParams(EMPTY_STATE, null, text)).toBe("path=workshops%2Fbagru&view=tables&q=kamla");
});

test("a hand-edited address cannot smuggle in an unknown type, range or scope", () => {
  const back = searchFromParams(new URLSearchParams("sq=x&sin=nonsense&stypes=tools,bogus&srange=forever&sscope=everywhere"));
  expect(back.focus).toBeNull();
  expect(back.state.types).toEqual(["tools"]);
  expect(back.state.range).toBe("any");
  expect(back.state.scope).toBe("local");
});

test("Open in Data is offered only where the record can be located", () => {
  const row = { portal: "fieldRepository" as const, local: true, type: "crafts" as const, id: "c1", title: "Bidri", href: "/crafts/c1" };
  expect(offersOpenInData(row, "craft")).toBe(true);
  expect(offersOpenInData({ ...row, locatable: true }, "craft")).toBe(true);
  expect(offersOpenInData({ ...row, locatable: false }, "craft")).toBe(false);
  expect(offersOpenInData({ ...row, local: false }, "craft")).toBe(false);
  expect(offersOpenInData(row, undefined)).toBe(false);
});

test("a sort rides in the address only when it is not the default, and only the search keys pass", () => {
  const state = { ...EMPTY_STATE, q: "indigo", sort: { key: "name" as const, dir: "asc" as const } };
  const text = searchToParams(state, null, "path=workshops");
  expect(new URLSearchParams(text).get("ssort")).toBe("name:asc");
  expect(searchFromParams(new URLSearchParams(text)).state.sort).toEqual({ key: "name", dir: "asc" });
  expect(new URLSearchParams(searchToParams({ ...state, sort: { key: "relevance", dir: "desc" } }, null, text)).get("ssort")).toBeNull();
  expect(searchFromParams(new URLSearchParams("sq=x&ssort=recorded:asc")).state.sort).toEqual({ key: "recorded", dir: "asc" });
  for (const raw of ["size:desc", "relevance:asc", "relevance:desc", "name", "added:desc", "name:sideways"]) {
    expect(searchFromParams(new URLSearchParams(`sq=x&ssort=${raw}`)).state.sort).toBeNull();
  }
  expect(sameSearch({ state: { ...EMPTY_STATE, q: "x" }, focus: null }, { state: { ...EMPTY_STATE, q: "x", sort: { key: "name", dir: "asc" } }, focus: null })).toBe(false);
});

test("the sort control offers only the keys the server answers, and each list level remembers its own", () => {
  expect(searchSortKeys({ ...DATA, sorts: ["recorded", "relevance", "size", "name"] } as UnifiedResponse)).toEqual(["relevance", "name", "recorded"]);
  expect(searchSortKeys(DATA)).toEqual([]);
  expect(searchSortKeys(null)).toEqual([]);
  expect(searchSortLevel(null)).toBe("search:all");
  expect(searchSortLevel("artisans")).toBe("search:artisans");
  expect(searchSortParam(null)).toBeUndefined();
  expect(searchSortParam({ key: "relevance", dir: "desc" })).toBeUndefined();
  expect(searchSortParam({ key: "name", dir: "desc" })).toBe("name:desc");
  expect(searchSortParam({ key: "recorded", dir: "asc" })).toBe("recorded:asc");
  expect(searchSortParam({ key: "size", dir: "desc" })).toBeUndefined();
  expect(searchSort("name:asc")).toEqual({ key: "name", dir: "asc" });
  expect(searchSort({ key: "relevance", dir: "desc" })).toBeNull();
});
