/**
 * One search across both portals, on the web (docs/SEARCH.md).
 *
 * `GET /search/unified` ranks this portal's index and, for the roles allowed, the designer portal's
 * too; this module types the answer, keeps the group order and labels, and falls back to the legacy
 * `GET /search` whenever the server answers 404 (the feature switched off). The ranking, grouping
 * and recent-search rules are Android's `ui/AppSearch.kt` — group order, score 0 exact / 1 prefix /
 * 2 word prefix / 3 substring / 4 other, stable within a score, five per group, eight recents — so
 * the two clients order one answer the same way.
 *
 * Snippets are plain text and highlights are character ranges: nothing here builds HTML.
 */
import { apiFetch, ApiError, buildQuery } from "@/lib/api";

/** Every search type, in the order groups are shown. The server's `cross_portal.SEARCH_TYPES`. */
export const UNIFIED_TYPES = [
  "artisans",
  "crafts",
  "clusters",
  "workshops",
  "designWorkshops",
  "interviews",
  "transcripts",
  "reports",
  "products",
  "tools",
  "processes",
  "media"
] as const;

export type UnifiedType = (typeof UNIFIED_TYPES)[number];

export const UNIFIED_LABELS: Record<UnifiedType, string> = {
  artisans: "Artisans",
  crafts: "Crafts",
  clusters: "Clusters",
  workshops: "Workshops",
  designWorkshops: "Design workshops",
  interviews: "Interviews",
  transcripts: "Transcripts",
  reports: "Reports",
  products: "Products",
  tools: "Tools",
  processes: "Processes",
  media: "Media"
};

export type Portal = "designer" | "fieldRepository";

export const PORTAL_LABELS: Record<Portal, string> = {
  designer: "Designer portal",
  fieldRepository: "Field Repository"
};

export type UnifiedItem = {
  portal: Portal;
  local: boolean;
  type: UnifiedType;
  id: string;
  title: string;
  subtitle?: string | null;
  snippet?: string;
  highlights?: number[][];
  place?: string | null;
  craftCodes?: string[];
  recordDate?: string | null;
  href: string;
  openUrl?: string | null;
  score?: number;
};

export type UnifiedGroup = {
  type: UnifiedType;
  label: string;
  total: number;
  localTotal: number;
  remoteTotal: number;
  items: UnifiedItem[];
};

export type RemoteStatus = "ok" | "off" | "notPermitted" | "unavailable" | "filteredOut";

export type UnifiedResponse = {
  query: string;
  mode: "hybrid" | "text";
  groups: UnifiedGroup[];
  remote: { portal: Portal; label: string; status: RemoteStatus };
  typesRefused: string[];
  type?: UnifiedType;
  page?: number;
  limit?: number;
};

/** One record from the other portal as a read-only card (`GET /search/remote/{type}/{id}`). */
export type RemoteRecord = {
  portal: Portal;
  type: UnifiedType;
  id: string;
  title: string;
  subtitle?: string | null;
  fields: { label: string; value: string }[];
  snippet?: string;
  recordDate?: string | null;
  openUrl?: string | null;
};

export const UNIFIED_GROUP_CAP = 5;
export const UNIFIED_MIN_CHARS = 2;
export const UNIFIED_MAX_CHARS = 200;
export const RECENT_MAX = 8;

export type UnifiedQuery = {
  q: string;
  types?: string[];
  scope?: "all" | "local";
  limit?: number;
  type?: UnifiedType;
  page?: number;
  craftId?: string;
  place?: string;
  dateFrom?: string;
  dateTo?: string;
  workshopIds?: string;
};

export function isUnifiedType(value: unknown): value is UnifiedType {
  return typeof value === "string" && (UNIFIED_TYPES as readonly string[]).includes(value);
}

