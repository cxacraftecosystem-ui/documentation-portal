/**
 * One set of filters for the sessions list, the forms library, a form's sessions, the recordings
 * list and the media list (owner, 31 Oct 2026; `backend/app/questionnaire/filters.py`).
 *
 * The same query string is the page's URL and the API's: `filtersToQuery` writes the keys in the
 * order of `FILTER_KEYS`, each repeatable list comma-joined, encoded as `encodeURIComponent` does —
 * byte for byte what `shared/questionnaire-chips.json`'s `queryCases` say, and what Android writes.
 */

import type { ChipFilter } from "@/lib/questionnaire/chips";

export const MULTI_KEYS = [
  "questionnaireId",
  "sectionId",
  "questionLineageId",
  "artisanId",
  "researcherId",
  "workshopId",
  "designWorkshopId",
  "kind",
  "source",
  "transcript",
  "languageCode"
] as const;

export type MultiKey = (typeof MULTI_KEYS)[number];

export const FILTER_KEYS = [
  "q",
  "questionnaireId",
  "sectionId",
  "questionLineageId",
  "artisanId",
  "researcherId",
  "workshopId",
  "designWorkshopId",
  "dateFrom",
  "dateTo",
  "kind",
  "source",
  "transcript",
  "languageCode"
] as const;

export type FilterKey = (typeof FILTER_KEYS)[number];

export type Filters = Partial<Record<MultiKey, string[]>> & { q?: string; dateFrom?: string; dateTo?: string };

export const KIND_VALUES = ["WORKSHOP_INTERVIEW", "MARKET_SURVEY", "NONE"] as const;
export const SOURCE_VALUES = ["APP", "UPLOAD", "HANDOFF"] as const;
export const TRANSCRIPT_VALUES = ["HAS", "NONE", "PENDING", "FAILED"] as const;

const isMulti = (key: string): key is MultiKey => (MULTI_KEYS as readonly string[]).includes(key);

/** The filters with every empty value dropped, so two spellings of "nothing" are one. */
export function cleanFilters(f: Filters): Filters {
  const out: Filters = {};
  for (const key of FILTER_KEYS) {
    const value = f[key];
    if (Array.isArray(value)) {
      const list = value.filter((v) => v !== "");
      if (list.length) (out as Record<string, string[]>)[key] = list;
    } else if (typeof value === "string" && value.trim() !== "") {
      (out as Record<string, string>)[key] = value;
    }
  }
  return out;
}

/** Filters read from a URL (or API) query. Unknown keys are ignored. */
export function filtersFromQuery(params: URLSearchParams | string): Filters {
  const search = typeof params === "string" ? new URLSearchParams(params.replace(/^\?/, "")) : params;
  const out: Filters = {};
  for (const key of FILTER_KEYS) {
    const raw = search.getAll(key);
    if (!raw.length) continue;
    if (isMulti(key)) {
      const values: string[] = [];
      for (const part of raw.join(",").split(",")) {
        const v = part.trim();
        if (v && !values.includes(v)) values.push(v);
      }
      if (values.length) out[key] = values;
    } else {
      const v = raw[raw.length - 1].trim();
      if (v) (out as Record<string, string>)[key] = v;
    }
  }
  return out;
}

/** The canonical query (no leading "?"): `""` when nothing is set. */
export function filtersToQuery(f: Filters): string {
  const clean = cleanFilters(f);
  const parts: string[] = [];
  for (const key of FILTER_KEYS) {
    const value = clean[key];
    if (Array.isArray(value)) parts.push(`${key}=${value.map(encodeURIComponent).join(",")}`);
    else if (typeof value === "string") parts.push(`${key}=${encodeURIComponent(value)}`);
  }
  return parts.join("&");
}

/** Whether a chip's filter is already applied (the chip then reads as active). */
export function isChipActive(f: Filters, chip: ChipFilter | null): boolean {
  if (!chip) return false;
  if (chip.key === "date") return f.dateFrom === chip.value && f.dateTo === chip.value;
  if (isMulti(chip.key)) return (f[chip.key] ?? []).includes(chip.value);
  return false;
}

/** Tapping a chip: a repeatable key gains the value or loses it; a day sets both ends or clears them. */
export function applyChip(f: Filters, chip: ChipFilter): Filters {
  const next: Filters = { ...f };
  if (chip.key === "date") {
    if (isChipActive(f, chip)) {
      delete next.dateFrom;
      delete next.dateTo;
    } else {
      next.dateFrom = chip.value;
      next.dateTo = chip.value;
    }
    return cleanFilters(next);
  }
  if (isMulti(chip.key)) {
    const list = f[chip.key] ?? [];
    next[chip.key] = list.includes(chip.value) ? list.filter((v) => v !== chip.value) : [...list, chip.value];
  }
  return cleanFilters(next);
}

/** How many filters are set, not counting the free text (the "Filters (n)" control). */
export function activeCount(f: Filters): number {
  const clean = cleanFilters(f);
  let n = 0;
  for (const key of MULTI_KEYS) n += clean[key]?.length ?? 0;
  if (clean.dateFrom || clean.dateTo) n += 1;
  return n;
}

/** The filters with `keys` removed (a screen that fixes one, e.g. a form's own sessions). */
export function withoutKeys(f: Filters, keys: readonly FilterKey[]): Filters {
  const next = { ...f } as Record<string, unknown>;
  for (const key of keys) delete next[key];
  return cleanFilters(next as Filters);
}

/** The filters as flat query parameters (repeatable lists comma-joined), for `buildQuery` callers. */
export function filtersAsParams(f: Filters): Record<string, string> {
  const clean = cleanFilters(f);
  const out: Record<string, string> = {};
  for (const key of FILTER_KEYS) {
    const value = clean[key];
    if (Array.isArray(value)) out[key] = value.join(",");
    else if (typeof value === "string") out[key] = value;
  }
  return out;
}
