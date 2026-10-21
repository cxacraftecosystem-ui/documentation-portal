/**
 * THE QUESTIONNAIRE OUTBOX (DESIGN §6.2, BUILD_PLAN §3.1): one ordered unit per session.
 *
 *   session(clientKey) → participant changes → clip(clientClipKey)* → answers
 *
 * The same queue runs online (immediately) and offline (when the signal returns), so there is one
 * code path and the researcher's first press of Record never waits for the network. Each step is
 * ticked off on its own and persisted before the next starts, so a closed tab resumes where it was.
 *
 * - Every create carries an `Idempotency-Key` that was minted ONCE, when the step was queued, and
 *   travels with every retry (`clientKey` for the session, `clientClipKey` for a clip). Bodies are
 *   frozen at queue time.
 * - A clip resumes: before presigning it asks `GET /media/uploads/{clientClipKey}`; the server's
 *   `partsDone` (merged with the parts this browser recorded) decides which parts are left. A clip
 *   that already completed is never uploaded again.
 * - A failed clip blocks nothing: the other clips and the answers keep going. At most 3 clips upload
 *   at once, 1 on a slow link.
 * - The client never names the file: the object key (and its readable leaf) comes from presign.
 *
 * ONE WRITER PER UNIT (audit C5). Every write to a unit — a step appended by the screen, a step
 * ticked off by the runner, a P9 decision — goes through `mutateUnit`, which re-reads the stored
 * unit and applies the change inside a chain held per unit id. The runner writes back only the steps
 * it touched, so a take recorded while another uploads is never overwritten by the runner's stale
 * copy, and a unit is removed only when the STORED unit is all done.
 *
 * A unit is keyed on the session's `clientKey`, or on its id when it has none (audit C4: every session
 * the backfill rebuilt has a null clientKey).
 *
 * The runner takes its API, store and byte transport as arguments so the unit spec drives it with an
 * in-memory store that refuses what IndexedDB refuses, and `e2e/questionnaire-v2-stack.spec.ts`
 * drives it against a real backend and bucket. `indexedDbQueueStore()` is the browser store.
 */

import { ApiError } from "@/lib/api";
import type {
  AnswerItem,
  AnswerResult,
  CompleteRecording,
  PresignRequest,
  QuestionnaireApi,
  Recording,
  Session,
  SessionCreate,
  UploadPart
} from "@/lib/questionnaireApi";

export type StepStatus = "PENDING" | "DONE" | "FAILED";

export type SessionStep = { kind: "session"; status: StepStatus; body: SessionCreate; error?: string };
export type ParticipantStep = {
  kind: "participant";
  status: StepStatus;
  op: "add" | "leave" | "join";
  artisanId: string;
  at: string;
  idempotencyKey: string;
  error?: string;
};
export type ClipStep = {
  kind: "clip";
  status: StepStatus;
  clientClipKey: string;
  /** Key of the Blob in the blob store (the recorder's assembled take or the picked file). */
  blobKey: string;
  /** What was recorded ("Section D"), for the missing-file line; never sent. */
  label?: string;
  sizeBytes: number;
  contentType: string;
  extension: string;
  presign: Omit<PresignRequest, "sessionId" | "linkedRecordType" | "clientClipKey" | "sizeBytes" | "contentType" | "extension">;
  recording: CompleteRecording;
  /** Upload progress the browser itself recorded, merged with the server's on resume. */
  objectKey?: string;
  uploadId?: string | null;
  /** The part size the SERVER chose for this upload; parts are cut at its boundaries. */
  partSize?: number;
  partsDone?: UploadPart[];
  uploaded?: boolean;
  recordingId?: string;
  attempts?: number;
  error?: string;
};
export type AnswersStep = {
  kind: "answers";
  status: StepStatus;
  items: Omit<AnswerItem, "sessionId">[];
  /** Minted when the step is queued; how the one writer finds this step again. */
  key?: string;
  /** `CONFLICT` while items wait for P9 (or a refusal the researcher must see). */
  error?: string;
  /** The server's answer for each held item: the current text and who changed it. */
  conflicts?: AnswerResult[];
};
export type Step = SessionStep | ParticipantStep | ClipStep | AnswersStep;

export type SessionUnit = {
  /** The session's clientKey, or its id when it has none; one unit per session. */
  id: string;
  sessionId: string | null;
  createdAt: string;
  steps: Step[];
};

