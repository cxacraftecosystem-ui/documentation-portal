"use client";

import { Archive, ChevronRight, Download, Eye, FileText, MoreHorizontal } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type MouseEvent, type ReactNode } from "react";

import { FileTypeIcon, LINK_BUTTON, Note, Problem, RecordIcon, Spinner } from "@/components/dataExplorer/explorerUi";
import { canPreview } from "@/components/dataExplorer/PreviewDialog";
import { EmptyState } from "@/components/EmptyState";
import { TranscriptMarkdown } from "@/components/transcript/TranscriptMarkdown";
import { fetchTranscriptText, mediaDownloadUrl, mediaThumbnailUrl, transcriptZipUrl } from "@/lib/dataExplorer/api";
import { sizeOf, type Crumb, type FolderInfo, type Manifest, type TreeEntry, type TreeResponse } from "@/lib/dataExplorer/contract";
import { formatBytes } from "@/lib/dataExplorer/plan";
import { selectionKey, type Selection } from "@/lib/dataExplorer/selection";
import { apiUrl, type QueryParams } from "@/lib/dataExplorer/wire";
import { apiFetch, buildQuery } from "@/lib/api";

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

export function entryMeta(entry: TreeEntry): string {
  const kind = entry.mediaType ?? (entry.content != null ? "Text" : "File");
  const size = sizeOf(entry.sizeBytes);
  return [kind.charAt(0) + kind.slice(1).toLowerCase(), size !== null ? formatBytes(size) : null].filter(Boolean).join(" · ");
}

function Thumb({ entry }: { entry: TreeEntry }) {
  const [broken, setBroken] = useState(false);
  const source = entry.mediaType === "IMAGE" && entry.mediaId ? (entry.thumbnailPath ? apiUrl(entry.thumbnailPath, { size: 256 }) : mediaThumbnailUrl(entry.mediaId)) : null;
  if (source && !broken) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={source}
        alt=""
        width={48}
        height={48}
        loading="lazy"
        decoding="async"
        onError={() => setBroken(true)}
        className="h-12 w-12 shrink-0 rounded-md border border-line-200 bg-surface-50 object-cover"
      />
    );
  }
  return (
    <span className="grid h-12 w-12 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700 dark:bg-purple-950/40 dark:text-purple-300">
      <FileTypeIcon entry={entry} className="h-5 w-5" />
    </span>
  );
}

