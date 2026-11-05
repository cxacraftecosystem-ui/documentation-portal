import { expectedBytes, INCLUDE_KEYS, includeKeyOf, type IncludeKey, type ManifestFile } from "@/lib/dataExplorer/contract";

export type TypeTally = { files: number; bytes: number; unknown: number };

export type ManifestSummary = {
  files: number;
  bytes: number;
  unknown: number;
  byType: Record<IncludeKey, TypeTally>;
};

export function emptySummary(): ManifestSummary {
  const byType = {} as Record<IncludeKey, TypeTally>;
  for (const key of INCLUDE_KEYS) byType[key] = { files: 0, bytes: 0, unknown: 0 };
  return { files: 0, bytes: 0, unknown: 0, byType };
}

export function addToSummary(summary: ManifestSummary, file: ManifestFile): void {
  const key = includeKeyOf(file);
  const size = expectedBytes(file);
  const tally = summary.byType[key];
  summary.files += 1;
  tally.files += 1;
  if (size === null) {
    summary.unknown += 1;
    tally.unknown += 1;
  } else {
    summary.bytes += size;
    tally.bytes += size;
  }
}

export function summarise(files: ManifestFile[]): ManifestSummary {
  const summary = emptySummary();
  for (const file of files) addToSummary(summary, file);
  return summary;
}

export function selectedSummary(summary: ManifestSummary, include: readonly IncludeKey[]): ManifestSummary {
  const out = emptySummary();
  for (const key of include) {
    const tally = summary.byType[key];
    out.byType[key] = { ...tally };
    out.files += tally.files;
    out.bytes += tally.bytes;
    out.unknown += tally.unknown;
  }
  return out;
}

export function filterFiles(files: ManifestFile[], include: readonly IncludeKey[]): ManifestFile[] {
  const wanted = new Set(include);
  return files.filter((file) => wanted.has(includeKeyOf(file)));
}

export function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "0 B";
  if (value < 1024) return `${value} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let size = value / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size >= 100 ? Math.round(size) : Number(size.toPrecision(3))} ${units[unit]}`;
}

export function bytesOf(done: number, total: number): string {
  const whole = formatBytes(total);
  const part = formatBytes(done);
  const unit = whole.split(" ")[1];
  const [number, partUnit] = part.split(" ");
  return partUnit === unit ? `${number} of ${whole}` : `${part} of ${whole}`;
}

export function countLabel(count: number, one: string, many: string): string {
  return `${count.toLocaleString("en-IN")} ${count === 1 ? one : many}`;
}

export function summaryLine(summary: Pick<ManifestSummary, "files" | "bytes" | "unknown">): string {
  const files = countLabel(summary.files, "file", "files");
  if (summary.files === 0) return files;
  if (summary.unknown === summary.files) return files;
  const size = formatBytes(summary.bytes);
  return summary.unknown ? `${files} · ${size}+` : `${files} · ${size}`;
}

export function typeHint(tally: TypeTally): string {
  if (tally.files === 0) return "None";
  return summaryLine(tally);
}

export type BrowserKind = "chromium" | "firefox" | "safari" | "ios" | "other";

