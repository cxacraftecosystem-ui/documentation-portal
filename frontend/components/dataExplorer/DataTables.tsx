"use client";

import { FileSpreadsheet, FileText, RefreshCw, Search, Table2 } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";

import { LoadFailed, Note, Skeleton } from "@/components/dataExplorer/explorerUi";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { fetchReport, reportFileUrl } from "@/lib/dataExplorer/api";
import type { ReportResponse, ReportSheet } from "@/lib/dataExplorer/contract";
import type { DownloadItem, DownloadRequest } from "@/lib/dataExplorer/downloads";
import { percentOf } from "@/lib/dataExplorer/plan";
import { builtAgo, highlightParts } from "@/lib/dataExplorer/explorerView";
import { recallTables, rememberTables } from "@/lib/dataExplorer/tableMemory";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export const BUSY_RETRY_SECONDS = 15;
const SEARCH_DEBOUNCE_MS = 350;
const CARD_FIELDS = 4;

export function sheetColor(raw?: string | null): string {
  const fallback = "#58068c";
  if (!raw) return fallback;
  const value = raw.trim();
  if (value.startsWith("#")) return value;
  if (/^[0-9A-Fa-f]{8}$/.test(value)) return `#${value.slice(2)}`;
  if (/^[0-9A-Fa-f]{6}$/.test(value)) return `#${value}`;
  return value;
}

export function sheetCutNote(sheet: ReportSheet): string | null {
  const shown = sheet.rows.length - (sheet.capped ? 1 : 0);
  if (sheet.capped) {
    const lead = sheet.preview ? `Showing the first ${shown}. ` : "";
    return lead + (sheet.truncatedNote ?? "Too long to show in full. Open a narrower folder.");
  }
  if (!sheet.preview) return sheet.truncated ? (sheet.truncatedNote ?? null) : null;
  const of = typeof sheet.totalRows === "number" ? ` of ${sheet.totalRows.toLocaleString("en-IN")}` : "";
  return `Showing the first ${shown}${of} rows. Downloads have every row.`;
}

export function sheetCount(sheet: ReportSheet): string {
  if (typeof sheet.totalRows === "number") return sheet.totalRows.toLocaleString("en-IN");
  return sheet.preview ? `${sheet.rows.length}+` : String(sheet.rows.length);
}

export function localMatches(sheet: ReportSheet, needle: string): ReportSheet["rows"] {
  const words = needle.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return sheet.rows;
  return sheet.rows.filter((row) => {
    const text = row.map((cell) => String(cell ?? "")).join(" ").toLowerCase();
    return words.every((word) => text.includes(word));
  });
}

function Marked({ text, needle }: { text: string; needle: string }) {
  return (
    <>
      {highlightParts(text, needle).map((part, index) =>
        part.hit ? (
          <mark key={index} className="rounded-sm bg-purple-100 px-0.5 text-ink-900 dark:bg-purple-900/60">
            {part.text}
          </mark>
        ) : (
          <span key={index}>{part.text}</span>
        )
      )}
    </>
  );
}

function cellText(value: unknown): string {
  return value === null || value === undefined || value === "" ? "-" : String(value);
}

type Found = { key: string; report: ReportResponse | null; failed: boolean };

function RowCard({ sheet, row, index, needle }: { sheet: ReportSheet; row: ReportSheet["rows"][number]; index: number; needle: string }) {
  const [open, setOpen] = useState(false);
  const columns = open ? sheet.columns : sheet.columns.slice(0, CARD_FIELDS);
  return (
    <article className="grid gap-2 rounded-md border border-line-200 bg-card p-3">
      <div className="flex items-start justify-between gap-2">
        <strong className="min-w-0 text-sm font-semibold text-ink-900">
          <Marked text={cellText(row[0])} needle={needle} />
        </strong>
        <span className="shrink-0 text-xs text-ink-500">Row {index + 1}</span>
      </div>
      <dl className="grid grid-cols-2 gap-x-3 gap-y-2 text-sm">
        {columns.slice(1).map((column, offset) => (
          <div key={`${column}-${offset}`} className="min-w-0">
            <dt className="text-xs text-ink-500">{column}</dt>
            <dd className="max-h-24 overflow-y-auto wrap-break-word text-ink-900">
              <Marked text={cellText(row[offset + 1])} needle={needle} />
            </dd>
          </div>
        ))}
      </dl>
      {sheet.columns.length > CARD_FIELDS ? (
        <button type="button" className="min-h-10 justify-self-start text-sm font-semibold text-purple-700 hover:underline dark:text-purple-300" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
          {open ? "Fewer columns" : `All ${sheet.columns.length} columns`}
        </button>
      ) : null}
    </article>
  );
}

