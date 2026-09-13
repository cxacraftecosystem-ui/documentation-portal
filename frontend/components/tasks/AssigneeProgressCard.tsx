"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CalendarClock, CheckCircle2, ClipboardList, RefreshCw, ShieldCheck, TriangleAlert } from "lucide-react";

import { EffectiveProgressBar, StatTile } from "@/components/tasks/TaskPrimitives";
import type { TaskSummary } from "@/components/tasks/types";
import { apiFetch, buildQuery } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { cn } from "@/lib/utils";

/**
 * THE FULL-WIDTH CARD AT THE TOP OF THE ASSIGNEE'S SCREEN — "a horizontal width spanning card on
 * the top with the remaining tasks and progress bar", the owner's words, 2026-09-14.
 *
 * WHAT IT IS FOR. The task list below it is a page of twelve cards, each one a scope, a due date and
 * two progress figures. That is the right shape for doing the work and the wrong shape for the
 * question a researcher opens the screen with: how much is left, and is any of it late. This card
 * answers that in four numbers and one bar, and everything under it stays as it was.
 *
 * ═══ THE THREE RULES THIS COMPONENT EXISTS TO HOLD ═══
 *
 * 1. IT IS DRAWN FROM `GET /tasks/summary`, NEVER FROM THE PAGE OF TASKS ON SCREEN. The list is
 *    paged twelve at a time; a card that counted those rows would tell anyone with thirteen tasks
 *    that they have fewer than they do. Under-reporting outstanding work is the single failure this
 *    card was asked for in order to prevent, so it must not be built out of the one data source that
 *    guarantees it. The endpoint scans the caller's whole (bounded) list and reports `truncated`
 *    when even that window was hit — which is printed, not swallowed.
 *
 * 2. THE BAR REFUSES TO EXIST RATHER THAN LIE. `summaryBar()` below is the whole argument: a
 *    percentage is drawn only where something real is behind it, and where nothing is, the card
 *    says so in a sentence and draws no track at all. An empty bar is not a neutral graphic — it
 *    reads as "this person has done none of it", which about a task with nothing countable in it is
 *    an accusation the app manufactured out of an absence.
 *
 * 3. IT SAYS WHICH MEASURE IT IS SHOWING. "8 of 12 recorded" and "you reported 8 of 12" are two
 *    completely different claims that draw the identical rectangle, and the second one silently
 *    becoming the first is exactly the belief the accountability board exists to correct one screen
 *    later. `measuredCount` is on the wire for this reason and is printed under the bar every time.
 *
 * WHY IT FETCHES FOR ITSELF. So that mounting it is one line in the page that owns the list, with no
 * new state, no new effect and no new error branch there. The trade is that it does not know when
 * the list around it changes — hence `refreshToken`: bump it after a status write and the card
 * re-reads. Mounted without one it is still correct on arrival and after its own Refresh button,
 * which is why the button is not optional chrome.
 */

/** What to draw, and what to say about it. Pure, so `e2e/task-progress-unit.spec.ts` can hold it. */
export type SummaryBar = {
  /** `null` means DRAW NO BAR — see rule 2 above. */
  percent: number | null;
  /** The sentence under the bar, or in its place when there is none. */
  caption: string;
  /** How much of that percentage was counted from records rather than claimed. */
  measured: "all" | "some" | "none";
  /** Whole-card qualifications: a scan window that was hit, a derivation that was declined. */
  caveats: string[];
};

