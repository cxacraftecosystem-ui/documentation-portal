import { API_BASE, ApiError, apiFetch, getSessionMarker } from "@/lib/api";
import { field, questionSequence, recordingFilename, sectionSequence, splitExtension } from "@/lib/naming";
import { processingFor } from "@/lib/transcript/uploadChoice";
import type { MediaFile, MediaType } from "@/lib/types";

export function inferMediaType(file: File): MediaType {
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type === "application/pdf") return "PDF";
  return "DOCUMENT";
}

const KIND_WORD: Record<MediaType, string> = {
  IMAGE: "Photo",
  VIDEO: "Video",
  AUDIO: "Recording",
  PDF: "Document",
  DOCUMENT: "Document",
  OTHER: "File"
};

export function mediaKindWord(subject: File | MediaType): string {
  return KIND_WORD[typeof subject === "string" ? subject : inferMediaType(subject)] ?? "File";
}

export type MediaDescriptor = { what: string; sequence?: string };

function positiveIndex(index: number | null | undefined): number {
  const value = Math.trunc(index ?? 0);
  return value > 0 ? value : 1;
}

function twoDigits(index: number | null | undefined): string {
  return String(positiveIndex(index)).padStart(2, "0");
}

export function describeMedia(subject: File | MediaType, index: number): MediaDescriptor {
  return { what: mediaKindWord(subject), sequence: twoDigits(index) };
}

export function describeInterviewAudio({
  sectionCode,
  answerNumber,
  subject,
  index
}: {
  sectionCode: string | null | undefined;
  answerNumber?: number | null;
  subject: File | MediaType;
  index: number;
}): MediaDescriptor {
  const what = mediaKindWord(subject);
  if (!field(sectionCode)) return describeMedia(subject, index);
  if (answerNumber && answerNumber > 0) return { what, sequence: questionSequence(sectionCode, answerNumber) };
  return { what, sequence: sectionSequence(sectionCode) };
}

export function describeMeasurementGrid(
  axis: "lengthBreadth" | "height" | null | undefined,
  index = 1
): MediaDescriptor {
  const what =
    axis === "lengthBreadth" ? "Grid-Measurement-Length-Breadth" : axis === "height" ? "Grid-Measurement-Height" : "Grid-Measurement";
  return { what, sequence: twoDigits(index) };
}

export function describeProcessStep({
  stepNumber,
  subject,
  index
}: {
  stepNumber?: number | null;
  stepName?: string | null;
  subject: File | MediaType;
  index: number;
}): MediaDescriptor {
  const head = stepNumber && stepNumber > 0 ? `Step-${Math.trunc(stepNumber)}` : "Step";
  return { what: `${head}-${mediaKindWord(subject)}`, sequence: twoDigits(index) };
}

export function describePreProcess(subject: File | MediaType, index: number): MediaDescriptor {
  return { what: `Pre-Process-${mediaKindWord(subject)}`, sequence: twoDigits(index) };
}

export type MediaNameSpec = {
  recordType: string;
  recordName: string;
  descriptor: MediaDescriptor;
  at?: Date;
  place?: string | null;
  craft?: string | null;
};

function extensionOf(filename: string): string {
  return splitExtension(filename)[1];
}

export function mediaFileName(file: File, spec: MediaNameSpec): string {
  return recordingFilename(spec.recordType, extensionOf(file.name), {
    capturedAt: spec.at ?? new Date(),
    place: spec.place,
    craft: spec.craft,
    subject: spec.recordName,
    what: spec.descriptor.what,
    sequence: spec.descriptor.sequence
  });
}

export function renameMediaFile(file: File, spec: MediaNameSpec): File {
  const name = mediaFileName(file, spec);
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

const UPLOAD_MAX_ATTEMPTS = 3;

const RETRIABLE_GATEWAY_CODES = new Set([502, 503, 504]);
const SAFE_RETRY_ATTEMPTS = 4;

export const MULTIPART_THRESHOLD = 64 * 1024 * 1024;
const PART_CONCURRENCY = 3;
const PART_MAX_ATTEMPTS = 3;

export const UPLOAD_CONCURRENCY = 3;

const CHECKSUM_MAX_BYTES = 32 * 1024 * 1024;

const STALL_TIMEOUT_MS = 60_000;
const FINALIZE_TIMEOUT_MS = 5 * 60_000;

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const AUDIO_RECORDER_MIME_TYPES = [
  "audio/webm;codecs=opus",
  "audio/webm",
  "audio/mp4;codecs=mp4a.40.2",
  "audio/mp4",
  "audio/ogg;codecs=opus",
  "audio/ogg"
];

export function pickAudioRecorderMimeType(): string | null {
  if (typeof MediaRecorder === "undefined" || typeof MediaRecorder.isTypeSupported !== "function") return null;
  return AUDIO_RECORDER_MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) ?? null;
}

