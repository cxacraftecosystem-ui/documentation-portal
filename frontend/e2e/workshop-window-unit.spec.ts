import { expect, test } from "@playwright/test";

import {
  formatWorkshopDay,
  istDayKey,
  workshopDayKey,
  workshopWindowNotice,
  workshopWindowState
} from "@/components/forms/WorkshopSelect";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";

/**
 * "THIS WORKSHOP ENDED ON 24 SEPT 2026", SAID TO SOMEONE STANDING IN IT ON THE 14TH.
 *
 * THE INCIDENT. At 00:55 IST on 2026-09-14 the owner opened a record form for the 3rd Toolkit
 * Workshop — running 14–23 Sept — and the field under the picker read:
 *
 *     "Shristi O Anusandhan 3rd Toolkit Workshop for Mud Craft Tradition · 14 Sept 2026
 *      This workshop ended on 24 Sept 2026. Saving now is recorded as a late submission."
 *
 * Every clause of the second line is false, and three separate bugs had to line up to produce it:
 *
 *   1. `late = check.outOfWindow || check.isOver` printed the after-the-end half of a two-sided flag
 *      for both halves. `outOfWindow` means "before the start OR after the end", so a workshop that
 *      had not opened was announced as over. There was no copy for "not started" in the file at all.
 *   2. The server judged the window as UTC instants against days typed in IST, so `now < startDate`
 *      fired for the first 5h30m of every IST day of the run.
 *   3. `formatDate` renders an instant in the browser's zone, turning an endDate stored at
 *      2026-09-23T23:59:59.999Z into "24 Sept" — a date outside the workshop it was describing.
 *
 * WHY THIS SPEC NEVER OPENS A BROWSER. Reproducing any of it on screen needs a workshop whose window
 * straddles the hour the run happens to start, an IST machine clock, and a server build old enough
 * to still be wrong — which is to say it is not reproducible on purpose, which is how it reached a
 * user. The rulings were therefore lifted out of the JSX into exported functions that a test can
 * stand in front of at a NAMED instant. Same shape and same argument as `record-pickers-unit.spec.ts`
 * in this directory.
 *
 * ANDROID PARITY. Every assertion below has a Kotlin twin in
 * `android/app/src/test/java/com/fieldrepository/app/ui/WorkshopWindowTest.kt`, against
 * `ui/WorkshopOptions.kt`. Two surfaces answering one question about one workshop differently is
 * this product family's most repeated defect class; if you change a rule here and the Kotlin suite
 * still passes unchanged, you have just created one.
 */

/** The production row. startDate/endDate are IST calendar days, stored as the instants shown. */
const THIRD_TOOLKIT = {
  id: "wsp_3",
  title: "Shristi O Anusandhan 3rd Toolkit Workshop for Mud Craft Tradition",
  startDate: "2026-09-14T00:00:00+00:00",
  endDate: "2026-09-23T23:59:59.999000+00:00",
  date: "2026-09-14T00:00:00+00:00"
} as unknown as Workshop;

/** An instant named on the clock the researcher is reading, returned as epoch millis. */
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
  /**
   * THE ONE ASSERTION THIS FILE EXISTS FOR. 00:55 IST on the workshop's opening day is
   * 2026-09-13T19:25Z — the day BEFORE it, in UTC. Nothing about that moment is outside the window,
   * and no sentence about it may contain the word "ended".
   */
  test("a workshop opening this morning is in window at five past midnight IST", () => {
    const moment = ist(14, 0, 55);
    expect(new Date(moment).toISOString()).toBe("2026-09-13T19:25:00.000Z"); // the premise

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

  /**
   * And the server's own verdict for that moment, as the fixed backend now sends it, says the same
   * thing. Both paths are asserted because a form shows whichever one it has.
   */
  test("the server's in-window answer produces no warning either", () => {
    const check = answer({ outOfWindow: false, isOver: false });
    expect(workshopWindowState(check, THIRD_TOOLKIT, ist(14, 0, 55))).toBe("IN_WINDOW");
  });
});

