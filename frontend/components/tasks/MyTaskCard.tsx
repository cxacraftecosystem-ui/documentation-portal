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

/**
 * One task as the person who has to do it sees it.
 *
 * The scope arrives fully resolved from the server — workshop title, artisan names, section codes —
 * so the card can say what the work IS rather than showing the ids it was scoped by. The reported
 * figure is editable here and only here: it belongs to the assignee, while the repository-derived
 * count beside it belongs to the database, and the card deliberately shows both so nobody is
 * surprised later by a gap an admin can see and they cannot.
 *
 * ═══ THE REVIEW STATE (owner request, 2026-09-14) ═══
 *
 * "The admin/master admin need to approve the work as done when the researcher has marked it as
 * done for it to not pop up next time, tell them that it is currently under review."
 *
 * The server does the first half: an assignee's "DONE" is rewritten to SUBMITTED
 * (`update_task`, backend/app/api/routes/tasks.py) and the task stays outstanding until a manager
 * writes the real DONE. That change, on its own, produces the worst possible screen — a researcher
 * presses the button, the card does not leave the list, and nothing says why. The only reading
 * available to them is that the press failed, so they press it again, and then they email somebody.
 * Everything below marked ⚑ exists to close that gap, and none of it is decoration.
 *
 * ⚑ THE PILL takes the SERVER's `statusLabel` ("Under review"), not a word invented here — three
 *   hand-written clients, no codegen, and a researcher who moves between the handset and the laptop
 *   mid-workshop must not be told two different things about one row.
 * ⚑ THE NOTICE sits directly under the heading, in the app's informational purple rather than in
 *   amber or red: being under review is the correct outcome of doing the right thing, and the card
 *   must not read as a rejection.
 * ⚑ THE BUTTON IS GONE, replaced by the only move that is still theirs — taking the submission back.
 *   Leaving "Mark done" on screen after a submission is an invitation to press it again.
 * ⚑ AN APPROVED TASK OFFERS NO REOPEN. It used to, and it would now 403: `update_task` refuses an
 *   assignee any status write on a DONE task, because DONE means somebody else agreed and letting
 *   the person whose work it is take that back makes the approval decorative. A button that always
 *   fails is worse than no button, so the card says who can undo it instead.
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

  const awaitingReview = task.isAwaitingReview || task.status === "SUBMITTED";
  const review = reviewNoticeCopy(task.createdBy?.name);
  /**
   * AN APPROVED TASK'S REPORTED FIGURE IS NOT ITS ASSIGNEE'S ANY MORE, and this is the client
   * declining a power the server would grant. `update_task` blocks an assignee's STATUS write on a
   * DONE task and says nothing about `progressCount`, so the box would work — and editing it would
   * silently rewrite the number somebody else's approval was based on, on a row that already reads
   * "agreed". Reporting stays open right through the review, where it is still a live claim.
   */
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
        {/*
          THE HEADLINE MEASURE FIRST, THE TWO-NUMBER COMPARISON UNDER IT. `effectivePercent` is the
          server's answer to "how far along is this one task", and on a scoped task it is counted
          from the repository — the owner's "should progress automatically as they record for more
          and more artisans", with nobody typing anything. Where it is null NO BAR IS DRAWN, because
          a task with no countable scope and no quota at 0% would read as "you have done none of it"
          about work that was never countable.

          `ProgressGapMeter` stays underneath rather than being replaced by it: the two figures it
          compares are what an admin will be looking at on the accountability board, and an assignee
          who cannot see the same gap is the one person in the building who gets surprised by it.
        */}
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
        </div>
        {typeof task.derivedBreakdown?.unlinkedSections === "number" && task.derivedBreakdown.unlinkedSections > 0 ? (
          // WHY A COUNTED TASK CAN SIT AT ZERO WHILE THE WORK IS PLAINLY DONE. These answers exist;
          // they are on an interview with no artisan attached, so they cannot raise a per-artisan
          // count. Without this line the bar is stuck at 0% for a reason nothing on screen explains,
          // and the researcher's only conclusion is that the app is not seeing their work.
          <p className="mt-2 text-xs leading-5 text-amber-800">
            {task.derivedBreakdown.unlinkedSections} section
            {task.derivedBreakdown.unlinkedSections === 1 ? " has" : "s have"} answers on an interview with no artisan
            attached, so they are not counted here. Link the interview to an artisan and they will be.
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
          <div className="flex flex-wrap gap-2">
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
              /*
                THE ONE MOVE STILL THEIRS. `update_task` lets an assignee move a SUBMITTED task back
                (only DONE is locked), so somebody who handed in the wrong task, or realised there is
                more to do, can take it back without asking an admin to do it for them. It is the
                secondary button, never the filled one — withdrawing is a correction, not the
                expected next step.
              */
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => onStatus("IN_PROGRESS")}>
                <Play className="h-4 w-4" aria-hidden />
                Still working on it
              </button>
            ) : null}
          </div>
          {task.status === "OPEN" || task.status === "IN_PROGRESS" ? (
            // SAID BEFORE THE PRESS, NOT DISCOVERED AFTER IT. The button keeps the word Android uses
            // and every field build already sends; this sentence is what makes the outcome — a task
            // that stays on the list — an expectation rather than a surprise.
            <p className="text-xs leading-5 text-ink-500">
              Marking this done sends it to an admin to approve. It stays on your list, marked under review, until they
              do.
            </p>
          ) : null}
          {task.status === "DONE" ? (
            <p className="text-xs leading-5 text-ink-500">
              Approved — this one is finished. Only an admin or the person who assigned it can reopen it now.
            </p>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}