export const SPEECH_AUDIO_CONSTRAINTS: MediaTrackConstraints = {
  noiseSuppression: true,
  echoCancellation: true,
  autoGainControl: true,
  channelCount: 1,
  sampleRate: 44_100
};

export function audioExtensionForMimeType(mimeType: string | null | undefined): string {
  const type = (mimeType ?? "").toLowerCase();
  if (type.includes("webm")) return "webm";
  if (type.includes("mp4") || type.includes("m4a") || type.includes("aac")) return "m4a";
  if (type.includes("ogg") || type.includes("opus")) return "ogg";
  if (type.includes("wav")) return "wav";
  if (type.includes("mpeg") || type.includes("mp3")) return "mp3";
  return "webm";
}

class StorageError extends Error {
  status: number | null;

  constructor(message: string, status: number | null = null) {
    super(message);
    this.name = "StorageError";
    this.status = status;
  }
}

const UPLOAD_MISSING_STATUS = 409;

class EtagUnavailableError extends Error {}

function isRetriableApiFailure(error: unknown): boolean {
  if (error instanceof ApiError) return RETRIABLE_GATEWAY_CODES.has(error.status);
  return error instanceof TypeError;
}

async function apiRetry<T>(path: string, init: RequestInit, attempts = SAFE_RETRY_ATTEMPTS): Promise<T> {
  let lastError: unknown;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await apiFetch<T>(path, init);
    } catch (error) {
      lastError = error;
      if (attempt >= attempts || !isRetriableApiFailure(error)) throw error;
      await delay(Math.min(4_000, 600 * attempt));
    }
  }
  throw lastError;
}

function putBlob({
  url,
  headers,
  body,
  onProgress,
  signal
}: {
  url: string;
  headers?: Record<string, string>;
  body: Blob;
  onProgress?: (loaded: number, total: number) => void;
  signal?: AbortSignal;
}): Promise<{ etag: string | null }> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new StorageError("Upload cancelled"));
      return;
    }
    const total = body.size;
    const xhr = new XMLHttpRequest();
    let stalled = false;
    let watchdog: number | null = null;
    const arm = (ms: number) => {
      if (watchdog !== null) window.clearTimeout(watchdog);
      watchdog = window.setTimeout(() => {
        stalled = true;
        xhr.abort();
      }, ms);
    };
    const abortFromSignal = () => xhr.abort();
    const settle = (finish: () => void) => {
      if (watchdog !== null) window.clearTimeout(watchdog);
      watchdog = null;
      signal?.removeEventListener("abort", abortFromSignal);
      finish();
    };

    xhr.open("PUT", url, true);
    Object.entries(headers ?? {}).forEach(([key, value]) => xhr.setRequestHeader(key, value));
    xhr.timeout = 0;
    xhr.upload.onprogress = (event) => {
      arm(STALL_TIMEOUT_MS);
      if (event.lengthComputable) onProgress?.(event.loaded, event.total);
    };
    xhr.upload.onload = () => arm(FINALIZE_TIMEOUT_MS);
    xhr.onload = () =>
      settle(() => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.(total, total);
          resolve({ etag: (xhr.getResponseHeader("ETag") ?? "").replace(/"/g, "") || null });
        } else {
          reject(new StorageError("Upload didn’t finish. Try again.", xhr.status));
        }
      });
    xhr.onerror = () => settle(() => reject(new StorageError("Upload didn’t finish. Try again.")));
    xhr.onabort = () =>
      settle(() =>
        reject(
          new StorageError(
            stalled
              ? "Upload stopped. Try again."
              : "Upload cancelled"
          )
        )
      );

    signal?.addEventListener("abort", abortFromSignal, { once: true });
    arm(STALL_TIMEOUT_MS);
    xhr.send(body);
  });
}

