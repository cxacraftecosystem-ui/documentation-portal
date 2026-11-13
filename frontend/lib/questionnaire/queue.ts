import { ApiError, apiErrorMessage } from "@/lib/api";
import { ownerKey } from "@/lib/questionnaire/api";
import type {
  AnswerItem,
  AnswerOwner,
  AnswerResult,
  CompleteRecording,
  PresignRequest,
  QuestionnaireApi,
  Recording,
  Session,
  SessionCreate,
  UploadPart
} from "@/lib/questionnaire/api";

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
  blobKey: string;
  label?: string;
  sizeBytes: number;
  contentType: string;
  extension: string;
  presign: Omit<PresignRequest, "sessionId" | "linkedRecordType" | "clientClipKey" | "sizeBytes" | "contentType" | "extension">;
  recording: CompleteRecording;
  objectKey?: string;
  uploadId?: string | null;
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
  key?: string;
  error?: string;
  conflicts?: AnswerResult[];
};
export type Step = SessionStep | ParticipantStep | ClipStep | AnswersStep;

export type SessionUnit = {
  id: string;
  sessionId: string | null;
  createdAt: string;
  steps: Step[];
  ownerUserId?: string | null;
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
  slowLink?: boolean;
  onProgress?: (clientClipKey: string, fraction: number) => void;
  onRecording?: (recording: Recording) => void;
  onSession?: (session: Session) => void;
};

export const MAX_PARALLEL_CLIPS = 3;

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

export function newSessionUnit(body: SessionCreate, now = new Date()): SessionUnit {
  return { id: body.clientKey, sessionId: null, createdAt: now.toISOString(), steps: [{ kind: "session", status: "PENDING", body }] };
}

export function existingSessionUnit(session: Pick<Session, "id" | "clientKey">, now = new Date()): SessionUnit {
  return { id: sessionUnitKey(session), sessionId: session.id, createdAt: now.toISOString(), steps: [] };
}

export function stampOwner(unit: SessionUnit, ownerUserId: string | null): SessionUnit {
  return unit.ownerUserId ? unit : { ...unit, ownerUserId: ownerUserId || null };
}

export function unitOwnedBy(unit: Pick<SessionUnit, "ownerUserId">, userId: string | null): boolean {
  return Boolean(userId) && unit.ownerUserId === userId;
}

export function unitsOwnedBy(units: SessionUnit[], userId: string | null): SessionUnit[] {
  return units.filter((unit) => unitOwnedBy(unit, userId));
}

export type HeldUnits = { otherAccount: string[]; unowned: string[] };

export function heldUnits(units: SessionUnit[], userId: string | null): HeldUnits {
  const held: HeldUnits = { otherAccount: [], unowned: [] };
  if (!userId) return held;
  for (const unit of units) {
    if (!unit.ownerUserId) held.unowned.push(unit.id);
    else if (unit.ownerUserId !== userId) held.otherAccount.push(unit.id);
  }
  return held;
}

export async function drainableUnits(store: QueueStore, userId: string | null): Promise<SessionUnit[]> {
  if (!userId) return [];
  return unitsOwnedBy(await store.load(), userId);
}

export class QueueOwnerError extends Error {
  constructor() {
    super("Another account has unsent work for this session on this device.");
    this.name = "QueueOwnerError";
  }
}

function sameOwner(current: SessionUnit, base: SessionUnit): SessionUnit {
  if ((current.ownerUserId ?? null) !== (base.ownerUserId ?? null) && current.ownerUserId) throw new QueueOwnerError();
  return current;
}

export async function claimUnits(store: QueueStore, ids: readonly string[], userId: string): Promise<void> {
  for (const id of ids) {
    await mutateUnit(store, id, (current) => (current && !current.ownerUserId ? { ...current, ownerUserId: userId } : current));
  }
}

export async function discardUnits(store: QueueStore, ids: readonly string[]): Promise<void> {
  for (const id of ids) {
    await mutateUnit(store, id, (current) => {
      if (current) for (const s of current.steps) if (s.kind === "clip") void store.deleteBlob(s.blobKey).catch(() => undefined);
      return null;
    });
  }
}

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

export function pendingSteps(unit: SessionUnit): Step[] {
  return unit.steps.filter((s) => s.status !== "DONE");
}

export function pendingClipCount(units: SessionUnit[]): number {
  return units.reduce((n, u) => n + u.steps.filter((s) => s.kind === "clip" && s.status !== "DONE").length, 0);
}

const chains = new WeakMap<QueueStore, Map<string, Promise<unknown>>>();

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

export function enqueueStep(store: QueueStore, base: SessionUnit, step: Step): Promise<SessionUnit | null> {
  return mutateUnit(store, base.id, (current) => appendStep(current ? sameOwner(current, base) : base, step));
}

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

type ItemKey = AnswerOwner & { questionLineageId: string };
const sameItem = (a: ItemKey, b: ItemKey) => ownerKey(a) === ownerKey(b) && a.questionLineageId === b.questionLineageId;

export function heldAnswerResults(items: ItemKey[], results: AnswerResult[]): AnswerResult[] {
  return results.filter((r) => (r.status === "CONFLICT" || r.status === "FORBIDDEN") && items.some((item) => sameItem(item, r)));
}

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

