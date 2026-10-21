/**
 * `GET /workshops?pageSize=100` for the record forms, shared by `RecordSwitcher` and
 * `WorkshopSelect`, which mount together on every record form and each used to read it.
 *
 * Concurrent callers share one request; a successful answer is reused for a few seconds (long enough
 * for the controls of one page mount, short enough that a later visit reads afresh, as before).
 * A failure is never kept. Any workshop write calls `invalidateWorkshopList()`.
 */

import { listResource } from "@/lib/api";
import type { PageResult, Workshop } from "@/lib/types";

export const WORKSHOP_LIST_REUSE_MS = 10_000;

/** Pure apart from the injected fetcher and clock, so the unit spec drives it directly. */
export function createWorkshopListLoader(
  fetchList: () => Promise<PageResult<Workshop>>,
  now: () => number = Date.now
) {
  let cached: { at: number; value: PageResult<Workshop> } | null = null;
  let inflight: Promise<PageResult<Workshop>> | null = null;
  let generation = 0;

  function load(): Promise<PageResult<Workshop>> {
    if (cached && now() - cached.at < WORKSHOP_LIST_REUSE_MS) return Promise.resolve(cached.value);
    if (inflight) return inflight;
    const mine = generation;
    const request = fetchList()
      .then((value) => {
        if (mine === generation) cached = { at: now(), value };
        return value;
      })
      .finally(() => {
        if (inflight === request) inflight = null;
      });
    inflight = request;
    return request;
  }

  function invalidate(): void {
    generation += 1;
    cached = null;
    inflight = null;
  }

  return { load, invalidate };
}

const shared = createWorkshopListLoader(() => listResource<Workshop>("/workshops", { pageSize: 100 }));

export const loadWorkshopList = shared.load;

/** Forget the shared answer (a workshop was created, updated or deleted). */
export const invalidateWorkshopList = shared.invalidate;
