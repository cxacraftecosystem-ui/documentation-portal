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

/**
 * THE ADMIN OVERRIDE ON THE ACCOUNTABILITY BOARD — who may press it, what it is called, and what it
 * has to say before it fires.
 *
 * WHAT THE FEATURE IS. An admin or master admin may mark somebody else's assigned task DONE from
 * `components/tasks/AccountabilityBoard.tsx`, and reopen it afterwards. The server has permitted
 * this since the day `update_task` was written — an admin is a manager and a manager may write
 * `status` — so the risk here was never authorisation. It is that the control would be built to
 * look exactly like the assignee's own "Mark done" on `MyTaskCard.tsx:124`, which is a completely
 * different act: one is the person who did the work reporting it finished, the other is somebody
 * who did not do it declaring that it is. The row written is byte-identical either way (pinned by
 * `backend/tests/test_task_authority.py::test_an_override_leaves_no_trace_of_who_performed_it`), so
 * the distinction exists ONLY in the wording, and wording is exactly the thing that drifts.
 *
 * WHY THIS SPEC NEVER OPENS A BROWSER. Reproducing any of it on screen needs an admin session, a
 * second person's task in a state worth overriding, and the nerve to press the button on real data.
 * This repository has no React renderer in its devDependencies, so the judgements were lifted out
 * of the JSX into exported functions a test can stand in front of — the same shape, and the same
 * argument, as `record-pickers-unit.spec.ts` and `access-roster-unit.spec.ts` beside it.
 *
 * THE BACKEND HALF IS ASSERTED SEPARATELY, over HTTP, in `backend/tests/test_task_authority.py`.
 * The quota claim below is a claim about what the SERVER does; if you change the sentence here,
 * that file is where you find out whether the new sentence is true.
 */

const ALL_ROLES = Object.keys(ROLE_RANK) as UserRole[];

function user(role: UserRole): User {
  // Only `role` is read by the gate; the cast keeps the fixture to the point rather than inventing
  // a plausible-looking whole account that nothing asserts on.
  return { id: "u1", name: "Somebody", email: "somebody@example.org", role } as User;
}

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

test.describe("the gate — who is offered the override at all", () => {
  test("exactly the two administrative tiers, and nobody else", () => {
    const admitted = ALL_ROLES.filter((role) => isAdmin(user(role))).sort();
    expect(admitted).toEqual(["ADMIN", "MASTER_ADMIN"]);
  });

  test("a signed-out viewer is refused rather than crashing the board", () => {
    // `useAuth()` hands back null while the session is still resolving, and the board renders in
    // that window. A gate that read `user.role` directly would throw there instead of hiding.
    expect(isAdmin(null)).toBe(false);
    expect(isAdmin(undefined)).toBe(false);
  });

  test("the board reaches its gate through lib/permissions and never re-derives a rank", () => {
    // THE RULE THIS FILE ENFORCES ON THE NEXT EDIT, not a fact about today's code. The app has one
    // client-side role idiom and the board must borrow it (the page passes `isAdmin(user)` down as
    // `canOverride`). A hand-rolled `ROLE_RANK[user.role] >= 50` inside the board is a second copy
    // of the ladder that no `tsc` failure and no test but this one would ever catch.
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
    // "Never started" and "half done" say different things about a person's week. An override that
    // could only restore OPEN would erase that difference on every task it was used to undo.
    expect(overrideTargets("DONE")).toEqual(["IN_PROGRESS", "OPEN"]);
  });

  test("the override is reversible in both directions, so no press is a one-way door", () => {
    for (const target of overrideTargets("OPEN")) {
      expect(overrideTargets(target).length).toBeGreaterThan(0);
    }
  });

  test("a cancelled task offers nothing — withdrawal is not this board's to undo", () => {
    // The server would accept it: an admin is a manager and a manager may write any status. This is
    // the client declining a power it holds, because un-withdrawing an assignment from a disclosure
    // on a reading screen would resurrect work nobody is expecting, one row deep, with no batch
    // context on screen. The Assignments tab owns withdrawal, over the whole batch, behind a
    // danger-tone confirm.
    expect(overrideTargets("CANCELLED")).toEqual([]);
  });

  test("CANCELLED is never a destination of any offered move", () => {
    const everyOffer = (["OPEN", "IN_PROGRESS", "DONE", "CANCELLED"] as const).flatMap(overrideTargets);
    expect(everyOffer).not.toContain("CANCELLED");
  });
});