async function runPool<T>(items: T[], limit: number, worker: (item: T, index: number) => Promise<void>): Promise<void> {
  let cursor = 0;
  let firstError: unknown = null;
  const runner = async () => {
    while (cursor < items.length && firstError === null) {
      const index = cursor;
      cursor += 1;
      try {
        await worker(items[index], index);
      } catch (error) {
        if (firstError === null) firstError = error;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, runner));
  if (firstError !== null) throw firstError;
}

async function computeChecksum(file: File): Promise<string | null> {
  if (file.size === 0 || file.size > CHECKSUM_MAX_BYTES) return null;
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  try {
    const digest = await subtle.digest("SHA-256", await file.arrayBuffer());
    return `sha256:${Array.from(new Uint8Array(digest))
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("")}`;
  } catch {
    return null;
  }
}

export type StagedObject = {
  objectKey: string;
  bucket: string;
  publicUrl: string | null;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes: number;
  checksum: string | null;
};

type UploadTarget = { objectKey: string; bucket: string; publicUrl: string | null };

type PresignResponse = {
  uploadUrl: string;
  objectKey: string;
  bucket: string;
  headers: Record<string, string>;
  publicUrl?: string;
};

type MultipartCreateResponse = {
  objectKey: string;
  uploadId: string;
  bucket: string;
  partSize: number;
  partCount: number;
  publicUrl?: string;
};

type MultipartPartsResponse = { urls: Record<string, string> };
type MultipartCompleteResponse = { objectKey: string; bucket: string; publicUrl?: string };

type ObjectUploadOptions = {
  file: File;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  onProgress?: (loaded: number, total: number) => void;
  onObjectKey?: (objectKey: string) => void;
  signal?: AbortSignal;
};

let multipartAvailable = true;

export function uploadStorageObject(options: ObjectUploadOptions): Promise<StagedObject> {
  return uploadObject(options);
}

async function uploadObject(options: ObjectUploadOptions): Promise<StagedObject> {
  const { file } = options;
  if (file.size === 0) throw new Error(`"${file.name}" is empty.`);
  const mediaType = inferMediaType(file);
  const mimeType = file.type || "application/octet-stream";
  const checksum = computeChecksum(file);
  const target =
    file.size > MULTIPART_THRESHOLD && multipartAvailable
      ? await uploadInParts(options, mediaType, mimeType)
      : await uploadWhole(options, mediaType, mimeType);
  return { ...target, mediaType, mimeType, sizeBytes: file.size, checksum: await checksum };
}

async function uploadWhole(options: ObjectUploadOptions, mediaType: MediaType, mimeType: string): Promise<UploadTarget> {
  const { file, linkedRecordType, linkedRecordId, onProgress, onObjectKey, signal } = options;
  let lastError: unknown;
  let previousKey: string | null = null;
  for (let attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt += 1) {
    const presign = await apiRetry<PresignResponse>("/media/presign", {
      method: "POST",
      body: JSON.stringify({
        filename: file.name,
        mimeType,
        mediaType,
        sizeBytes: file.size,
        linkedRecordType,
        linkedRecordId
      })
    });
    if (previousKey) liveObjectKeys.delete(previousKey);
    previousKey = presign.objectKey;
    onObjectKey?.(presign.objectKey);
    try {
      await putBlob({ url: presign.uploadUrl, headers: presign.headers, body: file, onProgress, signal });
      return { objectKey: presign.objectKey, bucket: presign.bucket, publicUrl: presign.publicUrl ?? null };
    } catch (error) {
      if (signal?.aborted) throw error;
      lastError = error;
      onProgress?.(0, file.size);
      if (attempt < UPLOAD_MAX_ATTEMPTS) await delay(800 * attempt);
    }
  }
  throw lastError instanceof Error ? lastError : new StorageError(`${file.name} didn’t upload. Try again.`);
}

async function uploadInParts(options: ObjectUploadOptions, mediaType: MediaType, mimeType: string): Promise<UploadTarget> {
  const { file, linkedRecordType, linkedRecordId, onProgress, onObjectKey, signal } = options;
  const create = await apiRetry<MultipartCreateResponse>("/media/multipart/create", {
    method: "POST",
    body: JSON.stringify({
      filename: file.name,
      mimeType,
      mediaType,
      sizeBytes: file.size,
      linkedRecordType,
      linkedRecordId
    })
  });
  onObjectKey?.(create.objectKey);

  const partNumbers = Array.from({ length: create.partCount }, (_, index) => index + 1);
  const loaded = new Array<number>(create.partCount).fill(0);
  const etags = new Array<string>(create.partCount);
  const report = () => onProgress?.(loaded.reduce((sum, value) => sum + value, 0), file.size);

  const presignParts = (numbers: number[]) =>
    apiRetry<MultipartPartsResponse>("/media/multipart/presign-parts", {
      method: "POST",
      body: JSON.stringify({ objectKey: create.objectKey, uploadId: create.uploadId, partNumbers: numbers })
    });

  const urls = (await presignParts(partNumbers)).urls;

  async function sendPart(partNumber: number): Promise<string | null> {
    let url = urls[String(partNumber)];
    if (!url) throw new StorageError(`Missing presigned URL for part ${partNumber}`);
    const start = (partNumber - 1) * create.partSize;
    let lastError: unknown;
    for (let attempt = 1; attempt <= PART_MAX_ATTEMPTS; attempt += 1) {
      try {
        const { etag } = await putBlob({
          url,
          body: file.slice(start, Math.min(file.size, start + create.partSize)),
          onProgress: (sent) => {
            loaded[partNumber - 1] = sent;
            report();
          },
          signal
        });
        return etag;
      } catch (error) {
        if (signal?.aborted) throw error;
        lastError = error;
        loaded[partNumber - 1] = 0;
        report();
        if (error instanceof StorageError && error.status === 403) {
          const refreshed = await presignParts([partNumber]).catch(() => null);
          const fresh = refreshed?.urls?.[String(partNumber)];
          if (fresh) url = fresh;
        }
        if (attempt < PART_MAX_ATTEMPTS) await delay(800 * attempt);
      }
    }
    throw lastError instanceof Error ? lastError : new StorageError(`Part ${partNumber} failed to upload`);
  }

  try {
    const firstEtag = await sendPart(1);
    if (!firstEtag) throw new EtagUnavailableError();
    etags[0] = firstEtag;
    await runPool(partNumbers.slice(1), PART_CONCURRENCY, async (partNumber) => {
      const etag = await sendPart(partNumber);
      if (!etag) throw new EtagUnavailableError();
      etags[partNumber - 1] = etag;
    });
    const done = await apiRetry<MultipartCompleteResponse>("/media/multipart/complete", {
      method: "POST",
      body: JSON.stringify({
        objectKey: create.objectKey,
        uploadId: create.uploadId,
        parts: etags.map((etag, index) => ({ partNumber: index + 1, etag }))
      })
    });
    return { objectKey: done.objectKey, bucket: done.bucket, publicUrl: done.publicUrl ?? null };
  } catch (error) {
    await apiFetch("/media/multipart/abort", {
      method: "POST",
      body: JSON.stringify({ objectKey: create.objectKey, uploadId: create.uploadId })
    }).catch(() => undefined);
    forgetStagedObject(create.objectKey);
    if (error instanceof EtagUnavailableError) {
      multipartAvailable = false;
      loaded.fill(0);
      report();
      return uploadWhole(options, mediaType, mimeType);
    }
    throw error;
  }
}

const STAGED_JOURNAL_KEY = "field_repo_staged_objects";
const STAGED_STALE_MS = 5 * 60 * 1000;
const STAGED_HEARTBEAT_MS = 60 * 1000;
const STAGED_SWEEP_INTERVAL_MS = 5 * 60 * 1000;
const STAGED_SWEEP_BATCH = 20;

const liveObjectKeys = new Set<string>();
let heartbeatTimer: number | null = null;
let sweepTimer: number | null = null;
let sweptThisSession = false;

function readJournal(): Record<string, number> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(STAGED_JOURNAL_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    return parsed && typeof parsed === "object" ? (parsed as Record<string, number>) : {};
  } catch {
    return {};
  }
}