export interface QueueStore {
  load(): Promise<SessionUnit[]>;
  get(id: string): Promise<SessionUnit | null>;
  save(unit: SessionUnit): Promise<void>;
  remove(id: string): Promise<void>;
  getBlob(key: string): Promise<Blob | null>;
  putBlob(key: string, blob: Blob): Promise<void>;
  deleteBlob(key: string): Promise<void>;
}

export interface ByteTransport {
  /** PUT bytes to a presigned URL; resolves with the part's ETag where the bucket exposes it. */
  put(url: string, body: Blob, headers: Record<string, string>): Promise<{ etag: string | null }>;
}

export type QueueApi = Pick<
  QuestionnaireApi,
  | "createSession"
  | "addParticipant"
  | "patchParticipant"
  | "presign"
  | "multipartCreate"
  | "multipartPresignParts"
  | "multipartComplete"
  | "uploadState"
  | "complete"
  | "putAnswers"
>;

export type QueueOptions = {
  multipartThresholdBytes: number;
  partSizeBytes: number;
  /** Measured link speed; under ~50 kbps clips go one at a time. */
  slowLink?: boolean;
  onProgress?: (clientClipKey: string, fraction: number) => void;
  onRecording?: (recording: Recording) => void;
  onSession?: (session: Session) => void;
};

export const MAX_PARALLEL_CLIPS = 3;

/** The key a session's unit is stored under: its clientKey, else its id (audit C4). */
export function sessionUnitKey(session: { id: string; clientKey: string | null | undefined }): string {
  return session.clientKey || session.id;
}

function freshKey(prefix: string): string {
  const random =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `${prefix}-${random}`;
}

/** A new unit for a session started on this device. Nothing is sent until `runUnit`. */
export function newSessionUnit(body: SessionCreate, now = new Date()): SessionUnit {
  return { id: body.clientKey, sessionId: null, createdAt: now.toISOString(), steps: [{ kind: "session", status: "PENDING", body }] };
}

/** A unit for a session that already exists on the server (opened online, or joined). */
export function existingSessionUnit(session: Pick<Session, "id" | "clientKey">, now = new Date()): SessionUnit {
  return { id: sessionUnitKey(session), sessionId: session.id, createdAt: now.toISOString(), steps: [] };
}

/** A step's identity inside its unit; the one writer matches the runner's copy to the stored one by it. */
export function stepKey(step: Step): string {
  switch (step.kind) {
    case "session":
      return "session";
    case "participant":
      return `participant:${step.idempotencyKey}`;
    case "clip":
      return `clip:${step.clientClipKey}`;
    default:
      return `answers:${step.key ?? JSON.stringify(step.items)}`;
  }
}

/**
 * Participant changes are ordered by device time among themselves; clips keep the order they were
 * recorded in; answers go last. The unit's own array is kept in that order on every append.
 */
function rank(step: Step): number {
  switch (step.kind) {
    case "session":
      return 0;
    case "participant":
      return 1;
    case "clip":
      return 2;
    default:
      return 3;
  }
}

export function appendStep(unit: SessionUnit, step: Step): SessionUnit {
  const keyed: Step = step.kind === "answers" && !step.key ? { ...step, key: freshKey("answers") } : step;
  const steps = [...unit.steps, keyed];
  const indexed = steps.map((s, index) => ({ s, index }));
  indexed.sort((a, b) => {
    const r = rank(a.s) - rank(b.s);
    if (r !== 0) return r;
    if (a.s.kind === "participant" && b.s.kind === "participant") return a.s.at.localeCompare(b.s.at) || a.index - b.index;
    return a.index - b.index;
  });
  return { ...unit, steps: indexed.map((x) => x.s) };
}

/** Steps that still need sending, in order. */
export function pendingSteps(unit: SessionUnit): Step[] {
  return unit.steps.filter((s) => s.status !== "DONE");
}

export function pendingClipCount(units: SessionUnit[]): number {
  return units.reduce((n, u) => n + u.steps.filter((s) => s.kind === "clip" && s.status !== "DONE").length, 0);
}

// ── the one writer ───────────────────────────────────────────────────────────────────────────────

const chains = new WeakMap<QueueStore, Map<string, Promise<unknown>>>();

/** Runs `work` after every earlier write to the same unit of the same store has settled. */
export function serialised<T>(store: QueueStore, id: string, work: () => Promise<T>): Promise<T> {
  let byId = chains.get(store);
  if (!byId) {
    byId = new Map();
    chains.set(store, byId);
  }
  const previous = byId.get(id) ?? Promise.resolve();
  const next = previous.then(work, work);
  const tail = next.catch(() => undefined);
  byId.set(id, tail);
  void tail.then(() => {
    if (byId!.get(id) === tail) byId!.delete(id);
  });
  return next;
}

