"use client";

import { useState } from "react";
import { ChevronDown, RotateCcw, ShieldCheck, TriangleAlert } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { rowAction } from "@/components/RowActions";
import { useConfirm } from "@/components/dialogs";
import {
  DueBadge,
  EffectiveProgressBar,
  overrideConfirmCopy,
  overrideTargets,
  PersonLine,
  ProgressGapMeter,
  ScopeChips,
  StatTile,
  taskDecisionLabel,
  taskStatusText,
  TaskKindBadge,
  TaskStatusPill
} from "@/components/tasks/TaskPrimitives";
import type {
  FieldTask,
  TaskOverrideTarget,
  TaskProgressAssignee,
  TaskProgressReport,
  TaskStatus
} from "@/components/tasks/types";
import { apiFetch } from "@/lib/api";
import { cn } from "@/lib/utils";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { TaskPendingRecords } from "@/components/tasks/TaskPendingRecords";

type OverrideMark = { next: TaskOverrideTarget; from: TaskStatus };
type OverrideMarks = Record<string, OverrideMark>;

function TaskOverrideControls({
  task,
  assigneeName,
  busy,
  mark,
  error,
  onOverride
}: {
  task: FieldTask;
  assigneeName?: string | null;
  busy: boolean;
  mark?: OverrideMark;
  error?: string | null;
  onOverride: (task: FieldTask, next: TaskOverrideTarget, assigneeName?: string | null) => void;
}) {
  const targets = overrideTargets(task.status);
  const reviewing = task.status === "SUBMITTED";

  if (!targets.length && !mark && !error) return null;

  return (
    <div
      className={cn(
        "mt-3 grid gap-2 border-t pt-3",
        reviewing ? "-mx-3 -mb-3 rounded-b-md border-purple-200 bg-purple-50 px-3 pb-3" : "border-line-200"
      )}
    >
      <div className="flex items-center gap-1.5">
        <ShieldCheck className={cn("h-3.5 w-3.5", reviewing ? "text-purple-700" : "text-ink-500")} aria-hidden />
        <span className={cn("field-label", reviewing && "text-purple-700")}>
          {reviewing ? "Waiting on your approval" : "Admin override"}
        </span>
      </div>
      <p className={cn("text-xs leading-5", reviewing ? "text-purple-700" : "text-ink-500")}>
        {reviewing ? (
          <>
            {assigneeName || "The assignee"} marked this finished. Check the figures above, then decide.
          </>
        ) : (
          <>
            This is saved in {assigneeName || "the assignee"}&apos;s name.
          </>
        )}
      </p>

      {targets.length ? (
        <ButtonRow size="sm" align="start">
          {targets.map((next) => (
            <button
              key={next}
              type="button"
              className={rowAction(next === "DONE" ? "positive" : "neutral")}
              disabled={busy}
              onClick={() => onOverride(task, next, assigneeName)}
            >
              {next === "DONE" ? (
                <ShieldCheck className="h-3.5 w-3.5" aria-hidden />
              ) : (
                <RotateCcw className="h-3.5 w-3.5" aria-hidden />
              )}
              {busy ? "Saving..." : taskDecisionLabel(task.status, next)}
            </button>
          ))}
        </ButtonRow>
      ) : null}

      {mark ? (
        <p className="text-xs leading-5 text-ink-500">
          <span className="font-semibold text-ink-700">
            {mark.from === "SUBMITTED"
              ? mark.next === "DONE"
                ? `You approved ${assigneeName || "the assignee"}'s work`
                : `You sent this back to ${assigneeName || "the assignee"}`
              : `You set this to ${taskStatusText(mark.next).toLowerCase()}`}
          </span>{" "}
          — {mark.from === "SUBMITTED" ? "your decision" : "an override"}, not{" "}
          {assigneeName || "the assignee"}&apos;s own report.
        </p>
      ) : null}

      {error ? (
        <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs leading-5 text-red-700">{error}</p>
      ) : null}
    </div>
  );
}

