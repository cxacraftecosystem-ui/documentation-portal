"use client";

/**
 * The filter controls behind BOTH repository searches: the Search page and the search panel at the
 * top of View Data.
 *
 * They were drifting apart — the Search page had type chips and a place box, the View Data panel
 * had a text field and nothing else — which meant the same question got two different answers
 * depending on which screen a researcher happened to be standing on. The vocabulary (what a "type"
 * is, what "Last 30 days" resolves to, how the filters become a query string) lives here once, so
 * the two screens cannot disagree again.
 */

import { useState } from "react";
import { SlidersHorizontal } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";

/** The five buckets `GET /search` returns, in the order the results are rendered. */
export const RECORD_TYPES = ["artisans", "workshops", "products", "tools", "media"] as const;
export type RecordType = (typeof RECORD_TYPES)[number];

/** Chip vocabulary: the five buckets plus "all". Also the `?type=` values the dashboard links use. */
export const CHIP_IDS = ["all", ...RECORD_TYPES] as const;
export type ChipId = (typeof CHIP_IDS)[number];

export const TYPE_LABEL: Record<ChipId, string> = {
  all: "Everything",
  artisans: "Artisans",
  workshops: "Workshops",
  products: "Products",
  tools: "Tools",
  media: "Media"
};

export const RANGE_IDS = ["any", "today", "7d", "30d", "90d", "month", "year", "custom"] as const;
export type RangeId = (typeof RANGE_IDS)[number];

const RANGE_OPTIONS: Array<{ value: RangeId; label: string }> = [
  { value: "any", label: "Any time" },
  { value: "today", label: "Today" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
  { value: "90d", label: "Last 90 days" },
  { value: "month", label: "This month" },
  { value: "year", label: "This year" },
  { value: "custom", label: "Custom range" }
];

export type SearchFilters = {
  /**
   * The record types to search. EMPTY MEANS EVERYTHING — the set never lists all five explicitly,
   * so "nothing ticked" and "everything ticked" cannot both exist and mean the same thing.
   */
  types: RecordType[];
  place: string;
  range: RangeId;
  /** `yyyy-mm-dd` from the two date inputs; only read when `range` is "custom". */
  from: string;
  to: string;
};

export const EMPTY_SEARCH_FILTERS: SearchFilters = { types: [], place: "", range: "any", from: "", to: "" };

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function endOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999);
}

function daysBefore(date: Date, days: number) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() - days);
}

/**
 * `yyyy-mm-dd` as a LOCAL date. `new Date("2026-07-20")` is parsed as UTC midnight, which is the
 * previous day for anyone west of Greenwich — the one bug every date filter is born with.
 */