/**
 * The only way a unit is written. `change` sees the STORED unit (a copy; null when there is none)
 * and returns the unit to store, or null to remove it.
 */
export function mutateUnit(
  store: QueueStore,
  id: string,
  change: (current: SessionUnit | null) => SessionUnit | null
): Promise<SessionUnit | null> {
  return serialised(store, id, async () => {
    const stored = await store.get(id);
    const next = change(stored ? structuredClone(stored) : null);
    if (next === null) {
      if (stored) await store.remove(id);
      return null;
    }
    await store.save(next);
    return next;
  });
}

/** Appends a step to a unit (creating it from `base` when it is not stored yet), through the one writer. */
export function enqueueStep(store: QueueStore, base: SessionUnit, step: Step): Promise<SessionUnit | null> {
  return mutateUnit(store, base.id, (current) => appendStep(current ?? base, step));
}

/** The stored unit with the runner's copies of the steps it touched laid over it; new steps stand. */
function mergeRunner(stored: SessionUnit | null, runner: SessionUnit, touched: ReadonlySet<string>): SessionUnit {
  if (!stored) return structuredClone(runner);
  const mine = new Map(runner.steps.map((s) => [stepKey(s), s] as const));
  const steps = stored.steps.map((s) => {
    const key = stepKey(s);
    const ours = mine.get(key);
    return touched.has(key) && ours ? structuredClone(ours) : s;
  });
  return { ...stored, sessionId: runner.sessionId ?? stored.sessionId, steps };
}

// ── answers held for P9 (audit H18) ──────────────────────────────────────────────────────────────

const sameItem = (a: { artisanId: string; questionLineageId: string }, b: { artisanId: string; questionLineageId: string }) =>
  a.artisanId === b.artisanId && a.questionLineageId === b.questionLineageId;

/** The results that must not be dropped: a CONFLICT waits for P9, a FORBIDDEN is shown. */
export function heldAnswerResults(items: { artisanId: string; questionLineageId: string }[], results: AnswerResult[]): AnswerResult[] {
  return results.filter((r) => (r.status === "CONFLICT" || r.status === "FORBIDDEN") && items.some((item) => sameItem(item, r)));
}

/** Every answer waiting for a decision in a unit, with the step that holds it. */
export function answerConflicts(unit: SessionUnit | null): { stepKey: string; item: Omit<AnswerItem, "sessionId">; result: AnswerResult }[] {
  if (!unit) return [];
  return unit.steps.flatMap((step) =>
    step.kind === "answers" && step.error === "CONFLICT"
      ? step.items.flatMap((item) => {
          const result = step.conflicts?.find((r) => sameItem(r, item));
          return result ? [{ stepKey: stepKey(step), item, result }] : [];
        })
      : []
  );
}

/**
 * P9 decided: "mine" re-queues the typed text over the server's current version; "theirs" (or a
 * refusal acknowledged) drops it. The step is done once nothing in it waits.
 */
export function resolveAnswerConflict(
  store: QueueStore,
  unitId: string,
  key: string,
  target: { artisanId: string; questionLineageId: string },
  choice: "mine" | "theirs"
): Promise<SessionUnit | null> {
  return mutateUnit(store, unitId, (current) => {
    if (!current) return null;
    let unit = current;
    const step = unit.steps.find((s): s is AnswersStep => s.kind === "answers" && stepKey(s) === key);
    if (!step) return unit;
    const item = step.items.find((i) => sameItem(i, target));
    const result = step.conflicts?.find((r) => sameItem(r, target));
    step.items = step.items.filter((i) => !sameItem(i, target));
    step.conflicts = (step.conflicts ?? []).filter((r) => !sameItem(r, target));
    if (!step.items.length) {
      step.status = "DONE";
      step.error = undefined;
      step.conflicts = undefined;
    }
    if (choice === "mine" && item && result?.status === "CONFLICT") {
      const baseVersion = result.current?.version;
      unit = appendStep(unit, {
        kind: "answers",
        status: "PENDING",
        items: [{ ...item, ...(typeof baseVersion === "number" ? { baseVersion } : {}) }]
      });
    }
    return unit.steps.every((s) => s.status === "DONE") ? null : unit;
  });
}

