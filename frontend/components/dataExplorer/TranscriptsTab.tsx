"use client";

import { Archive, BookOpen, FileText, RefreshCw, Search } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { LoadFailed, Skeleton } from "@/components/dataExplorer/explorerUi";
import { EmptyState } from "@/components/EmptyState";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { ROW_TONE_CLASS, type DownloadRequest } from "@/lib/dataExplorer/downloads";
import {
  fetchFolderTranscripts,
  fetchTranscriptSummaries,
  languageOptions,
  stateCounts,
  highlightRanges,
  languagesLine,
  matchesState,
  rowState,
  shortClock,
  speakersLine,
  translatingLabel,
  type FolderTranscript,
  type RowState,
  type StateFilter,
  type TranscriptSummary
} from "@/lib/dataExplorer/preview";
import { apiUrl, type QueryParams } from "@/lib/dataExplorer/wire";
import { TRANSCRIPT_ROUTES, retryTranscript } from "@/lib/transcript/api";

export const TRANSCRIPTS_PAGE = 25;
const SEARCH_DEBOUNCE_MS = 350;
const POLL_MS = 15_000;

export type ReadTarget = { mediaId: string; name: string; mediaType: string | null; startMs?: number | null; find?: string };

type Row = FolderTranscript & { summary: TranscriptSummary | null; state: RowState };

type Loaded = { key: string; items: FolderTranscript[]; total: number; truncated: boolean; nextOffset: number | null };

const ROW_BUTTON =
  "inline-flex min-h-9 items-center justify-center gap-1.5 rounded-md border border-line-200 bg-card px-3 text-xs font-semibold text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60 dark:hover:bg-purple-950/40 pointer-coarse:min-h-11";

const PILL = "inline-flex min-h-6 items-center gap-1 rounded-full px-2 text-[11px] font-semibold";

function StatePill({ row }: { row: Row }) {
  switch (row.state) {
    case "ready":
      return <span className={`${PILL} bg-success-100 text-success-600`}>✓ Ready</span>;
    case "working":
      return <span className={`${PILL} bg-purple-50 text-purple-800 dark:bg-purple-950/50 dark:text-purple-200`}>{translatingLabel(row.summary)}</span>;
    case "failed":
      return <span className={`${PILL} border border-red-300 bg-card text-red-900`}>Failed</span>;
    case "waiting":
      return <span className={`${PILL} bg-surface-50 text-ink-700`}>Waiting</span>;
    default:
      return <span className={`${PILL} bg-surface-50 text-ink-500`}>No transcript</span>;
  }
}

function Snippet({ text, needle }: { text: string; needle: string }) {
  return (
    <>
      “
      {highlightRanges(text, needle).map((part, index) =>
        part.hit ? (
          <mark key={index} className="rounded-sm bg-purple-100 px-0.5 text-purple-800 dark:bg-purple-900 dark:text-purple-100">
            {part.text}
          </mark>
        ) : (
          <span key={index}>{part.text}</span>
        )
      )}
      ”
    </>
  );
}