function writeJournal(entries: Record<string, number>) {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(STAGED_JOURNAL_KEY, JSON.stringify(entries));
  } catch {
  }
}

function rememberStagedObject(objectKey: string) {
  liveObjectKeys.add(objectKey);
  const journal = readJournal();
  journal[objectKey] = Date.now();
  writeJournal(journal);
  startHeartbeat();
}

function forgetStagedObject(objectKey: string) {
  liveObjectKeys.delete(objectKey);
  const journal = readJournal();
  if (objectKey in journal) {
    delete journal[objectKey];
    writeJournal(journal);
  }
}

function startHeartbeat() {
  if (heartbeatTimer !== null || typeof window === "undefined") return;
  heartbeatTimer = window.setInterval(() => {
    if (liveObjectKeys.size === 0) {
      if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer);
      heartbeatTimer = null;
      return;
    }
    const journal = readJournal();
    const now = Date.now();
    liveObjectKeys.forEach((key) => {
      journal[key] = now;
    });
    writeJournal(journal);
  }, STAGED_HEARTBEAT_MS);
}

async function deleteStagedObject(objectKey: string) {
  liveObjectKeys.delete(objectKey);
  try {
    await apiFetch<void>(`/media/object?objectKey=${encodeURIComponent(objectKey)}`, { method: "DELETE" });
    forgetStagedObject(objectKey);
  } catch (error) {
    if (error instanceof ApiError) forgetStagedObject(objectKey);
  }
}

function beaconDeleteStagedObject(objectKey: string) {
  if (!getSessionMarker()) return;
  liveObjectKeys.delete(objectKey);
  try {
    void fetch(`${API_BASE}/api/media/object?objectKey=${encodeURIComponent(objectKey)}`, {
      method: "DELETE",
      keepalive: true
    }).catch(() => undefined);
  } catch {
  }
}

