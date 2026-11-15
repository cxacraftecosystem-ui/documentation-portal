import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { stepLabel } from "@/components/questionnaire/OutboxTray";
import { reviewSummary } from "@/components/questionnaire/ReviewFinish";
import { uploadBlocked } from "@/components/questionnaire/UploadSheet";
import { ApiError } from "@/lib/api";
import { questionProgress } from "@/lib/questionnaire/coverageCache";
import { qs } from "@/lib/questionnaire/qs";
import {
  dropQueuedRecordBy,
  existingSessionUnit,
  isRecordByConflict,
  memoryQueueStore,
  queuedRecordBy,
  runUnit,
  sendDetails,
  sendStepAgain,
  stepKey,
  trayItems,
  withRecordBy,
  type DetailsStep,
  type QueueApi,
  type SessionUnit
} from "@/lib/questionnaire/queue";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");
const OPTS = { multipartThresholdBytes: 8 * 1024 * 1024, partSizeBytes: 5 * 1024 * 1024 };
const noBytes = { put: async () => ({ etag: null }) } as never;

const CHANGED = "Changed on another device.";
const conflict = () =>
  new ApiError(409, CHANGED, { detail: { code: "STALE_VERSION", message: CHANGED, current: { id: "s-1", version: 8, recordBy: "SECTION" }, diff: { recordBy: { current: "SECTION", yours: "QUESTION" } } } });

function fakeApi(patch: (body: { version: number; recordBy: string }) => unknown, version = 8) {
  const calls: { name: string; body?: unknown }[] = [];
  const api = new Proxy({} as QueueApi, {
    get(_t, name: string) {
      return async (...args: unknown[]) => {
        calls.push({ name, body: args[1] });
        if (name === "patchSession") return patch(args[1] as { version: number; recordBy: string });
        if (name === "getSession") return { id: "s-1", version };
        throw new Error(`unexpected ${name}`);
      };
    }
  });
  return { api, calls };
}

test("@qf:Q1 a queued Record by change that another device beat is held as not sent, never forced over their value", async () => {
  const { api, calls } = fakeApi(() => {
    throw conflict();
  });
  expect(isRecordByConflict(conflict())).toBe(true);
  expect(isRecordByConflict(new ApiError(409, "STALE_VERSION", { detail: { code: "STALE_VERSION" } }))).toBe(false);
  await expect(sendDetails(api, "s-1", { version: 7, recordBy: "QUESTION" })).rejects.toBeInstanceOf(ApiError);
  expect(calls.map((c) => c.name)).toEqual(["patchSession"]);

  const store = memoryQueueStore();
  const unit: SessionUnit = withRecordBy(existingSessionUnit({ id: "s-1", clientKey: "ck-s-1" }), "QUESTION", 7, "d1");
  await store.save(unit);
  calls.length = 0;
  await runUnit(structuredClone(unit), api, store, noBytes, OPTS);
  expect(calls.map((c) => c.name)).toEqual(["patchSession"]);
  const held = await store.get(unit.id);
  const step = held?.steps.find((s): s is DetailsStep => s.kind === "details");
  expect(step).toMatchObject({ status: "FAILED", recordBy: "QUESTION" });
  expect(step?.error).toBeTruthy();
  expect(queuedRecordBy(held)).toBeNull();
  expect(trayItems(held ? [held] : []).map((i) => [i.state, stepLabel(i.step)])).toEqual([["failed", "Record by change"]]);
});

test("@qf:Q1 Send again on a held Record by change keeps this device's value on the fresh version", async () => {
  const store = memoryQueueStore();
  const base: SessionUnit = withRecordBy(existingSessionUnit({ id: "s-1", clientKey: "ck-s-1" }), "QUESTION", 7, "d1");
  const failed: SessionUnit = { ...base, steps: base.steps.map((s) => ({ ...s, status: "FAILED" as const, error: CHANGED })) };
  await store.save(failed);
  const key = stepKey(failed.steps[0]);
  const again = await sendStepAgain(store, failed.id, key);
  expect(again?.steps[0]).toMatchObject({ kind: "details", status: "PENDING", force: true });
  expect(queuedRecordBy(again)).toBe("QUESTION");
  let first = true;
  const { api, calls } = fakeApi((body) => {
    if (first) {
      first = false;
      throw conflict();
    }
    return { id: "s-1", version: 9, recordBy: body.recordBy };
  });
  await runUnit(structuredClone(again!), api, store, noBytes, OPTS);
  expect(calls.map((c) => c.name)).toEqual(["patchSession", "getSession", "patchSession"]);
  expect(calls[2].body).toEqual({ version: 8, recordBy: "QUESTION" });
  expect(await store.get(failed.id)).toBeNull();
});