// ── the runner ───────────────────────────────────────────────────────────────────────────────────

function describe(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}

/** A 4xx (other than 408/429) will not succeed on retry with the same frozen body. */
export function isPermanent(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429;
}

/** `POST /media/multipart/presign-parts` answers `{urls: {"2": url, …}}`; the parts asked for, in order. */
export function signedParts(signed: { urls?: Record<string, string> | null }, wanted: number[]): { partNumber: number; url: string }[] {
  const urls = signed.urls ?? {};
  const parts = wanted.map((partNumber) => ({ partNumber, url: urls[String(partNumber)] })).filter((p): p is { partNumber: number; url: string } => typeof p.url === "string" && p.url.length > 0);
  if (parts.length !== wanted.length) throw new Error("The upload server did not sign every part.");
  return parts;
}

async function uploadClip(step: ClipStep, sessionId: string, api: QueueApi, store: QueueStore, transport: ByteTransport, opts: QueueOptions, persist: () => Promise<void>) {
  const blob = await store.getBlob(step.blobKey);
  if (!blob) {
    // The bytes are gone from this browser; nothing can bring them back. Never ticked.
    step.status = "FAILED";
    step.error = "MISSING_FILE";
    return;
  }

  if (!step.uploaded) {
    // Resume first: the server may already hold an intent (and parts) for this clip.
    if (!step.objectKey) {
      try {
        const state = await api.uploadState(step.clientClipKey);
        step.objectKey = state.objectKey;
        step.uploadId = state.multipartUploadId;
        if (state.partSize) step.partSize = state.partSize;
        step.partsDone = mergeParts(step.partsDone ?? [], state.partsDone);
        if (state.closed) step.uploaded = true;
      } catch (error) {
        if (!(error instanceof ApiError && error.status === 404)) throw error;
      }
    }

    const request: PresignRequest = {
      ...step.presign,
      linkedRecordType: "questionnaire_session",
      sessionId,
      clientClipKey: step.clientClipKey,
      sizeBytes: step.sizeBytes,
      contentType: step.contentType,
      extension: step.extension
    };

    if (!step.uploaded && step.sizeBytes <= opts.multipartThresholdBytes) {
      // A re-presign signs a fresh URL for the SAME object key (the server keys the intent on clientClipKey).
      const signed = await api.presign(request);
      step.objectKey = signed.objectKey;
      await persist();
      await transport.put(signed.url, blob, signed.headers);
      opts.onProgress?.(step.clientClipKey, 1);
      step.uploaded = true;
      await persist();
    } else if (!step.uploaded) {
      if (!step.uploadId) {
        const created = await api.multipartCreate(request);
        step.objectKey = created.objectKey;
        step.uploadId = created.uploadId;
        if (created.partSize) step.partSize = created.partSize;
        step.partsDone = mergeParts(step.partsDone ?? [], created.partsDone ?? []);
        await persist();
      }
      // The server's part size decides the boundaries; the config's is only the fallback.
      const partSize = step.partSize || opts.partSizeBytes;
      const total = Math.max(1, Math.ceil(step.sizeBytes / partSize));
      const done = new Set((step.partsDone ?? []).map((p) => p.partNumber));
      const missing = Array.from({ length: total }, (_, i) => i + 1).filter((n) => !done.has(n));
      if (missing.length) {
        const signed = await api.multipartPresignParts({ objectKey: step.objectKey!, uploadId: step.uploadId!, partNumbers: missing });
        for (const part of signedParts(signed, missing)) {
          const start = (part.partNumber - 1) * partSize;
          const slice = blob.slice(start, Math.min(start + partSize, step.sizeBytes));
          const { etag } = await transport.put(part.url, slice, {});
          if (!etag) throw new Error("The upload server did not return a part tag.");
          step.partsDone = mergeParts(step.partsDone ?? [], [{ partNumber: part.partNumber, etag }]);
          opts.onProgress?.(step.clientClipKey, step.partsDone.length / total);
          await persist();
        }
      }
      await api.multipartComplete({
        objectKey: step.objectKey!,
        uploadId: step.uploadId!,
        parts: [...(step.partsDone ?? [])].sort((a, b) => a.partNumber - b.partNumber)
      });
      step.uploaded = true;
      await persist();
    }
  }

  const recording = await api.complete({
    objectKey: step.objectKey!,
    clientClipKey: step.clientClipKey,
    linkedRecordType: "questionnaire_session",
    linkedRecordId: sessionId,
    recording: step.recording
  });
  step.recordingId = recording.id;
  step.status = "DONE";
  step.error = undefined;
  opts.onRecording?.(recording);
  await persist();
  await store.deleteBlob(step.blobKey);
}

