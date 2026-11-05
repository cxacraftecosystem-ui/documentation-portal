"use client";

import { Archive, Download, Eye, FileText, LayoutGrid, List, MoreVertical, Play } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type MouseEvent, type PointerEvent, type ReactNode } from "react";

import { FileTypeIcon, FolderSkeleton, LoadFailed, Note, Problem, RecordIcon, Spinner } from "@/components/dataExplorer/explorerUi";
import { canPreview } from "@/components/dataExplorer/PreviewDialog";
import { TranscriptMarkdown } from "@/components/transcript/TranscriptMarkdown";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { apiFetch, buildQuery } from "@/lib/api";
import { fetchTranscriptText, mediaDownloadUrl, mediaThumbnailUrl, transcriptZipUrl } from "@/lib/dataExplorer/api";
import { sizeOf, type Manifest, type TreeEntry, type TreeResponse } from "@/lib/dataExplorer/contract";
import { capturedLabel, filterOf, pageOf, TYPE_FILTERS, type TypeFilter } from "@/lib/dataExplorer/explorerView";
import { ROW_TONE_CLASS, toneOf, type DownloadRequest, type DownloadStatus } from "@/lib/dataExplorer/downloads";
import { formatBytes } from "@/lib/dataExplorer/plan";
import { selectionKey, type Selection } from "@/lib/dataExplorer/selection";
import { apiUrl, type QueryParams } from "@/lib/dataExplorer/wire";

const INTERACTIVE = "a, button, input, select, textarea, label, summary, video, audio, [role='button'], [data-row-ignore]";

const folderTranscripts = new Map<string, Promise<Manifest>>();

function transcriptFallback(folder: string, extra: QueryParams, entry: TreeEntry): () => Promise<string | null> {
  return async () => {
    const key = JSON.stringify([folder, extra]);
    let asking = folderTranscripts.get(key);
    if (!asking) {
      asking = apiFetch<Manifest>(`/data/manifest${buildQuery({ path: folder, include: "transcripts", ...extra })}`);
      folderTranscripts.set(key, asking);
      asking.catch(() => folderTranscripts.delete(key));
    }
    const manifest = await asking;
    const byId = entry.mediaId ? manifest.files.find((file) => file.mediaId === entry.mediaId && file.content != null) : undefined;
    if (byId?.content != null) return byId.content;
    const stem = entry.name.replace(/\.[^.]+$/, "");
    return manifest.files.find((file) => file.content != null && file.path.includes(stem))?.content ?? null;
  };
}

export function hasTranscript(entry: TreeEntry): boolean {
  return Boolean(entry.mediaId && (entry.transcriptAvailable || entry.transcriptPath));
}

export function kindLabel(entry: TreeEntry): string {
  const kind = entry.mediaType ?? (entry.content != null ? "Text" : "File");
  if (kind === "PDF") return "Document";
  return kind.charAt(0) + kind.slice(1).toLowerCase();
}

export function entryMeta(entry: TreeEntry): string {
  const size = sizeOf(entry.sizeBytes);
  return [kindLabel(entry), size !== null ? formatBytes(size) : null, hasTranscript(entry) ? "transcript" : null].filter(Boolean).join(" · ");
}

function Thumb({ entry, size = "md" }: { entry: TreeEntry; size?: "md" | "lg" }) {
  const [broken, setBroken] = useState(false);
  const box = size === "lg" ? "h-28 w-full" : "h-10 w-12";
  const source = entry.mediaType === "IMAGE" && entry.mediaId ? (entry.thumbnailPath ? apiUrl(entry.thumbnailPath, { size: 256 }) : mediaThumbnailUrl(entry.mediaId)) : null;
  if (source && !broken) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={source} alt="" loading="lazy" decoding="async" onError={() => setBroken(true)} className={`${box} shrink-0 rounded-sm border border-line-200 bg-surface-50 object-cover`} />
    );
  }
  const tag = entry.mediaType === "VIDEO" ? "VID" : entry.mediaType === "AUDIO" ? "AUD" : entry.mediaType === "PDF" ? "PDF" : entry.mediaType === "DOCUMENT" ? "DOC" : null;
  return (
    <span className={`grid ${box} shrink-0 place-items-center rounded-sm bg-purple-100 text-[11px] font-bold text-purple-800 dark:bg-purple-950/50 dark:text-purple-200`}>
      {tag ?? <FileTypeIcon entry={entry} className="h-5 w-5" />}
    </span>
  );
}