function SelectBox({ checked, label, onChange }: { checked: boolean; label: string; onChange: () => void }) {
  return (
    <label className="grid h-11 w-11 shrink-0 cursor-pointer place-items-center rounded-md hover:bg-purple-50 dark:hover:bg-purple-950/30" data-row-ignore="">
      <input type="checkbox" checked={checked} onChange={onChange} aria-label={label} className="h-4 w-4 accent-purple-700" />
    </label>
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

export function RecordInfoCard({ info }: { info: FolderInfo }) {
  const fields = (info.fields ?? []).filter((field) => field.value != null && String(field.value).trim() !== "");
  if (fields.length === 0) return null;
  return (
    <details className="panel group overflow-hidden" open>
      <summary className="flex min-h-11 cursor-pointer list-none flex-wrap items-baseline gap-x-3 gap-y-1 px-5 py-3">
        <ChevronRight className="h-4 w-4 self-center text-ink-500 transition-transform group-open:rotate-90" aria-hidden />
        <h2 className="font-display text-base font-bold text-ink-900">{info.title}</h2>
        <span className="text-xs text-ink-500">
          {fields.length} {fields.length === 1 ? "field" : "fields"}
        </span>
      </summary>
      <dl className="grid border-t border-line-200 text-sm sm:grid-cols-[14rem_minmax(0,1fr)]">
        {fields.map((field, index) => (
          <div key={`${field.label}-${index}`} className="contents">
            <dt className="wrap-break-word border-b border-line-200 bg-surface-50 px-5 py-2.5 font-semibold text-ink-900 sm:border-r">{field.label}</dt>
            <dd className="border-b border-line-200 px-5 py-2.5 text-ink-700">
              <div className="max-h-72 overflow-y-auto whitespace-pre-line wrap-break-word leading-6">{field.value}</div>
            </dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

export function FolderContents({
  path,
  tree,
  crumbs,
  loading,
  error,
  extra,
  selection,
  fileHref,
  emptyBody,
  emptyAction,
  onOpen,
  onToggle,
  onSelectAll,
  onPreview,
  onMore,
  onRetry
}: {
  path: string;
  tree: TreeResponse | undefined;
  crumbs: Crumb[];
  loading: boolean;
  error: string | null;
  extra: QueryParams;
  selection: Selection;
  fileHref: (mediaId: string) => string | null;
  emptyBody: string;
  emptyAction?: ReactNode;
  onOpen: (path: string) => void;
  onToggle: (entry: TreeEntry) => void;
  onSelectAll: (entries: TreeEntry[], on: boolean) => void;
  onPreview: (entry: TreeEntry) => void;
  onMore: () => void;
  onRetry: () => void;
}) {
  const router = useRouter();
  const [openText, setOpenText] = useState<ReadonlySet<string>>(new Set());
  const [openTranscripts, setOpenTranscripts] = useState<ReadonlySet<string>>(new Set());
  const [shownFor, setShownFor] = useState(path);
  if (shownFor !== path) {
    setShownFor(path);
    setOpenText(new Set());
    setOpenTranscripts(new Set());
  }
  const entries = tree?.entries ?? [];
  const folders = entries.filter((entry) => entry.kind === "folder");
  const files = entries.filter((entry) => entry.kind === "file");
  const allSelected = entries.length > 0 && entries.every((entry) => selection.has(selectionKey(entry)));

  const flip = (set: ReadonlySet<string>, key: string) => {
    const next = new Set(set);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  };

  const openRow = (event: MouseEvent<HTMLElement>, href: string | null) => {
    if (!href) return;
    if ((event.target as HTMLElement).closest(INTERACTIVE)) return;
    if (window.getSelection()?.toString()) return;
    if (event.metaKey || event.ctrlKey) window.open(href, "_blank", "noopener");
    else router.push(href);
  };

  return (
    <section className="panel overflow-hidden" aria-label="Folder contents">
      <nav aria-label="Folder path" className="flex flex-wrap items-center gap-1 border-b border-line-200 bg-surface-50 px-4 py-2.5 text-sm">
        {crumbs.map((crumb, index) => (
          <span key={crumb.path || "__root"} className="flex items-center gap-1">
            {index > 0 ? <ChevronRight className="h-3.5 w-3.5 text-ink-500" aria-hidden /> : null}
            {index === crumbs.length - 1 ? (
              <span className="font-display font-semibold text-ink-900" aria-current="location">
                {crumb.name}
              </span>
            ) : (
              <button type="button" className="min-h-9 font-medium text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => onOpen(crumb.path)}>
                {crumb.name}
              </button>
            )}
          </span>
        ))}
        <span className="ml-auto text-xs text-ink-500">
          {tree ? `${folders.length} ${folders.length === 1 ? "folder" : "folders"} · ${files.length} ${files.length === 1 ? "file" : "files"}${tree.nextCursor ? "+" : ""}` : ""}
        </span>
      </nav>

      {error ? (
        <div className="p-4">
          <Problem onRetry={onRetry}>{error}</Problem>
        </div>
      ) : null}

      {tree?.truncated && !tree.nextCursor ? (
        <div className="border-b border-line-200 px-4 py-2">
          <Note tone="warn">Showing part of this folder. Open subfolders for the rest.</Note>
        </div>
      ) : null}

      {!tree ? (
        <div className="p-4">{loading ? <Spinner label="Loading folder…" /> : <p className="text-sm text-ink-500">Choose a folder.</p>}</div>
      ) : entries.length === 0 ? (
        <div className="grid gap-2 p-4">
          <EmptyState title="Empty folder" body={emptyBody} />
          {emptyAction}
        </div>
      ) : (
        <div>
          <div className="flex items-center border-b border-line-200 px-2 py-1">
            <label className="flex min-h-11 cursor-pointer items-center gap-2 rounded-md pr-3 hover:bg-purple-50 dark:hover:bg-purple-950/30" data-row-ignore="">
              <span className="grid h-11 w-11 shrink-0 place-items-center">
                <input type="checkbox" checked={allSelected} onChange={() => onSelectAll(entries, !allSelected)} className="h-4 w-4 accent-purple-700" />
              </span>
              <span className="text-xs text-ink-500">Select all</span>
            </label>
          </div>
          {folders.length ? (
            <ul className="grid gap-2 border-b border-line-200 p-3 sm:grid-cols-2 xl:grid-cols-3" aria-label="Folders">
              {folders.map((folder) => {
                const key = selectionKey(folder);
                return (
                  <li key={folder.path} className="flex items-center gap-1 rounded-lg border border-line-200 bg-card pr-2 shadow-sm transition hover:border-purple-300">
                    <SelectBox checked={selection.has(key)} label={`Select ${folder.name}`} onChange={() => onToggle(folder)} />
                    <button type="button" className="flex min-h-11 min-w-0 flex-1 items-center gap-3 py-2 text-left" onClick={() => onOpen(folder.path)}>
                      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-purple-800 text-purple-50">
                        <RecordIcon recordType={folder.recordType} className="h-4 w-4" />
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate font-display text-sm font-semibold text-ink-900">{folder.name}</span>
                        {folder.recordType ? <span className="block text-xs capitalize text-ink-500">{folder.recordType}</span> : null}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : null}
          {files.length ? (
            <ul className="divide-y divide-line-200" aria-label="Files">
              {files.map((file) => {
                const key = selectionKey(file);
                const href = file.mediaId ? fileHref(file.mediaId) : null;
                const textOpen = openText.has(file.path);
                const transcriptOpen = openTranscripts.has(file.path);
                return (
                  <li key={file.path} className={`px-2 py-2 ${href ? "cursor-pointer hover:bg-purple-50/30" : ""}`} onClick={(event) => openRow(event, href)} data-file-row="">
                    <div className="flex flex-wrap items-center gap-2">
                      <SelectBox checked={selection.has(key)} label={`Select ${file.name}`} onChange={() => onToggle(file)} />
                      <Thumb entry={file} />
                      <div className="min-w-0 flex-1 px-1">
                        <div className="break-all text-sm font-medium text-ink-900">
                          {href ? (
                            <Link href={href} className="hover:text-purple-700 hover:underline">
                              {file.name}
                            </Link>
                          ) : (
                            file.name
                          )}
                        </div>
                        <div className="text-xs text-ink-500">{entryMeta(file)}</div>
                      </div>
                      <div className="flex flex-wrap items-center gap-1 max-sm:w-full max-sm:pl-12" data-row-ignore="">
                        {canPreview(file) ? (
                          <button type="button" className={LINK_BUTTON} onClick={() => onPreview(file)}>
                            <Eye className="h-4 w-4" aria-hidden />
                            Preview
                          </button>
                        ) : null}
                        {file.content != null ? (
                          <button type="button" className={LINK_BUTTON} aria-expanded={textOpen} onClick={() => setOpenText(flip(openText, file.path))}>
                            <FileText className="h-4 w-4" aria-hidden />
                            {textOpen ? "Hide" : "View"}
                          </button>
                        ) : null}
                        {hasTranscript(file) && file.content == null ? (
                          <button type="button" className={LINK_BUTTON} aria-expanded={transcriptOpen} onClick={() => setOpenTranscripts(flip(openTranscripts, file.path))}>
                            <FileText className="h-4 w-4" aria-hidden />
                            {transcriptOpen ? "Hide transcript" : "Transcript"}
                          </button>
                        ) : null}
                        {hasTranscript(file) ? (
                          <a href={transcriptZipUrl(file.mediaId!)} download className={LINK_BUTTON}>
                            <Archive className="h-4 w-4" aria-hidden />
                            All formats
                            <span className="sr-only"> (.zip), {file.name}</span>
                          </a>
                        ) : null}
                        {file.mediaId && file.mediaType && file.content == null ? (
                          <a href={file.downloadPath ? apiUrl(file.downloadPath) : mediaDownloadUrl(file.mediaId)} download className={LINK_BUTTON}>
                            <Download className="h-4 w-4" aria-hidden />
                            <span className="sr-only">Download {file.name}</span>
                            <span aria-hidden>Save</span>
                          </a>
                        ) : null}
                      </div>
                    </div>
                    {file.transcriptFiles?.length ? (
                      <p data-row-ignore="" className="mt-1 flex flex-wrap gap-x-3 gap-y-1 pl-24 text-xs" data-transcript-files>
                        {file.transcriptFiles.map((link) => (
                          <a key={link.url} href={link.url} download={link.name} className="inline-flex min-h-9 items-center font-semibold text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11">
                            {link.name}
                          </a>
                        ))}
                      </p>
                    ) : null}
                    {textOpen && file.content != null ? (
                      <pre data-row-ignore="" tabIndex={0} className="mx-2 mt-2 max-h-80 overflow-auto whitespace-pre-wrap rounded-md border border-line-200 bg-surface-50 p-4 font-sans text-sm leading-6 text-ink-700">
                        {file.content}
                      </pre>
                    ) : null}
                    {transcriptOpen ? (
                      <div data-row-ignore="" className="mx-2 mt-2">
                        <TranscriptBlock entry={file} folder={path} extra={extra} />
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          ) : null}
          {tree.nextCursor ? (
            <div className="border-t border-line-200 p-3">
              <button type="button" className="field-button-secondary mx-auto flex" onClick={onMore} disabled={loading}>
                <MoreHorizontal className="h-4 w-4" aria-hidden />
                {loading ? "Loading…" : "Load more"}
              </button>
            </div>
          ) : null}
        </div>
      )}
    </section>
  );
}