export function mergeParts(a: UploadPart[], b: UploadPart[]): UploadPart[] {
  const byNumber = new Map<number, UploadPart>();
  for (const part of [...a, ...b]) byNumber.set(part.partNumber, part);
  return [...byNumber.values()].sort((x, y) => x.partNumber - y.partNumber);
}

/**
 * Sends one unit's pending steps in order. Returns the runner's view of the unit; every change is
 * written back through the one writer as it happens. A session step that fails stops the unit,
 * since nothing after it has a session to point at; a failed clip stops only itself. Steps appended
 * while this runs are kept and sent by the next pass.
 */
export async function runUnit(
  unit: SessionUnit,
  api: QueueApi,
  store: QueueStore,
  transport: ByteTransport,
  opts: QueueOptions
): Promise<SessionUnit> {
  const touched = new Set<string>();
  const persist = async (step: Step) => {
    touched.add(stepKey(step));
    await mutateUnit(store, unit.id, (stored) => mergeRunner(stored, unit, touched));
  };

  for (const step of unit.steps) {
    if (step.status === "DONE") continue;
    if (step.kind === "session") {
      try {
        const session = await api.createSession(step.body);
        unit.sessionId = session.id;
        step.status = "DONE";
        step.error = undefined;
        opts.onSession?.(session);
        await persist(step);
      } catch (error) {
        step.error = describe(error);
        if (isPermanent(error)) step.status = "FAILED";
        await persist(step);
        return unit;
      }
    }
  }
  if (!unit.sessionId) return unit;
  const sessionId = unit.sessionId;

  for (const step of unit.steps) {
    if (step.kind !== "participant" || step.status === "DONE") continue;
    try {
      if (step.op === "add") await api.addParticipant(sessionId, step.artisanId, step.idempotencyKey);
      else await api.patchParticipant(sessionId, step.artisanId, step.op === "leave" ? { leftAt: step.at } : { joinedAt: step.at, leftAt: null });
      step.status = "DONE";
      step.error = undefined;
    } catch (error) {
      step.error = describe(error);
      if (isPermanent(error)) step.status = "FAILED";
      // A clip may name this artisan; the server adds non-participant subjects itself, so carry on.
    }
    await persist(step);
  }

  const clips = unit.steps.filter((s): s is ClipStep => s.kind === "clip" && s.status !== "DONE" && s.error !== "MISSING_FILE");
  const width = opts.slowLink ? 1 : MAX_PARALLEL_CLIPS;
  let cursor = 0;
  async function worker() {
    while (cursor < clips.length) {
      const step = clips[cursor];
      cursor += 1;
      step.attempts = (step.attempts ?? 0) + 1;
      try {
        await uploadClip(step, sessionId, api, store, transport, opts, () => persist(step));
        // A missing file is settled inside; its FAILED state still has to reach the store.
        if (step.error === "MISSING_FILE") await persist(step);
      } catch (error) {
        step.error = describe(error);
        if (isPermanent(error)) step.status = "FAILED";
        await persist(step);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(width, clips.length) }, worker));

  for (const step of unit.steps) {
    // Held answers wait for the researcher's P9; resending them would only conflict again.
    if (step.kind !== "answers" || step.status === "DONE" || step.error === "CONFLICT") continue;
    try {
      const { results } = await api.putAnswers(step.items.map((item) => ({ ...item, sessionId })));
      const held = heldAnswerResults(step.items, results ?? []);
      if (held.length) {
        step.items = step.items.filter((item) => held.some((r) => sameItem(r, item)));
        step.conflicts = held;
        step.status = "FAILED";
        step.error = "CONFLICT";
      } else {
        step.status = "DONE";
        step.error = undefined;
        step.conflicts = undefined;
      }
    } catch (error) {
      step.error = describe(error);
      if (isPermanent(error)) step.status = "FAILED";
    }
    await persist(step);
  }

  // Removed only when the STORED unit is all done: a step appended meanwhile keeps it.
  await mutateUnit(store, unit.id, (stored) => {
    const merged = mergeRunner(stored, unit, touched);
    return merged.steps.every((s) => s.status === "DONE") ? null : merged;
  });
  return unit;
}

// ── what the screens read off the outbox ─────────────────────────────────────────────────────────

