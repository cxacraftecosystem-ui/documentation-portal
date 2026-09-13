"use client";

import { useId } from "react";
import {
  Boxes,
  CalendarClock,
  CheckCircle2,
  ClipboardList,
  HelpCircle,
  Minus,
  TriangleAlert,
  type LucideIcon
} from "lucide-react";

import { progressGap } from "@/components/tasks/scope";
import { RequiredMark } from "@/components/ui/RequiredMark";
import type {
  TaskArtisanRef,
  TaskOverrideTarget,
  TaskProgressSource,
  TaskSectionRef,
  TaskStatus,
  TaskUserBrief
} from "@/components/tasks/types";
import { formatDate } from "@/lib/format";
import { roleLabel } from "@/lib/permissions";
import { cn } from "@/lib/utils";

/**
 * The shared vocabulary of the task screens.
 *
 * Both boards — the admin's assignment/accountability board and the assignee's own list — describe
 * the same five-dimension scope and the same two progress numbers, so every chip, pill and meter is
 * defined once here. Two rules hold throughout: colour never carries meaning on its own (each bar
 * and chip is labelled in words), and the reported figure and the repository-derived figure are
 * always shown TOGETHER, because either one alone is misleading.
 */

const CHIP = "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium";

/**
 * A labelled slot for a DROPDOWN — looks exactly like `FormControls.Field`, but is a `<div>` rather
 * than a `<label>`.
 *
 * That difference is load-bearing. A `<label>` forwards a stray click to the first labelable control
 * inside it, and for a custom listbox that control is the trigger button: ticking an option inside a
 * `<label>`-wrapped MultiSelectDropdown therefore fires the trigger's toggle as well and slams the
 * menu shut after ONE pick, so a second option can never be reached and the Confirm button — which
 * only appears once something is ticked — is never on screen long enough to click. Verified in the
 * browser before this wrapper existed. `Field` stays correct for inputs and textareas, which are
 * exactly what a `<label>` is for; composite widgets get named through `role="group"` instead.
 */
export function FieldBlock({
  label,
  required,
  hint,
  children
}: {
  label: string;
  required?: boolean;
  hint?: React.ReactNode;
  children: React.ReactNode;
}) {
  const id = useId();
  return (
    <div className="grid gap-1">
      <span id={id} className="field-label">
        {label}
        {/*
          The third and last of the hand-written asterisks — see `components/ui/RequiredMark.tsx`
          for why all three had to be converted in one commit before the mark could be given its
          red, and `e2e/record-form-dictation-unit.spec.ts` for the census that refuses to let a
          fourth be written.

          THIS IS `FieldBlock`, NOT `Field`, and the distinction is not cosmetic: this wrapper is a
          `<div>` with `role="group"` because everything it labels contains a button, and a `<label>`
          forwards a stray click to the first labelable control inside it (which slams a multi-select
          shut after one pick) and folds every named descendant into the control's accessible name.
          The mark renders identically in both; only the wrapper differs.
        */}
        <RequiredMark when={required} />
      </span>
      <div role="group" aria-labelledby={id}>
        {children}
      </div>
      {hint}
    </div>
  );
}

/**
 * SUBMITTED IS PURPLE, NOT AMBER AND NOT GREEN, and the choice is the message.
 *
 * Green would say the work is finished — it is not, nobody has agreed to it yet. Amber is already
 * IN_PROGRESS on this very board, so a second amber pill would put "still working" and "handed in,
 * waiting on somebody else" in one visual bucket, which is precisely the distinction the review
 * state was added to draw. Error red would be a reprimand for doing the right thing. Purple is this
 * app's own register for "the app is telling you something", it is what `TaskKindBadge` and
 * `ScopeChips` already use two lines below, and it reads as neutral-informative rather than as a
 * verdict. The word "Under review" is what actually carries the meaning; the colour only has to
 * avoid contradicting it.
 */
const STATUS_TONE: Record<TaskStatus, string> = {
  OPEN: "border-line-200 bg-surface-50 text-ink-500",
  IN_PROGRESS: "border-amber-500/30 bg-amber-100 text-amber-800",
  SUBMITTED: "border-purple-200 bg-purple-50 text-purple-700",
  DONE: "border-success-600/25 bg-success-100 text-success-600",
  CANCELLED: "border-error-600/25 bg-error-100 text-error-600"
};

