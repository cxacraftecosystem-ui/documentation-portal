import { apiFetch, buildQuery } from "@/lib/api";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type FacetKey = "recordType" | "workshop" | "designWorkshop" | "craft" | "artisan" | "mediaType" | "status" | "recordedBy" | "language" | "hasTranscript";

export type FilterParam = "recordTypes" | "workshopIds" | "designWorkshopIds" | "craftIds" | "artisanIds" | "mediaTypes" | "statuses" | "recordedByIds" | "languages" | "hasTranscript";

export type Portal = "this" | "other";

export type ExplorerFilters = {
  values: Record<FilterParam, string[]>;
  dateFrom: string;
  dateTo: string;
  portals: Portal[];
};

export const FILTER_PARAMS: readonly { param: FilterParam; facet: FacetKey; label: string }[] = [
  { param: "recordTypes", facet: "recordType", label: "Record type" },
  { param: "workshopIds", facet: "workshop", label: "Workshop" },
  { param: "designWorkshopIds", facet: "designWorkshop", label: "Design workshop" },
  { param: "craftIds", facet: "craft", label: "Craft" },
  { param: "artisanIds", facet: "artisan", label: "Artisan" },
  { param: "mediaTypes", facet: "mediaType", label: "Media type" },
  { param: "statuses", facet: "status", label: "Review status" },
  { param: "recordedByIds", facet: "recordedBy", label: "Recorded by" },
  { param: "languages", facet: "language", label: "Language" },
  { param: "hasTranscript", facet: "hasTranscript", label: "Has transcript" }
];

export const PORTAL_VALUES: readonly Portal[] = ["this", "other"];

export const DEFAULT_PORTALS: Portal[] = ["this"];

export const FACET_ORDER: readonly string[] = ["portal", "recordType", "workshop", "designWorkshop", "craft", "artisan", "mediaType", "status", "recordedBy", "language", "hasTranscript", "date"];

export const MEDIA_TYPE_LABELS: Record<string, string> = { IMAGE: "Images", VIDEO: "Videos", AUDIO: "Audio", PDF: "PDFs", DOCUMENT: "Documents", OTHER: "Other" };

export const STATUS_LABELS: Record<string, string> = { DRAFT: "Draft", PENDING: "Pending", APPROVED: "Approved", REJECTED: "Rejected", NEEDS_REVISION: "Needs revision" };

export const RECORD_TYPE_VALUES = ["artisans", "workshops", "products", "tools", "media", "designWorkshops"] as const;

export const RECORD_TYPE_LABELS: Record<string, string> = { artisans: "Artisans", workshops: "Workshops", products: "Products", tools: "Tools", media: "Media", designWorkshops: "Design workshops" };

export const TRANSCRIPT_VALUES = ["yes", "no"] as const;

export const TRANSCRIPT_LABELS: Record<string, string> = { yes: "Yes", no: "No" };

export type FacetOption = { value: string; label: string; hint: string | null; count: number };

export type Facet = { facet: FacetKey; param: FilterParam; label: string; options: FacetOption[]; truncated: boolean; selected: string[] };

export type PortalOption = { value: Portal; label: string; available: boolean; status: string };

export type FacetsResponse = {
  facets: Facet[];
  dateBounds: { min: string | null; max: string | null };
  portal: { options: PortalOption[] };
};

export type ActiveFilter = { param: string; facet: string; label: string; values: { value: string; label: string }[] };

export type AppliedFilters = { active: ActiveFilter[]; count: number };

export type PeerRow = {
  portal: string;
  portalLabel: string;
  local: false;
  type: string;
  id: string;
  title: string;
  subtitle: string | null;
  place: string | null;
  recordDate: string | null;
  craftCodes: string[];
  href: string;
  openUrl: string | null;
};

export type PeerStatus = "ok" | "off" | "notPermitted" | "unavailable" | "filteredOut";

