"use client";

import { Archive, ArrowUpRight, Download, MoreVertical, Square } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type MouseEvent, type PointerEvent, type ReactNode } from "react";

import { FileTypeIcon, FolderSkeleton, LoadFailed, Problem, RecordIcon, Spinner } from "@/components/dataExplorer/explorerUi";
import { canPreview } from "@/components/dataExplorer/PreviewDialog";
import { TranscriptMarkdown } from "@/components/transcript/TranscriptMarkdown";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { apiFetch, buildQuery } from "@/lib/api";
import { fetchTranscriptText, mediaDownloadUrl, mediaThumbnailUrl, transcriptZipUrl } from "@/lib/dataExplorer/api";
import { fetchTranscriptSummaries, languagesLine, rowState, shortClock, translatingLabel, type TranscriptSummary } from "@/lib/dataExplorer/preview";
import { sizeOf, type Manifest, type TreeEntry, type TreeResponse } from "@/lib/dataExplorer/contract";
import { capturedLabel, filterOf, pageOf, thumbnailEdge, TYPE_FILTERS, type TypeFilter } from "@/lib/dataExplorer/explorerView";
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

export const TAG_TONE: Record<string, string> = {
  VID: "bg-[#e7d5fe] text-purple-800 dark:bg-purple-950/70 dark:text-purple-200",
  AUD: "bg-amber-100 text-amber-800 dark:bg-amber-950/60 dark:text-amber-200",
  PDF: "bg-error-100 text-red-700 dark:bg-[#4a1717] dark:text-red-200",
  DOC: "bg-sky-100 text-sky-800 dark:bg-sky-950/60 dark:text-sky-200"
};

export function mediaTag(mediaType: string | null | undefined): string | null {
  return mediaType === "VIDEO" ? "VID" : mediaType === "AUDIO" ? "AUD" : mediaType === "PDF" ? "PDF" : mediaType === "DOCUMENT" ? "DOC" : null;
}

function Thumb({ entry, size = "md" }: { entry: TreeEntry; size?: "md" | "lg" }) {
  const [broken, setBroken] = useState(false);
  const [edge, setEdge] = useState<number | null>(null);
  const frame = useRef<HTMLSpanElement | null>(null);
  const box = size === "lg" ? "aspect-[4/3] w-full rounded-sm" : "h-9 w-12 rounded-sm sm:max-lg:h-[42px] sm:max-lg:w-14 max-sm:h-12 max-sm:w-12 max-sm:rounded-[10px]";
  const tag = mediaTag(entry.mediaType);
  const pictured = Boolean(entry.mediaId && (entry.mediaType === "IMAGE" || (entry.mediaType === "VIDEO" && entry.thumbnailPath)) && (entry.mediaType === "IMAGE" || !tag));
  useEffect(() => {
    const node = frame.current;
    if (!pictured || !node) return;
    const measure = () => {
      const width = Math.max(node.clientWidth, node.clientHeight);
      if (width > 0) setEdge((previous) => Math.max(previous ?? 0, thumbnailEdge(width, window.devicePixelRatio || 1)));
    };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [pictured]);
  if (pictured && !broken && entry.mediaId) {
    const source = edge === null ? null : entry.thumbnailPath ? apiUrl(entry.thumbnailPath, { size: edge }) : mediaThumbnailUrl(entry.mediaId, edge);
    return (
      <span ref={frame} className={`${box} relative block shrink-0 overflow-hidden bg-field-100`} data-thumb="">
        {source ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={source} alt="" loading="lazy" decoding="async" onError={() => setBroken(true)} className="absolute inset-0 h-full w-full object-contain" />
        ) : null}
      </span>
    );
  }
  return (
    <span className={`grid ${box} shrink-0 place-items-center text-[11px] font-bold ${tag ? TAG_TONE[tag] : "bg-purple-100 text-purple-800 dark:bg-purple-950/50 dark:text-purple-200"}`}>
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

const ACTION =
  "inline-flex min-h-[34px] items-center justify-center gap-1.5 rounded-sm border border-line-200 bg-card px-3 text-[13px] text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40 pointer-coarse:min-h-11";
const ICON_ACTION =
  "grid h-[34px] w-[34px] shrink-0 place-items-center rounded-sm border border-line-200 bg-card text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40 pointer-coarse:h-11 pointer-coarse:w-11";
const COLUMNS = "lg:grid-cols-[76px_minmax(0,1fr)_97px_81px_159px_234px]";

function statusWord(status: DownloadStatus | undefined): string | null {
  if (status === "saved") return "Saved";
  if (status === "failed") return "Failed";
  if (status === "running") return "Downloading";
  if (status === "waiting") return "Waiting";
  return null;
}

function MoreButton({ className, loading, onMore }: { className: string; loading: boolean; onMore: () => void }) {
  const ref = useRef<HTMLButtonElement | null>(null);
  const latest = useRef({ loading, onMore });
  useEffect(() => {
    latest.current = { loading, onMore };
  }, [loading, onMore]);
  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((seen) => {
      if (seen.some((entry) => entry.isIntersecting) && !latest.current.loading) latest.current.onMore();
    }, { rootMargin: "200px" });
    observer.observe(node);
    return () => observer.disconnect();
  }, [loading]);
  return (
    <button ref={ref} type="button" className={className} disabled={loading} onClick={onMore} data-testid="folder-more">
      {loading ? "Loading…" : "Load more"}
    </button>
  );
}