export async function sweepStagedObjects(): Promise<number> {
  if (typeof window === "undefined" || !getSessionMarker()) return 0;
  const journal = readJournal();
  const now = Date.now();
  const stale = Object.keys(journal)
    .filter((key) => !liveObjectKeys.has(key) && now - (journal[key] ?? 0) > STAGED_STALE_MS)
    .slice(0, STAGED_SWEEP_BATCH);
  for (const key of stale) {
    await deleteStagedObject(key);
  }
  return stale.length;
}

export function scheduleStagedObjectSweep() {
  if (typeof window === "undefined") return;
  if (!sweptThisSession) {
    sweptThisSession = true;
    window.setTimeout(() => void sweepStagedObjects(), 4_000);
  }
  if (sweepTimer === null) {
    sweepTimer = window.setInterval(() => void sweepStagedObjects(), STAGED_SWEEP_INTERVAL_MS);
  }
}

export type StageStatus = "uploading" | "ready" | "error";

export type StageEntry = {
  file: File;
  status: StageStatus;
  loaded: number;
  total: number;
  error: string | null;
  objectKey: string | null;
  staged: StagedObject | null;
};

function signatureOf(file: File): string {
  return `${file.size}:${file.lastModified}:${file.type}`;
}

class StageRecord {
  readonly file: File;
  readonly signature: string;
  readonly owners = new Set<string>();
  readonly controller = new AbortController();
  readonly promise: Promise<StagedObject>;
  status: StageStatus = "uploading";
  loaded = 0;
  error: string | null = null;
  objectKey: string | null = null;
  staged: StagedObject | null = null;

  constructor(file: File) {
    this.file = file;
    this.signature = signatureOf(file);
    this.promise = runStage(this);
    void this.promise.catch(() => undefined);
  }

  get total(): number {
    return this.file.size;
  }
}

const stagedByFile = new Map<File, StageRecord>();
const stagingListeners = new Set<() => void>();
const EMPTY_STAGING: ReadonlyMap<File, StageEntry> = new Map();
let stagingSnapshot: ReadonlyMap<File, StageEntry> = EMPTY_STAGING;

function notifyStaging() {
  const next = new Map<File, StageEntry>();
  stagedByFile.forEach((record, file) => {
    next.set(file, {
      file,
      status: record.status,
      loaded: record.loaded,
      total: record.total,
      error: record.error,
      objectKey: record.objectKey,
      staged: record.staged
    });
  });
  stagingSnapshot = next;
  stagingListeners.forEach((listener) => listener());
}

export function subscribeStaging(listener: () => void): () => void {
  stagingListeners.add(listener);
  return () => {
    stagingListeners.delete(listener);
  };
}

export function getStagingSnapshot(): ReadonlyMap<File, StageEntry> {
  return stagingSnapshot;
}

export function getServerStagingSnapshot(): ReadonlyMap<File, StageEntry> {
  return EMPTY_STAGING;
}

function setStageProgress(record: StageRecord, loaded: number) {
  const before = record.total > 0 ? Math.floor((record.loaded * 100) / record.total) : 0;
  const after = record.total > 0 ? Math.floor((loaded * 100) / record.total) : 0;
  record.loaded = loaded;
  if (after !== before) notifyStaging();
}

async function runStage(record: StageRecord): Promise<StagedObject> {
  try {
    const staged = await uploadObject({
      file: record.file,
      signal: record.controller.signal,
      onObjectKey: (objectKey) => {
        record.objectKey = objectKey;
        rememberStagedObject(objectKey);
      },
      onProgress: (loaded) => setStageProgress(record, loaded)
    });
    record.staged = staged;
    record.status = "ready";
    record.loaded = staged.sizeBytes;
    record.error = null;
    notifyStaging();
    return staged;
  } catch (error) {
    record.status = "error";
    record.error = error instanceof Error ? error.message : "Upload failed";
    if (record.objectKey) liveObjectKeys.delete(record.objectKey);
    notifyStaging();
    throw error;
  }
}

let lifecycleInstalled = false;

function installLifecycleHooks() {
  if (lifecycleInstalled || typeof window === "undefined") return;
  lifecycleInstalled = true;
  window.addEventListener("beforeunload", (event) => {
    const busy = Array.from(stagedByFile.values()).some((record) => record.status === "uploading");
    if (!busy) return;
    event.preventDefault();
    event.returnValue = "";
  });
  window.addEventListener("pagehide", (event) => {
    if (event.persisted) return;
    stagedByFile.forEach((record) => {
      record.controller.abort();
      const objectKey = record.staged?.objectKey ?? record.objectKey;
      if (objectKey) beaconDeleteStagedObject(objectKey);
    });
  });
}

