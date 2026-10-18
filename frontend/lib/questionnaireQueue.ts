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
 * The runner takes its API, store and byte transport as arguments so `e2e/questionnaire-queue-unit.spec.ts`
 * drives it with fakes; `indexedDbQueueStore()` is the browser store.
 */

import { ApiError } from "@/lib/api";
import type {
  AnswerItem,
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
  partsDone?: UploadPart[];
  uploaded?: boolean;
  recordingId?: string;
  attempts?: number;
  error?: string;
};
export type AnswersStep = { kind: "answers"; status: StepStatus; items: Omit<AnswerItem, "sessionId">[]; error?: string };
export type Step = SessionStep | ParticipantStep | ClipStep | AnswersStep;

export type SessionUnit = {
  /** The session's clientKey; one unit per session. */
  id: string;
  sessionId: string | null;
  createdAt: string;
  steps: Step[];
};

export interface QueueStore {
  load(): Promise<SessionUnit[]>;
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

/** A new unit for a session started on this device. Nothing is sent until `runUnit`. */
export function newSessionUnit(body: SessionCreate, now = new Date()): SessionUnit {
  return { id: body.clientKey, sessionId: null, createdAt: now.toISOString(), steps: [{ kind: "session", status: "PENDING", body }] };
}

/** A unit for a session that already exists on the server (opened online, or joined). */
export function existingSessionUnit(session: Pick<Session, "id" | "clientKey">, now = new Date()): SessionUnit {
  return { id: session.clientKey, sessionId: session.id, createdAt: now.toISOString(), steps: [] };
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
  const steps = [...unit.steps, step];
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

function describe(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}

/** A 4xx (other than 408/429) will not succeed on retry with the same frozen body. */
function isPermanent(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429;
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
        step.partsDone = mergeParts(step.partsDone ?? [], created.partsDone);
        await persist();
      }
      const partSize = opts.partSizeBytes;
      const total = Math.max(1, Math.ceil(step.sizeBytes / partSize));
      const done = new Set((step.partsDone ?? []).map((p) => p.partNumber));
      const missing = Array.from({ length: total }, (_, i) => i + 1).filter((n) => !done.has(n));
      if (missing.length) {
        const signed = await api.multipartPresignParts({ objectKey: step.objectKey!, uploadId: step.uploadId!, partNumbers: missing });
        for (const part of signed.parts.sort((a, b) => a.partNumber - b.partNumber)) {
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
 * Sends one unit's pending steps in order. Returns the unit as it now stands (also persisted after
 * every step). A session step that fails stops the unit, since nothing after it has a session to
 * point at; a failed clip stops only itself.
 */
export async function runUnit(
  unit: SessionUnit,
  api: QueueApi,
  store: QueueStore,
  transport: ByteTransport,
  opts: QueueOptions
): Promise<SessionUnit> {
  const persist = () => store.save(unit);

  for (const step of unit.steps) {
    if (step.status === "DONE") continue;
    if (step.kind === "session") {
      try {
        const session = await api.createSession(step.body);
        unit.sessionId = session.id;
        step.status = "DONE";
        opts.onSession?.(session);
        await persist();
      } catch (error) {
        step.error = describe(error);
        if (isPermanent(error)) step.status = "FAILED";
        await persist();
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
      await persist();
      // A clip may name this artisan; the server adds non-participant subjects itself, so carry on.
      continue;
    }
    await persist();
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
        await uploadClip(step, sessionId, api, store, transport, opts, persist);
      } catch (error) {
        step.error = describe(error);
        if (isPermanent(error)) step.status = "FAILED";
        await persist();
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(width, clips.length) }, worker));

  for (const step of unit.steps) {
    if (step.kind !== "answers" || step.status === "DONE") continue;
    try {
      await api.putAnswers(step.items.map((item) => ({ ...item, sessionId })));
      step.status = "DONE";
      step.error = undefined;
    } catch (error) {
      step.error = describe(error);
      if (isPermanent(error)) step.status = "FAILED";
    }
    await persist();
  }

  if (unit.steps.every((s) => s.status === "DONE")) await store.remove(unit.id);
  return unit;
}

/** An in-memory store, for the unit specs and as the fallback where IndexedDB is unavailable. */
export function memoryQueueStore(): QueueStore & { units: Map<string, SessionUnit>; blobs: Map<string, Blob> } {
  const units = new Map<string, SessionUnit>();
  const blobs = new Map<string, Blob>();
  return {
    units,
    blobs,
    async load() {
      return [...units.values()].map((u) => structuredClone(u));
    },
    async save(unit) {
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
