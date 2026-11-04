"use client";

import { ApiError, apiFetch } from "@/lib/api";
import { holdFromPayload } from "@/lib/duplicates";
import { uploadMediaBatch } from "@/lib/media";
import { transcribeFlags } from "@/lib/transcript/uploadChoice";
import { countNoun } from "@/lib/format";

const DB_NAME = "field-repo-outbox";
const DB_VERSION = 1;
const STORE = "entries";

export const CLIENT_KEY_ENDPOINTS: readonly string[] = ["/workshops", "/products", "/tools", "/processes"];

export function endpointTakesClientKey(endpoint: string): boolean {
  const path = endpoint.split("?")[0].replace(/\/+$/, "");
  return CLIENT_KEY_ENDPOINTS.includes(path);
}

export function mintClientKey(): string {
  const c = typeof globalThis !== "undefined" ? (globalThis.crypto as Crypto | undefined) : undefined;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;
}

function bodyWithClientKey(endpoint: string, method: "POST" | "PATCH", body: unknown): unknown {
  if (method !== "POST") return body;
  if (!endpointTakesClientKey(endpoint)) return body;
  if (typeof body !== "object" || body === null || Array.isArray(body)) return body;
  if ("clientKey" in (body as Record<string, unknown>)) return body;
  return { ...(body as Record<string, unknown>), clientKey: mintClientKey() };
}

export type OutboxMediaBatch = {
  files: File[];
  linkedRecordType: string;
  caption?: string;
  location?: unknown;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: Record<string, unknown>;
  transcribeAudio?: boolean;
  alwaysTranscribe?: boolean;
  transcribe?: boolean[];
  stepIndex?: number;
  linkedRecordId?: string | null;
};

export function withTranscribeFlags(batch: OutboxMediaBatch): OutboxMediaBatch {
  return batch.transcribe ? batch : { ...batch, transcribe: transcribeFlags(batch.files) };
}

export function transcribeFlagsFor(batch: OutboxMediaBatch, subset: readonly File[]): boolean[] {
  return subset.map((file) => {
    const position = batch.files.indexOf(file);
    return position >= 0 ? Boolean(batch.transcribe?.[position]) : false;
  });
}

export type OutboxEntry = {
  id?: number;
  label: string;
  createdAt: number;
  endpoint: string;
  method: "POST" | "PATCH";
  body: string;
  media: OutboxMediaBatch[];
  attempts: number;
  failure: string | null;
  created?: boolean;
  createdId?: string | null;
  createdStepIds?: string[];
  uploadedBatches?: number[];
  mediaOnly?: boolean;
  possibleDuplicate?: {
    message: string;
    candidates: Array<{ id: string; name: string; place?: string | null; craft?: string | null }>;
  } | null;
  attachedToExisting?: boolean;
};

export function possibleDuplicateSentence(entry: Pick<OutboxEntry, "possibleDuplicate">): string | null {
  const first = entry.possibleDuplicate?.candidates?.[0];
  if (!first) return null;
  return `Not saved yet. This looks like ${first.name}${first.place ? ` (${first.place})` : ""}.`;
}

export function samePersonEntry(entry: OutboxEntry, candidateId: string): OutboxEntry {
  return {
    ...entry,
    created: true,
    createdId: candidateId,
    attachedToExisting: true,
    possibleDuplicate: null,
    failure: null
  };
}

export function differentPersonEntry(entry: OutboxEntry): OutboxEntry {
  const ids = (entry.possibleDuplicate?.candidates ?? []).map((candidate) => candidate.id);
  let body: Record<string, unknown> = {};
  try {
    const parsed = JSON.parse(entry.body);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) body = parsed as Record<string, unknown>;
  } catch {
    body = {};
  }
  const previous = Array.isArray(body.duplicateAcknowledged) ? (body.duplicateAcknowledged as string[]) : [];
  const acknowledged = Array.from(new Set([...previous, ...ids])).slice(0, 20);
  return {
    ...entry,
    body: JSON.stringify({ ...body, duplicateAcknowledged: acknowledged }),
    possibleDuplicate: null,
    failure: null
  };
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: "id", autoIncrement: true });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open the offline outbox"));
    });
    dbPromise.catch(() => {
      dbPromise = null;
    });
  }
  return dbPromise;
}

