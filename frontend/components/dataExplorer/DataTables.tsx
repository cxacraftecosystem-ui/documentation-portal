"use client";

import { Download, RefreshCw, Search } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";

import { LoadFailed, Note, Skeleton } from "@/components/dataExplorer/explorerUi";
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

export function rowNumbersOf(sheet: Pick<ReportSheet, "rows" | "rowNumbers">): number[] {
  const given = sheet.rowNumbers;
  if (Array.isArray(given) && given.length === sheet.rows.length) return given;
  return sheet.rows.map((_row, index) => index + 1);
}

export function localMatchNumbers(sheet: Pick<ReportSheet, "rows" | "rowNumbers">, needle: string): { rows: ReportSheet["rows"]; numbers: number[] } {
  const words = needle.toLowerCase().split(/\s+/).filter(Boolean);
  const numbers = rowNumbersOf(sheet);
  if (!words.length) return { rows: sheet.rows, numbers };
  const rows: ReportSheet["rows"] = [];
  const kept: number[] = [];
  sheet.rows.forEach((row, index) => {
    const text = row.map((cell) => String(cell ?? "")).join(" ").toLowerCase();
    if (words.every((word) => text.includes(word))) {
      rows.push(row);
      kept.push(numbers[index]);
    }
  });
  return { rows, numbers: kept };
}

