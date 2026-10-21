import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { answerFallback } from "@/components/questionnaire/AnswerBox";
import { hasRetryableWork, nextRetryDelay, RETRY_FIRST_MS, RETRY_MAX_MS } from "@/components/questionnaire/useSessionQueue";
import { ApiError } from "@/lib/api";
import type { AnswerItem, AnswerResult } from "@/lib/questionnaireApi";
import type { QueueApi } from "@/lib/questionnaireQueue";
import {
  answerConflicts,
  appendStep,
  clipUploadState,
  enqueueStep,
  existingSessionUnit,
  MAX_PARALLEL_CLIPS,
  memoryQueueStore,
  newSessionUnit,
  resolveAnswerConflict,
  runUnit,
  sessionUnitKey,
  signedParts,
  stepKey,
  type AnswersStep,
  type ByteTransport,
  type ClipStep
} from "@/lib/questionnaireQueue";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * DESIGN test 42 (web half), BUILD_PLAN §3.2: the ordered outbox. Session → participant changes →
 * clips → answers; the same Idempotency-Key and clientKey/clientClipKey on every retry; a failed
 * clip blocks nothing; at most 3 clips at once (1 on a slow link); a multipart upload resumes from
 * `GET /media/uploads/{clientClipKey}` and never re-sends a part the server already holds.
 * The API is mocked from the contract examples, and where the contract has no example the fake
 * answers in the server's own shape (`backend/app/api/routes/media.py`). The fake used to answer
 * presign-parts with `{parts}` while the server sends `{urls}`, which is how audit C3 shipped; the
 * same calls run against a real backend and bucket in `questionnaire-v2-stack.spec.ts`.
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
    // The server's shape: part number (as a string key) → signed URL (media.py presign_multipart_parts).
    multipartPresignParts: async (body: unknown) => ({
      urls: Object.fromEntries((body as { partNumbers: number[] }).partNumbers.map((n) => [String(n), `https://bucket/part/${n}`]))
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

test("C3: part URLs are read from the server's {urls} map, and a missing one stops the upload", () => {
  expect(signedParts({ urls: { "2": "https://b/2", "3": "https://b/3" } }, [2, 3])).toEqual([
    { partNumber: 2, url: "https://b/2" },
    { partNumber: 3, url: "https://b/3" }
  ]);
  // The old reading: `{parts: [...]}` is not what the server sends, and must not pass for it.
  expect(() => signedParts({ parts: [{ partNumber: 1, url: "x" }] } as never, [1])).toThrow(/did not sign every part/);
  expect(() => signedParts({ urls: { "1": "https://b/1" } }, [1, 2])).toThrow();
});

test("C3: a take over the threshold uploads through multipart with the server's part size", async () => {
  const store = memoryQueueStore();
  const size = 12 * 1024 * 1024;
  let unit = { ...newSessionUnit(body), sessionId: "s1", steps: [] as ClipStep[] } as ReturnType<typeof newSessionUnit>;
  unit = appendStep(unit, clip("large", size));
  await store.putBlob("blob-large", new Blob([new Uint8Array(size)]));
  const { api, calls } = fakeApi({
    multipartCreate: (async () => ({ objectKey: "media/u3/x/leaf.m4a", uploadId: "up1", partSize: 5 * 1024 * 1024, partsDone: [] })) as never
  });
  const t = transport();
  const out = await runUnit(unit, api, store, t, OPTS);
  // 12 MiB in the server's 5 MiB parts is three parts, whatever the config said (8 MiB).
  expect((calls.find((c) => c.name === "multipartPresignParts")!.args[0] as { partNumbers: number[] }).partNumbers).toEqual([1, 2, 3]);
  expect(t.puts).toEqual(["https://bucket/part/1", "https://bucket/part/2", "https://bucket/part/3"]);
  expect(out.steps[0].status).toBe("DONE");
});

test("C4: a rebuilt session with no clientKey queues under its id; the store refuses a unit with no key", async () => {
  expect(sessionUnitKey({ id: "s9", clientKey: null })).toBe("s9");
  expect(sessionUnitKey({ id: "s9", clientKey: "c-9" })).toBe("c-9");
  expect(existingSessionUnit({ id: "s9", clientKey: null }).id).toBe("s9");
  const store = memoryQueueStore();
  // IndexedDB's keyPath refuses a null id; the fake now does too, as the real store always did.
  await expect(store.save({ id: null as unknown as string, sessionId: "s9", createdAt: "", steps: [] })).rejects.toThrow();
  await enqueueStep(store, existingSessionUnit({ id: "s9", clientKey: null }), clip("k9"));
  await store.putBlob("blob-k9", new Blob(["abc"]));
  const [stored] = await store.load();
  expect(stored.id).toBe("s9");
  const { api, calls } = fakeApi();
  await runUnit(stored, api, store, transport(), OPTS);
  expect((calls.find((c) => c.name === "complete")!.args[0] as { linkedRecordId: string }).linkedRecordId).toBe("s9");
  expect(store.units.size).toBe(0);
});

test("C5: a take queued while another uploads is neither overwritten nor dropped", async () => {
  const store = memoryQueueStore();
  const base = { ...newSessionUnit(body), sessionId: "s1", steps: [] as ClipStep[] } as ReturnType<typeof newSessionUnit>;
  await enqueueStep(store, base, clip("first"));
  await store.putBlob("blob-first", new Blob(["a"]));
  let release!: () => void;
  const gate = new Promise<void>((resolve) => (release = resolve));
  let presigned!: () => void;
  const presignCalled = new Promise<void>((resolve) => (presigned = resolve));
  const { api } = fakeApi({
    presign: (async (b: { clientClipKey: string }) => {
      if (b.clientClipKey === "first") {
        presigned();
        await gate;
      }
      return { objectKey: `media/u3/${b.clientClipKey}/leaf.m4a`, url: "https://bucket/put", headers: {}, expiresAt: "" };
    }) as never
  });
  const [loaded] = await store.load();
  const running = runUnit(loaded, api, store, transport(), OPTS);
  await presignCalled;
  // The researcher records a second take while the first is still uploading.
  await enqueueStep(store, base, clip("second"));
  await store.putBlob("blob-second", new Blob(["b"]));
  release();
  await running;
  const [after] = await store.load();
  const byKey = Object.fromEntries(after.steps.filter((s): s is ClipStep => s.kind === "clip").map((s) => [s.clientClipKey, s.status]));
  expect(byKey).toEqual({ first: "DONE", second: "PENDING" });
  // The next pass (the re-drain) sends it, and only then does the unit leave the store.
  await runUnit(after, fakeApi().api, store, transport(), OPTS);
  expect(store.units.size).toBe(0);
});

const conflict = (version: number): AnswerResult => ({
  artisanId: "a1",
  questionLineageId: "lin_d3",
  status: "CONFLICT",
  answer: null,
  current: { id: "ans1", version, text: "Chisels.", origin: "TYPED", attribution: "INDIVIDUAL", pinned: false, sourceRecordingId: null, editedBy: { id: "u9", name: "Nandini" }, editedAt: "2026-07-07T05:34:00Z" }
});
const typed = { artisanId: "a1", questionLineageId: "lin_d3", text: "Mostly chisels.", origin: "TYPED", attribution: "INDIVIDUAL", sourceRecordingId: null } as Omit<AnswerItem, "sessionId">;

test("H18: an answer that conflicts is kept for P9, never marked done and dropped", async () => {
  for (const choice of ["mine", "theirs"] as const) {
    const store = memoryQueueStore();
    const base = { ...newSessionUnit(body), sessionId: "s1", steps: [] } as ReturnType<typeof newSessionUnit>;
    await enqueueStep(store, base, { kind: "answers", status: "PENDING", items: [typed] });
    const first = fakeApi({ putAnswers: (async () => ({ results: [conflict(4)] })) as never });
    const [loaded] = await store.load();
    await runUnit(loaded, first.api, store, transport(), OPTS);
    const [held] = await store.load();
    expect(held, "the unit stays while an answer waits").toBeTruthy();
    const waiting = answerConflicts(held);
    expect(waiting).toHaveLength(1);
    expect(waiting[0].result.current?.editedBy?.name).toBe("Nandini");
    // A second pass does not resend it: it waits for the researcher.
    const idle = fakeApi();
    await runUnit(held, idle.api, store, transport(), OPTS);
    expect(idle.calls.filter((c) => c.name === "putAnswers")).toHaveLength(0);

    await resolveAnswerConflict(store, held.id, waiting[0].stepKey, typed, choice);
    if (choice === "theirs") {
      expect(store.units.size, "Keep theirs drops the typed text").toBe(0);
      continue;
    }
    const [again] = await store.load();
    const resend = again.steps.find((s): s is AnswersStep => s.kind === "answers" && s.status === "PENDING")!;
    expect(resend.items[0].baseVersion, "Keep mine writes over the version it saw").toBe(4);
    const second = fakeApi();
    await runUnit(again, second.api, store, transport(), OPTS);
    expect((second.calls.find((c) => c.name === "putAnswers")!.args[0] as { baseVersion?: number }[])[0].baseVersion).toBe(4);
    expect(store.units.size).toBe(0);
  }
});

test("H18: a failed online save falls back to the queue; a conflict is kept; a lasting refusal is shown", () => {
  const items = [{ ...typed, sessionId: "s1" }] as AnswerItem[];
  const offline = answerFallback(items, { error: new TypeError("Failed to fetch") });
  expect(offline?.status).toBe("PENDING");
  expect(offline?.items[0]).not.toHaveProperty("sessionId");
  expect(answerFallback(items, { error: new ApiError(503, "Unavailable", null) })?.status).toBe("PENDING");
  expect(answerFallback(items, { error: new ApiError(422, "Bad", null) }), "a 4xx no retry cures is the caller's error").toBeNull();
  const kept = answerFallback(items, { results: [conflict(2)] });
  expect(kept?.error).toBe("CONFLICT");
  expect(kept?.conflicts?.[0].status).toBe("CONFLICT");
  expect(answerFallback(items, { results: [{ ...conflict(2), status: "SAVED" }] })).toBeNull();
  const box = readFileSync(join(__dirname, "..", "components", "questionnaire", "AnswerBox.tsx"), "utf8");
  expect(box, "the online path no longer drops a CONFLICT behind a dialog").not.toContain("setConflict(");
});

test("M22: waiting steps are retried on a backing-off timer; per-take upload state in words", () => {
  expect(nextRetryDelay(null)).toBe(RETRY_FIRST_MS);
  expect(nextRetryDelay(RETRY_FIRST_MS)).toBe(RETRY_FIRST_MS * 2);
  expect(nextRetryDelay(RETRY_MAX_MS)).toBe(RETRY_MAX_MS);
  const unit = appendStep(newSessionUnit(body), clip("k1"));
  expect(hasRetryableWork([unit])).toBe(true);
  const held = { ...unit, steps: [{ kind: "answers", status: "FAILED", error: "CONFLICT", items: [], key: "a" } as AnswersStep] };
  expect(hasRetryableWork([held]), "an answer waiting for P9 is not retried by the timer").toBe(false);
  const step = clip("k1");
  expect(clipUploadState(step, 0.4, true)).toEqual({ kind: "UPLOADING", percent: 40 });
  expect(clipUploadState(step, 0.4, false)).toEqual({ kind: "WAITING" });
  expect(clipUploadState({ ...step, error: "Upload refused (500)." }, 0, true).kind).toBe("RETRYING");
  expect(clipUploadState({ ...step, status: "DONE" }, 1, true).kind).toBe("UPLOADED");
  expect(stepKey(step)).toBe("clip:k1");
});
