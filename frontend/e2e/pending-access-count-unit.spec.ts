/**
 * The access-roster badge count is one store for every mount (`usePendingAccessCount`): the nav
 * and an admin page share one request, one timer, and a refresh from either updates both.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { createPendingCountStore, REFRESH_MS, type PollTimers } from "@/components/hooks/usePendingAccessCount";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function fakeIntervals() {
  const running = new Map<number, () => void>();
  let seq = 0;
  const timers: PollTimers = {
    every: (run) => {
      seq += 1;
      running.set(seq, run);
      return seq;
    },
    stop: (handle) => void running.delete(handle as number)
  };
  return { timers, tick: () => [...running.values()].forEach((run) => run()), count: () => running.size };
}

const settle = async () => {
  for (let i = 0; i < 5; i += 1) await Promise.resolve();
};

test("two mounts share one request and one timer", async () => {
  let calls = 0;
  const clock = fakeIntervals();
  const store = createPendingCountStore(async () => {
    calls += 1;
    return 3;
  }, clock.timers);
  const nav: (number | null)[] = [];
  const page: (number | null)[] = [];
  const stopNav = store.subscribe((value) => nav.push(value));
  const stopPage = store.subscribe((value) => page.push(value));
  await settle();
  expect(calls).toBe(1);
  expect(clock.count()).toBe(1);
  expect(nav.at(-1)).toBe(3);
  expect(page.at(-1)).toBe(3);

  clock.tick();
  await settle();
  expect(calls).toBe(2);

  stopPage();
  expect(clock.count()).toBe(1);
  stopNav();
  expect(clock.count()).toBe(0);
  expect(REFRESH_MS).toBe(60_000);
});

test("a refresh from one mount updates every badge, and a stale answer never wins", async () => {
  const answers: ((value: number) => void)[] = [];
  const store = createPendingCountStore(() => new Promise<number>((resolve) => answers.push(resolve)), fakeIntervals().timers);
  const nav: (number | null)[] = [];
  const page: (number | null)[] = [];
  store.subscribe((value) => nav.push(value));
  store.subscribe((value) => page.push(value));
  expect(answers).toHaveLength(1);
  void store.refresh(true);
  expect(answers).toHaveLength(2);
  answers[1](0);
  await settle();
  answers[0](1);
  await settle();
  expect(nav.at(-1)).toBe(0);
  expect(page.at(-1)).toBe(0);
});

test("a failed read keeps the last count", async () => {
  let fail = false;
  const store = createPendingCountStore(async () => {
    if (fail) throw new Error("offline");
    return 2;
  }, fakeIntervals().timers);
  const seen: (number | null)[] = [];
  store.subscribe((value) => seen.push(value));
  await settle();
  fail = true;
  await store.refresh(true);
  expect(seen.at(-1)).toBe(2);
});

test("the hook reads the shared store", () => {
  const hook = read("components/hooks/usePendingAccessCount.ts");
  expect(hook).toContain("const sharedStore = createPendingCountStore(accessRosterPendingCount);");
  expect(hook).toContain("return sharedStore.subscribe(setPending);");
  expect(hook).not.toContain("window.setInterval(load");
});
