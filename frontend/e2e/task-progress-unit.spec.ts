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
    const bar = summaryBar(summary({ taskCount: 3, cancelledCount: 3 }));
    expect(bar.percent).toBeNull();
    expect(bar.caption).toContain("withdrawn");
  });

  test("THE FAKE ZERO: nothing counted and nothing reported draws NO BAR", () => {
    const bar = summaryBar(summary({ taskCount: 3, remainingCount: 3, percentComplete: 0, measuredCount: 0 }));
    expect(bar.percent).toBeNull();
    expect(bar.caption).toContain("No progress yet");
    expect(bar.caption).toContain("exact");
  });

  test("AN HONEST ZERO IS DRAWN: something was counted, and what was counted is nothing yet", () => {
    const bar = summaryBar(summary({ taskCount: 2, remainingCount: 2, percentComplete: 0, measuredCount: 1 }));
    expect(bar.percent).toBe(0);
    expect(bar.measured).toBe("some");
  });

  test("a percentage above zero is always drawn, even with nothing measured", () => {
    const bar = summaryBar(summary({ taskCount: 4, percentComplete: 50, measuredCount: 0, approvedCount: 2 }));
    expect(bar.percent).toBe(50);
    expect(bar.measured).toBe("none");
    expect(bar.caption).toContain("reported");
  });
});

test.describe("the caption says WHICH measure the bar is showing", () => {
  test('"8 of 12 recorded" and "you reported 8 of 12" never come out as the same sentence', () => {
    expect(progressSourceNote("derived")).not.toBe(progressSourceNote("reported"));
    expect(progressSourceNote("derived")).toContain("automatically");
    expect(progressSourceNote("reported")).toContain("Self-reported");
  });

  test("the automatic measure says it is automatic, because that is the whole feature", () => {
    expect(progressSourceNote("derived")).toMatch(/on its own|automatic/i);
  });

  test("no source at all explains the ABSENCE of a bar rather than describing one", () => {
    expect(progressSourceNote(null)).toContain("No progress yet");
  });

  test("all measured, some measured and none measured are three different sentences", () => {
    const all = summaryBar(summary({ taskCount: 2, percentComplete: 40, measuredCount: 2 }));
    const some = summaryBar(summary({ taskCount: 4, percentComplete: 40, measuredCount: 2 }));
    const none = summaryBar(summary({ taskCount: 4, percentComplete: 40, measuredCount: 0 }));
    expect([all.measured, some.measured, none.measured]).toEqual(["all", "some", "none"]);
    expect(new Set([all.caption, some.caption, none.caption]).size).toBe(3);
    expect(some.caption).toContain("2 of 4");
  });

  test("a cancelled task is out of the denominator the caption quotes", () => {
    const bar = summaryBar(summary({ taskCount: 5, cancelledCount: 1, percentComplete: 60, measuredCount: 4 }));
    expect(bar.measured).toBe("all");
    expect(bar.caption).toContain("All 4");
  });
});

