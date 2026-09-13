import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { dueSentence, summaryBar } from "@/components/tasks/AssigneeProgressCard";
import {
  overrideConfirmCopy,
  overrideTargets,
  progressSourceNote,
  reviewActionLabel,
  reviewNoticeCopy,
  taskDecisionLabel
} from "@/components/tasks/TaskPrimitives";
import type { TaskSummary } from "@/components/tasks/types";

/**
 * THE ASSIGNEE'S PROGRESS CARD AND THE REVIEW STATE — what the numbers are allowed to claim, and
 * what a researcher is told when their "Mark done" does not take the task off their list.
 *
 * WHAT THE FEATURE IS (owner, 2026-09-14). A full-width card at the top of the assignee's screen
 * carrying the remaining count and a progress bar; a researcher's DONE becoming "under review" until
 * an admin approves it, with the task staying visible until then; and measurable scopes advancing on
 * their own as records land.
 *
 * WHY THIS SPEC NEVER OPENS A BROWSER. Reproducing any of it on screen needs a real session, a task
 * in each of five states and an admin willing to press Approve on somebody's actual fieldwork. This
 * repository has no React renderer in its devDependencies, so — exactly as `record-pickers-unit`,
 * `access-roster-unit` and `task-authority-unit` do beside it — the judgements were lifted out of
 * the JSX into exported functions a test can stand in front of, and the handful of claims that are
 * genuinely about the JSX are asserted against the source text.
 *
 * THE RULE THIS FILE IS REALLY FOR is the one an edit will erode first: A BAR MAY ONLY BE DRAWN
 * WHERE A REAL FIGURE IS BEHIND IT. Every other assertion here protects a sentence; this one
 * protects a rectangle that means "you have done none of it" and is very easy to draw by accident.
 */

/** A summary with nothing in it; each test overrides only the fields it is actually about. */
function summary(over: Partial<TaskSummary> = {}): TaskSummary {
  return {
    assignee: null,
    workshopId: null,
    taskCount: 0,
    statusCounts: { OPEN: 0, IN_PROGRESS: 0, SUBMITTED: 0, DONE: 0, CANCELLED: 0 },
    remainingCount: 0,
    awaitingReviewCount: 0,
    outstandingCount: 0,
    approvedCount: 0,
    cancelledCount: 0,
    overdueCount: 0,
    dueSoonCount: 0,
    nextDueAt: null,
    percentComplete: 0,
    measuredCount: 0,
    derivationSkipped: false,
    truncated: false,
    ...over
  };
}

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

test.describe("the bar refuses to exist rather than lie", () => {
  test("nobody has been assigned anything: no bar, and it says so", () => {
    const bar = summaryBar(summary());
    expect(bar.percent).toBeNull();
    expect(bar.caption).toContain("No tasks");
  });

  test("every task withdrawn: no bar, because a withdrawal is not work anybody failed to do", () => {
    // The server already keeps CANCELLED out of its average; the card subtracts it here too so the
    // two cannot disagree, and an admin tidying up their own mis-assignments cannot drag somebody's
    // bar down by doing it.
    const bar = summaryBar(summary({ taskCount: 3, cancelledCount: 3 }));
    expect(bar.percent).toBeNull();
    expect(bar.caption).toContain("withdrawn");
  });

  test("THE FAKE ZERO: nothing counted and nothing reported draws NO BAR", () => {
    // The defect this whole function exists to prevent. `percentComplete` is an average in which
    // the server stands a 0 in for every task that has no honest percentage — so "every task I can
    // measure is at zero" and "nothing you have is measurable" arrive as the same 0, and drawing it
    // would pick the first reading and put it on screen as a fact about the person.
    const bar = summaryBar(summary({ taskCount: 3, remainingCount: 3, percentComplete: 0, measuredCount: 0 }));
    expect(bar.percent).toBeNull();
    expect(bar.caption).toContain("no honest bar");
    // And it must not leave the reader thinking the COUNTS are unreliable too — those are exact.
    expect(bar.caption).toContain("exact");
  });

  test("AN HONEST ZERO IS DRAWN: something was counted, and what was counted is nothing yet", () => {
    // The mirror image, and the reason the rule is not simply "hide the bar at 0%". A measured 0%
    // says "this is being counted and it has not started", which is a real and useful state — the
    // task's scope is countable, the count is running, the number is zero.
    const bar = summaryBar(summary({ taskCount: 2, remainingCount: 2, percentComplete: 0, measuredCount: 1 }));
    expect(bar.percent).toBe(0);
    expect(bar.measured).toBe("some");
  });

  test("a percentage above zero is always drawn, even with nothing measured", () => {
    // It cannot be above zero without a reported figure, a submission or an approval underneath it,
    // so there is something real to draw — it just has to be labelled as claimed rather than counted.
    const bar = summaryBar(summary({ taskCount: 4, percentComplete: 50, measuredCount: 0, approvedCount: 2 }));
    expect(bar.percent).toBe(50);
    expect(bar.measured).toBe("none");
    expect(bar.caption).toContain("reported");
  });
});

