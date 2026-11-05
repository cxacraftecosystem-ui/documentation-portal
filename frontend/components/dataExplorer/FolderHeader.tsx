"use client";

import { ChevronRight, Download, FileSpreadsheet, FolderDown } from "lucide-react";
import Link from "next/link";
import { useEffect, useMemo, useState, type ReactNode } from "react";

import { Skeleton } from "@/components/dataExplorer/explorerUi";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { fetchSelectionStats, fetchStats, unsupported } from "@/lib/dataExplorer/api";
import { INCLUDE_KEYS, INCLUDE_LABELS, type Crumb, type FolderInfo, type IncludeKey, type Stats } from "@/lib/dataExplorer/contract";
import { formatBytes, type ManifestSummary } from "@/lib/dataExplorer/plan";
import type { JobSource } from "@/lib/dataExplorer/protocol";
import { selectionPayload, selectionSource, type Selection } from "@/lib/dataExplorer/selection";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type Sized = { files: number; bytes: number; unknown: number; byType: Partial<Record<IncludeKey, { files: number; bytes: number }>>; truncated: boolean };

export type Measure = (source: JobSource, include: IncludeKey[] | null) => Promise<{ manifest: { summary: ManifestSummary; truncated: boolean } } | { error: string }>;

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

function useSized(key: string, load: (() => Promise<Sized>) | null): { sized: Sized | null; error: string | null; loading: boolean } {
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
  if (!load) return { sized: null, error: null, loading: false };
  return answer && answer.key === key ? { sized: answer.sized, error: answer.error, loading: false } : { sized: null, error: null, loading: true };
}

export function useFolderSize(path: string, extra: QueryParams, downloadExtra: QueryParams, measure: Measure) {
  const extraKey = JSON.stringify(extra);
  const downloadKey = JSON.stringify(downloadExtra);
  const load = useMemo(
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
  );
  return useSized(`folder:${path}:${extraKey}`, load);
}

export function useSelectionSize(selection: Selection, extra: QueryParams, downloadExtra: QueryParams, measure: Measure) {
  const extraKey = JSON.stringify(extra);
  const downloadKey = JSON.stringify(downloadExtra);
  const keyText = [...selection.keys()].sort().join("|");
  const load = useMemo(() => {
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
  }, [selection, extraKey, downloadKey, measure]);
  return useSized(`selection:${keyText}:${extraKey}`, load);
}

const TYPE_TILE_KEYS: IncludeKey[] = ["images", "videos", "audios", "documents", "transcripts", "text", "other"];

