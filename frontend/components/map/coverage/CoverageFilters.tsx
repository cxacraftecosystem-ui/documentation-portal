"use client";

/**
 * The coverage filters: which crafts, which kind of workshop, which financial year, which state,
 * and what the map is coloured by. Every value lives in the URL (`CoverageView`), so a filtered view
 * can be linked. Craft and year are the shared pickers; scheme and metric are short fixed
 * vocabularies, drawn as pills and a segmented control.
 */

import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { METRICS, type RegisterState } from "@/components/map/coverage/coverageMath";
import type {
  CoverageMetric,
  CoverageQuery,
  CoverageResponse
} from "@/components/map/coverage/coverageTypes";

function Segmented<T extends string>({
  label,
  value,
  options,
  onChange
}: {
  label: string;
  value: T;
  options: Array<{ value: T; label: string }>;
  onChange: (value: T) => void;
}) {
  return (
    <div className="grid min-w-0 content-start gap-1.5">
      <span className="field-label">{label}</span>
      <div
        role="group"
        aria-label={label}
        className="inline-flex w-fit max-w-full overflow-hidden rounded-md border border-line-200 bg-card"
      >
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-pressed={value === option.value}
            onClick={() => onChange(option.value)}
            className={`px-3 py-1.5 text-xs font-medium transition-colors ${
              value === option.value
                ? "bg-purple-700 text-white"
                : "text-ink-700 hover:bg-purple-50 hover:text-purple-700"
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

export function CoverageFilters({
  query,
  metric,
  catalogue,
  states,
  onChange,
  onMetric
}: {
  query: CoverageQuery;
  metric: CoverageMetric;
  catalogue: CoverageResponse["catalogue"] | null;
  states: RegisterState[];
  onChange: (next: CoverageQuery) => void;
  onMetric: (metric: CoverageMetric) => void;
}) {
  const crafts = (catalogue?.crafts ?? []).map((craft) => ({ value: craft.code, label: craft.name }));
  const years = [
    { value: "", label: "All years" },
    ...(catalogue?.years ?? []).map((year) => ({ value: String(year.fy), label: `FY ${year.label}` }))
  ];
  const stateOptions = [
    { value: "", label: "All of India" },
    ...states.map((state) => ({ value: String(state.lgd), label: state.name }))
  ];
  const schemes = catalogue?.schemes ?? [];

  const toggleScheme = (value: string) => {
    const next = query.scheme.includes(value)
      ? query.scheme.filter((item) => item !== value)
      : [...query.scheme, value];
    onChange({ ...query, scheme: next });
  };

  return (
    <div className="grid gap-4 rounded-md border border-line-200 bg-surface-50 p-3">
      <div className="grid gap-4 md:grid-cols-3">
        <div className="grid min-w-0 content-start gap-1.5">
          <span className="field-label">Crafts</span>
          <SearchableMultiSelect
            values={query.craftCode}
            options={crafts}
            onChange={(craftCode) => onChange({ ...query, craftCode })}
            placeholder="All crafts"
            ariaLabel="Crafts"
            searchable
            confirmOnSelect={false}
          />
        </div>
        <div className="grid min-w-0 content-start gap-1.5">
          <span className="field-label">Financial year</span>
          <SearchableSelect
            value={query.fy.length === 1 ? String(query.fy[0]) : ""}
            options={years}
            onChange={(value) => onChange({ ...query, fy: value ? [Number(value)] : [] })}
            ariaLabel="Financial year"
            advanceOnSelect={false}
          />
        </div>
        <div className="grid min-w-0 content-start gap-1.5">
          <span className="field-label">State</span>
          <SearchableSelect
            value={query.stateLgd ? String(query.stateLgd) : ""}
            options={stateOptions}
            onChange={(value) => onChange({ ...query, stateLgd: value ? Number(value) : null })}
            ariaLabel="State"
            searchable
            advanceOnSelect={false}
          />
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        {schemes.length ? (
          <div className="grid min-w-0 content-start gap-1.5">
            <span className="field-label">Workshop type</span>
            <div role="group" aria-label="Workshop type" className="flex flex-wrap gap-2">
              {schemes.map((scheme) => {
                const on = query.scheme.includes(scheme.value);
                return (
                  <button
                    key={scheme.value}
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggleScheme(scheme.value)}
                    className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                      on
                        ? "border-purple-700 bg-purple-700 text-white"
                        : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                    }`}
                  >
                    {scheme.label}
                  </button>
                );
              })}
            </div>
          </div>
        ) : null}
        <Segmented
          label="Colour by"
          value={metric}
          options={METRICS}
          onChange={onMetric}
        />
        <Segmented
          label="Detail"
          value={query.level}
          options={[
            { value: "DISTRICT", label: "District" },
            { value: "STATE", label: "State" }
          ]}
          onChange={(level) => onChange({ ...query, level })}
        />
      </div>
    </div>
  );
}
