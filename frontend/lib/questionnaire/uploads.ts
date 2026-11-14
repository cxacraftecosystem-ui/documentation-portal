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

export function flagDuplicates<T extends { key: string; sha: string | null }>(
  rows: T[],
  found: { sha: string; capturedAt?: string | null }[],
  queued: Record<string, string>,
  fallbackDate: string | null
): Record<string, string> {
  const byServer = new Map(found.map((f) => [f.sha, f.capturedAt ?? fallbackDate ?? ""] as const));
  const out: Record<string, string> = {};
  const seen = new Set<string>();
  for (const row of rows) {
    if (!row.sha) continue;
    const at = queued[row.sha] ?? byServer.get(row.sha) ?? (seen.has(row.sha) ? new Date().toISOString() : null);
    if (at !== null && at !== undefined) out[row.key] = at;
    seen.add(row.sha);
  }
  return out;
}

export function toUpload<T extends { duplicateOn: string | null }>(rows: T[]): T[] {
  return rows.filter((r) => r.duplicateOn === null);
}