/**
 * THE CLIENT'S FALLBACK WORDING — used when a row carries no server label, and nowhere else.
 *
 * The authority is `STATUS_LABELS` on the server (backend/app/api/routes/tasks.py:129), which every
 * serialised task now arrives with as `statusLabel`; `TaskStatusPill` prefers it. This map survives
 * for the two callers that hold a STATUS with no row behind it — `overrideActionLabel` and
 * `overrideConfirmCopy` name a DESTINATION status, and a destination has not been serialised yet —
 * and for a payload cached before the field existed.
 *
 * ⚠ IT DOES NOT AGREE WITH THE SERVER ON TWO WORDS, and pretending otherwise would be worse than
 * saying so: the server calls OPEN "To do" and DONE "Approved", where this map says "Open" and
 * "Done". Closing that gap means changing these two strings, which are pinned by
 * `e2e/task-authority-unit.spec.ts` ("a status is spoken in one wording everywhere") — the two
 * assertions there and these two values move together or not at all. Every row a reader actually
 * sees is labelled by the server, so the seam shows only on the override BUTTONS ("Back to open"
 * beside pills reading "To do"), which is the milder half of the defect that spec was written for.
 */
const STATUS_TEXT: Record<TaskStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  SUBMITTED: "Under review",
  DONE: "Done",
  CANCELLED: "Cancelled"
};

/**
 * The status, worded by whoever has the better claim to word it.
 *
 * `label` is the server's `statusLabel` and it WINS when present. There is no API codegen in this
 * repository — `lib/types.ts` and Android's `ApiModels.kt` are both hand-written — so a status
 * vocabulary maintained separately in each client is three vocabularies, and a researcher who works
 * a workshop on the handset and writes it up on the laptop is the person who finds out. The local
 * map is the fallback for a payload that predates the field, never the preference.
 */
export function TaskStatusPill({ status, label }: { status: TaskStatus; label?: string | null }) {
  return (
    <span className={cn(CHIP, STATUS_TONE[status] ?? STATUS_TONE.OPEN)}>{label?.trim() || taskStatusText(status)}</span>
  );
}

/**
 * One status, in the one wording every task surface uses that has no server label to hand.
 *
 * Exported because the pill is not the only place a status is now spoken: the accountability
 * board's admin override writes the same words into a confirmation dialog and into the note it
 * leaves behind afterwards. A second `status === "IN_PROGRESS" ? "In progress" : ...` written at
 * one of those call sites is how a screen ends up telling a reader their task is "IN_PROGRESS" in
 * the dialog and "In progress" on the row.
 *
 * An unknown status prints as itself rather than being swallowed — the server may grow a sixth
 * state before this bundle is rebuilt, and a raw value is ugly and honest where a fallback of
 * "Open" would be neither.
 */
export function taskStatusText(status: TaskStatus | string): string {
  return STATUS_TEXT[status as TaskStatus] ?? String(status);
}

/* ────────────────────────────────────────────────────────────────────────────
 * THE ADMIN OVERRIDE — an admin declaring somebody else's task finished, or undoing that.
 *
 * WHY THIS IS A SEPARATE VOCABULARY AND NOT A REUSE OF `MyTaskCard`'s BUTTONS. Those two acts are
 * not the same act. The assignee pressing "Mark done" is a REPORT: the person who did the work is
 * the person saying it is finished, and the card is theirs. An admin pressing a button on the
 * accountability board is a DECLARATION ABOUT SOMEBODY ELSE — made by a person who did not do the
 * work, possibly while that person is still doing it. The row it writes is byte-identical either
 * way (`backend/tests/test_task_authority.py::test_an_override_leaves_no_trace_of_who_performed_it`
 * pins that: `status` and `completedAt`, nothing more), so nothing downstream will ever distinguish
 * them and the ONLY place the difference can be made visible is here, before the press.
 *
 * Three things carry it, and none of them is colour — this file's header rule holds: the wording
 * ("for them", never a bare "Mark done"), a mandatory confirmation that names the assignee and
 * states what the server will do, and a shield icon rather than the assignee card's tick.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Which overrides the board offers from a given status, in the order they should be drawn.
 *
 * A finished task offers BOTH ways back, and offering both is deliberate: "never started" and
 * "half done" are different answers about a person's week, and an override that could only restore
 * OPEN would quietly erase the difference on every task it touched. A CANCELLED task offers
 * nothing — see `TaskOverrideTarget` for why withdrawal does not belong on this board.
 */
