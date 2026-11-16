import { shortDate } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";

export type BatchOutcome<T> = {
  uploaded: T[];
  left: File[];
  errors: string[];
};

export function filesStillToSend(files: File[], failed: Array<{ name: string }>): File[] {
  const owed = new Map<string, number>();
  for (const item of failed) owed.set(item.name, (owed.get(item.name) ?? 0) + 1);
  const left: File[] = [];
  for (const file of files) {
    const count = owed.get(file.name) ?? 0;
    if (count > 0) {
      left.push(file);
      owed.set(file.name, count - 1);
    }
  }
  return left;
}

export async function settleBatch<T>(
  files: File[],
  run: () => Promise<{ uploaded: T[]; failed: Array<{ name: string; error: string }> }>
): Promise<BatchOutcome<T>> {
  if (!files.length) return { uploaded: [], left: [], errors: [] };
  try {
    const { uploaded, failed } = await run();
    return {
      uploaded,
      left: filesStillToSend(files, failed),
      errors: failed.map((item) => `${item.name}: ${item.error}`)
    };
  } catch (error) {
    return {
      uploaded: [],
      left: [...files],
      errors: [error instanceof Error ? error.message : "Upload failed"]
    };
  }
}

export function unsentMessage(names: string[]): string {
  const count = names.length;
  const shown = names.slice(0, 3).join(", ");
  const more = count > 3 ? ` and ${count - 3} more` : "";
  return `Saved, but ${count} ${count === 1 ? "recording" : "recordings"} didn't upload (${shown}${more}). Save again to send ${count === 1 ? "it" : "them"}.`;
}

export type UploadRow = {
  key: string;
  file: File;
  durationMs: number | null;
  recordedAt: string;
  source: "FILE_METADATA" | "SESSION_DATE" | "UPLOAD_TIME";
  sha: string | null;
  duplicateOn: string | null;
};

export function recordedAtOf(file: { lastModified?: number }, sessionDate: string | null, now: Date = new Date()): { at: string; source: UploadRow["source"] } {
  const stamp = typeof file.lastModified === "number" && file.lastModified > 0 && file.lastModified <= now.getTime() + 60_000 ? new Date(file.lastModified) : null;
  if (stamp && !Number.isNaN(stamp.getTime())) return { at: stamp.toISOString(), source: "FILE_METADATA" };
  if (sessionDate && /^\d{4}-\d{2}-\d{2}$/.test(sessionDate.slice(0, 10))) return { at: `${sessionDate.slice(0, 10)}T06:30:00.000Z`, source: "SESSION_DATE" };
  return { at: now.toISOString(), source: "UPLOAD_TIME" };
}

export type ServerCopy = {
  sha: string;
  uploadedAt?: string | null;
  sameSession?: boolean;
  sessionId?: string | null;
  sessionTitle?: string | null;
  recordingId?: string | null;
};

export type QueuedCopy = { at: string; uploaded: boolean; sessionId: string | null; sameSession: boolean };

export type Duplicate =
  | { kind: "LIST"; at: string }
  | { kind: "QUEUED"; at: string }
  | { kind: "SERVER"; at: string; sameSession: boolean; sessionId: string | null; sessionTitle: string | null; recordingId: string | null };

export function duplicateStates<T extends { key: string; sha: string | null }>(
  rows: T[],
  found: ServerCopy[],
  queued: Record<string, QueuedCopy>,
  fallbackDate: string | null,
  now: Date = new Date()
): Record<string, Duplicate> {
  const byServer = new Map(found.map((f) => [f.sha, f] as const));
  const out: Record<string, Duplicate> = {};
  const seen = new Set<string>();
  for (const row of rows) {
    if (!row.sha) continue;
    const server = byServer.get(row.sha);
    const local = queued[row.sha];
    if (seen.has(row.sha)) out[row.key] = { kind: "LIST", at: now.toISOString() };
    else if (server)
      out[row.key] = {
        kind: "SERVER",
        at: server.uploadedAt ?? fallbackDate ?? "",
        sameSession: Boolean(server.sameSession),
        sessionId: server.sessionId ?? null,
        sessionTitle: server.sessionTitle?.trim() || null,
        recordingId: server.recordingId ?? null
      };
    else if (local && !local.uploaded) out[row.key] = { kind: "QUEUED", at: local.at };
    else if (local) out[row.key] = { kind: "SERVER", at: local.at, sameSession: local.sameSession, sessionId: local.sessionId, sessionTitle: null, recordingId: null };
    seen.add(row.sha);
  }
  return out;
}

export function flagDuplicates<T extends { key: string; sha: string | null }>(
  rows: T[],
  found: ServerCopy[],
  queued: Record<string, string>,
  fallbackDate: string | null
): Record<string, string> {
  const asQueued = Object.fromEntries(Object.entries(queued).map(([sha, at]) => [sha, { at, uploaded: false, sessionId: null, sameSession: false }] as const));
  return Object.fromEntries(Object.entries(duplicateStates(rows, found, asQueued, fallbackDate)).map(([key, d]) => [key, d.at] as const));
}

export type DuplicateOpen = { kind: "TAKE"; recordingId: string } | { kind: "SESSION"; sessionId: string; recordingId: string | null };

export type DuplicateView = { chip: string; where: string | null; open: DuplicateOpen | null };

export function duplicateView(d: Duplicate): DuplicateView {
  if (d.kind === "LIST") return { chip: qs("duplicateInList"), where: null, open: null };
  if (d.kind === "QUEUED") return { chip: qs("duplicateQueued"), where: null, open: null };
  const chip = qs("alreadyUploadedOn", { date: shortDate(d.at) });
  if (d.sameSession) return { chip, where: qs("duplicateHere"), open: d.recordingId ? { kind: "TAKE", recordingId: d.recordingId } : null };
  return {
    chip,
    where: d.sessionTitle ? qs("duplicateIn", { title: d.sessionTitle }) : qs("duplicateElsewhere"),
    open: d.sessionId ? { kind: "SESSION", sessionId: d.sessionId, recordingId: d.recordingId } : null
  };
}

export function uploadedLine(count: number, target: string): string {
  return count === 1 ? qs("uploadedOne", { target }) : qs("uploadedMany", { count, target });
}

export function batchOutcome(keys: string[], landed: ReadonlySet<string>, failed: ReadonlySet<string>): { settled: boolean; landed: number; failed: number } {
  const done = keys.filter((k) => landed.has(k)).length;
  const lost = keys.filter((k) => !landed.has(k) && failed.has(k)).length;
  return { settled: done + lost === keys.length, landed: done, failed: lost };
}

export function toUpload<T extends { duplicateOn: string | null }>(rows: T[]): T[] {
  return rows.filter((r) => r.duplicateOn === null);
}
