/**
 * C22 — FASTER PROCESS STEPS (owner decision 2026-10-17), the pure half.
 *
 * Until now a step needed a name and a separate media pick each, about twenty to thirty taps for ten
 * steps. Three shortcuts:
 *
 *  - "Steps from photos": one step per picked file, in CAPTURE order (the photo's own EXIF time,
 *    else the file's modified time, else the order picked), each with its file already attached.
 *  - "Start from last process": the step names and kinds of this craft's most recent process, as a
 *    template — no media, no notes — marked until the researcher edits them.
 *  - A step may be saved WITHOUT a name and is flagged ("Unnamed step") until somebody names it.
 */

/** "2026:10:17 09:30:12" (EXIF, unrevived) or an ISO string → epoch ms; null when unreadable. */
export function exifTimeToMs(value: unknown): number | null {
  if (typeof value !== "string" || !value.trim()) return null;
  const exif = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(value.trim());
  if (exif) {
    const [, y, mo, d, h, mi, se] = exif.map(Number);
    const ms = new Date(y, mo - 1, d, h, mi, se).getTime();
    return Number.isFinite(ms) ? ms : null;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * The files in the order they were captured. `capturedAt` holds what the EXIF said (null when the
 * file carries none); a file with no capture time falls back to `lastModified`, and a tie keeps the
 * order the files were picked in.
 */
export function inCaptureOrder(files: readonly File[], capturedAt: ReadonlyArray<number | null>): File[] {
  return files
    .map((file, index) => ({ file, index, at: capturedAt[index] ?? (Number.isFinite(file.lastModified) ? file.lastModified : null) }))
    .sort((a, b) => {
      if (a.at !== null && b.at !== null && a.at !== b.at) return a.at - b.at;
      if (a.at === null && b.at !== null) return 1;
      if (a.at !== null && b.at === null) return -1;
      return a.index - b.index;
    })
    .map((entry) => entry.file);
}

/** The step names and kinds a template carries, in order. Blank names are dropped, never invented. */
export function templateStepsFrom(
  steps: ReadonlyArray<{ name?: string | null; stepType?: string | null; sortOrder?: number | null }> | null | undefined
): Array<{ name: string; stepType: string }> {
  return [...(steps ?? [])]
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    .map((step) => ({ name: (step.name ?? "").trim(), stepType: step.stepType === "GROUP" ? "GROUP" : "SEQUENTIAL" }))
    .filter((step) => step.name);
}

/** How many steps still need a name — the flag the form and the record view both show. */
export function unnamedStepCount(steps: ReadonlyArray<{ name: string }>): number {
  return steps.filter((step) => !step.name.trim()).length;
}
