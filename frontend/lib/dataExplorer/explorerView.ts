import { includeKeyOf, type IncludeKey, type ReportResponse, type ReviewCounts, type SavedView, type SavedViewState, type TranscriptCounts, type TreeEntry } from "@/lib/dataExplorer/contract";
import type { ExplorerUrlState } from "@/lib/dataExplorer/urlState";

export const FILES_PAGE_SIZE = 25;


export type TypeFilter = "all" | "images" | "videos" | "audios" | "documents" | "text" | "other";

export const TYPE_FILTERS: { value: TypeFilter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "images", label: "Images" },
  { value: "videos", label: "Videos" },
  { value: "audios", label: "Audio" },
  { value: "documents", label: "Documents" },
  { value: "text", label: "Text" },
  { value: "other", label: "Other" }
];

export function filterOf(entry: TreeEntry): TypeFilter {
  const key: IncludeKey = includeKeyOf({ content: entry.content, mediaType: entry.mediaType, path: entry.path, mediaId: entry.mediaId });
  if (key === "images" || key === "videos" || key === "audios" || key === "documents" || key === "text") return key;
  return "other";
}

export function pageOf<T>(items: readonly T[], page: number, size = FILES_PAGE_SIZE): { items: T[]; page: number; pages: number; first: number; last: number } {
  const pages = Math.max(1, Math.ceil(items.length / size));
  const current = Math.min(Math.max(1, page), pages);
  const start = (current - 1) * size;
  const slice = items.slice(start, start + size);
  return { items: slice, page: current, pages, first: slice.length ? start + 1 : 0, last: start + slice.length };
}


export function builtAgo(builtAt: string | null | undefined, now = Date.now()): string | null {
  if (!builtAt) return null;
  const at = Date.parse(builtAt);
  if (!Number.isFinite(at)) return null;
  const minutes = Math.max(0, Math.round((now - at) / 60000));
  if (minutes < 1) return "built just now";
  if (minutes < 60) return `built ${minutes} min ago`;
  return `built ${Math.round(minutes / 60)} h ago`;
}

