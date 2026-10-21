import { createHash, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";

import { expect, test } from "@playwright/test";

import { ApiError, describeApiDetail } from "@/lib/api";
import { offlineCheck } from "@/lib/coverageCache";
import { decisionPartner } from "@/lib/questionnaireInbox";
import { mintKey, questionnaireClient, type AnswerItem, type JsonFetcher } from "@/lib/questionnaireApi";
import {
  answerConflicts,
  enqueueStep,
  existingSessionUnit,
  fetchTransport,
  memoryQueueStore,
  resolveAnswerConflict,
  runUnit,
  type ByteTransport,
  type ClipStep,
  type QueueOptions,
  type SessionUnit
} from "@/lib/questionnaireQueue";

/**
 * THE WEB OUTBOX AGAINST A REAL BACKEND AND A REAL BUCKET (audit 2026-10-21: "the unit tests use
 * fakes that hid C3"). No fake API and no fake transport: the browser's own client
 * (`questionnaireClient`, the same object `questionnaireApi` is) is pointed at a local FastAPI with
 * QUESTIONNAIRE_V2 on, a migrated Postgres and a MinIO bucket, and the browser's own byte transport
 * (`fetchTransport`) PUTs the bytes to the URLs the server signs.
 *
 * CI only: the `web-v2-stack` job in `.github/workflows/checks.yml` starts the stack, seeds it with
 * `scripts/ci/seed_qv2_stack.py` and sets QV2_STACK_API and QV2_STACK_SEED. It refuses any host but
 * loopback, because it writes.
 */

const API = process.env.QV2_STACK_API ?? "";
const SEED_PATH = process.env.QV2_STACK_SEED ?? "";
const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(API);

test.skip(!API || !SEED_PATH, "Set QV2_STACK_API and QV2_STACK_SEED (the web-v2-stack CI job does).");
test.skip(Boolean(API) && !LOCAL, "This spec writes; it runs only against a local stack.");
test.describe.configure({ mode: "serial" });

type Seed = {
  questionnaireId: string;
  workshopId: string | null;
  sections: Record<string, string>;
  questions: Record<string, string>;
  artisans: string[];
  rebuiltSessionId: string;
  tokens: { lead: string; other: string };
};
const seed = (): Seed => JSON.parse(readFileSync(SEED_PATH, "utf8")) as Seed;

/** `apiFetch`'s contract over plain fetch: same headers, same ApiError on a refusal. */
function stackFetch(token: string): JsonFetcher {
  return async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    headers.set("X-Client-Time", new Date().toISOString());
    headers.set("X-App-Version", "web-ci");
    headers.set("X-App-Platform", "web");
    const response = await fetch(`${API.replace(/\/$/, "")}/api${path}`, { ...init, headers, redirect: "manual" });
    if (response.status === 204) return undefined as T;
    const type = response.headers.get("content-type") ?? "";
    const body = type.includes("application/json") ? await response.json() : await response.text();
    if (!response.ok) {
      const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
      throw new ApiError(response.status, describeApiDetail(detail, `HTTP ${response.status}`), body, response.headers);
    }
    return body as T;
  };
}

const sha = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

function clip(input: { key: string; bytes: Uint8Array; sectionId: string; artisanId: string; at?: string; acked?: string[] }): ClipStep {
  const at = input.at ?? new Date().toISOString();
  const hash = sha(input.bytes);
  return {
    kind: "clip",
    status: "PENDING",
    clientClipKey: input.key,
    blobKey: `blob-${input.key}`,
    label: "Section",
    sizeBytes: input.bytes.byteLength,
    contentType: "audio/webm",
    extension: "webm",
    presign: { contentSha256: hash, targets: [{ sectionId: input.sectionId }], subjectArtisanIds: [input.artisanId], deviceCapturedAt: at, clockOffsetMs: 0 },
    recording: {
      targets: [{ sectionId: input.sectionId }],
      subjects: [{ artisanId: input.artisanId, role: "ANSWERED", named: false, repeatReason: null }],
      deviceCapturedAt: at,
      clockOffsetMs: 0,
      capturedAtSource: "DEVICE",
      durationMs: 1000,
      rosterCount: 1,
      contentSha256: hash,
      continuationOfId: null,
      replacesRecordingId: null,
      ackedRecordingIds: input.acked ?? []
    }
  };
}

