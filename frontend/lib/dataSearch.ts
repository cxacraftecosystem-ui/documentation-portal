import { recordViewHref } from "@/lib/recordView";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";
import { UNIFIED_TYPES, type Portal, type UnifiedItem, type UnifiedResponse, type UnifiedType } from "@/lib/unifiedSearch";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export const LOCAL_PORTAL: Portal = "fieldRepository";

export type Scope = "local" | "all";

export type DateRange = "any" | "7d" | "30d" | "90d" | "year";

export type SearchState = {
  q: string;
  scope: Scope;
  workshopIds: string[];
  artisanId: string;
  craftId: string;
  types: UnifiedType[];
  range: DateRange;
  transcriptOnly: boolean;
};

export const EMPTY_STATE: SearchState = { q: "", scope: "local", workshopIds: [], artisanId: "", craftId: "", types: [], range: "any", transcriptOnly: false };

export const SEARCH_URL_KEYS = { q: "sq", scope: "sscope", focus: "sin", types: "stypes", range: "srange", transcriptOnly: "stx", workshopIds: "sws", artisanId: "sart", craftId: "scraft" } as const;

const DATE_RANGES: readonly DateRange[] = ["any", "7d", "30d", "90d", "year"];

type SearchParamsLike = { get(name: string): string | null };

function isUnifiedType(value: string): value is UnifiedType {
  return (UNIFIED_TYPES as readonly string[]).includes(value);
}

function listParam(params: SearchParamsLike, key: string): string[] {
  return (params.get(key) ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

export function searchFromParams(params: SearchParamsLike): { state: SearchState; focus: UnifiedType | null } {
  const range = params.get(SEARCH_URL_KEYS.range) ?? "";
  const focus = params.get(SEARCH_URL_KEYS.focus) ?? "";
  return {
    state: {
      q: params.get(SEARCH_URL_KEYS.q) ?? "",
      scope: params.get(SEARCH_URL_KEYS.scope) === "all" ? "all" : "local",
      workshopIds: listParam(params, SEARCH_URL_KEYS.workshopIds),
      artisanId: params.get(SEARCH_URL_KEYS.artisanId) ?? "",
      craftId: params.get(SEARCH_URL_KEYS.craftId) ?? "",
      types: listParam(params, SEARCH_URL_KEYS.types).filter(isUnifiedType),
      range: (DATE_RANGES as readonly string[]).includes(range) ? (range as DateRange) : "any",
      transcriptOnly: params.get(SEARCH_URL_KEYS.transcriptOnly) === "1"
    },
    focus: isUnifiedType(focus) ? focus : null
  };
}

export function searchToParams(state: SearchState, focus: UnifiedType | null, base: string): string {
  const params = new URLSearchParams(base);
  const values: Record<keyof typeof SEARCH_URL_KEYS, string> = {
    q: state.q.trim(),
    scope: state.scope === "all" ? "all" : "",
    focus: focus ?? "",
    types: state.types.join(","),
    range: state.range === "any" ? "" : state.range,
    transcriptOnly: state.transcriptOnly ? "1" : "",
    workshopIds: state.workshopIds.join(","),
    artisanId: state.artisanId,
    craftId: state.craftId
  };
  for (const key of Object.keys(SEARCH_URL_KEYS) as (keyof typeof SEARCH_URL_KEYS)[]) {
    if (values[key]) params.set(SEARCH_URL_KEYS[key], values[key]);
    else params.delete(SEARCH_URL_KEYS[key]);
  }
  return params.toString();
}

export function sameSearch(a: { state: SearchState; focus: UnifiedType | null }, b: { state: SearchState; focus: UnifiedType | null }): boolean {
  return searchToParams({ ...a.state, q: a.state.q.trim() }, a.focus, "") === searchToParams({ ...b.state, q: b.state.q.trim() }, b.focus, "");
}

export type Row = UnifiedItem;

export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, "0");
  return hours ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}

export function rangeBounds(range: DateRange, now = new Date()): { dateFrom?: string; dateTo?: string } {
  const day = (offset: number) => new Date(now.getFullYear(), now.getMonth(), now.getDate() - offset).toISOString();
  switch (range) {
    case "7d":
      return { dateFrom: day(6) };
    case "30d":
      return { dateFrom: day(29) };
    case "90d":
      return { dateFrom: day(89) };
    case "year":
      return { dateFrom: new Date(now.getFullYear(), 0, 1).toISOString() };
    default:
      return {};
  }
}

export function activeFilters(state: SearchState): number {
  return (state.workshopIds.length ? 1 : 0) + (state.artisanId ? 1 : 0) + (state.craftId ? 1 : 0) + (state.types.length ? 1 : 0) + (state.range !== "any" ? 1 : 0) + (state.transcriptOnly ? 1 : 0);
}

