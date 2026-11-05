"use client";

import { ChevronLeft, ChevronRight, Expand, Pause, Play, Search, X } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type MutableRefObject } from "react";

import { Spinner } from "@/components/dataExplorer/explorerUi";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { TranscriptMarkdown } from "@/components/transcript/TranscriptMarkdown";
import { fetchTranscriptText, mediaDownloadUrl, mediaPreviewUrl } from "@/lib/dataExplorer/api";
import { sizeOf, type TreeEntry } from "@/lib/dataExplorer/contract";
import type { DownloadRequest } from "@/lib/dataExplorer/downloads";
import { formatBytes } from "@/lib/dataExplorer/plan";
import {
  baseName,
  dayLabel,
  downsample,
  fetchPeaks,
  findHits,
  fractionOf,
  highlightRanges,
  hitBars,
  hitLabel,
  hitMarkers,
  ISOLATE_POLL_MS,
  isolateMedia,
  isolatedPreviewUrl,
  lineAt,
  nextIndex,
  PLAYBACK_RATES,
  prettyTitle,
  quoteLine,
  SEEK_STEP_SECONDS,
  shortClock,
  speakerLanes,
  stepHit,
  talkMinutes,
  talkTime,
  timeLink,
  type FindHit,
  type Peaks,
  type TimedLine
} from "@/lib/dataExplorer/preview";
import { apiUrl } from "@/lib/dataExplorer/wire";
import { TRANSCRIPT_ROUTES, fetchTranscript } from "@/lib/transcript/api";
import { copyText } from "@/lib/transcript/clipboard";
import type { Transcript } from "@/lib/transcript/contract";
import { resolveColorKey, SPEAKER_FILL, SPEAKER_TEXT, speakerStyle, type SpeakerColorKey } from "@/lib/transcript/speakers";

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

type Tab = "original" | "english" | "side" | "details" | "find";

const BTN =
  "inline-flex min-h-10 items-center justify-center gap-1.5 rounded-md border border-line-200 bg-card px-3.5 text-[13px] text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-purple-950/40";

const SQUARE =
  "grid h-10 w-10 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-40 dark:hover:bg-purple-950/40";

const PRIMARY =
  "inline-flex min-h-10 items-center justify-center gap-1.5 rounded-md bg-purple-700 px-3.5 text-[13px] font-semibold text-white transition hover:bg-purple-800 disabled:cursor-not-allowed disabled:opacity-60";

const ROUND =
  "grid shrink-0 place-items-center rounded-full border border-line-200 bg-card text-[13px] text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40";

const CURRENT = "bg-[#f2e9fe] dark:bg-purple-950/60";

const MARK_AMBER = "rounded bg-[#fde68a] px-0.5 text-[#78350f] dark:bg-amber-900 dark:text-amber-200";

const MARK_PURPLE = "rounded bg-[#f2e9fe] px-0.5 text-[#5f2491] dark:bg-purple-900 dark:text-purple-100";


function lessMotion(): boolean {
  if (typeof window === "undefined") return true;
  return document.documentElement.dataset.reducedMotion === "true" || window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}

