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

/**
 * Who has what, and how far along they ACTUALLY are.
 *
 * Every line carries two numbers: `reportedTotal`, which the assignee typed in, and `derivedTotal`,
 * which is counted from the records that reached the repository. They are never merged and neither
 * one is presented as the truth — the distance between them is the signal this whole view exists to
 * surface, so it is shown as two bars on one scale plus a worded verdict, never as a single
 * "progress" percentage that would quietly average the disagreement away.
 *
 * THE ADMIN OVERRIDE (owner request, 2026-09-14) lives here rather than on `MyTaskCard`, and the
 * placement is the argument. `MyTaskCard`'s "Mark done" renders only on the assignee's own card,
 * which is correct — that button is a REPORT by the person who did the work. An admin declaring
 * somebody else's task finished is a different act performed by a different person, and this board
 * is the only screen where an admin is already looking at other people's tasks one row at a time.
 * The server has permitted it all along (an admin is a manager in `update_task`, and a manager may
 * write `status`), so this is the missing half of a feature, not a new power — see
 * `backend/tests/test_task_authority.py`, which pins the server side so the control cannot be left
 * pointing at a rule somebody "tidied" away.
 *
 * THE APPROVAL QUEUE (owner request, 2026-09-14) IS THE SAME CONTROL, NOT A SECOND ONE. "The
 * admin/master admin need to approve the work as done when the researcher has marked it as done" is,
 * on the wire, `PATCH {status: "DONE"}` by a manager — byte for byte the write the override strip
 * already performs. So a SUBMITTED task grows two entries in `overrideTargets` rather than a
 * parallel pair of buttons with their own gate, their own confirm and their own error slot; only the
 * WORDS change ("Approve" / "Send back", `taskDecisionLabel`), because an admin agreeing with a
 * researcher's own claim is a different act from an admin overruling them, and the row that lands in
 * the database is identical either way. Two controls over one endpoint drift the first time either
 * one is edited; two vocabularies over one control cannot.
 */