test.describe("the caption says WHICH measure the bar is showing", () => {
  test('"8 of 12 recorded" and "you reported 8 of 12" never come out as the same sentence', () => {
    // The owner's distinction, and the one a reader will otherwise collapse: two identical
    // rectangles, two completely different claims. The per-task note and the card's caption both
    // have to name the source.
    expect(progressSourceNote("derived")).not.toBe(progressSourceNote("reported"));
    expect(progressSourceNote("derived")).toContain("repository");
    expect(progressSourceNote("reported")).toContain("Self-reported");
  });

  test("the automatic measure says it is automatic, because that is the whole feature", () => {
    // "should progress automatically as they record for more and more artisans" — a researcher who
    // does not know the bar moves on its own will go on typing into the reported box instead.
    expect(progressSourceNote("derived")).toMatch(/on its own|automatic/i);
  });

  test("no source at all explains the ABSENCE of a bar rather than describing one", () => {
    expect(progressSourceNote(null)).toContain("no honest bar");
  });

  test("all measured, some measured and none measured are three different sentences", () => {
    const all = summaryBar(summary({ taskCount: 2, percentComplete: 40, measuredCount: 2 }));
    const some = summaryBar(summary({ taskCount: 4, percentComplete: 40, measuredCount: 2 }));
    const none = summaryBar(summary({ taskCount: 4, percentComplete: 40, measuredCount: 0 }));
    expect([all.measured, some.measured, none.measured]).toEqual(["all", "some", "none"]);
    expect(new Set([all.caption, some.caption, none.caption]).size).toBe(3);
    // The partly-measured one has to print BOTH numbers, or "counted from the repository" reads as
    // a claim about the whole bar.
    expect(some.caption).toContain("2 of 4");
  });

  test("a cancelled task is out of the denominator the caption quotes", () => {
    // 5 rows, 1 withdrawn, 4 measured — "4 of 4", never "4 of 5", which would read as one task
    // mysteriously uncounted.
    const bar = summaryBar(summary({ taskCount: 5, cancelledCount: 1, percentComplete: 60, measuredCount: 4 }));
    expect(bar.measured).toBe("all");
    expect(bar.caption).toContain("All 4");
  });
});

test.describe("what the card admits it does not know", () => {
  test("a truncated scan says the counts are a floor, not a total", () => {
    // Rule 10 of the frontend contract: a list that quietly stops is indistinguishable from a place
    // with no records. Here it would be worse — the number it stops short on is how much work
    // somebody still owes.
    const bar = summaryBar(summary({ taskCount: 2000, percentComplete: 10, measuredCount: 5, truncated: true }));
    expect(bar.caveats.join(" ")).toContain("floor");
  });

  test("declined derivation is stated, not quietly redrawn as a measured bar", () => {
    const bar = summaryBar(summary({ taskCount: 300, percentComplete: 20, measuredCount: 0, derivationSkipped: true }));
    expect(bar.caveats.join(" ")).toContain("counted from records");
  });

  test("a caveat never invents the server's limit as a number", () => {
    // `DERIVED_TASK_LIMIT` is the server's to change and is not on the wire. Printing "150" here
    // would be a cap the client did not read, which goes stale silently — the same rule that keeps
    // `maxItems` from being printed where it was not declared.
    const bar = summaryBar(summary({ taskCount: 300, percentComplete: 20, derivationSkipped: true, truncated: true }));
    expect(bar.caveats.join(" ")).not.toMatch(/\b\d{2,}\b/);
  });

  test("both caveats can be true at once and both are said", () => {
    const bar = summaryBar(summary({ taskCount: 2000, percentComplete: 5, truncated: true, derivationSkipped: true }));
    expect(bar.caveats).toHaveLength(2);
  });
});

