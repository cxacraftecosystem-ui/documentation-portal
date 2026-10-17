/** B7 — which picked files a batch reported failed, pure so it is tested without a browser. */

export type FailedUpload = { name: string; error?: string };

/** The picked files a batch reported as failed, matched by name with multiplicity, in pick order. */
export function failedFilesOf(files: readonly File[], failed: readonly FailedUpload[]): File[] {
  const remaining = new Map<string, number>();
  for (const entry of failed) remaining.set(entry.name, (remaining.get(entry.name) ?? 0) + 1);
  const out: File[] = [];
  for (const file of files) {
    const left = remaining.get(file.name) ?? 0;
    if (left > 0) {
      out.push(file);
      remaining.set(file.name, left - 1);
    }
  }
  return out;
}
