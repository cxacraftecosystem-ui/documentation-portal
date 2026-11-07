"use client";

import { Search } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { TAG_TONE } from "@/components/dataExplorer/FolderContents";
import { LoadFailed, PillMenu, Skeleton } from "@/components/dataExplorer/explorerUi";
import { EmptyState } from "@/components/EmptyState";
import type { DownloadRequest } from "@/lib/dataExplorer/downloads";
import {
  fetchFolderTranscripts,
  fetchTranscriptSummaries,
  languageOptions,
  stateCounts,
  transcriptPillCount,
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

export const TRANSCRIPTS_FIRST = 5;
const SEARCH_DEBOUNCE_MS = 350;
const POLL_MS = 15_000;

export type ReadTarget = { mediaId: string; name: string; mediaType: string | null; startMs?: number | null; find?: string };

type Row = FolderTranscript & { summary: TranscriptSummary | null; state: RowState };

type Loaded = { key: string; items: FolderTranscript[]; total: number; truncated: boolean; nextOffset: number | null };

const BUTTON =
  "min-h-10 rounded-md border border-line-200 bg-card px-3.5 text-[13px] text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60 dark:hover:bg-purple-950/40 pointer-coarse:min-h-11";

const ROW_GRID = "grid-cols-[44px_minmax(0,1fr)_140px_160px_220px] gap-3.5";
const ROW_GRID_MD = "md:grid-cols-[44px_minmax(0,1fr)_140px_160px_220px]";

const PILL = "inline-flex min-h-6 items-center rounded-full px-2 text-[11px] font-semibold";

export function transcriptTitle(name: string): string {
  const stem = name.replace(/\.[A-Za-z0-9]{1,5}$/, "");
  if (!/[_-]/.test(stem) || /\s/.test(stem)) return stem || name;
  return stem
    .split("_")
    .map((part) => part.replace(/-/g, " ").trim())
    .filter(Boolean)
    .join(" · ");
}

function workingLine(summary: TranscriptSummary | null): string {
  if (summary?.status === "TRANSCRIBING") return "Transcribing";
  return "Transcribed · translating to English";
}

function StatePill({ row }: { row: Row }) {
  switch (row.state) {
    case "ready":
      return <span className={`${PILL} bg-success-100 text-success-600 dark:bg-[#123b25] dark:text-green-200`}>✓ Ready</span>;
    case "working":
      return <span className={`${PILL} bg-amber-100 text-amber-800 dark:bg-amber-950/50 dark:text-amber-200`}>{translatingLabel(row.summary)}</span>;
    case "failed":
      return <span className={`${PILL} bg-card text-red-700 dark:text-red-200`}>Failed</span>;
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
  onCount,
  trail
}: {
  path: string;
  folderName: string;
  extra: QueryParams;
  onRead: (target: ReadTarget) => void;
  onDownload: (request: DownloadRequest) => void;
  zipUrl: string;
  onCount?: (count: number) => void;
  trail?: string;
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
        if (!needle && !answer?.truncated) countRef.current?.(answer?.total ?? 0);
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
  const shown = showAll ? visible : visible.slice(0, TRANSCRIPTS_FIRST);
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
  const known = { rows: loaded !== null && !stale, statuses: loaded !== null && !stale && !loading, more: (loaded?.nextOffset ?? null) !== null };

  return (
    <section className="min-w-0" aria-label="Transcripts" data-testid="transcripts-tab">
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 pt-4 pb-3">
        <div className="min-w-0">
          <p className="truncate text-xs text-ink-500">{trail || folderName}</p>
          <h2 className="mt-1 font-display text-2xl font-extrabold text-ink-900">Transcripts</h2>
        </div>
        {total > 0 ? (
          <div className="flex gap-2">
            <button
              type="button"
              className={BUTTON}
              onClick={() => onDownload({ kind: "transcript", label: `Transcripts (.docx) · ${folderName}`, url: apiUrl("/data/transcripts/export", { path, format: "docx", ...(JSON.parse(extraKey) as QueryParams) }), path })}
            >
              Export all (.docx)
            </button>
            <button type="button" className="min-h-10 rounded-md bg-purple-700 px-3.5 text-[13px] font-semibold text-white hover:bg-purple-800 dark:bg-purple-300 dark:text-purple-950" onClick={() => onDownload({ kind: "transcript", label: `Transcripts · ${folderName}`, url: zipUrl, path })}>
              Transcripts (.zip) · {total.toLocaleString("en-IN")}
            </button>
          </div>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-2 px-4 pb-4">
        <label className="relative flex min-w-0 max-w-[520px] flex-[1_1_16rem] items-center">
          <Search className="pointer-events-none absolute left-3 h-4 w-4 text-ink-500" aria-hidden />
          <input
            type="search"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            placeholder="Search inside these transcripts"
            aria-label="Search inside these transcripts"
            className="min-h-11 w-full rounded-md border border-line-200 bg-card pr-3 pl-9 text-sm text-ink-900 outline-hidden placeholder:text-ink-300 focus:border-purple-600"
          />
        </label>
        <div role="group" aria-label="Transcript status" className="flex flex-wrap gap-2">
          {pills.map((pill) => {
            const count = transcriptPillCount(pill.value, counts, known);
            return (
              <button
                key={pill.value}
                type="button"
                aria-pressed={filter === pill.value}
                aria-busy={count === null || undefined}
                data-count-pending={count === null || undefined}
                onClick={() => setFilter(pill.value)}
                className={`inline-flex min-h-10 items-center gap-1 rounded-full px-3.5 text-[13px] transition pointer-coarse:min-h-11 ${filter === pill.value ? "bg-ink-900 text-card" : "border border-line-200 bg-card text-ink-900 hover:border-purple-300"}`}
              >
                {pill.label} {count ?? <span aria-hidden className="inline-block h-3 w-6 animate-pulse rounded-sm bg-line-200" />}
              </button>
            );
          })}
        </div>
        <PillMenu
          label={language ? (languages.find((option) => option.value === language)?.label ?? "Language") : "Language"}
          ariaLabel="Language"
          value={language}
          options={languages}
          onChange={setLanguage}
          className="min-h-10 rounded-full border border-line-200 bg-card px-3.5 text-[13px] text-ink-900 hover:border-purple-300 pointer-coarse:min-h-11"
        />
      </div>

      {error && !loaded ? (
        <div className="px-4 pb-4">
          <LoadFailed title="These transcripts didn't load" onRetry={() => setAttempt((value) => value + 1)}>
            {error}
          </LoadFailed>
        </div>
      ) : loading && !loaded ? (
        <div className="mx-4 mb-4 grid gap-3 rounded-lg border border-line-200 p-4" role="status" aria-label="Loading transcripts">
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
        <div className="px-4 pb-4">
          <EmptyState title={needle ? `Nothing matches “${needle}”` : "No transcripts here yet"} body={needle ? "Try other words." : "Recordings in this folder appear here once they're transcribed."} />
        </div>
      ) : (
        <>
          {error ? (
            <p role="alert" className="mx-4 mb-3 flex flex-wrap items-center gap-2 rounded-md bg-error-100 px-4 py-2 text-sm text-red-900">
              <span className="flex-1">{error}</span>
              <button type="button" className={BUTTON} onClick={() => setAttempt((value) => value + 1)}>
                Try now
              </button>
            </p>
          ) : null}
          <div className="mx-4 overflow-hidden rounded-lg border border-line-200 bg-card">
            <div className={`hidden ${ROW_GRID} border-b border-line-200 px-[18px] py-2.5 text-xs text-ink-500 md:grid`} aria-hidden>
              <span />
              <span>Recording</span>
              <span>Length</span>
              <span>Status</span>
              <span className="text-right">Get</span>
            </div>
            <ul className={`${stale || loading ? "opacity-70" : ""}`} aria-busy={loading || undefined} aria-label="Transcripts list" data-testid="transcript-rows">
              {shown.map((row) => {
                const sub = row.snippet && needle ? null : row.state === "failed" ? row.summary?.statusMessage?.trim() || "The transcript couldn't be made." : row.state === "working" ? workingLine(row.summary) : [languagesLine(row.summary?.languages ?? []), speakersLine(row.summary?.speakerCount ?? 0)].filter(Boolean).join(" · ");
                const length = row.summary?.durationMs ? shortClock(row.summary.durationMs) : "";
                const failed = row.state === "failed";
                const tag = row.mediaType === "VIDEO" ? "VID" : "AUD";
                return (
                  <li
                    key={row.mediaId}
                    className={`grid grid-cols-[44px_minmax(0,1fr)] items-center gap-3.5 border-b px-[18px] py-3.5 last:border-b-0 ${ROW_GRID_MD} ${failed ? "border-red-200 bg-error-100 text-red-900 dark:border-red-900 dark:bg-[#4a1717] dark:text-red-100" : "border-line-200"}`}
                    data-state={row.state}
                    data-transcript-row=""
                  >
                    <span className={`grid h-11 w-11 place-items-center rounded-[10px] text-[11px] font-bold ${failed ? "bg-card text-red-700 dark:text-red-200" : TAG_TONE[tag]}`} aria-hidden>
                      {tag}
                    </span>
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold">{transcriptTitle(row.name)}</p>
                      <p className={`truncate text-xs ${failed ? "text-red-800 dark:text-red-200" : "text-ink-500"}`}>
                        {row.snippet && needle ? <Snippet text={row.snippet} needle={needle} /> : null}
                        {sub ? sub : null}
                        {failed && !sub && !(row.snippet && needle) ? "The transcript couldn't be made." : null}
                      </p>
                      {row.state === "working" ? (
                        <div className="mt-1.5 h-1 max-w-60 overflow-hidden rounded-full bg-progress-track" aria-hidden>
                          <div className={`h-full rounded-full bg-progress-fill ${typeof row.summary?.translation?.percent === "number" ? "" : "animate-pulse"}`} style={{ width: `${row.summary?.translation?.percent ?? 33}%` }} />
                        </div>
                      ) : null}
                      <div className="mt-2 flex flex-wrap items-center gap-2 md:hidden">
                        <StatePill row={row} />
                        {length ? <span className="text-xs tabular-nums">{length}</span> : null}
                      </div>
                    </div>
                    <span className="hidden text-[13px] tabular-nums md:block">{length}</span>
                    <span className="hidden md:block">
                      <StatePill row={row} />
                    </span>
                    <div className="col-span-2 flex flex-wrap justify-end gap-1.5 md:col-span-1">
                      {failed ? (
                        <button type="button" className="min-h-10 rounded-md bg-red-700 px-3.5 text-[13px] font-semibold text-white hover:bg-red-800 disabled:opacity-60" disabled={retrying.has(row.mediaId)} onClick={() => void retry(row)}>
                          Try again
                        </button>
                      ) : null}
                      {row.state === "ready" || ((row.state === "working" || row.state === "waiting") && row.hasText) ? (
                        <button type="button" className={BUTTON} onClick={() => onRead({ mediaId: row.mediaId, name: row.name, mediaType: row.mediaType, find: needle || undefined })}>
                          {row.state === "ready" ? "Read" : "Read original"}
                        </button>
                      ) : null}
                      {row.state === "ready" && row.summary ? (
                        <button type="button" className={BUTTON} onClick={() => docx(row)}>
                          .docx
                        </button>
                      ) : null}
                    </div>
                  </li>
                );
              })}
              {visible.length === 0 ? <li className="px-4 py-6 text-center text-sm text-ink-500">None with this status.</li> : null}
            </ul>
          </div>
          <div className="flex flex-wrap items-center gap-1 px-4 pt-3 pb-4 text-xs text-ink-500" role="status" aria-live="polite">
            <span>
              Showing {shown.length.toLocaleString("en-IN")} of {visible.length.toLocaleString("en-IN")}
              {loaded?.truncated ? ` · the first ${rows.length.toLocaleString("en-IN")} of ${total.toLocaleString("en-IN")} recordings` : ""}
            </span>
            {!showAll && visible.length > shown.length ? (
              <>
                <span aria-hidden>·</span>
                <button type="button" className="font-semibold text-purple-700 underline underline-offset-2 dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => setShowAll(true)}>
                  Show all
                </button>
              </>
            ) : loaded?.nextOffset !== null && loaded?.nextOffset !== undefined ? (
              <>
                <span aria-hidden>·</span>
                <button type="button" className="font-semibold text-purple-700 underline underline-offset-2 dark:text-purple-300 pointer-coarse:min-h-11" data-testid="transcripts-more" disabled={loadingMore} onClick={() => void loadMore()}>
                  {loadingMore ? "Loading…" : "Load more recordings"}
                </button>
              </>
            ) : null}
            {retryNote ? <span className="ml-auto">{retryNote}</span> : null}
          </div>
        </>
      )}
    </section>
  );
}
