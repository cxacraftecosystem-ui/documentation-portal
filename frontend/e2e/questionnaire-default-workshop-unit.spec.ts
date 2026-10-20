import { expect, test } from "@playwright/test";

import { mostRecentRecordableWorkshop } from "@/components/forms/WorkshopSelect";
import type { Workshop } from "@/lib/types";

/**
 * The questionnaire's default workshop (web interview form and the session start sheet): the most
 * recent workshop the user may record in, by the day it ran, then by when it was entered. Kotlin twin:
 * `android/app/src/test/java/com/fieldrepository/app/ui/DefaultWorkshopTest.kt` — same cases.
 */

const NOW = Date.parse("2026-10-20T06:00:00Z"); // 11:30 IST on 20 Oct

function ws(id: string, startDate: string | null, extra: Partial<Workshop> = {}): Workshop {
  return {
    id,
    title: id,
    date: startDate ?? "2026-01-01T00:00:00.000Z",
    startDate,
    endDate: null,
    place: "",
    status: "APPROVED",
    createdAt: "2026-01-01T00:00:00.000Z",
    ...extra
  } as Workshop;
}

test("picks the most recent workshop by the day it ran, not the order it arrived in", () => {
  const list = [ws("older", "2026-09-14T00:00:00.000Z"), ws("recent", "2026-10-12T00:00:00.000Z"), ws("oldest", "2026-08-01T00:00:00.000Z")];
  expect(mostRecentRecordableWorkshop(list, NOW)?.id).toBe("recent");
});

test("skips workshops the user may not record in", () => {
  const list = [ws("closed", "2026-10-18T00:00:00.000Z", { canRecord: false }), ws("mine", "2026-10-01T00:00:00.000Z", { canRecord: true })];
  expect(mostRecentRecordableWorkshop(list, NOW)?.id).toBe("mine");
});

test("an older server without canRecord reads as recordable", () => {
  expect(mostRecentRecordableWorkshop([ws("legacy", "2026-10-01T00:00:00.000Z")], NOW)?.id).toBe("legacy");
});

test("an ended workshop is still a valid default; one not started yet is not", () => {
  const list = [ws("next-month", "2026-11-10T00:00:00.000Z"), ws("ended", "2026-09-14T00:00:00.000Z", { endDate: "2026-09-23T23:59:59.999Z" })];
  expect(mostRecentRecordableWorkshop(list, NOW)?.id).toBe("ended");
});

test("a workshop starting today counts, judged on the IST day", () => {
  // 00:30 IST on 21 Oct is still 20 Oct in UTC.
  const justAfterMidnightIst = Date.parse("2026-10-20T19:00:00Z");
  expect(mostRecentRecordableWorkshop([ws("today", "2026-10-21T00:00:00.000Z")], justAfterMidnightIst)?.id).toBe("today");
  expect(mostRecentRecordableWorkshop([ws("today", "2026-10-21T00:00:00.000Z")], NOW)).toBeNull();
});

test("same day: the one entered last wins", () => {
  const list = [
    ws("first", "2026-10-12T00:00:00.000Z", { createdAt: "2026-10-01T10:00:00.000Z" }),
    ws("second", "2026-10-12T00:00:00.000Z", { createdAt: "2026-10-02T10:00:00.000Z" })
  ];
  expect(mostRecentRecordableWorkshop(list, NOW)?.id).toBe("second");
});

test("none the user may record in leaves the field empty", () => {
  expect(mostRecentRecordableWorkshop([], NOW)).toBeNull();
  expect(mostRecentRecordableWorkshop([ws("closed", "2026-10-01T00:00:00.000Z", { canRecord: false })], NOW)).toBeNull();
});