/**
 * The decision the viewer performed in THIS browser tab, per task. Never read back from the API.
 *
 * `from` is carried as well as `next` because the two are what distinguish the acts: DONE written
 * over SUBMITTED is an approval, DONE written over OPEN is an override, and the row the server
 * stores is identical. The moment the page reloads this map is gone and both read as "Approved" —
 * which is exactly what the note below says out loud rather than letting an admin discover it.
 */
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

  // A cancelled task offers nothing, so the strip would be a heading over an empty row saying an
  // override is available when none is. Withdrawal is the Assignments tab's, deliberately.
  if (!targets.length && !mark && !error) return null;

  return (
    <div
      className={cn(
        "mt-3 grid gap-2 border-t pt-3",
        // A row that is WAITING ON THE READER is lifted out of the page, because the whole strip is
        // otherwise identical on every task and an approval queue nobody can pick out of a list is
        // an approval queue nobody works through. The border and the heading carry it; the tint is
        // the same informational purple the assignee's own card uses for this state, so both ends of
        // the transaction are the same colour.
        // `rounded-b-md` because the negative margins take the band to the card's edges and the card
        // is `rounded-md`: without it the tint squares off two corners the border still rounds. And
        // NOT `overflow-hidden` on the card to solve it — the global focus ring is an outline at
        // `outline-offset: 2px`, and clipping the card would erase it from these buttons.
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
            {/*
              THE TWO NUMBERS ARE ALREADY ON THIS ROW, directly above — `ProgressGapMeter` prints
              what they reported against what the repository can find. This sentence's job is only
              to say that the decision is the reader's and that nothing else is coming: no
              notification is sent, no comment box exists, and the row will sit here until somebody
              presses one of these two buttons.
            */}
            {assigneeName || "The assignee"} marked this finished and it is waiting on you. Check the two figures above
            before you agree — nothing else will chase this, and the task stays on their list until you decide.
          </>
        ) : (
          <>
            {/*
              SAID BEFORE THE BUTTONS, NOT AFTER THE PRESS. The task row records `status` and
              `completedAt` and nothing else, so an override and the assignee's own "Mark done" are
              indistinguishable the moment the page reloads. An admin who assumes the board remembers
              who pressed this would otherwise find out by reloading and finding nothing.
            */}
            Changing this writes in {assigneeName || "the assignee"}&apos;s name. The task keeps only its status, so
            nothing stored on it will say an admin set it.
          </>
        )}
      </p>

      {targets.length ? (
        <div className="flex flex-wrap gap-2">
          {targets.map((next) => (
            <button
              key={next}
              type="button"
              // `positive` for done, `neutral` for the ways back — and deliberately NOT the filled
              // purple `.field-button` MyTaskCard uses. Two controls that write the same field must
              // not look like the same control when one is a person reporting their own work and the
              // other is somebody deciding about it.
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
        </div>
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
          {assigneeName || "the assignee"}&apos;s own report. This sentence lives in this tab only; a reload shows the
          status alone, because the repository has no column for who changed it.
        </p>
      ) : null}

      {/*
        The failure is reported HERE and not in a banner at the top of the board. These rows sit
        inside a per-person disclosure that is usually several screens down, so a top-pinned error
        would be a button that appeared to do nothing with an explanation the admin never scrolled
        back up to see. Same reasoning as `ReviewEditPanel`'s panel-level banner.
      */}
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
            {/*
              "OUTSTANDING" NARROWED TO `openCount` ON PURPOSE, and the awaiting-review chip beside
              it is why. `outstandingCount` includes submissions, so a chip labelled "outstanding"
              over that total would count work this person has already handed in as work they still
              owe — and this board's rows are sorted by exactly that judgement. What they owe and
              what the reader owes are now two chips with two counts, which is the only arrangement
              in which either number means anything.
            */}
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
                {/*
                  The same headline measure the assignee sees on their own card, so an approver and
                  the person being approved are reading one number rather than two views of it. The
                  two-figure comparison stays underneath — it is what this board is FOR, and it is
                  what the reader is being asked to check before pressing Approve.
                */}
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
  /**
   * May the viewer change other people's tasks from here? MUST come from `isAdmin(user)` in
   * `lib/permissions.ts` (the page passes exactly that) — never a rank comparison written here.
   * The route is already admin-only, so this is the control's own gate rather than the page's, and
   * it is the one that would still hold if this board were ever mounted somewhere less guarded.
   */
  canOverride?: boolean;
  /** Re-read the rollup after an override — the percentages and status counts all move. */
  onOverridden?: () => void;
}) {
  const confirm = useConfirm();
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null);
  const [marks, setMarks] = useState<OverrideMarks>({});
  const [errors, setErrors] = useState<Record<string, string>>({});

  async function override(task: FieldTask, next: TaskOverrideTarget, assigneeName?: string | null) {
    // THE STATUS IS READ BEFORE THE WRITE and carried through everything below, because it is what
    // decides whether this press is an approval or an override — the same PATCH, two acts, and the
    // row that lands cannot tell them apart afterwards.
    const from: TaskStatus = task.status;
    const copy = overrideConfirmCopy({
      next,
      from,
      assigneeName,
      taskTitle: task.title,
      targetCount: task.targetCount,
      progressCount: task.progressCount ?? 0,
      // The approver's counter-number. Null is passed through as null rather than defaulted to 0:
      // "the repository can find 0" and "we did not count" are different answers, and only one of
      // them is a reason to refuse an approval.
      derivedCount: task.derivedCount
    });
    // "warning", not "danger": nothing is destroyed and the move is reversible, but it is a write
    // in somebody else's name and must not be one reflex click away. A danger tone here would put
    // it in the same register as withdrawing an assignment, which genuinely loses reported progress.
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
        body="Assign work on the first tab and this becomes the accountability view: who has what, what they say they have done, and what the repository can actually find."
      />
    );
  }

  return (
    <div className="grid gap-4">
      {/*
        SIX TILES, AND THE NEW ONE IS THE ONLY ONE ABOUT THE READER. Everything else on this board
        describes other people's weeks; "Waiting on you" is a count of decisions this admin owes, and
        it was worth a sixth column rather than being folded into "Still to do" — which is what
        `openCount` means now that submissions have a status of their own. Folding them would have
        published the reviewer's own backlog as the researchers' lateness, one tile wide.
      */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
        <StatTile label="People with work" value={report.assigneeCount} />
        <StatTile label="Tasks in scope" value={report.taskCount} />
        <StatTile label="Still to do" value={report.openCount} hint="with the assignee" />
        <StatTile label="Waiting on you" value={report.awaitingReviewCount} hint="to approve" />
        <StatTile label="Approved" value={report.doneCount} tone="good" />
        <StatTile label="Overdue" value={report.overdueCount} tone="warn" />
      </div>

      {report.awaitingReviewCount > 0 ? (
        // THE QUEUE, NAMED WHERE THE READER IS ALREADY LOOKING. The decisions themselves live one
        // disclosure down, per task, beside the two figures an approver has to compare — there is
        // deliberately no "approve all" here, because the whole reason this board exists is that a
        // task reported done with nothing behind it should be caught, and a bulk button is a bulk
        // way not to look. (The Assignments tab's bulk action is a WITHDRAWAL of one scope, which
        // is one decision about one assignment; this would be N decisions about N people's work.)
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
        <span className="font-semibold text-ink-700">In repository</span>{" "}
        is what the database can find them having actually created inside the task&apos;s scope. Neither overwrites the
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
