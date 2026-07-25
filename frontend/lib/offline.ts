"use client";

/**
 * The offline outbox — the web's port of the Android app's `OfflineOutbox`.
 *
 * A researcher in a village with no signal must be able to finish the interview they are in the
 * middle of. On Android they always could: a save with no connection goes into a local outbox and
 * drains when the network returns. On the web it used to fail at the Save button, and the only
 * honest thing the UI could do was warn them (see `components/dialogs/OfflineDialog`). This is the
 * outbox that warning was apologising for.
 *
 * WHAT IS STORED. One entry per attempted save: the record request (endpoint, method, JSON body)
 * and the files that were attached to it. Files go in as `File` objects — IndexedDB stores them by
 * structured clone, so the bytes, the name and the MIME type all survive a browser restart, which a
 * blob: URL or an in-memory array would not. This matters: the attachments are usually the part
 * that cannot be recreated, because the artisan has gone home.
 *
 * HOW IT DRAINS, AND HOW IT DIFFERS FROM ANDROID. `syncOutbox` replays entries oldest first: create
 * the record, then upload its media against the new id, then delete the entry. The Android outbox
 * stops at the first failure, which is right for a connection that dropped again — but wrong for a
 * request the server will never accept: one 422 at the head of the queue blocks every entry behind
 * it forever, and nothing tells the user why. So the failure is triaged here:
 *
 *   - no connection / 5xx / timeout  → transient. Stop the pass, keep everything queued, try again
 *                                      on the next `online` event. Nothing is lost.
 *   - 4xx (validation, permission)   → permanent. Mark THAT entry with the server's reason, leave
 *                                      it in the outbox for the user to see and discard, and carry
 *                                      on to the next one. One bad record cannot strand the others.
 *
 * A 409 is treated as already-saved rather than as an error: replaying a create that the server
 * accepted before the response was lost must not leave a duplicate in the outbox for ever.
 */

import { ApiError, apiFetch } from "@/lib/api";
import { uploadMediaBatch } from "@/lib/media";

const DB_NAME = "field-repo-outbox";
const DB_VERSION = 1;
const STORE = "entries";

/**
 * One media batch of a queued save: everything `uploadMediaBatch` will need on replay.
 *
 * A LIST of these, not one, because the forms do not attach media in a single lump: a product
 * queues its two measurement-grid photos (each with its own caption naming the dimension) beside
 * the general field media, and a tool adds its numbered process-stage captures on top. Flattening
 * them into one batch would put every file under one caption, and the caption is the only thing
 * that says which photo is the height grid.
 */
export type OutboxMediaBatch = {
  files: File[];
  linkedRecordType: string;
  caption?: string;
  location?: unknown;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: Record<string, unknown>;
  transcribeAudio?: boolean;
  /**
   * Link this batch to the Nth CHILD of the created record instead of the record itself — the
   * process form's per-step media, whose `processstep` ids do not exist until the server has made
   * them. Resolved on replay from the create response's `steps[]`, in the order they were sent.
   */
  stepIndex?: number;
};

export type OutboxEntry = {
  id?: number;
  /** Human label for the banner — "Artisan · Giriraj Prasad", not an endpoint. */
  label: string;
  createdAt: number;
  endpoint: string;
  method: "POST" | "PATCH";
  /** Serialised at queue time so a later schema change cannot alter what the user actually saved. */
  body: string;
  media: OutboxMediaBatch[];
  attempts: number;
  /** Set when the server rejected this permanently; the entry stays for the user to read and discard. */
  failure: string | null;
};

// ---------------------------------------------------------------------------
// IndexedDB plumbing
// ---------------------------------------------------------------------------

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
    // A failed open must not be cached forever — a private-mode tab that later allows storage
    // should get a working outbox rather than the first rejection for the rest of the session.
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

// ---------------------------------------------------------------------------
// Subscription — one source of truth for every banner on the page
// ---------------------------------------------------------------------------

const listeners = new Set<() => void>();
let cache: OutboxEntry[] = [];

function publish() {
  listeners.forEach((listener) => listener());
}

