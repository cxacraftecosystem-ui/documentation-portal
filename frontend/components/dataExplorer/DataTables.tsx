"use client";

import { FileSpreadsheet, FileText, Search, Table2 } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";

import { Note, Problem, Spinner } from "@/components/dataExplorer/explorerUi";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { fetchReport, reportFileUrl, statusOf } from "@/lib/dataExplorer/api";
import type { ReportResponse, ReportSheet } from "@/lib/dataExplorer/contract";
import { recallTables, rememberTables } from "@/lib/dataExplorer/tableMemory";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export const BUSY_RETRY_SECONDS = 60;
const SEARCH_DEBOUNCE_MS = 350;

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

type Searched = { q: string; sheet: string; result: ReportSheet | null; server: boolean };

export function DataTables({
  path,
  folderName,
  extra,
  memoryKey,
  autoLoad,
  initialSheet,
  initialQuery,
  onState,
  notice
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
}) {
  const remembered = useMemo(() => recallTables({ path, status: memoryKey.status, viewer: memoryKey.viewer }), [path, memoryKey.status, memoryKey.viewer]);
  const [report, setReport] = useState<ReportResponse | null>(remembered);
  const [requested, setRequested] = useState(autoLoad || Boolean(remembered));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyRetry, setBusyRetry] = useState(false);
  const [active, setActive] = useState(initialSheet);
  const [filter, setFilter] = useState(initialQuery);
  const [searched, setSearched] = useState<Searched | null>(null);
  const [searching, setSearching] = useState(false);
  const inFlight = useRef<AbortController | null>(null);
  const retried = useRef(false);
  const tabsId = useId();
  const extraKey = JSON.stringify(extra);

  const load = useCallback(async () => {
    inFlight.current?.abort();
    const abort = new AbortController();
    inFlight.current = abort;
    setLoading(true);
    setError(null);
    try {
      const next = await fetchReport({ path, extra: JSON.parse(extraKey) as QueryParams }, abort.signal);
      if (abort.signal.aborted) return;
      setReport(next);
      setBusyRetry(false);
      rememberTables({ path, status: memoryKey.status, viewer: memoryKey.viewer }, next);
    } catch (err) {
      if (abort.signal.aborted) return;
      if (statusOf(err) === 503 && !retried.current) {
        retried.current = true;
        setBusyRetry(true);
        return;
      }
      setBusyRetry(false);
      setError(err instanceof Error && err.message ? err.message : "The tables didn't load.");
    } finally {
      if (inFlight.current === abort) inFlight.current = null;
      if (!abort.signal.aborted) setLoading(false);
    }
  }, [path, extraKey, memoryKey.status, memoryKey.viewer]);

  useEffect(() => {
    if (!busyRetry) return;
    const timer = setTimeout(() => void load(), BUSY_RETRY_SECONDS * 1000);
    return () => clearTimeout(timer);
  }, [busyRetry, load]);

  useEffect(() => {
    if (requested && !report) void load();
  }, [requested, report, load]);

  useEffect(() => () => inFlight.current?.abort(), []);

  const sheets = useMemo(() => (report?.sheets ?? []).filter((sheet) => sheet.rows.length > 0), [report]);
  const index = Math.max(0, sheets.findIndex((sheet) => sheet.name === active));
  const sheet = sheets[index];
  const needle = filter.trim();

  useEffect(() => {
    if (!sheet || !needle) return;
    const abort = new AbortController();
    const timer = setTimeout(async () => {
      setSearching(true);
      try {
        const answer = await fetchReport({ path, sheet: sheet.name, q: needle, extra: JSON.parse(extraKey) as QueryParams }, abort.signal);
        if (abort.signal.aborted) return;
        const hit = answer.sheets.find((candidate) => candidate.name === sheet.name) ?? null;
        const server = Boolean(hit && (typeof hit.matchedRows === "number" || answer.query === needle));
        setSearched({ q: needle, sheet: sheet.name, result: server ? hit : null, server });
      } catch {
        if (!abort.signal.aborted) setSearched({ q: needle, sheet: sheet.name, result: null, server: false });
      } finally {
        if (!abort.signal.aborted) setSearching(false);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [needle, sheet, path, extraKey]);

  const current = searched && searched.q === needle && searched.sheet === sheet?.name ? searched : null;
  const rows = useMemo(() => {
    if (!sheet) return [];
    if (!needle) return sheet.rows;
    if (current?.server && current.result) return current.result.rows;
    return localMatches(sheet, needle);
  }, [sheet, needle, current]);
  const serverSearch = Boolean(current?.server);

  const choose = (name: string) => {
    setActive(name);
    setFilter("");
    onState(name, "");
  };

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, position: number) => {
    const last = sheets.length - 1;
    const target =
      event.key === "ArrowRight" ? (position === last ? 0 : position + 1) : event.key === "ArrowLeft" ? (position === 0 ? last : position - 1) : event.key === "Home" ? 0 : event.key === "End" ? last : null;
    if (target === null) return;
    event.preventDefault();
    choose(sheets[target].name);
    document.getElementById(`${tabsId}-tab-${target}`)?.focus();
  };

  const xlsx = reportFileUrl(path, "xlsx", extra);
  const blocked = report && notice ? notice(report) : null;

  return (
    <section className="panel overflow-hidden" aria-label="Data tables">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <h2 className="flex min-w-0 flex-1 items-center gap-2 font-display font-bold text-ink-900">
          <Table2 className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
          <span className="truncate">Tables · {folderName}</span>
        </h2>
        <ButtonRow size="sm">
          <a href={xlsx} download className="field-button-secondary min-h-9 px-3 text-sm pointer-coarse:min-h-11">
            <FileSpreadsheet className="h-4 w-4" aria-hidden />
            Report .xlsx
          </a>
          {sheet ? (
            <a
              href={reportFileUrl(path, "csv", { ...extra, sheet: sheet.name })}
              download
              className="field-button-secondary min-h-9 px-3 text-sm pointer-coarse:min-h-11"
            >
              <FileText className="h-4 w-4" aria-hidden />
              {sheet.name} .csv
            </a>
          ) : null}
        </ButtonRow>
      </div>
      {blocked ? <p className="border-t border-line-200 bg-surface-50 px-4 py-2 text-xs text-ink-500">{blocked}</p> : null}
      <div className="border-t border-line-200">
        {busyRetry ? (
          <div className="p-4">
            <Note tone="warn">Busy, retrying in {BUSY_RETRY_SECONDS} s.</Note>
          </div>
        ) : error ? (
          <div className="p-4">
            <Problem onRetry={() => void load()}>{error}</Problem>
          </div>
        ) : loading && !report ? (
          <div className="p-4">
            <Spinner label="Building tables…" />
          </div>
        ) : !report ? (
          <div className="grid justify-items-center gap-3 p-6 text-center">
            <p className="text-sm text-ink-500">Every record in the repository.</p>
            <ButtonRow>
              <button type="button" className="field-button" onClick={() => setRequested(true)}>
                <Table2 className="h-4 w-4" aria-hidden />
                Load tables
              </button>
            </ButtonRow>
          </div>
        ) : sheets.length === 0 ? (
          <p className="p-4 text-sm text-ink-500">No tables here.</p>
        ) : (
          <>
            <div className="flex gap-1 overflow-x-auto border-b border-line-200 bg-surface-50 px-2 py-2" role="tablist" aria-label="Sheets">
              {sheets.map((entry, position) => {
                const selected = position === index;
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
                    className={`inline-flex min-h-9 shrink-0 items-center gap-2 rounded-md px-3 text-xs font-semibold transition pointer-coarse:min-h-11 ${
                      selected ? "bg-card text-ink-900 shadow-sm" : "text-ink-500 hover:bg-card/60 hover:text-ink-900"
                    }`}
                  >
                    <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: sheetColor(entry.color) }} aria-hidden />
                    {entry.name}
                    <span className="text-ink-500">{sheetCount(entry)}</span>
                  </button>
                );
              })}
            </div>
            {sheet ? (
              <div id={`${tabsId}-panel`} role="tabpanel" aria-labelledby={`${tabsId}-tab-${index}`} className="min-w-0">
                <div className="flex flex-wrap items-center gap-3 px-4 py-3">
                  <label className="relative flex min-w-0 flex-1 items-center">
                    <Search className="pointer-events-none absolute left-3 h-3.5 w-3.5 text-ink-300" aria-hidden />
                    <input
                      type="search"
                      value={filter}
                      onChange={(event) => {
                        setFilter(event.target.value);
                        onState(sheet.name, event.target.value);
                      }}
                      placeholder={`Search ${sheet.name.toLowerCase()}`}
                      aria-label={`Search every row of ${sheet.name}`}
                      className="field-input py-1.5 pl-8 text-sm"
                    />
                  </label>
                  <span className="text-xs text-ink-500" role="status" aria-live="polite">
                    {searching
                      ? "Searching…"
                      : needle
                        ? serverSearch
                          ? `${(current?.result?.matchedRows ?? rows.length).toLocaleString("en-IN")} matching rows`
                          : `${rows.length} of the first ${sheet.rows.length} rows`
                        : `${sheet.columns.length} columns`}
                  </span>
                </div>
                {needle && !searching && !serverSearch ? (
                  <p className="mx-4 mb-3 text-xs text-ink-500">Searching the rows shown. Download for every row.</p>
                ) : !needle && sheetCutNote(sheet) ? (
                  <div className="mx-4 mb-3">
                    <Note tone="warn">{sheetCutNote(sheet)}</Note>
                  </div>
                ) : null}
                <div className="max-h-128 overflow-auto border-t border-line-200" tabIndex={0} role="region" aria-label={`${sheet.name} rows`}>
                  <table className="w-full text-left text-sm">
                    <thead className="sticky top-0 z-10 bg-surface-50 text-xs uppercase text-ink-500 shadow-sm">
                      <tr>
                        <th scope="col" className="w-12 px-3 py-2 text-right font-bold text-ink-500">
                          #
                        </th>
                        {sheet.columns.map((column, position) => (
                          <th key={`${column}-${position}`} scope="col" className="whitespace-nowrap px-3 py-2 font-bold text-ink-900" style={{ minWidth: 96 }}>
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
                          <tr key={rowIndex} className="align-top hover:bg-purple-50/40">
                            <td className="px-3 py-2 text-right text-xs text-ink-500">{rowIndex + 1}</td>
                            {sheet.columns.map((_column, cellIndex) => {
                              const value = row[cellIndex];
                              return (
                                <td key={cellIndex} className="max-w-md px-3 py-2 text-ink-700">
                                  <div className="max-h-32 overflow-y-auto whitespace-pre-line wrap-break-word">
                                    {value === null || value === undefined || value === "" ? "-" : String(value)}
                                  </div>
                                </td>
                              );
                            })}
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
          </>
        )}
      </div>
    </section>
  );
}