function AssigneeRow({
  row,
  canOverride,
  busyTaskId,
  marks,
  errors,
  onOverride
}: {
  row: TaskProgressAssignee;
  canOverride: boolean;
  busyTaskId: string | null;
  marks: OverrideMarks;
  errors: Record<string, string>;
  onOverride: (task: FieldTask, next: TaskOverrideTarget, assigneeName?: string | null) => void;
}) {
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
              {row.openCount} still to do
            </span>
            {row.awaitingReviewCount > 0 ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-purple-200 bg-purple-50 px-2.5 py-1 font-medium text-purple-700">
                <ShieldCheck className="h-3.5 w-3.5" aria-hidden />
                {row.awaitingReviewCount} waiting on you
              </span>
            ) : null}
            <span className="rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 font-medium text-success-600">
              {row.statusCounts.DONE} approved
            </span>
            {row.overdueCount > 0 ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 font-medium text-red-800">
                <TriangleAlert className="h-3.5 w-3.5" aria-hidden />
                {row.overdueCount} overdue
              </span>
            ) : null}
          </div>
          <button
            type="button"
            className="inline-flex w-fit items-center gap-1.5 text-xs font-semibold text-purple-700"
            aria-expanded={open}
            aria-controls={open ? panelId : undefined}
            onClick={() => setOpen((prev) => !prev)}
          >
            <ChevronDown className={cn("h-4 w-4 transition-transform", open && "rotate-180")} aria-hidden />
            {open ? "Hide" : "Show"} the {row.taskCount} task{row.taskCount === 1 ? "" : "s"}
            {canOverride ? " (and override them)" : ""}
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
                <TaskStatusPill status={task.status} label={task.statusLabel} />
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
              <div className="mt-3 grid gap-3 border-t border-line-200 pt-3">
                <EffectiveProgressBar
                  percent={task.effectivePercent}
                  label={task.progressLabel}
                  source={task.progressSource}
                  ariaLabel={`Progress on ${task.title}`}
                />
                <ProgressGapMeter
                  reported={task.progressCount}
                  derived={task.derivedCount}
                  target={task.targetCount ?? task.derivedTarget}
                />
                <TaskPendingRecords taskId={task.id} showReviewLink />
              </div>
              {canOverride ? (
                <TaskOverrideControls
                  task={task}
                  assigneeName={row.user?.name}
                  busy={busyTaskId === task.id}
                  mark={marks[task.id]}
                  error={errors[task.id]}
                  onOverride={onOverride}
                />
              ) : null}
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
  error,
  canOverride = false,
  onOverridden
}: {
  report: TaskProgressReport | null;
  loading: boolean;
  error: string | null;
  canOverride?: boolean;
  onOverridden?: () => void;
}) {
  const confirm = useConfirm();
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null);
  const [marks, setMarks] = useState<OverrideMarks>({});
  const [errors, setErrors] = useState<Record<string, string>>({});

  async function override(task: FieldTask, next: TaskOverrideTarget, assigneeName?: string | null) {
    const from: TaskStatus = task.status;
    const copy = overrideConfirmCopy({
      next,
      from,
      assigneeName,
      taskTitle: task.title,
      targetCount: task.targetCount,
      progressCount: task.progressCount ?? 0,
      derivedCount: task.derivedCount
    });
    const ok = await confirm({
      title: copy.title,
      body: copy.body,
      note: copy.note,
      confirmLabel: copy.confirmLabel,
      tone: "warning"
    });
    if (!ok) return;

    setBusyTaskId(task.id);
    setErrors((prev) => {
      const { [task.id]: _dropped, ...rest } = prev;
      return rest;
    });
    try {
      await apiFetch(`/tasks/${task.id}`, { method: "PATCH", body: JSON.stringify({ status: next }) });
      setMarks((prev) => ({ ...prev, [task.id]: { next, from } }));
      onOverridden?.();
    } catch (err) {
      setErrors((prev) => ({
        ...prev,
        [task.id]: err instanceof Error ? err.message : "Unable to change this task"
      }));
    } finally {
      setBusyTaskId(null);
    }
  }

  if (error) {
    return <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>;
  }
  if (loading && !report) return <div className="panel p-4 text-sm text-ink-500">Loading the rollup...</div>;
  if (!report) return null;
  if (!report.assignees.length) {
    return (
      <EmptyState
        title="Nobody has been given work here yet"
        body="Assign work to track it here."
      />
    );
  }

  return (
    <div className="grid gap-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
        <StatTile label="People with work" value={report.assigneeCount} />
        <StatTile label="Tasks in scope" value={report.taskCount} />
        <StatTile label="Still to do" value={report.openCount} hint="with the assignee" />
        <StatTile label="Waiting on you" value={report.awaitingReviewCount} hint="to approve" />
        <StatTile label="Approved" value={report.doneCount} tone="good" />
        <StatTile label="Overdue" value={report.overdueCount} tone="warn" />
      </div>

      {report.awaitingReviewCount > 0 ? (
        <p className="flex items-start gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm leading-6 text-purple-700">
          <ShieldCheck className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          <span>
            {report.awaitingReviewCount === 1
              ? "One task has been handed in and is waiting for you to approve it."
              : `${report.awaitingReviewCount} tasks have been handed in and are waiting for you to approve them.`}{" "}
            They stay on the assignee&apos;s list until you do. Open the person below to approve or send one back.
          </span>
        </p>
      ) : null}

      {report.truncated ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          This rollup hit its scan limit, so it is a partial picture. Pick a single workshop above to narrow it.
        </p>
      ) : null}

      <p className="text-xs leading-5 text-ink-500">
        <span className="font-semibold text-ink-700">Reported</span> is what the person says they have done.{" "}
        <span className="font-semibold text-ink-700">Recorded</span>{" "}
        is what they&apos;ve actually created for this task. Neither overwrites the
        other — a wide gap is the thing to ask about.
        {canOverride ? (
          <>
            {" "}
            Open a person&apos;s tasks to approve one they have handed in, send it back, mark one done on their behalf,
            or reopen one you marked.
          </>
        ) : null}
      </p>

      <div className="grid gap-3">
        {report.assignees.map((row) => (
          <AssigneeRow
            key={row.user?.id ?? row.tasks[0]?.id}
            row={row}
            canOverride={canOverride}
            busyTaskId={busyTaskId}
            marks={marks}
            errors={errors}
            onOverride={override}
          />
        ))}
      </div>
    </div>
  );
}
