import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  enqueueStep,
  existingSessionUnit,
  memoryQueueStore,
  runUnit,
  sendStepAgain,
  stepKey,
  type ParticipantStep,
  type QueueApi,
  type SessionUnit
} from "@/lib/questionnaire/queue";

/**
 * A step the server refused for good stays refused, with its reason, until a person acts on it.
 * Identical in both portals (the queue is shared).
 *
 * `runUnit` used to re-send every FAILED step on every drain — and the sessions list drains on every
 * page load — so a participant the server refused with 409 "That artisan was removed." was sent
 * again forever, and each re-send replaced that reason in the fix tray with whatever came back. The
 * automatic retry (`useSessionQueue.hasRetryableWork`) only ever meant PENDING; now the runner agrees.
 */

const OPTS = { multipartThresholdBytes: 8 * 1024 * 1024, partSizeBytes: 5 * 1024 * 1024 };
const REMOVED = "That artisan was removed.";
const noBytes = { put: async () => ({ etag: null }) } as never;

function participantApi(answers: Array<() => unknown>) {
  const calls: string[] = [];
  const api = new Proxy({} as QueueApi, {
    get(_t, name: string) {
      return async () => {
        calls.push(name);
        if (name !== "addParticipant") throw new Error(`unexpected ${name}`);
        const next = answers.shift();
        if (!next) throw new Error("no answer left: the step was sent more often than expected");
        return next();
      };
    }
  });
  return { api, calls };
}

const refuse = () => {
  throw new ApiError(409, REMOVED, { detail: REMOVED });
};
const ok = () => ({ id: "s1" });

async function seeded() {
  const store = memoryQueueStore();
  const base = existingSessionUnit({ id: "s1", clientKey: "c1" });
  const step: ParticipantStep = { kind: "participant", status: "PENDING", op: "add", artisanId: "a2", at: "2026-10-31T05:00:00Z", idempotencyKey: "p-1" };
  await enqueueStep(store, base, step);
  return { store, id: base.id, key: stepKey(step) };
}

async function drain(store: ReturnType<typeof memoryQueueStore>, api: QueueApi) {
  for (const unit of await store.load()) await runUnit(unit, api, store, noBytes, OPTS);
}

function participant(unit: SessionUnit | null): ParticipantStep | undefined {
  return unit?.steps.find((s): s is ParticipantStep => s.kind === "participant");
}

test("a refused step is not re-sent by the next drain, and its reason survives", async () => {
  const { store, id } = await seeded();
  const { api, calls } = participantApi([refuse]);
  await drain(store, api);
  expect(calls).toEqual(["addParticipant"]);
  expect(participant(await store.get(id))).toMatchObject({ status: "FAILED", error: REMOVED });

  // Two more page loads. Nothing is sent; the reason the tray shows is still the server's own.
  await drain(store, api);
  await drain(store, api);
  expect(calls).toEqual(["addParticipant"]);
  expect(participant(await store.get(id))).toMatchObject({ status: "FAILED", error: REMOVED });
});

test("Send again re-sends a refused step exactly once", async () => {
  const { store, id, key } = await seeded();
  const { api, calls } = participantApi([refuse, refuse, ok]);
  await drain(store, api);
  expect(calls).toHaveLength(1);

  await sendStepAgain(store, id, key);
  await drain(store, api);
  await drain(store, api);
  expect(calls).toHaveLength(2);
  expect(participant(await store.get(id))).toMatchObject({ status: "FAILED", error: REMOVED });

  await sendStepAgain(store, id, key);
  await drain(store, api);
  expect(calls).toHaveLength(3);
  expect(await store.get(id), "everything landed, so the unit leaves the outbox").toBeNull();
});

test("a retryable failure (5xx, 429, network) stays waiting and IS sent again", async () => {
  for (const failure of [
    () => {
      throw new ApiError(503, "Service Unavailable", null);
    },
    () => {
      throw new ApiError(429, "Too Many Requests", null);
    },
    () => {
      throw new TypeError("Failed to fetch");
    }
  ]) {
    const { store, id } = await seeded();
    const { api, calls } = participantApi([failure, ok]);
    await drain(store, api);
    expect(participant(await store.get(id))?.status).toBe("PENDING");
    await drain(store, api);
    expect(calls).toHaveLength(2);
    expect(await store.get(id)).toBeNull();
  }
});
