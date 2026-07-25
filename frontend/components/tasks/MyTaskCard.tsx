"use client";

import { useEffect, useState } from "react";
import { CheckCheck, Play, RotateCcw } from "lucide-react";

import { Markdown } from "@/components/Markdown";
import { DueBadge, ProgressGapMeter, ScopeChips, TaskKindBadge, TaskStatusPill } from "@/components/tasks/TaskPrimitives";
import type { FieldTask, TaskStatus } from "@/components/tasks/types";

/**
 * One task as the person who has to do it sees it.
 *
 * The scope arrives fully resolved from the server — workshop title, artisan names, section codes —
 * so the card can say what the work IS rather than showing the ids it was scoped by. The reported
 * figure is editable here and only here: it belongs to the assignee, while the repository-derived
 * count beside it belongs to the database, and the card deliberately shows both so nobody is
 * surprised later by a gap an admin can see and they cannot.
 */
export function MyTaskCard({
  task,
  editable,
  busy,
  onStatus,
  onReport
}: {
  task: FieldTask;
  /** False for a cancelled task, or when the viewer is not the assignee. */
  editable: boolean;
  busy: boolean;
  onStatus: (status: TaskStatus) => void;
  onReport: (progressCount: number) => void;
}) {
  const [reported, setReported] = useState(String(task.progressCount ?? 0));

  // Follow the server's value after a save (and after "Mark done" fills a quota for us).
  useEffect(() => {
    setReported(String(task.progressCount ?? 0));
  }, [task.progressCount]);

  const parsed = Number.parseInt(reported, 10);
  const validReport = Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  const changed = validReport !== null && validReport !== (task.progressCount ?? 0);

  return (
    <article className="panel flex flex-col gap-3 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="font-display font-semibold text-ink-900">{task.title}</h3>
          <p className="mt-0.5 text-xs text-ink-500">Assigned by {task.createdBy?.name ?? "an administrator"}</p>
        </div>
        <TaskStatusPill status={task.status} />
      </div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <TaskKindBadge recordTypes={task.recordTypes} sections={task.sections} />
        <DueBadge dueAt={task.dueAt} overdue={task.isOverdue} />
      </div>

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
        <ProgressGapMeter
          reported={task.progressCount ?? 0}
          derived={task.derivedCount}
          target={task.targetCount ?? task.derivedTarget}
        />
        {editable ? (
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
        <div className="mt-auto flex flex-wrap gap-2 border-t border-line-200 pt-3">
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
          {task.status === "DONE" ? (
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => onStatus("OPEN")}>
              <RotateCcw className="h-4 w-4" aria-hidden />
              Reopen
            </button>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}
