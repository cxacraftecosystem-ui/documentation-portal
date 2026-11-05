"use client";

import {
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  Copy,
  Download,
  ExternalLink,
  FileText,
  Link2,
  Minimize2,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  Search,
  Share2,
  Subtitles
} from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import { Spinner } from "@/components/dataExplorer/explorerUi";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { TranscriptMarkdown } from "@/components/transcript/TranscriptMarkdown";
import { fetchTranscriptText, mediaDownloadUrl, mediaPreviewUrl } from "@/lib/dataExplorer/api";
import { sizeOf, type TreeEntry } from "@/lib/dataExplorer/contract";
import type { DownloadRequest } from "@/lib/dataExplorer/downloads";
import { formatBytes } from "@/lib/dataExplorer/plan";
import {
  downsample,
  fetchPeaks,
  findHits,
  fractionOf,
  highlightRanges,
  hitLabel,
  hitMarkers,
  lineAt,
  nextIndex,
  PLAYBACK_RATES,
  quoteLine,
  SEEK_STEP_SECONDS,
  shortClock,
  speakerLanes,
  stepHit,
  talkMinutes,
  talkTime,
  timeLink,
  type FindHit,
  type TimedLine
} from "@/lib/dataExplorer/preview";
import { apiUrl } from "@/lib/dataExplorer/wire";
import { TRANSCRIPT_ROUTES, fetchTranscript } from "@/lib/transcript/api";
import { copyText } from "@/lib/transcript/clipboard";
import type { Transcript } from "@/lib/transcript/contract";
import { resolveColorKey, SPEAKER_FILL, SPEAKER_TEXT, speakerStyle } from "@/lib/transcript/speakers";

export const PREVIEWABLE = new Set(["IMAGE", "VIDEO", "AUDIO", "PDF"]);

export function canPreview(entry: Pick<TreeEntry, "mediaType" | "mediaId" | "url" | "previewPath">): boolean {
  return PREVIEWABLE.has(entry.mediaType ?? "") && Boolean(entry.mediaId || entry.url);
}

export function previewSource(entry: Pick<TreeEntry, "mediaId" | "url" | "previewPath">): string | null {
  if (entry.previewPath) return `/api${entry.previewPath}`;
  if (entry.mediaId) return mediaPreviewUrl(entry.mediaId);
  return entry.url ?? null;
}

export type MiniTrack = { mediaId: string; name: string; src: string; startMs?: number };

type Tab = "original" | "english" | "side" | "details";

const BUTTON =
  "inline-flex min-h-10 items-center justify-center gap-1.5 rounded-md border border-line-200 bg-card px-3 text-sm font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-purple-950/40 pointer-coarse:min-h-11";

const ICON_BUTTON =
  "grid h-10 w-10 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-40 dark:hover:bg-purple-950/40 pointer-coarse:h-11 pointer-coarse:w-11";

const MARK = "rounded-sm bg-amber-100 px-0.5 text-amber-800";

const MARK_CURRENT = "rounded-sm bg-amber-100 px-0.5 text-amber-800 ring-2 ring-amber-500";

function lessMotion(): boolean {
  if (typeof window === "undefined") return true;
  return document.documentElement.dataset.reducedMotion === "true" || window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}

function downloadUrl(entry: TreeEntry): string | null {
  if (entry.downloadPath) return apiUrl(entry.downloadPath);
  return entry.mediaId ? mediaDownloadUrl(entry.mediaId) : null;
}

function typeWord(entry: TreeEntry): string {
  const type = entry.mediaType ?? "File";
  return type === "PDF" ? "Document" : type.charAt(0) + type.slice(1).toLowerCase();
}

export function linesOf(transcript: Transcript | null): TimedLine[] {
  if (!transcript) return [];
  return [...transcript.turns]
    .sort((a, b) => a.startMs - b.startMs)
    .map((turn) => ({
      startMs: turn.startMs,
      endMs: Math.max(turn.endMs, turn.startMs),
      speakerKey: turn.speakerKey,
      speakerName: turn.speakerName,
      text: turn.text,
      english: turn.translation?.text ?? null
    }));
}

type Loaded = { transcript: Transcript | null; text: string | null; error: string | null };