export function recordTypeLabel(recordType: string | null | undefined): string | null {
  if (!recordType) return null;
  const spaced = recordType.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function Breadcrumbs({ crumbs, onOpen }: { crumbs: Crumb[]; onOpen: (path: string) => void }) {
  return (
    <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1 text-sm text-ink-500">
      {crumbs.map((crumb, index) => (
        <span key={crumb.path || "__root"} className="flex min-w-0 items-center gap-1">
          {index > 0 ? <ChevronRight className="h-3.5 w-3.5 shrink-0" aria-hidden /> : null}
          {index === crumbs.length - 1 ? (
            <span className="truncate font-semibold text-ink-900" aria-current="location">
              {crumb.name}
            </span>
          ) : (
            <button type="button" className="min-h-9 truncate font-medium text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => onOpen(crumb.path)}>
              {crumb.name}
            </button>
          )}
        </span>
      ))}
    </nav>
  );
}

export function FolderHeader({
  crumbs,
  folderName,
  recordType,
  size,
  include,
  onInclude,
  transcripts,
  onOpen,
  onReport,
  onDownload,
  onSaveFolder,
  extraActions,
  busy,
  loading
}: {
  crumbs: Crumb[];
  folderName: string;
  recordType: string | null;
  size: { sized: Sized | null; error: string | null; loading: boolean };
  include: IncludeKey[];
  onInclude: (next: IncludeKey[]) => void;
  transcripts: number | null;
  onOpen: (path: string) => void;
  onReport: () => void;
  onDownload: () => void;
  onSaveFolder: (() => void) | null;
  extraActions?: ReactNode;
  busy: boolean;
  loading: boolean;
}) {
  const sized = size.sized;
  const chosen = sized ? sizedFor(sized, include) : null;
  const typeLabel = recordTypeLabel(recordType);
  const tiles = sized ? TYPE_TILE_KEYS.filter((key) => (sized.byType[key]?.files ?? 0) > 0) : [];
  const toggle = (key: IncludeKey) => {
    const next = include.includes(key) ? include.filter((value) => value !== key) : [...include, key];
    onInclude(INCLUDE_KEYS.filter((value) => next.includes(value)));
  };
  const sizeText = chosen ? formatBytes(chosen.bytes) : null;
  return (
    <section aria-label="Folder" className="panel grid gap-4 p-4 sm:p-5" data-testid="download-folder">
      <Breadcrumbs crumbs={crumbs} onOpen={onOpen} />
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="grid min-w-0 gap-1.5">
          <h2 className="font-display text-xl font-extrabold text-ink-900 sm:text-2xl">{loading && !folderName ? <Skeleton className="h-7 w-48" /> : folderName}</h2>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-ink-700" data-testid="folder-size">
            {typeLabel ? <span className="rounded-full bg-purple-100 px-2.5 py-0.5 text-xs font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">{typeLabel}</span> : null}
            {sized ? (
              <span>
                {[
                  `${sized.files.toLocaleString("en-IN")} ${sized.files === 1 ? "file" : "files"}${sized.truncated ? "+" : ""}`,
                  formatBytes(sized.bytes),
                  transcripts !== null ? `${transcripts.toLocaleString("en-IN")} ${transcripts === 1 ? "transcript" : "transcripts"}` : null
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
            ) : size.error ? (
              <span>Size unknown</span>
            ) : (
              <span>Checking size…</span>
            )}
          </p>
        </div>
        <ActionGroup align="end" className="max-sm:w-full">
          {extraActions}
          <button type="button" className="field-button-secondary min-h-11" onClick={onReport}>
            <FileSpreadsheet className="h-4 w-4" aria-hidden />
            Report (.xlsx)
          </button>
          {onSaveFolder ? (
            <button type="button" className="field-button-secondary min-h-11" disabled={busy || include.length === 0} onClick={onSaveFolder}>
              <FolderDown className="h-4 w-4" aria-hidden />
              Save to a folder
            </button>
          ) : null}
          <button type="button" className="field-button min-h-11 shadow-cta" disabled={include.length === 0} onClick={onDownload}>
            <Download className="h-4 w-4" aria-hidden />
            <span className="sm:hidden">Download</span>
            <span className="max-sm:hidden">Download folder{sizeText ? ` · ${sizeText}` : ""}</span>
          </button>
        </ActionGroup>
      </div>
      {tiles.length ? (
        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4" role="group" aria-label="Include in the download">
          {tiles.map((key) => {
            const tally = sized?.byType[key];
            const on = include.includes(key);
            return (
              <button
                key={key}
                type="button"
                aria-pressed={on}
                onClick={() => toggle(key)}
                className={`grid min-h-16 gap-0.5 rounded-md border px-3 py-2.5 text-left transition pointer-coarse:min-h-18 ${
                  on ? "border-line-200 bg-surface-50 text-ink-900 hover:border-purple-300" : "border-dashed border-line-200 bg-card text-ink-500 hover:border-purple-300"
                }`}
              >
                <span className="text-xs text-ink-500">
                  {INCLUDE_LABELS[key]}
                  {on ? "" : " · left out"}
                </span>
                <span className={`text-lg font-bold tabular-nums ${on ? "" : "line-through decoration-1"}`}>
                  {(tally?.files ?? 0).toLocaleString("en-IN")} · {formatBytes(tally?.bytes ?? 0)}
                </span>
              </button>
            );
          })}
        </div>
      ) : size.loading ? (
        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4" aria-hidden>
          {[0, 1, 2, 3].map((tile) => (
            <Skeleton key={tile} className="h-16" />
          ))}
        </div>
      ) : null}
    </section>
  );
}

export function RecordDetails({ info, recordHref }: { info: FolderInfo; recordHref?: string | null }) {
  const fields = (info.fields ?? []).filter((field) => field.value != null && String(field.value).trim() !== "");
  const [all, setAll] = useState(false);
  if (fields.length === 0) return null;
  const shown = all ? fields : fields.slice(0, 9);
  return (
    <section aria-label="Record details" className="panel grid gap-3 p-4 sm:p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-display text-base font-bold text-ink-900">{info.title || "Record details"}</h3>
        {recordHref ? (
          <Link href={recordHref} className="text-sm font-semibold text-purple-700 hover:underline dark:text-purple-300">
            Open record
          </Link>
        ) : null}
      </div>
      <dl className="grid gap-x-5 gap-y-3 text-sm sm:grid-cols-2 lg:grid-cols-3">
        {shown.map((field, index) => (
          <div key={`${field.label}-${index}`} className="min-w-0">
            <dt className="text-xs text-ink-500">{field.label}</dt>
            <dd className="mt-0.5 max-h-40 overflow-y-auto whitespace-pre-line wrap-break-word text-ink-900">{field.value}</dd>
          </div>
        ))}
      </dl>
      {fields.length > 9 ? (
        <button type="button" className="justify-self-start text-sm font-semibold text-purple-700 hover:underline dark:text-purple-300" onClick={() => setAll((value) => !value)}>
          {all ? "Show fewer" : `Show all ${fields.length} fields`}
        </button>
      ) : null}
    </section>
  );
}
