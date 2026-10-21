/**
 * `GET /workshops?pageSize=100` for the record forms, shared by `RecordSwitcher` and
 * `WorkshopSelect`, which mount together on every record form and each used to read it.
 *
 * Concurrent callers share one request; a successful answer is reused for a few seconds (long enough
 * for the controls of one page mount, short enough that a later visit reads afresh, as before).
 * A failure is never kept.
 *
 * The answer belongs to ONE account: it is keyed by the reader's user id, so a different account in
 * this tab (sign-out then sign-in) never receives the previous one's list, and an anonymous caller is
 * never served from or written to the cache. `clearSession` in `AuthProvider` forgets it outright.
 *
 * Invalidation is centralised: every successful write that goes through the API client
 * (`apiFetch`, which the review editor, the danger zone and the offline outbox's replays all use)
 * forgets the list — see `onApiWrite` in `lib/api.ts`. The workshops page's explicit
 * `invalidateWorkshopList()` calls stay as belt and braces.
 */

import { listResource, onApiWrite } from "@/lib/api";
import type { PageResult, Workshop } from "@/lib/types";

export const WORKSHOP_LIST_REUSE_MS = 10_000;

/** Pure apart from the injected fetcher and clock, so the unit spec drives it directly. */
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

  /** `owner` is the signed-in user's id; null (nobody known) always reads afresh and keeps nothing. */
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

/** The workshop list as `userId` may see it. */
export const loadWorkshopList = shared.load;

/** Forget the shared answer (a write happened, or the session ended). */
export const invalidateWorkshopList = shared.invalidate;

onApiWrite(invalidateWorkshopList);