function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && window.matchMedia?.("(max-width: 767px)").matches === true);
  useEffect(() => {
    const query = window.matchMedia?.("(max-width: 767px)");
    if (!query) return;
    const update = () => setNarrow(query.matches);
    update();
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return narrow;
}

function useWidth<T extends HTMLElement>(): [MutableRefObject<T | null>, number] {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    setWidth(node.clientWidth);
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => setWidth(node.clientWidth));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

function downloadUrl(entry: TreeEntry): string | null {
  if (entry.downloadPath) return apiUrl(entry.downloadPath);
  return entry.mediaId ? mediaDownloadUrl(entry.mediaId) : null;
}

function typeWord(entry: TreeEntry): string {
  const type = entry.mediaType ?? "File";
  return type === "PDF" ? "Document" : type.charAt(0) + type.slice(1).toLowerCase();
}

function typeBadge(entry: TreeEntry): string {
  switch (entry.mediaType) {
    case "AUDIO":
      return "AUD";
    case "VIDEO":
      return "VID";
    case "PDF":
      return "PDF";
    default:
      return "IMG";
  }
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

function usePeaks(mediaId: string | undefined, wanted: boolean, round: number): Peaks | null | undefined {
  const [answer, setAnswer] = useState<{ id: string; peaks: Peaks | null } | null>(null);
  useEffect(() => {
    if (!mediaId || !wanted) return;
    const abort = new AbortController();
    fetchPeaks(mediaId, abort.signal)
      .then((found) => {
        if (!abort.signal.aborted) setAnswer({ id: mediaId, peaks: found });
      })
      .catch(() => {
        if (!abort.signal.aborted) setAnswer((previous) => (previous && previous.id === mediaId ? previous : { id: mediaId, peaks: null }));
      });
    return () => abort.abort();
  }, [mediaId, wanted, round]);
  if (!mediaId || !wanted) return null;
  return answer && answer.id === mediaId ? answer.peaks : undefined;
}

function Highlighted({ text, needle, current, mark }: { text: string; needle: string; current: boolean; mark: string }) {
  const parts = highlightRanges(text, needle);
  const first = parts.findIndex((part) => part.hit);
  return (
    <>
      {parts.map((part, index) => {
        if (!part.hit) return <span key={index}>{part.text}</span>;
        return (
          <mark key={index} className={mark} data-current-hit={current && index === first ? "true" : undefined}>
            {part.text}
          </mark>
        );
      })}
    </>
  );
}

function Waveform({
  peaks,
  progress,
  markers,
  label,
  height,
  onSeek
}: {
  peaks: number[] | null;
  progress: number;
  markers: number[];
  label: string;
  height: number;
  onSeek: (fraction: number) => void;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const count = Math.max(24, Math.floor((width + 2) / 5));
  const bars = useMemo(() => (peaks ? downsample(peaks, count) : null), [peaks, count]);
  const hot = useMemo(() => hitBars(markers, bars?.length ?? 0), [markers, bars]);
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
      className="relative w-full cursor-pointer select-none touch-none"
      style={{ height }}
      data-testid="preview-seek"
    >
      {bars ? (
        <div className="flex h-full items-center gap-[2px] overflow-hidden" aria-hidden>
          {bars.map((value, index) => {
            const played = (index + 0.5) / bars.length <= progress;
            const tone = hot.has(index) ? "bg-amber-400" : played ? "bg-progress-fill" : "bg-progress-track";
            return <i key={index} className={`block w-[3px] shrink-0 rounded-[2px] ${tone}`} style={{ height: `${Math.max(10, Math.round(value * 100))}%` }} />;
          })}
        </div>
      ) : (
        <div className="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-progress-track" aria-hidden>
          <div className="h-full rounded-full bg-progress-fill" style={{ width: `${percent}%` }} />
        </div>
      )}
      {bars ? <span aria-hidden className="pointer-events-none absolute inset-y-0 w-[2px] -translate-x-1/2 bg-ink-900" style={{ left: `${percent}%` }} /> : null}
      {!bars
        ? markers.map((at, index) => <span key={index} aria-hidden className="pointer-events-none absolute -top-1 h-2 w-2 -translate-x-1/2 rounded-full bg-amber-400" style={{ left: `${at * 100}%` }} />)
        : null}
    </div>
  );
}

function SpeakerDot({ colorKey, size = "h-2.5 w-2.5" }: { colorKey: SpeakerColorKey; size?: string }) {
  return <span aria-hidden className={`inline-block shrink-0 rounded-full ${size} ${SPEAKER_FILL}`} style={speakerStyle(colorKey)} />;
}

function PreviewBody({
  entry,
  siblings,
  onNavigate,
  onClose,
  recordHref,
  recordName,
  startMs,
  find,
  onDownload,
  onMinimise,
  closerRef
}: {
  entry: TreeEntry;
  siblings: TreeEntry[];
  onNavigate: (entry: TreeEntry) => void;
  onClose: () => void;
  recordHref: string | null;
  recordName?: string | null;
  startMs?: number | null;
  find?: string;
  onDownload: (request: DownloadRequest) => void;
  onMinimise?: (track: MiniTrack) => void;
  closerRef: MutableRefObject<(() => void) | null>;
}) {
  const tabsId = useId();
  const narrow = useNarrow();
  const loaded = useTranscript(entry);
  const transcript = loaded?.transcript ?? null;
  const lines = useMemo(() => linesOf(transcript), [transcript]);
  const timed = entry.mediaType === "AUDIO" || entry.mediaType === "VIDEO";
  const audio = entry.mediaType === "AUDIO";
  const [peaksRound, setPeaksRound] = useState(0);
  const [isolating, setIsolating] = useState<"idle" | "asking" | "waiting" | "failed">("idle");
  const peakInfo = usePeaks(entry.mediaId, audio, peaksRound);
  const peaks = peakInfo && peakInfo.peaks.length ? peakInfo.peaks : null;
  const isolatedCopy = Boolean(peakInfo?.isolated && entry.mediaId);
  const canIsolate = Boolean(peakInfo?.canIsolate && !peakInfo.isolated && entry.mediaId);
  const mediaRef = useRef<HTMLVideoElement & HTMLAudioElement>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const stageRef = useRef<HTMLDivElement | null>(null);
  const seeded = useRef(false);
  const [position, setPosition] = useState(startMs && startMs > 0 ? startMs : 0);
  const [duration, setDuration] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [frame, setFrame] = useState(false);
  const [rate, setRate] = useState(1);
  const [clean, setClean] = useState(true);
  const [follow, setFollow] = useState(true);
  const [captions, setCaptions] = useState(true);
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
  const [brokenSource, setBrokenSource] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const hasEnglish = lines.some((line) => (line.english ?? "").trim() !== "");
  const originalLabel = transcript?.languages?.find((language) => language.code !== "en")?.label ?? transcript?.languages?.[0]?.label ?? "Transcript";
  const tabs: { value: Tab; label: string }[] = useMemo(() => {
    const out: { value: Tab; label: string }[] = [{ value: "original", label: audio ? originalLabel : "Transcript" }];
    if (hasEnglish) out.push({ value: "english", label: "English" });
    if (audio) {
      if (hasEnglish && !narrow) out.push({ value: "side", label: "Side by side" });
      if (narrow && lines.length) out.push({ value: "find", label: "Find ⌕" });
      return out;
    }
    out.push({ value: "details", label: "Details" });
    return out;
  }, [lines.length, hasEnglish, originalLabel, audio, narrow]);
  const [chosenTab, setTab] = useState<Tab | null>(null);
  const [lastText, setLastText] = useState<Tab>("original");
  const tab: Tab = chosenTab && tabs.some((candidate) => candidate.value === chosenTab) ? chosenTab : (tabs[0]?.value ?? "details");
  const textTab: Tab = tab === "find" ? lastText : tab;
  const track = textTab === "english" ? "english" : textTab === "side" ? "both" : "original";
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
  const fullName = useCallback(
    (key: string | null, name: string) => {
      const known = transcript?.speakers?.find((speaker) => speaker.speakerKey === key);
      return known?.name?.trim() || name;
    },
    [transcript]
  );
  const index = siblings.findIndex((candidate) => candidate.path === entry.path);
  const previous = index >= 0 ? nextIndex(siblings.length, index, -1) : null;
  const next = index >= 0 ? nextIndex(siblings.length, index, 1) : null;
  const size = sizeOf(entry.sizeBytes);
  const fileUrl = downloadUrl(entry);
  const cleanedByTranscript = Boolean(transcript?.audio?.cleaned && transcript.audio.url && transcript.audio.originalUrl);
  const cleanable = isolatedCopy || cleanedByTranscript;
  const baseSource = isolatedCopy
    ? clean
      ? isolatedPreviewUrl(entry.mediaId!)
      : previewSource(entry)
    : cleanedByTranscript
      ? clean
        ? transcript!.audio.url!
        : transcript!.audio.originalUrl!
      : previewSource(entry);
  const broken = brokenSource !== null && brokenSource === baseSource;
  const fallback = entry.url && entry.url !== baseSource ? entry.url : null;
  const source = broken && fallback ? fallback : baseSource;
  const [fragment] = useState(() => (startMs && startMs > 0 ? `#t=${Math.floor(startMs / 1000)}` : ""));
  const seededSource = source ? `${source}${fragment}` : source;
  const reachable = Boolean(transcript && transcript.status === "READY" && entry.mediaId);
  const languageLine = transcript?.languages?.length ? transcript.languages.map((language) => language.label).join(" → ") : null;
  const captured = dayLabel(entry.capturedAt ?? null);
  const position1 = index >= 0 && siblings.length > 1 ? index + 1 : null;
  const title = audio ? prettyTitle(entry.name) : narrow ? baseName(entry.name) : entry.name;
  const meta = (
    narrow
      ? [typeWord(entry), size !== null ? formatBytes(size) : null, audio && total ? shortClock(total) : null, position1 ? `${position1} of ${siblings.length}` : null]
      : [
          typeWord(entry),
          size !== null ? formatBytes(size) : null,
          timed && total ? shortClock(total) : null,
          audio ? languageLine : (recordName ?? null),
          audio && transcript?.speakerCount ? `${transcript.speakerCount} ${transcript.speakerCount === 1 ? "speaker" : "speakers"}` : null,
          captured,
          position1 ? `file ${position1} of ${siblings.length}` : null
        ]
  )
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

  const toggle = () => {
    const element = mediaRef.current;
    if (!element) return;
    if (element.paused) void element.play().catch(() => undefined);
    else element.pause();
  };

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

  const lead = narrow ? 1 : 2;

  const bringUp = useCallback(
    (line: number) => {
      const list = listRef.current;
      const node = list?.querySelector<HTMLElement>(`[data-line="${Math.max(0, line - lead)}"]`);
      if (!list || !node) return;
      list.scrollTo({ top: Math.max(0, node.offsetTop - (parseFloat(getComputedStyle(list).paddingTop) || 0)), behavior: lessMotion() ? "auto" : "smooth" });
    },
    [lead]
  );

  useEffect(() => {
    if (!follow || currentLine < 0) return;
    bringUp(currentLine);
  }, [currentLine, follow, bringUp]);

  const [stepped, setStepped] = useState(false);

  useEffect(() => {
    if (!hit || !stepped) return;
    bringUp(hit.line);
  }, [hit, stepped, bringUp]);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(null), 2500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  useEffect(() => {
    if (isolating !== "waiting") return;
    if (isolatedCopy) {
      setIsolating("idle");
      return;
    }
    const timer = window.setTimeout(() => setPeaksRound((value) => value + 1), ISOLATE_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [isolating, isolatedCopy, peaksRound]);

  const askIsolate = async () => {
    if (!entry.mediaId) return;
    setIsolating("asking");
    try {
      const answer = await isolateMedia(entry.mediaId);
      if (answer.isolated) {
        setIsolating("idle");
        setPeaksRound((value) => value + 1);
      } else setIsolating("waiting");
    } catch {
      setIsolating("failed");
    }
  };

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
    const text = lines.length ? lines.map((line) => `[${shortClock(line.startMs)}] ${line.speakerName}: ${textTab === "english" ? (line.english ?? line.text) : line.text}`).join("\n") : (loaded?.text ?? "");
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

  const minimise = useCallback(() => {
    if (!onMinimise || !entry.mediaId || !source) return false;
    onMinimise({ mediaId: entry.mediaId, name: audio ? prettyTitle(entry.name) : entry.name, src: source, startMs: mediaRef.current ? Math.floor(mediaRef.current.currentTime * 1000) : position });
    return true;
  }, [onMinimise, entry.mediaId, entry.name, source, position, audio]);

  useEffect(() => {
    closerRef.current = () => {
      const media = mediaRef.current;
      if (audio && media && !media.paused && minimise()) return;
      onClose();
    };
    return () => {
      closerRef.current = null;
    };
  }, [closerRef, audio, minimise, onClose]);

  const requestClose = () => (closerRef.current ? closerRef.current() : onClose());

  const pickTab = (value: Tab) => {
    if (value !== "find" && value !== "details") setLastText(value);
    setTab(value);
  };

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, at: number) => {
    const last = tabs.length - 1;
    const target = event.key === "ArrowRight" ? (at === last ? 0 : at + 1) : event.key === "ArrowLeft" ? (at === 0 ? last : at - 1) : event.key === "Home" ? 0 : event.key === "End" ? last : null;
    if (target === null) return;
    event.preventDefault();
    pickTab(tabs[target].value);
    document.getElementById(`${tabsId}-${tabs[target].value}`)?.focus();
  };

  const onFindKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    setStepped(true);
    setHitIndex((value) => stepHit(hits.length, value, event.shiftKey ? -1 : 1));
  };

  const live = currentLine >= 0 ? lines[currentLine] : null;
  const liveText = live ? (textTab === "english" ? (live.english ?? live.text) : live.text) : "";
  const progress = total && total > 0 ? Math.min(1, position / total) : 0;
  const mark = audio ? MARK_AMBER : MARK_PURPLE;
  const cycleRate = () => setRate(PLAYBACK_RATES[(PLAYBACK_RATES.indexOf(rate as (typeof PLAYBACK_RATES)[number]) + 1) % PLAYBACK_RATES.length]);
  const onMeta = (element: HTMLMediaElement) => {
    if (Number.isFinite(element.duration)) setDuration(element.duration * 1000);
    if (!seeded.current && startMs && startMs > 0) {
      element.currentTime = startMs / 1000;
      seeded.current = true;
    }
    element.playbackRate = rate;
  };

  const audioElement =
    audio && source ? (
      <audio
        ref={mediaRef}
        src={seededSource ?? undefined}
        preload="metadata"
        className="hidden"
        onError={() => setBrokenSource(baseSource)}
        onLoadedMetadata={(event) => onMeta(event.currentTarget)}
        onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime * 1000)}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
      />
    ) : null;

  const videoElement =
    entry.mediaType === "VIDEO" && source ? (
      <video
        ref={mediaRef}
        src={seededSource ?? undefined}
        playsInline
        preload="metadata"
        className="absolute inset-0 h-full w-full object-contain"
        onClick={toggle}
        onError={() => setBrokenSource(baseSource)}
        onLoadedMetadata={(event) => onMeta(event.currentTarget)}
        onLoadedData={() => setFrame(true)}
        onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime * 1000)}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
      >
        <track kind="captions" />
      </video>
    ) : null;

  const playIcon = (big: string) => (playing ? <Pause className={big} fill="currentColor" aria-hidden /> : <Play className={big} fill="currentColor" aria-hidden />);

  const findCount = needle.trim() ? hitLabel(hits, hitIndex) : "";

  const findBar = (withIcon: boolean) => (
    <div className="flex min-h-10 items-center gap-2 rounded-[10px] border border-line-200 bg-card px-2.5">
      {withIcon ? <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden /> : null}
      <input
        type="search"
        value={needle}
        onChange={(event) => {
          setNeedle(event.target.value);
          setHitIndex(0);
          setStepped(true);
        }}
        onKeyDown={onFindKey}
        placeholder="Find in transcript"
        aria-label="Find in transcript"
        className="min-w-0 flex-1 border-0 bg-transparent text-[13px] text-ink-900 outline-hidden placeholder:text-ink-300 [&::-webkit-search-cancel-button]:hidden"
      />
      <span className="shrink-0 text-xs tabular-nums text-ink-500" role="status" aria-live="polite">
        {findCount}
      </span>
      <button type="button" className="grid h-8 w-6 place-items-center text-ink-700 disabled:opacity-40" aria-label="Previous match" disabled={!hits.length} onClick={() => {
          setStepped(true);
          setHitIndex((value) => stepHit(hits.length, value, -1));
        }}>
        ↑
      </button>
      <button type="button" className="grid h-8 w-6 place-items-center text-ink-700 disabled:opacity-40" aria-label="Next match" disabled={!hits.length} onClick={() => {
          setStepped(true);
          setHitIndex((value) => stepHit(hits.length, value, 1));
        }}>
        ↓
      </button>
    </div>
  );

  const tabStrip = (height: string, text: string) => (
    <div role="tablist" aria-label="Transcript view" className="flex rounded-md border border-line-200 bg-surface-50 p-[3px]">
      {tabs.map((option, at) => (
        <button
          key={option.value}
          id={`${tabsId}-${option.value}`}
          type="button"
          role="tab"
          aria-selected={tab === option.value}
          aria-controls={`${tabsId}-panel`}
          tabIndex={tab === option.value ? 0 : -1}
          onClick={() => pickTab(option.value)}
          onKeyDown={(event) => onTabKey(event, at)}
          className={`${height} ${text} flex-1 rounded-[9px] transition ${tab === option.value ? "bg-purple-700 font-semibold text-white" : "text-ink-900 hover:bg-card"}`}
        >
          {option.label}
        </button>
      ))}
    </div>
  );

  const followSwitch = (label: string, off: string) => (
    <button type="button" role="switch" aria-checked={follow} onClick={() => setFollow((value) => !value)} className={`text-left text-xs font-semibold ${follow ? "text-green-700 dark:text-green-200" : "text-ink-500"}`}>
      {follow ? `✓ ${label}` : off}
    </button>
  );

  const lineText = (line: TimedLine, isHit: boolean) => {
    const english = line.english ?? "";
    if (textTab === "side")
      return (
        <span className="grid gap-3 md:grid-cols-2">
          <span>
            <Highlighted text={line.text} needle={needle} current={isHit} mark={mark} />
          </span>
          <span className="text-ink-700">{english ? <Highlighted text={english} needle={needle} current={false} mark={mark} /> : <span className="text-ink-500">-</span>}</span>
        </span>
      );
    return <Highlighted text={textTab === "english" ? english || line.text : line.text} needle={needle} current={isHit} mark={mark} />;
  };

  const lineList = (variant: "wide" | "stacked" | "phone") => (
    <div
      ref={listRef}
      className={`relative flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto overscroll-contain ${variant === "wide" ? "px-4 py-2.5" : variant === "phone" ? "px-2" : "p-2"}`}
      role="list"
      aria-label="Transcript"
      tabIndex={0}
      data-testid="preview-lines"
    >
      {lines.map((line, at) => {
        const isCurrent = at === currentLine;
        const isHit = hit?.line === at;
        const colorKey = colorOf(line.speakerKey, line.speakerName);
        return (
          <div key={at} role="listitem" data-line={at} data-current={isCurrent ? "true" : undefined}>
            <button
              type="button"
              onClick={() => seek(line.startMs, true)}
              aria-current={isCurrent ? "true" : undefined}
              className={
                variant === "wide"
                  ? `grid w-full grid-cols-[48px_minmax(0,1fr)] gap-3 lg:grid-cols-[56px_120px_minmax(0,1fr)] lg:gap-3.5 rounded-md px-4 py-3 text-left text-[15px] leading-[1.6] text-ink-900 transition ${isCurrent ? CURRENT : "hover:bg-surface-50"}`
                  : `flex w-full gap-2.5 rounded-md px-3 py-2.5 text-left ${variant === "phone" ? "text-[15px] leading-[1.55]" : "text-sm leading-normal gap-3"} text-ink-900 transition ${isCurrent ? CURRENT : "hover:bg-surface-50"}`
              }
            >
              <span className={`${variant === "wide" ? "pt-[3px]" : variant === "phone" ? "w-10 flex-none pt-0.5" : "w-11 flex-none"} text-xs font-semibold tabular-nums text-purple-700 dark:text-purple-300`}>{shortClock(line.startMs)}</span>
              {variant === "wide" ? (
                <>
                  <span className={`hidden truncate pt-px text-[13px] font-bold lg:block ${SPEAKER_TEXT}`} style={speakerStyle(colorKey)}>
                    {line.speakerName}
                  </span>
                  <span className="min-w-0">
                    <span className={`block text-[13px] font-bold lg:hidden ${SPEAKER_TEXT}`} style={speakerStyle(colorKey)}>
                      {line.speakerName}
                    </span>
                    {lineText(line, isHit)}
                  </span>
                </>
              ) : (
                <span className="min-w-0">
                  <span className={`block text-xs font-bold ${SPEAKER_TEXT}`} style={speakerStyle(colorKey)}>
                    {line.speakerName}
                  </span>
                  {lineText(line, isHit)}
                </span>
              )}
            </button>
          </div>
        );
      })}
    </div>
  );

  const details = (
    <dl className="grid gap-3 p-4 text-sm" data-testid="preview-details">
      {[
        ["Name", entry.originalFilename || entry.name],
        ["Type", typeWord(entry)],
        ["Size", size !== null ? formatBytes(size) : null],
        ["Length", total ? shortClock(total) : null],
        ["Languages", languageLine],
        ["Speakers", transcript?.speakerCount ? String(transcript.speakerCount) : null],
        ["Captured", captured],
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

  const emptyNote = loaded && !lines.length && !loaded.text ? (
    <p className="p-4 text-sm text-ink-500">{loaded.error ?? (transcript && transcript.status !== "READY" ? (transcript.statusMessage ?? "The transcript isn't ready yet.") : "No transcript for this file.")}</p>
  ) : null;

  const textPanel = (variant: "wide" | "stacked" | "phone") => (
    <div id={`${tabsId}-panel`} role="tabpanel" aria-labelledby={`${tabsId}-${tab}`} className="flex min-h-0 min-w-0 flex-1 flex-col">
      {loaded === null ? (
        <div className="p-4">
          <Spinner label="Loading transcript…" />
        </div>
      ) : tab === "details" ? (
        details
      ) : lines.length ? (
        lineList(variant)
      ) : loaded.text ? (
        <div className="min-h-0 flex-1 overflow-y-auto p-4" tabIndex={0} role="region" aria-label={`Transcript of ${entry.name}`}>
          <TranscriptMarkdown text={loaded.text} />
        </div>
      ) : (
        emptyNote
      )}
    </div>
  );

  const status = (
    <>
      <p className="sr-only" role="status" aria-live="polite">
        {copied ?? ""}
      </p>
      {copied ? <p className="text-xs font-semibold text-green-700 dark:text-green-200">{copied}</p> : null}
    </>
  );

  const header = (
    <header className="flex items-center gap-3 border-b border-line-200 px-5 py-3.5">
      <button type="button" className={SQUARE} aria-label="Previous file" disabled={previous === null} onClick={() => previous !== null && onNavigate(siblings[previous])}>
        <ChevronLeft className="h-4 w-4" aria-hidden />
      </button>
      {audio ? <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[10px] bg-[#fef3c7] text-[11px] font-bold text-[#92400e] dark:bg-amber-950 dark:text-amber-200" aria-hidden>{typeBadge(entry)}</span> : null}
      <div className="min-w-0 flex-1">
        <div className="truncate text-base font-bold text-ink-900" title={entry.name}>
          {title}
        </div>
        <div className="truncate text-xs text-ink-500" data-testid="preview-meta">
          {meta}
        </div>
      </div>
      <button type="button" className={SQUARE} aria-label="Next file" disabled={next === null} onClick={() => next !== null && onNavigate(siblings[next])}>
        <ChevronRight className="h-4 w-4" aria-hidden />
      </button>
      {recordHref ? (
        <Link href={recordHref} className={BTN}>
          Open in record
        </Link>
      ) : null}
      {fileUrl ? (
        <button type="button" className={PRIMARY} onClick={save}>
          {size !== null ? `Save · ${formatBytes(size)}` : "Save"}
        </button>
      ) : null}
      <button type="button" className={SQUARE} aria-label="Close" onClick={requestClose}>
        <X className="h-4 w-4" aria-hidden />
      </button>
    </header>
  );

  const phoneHeader = (
    <header className="flex items-center gap-1 px-2 py-2.5">
      <button type="button" className="grid h-11 w-11 shrink-0 place-items-center rounded-md text-ink-900" aria-label="Close" onClick={requestClose}>
        <X className="h-[18px] w-[18px]" aria-hidden />
      </button>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-bold text-ink-900" title={entry.name}>
          {title}
        </div>
        <div className="truncate text-xs text-ink-500" data-testid="preview-meta">
          {meta}
        </div>
      </div>
      {fileUrl ? (
        <button type="button" className={`${PRIMARY} mr-1`} onClick={save}>
          Save
        </button>
      ) : null}
    </header>
  );

  const phoneBar = (
    <div className="flex items-center gap-2 border-t border-line-200 px-4 pb-4 pt-2.5" data-testid="preview-actions">
      <button type="button" className="grid min-h-12 w-12 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-900 disabled:opacity-40" aria-label="Previous file" disabled={previous === null} onClick={() => previous !== null && onNavigate(siblings[previous])}>
        <ChevronLeft className="h-4 w-4" aria-hidden />
      </button>
      {timed && reachable ? (
        <button type="button" className={`${BTN} min-h-12 flex-1`} onClick={() => transcriptFile("docx")}>
          Transcript (.docx)
        </button>
      ) : (
        <span className="flex-1" />
      )}
      {timed ? (
        <button type="button" className={`${BTN} min-h-12 flex-1`} onClick={() => void share()}>
          Share
        </button>
      ) : null}
      <button type="button" className="grid min-h-12 w-12 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-900 disabled:opacity-40" aria-label="Next file" disabled={next === null} onClick={() => next !== null && onNavigate(siblings[next])}>
        <ChevronRight className="h-4 w-4" aria-hidden />
      </button>
    </div>
  );

  const clearControl = (phone: boolean) => {
    if (cleanable)
      return (
        <button
          type="button"
          role="switch"
          aria-checked={clean}
          onClick={switchSource}
          className={
            phone
              ? `min-h-10 rounded-full px-2.5 text-xs ${clean ? "bg-purple-700 font-semibold text-white" : "border border-line-200 bg-card text-ink-900"}`
              : clean
                ? "inline-flex min-h-10 items-center justify-center gap-1.5 rounded-md border border-[#d6b6fb] bg-[#f2e9fe] px-3.5 text-[13px] font-semibold text-[#5f2491] transition hover:bg-purple-100 dark:border-purple-700 dark:bg-purple-950/60 dark:text-purple-100"
                : BTN
          }
        >
          <span aria-hidden>{clean ? "✓ " : ""}</span>
          {phone ? "Clear" : "Clear voices"}
        </button>
      );
    if (canIsolate)
      return (
        <button
          type="button"
          className={phone ? "min-h-10 rounded-full border border-line-200 bg-card px-2.5 text-xs text-ink-900 disabled:opacity-60" : BTN}
          disabled={isolating === "asking" || isolating === "waiting"}
          onClick={() => void askIsolate()}
        >
          {isolating === "waiting" || isolating === "asking" ? "Clearing…" : isolating === "failed" ? "Try clearing again" : "Make a clear-voices copy"}
        </button>
      );
    return null;
  };

  const speaking = live ? `${live.speakerName} speaking` : "";

  if (audio && !narrow) {
    return (
      <div className="flex h-[calc(100dvh-5rem)] flex-col" data-testid="preview-body">
        {audioElement}
        {header}
        <section className="flex flex-col gap-2.5 border-b border-line-200 bg-linear-to-b from-[#faf5ff] to-card px-6 pb-3.5 pt-[18px] dark:from-purple-950/40" data-testid="preview-controls">
          <div className="flex items-center gap-4">
            <button type="button" className={`${ROUND} h-11 w-11`} aria-label={`Back ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position - SEEK_STEP_SECONDS * 1000)}>
              ↺10
            </button>
            <button type="button" className="grid h-14 w-14 shrink-0 place-items-center rounded-full bg-purple-700 text-white transition hover:bg-purple-800" aria-label={playing ? "Pause" : "Play"} onClick={toggle}>
              {playIcon("h-5 w-5")}
            </button>
            <button type="button" className={`${ROUND} h-11 w-11`} aria-label={`Forward ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position + SEEK_STEP_SECONDS * 1000)}>
              10↻
            </button>
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <Waveform peaks={peaks} progress={progress} markers={markers} height={72} label={`Waveform, ${shortClock(position)} of ${shortClock(total)}`} onSeek={(fraction) => total && seek(fraction * total)} />
              <div className="flex justify-between text-xs text-ink-500">
                <span className="tabular-nums">
                  <strong className="font-semibold text-ink-900">{shortClock(position)}</strong> / {shortClock(total)}
                </span>
                {needle.trim() && hits.length ? (
                  <span className="flex items-center gap-1.5">
                    <span aria-hidden className="inline-block h-2.5 w-2.5 rounded-[2px] bg-amber-400" />
                    {hits.length} {hits.length === 1 ? "match" : "matches"} for “{needle.trim()}”
                  </span>
                ) : null}
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <button type="button" className={BTN} aria-label={`Playback speed ${rate}×`} onClick={cycleRate}>
                Speed {rate}×
              </button>
              {clearControl(false)}
            </div>
          </div>
          {speakers.length > 1 ? (
            <div className="flex flex-col gap-1" aria-label="Who speaks when">
              {speakers.map((speaker) => {
                const colorKey = colorOf(speaker.key, speaker.name);
                return (
                  <div key={speaker.key} className="flex items-center gap-2.5 text-xs text-ink-500">
                    <span className={`w-[92px] shrink-0 truncate font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(colorKey)}>
                      {speaker.name}
                    </span>
                    <div className="relative h-2.5 flex-1 rounded bg-surface-100" aria-hidden>
                      {(lanes.get(speaker.key) ?? []).map((span, at) => (
                        <span key={at} className={`absolute inset-y-0 rounded ${SPEAKER_FILL}`} style={{ ...speakerStyle(colorKey), left: `${span.left * 100}%`, width: `${span.width * 100}%` }} />
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : null}
        </section>
        <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_260px] lg:grid-cols-[minmax(0,1fr)_300px]">
          {textPanel("wide")}
          <aside className="flex min-h-0 flex-col gap-3 overflow-y-auto border-l border-line-200 px-4 py-3.5" aria-label="Transcript panel">
            {tabs.length ? tabStrip("min-h-[34px]", "text-xs") : null}
            {lines.length ? findBar(false) : null}
            {speakers.length ? (
              <>
                <div className="text-xs font-semibold uppercase tracking-[.06em] text-ink-500">Speakers</div>
                <ul className="flex flex-col gap-1.5 text-[13px] text-ink-900">
                  {speakers.map((speaker) => (
                    <li key={speaker.key} className="flex items-center gap-2">
                      <SpeakerDot colorKey={colorOf(speaker.key, speaker.name)} />
                      <span className="min-w-0 flex-1 truncate">{fullName(speaker.key, speaker.name)}</span>
                      <span className="text-ink-500 tabular-nums">{talkMinutes(speaker.ms)}</span>
                    </li>
                  ))}
                </ul>
                {followSwitch("Text follows the audio · click any line to jump", "Follow the audio")}
              </>
            ) : null}
            <div className="mt-auto flex flex-col gap-2" data-testid="preview-actions">
              {status}
              {reachable ? (
                <button type="button" className={BTN} onClick={() => transcriptFile("docx")}>
                  Transcript (.docx)
                </button>
              ) : null}
              {reachable && lines.length ? (
                <button type="button" className={BTN} onClick={() => transcriptFile("srt")}>
                  Subtitles (.srt)
                </button>
              ) : null}
              {lines.length ? (
                <button type="button" className={BTN} onClick={() => void copyLine()}>
                  Copy this line with time link
                </button>
              ) : !lines.length && loaded?.text ? (
                <button type="button" className={BTN} onClick={() => void copyAll()}>
                  Copy
                </button>
              ) : null}
            </div>
          </aside>
        </div>
      </div>
    );
  }

  if (audio && narrow) {
    return (
      <div className="flex h-dvh flex-col bg-card" data-testid="preview-body">
        {audioElement}
        {phoneHeader}
        <section className="mx-3 flex flex-col gap-2.5 rounded-[20px] bg-linear-160 from-[#f2e9fe] to-[#fff7ed] p-3.5 dark:from-purple-950/70 dark:to-surface-100" data-testid="preview-controls">
          <Waveform peaks={peaks} progress={progress} markers={markers} height={48} label={`Waveform, ${shortClock(position)} of ${shortClock(total)}`} onSeek={(fraction) => total && seek(fraction * total)} />
          <div className="flex justify-between gap-2 text-xs text-ink-500">
            <strong className="font-semibold tabular-nums text-ink-900">{shortClock(position)}</strong>
            <span className="truncate">{speaking}</span>
            <span className="tabular-nums">{shortClock(total)}</span>
          </div>
          <div className="flex items-center justify-between">
            <button type="button" className="min-h-10 rounded-full border border-line-200 bg-card px-2.5 text-xs text-ink-900" aria-label={`Playback speed ${rate}×`} onClick={cycleRate}>
              {rate}×
            </button>
            <button type="button" className={`${ROUND} h-12 w-12`} aria-label={`Back ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position - SEEK_STEP_SECONDS * 1000)}>
              ↺10
            </button>
            <button type="button" className="grid h-16 w-16 shrink-0 place-items-center rounded-full bg-purple-700 text-white" aria-label={playing ? "Pause" : "Play"} onClick={toggle}>
              {playIcon("h-5 w-5")}
            </button>
            <button type="button" className={`${ROUND} h-12 w-12`} aria-label={`Forward ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position + SEEK_STEP_SECONDS * 1000)}>
              10↻
            </button>
            {clearControl(true) ?? <span className="w-[52px]" aria-hidden />}
          </div>
        </section>
        <div className="flex flex-col gap-2 px-4 pb-1.5 pt-3">
          {tabs.length ? tabStrip("min-h-10", "text-[13px]") : null}
          {tab === "find" ? findBar(true) : null}
          {speakers.length ? (
            <div className="flex gap-1.5 overflow-x-auto [scrollbar-width:none]">
              {speakers.map((speaker) => (
                <span
                  key={speaker.key}
                  className={`inline-flex min-h-7 flex-none items-center gap-[5px] rounded-full px-2.5 text-xs font-semibold ${SPEAKER_TEXT}`}
                  style={{ ...speakerStyle(colorOf(speaker.key, speaker.name)), backgroundColor: "color-mix(in srgb, currentColor 14%, transparent)" }}
                >
                  <SpeakerDot colorKey={colorOf(speaker.key, speaker.name)} size="h-2 w-2" />
                  {speaker.name}
                </span>
              ))}
            </div>
          ) : null}
        </div>
        {textPanel("phone")}
        {status}
        {phoneBar}
      </div>
    );
  }

  if (entry.mediaType === "VIDEO") {
    const subtitle = captions && liveText ? liveText : "";
    const ticks = markers;
    const stage = (phone: boolean) => (
      <section ref={stageRef} className={`relative overflow-hidden bg-[#12101c] ${phone ? "h-[219px] shrink-0" : "min-h-0"}`} data-testid="preview-controls">
        {videoElement}
        {!frame ? <div aria-hidden className="pointer-events-none absolute inset-0 bg-linear-135 from-[#4b3a2e] to-[#1a1520]" /> : null}
        {!playing ? (
          <button type="button" className={`absolute left-1/2 top-1/2 grid -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full bg-white/[.18] text-white ${phone ? "h-[60px] w-[60px]" : "h-[72px] w-[72px]"}`} aria-label="Play" onClick={toggle}>
            <Play className={phone ? "h-[22px] w-[22px]" : "h-[26px] w-[26px]"} fill="currentColor" aria-hidden />
          </button>
        ) : null}
        {subtitle ? (
          <p
            className={
              phone
                ? "pointer-events-none absolute inset-x-3 bottom-[34px] rounded-[10px] bg-[rgba(18,16,28,.78)] px-2.5 py-1.5 text-center text-[13px] text-[#f2f0f9]"
                : "pointer-events-none absolute inset-x-4 bottom-[66px] rounded-[14px] bg-[rgba(18,16,28,.78)] px-3.5 py-2.5 text-center text-[15px] text-[#f2f0f9]"
            }
            aria-hidden
            data-testid="live-subtitle"
          >
            {subtitle}
          </p>
        ) : null}
        {phone ? (
          <div className="absolute inset-x-3 bottom-3 flex items-center gap-2 text-[11px] text-[#cfcbe0]">
            <span className="tabular-nums">{shortClock(position)}</span>
            <VideoBar progress={progress} ticks={[]} height="h-1" onSeek={(fraction) => total && seek(fraction * total)} label={`Position, ${shortClock(position)} of ${shortClock(total)}`} />
            <span className="tabular-nums">{shortClock(total)}</span>
          </div>
        ) : (
          <div className="absolute inset-x-0 bottom-0 flex items-center gap-3.5 bg-[rgba(18,16,28,.9)] px-5 pb-4 pt-3 text-[13px] text-[#cfcbe0]">
            <button type="button" aria-label={playing ? "Pause" : "Play"} onClick={toggle} className="grid h-6 w-6 place-items-center">
              {playIcon("h-4 w-4")}
            </button>
            <span className="tabular-nums">{shortClock(position)}</span>
            <VideoBar progress={progress} ticks={ticks} height="h-1.5" onSeek={(fraction) => total && seek(fraction * total)} label={`Position, ${shortClock(position)} of ${shortClock(total)}`} />
            <span className="tabular-nums">{shortClock(total)}</span>
            <button type="button" aria-label={`Playback speed ${rate}×`} onClick={cycleRate}>
              {rate}×
            </button>
            <button type="button" aria-pressed={captions} aria-label="Subtitles on the video" onClick={() => setCaptions((value) => !value)} className={captions ? "text-white" : "opacity-60"}>
              CC
            </button>
            <button type="button" aria-label="Full screen" onClick={() => void stageRef.current?.requestFullscreen?.().catch(() => undefined)}>
              <Expand className="h-4 w-4" aria-hidden />
            </button>
          </div>
        )}
      </section>
    );
    const legend = (note: string) =>
      speakers.length ? (
        <div className="flex flex-wrap items-center gap-2 text-xs text-ink-500">
          {speakers.map((speaker, at) => (
            <span key={speaker.key} className={`inline-flex items-center gap-2 ${at ? "ml-1.5" : ""}`}>
              <SpeakerDot colorKey={colorOf(speaker.key, speaker.name)} />
              {speaker.name}
            </span>
          ))}
          <span className="ml-auto">{followSwitch(note, "Follow playback")}</span>
        </div>
      ) : null;
    if (narrow) {
      return (
        <div className="flex h-dvh flex-col bg-card" data-testid="preview-body">
          {phoneHeader}
          {stage(true)}
          <div className="flex flex-col gap-2.5 px-4 pb-2 pt-3">
            {tabStrip("min-h-10", "text-[13px]")}
            {tab !== "details" && lines.length ? findBar(true) : null}
            {tab !== "details" ? legend("Follows video") : null}
          </div>
          {textPanel("phone")}
          {status}
          {phoneBar}
        </div>
      );
    }
    return (
      <div className="grid h-[calc(100dvh-5rem)] grid-cols-[minmax(0,1fr)_380px] grid-rows-[auto_minmax(0,1fr)] lg:grid-cols-[minmax(0,1fr)_440px]" data-testid="preview-body">
        <div className="col-span-full">{header}</div>
        {stage(false)}
        <aside className="flex min-h-0 flex-col border-l border-line-200" aria-label="Transcript panel">
          <div className="flex flex-col gap-2.5 border-b border-line-200 px-4 py-3.5">
            {tabStrip("min-h-9", "text-[13px]")}
            {tab !== "details" && lines.length ? findBar(true) : null}
            {tab !== "details" ? legend("Follows playback") : null}
          </div>
          {textPanel("stacked")}
          <div className="flex flex-col gap-2 border-t border-line-200 px-4 py-3" data-testid="preview-actions">
            {status}
            <div className="flex gap-2">
              {reachable ? (
                <button type="button" className={`${BTN} flex-1 whitespace-nowrap px-2`} onClick={() => transcriptFile("docx")}>
                  Transcript (.docx)
                </button>
              ) : null}
              {reachable && lines.length ? (
                <button type="button" className={`${BTN} flex-1 whitespace-nowrap px-2`} onClick={() => transcriptFile("srt")}>
                  Subtitles (.srt)
                </button>
              ) : null}
              {lines.length || loaded?.text ? (
                <button type="button" className={`${BTN} flex-1 whitespace-nowrap px-2`} onClick={() => void copyAll()}>
                  Copy
                </button>
              ) : null}
            </div>
          </div>
        </aside>
      </div>
    );
  }

  const still =
    entry.mediaType === "IMAGE" && source ? (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={source} alt={entry.name} className="max-h-full max-w-full object-contain" onError={() => setBrokenSource(baseSource)} />
    ) : entry.mediaType === "PDF" && source ? (
      <object data={source} type="application/pdf" className="h-full w-full" aria-label={entry.name}>
        <p className="p-6 text-sm text-ink-500">This browser can&apos;t show the PDF here. Save it instead.</p>
      </object>
    ) : (
      <p className="p-6 text-sm text-ink-500">No preview for this file.</p>
    );

  return (
    <div className={`flex flex-col ${narrow ? "h-dvh bg-card" : "h-[calc(100dvh-5rem)]"}`} data-testid="preview-body">
      {narrow ? phoneHeader : header}
      <div className="grid min-h-0 flex-1 place-items-center overflow-hidden bg-surface-50 p-4">{still}</div>
      {broken && !fallback ? <p className="px-5 py-2 text-xs text-red-800 dark:text-red-200">The preview didn&apos;t load.</p> : null}
      {narrow ? phoneBar : null}
    </div>
  );
}

function VideoBar({ progress, ticks, height, onSeek, label }: { progress: number; ticks: number[]; height: string; onSeek: (fraction: number) => void; label: string }) {
  const ref = useRef<HTMLDivElement | null>(null);
  const percent = Math.round(progress * 1000) / 10;
  return (
    <div
      ref={ref}
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(percent)}
      className={`relative ${height} flex-1 cursor-pointer rounded-full bg-[#3a3651]`}
      onClick={(event) => {
        const rect = ref.current?.getBoundingClientRect();
        if (rect) onSeek(fractionOf(event.clientX, rect));
      }}
      onKeyDown={(event) => {
        if (event.key === "ArrowRight") onSeek(Math.min(1, progress + 0.02));
        else if (event.key === "ArrowLeft") onSeek(Math.max(0, progress - 0.02));
        else return;
        event.preventDefault();
      }}
      data-testid="preview-seek"
    >
      <div className="h-full rounded-full bg-[#c090f5]" style={{ width: `${percent}%` }} />
      {ticks.map((at, index) => (
        <span key={index} aria-hidden className="absolute -top-[3px] h-3 w-[3px] bg-[#fde68a]" style={{ left: `${at * 100}%` }} />
      ))}
    </div>
  );
}

export function PreviewDialog({
  entry,
  siblings,
  onNavigate,
  onClose,
  recordHref,
  recordName,
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
  recordName?: string | null;
  startMs?: number | null;
  find?: string;
  onDownload: (request: DownloadRequest) => void;
  onMinimise?: (track: MiniTrack) => void;
}) {
  const closerRef = useRef<(() => void) | null>(null);
  const requestClose = useCallback(() => (closerRef.current ? closerRef.current() : onClose()), [onClose]);
  return (
    <FieldDialog
      open={Boolean(entry)}
      onClose={requestClose}
      showClose={false}
      title={<span className="sr-only">{entry?.name ?? "Preview"}</span>}
      className="max-w-none! overflow-hidden border-0! p-0! shadow-[0_24px_64px_rgba(30,27,46,.3)]! max-md:fixed! max-md:inset-0! max-md:rounded-none! md:w-[calc(100vw-5rem)]! md:rounded-[24px]!"
    >
      {entry ? (
        <PreviewBody
          key={entry.path}
          entry={entry}
          siblings={siblings}
          onNavigate={onNavigate}
          onClose={onClose}
          recordHref={recordHref}
          recordName={recordName}
          startMs={startMs}
          find={find}
          onDownload={onDownload}
          onMinimise={onMinimise}
          closerRef={closerRef}
        />
      ) : null}
    </FieldDialog>
  );
}