test.describe("the due line", () => {
  test("a list with no dates and nothing late earns no sentence at all", () => {
    // "0 overdue" is not reassurance, it is noise on a card whose job is the two numbers above it.
    expect(dueSentence(summary({ taskCount: 3, remainingCount: 3 }))).toBeNull();
  });

  test("overdue leads, because it is the only emergency on this card", () => {
    const line = dueSentence(summary({ overdueCount: 2, dueSoonCount: 1, nextDueAt: "2026-09-20T00:00:00Z" }));
    expect(line?.startsWith("2 tasks are past")).toBe(true);
  });

  test("one overdue task is singular in all three of its words", () => {
    // "1 tasks are past their due date" is the kind of sentence that makes a reader distrust the
    // number in front of it.
    expect(dueSentence(summary({ overdueCount: 1 }))).toBe("1 task is past its due date.");
  });

  test("a next due date is named without the already-overdue ones being counted twice", () => {
    // The server keeps overdue rows out of `nextDueAt` deliberately: a "next due" in the past
    // reports one emergency under two headings and hides the real next deadline behind it.
    const line = dueSentence(summary({ dueSoonCount: 1, nextDueAt: "2026-09-20T00:00:00Z" }));
    expect(line).toContain("next due");
    expect(line).not.toContain("past");
  });
});

test.describe("the review decision — approving is not the same act as overriding", () => {
  test("a submitted task offers approve and send back, and nothing else", () => {
    expect(overrideTargets("SUBMITTED")).toEqual(["DONE", "IN_PROGRESS"]);
  });

  test("OPEN is deliberately NOT offered as a way back from a submission", () => {
    // They demonstrably started — they handed it in. Restoring "not started" would be the board
    // contradicting a fact it is looking at. A DONE task still offers both ways back, because there
    // "never started" and "half done" are genuinely different answers about somebody's week.
    expect(overrideTargets("SUBMITTED")).not.toContain("OPEN");
    expect(overrideTargets("DONE")).toContain("OPEN");
  });

  test("SUBMITTED is never a destination — an admin does not hand work in for somebody", () => {
    const everyOffer = (["OPEN", "IN_PROGRESS", "SUBMITTED", "DONE", "CANCELLED"] as const).flatMap(overrideTargets);
    expect(everyOffer).not.toContain("SUBMITTED");
    expect(everyOffer).not.toContain("CANCELLED");
  });

  test("the approve button does not borrow the override's words", () => {
    // Same PATCH, two acts. "Mark done for them" tells an approver they are overruling the
    // researcher at the exact moment they are agreeing with them.
    expect(taskDecisionLabel("SUBMITTED", "DONE")).toBe("Approve");
    expect(taskDecisionLabel("OPEN", "DONE")).toBe("Mark done for them");
    expect(taskDecisionLabel("SUBMITTED", "DONE")).not.toBe(taskDecisionLabel("OPEN", "DONE"));
  });

  test("sending back is not called rejecting, and not called sending for revision either", () => {
    // "Reject" refuses the work; this returns it. "Send for revision" is the RECORD review ladder's
    // wording and promises a comment box that an assigned task has no column for.
    expect(reviewActionLabel("IN_PROGRESS")).toBe("Send back");
    expect(reviewActionLabel("IN_PROGRESS")).not.toMatch(/reject/i);
    expect(reviewActionLabel("IN_PROGRESS")).not.toMatch(/revision/i);
  });

  test("every decision label is a verb phrase a reader can check their click against", () => {
    for (const next of ["DONE", "IN_PROGRESS"] as const) {
      const label = taskDecisionLabel("SUBMITTED", next);
      expect(label).not.toMatch(/^(Yes|OK|Confirm)$/);
      expect(label.length).toBeGreaterThan(3);
    }
  });
});