export function TranscriptsTab({
  path,
  folderName,
  extra,
  onRead,
  onDownload,
  zipUrl,
  onCount
}: {
  path: string;
  folderName: string;
  extra: QueryParams;
  onRead: (target: ReadTarget) => void;
  onDownload: (request: DownloadRequest) => void;
  zipUrl: string;
  onCount?: (count: number) => void;
}) {
  const extraKey = JSON.stringify(extra);
  const [typed, setTyped] = useState("");
  const [needle, setNeedle] = useState("");
  const [filter, setFilter] = useState<StateFilter>("all");
  const [language, setLanguage] = useState("");
  const [showAll, setShowAll] = useState(false);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [summaries, setSummaries] = useState<Map<string, TranscriptSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [retrying, setRetrying] = useState<ReadonlySet<string>>(new Set());
  const [retryNote, setRetryNote] = useState<string | null>(null);
  const countRef = useRef(onCount);
  useEffect(() => {
    countRef.current = onCount;
  });

  useEffect(() => {
    const timer = window.setTimeout(() => setNeedle(typed.trim()), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [typed]);

  const key = `${path}\u0000${extraKey}\u0000${needle}`;

  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const answer = await fetchFolderTranscripts(path, needle, JSON.parse(extraKey) as QueryParams, abort.signal);
        if (abort.signal.aborted) return;
        const items = answer?.items ?? [];
        setLoaded({ key, items, total: answer?.total ?? 0, truncated: answer?.truncated ?? false, nextOffset: answer?.truncated ? (answer.nextOffset ?? null) : null });
        if (!needle) countRef.current?.(answer?.total ?? 0);
        const found = items.length ? await fetchTranscriptSummaries(items.map((item) => item.mediaId), abort.signal).catch(() => null) : new Map<string, TranscriptSummary>();
        if (!abort.signal.aborted) setSummaries((previous) => (found ? new Map([...(previous ?? []), ...found]) : previous));
      } catch (failure) {
        if (!abort.signal.aborted) setError(failure instanceof Error && failure.message ? failure.message : "The transcripts didn't load.");
      } finally {
        if (!abort.signal.aborted) setLoading(false);
      }
    })();
    return () => abort.abort();
  }, [path, extraKey, needle, key, attempt]);

  const rows = useMemo<Row[]>(
    () =>
      (loaded?.items ?? []).map((item) => {
        const summary = summaries?.get(item.mediaId) ?? null;
        return { ...item, summary, state: rowState(summary?.status ?? null, item.hasText) };
      }),
    [loaded, summaries]
  );

  const working = rows.filter((row) => row.state === "working").map((row) => row.mediaId);
  const workingKey = working.join(",");
  useEffect(() => {
    if (!workingKey) return;
    const timer = window.setInterval(() => {
      void fetchTranscriptSummaries(workingKey.split(","))
        .then((found) => {
          if (found) setSummaries((previous) => new Map([...(previous ?? []), ...found]));
        })
        .catch(() => undefined);
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [workingKey]);

  const [loadingMore, setLoadingMore] = useState(false);
  const loadMore = useCallback(async () => {
    const current = loaded;
    if (!current || current.nextOffset === null) return;
    setLoadingMore(true);
    try {
      const answer = await fetchFolderTranscripts(path, needle, JSON.parse(extraKey) as QueryParams, undefined, current.nextOffset);
      const fresh = (answer?.items ?? []).filter((item) => !current.items.some((have) => have.mediaId === item.mediaId));
      setLoaded((previous) =>
        previous && previous.key === current.key
          ? { ...previous, items: [...previous.items, ...fresh], total: previous.total + fresh.length, truncated: Boolean(answer?.truncated) && fresh.length > 0, nextOffset: answer?.truncated && fresh.length ? (answer.nextOffset ?? null) : null }
          : previous
      );
      setShowAll(true);
      const found = fresh.length ? await fetchTranscriptSummaries(fresh.map((item) => item.mediaId)).catch(() => null) : null;
      if (found) setSummaries((previous) => new Map([...(previous ?? []), ...found]));
    } catch (failure) {
      setError(failure instanceof Error && failure.message ? failure.message : "The transcripts didn't load.");
    } finally {
      setLoadingMore(false);
    }
  }, [loaded, path, needle, extraKey]);

  const counts = stateCounts(rows);
  const languages = useMemo(() => languageOptions(rows), [rows]);
  const visible = rows.filter((row) => matchesState(row.state, filter) && (!language || (row.summary?.languages ?? []).some((entry) => entry.code === language)));
  const shown = showAll ? visible : visible.slice(0, TRANSCRIPTS_PAGE);
  const total = loaded?.total ?? 0;

  const retry = useCallback(async (row: Row) => {
    setRetrying((previous) => new Set(previous).add(row.mediaId));
    setRetryNote(null);
    try {
      await retryTranscript(row.mediaId);
      const found = await fetchTranscriptSummaries([row.mediaId]).catch(() => null);
      if (found) setSummaries((previous) => new Map([...(previous ?? []), ...found]));
      setRetryNote(`Trying ${row.name} again.`);
    } catch (failure) {
      setRetryNote(failure instanceof Error && failure.message ? failure.message : "That didn't start. Try again.");
    } finally {
      setRetrying((previous) => {
        const next = new Set(previous);
        next.delete(row.mediaId);
        return next;
      });
    }
  }, []);

  const docx = (row: Row) =>
    onDownload({
      kind: "transcript",
      label: `${row.name} · Transcript (.docx)`,
      url: apiUrl(TRANSCRIPT_ROUTES.download(row.mediaId, "docx", "both")),
      mediaId: row.mediaId,
      path: row.path
    });

  const pills: { value: StateFilter; label: string }[] = [
    { value: "all", label: "All" },
    { value: "ready", label: "Ready" },
    { value: "working", label: "Translating" },
    { value: "failed", label: "Failed" }
  ];

  const stale = loaded !== null && loaded.key !== key;

  return (
    <section className="panel overflow-hidden" aria-label="Transcripts" data-testid="transcripts-tab">
      <div className="flex flex-wrap items-end gap-3 border-b border-line-200 px-4 py-3">
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs text-ink-500">{folderName}</p>
          <h2 className="font-display text-lg font-bold text-ink-900">Transcripts</h2>
        </div>
        {total > 0 ? (
          <ActionGroup align="end">
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => onDownload({ kind: "transcript", label: `Transcripts (.docx) · ${folderName}`, url: apiUrl("/data/transcripts/export", { path, format: "docx", ...(JSON.parse(extraKey) as QueryParams) }), path })}
            >
              <FileText className="h-4 w-4" aria-hidden />
              Export all (.docx)
            </button>
            <button type="button" className="field-button-secondary" onClick={() => onDownload({ kind: "transcript", label: `Transcripts · ${folderName}`, url: zipUrl, path })}>
              <Archive className="h-4 w-4" aria-hidden />
              Transcripts (.zip) · {total.toLocaleString("en-IN")}
            </button>
          </ActionGroup>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-b border-line-200 px-4 py-3">
        <label className="relative flex min-w-0 flex-[1_1_16rem] items-center">
          <Search className="pointer-events-none absolute left-3 h-3.5 w-3.5 text-ink-300" aria-hidden />
          <input
            type="search"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            placeholder="Search inside these transcripts"
            aria-label="Search inside these transcripts"
            className="field-input py-2 pl-8 text-sm"
          />
        </label>
        <div role="group" aria-label="Transcript status" className="flex flex-wrap gap-1.5">
          {pills.map((pill) => (
            <button
              key={pill.value}
              type="button"
              aria-pressed={filter === pill.value}
              onClick={() => setFilter(pill.value)}
              className={`min-h-9 rounded-full border px-3 text-xs font-semibold transition pointer-coarse:min-h-11 ${
                filter === pill.value ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40"
              }`}
            >
              {pill.label} {counts[pill.value].toLocaleString("en-IN")}
            </button>
          ))}
        </div>
        {languages.length > 2 ? (
          <div className="w-44">
            <SearchableSelect ariaLabel="Language" value={language} onChange={setLanguage} options={languages} placeholder="Any language" advanceOnSelect={false} />
          </div>
        ) : null}
      </div>

      {error && !loaded ? (
        <div className="p-4">
          <LoadFailed title="These transcripts didn't load" onRetry={() => setAttempt((value) => value + 1)}>
            {error}
          </LoadFailed>
        </div>
      ) : loading && !loaded ? (
        <div className="grid gap-3 p-4" role="status" aria-label="Loading transcripts">
          {[0, 1, 2, 3].map((index) => (
            <div key={index} className="flex items-center gap-3">
              <Skeleton className="h-11 w-11 shrink-0" />
              <div className="grid flex-1 gap-1.5">
                <Skeleton className="h-3.5 w-2/3" />
                <Skeleton className="h-3 w-1/3" />
              </div>
              <Skeleton className="hidden h-6 w-20 sm:block" />
            </div>
          ))}
        </div>
      ) : rows.length === 0 ? (
        <div className="p-4">
          <EmptyState title={needle ? `Nothing matches “${needle}”` : "No transcripts here yet"} body={needle ? "Try other words." : "Recordings in this folder appear here once they're transcribed."} />
        </div>
      ) : (
        <>
          {error ? (
            <p role="alert" className="flex flex-wrap items-center gap-2 border-b border-red-300 bg-error-100 px-4 py-2 text-sm text-red-900">
              <span className="flex-1">{error}</span>
              <button type="button" className={ROW_BUTTON} onClick={() => setAttempt((value) => value + 1)}>
                <RefreshCw className="h-3.5 w-3.5" aria-hidden />
                Try now
              </button>
            </p>
          ) : null}
          <div className="hidden grid-cols-[2.75rem_minmax(0,1fr)_5rem_8.5rem_12rem] gap-3 border-b border-line-200 bg-surface-50 px-4 py-2 text-xs font-semibold uppercase tracking-wide text-ink-500 md:grid" aria-hidden>
            <span />
            <span>Recording</span>
            <span>Length</span>
            <span>Status</span>
            <span className="text-right">Get</span>
          </div>
          <ul className={`divide-y divide-line-200 ${stale || loading ? "opacity-70" : ""}`} aria-busy={loading || undefined} aria-label="Transcripts list" data-testid="transcript-rows">
            {shown.map((row) => {
              const sub = row.snippet && needle ? null : [languagesLine(row.summary?.languages ?? []), speakersLine(row.summary?.speakerCount ?? 0)].filter(Boolean).join(" · ");
              const length = row.summary?.durationMs ? shortClock(row.summary.durationMs) : "";
              const failed = row.state === "failed";
              return (
                <li
                  key={row.mediaId}
                  className={`grid grid-cols-[2.75rem_minmax(0,1fr)] items-center gap-3 px-4 py-3 md:grid-cols-[2.75rem_minmax(0,1fr)_5rem_8.5rem_12rem] ${failed ? ROW_TONE_CLASS.failed : ""}`}
                  data-state={row.state}
                  data-transcript-row=""
                >
                  <span
                    className={`grid h-11 w-11 place-items-center rounded-md text-[11px] font-bold ${row.mediaType === "VIDEO" ? "bg-purple-100 text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "bg-amber-100 text-amber-800"}`}
                    aria-hidden
                  >
                    {row.mediaType === "VIDEO" ? "VID" : "AUD"}
                  </span>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold">{row.name}</p>
                    <p className={`text-xs ${failed ? "" : "text-ink-500"}`}>
                      {row.snippet && needle ? <Snippet text={row.snippet} needle={needle} /> : null}
                      {sub ? sub : null}
                      {failed && !sub && !(row.snippet && needle) ? "The transcript couldn't be made." : null}
                    </p>
                    {row.state === "working" ? (
                      <ProgressBar
                        percent={row.summary?.translation?.percent ?? 33}
                        className={`mt-1.5 h-1.5 w-40 max-w-full ${typeof row.summary?.translation?.percent === "number" ? "" : "animate-pulse"}`}
                        aria-hidden
                      />
                    ) : null}
                    <div className="mt-2 flex flex-wrap items-center gap-2 md:hidden">
                      <StatePill row={row} />
                      {length ? <span className="text-xs tabular-nums text-ink-500">{length}</span> : null}
                    </div>
                  </div>
                  <span className="hidden text-sm tabular-nums text-ink-700 md:block">{length}</span>
                  <span className="hidden md:block">
                    <StatePill row={row} />
                  </span>
                  <div className="col-span-2 flex flex-wrap justify-end gap-2 md:col-span-1">
                    {failed ? (
                      <button type="button" className={ROW_BUTTON} disabled={retrying.has(row.mediaId)} onClick={() => void retry(row)}>
                        <RefreshCw className={`h-3.5 w-3.5 ${retrying.has(row.mediaId) ? "animate-spin" : ""}`} aria-hidden />
                        Try again
                      </button>
                    ) : null}
                    {row.state === "ready" || ((row.state === "working" || row.state === "waiting") && row.hasText) ? (
                      <button type="button" className={ROW_BUTTON} onClick={() => onRead({ mediaId: row.mediaId, name: row.name, mediaType: row.mediaType, find: needle || undefined })}>
                        <BookOpen className="h-3.5 w-3.5" aria-hidden />
                        {row.state === "ready" ? "Read" : "Read original"}
                      </button>
                    ) : null}
                    {row.state === "ready" && row.summary ? (
                      <button type="button" className={ROW_BUTTON} onClick={() => docx(row)}>
                        <FileText className="h-3.5 w-3.5" aria-hidden />
                        .docx
                      </button>
                    ) : null}
                  </div>
                </li>
              );
            })}
            {visible.length === 0 ? <li className="px-4 py-6 text-center text-sm text-ink-500">None with this status.</li> : null}
          </ul>
          <div className="flex flex-wrap items-center gap-2 border-t border-line-200 px-4 py-3 text-xs text-ink-500" role="status" aria-live="polite">
            <span>
              Showing {shown.length.toLocaleString("en-IN")} of {visible.length.toLocaleString("en-IN")}
              {loaded?.truncated ? ` · the first ${rows.length.toLocaleString("en-IN")} of ${total.toLocaleString("en-IN")} recordings` : ""}
            </span>
            {!showAll && visible.length > shown.length ? (
              <button type="button" className="min-h-9 font-semibold text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => setShowAll(true)}>
                Show all
              </button>
            ) : loaded?.nextOffset !== null && loaded?.nextOffset !== undefined ? (
              <button type="button" className="min-h-9 font-semibold text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11" data-testid="transcripts-more" disabled={loadingMore} onClick={() => void loadMore()}>
                {loadingMore ? "Loading…" : "Load more recordings"}
              </button>
            ) : null}
            {retryNote ? <span className="ml-auto">{retryNote}</span> : null}
          </div>
        </>
      )}
    </section>
  );
}
