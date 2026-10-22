import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { discardStep, memoryQueueStore, sendStepAgain, sendWithoutConflicts, trayItems, type SessionUnit } from "@/lib/questionnaire/queue";

/** The questionnaire outbox's fix tray on Field Repository (UNIFY B39): the shared tray, under the sessions list. */

const item = (artisanId: string, lineage: string, text: string) => ({
  artisanId,
  questionLineageId: lineage,
  text,
  origin: "TYPED" as const,
  attribution: "INDIVIDUAL" as const,
  sourceRecordingId: null
});

test("@qf:B39 Send again, Discard and Send without the conflicting answers act on this device's queue", async () => {
  const store = memoryQueueStore();
  const unit: SessionUnit = {
    id: "ck-1",
    sessionId: "s-1",
    createdAt: "2026-10-22T05:00:00Z",
    steps: [
      { kind: "participant", status: "FAILED", op: "add", artisanId: "a2", at: "2026-10-22T05:01:00Z", idempotencyKey: "p-1", error: "That artisan was merged." },
      {
        kind: "answers",
        status: "PENDING",
        key: "answers-1",
        items: [item("a1", "l1", "Mine")],
        error: "CONFLICT",
        conflicts: [{ artisanId: "a1", respondentId: null, questionLineageId: "l1", status: "CONFLICT", answer: null, current: { id: "x", version: 3, text: "Theirs" } }] as never
      }
    ]
  };
  await store.save(unit);
  expect(trayItems(await store.load()).length).toBeGreaterThan(0);
  await sendStepAgain(store, "ck-1", "participant:p-1");
  expect((await store.get("ck-1"))!.steps[0]).toMatchObject({ status: "PENDING" });
  await sendWithoutConflicts(store, "ck-1", "answers:answers-1");
  await discardStep(store, "ck-1", "participant:p-1");
  expect(store.units.size).toBe(0);
  const list = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionsList.tsx"), "utf8");
  expect(list).toContain("<OutboxTray />");
});
