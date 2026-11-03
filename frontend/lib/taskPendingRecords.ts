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

export function fetchPendingRecordsOne(id: string): Promise<TaskPendingSummary> {
  return apiFetch<TaskPendingSummary>(`/tasks/${encodeURIComponent(id)}/pending-records`);
}

type Waiter = (answer: TaskPendingSummary | null) => void;

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
