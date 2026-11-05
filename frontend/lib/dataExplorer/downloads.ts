export type DownloadKind = "zip" | "report" | "csv" | "file" | "transcript";

export type DownloadStatus = "waiting" | "running" | "saved" | "failed" | "cancelled";

export type DownloadRequest = {
  kind: Exclude<DownloadKind, "zip">;
  label: string;
  url: string;
  fileName?: string;
  mediaId?: string | null;
  bytes?: number | null;
  path?: string | null;
};

export type DownloadItem = {
  id: string;
  kind: DownloadKind;
  label: string;
  status: DownloadStatus;
  url: string | null;
  fileName: string | null;
  mediaId: string | null;
  path: string | null;
  bytesDone: number;
  bytesTotal: number | null;
  filesDone: number | null;
  filesTotal: number | null;
  rate: number | null;
  timeLeft: string | null;
  current: string | null;
  note: string | null;
  error: string | null;
  retryAt: number | null;
  finishedAt: number | null;
  failedFiles: { path: string; reason: string }[];
};

export type Tone = "saved" | "failed" | "neutral";

export function toneOf(status: DownloadStatus): Tone {
  if (status === "saved") return "saved";
  if (status === "failed") return "failed";
  return "neutral";
}

export const TONE_CLASS: Record<Tone, string> = {
  saved: "border-green-300 bg-success-100 text-green-900 dark:border-[#15803d] dark:bg-[#123b25] dark:text-green-100",
  failed: "border-red-300 bg-error-100 text-red-900 dark:border-[#b91c1c] dark:bg-[#4a1717] dark:text-red-100",
  neutral: "border-line-200 bg-card text-ink-900"
};

export const ROW_TONE_CLASS: Record<Tone, string> = {
  saved: "bg-success-100 text-green-900 dark:bg-[#123b25] dark:text-green-100",
  failed: "bg-error-100 text-red-900 dark:bg-[#4a1717] dark:text-red-100",
  neutral: ""
};

export function trayHeadline(items: readonly Pick<DownloadItem, "status">[]): string {
  const count = (status: DownloadStatus) => items.filter((item) => item.status === status).length;
  const parts: string[] = [];
  const running = count("running");
  const waiting = count("waiting");
  const saved = count("saved");
  const failed = count("failed");
  if (running) parts.push(`${running} running`);
  if (waiting) parts.push(`${waiting} waiting`);
  if (saved) parts.push(`${saved} done`);
  if (failed) parts.push(`${failed} failed`);
  return parts.join(" · ") || "Nothing yet";
}

export function nextToStart(items: readonly DownloadItem[]): DownloadItem | null {
  if (items.some((item) => item.status === "running")) return null;
  return items.find((item) => item.status === "waiting") ?? null;
}

export function fileNameFrom(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const star = /filename\*\s*=\s*(?:UTF-8'')?([^;]+)/i.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, "")) || fallback;
    } catch {
      return fallback;
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition);
  return plain?.[1]?.trim() || fallback;
}

export const XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export function looksLikeZip(head: Uint8Array): boolean {
  return head.length >= 4 && head[0] === 0x50 && head[1] === 0x4b && (head[2] === 0x03 || head[2] === 0x05) && (head[3] === 0x04 || head[3] === 0x06);
}

export function retryDelaySeconds(attempt: number, retryAfter: number | null): number {
  if (retryAfter && retryAfter > 0) return Math.min(120, retryAfter);
  return Math.min(60, 5 * 2 ** Math.max(0, attempt - 1));
}
