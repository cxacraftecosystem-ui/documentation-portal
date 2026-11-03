import { ApiError } from "@/lib/api";

export type BatchTarget = { id: string; label: string };

export type BatchOutcome = {
  id: string;
  label: string;
  error?: string;
  status?: number;
};

export type BatchResult = { succeeded: BatchOutcome[]; failed: BatchOutcome[] };

const CONCURRENCY = 4;

function describeFailure(error: unknown): { message: string; status?: number } {
  if (error instanceof ApiError) return { message: error.message, status: error.status };
  if (error instanceof Error) {
    if (/failed to fetch|networkerror|load failed/i.test(error.message)) {
      return { message: "Couldn’t connect — check your connection and try again." };
    }
    return { message: error.message };
  }
  return { message: "The request failed for an unknown reason." };
}

export async function runPerPerson(
  targets: BatchTarget[],
  send: (target: BatchTarget) => Promise<unknown>,
  onProgress?: (done: number, total: number) => void
): Promise<BatchResult> {
  const outcomes: Array<BatchOutcome & { ok: boolean }> = new Array(targets.length);
  let cursor = 0;
  let done = 0;

  async function worker() {
    for (;;) {
      const index = cursor++;
      if (index >= targets.length) return;
      const target = targets[index];
      try {
        await send(target);
        outcomes[index] = { id: target.id, label: target.label, ok: true };
      } catch (error) {
        const { message, status } = describeFailure(error);
        outcomes[index] = { id: target.id, label: target.label, ok: false, error: message, status };
      }
      done += 1;
      onProgress?.(done, targets.length);
    }
  }

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, targets.length) }, worker));

  const succeeded: BatchOutcome[] = [];
  const failed: BatchOutcome[] = [];
  outcomes.forEach(({ ok, ...outcome }) => (ok ? succeeded : failed).push(outcome));
  return { succeeded, failed };
}
