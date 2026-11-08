"use client";

import { Database, FolderDown, MoreHorizontal } from "lucide-react";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import { Skeleton } from "@/components/dataExplorer/explorerUi";
import { fetchSelectionStats, fetchStats, unsupported } from "@/lib/dataExplorer/api";
import { INCLUDE_KEYS, INCLUDE_LABELS, type Crumb, type FolderInfo, type IncludeKey, type InfoField, type Stats } from "@/lib/dataExplorer/contract";
import { folderKindLabel } from "@/lib/dataExplorer/explorerView";
import { formatBytes, recordsOf, type ManifestSummary } from "@/lib/dataExplorer/plan";
import type { JobSource } from "@/lib/dataExplorer/protocol";
import { selectionPayload, selectionSource, type Selection } from "@/lib/dataExplorer/selection";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type Sized = { files: number; records: number; bytes: number; unknown: number; byType: Partial<Record<IncludeKey, { files: number; bytes: number }>>; truncated: boolean };

export type Measure = (source: JobSource, include: IncludeKey[] | null) => Promise<{ manifest: { summary: ManifestSummary; truncated: boolean } } | { error: string }>;

export function fromStats(stats: Stats): Sized {
  return { files: stats.files, records: recordsOf(stats), bytes: stats.bytes, unknown: stats.unknownSizeFiles, byType: stats.byType ?? {}, truncated: stats.truncated };
}

