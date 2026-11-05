import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  chartValue,
  mergeShareViews,
  dayLabel,
  daysLabel,
  deviceOf,
  expiryLine,
  fill,
  langOf,
  SHARE_LABELS_HI,
  filterLabel,
  filtersOf,
  formatWhen,
  isWeekly,
  localeOf,
  SHARE_LABELS_EN,
  shareLabels,
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

test("a Hindi snapshot brings its own labels and an English one falls back", () => {
  const hindi = shareLabels({ labels: { sharedBy: "द्वारा साझा", unknown: "x", readOnly: " " } });
  expect(hindi.sharedBy).toBe("द्वारा साझा");
  expect(hindi.readOnly).toBe(SHARE_LABELS_EN.readOnly);
  expect(shareLabels(undefined)).toEqual(SHARE_LABELS_EN);
  expect(localeOf({ locale: "hi" })).toBe("hi");
  expect(localeOf({})).toBe("en");
  expect(fill("{product} · लाइव रिपोर्ट", { product: "Design Workshop" })).toBe("Design Workshop · लाइव रिपोर्ट");
  expect(fill("{n} more {missing}", { n: 3 })).toBe("3 more {missing}");
  expect(filterLabel({ ...SHARE_LABELS_EN, "filter.status": "समीक्षा की स्थिति" }, "status")).toBe("समीक्षा की स्थिति");
  expect(filterLabel(SHARE_LABELS_EN, "craft")).toBe("craft");
});

test("Hindi dates name the month in Hindi", () => {
  expect(dayLabel("2026-11-03", "hi")).toBe("3 नवंबर");
  expect(dayLabel("2026-11-03")).toBe("3 Nov");
  expect(formatWhen("2026-11-05T10:00:00Z", "hi")).not.toBe("");
  expect(formatWhen("2026-11-05T10:00:00Z", "hi")).not.toBe(formatWhen("2026-11-05T10:00:00Z"));
});

test("weekly charts are told apart by their period, whatever the title's language", () => {
  const chart: ShareChart = { key: "recordingsOverTime", title: "प्रति सप्ताह रिकॉर्डिंग", kind: "column", categories: [], series: [], period: "week" };
  expect(isWeekly(chart)).toBe(true);
  expect(isWeekly({ ...chart, period: undefined })).toBe(false);
  expect(isWeekly({ ...chart, period: undefined, title: "Recordings per week" })).toBe(true);
});

test("the page's own words match the server's i18n strings in both languages", () => {
  const strings = (locale: string) =>
    JSON.parse(readFileSync(join(__dirname, "..", "..", "backend", "app", "services", "datareport", `i18n_${locale}.json`), "utf8")) as Record<string, string>;
  const en = strings("en");
  const hi = strings("hi");
  for (const key of Object.keys(SHARE_LABELS_EN) as (keyof typeof SHARE_LABELS_EN)[]) {
    expect(en[`share.${key}`], key).toBe(SHARE_LABELS_EN[key]);
    expect(hi[`share.${key}`], key).toBe(SHARE_LABELS_HI[key]);
  }
});

test("the lang query picks the page's language and old links stay English", () => {
  expect(langOf("hi")).toBe("hi");
  expect(langOf(null)).toBe("en");
  expect(langOf("fr")).toBe("en");
  expect(shareLabels(null, "hi").expired).toBe(SHARE_LABELS_HI.expired);
  expect(shareLabels(null).expired).toBe(SHARE_LABELS_EN.expired);
  expect(shareLabels({ locale: "hi", labels: { tryAgain: "x" } }).tryAgain).toBe("x");
  expect(shareLabels({ locale: "hi" }).opening).toBe(SHARE_LABELS_HI.opening);
});

test("older opens page in until the last one, and an ended link is never cut from the list", () => {
  const view = (n: number) => ({ id: `v${n}`, openedAt: "2026-11-05T10:00:00Z", viewer: null, visitor: null, userAgent: null });
  const first = { items: Array.from({ length: 500 }, (_, i) => view(i)), total: 531, truncated: true };
  const merged = mergeShareViews(first, { items: Array.from({ length: 31 }, (_, i) => view(500 + i)), total: 531, truncated: false });
  expect(merged.items).toHaveLength(531);
  expect(merged.items.at(-1)?.id).toBe("v530");
  expect(merged.truncated).toBe(false);
  expect(mergeShareViews(first, { items: [], total: 531, truncated: true }).truncated).toBe(false);
  const source = readFileSync(join(__dirname, "..", "components", "dataExplorer", "ReportShareControls.tsx"), "utf8");
  expect(source).not.toContain(".slice(0, 5)");
  expect(source).toContain("Show older");
});