export function flattenGroups(data: UnifiedResponse, focus: UnifiedType | null): Row[] {
  const groups = focus ? data.groups.filter((group) => group.type === focus) : data.groups;
  return groups.flatMap((group) => group.items as Row[]);
}

export function portalCounts(data: UnifiedResponse, local: Portal = LOCAL_PORTAL): Record<Portal, number> {
  const out: Record<Portal, number> = { designer: 0, fieldRepository: 0 };
  const remote: Portal = local === "designer" ? "fieldRepository" : "designer";
  for (const group of data.groups) {
    out[local] += group.localTotal;
    out[remote] += group.remoteTotal;
  }
  return out;
}

export type LegacyResult = { artisans?: Artisan[]; workshops?: Workshop[]; products?: ProductDocumentation[]; tools?: ToolDocumentation[]; media?: MediaFile[]; totals?: Partial<Record<string, number>> };

export function legacyRows(result: LegacyResult): Row[] {
  const base = { portal: LOCAL_PORTAL, local: true, snippet: "", highlights: [] as number[][] };
  return [
    ...(result.artisans ?? []).map((a) => ({ ...base, type: "artisans" as UnifiedType, id: a.id, title: a.name, subtitle: [a.craft?.name, a.place].filter(Boolean).join(" · "), href: recordViewHref("artisan", a.id) ?? "" })),
    ...(result.workshops ?? []).map((w) => ({ ...base, type: "workshops" as UnifiedType, id: w.id, title: workshopDisplayTitle(w), subtitle: w.place ?? null, href: recordViewHref("workshop", w.id) ?? "" })),
    ...(result.products ?? []).map((p) => ({ ...base, type: "products" as UnifiedType, id: p.id, title: p.productName, subtitle: p.artisanName ?? null, href: recordViewHref("product", p.id) ?? "" })),
    ...(result.tools ?? []).map((t) => ({ ...base, type: "tools" as UnifiedType, id: t.id, title: t.toolkitName, subtitle: t.artisanName ?? null, href: recordViewHref("tool", t.id) ?? "" })),
    ...(result.media ?? []).map((m) => ({ ...base, type: "media" as UnifiedType, id: m.id, title: m.caption || m.originalFilename, subtitle: m.mediaType ?? null, href: `/media/${encodeURIComponent(m.id)}` }))
  ];
}


export const ALL_LIMIT = 5;

export const TYPE_LIMIT = 20;

export const LEGACY_PAGE = 20;

export function offersOpenInData(row: Row, locateType: string | undefined): boolean {
  return Boolean(row.local && locateType && row.locatable !== false);
}

export function rowKey(row: Row): string {
  return `${row.portal}:${row.type}:${row.id}`;
}

export function mergeRows(previous: Row[], next: Row[]): Row[] {
  const seen = new Set(previous.map(rowKey));
  return [...previous, ...next.filter((row) => !seen.has(rowKey(row)) && seen.add(rowKey(row)))];
}

export type GroupTotal = { type: UnifiedType; label: string; total: number };

export function unifiedTotals(data: UnifiedResponse): GroupTotal[] {
  return data.groups.map((group) => ({ type: group.type, label: group.label, total: group.total }));
}

const LEGACY_LABELS: [UnifiedType, string][] = [
  ["artisans", "Artisans"],
  ["workshops", "Workshops"],
  ["products", "Products"],
  ["tools", "Tools"],
  ["media", "Media"]
];

export function legacyTotals(result: LegacyResult): GroupTotal[] {
  const rows = legacyRows(result);
  return LEGACY_LABELS.map(([type, label]) => ({ type, label, total: Math.max(result.totals?.[type] ?? 0, rows.filter((row) => row.type === type).length) })).filter((group) => group.total > 0);
}

export function remainingIn(group: GroupTotal, rows: Row[]): number {
  return Math.max(0, group.total - rows.filter((row) => row.type === group.type).length);
}

export function hasMore(focus: UnifiedType | null, groups: GroupTotal[], rows: Row[], progressed: boolean): boolean {
  return progressed && groups.filter((group) => !focus || group.type === focus).some((group) => remainingIn(group, rows) > 0);
}

export function sections(groups: GroupTotal[], rows: Row[]): { group: GroupTotal; rows: Row[] }[] {
  return groups.map((group) => ({ group, rows: rows.filter((row) => row.type === group.type) })).filter((section) => section.rows.length > 0);
}

export function moreLabel(focus: UnifiedType | null, groups: GroupTotal[], rows: Row[]): string {
  if (!focus) return "Load more";
  const left = groups.filter((group) => group.type === focus).reduce((sum, group) => sum + remainingIn(group, rows), 0);
  return `Show ${Math.min(TYPE_LIMIT, left)} more`;
}

export function showAllLabel(group: GroupTotal): string {
  return `Show all ${group.total.toLocaleString("en-IN")} ${group.label.toLowerCase()}`;
}
