import { includeKeyOf, type IncludeKey, type ReportResponse, type SavedView, type SavedViewState, type TreeEntry } from "@/lib/dataExplorer/contract";
import type { ExplorerUrlState } from "@/lib/dataExplorer/urlState";

export const FILES_PAGE_SIZE = 25;


export type TypeFilter = "all" | "images" | "videos" | "audios" | "documents" | "other";

export const TYPE_FILTERS: { value: TypeFilter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "images", label: "Images" },
  { value: "videos", label: "Videos" },
  { value: "audios", label: "Audio" },
  { value: "documents", label: "Documents" },
  { value: "other", label: "Other" }
];

export function filterOf(entry: TreeEntry): TypeFilter {
  const key: IncludeKey = includeKeyOf({ content: entry.content, mediaType: entry.mediaType, path: entry.path, mediaId: entry.mediaId });
  if (key === "images" || key === "videos" || key === "audios" || key === "documents") return key;
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