export function overrideTargets(status: TaskStatus): TaskOverrideTarget[] {
  if (status === "DONE") return ["IN_PROGRESS", "OPEN"];
  // THE REVIEW DECISION, AND IT IS THE SAME CONTROL AS THE OVERRIDE RATHER THAN A SECOND ONE. The
  // server has no `POST /tasks/{id}/approve`: approving is `status: "DONE"` written by a manager,
  // byte for byte the write this strip already performs. Building a parallel pair of buttons with
  // their own gate, their own confirm and their own error slot would be a second control over one
  // endpoint, and the two would drift the first time either was edited.
  //
  // Approve comes first because it is the expected answer to a submission; the reader's eye should
  // land on it. IN_PROGRESS is the ONLY way back offered here, and OPEN is deliberately withheld:
  // the person demonstrably started — they handed the work in — so restoring "not started" would be
  // the board contradicting a fact it is looking at. A finished task still offers both, because
  // there "never started" and "half done" are genuinely different answers about somebody's week.
  if (status === "SUBMITTED") return ["DONE", "IN_PROGRESS"];
  if (status === "OPEN" || status === "IN_PROGRESS") return ["DONE"];
  return [];
}

/**
 * The button's wording. "Mark done FOR THEM" rather than `MyTaskCard`'s "Mark done" is the whole
 * distinction in two words, and it is the half a reader sees without opening anything.
 *
 * THE WAYS BACK BORROW THE PILL'S WORDS RATHER THAN INVENTING THEIR OWN. These buttons sit inches
 * below the `TaskStatusPill` for the very task they act on, so a button reading "Reopen as not
 * started" beside a pill reading "Open" puts two names for one state on one row — which was the
 * first wording written here, and `e2e/task-authority-unit.spec.ts` caught it against the sentence
 * `overrideConfirmCopy` builds from `taskStatusText`. Deriving all three from one function is what
 * stops the next edit re-opening that gap.
 */
export function overrideActionLabel(next: TaskOverrideTarget): string {
  if (next === "DONE") return "Mark done for them";
  return `Back to ${taskStatusText(next).toLowerCase()}`;
}

/**
 * The same two writes, when the task is SUBMITTED — and they are a different pair of acts, so they
 * get a different pair of words.
 *
 * `status: "DONE"` on a task nobody has handed in is an admin DECLARING somebody else's work
 * finished. The identical PATCH on a SUBMITTED task is an admin AGREEING with a claim that person
 * made — the thing the owner actually asked for ("the admin/master admin need to approve the work
 * as done"). Calling the second one "Mark done for them" would tell an approver they are overruling
 * the researcher at the exact moment they are backing them up.
 *
 * ⚠ THIS IS A SECOND FUNCTION RATHER THAN A SECOND ARGUMENT TO `overrideActionLabel`, and the
 * reason is worth keeping: that function is passed by reference to `.map()` in
 * `e2e/task-authority-unit.spec.ts:118`, so an added positional parameter is silently fed the array
 * INDEX. `tsc` catches the type today; a looser signature would not have, and the wrong label would
 * have been chosen by whichever position the button happened to occupy.
 */
export function reviewActionLabel(next: TaskOverrideTarget): string {
  if (next === "DONE") return "Approve";
  // "Send back", not "Reject": the work is not refused, it is returned for more of it, and the row
  // it writes is plain IN_PROGRESS. Nor "Send for revision", which is this app's REVIEW LADDER's
  // wording (docs/PERMISSIONS.md) and carries a promise this screen cannot keep — that one takes
  // mandatory comments, and an assigned task has no column to put them in.
  return "Send back";
}

/** Which of the two vocabularies a row is in, decided by where the task stands and nothing else. */
export function taskDecisionLabel(from: TaskStatus, next: TaskOverrideTarget): string {
  return from === "SUBMITTED" ? reviewActionLabel(next) : overrideActionLabel(next);
}

