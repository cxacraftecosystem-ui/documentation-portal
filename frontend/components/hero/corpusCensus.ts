export type CorpusCounts = {
  artisans: number;
  crafts: number;
  craftCategories?: number;
  products: number;
  processes: number;
  tools: number;
  interviews: number;
  media: number;
  workshops: number;
};

export type CorpusCensus = {
  asOf: string;
  counts: CorpusCounts;
  source: "live" | "snapshot";
  basis?: "approved" | "held";
};

export const CORPUS_CENSUS: CorpusCensus = {
  asOf: "2026-07-27",
  source: "snapshot",
  basis: "held",
  counts: {
    artisans: 16,
    crafts: 455,
    craftCategories: 9,
    products: 18,
    processes: 4,
    tools: 74,
    interviews: 25,
    media: 925,
    workshops: 1
  }
};

const REQUIRED_KEYS: ReadonlyArray<keyof CorpusCounts> = [
  "artisans",
  "crafts",
  "products",
  "processes",
  "tools",
  "interviews",
  "media",
  "workshops"
];

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

type CensusPayload = {
  counted?: unknown;
  stale?: unknown;
  asOfDate?: unknown;
  recordsHeld?: unknown;
  recordsPublished?: unknown;
};

export function parseCensus(payload: unknown): CorpusCensus | null {
  if (typeof payload !== "object" || payload === null) return null;
  const body = payload as CensusPayload;
  if (body.counted !== true) return null;

  const asOf = body.asOfDate;
  if (typeof asOf !== "string" || !ISO_DATE.test(asOf)) return null;

  const published = body.recordsPublished;
  const approved = typeof published === "object" && published !== null;
  const held = approved ? published : body.recordsHeld;
  if (typeof held !== "object" || held === null) return null;
  const source = held as Record<string, unknown>;

  const counts = {} as CorpusCounts;
  for (const key of REQUIRED_KEYS) {
    const value = source[key];
    if (typeof value !== "number" || !Number.isInteger(value) || value < 0) return null;
    counts[key] = value;
  }
  return { asOf, counts, source: "live", basis: approved ? "approved" : "held" };
}

const REVALIDATE_SECONDS = 300;

const FETCH_TIMEOUT_MS = 4000;

export async function fetchCorpusCensus(apiBase: string): Promise<CorpusCensus> {
  const base = apiBase.replace(/\/+$/, "");
  if (!base) return CORPUS_CENSUS;
  try {
    const response = await fetch(`${base}/api/public/census`, {
      next: { revalidate: REVALIDATE_SECONDS },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      headers: { accept: "application/json" }
    });
    if (!response.ok) return CORPUS_CENSUS;
    return parseCensus(await response.json()) ?? CORPUS_CENSUS;
  } catch {
    return CORPUS_CENSUS;
  }
}

const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December"
];

export function formatCensusDate(iso: string): string {
  const [year, month, day] = iso.split("-");
  const monthName = MONTHS[Number(month) - 1];
  if (!monthName || !year || !day) return iso;
  return `${Number(day)} ${monthName} ${year}`;
}

export const CENSUS_ROWS: ReadonlyArray<{ key: keyof CorpusCounts; one: string; many: string }> = [
  { key: "artisans", one: "Artisan", many: "Artisans" },
  { key: "crafts", one: "Craft", many: "Crafts" },
  { key: "products", one: "Product", many: "Products" },
  { key: "processes", one: "Process", many: "Processes" },
  { key: "tools", one: "Tool", many: "Tools" },
  { key: "interviews", one: "Interview", many: "Interviews" },
  { key: "media", one: "Media file", many: "Media files" },
  { key: "workshops", one: "Workshop", many: "Workshops" }
];

export function censusCaption(census: Pick<CorpusCensus, "basis">, asOf: string): string {
  return census.basis === "approved" ? `Approved records, as of ${asOf}.` : `Records held, as of ${asOf}.`;
}