async function options(api: ReturnType<typeof questionnaireClient>): Promise<QueueOptions> {
  const config = await api.config();
  return { multipartThresholdBytes: config.multipartThresholdBytes, partSizeBytes: config.partSizeBytes };
}

async function newSession(api: ReturnType<typeof questionnaireClient>, s: Seed, artisanId: string) {
  const clientKey = mintKey("stack");
  return api.createSession({
    clientKey,
    questionnaireId: s.questionnaireId,
    workshopId: s.workshopId,
    designWorkshopId: null,
    sessionDate: new Date().toISOString().slice(0, 10),
    startedAt: new Date().toISOString(),
    purpose: "FIRST_SITTING",
    place: null,
    languageCode: null,
    languageOther: null,
    note: null,
    participants: [{ artisanId }]
  });
}

test("the stack is v2 and answers the web's headers", async () => {
  const api = questionnaireClient(stackFetch(seed().tokens.lead));
  const config = await api.config();
  expect(config.v2Enabled).toBe(true);
  expect(config.multipartThresholdBytes).toBeGreaterThan(0);
});

test("C3 + C4: a large take on a rebuilt session (no clientKey) uploads through multipart and lands", async () => {
  const s = seed();
  const api = questionnaireClient(stackFetch(s.tokens.lead));
  const session = await api.getSession(s.rebuiltSessionId);
  expect(session.clientKey, "the seed rebuilt this session with no clientKey, as the backfill did").toBeNull();
  const opts = await options(api);
  // Over the threshold and over one part, so it goes up in at least two parts.
  const bytes = new Uint8Array(randomBytes(Math.max(opts.multipartThresholdBytes, opts.partSizeBytes) + 2 * 1024 * 1024));
  const store = memoryQueueStore();
  const base = existingSessionUnit(session);
  expect(base.id).toBe(session.id);
  const step = clip({ key: mintKey("big"), bytes, sectionId: Object.values(s.sections)[0], artisanId: s.artisans[0] });
  await store.putBlob(step.blobKey, new Blob([bytes]));
  await enqueueStep(store, base, step);
  const [unit] = await store.load();
  const out = await runUnit(unit, api, store, fetchTransport, opts);
  const done = out.steps[0] as ClipStep;
  expect(done.error, "no part URL went missing").toBeUndefined();
  expect(done.status).toBe("DONE");
  expect(done.partsDone?.length ?? 0).toBeGreaterThan(1);
  const after = await api.getSession(session.id);
  expect(after.recordings.map((r) => r.clientClipKey)).toContain(step.clientClipKey);
  expect((await api.uploadState(step.clientClipKey)).closed).toBe(true);
  expect(store.units.size).toBe(0);
});

test("C5: a take queued while another is uploading lands too", async () => {
  const s = seed();
  const api = questionnaireClient(stackFetch(s.tokens.lead));
  const session = await newSession(api, s, s.artisans[1]);
  const opts = await options(api);
  const store = memoryQueueStore();
  const base = existingSessionUnit(session);
  const section = Object.values(s.sections)[1];
  // The blob is the hashed bytes: the bucket refuses a body whose sha256 differs from the signed one.
  const firstBytes = new Uint8Array(randomBytes(4096));
  const firstStep = clip({ key: mintKey("c5a"), bytes: firstBytes, sectionId: section, artisanId: s.artisans[1] });
  await store.putBlob(firstStep.blobKey, new Blob([firstBytes]));
  await enqueueStep(store, base, firstStep);

  let release!: () => void;
  const gate = new Promise<void>((resolve) => (release = resolve));
  let started!: () => void;
  const uploading = new Promise<void>((resolve) => (started = resolve));
  const slow: ByteTransport = {
    async put(url, body, headers) {
      started();
      await gate;
      return fetchTransport.put(url, body, headers);
    }
  };
  const [unit] = await store.load();
  const running = runUnit(unit, api, store, slow, opts);
  await uploading;
  const secondBytes = new Uint8Array(randomBytes(4096));
  const second = clip({ key: mintKey("c5b"), bytes: secondBytes, sectionId: section, artisanId: s.artisans[1] });
  await store.putBlob(second.blobKey, new Blob([secondBytes]));
  await enqueueStep(store, base, second);
  release();
  await running;
  const [held] = await store.load();
  expect(held, "the unit is still there: the second take is pending").toBeTruthy();
  await runUnit(held as SessionUnit, api, store, fetchTransport, opts);
  const after = await api.getSession(session.id);
  expect(after.recordings.map((r) => r.clientClipKey).sort()).toEqual([firstStep.clientClipKey, second.clientClipKey].sort());
  expect(store.units.size).toBe(0);
});

