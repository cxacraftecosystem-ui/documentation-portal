import { openQuestionnaireDb } from "@/lib/questionnaire/queue";

export const TIMESLICE_MS = 10_000;

export type TakeDraft = {
  takeKey: string;
  sessionKey: string;
  clientClipKey: string;
  deviceCapturedAt: string;
  mimeType: string;
  targetLabel: string;
  targets: ({ sectionId: string } | { questionLineageId: string })[];
  roster: string[];
  startedAt: number;
  finishedAt?: number | null;
};

export type TakeChunk = { takeKey: string; index: number; blob: Blob; at: number };

export function assembleTake(chunks: TakeChunk[], mimeType: string): Blob {
  const byIndex = new Map<number, TakeChunk>();
  for (const chunk of chunks) if (!byIndex.has(chunk.index)) byIndex.set(chunk.index, chunk);
  const ordered = [...byIndex.values()].sort((a, b) => a.index - b.index);
  return new Blob(
    ordered.map((c) => c.blob),
    { type: mimeType }
  );
}

export function recoveredMinutes(draft: TakeDraft, chunks: TakeChunk[]): number {
  const last = chunks.reduce((max, c) => Math.max(max, c.at), draft.startedAt);
  return Math.max(1, Math.round((last - draft.startedAt) / 60_000));
}

export function recoverable(drafts: TakeDraft[], chunks: TakeChunk[]): { draft: TakeDraft; chunks: TakeChunk[] }[] {
  return drafts
    .filter((d) => !d.finishedAt)
    .map((draft) => ({ draft, chunks: chunks.filter((c) => c.takeKey === draft.takeKey) }))
    .filter((r) => r.chunks.length > 0);
}

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
