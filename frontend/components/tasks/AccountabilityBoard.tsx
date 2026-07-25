"use client";

import { useState } from "react";
import { ChevronDown, TriangleAlert } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import {
  DueBadge,
  PersonLine,
  ProgressGapMeter,
  ScopeChips,
  StatTile,
  TaskKindBadge,
  TaskStatusPill
} from "@/components/tasks/TaskPrimitives";
import type { TaskProgressAssignee, TaskProgressReport } from "@/components/tasks/types";
import { cn } from "@/lib/utils";

/**
 * Who has what, and how far along they ACTUALLY are.
 *
 * Every line carries two numbers: `reportedTotal`, which the assignee typed in, and `derivedTotal`,
 * which is counted from the records that reached the repository. They are never merged and neither
 * one is presented as the truth — the distance between them is the signal this whole view exists to
 * surface, so it is shown as two bars on one scale plus a worded verdict, never as a single
 * "progress" percentage that would quietly average the disagreement away.
 */

function AssigneeRow({ row }: { row: TaskProgressAssignee }) {
  const [open, setOpen] = useState(false);
  const userId = row.user?.id ?? "unknown";
  const panelId = `assignee-tasks-${userId}`;

  return (
    <article className="panel overflow-hidden">
      <div className="grid gap-4 p-4 md:grid-cols-[minmax(0,1fr)_minmax(0,22rem)] md:items-start">
        <div className="grid gap-2">
          <PersonLine user={row.user} className="text-base" />
          <div className="flex flex-wrap items-center gap-1.5 text-xs">
            <span className="rounded-full border border-line-200 bg-surface-50 px-2.5 py-1 font-medium text-ink-700">
              {row.taskCount} task{row.taskCount === 1 ? "" : "s"}
            </span>
            <span className="rounded-full border border-line-200 bg-surface-50 px-2.5 py-1 font-medium text-ink-700">
              {row.openCount} outstanding
            </span>
            <span className="rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 font-medium text-success-600">
              {row.statusCounts.DONE} done
            </span>
            {row.overdueCount > 0 ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 font-medium text-error-600">
                <TriangleAlert className="h-3.5 w-3.5" aria-hidden />
                {row.overdueCount} overdue
              </span>
            ) : null}
          </div>
          <button
            type="button"
            className="inline-flex w-fit items-center gap-1.5 text-xs font-semibold text-purple-700"
            aria-expanded={open}
            aria-controls={panelId}
            onClick={() => setOpen((prev) => !prev)}
          >
            <ChevronDown className={cn("h-4 w-4 transition-transform", open && "rotate-180")} aria-hidden />
            {open ? "Hide" : "Show"} the {row.taskCount} task{row.taskCount === 1 ? "" : "s"}
          </button>
        </div>
        <div className="rounded-md border border-line-200 bg-surface-50 p-3">
          <ProgressGapMeter reported={row.reportedTotal} derived={row.derivedTotal} target={row.targetTotal} />
        </div>
      </div>

      {open ? (
        <div id={panelId} className="grid gap-3 border-t border-line-200 bg-surface-50 p-4">
          {row.tasks.map((task) => (
            <div key={task.id} className="rounded-md border border-line-200 bg-card p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <h4 className="font-display text-sm font-semibold text-ink-900">{task.title}</h4>
                  <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
                    <TaskKindBadge recordTypes={task.recordTypes} sections={task.sections} />
                    <DueBadge dueAt={task.dueAt} overdue={task.isOverdue} />
                  </div>
                </div>
                <TaskStatusPill status={task.status} />
              </div>
              <div className="mt-2">
                <ScopeChips
                  recordTypeLabels={task.recordTypeLabels}
                  sections={task.sections}
                  artisans={task.artisans}
                  targetCount={task.targetCount}
                  workshopTitle={task.workshopTitle}
                />
              </div>
              <div className="mt-3 border-t border-line-200 pt-3">
                <ProgressGapMeter
                  reported={task.progressCount}
                  derived={task.derivedCount}
                  target={task.targetCount ?? task.derivedTarget}
                />
              </div>
            </div>
          ))}
        </div>
      ) : null}
    </article>
  );
}

export function AccountabilityBoard({
  report,
  loading,
  error
}: {
  report: TaskProgressReport | null;
  loading: boolean;
  error: string | null;
}) {
  if (error) {
    return <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>;
  }
  if (loading && !report) return <div className="panel p-4 text-sm text-ink-500">Loading the rollup...</div>;
  if (!report) return null;
  if (!report.assignees.length) {
    return (
      <EmptyState
        title="Nobody has been given work here yet"
        body="Assign work on the first tab and this becomes the accountability view: who has what, what they say they have done, and what the repository can actually find."
      />
    );
  }

  return (
    <div className="grid gap-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
        <StatTile label="People with work" value={report.assigneeCount} />
        <StatTile label="Tasks in scope" value={report.taskCount} />
        <StatTile label="Still outstanding" value={report.openCount} />
        <StatTile label="Finished" value={report.doneCount} tone="good" />
        <StatTile label="Overdue" value={report.overdueCount} tone="warn" />
      </div>

      {report.truncated ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          This rollup hit its scan limit, so it is a partial picture. Pick a single workshop above to narrow it.
        </p>
      ) : null}

      <p className="text-xs leading-5 text-ink-500">
        <span className="font-semibold text-ink-700">Reported</span> is what the person says they have done.{" "}
        <span className="font-semibold text-ink-700">In repository</span>{" "}
        is what the database can find them having actually created inside the task&apos;s scope. Neither overwrites the
        other — a wide gap is the thing to ask about.
      </p>

      <div className="grid gap-3">
        {report.assignees.map((row) => (
          <AssigneeRow key={row.user?.id ?? row.tasks[0]?.id} row={row} />
        ))}
      </div>
    </div>
  );
}