test.describe("the wording — an override must not read as the assignee's own report", () => {
  test("the button does not say what the assignee's own button says", () => {
    // THE ASSERTION THIS FILE EXISTS FOR. `MyTaskCard` is the assignee's card and its labels are
    // the right labels THERE; the moment the board borrows one of them, an admin declaring
    // somebody else's work finished and that person reporting it finished become the same gesture
    // on screen, and the only surviving difference between the two acts is gone.
    const myTaskCard = read("components", "tasks", "MyTaskCard.tsx");
    const assigneeLabels = [...myTaskCard.matchAll(/^\s{10,}([A-Z][A-Za-z ]+)$/gm)].map((match) => match[1].trim());
    expect(assigneeLabels).toContain("Mark done");

    const boardLabels = (["DONE", "IN_PROGRESS", "OPEN"] as const).map(overrideActionLabel);
    for (const label of boardLabels) {
      expect(assigneeLabels).not.toContain(label);
    }

    // AND THE SAME AT THE LAST CLICK, which is the worst place to lose the distinction: the
    // confirmation's own button restates the board button rather than collapsing to a bare "Mark
    // done" — which is exactly the string `MyTaskCard` uses.
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
    // THE FIRST WORDING WRITTEN HERE WAS "Reopen as not started", and this assertion is what caught
    // it: the status pill on the same row says "Open", so the button was naming one state twice.
    // All three — pill, button, confirmation sentence — now come out of `taskStatusText`.
    expect(overrideActionLabel("IN_PROGRESS")).toBe(`Back to ${taskStatusText("IN_PROGRESS").toLowerCase()}`);
    expect(overrideActionLabel("OPEN")).toBe(`Back to ${taskStatusText("OPEN").toLowerCase()}`);
    expect(overrideActionLabel("OPEN")).toBe("Back to open");
  });

  test("a status is spoken in one wording everywhere", () => {
    expect(taskStatusText("IN_PROGRESS")).toBe("In progress");
    expect(taskStatusText("DONE")).toBe("Done");
    // A fifth state the server grows before this bundle is rebuilt prints as itself. Ugly and
    // honest; mapping it to "Open" would be neither.
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
    expect(copy.body).toContain("has not reported it finished");
  });

  test("it says the record will not remember who did this", () => {
    // There is no "overridden by" column and this change deliberately does not add one. An admin
    // who assumes the board remembers the press would otherwise learn it by reloading.
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: null });
    expect(copy.note).toContain("nothing on it will say an admin set it");
  });

  test("THE QUOTA SENTENCE: marking a quota task done rewrites the reported figure, and says so", () => {
    // `update_task` fills `progressCount` to `targetCount` on DONE — correct for somebody who
    // genuinely finished, and for an override it is a silent rewrite of the one number this whole
    // board exists to compare against the repository-derived count. Both numbers must be on screen
    // BEFORE the press. Verified server-side by
    // `test_marking_a_quota_task_done_fills_the_reported_figure_to_the_target`.
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 0, targetCount: 10 });
    expect(copy.note).toContain("0 of 10");
    expect(copy.note).toContain("10 of 10");
    expect(copy.note).toContain("reported 0");
  });

  test("no quota, no quota sentence — the dialog may not invent a target that was never set", () => {
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 3, targetCount: null });
    expect(copy.note).not.toContain(" of ");
  });

  test("a quota already met produces no rewrite and is not claimed as one", () => {
    // `update_task` only fills when the reported figure is BELOW the target, so an admin finishing
    // a "10 of 10" row changes no number and must not be warned that it does.
    const copy = overrideConfirmCopy({ ...base, next: "DONE", progressCount: 10, targetCount: 10 });
    expect(copy.note).not.toContain("10 of 10");
  });

  test("reopening says the completion date is cleared", () => {
    const copy = overrideConfirmCopy({ ...base, next: "OPEN", progressCount: 10, targetCount: 10 });
    expect(copy.body).toContain("completion date is cleared");
    // And it names the destination with the SAME word as the button and the pill, not a third one.
    expect(copy.body).toContain(taskStatusText("OPEN").toLowerCase());
    expect(copy.confirmLabel).toBe(overrideActionLabel("OPEN"));
  });

  test("reopening does NOT promise to give the reported figure back, because nothing does", () => {
    // The asymmetry a reader will assume away: "reversible" covers the status and the completion
    // stamp and stops there. Pinned server-side by
    // `test_reopening_does_not_put_the_reported_figure_back`.
    const copy = overrideConfirmCopy({ ...base, next: "IN_PROGRESS", progressCount: 10, targetCount: 10 });
    expect(copy.note).toContain("stays at 10 of 10");
    expect(copy.note).toContain("does not undo a quota");
  });

  test("every confirm label is a verb phrase, never a bare Yes/OK", () => {
    for (const next of ["DONE", "IN_PROGRESS", "OPEN"] as const) {
      const copy = overrideConfirmCopy({ ...base, next, progressCount: 0, targetCount: null });
      expect(copy.confirmLabel).not.toMatch(/^(Yes|OK|Confirm)$/);
      expect(copy.confirmLabel.length).toBeGreaterThan(3);
    }
  });

  test("a task whose assignee has no name still reads as a sentence about a person", () => {
    // `user_brief` can hand back a row with a blank name. "Mark 's task done for them?" is the
    // failure; the fallback keeps the possessive grammatical.
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
