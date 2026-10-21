/**
 * The records a task produced that still wait for review, read for every card on a page at once.
 *
 * `/tasks` draws twelve cards and an expanded row of the accountability board can draw dozens; each
 * card used to ask `GET /tasks/{id}/pending-records` on its own. Every card that mounts in the same
 * tick now joins one `GET /tasks/pending-records?ids=` (at most 50 ids per request). An id the
 * answer leaves out (not found, not visible) reads as `null` — exactly what a refused single read
 * gave the card: nothing drawn.
 */

import { ApiError, apiFetch } from "@/lib/api";

export const PENDING_BATCH_MAX = 50;

export type TaskPendingSummary = {
  total: number;
  byType: Record<string, number>;
  reviewQuery: Record<string, string>;
};

export type PendingBatchAnswer = { tasks: Record<string, TaskPendingSummary> };

export function fetchPendingRecordsBatch(ids: string[]): Promise<PendingBatchAnswer> {
  return apiFetch<PendingBatchAnswer>(`/tasks/pending-records?ids=${ids.map(encodeURIComponent).join(",")}`);
}

/** The single-task route: only a fallback for an API that predates the batch one. */
export function fetchPendingRecordsOne(id: string): Promise<TaskPendingSummary> {
  return apiFetch<TaskPendingSummary>(`/tasks/${encodeURIComponent(id)}/pending-records`);
}

type Waiter = (answer: TaskPendingSummary | null) => void;

/**
 * Collects ids asked for within one tick and answers them from as few requests as possible.
 * Pure apart from the injected fetchers and `schedule`, so the unit spec drives it directly.
 */
export function createPendingRecordsBatcher(
  fetchBatch: (ids: string[]) => Promise<PendingBatchAnswer>,
  fetchOne: (id: string) => Promise<TaskPendingSummary>,
  schedule: (run: () => void) => void = (run) => queueMicrotask(run)
) {
  let pending = new Map<string, Waiter[]>();
  let scheduled = false;

  async function answerSlice(slice: string[]): Promise<Record<string, TaskPendingSummary | null>> {
    try {
      const tasks = (await fetchBatch(slice)).tasks ?? {};
      return Object.fromEntries(slice.map((id) => [id, tasks[id] ?? null]));
    } catch (error) {
      // An API older than the batch route answers 404/405 for it; read one by one as before.
      if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
        const each = await Promise.all(slice.map((id) => fetchOne(id).catch(() => null)));
        return Object.fromEntries(slice.map((id, index) => [id, each[index]]));
      }
      return Object.fromEntries(slice.map((id) => [id, null]));
    }
  }

  async function flush() {
    scheduled = false;
    const batch = pending;
    pending = new Map();
    const ids = [...batch.keys()];
    const slices: string[][] = [];
    for (let start = 0; start < ids.length; start += PENDING_BATCH_MAX) slices.push(ids.slice(start, start + PENDING_BATCH_MAX));
    await Promise.all(
      slices.map(async (slice) => {
        const answers = await answerSlice(slice);
        for (const id of slice) for (const waiter of batch.get(id) ?? []) waiter(answers[id] ?? null);
      })
    );
  }

  return function load(id: string): Promise<TaskPendingSummary | null> {
    return new Promise((resolve) => {
      const waiters = pending.get(id) ?? [];
      waiters.push(resolve);
      pending.set(id, waiters);
      if (!scheduled) {
        scheduled = true;
        schedule(() => void flush());
      }
    });
  };
}

export const loadTaskPendingRecords = createPendingRecordsBatcher(fetchPendingRecordsBatch, fetchPendingRecordsOne);