export function highlightParts(text: string, needle: string): { text: string; hit: boolean }[] {
  const words = needle.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length || !text) return [{ text, hit: false }];
  const pattern = new RegExp(`(${words.map((word) => word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "gi");
  return text
    .split(pattern)
    .filter((part) => part !== "")
    .map((part) => ({ text: part, hit: words.includes(part.toLowerCase()) }));
}


export type OverviewSort = "least" | "most" | "name";

export function completeness(fields: { filled: number; total: number }): number {
  return fields.total > 0 ? fields.filled / fields.total : 0;
}

export function sortArtisans<T extends { name: string; fields: { filled: number; total: number } }>(rows: readonly T[], sort: OverviewSort): T[] {
  const out = [...rows];
  if (sort === "name") return out.sort((a, b) => a.name.localeCompare(b.name));
  out.sort((a, b) => completeness(a.fields) - completeness(b.fields) || a.name.localeCompare(b.name));
  return sort === "most" ? out.reverse() : out;
}

export function percentLabel(fields: { filled: number; total: number }): string {
  return fields.total > 0 ? `${Math.round((fields.filled / fields.total) * 100)}%` : "-";
}

export type ReviewTone = "done" | "waiting" | "none";

export function reviewFraction(review: ReviewCounts): string {
  return `${review.approved} / ${review.total}`;
}

export function reviewDetail(review: ReviewCounts): string {
  return `approved · ${review.pending} pending`;
}

export function reviewWord(review: ReviewCounts): string {
  if (review.total <= 0) return "-";
  if (review.approved >= review.total) return "Approved";
  if (review.approved <= 0) return "Pending";
  return "Partly approved";
}

export function reviewTone(review: ReviewCounts): ReviewTone {
  if (review.total <= 0) return "none";
  return review.approved >= review.total ? "done" : "waiting";
}

export function transcriptDetail(transcripts: TranscriptCounts): string {
  return `${transcripts.ready} ready · ${transcripts.translating} translating · ${transcripts.failed} failed`;
}


export function estimateLine(report: ReportResponse | null, chosen: number): string | null {
  if (!report) return null;
  const rows = report.sheets.reduce((sum, sheet) => sum + (typeof sheet.totalRows === "number" ? sheet.totalRows : sheet.rows.length), 0);
  const seconds = report.cached ? 2 : Math.max(3, Math.round(rows / 400));
  return `Ready in about ${seconds} s · ${chosen} ${chosen === 1 ? "sheet" : "sheets"} · checked to open in Excel, Numbers and Google Sheets`;
}


const VIEW_LABEL: Record<SavedViewState["view"], string> = { files: "Files", tables: "Tables", transcripts: "Transcripts" };

export function viewSummary(view: Pick<SavedView, "state" | "path">, folderName?: string): string {
  const parts: string[] = [VIEW_LABEL[view.state.view] ?? "Files"];
  if (view.state.sheet) parts.push(view.state.sheet);
  if (view.state.q) parts.push(`“${view.state.q}”`);
  for (const value of Object.values(view.state.filters ?? {})) if (value) parts.push(value);
  if (folderName) parts.push(folderName);
  return parts.join(" · ");
}

export function viewLink(id: string, origin = typeof window === "undefined" ? "" : window.location.origin): string {
  return `${origin}/data?viewId=${encodeURIComponent(id)}`;
}


export const TREE_WIDTH = { min: 240, initial: 326, max: 560, step: 16 } as const;

export const TREE_WIDTH_KEY = "data-explorer-tree-width";

export type WidthBounds = { min: number; max: number };

export const CONTENT_MIN_WIDTH = 480;

export const DIVIDER_WIDTH = 16;

export function treeWidthBounds(containerWidth: number | null | undefined): WidthBounds {
  const ceiling = containerWidth && containerWidth > 0 ? Math.floor(Math.min(containerWidth * 0.5, containerWidth - DIVIDER_WIDTH - CONTENT_MIN_WIDTH)) : TREE_WIDTH.max;
  return { min: TREE_WIDTH.min, max: Math.max(TREE_WIDTH.min, Math.min(TREE_WIDTH.max, ceiling)) };
}

export function clampTreeWidth(width: number, bounds: WidthBounds = treeWidthBounds(null)): number {
  const wanted = Number.isFinite(width) ? width : TREE_WIDTH.initial;
  return Math.round(Math.min(bounds.max, Math.max(bounds.min, wanted)));
}

export function treeWidthForKey(key: string, width: number, bounds: WidthBounds, large = false): number | null {
  const step = large ? TREE_WIDTH.step * 4 : TREE_WIDTH.step;
  if (key === "ArrowLeft" || key === "ArrowDown") return clampTreeWidth(width - step, bounds);
  if (key === "ArrowRight" || key === "ArrowUp") return clampTreeWidth(width + step, bounds);
  if (key === "Home") return bounds.min;
  if (key === "End") return bounds.max;
  return null;
}

export function readTreeWidth(raw: string | null | undefined): number | null {
  if (raw == null || raw.trim() === "") return null;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? clampTreeWidth(value) : null;
}

export const THUMBNAIL_EDGES = [256, 512] as const;

export function thumbnailEdge(renderedWidth: number, pixelRatio = 1): number {
  const needed = Math.max(0, renderedWidth) * Math.max(1, Number.isFinite(pixelRatio) ? pixelRatio : 1);
  return THUMBNAIL_EDGES.find((edge) => edge >= needed) ?? THUMBNAIL_EDGES[THUMBNAIL_EDGES.length - 1];
}

export function followingCursor(asked: string, answered: string | null | undefined, seen: ReadonlySet<string>): string | null {
  if (!answered || answered === asked || seen.has(answered)) return null;
  return answered;
}

export function mergeNavigation(base: ExplorerUrlState, next: Partial<ExplorerUrlState>): ExplorerUrlState {
  return { ...base, ...next };
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export function capturedLabel(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
}

const KIND_LABELS: Record<string, string> = {
  designWorkshop: "Design workshop",
  designWorkshopStage: "Design workshop stage",
  processStep: "Process step",
  questionnaireInterview: "Interview"
};

export function folderKindLabel(recordType: string | null | undefined): string | null {
  const raw = (recordType ?? "").trim();
  if (!raw) return null;
  if (KIND_LABELS[raw]) return KIND_LABELS[raw];
  const spaced = raw.replace(/[_-]+/g, " ").replace(/([a-z0-9])([A-Z])/g, "$1 $2").toLowerCase().trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function filesLabel(count: number): string {
  return `${count.toLocaleString("en-IN")} ${count === 1 ? "file" : "files"}`;
}
