"use client";

const DB_NAME = "field-repo-drafts";
const DB_VERSION = 1;
const STORE = "drafts";

export type FormDraftRecord = {
  key: string;
  account: string;
  formKind: string;
  recordKey: string;
  label: string;
  savedAt: string;
  fields: Record<string, unknown>;
  parentId: string | null;
  parentLabel: string | null;
};

const IDENTITY_KEY = /aadhaar|pehchan/i;

export function stripIdentity(fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(fields).filter(([key]) => !IDENTITY_KEY.test(key)));
}

export function newDraftPrefix(parentId: string | null | undefined): string {
  return `new-${parentId ? `p_${parentId.replace(/[^A-Za-z0-9_-]/g, "_")}` : "none"}-`;
}

export function newRecordKey(parentId: string | null | undefined): string {
  const c = typeof globalThis !== "undefined" ? (globalThis.crypto as Crypto | undefined) : undefined;
  const id = c && typeof c.randomUUID === "function" ? c.randomUUID() : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
  return newDraftPrefix(parentId) + id;
}

export function editRecordKey(recordId: string): string {
  return `rec-${recordId}`;
}

export function draftStoreKey(account: string, formKind: string, recordKey: string): string {
  return `${account}|${formKind}|${recordKey}`;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export function draftOfferSentence(draft: Pick<FormDraftRecord, "label" | "savedAt" | "parentLabel">): string {
  const at = new Date(draft.savedAt);
  const when = Number.isFinite(at.getTime())
    ? `${at.getDate()} ${MONTHS[at.getMonth()]}, ${String(at.getHours()).padStart(2, "0")}:${String(at.getMinutes()).padStart(2, "0")}`
    : "";
  return (
    "Unsaved draft" +
    (draft.parentLabel?.trim() ? ` for ${draft.parentLabel.trim()}` : "") +
    (draft.label.trim() ? ` (“${draft.label.trim()}”)` : "") +
    (when ? `, last changed ${when}.` : ".")
  );
}

export function latestNewDraft(
  drafts: readonly FormDraftRecord[],
  account: string,
  formKind: string,
  parentId: string | null | undefined
): FormDraftRecord | null {
  const prefix = newDraftPrefix(parentId);
  return (
    drafts
      .filter((draft) => draft.account === account && draft.formKind === formKind && draft.recordKey.startsWith(prefix))
      .sort((a, b) => b.savedAt.localeCompare(a.savedAt))[0] ?? null
  );
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "key" });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open drafts"));
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
        const request = run(db.transaction(STORE, mode).objectStore(STORE));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error ?? new Error("Draft write failed"));
      })
  );
}

export async function writeDraft(draft: Omit<FormDraftRecord, "key">): Promise<void> {
  const record: FormDraftRecord = {
    ...draft,
    fields: stripIdentity(draft.fields),
    key: draftStoreKey(draft.account, draft.formKind, draft.recordKey)
  };
  await tx("readwrite", (store) => store.put(record));
}

export async function readDraft(account: string, formKind: string, recordKey: string): Promise<FormDraftRecord | null> {
  const found = await tx<FormDraftRecord | undefined>("readonly", (store) => store.get(draftStoreKey(account, formKind, recordKey)) as IDBRequest<FormDraftRecord | undefined>);
  return found ?? null;
}

export async function readLatestNewDraft(account: string, formKind: string, parentId: string | null | undefined): Promise<FormDraftRecord | null> {
  const all = await tx<FormDraftRecord[]>("readonly", (store) => store.getAll() as IDBRequest<FormDraftRecord[]>);
  return latestNewDraft(all, account, formKind, parentId);
}

export async function deleteDraft(account: string, formKind: string, recordKey: string): Promise<void> {
  await tx("readwrite", (store) => store.delete(draftStoreKey(account, formKind, recordKey)));
}