test("H18: an answer that conflicts on the server is held for P9, and Keep mine writes over it", async () => {
  const s = seed();
  const lead = questionnaireClient(stackFetch(s.tokens.lead));
  const session = await newSession(lead, s, s.artisans[2]);
  const lineage = Object.values(s.questions)[0];
  // Another device answers first.
  const theirs = await lead.putAnswers([
    { artisanId: s.artisans[2], questionLineageId: lineage, text: "Theirs.", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: session.id, sourceRecordingId: null }
  ]);
  expect(theirs.results[0].status).toBe("SAVED");
  // This device typed an answer with no base version: create-only, so the server says CONFLICT.
  const typed: Omit<AnswerItem, "sessionId"> = { artisanId: s.artisans[2], questionLineageId: lineage, text: "Mine.", origin: "TYPED", attribution: "INDIVIDUAL", sourceRecordingId: null };
  const store = memoryQueueStore();
  await enqueueStep(store, existingSessionUnit(session), { kind: "answers", status: "PENDING", items: [typed] });
  const opts = await options(lead);
  await runUnit((await store.load())[0], lead, store, fetchTransport, opts);
  const [held] = await store.load();
  const waiting = answerConflicts(held);
  expect(waiting).toHaveLength(1);
  expect(waiting[0].result.status).toBe("CONFLICT");
  expect(waiting[0].result.current?.text).toBe("Theirs.");
  await resolveAnswerConflict(store, held.id, waiting[0].stepKey, typed, "mine");
  await runUnit((await store.load())[0], lead, store, fetchTransport, opts);
  expect(store.units.size).toBe(0);
  const after = await lead.getSession(session.id);
  expect(after.answers.find((a) => a.questionLineageId === lineage && a.artisanId === s.artisans[2])?.text).toBe("Mine.");
});

test("M4 + H13: offline coverage agrees with the server, and Keep one decides a clash in place", async () => {
  const s = seed();
  const api = questionnaireClient(stackFetch(s.tokens.lead));
  const artisan = s.artisans[0];
  const session = await newSession(api, s, artisan);
  const section = Object.values(s.sections)[2];
  const opts = await options(api);
  const store = memoryQueueStore();
  const base = existingSessionUnit(session);
  // Two takes of one section for one artisan, neither knowing the other: the second is a clash.
  for (const key of [mintKey("h13a"), mintKey("h13b")]) {
    const bytes = new Uint8Array(randomBytes(2048));
    const step = clip({ key, bytes, sectionId: section, artisanId: artisan });
    await store.putBlob(step.blobKey, new Blob([bytes]));
    await enqueueStep(store, base, step);
    await runUnit((await store.load())[0], api, store, fetchTransport, opts);
  }
  const detail = await api.getSession(session.id);
  expect(detail.recordings).toHaveLength(2);

  // M4: the snapshot this browser would hold answers coverage/check as the server does.
  const request = { questionnaireId: s.questionnaireId, artisanIds: [artisan], targets: [{ sectionId: section }], sessionId: session.id };
  const server = await api.checkCoverage(request);
  const snapshot = await api.snapshot({ questionnaireId: s.questionnaireId, workshopId: s.workshopId });
  const offline = offlineCheck(snapshot, [], request, { sectionOf: () => null, labelOf: (k) => k, sessionId: session.id });
  expect(offline.targets[0].perArtisan[0].takes.length).toBe(server.targets[0].perArtisan[0].takes.length);
  expect(offline.targets[0].perArtisan[0].takes.length).toBeGreaterThan(0);

  // H13: the inbox row, its partner, and Keep one.
  const inbox = await api.inbox();
  const row = inbox.needsDecision.find((r) => r.sessionId === session.id);
  expect(row, "the second take needs a decision").toBeTruthy();
  const other = decisionPartner(row!, detail.recordings);
  expect(other?.id).not.toBe(row!.recordingId);
  await api.decide(row!.recordingId, { action: "KEEP_THIS", otherRecordingId: other!.id });
  const decided = await api.getSession(session.id);
  const kept = decided.recordings.find((r) => r.id === row!.recordingId);
  expect(kept?.filing).toBe("FILED");
  expect(decided.recordings.find((r) => r.id === other!.id && !r.removedAt), "the other take is removed, restorably").toBeFalsy();
});