export type TaskOverrideCopy = {
  title: string;
  /** What is about to happen, in one sentence. */
  body: string;
  /** The quieter second line: the consequences a reader must see BEFORE pressing, not after. */
  note: string;
  confirmLabel: string;
};

/**
 * The confirmation an override must pass through, as data rather than as JSX, so it can be
 * asserted (`e2e/task-authority-unit.spec.ts`) instead of eyeballed.
 *
 * THE QUOTA SENTENCE IS THE REASON THIS FUNCTION EXISTS. `update_task` fills `progressCount` to
 * `targetCount` whenever a task with a quota is marked DONE
 * (backend/app/api/routes/tasks.py, the `new_status == "DONE"` branch) so the rollups stop reading
 * "DONE — 0 of 10" for somebody who genuinely finished but never touched the progress box. Applied
 * to an OVERRIDE that is a silent rewrite of the one number this entire board exists to compare:
 * an admin pressing "Mark done for them" on a row reading "0 of 10 reported" turns it into "10 of
 * 10 reported", against a repository-derived figure that has not moved, and the gap the board was
 * built to surface disappears at the press of the button meant to be managing it. So the two
 * numbers are printed in the dialog, before the press.
 *
 * And the note says what the record will NOT hold, because "overridden by" is not a column: an
 * admin who expects the board to remember who pressed this would otherwise find out by reloading.
 */
export function overrideConfirmCopy({
  next,
  from,
  assigneeName,
  taskTitle,
  targetCount,
  progressCount,
  derivedCount
}: {
  next: TaskOverrideTarget;
  /**
   * Where the task stands NOW. Omitted by callers that predate the review state, which is why every
   * branch below still reads correctly without it — an absent `from` means "not a review decision".
   */
  from?: TaskStatus;
  assigneeName?: string | null;
  taskTitle: string;
  targetCount?: number | null;
  progressCount: number;
  /** What the repository can find. Printed beside the claim on an approval; null = not counted. */
  derivedCount?: number | null;
}): TaskOverrideCopy {
  const who = (assigneeName || "").trim() || "this person";
  const reported = progressCount ?? 0;

  if (from === "SUBMITTED") {
    // THE APPROVER'S TWO NUMBERS, SIDE BY SIDE, BEFORE THE DECISION AND NOT AFTER IT. The backend
    // moved the quota auto-fill from the approval to the SUBMISSION for exactly this reason: the
    // claim is already filled in by the time anybody is asked to agree with it, so an approver can
    // be shown "says 10 / the repository can find 2" at the moment the question is put to them.
    // Without the second number this dialog is a Yes/No about a sentence, which is how a board
    // built to catch work reported but never recorded ends up rubber-stamping exactly that.
    const claim = targetCount ? `${reported} of ${targetCount}` : `${reported}`;
    const found =
      derivedCount === null || derivedCount === undefined
        ? "The repository count is not available for this row, so this approval rests on their word alone."
        : `The repository can find ${derivedCount} inside this task's scope against their ${claim}.`;

    if (next === "DONE") {
      return {
        title: `Approve ${who}'s work on this task?`,
        body: `"${taskTitle}" is marked as finished by ${who} and is waiting on you. Approving records it as agreed and takes it off their list.`,
        // No quota warning on this branch, and its absence is deliberate: the fill already happened
        // when they submitted, so warning about a rewrite here would describe a write that is not
        // about to occur.
        note: `${found} Approving changes no reported figure — that was filled in when they handed it in. You can reopen the task afterwards.`,
        confirmLabel: reviewActionLabel(next)
      };
    }
    return {
      title: `Send ${who}'s task back?`,
      body: `"${taskTitle}" goes back to ${taskStatusText(next).toLowerCase()} and returns to ${who}'s list as work still to do.`,
      // THE ONE THING A SENT-BACK TASK CANNOT CARRY. There is no comment column on a task row, so
      // whatever the reason was travels by some other route or not at all — and an admin who
      // assumes the button delivers it would be sending silent rejections.
      note: `Nothing on the task records why, and ${who} is not told a reason — the row keeps only its status, so say why some other way. Their reported figure is left exactly as it is.`,
      confirmLabel: reviewActionLabel(next)
    };
  }
  // THE CONFIRM BUTTON IS THE BOARD'S BUTTON, WORD FOR WORD, ON BOTH BRANCHES. It restates the
  // press so a reader can check they clicked the one they meant — and, for the DONE branch, it is
  // the reason the label is not the bare "Mark done" that would read well here: that string is
  // `MyTaskCard.tsx:126`, the assignee's own report, and the last click of an override is the worst
  // possible place for the two acts to become the same three words.
  const confirmLabel = overrideActionLabel(next);

  if (next === "DONE") {
    const quota =
      targetCount && reported < targetCount
        ? ` Marking a task with a quota done fills the reported figure to the target, so "${reported} of ${targetCount}" becomes "${targetCount} of ${targetCount}" even though ${who} reported ${reported}.`
        : "";
    return {
      title: `Mark ${who}'s task done for them?`,
      body: `"${taskTitle}" will be recorded as finished. ${who} has not reported it finished — you are declaring it on their behalf.`,
      note: `The task stores only the status, so nothing on it will say an admin set it.${quota} You can reopen it afterwards; the reported figure does not come back.`,
      confirmLabel
    };
  }

  return {
    title: `Reopen ${who}'s task?`,
    body: `"${taskTitle}" goes back to ${taskStatusText(next).toLowerCase()} and its completion date is cleared.`,
    note: targetCount
      ? `The reported figure stays at ${reported} of ${targetCount}. Reopening does not undo a quota that was filled when the task was marked done — only ${who} can change what they reported.`
      : `The reported figure is left exactly as it is — only ${who} can change what they reported.`,
    confirmLabel
  };
}

