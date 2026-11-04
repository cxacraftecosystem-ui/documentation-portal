import { expect, test } from "@playwright/test";

import {
  formatWorkshopDay,
  istDayKey,
  workshopDayKey,
  workshopWindowNotice,
  workshopWindowState
} from "@/components/forms/WorkshopSelect";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";

const THIRD_TOOLKIT = {
  id: "wsp_3",
  title: "Shristi O Anusandhan 3rd Toolkit Workshop for Mud Craft Tradition",
  startDate: "2026-09-14T00:00:00+00:00",
  endDate: "2026-09-23T23:59:59.999000+00:00",
  date: "2026-09-14T00:00:00+00:00"
} as unknown as Workshop;

function ist(day: number, hour = 12, minute = 0, month = 9): number {
  const stamp = `2026-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
  return Date.parse(`${stamp}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00+05:30`);
}

function answer(fields: Partial<WorkshopSubmissionCheck>): WorkshopSubmissionCheck {
  return {
    workshopId: THIRD_TOOLKIT.id,
    title: THIRD_TOOLKIT.title,
    endDate: THIRD_TOOLKIT.endDate,
    isOver: false,
    outOfWindow: false,
    needsAdminApproval: false,
    assigned: true,
    canSubmit: true,
    ...fields
  };
}

test.describe("the regression", () => {
  test("a workshop opening this morning is in window at five past midnight IST", () => {
    const moment = ist(14, 0, 55);
    expect(new Date(moment).toISOString()).toBe("2026-09-13T19:25:00.000Z");

    expect(workshopWindowState(null, THIRD_TOOLKIT, moment)).toBe("IN_WINDOW");
    expect(
      workshopWindowNotice({
        state: workshopWindowState(null, THIRD_TOOLKIT, moment),
        startLabel: formatWorkshopDay(THIRD_TOOLKIT.startDate),
        endLabel: formatWorkshopDay(THIRD_TOOLKIT.endDate),
        needsAdminApproval: true
      })
    ).toBeNull();
  });

  test("the server's in-window answer produces no warning either", () => {
    const check = answer({ outOfWindow: false, isOver: false });
    expect(workshopWindowState(check, THIRD_TOOLKIT, ist(14, 0, 55))).toBe("IN_WINDOW");
  });
});

test.describe("three states, from the two booleans already on the wire", () => {
  test("not started, in window and ended are told apart with no new field", () => {
    const now = ist(18);
    expect(workshopWindowState(answer({ outOfWindow: true, isOver: false }), THIRD_TOOLKIT, now)).toBe("NOT_STARTED");
    expect(workshopWindowState(answer({ outOfWindow: false, isOver: false }), THIRD_TOOLKIT, now)).toBe("IN_WINDOW");
    expect(workshopWindowState(answer({ outOfWindow: true, isOver: true }), THIRD_TOOLKIT, now)).toBe("ENDED");
  });

  test("a workshop that has not started is never called ended", () => {
    const notice = workshopWindowNotice({
      state: "NOT_STARTED",
      startLabel: "20 Sept 2026",
      endLabel: "29 Sept 2026",
      needsAdminApproval: true
    });

    expect(notice).not.toContain("ended");
    expect(notice).toContain("starts on 20 Sept 2026");
    expect(notice).toContain("early submission");
  });

  test("the ended sentence keeps the wording researchers already know", () => {
    expect(
      workshopWindowNotice({ state: "ENDED", startLabel: "-", endLabel: "23 Sept 2026", needsAdminApproval: true })
    ).toBe("This workshop ended on 23 Sept 2026. Saving now counts as a late submission and needs an admin's approval.");
    expect(
      workshopWindowNotice({ state: "ENDED", startLabel: "-", endLabel: "23 Sept 2026", needsAdminApproval: false })
    ).toBe("This workshop ended on 23 Sept 2026. Saving now is recorded as a late submission.");
  });

  test("an in-window workshop earns no sentence at all", () => {
    expect(
      workshopWindowNotice({ state: "IN_WINDOW", startLabel: "14 Sept 2026", endLabel: "23 Sept 2026", needsAdminApproval: true })
    ).toBeNull();
  });

  test("a start day we do not know degrades to a dateless sentence rather than a wrong one", () => {
    const notice = workshopWindowNotice({
      state: "NOT_STARTED",
      startLabel: "-",
      endLabel: "-",
      needsAdminApproval: false
    });

    expect(notice).toBe("This workshop has not started yet. Saving now is recorded as an early submission.");
  });
});

test.describe("the local fallback, when the pre-flight cannot be reached", () => {
  test("both boundary days belong to the workshop in full", () => {
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(13))).toBe("NOT_STARTED");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(14))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(23))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(24))).toBe("ENDED");
  });

  test("a workshop ending today has not ended, at either end of that day", () => {
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(23, 0, 5))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(23, 23, 55))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(24, 0, 5))).toBe("ENDED");
  });

  test("a workshop with no dates at all is not declared over or unopened", () => {
    const undated = { id: "wsp_6", title: "Dates never recorded" } as unknown as Workshop;
    expect(workshopWindowState(null, undated, ist(18))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, undefined, ist(18))).toBe("IN_WINDOW");
  });
});

test.describe("the day a column names", () => {
  test("the last millisecond of the 23rd reads as the 23rd", () => {
    expect(workshopDayKey("2026-09-23T23:59:59.999000+00:00")).toBe("2026-09-23");
    expect(formatWorkshopDay("2026-09-23T23:59:59.999000+00:00")).toBe("23 Sept 2026");
  });

  test("an offset-bearing stamp names the same day as a UTC one", () => {
    expect(workshopDayKey("2026-09-23T23:59:59.999+05:30")).toBe("2026-09-23");
    expect(workshopDayKey("2026-09-14")).toBe("2026-09-14");
    expect(workshopDayKey(null)).toBeNull();
    expect(workshopDayKey("not a date")).toBeNull();
    expect(formatWorkshopDay(null)).toBe("-");
  });

  test("today is counted in IST, across the 18:30Z rollover", () => {
    expect(istDayKey(Date.parse("2026-09-13T18:29:00Z"))).toBe("2026-09-13");
    expect(istDayKey(Date.parse("2026-09-13T18:30:00Z"))).toBe("2026-09-14");
    expect(istDayKey(Date.parse("2026-09-13T19:25:00Z"))).toBe("2026-09-14");
  });
});
