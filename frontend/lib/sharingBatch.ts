/**
 * One API call per person, and an honest account of which ones failed.
 *
 * The sharing endpoints take a single person each — `POST /data-access/requests` has `ownerId`,
 * `POST /data-access/grants` has `granteeId` — so choosing five colleagues in one action is five
 * POSTs. That was left alone deliberately rather than widened into a list payload, and the reason is
 * the failure case, not the happy one.
 *
 * **Why not one batched call in a transaction.** The requirement on a partial failure is that the
 * three that worked STAY worked while the two that failed are named. A transaction gives the exact
 * opposite: one 409 rolls back the four grants that were fine, and the owner has to guess which
 * person poisoned the batch. So the atomicity that is the only real argument for a list payload is
 * the one property this screen must not have. What would be left is a server-side loop returning a
 * per-item result array — a second code path duplicating the logic of the singular route, which the
 * Android app also calls, with two sets of error strings to keep in step.
 *
 * **Why retrying is safe.** `_upsert_grant` in `backend/app/api/routes/data_access.py` resolves the
 * row through the unique `ownerId_granteeId` pair and updates it in place when it exists, so posting
 * the same grant twice converges on one row rather than making a second. Re-running the failures
 * therefore cannot double-grant the people who already succeeded, which is what lets this be a
 * client-side loop with a Retry button instead of a distributed transaction.
 *
 * This module is deliberately free of React and of the sharing screen's vocabulary: it takes people
 * and a sender, and returns who worked and who did not.
 */

import { ApiError } from "@/lib/api";

/** One person a batch acts on: the id the API needs, and the name the report has to be able to use. */
export type BatchTarget = { id: string; label: string };

export type BatchOutcome = {
  id: string;
  label: string;
  /** The server's own sentence, on failure. Absent when the call succeeded. */
  error?: string;
  /** Kept apart from the message so the report can tell "already has it" (409) from "the API is down". */
  status?: number;
};

export type BatchResult = { succeeded: BatchOutcome[]; failed: BatchOutcome[] };

/**
 * Calls in flight at once.
 *
 * Not one-at-a-time: the directory tops out around twenty people, and twenty sequential round trips
 * to a CloudFront-fronted API is long enough that a researcher starts clicking things. Not unbounded
 * either: the backend runs a single web worker against a pooled Postgres with a small connection
 * ceiling, and a burst of nineteen concurrent writes is how that pool has been exhausted before.
 * Four keeps the whole batch inside a couple of seconds while leaving the pool alone.
 */
const CONCURRENCY = 4;

/** What to show when `fetch` itself rejected, so the row does not read "Failed to fetch". */
function describeFailure(error: unknown): { message: string; status?: number } {
  if (error instanceof ApiError) return { message: error.message, status: error.status };
  if (error instanceof Error) {
    // A rejected fetch means the request never got an answer, which is a different thing from a
    // refusal — and the difference decides whether retrying is worth anything.
    if (/failed to fetch|networkerror|load failed/i.test(error.message)) {
      return { message: "The request never reached the server — check the connection and try again." };
    }
    return { message: error.message };
  }
  return { message: "The request failed for an unknown reason." };
}

/**
 * Send once per target, never throwing.
 *
 * Results come back in the order the targets were given rather than the order they finished, so the
 * report on screen reads in the same order as the picker the person chose from.
 */
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
