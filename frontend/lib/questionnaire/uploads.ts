/**
 * The legacy questionnaire form's upload bookkeeping (audit H21, 21 Oct 2026).
 *
 * `uploadMediaBatch` resolves with `failed[]` when only some files fail and throws only when every
 * file fails. The form ignored `failed`, reset itself and lost those recordings; and when a batch
 * threw after the interview was created, the form stayed in create mode, so "Save" again made a
 * second interview and uploaded every clip a second time. These helpers keep exactly the files that
 * did not land, so the form can switch to editing the saved interview and resend only those.
 */

export type BatchOutcome<T> = {
  uploaded: T[];
  /** The files that did not land, in their original order. */
  left: File[];
  /** One line per failure, for the message. */
  errors: string[];
};

/** The files named in `failed`, matched by name as a multiset (two "clip.webm" can fail once). */
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

/** Run one batch and report what is left, never throwing: a batch that failed whole leaves every file. */
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

/** The one line shown when some recordings did not upload. */
export function unsentMessage(names: string[]): string {
  const count = names.length;
  const shown = names.slice(0, 3).join(", ");
  const more = count > 3 ? ` and ${count - 3} more` : "";
  return `Saved, but ${count} ${count === 1 ? "recording" : "recordings"} didn't upload (${shown}${more}). Save again to send ${count === 1 ? "it" : "them"}.`;
}
