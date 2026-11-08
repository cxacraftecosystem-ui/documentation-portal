"use client";

import { SlidersHorizontal, X } from "lucide-react";
import { useState } from "react";

import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import {
  activeCount,
  FILTER_PARAMS,
  MEDIA_TYPE_LABELS,
  optionLabel,
  STATUS_LABELS,
  withDates,
  withPortals,
  withValues,
  type ExplorerFilters,
  type Facet,
  type FacetsResponse,
  type FilterParam
} from "@/lib/dataExplorer/filters";

function fallbackOptions(param: FilterParam, selected: string[]): { value: string; label: string }[] {
  const names = param === "mediaTypes" ? MEDIA_TYPE_LABELS : param === "statuses" ? STATUS_LABELS : {};
  const base = Object.entries(names).map(([value, label]) => ({ value, label }));
  const extra = selected.filter((value) => !base.some((option) => option.value === value)).map((value) => ({ value, label: value }));
  return [...base, ...extra];
}

function optionsOf(facet: Facet | undefined, param: FilterParam, selected: string[]): { value: string; label: string }[] {
  if (!facet) return fallbackOptions(param, selected);
  const listed = facet.options.map((option) => ({ value: option.value, label: optionLabel(option) }));
  const missing = selected.filter((value) => !listed.some((option) => option.value === value)).map((value) => ({ value, label: value }));
  return [...listed, ...missing];
}

export function FilterBar({
  filters,
  facets,
  loading,
  onChange,
  onClear
}: {
  filters: ExplorerFilters;
  facets: FacetsResponse | null;
  loading: boolean;
  onChange: (next: ExplorerFilters) => void;
  onClear: () => void;
}) {
  const [open, setOpen] = useState(false);
  const count = activeCount(filters) + (filters.portals.length === 1 && filters.portals[0] === "this" ? 0 : 1);
  const portalOptions = (facets?.portal.options ?? [
    { value: "this", label: "This portal", available: true, status: "ok" },
    { value: "other", label: "Other portal", available: false, status: "off" }
  ]).map((option) => ({ value: option.value, label: option.label, disabled: option.value === "other" && !option.available && !filters.portals.includes("other") }));
  const shown = FILTER_PARAMS.filter(({ facet, param }) => {
    if (facet !== "designWorkshop" && facet !== "language") return true;
    if (filters.values[param].length) return true;
    return Boolean(facets?.facets.some((entry) => entry.facet === facet));
  });
  const bounds = facets?.dateBounds ?? { min: null, max: null };

  return (
    <section aria-label="Filters" className="panel grid gap-3 p-3 sm:p-4" data-testid="explorer-filters">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="inline-flex items-center gap-2 text-sm font-semibold text-ink-900 max-lg:hidden">
          <SlidersHorizontal className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
          Filters
          {count ? <span className="rounded-full bg-purple-100 px-2 text-xs text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">{count}</span> : null}
        </h2>
        <button
          type="button"
          className="inline-flex min-h-11 items-center gap-2 rounded-full border border-line-200 bg-card px-3.5 text-sm font-semibold text-ink-900 hover:border-purple-300 lg:hidden"
          aria-expanded={open}
          aria-controls="explorer-filter-controls"
          onClick={() => setOpen((value) => !value)}
          data-testid="filters-toggle"
        >
          <SlidersHorizontal className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
          Filters
          {count ? <span className="rounded-full bg-purple-100 px-2 text-xs text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">{count}</span> : null}
        </button>
        {loading ? <span className="text-xs text-ink-500">Updating…</span> : null}
        <span className="flex-1" />
        {count ? (
          <button type="button" className="inline-flex min-h-11 items-center gap-1.5 rounded-full px-3 text-sm font-semibold text-purple-700 hover:bg-purple-50 dark:text-purple-300 dark:hover:bg-purple-950/40" onClick={onClear} data-testid="clear-filters">
            <X className="h-4 w-4" aria-hidden />
            Clear filters
          </button>
        ) : null}
      </div>
      <div id="explorer-filter-controls" className={`grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid lg:grid-cols-4 xl:grid-cols-5 ${open ? "" : "max-lg:hidden"}`}>
        <div className="grid min-w-0 gap-1" data-facet="portal">
          <span className="field-label">Portal</span>
          <SearchableMultiSelect
            values={filters.portals}
            onChange={(values) => onChange(withPortals(filters, values))}
            options={portalOptions}
            placeholder="This portal"
            ariaLabel="Portal"
            bulk={false}
            searchable
          />
        </div>
        {shown.map(({ param, facet, label }) => {
          const entry = facets?.facets.find((candidate) => candidate.facet === facet);
          const options = optionsOf(entry, param, filters.values[param]);
          return (
            <div key={param} className="grid min-w-0 gap-1" data-facet={facet}>
              <span className="field-label">{label}</span>
              <SearchableMultiSelect
                values={filters.values[param]}
                onChange={(values) => onChange(withValues(filters, param, values))}
                options={options}
                placeholder={`Any ${label.toLowerCase()}`}
                emptyLabel={loading && !entry ? "Loading…" : "Nothing to pick"}
                ariaLabel={label}
                bulk={false}
                searchable
              />
              {entry?.truncated ? <span className="text-xs text-ink-500">The busiest {entry.options.length} are listed.</span> : null}
            </div>
          );
        })}
        <div className="grid min-w-0 gap-1" data-facet="date">
          <CalendarField
            label="From"
            value={filters.dateFrom}
            min={bounds.min ?? undefined}
            max={filters.dateTo || bounds.max || undefined}
            onChange={(iso) => onChange(withDates(filters, iso, filters.dateTo))}
          />
        </div>
        <div className="grid min-w-0 gap-1" data-facet="date-to">
          <CalendarField
            label="To"
            value={filters.dateTo}
            min={filters.dateFrom || bounds.min || undefined}
            max={bounds.max ?? undefined}
            onChange={(iso) => onChange(withDates(filters, filters.dateFrom, iso))}
          />
        </div>
      </div>
    </section>
  );
}