export function fromSummary(summary: ManifestSummary, truncated: boolean): Sized {
  const byType: Sized["byType"] = {};
  for (const key of INCLUDE_KEYS) byType[key] = { files: summary.byType[key].files, bytes: summary.byType[key].bytes };
  return { files: summary.files, records: recordsOf(summary), bytes: summary.bytes, unknown: summary.unknown, byType, truncated };
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

const TYPE_TILE_KEYS: IncludeKey[] = ["images", "videos", "audios", "documents", "text", "other"];

export function recordTypeLabel(recordType: string | null | undefined): string | null {
  return folderKindLabel(recordType);
}

export function Breadcrumbs({ crumbs, onOpen }: { crumbs: Crumb[]; onOpen: (path: string) => void }) {
  return (
    <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1.5 text-[13px] text-ink-500">
      {crumbs.map((crumb, index) => (
        <span key={crumb.path || "__root"} className="flex min-w-0 items-center gap-1.5">
          {index > 0 ? <span aria-hidden>/</span> : null}
          {index === crumbs.length - 1 ? (
            <span className="truncate font-semibold text-ink-900" aria-current="location">
              {crumb.name}
            </span>
          ) : (
            <button type="button" className="truncate text-purple-700 underline underline-offset-2 hover:text-purple-800 dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => onOpen(crumb.path)}>
              {crumb.name}
            </button>
          )}
        </span>
      ))}
    </nav>
  );
}

export function reviewTone(value: string): "approved" | "pending" | "rejected" | null {
  const word = value.trim().toLowerCase();
  if (word.startsWith("approved")) return "approved";
  if (word.startsWith("pending") || word.startsWith("in review") || word.startsWith("submitted")) return "pending";
  if (word.startsWith("rejected") || word.startsWith("changes")) return "rejected";
  return null;
}

const REVIEW_PILL: Record<"approved" | "pending" | "rejected", string> = {
  approved: "bg-success-100 text-success-600 dark:bg-[#123b25] dark:text-green-200",
  pending: "bg-amber-100 text-amber-800 dark:bg-amber-950/50 dark:text-amber-200",
  rejected: "bg-error-100 text-red-700 dark:bg-[#4a1717] dark:text-red-200"
};

export function ReviewPill({ value }: { value: string }) {
  const tone = reviewTone(value);
  if (!tone) return <>{value}</>;
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${REVIEW_PILL[tone]}`}>{value}</span>;
}

const COMPACT_FIELDS = ["Craft", "Location", "Review"];

function compactFields(info: FolderInfo | null): InfoField[] {
  const fields = (info?.fields ?? []).filter((field) => field.value != null && String(field.value).trim() !== "");
  const picked = COMPACT_FIELDS.map((label) => fields.find((field) => field.label === label)).filter((field): field is InfoField => Boolean(field));
  return picked.length ? picked : fields.slice(0, 3);
}

function MoreDownloads({ onSaveFolder, onWholeDataset, disabled }: { onSaveFolder: (() => void) | null; onWholeDataset: (() => void) | null; disabled: boolean }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("pointerdown", away);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("pointerdown", away);
      window.removeEventListener("keydown", key);
    };
  }, [open]);
  if (!onSaveFolder && !onWholeDataset) return null;
  const item = "flex min-h-11 w-full items-center gap-2 rounded-sm px-3 text-left text-sm text-ink-900 hover:bg-purple-50 disabled:opacity-50 dark:hover:bg-purple-950/40";
  return (
    <div className="relative" ref={box}>
      <button
        type="button"
        aria-label="More ways to download"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        className="grid h-11 w-11 place-items-center rounded-md border border-line-200 bg-card text-ink-700 transition hover:border-purple-300"
      >
        <MoreHorizontal className="h-4 w-4" aria-hidden />
      </button>
      {open ? (
        <div role="menu" aria-label="More ways to download" className="absolute right-0 top-12 z-30 grid w-56 gap-0.5 rounded-md border border-line-200 bg-card p-1 shadow-md">
          {onSaveFolder ? (
            <button
              type="button"
              role="menuitem"
              className={item}
              disabled={disabled}
              onClick={() => {
                setOpen(false);
                onSaveFolder();
              }}
            >
              <FolderDown className="h-4 w-4 text-ink-500" aria-hidden />
              Save to a folder
            </button>
          ) : null}
          {onWholeDataset ? (
            <button
              type="button"
              role="menuitem"
              className={item}
              onClick={() => {
                setOpen(false);
                onWholeDataset();
              }}
            >
              <Database className="h-4 w-4 text-ink-500" aria-hidden />
              Whole dataset
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
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
  onWholeDataset,
  info,
  parent,
  foldersButton,
  fresh = null,
  busy,
  loading,
  recordHref = null
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
  onWholeDataset?: (() => void) | null;
  info?: FolderInfo | null;
  parent?: Crumb | null;
  foldersButton?: ReactNode;
  fresh?: string | null;
  busy: boolean;
  loading: boolean;
  recordHref?: string | null;
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
  const nothingToDownload = Boolean(chosen && sized && !sized.truncated && chosen.files === 0 && chosen.unknown === 0);
  const facts = sized
    ? [
        `${sized.records.toLocaleString("en-IN")} ${sized.records === 1 ? "file" : "files"}${sized.truncated ? "+" : ""}`,
        formatBytes(sized.bytes),
        transcripts !== null && transcripts > 0 ? `${transcripts.toLocaleString("en-IN")} ${transcripts === 1 ? "transcript" : "transcripts"}` : null,
        typeof info?.counts?.tools === "number" && info.counts.tools > 0 ? `${info.counts.tools.toLocaleString("en-IN")} ${info.counts.tools === 1 ? "tool" : "tools"}` : null
      ].filter((part): part is string => Boolean(part))
    : [size.error ? "Size unknown" : "Checking size…"];
  const compact = compactFields(info ?? null);
  const detailsDrawn = (info?.fields ?? []).some((field) => field.value != null && String(field.value).trim() !== "");
  return (
    <>
      <div className="flex items-center justify-between gap-2 sm:hidden">
        {parent ? (
          <button type="button" className="inline-flex min-h-11 min-w-0 items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 text-[13px] text-ink-700" onClick={() => onOpen(parent.path)}>
            <span aria-hidden>‹</span>
            <span className="truncate">{parent.name}</span>
          </button>
        ) : (
          <span />
        )}
        {foldersButton}
      </div>
      <section aria-label="Folder" className="panel grid gap-4 p-5 max-lg:gap-3 max-lg:p-[18px] max-sm:gap-2.5 max-sm:p-3.5" data-testid="download-folder">
        <div className="max-sm:hidden">
          <Breadcrumbs crumbs={crumbs} onOpen={onOpen} />
        </div>
        <div className="flex flex-wrap items-start justify-between gap-4 max-lg:items-center max-lg:gap-3 max-sm:gap-2.5">
          <div className="grid min-w-0 gap-1.5 max-lg:gap-1 max-sm:w-full max-sm:gap-2.5">
            <h2 className="font-display text-2xl font-extrabold text-ink-900 max-lg:text-[22px] max-sm:text-xl">{folderName || (loading ? <Skeleton className="h-7 w-48" /> : "Folder")}</h2>
            <p className="flex flex-wrap items-center gap-2 text-[13px] text-ink-700" data-testid="folder-size">
              {typeLabel ? <span className="rounded-full bg-purple-100 px-2.5 py-1 font-semibold text-purple-800 max-lg:hidden dark:bg-purple-950/60 dark:text-purple-200">{typeLabel}</span> : null}
              {facts.map((fact, index) => (
                <span key={fact} className="contents">
                  {index > 0 ? (
                    <span aria-hidden className="max-lg:-mx-1">
                      ·
                    </span>
                  ) : null}
                  <span>{fact}</span>
                </span>
              ))}
              {fresh ? <span className="rounded-full bg-purple-100 px-2.5 py-1 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">{fresh}</span> : null}
              {recordHref ? (
                <Link href={recordHref} data-testid="folder-open-record" className={`inline-flex min-h-9 items-center font-semibold text-purple-700 underline underline-offset-2 hover:text-purple-800 pointer-coarse:min-h-11 dark:text-purple-300 ${detailsDrawn ? "lg:hidden" : ""}`}>
                  Open record
                </Link>
              ) : null}
            </p>
          </div>
          <div className="flex gap-2 max-sm:w-full">
            <button
              type="button"
              className="inline-flex min-h-11 items-center justify-center rounded-md border border-line-200 bg-card px-4 text-sm font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 max-lg:px-3.5 max-sm:flex-1 dark:hover:bg-purple-950/40"
              onClick={onReport}
            >
              Report (.xlsx)
            </button>
            <button
              type="button"
              className="inline-flex min-h-11 items-center justify-center rounded-md bg-purple-700 px-[18px] text-sm font-semibold text-white shadow-cta transition hover:bg-purple-800 disabled:bg-line-200 disabled:text-ink-500 disabled:shadow-none max-lg:px-4 max-lg:shadow-none max-sm:flex-1 dark:bg-purple-300 dark:text-purple-950 dark:hover:bg-purple-200"
              disabled={include.length === 0 || nothingToDownload}
              title={nothingToDownload ? "Nothing to download here yet." : undefined}
              data-testid="folder-download"
              onClick={onDownload}
            >
              <span className="sm:hidden">Download</span>
              <span className="max-sm:hidden lg:hidden">Download{sizeText ? ` · ${sizeText}` : ""}</span>
              <span className="max-lg:hidden">{recordType === "workshop" ? "Download workshop" : "Download folder"}{sizeText ? ` · ${sizeText}` : ""}</span>
            </button>
            <MoreDownloads onSaveFolder={onSaveFolder} onWholeDataset={onWholeDataset ?? null} disabled={busy || include.length === 0} />
          </div>
        </div>
        {compact.length ? (
          <dl aria-label="Record details" className="grid grid-cols-3 gap-x-4 gap-y-2.5 text-sm max-sm:hidden lg:hidden">
            {compact.map((field) => {
              const tone = reviewTone(field.value);
              return (
                <div key={field.label} className="min-w-0">
                  <dt className="text-xs text-ink-500">{field.label}</dt>
                  <dd className={`mt-0.5 truncate ${tone === "approved" ? "font-semibold text-success-600 dark:text-green-300" : tone === "pending" ? "font-semibold text-amber-700 dark:text-amber-300" : tone === "rejected" ? "font-semibold text-red-700 dark:text-red-300" : "text-ink-900"}`}>{field.value}</dd>
                </div>
              );
            })}
          </dl>
        ) : null}
        {tiles.length ? (
          <div className="grid grid-cols-4 gap-2.5 max-lg:hidden" role="group" aria-label="Include in the download">
            {tiles.map((key) => {
              const tally = sized?.byType[key];
              const on = include.includes(key);
              return (
                <button
                  key={key}
                  type="button"
                  aria-pressed={on}
                  onClick={() => toggle(key)}
                  className={`grid gap-0.5 rounded-md border p-3 text-left transition ${on ? "border-line-200 bg-surface-50 text-ink-900 hover:border-purple-300" : "border-dashed border-line-200 bg-card text-ink-500 hover:border-purple-300"}`}
                >
                  <span className="text-xs text-ink-500">
                    {TILE_LABELS[key]}
                    {on ? "" : " · left out"}
                  </span>
                  <span className={`text-lg leading-7 font-bold tabular-nums ${on ? "" : "line-through decoration-1"}`}>
                    {(tally?.files ?? 0).toLocaleString("en-IN")} · {formatBytes(tally?.bytes ?? 0)}
                  </span>
                </button>
              );
            })}
          </div>
        ) : size.loading ? (
          <div className="grid grid-cols-4 gap-2.5 max-lg:hidden" aria-hidden>
            {[0, 1, 2, 3].map((tile) => (
              <Skeleton key={tile} className="h-[66px]" />
            ))}
          </div>
        ) : null}
      </section>
    </>
  );
}

const TILE_LABELS: Record<IncludeKey, string> = { ...INCLUDE_LABELS, audios: "Audio" };

export function RecordDetails({ info, recordHref }: { info: FolderInfo; recordHref?: string | null }) {
  const fields = (info.fields ?? []).filter((field) => field.value != null && String(field.value).trim() !== "");
  const [all, setAll] = useState(false);
  if (fields.length === 0) return null;
  const shown = all ? fields : fields.slice(0, 9);
  return (
    <section aria-label="Record details" className="panel grid gap-3 p-5 max-lg:hidden">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-bold text-ink-900">Record details</h3>
        {recordHref ? (
          <Link href={recordHref} className="text-[13px] font-semibold text-purple-700 underline underline-offset-2 hover:text-purple-800 dark:text-purple-300">
            Open record
          </Link>
        ) : null}
      </div>
      <dl className="grid grid-cols-3 gap-x-5 gap-y-3 text-sm">
        {shown.map((field, index) => (
          <div key={`${field.label}-${index}`} className="min-w-0">
            <dt className="text-xs text-ink-500">{field.label}</dt>
            <dd className="mt-0.5 max-h-40 overflow-y-auto whitespace-pre-line wrap-break-word text-ink-900">{field.label === "Review" ? <ReviewPill value={field.value} /> : field.value}</dd>
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
