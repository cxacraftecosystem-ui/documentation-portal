/**
 * The pending-records aside on every task card is read through one batched request per page,
 * not one request per card (`lib/taskPendingRecords.ts`).
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  createPendingRecordsBatcher,
  PENDING_BATCH_MAX,
  type PendingBatchAnswer,
  type TaskPendingSummary
} from "@/lib/taskPendingRecords";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function summary(total: number): TaskPendingSummary {
  return { total, byType: { artisan: total }, reviewQuery: { taskId: "t" } };
}

function manualSchedule() {
  const queue: (() => void)[] = [];
  return { schedule: (run: () => void) => queue.push(run), run: () => queue.splice(0).forEach((f) => f()) };
}

test.describe("task pending records batcher", () => {
  test("every card mounted in one tick shares one request; absent ids draw nothing", async () => {
    const calls: string[][] = [];
    const one: string[] = [];
    const clock = manualSchedule();
    const load = createPendingRecordsBatcher(
      async (ids) => {
        calls.push(ids);
        return { tasks: { a: summary(2), c: summary(0) } } satisfies PendingBatchAnswer;
      },
      async (id) => {
        one.push(id);
        return summary(9);
      },
      clock.schedule
    );
    const answers = Promise.all([load("a"), load("b"), load("c"), load("a")]);
    clock.run();
    const [a, b, c, again] = await answers;
    expect(calls).toEqual([["a", "b", "c"]]);
    expect(one).toEqual([]);
    expect(a).toEqual(summary(2));
    expect(again).toEqual(summary(2));
    expect(b).toBeNull();
    expect(c).toEqual(summary(0));
  });

  test("more than fifty ids are split into requests of at most fifty", async () => {
    const calls: string[][] = [];
    const clock = manualSchedule();
    const load = createPendingRecordsBatcher(
      async (ids) => {
        calls.push(ids);
        return { tasks: {} };
      },
      async () => summary(1),
      clock.schedule
    );
    const ids = Array.from({ length: PENDING_BATCH_MAX + 3 }, (_, index) => `t${index}`);
    const answers = Promise.all(ids.map((id) => load(id)));
    clock.run();
    await answers;
    expect(calls.map((c) => c.length)).toEqual([PENDING_BATCH_MAX, 3]);
  });

  test("a failed batch draws nothing; an API without the batch route is read one by one", async () => {
    const clock = manualSchedule();
    const failing = createPendingRecordsBatcher(
      async () => {
        throw new ApiError(500, "boom", null);
      },
      async () => summary(4),
      clock.schedule
    );
    const failed = failing("x");
    clock.run();
    expect(await failed).toBeNull();

    const one: string[] = [];
    const legacy = createPendingRecordsBatcher(
      async () => {
        throw new ApiError(404, "Not Found", null);
      },
      async (id) => {
        one.push(id);
        if (id === "gone") throw new ApiError(404, "Not Found", null);
        return summary(3);
      },
      clock.schedule
    );
    const answers = Promise.all([legacy("x"), legacy("gone")]);
    clock.run();
    expect(await answers).toEqual([summary(3), null]);
    expect(one).toEqual(["x", "gone"]);
  });

  test("the card reads through the batcher, not the single route", () => {
    const card = read("components/tasks/TaskPendingRecords.tsx");
    expect(card).toContain("loadTaskPendingRecords(taskId)");
    expect(card).not.toContain("/pending-records`");
  });
});