test.describe("what the card admits it does not know", () => {
  test("a truncated scan says the counts are a floor, not a total", () => {
    const bar = summaryBar(summary({ taskCount: 2000, percentComplete: 10, measuredCount: 5, truncated: true }));
    expect(bar.caveats.join(" ")).toContain("may be incomplete");
  });

  test("declined derivation is stated, not quietly redrawn as a measured bar", () => {
    const bar = summaryBar(summary({ taskCount: 300, percentComplete: 20, measuredCount: 0, derivationSkipped: true }));
    expect(bar.caveats.join(" ")).toContain("count automatically");
  });

  test("a caveat never invents the server's limit as a number", () => {
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
    expect(dueSentence(summary({ taskCount: 3, remainingCount: 3 }))).toBeNull();
  });

  test("overdue leads, because it is the only emergency on this card", () => {
    const line = dueSentence(summary({ overdueCount: 2, dueSoonCount: 1, nextDueAt: "2026-09-20T00:00:00Z" }));
    expect(line?.startsWith("2 tasks are past")).toBe(true);
  });

  test("one overdue task is singular in all three of its words", () => {
    expect(dueSentence(summary({ overdueCount: 1 }))).toBe("1 task is past its due date.");
  });

  test("a next due date is named without the already-overdue ones being counted twice", () => {
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
    expect(overrideTargets("SUBMITTED")).not.toContain("OPEN");
    expect(overrideTargets("DONE")).toContain("OPEN");
  });

  test("SUBMITTED is never a destination — an admin does not hand work in for somebody", () => {
    const everyOffer = (["OPEN", "IN_PROGRESS", "SUBMITTED", "DONE", "CANCELLED"] as const).flatMap(overrideTargets);
    expect(everyOffer).not.toContain("SUBMITTED");
    expect(everyOffer).not.toContain("CANCELLED");
  });

  test("the approve button does not borrow the override's words", () => {
    expect(taskDecisionLabel("SUBMITTED", "DONE")).toBe("Approve");
    expect(taskDecisionLabel("OPEN", "DONE")).toBe("Mark done for them");
    expect(taskDecisionLabel("SUBMITTED", "DONE")).not.toBe(taskDecisionLabel("OPEN", "DONE"));
  });

  test("sending back is not called rejecting, and not called sending for revision either", () => {
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
    const copy = overrideConfirmCopy({
      ...base,
      next: "DONE",
      from: "SUBMITTED",
      progressCount: 10,
      targetCount: 10,
      derivedCount: 2
    });
    expect(copy.note).toContain("2 recorded");
    expect(copy.note).toContain("10 of 10");
  });

  test("an uncounted row says so rather than printing a zero it did not measure", () => {
    const copy = overrideConfirmCopy({
      ...base,
      next: "DONE",
      from: "SUBMITTED",
      progressCount: 4,
      targetCount: null,
      derivedCount: null
    });
    expect(copy.note).toContain("No recorded count");
    expect(copy.note).toContain("based on their report");
    expect(copy.note).not.toMatch(/\b0 recorded\b/);
  });

  test("approving carries NO quota-rewrite warning, because the fill already happened", () => {
    const copy = overrideConfirmCopy({
      ...base,
      next: "DONE",
      from: "SUBMITTED",
      progressCount: 3,
      targetCount: 10,
      derivedCount: 3
    });
    expect(copy.note).not.toContain("becomes");
    expect(copy.note).toContain("You can reopen it later");
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

  test("SENDING BACK TELLS THE ADMIN THAT NO REASON TRAVELS WITH IT", () => {
    const copy = overrideConfirmCopy({
      ...base,
      next: "IN_PROGRESS",
      from: "SUBMITTED",
      progressCount: 10,
      targetCount: 10,
      derivedCount: 1
    });
    expect(copy.note).toContain("won’t see a reason");
    expect(copy.note).toContain("tell them why");
    expect(copy.confirmLabel).toBe("Send back");
  });

  test("the override wording is untouched for every task that was never submitted", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: null });
    expect(copy.title).toContain("done for them");
    expect(copy.body).toContain("on their behalf");
    expect(copy.note).toContain("won’t show that an admin did this");
  });
});

test.describe("what the assignee is told when their task does not go away", () => {
  test("it confirms the press WORKED before it explains the wait", () => {
    const copy = reviewNoticeCopy("Dr Rao");
    expect(copy.body).toMatch(/Handed in/);
    expect(copy.body.indexOf("Handed in")).toBeLessThan(copy.body.indexOf("approve"));
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
    expect(reviewNoticeCopy("Dr Rao").action).toContain("Nothing to do");
  });
});

test.describe("the wiring — claims about the JSX that no exported function can hold", () => {
  const card = read("components", "tasks", "AssigneeProgressCard.tsx");
  const myTask = read("components", "tasks", "MyTaskCard.tsx");
  const board = read("components", "tasks", "AccountabilityBoard.tsx");

  test("the card is drawn from /tasks/summary and never from the page of tasks on screen", () => {
    expect(card).toContain("/tasks/summary");
    expect(card).not.toMatch(/apiFetch<[^>]*>\(`\/tasks\$\{/);
  });

  test("it spans the full width and sits above the list, not beside it", () => {
    expect(card).toContain("w-full");
  });

  test("every status pill on both task surfaces takes the SERVER's label", () => {
    expect(myTask).toContain("label={task.statusLabel}");
    expect(board).toContain("label={task.statusLabel}");
  });

  test("the assignee's card no longer offers a reopen that the server would refuse", () => {
    expect(myTask).not.toContain('onStatus("OPEN")');
    expect(myTask).toContain("Approved.");
  });

  test("the button still says what Android says, and the consequence is said beside it", () => {
    expect(myTask).toContain("Mark done");
    expect(myTask).toContain("sends it for approval");
  });

  test("the review state has its own pill tone rather than falling through to another state's", () => {
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
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    expect(primitives).toContain("transition-all");
    for (const source of [primitives, card]) {
      expect(source).not.toMatch(/from\s+["']framer-motion["']/);
    }
  });

  test("an absent percentage draws no bar rather than an empty one", () => {
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    expect(primitives).toContain("Number.isFinite(percent as number)");
  });

  test("the percentage is never the only carrier of the state", () => {
    const primitives = read("components", "tasks", "TaskPrimitives.tsx");
    expect(primitives).toContain("aria-valuetext");
    expect(primitives).toContain('role="progressbar"');
  });

  test("the review state is read from the server's boolean, and no status SET is rebuilt by hand", () => {
    expect(myTask).toContain("task.isAwaitingReview");
    for (const source of [card, myTask, board]) {
      expect(source).not.toMatch(/"OPEN"\s*,\s*"IN_PROGRESS"/);
    }
  });
});