function TranscriptBlock({ entry, folder, extra }: { entry: TreeEntry; folder: string; extra: QueryParams }) {
  const [answer, setAnswer] = useState<{ attempt: number; text: string | null; error: string | null } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const mediaId = entry.mediaId ?? "";
  const extraKey = JSON.stringify(extra);
  useEffect(() => {
    let cancelled = false;
    fetchTranscriptText(mediaId, transcriptFallback(folder, JSON.parse(extraKey) as QueryParams, entry))
      .then((found) => {
        if (!cancelled) setAnswer({ attempt, text: found.content, error: null });
      })
      .catch((error) => {
        if (!cancelled) setAnswer({ attempt, text: null, error: error instanceof Error && error.message ? error.message : "The transcript could not be loaded." });
      });
    return () => {
      cancelled = true;
    };
  }, [mediaId, folder, extraKey, entry, attempt]);
  const state = answer && answer.attempt === attempt ? answer : null;
  if (!state) return <Spinner label="Loading transcript…" />;
  if (state.error) return <Problem onRetry={() => setAttempt((value) => value + 1)}>{state.error}</Problem>;
  if (!state.text) return <p className="text-xs text-ink-500">No transcript for this file.</p>;
  return (
    <div className="max-h-80 overflow-y-auto rounded-md border border-line-200 bg-surface-50 p-4" tabIndex={0} role="region" aria-label={`Transcript of ${entry.name}`}>
      <TranscriptMarkdown text={state.text} />
    </div>
  );
}

const ACTION = "inline-flex min-h-9 items-center justify-center gap-1.5 rounded-sm border border-line-200 bg-card px-3 text-sm font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 pointer-coarse:min-h-11";
const ICON_ACTION = "grid h-9 w-9 shrink-0 place-items-center rounded-sm border border-line-200 bg-card text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 pointer-coarse:h-11 pointer-coarse:w-11";

function statusWord(status: DownloadStatus | undefined): string | null {
  if (status === "saved") return "Saved";
  if (status === "failed") return "Failed";
  if (status === "running") return "Downloading";
  if (status === "waiting") return "Waiting";
  return null;
}

