"use client";

import { CheckCircle2, FileSpreadsheet } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { Skeleton } from "@/components/dataExplorer/explorerUi";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Toggle } from "@/components/ui/Toggle";
import { fetchReport, fetchReportOptions, saveReportOptions } from "@/lib/dataExplorer/api";
import { DEFAULT_REPORT_OPTIONS, type ReportOptions, type ReportResponse } from "@/lib/dataExplorer/contract";
import { exportFilename } from "@/lib/naming";
import { estimateLine } from "@/lib/dataExplorer/explorerView";
import type { QueryParams } from "@/lib/dataExplorer/wire";

import { sheetCount } from "@/components/dataExplorer/DataTables";

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

export function ReportOptionsDialog({
  open,
  onClose,
  path,
  folderName,
  subtitle,
  extra,
  query,
  onMake
}: {
  open: boolean;
  onClose: () => void;
  path: string;
  folderName: string;
  subtitle: string;
  extra: QueryParams;
  query: string;
  onMake: (options: ReportOptions, name: string) => void;
}) {
  const [report, setReport] = useState<{ path: string; report: ReportResponse | null; error: boolean } | null>(null);
  const [options, setOptions] = useState<ReportOptions>(DEFAULT_REPORT_OPTIONS);
  const [name, setName] = useState("");
  const [saved, setSaved] = useState<"idle" | "saving" | "saved" | "failed">("idle");
  const extraKey = JSON.stringify(extra);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setName(defaultReportName(folderName));
    setSaved("idle");
    fetchReportOptions()
      .then((remembered) => {
        if (!cancelled && remembered) setOptions({ ...DEFAULT_REPORT_OPTIONS, ...remembered });
      })
      .catch(() => undefined);
    fetchReport({ path, extra: JSON.parse(extraKey) as QueryParams })
      .then((answer) => {
        if (!cancelled) setReport({ path, report: answer, error: false });
      })
      .catch(() => {
        if (!cancelled) setReport({ path, report: null, error: true });
      });
    return () => {
      cancelled = true;
    };
  }, [open, path, extraKey, folderName]);

  const sheets = useMemo(() => (report?.path === path ? (report.report?.sheets ?? []) : []), [report, path]);
  const chosen = options.sheets ? sheets.filter((sheet) => options.sheets!.includes(sheet.name)) : sheets;
  const toggleSheet = (sheetName: string) => {
    const current = options.sheets ?? sheets.map((sheet) => sheet.name);
    const next = current.includes(sheetName) ? current.filter((value) => value !== sheetName) : [...current, sheetName];
    setOptions({ ...options, sheets: next.length === sheets.length ? null : next });
  };
  const loading = open && (!report || report.path !== path);
  const remember = async () => {
    setSaved("saving");
    try {
      await saveReportOptions(options);
      setSaved("saved");
    } catch {
      setSaved("failed");
    }
  };

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title="Report (.xlsx)"
      description={subtitle}
      icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={saved === "saving"} onClick={() => void remember()}>
            {saved === "saved" ? "Remembered" : "Remember these choices"}
          </button>
          <button type="button" className="field-button-secondary" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="field-button"
            disabled={chosen.length === 0 && sheets.length > 0}
            onClick={() => {
              onMake(options, name.endsWith(".xlsx") ? name : `${name}.xlsx`);
              onClose();
            }}
          >
            Make report
          </button>
        </>
      }
    >
      <div className="grid gap-5">
        <div className="grid gap-1.5">
          <span className="field-label">Sheets</span>
          {loading ? (
            <div className="grid gap-1.5">
              {[0, 1, 2, 3].map((row) => (
                <Skeleton key={row} className="h-9" />
              ))}
            </div>
          ) : sheets.length ? (
            <ul className="grid gap-1 sm:grid-cols-2" aria-label="Sheets">
              {sheets.map((sheet) => {
                const on = !options.sheets || options.sheets.includes(sheet.name);
                return (
                  <li key={sheet.name}>
                    <label className={`flex min-h-11 cursor-pointer items-center gap-2.5 rounded-sm px-2.5 text-sm ${on ? "bg-purple-50 text-ink-900 dark:bg-purple-950/30" : "text-ink-500 hover:bg-surface-50"}`}>
                      <input type="checkbox" checked={on} onChange={() => toggleSheet(sheet.name)} className="h-4.5 w-4.5 accent-purple-700" />
                      <span className="min-w-0 flex-1 truncate">{sheet.name}</span>
                      <span className="text-xs tabular-nums text-ink-500">{sheetCount(sheet)}</span>
                    </label>
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className="text-sm text-ink-500">{report?.error ? "The sheet list didn't load. The report still has every sheet." : "Every sheet is included."}</p>
          )}
        </div>
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
        <Toggle label="Links to files open in the portal" checked={options.links} onChange={(links) => setOptions({ ...options, links })} />
        <div className="grid gap-1.5">
          <label htmlFor="report-file-name" className="field-label">
            File name
          </label>
          <input id="report-file-name" className="field-input" value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        {estimateLine(report?.report ?? null, chosen.length) ? (
          <p className="flex items-center gap-1.5 text-sm text-success-600">
            <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden />
            {estimateLine(report?.report ?? null, chosen.length)}
          </p>
        ) : null}
        {saved === "failed" ? <p className="text-sm text-error-600">Your choices weren&apos;t remembered. They still apply to this report.</p> : null}
      </div>
    </FieldDialog>
  );
}