export type ClipUploadState =
  | { kind: "UPLOADING"; percent: number }
  | { kind: "WAITING" }
  | { kind: "RETRYING"; error: string }
  | { kind: "FAILED"; error: string }
  | { kind: "UPLOADED" };

/** "Uploading 40%" · "Uploaded" · "Waiting for signal" · "Upload failed. Retrying." (DESIGN §4.3). */
export function clipUploadState(step: ClipStep, progress: number | undefined, online: boolean): ClipUploadState {
  if (step.status === "DONE") return { kind: "UPLOADED" };
  if (step.status === "FAILED") return { kind: "FAILED", error: step.error ?? "" };
  if (!online) return { kind: "WAITING" };
  if (step.error) return { kind: "RETRYING", error: step.error };
  return { kind: "UPLOADING", percent: Math.round(Math.max(0, Math.min(1, progress ?? 0)) * 100) };
}

/**
 * An in-memory store, for the unit specs and as the fallback where IndexedDB is unavailable. It
 * refuses what IndexedDB refuses: a unit with no key (`keyPath: "id"` on a null id throws a
 * DataError there). The fake that accepted one is how audit C4 shipped.
 */
export function memoryQueueStore(): QueueStore & { units: Map<string, SessionUnit>; blobs: Map<string, Blob> } {
  const units = new Map<string, SessionUnit>();
  const blobs = new Map<string, Blob>();
  return {
    units,
    blobs,
    async load() {
      return [...units.values()].map((u) => structuredClone(u));
    },
    async get(id) {
      const unit = units.get(id);
      return unit ? structuredClone(unit) : null;
    },
    async save(unit) {
      if (typeof unit.id !== "string" || !unit.id) throw new DOMException("Evaluating the object store's key path did not yield a value.", "DataError");
      units.set(unit.id, structuredClone(unit));
    },
    async remove(id) {
      units.delete(id);
    },
    async getBlob(key) {
      return blobs.get(key) ?? null;
    },
    async putBlob(key, blob) {
      blobs.set(key, blob);
    },
    async deleteBlob(key) {
      blobs.delete(key);
    }
  };
}

const DB_NAME = "field-repo-questionnaire";
const DB_VERSION = 1;
const UNITS = "units";
const BLOBS = "blobs";

export function openQuestionnaireDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(UNITS)) db.createObjectStore(UNITS, { keyPath: "id" });
      if (!db.objectStoreNames.contains(BLOBS)) db.createObjectStore(BLOBS);
      if (!db.objectStoreNames.contains("chunks")) db.createObjectStore("chunks", { keyPath: ["takeKey", "index"] });
      if (!db.objectStoreNames.contains("takes")) db.createObjectStore("takes", { keyPath: "takeKey" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function tx<T>(db: IDBDatabase, store: string, mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = run(db.transaction(store, mode).objectStore(store));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/** The browser store: units and blobs in IndexedDB, surviving a closed tab. */
export function indexedDbQueueStore(): QueueStore {
  let opened: Promise<IDBDatabase> | null = null;
  const db = () => (opened ??= openQuestionnaireDb());
  return {
    async load() {
      return tx<SessionUnit[]>(await db(), UNITS, "readonly", (s) => s.getAll() as IDBRequest<SessionUnit[]>);
    },
    async get(id) {
      return ((await tx(await db(), UNITS, "readonly", (s) => s.get(id))) as SessionUnit | undefined) ?? null;
    },
    async save(unit) {
      await tx(await db(), UNITS, "readwrite", (s) => s.put(structuredClone(unit)));
    },
    async remove(id) {
      await tx(await db(), UNITS, "readwrite", (s) => s.delete(id));
    },
    async getBlob(key) {
      return ((await tx(await db(), BLOBS, "readonly", (s) => s.get(key))) as Blob | undefined) ?? null;
    },
    async putBlob(key, blob) {
      await tx(await db(), BLOBS, "readwrite", (s) => s.put(blob, key));
    },
    async deleteBlob(key) {
      await tx(await db(), BLOBS, "readwrite", (s) => s.delete(key));
    }
  };
}

/** The browser transport: `fetch` PUT, reading the part ETag the bucket's CORS must expose. */
export const fetchTransport: ByteTransport = {
  async put(url, body, headers) {
    const response = await fetch(url, { method: "PUT", body, headers });
    if (!response.ok) throw new Error(`Upload refused (${response.status}).`);
    return { etag: response.headers.get("etag") };
  }
};
