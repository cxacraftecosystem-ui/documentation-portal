import { expect, test } from "@playwright/test";

import type { SavedView } from "@/lib/dataExplorer/contract";
import {
  browserTimezone,
  canAddSchedule,
  HOUR_OPTIONS,
  hourLabel,
  nextRunWords,
  scheduleFilePath,
  scheduleLabel,
  scheduleStatus,
  sentWords,
  sizeWords,
  viewOptions,
  WEEKDAY_OPTIONS,
  zoneLabel,
  type ReportSchedule
} from "@/lib/dataExplorer/reportSchedules";

const NOW = Date.parse("2026-11-09T03:00:00Z");

function schedule(extra: Partial<ReportSchedule> = {}): ReportSchedule {
  return {
    id: "rs1",
    savedViewId: "v1",
    viewName: "Bagru media",
    path: "by-workshop/w1",
    weekday: 0,
    hourLocal: 8,
    timezone: "Asia/Kolkata",
    label: "every Monday at 08:00 IST",
    options: {},
    paused: false,
    nextRunAt: "2026-11-16T02:30:00Z",
    lastSentAt: null,
    lastError: null,
    lastFile: null,
    createdAt: "2026-11-01T00:00:00Z",
    ...extra
  };
}

function view(id: string, mine: boolean, ownerName: string | null = null): SavedView {
  return {
    id,
    name: `View ${id}`,
    path: "",
    state: { view: "files", sheet: "", q: "", filters: {} },
    shared: !mine,
    mine,
    ownerName,
    createdAt: "",
    updatedAt: ""
  };
}

test("the schedule label matches the server's wording, with IST for India", () => {
  expect(scheduleLabel(0, 8, "Asia/Kolkata")).toBe("Every Monday at 08:00 IST");
  expect(scheduleLabel(6, 23, "Europe/London")).toBe("Every Sunday at 23:00 Europe/London");
  expect(scheduleLabel(7, 0, null)).toBe("Every Monday at 00:00 IST");
  expect(zoneLabel(undefined)).toBe("IST");
  expect(hourLabel(9)).toBe("09:00");
  expect(hourLabel(30)).toBe("23:00");
});

test("the pickers offer seven days from Monday and twenty-four hours", () => {
  expect(WEEKDAY_OPTIONS.map((option) => option.label)).toEqual(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]);
  expect(WEEKDAY_OPTIONS[0].value).toBe("0");
  expect(HOUR_OPTIONS).toHaveLength(24);
  expect(HOUR_OPTIONS[8]).toEqual({ value: "8", label: "08:00" });
});

test("views are offered once each, a shared one naming its owner", () => {
  const options = viewOptions([view("a", true), view("b", false, "Asha"), view("a", true), view("c", false)]);
  expect(options).toEqual([
    { value: "a", label: "View a" },
    { value: "b", label: "View b", hint: "Shared by Asha" },
    { value: "c", label: "View c", hint: "Shared" }
  ]);
});

test("next run and sent words", () => {
  expect(nextRunWords(null, NOW)).toBeNull();
  expect(nextRunWords("nonsense", NOW)).toBeNull();
  expect(nextRunWords("2026-11-09T03:00:30Z", NOW)).toBe("Sending soon");
  expect(nextRunWords("2026-11-09T04:00:00Z", NOW)).toBe("Next in 1 hour");
  expect(nextRunWords("2026-11-09T08:00:00Z", NOW)).toBe("Next in 5 hours");
  expect(nextRunWords("2026-11-16T02:30:00Z", NOW)).toBe("Next in 7 days");
  expect(sentWords("2026-11-09T02:30:00Z", NOW)).toBe("Sent today");
  expect(sentWords("2026-11-08T02:30:00Z", NOW)).toBe("Sent yesterday");
  expect(sentWords("2026-11-02T02:30:00Z", NOW)).toBe("Sent 7 days ago");
});

test("sizes read like the e-mail's", () => {
  expect(sizeWords(512)).toBe("512 bytes");
  expect(sizeWords(2048)).toBe("2 KB");
  expect(sizeWords(3 * 1024 * 1024)).toBe("3.0 MB");
});

test("the status line says paused, the error, or what went and what is next", () => {
  expect(scheduleStatus(schedule({ paused: true, lastError: "x" }), NOW)).toEqual({ text: "Paused", tone: "info" });
  expect(scheduleStatus(schedule({ lastError: "You can no longer see this folder, so this report was not sent." }), NOW).tone).toBe("warn");
  expect(scheduleStatus(schedule(), NOW)).toEqual({ text: "Next in 7 days", tone: "info" });
  expect(
    scheduleStatus(
      schedule({ lastSentAt: "2026-11-09T02:30:00Z", lastFile: { fileName: "R.xlsx", sizeBytes: 2048, attached: true } }),
      NOW
    )
  ).toEqual({ text: "Sent today, attached (2 KB) · Next in 7 days", tone: "done" });
  expect(
    scheduleStatus(
      schedule({ lastSentAt: "2026-11-09T02:30:00Z", lastFile: { fileName: "R.xlsx", sizeBytes: 9 * 1024 * 1024, attached: false } }),
      NOW
    ).text
  ).toContain("as a link (9.0 MB)");
});

test("the add button stops at the owner's limit", () => {
  expect(canAddSchedule(null)).toBe(false);
  expect(canAddSchedule({ items: [], maxPerOwner: 20 })).toBe(true);
  expect(canAddSchedule({ items: Array.from({ length: 20 }, () => schedule()), maxPerOwner: 20 })).toBe(false);
});

test("the e-mailed download link is the page this portal serves", () => {
  expect(scheduleFilePath("rs 1")).toBe("/data/report-schedules/rs%201");
});

test("the browser's zone is an IANA name", () => {
  expect(browserTimezone()).toMatch(/^[A-Za-z_]+\/[A-Za-z_/+-]+$/);
});
