"use client";

import { COMPLETENESS_FILTERS, type CompletenessFilter as Value } from "@/lib/completenessCore";

/**
 * All · Incomplete · Missing required — a choice between three named views of one list, so a
 * segmented `aria-pressed` group (§11.11 leaves mode buttons alone), sent as `completeness=`.
 */
export function CompletenessFilter({ value, onChange }: { value: Value; onChange: (next: Value) => void }) {
  return (
    <div role="group" aria-label="Completeness" className="inline-flex rounded-full border border-line-200 bg-card p-0.5" data-completeness-filter>
      {COMPLETENESS_FILTERS.map((option) => {
        const active = option.value === value;
        return (
          <button
            key={option.value || "all"}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(option.value)}
            className={`min-h-9 rounded-full px-3 text-xs font-semibold transition max-sm:min-h-11 ${
              active ? "bg-purple-700 text-white" : "text-ink-700 hover:bg-purple-50"
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
