import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  overrideActionLabel,
  overrideConfirmCopy,
  overrideTargets,
  taskStatusText
} from "@/components/tasks/TaskPrimitives";
import { isAdmin, ROLE_RANK } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const ALL_ROLES = Object.keys(ROLE_RANK) as UserRole[];

function user(role: UserRole): User {
  return { id: "u1", name: "Somebody", email: "somebody@example.org", role } as User;
}

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

test.describe("the gate — who is offered the override at all", () => {
  test("exactly the two administrative tiers, and nobody else", () => {
    const admitted = ALL_ROLES.filter((role) => isAdmin(user(role))).sort();
    expect(admitted).toEqual(["ADMIN", "MASTER_ADMIN"]);
  });

  test("a signed-out viewer is refused rather than crashing the board", () => {
    expect(isAdmin(null)).toBe(false);
    expect(isAdmin(undefined)).toBe(false);
  });

  test("the board reaches its gate through lib/permissions and never re-derives a rank", () => {
    const board = read("components", "tasks", "AccountabilityBoard.tsx");
    expect(board).not.toMatch(/ROLE_RANK/);
    expect(board).not.toMatch(/roleRank\s*\(/);
    expect(board).not.toMatch(/role\s*===\s*["']MASTER_ADMIN["']/);
  });
});

test.describe("which moves the board offers, and which it refuses", () => {
  test("an unfinished task offers only the one move: declare it done", () => {
    expect(overrideTargets("OPEN")).toEqual(["DONE"]);
    expect(overrideTargets("IN_PROGRESS")).toEqual(["DONE"]);
  });

  test("a finished task offers BOTH ways back, because the two are different answers", () => {
    expect(overrideTargets("DONE")).toEqual(["IN_PROGRESS", "OPEN"]);
  });

  test("the override is reversible in both directions, so no press is a one-way door", () => {
    for (const target of overrideTargets("OPEN")) {
      expect(overrideTargets(target).length).toBeGreaterThan(0);
    }
  });

  test("a cancelled task offers nothing — withdrawal is not this board's to undo", () => {
    expect(overrideTargets("CANCELLED")).toEqual([]);
  });

  test("CANCELLED is never a destination of any offered move", () => {
    const everyOffer = (["OPEN", "IN_PROGRESS", "DONE", "CANCELLED"] as const).flatMap(overrideTargets);
    expect(everyOffer).not.toContain("CANCELLED");
  });
});

test.describe("the wording — an override must not read as the assignee's own report", () => {
  test("the button does not say what the assignee's own button says", () => {
    const myTaskCard = read("components", "tasks", "MyTaskCard.tsx");
    const assigneeLabels = [...myTaskCard.matchAll(/^\s{10,}([A-Z][A-Za-z ]+)$/gm)].map((match) => match[1].trim());
    expect(assigneeLabels).toContain("Mark done");

    const boardLabels = (["DONE", "IN_PROGRESS", "OPEN"] as const).map(overrideActionLabel);
    for (const label of boardLabels) {
      expect(assigneeLabels).not.toContain(label);
    }

    for (const next of ["DONE", "IN_PROGRESS", "OPEN"] as const) {
      const copy = overrideConfirmCopy({
        next,
        taskTitle: "Tool survey",
        assigneeName: "Meera Sharma",
        progressCount: 0,
        targetCount: null
      });
      expect(copy.confirmLabel).toBe(overrideActionLabel(next));
      expect(assigneeLabels).not.toContain(copy.confirmLabel);
    }
  });

  test("the done button names whose task it is", () => {
    expect(overrideActionLabel("DONE")).toBe("Mark done for them");
  });

  test("each way back names the state it restores, in the words the pill on that row uses", () => {
    expect(overrideActionLabel("IN_PROGRESS")).toBe(`Back to ${taskStatusText("IN_PROGRESS").toLowerCase()}`);
    expect(overrideActionLabel("OPEN")).toBe(`Back to ${taskStatusText("OPEN").toLowerCase()}`);
    expect(overrideActionLabel("OPEN")).toBe("Back to open");
  });

  test("a status is spoken in one wording everywhere", () => {
    expect(taskStatusText("IN_PROGRESS")).toBe("In progress");
    expect(taskStatusText("DONE")).toBe("Done");
    expect(taskStatusText("ARCHIVED")).toBe("ARCHIVED");
  });
});

test.describe("the confirmation — what an admin is told before the write happens", () => {
  const base = { taskTitle: "Record 10 tools for the Bagru trip", assigneeName: "Meera Sharma" };

  test("the dialog names the person whose task is being changed", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: null });
    expect(copy.title).toContain("Meera Sharma");
    expect(copy.body).toContain("Meera Sharma");
    expect(copy.body).toContain(base.taskTitle);
  });

  test("it says out loud that this is a declaration on somebody else's behalf", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: null });
    expect(copy.body).toContain("on their behalf");
    expect(copy.body).toContain("hasn’t reported");
  });

  test("it says the record will not remember who did this", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: null });
    expect(copy.note).toContain("won’t show that an admin did this");
  });

  test("THE QUOTA SENTENCE: marking a quota task done rewrites the reported figure, and says so", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: 10 });
    expect(copy.note).toContain("0 of 10");
    expect(copy.note).toContain("10 of 10");
    expect(copy.note).toContain("reported 0 of 10 becomes 10 of 10");
  });

  test("no quota, no quota sentence — the dialog may not invent a target that was never set", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 3, targetCount: null });
    expect(copy.note).not.toContain(" of ");
  });

  test("a quota already met produces no rewrite and is not claimed as one", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 10, targetCount: 10 });
    expect(copy.note).not.toContain("10 of 10");
  });

  test("reopening says the completion date is cleared", () => {
    const copy = overrideConfirmCopy({ ...base, next: "OPEN", progressCount: 10, targetCount: 10 });
    expect(copy.body).toContain("completion date is cleared");
    expect(copy.body).toContain(taskStatusText("OPEN").toLowerCase());
    expect(copy.confirmLabel).toBe(overrideActionLabel("OPEN"));
  });

  test("reopening does NOT promise to give the reported figure back, because nothing does", () => {
    const copy = overrideConfirmCopy({ ...base, next: "IN_PROGRESS", progressCount: 10, targetCount: 10 });
    expect(copy.note).toContain("stays at 10 of 10");
    expect(copy.note).toContain("doesn’t undo the quota");
  });

  test("every confirm label is a verb phrase, never a bare Yes/OK", () => {
    for (const next of ["DONE", "IN_PROGRESS", "OPEN"] as const) {
      const copy = overrideConfirmCopy({ ...base, next, progressCount: 0, targetCount: null });
      expect(copy.confirmLabel).not.toMatch(/^(Yes|OK|Confirm)$/);
      expect(copy.confirmLabel.length).toBeGreaterThan(3);
    }
  });

  test("a task whose assignee has no name still reads as a sentence about a person", () => {
    const copy = overrideConfirmCopy({
      next: "DONE",
      taskTitle: "Tool survey",
      assigneeName: "  ",
      progressCount: 0,
      targetCount: null
    });
    expect(copy.title).toBe("Mark this person's task done for them?");
    expect(copy.title).not.toContain("  ");
  });
});
