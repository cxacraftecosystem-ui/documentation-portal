/**
 * The record forms read each reference list once per page mount: the workshop list shared by
 * `RecordSwitcher` and `WorkshopSelect`, and the artisan page shared by the two artisan pickers.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { sharedListResource } from "@/lib/sharedList";
import type { PageResult, Workshop } from "@/lib/types";
import { createWorkshopListLoader, WORKSHOP_LIST_REUSE_MS } from "@/lib/workshopList";

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
  await Promise.all([loader.load(), loader.load()]);
  expect(calls).toBe(1);
  now = WORKSHOP_LIST_REUSE_MS - 1;
  await loader.load();
  expect(calls).toBe(1);
  now = WORKSHOP_LIST_REUSE_MS;
  await loader.load();
  expect(calls).toBe(2);
  loader.invalidate();
  await loader.load();
  expect(calls).toBe(3);
});

test("a failed workshop read is not kept", async () => {
  let calls = 0;
  const loader = createWorkshopListLoader(async () => {
    calls += 1;
    if (calls === 1) throw new Error("offline");
    return page<Workshop>([]);
  });
  await expect(loader.load()).rejects.toThrow("offline");
  await loader.load();
  expect(calls).toBe(2);
});

test("the record forms read through the shared loaders", () => {
  expect(read("components/forms/RecordSwitcher.tsx")).toContain("loadWorkshopList()");
  expect(read("components/forms/WorkshopSelect.tsx")).toContain("loadWorkshopList()");
  expect(read("app/(protected)/workshops/page.tsx")).toContain("invalidateWorkshopList()");
  expect(read("components/forms/useServerSearchList.ts")).toContain("sharedListResource<T>(endpoint, { ...stableParams, pageSize: LIST_PAGE_CEILING })");
  const pickers = read("components/forms/recordPickers.ts");
  expect(pickers).toContain('sharedListResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })');
  expect(pickers).toContain('useRecordsOffPage<Artisan>("/artisans", artisanIds, artisans, artisanState !== "pending")');
});
