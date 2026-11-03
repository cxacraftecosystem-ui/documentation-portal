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
