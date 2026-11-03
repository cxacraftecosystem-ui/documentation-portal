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

export function templateStepsFrom(
  steps: ReadonlyArray<{ name?: string | null; stepType?: string | null; sortOrder?: number | null }> | null | undefined
): Array<{ name: string; stepType: string }> {
  return [...(steps ?? [])]
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    .map((step) => ({ name: (step.name ?? "").trim(), stepType: step.stepType === "GROUP" ? "GROUP" : "SEQUENTIAL" }))
    .filter((step) => step.name);
}

export function unnamedStepCount(steps: ReadonlyArray<{ name: string }>): number {
  return steps.filter((step) => !step.name.trim()).length;
}
