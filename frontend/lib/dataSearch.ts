import { recordViewHref } from "@/lib/recordView";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";
import type { Portal, UnifiedItem, UnifiedResponse, UnifiedType } from "@/lib/unifiedSearch";
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

export type LegacyResult = { artisans?: Artisan[]; workshops?: Workshop[]; products?: ProductDocumentation[]; tools?: ToolDocumentation[]; media?: MediaFile[] };

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

