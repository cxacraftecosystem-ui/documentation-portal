/**
 * The record forms read each reference list once per page mount: the workshop list shared by
 * `RecordSwitcher` and `WorkshopSelect`, and the artisan page shared by the two artisan pickers.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { apiFetch } from "@/lib/api";
import { sharedListResource } from "@/lib/sharedList";
import type { PageResult, Workshop } from "@/lib/types";
import { createWorkshopListLoader, loadWorkshopList, WORKSHOP_LIST_REUSE_MS } from "@/lib/workshopList";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function page<T>(items: T[]): PageResult<T> {
  return { items, total: items.length, page: 1, pageSize: 100, pages: 1 } as PageResult<T>;
}

test("two pickers asking for the same list at once share one request; nothing is kept after", async () => {
  const calls: string[] = [];
  const fetchList = async (path: string, params: Record<string, string | number | undefined | null>) => {
    calls.push(`${path}?${JSON.stringify(params)}`);
    return page([{ id: "a" }]);
  };
  const [one, two] = await Promise.all([
    sharedListResource("/artisans", { pageSize: 100 }, fetchList),
    sharedListResource("/artisans", { pageSize: 100 }, fetchList)
  ]);
  expect(calls).toHaveLength(1);
  expect(one).toBe(two);
  await sharedListResource("/artisans", { pageSize: 100 }, fetchList);
  expect(calls).toHaveLength(2);
  await Promise.all([
    sharedListResource("/artisans", { pageSize: 100, craftId: "c" }, fetchList),
    sharedListResource("/artisans", { pageSize: 100 }, fetchList)
  ]);
  expect(calls).toHaveLength(4);
});

test("the workshop list is read once per page mount, re-read later, and forgotten on a write", async () => {
  let now = 0;
  let calls = 0;
  const loader = createWorkshopListLoader(async () => {
    calls += 1;
    return page<Workshop>([]);
  }, () => now);
  await Promise.all([loader.load("u1"), loader.load("u1")]);
  expect(calls).toBe(1);
  now = WORKSHOP_LIST_REUSE_MS - 1;
  await loader.load("u1");
  expect(calls).toBe(1);
  now = WORKSHOP_LIST_REUSE_MS;
  await loader.load("u1");
  expect(calls).toBe(2);
  loader.invalidate();
  await loader.load("u1");
  expect(calls).toBe(3);
});

test("a failed workshop read is not kept", async () => {
  let calls = 0;
  const loader = createWorkshopListLoader(async () => {
    calls += 1;
    if (calls === 1) throw new Error("offline");
    return page<Workshop>([]);
  });
  await expect(loader.load("u1")).rejects.toThrow("offline");
  await loader.load("u1");
  expect(calls).toBe(2);
});

test("the record forms read through the shared loaders", () => {
  expect(read("components/forms/RecordSwitcher.tsx")).toContain("loadWorkshopList(readerId)");
  expect(read("components/forms/WorkshopSelect.tsx")).toContain("loadWorkshopList(readerId)");
  expect(read("components/AuthProvider.tsx")).toMatch(/const clearSession = useCallback\(\(\) => \{[^}]*invalidateWorkshopList\(\);/);
  expect(read("app/(protected)/workshops/page.tsx")).toContain("invalidateWorkshopList()");
  expect(read("components/forms/useServerSearchList.ts")).toContain("sharedListResource<T>(endpoint, { ...stableParams, pageSize: LIST_PAGE_CEILING })");
  const pickers = read("components/forms/recordPickers.ts");
  expect(pickers).toContain('sharedListResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })');
  expect(pickers).toContain('useRecordsOffPage<Artisan>("/artisans", artisanIds, artisans, artisanState !== "pending")');
});

test("user B never receives user A's cached or in-flight workshop list", async () => {
  const lists: Record<string, Workshop[]> = { a: [{ id: "wa" } as Workshop], b: [{ id: "wb" } as Workshop] };
  let reader = "a";
  let calls = 0;
  const loader = createWorkshopListLoader(async () => {
    calls += 1;
    const who = reader;
    await new Promise((resolve) => setTimeout(resolve, 5));
    return page(lists[who]);
  }, () => 0);

  // Cached: A's answer is fresh, yet B (same tab, within the reuse window) reads its own.
  expect((await loader.load("a")).items).toEqual(lists.a);
  reader = "b";
  expect((await loader.load("b")).items).toEqual(lists.b);
  expect(calls).toBe(2);

  // In flight: B asks while A's request is still out; B is not handed A's promise, and A's late
  // answer is not kept for anybody.
  loader.invalidate();
  reader = "a";
  const forA = loader.load("a");
  reader = "b";
  const forB = loader.load("b");
  expect((await forB).items).toEqual(lists.b);
  expect((await forA).items).toEqual(lists.a);
  expect((await loader.load("b")).items).toEqual(lists.b);
  expect(calls).toBe(4);

  // Nobody known: never served from, nor written to, the cache.
  await loader.load(null);
  await loader.load(null);
  expect(calls).toBe(6);
  expect((await loader.load("b")).items).toEqual(lists.b);
  expect(calls).toBe(6);
});

test("any successful write through the API client forgets the shared workshop list", async () => {
  const original = globalThis.fetch;
  let listReads = 0;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    if (method === "GET" && url.startsWith("/api/workshops")) {
      listReads += 1;
      return new Response(JSON.stringify(page([])), { status: 200, headers: { "content-type": "application/json" } });
    }
    if (url.includes("/fails")) {
      return new Response(JSON.stringify({ detail: "no" }), { status: 500, headers: { "content-type": "application/json" } });
    }
    if (method === "DELETE") return new Response(null, { status: 204 });
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof fetch;
  try {
    await loadWorkshopList("reader");
    await loadWorkshopList("reader");
    expect(listReads).toBe(1);

    // The review editor's save (not under /workshops) and an outbox replay both go through apiFetch.
    await apiFetch("/review/workshop/w1", { method: "PATCH", body: JSON.stringify({ title: "x" }) });
    await loadWorkshopList("reader");
    expect(listReads).toBe(2);

    // The danger zone's delete answers 204 with no body.
    await apiFetch("/workshops/w1", { method: "DELETE" });
    await loadWorkshopList("reader");
    expect(listReads).toBe(3);

    // A read, or a refused write, changes nothing on the server and keeps the list.
    await apiFetch("/artisans");
    await expect(apiFetch("/fails", { method: "POST" })).rejects.toThrow();
    await loadWorkshopList("reader");
    expect(listReads).toBe(3);
  } finally {
    globalThis.fetch = original;
  }
});