test.describe("the approval dialog — what an admin is shown before they agree", () => {
  const base = { taskTitle: "Questionnaire sections C, D for 12 artisans", assigneeName: "Meera Sharma" };

  test("THE TWO NUMBERS: the claim and the repository count are both on screen before the press", () => {
    // The reason this board exists is a task reported done with nothing behind it. An approval
    // dialog that did not print the counter-number would be a Yes/No about a sentence, which is how
    // that exact failure gets rubber-stamped by the control built to catch it.
    const copy = overrideConfirmCopy({
      ...base,
      next: "DONE",
      from: "SUBMITTED",
      progressCount: 10,
      targetCount: 10,
      derivedCount: 2
    });
    expect(copy.note).toContain("can find 2");
    expect(copy.note).toContain("10 of 10");
  });

  test("an uncounted row says so rather than printing a zero it did not measure", () => {
    // "The repository found 0" and "we did not count" are different answers and only one of them is
    // a reason to refuse an approval.
    const copy = overrideConfirmCopy({
      ...base,
      next: "DONE",
      from: "SUBMITTED",
      progressCount: 4,
      targetCount: null,
      derivedCount: null
    });
    expect(copy.note).toContain("not available");
    expect(copy.note).toContain("their word alone");
    expect(copy.note).not.toMatch(/can find 0\b/);
  });

  test("approving carries NO quota-rewrite warning, because the fill already happened", () => {
    // The server moved the auto-fill from the approval to the submission so an approver sees the
    // claim already filled in. Warning about a rewrite here would describe a write that is not
    // about to occur — and the override branch's warning, which IS true, would be diluted by it.
    const copy = overrideConfirmCopy({
      ...base,
      next: "DONE",
      from: "SUBMITTED",
      progressCount: 3,
      targetCount: 10,
      derivedCount: 3
    });
    expect(copy.note).not.toContain("becomes");
    expect(copy.note).toContain("filled in when they handed it in");
    // The override branch, on an identical task that was never submitted, still warns.
    const override = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 3, targetCount: 10 });
    expect(override.note).toContain("becomes");
  });

  test("the approval names the person and the task, and calls it their work", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", from: "SUBMITTED", progressCount: 1, derivedCount: 1 });
    expect(copy.title).toContain("Meera Sharma");
    expect(copy.body).toContain(base.taskTitle);
    expect(copy.title).not.toContain("on their behalf");
    expect(copy.confirmLabel).toBe("Approve");
  });

  test("SENDING BACK ADMITS THAT NO REASON TRAVELS WITH IT", () => {
    // There is no comment column on a task row. An admin who assumes the button delivers their
    // reason is sending silent rejections, and the researcher gets a task back with no explanation
    // and no way to ask for one.
    const copy = overrideConfirmCopy({
      ...base,
      next: "IN_PROGRESS",
      from: "SUBMITTED",
      progressCount: 10,
      targetCount: 10,
      derivedCount: 1
    });
    expect(copy.note).toContain("not told a reason");
    expect(copy.note).toContain("say why some other way");
    expect(copy.confirmLabel).toBe("Send back");
  });

  test("the override wording is untouched for every task that was never submitted", () => {
    // `from` is optional precisely so the three older states keep their argued copy. If this fails,
    // the review branch has leaked into the override branch.
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: null });
    expect(copy.title).toContain("done for them");
    expect(copy.body).toContain("on their behalf");
    expect(copy.note).toContain("nothing on it will say an admin set it");
  });
});

test.describe("what the assignee is told when their task does not go away", () => {
  test("it confirms the press WORKED before it explains the wait", () => {
    // The failure being prevented: press "Mark done", card stays, nothing says why, so the only
    // available reading is that the press failed — and the researcher presses it again.
    const copy = reviewNoticeCopy("Dr Rao");
    expect(copy.body).toMatch(/has been recorded|recorded/);
    expect(copy.body.indexOf("recorded")).toBeLessThan(copy.body.indexOf("approve"));
  });

  test("it names who has to approve it, and that it stays on the list until then", () => {
    const copy = reviewNoticeCopy("Dr Rao");
    expect(copy.body).toContain("Dr Rao");
    expect(copy.body).toContain("approve");
    expect(copy.body).toContain("stays on your list");
  });

  test("with no assigner on the row it still names a role rather than nobody", () => {
    const copy = reviewNoticeCopy(null);
    expect(copy.body).toContain("admin");
    expect(copy.body).not.toContain("null");
    expect(copy.body).not.toContain("undefined");
    expect(reviewNoticeCopy("   ").body).toBe(copy.body);
  });

  test("IT MUST NOT READ AS FAILURE — the owner's constraint, as a word list", () => {
    const copy = reviewNoticeCopy("Dr Rao");
    const all = `${copy.title} ${copy.body} ${copy.action}`;
    for (const word of ["fail", "error", "reject", "denied", "problem", "unable", "could not"]) {
      expect(all.toLowerCase()).not.toContain(word);
    }
  });

  test("it closes by saying there is nothing further to do", () => {
    // The sentence before it reads as an instruction to wait and do something. There is nothing to
    // do, and saying so is the difference between a queue and a problem.
    expect(reviewNoticeCopy("Dr Rao").action).toContain("Nothing more is needed from you");
  });
});

