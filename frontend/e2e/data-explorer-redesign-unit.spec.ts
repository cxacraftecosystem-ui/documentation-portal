import { expect, test } from "@playwright/test";

import { legacyHits, locatedOf, recordPageHref, unifiedHits } from "@/components/dataExplorer/paletteSearch";
import { reportOptionParams } from "@/lib/dataExplorer/api";
import { DEFAULT_REPORT_OPTIONS, withReportDefaults, type ReportResponse, type TreeEntry } from "@/lib/dataExplorer/contract";
import { fileNameFrom, looksLikeZip, nextToStart, OUTCOME_CARD, outcomeOf, retryDelaySeconds, ROW_TONE_CLASS, TONE_CLASS, toneOf, trayHeadline, type DownloadItem } from "@/lib/dataExplorer/downloads";
import { builtAgo, clampTreeWidth, estimateLine, filterOf, followingCursor, highlightParts, mergeNavigation, pageOf, percentLabel, readTreeWidth, sortArtisans, thumbnailEdge, TREE_WIDTH, treeWidthBounds, treeWidthForKey, viewLink, viewSummary } from "@/lib/dataExplorer/explorerView";
import { fetchDownload, NotAFile } from "@/lib/dataExplorer/fetchDownload";
import { filtersLeaveNothing, recordLine, reportFilterChips, withoutStatus } from "@/lib/dataExplorer/reportFilters";
import { DEFAULT_URL_STATE } from "@/lib/dataExplorer/urlState";

function item(status: DownloadItem["status"], kind: DownloadItem["kind"] = "file"): DownloadItem {
  return {
    id: `${status}-${Math.random()}`,
    kind,
    label: status,
    status,
    url: null,
    fileName: null,
    mediaId: null,
    path: null,
    bytesDone: 0,
    bytesTotal: null,
    filesDone: null,
    filesTotal: null,
    rate: null,
    timeLeft: null,
    current: null,
    note: null,
    error: null,
    retryAt: null,
    finishedAt: null,
    failedFiles: []
  };
}

test.describe("@qf:X13 the downloads tray", () => {
  test("the headline counts running, waiting, done and failed", () => {
    expect(trayHeadline([item("running"), item("waiting"), item("saved"), item("saved"), item("failed")])).toBe("1 running · 1 waiting · 2 done · 1 failed");
    expect(trayHeadline([])).toBe("Nothing yet");
  });

  test("a partial download never reads as success", () => {
    expect(outcomeOf(item("saved"))).toBe("saved");
    expect(outcomeOf({ ...item("saved", "zip"), failedFiles: [{ path: "missing.jpg", reason: "404" }] })).toBe("partial");
    expect(outcomeOf(item("failed"))).toBe("failed");
    expect(OUTCOME_CARD.partial).not.toContain("green");
    expect(OUTCOME_CARD.partial).not.toContain("success");
    expect(OUTCOME_CARD.partial).toContain("amber");
    expect(OUTCOME_CARD.saved).toContain("bg-success-100");
    expect(OUTCOME_CARD.failed).toContain("bg-error-100");
  });

  test("saved is green and failed is red, every other state stays neutral", () => {
    expect(toneOf("saved")).toBe("saved");
    expect(toneOf("failed")).toBe("failed");
    for (const status of ["waiting", "running", "cancelled"] as const) expect(toneOf(status)).toBe("neutral");
    expect(TONE_CLASS.saved).toContain("bg-success-100");
    expect(TONE_CLASS.saved).toContain("border-green-300");
    expect(TONE_CLASS.failed).toContain("bg-error-100");
    expect(TONE_CLASS.failed).toContain("border-red-300");
    for (const tone of Object.values(ROW_TONE_CLASS)) {
      expect(tone, "no side stripe on a row").not.toMatch(/inset|border-l/);
    }
  });

  test("one job runs at a time and the oldest waiting one goes next", () => {
    const older = item("waiting");
    const newer = item("waiting");
    expect(nextToStart([newer, older])).toBe(newer);
    expect(nextToStart([item("running"), older])).toBeNull();
  });

  test("a file name comes from the server's Content-Disposition", () => {
    expect(fileNameFrom("attachment; filename*=UTF-8''Dataset_Report_Bagru%202026.xlsx", "x")).toBe("Dataset_Report_Bagru 2026.xlsx");
    expect(fileNameFrom('attachment; filename="report.xlsx"', "x")).toBe("report.xlsx");
    expect(fileNameFrom(null, "fallback.bin")).toBe("fallback.bin");
  });

  test("a workbook must start with the zip signature", () => {
    expect(looksLikeZip(new Uint8Array([0x50, 0x4b, 0x03, 0x04]))).toBe(true);
    expect(looksLikeZip(new TextEncoder().encode('{"detail"'))).toBe(false);
    expect(looksLikeZip(new Uint8Array([0x50, 0x4b]))).toBe(false);
  });

  test("retries back off and honour Retry-After", () => {
    expect(retryDelaySeconds(1, null)).toBe(5);
    expect(retryDelaySeconds(2, null)).toBe(10);
    expect(retryDelaySeconds(9, null)).toBe(60);
    expect(retryDelaySeconds(1, 15)).toBe(15);
  });
});

