"use client";

import { useState } from "react";
import { ChevronDown } from "lucide-react";

import type { CoverageGaps } from "@/components/map/coverage/coverageTypes";

/** What is missing: shared crafts nobody has documented, and districts with no workshop, by state. */
export function CoverageGapsPanel({ gaps }: { gaps: CoverageGaps }) {
  const [open, setOpen] = useState<ReadonlySet<number>>(() => new Set());
  const toggle = (lgd: number) =>
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(lgd)) next.delete(lgd);
      else next.add(lgd);
      return next;
    });
  const crafts = gaps.craftsNeverDocumented;
  const districts = gaps.districtsWithoutWorkshop;

  return (
    <section className="panel p-4" aria-labelledby="coverage-gaps-heading" data-testid="coverage-gaps">
      <h2 id="coverage-gaps-heading" className="font-display text-sm font-bold text-ink-900">
        Gaps
      </h2>

      <h3 className="mt-3 text-xs font-semibold text-ink-700">
        Crafts never documented <span className="text-ink-500">· {crafts.length}</span>
      </h3>
      {crafts.length ? (
        <ul className="mt-2 flex flex-wrap gap-1.5">
          {crafts.map((craft) => (
            <li key={craft.code} className="rounded-full border border-line-200 bg-surface-50 px-2.5 py-0.5 text-xs text-ink-700">
              {craft.name}
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-1 text-xs text-ink-500">Every shared craft has a record.</p>
      )}

      <h3 className="mt-4 text-xs font-semibold text-ink-700">
        Districts without a workshop <span className="text-ink-500">· {districts.count}</span>
      </h3>
      <ul className="mt-2 grid gap-1">
        {districts.byState.map((state) => {
          const expanded = open.has(state.stateLgd);
          return (
            <li key={state.stateLgd} className="rounded-md border border-line-200">
              <button
                type="button"
                aria-expanded={expanded}
                onClick={() => toggle(state.stateLgd)}
                className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm text-ink-900"
              >
                <span className="min-w-0 truncate">{state.state}</span>
                <span className="flex shrink-0 items-center gap-2 text-xs text-ink-500">
                  {state.without.length} of {state.total}
                  <ChevronDown className={`h-4 w-4 transition-transform ${expanded ? "rotate-180" : ""}`} aria-hidden />
                </span>
              </button>
              {expanded ? (
                <ul className="flex flex-wrap gap-1.5 border-t border-line-200 px-3 py-2">
                  {state.without.map((district) => (
                    <li key={`${district.lgd}-${district.name}`} className="text-xs text-ink-700">
                      {district.name}
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          );
        })}
      </ul>
      {gaps.statesWithoutWorkshop.length ? (
        <p className="mt-3 text-xs text-ink-500">
          States and UTs with no workshop: {gaps.statesWithoutWorkshop.length}
        </p>
      ) : null}
    </section>
  );
}
