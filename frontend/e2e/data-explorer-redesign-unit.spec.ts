import { expect, test } from "@playwright/test";

import { legacyHits, unifiedHits } from "@/components/dataExplorer/paletteSearch";
import { reportOptionParams } from "@/lib/dataExplorer/api";
import { DEFAULT_REPORT_OPTIONS, type ReportResponse, type TreeEntry } from "@/lib/dataExplorer/contract";
import { fileNameFrom, looksLikeZip, nextToStart, retryDelaySeconds, ROW_TONE_CLASS, TONE_CLASS, toneOf, trayHeadline, type DownloadItem } from "@/lib/dataExplorer/downloads";
import { builtAgo, estimateLine, filterOf, highlightParts, mergeNavigation, pageOf, percentLabel, sortArtisans, viewLink, viewSummary } from "@/lib/dataExplorer/explorerView";
import { fetchDownload, NotAFile } from "@/lib/dataExplorer/fetchDownload";
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
    expect(reportOptionParams(DEFAULT_REPORT_OPTIONS, "", "")).toEqual({ sheets: undefined, answers: undefined, rows: undefined, q: undefined, links: undefined, name: undefined });
    expect(reportOptionParams({ sheets: ["Tools", "Media"], answers: "english", rows: "filtered", links: false }, " indigo ", "Bagru.xlsx")).toEqual({
      sheets: "Tools,Media",
      answers: "english",
      rows: "filtered",
      q: "indigo",
      links: 0,
      name: "Bagru.xlsx"
    });
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

  test("the legacy search maps to the same hits", () => {
    const hits = legacyHits({ artisans: [{ id: "a1", name: "Kamla", place: "Bagru" }], tools: [{ id: "t1", toolkitName: "Vat stirrer", artisanName: null }] });
    expect(hits.map((hit) => [hit.type, hit.title, hit.subtitle])).toEqual([
      ["artisans", "Kamla", "Artisan · Bagru"],
      ["tools", "Vat stirrer", "Tool"]
    ]);
  });
});
