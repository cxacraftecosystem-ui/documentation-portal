import { expect, test } from "@playwright/test";

import { LIST_PARAMS, listMemoryFromSearch, listMemoryToSearch } from "@/components/hooks/useListMemory";

/**
 * BACK FROM A RECORD RESTORES THE LIST (owner, 1 Nov 2026), the pure half: a list's page, search,
 * funnel and filters written into its URL and read back. Identical in both portals. The browser
 * half is `list-back-nav.spec.ts`.
 */

test.describe("list state in the URL", () => {
  test("round-trips page, search, funnel and filters", () => {
    const search = listMemoryToSearch({
      query: "bagru",
      applied: "bagru",
      page: 3,
      funnel: { workshopId: "w1", craftId: "", artisanId: "" },
      filters: { completeness: "incomplete", status: "" }
    });
    expect(search).toBe("?search=bagru&page=3&workshop=w1&craft=&artisan=&f_completeness=incomplete");
    expect(listMemoryFromSearch(search)).toEqual({
      query: "bagru",
      applied: "bagru",
      page: 3,
      funnel: { workshopId: "w1", craftId: "", artisanId: "" },
      filters: { completeness: "incomplete" }
    });
  });

  test("keeps every other parameter where it stands and replaces only its own", () => {
    expect(listMemoryToSearch({ page: 2 }, "?edit=abc&page=7&f_old=x&return=%2Fdashboard")).toBe("?edit=abc&return=%2Fdashboard&page=2");
    expect(listMemoryToSearch({ page: 1, query: "" }, "?new=1&search=old")).toBe("?new=1");
    expect(listMemoryToSearch({}, "")).toBe("");
  });

  test("page one and an empty search are left out; the applied search wins over a half-typed one", () => {
    expect(listMemoryToSearch({ query: "bag", applied: "bagru", page: 1 })).toBe("?search=bagru");
  });

  test("a URL without list parameters says nothing, and a bad page reads as page one", () => {
    expect(listMemoryFromSearch("")).toBeNull();
    expect(listMemoryFromSearch("?edit=abc&new=1")).toBeNull();
    expect(listMemoryFromSearch("?page=abc")).toEqual({ query: "", applied: "", page: 1, filters: {} });
    expect(listMemoryFromSearch("?page=0&f_kind=MARKET")).toEqual({ query: "", applied: "", page: 1, filters: { kind: "MARKET" } });
  });

  test("never claims a questionnaire filter's name", () => {
    // `q`, `workshopId` and `artisanId` belong to the shared questionnaire filters on the same URLs.
    expect(Object.values(LIST_PARAMS)).not.toContain("q");
    expect(listMemoryFromSearch("?q=indigo&workshopId=w1&artisanId=a1")).toBeNull();
  });
});
