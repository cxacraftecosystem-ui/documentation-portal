"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CalendarClock, CheckCircle2, ClipboardList, RefreshCw, ShieldCheck, TriangleAlert } from "lucide-react";

import { EffectiveProgressBar, StatTile } from "@/components/tasks/TaskPrimitives";
import type { TaskSummary } from "@/components/tasks/types";
import { apiFetch, buildQuery } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { cn } from "@/lib/utils";

export type SummaryBar = {
  percent: number | null;
  caption: string;
  measured: "all" | "some" | "none";
  caveats: string[];
};

export function summaryBar(summary: TaskSummary): SummaryBar {
  const active = Math.max(0, summary.taskCount - summary.cancelledCount);
  const caveats: string[] = [];
  if (summary.truncated) {
    caveats.push(
      "Counts may be incomplete — too many tasks."
    );
  }
  if (summary.derivationSkipped) {
    caveats.push(
      "Too many tasks to count automatically."
    );
  }

  if (summary.taskCount === 0) {
    return { percent: null, caption: "No tasks yet.", measured: "none", caveats };
  }
  if (active === 0) {
    return {
      percent: null,
      caption: "All your tasks were withdrawn.",
      measured: "none",
      caveats
    };
  }

  const measured: SummaryBar["measured"] =
    summary.measuredCount === 0 ? "none" : summary.measuredCount >= active ? "all" : "some";

  if (summary.measuredCount === 0 && summary.percentComplete === 0) {
    return {
      percent: null,
      caption:
        "No progress yet — the counts above are exact.",
      measured,
      caveats
    };
  }

  const caption =
    measured === "all"
      ? `All ${active} tasks counted from records.`
      : measured === "some"
        ? `${summary.measuredCount} of ${active} counted from records; the rest self-reported.`
        : "Self-reported.";

  return { percent: summary.percentComplete, caption, measured, caveats };
}

export function dueSentence(summary: TaskSummary): string | null {
  const parts: string[] = [];
  if (summary.overdueCount > 0) {
    parts.push(
      `${summary.overdueCount} ${summary.overdueCount === 1 ? "task is" : "tasks are"} past ${
        summary.overdueCount === 1 ? "its" : "their"
      } due date`
    );
  }
  if (summary.dueSoonCount > 0) {
    parts.push(`${summary.dueSoonCount} due in 2 days`);
  }
  if (summary.nextDueAt) parts.push(`next due ${formatDate(summary.nextDueAt)}`);
  if (!parts.length) return null;
  return `${parts.join(" · ")}.`;
}

export function AssigneeProgressCard({
  workshopId,
  refreshToken,
  className
}: {
  workshopId?: string | null;
  refreshToken?: number;
  className?: string;
}) {
  const [summary, setSummary] = useState<TaskSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const currentLoad = useRef(0);

  const load = useCallback(async () => {
    const generation = ++currentLoad.current;
    setLoading(true);
    try {
      const data = await apiFetch<TaskSummary>(`/tasks/summary${buildQuery({ workshopId: workshopId || undefined })}`);
      if (generation !== currentLoad.current) return;
      setSummary(data);
      setError(null);
    } catch (err) {
      if (generation !== currentLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load your task summary");
    } finally {
      if (generation === currentLoad.current) setLoading(false);
    }
  }, [workshopId]);

  useEffect(() => {
    load();
  }, [load, refreshToken]);

  if (loading && !summary) {
    return (
      <section className={cn("panel mb-4 w-full p-4 text-sm text-ink-500", className)} aria-busy="true">
        Loading your progress...
      </section>
    );
  }

  if (error && !summary) {
    return (
      <section className={cn("panel mb-4 w-full p-4", className)}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-ink-700">
            Couldn’t load your progress.
          </p>
          <button type="button" className="field-button-secondary" onClick={load}>
            <RefreshCw className="h-4 w-4" aria-hidden />
            Try again
          </button>
        </div>
      </section>
    );
  }

  if (!summary || summary.taskCount === 0) return null;

  const bar = summaryBar(summary);
  const due = dueSentence(summary);

  return (
    <section
      aria-labelledby="assignee-progress-heading"
      className={cn("panel mb-4 w-full overflow-hidden", className)}
    >
      <div className="grid gap-4 p-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)] lg:items-start lg:gap-6">
        <div className="grid gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 id="assignee-progress-heading" className="font-display text-base font-bold text-ink-900">
              <ClipboardList className="mr-2 inline h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
              Where you stand
            </h2>
            <button
              type="button"
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-purple-700 dark:text-purple-300"
              onClick={load}
              disabled={loading}
            >
              <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} aria-hidden />
              {loading ? "Refreshing..." : "Refresh"}
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <StatTile label="Remaining" value={summary.remainingCount} hint="still to do" />
            <StatTile label="Under review" value={summary.awaitingReviewCount} hint="waiting on an admin" />
            <StatTile label="Overdue" value={summary.overdueCount} tone="warn" hint="past the due date" />
            <StatTile label="Approved" value={summary.approvedCount} tone="good" hint="signed off" />
          </div>

          {summary.awaitingReviewCount > 0 ? (
            <p className="flex items-start gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-xs leading-5 text-purple-700">
              <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                {summary.awaitingReviewCount === 1
                  ? "1 task awaiting approval."
                  : `${summary.awaitingReviewCount} tasks awaiting approval.`}
              </span>
            </p>
          ) : null}

          {due ? (
            <p
              className={cn(
                "flex items-center gap-1.5 text-xs leading-5",
                summary.overdueCount > 0 ? "font-semibold text-error-600" : "text-ink-500"
              )}
            >
              {summary.overdueCount > 0 ? (
                <TriangleAlert className="h-3.5 w-3.5 shrink-0" aria-hidden />
              ) : (
                <CalendarClock className="h-3.5 w-3.5 shrink-0" aria-hidden />
              )}
              {due}
            </p>
          ) : null}
        </div>

        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          {bar.percent === null ? (
            <>
              <span className="text-xs font-medium text-ink-700">Overall progress</span>
              <p className="text-xs leading-5 text-ink-500">{bar.caption}</p>
            </>
          ) : (
            <>
              <EffectiveProgressBar
                size="lg"
                percent={bar.percent}
                label="Overall progress"
                ariaLabel="Overall progress across your tasks"
                source={bar.measured === "none" ? "reported" : "derived"}
                note={null}
              />
              <p className="text-xs leading-5 text-ink-500">{bar.caption}</p>
            </>
          )}
          {bar.caveats.map((caveat) => (
            <p
              key={caveat}
              className="flex items-start gap-1.5 rounded-md border border-amber-500/30 bg-amber-100 px-2.5 py-1.5 text-xs leading-5 text-amber-800"
            >
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{caveat}</span>
            </p>
          ))}
          {summary.approvedCount > 0 && summary.remainingCount === 0 && summary.awaitingReviewCount === 0 ? (
            <p className="flex items-center gap-1.5 text-xs font-medium leading-5 text-success-600">
              <CheckCircle2 className="h-3.5 w-3.5 shrink-0" aria-hidden />
              Everything assigned to you has been approved.
            </p>
          ) : null}
        </div>
      </div>
    </section>
  );
}
