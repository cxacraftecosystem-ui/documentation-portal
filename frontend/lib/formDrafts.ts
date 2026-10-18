"use client";

/**
 * B6 — THE WEB KEEPS UNFINISHED FORMS, by the same rules as the handset's `DraftStore`.
 *
 * The outbox protects a record from the moment it is saved; this protects it before that. A closed
 * tab, a crash or an expired session used to lose every field and every attached file. Now a record
 * form autosaves here — debounced, only while it has unsaved changes and only once any earlier draft
 * has been answered — and on the next open offers "Continue" or "Discard".
 *
 * THE RULES, ported from `android/.../data/DraftStore.kt` and `ui/FormDrafts.kt`:
 *
 *  - Keyed by (account, form, record): an edit by `rec-<id>`, a new record by `new-<parent>-<uuid>`
 *    minted when the form opens, where the parent is the record the form was opened from (`p_<id>`,
 *    or `none`) — so a draft for one artisan's product is never offered on another's form.
 *  - Per account and kept across sign-out, like the outbox.
 *  - A clean form writes nothing: merely opening a form never leaves a draft.
 *  - Saving or queueing the record deletes its draft; a form that stays open starts a fresh one.
 *  - "Unsaved draft for <parent> (“<label>”), last changed 17 Oct, 09:30." with Continue / Discard.
 *
 * WHERE: IndexedDB, not localStorage — attached `File`s are kept by structured clone, exactly as the
 * outbox keeps them. REGULATED IDENTITY NEVER ENTERS A DRAFT: `stripIdentity` drops every Aadhaar and
 * Pehchan key before a write, because field laptops are shared (the `sanitizeCarryContext` rule).
 */

const DB_NAME = "field-repo-drafts";
const DB_VERSION = 1;
const STORE = "drafts";

export type FormDraftRecord = {
  /** `${account}|${formKind}|${recordKey}` — the store's key. */
  key: string;
  account: string;
  formKind: string;
  recordKey: string;
  label: string;
  /** ISO-8601. */
  savedAt: string;
  fields: Record<string, unknown>;
  parentId: string | null;
  parentLabel: string | null;
};

/** The keys a draft must never carry, whatever a form hands it. */
const IDENTITY_KEY = /aadhaar|pehchan/i;

export function stripIdentity(fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(fields).filter(([key]) => !IDENTITY_KEY.test(key)));
}

/** `new-<parent>-` — the handset's `newDraftPrefix`, character for character. */
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

/** "Unsaved draft for Ramesh (“Dabu stole”), last changed 17 Oct, 09:30." — the handset's sentence. */
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

/** The newest NEW-record draft of a form for this parent, from a list — the handset's `latestNew`. */
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

// ---------------------------------------------------------------------------
// IndexedDB
// ---------------------------------------------------------------------------

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
