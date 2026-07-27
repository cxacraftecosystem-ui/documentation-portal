/**
 * The pilot census — what the repository actually holds, and the date it was counted.
 *
 * The ledger reads LIVE from `GET /api/public/census`, fetched on the server in `app/page.tsx`,
 * with the dated snapshot below as the fallback. Both halves matter and neither is decorative.
 *
 * WHY THE SNAPSHOT SURVIVES THE ARRIVAL OF THE ENDPOINT
 * ----------------------------------------------------
 * The endpoint is not deployed at the time of writing — the live API answers 404 for it — and this
 * exact "the backend agent shipped it, the deploy had not run yet" gap has taken this project down
 * once already. A landing page whose first paint depends on a route that might not exist is a page
 * that renders a spinner, or worse a row of zeroes, to a stranger. A zero is not a missing value;
 * read as a fact it says the collection is empty.
 *
 * So the failure mode is a *dated* one. If the census cannot be fetched, cannot be parsed, or
 * reports that it could not count, the page renders the snapshot and prints the snapshot's date.
 * The reader is never shown a number without the date it belongs to, and the page is never blocked
 * on the API being up.
 *
 * WHAT THESE NUMBERS ARE
 * ----------------------
 * Records HELD, not records approved. The distinction matters: these are the whole corpus, and any
 * endpoint that filters `status = APPROVED` returns SMALLER numbers. Labelling a holdings figure
 * "approved" would guarantee the page's numbers drop the day it went live. Nothing here may say
 * "approved" about these figures unless it is actually querying approvals — the backend route
 * carries the matching warning on its own `recordsHeld` field.
 */

export type CorpusCounts = {
  artisans: number;
  crafts: number;
  products: number;
  processes: number;
  tools: number;
  interviews: number;
  media: number;
  workshops: number;
};

export type CorpusCensus = {
  /** ISO date the corpus was counted. Rendered visibly; never omitted. */
  asOf: string;
  counts: CorpusCounts;
  /** Where the numbers came from. Drives nothing visual; it is here so a bug is diagnosable. */
  source: "live" | "snapshot";
};

/** The fallback. Counted by hand against the live database on the date stated. */
export const CORPUS_CENSUS: CorpusCensus = {
  asOf: "2026-07-27",
  source: "snapshot",
  counts: {
    artisans: 16,
    crafts: 9,
    products: 18,
    processes: 4,
    tools: 74,
    interviews: 25,
    media: 925,
    workshops: 1
  }
};

/** Keys the wire payload must carry in full. A partial census renders "undefined" in a ledger. */
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

/**
 * The wire shape of `GET /api/public/census`, as the backend actually sends it.
 *
 * `counted` is the discriminator: an empty corpus and an unreachable database both produce "no
 * numbers", and only one of them is a fact about the collection. When `counted` is false the
 * counts and the date are both null, which is why neither can be trusted without checking it.
 */
type CensusPayload = {
  counted?: unknown;
  stale?: unknown;
  asOfDate?: unknown;
  recordsHeld?: unknown;
};

/**
 * Coerce a payload into a census, or return null and let the caller fall back.
 *
 * Deliberately strict. This value is rendered to the public as a factual claim about the size of a
 * research collection, so anything less than a complete set of non-negative integers with a date
 * attached is treated as no answer at all. Half a census is not better than the snapshot; it is a
 * wrong number with a fresh date on it, which is the one outcome worse than being slightly stale.
 */
export function parseCensus(payload: unknown): CorpusCensus | null {
  if (typeof payload !== "object" || payload === null) return null;
  const body = payload as CensusPayload;
  if (body.counted !== true) return null;

  const asOf = body.asOfDate;
  if (typeof asOf !== "string" || !ISO_DATE.test(asOf)) return null;

  const held = body.recordsHeld;
  if (typeof held !== "object" || held === null) return null;
  const source = held as Record<string, unknown>;

  const counts = {} as CorpusCounts;
  for (const key of REQUIRED_KEYS) {
    const value = source[key];
    if (typeof value !== "number" || !Number.isInteger(value) || value < 0) return null;
    counts[key] = value;
  }
  return { asOf, counts, source: "live" };
}

/**
 * How long a fetched census may be reused before the next request re-fetches it.
 *
 * Matched to the backend's own five-minute cache: revalidating faster would only add round trips
 * to a value that cannot have changed. This keeps `app/page.tsx` statically prerendered — the
 * route is built once and refreshed in the background, so a reader never waits on the API and a
 * cold API never delays a paint.
 */
const REVALIDATE_SECONDS = 300;

/** A slow API must not hold the render open; the snapshot is right there. */
const FETCH_TIMEOUT_MS = 4000;

/**
 * The live census, or the snapshot.
 *
 * Server-only: called from a server component, so there is no CORS story, no token in the browser
 * and no client fetch. It never throws and never rejects — every failure path returns
 * {@link CORPUS_CENSUS}, because the one thing this function must not do is take the landing page
 * down over a decorative count.
 */
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
    // Endpoint not deployed yet, DNS down, timeout, malformed JSON — all the same answer.
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

/**
 * "2026-07-27" -> "27 July 2026".
 *
 * Formatted by hand rather than with `toLocaleDateString`, which resolves against the runtime's
 * locale and time zone: the server and the browser would disagree and the date would flicker or
 * fail hydration. This is a pure string transform, so both render the same characters.
 */
export function formatCensusDate(iso: string): string {
  const [year, month, day] = iso.split("-");
  const monthName = MONTHS[Number(month) - 1];
  if (!monthName || !year || !day) return iso;
  return `${Number(day)} ${monthName} ${year}`;
}

/**
 * The ledger, in reading order. Singular and plural are both carried so "1 Workshop" does not read
 * as "1 Workshops" — the corpus is a pilot and the singular is the whole point of the line.
 */
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