/**
 * THE HONESTY RULE, AS ONE FUNCTION.
 *
 * `percentComplete` arrives already averaged across the caller's tasks, and the server is explicit
 * about what it did with the unmeasurable ones: it stood a 0 (or a 100, for work handed in or
 * approved) in for them so the average has a number for every row. That substitution is correct for
 * an average and dangerous as a bar, because the two ways of arriving at 0% are indistinguishable
 * once drawn: "every task I can count is at zero" and "nothing you have is countable" are the same
 * empty rectangle, and only the first is a fact about the person.
 *
 * So the bar is drawn when — and only when — at least one figure behind it is real:
 *   - something was measured from the repository (`measuredCount > 0`), including a genuine 0%,
 *     which is worth drawing precisely because it says "this is being counted, and it is zero"; or
 *   - the average is above zero, which cannot happen without a reported figure, a submission or an
 *     approval underneath it.
 * Otherwise: no bar, and a sentence saying why — never an empty track passed off as a measurement.
 *
 * A withdrawn task is not work and the server already keeps CANCELLED out of the average; the same
 * subtraction happens here so the card's own arithmetic cannot disagree with the bar above it.
 */
export function summaryBar(summary: TaskSummary): SummaryBar {
  const active = Math.max(0, summary.taskCount - summary.cancelledCount);
  const caveats: string[] = [];
  if (summary.truncated) {
    caveats.push(
      "You have more tasks than this summary can scan in one go, so these counts are a floor rather than a total."
    );
  }
  if (summary.derivationSkipped) {
    // The server declined to count records for a list this long. Not a failure and not hidden: the
    // alternative is a bar that looks measured and is not, which is the one outcome ruled out above.
    caveats.push(
      "You have too many tasks for the repository counts to be worked out in one go, so nothing here was counted from records."
    );
  }

  if (summary.taskCount === 0) {
    return { percent: null, caption: "No tasks have been assigned to you yet.", measured: "none", caveats };
  }
  if (active === 0) {
    return {
      percent: null,
      caption: "Every task assigned to you has been withdrawn, so there is nothing left to measure.",
      measured: "none",
      caveats
    };
  }

  const measured: SummaryBar["measured"] =
    summary.measuredCount === 0 ? "none" : summary.measuredCount >= active ? "all" : "some";

  if (summary.measuredCount === 0 && summary.percentComplete === 0) {
    return {
      percent: null,
      // Both halves are said because the card cannot tell which one is true from this payload, and
      // guessing would put one of two different explanations on screen as if it were certain.
      caption:
        "Nothing has been counted from the repository yet and nothing has been reported against a target, so there is no honest bar to draw. The counts above are exact.",
      measured,
      caveats
    };
  }

  const caption =
    measured === "all"
      ? `All ${active} of your tasks are counted from the repository — this bar moves on its own as you record.`
      : measured === "some"
        ? `${summary.measuredCount} of ${active} tasks counted from the repository; the rest from what you reported or handed in.`
        : "Based on what you have reported and handed in — none of these tasks could be counted from records.";

  return { percent: summary.percentComplete, caption, measured, caveats };
}

/**
 * The one sentence about deadlines, assembled so an empty one is genuinely empty rather than a
 * cheerful "0 overdue" nobody asked for. Overdue leads when it exists — it is the only part of this
 * card that is an emergency.
 */
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
    parts.push(`${summary.dueSoonCount} due within the next two days`);
  }
  if (summary.nextDueAt) parts.push(`next due ${formatDate(summary.nextDueAt)}`);
  if (!parts.length) return null;
  // Sentence case, one line, no bullet list: this is a status line, not a second list of tasks.
  return `${parts.join(" · ")}.`;
}