export function browserKind(userAgent: string): BrowserKind {
  if (/iPhone|iPad|iPod/i.test(userAgent)) return "ios";
  if (/Firefox\//i.test(userAgent)) return "firefox";
  if (/Edg\/|Chrome\/|Chromium\//i.test(userAgent)) return "chromium";
  if (/Safari\//i.test(userAgent)) return "safari";
  return "other";
}

const GIB = 1024 * 1024 * 1024;
const MIB = 1024 * 1024;

export const MEMORY_ZIP_LIMIT: Record<BrowserKind, number> = {
  chromium: 2 * GIB,
  firefox: 2 * GIB,
  safari: 1 * GIB,
  ios: 512 * MIB,
  other: 1 * GIB
};

export type SaveCapabilities = { saveFile: boolean; saveFolder: boolean; browser: BrowserKind };

export function zipLimit(capabilities: SaveCapabilities): number | null {
  return capabilities.saveFile ? null : MEMORY_ZIP_LIMIT[capabilities.browser];
}

export function overLimit(bytes: number, capabilities: SaveCapabilities): boolean {
  const limit = zipLimit(capabilities);
  return limit !== null && bytes > limit;
}

export function limitSentence(capabilities: SaveCapabilities): string | null {
  const limit = zipLimit(capabilities);
  if (limit === null) return null;
  return `This browser saves zips up to ${formatBytes(limit)}.`;
}

export const SMALL_FILE_BYTES = 16 * MIB;
export const FETCH_CONCURRENCY = 4;
export const CONVERSION_CONCURRENCY = 1;
export const FILE_ATTEMPTS = 3;

export function retryDelay(attempt: number): number {
  return Math.min(8000, 1000 * 2 ** Math.max(0, attempt - 1));
}

export type RateSample = { at: number; bytes: number };

export function rateOf(samples: RateSample[]): number | null {
  if (samples.length < 2) return null;
  const first = samples[0];
  const last = samples[samples.length - 1];
  const seconds = (last.at - first.at) / 1000;
  if (seconds <= 0.5) return null;
  return Math.max(0, (last.bytes - first.bytes) / seconds);
}

export function pushSample(samples: RateSample[], sample: RateSample, windowMs = 8000): RateSample[] {
  const next = [...samples, sample];
  while (next.length > 2 && sample.at - next[0].at > windowMs) next.shift();
  return next;
}

export function secondsLeft(done: number, total: number, rate: number | null): number | null {
  if (!rate || rate <= 0 || total <= 0 || done >= total) return null;
  return Math.ceil((total - done) / rate);
}

export function timeLeftLabel(seconds: number | null): string | null {
  if (seconds === null) return null;
  const whole = Math.max(1, Math.round(seconds));
  if (whole < 60) return `${whole} s left`;
  if (whole < 600) {
    const minutes = Math.floor(whole / 60);
    const rest = whole % 60;
    return rest ? `${minutes} min ${rest} s left` : `${minutes} min left`;
  }
  const minutes = Math.round(whole / 60);
  if (minutes < 60) return `${minutes} min left`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} h ${rest} min left` : `${hours} h left`;
}

export function percentOf(done: number, total: number): number {
  if (total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.floor((done / total) * 100)));
}

export function announceStep(percent: number, previous: number | null): boolean {
  if (previous === null) return true;
  return Math.floor(percent / 10) > Math.floor(previous / 10);
}

export type FailedFile = { path: string; reason: string };

export const FAILED_LIST_NAME = "_failed-downloads.txt";

export function failedListText(failed: FailedFile[]): string {
  return `These files could not be downloaded and are not in the archive:\n\n${failed.map((f) => `${f.path} — ${f.reason}`).join("\n")}\n`;
}

export function mp4Path(path: string): string {
  return `${path.replace(/\.[^./\\]+$/, "")}.mp4`;
}

export function uniquePaths(files: ManifestFile[]): ManifestFile[] {
  const used = new Set<string>();
  return files.map((file) => {
    let path = file.path.replace(/\\/g, "/").replace(/^\/+/, "");
    if (!used.has(path.toLowerCase())) {
      used.add(path.toLowerCase());
      return path === file.path ? file : { ...file, path };
    }
    const dot = path.lastIndexOf(".");
    const slash = path.lastIndexOf("/");
    const stem = dot > slash ? path.slice(0, dot) : path;
    const ext = dot > slash ? path.slice(dot) : "";
    let n = 2;
    while (used.has(`${stem}_${n}${ext}`.toLowerCase())) n += 1;
    path = `${stem}_${n}${ext}`;
    used.add(path.toLowerCase());
    return { ...file, path };
  });
}

export function prefixed(files: ManifestFile[], folder: string): ManifestFile[] {
  const base = folder.replace(/[\\/]+/g, "-").trim();
  if (!base) return files;
  return files.map((file) => ({
    ...file,
    path: `${base}/${file.path}`,
    originalPath: file.originalPath ? `${base}/${file.originalPath}` : file.originalPath
  }));
}
