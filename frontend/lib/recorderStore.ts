/**
 * Crash-safe recording on the web (DESIGN §6.2): MediaRecorder runs with a 10-second timeslice and
 * every chunk is written to IndexedDB as it arrives. The FIRST chunk carries the container header,
 * so a take is reassembled as `[header, ...rest]` in index order and stays playable however it was
 * cut short. A take whose draft is still open when the page loads is offered back through P13.
 *
 * The draft (`TakeDraft`) is written BEFORE the recorder starts, with the clip key, the device time
 * and what is being recorded, so a recovered take can be filed exactly as it would have been.
 */

import { openQuestionnaireDb } from "@/lib/questionnaireQueue";

export const TIMESLICE_MS = 10_000;

export type TakeDraft = {
  takeKey: string;
  sessionKey: string;
  clientClipKey: string;
  deviceCapturedAt: string;
  mimeType: string;
  /** Human label of what was being recorded ("Section D"), for P13's sentence. */
  targetLabel: string;
  targets: ({ sectionId: string } | { questionLineageId: string })[];
  roster: string[];
  startedAt: number;
  /** Set when the take was stopped normally; a draft without it after a reload is a recovery. */
  finishedAt?: number | null;
};

export type TakeChunk = { takeKey: string; index: number; blob: Blob; at: number };

/** Reassembles a take: chunks in index order, the header chunk first, duplicates dropped. */
export function assembleTake(chunks: TakeChunk[], mimeType: string): Blob {
  const byIndex = new Map<number, TakeChunk>();
  for (const chunk of chunks) if (!byIndex.has(chunk.index)) byIndex.set(chunk.index, chunk);
  const ordered = [...byIndex.values()].sort((a, b) => a.index - b.index);
  return new Blob(
    ordered.map((c) => c.blob),
    { type: mimeType }
  );
}

/** Approximate length of a recovered take from its chunk times, in whole minutes (at least 1). */
export function recoveredMinutes(draft: TakeDraft, chunks: TakeChunk[]): number {
  const last = chunks.reduce((max, c) => Math.max(max, c.at), draft.startedAt);
  return Math.max(1, Math.round((last - draft.startedAt) / 60_000));
}

/** Drafts with chunks and no `finishedAt`: what P13 offers on load. */
export function recoverable(drafts: TakeDraft[], chunks: TakeChunk[]): { draft: TakeDraft; chunks: TakeChunk[] }[] {
  return drafts
    .filter((d) => !d.finishedAt)
    .map((draft) => ({ draft, chunks: chunks.filter((c) => c.takeKey === draft.takeKey) }))
    .filter((r) => r.chunks.length > 0);
}

/** A whole-chunk-per-blob store over the questionnaire IndexedDB. */
export const recorderStore = {
  async saveDraft(draft: TakeDraft) {
    const db = await openQuestionnaireDb();
    await put(db, "takes", draft);
  },
  async addChunk(chunk: TakeChunk) {
    const db = await openQuestionnaireDb();
    await put(db, "chunks", chunk);
  },
  async finish(takeKey: string) {
    const db = await openQuestionnaireDb();
    const draft = await get<TakeDraft>(db, "takes", takeKey);
    if (draft) await put(db, "takes", { ...draft, finishedAt: Date.now() });
  },
  async drafts(): Promise<TakeDraft[]> {
    const db = await openQuestionnaireDb();
    return all<TakeDraft>(db, "takes");
  },
  async chunks(takeKey?: string): Promise<TakeChunk[]> {
    const db = await openQuestionnaireDb();
    const every = await all<TakeChunk>(db, "chunks");
    return takeKey ? every.filter((c) => c.takeKey === takeKey) : every;
  },
  async discard(takeKey: string) {
    const db = await openQuestionnaireDb();
    const chunks = await this.chunks(takeKey);
    await Promise.all(chunks.map((c) => del(db, "chunks", [c.takeKey, c.index])));
    await del(db, "takes", takeKey);
  }
};

function request<T>(run: () => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const r = run();
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
const put = (db: IDBDatabase, store: string, value: unknown) =>
  request(() => db.transaction(store, "readwrite").objectStore(store).put(value));
const get = <T>(db: IDBDatabase, store: string, key: IDBValidKey) =>
  request(() => db.transaction(store, "readonly").objectStore(store).get(key)) as Promise<T | undefined>;
const all = <T>(db: IDBDatabase, store: string) =>
  request(() => db.transaction(store, "readonly").objectStore(store).getAll()) as Promise<T[]>;
const del = (db: IDBDatabase, store: string, key: IDBValidKey) =>
  request(() => db.transaction(store, "readwrite").objectStore(store).delete(key));

/** Safari records fragmented MP4; everything else WebM/Opus. Never hard-code one. */
export function pickRecorderMime(isSupported: (type: string) => boolean): { mimeType: string; extension: string } {
  const candidates: [string, string][] = [
    ["audio/webm;codecs=opus", "webm"],
    ["audio/webm", "webm"],
    ["audio/mp4", "m4a"],
    ["audio/ogg;codecs=opus", "ogg"]
  ];
  for (const [mimeType, extension] of candidates) if (isSupported(mimeType)) return { mimeType, extension };
  return { mimeType: "", extension: "webm" };
}
