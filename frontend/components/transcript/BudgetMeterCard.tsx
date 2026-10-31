"use client";

import { useEffect, useState } from "react";
import { Gauge } from "lucide-react";

import { fetchBudget } from "@/lib/transcript/api";
import type { BudgetMeter } from "@/lib/transcript/contract";
import { usd } from "@/lib/transcript/format";
import { ts } from "@/lib/transcript/strings";

/**
 * This month's transcription spend for this portal (design §4.7): an alert at 80%, a pause at 100%.
 * Mounted in each portal's admin hub; the route is admin-only server-side and the card draws nothing
 * when it is refused or the pipeline is off.
 */
export function BudgetMeterCard() {
  const [meter, setMeter] = useState<BudgetMeter | null>(null);
  useEffect(() => {
    let cancelled = false;
    fetchBudget()
      .then((loaded) => {
        if (!cancelled) setMeter(loaded);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  if (!meter) return null;
  const percent = Math.max(0, Math.min(100, Math.round(meter.percent ?? 0)));
  const cap = meter.monthlyUsd;

  return (
    <section className="panel grid gap-3 p-4" aria-label={ts("budgetTitle")} data-budget-meter={meter.state}>
      <h2 className="flex items-center gap-2 font-display text-base font-bold text-ink-900">
        <Gauge className="h-5 w-5 text-purple-700 dark:text-purple-300" aria-hidden />
        {ts("budgetTitle")}
      </h2>
      <p className="text-sm font-semibold tabular-nums text-ink-900">
        {cap !== null ? ts("budgetSpent", { spent: `$${usd(meter.spentUsd)}`, cap: `$${usd(cap)}` }) : `$${usd(meter.spentUsd)}`}
      </p>
      {cap !== null ? (
        <div
          role="meter"
          aria-label={ts("budgetTitle")}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent}
          aria-valuetext={`${percent}%`}
          className="progress-track h-2"
        >
          <div className="progress-fill h-full" style={{ width: `${percent}%` }} />
        </div>
      ) : null}
      {meter.state === "PAUSED" ? (
        <p className="rounded-md bg-error-100 px-3 py-2 text-sm text-error-600">{ts("budgetPaused")}</p>
      ) : meter.state === "ALERT" ? (
        <p className="rounded-md bg-amber-100 px-3 py-2 text-sm text-amber-800">{ts("budgetAlert")}</p>
      ) : null}
    </section>
  );
}
