"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
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

const CHIP = "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium";

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
        <RequiredMark when={required} />
      </span>
      <div role="group" aria-labelledby={id}>
        {children}
      </div>
      {hint}
    </div>
  );
}

const STATUS_TONE: Record<TaskStatus, string> = {
  OPEN: "border-line-200 bg-surface-50 text-ink-500",
  IN_PROGRESS: "border-amber-500/30 bg-amber-100 text-amber-800",
  SUBMITTED: "border-purple-200 bg-purple-50 text-purple-700",
  DONE: "border-success-600/25 bg-success-100 text-success-600",
  CANCELLED: "border-error-600/25 bg-error-100 text-red-800"
};

const STATUS_TEXT: Record<TaskStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  SUBMITTED: "Under review",
  DONE: "Done",
  CANCELLED: "Cancelled"
};

export function TaskStatusPill({ status, label }: { status: TaskStatus; label?: string | null }) {
  return (
    <span className={cn(CHIP, STATUS_TONE[status] ?? STATUS_TONE.OPEN)}>{label?.trim() || taskStatusText(status)}</span>
  );
}

export function taskStatusText(status: TaskStatus | string): string {
  return STATUS_TEXT[status as TaskStatus] ?? String(status);
}

export function overrideTargets(status: TaskStatus): TaskOverrideTarget[] {
  if (status === "DONE") return ["IN_PROGRESS", "OPEN"];
  if (status === "SUBMITTED") return ["DONE", "IN_PROGRESS"];
  if (status === "OPEN" || status === "IN_PROGRESS") return ["DONE"];
  return [];
}

export function overrideActionLabel(next: TaskOverrideTarget): string {
  if (next === "DONE") return "Mark done for them";
  return `Back to ${taskStatusText(next).toLowerCase()}`;
}

export function reviewActionLabel(next: TaskOverrideTarget): string {
  if (next === "DONE") return "Approve";
  return "Send back";
}

export function taskDecisionLabel(from: TaskStatus, next: TaskOverrideTarget): string {
  return from === "SUBMITTED" ? reviewActionLabel(next) : overrideActionLabel(next);
}

export type TaskOverrideCopy = {
  title: string;
  body: string;
  note: string;
  confirmLabel: string;
};

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
  from?: TaskStatus;
  assigneeName?: string | null;
  taskTitle: string;
  targetCount?: number | null;
  progressCount: number;
  derivedCount?: number | null;
}): TaskOverrideCopy {
  const who = (assigneeName || "").trim() || "this person";
  const reported = progressCount ?? 0;

  if (from === "SUBMITTED") {
    const claim = targetCount ? `${reported} of ${targetCount}` : `${reported}`;
    const found =
      derivedCount === null || derivedCount === undefined
        ? "No recorded count — based on their report."
        : `${derivedCount} recorded, against their ${claim}.`;

    if (next === "DONE") {
      return {
        title: `Approve ${who}'s work on this task?`,
        body: `${who} marked "${taskTitle}" finished.`,
        note: `${found} You can reopen it later.`,
        confirmLabel: reviewActionLabel(next)
      };
    }
    return {
      title: `Send ${who}'s task back?`,
      body: `"${taskTitle}" returns to ${who} as ${taskStatusText(next).toLowerCase()}.`,
      note: `${who} won’t see a reason — tell them why.`,
      confirmLabel: reviewActionLabel(next)
    };
  }
  const confirmLabel = overrideActionLabel(next);

  if (next === "DONE") {
    const quota =
      targetCount && reported < targetCount
        ? ` Their reported ${reported} of ${targetCount} becomes ${targetCount} of ${targetCount}.`
        : "";
    return {
      title: `Mark ${who}'s task done for them?`,
      body: `${who} hasn’t reported "${taskTitle}" finished — you’re marking it on their behalf.`,
      note: `It won’t show that an admin did this.${quota}`,
      confirmLabel
    };
  }

  return {
    title: `Reopen ${who}'s task?`,
    body: `"${taskTitle}" returns to ${taskStatusText(next).toLowerCase()} and its completion date is cleared.`,
    note: targetCount
      ? `Reported figure stays at ${reported} of ${targetCount} — reopening doesn’t undo the quota fill.`
      : `Reported figure stays ${reported}.`,
    confirmLabel
  };
}

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

export function PersonLine({ user, className }: { user?: TaskUserBrief | null; className?: string }) {
  if (!user) return <span className={cn("text-sm text-ink-500", className)}>Unknown user</span>;
  return (
    <span className={cn("inline-flex flex-wrap items-baseline gap-x-2", className)}>
      <span className="font-medium text-ink-900">{user.name}</span>
      <span className="text-xs text-ink-500">{user.roleLabel ?? roleLabel(user.role)}</span>
    </span>
  );
}

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

function Bar({
  value,
  denominator,
  className,
  size = "sm"
}: {
  value: number;
  denominator: number;
  className?: string;
  size?: "sm" | "lg";
}) {
  const pct = denominator > 0 ? Math.min(100, Math.round((100 * value) / denominator)) : 0;
  const height = size === "lg" ? "h-3" : "h-2";
  return (
    <ProgressBar percent={pct} className={height} fillClassName={cn("transition-all", className)} />
  );
}

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
        <Bar value={reported} denominator={denominator} />
        <span className="text-xs font-semibold tabular-nums text-ink-900">
          {reported}
          {suffix}
        </span>
        <span className="text-xs text-ink-500">Recorded</span>
        {derived === null || derived === undefined ? (
          <span className="text-xs text-ink-500">not counted for this page</span>
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
        <p className="text-xs text-ink-500">No target set.</p>
      ) : null}
    </div>
  );
}

export function progressSourceNote(source: TaskProgressSource | null): string {
  if (source === "derived") return "Counted automatically from records.";
  if (source === "reported") return "Self-reported.";
  if (source === "status") return "From the task status.";
  return "No progress yet.";
}

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
  note?: string | null;
  ariaLabel?: string;
  className?: string;
  size?: "sm" | "lg";
}) {
  const measured = source === "derived";
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
          aria-valuetext={label}
        >
          <Bar
            value={clamped}
            denominator={100}
            size={size}
            className={measured ? "bg-purple-700" : "bg-ink-500"}
          />
        </div>
      )}
      {note === null ? null : <p className="text-xs leading-5 text-ink-500">{note ?? progressSourceNote(source)}</p>}
    </div>
  );
}

export type ReviewNoticeCopy = { title: string; body: string; action: string };

export function reviewNoticeCopy(assignerName?: string | null): ReviewNoticeCopy {
  const who = (assignerName || "").trim();
  return {
    title: "Handed in — under review",
    body: `Handed in — it stays on your list until ${who || "an admin"} approves it.`,
    action: "Nothing to do."
  };
}

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
      {hint ? <div className="mt-0.5 text-xs text-ink-500">{hint}</div> : null}
    </div>
  );
}

export function PercentBar({ percent, label }: { percent: number; label?: string }) {
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  return (
    <div className="grid gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs text-ink-500">{label ?? "Progress"}</span>
        <span className="text-xs font-semibold tabular-nums text-ink-900">{clamped}%</span>
      </div>
      <Bar value={clamped} denominator={100} />
    </div>
  );
}