function ReportProgress({ item, now, onTryNow }: { item: DownloadItem; now: number; onTryNow: () => void }) {
  if (item.status === "running") {
    const percent = item.bytesTotal ? percentOf(item.bytesDone, item.bytesTotal) : null;
    return (
      <div className="m-4 flex flex-wrap items-center gap-3 rounded-md border border-dashed border-purple-300 bg-surface-50 px-4 py-3" role="status">
        <span className="grid h-10 w-10 place-items-center rounded-md bg-purple-100 text-purple-700 dark:bg-purple-950/50 dark:text-purple-200" aria-hidden>
          <FileSpreadsheet className="h-5 w-5" />
        </span>
        <div className="min-w-56 flex-1">
          <p className="text-sm font-semibold text-ink-900">{percent === null ? "Full report (.xlsx) is being prepared" : "Full report (.xlsx) is downloading"}</p>
          <p className="text-sm text-ink-500">The file saves by itself when it&apos;s ready.</p>
        </div>
        <ProgressBar percent={percent ?? 0} className={`h-2 w-full sm:w-48 ${percent === null ? "animate-pulse" : ""}`} aria-label="Report progress" />
      </div>
    );
  }
  if (item.status === "failed") {
    const left = item.retryAt ? Math.max(0, Math.ceil((item.retryAt - now) / 1000)) : null;
    return (
      <div className="mx-4 mb-4 flex flex-wrap items-center gap-3 rounded-md border border-red-300 bg-error-100 px-4 py-3 text-red-900" role="alert">
        <p className="min-w-56 flex-1 text-sm">
          {item.error ?? "The report couldn't be built. Nothing was saved."}
          {left !== null ? ` We'll try again automatically in ${left} s.` : ""}
        </p>
        <button type="button" className="min-h-10 rounded-sm border border-red-300 bg-card px-3.5 text-sm font-semibold text-red-900 pointer-coarse:min-h-11" onClick={onTryNow}>
          Try now
        </button>
      </div>
    );
  }
  return null;
}

