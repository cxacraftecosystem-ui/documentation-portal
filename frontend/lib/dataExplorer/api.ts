import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import {
  mediaPath,
  LIMITS,
  type BrowsePage,
  type IncludeKey,
  type ReportResponse,
  type Stats,
  type TranscriptText,
  type TreeResponse
} from "@/lib/dataExplorer/contract";
import { apiUrl, isUnsupported, type QueryParams } from "@/lib/dataExplorer/wire";

export function statusOf(error: unknown): number | null {
  return error instanceof ApiError ? error.status : null;
}

export function unsupported(error: unknown): boolean {
  const status = statusOf(error);
  return status !== null && isUnsupported(status);
}

export async function fetchTreePage(
  path: string,
  extra: QueryParams,
  cursor?: string | null,
  signal?: AbortSignal
): Promise<TreeResponse> {
  return apiFetch<TreeResponse>(
    `/data/tree${buildQuery({ path, slim: 1, cursor: cursor ?? undefined, limit: LIMITS.treePageMax, ...extra })}`,
    { signal }
  );
}

export function includeParam(include: readonly IncludeKey[] | null): string | undefined {
  return include && include.length ? include.join(",") : undefined;
}

export async function fetchStats(path: string, include: readonly IncludeKey[] | null, extra: QueryParams = {}): Promise<Stats> {
  return apiFetch<Stats>(`/data/stats${buildQuery({ path, include: includeParam(include), ...extra })}`);
}

export async function fetchSelectionStats(
  paths: string[],
  mediaIds: string[],
  include: readonly IncludeKey[] | null,
  extra: QueryParams = {}
): Promise<Stats> {
  return apiFetch<Stats>(`/data/stats${buildQuery(extra)}`, {
    method: "POST",
    body: JSON.stringify({ paths, mediaIds, include: include && include.length ? include : null })
  });
}

export type ReportQuery = { path: string; sheet?: string; q?: string; extra?: QueryParams };

export function fetchReport(request: ReportQuery, signal?: AbortSignal): Promise<ReportResponse> {
  return apiFetch<ReportResponse>(
    `/data/report${buildQuery({ path: request.path, format: "json", sheet: request.sheet, q: request.q, ...(request.extra ?? {}) })}`,
    { signal }
  );
}

export function reportFileUrl(path: string, format: "xlsx" | "csv", extra: QueryParams = {}): string {
  return apiUrl("/data/report", { path, format, ...extra });
}

const transcriptInFlight = new Map<string, Promise<TranscriptText>>();
const transcriptCache = new Map<string, TranscriptText>();
const TRANSCRIPT_CACHE_ENTRIES = 40;

export function forgetTranscripts(): void {
  transcriptInFlight.clear();
  transcriptCache.clear();
}

export function transcriptRequests(): number {
  return transcriptInFlight.size;
}

export function fetchTranscriptText(mediaId: string, fallback: () => Promise<string | null>): Promise<TranscriptText> {
  const cached = transcriptCache.get(mediaId);
  if (cached) return Promise.resolve(cached);
  const running = transcriptInFlight.get(mediaId);
  if (running) return running;
  const asking = (async (): Promise<TranscriptText> => {
    try {
      const answer = await apiFetch<TranscriptText>(mediaPath(mediaId, "transcript"));
      return { mediaId, name: answer.name ?? null, language: answer.language ?? null, content: answer.content ?? null };
    } catch (error) {
      if (!unsupported(error)) throw error;
      return { mediaId, content: await fallback() };
    }
  })();
  transcriptInFlight.set(mediaId, asking);
  asking
    .then((answer) => {
      transcriptCache.set(mediaId, answer);
      while (transcriptCache.size > TRANSCRIPT_CACHE_ENTRIES) {
        const oldest = transcriptCache.keys().next().value as string | undefined;
        if (oldest === undefined) break;
        transcriptCache.delete(oldest);
      }
    })
    .catch(() => undefined)
    .finally(() => transcriptInFlight.delete(mediaId));
  return asking;
}

export type BrowseQuery = { type: string; page: number; pageSize?: number; q?: string; workshopIds?: string };

export function fetchBrowse(request: BrowseQuery): Promise<BrowsePage> {
  return apiFetch<BrowsePage>(
    `/data/browse${buildQuery({
      type: request.type,
      page: request.page,
      pageSize: request.pageSize ?? LIMITS.browsePageSize,
      q: request.q?.trim() || undefined,
      workshopIds: request.workshopIds
    })}`
  );
}

export function mediaDownloadUrl(mediaId: string): string {
  return apiUrl(mediaPath(mediaId, "download"));
}

export function mediaPreviewUrl(mediaId: string): string {
  return apiUrl(mediaPath(mediaId, "preview"));
}

export function mediaThumbnailUrl(mediaId: string, size: number = LIMITS.thumbnailSize): string {
  return apiUrl(mediaPath(mediaId, "thumbnail"), { size });
}

export function transcriptZipUrl(mediaId: string): string {
  return apiUrl(`/transcripts/${encodeURIComponent(mediaId)}/download`, { format: "zip", track: "both", timestamps: "true" });
}

export function transcriptBundleUrl(path: string, extra: QueryParams = {}): string {
  return apiUrl("/data/transcript-bundle", { path, track: "both", timestamps: "true", ...extra });
}

export function handOff(url: string, name?: string): void {
  const anchor = document.createElement("a");
  anchor.href = url;
  if (name) anchor.download = name;
  else anchor.setAttribute("download", "");
  anchor.rel = "noopener";
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}
