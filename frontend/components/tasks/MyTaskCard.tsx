"use client";

import { useEffect, useState } from "react";
import { CheckCheck, Play, ShieldCheck } from "lucide-react";

import { Markdown } from "@/components/Markdown";
import {
  DueBadge,
  EffectiveProgressBar,
  ProgressGapMeter,
  reviewNoticeCopy,
  ScopeChips,
  TaskKindBadge,
  TaskStatusPill
} from "@/components/tasks/TaskPrimitives";
import type { FieldTask, TaskStatus } from "@/components/tasks/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { TaskPendingRecords } from "@/components/tasks/TaskPendingRecords";

export function MyTaskCard({
  task,
  editable,
  busy,
  onStatus,
  onReport
}: {
  task: FieldTask;
  editable: boolean;
  busy: boolean;
  onStatus: (status: TaskStatus) => void;
  onReport: (progressCount: number) => void;
}) {
  const [reported, setReported] = useState(String(task.progressCount ?? 0));

  useEffect(() => {
    setReported(String(task.progressCount ?? 0));
  }, [task.progressCount]);

  const parsed = Number.parseInt(reported, 10);
  const validReport = Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  const changed = validReport !== null && validReport !== (task.progressCount ?? 0);

  const awaitingReview = task.isAwaitingReview || task.status === "SUBMITTED";
  const review = reviewNoticeCopy(task.createdBy?.name);
  const reportable = editable && task.status !== "DONE";

  return (
    <article className="panel flex flex-col gap-3 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="font-display font-semibold text-ink-900">{task.title}</h3>
          <p className="mt-0.5 text-xs text-ink-500">Assigned by {task.createdBy?.name ?? "an administrator"}</p>
        </div>
        <TaskStatusPill status={task.status} label={task.statusLabel} />
      </div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <TaskKindBadge recordTypes={task.recordTypes} sections={task.sections} />
        <DueBadge dueAt={task.dueAt} overdue={task.isOverdue} />
      </div>

      {awaitingReview ? (
        <div className="grid gap-1 rounded-md border border-purple-200 bg-purple-50 p-3">
          <p className="flex items-center gap-1.5 text-sm font-semibold text-purple-700">
            <ShieldCheck className="h-4 w-4 shrink-0" aria-hidden />
            {review.title}
          </p>
          <p className="text-xs leading-5 text-purple-700">{review.body}</p>
          <p className="text-xs leading-5 text-purple-700">{review.action}</p>
        </div>
      ) : null}

      {task.description ? <Markdown text={task.description} className="text-ink-700" /> : null}

      <ScopeChips
        recordTypeLabels={task.recordTypeLabels}
        sections={task.sections}
        artisans={task.artisans}
        targetCount={task.targetCount}
        workshopTitle={task.workshopTitle}
      />

      {task.sections.length ? (
        <p className="text-xs leading-5 text-ink-500">
          Questionnaire work: answer{" "}
          {task.sections.map((section) => `section ${section.code}`).join(", ")}
          {task.artisans.length ? ` for ${task.artisans.length} named artisan${task.artisans.length === 1 ? "" : "s"}` : ""}
          {" "}on the Take interview screen.
        </p>
      ) : null}

      <div className="rounded-md border border-line-200 bg-surface-50 p-3">
        <EffectiveProgressBar
          percent={task.effectivePercent}
          label={task.progressLabel}
          source={task.progressSource}
          ariaLabel={`Progress on ${task.title}`}
        />
        <div className="mt-3 border-t border-line-200 pt-3">
          <ProgressGapMeter
            reported={task.progressCount ?? 0}
            derived={task.derivedCount}
            target={task.targetCount ?? task.derivedTarget}
          />
          <TaskPendingRecords taskId={task.id} showReviewLink={false} />
        </div>
        {typeof task.derivedBreakdown?.unlinkedSections === "number" && task.derivedBreakdown.unlinkedSections > 0 ? (
          <p className="mt-2 text-xs leading-5 text-amber-800 dark:text-amber-200">
            {task.derivedBreakdown.unlinkedSections} section
            {task.derivedBreakdown.unlinkedSections === 1 ? " isn’t" : "s aren’t"} counted — link the interview to an
            artisan.
          </p>
        ) : null}
        {reportable ? (
          <div className="mt-3 flex flex-wrap items-end gap-2 border-t border-line-200 pt-3">
            <label className="grid gap-1">
              <span className="field-label">My progress</span>
              <input
                type="number"
                min={0}
                max={task.targetCount ?? undefined}
                inputMode="numeric"
                value={reported}
                onChange={(event) => setReported(event.target.value)}
                className="field-input w-28"
              />
            </label>
            <button
              type="button"
              className="field-button-secondary"
              disabled={busy || !changed}
              onClick={() => validReport !== null && onReport(validReport)}
            >
              {busy ? "Saving..." : "Report"}
            </button>
            {task.targetCount ? (
              <p className="pb-2.5 text-xs text-ink-500">out of {task.targetCount}</p>
            ) : (
              <p className="pb-2.5 text-xs text-ink-500">no target set</p>
            )}
          </div>
        ) : null}
      </div>

      {editable ? (
        <div className="mt-auto grid gap-2 border-t border-line-200 pt-3">
          <ButtonRow align="start">
            {task.status === "OPEN" ? (
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => onStatus("IN_PROGRESS")}>
                <Play className="h-4 w-4" aria-hidden />
                Start
              </button>
            ) : null}
            {task.status === "OPEN" || task.status === "IN_PROGRESS" ? (
              <button type="button" className="field-button" disabled={busy} onClick={() => onStatus("DONE")}>
                <CheckCheck className="h-4 w-4" aria-hidden />
                Mark done
              </button>
            ) : null}
            {awaitingReview ? (
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => onStatus("IN_PROGRESS")}>
                <Play className="h-4 w-4" aria-hidden />
                Still working on it
              </button>
            ) : null}
          </ButtonRow>
          {task.status === "OPEN" || task.status === "IN_PROGRESS" ? (
            <p className="text-xs leading-5 text-ink-500">
              Marking it done sends it for approval.
            </p>
          ) : null}
          {task.status === "DONE" ? (
            <p className="text-xs leading-5 text-ink-500">
              Approved.
            </p>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}