/** The query string for `/search/unified`; arrays are comma-joined, blanks dropped. */
export function unifiedQueryString(query: UnifiedQuery): string {
  return buildQuery({
    q: query.q.trim(),
    types: query.types?.length ? query.types.join(",") : undefined,
    scope: query.scope && query.scope !== "all" ? query.scope : undefined,
    limit: query.limit ?? UNIFIED_GROUP_CAP,
    type: query.type,
    page: query.type ? (query.page ?? 1) : undefined,
    craftId: query.craftId,
    place: query.place?.trim() || undefined,
    dateFrom: query.dateFrom,
    dateTo: query.dateTo,
    workshopIds: query.workshopIds
  });
}

/** Whether a question can go to the unified route at all: enough text, and no legacy-only filter. */
export function unifiedApplies(q: string, legacyOnly: { artisanId?: string; mediaType?: string } = {}): boolean {
  const text = q.trim();
  return text.length >= UNIFIED_MIN_CHARS && text.length <= UNIFIED_MAX_CHARS && !legacyOnly.artisanId && !legacyOnly.mediaType;
}

export type UnifiedOutcome = { kind: "unified"; data: UnifiedResponse } | { kind: "legacy" };

/**
 * Ask the unified route; a 404 means the feature is off on this server, and the caller uses the
 * legacy `GET /search` instead. Any other failure is thrown as it came.
 */
export async function fetchUnified(
  query: UnifiedQuery,
  fetcher: (path: string) => Promise<UnifiedResponse> = (path) => apiFetch<UnifiedResponse>(path)
): Promise<UnifiedOutcome> {
  try {
    const data = await fetcher(`/search/unified${unifiedQueryString(query)}`);
    return { kind: "unified", data: { ...data, groups: mergeGroups(data.groups ?? []) } };
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return { kind: "legacy" };
    throw error;
  }
}

export function fetchRemoteRecord(type: string, id: string): Promise<RemoteRecord> {
  return apiFetch<RemoteRecord>(`/search/remote/${encodeURIComponent(type)}/${encodeURIComponent(id)}`);
}

/**
 * Groups in vocabulary order, unknown types dropped, items de-duplicated by (portal, type, id) and
 * ordered by score (higher first), local first on a tie, then arrival order. Several answers (a page
 * and the next) merge into one group.
 */
