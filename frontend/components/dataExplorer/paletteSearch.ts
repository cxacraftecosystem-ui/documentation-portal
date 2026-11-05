import { ApiError, apiFetch, buildQuery } from "@/lib/api";

export type PaletteHit = { id: string; title: string; subtitle?: string; type: string; path?: string | null };

const LOCATE_TYPE: Record<string, string> = {
  artisans: "artisan",
  workshops: "workshop",
  products: "product",
  tools: "tool",
  processes: "process",
  crafts: "craft",
  interviews: "interview",
  designWorkshops: "designWorkshop",
  media: "media",
  transcripts: "media"
};

const TYPE_WORD: Record<string, string> = {
  artisans: "Artisan",
  workshops: "Workshop",
  products: "Product",
  tools: "Tool",
  processes: "Process",
  crafts: "Craft",
  interviews: "Interview",
  designWorkshops: "Design workshop",
  media: "File",
  transcripts: "Transcript"
};

export function typeWord(type: string): string {
  return TYPE_WORD[type] ?? type;
}

type UnifiedLike = { groups?: { type: string; items?: { id: string; title: string; subtitle?: string | null; local?: boolean }[] }[] };

type LegacyLike = {
  artisans?: { id: string; name: string; place?: string | null }[];
  workshops?: { id: string; title: string; place?: string | null }[];
  products?: { id: string; productName: string; artisanName?: string | null }[];
  tools?: { id: string; toolkitName: string; artisanName?: string | null }[];
  media?: { id: string; caption?: string | null; originalFilename: string }[];
};

export function unifiedHits(data: UnifiedLike, cap = 8): PaletteHit[] {
  const out: PaletteHit[] = [];
  for (const group of data.groups ?? []) {
    if (!LOCATE_TYPE[group.type]) continue;
    for (const item of group.items ?? []) {
      if (item.local === false) continue;
      out.push({ id: item.id, title: item.title, subtitle: [typeWord(group.type), item.subtitle].filter(Boolean).join(" · "), type: group.type, path: null });
    }
  }
  return out.slice(0, cap);
}

export function legacyHits(data: LegacyLike, cap = 8): PaletteHit[] {
  const out: PaletteHit[] = [
    ...(data.artisans ?? []).map((a) => ({ id: a.id, title: a.name, subtitle: ["Artisan", a.place].filter(Boolean).join(" · "), type: "artisans" })),
    ...(data.workshops ?? []).map((w) => ({ id: w.id, title: w.title, subtitle: ["Workshop", w.place].filter(Boolean).join(" · "), type: "workshops" })),
    ...(data.products ?? []).map((p) => ({ id: p.id, title: p.productName, subtitle: ["Product", p.artisanName].filter(Boolean).join(" · "), type: "products" })),
    ...(data.tools ?? []).map((t) => ({ id: t.id, title: t.toolkitName, subtitle: ["Tool", t.artisanName].filter(Boolean).join(" · "), type: "tools" })),
    ...(data.media ?? []).map((m) => ({ id: m.id, title: m.caption || m.originalFilename, subtitle: "File", type: "media" }))
  ];
  return out.slice(0, cap);
}

export async function fetchLocateHits(q: string, signal?: AbortSignal): Promise<PaletteHit[]> {
  const text = q.trim();
  if (text.length < 2) return [];
  try {
    const data = await apiFetch<UnifiedLike>(`/search/unified${buildQuery({ q: text, scope: "local", limit: 4 })}`, { signal });
    return unifiedHits(data);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 404) throw error;
  }
  const legacy = await apiFetch<LegacyLike>(`/search${buildQuery({ q: text, page: 1, pageSize: 4 })}`, { signal });
  return legacyHits(legacy);
}

export async function locateHit(hit: PaletteHit): Promise<string | null> {
  if (hit.path) return hit.path;
  const type = LOCATE_TYPE[hit.type];
  if (!type) return null;
  const found = await apiFetch<{ path: string | null }>(`/data/locate${buildQuery({ type, id: hit.id })}`);
  return found.path;
}
