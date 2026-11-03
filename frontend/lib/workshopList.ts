import { listResource, onApiWrite } from "@/lib/api";
import type { PageResult, Workshop } from "@/lib/types";

export const WORKSHOP_LIST_REUSE_MS = 10_000;

export function createWorkshopListLoader(
  fetchList: () => Promise<PageResult<Workshop>>,
  now: () => number = Date.now
) {
  let cached: { owner: string; at: number; value: PageResult<Workshop> } | null = null;
  let inflight: { owner: string; request: Promise<PageResult<Workshop>> } | null = null;
  let generation = 0;

  function invalidate(): void {
    generation += 1;
    cached = null;
    inflight = null;
  }

  function load(owner: string | null): Promise<PageResult<Workshop>> {
    if (!owner) return fetchList();
    if ((cached && cached.owner !== owner) || (inflight && inflight.owner !== owner)) invalidate();
    if (cached && now() - cached.at < WORKSHOP_LIST_REUSE_MS) return Promise.resolve(cached.value);
    if (inflight) return inflight.request;
    const mine = generation;
    const request = fetchList()
      .then((value) => {
        if (mine === generation) cached = { owner, at: now(), value };
        return value;
      })
      .finally(() => {
        if (inflight?.request === request) inflight = null;
      });
    inflight = { owner, request };
    return request;
  }

  return { load, invalidate };
}

const shared = createWorkshopListLoader(() => listResource<Workshop>("/workshops", { pageSize: 100 }));

export const loadWorkshopList = shared.load;

export const invalidateWorkshopList = shared.invalidate;

onApiWrite(invalidateWorkshopList);