export function mergeGroups(...lists: UnifiedGroup[][]): UnifiedGroup[] {
  const byType = new Map<UnifiedType, UnifiedGroup>();
  for (const list of lists) {
    for (const group of list) {
      if (!isUnifiedType(group?.type)) continue;
      const existing = byType.get(group.type);
      if (!existing) {
        byType.set(group.type, { ...group, label: UNIFIED_LABELS[group.type], items: [...(group.items ?? [])] });
      } else {
        existing.items.push(...(group.items ?? []));
        existing.total = Math.max(existing.total, group.total);
        existing.localTotal = Math.max(existing.localTotal, group.localTotal);
        existing.remoteTotal = Math.max(existing.remoteTotal, group.remoteTotal);
      }
    }
  }
  return UNIFIED_TYPES.filter((type) => byType.has(type)).map((type) => {
    const group = byType.get(type)!;
    const seen = new Set<string>();
    const unique = group.items.filter((item) => {
      const key = `${item.portal}\u0000${item.type}\u0000${item.id}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    const ranked = unique
      .map((item, index) => ({ item, index }))
      .sort(
        (a, b) =>
          (b.item.score ?? 0) - (a.item.score ?? 0) ||
          Number(b.item.local) - Number(a.item.local) ||
          a.index - b.index
      )
      .map(({ item }) => item);
    return { ...group, items: ranked };
  });
}

/** Android's `appSearchScore`: 0 exact, 1 prefix, 2 word prefix, 3 substring, 4 anything else. */
export function appSearchScore(title: string, query: string): number {
  const t = title.trim().toLowerCase();
  const q = query.trim().toLowerCase();
  if (!q) return 4;
  if (t === q) return 0;
  if (t.startsWith(q)) return 1;
  if (t.split(/[^\p{L}\p{N}]+/u).some((word) => word && word.startsWith(q))) return 2;
  if (t.includes(q)) return 3;
  return 4;
}

/** Android's `pushRecentSearch`. */
export function pushRecentSearch(recents: string[], query: string, max = RECENT_MAX): string[] {
  const q = query.trim();
  if (!q) return recents.slice(0, max);
  return [q, ...recents.filter((r) => r.toLowerCase() !== q.toLowerCase())].slice(0, max);
}

export type HighlightSegment = { text: string; hit: boolean };

/**
 * `text` cut into plain segments at the highlight ranges. Ranges out of bounds, reversed or
 * overlapping are clipped; the caller renders each segment as a text node (a hit in `<mark>`).
 */
export function highlightSegments(text: string, ranges: number[][] | undefined): HighlightSegment[] {
  if (!text) return [];
  const clean = (ranges ?? [])
    .filter((r) => Array.isArray(r) && r.length === 2 && Number.isFinite(r[0]) && Number.isFinite(r[1]))
    .map(([s, e]) => [Math.max(0, Math.min(s, text.length)), Math.max(0, Math.min(e, text.length))])
    .filter(([s, e]) => e > s)
    .sort((a, b) => a[0] - b[0]);
  const out: HighlightSegment[] = [];
  let cursor = 0;
  for (const [start, end] of clean) {
    const s = Math.max(start, cursor);
    if (end <= s) continue;
    if (s > cursor) out.push({ text: text.slice(cursor, s), hit: false });
    out.push({ text: text.slice(s, end), hit: true });
    cursor = end;
  }
  if (cursor < text.length) out.push({ text: text.slice(cursor), hit: false });
  return out;
}

/** The one quiet line under the results when the other portal was not asked or did not answer. */
export function remoteStatusLine(status: RemoteStatus | undefined, label: string): string | null {
  switch (status) {
    case "unavailable":
      return `${label} results aren't available right now.`;
    case "filteredOut":
      return `${label} results don't apply to these filters.`;
    case "notPermitted":
      return `Showing results from this portal.`;
    default:
      return null;
  }
}

/**
 * The other portal's address for a record, only when it is a web address. It comes from the other
 * server (signed, but still not this portal's own words), so anything else is no link at all.
 */
export function safeOpenUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" || parsed.protocol === "http:" ? parsed.toString() : null;
  } catch {
    return null;
  }
}

/** "Open in Field Repository" / "Open in Designer portal". */
export function openInLabel(portal: Portal): string {
  return `Open in ${PORTAL_LABELS[portal] ?? "the other portal"}`;
}

/** The card's rows: the other portal's own field list, then the record date. */
export function remoteRecordRows(record: RemoteRecord, formatDate: (iso: string) => string): { label: string; value: string }[] {
  const rows = (record.fields ?? []).filter((field) => field.label && field.value);
  if (record.recordDate) rows.push({ label: "Recorded", value: formatDate(record.recordDate) });
  return rows;
}

/**
 * Whether trying again could help: the proxy's 503 (the other portal did not answer), a 5xx, or no
 * connection at all (`fetch` rejects with a TypeError before any status exists).
 */
export function remoteRecordRetryable(error: unknown): boolean {
  if (error instanceof ApiError) return error.status >= 500;
  return error instanceof TypeError;
}

/** The one sentence for a remote record that could not be shown. */
export function remoteRecordError(error: unknown): string {
  if (remoteRecordRetryable(error)) return "The other portal can't be reached right now. Try again shortly.";
  return "This record isn't available.";
}

/** The interview a search result opens on the questionnaire page (`?interview=<id>`); "" when absent. */
export function interviewFromSearchParams(params: { get(name: string): string | null }): string {
  return (params.get("interview") ?? "").trim().slice(0, 64);
}
