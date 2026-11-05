"use client";

import { CheckCircle2, FileSpreadsheet, Filter, Package } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { Skeleton } from "@/components/dataExplorer/explorerUi";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Toggle } from "@/components/ui/Toggle";
import { fetchReport, fetchReportOptions, fetchReportSheets, saveReportOptions } from "@/lib/dataExplorer/api";
import { DEFAULT_REPORT_OPTIONS, withReportDefaults, type ReportLocale, type ReportOptions, type ReportResponse, type ReportSheetList, type ReportStatusFilter } from "@/lib/dataExplorer/contract";
import { exportFilename } from "@/lib/naming";
import { estimateLine } from "@/lib/dataExplorer/explorerView";
import { filtersLeaveNothing, recordLine, reportFilterChips, withoutStatus } from "@/lib/dataExplorer/reportFilters";
import type { QueryParams } from "@/lib/dataExplorer/wire";

import { sheetCount } from "@/components/dataExplorer/DataTables";
import { ReportShareControls } from "@/components/dataExplorer/ReportShareControls";

export function defaultReportName(folderName: string): string {
  return exportFilename("Dataset", "Report", "xlsx", { subject: folderName || "All Records" });
}

const SEGMENT = "min-h-10 flex-1 rounded-sm px-3 text-sm font-semibold transition pointer-coarse:min-h-11";