export function AssigneeProgressCard({
  workshopId,
  refreshToken,
  className
}: {
  /** Scope the card to one workshop. Omit for the lifetime picture. */
  workshopId?: string | null;
  /** Bump to re-read after the list around it changes. */
  refreshToken?: number;
  className?: string;
}) {
  const [summary, setSummary] = useState<TaskSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /**
   * THE GENERATION COUNTER, not an AbortSignal — `apiFetch` takes none, and what matters here is
   * ignoring the late answer rather than cancelling it. Two calls are genuinely in flight whenever
   * a status write bumps `refreshToken` while a scope change is still resolving, and the older one
   * finishing second would repaint the card with counts for a workshop the reader has left.
   */
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
      // The spinner belongs to the NEWEST request, so a superseded call must not switch it off
      // underneath the one that is still running.
      if (generation === currentLoad.current) setLoading(false);
    }
  }, [workshopId]);

  useEffect(() => {
    load();
  }, [load, refreshToken]);

  // FIRST PAINT IS A HEIGHT, NOT A HOLE. The list below renders as soon as its own call returns, so
  // a card that occupied no space until its fetch landed would shove the whole page down under the
  // reader's cursor a moment after they started reading it.
  if (loading && !summary) {
    return (
      <section className={cn("panel mb-4 w-full p-4 text-sm text-ink-500", className)} aria-busy="true">
        Loading your progress...
      </section>
    );
  }

  if (error && !summary) {
    // SAID, NOT SWALLOWED. A card that simply failed to appear is indistinguishable from a card that
    // decided there was nothing to show, and the second reading is the dangerous one on a screen
    // whose whole job is telling somebody how much work they still owe.
    return (
      <section className={cn("panel mb-4 w-full p-4", className)}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-ink-700">
            Your progress summary could not be loaded, so the numbers for it are not on screen. The tasks below are
            unaffected.
          </p>
          <button type="button" className="field-button-secondary" onClick={load}>
            <RefreshCw className="h-4 w-4" aria-hidden />
            Try again
          </button>
        </div>
      </section>
    );
  }

  // Nothing assigned at all: the list below already renders a full `EmptyState` saying so in more
  // useful words, and a card of four zeroes above it would be the same non-news twice.
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
              <ClipboardList className="mr-2 inline h-4 w-4 text-purple-700" aria-hidden />
              Where you stand
            </h2>
            <button
              type="button"
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-purple-700"
              onClick={load}
              disabled={loading}
            >
              <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} aria-hidden />
              {loading ? "Refreshing..." : "Refresh"}
            </button>
          </div>

          {/*
            FOUR NUMBERS, AND THE ORDER IS THE ARGUMENT. Remaining leads because it is the owner's
            "remaining tasks" and the thing a researcher opens this screen to find out. Under review
            comes second because it is the new state and the one that would otherwise be read as
            "still to do" — it is work that has LEFT them. Overdue is third, loud only when it is
            non-zero (`StatTile` keeps a zero in ink rather than in red, so an on-time list is not
            decorated with a warning colour). Approved is last: it is the only one that is over.
          */}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <StatTile label="Remaining" value={summary.remainingCount} hint="still to do" />
            <StatTile label="Under review" value={summary.awaitingReviewCount} hint="waiting on an admin" />
            <StatTile label="Overdue" value={summary.overdueCount} tone="warn" hint="past the due date" />
            <StatTile label="Approved" value={summary.approvedCount} tone="good" hint="signed off" />
          </div>

          {summary.awaitingReviewCount > 0 ? (
            // THE SENTENCE THAT STOPS THE SECOND PRESS. Without it, `remainingCount` drops when work
            // is handed in while the cards stay on the list, and the only available reading of that
            // is that something went wrong.
            <p className="flex items-start gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-xs leading-5 text-purple-700">
              <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                {summary.awaitingReviewCount === 1
                  ? "One task is under review. It stays on your list until an admin approves it — there is nothing more for you to do on it."
                  : `${summary.awaitingReviewCount} tasks are under review. They stay on your list until an admin approves them — there is nothing more for you to do on them.`}
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
                // Purple only where at least one figure behind the bar was counted from records —
                // the same colour rule as a single task's bar, so the two screens agree about what
                // purple means. `measured: "none"` gets the quieter ink.
                source={bar.measured === "none" ? "reported" : "derived"}
                // The generic per-task sentence is suppressed because `bar.caption` says the same
                // thing with this person's real numbers in it, and two sentences making one point
                // is how a reader learns to skip both.
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