test("@qf:Q1 a refused Record by step stops steering capture, and a direct save drops the queued one", () => {
  const unit = withRecordBy(existingSessionUnit({ id: "s-1", clientKey: "ck-s-1" }), "QUESTION", 3, "d1");
  expect(queuedRecordBy(unit)).toBe("QUESTION");
  const refused: SessionUnit = { ...unit, steps: unit.steps.map((s) => ({ ...s, status: "FAILED" as const, error: "You can't do that here." })) };
  expect(queuedRecordBy(refused)).toBeNull();
  expect(dropQueuedRecordBy(unit)).toBeNull();
  const withAnswers: SessionUnit = { ...unit, steps: [...unit.steps, { kind: "answers", status: "PENDING", key: "a1", items: [] }] };
  expect(dropQueuedRecordBy(withAnswers)?.steps.map((s) => s.kind)).toEqual(["answers"]);
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  const change = ws.slice(ws.indexOf("async function changeRecordBy"), ws.indexOf("async function identicalPrompt"));
  expect(change).toContain("await clearQueuedRecordBy(unitSession)");
  expect(change).toMatch(/finally \{\n\s+setRecordByLocal\(null\);\n\s+\}/);
  expect(change).toContain("if (isRecordByConflict(error)) void load();");
  expect(ws).toContain("if (was && !queuedMode) void load();");
});

test("@qf:Q4 Upload stays off until the server has said which picked files are already uploaded", () => {
  expect(uploadBlocked({ ready: 1, hashing: false, shaKey: "aaa", checkedKey: "", online: true })).toBe(true);
  expect(uploadBlocked({ ready: 1, hashing: false, shaKey: "aaa,bbb", checkedKey: "aaa", online: true })).toBe(true);
  expect(uploadBlocked({ ready: 1, hashing: false, shaKey: "aaa", checkedKey: "aaa", online: true })).toBe(false);
  expect(uploadBlocked({ ready: 1, hashing: false, shaKey: "aaa", checkedKey: "", online: false })).toBe(false);
  expect(uploadBlocked({ ready: 1, hashing: true, shaKey: "", checkedKey: "", online: true })).toBe(true);
  expect(uploadBlocked({ ready: 0, hashing: false, shaKey: "aaa", checkedKey: "aaa", online: true })).toBe(true);
});

test("@qf:Q6 Review uses one source: while a take is still uploading the totals and Still to ask come from this device", () => {
  const sections = [
    { id: "sA", code: "A", title: "Origin", questions: [{ lineageId: "a1" }, { lineageId: "a2" }] },
    { id: "sE", code: "E", title: "Tools", questions: [{ lineageId: "e1" }, { lineageId: "e2" }, { lineageId: "e3" }] }
  ];
  const progress = questionProgress({
    sections,
    recordings: [{ targets: [{ sectionId: "sA", questionLineageId: null }], subjects: [{ role: "ANSWERED" }] }],
    answers: [],
    pending: [{ targets: [{ sectionId: "sE", questionLineageId: null }], subjects: [{ role: "ANSWERED" }] }]
  });
  const server = {
    sessionId: "s-1",
    answered: 2,
    total: 5,
    sectionsDone: 1,
    pendingUploads: 0,
    needsDecision: [],
    stillToAsk: [{ sectionId: "sE", code: "E", title: "Tools", answered: 0, total: 3, lineages: [] }]
  };
  const uploading = reviewSummary(sections, progress, server, 1);
  expect(uploading).toEqual({ answered: 5, total: 5, sectionsDone: 2, still: [] });
  const landed = reviewSummary(sections, progress, server, 0);
  expect(landed).toMatchObject({ answered: 2, total: 5, still: [{ code: "E", left: 3 }] });
  expect(qs("outboxStepRecordBy")).toBe("Record by change");
});
