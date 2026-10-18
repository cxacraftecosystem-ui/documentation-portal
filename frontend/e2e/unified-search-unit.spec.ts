import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  appSearchScore,
  fetchUnified,
  highlightSegments,
  mergeGroups,
  pushRecentSearch,
  remoteStatusLine,
  UNIFIED_TYPES,
  unifiedApplies,
  unifiedQueryString,
  type UnifiedGroup,
  type UnifiedItem,
  type UnifiedResponse
} from "@/lib/unifiedSearch";

/** One search across both portals on the web (docs/SEARCH.md). No browser, no server. */

function item(over: Partial<UnifiedItem>): UnifiedItem {
  return { portal: "fieldRepository", local: true, type: "artisans", id: "a", title: "A", href: "/artisans/a", score: 0.01, ...over };
}

function group(type: UnifiedGroup["type"], items: UnifiedItem[]): UnifiedGroup {
  return { type, label: type, total: items.length, localTotal: items.length, remoteTotal: 0, items };
}

test("the vocabulary is the server's, in its order", () => {
  const server = readFileSync(join(__dirname, "..", "..", "backend", "app", "services", "cross_portal.py"), "utf8");
  const block = server.slice(server.indexOf("SEARCH_TYPES: tuple[str, ...] = ("), server.indexOf("CROSS_PORTAL_SEARCH_ROLES"));
  const names = [...block.matchAll(/"([a-zA-Z]+)"/g)].map((m) => m[1]);
  expect(names).toEqual([...UNIFIED_TYPES]);
});

test("groups come in vocabulary order and unknown types are dropped", () => {
  const merged = mergeGroups([
    group("media", [item({ type: "media", id: "m" })]),
    group("artisans", [item({})]),
    { ...group("artisans", []), type: "nonsense" as never }
  ]);
  expect(merged.map((g) => g.type)).toEqual(["artisans", "media"]);
  expect(merged[0].label).toBe("Artisans");
});

test("items merge by score, local first on a tie, and the same id from two portals stays twice", () => {
  const merged = mergeGroups([
    group("artisans", [
      item({ id: "r1", portal: "designer", local: false, score: 0.0164 }),
      item({ id: "l1", score: 0.0164 }),
      item({ id: "l2", score: 0.0161 }),
      item({ id: "r1", portal: "designer", local: false, score: 0.0164 }),
      item({ id: "l2", portal: "designer", local: false, score: 0.02 })
    ])
  ]);
  expect(merged[0].items.map((i) => `${i.portal}:${i.id}`)).toEqual([
    "designer:l2",
    "fieldRepository:l1",
    "designer:r1",
    "fieldRepository:l2"
  ]);
});

test("a highlight renders text segments only, clipped and in order", () => {
  expect(
    highlightSegments("hand block print with indigo", [
      [22, 28],
      [0, 4],
      [100, 120],
      [3, 2]
    ])
  ).toEqual([
    { text: "hand", hit: true },
    { text: " block print with ", hit: false },
    { text: "indigo", hit: true }
  ]);
  expect(highlightSegments("<b>x</b>", [[0, 3]])).toEqual([
    { text: "<b>", hit: true },
    { text: "x</b>", hit: false }
  ]);
  expect(highlightSegments("", [[0, 1]])).toEqual([]);
});

test("a 404 falls back to the legacy search; other failures are thrown", async () => {
  const off = await fetchUnified({ q: "indigo" }, () => Promise.reject(new ApiError(404, "Not Found", null)));
  expect(off).toEqual({ kind: "legacy" });
  await expect(fetchUnified({ q: "indigo" }, () => Promise.reject(new ApiError(500, "boom", null)))).rejects.toThrow("boom");
  const paths: string[] = [];
  const answer: UnifiedResponse = {
    query: "indigo",
    mode: "text",
    groups: [group("media", [item({ type: "media" })]), group("artisans", [item({})])],
    remote: { portal: "designer", label: "Designer portal", status: "ok" },
    typesRefused: []
  };
  const on = await fetchUnified({ q: " indigo ", types: ["artisans", "media"], place: "Bagru" }, (path) => {
    paths.push(path);
    return Promise.resolve(answer);
  });
  expect(on.kind).toBe("unified");
  if (on.kind === "unified") expect(on.data.groups.map((g) => g.type)).toEqual(["artisans", "media"]);
  expect(paths[0]).toBe("/search/unified?q=indigo&types=artisans%2Cmedia&limit=5&place=Bagru");
});

test("one group pages with type and page; local scope is sent, all is the default", () => {
  expect(unifiedQueryString({ q: "x y", type: "products", page: 2, limit: 20 })).toBe("?q=x+y&limit=20&type=products&page=2");
  expect(unifiedQueryString({ q: "ab", scope: "local" })).toContain("scope=local");
  expect(unifiedQueryString({ q: "ab", scope: "all" })).not.toContain("scope");
});

test("browsing, short text and the legacy-only filters stay on the legacy route", () => {
  expect(unifiedApplies("indigo")).toBe(true);
  expect(unifiedApplies("a")).toBe(false);
  expect(unifiedApplies("")).toBe(false);
  expect(unifiedApplies("indigo", { artisanId: "a1" })).toBe(false);
  expect(unifiedApplies("indigo", { mediaType: "AUDIO" })).toBe(false);
});

test("the remote line is one quiet sentence, and nothing when ok or off", () => {
  expect(remoteStatusLine("ok", "Designer portal")).toBeNull();
  expect(remoteStatusLine("off", "Designer portal")).toBeNull();
  for (const status of ["unavailable", "filteredOut", "notPermitted"] as const) {
    const line = remoteStatusLine(status, "Designer portal") ?? "";
    expect(line.length).toBeGreaterThan(0);
    expect(line.split(". ").length).toBe(1);
    expect(line).not.toMatch(/not built|coming soon|endpoint|server/i);
  }
});

test("scoring and recents match Android's AppSearch.kt", () => {
  expect(appSearchScore("Indigo", "indigo")).toBe(0);
  expect(appSearchScore("Indigo dyeing", "indigo")).toBe(1);
  expect(appSearchScore("Bagru indigo", "indigo")).toBe(2);
  expect(appSearchScore("Bagruindigo", "indigo")).toBe(3);
  expect(appSearchScore("Madder", "indigo")).toBe(4);
  expect(pushRecentSearch(["Indigo", "b"], "indigo")).toEqual(["indigo", "b"]);
  expect(pushRecentSearch(Array.from({ length: 10 }, (_, i) => `q${i}`), "new")).toHaveLength(8);
});

test("the search page renders snippets as text nodes, never as HTML", () => {
  const source = readFileSync(join(__dirname, "..", "components", "search", "UnifiedResults.tsx"), "utf8");
  expect(source).not.toContain("dangerouslySetInnerHTML");
  expect(source).toContain("highlightSegments(");
});

test("the search page sends a typed question to the unified route and keeps browsing legacy", () => {
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "search", "page.tsx"), "utf8");
  expect(page).toContain("unifiedApplies(applied.q)");
  expect(page).toContain("fetchUnified(");
  expect(page).toContain("<UnifiedResults");
});

test("the island calls the page Search", () => {
  const nav = readFileSync(join(__dirname, "..", "components", "DynamicIslandNav.tsx"), "utf8");
  expect(nav).toContain('{ href: "/search", label: "Search"');
});