/**
 * Records vs questionnaire sections — the one distinction an assignee has to make at a glance,
 * because the two kinds of work happen in completely different parts of the app.
 */
export function TaskKindBadge({ recordTypes, sections }: { recordTypes: string[]; sections: TaskSectionRef[] }) {
  const hasRecords = (recordTypes ?? []).length > 0;
  const hasSections = (sections ?? []).length > 0;
  if (!hasRecords && !hasSections) return null;
  const both = hasRecords && hasSections;
  const Icon: LucideIcon = hasSections && !hasRecords ? ClipboardList : Boxes;
  const text = both ? "Records + questionnaire" : hasSections ? "Questionnaire sections" : "Record documentation";
  return (
    <span className={cn(CHIP, "border-purple-200 bg-purple-50 text-purple-700")}>
      <Icon className="h-3.5 w-3.5" aria-hidden />
      {text}
    </span>
  );
}

export function DueBadge({ dueAt, overdue }: { dueAt?: string | null; overdue?: boolean }) {
  if (!dueAt) return null;
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-xs", overdue ? "font-semibold text-error-600" : "text-ink-500")}>
      <CalendarClock className="h-3.5 w-3.5" aria-hidden />
      Due {formatDate(dueAt)}
      {overdue ? " — overdue" : ""}
    </span>
  );
}

/** Name + role, the way every hierarchy-aware screen in the app writes a person. */
export function PersonLine({ user, className }: { user?: TaskUserBrief | null; className?: string }) {
  if (!user) return <span className={cn("text-sm text-ink-500", className)}>Unknown user</span>;
  return (
    <span className={cn("inline-flex flex-wrap items-baseline gap-x-2", className)}>
      <span className="font-medium text-ink-900">{user.name}</span>
      <span className="text-xs text-ink-500">{user.roleLabel ?? roleLabel(user.role)}</span>
    </span>
  );
}

/**
 * Every dimension of a scope, spelled out. Artisans collapse past four names — a task handed out for
 * twenty artisans is about the count, not the roster, and the full list is on the task itself.
 */