export function stageFiles(files: File[], ownerId: string) {
  if (typeof window === "undefined" || !getSessionMarker()) return;
  cancelStagedOwnerRelease(ownerId);
  installLifecycleHooks();
  scheduleStagedObjectSweep();
  let changed = false;
  files.forEach((file) => {
    if (file.size === 0) return;
    let record = stagedByFile.get(file);
    if (!record) {
      record = new StageRecord(file);
      stagedByFile.set(file, record);
      changed = true;
    }
    if (!record.owners.has(ownerId)) record.owners.add(ownerId);
  });
  if (changed) notifyStaging();
}

export function discardStagedFile(file: File) {
  const record = stagedByFile.get(file);
  if (!record) return;
  stagedByFile.delete(file);
  notifyStaging();
  record.controller.abort();
  discardRecordObject(record);
}

export function retryStagedFile(file: File) {
  const previous = stagedByFile.get(file);
  if (!previous) return;
  previous.controller.abort();
  discardRecordObject(previous);
  const record = new StageRecord(file);
  previous.owners.forEach((owner) => record.owners.add(owner));
  stagedByFile.set(file, record);
  notifyStaging();
}

const RELEASE_GRACE_MS = 2_000;
const releaseTimers = new Map<string, number>();

function cancelStagedOwnerRelease(ownerId: string) {
  const timer = releaseTimers.get(ownerId);
  if (timer === undefined) return;
  window.clearTimeout(timer);
  releaseTimers.delete(ownerId);
}

export function releaseStagedOwner(ownerId: string) {
  if (typeof window === "undefined") return;
  cancelStagedOwnerRelease(ownerId);
  releaseTimers.set(
    ownerId,
    window.setTimeout(() => {
      releaseTimers.delete(ownerId);
      let changed = false;
      stagedByFile.forEach((record, file) => {
        if (!record.owners.delete(ownerId)) return;
        if (record.owners.size > 0) return;
        stagedByFile.delete(file);
        record.controller.abort();
        discardRecordObject(record);
        changed = true;
      });
      if (changed) notifyStaging();
    }, RELEASE_GRACE_MS)
  );
}

function discardRecordObject(record: StageRecord) {
  void record.promise
    .then((staged) => deleteStagedObject(staged.objectKey))
    .catch(() => {
      if (record.objectKey) void deleteStagedObject(record.objectKey);
    });
}

function takeStagedFor(files: File[]): Array<StageRecord | null> {
  const taken = new Array<StageRecord | null>(files.length).fill(null);
  const unmatched: number[] = [];
  files.forEach((file, index) => {
    const record = stagedByFile.get(file);
    if (record) {
      stagedByFile.delete(file);
      taken[index] = record;
    } else {
      unmatched.push(index);
    }
  });
  if (unmatched.length && stagedByFile.size) {
    const bySignature = new Map<string, StageRecord[]>();
    stagedByFile.forEach((record) => {
      const list = bySignature.get(record.signature);
      if (list) list.push(record);
      else bySignature.set(record.signature, [record]);
    });
    unmatched.forEach((index) => {
      const signature = signatureOf(files[index]);
      const candidates = bySignature.get(signature);
      if (!candidates || candidates.length !== 1) return;
      const record = candidates[0];
      stagedByFile.delete(record.file);
      bySignature.delete(signature);
      taken[index] = record;
    });
  }
  if (taken.some(Boolean)) notifyStaging();
  return taken;
}

async function awaitStaged(record: StageRecord, onProgress?: (loaded: number, total: number) => void) {
  if (!onProgress) return record.promise;
  onProgress(record.loaded, record.total);
  const unsubscribe = subscribeStaging(() => onProgress(record.loaded, record.total));
  try {
    return await record.promise;
  } finally {
    unsubscribe();
  }
}

type CompleteParams = {
  caption?: string;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  processingRequests: string[];
};

function resolveProcessing(file: File, requested: string[] | undefined, always: boolean, flag?: boolean): string[] {
  return processingFor(file, requested, { always, flag });
}

async function completeUpload(file: File, staged: StagedObject, params: CompleteParams): Promise<MediaFile> {
  return apiRetry<MediaFile>("/media/complete", {
    method: "POST",
    body: JSON.stringify({
      originalFilename: file.name,
      mediaType: staged.mediaType,
      mimeType: staged.mimeType,
      sizeBytes: staged.sizeBytes,
      objectKey: staged.objectKey,
      bucket: staged.bucket,
      url: staged.publicUrl,
      checksum: staged.checksum,
      caption: params.caption,
      linkedRecordType: params.linkedRecordType,
      linkedRecordId: params.linkedRecordId,
      location: params.location,
      extraMetadata: params.extraMetadata,
      recordedAt: params.recordedAt,
      recordedTimezone: params.recordedTimezone,
      processingRequests: params.processingRequests
    })
  });
}