export function FolderContents({
  path,
  tree,
  loading,
  error,
  extra,
  selection,
  fileHref,
  emptyBody,
  emptyAction,
  mediaStatus,
  onOpen,
  onToggle,
  onSelectAll,
  onPreview,
  onDownload,
  onMore,
  onRetry
}: {
  path: string;
  tree: TreeResponse | undefined;
  loading: boolean;
  error: string | null;
  extra: QueryParams;
  selection: Selection;
  fileHref: (mediaId: string) => string | null;
  emptyBody: string;
  emptyAction?: ReactNode;
  mediaStatus: ReadonlyMap<string, DownloadStatus>;
  onOpen: (path: string) => void;
  onToggle: (entry: TreeEntry) => void;
  onSelectAll: (entries: TreeEntry[], on: boolean) => void;
  onPreview: (entry: TreeEntry) => void;
  onDownload: (request: DownloadRequest) => void;
  onMore: () => void;
  onRetry: () => void;
}) {
  const router = useRouter();
  const [openText, setOpenText] = useState<ReadonlySet<string>>(new Set());
  const [openTranscripts, setOpenTranscripts] = useState<ReadonlySet<string>>(new Set());
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [filter, setFilter] = useState<TypeFilter>("all");
  const [page, setPage] = useState(1);
  const [layout, setLayout] = useState<"list" | "grid">("list");
  const press = useRef<{ timer: number; path: string } | null>(null);
  const pressed = useRef<string | null>(null);
  const lastPointer = useRef<string>("mouse");
  const startPress = (event: PointerEvent<HTMLElement>, entry: TreeEntry) => {
    lastPointer.current = event.pointerType;
    if (event.pointerType !== "touch") return;
    if ((event.target as HTMLElement).closest("button, a, input, label")) return;
    const timer = window.setTimeout(() => {
      pressed.current = entry.path;
      onToggle(entry);
      if (typeof navigator !== "undefined" && "vibrate" in navigator) navigator.vibrate?.(15);
    }, 450);
    press.current = { timer, path: entry.path };
  };
  const endPress = () => {
    if (press.current) window.clearTimeout(press.current.timer);
    press.current = null;
  };
  const [shownFor, setShownFor] = useState(path);
  if (shownFor !== path) {
    setShownFor(path);
    setOpenText(new Set());
    setOpenTranscripts(new Set());
    setFilter("all");
    setPage(1);
    setMenuFor(null);
  }
  const entries = useMemo(() => tree?.entries ?? [], [tree]);
  const folders = entries.filter((entry) => entry.kind === "folder");
  const files = useMemo(() => entries.filter((entry) => entry.kind === "file"), [entries]);
  const counts = useMemo(() => {
    const out: Record<TypeFilter, number> = { all: files.length, images: 0, videos: 0, audios: 0, documents: 0, other: 0 };
    for (const file of files) out[filterOf(file)] += 1;
    return out;
  }, [files]);
  const filtered = filter === "all" ? files : files.filter((file) => filterOf(file) === filter);
  const paged = pageOf(filtered, page);
  const allSelected = entries.length > 0 && entries.every((entry) => selection.has(selectionKey(entry)));

  const flip = (set: ReadonlySet<string>, key: string) => {
    const next = new Set(set);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  };

  const openRow = (event: MouseEvent<HTMLElement>, href: string | null, entry?: TreeEntry) => {
    if (entry && pressed.current === entry.path) {
      pressed.current = null;
      return;
    }
    if (entry && lastPointer.current === "touch" && selection.size > 0 && !(event.target as HTMLElement).closest(INTERACTIVE)) {
      onToggle(entry);
      return;
    }
    if (!href) return;
    if ((event.target as HTMLElement).closest(INTERACTIVE)) return;
    if (window.getSelection()?.toString()) return;
    if (event.metaKey || event.ctrlKey) window.open(href, "_blank", "noopener");
    else router.push(href);
  };

  const download = (file: TreeEntry) => {
    if (!file.mediaId) return;
    onDownload({
      kind: "file",
      label: file.name,
      url: file.downloadPath ? apiUrl(file.downloadPath) : mediaDownloadUrl(file.mediaId),
      fileName: file.originalFilename ?? file.name,
      mediaId: file.mediaId,
      bytes: sizeOf(file.sizeBytes)
    });
  };

  const actions = (file: TreeEntry): ReactNode[] => {
    const out: ReactNode[] = [];
    const transcriptOpen = openTranscripts.has(file.path);
    if (canPreview(file))
      out.push(
        <button key="preview" type="button" className={ACTION} onClick={() => onPreview(file)}>
          {file.mediaType === "AUDIO" ? <Play className="h-4 w-4" aria-hidden /> : <Eye className="h-4 w-4" aria-hidden />}
          {file.mediaType === "AUDIO" ? "Play" : "Preview"}
        </button>
      );
    if (file.content != null)
      out.push(
        <button key="text" type="button" className={ACTION} aria-expanded={openText.has(file.path)} onClick={() => setOpenText(flip(openText, file.path))}>
          <FileText className="h-4 w-4" aria-hidden />
          {openText.has(file.path) ? "Hide" : "View"}
        </button>
      );
    if (hasTranscript(file) && file.content == null)
      out.push(
        <button key="transcript" type="button" className={ACTION} aria-expanded={transcriptOpen} onClick={() => setOpenTranscripts(flip(openTranscripts, file.path))}>
          <FileText className="h-4 w-4" aria-hidden />
          {transcriptOpen ? "Hide transcript" : "Transcript"}
        </button>
      );
    if (hasTranscript(file))
      out.push(
        <button key="zip" type="button" className={ACTION} onClick={() => onDownload({ kind: "transcript", label: `Transcript · ${file.name}`, url: transcriptZipUrl(file.mediaId!) })}>
          <Archive className="h-4 w-4" aria-hidden />
          All formats
          <span className="sr-only"> (.zip), {file.name}</span>
        </button>
      );
    if (file.mediaId && file.mediaType && file.content == null)
      out.push(
        <button key="download" type="button" className={ICON_ACTION} aria-label={`Download ${file.name}`} onClick={() => download(file)}>
          <Download className="h-4 w-4" aria-hidden />
        </button>
      );
    return out;
  };

  const extras = (file: TreeEntry) => (
    <>
      {file.transcriptFiles?.length ? (
        <p data-row-ignore="" className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs sm:pl-16" data-transcript-files>
          {file.transcriptFiles.map((link) => (
            <a key={link.url} href={link.url} download={link.name} className="inline-flex min-h-9 items-center font-semibold text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11">
              {link.name}
            </a>
          ))}
        </p>
      ) : null}
      {openText.has(file.path) && file.content != null ? (
        <pre data-row-ignore="" tabIndex={0} className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap rounded-md border border-line-200 bg-surface-50 p-4 font-sans text-sm leading-6 text-ink-700">
          {file.content}
        </pre>
      ) : null}
      {openTranscripts.has(file.path) ? (
        <div data-row-ignore="" className="mt-2">
          <TranscriptBlock entry={file} folder={path} extra={extra} />
        </div>
      ) : null}
    </>
  );

  if (error && !tree) {
    return (
      <div className="p-4">
        <LoadFailed title="This folder didn't load" onRetry={onRetry}>
          {error}
        </LoadFailed>
      </div>
    );
  }
  if (!tree) return loading ? <FolderSkeleton /> : <p className="p-4 text-sm text-ink-500">Choose a folder.</p>;
  if (entries.length === 0) {
    return (
      <div className="grid justify-items-center gap-2 px-4 py-10 text-center">
        <span className="grid h-12 w-12 place-items-center rounded-full bg-purple-100 text-purple-700 dark:bg-purple-950/50 dark:text-purple-200" aria-hidden>
          <Archive className="h-5 w-5" />
        </span>
        <h3 className="font-display text-base font-bold text-ink-900">Nothing recorded here yet</h3>
        <p className="max-w-sm text-sm text-ink-500">{emptyBody}</p>
        {emptyAction ? <ActionGroup>{emptyAction}</ActionGroup> : null}
      </div>
    );
  }

  return (
    <div aria-busy={loading || undefined} data-testid="folder-contents">
      {error ? (
        <div className="px-4 pt-3">
          <Problem onRetry={onRetry}>{error}</Problem>
        </div>
      ) : null}
      {tree.truncated && !tree.nextCursor ? (
        <div className="px-4 pt-3">
          <Note tone="warn">Showing part of this folder. Open subfolders for the rest.</Note>
        </div>
      ) : null}
      {folders.length ? (
        <ul className="grid gap-2 border-b border-line-200 p-3 sm:grid-cols-2 xl:grid-cols-3" aria-label="Folders">
          {folders.map((folder) => (
            <li key={folder.path} className={`flex items-center gap-1 rounded-md border border-line-200 pr-2 transition hover:border-purple-300 ${selection.has(selectionKey(folder)) ? "bg-purple-50 dark:bg-purple-950/30" : "bg-card"}`}>
              <label className="grid h-11 w-11 shrink-0 cursor-pointer place-items-center" data-row-ignore="">
                <input type="checkbox" checked={selection.has(selectionKey(folder))} onChange={() => onToggle(folder)} aria-label={`Select ${folder.name}`} className="h-4.5 w-4.5 accent-purple-700" />
              </label>
              <button type="button" className="flex min-h-12 min-w-0 flex-1 items-center gap-3 py-2 text-left" onClick={() => onOpen(folder.path)}>
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-sm bg-purple-800 text-purple-50">
                  <RecordIcon recordType={folder.recordType} className="h-4 w-4" />
                </span>
                <span className="min-w-0">
                  <span className="block truncate font-display text-sm font-semibold text-ink-900">{folder.name}</span>
                  {folder.recordType ? <span className="block text-xs capitalize text-ink-500">{folder.recordType.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase()}</span> : null}
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {files.length ? (
        <>
          <div className="flex flex-wrap items-center gap-2 border-b border-line-200 px-4 py-3">
            <span className="text-xs text-ink-500">Show</span>
            <div role="group" aria-label="Show file types" className="flex flex-wrap gap-1.5">
              {TYPE_FILTERS.filter((option) => option.value === "all" || counts[option.value] > 0).map((option) => (
                <button
                  key={option.value}
                  type="button"
                  aria-pressed={filter === option.value}
                  onClick={() => {
                    setFilter(option.value);
                    setPage(1);
                  }}
                  className={`min-h-8 rounded-full px-3 text-xs font-semibold transition pointer-coarse:min-h-11 ${
                    filter === option.value ? "bg-purple-700 text-white" : "border border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                  }`}
                >
                  {option.label} {counts[option.value]}
                </button>
              ))}
            </div>
            <span className="flex-1" />
            <label className="inline-flex min-h-9 cursor-pointer items-center gap-2 rounded-sm px-2 text-xs text-ink-700 hover:bg-purple-50 pointer-coarse:min-h-11" data-row-ignore="">
              <input type="checkbox" checked={allSelected} onChange={() => onSelectAll(entries, !allSelected)} className="h-4.5 w-4.5 accent-purple-700" />
              Select all
            </label>
            <div className="flex gap-1 max-sm:hidden" role="group" aria-label="Layout">
              <button type="button" aria-pressed={layout === "list"} aria-label="List view" onClick={() => setLayout("list")} className={`grid h-9 w-9 place-items-center rounded-sm border ${layout === "list" ? "border-purple-300 bg-purple-100 text-purple-800" : "border-line-200 bg-card text-ink-700"}`}>
                <List className="h-4 w-4" aria-hidden />
              </button>
              <button type="button" aria-pressed={layout === "grid"} aria-label="Grid view" onClick={() => setLayout("grid")} className={`grid h-9 w-9 place-items-center rounded-sm border ${layout === "grid" ? "border-purple-300 bg-purple-100 text-purple-800" : "border-line-200 bg-card text-ink-700"}`}>
                <LayoutGrid className="h-4 w-4" aria-hidden />
              </button>
            </div>
          </div>
          {layout === "grid" ? (
            <ul className="grid grid-cols-2 gap-3 p-3 md:grid-cols-3 xl:grid-cols-4" aria-label="Files">
              {paged.items.map((file) => {
                const status = file.mediaId ? mediaStatus.get(file.mediaId) : undefined;
                const tone = status ? toneOf(status) : "neutral";
                const checked = selection.has(selectionKey(file));
                return (
                  <li key={file.path} className={`grid gap-2 rounded-md border border-line-200 p-2 ${tone !== "neutral" ? ROW_TONE_CLASS[tone] : checked ? "bg-purple-50 dark:bg-purple-950/30" : "bg-card"}`} data-status={status}>
                    <button type="button" className="block" onClick={() => (canPreview(file) ? onPreview(file) : undefined)} aria-label={canPreview(file) ? `Preview ${file.name}` : file.name}>
                      <Thumb entry={file} size="lg" />
                    </button>
                    <div className="flex items-start gap-2">
                      <input type="checkbox" checked={checked} onChange={() => onToggle(file)} aria-label={`Select ${file.name}`} className="mt-0.5 h-4.5 w-4.5 shrink-0 accent-purple-700" />
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-ink-900">{file.name}</p>
                        <p className="text-xs text-ink-500">{[entryMeta(file), statusWord(status)].filter(Boolean).join(" · ")}</p>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : (
            <>
            <div className="flex items-center gap-3 border-b border-line-200 bg-surface-50 px-4 py-2 text-xs font-semibold uppercase tracking-wide text-ink-500 max-md:hidden" aria-hidden>
              <span className="w-11 shrink-0" />
              <span className="w-12 shrink-0" />
              <span className="min-w-0 flex-1">Name</span>
              <span className="w-20 shrink-0">Type</span>
              <span className="w-20 shrink-0">Size</span>
              <span className="w-28 shrink-0 max-lg:hidden">Captured</span>
              <span className="w-40 shrink-0 text-right">Actions</span>
            </div>
            <ul className="divide-y divide-line-200" aria-label="Files">
              {paged.items.map((file) => {
                const key = selectionKey(file);
                const href = file.mediaId ? fileHref(file.mediaId) : null;
                const status = file.mediaId ? mediaStatus.get(file.mediaId) : undefined;
                const tone = status ? toneOf(status) : "neutral";
                const checked = selection.has(key);
                const rowActions = actions(file);
                const menuOpen = menuFor === file.path;
                return (
                  <li
                    key={file.path}
                    className={`px-2 py-2 sm:px-4 ${tone !== "neutral" ? ROW_TONE_CLASS[tone] : checked ? "bg-purple-50 dark:bg-purple-950/30" : href ? "cursor-pointer hover:bg-surface-50" : ""}`}
                    onClick={(event) => openRow(event, href, file)}
                    onPointerDown={(event) => startPress(event, file)}
                    onPointerUp={endPress}
                    onPointerCancel={endPress}
                    onPointerMove={(event) => {
                      if (Math.abs(event.movementX) + Math.abs(event.movementY) > 6) endPress();
                    }}
                    onContextMenu={(event) => {
                      if (press.current || pressed.current) event.preventDefault();
                    }}
                    data-file-row=""
                    data-status={status}
                  >
                    <div className="flex items-center gap-2 sm:gap-3">
                      <label className="grid h-11 w-11 shrink-0 cursor-pointer place-items-center" data-row-ignore="">
                        <input type="checkbox" checked={checked} onChange={() => onToggle(file)} aria-label={`Select ${file.name}`} className="h-4.5 w-4.5 accent-purple-700" />
                      </label>
                      <Thumb entry={file} />
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-semibold text-ink-900">
                          {href ? (
                            <Link href={href} className="hover:text-purple-700 hover:underline">
                              {file.name}
                            </Link>
                          ) : (
                            file.name
                          )}
                        </div>
                        <div className="text-xs text-ink-500">{[entryMeta(file), statusWord(status)].filter(Boolean).join(" · ")}</div>
                      </div>
                      <span className="w-20 shrink-0 text-sm text-ink-700 max-md:hidden">{kindLabel(file)}</span>
                      <span className="w-20 shrink-0 text-sm tabular-nums text-ink-700 max-md:hidden">{sizeOf(file.sizeBytes) !== null ? formatBytes(sizeOf(file.sizeBytes)!) : "-"}</span>
                      <span className="w-28 shrink-0 text-sm tabular-nums text-ink-700 max-lg:hidden" data-captured="">{capturedLabel(file.capturedAt)}</span>
                      <div className="flex shrink-0 items-center gap-1.5 max-sm:hidden" data-row-ignore="">
                        {rowActions}
                      </div>
                      {rowActions.length ? (
                        <button
                          type="button"
                          className="grid h-11 w-11 shrink-0 place-items-center rounded-sm text-ink-700 hover:bg-purple-50 sm:hidden"
                          aria-label={`More for ${file.name}`}
                          aria-expanded={menuOpen}
                          onClick={() => setMenuFor(menuOpen ? null : file.path)}
                        >
                          <MoreVertical className="h-5 w-5" aria-hidden />
                        </button>
                      ) : null}
                    </div>
                    {menuOpen ? (
                      <div className="mt-2 sm:hidden" data-row-ignore="">
                        <ActionGroup>{rowActions}</ActionGroup>
                      </div>
                    ) : null}
                    {extras(file)}
                  </li>
                );
              })}
            </ul>
            </>
          )}
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line-200 px-4 py-3 text-sm text-ink-500">
            <span>
              {filtered.length
                ? `Showing ${paged.first.toLocaleString("en-IN")}–${paged.last.toLocaleString("en-IN")} of ${filtered.length.toLocaleString("en-IN")}${tree.nextCursor ? "+" : ""}`
                : "No files of this type here."}
            </span>
            <ActionGroup align="end">
              {paged.pages > 1 ? (
                <button type="button" className={ACTION} disabled={paged.page <= 1} onClick={() => setPage(paged.page - 1)}>
                  Previous
                </button>
              ) : null}
              {paged.pages > 1 ? (
                <button type="button" className={ACTION} disabled={paged.page >= paged.pages} onClick={() => setPage(paged.page + 1)}>
                  Next
                </button>
              ) : null}
              {tree.nextCursor && paged.page >= paged.pages ? (
                <button type="button" className={ACTION} disabled={loading} onClick={onMore}>
                  {loading ? "Loading…" : "Load more"}
                </button>
              ) : null}
            </ActionGroup>
          </div>
        </>
      ) : tree.nextCursor ? (
        <div className="p-3">
          <button type="button" className={`${ACTION} mx-auto flex`} disabled={loading} onClick={onMore}>
            {loading ? "Loading…" : "Load more"}
          </button>
        </div>
      ) : null}
    </div>
  );
}