export function ScopeChips({
  recordTypeLabels,
  sections,
  artisans,
  targetCount,
  workshopTitle,
  maxArtisans = 4
}: {
  recordTypeLabels: string[];
  sections: TaskSectionRef[];
  artisans: TaskArtisanRef[];
  targetCount?: number | null;
  workshopTitle?: string | null;
  maxArtisans?: number;
}) {
  const shownArtisans = artisans.slice(0, maxArtisans);
  const hiddenArtisans = artisans.length - shownArtisans.length;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {workshopTitle ? <span className={cn(CHIP, "border-line-200 bg-surface-50 text-ink-700")}>{workshopTitle}</span> : null}
      {(recordTypeLabels ?? []).map((label) => (
        <span key={label} className={cn(CHIP, "border-purple-200 bg-purple-50 capitalize text-purple-700")}>
          {label}
        </span>
      ))}
      {targetCount ? (
        <span className={cn(CHIP, "border-purple-200 bg-purple-50 text-purple-700")}>Target {targetCount}</span>
      ) : null}
      {(sections ?? []).map((section) => (
        <span key={section.id} title={section.title} className={cn(CHIP, "border-line-200 bg-card text-ink-700")}>
          <ClipboardList className="h-3.5 w-3.5 text-purple-700" aria-hidden />
          Section {section.code}
        </span>
      ))}
      {shownArtisans.map((artisan) => (
        <span key={artisan.id} className={cn(CHIP, "border-line-200 bg-card text-ink-700")}>
          {artisan.name}
        </span>
      ))}
      {hiddenArtisans > 0 ? (
        <span className={cn(CHIP, "border-line-200 bg-card text-ink-500")}>+{hiddenArtisans} more artisans</span>
      ) : null}
      {artisans.length === 0 ? (
        <span className={cn(CHIP, "border-dashed border-line-200 bg-card text-ink-500")}>All artisans in scope</span>
      ) : null}
    </div>
  );
}

const GAP_TONE = {
  match: { className: "border-success-600/25 bg-success-100 text-success-600", Icon: CheckCircle2 },
  ahead: { className: "border-success-600/25 bg-success-100 text-success-600", Icon: CheckCircle2 },
  behind: { className: "border-amber-500/30 bg-amber-100 text-amber-800", Icon: TriangleAlert },
  idle: { className: "border-line-200 bg-surface-50 text-ink-500", Icon: Minus },
  unknown: { className: "border-line-200 bg-surface-50 text-ink-500", Icon: HelpCircle }
} as const;

/**
 * The reported-vs-derived verdict as a labelled, icon-bearing chip. Every tone carries an icon and a
 * sentence, so the judgement survives colour-blindness, greyscale printing and forced-colours mode —
 * and `w-fit` keeps it a chip rather than letting a grid stretch it into a full-width banner.
 */
export function GapChip({ reported, derived }: { reported: number; derived: number | null | undefined }) {
  const gap = progressGap(reported, derived);
  const tone = GAP_TONE[gap.tone];
  return (
    <span className={cn(CHIP, "w-fit max-w-full", tone.className)}>
      <tone.Icon className="h-3.5 w-3.5 shrink-0" aria-hidden />
      <span className="min-w-0 truncate">{gap.label}</span>
    </span>
  );
}

/**
 * The one bar in this file, and the reason it animates in CSS rather than in framer-motion.
 *
 * `transition-all` is a CSS transition, so BOTH reduced-motion switches reach it: the
 * `prefers-reduced-motion` media query and the app's own `:root[data-reduced-motion="true"]` rule
 * in globals.css each zero `transition-duration` and `transition-delay`. A framer `animate` on the
 * width would be an inline style neither rule can touch, and would need a JS branch nobody reading
 * this component would know to look for. The width is also never the only signal — every caller
 * prints the figure beside it — because a bar is exactly the kind of state a reduced-motion reader,
 * a greyscale printout and a forced-colours theme all lose at once.
 */
function Bar({
  value,
  denominator,
  className,
  size = "sm"
}: {
  value: number;
  denominator: number;
  className: string;
  size?: "sm" | "lg";
}) {
  const pct = denominator > 0 ? Math.min(100, Math.round((100 * value) / denominator)) : 0;
  const height = size === "lg" ? "h-3" : "h-2";
  return (
    <div className={cn("w-full overflow-hidden rounded-full bg-surface-50 ring-1 ring-inset ring-line-200", height)}>
      <div className={cn("rounded-full transition-all", height, className)} style={{ width: `${pct}%` }} />
    </div>
  );
}

/**
 * The gap made legible: what the assignee says, directly above what the repository can actually see,
 * on ONE shared scale so the two bars are comparable by length.
 *
 * When there is no target count the shared denominator is the larger of the two figures, which keeps
 * the comparison honest (the longer bar is genuinely the bigger number) without inventing a quota
 * that was never set.
 */