async function linkOrUpload({
  file,
  record,
  params,
  onProgress
}: {
  file: File;
  record: StageRecord | null;
  params: CompleteParams;
  onProgress?: (loaded: number, total: number) => void;
}): Promise<MediaFile> {
  let staged: StagedObject | null = null;
  if (record) {
    if (record.status === "error") {
      discardRecordObject(record);
    } else {
      try {
        staged = await awaitStaged(record, onProgress);
      } catch {
        discardRecordObject(record);
      }
    }
  }
  const send = () =>
    uploadObject({
      file,
      linkedRecordType: params.linkedRecordType,
      linkedRecordId: params.linkedRecordId,
      onProgress,
      onObjectKey: rememberStagedObject
    });
  if (!staged) staged = await send();
  try {
    const media = await completeUpload(file, staged, params);
    forgetStagedObject(staged.objectKey);
    return media;
  } catch (error) {
    if (!uploadNeverLanded(error)) throw error;
    forgetStagedObject(staged.objectKey);
  }
  const again = await send();
  const media = await completeUpload(file, again, params);
  forgetStagedObject(again.objectKey);
  return media;
}

export function uploadNeverLanded(error: unknown): boolean {
  return error instanceof ApiError && error.status === UPLOAD_MISSING_STATUS;
}

export async function uploadMediaFile({
  file,
  linkedRecordType,
  linkedRecordId,
  caption,
  location,
  extraMetadata,
  recordedAt,
  recordedTimezone,
  alwaysTranscribe = false,
  transcribe,
  processingRequests,
  onProgress
}: {
  file: File;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  caption?: string;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  alwaysTranscribe?: boolean;
  transcribe?: boolean;
  processingRequests?: string[];
  onProgress?: (loaded: number, total: number) => void;
}) {
  const [record] = takeStagedFor([file]);
  return linkOrUpload({
    file,
    record,
    onProgress,
    params: {
      caption,
      linkedRecordType,
      linkedRecordId,
      location,
      extraMetadata,
      recordedAt,
      recordedTimezone,
      processingRequests: resolveProcessing(file, processingRequests, alwaysTranscribe, transcribe)
    }
  });
}

export type BatchProgress = {
  fileIndex: number;
  fileCount: number;
  uploadedBytes: number;
  totalBytes: number;
  fraction: number;
  etaSeconds: number | null;
  currentFileName: string;
  files?: File[];
};

export type BatchResult = {
  uploaded: MediaFile[];
  failed: Array<{ name: string; error: string }>;
};

export async function uploadMediaBatch({
  files,
  linkedRecordType,
  linkedRecordId,
  caption,
  location,
  extraMetadata,
  recordedAt,
  recordedTimezone,
  alwaysTranscribe = false,
  transcribe,
  processingRequests,
  onProgress
}: {
  files: File[];
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  caption?: string;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  alwaysTranscribe?: boolean;
  transcribe?: readonly (boolean | undefined)[];
  processingRequests?: string[];
  onProgress?: (progress: BatchProgress) => void;
}): Promise<BatchResult> {
  const records = takeStagedFor(files);
  const uploadedByIndex = new Array<MediaFile | null>(files.length).fill(null);
  const failed: Array<{ name: string; error: string }> = [];
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0) || 1;
  const loaded = new Array<number>(files.length).fill(0);
  const startedAt = Date.now();
  let completedFiles = 0;
  let currentFileName = files[0]?.name ?? "";

  const report = () => {
    const uploadedBytes = Math.min(
      totalBytes,
      loaded.reduce((sum, value) => sum + value, 0)
    );
    const elapsed = (Date.now() - startedAt) / 1000;
    const rate = elapsed > 0 ? uploadedBytes / elapsed : 0;
    onProgress?.({
      fileIndex: Math.min(completedFiles, Math.max(0, files.length - 1)),
      fileCount: files.length,
      uploadedBytes,
      totalBytes,
      fraction: uploadedBytes / totalBytes,
      etaSeconds: rate > 0 ? Math.max(0, Math.round((totalBytes - uploadedBytes) / rate)) : null,
      currentFileName,
      files
    });
  };

  report();
  await runPool(files, UPLOAD_CONCURRENCY, async (file, index) => {
    currentFileName = file.name;
    report();
    try {
      uploadedByIndex[index] = await linkOrUpload({
        file,
        record: records[index],
        onProgress: (sent) => {
          loaded[index] = sent;
          report();
        },
        params: {
          caption,
          linkedRecordType,
          linkedRecordId,
          location,
          extraMetadata,
          recordedAt,
          recordedTimezone,
          processingRequests: resolveProcessing(file, processingRequests, alwaysTranscribe, transcribe?.[index])
        }
      });
    } catch (error) {
      failed.push({ name: file.name, error: error instanceof Error ? error.message : "Upload failed" });
    }
    loaded[index] = file.size;
    completedFiles += 1;
    report();
  });

  if (files.length > 0 && failed.length === files.length) {
    throw new Error(
      `No files uploaded (${failed[0]?.error ?? "network error"}). The record was saved — reopen it to re-attach.`
    );
  }
  return { uploaded: uploadedByIndex.filter((item): item is MediaFile => item !== null), failed };
}