export type PeerPage = {
  portal: string;
  portalLabel: string;
  status: PeerStatus;
  items: PeerRow[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
  notApplied: string[];
};

export const EMPTY_FILTERS: ExplorerFilters = {
  values: { recordTypes: [], workshopIds: [], designWorkshopIds: [], craftIds: [], artisanIds: [], mediaTypes: [], statuses: [], recordedByIds: [], languages: [], hasTranscript: [] },
  dateFrom: "",
  dateTo: "",
  portals: [...DEFAULT_PORTALS]
};

type ParamsLike = { get(name: string): string | null; getAll?(name: string): string[] };

function listOf(params: ParamsLike, name: string): string[] {
  const raw = params.getAll ? params.getAll(name) : [params.get(name) ?? ""];
  const seen = new Set<string>();
  for (const value of raw)
    for (const part of value.split(",")) {
      const text = part.trim();
      if (text) seen.add(text);
    }
  return [...seen];
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

export function readFilters(params: ParamsLike | null | undefined): ExplorerFilters {
  if (!params) return cloneFilters(EMPTY_FILTERS);
  const values = { ...EMPTY_FILTERS.values };
  for (const { param } of FILTER_PARAMS) values[param] = listOf(params, param);
  values.recordTypes = RECORD_TYPE_VALUES.filter((value) => values.recordTypes.some((picked) => picked.toLowerCase() === value.toLowerCase()));
  values.hasTranscript = values.hasTranscript.map((value) => value.toLowerCase()).filter((value) => (TRANSCRIPT_VALUES as readonly string[]).includes(value)).slice(-1);
  const portals = listOf(params, "portals").filter((value): value is Portal => (PORTAL_VALUES as readonly string[]).includes(value));
  const from = (params.get("dateFrom") ?? "").trim();
  const to = (params.get("dateTo") ?? "").trim();
  return {
    values,
    dateFrom: ISO_DAY.test(from) ? from : "",
    dateTo: ISO_DAY.test(to) ? to : "",
    portals: portals.length ? portals : [...DEFAULT_PORTALS]
  };
}

export function cloneFilters(filters: ExplorerFilters): ExplorerFilters {
  return { values: { ...filters.values }, dateFrom: filters.dateFrom, dateTo: filters.dateTo, portals: [...filters.portals] };
}

export function filterQuery(filters: ExplorerFilters): QueryParams {
  const out: QueryParams = {};
  for (const { param } of FILTER_PARAMS) {
    const values = filters.values[param];
    if (values.length) out[param] = values.join(",");
  }
  if (filters.dateFrom) out.dateFrom = filters.dateFrom;
  if (filters.dateTo) out.dateTo = filters.dateTo;
  return out;
}

export function urlFilterQuery(filters: ExplorerFilters): QueryParams {
  const out = filterQuery(filters);
  if (!samePortals(filters.portals, DEFAULT_PORTALS)) out.portals = filters.portals.join(",");
  return out;
}

export function writeFilters(filters: ExplorerFilters, base: { toString(): string }): URLSearchParams {
  const params = new URLSearchParams(base.toString());
  for (const { param } of FILTER_PARAMS) params.delete(param);
  for (const name of ["dateFrom", "dateTo", "portals"]) params.delete(name);
  for (const [key, value] of Object.entries(urlFilterQuery(filters))) if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  return params;
}

export function samePortals(a: readonly Portal[], b: readonly Portal[]): boolean {
  return a.length === b.length && a.every((value) => b.includes(value));
}

export function activeCount(filters: ExplorerFilters): number {
  let count = FILTER_PARAMS.filter(({ param }) => filters.values[param].length > 0).length;
  if (filters.dateFrom) count += 1;
  if (filters.dateTo) count += 1;
  return count;
}

export function hasFilters(filters: ExplorerFilters): boolean {
  return activeCount(filters) > 0;
}

export function showsThis(filters: ExplorerFilters): boolean {
  return filters.portals.includes("this");
}

export function showsOther(filters: ExplorerFilters): boolean {
  return filters.portals.includes("other");
}

export function cleared(): ExplorerFilters {
  return cloneFilters(EMPTY_FILTERS);
}

export function withValues(filters: ExplorerFilters, param: FilterParam, values: string[]): ExplorerFilters {
  return { ...cloneFilters(filters), values: { ...filters.values, [param]: values } };
}

export function withTranscript(filters: ExplorerFilters, choice: string): ExplorerFilters {
  const value = (TRANSCRIPT_VALUES as readonly string[]).includes(choice) ? [choice] : [];
  return withValues(filters, "hasTranscript", value);
}

export function searchTypes(filters: ExplorerFilters, offered: readonly string[]): string[] | null {
  return outerSearchTypes(filters.values.recordTypes, filters.values.hasTranscript[0] ?? "", offered);
}

export function outerSearchTypes(picked: readonly string[], choice: string, offered: readonly string[]): string[] | null {
  const base = picked.length ? picked.flatMap((type) => (type === "media" ? ["media", "transcripts"] : [type])).filter((type) => offered.includes(type)) : [];
  if (picked.length && !base.length) return null;
  if (choice === "yes") return base.length ? base.filter((type) => type !== "media") : ["transcripts"];
  if (choice === "no") return base.filter((type) => type !== "transcripts");
  return base;
}

export function withDates(filters: ExplorerFilters, dateFrom: string, dateTo: string): ExplorerFilters {
  return { ...cloneFilters(filters), dateFrom, dateTo };
}

export function withPortals(filters: ExplorerFilters, portals: string[]): ExplorerFilters {
  const chosen = PORTAL_VALUES.filter((value) => portals.includes(value));
  return { ...cloneFilters(filters), portals: chosen.length ? chosen : [...DEFAULT_PORTALS] };
}

function day(value: string): string {
  const [year, month, date] = value.split("-").map(Number);
  if (!year || !month || !date) return value;
  return new Date(year, month - 1, date).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function describeFilters(filters: ExplorerFilters, facets: readonly Facet[] | null, applied?: AppliedFilters | null): string[] {
  const out: string[] = [];
  for (const { param, facet, label } of FILTER_PARAMS) {
    const values = filters.values[param];
    if (!values.length) continue;
    const fromServer = applied?.active.find((entry) => entry.param === param);
    const options = facets?.find((entry) => entry.facet === facet)?.options ?? [];
    const names = values.map((value) => {
      const known = fromServer?.values.find((entry) => entry.value === value)?.label ?? options.find((option) => option.value === value)?.label;
      if (known) return known;
      if (param === "mediaTypes") return MEDIA_TYPE_LABELS[value] ?? value;
      if (param === "statuses") return STATUS_LABELS[value] ?? value;
      if (param === "recordTypes") return RECORD_TYPE_LABELS[value] ?? value;
      if (param === "hasTranscript") return TRANSCRIPT_LABELS[value] ?? value;
      return value;
    });
    out.push(`${label}: ${names.join(", ")}`);
  }
  if (filters.dateFrom && filters.dateTo) out.push(`Date: ${day(filters.dateFrom)} – ${day(filters.dateTo)}`);
  else if (filters.dateFrom) out.push(`From ${day(filters.dateFrom)}`);
  else if (filters.dateTo) out.push(`To ${day(filters.dateTo)}`);
  return out;
}

export function optionLabel(option: FacetOption): string {
  const name = option.hint ? `${option.label} · ${option.hint}` : option.label;
  return `${name} (${option.count.toLocaleString("en-IN")})`;
}

export function fetchFacets(path: string, filters: ExplorerFilters, signal?: AbortSignal): Promise<FacetsResponse> {
  return apiFetch<FacetsResponse>(`/data/filters${buildQuery({ path: path || undefined, ...filterQuery(filters) })}`, { signal });
}

export function fetchPeer(request: { type?: string; q?: string; page?: number; filters: ExplorerFilters }, signal?: AbortSignal): Promise<PeerPage> {
  return apiFetch<PeerPage>(
    `/data/peer${buildQuery({ type: request.type || undefined, q: request.q?.trim() || undefined, page: request.page ?? 1, pageSize: 20, ...filterQuery(request.filters) })}`,
    { signal }
  );
}

export function peerStatusLine(page: PeerPage | null): string | null {
  if (!page) return null;
  if (page.status === "off") return `${page.portalLabel} results are switched off.`;
  if (page.status === "notPermitted") return `Your role can't see ${page.portalLabel} results.`;
  if (page.status === "unavailable") return `${page.portalLabel} can't be reached right now.`;
  if (page.status === "filteredOut") return `No ${page.portalLabel} records share these crafts or artisans.`;
  return null;
}

export const PEER_TYPE_LABELS: Record<string, string> = {
  artisans: "Artisan",
  crafts: "Craft",
  clusters: "Cluster",
  workshops: "Workshop",
  designWorkshops: "Design workshop",
  interviews: "Interview",
  transcripts: "Transcript",
  reports: "Report",
  products: "Product",
  tools: "Tool",
  processes: "Process",
  media: "Media"
};