export function subscribeOutbox(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Synchronous snapshot for `useSyncExternalStore`; refreshed by {@link refreshOutbox}. */
export function getOutboxSnapshot(): OutboxEntry[] {
  return cache;
}

/** Server render has no IndexedDB — a stable empty array keeps the store from looping. */
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

// ---------------------------------------------------------------------------
// Queue / discard
// ---------------------------------------------------------------------------

export async function queueOffline(entry: Omit<OutboxEntry, "id" | "createdAt" | "attempts" | "failure">): Promise<void> {
  await tx("readwrite", (store) =>
    store.add({ ...entry, createdAt: Date.now(), attempts: 0, failure: null } as OutboxEntry)
  );
  await refreshOutbox();
}

export async function discardOutboxEntry(id: number): Promise<void> {
  await tx("readwrite", (store) => store.delete(id));
  await refreshOutbox();
}

async function markFailure(entry: OutboxEntry, failure: string): Promise<void> {
  await tx("readwrite", (store) => store.put({ ...entry, attempts: entry.attempts + 1, failure }));
}

// ---------------------------------------------------------------------------
// Drain
// ---------------------------------------------------------------------------

/** A fetch that never reached a server: still offline, so stop the pass and keep everything. */
function isTransient(error: unknown): boolean {
  if (error instanceof ApiError) return error.status >= 500 || error.status === 408 || error.status === 429;
  return true; // TypeError from fetch, an abort, a DNS failure — all "try again later".
}

export type SyncResult = { synced: number; failed: number; remaining: number; stoppedOffline: boolean };

let syncing: Promise<SyncResult> | null = null;

/**
 * Replay the outbox oldest first. Concurrent calls (the `online` event and a "Sync now" click
 * landing together) share one pass — replaying an entry twice would create the record twice.
 */
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
    if (entry.failure) continue; // Already triaged as permanent; waiting on the user, not the network.
    try {
      let savedId: string | null = null;
      let savedSteps: Array<{ id: string }> = [];
      try {
        const saved = await apiFetch<{ id: string; steps?: Array<{ id: string }> }>(entry.endpoint, {
          method: entry.method,
          body: entry.body
        });
        savedId = saved?.id ?? null;
        savedSteps = saved?.steps ?? [];
      } catch (error) {
        // The create already landed on a previous pass whose response we never saw. Dropping the
        // entry is right; re-queueing it would duplicate the record on every future sync.
        if (error instanceof ApiError && error.status === 409) {
          await discardOutboxEntry(entry.id!);
          synced += 1;
          continue;
        }
        throw error;
      }

      const mediaFailed: Array<{ name: string }> = [];
      if (savedId) {
        for (const batch of entry.media) {
          if (!batch.files.length) continue;
          // A step batch has to wait for the server to mint the step; if the create came back with
          // fewer steps than were queued, say so rather than silently attaching to the wrong one.
          const linkedRecordId = batch.stepIndex === undefined ? savedId : (savedSteps[batch.stepIndex]?.id ?? null);
          if (!linkedRecordId) {
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
            transcribeAudio: batch.transcribeAudio ?? true
          });
          mediaFailed.push(...result.failed);
        }
      }
      // The record exists now, so the entry cannot simply be retried — re-running it would create a
      // second record just to have another go at a file. Report which files did not make it instead
      // of dropping them silently.
      if (mediaFailed.length) {
        await markFailure(
          entry,
          `Saved, but ${mediaFailed.length} file(s) did not upload: ${mediaFailed.map((f) => f.name).join(", ")}. ` +
            "Re-attach them on the record."
        );
        failed += 1;
        await refreshOutbox();
        continue;
      }

      await discardOutboxEntry(entry.id!);
      synced += 1;
    } catch (error) {
      if (isTransient(error)) {
        stoppedOffline = true;
        break; // Still offline (or the API is down) — everything behind this stays queued.
      }
      await markFailure(entry, error instanceof Error ? error.message : "The server rejected this entry.");
      failed += 1;
    }
  }

  const remaining = (await refreshOutbox()).length;
  return { synced, failed, remaining, stoppedOffline };
}

// ---------------------------------------------------------------------------
// The one call a form makes
// ---------------------------------------------------------------------------

export type SaveOutcome<T> = { queued: true } | { queued: false; saved: T };

/**
 * Save a record, or queue it if this device has no connection.
 *
 * Deliberately does NOT upload the media when online: the caller keeps its own `uploadMediaBatch`
 * call so its progress bar, per-file retry and eager-staging claim all behave exactly as before.
 * `media` is taken here only so that a QUEUED save can carry the files into IndexedDB with it.
 */
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
  /** Batches to persist WITH a queued save. Ignored when the save goes through online. */
  media?: OutboxMediaBatch[];
}): Promise<SaveOutcome<T>> {
  const payload = JSON.stringify(body);
  const queue = async () => {
    await queueOffline({ label, endpoint, method, body: payload, media: (media ?? []).filter((batch) => batch.files.length) });
    return { queued: true } as const;
  };

  // Known-offline: do not burn a request (and a 30s timeout) to learn what the browser already knows.
  if (typeof navigator !== "undefined" && navigator.onLine === false) return queue();

  try {
    return { queued: false, saved: await apiFetch<T>(endpoint, { method, body: payload }) };
  } catch (error) {
    // Only a request that never reached the server may be queued. A 4xx means the server saw it and
    // said no; queueing that would replay a rejection forever and hide the real problem from the user.
    if (isTransient(error) && !(error instanceof ApiError)) return queue();
    throw error;
  }
}