test.describe("@qf:X13 xlsx is fetched, checked and never saved as an error", () => {
  const realFetch = globalThis.fetch;
  test.afterEach(() => {
    globalThis.fetch = realFetch;
  });

  test("an error answer throws and nothing is saved", async () => {
    globalThis.fetch = async () => new Response(JSON.stringify({ detail: "Reports are busy." }), { status: 503, headers: { "content-type": "application/json", "retry-after": "15" } });
    await expect(fetchDownload("/api/data/report?format=xlsx", { expect: "xlsx", fallbackName: "r.xlsx" })).rejects.toMatchObject({ status: 503, retryAfter: 15, message: "Reports are busy." });
  });

  test("a 200 that is not a workbook is refused", async () => {
    globalThis.fetch = async () => new Response("<html>oops</html>", { status: 200, headers: { "content-type": "text/html" } });
    await expect(fetchDownload("/api/data/report?format=xlsx", { expect: "xlsx", fallbackName: "r.xlsx" })).rejects.toBeInstanceOf(NotAFile);
  });

  test("a real workbook comes back as a blob with the server's name and progress", async () => {
    const body = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4]);
    globalThis.fetch = async () => new Response(body, { status: 200, headers: { "content-type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "content-length": String(body.length), "content-disposition": 'attachment; filename="Report.xlsx"' } });
    const seen: number[] = [];
    const fetched = await fetchDownload("/api/data/report?format=xlsx", { expect: "xlsx", fallbackName: "r.xlsx", onProgress: (progress) => seen.push(progress.bytesDone) });
    expect(fetched.name).toBe("Report.xlsx");
    expect(fetched.blob.size).toBe(8);
    expect(seen.at(-1)).toBe(8);
  });

  test("a report never asks for a byte range, a file resumes from where it stopped", async () => {
    const asked: (string | null)[] = [];
    globalThis.fetch = async (_url, init) => {
      asked.push(new Headers(init?.headers).get("range"));
      return new Response(new Uint8Array([0x50, 0x4b, 0x03, 0x04]), { status: 200, headers: { "content-type": "application/octet-stream" } });
    };
    const resume = { chunks: [new Uint8Array([1, 2])], bytes: 2, total: 6, etag: null };
    await fetchDownload("/r", { expect: "xlsx", fallbackName: "r.xlsx", resume });
    await fetchDownload("/f", { expect: "any", fallbackName: "f.bin", resume });
    expect(asked).toEqual([null, "bytes=2-"]);
  });
});

test.describe("@qf:X14 the redesigned folder view", () => {
  const file = (mediaType: string | undefined, content?: string): TreeEntry => ({ name: "x", path: `p/${mediaType}`, kind: "file", mediaType, mediaId: "m", content });

  test("type pills group files the way the include tiles do", () => {
    expect(filterOf(file("IMAGE"))).toBe("images");
    expect(filterOf(file("VIDEO"))).toBe("videos");
    expect(filterOf(file("AUDIO"))).toBe("audios");
    expect(filterOf(file("PDF"))).toBe("documents");
    expect(filterOf(file("OTHER"))).toBe("other");
  });

  test("pages say exactly which rows are shown", () => {
    const rows = Array.from({ length: 48 }, (_, index) => index);
    expect(pageOf(rows, 1, 25)).toMatchObject({ page: 1, pages: 2, first: 1, last: 25 });
    expect(pageOf(rows, 9, 25)).toMatchObject({ page: 2, first: 26, last: 48 });
    expect(pageOf([], 1, 25)).toMatchObject({ pages: 1, first: 0, last: 0 });
  });

  test("a second navigation builds on the first before the URL commits", () => {
    const first = mergeNavigation(DEFAULT_URL_STATE, { view: "tables", q: "indigo" });
    const second = mergeNavigation(first, { browse: "tool", browseQ: "vat" });
    expect(second).toMatchObject({ view: "tables", q: "indigo", browse: "tool", browseQ: "vat" });
  });
});

test.describe("@qf:X15 tables, report options and saved views", () => {
  test("built-ago reads in minutes and hours", () => {
    const now = Date.parse("2026-11-05T12:00:00Z");
    expect(builtAgo("2026-11-05T11:58:00Z", now)).toBe("built 2 min ago");
    expect(builtAgo("2026-11-05T09:00:00Z", now)).toBe("built 3 h ago");
    expect(builtAgo(null, now)).toBeNull();
  });

  test("matches are marked case-insensitively and regex characters are literal", () => {
    expect(highlightParts("Indigo vat (indigo)", "indigo")).toEqual([
      { text: "Indigo", hit: true },
      { text: " vat (", hit: false },
      { text: "indigo", hit: true },
      { text: ")", hit: false }
    ]);
    expect(highlightParts("a+b", "a+b")).toEqual([{ text: "a+b", hit: true }]);
  });

  test("report options become query parameters, defaults send nothing", () => {
    expect(reportOptionParams(DEFAULT_REPORT_OPTIONS, "", "")).toEqual({ sheets: undefined, answers: undefined, rows: undefined, q: undefined, links: undefined, thumbnails: undefined, locale: undefined, extras: undefined, name: undefined });
    expect(
      reportOptionParams({ ...DEFAULT_REPORT_OPTIONS, sheets: ["Tools", "Media"], answers: "english", rows: "filtered", links: false, thumbnails: true, locale: "hi", extras: { quotes: true, glossary: false, charts: true } }, " indigo ", "Bagru.xlsx")
    ).toEqual({
      sheets: "Tools,Media",
      answers: "english",
      rows: "filtered",
      q: "indigo",
      links: 0,
      thumbnails: 1,
      locale: "hi",
      extras: "charts,quotes",
      name: "Bagru.xlsx"
    });
    expect(withReportDefaults({ sheets: ["Tools"], answers: "both", rows: "all", links: true } as never)).toEqual({ ...DEFAULT_REPORT_OPTIONS, sheets: ["Tools"] });
    expect(withReportDefaults({ locale: "fr" } as never).locale).toBe("en");
    expect(reportOptionParams({ ...DEFAULT_REPORT_OPTIONS, rows: "filtered" }, "", "").rows).toBeUndefined();
  });

  test("the estimate says how many sheets and that the file is checked", () => {
    const report: ReportResponse = { sheets: [{ name: "Tools", columns: ["Tool"], rows: [["a"]], totalRows: 120 }], cached: true };
    expect(estimateLine(report, 1)).toBe("Ready in about 2 s · 1 sheet · checked to open in Excel, Numbers and Google Sheets");
    expect(estimateLine(null, 1)).toBeNull();
  });

  test("a saved view describes itself and links by id", () => {
    expect(viewSummary({ path: "p", state: { view: "tables", sheet: "Tools", q: "indigo", filters: { status: "APPROVED" } } })).toBe("Tables · Tools · “indigo” · APPROVED");
    expect(viewLink("v 1", "https://portal")).toBe("https://portal/data?viewId=v%201");
  });

  test("artisans sort least complete first by default", () => {
    const rows = [
      { name: "B", fields: { filled: 40, total: 40 } },
      { name: "A", fields: { filled: 18, total: 40 } },
      { name: "C", fields: { filled: 0, total: 0 } }
    ];
    expect(sortArtisans(rows, "least").map((row) => row.name)).toEqual(["C", "A", "B"]);
    expect(sortArtisans(rows, "most").map((row) => row.name)).toEqual(["B", "A", "C"]);
    expect(sortArtisans(rows, "name").map((row) => row.name)).toEqual(["A", "B", "C"]);
    expect(percentLabel({ filled: 418, total: 480 })).toBe("87%");
    expect(percentLabel({ filled: 0, total: 0 })).toBe("-");
  });
});

test.describe("@qf:X16 the command palette's search", () => {
  test("unified groups become hits, the other portal's are left out", () => {
    const hits = unifiedHits({ groups: [{ type: "artisans", items: [{ id: "a1", title: "Kamla", subtitle: "Bagru", local: true }, { id: "r1", title: "Remote", local: false }] }, { type: "clusters", items: [{ id: "c", title: "x" }] }] });
    expect(hits).toEqual([{ id: "a1", title: "Kamla", subtitle: "Artisan · Bagru", type: "artisans", path: null }]);
  });

  test("a locate answer with no folder falls back to the record page", () => {
    expect(locatedOf({ path: "workshops/bagru/kamla", locatable: true, record: null })).toEqual({ path: "workshops/bagru/kamla", recordHref: null });
    expect(locatedOf({ path: null, locatable: false, record: { kind: "craft", id: "c 1" } })).toEqual({ path: null, recordHref: "/crafts/c%201" });
    expect(locatedOf({ path: null, locatable: false, record: { kind: "artisan", id: "a1" } }).recordHref).toBe("/artisans/a1");
    expect(locatedOf({ path: null })).toEqual({ path: null, recordHref: null });
    expect(recordPageHref({ kind: "cluster", id: "x" })).toBeNull();
    expect(recordPageHref(null)).toBeNull();
  });

  test("the legacy search maps to the same hits", () => {
    const hits = legacyHits({ artisans: [{ id: "a1", name: "Kamla", place: "Bagru" }], tools: [{ id: "t1", toolkitName: "Vat stirrer", artisanName: null }] });
    expect(hits.map((hit) => [hit.type, hit.title, hit.subtitle])).toEqual([
      ["artisans", "Kamla", "Artisan · Bagru"],
      ["tools", "Vat stirrer", "Tool"]
    ]);
  });
});

test("report filters: chips name what narrows the report, and an empty result is spotted before any download", () => {
  const rejected = { active: true, label: "Rejected" };
  expect(reportFilterChips(undefined, true, "")).toEqual([]);
  expect(reportFilterChips({ active: false, label: "All" }, true, "")).toEqual([]);
  expect(reportFilterChips(rejected, false, " indigo ")).toEqual([
    { label: "Review status", value: "Rejected" },
    { label: "Search", value: "indigo" }
  ]);
  expect(reportFilterChips(rejected, true, "")).toEqual([]);
  expect(withoutStatus({ status: "REJECTED", path: "x" })).toEqual({ path: "x" });
  const list = {
    path: "",
    extras: [],
    locales: ["en"],
    records: 0,
    sheets: [
      { key: "workshops", name: "Workshops", count: 0, record: true },
      { key: "crafts", name: "Crafts", count: 460, record: false },
      { key: "media", name: "Media", count: 0, record: true },
      { key: "transcripts", name: "Transcripts", count: null, record: true }
    ]
  };
  expect(recordLine(list)).toBe("Workshops 0 · Media 0");
  expect(filtersLeaveNothing(list, true)).toBe(true);
  expect(filtersLeaveNothing(list, false)).toBe(false);
  expect(filtersLeaveNothing({ ...list, records: 3 }, true)).toBe(false);
  expect(filtersLeaveNothing(null, true)).toBe(false);
});

test("the folders panel width stays between its floor and half the explorer, and steps by key", () => {
  expect(treeWidthBounds(null)).toEqual({ min: TREE_WIDTH.min, max: TREE_WIDTH.max });
  expect(treeWidthBounds(1300)).toEqual({ min: 240, max: 560 });
  expect(treeWidthBounds(900)).toEqual({ min: 240, max: 404 });
  expect(treeWidthBounds(300)).toEqual({ min: 240, max: 240 });
  const bounds = treeWidthBounds(1300);
  expect(clampTreeWidth(100, bounds)).toBe(240);
  expect(clampTreeWidth(9999, bounds)).toBe(560);
  expect(clampTreeWidth(Number.NaN, bounds)).toBe(TREE_WIDTH.initial);
  expect(treeWidthForKey("ArrowRight", 326, bounds)).toBe(342);
  expect(treeWidthForKey("ArrowLeft", 326, bounds)).toBe(310);
  expect(treeWidthForKey("ArrowRight", 326, bounds, true)).toBe(390);
  expect(treeWidthForKey("Home", 326, bounds)).toBe(240);
  expect(treeWidthForKey("End", 326, bounds)).toBe(560);
  expect(treeWidthForKey("Enter", 326, bounds)).toBeNull();
  expect(readTreeWidth("400")).toBe(400);
  expect(readTreeWidth("10")).toBe(240);
  expect(readTreeWidth("wide")).toBeNull();
  expect(readTreeWidth(null)).toBeNull();
});

test("a thumbnail asks for the smallest stored size that covers the tile", () => {
  expect(thumbnailEdge(48)).toBe(256);
  expect(thumbnailEdge(48, 3)).toBe(256);
  expect(thumbnailEdge(240)).toBe(256);
  expect(thumbnailEdge(240, 2)).toBe(512);
  expect(thumbnailEdge(485)).toBe(512);
  expect(thumbnailEdge(1200, 2)).toBe(512);
});

test("the pager stops when the server repeats a cursor", () => {
  expect(followingCursor("25", "50", new Set(["25"]))).toBe("50");
  expect(followingCursor("25", "25", new Set(["25"]))).toBeNull();
  expect(followingCursor("50", "25", new Set(["25", "50"]))).toBeNull();
  expect(followingCursor("50", null, new Set(["50"]))).toBeNull();
});
