import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import type { QueueApi } from "@/lib/questionnaireQueue";
import {
  appendStep,
  MAX_PARALLEL_CLIPS,
  memoryQueueStore,
  newSessionUnit,
  runUnit,
  type ByteTransport,
  type ClipStep
} from "@/lib/questionnaireQueue";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * DESIGN test 42 (web half), BUILD_PLAN §3.2: the ordered outbox. Session → participant changes →
 * clips → answers; the same Idempotency-Key and clientKey/clientClipKey on every retry; a failed
 * clip blocks nothing; at most 3 clips at once (1 on a slow link); a multipart upload resumes from
 * `GET /media/uploads/{clientClipKey}` and never re-sends a part the server already holds.
 * The API is mocked from the contract examples.
 */

type Call = { name: string; args: unknown[] };

function fakeApi(overrides: Partial<Record<keyof QueueApi, (...args: never[]) => unknown>> = {}) {
  const calls: Call[] = [];
  const ex = CONTRACT.examples;
  let inFlight = 0;
  let peak = 0;
  const base: Record<string, (...args: unknown[]) => unknown> = {
    createSession: async (body: unknown) => ({ ...(ex.Session as object), clientKey: (body as { clientKey: string }).clientKey, id: "s1" }),
    addParticipant: async () => ex.Session,
    patchParticipant: async () => ex.Session,
    uploadState: async () => {
      throw new ApiError(404, "Not Found", null);
    },
    presign: async (body: unknown) => ({ ...(ex.PresignResponse as object), objectKey: `media/u3/${(body as { clientClipKey: string }).clientClipKey}/leaf.m4a` }),
    multipartCreate: async () => ex.MultipartCreateResponse,
    multipartPresignParts: async (body: unknown) => ({
      parts: (body as { partNumbers: number[] }).partNumbers.map((n) => ({ partNumber: n, url: `https://bucket/part/${n}` }))
    }),
    multipartComplete: async () => ({ objectKey: "k" }),
    complete: async (body: unknown) => {
      inFlight += 1;
      peak = Math.max(peak, inFlight);
      await new Promise((r) => setTimeout(r, 5));
      inFlight -= 1;
      return { ...(ex.Recording as object), id: `r-${(body as { clientClipKey: string }).clientClipKey}`, clientClipKey: (body as { clientClipKey: string }).clientClipKey };
    },
    putAnswers: async () => ex.AnswersPutResponse
  };
  const api = new Proxy({} as QueueApi, {
    get(_t, name: string) {
      return async (...args: unknown[]) => {
        calls.push({ name, args });
        const fn = (overrides as Record<string, (...a: unknown[]) => unknown>)[name] ?? base[name];
        return fn(...args);
      };
    }
  });
  return { api, calls, peak: () => peak };
}

const transport = (): ByteTransport & { puts: string[] } => {
  const puts: string[] = [];
  return {
    puts,
    async put(url) {
      puts.push(url);
      return { etag: `"etag-${puts.length}"` };
    }
  };
};

function clip(key: string, size = 1000): ClipStep {
  return {
    kind: "clip",
    status: "PENDING",
    clientClipKey: key,
    blobKey: `blob-${key}`,
    sizeBytes: size,
    contentType: "audio/webm",
    extension: "webm",
    presign: { contentSha256: null, targets: [{ sectionId: "secD" }], subjectArtisanIds: ["a1"], deviceCapturedAt: "2026-07-01T12:54:00Z", clockOffsetMs: 0 },
    recording: {
      targets: [{ sectionId: "secD" }],
      subjects: [{ artisanId: "a1", role: "ANSWERED", named: false, repeatReason: null }],
      deviceCapturedAt: "2026-07-01T12:54:00Z",
      clockOffsetMs: 0,
      capturedAtSource: "DEVICE",
      durationMs: 1000,
      rosterCount: 1,
      contentSha256: null,
      continuationOfId: null,
      replacesRecordingId: null,
      ackedRecordingIds: []
    }
  };
}

const OPTS = { multipartThresholdBytes: 5 * 1024 * 1024, partSizeBytes: 8 * 1024 * 1024 };
const body = { ...(CONTRACT.examples.SessionCreate as object), clientKey: "c-1" } as Parameters<typeof newSessionUnit>[0];

test("offline start → add participant → 3 takes → answers: sent in order, each once", async () => {
  const store = memoryQueueStore();
  let unit = newSessionUnit(body);
  unit = appendStep(unit, clip("k1"));
  unit = appendStep(unit, { kind: "answers", status: "PENDING", items: [{ artisanId: "a1", questionLineageId: "lin_d3", text: "x", origin: "TYPED", attribution: "INDIVIDUAL", sourceRecordingId: null }] });
  unit = appendStep(unit, { kind: "participant", status: "PENDING", op: "add", artisanId: "a2", at: "2026-07-01T12:55:00Z", idempotencyKey: "part-1" });
  unit = appendStep(unit, clip("k2"));
  unit = appendStep(unit, clip("k3"));
  expect(unit.steps.map((s) => s.kind)).toEqual(["session", "participant", "clip", "clip", "clip", "answers"]);
  for (const k of ["k1", "k2", "k3"]) await store.putBlob(`blob-${k}`, new Blob(["abc"]));

  const { api, calls } = fakeApi();
  const t = transport();
  const done = await runUnit(unit, api, store, t, OPTS);
  const order = calls.map((c) => c.name);
  expect(order[0]).toBe("createSession");
  expect(order.indexOf("addParticipant")).toBeLessThan(order.indexOf("presign"));
  expect(order.lastIndexOf("complete")).toBeLessThan(order.indexOf("putAnswers"));
  expect(calls.filter((c) => c.name === "complete")).toHaveLength(3);
  expect(t.puts).toHaveLength(3);
  expect(done.steps.every((s) => s.status === "DONE")).toBe(true);
  // The answers carry the server session id the queue learnt.
  const answers = calls.find((c) => c.name === "putAnswers")!.args[0] as { sessionId: string }[];
  expect(answers[0].sessionId).toBe("s1");
  // A finished unit leaves the store, with its blobs.
  expect(store.units.size).toBe(0);
  expect(store.blobs.size).toBe(0);

  // Replaying the (already done) unit sends nothing.
  const again = fakeApi();
  await runUnit(done, again.api, store, transport(), OPTS);
  expect(again.calls).toHaveLength(0);
});