function parseDateInput(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Presets become concrete instants HERE, not on the server: "Last 30 days" means 30 days as the
 * researcher's clock sees them, and only the browser knows that clock. The boundaries are built in
 * local time and serialised as UTC instants, so an inclusive end date really does include the whole
 * of that day instead of stopping at its midnight.
 */
export function resolveRange(filters: SearchFilters, now: Date = new Date()): { dateFrom?: string; dateTo?: string } {
  const untilNow = { dateTo: endOfDay(now).toISOString() };
  switch (filters.range) {
    case "today":
      return { dateFrom: startOfDay(now).toISOString(), ...untilNow };
    case "7d":
      return { dateFrom: daysBefore(now, 6).toISOString(), ...untilNow };
    case "30d":
      return { dateFrom: daysBefore(now, 29).toISOString(), ...untilNow };
    case "90d":
      return { dateFrom: daysBefore(now, 89).toISOString(), ...untilNow };
    case "month":
      return { dateFrom: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(), ...untilNow };
    case "year":
      return { dateFrom: new Date(now.getFullYear(), 0, 1).toISOString(), ...untilNow };
    case "custom": {
      const from = parseDateInput(filters.from);
      const to = parseDateInput(filters.to);
      return {
        dateFrom: from ? startOfDay(from).toISOString() : undefined,
        dateTo: to ? endOfDay(to).toISOString() : undefined
      };
    }
    default:
      return {};
  }
}

/**
 * The filters as query keys for `GET /search`, to be spread into one `buildQuery` call alongside
 * `q` and the pager — every active filter therefore ANDs into a single request rather than being
 * applied in passes.
 *
 * `types` is sent comma-joined and is the one key the API may not know yet. That is survivable
 * because it is an optimisation, not the filter itself: both screens also drop the buckets they
 * were not asked for when they render, so an API that ignores `types` still shows the right rows —
 * it just counts and pages the hidden buckets too.
 */
export function searchFilterParams(filters: SearchFilters) {
  return {
    place: filters.place.trim() || undefined,
    types: typeList(filters),
    ...resolveRange(filters)
  };
}

/**
 * The selected types as one canonical `types=` value. Always in bucket order and de-duplicated, so
 * the same three types cannot produce two different query strings — and therefore two cache entries
 * and two "why did that reload?" — depending on which control the researcher ticked first.
 */
function typeList(filters: SearchFilters): string | undefined {
  const ordered = RECORD_TYPES.filter((type) => filters.types.includes(type));
  return ordered.length ? ordered.join(",") : undefined;
}

/** Whether a bucket survives the type filter — the client half of the `types` contract. */
export function typeVisible(filters: SearchFilters, type: RecordType) {
  return filters.types.length === 0 || filters.types.includes(type);
}

/**
 * How many filters the disclosure is hiding. Types are deliberately NOT counted: the chip row shows
 * them whether the panel is open or shut, and a badge that counts something already on screen reads
 * as a second, disagreeing filter.
 */
export function activeFilterCount(filters: SearchFilters) {
  return (filters.place.trim() ? 1 : 0) + (filters.range !== "any" ? 1 : 0);
}

/** True when anything at all is narrowing the search — used to decide whether a bare filter searches. */
export function hasActiveFilters(filters: SearchFilters) {
  return filters.types.length > 0 || activeFilterCount(filters) > 0;
}

/**
 * Filters carried in a URL. `type` keeps the singular name and the single-value form the dashboard
 * totals already link with (`/search?type=tools`), and simply accepts a comma list as well, so every
 * link that exists today still resolves.
 */
export function filtersFromSearchParams(params: URLSearchParams): SearchFilters {
  const requested = (params.get("type") ?? "")
    .split(",")
    .map((value) => value.trim().toLowerCase())
    .filter((value): value is RecordType => (RECORD_TYPES as readonly string[]).includes(value));
  const fromParam = params.get("from") ?? "";
  const toParam = params.get("to") ?? "";
  const from = parseDateInput(fromParam) ? fromParam : "";
  const to = parseDateInput(toParam) ? toParam : "";
  const rangeParam = (params.get("range") ?? "").trim() as RangeId;
  const range: RangeId = (RANGE_IDS as readonly string[]).includes(rangeParam)
    ? rangeParam
    : from || to
      ? "custom"
      : "any";
  return {
    types: RECORD_TYPES.filter((type) => requested.includes(type)),
    place: params.get("place") ?? "",
    range,
    from,
    to
  };
}

/** The same filters as `/search` link params, so a hit found in View Data opens fully filtered. */
export function filtersToLinkParams(filters: SearchFilters) {
  return {
    type: typeList(filters),
    place: filters.place.trim() || undefined,
    range: filters.range !== "any" ? filters.range : undefined,
    from: filters.range === "custom" ? filters.from || undefined : undefined,
    to: filters.range === "custom" ? filters.to || undefined : undefined
  };
}

const CHIP_BASE = "rounded-full border px-3 py-1 text-xs font-medium transition-colors";
const CHIP_ON = "border-purple-700 bg-purple-700 text-white";
const CHIP_PART = "border-purple-300 bg-purple-50 text-purple-700 hover:border-purple-400";
const CHIP_OFF = "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700";

/**
 * Chips, a disclosure, and the advanced panel behind it.
 *
 * THE CHIPS AND THE MULTI-SELECT ARE ONE PIECE OF STATE, not two. `filters.types` is the only store
 * of which types are being searched; the chip row and the checkbox list are two editors of that same
 * set, which is why they can never fall out of step:
 *
 *   - a chip is the shortcut for "only this" — clicking one REPLACES the set with that single type,
 *     and "Everything" empties it;
 *   - a checkbox adds or removes one member and leaves the rest alone.
 *
 * The chip row keeps saying what the set is even when the set is something chips alone cannot
 * express: with two or more types selected no chip is the solid "this is the filter" purple, the
 * members are drawn in the lighter included style instead, and a line of text says how many types
 * are in play. So the state is always legible from the chips, and clicking one is always the same
 * promise — "narrow to just this".
 */
export function SearchFilterBar({
  value,
  onChange,
  className = "mb-4",
  showPlace = true
}: {
  value: SearchFilters;
  onChange: (next: SearchFilters) => void;
  className?: string;
  /** False where the screen already has its own place box, so the field is not asked for twice. */
  showPlace?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const hidden = activeFilterCount(value);
  const multiple = value.types.length > 1;

  function chipClass(id: ChipId) {
    if (id === "all") return value.types.length === 0 ? CHIP_ON : CHIP_OFF;
    if (value.types.length === 1 && value.types[0] === id) return CHIP_ON;
    return value.types.includes(id as RecordType) ? CHIP_PART : CHIP_OFF;
  }

  function pickChip(id: ChipId) {
    onChange({ ...value, types: id === "all" ? [] : [id as RecordType] });
  }

  function toggleType(type: RecordType) {
    const next = value.types.includes(type)
      ? value.types.filter((current) => current !== type)
      : [...value.types, type];
    // Held in bucket order so the state reads the same as the row of chips above it, whatever order
    // the ticks went in. `typeList` re-derives it anyway; this keeps what is stored honest too.
    onChange({ ...value, types: RECORD_TYPES.filter((current) => next.includes(current)) });
  }

  return (
    <div className={className}>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter results by record type">
        {CHIP_IDS.map((id) => (
          <button
            key={id}
            type="button"
            aria-pressed={id === "all" ? value.types.length === 0 : value.types.includes(id as RecordType)}
            onClick={() => pickChip(id)}
            className={`${CHIP_BASE} ${chipClass(id)}`}
          >
            {TYPE_LABEL[id]}
          </button>
        ))}
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
          className={`inline-flex items-center gap-1.5 ${CHIP_BASE} ${open || hidden ? CHIP_PART : CHIP_OFF}`}
        >
          <SlidersHorizontal className="h-3.5 w-3.5" aria-hidden />
          {hidden ? `Filters · ${hidden}` : "Filters"}
        </button>
      </div>

      {multiple ? (
        <p className="mt-1.5 text-xs text-ink-500">
          Searching {value.types.length} record types. A chip narrows to just that one; tick more under Filters.
        </p>
      ) : null}

      {open ? (
        <div className="mt-2 grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
          <div className="grid gap-3 sm:grid-cols-2">
            {showPlace ? (
              <label className="grid gap-1">
                <span className="field-label">Place</span>
                <input
                  className="field-input"
                  value={value.place}
                  placeholder="Any place"
                  onChange={(event) => onChange({ ...value, place: event.target.value })}
                />
              </label>
            ) : null}
            <div className="grid gap-1">
              <span className="field-label">Record time</span>
              <Dropdown
                ariaLabel="Filter by when the record was made"
                value={value.range}
                onChange={(range) => onChange({ ...value, range: range as RangeId })}
                advanceOnSelect={false}
                options={RANGE_OPTIONS}
              />
            </div>
          </div>

          {value.range === "custom" ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="grid gap-1">
                <span className="field-label">From</span>
                <input
                  type="date"
                  className="field-input"
                  value={value.from}
                  max={value.to || undefined}
                  onChange={(event) => onChange({ ...value, from: event.target.value })}
                />
              </label>
              <label className="grid gap-1">
                <span className="field-label">To</span>
                <input
                  type="date"
                  className="field-input"
                  value={value.to}
                  min={value.from || undefined}
                  onChange={(event) => onChange({ ...value, to: event.target.value })}
                />
              </label>
            </div>
          ) : null}

          <fieldset className="grid gap-1.5">
            <legend className="field-label">Record types</legend>
            <p className="text-xs text-ink-500">
              The same setting as the chips above. Tick any number; nothing ticked searches everything.
            </p>
            <div className="flex flex-wrap gap-x-4 gap-y-1.5">
              {RECORD_TYPES.map((type) => (
                <label key={type} className="flex items-center gap-2 text-sm text-ink-700">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-purple-700"
                    checked={value.types.includes(type)}
                    onChange={() => toggleType(type)}
                  />
                  {TYPE_LABEL[type]}
                </label>
              ))}
            </div>
          </fieldset>

          {hasActiveFilters(value) ? (
            <div>
              <button
                type="button"
                className="text-xs font-semibold text-purple-700 hover:underline"
                onClick={() => onChange(EMPTY_SEARCH_FILTERS)}
              >
                Clear all filters
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