function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const transaction = db.transaction(STORE, mode);
        const request = run(transaction.objectStore(STORE));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error ?? new Error("Offline outbox write failed"));
      })
  );
}

const listeners = new Set<() => void>();
let cache: OutboxEntry[] = [];

function publish() {
  listeners.forEach((listener) => listener());
}

export function subscribeOutbox(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getOutboxSnapshot(): OutboxEntry[] {
  return cache;
}

const SERVER_SNAPSHOT: OutboxEntry[] = [];
export function getServerOutboxSnapshot(): OutboxEntry[] {
  return SERVER_SNAPSHOT;
}

export async function refreshOutbox(): Promise<OutboxEntry[]> {
  try {
    const rows = await tx<OutboxEntry[]>("readonly", (store) => store.getAll() as IDBRequest<OutboxEntry[]>);
    cache = rows.sort((a, b) => a.createdAt - b.createdAt);
  } catch {
    cache = [];
  }
  publish();
  return cache;
}

export async function queueOffline(entry: Omit<OutboxEntry, "id" | "createdAt" | "attempts" | "failure">): Promise<void> {
  await tx("readwrite", (store) =>
    store.add({ ...entry, media: (entry.media ?? []).map(withTranscribeFlags), createdAt: Date.now(), attempts: 0, failure: null } as OutboxEntry)
  );
  await refreshOutbox();
}

export async function discardOutboxEntry(id: number): Promise<void> {
  await tx("readwrite", (store) => store.delete(id));
  await refreshOutbox();
}

async function markFailure(entry: OutboxEntry, failure: string, extra: Partial<OutboxEntry> = {}): Promise<void> {
  await tx("readwrite", (store) => store.put({ ...entry, ...extra, attempts: entry.attempts + 1, failure }));
}

export async function resolvePossibleDuplicate(
  entry: OutboxEntry,
  choice: "same" | "different",
  candidateId?: string
): Promise<SyncResult> {
  const target = candidateId ?? entry.possibleDuplicate?.candidates?.[0]?.id;
  const next = choice === "same" && target ? samePersonEntry(entry, target) : differentPersonEntry(entry);
  await persistProgress(next);
  await refreshOutbox();
  return syncOutbox();
}

async function persistProgress(entry: OutboxEntry): Promise<void> {
  await tx("readwrite", (store) => store.put(entry));
}

function pendingFileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

function isTransient(error: unknown): boolean {
  if (error instanceof ApiError) return error.status >= 500 || error.status === 408 || error.status === 429;
  return true;
}

export function uploadFailureIsOffline(error: unknown): boolean {
  return isTransient(error) && !(error instanceof ApiError);
}

export type SyncResult = { synced: number; failed: number; remaining: number; stoppedOffline: boolean };

let syncing: Promise<SyncResult> | null = null;

export function syncOutbox(): Promise<SyncResult> {
  if (syncing) return syncing;
  syncing = runSync().finally(() => {
    syncing = null;
  });
  return syncing;
}

async function runSync(): Promise<SyncResult> {
  const entries = await refreshOutbox();
  let synced = 0;
  let failed = 0;
  let stoppedOffline = false;

  for (const entry of entries) {
    if (entry.failure) continue;
    const progress: OutboxEntry = { ...entry };
    try {
      if (progress.mediaOnly) {
        progress.created = true;
      }
      if (!progress.created) {
        try {
          const saved = await apiFetch<{ id: string; steps?: Array<{ id: string }> }>(progress.endpoint, {
            method: progress.method,
            body: progress.body
          });
          const createdId = typeof saved?.id === "string" && saved.id ? saved.id : null;
          if (!createdId) {
            const files = pendingFileCount(progress);
            await markFailure(
              progress,
              `Couldn’t confirm this save. Kept on this device${files ? ` with ${countNoun(files, "file")}` : ""} — check the record arrived before discarding.`
            );
            failed += 1;
            continue;
          }
          progress.created = true;
          progress.createdId = createdId;
          progress.createdStepIds = (saved?.steps ?? []).map((step) => step.id);
        } catch (error) {
          if (error instanceof ApiError && error.status === 409) {
            const files = pendingFileCount(progress);
            const hold = holdFromPayload(error.payload);
            if (hold) {
              const possibleDuplicate = {
                message: hold.message,
                candidates: hold.candidates.slice(0, 5).map((candidate) => ({
                  id: candidate.artisan.id,
                  name: candidate.artisan.name,
                  place: candidate.artisan.place ?? null,
                  craft: candidate.artisan.craft ?? null
                }))
              };
              await markFailure(progress, possibleDuplicateSentence({ possibleDuplicate }) ?? hold.message, { possibleDuplicate });
              failed += 1;
              continue;
            }
            await markFailure(
              progress,
              `Possible duplicate. ${error.message} Kept on this device${files ? ` with ${countNoun(files, "file")}` : ""}.`
            );
            failed += 1;
            continue;
          }
          throw error;
        }
        await persistProgress(progress);
      }

      const mediaFailed: Array<{ name: string }> = [];
      const uploaded = new Set(progress.uploadedBatches ?? []);
      if (progress.createdId || progress.mediaOnly) {
        for (const [index, batch] of progress.media.entries()) {
          if (uploaded.has(index) || !batch.files.length) continue;
          const linkedRecordId = progress.mediaOnly
            ? (batch.linkedRecordId ?? null)
            : batch.stepIndex === undefined
              ? progress.createdId
              : (progress.createdStepIds?.[batch.stepIndex] ?? null);
          if (!linkedRecordId && !progress.mediaOnly) {
            mediaFailed.push(...batch.files.map((file) => ({ name: file.name })));
            continue;
          }
          const result = await uploadMediaBatch({
            files: batch.files,
            linkedRecordType: batch.linkedRecordType,
            linkedRecordId,
            caption: batch.caption,
            location: batch.location,
            recordedAt: batch.recordedAt ?? undefined,
            recordedTimezone: batch.recordedTimezone ?? undefined,
            extraMetadata: batch.extraMetadata,
            alwaysTranscribe: batch.alwaysTranscribe ?? batch.transcribeAudio === true,
            transcribe: transcribeFlagsFor(batch, batch.files)
          });
          mediaFailed.push(...result.failed);
          uploaded.add(index);
          progress.uploadedBatches = Array.from(uploaded);
          await persistProgress(progress);
        }
      }
      if (mediaFailed.length) {
        await markFailure(
          progress,
          `Saved, but ${countNoun(mediaFailed.length, "file")} didn’t upload: ${mediaFailed.map((f) => f.name).join(", ")}. Re-attach them.`
        );
        failed += 1;
        await refreshOutbox();
        continue;
      }

      await discardOutboxEntry(progress.id!);
      synced += 1;
    } catch (error) {
      if (isTransient(error)) {
        stoppedOffline = true;
        break;
      }
      await markFailure(progress, error instanceof Error ? error.message : "This entry couldn’t be saved.");
      failed += 1;
    }
  }

  const remaining = (await refreshOutbox()).length;
  return { synced, failed, remaining, stoppedOffline };
}

export async function queueMediaOnly({ label, media }: { label: string; media: OutboxMediaBatch[] }): Promise<void> {
  await queueOffline({
    label,
    endpoint: "",
    method: "POST",
    body: "",
    media: media.filter((batch) => batch.files.length),
    mediaOnly: true,
    created: true,
    createdId: null
  });
}

export type SaveOutcome<T> = { queued: true } | { queued: false; saved: T };

export async function saveOrQueue<T extends { id: string }>({
  label,
  endpoint,
  method,
  body,
  media
}: {
  label: string;
  endpoint: string;
  method: "POST" | "PATCH";
  body: unknown;
  media?: OutboxMediaBatch[];
}): Promise<SaveOutcome<T>> {
  const payload = JSON.stringify(bodyWithClientKey(endpoint, method, body));
  const queue = async () => {
    await queueOffline({ label, endpoint, method, body: payload, media: (media ?? []).filter((batch) => batch.files.length) });
    return { queued: true } as const;
  };

  if (typeof navigator !== "undefined" && navigator.onLine === false) return queue();

  try {
    return { queued: false, saved: await apiFetch<T>(endpoint, { method, body: payload }) };
  } catch (error) {
    if (isTransient(error) && !(error instanceof ApiError)) return queue();
    throw error;
  }
}