test.describe("the wiring — claims about the JSX that no exported function can hold", () => {
  const card = read("components", "tasks", "AssigneeProgressCard.tsx");
  const myTask = read("components", "tasks", "MyTaskCard.tsx");
  const board = read("components", "tasks", "AccountabilityBoard.tsx");

  test("the card is drawn from /tasks/summary and never from the page of tasks on screen", () => {
    // A card counted from the twelve rows below it tells anyone with thirteen tasks that they have
    // fewer than they do — under-reporting outstanding work, which is the one failure this card was
    // asked for in order to prevent.
    expect(card).toContain("/tasks/summary");
    expect(card).not.toMatch(/apiFetch<[^>]*>\(`\/tasks\$\{/);
  });

  test("it spans the full width and sits above the list, not beside it", () => {
    // "a horizontal width spanning card on the top" — the owner's words, and the reason it is a
    // `w-full` section with its own bottom margin rather than a column in a grid.
    expect(card).toContain("w-full");
  });

  test("every status pill on both task surfaces takes the SERVER's label", () => {
    // No API codegen here and three hand-written clients; a status vocabulary maintained separately
    // in each one is three vocabularies, and a researcher moving between the handset and the laptop
    // is the person who finds out.
    expect(myTask).toContain("label={task.statusLabel}");
    expect(board).toContain("label={task.statusLabel}");
  });

  test("the assignee's card no longer offers a reopen that the server would refuse", () => {
    // `update_task` 403s an assignee's status write on a DONE task — approval is somebody else's
    // decision and cannot be taken back by the person whose work it is. A button that always fails
    // is worse than no button.
    expect(myTask).not.toContain('onStatus("OPEN")');
    expect(myTask).toContain("Only an admin or the person who assigned it can reopen it");
  });

  test("the button still says what Android says, and the consequence is said beside it", () => {
    // Rule 3 of the frontend contract, and the practical half: every field build already in
    // somebody's hand sends DONE from a button worded exactly this way, and the server rewrites it
    // rather than refusing it. The wording stays; the outcome is explained.
    expect(myTask).toContain("Mark done");
    expect(myTask).toContain("sends it to an admin to approve");
  });

  test("the review state has its own pill tone rather than falling through to another state's", () => {
    // A `Record<TaskStatus, …>` that silently fell through would paint "Under review" in IN_PROGRESS
    // amber or OPEN grey, and nothing but a person looking at it would ever notice.
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    const tones = primitives.slice(primitives.indexOf("const STATUS_TONE"), primitives.indexOf("const STATUS_TEXT"));
    expect(tones).toMatch(/SUBMITTED:\s*"[^"]+"/);
    const submitted = tones.match(/SUBMITTED:\s*"([^"]+)"/)?.[1];
    const inProgress = tones.match(/IN_PROGRESS:\s*"([^"]+)"/)?.[1];
    const done = tones.match(/DONE:\s*"([^"]+)"/)?.[1];
    expect(submitted).toBeTruthy();
    expect(submitted).not.toBe(inProgress);
    expect(submitted).not.toBe(done);
  });

  test("the bar animates in CSS, so BOTH reduced-motion switches reach it", () => {
    // globals.css zeroes `transition-duration` under `prefers-reduced-motion` AND under the app's
    // own `:root[data-reduced-motion="true"]`. A framer `animate` on the width would be an inline
    // style neither rule can touch, and would need a JS branch nobody reading the component would
    // know to look for.
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    expect(primitives).toContain("transition-all");
    // The IMPORT, not the word: both files argue about framer-motion in their comments, and a test
    // that banned the string would be a test nobody could explain their reasoning in front of.
    for (const source of [primitives, card]) {
      expect(source).not.toMatch(/from\s+["']framer-motion["']/);
    }
  });

  test("an absent percentage draws no bar rather than an empty one", () => {
    // `Math.round(undefined)` is NaN and `width: "NaN%"` is ignored by the browser, so a payload
    // that predates `effectivePercent` would render a full-length EMPTY track — "none of it done"
    // about rows whose progress simply was not sent. The guard is `Number.isFinite`, not a null
    // check, and this is the assertion that keeps it that way.
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    expect(primitives).toContain("Number.isFinite(percent as number)");
  });

  test("the percentage is never the only carrier of the state", () => {
    // A signal that exists only as a length is a signal a reduced-motion reader, a greyscale
    // printout and forced-colours mode all lose at once. The figure is printed beside the bar and
    // the bar carries `aria-valuetext` for anyone who hears it rather than sees it.
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    expect(primitives).toContain("aria-valuetext");
    expect(primitives).toContain('role="progressbar"');
  });

  test("the review state is read from the server's boolean, and no status SET is rebuilt by hand", () => {
    // `isAwaitingReview` / `isOutstanding` exist so that no client has to remember that "still on my
    // list" is three statuses now — and the way that knowledge gets lost is somebody writing the set
    // out as literals in one file and not the next. Comparing a single status to decide which BUTTON
    // to draw is a different thing and stays allowed; it is the SET that must not be re-derived.
    expect(myTask).toContain("task.isAwaitingReview");
    for (const source of [card, myTask, board]) {
      expect(source).not.toMatch(/"OPEN"\s*,\s*"IN_PROGRESS"/);
    }
  });
});