test.describe("three states, from the two booleans already on the wire", () => {
  /**
   * The fix that needed no DTO change. `isOver` is the after-the-end half alone and `outOfWindow` is
   * the union, so `outOfWindow && !isOver` is exactly "has not started". `lib/types.ts` and Android's
   * `ApiModels.kt` are hand-maintained mirrors of the server's dict; a thirteenth key would have had
   * to land in three files at once, and the client that missed the edit would have read the missing
   * discriminator as `false` and printed the old wrong sentence anyway.
   */
  test("not started, in window and ended are told apart with no new field", () => {
    const now = ist(18);
    expect(workshopWindowState(answer({ outOfWindow: true, isOver: false }), THIRD_TOOLKIT, now)).toBe("NOT_STARTED");
    expect(workshopWindowState(answer({ outOfWindow: false, isOver: false }), THIRD_TOOLKIT, now)).toBe("IN_WINDOW");
    expect(workshopWindowState(answer({ outOfWindow: true, isOver: true }), THIRD_TOOLKIT, now)).toBe("ENDED");
  });

  /**
   * THE DEFECT ITSELF, stated as the thing that may never happen again: a workshop that has not
   * started is never described with the word "ended".
   */
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

  /** And the ended sentence is the one that already shipped, unchanged — it was never the wrong one. */
  test("the ended sentence keeps the wording researchers already know", () => {
    expect(
      workshopWindowNotice({ state: "ENDED", startLabel: "-", endLabel: "23 Sept 2026", needsAdminApproval: true })
    ).toBe("This workshop ended on 23 Sept 2026. Saving now counts as a late submission and needs an admin's approval.");
    expect(
      workshopWindowNotice({ state: "ENDED", startLabel: "-", endLabel: "23 Sept 2026", needsAdminApproval: false })
    ).toBe("This workshop ended on 23 Sept 2026. Saving now is recorded as a late submission.");
  });

  /**
   * A workshop in its window is told NOTHING. The warning row is the only thing on a record form
   * that can make a researcher doubt a save they are entitled to make.
   */
  test("an in-window workshop earns no sentence at all", () => {
    expect(
      workshopWindowNotice({ state: "IN_WINDOW", startLabel: "14 Sept 2026", endLabel: "23 Sept 2026", needsAdminApproval: true })
    ).toBeNull();
  });

  /**
   * The pre-flight carries no `startDate` (keeping the wire stable was the point), so a workshop
   * that has scrolled off the loaded first page leaves the start day unknown. "Not started yet" is
   * still worth saying; a date we do not have is not worth inventing.
   */
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
  /**
   * A researcher in a courtyard with no signal is exactly who reads this sentence, and the fallback
   * is the only thing they get. It must count the same days the server counts. The old expression
   * (`Date.now() >= end.getTime() + 24h`) carried the same UTC skew as the server bug.
   */
  test("both boundary days belong to the workshop in full", () => {
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(13))).toBe("NOT_STARTED");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(14))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(23))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(24))).toBe("ENDED");
  });

  /**
   * A workshop ending today has not ended — the invariant the sibling repository names in
   * `designer-portal/android/.../WorkshopOptionsTest.kt` as `a workshop ending today has not ended`.
   * Asserted at both ends of the last IST day, which is where the UTC skew used to flip the answer.
   */
  test("a workshop ending today has not ended, at either end of that day", () => {
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(23, 0, 5))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(23, 23, 55))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, THIRD_TOOLKIT, ist(24, 0, 5))).toBe("ENDED");
  });

  /** A workshop with no dates recorded is not declared anything; a blank column is not evidence. */
  test("a workshop with no dates at all is not declared over or unopened", () => {
    const undated = { id: "wsp_6", title: "Dates never recorded" } as unknown as Workshop;
    expect(workshopWindowState(null, undated, ist(18))).toBe("IN_WINDOW");
    expect(workshopWindowState(null, undefined, ist(18))).toBe("IN_WINDOW");
  });
});

test.describe("the day a column names", () => {
  /**
   * THE "24 SEPT" HALF OF THE INCIDENT. `lib/format.ts::formatDate` resolves an instant in the
   * browser's zone, so the last millisecond of the 23rd becomes 05:29 on the 24th on an IST laptop
   * and the app printed a date the workshop does not contain. The boundary columns are DAYS.
   */
  test("the last millisecond of the 23rd reads as the 23rd", () => {
    expect(workshopDayKey("2026-09-23T23:59:59.999000+00:00")).toBe("2026-09-23");
    expect(formatWorkshopDay("2026-09-23T23:59:59.999000+00:00")).toBe("23 Sept 2026");
  });

  /**
   * And the same day stamped from a handset is the same day. Whatever offset the writing client
   * attached, the date part is the day the researcher typed — the read Android already makes with
   * `OffsetDateTime.parse(value).toLocalDate()` and the server makes in `_boundary_day`.
   */
  test("an offset-bearing stamp names the same day as a UTC one", () => {
    expect(workshopDayKey("2026-09-23T23:59:59.999+05:30")).toBe("2026-09-23");
    expect(workshopDayKey("2026-09-14")).toBe("2026-09-14");
    expect(workshopDayKey(null)).toBeNull();
    expect(workshopDayKey("not a date")).toBeNull();
    expect(formatWorkshopDay(null)).toBe("-");
  });

  /**
   * "Today" is an IST day, whatever the laptop is set to. 19:25Z is already tomorrow in Odisha, and
   * a picker that disagreed with the researcher's own watch about what day it is would be arguing
   * with them about the only fact they are certain of.
   */
  test("today is counted in IST, across the 18:30Z rollover", () => {
    expect(istDayKey(Date.parse("2026-09-13T18:29:00Z"))).toBe("2026-09-13");
    expect(istDayKey(Date.parse("2026-09-13T18:30:00Z"))).toBe("2026-09-14");
    expect(istDayKey(Date.parse("2026-09-13T19:25:00Z"))).toBe("2026-09-14");
  });
});