export function ProgressGapMeter({
  reported,
  derived,
  target,
  className
}: {
  reported: number;
  derived: number | null | undefined;
  target?: number | null;
  className?: string;
}) {
  const hasTarget = !!target && target > 0;
  const denominator = hasTarget ? (target as number) : Math.max(reported, derived ?? 0, 1);
  const suffix = hasTarget ? ` / ${target}` : "";
  return (
    <div className={cn("grid gap-2", className)}>
      <div className="grid grid-cols-[6.5rem_1fr_auto] items-center gap-x-3 gap-y-2">
        <span className="text-xs text-ink-500">Reported</span>
        <Bar value={reported} denominator={denominator} className="bg-purple-700" />
        <span className="text-xs font-semibold tabular-nums text-ink-900">
          {reported}
          {suffix}
        </span>
        <span className="text-xs text-ink-500">In repository</span>
        {derived === null || derived === undefined ? (
          <span className="text-xs text-ink-300">not counted for this page</span>
        ) : (
          <Bar value={derived} denominator={denominator} className="bg-ink-500" />
        )}
        <span className="text-xs font-semibold tabular-nums text-ink-900">
          {derived ?? "—"}
          {derived === null || derived === undefined ? "" : suffix}
        </span>
      </div>
      <GapChip reported={reported} derived={derived} />
      {!hasTarget ? (
        <p className="text-xs text-ink-300">No target count on this task — the bars compare the two figures to each other.</p>
      ) : null}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * THE HONEST SINGLE BAR — `effectivePercent` drawn, `progressSource` captioned.
 *
 * `ProgressGapMeter` above answers an ADMIN's question ("does what they say match what is there?")
 * and needs two bars to do it. This answers the ASSIGNEE's ("how far along am I?"), which is one
 * number — but only where an honest one exists, and the whole point of this block is the branch
 * where it does not.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the bar is measuring, said in words, because two bars of identical length can mean entirely
 * different things: "6 of 24 recorded" was counted by the database and "6 of 24 reported" was typed
 * by the person the bar is about. A reader who cannot tell them apart will read the second as the
 * first — which is the belief the accountability board exists to correct, one screen later.
 */
export function progressSourceNote(source: TaskProgressSource | null): string {
  if (source === "derived") return "Counted from the repository — this moves on its own as records are saved.";
  if (source === "reported") return "Self-reported — this only moves when the reported figure is changed.";
  if (source === "status") return "Read from the task's status, not counted from records.";
  return "Nothing on this task is countable and no target was set, so there is no honest bar to draw.";
}

/**
 * One task's progress, from the server's `effectivePercent` / `progressSource` / `progressLabel`.
 *
 * **A NULL PERCENT RENDERS NO BAR.** Not a grey bar, not a 0% bar, not a bar with a question mark:
 * nothing. "Food + collect TA details" has no measurable scope and no quota, and an empty track
 * reads as "this person has done none of it" — an accusation manufactured out of an absence. The
 * label and the state pill are what that task gets, and they are enough.
 */
export function EffectiveProgressBar({
  percent,
  label,
  source,
  note,
  ariaLabel,
  className,
  size = "sm"
}: {
  percent: number | null;
  label: string;
  source: TaskProgressSource | null;
  /**
   * Overrides the sentence under the bar; pass `null` to render none because the caller prints a
   * more specific one of its own. Left off, it is `progressSourceNote(source)` — and the default is
   * the point: a bar whose caller forgets to say what it measured still says what it measured.
   */
  note?: string | null;
  /** The accessible name of the bar itself — the visible label is its `aria-valuetext`. */
  ariaLabel?: string;
  className?: string;
  size?: "sm" | "lg";
}) {
  const measured = source === "derived";
  // `Number.isFinite`, not `!== null`, and the difference is a bar drawn at `NaN%`. A payload that
  // predates `effectivePercent` hands this `undefined`, `Math.round(undefined)` is NaN, and
  // `width: "NaN%"` is ignored by the browser — so the track would render EMPTY and read as "none
  // of it done" on exactly the rows whose progress the server never sent. Absent is the same answer
  // as unmeasurable here: no bar.
  const clamped = Number.isFinite(percent as number) ? Math.max(0, Math.min(100, Math.round(percent as number))) : null;
  return (
    <div className={cn("grid gap-1.5", className)}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className={cn("text-xs font-medium text-ink-700", size === "lg" && "text-sm")}>{label}</span>
        {clamped === null ? null : (
          <span className={cn("text-xs font-semibold tabular-nums text-ink-900", size === "lg" && "text-base")}>
            {clamped}%
          </span>
        )}
      </div>
      {clamped === null ? null : (
        <div
          role="progressbar"
          aria-label={ariaLabel ?? "Progress"}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={clamped}
          // The percentage alone announces "62 percent" of an unnamed thing. The sentence that is
          // already on screen is the honest reading, so a screen reader gets exactly what a sighted
          // reader gets rather than the bare number.
          aria-valuetext={label}
        >
          <Bar
            value={clamped}
            denominator={100}
            size={size}
            // Derived progress is the brand purple because it is the thing the app did for them;
            // everything else is the quieter ink, so a glance down a list separates counted from
            // claimed before a single word is read. The words are still there either way.
            className={measured ? "bg-purple-700" : "bg-ink-500"}
          />
        </div>
      )}
      {note === null ? null : <p className="text-xs leading-5 text-ink-500">{note ?? progressSourceNote(source)}</p>}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * THE REVIEW STATE, SAID TO THE PERSON WHO SUBMITTED IT.
 *
 * The owner's sentence was "tell them that it is currently under review", and the failure mode it
 * guards against is precise: a researcher presses "Mark done", the task does NOT leave their list,
 * and with no explanation on screen the only available reading is that the press failed. They press
 * it again. Then they email somebody. So the card has to say three things in the order a worried
 * person asks them — it worked, it is with somebody else now, and there is nothing further for you
 * to do — and it must not look like a rejection while doing it.
 * ──────────────────────────────────────────────────────────────────────────── */

export type ReviewNoticeCopy = { title: string; body: string; action: string };

/**
 * Exported as data rather than written into the JSX so the wording can be asserted
 * (`e2e/task-progress-unit.spec.ts`) — this repository has no React renderer in its
 * devDependencies, so a sentence inside a component is only ever checked by somebody looking at a
 * screen, and this is the sentence that decides whether a researcher trusts the button.
 */
export function reviewNoticeCopy(assignerName?: string | null): ReviewNoticeCopy {
  const who = (assignerName || "").trim();
  return {
    title: "Handed in — under review",
    // "Waiting on" rather than "not accepted": the delay belongs to the reviewer, and the sentence
    // has to put it there. Naming the person who assigned it where we know them turns an anonymous
    // wait into somebody a researcher can actually go and ask.
    body: `You marked this done and it has been recorded. ${
      who ? `${who} or another admin` : "An admin or the master admin"
    } has to approve it before it is finished, so it stays on your list until then.`,
    // The third sentence exists because the second one, alone, reads as an instruction to wait and
    // do something. There is nothing to do, and saying so is the difference between a queue and a
    // problem.
    action: "Nothing more is needed from you unless it comes back."
  };
}

/** A single headline number. No plot, so no hover layer — the number IS the content. */
export function StatTile({
  label,
  value,
  hint,
  tone = "neutral"
}: {
  label: string;
  value: number | string;
  hint?: string;
  tone?: "neutral" | "warn" | "good";
}) {
  const valueTone =
    tone === "warn" && value !== 0 ? "text-error-600" : tone === "good" && value !== 0 ? "text-success-600" : "text-ink-900";
  return (
    <div className="rounded-md border border-line-200 bg-card px-4 py-3">
      <div className={cn("font-display text-2xl font-bold tabular-nums", valueTone)}>{value}</div>
      <div className="mt-0.5 text-xs font-medium text-ink-500">{label}</div>
      {hint ? <div className="mt-0.5 text-xs text-ink-300">{hint}</div> : null}
    </div>
  );
}

/** Slim single-value bar used where the two-number meter would be overkill (batch rollups). */
export function PercentBar({ percent, label }: { percent: number; label?: string }) {
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  return (
    <div className="grid gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs text-ink-500">{label ?? "Progress"}</span>
        <span className="text-xs font-semibold tabular-nums text-ink-900">{clamped}%</span>
      </div>
      <Bar value={clamped} denominator={100} className="bg-purple-700" />
    </div>
  );
}