function FileName({ entry }: { entry: TreeEntry }) {
  const stem = displayName(entry);
  return (
    <>
      {stem}
      {stem !== entry.name ? <span className="sr-only">{entry.name.slice(stem.length)}</span> : null}
    </>
  );
}

export function displayName(entry: TreeEntry): string {
  if (entry.kind !== "file" || entry.content != null) return entry.name;
  const stem = entry.name.replace(/\.[A-Za-z0-9]{1,5}$/, "");
  return stem || entry.name;
}

export type PlayingRow = { mediaId: string; ms: number; durationMs: number | null };

export function factsLine(entry: TreeEntry): string {
  const kind = kindLabel(entry);
  if (entry.mediaType === "IMAGE" && entry.pixels && entry.pixels.width > 0 && entry.pixels.height > 0) return `${kind} · ${entry.pixels.width} × ${entry.pixels.height}`;
  if ((entry.mediaType === "PDF" || entry.mediaType === "DOCUMENT") && typeof entry.pages === "number" && entry.pages > 0) return `${kind} · ${entry.pages} ${entry.pages === 1 ? "page" : "pages"}`;
  return kind;
}

function detailLine(entry: TreeEntry, summary: TranscriptSummary | undefined): string {
  const kind = kindLabel(entry);
  if (!hasTranscript(entry)) return factsLine(entry);
  if (!summary) return `${kind} · transcript`;
  const state = rowState(summary.status, true);
  if (state === "failed") return "Transcript failed";
  if (state === "working") return translatingLabel(summary);
  if (state === "waiting") return "Transcript waiting";
  if (entry.mediaType === "AUDIO" && summary.durationMs) return `Transcript ready · ${shortClock(summary.durationMs)}`;
  const languages = languagesLine(summary.languages);
  return languages ? `Transcript · ${languages}` : "Transcript ready";
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
  onRetry,
  layout = "list",
  playing = null
}: {
  path: string;
  tree: TreeResponse | undefined;
  loading: boolean;
  error: string | null;
  extra: QueryParams;
  selection: Selection;
  fileHref: (mediaId: string) => string | null;
  emptyBody: string | ((folder: string) => string);
  emptyAction?: ReactNode;
  mediaStatus: ReadonlyMap<string, DownloadStatus>;
  onOpen: (path: string) => void;
  onToggle: (entry: TreeEntry) => void;
  onSelectAll: (entries: TreeEntry[], on: boolean) => void;
  onPreview: (entry: TreeEntry) => void;
  onDownload: (request: DownloadRequest) => void;
  onMore: () => void;
  onRetry: () => void;
  layout?: "list" | "grid";
  playing?: PlayingRow | null;
}) {
  const router = useRouter();
  const [openText, setOpenText] = useState<ReadonlySet<string>>(new Set());
  const [openTranscripts, setOpenTranscripts] = useState<ReadonlySet<string>>(new Set());
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [filter, setFilter] = useState<TypeFilter>("all");
  const [page, setPage] = useState(1);
  const [summaries, setSummaries] = useState<ReadonlyMap<string, TranscriptSummary>>(new Map());
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
  const selecting = selection.size > 0;

  const wantedKey = paged.items
    .filter((file) => file.mediaId && hasTranscript(file))
    .map((file) => file.mediaId as string)
    .join(",");
  useEffect(() => {
    if (!wantedKey) return;
    const abort = new AbortController();
    fetchTranscriptSummaries(wantedKey.split(","), abort.signal)
      .then((found) => {
        if (found && !abort.signal.aborted) setSummaries((previous) => new Map([...previous, ...found]));
      })
      .catch(() => undefined);
    return () => abort.abort();
  }, [wantedKey]);

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
    if ((event.target as HTMLElement).closest(INTERACTIVE)) return;
    if (window.getSelection()?.toString()) return;
    if (href && (event.metaKey || event.ctrlKey)) {
      window.open(href, "_blank", "noopener");
      return;
    }
    if (entry && canPreview(entry)) {
      onPreview(entry);
      return;
    }
    if (href) router.push(href);
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
          {file.mediaType === "AUDIO" ? "Play" : "Preview"}
        </button>
      );
    if (file.content != null)
      out.push(
        <button key="text" type="button" className={ACTION} aria-expanded={openText.has(file.path)} onClick={() => setOpenText(flip(openText, file.path))}>
          {openText.has(file.path) ? "Hide" : "View"}
        </button>
      );
    const recordHref = file.mediaId ? fileHref(file.mediaId) : null;
    if (recordHref)
      out.push(
        <Link key="record" href={recordHref} className={`${ACTION} lg:w-[34px] lg:px-0`} title="Open record" data-testid="row-open-record">
          <ArrowUpRight className="h-4 w-4" aria-hidden />
          <span className="lg:sr-only">Open record</span>
        </Link>
      );
    if (hasTranscript(file) && file.content == null)
      out.push(
        <button key="transcript" type="button" className={ACTION} aria-expanded={transcriptOpen} onClick={() => setOpenTranscripts(flip(openTranscripts, file.path))}>
          {transcriptOpen ? "Hide transcript" : "Transcript"}
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
        <p data-row-ignore="" className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs lg:pl-[136px]" data-transcript-files>
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
        <div data-row-ignore="" className="mt-2 grid gap-2">
          <TranscriptBlock entry={file} folder={path} extra={extra} />
          {hasTranscript(file) ? (
            <div>
              <button type="button" className={ACTION} onClick={() => onDownload({ kind: "transcript", label: `Transcript · ${file.name}`, url: transcriptZipUrl(file.mediaId!) })}>
                <Archive className="h-4 w-4" aria-hidden />
                All formats
                <span className="sr-only"> (.zip), {file.name}</span>
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </>
  );

  if (error && !tree) {
    return (
      <div className="p-4">
        <LoadFailed title="This folder didn't load" onRetry={onRetry} />
      </div>
    );
  }
  if (!tree) return loading ? <FolderSkeleton /> : <p className="p-4 text-sm text-ink-500">Choose a folder.</p>;
  if (entries.length === 0) {
    return (
      <div className="grid justify-items-center gap-2 px-4 py-10 text-center max-sm:panel">
        <span className="mb-2 grid h-14 w-14 place-items-center rounded-[16px] bg-purple-100 text-purple-700 dark:bg-purple-950/50 dark:text-purple-200" aria-hidden>
          <Square className="h-5 w-5" />
        </span>
        <h3 className="font-display text-lg leading-6 font-bold text-ink-900">Nothing recorded here yet</h3>
        <p className="max-w-[16rem] text-sm leading-[21px] text-ink-700">{typeof emptyBody === "function" ? emptyBody(tree.crumbs?.[tree.crumbs.length - 1]?.name ?? "this folder") : emptyBody}</p>
        {emptyAction ? <div className="mt-2 flex flex-wrap justify-center gap-2">{emptyAction}</div> : null}
      </div>
    );
  }

  const rowTone = (file: TreeEntry, checked: boolean) => {
    const status = file.mediaId ? mediaStatus.get(file.mediaId) : undefined;
    const tone = status ? toneOf(status) : "neutral";
    if (tone !== "neutral") return ROW_TONE_CLASS[tone];
    if (playing && file.mediaId === playing.mediaId) return "bg-[#faf5ff] dark:bg-purple-950/40";
    if (checked) return "bg-[#faf6ff] dark:bg-purple-950/30";
    return "";
  };

  return (
    <div aria-busy={loading || undefined} data-testid="folder-contents" className="max-sm:grid max-sm:gap-3">
      {error ? (
        <div className="px-4 pt-3">
          <Problem onRetry={onRetry}>{error}</Problem>
        </div>
      ) : null}
      {folders.length ? (
        <ul className="grid gap-2 border-b border-line-200 p-3 max-sm:panel sm:grid-cols-2 xl:grid-cols-3" aria-label="Folders">
          {folders.map((folder) => (
            <li key={folder.path} className={`flex items-center gap-1 rounded-md border border-line-200 pr-2 transition hover:border-purple-300 ${selection.has(selectionKey(folder)) ? "bg-[#faf6ff] dark:bg-purple-950/30" : "bg-card"}`}>
              <label className="grid h-11 w-11 shrink-0 cursor-pointer place-items-center" data-row-ignore="">
                <input type="checkbox" checked={selection.has(selectionKey(folder))} onChange={() => onToggle(folder)} aria-label={`Select ${folder.name}`} className="h-[18px] w-[18px] accent-purple-700" />
              </label>
              <button type="button" className="flex min-h-12 min-w-0 flex-1 items-center gap-3 py-2 text-left" onClick={() => onOpen(folder.path)}>
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-sm bg-purple-100 text-purple-800 dark:bg-purple-950/60 dark:text-purple-200">
                  <RecordIcon recordType={folder.recordType} className="h-4 w-4" />
                </span>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-semibold text-ink-900">{folder.name}</span>
                  <span className="block text-xs text-ink-500">
                    {[folder.recordType ? folder.recordType.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (first) => first.toUpperCase()) : null, typeof folder.count === "number" ? `${folder.count.toLocaleString("en-IN")} files` : null]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {files.length ? (
        <>
          <div className={`flex flex-wrap items-center gap-2 border-b border-line-200 px-4 py-3 max-lg:hidden max-sm:min-w-0 max-sm:flex-nowrap max-sm:border-0 max-sm:p-0 ${selecting ? "max-sm:flex" : ""}`}>
            <span className="text-xs text-ink-500 max-sm:hidden">Show</span>
            <div role="group" aria-label="Show file types" className="flex min-w-0 flex-wrap gap-2 max-sm:flex-1 max-sm:flex-nowrap max-sm:overflow-x-auto">
              {TYPE_FILTERS.filter((option) => option.value === "all" || counts[option.value] > 0).map((option) => (
                <button
                  key={option.value}
                  type="button"
                  aria-pressed={filter === option.value}
                  onClick={() => {
                    setFilter(option.value);
                    setPage(1);
                  }}
                  className={`min-h-8 shrink-0 rounded-full px-3 text-xs transition pointer-coarse:min-h-11 max-sm:min-h-[34px] max-sm:px-3 max-sm:text-[13px] ${
                    filter === option.value
                      ? "bg-purple-700 font-semibold text-white max-sm:bg-ink-900 max-sm:text-card dark:bg-purple-300 dark:text-purple-950 max-sm:dark:bg-ink-900"
                      : "border border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40"
                  }`}
                >
                  {option.label} {counts[option.value]}
                </button>
              ))}
            </div>
          </div>
          {layout === "grid" ? (
            <ul className="grid grid-cols-2 gap-3 p-3 md:grid-cols-3 xl:grid-cols-4" aria-label="Files">
              {paged.items.map((file) => {
                const status = file.mediaId ? mediaStatus.get(file.mediaId) : undefined;
                const checked = selection.has(selectionKey(file));
                return (
                  <li key={file.path} className={`grid gap-2 rounded-md border border-line-200 p-2 ${rowTone(file, checked) || "bg-card"}`} data-status={status}>
                    <button type="button" className="block" onClick={() => (canPreview(file) ? onPreview(file) : undefined)} aria-label={canPreview(file) ? `Preview ${file.name}` : file.name}>
                      <Thumb entry={file} size="lg" />
                    </button>
                    <div className="flex items-start gap-2">
                      <input type="checkbox" checked={checked} onChange={() => onToggle(file)} aria-label={`Select ${file.name}`} className="mt-0.5 h-[18px] w-[18px] shrink-0 accent-purple-700" />
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-ink-900">
                          <FileName entry={file} />
                        </p>
                        <p className="text-xs text-ink-500">{[entryMeta(file), statusWord(status)].filter(Boolean).join(" · ")}</p>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : (
            <>
              <div className={`hidden items-center border-b border-line-200 bg-surface-50 text-xs font-semibold tracking-[0.04em] text-ink-500 uppercase lg:grid ${COLUMNS}`}>
                <span className="px-4 py-2.5">
                  <input type="checkbox" checked={allSelected} onChange={() => onSelectAll(entries, !allSelected)} aria-label="Select all" className="block h-[18px] w-[18px] accent-purple-700" />
                </span>
                <span className="px-2 py-2.5">Name</span>
                <span className="px-2 py-2.5">Type</span>
                <span className="px-2 py-2.5">Size</span>
                <span className="px-2 py-2.5">Captured</span>
                <span className="px-4 py-2.5 text-right">Actions</span>
              </div>
              <ul className="divide-y divide-line-200 max-sm:panel max-sm:overflow-hidden" aria-label="Files">
                {paged.items.map((file) => {
                  const key = selectionKey(file);
                  const href = file.mediaId ? fileHref(file.mediaId) : null;
                  const status = file.mediaId ? mediaStatus.get(file.mediaId) : undefined;
                  const checked = selection.has(key);
                  const rowActions = actions(file);
                  const primary = rowActions[0] ?? null;
                  const menuOpen = menuFor === file.path;
                  const isPlaying = Boolean(playing && file.mediaId === playing.mediaId);
                  const word = statusWord(status);
                  const desktopLine = isPlaying && playing ? `Playing · ${shortClock(playing.ms)}${playing.durationMs ? ` of ${shortClock(playing.durationMs)}` : ""}` : [detailLine(file, file.mediaId ? summaries.get(file.mediaId) : undefined), word].filter(Boolean).join(" · ");
                  const shortLine = isPlaying && playing ? desktopLine : [entryMeta(file), word].filter(Boolean).join(" · ");
                  return (
                    <li
                      key={file.path}
                      className={`${rowTone(file, checked)} ${(href || canPreview(file)) && !rowTone(file, checked) ? "cursor-pointer hover:bg-surface-50" : href || canPreview(file) ? "cursor-pointer" : ""}`}
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
                      data-playing={isPlaying || undefined}
                    >
                      <div className={`flex items-center gap-3 px-4 py-3 max-sm:p-3 lg:grid lg:gap-0 lg:px-0 lg:py-0 ${COLUMNS}`}>
                        <label className={`shrink-0 cursor-pointer lg:grid lg:h-full lg:place-items-start lg:px-4 lg:py-2.5 ${selecting ? "" : "max-sm:hidden"}`} data-row-ignore="">
                          <input type="checkbox" checked={checked} onChange={() => onToggle(file)} aria-label={`Select ${file.name}`} className="block h-[22px] w-[22px] accent-purple-700 max-sm:h-6 max-sm:w-6 lg:mt-[9px] lg:h-[18px] lg:w-[18px]" />
                        </label>
                        <div className="flex min-w-0 flex-1 items-center gap-3 lg:px-2 lg:py-2.5">
                          <Thumb entry={file} />
                          <div className="min-w-0 flex-1">
                            <div className={`truncate text-sm font-semibold ${isPlaying ? "text-purple-800 dark:text-purple-100" : "text-ink-900"}`}>
                              {canPreview(file) ? (
                                <button type="button" className="max-w-full truncate text-left hover:text-purple-700 hover:underline" onClick={() => onPreview(file)} data-testid="row-preview-name">
                                  <FileName entry={file} />
                                </button>
                              ) : href ? (
                                <Link href={href} aria-label={file.name} className="hover:text-purple-700 hover:underline">
                                  <FileName entry={file} />
                                </Link>
                              ) : (
                                <FileName entry={file} />
                              )}
                            </div>
                            <div className={`truncate text-xs ${isPlaying ? "text-purple-800 dark:text-purple-200" : "text-ink-500"}`}>
                              <span className="max-lg:hidden">{desktopLine}</span>
                              <span className="lg:hidden">{shortLine}</span>
                            </div>
                          </div>
                        </div>
                        <span className="hidden px-2 text-sm text-ink-700 lg:block">{kindLabel(file)}</span>
                        <span className="hidden px-2 text-sm tabular-nums text-ink-700 lg:block">{sizeOf(file.sizeBytes) !== null ? formatBytes(sizeOf(file.sizeBytes)!) : "-"}</span>
                        <span className="hidden px-2 text-sm tabular-nums text-ink-700 lg:block" data-captured="">
                          {capturedLabel(file.capturedAt)}
                        </span>
                        <div className="hidden items-center justify-end gap-1.5 px-4 lg:flex" data-row-ignore="">
                          {rowActions}
                        </div>
                        {primary ? (
                          <div className="hidden shrink-0 sm:max-lg:flex sm:max-lg:items-center sm:max-lg:gap-1" data-row-ignore="">
                            <span className="contents [&>button:first-child]:min-h-10 [&>button:first-child]:rounded-[10px]">{primary}</span>
                          </div>
                        ) : null}
                        {rowActions.length ? (
                          <button
                            type="button"
                            className={`grid h-11 w-11 shrink-0 place-items-center rounded-[10px] text-ink-700 hover:bg-purple-50 lg:hidden dark:hover:bg-purple-950/40 ${rowActions.length === 1 ? "sm:hidden" : ""}`}
                            aria-label={`More for ${file.name}`}
                            aria-expanded={menuOpen}
                            onClick={() => setMenuFor(menuOpen ? null : file.path)}
                          >
                            <MoreVertical className="h-[18px] w-[18px]" aria-hidden />
                          </button>
                        ) : null}
                      </div>
                      {menuOpen ? (
                        <div className="px-4 pb-3 lg:hidden" data-row-ignore="">
                          <ActionGroup>{rowActions}</ActionGroup>
                        </div>
                      ) : null}
                      <div className="px-4 lg:px-0">{extras(file)}</div>
                    </li>
                  );
                })}
              </ul>
            </>
          )}
          {paged.pages > 1 || tree.nextCursor ? (
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line-200 px-4 py-3 text-[13px] text-ink-500 max-sm:border-0 max-sm:px-1 max-sm:py-0">
              <span>
                {filtered.length ? `Showing ${paged.first.toLocaleString("en-IN")}–${paged.last.toLocaleString("en-IN")} of ${filtered.length.toLocaleString("en-IN")}${tree.nextCursor ? "+" : ""}` : "No files of this type here."}
              </span>
              <div className="flex gap-1.5">
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
                  <MoreButton className={ACTION} loading={loading} onMore={onMore} />
                ) : null}
              </div>
            </div>
          ) : (
            <div className="border-t border-line-200 px-4 py-3 text-[13px] text-ink-500 max-sm:hidden">
              {filtered.length ? `Showing ${paged.first.toLocaleString("en-IN")}–${paged.last.toLocaleString("en-IN")} of ${filtered.length.toLocaleString("en-IN")}` : "No files of this type here."}
            </div>
          )}
        </>
      ) : tree.nextCursor ? (
        <div className="p-3">
          <MoreButton className={`${ACTION} mx-auto flex`} loading={loading} onMore={onMore} />
        </div>
      ) : null}
      {tree.truncated && !tree.nextCursor ? (
        <p className="border-t border-line-200 px-4 py-3 text-[13px] text-ink-500 max-sm:border-0 max-sm:px-1" data-testid="folder-truncated">
          Not every file could be listed.
        </p>
      ) : null}
    </div>
  );
}