test("the same idempotency keys travel on every retry; bodies are frozen", async () => {
  const store = memoryQueueStore();
  let unit = appendStep(newSessionUnit(body), { kind: "participant", status: "PENDING", op: "add", artisanId: "a2", at: "2026-07-01T12:55:00Z", idempotencyKey: "part-1" });
  let failSession = true;
  const first = fakeApi({
    createSession: async () => {
      if (failSession) throw new TypeError("Failed to fetch");
      return { id: "s1" };
    }
  });
  unit = await runUnit(unit, first.api, store, transport(), OPTS);
  expect(unit.steps[0].status).toBe("PENDING");
  failSession = false;
  unit = await runUnit(unit, first.api, store, transport(), OPTS);
  const creates = first.calls.filter((c) => c.name === "createSession").map((c) => (c.args[0] as { clientKey: string }).clientKey);
  expect(creates).toEqual(["c-1", "c-1"]);
  const adds = first.calls.filter((c) => c.name === "addParticipant").map((c) => c.args[2]);
  expect(adds).toEqual(["part-1"]);
});

test("a failed clip blocks nothing; a missing file is never ticked", async () => {
  const store = memoryQueueStore();
  let unit = newSessionUnit(body);
  unit = appendStep(unit, clip("bad"));
  unit = appendStep(unit, clip("good"));
  unit = appendStep(unit, clip("gone"));
  unit = appendStep(unit, { kind: "answers", status: "PENDING", items: [] });
  await store.putBlob("blob-bad", new Blob(["x"]));
  await store.putBlob("blob-good", new Blob(["y"]));
  const { api, calls } = fakeApi({
    presign: (async (b: { clientClipKey: string }) => {
      if (b.clientClipKey === "bad") throw new TypeError("Failed to fetch");
      return { objectKey: "media/u3/x/leaf.m4a", url: "https://bucket/put", headers: {}, expiresAt: "" };
    }) as never
  });
  const out = await runUnit(unit, api, store, transport(), OPTS);
  const byKey = Object.fromEntries(out.steps.filter((s): s is ClipStep => s.kind === "clip").map((s) => [s.clientClipKey, s]));
  expect(byKey.good.status).toBe("DONE");
  expect(byKey.bad.status).toBe("PENDING");
  expect(byKey.gone.status).toBe("FAILED");
  expect(byKey.gone.error).toBe("MISSING_FILE");
  expect(calls.some((c) => c.name === "putAnswers")).toBe(true);
});

test("at most 3 clips at once, 1 on a slow link", async () => {
  for (const slowLink of [false, true]) {
    const store = memoryQueueStore();
    let unit = newSessionUnit(body);
    for (let i = 0; i < 8; i += 1) {
      unit = appendStep(unit, clip(`k${i}`));
      await store.putBlob(`blob-k${i}`, new Blob(["z"]));
    }
    const fake = fakeApi();
    await runUnit(unit, fake.api, store, transport(), { ...OPTS, slowLink });
    expect(fake.peak()).toBeLessThanOrEqual(slowLink ? 1 : MAX_PARALLEL_CLIPS);
    if (!slowLink) expect(fake.peak()).toBeGreaterThan(1);
  }
});

test("a multipart upload resumes from the server's parts and never re-sends one", async () => {
  const store = memoryQueueStore();
  const size = 20 * 1024 * 1024; // three 8 MiB parts
  let unit = { ...newSessionUnit(body), sessionId: "s1", steps: [] as ClipStep[] } as ReturnType<typeof newSessionUnit>;
  unit = appendStep(unit, clip("big", size));
  await store.putBlob("blob-big", new Blob([new Uint8Array(size)]));
  const { api, calls } = fakeApi({
    uploadState: (async () => ({ ...(CONTRACT.examples.UploadState as object) })) as never
  });
  const t = transport();
  const out = await runUnit(unit, api, store, t, OPTS);
  expect(calls.some((c) => c.name === "multipartCreate")).toBe(false);
  const asked = calls.find((c) => c.name === "multipartPresignParts")!.args[0] as { partNumbers: number[] };
  expect(asked.partNumbers).toEqual([2, 3]);
  expect(t.puts).toEqual(["https://bucket/part/2", "https://bucket/part/3"]);
  const completeParts = (calls.find((c) => c.name === "multipartComplete")!.args[0] as { parts: { partNumber: number }[] }).parts.map((p) => p.partNumber);
  expect(completeParts).toEqual([1, 2, 3]);
  expect(out.steps[0].status).toBe("DONE");
});

test("the client never names an uploaded file: the object key comes from presign", () => {
  const source = readFileSync(join(__dirname, "..", "lib", "questionnaireQueue.ts"), "utf8");
  expect(source).toContain("step.objectKey = signed.objectKey");
  expect(source).not.toMatch(/recordingFilename|renameMediaFile|mediaFileName/);
});
