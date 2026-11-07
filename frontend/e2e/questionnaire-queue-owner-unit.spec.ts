import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { SessionCreate } from "@/lib/questionnaire/api";
import * as queue from "@/lib/questionnaire/queue";
import { enqueueStep, memoryQueueStore, newSessionUnit, type SessionUnit, type Step } from "@/lib/questionnaire/queue";

type OwnerApi = {
  stampOwner: (unit: SessionUnit, owner: string | null) => SessionUnit;
  drainableUnits: (store: ReturnType<typeof memoryQueueStore>, owner: string | null) => Promise<SessionUnit[]>;
  heldUnits: (units: SessionUnit[], owner: string | null) => { otherAccount: string[]; unowned: string[] };
  unitsOwnedBy: (units: SessionUnit[], owner: string | null) => SessionUnit[];
};

const owners = queue as unknown as OwnerApi;
const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const answers = (value: string): Step => ({
  kind: "answers",
  status: "PENDING",
  items: [{ artisanId: "artisan-1", questionLineageId: "q-1", value } as never]
});

function unitFor(clientKey: string): SessionUnit {
  return newSessionUnit({ clientKey } as unknown as SessionCreate);
}

async function queued(store: ReturnType<typeof memoryQueueStore>, clientKey: string, owner: string | null) {
  const base = typeof owners.stampOwner === "function" ? owners.stampOwner(unitFor(clientKey), owner) : unitFor(clientKey);
  await enqueueStep(store, base, answers(clientKey));
  return store.get(clientKey);
}

test.describe("Questionnaire offline queue is bound to the account that queued it", () => {
  test("a queued session keeps the account that queued it", async () => {
    const store = memoryQueueStore();
    const stored = await queued(store, "ck-a", "designer-a");
    expect(stored?.ownerUserId).toBe("designer-a");
  });

  test("the next account on the browser drains only its own sessions", async () => {
    const store = memoryQueueStore();
    await queued(store, "ck-a", "designer-a");
    await queued(store, "ck-b", "designer-b");
    await store.save({ ...unitFor("ck-legacy"), steps: [answers("legacy")] });
    expect(typeof owners.drainableUnits).toBe("function");
    expect((await owners.drainableUnits(store, "designer-b")).map((u) => u.id)).toEqual(["ck-b"]);
    expect((await owners.drainableUnits(store, "designer-a")).map((u) => u.id)).toEqual(["ck-a"]);
    expect(await owners.drainableUnits(store, null)).toEqual([]);
  });

  test("another account's and unowned sessions are held, not shown", async () => {
    const store = memoryQueueStore();
    await queued(store, "ck-a", "designer-a");
    await queued(store, "ck-b", "designer-b");
    await store.save({ ...unitFor("ck-legacy"), steps: [answers("legacy")] });
    const all = await store.load();
    expect(owners.unitsOwnedBy(all, "designer-b").map((u) => u.id)).toEqual(["ck-b"]);
    expect(owners.heldUnits(all, "designer-b")).toEqual({ otherAccount: ["ck-a"], unowned: ["ck-legacy"] });
    expect(owners.heldUnits(all, null)).toEqual({ otherAccount: [], unowned: [] });
  });

  test("a step for a session another account queued is refused, not merged into it", async () => {
    const store = memoryQueueStore();
    await queued(store, "ck-a", "designer-a");
    const mine = owners.stampOwner(unitFor("ck-a"), "designer-b");
    await expect(enqueueStep(store, mine, answers("b"))).rejects.toThrow(/another account/i);
    expect((await store.get("ck-a"))?.steps).toHaveLength(1);
  });

  test("the drain, the list and every enqueue go through the owner", () => {
    const hook = read("components", "questionnaire", "useSessionQueue.ts");
    expect(hook).not.toContain("for (const unit of await getStore().load())");
    expect(hook).toContain("drainableUnits(getStore(), owner)");
    expect(hook).toContain("units: unitsOwnedBy(all, owner)");
    expect(hook).toContain("stampOwner(baseUnit(session), owner)");
    expect(hook).toContain("stampOwner(unit, owner)");
    expect(hook).toContain("useAuth()");
    const tray = read("components", "questionnaire", "OutboxTray.tsx");
    expect(tray).toContain("queue.held.otherAccount");
    expect(tray).toContain("settleHeld(");
  });
});