function Segmented<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { value: T; label: string; disabled?: boolean }[]; onChange: (value: T) => void }) {
  return (
    <div className="grid gap-1.5">
      <span className="field-label">{label}</span>
      <div role="radiogroup" aria-label={label} className="flex gap-1 rounded-md border border-line-200 bg-surface-50 p-1">
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={value === option.value}
            disabled={option.disabled}
            onClick={() => onChange(option.value)}
            className={`${SEGMENT} ${value === option.value ? "bg-card text-ink-900 shadow-sm" : "text-ink-500 hover:text-ink-900"} disabled:opacity-50`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

const LOCALE_LABEL: Record<string, string> = { en: "English", hi: "हिन्दी" };

type SheetRow = { name: string; count: string | null; description: string | null };

export function sheetRows(list: ReportSheetList | null, preview: ReportResponse | null): SheetRow[] {
  if (list && list.sheets.length) return list.sheets.map((sheet) => ({ name: sheet.name, count: typeof sheet.count === "number" ? sheet.count.toLocaleString("en-IN") : null, description: sheet.description ?? null }));
  return (preview?.sheets ?? []).map((sheet) => ({ name: sheet.name, count: sheetCount(sheet), description: null }));
}

export function ReportOptionsDialog({
  open,
  onClose,
  path,
  folderName,
  subtitle,
  extra,
  statusFilter,
  query,
  onMake,
  onPack
}: {
  open: boolean;
  onClose: () => void;
  path: string;
  folderName: string;
  subtitle: string;
  extra: QueryParams;
  statusFilter?: ReportStatusFilter;
  query: string;
  onMake: (options: ReportOptions, name: string, extra: QueryParams) => void;
  onPack?: (options: ReportOptions, name: string, extra: QueryParams) => void;
}) {
  const [report, setReport] = useState<{ path: string; report: ReportResponse | null; error: boolean } | null>(null);
  const [list, setList] = useState<{ path: string; key: string; list: ReportSheetList | null } | null>(null);
  const [unfiltered, setUnfiltered] = useState<{ path: string; list: ReportSheetList | null } | null>(null);
  const [options, setOptions] = useState<ReportOptions>(DEFAULT_REPORT_OPTIONS);
  const [name, setName] = useState("");
  const [saved, setSaved] = useState<"idle" | "saving" | "saved" | "failed">("idle");
  const statusActive = Boolean(statusFilter?.active);
  const [allStatuses, setAllStatuses] = useState(!statusActive);
  const search = options.rows === "filtered" ? query.trim() : "";
  const chosenExtra = useMemo(() => (allStatuses ? withoutStatus(extra) : extra), [allStatuses, extra]);
  const extraKey = JSON.stringify(chosenExtra);
  const baseKey = JSON.stringify(withoutStatus(extra));
  const listKey = `${extraKey}|${search}`;
  const chips = reportFilterChips(statusFilter, allStatuses, search);
  const filtered = chips.length > 0;

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setName(defaultReportName(folderName));
    setSaved("idle");
    setAllStatuses(!statusActive);
    fetchReportOptions()
      .then((remembered) => {
        if (!cancelled && remembered) setOptions(withReportDefaults(remembered));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [open, folderName, statusActive]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const key = `${extraKey}|${search}`;
    const chosen = JSON.parse(extraKey) as QueryParams;
    fetchReportSheets(path, chosen, undefined, search || undefined)
      .then((answer) => {
        if (!cancelled) setList({ path, key, list: answer });
      })
      .catch(() => {
        if (!cancelled) setList({ path, key, list: null });
      });
    fetchReport({ path, extra: chosen })
      .then((answer) => {
        if (!cancelled) setReport({ path, report: answer, error: false });
      })
      .catch(() => {
        if (!cancelled) setReport({ path, report: null, error: true });
      });
    return () => {
      cancelled = true;
    };
  }, [open, path, extraKey, search]);

  const listed = list?.path === path && list.key === listKey ? list.list : null;
  const empty = filtersLeaveNothing(listed, filtered);

  useEffect(() => {
    if (!open || !empty) return;
    let cancelled = false;
    fetchReportSheets(path, JSON.parse(baseKey) as QueryParams)
      .then((answer) => {
        if (!cancelled) setUnfiltered({ path, list: answer });
      })
      .catch(() => {
        if (!cancelled) setUnfiltered({ path, list: null });
      });
    return () => {
      cancelled = true;
    };
  }, [open, empty, path, baseKey]);

  const refreshing = list?.path === path && list.key !== listKey;
  const server = list?.path === path ? list.list : null;
  const everything = unfiltered?.path === path ? unfiltered.list : null;
  const preview = report?.path === path ? report.report : null;
  const sheets = useMemo(() => sheetRows(server, preview), [server, preview]);
  const extras = server?.extras ?? [];
  const locales = (server?.locales ?? ["en"]).filter((code) => code in LOCALE_LABEL);
  const chosen = options.sheets ? sheets.filter((sheet) => options.sheets!.includes(sheet.name)) : sheets;
  const toggleSheet = (sheetName: string) => {
    const current = options.sheets ?? sheets.map((sheet) => sheet.name);
    const next = current.includes(sheetName) ? current.filter((value) => value !== sheetName) : [...current, sheetName];
    setOptions({ ...options, sheets: next.length === sheets.length ? null : next });
  };
  const loading = open && (!list || list.path !== path || (!server?.sheets.length && (!report || report.path !== path)));
  const fileName = name.endsWith(".xlsx") ? name : `${name}.xlsx`;
  const remember = async () => {
    setSaved("saving");
    try {
      await saveReportOptions(options);
      setSaved("saved");
    } catch {
      setSaved("failed");
    }
  };
  const blocked = chosen.length === 0 && sheets.length > 0;
  const makeUnfiltered = () => {
    onMake({ ...options, rows: "all" }, fileName, withoutStatus(extra));
    onClose();
  };

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title="Report (.xlsx)"
      description={subtitle}
      icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
      className="max-w-2xl!"
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={saved === "saving"} onClick={() => void remember()}>
            {saved === "saved" ? "Remembered" : "Remember these choices"}
          </button>
          <button type="button" className="field-button-secondary" onClick={onClose}>
            Cancel
          </button>
          {onPack ? (
            <button
              type="button"
              className="field-button-secondary"
              disabled={blocked}
              onClick={() => {
                onPack(options, fileName, chosenExtra);
                onClose();
              }}
            >
              <Package className="h-4 w-4" aria-hidden />
              Report pack (.zip)
            </button>
          ) : null}
          <button
            type="button"
            className="field-button"
            disabled={blocked}
            onClick={() => {
              onMake(options, fileName, chosenExtra);
              onClose();
            }}
          >
            Make report
          </button>
        </>
      }
    >
      <div className="grid gap-5">
        <section aria-label="Filters" className="grid gap-2.5 rounded-md border border-line-200 p-3" data-testid="report-filters">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="field-label inline-flex items-center gap-1.5">
              <Filter className="h-3.5 w-3.5" aria-hidden />
              Filters
            </span>
            {chips.length ? (
              chips.map((chip) => (
                <span key={chip.label} className="inline-flex min-h-7 items-center rounded-full bg-purple-50 px-2.5 text-xs font-semibold text-purple-800 dark:bg-purple-950/40 dark:text-purple-200">
                  {chip.label}: {chip.value}
                </span>
              ))
            ) : (
              <span className="text-sm text-ink-500">None. Every record you can see.</span>
            )}
          </div>
          {statusFilter ? <Toggle label="Include all statuses" checked={allStatuses} onChange={setAllStatuses} /> : null}
        </section>
        {empty ? (
          <div role="alert" className="grid gap-2 rounded-md border border-amber-500/30 bg-amber-100 p-3 text-sm text-amber-800" data-testid="report-empty">
            <p className="font-semibold">No records match these filters.</p>
            {recordLine(listed) ? <p>{recordLine(listed)}</p> : null}
            {recordLine(everything) ? <p>Without filters: {recordLine(everything)}</p> : null}
            <button type="button" className="field-button justify-self-start" onClick={makeUnfiltered}>
              Make the unfiltered report
            </button>
          </div>
        ) : null}
        <div className="grid gap-1.5">
          <span className="field-label">Sheets</span>
          {loading ? (
            <div className="grid gap-1.5">
              {[0, 1, 2, 3].map((row) => (
                <Skeleton key={row} className="h-9" />
              ))}
            </div>
          ) : sheets.length ? (
            <ul className={`grid gap-1 transition-opacity sm:grid-cols-2 ${refreshing ? "opacity-60" : ""}`} aria-label="Sheets" aria-busy={refreshing}>
              {sheets.map((sheet) => {
                const on = !options.sheets || options.sheets.includes(sheet.name);
                return (
                  <li key={sheet.name}>
                    <label
                      title={sheet.description ?? undefined}
                      className={`flex min-h-11 cursor-pointer items-center gap-2.5 rounded-sm px-2.5 text-sm ${on ? "bg-purple-50 text-ink-900 dark:bg-purple-950/30" : "text-ink-500 hover:bg-surface-50"}`}
                    >
                      <input type="checkbox" checked={on} onChange={() => toggleSheet(sheet.name)} className="h-4.5 w-4.5 accent-purple-700" />
                      <span className="min-w-0 flex-1 truncate">{sheet.name}</span>
                      {sheet.count !== null ? <span className="text-xs tabular-nums text-ink-500">{sheet.count}</span> : null}
                    </label>
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className="text-sm text-ink-500">{report?.error ? "The sheet list didn't load. The report still has every sheet." : "Every sheet is included."}</p>
          )}
        </div>
        {extras.length ? (
          <div className="grid gap-1.5" role="group" aria-label="Extras">
            <span className="field-label">Extras</span>
            <div className="grid gap-1 sm:grid-cols-2">
              {extras.map((item) => (
                <Toggle
                  key={item.key}
                  label={item.title}
                  checked={Boolean(options.extras[item.key])}
                  onChange={(on) => setOptions({ ...options, extras: { ...options.extras, [item.key]: on } })}
                />
              ))}
            </div>
          </div>
        ) : null}
        <Segmented
          label="Answers in"
          value={options.answers}
          onChange={(answers) => setOptions({ ...options, answers })}
          options={[
            { value: "original", label: "Original" },
            { value: "english", label: "English" },
            { value: "both", label: "Both" }
          ]}
        />
        <Segmented
          label="Rows"
          value={query.trim() ? options.rows : "all"}
          onChange={(rows) => setOptions({ ...options, rows })}
          options={[
            { value: "all", label: "Everything" },
            { value: "filtered", label: query.trim() ? `Only what’s filtered (${query.trim()})` : "Only what’s filtered", disabled: !query.trim() }
          ]}
        />
        {locales.length > 1 ? (
          <Segmented
            label="Language"
            value={options.locale}
            onChange={(locale) => setOptions({ ...options, locale })}
            options={locales.map((code) => ({ value: code as ReportLocale, label: LOCALE_LABEL[code] }))}
          />
        ) : null}
        <Toggle label="Links to files open in the portal" checked={options.links} onChange={(links) => setOptions({ ...options, links })} />
        <Toggle label="Small image thumbnails" checked={options.thumbnails} onChange={(thumbnails) => setOptions({ ...options, thumbnails })} />
        <div className="grid gap-1.5">
          <label htmlFor="report-file-name" className="field-label">
            File name
          </label>
          <input id="report-file-name" className="field-input" value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        {estimateLine(preview, chosen.length) ? (
          <p className="flex items-center gap-1.5 rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">
            <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden />
            {estimateLine(preview, chosen.length)}
          </p>
        ) : null}
        {saved === "failed" ? <p className="text-sm text-error-600">Your choices weren&apos;t remembered. They still apply to this report.</p> : null}
        <div className="border-t border-line-200 pt-4">
          <ReportShareControls path={path} options={options} extra={chosenExtra} />
        </div>
      </div>
    </FieldDialog>
  );
}