export async function transcribeMediaFile(file: File, mediaType = inferMediaType(file)) {
  if (mediaType !== "AUDIO") return {};
  const form = new FormData();
  form.append("file", file);
  const result = await apiFetch<{
    available: boolean;
    status: string;
    text?: string | null;
    formattedTranscript?: string | null;
    message?: string;
  }>("/media/transcribe", { method: "POST", body: form });
  return {
    transcriptText: result.formattedTranscript ?? result.text ?? null,
    transcriptStatus: result.status,
    transcriptError: result.available ? null : result.message ?? "Transcription isn’t available right now."
  };
}

export type MeasurementMethodMarker = {
  method: string;
  provider?: string;
  modelId?: string;
  selfReportedConfidence?: number;
  technique?: string;
};

export type MeasurementAnalysisResponse = {
  available: boolean;
  status: string;
  analysis?: {
    valueInches?: number | string | null;
    lengthInches?: number | string | null;
    breadthInches?: number | string | null;
    notes?: string;
  } | null;
  message?: string;
  method?: string;
  provider?: string | null;
  modelId?: string | null;
  selfReportedConfidence?: number | null;
  confidenceIsCalibrated?: boolean;
  requiresAcceptance?: boolean;
  methodMarker?: MeasurementMethodMarker | null;
};

export async function analyzeMeasurementImage(file: File, dimension?: "length" | "breadth" | "height") {
  const form = new FormData();
  form.append("file", file);
  const query = dimension ? `?dimension=${dimension}` : "";
  return apiFetch<MeasurementAnalysisResponse>(`/media/analyze-measurement${query}`, { method: "POST", body: form });
}

export async function extractImageExifMetadata(file: File) {
  if (!file.type.startsWith("image/")) return null;
  try {
    const exifr = await import("exifr");
    const metadata = await exifr.parse(file, {
      tiff: true,
      exif: true,
      gps: true,
      reviveValues: false
    });
    if (!metadata) return null;
    const keys = [
      "Make",
      "Model",
      "DateTimeOriginal",
      "CreateDate",
      "ModifyDate",
      "latitude",
      "longitude",
      "GPSLatitude",
      "GPSLongitude",
      "GPSAltitude",
      "Orientation",
      "LensModel"
    ];
    return Object.fromEntries(keys.filter((key) => metadata[key] !== undefined).map((key) => [key, metadata[key]]));
  } catch {
    return null;
  }
}

export async function collectExifMetadata(files: File[]) {
  const results = await Promise.all(
    files.map(async (file) => {
      const metadata = await extractImageExifMetadata(file);
      return metadata ? { filename: file.name, metadata } : null;
    })
  );
  return results.filter(Boolean) as Array<{ filename: string; metadata: Record<string, unknown> }>;
}

export function exifMetadataToRemark(items: Array<{ filename: string; metadata: Record<string, unknown> }>) {
  if (!items.length) return "";
  return items
    .map(({ filename, metadata }) => {
      const camera = [metadata.Make, metadata.Model].filter(Boolean).join(" ");
      const captured = metadata.DateTimeOriginal ?? metadata.CreateDate ?? metadata.ModifyDate;
      const latitude = metadata.latitude ?? metadata.GPSLatitude;
      const longitude = metadata.longitude ?? metadata.GPSLongitude;
      return [
        `Image EXIF (${filename})`,
        camera ? `camera: ${camera}` : null,
        captured ? `captured: ${captured}` : null,
        latitude && longitude ? `gps: ${latitude}, ${longitude}` : null
      ]
        .filter(Boolean)
        .join("; ");
    })
    .join("\n");
}

export function appendRemarksWithExif(remarks: string | null, exifRemark: string) {
  const base = remarks?.trim() ?? "";
  if (!exifRemark) return base || null;
  return [base, exifRemark].filter(Boolean).join("\n\n");
}
