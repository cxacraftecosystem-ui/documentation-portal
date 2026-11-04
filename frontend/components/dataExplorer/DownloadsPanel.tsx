"use client";

import { Archive, CheckCircle2, Database, Download, FileSpreadsheet, FolderDown, ListChecks, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { CHIP_BUTTON, Note, Problem } from "@/components/dataExplorer/explorerUi";
import {
  pickerCancelled,
  pickTarget,
  saveCapabilities,
  type JobState,
  type MeasureOutcome,
  type SaveMode,
  type StartOptions
} from "@/components/dataExplorer/useDownloadJob";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { fetchSelectionStats, fetchStats, includeParam, reportFileUrl, transcriptBundleUrl, unsupported } from "@/lib/dataExplorer/api";
import { INCLUDE_KEYS, INCLUDE_LABELS, type IncludeKey, type Stats } from "@/lib/dataExplorer/contract";
import { formatBytes, limitSentence, overLimit, percentOf, summaryLine, typeHint, type ManifestSummary } from "@/lib/dataExplorer/plan";
import type { JobSource } from "@/lib/dataExplorer/protocol";
import { overSelectionLimit, selectionLabel, selectionPayload, selectionSource, type Selection } from "@/lib/dataExplorer/selection";
import type { QueryParams } from "@/lib/dataExplorer/wire";
import { exportFilename } from "@/lib/naming";

export type DownloadJob = {
  state: JobState;
  busy: boolean;
  start: (options: StartOptions) => boolean;
  measure: (source: JobSource, include: IncludeKey[] | null) => Promise<MeasureOutcome>;
  cancel: () => void;
  dismiss: () => void;
  fail: (label: string, message: string) => void;
};

type Sized = { files: number; bytes: number; unknown: number; byType: Partial<Record<IncludeKey, { files: number; bytes: number }>>; truncated: boolean };

export function fromStats(stats: Stats): Sized {
  return { files: stats.files, bytes: stats.bytes, unknown: stats.unknownSizeFiles, byType: stats.byType ?? {}, truncated: stats.truncated };
}

export function fromSummary(summary: ManifestSummary, truncated: boolean): Sized {
  const byType: Sized["byType"] = {};
  for (const key of INCLUDE_KEYS) byType[key] = { files: summary.byType[key].files, bytes: summary.byType[key].bytes };
  return { files: summary.files, bytes: summary.bytes, unknown: summary.unknown, byType, truncated };
}

export function sizedFor(sized: Sized, include: readonly IncludeKey[]): { files: number; bytes: number; unknown: number } {
  if (include.length === INCLUDE_KEYS.length) return { files: sized.files, bytes: sized.bytes, unknown: sized.unknown };
  let files = 0;
  let bytes = 0;
  for (const key of include) {
    files += sized.byType[key]?.files ?? 0;
    bytes += sized.byType[key]?.bytes ?? 0;
  }
  return { files, bytes, unknown: 0 };
}

export function zipName(subject: string): string {
  return exportFilename("Dataset", "Export", "zip", { subject, withTime: true });
}

function useSize(key: string, load: (() => Promise<Sized>) | null): { sized: Sized | null; error: string | null } {
  const [answer, setAnswer] = useState<{ key: string; sized: Sized | null; error: string | null } | null>(null);
  useEffect(() => {
    if (!load) return;
    let cancelled = false;
    load()
      .then((sized) => {
        if (!cancelled) setAnswer({ key, sized, error: null });
      })
      .catch((error) => {
        if (!cancelled) setAnswer({ key, sized: null, error: error instanceof Error && error.message ? error.message : "The size didn't load." });
      });
    return () => {
      cancelled = true;
    };
  }, [key, load]);
  return answer && answer.key === key ? { sized: answer.sized, error: answer.error } : { sized: null, error: null };
}

function Progress({ job, onDismiss, onCancel }: { job: JobState; onDismiss: () => void; onCancel: () => void }) {
  const progress = job.progress;
  const total = progress?.bytesTotal ?? job.manifest?.summary.bytes ?? 0;
  const done = progress?.bytesDone ?? 0;
  const percent = percentOf(done, total);
  const failed = job.outcome?.failed ?? progress?.failed ?? [];
  const running = job.phase === "running" || job.phase === "preparing";
  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3" data-testid="download-progress">
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-0 flex-1 font-display text-sm font-semibold text-ink-900">{job.label}</span>
        {running ? (
          <button type="button" className={CHIP_BUTTON} onClick={onCancel}>
            <X className="h-3.5 w-3.5" aria-hidden />
            Cancel
          </button>
        ) : (
          <button type="button" className={CHIP_BUTTON} onClick={onDismiss}>
            Dismiss
          </button>
        )}
      </div>
      {job.phase === "preparing" ? <p className="text-xs text-ink-500">Listing files…</p> : null}
      {job.phase === "running" || job.phase === "done" ? (
        <>
          <ProgressBar
            percent={job.phase === "done" ? 100 : percent}
            tone={job.phase === "done" && failed.length === 0 ? "done" : "brand"}
            className="h-2"
            role="progressbar"
            aria-label="Download progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={job.phase === "done" ? 100 : percent}
            data-bytes-done={done}
          />
          <p className="flex flex-wrap gap-x-3 text-xs text-ink-500">
            <span className="tabular-nums" data-testid="download-bytes">
              {formatBytes(done)} of {formatBytes(total)}
            </span>
            {progress ? (
              <span className="tabular-nums">
                {progress.filesDone.toLocaleString("en-IN")} of {progress.filesTotal.toLocaleString("en-IN")} files
              </span>
            ) : null}
            {running && job.timeLeft ? <span>{job.timeLeft}</span> : null}
            {job.folderName ? <span>Into {job.folderName}</span> : null}
          </p>
          {running && progress?.current ? <p className="truncate text-xs text-ink-700">{progress.current}</p> : null}
        </>
      ) : null}
      {job.phase === "done" ? (
        <Note tone={failed.length ? "warn" : "done"}>
          <CheckCircle2 className="mr-1 inline h-3.5 w-3.5" aria-hidden />
          {failed.length
            ? `Saved ${job.outcome!.filesSaved} of ${job.outcome!.filesTotal}. ${failed.length} failed.`
            : job.outcome!.filesTotal === 0
              ? "Nothing here to download."
              : `Saved ${job.outcome!.filesSaved.toLocaleString("en-IN")} files.`}
          {job.outcome?.skipped ? ` ${job.outcome.skipped} were already in the folder.` : ""}
          {job.truncated ? " Too many files: download subfolders for the rest." : ""}
        </Note>
      ) : null}
      {job.phase === "cancelled" ? <Note>Cancelled. {job.mode === "folder" ? "Save to the same folder to pick up where it stopped." : ""}</Note> : null}
      {job.phase === "error" ? <Problem>{job.error}{job.limit ? " Use Save to a folder, or a smaller selection." : ""}</Problem> : null}
      {failed.length ? (
        <details className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs text-amber-800" open={job.phase === "done"}>
          <summary className="cursor-pointer font-semibold">
            {failed.length} {failed.length === 1 ? "file" : "files"} not downloaded
          </summary>
          <ul className="mt-2 grid gap-1" data-testid="failed-files">
            {failed.map((entry) => (
              <li key={entry.path}>
                <span className="break-all font-medium">{entry.path}</span> · {entry.reason}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}

export function DownloadsPanel({
  path,
  folderName,
  extra,
  downloadExtra,
  selection,
  onClearSelection,
  job,
  datasetLine
}: {
  path: string;
  folderName: string;
  extra: QueryParams;
  downloadExtra: QueryParams;
  selection: Selection;
  onClearSelection: () => void;
  job: DownloadJob;
  datasetLine?: string | null;
}) {
  const [include, setInclude] = useState<IncludeKey[]>([...INCLUDE_KEYS]);
  const [datasetSize, setDatasetSize] = useState<{ sized: Sized | null; error: string | null; asking: boolean } | null>(null);
  const capabilities = useMemo(() => saveCapabilities(), []);
  const extraKey = JSON.stringify(extra);
  const downloadKey = JSON.stringify(downloadExtra);
  const wanted = include.length === INCLUDE_KEYS.length ? null : include;
  const measure = job.measure;

  const folderSize = useSize(
    `folder:${path}:${extraKey}`,
    useMemo(
      () => async () => {
        try {
          return fromStats(await fetchStats(path, null, JSON.parse(extraKey) as QueryParams));
        } catch (error) {
          if (!unsupported(error)) throw error;
          const measured = await measure({ kind: "manifests", requests: [{ method: "GET", endpoint: "/data/manifest", params: { path, ...(JSON.parse(downloadKey) as QueryParams) } }], extra: [] }, null);
          if ("error" in measured) throw new Error(measured.error);
          return fromSummary(measured.manifest.summary, measured.manifest.truncated);
        }
      },
      [path, extraKey, downloadKey, measure]
    )
  );

  const selectionKeyText = [...selection.keys()].sort().join("|");
  const selectionSize = useSize(
    `selection:${selectionKeyText}:${extraKey}`,
    useMemo(() => {
      if (selection.size === 0) return null;
      return async () => {
        const { paths, mediaIds } = selectionPayload(selection);
        try {
          return fromStats(await fetchSelectionStats(paths, mediaIds, null, JSON.parse(extraKey) as QueryParams));
        } catch (error) {
          if (!unsupported(error)) throw error;
          const measured = await measure(selectionSource(selection, null, JSON.parse(downloadKey) as QueryParams), null);
          if ("error" in measured) throw new Error(measured.error);
          return fromSummary(measured.manifest.summary, measured.manifest.truncated);
        }
      };
    }, [selection, extraKey, downloadKey, measure])
  );

  const includeOptions = INCLUDE_KEYS.map((key) => {
    const tally = folderSize.sized?.byType[key];
    const hint = tally ? typeHint({ files: tally.files, bytes: tally.bytes, unknown: 0 }) : null;
    return { value: key, label: hint ? `${INCLUDE_LABELS[key]} · ${hint}` : INCLUDE_LABELS[key] };
  });

  const begin = async (label: string, subject: string, source: JobSource, mode: SaveMode, size: number | null) => {
    const fileName = zipName(subject);
    try {
      const target = await pickTarget(mode, fileName, capabilities, size);
      job.start({ label, fileName, source, include: wanted, target, mode });
    } catch (error) {
      if (pickerCancelled(error)) return;
      job.fail(label, error instanceof Error && error.message ? error.message : "The download didn't start.");
    }
  };

  const folderSource = (): JobSource => ({
    kind: "manifests",
    requests: [{ method: "GET", endpoint: "/data/manifest", params: { path, include: includeParam(wanted), ...downloadExtra } }],
    extra: []
  });

  const folderLine = folderSize.sized ? summaryLine(sizedFor(folderSize.sized, include)) : folderSize.error ? "Size unknown" : "Checking size…";
  const folderBytes = folderSize.sized ? sizedFor(folderSize.sized, include).bytes : null;
  const tooBig = folderBytes !== null && overLimit(folderBytes, capabilities) && !capabilities.stream;
  const limit = capabilities.stream ? null : limitSentence(capabilities);
  const selectionLine = selectionSize.sized ? summaryLine(sizedFor(selectionSize.sized, include)) : selectionSize.error ? "Size unknown" : "Checking size…";

  const measureDataset = async () => {
    setDatasetSize({ sized: null, error: null, asking: true });
    const measured = await measure({ kind: "manifests", requests: [{ method: "GET", endpoint: "/export/dataset", params: {} }], extra: [] }, wanted);
    if ("error" in measured) setDatasetSize({ sized: null, error: measured.error, asking: false });
    else setDatasetSize({ sized: fromSummary(measured.manifest.summary, measured.manifest.truncated), error: null, asking: false });
  };

  const showProgress = job.state.phase !== "idle";

  return (
    <section className="panel grid gap-4 p-4" aria-labelledby="downloads-heading">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="downloads-heading" className="flex items-center gap-2 font-display text-base font-bold text-ink-900">
          <Download className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
          Downloads
        </h2>
        {limit ? <span className="text-xs text-ink-500">{limit}</span> : null}
      </div>

      <div className="grid gap-1">
        <span className="field-label" id="include-label">
          Include
        </span>
        <SearchableMultiSelect
          ariaLabel="Include in downloads"
          options={includeOptions}
          values={include}
          onChange={(keys) => setInclude(INCLUDE_KEYS.filter((key) => keys.includes(key)))}
          placeholder="Choose what to include"
          confirmOnSelect={false}
          bulk
        />
      </div>

      <div className="grid gap-2 rounded-md border border-line-200 p-3" data-testid="download-folder">
        <div className="flex flex-wrap items-baseline gap-x-2">
          <span className="font-display text-sm font-semibold text-ink-900">This folder</span>
          <span className="min-w-0 truncate text-xs text-ink-500">{folderName}</span>
          <span className="ml-auto text-xs tabular-nums text-ink-700" data-testid="folder-size">
            {folderLine}
          </span>
        </div>
        {tooBig ? <Note tone="warn">Too big for one zip in this browser. Use Save to a folder or pick less.</Note> : null}
        <ButtonRow size="sm" align="start" className="pointer-coarse:[&>*]:min-h-11">
          <button
            type="button"
            className="field-button"
            disabled={job.busy || include.length === 0 || tooBig}
            onClick={() => void begin(`Folder · ${folderName}`, folderName, folderSource(), "zip", folderBytes)}
          >
            <Archive className="h-4 w-4" aria-hidden />
            Download zip
          </button>
          {capabilities.saveFolder ? (
            <button
              type="button"
              className="field-button-secondary"
              disabled={job.busy || include.length === 0}
              onClick={() => void begin(`Folder · ${folderName}`, folderName, folderSource(), "folder", folderBytes)}
            >
              <FolderDown className="h-4 w-4" aria-hidden />
              Save to a folder
            </button>
          ) : null}
          <a href={transcriptBundleUrl(path, extra)} download className="field-button-secondary">
            <Archive className="h-4 w-4" aria-hidden />
            Transcripts, all formats
          </a>
          <a href={reportFileUrl(path, "xlsx", extra)} download className="field-button-secondary">
            <FileSpreadsheet className="h-4 w-4" aria-hidden />
            Report (.xlsx)
          </a>
        </ButtonRow>
      </div>

      {selection.size ? (
        <div className="grid gap-2 rounded-md border border-purple-300 bg-purple-50/40 p-3 dark:bg-purple-950/20" data-testid="download-selection">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="flex items-center gap-1.5 font-display text-sm font-semibold text-ink-900">
              <ListChecks className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
              Selected
            </span>
            <span className="text-xs text-ink-500">{selectionLabel(selection)}</span>
            <span className="ml-auto text-xs tabular-nums text-ink-700" data-testid="selection-size">
              {selectionLine}
            </span>
          </div>
          {overSelectionLimit(selection) ? <Note tone="warn">Too many picked. Only the first ones are included.</Note> : null}
          <ButtonRow size="sm" align="start" className="pointer-coarse:[&>*]:min-h-11">
            <button
              type="button"
              className="field-button"
              disabled={job.busy || include.length === 0}
              onClick={() => void begin(`Selected · ${selectionLabel(selection)}`, "Selection", selectionSource(selection, wanted, downloadExtra), "zip", selectionSize.sized?.bytes ?? null)}
            >
              <Archive className="h-4 w-4" aria-hidden />
              Download selected
            </button>
            {capabilities.saveFolder ? (
              <button
                type="button"
                className="field-button-secondary"
                disabled={job.busy || include.length === 0}
                onClick={() => void begin(`Selected · ${selectionLabel(selection)}`, "Selection", selectionSource(selection, wanted, downloadExtra), "folder", null)}
              >
                <FolderDown className="h-4 w-4" aria-hidden />
                Save to a folder
              </button>
            ) : null}
            <button type="button" className="field-button-secondary" onClick={onClearSelection}>
              <X className="h-4 w-4" aria-hidden />
              Clear
            </button>
          </ButtonRow>
        </div>
      ) : null}

      <div className="grid gap-2 rounded-md border border-line-200 p-3" data-testid="download-dataset">
        <div className="flex flex-wrap items-baseline gap-x-2">
          <span className="flex items-center gap-1.5 font-display text-sm font-semibold text-ink-900">
            <Database className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
            Whole dataset
          </span>
          {datasetLine ? <span className="text-xs text-ink-500">{datasetLine}</span> : null}
          <span className="ml-auto text-xs tabular-nums text-ink-700" data-testid="dataset-size">
            {datasetSize?.sized ? summaryLine(datasetSize.sized) : datasetSize?.error ? "Size unknown" : datasetSize?.asking ? "Checking size…" : ""}
          </span>
        </div>
        <ButtonRow size="sm" align="start" className="pointer-coarse:[&>*]:min-h-11">
          <button
            type="button"
            className="field-button"
            disabled={job.busy || include.length === 0}
            onClick={() =>
              void begin("Whole dataset", "All Records", { kind: "manifests", requests: [{ method: "GET", endpoint: "/export/dataset", params: {} }], extra: [] }, "zip", datasetSize?.sized?.bytes ?? null)
            }
          >
            <Archive className="h-4 w-4" aria-hidden />
            Download dataset
          </button>
          {capabilities.saveFolder ? (
            <button
              type="button"
              className="field-button-secondary"
              disabled={job.busy || include.length === 0}
              onClick={() =>
                void begin("Whole dataset", "All Records", { kind: "manifests", requests: [{ method: "GET", endpoint: "/export/dataset", params: {} }], extra: [] }, "folder", null)
              }
            >
              <FolderDown className="h-4 w-4" aria-hidden />
              Save to a folder
            </button>
          ) : null}
          {!datasetSize?.sized ? (
            <button type="button" className="field-button-secondary" disabled={datasetSize?.asking} onClick={() => void measureDataset()}>
              Check size
            </button>
          ) : null}
        </ButtonRow>
      </div>

      <div className="sr-only" role="status" aria-live="polite" data-testid="download-announcer">
        {job.state.announcement}
      </div>
      {showProgress ? <Progress job={job.state} onCancel={job.cancel} onDismiss={job.dismiss} /> : null}
    </section>
  );
}
