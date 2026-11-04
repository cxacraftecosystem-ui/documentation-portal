import type { ReportResponse } from "@/lib/dataExplorer/contract";

export const TABLE_MEMORY_ENTRIES = 16;
export const TABLE_MEMORY_BYTES = 24 * 1024 * 1024;
export const TABLE_MEMORY_TTL_MS = 10 * 60 * 1000;
const SESSION_PREFIX = "data-explorer-tables:";
const SESSION_ENTRY_LIMIT = 900 * 1024;

export type TableMemoryKey = { path: string; status?: string | null; viewer?: string | null };

type Remembered = { at: number; size: number; report: ReportResponse };

const memory = new Map<string, Remembered>();
let memoryBytes = 0;

export function tableMemoryKey(key: TableMemoryKey): string {
  return JSON.stringify([key.viewer ?? "", key.status ?? "", key.path]);
}

function sizeOf(report: ReportResponse): { size: number; text: string } {
  const text = JSON.stringify(report);
  return { size: text.length * 2, text };
}

function drop(key: string) {
  const entry = memory.get(key);
  if (!entry) return;
  memory.delete(key);
  memoryBytes -= entry.size;
}

function sessionGet(key: string): Remembered | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_PREFIX + key);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { at: number; report: ReportResponse };
    return { at: parsed.at, size: raw.length * 2, report: parsed.report };
  } catch {
    return null;
  }
}

function sessionSet(key: string, at: number, text: string) {
  try {
    if (text.length > SESSION_ENTRY_LIMIT) return;
    window.sessionStorage.setItem(SESSION_PREFIX + key, `{"at":${at},"report":${text}}`);
  } catch {
    return;
  }
}

function sessionDrop(key: string) {
  try {
    window.sessionStorage.removeItem(SESSION_PREFIX + key);
  } catch {
    return;
  }
}

export function recallTables(key: TableMemoryKey, now = Date.now()): ReportResponse | null {
  const id = tableMemoryKey(key);
  let entry = memory.get(id) ?? null;
  if (!entry && typeof window !== "undefined") {
    entry = sessionGet(id);
    if (entry) {
      memory.set(id, entry);
      memoryBytes += entry.size;
    }
  }
  if (!entry) return null;
  if (now - entry.at > TABLE_MEMORY_TTL_MS) {
    drop(id);
    if (typeof window !== "undefined") sessionDrop(id);
    return null;
  }
  memory.delete(id);
  memory.set(id, entry);
  return entry.report;
}

export function rememberTables(key: TableMemoryKey, report: ReportResponse, now = Date.now()): void {
  const id = tableMemoryKey(key);
  const { size, text } = sizeOf(report);
  drop(id);
  if (size > TABLE_MEMORY_BYTES) return;
  memory.set(id, { at: now, size, report });
  memoryBytes += size;
  while (memory.size > TABLE_MEMORY_ENTRIES || memoryBytes > TABLE_MEMORY_BYTES) {
    const oldest = memory.keys().next().value as string | undefined;
    if (oldest === undefined) break;
    drop(oldest);
  }
  if (typeof window !== "undefined") sessionSet(id, now, text);
}

export function forgetTables(match?: (key: TableMemoryKey) => boolean): void {
  for (const id of [...memory.keys()]) {
    const [viewer, status, path] = JSON.parse(id) as [string, string, string];
    if (!match || match({ viewer, status, path })) {
      drop(id);
      if (typeof window !== "undefined") sessionDrop(id);
    }
  }
}

export function rememberedCount(): number {
  return memory.size;
}
