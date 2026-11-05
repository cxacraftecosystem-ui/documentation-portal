import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  chartValue,
  dayLabel,
  daysLabel,
  deviceOf,
  expiryLine,
  filtersOf,
  hasData,
  kpiValue,
  labelEvery,
  niceMax,
  productName,
  SHARE_DAYS,
  stackTotals,
  ticks,
  viewerLabel,
  viewsLabel,
  type ShareChart
} from "@/lib/dataExplorer/reportShare";

const NOW = Date.parse("2026-11-05T10:00:00Z");

test("the product name follows the portal the server reports", () => {
  expect(productName("designer")).toBe("Design Workshop");
  expect(productName("fieldRepository")).toBe("Field Repository");
  expect(productName(undefined)).toBe("Design Workshop");
});

test("kpi values are formatted by unit", () => {
  expect(kpiValue({ key: "files", label: "Files", value: 12345, unit: "count", sub: null })).toBe("12,345");
  expect(kpiValue({ key: "size", label: "Size", value: 1536, unit: "bytes", sub: null })).toBe("1.5 KB");
  expect(kpiValue({ key: "coverage", label: "Coverage", value: 66.6, unit: "percent", sub: null })).toBe("67%");
  expect(kpiValue({ key: "coverage", label: "Coverage", value: null, unit: "percent", sub: null })).toBe("—");
});

test("chart values carry their unit", () => {
  expect(chartValue(66.67, "percent")).toBe("67%");
  expect(chartValue(1234, "count")).toBe("1,234");
  expect(chartValue(2.25, undefined)).toBe("2.3");
  expect(chartValue(Number.NaN, "count")).toBe("0");
});

test("axis scales are round", () => {
  expect(niceMax(0)).toBe(1);
  expect(niceMax(7)).toBe(10);
  expect(niceMax(13)).toBe(20);
  expect(niceMax(240)).toBe(250);
  expect(ticks(7)).toEqual([0, 2, 4, 6, 8, 10]);
  expect(ticks(13)).toEqual([0, 5, 10, 15, 20]);
  expect(ticks(1)).toEqual([0, 1]);
  expect(ticks(0)).toEqual([0, 1]);
  expect(labelEvery(60, 7)).toBe(9);
  expect(labelEvery(5, 7)).toBe(1);
});

test("stacked totals and empty charts", () => {
  const chart: ShareChart = {
    key: "completionBySection",
    title: "Completion by section",
    kind: "stackedBar",
    categories: ["Life", "Craft"],
    series: [
      { name: "Complete", values: [1, 0] },
      { name: "Partial", values: [1, 2] }
    ]
  };
  expect(stackTotals(chart)).toEqual([2, 2]);
  expect(hasData(chart)).toBe(true);
  expect(hasData({ ...chart, series: [{ name: "Complete", values: [0, 0] }] })).toBe(false);
  expect(hasData({ ...chart, categories: [] })).toBe(false);
});

test("dates and expiry read plainly", () => {
  expect(dayLabel("2026-11-03")).toBe("3 Nov");
  expect(dayLabel("not a date")).toBe("not a date");
  expect(expiryLine("revoked", "2026-11-09T10:00:00Z", NOW)).toBe("Turned off");
  expect(expiryLine("active", "2026-11-04T10:00:00Z", NOW)).toBe("Expired");
  expect(expiryLine("active", "2026-11-05T10:30:00Z", NOW)).toBe("Expires within an hour");
  expect(expiryLine("active", "2026-11-05T15:00:00Z", NOW)).toBe("Expires in 5 hours");
  expect(expiryLine("active", "2026-11-06T10:00:00Z", NOW)).toBe("Expires in 1 day");
  expect(expiryLine("active", "2026-11-12T10:00:00Z", NOW)).toBe("Expires in 7 days");
  expect(SHARE_DAYS.map(daysLabel)).toEqual(["1 day", "7 days", "14 days", "30 days"]);
});

test("opens and visitors are named", () => {
  expect(viewsLabel(0)).toBe("Not opened yet");
  expect(viewsLabel(1)).toBe("Opened once");
  expect(viewsLabel(1200)).toBe("Opened 1,200 times");
  expect(deviceOf("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")).toBe("Chrome on Android");
  expect(deviceOf("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605 Version/18.0 Safari/604.1")).toBe("Safari on iOS");
  expect(deviceOf(null)).toBe("Unknown device");
  expect(viewerLabel({ id: "1", openedAt: "", viewer: { id: "u", name: "Kamla" }, visitor: "abcd1234", userAgent: null })).toBe("Kamla");
  expect(viewerLabel({ id: "1", openedAt: "", viewer: null, visitor: "abcd1234", userAgent: null })).toBe("Visitor abcd");
});

test("filters keep only real values", () => {
  expect(filtersOf({ status: "APPROVED", empty: "", none: null, page: 2 })).toEqual({ status: "APPROVED", page: "2" });
  expect(filtersOf(undefined)).toEqual({});
});

test("the public page sits outside the signed-in shell and keeps its link out of referrers", () => {
  const app = join(__dirname, "..", "app", "report-share", "[token]");
  const layout = readFileSync(join(app, "layout.tsx"), "utf8");
  const page = readFileSync(join(app, "page.tsx"), "utf8");
  expect(layout).toContain('referrer: "no-referrer"');
  expect(layout).toContain("index: false");
  expect(page).toContain("ReportShareView");
  expect(page).not.toContain("(protected)");
});