function useTranscript(entry: TreeEntry): Loaded | null {
  const [answer, setAnswer] = useState<{ id: string; loaded: Loaded } | null>(null);
  const mediaId = entry.mediaId ?? "";
  const timed = entry.mediaType === "AUDIO" || entry.mediaType === "VIDEO";
  useEffect(() => {
    if (!mediaId || !timed) return;
    let cancelled = false;
    (async () => {
      let transcript: Transcript | null = null;
      try {
        transcript = await fetchTranscript(mediaId);
      } catch {
        transcript = null;
      }
      if (transcript && transcript.turns.length) {
        if (!cancelled) setAnswer({ id: mediaId, loaded: { transcript, text: null, error: null } });
        return;
      }
      try {
        const plain = await fetchTranscriptText(mediaId, async () => null);
        if (!cancelled) setAnswer({ id: mediaId, loaded: { transcript, text: plain.content, error: null } });
      } catch (error) {
        if (!cancelled) setAnswer({ id: mediaId, loaded: { transcript, text: null, error: error instanceof Error && error.message ? error.message : "The transcript didn't load." } });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [mediaId, timed]);
  if (!timed || !mediaId) return { transcript: null, text: null, error: null };
  return answer && answer.id === mediaId ? answer.loaded : null;
}

function usePeaks(mediaId: string | undefined, wanted: boolean): number[] | null | undefined {
  const [answer, setAnswer] = useState<{ id: string; peaks: number[] | null } | null>(null);
  useEffect(() => {
    if (!mediaId || !wanted) return;
    const abort = new AbortController();
    fetchPeaks(mediaId, abort.signal)
      .then((found) => {
        if (!abort.signal.aborted) setAnswer({ id: mediaId, peaks: found ? found.peaks : null });
      })
      .catch(() => {
        if (!abort.signal.aborted) setAnswer({ id: mediaId, peaks: null });
      });
    return () => abort.abort();
  }, [mediaId, wanted]);
  if (!mediaId || !wanted) return null;
  return answer && answer.id === mediaId ? answer.peaks : undefined;
}

function Highlighted({ text, needle, current }: { text: string; needle: string; current: boolean }) {
  const parts = highlightRanges(text, needle);
  const first = parts.findIndex((part) => part.hit);
  return (
    <>
      {parts.map((part, index) => {
        if (!part.hit) return <span key={index}>{part.text}</span>;
        return (
          <mark key={index} className={current && index === first ? MARK_CURRENT : MARK}>
            {part.text}
          </mark>
        );
      })}
    </>
  );
}

function Waveform({
  bars,
  progress,
  markers,
  label,
  onSeek
}: {
  bars: number[] | null;
  progress: number;
  markers: number[];
  label: string;
  onSeek: (fraction: number) => void;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const percent = Math.round(progress * 1000) / 10;
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = event.shiftKey ? 0.1 : 0.02;
    if (event.key === "ArrowRight") onSeek(Math.min(1, progress + step));
    else if (event.key === "ArrowLeft") onSeek(Math.max(0, progress - step));
    else if (event.key === "Home") onSeek(0);
    else if (event.key === "End") onSeek(1);
    else return;
    event.preventDefault();
  };
  return (
    <div
      ref={ref}
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(percent)}
      aria-valuetext={label}
      onKeyDown={onKey}
      onClick={(event) => {
        const rect = ref.current?.getBoundingClientRect();
        if (rect) onSeek(fractionOf(event.clientX, rect));
      }}
      className={`relative w-full cursor-pointer select-none touch-none ${bars ? "h-12 sm:h-16" : "h-6"}`}
      data-testid="preview-seek"
    >
      {bars ? (
        <div className="flex h-full items-center gap-px" aria-hidden>
          {bars.map((value, index) => (
            <span
              key={index}
              className={`block flex-1 rounded-full ${index / bars.length < progress ? "bg-progress-fill" : "bg-progress-track"}`}
              style={{ height: `${Math.max(8, Math.round(value * 100))}%` }}
            />
          ))}
        </div>
      ) : (
        <div className="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-progress-track" aria-hidden>
          <div className="h-full rounded-full bg-progress-fill" style={{ width: `${percent}%` }} />
        </div>
      )}
      {markers.map((at, index) => (
        <span key={index} aria-hidden className="pointer-events-none absolute -top-1 h-2 w-2 -translate-x-1/2 rounded-full bg-amber-500" style={{ left: `${at * 100}%` }} />
      ))}
    </div>
  );
}

function SpeakerDot({ colorKey }: { colorKey: ReturnType<typeof resolveColorKey> }) {
  return <span aria-hidden className={`inline-block h-2.5 w-2.5 shrink-0 rounded-full ${SPEAKER_FILL}`} style={speakerStyle(colorKey)} />;
}

function PreviewBody({
  entry,
  siblings,
  onNavigate,
  onClose,
  recordHref,
  startMs,
  find,
  onDownload,
  onMinimise
}: {
  entry: TreeEntry;
  siblings: TreeEntry[];
  onNavigate: (entry: TreeEntry) => void;
  onClose: () => void;
  recordHref: string | null;
  startMs?: number | null;
  find?: string;
  onDownload: (request: DownloadRequest) => void;
  onMinimise?: (track: MiniTrack) => void;
}) {
  const tabsId = useId();
  const loaded = useTranscript(entry);
  const transcript = loaded?.transcript ?? null;
  const lines = useMemo(() => linesOf(transcript), [transcript]);
  const timed = entry.mediaType === "AUDIO" || entry.mediaType === "VIDEO";
  const audio = entry.mediaType === "AUDIO";
  const peaks = usePeaks(entry.mediaId, audio);
  const bars = useMemo(() => (peaks ? downsample(peaks) : null), [peaks]);
  const mediaRef = useRef<HTMLVideoElement & HTMLAudioElement>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const seeded = useRef(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);
  const [clean, setClean] = useState(true);
  const [follow, setFollow] = useState(true);
  const [needle, setNeedle] = useState(find ?? "");
  const [hitIndex, setHitIndex] = useState(0);
  const [seenFind, setSeenFind] = useState(find);
  if (find !== seenFind) {
    setSeenFind(find);
    if (find) {
      setNeedle(find);
      setHitIndex(0);
    }
  }
  const [broken, setBroken] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const hasEnglish = lines.some((line) => (line.english ?? "").trim() !== "");
  const originalLabel = transcript?.languages?.find((language) => language.code !== "en")?.label ?? transcript?.languages?.[0]?.label ?? "Transcript";
  const tabs: { value: Tab; label: string }[] = useMemo(() => {
    const out: { value: Tab; label: string }[] = [];
    if (lines.length || loaded?.text) out.push({ value: "original", label: originalLabel });
    if (hasEnglish) out.push({ value: "english", label: "English" }, { value: "side", label: "Side by side" });
    out.push({ value: "details", label: "Details" });
    return out;
  }, [lines.length, loaded?.text, hasEnglish, originalLabel]);
  const [chosenTab, setTab] = useState<Tab | null>(null);
  const tab: Tab = chosenTab && tabs.some((candidate) => candidate.value === chosenTab) ? chosenTab : (tabs[0]?.value ?? "details");
  const track = tab === "english" ? "english" : tab === "side" ? "both" : "original";
  const hits = useMemo<FindHit[]>(() => findHits(lines, needle, track), [lines, needle, track]);
  const hit = hits.length ? hits[Math.min(hitIndex, hits.length - 1)] : null;
  const total = duration ?? transcript?.durationMs ?? transcript?.audio?.durationMs ?? null;
  const markers = useMemo(() => hitMarkers(lines, hits, total), [lines, hits, total]);
  const currentLine = lineAt(lines, position);
  const speakers = useMemo(() => talkTime(lines), [lines]);
  const lanes = useMemo(() => speakerLanes(lines, total), [lines, total]);
  const colorOf = useCallback(
    (key: string | null, name: string) => {
      const known = transcript?.speakers?.find((speaker) => speaker.speakerKey === key);
      return resolveColorKey(known?.colorKey ?? transcript?.turns.find((turn) => turn.speakerKey === key)?.colorKey ?? null, key, name);
    },
    [transcript]
  );
  const index = siblings.findIndex((candidate) => candidate.path === entry.path);
  const previous = index >= 0 ? nextIndex(siblings.length, index, -1) : null;
  const next = index >= 0 ? nextIndex(siblings.length, index, 1) : null;
  const size = sizeOf(entry.sizeBytes);
  const fileUrl = downloadUrl(entry);
  const cleanable = Boolean(transcript?.audio?.cleaned && transcript.audio.url && transcript.audio.originalUrl);
  const baseSource = cleanable ? (clean ? transcript!.audio.url! : transcript!.audio.originalUrl!) : previewSource(entry);
  const fallback = entry.url && entry.url !== baseSource ? entry.url : null;
  const source = broken && fallback ? fallback : baseSource;
  const [fragment] = useState(() => (startMs && startMs > 0 ? `#t=${Math.floor(startMs / 1000)}` : ""));
  const seededSource = source ? `${source}${fragment}` : source;
  const reachable = Boolean(transcript && transcript.status === "READY" && entry.mediaId);
  const languageLine = transcript?.languages?.length ? transcript.languages.map((language) => language.label).join(" → ") : null;
  const meta = [
    typeWord(entry),
    size !== null ? formatBytes(size) : null,
    total ? shortClock(total) : null,
    languageLine,
    transcript?.speakerCount ? `${transcript.speakerCount} ${transcript.speakerCount === 1 ? "speaker" : "speakers"}` : null,
    index >= 0 && siblings.length > 1 ? `file ${index + 1} of ${siblings.length}` : null
  ]
    .filter(Boolean)
    .join(" · ");

  const seek = useCallback((ms: number, play = false) => {
    const media = mediaRef.current;
    if (!media) return;
    const limit = Number.isFinite(media.duration) && media.duration > 0 ? media.duration : Infinity;
    media.currentTime = Math.max(0, Math.min(limit, ms / 1000));
    setPosition(media.currentTime * 1000);
    if (play) void media.play().catch(() => undefined);
  }, []);

  useEffect(() => {
    const media = mediaRef.current;
    if (media) media.playbackRate = rate;
  }, [rate, source]);

  useEffect(() => {
    const media = mediaRef.current;
    if (!media || !startMs || startMs <= 0 || media.readyState < 1) return;
    media.currentTime = startMs / 1000;
    seeded.current = true;
  }, [startMs]);

  useEffect(() => {
    if (!follow || currentLine < 0) return;
    const node = listRef.current?.querySelector<HTMLElement>(`[data-line="${currentLine}"]`);
    node?.scrollIntoView({ block: "nearest", behavior: lessMotion() ? "auto" : "smooth" });
  }, [currentLine, follow]);

  useEffect(() => {
    if (!hit) return;
    const node = listRef.current?.querySelector<HTMLElement>(`[data-line="${hit.line}"]`);
    node?.scrollIntoView({ block: "center", behavior: lessMotion() ? "auto" : "smooth" });
  }, [hit]);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(null), 2500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const switchSource = () => {
    const media = mediaRef.current;
    const at = media ? media.currentTime : 0;
    const wasPlaying = media ? !media.paused : false;
    setClean((value) => !value);
    requestAnimationFrame(() => {
      const after = mediaRef.current;
      if (!after) return;
      const resume = () => {
        after.currentTime = at;
        if (wasPlaying) void after.play().catch(() => undefined);
        after.removeEventListener("loadedmetadata", resume);
      };
      after.addEventListener("loadedmetadata", resume);
    });
  };

  const here = () => {
    if (typeof window === "undefined") return "";
    return timeLink(window.location.href, mediaRef.current ? mediaRef.current.currentTime * 1000 : position);
  };

  const copyLine = async () => {
    const line = lines[currentLine >= 0 ? currentLine : 0];
    if (!line) return;
    const link = timeLink(window.location.href, line.startMs);
    setCopied((await copyText(quoteLine(line, link))) ? "Line and time link copied." : "Couldn't copy.");
  };

  const copyAll = async () => {
    const text = lines.length ? lines.map((line) => `[${shortClock(line.startMs)}] ${line.speakerName}: ${tab === "english" ? (line.english ?? line.text) : line.text}`).join("\n") : (loaded?.text ?? "");
    if (!text) return;
    setCopied((await copyText(text)) ? "Transcript copied." : "Couldn't copy.");
  };

  const share = async () => {
    const link = here();
    const nav = navigator as Navigator & { share?: (data: ShareData) => Promise<void> };
    if (nav.share) {
      try {
        await nav.share({ title: entry.name, url: link });
        return;
      } catch {
        return;
      }
    }
    setCopied((await copyText(link)) ? "Link copied." : "Couldn't copy.");
  };

  const save = () => {
    if (!fileUrl) return;
    onDownload({ kind: "file", label: entry.name, url: fileUrl, mediaId: entry.mediaId ?? null, bytes: size, path: entry.path });
  };

  const transcriptFile = (format: "docx" | "srt") => {
    if (!entry.mediaId) return;
    onDownload({
      kind: "transcript",
      label: `${entry.name} · ${format === "docx" ? "Transcript (.docx)" : "Subtitles (.srt)"}`,
      url: apiUrl(TRANSCRIPT_ROUTES.download(entry.mediaId, format, format === "docx" ? "both" : "original")),
      mediaId: entry.mediaId
    });
  };

  const minimise = () => {
    if (!onMinimise || !entry.mediaId || !source) return;
    onMinimise({ mediaId: entry.mediaId, name: entry.name, src: source, startMs: mediaRef.current ? Math.floor(mediaRef.current.currentTime * 1000) : position });
    onClose();
  };

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, at: number) => {
    const last = tabs.length - 1;
    const target = event.key === "ArrowRight" ? (at === last ? 0 : at + 1) : event.key === "ArrowLeft" ? (at === 0 ? last : at - 1) : event.key === "Home" ? 0 : event.key === "End" ? last : null;
    if (target === null) return;
    event.preventDefault();
    setTab(tabs[target].value);
    document.getElementById(`${tabsId}-${tabs[target].value}`)?.focus();
  };

  const onFindKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    setHitIndex((value) => stepHit(hits.length, value, event.shiftKey ? -1 : 1));
  };

  const live = currentLine >= 0 ? lines[currentLine] : null;
  const liveText = live ? (tab === "english" ? (live.english ?? live.text) : live.text) : "";

  const media =
    entry.mediaType === "IMAGE" && source ? (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={source} alt={entry.name} className="max-h-[70vh] w-auto object-contain" onError={() => setBroken(true)} />
    ) : entry.mediaType === "PDF" && source ? (
      <object data={source} type="application/pdf" className="h-[70vh] w-full" aria-label={entry.name}>
        <p className="p-6 text-sm text-ink-500">This browser can&apos;t show the PDF here. Save it instead.</p>
      </object>
    ) : timed && source ? (
      <div className={`relative w-full ${audio ? "" : "bg-black"}`}>
        {audio ? (
          <audio
            ref={mediaRef}
            src={seededSource ?? undefined}
            preload="metadata"
            className="hidden"
            onError={() => setBroken(true)}
            onLoadedMetadata={(event) => {
              const element = event.currentTarget;
              if (Number.isFinite(element.duration)) setDuration(element.duration * 1000);
              if (!seeded.current && startMs && startMs > 0) {
                element.currentTime = startMs / 1000;
                seeded.current = true;
              }
              element.playbackRate = rate;
            }}
            onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime * 1000)}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
          />
        ) : (
          <video
            ref={mediaRef}
            src={seededSource ?? undefined}
            controls
            playsInline
            preload="metadata"
            className="max-h-[60vh] w-full bg-black"
            onError={() => setBroken(true)}
            onLoadedMetadata={(event) => {
              const element = event.currentTarget;
              if (Number.isFinite(element.duration)) setDuration(element.duration * 1000);
              if (!seeded.current && startMs && startMs > 0) {
                element.currentTime = startMs / 1000;
                seeded.current = true;
              }
              element.playbackRate = rate;
            }}
            onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime * 1000)}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
          >
            <track kind="captions" />
          </video>
        )}
        {!audio && liveText ? (
          <p className="pointer-events-none absolute inset-x-4 bottom-14 mx-auto w-fit max-w-[90%] rounded-md bg-black/70 px-3 py-1.5 text-center text-sm leading-6 text-white" aria-hidden data-testid="live-subtitle">
            {liveText}
          </p>
        ) : null}
      </div>
    ) : (
      <p className="p-6 text-sm text-ink-500">No preview for this file.</p>
    );

  const progress = total && total > 0 ? Math.min(1, position / total) : 0;
  const speaking = live ? `${live.speakerName} speaking` : null;

  const controls = timed ? (
    <div className="grid gap-3" data-testid="preview-controls">
      <div className="flex flex-wrap items-center gap-2 sm:flex-nowrap">
        <div className="order-2 flex items-center gap-2 sm:order-1">
          <button type="button" className={ICON_BUTTON} aria-label={`Back ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position - SEEK_STEP_SECONDS * 1000)}>
            <RotateCcw className="h-4 w-4" aria-hidden />
          </button>
          <button
            type="button"
            className="grid h-12 w-12 shrink-0 place-items-center rounded-full bg-purple-700 text-white transition hover:bg-purple-800"
            aria-label={playing ? "Pause" : "Play"}
            onClick={() => {
              const element = mediaRef.current;
              if (!element) return;
              if (element.paused) void element.play().catch(() => undefined);
              else element.pause();
            }}
          >
            {playing ? <Pause className="h-5 w-5" aria-hidden /> : <Play className="h-5 w-5" aria-hidden />}
          </button>
          <button type="button" className={ICON_BUTTON} aria-label={`Forward ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position + SEEK_STEP_SECONDS * 1000)}>
            <RotateCw className="h-4 w-4" aria-hidden />
          </button>
        </div>
        <div className="order-1 grid w-full min-w-0 gap-1 sm:order-2 sm:flex-1">
          <Waveform bars={audio ? bars : null} progress={progress} markers={markers} label={`Position, ${shortClock(position)} of ${shortClock(total)}`} onSeek={(fraction) => total && seek(fraction * total)} />
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-ink-500">
            <span className="tabular-nums">
              <strong className="font-semibold text-ink-900">{shortClock(position)}</strong> / {shortClock(total)}
            </span>
            {speaking ? <span className="sm:hidden">{speaking}</span> : null}
            {needle.trim() && hits.length ? (
              <span className="flex items-center gap-1.5">
                <span aria-hidden className="h-2 w-2 rounded-full bg-amber-500" />
                {hits.length} {hits.length === 1 ? "match" : "matches"} for “{needle.trim()}”
              </span>
            ) : null}
          </div>
        </div>
        <div className="order-3 flex items-center gap-2">
          <button
            type="button"
            className={BUTTON}
            aria-label={`Playback speed ${rate}×`}
            onClick={() => setRate(PLAYBACK_RATES[(PLAYBACK_RATES.indexOf(rate as (typeof PLAYBACK_RATES)[number]) + 1) % PLAYBACK_RATES.length])}
          >
            <span className="hidden sm:inline">Speed</span> {rate}×
          </button>
          {cleanable ? (
            <button type="button" role="switch" aria-checked={clean} className={`${BUTTON} ${clean ? "border-purple-300 bg-purple-50 text-purple-800 dark:bg-purple-950/40 dark:text-purple-200" : ""}`} onClick={switchSource}>
              Clear voices
            </button>
          ) : null}
          {onMinimise ? (
            <button type="button" className={ICON_BUTTON} aria-label="Keep playing while browsing" onClick={minimise}>
              <Minimize2 className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
      </div>
      {audio && speakers.length > 1 ? (
        <div className="hidden gap-1.5 sm:grid" aria-label="Who speaks when">
          {speakers.map((speaker) => (
            <div key={speaker.key} className="flex items-center gap-3 text-xs text-ink-500">
              <span className={`w-24 shrink-0 truncate font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(colorOf(speaker.key, speaker.name))}>
                {speaker.name}
              </span>
              <div className="relative h-2.5 flex-1 rounded-sm bg-surface-50" aria-hidden>
                {(lanes.get(speaker.key) ?? []).map((span, at) => (
                  <span key={at} className={`absolute inset-y-0 rounded-sm ${SPEAKER_FILL}`} style={{ ...speakerStyle(colorOf(speaker.key, speaker.name)), left: `${span.left * 100}%`, width: `${span.width * 100}%` }} />
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  ) : null;

  const findBar =
    lines.length && tab !== "details" ? (
      <div className="flex items-center gap-2">
        <label className="relative flex min-w-0 flex-1 items-center">
          <Search className="pointer-events-none absolute left-3 h-3.5 w-3.5 text-ink-300" aria-hidden />
          <input
            type="search"
            value={needle}
            onChange={(event) => {
              setNeedle(event.target.value);
              setHitIndex(0);
            }}
            onKeyDown={onFindKey}
            placeholder="Find in transcript"
            aria-label="Find in transcript"
            className="field-input py-1.5 pl-8 text-sm"
          />
        </label>
        <span className="shrink-0 text-xs tabular-nums text-ink-500" role="status" aria-live="polite">
          {needle.trim() ? hitLabel(hits, hitIndex) : ""}
        </span>
        <button type="button" className={ICON_BUTTON} aria-label="Previous match" disabled={!hits.length} onClick={() => setHitIndex((value) => stepHit(hits.length, value, -1))}>
          <ChevronUp className="h-4 w-4" aria-hidden />
        </button>
        <button type="button" className={ICON_BUTTON} aria-label="Next match" disabled={!hits.length} onClick={() => setHitIndex((value) => stepHit(hits.length, value, 1))}>
          <ChevronDown className="h-4 w-4" aria-hidden />
        </button>
      </div>
    ) : null;

  const legend =
    speakers.length ? (
      <div className="grid gap-2">
        <span className="field-label">Speakers</span>
        <ul className="flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-ink-700 lg:grid lg:gap-1.5">
          {speakers.map((speaker) => (
            <li key={speaker.key} className="flex items-center gap-2" style={speakerStyle(colorOf(speaker.key, speaker.name))}>
              <SpeakerDot colorKey={colorOf(speaker.key, speaker.name)} />
              <span className="min-w-0 flex-1 truncate">{speaker.name}</span>
              <span className="text-xs tabular-nums text-ink-500">{talkMinutes(speaker.ms)}</span>
            </li>
          ))}
        </ul>
        <button
          type="button"
          role="switch"
          aria-checked={follow}
          onClick={() => setFollow((value) => !value)}
          className={`inline-flex min-h-9 w-fit items-center gap-2 rounded-full border px-3 text-xs font-semibold transition pointer-coarse:min-h-11 ${
            follow ? "border-purple-300 bg-purple-50 text-purple-800 dark:bg-purple-950/40 dark:text-purple-200" : "border-line-200 bg-card text-ink-700"
          }`}
        >
          <span aria-hidden className={`h-2 w-2 rounded-full ${follow ? "bg-purple-700 dark:bg-purple-300" : "bg-ink-300"}`} />
          Follows playback
        </button>
      </div>
    ) : null;

  const lineList = (
    <div ref={listRef} className="grid max-h-[46vh] gap-1 overflow-y-auto overscroll-contain pr-1 lg:max-h-[52vh]" role="list" aria-label="Transcript" tabIndex={0} data-testid="preview-lines">
      {lines.map((line, at) => {
        const isCurrent = at === currentLine;
        const isHit = hit?.line === at;
        const english = line.english ?? "";
        const colorKey = colorOf(line.speakerKey, line.speakerName);
        return (
          <div key={at} role="listitem" data-line={at} data-current={isCurrent ? "true" : undefined}>
            <button
              type="button"
              onClick={() => seek(line.startMs, true)}
              aria-current={isCurrent ? "true" : undefined}
              className={`grid w-full grid-cols-[3rem_minmax(0,1fr)] gap-3 rounded-md px-3 py-2.5 text-left text-sm leading-6 transition ${
                isCurrent ? "bg-purple-50 dark:bg-purple-950/50" : "hover:bg-surface-50"
              }`}
            >
              <span className="pt-0.5 text-xs font-semibold tabular-nums text-purple-700 dark:text-purple-300">{shortClock(line.startMs)}</span>
              <span className="min-w-0">
                <span className={`block text-xs font-bold ${SPEAKER_TEXT}`} style={speakerStyle(colorKey)}>
                  {line.speakerName}
                </span>
                {tab === "side" ? (
                  <span className="grid gap-2 sm:grid-cols-2">
                    <span className="text-ink-900">
                      <Highlighted text={line.text} needle={needle} current={isHit} />
                    </span>
                    <span className="text-ink-700">{english ? <Highlighted text={english} needle={needle} current={false} /> : <span className="text-ink-500">-</span>}</span>
                  </span>
                ) : (
                  <span className="text-ink-900">
                    <Highlighted text={tab === "english" ? english || line.text : line.text} needle={needle} current={isHit} />
                  </span>
                )}
              </span>
            </button>
          </div>
        );
      })}
    </div>
  );

  const details = (
    <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-1">
      {[
        ["Name", entry.originalFilename || entry.name],
        ["Type", typeWord(entry)],
        ["Size", size !== null ? formatBytes(size) : null],
        ["Length", total ? shortClock(total) : null],
        ["Languages", languageLine],
        ["Speakers", transcript?.speakerCount ? String(transcript.speakerCount) : null],
        ["Review", entry.reviewStatus ? entry.reviewStatus.charAt(0) + entry.reviewStatus.slice(1).toLowerCase().replace(/_/g, " ") : null],
        ["Folder", entry.path.split("/").slice(0, -1).join(" / ") || null]
      ]
        .filter((pair): pair is [string, string] => Boolean(pair[1]))
        .map(([label, value]) => (
          <div key={label}>
            <dt className="text-xs text-ink-500">{label}</dt>
            <dd className="mt-0.5 break-words text-ink-900">{value}</dd>
          </div>
        ))}
    </dl>
  );

  const tabPanel =
    tab === "details" ? (
      details
    ) : lines.length ? (
      lineList
    ) : loaded?.text ? (
      <div className="max-h-[52vh] overflow-y-auto rounded-md border border-line-200 bg-surface-50 p-4" tabIndex={0} role="region" aria-label={`Transcript of ${entry.name}`}>
        <TranscriptMarkdown text={loaded.text} />
      </div>
    ) : null;

  const side = timed ? (
    <aside className="grid min-w-0 content-start gap-3 lg:border-l lg:border-line-200 lg:pl-5" aria-label="Transcript panel">
      {loaded === null ? (
        <Spinner label="Loading transcript…" />
      ) : (
        <>
          <div role="tablist" aria-label="Transcript view" className="flex gap-1 rounded-lg border border-line-200 bg-surface-50 p-1">
            {tabs.map((option, at) => (
              <button
                key={option.value}
                id={`${tabsId}-${option.value}`}
                type="button"
                role="tab"
                aria-selected={tab === option.value}
                aria-controls={`${tabsId}-panel`}
                tabIndex={tab === option.value ? 0 : -1}
                onClick={() => setTab(option.value)}
                onKeyDown={(event) => onTabKey(event, at)}
                className={`min-h-9 flex-1 rounded-md px-2 text-xs font-semibold transition pointer-coarse:min-h-11 sm:text-sm ${
                  tab === option.value ? "bg-purple-700 text-white" : "text-ink-700 hover:bg-card"
                }`}
              >
                {option.label}
              </button>
            ))}
          </div>
          {findBar}
          {tab !== "details" ? legend : null}
          <div id={`${tabsId}-panel`} role="tabpanel" aria-labelledby={`${tabsId}-${tab}`} className="min-w-0">
            {tabPanel}
            {!lines.length && !loaded.text ? (
              <p className="text-sm text-ink-500">{loaded.error ?? (transcript && transcript.status !== "READY" ? (transcript.statusMessage ?? "The transcript isn't ready yet.") : "No transcript for this file.")}</p>
            ) : null}
          </div>
        </>
      )}
    </aside>
  ) : null;

  return (
    <div className="mt-3 grid gap-4" data-testid="preview-body">
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className={ICON_BUTTON} aria-label="Previous file" disabled={previous === null} onClick={() => previous !== null && onNavigate(siblings[previous])}>
          <ChevronLeft className="h-4 w-4" aria-hidden />
        </button>
        <p className="min-w-0 flex-1 text-xs text-ink-500" data-testid="preview-meta">
          {meta}
        </p>
        <button type="button" className={ICON_BUTTON} aria-label="Next file" disabled={next === null} onClick={() => next !== null && onNavigate(siblings[next])}>
          <ChevronRight className="h-4 w-4" aria-hidden />
        </button>
      </div>
      <div className={timed ? "grid gap-5 lg:grid-cols-[minmax(0,1fr)_26rem]" : "grid"}>
        <div className="grid min-w-0 content-start gap-4">
          <div className={`grid place-items-center overflow-hidden rounded-lg ${audio ? "" : "bg-surface-50"}`}>{media}</div>
          {broken && !fallback ? <p className="text-xs text-red-800">The preview didn&apos;t load.</p> : null}
          {controls}
        </div>
        {side}
      </div>
      <p className="sr-only" role="status" aria-live="polite">
        {copied ?? ""}
      </p>
      {copied ? <p className="text-xs font-semibold text-success-600">{copied}</p> : null}
      <PreviewActions
        entry={entry}
        recordHref={recordHref}
        size={size}
        canSave={Boolean(fileUrl)}
        onSave={save}
        transcriptFiles={reachable}
        timedLines={lines.length > 0}
        onTranscript={transcriptFile}
        onCopyAll={copyAll}
        onCopyLine={copyLine}
        onShare={share}
        hasText={lines.length > 0 || Boolean(loaded?.text)}
      />
    </div>
  );
}

function PreviewActions({
  entry,
  recordHref,
  size,
  canSave,
  onSave,
  transcriptFiles,
  timedLines,
  onTranscript,
  onCopyAll,
  onCopyLine,
  onShare,
  hasText
}: {
  entry: TreeEntry;
  recordHref: string | null;
  size: number | null;
  canSave: boolean;
  onSave: () => void;
  transcriptFiles: boolean;
  timedLines: boolean;
  onTranscript: (format: "docx" | "srt") => void;
  onCopyAll: () => void;
  onCopyLine: () => void;
  onShare: () => void;
  hasText: boolean;
}) {
  const items: ReactNode[] = [];
  if (recordHref)
    items.push(
      <Link key="record" href={recordHref} className={BUTTON}>
        <ExternalLink className="h-4 w-4" aria-hidden />
        Open in record
      </Link>
    );
  if (transcriptFiles)
    items.push(
      <button key="docx" type="button" className={BUTTON} onClick={() => onTranscript("docx")}>
        <FileText className="h-4 w-4" aria-hidden />
        Transcript (.docx)
      </button>
    );
  if (transcriptFiles && timedLines)
    items.push(
      <button key="srt" type="button" className={BUTTON} onClick={() => onTranscript("srt")}>
        <Subtitles className="h-4 w-4" aria-hidden />
        Subtitles (.srt)
      </button>
    );
  if (hasText)
    items.push(
      <button key="copy" type="button" className={BUTTON} onClick={onCopyAll}>
        <Copy className="h-4 w-4" aria-hidden />
        Copy
      </button>
    );
  if (timedLines)
    items.push(
      <button key="line" type="button" className={`${BUTTON} max-sm:hidden`} onClick={onCopyLine}>
        <Link2 className="h-4 w-4" aria-hidden />
        Copy this line with time link
      </button>
    );
  if (entry.mediaType === "AUDIO" || entry.mediaType === "VIDEO")
    items.push(
      <button key="share" type="button" className={`${BUTTON} sm:hidden`} onClick={onShare}>
        <Share2 className="h-4 w-4" aria-hidden />
        Share
      </button>
    );
  if (canSave)
    items.push(
      <button key="save" type="button" className="field-button" onClick={onSave}>
        <Download className="h-4 w-4" aria-hidden />
        {size !== null ? `Save · ${formatBytes(size)}` : "Save"}
      </button>
    );
  return (
    <div className="flex flex-wrap justify-end gap-2 border-t border-line-200 pt-4 max-sm:grid max-sm:grid-cols-2 max-sm:[&>*]:w-full" data-testid="preview-actions">
      {items}
    </div>
  );
}

export function PreviewDialog({
  entry,
  siblings,
  onNavigate,
  onClose,
  recordHref,
  startMs,
  find,
  onDownload,
  onMinimise
}: {
  entry: TreeEntry | null;
  siblings: TreeEntry[];
  onNavigate: (entry: TreeEntry) => void;
  onClose: () => void;
  recordHref: string | null;
  startMs?: number | null;
  find?: string;
  onDownload: (request: DownloadRequest) => void;
  onMinimise?: (track: MiniTrack) => void;
}) {
  const timed = entry?.mediaType === "AUDIO" || entry?.mediaType === "VIDEO";
  return (
    <FieldDialog
      open={Boolean(entry)}
      onClose={onClose}
      title={<span className="block truncate">{entry?.name ?? "Preview"}</span>}
      className={`${timed ? "max-w-6xl!" : "max-w-3xl!"} max-sm:min-h-[calc(100dvh-2rem)] max-sm:p-3!`}
    >
      {entry ? (
        <PreviewBody
          key={entry.path}
          entry={entry}
          siblings={siblings}
          onNavigate={onNavigate}
          onClose={onClose}
          recordHref={recordHref}
          startMs={startMs}
          find={find}
          onDownload={onDownload}
          onMinimise={onMinimise}
        />
      ) : null}
    </FieldDialog>
  );
}
