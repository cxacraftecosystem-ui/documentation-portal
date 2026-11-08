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
  RECORD_TYPE_LABELS,
  STATUS_LABELS,
  TRANSCRIPT_LABELS,
  withDates,
  withPortals,
  withTranscript,
  withValues,
  type ExplorerFilters,
  type Facet,
  type FacetsResponse,
  type FilterParam
} from "@/lib/dataExplorer/filters";

function fallbackOptions(param: FilterParam, selected: string[]): { value: string; label: string }[] {
  const names = param === "mediaTypes" ? MEDIA_TYPE_LABELS : param === "statuses" ? STATUS_LABELS : param === "recordTypes" ? RECORD_TYPE_LABELS : {};
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

const TRANSCRIPT_CHOICES: { value: string; label: string }[] = [{ value: "", label: "Any" }, ...Object.entries(TRANSCRIPT_LABELS).map(([value, label]) => ({ value, label }))];

function TranscriptChoice({ value, facet, onChange }: { value: string; facet: Facet | undefined; onChange: (value: string) => void }) {
  return (
    <div role="group" aria-label="Has transcript" className="flex w-full overflow-hidden rounded-md border border-line-200 bg-card" data-testid="has-transcript">
      {TRANSCRIPT_CHOICES.map((choice) => {
        const on = choice.value === value;
        const count = facet?.options.find((option) => option.value === choice.value)?.count;
        return (
          <button
            key={choice.value || "any"}
            type="button"
            aria-pressed={on}
            onClick={() => {
              if (!on) onChange(choice.value);
            }}
            className={`min-h-11 flex-1 px-3 text-sm font-medium transition-colors ${on ? "bg-purple-700 text-white" : "text-ink-700 hover:bg-purple-50 hover:text-purple-700"}`}
          >
            {choice.label}
            {count !== undefined ? <span className={`ml-1 text-xs ${on ? "text-white/80" : "text-ink-500"}`}>{count.toLocaleString("en-IN")}</span> : null}
          </button>
        );
      })}
    </div>
  );
}

export function FilterBar({
  filters,
  facets,
  loading,
  summary = [],
  onChange,
  onClear
}: {
  filters: ExplorerFilters;
  facets: FacetsResponse | null;
  loading: boolean;
  summary?: string[];
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
    <section aria-label="Filters" className="panel grid gap-3 p-3" data-testid="explorer-filters">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="inline-flex min-h-11 items-center gap-2 rounded-full border border-line-200 bg-card px-3.5 text-sm font-semibold text-ink-900 hover:border-purple-300"
          aria-expanded={open}
          aria-controls="explorer-filter-controls"
          onClick={() => setOpen((value) => !value)}
          data-testid="filters-toggle"
        >
          <SlidersHorizontal className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
          Filters
          {count ? <span className="rounded-full bg-purple-100 px-2 text-xs text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">{count}</span> : null}
        </button>
        {!open && summary.length ? (
          <ul className="flex min-w-0 flex-wrap gap-1.5" aria-label="Active filters">
            {summary.map((line) => (
              <li key={line} className="max-w-full truncate rounded-full border border-line-200 bg-surface-50 px-2.5 py-1 text-xs text-ink-700">
                {line}
              </li>
            ))}
          </ul>
        ) : null}
        {loading ? <span className="text-xs text-ink-500">Updating…</span> : null}
        <span className="flex-1" />
        {count ? (
          <button type="button" className="inline-flex min-h-11 items-center gap-1.5 rounded-full px-3 text-sm font-semibold text-purple-700 hover:bg-purple-50 dark:text-purple-300 dark:hover:bg-purple-950/40" onClick={onClear} data-testid="clear-filters">
            <X className="h-4 w-4" aria-hidden />
            Clear filters
          </button>
        ) : null}
      </div>
      <div id="explorer-filter-controls" className={`grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5 ${open ? "" : "hidden"}`}>
        <div className="grid min-w-0 content-start gap-1" data-facet="portal">
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
          if (param === "hasTranscript")
            return (
              <div key={param} className="grid min-w-0 content-start gap-1" data-facet={facet}>
                <span className="field-label">{label}</span>
                <TranscriptChoice value={filters.values.hasTranscript[0] ?? ""} facet={entry} onChange={(choice) => onChange(withTranscript(filters, choice))} />
              </div>
            );
          const options = optionsOf(entry, param, filters.values[param]);
          return (
            <div key={param} className="grid min-w-0 content-start gap-1" data-facet={facet}>
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
        <div className="grid min-w-0 content-start gap-1" data-facet="date">
          <CalendarField
            label="From"
            value={filters.dateFrom}
            min={bounds.min ?? undefined}
            max={filters.dateTo || bounds.max || undefined}
            onChange={(iso) => onChange(withDates(filters, iso, filters.dateTo))}
          />
        </div>
        <div className="grid min-w-0 content-start gap-1" data-facet="date-to">
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