export function DataTables({
  path,
  folderName,
  extra,
  memoryKey,
  autoLoad,
  initialSheet,
  initialQuery,
  onState,
  notice,
  reportItem,
  onReport,
  onTryReport,
  onDownload,
  now
}: {
  path: string;
  folderName: string;
  extra: QueryParams;
  memoryKey: { status: string; viewer: string };
  autoLoad: boolean;
  initialSheet: string;
  initialQuery: string;
  onState: (sheet: string, q: string) => void;
  notice?: (report: ReportResponse) => string | null;
  reportItem: DownloadItem | null;
  onReport: () => void;
  onTryReport: (id: string) => void;
  onDownload: (request: DownloadRequest) => void;
  now: number;
}) {
  const extraKey = JSON.stringify(extra);
  const memoryFor = useCallback((target: string) => ({ path: target, status: memoryKey.status, viewer: memoryKey.viewer }), [memoryKey.status, memoryKey.viewer]);
  const [loaded, setLoaded] = useState<{ path: string; extraKey: string; report: ReportResponse } | null>(() => {
    const remembered = recallTables(memoryFor(path));
    return remembered ? { path, extraKey, report: remembered } : null;
  });
  const [requested, setRequested] = useState<ReadonlySet<string>>(() => new Set(autoLoad ? [path] : []));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<{ path: string; message: string } | null>(null);
  const [active, setActive] = useState(initialSheet);
  const [filter, setFilter] = useState(initialQuery);
  const [found, setFound] = useState<Found | null>(null);
  const [searching, setSearching] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const inFlight = useRef<AbortController | null>(null);
  const tabsId = useId();
  const [seenPath, setSeenPath] = useState(path);
  if (seenPath !== path) {
    setSeenPath(path);
    setFilter("");
    setFound(null);
    setError(null);
    if (autoLoad && !requested.has(path)) setRequested(new Set([...requested, path]));
  }

  const current = loaded && loaded.path === path && loaded.extraKey === extraKey ? loaded.report : null;
  const shown = loaded?.report ?? null;
  const stale = Boolean(shown && !current);
  const wanted = requested.has(path) || Boolean(recallTables(memoryFor(path)));

  useEffect(() => {
    if (current || !wanted) return;
    const remembered = recallTables(memoryFor(path));
    if (remembered) {
      setLoaded({ path, extraKey, report: remembered });
      return;
    }
    inFlight.current?.abort();
    const abort = new AbortController();
    inFlight.current = abort;
    setLoading(true);
    fetchReport({ path, extra: JSON.parse(extraKey) as QueryParams }, abort.signal)
      .then((report) => {
        if (abort.signal.aborted) return;
        setLoaded({ path, extraKey, report });
        setError(null);
        rememberTables(memoryFor(path), report);
      })
      .catch((err) => {
        if (abort.signal.aborted) return;
        setError({ path, message: err instanceof Error && err.message ? err.message : "The tables didn't load." });
      })
      .finally(() => {
        if (inFlight.current === abort) inFlight.current = null;
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [current, wanted, path, extraKey, memoryFor, attempt]);

  const sheets = useMemo(() => (shown?.sheets ?? []).filter((sheet) => sheet.rows.length > 0 || (sheet.totalRows ?? 0) > 0), [shown]);
  const index = Math.max(0, sheets.findIndex((sheet) => sheet.name === active));
  const sheet = sheets[index];
  const needle = filter.trim();
  const searchKey = `${path}\u0000${extraKey}\u0000${needle}`;

  useEffect(() => {
    if (!needle || !current) return;
    const abort = new AbortController();
    const timer = setTimeout(async () => {
      setSearching(true);
      try {
        const answer = await fetchReport({ path, q: needle, extra: JSON.parse(extraKey) as QueryParams }, abort.signal);
        if (!abort.signal.aborted) setFound({ key: searchKey, report: answer, failed: false });
      } catch {
        if (!abort.signal.aborted) setFound({ key: searchKey, report: null, failed: true });
      } finally {
        if (!abort.signal.aborted) setSearching(false);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [needle, current, path, extraKey, searchKey]);

  const result = found && found.key === searchKey ? found : null;
  const matchOf = useCallback(
    (name: string) => {
      const hit = result?.report?.sheets.find((candidate) => candidate.name === name);
      return hit ? (hit.matchedRows ?? hit.rows.length) : null;
    },
    [result]
  );
  const rows = useMemo(() => {
    if (!sheet) return [];
    if (!needle) return sheet.rows;
    const hit = result?.report?.sheets.find((candidate) => candidate.name === sheet.name);
    if (hit) return hit.rows;
    return localMatches(sheet, needle);
  }, [sheet, needle, result]);
  const serverSearch = Boolean(needle && result?.report);
  const matched = sheet ? matchOf(sheet.name) : null;

  const choose = (name: string) => {
    setActive(name);
    onState(name, filter);
  };

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, position: number) => {
    const last = sheets.length - 1;
    const target =
      event.key === "ArrowDown" || event.key === "ArrowRight"
        ? position === last
          ? 0
          : position + 1
        : event.key === "ArrowUp" || event.key === "ArrowLeft"
          ? position === 0
            ? last
            : position - 1
          : event.key === "Home"
            ? 0
            : event.key === "End"
              ? last
              : null;
    if (target === null) return;
    event.preventDefault();
    choose(sheets[target].name);
    document.getElementById(`${tabsId}-tab-${target}`)?.focus();
  };

  const blocked = shown && notice ? notice(shown) : null;
  const built = builtAgo(shown?.builtAt, now);
  const pickerOptions = sheets.map((entry) => {
    const hits = needle ? matchOf(entry.name) : null;
    return { value: entry.name, label: entry.name, hint: hits !== null ? `${hits} of ${sheetCount(entry)}` : `${sheetCount(entry)} rows` };
  });

  return (
    <section aria-label="Data tables" className="min-w-0">
      <div className="flex flex-wrap items-center gap-3 border-b border-line-200 px-4 py-4">
        <div className="mr-auto grid min-w-0 gap-0.5">
          <h2 className="flex min-w-0 items-center gap-2 font-display text-lg font-bold text-ink-900">
            <span className="truncate">{folderName}</span>
            {stale || loading ? <RefreshCw className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-label="Refreshing" /> : null}
          </h2>
          <p className="text-sm text-ink-500">{[sheets.length ? `${sheets.length} ${sheets.length === 1 ? "table" : "tables"}` : null, built, "updates when the folder changes"].filter(Boolean).join(" · ")}</p>
        </div>
        {shown ? (
          <label className="relative flex min-h-11 w-full items-center sm:w-80">
            <Search className="pointer-events-none absolute left-3 h-4 w-4 text-ink-300" aria-hidden />
            <input
              type="search"
              value={filter}
              onChange={(event) => {
                setFilter(event.target.value);
                onState(sheet?.name ?? "", event.target.value);
              }}
              placeholder="Search every row"
              aria-label={sheet ? `Search every row of ${sheet.name}` : "Search every row"}
              className="field-input min-h-11 pl-9 text-sm"
            />
          </label>
        ) : null}
        <ActionGroup align="end" className="max-sm:w-full">
          {sheet ? (
            <button
              type="button"
              className="field-button-secondary min-h-11"
              onClick={() => onDownload({ kind: "csv", label: `${sheet.name} (.csv) · ${folderName}`, url: reportFileUrl(path, "csv", { ...extra, sheet: sheet.name }), path })}
            >
              <FileText className="h-4 w-4" aria-hidden />
              This table (.csv)
            </button>
          ) : null}
          <button type="button" className="field-button min-h-11" onClick={onReport}>
            <FileSpreadsheet className="h-4 w-4" aria-hidden />
            Full report (.xlsx)
          </button>
        </ActionGroup>
      </div>
      {blocked ? <p className="border-b border-line-200 bg-surface-50 px-4 py-2 text-xs text-ink-500">{blocked}</p> : null}
      {reportItem ? <ReportProgress item={reportItem} now={now} onTryNow={() => onTryReport(reportItem.id)} /> : null}
      {error && error.path === path && !shown ? (
        <div className="p-4">
          <LoadFailed title="The tables didn't load" onRetry={() => setAttempt((value) => value + 1)}>
            {error.message}
          </LoadFailed>
        </div>
      ) : !shown && (loading || wanted) ? (
        <div className="grid gap-2 p-4" role="status" aria-label="Building tables">
          <Skeleton className="h-9 w-2/3" />
          {[0, 1, 2, 3, 4].map((row) => (
            <Skeleton key={row} className="h-8" />
          ))}
        </div>
      ) : !shown ? (
        <div className="grid justify-items-center gap-3 p-8 text-center">
          <p className="text-sm text-ink-500">Every record in the repository.</p>
          <ActionGroup>
            <button type="button" className="field-button" onClick={() => setRequested(new Set([...requested, path]))}>
              <Table2 className="h-4 w-4" aria-hidden />
              Load tables
            </button>
          </ActionGroup>
        </div>
      ) : sheets.length === 0 ? (
        <p className="p-6 text-center text-sm text-ink-500">No tables here.</p>
      ) : (
        <div className="lg:flex lg:min-h-96">
          <nav aria-label="Tables" className="hidden w-60 shrink-0 border-r border-line-200 p-3 lg:block">
            <div role="tablist" aria-orientation="vertical" aria-label="Sheets" className="grid gap-1">
              {sheets.map((entry, position) => {
                const selected = position === index;
                const hits = needle ? matchOf(entry.name) : null;
                return (
                  <button
                    key={entry.name}
                    id={`${tabsId}-tab-${position}`}
                    type="button"
                    role="tab"
                    aria-selected={selected}
                    aria-controls={`${tabsId}-panel`}
                    tabIndex={selected ? 0 : -1}
                    onClick={() => choose(entry.name)}
                    onKeyDown={(event) => onTabKey(event, position)}
                    className={`flex min-h-11 items-center justify-between gap-2 rounded-md px-3 text-left text-sm transition ${
                      selected ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-700 hover:bg-surface-50"
                    }`}
                  >
                    <span className="flex min-w-0 items-center gap-2">
                      <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: sheetColor(entry.color) }} aria-hidden />
                      <span className="truncate">{entry.name}</span>
                    </span>
                    <span className="shrink-0 text-xs tabular-nums text-ink-500">{hits !== null ? `${hits} of ${sheetCount(entry)}` : sheetCount(entry)}</span>
                  </button>
                );
              })}
            </div>
          </nav>
          <div className="min-w-0 flex-1">
            <div className="grid gap-2 border-b border-line-200 px-4 py-3 lg:hidden">
              <SearchableSelect ariaLabel="Sheet" options={pickerOptions} value={sheet?.name ?? ""} onChange={(value) => choose(String(value))} searchable advanceOnSelect={false} />
              {needle ? (
                <div className="flex gap-1.5 overflow-x-auto pb-1" aria-label="Matches per sheet">
                  {sheets.map((entry) => {
                    const hits = matchOf(entry.name);
                    return (
                      <button
                        key={entry.name}
                        type="button"
                        onClick={() => choose(entry.name)}
                        className={`min-h-9 shrink-0 rounded-full px-3 text-xs font-semibold pointer-coarse:min-h-11 ${
                          entry.name === sheet?.name ? "bg-purple-700 text-white" : hits ? "border border-line-200 bg-card text-ink-900" : "border border-line-200 bg-card text-ink-300"
                        }`}
                      >
                        {entry.name} {hits ?? "…"}
                      </button>
                    );
                  })}
                </div>
              ) : null}
            </div>
            {sheet ? (
              <div id={`${tabsId}-panel`} role="tabpanel" aria-labelledby={`${tabsId}-tab-${index}`} className="min-w-0">
                <div className="flex flex-wrap items-center gap-2 border-b border-line-200 px-4 py-3 text-sm" role="status" aria-live="polite">
                  {searching ? (
                    <span className="text-ink-500">Searching…</span>
                  ) : needle ? (
                    serverSearch ? (
                      <>
                        <span className="rounded-full bg-purple-100 px-2.5 py-0.5 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">
                          {(matched ?? rows.length).toLocaleString("en-IN")} {matched === 1 ? "row matches" : "rows match"} “{needle}”
                        </span>
                        <span className="text-ink-500">across all {sheetCount(sheet)} rows, not just the preview</span>
                      </>
                    ) : (
                      <span className="text-ink-500">
                        {rows.length} of the first {sheet.rows.length} rows match. Download for every row.
                      </span>
                    )
                  ) : (
                    <span className="text-ink-500">
                      {sheet.columns.length} columns · {sheetCount(sheet)} rows
                    </span>
                  )}
                  {stale ? <span className="text-ink-500">· showing the last folder until this one is ready</span> : null}
                </div>
                {!needle && sheetCutNote(sheet) ? (
                  <div className="mx-4 mt-3">
                    <Note tone="warn">{sheetCutNote(sheet)}</Note>
                  </div>
                ) : null}
                <div className="grid gap-2 p-3 sm:hidden" aria-label={`${sheet.name} rows`}>
                  {rows.length === 0 ? <p className="py-6 text-center text-sm text-ink-500">No rows match “{needle}”.</p> : rows.map((row, rowIndex) => <RowCard key={rowIndex} sheet={sheet} row={row} index={rowIndex} needle={needle} />)}
                </div>
                <div className="max-h-128 overflow-auto max-sm:hidden" tabIndex={0} role="region" aria-label={`${sheet.name} rows`}>
                  <table className="w-full text-left text-sm">
                    <thead className="sticky top-0 z-10 bg-surface-50 text-xs uppercase tracking-wide text-ink-500">
                      <tr>
                        <th scope="col" className="w-12 px-3 py-2.5 text-right font-semibold">
                          #
                        </th>
                        {sheet.columns.map((column, position) => (
                          <th key={`${column}-${position}`} scope="col" className="whitespace-nowrap px-3 py-2.5 font-semibold" style={{ minWidth: 96 }}>
                            {column}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-line-200">
                      {rows.length === 0 ? (
                        <tr>
                          <td colSpan={sheet.columns.length + 1} className="px-3 py-6 text-center text-sm text-ink-500">
                            No rows match “{needle}”.
                          </td>
                        </tr>
                      ) : (
                        rows.map((row, rowIndex) => (
                          <tr key={rowIndex} className="align-top hover:bg-surface-50">
                            <td className="px-3 py-2.5 text-right text-xs text-ink-500">{rowIndex + 1}</td>
                            {sheet.columns.map((_column, cellIndex) => (
                              <td key={cellIndex} className={`max-w-md px-3 py-2.5 text-ink-700 ${cellIndex === 0 ? "font-semibold text-ink-900" : ""}`}>
                                <div className="max-h-32 overflow-y-auto whitespace-pre-line wrap-break-word">
                                  <Marked text={cellText(row[cellIndex])} needle={needle} />
                                </div>
                              </td>
                            ))}
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
          </div>
        </div>
      )}
      {stale && error && error.path === path ? (
        <div className="p-4">
          <LoadFailed title="This folder's tables didn't load" onRetry={() => setAttempt((value) => value + 1)}>
            {error.message}
          </LoadFailed>
        </div>
      ) : null}
    </section>
  );
}