export function rowLabel(number: number | undefined): string {
  return number && number > 0 ? String(number) : "";
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

function RowCard({ sheet, row, number, needle, compact }: { sheet: ReportSheet; row: ReportSheet["rows"][number]; number: number | undefined; needle: string; compact: boolean }) {
  const [open, setOpen] = useState(!compact);
  const [all, setAll] = useState(false);
  const columns = all ? sheet.columns : sheet.columns.slice(0, CARD_FIELDS + 1);
  const summary = sheet.columns
    .slice(1, CARD_FIELDS + 1)
    .map((_column, offset) => row[offset + 1])
    .filter((value) => value !== null && value !== undefined && String(value).trim() !== "")
    .map((value) => String(value))
    .join(" · ");
  return (
    <article className="grid gap-2 rounded-lg border border-line-200 bg-card p-3.5">
      <div className="flex items-baseline justify-between gap-2">
        <strong className="min-w-0 text-[15px] font-semibold text-ink-900">
          <Marked text={cellText(row[0])} needle={needle} />
        </strong>
        {rowLabel(number) ? <span className="shrink-0 text-xs text-ink-500">Row {rowLabel(number)}</span> : null}
      </div>
      {open ? (
        <dl className="grid grid-cols-2 gap-x-3 gap-y-1.5">
          {columns.slice(1).map((column, offset) => (
            <div key={`${column}-${offset}`} className="min-w-0">
              <dt className="text-xs text-ink-500">{column}</dt>
              <dd className="max-h-24 overflow-y-auto text-sm wrap-break-word text-ink-900">
                <Marked text={cellText(row[offset + 1])} needle={needle} />
              </dd>
            </div>
          ))}
        </dl>
      ) : (
        <button type="button" className="text-left text-[13px] text-ink-700" aria-expanded={false} onClick={() => setOpen(true)}>
          <Marked text={summary || "-"} needle={needle} />
        </button>
      )}
      {open && sheet.columns.length > CARD_FIELDS + 1 ? (
        <button type="button" className="min-h-9 justify-self-start text-[13px] font-semibold text-purple-700 dark:text-purple-300" aria-expanded={all} onClick={() => setAll((value) => !value)}>
          {all ? "Fewer columns" : `All ${sheet.columns.length} columns`}
        </button>
      ) : null}
    </article>
  );
}

function ReportProgress({ item, now, onTryNow }: { item: DownloadItem; now: number; onTryNow: () => void }) {
  if (item.status === "running" || item.status === "waiting") {
    const percent = item.bytesTotal ? percentOf(item.bytesDone, item.bytesTotal) : null;
    let sheets: number | null = null;
    try {
      const chosen = item.url ? new URL(item.url, "http://local").searchParams.get("sheets") : null;
      sheets = chosen ? chosen.split(",").filter(Boolean).length : null;
    } catch {
      sheets = null;
    }
    return (
      <div className="m-4 flex flex-wrap items-center gap-3 rounded-md border border-dashed border-[#d6b6fb] bg-surface-50 px-4 py-3.5 dark:border-purple-800" role="status">
        <span className="grid h-10 w-10 place-items-center rounded-md bg-purple-100 text-purple-700 dark:bg-purple-950/50 dark:text-purple-200" aria-hidden>
          <Download className="h-5 w-5" />
        </span>
        <div className="min-w-[220px] flex-1">
          <p className="text-sm font-semibold text-ink-900">{item.status === "waiting" ? "Full report (.xlsx) is waiting its turn" : percent === null ? "Full report (.xlsx) is being prepared" : "Full report (.xlsx) is downloading"}</p>
          <p className="text-[13px] text-ink-500">{[sheets ? `${sheets} ${sheets === 1 ? "sheet" : "sheets"}` : null, "The file downloads by itself when it’s ready."].filter(Boolean).join(" · ")}</p>
        </div>
        <div className={`h-2 w-[200px] overflow-hidden rounded-full bg-progress-track max-sm:w-full ${percent === null ? "animate-pulse" : ""}`} role="progressbar" aria-label="Report progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent ?? undefined}>
          <div className="h-full rounded-full bg-progress-fill" style={{ width: `${percent ?? 45}%` }} />
        </div>
      </div>
    );
  }
  if (item.status === "failed") {
    const left = item.retryAt ? Math.max(0, Math.ceil((item.retryAt - now) / 1000)) : null;
    const message = item.error ?? "The report couldn't be built. Nothing was saved.";
    const [lead, ...rest] = message.split(". ");
    return (
      <div className="mx-4 mb-4 flex flex-wrap items-center gap-3 rounded-md bg-error-100 px-4 py-3.5 text-red-900 dark:bg-[#4a1717] dark:text-red-100" role="alert">
        <p className="min-w-[220px] flex-1 text-[13px]">
          <strong>{rest.length ? `${lead}.` : lead}</strong>
          {rest.length ? ` ${rest.join(". ")}` : ""}
          {left !== null ? ` We’ll try again automatically in ${left} s.` : ""}
        </p>
        <button type="button" className="min-h-10 rounded-[10px] bg-card px-3.5 text-[13px] font-semibold text-red-900 dark:text-red-100 pointer-coarse:min-h-11" onClick={onTryNow}>
          Try now
        </button>
      </div>
    );
  }
  return null;
}

function ReviewCell({ text, needle }: { text: string; needle: string }) {
  const word = text.trim().toLowerCase();
  const tone = word.startsWith("approved") ? "bg-success-100 text-success-600 dark:bg-[#123b25] dark:text-green-200" : word.startsWith("pending") ? "bg-amber-100 text-amber-800 dark:bg-amber-950/50 dark:text-amber-200" : word.startsWith("rejected") ? "bg-error-100 text-red-700 dark:bg-[#4a1717] dark:text-red-200" : null;
  if (!tone) return <Marked text={text} needle={needle} />;
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${tone}`}>{text}</span>;
}

function SheetPicker({ sheets, sheet, onChoose }: { sheets: ReportSheet[]; sheet: ReportSheet | undefined; onChoose: (name: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        className="flex min-h-[52px] w-full items-center justify-between rounded-md border border-line-200 bg-card px-3.5 text-left"
      >
        <span>
          <span className="block text-xs text-ink-500">Sheet</span>
          <strong className="text-[15px] text-ink-900">{sheet?.name ?? "-"}</strong> <span className="text-[13px] text-ink-500">· {sheet ? `${sheetCount(sheet)} rows` : ""}</span>
        </span>
        <span aria-hidden className="text-ink-900">
          ▾
        </span>
      </button>
      {open ? (
        <ul role="listbox" aria-label="Sheets" className="absolute inset-x-0 top-[56px] z-20 grid max-h-80 gap-0.5 overflow-y-auto rounded-md border border-line-200 bg-card p-1 shadow-md">
          {sheets.map((entry) => (
            <li
              key={entry.name}
              role="option"
              aria-selected={entry.name === sheet?.name}
              tabIndex={0}
              onClick={() => {
                onChoose(entry.name);
                setOpen(false);
              }}
              onKeyDown={(event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                onChoose(entry.name);
                setOpen(false);
              }}
              className={`flex min-h-11 items-center justify-between rounded-sm px-3 text-sm ${entry.name === sheet?.name ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-900"}`}
            >
              <span>{entry.name}</span>
              <span className="text-xs text-ink-500">{sheetCount(entry)}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
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
  now,
  onBack
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
  onBack?: () => void;
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
  const { rows, numbers } = useMemo(() => {
    if (!sheet) return { rows: [] as ReportSheet["rows"], numbers: [] as number[] };
    if (!needle) return { rows: sheet.rows, numbers: rowNumbersOf(sheet) };
    const hit = result?.report?.sheets.find((candidate) => candidate.name === sheet.name);
    if (hit) return { rows: hit.rows, numbers: rowNumbersOf(hit) };
    return localMatchNumbers(sheet, needle);
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
  const reviewColumn = sheet ? sheet.columns.findIndex((column) => /^(review|review status|status)$/i.test(column.trim())) : -1;
  const sheetNoun = sheet ? sheet.name.toLowerCase() : "rows";
  const csv = () => {
    if (!sheet) return;
    onDownload({ kind: "csv", label: `${sheet.name} (.csv) · ${folderName}`, url: reportFileUrl(path, "csv", { ...extra, sheet: sheet.name }), path });
  };
  const searchBox = (phone: boolean) => (
    <label className={`relative flex items-center ${phone ? "min-h-12 w-full" : "min-h-11 w-80 max-lg:w-full"}`}>
      <Search className={`pointer-events-none absolute left-3 text-ink-500 ${phone ? "h-[18px] w-[18px]" : "h-4 w-4"}`} aria-hidden />
      <input
        type="search"
        value={filter}
        onChange={(event) => {
          setFilter(event.target.value);
          onState(sheet?.name ?? "", event.target.value);
        }}
        placeholder="Search every row"
        aria-label={sheet ? `Search every row of ${sheet.name}` : "Search every row"}
        className={`w-full rounded-md border bg-card pr-3 text-ink-900 outline-hidden placeholder:text-ink-300 focus:border-purple-600 ${phone ? `min-h-12 pl-10 text-[15px] ${needle ? "border-[#9148d2]" : "border-line-200"} pr-20` : "min-h-11 border-line-200 pl-9 text-sm"}`}
      />
      {phone && needle && sheet && matched !== null ? (
        <span className="pointer-events-none absolute right-3 text-xs text-ink-500">
          {matched.toLocaleString("en-IN")} of {sheetCount(sheet)}
        </span>
      ) : null}
    </label>
  );

  return (
    <section aria-label="Data tables" className="min-w-0 max-sm:fixed max-sm:inset-y-0 max-sm:left-0 max-sm:z-50 max-sm:w-screen max-sm:overflow-x-hidden max-sm:flex max-sm:flex-col max-sm:gap-3 max-sm:overflow-y-auto max-sm:bg-bg-0 max-sm:px-4 max-sm:pt-3.5 max-sm:pb-4">
      <div className="flex items-center gap-2 sm:hidden">
        {onBack ? (
          <button type="button" aria-label="Back" className="grid h-11 w-11 place-items-center rounded-md text-xl text-ink-900" onClick={onBack}>
            ‹
          </button>
        ) : null}
        <h2 className="flex-1 font-display text-xl font-extrabold text-ink-900">Tables</h2>
        <span className="truncate text-xs text-ink-500">{folderName}</span>
      </div>
      <div className="flex flex-wrap items-center gap-3 border-b border-line-200 p-4 max-sm:hidden">
        <div className="mr-auto grid min-w-0 gap-0.5">
          <h2 className="flex min-w-0 items-center gap-2 text-lg font-bold text-ink-900">
            <span className="truncate">{folderName}</span>
            {stale || loading ? <RefreshCw className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-label="Refreshing" /> : null}
          </h2>
          <p className="text-[13px] text-ink-500">{[sheets.length ? `${sheets.length} ${sheets.length === 1 ? "table" : "tables"}` : null, built, "updates when the folder changes"].filter(Boolean).join(" · ")}</p>
        </div>
        {shown ? searchBox(false) : null}
        <div className="flex gap-3">
          {sheet ? (
            <button type="button" className="min-h-11 rounded-md border border-line-200 bg-card px-4 text-sm text-ink-900 hover:border-purple-300" onClick={csv}>
              This table (.csv)
            </button>
          ) : null}
          <button type="button" className="min-h-11 rounded-md bg-purple-700 px-[18px] text-sm font-semibold text-white hover:bg-purple-800 dark:bg-purple-300 dark:text-purple-950" onClick={onReport}>
            Full report (.xlsx)
          </button>
        </div>
      </div>
      {blocked ? <p className="border-b border-line-200 bg-surface-50 px-4 py-2 text-xs text-ink-500">{blocked}</p> : null}
      {error && error.path === path && !shown ? (
        <div className="p-4 max-sm:p-0">
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
          <button type="button" className="min-h-11 rounded-md bg-purple-700 px-[18px] text-sm font-semibold text-white" onClick={() => setRequested(new Set([...requested, path]))}>
            Load tables
          </button>
        </div>
      ) : sheets.length === 0 ? (
        <p className="p-6 text-center text-sm text-ink-500">No tables here.</p>
      ) : (
        <div className="sm:flex sm:min-h-[560px] max-sm:contents">
          <nav aria-label="Tables" className="w-60 shrink-0 border-r border-line-200 p-3 max-lg:hidden">
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
                    className={`flex min-h-11 items-center justify-between gap-2 rounded-[10px] px-3 text-left text-sm transition ${
                      selected ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-700 hover:bg-surface-50"
                    }`}
                  >
                    <span className="truncate">{entry.name}</span>
                    <span className={`shrink-0 text-xs tabular-nums ${selected ? "" : "text-ink-500"}`}>{hits !== null ? `${hits} of ${sheetCount(entry)}` : sheetCount(entry)}</span>
                  </button>
                );
              })}
            </div>
          </nav>
          <div className="min-w-0 flex-1 max-sm:contents">
            <div className="grid gap-3 border-b border-line-200 px-4 py-3 lg:hidden max-sm:contents">
              <SheetPicker sheets={sheets} sheet={sheet} onChoose={choose} />
              {needle ? (
                <div className="flex min-w-0 gap-2 overflow-x-auto" aria-label="Matches per sheet">
                  {[...sheets]
                    .map((entry) => ({ entry, hits: matchOf(entry.name) }))
                    .sort((a, b) => (b.hits ?? -1) - (a.hits ?? -1))
                    .map(({ entry, hits }) => (
                      <button
                        key={entry.name}
                        type="button"
                        onClick={() => choose(entry.name)}
                        className={`min-h-8 shrink-0 rounded-full px-3 text-xs pointer-coarse:min-h-11 ${entry.name === sheet?.name ? "bg-ink-900 font-semibold text-card" : "border border-line-200 bg-card text-ink-900"}`}
                      >
                        {entry.name} {hits ?? "…"}
                      </button>
                    ))}
                </div>
              ) : null}
              <div className="sm:hidden">{searchBox(true)}</div>
            </div>
            {sheet ? (
              <div id={`${tabsId}-panel`} role="tabpanel" aria-labelledby={`${tabsId}-tab-${index}`} className="min-w-0 max-sm:contents">
                <div className="flex flex-wrap items-center gap-2 border-b border-line-200 px-4 py-3 text-[13px] text-ink-700 max-sm:hidden" role="status" aria-live="polite">
                  {searching ? (
                    <span className="text-ink-500">Searching…</span>
                  ) : needle ? (
                    serverSearch ? (
                      <>
                        <span className="rounded-full bg-purple-100 px-2.5 py-[3px] font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">
                          {(matched ?? rows.length).toLocaleString("en-IN")} {matched === 1 ? "row matches" : "rows match"} “{needle}”
                        </span>
                        <span className="text-ink-500">
                          across all {sheetCount(sheet)} {sheetNoun}, not just the preview
                        </span>
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
                  <div className="mx-4 mt-3 max-sm:mx-0 max-sm:mt-0">
                    <Note tone="warn">{sheetCutNote(sheet)}</Note>
                  </div>
                ) : null}
                <div className="grid gap-2.5 sm:hidden" aria-label={`${sheet.name} rows`}>
                  {rows.length === 0 ? <p className="py-6 text-center text-sm text-ink-500">No rows match “{needle}”.</p> : rows.map((row, rowIndex) => <RowCard key={`${numbers[rowIndex]}-${rowIndex}`} sheet={sheet} row={row} number={numbers[rowIndex]} needle={needle} compact={rowIndex > 0} />)}
                </div>
                <div className="max-h-128 overflow-auto max-sm:hidden" tabIndex={0} role="region" aria-label={`${sheet.name} rows`}>
                  <table className="w-full text-left text-sm">
                    <thead className="sticky top-0 z-10 bg-surface-50 text-xs tracking-[0.04em] text-ink-500 uppercase">
                      <tr>
                        {sheet.columns.map((column, position) => (
                          <th key={`${column}-${position}`} scope="col" className={`py-2.5 font-semibold whitespace-nowrap ${position === 0 ? "pr-2 pl-4" : position === sheet.columns.length - 1 ? "pr-4 pl-2" : "px-2"}`} style={{ minWidth: 96 }}>
                            {column}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {rows.length === 0 ? (
                        <tr>
                          <td colSpan={sheet.columns.length} className="px-3 py-6 text-center text-sm text-ink-500">
                            No rows match “{needle}”.
                          </td>
                        </tr>
                      ) : (
                        rows.map((row, rowIndex) => (
                          <tr key={rowIndex} className="border-t border-line-200 align-top" data-row-number={rowLabel(numbers[rowIndex])}>
                            {sheet.columns.map((_column, cellIndex) => (
                              <td key={cellIndex} className={`max-w-md py-3 text-ink-900 ${cellIndex === 0 ? "pr-2 pl-4 font-semibold" : cellIndex === sheet.columns.length - 1 ? "pr-4 pl-2" : "px-2"}`}>
                                <div className="max-h-32 overflow-y-auto whitespace-pre-line wrap-break-word">
                                  {cellIndex === reviewColumn ? <ReviewCell text={cellText(row[cellIndex])} needle={needle} /> : <Marked text={cellText(row[cellIndex])} needle={needle} />}
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
            {reportItem ? <ReportProgress item={reportItem} now={now} onTryNow={() => onTryReport(reportItem.id)} /> : null}
          </div>
        </div>
      )}
      {!shown && reportItem ? <ReportProgress item={reportItem} now={now} onTryNow={() => onTryReport(reportItem.id)} /> : null}
      {stale && error && error.path === path ? (
        <div className="p-4">
          <LoadFailed title="This folder's tables didn't load" onRetry={() => setAttempt((value) => value + 1)}>
            {error.message}
          </LoadFailed>
        </div>
      ) : null}
      <div className="mt-auto flex gap-2 pt-2 sm:hidden">
        {sheet ? (
          <button type="button" className="min-h-11 flex-1 rounded-md border border-line-200 bg-card text-sm text-ink-900" onClick={csv}>
            This table (.csv)
          </button>
        ) : null}
        <button type="button" className="min-h-11 flex-1 rounded-md bg-purple-700 text-sm font-semibold text-white dark:bg-purple-300 dark:text-purple-950" onClick={onReport}>
          Full report (.xlsx)
        </button>
      </div>
    </section>
  );
}