export function resolveAnswerConflict(
  store: QueueStore,
  unitId: string,
  key: string,
  target: ItemKey,
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

export type TrayItem = {
  unitId: string;
  stepKey: string;
  step: Step;
  state: "failed" | "conflict";
};

export function trayItems(units: SessionUnit[]): TrayItem[] {
  return units.flatMap((unit) =>
    unit.steps.flatMap((step): TrayItem[] => {
      if (step.status === "DONE") return [];
      if (step.kind === "answers" && step.error === "CONFLICT") return [{ unitId: unit.id, stepKey: stepKey(step), step, state: "conflict" }];
      if (step.status === "FAILED") return [{ unitId: unit.id, stepKey: stepKey(step), step, state: "failed" }];
      return [];
    })
  );
}

export function sendStepAgain(store: QueueStore, unitId: string, key: string): Promise<SessionUnit | null> {
  return mutateUnit(store, unitId, (current) => {
    if (!current) return null;
    for (const step of current.steps) {
      if (stepKey(step) !== key || step.status !== "FAILED") continue;
      step.status = "PENDING";
      step.error = undefined;
      if (step.kind === "clip") step.attempts = 0;
    }
    return current;
  });
}

export function discardStep(store: QueueStore, unitId: string, key: string): Promise<SessionUnit | null> {
  return mutateUnit(store, unitId, (current) => {
    if (!current) return null;
    const step = current.steps.find((s) => stepKey(s) === key);
    if (!step) return current;
    if (step.kind === "session" && !current.sessionId) {
      for (const s of current.steps) if (s.kind === "clip") void store.deleteBlob(s.blobKey).catch(() => undefined);
      return null;
    }
    if (step.kind === "clip") void store.deleteBlob(step.blobKey).catch(() => undefined);
    const steps = current.steps.filter((s) => stepKey(s) !== key);
    return steps.some((s) => s.status !== "DONE") ? { ...current, steps } : null;
  });
}

export function sendWithoutConflicts(store: QueueStore, unitId: string, key: string): Promise<SessionUnit | null> {
  return mutateUnit(store, unitId, (current) => {
    if (!current) return null;
    const step = current.steps.find((s): s is AnswersStep => s.kind === "answers" && stepKey(s) === key);
    if (!step) return current;
    const held = step.conflicts ?? [];
    step.items = step.items.filter((item) => !held.some((r) => sameItem(r, item)));
    step.conflicts = undefined;
    step.error = undefined;
    step.status = step.items.length ? "PENDING" : "DONE";
    return current.steps.every((s) => s.status === "DONE") ? null : current;
  });
}

function describe(error: unknown): string {
  if (typeof error === "string" && error.trim()) return error;
  return apiErrorMessage(error, "Something went wrong. Try again.");
}

export function isPermanent(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429;
}

export function signedParts(signed: { urls?: Record<string, string> | null }, wanted: number[]): { partNumber: number; url: string }[] {
  const urls = signed.urls ?? {};
  const parts = wanted.map((partNumber) => ({ partNumber, url: urls[String(partNumber)] })).filter((p): p is { partNumber: number; url: string } => typeof p.url === "string" && p.url.length > 0);
  if (parts.length !== wanted.length) throw new Error("The upload server did not sign every part.");
  return parts;
}

async function uploadClip(step: ClipStep, sessionId: string, api: QueueApi, store: QueueStore, transport: ByteTransport, opts: QueueOptions, persist: () => Promise<void>) {
  const blob = await store.getBlob(step.blobKey);
  if (!blob) {
    step.status = "FAILED";
    step.error = "MISSING_FILE";
    return;
  }

  if (!step.uploaded) {
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
    if (step.status !== "PENDING") continue;
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
    if (step.kind !== "participant" || step.status !== "PENDING") continue;
    try {
      if (step.op === "add") await api.addParticipant(sessionId, step.artisanId, step.idempotencyKey);
      else await api.patchParticipant(sessionId, step.artisanId, step.op === "leave" ? { leftAt: step.at } : { joinedAt: step.at, leftAt: null });
      step.status = "DONE";
      step.error = undefined;
    } catch (error) {
      step.error = describe(error);
      if (isPermanent(error)) step.status = "FAILED";
    }
    await persist(step);
  }

  const clips = unit.steps.filter((s): s is ClipStep => s.kind === "clip" && s.status === "PENDING" && s.error !== "MISSING_FILE");
  const width = opts.slowLink ? 1 : MAX_PARALLEL_CLIPS;
  let cursor = 0;
  async function worker() {
    while (cursor < clips.length) {
      const step = clips[cursor];
      cursor += 1;
      step.attempts = (step.attempts ?? 0) + 1;
      try {
        await uploadClip(step, sessionId, api, store, transport, opts, () => persist(step));
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
    if (step.kind !== "answers" || step.status !== "PENDING" || step.error === "CONFLICT") continue;
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

  await mutateUnit(store, unit.id, (stored) => {
    const merged = mergeRunner(stored, unit, touched);
    return merged.steps.every((s) => s.status === "DONE") ? null : merged;
  });
  return unit;
}

export type ClipUploadState =
  | { kind: "UPLOADING"; percent: number }
  | { kind: "WAITING" }
  | { kind: "RETRYING"; error: string }
  | { kind: "FAILED"; error: string }
  | { kind: "UPLOADED" };

export function clipUploadState(step: ClipStep, progress: number | undefined, online: boolean): ClipUploadState {
  if (step.status === "DONE") return { kind: "UPLOADED" };
  if (step.status === "FAILED") return { kind: "FAILED", error: step.error ?? "" };
  if (!online) return { kind: "WAITING" };
  if (step.error) return { kind: "RETRYING", error: step.error };
  return { kind: "UPLOADING", percent: Math.round(Math.max(0, Math.min(1, progress ?? 0)) * 100) };
}

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

export const fetchTransport: ByteTransport = {
  async put(url, body, headers) {
    const response = await fetch(url, { method: "PUT", body, headers });
    if (!response.ok) throw new Error(`Upload refused (${response.status}).`);
    return { etag: response.headers.get("etag") };
  }
};
